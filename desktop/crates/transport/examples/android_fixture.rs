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
    windows_input::open_ledger,
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::{HostIdentity, server::NetworkService, storage::secure_directory};

struct FakeInput(Arc<AtomicUsize>);
impl InputAdapter for FakeInput {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
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
        self.0.fetch_add(1, Ordering::SeqCst);
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
    if std::env::args().any(|arg| arg == "--images") {
        let images = output.join("images");
        secure_directory(&images)?;
        ledger.attach_images(voicelink_receiver::images::storage::ImageStore::open(
            &images,
            identity.host_id(),
        )?)?;
    }
    let receiver = Receiver::start(ledger, FakeInput(calls.clone()))?;
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
    println!("Isolated Android test fixture ready (fake input).");
    let target = Uuid::new_v4().to_string();
    let until = std::time::Instant::now() + Duration::from_secs(180);
    let mut ready = false;
    while std::time::Instant::now() < until {
        if let Some(approval) = service.snapshot()?.approval {
            service.decide(&approval.id, true).await?;
        }
        if !ready && receiver.snapshot().await?.paired_devices > 0 {
            let state = receiver.bind_target(target.clone()).await?;
            receiver.resume(state.state_rev, false).await?;
            ready = true;
        }
        std::fs::write(
            output.join("calls.json"),
            serde_json::to_vec(
                &serde_json::json!({"fake_input_calls":calls.load(Ordering::SeqCst)}),
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
