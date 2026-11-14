#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]

use std::path::PathBuf;
use voicelink_desktop::{
    localization::Message as M,
    preferences::{PreferencesFile, PreferencesState},
    reception::ReceptionState,
    shell::{Shell, ShellEvent},
    theme,
    tray::{self, TrayEvent, TrayHandle, TrayLabels},
};
use yue_component::{
    ThemeMode,
    gpui_kit::{self, AppContext as _, Bounds, Global, WindowBounds, WindowOptions, px, size},
};

struct AppServices {
    preferences: gpui_kit::Entity<PreferencesState>,
    reception: gpui_kit::Entity<ReceptionState>,
    tray: Option<TrayHandle>,
    quitting: bool,
}
impl Global for AppServices {}

fn finish_exit(cx: &mut gpui_kit::App) {
    if !cx.global::<AppServices>().quitting {
        return;
    }
    let services = cx.global::<AppServices>();
    if services.reception.read(cx).is_stopped() && !services.preferences.read(cx).is_saving() {
        // Destroy native windows while the GPUI app still owns their callbacks.
        // Quitting with live HWNDs can deliver focus callbacks during App drop.
        for handle in cx.windows() {
            let _ = handle.update(cx, |_, window, _| window.remove_window());
        }
        cx.defer(|cx| cx.quit());
    }
}

fn tray_labels(preferences: &PreferencesState, reception: Option<&ReceptionState>) -> TrayLabels {
    let paused = reception
        .and_then(ReceptionState::snapshot)
        .is_none_or(|s| s.control.paused);
    TrayLabels {
        open: preferences.text(M::OpenWindow),
        toggle: preferences.text(if paused {
            M::ResumeInput
        } else {
            M::PauseInput
        }),
        quit: preferences.text(M::QuitApp),
        tooltip: format!(
            "{} · {}",
            preferences.text(M::AppName),
            preferences.text(if paused {
                M::ReceiverPaused
            } else {
                M::ReceiverRunning
            })
        ),
        can_toggle: reception.is_some_and(|s| {
            s.is_ready()
                && !s.is_busy()
                && s.snapshot()
                    .is_some_and(|s| s.control.active_device_id.is_some())
        }),
    }
}
fn sync_tray(cx: &mut gpui_kit::App) {
    let services = cx.global::<AppServices>();
    if let Some(tray) = &services.tray {
        tray.update(tray_labels(
            services.preferences.read(cx),
            Some(services.reception.read(cx)),
        ));
    }
    finish_exit(cx);
}
fn request_quit(cx: &mut gpui_kit::App) {
    cx.global_mut::<AppServices>().quitting = true;
    let receiver = cx.global::<AppServices>().reception.clone();
    receiver.update(cx, |s, cx| s.stop(cx));
    finish_exit(cx);
}
fn show_main_window(window: &gpui_kit::Window) {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    use windows::Win32::{Foundation::HWND, UI::WindowsAndMessaging::*};
    if let Ok(handle) = HasWindowHandle::window_handle(window)
        && let RawWindowHandle::Win32(raw) = handle.as_raw()
    {
        let hwnd = HWND(raw.hwnd.get() as _);
        voicelink_desktop::branding::set_window_icon(hwnd);
        // Only the explicitly opened main window is shown. Activation alone
        // does not reveal a hidden, non-minimized HWND on the Windows backend.
        unsafe {
            if IsIconic(hwnd).as_bool() {
                let _ = ShowWindow(hwnd, SW_RESTORE);
            }
            if !IsWindowVisible(hwnd).as_bool()
                && let Err(error) = SetWindowPos(
                    hwnd,
                    None,
                    0,
                    0,
                    0,
                    0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW,
                )
            {
                eprintln!("Show main window: {error}");
            }
        }
    }
    window.activate_window();
}
fn open_main(cx: &mut gpui_kit::App) {
    if cx.global::<AppServices>().quitting {
        return;
    }
    if let Some(window) = cx.windows().first().copied() {
        let _ = window.update(cx, |_, window, _| show_main_window(window));
        cx.activate(true);
        return;
    }
    let result = gpui_kit::open_window(
        WindowOptions {
            window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                None,
                size(px(1000.), px(660.)),
                cx,
            ))),
            window_min_size: Some(size(px(720.), px(540.))),
            app_id: Some("seeyuer.voice.link".into()),
            ..Default::default()
        },
        cx,
        |window, cx| {
            let services = cx.global::<AppServices>();
            let preferences = services.preferences.clone();
            let reception = services.reception.clone();
            cx.new(|cx| Shell::new(preferences, reception, window, cx))
        },
    );
    match result {
        Err(error) => {
            eprintln!("Open window: {error}");
            request_quit(cx);
        }
        Ok((handle, shell)) => {
            cx.subscribe(&shell, |_, event, cx| match event {
                ShellEvent::QuitRequested => request_quit(cx),
            })
            .detach();
            let _ = handle.update(cx, |_, window, _| show_main_window(window));
            cx.activate(true);
        }
    }
}

fn dispatch_tray_event(event: TrayEvent, cx: &mut gpui_kit::App) {
    match event {
        TrayEvent::Open => open_main(cx),
        TrayEvent::Quit => request_quit(cx),
        TrayEvent::Toggle => {
            let receiver = cx.global::<AppServices>().reception.clone();
            let paused = receiver
                .read(cx)
                .snapshot()
                .is_none_or(|s| s.control.paused);
            let review = receiver.read(cx).snapshot().is_some_and(|s| {
                s.control.pause_reason.as_deref() == Some("outcome_review_required")
            });
            if paused && review {
                open_main(cx);
            } else {
                receiver.update(cx, |s, cx| if paused { s.resume(cx) } else { s.pause(cx) });
            }
        }
        TrayEvent::Unavailable => {
            cx.global_mut::<AppServices>().tray = None;
            let receiver = cx.global::<AppServices>().reception.clone();
            receiver.update(cx, |s, cx| s.set_background_available(false, cx));
            open_main(cx);
        }
    }
}

fn data_directory() -> Result<PathBuf, String> {
    let mut args = std::env::args_os().skip(1);
    let directory = if let Some(argument) = args.next() {
        if argument != "--data-dir" {
            return Err("Usage: sayin [--data-dir <absolute-directory>]".into());
        }
        let path = args
            .next()
            .map(PathBuf::from)
            .ok_or("--data-dir requires an absolute directory")?;
        if args.next().is_some() || !path.is_absolute() {
            return Err("--data-dir requires one absolute directory".into());
        }
        path
    } else {
        // This installed storage identity predates SayIn branding. Keep it so
        // a product-name update retains existing pairing keys and history.
        directories::ProjectDirs::from("", "seeyuer", "VoiceLink")
            .ok_or("Windows application data directory is unavailable")?
            .config_dir()
            .to_owned()
    };
    Ok(directory)
}

fn main() {
    let directory = match data_directory() {
        Ok(directory) => directory,
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(2);
        }
    };
    let state = PreferencesState::new(PreferencesFile::in_directory(&directory));
    let (tray, events, _tray_thread) = match tray::start(tray_labels(&state, None)) {
        Ok((handle, events, thread)) => (Some(handle), Some(events), Some(thread)),
        Err(error) => {
            eprintln!("Tray unavailable: {error}");
            (None, None, None)
        }
    };
    gpui_kit::application().run(move |cx| initialize(directory, state, tray, events, cx));
}
fn initialize(
    directory: PathBuf,
    state: PreferencesState,
    tray: Option<TrayHandle>,
    events: Option<std::sync::mpsc::Receiver<TrayEvent>>,
    cx: &mut gpui_kit::App,
) {
    cx.set_quit_mode(gpui_kit::QuitMode::Explicit);
    yue_component::init(cx, theme::yue(ThemeMode::Light)).expect("initialize Yue");
    theme::install(ThemeMode::Light, cx);
    let preferences = cx.new(|_| state);
    let reception = cx.new(|cx| ReceptionState::new(directory.join("receiver"), cx));
    reception.update(cx, |s, cx| s.set_background_available(tray.is_some(), cx));
    cx.set_global(AppServices {
        preferences: preferences.clone(),
        reception: reception.clone(),
        tray,
        quitting: false,
    });
    cx.on_window_closed(move |cx, _| {
        if cx.windows().is_empty() && cx.global::<AppServices>().tray.is_none() {
            request_quit(cx);
        }
    })
    .detach();
    cx.observe(&preferences, |_, cx| sync_tray(cx)).detach();
    cx.observe(&reception, |_, cx| sync_tray(cx)).detach();
    if let Some(events) = events {
        cx.spawn(async move |cx| {
            loop {
                cx.background_executor()
                    .timer(std::time::Duration::from_millis(100))
                    .await;
                if cx.update(|cx| cx.global::<AppServices>().quitting) {
                    break;
                }
                for event in events.try_iter().take(8) {
                    cx.update(|cx| dispatch_tray_event(event, cx));
                }
            }
        })
        .detach();
    }
    open_main(cx);
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};
    // Both tests create the same process-local native tray window class.
    static NATIVE_LIFECYCLE: std::sync::Mutex<()> = std::sync::Mutex::new(());

    #[test]
    fn native_tray_open_shows_hidden_and_restores_minimized_main_window() {
        let _lifecycle = NATIVE_LIFECYCLE.lock().unwrap();
        use raw_window_handle::{HasWindowHandle as _, RawWindowHandle};
        use std::sync::{Arc, Mutex, mpsc};
        use windows::Win32::{
            Foundation::{HWND, WPARAM},
            UI::WindowsAndMessaging::*,
        };
        let directory =
            std::env::temp_dir().join(format!("voicelink-native-open-{}", std::process::id()));
        std::fs::create_dir(&directory).unwrap();
        let cleanup = directory.clone();
        let observed = Arc::new(Mutex::new(Vec::new()));
        let assertions = observed.clone();
        let settings = PreferencesState::new(PreferencesFile::in_directory(&directory));
        let (tray, _, guard) = tray::start(tray_labels(&settings, None)).unwrap();
        let (commands, events) = mpsc::sync_channel(8);
        gpui_kit::application().run(move |cx| {
            initialize(directory, settings, Some(tray), Some(events), cx);
            cx.spawn(async move |cx| {
                cx.background_executor().timer(Duration::from_millis(250)).await;
                for stage in ["hidden", "minimized", "closed"] {
                    let before = cx.update(|cx| cx.windows().first().copied());
                    if let Some(handle) = before {
                        let hwnd = cx.update_window(handle, |_, w, cx| {
                            let RawWindowHandle::Win32(raw) = w.window_handle().unwrap().as_raw() else { unreachable!() };
                            let hwnd = HWND(raw.hwnd.get() as _);
                            for kind in [ICON_SMALL, ICON_BIG] {
                                assert_ne!(unsafe { SendMessageW(hwnd, WM_GETICON, Some(WPARAM(kind as usize)), None) }.0, 0,
                                    "SayIn must install its embedded icon on every main window");
                            }
                            if stage == "closed" { w.remove_window(); }
                            else {
                                // Only this disposable fixture's own GPUI window.
                                let _ = unsafe { ShowWindowAsync(hwnd, if stage == "hidden" { SW_HIDE } else { SW_MINIMIZE }) };
                            }
                            cx.refresh_windows();
                            hwnd.0 as isize
                        }).unwrap();
                        cx.background_executor().timer(Duration::from_millis(200)).await;
                        let was_hidden = !unsafe { IsWindowVisible(HWND(hwnd as _)).as_bool() }
                            || unsafe { IsIconic(HWND(hwnd as _)).as_bool() };
                        commands.send(TrayEvent::Open).unwrap();
                        cx.background_executor().timer(Duration::from_millis(350)).await;
                        let visible = cx.update(|cx| {
                            cx.windows().len() == 1 && cx.windows()[0].update(cx, |_, w, _| {
                                let RawWindowHandle::Win32(raw) = w.window_handle().unwrap().as_raw() else { unreachable!() };
                                let hwnd = HWND(raw.hwnd.get() as _);
                                unsafe { IsWindowVisible(hwnd).as_bool() && !IsIconic(hwnd).as_bool() }
                            }).unwrap()
                        });
                        observed.lock().unwrap().push((stage, was_hidden, visible));
                        eprintln!("Native tray verification: {stage}: hidden_before={was_hidden}, visible_after={visible}");
                    }
                }
                cx.update(request_quit);
            }).detach();
        });
        drop(guard);
        std::fs::remove_dir_all(cleanup).unwrap();
        assert_eq!(
            *assertions.lock().unwrap(),
            [
                ("hidden", true, true),
                ("minimized", true, true),
                ("closed", true, true)
            ]
        );
    }
    fn wait(cx: &mut gpui_kit::TestAppContext, predicate: impl Fn(&ReceptionState) -> bool) {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            cx.executor().advance_clock(Duration::from_millis(20));
            cx.run_until_parked();
            if cx.update(|cx| predicate(cx.global::<AppServices>().reception.read(cx))) {
                break;
            }
            assert!(Instant::now() < deadline, "application lifecycle timed out");
            std::thread::sleep(Duration::from_millis(1));
        }
    }
    #[gpui_kit::test]
    fn closing_window_keeps_receiver_and_tray_reopens_same_owner(
        cx: &mut gpui_kit::TestAppContext,
    ) {
        let _lifecycle = NATIVE_LIFECYCLE.lock().unwrap();
        cx.executor().allow_parking();
        let directory = std::env::temp_dir().join(format!(
            "voicelink-lifecycle-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let settings = PreferencesState::new(PreferencesFile::in_directory(&directory));
        let (tray, events, guard) = tray::start(tray_labels(&settings, None)).unwrap();
        cx.update(|cx| initialize(directory.clone(), settings, Some(tray), Some(events), cx));
        wait(cx, ReceptionState::is_ready);
        let (window, owner) = cx.update(|cx| {
            (
                cx.windows()[0],
                cx.global::<AppServices>().reception.entity_id(),
            )
        });
        cx.update(|cx| {
            window
                .update(cx, |_, window, _| window.remove_window())
                .unwrap()
        });
        cx.run_until_parked();
        assert!(cx.update(|cx| cx.windows().is_empty()));
        assert!(cx.update(|cx| !cx.global::<AppServices>().quitting
            && cx.global::<AppServices>().reception.read(cx).is_ready()));
        assert!(
            voicelink_receiver::windows_input::open_ledger(&directory.join("receiver")).is_err(),
            "the background owner must retain the ledger namespace lock"
        );
        cx.update(|cx| dispatch_tray_event(TrayEvent::Open, cx));
        cx.run_until_parked();
        assert_eq!(cx.update(|cx| cx.windows().len()), 1);
        assert_eq!(
            cx.update(|cx| cx.global::<AppServices>().reception.entity_id()),
            owner
        );
        cx.update(|cx| dispatch_tray_event(TrayEvent::Quit, cx));
        wait(cx, ReceptionState::is_stopped);
        assert!(cx.update(|cx| cx.global::<AppServices>().quitting));
        drop(guard);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
