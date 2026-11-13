//! Native D3D readback of the production QR element, for scanner regression checks.
//! Loopback only; no phone approval, no input, disposable identity and invitation.
use std::{path::PathBuf, sync::Arc, time::Duration};
use voicelink_desktop::{shell::pairing_qr, theme};
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    windows_input::open_ledger,
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::{HostIdentity, server::NetworkService, storage::secure_directory};
use yue_component::{
    ThemeMode, current_theme,
    gpui_kit::{
        self, AppContext as _, Bounds, Context, Image, ImageFormat, IntoElement,
        ParentElement as _, Render, Styled as _, Window, WindowBounds, WindowOptions, div, px,
        size,
    },
};

struct NoInput;
impl InputAdapter for NoInput {
    fn preflight(&mut self, _: &Execution) -> Result<(), &'static str> {
        Err("DIAGNOSTIC_ONLY")
    }
    fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
        unreachable!()
    }
}
struct Preview {
    image: Arc<Image>,
    modules: u32,
    rem: f32,
}
impl Render for Preview {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // Exercise different effective pixel pitches without changing OS settings.
        window.set_rem_size(px(self.rem));
        div()
            .size_full()
            .flex()
            .items_center()
            .justify_center()
            .bg(gpui_kit::rgb(current_theme(cx).page()))
            .child(pairing_qr(
                self.image.clone(),
                self.modules,
                "Pairing QR code".into(),
            ))
    }
}
fn main() {
    let output = PathBuf::from(
        std::env::args_os()
            .nth(1)
            .expect("output directory required"),
    );
    assert!(output.is_absolute());
    std::fs::create_dir_all(&output).unwrap();
    gpui_kit::application().run(move |cx| {
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        cx.spawn(async move |cx| {
            let directory = output.join("receiver");
            let preparing = directory.clone();
            let (receiver, service, image, modules, expected) = cx
                .background_spawn(async move {
                    secure_directory(&preparing).unwrap();
                    let receiver =
                        Receiver::start(open_ledger(&preparing).unwrap(), NoInput).unwrap();
                    let identity = HostIdentity::open(&preparing, 0).unwrap();
                    let service = NetworkService::start_loopback(
                        receiver.clone(),
                        identity,
                        "127.0.0.1:0".parse().unwrap(),
                    )
                    .unwrap();
                    service.open_pairing().await.unwrap();
                    let snapshot = service.snapshot().unwrap();
                    let window = snapshot.window.unwrap();
                    let invitation = voicelink_pairing::pair_code(
                        snapshot.address.ip(),
                        snapshot.address.port(),
                        &window.code,
                    )
                    .unwrap();
                    let expected = invitation.qr().unwrap();
                    let image =
                        Arc::new(Image::from_bytes(ImageFormat::Svg, window.qr_svg.to_vec()));
                    (receiver, service, image, window.qr_modules(), expected)
                })
                .await;
            std::fs::write(output.join("expected-qr.txt"), expected).unwrap();
            let (handle, preview) = cx
                .update(|cx| {
                    gpui_kit::open_window(
                        WindowOptions {
                            window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                                None,
                                size(px(400.), px(400.)),
                                cx,
                            ))),
                            titlebar: None,
                            ..Default::default()
                        },
                        cx,
                        |_, cx| {
                            cx.new(|_| Preview {
                                image,
                                modules,
                                rem: 16.,
                            })
                        },
                    )
                })
                .unwrap();
            cx.update_window(handle, |_, window, _| {
                println!(
                    "Native QR: {modules} modules/side; scale={}",
                    window.scale_factor()
                );
            })
            .unwrap();
            for (mode, name) in [(ThemeMode::Light, "light"), (ThemeMode::Dark, "dark")] {
                for rem in [12., 16., 20.] {
                    cx.update_window(handle, |_, window, cx| {
                        theme::install(mode, cx);
                        preview.update(cx, |preview, cx| {
                            preview.rem = rem;
                            cx.notify();
                        });
                        window.refresh();
                    })
                    .unwrap();
                    // Wait for the normal asynchronous image asset loader and native frame.
                    cx.background_executor()
                        .timer(Duration::from_millis(800))
                        .await;
                    cx.update_window(handle, |_, window, cx| {
                        window.draw(cx).clear(cx);
                        window
                            .render_to_image()
                            .unwrap()
                            .save(output.join(format!("{name}-{}.png", rem as u32)))
                            .unwrap();
                    })
                    .unwrap();
                }
            }
            service.close_pairing().await;
            service.shutdown().await.unwrap();
            receiver.shutdown().await.unwrap();
            drop(service);
            std::fs::remove_dir_all(directory).unwrap();
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
