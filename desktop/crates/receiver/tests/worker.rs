#[path = "../../../tests/support/manual_clock.rs"]
mod manual_clock;

use manual_clock::ManualClock;
use std::{
    fs,
    future::Future,
    path::PathBuf,
    pin::pin,
    sync::{Arc, Mutex},
    task::{Context, Poll, Wake, Waker},
    thread,
    time::{Duration, Instant},
};
use uuid::Uuid;
use voicelink_receiver::{
    Error, Result,
    gate::InputGate,
    ledger::{Execution, InputOutcome, Ledger, Protector, Session},
    protocol::{Operation, State, Submit, TextSource},
    worker::{InputAdapter, Receiver},
};
use zeroize::Zeroizing;

struct WakeThread(thread::Thread);
impl Wake for WakeThread {
    fn wake(self: Arc<Self>) {
        self.0.unpark();
    }
}
fn wait<T>(future: impl Future<Output = T>) -> T {
    let waker = Waker::from(Arc::new(WakeThread(thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut future = pin!(future);
    let started = Instant::now();
    loop {
        if let Poll::Ready(value) = future.as_mut().poll(&mut context) {
            return value;
        }
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "worker operation timed out"
        );
        thread::park_timeout(Duration::from_millis(10));
    }
}

struct TestProtector;
impl Protector for TestProtector {
    fn protect(&self, input: &[u8]) -> Result<Vec<u8>> {
        Ok(input.to_vec())
    }
    fn unprotect(&self, input: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
        Ok(Zeroizing::new(input.to_vec()))
    }
}
struct BlockingProtector {
    entered: std::sync::mpsc::SyncSender<()>,
    release: std::sync::mpsc::Receiver<()>,
}
impl Protector for BlockingProtector {
    fn protect(&self, input: &[u8]) -> Result<Vec<u8>> {
        self.entered.send(()).unwrap();
        self.release.recv_timeout(Duration::from_secs(3)).unwrap();
        Ok(input.to_vec())
    }
    fn unprotect(&self, input: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
        Ok(Zeroizing::new(input.to_vec()))
    }
}

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("voicelink-worker-{}", Uuid::new_v4()));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
struct Adapter {
    calls: Arc<Mutex<Vec<String>>>,
    preflight: bool,
    uncertain: bool,
}
impl InputAdapter for Adapter {
    fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
        if self.preflight {
            Ok(())
        } else {
            Err("focus_changed")
        }
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        assert!(!gate.is_paused());
        self.calls.lock().unwrap().push(job.id().into());
        if self.uncertain {
            InputOutcome::Uncertain {
                reason: "test_partial",
            }
        } else {
            InputOutcome::Injected { method: "test" }
        }
    }
}
fn ready(directory: &Directory, adapter: impl InputAdapter) -> (Receiver, Session, String) {
    ready_with_protector(directory, adapter, Box::new(TestProtector))
}
fn ready_with_protector(
    directory: &Directory,
    adapter: impl InputAdapter,
    protector: Box<dyn Protector>,
) -> (Receiver, Session, String) {
    let ledger = Ledger::open(
        &directory.0.join("receiver.sqlite3"),
        Zeroizing::new(vec![1; 32]),
        protector,
    )
    .unwrap();
    ready_with_ledger(ledger, adapter)
}
fn ready_with_ledger(ledger: Ledger, adapter: impl InputAdapter) -> (Receiver, Session, String) {
    let receiver = Receiver::start(ledger, adapter).unwrap();
    let device = Uuid::new_v4().to_string();
    wait(receiver.provision_device(device.clone(), Zeroizing::new(vec![2; 32]), String::new()))
        .unwrap();
    let session = wait(receiver.authenticate(device, Zeroizing::new(vec![2; 32]))).unwrap();
    let target = Uuid::new_v4().to_string();
    let state = wait(receiver.bind_target(target.clone())).unwrap();
    wait(receiver.resume(state.state_rev, false)).unwrap();
    (receiver, session, target)
}
fn submit(session: &Session, target: &str, text: &str) -> Submit {
    Submit::new(
        session.id().into(),
        Uuid::new_v4().to_string(),
        target.into(),
        Operation::Text {
            text: text.into(),
            src: TextSource::Manual,
        },
    )
}
fn until(receiver: &Receiver, session: &Session, id: &str, state: State) {
    let started = Instant::now();
    loop {
        if wait(receiver.query(session.clone(), id.into()))
            .unwrap()
            .unwrap()
            .st
            == state
        {
            return;
        }
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "state never reached {state:?}"
        );
        thread::sleep(Duration::from_millis(1));
    }
}

#[test]
fn real_coordinator_serializes_executes_once_and_deduplicates() {
    let directory = Directory::new();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready(
        &directory,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: false,
        },
    );
    let first = submit(&session, &target, "one");
    let second = submit(&session, &target, "two");
    wait(receiver.submit(session.clone(), first.clone())).unwrap();
    wait(receiver.submit(session.clone(), second.clone())).unwrap();
    until(&receiver, &session, &second.id, State::Injected);
    for _ in 0..8 {
        assert_eq!(
            wait(receiver.submit(session.clone(), first.clone()))
                .unwrap()
                .st,
            State::Injected
        );
    }
    assert_eq!(*calls.lock().unwrap(), [first.id, second.id]);
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn network_recovery_uses_a_fresh_scope_and_never_replays_held_text() {
    let directory = Directory::new();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready(
        &directory,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: false,
        },
    );
    let paused = wait(receiver.network_lost()).unwrap();
    assert_eq!(paused.pause_reason.as_deref(), Some("network_unavailable"));
    let old = submit(&session, &target, "must not follow a new cursor");
    assert_eq!(
        wait(receiver.submit(session.clone(), old.clone()))
            .unwrap()
            .st,
        State::Held
    );
    let recovered = wait(receiver.enable_foreground_input(paused.state_rev, false)).unwrap();
    assert!(!recovered.paused);
    assert_ne!(recovered.target.as_deref(), Some(target.as_str()));
    assert_eq!(
        wait(receiver.query(session.clone(), old.id.clone()))
            .unwrap()
            .unwrap()
            .st,
        State::Held
    );
    assert!(calls.lock().unwrap().is_empty());
    wait(receiver.cancel(session.clone(), old.id)).unwrap();
    let fresh = submit(&session, recovered.target.as_deref().unwrap(), "new text");
    wait(receiver.submit(session.clone(), fresh.clone())).unwrap();
    until(&receiver, &session, &fresh.id, State::Injected);
    assert_eq!(*calls.lock().unwrap(), [fresh.id]);
    wait(receiver.pause()).unwrap();
    assert_eq!(
        wait(receiver.network_lost())
            .unwrap()
            .pause_reason
            .as_deref(),
        Some("paused")
    );
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn network_loss_cannot_erase_uncertainty_or_authorize_review() {
    let directory = Directory::new();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready(
        &directory,
        Adapter {
            calls,
            preflight: true,
            uncertain: true,
        },
    );
    let message = submit(&session, &target, "partial");
    wait(receiver.submit(session.clone(), message.clone())).unwrap();
    until(&receiver, &session, &message.id, State::Uncertain);
    let state = wait(receiver.network_lost()).unwrap();
    assert_eq!(
        state.pause_reason.as_deref(),
        Some("outcome_review_required")
    );
    assert!(wait(receiver.enable_foreground_input(state.state_rev, false)).is_err());
    assert_eq!(
        wait(receiver.query(session, message.id))
            .unwrap()
            .unwrap()
            .st,
        State::Uncertain
    );
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn prepared_application_is_durable_before_native_execution() {
    use voicelink_receiver::history::{HistoryQuery, TargetApplication};
    struct ObservedAdapter {
        path: PathBuf,
        target: TargetApplication,
    }
    impl InputAdapter for ObservedAdapter {
        fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
            Ok(())
        }
        fn prepared_application(&self) -> Option<TargetApplication> {
            Some(self.target.clone())
        }
        fn execute(&mut self, job: &Execution, _: &InputGate) -> InputOutcome {
            let database = rusqlite::Connection::open(&self.path).unwrap();
            let (state, target, executed_at): (String, Vec<u8>, i64) = database.query_row(
                "SELECT r.state,h.target,h.executed_at FROM receipts r JOIN message_history h USING(accept_order) WHERE r.id=?1",
                [job.id()], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            ).unwrap();
            assert_eq!(state, "executing");
            assert!(executed_at > 0);
            let envelope: serde_json::Value = serde_json::from_slice(&target).unwrap();
            assert_eq!(envelope["id"], job.id());
            assert_eq!(
                envelope["target"],
                serde_json::to_value(&self.target).unwrap()
            );
            InputOutcome::Injected { method: "test" }
        }
    }
    let directory = Directory::new();
    let application =
        TargetApplication::new("C:\\Editor.exe".into(), "Original window".into(), 77, 1234);
    let (receiver, session, target) = ready(
        &directory,
        ObservedAdapter {
            path: directory.0.join("receiver.sqlite3"),
            target: application.clone(),
        },
    );
    let message = submit(&session, &target, "durable metadata");
    wait(receiver.submit(session.clone(), message.clone())).unwrap();
    until(&receiver, &session, &message.id, State::Injected);
    let page = wait(receiver.history(HistoryQuery::default())).unwrap();
    assert_eq!(page.entries[0].target, Some(application));
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn automatic_text_executes_only_in_the_requested_application() {
    use voicelink_receiver::{application_icon::ApplicationDisplay, history::TargetApplication};

    struct Editor;
    impl InputAdapter for Editor {
        fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
            Ok(())
        }
        fn prepared_application(&self) -> Option<TargetApplication> {
            let mut target = TargetApplication::new("Editor.exe".into(), "".into(), 77, 1);
            target.display = Some(ApplicationDisplay {
                name: "Editor".into(),
                icon_png: None,
            });
            Some(target)
        }
        fn execute(&mut self, job: &Execution, _: &InputGate) -> InputOutcome {
            assert_eq!(job.operation().text(), Some("matching target"));
            InputOutcome::Injected { method: "test" }
        }
    }
    let directory = Directory::new();
    let (receiver, session, target) = ready(&directory, Editor);
    let make_message = |text: &str, application: &str| {
        let mut message = submit(&session, &target, text);
        message.op = Operation::AutoText {
            text: text.into(),
            application: application.into(),
        };
        message
    };
    let wrong = make_message("wrong target", "Browser");
    wait(receiver.submit(session.clone(), wrong.clone())).unwrap();
    until(&receiver, &session, &wrong.id, State::Failed);
    let receipt = wait(receiver.query(session.clone(), wrong.id))
        .unwrap()
        .unwrap();
    assert_eq!(receipt.reason.as_deref(), Some("automatic_target_changed"));
    let right = make_message("matching target", "Editor");
    wait(receiver.submit(session.clone(), right.clone())).unwrap();
    until(&receiver, &session, &right.id, State::Injected);
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn native_preflight_failure_holds_oldest_without_input_or_skipping() {
    let directory = Directory::new();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready(
        &directory,
        Adapter {
            calls: calls.clone(),
            preflight: false,
            uncertain: false,
        },
    );
    let first = submit(&session, &target, "wrong focus");
    let second = submit(&session, &target, "must wait");
    wait(receiver.submit(session.clone(), first.clone())).unwrap();
    wait(receiver.submit(session.clone(), second.clone())).unwrap();
    until(&receiver, &session, &first.id, State::Held);
    assert_eq!(
        wait(receiver.query(session.clone(), second.id))
            .unwrap()
            .unwrap()
            .st,
        State::Accepted
    );
    assert!(calls.lock().unwrap().is_empty());
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn held_head_expires_without_control_traffic_then_fresh_text_executes() {
    struct RecoveringAdapter {
        ready: Arc<std::sync::atomic::AtomicBool>,
        calls: Arc<Mutex<Vec<String>>>,
    }
    impl InputAdapter for RecoveringAdapter {
        fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
            if self.ready.load(std::sync::atomic::Ordering::SeqCst) {
                Ok(())
            } else {
                Err("receiver_focused")
            }
        }
        fn execute(&mut self, job: &Execution, _: &InputGate) -> InputOutcome {
            self.calls.lock().unwrap().push(job.id().into());
            InputOutcome::Injected { method: "test" }
        }
    }
    let directory = Directory::new();
    let allowed = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let calls = Arc::new(Mutex::new(Vec::new()));
    let clock = ManualClock::new();
    let ledger = Ledger::open_with_clock(
        &directory.0.join("receiver.sqlite3"),
        Zeroizing::new(vec![1; 32]),
        Box::new(TestProtector),
        clock.clone(),
    )
    .unwrap();
    let (receiver, session, target) = ready_with_ledger(
        ledger,
        RecoveringAdapter {
            ready: allowed.clone(),
            calls: calls.clone(),
        },
    );
    let first = submit(&session, &target, "never replay this old text");
    wait(receiver.submit(session.clone(), first.clone())).unwrap();
    until(&receiver, &session, &first.id, State::Held);
    allowed.store(true, std::sync::atomic::Ordering::SeqCst);
    let second = submit(&session, &target, "fresh valid text");
    wait(receiver.submit(session.clone(), second.clone())).unwrap();
    // Subscription runs after the submit mutation's notification.
    let mut updates = wait(receiver.subscribe()).unwrap();
    while updates.try_recv().is_ok() {}
    clock.advance(Duration::from_secs(600));
    // Only the coordinator's timer can publish the next notification.
    wait(std::future::poll_fn(|_| match updates.try_recv() {
        Ok(()) => Poll::Ready(()),
        Err(_) => Poll::Pending,
    }));
    until(&receiver, &session, &second.id, State::Injected);
    let receipt = wait(receiver.query(session, first.id.clone()))
        .unwrap()
        .unwrap();
    assert_eq!(receipt.st, State::Expired);
    assert_eq!(receipt.reason.as_deref(), Some("hold_expired"));
    assert_eq!(*calls.lock().unwrap(), [second.id]);
    let history =
        wait(receiver.history(voicelink_receiver::history::HistoryQuery::default())).unwrap();
    let expired = history
        .entries
        .iter()
        .find(|e| e.receipt.id == first.id)
        .unwrap();
    assert!(
        expired.operation.is_some(),
        "expiration retains history original"
    );
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn uncertain_result_stops_subsequent_input_and_cannot_be_remotely_resumed() {
    let directory = Directory::new();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready(
        &directory,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: true,
        },
    );
    let first = submit(&session, &target, "partial");
    let second = submit(&session, &target, "blocked");
    wait(receiver.submit(session.clone(), first.clone())).unwrap();
    wait(receiver.submit(session.clone(), second.clone())).unwrap();
    until(&receiver, &session, &first.id, State::Uncertain);
    let state = wait(receiver.state()).unwrap();
    assert!(state.paused);
    assert!(wait(receiver.resume(state.state_rev, false)).is_err());
    assert_eq!(*calls.lock().unwrap(), [first.id]);
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn pause_is_processed_while_native_preflight_is_blocked() {
    struct BlockedPreflight {
        entered: std::sync::mpsc::SyncSender<()>,
        release: std::sync::mpsc::Receiver<()>,
    }
    impl InputAdapter for BlockedPreflight {
        fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
            self.entered.send(()).unwrap();
            self.release.recv_timeout(Duration::from_secs(3)).unwrap();
            Ok(())
        }
        fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
            panic!("paused reservation must never execute")
        }
    }
    let directory = Directory::new();
    let (entered, entry) = std::sync::mpsc::sync_channel(1);
    let (release, wait_release) = std::sync::mpsc::channel();
    let (receiver, session, target) = ready(
        &directory,
        BlockedPreflight {
            entered,
            release: wait_release,
        },
    );
    let submit = submit(&session, &target, "reserved");
    wait(receiver.submit(session.clone(), submit.clone())).unwrap();
    entry.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(
        wait(receiver.pause()).unwrap().paused,
        "DB/control owner is not blocked by input"
    );
    release.send(()).unwrap();
    until(&receiver, &session, &submit.id, State::Held);
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn newer_pause_fences_resume_queued_behind_a_blocked_accept_commit() {
    pause_fence(false);
}
#[test]
fn newer_pause_fences_local_activation_queued_behind_a_blocked_accept_commit() {
    pause_fence(true);
}
fn pause_fence(local: bool) {
    let directory = Directory::new();
    let (entered, entry) = std::sync::mpsc::sync_channel(1);
    let (release, wait_release) = std::sync::mpsc::channel();
    let ledger = Ledger::open(
        &directory.0.join("receiver.sqlite3"),
        Zeroizing::new(vec![1; 32]),
        Box::new(BlockingProtector {
            entered,
            release: wait_release,
        }),
    )
    .unwrap();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let receiver = Receiver::start(
        ledger,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: false,
        },
    )
    .unwrap();
    let device = Uuid::new_v4().to_string();
    wait(receiver.provision_device(device.clone(), Zeroizing::new(vec![2; 32]), String::new()))
        .unwrap();
    let session = wait(receiver.authenticate(device, Zeroizing::new(vec![2; 32]))).unwrap();
    let target = Uuid::new_v4().to_string();
    let state = wait(receiver.bind_target(target.clone())).unwrap();
    let operation = submit(&session, &target, "must remain held");
    let sender = receiver.clone();
    let submit_task = thread::spawn(move || wait(sender.submit(session, operation)));
    entry.recv_timeout(Duration::from_secs(2)).unwrap();

    // Poll once to enqueue, while the coordinator is blocked in protection.
    // A later pause closes the gate before the earlier resume can be processed.
    let waker = Waker::from(Arc::new(WakeThread(thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut stale_resume = pin!(async {
        if local {
            receiver
                .enable_foreground_input(state.state_rev, false)
                .await
        } else {
            receiver.resume(state.state_rev, false).await
        }
    });
    assert!(stale_resume.as_mut().poll(&mut context).is_pending());
    let mut newer_pause = pin!(receiver.pause());
    assert!(newer_pause.as_mut().poll(&mut context).is_pending());
    release.send(()).unwrap();
    assert!(matches!(
        wait(stale_resume),
        Err(Error::Protocol("STATE_CONFLICT"))
    ));
    assert!(wait(newer_pause).unwrap().paused);
    assert_eq!(submit_task.join().unwrap().unwrap().st, State::Held);
    assert!(calls.lock().unwrap().is_empty());
    wait(receiver.clone().shutdown()).unwrap();
}

#[test]
fn local_notifications_coalesce_and_reading_a_snapshot_does_not_wake_again() {
    let directory = Directory::new();
    // No earlier mutations: the initial subscription is the only pending wake.
    let ledger = Ledger::open(
        &directory.0.join("receiver.sqlite3"),
        Zeroizing::new(vec![1; 32]),
        Box::new(TestProtector),
    )
    .unwrap();
    let receiver = Receiver::start(
        ledger,
        Adapter {
            calls: Arc::new(Mutex::new(Vec::new())),
            preflight: true,
            uncertain: false,
        },
    )
    .unwrap();
    let mut updates = wait(receiver.subscribe()).unwrap();
    while updates.try_recv().is_ok() {}
    for _ in 0..20 {
        wait(receiver.snapshot()).unwrap();
    }
    assert!(
        updates.try_recv().is_err(),
        "snapshot reads must not form a feedback loop"
    );
    for _ in 0..100 {
        wait(receiver.pause()).unwrap();
    }
    // Freeze producers before counting: otherwise the last durable pause may
    // publish its wake while the consumer is draining, which is valid behavior.
    updates.close();
    let mut notifications = 0;
    while updates.try_recv().is_ok() {
        notifications += 1;
    }
    assert!(
        (1..=2).contains(&notifications),
        "bounded mailbox coalesces writes"
    );
    let snapshot = wait(receiver.snapshot()).unwrap();
    assert!(snapshot.control.paused);
    assert!(snapshot.storage_healthy);
    assert_eq!(snapshot.paired_devices, 0);
    assert_eq!(snapshot.pending, 0);
    assert!(snapshot.recent.is_empty());
    assert!(updates.try_recv().is_err());
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn shutdown_closes_and_flushes_even_when_the_command_queue_is_full() {
    let directory = Directory::new();
    let (entered, entry) = std::sync::mpsc::sync_channel(1);
    let (release, wait_release) = std::sync::mpsc::channel();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready_with_protector(
        &directory,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: false,
        },
        Box::new(BlockingProtector {
            entered,
            release: wait_release,
        }),
    );
    wait(receiver.pause()).unwrap();
    let operation = submit(&session, &target, "shutdown during acceptance");
    let sender = receiver.clone();
    let accepting = thread::spawn(move || wait(sender.submit(session, operation)));
    entry.recv_timeout(Duration::from_secs(2)).unwrap();
    let waker = Waker::from(Arc::new(WakeThread(thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut queued = (0..64)
        .map(|_| Box::pin(receiver.state()))
        .collect::<Vec<_>>();
    for request in &mut queued {
        assert!(request.as_mut().poll(&mut context).is_pending());
    }
    let mut shutting_down = pin!(receiver.clone().shutdown());
    assert!(shutting_down.as_mut().poll(&mut context).is_pending());
    assert!(matches!(wait(receiver.state()), Err(Error::Unavailable)));
    release.send(()).unwrap();
    wait(shutting_down).unwrap();
    assert_eq!(accepting.join().unwrap().unwrap().st, State::Held);
    for request in queued {
        wait(request).unwrap();
    }
    assert!(calls.lock().unwrap().is_empty());
    // Successful shutdown acknowledges only after the ledger lock is released.
    drop(
        Ledger::open(
            &directory.0.join("receiver.sqlite3"),
            Zeroizing::new(vec![1; 32]),
            Box::new(TestProtector),
        )
        .unwrap(),
    );
}

#[test]
fn local_activation_creates_scope_without_window_binding_and_fences_old_requests() {
    let directory = Directory::new();
    let ledger = Ledger::open(
        &directory.0.join("receiver.sqlite3"),
        Zeroizing::new(vec![1; 32]),
        Box::new(TestProtector),
    )
    .unwrap();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let receiver = Receiver::start(
        ledger,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: false,
        },
    )
    .unwrap();
    let initial = wait(receiver.state()).unwrap();
    assert!(initial.target.is_none());
    assert!(wait(receiver.enable_foreground_input(initial.state_rev.clone(), false)).is_err());
    let device = Uuid::new_v4().to_string();
    wait(receiver.provision_device(device.clone(), Zeroizing::new(vec![2; 32]), String::new()))
        .unwrap();
    let session = wait(receiver.authenticate(device, Zeroizing::new(vec![2; 32]))).unwrap();
    let paired = wait(receiver.state()).unwrap();
    assert!(
        wait(receiver.resume(paired.state_rev.clone(), false)).is_err(),
        "phone cannot create a local activation"
    );
    let active = wait(receiver.enable_foreground_input(paired.state_rev.clone(), false)).unwrap();
    assert!(!active.paused);
    assert!(active.target.is_some());
    assert!(
        wait(receiver.enable_foreground_input(paired.state_rev, false)).is_err(),
        "stale revision must not rotate scope"
    );
    assert!(
        wait(receiver.enable_foreground_input(active.state_rev.clone(), false)).is_err(),
        "already enabled is not a new activation"
    );
    let paused = wait(receiver.pause()).unwrap();
    let previous = submit(
        &session,
        active.target.as_ref().unwrap(),
        "held at previous cursor",
    );
    assert_eq!(
        wait(receiver.submit(session.clone(), previous.clone()))
            .unwrap()
            .st,
        State::Held
    );
    let next = wait(receiver.enable_foreground_input(paused.state_rev, false)).unwrap();
    assert_ne!(next.target, active.target);
    assert_eq!(
        wait(receiver.query(session.clone(), previous.id.clone()))
            .unwrap()
            .unwrap()
            .st,
        State::Held
    );
    assert!(calls.lock().unwrap().is_empty());
    wait(receiver.cancel(session.clone(), previous.id)).unwrap();
    let fresh = submit(&session, next.target.as_ref().unwrap(), "new cursor");
    wait(receiver.submit(session.clone(), fresh.clone())).unwrap();
    until(&receiver, &session, &fresh.id, State::Injected);
    assert_eq!(*calls.lock().unwrap(), [fresh.id]);
    wait(receiver.shutdown()).unwrap();
}

#[test]
fn local_new_scope_does_not_bypass_uncertain_review_or_replay_partial_input() {
    let directory = Directory::new();
    let calls = Arc::new(Mutex::new(Vec::new()));
    let (receiver, session, target) = ready(
        &directory,
        Adapter {
            calls: calls.clone(),
            preflight: true,
            uncertain: true,
        },
    );
    let first = submit(&session, &target, "partial result");
    wait(receiver.submit(session.clone(), first.clone())).unwrap();
    until(&receiver, &session, &first.id, State::Uncertain);
    let state = wait(receiver.state()).unwrap();
    assert!(wait(receiver.enable_foreground_input(state.state_rev.clone(), false)).is_err());
    assert_eq!(
        wait(receiver.state()).unwrap().target.as_deref(),
        Some(target.as_str())
    );
    let reviewed = wait(receiver.enable_foreground_input(state.state_rev, true)).unwrap();
    assert_ne!(reviewed.target.as_deref(), Some(target.as_str()));
    assert_eq!(
        wait(receiver.query(session, first.id.clone()))
            .unwrap()
            .unwrap()
            .st,
        State::UncertainAcknowledged
    );
    assert_eq!(*calls.lock().unwrap(), [first.id]);
    wait(receiver.shutdown()).unwrap();
}
