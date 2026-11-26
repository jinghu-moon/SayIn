//! The shell owns navigation only. Application preferences outlive its window.
use crate::{
    localization::{LanguagePreference, Message as M},
    preferences::{AppearancePreference, CloseBehavior, PreferencesState, StorageIssue},
    reception::{ReceptionPhase, ReceptionState, ReviewScope},
    theme,
};
use voicelink_receiver::windows_image_delivery::ImageDeliveryMode;
use yue_component::gpui_kit::base::{
    Tab, TabDescriptor, TabPanel, Tabs, TabsController, TabsSnapshot, TestSupportExt as _,
};
use yue_component::{
    ButtonVariant, CheckboxState, Icon, IconDefinition, SelectEvent, SelectMode, SelectOption,
    SelectValue, YueButton, YueCard, YueCardHeader, YueCheckbox, YueDropdownMenu, YueEmpty,
    YueMenuItem, YueSelect, YueSelectState, current_theme,
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
    navigation: Entity<TabsController>,
    receive: Entity<crate::receive_view::ReceiveView>,
    last_pairing_request: bool,
    review_dialog: Entity<yue_component::YueDialogState>,
    history: Entity<crate::history_view::HistoryView>,
    settings: SettingsControls,
    reviewed: Option<ReviewScope>,
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
            this.settings.synchronize(&this.preferences, window, cx);
            cx.notify();
        });

        let review_dialog = cx.new(yue_component::YueDialogState::new);
        let close_dialog = cx.new(yue_component::YueDialogState::new);
        let owner = cx.entity().downgrade();
        window.on_window_should_close(cx, move |window, cx| {
            owner
                .update(cx, |this, cx| this.request_close(window, cx))
                .unwrap_or(true)
        });
        let history = cx.new(|cx| {
            crate::history_view::HistoryView::new(
                preferences.clone(),
                reception.clone(),
                window,
                cx,
            )
        });
        let receive = cx.new(|cx| {
            crate::receive_view::ReceiveView::new(
                preferences.clone(),
                reception.clone(),
                review_dialog.clone(),
                window,
                cx,
            )
        });
        let navigating = cx.subscribe(&receive, |this, _, event, cx| {
            match event {
                crate::receive_view::ReceiveEvent::OpenHistory => this.selected = "history".into(),
            }
            cx.notify();
        });
        let receiving = cx.observe(&reception, |this, state, cx| {
            if this.reviewed.as_ref() != state.read(cx).review_scope().as_ref() {
                this.reviewed = None;
            }
            let requested = state.read(cx).pairing_requested();
            if requested && !this.last_pairing_request {
                this.selected = "connection".into();
            }
            this.last_pairing_request = requested;
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
        let settings = SettingsControls::new(&preferences, window, cx);
        let this = Self {
            preferences,
            reception,
            selected: "connection".into(),
            navigation: cx.new(|cx| TabsController::new(cx)),
            receive,
            last_pairing_request: false,
            review_dialog,
            history,
            settings,
            reviewed: None,
            close_dialog,
            _subscriptions: vec![changed, receiving, appearance, activation, navigating],
        };
        this.synchronize_window(window, cx);
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
        if current_theme(cx).mode() != mode {
            theme::install(mode, cx);
        }
        window.set_window_title(&name);
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
            let replace = reception.clone();
            let id = device.clone();
            let replace_id = device.clone();
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
                        YueButton::new(
                            gpui_kit::SharedString::from(format!("replace-pairing-{device}")),
                            settings.text(M::ReplacePairing),
                        )
                        .variant(ButtonVariant::Ghost)
                        .disabled(!service.is_ready() || service.is_busy())
                        .on_click(move |_, _, cx| {
                            replace
                                .update(cx, |s, cx| s.prepare_replacement(replace_id.clone(), cx))
                        }),
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
        .size(gpui_kit::rems(16.))
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

/// Retained field behavior; PreferencesState is the only owner of saved values.
struct SettingsControls {
    language: Entity<YueSelectState>,
    appearance: Entity<YueSelectState>,
    close_behavior: Entity<YueSelectState>,
    _subscriptions: Vec<Subscription>,
}
impl SettingsControls {
    fn new(
        preferences: &Entity<PreferencesState>,
        window: &mut Window,
        cx: &mut Context<Shell>,
    ) -> Self {
        let language = cx.new(|cx| YueSelectState::new(SelectMode::Single, window, cx));
        let appearance = cx.new(|cx| YueSelectState::new(SelectMode::Single, window, cx));
        let close_behavior = cx.new(|cx| YueSelectState::new(SelectMode::Single, window, cx));
        enum Choice {
            Language,
            Appearance,
            Close,
        }
        let subscriptions = [
            (&language, Choice::Language),
            (&appearance, Choice::Appearance),
            (&close_behavior, Choice::Close),
        ]
        .into_iter()
        .map(|(control, choice)| {
            let owner = preferences.clone();
            cx.subscribe(control, move |_, _, event, cx| {
                let SelectEvent::Change(change) = event else {
                    return;
                };
                let Some(key) = change.after().keys().first() else {
                    return;
                };
                owner.update(cx, |state, cx| {
                    let next = match choice {
                        Choice::Language => {
                            state.value().clone().with_language(match key.as_ref() {
                                "zh-Hans" => LanguagePreference::Chinese,
                                "en" => LanguagePreference::English,
                                _ => LanguagePreference::System,
                            })
                        }
                        Choice::Appearance => {
                            state.value().clone().with_appearance(match key.as_ref() {
                                "light" => AppearancePreference::Light,
                                "dark" => AppearancePreference::Dark,
                                _ => AppearancePreference::System,
                            })
                        }
                        Choice::Close => {
                            state
                                .value()
                                .clone()
                                .with_close_behavior(match key.as_ref() {
                                    "tray" => CloseBehavior::Tray,
                                    "quit" => CloseBehavior::Quit,
                                    _ => CloseBehavior::Ask,
                                })
                        }
                    };
                    if &next != state.value() {
                        state.save(next, cx);
                    }
                });
            })
        })
        .collect();
        let this = Self {
            language,
            appearance,
            close_behavior,
            _subscriptions: subscriptions,
        };
        this.synchronize(preferences, window, cx);
        this
    }

    fn synchronize(
        &self,
        preferences: &Entity<PreferencesState>,
        window: &mut Window,
        cx: &mut App,
    ) {
        let state = preferences.read(cx);
        let disabled = !state.is_editable();
        let fields = [
            (
                &self.language,
                state.value().language().key(),
                [
                    SelectOption::new("system", state.text(M::FollowSystem)),
                    SelectOption::new("zh-Hans", state.text(M::LanguageChinese)),
                    SelectOption::new("en", state.text(M::LanguageEnglish)),
                ],
            ),
            (
                &self.appearance,
                state.value().appearance().key(),
                [
                    SelectOption::new("system", state.text(M::FollowSystem)),
                    SelectOption::new("light", state.text(M::ThemeLight)),
                    SelectOption::new("dark", state.text(M::ThemeDark)),
                ],
            ),
            (
                &self.close_behavior,
                state.value().close_behavior().key(),
                [
                    SelectOption::new("ask", state.text(M::CloseAsk)),
                    SelectOption::new("tray", state.text(M::CloseTray)),
                    SelectOption::new("quit", state.text(M::CloseQuit)),
                ],
            ),
        ];
        for (control, key, options) in fields {
            control.update(cx, |select, cx| {
                select.set_options(options, cx).expect("preference options");
                select
                    .set_value(SelectValue::Single(Some(key.into())), window, cx)
                    .expect("preference value");
                select.set_disabled(disabled, window, cx);
            });
        }
    }
}

fn preference_card(
    id: &'static str,
    title: String,
    help: String,
    icon: impl IconDefinition,
    select: &Entity<YueSelectState>,
    cx: &App,
) -> gpui_kit::AnyElement {
    section(id, title.clone(), icon, cx)
        .child(
            div().w_full().max_w(gpui_kit::rems(20.)).child(
                YueSelect::new(id, select)
                    .block()
                    .accessibility_label(title)
                    .accessibility_description(help.clone()),
            ),
        )
        .child(muted(help, cx))
        .into_any_element()
}

fn settings(
    preferences: &Entity<PreferencesState>,
    controls: &SettingsControls,
    cx: &mut App,
) -> gpui_kit::AnyElement {
    let state = preferences.read(cx);
    let theme = current_theme(cx);
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
        ));
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
    let content = content
        .child(preference_card(
            "language-card",
            state.text(M::Language),
            state.text(M::LanguageDescription),
            outline::Language,
            &controls.language,
            cx,
        ))
        .child(preference_card(
            "appearance-card",
            state.text(M::Appearance),
            state.text(M::AppearanceDescription),
            outline::Palette,
            &controls.appearance,
            cx,
        ))
        .child(preference_card(
            "close-behavior-card",
            state.text(M::CloseBehavior),
            state.text(M::CloseBehaviorHelp),
            outline::AppWindow,
            &controls.close_behavior,
            cx,
        ))
        .child({
            let owner = preferences.clone();
            let mut card = section(
                "image-directory-card",
                state.text(M::ImageDirectory),
                outline::Folder,
                cx,
            )
            .child(
                div()
                    .text_sm()
                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                    .child(state.text(M::ImageDirectoryHelp)),
            );
            if let Some(path) = state.image_directory() {
                card = card.child(div().min_w_0().text_sm().child(path.display().to_string()));
            }
            if let Some(issue) = state.image_picker_issue() {
                card = card.child(div().text_sm().child(state.text(issue)));
            }
            card.child(
                div().child(
                    YueButton::new(
                        "choose-image-directory",
                        state.text(M::ChooseImageDirectory),
                    )
                    .disabled(!state.is_editable() || state.image_picker_busy())
                    .on_click(move |_, _, cx| {
                        owner.update(cx, |state, cx| state.pick_image_path(None, cx))
                    }),
                ),
            )
            .child(image_application_rules(preferences, cx))
        })
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
    // This viewport owns settings scrolling; the inset belongs to its content.
    div()
        .id("settings-scroll")
        .size_full()
        .overflow_y_scroll()
        .child(content)
        .into_any_element()
}

fn image_application_rules(preferences: &Entity<PreferencesState>, cx: &App) -> impl IntoElement {
    use ImageDeliveryMode::{CopyPath, PasteImage};

    let state = preferences.read(cx);
    let theme = current_theme(cx);
    let modes = [
        (PasteImage, M::ImageModePaste),
        (CopyPath, M::ImageModeCopyPath),
    ];
    let add = YueDropdownMenu::new(
        "add-image-application-menu",
        theme,
        YueButton::new("add-image-application", state.text(M::ImageAddApplication))
            .disabled(!state.is_editable() || state.image_picker_busy()),
    )
    .items(modes.map(|(mode, label)| {
        let owner = preferences.clone();
        YueMenuItem::new(format!("{}…", state.text(label))).on_click(move |_, _, cx| {
            owner.update(cx, |state, cx| state.pick_image_path(Some(mode), cx));
        })
    }));

    div()
        .flex()
        .flex_col()
        .gap_3()
        .child(
            div()
                .text_sm()
                .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                .child(state.text(M::ImageApplicationRules)),
        )
        .child(muted(state.text(M::ImageApplicationDefaults), cx))
        .children(state.image_applications().iter().map(|(path, current)| {
            let executable = std::path::Path::new(path)
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or(path);
            let label = state.text(match current {
                PasteImage => M::ImageModePaste,
                CopyPath => M::ImageModeCopyPath,
            });
            let options = [
                (Some(PasteImage), M::ImageModePaste),
                (Some(CopyPath), M::ImageModeCopyPath),
                (None, M::ImageUseDefault),
            ];
            let menu = YueDropdownMenu::new(
                gpui_kit::SharedString::from(format!("image-application-menu-{path}")),
                theme,
                YueButton::new(
                    gpui_kit::SharedString::from(format!("image-application-mode-{path}")),
                    label.clone(),
                )
                .accessibility_label(format!("{executable} · {label}"))
                .disabled(!state.is_editable()),
            )
            .items(options.map(|(mode, message)| {
                let owner = preferences.clone();
                let path = path.clone();
                YueMenuItem::new(state.text(message))
                    .checked(mode == Some(*current))
                    .on_click(move |_, _, cx| {
                        owner.update(cx, |state, cx| state.set_image_application(&path, mode, cx));
                    })
            }));
            div()
                .flex()
                .flex_wrap()
                .items_center()
                .justify_between()
                .gap_2()
                .child(
                    div()
                        .min_w_0()
                        .flex_1()
                        .child(div().text_sm().child(executable.to_owned()))
                        .child(muted(path.clone(), cx)),
                )
                .child(menu)
        }))
        .child(div().child(add))
}

impl Shell {
    fn render_navigation(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> gpui_kit::AnyElement {
        let theme = current_theme(cx);
        let items = [
            (
                "connection",
                M::Connection,
                Icon::new(outline::DeviceDesktop),
            ),
            ("devices", M::Devices, Icon::new(outline::DeviceMobile)),
            ("history", M::History, Icon::new(outline::History)),
            ("settings", M::Settings, Icon::new(outline::Settings)),
        ];
        let change = cx.listener(|this, request: &yue_component::TabsChangeRequest, _, cx| {
            this.selected = request.key().clone();
            cx.notify();
        });
        // Base retains roving focus and keyboard activation. The shell owns the
        // committed destination and presents it as navigation, not document tabs.
        self.navigation.update(cx, |navigation, cx| {
            navigation.sync(
                TabsSnapshot::new(
                    items.iter().map(|(key, _, _)| TabDescriptor::new(*key)),
                    Some(self.selected.clone()),
                )
                .axis(gpui_kit::Axis::Vertical),
                window,
                cx,
            );
            navigation.on_change(change);
        });
        let preferences = self.preferences.read(cx);
        let mut sidebar = Tabs::new("main-navigation")
            .axis(gpui_kit::Axis::Vertical)
            .aria_label(preferences.text(M::Navigation))
            .flex()
            .flex_col()
            .flex_shrink_0()
            .w(gpui_kit::rems(sidebar_rems(window)))
            .h_full()
            .p_3()
            .gap_1()
            .border_r_1()
            .border_color(gpui_kit::rgb(theme.border_default()))
            .bg(gpui_kit::rgb(theme.page()));
        let item_count = items.len();
        for (index, (key, message, icon)) in items.into_iter().enumerate() {
            let selected = self.selected == key;
            let foreground = if selected {
                theme::brand()
            } else {
                gpui_kit::rgb(theme.text_secondary()).into()
            };
            sidebar = sidebar.child(
                Tab::new((gpui_kit::ElementId::from("navigation"), key))
                    .in_list(&self.navigation, key)
                    .accessibility_label(preferences.text(message))
                    .set_position(index + 1, item_count)
                    .w_full()
                    .h_10()
                    .flex_shrink_0()
                    .justify_start()
                    .gap_2()
                    .px_2()
                    .rounded_md()
                    .border_2()
                    .border_color(gpui_kit::transparent_black())
                    .cursor_default()
                    .text_sm()
                    .text_color(foreground)
                    .font_weight(if selected {
                        gpui_kit::FontWeight::SEMIBOLD
                    } else {
                        gpui_kit::FontWeight::NORMAL
                    })
                    .bg(gpui_kit::rgb(if selected {
                        theme.surface_component()
                    } else {
                        theme.page()
                    }))
                    .hover(|style| style.bg(gpui_kit::rgb(theme.surface_component())))
                    .active(|style| style.bg(gpui_kit::rgb(theme.border_default())))
                    .focus_visible(|style| style.border_color(theme::brand()))
                    .child(
                        icon.size(gpui_kit::rems(1.25))
                            .color_value(foreground.into()),
                    )
                    .child(
                        div()
                            .min_w_0()
                            .text_ellipsis()
                            .child(preferences.text(message)),
                    ),
            );
        }
        let (message, content) = match self.selected.as_ref() {
            "devices" => (M::Devices, devices(&self.preferences, &self.reception, cx)),
            "history" => (M::History, self.history.clone().into_any_element()),
            "settings" => (M::Settings, settings(&self.preferences, &self.settings, cx)),
            _ => (M::Connection, self.receive.clone().into_any_element()),
        };
        div()
            .flex()
            .items_stretch()
            .line_height(gpui_kit::relative(1.))
            .flex_1()
            .min_w_0()
            .min_h_0()
            .child(sidebar)
            .child(
                TabPanel::new(
                    (
                        gpui_kit::ElementId::from("navigation-panel"),
                        self.selected.clone(),
                    ),
                    &self.navigation,
                    self.selected.clone(),
                    self.preferences.read(cx).text(message),
                )
                .tab_stop(self.selected != "settings")
                .flex_1()
                .h_full()
                .border_2()
                .border_color(gpui_kit::transparent_black())
                .focus_visible(|style| style.border_color(theme::brand()))
                .content(content),
            )
            .into_any_element()
    }
}

impl Render for Shell {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // A single rem anchor scales the shell and Yue controls together.
        window.set_rem_size(gpui_kit::px(16.));
        let navigation = self.render_navigation(window, cx);
        let state = self.preferences.read(cx);
        let theme = current_theme(cx);
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
            .child(navigation)
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
                yue_component::YueDialog::new("review-dialog", theme, &self.review_dialog)
                    .width(gpui_kit::rems(32.))
                    .title(state.text(M::ReviewTitle))
                    .description(state.text(M::ReviewHelp))
                    .confirm_label(state.text(M::ReviewedResume))
                    .cancel_label(state.text(M::Cancel))
                    .close_label(state.text(M::CloseDialog))
                    .confirm_disabled(
                        self.reviewed.is_none()
                            || self.reviewed != self.reception.read(cx).review_scope()
                            || self.reception.read(cx).is_busy(),
                    )
                    .on_confirm({
                        let reception = self.reception.clone();
                        let authorization = self.reviewed.clone();
                        move |_, _, cx| {
                            let accepted = authorization.clone().is_some_and(|scope| {
                                reception.update(cx, |s, cx| s.resume_reviewed(scope, cx))
                            });
                            if accepted {
                                yue_component::DialogDecision::Close
                            } else {
                                yue_component::DialogDecision::KeepOpen
                            }
                        }
                    })
                    .on_close_request({
                        let owner = cx.entity().downgrade();
                        move |_, _, cx| {
                            let _ = owner.update(cx, |this, cx| {
                                this.reviewed = None;
                                cx.notify();
                            });
                            true
                        }
                    })
                    .child({
                        let original = self
                            .reception
                            .read(cx)
                            .review_entry()
                            .and_then(|entry| entry.operation.as_ref()?.text().map(str::to_owned))
                            .unwrap_or_else(|| state.text(M::BodyUnavailable));
                        div()
                            .p_3()
                            .rounded_md()
                            .bg(gpui_kit::rgb(theme.surface_component()))
                            .child(original)
                    })
                    .child(
                        YueCheckbox::new(
                            "review-confirm",
                            theme,
                            if self.reviewed.is_some()
                                && self.reviewed == self.reception.read(cx).review_scope()
                            {
                                CheckboxState::Checked
                            } else {
                                CheckboxState::Unchecked
                            },
                        )
                        .label(state.text(M::ReviewCheckbox))
                        .disabled(
                            self.reception.read(cx).review_scope().is_none()
                                || self.reception.read(cx).is_busy(),
                        )
                        .on_change({
                            let owner = cx.entity().downgrade();
                            let displayed = self.reception.read(cx).review_scope();
                            move |state, _, _, cx| {
                                let _ = owner.update(cx, |this, cx| {
                                    this.reviewed = if state == CheckboxState::Checked
                                        && displayed == this.reception.read(cx).review_scope()
                                    {
                                        displayed.clone()
                                    } else {
                                        None
                                    };
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

/// Shared by the shell and the history grid; reacts to application zoom.
pub(crate) fn sidebar_rems(window: &Window) -> f32 {
    if f32::from(window.viewport_size().width) < 800. {
        9.5
    } else {
        11.
    }
}
