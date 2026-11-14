//! Bounded HTTP/1.1 experiment endpoint for real Android OkHttp, not a product HTTP server.
use super::*;
use tokio_tungstenite::tungstenite::{
    handshake::server::create_response, http::Request, protocol::Role,
};

const CANCEL: u8 = 10;
const CANCELLED: u8 = 11;
const STATUS: u8 = 12;
const STATUS_ACK: u8 = 13;
#[derive(Clone, Default)]
struct State {
    sessions: Sessions,
    cancelled: Arc<Mutex<HashMap<(u64, u32), u64>>>,
}
struct Head {
    method: String,
    path: String,
    headers: Vec<(String, String)>,
}
impl Head {
    fn field(&self, name: &str) -> Result<&str> {
        let mut values = self
            .headers
            .iter()
            .filter(|(n, _)| n.eq_ignore_ascii_case(name));
        let value = &values.next().context("missing header")?.1;
        ensure!(values.next().is_none(), "duplicate header");
        Ok(value)
    }
}
async fn head(io: &mut AnyIo) -> Result<Head> {
    let mut bytes = Vec::new();
    loop {
        ensure!(bytes.len() < 8192, "headers too large");
        bytes.push(io.read_u8().await?);
        if bytes.ends_with(b"\r\n\r\n") {
            break;
        }
    }
    let mut headers = [httparse::EMPTY_HEADER; 32];
    let mut request = httparse::Request::new(&mut headers);
    ensure!(
        request.parse(&bytes)?.is_complete() && request.version == Some(1),
        "HTTP/1.1 required"
    );
    Ok(Head {
        method: request.method.context("method")?.into(),
        path: request.path.context("path")?.into(),
        headers: request
            .headers
            .iter()
            .map(|h| Ok((h.name.to_string(), std::str::from_utf8(h.value)?.to_owned())))
            .collect::<Result<_>>()?,
    })
}
async fn reply(io: &mut AnyIo, code: &str, body: &[u8]) -> Result<()> {
    io.write_all(
        format!(
            "HTTP/1.1 {code}\r\nContent-Length: {}\r\nContent-Type: application/json\r\n\r\n",
            body.len()
        )
        .as_bytes(),
    )
    .await?;
    io.write_all(body).await?;
    io.flush().await?;
    Ok(())
}
fn special(state: &State, session: u64, role: u8, b: &[u8]) -> Result<Option<Vec<u8>>> {
    let kind = b.first().copied().context("empty command")?;
    if kind != CANCEL && kind != STATUS {
        return Ok(None);
    }
    ensure!(role == 0 && b.len() == 5, "cancel/status scope");
    let job = u32at(b, 1)?;
    let count = if kind == CANCEL {
        let mut sessions = state.sessions.lock().unwrap();
        let s = sessions.get_mut(&session).context("session")?;
        let job_data = s.jobs.remove(&job).context("no active job to cancel")?;
        let count = job_data.parts.iter().map(|p| p.got).sum();
        state
            .cancelled
            .lock()
            .unwrap()
            .insert((session, job), count);
        count
    } else {
        *state
            .cancelled
            .lock()
            .unwrap()
            .get(&(session, job))
            .context("no cancelled job")?
    };
    let mut ack = vec![if kind == CANCEL {
        CANCELLED
    } else {
        STATUS_ACK
    }];
    ack.extend(job.to_be_bytes());
    ack.extend(count.to_be_bytes());
    Ok(Some(ack))
}
async fn send_control(state: &State, session: u64, b: Vec<u8>) -> Result<()> {
    let tx = state
        .sessions
        .lock()
        .unwrap()
        .get(&session)
        .and_then(|s| s.control.clone())
        .context("no control")?;
    tx.high.send(b).await?;
    Ok(())
}
fn dispatch(
    state: &State,
    c: &Config,
    session: u64,
    role: u8,
    b: &[u8],
) -> Result<Option<Vec<u8>>> {
    if matches!(b.first(), Some(&CANCEL) | Some(&STATUS)) {
        return special(state, session, role, b);
    }
    let mut all = state.sessions.lock().unwrap();
    if role == 1
        && matches!(b.first(), Some(&DATA) | Some(&END))
        && b.len() >= 6
        && state
            .cancelled
            .lock()
            .unwrap()
            .contains_key(&(session, u32at(b, 1)?))
    {
        return Ok(None);
    }
    server_message(c, all.get_mut(&session).context("session")?, role, b)
}
async fn websocket(
    mut io: AnyIo,
    h: Head,
    c: Arc<Config>,
    state: State,
    session: u64,
) -> Result<()> {
    let role = if h.path == "/control" { 0 } else { 1 };
    ensure!(
        h.method == "GET" && matches!(h.path.as_str(), "/control" | "/data"),
        "WS route"
    );
    let mut req = Request::builder().method("GET").uri(&h.path);
    for (n, v) in h.headers {
        req = req.header(n, v)
    }
    let response = create_response(&req.body(())?)?;
    let mut raw_response = String::from("HTTP/1.1 101 Switching Protocols\r\n");
    for (n, v) in response.headers() {
        raw_response.push_str(n.as_str());
        raw_response.push_str(": ");
        raw_response.push_str(v.to_str()?);
        raw_response.push_str("\r\n")
    }
    raw_response.push_str("\r\n");
    io.write_all(raw_response.as_bytes()).await?;
    io.flush().await?;
    let socket = WebSocketStream::from_raw_socket(io, Role::Server, Some(ws_config())).await;
    let (et, mut er) = mpsc::channel(16);
    let channel = ws(socket, et);
    {
        let mut all = state.sessions.lock().unwrap();
        ensure!(all.len() < 128 || all.contains_key(&session), "sessions");
        let s = all.entry(session).or_default();
        if role == 0 {
            ensure!(s.control.is_none(), "duplicate control");
            s.control = Some(channel.tx.clone())
        }
    }
    while let Ok(Some(b)) = timeout(Duration::from_secs(120), er.recv()).await {
        let response = dispatch(&state, &c, session, role, &b)?;
        if let Some(b) = response {
            send_control(&state, session, b).await?
        }
    }
    Ok(())
}
async fn upload(io: &mut AnyIo, h: &Head, c: &Config, state: &State, session: u64) -> Result<bool> {
    ensure!(h.method == "PUT", "upload method");
    let pieces: Vec<_> = h.path.split('/').collect();
    ensure!(pieces.len() == 4 && pieces[1] == "upload", "upload route");
    let fi = pieces[2].parse::<usize>()?;
    let job = pieces[3].parse::<u32>()?;
    let sample = c.samples.get(fi).context("sample")?;
    ensure!(
        !h.headers
            .iter()
            .any(|(n, _)| n.eq_ignore_ascii_case("transfer-encoding")),
        "fixed length only"
    );
    ensure!(
        h.field("Content-Length")?.parse::<u64>()? == sample.bytes,
        "content length"
    );
    {
        let all = state.sessions.lock().unwrap();
        let parts = &all
            .get(&session)
            .context("session")?
            .jobs
            .get(&job)
            .context("upload not begun")?
            .parts;
        ensure!(
            parts.len() == 1
                && parts[0].expected.sha256 == sample.sha256
                && parts[0].expected.len == sample.bytes,
            "upload identity"
        );
    }
    let mut left = sample.bytes;
    while left > 0 {
        let size = (left as usize).min(CHUNK);
        let mut b = vec![DATA];
        b.extend(job.to_be_bytes());
        b.push(0);
        b.resize(size + 6, 0);
        io.read_exact(&mut b[6..]).await?;
        {
            let mut all = state.sessions.lock().unwrap();
            if state
                .cancelled
                .lock()
                .unwrap()
                .contains_key(&(session, job))
            {
                return Ok(false);
            }
            server_message(c, all.get_mut(&session).context("session")?, 1, &b)?;
        }
        left -= size as u64;
    }
    let response = {
        let mut all = state.sessions.lock().unwrap();
        if state
            .cancelled
            .lock()
            .unwrap()
            .contains_key(&(session, job))
        {
            return Ok(false);
        }
        let mut b = vec![END];
        b.extend(job.to_be_bytes());
        b.push(0);
        server_message(c, all.get_mut(&session).context("session")?, 1, &b)?
            .context("completion")?
    };
    send_control(state, session, response).await?;
    reply(io, "200 OK", b"{\"verified\":true}").await?;
    Ok(true)
}
fn closed(e: &anyhow::Error) -> bool {
    e.downcast_ref::<std::io::Error>().is_some_and(|e| {
        matches!(
            e.kind(),
            std::io::ErrorKind::UnexpectedEof
                | std::io::ErrorKind::ConnectionReset
                | std::io::ErrorKind::ConnectionAborted
                | std::io::ErrorKind::BrokenPipe
        )
    })
}
async fn connection(mut io: AnyIo, c: Arc<Config>, state: State) -> Result<()> {
    loop {
        let h = match timeout(Duration::from_secs(120), head(&mut io)).await? {
            Ok(h) => h,
            Err(e) if closed(&e) => return Ok(()),
            Err(e) => return Err(e),
        };
        ensure!(
            h.field("Authorization")? == format!("Bearer {}", hex(&c.token)),
            "authorization"
        );
        let session = h.field("X-Bench-Session")?.parse::<u64>()?;
        if h.path == "/control" || h.path == "/data" {
            return websocket(io, h, c, state, session).await;
        }
        if h.path == "/health" {
            ensure!(h.method == "GET", "health method");
            reply(&mut io, "200 OK", b"{}").await?;
            continue;
        }
        match upload(&mut io, &h, &c, &state, session).await {
            Ok(true) => {}
            Ok(false) => return Ok(()),
            Err(e) if closed(&e) => return Ok(()),
            Err(e) => return Err(e),
        }
    }
}
async fn raw_channel(mut io: AnyIo, c: Arc<Config>, state: State) -> Result<()> {
    let (mode, role, session) = timeout(Duration::from_secs(8), server_auth(&mut io, &c)).await??;
    ensure!(mode == 0, "raw mode");
    let (et, mut er) = mpsc::channel(16);
    let channel = raw(io, et);
    {
        let mut all = state.sessions.lock().unwrap();
        ensure!(all.len() < 128 || all.contains_key(&session), "sessions");
        let s = all.entry(session).or_default();
        if role == 0 {
            ensure!(s.control.is_none(), "duplicate control");
            s.control = Some(channel.tx.clone());
        }
    }
    while let Ok(Some(b)) = timeout(Duration::from_secs(120), er.recv()).await {
        let response = dispatch(&state, &c, session, role, &b)?;
        if let Some(b) = response {
            send_control(&state, session, b).await?;
        }
    }
    Ok(())
}

pub(super) async fn server(ip: IpAddr, images: &Path, run: &Path, all_modes: bool) -> Result<()> {
    ensure!(
        ip.is_ipv4() && !ip.is_unspecified() && !ip.is_multicast(),
        "specific IPv4 required"
    );
    fs::create_dir_all(run)?;
    let key = rcgen::KeyPair::generate()?;
    let cert = rcgen::CertificateParams::new(vec![NAME.into()])?
        .self_signed(&key)?
        .der()
        .to_vec();
    let tls =
        tokio_rustls::TlsAcceptor::from(Arc::new(tls_server(cert.clone(), key.serialize_der())?));
    let listener = TcpListener::bind(SocketAddr::new(ip, 0)).await?;
    let raw_listener = if all_modes {
        Some(TcpListener::bind(SocketAddr::new(ip, 0)).await?)
    } else {
        None
    };
    let quic = if all_modes {
        let mut crypto = tls_server(cert.clone(), key.serialize_der())?;
        crypto.alpn_protocols = vec![b"sayin-bench".to_vec()];
        let mut qc = quinn::ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(crypto)?,
        ));
        qc.transport_config(transport());
        Some(quinn::Endpoint::server(qc, SocketAddr::new(ip, 0))?)
    } else {
        None
    };
    let mut token = vec![0; 32];
    getrandom::fill(&mut token).map_err(|e| anyhow::anyhow!("{e}"))?;
    let c = Arc::new(Config {
        tcp: listener.local_addr()?,
        quic: quic
            .as_ref()
            .map_or(SocketAddr::new(ip, 0), |q| q.local_addr().unwrap()),
        cert,
        token,
        samples: manifest(images)?,
    });
    let mut config = serde_json::to_value(&*c)?;
    if let Some(raw) = &raw_listener {
        config["raw"] = serde_json::to_value(raw.local_addr()?)?;
    }
    fs::write(run.join("config.json"), serde_json::to_vec_pretty(&config)?)?;
    println!(
        "READY okhttp tcp={} raw={:?} quic={} samples={}",
        c.tcp,
        raw_listener.as_ref().map(|r| r.local_addr().unwrap()),
        c.quic,
        c.samples.len()
    );
    std::io::stdout().flush()?;
    let state = State::default();
    let deadline = tokio::time::sleep(Duration::from_secs(3600));
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            Ok((socket,_))=listener.accept()=>{socket.set_nodelay(true)?;let tls=tls.clone();let c=c.clone();let state=state.clone();tokio::spawn(async move{let result=async{let io=timeout(Duration::from_secs(8),tls.accept(socket)).await??;connection(Box::new(io),c,state).await}.await;if let Err(e)=result && !closed(&e){eprintln!("OkHttp endpoint: {e:#}")}});},
            Ok((socket,_))=async { raw_listener.as_ref().unwrap().accept().await }, if all_modes=>{
                socket.set_nodelay(true)?; let tls=tls.clone();let c=c.clone();let state=state.clone();
                tokio::spawn(async move { let result=async {let io=timeout(Duration::from_secs(8),tls.accept(socket)).await??;raw_channel(Box::new(io),c,state).await}.await; if let Err(e)=result && !closed(&e){eprintln!("Raw TLS endpoint: {e:#}")} });
            },
            Some(incoming)=async { quic.as_ref().unwrap().accept().await }, if all_modes=>{
                let c=c.clone();let state=state.clone();
                tokio::spawn(async move {match timeout(Duration::from_secs(8),incoming).await {
                    Ok(Ok(conn))=>while let Ok((send,recv))=conn.accept_bi().await {let c=c.clone();let state=state.clone();tokio::spawn(async move {if let Err(e)=raw_channel(Box::new(tokio::io::join(recv,send)),c,state).await && !closed(&e){eprintln!("QUIC endpoint: {e:#}")}});},
                    Ok(Err(e))=>eprintln!("QUIC handshake: {e}"), Err(_)=>eprintln!("QUIC handshake timeout"),
                }});
            },
            _=tokio::time::sleep(Duration::from_millis(200))=>{if run.join("stop").exists(){break}},
            _=&mut deadline=>break,
        }
    }
    if let Some(quic) = quic {
        quic.close(0u32.into(), b"test complete");
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancel_freezes_received_count_and_removes_job() {
        let state = State::default();
        let mut config = super::super::tests::fixture();
        config.samples[0].bytes = 10;
        let mut session = Session::default();
        server_message(
            &config,
            &mut session,
            0,
            &[START, 0, 0, 0, 1, 0, 0, 0, 0, 1],
        )
        .unwrap();
        server_message(&config, &mut session, 1, &[DATA, 0, 0, 0, 1, 0, b't', b'e']).unwrap();
        state.sessions.lock().unwrap().insert(9, session);
        let ack = special(&state, 9, 0, &[CANCEL, 0, 0, 0, 1])
            .unwrap()
            .unwrap();
        assert_eq!(u64::from_be_bytes(ack[5..].try_into().unwrap()), 2);
        assert!(
            state
                .sessions
                .lock()
                .unwrap()
                .get(&9)
                .unwrap()
                .jobs
                .is_empty()
        );
        assert_eq!(
            special(&state, 9, 0, &[STATUS, 0, 0, 0, 1])
                .unwrap()
                .unwrap()[5..],
            ack[5..]
        );
        assert!(special(&state, 9, 1, &[CANCEL, 0, 0, 0, 1]).is_err());
    }
    #[test]
    fn cancelled_late_frames_do_not_recreate_or_complete_job() {
        let state = State::default();
        let config = super::super::tests::fixture();
        state
            .sessions
            .lock()
            .unwrap()
            .insert(42, Session::default());
        dispatch(&state, &config, 42, 0, &[START, 0, 0, 0, 1, 0, 0, 0, 0, 1]).unwrap();
        dispatch(&state, &config, 42, 1, &[DATA, 0, 0, 0, 1, 0, b't']).unwrap();
        let ack = dispatch(&state, &config, 42, 0, &[CANCEL, 0, 0, 0, 1])
            .unwrap()
            .unwrap();
        assert_eq!(u64::from_be_bytes(ack[5..].try_into().unwrap()), 1);
        assert!(
            dispatch(&state, &config, 42, 1, &[DATA, 0, 0, 0, 1, 0, b'e'])
                .unwrap()
                .is_none()
        );
        assert!(
            dispatch(&state, &config, 42, 1, &[END, 0, 0, 0, 1, 0])
                .unwrap()
                .is_none()
        );
        let status = dispatch(&state, &config, 42, 0, &[STATUS, 0, 0, 0, 1])
            .unwrap()
            .unwrap();
        assert_eq!(u64::from_be_bytes(status[5..].try_into().unwrap()), 1);
        assert!(state.sessions.lock().unwrap()[&42].jobs.is_empty());
        assert!(dispatch(&state, &config, 42, 1, &[DATA, 0, 0, 0, 2, 0, b'e']).is_err());
    }
    #[test]
    fn duplicate_critical_headers_rejected() {
        let h = Head {
            method: "GET".into(),
            path: "/".into(),
            headers: vec![
                ("Content-Length".into(), "1".into()),
                ("content-length".into(), "2".into()),
            ],
        };
        assert!(h.field("Content-Length").is_err());
    }
}
