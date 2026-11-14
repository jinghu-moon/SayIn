use super::*;
use crate::interfaces::{NetworkCategory, NetworkInterface, NetworkProfile};
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    windows_input::open_ledger,
    worker::InputAdapter,
};

struct NoInput;
impl InputAdapter for NoInput {
    fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
        Err("TEST_ONLY")
    }
    fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
        unreachable!()
    }
}

#[tokio::test]
async fn profile_monitor_pauses_running_receiver_and_signals_shutdown_on_change_or_query_error() {
    for query_error in [false, true] {
        let directory = std::env::temp_dir().join(format!("voicelink-monitor-{}", Uuid::new_v4()));
        crate::storage::secure_directory(&directory).unwrap();
        let receiver = Receiver::start(open_ledger(&directory).unwrap(), NoInput).unwrap();
        let identity = HostIdentity::open(&directory, 0).unwrap();
        let device = Uuid::new_v4().to_string();
        receiver
            .provision_device(device.clone(), Zeroizing::new(vec![3; 32]), String::new())
            .await
            .unwrap();
        let _session = receiver
            .authenticate(device, Zeroizing::new(vec![3; 32]))
            .await
            .unwrap();
        let state = receiver
            .bind_target(Uuid::new_v4().to_string())
            .await
            .unwrap();
        receiver.resume(state.state_rev, false).await.unwrap();
        assert!(!receiver.snapshot().await.unwrap().control.paused);
        let interface = NetworkInterface::new(
            Uuid::new_v4(),
            "192.0.2.1".parse().unwrap(),
            "test".into(),
            vec![NetworkProfile {
                id: Uuid::new_v4(),
                category: NetworkCategory::Public,
            }],
        )
        .with_physical(true);
        let binding = interface.binding().unwrap();
        let address = SocketAddr::new(interface.address.into(), 7788);
        let shared = Arc::new(Shared {
            receiver: receiver.clone(),
            identity,
            address,
            pairing: AsyncMutex::new(None),
            snapshot: Mutex::new(NetworkSnapshot {
                address,
                window: None,
                approval: None,
                connections: 0,
                running: true,
                discovery: PublicationStatus::Disabled,
            }),
            observers: Mutex::new(Vec::new()),
            failures: Mutex::new(VecDeque::new()),
        });
        let changed = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let source = changed.clone();
        let (stop, stopping) = watch::channel(false);
        let observed = stop.subscribe();
        let monitor = tokio::spawn(monitor_profile(
            shared,
            Some(binding),
            stop,
            stopping,
            move || {
                if !source.load(std::sync::atomic::Ordering::SeqCst) {
                    Ok(vec![interface.clone()])
                } else if query_error {
                    Err(Error::Protocol("NETWORK_PROFILE_UNAVAILABLE"))
                } else {
                    // Same adapter and IP, but a different connected Windows network.
                    let mut next = interface.clone();
                    next.profiles[0].id = Uuid::nil();
                    Ok(vec![next])
                }
            },
        ));
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(!*observed.borrow());
        assert!(!receiver.snapshot().await.unwrap().control.paused);
        changed.store(true, std::sync::atomic::Ordering::SeqCst);
        timeout(Duration::from_secs(4), monitor)
            .await
            .unwrap()
            .unwrap();
        assert!(*observed.borrow());
        assert!(receiver.snapshot().await.unwrap().control.paused);
        assert_eq!(
            receiver.state().await.unwrap().pause_reason.as_deref(),
            Some("network_unavailable")
        );
        receiver.shutdown().await.unwrap();
        std::fs::remove_dir_all(directory).unwrap();
    }
}
