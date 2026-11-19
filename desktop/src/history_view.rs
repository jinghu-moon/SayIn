//! History owns its query controls, disclosures, privacy dialog and icon tasks.
//! The receiver remains the only owner of persisted history and retention.
use crate::{
    application_icon::{self, ApplicationIcon},
    localization::Message as M,
    preferences::PreferencesState,
    reception::ReceptionState,
    theme,
};
use gpui_kit::{
    base::{
        TestSupportExt as _,
        input::{InputEvent, InputState},
    },
    prelude::FluentBuilder as _,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    ops::Range,
};
use voicelink_receiver::{
    history::{HistoryEntry, HistoryPolicy},
    protocol::{Operation, State},
    windows_image_delivery::{ImageDeliveryMode, is_executable_path},
};
use yue_component::{
    ButtonSize, ButtonVariant, DialogFooter, Icon, SelectEvent, SelectMode, SelectOption,
    SelectValue, YueButton, YueDialog, YueDialogState, YueDropdownMenu, YueEmpty, YueInput,
    YueMenuItem, YueSelect, YueSelectState, YueSwitch, YueTheme, current_theme,
    gpui_kit::{
        self, AppContext as _, Context, Entity, InteractiveElement as _, IntoElement,
        ParentElement as _, Render, SharedString, StatefulInteractiveElement as _, Styled as _,
        StyledImage as _, Subscription, Task, Window, div,
    },
    outline,
};

gpui_kit::actions!(history_view, [FocusHistorySearch]);

pub struct HistoryView {
    recent_only: bool,
    preferences: Entity<PreferencesState>,
    reception: Entity<ReceptionState>,
    search: Entity<InputState>,
    retention: Entity<YueSelectState>,
    privacy: Entity<YueDialogState>,
    details: BTreeSet<String>,
    expanded: BTreeSet<String>,
    icons: BTreeMap<String, ApplicationIcon>,
    icon_task: Option<Task<()>>,
    icons_dirty: bool,
    image_orders: BTreeSet<String>,
    images: BTreeMap<String, Option<yue_component::ImagePreviewImage>>,
    image_task: Option<Task<()>>,
    image_action: Option<Task<()>>,
    image_feedback: Option<(i64, M)>,
    clipboard_owner: Option<std::sync::Arc<voicelink_receiver::windows_clipboard::ClipboardOwner>>,
    preview: Option<yue_component::ImagePreviewImage>,
    preview_dialog: Entity<YueDialogState>,
    preview_task: Option<Task<()>>,
    preview_order: Option<i64>,
    undo_focus: gpui_kit::FocusHandle,
    last_undo: Option<i64>,
    _subscriptions: Vec<Subscription>,
}
impl HistoryView {
    pub fn new(
        preferences: Entity<PreferencesState>,
        reception: Entity<ReceptionState>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        Self::create(preferences, reception, false, window, cx)
    }
    pub(crate) fn recent(
        preferences: Entity<PreferencesState>,
        reception: Entity<ReceptionState>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        Self::create(preferences, reception, true, window, cx)
    }
    fn create(
        preferences: Entity<PreferencesState>,
        reception: Entity<ReceptionState>,
        recent_only: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        cx.bind_keys([gpui_kit::KeyBinding::new(
            "ctrl-k",
            FocusHistorySearch,
            Some("SayInHistory"),
        )]);
        let search = cx.new(|cx| {
            InputState::new(window, cx)
                .default_value(reception.read(cx).history_query().search.clone())
        });
        let retention = cx.new(|cx| YueSelectState::new(SelectMode::Single, window, cx));
        let searching = cx.subscribe(&search, |this, state, event, cx| {
            if matches!(event, InputEvent::Change) {
                let value = state.read(cx).value().to_string();
                this.reception
                    .update(cx, |state, cx| state.search_history(value, cx));
            }
        });
        let retaining = cx.subscribe(&retention, |this, _, event, cx| {
            if let SelectEvent::Change(change) = event {
                let days = match change.after().keys().first().map(|s| s.as_ref()) {
                    Some("7") => Some(7),
                    Some("30") => Some(30),
                    _ => None,
                };
                let policy = this.reception.read(cx).history_policy();
                if let Some(policy) = policy {
                    let next =
                        HistoryPolicy::new(policy.is_enabled(), days).expect("retention option");
                    this.reception
                        .update(cx, |s, cx| s.set_history_policy(next, cx));
                }
            }
        });
        let receiving = cx.observe_in(&reception, window, |this, _, window, cx| {
            let undo = this.reception.read(cx).history_undo();
            if !this.recent_only && undo != this.last_undo {
                if undo.is_some() {
                    this.undo_focus.focus(window, cx);
                } else if this.undo_focus.is_focused(window) {
                    this.search.update(cx, |s, cx| s.focus(window, cx));
                }
                this.last_undo = undo;
            }
            this.synchronize_policy(window, cx);
            this.refresh_icons(cx);
            this.refresh_images(cx);
            cx.notify();
        });
        let localizing = cx.observe_in(&preferences, window, |this, _, window, cx| {
            this.synchronize_policy(window, cx);
            cx.notify();
        });
        let preview_dialog = cx.new(YueDialogState::new);
        let preview_closing =
            cx.subscribe_in(&preview_dialog, window, |this, dialog, _, window, cx| {
                if !dialog.read(cx).is_open() {
                    this.preview_order = None;
                    if let Some(image) = this.preview.take() {
                        // Arc release only frees CPU pixels. Direct img resources
                        // also require explicit removal from GPUI's sprite atlas.
                        cx.drop_image(image.image().clone(), Some(window));
                    }
                }
            });
        let releasing = cx.on_release(|this, cx| {
            let images = this
                .images
                .values()
                .flatten()
                .chain(this.preview.iter())
                .map(|image| image.image().clone())
                .collect::<Vec<_>>();
            // A view can be released while its window is being updated and is
            // temporarily absent from App.windows. Defer until it is restored.
            cx.defer(move |cx| {
                for image in images {
                    cx.drop_image(image, None);
                }
            });
        });
        let mut this = Self {
            recent_only,
            preferences,
            reception,
            search,
            retention,
            privacy: cx.new(YueDialogState::new),
            details: BTreeSet::new(),
            expanded: BTreeSet::new(),
            icons: BTreeMap::new(),
            icon_task: None,
            icons_dirty: false,
            image_orders: BTreeSet::new(),
            images: BTreeMap::new(),
            image_task: None,
            image_action: None,
            image_feedback: None,
            clipboard_owner: None,
            preview: None,
            preview_dialog,
            preview_task: None,
            preview_order: None,
            undo_focus: cx.focus_handle().tab_stop(true),
            last_undo: None,
            _subscriptions: vec![
                searching,
                retaining,
                receiving,
                localizing,
                preview_closing,
                releasing,
            ],
        };
        this.synchronize_policy(window, cx);
        this.refresh_icons(cx);
        this.refresh_images(cx);
        this
    }
    fn synchronize_policy(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let settings = self.preferences.read(cx);
        let options = [
            ("7", M::HistoryKeep7),
            ("30", M::HistoryKeep30),
            ("forever", M::HistoryKeepForever),
        ]
        .map(|(key, label)| SelectOption::new(key, settings.text(label)));
        let key = match self
            .reception
            .read(cx)
            .history_policy()
            .and_then(HistoryPolicy::days)
        {
            Some(7) => "7",
            Some(30) => "30",
            _ => "forever",
        };
        let disabled = self.reception.read(cx).history_busy()
            || self.reception.read(cx).history_policy().is_none();
        self.retention.update(cx, |state, cx| {
            state.set_disabled(disabled, window, cx);
            state.set_options(options, cx).expect("retention options");
            let value = SelectValue::Single(Some(key.into()));
            if state.value() != &value {
                state.set_value(value, window, cx).expect("retention value");
            }
        });
    }
    fn refresh_icons(&mut self, cx: &mut Context<Self>) {
        if self.icon_task.is_some() {
            self.icons_dirty = true;
            return;
        }
        let service = self.reception.read(cx);
        let page = if self.recent_only {
            service.latest()
        } else {
            service.history()
        };
        let paths = page
            .entries
            .iter()
            .filter_map(|entry| entry.target.as_ref().map(|t| t.executable.clone()))
            .collect::<BTreeSet<_>>();
        self.icons.retain(|path, _| paths.contains(path));
        if paths.is_empty() {
            return;
        }
        let previous = self.icons.clone();
        let loading = cx.background_spawn(async move {
            paths
                .into_iter()
                .map(|path| {
                    let icon = application_icon::load(&path, previous.get(&path).cloned());
                    (path, icon)
                })
                .collect()
        });
        self.icon_task = Some(cx.spawn(async move |owner, cx| {
            let icons = loading.await;
            let _ = owner.update(cx, |this, cx| {
                this.icon_task = None;
                this.icons = icons;
                if this.icons_dirty {
                    this.icons_dirty = false;
                    this.refresh_icons(cx);
                }
                cx.notify();
            });
        }));
    }
    fn clear_filters(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.search.update(cx, |state, cx| {
            state.set_value("", window, cx);
            state.focus(window, cx);
        });
        self.reception.update(cx, |state, cx| {
            state.search_history(String::new(), cx);
            state.filter_history(None, cx);
        });
    }
    fn refresh_images(&mut self, cx: &mut Context<Self>) {
        let service = self.reception.read(cx);
        let page = if self.recent_only {
            service.latest()
        } else {
            service.history()
        };
        self.image_orders = page
            .entries
            .iter()
            .filter(|entry| matches!(entry.operation, Some(Operation::Image { .. })))
            .map(|entry| entry.receipt.order.clone())
            .take(30)
            .collect();
        let receiver = service.image_receiver();
        let mut removed = Vec::new();
        self.images.retain(|order, image| {
            let keep = self.image_orders.contains(order);
            if !keep && let Some(image) = image {
                removed.push(image.image().clone());
            }
            keep
        });
        if !removed.is_empty() {
            cx.defer(move |cx| {
                for image in removed {
                    cx.drop_image(image, None);
                }
            });
        }
        if self.image_task.is_some() {
            return;
        }
        let Some(receiver) = receiver else {
            return;
        };
        let missing = self
            .image_orders
            .iter()
            .filter(|order| !self.images.contains_key(*order))
            .cloned()
            .collect::<Vec<_>>();
        if missing.is_empty() {
            return;
        }
        let loading = cx.background_spawn(async move {
            let mut results = Vec::new();
            for order in missing {
                let image = match order.parse() {
                    Ok(value) => crate::image_history::thumbnail(&receiver, value).await.ok(),
                    Err(_) => None,
                };
                results.push((order, image));
            }
            results
        });
        self.image_task = Some(cx.spawn(async move |owner, cx| {
            let results = loading.await;
            let _ = owner.update(cx, |this, cx| {
                this.image_task = None;
                for (order, image) in results {
                    if this.image_orders.contains(&order) {
                        this.images.insert(order, image);
                    }
                }
                this.refresh_images(cx);
                cx.notify();
            });
        }));
    }
    fn image_command(
        &mut self,
        order: i64,
        path: Option<std::path::PathBuf>,
        cx: &mut Context<Self>,
    ) {
        if self.image_action.is_some() {
            return;
        }
        let Some(receiver) = self.reception.read(cx).image_receiver() else {
            return;
        };
        self.image_feedback = None;
        let clipboard = self.clipboard_owner.clone();
        let saving = path.is_some();
        let work = cx.background_spawn(async move {
            if let Some(path) = path {
                return (
                    crate::image_history::save(&receiver, order, &path).await,
                    clipboard,
                );
            }
            let clipboard = match clipboard {
                Some(owner) => owner,
                None => match voicelink_receiver::windows_clipboard::ClipboardOwner::new() {
                    Ok(owner) => std::sync::Arc::new(owner),
                    Err(error) => return (Err(error), None),
                },
            };
            (
                crate::image_history::copy(&receiver, order, &clipboard).await,
                Some(clipboard),
            )
        });
        self.image_action = Some(cx.spawn(async move |owner, cx| {
            let (result, clipboard) = work.await;
            let _ = owner.update(cx, |this, cx| {
                this.image_action = None;
                this.clipboard_owner = clipboard;
                this.image_feedback = Some((
                    order,
                    match result {
                        Ok(()) => {
                            if saving {
                                M::ImageSaved
                            } else {
                                M::ImageCopied
                            }
                        }
                        Err(voicelink_receiver::Error::Protocol(
                            "CLIPBOARD_CHANGED_NOT_COPIED",
                        )) => M::ImageClipboardChanged,
                        Err(_) => M::ImageActionFailed,
                    },
                ));
                cx.notify();
            });
        }));
        cx.notify();
    }
    fn view_image(&mut self, order: i64, window: &mut Window, cx: &mut Context<Self>) {
        if self.preview_task.is_some() {
            return;
        }
        let Some(receiver) = self.reception.read(cx).image_receiver() else {
            return;
        };
        self.preview_order = Some(order);
        if let Some(image) = self.preview.take() {
            cx.drop_image(image.image().clone(), Some(window));
        }
        let _ = self
            .preview_dialog
            .update(cx, |state, cx| state.open(window, cx));
        let loading = cx.background_spawn(async move {
            crate::image_history::preview(&receiver, order, 8192).await
        });
        self.preview_task = Some(cx.spawn(async move |owner, cx| {
            let result = loading.await;
            let _ = owner.update(cx, |this, cx| {
                this.preview_task = None;
                if this.preview_order == Some(order) && this.preview_dialog.read(cx).is_open() {
                    this.preview = result.ok();
                }
                cx.notify();
            });
        }));
    }

    fn save_image(&mut self, order: i64, extension: &str, cx: &mut Context<Self>) {
        if self.image_action.is_some() {
            return;
        }
        let directory = directories::UserDirs::new()
            .and_then(|dirs| dirs.picture_dir().map(std::path::Path::to_path_buf))
            .unwrap_or_else(std::env::temp_dir);
        let selecting =
            cx.prompt_for_new_path(&directory, Some(&format!("SayIn-{order}.{extension}")));
        self.image_action = Some(cx.spawn(async move |owner, cx| {
            let result = selecting.await;
            let _ = owner.update(cx, |this, cx| {
                this.image_action = None;
                match result {
                    Ok(Ok(Some(path))) => this.image_command(order, Some(path), cx),
                    Ok(Ok(None)) => {}
                    _ => this.image_feedback = Some((order, M::ImageActionFailed)),
                }
                cx.notify();
            });
        }));
    }
    fn toggle_details(&mut self, order: &str, cx: &mut Context<Self>) {
        if !self.details.remove(order) {
            self.details.insert(order.into());
        }
        cx.notify();
    }
    fn toggle_text(&mut self, order: &str, cx: &mut Context<Self>) {
        if !self.expanded.remove(order) {
            self.expanded.insert(order.into());
        }
        cx.notify();
    }

    fn card(
        &self,
        entry: &HistoryEntry,
        compact_route: bool,
        cx: &Context<Self>,
    ) -> gpui_kit::AnyElement {
        let settings = self.preferences.read(cx);
        let theme = current_theme(cx);
        let order = &entry.receipt.order;
        let body = match &entry.operation {
            Some(Operation::Text { text, .. } | Operation::AutoText { text, .. }) => text.clone(),
            Some(Operation::Image { image }) => format!(
                "{} · {} × {} · {} KiB",
                settings.text(M::ImageLabel),
                image.metadata.width,
                image.metadata.height,
                image.metadata.bytes.div_ceil(1024)
            ),
            Some(_) => settings.text(M::KeyOperation),
            None => settings.text(M::BodyUnavailable),
        };
        let expanded = self.expanded.contains(order);
        let detailed = self.details.contains(order);
        let query = if self.recent_only {
            ""
        } else {
            self.reception.read(cx).history_query().search.trim()
        };
        let long = body.chars().count() > 160;
        let highlights = highlight_ranges(&body, query).into_iter().map(|range| {
            (
                range,
                gpui_kit::HighlightStyle {
                    background_color: Some(theme::brand().opacity(0.16)),
                    ..Default::default()
                },
            )
        });
        let text = gpui_kit::StyledText::new(body.clone()).with_highlights(highlights);
        let time = format_time(entry.received_at, "%H:%M")
            .unwrap_or_else(|| settings.text(M::TimeUnavailable));
        let date = format_time(entry.received_at, "%Y-%m-%d").unwrap_or_default();
        let mut times = div()
            .min_w_0()
            .flex()
            .flex_wrap()
            .items_center()
            .gap_2()
            .text_xs()
            .text_color(gpui_kit::rgb(theme.text_secondary()))
            .child(time);
        if !date.is_empty() {
            times = times.child(
                div()
                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                    .child(date),
            );
        }
        let detail_order = order.clone();
        let delete_order = order.parse::<i64>().ok();
        let deleting = self.reception.clone();
        let menu = YueDropdownMenu::new(
            SharedString::from(format!("history-menu-{order}")),
            theme,
            YueButton::icon(
                SharedString::from(format!("history-more-{order}")),
                theme,
                outline::DotsVertical,
                settings.text(M::HistoryMore),
            )
            .size(ButtonSize::Small)
            .variant(ButtonVariant::Ghost)
            .tooltip(settings.text(M::HistoryMore)),
        )
        .items({
            let mut items = vec![
                YueMenuItem::new(settings.text(if detailed {
                    M::HistoryHideDetails
                } else {
                    M::HistoryDetails
                }))
                .icon(outline::FileDescription)
                .on_click(
                    cx.listener(move |this, _, _, cx| this.toggle_details(&detail_order, cx)),
                ),
            ];
            if matches!(entry.operation, Some(Operation::Image { .. })) {
                let viewing = order.parse::<i64>().ok();
                items.push(
                    YueMenuItem::new(settings.text(M::ImageView))
                        .icon(outline::Photo)
                        .disabled(self.preview_task.is_some() || viewing.is_none())
                        .on_click(cx.listener(move |this, _, window, cx| {
                            if let Some(order) = viewing {
                                this.view_image(order, window, cx);
                            }
                        })),
                );
            }
            if !self.recent_only {
                if let Some(target) = entry
                    .target
                    .as_ref()
                    .filter(|target| is_executable_path(std::path::Path::new(&target.executable)))
                {
                    items.push(YueMenuItem::separator());
                    for (mode, label) in [
                        (ImageDeliveryMode::PasteImage, M::ImageModePasteForApp),
                        (ImageDeliveryMode::CopyPath, M::ImageModeCopyPathForApp),
                    ] {
                        let owner = self.preferences.clone();
                        let executable = target.executable.clone();
                        items.push(
                            YueMenuItem::new(settings.text(label))
                                .disabled(!settings.is_editable())
                                .on_click(move |_, _, cx| {
                                    owner.update(cx, |settings, cx| {
                                        settings.set_image_application(&executable, Some(mode), cx);
                                    })
                                }),
                        );
                    }
                }
                items.extend([
                    YueMenuItem::separator(),
                    YueMenuItem::new(settings.text(M::HistoryDelete))
                        .icon(outline::Trash)
                        .danger(true)
                        .disabled(self.reception.read(cx).history_busy() || delete_order.is_none())
                        .on_click(move |_, _, cx| {
                            if let Some(order) = delete_order {
                                deleting.update(cx, |state, cx| state.delete_history(order, cx));
                            }
                        }),
                ]);
            }
            items
        });
        let mut content = div()
            .id(SharedString::from(format!("history-card-{order}")))
            .test_support()
            .role(gpui_kit::Role::Group)
            .aria_label(format!("{} · {}", settings.text(M::History), order))
            .w_full()
            .min_w_0()
            .when(!self.recent_only, |d| d.h_full())
            .flex()
            .flex_col()
            .gap_3()
            .p_4()
            .rounded_lg()
            .border_1()
            .border_color(gpui_kit::rgb(theme.border_default()))
            .bg(gpui_kit::rgb(theme.surface()))
            .child(
                div()
                    .min_w_0()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .justify_between()
                    .gap_2()
                    .child(times)
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .child(status(entry.receipt.st, settings, theme))
                            .child(menu),
                    ),
            )
            .child(
                div()
                    .min_w_0()
                    .whitespace_normal()
                    .line_height(gpui_kit::relative(1.75))
                    .when(long && !expanded && query.is_empty(), |d| d.line_clamp(3))
                    .child(text),
            );
        if let Some(Operation::Image { .. }) = &entry.operation {
            content = content.child(match self.images.get(order) {
                Some(Some(image)) => div()
                    .id(SharedString::from(format!("history-image-preview-{order}")))
                    .test_support()
                    .role(gpui_kit::Role::Image)
                    .aria_label(format!("{} · {order}", settings.text(M::ImageView)))
                    .relative()
                    .flex_none()
                    .w_full()
                    .h_40()
                    .overflow_hidden()
                    .child(
                        gpui_kit::img(image.image().clone())
                            // Keep intrinsic image aspect ratio out of flex/grid
                            // measurement. ScaleDown contains without enlarging
                            // small sources just to fill this viewport.
                            .absolute()
                            .inset_0()
                            .size_full()
                            .min_w_0()
                            .min_h_0()
                            .object_fit(gpui_kit::ObjectFit::ScaleDown)
                            .id(SharedString::from(format!("history-image-pixels-{order}")))
                            .test_support(),
                    )
                    .into_any_element(),
                Some(None) => {
                    support(settings.text(M::ImagePreviewFailed), theme).into_any_element()
                }
                None => support(settings.text(M::HistoryLoading), theme).into_any_element(),
            });
            if entry.receipt.st == State::Injected {
                content = content.child(support(settings.text(M::ImagePasteSubmitted), theme));
            }
        }
        if long && query.is_empty() {
            let order = order.clone();
            content = content.child(
                div().flex().child(
                    YueButton::new(
                        SharedString::from(format!("history-expand-{order}")),
                        settings.text(if expanded {
                            M::HistoryCollapse
                        } else {
                            M::HistoryExpand
                        }),
                    )
                    .variant(ButtonVariant::Ghost)
                    .size(ButtonSize::Small)
                    .on_click(cx.listener(move |this, _, _, cx| this.toggle_text(&order, cx))),
                ),
            );
        }
        if matches!(
            entry.receipt.st,
            State::Failed | State::Uncertain | State::Expired | State::Held
        ) {
            let (fg, bg, border) = feedback(entry.receipt.st, theme);
            content = content.child(
                div()
                    .flex()
                    .items_start()
                    .gap_2()
                    .rounded_md()
                    .border_1()
                    .border_color(border)
                    .bg(bg)
                    .p_3()
                    .text_xs()
                    .text_color(fg)
                    .child(
                        Icon::new(outline::AlertCircle)
                            .size(gpui_kit::rems(1.))
                            .color_value(fg.into()),
                    )
                    .child(
                        div()
                            .min_w_0()
                            .flex_1()
                            .whitespace_normal()
                            .line_height(gpui_kit::relative(1.5))
                            .child(settings.receipt_help(&entry.receipt)),
                    ),
            );
        }
        let mut target = div()
            .id(SharedString::from(format!("history-destination-{order}")))
            .test_support()
            .role(gpui_kit::Role::Group)
            .aria_label(format!("Destination · {order}"))
            .min_w_0()
            .flex_1()
            .flex()
            .items_center()
            .gap_1p5();
        if let Some(image) = entry
            .target
            .as_ref()
            .and_then(|t| self.icons.get(&t.executable))
            .and_then(|i| i.image.as_ref())
        {
            target = target.child(
                gpui_kit::img(image.clone())
                    .size(gpui_kit::rems(0.9375))
                    .flex_shrink_0()
                    .object_fit(gpui_kit::ObjectFit::Contain),
            );
        } else {
            target = target.child(
                Icon::new(outline::AppWindow)
                    .size(gpui_kit::rems(0.9375))
                    .color_value(gpui_kit::rgb(theme.text_secondary())),
            );
        }
        target = target.child(
            div()
                .min_w_0()
                .truncate()
                .text_color(gpui_kit::rgb(theme.text_primary()))
                .child(
                    entry
                        .target
                        .as_ref()
                        .map(|t| {
                            self.icons
                                .get(&t.executable)
                                .and_then(|icon| icon.display_name.clone())
                                .unwrap_or_else(|| application_icon::process_label(&t.executable))
                        })
                        .unwrap_or_else(|| settings.text(M::TargetUnavailable)),
                ),
        );
        let source = div()
            .id(SharedString::from(format!("history-source-{order}")))
            .test_support()
            .role(gpui_kit::Role::Group)
            .aria_label(format!("Source · {order}"))
            .min_w_0()
            .max_w(gpui_kit::rems(9.))
            .flex()
            .items_center()
            .gap_1p5()
            .child(
                Icon::new(outline::DeviceMobile)
                    .size(gpui_kit::rems(0.9375))
                    .color_value(gpui_kit::rgb(theme.text_secondary())),
            )
            .child(
                div()
                    .min_w_0()
                    .truncate()
                    .child(if entry.source_name.is_empty() {
                        settings.text(M::PhoneDevice)
                    } else {
                        entry.source_name.clone()
                    }),
            );
        let route = div()
            .flex_1()
            .min_w_0()
            .flex()
            .when(compact_route, |route| route.flex_col().gap_1())
            .when(!compact_route, |route| route.items_center().gap_2())
            .text_xs()
            .text_color(gpui_kit::rgb(theme.text_secondary()))
            .child(source)
            .when(!compact_route, |route| {
                route.child(
                    Icon::new(outline::ChevronRight)
                        .size(gpui_kit::rems(0.875))
                        .color_value(gpui_kit::rgb(theme.text_secondary())),
                )
            })
            .child(target);
        let mut footer = div()
            .mt_auto()
            .pt_2()
            .min_w_0()
            .flex()
            .when(compact_route, |footer| footer.items_end())
            .when(!compact_route, |footer| footer.items_center())
            .gap_3()
            .child(route);
        if entry.operation.as_ref().and_then(Operation::text).is_some() {
            footer = footer.child(
                YueButton::icon_text(
                    SharedString::from(format!("history-copy-{order}")),
                    theme,
                    outline::Copy,
                    settings.text(M::CopyOriginal),
                )
                .size(ButtonSize::Small)
                .variant(ButtonVariant::Ghost)
                .on_click(move |_, _, cx| {
                    cx.write_to_clipboard(gpui_kit::ClipboardItem::new_string(body.clone()))
                }),
            );
        }
        if let Some(Operation::Image { image }) = &entry.operation {
            let copy_order = order.parse::<i64>().ok();
            let save_order = copy_order;
            let extension = if image.metadata.mime == voicelink_receiver::images::ImageMime::Png {
                "png"
            } else {
                "jpg"
            };
            footer = footer.flex_col().items_stretch().child(
                div()
                    .flex()
                    .items_center()
                    .gap_2()
                    .child(
                        YueButton::icon_text(
                            SharedString::from(format!("history-image-copy-{order}")),
                            theme,
                            outline::Copy,
                            settings.text(M::ImageCopy),
                        )
                        .size(ButtonSize::Small)
                        .variant(ButtonVariant::Ghost)
                        .disabled(self.image_action.is_some() || copy_order.is_none())
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if let Some(order) = copy_order {
                                this.image_command(order, None, cx);
                            }
                        })),
                    )
                    .child(
                        YueButton::icon(
                            SharedString::from(format!("history-image-save-{order}")),
                            theme,
                            outline::Download,
                            settings.text(M::ImageSave),
                        )
                        .size(ButtonSize::Small)
                        .variant(ButtonVariant::Ghost)
                        .tooltip(settings.text(M::ImageSave))
                        .disabled(self.image_action.is_some() || save_order.is_none())
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if let Some(order) = save_order {
                                this.save_image(order, extension, cx);
                            }
                        })),
                    ),
            );
            if let Some((_, message)) = self
                .image_feedback
                .filter(|(value, _)| Some(*value) == copy_order)
            {
                content = content.child(support(settings.text(message), theme));
            }
        }
        content = content.child(footer);
        if detailed {
            let target = entry.target.as_ref();
            let fields = [
                (
                    M::HistorySource,
                    if entry.source_name.is_empty() {
                        settings.text(M::PhoneDevice)
                    } else {
                        entry.source_name.clone()
                    },
                ),
                (
                    M::HistoryTarget,
                    target
                        .map(|t| t.executable.clone())
                        .unwrap_or_else(|| settings.text(M::TargetUnavailable)),
                ),
                (
                    M::HistoryWindow,
                    target
                        .map(|t| t.window_title.clone())
                        .unwrap_or_else(|| settings.text(M::TargetUnavailable)),
                ),
                (
                    M::HistoryReceived,
                    format_time(entry.received_at, "%Y-%m-%d %H:%M:%S")
                        .unwrap_or_else(|| settings.text(M::TimeUnavailable)),
                ),
                (
                    M::HistoryExecuted,
                    format_time(entry.executed_at, "%Y-%m-%d %H:%M:%S")
                        .unwrap_or_else(|| settings.text(M::HistoryNoAttempt)),
                ),
                (M::HistoryDeviceId, entry.device.clone()),
                (M::HistoryOrder, order.clone()),
            ];
            let mut details = div()
                .min_w_0()
                .flex()
                .flex_col()
                .gap_3()
                .border_t_1()
                .border_color(gpui_kit::rgb(theme.border_default()))
                .pt_4()
                .text_xs();
            for (label, value) in fields {
                let label = settings.text(label);
                details = details.child(
                    div()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .gap_1()
                        .child(
                            div()
                                .id(SharedString::from(format!(
                                    "history-field-{order}-{}",
                                    label
                                )))
                                .test_support()
                                .role(gpui_kit::Role::Label)
                                .aria_label(label.clone())
                                .text_color(gpui_kit::rgb(theme.text_secondary()))
                                .child(label),
                        )
                        .child(
                            div()
                                .min_w_0()
                                .whitespace_normal()
                                .line_height(gpui_kit::relative(1.5))
                                .child(value),
                        ),
                );
            }
            details = details.child(
                div()
                    .text_color(gpui_kit::rgb(theme.text_secondary()))
                    .whitespace_normal()
                    .line_height(gpui_kit::relative(1.5))
                    .child(settings.receipt_help(&entry.receipt)),
            );
            content = content.child(details);
        }
        content.into_any_element()
    }
    fn privacy_dialog(&self, cx: &Context<Self>) -> gpui_kit::AnyElement {
        let settings = self.preferences.read(cx);
        let service = self.reception.read(cx);
        let theme = current_theme(cx);
        let policy = service.history_policy();
        let disabled = policy.is_none() || service.history_busy();
        let receiver = self.reception.clone();
        let switch = YueSwitch::new(
            "history-save",
            theme,
            policy.is_some_and(HistoryPolicy::is_enabled),
            settings.text(M::HistorySave),
        )
        .disabled(disabled)
        .on_change(move |enabled, _, _, cx| {
            let policy = receiver.read(cx).history_policy();
            if let Some(policy) = policy {
                receiver.update(cx, |state, cx| {
                    state.set_history_policy(
                        HistoryPolicy::new(enabled, policy.days()).expect("current retention"),
                        cx,
                    )
                });
            }
        });
        let mut body = div()
            .min_w_0()
            .flex()
            .flex_col()
            .gap_6()
            .child(
                div()
                    .min_w_0()
                    .flex()
                    .items_start()
                    .gap_4()
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap_2()
                            .child(settings.text(M::HistorySave))
                            .child(support(settings.text(M::HistorySaveHelp), theme)),
                    )
                    .child(switch),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap_3()
                    .child(settings.text(M::HistoryRetention))
                    .child(
                        YueSelect::new("history-retention", &self.retention)
                            .accessibility_label(settings.text(M::HistoryRetention))
                            .width(gpui_kit::rems(15.)),
                    )
                    .child(support(settings.text(M::HistoryRetentionHelp), theme)),
            )
            .child(
                div()
                    .flex()
                    .flex_col()
                    .gap_2()
                    .child(settings.text(M::HistoryLocal))
                    .child(support(settings.text(M::HistoryLocalHelp), theme)),
            );
        if service.history_command_failed() {
            body = body.child(support(settings.text(M::HistoryCommandFailed), theme));
        }
        YueDialog::new("history-privacy-dialog", theme, &self.privacy)
            .title(settings.text(M::HistoryPrivacyTitle))
            .close_label(settings.text(M::HistoryClose))
            .footer(DialogFooter::None)
            .width(gpui_kit::rems(32.))
            .child(body)
            .into_any_element()
    }
}
impl Render for HistoryView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let settings = self.preferences.read(cx);
        let service = self.reception.read(cx);
        let theme = current_theme(cx);
        if self.recent_only {
            let width = f32::from(window.viewport_size().width) / f32::from(window.rem_size())
                - crate::shell::sidebar_rems(window)
                - 3.;
            return if let Some(entry) = service.latest().entries.first() {
                self.card(entry, width < 26., cx)
            } else {
                div()
                    .p_6()
                    .rounded_lg()
                    .border_1()
                    .border_color(gpui_kit::rgb(theme.border_default()))
                    .child(
                        yue_component::YueEmpty::new("latest-empty")
                            .title(settings.text(M::LatestEmpty))
                            .description(settings.text(M::ReceiveEmptyHelp)),
                    )
                    .into_any_element()
            };
        }
        let query = service.history_query();
        let content_width = f32::from(window.viewport_size().width) / f32::from(window.rem_size())
            - crate::shell::sidebar_rems(window)
            - 3.;
        let columns = columns_for_width(content_width);
        let compact_route =
            (content_width - f32::from(columns - 1) * 0.75) / f32::from(columns) < 26.;
        let privacy = self.privacy.clone();
        let receiver = self.reception.clone();
        let mut content = div()
            .w_full()
            .min_w_0()
            .flex()
            .flex_col()
            .gap_5()
            .p_6()
            .child(
                div()
                    .min_w_0()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .justify_between()
                    .gap_3()
                    .child(
                        div()
                            .flex_1()
                            .min_w(gpui_kit::rems(12.))
                            .flex()
                            .flex_col()
                            .gap_2()
                            .child(
                                div()
                                    .text_xl()
                                    .font_weight(gpui_kit::FontWeight::SEMIBOLD)
                                    .child(settings.text(M::History)),
                            )
                            .child(support(settings.text(M::HistoryDescription), theme)),
                    )
                    .child(
                        YueButton::icon_text(
                            "history-privacy-open",
                            theme,
                            outline::ShieldLock,
                            settings.text(M::HistoryPrivacyEntry),
                        )
                        .variant(ButtonVariant::Outline)
                        .on_click(move |_, window, cx| {
                            let _ = privacy.update(cx, |s, cx| s.open(window, cx));
                        }),
                    ),
            )
            .child(
                YueInput::new("history-search", theme, &self.search)
                    .input_type(yue_component::InputType::Search)
                    .block()
                    .clearable(true)
                    .max_length(256)
                    .leading_icon(outline::Search)
                    .placeholder(settings.text(M::SearchHistory))
                    .accessibility_label(settings.text(M::SearchHistory)),
            );
        let mut filters = div().min_w_0().flex().flex_wrap().items_center().gap_2();
        for (state, label) in filter_options().into_iter().take(5) {
            let owner = receiver.clone();
            let selected = query.state == state;
            filters = filters.child(
                YueButton::new(
                    SharedString::from(format!(
                        "history-filter-{}",
                        state.map_or("all", State::key)
                    )),
                    settings.text(label),
                )
                .size(ButtonSize::Small)
                .when(selected, |button| {
                    button.accessibility_description(settings.text(M::HistorySelectedFilter))
                })
                .variant(if selected {
                    ButtonVariant::Outline
                } else {
                    ButtonVariant::Ghost
                })
                .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.filter_history(state, cx))),
            );
        }
        let more_items = filter_options().into_iter().skip(5).map(|(state, label)| {
            let owner = receiver.clone();
            YueMenuItem::new(settings.text(label))
                .radio(query.state == state)
                .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.filter_history(state, cx)))
        });
        let more_label = filter_options()
            .into_iter()
            .skip(5)
            .find(|(state, _)| *state == query.state)
            .map(|(_, label)| settings.text(label))
            .unwrap_or_else(|| settings.text(M::HistoryMoreStates));
        filters = filters.child(
            YueDropdownMenu::new(
                "history-more-filters",
                theme,
                YueButton::new("history-more-filters-trigger", more_label)
                    .size(ButtonSize::Small)
                    .variant(ButtonVariant::Ghost),
            )
            .items(more_items),
        );
        content = content.child(filters);
        if service.history_failed() {
            let owner = receiver.clone();
            content = content
                .child(support(settings.text(M::HistoryReadFailed), theme))
                .child(
                    YueButton::new("history-retry", settings.text(M::Retry))
                        .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.refresh_history(cx))),
                );
        } else if service.history().entries.is_empty() {
            let (title, help) = if service.history_loading() {
                (M::HistoryLoading, M::HistoryEmptyDescription)
            } else if !query.search.trim().is_empty() {
                (M::HistorySearchEmpty, M::HistorySearchEmptyHelp)
            } else if query.state.is_some() {
                (M::HistoryFilterEmpty, M::HistoryFilterEmptyHelp)
            } else {
                (M::HistoryEmpty, M::HistoryEmptyDescription)
            };
            content = content.child(
                YueEmpty::new("history-empty")
                    .icon(outline::History)
                    .title(settings.text(title))
                    .description(settings.text(help)),
            );
            if !query.search.is_empty() || query.state.is_some() {
                content = content.child(div().flex().child(
                    YueButton::new("history-clear", settings.text(M::HistoryClear)).on_click(
                        cx.listener(|this, _, window, cx| this.clear_filters(window, cx)),
                    ),
                ));
            }
        } else {
            // Only the current bounded page is rendered. Groups and columns retain
            // receipt IDs through live updates, filtering and window resizing.
            let today = chrono::Local::now().date_naive();
            let mut groups: Vec<(Option<chrono::NaiveDate>, Vec<&HistoryEntry>)> = Vec::new();
            for entry in &service.history().entries {
                let date = entry
                    .received_at
                    .and_then(chrono::DateTime::from_timestamp_millis)
                    .map(|t| t.with_timezone(&chrono::Local).date_naive());
                if groups.last().is_none_or(|g| g.0 != date) {
                    groups.push((date, Vec::new()));
                }
                groups.last_mut().expect("group").1.push(entry);
            }
            for (date, entries) in groups {
                let label = match date {
                    Some(d) if d == today => settings.text(M::HistoryToday),
                    Some(d) if Some(d) == today.pred_opt() => settings.text(M::HistoryYesterday),
                    Some(d) => d.format("%Y-%m-%d").to_string(),
                    None => settings.text(M::HistoryUnknownDate),
                };
                content = content.child(
                    div()
                        .min_w_0()
                        .flex()
                        .flex_col()
                        .gap_3()
                        .child(
                            div()
                                .flex()
                                .items_center()
                                .gap_2()
                                .text_sm()
                                .text_color(gpui_kit::rgb(theme.text_secondary()))
                                .child(label)
                                .child(
                                    div().text_xs().child(settings.history_count(entries.len())),
                                ),
                        )
                        .child(
                            div()
                                .w_full()
                                .min_w_0()
                                .grid()
                                .grid_cols(columns)
                                .gap_3()
                                .children(
                                    entries
                                        .into_iter()
                                        .map(|entry| self.card(entry, compact_route, cx)),
                                ),
                        ),
                );
            }
        }
        let mut paging = div().flex().flex_wrap().items_center().gap_3();
        if query.before.is_some() {
            let owner = receiver.clone();
            paging = paging.child(
                YueButton::new("history-latest", settings.text(M::LatestPage))
                    .disabled(service.history_loading())
                    .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.first_history(cx))),
            );
        }
        if service.history().next.is_some() {
            let owner = receiver.clone();
            paging = paging.child(
                YueButton::new("history-older", settings.text(M::OlderPage))
                    .disabled(service.history_loading())
                    .on_click(move |_, _, cx| owner.update(cx, |s, cx| s.next_history(cx))),
            );
        }
        content = content.child(paging);
        let retention = service.history_policy().map(|policy| {
            if !policy.is_enabled() {
                M::HistoryPaused
            } else {
                match policy.days() {
                    Some(7) => M::HistoryKeep7,
                    Some(30) => M::HistoryKeep30,
                    _ => M::HistoryKeepForever,
                }
            }
        });
        content = content.child(
            div()
                .flex()
                .flex_wrap()
                .items_center()
                .gap_2()
                .text_xs()
                .text_color(gpui_kit::rgb(theme.text_secondary()))
                .child(
                    Icon::new(outline::ShieldLock)
                        .size(gpui_kit::rems(1.))
                        .color_value(gpui_kit::rgb(theme.text_secondary())),
                )
                .child(settings.text(M::HistoryLocal))
                .when_some(retention, |d, retention| d.child(settings.text(retention))),
        );
        let mut root = div()
            .id("history-workspace")
            .key_context("SayInHistory")
            .size_full()
            .min_w_0()
            .min_h_0()
            .flex()
            .flex_col()
            .line_height(gpui_kit::relative(1.5))
            .on_action(cx.listener(|this, _: &FocusHistorySearch, window, cx| {
                this.search.update(cx, |state, cx| state.focus(window, cx))
            }))
            .child(
                div()
                    .id("history-scroll")
                    .flex_1()
                    .min_w_0()
                    .min_h_0()
                    .overflow_y_scroll()
                    .child(content),
            );
        if service.history_undo().is_some() {
            let owner = receiver.clone();
            root = root.child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .gap_3()
                    .px_6()
                    .py_3()
                    .border_t_1()
                    .border_color(gpui_kit::rgb(theme.border_default()))
                    .bg(gpui_kit::rgb(theme.surface()))
                    .child(settings.text(M::HistoryDeleted))
                    .child(
                        YueButton::new("history-undo", settings.text(M::HistoryUndo))
                            .track_focus(&self.undo_focus)
                            .disabled(service.history_busy())
                            .on_click(move |_, _, cx| {
                                owner.update(cx, |s, cx| s.restore_history(cx))
                            }),
                    ),
            );
        }
        if service.history_command_failed() {
            root = root.child(
                div()
                    .px_6()
                    .py_3()
                    .child(support(settings.text(M::HistoryCommandFailed), theme)),
            );
        }
        root.child(self.privacy_dialog(cx))
            .child(
                YueDialog::new("history-image-dialog", theme, &self.preview_dialog)
                    .title(settings.text(M::ImageLabel))
                    .close_label(settings.text(M::HistoryClose))
                    .footer(DialogFooter::None)
                    .width(gpui_kit::rems(48.))
                    .child(
                        div()
                            .id("history-image-preview")
                            .test_support()
                            .role(gpui_kit::Role::Image)
                            .aria_label(settings.text(M::ImageView))
                            .relative()
                            .flex_none()
                            .h_80()
                            .w_full()
                            .overflow_hidden()
                            .child(match &self.preview {
                                Some(image) => gpui_kit::img(image.image().clone())
                                    .absolute()
                                    .inset_0()
                                    .size_full()
                                    .min_w_0()
                                    .min_h_0()
                                    .object_fit(gpui_kit::ObjectFit::ScaleDown)
                                    .id("history-dialog-image-pixels")
                                    .test_support()
                                    .into_any_element(),
                                None => support(
                                    settings.text(if self.preview_task.is_some() {
                                        M::HistoryLoading
                                    } else {
                                        M::ImagePreviewFailed
                                    }),
                                    theme,
                                )
                                .into_any_element(),
                            }),
                    ),
            )
            .into_any_element()
    }
}
#[cfg(test)]
#[path = "history_view/image_tests.rs"]
mod image_tests;
fn filter_options() -> [(Option<State>, M); 9] {
    [
        (None, M::AllMessages),
        (Some(State::Injected), M::HistorySubmitted),
        (Some(State::Failed), M::HistoryNotEntered),
        (Some(State::Uncertain), M::HistoryCheck),
        (Some(State::Expired), M::ReceiptExpired),
        (Some(State::Accepted), M::ReceiptAccepted),
        (Some(State::Executing), M::ReceiptExecuting),
        (Some(State::Held), M::ReceiptHeld),
        (Some(State::Cancelled), M::ReceiptCancelled),
    ]
}
fn status(state: State, settings: &PreferencesState, theme: YueTheme) -> gpui_kit::AnyElement {
    let label = filter_options()
        .into_iter()
        .find(|(value, _)| *value == Some(state))
        .map(|(_, label)| label)
        .expect("receipt state");
    let icon = match state {
        State::Injected => Icon::new(outline::CircleCheck),
        State::Failed => Icon::new(outline::AlertCircle),
        _ => Icon::new(outline::Clock),
    };
    let (fg, bg, border) = feedback(state, theme);
    div()
        .flex()
        .items_center()
        .gap_1()
        .rounded_md()
        .px_2()
        .py_1()
        .border_1()
        .border_color(border)
        .bg(bg)
        .text_xs()
        .text_color(fg)
        .child(icon.size(gpui_kit::rems(0.875)).color_value(fg.into()))
        .child(settings.text(label))
        .into_any_element()
}
fn feedback(state: State, theme: YueTheme) -> (gpui_kit::Hsla, gpui_kit::Hsla, gpui_kit::Hsla) {
    let role = match state {
        State::Injected => Some("success"),
        State::Failed => Some("error"),
        State::Uncertain | State::Expired | State::Held => Some("warning"),
        _ => None,
    };
    if let Some(role) = role {
        let token = |suffix: &str| -> gpui_kit::Hsla {
            theme
                .token(format!("--{role}-{suffix}").as_str())
                .and_then(|t| t.rgba())
                .expect("Yue feedback token")
                .into()
        };
        (token("foreground"), token("bg"), token("border"))
    } else {
        (
            gpui_kit::rgb(theme.text_secondary()).into(),
            gpui_kit::rgb(theme.surface_component()).into(),
            gpui_kit::rgb(theme.border_default()).into(),
        )
    }
}
fn support(text: String, theme: YueTheme) -> gpui_kit::Div {
    div()
        .min_w_0()
        .whitespace_normal()
        .text_xs()
        .line_height(gpui_kit::relative(1.5))
        .text_color(gpui_kit::rgb(theme.text_secondary()))
        .child(text)
}
fn format_time(timestamp: Option<i64>, format: &str) -> Option<String> {
    timestamp
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|t| t.with_timezone(&chrono::Local).format(format).to_string())
}
/// Available content width in rem, independent of display DPI and app zoom.
fn columns_for_width(width: f32) -> u16 {
    ((width + 0.75) / 24.75).floor().clamp(1., 3.) as u16
}
fn highlight_ranges(text: &str, query: &str) -> Vec<Range<usize>> {
    if query.is_empty() {
        return Vec::new();
    }
    let mut normalized = String::new();
    let mut original = Vec::new();
    for (start, c) in text.char_indices() {
        let folded = c.to_lowercase().collect::<String>();
        original.extend(std::iter::repeat_n(
            (start, start + c.len_utf8()),
            folded.len(),
        ));
        normalized.push_str(&folded);
    }
    let needle = query.to_lowercase();
    let mut ranges: Vec<Range<usize>> = Vec::new();
    for (start, _) in normalized.match_indices(&needle) {
        let range = original[start].0..original[start + needle.len() - 1].1;
        if let Some(previous) = ranges.last_mut().filter(|p| p.end >= range.start) {
            previous.end = range.end;
        } else {
            ranges.push(range);
        }
    }
    ranges
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn adaptive_columns_respect_readable_card_width_and_zoom() {
        assert_eq!(columns_for_width(30.), 1);
        assert_eq!(columns_for_width(49.), 2);
        assert_eq!(columns_for_width(75.), 3);
        assert_eq!(columns_for_width(200.), 3);
        assert_eq!(columns_for_width(75. / 1.5), 2);
    }
    #[test]
    fn highlights_keep_utf8_ranges_and_original_text() {
        let text = "中文 SayIn 😀 İSTANBUL";
        let ranges = highlight_ranges(text, "sayin");
        assert_eq!(&text[ranges[0].clone()], "SayIn");
        let ranges = highlight_ranges(text, "i");
        for range in ranges {
            assert!(text.is_char_boundary(range.start) && text.is_char_boundary(range.end));
        }
        assert_eq!(highlight_ranges("重复重复", "重复"), vec![0..12]);
    }
}
