//! Native clipboard publication shared by production input and history.
use crate::images::payload::PreparedImage;
use std::{
    os::windows::ffi::OsStrExt,
    path::Path,
    time::{Duration, Instant},
};
use windows::{
    Win32::{
        Foundation::{GlobalFree, HANDLE, HGLOBAL, HWND},
        System::{
            DataExchange::{
                CloseClipboard, EmptyClipboard, GetClipboardOwner, GetClipboardSequenceNumber,
                OpenClipboard, RegisterClipboardFormatW, SetClipboardData,
            },
            Memory::{GMEM_MOVEABLE, GMEM_ZEROINIT, GlobalAlloc, GlobalLock, GlobalUnlock},
            Ole::{CF_DIBV5, CF_UNICODETEXT},
        },
    },
    core::w,
};

/// Clipboard ownership needs a responsive window even while the input worker
/// is waiting or decoding. This thread only pumps messages; it never focuses a
/// window, reads user content, renders delayed formats or injects input.
pub struct ClipboardOwner(usize);
const STOP_OWNER: u32 = windows::Win32::UI::WindowsAndMessaging::WM_APP + 37;
impl ClipboardOwner {
    pub fn new() -> crate::Result<Self> {
        let (ready, receiver) = std::sync::mpsc::sync_channel(0);
        std::thread::Builder::new()
            .name("sayin-clipboard-owner".into())
            .spawn(move || {
                use windows::{Win32::UI::WindowsAndMessaging::*, core::w};
                unsafe {
                    let owner = CreateWindowExW(
                        Default::default(),
                        w!("STATIC"),
                        w!("SayIn clipboard owner"),
                        WINDOW_STYLE::default(),
                        0,
                        0,
                        0,
                        0,
                        Some(HWND_MESSAGE),
                        None,
                        None,
                        None,
                    );
                    let owner = match owner {
                        Ok(owner) => owner,
                        Err(_) => {
                            let _ = ready.send(None);
                            return;
                        }
                    };
                    if ready.send(Some(owner.0 as usize)).is_err() {
                        let _ = DestroyWindow(owner);
                        return;
                    }
                    let mut message = MSG::default();
                    while GetMessageW(&mut message, None, 0, 0).0 > 0 {
                        if message.hwnd == owner && message.message == STOP_OWNER {
                            break;
                        }
                        let _ = TranslateMessage(&message);
                        DispatchMessageW(&message);
                    }
                    let _ = DestroyWindow(owner);
                }
            })?;
        // Creating a hidden local window has no interaction or network work.
        // If the receiver disappears, the creator destroys its own window.
        let owner = receiver
            .recv_timeout(Duration::from_secs(2))
            .map_err(|_| crate::Error::Protocol("CLIPBOARD_OWNER"))?
            .ok_or(crate::Error::Protocol("CLIPBOARD_OWNER"))?;
        Ok(Self(owner))
    }
    pub fn window(&self) -> HWND {
        HWND(self.0 as *mut _)
    }
}
impl Drop for ClipboardOwner {
    fn drop(&mut self) {
        unsafe {
            let _ = windows::Win32::UI::WindowsAndMessaging::PostMessageW(
                Some(self.window()),
                STOP_OWNER,
                Default::default(),
                Default::default(),
            );
        }
    }
}

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
    let png = unsafe { RegisterClipboardFormatW(w!("PNG")) };
    publish_formats(
        owner,
        &[(png, &image.png), (CF_DIBV5.0 as u32, &image.dibv5)],
        allowed,
    )
}

pub(crate) fn publish_path(
    owner: HWND,
    path: &Path,
    allowed: impl Fn() -> bool,
) -> Result<Published, Failure> {
    let bytes = path
        .as_os_str()
        .encode_wide()
        .chain([0])
        .flat_map(u16::to_le_bytes)
        .collect::<Vec<_>>();
    publish_formats(owner, &[(CF_UNICODETEXT.0 as u32, &bytes)], allowed)
}

fn publish_formats(
    owner: HWND,
    formats: &[(u32, &[u8])],
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
    let history = unsafe { RegisterClipboardFormatW(w!("CanIncludeInClipboardHistory")) };
    let cloud = unsafe { RegisterClipboardFormatW(w!("CanUploadToCloudClipboard")) };
    let monitor =
        unsafe { RegisterClipboardFormatW(w!("ExcludeClipboardContentFromMonitorProcessing")) };
    let exclude = 0u32.to_le_bytes();
    let monitor_exclude = 1u32.to_le_bytes();
    let privacy = [
        (history, exclude.as_slice()),
        (cloud, exclude.as_slice()),
        (monitor, monitor_exclude.as_slice()),
    ];
    let formats = privacy.iter().chain(formats);
    if formats.clone().any(|(format, _)| *format == 0) {
        return Err(before("register_format"));
    }
    let blocks = formats
        .map(|&(format, bytes)| {
            Block::new(bytes)
                .map(|block| (format, block))
                .map_err(|_| before("allocation"))
        })
        .collect::<Result<Vec<_>, _>>()?;
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
    for (format, mut block) in blocks {
        block.publish(format).map_err(|_| after("set_clipboard"))?;
    }
    drop(open);
    let sequence = unsafe { GetClipboardSequenceNumber() };
    let published = Published { sequence, owner };
    if !published.unchanged() || !allowed() {
        return Err(after("guard_after_clipboard"));
    }
    Ok(published)
}

#[cfg(test)]
mod tests {
    #[test]
    fn hidden_owner_pumps_messages_without_input_or_polling_and_stops_on_drop() {
        use windows::Win32::UI::WindowsAndMessaging::*;
        let owner = super::ClipboardOwner::new().unwrap();
        let hwnd = owner.window();
        let mut result = 0;
        unsafe {
            assert_ne!(
                SendMessageTimeoutW(
                    hwnd,
                    WM_NULL,
                    Default::default(),
                    Default::default(),
                    SMTO_ABORTIFHUNG,
                    500,
                    Some(&mut result)
                ),
                windows::Win32::Foundation::LRESULT(0)
            );
        }
        drop(owner);
        let until = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while unsafe { IsWindow(Some(hwnd)).as_bool() } && std::time::Instant::now() < until {
            std::thread::yield_now();
        }
        assert!(!unsafe { IsWindow(Some(hwnd)).as_bool() });
    }
}
