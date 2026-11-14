//! Explicit opt-in isolated LAN/loopback fixture. Fake input only, independent
//! identity/ledger and random ports. Never started by the production application.
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};
use uuid::Uuid;
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    windows_input::open_ledger,
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::{
    HostIdentity, interfaces::available_interfaces, server::NetworkService,
    storage::secure_directory,
};

struct Fake(Arc<AtomicUsize>);
impl InputAdapter for Fake {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
        if matches!(job.operation(), voicelink_receiver::protocol::Operation::Text { text, .. } if text == "fixture:hold")
        {
            Err("receiver_focused")
        } else {
            Ok(())
        }
    }
    fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
        self.0.fetch_add(1, Ordering::SeqCst);
        InputOutcome::Injected {
            method: "discovery_fixture_fake",
        }
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = std::env::args().collect::<Vec<_>>();
    let output = PathBuf::from(args.get(1).ok_or("isolated output directory required")?);
    let lan = args.get(2).is_some_and(|s| s == "lan");
    let revoke = args.get(3).is_some_and(|s| s == "revoked");
    secure_directory(&output)?;
    let directory = output.join("host");
    secure_directory(&directory)?;
    let calls = Arc::new(AtomicUsize::new(0));
    let receiver = Receiver::start(open_ledger(&directory)?, Fake(calls.clone()))?;
    let identity = HostIdentity::open(&directory, 0)?;
    let binding = if lan {
        Some(
            available_interfaces()?
                .into_iter()
                .find(|i| i.is_physical() && i.binding().is_ok())
                .ok_or("no physical LAN")?
                .binding()?,
        )
    } else {
        None
    };
    let start = |port| match binding.as_ref() {
        Some(binding) => NetworkService::start_on_network(
            receiver.clone(),
            identity.clone(),
            binding.clone(),
            port,
        ),
        None => NetworkService::start_loopback(
            receiver.clone(),
            identity.clone(),
            format!("127.0.0.1:{port}").parse().unwrap(),
        ),
    };
    // Reserve the new random port with a separate loopback listener; no fixed
    // port or production identity is touched. Drop immediately before migration.
    let reserved = std::net::TcpListener::bind("127.0.0.1:0")?;
    let new_port = reserved.local_addr()?.port();
    let mut reserved = Some(reserved);
    let mut service = start(0)?;
    service.open_pairing().await?;
    let initial = service.snapshot()?;
    let decoy_directory = output.join("decoy");
    secure_directory(&decoy_directory)?;
    let decoy_receiver = Receiver::start(
        open_ledger(&decoy_directory)?,
        Fake(Arc::new(AtomicUsize::new(0))),
    )?;
    let decoy = NetworkService::start_loopback(
        decoy_receiver.clone(),
        HostIdentity::open(&decoy_directory, 0)?,
        "127.0.0.1:0".parse()?,
    )?;
    std::fs::write(
        output.join("fixture.json"),
        serde_json::to_vec(&serde_json::json!({
            "address":initial.address.ip().to_string(), "port":initial.address.port(),
            "code":initial.window.ok_or("no invitation")?.code.to_string(),
            "new_address":if lan { initial.address.ip().to_string() } else { "127.0.0.1".into() },
            "new_port":new_port, "wrong_port":decoy.snapshot()?.address.port()
        }))?,
    )?;
    let mut ready = false;
    let mut migrated = false;
    let mut blocked_at = None;
    let until = Instant::now() + Duration::from_secs(180);
    while Instant::now() < until && !output.join("stop").exists() {
        if let Some(approval) = service.snapshot()?.approval {
            service.decide(&approval.id, true).await?;
        }
        let snapshot = receiver.snapshot().await?;
        if !ready && snapshot.paired_devices > 0 {
            let state = receiver.bind_target(Uuid::new_v4().to_string()).await?;
            receiver.resume(state.state_rev, false).await?;
            ready = true;
        }
        if ready && !migrated && snapshot.blocked.is_some() {
            let since = blocked_at.get_or_insert_with(Instant::now);
            if since.elapsed() > Duration::from_secs(2) {
                if revoke {
                    for id in snapshot.device_names.keys() {
                        receiver.revoke(id.clone()).await?;
                    }
                }
                service.shutdown().await?;
                drop(reserved.take());
                service = start(new_port)?;
                migrated = true;
            }
        }
        std::fs::write(
            output.join("result.json"),
            serde_json::to_vec(&serde_json::json!({
                "migrated":migrated,"fake_input_calls":calls.load(Ordering::SeqCst),
                "port":service.snapshot()?.address.port(), "paired_devices":snapshot.paired_devices,
                "paused":snapshot.control.paused,"connections":service.snapshot()?.connections, "discovery":format!("{:?}",service.snapshot()?.discovery)
            }))?,
        )?;
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    service.shutdown().await?;
    decoy.shutdown().await?;
    receiver.shutdown().await?;
    decoy_receiver.shutdown().await?;
    Ok(())
}
