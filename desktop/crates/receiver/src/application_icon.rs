//! Local executable resources only. Call on a background worker, never in render.
use std::path::Path;

pub const ICON_EDGE: u32 = 48;
pub const MAX_ICON_BYTES: usize = 12 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ApplicationDisplay {
    pub name: String,
    pub icon_png: Option<Vec<u8>>,
}

pub fn png(path: &Path) -> Option<Vec<u8>> {
    if !path.is_absolute() || path.to_string_lossy().starts_with(r"\\") {
        return None;
    }
    extract(path)
}

#[cfg(windows)]
fn extract(path: &Path) -> Option<Vec<u8>> {
    let icon = win_icon_extractor::extract_icon_best(path, ICON_EDGE).ok()?;
    let png = win_icon_extractor::encode_png(&icon.rgba, icon.width, icon.height).ok()?;
    (png.len() <= MAX_ICON_BYTES).then_some(png)
}

#[cfg(not(windows))]
fn extract(_: &Path) -> Option<Vec<u8>> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn refuses_nonlocal_paths() {
        assert!(png(Path::new("app.exe")).is_none());
        assert!(png(Path::new(r"\\server\share\app.exe")).is_none());
    }
    #[cfg(windows)]
    #[test]
    fn real_executable_icon_is_bounded_and_decodable_with_alpha() {
        let path = std::env::var("WINDIR").unwrap() + r"\explorer.exe";
        let bytes = png(Path::new(&path)).unwrap();
        assert!(bytes.len() <= MAX_ICON_BYTES);
        let image = image::load_from_memory(&bytes).unwrap().into_rgba8();
        assert_eq!(image.dimensions(), (ICON_EDGE, ICON_EDGE));
        assert!(image.pixels().any(|p| p.0[3] != 0));
        assert!(png(Path::new(r"C:\missing-sayin-icon-test.exe")).is_none());
    }
}
