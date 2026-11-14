//! Service implementation (one supervised runtime, bounded per-connection work).
use crate::interfaces::NetworkBinding;
use crate::{Error, HostIdentity, Result};
use futures_channel::{mpsc as notifications, oneshot};
use futures_util::{SinkExt, StreamExt};
use qrcode::{QrCode, render::svg};
use serde_json::{Value, json};
use std::{
    collections::{HashMap, VecDeque},
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
    worker::Receiver,
};
use zeroize::Zeroizing;

#[cfg(test)]
mod tests;

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
}
#[derive(Clone)]
#[non_exhaustive]
pub struct NetworkSnapshot {
    pub address: SocketAddr,
    pub window: Option<PairingWindow>,
    pub approval: Option<PendingApproval>,
    pub connections: usize,
    pub running: bool,
}
struct Pairing {
    info: BootstrapInfo,
    code: Zeroizing<String>,
    invitation: Invitation,
    expires: Instant,
    attempts: u8,
    approval: Option<(String, oneshot::Sender<bool>)>,
}
struct Shared {
    receiver: Receiver,
    identity: HostIdentity,
    address: SocketAddr,
    pairing: AsyncMutex<Option<Pairing>>,
    snapshot: Mutex<NetworkSnapshot>,
    observers: Mutex<Vec<notifications::Sender<()>>>,
    failures: Mutex<VecDeque<(IpAddr, Instant, u8)>>,
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
            receiver,
            identity,
            address,
            pairing: AsyncMutex::new(None),
            snapshot: Mutex::new(NetworkSnapshot {
                address,
                window: None,
                approval: None,
                connections: 0,
                running: true,
            }),
            observers: Mutex::new(Vec::new()),
            failures: Mutex::new(VecDeque::new()),
        });
        let (stop, mut stopping) = watch::channel(false);
        let (done, completion) = oneshot::channel();
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?;
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
                let unauthenticated=Arc::new(Semaphore::new(8));
                let mut connections=JoinSet::new();
                let monitor=tokio::spawn(monitor_profile(state.clone(),binding,policy_stop.clone(),stopping.clone(),crate::interfaces::available_interfaces));
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
                            let mut window=state.pairing.lock().await;
                            if window.as_ref().is_some_and(|window|Instant::now()>=window.expires) {
                                *window=None;state.clear_pairing_snapshot();
                            }
                        },
                        _=connections.join_next(),if !connections.is_empty()=>{},
                    }
                }
                let _=policy_stop.send(true);
                monitor.abort();
                // Persist suspension before publishing the stopped listener. This
                // also covers accept failures and a cancelled profile monitor.
                let _=state.receiver.network_lost().await;
                *state.pairing.lock().await=None;
                state.clear_pairing_snapshot();
                connections.abort_all();
                while connections.join_next().await.is_some() {}
                if let Ok(mut snapshot)=state.snapshot.lock(){snapshot.running=false;snapshot.connections=0;}
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
        if *self.handle.stop.borrow() {
            return Err(Error::Protocol("SERVICE_UNAVAILABLE"));
        }
        let shared = &self.handle.shared;
        let mut pairing = shared.pairing.lock().await;
        let info = BootstrapInfo::new(shared.identity.host_id.clone(), Uuid::new_v4().to_string());
        let code = voicelink_pairing::pairing_code()?;
        let secret = Zeroizing::new(random::<16>()?);
        let invitation = Invitation::new(
            &info,
            shared.address.ip(),
            shared.address.port(),
            &shared.identity.cert,
            &*secret,
        )?;
        let qr = invitation.qr()?;
        let qr_code = QrCode::with_error_correction_level(qr.as_bytes(), qrcode::EcLevel::M)
            .map_err(|_| Error::Protocol("QR_CAPACITY"))?;
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
        let expires = Instant::now() + Duration::from_secs(120);
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
            approval: None,
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
        let pending = window
            .approval
            .as_ref()
            .ok_or(Error::Protocol("PAIRING_EXPIRED"))?;
        if pending.0 != id {
            return Err(Error::Protocol("PAIRING_EXPIRED"));
        }
        let (_, decision) = window
            .approval
            .take()
            .ok_or(Error::Protocol("PAIRING_EXPIRED"))?;
        let _ = decision.send(approved);
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
    let plaintext = Zeroizing::new(serde_json::to_vec(&invitation)?);
    let envelope = key.seal(&info, &plaintext)?;
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
    if request.uri().path() != "/v2"
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
    let mut socket = timeout(
        Duration::from_secs(5),
        accept_hdr_async_with_config(stream, validate_upgrade, Some(config)),
    )
    .await
    .map_err(|_| Error::Protocol("HANDSHAKE_TIMEOUT"))??;
    shared.attempt(peer.ip())?;
    let first = timeout(Duration::from_secs(5), read_json(&mut socket))
        .await
        .map_err(|_| Error::Protocol("AUTH_TIMEOUT"))??;
    if first.get("v").and_then(Value::as_u64) != Some(2) {
        return Err(Error::Protocol("VERSION_MISMATCH"));
    }
    if first.get("t").and_then(Value::as_str) == Some("pair") {
        let result = pair(&shared, &mut socket, first).await;
        if result.is_ok() {
            shared.authenticated(peer.ip());
        }
        return result;
    }
    if first.get("t").and_then(Value::as_str) != Some("hello") {
        return Err(Error::Protocol("AUTH_REQUIRED"));
    }
    let device = field(&first, "device_id")?.to_owned();
    let token = Zeroizing::new(decode(field(&first, "token")?)?);
    if token.len() != 32 {
        return Err(Error::Protocol("UNAUTHORIZED"));
    }
    let name = field(&first, "name")?.to_owned();
    let session = shared
        .receiver
        .authenticate_named(device, token, name)
        .await?;
    shared.authenticated(peer.ip());
    drop(unauthenticated);
    {
        if let Ok(mut snapshot) = shared.snapshot.lock() {
            snapshot.connections += 1;
        }
    }
    shared.notify();
    let result = authenticated(&shared, &mut socket, session, &mut stop).await;
    {
        if let Ok(mut snapshot) = shared.snapshot.lock() {
            snapshot.connections = snapshot.connections.saturating_sub(1);
        }
    }
    shared.notify();
    result
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
    if text.len() > 8192 {
        return Err(Error::Protocol("OUTPUT_LIMIT"));
    }
    bounded(async {
        socket.send(Message::Text(text.to_string().into())).await?;
        Ok(())
    })
    .await
}
async fn pair(shared: &Shared, socket: &mut Socket, value: Value) -> Result<()> {
    let name = field(&value, "name")?.to_owned();
    if name.is_empty() || name.chars().count() > 64 || name.chars().any(char::is_control) {
        return Err(Error::Protocol("INVALID_NAME"));
    }
    let secret = Zeroizing::new(decode(field(&value, "secret")?)?);
    let window_id = field(&value, "window_id")?.to_owned();
    let id = Uuid::new_v4().to_string();
    let decision = {
        let mut pairing = shared.pairing.lock().await;
        let window = pairing.as_mut().ok_or(Error::Protocol("PAIRING_CLOSED"))?;
        // Compare the 128-bit grant without a content-dependent early return.
        if Instant::now() >= window.expires
            || window.info.window_id != window_id
            || !constant_time_equal(&secret, &decode(&window.invitation.secret)?)
            || window.approval.is_some()
        {
            return Err(Error::Protocol("PAIRING_REJECTED"));
        }
        let (sender, receiver) = oneshot::channel();
        window.approval = Some((id.clone(), sender));
        if let Ok(mut snapshot) = shared.snapshot.lock() {
            snapshot.approval = Some(PendingApproval {
                id: id.clone(),
                name: name.clone(),
            });
        }
        shared.notify();
        receiver
    };
    let approved = tokio::select! {
        result=timeout(Duration::from_secs(120), decision)=>result.ok().and_then(std::result::Result::ok).unwrap_or(false),
        // No post-request application message is valid until approval. Detect
        // cancellation/close here so the desktop never approves a lost request.
        _=socket.next()=>false,
    };
    if !approved {
        let mut window = shared.pairing.lock().await;
        if let Some(window) = window.as_mut().filter(|window| {
            window
                .approval
                .as_ref()
                .is_some_and(|pending| pending.0 == id)
        }) {
            window.approval = None;
            if let Ok(mut snapshot) = shared.snapshot.lock() {
                snapshot.approval = None;
            }
            shared.notify();
        }
        return Err(Error::Protocol("PAIRING_REJECTED"));
    }
    let mut pairing = shared.pairing.lock().await;
    if !pairing
        .as_ref()
        .is_some_and(|window| window.info.window_id == window_id && Instant::now() < window.expires)
    {
        return Err(Error::Protocol("PAIRING_EXPIRED"));
    }
    // Consume before awaiting persistence. Failure requires reopening pairing;
    // a crash also loses the entire short-lived grant, so it cannot be replayed.
    *pairing = None;
    shared.clear_pairing_snapshot();
    let device = Uuid::new_v4().to_string();
    let token = Zeroizing::new(random::<32>()?);
    shared
        .receiver
        .provision_device(device.clone(), Zeroizing::new(token.to_vec()), name)
        .await?;
    drop(pairing);
    send(socket,&json!({"v":2,"t":"paired","host_id":shared.identity.host_id,"device_id":device,"token":encode(&*token)})).await
}
fn constant_time_equal(a: &[u8], b: &[u8]) -> bool {
    use subtle::ConstantTimeEq;
    bool::from(a.ct_eq(b))
}
struct Rate {
    tokens: f64,
    updated: Instant,
}
impl Rate {
    fn new() -> Self {
        Self {
            tokens: 10.,
            updated: Instant::now(),
        }
    }
    fn take(&mut self) -> Result<()> {
        self.tokens = (self.tokens + self.updated.elapsed().as_secs_f64() * 10.).min(10.);
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
    stop: &mut watch::Receiver<bool>,
) -> Result<()> {
    let mut notifications = shared.receiver.subscribe().await?;
    let initial = shared.receiver.snapshot().await?;
    let control = initial.control.clone();
    send(socket,&json!({"v":2,"t":"welcome","session_id":session.id(),"host_id":shared.identity.host_id,
        "device_id":session.device(),"active_device_id":control.active_device_id,"state_rev":control.state_rev,
        "paused":control.paused,"pause_reason":control.pause_reason,"target":control.target,
        "capabilities":["text","key_enter","control","query"],"limits":{"max_message_bytes":65536,"max_submit_bytes":32768,
            "max_text_scalars":4000,"max_text_bytes":16384,"pending_ops":16,"pending_bytes":65536}})).await?;
    let mut revision = control.state_rev;
    let mut tracked = HashMap::<String, Receipt>::new();
    let mut control_rate = Rate::new();
    let mut inbound_rate = Rate::new();
    let mut last_inbound = Instant::now();
    let mut watchdog = tokio::time::interval(Duration::from_secs(2));
    loop {
        tokio::select! {
            biased;
            _=stop.changed()=>return Ok(()),
            incoming=read_json(socket)=>{
                let value=incoming?;last_inbound=Instant::now();
                inbound_rate.take()?;
                shared.receiver.check_session(session.clone()).await?;
                let result=dispatch(shared,socket,&session,&value,&mut tracked,&mut control_rate).await;
                if let Err(error)=result {
                    let code=match error {Error::Protocol(code)=>code,Error::Receiver(voicelink_receiver::Error::Protocol(code))=>code,_=>"SERVICE_UNAVAILABLE"};
                    send(socket,&json!({"v":2,"t":"err","session_id":session.id(),"code":code,"ref":value.get("id")})).await?;
                }
            },
            _=watchdog.tick()=>{
                shared.receiver.check_session(session.clone()).await?;
                if last_inbound.elapsed()>Duration::from_secs(65){return Err(Error::Protocol("IDLE_TIMEOUT"));}
            },
            notification=notifications.next()=>{
                if notification.is_none(){return Err(Error::Protocol("SERVICE_UNAVAILABLE"));}
                shared.receiver.check_session(session.clone()).await?;
                let control=shared.receiver.state().await?;
                if revision!=control.state_rev {revision=control.state_rev.clone();send_state(socket,&session,&control).await?;}
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
    send(socket,&json!({"v":2,"t":"state","session_id":session.id(),"state_rev":control.state_rev,
        "paused":control.paused,"pause_reason":control.pause_reason,"active_device_id":control.active_device_id,"target":control.target})).await
}
async fn send_receipt(socket: &mut Socket, session: &Session, receipt: &Receipt) -> Result<()> {
    let mut value = serde_json::to_value(receipt)?;
    value["v"] = json!(2);
    value["t"] = json!("status");
    value["session_id"] = json!(session.id());
    send(socket, &value).await
}
async fn dispatch(
    shared: &Shared,
    socket: &mut Socket,
    session: &Session,
    value: &Value,
    tracked: &mut HashMap<String, Receipt>,
    rate: &mut Rate,
) -> Result<()> {
    if value.get("v").and_then(Value::as_u64) != Some(2)
        || field(value, "session_id")? != session.id()
    {
        return Err(Error::Protocol("STALE_SESSION"));
    }
    let kind = field(value, "t")?;
    if kind == "submit" {
        let submit = Submit::decode(&serde_json::to_vec(value)?)?;
        let receipt = shared.receiver.submit(session.clone(), submit).await?;
        send_receipt(socket, session, &receipt).await?;
        if !receipt.st.is_terminal() {
            if tracked.len() >= 16 && !tracked.contains_key(&receipt.id) {
                return Err(Error::Protocol("TRACKING_LIMIT"));
            }
            tracked.insert(receipt.id.clone(), receipt);
        }
        return Ok(());
    }
    rate.take()?;
    if kind == "ping" {
        let nonce = field(value, "nonce")?;
        if nonce.len() > 64 {
            return Err(Error::Protocol("BAD_MESSAGE"));
        }
        return send(
            socket,
            &json!({"v":2,"t":"pong","session_id":session.id(),"nonce":nonce}),
        )
        .await;
    }
    let id = field(value, "id")?;
    validate_uuid(id)?;
    match kind {
        "query" => {
            let refs = value
                .get("refs")
                .and_then(Value::as_array)
                .ok_or(Error::Protocol("BAD_MESSAGE"))?;
            if refs.len() > 32 {
                return Err(Error::Protocol("BAD_MESSAGE"));
            }
            for reference in refs {
                let reference = reference.as_str().ok_or(Error::Protocol("BAD_MESSAGE"))?;
                if let Some(receipt) = shared
                    .receiver
                    .query(session.clone(), reference.to_owned())
                    .await?
                {
                    send_receipt(socket, session, &receipt).await?;
                    if !receipt.st.is_terminal() && tracked.len() < 16 {
                        tracked.insert(receipt.id.clone(), receipt);
                    }
                } else {
                    send(socket,&json!({"v":2,"t":"status","session_id":session.id(),"id":reference,"st":"unknown"})).await?;
                }
            }
            send(
                socket,
                &json!({"v":2,"t":"queried","session_id":session.id(),"ref":id}),
            )
            .await
        }
        "control" => {
            match field(value, "name")? {
                "pause" => {
                    shared.receiver.pause().await?;
                }
                "resume" => {
                    shared
                        .receiver
                        .resume(field(value, "expected_rev")?.into(), false)
                        .await?;
                }
                "cancel" | "release" => {
                    let refs = value
                        .get("refs")
                        .and_then(Value::as_array)
                        .ok_or(Error::Protocol("BAD_MESSAGE"))?;
                    if refs.len() != 1 {
                        return Err(Error::Protocol("ONE_OPERATION_REQUIRED"));
                    }
                    let reference = refs[0]
                        .as_str()
                        .ok_or(Error::Protocol("BAD_MESSAGE"))?
                        .to_owned();
                    let receipt = if field(value, "name")? == "cancel" {
                        shared.receiver.cancel(session.clone(), reference).await?
                    } else {
                        shared
                            .receiver
                            .release(
                                session.clone(),
                                reference,
                                field(value, "target")?.into(),
                                field(value, "expected_rev")?.into(),
                            )
                            .await?
                    };
                    send_receipt(socket, session, &receipt).await?;
                }
                _ => return Err(Error::Protocol("UNKNOWN_CONTROL")),
            }
            let control = shared.receiver.state().await?;
            send(
                socket,
                &json!({"v":2,"t":"controlled","session_id":session.id(),"ref":id,"ok":true,
                "state_rev":control.state_rev,"paused":control.paused,"target":control.target,
                "active_device_id":control.active_device_id,"pause_reason":control.pause_reason}),
            )
            .await
        }
        _ => Err(Error::Protocol("UNKNOWN_OPERATION")),
    }
}
