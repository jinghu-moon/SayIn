//! UI locale never changes the language or contents of a message payload.
use fluent_bundle::{FluentArgs, FluentResource, concurrent::FluentBundle};
use serde::{Deserialize, Serialize};
use unic_langid::LanguageIdentifier;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum LanguagePreference {
    #[default]
    #[serde(rename = "system")]
    System,
    #[serde(rename = "zh-Hans")]
    Chinese,
    #[serde(rename = "en")]
    English,
}

impl LanguagePreference {
    pub fn key(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::Chinese => "zh-Hans",
            Self::English => "en",
        }
    }

    pub fn resolve(self, preferred_languages: &[String]) -> Locale {
        match self {
            Self::Chinese => Locale::Chinese,
            Self::English => Locale::English,
            Self::System => preferred_languages
                .iter()
                .find_map(|tag| supported_locale(tag))
                .unwrap_or(Locale::English),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Locale {
    Chinese,
    English,
}

fn supported_locale(tag: &str) -> Option<Locale> {
    let language = tag.parse::<LanguageIdentifier>().ok()?;
    match language.language.as_str() {
        "en" => Some(Locale::English),
        "zh" => {
            let script = language.script.map(|script| script.to_string());
            let region = language.region.map(|region| region.to_string());
            match (script.as_deref(), region.as_deref()) {
                (Some("Hans"), _) => Some(Locale::Chinese),
                (Some(_), _) => None,
                (None, Some("TW" | "HK" | "MO")) => None,
                _ => Some(Locale::Chinese),
            }
        }
        _ => None,
    }
}

// IDs are stable domain intent, never translated text or visual positions.
macro_rules! messages {
    ($($variant:ident => $key:literal),+ $(,)?) => {
        #[derive(Clone, Copy, Debug)]
        pub enum Message { $($variant),+ }
        impl Message {
            pub const ALL: &'static [Self] = &[$(Self::$variant),+];
            pub const fn key(self) -> &'static str {
                match self { $(Self::$variant => $key),+ }
            }
        }
    };
}
messages! {
    Devices => "devices",
    DevicesDescription => "devices-description",
    LocalNetwork => "local-network",
    CurrentInput => "current-input",
    LatestMessage => "latest-message",
    LatestEmpty => "latest-empty",
    KeyOperation => "key-operation",
    BodyUnavailable => "body-unavailable",
    TargetUnavailable => "target-unavailable",
    TimeUnavailable => "time-unavailable",
    ReceivedTime => "received-time",
    ExecutionTime => "execution-time",
    CopyOriginal => "copy-original",
    SearchHistory => "search-history",
    HistoryFilter => "history-filter",
    AllMessages => "all-messages",
    HistoryReadFailed => "history-read-failed",
    HistoryLoading => "history-loading",
    LatestPage => "latest-page",
    OlderPage => "older-page",
    ReviewInput => "review-input",
    ReviewTitle => "review-title",
    ReviewCheckbox => "review-checkbox",
    OpenWindow => "open-window",
    QuitApp => "quit-app",
    TrayHint => "tray-hint",
    TrayFailed => "tray-failed",
    CloseBehavior => "close-behavior",
    CloseAsk => "close-ask",
    CloseTray => "close-tray",
    CloseQuit => "close-quit",
    CloseBehaviorHelp => "close-behavior-help",
    CloseTitle => "close-title",
    CloseDialog => "close-dialog",
    CloseHelp => "close-help",
    ContinueBackground => "continue-background",
    CloseAskStatus => "close-ask-status", CloseQuitStatus => "close-quit-status",
    AppName => "app-name", AppTagline => "app-tagline", Navigation => "navigation",
    Connection => "connection", History => "history", Settings => "settings",
    NotConnected => "not-connected", ServiceUnavailable => "service-unavailable",
    ConnectionDescription => "connection-description", PairPhone => "pair-phone",
    PairUnavailable => "pair-unavailable", ConnectionEmpty => "connection-empty",
    ConnectionEmptyDescription => "connection-empty-description",
    HistoryDescription => "history-description", HistoryEmpty => "history-empty",
    HistoryEmptyDescription => "history-empty-description",
    SettingsDescription => "settings-description", Language => "language",
    LanguageDescription => "language-description", FollowSystem => "follow-system",
    LanguageChinese => "language-chinese", LanguageEnglish => "language-english",
    Appearance => "appearance", AppearanceDescription => "appearance-description",
    ThemeLight => "theme-light", ThemeDark => "theme-dark", About => "about",
    Version => "version", FoundationNote => "foundation-note",
    SettingsReadFailed => "settings-read-failed", SettingsSaveFailed => "settings-save-failed",
    Retry => "retry", Saving => "saving",
    ReceiverLoading => "receiver-loading", ReceiverFailed => "receiver-failed",
    ReceiverStopping => "receiver-stopping", ReceiverStopped => "receiver-stopped",
    ReceiverPaused => "receiver-paused", ReceiverRunning => "receiver-running",
    ReceiverCommandFailed => "receiver-command-failed", ReceiverStorageFailed => "receiver-storage-failed",
    CursorMode => "cursor-mode", CursorReady => "cursor-ready", ConnectFirst => "connect-first",
    ReadyToEnable => "ready-to-enable", PhoneConnected => "phone-connected", PhoneOffline => "phone-offline",
    DeviceAuthorized => "device-authorized", PhoneDevice => "phone-device", QuickStart => "quick-start", CopyAddress => "copy-address", PairingTitle => "pairing-title",
    GuidePair => "guide-pair", GuidePairHelp => "guide-pair-help", GuideCursor => "guide-cursor", GuideCursorHelp => "guide-cursor-help",
    GuideSend => "guide-send", GuideSendHelp => "guide-send-help", Cancel => "cancel", PauseInput => "pause-input",
    ResumeInput => "resume-input", ReviewedResume => "reviewed-resume", ReviewHelp => "review-help",
    ReceiverHelp => "receiver-help", ReceiptSummary => "receipt-summary",
    ReceiptInputHelp => "receipt-input-help", ReceiptHeldHelp => "receipt-held-help", ReceiptOtherHelp => "receipt-other-help",
    ReceiptAcceptedHelp => "receipt-accepted-help", ReceiptExpiredHelp => "receipt-expired-help",
    ReceiptReceiverFocused => "receipt-receiver-focused", ReceiptTargetMissing => "receipt-target-missing",
    ReceiptElevatedTarget => "receipt-elevated-target", ReceiptModifierHeld => "receipt-modifier-held",
    ReceiptSessionUnavailable => "receipt-session-unavailable",
    ReceiptHeldCauseHelp => "receipt-held-cause-help", ReceiptExpiredCauseHelp => "receipt-expired-cause-help",
    ReceiptAccepted => "receipt-accepted", ReceiptHeld => "receipt-held", ReceiptExecuting => "receipt-executing",
    ReceiptInjected => "receipt-injected", ReceiptCancelled => "receipt-cancelled", ReceiptExpired => "receipt-expired",
    ReceiptFailed => "receipt-failed", ReceiptUncertain => "receipt-uncertain",
    PairNetwork => "pair-network", PairNetworkHelp => "pair-network-help", NoNetwork => "no-network",
    NetworkPublic => "network-public", NetworkPrivate => "network-private", NetworkDomain => "network-domain",
    PublicNetworkHelp => "public-network-help", AuthorizePublicNetwork => "authorize-public-network",
    NetworkFirewallHelp => "network-firewall-help", PublicNetworkActive => "public-network-active",
    StartPairing => "start-pairing", ClosePairing => "close-pairing", PairingChoices => "pairing-choices",
    PairingCode => "pairing-code", PairingAddress => "pairing-address", PairingExpiry => "pairing-expiry",
    PairingQr => "pairing-qr",
    ApprovePhone => "approve-phone", Approve => "approve", Reject => "reject", NetworkFailed => "network-failed",
    PairingUnavailable => "pairing-unavailable",
    AuthorizedDevices => "authorized-devices", RevokeDevice => "revoke-device", RevokeHelp => "revoke-help",
    ConfirmRevoke => "confirm-revoke", ActiveDevice => "active-device",
}

pub struct Catalogs {
    english: FluentBundle<FluentResource>,
    chinese: FluentBundle<FluentResource>,
}

impl Default for Catalogs {
    fn default() -> Self {
        Self {
            english: bundle("en", include_str!("../locales/en.ftl")),
            chinese: bundle("zh-Hans", include_str!("../locales/zh-Hans.ftl")),
        }
    }
}

fn bundle(locale: &str, source: &str) -> FluentBundle<FluentResource> {
    let mut bundle = FluentBundle::new_concurrent(vec![locale.parse().expect("built-in locale")]);
    let resource = FluentResource::try_new(source.to_owned()).expect("valid built-in FTL");
    bundle
        .add_resource(resource)
        .expect("unique built-in FTL keys");
    bundle
}

impl Catalogs {
    pub fn text(&self, locale: Locale, message: Message) -> String {
        self.format(locale, message, None)
    }

    pub fn version(&self, locale: Locale) -> String {
        let mut args = FluentArgs::new();
        args.set("version", env!("CARGO_PKG_VERSION"));
        self.format(locale, Message::Version, Some(&args))
    }

    pub fn history_time(&self, locale: Locale, executed: bool, timestamp: &str) -> String {
        let mut args = FluentArgs::new();
        args.set("time", timestamp);
        self.format(
            locale,
            if executed {
                Message::ExecutionTime
            } else {
                Message::ReceivedTime
            },
            Some(&args),
        )
    }

    pub fn receipt_summary(
        &self,
        locale: Locale,
        receipt: &voicelink_receiver::protocol::Receipt,
    ) -> String {
        use voicelink_receiver::protocol::State;
        let message = match receipt.st {
            State::Accepted => Message::ReceiptAccepted,
            State::Held => Message::ReceiptHeld,
            State::Executing => Message::ReceiptExecuting,
            State::Injected => Message::ReceiptInjected,
            State::Cancelled => Message::ReceiptCancelled,
            State::Expired => Message::ReceiptExpired,
            State::Failed => Message::ReceiptFailed,
            State::Uncertain => Message::ReceiptUncertain,
        };
        let mut args = FluentArgs::new();
        args.set("order", receipt.order.as_str());
        args.set("state", self.text(locale, message));
        self.format(locale, Message::ReceiptSummary, Some(&args))
    }

    pub fn receipt_help(
        &self,
        locale: Locale,
        receipt: &voicelink_receiver::protocol::Receipt,
    ) -> String {
        use voicelink_receiver::protocol::State;
        if matches!(receipt.st, State::Held | State::Expired) {
            let cause = match receipt.reason.as_deref() {
                Some("receiver_focused") => Some(Message::ReceiptReceiverFocused),
                Some("target_missing") => Some(Message::ReceiptTargetMissing),
                Some("elevated_target") => Some(Message::ReceiptElevatedTarget),
                Some("modifier_held") => Some(Message::ReceiptModifierHeld),
                Some("session_unavailable") => Some(Message::ReceiptSessionUnavailable),
                _ => None,
            };
            if let Some(cause) = cause {
                let mut args = FluentArgs::new();
                args.set("cause", self.text(locale, cause));
                return self.format(
                    locale,
                    if receipt.st == State::Expired {
                        Message::ReceiptExpiredCauseHelp
                    } else {
                        Message::ReceiptHeldCauseHelp
                    },
                    Some(&args),
                );
            }
        }
        let message = match receipt.st {
            State::Accepted => Message::ReceiptAcceptedHelp,
            State::Expired => Message::ReceiptExpiredHelp,
            State::Injected => Message::ReceiptInputHelp,
            State::Uncertain => Message::ReviewHelp,
            State::Held => Message::ReceiptHeldHelp,
            _ => Message::ReceiptOtherHelp,
        };
        self.text(locale, message)
    }

    fn format(&self, locale: Locale, message: Message, args: Option<&FluentArgs>) -> String {
        let preferred = match locale {
            Locale::Chinese => &self.chinese,
            Locale::English => &self.english,
        };
        let render = |bundle: &FluentBundle<FluentResource>| {
            let message = bundle.get_message(message.key())?;
            let pattern = message.value()?;
            let mut errors = Vec::new();
            let value = bundle
                .format_pattern(pattern, args, &mut errors)
                .into_owned();
            errors.is_empty().then_some(value)
        };
        render(preferred)
            .or_else(|| render(&self.english))
            .unwrap_or_else(|| message.key().to_owned())
    }
}

/// Windows' ordered UI language list, refreshed whenever the window activates.
#[cfg(windows)]
pub fn system_languages() -> Vec<String> {
    use windows::Win32::Globalization::{GetUserPreferredUILanguages, MUI_LANGUAGE_NAME};
    use windows::core::PWSTR;
    let mut count = 0;
    let mut length = 0;
    // SAFETY: The first call only queries size. The second writes into a buffer
    // of that exact UTF-16 capacity; MULTI_SZ entries are decoded within it.
    unsafe {
        if GetUserPreferredUILanguages(MUI_LANGUAGE_NAME, &mut count, None, &mut length).is_err()
            || length == 0
            || length > 65536
        {
            return Vec::new();
        }
        let mut buffer = vec![0_u16; length as usize];
        if GetUserPreferredUILanguages(
            MUI_LANGUAGE_NAME,
            &mut count,
            Some(PWSTR(buffer.as_mut_ptr())),
            &mut length,
        )
        .is_err()
        {
            return Vec::new();
        }
        buffer
            .split(|unit| *unit == 0)
            .filter(|part| !part.is_empty())
            .map(String::from_utf16_lossy)
            .collect()
    }
}

#[cfg(not(windows))]
pub fn system_languages() -> Vec<String> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn receipt_help_uses_state_and_known_reason_without_exposing_raw_codes() {
        let catalogs = Catalogs::default();
        for locale in [Locale::Chinese, Locale::English] {
            for (state, reason, message) in [
                ("accepted", "", Message::ReceiptAcceptedHelp),
                (
                    "expired",
                    "expired_before_input",
                    Message::ReceiptExpiredHelp,
                ),
                ("held", "receiver_focused", Message::ReceiptReceiverFocused),
                ("expired", "elevated_target", Message::ReceiptElevatedTarget),
                (
                    "expired",
                    "private_platform_detail",
                    Message::ReceiptExpiredHelp,
                ),
                ("held", "target_missing", Message::ReceiptTargetMissing),
                ("held", "elevated_target", Message::ReceiptElevatedTarget),
                ("held", "modifier_held", Message::ReceiptModifierHeld),
                (
                    "held",
                    "session_unavailable",
                    Message::ReceiptSessionUnavailable,
                ),
                ("held", "private_platform_detail", Message::ReceiptHeldHelp),
                ("injected", "receiver_focused", Message::ReceiptInputHelp),
                ("uncertain", "", Message::ReviewHelp),
            ] {
                let receipt = serde_json::from_value(serde_json::json!({
                    "id":"test", "order":"1", "st":state, "reason":reason,
                }))
                .unwrap();
                let help = catalogs.receipt_help(locale, &receipt);
                assert!(help.contains(&catalogs.text(locale, message)));
                if state == "expired" {
                    assert!(help.contains(if locale == Locale::Chinese {
                        "已过期"
                    } else {
                        "expired"
                    }));
                }
                assert!(
                    !catalogs
                        .receipt_help(locale, &receipt)
                        .contains("private_platform_detail")
                );
            }
        }
    }

    #[test]
    fn follows_ordered_supported_languages_and_preserves_explicit_choice() {
        let tags = |values: &[&str]| {
            values
                .iter()
                .map(|value| (*value).to_owned())
                .collect::<Vec<_>>()
        };
        assert_eq!(
            LanguagePreference::System.resolve(&tags(&["fr-FR", "zh-CN", "en-US"])),
            Locale::Chinese
        );
        assert_eq!(
            LanguagePreference::System.resolve(&tags(&["en-GB", "zh-Hans"])),
            Locale::English
        );
        assert_eq!(
            LanguagePreference::System.resolve(&tags(&["zh-TW"])),
            Locale::English
        );
        assert_eq!(
            LanguagePreference::System.resolve(&tags(&["zh-Hant-CN"])),
            Locale::English
        );
        assert_eq!(
            LanguagePreference::System.resolve(&tags(&["zh-Hans-HK"])),
            Locale::Chinese
        );
        assert_eq!(LanguagePreference::System.resolve(&[]), Locale::English);
        assert_eq!(
            LanguagePreference::English.resolve(&tags(&["zh-CN"])),
            Locale::English
        );
    }

    #[test]
    fn every_product_message_renders_in_both_languages_without_resolver_errors() {
        let catalogs = Catalogs::default();
        for locale in [Locale::English, Locale::Chinese] {
            for message in Message::ALL {
                if matches!(
                    message,
                    Message::Version
                        | Message::ReceiptHeldCauseHelp
                        | Message::ReceiptExpiredCauseHelp
                        | Message::ReceiptSummary
                        | Message::ReceivedTime
                        | Message::ExecutionTime
                ) {
                    continue;
                }
                assert_ne!(catalogs.text(locale, *message), message.key());
            }
            assert!(catalogs.version(locale).contains(env!("CARGO_PKG_VERSION")));
            for executed in [false, true] {
                let label = catalogs.history_time(locale, executed, "2026-11-13 10:00:00");
                assert!(label.contains("2026-11-13 10:00:00"));
                assert!(label.contains(if executed {
                    if locale == Locale::English {
                        "Execution"
                    } else {
                        "执行"
                    }
                } else if locale == Locale::English {
                    "Received"
                } else {
                    "收到"
                }));
            }
            let receipt = serde_json::from_str(
                r#"{"id":"x","order":"9223372036854775807","st":"uncertain"}"#,
            )
            .unwrap();
            let label = catalogs.receipt_summary(locale, &receipt);
            assert!(label.contains("9223372036854775807"));
            assert!(label.contains(&catalogs.text(locale, Message::ReceiptUncertain)));
        }
        let keys = |source: &str| {
            source
                .lines()
                .filter_map(|line| line.split_once(" = ").map(|(key, _)| key.to_owned()))
                .collect::<std::collections::BTreeSet<_>>()
        };
        let expected = Message::ALL
            .iter()
            .map(|message| message.key().to_owned())
            .collect();
        assert_eq!(keys(include_str!("../locales/en.ftl")), expected);
        assert_eq!(keys(include_str!("../locales/zh-Hans.ftl")), expected);
    }
}
