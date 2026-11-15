//! Opt-in real SendInput verification against this executable's own child EDIT.
//! No production data, network or user application is used. --image explicitly
//! replaces the real clipboard and pastes into a disposable child RichEdit.
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
    windows_input::{NativeInput, open_ledger},
    worker::{InputAdapter, Receiver},
};
use windows::{
    Win32::{
        Foundation::{LPARAM, WPARAM},
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
        for byte in std::slice::from_raw_parts(buffer, length as usize) {
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
fn picture_count(
    target: windows::Win32::Foundation::HWND,
) -> Result<usize, Box<dyn std::error::Error>> {
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
    Ok(counter.count)
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
                picture_count(hwnd)?
            } else {
                0
            };
            if !recorded
                && (if is_image {
                    pictures == 1 && length == ORIGINAL.len() + 1
                } else {
                    text == format!("{ORIGINAL}{TEXT}")
                })
            {
                if is_image {
                    observed_images += 1;
                }
                std::fs::write(
                    directory.join("observed.json"),
                    serde_json::to_vec(
                        &serde_json::json!({"text":text,"pictures":pictures,"observed_images":observed_images,"pid":std::process::id()}),
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
                "pictures":if is_image { picture_count(hwnd)? } else { 0 },
                "observed_images":observed_images,
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
) -> Result<(), Box<dyn std::error::Error>> {
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
    let receiver = Receiver::start(
        ledger,
        FixtureInput {
            native: NativeInput::with_images(store.clone()),
            hwnd: window["hwnd"].as_u64().unwrap() as usize,
            pid: child_pid,
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
        while !directory.join("observed.json").exists() {
            if Instant::now() >= deadline {
                return Err(
                    "SendInput returned but EDIT did not contain exact expected text".into(),
                );
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let observed: serde_json::Value =
            serde_json::from_slice(&std::fs::read(directory.join("observed.json"))?)?;
        if is_image {
            assert_eq!(observed["pictures"], 1);
        } else {
            assert_eq!(observed["text"], format!("{ORIGINAL}{TEXT}"));
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
        if is_image {
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
    let is_image = arguments.get(1).is_some_and(|arg| arg == "--image");
    let mut child = Command::new(std::env::current_exe()?)
        .arg("--edit-target")
        .arg(&directory)
        .arg(if is_image { "--image" } else { "--text" })
        .spawn()?;
    let image_path = arguments
        .windows(2)
        .find(|args| args[0] == "--image-path")
        .map(|args| Path::new(&args[1]));
    let result = verify(&directory, child.id(), is_image, image_path);
    std::fs::write(directory.join("stop"), b"")?;
    let child_status = child.wait()?;
    let final_content = std::fs::read(directory.join("final.json"));
    std::fs::remove_dir_all(&directory)?;
    result?;
    let final_content: serde_json::Value = serde_json::from_slice(&final_content?)?;
    if is_image {
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
