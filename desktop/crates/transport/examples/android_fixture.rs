//! Opt-in isolated Android fixture: fake input and test-only local approval.
//! Loopback by default; --lan explicitly uses one validated physical interface.
//! Never link this into the product or use a daily identity/data directory.
#[path = "../../../tests/support/manual_clock.rs"]
mod manual_clock;

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
    protocol::Operation,
    windows_input::{
        NativeInput, open_ledger_with_clock_and_retention, open_ledger_with_retention,
    },
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::{HostIdentity, server::NetworkService, storage::secure_directory};

struct FakeInput {
    calls: Arc<AtomicUsize>,
    pause: Option<std::sync::mpsc::SyncSender<String>>,
    image_path_receipt: bool,
    batch_release: Option<std::sync::mpsc::Receiver<()>>,
}
impl InputAdapter for FakeInput {
    fn prepared_application(&self) -> Option<voicelink_receiver::history::TargetApplication> {
        let mut target = voicelink_receiver::history::TargetApplication::new(
            "C:\\fixture\\Editor.exe".into(),
            "fixture".into(),
            1,
            1,
        );
        target.display = Some(voicelink_receiver::application_icon::ApplicationDisplay {
            name: "Fixture Editor".into(),
            icon_webp: voicelink_receiver::application_icon::webp(
                &std::path::PathBuf::from(std::env::var("WINDIR").ok()?).join("explorer.exe"),
            ),
        });
        Some(target)
    }
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
        if let Some(release) = self.batch_release.take()
            && release.recv_timeout(Duration::from_secs(25)).is_err()
        {
            return InputOutcome::Failed {
                reason: "fixture_release_timeout",
            };
        }
        // Test-only uncertain result exercises the real pushed desktop pause reason.
        if matches!(job.operation(), voicelink_receiver::protocol::Operation::Text { text, .. }
            if text == "fixture: uncertain")
        {
            return InputOutcome::Uncertain {
                reason: "fixture_uncertain",
            };
        }
        InputOutcome::Injected {
            method: if self.image_path_receipt
                && matches!(
                    job.operation(),
                    voicelink_receiver::protocol::Operation::Image { .. }
                ) {
                "clipboard_image_path"
            } else {
                "android_test_fake"
            },
        }
    }
}

// Opt-in production NativeInput, guarded against all windows except the child
// RichEdit started by this fixture. Never sends to an existing application.
struct NativeFixture {
    native: NativeInput,
    target: std::sync::Arc<DisposableTarget>,
    calls: Arc<AtomicUsize>,
    benchmark: bool,
    native_text: bool,
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
        if self.native_text
            && !matches!(job.operation(),
            voicelink_receiver::protocol::Operation::Text { text, .. } if text == "中文 😀 literal {ENTER}")
        {
            return Err("fixture_unexpected_text");
        }
        let start = std::time::Instant::now();
        let result = self.native.preflight(job);
        if self.native_text {
            eprintln!("text_preflight {} {}", job.id(), start.elapsed().as_nanos());
        }
        if self.benchmark
            && let voicelink_receiver::protocol::Operation::Image { image } = job.operation()
        {
            eprintln!(
                "image_preflight {} {}",
                image.resource_id,
                start.elapsed().as_nanos()
            );
        }
        result
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
        let start = std::time::Instant::now();
        let outcome = self.native.execute(job, gate);
        let submitted = std::time::Instant::now();
        let expected = self.calls.fetch_add(1, Ordering::SeqCst) + 1;
        let image = matches!(
            outcome,
            InputOutcome::Injected {
                method: "clipboard_image"
            }
        );
        let text =
            self.native_text && matches!(outcome, InputOutcome::Injected { method: "unicode" });
        if image || text {
            // Additional test-only assertion, not a new production receipt
            // promise. The conservative latency includes the target RTF check.
            let deadline = submitted + Duration::from_secs(15);
            loop {
                if std::fs::read(self.target.directory.join("observed.json"))
                    .ok()
                    .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
                    .is_some_and(|value| {
                        if text {
                            value["observed_texts"].as_u64() == Some(expected as u64)
                        } else {
                            value["observed_images"].as_u64() == Some(expected as u64)
                                && value["pictures"] == 1
                        }
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
            if text {
                eprintln!(
                    "text_native {} {} {}",
                    job.id(),
                    submitted.duration_since(start).as_nanos(),
                    submitted.elapsed().as_nanos()
                );
            }
            if self.benchmark
                && let voicelink_receiver::protocol::Operation::Image { image } = job.operation()
            {
                eprintln!(
                    "image_native {} {} {}",
                    image.resource_id,
                    submitted.duration_since(start).as_nanos(),
                    submitted.elapsed().as_nanos()
                );
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
    fn start(
        directory: PathBuf,
        count_objects: bool,
        native_text: bool,
    ) -> Result<Arc<Self>, Box<dyn std::error::Error>> {
        std::fs::create_dir(&directory)?;
        let mut command = std::process::Command::new(
            std::env::current_exe()?.with_file_name("native_input_fixture.exe"),
        );
        command
            .arg("--edit-target")
            .arg(&directory)
            .args([if native_text { "--text" } else { "--image" }, "--batch"])
            .stderr(std::fs::File::create(directory.join("stderr.log"))?);
        if count_objects {
            command.arg("--count-objects");
        }
        let child = command.spawn()?;
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

struct RegistrationLog;

impl log::Log for RegistrationLog {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        matches!(
            metadata.target(),
            "sayin_image_registration" | "sayin_receipt_sent"
        ) && metadata.level() == log::Level::Trace
    }

    fn log(&self, record: &log::Record<'_>) {
        if self.enabled(record.metadata()) {
            let label = if record.target() == "sayin_receipt_sent" {
                "receipt_sent"
            } else {
                "image_registered"
            };
            eprintln!("{label} {}", record.args());
        }
    }

    fn flush(&self) {}
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
    let hold_retention = std::env::args()
        .find_map(|arg| {
            arg.strip_prefix("--hold-retention-seconds=")
                .map(|value| value.parse::<u64>())
        })
        .transpose()?
        .map(Duration::from_secs)
        .unwrap_or(voicelink_receiver::ledger::DEFAULT_HOLD_RETENTION);
    let expiry_probe = std::env::args().any(|arg| arg == "--pair-replacement-expiry");
    let clock = expiry_probe.then(manual_clock::ManualClock::new);
    let mut ledger = if let Some(clock) = &clock {
        open_ledger_with_clock_and_retention(&output, clock.clone(), hold_retention)?
    } else {
        open_ledger_with_retention(&output, hold_retention)?
    };
    let text_commits = if std::env::args().any(|arg| arg == "--text-commit-timing") {
        let (sender, events) = std::sync::mpsc::sync_channel(900);
        let origin = std::time::Instant::now();
        ledger.observe_requests(move |op, id, stage, at| {
            if matches!(op, Operation::Text { .. }) {
                let _ =
                    sender.try_send((id.to_owned(), stage, at.duration_since(origin).as_nanos()));
            }
        });
        Some(events)
    } else {
        None
    };
    #[cfg(windows)]
    if std::env::args().any(|arg| arg == "--image-commit-timing") {
        assert!(text_commits.is_none(), "one commit timing mode per fixture");
        ledger.observe_requests(|op, id, stage, _| {
            if let Operation::Image { image } = op
                && matches!(stage, "accepted" | "executing" | "injected")
            {
                let mut ticks = 0;
                if unsafe {
                    windows::Win32::System::Performance::QueryPerformanceCounter(&mut ticks)
                }
                .is_ok()
                {
                    eprintln!("image_commit {} {id} {stage} {ticks}", image.resource_id);
                }
            }
        });
    }
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
    let native_text = std::env::args().any(|arg| arg == "--native-text-target");
    let disposable = native_text || std::env::args().any(|arg| arg == "--native-target");
    let path_receipt = std::env::args().any(|arg| arg == "--image-path-receipt");
    let benchmark = std::env::args().any(|arg| arg == "--benchmark");
    if disposable && benchmark {
        log::set_logger(&RegistrationLog).map_err(|_| "registration logger already set")?;
        log::set_max_level(log::LevelFilter::Trace);
    }
    let manual = std::env::args().any(|arg| arg == "--manual-native");
    let pause_probe = std::env::args().any(|arg| arg == "--pause-probe");
    let upgrade_probe = std::env::args().any(|arg| arg == "--upgrade-probe");
    if manual && disposable {
        return Err("Choose exactly one native target mode".into());
    }
    let native = disposable || manual;
    if pause_probe && (native || image_store.is_none()) {
        return Err("--pause-probe requires --images and forbids real input".into());
    }
    let batch_switch = std::env::args().any(|arg| arg == "--batch-switch");
    let batch_revoke = std::env::args().any(|arg| arg == "--batch-revoke");
    if (batch_switch || batch_revoke)
        && (native || image_store.is_none() || pause_probe || (batch_switch && batch_revoke))
    {
        return Err("Batch interruption requires an isolated fake image adapter".into());
    }
    let (release, batch_release) = std::sync::mpsc::sync_channel(1);
    let (pause_sender, pause_events) = std::sync::mpsc::sync_channel(1);
    let target = if disposable {
        Some(DisposableTarget::start(
            output.join("target"),
            benchmark,
            native_text,
        )?)
    } else {
        None
    };
    let mut application_modes = std::collections::BTreeMap::new();
    if disposable && path_receipt {
        let executable = std::env::current_exe()?.with_file_name("native_input_fixture.exe");
        application_modes.insert(
            executable
                .to_str()
                .ok_or("non-Unicode fixture path")?
                .to_owned(),
            voicelink_receiver::windows_image_delivery::ImageDeliveryMode::CopyPath,
        );
    }
    let image_delivery = Arc::new(std::sync::RwLock::new(
        voicelink_receiver::windows_image_delivery::ImageDeliverySettings::new(
            Some(output.join("exports")),
            application_modes,
        ),
    ));
    let adapter = if let Some(target) = target.clone() {
        FixtureInput::Native(Box::new(NativeFixture {
            native: NativeInput::with_images(
                image_store.ok_or("--native-target requires --images")?,
                image_delivery,
            )
            .with_image_timing(benchmark)
            .with_text_timing(native_text),
            target,
            calls: calls.clone(),
            benchmark,
            native_text,
        }))
    } else if manual {
        eprintln!(
            "MANUAL ACCEPTANCE: only explicit requests from the isolated paired phone reach current foreground. No generated input; no Enter is appended."
        );
        FixtureInput::Manual(
            Box::new(NativeInput::with_images(
                image_store.ok_or("--manual-native requires --images")?,
                image_delivery,
            )),
            calls.clone(),
        )
    } else {
        FixtureInput::Fake(FakeInput {
            calls: calls.clone(),
            pause: pause_probe.then_some(pause_sender),
            image_path_receipt: path_receipt,
            batch_release: (batch_switch || batch_revoke).then_some(batch_release),
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
    let lifetime = std::env::args()
        .find_map(|arg| {
            arg.strip_prefix("--lifetime=")
                .and_then(|value| value.parse().ok())
        })
        .unwrap_or(if benchmark { 600 } else { 180 });
    if !upgrade_probe {
        service
            .open_pairing_with_lifetime(Duration::from_secs(lifetime))
            .await?;
    }
    let snapshot = service.snapshot()?;
    let replacement_control =
        if expiry_probe || std::env::args().any(|arg| arg == "--pair-replacement") {
            Some(tokio::net::TcpListener::bind(("127.0.0.1", 0)).await?)
        } else {
            None
        };
    let replacement_port = replacement_control
        .as_ref()
        .map(|listener| listener.local_addr())
        .transpose()?
        .map(|address| address.port());
    let batch_control = if batch_switch || batch_revoke {
        Some(tokio::net::TcpListener::bind(("127.0.0.1", 0)).await?)
    } else {
        None
    };
    let batch_port = batch_control
        .as_ref()
        .map(|listener| listener.local_addr())
        .transpose()?
        .map(|address| address.port());
    let batch_task = batch_control.map(|listener| {
        let receiver = receiver.clone();
        tokio::spawn(async move {
            use tokio::io::{AsyncReadExt as _, AsyncWriteExt as _};
            let (mut peer, _) = listener.accept().await?;
            let command = tokio::time::timeout(Duration::from_secs(5), peer.read_u8()).await??;
            if command != 1 {
                return Err("Unexpected batch control command".into());
            }
            if batch_revoke {
                let devices = receiver.snapshot().await?.device_ids;
                let [device] = devices.as_slice() else {
                    return Err("Expected one isolated pair".into());
                };
                receiver.revoke(device.clone()).await?;
            }
            release.send(())?;
            peer.write_u8(1).await?;
            Ok::<_, Box<dyn std::error::Error + Send + Sync>>(())
        })
    });
    let code = snapshot.window.map(|window| window.code.to_string());
    if !upgrade_probe && code.is_none() {
        return Err("pairing window missing".into());
    }
    let qr = if std::env::args().any(|arg| arg == "--qr-pairing") {
        let code = code.clone().ok_or("pairing code missing")?;
        let address = snapshot.address;
        let invitation = tokio::task::spawn_blocking(move || {
            voicelink_pairing::pair_code(address.ip(), address.port(), &code)
        })
        .await??;
        Some(voicelink_pairing::encode(invitation.qr()?.as_bytes()))
    } else {
        None
    };
    std::fs::write(
        output.join("fixture.json"),
        serde_json::to_vec(
            &serde_json::json!({"address":snapshot.address.ip().to_string(),"port":snapshot.address.port(),"code":code,"qr":qr,"replacement_port":replacement_port,"batch_port":batch_port}),
        )?,
    )?;
    println!("Isolated Android test fixture ready; native_child_target={native}.");
    let target = Uuid::new_v4().to_string();
    let until = std::time::Instant::now() + Duration::from_secs(lifetime);
    // An upgrade only authenticates and queries retained requests. Preserve the
    // original target and unresolved gate instead of simulating local approval.
    let mut ready = upgrade_probe;
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
            if let Some(control) = replacement_control.as_ref() {
                let devices = receiver.snapshot().await?.device_ids;
                let old = devices.first().ok_or("missing initial pair")?;
                service.open_pairing_replacing(old.clone()).await?;
                let window = service
                    .snapshot()?
                    .window
                    .ok_or("missing replacement invitation")?;
                let listener = control;
                if let Ok(Ok((mut client, _))) =
                    tokio::time::timeout(Duration::from_secs(60), listener.accept()).await
                {
                    use tokio::io::AsyncWriteExt as _;
                    client.write_all(window.code.as_bytes()).await?;
                }
                if let Some(clock) = &clock {
                    use tokio::io::AsyncReadExt as _;
                    let (mut client, _) =
                        tokio::time::timeout(Duration::from_secs(30), listener.accept()).await??;
                    let command =
                        tokio::time::timeout(Duration::from_secs(5), client.read_u8()).await??;
                    if command != 1 {
                        return Err("Unexpected expiry clock command".into());
                    }
                    clock.advance(hold_retention);
                }
            }
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
    if let Some(task) = batch_task {
        if task.is_finished() {
            task.await?.map_err(|error| error.to_string())?;
            let page = receiver.history(Default::default()).await?;
            let [entry] = page.entries.as_slice() else {
                return Err("Interrupted batch must have exactly one desktop history entry".into());
            };
            if entry.receipt.st != voicelink_receiver::protocol::State::Injected {
                return Err("Interrupted batch did not persist its first input outcome".into());
            }
        } else {
            task.abort();
            let _ = task.await;
        }
    }
    service.shutdown().await?;
    receiver.shutdown().await?;
    if let Some(events) = text_commits {
        let events: Vec<_> = events.try_iter().collect();
        std::fs::write(
            output.join("text-commits.json"),
            serde_json::to_vec(&events)?,
        )?;
    }
    Ok(())
}
