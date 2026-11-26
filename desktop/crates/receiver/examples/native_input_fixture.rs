//! Opt-in verification with disposable EDIT, RichEdit or Windows Terminal windows.
//! Image modes replace the system clipboard. No production data or network is used.
use std::{
    future::Future,
    path::Path,
    process::Command,
    sync::Arc,
    task::{Context, Poll, Wake, Waker},
    time::{Duration, Instant},
};
use uuid::Uuid;
use voicelink_receiver::{
    gate::InputGate,
    ledger::{Execution, InputOutcome},
    protocol::{Operation, State, Submit, TextSource},
    windows_image_delivery::{ImageDeliveryMode, ImageDeliverySettings},
    windows_input::{NativeInput, open_ledger},
    worker::{InputAdapter, Receiver},
};
use windows::{
    Win32::{
        Foundation::{HGLOBAL, LPARAM, WPARAM},
        System::{
            DataExchange::{CloseClipboard, GetClipboardData, OpenClipboard},
            Memory::{GlobalLock, GlobalSize, GlobalUnlock},
            Ole::CF_UNICODETEXT,
        },
        UI::{
            Controls::EM_SETSEL,
            Input::KeyboardAndMouse::{GetFocus, SetFocus},
            WindowsAndMessaging::*,
        },
    },
    core::w,
};
use zeroize::Zeroizing;

const ORIGINAL: &str = "existing|";
const TEXT: &str = "中文 😀 literal {ENTER}";

fn wait<T>(future: impl Future<Output = T>) -> T {
    struct ThreadWake(std::thread::Thread);
    impl Wake for ThreadWake {
        fn wake(self: Arc<Self>) {
            self.0.unpark();
        }
    }
    let waker = Waker::from(Arc::new(ThreadWake(std::thread::current())));
    let mut context = Context::from_waker(&waker);
    let mut future = std::pin::pin!(future);
    loop {
        match future.as_mut().poll(&mut context) {
            Poll::Ready(value) => return value,
            Poll::Pending => std::thread::park(),
        }
    }
}

#[derive(Default)]
struct PictureCounter {
    matched: usize,
    count: usize,
    bytes: usize,
    prefix: Vec<u8>,
}

impl PictureCounter {
    fn first_picture_width(&self) -> Option<u32> {
        let rtf = std::str::from_utf8(&self.prefix).ok()?;
        let (_, picture) = rtf.split_once("\\pict")?;
        let (_, width) = picture.split_once("\\picwgoal")?;
        width[..width.find(|character: char| !character.is_ascii_digit())?]
            .parse()
            .ok()
    }
}

#[cfg(test)]
mod stream_tests {
    use super::*;

    #[test]
    fn counts_pictures_across_stream_boundaries_without_retaining_rtf() {
        let mut counter = PictureCounter::default();
        for chunk in [b"{\\pi".as_slice(), b"ct xx \\\\p", b"ict abc \\pic", b"t}"] {
            let mut written = 0;
            assert_eq!(
                unsafe {
                    stream_image(
                        (&mut counter as *mut PictureCounter) as usize,
                        chunk.as_ptr() as *mut u8,
                        chunk.len() as i32,
                        &mut written,
                    )
                },
                0
            );
            assert_eq!(written, chunk.len() as i32);
        }
        assert_eq!(counter.count, 3);
        counter.bytes = 256 * 1024 * 1024;
        let mut byte = 0u8;
        let mut written = 0;
        assert_ne!(
            unsafe {
                stream_image(
                    (&mut counter as *mut PictureCounter) as usize,
                    &mut byte,
                    1,
                    &mut written,
                )
            },
            0
        );
    }
}
unsafe extern "system" fn stream_image(
    cookie: usize,
    buffer: *mut u8,
    length: i32,
    written: *mut i32,
) -> u32 {
    unsafe {
        let output = &mut *(cookie as *mut PictureCounter);
        if length < 0 || output.bytes + length as usize > 256 * 1024 * 1024 {
            return 1;
        }
        output.bytes += length as usize;
        let chunk = std::slice::from_raw_parts(buffer, length as usize);
        output
            .prefix
            .extend_from_slice(&chunk[..chunk.len().min(4096 - output.prefix.len())]);
        for byte in chunk {
            if *byte == b"\\pict"[output.matched] {
                output.matched += 1;
                if output.matched == 5 {
                    output.count += 1;
                    output.matched = 0;
                }
            } else {
                output.matched = usize::from(*byte == b'\\');
            }
        }
        *written = length;
    }
    0
}
fn pictures_in(
    target: windows::Win32::Foundation::HWND,
) -> Result<PictureCounter, Box<dyn std::error::Error>> {
    use windows::Win32::UI::Controls::RichEdit::{EDITSTREAM, EM_STREAMOUT, SF_RTF};
    let mut counter = PictureCounter::default();
    let mut stream = EDITSTREAM {
        dwCookie: (&mut counter as *mut PictureCounter) as usize,
        dwError: 0,
        pfnCallback: Some(stream_image),
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
        return Err("RTF export failed".into());
    }
    Ok(counter)
}

fn edit_target(
    directory: &Path,
    is_image: bool,
    batch: bool,
) -> Result<(), Box<dyn std::error::Error>> {
    // SAFETY: This process creates, focuses, pumps and destroys only its own
    // disposable EDIT. The parent validates its HWND and PID before injection.
    unsafe {
        if is_image {
            windows::Win32::System::LibraryLoader::LoadLibraryW(w!("Msftedit.dll"))?;
            windows::Win32::System::Ole::OleInitialize(None)?;
        }
        let root = if is_image {
            Some(CreateWindowExW(
                Default::default(),
                w!("STATIC"),
                w!("SayIn disposable image target"),
                WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                100,
                100,
                640,
                240,
                None,
                None,
                None,
                None,
            )?)
        } else {
            None
        };
        let hwnd = CreateWindowExW(
            Default::default(),
            if is_image {
                w!("RICHEDIT50W")
            } else {
                w!("EDIT")
            },
            w!("existing|"),
            (if is_image {
                WS_CHILD
            } else {
                WS_OVERLAPPEDWINDOW
            }) | WS_VISIBLE
                | WINDOW_STYLE(ES_MULTILINE as u32),
            100,
            100,
            640,
            240,
            root,
            None,
            None,
            None,
        )?;
        let root = root.unwrap_or(hwnd);
        let _ = SetForegroundWindow(root);
        let _ = SetFocus(Some(hwnd));
        SendMessageW(
            hwnd,
            EM_SETSEL,
            Some(WPARAM(ORIGINAL.len())),
            Some(LPARAM(ORIGINAL.len() as isize)),
        );
        let deadline = Instant::now() + Duration::from_secs(if batch { 600 } else { 120 });
        let mut recorded = false;
        let mut observed_images = 0;
        let mut picture_widths = Vec::new();
        let mut ready = false;
        let mut reported = false;
        while Instant::now() < deadline && !directory.join("stop").exists() {
            let mut message = MSG::default();
            while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                if batch
                    && message.hwnd == hwnd
                    && matches!(message.message, WM_KEYDOWN | WM_KEYUP | WM_CHAR)
                {
                    eprintln!(
                        "own-target input msg={} code={} observed={observed_images}",
                        message.message, message.wParam.0
                    );
                }
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            if !ready {
                let _ = SetForegroundWindow(root);
                let _ = SetFocus(Some(hwnd));
                if !reported {
                    eprintln!(
                        "fixture root={:?}, target={:?}, foreground={:?}, focus={:?}",
                        root,
                        hwnd,
                        GetForegroundWindow(),
                        GetFocus()
                    );
                    reported = true;
                }
                if GetForegroundWindow() == root && GetFocus() == hwnd {
                    SendMessageW(
                        hwnd,
                        EM_SETSEL,
                        Some(WPARAM(ORIGINAL.len())),
                        Some(LPARAM(ORIGINAL.len() as isize)),
                    );
                    std::fs::write(
                        directory.join("window.json"),
                        serde_json::to_vec(
                            &serde_json::json!({"hwnd":root.0 as usize,"pid":std::process::id()}),
                        )?,
                    )?;
                    ready = true;
                }
            }
            let mut buffer = [0u16; 256];
            let length = GetWindowTextW(hwnd, &mut buffer).max(0) as usize;
            let text = String::from_utf16_lossy(&buffer[..length]);
            let pictures = if is_image && !recorded && length == ORIGINAL.len() + 1 {
                pictures_in(hwnd)?
            } else {
                PictureCounter::default()
            };
            if !recorded
                && (if is_image {
                    pictures.count == 1 && length == ORIGINAL.len() + 1
                } else {
                    text == format!("{ORIGINAL}{TEXT}")
                })
            {
                if is_image {
                    observed_images += 1;
                    picture_widths.push(pictures.first_picture_width());
                }
                std::fs::write(
                    directory.join("observed.json"),
                    serde_json::to_vec(
                        &serde_json::json!({"text":text,"pictures":pictures.count,"observed_images":observed_images,"picture_widths_twips":picture_widths,"pid":std::process::id()}),
                    )?,
                )?;
                recorded = true;
                if batch {
                    SetWindowTextW(hwnd, w!("existing|"))?;
                    SendMessageW(
                        hwnd,
                        EM_SETSEL,
                        Some(WPARAM(ORIGINAL.len())),
                        Some(LPARAM(ORIGINAL.len() as isize)),
                    );
                    recorded = false;
                }
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let mut buffer = [0u16; 256];
        let length = GetWindowTextW(hwnd, &mut buffer).max(0) as usize;
        std::fs::write(
            directory.join("final.json"),
            serde_json::to_vec(&serde_json::json!({
                "text":String::from_utf16_lossy(&buffer[..length]),
                "length":length,
                "pictures":if is_image { pictures_in(hwnd)?.count } else { 0 },
                "observed_images":observed_images,
                "picture_widths_twips":picture_widths,
            }))?,
        )?;
        eprintln!(
            "Own EDIT final text: {:?}",
            String::from_utf16_lossy(&buffer[..length])
        );
        DestroyWindow(root)?;
    }
    Ok(())
}

struct FixtureInput {
    native: NativeInput,
    hwnd: usize,
    pid: u32,
    terminal: bool,
}
impl FixtureInput {
    fn is_fixture(&self) -> bool {
        unsafe {
            let foreground = GetForegroundWindow();
            let mut pid = 0;
            GetWindowThreadProcessId(foreground, Some(&mut pid));
            foreground.0 as usize == self.hwnd && pid == self.pid
        }
    }
}
impl InputAdapter for FixtureInput {
    fn preflight(&mut self, job: &Execution) -> Result<(), &'static str> {
        if !self.is_fixture() {
            return Err("fixture_not_foreground");
        }
        let started = Instant::now();
        let result = self.native.preflight(job);
        if self.terminal
            && !self.native.prepared_application().is_some_and(|app| {
                Path::new(&app.executable)
                    .file_name()
                    .is_some_and(|name| name.eq_ignore_ascii_case("WindowsTerminal.exe"))
            })
        {
            return Err("fixture_not_windows_terminal");
        }
        eprintln!(
            "Production native preflight: {} ms",
            started.elapsed().as_millis()
        );
        result
    }
    fn prepared_application(&self) -> Option<voicelink_receiver::history::TargetApplication> {
        self.native.prepared_application()
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        if !self.is_fixture() {
            return InputOutcome::Failed {
                reason: "fixture_not_foreground",
            };
        }
        self.native.execute(job, gate)
    }
}

fn verify(
    directory: &Path,
    child_pid: u32,
    is_image: bool,
    image_path: Option<&Path>,
    terminal: bool,
    copy_path_override: bool,
) -> Result<(), Box<dyn std::error::Error>> {
    let copy_path = terminal || copy_path_override;
    let deadline = Instant::now() + Duration::from_secs(5);
    while !directory.join("window.json").exists() {
        if Instant::now() >= deadline {
            return Err("fixture window not created".into());
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let window: serde_json::Value =
        serde_json::from_slice(&std::fs::read(directory.join("window.json"))?)?;
    assert_eq!(window["pid"], child_pid);
    let mut ledger = open_ledger(&directory.join("ledger"))?;
    let store = voicelink_receiver::images::storage::ImageStore::open(
        &directory.join("images"),
        &Uuid::new_v4().to_string(),
    )?;
    ledger.attach_images(store.clone())?;
    let mut rules = std::collections::BTreeMap::new();
    if copy_path_override {
        let exe = std::env::current_exe()?;
        rules.insert(
            exe.to_str()
                .ok_or("invalid fixture executable path")?
                .to_owned(),
            ImageDeliveryMode::CopyPath,
        );
    }
    let receiver = Receiver::start(
        ledger,
        FixtureInput {
            native: NativeInput::with_images(
                store.clone(),
                Arc::new(std::sync::RwLock::new(ImageDeliverySettings::new(
                    Some(directory.join("exports")),
                    rules,
                ))),
            ),
            hwnd: window["hwnd"].as_u64().unwrap() as usize,
            pid: child_pid,
            terminal,
        },
    )?;
    let result = (|| -> Result<(), Box<dyn std::error::Error>> {
        let device = Uuid::new_v4().to_string();
        wait(receiver.provision_device(
            device.clone(),
            Zeroizing::new(vec![88; 32]),
            "Own EDIT fixture".into(),
        ))?;
        let session = wait(receiver.authenticate(device, Zeroizing::new(vec![88; 32])))?;
        let state = wait(receiver.state())?;
        let target = wait(receiver.enable_foreground_input(state.state_rev, false))?
            .target
            .unwrap();
        let op = if is_image {
            use voicelink_receiver::images::{
                ImageAuthorization, ImageMetadata, ImageMime, digest, wire::UploadScope,
            };
            let bytes = if let Some(path) = image_path {
                use std::io::Read as _;
                let mut bytes = Vec::new();
                std::fs::File::open(path)?
                    .take(voicelink_receiver::images::MAX_IMAGE_BYTES as u64 + 1)
                    .read_to_end(&mut bytes)?;
                bytes
            } else {
                let mut bytes = std::io::Cursor::new(Vec::new());
                image::DynamicImage::new_rgba8(3, 2)
                    .write_to(&mut bytes, image::ImageFormat::Png)?;
                bytes.into_inner()
            };
            let decoded = voicelink_receiver::images::payload::prepare(&bytes)?;
            let (width, height) = (decoded.width, decoded.height);
            drop(decoded);
            let mime = if bytes.starts_with(&[137, 80, 78, 71]) {
                ImageMime::Png
            } else {
                ImageMime::Jpeg
            };
            let resource = store.prepare(
                session.device(),
                ImageMetadata {
                    bytes: bytes.len() as u32,
                    mime,
                    width,
                    height,
                    sha256: digest(&bytes),
                },
                &bytes,
                &voicelink_receiver::windows_input::Dpapi,
            )?;
            let image = resource.image().clone();
            let state = wait(receiver.state())?;
            wait(receiver.register_image(
                session.clone(),
                UploadScope {
                    device: session.device().into(),
                    session: session.id().into(),
                    target: target.clone(),
                    revision: state.state_rev,
                },
                resource,
                Arc::new(ImageAuthorization::new(Arc::new(
                    std::sync::atomic::AtomicBool::new(true),
                ))),
            ))?;
            Operation::Image { image }
        } else {
            Operation::Text {
                text: TEXT.into(),
                src: TextSource::Manual,
            }
        };
        let operation = Submit::new(session.id().into(), Uuid::new_v4().to_string(), target, op);
        wait(receiver.submit(session.clone(), operation.clone()))?;
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let receipt = wait(receiver.query(session.clone(), operation.id.clone()))?.unwrap();
            if receipt.st == State::Injected {
                assert_eq!(
                    receipt.method.as_deref(),
                    Some(if copy_path {
                        "clipboard_image_path"
                    } else if is_image {
                        "clipboard_image"
                    } else {
                        "unicode"
                    })
                );
                break;
            }
            if receipt.st == State::Held || receipt.st.is_terminal() || Instant::now() >= deadline {
                return Err(format!(
                    "native receipt={:?}, reason={:?}",
                    receipt.st, receipt.reason
                )
                .into());
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        if let Operation::Image { image } = &operation.op {
            let paths = std::fs::read_dir(directory.join("exports"))?
                .collect::<std::io::Result<Vec<_>>>()?;
            assert_eq!(paths.len(), 1);
            let path = paths[0].path();
            image.metadata.verify_bytes(&std::fs::read(&path)?)?;
            if copy_path {
                assert!(clipboard_has_path(&path)?);
            }
        }
        while !copy_path && !directory.join("observed.json").exists() {
            if Instant::now() >= deadline {
                return Err(
                    "SendInput returned but EDIT did not contain exact expected text".into(),
                );
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        if !copy_path {
            let observed: serde_json::Value =
                serde_json::from_slice(&std::fs::read(directory.join("observed.json"))?)?;
            if is_image {
                assert_eq!(observed["pictures"], 1);
            } else {
                assert_eq!(observed["text"], format!("{ORIGINAL}{TEXT}"));
            }
        }
        assert_eq!(
            wait(receiver.submit(session, operation))?.st,
            State::Injected
        );
        let history = wait(receiver.history(Default::default()))?;
        assert_eq!(history.entries.len(), 1);
        assert_eq!(
            history.entries[0].target.as_ref().unwrap().process_id,
            child_pid
        );
        if terminal {
            println!(
                "PASS: Windows Terminal target saved original bytes and copied the exact Windows path as Unicode text."
            );
        } else if copy_path_override {
            println!(
                "PASS: Per-app rule for a non-terminal selected path copy and saved original bytes."
            );
        } else if is_image {
            println!(
                "PASS: production DPAPI/receipt/NativeInput path pasted one image into child RichEdit; no Enter; duplicate did not execute; target PID recorded."
            );
        } else {
            println!(
                "PASS: real native EDIT received exact CJK + emoji + literal {{ENTER}} at caret; prefix preserved; no appended Enter; duplicate did not execute; target PID recorded."
            );
        }
        Ok(())
    })();
    wait(receiver.shutdown())?;
    result
}

fn clipboard_has_path(path: &Path) -> windows::core::Result<bool> {
    use std::os::windows::ffi::OsStrExt as _;
    let expected = path
        .as_os_str()
        .encode_wide()
        .chain([0])
        .collect::<Vec<_>>();
    // The clipboard owns the block. It remains locked only during comparison.
    unsafe {
        OpenClipboard(None)?;
        let result = (|| {
            let handle = HGLOBAL(GetClipboardData(CF_UNICODETEXT.0 as u32)?.0);
            if GlobalSize(handle) < expected.len() * size_of::<u16>() {
                return Ok(false);
            }
            let data = GlobalLock(handle).cast::<u16>();
            if data.is_null() {
                return Err(windows::core::Error::from_thread());
            }
            let matched = std::slice::from_raw_parts(data, expected.len()) == expected;
            let _ = GlobalUnlock(handle);
            Ok(matched)
        })();
        let _ = CloseClipboard();
        result
    }
}

fn verify_terminal(directory: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let title = format!("SayIn image validation {}", Uuid::new_v4());
    let status = Command::new("wt.exe")
        .args([
            "--window",
            "new",
            "new-tab",
            "--title",
            &title,
            "--suppressApplicationTitle",
            "cmd.exe",
            "/d",
        ])
        .status()?;
    if !status.success() {
        return Err("Windows Terminal failed to launch".into());
    }
    let deadline = Instant::now() + Duration::from_secs(8);
    loop {
        let mut caption = [0u16; 256];
        // Check the unique test title and pin the native window and process.
        let (window, pid, length) = unsafe {
            let window = GetForegroundWindow();
            let mut pid = 0;
            GetWindowThreadProcessId(window, Some(&mut pid));
            (
                window,
                pid,
                GetWindowTextW(window, &mut caption).max(0) as usize,
            )
        };
        if String::from_utf16_lossy(&caption[..length]).contains(&title) {
            std::fs::write(
                directory.join("window.json"),
                serde_json::to_vec(&serde_json::json!({"hwnd": window.0 as usize, "pid": pid}))?,
            )?;
            let result = verify(directory, pid, true, None, true, false);
            unsafe {
                PostMessageW(Some(window), WM_CLOSE, WPARAM(0), LPARAM(0))?;
            }
            return result;
        }
        if Instant::now() >= deadline {
            return Err("disposable Windows Terminal did not become foreground".into());
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments = std::env::args().collect::<Vec<_>>();
    if arguments.get(1).is_some_and(|arg| arg == "--edit-target") {
        return edit_target(
            Path::new(&arguments[2]),
            arguments.get(3).is_some_and(|arg| arg == "--image"),
            arguments.iter().any(|arg| arg == "--batch"),
        );
    }
    let directory = std::env::temp_dir().join(format!("voicelink-real-edit-{}", Uuid::new_v4()));
    std::fs::create_dir_all(directory.join("ledger"))?;
    if arguments.get(1).is_some_and(|arg| arg == "--terminal") {
        let result = verify_terminal(&directory);
        std::fs::remove_dir_all(&directory)?;
        return result;
    }
    let copy_path_override = arguments.get(1).is_some_and(|arg| arg == "--copy-path");
    let is_image = copy_path_override || arguments.get(1).is_some_and(|arg| arg == "--image");
    let mut child = Command::new(std::env::current_exe()?)
        .arg("--edit-target")
        .arg(&directory)
        .arg(if is_image { "--image" } else { "--text" })
        .spawn()?;
    let image_path = arguments
        .windows(2)
        .find(|args| args[0] == "--image-path")
        .map(|args| Path::new(&args[1]));
    let result = verify(
        &directory,
        child.id(),
        is_image,
        image_path,
        false,
        copy_path_override,
    );
    std::fs::write(directory.join("stop"), b"")?;
    let child_status = child.wait()?;
    let final_content = std::fs::read(directory.join("final.json"));
    std::fs::remove_dir_all(&directory)?;
    result?;
    let final_content: serde_json::Value = serde_json::from_slice(&final_content?)?;
    if copy_path_override {
        assert_eq!(final_content["pictures"], 0);
    } else if is_image {
        assert_eq!(final_content["pictures"], 1);
        assert_eq!(final_content["length"], ORIGINAL.len() + 1);
    } else {
        assert_eq!(final_content["text"], format!("{ORIGINAL}{TEXT}"));
    }
    if !child_status.success() {
        return Err("EDIT child failed".into());
    }
    Ok(())
}
