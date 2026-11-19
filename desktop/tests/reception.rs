//! Exercise real DPAPI/SQLite startup, failure recovery and close races.
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};
static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);
use voicelink_desktop::reception::{ReceptionPhase, ReceptionState};
use yue_component::gpui_kit::{self, AppContext as _, Entity, TestAppContext};

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "voicelink-reception-{}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn await_phase(cx: &mut TestAppContext, state: &Entity<ReceptionState>, phase: ReceptionPhase) {
    let started = Instant::now();
    loop {
        cx.run_until_parked();
        if state.read_with(cx, |s, _| s.phase() == phase) {
            break;
        }
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "receiver phase timed out: {phase:?}"
        );
        std::thread::sleep(Duration::from_millis(1));
    }
}

#[gpui_kit::test]
fn startup_failure_preserves_data_and_retry_uses_the_same_directory(cx: &mut TestAppContext) {
    cx.executor().allow_parking();
    let directory = Directory::new();
    let target = directory.0.join("receiver");
    fs::write(&target, b"keep this existing file").unwrap();
    let state =
        cx.update(|cx| cx.new(|cx| ReceptionState::new(target.clone(), Default::default(), cx)));
    await_phase(cx, &state, ReceptionPhase::Failed);
    assert_eq!(fs::read(&target).unwrap(), b"keep this existing file");
    fs::remove_file(&target).unwrap();
    state.update(cx, |s, cx| s.retry_start(cx));
    await_phase(cx, &state, ReceptionPhase::Ready);
    assert!(state.read_with(cx, |s, _| s.snapshot().unwrap().control.paused));
    state.update(cx, |s, cx| s.stop(cx));
    await_phase(cx, &state, ReceptionPhase::Stopped);
    drop(voicelink_receiver::windows_input::open_ledger(&target).unwrap());
}

#[gpui_kit::test]
fn close_during_startup_waits_for_open_and_releases_the_ledger(cx: &mut TestAppContext) {
    cx.executor().allow_parking();
    let directory = Directory::new();
    let target = directory.0.join("receiver");
    let state =
        cx.update(|cx| cx.new(|cx| ReceptionState::new(target.clone(), Default::default(), cx)));
    state.update(cx, |s, cx| s.stop(cx));
    assert!(!state.read_with(cx, |s, _| s.is_stopped()));
    await_phase(cx, &state, ReceptionPhase::Stopped);
    drop(voicelink_receiver::windows_input::open_ledger(&target).unwrap());
}
