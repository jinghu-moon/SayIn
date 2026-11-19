//! Native rendering with isolated storage. Default: seeded offline phone.
//! --first: unpaired real listener for onboarding screenshots, never approved.
//! Neither mode executes native input; seeded history uses fixture outcomes.
use std::{path::PathBuf, time::Duration};
use voicelink_desktop::{
    localization::LanguagePreference,
    preferences::{AppearancePreference, Preferences, PreferencesFile, PreferencesState},
    reception::ReceptionState,
    shell::Shell,
    theme,
};
use voicelink_receiver::windows_input::open_ledger;
use voicelink_transport::{HostIdentity, storage::secure_directory};
use yue_component::{
    ThemeMode,
    gpui_kit::{self, AppContext as _, Bounds, WindowBounds, WindowOptions, px, size},
};

fn main() {
    let output = PathBuf::from(std::env::args_os().nth(1).expect("output directory"));
    let first = std::env::args().any(|a| a == "--first");
    let receive_only = std::env::args().any(|a| a == "--receive-only");
    assert!(output.is_absolute());
    std::fs::create_dir_all(&output).unwrap();
    // Render with a real notification icon; the receiver remains offline.
    let tray = voicelink_desktop::tray::start(voicelink_desktop::tray::TrayLabels {
        open: "Open render fixture".into(),
        toggle: "Paused fixture".into(),
        quit: "Quit render fixture".into(),
        tooltip: "SayIn rendering verification".into(),
        can_toggle: false,
    });
    assert!(tray.is_ok(), "render fixture tray must start");
    gpui_kit::application().run(move |cx| {
        // Keep the event loop alive through asynchronous ledger shutdown and
        // fixture cleanup, even after its last preview window is removed.
        cx.set_quit_mode(gpui_kit::QuitMode::Explicit);
        yue_component::init(cx, theme::yue(ThemeMode::Light)).unwrap();
        cx.spawn(async move |cx| {
            let directory = output.join(format!("state-{}", std::process::id()));
            let preparing = directory.clone();
            cx.background_spawn(async move {
                let receiver = preparing.join("receiver");
                secure_directory(&receiver).unwrap();
                let mut ledger = open_ledger(&receiver).unwrap();
                HostIdentity::open(&receiver, 0).unwrap();
                if first {
                    return;
                }
                ledger
                    .provision_device(
                        "12345678-1234-4234-8234-123456789012",
                        &[9; 32],
                        "Redmi K40".into(),
                    )
                    .unwrap();
                use voicelink_receiver::{
                    history::TargetApplication,
                    ledger::InputOutcome,
                    protocol::{Operation, Submit, TextSource},
                };
                let session = ledger
                    .authenticate("12345678-1234-4234-8234-123456789012", &[9; 32])
                    .unwrap();
                let target = "22345678-1234-4234-8234-123456789012";
                let state = ledger.bind_target(target).unwrap();
                ledger.resume(&state.state_rev, false).unwrap();
                for (index, text, _program, title) in [
                    (
                        1,
                        "这是一条来自手机的完整记录。每条原文和执行目标都保存在本机。",
                        "Code.exe",
                        "SayIn · Visual Studio Code",
                    ),
                    (
                        2,
                        "Please send the revised design tomorrow. 中文与 English 原文保持不变。",
                        "notepad.exe",
                        "项目记录 — Notepad",
                    ),
                    (
                        3,
                        "今天先完成桌面端的接收界面，再核对设备与历史记录。",
                        "msedge.exe",
                        "产品讨论 — Microsoft Edge",
                    ),
                ] {
                    let message = Submit::new(
                        session.id().into(),
                        format!("32345678-1234-4234-8234-{index:012}"),
                        target.into(),
                        Operation::Text {
                            text: text.into(),
                            src: TextSource::Ime,
                        },
                    );
                    ledger.submit(&session, &message).unwrap();
                    let job = ledger.prepare_next().unwrap().unwrap();
                    ledger
                        .mark_executing_at(
                            &job,
                            Some(TargetApplication::new(
                                std::env::var("SAYIN_VERIFY_TARGET_EXE").unwrap_or_else(|_| {
                                    std::env::var("WINDIR").unwrap() + r"\explorer.exe"
                                }),
                                title.into(),
                                100 + index,
                                1000,
                            )),
                        )
                        .unwrap();
                    ledger
                        .finish(
                            &job,
                            if index == 2 {
                                InputOutcome::Failed {
                                    reason: "fixture_focus_changed",
                                }
                            } else {
                                InputOutcome::Injected { method: "fixture" }
                            },
                        )
                        .unwrap();
                }
                PreferencesFile::in_directory(&preparing)
                    .save(
                        &Preferences::default()
                            .with_language(LanguagePreference::Chinese)
                            .with_appearance(AppearancePreference::Light),
                    )
                    .unwrap();
            })
            .await;
            let (handle, shell, preferences, reception) = cx.update(|cx| {
                let preferences =
                    cx.new(|_| PreferencesState::new(PreferencesFile::in_directory(&directory)));
                let reception = cx.new(|cx| {
                    if first {
                        ReceptionState::new(directory.join("receiver"), Default::default(), cx)
                    } else {
                        ReceptionState::local_only(directory.join("receiver"), cx)
                    }
                });
                reception.update(cx, |s, cx| s.set_background_available(true, cx));
                let a = preferences.clone();
                let b = reception.clone();
                let (handle, shell) = gpui_kit::open_window(
                    WindowOptions {
                        window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                            None,
                            size(px(960.), px(680.)),
                            cx,
                        ))),
                        titlebar: None,
                        ..Default::default()
                    },
                    cx,
                    |w, cx| cx.new(|cx| Shell::new(a, b, w, cx)),
                )
                .unwrap();
                (handle, shell, preferences, reception)
            });
            for _ in 0..100 {
                if cx.update(|cx| reception.read(cx).is_ready()) {
                    break;
                }
                cx.background_executor()
                    .timer(Duration::from_millis(50))
                    .await;
            }
            assert!(cx.update(|cx| reception.read(cx).is_ready()));
            if first {
                for _ in 0..200 {
                    if cx.update(|cx| {
                        reception
                            .read(cx)
                            .network_snapshot()
                            .is_some_and(|s| s.window.is_some())
                    }) {
                        break;
                    }
                    cx.background_executor()
                        .timer(Duration::from_millis(50))
                        .await;
                }
                assert!(
                    cx.update(|cx| reception
                        .read(cx)
                        .network_snapshot()
                        .is_some_and(|s| s.window.is_some())),
                    "onboarding needs a usable LAN; fixture never grants trust"
                );
            }
            for (language, lang) in [
                (LanguagePreference::Chinese, "zh"),
                (LanguagePreference::English, "en"),
            ] {
                for (appearance, mode) in [
                    (AppearancePreference::Light, "light"),
                    (AppearancePreference::Dark, "dark"),
                ] {
                    cx.update(|cx| {
                        preferences.update(cx, |s, cx| {
                            s.save(
                                s.value()
                                    .clone()
                                    .with_language(language)
                                    .with_appearance(appearance),
                                cx,
                            )
                        })
                    });
                    for (width, height) in
                        [(1000., 660.), (720., 540.), (1200., 900.), (1600., 1000.)]
                    {
                        cx.update_window(handle, |_, w, _| w.resize(size(px(width), px(height))))
                            .unwrap();
                        cx.background_executor()
                            .timer(Duration::from_millis(500))
                            .await;
                        cx.update_window(handle, |_, w, cx| {
                            w.draw(cx).clear(cx);
                            w.render_to_image()
                                .unwrap()
                                .save(output.join(format!(
                                    "{lang}-{mode}-{}x{}.png",
                                    width as u32, height as u32
                                )))
                                .unwrap();
                        })
                        .unwrap();
                        for (page, label) in [
                            ("devices", if lang == "zh" { "设备" } else { "Devices" }),
                            ("settings", if lang == "zh" { "设置" } else { "Settings" }),
                            (
                                "history",
                                if lang == "zh" {
                                    "历史记录"
                                } else {
                                    "History"
                                },
                            ),
                            ("connection", if lang == "zh" { "接收" } else { "Receive" }),
                        ] {
                            if receive_only && page != "connection" {
                                continue;
                            }
                            cx.update_window(handle, |_, w, cx| select_tab(w, cx, label))
                                .unwrap();
                            cx.background_executor()
                                .timer(Duration::from_millis(250))
                                .await;
                            cx.update_window(handle, |_, w, cx| {
                                w.draw(cx).clear(cx);
                                w.render_to_image()
                                    .unwrap()
                                    .save(output.join(format!(
                                        "{lang}-{mode}-{page}-{}x{}.png",
                                        width as u32, height as u32
                                    )))
                                    .unwrap();
                            })
                            .unwrap();
                            if first && page == "connection" && width >= 1200. {
                                cx.update_window(handle, |_, w, cx| {
                                    activate_control(
                                        w,
                                        cx,
                                        if lang == "zh" {
                                            "改用配对码"
                                        } else {
                                            "Use a pairing code"
                                        },
                                        gpui_kit::Role::Button,
                                    )
                                })
                                .unwrap();
                                cx.background_executor()
                                    .timer(Duration::from_millis(250))
                                    .await;
                                cx.update_window(handle, |_, w, cx| {
                                    w.draw(cx).clear(cx);
                                    w.render_to_image()
                                        .unwrap()
                                        .save(output.join(format!(
                                            "{lang}-{mode}-code-{}x{}.png",
                                            width as u32, height as u32
                                        )))
                                        .unwrap();
                                    activate_control(
                                        w,
                                        cx,
                                        if lang == "zh" {
                                            "返回二维码"
                                        } else {
                                            "Back to QR code"
                                        },
                                        gpui_kit::Role::Button,
                                    );
                                })
                                .unwrap();
                                cx.background_executor()
                                    .timer(Duration::from_millis(250))
                                    .await;
                            }
                            if page == "settings" {
                                cx.update_window(handle, |_, w, cx| {
                                    activate_control(
                                        w,
                                        cx,
                                        if lang == "zh" {
                                            "界面语言"
                                        } else {
                                            "Language"
                                        },
                                        gpui_kit::Role::ComboBox,
                                    );
                                })
                                .unwrap();
                                cx.background_executor()
                                    .timer(Duration::from_millis(250))
                                    .await;
                                cx.update_window(handle, |_, w, cx| {
                                    w.draw(cx).clear(cx);
                                    w.render_to_image()
                                        .unwrap()
                                        .save(output.join(format!(
                                            "{lang}-{mode}-settings-select-{}x{}.png",
                                            width as u32, height as u32
                                        )))
                                        .unwrap();
                                    w.dispatch_event(
                                        gpui_kit::PlatformInput::KeyDown(gpui_kit::KeyDownEvent {
                                            keystroke: gpui_kit::Keystroke::parse("escape")
                                                .unwrap(),
                                            is_held: false,
                                            prefer_character_input: false,
                                        }),
                                        cx,
                                    );
                                    w.dispatch_event(
                                        gpui_kit::PlatformInput::KeyUp(gpui_kit::KeyUpEvent {
                                            keystroke: gpui_kit::Keystroke::parse("escape")
                                                .unwrap(),
                                        }),
                                        cx,
                                    );
                                })
                                .unwrap();
                                cx.background_executor()
                                    .timer(Duration::from_millis(250))
                                    .await;
                            }
                        }
                        if !receive_only {
                            cx.update_window(handle, |_, w, cx| {
                                shell.update(cx, |s, cx| assert!(!s.request_close(w, cx)));
                            })
                            .unwrap();
                            cx.background_executor()
                                .timer(Duration::from_millis(500))
                                .await;
                            cx.update_window(handle, |_, w, cx| {
                                w.draw(cx).clear(cx);
                                w.render_to_image()
                                    .unwrap()
                                    .save(output.join(format!(
                                        "{lang}-{mode}-close-{}x{}.png",
                                        width as u32, height as u32
                                    )))
                                    .unwrap();
                                activate_control(
                                    w,
                                    cx,
                                    if lang == "zh" { "取消" } else { "Cancel" },
                                    gpui_kit::Role::Button,
                                );
                            })
                            .unwrap();
                        }
                    }
                }
            }
            if !first {
                cx.update(|cx| reception.update(cx, |s, cx| s.resume(cx)));
                for _ in 0..100 {
                    if cx.update(|cx| {
                        reception
                            .read(cx)
                            .snapshot()
                            .is_some_and(|s| !s.control.paused)
                    }) {
                        break;
                    }
                    cx.background_executor()
                        .timer(Duration::from_millis(50))
                        .await;
                }
                assert!(cx.update(|cx| {
                    reception
                        .read(cx)
                        .snapshot()
                        .is_some_and(|s| !s.control.paused)
                }));
                cx.update_window(handle, |_, w, _| w.resize(size(px(960.), px(680.))))
                    .unwrap();
                cx.background_executor()
                    .timer(Duration::from_millis(500))
                    .await;
                cx.update_window(handle, |_, w, cx| {
                    w.draw(cx).clear(cx);
                    w.render_to_image()
                        .unwrap()
                        .save(output.join("en-dark-enabled.png"))
                        .unwrap();
                })
                .unwrap();
            }
            cx.update(|cx| reception.update(cx, |s, cx| s.stop(cx)));
            for _ in 0..100 {
                if cx.update(|cx| {
                    reception.read(cx).is_stopped() && !preferences.read(cx).is_saving()
                }) {
                    break;
                }
                cx.background_executor()
                    .timer(Duration::from_millis(50))
                    .await;
            }
            assert!(cx.update(|cx| reception.read(cx).is_stopped()));
            cx.update_window(handle, |_, w, _| w.remove_window())
                .unwrap();
            cx.background_executor()
                .timer(Duration::from_millis(100))
                .await;
            std::fs::remove_dir_all(directory).unwrap();
            cx.update(|cx| cx.defer(|cx| cx.quit()));
        })
        .detach();
    });
}

fn select_tab(window: &mut gpui_kit::Window, cx: &mut gpui_kit::App, label: &str) {
    activate_control(window, cx, label, gpui_kit::Role::Tab);
}
fn activate_control(
    window: &mut gpui_kit::Window,
    cx: &mut gpui_kit::App,
    label: &str,
    role: gpui_kit::Role,
) {
    use gpui_kit::{
        Modifiers, MouseButton, MouseDownEvent, MouseMoveEvent, MouseUpEvent, PlatformInput,
    };
    window.draw(cx).clear(cx);
    let nodes = gpui_base::test_support::snapshots(window);
    let tab = nodes
        .iter()
        .find(|n| n.role() == Some(role) && n.label() == Some(label))
        .unwrap_or_else(|| panic!("missing {label}"));
    let position = tab.bounds().center();
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
            modifiers: Modifiers::default(),
            click_count: 1,
            first_mouse: false,
        }),
        cx,
    );
    window.dispatch_event(
        PlatformInput::MouseUp(MouseUpEvent {
            button: MouseButton::Left,
            position,
            modifiers: Modifiers::default(),
            click_count: 1,
        }),
        cx,
    );
    window.dispatch_event(
        PlatformInput::MouseMove(MouseMoveEvent {
            position: gpui_kit::point(px(1.), px(1.)),
            ..Default::default()
        }),
        cx,
    );
}
