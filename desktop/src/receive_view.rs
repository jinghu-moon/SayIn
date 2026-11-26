//! Receive owns presentation and disclosures; ReceptionState owns all side effects.
use crate::{
    history_view::HistoryView,
    localization::Message as M,
    preferences::PreferencesState,
    reception::{ReceptionIssue, ReceptionPhase, ReceptionState},
    theme,
};
use gpui_kit::prelude::FluentBuilder as _;
use yue_component::{
    ButtonVariant, DialogFooter, Icon, YueButton, YueDialog, YueDialogState, current_theme,
    gpui_kit::{
        self, App, AppContext as _, Context, Entity, InteractiveElement as _, IntoElement,
        ParentElement as _, Render, StatefulInteractiveElement as _, Styled as _, Subscription,
        Window, div,
    },
    outline,
};

pub(crate) enum ReceiveEvent {
    OpenHistory,
}
pub(crate) struct ReceiveView {
    preferences: Entity<PreferencesState>,
    reception: Entity<ReceptionState>,
    review: Entity<YueDialogState>,
    help: Entity<YueDialogState>,
    recent: Entity<HistoryView>,
    code: bool,
    invitation_id: Option<String>,
    network_details: bool,
    _subscriptions: Vec<Subscription>,
}
impl gpui_kit::EventEmitter<ReceiveEvent> for ReceiveView {}

impl ReceiveView {
    pub(crate) fn new(
        preferences: Entity<PreferencesState>,
        reception: Entity<ReceptionState>,
        review: Entity<YueDialogState>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let changes = cx.observe(&reception, |this, reception, cx| {
            let id = reception
                .read(cx)
                .network_snapshot()
                .and_then(|s| s.window.as_ref())
                .map(|w| w.id.clone());
            if this.invitation_id != id {
                this.invitation_id = id;
                this.code = false;
            }
            cx.notify();
        });
        let language = cx.observe(&preferences, |_, _, cx| cx.notify());
        let recent =
            cx.new(|cx| HistoryView::recent(preferences.clone(), reception.clone(), window, cx));
        Self {
            preferences,
            reception,
            review,
            help: cx.new(YueDialogState::new),
            recent,
            code: false,
            invitation_id: None,
            network_details: false,
            _subscriptions: vec![changes, language],
        }
    }
    fn help_button(&self, cx: &Context<Self>) -> YueButton {
        YueButton::new(
            "receive-help",
            self.preferences.read(cx).text(M::ReceiveHelp),
        )
        .variant(ButtonVariant::Ghost)
        .on_click(cx.listener(|this, _, window, cx| {
            let _ = this.help.update(cx, |s, cx| s.open(window, cx));
        }))
    }
    fn invitation(&self, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let state = self.reception.read(cx);
        let settings = self.preferences.read(cx);
        let network = state.network_snapshot();
        let mut panel = div()
            .min_w_0()
            .w_full()
            .min_h(gpui_kit::rems(23.))
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap_3()
            .p_6();
        let replaces = network
            .and_then(|snapshot| snapshot.approval.as_ref())
            .and_then(|approval| approval.replaces.as_deref())
            .or_else(|| state.replacing_pair());
        if let Some(id) = replaces {
            let name = state
                .snapshot()
                .and_then(|snapshot| snapshot.device_names.get(id))
                .filter(|name| !name.is_empty())
                .cloned()
                .unwrap_or_else(|| settings.text(M::PhoneDevice));
            panel = panel
                .child(note(settings.text(M::ReplacingPairing), cx))
                .child(div().text_sm().child(format!("{name} · {}", &id[..8])));
        }
        if let Some(approval) = network.and_then(|s| s.approval.as_ref()) {
            let approve = self.reception.clone();
            let reject = self.reception.clone();
            let approve_id = approval.id.clone();
            let reject_id = approval.id.clone();
            panel = panel
                .child(
                    Icon::new(outline::DeviceMobile)
                        .size(gpui_kit::rems(2.5))
                        .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
                )
                .child(
                    div()
                        .text_lg()
                        .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                        .child(approval.name.clone()),
                )
                .child(note(approval.source.to_string(), cx))
                .child(note(settings.text(M::ReceiveApproval), cx))
                .child(
                    div()
                        .flex()
                        .gap_2()
                        .child(
                            YueButton::new("reject-phone", settings.text(M::Reject)).on_click(
                                move |_, _, cx| {
                                    reject.update(cx, |s, cx| {
                                        s.approve_pairing(reject_id.clone(), false, cx)
                                    })
                                },
                            ),
                        )
                        .child(
                            YueButton::new("approve-phone", settings.text(M::Approve))
                                .variant(ButtonVariant::Primary)
                                .on_click(move |_, _, cx| {
                                    approve.update(cx, |s, cx| {
                                        s.approve_pairing(approve_id.clone(), true, cx)
                                    })
                                }),
                        ),
                )
                .child(note(settings.text(M::ReceiveVerifyPhone), cx));
        } else if let Some(invitation) = network
            .and_then(|s| s.window.as_ref())
            .filter(|w| std::time::Instant::now() < w.expires)
        {
            panel = panel.child(div().text_sm().child(settings.text(if self.code {
                M::ReceiveCodeTitle
            } else {
                M::ReceiveScanTitle
            })));
            let mut method = div()
                .w_full()
                .min_h(gpui_kit::rems(16.))
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .gap_5();
            if self.code {
                let code = invitation.code.as_str();
                method = method
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .items_center()
                            .gap_2()
                            .child(note(settings.text(M::PairingCode), cx))
                            .child(
                                div()
                                    .font_family("Consolas")
                                    .text_2xl()
                                    .font_weight(gpui_kit::FontWeight::MEDIUM)
                                    .child(format!("{} {}", &code[..4], &code[4..])),
                            ),
                    )
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .items_center()
                            .gap_2()
                            .child(note(settings.text(M::PairingAddress), cx))
                            .child(
                                div()
                                    .font_family("Consolas")
                                    .text_sm()
                                    .child(network.unwrap().address.to_string()),
                            ),
                    );
            } else if let Some(image) = state.pairing_image() {
                method = method.child(crate::shell::pairing_qr(
                    image,
                    invitation.qr_modules(),
                    settings.text(M::PairingQr),
                ));
            }
            panel = panel
                .child(method)
                .child(note(settings.text(M::PairingExpiry), cx))
                .child(
                    (if self.code {
                        YueButton::icon_text(
                            "pairing-method",
                            current_theme(cx),
                            outline::ChevronLeft,
                            settings.text(M::ReceiveBackQr),
                        )
                    } else {
                        YueButton::icon_text(
                            "pairing-method",
                            current_theme(cx),
                            outline::ChevronRight,
                            settings.text(M::ReceiveUseCode),
                        )
                    })
                    .variant(ButtonVariant::Ghost)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.code = !this.code;
                        cx.notify();
                    })),
                );
        } else {
            let owner = self.reception.clone();
            panel = panel
                .child(note(
                    settings.text(if state.is_busy() {
                        M::ReceiverLoading
                    } else {
                        M::ReceiveInviteExpired
                    }),
                    cx,
                ))
                .child(
                    YueButton::new("refresh-invitation", settings.text(M::ReceiveRefreshInvite))
                        .disabled(state.is_busy() || !state.is_ready())
                        .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.prepare_network(cx))),
                );
        }
        panel.into_any_element()
    }
    fn first_connection(&self, wide: bool, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let settings = self.preferences.read(cx);
        let instructions = div()
            .flex_1()
            .min_w_0()
            .p_8()
            .flex()
            .flex_col()
            .justify_center()
            .gap_5()
            .child(title(settings.text(M::ReceiveFirstTitle)))
            .child(note(settings.text(M::ReceiveFirstHelp), cx))
            .child(step(
                "01",
                settings.text(M::ReceiveScanStep),
                settings.text(M::ReceiveScanHelp),
                cx,
            ))
            .child(step(
                "02",
                settings.text(M::ReceiveApproveStep),
                settings.text(M::ReceiveApproveHelp),
                cx,
            ))
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap_2()
                    .child(note(settings.text(M::ReceiveSameNetwork), cx))
                    .child(self.help_button(cx)),
            );
        let invitation = div()
            .when(wide, |d| d.w(gpui_kit::rems(22.)).flex_none().border_l_1())
            .when(!wide, |d| d.w_full().border_t_1())
            .border_color(gpui_kit::rgb(current_theme(cx).border_default()))
            .child(self.invitation(cx));
        surface("receive-first", cx)
            .flex()
            .items_stretch()
            .when(!wide, |d| d.flex_col())
            .child(instructions)
            .child(invitation)
            .into_any_element()
    }
    fn device_header(&self, online: bool, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let settings = self.preferences.read(cx);
        let snapshot = self.reception.read(cx).snapshot();
        let name = snapshot
            .and_then(|s| {
                s.control
                    .active_device_id
                    .as_ref()
                    .and_then(|id| s.device_names.get(id))
            })
            .filter(|name| !name.is_empty())
            .cloned()
            .unwrap_or_else(|| settings.text(M::PhoneDevice));
        div()
            .p_6()
            .min_w_0()
            .flex()
            .items_center()
            .gap_4()
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_center()
                    .flex_none()
                    .p_3()
                    .rounded_md()
                    .bg(gpui_kit::rgb(current_theme(cx).surface_component()))
                    .child(
                        Icon::new(outline::DeviceMobile)
                            .size(gpui_kit::rems(1.5))
                            .color_value(gpui_kit::rgb(current_theme(cx).text_secondary())),
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
                            .text_base()
                            .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                            .child(name),
                    )
                    .child(note(
                        settings.text(if online {
                            M::ReceiveLocalConnection
                        } else {
                            M::ReceivePaired
                        }),
                        cx,
                    )),
            )
            .child(
                div()
                    .flex_none()
                    .text_xs()
                    .text_color(if online {
                        theme::brand()
                    } else {
                        gpui_kit::rgb(current_theme(cx).text_secondary()).into()
                    })
                    .child(settings.text(if online {
                        M::PhoneConnected
                    } else {
                        M::PhoneOffline
                    })),
            )
            .into_any_element()
    }
    fn input_row(&self, online: bool, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let state = self.reception.read(cx);
        let settings = self.preferences.read(cx);
        let snapshot = state.snapshot();
        let review = snapshot
            .is_some_and(|s| s.control.pause_reason.as_deref() == Some("outcome_review_required"));
        let paused = snapshot.is_none_or(|s| s.control.paused);
        let held = snapshot.is_some_and(|s| s.blocked.is_some());
        let failed = !state.is_ready();
        let title_key = if failed {
            M::ReceiverStorageFailed
        } else if review {
            M::ReceiveReviewTitle
        } else if held {
            M::ReceiveHeldTitle
        } else if !online {
            M::ReceiveReconnectTitle
        } else if paused {
            M::ReceiverPaused
        } else {
            M::ReceiverRunning
        };
        let help_key = if failed {
            M::ReceiveStorageHelp
        } else if review {
            M::ReviewHelp
        } else if held {
            M::ReceiveHeldHelp
        } else if !online {
            M::ReceiveReconnectHelp
        } else if paused {
            M::ReadyToEnable
        } else {
            M::CursorReady
        };
        let owner = self.reception.clone();
        let review_owner = self.review.clone();
        let action = if review {
            YueButton::new("receiving-toggle", settings.text(M::ReviewInput))
                .variant(ButtonVariant::Primary)
                .disabled(!state.is_ready() || state.is_busy())
                .on_click(move |_, window, cx| {
                    let _ = review_owner.update(cx, |s, cx| s.open(window, cx));
                })
        } else if held || failed {
            self.help_button(cx)
        } else {
            (if paused {
                YueButton::icon_text(
                    "receiving-toggle",
                    current_theme(cx),
                    outline::PlayerPlay,
                    settings.text(M::ResumeInput),
                )
            } else {
                YueButton::icon_text(
                    "receiving-toggle",
                    current_theme(cx),
                    outline::PlayerPause,
                    settings.text(M::PauseInput),
                )
            })
            .variant(if paused {
                ButtonVariant::Primary
            } else {
                ButtonVariant::Outline
            })
            .disabled(!state.is_ready() || state.is_busy())
            .on_click(move |_, _, cx| {
                owner.update(cx, |s, cx| if paused { s.resume(cx) } else { s.pause(cx) })
            })
        };
        div()
            .border_t_1()
            .border_color(gpui_kit::rgb(current_theme(cx).border_default()))
            .p_6()
            .flex()
            .flex_wrap()
            .items_center()
            .gap_4()
            .child(
                div()
                    .flex_1()
                    .min_w(gpui_kit::rems(14.))
                    .flex()
                    .flex_col()
                    .gap_2()
                    .child(title(settings.text(title_key)))
                    .child(note(settings.text(help_key), cx))
                    .when(!online && !review && !held && !failed, |d| {
                        d.child(note(
                            settings.text(if paused {
                                M::ReceiverPaused
                            } else {
                                M::ReceiverRunning
                            }),
                            cx,
                        ))
                    }),
            )
            .child(action)
            .into_any_element()
    }
    fn offline(&self, paired: bool, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let state = self.reception.read(cx);
        let settings = self.preferences.read(cx);
        let owner = self.reception.clone();
        let mut content = surface("receive-offline", cx).flex().flex_col();
        if paired {
            content = content.child(self.device_header(false, cx));
        }
        let mut body = div()
            .p_6()
            .flex()
            .flex_col()
            .gap_3()
            .child(title(settings.text(M::ReceiveNetworkTitle)))
            .child(note(settings.text(M::ReceiveNetworkHelp), cx));
        if let Some(snapshot) = state.snapshot() {
            let retained =
                if snapshot.control.pause_reason.as_deref() == Some("outcome_review_required") {
                    Some(M::ReceiveReviewRetained)
                } else if snapshot.control.pause_reason.as_deref() == Some("paused") {
                    Some(M::ReceivePauseRetained)
                } else {
                    None
                };
            if let Some(message) = retained {
                body = body.child(note(settings.text(message), cx));
            }
        }
        content
            .child(
                body.child(
                    div()
                        .flex()
                        .flex_wrap()
                        .gap_2()
                        .child(
                            YueButton::new("retry-network", settings.text(M::ReceiveRetryNetwork))
                                .disabled(state.is_busy())
                                .loading(state.is_busy())
                                .on_click(move |_, _, cx| {
                                    owner.update(cx, |s, cx| s.retry_network(cx))
                                }),
                        )
                        .child(self.help_button(cx)),
                ),
            )
            .into_any_element()
    }
    fn help_content(&self, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let settings = self.preferences.read(cx);
        let service = self.reception.read(cx);
        let mut body = div()
            .flex()
            .flex_col()
            .gap_4()
            .child(note(settings.text(M::ReceiverHelp), cx))
            .child(note(settings.text(M::ReceiveSameNetwork), cx))
            .child(note(settings.text(M::NetworkFirewallHelp), cx));
        if let Some(receipt) = service.snapshot().and_then(|s| s.blocked.as_ref()) {
            body = body.child(note(settings.receipt_help(receipt), cx));
        }
        body = body.child(
            YueButton::icon_text(
                "network-details",
                current_theme(cx),
                outline::ChevronDown,
                settings.text(M::ReceiveNetworkDetails),
            )
            .variant(ButtonVariant::Ghost)
            .on_click(cx.listener(|this, _, _, cx| {
                this.network_details = !this.network_details;
                cx.notify();
            })),
        );
        if self.network_details {
            let address = service
                .network_snapshot()
                .map(|s| s.address.to_string())
                .unwrap_or_else(|| settings.text(M::ReceiveNoAddress));
            body = body.child(note(address, cx));
            if let Some(network) = service.network_snapshot() {
                use voicelink_transport::server::PublicationStatus;
                let message = match network.discovery {
                    PublicationStatus::Publishing => Some(M::DiscoveryPublishing),
                    PublicationStatus::Published => Some(M::DiscoveryPublished),
                    PublicationStatus::Unavailable => Some(M::DiscoveryUnavailable),
                    PublicationStatus::Disabled => None,
                };
                if let Some(message) = message {
                    body = body.child(note(settings.text(message), cx));
                }
            }
            if let Some(interface) = service.selected_interface() {
                body = body.child(note(
                    format!(
                        "{} · {}",
                        interface.name,
                        settings.text(if interface.is_public() {
                            M::NetworkPublic
                        } else if interface.profiles.iter().any(|p| p.category
                            == voicelink_transport::interfaces::NetworkCategory::Private)
                        {
                            M::NetworkPrivate
                        } else {
                            M::NetworkDomain
                        })
                    ),
                    cx,
                ));
            }
            body = body.child(note(settings.text(M::ReceiveNetworkPolicy), cx));
            if service.interfaces().len() > 1 {
                body = body.child(note(settings.text(M::ReceiveOtherNetwork), cx));
                for interface in service.interfaces() {
                    if service
                        .selected_interface()
                        .is_some_and(|i| i.key() == interface.key())
                    {
                        continue;
                    }
                    let key = interface.key();
                    let owner = self.reception.clone();
                    body = body.child(
                        YueButton::new(
                            gpui_kit::SharedString::from(format!("network-{key}")),
                            format!("{} · {}", interface.name, interface.address),
                        )
                        .disabled(service.is_busy())
                        .on_click(move |_, _, cx| {
                            owner.update(cx, |s, cx| s.select_interface(&key, cx))
                        }),
                    );
                }
            }
        }
        body.into_any_element()
    }
}
impl Render for ReceiveView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let state = self.reception.read(cx);
        let settings = self.preferences.read(cx);
        let paired = state.snapshot().is_some_and(|s| s.paired_devices > 0);
        let network = state.network_snapshot();
        let online = network.is_some_and(|s| s.running && s.connections > 0);
        let inviting = !paired
            || network.is_some_and(|s| s.window.is_some() || s.approval.is_some())
            || state.pairing_requested();
        let width = f32::from(window.viewport_size().width) / f32::from(window.rem_size())
            - crate::shell::sidebar_rems(window)
            - 3.;
        let mut content = div()
            .w_full()
            .max_w(gpui_kit::rems(65.))
            .mx_auto()
            .min_w_0()
            .p_6()
            .flex()
            .flex_col()
            .gap_5()
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap_2()
                    .child(
                        div()
                            .text_xl()
                            .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                            .child(settings.text(M::Connection)),
                    )
                    .child(note(settings.text(M::ConnectionDescription), cx)),
            );
        if matches!(
            state.phase(),
            ReceptionPhase::Failed | ReceptionPhase::Loading
        ) {
            let owner = self.reception.clone();
            content = content.child(
                surface("receive-startup", cx)
                    .p_6()
                    .flex()
                    .flex_col()
                    .gap_3()
                    .child(title(settings.text(
                        if state.phase() == ReceptionPhase::Failed {
                            M::ReceiverFailed
                        } else {
                            M::ReceiverLoading
                        },
                    )))
                    .when(state.phase() == ReceptionPhase::Failed, |d| {
                        d.child(
                            YueButton::new("retry-receiver", settings.text(M::Retry)).on_click(
                                move |_, _, cx| owner.update(cx, |s, cx| s.retry_start(cx)),
                            ),
                        )
                    }),
            );
        } else if !state.is_ready() {
            content = content.child(
                surface("receive-storage", cx)
                    .p_6()
                    .flex()
                    .flex_col()
                    .gap_3()
                    .child(title(settings.text(M::ReceiverStorageFailed)))
                    .child(note(settings.text(M::ReceiveStorageHelp), cx))
                    .child(self.help_button(cx)),
            );
        } else if state.issue() == Some(ReceptionIssue::Network) && network.is_none() {
            content = content.child(self.offline(paired, cx));
        } else if inviting {
            content = content.child(self.first_connection(width >= 49., cx));
            if paired || network.is_some_and(|s| s.window.is_some()) {
                let owner = self.reception.clone();
                content = content.child(
                    div().flex().child(
                        YueButton::new("close-pairing", settings.text(M::ClosePairing))
                            .variant(ButtonVariant::Ghost)
                            .on_click(move |_, _, cx| {
                                owner.update(cx, |s, cx| s.close_pairing(cx))
                            }),
                    ),
                );
            }
        } else {
            content = content.child(
                surface("receive-device", cx)
                    .flex()
                    .flex_col()
                    .child(self.device_header(online, cx))
                    .child(self.input_row(online, cx)),
            );
            // Reuse one existing help entry; no additional network panel in daily use.
            if state.is_ready() && state.snapshot().is_some_and(|s| s.blocked.is_none()) {
                content = content.child(
                    div()
                        .flex()
                        .flex_wrap()
                        .items_center()
                        .justify_between()
                        .gap_2()
                        .child(note(settings.text(M::ReceiveCursorTip), cx))
                        .child(self.help_button(cx)),
                );
            }
        }
        if state.issue() == Some(ReceptionIssue::Command) {
            content = content.child(note(settings.text(M::ReceiverCommandFailed), cx));
        }
        if paired {
            content = content
                .child(
                    div()
                        .flex()
                        .items_center()
                        .justify_between()
                        .gap_3()
                        .child(
                            div()
                                .font_weight(gpui_kit::FontWeight::MEDIUM)
                                .child(settings.text(M::LatestMessage)),
                        )
                        .child(
                            YueButton::icon_text(
                                "receive-history",
                                current_theme(cx),
                                outline::ChevronRight,
                                settings.text(M::ReceiveAllHistory),
                            )
                            .variant(ButtonVariant::Ghost)
                            .on_click(
                                cx.listener(|_, _, _, cx| cx.emit(ReceiveEvent::OpenHistory)),
                            ),
                        ),
                )
                .child(self.recent.clone());
        }
        div()
            .size_full()
            .flex()
            .flex_col()
            .child(
                div()
                    .id("connection-scroll")
                    .size_full()
                    .overflow_y_scroll()
                    .child(content),
            )
            .child(
                YueDialog::new("receive-help-dialog", current_theme(cx), &self.help)
                    .title(settings.text(M::ReceiveHelpTitle))
                    .close_label(settings.text(M::CloseDialog))
                    .footer(DialogFooter::None)
                    .width(gpui_kit::rems(32.))
                    .child(self.help_content(cx)),
            )
    }
}

fn title(text: String) -> impl IntoElement {
    div()
        .text_base()
        .font_weight(gpui_kit::FontWeight::SEMIBOLD)
        .child(text)
}
fn note(text: String, cx: &App) -> impl IntoElement {
    div()
        .min_w_0()
        .text_sm()
        .line_height(gpui_kit::relative(1.6))
        .text_color(gpui_kit::rgb(current_theme(cx).text_secondary()))
        .child(text)
}
fn surface(id: &'static str, cx: &App) -> gpui_kit::Stateful<gpui_kit::Div> {
    div()
        .id(id)
        .w_full()
        .min_w_0()
        .rounded_lg()
        .border_1()
        .border_color(gpui_kit::rgb(current_theme(cx).border_default()))
        .bg(gpui_kit::rgb(current_theme(cx).surface()))
}
fn step(number: &'static str, name: String, help: String, cx: &App) -> impl IntoElement {
    div()
        .flex()
        .items_start()
        .gap_3()
        .child(
            div()
                .text_xs()
                .text_color(gpui_kit::rgb(current_theme(cx).text_secondary()))
                .child(number),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap_1()
                .child(div().font_weight(gpui_kit::FontWeight::MEDIUM).child(name))
                .child(note(help, cx)),
        )
}
