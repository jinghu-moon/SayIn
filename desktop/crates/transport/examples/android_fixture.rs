//! Opt-in isolated Android fixture: fake input and test-only local approval.
//! Loopback by default; --lan explicitly uses one validated physical interface.
//! Never link this into the product or use a daily identity/data directory.
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};
use uuid::Uuid;
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    windows_input::{NativeInput, open_ledger},
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::{HostIdentity, server::NetworkService, storage::secure_directory};

struct FakeInput {
    calls: Arc<AtomicUsize>,
    pause: Option<std::sync::mpsc::SyncSender<String>>,
}
impl InputAdapter for FakeInput {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
        if let Some(pause) = &self.pause
            && matches!(job.operation(), voicelink_receiver::protocol::Operation::Text { text, .. } if text == "fixture: pause upload")
        {
            pause
                .try_send(job.id().into())
                .expect("bounded pause probe");
            return Err("fixture_pause_probe");
        }
        // Test-only stimulus for checking pushed held/expired receipts all the
        // way through Android's actor and Room. Never linked into the product.
        if matches!(job.operation(), voicelink_receiver::protocol::Operation::Text { text, .. }
            if text == "fixture: hold without input")
        {
            return Err("receiver_focused");
        }
        Ok(())
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        assert!(!gate.is_paused());
        self.calls.fetch_add(1, Ordering::SeqCst);
        // Test-only uncertain result exercises the real pushed desktop pause reason.
        if matches!(job.operation(), voicelink_receiver::protocol::Operation::Text { text, .. }
            if text == "fixture: uncertain")
        {
            return InputOutcome::Uncertain {
                reason: "fixture_uncertain",
            };
        }
        InputOutcome::Injected {
            method: "android_test_fake",
        }
    }
}

// Opt-in production NativeInput, guarded against all windows except the child
// RichEdit started by this fixture. Never sends to an existing application.
struct NativeFixture {
    native: NativeInput,
    target: std::sync::Arc<DisposableTarget>,
    calls: Arc<AtomicUsize>,
}
impl NativeFixture {
    fn is_target(&self) -> bool {
        use windows::Win32::UI::WindowsAndMessaging::{
            GetForegroundWindow, GetWindowThreadProcessId,
        };
        unsafe {
            let hwnd = GetForegroundWindow();
            let mut pid = 0;
            GetWindowThreadProcessId(hwnd, Some(&mut pid));
            pid == self.target.pid && hwnd.0 as usize == self.target.hwnd
        }
    }
}
impl InputAdapter for NativeFixture {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
        if !self.is_target() {
            return Err("fixture_not_foreground");
        }
        self.native.preflight(job)
    }
    fn prepared_application(&self) -> Option<voicelink_receiver::history::TargetApplication> {
        self.native.prepared_application()
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        if !self.is_target() {
            return InputOutcome::Failed {
                reason: "fixture_not_foreground",
            };
        }
        let outcome = self.native.execute(job, gate);
        let expected = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
        if matches!(outcome, InputOutcome::Injected { .. }) {
            // Additional test-only assertion, not a new production receipt
            // promise. The conservative latency includes the target RTF check.
            let deadline = std::time::Instant::now() + Duration::from_secs(15);
            loop {
                if std::fs::read(self.target.directory.join("observed.json"))
                    .ok()
                    .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
                    .is_some_and(|value| {
                        value["observed_images"].as_u64() == Some(expected as u64)
                            && value["pictures"] == 1
                    })
                {
                    break;
                }
                if std::time::Instant::now() >= deadline {
                    return InputOutcome::Uncertain {
                        reason: "fixture_target_not_observed",
                    };
                }
                std::thread::sleep(Duration::from_millis(2));
            }
        }
        outcome
    }
}
struct DisposableTarget {
    directory: PathBuf,
    pid: u32,
    hwnd: usize,
    child: std::sync::Mutex<std::process::Child>,
}
impl DisposableTarget {
    fn start(directory: PathBuf) -> Result<Arc<Self>, Box<dyn std::error::Error>> {
        std::fs::create_dir(&directory)?;
        let child = std::process::Command::new(
            std::env::current_exe()?.with_file_name("native_input_fixture.exe"),
        )
        .arg("--edit-target")
        .arg(&directory)
        .args(["--image", "--batch"])
        .spawn()?;
        let pid = child.id();
        let mut target = Self {
            directory,
            pid,
            hwnd: 0,
            child: std::sync::Mutex::new(child),
        };
        let deadline = std::time::Instant::now() + Duration::from_secs(8);
        loop {
            if let Some(value) = std::fs::read(target.directory.join("window.json"))
                .ok()
                .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
            {
                if value["pid"].as_u64() != Some(u64::from(pid)) {
                    return Err("wrong disposable target identity".into());
                }
                target.hwnd = value["hwnd"].as_u64().ok_or("missing own HWND")? as usize;
                return Ok(Arc::new(target));
            }
            if std::time::Instant::now() >= deadline {
                return Err("disposable target could not focus; unlock Windows".into());
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    }
}
impl Drop for DisposableTarget {
    fn drop(&mut self) {
        let _ = std::fs::write(self.directory.join("stop"), b"");
        if let Ok(child) = self.child.get_mut() {
            let deadline = std::time::Instant::now() + Duration::from_secs(5);
            while child.try_wait().ok().flatten().is_none() && std::time::Instant::now() < deadline
            {
                std::thread::sleep(Duration::from_millis(10));
            }
            if child.try_wait().ok().flatten().is_none() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}
enum FixtureInput {
    Fake(FakeInput),
    Native(Box<NativeFixture>),
    // Explicit human acceptance mode: no generated uploads or input. Only the
    // paired isolated phone's user can submit; all production guards remain.
    Manual(Box<NativeInput>, Arc<AtomicUsize>),
}
impl InputAdapter for FixtureInput {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
        match self {
            Self::Fake(v) => v.preflight(job),
            Self::Native(v) => v.preflight(job),
            Self::Manual(v, _) => v.preflight(job),
        }
    }
    fn prepared_application(&self) -> Option<voicelink_receiver::history::TargetApplication> {
        match self {
            Self::Fake(v) => v.prepared_application(),
            Self::Native(v) => v.prepared_application(),
            Self::Manual(v, _) => v.prepared_application(),
        }
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        match self {
            Self::Fake(v) => v.execute(job, gate),
            Self::Native(v) => v.execute(job, gate),
            Self::Manual(v, calls) => {
                calls.fetch_add(1, Ordering::SeqCst);
                v.execute(job, gate)
            }
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    if std::env::args().any(|argument| argument == "--check-network-profile") {
        let interfaces = voicelink_transport::interfaces::available_interfaces()?;
        println!(
            "Windows Network List Manager queried; available IPv4 count: {}",
            interfaces.len()
        );
        for interface in interfaces {
            println!(
                "{} {} public={} default_permitted={}",
                interface.name,
                interface.address,
                interface.is_public(),
                interface.binding().is_ok()
            );
        }
        return Ok(());
    }
    let output = PathBuf::from(
        std::env::args()
            .nth(1)
            .ok_or("fixture output directory required")?,
    );
    secure_directory(&output)?;
    let calls = Arc::new(AtomicUsize::new(0));
    let mut ledger = open_ledger(&output)?;
    let identity = HostIdentity::open(&output, 0)?;
    let image_store = if std::env::args().any(|arg| arg == "--images") {
        let images = output.join("images");
        secure_directory(&images)?;
        let store =
            voicelink_receiver::images::storage::ImageStore::open(&images, identity.host_id())?;
        ledger.attach_images(store.clone())?;
        Some(store)
    } else {
        None
    };
    let disposable = std::env::args().any(|arg| arg == "--native-target");
    let manual = std::env::args().any(|arg| arg == "--manual-native");
    let pause_probe = std::env::args().any(|arg| arg == "--pause-probe");
    if manual && disposable {
        return Err("Choose exactly one native target mode".into());
    }
    let native = disposable || manual;
    if pause_probe && (native || image_store.is_none()) {
        return Err("--pause-probe requires --images and forbids real input".into());
    }
    let (pause_sender, pause_events) = std::sync::mpsc::sync_channel(1);
    let target = if disposable {
        Some(DisposableTarget::start(output.join("target"))?)
    } else {
        None
    };
    let adapter = if let Some(target) = target.clone() {
        FixtureInput::Native(Box::new(NativeFixture {
            native: NativeInput::with_images(
                image_store.ok_or("--native-target requires --images")?,
            ),
            target,
            calls: calls.clone(),
        }))
    } else if manual {
        eprintln!(
            "MANUAL ACCEPTANCE: only explicit requests from the isolated paired phone reach current foreground. No generated input; no Enter is appended."
        );
        FixtureInput::Manual(
            Box::new(NativeInput::with_images(
                image_store.ok_or("--manual-native requires --images")?,
            )),
            calls.clone(),
        )
    } else {
        FixtureInput::Fake(FakeInput {
            calls: calls.clone(),
            pause: pause_probe.then_some(pause_sender),
        })
    };
    let receiver = Receiver::start(ledger, adapter)?;
    let port = std::env::args()
        .find_map(|arg| arg.strip_prefix("--port=").map(str::to_owned))
        .map(|port| port.parse::<u16>())
        .transpose()?
        .unwrap_or(17888);
    let service = if std::env::args().any(|arg| arg == "--lan") {
        let binding = voicelink_transport::interfaces::available_interfaces()?
            .into_iter()
            .find(|interface| interface.is_physical() && interface.binding().is_ok())
            .ok_or("no valid physical LAN interface")?
            .binding()?;
        NetworkService::start_on_network(receiver.clone(), identity, binding, port)?
    } else {
        NetworkService::start_loopback(
            receiver.clone(),
            identity,
            std::net::SocketAddr::from(([127, 0, 0, 1], port)),
        )?
    };
    service.open_pairing().await?;
    let snapshot = service.snapshot()?;
    let window = snapshot.window.ok_or("pairing window missing")?;
    std::fs::write(
        output.join("fixture.json"),
        serde_json::to_vec(
            &serde_json::json!({"address":snapshot.address.ip().to_string(),"port":snapshot.address.port(),"code":window.code.to_string()}),
        )?,
    )?;
    println!("Isolated Android test fixture ready; native_child_target={native}.");
    let target = Uuid::new_v4().to_string();
    let lifetime = if std::env::args().any(|arg| arg == "--benchmark") {
        600
    } else {
        180
    };
    let until = std::time::Instant::now() + Duration::from_secs(lifetime);
    let mut ready = false;
    let mut paused_request: Option<String> = None;
    let mut pause_measurements = Vec::new();
    while std::time::Instant::now() < until {
        if let Some(approval) = service.snapshot()?.approval {
            service.decide(&approval.id, true).await?;
        }
        if !ready && receiver.snapshot().await?.paired_devices > 0 {
            let state = receiver.bind_target(target.clone()).await?;
            receiver.resume(state.state_rev, false).await?;
            ready = true;
        }
        if pause_probe {
            if let Ok(request) = pause_events.try_recv() {
                assert!(paused_request.is_none());
                let started = std::time::Instant::now();
                let state = receiver.pause().await?;
                assert!(state.paused);
                pause_measurements.push(started.elapsed().as_secs_f64() * 1000.);
                paused_request = Some(request);
                std::fs::write(
                    output.join("pause-metrics.json"),
                    serde_json::to_vec(&pause_measurements)?,
                )?;
            }
            if let Some(request) = &paused_request {
                // Test-only emulated local resume, after the phone has checked
                // pause/reconnect/no-replay and explicitly canceled its marker.
                // No such remote pause/resume hook exists in the product.
                let page = receiver.history(Default::default()).await?;
                if page.entries.iter().any(|entry| {
                    entry.receipt.id == *request
                        && entry.receipt.st == voicelink_receiver::protocol::State::Cancelled
                }) {
                    receiver
                        .resume(receiver.state().await?.state_rev, false)
                        .await?;
                    paused_request = None;
                }
            }
        }
        std::fs::write(
            output.join("calls.json"),
            serde_json::to_vec(
                &serde_json::json!({"fake_input_calls":if native {0} else {calls.load(Ordering::SeqCst)},"native_input_calls":if native {calls.load(Ordering::SeqCst)} else {0}}),
            )?,
        )?;
        if output.join("stop").exists() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    service.shutdown().await?;
    receiver.shutdown().await?;
    Ok(())
}
