#[path = "../../../tests/support/manual_clock.rs"]
mod manual_clock;

use manual_clock::ManualClock;
use std::{
    fs,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use uuid::Uuid;
use voicelink_receiver::{
    Error, Result,
    ledger::{Clock, InputOutcome, Ledger, Protector, Session},
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

#[test]
fn receipt_application_is_an_identity_bound_execution_snapshot_not_live_metadata() {
    use voicelink_receiver::{application_icon::ApplicationDisplay, history::TargetApplication};
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let mut request = fixture.submit(&session, "snapshot test");
    let pending = ledger.submit(&session, &request).unwrap();
    assert!(pending.application.is_none());
    let mut target = TargetApplication::new(
        "C:\\private\\Editor.exe".into(),
        "Private title".into(),
        42,
        99,
    );
    target.display = Some(ApplicationDisplay {
        name: "Original editor".into(),
        icon_png: None,
    });
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing_at(&job, Some(target)).unwrap());
    assert_eq!(
        ledger
            .query(&session, &request.id)
            .unwrap()
            .unwrap()
            .application
            .unwrap()
            .name,
        "Original editor"
    );
    let receipt = ledger
        .finish(&job, InputOutcome::Injected { method: "test" })
        .unwrap();
    let wire = serde_json::to_string(&receipt).unwrap();
    assert!(wire.contains("Original editor"));
    for secret in [
        "private",
        "Private title",
        "process_id",
        "process_created",
        "executable",
    ] {
        assert!(!wire.contains(secret));
    }
    drop(ledger);
    let mut ledger = fixture.open();
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    let restored = ledger.query(&session, &request.id).unwrap().unwrap();
    assert_eq!(restored.application, receipt.application);
    let other_device = Uuid::new_v4().to_string();
    ledger
        .provision_device(&other_device, &[5; 32], "same name".into())
        .unwrap();
    let other = ledger.authenticate(&other_device, &[5; 32]).unwrap();
    assert!(ledger.query(&other, &request.id).unwrap().is_none());
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    ledger
        .delete_history(receipt.order.parse().unwrap())
        .unwrap();
    let hidden = ledger.query(&session, &request.id).unwrap().unwrap();
    assert_eq!(hidden.st, State::Injected);
    assert!(
        hidden.application.is_none(),
        "deleted history is not disclosed by queries"
    );
    request.session_id = session.id().into();
    assert_eq!(
        ledger.submit(&session, &request).unwrap().order,
        receipt.order
    );
    ledger.revoke(&fixture.device).unwrap();
    assert!(ledger.query(&session, &request.id).is_err());
}
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
    fn open_with_clock(&self, clock: Arc<ManualClock>) -> Ledger {
        Ledger::open_with_clock(
            &self.path(),
            Zeroizing::new(self.key.clone()),
            Box::new(TestProtector),
            clock,
        )
        .unwrap()
    }
    fn ready(&self) -> (Ledger, Session) {
        self.ready_named("")
    }
    fn ready_named(&self, name: &str) -> (Ledger, Session) {
        self.ready_ledger(self.open(), name)
    }
    fn ready_ledger(&self, mut ledger: Ledger, name: &str) -> (Ledger, Session) {
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
fn unknown_input_side_effects_are_global_in_the_committed_receipt() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let request = fixture.submit(&session, "native call did not report its outcome");
    let accepted = ledger.submit(&session, &request).unwrap();
    assert!(
        serde_json::to_value(accepted)
            .unwrap()
            .get("uncertain_scope")
            .is_none()
    );
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    let receipt = ledger
        .finish(
            &job,
            InputOutcome::Uncertain {
                reason: "input_worker_panicked",
            },
        )
        .unwrap();
    assert_eq!(
        serde_json::to_value(receipt).unwrap()["uncertain_scope"],
        "global"
    );
    let queried = ledger.query(&session, &request.id).unwrap().unwrap();
    assert_eq!(
        serde_json::to_value(queried).unwrap()["uncertain_scope"],
        "global"
    );
}

#[test]
fn partial_unicode_with_a_preflight_window_has_a_target_scope_in_queries() {
    use voicelink_receiver::ledger::InputWindow;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let request = fixture.submit(&session, "partially entered text");
    ledger.submit(&session, &request).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    let window = InputWindow::new("C:\\Editor.exe".into(), 123, 45);
    assert!(ledger.mark_executing_in(&job, None, Some(window)).unwrap());
    let finished = ledger.finish(&job, InputOutcome::PartialUnicode).unwrap();
    let queried = ledger.query(&session, &request.id).unwrap().unwrap();
    assert_eq!(
        serde_json::to_value(finished).unwrap()["uncertain_scope"],
        "target"
    );
    assert_eq!(
        serde_json::to_value(queried).unwrap()["uncertain_scope"],
        "target"
    );
}

#[test]
fn target_uncertainty_holds_only_the_same_execution_window_across_pairs() {
    use voicelink_receiver::ledger::InputWindow;
    let fixture = Fixture::new();
    let (mut ledger, sender) = fixture.ready();
    let other = Uuid::new_v4().to_string();
    ledger
        .provision_device(&other, &[3; 32], "Other phone".into())
        .unwrap();
    let other = ledger.authenticate(&other, &[3; 32]).unwrap();
    let window = InputWindow::new("C:\\Editor.exe".into(), 123, 45);
    let request = fixture.submit(&sender, "partial text");
    ledger.submit(&sender, &request).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(
        ledger
            .mark_executing_in(&job, None, Some(window.clone()))
            .unwrap()
    );
    ledger.finish(&job, InputOutcome::PartialUnicode).unwrap();
    assert!(!ledger.state().unwrap().paused);

    let blocked = fixture.submit(&other, "same window");
    ledger.submit(&other, &blocked).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(!ledger.mark_executing_in(&job, None, Some(window)).unwrap());
    let held = ledger.query(&other, &blocked.id).unwrap().unwrap();
    assert_eq!(held.st, State::Held);
    assert_eq!(held.reason.as_deref(), Some("uncertain_target"));
    let control = ledger.state().unwrap();
    code(
        ledger.release(&other, &blocked.id, &fixture.target, &control.state_rev),
        "BLOCKED_REVIEW",
    );

    for (creation, handle) in [(123, 46), (124, 45)] {
        let request = fixture.submit(&sender, "fresh window");
        ledger.submit(&sender, &request).unwrap();
        let job = ledger.prepare_next().unwrap().unwrap();
        assert_eq!(job.id(), request.id);
        let recreated = InputWindow::new("C:\\Editor.exe".into(), creation, handle);
        assert!(
            ledger
                .mark_executing_in(&job, None, Some(recreated))
                .unwrap()
        );
        ledger
            .finish(&job, InputOutcome::Injected { method: "unicode" })
            .unwrap();
    }
}

#[test]
fn resume_cannot_clear_an_uncertain_request_without_a_bound_review() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let request = fixture.submit(&session, "uncertain text");
    ledger.submit(&session, &request).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    ledger
        .finish(
            &job,
            InputOutcome::Uncertain {
                reason: "unclassified",
            },
        )
        .unwrap();
    let state = ledger.state().unwrap();
    code(ledger.resume(&state.state_rev, false), "STATE_CONFLICT");
    assert!(ledger.state().unwrap().paused);
}

#[test]
fn scheduler_orders_authenticated_pair_heads_and_skips_a_held_pair() {
    let fixture = Fixture::new();
    let clock = ManualClock::new();
    let (mut ledger, a) = fixture.ready_ledger(fixture.open_with_clock(clock.clone()), "A");
    let other = Uuid::new_v4().to_string();
    ledger
        .provision_device(&other, &[8; 32], "B".into())
        .unwrap();
    let b = ledger.authenticate(&other, &[8; 32]).unwrap();
    let a1 = fixture.submit(&a, "A1");
    let a2 = fixture.submit(&a, "A2");
    let b1 = fixture.submit(&b, "B1");
    let b2 = fixture.submit(&b, "B2");
    for (session, request) in [(&a, &a1), (&b, &b1), (&a, &a2), (&b, &b2)] {
        assert_eq!(ledger.submit(session, request).unwrap().st, State::Accepted);
    }
    let head = ledger.prepare_next().unwrap().unwrap();
    assert_eq!(head.id(), a1.id);
    ledger.hold(&head, "receiver_focused").unwrap();
    let head = ledger.prepare_next().unwrap().unwrap();
    assert_eq!(head.id(), b1.id);
    assert!(ledger.mark_executing(&head).unwrap());
    assert!(ledger.prepare_next().unwrap().is_none());
    ledger
        .finish(&head, InputOutcome::Injected { method: "test" })
        .unwrap();
    clock.advance(Duration::from_secs(31));
    assert_eq!(
        ledger.query(&a, &a2.id).unwrap().unwrap().st,
        State::Accepted
    );
    let rev = ledger.state().unwrap().state_rev;
    assert_eq!(
        ledger
            .release(&a, &a1.id, &fixture.target, &rev)
            .unwrap()
            .st,
        State::Accepted
    );
    for expected in [&a1, &a2, &b2] {
        let job = ledger.prepare_next().unwrap().unwrap();
        assert_eq!(job.id(), expected.id);
        assert!(ledger.mark_executing(&job).unwrap());
        ledger
            .finish(&job, InputOutcome::Injected { method: "test" })
            .unwrap();
    }
    assert!(ledger.prepare_next().unwrap().is_none());
}

#[test]
fn execution_deadline_starts_on_dispatch_and_includes_exactly_thirty_seconds() {
    let fixture = Fixture::new();
    let clock = ManualClock::new();
    let (mut ledger, session) = fixture.ready_ledger(fixture.open_with_clock(clock.clone()), "");
    let first = fixture.submit(&session, "first");
    let second = fixture.submit(&session, "second");
    ledger.submit(&session, &first).unwrap();
    ledger.submit(&session, &second).unwrap();
    let head = ledger.prepare_next().unwrap().unwrap();
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    let executable_at = |id: &str| -> Option<i64> {
        database
            .query_row(
                "SELECT executable_at FROM receipts WHERE id=?1",
                [id],
                |row| row.get(0),
            )
            .unwrap()
    };
    assert_eq!(executable_at(&first.id), Some(clock.unix_millis()));
    assert_eq!(executable_at(&second.id), None);
    clock.advance(Duration::from_secs(30));
    assert!(ledger.mark_executing(&head).unwrap());
    clock.advance(Duration::from_secs(60));
    assert!(ledger.prepare_next().unwrap().is_none());
    assert_eq!(executable_at(&second.id), None);
    ledger
        .finish(&head, InputOutcome::Injected { method: "test" })
        .unwrap();
    let head = ledger.prepare_next().unwrap().unwrap();
    assert_eq!(head.id(), second.id);
    let started = clock.unix_millis();
    clock.advance(Duration::from_millis(29_999));
    assert_eq!(ledger.prepare_next().unwrap().unwrap().id(), second.id);
    assert_eq!(executable_at(&second.id), Some(started));
    clock.advance(Duration::from_millis(2));
    assert!(!ledger.mark_executing(&head).unwrap());
    let expired = ledger.query(&session, &second.id).unwrap().unwrap();
    assert_eq!(expired.st, State::Expired);
    assert_eq!(expired.reason.as_deref(), Some("expired_before_input"));
}

#[test]
fn held_retention_starts_on_paused_admission_and_release_does_not_renew_execution() {
    let fixture = Fixture::new();
    let clock = ManualClock::new();
    let (mut ledger, session) = fixture.ready_ledger(fixture.open_with_clock(clock.clone()), "");
    ledger.pause().unwrap();
    let first = fixture.submit(&session, "release near the limit");
    let second = fixture.submit(&session, "expire at the limit");
    for request in [&first, &second] {
        assert_eq!(ledger.submit(&session, request).unwrap().st, State::Held);
    }
    clock.advance(Duration::from_secs(300));
    let paused = ledger.pause().unwrap();
    assert_eq!(
        ledger.query(&session, &first.id).unwrap().unwrap().st,
        State::Held
    );
    clock.advance(Duration::from_millis(299_999));
    let resumed = ledger.resume(&paused.state_rev, false).unwrap();
    ledger
        .release(&session, &first.id, &fixture.target, &resumed.state_rev)
        .unwrap();
    let head = ledger.prepare_next().unwrap().unwrap();
    assert_eq!(head.id(), first.id);
    clock.advance(Duration::from_millis(1));
    let rev = ledger.state().unwrap().state_rev;
    code(
        ledger.release(&session, &second.id, &fixture.target, &rev),
        "TOO_LATE",
    );
    let expired = ledger.query(&session, &second.id).unwrap().unwrap();
    assert_eq!(expired.st, State::Expired);
    assert_eq!(expired.reason.as_deref(), Some("hold_expired"));
    clock.advance(Duration::from_secs(29));
    code(
        ledger.release(&session, &first.id, &fixture.target, &rev),
        "TOO_LATE",
    );
    clock.advance(Duration::from_secs(1));
    assert!(!ledger.mark_executing(&head).unwrap());
    assert_eq!(
        ledger
            .query(&session, &first.id)
            .unwrap()
            .unwrap()
            .reason
            .as_deref(),
        Some("expired_before_input")
    );
}

#[test]
fn restart_keeps_held_retention_and_never_dispatches_old_input() {
    let fixture = Fixture::new();
    let clock = ManualClock::new();
    let (mut ledger, session) = fixture.ready_ledger(fixture.open_with_clock(clock.clone()), "");
    let injected = fixture.submit(&session, "already injected");
    ledger.submit(&session, &injected).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    ledger
        .finish(&job, InputOutcome::Injected { method: "test" })
        .unwrap();
    let held = fixture.submit(&session, "still held");
    ledger.submit(&session, &held).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger.hold(&job, "receiver_focused").unwrap();
    let accepted = fixture.submit(&session, "queued behind held");
    ledger.submit(&session, &accepted).unwrap();
    let other = Uuid::new_v4().to_string();
    ledger
        .provision_device(&other, &[8; 32], String::new())
        .unwrap();
    let second = ledger.authenticate(&other, &[8; 32]).unwrap();
    let executing = fixture.submit(&second, "interrupted in flight");
    ledger.submit(&second, &executing).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    clock.advance(Duration::from_secs(599));
    drop(ledger);

    let mut ledger = fixture.open_with_clock(clock.clone());
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    let second = ledger.authenticate(&other, &[8; 32]).unwrap();
    for (session, request, state, reason) in [
        (&session, &injected, State::Injected, None),
        (&session, &held, State::Held, Some("receiver_focused")),
        (
            &session,
            &accepted,
            State::Expired,
            Some("restart_confirmation"),
        ),
        (
            &second,
            &executing,
            State::Uncertain,
            Some("restart_during_input"),
        ),
    ] {
        let receipt = ledger.query(session, &request.id).unwrap().unwrap();
        assert_eq!(receipt.st, state);
        assert_eq!(receipt.reason.as_deref(), reason);
    }
    let bound = ledger.bind_target(&fixture.target).unwrap();
    ledger.resume(&bound.state_rev, true).unwrap();
    assert!(ledger.prepare_next().unwrap().is_none());
    clock.advance(Duration::from_secs(1));
    assert!(ledger.prepare_next().unwrap().is_none());
    let receipt = ledger.query(&session, &held.id).unwrap().unwrap();
    assert_eq!(receipt.st, State::Expired);
    assert_eq!(receipt.reason.as_deref(), Some("hold_expired"));
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
fn accepted_is_durable_and_expires_without_automatic_input_after_restart() {
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
    assert_eq!(receipt.st, State::Expired);
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
fn uncertain_review_ticket_is_invalid_after_control_revision_changes() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let request = fixture.submit(&session, "review ticket");
    ledger.submit(&session, &request).unwrap();
    let job = ledger.prepare_next().unwrap().unwrap();
    ledger.mark_executing(&job).unwrap();
    ledger
        .finish(&job, InputOutcome::Uncertain { reason: "partial" })
        .unwrap();
    let ticket = ledger.review_ticket().unwrap().unwrap();
    ledger.pause().unwrap();
    assert!(matches!(
        ledger.acknowledge_uncertain(&ticket),
        Err(Error::Protocol("STATE_CONFLICT"))
    ));
    let current = ledger.review_ticket().unwrap().unwrap();
    let state = ledger.acknowledge_uncertain(&current).unwrap();
    assert!(!state.paused);
    assert_eq!(
        ledger.query(&session, &request.id).unwrap().unwrap().st,
        State::UncertainAcknowledged
    );
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
        "STATE_CONFLICT",
    );
    let retained = ledger.query(&session, &submit.id).unwrap().unwrap();
    assert_eq!(retained.st, State::Uncertain);
    assert_eq!(retained.reason, receipt.reason);
    assert!(ledger.snapshot().unwrap().storage_healthy);
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
                State::Expired
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
            .all(|e| e.receipt.st == State::Expired && e.operation.is_some())
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
fn schema_seven_upgrade_preserves_pair_receipt_history_and_deduplication() {
    use voicelink_receiver::history::HistoryQuery;
    for version in [1, 2, 3, 4, 5, 6] {
        let fixture = Fixture::new();
        let (mut ledger, session) = fixture.ready();
        let message = fixture.submit(&session, "existing pending original");
        let accepted = ledger.submit(&session, &message).unwrap();
        let cancelled_id = Uuid::new_v4().to_string();
        let cancelled = ledger.cancel(&session, &cancelled_id).unwrap();
        drop(ledger);
        let database = rusqlite::Connection::open(fixture.path()).unwrap();
        database
            .execute_batch(
                "DROP TRIGGER receipt_state_insert;
             DROP TRIGGER receipt_state_transition;
             DROP TRIGGER receipt_terminal_immutable;
             DROP TRIGGER cancellation_insert;
             DROP TRIGGER cancellation_delete;
             DROP TABLE transferred_receipts;
             ALTER TABLE pairs DROP COLUMN cancel_count;
             ALTER TABLE image_resources RENAME COLUMN pair_id TO device;
             ALTER TABLE receipts RENAME COLUMN pair_id TO device;
             ALTER TABLE pairs RENAME TO devices;
             ALTER TABLE receipts DROP COLUMN request_rev;
             ALTER TABLE receipts DROP COLUMN execution_stage;
             ALTER TABLE receipts DROP COLUMN confirmed;
             DROP INDEX uncertain_windows;
             DROP INDEX uncertain_receipts;
             ALTER TABLE receipts DROP COLUMN input_window_mac;
             ALTER TABLE receipts DROP COLUMN uncertain_scope;
             ALTER TABLE receipts DROP COLUMN hold_expires_at;
             ALTER TABLE receipts DROP COLUMN executable_at;",
            )
            .unwrap();
        if version < 6 {
            database.execute_batch("DROP TRIGGER image_history_retired; DROP TRIGGER image_receipt_retired; DROP INDEX image_pending; DROP TABLE image_resources; DROP TABLE image_garbage; ALTER TABLE receipts DROP COLUMN image_resource;").unwrap();
        }
        if version < 5 {
            database
                .execute_batch("ALTER TABLE message_history DROP COLUMN source_name;")
                .unwrap();
        }
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
                "SELECT d.token_mac,r.payload_mac FROM devices d JOIN receipts r ON d.id=r.device WHERE r.id=?1",
                [&message.id],
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
        assert_eq!(receipt.st, State::Expired);
        assert_eq!(receipt.reason.as_deref(), Some("restart_confirmation"));
        let tombstone = ledger.query(&restored, &cancelled_id).unwrap().unwrap();
        assert_eq!(tombstone.order, cancelled.order);
        assert_eq!(tombstone.st, State::Cancelled);
        let mut replay = message.clone();
        replay.session_id = restored.id().to_owned();
        assert_eq!(
            ledger.submit(&restored, &replay).unwrap().order,
            accepted.order
        );
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
        assert_eq!(installed, 7);
        let counts = database
            .query_row(
                "SELECT receipt_count,cancel_count,pending_count FROM pairs WHERE id=?1",
                [&fixture.device],
                |r| {
                    Ok((
                        r.get::<_, u32>(0)?,
                        r.get::<_, u32>(1)?,
                        r.get::<_, u32>(2)?,
                    ))
                },
            )
            .unwrap();
        assert_eq!(counts, (2, 1, 0));
        let unchanged: (Vec<u8>, Vec<u8>) = database
            .query_row(
                "SELECT d.token_mac,r.payload_mac FROM pairs d JOIN receipts r ON d.id=r.pair_id WHERE r.id=?1",
                [&message.id],
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
        .execute_batch("ALTER TABLE pairs DROP COLUMN display_name")
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
        database.execute("INSERT INTO receipts(pair_id,id,payload_mac,original_target,state) SELECT pair_id,?1,payload_mac,original_target,'injected' FROM receipts WHERE accept_order=1",[Uuid::new_v4().to_string()]).unwrap();
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

fn prepared_image(
    fixture: &Fixture,
    ledger: &mut Ledger,
    session: &Session,
) -> (
    voicelink_receiver::images::ImageReference,
    std::sync::Arc<voicelink_receiver::images::ImageAuthorization>,
) {
    use voicelink_receiver::images::{
        ImageAuthorization, ImageMetadata, ImageMime, digest, storage::ImageStore,
        wire::UploadScope,
    };
    let store = ImageStore::open(&fixture.directory.join("images"), &fixture.target).unwrap();
    ledger.attach_images(store.clone()).unwrap();
    let mut bytes = std::io::Cursor::new(Vec::new());
    image::DynamicImage::new_rgba8(3, 2)
        .write_to(&mut bytes, image::ImageFormat::Png)
        .unwrap();
    let bytes = bytes.into_inner();
    let resource = store
        .prepare(
            session.device(),
            ImageMetadata {
                bytes: bytes.len() as u32,
                mime: ImageMime::Png,
                width: 3,
                height: 2,
                sha256: digest(&bytes),
            },
            &bytes,
            &TestProtector,
        )
        .unwrap();
    let reference = resource.image().clone();
    let auth = std::sync::Arc::new(ImageAuthorization::new(std::sync::Arc::new(
        std::sync::atomic::AtomicBool::new(true),
    )));
    ledger
        .register_image(
            session,
            UploadScope {
                device: session.device().into(),
                session: session.id().into(),
                target: fixture.target.clone(),
                revision: ledger.state().unwrap().state_rev,
            },
            resource,
            auth.clone(),
        )
        .unwrap();
    (reference, auth)
}

#[test]
fn image_acceptance_is_durable_deduplicated_and_never_replays_after_restart() {
    use voicelink_receiver::{history::HistoryQuery, images::storage::ImageStore};
    for executing in [false, true] {
        let fixture = Fixture::new();
        let (mut ledger, session) = fixture.ready();
        let (image, _) = prepared_image(&fixture, &mut ledger, &session);
        let message = Submit::new(
            session.id().into(),
            Uuid::new_v4().to_string(),
            fixture.target.clone(),
            Operation::Image {
                image: image.clone(),
            },
        );
        let receipt = ledger.submit(&session, &message).unwrap();
        assert_eq!(receipt.st, State::Accepted);
        assert_eq!(
            ledger.submit(&session, &message).unwrap().order,
            receipt.order
        );
        let mut conflict = message.clone();
        if let Operation::Image { image } = &mut conflict.op {
            image.metadata.sha256 = "a".repeat(64);
        }
        code(ledger.submit(&session, &conflict), "ID_CONFLICT");
        let job = ledger.prepare_next().unwrap().unwrap();
        if executing {
            assert!(ledger.mark_executing(&job).unwrap());
        }
        drop(job);
        drop(ledger);
        let mut ledger = fixture.open();
        let store = ImageStore::open(&fixture.directory.join("images"), &fixture.target).unwrap();
        ledger.attach_images(store.clone()).unwrap();
        assert!(store.contains(&image.resource_id).unwrap());
        let session = ledger
            .authenticate(&fixture.device, &fixture.token)
            .unwrap();
        let receipt = ledger.query(&session, &message.id).unwrap().unwrap();
        assert_eq!(
            receipt.st,
            if executing {
                State::Uncertain
            } else {
                State::Expired
            }
        );
        assert!(ledger.prepare_next().unwrap().is_none());
        let history = ledger.history(&HistoryQuery::default()).unwrap();
        assert!(
            matches!(&history.entries[0].operation,Some(Operation::Image{image:original}) if original==&image)
        );
        store.remove(&image.resource_id).unwrap();
        drop(ledger);
        let mut ledger = fixture.open();
        code(ledger.attach_images(store), "IMAGE_RESOURCE_MISSING");
    }
}

#[test]
fn image_authorization_cannot_survive_pause_new_session_or_revoke() {
    for event in ["pause", "session", "revoke"] {
        let fixture = Fixture::new();
        let (mut ledger, session) = fixture.ready();
        let (image, auth) = prepared_image(&fixture, &mut ledger, &session);
        let message = Submit::new(
            session.id().into(),
            Uuid::new_v4().to_string(),
            fixture.target.clone(),
            Operation::Image { image },
        );
        ledger.submit(&session, &message).unwrap();
        let job = ledger.prepare_next().unwrap().unwrap();
        assert!(job.image_authorized());
        match event {
            "pause" => {
                let state = ledger.pause().unwrap();
                ledger.resume(&state.state_rev, false).unwrap();
            }
            "session" => {
                ledger
                    .authenticate(&fixture.device, &fixture.token)
                    .unwrap();
            }
            "revoke" => ledger.revoke(&fixture.device).unwrap(),
            _ => {
                drop(auth);
                ledger.pause().unwrap();
            }
        }
        assert!(!job.image_authorized());
        assert!(!ledger.mark_executing(&job).unwrap());
        assert!(ledger.prepare_next().unwrap().is_none());
    }
}

#[test]
fn unaccepted_image_is_discarded_but_accepted_resource_is_not() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let (image, _) = prepared_image(&fixture, &mut ledger, &session);
    assert!(ledger.discard_image(&session, &image.resource_id).unwrap());
    let path = fixture
        .directory
        .join("images")
        .join(format!("{}.image", image.resource_id));
    let until = std::time::Instant::now() + std::time::Duration::from_secs(2);
    while path.exists() && std::time::Instant::now() < until {
        std::thread::yield_now();
    }
    assert!(!path.exists());
    let (image, _) = prepared_image(&fixture, &mut ledger, &session);
    let message = Submit::new(
        session.id().into(),
        Uuid::new_v4().to_string(),
        fixture.target.clone(),
        Operation::Image {
            image: image.clone(),
        },
    );
    ledger.submit(&session, &message).unwrap();
    assert!(!ledger.discard_image(&session, &image.resource_id).unwrap());
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    let result = ledger
        .finish(
            &job,
            InputOutcome::Injected {
                method: "clipboard_image",
            },
        )
        .unwrap();
    assert_eq!(result.method.as_deref(), Some("clipboard_image"));
    assert_eq!(
        ledger.submit(&session, &message).unwrap().st,
        State::Injected
    );
    assert!(ledger.prepare_next().unwrap().is_none());
    assert!(
        fixture
            .directory
            .join("images")
            .join(format!("{}.image", image.resource_id))
            .exists()
    );
}

#[test]
fn image_history_cleanup_preserves_undo_pending_and_dedup_receipts() {
    use voicelink_receiver::history::HistoryPolicy;
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let (image, _) = prepared_image(&fixture, &mut ledger, &session);
    let message = Submit::new(
        session.id().into(),
        Uuid::new_v4().to_string(),
        fixture.target.clone(),
        Operation::Image {
            image: image.clone(),
        },
    );
    let receipt = ledger.submit(&session, &message).unwrap();
    let order = receipt.order.parse().unwrap();
    let path = fixture
        .directory
        .join("images")
        .join(format!("{}.image", image.resource_id));
    assert!(ledger.delete_history(order).unwrap());
    ledger.set_history_policy(HistoryPolicy::default()).unwrap();
    assert!(path.exists());
    assert!(ledger.restore_history(order).unwrap());
    assert!(path.exists());
    assert!(ledger.delete_history(order).unwrap());
    let database = rusqlite::Connection::open(fixture.path()).unwrap();
    database
        .execute(
            "UPDATE message_history SET deleted_at=0 WHERE accept_order=?1",
            [order],
        )
        .unwrap();
    ledger.set_history_policy(HistoryPolicy::default()).unwrap();
    assert!(path.exists(), "pending input retains its required resource");
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    ledger
        .finish(
            &job,
            InputOutcome::Injected {
                method: "clipboard_image",
            },
        )
        .unwrap();
    let until = std::time::Instant::now() + std::time::Duration::from_secs(2);
    loop {
        ledger.set_history_policy(HistoryPolicy::default()).unwrap();
        let remaining: i64 = database
            .query_row("SELECT COUNT(*) FROM image_garbage", [], |r| r.get(0))
            .unwrap();
        if !path.exists() && remaining == 0 {
            break;
        }
        assert!(std::time::Instant::now() < until);
        std::thread::yield_now();
    }
    assert_eq!(
        ledger.query(&session, &message.id).unwrap().unwrap().st,
        State::Injected
    );
    assert_eq!(
        ledger.submit(&session, &message).unwrap().st,
        State::Injected
    );
    assert!(ledger.prepare_next().unwrap().is_none());
    drop(ledger);
    let mut ledger = fixture.open();
    ledger
        .attach_images(
            voicelink_receiver::images::storage::ImageStore::open(
                &fixture.directory.join("images"),
                &fixture.target,
            )
            .unwrap(),
        )
        .unwrap();
    let session = ledger
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    assert_eq!(
        ledger.query(&session, &message.id).unwrap().unwrap().st,
        State::Injected
    );
}

#[test]
fn new_ledger_creates_schema_seven_with_pair_key_and_execution_fields() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let message = fixture.submit(&session, "schema fields");
    ledger.submit(&session, &message).unwrap();
    let db = rusqlite::Connection::open(fixture.path()).unwrap();
    let version: u32 = db
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .unwrap();
    assert_eq!(version, 7);
    let row: (String, i64, Option<String>, Option<i64>, Option<String>, Option<i64>) = db.query_row(
        "SELECT pair_id,request_rev,execution_stage,confirmed,uncertain_scope,hold_expires_at FROM receipts WHERE id=?1",
        [&message.id], |row| Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?,row.get(4)?,row.get(5)?))
    ).unwrap();
    assert_eq!(
        row,
        (
            fixture.device.clone(),
            0,
            Some("not_started".into()),
            Some(0),
            None,
            None,
        )
    );
    let fk: (String, String) = db
        .query_row(
            "SELECT [table], [from] FROM pragma_foreign_key_list('receipts')",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(fk, ("pairs".into(), "pair_id".into()));
    assert!(
        db.execute(
            "INSERT INTO receipts(pair_id,id,state) VALUES(?1,?2,'cancelled')",
            rusqlite::params![fixture.device, message.id]
        )
        .is_err()
    );
}

#[test]
fn receipt_state_matrix_rejects_illegal_transitions_atomically() {
    let fixture = Fixture::new();
    let (ledger, _) = fixture.ready();
    let mut db = rusqlite::Connection::open(fixture.path()).unwrap();
    let allowed = [
        ("accepted", "held"),
        ("accepted", "executing"),
        ("accepted", "expired"),
        ("accepted", "cancelled"),
        ("held", "accepted"),
        ("held", "expired"),
        ("held", "cancelled"),
        ("executing", "injected"),
        ("executing", "failed"),
        ("executing", "uncertain"),
        ("uncertain", "uncertain_acknowledged"),
    ];
    let states = [
        "accepted",
        "held",
        "executing",
        "injected",
        "failed",
        "cancelled",
        "expired",
        "uncertain",
        "uncertain_acknowledged",
        "saved",
    ];
    for from in states {
        for to in states {
            let tx = db.transaction().unwrap();
            let inserted = tx.execute(
                "INSERT INTO receipts(pair_id,id,state,reason) VALUES(?1,'matrix',?2,'before')",
                rusqlite::params![fixture.device, from],
            );
            if from == "saved" {
                assert!(
                    inserted.is_err(),
                    "phone-side saved is never persisted by the receiver"
                );
                continue;
            }
            inserted.unwrap();
            let result = tx.execute(
                "UPDATE receipts SET state=?1,reason='after',request_rev=request_rev+1 WHERE id='matrix'",
                [to],
            );
            let after: (String, String, i64) = tx
                .query_row(
                    "SELECT state,reason,request_rev FROM receipts WHERE id='matrix'",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .unwrap();
            if allowed.contains(&(from, to)) {
                assert_eq!(result.unwrap(), 1, "{from} -> {to}");
                assert_eq!(after, (to.into(), "after".into(), 1));
            } else {
                assert!(
                    result.unwrap_err().to_string().contains("STATE_CONFLICT"),
                    "{from} -> {to}"
                );
                assert_eq!(after, (from.into(), "before".into(), 0));
            }
        }
    }
    drop(ledger);
}

#[test]
fn terminal_receipt_metadata_cannot_be_rewritten() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let id = Uuid::new_v4().to_string();
    let receipt = ledger.cancel(&session, &id).unwrap();
    let db = rusqlite::Connection::open(fixture.path()).unwrap();
    let result = db.execute("UPDATE receipts SET reason='rewrite' WHERE id=?1", [&id]);
    assert!(result.unwrap_err().to_string().contains("STATE_CONFLICT"));
    assert_eq!(
        ledger.query(&session, &id).unwrap().unwrap().reason,
        receipt.reason
    );
}
#[test]
fn request_revision_advances_on_mutation_but_not_query_and_accept_order_is_global() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let first = fixture.submit(&session, "revision first");
    let second = fixture.submit(&session, "revision second");
    let accepted = ledger.submit(&session, &first).unwrap();
    let second_accepted = ledger.submit(&session, &second).unwrap();
    assert!(accepted.order.parse::<u64>().unwrap() < second_accepted.order.parse::<u64>().unwrap());
    let rev = |id: &str| -> i64 {
        rusqlite::Connection::open(fixture.path())
            .unwrap()
            .query_row(
                "SELECT request_rev FROM receipts WHERE pair_id=?1 AND id=?2",
                rusqlite::params![fixture.device, id],
                |row| row.get(0),
            )
            .unwrap()
    };
    assert_eq!(rev(&first.id), 0);
    assert_eq!(
        ledger.query(&session, &first.id).unwrap().unwrap().order,
        accepted.order
    );
    assert_eq!(rev(&first.id), 0);
    ledger.cancel(&session, &first.id).unwrap();
    assert_eq!(rev(&first.id), 1);
    drop(ledger);
    let mut reopened = fixture.open();
    let session = reopened
        .authenticate(&fixture.device, &fixture.token)
        .unwrap();
    assert_eq!(
        reopened.query(&session, &first.id).unwrap().unwrap().order,
        accepted.order
    );
    assert_eq!(rev(&first.id), 1);
    assert_eq!(
        reopened.query(&session, &second.id).unwrap().unwrap().order,
        second_accepted.order
    );
}

#[test]
fn competing_writers_cannot_advance_the_same_request_revision() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let message = fixture.submit(&session, "concurrent revision");
    ledger.submit(&session, &message).unwrap();
    let barrier = std::sync::Barrier::new(2);
    let outcomes = std::thread::scope(|scope| {
        let writers = ["held", "cancelled"].map(|state| {
            let barrier = &barrier;
            let fixture = &fixture;
            let id = &message.id;
            scope.spawn(move || {
                let db = rusqlite::Connection::open(fixture.path()).unwrap();
                db.busy_timeout(std::time::Duration::from_secs(5)).unwrap();
                let rev: i64 = db
                    .query_row("SELECT request_rev FROM receipts WHERE id=?1", [id], |r| {
                        r.get(0)
                    })
                    .unwrap();
                barrier.wait();
                db.execute(
                    "UPDATE receipts SET state=?1, request_rev=?2
                     WHERE id=?3 AND request_rev=?4",
                    rusqlite::params![state, rev + 1, id, rev],
                )
                .unwrap()
            })
        });
        writers.map(|writer| writer.join().unwrap())
    });
    assert_eq!(outcomes.iter().sum::<usize>(), 1);

    let db = rusqlite::Connection::open(fixture.path()).unwrap();
    let advanced: i64 = db
        .query_row(
            "SELECT request_rev FROM receipts WHERE id=?1",
            [&message.id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(advanced, 1);
}

#[test]
fn receipt_transition_requires_exactly_one_revision_increment() {
    let fixture = Fixture::new();
    let (mut ledger, session) = fixture.ready();
    let message = fixture.submit(&session, "revision guard");
    ledger.submit(&session, &message).unwrap();

    let db = rusqlite::Connection::open(fixture.path()).unwrap();
    for sql in [
        "UPDATE receipts SET state='held' WHERE id=?1",
        "UPDATE receipts SET state='held',request_rev=2 WHERE id=?1",
        "UPDATE receipts SET request_rev=1 WHERE id=?1",
    ] {
        let err = db.execute(sql, [&message.id]).unwrap_err();
        assert!(err.to_string().contains("STATE_CONFLICT"), "{err}");
    }
    assert_eq!(
        ledger.query(&session, &message.id).unwrap().unwrap().st,
        State::Accepted
    );
    let at_limit = Uuid::new_v4().to_string();
    db.execute(
        "INSERT INTO receipts(pair_id,id,state,request_rev) VALUES(?1,?2,'accepted',?3)",
        rusqlite::params![session.device(), at_limit, i64::MAX],
    )
    .unwrap();
    assert!(matches!(
        ledger.cancel(&session, &at_limit),
        Err(Error::Protocol("STATE_CONFLICT"))
    ));
    let unchanged = db
        .query_row(
            "SELECT state,request_rev FROM receipts WHERE id=?1",
            [&at_limit],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )
        .unwrap();
    assert_eq!(unchanged, ("accepted".into(), i64::MAX));
}

#[test]
fn replacing_a_pair_at_capacity_transfers_only_read_only_terminal_receipts() {
    let fixture = Fixture::new();
    let (mut ledger, old) = fixture.ready();
    let mut other_pairs = Vec::new();
    for byte in 20..27 {
        let pair = Uuid::new_v4().to_string();
        let token = [byte; 32];
        ledger
            .provision_device(&pair, &token, String::new())
            .unwrap();
        other_pairs.push(ledger.authenticate(&pair, &token).unwrap());
    }

    let message = fixture.submit(&old, "replace only after completion");
    let accepted = ledger.submit(&old, &message).unwrap();
    let replacement = Uuid::new_v4().to_string();
    let replacement_token = [32; 32];
    assert!(matches!(
        ledger.replace_pair(&old, &replacement, &replacement_token, String::new()),
        Err(Error::Protocol("PAIR_REPLACE_BLOCKED"))
    ));
    assert!(ledger.query(&old, &message.id).unwrap().is_some());
    let job = ledger.prepare_next().unwrap().unwrap();
    assert!(ledger.mark_executing(&job).unwrap());
    let terminal = ledger
        .finish(&job, InputOutcome::Injected { method: "test" })
        .unwrap();
    ledger
        .replace_pair(&old, &replacement, &replacement_token, String::new())
        .unwrap();

    assert!(
        ledger
            .authenticate(&fixture.device, &fixture.token)
            .is_err()
    );
    assert!(ledger.query(&old, &message.id).is_err());
    let new = ledger
        .authenticate(&replacement, &replacement_token)
        .unwrap();
    let copied = ledger.query(&new, &message.id).unwrap().unwrap();
    assert_eq!(
        (&copied.order, copied.st, copied.method),
        (&accepted.order, terminal.st, terminal.method)
    );
    assert!(
        ledger
            .query(&other_pairs[0], &message.id)
            .unwrap()
            .is_none()
    );
    assert!(matches!(
        ledger.cancel(&new, &message.id),
        Err(Error::Protocol("TOO_LATE"))
    ));
    let mut replay = message;
    replay.session_id = new.id().into();
    assert!(matches!(
        ledger.submit(&new, &replay),
        Err(Error::Protocol("ID_CONFLICT"))
    ));
    assert_eq!(ledger.snapshot().unwrap().pending, 0);
    assert!(matches!(
        ledger.provision_device(&Uuid::new_v4().to_string(), &[99; 32], String::new()),
        Err(Error::Protocol("DEVICE_LIMIT"))
    ));
    drop(ledger);

    let mut reopened = fixture.open();
    let session = reopened
        .authenticate(&replacement, &replacement_token)
        .unwrap();
    assert_eq!(
        reopened.query(&session, &replay.id).unwrap().unwrap().order,
        accepted.order
    );
    let db = rusqlite::Connection::open(fixture.path()).unwrap();
    let counts = db
        .query_row(
            "SELECT receipt_count,cancel_count,pending_count FROM pairs WHERE id=?1",
            [&replacement],
            |row| {
                Ok((
                    row.get::<_, u32>(0)?,
                    row.get::<_, u32>(1)?,
                    row.get::<_, u32>(2)?,
                ))
            },
        )
        .unwrap();
    assert_eq!(counts, (0, 0, 0));
}
