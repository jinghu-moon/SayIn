//! Bounded executable version metadata, shared by history and live display.
use std::path::Path;

/// Version metadata supplies a human-readable name, without a guessed app map.
#[cfg(windows)]
pub fn display_name(path: &Path) -> Option<String> {
    use std::{ffi::c_void, os::windows::ffi::OsStrExt, ptr};
    use windows::{
        Win32::Storage::FileSystem::{
            GetFileVersionInfoSizeW, GetFileVersionInfoW, VerQueryValueW,
        },
        core::{PCWSTR, w},
    };
    if !path.is_absolute() || path.to_string_lossy().starts_with(r"\\") {
        return None;
    }
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
pub fn display_name(_: &Path) -> Option<String> {
    None
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
    }
}
