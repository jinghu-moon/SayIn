use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
use uuid::Uuid;
use voicelink_receiver::{
    Error, Result,
    ledger::{InputOutcome, Ledger, Protector, Session},
    protocol::{Operation, State, Submit, TextSource},
};
use zeroize::Zeroizing;

// Deliberately test-only framing: production always uses Windows DPAPI.
struct TestProtector;
impl Protector for TestProtector {
    fn protect(&self, input: &[u8]) -> Result<Vec<u8>> {
        Ok(input.iter().map(|byte| byte ^ 0xa5).collect())
    }
    fn unprotect(&self, input: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
        Ok(Zeroizing::new(
            input.iter().map(|byte| byte ^ 0xa5).collect(),
        ))
    }
}
static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture {
    directory: PathBuf,
    key: Vec<u8>,
    device: String,
    token: Vec<u8>,
    target: String,
}
impl Fixture {
    fn new() -> Self {
        let directory = std::env::temp_dir().join(format!(
            "voicelink-ledger-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&directory).unwrap();
        Self {
            directory,
            key: vec![7; 32],
            device: Uuid::new_v4().to_string(),
            token: vec![9; 32],
            target: Uuid::new_v4().to_string(),
        }
    }
    fn path(&self) -> PathBuf {
        self.directory.join("ledger.sqlite3")
    }
    fn open(&self) -> Ledger {
        Ledger::open(
            &self.path(),
            Zeroizing::new(self.key.clone()),
            Box::new(TestProtector),
        )
        .unwrap()
    }
    fn ready(&self) -> (Ledger, Session) {
        self.ready_named("")
    }
    fn ready_named(&self, name: &str) -> (Ledger, Session) {
        let mut ledger = self.open();
        ledger
            .provision_device(&self.device, &self.token, name.into())
            .unwrap();
        let session = ledger.authenticate(&self.device, &self.token).unwrap();
        let state = ledger.bind_target(&self.target).unwrap();
        ledger.resume(&state.state_rev, false).unwrap();
        (ledger, session)
    }
    fn submit(&self, session: &Session, text: &str) -> Submit {
        Submit::new(
            session.id().into(),
            Uuid::new_v4().to_string(),
            self.target.clone(),
            Operation::Text {
                text: text.into(),
                src: TextSource::Ime,
            },
        )
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.directory);
    }
}
fn code<T>(result: Result<T>, expected: &str) {
    assert!(matches!(result,Err(Error::Protocol(code)) if code==expected));
}

#[test]
fn missing_schema_or_key_check_is_not_silently_recreated() {
    for change in [
        "DROP TABLE receipts",
        "DELETE FROM ledger_meta WHERE key='key-check'",
        "PRAGMA user_version=99",
    ] {
        let fixture = Fixture::new();
        drop(fixture.ready());
        let database = rusqlite::Connection::open(fixture.path()).unwrap();
        database.execute_batch(change).unwrap();
        drop(database);
        let result = Ledger::open(
            &fixture.path(),
            Zeroizing::new(fixture.key.clone()),
            Box::new(TestProtector),
        );
        code(
            result,
            if change.contains("99") {
                "LEDGER_SCHEMA_UNSUPPORTED"
            } else {
                "LEDGER_IDENTITY_LOST"
            },
        );
        let database = rusqlite::Connection::open(fixture.path()).unwrap();
        if change.contains("DROP") {
            let count: u32 = database
                .query_row(
                    "SELECT COUNT(*) FROM sqlite_schema WHERE name='receipts'",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(count, 0, "opening must not rebuild a lost dedup namespace");
        }
    }
}

#[test]
fn accepted_is_durable_and_restarts_held_without_automatic_input() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "中文😀");
    let accepted = ledger.submit(&session, &submit).unwrap();
    assert_eq!(accepted.st, State::Accepted);
    drop(ledger);
    let mut ledger = fixture.open();
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    let receipt = ledger.query(&session, &submit.id).unwrap().unwrap();
    assert_eq!(receipt.order, accepted.order);
    assert_eq!(receipt.st, State::Held);
    assert_eq!(receipt.reason.as_deref(), Some("restart_confirmation"));
    assert!(ledger.prepare_next().unwrap().is_none());
}

#[test]
fn executing_restart_is_uncertain_and_remote_resume_cannot_clear_review() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "already possibly typed");
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    drop(ledger);
    let mut ledger = fixture.open();
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    assert_eq!(
        ledger.query(&session, &submit.id).unwrap().unwrap().st,
        State::Uncertain
    );
    let state = ledger.bind_target(&fixture.target).unwrap();
    code(ledger.resume(&state.state_rev, false), "STATE_CONFLICT");
    ledger.resume(&state.state_rev, true).unwrap();
    assert!(
        ledger.prepare_next().unwrap().is_none(),
        "review never retries uncertain input"
    );
    drop(ledger);
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    let payload: Option<Vec<u8>> = database
        .query_row(
            "SELECT payload FROM receipts WHERE id=?1",
            [&submit.id],
            |row| row.get(0),
        )
        .unwrap();
    assert!(payload.is_none());
}

#[test]
fn terminal_dedup_precedes_stale_target_and_conflicting_id_is_rejected() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "literal {ENTER}");
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    ledger
        .finish(&job, InputOutcome::Injected { method: "unicode" })
        .unwrap();
    ledger.bind_target(&Uuid::new_v4().to_string()).unwrap();
    assert_eq!(
        ledger.submit(&session, &submit).unwrap().st,
        State::Injected
    );
    let mut conflict = submit.clone();
    conflict.op = Operation::Text {
        text: "different".into(),
        src: TextSource::Ime,
    };
    code(ledger.submit(&session, &conflict), "ID_CONFLICT");
    assert!(ledger.prepare_next().unwrap().is_none());
}

#[test]
fn tombstone_blocks_late_submit_and_is_device_scoped() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "late");
    let cancelled = ledger.cancel(&session, &submit.id).unwrap();
    assert_eq!(cancelled.st, State::Cancelled);
    assert_eq!(
        ledger.submit(&session, &submit).unwrap().st,
        State::Cancelled
    );
    let other = Uuid::new_v4().to_string();
    ledger
        .provision_device(&other, &[3; 32], String::new())
        .unwrap();
    let other = ledger.authenticate(&other, &[3; 32]).unwrap();
    assert!(ledger.query(&other, &submit.id).unwrap().is_none());
}

#[test]
fn session_replacement_and_revocation_fence_old_senders() {
    let fixture = Fixture::new();
    let (mut ledger, old) = fixture.ready();
    assert_eq!(
        ledger.snapshot().unwrap().device_ids,
        vec![fixture.device.clone()]
    );
    let submit = fixture.submit(&old, "no stale send");
    code(
        ledger.authenticate(&fixture.device, &[4; 32]),
        "UNAUTHORIZED",
    );
    let current = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    code(ledger.submit(&old, &submit), "STALE_SESSION");
    let mut submit = submit;
    submit.session_id = current.id().into();
    ledger.submit(&current, &submit).unwrap();
    ledger.revoke(&fixture.device).unwrap();
    assert!(ledger.snapshot().unwrap().device_ids.is_empty());
    code(ledger.submit(&current, &submit), "STALE_SESSION");
    code(
        ledger.authenticate(&fixture.device, &fixture.token),
        "UNAUTHORIZED",
    );
    assert!(ledger.prepare_next().unwrap().is_none());
}

#[test]
fn held_head_blocks_later_text_and_release_keeps_original_fingerprint() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let first = fixture.submit(&session, "first");
    let second = fixture.submit(&session, "second");
    ledger.submit(&session, &first).unwrap();
    ledger.submit(&session, &second).unwrap();
    let next_target = Uuid::new_v4().to_string();
    let state = ledger.bind_target(&next_target).unwrap();
    ledger.resume(&state.state_rev, false).unwrap();
    assert!(ledger.prepare_next().unwrap().is_none());
    let rev = ledger.state().unwrap().state_rev;
    let released = ledger
        .release(&session, &first.id, &next_target, &rev)
        .unwrap();
    assert_eq!(released.st, State::Accepted);
    assert_eq!(ledger.submit(&session, &first).unwrap().st, State::Accepted);
    let job = ledger.prepare_next().unwrap().unwrap();
    assert_eq!(job.id(), first.id);
    assert_eq!(job.target(), next_target);
    assert!(ledger.mark_executing(&job).unwrap());
    ledger
        .finish(&job, InputOutcome::Injected { method: "unicode" })
        .unwrap();
    assert!(
        ledger.prepare_next().unwrap().is_none(),
        "second remains held instead of being skipped"
    );
}

#[test]
fn cancel_or_pause_during_reservation_prevents_execution_marker() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "cancel reserved");
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger.cancel(&session, &submit.id).unwrap();
    assert!(!ledger.mark_executing(&job).unwrap());
    let submit = fixture.submit(&session, "pause reserved");
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger.pause().unwrap();
    assert!(!ledger.mark_executing(&job).unwrap());
    assert_eq!(
        ledger.query(&session, &submit.id).unwrap().unwrap().st,
        State::Held
    );
}

#[test]
fn uncertain_pauses_queue_and_finish_cannot_be_committed_twice() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "partial");
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    let receipt = ledger
        .finish(
            &job,
            InputOutcome::Uncertain {
                reason: "partial_input",
            },
        )
        .unwrap();
    assert_eq!(receipt.st, State::Uncertain);
    assert!(ledger.state().unwrap().paused);
    code(
        ledger.finish(&job, InputOutcome::Injected { method: "unicode" }),
        "INVALID_EXECUTION_STATE",
    );
    code(
        ledger.submit(&session, &fixture.submit(&session, "no more")),
        "STORAGE_UNAVAILABLE",
    );
}

#[test]
fn sqlite_is_wal_full_single_owner_and_wrong_key_does_not_reopen_identity() {
    let fixture = Fixture::new();
    let ledger = fixture.open();
    code(
        Ledger::open(
            &fixture.path(),
            Zeroizing::new(fixture.key.clone()),
            Box::new(TestProtector),
        ),
        "LEDGER_IN_USE",
    );
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    let mode: String = database
        .pragma_query_value(None, "journal_mode", |row| row.get(0))
        .unwrap();
    assert_eq!(mode, "wal");
    drop(database);
    drop(ledger);
    code(
        Ledger::open(
            &fixture.path(),
            Zeroizing::new(vec![8; 32]),
            Box::new(TestProtector),
        ),
        "LEDGER_KEY_MISMATCH",
    );
    drop(fixture.open());
}

#[test]
fn failed_final_commit_never_reports_success_and_recovers_uncertain() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "side effect already happened");
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    database.execute_batch("CREATE TRIGGER fail_final BEFORE UPDATE OF state ON receipts WHEN NEW.state='injected' BEGIN SELECT RAISE(ABORT,'simulated fsync boundary failure'); END;").unwrap();
    assert!(
        ledger
            .finish(&job, InputOutcome::Injected { method: "unicode" })
            .is_err()
    );
    code(
        ledger.submit(&session, &fixture.submit(&session, "blocked")),
        "STORAGE_UNAVAILABLE",
    );
    drop(database);
    drop(ledger);
    let mut ledger = fixture.open();
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    assert_eq!(
        ledger.query(&session, &submit.id).unwrap().unwrap().st,
        State::Uncertain
    );
}

#[test]
fn failed_accept_transaction_allocates_no_receipt_and_can_retry_same_id() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "not yet accepted");
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    database.execute_batch("CREATE TRIGGER fail_accept BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT,'simulated storage failure'); END;").unwrap();
    assert!(ledger.submit(&session, &submit).is_err());
    assert!(ledger.query(&session, &submit.id).unwrap().is_none());
    database.execute_batch("DROP TRIGGER fail_accept;").unwrap();
    assert_eq!(
        ledger.submit(&session, &submit).unwrap().st,
        State::Accepted
    );
}

#[test]
fn abrupt_child() {
    let Some(directory) = std::env::var_os("VOICELINK_CRASH_FIXTURE") else {
        return;
    };
    let directory = PathBuf::from(directory);
    let fixture = Fixture {
        directory: directory.clone(),
        key: vec![7; 32],
        token: vec![9; 32],
        device: "2b9d9d91-10b0-45b9-ab2b-cbf08673fbf7".into(),
        target: "21c5469b-23da-446c-99ab-ea786a0ebc4e".into(),
    };
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "crash boundary");
    ledger.submit(&session, &submit).unwrap();
    fs::write(directory.join("id"), &submit.id).unwrap();
    if std::env::var_os("VOICELINK_CRASH_EXECUTING").is_some() {
        let job = ledger.prepare_next().unwrap().unwrap();
        assert!(ledger.mark_executing(&job).unwrap());
    }
    std::process::exit(23); // No Drop/close/checkpoint: this is the crash boundary.
}

#[test]
fn unclean_process_exit_preserves_accepted_and_executing_boundaries() {
    for executing in [false, true] {
        let fixture = Fixture::new();
        let mut child = std::process::Command::new(std::env::current_exe().unwrap());
        child
            .args(["--exact", "abrupt_child", "--nocapture"])
            .env("VOICELINK_CRASH_FIXTURE", &fixture.directory);
        if executing {
            child.env("VOICELINK_CRASH_EXECUTING", "1");
        }
        assert_eq!(child.status().unwrap().code(), Some(23));
        let id = fs::read_to_string(fixture.directory.join("id")).unwrap();
        let mut ledger = fixture.open();
        let session = ledger
            .authenticate("2b9d9d91-10b0-45b9-ab2b-cbf08673fbf7", &fixture.token)
            .unwrap();
        let receipt = ledger.query(&session, &id).unwrap().unwrap();
        assert_eq!(
            receipt.st,
            if executing {
                State::Uncertain
            } else {
                State::Held
            }
        );
        assert!(ledger.prepare_next().unwrap().is_none());
    }
}

#[test]
fn same_scope_resume_never_releases_old_text_at_a_potentially_new_cursor() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let operation = fixture.submit(&session, "old cursor");
    ledger.submit(&session, &operation).unwrap();
    let paused = ledger.pause().unwrap();
    let resumed = ledger.resume(&paused.state_rev, false).unwrap();
    assert!(!resumed.paused);
    assert_eq!(
        ledger.query(&session, &operation.id).unwrap().unwrap().st,
        State::Held
    );
    assert!(ledger.prepare_next().unwrap().is_none());
    ledger
        .release(&session, &operation.id, &fixture.target, &resumed.state_rev)
        .unwrap();
    assert_eq!(ledger.prepare_next().unwrap().unwrap().id(), operation.id);
}

#[test]
fn protected_history_retains_original_target_and_terminal_text_across_restart() {
    use voicelink_receiver::history::{HistoryQuery, TargetApplication};
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready_named("我的手机 Phone");
    let submit = fixture.submit(&session, "中文 original text — keep unchanged");
    ledger.submit(&session, &submit).unwrap();
    ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    let target = TargetApplication::new(
        "C:\\Tools\\Editor.exe".into(),
        "Draft — Editor".into(),
        42,
        123,
    );
    assert!(
        ledger
            .mark_executing_at(&job, Some(target.clone()))
            .unwrap()
    );
    ledger
        .finish(&job, InputOutcome::Injected { method: "test" })
        .unwrap();
    let page = ledger.history(&HistoryQuery::default()).unwrap();
    assert_eq!(
        page.entries.len(),
        1,
        "dedup must not create history duplicates"
    );
    assert_eq!(page.entries[0].target, Some(target.clone()));
    assert!(page.entries[0].received_at.is_some());
    assert!(page.entries[0].executed_at >= page.entries[0].received_at);
    ledger.revoke(&fixture.device).unwrap();
    drop(ledger);
    let ledger = fixture.open();
    let page = ledger.history(&HistoryQuery::default()).unwrap();
    assert_eq!(page.entries[0].target, Some(target));
    assert_eq!(page.entries[0].source_name, "我的手机 Phone");
    assert_eq!(page.entries[0].receipt.st, State::Injected);
    assert!(
        matches!(&page.entries[0].operation, Some(Operation::Text {text,..}) if text == "中文 original text — keep unchanged")
    );
    let mut query = HistoryQuery::default();
    query.search = "editor.exe".into();
    assert_eq!(ledger.history(&query).unwrap().entries.len(), 1);
    query.search = "我的手机 phone".into();
    assert_eq!(ledger.history(&query).unwrap().entries.len(), 1);
    query.state = Some(State::Failed);
    assert!(ledger.history(&query).unwrap().entries.is_empty());
}

#[test]
fn history_pending_has_no_fabricated_destination_and_keyset_is_exclusive() {
    use voicelink_receiver::history::HistoryQuery;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let first = fixture.submit(&session, "same original");
    let second = fixture.submit(&session, "same original");
    ledger.submit(&session, &first).unwrap();
    ledger.submit(&session, &second).unwrap();
    let page = ledger.history(&HistoryQuery::default()).unwrap();
    assert_eq!(page.entries.len(), 2);
    assert!(
        page.entries
            .iter()
            .all(|e| e.target.is_none() && e.executed_at.is_none())
    );
    let mut query = HistoryQuery::default();
    query.before = Some(page.entries[0].receipt.order.parse().unwrap());
    let older = ledger.history(&query).unwrap();
    assert_eq!(older.entries.len(), 1);
    assert_eq!(older.entries[0].receipt.id, first.id);
    drop(ledger);
    let ledger = fixture.open();
    let recovered = ledger.history(&HistoryQuery::default()).unwrap();
    assert!(
        recovered
            .entries
            .iter()
            .all(|e| e.receipt.st == State::Held && e.operation.is_some())
    );
}

#[test]
fn missing_installed_history_fails_without_changing_receipts() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    ledger
        .submit(&session, &fixture.submit(&session, "persist"))
        .unwrap();
    drop(ledger);
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    database
        .execute_batch("DROP TABLE message_history")
        .unwrap();
    drop(database);
    let result = Ledger::open(
        &fixture.path(),
        Zeroizing::new(fixture.key.clone()),
        Box::new(TestProtector),
    );
    assert!(result.is_err());
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    let state: String = database
        .query_row("SELECT state FROM receipts", [], |r| r.get(0))
        .unwrap();
    assert_eq!(
        state, "accepted",
        "corrupt history must be detected before crash recovery writes"
    );
}

#[test]
fn additive_history_upgrade_preserves_device_and_receipt_identity() {
    use voicelink_receiver::history::HistoryQuery;
    for version in [1, 2, 3, 4] {
        let fixture = Fixture::new();
        let (mut ledger, session) = fixture.ready();
        let message = fixture.submit(&session, "existing pending original");
        let accepted = ledger.submit(&session, &message).unwrap();
        drop(ledger);
        let database = rusqlite::Connection::open(fixture.path()).unwrap();
        database
            .execute_batch("ALTER TABLE message_history DROP COLUMN source_name;")
            .unwrap();
        if version < 4 {
            database.execute_batch("DROP INDEX history_received; DROP INDEX history_deleted; ALTER TABLE message_history DROP COLUMN deleted_at; DROP TABLE history_policy;").unwrap();
        }
        if version < 3 {
            database
                .execute_batch("ALTER TABLE devices DROP COLUMN display_name")
                .unwrap();
        }
        if version == 1 {
            database
                .execute_batch("DROP TABLE message_history")
                .unwrap();
        }
        database
            .pragma_update(None, "user_version", version)
            .unwrap();
        let identity: (Vec<u8>, Vec<u8>) = database
            .query_row(
                "SELECT d.token_mac,r.payload_mac FROM devices d JOIN receipts r ON d.id=r.device",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        drop(database);
        let mut ledger = fixture.open();
        let restored = ledger
            .authenticate(&fixture.device, &fixture.token)
            .unwrap();
        let receipt = ledger.query(&restored, &message.id).unwrap().unwrap();
        assert_eq!(receipt.order, accepted.order);
        assert_eq!(receipt.st, State::Held);
        let page = ledger.history(&HistoryQuery::default()).unwrap();
        assert_eq!(page.entries.len(), 1);
        assert_eq!(page.entries[0].device, fixture.device);
        assert!(page.entries[0].source_name.is_empty());
        assert!(
            matches!(&page.entries[0].operation, Some(Operation::Text { text, .. }) if text == "existing pending original")
        );
        if version == 1 {
            assert!(
                page.entries[0].received_at.is_none(),
                "old timestamps must not be invented"
            );
        }
        drop(ledger);
        let database = rusqlite::Connection::open(fixture.path()).unwrap();
        let installed: u32 = database
            .pragma_query_value(None, "user_version", |r| r.get(0))
            .unwrap();
        assert_eq!(installed, 5);
        let unchanged: (Vec<u8>, Vec<u8>) = database
            .query_row(
                "SELECT d.token_mac,r.payload_mac FROM devices d JOIN receipts r ON d.id=r.device",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(identity, unchanged);
    }
}

#[test]
fn missing_installed_device_name_fails_before_recovery() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    ledger
        .submit(&session, &fixture.submit(&session, "do not mutate"))
        .unwrap();
    drop(ledger);
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    database
        .execute_batch("ALTER TABLE devices DROP COLUMN display_name")
        .unwrap();
    drop(database);
    assert!(
        Ledger::open(
            &fixture.path(),
            Zeroizing::new(fixture.key.clone()),
            Box::new(TestProtector)
        )
        .is_err()
    );
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    let state: String = database
        .query_row("SELECT state FROM receipts", [], |r| r.get(0))
        .unwrap();
    assert_eq!(state, "accepted");
}

#[test]
fn authenticated_rename_preserves_identity_pause_dedup_and_historical_name() {
    use voicelink_receiver::history::HistoryQuery;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready_named("M2012K11AC");
    let message = fixture.submit(&session, "original before rename");
    let receipt = ledger.submit(&session, &message).unwrap();
    ledger.pause().unwrap();
    let before = ledger.state().unwrap();
    code(
        ledger.authenticate_named(&fixture.device, &[8; 32], "Intruder"),
        "UNAUTHORIZED",
    );
    code(
        ledger.authenticate_named(&fixture.device, &fixture.token, "\n"),
        "INVALID_NAME",
    );
    code(
        ledger.authenticate_named(&fixture.device, &fixture.token, &"😀".repeat(65)),
        "INVALID_NAME",
    );
    assert_eq!(
        ledger.snapshot().unwrap().device_names[&fixture.device],
        "M2012K11AC"
    );
    let refreshed = ledger
        .authenticate_named(&fixture.device, &fixture.token, "Redmi K40")
        .unwrap();
    let after = ledger.state().unwrap();
    assert_eq!(before.state_rev, after.state_rev);
    assert_eq!(before.paused, after.paused);
    assert_eq!(before.target, after.target);
    assert_eq!(before.active_device_id, after.active_device_id);
    assert_eq!(
        ledger.snapshot().unwrap().device_names[&fixture.device],
        "Redmi K40"
    );
    let history = ledger.history(&HistoryQuery::default()).unwrap();
    assert_eq!(history.entries[0].source_name, "M2012K11AC");
    let mut duplicate = message.clone();
    duplicate.session_id = refreshed.id().into();
    assert_eq!(
        ledger.submit(&refreshed, &duplicate).unwrap().order,
        receipt.order
    );
    ledger
        .submit(&refreshed, &fixture.submit(&refreshed, "new after rename"))
        .unwrap();
    assert_eq!(
        ledger.history(&HistoryQuery::default()).unwrap().entries[0].source_name,
        "Redmi K40"
    );
    drop(ledger);
    let mut ledger = fixture.open();
    assert_eq!(
        ledger.snapshot().unwrap().device_names[&fixture.device],
        "Redmi K40"
    );
    assert_eq!(
        ledger.history(&HistoryQuery::default()).unwrap().entries[1].source_name,
        "M2012K11AC"
    );
    ledger.revoke(&fixture.device).unwrap();
    code(
        ledger.authenticate_named(&fixture.device, &fixture.token, "Revoked"),
        "UNAUTHORIZED",
    );
}

#[test]
fn history_pagination_is_bounded_and_does_not_skip_records() {
    use voicelink_receiver::history::HistoryQuery;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    ledger
        .submit(&session, &fixture.submit(&session, "original search body"))
        .unwrap();
    // Bulk fixture construction bypasses the intentional live token bucket.
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    for _ in 0..299 {
        database.execute("INSERT INTO receipts(device,id,payload_mac,original_target,state) SELECT device,?1,payload_mac,original_target,'injected' FROM receipts WHERE accept_order=1",[Uuid::new_v4().to_string()]).unwrap();
        database.execute("INSERT INTO message_history(accept_order,body,received_at) SELECT (SELECT MAX(accept_order) FROM receipts),body,received_at FROM message_history WHERE accept_order=1",[]).unwrap();
    }
    drop(database);
    let mut query = HistoryQuery::default();
    let mut orders = Vec::new();
    loop {
        let page = ledger.history(&query).unwrap();
        assert!(page.entries.len() <= 30);
        orders.extend(
            page.entries
                .iter()
                .map(|e| e.receipt.order.parse::<u32>().unwrap()),
        );
        if page.next.is_none() {
            break;
        }
        query.before = page.next;
    }
    assert_eq!(orders, (1..=300).rev().collect::<Vec<_>>());
    query = HistoryQuery::default();
    query.search = "not present".into();
    let page = ledger.history(&query).unwrap();
    assert!(page.entries.is_empty());
    assert_eq!(
        page.next,
        Some(45),
        "search must yield after 256 candidates"
    );
    query.before = page.next;
    assert!(ledger.history(&query).unwrap().next.is_none());
}

#[test]
fn history_detects_swapped_body_and_target_context() {
    use voicelink_receiver::history::{HistoryQuery, TargetApplication};
    for column in ["body", "target"] {
        let fixture = Fixture::new();
        let (mut ledger, session) = fixture.ready();
        for text in ["first message", "second message"] {
            ledger
                .submit(&session, &fixture.submit(&session, text))
                .unwrap();
            let job = ledger.prepare_next().unwrap().unwrap();
            ledger
                .mark_executing_at(
                    &job,
                    Some(TargetApplication::new(
                        "C:\\Test.exe".into(),
                        text.into(),
                        42,
                        123,
                    )),
                )
                .unwrap();
            ledger
                .finish(&job, InputOutcome::Injected { method: "test" })
                .unwrap();
        }
        let database = rusqlite::Connection::open(fixture.path()).unwrap();
        database.execute_batch(&format!("UPDATE message_history SET {column}=(SELECT {column} FROM message_history WHERE accept_order=1) WHERE accept_order=2")).unwrap();
        assert!(matches!(
            ledger.history(&HistoryQuery::default()),
            Err(Error::Protocol("HISTORY_IDENTITY_MISMATCH"))
        ));
    }
}

#[test]
fn uncertain_history_keeps_body_and_target_but_never_retries_on_restart() {
    use voicelink_receiver::history::{HistoryQuery, TargetApplication};
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    ledger
        .submit(&session, &fixture.submit(&session, "possibly partial"))
        .unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger
        .mark_executing_at(
            &job,
            Some(TargetApplication::new(
                "C:\\Test.exe".into(),
                "Document".into(),
                42,
                123,
            )),
        )
        .unwrap();
    drop(ledger);
    let mut ledger = fixture.open();
    let page = ledger.history(&HistoryQuery::default()).unwrap();
    assert_eq!(page.entries[0].receipt.st, State::Uncertain);
    assert!(page.entries[0].target.is_some());
    assert!(page.entries[0].operation.is_some());
    assert_eq!(
        ledger.state().unwrap().pause_reason.as_deref(),
        Some("outcome_review_required")
    );
    assert!(ledger.prepare_next().unwrap().is_none());
}

#[test]
fn review_history_finds_exact_uncertain_text_beyond_latest_page_and_respects_deletion() {
    use voicelink_receiver::history::HistoryQuery;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    assert!(ledger.review_history().unwrap().is_none());
    let mut last = String::new();
    for text in ["previously reviewed", "needs review now"] {
        let message = fixture.submit(&session, text);
        last = ledger.submit(&session, &message).unwrap().order;
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.mark_executing(&job).unwrap();
        ledger
            .finish(&job, InputOutcome::Uncertain { reason: "partial" })
            .unwrap();
        if text == "previously reviewed" {
            let state = ledger.state().unwrap();
            ledger.resume(&state.state_rev, true).unwrap();
        }
    }
    for _ in 0..35 {
        // Exercise the real accept path within its 20/s token bucket.
        std::thread::sleep(std::time::Duration::from_millis(55));
        let later = fixture.submit(&session, "later held text");
        ledger.submit(&session, &later).unwrap();
        ledger.cancel(&session, &later.id).unwrap();
    }
    assert!(
        !ledger
            .history(&HistoryQuery::default())
            .unwrap()
            .entries
            .iter()
            .any(|e| e.receipt.st == State::Uncertain)
    );
    let review = ledger.review_history().unwrap().unwrap();
    assert_eq!(review.receipt.order, last);
    assert!(
        matches!(review.operation, Some(Operation::Text { text, .. }) if text == "needs review now")
    );
    ledger.delete_history(last.parse().unwrap()).unwrap();
    assert!(
        ledger.review_history().unwrap().is_none(),
        "never substitute an older reviewed message"
    );
    assert_eq!(
        ledger.state().unwrap().pause_reason.as_deref(),
        Some("outcome_review_required")
    );
}

#[test]
fn explicit_pause_survives_restart_for_automatic_startup_policy() {
    let fixture = Fixture::new();
    let (mut ledger, _) = fixture.ready();
    ledger.pause().unwrap();
    drop(ledger);
    let ledger = fixture.open();
    assert_eq!(
        ledger.state().unwrap().pause_reason.as_deref(),
        Some("paused")
    );
}

#[test]
fn deleting_history_retains_deduplication_and_undo_restores_the_original() {
    use voicelink_receiver::history::HistoryQuery;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let submit = fixture.submit(&session, "原文 {ENTER} English");
    let accepted = ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger.mark_executing(&job).unwrap();
    ledger
        .finish(&job, InputOutcome::Injected { method: "fixture" })
        .unwrap();
    let order = accepted.order.parse().unwrap();
    assert!(ledger.delete_history(order).unwrap());
    assert!(
        ledger
            .history(&HistoryQuery::default())
            .unwrap()
            .entries
            .is_empty()
    );
    assert_eq!(
        ledger.query(&session, &submit.id).unwrap().unwrap().st,
        State::Injected
    );
    assert_eq!(
        ledger.submit(&session, &submit).unwrap().order,
        accepted.order
    );
    assert!(ledger.prepare_next().unwrap().is_none());
    assert!(ledger.restore_history(order).unwrap());
    let page = ledger.history(&HistoryQuery::default()).unwrap();
    assert!(
        matches!(&page.entries[0].operation,Some(Operation::Text{text,..})if text=="原文 {ENTER} English")
    );
    assert!(!ledger.delete_history(i64::MAX).unwrap());
    assert!(!ledger.restore_history(i64::MAX).unwrap());
}

#[test]
fn saving_can_be_disabled_without_blocking_input_or_forgetting_receipts() {
    use voicelink_receiver::history::{HistoryPolicy, HistoryQuery};
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    ledger
        .set_history_policy(HistoryPolicy::new(false, Some(30)).unwrap())
        .unwrap();
    let submit = fixture.submit(&session, "do not retain a history copy");
    let accepted = ledger.submit(&session, &submit).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger.mark_executing(&job).unwrap();
    ledger
        .finish(&job, InputOutcome::Injected { method: "fixture" })
        .unwrap();
    assert!(
        ledger
            .history(&HistoryQuery::default())
            .unwrap()
            .entries
            .is_empty()
    );
    assert_eq!(
        ledger.query(&session, &submit.id).unwrap().unwrap().st,
        State::Injected
    );
    assert_eq!(
        ledger.submit(&session, &submit).unwrap().order,
        accepted.order
    );
    drop(ledger);
    let ledger = fixture.open();
    assert_eq!(
        ledger.history_policy().unwrap(),
        HistoryPolicy::new(false, Some(30)).unwrap()
    );
    assert!(HistoryPolicy::new(true, Some(1)).is_err());
}

#[test]
fn retention_and_expired_undo_do_not_remove_delivery_identity() {
    use voicelink_receiver::history::{HistoryPolicy, HistoryQuery};
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let mut message = fixture.submit(&session, "expired history");
    let receipt = ledger.submit(&session, &message).unwrap();
    let order = receipt.order.parse().unwrap();
    assert!(ledger.delete_history(order).unwrap());
    drop(ledger);
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    database
        .execute("UPDATE message_history SET received_at=1,deleted_at=1", [])
        .unwrap();
    drop(database);
    let mut ledger = fixture.open();
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    message.session_id = session.id().into();
    assert!(!ledger.restore_history(order).unwrap());
    ledger
        .set_history_policy(HistoryPolicy::new(true, Some(7)).unwrap())
        .unwrap();
    assert!(
        ledger
            .history(&HistoryQuery::default())
            .unwrap()
            .entries
            .is_empty()
    );
    assert!(ledger.query(&session, &message.id).unwrap().is_some());
    assert_eq!(
        ledger.submit(&session, &message).unwrap().order,
        receipt.order
    );
    drop(ledger);
    let db = rusqlite::Connection::open(fixture.path()).unwrap();
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM message_history", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM receipts", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        1
    );
}
