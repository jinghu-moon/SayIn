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
    interfaces::{NetworkInterface, available_interfaces},
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

/// A local review authorizes only the request and control revision actually shown.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ReviewScope {
    revision: String,
    request: String,
}

/// Discovery and selection are independent of device trust and input permission.
#[derive(Default)]
struct NetworkSelection {
    interfaces: Vec<NetworkInterface>,
    selected: Option<String>,
}
impl NetworkSelection {
    fn replace(&mut self, mut interfaces: Vec<NetworkInterface>, preferred: Option<&str>) {
        interfaces.sort_by_key(|i| {
            (
                !i.is_physical(),
                Some(i.key().as_str()) != preferred,
                i.adapter,
                i.address,
            )
        });
        self.selected = interfaces.first().map(NetworkInterface::key);
        self.interfaces = interfaces;
    }
    fn selected(&self) -> Option<&NetworkInterface> {
        self.interfaces
            .iter()
            .find(|i| Some(i.key().as_str()) == self.selected.as_deref())
    }
}

#[derive(Clone, Copy)]
enum HistoryChange {
    Delete(i64),
    Restore(i64),
    Policy(voicelink_receiver::history::HistoryPolicy),
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
    network_retry: Option<Task<()>>,
    pairing_requested: bool,
    revoking: Option<String>,
    history: HistoryPage,
    latest: HistoryPage,
    review_entry: Option<voicelink_receiver::history::HistoryEntry>,
    review_revision: Option<String>,
    history_query: HistoryQuery,
    history_task: Option<Task<()>>,
    history_generation: u64,
    history_dirty: bool,
    history_failed: bool,
    history_policy: Option<voicelink_receiver::history::HistoryPolicy>,
    history_command: Option<Task<()>>,
    history_command_failed: bool,
    history_undo: Option<i64>,
    history_undo_timer: Option<Task<()>>,
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
            network_retry: None,
            pairing_requested: false,
            revoking: None,
            history: HistoryPage::default(),
            latest: HistoryPage::default(),
            review_entry: None,
            review_revision: None,
            history_query: HistoryQuery::default(),
            history_task: None,
            history_generation: 0,
            history_dirty: false,
            history_failed: false,
            history_policy: None,
            history_command: None,
            history_command_failed: false,
            history_undo: None,
            history_undo_timer: None,
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
        self.watch =
            Some(cx.spawn(async move |owner, cx| {
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
                                    if this.snapshot.as_ref().is_some_and(|old| {
                                        old.paired_devices < snapshot.paired_devices
                                    }) {
                                        this.pairing_requested = false;
                                    }
                                    if this.snapshot.as_ref().map(|s| &s.control.state_rev)
                                        != Some(&snapshot.control.state_rev)
                                    {
                                        this.review_entry = None;
                                        this.review_revision = None;
                                    }
                                    this.snapshot = Some(snapshot);
                                    this.refresh_history(cx);
                                    if !this.bootstrapped {
                                        this.bootstrapped = true;
                                        this.pairing_requested = this
                                            .snapshot
                                            .as_ref()
                                            .is_some_and(|s| s.paired_devices == 0);
                                        this.discover_network(cx);
                                        this.watch_network_recovery(cx);
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

    pub(crate) fn review_scope(&self) -> Option<ReviewScope> {
        let snapshot = self.snapshot.as_ref()?;
        if !self.is_ready()
            || snapshot.control.pause_reason.as_deref() != Some("outcome_review_required")
            || self.review_revision.as_ref() != Some(&snapshot.control.state_rev)
        {
            return None;
        }
        Some(ReviewScope {
            revision: snapshot.control.state_rev.clone(),
            request: self.review_entry.as_ref()?.receipt.id.clone(),
        })
    }

    pub fn resume(&mut self, cx: &mut Context<Self>) {
        if let Some(snapshot) = self.snapshot.as_ref() {
            self.resume_in_scope(snapshot.control.state_rev.clone(), false, cx);
        }
    }

    pub(crate) fn resume_reviewed(&mut self, scope: ReviewScope, cx: &mut Context<Self>) -> bool {
        if self.review_scope().as_ref() != Some(&scope) {
            return false;
        }
        self.resume_in_scope(scope.revision, true, cx)
    }

    fn resume_in_scope(
        &mut self,
        revision: String,
        reviewed: bool,
        cx: &mut Context<Self>,
    ) -> bool {
        if !self.is_ready() || self.is_busy() || self.closing {
            return false;
        }
        let Some(snapshot) = self.snapshot.as_ref() else {
            return false;
        };
        if snapshot.control.active_device_id.is_none() || snapshot.control.state_rev != revision {
            return false;
        }
        let Some(receiver) = self.receiver.clone() else {
            return false;
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
        true
    }

    pub fn stop(&mut self, cx: &mut Context<Self>) {
        if self.is_stopped() || (self.closing && self.command.is_some()) {
            return;
        }
        self.closing = true;
        self.phase = ReceptionPhase::Stopping;
        self.watch = None;
        self.history_task = None;
        self.history_command = None;
        self.history_undo_timer = None;
        self.history_undo = None;
        self.command = None;
        self.network_task = None;
        self.network_watch = None;
        self.network_retry = None;
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
    pub fn pairing_requested(&self) -> bool {
        self.pairing_requested
    }
    /// Explicit pairing is for adding a phone, never for restoring a network.
    pub fn prepare_network(&mut self, cx: &mut Context<Self>) {
        if !self.is_ready() || self.is_busy() || self.closing {
            return;
        }
        self.pairing_requested = true;
        if self.network.is_some() {
            self.open_pairing(cx);
        } else {
            self.discover_network(cx);
        }
    }
    pub fn retry_network(&mut self, cx: &mut Context<Self>) {
        if self.is_ready() && !self.is_busy() && !self.closing && self.network.is_none() {
            self.discover_network(cx);
        }
    }
    /// Optional troubleshooting override, absent from the normal connection path.
    pub fn select_interface(&mut self, key: &str, cx: &mut Context<Self>) {
        if self.is_busy()
            || self.closing
            || !self.selection.interfaces.iter().any(|i| i.key() == key)
        {
            return;
        }
        self.preferred_interface = Some(key.into());
        let network = self.network.take();
        self.network_snapshot = None;
        self.pairing_image = None;
        self.network_watch = None;
        // Fence input before yielding to the async shutdown/rebind work.
        let pausing = self.receiver.as_ref().map(Receiver::network_lost);
        self.network_task = Some(cx.spawn(async move |owner, cx| {
            if let Some(pausing) = pausing {
                let _ = pausing.await;
            }
            if let Some(network) = network {
                let _ = network.shutdown().await;
            }
            let _ = owner.update(cx, |this, cx| {
                this.network_task = None;
                if this.closing {
                    return;
                }
                let preferred = this.preferred_interface.as_deref();
                this.selection
                    .interfaces
                    .sort_by_key(|i| Some(i.key().as_str()) != preferred);
                this.start_network(cx);
            });
        }));
        cx.notify();
    }
    fn watch_network_recovery(&mut self, cx: &mut Context<Self>) {
        self.network_retry = Some(cx.spawn(async move |owner, cx| {
            loop {
                cx.background_executor()
                    .timer(std::time::Duration::from_secs(2))
                    .await;
                if owner
                    .update(cx, |this, cx| {
                        if this.closing {
                            return false;
                        }
                        if this.network.is_none() && !this.is_busy() && this.is_ready() {
                            this.discover_network(cx);
                        }
                        true
                    })
                    .unwrap_or(false)
                {
                    continue;
                }
                break;
            }
        }));
    }
    fn discover_network(&mut self, cx: &mut Context<Self>) {
        let preferred = self.preferred_interface.clone();
        let operation = cx.background_spawn(async move {
            let mut selection = NetworkSelection::default();
            selection.replace(available_interfaces()?, preferred.as_deref());
            Ok::<_, voicelink_transport::Error>(selection)
        });
        self.network_task = Some(cx.spawn(async move |owner, cx| {
            let result = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.network_task = None;
                if this.closing {
                    return;
                }
                match result {
                    Ok(selection) if !selection.interfaces.is_empty() => {
                        this.selection = selection;
                        this.start_network(cx);
                    }
                    _ => {
                        this.selection = NetworkSelection::default();
                        this.issue = Some(ReceptionIssue::Network);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }
    fn start_network(&mut self, cx: &mut Context<Self>) {
        let (Some(receiver), Some(identity)) = (self.receiver.clone(), self.identity.clone())
        else {
            return;
        };
        let interfaces = self.selection.interfaces.clone();
        let path = self.directory.join("network-interface.txt");
        let operation = cx.background_spawn(async move {
            let mut last = voicelink_transport::Error::Protocol("NETWORK_UNAVAILABLE");
            for interface in interfaces {
                let bind = |port| {
                    NetworkService::start_on_network(
                        receiver.clone(),
                        identity.clone(),
                        interface.binding()?,
                        port,
                    )
                };
                let result = match bind(7788) {
                    Err(voicelink_transport::Error::Io(e))
                        if e.kind() == std::io::ErrorKind::AddrInUse =>
                    {
                        bind(0)
                    }
                    other => other,
                };
                match result {
                    Ok(service) => {
                        use std::io::Write as _;
                        if let Ok(mut file) = atomic_write_file::AtomicWriteFile::open(&path) {
                            let _ = file
                                .write_all(interface.key().as_bytes())
                                .and_then(|_| file.commit());
                        }
                        return Ok((service, interface.key()));
                    }
                    Err(error) => last = error,
                }
            }
            Err(last)
        });
        self.network_task = Some(cx.spawn(async move |owner, cx| {
            let result = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.network_task = None;
                if this.closing {
                    return;
                }
                match result {
                    Ok((service, key)) => {
                        this.selection.selected = Some(key.clone());
                        this.preferred_interface = Some(key);
                        this.network = Some(service.clone());
                        if this.issue == Some(ReceptionIssue::Network) {
                            this.issue = None;
                        }
                        this.observe_network(service, cx);
                        if this.pairing_requested {
                            this.open_pairing(cx);
                        }
                        this.maybe_enable(cx);
                    }
                    Err(error) => {
                        eprintln!("Start network: {error}");
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
                        if this.closing {
                            return;
                        }
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
        self.pairing_requested = false;
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
        if self.network.is_some()
            && self.is_ready()
            && !self.is_busy()
            && self.snapshot.as_ref().is_some_and(|s| {
                s.control.paused
                    && s.control.active_device_id.is_some()
                    && (s.control.pause_reason.as_deref() == Some("network_unavailable")
                        || (self.automatic_ready
                            && s.control.pause_reason.as_deref() == Some("startup")))
            })
        {
            self.automatic_ready = false;
            self.resume(cx);
        }
    }
    pub fn history(&self) -> &HistoryPage {
        &self.history
    }
    pub fn latest(&self) -> &HistoryPage {
        &self.latest
    }
    pub fn review_entry(&self) -> Option<&voicelink_receiver::history::HistoryEntry> {
        self.review_entry.as_ref()
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
    pub fn history_policy(&self) -> Option<voicelink_receiver::history::HistoryPolicy> {
        self.history_policy
    }
    pub fn history_busy(&self) -> bool {
        self.history_command.is_some()
    }
    pub fn history_command_failed(&self) -> bool {
        self.history_command_failed
    }
    pub fn history_undo(&self) -> Option<i64> {
        self.history_undo
    }

    pub fn delete_history(&mut self, order: i64, cx: &mut Context<Self>) {
        self.change_history(HistoryChange::Delete(order), cx);
    }
    pub fn restore_history(&mut self, cx: &mut Context<Self>) {
        if let Some(order) = self.history_undo {
            self.change_history(HistoryChange::Restore(order), cx);
        }
    }
    pub fn set_history_policy(
        &mut self,
        policy: voicelink_receiver::history::HistoryPolicy,
        cx: &mut Context<Self>,
    ) {
        self.change_history(HistoryChange::Policy(policy), cx);
    }
    fn change_history(&mut self, change: HistoryChange, cx: &mut Context<Self>) {
        if self.closing || self.history_busy() {
            return;
        }
        let Some(receiver) = self.receiver.clone() else {
            return;
        };
        self.history_command_failed = false;
        self.history_command = Some(cx.spawn(async move |owner, cx| {
            let result = match change {
                HistoryChange::Delete(order) => receiver.delete_history(order).await,
                HistoryChange::Restore(order) => receiver.restore_history(order).await,
                HistoryChange::Policy(policy) => {
                    receiver.set_history_policy(policy).await.map(|_| true)
                }
            };
            let _ = owner.update(cx, |this, cx| {
                this.history_command = None;
                if this.closing {
                    return;
                }
                match result {
                    Ok(true) => {
                        match change {
                            HistoryChange::Delete(order) => {
                                this.history
                                    .entries
                                    .retain(|e| e.receipt.order != order.to_string());
                                this.latest
                                    .entries
                                    .retain(|e| e.receipt.order != order.to_string());
                                this.history_undo = Some(order);
                                this.history_undo_timer = Some(cx.spawn(async move |owner, cx| {
                                    cx.background_executor()
                                        .timer(std::time::Duration::from_millis(
                                            voicelink_receiver::history::UNDO_MILLIS as u64,
                                        ))
                                        .await;
                                    let _ = owner.update(cx, |this, cx| {
                                        this.history_undo = None;
                                        cx.notify();
                                    });
                                }));
                            }
                            HistoryChange::Restore(_) => {
                                this.history_undo = None;
                                this.history_undo_timer = None;
                            }
                            HistoryChange::Policy(policy) => this.history_policy = Some(policy),
                        }
                        this.history_generation = this.history_generation.wrapping_add(1);
                        this.refresh_history(cx);
                    }
                    Ok(false) => {
                        this.history_command_failed = true;
                        this.history_undo = None;
                    }
                    Err(error) => {
                        eprintln!("History command failed: {error}");
                        this.history_command_failed = true;
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
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
        let review_revision = self
            .snapshot
            .as_ref()
            .filter(|s| s.control.pause_reason.as_deref() == Some("outcome_review_required"))
            .map(|s| s.control.state_rev.clone());
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
            let mut latest_query = HistoryQuery::default();
            latest_query.limit = 1;
            let latest = receiver.history(latest_query).await;
            let review = if review_revision.is_some() {
                receiver.review_history().await
            } else {
                Ok(None)
            };
            let policy = receiver.history_policy().await;
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
                // A read started before a delete/policy command must not put
                // stale data back after that command has updated the view.
                if generation == this.history_generation && !this.history_busy() {
                    if let Ok(page) = latest {
                        this.latest = page;
                    }
                    // A history read belongs to the control version at its
                    // start. Never pair an old request body with a newer gate.
                    let current_review_revision = this
                        .snapshot
                        .as_ref()
                        .filter(|s| {
                            s.control.pause_reason.as_deref() == Some("outcome_review_required")
                        })
                        .map(|s| s.control.state_rev.clone());
                    if current_review_revision == review_revision {
                        this.review_entry = review.ok().flatten();
                        this.review_revision = review_revision;
                    }
                    if let Ok(policy) = policy {
                        this.history_policy = Some(policy);
                    }
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
