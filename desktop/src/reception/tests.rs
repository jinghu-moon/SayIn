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
    NetworkInterface::new(
        format!("00000000-0000-4000-8000-{id:012}").parse().unwrap(),
        format!("192.0.2.{id}").parse().unwrap(),
        format!("Test Wi-Fi {id}"),
        vec![NetworkProfile {
            id: "00000000-0000-4000-8000-999999999999".parse().unwrap(),
            category,
        }],
    )
    .with_physical(true)
}

#[test]
fn automatic_selection_prefers_physical_lan_then_remembered_path_without_category_consent() {
    let public = interface(1, NetworkCategory::Public);
    let private = interface(2, NetworkCategory::Private);
    let virtual_adapter = interface(3, NetworkCategory::Domain).with_physical(false);
    let mut selection = NetworkSelection::default();
    selection.replace(
        vec![virtual_adapter.clone(), private.clone(), public.clone()],
        Some(&virtual_adapter.key()),
    );
    assert!(selection.selected().unwrap().is_physical());
    assert!(selection.selected().unwrap().binding().is_ok());
    selection.replace(vec![public.clone(), private.clone()], Some(&private.key()));
    assert_eq!(selection.selected().unwrap().key(), private.key());
    selection.replace(vec![public.clone()], Some(&private.key()));
    assert_eq!(selection.selected().unwrap().key(), public.key());
    selection.replace(vec![], None);
    assert!(selection.selected().is_none());
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
fn public_network_does_not_add_decisions_and_details_preserve_focus_and_locale(
    cx: &mut TestAppContext,
) {
    let (directory, state, settings, cx) = open(cx);
    state.update(cx, |s, cx| {
        s.selection
            .replace(vec![interface(1, NetworkCategory::Public)], None);
        cx.notify();
    });
    draw(cx);
    draw(cx);
    cx.update(|window, _| {
        let nodes = snapshots(window);
        assert!(
            !nodes
                .iter()
                .any(|n| n.role() == Some(Role::RadioButton) || n.role() == Some(Role::CheckBox))
        );
        assert!(
            !nodes
                .iter()
                .any(|n| n.label() == Some("Allow selected Public network for this session"))
        );
    });
    click(cx, "Connection help…", Role::Button);
    click(cx, "Connection details", Role::Button);
    let selected = state.read_with(cx, |s, _| s.selected_interface().unwrap().key());
    key(cx, "escape");
    assert_eq!(
        node(cx, "Connection help…", Role::Button).focused(),
        Some(true)
    );
    settings.update(cx, |s, cx| {
        s.save(
            s.value().clone().with_language(LanguagePreference::Chinese),
            cx,
        )
    });
    draw(cx);
    draw(cx);
    click(cx, "连接帮助…", Role::Button);
    assert_eq!(
        state.read_with(cx, |s, _| s.selected_interface().unwrap().key()),
        selected
    );
    key(cx, "escape");
    assert_eq!(node(cx, "连接帮助…", Role::Button).focused(), Some(true));
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, &state, |s| s.is_stopped());
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
    click(cx, "改用配对码", Role::Button);
    assert_eq!(node(cx, "返回二维码", Role::Button).focused(), Some(true));
    cx.update(|window, _| {
        assert!(
            !snapshots(window)
                .iter()
                .any(|n| n.role() == Some(Role::Image))
        );
    });
    click(cx, "关闭配对", Role::Button);
    wait(cx, &state, |s| s.pairing_image().is_none());
    assert!(service.snapshot().unwrap().window.is_none());
    click(cx, "生成配对邀请", Role::Button);
    wait(cx, &state, |s| s.pairing_image().is_some());
    let next = state.read_with(cx, |s, _| s.pairing_image().unwrap());
    assert!(!Arc::ptr_eq(&first, &next));
    assert_ne!(first.id, next.id);
    assert!(node(cx, "配对二维码", Role::Image).visible());
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, &state, |s| s.is_stopped());
    assert!(state.read_with(cx, |s, _| s.pairing_image().is_none()));
    std::fs::remove_dir_all(directory).unwrap();
}

#[gpui_kit::test]
fn listener_recovery_restores_only_transient_pause_without_opening_pairing(
    cx: &mut TestAppContext,
) {
    let (directory, state, _, cx) = open(cx);
    let receiver = state.read_with(cx, |s, _| s.receiver.clone().unwrap());
    let provisioning = receiver.clone();
    cx.background_spawn(async move {
        provisioning
            .provision_device(
                "12345678-1234-4234-8234-123456789012".into(),
                vec![9; 32].into(),
                "Redmi K40".into(),
            )
            .await
            .unwrap();
    })
    .detach();
    wait(cx, &state, |s| {
        s.snapshot().is_some_and(|s| s.paired_devices == 1)
    });
    let bind = |s: &mut ReceptionState, cx: &mut Context<ReceptionState>| {
        let service = NetworkService::start_loopback(
            s.receiver.clone().unwrap(),
            s.identity.clone().unwrap(),
            "127.0.0.1:0".parse().unwrap(),
        )
        .unwrap();
        s.network = Some(service.clone());
        s.issue = None;
        s.observe_network(service.clone(), cx);
        service
    };
    let service = state.update(cx, bind);
    wait(cx, &state, |s| {
        s.snapshot().is_some_and(|s| !s.control.paused) && !s.is_busy()
    });
    let scope = state.read_with(cx, |s, _| s.snapshot().unwrap().control.target.clone());
    click(cx, "All history", Role::Button);
    node(cx, "Search text, application or window", Role::SearchInput);
    cx.background_spawn(async move {
        service.shutdown().await.unwrap();
    })
    .detach();
    wait(cx, &state, |s| {
        s.network.is_none()
            && s.snapshot()
                .is_some_and(|s| s.control.pause_reason.as_deref() == Some("network_unavailable"))
    });
    let service = state.update(cx, bind);
    wait(cx, &state, |s| {
        s.snapshot().is_some_and(|s| !s.control.paused) && !s.is_busy()
    });
    assert_ne!(
        scope,
        state.read_with(cx, |s, _| s.snapshot().unwrap().control.target.clone())
    );
    assert!(!state.read_with(cx, |s, _| s.pairing_requested()));
    assert!(service.snapshot().unwrap().window.is_none());
    // Automatic listener recovery must not navigate away from the user's work.
    node(cx, "Search text, application or window", Role::SearchInput);
    click(cx, "Receive", Role::Tab);
    node(cx, "Pause receiving", Role::Button);
    node(cx, "All history", Role::Button);
    state.update(cx, |s, cx| s.pause(cx));
    wait(cx, &state, |s| {
        !s.is_busy()
            && s.snapshot()
                .is_some_and(|s| s.control.pause_reason.as_deref() == Some("paused"))
    });
    cx.background_spawn(async move {
        service.shutdown().await.unwrap();
    })
    .detach();
    wait(cx, &state, |s| s.network.is_none());
    let service = state.update(cx, bind);
    wait(cx, &state, |s| {
        s.network_snapshot().is_some() && !s.is_busy()
    });
    assert!(state.read_with(cx, |s, _| s.snapshot().unwrap().control.paused));
    assert_eq!(
        state
            .read_with(cx, |s, _| s
                .snapshot()
                .unwrap()
                .control
                .pause_reason
                .clone())
            .as_deref(),
        Some("paused")
    );
    assert!(service.snapshot().unwrap().window.is_none());
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, &state, |s| s.is_stopped());
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
