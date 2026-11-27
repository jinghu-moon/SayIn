//! Windows input delivery is selected from the foreground executable and local settings.
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};

const FAST_PASTE_MIN_UTF16_UNITS: usize = 32;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ImageDeliveryMode {
    PasteImage,
    CopyPath,
}

#[derive(Clone, Default)]
pub struct DeliverySettings {
    directory: Option<PathBuf>,
    applications: BTreeMap<String, ImageDeliveryMode>,
    fast_paste_applications: BTreeSet<String>,
}

pub type SharedDeliverySettings = Arc<RwLock<DeliverySettings>>;

impl DeliverySettings {
    pub fn new(
        directory: Option<PathBuf>,
        applications: BTreeMap<String, ImageDeliveryMode>,
    ) -> Self {
        Self {
            directory,
            applications,
            fast_paste_applications: BTreeSet::new(),
        }
    }

    pub fn with_fast_paste_applications(mut self, applications: BTreeSet<String>) -> Self {
        self.fast_paste_applications = applications;
        self
    }

    pub fn uses_fast_paste(&self, executable: &str, text: &str) -> bool {
        is_executable_path(Path::new(executable))
            && self
                .fast_paste_applications
                .iter()
                .any(|path| same_executable(path, executable))
            && text
                .encode_utf16()
                .nth(FAST_PASTE_MIN_UTF16_UNITS - 1)
                .is_some()
    }

    pub fn directory(&self) -> Option<&Path> {
        self.directory.as_deref()
    }

    pub fn mode_for(&self, executable: &str) -> ImageDeliveryMode {
        if let Some((_, mode)) = self
            .applications
            .iter()
            .find(|(path, _)| same_executable(path, executable))
        {
            return *mode;
        }
        if Path::new(executable)
            .file_name()
            .is_some_and(|name| name.eq_ignore_ascii_case("WindowsTerminal.exe"))
        {
            ImageDeliveryMode::CopyPath
        } else {
            ImageDeliveryMode::PasteImage
        }
    }
}

pub fn is_executable_path(path: &Path) -> bool {
    path.is_absolute()
        && path
            .extension()
            .is_some_and(|extension| extension.eq_ignore_ascii_case("exe"))
        && path.to_str().is_some_and(|value| !value.contains('\0'))
}

pub fn same_executable(left: &str, right: &str) -> bool {
    use windows::Win32::Globalization::{CSTR_EQUAL, CompareStringOrdinal};
    let left = left.encode_utf16().collect::<Vec<_>>();
    let right = right.encode_utf16().collect::<Vec<_>>();
    // Windows compares executable paths ordinally, independent of UI locale.
    unsafe { CompareStringOrdinal(&left, &right, true) == CSTR_EQUAL }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fast_paste_requires_the_selected_executable_and_32_utf16_units() {
        let app = r"C:\Éditeur\editor.exe";
        let text = "😀".repeat(16);
        assert!(!DeliverySettings::default().uses_fast_paste(app, &text));
        let enabled =
            DeliverySettings::default().with_fast_paste_applications(BTreeSet::from([app.into()]));
        assert!(enabled.uses_fast_paste(r"c:\éditeur\EDITOR.EXE", &text));
        assert!(!enabled.uses_fast_paste(app, &format!("{}a", "😀".repeat(15))));
        assert!(!enabled.uses_fast_paste(r"C:\Other\editor.exe", &text));
    }

    #[test]
    fn exact_app_overrides_win_over_defaults_and_other_apps_with_the_same_filename() {
        use ImageDeliveryMode::{CopyPath, PasteImage};
        let settings = DeliverySettings::new(
            None,
            BTreeMap::from([
                (r"C:\Éditeur\editor.exe".into(), CopyPath),
                (r"C:\Term\WindowsTerminal.exe".into(), PasteImage),
            ]),
        );
        assert_eq!(settings.mode_for(r"c:\éditeur\EDITOR.EXE"), CopyPath);
        assert_eq!(settings.mode_for(r"C:\Other\editor.exe"), PasteImage);
        assert_eq!(
            settings.mode_for(r"C:\Term\WindowsTerminal.exe"),
            PasteImage
        );
        assert_eq!(settings.mode_for(r"C:\Other\WINDOWSTERMINAL.EXE"), CopyPath);
        assert_eq!(
            settings.mode_for(r"C:\WindowsTerminal.exe\editor.exe"),
            PasteImage
        );
    }
}
