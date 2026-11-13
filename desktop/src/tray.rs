//! Windows notification area. The native menu has its own message-loop thread;
//! only bounded, typed commands cross into the GPUI application owner.
use std::sync::{Arc, Mutex, mpsc};
use windows::{
    Win32::{
        Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM},
        System::{
            LibraryLoader::GetModuleHandleW,
            Registry::{HKEY_CURRENT_USER, RRF_RT_REG_DWORD, RegGetValueW},
        },
        UI::{Shell::*, WindowsAndMessaging::*},
    },
    core::{PCWSTR, w},
};

const CALLBACK: u32 = WM_APP + 27;
const UPDATE: u32 = WM_APP + 28;
const KEY_SELECT: u32 = NIN_SELECT | NINF_KEY;

fn taskbar_is_light() -> bool {
    let mut light = 0u32;
    let mut size = std::mem::size_of_val(&light) as u32;
    // SAFETY: Read one DWORD from the current user's system taskbar theme.
    // A missing value defaults to the dark-taskbar artwork. No setting is changed.
    unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            w!("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
            w!("SystemUsesLightTheme"),
            RRF_RT_REG_DWORD,
            None,
            Some((&mut light as *mut u32).cast()),
            Some(&mut size),
        )
        .is_ok()
            && light != 0
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrayEvent {
    Open,
    Toggle,
    Quit,
    Unavailable,
}

#[derive(Clone)]
pub struct TrayLabels {
    pub open: String,
    pub toggle: String,
    pub quit: String,
    pub tooltip: String,
    pub can_toggle: bool,
}

#[derive(Clone)]
pub struct TrayHandle {
    window: isize,
    labels: Arc<Mutex<TrayLabels>>,
}
impl TrayHandle {
    pub fn update(&self, labels: TrayLabels) {
        if let Ok(mut value) = self.labels.lock() {
            *value = labels;
        }
        // SAFETY: the HWND is owned by the tray thread. PostMessage does not
        // dereference caller data; after teardown an invalid HWND just fails.
        let _ = unsafe { PostMessageW(Some(HWND(self.window as _)), UPDATE, WPARAM(0), LPARAM(0)) };
    }
}

pub struct TrayThread {
    handle: TrayHandle,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl Drop for TrayThread {
    fn drop(&mut self) {
        let _ = unsafe {
            PostMessageW(
                Some(HWND(self.handle.window as _)),
                WM_CLOSE,
                WPARAM(0),
                LPARAM(0),
            )
        };
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

struct NativeTray {
    labels: Arc<Mutex<TrayLabels>>,
    events: mpsc::SyncSender<TrayEvent>,
    icons: [HICON; 2],
    taskbar_created: u32,
}
impl NativeTray {
    fn notify(&self, window: HWND, message: NOTIFY_ICON_MESSAGE) -> bool {
        let mut data = NOTIFYICONDATAW {
            cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: window,
            uID: 1,
            uFlags: NIF_ICON | NIF_MESSAGE | NIF_TIP | NIF_SHOWTIP,
            uCallbackMessage: CALLBACK,
            hIcon: self.icons[usize::from(taskbar_is_light())],
            ..Default::default()
        };
        if let Ok(labels) = self.labels.lock() {
            for (to, from) in data
                .szTip
                .iter_mut()
                .take(127)
                .zip(labels.tooltip.encode_utf16())
            {
                *to = from;
            }
        }
        unsafe {
            if !Shell_NotifyIconW(message, &data).as_bool() {
                return false;
            }
            if message == NIM_ADD {
                data.Anonymous.uVersion = NOTIFYICON_VERSION_4;
                if !Shell_NotifyIconW(NIM_SETVERSION, &data).as_bool() {
                    let _ = Shell_NotifyIconW(NIM_DELETE, &data);
                    return false;
                }
            }
            true
        }
    }
    fn menu(&self, window: HWND) {
        let labels = match self.labels.lock() {
            Ok(v) => v.clone(),
            Err(_) => return,
        };
        // SAFETY: all menu/window handles remain on this thread; strings are
        // null-terminated and live through each synchronous Win32 call.
        unsafe {
            let Ok(menu) = CreatePopupMenu() else { return };
            let wide = |s: &str| s.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
            let open = wide(&labels.open);
            let toggle = wide(&labels.toggle);
            let quit = wide(&labels.quit);
            let _ = AppendMenuW(menu, MF_STRING, 1, PCWSTR(open.as_ptr()));
            let _ = AppendMenuW(
                menu,
                if labels.can_toggle {
                    MF_STRING
                } else {
                    MF_STRING | MF_GRAYED
                },
                2,
                PCWSTR(toggle.as_ptr()),
            );
            let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
            let _ = AppendMenuW(menu, MF_STRING, 3, PCWSTR(quit.as_ptr()));
            let mut point = POINT::default();
            let _ = GetCursorPos(&mut point);
            let _ = SetForegroundWindow(window);
            let command = TrackPopupMenu(
                menu,
                TPM_RETURNCMD | TPM_RIGHTBUTTON,
                point.x,
                point.y,
                None,
                window,
                None,
            )
            .0;
            let _ = PostMessageW(Some(window), WM_NULL, WPARAM(0), LPARAM(0));
            let _ = DestroyMenu(menu);
            let event = match command {
                1 => TrayEvent::Open,
                2 => TrayEvent::Toggle,
                3 => TrayEvent::Quit,
                _ => return,
            };
            let _ = self.events.try_send(event);
        }
    }
}

unsafe extern "system" fn window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    // SAFETY: pointer is installed only after Box allocation, used exclusively
    // on the owning thread, and outlives the entire HWND/message-loop lifetime.
    unsafe {
        let ptr = GetWindowLongPtrW(window, GWLP_USERDATA) as *const NativeTray;
        if let Some(tray) = ptr.as_ref() {
            if message == tray.taskbar_created {
                if !tray.notify(window, NIM_ADD) {
                    let _ = tray.events.try_send(TrayEvent::Unavailable);
                }
                return LRESULT(0);
            }
            match message {
                CALLBACK => {
                    match (lparam.0 as u32) & 0xffff {
                        NIN_SELECT | KEY_SELECT => {
                            let _ = tray.events.try_send(TrayEvent::Open);
                        }
                        WM_CONTEXTMENU => tray.menu(window),
                        _ => {}
                    }
                    return LRESULT(0);
                }
                UPDATE | WM_SETTINGCHANGE | WM_THEMECHANGED => {
                    tray.notify(window, NIM_MODIFY);
                    return LRESULT(0);
                }
                WM_CLOSE => {
                    let _ = DestroyWindow(window);
                    return LRESULT(0);
                }
                WM_DESTROY => {
                    tray.notify(window, NIM_DELETE);
                    PostQuitMessage(0);
                    return LRESULT(0);
                }
                _ => {}
            }
        }
        DefWindowProcW(window, message, wparam, lparam)
    }
}

pub fn start(
    labels: TrayLabels,
) -> Result<(TrayHandle, mpsc::Receiver<TrayEvent>, TrayThread), String> {
    let labels = Arc::new(Mutex::new(labels));
    let state = labels.clone();
    let (events, receiver) = mpsc::sync_channel(8);
    let (ready, waiting) = mpsc::sync_channel(1);
    let thread = std::thread::Builder::new()
        .name("voicelink-tray".into())
        .spawn(move || {
            let outcome = (|| -> windows::core::Result<()> {
                unsafe {
                    let instance = GetModuleHandleW(None)?;
                    let class = WNDCLASSW {
                        lpfnWndProc: Some(window_proc),
                        hInstance: instance.into(),
                        lpszClassName: w!("SayIn.NotificationArea"),
                        ..Default::default()
                    };
                    if RegisterClassW(&class) == 0 {
                        return Err(windows::core::Error::from_thread());
                    }
                    let load = |id: usize| -> windows::core::Result<HICON> {
                        LoadImageW(
                            Some(instance.into()),
                            PCWSTR(id as _),
                            IMAGE_ICON,
                            GetSystemMetrics(SM_CXSMICON),
                            GetSystemMetrics(SM_CYSMICON),
                            LR_SHARED,
                        )
                        .map(|handle| HICON(handle.0))
                    };
                    let icons = [load(102)?, load(103)?];
                    let window = CreateWindowExW(
                        WINDOW_EX_STYLE::default(),
                        class.lpszClassName,
                        w!("SayIn Tray"),
                        WINDOW_STYLE::default(),
                        0,
                        0,
                        0,
                        0,
                        None,
                        None,
                        Some(instance.into()),
                        None,
                    )?;
                    let tray = Box::new(NativeTray {
                        labels: state,
                        events: events.clone(),
                        icons,
                        taskbar_created: RegisterWindowMessageW(w!("TaskbarCreated")),
                    });
                    SetWindowLongPtrW(
                        window,
                        GWLP_USERDATA,
                        (&*tray as *const NativeTray) as isize,
                    );
                    if !tray.notify(window, NIM_ADD) {
                        let _ = DestroyWindow(window);
                        return Err(windows::core::Error::from_thread());
                    }
                    let _ = ready.send(Ok(window.0 as isize));
                    let mut message = MSG::default();
                    loop {
                        let value = GetMessageW(&mut message, None, 0, 0).0;
                        if value <= 0 {
                            break;
                        }
                        let _ = TranslateMessage(&message);
                        DispatchMessageW(&message);
                    }
                    SetWindowLongPtrW(window, GWLP_USERDATA, 0);
                    tray.notify(window, NIM_DELETE);
                    let _ = DestroyWindow(window);
                    let _ = UnregisterClassW(class.lpszClassName, Some(instance.into()));
                }
                Ok(())
            })();
            if let Err(error) = outcome {
                let _ = ready.send(Err(error.to_string()));
            }
            let _ = events.try_send(TrayEvent::Unavailable);
        })
        .map_err(|e| e.to_string())?;
    // This is called before GPUI starts, never from render or an event handler.
    let window = waiting.recv().map_err(|e| e.to_string())??;
    let handle = TrayHandle { window, labels };
    let guard = TrayThread {
        handle: handle.clone(),
        thread: Some(thread),
    };
    Ok((handle, receiver, guard))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_tray_keyboard_open_recovery_and_cleanup() {
        let labels = TrayLabels {
            open: "Open fixture".into(),
            toggle: "Pause fixture".into(),
            quit: "Quit fixture".into(),
            tooltip: "SayIn test fixture".into(),
            can_toggle: true,
        };
        let (handle, events, guard) = start(labels.clone()).expect("native notification area");
        handle.update(labels);
        let window = HWND(handle.window as _);
        unsafe {
            PostMessageW(Some(window), WM_THEMECHANGED, WPARAM(0), LPARAM(0)).unwrap();
            // Version-4 notifications pack the icon ID into the high word.
            PostMessageW(
                Some(window),
                CALLBACK,
                WPARAM(0),
                LPARAM(((1 << 16) | KEY_SELECT) as isize),
            )
            .unwrap();
        }
        assert_eq!(
            events
                .recv_timeout(std::time::Duration::from_secs(2))
                .unwrap(),
            TrayEvent::Open
        );
        unsafe {
            let data = NOTIFYICONDATAW {
                cbSize: std::mem::size_of::<NOTIFYICONDATAW>() as u32,
                hWnd: window,
                uID: 1,
                ..Default::default()
            };
            assert!(Shell_NotifyIconW(NIM_DELETE, &data).as_bool());
            let restart = RegisterWindowMessageW(w!("TaskbarCreated"));
            assert_ne!(restart, 0);
            PostMessageW(Some(window), restart, WPARAM(0), LPARAM(0)).unwrap();
            PostMessageW(
                Some(window),
                CALLBACK,
                WPARAM(0),
                LPARAM(((1 << 16) | NIN_SELECT) as isize),
            )
            .unwrap();
        }
        assert_eq!(
            events
                .recv_timeout(std::time::Duration::from_secs(2))
                .unwrap(),
            TrayEvent::Open
        );
        drop(guard);
        assert_eq!(
            events
                .recv_timeout(std::time::Duration::from_secs(2))
                .unwrap(),
            TrayEvent::Unavailable
        );
        assert!(
            !unsafe { IsWindow(Some(window)).as_bool() },
            "tray callback window must be destroyed"
        );
    }
}
