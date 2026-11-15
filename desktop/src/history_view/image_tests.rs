use super::*;
use crate::{
    localization::LanguagePreference,
    preferences::{Preferences, PreferencesFile},
};
use gpui_base::test_support::snapshots;
use std::{
    sync::Arc,
    time::{Duration, Instant},
};
use voicelink_receiver::{
    images::{
        ImageAuthorization, ImageMetadata, ImageMime, digest, storage::ImageStore,
        wire::UploadScope,
    },
    ledger::InputOutcome,
    protocol::Submit,
    windows_input::{Dpapi, open_ledger},
};
use voicelink_transport::HostIdentity;
use yue_component::{
    ThemeMode,
    gpui_kit::{Modifiers, Role, TestAppContext, VisualTestContext},
};

fn draw(cx: &mut VisualTestContext) {
    cx.run_until_parked();
    cx.update(|window, cx| {
        window.refresh();
        window.draw(cx).clear(cx);
    });
    cx.run_until_parked();
}
fn wait(cx: &mut VisualTestContext, mut predicate: impl FnMut(&mut VisualTestContext) -> bool) {
    let deadline = Instant::now() + Duration::from_secs(8);
    while !predicate(cx) {
        assert!(
            Instant::now() < deadline,
            "history image operation timed out"
        );
        cx.executor().advance_clock(Duration::from_millis(10));
        draw(cx);
        std::thread::sleep(Duration::from_millis(1));
    }
    draw(cx);
}
fn click(cx: &mut VisualTestContext, label: &str, role: Role) {
    let point = cx.update(|window, _| {
        snapshots(window)
            .iter()
            .find(|n| n.label() == Some(label) && n.role() == Some(role))
            .unwrap_or_else(|| panic!("missing {label}: {:?}", snapshots(window)))
            .bounds()
            .center()
    });
    cx.simulate_click(point, Modifiers::default());
    draw(cx);
    draw(cx);
}

// No native input is performed: construct two durable test receipts directly,
// then reopen the normal local-only service to exercise the production history.
#[gpui_kit::test]
fn image_history_previews_save_deletion_and_responsive_actions(cx: &mut TestAppContext) {
    cx.executor().allow_parking();
    let directory = std::env::temp_dir().join(format!(
        "sayin-image-ui-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let data = directory.join("receiver");
    std::fs::create_dir_all(&data).unwrap();
    let mut ledger = open_ledger(&data).unwrap();
    let identity = HostIdentity::open(&data, 0).unwrap();
    let store = ImageStore::open(&data.join("images"), identity.host_id()).unwrap();
    ledger.attach_images(store.clone()).unwrap();
    let device = "12345678-1234-4234-8234-123456789abc";
    ledger
        .provision_device(device, &[7; 32], "Isolated image UI phone".into())
        .unwrap();
    let session = ledger.authenticate(device, &[7; 32]).unwrap();
    let state = ledger.bind_target(identity.host_id()).unwrap();
    ledger.resume(&state.state_rev, false).unwrap();
    let bytes = include_bytes!("../../assets/sayin-64.png");
    let mut orders = Vec::new();
    for completed in [true, false] {
        let resource = store
            .prepare(
                device,
                ImageMetadata {
                    bytes: bytes.len() as u32,
                    mime: ImageMime::Png,
                    width: 64,
                    height: 64,
                    sha256: digest(bytes),
                },
                bytes,
                &Dpapi,
            )
            .unwrap();
        let image = resource.image().clone();
        ledger
            .register_image(
                &session,
                UploadScope {
                    device: device.into(),
                    session: session.id().into(),
                    target: identity.host_id().into(),
                    revision: ledger.state().unwrap().state_rev,
                },
                resource,
                Arc::new(ImageAuthorization::new(Arc::new(
                    std::sync::atomic::AtomicBool::new(true),
                ))),
            )
            .unwrap();
        // Test receipts only; finish never calls the Windows adapter.
        let id = if completed {
            "23456789-1234-4234-8234-123456789abc"
        } else {
            "34567890-1234-4234-8234-123456789abc"
        };
        let receipt = ledger
            .submit(
                &session,
                &Submit::new(
                    session.id().into(),
                    id.into(),
                    identity.host_id().into(),
                    Operation::Image { image },
                ),
            )
            .unwrap();
        orders.push(receipt.order.parse::<i64>().unwrap());
        if completed {
            let job = ledger.prepare_next().unwrap().unwrap();
            assert!(ledger.mark_executing(&job).unwrap());
            ledger
                .finish(
                    &job,
                    InputOutcome::Injected {
                        method: "clipboard_image",
                    },
                )
                .unwrap();
        }
    }
    drop(ledger);
    drop(store);
    drop(identity);
    let preferences_file = PreferencesFile::in_directory(&directory);
    preferences_file
        .save(&Preferences::default().with_language(LanguagePreference::English))
        .unwrap();
    let state = cx.update(|cx| cx.new(|cx| ReceptionState::local_only(data, cx)));
    let settings = cx.update(|cx| {
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        cx.new(|_| PreferencesState::new(preferences_file))
    });
    let owner = state.clone();
    let preferences = settings.clone();
    let retained = std::rc::Rc::new(std::cell::RefCell::new(None));
    let capture = retained.clone();
    let (_, cx) = cx.add_window_view(move |window, cx| {
        let view = cx.new(|cx| HistoryView::new(preferences, owner, window, cx));
        *capture.borrow_mut() = Some(view.clone());
        gpui_kit::base::Root::new(view, window, cx)
    });
    let view = retained.borrow().clone().unwrap();
    wait(cx, |cx| {
        state.read_with(cx, |s, _| s.is_ready() && s.history().entries.len() == 2)
    });
    wait(cx, |cx| {
        view.read_with(cx, |v, _| {
            v.images.len() == 2 && v.images.values().all(Option::is_some)
        })
    });
    for width in [560., 1000., 1540.] {
        let handle = cx.update(|window, _| window.window_handle());
        cx.simulate_window_resize(
            handle,
            gpui_kit::size(gpui_kit::px(width), gpui_kit::px(1600.)),
        );
        draw(cx);
        draw(cx);
        cx.update(|window, _| {
            let nodes = snapshots(window);
            for label in ["Copy image", "Save image as…"] {
                let actions = nodes
                    .iter()
                    .filter(|n| n.role() == Some(Role::Button) && n.label() == Some(label))
                    .collect::<Vec<_>>();
                assert_eq!(actions.len(), 2, "missing image actions: {nodes:?}");
                for action in actions {
                    assert!(action.bounds().origin.x >= gpui_kit::px(0.));
                    assert!(action.bounds().right() <= gpui_kit::px(width));
                }
            }
            assert_eq!(
                nodes
                    .iter()
                    .filter(|n| n.role() == Some(Role::Image))
                    .count(),
                2
            );
        });
    }
    let path = directory.join("export.png");
    view.update(cx, |v, cx| {
        v.image_command(orders[0], Some(path.clone()), cx)
    });
    wait(cx, |cx| view.read_with(cx, |v, _| v.image_action.is_none()));
    assert_eq!(std::fs::read(&path).unwrap(), bytes);
    assert!(view.read_with(
        cx,
        |v, _| matches!(v.image_feedback,Some((order,M::ImageSaved)) if order==orders[0])
    ));
    click(cx, "More actions", Role::Button);
    click(cx, "View image…", Role::MenuItem);
    wait(cx, |cx| view.read_with(cx, |v, _| v.preview.is_some()));
    click(cx, "Close", Role::Button);
    assert!(view.read_with(cx, |v, _| v.preview.is_none() && v.preview_order.is_none()));
    state.update(cx, |s, cx| s.delete_history(orders[0], cx));
    wait(cx, |cx| {
        state.read_with(cx, |s, _| {
            s.history().entries.len() == 1 && !s.history_busy()
        })
    });
    assert_eq!(view.read_with(cx, |v, _| v.images.len()), 1);
    view.update(cx, |v, cx| {
        v.image_command(orders[0], Some(path.clone()), cx)
    });
    wait(cx, |cx| view.read_with(cx, |v, _| v.image_action.is_none()));
    assert!(view.read_with(cx, |v, _| matches!(
        v.image_feedback,
        Some((_, M::ImageActionFailed))
    )));
    assert_eq!(
        std::fs::read(path).unwrap(),
        bytes,
        "a removed record must not overwrite an export"
    );
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, |cx| state.read_with(cx, |s, _| s.is_stopped()));
    std::fs::remove_dir_all(directory).unwrap();
}
