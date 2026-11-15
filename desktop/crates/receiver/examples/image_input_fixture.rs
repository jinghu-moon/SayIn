//! Opt-in local image experiment. No production protocol, pairing or user files.
//! Publishes synthetic images to the real clipboard and pastes ONLY into its own RichEdit.
use voicelink_receiver::images::payload;
use voicelink_receiver::windows_clipboard as clipboard;

use image::{DynamicImage, ImageBuffer, ImageFormat, Rgba};
use std::{
    io::Cursor,
    time::{Duration, Instant},
};
use windows::{
    Win32::{
        Foundation::{HWND, LPARAM, WPARAM},
        System::{
            DataExchange::{
                CloseClipboard, GetClipboardData, OpenClipboard, RegisterClipboardFormatW,
            },
            LibraryLoader::LoadLibraryW,
            Memory::{GlobalLock, GlobalSize, GlobalUnlock},
            Ole::{CF_DIBV5, OleInitialize, OleUninitialize},
        },
        UI::{
            Controls::{
                EM_SETSEL,
                RichEdit::{EDITSTREAM, EM_STREAMOUT, SF_RTF},
            },
            Input::KeyboardAndMouse::{
                GetAsyncKeyState, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
                SendInput, SetFocus, VIRTUAL_KEY, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
            },
            WindowsAndMessaging::*,
        },
    },
    core::w,
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

unsafe extern "system" fn stream(
    cookie: usize,
    buffer: *mut u8,
    length: i32,
    written: *mut i32,
) -> u32 {
    unsafe {
        let output = &mut *(cookie as *mut Vec<u8>);
        if length < 0 || output.len() + length as usize > 1024 * 1024 {
            return 1;
        }
        output.extend_from_slice(std::slice::from_raw_parts(buffer, length as usize));
        *written = length;
    }
    0
}
fn rtf(target: HWND) -> Result<String> {
    let mut output = Vec::<u8>::new();
    let mut stream = EDITSTREAM {
        dwCookie: (&mut output as *mut Vec<u8>) as usize,
        dwError: 0,
        pfnCallback: Some(stream),
    };
    unsafe {
        SendMessageW(
            target,
            EM_STREAMOUT,
            Some(WPARAM(SF_RTF as usize)),
            Some(LPARAM((&mut stream as *mut EDITSTREAM) as isize)),
        );
    }
    if stream.dwError != 0 {
        return Err("RichEdit RTF export failed".into());
    }
    Ok(String::from_utf8(output)?)
}

fn pump(duration: Duration) {
    let deadline = Instant::now() + duration;
    while Instant::now() < deadline {
        unsafe {
            let mut message = MSG::default();
            while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        std::thread::sleep(Duration::from_millis(5));
    }
}

fn allowed(target: HWND) -> bool {
    unsafe {
        let mut pid = 0;
        GetWindowThreadProcessId(target, Some(&mut pid));
        GetForegroundWindow() == GetAncestor(target, GA_ROOT)
            && pid == std::process::id()
            && [VK_CONTROL, VK_MENU, VK_SHIFT, VK_LWIN, VK_RWIN]
                .iter()
                .all(|key| GetAsyncKeyState(i32::from(key.0)) as u16 & 0x8000 == 0)
    }
}

fn key(code: u16, up: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: VIRTUAL_KEY(code),
                dwFlags: if up {
                    KEYEVENTF_KEYUP
                } else {
                    Default::default()
                },
                ..Default::default()
            },
        },
    }
}

fn read_format(format: u32) -> Result<Vec<u8>> {
    unsafe {
        OpenClipboard(None)?;
        let result = (|| -> Result<Vec<u8>> {
            let handle = GetClipboardData(format)?;
            let handle = windows::Win32::Foundation::HGLOBAL(handle.0);
            let size = GlobalSize(handle);
            if size == 0 || size > 65 * 1024 * 1024 {
                return Err("unexpected clipboard allocation".into());
            }
            let memory = GlobalLock(handle);
            if memory.is_null() {
                return Err("clipboard lock".into());
            }
            let bytes = std::slice::from_raw_parts(memory.cast::<u8>(), size).to_vec();
            let _ = GlobalUnlock(handle);
            Ok(bytes)
        })();
        CloseClipboard()?;
        result
    }
}

fn check_prefix(format: u32, bytes: &[u8]) -> Result<()> {
    let actual = read_format(format)?;
    // GlobalAlloc can round upward; the decoder/header defines actual bytes.
    if actual.get(..bytes.len()) != Some(bytes) {
        return Err("clipboard bytes differ".into());
    }
    Ok(())
}

fn run(target: HWND, owner: HWND) -> Result<()> {
    let png = unsafe { RegisterClipboardFormatW(w!("PNG")) };
    let mut images = Vec::new();
    let pixels = ImageBuffer::from_fn(32, 24, |x, y| {
        Rgba([
            x as u8 * 7,
            y as u8 * 10,
            100u8,
            if x < 8 {
                0
            } else if x < 16 {
                128
            } else {
                255
            },
        ])
    });
    for format in [ImageFormat::Png, ImageFormat::Jpeg] {
        let mut buffer = Cursor::new(Vec::new());
        let image = DynamicImage::ImageRgba8(pixels.clone());
        let image = if format == ImageFormat::Jpeg {
            DynamicImage::ImageRgb8(image.to_rgb8())
        } else {
            image
        };
        image.write_to(&mut buffer, format)?;
        images.push((
            format,
            payload::prepare(buffer.get_ref()).map_err(std::io::Error::other)?,
        ));
    }
    // A canceled/paused authorization must not change the clipboard at all.
    let rejected = clipboard::publish(owner, &images[0].1, || false)
        .err()
        .ok_or("guard unexpectedly allowed")?;
    assert!(!rejected.clipboard_changed);
    assert_eq!(rejected.reason, "guard");
    // A different thread owns the real clipboard throughout both attempts.
    // The first must time out; cancellation of the second must stop its retry.
    let (ready_tx, ready_rx) = std::sync::mpsc::channel();
    let (stop_tx, stop_rx) = std::sync::mpsc::channel();
    let blocker = std::thread::spawn(move || -> std::result::Result<(), String> {
        unsafe {
            OpenClipboard(None).map_err(|e| e.to_string())?;
        }
        ready_tx.send(()).map_err(|e| e.to_string())?;
        let _ = stop_rx.recv_timeout(Duration::from_secs(3));
        unsafe {
            CloseClipboard().map_err(|e| e.to_string())?;
        }
        Ok(())
    });
    ready_rx.recv_timeout(Duration::from_secs(2))?;
    let busy_started = Instant::now();
    let busy = clipboard::publish(owner, &images[0].1, || allowed(target)).err();
    let tries = std::cell::Cell::new(0);
    let canceled = clipboard::publish(owner, &images[0].1, || {
        tries.set(tries.get() + 1);
        tries.get() < 3
    })
    .err();
    let _ = stop_tx.send(());
    blocker
        .join()
        .map_err(|_| "clipboard blocker panicked")?
        .map_err(std::io::Error::other)?;
    let busy = busy.ok_or("clipboard lock unexpectedly bypassed")?;
    assert_eq!(busy.reason, "clipboard_busy");
    assert!(!busy.clipboard_changed);
    assert!(busy_started.elapsed() < Duration::from_secs(1));
    let canceled = canceled.ok_or("cancellation did not stop retry")?;
    assert_eq!(canceled.reason, "guard");
    assert!(!canceled.clipboard_changed);
    for (index, (format, image)) in images.iter().enumerate() {
        let published =
            clipboard::publish(owner, image, || allowed(target)).map_err(|failure| {
                format!(
                    "clipboard_changed={} reason={}",
                    failure.clipboard_changed, failure.reason
                )
            })?;
        check_prefix(png, &image.png)?;
        check_prefix(CF_DIBV5.0 as u32, &image.dibv5)?;
        for name in [
            w!("CanIncludeInClipboardHistory"),
            w!("CanUploadToCloudClipboard"),
        ] {
            let id = unsafe { RegisterClipboardFormatW(name) };
            check_prefix(id, &0u32.to_le_bytes())?;
        }
        if !published.unchanged() || !allowed(target) {
            return Err("focus/clipboard changed before paste".into());
        }
        let events = [
            key(VK_CONTROL.0, false),
            key(0x56, false),
            key(0x56, true),
            key(VK_CONTROL.0, true),
        ];
        let inserted = unsafe { SendInput(&events, std::mem::size_of::<INPUT>() as i32) };
        if inserted != 4 {
            if inserted > 0 {
                let release = [key(0x56, true), key(VK_CONTROL.0, true)];
                unsafe {
                    SendInput(&release, std::mem::size_of::<INPUT>() as i32);
                }
            }
            return Err(format!("uncertain partial paste input count={inserted}").into());
        }
        pump(Duration::from_millis(1000));
        let rtf = rtf(target)?;
        let count = rtf.matches("\\pict").count();
        if count != index + 1 || unsafe { GetWindowTextLengthW(target) } != index as i32 + 1 {
            return Err(format!("RichEdit did not insert exactly one image without extra text; picture_count={count}").into());
        }
        println!(
            "PASS {:?}: {}x{}, clipboard PNG/DIBV5 byte equality, history/cloud exclusion markers, one real Ctrl+V, RichEdit RTF picture_count={count}",
            format, image.width, image.height
        );
    }
    // Replacement after publication invalidates the sequence check; never paste again.
    let published = clipboard::publish(owner, &images[0].1, || allowed(target))
        .map_err(|e| format!("{e:?}"))?;
    let replacement = clipboard::publish(owner, &images[1].1, || allowed(target))
        .map_err(|e| format!("{e:?}"))?;
    assert!(!published.unchanged());
    assert!(replacement.unchanged());
    assert_eq!(rtf(target)?.matches("\\pict").count(), 2);
    println!(
        "PASS cancellation, bounded clipboard contention and content replacement never submit paste; production identities untouched"
    );
    Ok(())
}

fn main() -> Result<()> {
    if std::env::args().nth(1).as_deref() != Some("--run") {
        return Err("Opt-in only: --run uses the system clipboard and focuses a disposable RichEdit window. No user application receives input.".into());
    }
    // SAFETY: OLE and all windows are created, pumped and destroyed on this thread.
    unsafe {
        OleInitialize(None)?;
        let _library = LoadLibraryW(w!("Msftedit.dll"))?;
        let owner = CreateWindowExW(
            Default::default(),
            w!("STATIC"),
            w!("SayIn clipboard fixture owner"),
            WINDOW_STYLE::default(),
            0,
            0,
            0,
            0,
            Some(HWND_MESSAGE),
            None,
            None,
            None,
        )?;
        let host = CreateWindowExW(
            Default::default(),
            w!("STATIC"),
            w!("SayIn image fixture — disposable target"),
            WS_OVERLAPPEDWINDOW | WS_VISIBLE,
            120,
            120,
            640,
            360,
            None,
            None,
            None,
            None,
        )?;
        let target = CreateWindowExW(
            Default::default(),
            w!("RICHEDIT50W"),
            w!(""),
            WS_CHILD | WS_VISIBLE | WINDOW_STYLE(ES_MULTILINE as u32),
            0,
            0,
            600,
            300,
            Some(host),
            None,
            None,
            None,
        )?;
        let _ = SetForegroundWindow(host);
        let _ = SetFocus(Some(target));
        SendMessageW(target, EM_SETSEL, Some(WPARAM(0)), Some(LPARAM(0)));
        pump(Duration::from_millis(100));
        let result = run(target, owner);
        DestroyWindow(target)?;
        DestroyWindow(host)?;
        DestroyWindow(owner)?;
        OleUninitialize();
        result
    }
}
