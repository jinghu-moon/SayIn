#[path = "../../../tests/support/manual_clock.rs"]
mod manual_clock;

use futures_util::{SinkExt, StreamExt};
use manual_clock::ManualClock;
use rustls::{
    ClientConfig, DigitallySignedStruct, Error as TlsError, RootCertStore, SignatureScheme,
    client::{
        WebPkiServerVerifier,
        danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    },
    crypto::{CryptoProvider, verify_tls13_signature},
    pki_types::{CertificateDer, ServerName, UnixTime},
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
use voicelink_pairing::Invitation;
use voicelink_receiver::{
    application_icon::ApplicationDisplay,
    gate::InputGate,
    history::TargetApplication,
    ledger::{Execution, InputOutcome},
    protocol_v3::StatusFrame,
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
async fn held_expiry_pushes_without_queries_and_unblocks_execution_and_replacement() {
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
    let old_pair = paired["pair_id"].as_str().unwrap();
    service
        .open_pairing_replacing(old_pair.to_owned())
        .await
        .unwrap();
    let blocked_invite = current_invitation(&service).await;
    let replace = |invite: &Invitation| {
        json!({"v":3,"t":"pair","name":"Test Android","secret":invite.secret,
        "replace_pair_id":old_pair,"replace_token":paired["token"]})
    };
    let (mut blocked, _) =
        approve_pairing_request(&service, &blocked_invite, replace(&blocked_invite)).await;
    assert_eq!(
        next(&mut blocked, "err").await["code"],
        "PAIR_REPLACE_BLOCKED"
    );

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

    service
        .open_pairing_replacing(old_pair.to_owned())
        .await
        .unwrap();
    let replacement_invite = current_invitation(&service).await;
    let replacement =
        pair_with_request(&service, &replacement_invite, replace(&replacement_invite)).await;
    assert_ne!(replacement["pair_id"], paired["pair_id"]);
    let mut replaced = socket_path(&replacement_invite, "/sayin/v3").await;
    send(
        &mut replaced,
        json!({"v":3,"t":"hello","name":"Test Android",
        "pair_id":replacement["pair_id"],"token":replacement["token"]}),
    )
    .await;
    let current = next(&mut replaced, "welcome").await;
    let reference = Uuid::new_v4().to_string();
    send(
        &mut replaced,
        json!({"v":3,"t":"query","session_id":current["session_id"],"ref":reference,"ids":[first]}),
    )
    .await;
    let transferred = next(&mut replaced, "status").await;
    assert_eq!(transferred["id"], expired["id"]);
    assert_eq!(transferred["st"], "expired");
    assert_eq!(transferred["reason"], "hold_expired");
    assert_eq!(transferred["accept_order"], expired["accept_order"]);
    assert_eq!(transferred["request_rev"], expired["request_rev"]);
    assert_eq!(next(&mut replaced, "queried").await["ref"], reference);

    let mut revoked = socket_path(&replacement_invite, "/sayin/v3").await;
    send(
        &mut revoked,
        json!({"v":3,"t":"hello","name":"Test Android",
        "pair_id":paired["pair_id"],"token":paired["token"]}),
    )
    .await;
    assert_eq!(next(&mut revoked, "err").await["code"], "UNAUTHORIZED");
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

async fn socket(invitation: &Invitation) -> Socket {
    socket_path(invitation, "/sayin/v3").await
}
#[derive(Debug)]
struct DigestVerifier {
    digest: String,
    crypto: Arc<CryptoProvider>,
}
impl ServerCertVerifier for DigestVerifier {
    fn verify_server_cert(
        &self,
        leaf: &CertificateDer<'_>,
        chain: &[CertificateDer<'_>],
        name: &ServerName<'_>,
        ocsp: &[u8],
        now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        if !chain.is_empty() || voicelink_pairing::fingerprint(leaf.as_ref()) != self.digest {
            return Err(TlsError::General("Unexpected leaf certificate".into()));
        }
        let mut roots = RootCertStore::empty();
        roots.add(leaf.clone())?;
        WebPkiServerVerifier::builder_with_provider(Arc::new(roots), self.crypto.clone())
            .build()
            .map_err(|error| TlsError::General(error.to_string()))?
            .verify_server_cert(leaf, chain, name, ocsp, now)
    }
    fn verify_tls12_signature(
        &self,
        _: &[u8],
        _: &CertificateDer<'_>,
        _: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        Err(TlsError::General("TLS 1.3 required".into()))
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        leaf: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(
            message,
            leaf,
            signature,
            &self.crypto.signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.crypto
            .signature_verification_algorithms
            .supported_schemes()
    }
}
async fn pinned_tls(invitation: &Invitation) -> std::io::Result<TlsStream<TcpStream>> {
    let crypto = Arc::new(rustls::crypto::ring::default_provider());
    let config = ClientConfig::builder_with_provider(crypto.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(DigestVerifier {
            digest: invitation.sha256.clone(),
            crypto,
        }))
        .with_no_client_auth();
    let tcp = TcpStream::connect((invitation.address, invitation.port)).await?;
    TlsConnector::from(Arc::new(config))
        .connect(
            ServerName::try_from(invitation.tls_name.clone()).unwrap(),
            tcp,
        )
        .await
}
async fn socket_path(invitation: &Invitation, path: &str) -> Socket {
    let tls = pinned_tls(invitation).await.unwrap();
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
                assert_ne!(
                    value["t"], "err",
                    "received an error while waiting for {kind}"
                );
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
    current_invitation(service).await
}
async fn current_invitation(service: &NetworkService) -> Invitation {
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
    pair_with_request(
        service,
        invitation,
        json!({"v":3,"t":"pair","name":"Test Android","secret":invitation.secret}),
    )
    .await
}

async fn pair_with_request(
    service: &NetworkService,
    invitation: &Invitation,
    request: Value,
) -> Value {
    let (mut socket, candidate) = approve_pairing_request(service, invitation, request).await;
    let paired = next(&mut socket, "paired").await;
    assert_eq!(paired["pair_id"], candidate["pair_id"]);
    assert_eq!(paired["host_id"], candidate["host_id"]);
    assert!(paired.get("token").is_none());
    candidate
}

async fn approve_pairing_request(
    service: &NetworkService,
    invitation: &Invitation,
    request: Value,
) -> (Socket, Value) {
    let mut socket = socket(invitation).await;
    send(&mut socket, request).await;
    let candidate = next(&mut socket, "pair_candidate").await;
    assert!(service.snapshot().unwrap().approval.is_none());
    send(
        &mut socket,
        json!({"v":3,"t":"pair_saved","pair_id":candidate["pair_id"]}),
    )
    .await;
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
    assert_eq!(approval.source.to_string(), "127.0.0.1");
    service.decide(&approval.id, true).await.unwrap();
    (socket, candidate)
}

#[tokio::test]
async fn replacement_requires_selected_old_pair_and_its_token() {
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
    let invite = invitation(&service).await;
    let original = pair(&service, &invite).await;
    let old_id = original["pair_id"].as_str().unwrap();
    let mut previous = socket(&invite).await;
    let old_hello =
        json!({"v":3,"t":"hello","pair_id":old_id,"token":original["token"],"name":"Test Android"});
    send(&mut previous, old_hello.clone()).await;
    let old_session = next(&mut previous, "welcome").await["session_id"]
        .as_str()
        .unwrap()
        .to_owned();
    query_ready(&mut previous, &old_session).await;
    service
        .open_pairing_replacing(old_id.to_owned())
        .await
        .unwrap();
    let invite = current_invitation(&service).await;
    let actual = json!({"v":3,"t":"pair","name":"Test Android","secret":invite.secret,
        "replace_pair_id":old_id,"replace_token":original["token"]});
    let mut bad = socket(&invite).await;
    let mut invalid = actual.clone();
    invalid["replace_token"] = json!(voicelink_pairing::encode(&[0; 32]));
    send(&mut bad, invalid).await;
    assert_eq!(next(&mut bad, "err").await["code"], "UNAUTHORIZED");
    assert!(service.snapshot().unwrap().approval.is_none());
    query_ready(&mut previous, &old_session).await;

    let replacement = pair_with_request(&service, &invite, actual).await;
    assert_eq!(replacement["host_id"], original["host_id"]);
    assert_ne!(replacement["pair_id"], original["pair_id"]);
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 1);
    let mut revoked = socket(&invite).await;
    send(&mut revoked, old_hello).await;
    assert_eq!(next(&mut revoked, "err").await["code"], "UNAUTHORIZED");
    let mut restored = socket(&invite).await;
    send(&mut restored, json!({"v":3,"t":"hello","pair_id":replacement["pair_id"],"token":replacement["token"],"name":"Test Android"})).await;
    assert_eq!(
        next(&mut restored, "welcome").await["pair_id"],
        replacement["pair_id"]
    );
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

#[tokio::test]
async fn candidate_cannot_authenticate_or_be_approved_from_another_socket() {
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
    let invite = invitation(&service).await;
    let request = json!({"v":3,"t":"pair","name":"Test Android","secret":invite.secret});
    let mut original = socket(&invite).await;
    send(&mut original, request.clone()).await;
    let candidate = next(&mut original, "pair_candidate").await;
    assert!(service.snapshot().unwrap().approval.is_none());
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 0);
    assert!(
        service
            .decide(candidate["pair_id"].as_str().unwrap(), true)
            .await
            .is_err()
    );

    let mut other = socket(&invite).await;
    send(
        &mut other,
        json!({"v":3,"t":"pair_saved","pair_id":candidate["pair_id"]}),
    )
    .await;
    assert_eq!(next(&mut other, "err").await["code"], "UNSUPPORTED");
    let mut auth = socket(&invite).await;
    send(&mut auth, json!({"v":3,"t":"hello","name":"Test Android","pair_id":candidate["pair_id"],"token":candidate["token"]})).await;
    assert_eq!(next(&mut auth, "err").await["code"], "UNAUTHORIZED");
    assert!(service.snapshot().unwrap().approval.is_none());

    send(
        &mut original,
        json!({"v":3,"t":"pair_saved","pair_id":Uuid::new_v4().to_string()}),
    )
    .await;
    assert_eq!(next(&mut original, "err").await["code"], "INVALID_REQUEST");
    drop(original);
    assert!(service.snapshot().unwrap().approval.is_none());
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 0);

    let fresh = pair(&service, &invite).await;
    assert_ne!(fresh["pair_id"], candidate["pair_id"]);
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 1);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
}

#[tokio::test]
async fn candidate_reserves_invitation_and_close_clears_it_after_confirmation() {
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
    let mut updates = service.subscribe().unwrap();
    let invite = invitation(&service).await;
    let request = json!({"v":3,"t":"pair","name":"Test Android","secret":invite.secret});
    let mut original = socket(&invite).await;
    send(&mut original, request.clone()).await;
    let candidate = next(&mut original, "pair_candidate").await;
    let mut competing = socket(&invite).await;
    send(&mut competing, request).await;
    assert_eq!(next(&mut competing, "err").await["code"], "PAIRING_BUSY");
    send(
        &mut original,
        json!({"v":3,"t":"pair_saved","pair_id":candidate["pair_id"]}),
    )
    .await;
    timeout(Duration::from_secs(5), async {
        while service.snapshot().unwrap().approval.is_none() {
            updates.next().await.unwrap();
        }
        original.close(None).await.unwrap();
        while service.snapshot().unwrap().approval.is_some() {
            updates.next().await.unwrap();
        }
    })
    .await
    .unwrap();
    assert!(
        service
            .decide(candidate["pair_id"].as_str().unwrap(), true)
            .await
            .is_err()
    );
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 0);
    pair(&service, &invite).await;
    assert_eq!(receiver.snapshot().await.unwrap().paired_devices, 1);
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
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
    assert_eq!(service.snapshot().unwrap().window.unwrap().qr_modules(), 45);
    assert!(invitation.tls_name.starts_with("sayin-"));
    let mut wrong_certificate = invitation.clone();
    wrong_certificate.sha256 = voicelink_pairing::fingerprint(b"unrelated");
    assert!(pinned_tls(&wrong_certificate).await.is_err());
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

struct BudgetAdapter {
    application: ApplicationDisplay,
    reasons: [&'static str; 3],
    calls: usize,
}

impl InputAdapter for BudgetAdapter {
    fn preflight(&mut self, _: &Execution) -> Result<(), &'static str> {
        Ok(())
    }

    fn prepared_application(&self) -> Option<TargetApplication> {
        let mut target = TargetApplication::new("test.exe".into(), "fixture".into(), 1, 1);
        let mut application = self.application.clone();
        if self.calls == 2 {
            application.name.push('😀');
        }
        target.display = Some(application);
        Some(target)
    }

    fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
        let reason = self.reasons[self.calls];
        self.calls += 1;
        InputOutcome::Failed { reason }
    }
}

async fn failed_frame(socket: &mut Socket, id: &str) -> Vec<u8> {
    timeout(Duration::from_secs(5), async {
        loop {
            if let Message::Text(frame) = socket.next().await.unwrap().unwrap() {
                let value: Value = serde_json::from_str(&frame).unwrap();
                assert_ne!(value["t"], "err", "{value}");
                if value["t"] == "status" && value["id"] == id && value["st"] == "failed" {
                    return frame.as_bytes().to_vec();
                }
            }
        }
    })
    .await
    .unwrap()
}

#[tokio::test]
async fn authenticated_wss_preserves_exact_receipt_bytes_and_stays_open_after_budget_fallback() {
    let directory = Directory::new();
    let golden: Value = serde_json::from_str(include_str!(
        "../../../../docs/research/91-golden/status-budget-with-icon.json"
    ))
    .unwrap();
    let application: ApplicationDisplay =
        serde_json::from_value(golden["application"].clone()).unwrap();
    assert!(application.valid());
    assert_eq!(application.name.chars().count(), 96);
    assert_eq!(application.icon_webp.as_ref().unwrap().len(), 4096);
    let mut expected = json!({
        "v": 3, "t": "status", "session_id": Uuid::from_u128(0), "id": Uuid::from_u128(0),
        "accept_order": "1", "request_rev": "2", "st": "failed",
        "execution": {"stage": "prepared", "method": "unicode", "confirmed": false},
        "application": golden["application"], "reason": "",
    });
    // Pad only the fixture adapter's reason. No production reason is expanded.
    let padding = 8192 - serde_json::to_vec(&expected).unwrap().len();
    let exact: &'static str = "x".repeat(padding).leak();
    let over: &'static str = "x".repeat(padding + 1).leak();
    let receiver = Receiver::start(
        open_ledger(&directory.0).unwrap(),
        BudgetAdapter {
            application,
            reasons: [exact, over, "fixture_failed"],
            calls: 0,
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
    let mut ids = Vec::new();
    for (index, reason) in [exact, over, "fixture_failed"].into_iter().enumerate() {
        let id = Uuid::new_v4().to_string();
        send(
            &mut socket,
            json!({"v":3,"t":"submit","session_id":session,"id":id,"target":target,
                "op":{"kind":"text","text":"fixture","src":"manual"}}),
        )
        .await;
        let raw = failed_frame(&mut socket, &id).await;
        expected["session_id"] = json!(session);
        expected["id"] = json!(id);
        expected["accept_order"] = json!((index + 1).to_string());
        expected["reason"] = json!(reason);
        expected["application"] = golden["application"].clone();
        let object = expected.as_object_mut().unwrap();
        match index {
            0 => assert_eq!(raw.len(), 8192),
            1 => {
                assert_eq!(serde_json::to_vec(&object).unwrap().len(), 8193);
                object["application"]
                    .as_object_mut()
                    .unwrap()
                    .remove("icon_webp");
            }
            2 => {
                object.remove("application");
            }
            _ => unreachable!(),
        }
        expected.sort_all_objects();
        assert_eq!(raw, serde_json::to_vec(&expected).unwrap());
        assert_eq!(StatusFrame::decode(&raw).unwrap().encode().unwrap(), raw);
        ids.push((id, raw));
    }
    for (id, raw) in ids {
        let reference = Uuid::new_v4().to_string();
        send(
            &mut socket,
            json!({"v":3,"t":"query","session_id":session,"ref":reference,"ids":[id]}),
        )
        .await;
        assert_eq!(failed_frame(&mut socket, &id).await, raw);
        assert_eq!(next(&mut socket, "queried").await["ref"], reference);
    }
    service.shutdown().await.unwrap();
    receiver.shutdown().await.unwrap();
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
    send(
        &mut socket,
        json!({"v":3,"t":"pair","name":"Rejected","secret":invitation.secret}),
    )
    .await;
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
    assert_eq!(next(&mut socket, "err").await["code"], "UNAUTHORIZED");
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
    assert_eq!(next(&mut revoked, "err").await["code"], "UNAUTHORIZED");
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
            let rejection = next(&mut duplicate, "err").await;
            assert_eq!(rejection["code"], "IMAGE_UPLOAD_LIMIT");
            assert_eq!(rejection["session_id"], welcome["session_id"]);
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
async fn authenticated_image_slot_contention_returns_an_explicit_result() {
    use std::time::Instant;

    let directory = Directory::new();
    let calls = Arc::new(AtomicUsize::new(0));
    let (receiver, service) = image_service(&directory, calls.clone()).await;
    let mut pairs = Vec::new();
    for _ in 0..3 {
        let invite = invitation(&service).await;
        let paired = pair(&service, &invite).await;
        pairs.push((invite, paired));
    }
    let state = receiver.state().await.unwrap();
    receiver
        .enable_foreground_input(state.state_rev, false)
        .await
        .unwrap();

    let mut peers = Vec::new();
    for (invite, paired) in pairs {
        let mut control = socket(&invite).await;
        send(&mut control,json!({"v":3,"t":"hello","name":"Slot fixture","pair_id":paired["pair_id"],"token":paired["token"]})).await;
        let welcome = next(&mut control, "welcome").await;
        query_ready(&mut control, welcome["session_id"].as_str().unwrap()).await;
        peers.push((invite, paired, welcome, control));
    }
    let [
        (a, pair_a, welcome_a, _),
        (b, pair_b, welcome_b, _),
        (c, pair_c, welcome_c, _),
    ] = peers.as_mut_slice()
    else {
        unreachable!("three authenticated pairs")
    };

    let mut first = socket_path(a, "/sayin/v3/images").await;
    image_hello(&mut first, welcome_a, pair_a).await;
    next(&mut first, "image_welcome").await;
    image_begin(
        &mut first,
        welcome_a,
        &Uuid::new_v4().to_string(),
        &test_png(),
    )
    .await;

    let mut timings = Vec::new();
    for _ in 0..100 {
        let mut duplicate = socket_path(a, "/sayin/v3/images").await;
        image_hello(&mut duplicate, welcome_a, pair_a).await;
        assert_eq!(
            next(&mut duplicate, "err").await["code"],
            "IMAGE_UPLOAD_LIMIT"
        );
        connection_closed(&mut duplicate).await;

        let start = Instant::now();
        let mut second = socket_path(b, "/sayin/v3/images").await;
        image_hello(&mut second, welcome_b, pair_b).await;
        assert_eq!(
            next(&mut second, "image_welcome").await["pair_id"],
            pair_b["pair_id"]
        );
        let open_ms = start.elapsed().as_secs_f64() * 1000.;

        let start = Instant::now();
        let mut third = socket_path(c, "/sayin/v3/images").await;
        image_hello(&mut third, welcome_c, pair_c).await;
        let rejection = next(&mut third, "err").await;
        assert_eq!(rejection["code"], "IMAGE_UPLOAD_LIMIT");
        assert_eq!(rejection["session_id"], welcome_c["session_id"]);
        let full_ms = start.elapsed().as_secs_f64() * 1000.;
        timings.push((open_ms, full_ms));
        connection_closed(&mut third).await;

        let id = Uuid::new_v4().to_string();
        send(
            &mut second,
            json!({"v":3,"t":"image_cancel","session_id":welcome_b["session_id"],"id":id}),
        )
        .await;
        assert_eq!(next(&mut second, "image_canceled").await["id"], id);
        connection_closed(&mut second).await;
    }
    println!("image slot timing samples (free/full ms): {timings:?}");
    assert!(timings.iter().filter(|(free, _)| *free > 1000.).count() <= 1);
    assert!(timings.iter().filter(|(_, full)| *full > 1000.).count() <= 1);
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    drop(first);
    drop(peers);
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
