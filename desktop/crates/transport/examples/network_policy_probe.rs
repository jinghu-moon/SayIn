//! Local policy diagnostic: closed pairing window, refusing input, ephemeral storage/ports.
//! Does not change network profiles or firewall rules, and never approves a phone.
use std::time::{Duration, Instant};
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

struct RefuseInput;
impl InputAdapter for RefuseInput {
    fn preflight(&mut self, _: &Execution) -> Result<(), &'static str> {
        Err("DIAGNOSTIC_ONLY")
    }
    fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
        unreachable!("diagnostic never injects input")
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let interfaces = available_interfaces()?;
    let mut observations = Vec::new();
    for interface in interfaces {
        let started = Instant::now();
        let default_permitted = interface.authorize(false).is_ok();
        assert_eq!(default_permitted, !interface.is_public());
        let authorization = interface.authorize(true)?;
        authorization.validate()?;
        let directory =
            std::env::temp_dir().join(format!("voicelink-policy-probe-{}", Uuid::new_v4()));
        secure_directory(&directory)?;
        let receiver = Receiver::start(open_ledger(&directory)?, RefuseInput)?;
        let identity = HostIdentity::open(&directory, 0)?;
        let service =
            NetworkService::start_on_network(receiver.clone(), identity, authorization, 0)?;
        // Cross a periodic monitor tick as well as the immediate startup check.
        tokio::time::sleep(Duration::from_millis(2300)).await;
        let snapshot = service.snapshot()?;
        assert!(snapshot.running);
        assert_eq!(
            snapshot.address.ip(),
            std::net::IpAddr::V4(interface.address)
        );
        assert!(snapshot.window.is_none() && snapshot.approval.is_none());
        assert_eq!(snapshot.connections, 0);
        service.shutdown().await?;
        receiver.shutdown().await?;
        drop(service);
        // DPAPI secrets are temporary and no pairing credentials were issued.
        std::fs::remove_dir_all(directory)?;
        observations.push(serde_json::json!({
            "interface":interface.name,"address":interface.address.to_string(),"public":interface.is_public(),
            "default_permitted":default_permitted,"authorized_bind":true,"monitor_retained":true,
            "closed_pairing_window":true,"clean_shutdown":true,"elapsed_ms":started.elapsed().as_millis()
        }));
    }
    // One standalone JSON result makes the probe suitable for saved evidence.
    println!("{}", serde_json::to_string_pretty(&observations)?);
    Ok(())
}
