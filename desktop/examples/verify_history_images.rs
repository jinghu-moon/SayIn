//! Native D3D snapshots of the production history, with synthetic images only.
//! No listener, pairing, clipboard write or input; requires a fresh output dir.
#[path = "../test_support/history_images.rs"]
mod fixture;

use std::{path::PathBuf, time::Duration};
use voicelink_desktop::{
    history_view::HistoryView,
    localization::LanguagePreference,
    preferences::{Preferences, PreferencesFile, PreferencesState},
    reception::ReceptionState,
    theme,
};
use yue_component::{
    ThemeMode,
    gpui_kit::{
        self, AppContext as _, Bounds, Context, Entity, IntoElement, ParentElement as _, Render,
        Styled as _, Window, WindowBounds, WindowOptions, div, px, size,
    },
};

struct Preview {
    history: Entity<HistoryView>,
    rem: f32,
}
impl Render for Preview {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        window.set_rem_size(px(self.rem));
        let theme = yue_component::current_theme(cx);
        div()
            .size_full()
            .bg(gpui_kit::rgb(theme.page()))
            .text_color(gpui_kit::rgb(theme.text_primary()))
            .font_family(yue_component::typography::ui_font(theme).family)
            .child(self.history.clone())
    }
}
fn main() {
    let soak = std::env::args().any(|arg| arg == "--soak");
    let output = PathBuf::from(
        std::env::args_os()
            .nth(1)
            .expect("fresh output dir required"),
    );
    assert!(output.is_absolute() && !output.exists());
    std::fs::create_dir_all(&output).unwrap();
    let orders = if soak {
        fixture::seed_page(&output, 30)
    } else {
        fixture::seed(&output)
    };
    let preferences_file = PreferencesFile::in_directory(&output);
    preferences_file
        .save(&Preferences::default().with_language(LanguagePreference::English))
        .unwrap();
    gpui_kit::application().run(move |cx| {
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        let state = cx.new(|cx| ReceptionState::local_only(output.join("receiver"), cx));
        let settings = cx.new(|_| PreferencesState::new(preferences_file));
        let owner = state.clone();
        let (handle, preview) = gpui_kit::open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                    None,
                    size(px(1540.), px(1000.)),
                    cx,
                ))),
                titlebar: None,
                ..Default::default()
            },
            cx,
            |window, cx| {
                let history = cx.new(|cx| HistoryView::new(settings, owner, window, cx));
                cx.new(|_| Preview { history, rem: 16. })
            },
        )
        .unwrap();
        cx.spawn(async move |cx| {
            // The bounded production loader does the actual DPAPI/decode work.
            // Snapshot registration happens only after successful image load.
            let mut loaded = false;
            for _ in 0..600 {
                loaded = cx
                    .update_window(handle, |_, window, cx| {
                        window.draw(cx).clear(cx);
                        gpui_base::test_support::snapshots(window)
                            .iter()
                            .filter(|n| n.role() == Some(gpui_kit::Role::Image))
                            .count()
                            == if soak { 30 } else { 3 }
                    })
                    .unwrap();
                if loaded {
                    break;
                }
                cx.background_executor()
                    .timer(Duration::from_millis(50))
                    .await;
            }
            assert!(loaded, "production history did not load the full page");
            if soak {
                let started = std::time::Instant::now();
                let mut rounds = Vec::new();
                // Thirty independent protected resources, 20 large previews.
                // Exercise one of each orientation repeatedly via real menu
                // and dialog events inside this example's own native window.
                for iteration in 0..60 {
                    let order = orders[if iteration % 2 == 0 { 27 } else { 28 }];
                    cx.update_window(handle, |_, window, cx| {
                        window.draw(cx).clear(cx);
                        let nodes = gpui_base::test_support::snapshots(window);
                        let card = nodes.iter().find(|n| n.label() == Some(&format!("History · {order}"))).unwrap();
                        let position = nodes.iter().find(|n| n.label() == Some("More actions") && n.path().starts_with(card.path())).unwrap().bounds().center();
                        click_at(window, cx, position);
                    }).unwrap();
                    cx.background_executor().timer(Duration::from_millis(60)).await;
                    cx.update_window(handle, |_, window, cx| activate(window, cx, "View image…", gpui_kit::Role::MenuItem)).unwrap();
                    let mut ready = false;
                    for _ in 0..200 {
                        ready = cx.update_window(handle, |_, window, cx| {
                            window.draw(cx).clear(cx);
                            let id = gpui_kit::ElementId::Name("history-dialog-image-pixels".into());
                            gpui_base::test_support::snapshots(window).iter().any(|n| n.path().last() == Some(&id))
                        }).unwrap();
                        if ready { break; }
                        cx.background_executor().timer(Duration::from_millis(20)).await;
                    }
                    assert!(ready, "original did not load in round {iteration}");
                    if iteration == 0 {
                        cx.update_window(handle, |_, window, _| window.render_to_image().unwrap().save(output.join("soak-dialog.png")).unwrap()).unwrap();
                    }
                    std::fs::write(output.join("phase.json"), serde_json::json!({"iteration":iteration+1,"phase":"open"}).to_string()).unwrap();
                    cx.background_executor().timer(Duration::from_millis(1500)).await;
                    cx.update_window(handle, |_, window, cx| activate(window, cx, "Close", gpui_kit::Role::Button)).unwrap();
                    cx.background_executor().timer(Duration::from_millis(100)).await;
                    cx.update_window(handle, |_, window, cx| {
                        window.draw(cx).clear(cx);
                        let id = gpui_kit::ElementId::Name("history-dialog-image-pixels".into());
                        assert!(!gpui_base::test_support::snapshots(window).iter().any(|n| n.path().last() == Some(&id)));
                    }).unwrap();
                    std::fs::write(output.join("phase.json"), serde_json::json!({"iteration":iteration+1,"phase":"closed"}).to_string()).unwrap();
                    cx.background_executor().timer(Duration::from_millis(1500)).await;
                    rounds.push(serde_json::json!({"iteration":iteration+1,"order":order,"elapsed_seconds":started.elapsed().as_secs_f64()}));
                }
                std::fs::write(output.join("phase.json"), serde_json::json!({"iteration":60,"phase":"after-soak"}).to_string()).unwrap();
                std::fs::write(output.join("soak-result.json"), serde_json::to_vec_pretty(&serde_json::json!({
                    "scope":"Native D3D production HistoryView; synthetic durable receipts only, no input/listener/clipboard; no VRAM measurement",
                    "records":30,"large_sources":[[1080,2400],[2400,1080]],"small_sources":[64,64],"rounds":rounds
                })).unwrap()).unwrap();
            }
            for (mode, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
                for (width, rem) in [(560., 16.), (1000., 16.), (1540., 16.), (1000., 20.)] {
                    cx.update_window(handle, |_, window, cx| {
                        theme::install(mode, cx);
                        preview.update(cx, |view, cx| {
                            view.rem = rem;
                            cx.notify();
                        });
                        window.resize(size(px(width), px(1000.)));
                        window.refresh();
                    })
                    .unwrap();
                    cx.background_executor()
                        .timer(Duration::from_millis(500))
                        .await;
                    cx.update_window(handle, |_, window, cx| {
                        window.draw(cx).clear(cx);
                        window
                            .render_to_image()
                            .unwrap()
                            .save(
                                output.join(format!("{name}-{}-{}.png", width as u32, rem as u32)),
                            )
                            .unwrap();
                        println!(
                            "native history width={width} rem={rem} scale={}",
                            window.scale_factor()
                        );
                    })
                    .unwrap();
                }
            }
            state.update(cx, |s, cx| s.stop(cx));
            for _ in 0..100 {
                if state.read_with(cx, |s, _| s.is_stopped()) {
                    break;
                }
                cx.background_executor()
                    .timer(Duration::from_millis(20))
                    .await;
            }
            assert!(state.read_with(cx, |s, _| s.is_stopped()));
            cx.update_window(handle, |_, window, _| window.remove_window())
                .unwrap();
            cx.background_executor()
                .timer(Duration::from_millis(100))
                .await;
            cx.update(|cx| cx.defer(|cx| cx.quit()));
        })
        .detach();
    });
}

fn activate(window: &mut Window, cx: &mut gpui_kit::App, label: &str, role: gpui_kit::Role) {
    window.draw(cx).clear(cx);
    let nodes = gpui_base::test_support::snapshots(window);
    let position = nodes
        .iter()
        .find(|n| n.label() == Some(label) && n.role() == Some(role))
        .unwrap_or_else(|| panic!("missing {label}"))
        .bounds()
        .center();
    click_at(window, cx, position);
}

fn click_at(
    window: &mut Window,
    cx: &mut gpui_kit::App,
    position: gpui_kit::Point<gpui_kit::Pixels>,
) {
    use gpui_kit::{MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent, PlatformInput};
    window.dispatch_event(
        PlatformInput::MouseMove(MouseMoveEvent {
            position,
            ..Default::default()
        }),
        cx,
    );
    window.dispatch_event(
        PlatformInput::MouseDown(MouseDownEvent {
            button: MouseButton::Left,
            position,
            click_count: 1,
            ..Default::default()
        }),
        cx,
    );
    window.dispatch_event(
        PlatformInput::MouseUp(MouseUpEvent {
            button: MouseButton::Left,
            position,
            click_count: 1,
            ..Default::default()
        }),
        cx,
    );
}
