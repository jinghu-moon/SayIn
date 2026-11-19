//! Windows platform seam: current-user DPAPI and guarded batch Unicode input.
use crate::{
    Error, Result,
    gate::InputGate,
    images::{ImageMime, payload::PreparedImage},
    ledger::{Execution, InputOutcome, Ledger, Protector},
    protocol::{KeyName, Operation},
    windows_image_delivery::{ImageDeliveryMode, SharedImageDeliverySettings},
    worker::InputAdapter,
};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
};
use windows::{
    Win32::{
        Foundation::{CloseHandle, FILETIME, HANDLE, HLOCAL, LocalFree},
        Security::{
            Cryptography::{
                CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
            },
            GetSidSubAuthority, GetSidSubAuthorityCount, GetTokenInformation, IsValidSid,
            TOKEN_MANDATORY_LABEL, TOKEN_QUERY, TokenIntegrityLevel,
        },
        System::{
            RemoteDesktop::{
                ProcessIdToSessionId, WTS_CONNECTSTATE_CLASS, WTSActive, WTSConnectState,
                WTSFreeMemory, WTSQuerySessionInformationW,
            },
            StationsAndDesktops::{
                CloseDesktop, DESKTOP_READOBJECTS, GetUserObjectInformationW, OpenInputDesktop,
                UOI_NAME,
            },
            Threading::{
                GetCurrentProcess, GetCurrentProcessId, GetProcessTimes, OpenProcess,
                OpenProcessToken, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION,
                QueryFullProcessImageNameW,
            },
        },
        UI::{
            Input::KeyboardAndMouse::{
                GetAsyncKeyState, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
                KEYEVENTF_UNICODE, SendInput, VIRTUAL_KEY, VK_CONTROL, VK_LWIN, VK_MENU, VK_RETURN,
                VK_RWIN, VK_SHIFT,
            },
            WindowsAndMessaging::{
                GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId, IsIconic, IsWindow,
            },
        },
    },
    core::{PCWSTR, PWSTR},
};
use zeroize::{Zeroize, Zeroizing};

fn platform(error: windows::core::Error) -> Error {
    Error::Platform(error.to_string())
}

pub struct Dpapi;
impl Protector for Dpapi {
    fn protect(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        dpapi(plaintext, false).map(|value| value.to_vec())
    }
    fn unprotect(&self, ciphertext: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
        dpapi(ciphertext, true)
    }
}

fn dpapi(input: &[u8], decrypt: bool) -> Result<Zeroizing<Vec<u8>>> {
    let source = CRYPT_INTEGER_BLOB {
        cbData: input
            .len()
            .try_into()
            .map_err(|_| Error::Protocol("PROTECTED_DATA_LIMIT"))?,
        pbData: input.as_ptr().cast_mut(),
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    // SAFETY: Input is live for the entire native call. DPAPI allocates output;
    // it is copied before being cleared and released with LocalFree.
    unsafe {
        if decrypt {
            CryptUnprotectData(
                &source,
                None,
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
            .map_err(platform)?;
        } else {
            CryptProtectData(
                &source,
                PCWSTR::null(),
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
            .map_err(platform)?;
        }
        if output.cbData == 0 {
            if !output.pbData.is_null() {
                let _ = LocalFree(Some(HLOCAL(output.pbData.cast())));
            }
            return Ok(Zeroizing::new(Vec::new()));
        }
        if output.pbData.is_null() {
            return Err(Error::Protocol("INVALID_DPAPI_RESULT"));
        }
        let bytes = std::slice::from_raw_parts_mut(output.pbData, output.cbData as usize);
        let value = Zeroizing::new(bytes.to_vec());
        bytes.zeroize();
        let _ = LocalFree(Some(HLOCAL(output.pbData.cast())));
        Ok(value)
    }
}

/// Refuse orphaned keys or missing keys instead of reopening an old dedup namespace.
pub fn open_ledger(directory: &Path) -> Result<Ledger> {
    fs::create_dir_all(directory)?;
    let path = directory.join("receiver.sqlite3");
    let key_path = directory.join("receiver-key.dpapi");
    let protector = Dpapi;
    let key = if path.exists() && key_path.exists() {
        let mut bytes = Vec::new();
        fs::File::open(&key_path)?
            .take(16385)
            .read_to_end(&mut bytes)?;
        if bytes.len() > 16384 {
            return Err(Error::Protocol("INVALID_LEDGER_KEY"));
        }
        protector.unprotect(&bytes)?
    } else if !path.exists() && !key_path.exists() {
        let mut key = Zeroizing::new(vec![0; 32]);
        getrandom::fill(&mut key).map_err(|error| Error::Platform(error.to_string()))?;
        let protected = protector.protect(&key)?;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&key_path)?;
        file.write_all(&protected)?;
        file.sync_all()?;
        key
    } else {
        return Err(Error::Protocol("LEDGER_IDENTITY_LOST"));
    };
    Ledger::open(&path, key, Box::new(protector))
}

/// A lease is scoped to one operation, not to a user-managed window lock.
/// Retaining it across prepare/execute prevents a focus change during the durable
/// execution-marker commit from silently retargeting the same operation.
struct ForegroundLease {
    device: String,
    request: String,
    scope: String,
    foreground: (usize, u32, u64, u32),
}
impl ForegroundLease {
    fn matches(
        &self,
        device: &str,
        request: &str,
        scope: &str,
        foreground: (usize, u32, u64, u32),
    ) -> bool {
        self.device == device
            && self.request == request
            && self.scope == scope
            && self.foreground == foreground
    }
}

#[derive(Default)]
pub struct NativeInput {
    prepared: Option<ForegroundLease>,
    application: Option<crate::history::TargetApplication>,
    image_store: Option<crate::images::storage::ImageStore>,
    image: Option<ImageDelivery>,
    image_delivery: SharedImageDeliverySettings,
    clipboard_owner: Option<crate::windows_clipboard::ClipboardOwner>,
}

struct ImageDelivery {
    directory: Option<PathBuf>,
    mime: ImageMime,
    bytes: Zeroizing<Vec<u8>>,
    paste: Option<PreparedImage>,
}

impl NativeInput {
    pub fn with_images(
        store: crate::images::storage::ImageStore,
        image_delivery: SharedImageDeliverySettings,
    ) -> Self {
        Self {
            image_store: Some(store),
            image_delivery,
            ..Self::default()
        }
    }
    fn check(&self, job: &Execution) -> Result<()> {
        if !job.image_authorized() {
            return Err(Error::Protocol("IMAGE_SESSION_ENDED"));
        }
        let lease = self
            .prepared
            .as_ref()
            .ok_or(Error::Protocol("TARGET_UNPREPARED"))?;
        if !lease.matches(job.device(), job.id(), job.target(), foreground()?) {
            return Err(Error::Protocol("FOCUS_CHANGED"));
        }
        Ok(())
    }
}

fn save_received_image(
    directory: &Path,
    mime: ImageMime,
    bytes: &[u8],
    allowed: impl Fn() -> bool,
) -> Result<PathBuf> {
    if !directory.is_absolute() {
        return Err(Error::Protocol("IMAGE_EXPORT_DIRECTORY"));
    }
    if !allowed() {
        return Err(Error::Protocol("IMAGE_EXPORT_NOT_ALLOWED"));
    }
    fs::create_dir_all(directory)?;
    let extension = match mime {
        ImageMime::Png => "png",
        ImageMime::Jpeg => "jpg",
    };
    let path = directory.join(format!("SayIn-{}.{extension}", uuid::Uuid::new_v4()));
    let mut output = atomic_write_file::AtomicWriteFile::open(&path)?;
    output.write_all(bytes)?;
    output.sync_all()?;
    if !allowed() {
        return Err(Error::Protocol("IMAGE_EXPORT_NOT_ALLOWED"));
    }
    output.commit()?;
    Ok(path)
}

fn desktop_is_interactive() -> Result<()> {
    // SAFETY: Handle is owned locally and closed on every return path.
    unsafe {
        let desktop =
            OpenInputDesktop(Default::default(), false, DESKTOP_READOBJECTS).map_err(platform)?;
        let mut name = [0u16; 256];
        let mut needed = 0;
        let result = GetUserObjectInformationW(
            HANDLE(desktop.0),
            UOI_NAME,
            Some(name.as_mut_ptr().cast()),
            512,
            Some(&mut needed),
        );
        let _ = CloseDesktop(desktop);
        result.map_err(platform)?;
        let end = name
            .iter()
            .position(|unit| *unit == 0)
            .unwrap_or(name.len());
        if !String::from_utf16_lossy(&name[..end]).eq_ignore_ascii_case("default") {
            return Err(Error::Protocol("NON_INTERACTIVE_DESKTOP"));
        }
        Ok(())
    }
}

/// Display-only metadata cache. It never creates a lease or changes the input gate.
#[derive(Default)]
pub struct ForegroundApplication {
    identity: Option<(usize, u32, u64)>,
    application: Option<crate::application_icon::ApplicationDisplay>,
}
impl ForegroundApplication {
    pub fn sample(&mut self) -> Option<crate::application_icon::ApplicationDisplay> {
        let result = self.query();
        if result.is_err() {
            self.identity = None;
            self.application = None;
        }
        result.ok().flatten()
    }

    fn query(&mut self) -> Result<Option<crate::application_icon::ApplicationDisplay>> {
        desktop_is_interactive()?;
        // SAFETY: query-only handle, closed before returning; window identities
        // are compared again after the potentially slower version-resource read.
        unsafe {
            let window = GetForegroundWindow();
            if window.0.is_null() || !IsWindow(Some(window)).as_bool() || IsIconic(window).as_bool()
            {
                return Err(Error::Protocol("NO_FOREGROUND_TARGET"));
            }
            let mut pid = 0;
            GetWindowThreadProcessId(window, Some(&mut pid));
            let mut session = 0;
            let mut own = 0;
            ProcessIdToSessionId(pid, &mut session).map_err(platform)?;
            ProcessIdToSessionId(GetCurrentProcessId(), &mut own).map_err(platform)?;
            if session != own {
                return Err(Error::Protocol("SESSION_CHANGED"));
            }
            session_is_active(own)?;
            let handle =
                OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).map_err(platform)?;
            let result = (|| {
                let (mut created, mut exited, mut kernel, mut user) = (
                    FILETIME::default(),
                    FILETIME::default(),
                    FILETIME::default(),
                    FILETIME::default(),
                );
                GetProcessTimes(handle, &mut created, &mut exited, &mut kernel, &mut user)
                    .map_err(platform)?;
                let identity = (
                    window.0 as usize,
                    pid,
                    (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime),
                );
                if self.identity != Some(identity) {
                    let mut path = vec![0u16; 32768];
                    let mut length = path.len() as u32;
                    QueryFullProcessImageNameW(
                        handle,
                        PROCESS_NAME_WIN32,
                        PWSTR(path.as_mut_ptr()),
                        &mut length,
                    )
                    .map_err(platform)?;
                    let path = String::from_utf16(&path[..length as usize])
                        .map_err(|_| Error::Protocol("INVALID_PROCESS_PATH"))?;
                    // Live draft hint and execution history use the same
                    // display extraction. Their observation times stay separate.
                    self.application =
                        crate::application_icon::ApplicationDisplay::capture(Path::new(&path));
                    self.identity = Some(identity);
                }
                let mut current_pid = 0;
                GetWindowThreadProcessId(window, Some(&mut current_pid));
                if GetForegroundWindow() != window
                    || current_pid != pid
                    || !IsWindow(Some(window)).as_bool()
                {
                    return Err(Error::Protocol("FOCUS_CHANGED"));
                }
                desktop_is_interactive()?;
                Ok(self.application.clone())
            })();
            let _ = CloseHandle(handle);
            result
        }
    }
}

fn foreground() -> Result<(usize, u32, u64, u32)> {
    desktop_is_interactive()?;
    // SAFETY: Only query access is requested; process/token handles are closed.
    // Native window IDs are queried at use time, never dereferenced in Rust.
    unsafe {
        let window = GetForegroundWindow();
        if window.0.is_null() || !IsWindow(Some(window)).as_bool() || IsIconic(window).as_bool() {
            return Err(Error::Protocol("NO_FOREGROUND_TARGET"));
        }
        let mut process = 0;
        GetWindowThreadProcessId(window, Some(&mut process));
        if process == 0 {
            return Err(Error::Protocol("NO_FOREGROUND_TARGET"));
        }
        if process == GetCurrentProcessId() {
            return Err(Error::Protocol("RECEIVER_FOCUSED"));
        }
        let mut session = 0;
        ProcessIdToSessionId(process, &mut session).map_err(platform)?;
        let mut own_session = 0;
        ProcessIdToSessionId(GetCurrentProcessId(), &mut own_session).map_err(platform)?;
        if session != own_session {
            return Err(Error::Protocol("SESSION_CHANGED"));
        }
        session_is_active(own_session)?;
        let handle =
            OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, process).map_err(platform)?;
        let result = (|| {
            let (mut created, mut exited, mut kernel, mut user) = (
                FILETIME::default(),
                FILETIME::default(),
                FILETIME::default(),
                FILETIME::default(),
            );
            GetProcessTimes(handle, &mut created, &mut exited, &mut kernel, &mut user)
                .map_err(platform)?;
            // UIPI compares integrity levels, not the target's administrator
            // flag. An already-elevated receiver may type into an equal-level
            // target; a lower-integrity receiver must still fail closed.
            if !input_integrity_allows(
                process_integrity(GetCurrentProcess())?,
                process_integrity(handle)?,
            ) {
                return Err(Error::Protocol("ELEVATED_TARGET"));
            }
            for key in [VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN] {
                if GetAsyncKeyState(key.0 as i32) < 0 {
                    return Err(Error::Protocol("MODIFIER_HELD"));
                }
            }
            let timestamp =
                (u64::from(created.dwHighDateTime) << 32) | u64::from(created.dwLowDateTime);
            Ok((window.0 as usize, process, timestamp, session))
        })();
        let _ = CloseHandle(handle);
        result
    }
}

fn input_integrity_allows(receiver: u32, target: u32) -> bool {
    target <= receiver
}

fn process_integrity(process: HANDLE) -> Result<u32> {
    // SAFETY: The token is query-only and always closed. The aligned, bounded
    // buffer owns TOKEN_MANDATORY_LABEL and its SID until the RID is copied.
    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(process, TOKEN_QUERY, &mut token).map_err(platform)?;
        let result = (|| {
            let mut buffer = [0usize; 128];
            let capacity = std::mem::size_of_val(&buffer);
            let mut returned = 0;
            GetTokenInformation(
                token,
                TokenIntegrityLevel,
                Some(buffer.as_mut_ptr().cast()),
                capacity as u32,
                &mut returned,
            )
            .map_err(platform)?;
            if returned as usize > capacity
                || (returned as usize) < std::mem::size_of::<TOKEN_MANDATORY_LABEL>()
            {
                return Err(Error::Protocol("INVALID_INTEGRITY_LABEL"));
            }
            let label = &*buffer.as_ptr().cast::<TOKEN_MANDATORY_LABEL>();
            let start = buffer.as_ptr() as usize;
            let end = start + returned as usize;
            let sid = label.Label.Sid;
            let address = sid.0 as usize;
            if address < start || address.checked_add(8).is_none_or(|limit| limit > end) {
                return Err(Error::Protocol("INVALID_INTEGRITY_LABEL"));
            }
            let count = *GetSidSubAuthorityCount(sid);
            if count == 0
                || count > 15
                || address + 8 + 4 * usize::from(count) > end
                || !IsValidSid(sid).as_bool()
            {
                return Err(Error::Protocol("INVALID_INTEGRITY_LABEL"));
            }
            Ok(GetSidSubAuthority(sid, u32::from(count) - 1).read_unaligned())
        })();
        let _ = CloseHandle(token);
        result
    }
}

fn session_is_active(session: u32) -> Result<()> {
    let mut buffer = PWSTR::null();
    let mut bytes = 0;
    // SAFETY: WTS allocates the query buffer; it is size-checked, copied and
    // freed on every successful query. No pointer escapes this function.
    unsafe {
        WTSQuerySessionInformationW(None, session, WTSConnectState, &mut buffer, &mut bytes)
            .map_err(platform)?;
        let active = !buffer.is_null()
            && bytes as usize >= std::mem::size_of::<WTS_CONNECTSTATE_CLASS>()
            && buffer.0.cast::<WTS_CONNECTSTATE_CLASS>().read_unaligned() == WTSActive;
        if !buffer.is_null() {
            WTSFreeMemory(buffer.0.cast());
        }
        if !active {
            return Err(Error::Protocol("SESSION_INACTIVE"));
        }
    }
    Ok(())
}

fn keyboard(code: u16, unicode: bool, up: bool) -> INPUT {
    let flags = if unicode {
        KEYEVENTF_UNICODE
    } else {
        Default::default()
    } | if up {
        KEYEVENTF_KEYUP
    } else {
        Default::default()
    };
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: if unicode {
                    VIRTUAL_KEY(0)
                } else {
                    VIRTUAL_KEY(code)
                },
                wScan: if unicode { code } else { 0 },
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

fn events(operation: &Operation) -> Vec<INPUT> {
    match operation {
        Operation::Text { text, .. } | Operation::AutoText { text, .. } => text
            .encode_utf16()
            .flat_map(|code| [keyboard(code, true, false), keyboard(code, true, true)])
            .collect(),
        Operation::Key {
            name: KeyName::Enter,
        } => vec![
            keyboard(VK_RETURN.0, false, false),
            keyboard(VK_RETURN.0, false, true),
        ],
        Operation::Image { .. } => vec![
            keyboard(VK_CONTROL.0, false, false),
            keyboard(0x56, false, false),
            keyboard(0x56, false, true),
            keyboard(VK_CONTROL.0, false, true),
        ],
    }
}

/// Only release keys still down in the accepted prefix of our Ctrl+V batch.
/// Never submit another key-down or release a key we did not press.
fn paste_release_events(inserted: usize) -> Vec<INPUT> {
    match inserted {
        1 | 3 => vec![keyboard(VK_CONTROL.0, false, true)],
        2 => vec![
            keyboard(0x56, false, true),
            keyboard(VK_CONTROL.0, false, true),
        ],
        _ => Vec::new(),
    }
}

impl InputAdapter for NativeInput {
    fn prepared_application(&self) -> Option<crate::history::TargetApplication> {
        self.application.clone()
    }
    fn preflight(&mut self, job: &Execution) -> std::result::Result<(), &'static str> {
        self.prepared = None;
        self.application = None;
        self.image = None;
        let image = if let Operation::Image { image } = job.operation() {
            let store = self.image_store.as_ref().ok_or("image_unavailable")?;
            let bytes = store
                .read(job.device(), image, &Dpapi)
                .map_err(|_| "image_storage_failed")?;
            if self.clipboard_owner.is_none() {
                self.clipboard_owner = Some(
                    crate::windows_clipboard::ClipboardOwner::new()
                        .map_err(|_| "clipboard_owner_failed")?,
                );
            }
            Some((image, bytes))
        } else {
            None
        };
        let identity = foreground().map_err(preflight_reason)?;
        let application = application_at(identity);
        if let Some((image, bytes)) = image {
            let application = application
                .as_ref()
                .map_err(|_| "foreground_guard_failed")?;
            let (directory, mode) = {
                let settings = self
                    .image_delivery
                    .read()
                    .map_err(|_| "image_export_failed")?;
                (
                    settings.directory().map(Path::to_path_buf),
                    settings.mode_for(&application.executable),
                )
            };
            let paste = match mode {
                ImageDeliveryMode::CopyPath => None,
                ImageDeliveryMode::PasteImage => Some(
                    image
                        .metadata
                        .prepare_image(&bytes)
                        .map_err(|_| "image_invalid")?,
                ),
            };
            let current = foreground().map_err(preflight_reason)?;
            if identity != current {
                return Err("foreground_guard_failed");
            }
            self.image = Some(ImageDelivery {
                directory,
                mime: image.metadata.mime,
                bytes,
                paste,
            });
        }
        self.application = application.ok();
        self.prepared = Some(ForegroundLease {
            device: job.device().into(),
            request: job.id().into(),
            scope: job.target().into(),
            foreground: identity,
        });
        Ok(())
    }
    fn execute(&mut self, job: &Execution, gate: &InputGate) -> InputOutcome {
        let prepared_image = self.image.take();
        let fence = gate.snapshot();
        if gate.is_paused() {
            return InputOutcome::Failed {
                reason: "paused_before_input",
            };
        }
        if self.check(job).is_err() {
            return InputOutcome::Failed {
                reason: "foreground_guard_failed",
            };
        }
        let allowed = || self.check(job).is_ok() && !gate.is_paused() && gate.snapshot() == fence;
        let published = match prepared_image {
            Some(ImageDelivery {
                directory,
                mime,
                bytes,
                paste,
            }) => {
                let Some(directory) = directory else {
                    return InputOutcome::Failed {
                        reason: "image_export_failed",
                    };
                };
                let path = match save_received_image(&directory, mime, &bytes, allowed) {
                    Ok(path) => path,
                    Err(Error::Protocol("IMAGE_EXPORT_NOT_ALLOWED")) => {
                        return InputOutcome::Failed {
                            reason: "foreground_guard_failed",
                        };
                    }
                    Err(_) => {
                        return InputOutcome::Failed {
                            reason: "image_export_failed",
                        };
                    }
                };
                let owner = self
                    .clipboard_owner
                    .as_ref()
                    .expect("prepared owner")
                    .window();
                let Some(image) = paste else {
                    return match crate::windows_clipboard::publish_path(owner, &path, allowed) {
                        Ok(_) => InputOutcome::Injected {
                            method: "clipboard_image_path",
                        },
                        Err(_) => InputOutcome::Failed {
                            reason: "image_saved_path_unavailable",
                        },
                    };
                };
                match crate::windows_clipboard::publish(owner, &image, allowed) {
                    Ok(published) => Some(published),
                    Err(_) => {
                        return InputOutcome::Failed {
                            reason: "image_saved_paste_unavailable",
                        };
                    }
                }
            }
            None if matches!(job.operation(), Operation::Image { .. }) => {
                return InputOutcome::Failed {
                    reason: "image_unprepared",
                };
            }
            None => None,
        };
        let mut events = events(job.operation());
        // Foreground/session checks can cross a thread boundary or take time.
        // Recheck a newly requested pause as close to SendInput as possible.
        if gate.is_paused()
            || gate.snapshot() != fence
            || self.check(job).is_err()
            || published.as_ref().is_some_and(|value| !value.unchanged())
        {
            for event in &mut events {
                event.Anonymous = INPUT_0::default();
            }
            return InputOutcome::Failed {
                reason: if published.is_some() {
                    "clipboard_changed_not_pasted"
                } else {
                    "paused_before_input"
                },
            };
        }
        // SAFETY: A bounded initialized INPUT array is passed with the exact
        // native structure size. All text is KEYEVENTF_UNICODE literal content.
        let inserted = unsafe { SendInput(&events, std::mem::size_of::<INPUT>() as i32) } as usize;
        let count = events.len();
        // Clear scans containing the transient plaintext after the native call.
        for event in &mut events {
            event.Anonymous = INPUT_0::default();
        }
        if inserted == 0 {
            return InputOutcome::Failed {
                reason: if published.is_some() {
                    "clipboard_changed_not_pasted"
                } else {
                    "send_input_rejected"
                },
            };
        }
        if inserted != count || self.check(job).is_err() {
            if published.is_some() && inserted < count {
                let release = paste_release_events(inserted);
                unsafe {
                    SendInput(&release, std::mem::size_of::<INPUT>() as i32);
                }
            }
            return InputOutcome::Uncertain {
                reason: "partial_input_or_focus_changed",
            };
        }
        self.image = None;
        InputOutcome::Injected {
            method: if published.is_some() {
                "clipboard_image"
            } else {
                "unicode"
            },
        }
    }
}

fn preflight_reason(error: Error) -> &'static str {
    match error {
        Error::Protocol("NO_FOREGROUND_TARGET") => "target_missing",
        Error::Protocol("RECEIVER_FOCUSED") => "receiver_focused",
        Error::Protocol("ELEVATED_TARGET") => "elevated_target",
        Error::Protocol("MODIFIER_HELD") => "modifier_held",
        Error::Protocol("SESSION_CHANGED" | "SESSION_INACTIVE" | "NON_INTERACTIVE_DESKTOP") => {
            "session_unavailable"
        }
        _ => "foreground_guard_failed",
    }
}

fn application_at(identity: (usize, u32, u64, u32)) -> Result<crate::history::TargetApplication> {
    use windows::Win32::Foundation::HWND;
    // SAFETY: Bounded query buffers, owned process handle, no cross-process text
    // messaging. Metadata is accepted only while the validated identity matches.
    unsafe {
        let process =
            OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, identity.1).map_err(platform)?;
        let mut path = vec![0u16; 32768];
        let mut length = path.len() as u32;
        let result = QueryFullProcessImageNameW(
            process,
            PROCESS_NAME_WIN32,
            PWSTR(path.as_mut_ptr()),
            &mut length,
        );
        let _ = CloseHandle(process);
        result.map_err(platform)?;
        let mut title = [0u16; 1024];
        let count = GetWindowTextW(HWND(identity.0 as *mut _), &mut title).max(0) as usize;
        let executable = String::from_utf16_lossy(&path[..length as usize]);
        let display =
            crate::application_icon::ApplicationDisplay::capture(std::path::Path::new(&executable));
        if foreground()? != identity {
            return Err(Error::Protocol("FOCUS_CHANGED"));
        }
        let mut target = crate::history::TargetApplication::new(
            executable,
            String::from_utf16_lossy(&title[..count]),
            identity.1,
            identity.2,
        );
        target.display = display;
        Ok(target)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::TextSource;

    #[test]
    fn received_files_keep_original_bytes_and_guard_cancellation() {
        let directory =
            std::env::temp_dir().join(format!("sayin-image-export-{}", uuid::Uuid::new_v4()));
        let cancelled = std::cell::Cell::new(false);
        assert!(matches!(
            save_received_image(&directory, ImageMime::Png, b"cancelled", || !cancelled
                .replace(true)),
            Err(Error::Protocol("IMAGE_EXPORT_NOT_ALLOWED"))
        ));
        assert_eq!(fs::read_dir(&directory).unwrap().count(), 0);

        for (mime, extension) in [(ImageMime::Png, "png"), (ImageMime::Jpeg, "jpg")] {
            let path = save_received_image(&directory, mime, b"original bytes", || true).unwrap();
            assert_eq!(path.extension().unwrap(), extension);
            assert_eq!(fs::read(&path).unwrap(), b"original bytes");
            assert!(save_received_image(&path, mime, b"different bytes", || true).is_err());
            assert_eq!(fs::read(path).unwrap(), b"original bytes");
        }
        fs::remove_dir_all(&directory).unwrap();
    }

    #[test]
    fn partial_paste_releases_only_our_still_pressed_keys_without_repeating_paste() {
        for (inserted, expected) in [
            (0, vec![]),
            (1, vec![VK_CONTROL.0]),
            (2, vec![0x56, VK_CONTROL.0]),
            (3, vec![VK_CONTROL.0]),
            (4, vec![]),
        ] {
            let release = paste_release_events(inserted);
            let codes = release
                .iter()
                .map(|event| {
                    // SAFETY: The helper constructs only keyboard INPUT unions.
                    let key = unsafe { event.Anonymous.ki };
                    assert_eq!(event.r#type, INPUT_KEYBOARD);
                    assert_eq!(key.dwFlags, KEYEVENTF_KEYUP);
                    key.wVk.0
                })
                .collect::<Vec<_>>();
            assert_eq!(codes, expected);
        }
    }

    #[test]
    fn input_permissions_follow_uipi_equal_or_lower_integrity_rule() {
        let (low, medium, high, system) = (0x1000, 0x2000, 0x3000, 0x4000);
        for (receiver, target, allowed) in [
            (medium, medium, true),
            (high, high, true),
            (high, medium, true),
            (medium, low, true),
            (medium, high, false),
            (low, medium, false),
            (high, system, false),
            (medium, medium + 0x100, false),
        ] {
            assert_eq!(input_integrity_allows(receiver, target), allowed);
        }
    }

    #[test]
    fn current_process_integrity_is_readable_with_query_only_token() {
        assert!(process_integrity(unsafe { GetCurrentProcess() }).unwrap() >= 0x1000);
    }

    #[test]
    fn preflight_errors_expose_actionable_codes_without_platform_details() {
        for (code, reason) in [
            ("NO_FOREGROUND_TARGET", "target_missing"),
            ("RECEIVER_FOCUSED", "receiver_focused"),
            ("ELEVATED_TARGET", "elevated_target"),
            ("MODIFIER_HELD", "modifier_held"),
            ("SESSION_CHANGED", "session_unavailable"),
            ("SESSION_INACTIVE", "session_unavailable"),
            ("NON_INTERACTIVE_DESKTOP", "session_unavailable"),
            ("UNKNOWN", "foreground_guard_failed"),
        ] {
            assert_eq!(preflight_reason(Error::Protocol(code)), reason);
        }
    }

    #[test]
    fn foreground_lease_rejects_cross_request_scope_and_reused_native_identity() {
        let lease = ForegroundLease {
            device: "phone-a".into(),
            request: "request-a".into(),
            scope: "activation-a".into(),
            foreground: (100, 200, 300, 400),
        };
        assert!(lease.matches("phone-a", "request-a", "activation-a", (100, 200, 300, 400)));
        for (device, request, scope, identity) in [
            ("phone-b", "request-a", "activation-a", (100, 200, 300, 400)),
            ("phone-a", "request-b", "activation-a", (100, 200, 300, 400)),
            ("phone-a", "request-a", "activation-b", (100, 200, 300, 400)),
            ("phone-a", "request-a", "activation-a", (101, 200, 300, 400)),
            ("phone-a", "request-a", "activation-a", (100, 201, 300, 400)),
            ("phone-a", "request-a", "activation-a", (100, 200, 301, 400)),
            ("phone-a", "request-a", "activation-a", (100, 200, 300, 401)),
        ] {
            assert!(!lease.matches(device, request, scope, identity));
        }
        // A distinct operation acquires its own foreground, rather than reusing a window lock.
        let next = ForegroundLease {
            request: "request-b".into(),
            foreground: (101, 201, 301, 400),
            ..lease
        };
        assert!(next.matches("phone-a", "request-b", "activation-a", (101, 201, 301, 400)));
    }

    #[test]
    fn dpapi_round_trip_and_tamper_rejection() {
        let source = b"private phone text";
        let protected = Dpapi.protect(source).unwrap();
        assert_ne!(protected, source);
        assert_eq!(&**Dpapi.unprotect(&protected).unwrap(), source);
        let mut corrupted = protected;
        let end = corrupted.len() - 1;
        corrupted[end] ^= 1;
        assert!(Dpapi.unprotect(&corrupted).is_err());
    }

    #[test]
    fn utf16_surrogates_are_paired_and_commands_remain_literal() {
        let text = "中文😀{ENTER}";
        let inputs = events(&Operation::Text {
            text: text.into(),
            src: TextSource::Ime,
        });
        assert_eq!(inputs.len(), text.encode_utf16().count() * 2);
        for (pair, code) in inputs.as_chunks::<2>().0.iter().zip(text.encode_utf16()) {
            // SAFETY: Events are constructed above with the keyboard union arm.
            unsafe {
                assert_eq!(pair[0].Anonymous.ki.wScan, code);
                assert_eq!(pair[1].Anonymous.ki.wScan, code);
                assert_eq!(pair[0].Anonymous.ki.wVk.0, 0);
                assert!(pair[1].Anonymous.ki.dwFlags.contains(KEYEVENTF_KEYUP));
            }
        }
    }
}
