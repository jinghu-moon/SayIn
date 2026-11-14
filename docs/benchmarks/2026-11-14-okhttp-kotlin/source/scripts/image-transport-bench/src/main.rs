//! Isolated LAN experiment, not the SayIn wire protocol or input executor.
mod okhttp_bench;
use anyhow::{Context, Result, bail, ensure};
use futures_util::{SinkExt, StreamExt};
use rustls::pki_types::{CertificateDer, PrivatePkcs8KeyDer, ServerName};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::{self, File},
    io::{Read, Seek, SeekFrom, Write},
    net::{IpAddr, SocketAddr},
    path::Path,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{TcpListener, TcpSocket},
    sync::mpsc,
    task::JoinHandle,
    time::{interval, timeout},
};
use tokio_tungstenite::{
    WebSocketStream,
    tungstenite::{Message, protocol::WebSocketConfig},
};

const CHUNK: usize = 48 * 1024;
const MAX_FRAME: usize = CHUNK + 32;
const NAME: &str = "bench.sayin.local";
const PING: u8 = 3;
const START: u8 = 4;
const DATA: u8 = 5;
const END: u8 = 6;
const DONE: u8 = 8;
const READY: u8 = 9;
const BUDGET: Duration = Duration::from_secs(90);

#[derive(Clone, Serialize, Deserialize)]
struct Part {
    offset: u64,
    len: u64,
    sha256: String,
}
#[derive(Clone, Serialize, Deserialize)]
struct Sample {
    name: String,
    bytes: u64,
    sha256: String,
    parts: Vec<Vec<Part>>,
}
#[derive(Clone, Serialize, Deserialize)]
struct Config {
    tcp: SocketAddr,
    quic: SocketAddr,
    cert: Vec<u8>,
    token: Vec<u8>,
    samples: Vec<Sample>,
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn plan(size: u64, count: usize) -> Vec<(u64, u64)> {
    (0..count)
        .map(|i| {
            let a = size * i as u64 / count as u64;
            let b = size * (i + 1) as u64 / count as u64;
            (a, b - a)
        })
        .collect()
}
fn digest_part(path: &Path, offset: u64, len: u64) -> Result<String> {
    let mut f = File::open(path)?;
    f.seek(SeekFrom::Start(offset))?;
    let mut left = len;
    let mut buf = vec![0; CHUNK];
    let mut h = Sha256::new();
    while left > 0 {
        let n = (left as usize).min(CHUNK);
        f.read_exact(&mut buf[..n])?;
        h.update(&buf[..n]);
        left -= n as u64;
    }
    Ok(hex(&h.finalize()))
}
fn manifest(dir: &Path) -> Result<Vec<Sample>> {
    let mut paths = fs::read_dir(dir)?
        .map(|e| e.map(|x| x.path()))
        .collect::<std::io::Result<Vec<_>>>()?;
    paths.retain(|p| p.extension().is_some_and(|e| e.eq_ignore_ascii_case("jpg")));
    paths.sort();
    ensure!(
        !paths.is_empty() && paths.len() <= 32,
        "need 1..32 JPEG samples"
    );
    paths
        .iter()
        .map(|p| {
            let bytes = fs::metadata(p)?.len();
            ensure!((1..=128 * 1024 * 1024).contains(&bytes), "sample size");
            let parts = [1, 2, 4]
                .into_iter()
                .map(|n| {
                    plan(bytes, n)
                        .into_iter()
                        .map(|(offset, len)| {
                            Ok(Part {
                                offset,
                                len,
                                sha256: digest_part(p, offset, len)?,
                            })
                        })
                        .collect::<Result<Vec<_>>>()
                })
                .collect::<Result<Vec<_>>>()?;
            Ok(Sample {
                name: p
                    .file_name()
                    .unwrap()
                    .to_str()
                    .context("UTF8 filename")?
                    .into(),
                bytes,
                sha256: digest_part(p, 0, bytes)?,
                parts,
            })
        })
        .collect()
}
fn tls_server(cert: Vec<u8>, key: Vec<u8>) -> Result<rustls::ServerConfig> {
    let mut c = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS13])?
    .with_no_client_auth()
    .with_single_cert(vec![cert.into()], PrivatePkcs8KeyDer::from(key).into())?;
    c.max_early_data_size = 0;
    Ok(c)
}
fn tls_client(cert: Vec<u8>) -> Result<rustls::ClientConfig> {
    let mut roots = rustls::RootCertStore::empty();
    roots.add(cert.into())?;
    Ok(rustls::ClientConfig::builder_with_provider(Arc::new(
        rustls::crypto::ring::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS13])?
    .with_root_certificates(roots)
    .with_no_client_auth())
}
fn transport() -> Arc<quinn::TransportConfig> {
    let mut t = quinn::TransportConfig::default();
    t.stream_receive_window((2u32 * 1024 * 1024).into())
        .receive_window((8u32 * 1024 * 1024).into())
        .send_window(8 * 1024 * 1024)
        .max_concurrent_bidi_streams(8u32.into())
        .max_concurrent_uni_streams(0u32.into());
    t.max_idle_timeout(Some(Duration::from_secs(15).try_into().unwrap()));
    Arc::new(t)
}
trait Io: AsyncRead + AsyncWrite + Unpin + Send {}
impl<T: AsyncRead + AsyncWrite + Unpin + Send> Io for T {}
type AnyIo = Box<dyn Io>;
#[derive(Clone)]
struct Tx {
    high: mpsc::Sender<Vec<u8>>,
    low: mpsc::Sender<Vec<u8>>,
}
struct Channel {
    tx: Tx,
    tasks: Vec<JoinHandle<()>>,
}
impl Drop for Channel {
    fn drop(&mut self) {
        for h in &self.tasks {
            h.abort();
        }
    }
}
fn queues() -> (Tx, mpsc::Receiver<Vec<u8>>, mpsc::Receiver<Vec<u8>>) {
    let (hi, hr) = mpsc::channel(32);
    let (lo, lr) = mpsc::channel(2);
    (Tx { high: hi, low: lo }, hr, lr)
}
fn raw(io: AnyIo, events: mpsc::Sender<Vec<u8>>) -> Channel {
    let (tx, mut hi, mut lo) = queues();
    let (mut r, mut w) = tokio::io::split(io);
    let reader = tokio::spawn(async move {
        while let Ok(n) = r.read_u32().await {
            if n == 0 || n as usize > MAX_FRAME {
                break;
            }
            let mut b = vec![0; n as usize];
            if r.read_exact(&mut b).await.is_err() || events.send(b).await.is_err() {
                break;
            }
        }
    });
    let writer = tokio::spawn(async move {
        loop {
            let b = tokio::select! {biased;Some(b)=hi.recv()=>b,Some(b)=lo.recv()=>b,else=>break};
            if w.write_u32(b.len() as u32).await.is_err()
                || w.write_all(&b).await.is_err()
                || w.flush().await.is_err()
            {
                break;
            }
        }
    });
    Channel {
        tx,
        tasks: vec![reader, writer],
    }
}
fn ws(io: WebSocketStream<AnyIo>, events: mpsc::Sender<Vec<u8>>) -> Channel {
    let (tx, mut hi, mut lo) = queues();
    let (mut w, mut r) = io.split();
    let reader = tokio::spawn(async move {
        while let Some(Ok(m)) = r.next().await {
            match m {
                Message::Binary(b) if b.len() <= MAX_FRAME => {
                    if events.send(b.to_vec()).await.is_err() {
                        break;
                    }
                }
                Message::Close(_) => break,
                _ => break,
            }
        }
    });
    let writer = tokio::spawn(async move {
        loop {
            let b = tokio::select! {biased;Some(b)=hi.recv()=>b,Some(b)=lo.recv()=>b,else=>break};
            if w.send(Message::Binary(b.into())).await.is_err() {
                break;
            }
        }
    });
    Channel {
        tx,
        tasks: vec![reader, writer],
    }
}
fn ws_config() -> WebSocketConfig {
    WebSocketConfig::default()
        .max_message_size(Some(MAX_FRAME))
        .max_frame_size(Some(MAX_FRAME))
        .write_buffer_size(0)
}
async fn client_auth(io: &mut AnyIo, c: &Config, mode: u8, role: u8, session: u64) -> Result<()> {
    io.write_u8(mode).await?;
    io.write_all(&c.token).await?;
    io.write_u8(role).await?;
    io.write_u64(session).await?;
    io.flush().await?;
    ensure!(io.read_u8().await? == 1, "test authorization rejected");
    Ok(())
}
async fn server_auth(io: &mut AnyIo, c: &Config) -> Result<(u8, u8, u64)> {
    let mode = io.read_u8().await?;
    ensure!(mode <= 1, "mode");
    let mut token = vec![0; 32];
    io.read_exact(&mut token).await?;
    ensure!(token == c.token, "test authorization rejected");
    let role = io.read_u8().await?;
    ensure!(role <= 1, "role");
    let session = io.read_u64().await?;
    io.write_u8(1).await?;
    io.flush().await?;
    Ok((mode, role, session))
}
struct Receiving {
    got: u64,
    hash: Sha256,
    expected: Part,
    ended: bool,
}
struct Job {
    parts: Vec<Receiving>,
}
#[derive(Default)]
struct Session {
    control: Option<Tx>,
    jobs: HashMap<u32, Job>,
}
type Sessions = Arc<Mutex<HashMap<u64, Session>>>;
fn u32at(b: &[u8], i: usize) -> Result<u32> {
    Ok(u32::from_be_bytes(
        b.get(i..i + 4).context("short frame")?.try_into()?,
    ))
}
fn server_message(c: &Config, s: &mut Session, role: u8, b: &[u8]) -> Result<Option<Vec<u8>>> {
    match b.first().copied().context("empty frame")? {
        PING => {
            ensure!(role == 0 && b.len() == 13, "ping");
            Ok(Some(b.to_vec()))
        }
        START => {
            ensure!(role == 0 && b.len() == 10 && s.jobs.is_empty(), "start");
            let id = u32at(b, 1)?;
            let fi = u32at(b, 5)? as usize;
            let n = b[9] as usize;
            let pi = match n {
                1 => 0,
                2 => 1,
                4 => 2,
                _ => bail!("parallel count"),
            };
            let sample = c.samples.get(fi).context("sample index")?;
            let parts = sample.parts[pi]
                .iter()
                .map(|p| Receiving {
                    got: 0,
                    hash: Sha256::new(),
                    expected: p.clone(),
                    ended: false,
                })
                .collect();
            s.jobs.insert(id, Job { parts });
            let mut ack = vec![READY];
            ack.extend(id.to_be_bytes());
            Ok(Some(ack))
        }
        DATA | END => {
            ensure!(b.len() >= 6, "data header");
            let id = u32at(b, 1)?;
            let job = s.jobs.get_mut(&id).context("unknown job")?;
            let p = job.parts.get_mut(b[5] as usize).context("part index")?;
            ensure!(!p.ended, "ended part");
            if b[0] == DATA {
                ensure!(b.len() > 6 && b.len() <= CHUNK + 6, "chunk length");
                p.got = p
                    .got
                    .checked_add((b.len() - 6) as u64)
                    .context("overflow")?;
                ensure!(p.got <= p.expected.len, "excess bytes");
                p.hash.update(&b[6..]);
                Ok(None)
            } else {
                ensure!(b.len() == 6 && p.got == p.expected.len, "short part");
                ensure!(
                    hex(&p.hash.clone().finalize()) == p.expected.sha256,
                    "SHA256 mismatch"
                );
                p.ended = true;
                if job.parts.iter().all(|p| p.ended) {
                    s.jobs.remove(&id);
                    let mut ack = vec![DONE];
                    ack.extend(id.to_be_bytes());
                    Ok(Some(ack))
                } else {
                    Ok(None)
                }
            }
        }
        _ => bail!("unknown frame"),
    }
}
async fn serve_channel(io: AnyIo, c: Arc<Config>, sessions: Sessions) -> Result<()> {
    let mut io = io;
    let (mode, role, id) = timeout(Duration::from_secs(5), server_auth(&mut io, &c)).await??;
    let (et, mut er) = mpsc::channel(16);
    let channel = if mode == 1 {
        ws(
            timeout(
                Duration::from_secs(5),
                tokio_tungstenite::accept_async_with_config(io, Some(ws_config())),
            )
            .await??,
            et,
        )
    } else {
        raw(io, et)
    };
    {
        let mut all = sessions.lock().unwrap();
        ensure!(all.len() < 128 || all.contains_key(&id), "session limit");
        let s = all.entry(id).or_default();
        if role == 0 {
            ensure!(s.control.is_none(), "duplicate control");
            s.control = Some(channel.tx.clone());
        }
    }
    while let Ok(Some(b)) = timeout(Duration::from_secs(20), er.recv()).await {
        let (response, tx) = {
            let mut all = sessions.lock().unwrap();
            let s = all.get_mut(&id).context("session missing")?;
            (server_message(&c, s, role, &b)?, s.control.clone())
        };
        if let Some(b) = response {
            tx.context("no control")?
                .high
                .send(b)
                .await
                .context("control closed")?;
        }
    }
    Ok(())
}
async fn server(ip: IpAddr, images: &Path, run: &Path) -> Result<()> {
    ensure!(ip.is_ipv4(), "IPv4 required");
    fs::create_dir_all(run)?;
    let key = rcgen::KeyPair::generate()?;
    let cert = rcgen::CertificateParams::new(vec![NAME.into()])?.self_signed(&key)?;
    let der = cert.der().to_vec();
    let tls =
        tokio_rustls::TlsAcceptor::from(Arc::new(tls_server(der.clone(), key.serialize_der())?));
    let tcp = TcpListener::bind(SocketAddr::new(ip, 0)).await?;
    let mut qc = quinn::ServerConfig::with_crypto(Arc::new(
        quinn::crypto::rustls::QuicServerConfig::try_from(tls_server(
            der.clone(),
            key.serialize_der(),
        )?)?,
    ));
    qc.transport_config(transport());
    let quic = quinn::Endpoint::server(qc, SocketAddr::new(ip, 0))?;
    let mut token = vec![0; 32];
    getrandom::fill(&mut token).map_err(|e| anyhow::anyhow!("random: {e}"))?;
    let c = Arc::new(Config {
        tcp: tcp.local_addr()?,
        quic: quic.local_addr()?,
        cert: der,
        token,
        samples: manifest(images)?,
    });
    fs::write(run.join("config.json"), serde_json::to_vec_pretty(&*c)?)?;
    println!(
        "READY tcp={} quic={} samples={}",
        c.tcp,
        c.quic,
        c.samples.len()
    );
    std::io::stdout().flush()?;
    let sessions: Sessions = Arc::new(Mutex::new(HashMap::new()));
    let deadline = tokio::time::sleep(Duration::from_secs(1800));
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            Ok((s,_))=tcp.accept()=>{s.set_nodelay(true)?;let tls=tls.clone();let c=c.clone();let all=sessions.clone();tokio::spawn(async move{let r=async{let io=timeout(Duration::from_secs(5),tls.accept(s)).await??;serve_channel(Box::new(io),c,all).await}.await;if let Err(e)=r{eprintln!("TLS channel: {e:#}")}});},
            Some(incoming)=quic.accept()=>{let c=c.clone();let all=sessions.clone();tokio::spawn(async move {if let Ok(Ok(conn))=timeout(Duration::from_secs(5),incoming).await {while let Ok((send,recv))=conn.accept_bi().await {let c=c.clone();let all=all.clone();tokio::spawn(async move {if let Err(e)=serve_channel(Box::new(tokio::io::join(recv,send)),c,all).await{eprintln!("QUIC channel: {e:#}")}});}}});},
            _=tokio::time::sleep(Duration::from_millis(200))=>{if run.join("stop").exists(){break}},
            _=&mut deadline=>break,
        }
    }
    quic.close(0u32.into(), b"test complete");
    Ok(())
}
struct Link {
    channels: Vec<Channel>,
    quic: Option<quinn::Connection>,
    endpoint: Option<quinn::Endpoint>,
    events: mpsc::Receiver<Vec<u8>>,
    setup_ms: f64,
}
impl Drop for Link {
    fn drop(&mut self) {
        if let Some(q) = &self.quic {
            q.close(0u32.into(), b"case complete")
        }
        if let Some(e) = &self.endpoint {
            e.close(0u32.into(), b"case complete")
        }
    }
}
async fn connect(c: &Config, bind: IpAddr, kind: &str, n: usize, session: u64) -> Result<Link> {
    let start = Instant::now();
    let (et, er) = mpsc::channel(64);
    let mut channels = vec![];
    let mut endpoint = None;
    let mut connection = None;
    if kind == "quic" {
        let mut e = quinn::Endpoint::client(SocketAddr::new(bind, 0))?;
        let mut qc = quinn::ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(tls_client(c.cert.clone())?)?,
        ));
        qc.transport_config(transport());
        e.set_default_client_config(qc);
        let q = timeout(Duration::from_secs(8), e.connect(c.quic, NAME)?).await??;
        let identity = q
            .peer_identity()
            .context("missing peer identity")?
            .downcast::<Vec<CertificateDer<'static>>>()
            .map_err(|_| anyhow::anyhow!("peer identity type"))?;
        ensure!(
            identity.len() == 1 && identity[0].as_ref() == c.cert,
            "full certificate pin mismatch"
        );
        for i in 0..=n {
            let (send, recv) = q.open_bi().await?;
            send.set_priority(if i == 0 { 10 } else { 0 })?;
            let mut io: AnyIo = Box::new(tokio::io::join(recv, send));
            client_auth(&mut io, c, 0, u8::from(i > 0), session).await?;
            channels.push(raw(io, et.clone()));
        }
        connection = Some(q);
        endpoint = Some(e);
    } else {
        let count = if kind == "wss" { 1 } else { 2 };
        let websocket = matches!(kind, "wss" | "wss-split");
        let tls = tokio_rustls::TlsConnector::from(Arc::new(tls_client(c.cert.clone())?));
        for i in 0..count {
            let socket = TcpSocket::new_v4()?;
            socket.bind(SocketAddr::new(bind, 0))?;
            let s = socket.connect(c.tcp).await?;
            s.set_nodelay(true)?;
            let s = tls.connect(ServerName::try_from(NAME)?, s).await?;
            let peer = s.get_ref().1.peer_certificates().context("no cert")?;
            ensure!(
                peer.len() == 1 && peer[0].as_ref() == c.cert,
                "full certificate pin mismatch"
            );
            let mut io: AnyIo = Box::new(s);
            client_auth(&mut io, c, u8::from(websocket), u8::from(i > 0), session).await?;
            channels.push(if websocket {
                let (w, _) = tokio_tungstenite::client_async_with_config(
                    format!("wss://{NAME}/bench"),
                    io,
                    Some(ws_config()),
                )
                .await?;
                ws(w, et.clone())
            } else {
                raw(io, et.clone())
            });
        }
    }
    Ok(Link {
        channels,
        quic: connection,
        endpoint,
        events: er,
        setup_ms: start.elapsed().as_secs_f64() * 1000.,
    })
}
#[derive(Serialize)]
struct Rtt {
    round: usize,
    mode: String,
    job: u32,
    rtt_ms: f64,
}
#[derive(Default)]
struct Pings {
    next: u64,
    waiting: HashMap<u64, (Instant, u32)>,
    values: Vec<Rtt>,
}
impl Pings {
    async fn send(&mut self, tx: &Tx, job: u32) -> Result<()> {
        self.next += 1;
        let mut b = vec![PING];
        b.extend(self.next.to_be_bytes());
        b.extend(job.to_be_bytes());
        self.waiting.insert(self.next, (Instant::now(), job));
        tx.high.send(b).await?;
        Ok(())
    }
    fn receive(&mut self, b: &[u8], round: usize, mode: &str) -> Result<()> {
        ensure!(b.len() == 13, "echo length");
        let seq = u64::from_be_bytes(b[1..9].try_into()?);
        let (t, job) = self.waiting.remove(&seq).context("unexpected echo")?;
        ensure!(u32at(b, 9)? == job, "echo tag");
        self.values.push(Rtt {
            round,
            mode: mode.into(),
            job,
            rtt_ms: t.elapsed().as_secs_f64() * 1000.,
        });
        Ok(())
    }
}
async fn idle(link: &mut Link, pings: &mut Pings, round: usize, mode: &str, ms: u64) -> Result<()> {
    let until = tokio::time::sleep(Duration::from_millis(ms));
    tokio::pin!(until);
    let mut tick = interval(Duration::from_millis(20));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {_= &mut until=>break,_=tick.tick()=>pings.send(&link.channels[0].tx,0).await?,b=link.events.recv()=>{let b=b.context("link closed")?;ensure!(b[0]==PING,"unexpected idle record");pings.receive(&b,round,mode)?;}}
    }
    Ok(())
}
struct UploadCase<'a> {
    round: usize,
    mode: &'a str,
    fi: usize,
    streams: usize,
    job: u32,
}
async fn upload(
    link: &mut Link,
    pings: &mut Pings,
    c: &Config,
    dir: &Path,
    case: UploadCase<'_>,
) -> Result<f64> {
    let UploadCase {
        round,
        mode,
        fi,
        streams: n,
        job,
    } = case;
    let start = Instant::now();
    let mut begin = vec![START];
    begin.extend(job.to_be_bytes());
    begin.extend((fi as u32).to_be_bytes());
    begin.push(n as u8);
    link.channels[0].tx.high.send(begin).await?;
    let mut tick = interval(Duration::from_millis(20));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        tokio::select! {_=tick.tick()=>pings.send(&link.channels[0].tx,job).await?,b=link.events.recv()=>{let b=b.context("closed")?;if b[0]==PING{pings.receive(&b,round,mode)?}else{ensure!(b[0]==READY && u32at(&b,1)?==job,"ready");break}}}
    }
    let pi = match n {
        1 => 0,
        2 => 1,
        4 => 2,
        _ => bail!("streams"),
    };
    let mut producers = tokio::task::JoinSet::new();
    for (i, part) in c.samples[fi].parts[pi].iter().cloned().enumerate() {
        let tx = link.channels[if mode == "wss" { 0 } else { i + 1 }]
            .tx
            .clone();
        let path = dir.join(&c.samples[fi].name);
        producers.spawn(async move {
            let mut f = File::open(path)?;
            f.seek(SeekFrom::Start(part.offset))?;
            let mut left = part.len;
            while left > 0 {
                let count = (left as usize).min(CHUNK);
                let mut b = Vec::with_capacity(count + 6);
                b.push(DATA);
                b.extend(job.to_be_bytes());
                b.push(i as u8);
                b.resize(count + 6, 0);
                f.read_exact(&mut b[6..])?;
                tx.low.send(b).await?;
                left -= count as u64;
            }
            let mut end = vec![END];
            end.extend(job.to_be_bytes());
            end.push(i as u8);
            tx.low.send(end).await?;
            Ok::<_, anyhow::Error>(())
        });
    }
    loop {
        tokio::select! {_=tick.tick()=>pings.send(&link.channels[0].tx,job).await?,Some(result)=producers.join_next()=>{result??;},b=link.events.recv()=>{let b=b.context("closed")?;if b[0]==PING{pings.receive(&b,round,mode)?}else{ensure!(b[0]==DONE && u32at(&b,1)?==job,"completion");break}}}
    }
    while let Some(r) = producers.join_next().await {
        r??;
    }
    Ok(start.elapsed().as_secs_f64() * 1000.)
}
fn memory() -> String {
    fs::read_to_string("/proc/self/status")
        .unwrap_or_default()
        .lines()
        .filter(|l| l.starts_with("VmRSS:") || l.starts_with("VmHWM:"))
        .collect::<Vec<_>>()
        .join("; ")
}
fn selected_modes(only: Option<&str>) -> Result<Vec<(&'static str, usize)>> {
    let all = vec![
        ("wss", 1),
        ("wss-split", 1),
        ("tls-split", 1),
        ("quic", 1),
        ("quic", 2),
        ("quic", 4),
    ];
    let Some(only) = only else { return Ok(all) };
    let requested: Vec<_> = only.split(',').collect();
    let modes: Vec<_> = all
        .into_iter()
        .filter(|(kind, n)| {
            let label = if *kind == "quic" {
                format!("quic-{n}")
            } else {
                kind.to_string()
            };
            requested.contains(&label.as_str())
        })
        .collect();
    ensure!(
        !modes.is_empty() && modes.len() == requested.len(),
        "unknown or repeated mode"
    );
    Ok(modes)
}
async fn client(
    config: &Path,
    dir: &Path,
    bind: IpAddr,
    rounds: usize,
    out: &Path,
    only: Option<&str>,
) -> Result<()> {
    let c: Config = serde_json::from_slice(&fs::read(config)?)?;
    ensure!(rounds > 0 && rounds <= 10, "round count");
    let modes = selected_modes(only)?;
    let mut output = File::create(out)?;
    let mut job = 0u32;
    for round in 1..=rounds {
        for step in 0..modes.len() {
            let (kind, n) = modes[(step + round - 1) % modes.len()];
            let label = if kind == "quic" {
                format!("quic-{n}")
            } else {
                kind.into()
            };
            let mut random = [0u8; 8];
            getrandom::fill(&mut random).map_err(|e| anyhow::anyhow!("random: {e}"))?;
            let result = timeout(
                Duration::from_secs(12),
                connect(&c, bind, kind, n, u64::from_be_bytes(random)),
            )
            .await;
            let mut link = match result {
                Ok(Ok(l)) => l,
                other => {
                    let error = match other {
                        Ok(Err(e)) => format!("{e:#}"),
                        _ => "connection timed out".into(),
                    };
                    let row = serde_json::json!({"type":"error","mode":label,"round":round,"error":error});
                    writeln!(output, "{row}")?;
                    output.flush()?;
                    println!("{row}");
                    continue;
                }
            };
            writeln!(
                output,
                "{}",
                serde_json::json!({"type":"setup","mode":label,"round":round,"setup_ms":link.setup_ms})
            )?;
            let mut pings = Pings::default();
            idle(&mut link, &mut pings, round, &label, 1500).await?;
            for index in 0..c.samples.len() {
                let fi = if round % 2 == 1 {
                    index
                } else {
                    c.samples.len() - 1 - index
                };
                job += 1;
                let ms = timeout(
                    BUDGET,
                    upload(
                        &mut link,
                        &mut pings,
                        &c,
                        dir,
                        UploadCase {
                            round,
                            mode: &label,
                            fi,
                            streams: n,
                            job,
                        },
                    ),
                )
                .await
                .context("upload 90s timeout")??;
                let sample = &c.samples[fi];
                let row = serde_json::json!({"type":"upload","round":round,"mode":label,"job":job,"file":sample.name,"bytes":sample.bytes,"ms":ms,"mbps":sample.bytes as f64*8./(ms*1000.),"verified":true,"memory":memory()});
                writeln!(output, "{row}")?;
                output.flush()?;
                println!("{row}");
                std::io::stdout().flush()?;
            }
            idle(&mut link, &mut pings, round, &label, 1000).await?;
            for r in pings.values {
                writeln!(
                    output,
                    "{}",
                    serde_json::json!({"type":"rtt","round":r.round,"mode":r.mode,"job":r.job,"rtt_ms":r.rtt_ms})
                )?;
            }
            if let Some(q) = &link.quic {
                let s = q.stats();
                writeln!(
                    output,
                    "{}",
                    serde_json::json!({"type":"quic_stats","round":round,"mode":label,"lost_packets":s.path.lost_packets,"sent_packets":s.path.sent_packets,"rtt_ms":s.path.rtt.as_secs_f64()*1000.})
                )?;
            }
            output.flush()?;
            drop(link);
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
    }
    Ok(())
}
#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<()> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| anyhow::anyhow!("provider"))?;
    let a: Vec<String> = std::env::args().collect();
    match a.get(1).map(String::as_str) {
        Some("server-okhttp") if a.len() == 5 => {
            okhttp_bench::server(a[2].parse()?, Path::new(&a[3]), Path::new(&a[4])).await
        }
        Some("server") if a.len() == 5 => {
            server(a[2].parse()?, Path::new(&a[3]), Path::new(&a[4])).await
        }
        Some("client") if a.len() >= 7 => {
            client(
                Path::new(&a[2]),
                Path::new(&a[3]),
                a[4].parse()?,
                a[5].parse()?,
                Path::new(&a[6]),
                a.get(7).map(String::as_str),
            )
            .await
        }
        _ => bail!(
            "server BIND_IPV4 IMAGE_DIR RUN_DIR | client CONFIG IMAGE_DIR BIND_IPV4 ROUNDS OUTPUT_JSONL [MODE[,MODE...]]"
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mode_selection_rejects_missing_or_repeated_cases() {
        assert_eq!(
            selected_modes(Some("wss,wss-split,tls-split")).unwrap(),
            vec![("wss", 1), ("wss-split", 1), ("tls-split", 1)]
        );
        assert!(selected_modes(Some("unknown")).is_err());
        assert!(selected_modes(Some("wss,wss")).is_err());
        assert!(selected_modes(Some("wss,unknown")).is_err());
    }
    #[tokio::test]
    async fn tcp_modes_transfer_and_verify_with_expected_connection_count() -> Result<()> {
        rustls::crypto::ring::default_provider()
            .install_default()
            .ok();
        let key = rcgen::KeyPair::generate()?;
        let cert = rcgen::CertificateParams::new(vec![NAME.into()])?
            .self_signed(&key)?
            .der()
            .to_vec();
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(tls_server(
            cert.clone(),
            key.serialize_der(),
        )?));
        let mut nonce = [0u8; 8];
        getrandom::fill(&mut nonce).map_err(|e| anyhow::anyhow!("{e}"))?;
        let dir = std::env::temp_dir().join(format!("sayin-bench-test-{}", hex(&nonce)));
        fs::create_dir(&dir)?;
        fs::write(dir.join("test"), b"test bytes")?;
        let result = async {
            for (kind, count) in [("wss", 1), ("wss-split", 2), ("tls-split", 2)] {
                let listener = TcpListener::bind("127.0.0.1:0").await?;
                let mut c = fixture();
                c.tcp = listener.local_addr()?;
                c.cert = cert.clone();
                let c = Arc::new(c);
                let sessions: Sessions = Arc::new(Mutex::new(HashMap::new()));
                let sc = c.clone();
                let sa = acceptor.clone();
                let server = tokio::spawn(async move {
                    let mut handlers = tokio::task::JoinSet::new();
                    for _ in 0..count {
                        let (tcp, _) = listener.accept().await?;
                        tcp.set_nodelay(true)?;
                        let tls = sa.clone();
                        let c = sc.clone();
                        let all = sessions.clone();
                        handlers.spawn(async move {
                            serve_channel(Box::new(tls.accept(tcp).await?), c, all).await
                        });
                    }
                    while let Some(r) = handlers.join_next().await {
                        r??;
                    }
                    Ok::<_, anyhow::Error>(count)
                });
                let mut link = timeout(
                    Duration::from_secs(5),
                    connect(&c, "127.0.0.1".parse()?, kind, 1, 1),
                )
                .await??;
                assert_eq!(link.channels.len(), count);
                timeout(
                    Duration::from_secs(5),
                    upload(
                        &mut link,
                        &mut Pings::default(),
                        &c,
                        &dir,
                        UploadCase {
                            round: 1,
                            mode: kind,
                            fi: 0,
                            streams: 1,
                            job: 1,
                        },
                    ),
                )
                .await??;
                drop(link);
                assert_eq!(timeout(Duration::from_secs(5), server).await???, count);
            }
            Ok::<_, anyhow::Error>(())
        }
        .await;
        fs::remove_file(dir.join("test"))?;
        fs::remove_dir(dir)?;
        result
    }
    pub(super) fn fixture() -> Config {
        let bytes = b"test bytes";
        let make = |n| {
            plan(bytes.len() as u64, n)
                .into_iter()
                .map(|(offset, len)| Part {
                    offset,
                    len,
                    sha256: hex(&Sha256::digest(
                        &bytes[offset as usize..(offset + len) as usize],
                    )),
                })
                .collect()
        };
        Config {
            tcp: "127.0.0.1:1".parse().unwrap(),
            quic: "127.0.0.1:2".parse().unwrap(),
            cert: vec![],
            token: vec![0; 32],
            samples: vec![Sample {
                name: "test".into(),
                bytes: 10,
                sha256: hex(&Sha256::digest(bytes)),
                parts: vec![make(1), make(2), make(4)],
            }],
        }
    }
    fn begin(c: &Config, n: u8) -> Session {
        let mut s = Session::default();
        assert_eq!(
            server_message(c, &mut s, 0, &[START, 0, 0, 0, 1, 0, 0, 0, 0, n])
                .unwrap()
                .unwrap()[0],
            READY
        );
        s
    }
    #[test]
    fn partitions_cover_exactly() {
        for size in [1, 3, 10, 58658641] {
            for n in [1, 2, 4] {
                let p = plan(size, n);
                assert_eq!(p.iter().map(|p| p.1).sum::<u64>(), size);
                assert_eq!(p[0].0, 0);
                for pair in p.windows(2) {
                    assert_eq!(pair[0].0 + pair[0].1, pair[1].0);
                }
            }
        }
    }
    #[test]
    fn valid_parts_need_all_hashes() {
        let c = fixture();
        for n in [1, 2, 4] {
            let mut s = begin(&c, n);
            for (i, (o, l)) in plan(10, n as usize).into_iter().enumerate() {
                let mut b = vec![DATA, 0, 0, 0, 1, i as u8];
                b.extend(&b"test bytes"[o as usize..(o + l) as usize]);
                server_message(&c, &mut s, 1, &b).unwrap();
                let ack = server_message(&c, &mut s, 1, &[END, 0, 0, 0, 1, i as u8]).unwrap();
                assert_eq!(ack.is_some(), i + 1 == n as usize);
            }
            assert!(s.jobs.is_empty());
        }
    }
    #[test]
    fn rejects_corrupt_short_overlong_unknown() {
        let c = fixture();
        let mut s = begin(&c, 1);
        assert!(server_message(&c, &mut s, 1, &[END, 0, 0, 0, 1, 0]).is_err());
        let mut b = vec![DATA, 0, 0, 0, 1, 0];
        b.extend(b"wrongbytes");
        server_message(&c, &mut s, 1, &b).unwrap();
        assert!(server_message(&c, &mut s, 1, &[END, 0, 0, 0, 1, 0]).is_err());
        assert!(server_message(&c, &mut s, 1, &[DATA, 0, 0, 0, 1, 0, 1]).is_err());
        assert!(server_message(&c, &mut s, 1, &[DATA, 0, 0, 0, 2, 0, 1]).is_err());
    }
    #[test]
    fn rejects_unbounded_headers() {
        let c = fixture();
        let mut s = begin(&c, 1);
        assert!(server_message(&c, &mut s, 0, &[START, 0, 0, 0, 2, 0, 0, 0, 0, 1]).is_err());
        assert!(
            server_message(
                &c,
                &mut Session::default(),
                0,
                &[START, 0, 0, 0, 1, 0, 0, 0, 0, 3]
            )
            .is_err()
        );
        assert!(server_message(&c, &mut s, 1, &[PING; 13]).is_err());
        assert!(server_message(&c, &mut s, 0, &[]).is_err());
    }
}
