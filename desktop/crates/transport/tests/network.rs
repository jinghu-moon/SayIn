#[path = "../../../tests/support/manual_clock.rs"]
mod manual_clock;

use futures_util::{SinkExt, StreamExt};
use manual_clock::ManualClock;
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
    windows_input::{open_ledger, open_ledger_with_clock},
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
    let clock = ManualClock::new();
    let ledger = open_ledger_with_clock(&directory.0, clock.clone()).unwrap();
    let receiver = Receiver::start(
        ledger,
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
        json!({"v":3,"t":"hello","name":"Test Android","pair_id":paired["pair_id"],"token":paired["token"]}),
    )
    .await;
    let welcome = next(&mut socket, "welcome").await;
    let session = welcome["session_id"].as_str().unwrap();
    query_ready(&mut socket, session).await;
    let first = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":3,"t":"submit","session_id":session,"id":first,"target":target,
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
    clock.advance(Duration::from_secs(600));
    // Only consume server pushes. No query, UI refresh or control may drive TTL.
    let expired = next(&mut socket, "status").await;
    assert_eq!(expired["id"], first);
    assert_eq!(expired["st"], "expired");
    assert_eq!(expired["reason"], "hold_expired");
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    ready.store(true, Ordering::SeqCst);
    let second = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":3,"t":"submit","session_id":session,"id":second,"target":target,
        "op":{"kind":"text","text":"fresh text","src":"manual"}}),
    )
    .await;
    let accepted = next(&mut socket, "status").await;
    assert_eq!(accepted["id"], second);
    assert_eq!(accepted["st"], "accepted");
    loop {
        let status = next(&mut socket, "status").await;
        assert_eq!(status["id"], second);
        if status["st"] == "injected" {
            break;
        }
        assert_eq!(status["st"], "executing");
    }
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

async fn socket(invitation: &Invitation) -> Socket {
    socket_path(invitation, "/sayin/v3").await
}
async fn socket_path(invitation: &Invitation, path: &str) -> Socket {
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
        format!("wss://{}:{}{}", invitation.tls_name, invitation.port, path),
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
    .unwrap_or_else(|_| panic!("timed out waiting for {kind}"))
}
async fn query_ready(socket: &mut Socket, session: &str) {
    let reference = Uuid::new_v4().to_string();
    send(
        socket,
        json!({"v":3,"t":"query","session_id":session,"ref":reference,"ids":[]}),
    )
    .await;
    let value = timeout(Duration::from_secs(5), async {
        loop {
            if let Message::Text(value) = socket.next().await.unwrap().unwrap() {
                let value: Value = serde_json::from_str(&value).unwrap();
                if value["t"] == "queried" || value["t"] == "err" {
                    return value;
                }
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(value["t"], "queried", "query failed: {value}");
    assert_eq!(value["ref"], reference);
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
    send(&mut socket,json!({"v":3,"t":"pair","name":"Test Android","window_id":invitation.window_id,"secret":invitation.secret})).await;
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
            .get(paired["pair_id"].as_str().unwrap())
            .map(String::as_str),
        Some("Test Android")
    );
    let target = Uuid::new_v4().to_string();
    let state = receiver.bind_target(target.clone()).await.unwrap();
    receiver.resume(state.state_rev, false).await.unwrap();
    let mut socket = socket(&invitation).await;
    send(
        &mut socket,
        json!({"v":3,"t":"hello","name":"Test Android","pair_id":paired["pair_id"],"token":paired["token"]}),
    )
    .await;
    let welcome = next(&mut socket, "welcome").await;
    let session = welcome["session_id"].as_str().unwrap();
    query_ready(&mut socket, session).await;
    assert_eq!(welcome["target"], target);
    let missing = Uuid::new_v4().to_string();
    let missing_ref = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":3,"t":"query","session_id":session,"ref":missing_ref,"ids":[missing]}),
    )
    .await;
    let unknown = next(&mut socket, "status").await;
    assert_eq!(unknown["st"], "unknown");
    assert_eq!(unknown["id"], missing);
    assert!(unknown.get("accept_order").is_none());
    assert!(unknown.get("request_rev").is_none());
    assert!(unknown.get("execution").is_none());
    assert_eq!(next(&mut socket, "queried").await["ref"], missing_ref);
    let oversized_ref = Uuid::new_v4().to_string();
    let oversized_ids = (0..65)
        .map(|_| Uuid::new_v4().to_string())
        .collect::<Vec<_>>();
    send(
        &mut socket,
        json!({"v":3,"t":"query","session_id":session,"ref":oversized_ref,"ids":oversized_ids}),
    )
    .await;
    let error = next(&mut socket, "err").await;
    assert_eq!(error["code"], "INVALID_REQUEST");
    assert_eq!(error["ref"], oversized_ref);
    let id = Uuid::new_v4().to_string();
    let operation = json!({"v":3,"t":"submit","session_id":session,"id":id,"target":target,"op":{"kind":"text","text":"中文 😀 {ENTER}","src":"manual"}});
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
    send(&mut reconnect, json!({"v":3,"t":"hello","name":"Redmi K40","pair_id":paired["pair_id"],"token":paired["token"]})).await;
    let renamed = next(&mut reconnect, "welcome").await;
    query_ready(&mut reconnect, renamed["session_id"].as_str().unwrap()).await;
    assert_ne!(renamed["session_id"], original_session);
    assert_eq!(renamed["paused"], true);
    assert_eq!(receiver.state().await.unwrap().state_rev, paused.state_rev);
    assert_eq!(
        receiver.snapshot().await.unwrap().device_names[paired["pair_id"].as_str().unwrap()],
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
    let pause_ref = Uuid::new_v4().to_string();
    send(
        &mut socket,
        json!({"v":3,"t":"control","session_id":renamed_session,"ref":pause_ref,"refs":[pause_id],"name":"pause"}),
    )
    .await;
    let error = next(&mut socket, "err").await;
    assert_eq!(error["code"], "UNSUPPORTED");
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
    send(&mut socket,json!({"v":3,"t":"pair","name":"Rejected","window_id":invitation.window_id,"secret":invitation.secret})).await;
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
        .revoke(paired["pair_id"].as_str().unwrap().into())
        .await
        .unwrap();
    let mut socket = socket(&invitation).await;
    send(
        &mut socket,
        json!({"v":3,"t":"hello","name":"Test Android","pair_id":paired["pair_id"],"token":paired["token"]}),
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

#[tokio::test]
async fn same_identity_moves_ipv4_and_port_then_queries_without_resuming_or_replaying() {
    let directory = Directory::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let receiver =
        Receiver::start(open_ledger(&directory.0).unwrap(), Adapter(calls.clone())).unwrap();
    let identity = HostIdentity::open(&directory.0, 0).unwrap();
    let old = NetworkService::start_loopback(
        receiver.clone(),
        identity.clone(),
        "127.0.0.1:0".parse().unwrap(),
    )
    .unwrap();
    let invitation = invitation(&old).await;
    let paired = pair(&old, &invitation).await;
    let state = receiver
        .bind_target(Uuid::new_v4().to_string())
        .await
        .unwrap();
    receiver.resume(state.state_rev, false).await.unwrap();
    let mut original = socket(&invitation).await;
    let hello = json!({"v":3,"t":"hello","name":"Test Android","pair_id":paired["pair_id"],"token":paired["token"]});
    send(&mut original, hello.clone()).await;
    let welcome = next(&mut original, "welcome").await;
    query_ready(&mut original, welcome["session_id"].as_str().unwrap()).await;
    let id = Uuid::new_v4().to_string();
    send(&mut original, json!({"v":3,"t":"submit","session_id":welcome["session_id"],"id":id,"target":welcome["target"],"op":{"kind":"text","text":"once across migration","src":"manual"}})).await;
    while next(&mut original, "status").await["st"] != "injected" {}
    receiver.pause().await.unwrap();
    old.shutdown().await.unwrap();
    // Hold the old port so the new random endpoint must differ in both fields.
    let _reserved = std::net::TcpListener::bind(("127.0.0.2", invitation.port)).unwrap();
    let moved =
        NetworkService::start_loopback(receiver.clone(), identity, "127.0.0.2:0".parse().unwrap())
            .unwrap();
    let route = moved.snapshot().unwrap().address;
    assert_ne!(route.ip(), invitation.address);
    assert_ne!(route.port(), invitation.port);
    assert_eq!(
        moved.snapshot().unwrap().discovery,
        voicelink_transport::server::PublicationStatus::Disabled
    );
    let mut endpoint = invitation.clone();
    endpoint.address = route.ip();
    endpoint.port = route.port();
    // Certificate, tls_name, host/device identity and token remain exactly old.
    let mut connection = socket(&endpoint).await;
    send(&mut connection, hello.clone()).await;
    let restored = next(&mut connection, "welcome").await;
    query_ready(&mut connection, restored["session_id"].as_str().unwrap()).await;
    assert_eq!(restored["host_id"], invitation.host_id);
    assert_eq!(restored["pair_id"], paired["pair_id"]);
    assert_eq!(restored["paused"], true);
    assert_ne!(restored["session_id"], welcome["session_id"]);
    let query = Uuid::new_v4().to_string();
    send(
        &mut connection,
        json!({"v":3,"t":"query","session_id":restored["session_id"],"ref":query,"ids":[id]}),
    )
    .await;
    assert_eq!(next(&mut connection, "status").await["st"], "injected");
    assert_eq!(next(&mut connection, "queried").await["ref"], query);
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    receiver
        .revoke(paired["pair_id"].as_str().unwrap().into())
        .await
        .unwrap();
    let mut revoked = socket(&endpoint).await;
    send(&mut revoked, hello).await;
    assert!(matches!(
        timeout(Duration::from_secs(2), revoked.next())
            .await
            .unwrap(),
        None | Some(Err(_))
    ));
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    moved.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

fn test_png() -> Vec<u8> {
    vec![
        137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6,
        0, 0, 0, 31, 21, 196, 137, 0, 0, 0, 13, 73, 68, 65, 84, 120, 156, 99, 224, 119, 252, 223,
        0, 0, 3, 130, 1, 208, 59, 64, 140, 230, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130,
    ]
}
async fn image_service(
    directory: &Directory,
    calls: Arc<AtomicUsize>,
) -> (Receiver, NetworkService) {
    let mut ledger = open_ledger(&directory.0).unwrap();
    let identity = HostIdentity::open(&directory.0, 0).unwrap();
    let images = directory.0.join("images");
    secure_directory(&images).unwrap();
    ledger
        .attach_images(
            voicelink_receiver::images::storage::ImageStore::open(&images, identity.host_id())
                .unwrap(),
        )
        .unwrap();
    let receiver = Receiver::start(ledger, Adapter(calls)).unwrap();
    let service =
        NetworkService::start_loopback(receiver.clone(), identity, "127.0.0.1:0".parse().unwrap())
            .unwrap();
    (receiver, service)
}
async fn image_hello(images: &mut Socket, welcome: &Value, paired: &Value) {
    send(images,json!({"v":3,"t":"image_hello","session_id":welcome["session_id"],"pair_id":paired["pair_id"],"binding":welcome["image"]["binding"]})).await;
}
async fn image_begin(images: &mut Socket, welcome: &Value, id: &str, bytes: &[u8]) {
    send(images,json!({"v":3,"t":"image_begin","session_id":welcome["session_id"],"id":id,"target":welcome["target"],"state_rev":welcome["state_rev"],"metadata":{"bytes":bytes.len(),"width":1,"height":1,"mime":"image/png","sha256":voicelink_receiver::images::digest(bytes)}})).await;
    assert_eq!(next(images, "image_begun").await["id"], id);
}
async fn connection_closed(socket: &mut Socket) {
    timeout(Duration::from_secs(5), async {
        loop {
            match socket.next().await {
                None | Some(Err(_)) | Some(Ok(Message::Close(_))) => return,
                Some(Ok(Message::Text(value))) => panic!("unexpected message {value}"),
                _ => {}
            }
        }
    })
    .await
    .unwrap();
}

#[tokio::test]
async fn dual_wss_authenticates_uploads_and_deduplicates_without_blocking_control() {
    use voicelink_receiver::images::wire::encode_chunk;
    let directory = Directory::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let (receiver, service) = image_service(&directory, calls.clone()).await;
    let invitation = invitation(&service).await;
    let paired = pair(&service, &invitation).await;
    let state = receiver.state().await.unwrap();
    receiver
        .enable_foreground_input(state.state_rev, false)
        .await
        .unwrap();
    let mut control = socket(&invitation).await;
    send(&mut control,json!({"v":3,"t":"hello","name":"Image fixture","pair_id":paired["pair_id"],"token":paired["token"]})).await;
    let welcome = next(&mut control, "welcome").await;
    query_ready(&mut control, welcome["session_id"].as_str().unwrap()).await;
    assert_eq!(welcome["image"]["path"], "/sayin/v3/images");
    let mut images = socket_path(&invitation, "/sayin/v3/images").await;
    image_hello(&mut images, &welcome, &paired).await;
    assert_eq!(
        next(&mut images, "image_welcome").await["session_id"],
        welcome["session_id"]
    );
    let bytes = test_png();
    let upload = Uuid::new_v4().to_string();
    image_begin(&mut images, &welcome, &upload, &bytes).await;
    let started = std::time::Instant::now();
    send(
        &mut control,
        json!({"v":3,"t":"ping","session_id":welcome["session_id"],"nonce":"during-image"}),
    )
    .await;
    assert_eq!(next(&mut control, "pong").await["nonce"], "during-image");
    assert!(started.elapsed() < Duration::from_secs(1));
    images
        .send(Message::Binary(
            encode_chunk(&upload, 0, &bytes).unwrap().into(),
        ))
        .await
        .unwrap();
    assert_eq!(
        next(&mut images, "image_progress").await["received"],
        bytes.len()
    );
    send(
        &mut images,
        json!({"v":3,"t":"image_finish","session_id":welcome["session_id"],"id":upload}),
    )
    .await;
    let ready = next(&mut images, "image_ready").await;
    assert_ne!(ready["image"]["resource_id"], upload);
    let id = Uuid::new_v4().to_string();
    let submit = json!({"v":3,"t":"submit","session_id":welcome["session_id"],"id":id,"target":welcome["target"],"op":{"kind":"image","image":ready["image"]}});
    send(&mut control, submit.clone()).await;
    loop {
        let status = next(&mut control, "status").await;
        if status["st"] == "injected" {
            assert_eq!(status["execution"]["method"], "unicode");
            break;
        }
        assert!(status["st"] == "accepted" || status["st"] == "executing");
    }
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    send(&mut control, submit).await;
    assert_eq!(next(&mut control, "status").await["st"], "injected");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    images.close(None).await.unwrap();
    connection_closed(&mut images).await;
    // Successful image authentication must not accumulate failed-attempt debt.
    for _ in 0..6 {
        let mut images = socket_path(&invitation, "/sayin/v3/images").await;
        image_hello(&mut images, &welcome, &paired).await;
        next(&mut images, "image_welcome").await;
        send(&mut images,json!({"v":3,"t":"image_cancel","session_id":welcome["session_id"],"id":Uuid::new_v4().to_string()})).await;
        next(&mut images, "image_canceled").await;
        connection_closed(&mut images).await;
    }
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

#[tokio::test]
async fn two_distinct_pairs_can_hold_image_slots_in_the_same_scope() {
    use voicelink_receiver::images::wire::encode_chunk;
    let directory = Directory::new();
    let (receiver, service) = image_service(&directory, Arc::new(AtomicUsize::new(0))).await;
    let first_invite = invitation(&service).await;
    let first_pair = pair(&service, &first_invite).await;
    let second_invite = invitation(&service).await;
    let second_pair = pair(&service, &second_invite).await;
    let state = receiver.state().await.unwrap();
    receiver
        .enable_foreground_input(state.state_rev, false)
        .await
        .unwrap();

    let bytes = test_png();
    let mut open_uploads = Vec::new();
    for (invite, paired) in [(&first_invite, &first_pair), (&second_invite, &second_pair)] {
        let mut control = socket(invite).await;
        send(&mut control,json!({"v":3,"t":"hello","name":"Image fixture","pair_id":paired["pair_id"],"token":paired["token"]})).await;
        let welcome = next(&mut control, "welcome").await;
        query_ready(&mut control, welcome["session_id"].as_str().unwrap()).await;
        let mut images = socket_path(invite, "/sayin/v3/images").await;
        image_hello(&mut images, &welcome, paired).await;
        assert_eq!(
            next(&mut images, "image_welcome").await["pair_id"],
            paired["pair_id"]
        );
        let id = Uuid::new_v4().to_string();
        image_begin(&mut images, &welcome, &id, &bytes).await;
        if open_uploads.is_empty() {
            let mut duplicate = socket_path(invite, "/sayin/v3/images").await;
            image_hello(&mut duplicate, &welcome, paired).await;
            connection_closed(&mut duplicate).await;
        }
        open_uploads.push((control, images, welcome, id));
    }
    for (_, images, welcome, id) in &mut open_uploads {
        images
            .send(Message::Binary(encode_chunk(id, 0, &bytes).unwrap().into()))
            .await
            .unwrap();
        next(images, "image_progress").await;
        send(
            images,
            json!({"v":3,"t":"image_finish","session_id":welcome["session_id"],"id":id}),
        )
        .await;
        assert!(next(images, "image_ready").await["image"]["resource_id"].is_string());
    }
    drop(open_uploads);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

#[tokio::test]
async fn image_rejects_wrong_binding_repeated_chunks_and_old_control_session() {
    for fault in [
        "binding",
        "missing_binding",
        "other_pair",
        "offset",
        "cancel",
        "pause",
        "rotate_scope",
        "disconnect",
        "replace",
        "revoke",
    ] {
        let directory = Directory::new();
        let calls = Arc::new(AtomicUsize::new(0));
        let (receiver, service) = image_service(&directory, calls.clone()).await;
        let invitation = invitation(&service).await;
        let paired = pair(&service, &invitation).await;
        let state = receiver.state().await.unwrap();
        receiver
            .enable_foreground_input(state.state_rev, false)
            .await
            .unwrap();
        let mut control = socket(&invitation).await;
        let hello = json!({"v":3,"t":"hello","name":"Image fixture","pair_id":paired["pair_id"],"token":paired["token"]});
        send(&mut control, hello.clone()).await;
        let welcome = next(&mut control, "welcome").await;
        query_ready(&mut control, welcome["session_id"].as_str().unwrap()).await;
        let mut images = socket_path(&invitation, "/sayin/v3/images").await;
        if matches!(fault, "binding" | "missing_binding" | "other_pair") {
            let mut frame = json!({"v":3,"t":"image_hello","session_id":welcome["session_id"],"pair_id":paired["pair_id"],"binding":welcome["image"]["binding"]});
            match fault {
                "binding" => {
                    frame["binding"] = json!("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
                }
                "missing_binding" => {
                    frame.as_object_mut().unwrap().remove("binding");
                }
                "other_pair" => frame["pair_id"] = json!(Uuid::new_v4().to_string()),
                _ => unreachable!(),
            }
            send(&mut images, frame).await;
        } else {
            image_hello(&mut images, &welcome, &paired).await;
        }
        if !matches!(fault, "binding" | "missing_binding" | "other_pair") {
            next(&mut images, "image_welcome").await;
            let bytes = test_png();
            let id = Uuid::new_v4().to_string();
            image_begin(&mut images, &welcome, &id, &bytes).await;
            match fault {
                "offset" => {
                    images
                        .send(Message::Binary(
                            voicelink_receiver::images::wire::encode_chunk(&id, 1, &bytes)
                                .unwrap()
                                .into(),
                        ))
                        .await
                        .unwrap();
                }
                "cancel" => {
                    send(&mut images,json!({"v":3,"t":"image_cancel","session_id":welcome["session_id"],"id":id})).await;
                    next(&mut images, "image_canceled").await;
                }
                "pause" => {
                    receiver.pause().await.unwrap();
                }
                "rotate_scope" => {
                    receiver.pause().await.unwrap();
                    let current = receiver.state().await.unwrap();
                    let changed = receiver
                        .enable_foreground_input(current.state_rev, false)
                        .await
                        .unwrap();
                    assert_ne!(changed.target.as_deref(), welcome["target"].as_str());
                    let frame =
                        voicelink_receiver::images::wire::encode_chunk(&id, 0, &bytes).unwrap();
                    let _ = images.send(Message::Binary(frame.into())).await;
                }
                "disconnect" => {
                    control.close(None).await.unwrap();
                }
                "replace" => {
                    let mut newer = socket(&invitation).await;
                    send(&mut newer, hello).await;
                    next(&mut newer, "welcome").await;
                }
                "revoke" => {
                    receiver
                        .revoke(paired["pair_id"].as_str().unwrap().into())
                        .await
                        .unwrap();
                }
                _ => unreachable!(),
            }
        }
        connection_closed(&mut images).await;
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        service.shutdown().await.unwrap();
        receiver.shutdown().await.unwrap();
    }
}
