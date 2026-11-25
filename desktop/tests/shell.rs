//! Exercise production views through GPUI pointer/key events, not direct setters.
use gpui_base::test_support::{ElementSnapshot, snapshots};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};
use std::{fs, path::PathBuf};
static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);
use voicelink_desktop::{
    localization::LanguagePreference,
    preferences::{AppearancePreference, Preferences, PreferencesFile, PreferencesState},
    reception::ReceptionState,
    shell::Shell,
    theme,
};
use yue_component::{
    ThemeMode,
    gpui_kit::{
        self, AppContext as _, Entity, KeyDownEvent, KeyUpEvent, Keystroke, Modifiers, Role,
        TestAppContext, VisualTestContext,
    },
};

struct Directory(PathBuf);
impl Directory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "voicelink-ui-{}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for Directory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn draw(cx: &mut VisualTestContext) {
    cx.run_until_parked();
    cx.update(|window, cx| {
        window.refresh();
        window.draw(cx).clear(cx);
    });
    cx.run_until_parked();
}

fn await_receiver(
    cx: &mut VisualTestContext,
    receiver: &Entity<ReceptionState>,
    predicate: impl Fn(&ReceptionState) -> bool,
) {
    let started = Instant::now();
    loop {
        cx.executor().advance_clock(Duration::from_millis(20));
        cx.run_until_parked();
        if receiver.read_with(cx, |state, _| predicate(state)) {
            break;
        }
        assert!(
            started.elapsed() < Duration::from_secs(5),
            "native receiver task timed out"
        );
        std::thread::sleep(Duration::from_millis(1));
    }
    draw(cx);
}

fn open(
    cx: &mut TestAppContext,
    file: PreferencesFile,
) -> (
    Entity<PreferencesState>,
    Entity<ReceptionState>,
    &mut VisualTestContext,
) {
    // This integration fixture owns real SQLite/input threads. GPUI explicitly
    // supports external wakes in parking mode; pure view tests need no such mode.
    cx.executor().allow_parking();
    file.save(&Preferences::default().with_language(LanguagePreference::English))
        .unwrap();
    let receiver_directory = file.path().parent().unwrap().join("receiver");
    let receiver = cx.update(|cx| cx.new(|cx| ReceptionState::local_only(receiver_directory, cx)));
    let state = cx.update(|cx| {
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        cx.new(|_| PreferencesState::new(file))
    });
    let owner = state.clone();
    let receiver_owner = receiver.clone();
    let (_, cx) = cx.add_window_view(move |window, cx| {
        let shell = cx.new(|cx| Shell::new(owner, receiver_owner, window, cx));
        gpui_kit::base::Root::new(shell, window, cx)
    });
    cx.update(|window, _| window.activate_window());
    await_receiver(cx, &receiver, |s| s.is_ready());
    draw(cx);
    draw(cx);
    (state, receiver, cx)
}

fn node(cx: &mut VisualTestContext, label: &str, role: Role) -> ElementSnapshot {
    cx.update(|window, _| {
        let nodes = snapshots(window);
        let found = nodes
            .iter()
            .filter(|node| node.label() == Some(label) && node.role() == Some(role))
            .collect::<Vec<_>>();
        assert_eq!(found.len(), 1, "unique {label}/{role:?}; nodes: {nodes:?}");
        found[0].clone()
    })
}

fn click(cx: &mut VisualTestContext, label: &str, role: Role) {
    let point = node(cx, label, role).bounds().center();
    cx.simulate_click(point, Modifiers::default());
    draw(cx);
    draw(cx);
}

fn key(cx: &mut VisualTestContext, value: &str) {
    let stroke = Keystroke::parse(value).unwrap();
    cx.simulate_event(KeyDownEvent {
        keystroke: stroke.clone(),
        is_held: false,
        prefer_character_input: false,
    });
    cx.simulate_event(KeyUpEvent { keystroke: stroke });
    draw(cx);
    draw(cx);
}

fn choose(cx: &mut VisualTestContext, field: &str, option: &str) {
    click(cx, field, Role::ComboBox);
    click(cx, option, Role::ListBoxOption);
}

#[gpui_kit::test]
fn sidebar_rows_fill_an_inset_column_across_locales_and_sizes(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let (state, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    for (language, navigation, labels) in [
        (
            LanguagePreference::English,
            "Navigation",
            ["Receive", "Devices", "History", "Settings"],
        ),
        (
            LanguagePreference::Chinese,
            "导航",
            ["接收", "设备", "历史记录", "设置"],
        ),
    ] {
        for appearance in [AppearancePreference::Light, AppearancePreference::Dark] {
            state.update(cx, |state, cx| {
                state.save(
                    state
                        .value()
                        .clone()
                        .with_language(language)
                        .with_appearance(appearance),
                    cx,
                );
            });
            for width in [720., 1000.] {
                let handle = cx.update(|window, _| window.window_handle());
                cx.simulate_window_resize(
                    handle,
                    gpui_kit::size(gpui_kit::px(width), gpui_kit::px(660.)),
                );
                draw(cx);
                let sidebar = node(cx, navigation, Role::TabList).bounds();
                let rows = labels.map(|label| node(cx, label, Role::Tab).bounds());
                assert!(
                    rows[0].top() >= sidebar.top() + gpui_kit::px(12.),
                    "navigation needs a top inset"
                );
                for row in rows {
                    assert_eq!(row.left(), sidebar.left() + gpui_kit::px(12.));
                    assert!(row.right() <= sidebar.right() - gpui_kit::px(12.));
                    assert!(
                        row.size.width >= sidebar.size.width - gpui_kit::px(26.),
                        "the entire inset row is clickable"
                    );
                    assert!(row.size.height >= gpui_kit::px(36.));
                }
                for pair in rows.windows(2) {
                    assert!(pair[1].top() >= pair[0].bottom() + gpui_kit::px(4.));
                }
                // Activate near the trailing edge, outside the text/icon group.
                cx.simulate_click(
                    gpui_kit::point(rows[2].right() - gpui_kit::px(6.), rows[2].center().y),
                    Modifiers::default(),
                );
                draw(cx);
                assert_eq!(node(cx, labels[2], Role::Tab).selected(), Some(true));
                key(cx, "down");
                assert_eq!(node(cx, labels[3], Role::Tab).selected(), Some(true));
                key(cx, "home");
            }
        }
    }
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}

#[gpui_kit::test]
fn navigation_supports_pointer_keyboard_and_ready_pairing(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let (state, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    assert_eq!(node(cx, "Receive", Role::Tab).selected(), Some(true));
    assert_ne!(
        node(cx, "Generate invitation", Role::Button).disabled(),
        Some(true)
    );
    assert_eq!(node(cx, "Receive", Role::Tab).selected(), Some(true));
    click(cx, "History", Role::Tab);
    assert_eq!(node(cx, "History", Role::Tab).selected(), Some(true));
    key(cx, "down");
    assert_eq!(node(cx, "Settings", Role::Tab).selected(), Some(true));
    assert_eq!(
        node(cx, "Language", Role::ComboBox).value(),
        Some("English")
    );
    key(cx, "home");
    assert_eq!(node(cx, "Receive", Role::Tab).selected(), Some(true));
    assert_eq!(
        state.read_with(cx, |state, _| state.value().language()),
        LanguagePreference::English
    );
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
    assert!(receiver.read_with(cx, |s, _| s.is_stopped()));
}

#[gpui_kit::test]
fn local_device_revocation_requires_confirmation_and_persists(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let receiver_directory = directory.0.join("receiver");
    voicelink_transport::storage::secure_directory(&receiver_directory).unwrap();
    let device = "12345678-1234-4234-8234-123456789012";
    {
        let mut ledger =
            voicelink_receiver::windows_input::open_ledger(&receiver_directory).unwrap();
        voicelink_transport::HostIdentity::open(&receiver_directory, 0).unwrap();
        ledger
            .provision_device(device, &[9; 32], String::new())
            .unwrap();
    }
    let (_, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    assert_eq!(
        receiver.read_with(cx, |s, _| s.snapshot().unwrap().device_ids.clone()),
        vec![device]
    );
    click(cx, "Devices", Role::Tab);
    click(cx, "Revoke authorization…", Role::Button);
    assert_eq!(
        receiver.read_with(cx, |s, _| s.revoking().map(str::to_owned)),
        Some(device.into())
    );
    assert_eq!(
        receiver.read_with(cx, |s, _| s.snapshot().unwrap().paired_devices),
        1
    );
    click(cx, "Revoke this device", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && s.snapshot().unwrap().paired_devices == 0
    });
    assert!(receiver.read_with(cx, |s, _| s.snapshot().unwrap().control.paused));
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
    let mut ledger = voicelink_receiver::windows_input::open_ledger(&receiver_directory).unwrap();
    assert!(ledger.authenticate(device, &[9; 32]).is_err());
}

#[gpui_kit::test]
fn language_and_theme_changes_preserve_selection_focus_and_persist(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let file = PreferencesFile::in_directory(&directory.0);
    let (state, receiver, cx) = open(cx, file.clone());
    click(cx, "Settings", Role::Tab);
    choose(cx, "Language", "简体中文");
    assert_eq!(node(cx, "设置", Role::Tab).selected(), Some(true));
    assert_eq!(
        node(cx, "界面语言", Role::ComboBox).value(),
        Some("简体中文")
    );
    assert_eq!(node(cx, "界面语言", Role::ComboBox).focused(), Some(true));
    assert_eq!(file.load().unwrap().language(), LanguagePreference::Chinese);
    // Reopen the same retained field and commit the next locale by keyboard.
    key(cx, "down");
    key(cx, "end");
    key(cx, "enter");
    assert_eq!(node(cx, "Settings", Role::Tab).selected(), Some(true));
    assert_eq!(file.load().unwrap().language(), LanguagePreference::English);
    choose(cx, "Appearance", "Dark");
    assert_eq!(
        file.load().unwrap().appearance(),
        AppearancePreference::Dark
    );
    assert_eq!(node(cx, "Appearance", Role::ComboBox).value(), Some("Dark"));
    click(cx, "Appearance", Role::ComboBox);
    key(cx, "home");
    key(cx, "escape");
    assert_eq!(node(cx, "Appearance", Role::ComboBox).focused(), Some(true));
    assert_eq!(node(cx, "Appearance", Role::ComboBox).value(), Some("Dark"));
    assert_eq!(
        file.load().unwrap().appearance(),
        AppearancePreference::Dark
    );
    cx.update(|_, cx| assert_eq!(yue_component::current_theme(cx).mode(), ThemeMode::Dark));
    assert!(!state.read_with(cx, |state, _| state.is_saving()));
    // A fresh application state recovers exactly the choices committed by UI.
    assert_eq!(
        PreferencesState::new(file).value().language(),
        LanguagePreference::English
    );
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
    assert!(receiver.read_with(cx, |s, _| s.is_stopped()));
}

#[gpui_kit::test]
fn rapid_preferences_changes_commit_the_latest_complete_snapshot(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let file = PreferencesFile::in_directory(&directory.0);
    let state = cx.update(|cx| cx.new(|_| PreferencesState::new(file.clone())));
    state.update(cx, |state, cx| {
        state.save(
            state
                .value()
                .clone()
                .with_language(LanguagePreference::Chinese),
            cx,
        );
        assert!(state.is_saving());
        assert!(
            state.is_editable(),
            "pending I/O must not steal keyboard focus"
        );
        state.save(
            state
                .value()
                .clone()
                .with_appearance(AppearancePreference::Dark),
            cx,
        );
        state.save(
            state
                .value()
                .clone()
                .with_language(LanguagePreference::English),
            cx,
        );
    });
    cx.run_until_parked();
    let persisted = file.load().unwrap();
    assert_eq!(persisted.language(), LanguagePreference::English);
    assert_eq!(persisted.appearance(), AppearancePreference::Dark);
    assert!(!state.read_with(cx, |state, _| state.is_saving()));
}

#[gpui_kit::test]
fn failed_write_keeps_ui_choice_and_retry_persists_it(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let file = PreferencesFile::in_directory(&directory.0);
    let (state, receiver, cx) = open(cx, file.clone());
    click(cx, "Settings", Role::Tab);
    // A directory at the destination provokes a real atomic-replace failure.
    fs::remove_file(file.path()).unwrap();
    fs::create_dir(file.path()).unwrap();
    choose(cx, "Appearance", "Dark");
    assert_eq!(
        state.read_with(cx, |state, _| state.issue()),
        Some(voicelink_desktop::preferences::StorageIssue::Save)
    );
    assert_eq!(node(cx, "Appearance", Role::ComboBox).value(), Some("Dark"));
    assert_ne!(node(cx, "Retry", Role::Button).disabled(), Some(true));
    fs::remove_dir(file.path()).unwrap();
    click(cx, "Retry", Role::Button);
    assert_eq!(
        file.load().unwrap().appearance(),
        AppearancePreference::Dark
    );
    assert_eq!(state.read_with(cx, |state, _| state.issue()), None);
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
    assert!(receiver.read_with(cx, |s, _| s.is_stopped()));
}

#[gpui_kit::test]
fn controls_fit_the_minimum_window_in_both_locales(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let file = PreferencesFile::in_directory(&directory.0);
    let (_, receiver, cx) = open(cx, file);
    let handle = cx.update(|window, _| window.window_handle());
    cx.simulate_window_resize(
        handle,
        gpui_kit::size(gpui_kit::px(720.), gpui_kit::px(540.)),
    );
    draw(cx);
    for labels in [
        ["Settings", "Language", "Dark"],
        ["设置", "界面语言", "深色"],
    ] {
        click(cx, labels[0], Role::Tab);
        let group = node(cx, labels[1], Role::ComboBox);
        let appearance = if labels[0] == "Settings" {
            "Appearance"
        } else {
            "外观"
        };
        click(cx, appearance, Role::ComboBox);
        let option = node(cx, labels[2], Role::ListBoxOption);
        assert!(group.bounds().left() >= gpui_kit::px(0.));
        assert!(group.bounds().right() <= gpui_kit::px(720.));
        assert!(option.bounds().top() > group.bounds().bottom());
        assert!(option.bounds().right() <= gpui_kit::px(720.));
        assert!(option.bounds().bottom() <= gpui_kit::px(540.));
        key(cx, "escape");
        if labels[0] == "Settings" {
            choose(cx, "Language", "简体中文");
        }
    }
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
    assert!(receiver.read_with(cx, |s, _| s.is_stopped()));
}

#[gpui_kit::test]
fn receiver_starts_paused_and_enabling_needs_phone_but_never_a_window_lock(
    cx: &mut TestAppContext,
) {
    let directory = Directory::new();
    let (_, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    assert!(receiver.read_with(cx, |s, _| {
        let snapshot = s.snapshot().unwrap();
        snapshot.control.paused && snapshot.control.target.is_none() && snapshot.paired_devices == 0
    }));
    cx.update(|window, _| {
        assert!(
            !snapshots(window)
                .iter()
                .any(|n| n.label() == Some("Enable receiving"))
        )
    });
    cx.update(|window, _| {
        assert!(
            !snapshots(window)
                .iter()
                .any(|n| n.label() == Some("Lock window…"))
        )
    });
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}

#[gpui_kit::test]
fn paired_phone_can_enable_pause_and_reenable_without_capturing_app_window(
    cx: &mut TestAppContext,
) {
    let directory = Directory::new();
    let receiver_directory = directory.0.join("receiver");
    voicelink_transport::storage::secure_directory(&receiver_directory).unwrap();
    {
        let mut ledger =
            voicelink_receiver::windows_input::open_ledger(&receiver_directory).unwrap();
        voicelink_transport::HostIdentity::open(&receiver_directory, 0).unwrap();
        ledger
            .provision_device(
                "12345678-1234-4234-8234-123456789012",
                &[9; 32],
                String::new(),
            )
            .unwrap();
    }
    let (_, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    click(cx, "Enable receiving", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && !s.snapshot().unwrap().control.paused
    });
    let first_scope = receiver.read_with(cx, |s, _| s.snapshot().unwrap().control.target.clone());
    assert!(first_scope.is_some());
    assert_eq!(
        node(cx, "Pause receiving", Role::Button).focused(),
        Some(true)
    );
    key(cx, "space");
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && s.snapshot().unwrap().control.paused
    });
    key(cx, "space");
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && !s.snapshot().unwrap().control.paused
    });
    assert_ne!(
        receiver.read_with(cx, |s, _| s.snapshot().unwrap().control.target.clone()),
        first_scope
    );
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}

#[gpui_kit::test]
fn history_search_copy_filter_and_locale_preserve_original_text(cx: &mut TestAppContext) {
    use voicelink_receiver::{
        history::TargetApplication,
        ledger::InputOutcome,
        protocol::{Operation, State, Submit, TextSource},
    };
    let directory = Directory::new();
    let receiver_directory = directory.0.join("receiver");
    voicelink_transport::storage::secure_directory(&receiver_directory).unwrap();
    let original = "原文 Mixed English — must remain unchanged";
    {
        let mut ledger =
            voicelink_receiver::windows_input::open_ledger(&receiver_directory).unwrap();
        voicelink_transport::HostIdentity::open(&receiver_directory, 0).unwrap();
        let device = "12345678-1234-4234-8234-123456789012";
        ledger
            .provision_device(device, &[9; 32], String::new())
            .unwrap();
        let session = ledger.authenticate(device, &[9; 32]).unwrap();
        let target = "22345678-1234-4234-8234-123456789012";
        let state = ledger.bind_target(target).unwrap();
        ledger.resume(&state.state_rev, false).unwrap();
        for (index, text, program) in [
            (1, original, "Code.exe"),
            (2, "second message", "notepad.exe"),
        ] {
            let submit = Submit::new(
                session.id().into(),
                format!("32345678-1234-4234-8234-{index:012}"),
                target.into(),
                Operation::Text {
                    text: text.into(),
                    src: TextSource::Ime,
                },
            );
            ledger.submit(&session, &submit).unwrap();
            let job = ledger.prepare_next().unwrap().unwrap();
            ledger
                .mark_executing_at(
                    &job,
                    Some(TargetApplication::new(
                        format!("C:\\Fixture\\{program}"),
                        "Test document".into(),
                        42,
                        123,
                    )),
                )
                .unwrap();
            ledger
                .finish(&job, InputOutcome::Injected { method: "fixture" })
                .unwrap();
        }
    }
    let (_settings, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    let handle = cx.update(|w, _| w.window_handle());
    cx.simulate_window_resize(
        handle,
        gpui_kit::size(gpui_kit::px(1100.), gpui_kit::px(900.)),
    );
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.history().entries.len() == 2
    });
    click(cx, "History", Role::Tab);
    click(cx, "Search text, application or window", Role::SearchInput);
    cx.simulate_input("code.exe");
    draw(cx);
    await_receiver(cx, &receiver, |s| {
        !s.history_loading()
            && s.history_query().search == "code.exe"
            && s.history().entries.len() == 1
    });
    click(cx, "Copy original", Role::Button);
    assert_eq!(
        cx.update(|_, cx| cx.read_from_clipboard().unwrap().text().unwrap()),
        original
    );
    click(cx, "Expired", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.history_query().state == Some(State::Expired)
    });
    assert!(receiver.read_with(cx, |s, _| s.history().entries.is_empty()));
    click(cx, "Settings", Role::Tab);
    choose(cx, "Language", "简体中文");
    click(cx, "历史记录", Role::Tab);
    assert_eq!(
        node(cx, "搜索原文、软件或窗口", Role::SearchInput).value(),
        Some("code.exe")
    );
    click(cx, "全部", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.history_query().state.is_none() && s.history().entries.len() == 1
    });
    click(cx, "复制原文", Role::Button);
    assert_eq!(
        cx.update(|_, cx| cx.read_from_clipboard().unwrap().text().unwrap()),
        original
    );
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}

#[gpui_kit::test]
fn uncertain_input_requires_local_checkbox_and_does_not_release_held_text(cx: &mut TestAppContext) {
    use voicelink_receiver::{
        ledger::InputOutcome,
        protocol::{Operation, State, Submit, TextSource},
    };
    let directory = Directory::new();
    let receiver_directory = directory.0.join("receiver");
    voicelink_transport::storage::secure_directory(&receiver_directory).unwrap();
    {
        let mut ledger =
            voicelink_receiver::windows_input::open_ledger(&receiver_directory).unwrap();
        voicelink_transport::HostIdentity::open(&receiver_directory, 0).unwrap();
        let device = "12345678-1234-4234-8234-123456789012";
        ledger
            .provision_device(device, &[9; 32], String::new())
            .unwrap();
        let session = ledger.authenticate(device, &[9; 32]).unwrap();
        let target = "22345678-1234-4234-8234-123456789012";
        let state = ledger.bind_target(target).unwrap();
        ledger.resume(&state.state_rev, false).unwrap();
        for index in 1..=2 {
            ledger
                .submit(
                    &session,
                    &Submit::new(
                        session.id().into(),
                        format!("32345678-1234-4234-8234-{index:012}"),
                        target.into(),
                        Operation::Text {
                            text: "fixture old text".into(),
                            src: TextSource::Manual,
                        },
                    ),
                )
                .unwrap();
        }
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.mark_executing(&job).unwrap();
        ledger
            .finish(
                &job,
                InputOutcome::Uncertain {
                    reason: "fixture_partial",
                },
            )
            .unwrap();
    }
    let (_, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    let handle = cx.update(|w, _| w.window_handle());
    cx.simulate_window_resize(
        handle,
        gpui_kit::size(gpui_kit::px(1000.), gpui_kit::px(660.)),
    );
    draw(cx);
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.review_entry().is_some()
    });
    assert!(receiver.read_with(cx, |s, _| matches!(
        &s.review_entry().unwrap().operation,
        Some(Operation::Text { text, .. }) if text == "fixture old text"
    )));
    click(cx, "Review input…", Role::Button);
    assert_eq!(
        node(cx, "Reviewed, resume input", Role::Button).disabled(),
        Some(true)
    );
    click(cx, "Reviewed, resume input", Role::Button);
    assert!(receiver.read_with(cx, |s, _| s.snapshot().unwrap().control.paused));
    click(
        cx,
        "I checked the target content and understand that previous messages will not retry automatically.",
        Role::CheckBox,
    );
    assert_ne!(
        node(cx, "Reviewed, resume input", Role::Button).disabled(),
        Some(true)
    );
    // A checked box is not reusable authorization for a later desktop state.
    let checked_revision =
        receiver.read_with(cx, |s, _| s.snapshot().unwrap().control.state_rev.clone());
    receiver.update(cx, |s, cx| s.pause(cx));
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && s.snapshot().unwrap().control.state_rev != checked_revision
    });
    assert_eq!(
        node(cx, "Reviewed, resume input", Role::Button).disabled(),
        Some(true)
    );
    click(cx, "Reviewed, resume input", Role::Button);
    assert!(receiver.read_with(cx, |s, _| s.snapshot().unwrap().control.paused));
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.review_entry().is_some()
    });
    assert_eq!(
        node(cx, "Reviewed, resume input", Role::Button).disabled(),
        Some(true)
    );
    click(
        cx,
        "I checked the target content and understand that previous messages will not retry automatically.",
        Role::CheckBox,
    );
    click(cx, "Reviewed, resume input", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && !s.snapshot().unwrap().control.paused
    });
    // A held request keeps its retention across restart and still needs explicit release.
    await_receiver(cx, &receiver, |s| {
        s.snapshot().unwrap().pending == 1
            && !s.history_loading()
            && s.history().entries.len() == 2
            && s.history()
                .entries
                .iter()
                .any(|entry| entry.receipt.st == State::Held)
    });
    receiver.read_with(cx, |s, _| {
        let entries = &s.history().entries;
        let held = entries.iter().find(|entry| entry.receipt.st == State::Held).unwrap();
        assert_eq!(held.receipt.reason.as_deref(), Some("outcome_review_required"));
        assert!(held.executed_at.is_none(), "recovered old text must not execute");
        assert!(matches!(&held.operation, Some(Operation::Text { text, .. }) if text == "fixture old text"));
        assert!(entries.iter().any(|entry| entry.receipt.st == State::Uncertain));
        assert!(!entries.iter().any(|entry| entry.receipt.st == State::Injected));
    });
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}

#[gpui_kit::test]
fn native_close_asks_and_settings_persist_each_close_choice(cx: &mut TestAppContext) {
    use voicelink_desktop::preferences::CloseBehavior;
    let directory = Directory::new();
    let file = PreferencesFile::in_directory(&directory.0);
    let (_, receiver, cx) = open(cx, file.clone());
    receiver.update(cx, |s, cx| s.set_background_available(true, cx));
    draw(cx);
    assert!(!cx.simulate_close(), "default close must offer a choice");
    draw(cx);
    assert_eq!(
        node(cx, "Keep receiving in the background?", Role::Dialog).role(),
        Some(Role::Dialog)
    );
    assert_ne!(
        node(cx, "Keep receiving", Role::Button).disabled(),
        Some(true)
    );
    click(cx, "Cancel", Role::Button);
    assert!(receiver.read_with(cx, |s, _| s.is_ready()));
    click(cx, "Settings", Role::Tab);
    cx.simulate_event(gpui_kit::ScrollWheelEvent {
        position: gpui_kit::point(gpui_kit::px(400.), gpui_kit::px(400.)),
        delta: gpui_kit::ScrollDelta::Pixels(gpui_kit::point(
            gpui_kit::px(0.),
            gpui_kit::px(-2000.),
        )),
        touch_phase: gpui_kit::TouchPhase::Moved,
        ..Default::default()
    });
    draw(cx);
    choose(cx, "When closing the window", "Hide to tray");
    assert_eq!(file.load().unwrap().close_behavior(), CloseBehavior::Tray);
    assert!(
        cx.simulate_close(),
        "tray choice allows the native window to close"
    );
    choose(cx, "When closing the window", "Ask each time");
    assert_eq!(file.load().unwrap().close_behavior(), CloseBehavior::Ask);
    assert!(!cx.simulate_close());
    draw(cx);
    key(cx, "escape");
    assert!(receiver.read_with(cx, |s, _| s.is_ready()));
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}

#[gpui_kit::test]
fn history_cards_reflow_and_menu_privacy_delete_undo_work_through_controls(
    cx: &mut TestAppContext,
) {
    use voicelink_receiver::{
        history::TargetApplication,
        ledger::InputOutcome,
        protocol::{Operation, Submit, TextSource},
    };
    let directory = Directory::new();
    let receiver_directory = directory.0.join("receiver");
    voicelink_transport::storage::secure_directory(&receiver_directory).unwrap();
    {
        let mut ledger =
            voicelink_receiver::windows_input::open_ledger(&receiver_directory).unwrap();
        voicelink_transport::HostIdentity::open(&receiver_directory, 0).unwrap();
        let device = "12345678-1234-4234-8234-123456789012";
        ledger
            .provision_device(device, &[9; 32], "Test phone".into())
            .unwrap();
        let session = ledger.authenticate(device, &[9; 32]).unwrap();
        let target = "22345678-1234-4234-8234-123456789012";
        let state = ledger.bind_target(target).unwrap();
        ledger.resume(&state.state_rev, false).unwrap();
        for index in 1..=3 {
            let message = Submit::new(
                session.id().into(),
                format!("32345678-1234-4234-8234-{index:012}"),
                target.into(),
                Operation::Text {
                    text: format!("Record {index} 中文 SayIn original"),
                    src: TextSource::Ime,
                },
            );
            ledger.submit(&session, &message).unwrap();
            let job = ledger.prepare_next().unwrap().unwrap();
            ledger
                .mark_executing_at(
                    &job,
                    Some(TargetApplication::new(
                        std::env::var("WINDIR").unwrap() + r"\explorer.exe",
                        "Historical target".into(),
                        42,
                        123,
                    )),
                )
                .unwrap();
            ledger
                .finish(&job, InputOutcome::Injected { method: "fixture" })
                .unwrap();
        }
    }
    let (settings, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.history().entries.len() == 3
    });
    click(cx, "History", Role::Tab);
    let handle = cx.update(|window, _| window.window_handle());
    for (width, columns) in [(720., 1), (1040., 2), (1120., 2), (1600., 3)] {
        cx.simulate_window_resize(
            handle,
            gpui_kit::size(gpui_kit::px(width), gpui_kit::px(1000.)),
        );
        draw(cx);
        let bounds = ["History · 3", "History · 2", "History · 1"]
            .map(|label| node(cx, label, Role::Group).bounds());
        let distinct = bounds
            .iter()
            .map(|b| f32::from(b.left()).round() as i32)
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(distinct.len(), columns, "{width}: {bounds:?}");
        let source = node(cx, "Source · 3", Role::Group).bounds();
        let destination = node(cx, "Destination · 3", Role::Group).bounds();
        if width != 1040. {
            assert!(
                (f32::from(source.center().y) - f32::from(destination.center().y)).abs() <= 1.,
                "roomy single card keeps the demo's inline route: {source:?} {destination:?}"
            );
            assert!(destination.left() > source.right());
        } else {
            assert!(
                destination.top() >= source.bottom(),
                "narrow multi-column cards stack route metadata"
            );
        }
        for card in bounds {
            assert!(
                card.left() >= gpui_kit::px(140.) && card.right() <= gpui_kit::px(width),
                "card stays within content: {card:?}"
            );
        }
    }
    let first_more = cx.update(|w, _| {
        snapshots(w)
            .into_iter()
            .find(|n| n.label() == Some("More actions") && n.role() == Some(Role::Button))
            .unwrap()
            .bounds()
            .center()
    });
    cx.simulate_click(first_more, Modifiers::default());
    draw(cx);
    key(cx, "enter");
    assert!(cx.update(|w, _| {
        snapshots(w)
            .iter()
            .any(|n| n.label() == Some("Window title"))
    }));
    cx.simulate_click(first_more, Modifiers::default());
    draw(cx);
    key(cx, "end");
    key(cx, "enter");
    await_receiver(cx, &receiver, |s| {
        !s.history_busy()
            && !s.history_loading()
            && s.history().entries.len() == 2
            && s.history_undo().is_some()
    });
    click(cx, "Undo", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.history_busy() && !s.history_loading() && s.history().entries.len() == 3
    });
    click(cx, "History & privacy…", Role::Button);
    click(cx, "Save input history", Role::Switch);
    await_receiver(cx, &receiver, |s| {
        !s.history_busy() && s.history_policy().is_some_and(|p| !p.is_enabled())
    });
    key(cx, "escape");
    cx.executor().advance_clock(Duration::from_millis(500));
    draw(cx);
    click(cx, "Settings", Role::Tab);
    choose(cx, "Language", "简体中文");
    click(cx, "历史记录", Role::Tab);
    click(cx, "历史与隐私…", Role::Button);
    assert_eq!(
        node(cx, "保存输入历史", Role::Switch).checked(),
        Some(false)
    );
    key(cx, "escape");
    assert_eq!(
        settings.read_with(cx, |s, _| s.value().language()),
        LanguagePreference::Chinese
    );
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}
