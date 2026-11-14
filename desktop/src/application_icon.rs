//! File access and icon decoding run on a background executor, never in render.
use std::{path::Path, sync::Arc, time::SystemTime};
use yue_component::gpui_kit::{Image, ImageFormat};

#[derive(Clone)]
pub(crate) struct ApplicationIcon {
    modified: Option<SystemTime>,
    pub(crate) image: Option<Arc<Image>>,
    pub(crate) display_name: Option<String>,
}

pub(crate) fn load(path: &str, previous: Option<ApplicationIcon>) -> ApplicationIcon {
    let path = Path::new(path);
    // Target metadata is historical, not a command. Never execute it or resolve
    // shell links/URLs; missing and network paths use the neutral fallback.
    if !path.is_absolute() || path.to_string_lossy().starts_with(r"\\") {
        return ApplicationIcon {
            modified: None,
            image: None,
            display_name: None,
        };
    }
    let modified = std::fs::metadata(path)
        .and_then(|metadata| metadata.modified())
        .ok();
    if let Some(previous) = previous.filter(|p| p.modified == modified) {
        return previous;
    }
    ApplicationIcon {
        modified,
        image: extract(path),
        display_name: display_name(path),
    }
}

#[cfg(windows)]
fn extract(path: &Path) -> Option<Arc<Image>> {
    let icon = win_icon_extractor::extract_icon_best(path, 32).ok()?;
    let png = win_icon_extractor::encode_png(&icon.rgba, icon.width, icon.height).ok()?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Png, png)))
}

#[cfg(not(windows))]
fn extract(_: &Path) -> Option<Arc<Image>> {
    None
}

/// Version metadata supplies a human-readable name, without a guessed app map.
#[cfg(windows)]
fn display_name(path: &Path) -> Option<String> {
    use std::{ffi::c_void, os::windows::ffi::OsStrExt, ptr};
    use windows::{
        Win32::Storage::FileSystem::{
            GetFileVersionInfoSizeW, GetFileVersionInfoW, VerQueryValueW,
        },
        core::{PCWSTR, w},
    };
    let path = path
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect::<Vec<_>>();
    // SAFETY: buffers outlive every query and have native alignment. Query
    // pointers are checked against the block before being read or decoded.
    unsafe {
        let length = GetFileVersionInfoSizeW(PCWSTR(path.as_ptr()), None) as usize;
        if length == 0 || length > 4 * 1024 * 1024 {
            return None;
        }
        let mut block = vec![0u64; length.div_ceil(8)];
        GetFileVersionInfoW(
            PCWSTR(path.as_ptr()),
            None,
            length as u32,
            block.as_mut_ptr().cast(),
        )
        .ok()?;
        let start = block.as_ptr() as usize;
        let end = start + length;
        let mut pointer: *mut c_void = ptr::null_mut();
        let mut count = 0;
        if !VerQueryValueW(
            block.as_ptr().cast(),
            w!("\\VarFileInfo\\Translation"),
            &mut pointer,
            &mut count,
        )
        .as_bool()
            || (pointer as usize) < start
            || (pointer as usize).checked_add(count as usize)? > end
        {
            return None;
        }
        let translations = (0..(count as usize / 4).min(32))
            .map(|index| {
                let pair = pointer.cast::<u16>().add(index * 2);
                (pair.read_unaligned(), pair.add(1).read_unaligned())
            })
            .collect::<Vec<_>>();
        let mut names = [None, None];
        for (index, field) in ["FileDescription", "ProductName"].into_iter().enumerate() {
            for (language, page) in &translations {
                let key = format!("\\StringFileInfo\\{language:04x}{page:04x}\\{field}\0")
                    .encode_utf16()
                    .collect::<Vec<_>>();
                if !VerQueryValueW(
                    block.as_ptr().cast(),
                    PCWSTR(key.as_ptr()),
                    &mut pointer,
                    &mut count,
                )
                .as_bool()
                    || (pointer as usize) < start
                    || (pointer as usize).checked_add(count as usize * 2)? > end
                {
                    continue;
                }
                let text = (0..(count as usize).min(4096))
                    .map(|index| pointer.cast::<u16>().add(index).read_unaligned())
                    .take_while(|value| *value != 0)
                    .collect::<Vec<_>>();
                let name = String::from_utf16_lossy(&text)
                    .chars()
                    .filter(|c| !c.is_control())
                    .collect::<String>();
                let name = name.trim().chars().take(96).collect::<String>();
                if !name.is_empty() {
                    names[index] = Some(name);
                    break;
                }
            }
        }
        let [description, product] = names;
        version_label(description, product)
    }
}

#[cfg(any(windows, test))]
fn version_label(description: Option<String>, product: Option<String>) -> Option<String> {
    match (description, product) {
        // Windows Terminal's description is "Windows Terminal Host". Prefer
        // the product when it is the leading name, keeping OS-wide product
        // labels from replacing useful descriptions such as Windows Explorer.
        (Some(description), Some(product))
            if description
                .to_lowercase()
                .starts_with(&product.to_lowercase()) =>
        {
            Some(product)
        }
        (Some(description), _) => Some(description),
        (None, product) => product,
    }
}

#[cfg(not(windows))]
fn display_name(_: &Path) -> Option<String> {
    None
}

pub(crate) fn process_label(path: &str) -> String {
    let name = path.rsplit(['\\', '/']).next().unwrap_or(path);
    name.strip_suffix(".exe")
        .or_else(|| name.strip_suffix(".EXE"))
        .unwrap_or(name)
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn names_use_product_metadata_without_replacing_specific_apps_with_the_os() {
        assert_eq!(
            version_label(
                Some("Windows Terminal Host".into()),
                Some("Windows Terminal".into())
            ),
            Some("Windows Terminal".into())
        );
        assert_eq!(
            version_label(
                Some("Windows Explorer".into()),
                Some("Microsoft Windows Operating System".into())
            ),
            Some("Windows Explorer".into())
        );
        assert_eq!(process_label(r"C:\Apps\Code.exe"), "Code");
    }
    #[test]
    fn missing_or_relative_targets_have_no_fabricated_application_icon() {
        assert!(load("Code.exe", None).image.is_none());
        assert!(load(r"\\server\share\app.exe", None).image.is_none());
    }
    #[cfg(windows)]
    #[test]
    fn extracts_a_real_windows_executable_and_reuses_an_unchanged_image() {
        let path = std::env::var("WINDIR").unwrap() + r"\explorer.exe";
        let first = load(&path, None);
        assert!(first.image.is_some());
        assert!(
            first
                .display_name
                .as_ref()
                .is_some_and(|s| !s.is_empty() && !s.ends_with(".exe"))
        );
        let second = load(&path, Some(first.clone()));
        assert!(Arc::ptr_eq(
            first.image.as_ref().unwrap(),
            second.image.as_ref().unwrap()
        ));
    }
}
