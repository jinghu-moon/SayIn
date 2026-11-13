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

#[gpui_kit::test]
fn navigation_supports_pointer_keyboard_and_ready_pairing(cx: &mut TestAppContext) {
    let directory = Directory::new();
    let (state, receiver, cx) = open(cx, PreferencesFile::in_directory(&directory.0));
    assert_eq!(node(cx, "Receive", Role::Tab).selected(), Some(true));
    assert_ne!(node(cx, "Pair phone…", Role::Button).disabled(), Some(true));
    assert_eq!(node(cx, "Receive", Role::Tab).selected(), Some(true));
    click(cx, "History", Role::Tab);
    assert_eq!(node(cx, "History", Role::Tab).selected(), Some(true));
    key(cx, "down");
    assert_eq!(node(cx, "Settings", Role::Tab).selected(), Some(true));
    assert_eq!(node(cx, "English", Role::RadioButton).checked(), Some(true));
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
    click(cx, "简体中文", Role::RadioButton);
    assert_eq!(node(cx, "设置", Role::Tab).selected(), Some(true));
    assert_eq!(
        node(cx, "简体中文", Role::RadioButton).checked(),
        Some(true)
    );
    assert_eq!(
        node(cx, "简体中文", Role::RadioButton).focused(),
        Some(true)
    );
    assert_eq!(file.load().unwrap().language(), LanguagePreference::Chinese);
    // The same control remains focused; horizontal radio navigation uses Right.
    key(cx, "right");
    assert_eq!(node(cx, "Settings", Role::Tab).selected(), Some(true));
    assert_eq!(file.load().unwrap().language(), LanguagePreference::English);
    click(cx, "Dark", Role::RadioButton);
    assert_eq!(
        file.load().unwrap().appearance(),
        AppearancePreference::Dark
    );
    assert_eq!(node(cx, "Dark", Role::RadioButton).checked(), Some(true));
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
    click(cx, "Dark", Role::RadioButton);
    assert_eq!(
        state.read_with(cx, |state, _| state.issue()),
        Some(voicelink_desktop::preferences::StorageIssue::Save)
    );
    assert_eq!(node(cx, "Dark", Role::RadioButton).checked(), Some(true));
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
        let group = node(cx, labels[1], Role::RadioGroup);
        let option = node(cx, labels[2], Role::RadioButton);
        assert!(group.bounds().left() >= gpui_kit::px(0.));
        assert!(group.bounds().right() <= gpui_kit::px(720.));
        assert!(option.bounds().top() > group.bounds().bottom());
        assert!(option.bounds().right() <= gpui_kit::px(720.));
        assert!(option.bounds().bottom() <= gpui_kit::px(540.));
        if labels[0] == "Settings" {
            click(cx, "简体中文", Role::RadioButton);
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
    assert_eq!(
        node(cx, "Enable receiving", Role::Button).disabled(),
        Some(true)
    );
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
    click(cx, "Result", Role::ComboBox);
    key(cx, "end");
    key(cx, "enter");
    await_receiver(cx, &receiver, |s| {
        !s.history_loading() && s.history_query().state == Some(State::Expired)
    });
    assert!(receiver.read_with(cx, |s, _| s.history().entries.is_empty()));
    click(cx, "Settings", Role::Tab);
    click(cx, "简体中文", Role::RadioButton);
    click(cx, "历史记录", Role::Tab);
    assert_eq!(
        node(cx, "搜索原文、软件或窗口", Role::SearchInput).value(),
        Some("code.exe")
    );
    click(cx, "处理结果", Role::ComboBox);
    key(cx, "home");
    key(cx, "enter");
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
    click(cx, "Reviewed, resume input", Role::Button);
    await_receiver(cx, &receiver, |s| {
        !s.is_busy() && !s.snapshot().unwrap().control.paused
    });
    // This fixture reopens SQLite, so pending text has no trusted monotonic
    // deadline. Global resume must expire it, never authorize it at a new cursor.
    await_receiver(cx, &receiver, |s| {
        s.snapshot().unwrap().pending == 0
            && !s.history_loading()
            && s.history().entries.len() == 2
            && s.history()
                .entries
                .iter()
                .any(|entry| entry.receipt.st == State::Expired)
    });
    receiver.read_with(cx, |s, _| {
        let entries = &s.history().entries;
        let expired = entries.iter().find(|entry| entry.receipt.st == State::Expired).unwrap();
        assert_eq!(expired.receipt.reason.as_deref(), Some("restart_confirmation"));
        assert!(expired.executed_at.is_none(), "recovered old text must not execute");
        assert!(matches!(&expired.operation, Some(Operation::Text { text, .. }) if text == "fixture old text"));
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
    click(cx, "Hide to tray", Role::RadioButton);
    assert_eq!(file.load().unwrap().close_behavior(), CloseBehavior::Tray);
    assert!(
        cx.simulate_close(),
        "tray choice allows the native window to close"
    );
    click(cx, "Ask each time", Role::RadioButton);
    assert_eq!(file.load().unwrap().close_behavior(), CloseBehavior::Ask);
    assert!(!cx.simulate_close());
    draw(cx);
    key(cx, "escape");
    assert!(receiver.read_with(cx, |s, _| s.is_ready()));
    receiver.update(cx, |s, cx| s.stop(cx));
    await_receiver(cx, &receiver, |s| s.is_stopped());
}
