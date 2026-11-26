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

fn extract(path: &Path) -> Option<Arc<Image>> {
    let bytes = voicelink_receiver::application_icon::webp(path)?;
    Some(Arc::new(Image::from_bytes(ImageFormat::Webp, bytes)))
}

use voicelink_receiver::application_name::display_name;

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
