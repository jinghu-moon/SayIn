//! Native clipboard ownership experiment, used only by the opt-in fixture.
use std::time::{Duration, Instant};
use voicelink_receiver::images::payload::PreparedImage;
use windows::{
    Win32::{
        Foundation::{GlobalFree, HANDLE, HGLOBAL, HWND},
        System::{
            DataExchange::{
                CloseClipboard, EmptyClipboard, GetClipboardOwner, GetClipboardSequenceNumber,
                OpenClipboard, RegisterClipboardFormatW, SetClipboardData,
            },
            Memory::{GMEM_MOVEABLE, GMEM_ZEROINIT, GlobalAlloc, GlobalLock, GlobalUnlock},
            Ole::CF_DIBV5,
        },
    },
    core::w,
};

struct Block(Option<HGLOBAL>, usize);
impl Block {
    fn new(bytes: &[u8]) -> windows::core::Result<Self> {
        // SAFETY: All allocations are movable HGLOBALs. A failed publication is
        // freed here; only a successful SetClipboardData transfers ownership.
        unsafe {
            let block = Self(
                Some(GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes.len())?),
                bytes.len(),
            );
            let memory = GlobalLock(block.0.unwrap());
            if memory.is_null() {
                return Err(windows::core::Error::from_thread());
            }
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), memory.cast(), bytes.len());
            // GlobalUnlock returns false on success when lock count reaches zero.
            let _ = GlobalUnlock(block.0.unwrap());
            Ok(block)
        }
    }
    fn publish(&mut self, format: u32) -> windows::core::Result<()> {
        unsafe {
            SetClipboardData(format, Some(HANDLE(self.0.unwrap().0)))?;
        }
        self.0 = None;
        Ok(())
    }
}
impl Drop for Block {
    fn drop(&mut self) {
        if let Some(handle) = self.0.take() {
            unsafe {
                let memory = GlobalLock(handle);
                if !memory.is_null() {
                    zeroize::Zeroize::zeroize(std::slice::from_raw_parts_mut(
                        memory.cast::<u8>(),
                        self.1,
                    ));
                    let _ = GlobalUnlock(handle);
                }
                let _ = GlobalFree(Some(handle));
            }
        }
    }
}
struct Open;
impl Drop for Open {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseClipboard();
        }
    }
}

pub struct Published {
    pub sequence: u32,
    owner: HWND,
}
impl Published {
    pub fn unchanged(&self) -> bool {
        unsafe {
            self.sequence != 0
                && GetClipboardSequenceNumber() == self.sequence
                && GetClipboardOwner().is_ok_and(|owner| owner == self.owner)
        }
    }
}

/// A failure after EmptyClipboard explicitly reports the possible side effect.
#[derive(Debug)]
pub struct Failure {
    pub clipboard_changed: bool,
    pub reason: &'static str,
}

pub fn publish(
    owner: HWND,
    image: &PreparedImage,
    allowed: impl Fn() -> bool,
) -> Result<Published, Failure> {
    let before = |reason| Failure {
        clipboard_changed: false,
        reason,
    };
    let after = |reason| Failure {
        clipboard_changed: true,
        reason,
    };
    let png = unsafe { RegisterClipboardFormatW(w!("PNG")) };
    let history = unsafe { RegisterClipboardFormatW(w!("CanIncludeInClipboardHistory")) };
    let cloud = unsafe { RegisterClipboardFormatW(w!("CanUploadToCloudClipboard")) };
    let monitor =
        unsafe { RegisterClipboardFormatW(w!("ExcludeClipboardContentFromMonitorProcessing")) };
    if [png, history, cloud, monitor].contains(&0) {
        return Err(before("register_format"));
    }
    let mut blocks = [
        (
            png,
            Block::new(&image.png).map_err(|_| before("allocation"))?,
        ),
        (
            CF_DIBV5.0 as u32,
            Block::new(&image.dibv5).map_err(|_| before("allocation"))?,
        ),
        (
            history,
            Block::new(&0u32.to_le_bytes()).map_err(|_| before("allocation"))?,
        ),
        (
            cloud,
            Block::new(&0u32.to_le_bytes()).map_err(|_| before("allocation"))?,
        ),
        (
            monitor,
            Block::new(&1u32.to_le_bytes()).map_err(|_| before("allocation"))?,
        ),
    ];
    let deadline = Instant::now() + Duration::from_millis(150);
    loop {
        if !allowed() {
            return Err(before("guard"));
        }
        if unsafe { OpenClipboard(Some(owner)) }.is_ok() {
            break;
        }
        if Instant::now() >= deadline {
            return Err(before("clipboard_busy"));
        }
        std::thread::sleep(Duration::from_millis(5));
    }
    let open = Open;
    if !allowed() {
        return Err(before("guard"));
    }
    unsafe {
        EmptyClipboard().map_err(|_| before("empty_clipboard"))?;
    }
    // Set privacy markers first. Never paste if any requested format failed.
    for index in [2, 3, 4, 0, 1] {
        let (format, block) = &mut blocks[index];
        block.publish(*format).map_err(|_| after("set_clipboard"))?;
    }
    drop(open);
    let sequence = unsafe { GetClipboardSequenceNumber() };
    let published = Published { sequence, owner };
    if !published.unchanged() || !allowed() {
        return Err(after("guard_after_clipboard"));
    }
    Ok(published)
}
