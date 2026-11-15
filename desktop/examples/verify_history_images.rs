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
    let output = PathBuf::from(
        std::env::args_os()
            .nth(1)
            .expect("fresh output dir required"),
    );
    assert!(output.is_absolute() && !output.exists());
    std::fs::create_dir_all(&output).unwrap();
    fixture::seed(&output);
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
            for _ in 0..100 {
                loaded = cx
                    .update_window(handle, |_, window, cx| {
                        window.draw(cx).clear(cx);
                        gpui_base::test_support::snapshots(window)
                            .iter()
                            .filter(|n| n.role() == Some(gpui_kit::Role::Image))
                            .count()
                            == 3
                    })
                    .unwrap();
                if loaded {
                    break;
                }
                cx.background_executor()
                    .timer(Duration::from_millis(50))
                    .await;
            }
            assert!(loaded, "production history did not load all three previews");
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
