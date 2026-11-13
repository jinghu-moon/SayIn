//! Opt-in real SendInput verification against this executable's own child EDIT.
//! No production data, network, clipboard or user application is used.
use std::{
    future::Future,
    path::Path,
    process::Command,
    sync::Arc,
    task::{Context, Poll, Wake, Waker},
    time::{Duration, Instant},
};
use uuid::Uuid;
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    protocol::{Operation, State, Submit, TextSource},
    windows_input::{NativeInput, open_ledger},
    worker::{InputAdapter, Receiver},
};
use windows::{
    Win32::{
        Foundation::{LPARAM, WPARAM},
        UI::{
            Controls::EM_SETSEL,
            Input::KeyboardAndMouse::{GetFocus, SetFocus},
            WindowsAndMessaging::*,
        },
    },
    core::w,
};
use zeroize::Zeroizing;

const ORIGINAL: &str = "existing|";
const TEXT: &str = "中文 😀 literal {ENTER}";

fn wait<T>(future: impl Future<Output = T>) -> T {
    struct ThreadWake(std::thread::Thread);
    impl Wake for ThreadWake {
        fn wake(self: Arc<Self>) {
            self.0.unpark();
        }
    }
    let waker = Waker::from(Arc::new(ThreadWake(std::thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);
    loop {
        match future.as_mut().poll(&mut context) {
            Poll::Ready(value) => return value,
            Poll::Pending => std::thread::park(),
        }
    }
}

fn edit_target(directory: &Path) -> Result<(), Box<dyn std::error::Error>> {
    // SAFETY: This process creates, focuses, pumps and destroys only its own
    // disposable EDIT. The parent validates its HWND and PID before injection.
    unsafe {
        let hwnd = CreateWindowExW(
            Default::default(),
            w!("EDIT"),
            w!("existing|"),
            WS_OVERLAPPEDWINDOW | WS_VISIBLE | WINDOW_STYLE(ES_MULTILINE as u32),
            100,
            100,
            640,
            240,
            None,
            None,
            None,
            None,
        )?;
        let _ = SetForegroundWindow(hwnd);
        let _ = SetFocus(Some(hwnd));
        SendMessageW(
            hwnd,
            EM_SETSEL,
            Some(WPARAM(ORIGINAL.len())),
            Some(LPARAM(ORIGINAL.len() as isize)),
        );
        let deadline = Instant::now() + Duration::from_secs(20);
        let mut recorded = false;
        let mut ready = false;
        while Instant::now() < deadline && !directory.join("stop").exists() {
            let mut message = MSG::default();
            while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            if !ready {
                let _ = SetForegroundWindow(hwnd);
                let _ = SetFocus(Some(hwnd));
                if GetForegroundWindow() == hwnd && GetFocus() == hwnd {
                    SendMessageW(
                        hwnd,
                        EM_SETSEL,
                        Some(WPARAM(ORIGINAL.len())),
                        Some(LPARAM(ORIGINAL.len() as isize)),
                    );
                    std::fs::write(
                        directory.join("window.json"),
                        serde_json::to_vec(
                            &serde_json::json!({"hwnd":hwnd.0 as usize,"pid":std::process::id()}),
                        )?,
                    )?;
                    ready = true;
                }
            }
            let mut buffer = [0u16; 256];
            let length = GetWindowTextW(hwnd, &mut buffer).max(0) as usize;
            let text = String::from_utf16_lossy(&buffer[..length]);
            if !recorded && text == format!("{ORIGINAL}{TEXT}") {
                std::fs::write(
                    directory.join("observed.json"),
                    serde_json::to_vec(&serde_json::json!({"text":text,"pid":std::process::id()}))?,
                )?;
                recorded = true;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let mut buffer = [0u16; 256];
        let length = GetWindowTextW(hwnd, &mut buffer).max(0) as usize;
        eprintln!(
            "Own EDIT final text: {:?}",
            String::from_utf16_lossy(&buffer[..length])
        );
        DestroyWindow(hwnd)?;
    }
    Ok(())
}

struct FixtureInput {
    native: NativeInput,
    hwnd: usize,
    pid: u32,
}
impl FixtureInput {
    fn is_fixture(&self) -> bool {
        unsafe {
            let foreground = GetForegroundWindow();
            let mut pid = 0;
            GetWindowThreadProcessId(foreground, Some(&mut pid));
            foreground.0 as usize == self.hwnd && pid == self.pid
        }
    }
}
impl InputAdapter for FixtureInput {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
        if !self.is_fixture() {
            return Err("fixture_not_foreground");
        }
        self.native.preflight(job)
    }
    fn prepared_application(&self) -> Option<voicelink_receiver::history::TargetApplication> {
        self.native.prepared_application()
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        if !self.is_fixture() {
            return InputOutcome::Failed {
                reason: "fixture_not_foreground",
            };
        }
        self.native.execute(job, gate)
    }
}

fn verify(directory: &Path, child_pid: u32) -> Result<(), Box<dyn std::error::Error>> {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !directory.join("window.json").exists() {
        if Instant::now() >= deadline {
            return Err("fixture window not created".into());
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let window: serde_json::Value =
        serde_json::from_slice(&std::fs::read(directory.join("window.json"))?)?;
    assert_eq!(window["pid"], child_pid);
    let receiver = Receiver::start(
        open_ledger(&directory.join("ledger"))?,
        FixtureInput {
            native: NativeInput::default(),
            hwnd: window["hwnd"].as_u64().unwrap() as usize,
            pid: child_pid,
        },
    )?;
    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        let device = Uuid::new_v4().to_string();
        wait(receiver.provision_device(
            device.clone(),
            Zeroizing::new(vec![88; 32]),
            "Own EDIT fixture".into(),
        ))?;
        let session = wait(receiver.authenticate(device, Zeroizing::new(vec![88; 32])))?;
        let state = wait(receiver.state())?;
        let target = wait(receiver.enable_foreground_input(state.state_rev, false))?
            .target
            .unwrap();
        let operation = Submit::new(
            session.id().into(),
            Uuid::new_v4().to_string(),
            target,
            Operation::Text {
                text: TEXT.into(),
                src: TextSource::Manual,
            },
        );
        wait(receiver.submit(session.clone(), operation.clone()))?;
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let receipt = wait(receiver.query(session.clone(), operation.id.clone()))?.unwrap();
            if receipt.st == State::Injected {
                break;
            }
            if receipt.st == State::Held || receipt.st.is_terminal() || Instant::now() >= deadline {
                return Err(format!(
                    "native receipt={:?}, reason={:?}",
                    receipt.st, receipt.reason
                )
                .into());
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        while !directory.join("observed.json").exists() {
            if Instant::now() >= deadline {
                return Err(
                    "SendInput returned but EDIT did not contain exact expected text".into(),
                );
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let observed: serde_json::Value =
            serde_json::from_slice(&std::fs::read(directory.join("observed.json"))?)?;
        assert_eq!(observed["text"], format!("{ORIGINAL}{TEXT}"));
        assert_eq!(
            wait(receiver.submit(session, operation))?.st,
            State::Injected
        );
        let history = wait(receiver.history(Default::default()))?;
        assert_eq!(history.entries.len(), 1);
        assert_eq!(
            history.entries[0].target.as_ref().unwrap().process_id,
            child_pid
        );
        println!(
            "PASS: real native EDIT received exact CJK + emoji + literal {{ENTER}} at caret; prefix preserved; no appended Enter; duplicate did not execute; target PID recorded."
        );
        Ok(())
    })();
    wait(receiver.shutdown())?;
    result
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments = std::env::args().collect::<Vec<_>>();
    if arguments.get(1).is_some_and(|arg| arg == "--edit-target") {
        return edit_target(Path::new(&arguments[2]));
    }
    let directory = std::env::temp_dir().join(format!("voicelink-real-edit-{}", Uuid::new_v4()));
    std::fs::create_dir_all(directory.join("ledger"))?;
    let mut child = Command::new(std::env::current_exe()?)
        .arg("--edit-target")
        .arg(&directory)
        .spawn()?;
    let result = verify(&directory, child.id());
    std::fs::write(directory.join("stop"), b"")?;
    let child_status = child.wait()?;
    std::fs::remove_dir_all(&directory)?;
    result?;
    if !child_status.success() {
        return Err("EDIT child failed".into());
    }
    Ok(())
}
