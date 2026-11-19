//! One application-owned preferences entity; disk I/O runs off the UI thread.
use crate::localization::{Catalogs, LanguagePreference, Locale, Message, system_languages};
use atomic_write_file::AtomicWriteFile;
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    fs,
    io::{self, Read, Write},
    path::{Path, PathBuf},
};
use voicelink_receiver::windows_image_delivery::{
    ImageDeliveryMode, ImageDeliverySettings, SharedImageDeliverySettings, is_executable_path,
    same_executable,
};
use yue_component::gpui_kit::{AppContext as _, Context, PathPromptOptions, Task};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AppearancePreference {
    #[default]
    System,
    Light,
    Dark,
}

impl AppearancePreference {
    pub fn key(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }
    pub fn resolve(
        self,
        appearance: yue_component::gpui_kit::WindowAppearance,
    ) -> yue_component::ThemeMode {
        match self {
            Self::System => yue_component::ThemePreference::System.resolve(appearance),
            Self::Light => yue_component::ThemeMode::Light,
            Self::Dark => yue_component::ThemeMode::Dark,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CloseBehavior {
    #[default]
    Ask,
    Tray,
    Quit,
}
impl CloseBehavior {
    pub fn key(self) -> &'static str {
        match self {
            Self::Ask => "ask",
            Self::Tray => "tray",
            Self::Quit => "quit",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Preferences {
    schema_version: u32,
    language: LanguagePreference,
    appearance: AppearancePreference,
    #[serde(default)]
    close_behavior: CloseBehavior,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    image_directory: Option<PathBuf>,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    image_applications: BTreeMap<String, ImageDeliveryMode>,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            schema_version: 1,
            language: LanguagePreference::System,
            appearance: AppearancePreference::System,
            close_behavior: CloseBehavior::Ask,
            image_directory: None,
            image_applications: BTreeMap::new(),
        }
    }
}

impl Preferences {
    pub fn close_behavior(&self) -> CloseBehavior {
        self.close_behavior
    }
    pub fn with_close_behavior(mut self, value: CloseBehavior) -> Self {
        self.close_behavior = value;
        self
    }
    pub fn language(&self) -> LanguagePreference {
        self.language
    }
    pub fn appearance(&self) -> AppearancePreference {
        self.appearance
    }
    pub fn with_language(mut self, language: LanguagePreference) -> Self {
        self.language = language;
        self
    }
    pub fn with_appearance(mut self, appearance: AppearancePreference) -> Self {
        self.appearance = appearance;
        self
    }
}

#[derive(Clone)]
pub struct PreferencesFile {
    path: PathBuf,
}

impl PreferencesFile {
    pub fn in_directory(directory: &Path) -> Self {
        Self {
            path: directory.join("preferences.json"),
        }
    }
    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn load(&self) -> io::Result<Preferences> {
        let file = match fs::File::open(&self.path) {
            Ok(file) => file,
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                return Ok(Preferences::default());
            }
            Err(error) => return Err(error),
        };
        let mut bytes = Vec::new();
        file.take(65537).read_to_end(&mut bytes)?;
        if bytes.len() > 65536 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "preferences exceed 64 KiB",
            ));
        }
        let preferences: Preferences = serde_json::from_slice(&bytes)?;
        if preferences.schema_version != 1 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unsupported preferences schema",
            ));
        }
        Ok(preferences)
    }

    pub fn save(&self, preferences: &Preferences) -> io::Result<()> {
        let bytes = serde_json::to_vec_pretty(preferences)?;
        let directory = self
            .path
            .parent()
            .ok_or_else(|| io::Error::other("missing settings directory"))?;
        fs::create_dir_all(directory)?;
        let mut file = AtomicWriteFile::open(&self.path)?;
        file.write_all(&bytes)?;
        file.commit()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StorageIssue {
    Read,
    Save,
}

pub struct PreferencesState {
    value: Preferences,
    file: PreferencesFile,
    catalogs: Catalogs,
    preferred_languages: Vec<String>,
    issue: Option<StorageIssue>,
    task: Option<Task<()>>,
    default_image_directory: Option<PathBuf>,
    image_delivery: SharedImageDeliverySettings,
    image_picker: Option<Task<()>>,
    image_picker_issue: Option<Message>,
}

impl PreferencesState {
    /// Loading happens before the event loop starts. Invalid files are retained.
    pub fn new(file: PreferencesFile) -> Self {
        let (value, issue) = match file.load() {
            Ok(value) => (value, None),
            Err(error) => {
                eprintln!("Read preferences: {error}");
                (Preferences::default(), Some(StorageIssue::Read))
            }
        };
        let default_image_directory = directories::UserDirs::new()
            .and_then(|dirs| dirs.download_dir().map(|path| path.join("SayIn")));
        let mut state = Self {
            value,
            file,
            catalogs: Catalogs::default(),
            preferred_languages: system_languages(),
            issue,
            task: None,
            default_image_directory,
            image_delivery: Default::default(),
            image_picker: None,
            image_picker_issue: None,
        };
        state.sync_image_delivery();
        state
    }

    pub fn value(&self) -> &Preferences {
        &self.value
    }
    pub fn issue(&self) -> Option<StorageIssue> {
        self.issue
    }
    pub fn is_saving(&self) -> bool {
        self.task.is_some()
    }
    pub fn is_editable(&self) -> bool {
        self.issue != Some(StorageIssue::Read)
    }

    pub fn image_directory(&self) -> Option<&Path> {
        if !self.is_editable() {
            return None;
        }
        self.value
            .image_directory
            .as_deref()
            .or(self.default_image_directory.as_deref())
    }

    pub fn shared_image_delivery(&self) -> SharedImageDeliverySettings {
        self.image_delivery.clone()
    }

    fn sync_image_delivery(&mut self) {
        let settings = ImageDeliverySettings::new(
            self.image_directory().map(Path::to_path_buf),
            self.value.image_applications.clone(),
        );
        *self
            .image_delivery
            .write()
            .expect("image delivery snapshot") = settings;
    }

    pub fn image_applications(&self) -> &BTreeMap<String, ImageDeliveryMode> {
        &self.value.image_applications
    }

    pub fn image_picker_busy(&self) -> bool {
        self.image_picker.is_some()
    }

    pub fn image_picker_issue(&self) -> Option<Message> {
        self.image_picker_issue
    }

    pub fn set_image_application(
        &mut self,
        executable: &str,
        mode: Option<ImageDeliveryMode>,
        cx: &mut Context<Self>,
    ) {
        if !is_executable_path(Path::new(executable)) {
            return;
        }
        let mut next = self.value.clone();
        let key = next
            .image_applications
            .keys()
            .find(|key| same_executable(key, executable))
            .cloned();
        if let Some(mode) = mode {
            next.image_applications
                .insert(key.unwrap_or_else(|| executable.to_owned()), mode);
        } else if let Some(key) = key {
            next.image_applications.remove(&key);
        }
        self.save(next, cx);
    }

    pub fn pick_image_path(&mut self, mode: Option<ImageDeliveryMode>, cx: &mut Context<Self>) {
        if !self.is_editable() || self.image_picker_busy() {
            return;
        }
        self.image_picker_issue = None;
        let application = mode.is_some();
        let selecting = cx.prompt_for_paths(PathPromptOptions {
            files: application,
            directories: !application,
            multiple: false,
            prompt: Some(
                self.text(if application {
                    Message::ImageChooseApplication
                } else {
                    Message::ImageDirectory
                })
                .into(),
            ),
        });
        self.image_picker = Some(cx.spawn(async move |owner, cx| {
            let result = selecting.await;
            let _ = owner.update(cx, |this, cx| {
                this.image_picker = None;
                match result {
                    Ok(Ok(Some(mut paths))) if paths.len() == 1 && paths[0].is_absolute() => {
                        let path = paths.pop().expect("one selected path");
                        if let Some(mode) = mode {
                            if is_executable_path(&path) {
                                this.set_image_application(
                                    path.to_str().expect("validated executable"),
                                    Some(mode),
                                    cx,
                                );
                            } else {
                                this.image_picker_issue = Some(Message::ImageChooseExecutable);
                            }
                        } else {
                            let mut next = this.value.clone();
                            next.image_directory = Some(path);
                            this.save(next, cx);
                        }
                    }
                    Ok(Ok(None)) => {}
                    _ => {
                        this.image_picker_issue = Some(if application {
                            Message::ImageApplicationSelectionFailed
                        } else {
                            Message::ImageDirectorySelectionFailed
                        })
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }
    pub fn locale(&self) -> Locale {
        self.value.language.resolve(&self.preferred_languages)
    }
    pub fn text(&self, message: Message) -> String {
        self.catalogs.text(self.locale(), message)
    }
    pub fn version(&self) -> String {
        self.catalogs.version(self.locale())
    }
    pub fn receipt_summary(&self, receipt: &voicelink_receiver::protocol::Receipt) -> String {
        self.catalogs.receipt_summary(self.locale(), receipt)
    }
    pub fn receipt_help(&self, receipt: &voicelink_receiver::protocol::Receipt) -> String {
        self.catalogs.receipt_help(self.locale(), receipt)
    }
    pub fn history_count(&self, count: usize) -> String {
        self.catalogs.history_count(self.locale(), count)
    }
    pub fn history_time(&self, executed: bool, timestamp: &str) -> String {
        self.catalogs
            .history_time(self.locale(), executed, timestamp)
    }

    pub fn refresh_system_languages(&mut self, cx: &mut Context<Self>) {
        let languages = system_languages();
        if languages != self.preferred_languages {
            self.preferred_languages = languages;
            cx.notify();
        }
    }

    pub fn save(&mut self, next: Preferences, cx: &mut Context<Self>) {
        if !self.is_editable() || next == self.value {
            return;
        }
        self.value = next;
        self.issue = None;
        self.sync_image_delivery();
        if !self.is_saving() {
            self.start_write(cx);
        }
        cx.notify();
    }

    fn start_write(&mut self, cx: &mut Context<Self>) {
        let next = self.value.clone();
        let written = next.clone();
        let file = self.file.clone();
        let operation = cx.background_spawn(async move { file.save(&next) });
        self.task = Some(cx.spawn(async move |owner, cx| {
            let outcome = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.task = None;
                match outcome {
                    Ok(()) => {
                        if this.value != written {
                            // Only one write is in flight; coalesce subsequent
                            // changes into the latest complete snapshot.
                            this.start_write(cx);
                        }
                    }
                    Err(error) => {
                        eprintln!("Save preferences: {error}");
                        this.issue = Some(StorageIssue::Save);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }

    pub fn retry(&mut self, cx: &mut Context<Self>) {
        if self.is_saving() {
            return;
        }
        if self.issue == Some(StorageIssue::Save) {
            self.issue = None;
            self.start_write(cx);
            cx.notify();
            return;
        }
        let file = self.file.clone();
        let operation = cx.background_spawn(async move { file.load() });
        self.task = Some(cx.spawn(async move |owner, cx| {
            let outcome = operation.await;
            let _ = owner.update(cx, |this, cx| {
                this.task = None;
                match outcome {
                    Ok(value) => {
                        this.value = value;
                        this.issue = None;
                        this.sync_image_delivery();
                    }
                    Err(error) => {
                        eprintln!("Reload preferences: {error}");
                        this.issue = Some(StorageIssue::Read);
                    }
                }
                cx.notify();
            });
        }));
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Directory(PathBuf);
    impl Directory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "voicelink-settings-{}-{}-{}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_nanos(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn first_run_and_atomic_replacement_survive_restart() {
        let directory = Directory::new();
        let file = PreferencesFile::in_directory(&directory.0);
        assert_eq!(file.load().unwrap(), Preferences::default());
        let value = Preferences::default()
            .with_language(LanguagePreference::Chinese)
            .with_appearance(AppearancePreference::Dark);
        file.save(&value).unwrap();
        assert_eq!(
            PreferencesFile::in_directory(&directory.0).load().unwrap(),
            value
        );
        let replacement = value.with_language(LanguagePreference::English);
        file.save(&replacement).unwrap();
        assert_eq!(file.load().unwrap(), replacement);
        assert_eq!(fs::read_dir(&directory.0).unwrap().count(), 1);
    }

    #[gpui_kit::test]
    fn folder_picker_updates_the_receiver_and_persists_the_selection(
        cx: &mut gpui_kit::TestAppContext,
    ) {
        let directory = Directory::new();
        let file = PreferencesFile::in_directory(&directory.0);
        let state = cx.update(|cx| cx.new(|_| PreferencesState::new(file.clone())));
        let selected = directory.0.join("接收 图片");
        let snapshot = state.read_with(cx, |state, _| state.shared_image_delivery());

        state.update(cx, |state, cx| state.pick_image_path(None, cx));
        cx.simulate_path_prompt_response(|options| {
            assert!(options.directories && !options.files && !options.multiple);
            Some(vec![selected.clone()])
        });
        cx.run_until_parked();
        assert_eq!(
            snapshot.read().unwrap().directory(),
            Some(selected.as_path())
        );
        assert_eq!(file.load().unwrap().image_directory, Some(selected));
    }

    #[gpui_kit::test]
    fn app_picker_persists_updates_and_reset_by_executable(cx: &mut gpui_kit::TestAppContext) {
        use ImageDeliveryMode::{CopyPath, PasteImage};
        let directory = Directory::new();
        let file = PreferencesFile::in_directory(&directory.0);
        let state = cx.update(|cx| cx.new(|_| PreferencesState::new(file.clone())));
        let rule = r"C:\Tools\Editor.exe";
        let snapshot = state.read_with(cx, |state, _| state.shared_image_delivery());

        state.update(cx, |state, cx| state.pick_image_path(Some(CopyPath), cx));
        cx.simulate_path_prompt_response(|options| {
            assert!(options.files && !options.directories && !options.multiple);
            Some(vec![PathBuf::from(rule)])
        });
        cx.run_until_parked();
        assert_eq!(snapshot.read().unwrap().mode_for(rule), CopyPath);
        assert_eq!(file.load().unwrap().image_applications[rule], CopyPath);

        let alias = r"c:\TOOLS\editor.EXE";
        state.update(cx, |state, cx| {
            state.set_image_application(alias, Some(PasteImage), cx)
        });
        cx.run_until_parked();
        assert_eq!(
            file.load().unwrap().image_applications,
            BTreeMap::from([(rule.to_owned(), PasteImage)])
        );
        state.update(cx, |state, cx| state.set_image_application(alias, None, cx));
        cx.run_until_parked();
        assert!(file.load().unwrap().image_applications.is_empty());
        assert_eq!(snapshot.read().unwrap().mode_for(rule), PasteImage);
    }

    #[test]
    fn invalid_unknown_and_oversize_files_are_rejected_without_overwriting() {
        let directory = Directory::new();
        let file = PreferencesFile::in_directory(&directory.0);
        for bytes in [
            b"{corrupted".to_vec(),
            b"{\"schema_version\":99,\"language\":\"en\",\"appearance\":\"system\"}".to_vec(),
            b"{\"schema_version\":1,\"language\":\"fr\",\"appearance\":\"system\"}".to_vec(),
            vec![b' '; 65537],
        ] {
            fs::write(file.path(), &bytes).unwrap();
            assert!(file.load().is_err());
            assert_eq!(fs::read(file.path()).unwrap(), bytes);
        }
    }

    #[test]
    fn save_reports_unusable_directory_and_preserves_existing_file() {
        let directory = Directory::new();
        let blocker = directory.0.join("not-a-directory");
        fs::write(&blocker, b"keep").unwrap();
        assert!(
            PreferencesFile::in_directory(&blocker)
                .save(&Preferences::default())
                .is_err()
        );
        assert_eq!(fs::read(blocker).unwrap(), b"keep");
    }
}
