use futures_util::{SinkExt, StreamExt};
use rustls::{
    ClientConfig, RootCertStore,
    pki_types::{CertificateDer, ServerName},
};
use serde_json::{Value, json};
use std::{
    fs,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::Duration,
};
use tokio::{net::TcpStream, time::timeout};
use tokio_rustls::{TlsConnector, client::TlsStream};
use tokio_tungstenite::{WebSocketStream, client_async, tungstenite::Message};
use uuid::Uuid;
use voicelink_pairing::{Invitation, decode};
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    windows_input::open_ledger,
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::{HostIdentity, server::NetworkService, storage::secure_directory};

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let directory =
            Self(std::env::temp_dir().join(format!("voicelink-network-{}", Uuid::new_v4())));
        secure_directory(&directory.0).unwrap();
        directory
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
struct Adapter(Arc<AtomicUsize>);
impl InputAdapter for Adapter {
    fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
        Ok(())
    }
    fn execute(&mut self, _: &Execution, gate: &InputGate) -> InputOutcome {
        assert!(!gate.is_paused());
        self.0.fetch_add(1, Ordering::SeqCst);
        InputOutcome::Injected { method: "test" }
    }
}
type Socket = WebSocketStream<TlsStream<TcpStream>>;

struct RecoveringAdapter {
    ready: Arc<AtomicBool>,
    calls: Arc<AtomicUsize>,
}
impl InputAdapter for RecoveringAdapter {
    fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
        if self.ready.load(Ordering::SeqCst) {
            Ok(())
        } else {
            Err("receiver_focused")
        }
    }
    fn execute(&mut self, _: &Execution, gate: &InputGate) -> InputOutcome {
        assert!(!gate.is_paused());
        self.calls.fetch_add(1, Ordering::SeqCst);
        InputOutcome::Injected { method: "test" }
    }
}

#[tokio::test]
async fn held_and_expiry_are_pushed_without_phone_queries_then_fresh_text_executes() {
    let directory = Directory::new();
    let ready = Arc::new(AtomicBool::new(false));
    let calls = Arc::new(AtomicUsize::new(0));
    let receiver = Receiver::start(
        open_ledger(&directory.0).unwrap(),
        RecoveringAdapter {
            ready: ready.clone(),
            calls: calls.clone(),
        },
    )
    .unwrap();
    let service = NetworkService::start_loopback(
        receiver.clone(),
        HostIdentity::open(&directory.0, 0).unwrap(),
        "127.0.0.1:0".parse().unwrap(),
    )
    .unwrap();
    let invitation = invitation(&service).await;
    let paired = pair(&service, &invitation).await;
    let state = receiver.state().await.unwrap();
    let state = receiver
        .enable_foreground_input(state.state_rev, false)
        .await
        .unwrap();
    let target = state.target.unwrap();
    let mut socket = socket(&invitation).await;
    send(
        &mut socket,
        json!({"v":2,"t":"hello","name":"Test Android","device_id":paired["device_id"],"token":paired["token"]}),
    )
    .await;
    let welcome = next(&mut socket, "welcome").await;
    let session = welcome["session_id"].as_str().unwrap();
    let first = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":2,"t":"submit","session_id":session,"id":first,"target":target,
        "op":{"kind":"text","text":"held text must never be typed","src":"manual"}}),
    )
    .await;
    loop {
        let status = next(&mut socket, "status").await;
        assert_eq!(status["id"], first);
        if status["st"] == "held" {
            assert_eq!(status["reason"], "receiver_focused");
            break;
        }
        assert_eq!(status["st"], "accepted");
    }
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    tokio::time::sleep(Duration::from_secs(1)).await;
    ready.store(true, Ordering::SeqCst);
    let second = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":2,"t":"submit","session_id":session,"id":second,"target":target,
        "op":{"kind":"text","text":"fresh text","src":"manual"}}),
    )
    .await;
    let accepted = next(&mut socket, "status").await;
    assert_eq!(accepted["id"], second);
    assert_eq!(accepted["st"], "accepted");
    // Only consume server pushes. No query, UI refresh or control may drive TTL.
    timeout(Duration::from_secs(35), async {
        let (mut expired, mut injected) = (false, false);
        while !expired || !injected {
            if let Message::Text(value) = socket.next().await.unwrap().unwrap() {
                let status: Value = serde_json::from_str(&value).unwrap();
                if status["t"] != "status" {
                    continue;
                }
                if status["id"] == first {
                    assert_eq!(status["st"], "expired");
                    assert_eq!(status["reason"], "receiver_focused");
                    expired = true;
                } else if status["id"] == second {
                    assert!(status["st"] == "executing" || status["st"] == "injected");
                    injected |= status["st"] == "injected";
                } else {
                    panic!("unexpected receipt");
                }
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

async fn socket(invitation: &Invitation) -> Socket {
    let mut roots = RootCertStore::empty();
    roots
        .add(CertificateDer::from(decode(&invitation.cert).unwrap()))
        .unwrap();
    let config =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_protocol_versions(&[&rustls::version::TLS13])
            .unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
    let tcp = TcpStream::connect((invitation.address, invitation.port))
        .await
        .unwrap();
    let tls = TlsConnector::from(Arc::new(config))
        .connect(
            ServerName::try_from(invitation.tls_name.clone()).unwrap(),
            tcp,
        )
        .await
        .unwrap();
    assert_eq!(
        tls.get_ref().1.protocol_version(),
        Some(rustls::ProtocolVersion::TLSv1_3)
    );
    client_async(
        format!("wss://{}:{}/v2", invitation.tls_name, invitation.port),
        tls,
    )
    .await
    .unwrap()
    .0
}
async fn send(socket: &mut Socket, value: Value) {
    socket
        .send(Message::Text(value.to_string().into()))
        .await
        .unwrap();
}
async fn next(socket: &mut Socket, kind: &str) -> Value {
    timeout(Duration::from_secs(5), async {
        loop {
            if let Message::Text(value) = socket.next().await.unwrap().unwrap() {
                let value: Value = serde_json::from_str(&value).unwrap();
                if value["t"] == kind {
                    return value;
                }
            }
        }
    })
    .await
    .unwrap()
}
async fn invitation(service: &NetworkService) -> Invitation {
    service.open_pairing().await.unwrap();
    let snapshot = service.snapshot().unwrap();
    let code = snapshot.window.unwrap().code.to_string();
    tokio::task::spawn_blocking(move || {
        voicelink_pairing::pair_code(snapshot.address.ip(), snapshot.address.port(), &code)
    })
    .await
    .unwrap()
    .unwrap()
}
async fn pair(service: &NetworkService, invitation: &Invitation) -> Value {
    let mut socket = socket(invitation).await;
    send(&mut socket,json!({"v":2,"t":"pair","name":"Test Android","window_id":invitation.window_id,"secret":invitation.secret})).await;
    let approval = timeout(Duration::from_secs(5), async {
        loop {
            if let Some(approval) = service.snapshot().unwrap().approval {
                break approval;
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .unwrap();
    assert_eq!(approval.name, "Test Android");
    service.decide(&approval.id, true).await.unwrap();
    next(&mut socket, "paired").await
}

#[tokio::test]
async fn loopback_entry_rejects_lan_and_service_revalidates_scoped_binding() {
    use voicelink_transport::interfaces::{NetworkCategory, NetworkInterface, NetworkProfile};
    let directory = Directory::new();
    let receiver = Receiver::start(
        open_ledger(&directory.0).unwrap(),
        Adapter(Arc::new(AtomicUsize::new(0))),
    )
    .unwrap();
    let identity = HostIdentity::open(&directory.0, 0).unwrap();
    for address in ["0.0.0.0:0", "192.0.2.1:0", "[::]:0"] {
        assert!(
            NetworkService::start_loopback(
                receiver.clone(),
                identity.clone(),
                address.parse().unwrap()
            )
            .is_err()
        );
    }
    let interface = NetworkInterface::new(
        Uuid::new_v4(),
        "192.0.2.1".parse().unwrap(),
        "stale".into(),
        vec![NetworkProfile {
            id: Uuid::new_v4(),
            category: NetworkCategory::Public,
        }],
    )
    .with_physical(true);
    assert!(interface.binding().is_ok());
    assert!(
        NetworkService::start_on_network(
            receiver.clone(),
            identity,
            interface.binding().unwrap(),
            0
        )
        .is_err()
    );
    assert!(receiver.snapshot().await.unwrap().control.paused);
    receiver.shutdown().await.unwrap();
}

#[tokio::test]
async fn code_and_qr_bootstrap_reach_pinned_tls_local_approval_and_durable_submit() {
    let directory = Directory::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let receiver =
        Receiver::start(open_ledger(&directory.0).unwrap(), Adapter(calls.clone())).unwrap();
    let identity = HostIdentity::open(&directory.0, 0).unwrap();
    let service =
        NetworkService::start_loopback(receiver.clone(), identity, "127.0.0.1:0".parse().unwrap())
            .unwrap();
    let invitation = invitation(&service).await;
    let invitation = Invitation::from_qr(&invitation.qr().unwrap()).unwrap();
    let paired = pair(&service, &invitation).await;
    assert!(service.snapshot().unwrap().window.is_none());
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 1);
    assert_eq!(
        receiver
            .snapshot()
            .await
            .unwrap()
            .device_names
            .get(paired["device_id"].as_str().unwrap())
            .map(String::as_str),
        Some("Test Android")
    );
    let target = Uuid::new_v4().to_string();
    let state = receiver.bind_target(target.clone()).await.unwrap();
    receiver.resume(state.state_rev, false).await.unwrap();
    let mut socket = socket(&invitation).await;
    send(
        &mut socket,
        json!({"v":2,"t":"hello","name":"Test Android","device_id":paired["device_id"],"token":paired["token"]}),
    )
    .await;
    let welcome = next(&mut socket, "welcome").await;
    let session = welcome["session_id"].as_str().unwrap();
    assert_eq!(welcome["target"], target);
    let id = Uuid::new_v4().to_string();
    let operation = json!({"v":2,"t":"submit","session_id":session,"id":id,"target":target,"op":{"kind":"text","text":"中文 😀 {ENTER}","src":"manual"}});
    send(&mut socket, operation.clone()).await;
    loop {
        let status = next(&mut socket, "status").await;
        if status["st"] == "injected" {
            break;
        }
    }
    send(&mut socket, operation).await;
    assert_eq!(next(&mut socket, "status").await["st"], "injected");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let history = receiver
        .history(voicelink_receiver::history::HistoryQuery::default())
        .await
        .unwrap();
    assert_eq!(history.entries.len(), 1);
    assert_eq!(history.entries[0].source_name, "Test Android");
    let original_session = session.to_owned();
    let paused = receiver.pause().await.unwrap();
    let mut reconnect = crate::socket(&invitation).await;
    send(&mut reconnect, json!({"v":2,"t":"hello","name":"Redmi K40","device_id":paired["device_id"],"token":paired["token"]})).await;
    let renamed = next(&mut reconnect, "welcome").await;
    assert_ne!(renamed["session_id"], original_session);
    assert_eq!(renamed["paused"], true);
    assert_eq!(receiver.state().await.unwrap().state_rev, paused.state_rev);
    assert_eq!(
        receiver.snapshot().await.unwrap().device_names[paired["device_id"].as_str().unwrap()],
        "Redmi K40"
    );
    assert_eq!(
        receiver
            .history(voicelink_receiver::history::HistoryQuery::default())
            .await
            .unwrap()
            .entries[0]
            .source_name,
        "Test Android"
    );
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let mut socket = reconnect;
    let renamed_session = renamed["session_id"].as_str().unwrap();
    let pause_id = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":2,"t":"control","session_id":renamed_session,"id":pause_id,"name":"pause"}),
    )
    .await;
    assert_eq!(next(&mut socket, "controlled").await["paused"], true);
    assert!(receiver.state().await.unwrap().paused);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
    assert!(HostIdentity::open(&directory.0, 1).is_ok());
}

#[tokio::test]
async fn wrong_code_never_delivers_identity_and_closed_window_rejects_pending_grants() {
    let directory = Directory::new();
    let receiver = Receiver::start(
        open_ledger(&directory.0).unwrap(),
        Adapter(Arc::new(AtomicUsize::new(0))),
    )
    .unwrap();
    let service = NetworkService::start_loopback(
        receiver.clone(),
        HostIdentity::open(&directory.0, 0).unwrap(),
        "127.0.0.1:0".parse().unwrap(),
    )
    .unwrap();
    service.open_pairing().await.unwrap();
    let snapshot = service.snapshot().unwrap();
    let mut wrong = snapshot.window.unwrap().code.to_string();
    wrong.replace_range(..1, if wrong.starts_with('0') { "1" } else { "0" });
    assert!(
        tokio::task::spawn_blocking(move || voicelink_pairing::pair_code(
            snapshot.address.ip(),
            snapshot.address.port(),
            &wrong
        ))
        .await
        .unwrap()
        .is_err()
    );
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 0);
    let invitation = invitation(&service).await;
    service.close_pairing().await;
    let mut socket = socket(&invitation).await;
    send(&mut socket,json!({"v":2,"t":"pair","name":"Rejected","window_id":invitation.window_id,"secret":invitation.secret})).await;
    assert!(timeout(Duration::from_secs(2), socket.next()).await.is_ok());
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 0);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

#[tokio::test]
async fn explicit_rejection_and_revocation_prevent_authenticated_input() {
    let directory = Directory::new();
    let receiver = Receiver::start(
        open_ledger(&directory.0).unwrap(),
        Adapter(Arc::new(AtomicUsize::new(0))),
    )
    .unwrap();
    let service = NetworkService::start_loopback(
        receiver.clone(),
        HostIdentity::open(&directory.0, 0).unwrap(),
        "127.0.0.1:0".parse().unwrap(),
    )
    .unwrap();
    let invitation = invitation(&service).await;
    let paired = pair(&service, &invitation).await;
    receiver
        .revoke(paired["device_id"].as_str().unwrap().into())
        .await
        .unwrap();
    let mut socket = socket(&invitation).await;
    send(
        &mut socket,
        json!({"v":2,"t":"hello","name":"Test Android","device_id":paired["device_id"],"token":paired["token"]}),
    )
    .await;
    assert!(matches!(
        timeout(Duration::from_secs(2), socket.next())
            .await
            .unwrap(),
        None | Some(Err(_))
    ));
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}
