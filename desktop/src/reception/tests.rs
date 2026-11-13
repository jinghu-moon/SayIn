use super::*;
use crate::{
    localization::LanguagePreference,
    preferences::{Preferences, PreferencesFile, PreferencesState},
    shell::Shell,
    theme,
};
use gpui_base::test_support::{ElementSnapshot, snapshots};
use std::time::Duration;
use voicelink_transport::interfaces::{NetworkCategory, NetworkProfile};
use yue_component::{
    ThemeMode,
    gpui_kit::{
        self, KeyDownEvent, KeyUpEvent, Keystroke, Modifiers, Role, TestAppContext,
        VisualTestContext,
    },
};

fn interface(id: u8, category: NetworkCategory) -> NetworkInterface {
    NetworkInterface {
        adapter: format!("00000000-0000-4000-8000-{id:012}").parse().unwrap(),
        address: format!("192.0.2.{id}").parse().unwrap(),
        name: format!("Test Wi-Fi {id}"),
        profiles: vec![NetworkProfile {
            id: "00000000-0000-4000-8000-999999999999".parse().unwrap(),
            category,
        }],
    }
}

#[test]
fn changing_selection_or_refreshing_never_transfers_public_consent() {
    let first = interface(1, NetworkCategory::Public);
    let second = interface(2, NetworkCategory::Public);
    let private = interface(3, NetworkCategory::Private);
    let mut selection = NetworkSelection::default();
    selection.replace(vec![first.clone(), second.clone(), private.clone()]);
    selection.select(&first.key());
    assert!(selection.authorization.is_none());
    selection.authorize_public(true);
    assert!(
        selection
            .authorization
            .as_ref()
            .unwrap()
            .permits(std::slice::from_ref(&first))
    );
    selection.select(&second.key());
    assert!(selection.authorization.is_none());
    selection.select(&first.key());
    assert!(selection.authorization.is_none());
    selection.authorize_public(true);
    selection.authorize_public(false);
    assert!(selection.authorization.is_none());
    selection.select(&private.key());
    assert!(selection.authorization.is_some());
    selection.select(&first.key());
    selection.authorize_public(true);
    selection.replace(vec![first.clone()]);
    assert!(selection.selected().is_none());
    assert!(selection.authorization.is_none());
    selection.select(&first.key());
    assert!(selection.authorization.is_none());
}

fn draw(cx: &mut VisualTestContext) {
    cx.run_until_parked();
    cx.update(|window, cx| {
        window.refresh();
        window.draw(cx).clear(cx);
    });
    cx.run_until_parked();
}
fn node(cx: &mut VisualTestContext, label: &str, role: Role) -> ElementSnapshot {
    cx.update(|window, _| {
        snapshots(window)
            .into_iter()
            .find(|n| n.label() == Some(label) && n.role() == Some(role))
            .unwrap_or_else(|| panic!("missing {label}; nodes: {:?}", snapshots(window)))
    })
}
fn click(cx: &mut VisualTestContext, label: &str, role: Role) {
    let point = node(cx, label, role).bounds().center();
    cx.simulate_click(point, Modifiers::default());
    draw(cx);
    draw(cx);
}
fn key(cx: &mut VisualTestContext, value: &str) {
    let keystroke = Keystroke::parse(value).unwrap();
    cx.simulate_event(KeyDownEvent {
        keystroke: keystroke.clone(),
        is_held: false,
        prefer_character_input: false,
    });
    cx.simulate_event(KeyUpEvent { keystroke });
    draw(cx);
    draw(cx);
}
fn wait(
    cx: &mut VisualTestContext,
    state: &yue_component::gpui_kit::Entity<ReceptionState>,
    predicate: impl Fn(&ReceptionState) -> bool,
) {
    let until = std::time::Instant::now() + Duration::from_secs(5);
    while !state.read_with(cx, |s, _| predicate(s)) {
        assert!(std::time::Instant::now() < until, "receiver timed out");
        cx.run_until_parked();
        std::thread::sleep(Duration::from_millis(1));
    }
    draw(cx);
}

fn open(
    cx: &mut TestAppContext,
) -> (
    std::path::PathBuf,
    yue_component::gpui_kit::Entity<ReceptionState>,
    yue_component::gpui_kit::Entity<PreferencesState>,
    &mut VisualTestContext,
) {
    cx.executor().allow_parking();
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let directory = std::env::temp_dir().join(format!(
        "voicelink-reception-ui-{}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos(),
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    std::fs::create_dir(&directory).unwrap();
    let file = PreferencesFile::in_directory(&directory);
    file.save(&Preferences::default().with_language(LanguagePreference::English))
        .unwrap();
    let state =
        cx.update(|cx| cx.new(|cx| ReceptionState::local_only(directory.join("receiver"), cx)));
    let preferences = cx.update(|cx| {
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        cx.new(|_| PreferencesState::new(file))
    });
    let owner = state.clone();
    let settings = preferences.clone();
    let (_, cx) = cx.add_window_view(move |window, cx| {
        let shell = cx.new(|cx| Shell::new(preferences, owner, window, cx));
        gpui_kit::base::Root::new(shell, window, cx)
    });
    let handle = cx.update(|window, _| {
        window.activate_window();
        window.window_handle()
    });
    cx.simulate_window_resize(
        handle,
        gpui_kit::size(gpui_kit::px(1100.), gpui_kit::px(1500.)),
    );
    wait(cx, &state, |s| s.is_ready());
    (directory, state, settings, cx)
}

#[gpui_kit::test]
fn public_consent_controls_start_and_keyboard_switching_clears_it(cx: &mut TestAppContext) {
    let (directory, state, settings, cx) = open(cx);
    let handle = cx.update(|window, _| window.window_handle());
    // Inject OS discovery metadata only. All controls and handlers are production code.
    state.update(cx, |s, cx| {
        s.network_requested = true;
        s.selection.replace(vec![
            interface(1, NetworkCategory::Public),
            interface(2, NetworkCategory::Public),
        ]);
        cx.notify();
    });
    draw(cx);
    draw(cx);
    let first = "Test Wi-Fi 1 · 192.0.2.1 · Public network";
    let second = "Test Wi-Fi 2 · 192.0.2.2 · Public network";
    let consent = "Allow selected Public network for this session";
    click(cx, first, Role::RadioButton);
    assert_eq!(
        node(cx, "Start pairing", Role::Button).disabled(),
        Some(true)
    );
    click(cx, "Start pairing", Role::Button);
    assert!(!state.read_with(cx, |s, _| s.is_busy() || s.network.is_some()));
    click(cx, consent, Role::CheckBox);
    assert_eq!(node(cx, consent, Role::CheckBox).checked(), Some(true));
    assert!(state.read_with(cx, |s, _| s.network_authorized()));
    assert_ne!(
        node(cx, "Start pairing", Role::Button).disabled(),
        Some(true)
    );
    key(cx, "space");
    assert!(!state.read_with(cx, |s, _| s.network_authorized()));
    key(cx, "space");
    assert!(state.read_with(cx, |s, _| s.network_authorized()));
    click(cx, second, Role::RadioButton);
    assert_eq!(node(cx, consent, Role::CheckBox).checked(), Some(false));
    key(cx, "up");
    assert_eq!(node(cx, first, Role::RadioButton).checked(), Some(true));
    assert!(!state.read_with(cx, |s, _| s.network_authorized()));
    click(cx, consent, Role::CheckBox);
    // The same permission controls remain usable in both locales at minimum size.
    cx.simulate_window_resize(
        handle,
        gpui_kit::size(gpui_kit::px(720.), gpui_kit::px(540.)),
    );
    for (language, consent_label, start_label) in [
        (LanguagePreference::English, consent, "Start pairing"),
        (
            LanguagePreference::Chinese,
            "本次会话允许所选公用网络",
            "开始配对",
        ),
    ] {
        settings.update(cx, |s, cx| {
            s.save(s.value().clone().with_language(language), cx)
        });
        draw(cx);
        draw(cx);
        cx.simulate_event(gpui_kit::ScrollWheelEvent {
            position: gpui_kit::point(gpui_kit::px(360.), gpui_kit::px(350.)),
            delta: gpui_kit::ScrollDelta::Pixels(gpui_kit::point(
                gpui_kit::px(0.),
                gpui_kit::px(-3000.),
            )),
            touch_phase: gpui_kit::TouchPhase::Moved,
            ..Default::default()
        });
        draw(cx);
        draw(cx);
        let permission = node(cx, consent_label, Role::CheckBox);
        let start = node(cx, start_label, Role::Button);
        assert_eq!(permission.checked(), Some(true));
        for control in [permission, start] {
            assert!(control.bounds().left() >= gpui_kit::px(0.));
            assert!(control.bounds().right() <= gpui_kit::px(720.));
            assert!(control.bounds().top() >= gpui_kit::px(0.));
            assert!(control.bounds().bottom() <= gpui_kit::px(540.));
        }
    }
    settings.update(cx, |s, cx| {
        s.save(
            s.value().clone().with_language(LanguagePreference::English),
            cx,
        )
    });
    cx.simulate_window_resize(
        handle,
        gpui_kit::size(gpui_kit::px(1100.), gpui_kit::px(1500.)),
    );
    draw(cx);
    draw(cx);
    key(cx, "escape");
    click(cx, "Pair phone…", Role::Button);
    assert!(!state.read_with(cx, |s, _| s.network_authorized()));
    wait(cx, &state, |s| !s.is_busy());
    assert!(state.read_with(cx, |s, _| s.selected_interface().is_none()));
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, &state, |s| s.is_stopped());
    assert!(!state.read_with(cx, |s, _| s.network_authorized()));
    std::fs::remove_dir_all(directory).unwrap();
}

#[gpui_kit::test]
fn pairing_qr_renders_as_image_reuses_cache_and_clears_on_close(cx: &mut TestAppContext) {
    let (directory, state, settings, cx) = open(cx);
    let service = state.update(cx, |s, cx| {
        let service = NetworkService::start_loopback(
            s.receiver.clone().unwrap(),
            s.identity.clone().unwrap(),
            "127.0.0.1:0".parse().unwrap(),
        )
        .unwrap();
        s.network = Some(service.clone());
        s.observe_network(service.clone(), cx);
        s.open_pairing(cx);
        service
    });
    wait(cx, &state, |s| s.pairing_image.is_some());
    let first = state.read_with(cx, |s, _| s.pairing_image().unwrap());
    assert_eq!(first.format, ImageFormat::Svg);
    assert!(first.bytes.windows(7).any(|bytes| bytes == b"#0a0a0a"));
    assert!(first.bytes.windows(7).any(|bytes| bytes == b"#fafafa"));
    for (appearance, language, label) in [
        (
            crate::preferences::AppearancePreference::Light,
            LanguagePreference::English,
            "Pairing QR code",
        ),
        (
            crate::preferences::AppearancePreference::Dark,
            LanguagePreference::Chinese,
            "配对二维码",
        ),
    ] {
        settings.update(cx, |s, cx| {
            s.save(
                s.value()
                    .clone()
                    .with_appearance(appearance)
                    .with_language(language),
                cx,
            )
        });
        draw(cx);
        draw(cx);
        let qr = node(cx, label, Role::Image);
        assert!(qr.bounds().size.width > gpui_kit::px(0.));
        assert_eq!(qr.bounds().size.width, qr.bounds().size.height);
        cx.update(|window, cx| {
            let raster = first.clone().get_render_image(window, cx).unwrap();
            let modules = service.snapshot().unwrap().window.unwrap().qr_modules() as f32;
            assert!((29. ..=185.).contains(&modules));
            assert_eq!(raster.size(0).width.0 as f32 % modules, 0.);
            let primitives = window.painted_primitives();
            let sprite = primitives
                .iter()
                .find_map(|p| match p {
                    gpui_kit::Primitive::PolychromeSprite(sprite) if sprite.pad & 1 == 1 => {
                        Some(sprite)
                    }
                    _ => None,
                })
                .expect("QR must use nearest color image sampling");
            let pitch = sprite.bounds.size.width.0 / modules;
            assert!(pitch >= 1.);
            assert_eq!(
                pitch,
                pitch.floor(),
                "each QR module needs whole physical pixels"
            );
            assert_eq!(sprite.bounds.origin.x.0.fract(), 0.);
            assert_eq!(sprite.bounds.origin.y.0.fract(), 0.);
        });
        assert!(state.read_with(cx, |s, _| Arc::ptr_eq(&first, &s.pairing_image().unwrap())));
    }
    click(cx, "关闭配对", Role::Button);
    wait(cx, &state, |s| s.pairing_image().is_none());
    assert!(service.snapshot().unwrap().window.is_none());
    click(cx, "配对手机…", Role::Button);
    wait(cx, &state, |s| s.pairing_image().is_some());
    let next = state.read_with(cx, |s, _| s.pairing_image().unwrap());
    assert!(!Arc::ptr_eq(&first, &next));
    assert_ne!(first.id, next.id);
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, &state, |s| s.is_stopped());
    assert!(state.read_with(cx, |s, _| s.pairing_image().is_none()));
    std::fs::remove_dir_all(directory).unwrap();
}

#[gpui_kit::test]
fn locally_approved_phone_automatically_becomes_ready_but_explicit_pause_wins(
    cx: &mut TestAppContext,
) {
    let (directory, state, _, cx) = open(cx);
    let receiver = state.update(cx, |s, cx| {
        let receiver = s.receiver.clone().unwrap();
        let service = NetworkService::start_loopback(
            receiver.clone(),
            s.identity.clone().unwrap(),
            "127.0.0.1:0".parse().unwrap(),
        )
        .unwrap();
        s.network = Some(service.clone());
        s.observe_network(service, cx);
        receiver
    });
    state.update(cx, |_, cx| {
        cx.background_spawn(async move {
            receiver
                .provision_device(
                    "12345678-1234-4234-8234-123456789012".into(),
                    vec![9; 32].into(),
                    String::new(),
                )
                .await
                .unwrap();
        })
        .detach()
    });
    wait(cx, &state, |s| {
        s.snapshot().is_some_and(|s| !s.control.paused) && !s.is_busy()
    });
    assert!(state.read_with(cx, |s, _| s.snapshot().unwrap().control.target.is_some()));
    state.update(cx, |s, cx| s.pause(cx));
    wait(cx, &state, |s| {
        s.snapshot().is_some_and(|s| s.control.paused) && !s.is_busy()
    });
    state.update(cx, |s, cx| s.maybe_enable(cx));
    draw(cx);
    assert!(state.read_with(cx, |s, _| s.snapshot().unwrap().control.paused));
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, &state, |s| s.is_stopped());
    std::fs::remove_dir_all(directory).unwrap();
}
