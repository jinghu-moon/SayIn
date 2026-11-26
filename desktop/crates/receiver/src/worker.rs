//! Bounded coordinator/database thread plus a single serial input thread.
//! Network/GUI callers await oneshots; neither thread performs GUI mutations.
use crate::{
    Error, Result,
    gate::InputGate,
    ledger::{
        ControlState, Execution, InputOutcome, InputWindow, Ledger, ReceiverSnapshot, ReviewTicket,
        Session,
    },
    protocol::{Receipt, Submit},
};
use futures_channel::{mpsc as notifications, oneshot};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};

pub trait InputAdapter: Send + 'static {
    fn preflight(&mut self, job: &Execution) -> std::result::Result<(), &'static str>;
    /// Snapshot of the foreground identity validated by preflight, if available.
    fn prepared_application(&self) -> Option<crate::history::TargetApplication> {
        None
    }
    fn prepared_window(&self) -> Option<InputWindow> {
        None
    }
    /// Check the gate and native foreground immediately before touching input.
    /// Never interpret text as a shortcut, and never attempt to steal focus.
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome;
}

type Request = Box<dyn FnOnce(&mut Coordinator) + Send>;
enum Command {
    Request(Request, bool),
    Prepared(
        Execution,
        std::result::Result<(), &'static str>,
        Option<crate::history::TargetApplication>,
        Option<InputWindow>,
    ),
    Finished(Execution, InputOutcome),
    Shutdown(Option<oneshot::Sender<()>>),
}
enum Work {
    Prepare(Execution),
    Execute(Execution),
}

struct Coordinator {
    ledger: Ledger,
    paused: Arc<InputGate>,
    input: mpsc::SyncSender<Work>,
    in_flight: bool,
    shutting_down: bool,
    observers: Vec<notifications::Sender<()>>,
}

impl Coordinator {
    fn notify(&mut self) {
        self.observers.retain_mut(|observer| {
            // A full mailbox already promises a fresh snapshot. Coalesce the
            // notification, never store an unbounded stream of old snapshots.
            match observer.try_send(()) {
                Ok(()) => true,
                Err(error) => error.is_full(),
            }
        });
    }

    fn dispatch(&mut self) {
        if self.in_flight || self.shutting_down {
            return;
        }
        match self.ledger.prepare_next() {
            Ok(Some(job)) => {
                self.in_flight = true;
                if let Err(error) = self.input.try_send(Work::Prepare(job)) {
                    let work = match error {
                        mpsc::TrySendError::Full(work) | mpsc::TrySendError::Disconnected(work) => {
                            work
                        }
                    };
                    let job = match work {
                        Work::Prepare(job) | Work::Execute(job) => job,
                    };
                    let _ = self.ledger.hold(&job, "input_worker_unavailable");
                    self.paused.pause();
                    let _ = self.ledger.pause();
                    self.in_flight = false;
                }
            }
            Ok(None) => {}
            Err(error) => {
                eprintln!("Receiver scheduling stopped: {error}");
                self.paused.pause();
                let _ = self.ledger.pause();
            }
        }
    }
}

struct Handle {
    image_store: Option<crate::images::storage::ImageStore>,
    sender: mpsc::SyncSender<Command>,
    paused: Arc<InputGate>,
    closed: AtomicBool,
}
impl Drop for Handle {
    fn drop(&mut self) {
        self.paused.pause();
        // A separate tiny shutdown sender avoids blocking the UI on a full
        // command queue; coordinator drains it before starting another job.
        if self.closed.load(Ordering::Acquire) {
            return;
        }
        let sender = self.sender.clone();
        let _ = thread::Builder::new()
            .name("voicelink-stop".into())
            .spawn(move || {
                let _ = sender.send(Command::Shutdown(None));
            });
    }
}

#[derive(Clone)]
pub struct Receiver {
    handle: Arc<Handle>,
}

impl Receiver {
    /// Ledger creation/opening belongs off the GUI thread at service startup.
    pub fn start(ledger: Ledger, mut adapter: impl InputAdapter) -> Result<Self> {
        let image_store = ledger.image_store.clone();
        let (sender, commands) = mpsc::sync_channel::<Command>(64);
        let (input, jobs) = mpsc::sync_channel::<Work>(1);
        let paused = Arc::new(InputGate::new());
        let input_paused = paused.clone();
        let results = sender.clone();
        thread::Builder::new()
            .name("voicelink-input".into())
            .spawn(move || {
                while let Ok(work) = jobs.recv() {
                    let job = match work {
                        Work::Prepare(job) => {
                            let result = if !job.image_authorized() {
                                Err("image_session_ended")
                            } else if input_paused.is_paused() {
                                Err("paused")
                            } else {
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    adapter.preflight(&job)
                                }))
                                .unwrap_or(Err("preflight_panicked"))
                            };
                            let target = if result.is_ok() {
                                adapter.prepared_application()
                            } else {
                                None
                            };
                            let window = if result.is_ok() {
                                adapter.prepared_window()
                            } else {
                                None
                            };
                            if results
                                .send(Command::Prepared(job, result, target, window))
                                .is_err()
                            {
                                break;
                            }
                            continue;
                        }
                        Work::Execute(job) => job,
                    };
                    let outcome = if !job.image_authorized() {
                        InputOutcome::Failed {
                            reason: "image_session_ended",
                        }
                    } else if input_paused.is_paused() {
                        InputOutcome::Failed {
                            reason: "paused_before_input",
                        }
                    } else if let crate::protocol::Operation::AutoText { application, .. } =
                        job.operation()
                        && adapter
                            .prepared_application()
                            .and_then(|target| target.display)
                            .is_none_or(|display| display.name != *application)
                    {
                        InputOutcome::Failed {
                            reason: "automatic_target_changed",
                        }
                    } else {
                        // A panic cannot prove how many events were inserted.
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            adapter.execute(&job, &input_paused)
                        }))
                        .unwrap_or(InputOutcome::Uncertain {
                            reason: "input_worker_panicked",
                        })
                    };
                    if results.send(Command::Finished(job, outcome)).is_err() {
                        break;
                    }
                }
            })?;
        let coordinator_paused = paused.clone();
        thread::Builder::new().name("voicelink-ledger".into()).spawn(move || {
            let mut owner = Coordinator { ledger, paused: coordinator_paused, input, in_flight:false, shutting_down:false, observers:Vec::new() };
            let mut pending = None;
            let mut shutdown_reply=None;
            let mut needs_dispatch=false;
            let mut next_history_cleanup=Instant::now();
            loop {
                if Instant::now()>=next_history_cleanup {
                    match owner.ledger.clean_history(crate::history::now_millis()) {
                        Ok(true)=>owner.notify(),Ok(false)=>{},
                        Err(error)=>eprintln!("History cleanup failed: {error}"),
                    }
                    next_history_cleanup=Instant::now()+Duration::from_secs(1);
                }
                let command = match pending.take() {
                    Some(command) => command,
                    None => match commands.recv_timeout(Duration::from_millis(250)) {
                        Ok(command) => command,
                        Err(mpsc::RecvTimeoutError::Timeout) => {
                            match owner.ledger.expire_pending(owner.ledger.now()) {
                                Ok(true) => { owner.dispatch(); owner.notify(); },
                                Ok(false) => {},
                                Err(error) => { owner.paused.pause(); let _=owner.ledger.pause(); eprintln!("Pending expiry could not be committed: {error}"); },
                            }
                            continue;
                        },
                        Err(mpsc::RecvTimeoutError::Disconnected) => break,
                    },
                };
                match owner.ledger.expire_pending(owner.ledger.now()) {
                    Ok(true) => { needs_dispatch=true; owner.notify(); },
                    Ok(false) => {},
                    Err(error) => { owner.paused.pause(); let _=owner.ledger.pause(); eprintln!("Pending expiry could not be committed: {error}"); },
                }
                let changed = !matches!(&command, Command::Request(_, false));
                needs_dispatch |= changed;
                match command {
                    Command::Request(request, _) => request(&mut owner),
                    Command::Prepared(job,result,target,window) => {
                        owner.ledger.observe_request(job.operation(), job.id(), "preflight_returned");
                        if owner.paused.is_paused() || owner.shutting_down || result.is_err() {
                            let reason=if owner.paused.is_paused() { "paused" } else { result.err().unwrap_or("shutdown") };
                            let _=owner.ledger.hold(&job,reason); owner.in_flight=false;
                        } else {
                            match owner.ledger.mark_executing_in(&job,target,window) {
                                Ok(true)=>{
                                    if let Err(error)=owner.input.try_send(Work::Execute(job)) {
                                        let work=match error { mpsc::TrySendError::Full(work)|mpsc::TrySendError::Disconnected(work)=>work };
                                        let job=match work { Work::Prepare(job)|Work::Execute(job)=>job };
                                        let _=owner.ledger.finish(&job,InputOutcome::Failed{reason:"input_worker_unavailable"});
                                        owner.in_flight=false;
                                    }
                                }
                                Ok(false)=>owner.in_flight=false,
                                Err(error)=>{
                                    eprintln!("Execution marker could not be committed: {error}");
                                    owner.paused.pause(); let _=owner.ledger.pause(); owner.in_flight=false;
                                }
                            }
                        }
                    }
                    Command::Finished(job,outcome) => {
                        owner.ledger.observe_request(job.operation(), job.id(), "outcome_returned");
                        if let Err(error) = owner.ledger.finish(&job,outcome) {
                            owner.paused.pause();
                            eprintln!("Input outcome could not be committed; manual review required: {error}");
                        }
                        if owner.ledger.state().map_or(true, |state| state.paused) { owner.paused.pause(); }
                        owner.in_flight=false;
                    }
                    Command::Shutdown(reply) => {
                        shutdown_reply=reply;
                        owner.shutting_down=true; owner.paused.pause();
                        let _ = owner.ledger.pause();
                    }
                }
                if changed { owner.notify(); }
                if owner.shutting_down && !owner.in_flight { break; }
                // Give queued controls priority over starting an input; no
                // global mutex is held while SQLite commits or input runs.
                match commands.try_recv() {
                    Ok(command) => pending=Some(command),
                    Err(mpsc::TryRecvError::Empty) => {
                        if needs_dispatch { owner.dispatch(); owner.notify(); needs_dispatch=false; }
                    }
                    Err(mpsc::TryRecvError::Disconnected) => break,
                }
            }
            drop(owner);
            if let Some(reply)=shutdown_reply { let _=reply.send(()); }
        })?;
        Ok(Self {
            handle: Arc::new(Handle {
                image_store,
                sender,
                paused,
                closed: AtomicBool::new(false),
            }),
        })
    }

    async fn call<T: Send + 'static>(
        &self,
        operation: impl FnOnce(&mut Coordinator) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        self.request(operation, false).await
    }

    async fn mutate<T: Send + 'static>(
        &self,
        operation: impl FnOnce(&mut Coordinator) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        self.request(operation, true).await
    }

    async fn request<T: Send + 'static>(
        &self,
        operation: impl FnOnce(&mut Coordinator) -> Result<T> + Send + 'static,
        changed: bool,
    ) -> Result<T> {
        if self.handle.closed.load(Ordering::Acquire) {
            return Err(Error::Unavailable);
        }
        let (sender, reply) = oneshot::channel();
        self.handle
            .sender
            .try_send(Command::Request(
                Box::new(move |owner| {
                    let _ = sender.send(operation(owner));
                }),
                changed,
            ))
            .map_err(|_| Error::Protocol("RECEIVER_BUSY"))?;
        reply.await.map_err(|_| Error::Unavailable)?
    }

    pub async fn state(&self) -> Result<ControlState> {
        self.call(|owner| owner.ledger.state()).await
    }
    pub async fn check_session(&self, session: Session) -> Result<()> {
        self.call(move |owner| owner.ledger.check_session(&session))
            .await
    }

    pub async fn snapshot(&self) -> Result<ReceiverSnapshot> {
        self.call(|owner| owner.ledger.snapshot()).await
    }

    pub async fn history(
        &self,
        query: crate::history::HistoryQuery,
    ) -> Result<crate::history::HistoryPage> {
        self.call(move |owner| owner.ledger.history(&query)).await
    }
    pub async fn review_history(&self) -> Result<Option<crate::history::HistoryEntry>> {
        self.call(|owner| owner.ledger.review_history()).await
    }
    /// Resolve a still-visible, authenticated history reference on the ledger
    /// owner. File decryption/decoding belongs on the caller's background worker.
    pub async fn history_image(
        &self,
        order: i64,
    ) -> Result<Option<(String, crate::images::ImageReference)>> {
        self.call(move |owner| {
            let query = crate::history::HistoryQuery {
                before: order.checked_add(1),
                limit: 1,
                ..Default::default()
            };
            let page = owner.ledger.history(&query)?;
            Ok(page
                .entries
                .into_iter()
                .find(|e| e.receipt.order == order.to_string())
                .and_then(|e| {
                    if let Some(crate::protocol::Operation::Image { image }) = e.operation {
                        Some((e.device, image))
                    } else {
                        None
                    }
                }))
        })
        .await
    }

    pub async fn history_policy(&self) -> Result<crate::history::HistoryPolicy> {
        self.call(|owner| owner.ledger.history_policy()).await
    }
    pub async fn set_history_policy(&self, policy: crate::history::HistoryPolicy) -> Result<()> {
        self.mutate(move |owner| owner.ledger.set_history_policy(policy))
            .await
    }
    pub async fn delete_history(&self, order: i64) -> Result<bool> {
        self.mutate(move |owner| owner.ledger.delete_history(order))
            .await
    }
    pub async fn restore_history(&self, order: i64) -> Result<bool> {
        self.mutate(move |owner| owner.ledger.restore_history(order))
            .await
    }

    /// Each wake means "read the newest snapshot", not a replayable event.
    pub async fn subscribe(&self) -> Result<notifications::Receiver<()>> {
        self.call(|owner| {
            owner.observers.retain(|observer| !observer.is_closed());
            if owner.observers.len() >= 16 {
                return Err(Error::Protocol("OBSERVER_LIMIT"));
            }
            let (mut sender, receiver) = notifications::channel(1);
            let _ = sender.try_send(());
            owner.observers.push(sender);
            Ok(receiver)
        })
        .await
    }

    /// Wait for any already-started input's durable outcome, then close SQLite.
    /// All cloned handles stop accepting work immediately.
    pub async fn shutdown(self) -> Result<()> {
        self.handle.paused.pause();
        self.handle
            .closed
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| Error::Unavailable)?;
        let (sender, reply) = oneshot::channel();
        if let Err(error) = self.handle.sender.try_send(Command::Shutdown(Some(sender))) {
            match error {
                mpsc::TrySendError::Full(command) => {
                    let queue = self.handle.sender.clone();
                    if let Err(error) = thread::Builder::new()
                        .name("voicelink-shutdown".into())
                        .spawn(move || {
                            let _ = queue.send(command);
                        })
                    {
                        self.handle.closed.store(false, Ordering::Release);
                        return Err(error.into());
                    }
                }
                mpsc::TrySendError::Disconnected(_) => return Err(Error::Unavailable),
            }
        }
        reply.await.map_err(|_| Error::Unavailable)
    }
    pub async fn provision_device(
        &self,
        device: String,
        token: zeroize::Zeroizing<Vec<u8>>,
        name: String,
    ) -> Result<()> {
        self.mutate(move |owner| owner.ledger.provision_device(&device, &token, name))
            .await
    }
    pub async fn verify_pair_token(
        &self,
        device: String,
        token: zeroize::Zeroizing<Vec<u8>>,
    ) -> Result<()> {
        self.call(move |owner| owner.ledger.verify_pair_token(&device, &token))
            .await
    }

    pub async fn replace_pair_by_token(
        &self,
        old_pair: String,
        old_token: zeroize::Zeroizing<Vec<u8>>,
        new_pair: String,
        new_token: zeroize::Zeroizing<Vec<u8>>,
        name: String,
    ) -> Result<()> {
        self.mutate(move |owner| {
            owner
                .ledger
                .replace_pair_by_token(&old_pair, &old_token, &new_pair, &new_token, name)
        })
        .await
    }
    pub async fn authenticate(
        &self,
        device: String,
        token: zeroize::Zeroizing<Vec<u8>>,
    ) -> Result<Session> {
        self.call(move |owner| owner.ledger.authenticate(&device, &token))
            .await
    }
    pub async fn authenticate_named(
        &self,
        device: String,
        token: zeroize::Zeroizing<Vec<u8>>,
        name: String,
    ) -> Result<Session> {
        // Display metadata publishes a fresh snapshot without requesting input
        // dispatch; authenticating/renaming is never a send or retry command.
        self.call(move |owner| {
            let session = owner.ledger.authenticate_named(&device, &token, &name)?;
            owner.notify();
            Ok(session)
        })
        .await
    }
    pub async fn submit(&self, session: Session, submit: Submit) -> Result<Receipt> {
        self.mutate(move |owner| owner.ledger.submit(&session, &submit))
            .await
    }
    pub fn image_store(&self) -> Option<crate::images::storage::ImageStore> {
        self.handle.image_store.clone()
    }
    pub async fn register_image(
        &self,
        session: Session,
        scope: crate::images::wire::UploadScope,
        image: crate::images::storage::PreparedResource,
        authorization: Arc<crate::images::ImageAuthorization>,
    ) -> Result<()> {
        self.call(move |owner| {
            owner
                .ledger
                .register_image(&session, scope, image, authorization)
        })
        .await
    }
    pub async fn discard_image(&self, session: Session, id: String) -> Result<bool> {
        self.call(move |owner| owner.ledger.discard_image(&session, &id))
            .await
    }
    pub async fn query(&self, session: Session, id: String) -> Result<Option<Receipt>> {
        self.call(move |owner| owner.ledger.query(&session, &id))
            .await
    }
    /// Close the input gate immediately, even before the returned future is
    /// polled. Await completion for the durable pause and its state revision.
    pub fn pause(
        &self,
    ) -> impl std::future::Future<Output = Result<ControlState>> + Send + 'static {
        self.handle.paused.pause();
        let receiver = self.clone();
        async move { receiver.mutate(|owner| owner.ledger.pause()).await }
    }
    pub async fn bind_target(&self, target: String) -> Result<ControlState> {
        self.handle.paused.pause();
        self.mutate(move |owner| {
            owner.paused.pause();
            owner.ledger.bind_target(&target)
        })
        .await
    }
    /// Fence input immediately; keep the durable distinction from a user pause.
    pub fn network_lost(
        &self,
    ) -> impl std::future::Future<Output = Result<ControlState>> + Send + 'static {
        self.handle.paused.pause();
        let receiver = self.clone();
        async move { receiver.mutate(|owner| owner.ledger.network_lost()).await }
    }
    pub async fn resume(
        &self,
        expected_rev: String,
        locally_reviewed: bool,
    ) -> Result<ControlState> {
        self.set_input_enabled(expected_rev, locally_reviewed, false)
            .await
    }

    /// Acknowledges exactly the uncertain receipt represented by the local
    /// review ticket, then starts a fresh foreground activation scope.
    pub async fn enable_foreground_input_reviewed(
        &self,
        ticket: ReviewTicket,
    ) -> Result<ControlState> {
        self.handle.paused.pause();
        let fence = self.handle.paused.snapshot();
        self.mutate(move |owner| {
            if owner.paused.snapshot() != fence {
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            let current = owner.ledger.state()?;
            let current_ticket = owner.ledger.review_ticket()?;
            if current.state_rev != ticket.state_rev().to_string()
                || current_ticket.as_ref() != Some(&ticket)
            {
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            owner.ledger.acknowledge_uncertain(&ticket)?;
            let revision = owner
                .ledger
                .bind_target(&uuid::Uuid::new_v4().to_string())?
                .state_rev;
            let state = owner.ledger.resume(&revision, false)?;
            if !owner.paused.reopen(fence) {
                owner.ledger.pause()?;
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            Ok(state)
        })
        .await
    }

    /// Local activation of current-cursor input. A fresh scope prevents held
    /// operations from an earlier activation following the cursor to a new app.
    pub async fn enable_foreground_input(
        &self,
        expected_rev: String,
        locally_reviewed: bool,
    ) -> Result<ControlState> {
        self.set_input_enabled(expected_rev, locally_reviewed, true)
            .await
    }

    async fn set_input_enabled(
        &self,
        expected_rev: String,
        locally_reviewed: bool,
        fresh_scope: bool,
    ) -> Result<ControlState> {
        let fence = self.handle.paused.snapshot();
        self.mutate(move |owner| {
            if owner.paused.snapshot() != fence {
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            let revision = if fresh_scope {
                let current = owner.ledger.state()?;
                if !current.paused
                    || current.state_rev != expected_rev
                    || current.active_device_id.is_none()
                    || (current.pause_reason.as_deref() == Some("outcome_review_required")
                        && !locally_reviewed)
                {
                    return Err(Error::Protocol("STATE_CONFLICT"));
                }
                owner
                    .ledger
                    .bind_target(&uuid::Uuid::new_v4().to_string())?
                    .state_rev
            } else {
                expected_rev
            };
            let state = owner.ledger.resume(&revision, locally_reviewed)?;
            if !owner.paused.reopen(fence) {
                // A later local pause may arrive during the SQLite commit.
                // Keep the gate closed and restore durable paused state.
                owner.ledger.pause()?;
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            Ok(state)
        })
        .await
    }
    pub async fn cancel(&self, session: Session, id: String) -> Result<Receipt> {
        self.mutate(move |owner| owner.ledger.cancel(&session, &id))
            .await
    }
    pub async fn release(
        &self,
        session: Session,
        id: String,
        target: String,
        expected_rev: String,
    ) -> Result<Receipt> {
        self.release_checked(session, id, target, expected_rev, None)
            .await
    }
    pub async fn release_checked(
        &self,
        session: Session,
        id: String,
        target: String,
        expected_rev: String,
        expected_request_rev: Option<String>,
    ) -> Result<Receipt> {
        self.mutate(move |owner| {
            owner.ledger.release_checked(
                &session,
                &id,
                &target,
                &expected_rev,
                expected_request_rev.as_deref(),
            )
        })
        .await
    }
    pub async fn revoke(&self, device: String) -> Result<()> {
        self.handle.paused.pause();
        self.mutate(move |owner| {
            owner.paused.pause();
            // Credential removal is a local security configuration change.
            // Persist the conservative gate even for an inactive device.
            owner.ledger.pause()?;
            owner.ledger.revoke(&device)
        })
        .await
    }
}
