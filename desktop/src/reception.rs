//! Application-owned receiver lifecycle and local commands, independent of UI locale.
use futures_util::StreamExt as _;
use std::{path::PathBuf, sync::Arc};
use voicelink_receiver::{
    history::{HistoryPage, HistoryQuery},
    ledger::ReceiverSnapshot,
    windows_input::{NativeInput, open_ledger},
    worker::Receiver,
};
use voicelink_transport::{
    HostIdentity,
    interfaces::{NetworkAuthorization, NetworkInterface, available_interfaces},
    server::{NetworkService, NetworkSnapshot},
    storage::secure_directory,
};
use yue_component::gpui_kit::{AppContext as _, Context, Image, ImageFormat, Task};

#[cfg(test)]
mod tests;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReceptionPhase {
    Loading,
    Ready,
    Failed,
    Stopping,
    Stopped,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReceptionIssue {
    Startup,
    Command,
    Storage,
    Network,
}

/// One chooser owns both the selected identity and its ephemeral consent.
#[derive(Default)]
struct NetworkSelection {
    interfaces: Vec<NetworkInterface>,
    selected: Option<String>,
    authorization: Option<NetworkAuthorization>,
}
impl NetworkSelection {
    fn replace(&mut self, interfaces: Vec<NetworkInterface>) {
        *self = Self {
            interfaces,
            ..Self::default()
        };
    }
    fn selected(&self) -> Option<&NetworkInterface> {
        self.interfaces
            .iter()
            .find(|i| Some(i.key().as_str()) == self.selected.as_deref())
    }
    fn select(&mut self, key: &str) {
        if self.selected.as_deref() != Some(key) && self.interfaces.iter().any(|i| i.key() == key) {
            self.selected = Some(key.to_owned());
            self.authorize_public(false);
        }
    }
    fn authorize_public(&mut self, consent: bool) {
        self.authorization = self.selected().and_then(|i| i.authorize(consent).ok());
    }
}

pub struct ReceptionState {
    directory: PathBuf,
    phase: ReceptionPhase,
    snapshot: Option<ReceiverSnapshot>,
    issue: Option<ReceptionIssue>,
    receiver: Option<Receiver>,
    closing: bool,
    startup: Option<Task<()>>,
    watch: Option<Task<()>>,
    command: Option<Task<()>>,
    identity: Option<HostIdentity>,
    selection: NetworkSelection,
    network: Option<NetworkService>,
    network_snapshot: Option<NetworkSnapshot>,
    pairing_image: Option<Arc<Image>>,
    network_task: Option<Task<()>>,
    network_watch: Option<Task<()>>,
    network_requested: bool,
    revoking: Option<String>,
    history: HistoryPage,
    latest: HistoryPage,
    history_query: HistoryQuery,
    history_task: Option<Task<()>>,
    history_generation: u64,
    history_dirty: bool,
    history_failed: bool,
    bootstrapped: bool,
    automatic_ready: bool,
    preferred_interface: Option<String>,
    background_available: bool,
}

impl ReceptionState {
    pub fn new(directory: PathBuf, cx: &mut Context<Self>) -> Self {
        Self::create(directory, true, cx)
    }
    /// Offline inspection and verification; never discovers or starts a listener.
    pub fn local_only(directory: PathBuf, cx: &mut Context<Self>) -> Self {
        Self::create(directory, false, cx)
    }
    fn create(directory: PathBuf, start_network: bool, cx: &mut Context<Self>) -> Self {
        let mut this = Self {
            directory,
            phase: ReceptionPhase::Loading,
            snapshot: None,
            issue: None,
            receiver: None,
            closing: false,
            startup: None,
            watch: None,
            command: None,
            identity: None,
            selection: NetworkSelection::default(),
            network: None,
            network_snapshot: None,
            pairing_image: None,
            network_task: None,
            network_watch: None,
            network_requested: false,
            revoking: None,
            history: HistoryPage::default(),
            latest: HistoryPage::default(),
            history_query: HistoryQuery::default(),
            history_task: None,
            history_generation: 0,
            history_dirty: false,
            history_failed: false,
            bootstrapped: !start_network,
            automatic_ready: true,
            preferred_interface: None,
            background_available: false,
        };
        this.start(cx);
        this
    }

    fn start(&mut self, cx: &mut Context<Self>) {
        self.phase = ReceptionPhase::Loading;
        self.issue = None;
        let directory = self.directory.clone();
        let opening = cx.background_spawn(async move {
            secure_directory(&directory)?;
            let ledger = open_ledger(&directory)?;
            let identity = HostIdentity::open(&directory, ledger.snapshot()?.paired_devices)?;
            Ok::<_, voicelink_transport::Error>((
                Receiver::start(ledger, NativeInput::default())?,
                identity,
                std::fs::read_to_string(directory.join("network-interface.txt"))
                    .ok()
                    .filter(|s| s.len() < 256),
            ))
        });
        self.startup = Some(cx.spawn(async move |owner, cx| {
            let outcome = opening.await;
            let _ = owner.update(cx, |this, cx| {
                this.startup = None;
                match outcome {
                    Ok((receiver, identity, preferred_interface)) => {
                        this.preferred_interface = preferred_interface;
                        this.identity = Some(identity);
                        this.receiver = Some(receiver.clone());
                        if this.closing {
                            this.stop(cx);
                        } else {
                            this.observe(receiver, cx);
                        }
                    }
                    Err(error) => {
                        eprintln!("Receiver startup: {error}");
                        this.phase = if this.closing {
                            ReceptionPhase::Stopped
                        } else {
                            ReceptionPhase::Failed
                        };
                        this.issue = Some(ReceptionIssue::Startup);
                    }
                }
                cx.notify();
            });
        }));
    }

    fn observe(&mut self, receiver: Receiver, cx: &mut Context<Self>) {
        self.watch = Some(cx.spawn(async move |owner, cx| {
            let mut notifications = match receiver.subscribe().await {
                Ok(notifications) => notifications,
                Err(error) => {
                    eprintln!("Receiver subscription: {error}");
                    let _ = owner.update(cx, |this, cx| {
                        this.phase = ReceptionPhase::Failed;
                        this.issue = Some(ReceptionIssue::Command);
                        cx.notify();
                    });
                    return;
                }
            };
            while notifications.next().await.is_some() {
                let snapshot = receiver.snapshot().await;
                if owner
                    .update(cx, |this, cx| {
                        if this.closing {
                            return;
                        }
                        match snapshot {
                            Ok(snapshot) => {
                                this.phase = ReceptionPhase::Ready;
                                if !snapshot.storage_healthy {
                                    this.issue = Some(ReceptionIssue::Storage);
                                }
                                this.snapshot = Some(snapshot);
                                this.refresh_history(cx);
                                if !this.bootstrapped {
                                    this.bootstrapped = true;
                                    if this.snapshot.as_ref().is_some_and(|s| s.paired_devices > 0)
                                    {
                                        this.discover_network(true, cx);
                                    }
                                }
                                this.maybe_enable(cx);
                            }
                            Err(error) => {
                                eprintln!("Receiver snapshot: {error}");
                                this.issue = Some(ReceptionIssue::Command);
                            }
                        }
                        cx.notify();
                    })
                    .is_err()
                {
                    break;
                }
            }
        }));
    }

    pub fn background_available(&self) -> bool {
        self.background_available
    }
    pub fn set_background_available(&mut self, available: bool, cx: &mut Context<Self>) {
        self.background_available = available;
        cx.notify();
    }
    pub fn phase(&self) -> ReceptionPhase {
        self.phase
    }
    pub fn snapshot(&self) -> Option<&ReceiverSnapshot> {
        self.snapshot.as_ref()
    }
    pub fn issue(&self) -> Option<ReceptionIssue> {
        self.issue
    }
    pub fn is_busy(&self) -> bool {
        self.command.is_some() || self.network_task.is_some()
    }
    pub fn is_stopped(&self) -> bool {
        self.phase == ReceptionPhase::Stopped
    }
    pub fn is_ready(&self) -> bool {
        self.phase == ReceptionPhase::Ready
            && self.snapshot.as_ref().is_some_and(|s| s.storage_healthy)
    }

    pub fn retry_start(&mut self, cx: &mut Context<Self>) {
        // A running but unhealthy receiver must be closed/reopened deliberately;
        // never destroy its identity or silently create a new ledger.
        if self.phase == ReceptionPhase::Failed && self.receiver.is_none() && !self.closing {
            self.start(cx);
            cx.notify();
        }
    }

    pub fn revoking(&self) -> Option<&str> {
        self.revoking.as_deref()
    }
    pub fn request_revoke(&mut self, device: String, cx: &mut Context<Self>) {
        if self.is_ready()
            && self
                .snapshot
                .as_ref()
                .is_some_and(|s| s.device_ids.contains(&device))
        {
            self.revoking = Some(device);
            cx.notify();
        }
    }
    pub fn cancel_revoke(&mut self, cx: &mut Context<Self>) {
        self.revoking = None;
        cx.notify();
    }
    pub fn confirm_revoke(&mut self, cx: &mut Context<Self>) {
        if !self.is_ready() || self.is_busy() || self.closing {
            return;
        }
        let (Some(device), Some(receiver)) = (self.revoking.take(), self.receiver.clone()) else {
            return;
        };
        self.command = Some(cx.spawn(async move |owner, cx| {
            let result = receiver.revoke(device).await;
            let _ = owner.update(cx, |this, cx| {
                this.command = None;
                this.issue = result.err().map(|_| ReceptionIssue::Command);
                cx.notify();
            });
        }));
        cx.notify();
    }

    pub fn pause(&mut self, cx: &mut Context<Self>) {
        if !self.is_ready() || self.closing {
            return;
        }
        let Some(receiver) = self.receiver.clone() else {
            return;
        };
        self.automatic_ready = false;
        let pausing = receiver.pause();
        self.command = Some(cx.spawn(async move |owner, cx| {
            let result = pausing.await;
            let _ = owner.update(cx, |this, cx| {
                this.command = None;
                match result {
                    Ok(_) => this.issue = None,
                    Err(error) => {
                        eprintln!("Pause receiver: {error}");
                        this.issue = Some(ReceptionIssue::Command);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }

    pub fn resume(&mut self, reviewed: bool, cx: &mut Context<Self>) {
        if !self.is_ready() || self.is_busy() || self.closing {
            return;
        }
        let Some(snapshot) = self.snapshot.as_ref() else {
            return;
        };
        if snapshot.control.active_device_id.is_none() {
            return;
        }
        let revision = snapshot.control.state_rev.clone();
        let Some(receiver) = self.receiver.clone() else {
            return;
        };
        self.command = Some(cx.spawn(async move |owner, cx| {
            let result = receiver.enable_foreground_input(revision, reviewed).await;
            let _ = owner.update(cx, |this, cx| {
                this.command = None;
                match result {
                    Ok(_) => this.issue = None,
                    Err(error) => {
                        eprintln!("Resume receiver: {error}");
                        this.issue = Some(ReceptionIssue::Command);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }

    pub fn stop(&mut self, cx: &mut Context<Self>) {
        if self.is_stopped() || (self.closing && self.command.is_some()) {
            return;
        }
        self.closing = true;
        self.phase = ReceptionPhase::Stopping;
        self.watch = None;
        self.history_task = None;
        self.command = None;
        self.network_task = None;
        self.network_watch = None;
        self.selection = NetworkSelection::default();
        self.network_snapshot = None;
        self.pairing_image = None;
        let network = self.network.take();
        if let Some(receiver) = self.receiver.take() {
            self.command = Some(cx.spawn(async move |owner, cx| {
                if let Some(network) = network {
                    let _ = network.shutdown().await;
                }
                let outcome = receiver.shutdown().await;
                let _ = owner.update(cx, |this, cx| {
                    if let Err(error) = outcome {
                        eprintln!("Stop receiver: {error}");
                    }
                    this.command = None;
                    this.phase = ReceptionPhase::Stopped;
                    cx.notify();
                });
            }));
        } else if self.startup.is_none() {
            self.phase = ReceptionPhase::Stopped;
        }
        cx.notify();
    }

    pub fn network_snapshot(&self) -> Option<&NetworkSnapshot> {
        self.network_snapshot.as_ref()
    }
    pub fn pairing_image(&self) -> Option<Arc<Image>> {
        self.pairing_image.clone()
    }
    pub fn interfaces(&self) -> &[NetworkInterface] {
        &self.selection.interfaces
    }
    pub fn selected_interface(&self) -> Option<&NetworkInterface> {
        self.selection.selected()
    }
    pub fn network_authorized(&self) -> bool {
        self.selection.authorization.is_some()
    }
    pub fn network_requested(&self) -> bool {
        self.network_requested
    }
    pub fn select_interface(&mut self, key: &str, cx: &mut Context<Self>) {
        if self.network.is_none() && !self.is_busy() && !self.closing {
            self.selection.select(key);
            cx.notify();
        }
    }
    pub fn authorize_public_network(&mut self, consent: bool, cx: &mut Context<Self>) {
        if self.network.is_none() && !self.is_busy() && !self.closing {
            self.selection.authorize_public(consent);
            cx.notify();
        }
    }
    pub fn prepare_network(&mut self, cx: &mut Context<Self>) {
        if !self.is_ready() || self.is_busy() || self.closing {
            return;
        }
        self.network_requested = true;
        if self.network.is_some() {
            self.open_pairing(cx);
            return;
        }
        self.discover_network(false, cx);
    }
    fn discover_network(&mut self, automatic: bool, cx: &mut Context<Self>) {
        self.selection = NetworkSelection::default();
        let operation = cx.background_spawn(async { available_interfaces() });
        self.network_task = Some(cx.spawn(async move |owner, cx| {
            let result = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.network_task = None;
                if this.closing {
                    return;
                }
                match result {
                    Ok(interfaces) => {
                        this.selection.replace(interfaces);
                        this.issue = None;
                        if automatic {
                            let selected = this
                                .preferred_interface
                                .as_ref()
                                .and_then(|key| {
                                    this.selection.interfaces.iter().find(|i| i.key() == *key)
                                })
                                .or_else(|| {
                                    (this.selection.interfaces.len() == 1)
                                        .then(|| &this.selection.interfaces[0])
                                })
                                .map(NetworkInterface::key);
                            if let Some(key) = selected {
                                this.selection.select(&key);
                                if this.selection.authorization.is_some() {
                                    this.start_network(cx);
                                } else {
                                    this.network_requested = true;
                                }
                            } else {
                                this.network_requested = true;
                            }
                        }
                    }
                    Err(error) => {
                        this.network_requested = true;
                        eprintln!("Read network profiles: {error}");
                        this.issue = Some(ReceptionIssue::Network);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }
    pub fn start_network(&mut self, cx: &mut Context<Self>) {
        if !self.is_ready() || self.is_busy() || self.closing {
            return;
        }
        let (Some(authorization), Some(receiver), Some(identity)) = (
            self.selection.authorization.clone(),
            self.receiver.clone(),
            self.identity.clone(),
        ) else {
            return;
        };
        let interface = self.selection.selected.clone();
        let preference_path = self.directory.join("network-interface.txt");
        let operation = cx.background_spawn(async move {
            let service = match NetworkService::start_on_network(
                receiver.clone(),
                identity.clone(),
                authorization.clone(),
                7788,
            ) {
                Ok(service) => Ok(service),
                Err(voicelink_transport::Error::Io(error))
                    if error.kind() == std::io::ErrorKind::AddrInUse =>
                {
                    NetworkService::start_on_network(receiver, identity, authorization, 0)
                }
                Err(error) => Err(error),
            }?;
            // Only interface identity is remembered. Public consent remains session-only.
            if let Some(key) = interface {
                use std::io::Write as _;
                let saved = (|| -> std::io::Result<()> {
                    let mut file = atomic_write_file::AtomicWriteFile::open(&preference_path)?;
                    file.write_all(key.as_bytes())?;
                    file.commit()
                })();
                if let Err(error) = saved {
                    eprintln!("Remember network interface: {error}");
                }
            }
            Ok::<_, voicelink_transport::Error>(service)
        });
        self.network_task = Some(cx.spawn(async move |owner, cx| {
            let result = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.network_task = None;
                if this.closing {
                    return;
                }
                match result {
                    Ok(service) => {
                        this.network = Some(service.clone());
                        this.observe_network(service, cx);
                        if this.network_requested {
                            this.open_pairing(cx);
                        }
                        this.maybe_enable(cx);
                    }
                    Err(error) => {
                        eprintln!("Start network service: {error}");
                        this.selection = NetworkSelection::default();
                        this.issue = Some(ReceptionIssue::Network);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }
    fn observe_network(&mut self, service: NetworkService, cx: &mut Context<Self>) {
        let Ok(mut notifications) = service.subscribe() else {
            self.issue = Some(ReceptionIssue::Network);
            return;
        };
        self.network_watch = Some(cx.spawn(async move |owner, cx| {
            while notifications.next().await.is_some() {
                let result = service.snapshot();
                if owner
                    .update(cx, |this, cx| {
                        if let Ok(snapshot) = result {
                            if snapshot.running {
                                let previous = this
                                    .network_snapshot
                                    .as_ref()
                                    .and_then(|s| s.window.as_ref())
                                    .map(|w| &w.id);
                                let current = snapshot.window.as_ref().map(|w| &w.id);
                                if previous != current {
                                    // Cache once per window. Unlike monochrome SVG icons,
                                    // Image preserves QR contrast and the opaque quiet zone.
                                    this.pairing_image = snapshot.window.as_ref().map(|w| {
                                        Arc::new(Image::from_bytes(
                                            ImageFormat::Svg,
                                            w.qr_svg.to_vec(),
                                        ))
                                    });
                                }
                                this.network_snapshot = Some(snapshot);
                                this.maybe_enable(cx);
                            } else {
                                this.network = None;
                                this.network_snapshot = None;
                                this.pairing_image = None;
                                this.selection = NetworkSelection::default();
                                this.automatic_ready = false;
                                this.issue = Some(ReceptionIssue::Network);
                            }
                        }
                        cx.notify();
                    })
                    .is_err()
                {
                    break;
                }
            }
        }));
    }
    pub fn open_pairing(&mut self, cx: &mut Context<Self>) {
        if self.network_task.is_some() || self.closing {
            return;
        }
        let Some(service) = self.network.clone() else {
            return;
        };
        let operation = cx.background_spawn(async move { service.open_pairing().await });
        self.network_task = Some(cx.spawn(async move |owner, cx| {
            let result = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.network_task = None;
                if result.is_err() {
                    this.issue = Some(ReceptionIssue::Network);
                }
                this.maybe_enable(cx);
                cx.notify();
            });
        }));
        cx.notify();
    }
    pub fn close_pairing(&mut self, cx: &mut Context<Self>) {
        self.network_requested = false;
        cx.notify();
        let Some(service) = self.network.clone() else {
            return;
        };
        cx.spawn(async move |_, _| {
            service.close_pairing().await;
        })
        .detach();
    }
    pub fn approve_pairing(&mut self, id: String, approved: bool, cx: &mut Context<Self>) {
        let Some(service) = self.network.clone() else {
            return;
        };
        cx.spawn(async move |owner, cx| {
            let result = service.decide(&id, approved).await;
            let _ = owner.update(cx, |this, cx| {
                if result.is_err() {
                    this.issue = Some(ReceptionIssue::Network);
                }
                cx.notify();
            });
        })
        .detach();
    }
    fn maybe_enable(&mut self, cx: &mut Context<Self>) {
        if self.automatic_ready
            && self.network.is_some()
            && self.is_ready()
            && !self.is_busy()
            && self.snapshot.as_ref().is_some_and(|s| {
                s.control.paused
                    && s.control.active_device_id.is_some()
                    && s.control.pause_reason.as_deref() == Some("startup")
            })
        {
            self.automatic_ready = false;
            self.resume(false, cx);
        }
    }
    pub fn history(&self) -> &HistoryPage {
        &self.history
    }
    pub fn latest(&self) -> &HistoryPage {
        &self.latest
    }
    pub fn history_query(&self) -> &HistoryQuery {
        &self.history_query
    }
    pub fn history_loading(&self) -> bool {
        self.history_task.is_some()
    }
    pub fn history_failed(&self) -> bool {
        self.history_failed
    }
    pub fn search_history(&mut self, search: String, cx: &mut Context<Self>) {
        self.history_query.search = search.chars().take(256).collect();
        self.history_query.before = None;
        self.history_generation = self.history_generation.wrapping_add(1);
        self.refresh_history(cx);
    }
    pub fn filter_history(
        &mut self,
        state: Option<voicelink_receiver::protocol::State>,
        cx: &mut Context<Self>,
    ) {
        self.history_query.state = state;
        self.first_history(cx);
    }
    pub fn first_history(&mut self, cx: &mut Context<Self>) {
        self.history_query.before = None;
        self.history_generation = self.history_generation.wrapping_add(1);
        self.refresh_history(cx);
    }
    pub fn next_history(&mut self, cx: &mut Context<Self>) {
        if let Some(next) = self.history.next {
            self.history_query.before = Some(next);
            self.history_generation = self.history_generation.wrapping_add(1);
            self.refresh_history(cx);
        }
    }
    pub fn refresh_history(&mut self, cx: &mut Context<Self>) {
        if self.closing {
            return;
        }
        if self.history_task.is_some() {
            self.history_dirty = true;
            return;
        }
        let Some(receiver) = self.receiver.clone() else {
            return;
        };
        let query = self.history_query.clone();
        let generation = self.history_generation;
        self.history_dirty = false;
        self.history_task = Some(cx.spawn(async move |owner, cx| {
            cx.background_executor()
                .timer(std::time::Duration::from_millis(120))
                .await;
            // Each bounded ledger query yields the coordinator to input/control
            // requests. A search continues across batches without asking users
            // to guess which older page contains their message.
            let result = async {
                let mut query = query;
                let searching = !query.search.is_empty();
                let mut page = HistoryPage::default();
                loop {
                    if !owner
                        .update(cx, |this, _| {
                            this.history_generation == generation && !this.closing
                        })
                        .unwrap_or(false)
                    {
                        return Ok::<_, voicelink_receiver::Error>(None);
                    }
                    query.limit = 30 - page.entries.len();
                    let batch = receiver.history(query.clone()).await?;
                    page.entries.extend(batch.entries);
                    page.next = batch.next;
                    if !searching || page.entries.len() >= 30 || page.next.is_none() {
                        break;
                    }
                    query.before = page.next;
                    cx.background_executor()
                        .timer(std::time::Duration::from_millis(1))
                        .await;
                }
                Ok(Some(page))
            }
            .await;
            let latest = receiver.history(HistoryQuery::default()).await;
            let _ = owner.update(cx, |this, cx| {
                this.history_task = None;
                if this.closing {
                    return;
                }
                if generation == this.history_generation {
                    match result {
                        Ok(Some(page)) => {
                            this.history = page;
                            this.history_failed = false;
                        }
                        Ok(None) => {}
                        Err(error) => {
                            eprintln!("Read history: {error}");
                            this.history_failed = true;
                        }
                    }
                }
                if let Ok(page) = latest {
                    this.latest = page;
                }
                if this.history_dirty || generation != this.history_generation {
                    this.refresh_history(cx);
                }
                cx.notify();
            });
        }));
        cx.notify();
    }
}
