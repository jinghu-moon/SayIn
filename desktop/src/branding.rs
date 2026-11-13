//! Approved SayIn B-field artwork. Product branding never changes storage IDs.
use std::sync::{Arc, OnceLock};
use yue_component::gpui_kit::{Image, ImageFormat};

pub fn image() -> Arc<Image> {
    static IMAGE: OnceLock<Arc<Image>> = OnceLock::new();
    IMAGE
        .get_or_init(|| {
            Arc::new(Image::from_bytes(
                ImageFormat::Png,
                include_bytes!("../assets/sayin-64.png").to_vec(),
            ))
        })
        .clone()
}

#[cfg(windows)]
pub fn set_window_icon(window: windows::Win32::Foundation::HWND) {
    use windows::{
        Win32::{
            Foundation::{LPARAM, WPARAM},
            System::LibraryLoader::GetModuleHandleW,
            UI::WindowsAndMessaging::*,
        },
        core::PCWSTR,
    };
    // SAFETY: Only the application's own HWND is supplied. LR_SHARED returns
    // module-owned icons; Windows retains them for the module's lifetime.
    unsafe {
        let Ok(module) = GetModuleHandleW(None) else {
            return;
        };
        for (kind, size) in [(ICON_SMALL, SM_CXSMICON), (ICON_BIG, SM_CXICON)] {
            if let Ok(icon) = LoadImageW(
                Some(module.into()),
                PCWSTR(101usize as _),
                IMAGE_ICON,
                GetSystemMetrics(size),
                GetSystemMetrics(size),
                LR_SHARED,
            ) {
                SendMessageW(
                    window,
                    WM_SETICON,
                    Some(WPARAM(kind as usize)),
                    Some(LPARAM(icon.0 as isize)),
                );
            }
        }
    }
}
