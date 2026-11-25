//! On-demand image WSS. A separate TLS connection cannot create a session.
use super::*;
use std::sync::atomic::{AtomicBool, Ordering};
use voicelink_receiver::{
    images::{
        ImageMetadata, ImageReference,
        wire::{Upload, UploadScope},
    },
    windows_input::Dpapi,
};

pub(super) struct Binding {
    session: Session,
    token: Zeroizing<Vec<u8>>,
    alive: Arc<AtomicBool>,
}
pub(super) struct ControlBinding<'a> {
    shared: &'a Shared,
    binding: Option<Arc<Binding>>,
}
impl<'a> ControlBinding<'a> {
    pub fn new(shared: &'a Shared, session: &Session) -> Result<Self> {
        let binding = if shared.receiver.image_store().is_some() {
            let binding = Arc::new(Binding {
                session: session.clone(),
                token: Zeroizing::new(random::<32>()?.to_vec()),
                alive: Arc::new(AtomicBool::new(true)),
            });
            shared
                .image_bindings
                .lock()
                .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?
                .insert(session.id().into(), binding.clone());
            Some(binding)
        } else {
            None
        };
        Ok(Self { shared, binding })
    }
    pub fn description(&self) -> Value {
        self.binding.as_ref().map_or(Value::Null, |binding| json!({"path":"/sayin/v3/images","binding":encode(&binding.token),"max_image_bytes":10485760,"max_pixels":16000000,"max_side":8192,"chunk_bytes":49152}))
    }
}
impl Drop for ControlBinding<'_> {
    fn drop(&mut self) {
        if let Some(binding) = &self.binding {
            binding.alive.store(false, Ordering::Release);
            if let Ok(mut all) = self.shared.image_bindings.lock() {
                all.remove(binding.session.id());
            }
        }
    }
}
struct PairUploadSlot<'a> {
    pairs: &'a Mutex<HashSet<String>>,
    pair: &'a str,
}
impl Drop for PairUploadSlot<'_> {
    fn drop(&mut self) {
        if let Ok(mut pairs) = self.pairs.lock() {
            pairs.remove(self.pair);
        }
    }
}

async fn scope(shared: &Shared, binding: &Binding) -> Result<UploadScope> {
    if !binding.alive.load(Ordering::Acquire) {
        return Err(Error::Protocol("STALE_SESSION"));
    }
    shared
        .receiver
        .check_session(binding.session.clone())
        .await?;
    let state = shared.receiver.state().await?;
    if state.paused {
        return Err(Error::Protocol("IMAGE_SCOPE"));
    }
    Ok(UploadScope {
        device: binding.session.device().into(),
        session: binding.session.id().into(),
        target: state.target.ok_or(Error::Protocol("IMAGE_SCOPE"))?,
        revision: state.state_rev,
    })
}

pub(super) async fn serve(
    shared: &Shared,
    socket: &mut Socket,
    hello: Value,
    stop: &mut watch::Receiver<bool>,
    peer: IpAddr,
    unauthenticated: tokio::sync::OwnedSemaphorePermit,
) -> Result<()> {
    if hello.get("v").and_then(Value::as_u64) != Some(3) || field(&hello, "t")? != "image_hello" {
        return Err(Error::Protocol("AUTH_REQUIRED"));
    }
    let binding = shared
        .image_bindings
        .lock()
        .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?
        .get(field(&hello, "session_id")?)
        .cloned()
        .ok_or(Error::Protocol("STALE_SESSION"))?;
    let token = Zeroizing::new(decode(field(&hello, "binding")?)?);
    if field(&hello, "pair_id")? != binding.session.device()
        || !constant_time_equal(&token, &binding.token)
    {
        return Err(Error::Protocol("UNAUTHORIZED"));
    }
    let mut initial = scope(shared, &binding).await?;
    let pair = binding.session.device();
    if !shared
        .uploading_pairs
        .lock()
        .map_err(|_| Error::Protocol("SERVICE_UNAVAILABLE"))?
        .insert(pair.to_owned())
    {
        return Err(Error::Protocol("IMAGE_UPLOAD_LIMIT"));
    }
    let _pair_slot = PairUploadSlot {
        pairs: &shared.uploading_pairs,
        pair,
    };
    let _slot = shared
        .image_slots
        .clone()
        .try_acquire_owned()
        .map_err(|_| Error::Protocol("IMAGE_UPLOAD_LIMIT"))?;
    shared.authenticated(peer);
    drop(unauthenticated);
    send(socket,&json!({"v":3,"t":"image_welcome","session_id":binding.session.id(),"host_id":shared.identity.host_id,"pair_id":binding.session.device()})).await?;
    let mut ready: Option<ImageReference> = None;
    let result = transfer(shared, socket, &binding, stop, &mut initial, &mut ready).await;
    if let Some(resource) = ready {
        let _ = shared
            .receiver
            .discard_image(binding.session.clone(), resource.resource_id)
            .await;
    }
    result
}

async fn transfer(
    shared: &Shared,
    socket: &mut Socket,
    binding: &Binding,
    stop: &mut watch::Receiver<bool>,
    initial: &mut UploadScope,
    ready: &mut Option<ImageReference>,
) -> Result<()> {
    let mut upload: Option<Upload> = None;
    let created = Instant::now();
    let mut last = created;
    let mut watchdog = tokio::time::interval(Duration::from_secs(1));
    let mut rate = Rate::new();
    let mut chunks = Rate::image_chunks();
    loop {
        tokio::select! {
            biased;
            _=stop.changed()=>return Ok(()),
            _=watchdog.tick()=>{
                if scope(shared,binding).await? != *initial || last.elapsed() >= Duration::from_secs(60) || created.elapsed() >= Duration::from_secs(300) { return Err(Error::Protocol("IMAGE_SCOPE")); }
            },
            incoming=socket.next()=>{
                let Some(incoming) = incoming else {return Ok(());};
                let message = incoming?;
                if scope(shared,binding).await? != *initial { return Err(Error::Protocol("IMAGE_SCOPE")); }
                last=Instant::now();
                match message {
                    Message::Binary(frame)=>{
                        chunks.take()?;
                        let current=upload.as_mut().ok_or(Error::Protocol("IMAGE_NOT_STARTED"))?;
                        let received=current.push(initial,&frame,Instant::now())?;
                        send(socket,&json!({"v":3,"t":"image_progress","id":current.id,"received":received})).await?;
                    },
                    Message::Text(text)=>{
                        rate.take()?;
                        let value=strict_json(text.as_bytes())?;
                        if value.get("v").and_then(Value::as_u64)!=Some(3) || field(&value,"session_id")?!=binding.session.id() {return Err(Error::Protocol("STALE_SESSION"));}
                        let id=field(&value,"id")?;
                        validate_uuid(id)?;
                        match field(&value,"t")? {
                            "image_begin"=>{
                                if upload.is_some() || ready.is_some() {return Err(Error::Protocol("IMAGE_UPLOAD_LIMIT"));}
                                if field(&value,"target")? != initial.target || field(&value,"state_rev")? != initial.revision {return Err(Error::Protocol("IMAGE_SCOPE"));}
                                let metadata:ImageMetadata=serde_json::from_value(value.get("metadata").cloned().ok_or(Error::Protocol("IMAGE_METADATA"))?)?;
                                upload=Some(Upload::new(id.into(),initial.clone(),metadata,Instant::now())?);
                                send(socket,&json!({"v":3,"t":"image_begun","id":id})).await?;
                            },
                            "image_finish"=>{
                                let current=upload.take().ok_or(Error::Protocol("IMAGE_NOT_STARTED"))?;
                                if current.id!=id {return Err(Error::Protocol("IMAGE_CHUNK"));}
                                let metadata=current.metadata.clone();
                                let bytes=current.finish(initial,Instant::now())?;
                                let store=shared.receiver.image_store().ok_or(Error::Protocol("IMAGE_UNAVAILABLE"))?;
                                // Only one decoder at a time; this bound covers canceled blocking jobs too.
                                let permit=shared.image_decode.clone().acquire_owned().await.map_err(|_|Error::Protocol("SERVICE_UNAVAILABLE"))?;
                                let device=binding.session.device().to_owned();
                                let resource=tokio::task::spawn_blocking(move || {let _permit=permit;store.prepare(&device,metadata,&bytes,&Dpapi)}).await.map_err(|_|Error::Protocol("IMAGE_PREPARE"))??;
                                let valid=scope(shared,binding).await.is_ok_and(|scope|scope==*initial);
                                let registered=if valid {shared.receiver.register_image(binding.session.clone(),initial.clone(),resource.clone(),Arc::new(voicelink_receiver::images::ImageAuthorization::new(binding.alive.clone()))).await} else {Err(voicelink_receiver::Error::Protocol("IMAGE_SCOPE"))};
                                registered?;
                                let image=resource.image().clone();
                                *ready=Some(image.clone());
                                send(socket,&json!({"v":3,"t":"image_ready","id":id,"image":image})).await?;
                            },
                            "image_cancel"=>{
                                send(socket,&json!({"v":3,"t":"image_canceled","id":id})).await?;
                                return Ok(());
                            },
                            _=>return Err(Error::Protocol("UNKNOWN_OPERATION")),
                        }
                    },
                    Message::Ping(_)=>{socket.flush().await?;},
                    Message::Pong(_)=>{},
                    Message::Close(_)=>return Ok(()),
                    _=>return Err(Error::Protocol("IMAGE_MESSAGE")),
                }
            }
        }
    }
}
