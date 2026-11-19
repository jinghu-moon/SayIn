//! Image delivery is selected from the foreground executable and local settings.
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{Arc, RwLock},
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ImageDeliveryMode {
    PasteImage,
    CopyPath,
}

#[derive(Clone, Default)]
pub struct ImageDeliverySettings {
    directory: Option<PathBuf>,
    applications: BTreeMap<String, ImageDeliveryMode>,
}

pub type SharedImageDeliverySettings = Arc<RwLock<ImageDeliverySettings>>;

impl ImageDeliverySettings {
    pub fn new(
        directory: Option<PathBuf>,
        applications: BTreeMap<String, ImageDeliveryMode>,
    ) -> Self {
        Self {
            directory,
            applications,
        }
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
    fn exact_app_overrides_win_over_defaults_and_other_apps_with_the_same_filename() {
        use ImageDeliveryMode::{CopyPath, PasteImage};
        let settings = ImageDeliverySettings::new(
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
