use super::*;
use crate::{
    localization::LanguagePreference,
    preferences::{Preferences, PreferencesFile},
};
use gpui_base::test_support::snapshots;
use std::time::{Duration, Instant};
#[path = "../../test_support/history_images.rs"]
mod fixture;
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

// No native input is performed: construct three durable test receipts directly,
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
    let orders = fixture::seed(&directory);
    let portrait = include_bytes!("../../tests/fixtures/history-portrait.png").as_slice();
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
        state.read_with(cx, |s, _| s.is_ready() && s.history().entries.len() == 3)
    });
    wait(cx, |cx| {
        view.read_with(cx, |v, _| {
            v.images.len() == 3 && v.images.values().all(Option::is_some)
        })
    });
    view.read_with(cx, |v, _| {
        for (order, source) in orders.iter().zip([[1080, 2400], [2400, 1080], [64, 64]]) {
            let preview = v.images[&order.to_string()].as_ref().unwrap();
            assert_eq!(preview.pixel_size(), source);
            let [w, h] = preview.texture_size();
            assert!(u64::from(w) * u64::from(h) * 4 <= 1024 * 1024);
            if source != [64, 64] {
                assert!(
                    w.max(h) >= 640,
                    "large previews must not use the old 256px texture"
                );
            } else {
                assert_eq!(
                    [w, h],
                    source,
                    "small sources must not be upscaled during decode"
                );
            }
        }
    });
    for (mode, rem, scale, detailed) in [
        (ThemeMode::Light, 16., 1., false),
        (ThemeMode::Dark, 16., 2., false),
        (ThemeMode::Light, 20., 2., true),
        (ThemeMode::Dark, 20., 1., true),
    ] {
        cx.update(|window, cx| {
            theme::install(mode, cx);
            window.set_rem_size(gpui_kit::px(rem));
        });
        cx.simulate_scale_factor_change(scale);
        view.update(cx, |v, cx| {
            v.details = if detailed {
                orders.iter().map(i64::to_string).collect()
            } else {
                Default::default()
            };
            cx.notify();
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
                    assert_eq!(actions.len(), 3, "missing image actions: {nodes:?}");
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
                    3
                );
                for order in &orders {
                    let image = nodes
                        .iter()
                        .find(|n| n.label() == Some(&format!("View image… · {order}")))
                        .unwrap();
                    assert_eq!(
                        image.bounds().size.height,
                        gpui_kit::px(rem * 10.),
                        "preview height must not follow the source aspect ratio"
                    );
                    let card = nodes
                        .iter()
                        .find(|n| n.label() == Some(&format!("History · {order}")))
                        .unwrap();
                    let pixels_id =
                        gpui_kit::ElementId::Name(format!("history-image-pixels-{order}").into());
                    let pixels = nodes
                        .iter()
                        .find(|n| n.path().last() == Some(&pixels_id))
                        .unwrap();
                    assert!(
                        pixels.bounds().origin.y >= image.bounds().origin.y
                            && pixels.bounds().bottom() <= image.bounds().bottom(),
                        "image pixels must stay within the preview: frame={:?}, pixels={:?}",
                        image.bounds(),
                        pixels.bounds()
                    );
                    for label in ["Copy image", "Save image as…"] {
                        let action = nodes
                            .iter()
                            .find(|n| n.label() == Some(label) && n.path().starts_with(card.path()))
                            .unwrap();
                        assert!(
                            action.bounds().origin.y >= image.bounds().bottom(),
                            "image must not cover the actions: {nodes:?}"
                        );
                        assert!(
                            action.bounds().bottom() <= card.bounds().bottom(),
                            "actions must remain inside the card"
                        );
                    }
                }
            });
        }
    }
    // Return to a comfortable frame for the real menu/dialog interaction.
    cx.update(|window, cx| {
        theme::install(ThemeMode::Light, cx);
        window.set_rem_size(gpui_kit::px(16.));
    });
    draw(cx);
    let path = directory.join("export.png");
    view.update(cx, |v, cx| {
        v.image_command(orders[0], Some(path.clone()), cx)
    });
    wait(cx, |cx| view.read_with(cx, |v, _| v.image_action.is_none()));
    assert_eq!(std::fs::read(&path).unwrap(), portrait);
    assert!(view.read_with(
        cx,
        |v, _| matches!(v.image_feedback,Some((order,M::ImageSaved)) if order==orders[0])
    ));
    for order in orders.iter().rev() {
        let point = cx.update(|window, _| {
            let nodes = snapshots(window);
            let card = nodes
                .iter()
                .find(|n| n.label() == Some(&format!("History · {order}")))
                .unwrap();
            nodes
                .iter()
                .find(|n| n.label() == Some("More actions") && n.path().starts_with(card.path()))
                .unwrap()
                .bounds()
                .center()
        });
        cx.simulate_click(point, Modifiers::default());
        draw(cx);
        draw(cx);
        click(cx, "View image…", Role::MenuItem);
        wait(cx, |cx| view.read_with(cx, |v, _| v.preview.is_some()));
        cx.update(|window, _| {
            let nodes = snapshots(window);
            let frame = nodes
                .iter()
                .find(|n| n.role() == Some(Role::Image) && n.label() == Some("View image…"))
                .unwrap();
            let id = gpui_kit::ElementId::Name("history-dialog-image-pixels".into());
            let pixels = nodes.iter().find(|n| n.path().last() == Some(&id)).unwrap();
            assert_eq!(
                pixels.bounds(),
                frame.bounds(),
                "dialog image must fit its viewport too"
            );
        });
        click(cx, "Close", Role::Button);
        assert!(view.read_with(cx, |v, _| v.preview.is_none() && v.preview_order.is_none()));
    }
    state.update(cx, |s, cx| s.delete_history(orders[0], cx));
    wait(cx, |cx| {
        state.read_with(cx, |s, _| {
            s.history().entries.len() == 2 && !s.history_busy()
        })
    });
    assert_eq!(view.read_with(cx, |v, _| v.images.len()), 2);
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
        portrait,
        "a removed record must not overwrite an export"
    );
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, |cx| state.read_with(cx, |s, _| s.is_stopped()));
    std::fs::remove_dir_all(directory).unwrap();
}

#[gpui_kit::test]
fn full_image_page_bounds_cache_and_releases_closed_originals(cx: &mut TestAppContext) {
    cx.executor().allow_parking();
    let directory = std::env::temp_dir().join(format!(
        "sayin-image-page-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let orders = fixture::seed_page(&directory, 30);
    let preferences_file = PreferencesFile::in_directory(&directory);
    preferences_file
        .save(&Preferences::default().with_language(LanguagePreference::English))
        .unwrap();
    let state =
        cx.update(|cx| cx.new(|cx| ReceptionState::local_only(directory.join("receiver"), cx)));
    let settings = cx.update(|cx| {
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        cx.new(|_| PreferencesState::new(preferences_file))
    });
    let retained = std::rc::Rc::new(std::cell::RefCell::new(None));
    let capture = retained.clone();
    let owner = state.clone();
    let (_, cx) = cx.add_window_view(move |window, cx| {
        let view = cx.new(|cx| HistoryView::new(settings, owner, window, cx));
        *capture.borrow_mut() = Some(view.clone());
        gpui_kit::base::Root::new(view, window, cx)
    });
    let view = retained.borrow().clone().unwrap();
    wait(cx, |cx| {
        view.read_with(cx, |v, _| {
            v.images.len() == 30 && v.images.values().all(Option::is_some)
        })
    });
    let budget = view.read_with(cx, |v, _| {
        v.images
            .values()
            .map(|p| {
                let [w, h] = p.as_ref().unwrap().texture_size();
                let bytes = u64::from(w) * u64::from(h) * 4;
                assert!(bytes <= 1024 * 1024);
                bytes
            })
            .sum::<u64>()
    });
    assert!(budget <= 30 * 1024 * 1024);
    // Use the production dialog path. Weak references prove that closing does
    // not retain decoded originals in the view or the previous rendered frame.
    for order in orders.iter().filter(|order| (**order - 1) % 3 != 2).take(8) {
        cx.update(|window, cx| view.update(cx, |v, cx| v.view_image(*order, window, cx)));
        wait(cx, |cx| view.read_with(cx, |v, _| v.preview.is_some()));
        let original = view.read_with(cx, |v, _| {
            std::sync::Arc::downgrade(v.preview.as_ref().unwrap().image())
        });
        let image_id = original.upgrade().unwrap().id;
        assert!(
            cx.update(|window, _| window.has_image_atlas_frame(image_id, 0)),
            "the opened original must actually reach the renderer"
        );
        click(cx, "Close", Role::Button);
        wait(cx, |_| original.upgrade().is_none());
        assert!(
            !cx.update(|window, _| window.has_image_atlas_frame(image_id, 0)),
            "closed original must leave the renderer atlas as well as Rust state"
        );
        assert!(view.read_with(cx, |v, _| v.preview.is_none() && v.preview_order.is_none()));
    }
    let thumbnails = view.read_with(cx, |v, _| {
        v.images
            .values()
            .flatten()
            .map(|p| p.image().id)
            .collect::<Vec<_>>()
    });
    assert!(cx.update(|window, _| {
        thumbnails
            .iter()
            .any(|id| window.has_image_atlas_frame(*id, 0))
    }));
    state.update(cx, |s, cx| {
        s.search_history("no-matching-image-fixture".into(), cx)
    });
    wait(cx, |cx| {
        view.read_with(cx, |v, _| v.images.is_empty() && v.image_task.is_none())
    });
    assert!(
        cx.update(|window, _| thumbnails
            .iter()
            .all(|id| !window.has_image_atlas_frame(*id, 0))),
        "filtered thumbnails must also leave the renderer atlas"
    );
    state.update(cx, |s, cx| s.stop(cx));
    wait(cx, |cx| state.read_with(cx, |s, _| s.is_stopped()));
    std::fs::remove_dir_all(directory).unwrap();
}
