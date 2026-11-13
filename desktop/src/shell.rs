//! The shell owns navigation only. Application preferences outlive its window.
use crate::{
    localization::{LanguagePreference, Message as M},
    preferences::{AppearancePreference, CloseBehavior, PreferencesState, StorageIssue},
    reception::{ReceptionIssue, ReceptionPhase, ReceptionState},
    theme,
};
use yue_component::gpui_kit::base::TestSupportExt as _;
use yue_component::{
    ButtonVariant, CheckboxState, GroupDirection, Icon, IconDefinition, YueButton, YueCard,
    YueCardHeader, YueCheckbox, YueEmpty, YueRadioGroup, YueRadioOption, YueTabItem, YueTabs,
    current_theme,
    gpui_kit::{
        self, App, AppContext as _, Context, Entity, InteractiveElement as _, IntoElement,
        ParentElement as _, Render, StatefulInteractiveElement as _, Styled as _, Subscription,
        Window, div,
    },
    outline,
};

pub enum ShellEvent {
    QuitRequested,
}
impl gpui_kit::EventEmitter<ShellEvent> for Shell {}
pub struct Shell {
    preferences: Entity<PreferencesState>,
    reception: Entity<ReceptionState>,
    selected: gpui_kit::SharedString,
    pairing_dialog: Entity<yue_component::YueDialogState>,
    review_dialog: Entity<yue_component::YueDialogState>,
    search: Entity<gpui_kit::base::input::InputState>,
    reviewed: bool,
    history_filter: Entity<yue_component::YueSelectState>,
    close_dialog: Entity<yue_component::YueDialogState>,
    _subscriptions: Vec<Subscription>,
}

impl Shell {
    pub fn new(
        preferences: Entity<PreferencesState>,
        reception: Entity<ReceptionState>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let changed = cx.observe_in(&preferences, window, |this, _, window, cx| {
            this.synchronize_window(window, cx);
            cx.notify();
        });
        let pairing_dialog = cx.new(yue_component::YueDialogState::new);
        let review_dialog = cx.new(yue_component::YueDialogState::new);
        let close_dialog = cx.new(yue_component::YueDialogState::new);
        let owner = cx.entity().downgrade();
        window.on_window_should_close(cx, move |window, cx| {
            owner
                .update(cx, |this, cx| this.request_close(window, cx))
                .unwrap_or(true)
        });
        let query = reception.read(cx).history_query().clone();
        let search = cx.new(|cx| {
            gpui_kit::base::input::InputState::new(window, cx).default_value(query.search)
        });
        let history_filter = cx.new(|cx| {
            let mut state =
                yue_component::YueSelectState::new(yue_component::SelectMode::Single, window, cx);
            state
                .set_value(
                    yue_component::SelectValue::Single(Some(
                        query.state.map_or("all", |s| s.key()).into(),
                    )),
                    window,
                    cx,
                )
                .expect("history filter value");
            state
        });
        let filtering = cx.subscribe(&history_filter, |this, _, event, cx| {
            if let yue_component::SelectEvent::Change(change) = event {
                use voicelink_receiver::protocol::State;
                let state = match change.after().keys().first().map(|s| s.as_ref()) {
                    Some("accepted") => Some(State::Accepted),
                    Some("executing") => Some(State::Executing),
                    Some("injected") => Some(State::Injected),
                    Some("held") => Some(State::Held),
                    Some("uncertain") => Some(State::Uncertain),
                    Some("failed") => Some(State::Failed),
                    Some("cancelled") => Some(State::Cancelled),
                    Some("expired") => Some(State::Expired),
                    _ => None,
                };
                this.reception
                    .update(cx, |s, cx| s.filter_history(state, cx));
            }
        });
        let searching = cx.subscribe(&search, |this, state, event, cx| {
            if matches!(event, gpui_kit::base::input::InputEvent::Change) {
                let value = state.read(cx).value().to_string();
                this.reception
                    .update(cx, |s, cx| s.search_history(value, cx));
            }
        });
        let receiving = cx.observe_in(&reception, window, |this, state, window, cx| {
            let service = state.read(cx);
            let pairing = service.network_requested()
                || service
                    .network_snapshot()
                    .is_some_and(|s| s.window.is_some() || s.approval.is_some());
            if pairing && !this.pairing_dialog.read(cx).is_open() {
                let _ = this.pairing_dialog.update(cx, |s, cx| s.open(window, cx));
            } else if !pairing && this.pairing_dialog.read(cx).is_open() {
                this.pairing_dialog.update(cx, |s, cx| s.close(window, cx));
            }
            cx.notify();
        });
        let appearance = cx.observe_window_appearance(window, |this, window, cx| {
            this.synchronize_window(window, cx);
            cx.notify();
        });
        let activation = cx.observe_window_activation(window, |this, window, cx| {
            if window.is_window_active() {
                this.preferences
                    .update(cx, |settings, cx| settings.refresh_system_languages(cx));
            }
        });
        let this = Self {
            preferences,
            reception,
            selected: "connection".into(),
            pairing_dialog,
            review_dialog,
            search,
            reviewed: false,
            history_filter,
            close_dialog,
            _subscriptions: vec![
                changed, receiving, appearance, activation, searching, filtering,
            ],
        };
        this.synchronize_window(window, cx);
        cx.on_next_frame(window, |this, window, cx| {
            let service = this.reception.read(cx);
            if service.network_requested()
                || service
                    .network_snapshot()
                    .is_some_and(|s| s.window.is_some() || s.approval.is_some())
            {
                let _ = this.pairing_dialog.update(cx, |s, cx| s.open(window, cx));
            }
        });
        this
    }

    pub fn request_close(&mut self, window: &mut Window, cx: &mut Context<Self>) -> bool {
        if !self.reception.read(cx).background_available()
            || self.preferences.read(cx).value().close_behavior() == CloseBehavior::Quit
        {
            cx.emit(ShellEvent::QuitRequested);
            return false;
        }
        if self.preferences.read(cx).value().close_behavior() == CloseBehavior::Tray {
            return true;
        }
        let _ = self.close_dialog.update(cx, |s, cx| s.open(window, cx));
        false
    }
    fn synchronize_window(&self, window: &mut Window, cx: &mut App) {
        let preferences = self.preferences.read(cx);
        let mode = preferences
            .value()
            .appearance()
            .resolve(window.appearance());
        let name = preferences.text(M::AppName);
        let options = [
            ("all", M::AllMessages),
            ("accepted", M::ReceiptAccepted),
            ("executing", M::ReceiptExecuting),
            ("injected", M::ReceiptInjected),
            ("held", M::ReceiptHeld),
            ("uncertain", M::ReceiptUncertain),
            ("failed", M::ReceiptFailed),
            ("cancelled", M::ReceiptCancelled),
            ("expired", M::ReceiptExpired),
        ]
        .into_iter()
        .map(|(key, label)| yue_component::SelectOption::new(key, preferences.text(label)))
        .collect::<Vec<_>>();
        if current_theme(cx).mode() != mode {
            theme::install(mode, cx);
        }
        window.set_window_title(&name);
        self.history_filter.update(cx, |state, cx| {
            state
                .set_options(options, cx)
                .expect("localized history filter")
        });
    }
}

fn heading(title: String, description: String, cx: &App) -> impl IntoElement {
    let theme = current_theme(cx);
    div()
        .flex()
        .flex_col()
        .gap_2()
        .child(
            div()
                .text_xl()
                .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                .child(title),
        )
        .child(
            div()
                .text_sm()
                .text_color(gpui_kit::rgb(theme.text_secondary()))
                .child(description),
        )
}

fn reception_status(state: &ReceptionState) -> M {
    match state.phase() {
        ReceptionPhase::Loading => M::ReceiverLoading,
        ReceptionPhase::Failed => M::ReceiverFailed,
        ReceptionPhase::Stopping => M::ReceiverStopping,
        ReceptionPhase::Stopped => M::ReceiverStopped,
        ReceptionPhase::Ready => match state.snapshot() {
            Some(snapshot) if !snapshot.storage_healthy => M::ReceiverStorageFailed,
            Some(snapshot) if snapshot.control.paused => M::ReceiverPaused,
            Some(_) => M::ReceiverRunning,
            None => M::ReceiverLoading,
        },
    }
}

fn action(
    id: impl Into<gpui_kit::ElementId>,
    label: String,
    icon: impl IconDefinition,
    cx: &App,
) -> YueButton {
    YueButton::icon_text(id, current_theme(cx), icon, label)
}

fn section(id: &'static str, title: String, icon: impl IconDefinition, cx: &App) -> YueCard {
    YueCard::new(id).header(
        YueCardHeader::new(title).leading(
            Icon::new(icon)
                .size(gpui_kit::rems(1.25))
                .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
        ),
    )
}

fn muted(text: String, cx: &App) -> impl IntoElement {
    div()
        .text_sm()
        .text_color(gpui_kit::rgb(current_theme(cx).text_secondary()))
        .child(text)
}

fn connection_status(service: &ReceptionState) -> M {
    if service
        .network_snapshot()
        .is_some_and(|s| s.running && s.connections > 0)
    {
        M::PhoneConnected
    } else if service.snapshot().is_some_and(|s| s.paired_devices > 0) {
        M::PhoneOffline
    } else {
        M::NotConnected
    }
}

fn connection(
    preferences: &Entity<PreferencesState>,
    reception: &Entity<ReceptionState>,
    review_dialog: &Entity<yue_component::YueDialogState>,
    cx: &mut App,
) -> gpui_kit::AnyElement {
    let settings = preferences.read(cx);
    let service = reception.read(cx);
    let snapshot = service.snapshot();
    let paused = snapshot.is_none_or(|s| s.control.paused);
    let review = snapshot
        .is_some_and(|s| s.control.pause_reason.as_deref() == Some("outcome_review_required"));
    let ready = service.is_ready() && !service.is_busy();
    let paired = snapshot.is_some_and(|s| s.control.active_device_id.is_some());
    let owner = reception.clone();
    let pair_owner = reception.clone();
    let review_owner = review_dialog.clone();
    let toggle = if paused {
        action(
            "receiving-toggle",
            settings.text(if review {
                M::ReviewInput
            } else {
                M::ResumeInput
            }),
            outline::PlayerPlay,
            cx,
        )
        .variant(ButtonVariant::Primary)
        .disabled(!ready || !paired)
        .loading(service.is_busy())
        .on_click(move |_, window, cx| {
            if review {
                let _ = review_owner.update(cx, |s, cx| s.open(window, cx));
            } else {
                owner.update(cx, |s, cx| s.resume(false, cx));
            }
        })
    } else {
        action(
            "receiving-toggle",
            settings.text(M::PauseInput),
            outline::PlayerPause,
            cx,
        )
        .disabled(!service.is_ready())
        .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.pause(cx)))
    };
    let status_icon = if paused {
        Icon::new(outline::PlayerPause)
    } else {
        Icon::new(outline::CursorText)
    };
    let mut receiving = div()
        .flex()
        .flex_col()
        .gap_4()
        .child(
            div()
                .flex()
                .items_center()
                .gap_4()
                .child(
                    div()
                        .flex_none()
                        .p_3()
                        .rounded_lg()
                        .bg(gpui_kit::rgb(current_theme(cx).surface_component()))
                        .child(
                            status_icon
                                .size(gpui_kit::rems(1.75))
                                .color_value(theme::brand().into()),
                        ),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .gap_1()
                        .child(
                            div()
                                .text_xl()
                                .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                                .child(settings.text(reception_status(service))),
                        )
                        .child(muted(
                            settings.text(if !paired {
                                M::ConnectFirst
                            } else if paused {
                                M::ReadyToEnable
                            } else {
                                M::CursorReady
                            }),
                            cx,
                        )),
                )
                .child(toggle),
        )
        .child(
            div()
                .flex()
                .items_center()
                .gap_2()
                .child(
                    Icon::new(outline::Pointer)
                        .size(gpui_kit::rems(1.))
                        .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
                )
                .child(settings.text(M::CursorMode)),
        )
        .child(muted(
            settings.text(if review {
                M::ReviewHelp
            } else {
                M::ReceiverHelp
            }),
            cx,
        ));
    if let Some(issue) = service.issue() {
        receiving = receiving.child(
            div()
                .flex()
                .items_start()
                .gap_2()
                .p_3()
                .rounded_md()
                .bg(gpui_kit::rgb(current_theme(cx).surface_component()))
                .child(
                    div()
                        .flex_none()
                        .child(Icon::new(outline::AlertCircle).size(gpui_kit::rems(1.25))),
                )
                .child(div().min_w_0().child(settings.text(match issue {
                    ReceptionIssue::Startup => M::ReceiverFailed,
                    ReceptionIssue::Command => M::ReceiverCommandFailed,
                    ReceptionIssue::Storage => M::ReceiverStorageFailed,
                    ReceptionIssue::Network => M::NetworkFailed,
                }))),
        );
    }
    if service.phase() == ReceptionPhase::Failed {
        let owner = reception.clone();
        receiving = receiving.child(
            action(
                "retry-receiver",
                settings.text(M::Retry),
                outline::Refresh,
                cx,
            )
            .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.retry_start(cx))),
        );
    }
    let mut content = div()
        .w_full()
        .max_w(gpui_kit::rems(68.))
        .mx_auto()
        .flex()
        .flex_col()
        .gap_5()
        .p_6()
        .child(
            div()
                .flex()
                .items_center()
                .gap_4()
                .child(div().flex_1().min_w_0().child(heading(
                    settings.text(M::Connection),
                    settings.text(M::ConnectionDescription),
                    cx,
                )))
                .child(
                    action(
                        "pair-phone",
                        settings.text(M::PairPhone),
                        outline::Qrcode,
                        cx,
                    )
                    .disabled(!ready)
                    .on_click(move |_, _, cx| pair_owner.update(cx, |s, cx| s.prepare_network(cx))),
                ),
        )
        .child(YueCard::new("receiving-card").child(receiving));

    content = content
        .child(
            section(
                "route-card",
                settings.text(M::CursorMode),
                outline::Pointer,
                cx,
            )
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap_4()
                    .child(route_node(
                        outline::DeviceMobile,
                        settings.text(M::PhoneDevice),
                        cx,
                    ))
                    .child(Icon::new(outline::ArrowRight).size(gpui_kit::rems(1.)))
                    .child(route_node(
                        outline::Wifi,
                        settings.text(M::LocalNetwork),
                        cx,
                    ))
                    .child(Icon::new(outline::ArrowRight).size(gpui_kit::rems(1.)))
                    .child(route_node(
                        outline::CursorText,
                        settings.text(M::CurrentInput),
                        cx,
                    )),
            ),
        )
        .child(
            section(
                "latest-card",
                settings.text(M::LatestMessage),
                outline::Message,
                cx,
            )
            .child(if let Some(entry) = service.latest().entries.first() {
                latest_entry(entry, settings, cx)
            } else {
                muted(settings.text(M::LatestEmpty), cx).into_any_element()
            }),
        );
    viewport("connection-scroll", content)
}

fn route_node(icon: impl IconDefinition, label: String, cx: &App) -> impl IntoElement {
    div()
        .flex()
        .items_center()
        .gap_2()
        .child(
            Icon::new(icon)
                .size(gpui_kit::rems(1.25))
                .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
        )
        .child(label)
}
fn viewport(id: &'static str, content: impl IntoElement) -> gpui_kit::AnyElement {
    div()
        .id(id)
        .size_full()
        .overflow_y_scroll()
        .child(content)
        .into_any_element()
}
fn devices(
    preferences: &Entity<PreferencesState>,
    reception: &Entity<ReceptionState>,
    cx: &mut App,
) -> gpui_kit::AnyElement {
    let settings = preferences.read(cx);
    let service = reception.read(cx);
    let snapshot = service.snapshot();
    let pair = reception.clone();
    let mut devices = div().flex().flex_col().gap_3();
    if let Some(snapshot) = snapshot.filter(|s| !s.device_ids.is_empty()) {
        for device in &snapshot.device_ids {
            let owner = reception.clone();
            let id = device.clone();
            let name = snapshot
                .device_names
                .get(device)
                .filter(|s| !s.is_empty())
                .cloned()
                .unwrap_or_else(|| settings.text(M::PhoneDevice));
            let title = format!("{} · {}", name, &device[..8]);
            devices = devices.child(
                div()
                    .flex()
                    .items_center()
                    .gap_3()
                    .child(
                        Icon::new(outline::DeviceMobile)
                            .size(gpui_kit::rems(1.5))
                            .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .child(div().font_weight(gpui_kit::FontWeight::MEDIUM).child(title))
                            .child(muted(
                                settings.text(
                                    if snapshot.control.active_device_id.as_ref() == Some(device) {
                                        M::ActiveDevice
                                    } else {
                                        M::DeviceAuthorized
                                    },
                                ),
                                cx,
                            )),
                    )
                    .child(
                        YueButton::icon(
                            gpui_kit::SharedString::from(format!("revoke-{device}")),
                            current_theme(cx),
                            outline::Unlink,
                            settings.text(M::RevokeDevice),
                        )
                        .variant(ButtonVariant::Ghost)
                        .tooltip(settings.text(M::RevokeDevice))
                        .disabled(service.is_busy())
                        .on_click(move |_, _, cx| {
                            owner.update(cx, |s, cx| s.request_revoke(id.clone(), cx))
                        }),
                    ),
            );
        }
        if service.revoking().is_some() {
            let confirm = reception.clone();
            let cancel = reception.clone();
            devices = devices
                .child(muted(settings.text(M::RevokeHelp), cx))
                .child(
                    div()
                        .flex()
                        .flex_wrap()
                        .gap_2()
                        .child(
                            action(
                                "confirm-revoke",
                                settings.text(M::ConfirmRevoke),
                                outline::Unlink,
                                cx,
                            )
                            .disabled(service.is_busy())
                            .on_click(move |_, _, cx| {
                                confirm.update(cx, |s, cx| s.confirm_revoke(cx))
                            }),
                        )
                        .child(
                            YueButton::new("cancel-revoke", settings.text(M::Cancel))
                                .variant(ButtonVariant::Ghost)
                                .on_click(move |_, _, cx| {
                                    cancel.update(cx, |s, cx| s.cancel_revoke(cx))
                                }),
                        ),
                );
        }
    } else {
        devices = devices.child(
            YueEmpty::new("phone-empty")
                .icon(outline::DeviceMobile)
                .title(settings.text(M::ConnectionEmpty))
                .description(settings.text(M::ConnectionEmptyDescription)),
        );
    }

    viewport(
        "devices-scroll",
        div()
            .w_full()
            .flex()
            .flex_col()
            .gap_5()
            .p_6()
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_start()
                    .gap_3()
                    .child(div().flex_1().min_w_0().child(heading(
                        settings.text(M::Devices),
                        settings.text(M::DevicesDescription),
                        cx,
                    )))
                    .child(
                        action("pair-phone", settings.text(M::PairPhone), outline::Plus, cx)
                            .disabled(!service.is_ready() || service.is_busy())
                            .on_click(move |_, _, cx| {
                                pair.update(cx, |s, cx| s.prepare_network(cx))
                            }),
                    ),
            )
            .child(
                section(
                    "devices-card",
                    settings.text(M::AuthorizedDevices),
                    outline::DeviceMobile,
                    cx,
                )
                .child(devices),
            ),
    )
}
fn pairing(
    preferences: &Entity<PreferencesState>,
    reception: &Entity<ReceptionState>,
    cx: &App,
) -> gpui_kit::AnyElement {
    let settings = preferences.read(cx);
    let service = reception.read(cx);
    let mut content = div().w_full().flex().flex_col().gap_4();
    if service.network_requested() && service.network_snapshot().is_none() {
        let owner = reception.clone();
        let start = reception.clone();
        let mut chooser = div()
            .flex()
            .flex_col()
            .gap_3()
            .child(muted(settings.text(M::PairNetworkHelp), cx));
        if service.interfaces().is_empty() {
            chooser = chooser.child(settings.text(M::NoNetwork));
        } else {
            chooser = chooser.child(
                YueRadioGroup::new(
                    "network-interface",
                    current_theme(cx),
                    service
                        .selected_interface()
                        .map(|i| gpui_kit::SharedString::from(i.key())),
                )
                .label(settings.text(M::PairNetwork))
                .options(service.interfaces().iter().map(|i| {
                    use voicelink_transport::interfaces::NetworkCategory;
                    let category = if i.is_public() {
                        M::NetworkPublic
                    } else if i
                        .profiles
                        .iter()
                        .all(|p| p.category == NetworkCategory::Domain)
                    {
                        M::NetworkDomain
                    } else {
                        M::NetworkPrivate
                    };
                    YueRadioOption::new(
                        i.key(),
                        format!("{} · {} · {}", i.name, i.address, settings.text(category)),
                    )
                }))
                .disabled(service.is_busy())
                .on_change(move |request, _, cx| {
                    owner.update(cx, |s, cx| s.select_interface(&request, cx))
                }),
            );
            if service.selected_interface().is_some_and(|i| i.is_public()) {
                let consent = reception.clone();
                chooser = chooser
                    .child(muted(settings.text(M::PublicNetworkHelp), cx))
                    .child(
                        YueCheckbox::new(
                            "authorize-public-network",
                            current_theme(cx),
                            if service.network_authorized() {
                                CheckboxState::Checked
                            } else {
                                CheckboxState::Unchecked
                            },
                        )
                        .label(settings.text(M::AuthorizePublicNetwork))
                        .disabled(service.is_busy())
                        .on_change(move |state, _, _, cx| {
                            consent.update(cx, |s, cx| {
                                s.authorize_public_network(state == CheckboxState::Checked, cx)
                            })
                        }),
                    );
            }
            chooser = chooser
                .child(muted(settings.text(M::NetworkFirewallHelp), cx))
                .child(
                    div().flex().child(
                        action(
                            "start-pairing",
                            settings.text(M::StartPairing),
                            outline::Qrcode,
                            cx,
                        )
                        .variant(ButtonVariant::Primary)
                        .disabled(!service.network_authorized() || service.is_busy())
                        .loading(service.is_busy())
                        .on_click(move |_, _, cx| start.update(cx, |s, cx| s.start_network(cx))),
                    ),
                );
        }
        content = content.child(
            section(
                "network-card",
                settings.text(M::PairNetwork),
                outline::Wifi,
                cx,
            )
            .child(chooser),
        );
    }
    if let Some(network) = service.network_snapshot() {
        let mut panel = div().flex().flex_col().gap_4();
        if let Some(window) = network
            .window
            .as_ref()
            .filter(|w| std::time::Instant::now() < w.expires)
        {
            let owner = reception.clone();
            let mut invitation = div().flex().flex_col().gap_3();
            if let Some(image) = service.pairing_image() {
                invitation = invitation.child(pairing_qr(
                    image,
                    window.qr_modules(),
                    settings.text(M::PairingQr),
                ));
            }
            let code = window.code.to_string();
            panel = panel.child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap_6()
                    .child(invitation)
                    .child(
                        div()
                            .flex_1()
                            .min_w(gpui_kit::rems(12.))
                            .flex()
                            .flex_col()
                            .gap_3()
                            .child(muted(settings.text(M::PairingChoices), cx))
                            .child(settings.text(M::PairingCode))
                            .child(
                                div()
                                    .text_3xl()
                                    .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                                    .child(format!("{} {}", &code[..4], &code[4..])),
                            )
                            .child(muted(settings.text(M::PairingExpiry), cx))
                            .child(
                                div().flex().child(
                                    action(
                                        "close-pairing",
                                        settings.text(M::ClosePairing),
                                        outline::X,
                                        cx,
                                    )
                                    .variant(ButtonVariant::Outline)
                                    .on_click(
                                        move |_, _, cx| {
                                            owner.update(cx, |s, cx| s.close_pairing(cx))
                                        },
                                    ),
                                ),
                            ),
                    ),
            );
        }
        if let Some(approval) = &network.approval {
            let accept = reception.clone();
            let reject = reception.clone();
            let accept_id = approval.id.clone();
            let reject_id = approval.id.clone();
            panel = panel.child(
                div()
                    .p_4()
                    .rounded_lg()
                    .bg(gpui_kit::rgb(current_theme(cx).surface_component()))
                    .flex()
                    .flex_col()
                    .gap_3()
                    .child(
                        div()
                            .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                            .child(settings.text(M::ApprovePhone)),
                    )
                    .child(approval.name.clone())
                    .child(
                        div()
                            .flex()
                            .gap_3()
                            .child(
                                action(
                                    "approve-phone",
                                    settings.text(M::Approve),
                                    outline::Check,
                                    cx,
                                )
                                .variant(ButtonVariant::Primary)
                                .on_click(move |_, _, cx| {
                                    accept.update(cx, |s, cx| {
                                        s.approve_pairing(accept_id.clone(), true, cx)
                                    })
                                }),
                            )
                            .child(
                                action("reject-phone", settings.text(M::Reject), outline::X, cx)
                                    .on_click(move |_, _, cx| {
                                        reject.update(cx, |s, cx| {
                                            s.approve_pairing(reject_id.clone(), false, cx)
                                        })
                                    }),
                            ),
                    ),
            );
        }
        let address = network.address.to_string();
        panel = panel.child(
            div()
                .flex()
                .items_center()
                .gap_3()
                .child(
                    Icon::new(outline::Wifi)
                        .size(gpui_kit::rems(1.25))
                        .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(settings.text(M::PairingAddress))
                        .child(muted(address.clone(), cx)),
                )
                .child(
                    YueButton::icon(
                        "copy-address",
                        current_theme(cx),
                        outline::Copy,
                        settings.text(M::CopyAddress),
                    )
                    .variant(ButtonVariant::Ghost)
                    .tooltip(settings.text(M::CopyAddress))
                    .on_click(move |_, _, cx| {
                        cx.write_to_clipboard(gpui_kit::ClipboardItem::new_string(address.clone()))
                    }),
                ),
        );
        if service.selected_interface().is_some_and(|i| i.is_public()) {
            panel = panel.child(muted(settings.text(M::PublicNetworkActive), cx));
        }
        content = content.child(
            section(
                "pairing-card",
                settings.text(M::PairingTitle),
                outline::Qrcode,
                cx,
            )
            .child(panel),
        );
    }

    content.into_any_element()
}

/// A QR is image content, not a monochrome icon. Share this presentation with
/// rendering verification so alpha-mask regressions are observable.
pub fn pairing_qr(
    image: std::sync::Arc<gpui_kit::Image>,
    modules: u32,
    label: String,
) -> impl IntoElement {
    let modules = modules as f32;
    div()
        .id("pairing-qr")
        .role(gpui_kit::Role::Image)
        .aria_label(label)
        .test_support()
        .size(gpui_kit::rems(20.))
        .flex_none()
        .child(
            gpui_kit::canvas(
                move |_, window, cx| image.use_render_image(window, cx),
                move |bounds, image, window, _| {
                    let Some(image) = image else { return };
                    // Use the encoder's module count, independent of any SVG
                    // supersampling performed by the framework's image loader.
                    let scale = window.scale_factor();
                    let width = f32::from(bounds.size.width);
                    let height = f32::from(bounds.size.height);
                    let physical = width.min(height) * scale;
                    let pitch = (physical / modules).floor();
                    if pitch < 1. {
                        return;
                    }
                    let side = modules * pitch;
                    let x =
                        (f32::from(bounds.origin.x) * scale + (width * scale - side) / 2.).round();
                    let y =
                        (f32::from(bounds.origin.y) * scale + (height * scale - side) / 2.).round();
                    let image_bounds = gpui_kit::Bounds::new(
                        gpui_kit::point(gpui_kit::px(x / scale), gpui_kit::px(y / scale)),
                        gpui_kit::size(gpui_kit::px(side / scale), gpui_kit::px(side / scale)),
                    );
                    window
                        .paint_image_nearest(
                            image_bounds,
                            image_bounds,
                            gpui_kit::Corners::default(),
                            image,
                            0,
                            false,
                        )
                        .expect("pairing QR image paint failed");
                },
            )
            .size_full(),
        )
}

fn latest_entry(
    entry: &voicelink_receiver::history::HistoryEntry,
    settings: &PreferencesState,
    cx: &App,
) -> gpui_kit::AnyElement {
    use voicelink_receiver::protocol::Operation;
    let body = match &entry.operation {
        Some(Operation::Text { text, .. }) => text.clone(),
        Some(_) => settings.text(M::KeyOperation),
        None => settings.text(M::BodyUnavailable),
    };
    div()
        .min_w_0()
        .flex()
        .flex_col()
        .gap_2()
        .child(div().line_clamp(2).child(body))
        .child(muted(settings.receipt_summary(&entry.receipt), cx))
        .child(muted(settings.receipt_help(&entry.receipt), cx))
        .child(muted(
            entry
                .target
                .as_ref()
                .map(|t| t.name().to_owned())
                .unwrap_or_else(|| settings.text(M::TargetUnavailable)),
            cx,
        ))
        .into_any_element()
}

fn history_entry(
    entry: &voicelink_receiver::history::HistoryEntry,
    settings: &PreferencesState,
    cx: &App,
) -> gpui_kit::AnyElement {
    use voicelink_receiver::protocol::{Operation, State};
    let body = match &entry.operation {
        Some(Operation::Text { text, .. }) => text.clone(),
        Some(Operation::Key { .. }) => settings.text(M::KeyOperation),
        None => settings.text(M::BodyUnavailable),
    };
    let icon = match entry.receipt.st {
        State::Injected => Icon::new(outline::CircleCheck),
        State::Failed | State::Uncertain => Icon::new(outline::AlertCircle),
        _ => Icon::new(outline::Clock),
    };
    let destination = entry
        .target
        .as_ref()
        .map(|t| format!("{} · {}", t.name(), t.window_title))
        .unwrap_or_else(|| settings.text(M::TargetUnavailable));
    let received = entry
        .received_at
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|t| {
            t.with_timezone(&chrono::Local)
                .format("%Y-%m-%d %H:%M:%S")
                .to_string()
        })
        .map(|timestamp| settings.history_time(false, &timestamp))
        .unwrap_or_else(|| settings.text(M::TimeUnavailable));
    let timing = entry
        .executed_at
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|t| {
            t.with_timezone(&chrono::Local)
                .format("%Y-%m-%d %H:%M:%S")
                .to_string()
        })
        .map(|timestamp| format!("{} · {}", received, settings.history_time(true, &timestamp)))
        .unwrap_or(received);
    let mut row = div()
        .id(gpui_kit::SharedString::from(format!(
            "message-{}",
            entry.receipt.order
        )))
        .w_full()
        .min_w_0()
        .flex()
        .flex_col()
        .gap_3()
        .child(
            div()
                .flex()
                .items_center()
                .gap_2()
                .child(
                    icon.size(gpui_kit::rems(1.25))
                        .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(settings.receipt_summary(&entry.receipt)),
                ),
        )
        .child(div().whitespace_normal().child(body.clone()))
        .child(
            div()
                .flex()
                .items_start()
                .gap_2()
                .child(Icon::new(outline::AppWindow).size(gpui_kit::rems(1.)))
                .child(div().flex_1().min_w_0().child(muted(destination, cx))),
        )
        .child(muted(
            format!(
                "{} · {}",
                if entry.source_name.is_empty() {
                    settings.text(M::PhoneDevice)
                } else {
                    entry.source_name.clone()
                },
                entry.device
            ),
            cx,
        ))
        .child(muted(timing, cx))
        .child(muted(settings.receipt_help(&entry.receipt), cx));
    if matches!(entry.operation, Some(Operation::Text { .. })) {
        row = row.child(
            div().flex().child(
                action(
                    gpui_kit::SharedString::from(format!("copy-{}", entry.receipt.order)),
                    settings.text(M::CopyOriginal),
                    outline::Copy,
                    cx,
                )
                .variant(ButtonVariant::Ghost)
                .on_click(move |_, _, cx| {
                    cx.write_to_clipboard(gpui_kit::ClipboardItem::new_string(body.clone()))
                }),
            ),
        );
    }
    row.into_any_element()
}
fn history(
    preferences: &Entity<PreferencesState>,
    reception: &Entity<ReceptionState>,
    search: &Entity<gpui_kit::base::input::InputState>,
    filter_state: &Entity<yue_component::YueSelectState>,
    cx: &mut App,
) -> gpui_kit::AnyElement {
    let settings = preferences.read(cx);
    let service = reception.read(cx);
    let first = reception.clone();
    let next = reception.clone();
    let mut content = div()
        .w_full()
        .min_w_0()
        .flex()
        .flex_col()
        .gap_5()
        .p_6()
        .child(heading(
            settings.text(M::History),
            settings.text(M::HistoryDescription),
            cx,
        ))
        .child(
            div()
                .flex()
                .flex_wrap()
                .items_center()
                .gap_3()
                .child(
                    div().flex_1().min_w(gpui_kit::rems(12.)).child(
                        yue_component::YueInput::new("history-search", current_theme(cx), search)
                            .input_type(yue_component::InputType::Search)
                            .block()
                            .clearable(true)
                            .max_length(256)
                            .leading_icon(outline::Search)
                            .placeholder(settings.text(M::SearchHistory))
                            .accessibility_label(settings.text(M::SearchHistory)),
                    ),
                )
                .child(
                    yue_component::YueSelect::new("history-filter", filter_state)
                        .accessibility_label(settings.text(M::HistoryFilter))
                        .placeholder(settings.text(M::AllMessages))
                        .empty_text(settings.text(M::HistoryEmpty))
                        .loading_text(settings.text(M::HistoryLoading))
                        .width(gpui_kit::rems(14.))
                        .leading_icon(outline::Filter),
                ),
        );
    if service.history_failed() {
        let retry = reception.clone();
        content = content
            .child(muted(settings.text(M::HistoryReadFailed), cx))
            .child(
                action(
                    "retry-history",
                    settings.text(M::Retry),
                    outline::Refresh,
                    cx,
                )
                .on_click(move |_, _, cx| retry.update(cx, |s, cx| s.refresh_history(cx))),
            );
    } else if service.history().entries.is_empty() {
        content = content.child(
            YueEmpty::new("history-empty")
                .icon(outline::History)
                .title(settings.text(if service.history_loading() {
                    M::HistoryLoading
                } else {
                    M::HistoryEmpty
                }))
                .description(settings.text(M::HistoryEmptyDescription)),
        );
    } else {
        for entry in &service.history().entries {
            content = content.child(
                YueCard::new(gpui_kit::SharedString::from(format!(
                    "history-card-{}",
                    entry.receipt.order
                )))
                .child(history_entry(entry, settings, cx)),
            );
        }
    }
    content = content.child(
        div()
            .flex()
            .flex_wrap()
            .gap_2()
            .child(
                action(
                    "history-first",
                    settings.text(M::LatestPage),
                    outline::Refresh,
                    cx,
                )
                .disabled(service.history_loading())
                .on_click(move |_, _, cx| first.update(cx, |s, cx| s.first_history(cx))),
            )
            .child(
                action(
                    "history-next",
                    settings.text(M::OlderPage),
                    outline::ArrowRight,
                    cx,
                )
                .disabled(service.history_loading() || service.history().next.is_none())
                .on_click(move |_, _, cx| next.update(cx, |s, cx| s.next_history(cx))),
            ),
    );
    viewport("history-scroll", content)
}

fn settings(preferences: &Entity<PreferencesState>, cx: &mut App) -> gpui_kit::AnyElement {
    let state = preferences.read(cx);
    let theme = current_theme(cx);
    let disabled = !state.is_editable();
    let language_owner = preferences.clone();
    let theme_owner = preferences.clone();
    let close_owner = preferences.clone();
    let mut content = div()
        .w_full()
        .max_w(gpui_kit::rems(58.))
        .mx_auto()
        .flex()
        .flex_col()
        .gap_6()
        .p_6()
        .child(heading(
            state.text(M::Settings),
            state.text(M::SettingsDescription),
            cx,
        ))
        .child(
            section(
                "language-card",
                state.text(M::Language),
                outline::Language,
                cx,
            )
            .child(
                YueRadioGroup::new(
                    "language",
                    theme,
                    Some(state.value().language().key().into()),
                )
                .label(state.text(M::Language))
                .direction(GroupDirection::Horizontal)
                .disabled(disabled)
                .options([
                    YueRadioOption::new("system", state.text(M::FollowSystem)),
                    YueRadioOption::new("zh-Hans", state.text(M::LanguageChinese)),
                    YueRadioOption::new("en", state.text(M::LanguageEnglish)),
                ])
                .on_change(move |key, _, cx| {
                    let language = match key.as_ref() {
                        "zh-Hans" => LanguagePreference::Chinese,
                        "en" => LanguagePreference::English,
                        _ => LanguagePreference::System,
                    };
                    language_owner.update(cx, |state, cx| {
                        state.save(state.value().clone().with_language(language), cx)
                    });
                }),
            )
            .child(
                div()
                    .text_sm()
                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                    .child(state.text(M::LanguageDescription)),
            ),
        )
        .child(
            section(
                "appearance-card",
                state.text(M::Appearance),
                outline::Palette,
                cx,
            )
            .child(
                YueRadioGroup::new(
                    "appearance",
                    theme,
                    Some(state.value().appearance().key().into()),
                )
                .label(state.text(M::Appearance))
                .disabled(disabled)
                .direction(GroupDirection::Horizontal)
                .options([
                    YueRadioOption::new("system", state.text(M::FollowSystem)),
                    YueRadioOption::new("light", state.text(M::ThemeLight)),
                    YueRadioOption::new("dark", state.text(M::ThemeDark)),
                ])
                .on_change(move |key, _, cx| {
                    let appearance = match key.as_ref() {
                        "light" => AppearancePreference::Light,
                        "dark" => AppearancePreference::Dark,
                        _ => AppearancePreference::System,
                    };
                    theme_owner.update(cx, |state, cx| {
                        state.save(state.value().clone().with_appearance(appearance), cx)
                    });
                }),
            )
            .child(
                div()
                    .text_sm()
                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                    .child(state.text(M::AppearanceDescription)),
            ),
        )
        .child(
            section(
                "close-behavior-card",
                state.text(M::CloseBehavior),
                outline::AppWindow,
                cx,
            )
            .child(
                YueRadioGroup::new(
                    "close-behavior",
                    theme,
                    Some(state.value().close_behavior().key().into()),
                )
                .label(state.text(M::CloseBehavior))
                .disabled(disabled)
                .options([
                    YueRadioOption::new("ask", state.text(M::CloseAsk)),
                    YueRadioOption::new("tray", state.text(M::CloseTray)),
                    YueRadioOption::new("quit", state.text(M::CloseQuit)),
                ])
                .on_change(move |key, _, cx| {
                    close_owner.update(cx, |s, cx| {
                        s.save(
                            s.value().clone().with_close_behavior(match key.as_ref() {
                                "tray" => CloseBehavior::Tray,
                                "quit" => CloseBehavior::Quit,
                                _ => CloseBehavior::Ask,
                            }),
                            cx,
                        )
                    })
                }),
            )
            .child(muted(state.text(M::CloseBehaviorHelp), cx)),
        )
        .child(
            section("about-card", state.text(M::About), outline::InfoCircle, cx)
                .child(div().text_sm().child(state.version()))
                .child(
                    div()
                        .text_sm()
                        .text_color(gpui_kit::rgb(theme.text_secondary()))
                        .child(state.text(M::FoundationNote)),
                ),
        );
    if let Some(issue) = state.issue() {
        let owner = preferences.clone();
        content = content.child(
            div()
                .flex()
                .flex_col()
                .gap_3()
                .child(state.text(match issue {
                    StorageIssue::Read => M::SettingsReadFailed,
                    StorageIssue::Save => M::SettingsSaveFailed,
                }))
                .child(
                    YueButton::new("retry-settings", state.text(M::Retry))
                        .disabled(state.is_saving())
                        .on_click(move |_, _, cx| owner.update(cx, |state, cx| state.retry(cx))),
                ),
        );
    }
    // This viewport owns settings scrolling; the inset belongs to its content.
    div()
        .id("settings-scroll")
        .size_full()
        .overflow_y_scroll()
        .child(content)
        .into_any_element()
}

impl Render for Shell {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // A single rem anchor scales the shell and Yue controls together.
        window.set_rem_size(gpui_kit::px(16.));
        let state = self.preferences.read(cx);
        let theme = current_theme(cx);
        let language = self.preferences.clone();
        let connection_service = self.reception.clone();
        let history_service = self.reception.clone();
        let devices_service = self.reception.clone();
        let devices_preferences = self.preferences.clone();
        let review_dialog = self.review_dialog.clone();
        let search = self.search.clone();
        let filter_state = self.history_filter.clone();
        let reception = self.preferences.clone();
        let preferences = self.preferences.clone();
        let status = match state.issue() {
            Some(StorageIssue::Read) => M::SettingsReadFailed,
            Some(StorageIssue::Save) => M::SettingsSaveFailed,
            None if state.is_saving() => M::Saving,
            None => reception_status(self.reception.read(cx)),
        };
        div()
            .size_full()
            .flex()
            .flex_col()
            .min_w_0()
            .min_h_0()
            .bg(gpui_kit::rgb(theme.page()))
            .text_color(gpui_kit::rgb(theme.text_primary()))
            .font_family(yue_component::typography::ui_font(theme).family)
            .text_sm()
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap_3()
                    .px_6()
                    .py_3()
                    .border_b_1()
                    .border_color(gpui_kit::rgb(theme.border_default()))
                    .bg(gpui_kit::rgb(theme.surface()))
                    .child(gpui_kit::img(crate::branding::image()).size(gpui_kit::rems(2.5)))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap_1()
                            .flex_1()
                            .min_w_0()
                            .child(
                                div()
                                    .text_lg()
                                    .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                                    .child(state.text(M::AppName)),
                            )
                            .child(
                                div()
                                    .text_sm()
                                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                                    .child(state.text(M::AppTagline)),
                            ),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .text_sm()
                            .text_color(gpui_kit::rgb(theme.text_secondary()))
                            .child(
                                Icon::new(outline::DeviceMobile)
                                    .size(gpui_kit::rems(1.))
                                    .color_value(gpui_kit::rgb(theme.text_secondary())),
                            )
                            .child(state.text(connection_status(self.reception.read(cx)))),
                    ),
            )
            .child(
                div().flex_1().min_h_0().min_w_0().child(
                    YueTabs::new("main-navigation", Some(self.selected.clone()))
                        .placement(yue_component::TabsPlacement::Left)
                        .header_width(gpui_kit::rems(
                            if f32::from(window.viewport_size().width) < 800. {
                                8.5
                            } else {
                                11.
                            },
                        ))
                        .accessibility_label(state.text(M::Navigation))
                        .height(gpui_kit::relative(1.))
                        .items([
                            YueTabItem::new("connection", state.text(M::Connection))
                                .icon(outline::DeviceDesktop)
                                .content(move |_, cx| {
                                    connection(&language, &connection_service, &review_dialog, cx)
                                }),
                            YueTabItem::new("devices", state.text(M::Devices))
                                .icon(outline::DeviceMobile)
                                .content(move |_, cx| {
                                    devices(&devices_preferences, &devices_service, cx)
                                }),
                            YueTabItem::new("history", state.text(M::History))
                                .icon(outline::History)
                                .content(move |_, cx| {
                                    history(
                                        &reception,
                                        &history_service,
                                        &search,
                                        &filter_state,
                                        cx,
                                    )
                                }),
                            YueTabItem::new("settings", state.text(M::Settings))
                                .icon(outline::Settings)
                                .panel_tab_stop(false)
                                .content(move |_, cx| settings(&preferences, cx)),
                        ])
                        .on_change(cx.listener(
                            |this, request: &yue_component::TabsChangeRequest, _, cx| {
                                this.selected = request.key().clone();
                                cx.notify();
                            },
                        )),
                ),
            )
            .child(
                div()
                    .px_6()
                    .py_3()
                    .border_t_1()
                    .border_color(gpui_kit::rgb(theme.border_default()))
                    .text_xs()
                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                    .child(
                        div()
                            .flex()
                            .flex_wrap()
                            .justify_between()
                            .gap_2()
                            .child(state.text(status))
                            .child(state.text(
                                if !self.reception.read(cx).background_available() {
                                    M::TrayFailed
                                } else {
                                    match state.value().close_behavior() {
                                        CloseBehavior::Ask => M::CloseAskStatus,
                                        CloseBehavior::Quit => M::CloseQuitStatus,
                                        CloseBehavior::Tray => M::TrayHint,
                                    }
                                },
                            )),
                    ),
            )
            .child(
                yue_component::YueDialog::new("pairing-dialog", theme, &self.pairing_dialog)
                    .title(state.text(M::PairingTitle))
                    .close_label(state.text(M::ClosePairing))
                    .footer(yue_component::DialogFooter::None)
                    .width(gpui_kit::rems(38.))
                    .on_close_request({
                        let reception = self.reception.clone();
                        move |_, _, cx| {
                            reception.update(cx, |s, cx| s.close_pairing(cx));
                            true
                        }
                    })
                    .child(pairing(&self.preferences, &self.reception, cx)),
            )
            .child(
                yue_component::YueDialog::new("review-dialog", theme, &self.review_dialog)
                    .width(gpui_kit::rems(32.))
                    .title(state.text(M::ReviewTitle))
                    .description(state.text(M::ReviewHelp))
                    .confirm_label(state.text(M::ReviewedResume))
                    .cancel_label(state.text(M::Cancel))
                    .close_label(state.text(M::CloseDialog))
                    .confirm_disabled(!self.reviewed)
                    .on_confirm({
                        let reception = self.reception.clone();
                        move |_, _, cx| {
                            reception.update(cx, |s, cx| s.resume(true, cx));
                            yue_component::DialogDecision::Close
                        }
                    })
                    .on_close_request({
                        let owner = cx.entity().downgrade();
                        move |_, _, cx| {
                            let _ = owner.update(cx, |this, cx| {
                                this.reviewed = false;
                                cx.notify();
                            });
                            true
                        }
                    })
                    .child(
                        YueCheckbox::new(
                            "review-confirm",
                            theme,
                            if self.reviewed {
                                CheckboxState::Checked
                            } else {
                                CheckboxState::Unchecked
                            },
                        )
                        .label(state.text(M::ReviewCheckbox))
                        .on_change({
                            let owner = cx.entity().downgrade();
                            move |state, _, _, cx| {
                                let _ = owner.update(cx, |this, cx| {
                                    this.reviewed = state == CheckboxState::Checked;
                                    cx.notify();
                                });
                            }
                        }),
                    ),
            )
            .child(
                yue_component::YueDialog::new("close-dialog", theme, &self.close_dialog)
                    .width(gpui_kit::rems(32.))
                    .title(state.text(M::CloseTitle))
                    .description(state.text(M::CloseHelp))
                    .close_label(state.text(M::CloseDialog))
                    .footer(yue_component::DialogFooter::None)
                    .footer_content(
                        div()
                            .flex()
                            .flex_wrap()
                            .justify_end()
                            .gap_2()
                            .child(
                                YueButton::new("close-cancel", state.text(M::Cancel))
                                    .variant(ButtonVariant::Ghost)
                                    .on_click({
                                        let dialog = self.close_dialog.clone();
                                        move |_, window, cx| {
                                            dialog.update(cx, |s, cx| s.close(window, cx));
                                        }
                                    }),
                            )
                            .child(
                                action("quit-app", state.text(M::QuitApp), outline::Logout, cx)
                                    .on_click({
                                        let owner = cx.entity().downgrade();
                                        move |_, _, cx| {
                                            let _ = owner.update(cx, |_, cx| {
                                                cx.emit(ShellEvent::QuitRequested)
                                            });
                                        }
                                    }),
                            )
                            .child(
                                action(
                                    "hide-to-tray",
                                    state.text(M::ContinueBackground),
                                    outline::Microphone,
                                    cx,
                                )
                                .variant(ButtonVariant::Primary)
                                .disabled(!self.reception.read(cx).background_available())
                                .on_click(|_, window, _| window.remove_window()),
                            ),
                    ),
            )
    }
}
