//! Service implementation (one supervised runtime, bounded per-connection work).
pub use crate::discovery::PublicationStatus;
use crate::interfaces::NetworkBinding;
use crate::{Error, HostIdentity, Result};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use futures_channel::{mpsc as notifications, oneshot};
use futures_util::{SinkExt, StreamExt};
use qrcode::{QrCode, render::svg};
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    net::{IpAddr, SocketAddr},
    sync::{Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{Mutex as AsyncMutex, Semaphore, watch},
    task::JoinSet,
    time::timeout,
};
use tokio_rustls::{TlsAcceptor, server::TlsStream};
use tokio_tungstenite::{
    WebSocketStream, accept_hdr_async_with_config,
    tungstenite::{
        Message,
        handshake::server::{Request, Response},
        protocol::WebSocketConfig,
    },
};
use uuid::Uuid;
use voicelink_pairing::{BootstrapInfo, Exchange, Invitation, MAGIC, decode, encode, random};
use voicelink_receiver::{
    ledger::{ControlState, Session},
    protocol::{Receipt, Submit, strict_json, validate_uuid},
    protocol_v3::{
        Application, Execution, ExecutionMethod, ExecutionStage, Status, StatusFrame, StatusState,
    },
    worker::Receiver,
};
use zeroize::Zeroizing;

mod foreground;
mod image_channel;
#[cfg(test)]
mod tests;

const MAX_OUTBOUND_BYTES: usize = 8_192;

#[derive(Clone)]
#[non_exhaustive]
pub struct PairingWindow {
    pub id: String,
    pub code: Arc<Zeroizing<String>>,
    pub qr_svg: Arc<[u8]>,
    qr_modules: u32,
    pub expires: Instant,
}
impl PairingWindow {
    /// Module count per side, including the four-module quiet zone on each edge.
    pub fn qr_modules(&self) -> u32 {
        self.qr_modules
    }
}
#[derive(Clone, Debug)]
#[non_exhaustive]
pub struct PendingApproval {
    pub id: String,
    pub name: String,
    pub source: IpAddr,
    pub replaces: Option<String>,
}
#[derive(Clone)]
#[non_exhaustive]
pub struct NetworkSnapshot {
    pub address: SocketAddr,
    pub window: Option<PairingWindow>,
    pub approval: Option<PendingApproval>,
    connected_devices: HashMap<String, usize>,
    pub running: bool,
    pub discovery: PublicationStatus,
}
impl NetworkSnapshot {
    pub fn is_device_connected(&self, device: &str) -> bool {
        self.running && self.connected_devices.contains_key(device)
    }

    pub fn connected_devices(&self) -> usize {
        self.connected_devices.len()
    }
}

/// Take one presence lease per authenticated control socket. Reconnecting the
/// same pair can overlap the old socket until its session check completes.
struct OnlineDevice<'a> {
    shared: &'a Shared,
    device: &'a str,
}
impl<'a> OnlineDevice<'a> {
    fn register(shared: &'a Shared, device: &'a str) -> Result<Self> {
        {
            let mut snapshot = shared
                .snapshot
                .lock()
                .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?;
            *snapshot
                .connected_devices
                .entry(device.to_owned())
                .or_default() += 1;
        }
        shared.notify();
        Ok(Self { shared, device })
    }
}
impl Drop for OnlineDevice<'_> {
    fn drop(&mut self) {
        if let Ok(mut snapshot) = self.shared.snapshot.lock()
            && let Some(count) = snapshot.connected_devices.get_mut(self.device)
        {
            *count -= 1;
            if *count == 0 {
                snapshot.connected_devices.remove(self.device);
            }
        }
        self.shared.notify();
    }
}

struct Pairing {
    info: BootstrapInfo,
    code: Zeroizing<String>,
    invitation: Invitation,
    expires: Instant,
    attempts: u8,
    request: Option<PairingRequest>,
    replaces: Option<String>,
}
struct PairingRequest {
    id: String,
    saved: bool,
    decision: Option<oneshot::Sender<bool>>,
    finished: watch::Sender<()>,
}
struct Shared {
    #[cfg(feature = "receipt-replay-fixture")]
    replay_old_receipt: std::sync::atomic::AtomicBool,
    receiver: Receiver,
    identity: HostIdentity,
    computer_name: Option<String>,
    address: SocketAddr,
    pairing: AsyncMutex<Option<Pairing>>,
    snapshot: Mutex<NetworkSnapshot>,
    observers: Mutex<Vec<notifications::Sender<()>>>,
    failures: Mutex<VecDeque<(IpAddr, Instant, u8)>>,
    image_bindings: Mutex<HashMap<String, Arc<image_channel::Binding>>>,
    uploading_pairs: Mutex<HashSet<String>>,
    image_slots: Arc<Semaphore>,
    image_decode: Arc<Semaphore>,
    control_slots: Arc<Semaphore>,
    foreground: watch::Sender<foreground::Sample>,
}
impl Shared {
    fn notify(&self) {
        if let Ok(mut observers) = self.observers.lock() {
            observers.retain_mut(|sender| match sender.try_send(()) {
                Ok(()) => true,
                Err(error) => error.is_full(),
            });
        }
    }
    fn attempt(&self, ip: IpAddr) -> Result<()> {
        let mut failures = self
            .failures
            .lock()
            .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?;
        failures.retain(|(_, time, _)| time.elapsed() < Duration::from_secs(60));
        if let Some((_, _, count)) = failures.iter_mut().find(|(address, _, _)| *address == ip) {
            if *count >= 5 {
                return Err(Error::Protocol("AUTH_COOLDOWN"));
            }
            *count += 1;
        } else {
            if failures.len() >= 64 {
                return Err(Error::Protocol("AUTH_COOLDOWN"));
            }
            failures.push_back((ip, Instant::now(), 1));
        }
        Ok(())
    }
    fn authenticated(&self, ip: IpAddr) {
        if let Ok(mut failures) = self.failures.lock() {
            failures.retain(|(address, _, _)| *address != ip);
        }
    }
    fn clear_pairing_snapshot(&self) {
        if let Ok(mut snapshot) = self.snapshot.lock() {
            snapshot.window = None;
            snapshot.approval = None;
        }
        self.notify();
    }
}
struct Handle {
    shared: Arc<Shared>,
    stop: watch::Sender<bool>,
    done: Mutex<Option<oneshot::Receiver<()>>>,
}
impl Drop for Handle {
    fn drop(&mut self) {
        let _ = self.stop.send(true);
    }
}

#[derive(Clone)]
pub struct NetworkService {
    handle: Arc<Handle>,
}
impl NetworkService {
    #[cfg(feature = "receipt-replay-fixture")]
    pub fn enable_receipt_replay_fixture(&self) {
        self.handle
            .shared
            .replay_old_receipt
            .store(true, std::sync::atomic::Ordering::Relaxed);
    }

    /// Loopback fixtures cannot use this entry point to expose a LAN listener.
    pub fn start_loopback(
        receiver: Receiver,
        identity: HostIdentity,
        bind: SocketAddr,
    ) -> Result<Self> {
        if !bind.ip().is_loopback() {
            return Err(Error::Protocol("INTERFACE_NOT_PERMITTED"));
        }
        Self::start_bound(receiver, identity, bind, None)
    }

    pub fn start_on_network(
        receiver: Receiver,
        identity: HostIdentity,
        binding: NetworkBinding,
        port: u16,
    ) -> Result<Self> {
        binding.validate()?;
        let bind = SocketAddr::new(binding.address().into(), port);
        Self::start_bound(receiver, identity, bind, Some(binding))
    }

    fn start_bound(
        receiver: Receiver,
        identity: HostIdentity,
        bind: SocketAddr,
        binding: Option<NetworkBinding>,
    ) -> Result<Self> {
        let listener = std::net::TcpListener::bind(bind)?;
        listener.set_nonblocking(true)?;
        let address = listener.local_addr()?;
        let shared = Arc::new(Shared {
            #[cfg(feature = "receipt-replay-fixture")]
            replay_old_receipt: std::sync::atomic::AtomicBool::new(false),
            receiver,
            identity,
            computer_name: crate::discovery::computer_name(),
            address,
            pairing: AsyncMutex::new(None),
            snapshot: Mutex::new(NetworkSnapshot {
                address,
                window: None,
                approval: None,
                connected_devices: HashMap::new(),
                running: true,
                discovery: if binding.is_some() {
                    PublicationStatus::Publishing
                } else {
                    PublicationStatus::Disabled
                },
            }),
            observers: Mutex::new(Vec::new()),
            failures: Mutex::new(VecDeque::new()),
            image_bindings: Mutex::new(HashMap::new()),
            uploading_pairs: Mutex::new(HashSet::new()),
            image_slots: Arc::new(Semaphore::new(2)),
            image_decode: Arc::new(Semaphore::new(1)),
            control_slots: Arc::new(Semaphore::new(8)),
            foreground: watch::channel(foreground::Sample::default()).0,
        });
        let (stop, mut stopping) = watch::channel(false);
        let (done, completion) = oneshot::channel();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?;
        // A loopback fixture never advertises. A LAN publisher is scoped to the
        // exact listener binding and is withdrawn before network-lost handling.
        let publisher = binding.as_ref().and_then(|binding| {
            crate::discovery::Publisher::start(binding, address, &shared.identity.host_id).ok()
        });
        let binding_is_lan = binding.is_some();
        let state = shared.clone();
        let policy_stop = stop.clone();
        thread::Builder::new().name("voicelink-network".into()).spawn(move || {
            runtime.block_on(async move {
                let listener=match TcpListener::from_std(listener) {
                    Ok(listener)=>listener,
                    Err(_)=>{
                        let _=state.receiver.network_lost().await;
                        if let Ok(mut snapshot)=state.snapshot.lock(){snapshot.running=false;}
                        state.notify();
                        let _=done.send(());
                        return;
                    }
                };
                let total=Arc::new(Semaphore::new(16));
                // Four handshake slots remain available while eight control
                // connections are active. The total listener budget is 16.
                let unauthenticated=Arc::new(Semaphore::new(12));
                let mut connections=JoinSet::new();
                let monitor=tokio::spawn(monitor_profile(state.clone(),binding,policy_stop.clone(),stopping.clone(),crate::interfaces::available_interfaces));
                let foreground_monitor = tokio::spawn(foreground::monitor(state.clone(), stopping.clone()));
                let mut maintenance=tokio::time::interval(Duration::from_secs(1));
                loop {
                    tokio::select! {
                        _=stopping.changed()=>break,
                        accepted=listener.accept()=>{
                            let Ok((stream,peer))=accepted else {break;};
                            let Ok(permit)=total.clone().try_acquire_owned() else {continue;};
                            let Ok(unauthenticated)=unauthenticated.clone().try_acquire_owned() else {continue;};
                            let shared=state.clone();
                            let closed=stopping.clone();
                            connections.spawn(async move {
                                let _permit=permit;
                                let _=connection(shared,stream,peer,unauthenticated,closed).await;
                            });
                        },
                        _=maintenance.tick()=>{
                            if let Some(publisher)=publisher.as_ref(){publisher.maintain();}
                            let status = if binding_is_lan { publisher.as_ref().map_or(PublicationStatus::Unavailable, |p|p.status()) } else { PublicationStatus::Disabled };
                            let changed = state.snapshot.lock().is_ok_and(|mut s| { let changed=s.discovery != status; s.discovery=status; changed });
                            if changed { state.notify(); }
                            let mut window=state.pairing.lock().await;
                            if window.as_ref().is_some_and(|window|Instant::now()>=window.expires) {
                                *window=None;state.clear_pairing_snapshot();
                            }
                        },
                        _=connections.join_next(),if !connections.is_empty()=>{},
                    }
                }
                drop(publisher);
                let _=policy_stop.send(true);
                monitor.abort();
                foreground_monitor.abort();
                // Persist suspension before publishing the stopped listener. This
                // also covers accept failures and a cancelled profile monitor.
                let _=state.receiver.network_lost().await;
                *state.pairing.lock().await=None;
                state.clear_pairing_snapshot();
                connections.abort_all();
                while connections.join_next().await.is_some() {}
                if let Ok(mut snapshot)=state.snapshot.lock(){snapshot.running=false;snapshot.connected_devices.clear();snapshot.discovery=PublicationStatus::Disabled;}
                state.notify();
                let _=done.send(());
            });
            runtime.shutdown_timeout(Duration::from_secs(1));
        })?;
        Ok(Self {
            handle: Arc::new(Handle {
                shared,
                stop,
                done: Mutex::new(Some(completion)),
            }),
        })
    }
    pub fn snapshot(&self) -> Result<NetworkSnapshot> {
        self.handle
            .shared
            .snapshot
            .lock()
            .map(|value| value.clone())
            .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))
    }
    pub fn subscribe(&self) -> Result<notifications::Receiver<()>> {
        let mut observers = self
            .handle
            .shared
            .observers
            .lock()
            .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?;
        observers.retain(|sender| !sender.is_closed());
        if observers.len() >= 16 {
            return Err(Error::Protocol("OBSERVER_LIMIT"));
        }
        let (mut sender, receiver) = notifications::channel(1);
        let _ = sender.try_send(());
        observers.push(sender);
        Ok(receiver)
    }
    pub async fn open_pairing(&self) -> Result<()> {
        self.open_pairing_with_lifetime(Duration::from_secs(120))
            .await
    }

    pub async fn open_pairing_replacing(&self, device: String) -> Result<()> {
        let state = self.handle.shared.receiver.snapshot().await?;
        if !state.device_ids.contains(&device) {
            return Err(Error::Protocol("UNAUTHORIZED"));
        }
        self.open_pairing_window(Duration::from_secs(120), Some(device))
            .await
    }

    /// Test fixture entry point; product callers use the fixed 120 second window.
    #[doc(hidden)]
    pub async fn open_pairing_with_lifetime(&self, lifetime: Duration) -> Result<()> {
        self.open_pairing_window(lifetime, None).await
    }

    async fn open_pairing_window(
        &self,
        lifetime: Duration,
        replaces: Option<String>,
    ) -> Result<()> {
        if *self.handle.stop.borrow() {
            return Err(Error::Protocol("SERVICE_UNAVAILABLE"));
        }
        let shared = &self.handle.shared;
        let mut pairing = shared.pairing.lock().await;
        let info = BootstrapInfo::new(shared.identity.host_id.clone(), Uuid::new_v4().to_string());
        let code = voicelink_pairing::pairing_code()?;
        let secret = Zeroizing::new(random::<16>()?);
        let invitation = Invitation::new(
            &info.host_id,
            &shared.identity.tls_name,
            shared.address.ip(),
            shared.address.port(),
            &shared.identity.cert,
            &secret,
        )?;
        let qr = Zeroizing::new(invitation.qr()?);
        let qr_code = QrCode::with_version(
            qr.as_bytes(),
            qrcode::Version::Normal(5),
            qrcode::EcLevel::M,
        )
        .map_err(|_| Error::Protocol("QR_CAPACITY"))?;
        if qr_code.version() != qrcode::Version::Normal(5) || qr_code.width() != 37 {
            return Err(Error::Protocol("QR_CAPACITY"));
        }
        let qr_modules = qr_code.width() as u32 + 8;
        let qr_svg = qr_code
            .render::<svg::Color>()
            .dark_color(svg::Color("#0a0a0a"))
            .light_color(svg::Color("#fafafa"))
            .quiet_zone(true)
            // One SVG unit per module, including the four-module quiet zone.
            // The desktop scales this grid by whole physical pixels, never linearly.
            .module_dimensions(1, 1)
            .build()
            .into_bytes();
        let expires = Instant::now() + lifetime;
        let window = PairingWindow {
            id: info.window_id.clone(),
            code: Arc::new(Zeroizing::new(code.to_string())),
            qr_svg: qr_svg.into(),
            qr_modules,
            expires,
        };
        *pairing = Some(Pairing {
            info,
            code,
            invitation,
            expires,
            attempts: 0,
            request: None,
            replaces,
        });
        {
            let mut snapshot = shared
                .snapshot
                .lock()
                .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?;
            snapshot.window = Some(window);
            snapshot.approval = None;
        }
        shared.notify();
        Ok(())
    }
    pub async fn close_pairing(&self) {
        *self.handle.shared.pairing.lock().await = None;
        self.handle.shared.clear_pairing_snapshot();
    }
    pub async fn decide(&self, id: &str, approved: bool) -> Result<()> {
        let shared = &self.handle.shared;
        let mut pairing = shared.pairing.lock().await;
        let window = pairing.as_mut().ok_or(Error::Protocol("PAIRING_EXPIRED"))?;
        if Instant::now() >= window.expires {
            return Err(Error::Protocol("PAIRING_EXPIRED"));
        }
        let decision = window
            .request
            .as_mut()
            .filter(|request| request.saved && request.id == id)
            .and_then(|request| request.decision.take())
            .ok_or(Error::Protocol("PAIRING_EXPIRED"))?;
        decision
            .send(approved)
            .map_err(|_| Error::Protocol("PAIRING_EXPIRED"))?;
        if let Ok(mut snapshot) = shared.snapshot.lock() {
            snapshot.approval = None;
        }
        shared.notify();
        Ok(())
    }
    pub async fn shutdown(&self) -> Result<()> {
        let completion = self
            .handle
            .done
            .lock()
            .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?
            .take();
        let _ = self.handle.stop.send(true);
        if let Some(completion) = completion {
            completion
                .await
                .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?;
        }
        Ok(())
    }
}

async fn monitor_profile(
    shared: Arc<Shared>,
    binding: Option<NetworkBinding>,
    stop: watch::Sender<bool>,
    mut stopping: watch::Receiver<bool>,
    query: impl Fn() -> Result<Vec<crate::interfaces::NetworkInterface>> + Clone + Send + 'static,
) {
    let Some(binding) = binding else {
        return;
    };
    let mut interval = tokio::time::interval(Duration::from_secs(2));
    loop {
        tokio::select! {
            _=stopping.changed()=>break,
            _=interval.tick()=>{
                let permitted=timeout(Duration::from_secs(3),tokio::task::spawn_blocking(query.clone())).await
                    .ok().and_then(std::result::Result::ok).and_then(std::result::Result::ok)
                    .is_some_and(|interfaces|binding.permits(&interfaces));
                if !permitted {
                    let pause=shared.receiver.network_lost();
                    let _=stop.send(true);
                    let _=pause.await;
                    break;
                }
            }
        }
    }
}

type Socket = WebSocketStream<TlsStream<TcpStream>>;
async fn bounded<T>(future: impl std::future::Future<Output = Result<T>>) -> Result<T> {
    timeout(Duration::from_secs(5), future)
        .await
        .map_err(|_| Error::Protocol("NETWORK_TIMEOUT"))?
}
async fn frame(stream: &mut TcpStream) -> Result<Vec<u8>> {
    let length = stream.read_u32().await? as usize;
    if length > 8192 {
        return Err(Error::Protocol("MESSAGE_LIMIT"));
    }
    let mut bytes = vec![0; length];
    stream.read_exact(&mut bytes).await?;
    Ok(bytes)
}
async fn write_frame(stream: &mut TcpStream, bytes: &[u8]) -> Result<()> {
    if bytes.len() > 8192 {
        return Err(Error::Protocol("MESSAGE_LIMIT"));
    }
    stream.write_u32(bytes.len() as u32).await?;
    stream.write_all(bytes).await?;
    stream.flush().await?;
    Ok(())
}
async fn bootstrap(shared: Arc<Shared>, stream: &mut TcpStream) -> Result<()> {
    let (info, code, invitation) = {
        let mut pairing = shared.pairing.lock().await;
        let window = pairing.as_mut().ok_or(Error::Protocol("PAIRING_CLOSED"))?;
        if Instant::now() >= window.expires || window.attempts >= 5 {
            return Err(Error::Protocol("PAIRING_EXPIRED"));
        }
        window.attempts += 1;
        (
            window.info.clone(),
            Zeroizing::new(window.code.to_string()),
            window.invitation.clone(),
        )
    };
    write_frame(stream, &serde_json::to_vec(&info)?).await?;
    let message = frame(stream).await?;
    // Curve work stays off the two asynchronous IO workers.
    let info_for_crypto = info.clone();
    let negotiation = tokio::task::spawn_blocking(move || {
        voicelink_pairing::respond(&info_for_crypto, &code, message)
    })
    .await
    .map_err(|_| Error::Protocol("PAIRING_FAILED"))??;
    let response = Exchange::new(negotiation.message(), &negotiation.tag());
    write_frame(stream, &serde_json::to_vec(&response)?).await?;
    let key = negotiation.verify(&frame(stream).await?)?;
    let pairing = shared.pairing.lock().await;
    if !pairing.as_ref().is_some_and(|window| {
        window.info.window_id == info.window_id && Instant::now() < window.expires
    }) {
        return Err(Error::Protocol("PAIRING_EXPIRED"));
    }
    let plaintext = invitation.to_bytes()?;
    let envelope = key.seal(&info, &*plaintext)?;
    drop(pairing);
    write_frame(stream, &envelope).await
}

// Tungstenite requires this owned HTTP response as its callback error.
#[allow(clippy::result_large_err)]
fn validate_upgrade(
    request: &Request,
    response: Response,
) -> std::result::Result<Response, tokio_tungstenite::tungstenite::handshake::server::ErrorResponse>
{
    if !matches!(request.uri().path(), "/sayin/v3" | "/sayin/v3/images")
        || request.uri().query().is_some()
        || request.headers().contains_key("origin")
    {
        let mut error =
            tokio_tungstenite::tungstenite::http::Response::new(Some("Forbidden".into()));
        *error.status_mut() = tokio_tungstenite::tungstenite::http::StatusCode::FORBIDDEN;
        return Err(error);
    }
    Ok(response)
}

async fn connection(
    shared: Arc<Shared>,
    mut stream: TcpStream,
    peer: SocketAddr,
    unauthenticated: tokio::sync::OwnedSemaphorePermit,
    mut stop: watch::Receiver<bool>,
) -> Result<()> {
    stream.set_nodelay(true)?;
    let mut first = [0u8; 1];
    timeout(Duration::from_secs(5), stream.peek(&mut first))
        .await
        .map_err(|_| Error::Protocol("HANDSHAKE_TIMEOUT"))??;
    if first[0] == b'V' {
        shared.attempt(peer.ip())?;
        let mut magic = [0; 6];
        bounded(async {
            stream.read_exact(&mut magic).await?;
            Ok(())
        })
        .await?;
        if &magic != MAGIC {
            return Err(Error::Protocol("INVALID_BOOTSTRAP"));
        }
        return timeout(Duration::from_secs(10), bootstrap(shared, &mut stream))
            .await
            .map_err(|_| Error::Protocol("HANDSHAKE_TIMEOUT"))?;
    }
    let acceptor = TlsAcceptor::from(shared.identity.tls.clone());
    let stream = timeout(Duration::from_secs(5), acceptor.accept(stream))
        .await
        .map_err(|_| Error::Protocol("HANDSHAKE_TIMEOUT"))??;
    let config = WebSocketConfig::default()
        .read_buffer_size(8192)
        .write_buffer_size(0)
        .max_write_buffer_size(65536)
        .max_message_size(Some(65536))
        .max_frame_size(Some(65536));
    let image_path = std::sync::atomic::AtomicBool::new(false);
    // Tungstenite's Callback contract requires an owned HTTP error response.
    #[allow(clippy::result_large_err)]
    let upgrade = |request: &Request, response| {
        image_path.store(
            request.uri().path() == "/sayin/v3/images",
            std::sync::atomic::Ordering::Relaxed,
        );
        validate_upgrade(request, response)
    };
    let mut socket = timeout(
        Duration::from_secs(5),
        accept_hdr_async_with_config(stream, upgrade, Some(config)),
    )
    .await
    .map_err(|_| Error::Protocol("HANDSHAKE_TIMEOUT"))??;
    shared.attempt(peer.ip())?;
    let first = timeout(Duration::from_secs(5), read_json(&mut socket))
        .await
        .map_err(|_| Error::Protocol("AUTH_TIMEOUT"))??;
    if image_path.load(std::sync::atomic::Ordering::Relaxed) {
        return image_channel::serve(
            &shared,
            &mut socket,
            first,
            &mut stop,
            peer.ip(),
            unauthenticated,
        )
        .await;
    }
    if first.get("v").and_then(Value::as_u64) != Some(3) {
        return send_handshake_error(&mut socket, "VERSION_MISMATCH").await;
    }
    if first.get("t").and_then(Value::as_str) == Some("pair") {
        match pair(&shared, &mut socket, first, peer.ip()).await {
            Ok(()) => shared.authenticated(peer.ip()),
            Err(Error::Protocol("CONNECTION_CLOSED") | Error::WebSocket(_)) => {}
            Err(error) => send_handshake_error(&mut socket, pairing_error_code(&error)).await?,
        }
        return Ok(());
    }
    if first.get("t").and_then(Value::as_str) != Some("hello") {
        return send_handshake_error(&mut socket, "UNSUPPORTED").await;
    }
    let device = field(&first, "pair_id")?.to_owned();
    let token = Zeroizing::new(decode(field(&first, "token")?)?);
    if token.len() != 32 {
        return send_handshake_error(&mut socket, "UNAUTHORIZED").await;
    }
    let name = field(&first, "name")?.to_owned();
    // Approval can race with reconnect after the phone loses the final frame.
    // Once a decision is taken, let that request settle before testing its token.
    let finishing_pairing = {
        let pairing = shared.pairing.lock().await;
        pairing.as_ref().and_then(|window| {
            window
                .request
                .as_ref()
                .filter(|request| request.id == device && request.decision.is_none())
                .map(|request| request.finished.subscribe())
        })
    };
    if let Some(mut finished) = finishing_pairing
        && timeout(Duration::from_secs(5), finished.changed())
            .await
            .is_err()
    {
        return send_handshake_error(&mut socket, "STORAGE_UNAVAILABLE").await;
    }
    let session = match shared
        .receiver
        .authenticate_named(device, token, name)
        .await
    {
        Ok(session) => session,
        Err(error) => {
            return send_handshake_error(&mut socket, pairing_error_code(&error.into())).await;
        }
    };
    shared.authenticated(peer.ip());
    drop(unauthenticated);
    let control_slot = shared
        .control_slots
        .clone()
        .try_acquire_owned()
        .map_err(|_| Error::Protocol("CONNECTION_LIMIT"))?;
    authenticated(&shared, &mut socket, session, control_slot, &mut stop).await
}
fn field<'a>(value: &'a Value, key: &str) -> Result<&'a str> {
    value
        .get(key)
        .and_then(Value::as_str)
        .ok_or(Error::Protocol("BAD_MESSAGE"))
}
async fn read_json(socket: &mut Socket) -> Result<Value> {
    loop {
        match socket.next().await {
            Some(Ok(Message::Text(text))) => return Ok(strict_json(text.as_bytes())?),
            Some(Ok(Message::Ping(_))) => {
                bounded(async {
                    socket.flush().await?;
                    Ok(())
                })
                .await?;
            }
            Some(Ok(Message::Pong(_))) => {}
            _ => return Err(Error::Protocol("CONNECTION_CLOSED")),
        }
    }
}
async fn send(socket: &mut Socket, value: &Value) -> Result<()> {
    let text = Zeroizing::new(serde_json::to_string(value)?);
    if text.len() > MAX_OUTBOUND_BYTES {
        return Err(Error::Protocol("OUTPUT_LIMIT"));
    }
    bounded(async {
        socket.send(Message::Text(text.to_string().into())).await?;
        Ok(())
    })
    .await
}
async fn send_handshake_error(socket: &mut Socket, code: &str) -> Result<()> {
    send(socket, &json!({"v":3,"t":"err","code":code})).await
}

fn pairing_error_code(error: &Error) -> &'static str {
    match error {
        Error::Protocol(code) | Error::Receiver(voicelink_receiver::Error::Protocol(code)) => {
            match *code {
                "PAIRING_BUSY"
                | "PAIR_REPLACE_BLOCKED"
                | "UNAUTHORIZED"
                | "STORAGE_UNAVAILABLE" => code,
                "PAIRING_EXPIRED" => "TOO_LATE",
                "PAIRING_CLOSED" | "PAIRING_REJECTED" => "UNAUTHORIZED",
                "DEVICE_LIMIT" => "QUEUE_FULL",
                _ => "INVALID_REQUEST",
            }
        }
        Error::Pairing(_) | Error::Json(_) => "INVALID_REQUEST",
        _ => "STORAGE_UNAVAILABLE",
    }
}

async fn pair(shared: &Shared, socket: &mut Socket, value: Value, source: IpAddr) -> Result<()> {
    let name = field(&value, "name")?.to_owned();
    if name.trim().is_empty() || name.chars().count() > 64 || name.chars().any(char::is_control) {
        return Err(Error::Protocol("INVALID_NAME"));
    }
    if value.get("session_id").is_some() {
        return Err(Error::Protocol("BAD_MESSAGE"));
    }
    let secret = Zeroizing::new(decode(field(&value, "secret")?)?);
    let replaces = match (value.get("replace_pair_id"), value.get("replace_token")) {
        (None, None) => None,
        (Some(_), Some(_)) => {
            let predecessor = field(&value, "replace_pair_id")?.to_owned();
            validate_uuid(&predecessor)?;
            let proof = Zeroizing::new(decode(field(&value, "replace_token")?)?);
            if proof.len() != 32 {
                return Err(Error::Protocol("UNAUTHORIZED"));
            }
            Some((predecessor, proof))
        }
        _ => return Err(Error::Protocol("BAD_MESSAGE")),
    };
    let id = Uuid::new_v4().to_string();
    let token = Zeroizing::new(random::<32>()?.to_vec());
    let (expires, mut decision) = {
        let mut pairing = shared.pairing.lock().await;
        let window = pairing.as_mut().ok_or(Error::Protocol("PAIRING_CLOSED"))?;
        // Compare the 128-bit grant without a content-dependent early return.
        if Instant::now() >= window.expires {
            return Err(Error::Protocol("PAIRING_EXPIRED"));
        }
        if !constant_time_equal(&secret, &decode(&window.invitation.secret)?) {
            return Err(Error::Protocol("PAIRING_REJECTED"));
        }
        if window.replaces.as_deref() != replaces.as_ref().map(|(pair, _)| pair.as_str()) {
            return Err(Error::Protocol("UNAUTHORIZED"));
        }
        if window.request.is_some() {
            return Err(Error::Protocol("PAIRING_BUSY"));
        }
        let (sender, receiver) = oneshot::channel();
        window.request = Some(PairingRequest {
            id: id.clone(),
            saved: false,
            decision: Some(sender),
            finished: watch::channel(()).0,
        });
        (tokio::time::Instant::from_std(window.expires), receiver)
    };

    let approved = async {
        if let Some((old_pair, proof)) = &replaces {
            shared
                .receiver
                .verify_pair_token(old_pair.clone(), proof.clone())
                .await?;
        }
        send(
            socket,
            &json!({"v":3,"t":"pair_candidate","host_id":shared.identity.host_id,
            "host_name":shared.computer_name,"pair_id":id,"token":encode(&token)}),
        )
        .await?;
        let saved = tokio::select! {
            _ = &mut decision => return Err(Error::Protocol("PAIRING_EXPIRED")),
            saved = tokio::time::timeout_at(expires, read_json(socket)) =>
                saved.map_err(|_| Error::Protocol("PAIRING_EXPIRED"))??,
        };
        if saved.get("v").and_then(Value::as_u64) != Some(3)
            || saved.get("t").and_then(Value::as_str) != Some("pair_saved")
            || field(&saved, "pair_id")? != id
            || saved.get("session_id").is_some()
        {
            return Err(Error::Protocol("BAD_MESSAGE"));
        }
        let mut pairing = shared.pairing.lock().await;
        let request =
            current_pairing_request(&mut pairing, &id).ok_or(Error::Protocol("PAIRING_EXPIRED"))?;
        request.saved = true;
        if let Ok(mut snapshot) = shared.snapshot.lock() {
            snapshot.approval = Some(PendingApproval {
                id: id.clone(),
                name: name.clone(),
                source,
                replaces: replaces.as_ref().map(|(pair, _)| pair.clone()),
            });
        }
        shared.notify();
        drop(pairing);
        tokio::select! {
            biased;
            incoming = read_json(socket) => {
                incoming?;
                Err(Error::Protocol("BAD_MESSAGE"))
            },
            result = tokio::time::timeout_at(expires, decision) => match result {
                Ok(Ok(true)) => Ok(()),
                Ok(Ok(false)) => Err(Error::Protocol("PAIRING_REJECTED")),
                _ => Err(Error::Protocol("PAIRING_EXPIRED")),
            },
        }
    }
    .await;

    let mut pairing = shared.pairing.lock().await;
    if let Err(error) = approved {
        if let Some(window) = pairing.as_mut().filter(|window| {
            window
                .request
                .as_ref()
                .is_some_and(|request| request.id == id)
        }) {
            window.request = None;
            if let Ok(mut snapshot) = shared.snapshot.lock() {
                snapshot.approval = None;
            }
            shared.notify();
        }
        return Err(error);
    }
    if current_pairing_request(&mut pairing, &id).is_none() {
        return Err(Error::Protocol("PAIRING_EXPIRED"));
    }
    // Keep ownership through the durable transaction so a recovery hello
    // observes its result. Consume the invitation even on a storage failure.
    let provisioned = match replaces {
        Some((old_pair, proof)) => {
            shared
                .receiver
                .replace_pair_by_token(old_pair, proof, id.clone(), token, name)
                .await
        }
        None => {
            shared
                .receiver
                .provision_device(id.clone(), token, name)
                .await
        }
    };
    *pairing = None;
    shared.clear_pairing_snapshot();
    drop(pairing);
    provisioned?;
    send(
        socket,
        &json!({"v":3,"t":"paired","host_id":shared.identity.host_id,"pair_id":id}),
    )
    .await
}

fn current_pairing_request<'a>(
    pairing: &'a mut Option<Pairing>,
    id: &str,
) -> Option<&'a mut PairingRequest> {
    pairing
        .as_mut()
        .filter(|window| Instant::now() < window.expires)?
        .request
        .as_mut()
        .filter(|request| request.id == id)
}
fn constant_time_equal(a: &[u8], b: &[u8]) -> bool {
    use subtle::ConstantTimeEq;
    bool::from(a.ct_eq(b))
}
struct Rate {
    per_second: f64,
    burst: f64,
    tokens: f64,
    updated: Instant,
}
impl Rate {
    fn new() -> Self {
        Self {
            per_second: 10.,
            burst: 10.,
            tokens: 10.,
            updated: Instant::now(),
        }
    }
    fn image_chunks() -> Self {
        Self {
            per_second: 4096.,
            burst: 256.,
            tokens: 256.,
            updated: Instant::now(),
        }
    }
    fn take(&mut self) -> Result<()> {
        self.tokens =
            (self.tokens + self.updated.elapsed().as_secs_f64() * self.per_second).min(self.burst);
        self.updated = Instant::now();
        if self.tokens < 1. {
            return Err(Error::Protocol("RATE_LIMITED"));
        }
        self.tokens -= 1.;
        Ok(())
    }
}
async fn authenticated(
    shared: &Shared,
    socket: &mut Socket,
    session: Session,
    _control_slot: tokio::sync::OwnedSemaphorePermit,
    stop: &mut watch::Receiver<bool>,
) -> Result<()> {
    let mut notifications = shared.receiver.subscribe().await?;
    let initial = shared.receiver.snapshot().await?;
    let mut control = initial.control.clone();
    let mut foreground = shared.foreground.subscribe();
    let mut display = foreground::Display::default();
    let image_binding = image_channel::ControlBinding::new(shared, &session)?;
    send(socket,&json!({"v":3,"t":"welcome","session_id":session.id(),"host_id":shared.identity.host_id,
        "host_name":shared.computer_name,"pair_id":session.device(),"state_rev":control.state_rev,
        "paused":control.paused,"pause_reason":control.pause_reason,"target":control.target,
        "image":image_binding.description(),
        "capabilities":if shared.receiver.image_store().is_some() {vec!["text","auto_text","key_enter","control","query","image"]} else {vec!["text","auto_text","key_enter","control","query"]},"limits":{"max_message_bytes":65536,"max_submit_bytes":32768,
            "max_text_scalars":4000,"max_text_bytes":16384,"pending_ops":16,"pending_bytes":65536,"foreground_ttl_ms":5000}})).await?;
    let _online = OnlineDevice::register(shared, session.device())?;
    let sample = foreground.borrow_and_update().clone();
    display
        .update(socket, &shared.receiver, &session, &sample, &control, true)
        .await?;
    let mut revision = control.state_rev.clone();
    let mut tracked = HashMap::<String, Receipt>::new();
    #[cfg(feature = "receipt-replay-fixture")]
    let mut old_receipt = None;
    let mut control_rate = Rate::new();
    let mut inbound_rate = Rate::new();
    let mut last_inbound = Instant::now();
    let mut first_application = true;
    let mut watchdog = tokio::time::interval(Duration::from_secs(2));
    loop {
        tokio::select! {
            biased;
            _=stop.changed()=>return Ok(()),
            incoming=read_json(socket)=>{
                let value=incoming?;last_inbound=Instant::now();
                inbound_rate.take()?;
                if let Err(error) = shared.receiver.check_session(session.clone()).await {
                    let error = Error::Receiver(error);
                    let _ = send_error(socket, &session, &value, &error).await;
                    return Err(error);
                }
                let sample = foreground.borrow().clone();
                let mut dispatch_state = DispatchState {
                    tracked: &mut tracked,
                    rate: &mut control_rate,
                    first_application: &mut first_application,
                    #[cfg(feature = "receipt-replay-fixture")]
                    old_receipt: &mut old_receipt,
                };
                let result=dispatch(shared,socket,&session,&value,(&display, &sample, &control), &mut dispatch_state).await;
                if let Err(error)=result {
                    send_error(socket, &session, &value, &error).await?;
                }
            },
            _=watchdog.tick()=>{
                shared.receiver.check_session(session.clone()).await?;
                let sample = foreground.borrow().clone();
                display.update(socket, &shared.receiver, &session, &sample, &control, false).await?;
                if last_inbound.elapsed()>Duration::from_secs(65){return Err(Error::Protocol("IDLE_TIMEOUT"));}
            },
            _=foreground.changed()=>{
                let sample = foreground.borrow_and_update().clone();
                display.update(socket, &shared.receiver, &session, &sample, &control, false).await?;
            },
            notification=notifications.next()=>{
                if notification.is_none(){return Err(Error::Protocol("SERVICE_UNAVAILABLE"));}
                shared.receiver.check_session(session.clone()).await?;
                control=shared.receiver.state().await?;
                if revision!=control.state_rev {revision=control.state_rev.clone();send_state(socket,&session,&control).await?;
                    let sample = foreground.borrow().clone();
                    display.update(socket, &shared.receiver, &session, &sample, &control, false).await?;
                }
                for id in tracked.keys().cloned().collect::<Vec<_>>() {
                    if let Some(receipt)=shared.receiver.query(session.clone(),id.clone()).await? {
                        if tracked.get(&id).is_none_or(|old| old.st!=receipt.st || old.reason!=receipt.reason) {
                            send_receipt(socket,&session,&receipt).await?;
                        }
                        if receipt.st.is_terminal(){tracked.remove(&id);} else {tracked.insert(id,receipt);}
                    }
                }
            },
        }
    }
}
async fn send_state(socket: &mut Socket, session: &Session, control: &ControlState) -> Result<()> {
    send(
        socket,
        &json!({"v":3,"t":"state","session_id":session.id(),"state_rev":control.state_rev,
        "paused":control.paused,"pause_reason":control.pause_reason,"target":control.target}),
    )
    .await
}
async fn send_receipt(socket: &mut Socket, session: &Session, receipt: &Receipt) -> Result<()> {
    let session_id = Uuid::parse_str(session.id()).map_err(|_| Error::Protocol("INVALID_ID"))?;
    let id = Uuid::parse_str(&receipt.id).map_err(|_| Error::Protocol("INVALID_ID"))?;
    let state = match receipt.st {
        voicelink_receiver::protocol::State::Accepted => StatusState::Accepted,
        voicelink_receiver::protocol::State::Held => StatusState::Held,
        voicelink_receiver::protocol::State::Executing => StatusState::Executing,
        voicelink_receiver::protocol::State::Injected => StatusState::Injected,
        voicelink_receiver::protocol::State::Failed => StatusState::Failed,
        voicelink_receiver::protocol::State::Cancelled => StatusState::Cancelled,
        voicelink_receiver::protocol::State::Expired => StatusState::Expired,
        voicelink_receiver::protocol::State::Uncertain => StatusState::Uncertain,
        voicelink_receiver::protocol::State::UncertainAcknowledged => {
            StatusState::UncertainAcknowledged
        }
    };
    let execution = receipt.execution_stage.as_ref().map(|stage| Execution {
        stage: match stage.as_str() {
            "prepared" => ExecutionStage::Prepared,
            "input_submitted" => ExecutionStage::InputSubmitted,
            "file_saved" => ExecutionStage::FileSaved,
            "paste_submitted" => ExecutionStage::PasteSubmitted,
            "paste_confirmed" => ExecutionStage::PasteConfirmed,
            _ => ExecutionStage::NotStarted,
        },
        method: match receipt.method.as_deref() {
            Some("unicode") => ExecutionMethod::Unicode,
            Some("key") => ExecutionMethod::Key,
            Some("clipboard_text") => ExecutionMethod::ClipboardText,
            Some("clipboard_image") => ExecutionMethod::ClipboardImage,
            Some("clipboard_image_path") => ExecutionMethod::ClipboardImagePath,
            Some("none") | None => ExecutionMethod::None,
            // Test and third-party adapters may use an internal method label.
            // Preserve the v3 contract by exposing the closest text-input
            // method while keeping the raw adapter label out of the wire.
            Some(_) => ExecutionMethod::Unicode,
        },
        confirmed: receipt.confirmed.unwrap_or(false),
    });
    let status = Status {
        session_id,
        id,
        accept_order: (receipt.reason.as_deref() != Some("cancelled_before_accept"))
            .then(|| receipt.order.parse().ok())
            .flatten(),
        request_rev: receipt
            .request_rev
            .as_deref()
            .unwrap_or("0")
            .parse()
            .unwrap_or(0),
        state,
        execution,
        reason: receipt.reason.clone(),
        server_ms: None,
        application: receipt.application.as_ref().map(|display| Application {
            name: display.name.clone(),
            icon_webp: display.icon_webp.as_ref().map(|icon| STANDARD.encode(icon)),
        }),
        uncertain_scope: receipt.uncertain_scope,
    };
    let encoded = StatusFrame::Receipt(status).encode()?;
    let frame = strict_json(&encoded)?;
    send(socket, &frame).await?;
    if receipt.st.is_terminal()
        && log::log_enabled!(target: "sayin_receipt_budget", log::Level::Trace)
    {
        log::trace!(target: "sayin_receipt_budget", "{} {} {}", receipt.id,
            encoded.len(), frame["application"]["icon_webp"].is_string());
    }
    #[cfg(windows)]
    if receipt.st.is_terminal()
        && log::log_enabled!(target: "sayin_receipt_sent", log::Level::Trace)
    {
        let mut ticks = 0;
        if unsafe { windows::Win32::System::Performance::QueryPerformanceCounter(&mut ticks) }
            .is_ok()
        {
            log::trace!(target: "sayin_receipt_sent", "{} {} {ticks}", receipt.id, receipt.st.key());
        }
    }
    Ok(())
}
async fn send_unknown(socket: &mut Socket, session: &Session, id: &str) -> Result<()> {
    let session_id = Uuid::parse_str(session.id()).map_err(|_| Error::Protocol("INVALID_ID"))?;
    let id = Uuid::parse_str(id).map_err(|_| Error::Protocol("INVALID_ID"))?;
    let encoded = StatusFrame::Unknown { session_id, id }.encode()?;
    send(socket, &strict_json(&encoded)?).await
}
async fn send_error(
    socket: &mut Socket,
    session: &Session,
    value: &Value,
    error: &Error,
) -> Result<()> {
    let code = match error {
        Error::Protocol(code) | Error::Receiver(voicelink_receiver::Error::Protocol(code)) => {
            match *code {
                "BAD_MESSAGE" | "INVALID_JSON" | "INVALID_SUBMIT" | "INVALID_ID" | "TEXT_LIMIT"
                | "MESSAGE_LIMIT" => "INVALID_REQUEST",
                "UNKNOWN_OPERATION" => "UNSUPPORTED",
                other => other,
            }
        }
        _ => "STORAGE_UNAVAILABLE",
    };
    let mut frame = json!({"v":3,"t":"err","code":code,"session_id":session.id()});
    if let Some(reference) = value.get("ref").or_else(|| value.get("id")) {
        frame["ref"] = reference.clone();
    }
    send(socket, &frame).await
}
struct DispatchState<'a> {
    tracked: &'a mut HashMap<String, Receipt>,
    rate: &'a mut Rate,
    first_application: &'a mut bool,
    #[cfg(feature = "receipt-replay-fixture")]
    old_receipt: &'a mut Option<Receipt>,
}

async fn dispatch(
    shared: &Shared,
    socket: &mut Socket,
    session: &Session,
    value: &Value,
    foreground: (&foreground::Display, &foreground::Sample, &ControlState),
    state: &mut DispatchState<'_>,
) -> Result<()> {
    if value.get("v").and_then(Value::as_u64) != Some(3)
        || field(value, "session_id")? != session.id()
    {
        return Err(Error::Protocol("STALE_SESSION"));
    }
    let kind = field(value, "t")?;
    if *state.first_application {
        *state.first_application = false;
        if kind != "query" {
            return Err(Error::Protocol("INVALID_REQUEST"));
        }
    }
    if kind == "submit" {
        let mut legacy = value.clone();
        legacy["v"] = json!(2);
        let submit = Submit::decode(&serde_json::to_vec(&legacy)?)?;
        let receipt = shared.receiver.submit(session.clone(), submit).await?;
        send_receipt(socket, session, &receipt).await?;
        #[cfg(feature = "receipt-replay-fixture")]
        if shared
            .replay_old_receipt
            .load(std::sync::atomic::Ordering::Relaxed)
            && value["op"]["kind"] == "text"
            && receipt.st == voicelink_receiver::protocol::State::Accepted
            && state.old_receipt.is_none()
        {
            *state.old_receipt = Some(receipt.clone());
        }
        if !receipt.st.is_terminal() {
            if state.tracked.len() >= 16 && !state.tracked.contains_key(&receipt.id) {
                return Err(Error::Protocol("TRACKING_LIMIT"));
            }
            state.tracked.insert(receipt.id.clone(), receipt);
        }
        return Ok(());
    }
    state.rate.take()?;
    if kind == "ping" {
        let nonce = field(value, "nonce")?;
        if nonce.len() > 64 {
            return Err(Error::Protocol("BAD_MESSAGE"));
        }
        return send(
            socket,
            &json!({"v":3,"t":"pong","session_id":session.id(),"nonce":nonce,"foreground_seq":foreground.0.confirmation(foreground.1,foreground.2,session)}),
        )
        .await;
    }
    match kind {
        "query" => {
            let reference = field(value, "ref")?;
            validate_uuid(reference)?;
            let refs = value
                .get("ids")
                .and_then(Value::as_array)
                .ok_or(Error::Protocol("BAD_MESSAGE"))?;
            if refs.len() > 64 {
                return Err(Error::Protocol("BAD_MESSAGE"));
            }
            for id in refs {
                let id = id.as_str().ok_or(Error::Protocol("BAD_MESSAGE"))?;
                validate_uuid(id)?;
                if let Some(receipt) = shared
                    .receiver
                    .query(session.clone(), id.to_owned())
                    .await?
                {
                    #[cfg(feature = "receipt-replay-fixture")]
                    if receipt.st.is_terminal()
                        && state
                            .old_receipt
                            .as_ref()
                            .is_some_and(|old| old.id == receipt.id)
                    {
                        let old = state.old_receipt.take().expect("matching fixture receipt");
                        send_receipt(socket, session, &old).await?;
                        eprintln!(
                            "old_receipt_replayed {} {} {}",
                            old.id,
                            old.request_rev.as_deref().unwrap_or("0"),
                            receipt.request_rev.as_deref().unwrap_or("0")
                        );
                        continue;
                    }
                    send_receipt(socket, session, &receipt).await?;
                    if !receipt.st.is_terminal() && state.tracked.len() < 16 {
                        state.tracked.insert(receipt.id.clone(), receipt);
                    }
                } else {
                    send_unknown(socket, session, id).await?;
                }
            }
            send(
                socket,
                &json!({"v":3,"t":"queried","session_id":session.id(),"ref":reference}),
            )
            .await
        }
        "control" => {
            let reference = field(value, "ref")?;
            validate_uuid(reference)?;
            let refs = value
                .get("refs")
                .and_then(Value::as_array)
                .ok_or(Error::Protocol("BAD_MESSAGE"))?;
            if refs.len() != 1 {
                return Err(Error::Protocol("ONE_OPERATION_REQUIRED"));
            }
            let id = refs[0]
                .as_str()
                .ok_or(Error::Protocol("BAD_MESSAGE"))?
                .to_owned();
            validate_uuid(&id)?;
            match field(value, "name")? {
                "cancel" | "release" => {
                    let receipt = if field(value, "name")? == "cancel" {
                        shared.receiver.cancel(session.clone(), id).await?
                    } else {
                        shared
                            .receiver
                            .release_checked(
                                session.clone(),
                                id,
                                field(value, "target")?.into(),
                                field(value, "expected_rev")?.into(),
                                Some(field(value, "expected_request_rev")?.into()),
                            )
                            .await?
                    };
                    send_receipt(socket, session, &receipt).await?;
                }
                _ => return Err(Error::Protocol("UNSUPPORTED")),
            }
            let control = shared.receiver.state().await?;
            send(
                socket,
                &json!({"v":3,"t":"controlled","session_id":session.id(),"ref":reference,"ok":true,
                "state_rev":control.state_rev,"paused":control.paused,"target":control.target,
                "pause_reason":control.pause_reason}),
            )
            .await
        }
        _ => Err(Error::Protocol("UNKNOWN_OPERATION")),
    }
}
