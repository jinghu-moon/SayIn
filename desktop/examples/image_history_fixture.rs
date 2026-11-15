//! Opt-in production history copy/export check. Replaces the system clipboard,
//! never focuses a window or injects input; uses only a disposable local ledger.
#[path = "../src/image_history.rs"]
mod image_history;

use std::{
    future::Future,
    sync::Arc,
    task::{Context, Poll, Wake, Waker},
};
use voicelink_receiver::{
    gate::InputGate,
    images::{
        ImageAuthorization, ImageMetadata, ImageMime, digest, storage::ImageStore,
        wire::UploadScope,
    },
    ledger::{Execution, InputOutcome},
    protocol::{Operation, Submit},
    windows_input::{Dpapi, open_ledger},
    worker::{InputAdapter, Receiver},
};
use voicelink_transport::HostIdentity;
use windows::{
    Win32::System::{
        DataExchange::{CloseClipboard, GetClipboardData, OpenClipboard, RegisterClipboardFormatW},
        Memory::{GlobalLock, GlobalSize, GlobalUnlock},
        Ole::CF_DIBV5,
    },
    core::w,
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
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
struct NeverInput;
impl InputAdapter for NeverInput {
    fn preflight(&mut self, _: &Execution) -> std::result::Result<(), &'static str> {
        panic!("history must not schedule input")
    }
    fn execute(&mut self, _: &Execution, _: &InputGate) -> InputOutcome {
        panic!("history must not paste")
    }
}
fn check_format(format: u32, expected: &[u8]) -> Result<()> {
    unsafe {
        OpenClipboard(None)?;
        let result = (|| -> Result<()> {
            let handle = windows::Win32::Foundation::HGLOBAL(GetClipboardData(format)?.0);
            let size = GlobalSize(handle);
            if size < expected.len() || size > 65 * 1024 * 1024 {
                return Err("clipboard allocation differs".into());
            }
            let memory = GlobalLock(handle);
            if memory.is_null() {
                return Err("clipboard lock failed".into());
            }
            let equal = std::slice::from_raw_parts(memory.cast::<u8>(), expected.len()) == expected;
            let _ = GlobalUnlock(handle);
            if !equal {
                return Err("clipboard pixels/marker differ".into());
            }
            Ok(())
        })();
        CloseClipboard()?;
        result
    }
}
fn main() -> Result<()> {
    if std::env::args().nth(1).as_deref() != Some("--run") {
        return Err("Opt-in --run replaces the clipboard; never pastes or opens daily data".into());
    }
    let directory = std::env::temp_dir().join(format!(
        "sayin-history-copy-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)?
            .as_nanos()
    ));
    std::fs::create_dir_all(&directory)?;
    let mut ledger = open_ledger(&directory)?;
    let identity = HostIdentity::open(&directory, 0)?;
    let store = ImageStore::open(&directory.join("images"), identity.host_id())?;
    ledger.attach_images(store.clone())?;
    let device = "12345678-1234-4234-8234-123456789abc";
    ledger.provision_device(device, &[7; 32], "Isolated history fixture".into())?;
    let session = ledger.authenticate(device, &[7; 32])?;
    let state = ledger.bind_target(identity.host_id())?;
    ledger.resume(&state.state_rev, false)?;
    let bytes = include_bytes!("../assets/sayin-64.png");
    let resource = store.prepare(
        device,
        ImageMetadata {
            bytes: bytes.len() as u32,
            mime: ImageMime::Png,
            width: 64,
            height: 64,
            sha256: digest(bytes),
        },
        bytes,
        &Dpapi,
    )?;
    let image = resource.image().clone();
    ledger.register_image(
        &session,
        UploadScope {
            device: device.into(),
            session: session.id().into(),
            target: identity.host_id().into(),
            revision: ledger.state()?.state_rev,
        },
        resource,
        Arc::new(ImageAuthorization::new(Arc::new(
            std::sync::atomic::AtomicBool::new(true),
        ))),
    )?;
    let receipt = ledger.submit(
        &session,
        &Submit::new(
            session.id().into(),
            "23456789-1234-4234-8234-123456789abc".into(),
            identity.host_id().into(),
            Operation::Image { image },
        ),
    )?;
    let order = receipt.order.parse()?;
    // A preconstructed test receipt, not a claim that NativeInput was executed.
    let job = ledger.prepare_next()?.ok_or("missing test job")?;
    assert!(ledger.mark_executing(&job)?);
    ledger.finish(
        &job,
        InputOutcome::Injected {
            method: "clipboard_image",
        },
    )?;
    ledger.pause()?;
    let receiver = Receiver::start(ledger, NeverInput)?;
    let owner = voicelink_receiver::windows_clipboard::ClipboardOwner::new()?;
    let expected = voicelink_receiver::images::payload::prepare(bytes)?;
    wait(image_history::thumbnail(&receiver, order))?;
    wait(image_history::preview(&receiver, order, 8192))?;
    wait(image_history::copy(&receiver, order, &owner))?;
    check_format(
        unsafe { RegisterClipboardFormatW(w!("PNG")) },
        &expected.png,
    )?;
    check_format(CF_DIBV5.0 as u32, &expected.dibv5)?;
    for name in [
        w!("CanIncludeInClipboardHistory"),
        w!("CanUploadToCloudClipboard"),
    ] {
        check_format(
            unsafe { RegisterClipboardFormatW(name) },
            &0u32.to_le_bytes(),
        )?;
    }
    let export = directory.join("export.png");
    wait(image_history::save(&receiver, order, &export))?;
    assert_eq!(std::fs::read(&export)?, bytes);
    assert!(wait(receiver.delete_history(order))?);
    assert!(wait(image_history::copy(&receiver, order, &owner)).is_err());
    assert!(wait(image_history::save(&receiver, order, &export)).is_err());
    assert_eq!(std::fs::read(&export)?, bytes);
    wait(receiver.shutdown())?;
    drop(owner);
    drop(identity);
    drop(store);
    std::fs::remove_dir_all(directory)?;
    println!(
        "PASS production history preview/copy/export, PNG + DIBV5 + privacy markers, deletion prevents access, no input adapter invocation"
    );
    Ok(())
}
