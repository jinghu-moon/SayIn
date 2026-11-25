use crate::{Error, Result};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::{Deserialize, Serialize, de};
use serde_json::Value;
use uuid::Uuid;

pub const OUTBOUND_FRAME_BYTES: usize = 8192;
const MAX_ICON_BYTES: usize = 4096;
const MAX_NAME_SCALARS: usize = 96;
const MAX_REVISION: u64 = i64::MAX as u64;
const INVALID_REQUEST: Error = Error::Protocol("INVALID_REQUEST");

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionStage {
    NotStarted,
    Prepared,
    InputSubmitted,
    FileSaved,
    PasteSubmitted,
    PasteConfirmed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ExecutionMethod {
    Unicode,
    Key,
    ClipboardImage,
    ClipboardImagePath,
    None,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Execution {
    pub stage: ExecutionStage,
    pub method: ExecutionMethod,
    pub confirmed: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StatusState {
    Accepted,
    Held,
    Executing,
    Injected,
    Failed,
    Cancelled,
    Expired,
    Uncertain,
    UncertainAcknowledged,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UncertainScope {
    Target,
    Global,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Application {
    pub name: String,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub icon_webp: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerTimings {
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub accept: Option<u64>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub queue: Option<u64>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub execute: Option<u64>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub finish_commit: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Status {
    #[serde(deserialize_with = "canonical_uuid")]
    pub session_id: Uuid,
    #[serde(deserialize_with = "canonical_uuid")]
    pub id: Uuid,
    #[serde(
        default,
        deserialize_with = "optional_revision",
        serialize_with = "encode_optional_revision",
        skip_serializing_if = "Option::is_none"
    )]
    pub accept_order: Option<u64>,
    #[serde(deserialize_with = "revision", serialize_with = "encode_revision")]
    pub request_rev: u64,
    #[serde(rename = "st")]
    pub state: StatusState,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub execution: Option<Execution>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub server_ms: Option<ServerTimings>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub application: Option<Application>,
    #[serde(
        default,
        deserialize_with = "present",
        skip_serializing_if = "Option::is_none"
    )]
    pub uncertain_scope: Option<UncertainScope>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
struct StatusIds {
    #[serde(deserialize_with = "canonical_uuid")]
    session_id: Uuid,
    #[serde(deserialize_with = "canonical_uuid")]
    id: Uuid,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StatusFrame {
    Unknown { session_id: Uuid, id: Uuid },
    Receipt(Status),
}

impl StatusFrame {
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let value = decode_frame(bytes, "status")?;
        if value.get("st").and_then(Value::as_str) == Some("unknown") {
            if value.as_object().map(|object| object.len()) != Some(5) {
                return Err(INVALID_REQUEST);
            }
            let StatusIds { session_id, id } =
                serde_json::from_value(value).map_err(|_| INVALID_REQUEST)?;
            return Ok(Self::Unknown { session_id, id });
        }
        let receipt: Status = serde_json::from_value(value).map_err(|_| INVALID_REQUEST)?;
        receipt.validate()?;
        if receipt.application.as_ref().is_some_and(|application| {
            application.name.chars().count() > MAX_NAME_SCALARS || !application.valid_icon()
        }) {
            return Err(INVALID_REQUEST);
        }
        Ok(Self::Receipt(receipt))
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        let bytes = match self {
            Self::Unknown { session_id, id } => {
                if [session_id, id]
                    .into_iter()
                    .any(|id| id.get_variant() != uuid::Variant::RFC4122)
                {
                    return Err(INVALID_REQUEST);
                }
                let mut fields = serde_json::to_value(StatusIds {
                    session_id: *session_id,
                    id: *id,
                })
                .map_err(|_| INVALID_REQUEST)?;
                fields["st"] = Value::from("unknown");
                encode_frame(fields, "status")?
            }
            Self::Receipt(receipt) => {
                receipt.validate()?;
                let mut receipt = receipt.clone();
                if let Some(application) = &mut receipt.application {
                    if application.name.chars().count() > MAX_NAME_SCALARS {
                        receipt.application = None;
                    } else if !application.valid_icon() {
                        application.icon_webp = None;
                    }
                }
                let mut fields = serde_json::to_value(receipt).map_err(|_| INVALID_REQUEST)?;
                let bytes = encode_frame(fields.clone(), "status")?;
                if bytes.len() <= OUTBOUND_FRAME_BYTES {
                    return Ok(bytes);
                }
                if let Some(application) =
                    fields.get_mut("application").and_then(Value::as_object_mut)
                {
                    application.remove("icon_webp");
                }
                encode_frame(fields, "status")?
            }
        };
        if bytes.len() > OUTBOUND_FRAME_BYTES {
            return Err(INVALID_REQUEST);
        }
        Ok(bytes)
    }
}

impl Status {
    fn validate(&self) -> Result<()> {
        use ExecutionMethod::{ClipboardImage, ClipboardImagePath, None as NoMethod};
        use ExecutionStage::*;
        use StatusState::*;

        if self.request_rev > MAX_REVISION
            || self.accept_order.is_some_and(|order| order > MAX_REVISION)
            || (self.accept_order.is_none() && self.state != Cancelled)
            || [self.session_id, self.id]
                .into_iter()
                .any(|id| id.get_variant() != uuid::Variant::RFC4122)
            || self.uncertain_scope.is_some()
                != matches!(self.state, Uncertain | UncertainAcknowledged)
        {
            return Err(INVALID_REQUEST);
        }
        if self.accept_order.is_none()
            && self
                .execution
                .as_ref()
                .is_none_or(|execution| execution.stage != NotStarted)
        {
            return Err(INVALID_REQUEST);
        }
        // v2 migration preserves absence of execution evidence.
        let Some(execution) = &self.execution else {
            return Ok(());
        };
        let Execution {
            stage,
            method,
            confirmed,
        } = *execution;
        if confirmed != (stage == PasteConfirmed) {
            return Err(INVALID_REQUEST);
        }
        let allowed = match self.state {
            Accepted | Held => stage == NotStarted && method == NoMethod,
            Executing => {
                matches!(
                    stage,
                    Prepared | FileSaved | InputSubmitted | PasteSubmitted
                ) && method != NoMethod
            }
            Injected => {
                matches!(stage, InputSubmitted | PasteSubmitted | PasteConfirmed)
                    && method != NoMethod
            }
            Failed => match stage {
                NotStarted => method == NoMethod,
                Prepared => method != NoMethod,
                FileSaved => {
                    matches!(method, ClipboardImage | ClipboardImagePath)
                        && self.reason.as_deref() == Some("paste_not_started")
                }
                _ => false,
            },
            Cancelled | Expired => matches!(stage, NotStarted | Prepared) && method == NoMethod,
            Uncertain | UncertainAcknowledged => {
                method != NoMethod
                    && (matches!(stage, InputSubmitted | PasteSubmitted)
                        || (matches!(stage, Prepared | FileSaved)
                            && self.reason.as_deref() == Some("restart_during_input")
                            && self.uncertain_scope == Some(UncertainScope::Global)))
            }
        };
        if !allowed {
            return Err(INVALID_REQUEST);
        }
        Ok(())
    }
}

impl Application {
    fn valid_icon(&self) -> bool {
        self.icon_webp.as_ref().is_none_or(|icon| {
            icon.len() <= MAX_ICON_BYTES.div_ceil(3) * 4
                && STANDARD
                    .decode(icon)
                    .is_ok_and(|bytes| (1..=MAX_ICON_BYTES).contains(&bytes.len()))
        })
    }
}

macro_rules! error_codes {
    ($($variant:ident),+ $(,)?) => {
        #[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
        #[serde(rename_all = "SCREAMING_SNAKE_CASE")]
        pub enum ErrorCode { $($variant),+ }

        impl ErrorCode {
            pub const ALL: &'static [Self] = &[$(Self::$variant),+];
        }
    };
}

error_codes!(
    Unauthorized,
    StaleSession,
    PairingBusy,
    PairAlreadyExists,
    PairReplaceBlocked,
    TargetMismatch,
    StateConflict,
    IdConflict,
    QueueFull,
    RateLimited,
    StorageUnavailable,
    GlobalPaused,
    BlockedReview,
    TooLate,
    InvalidRequest,
    Unsupported,
    VersionMismatch,
);

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ErrorFrame {
    pub code: ErrorCode,
    #[serde(
        default,
        deserialize_with = "optional_uuid",
        skip_serializing_if = "Option::is_none"
    )]
    pub session_id: Option<Uuid>,
    #[serde(
        rename = "ref",
        default,
        deserialize_with = "optional_uuid",
        skip_serializing_if = "Option::is_none"
    )]
    pub reference: Option<Uuid>,
    #[serde(
        default,
        deserialize_with = "optional_uuid",
        skip_serializing_if = "Option::is_none"
    )]
    pub id: Option<Uuid>,
}

impl ErrorFrame {
    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let fields = decode_frame(bytes, "err")?;
        let frame: Self = serde_json::from_value(fields).map_err(|_| INVALID_REQUEST)?;
        frame.validate()?;
        Ok(frame)
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        self.validate()?;
        let fields = serde_json::to_value(self).map_err(|_| INVALID_REQUEST)?;
        encode_frame(fields, "err")
    }

    fn validate(&self) -> Result<()> {
        if self.code == ErrorCode::PairAlreadyExists
            || [self.session_id, self.reference, self.id]
                .into_iter()
                .flatten()
                .any(|id| id.get_variant() != uuid::Variant::RFC4122)
        {
            return Err(INVALID_REQUEST);
        }
        Ok(())
    }
}

fn optional_uuid<'de, D>(deserializer: D) -> std::result::Result<Option<Uuid>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    canonical_uuid(deserializer).map(Some)
}

fn decode_frame(bytes: &[u8], kind: &str) -> Result<Value> {
    if bytes.len() > OUTBOUND_FRAME_BYTES {
        return Err(INVALID_REQUEST);
    }
    let value = crate::protocol::strict_json(bytes).map_err(|_| INVALID_REQUEST)?;
    if value.get("v").and_then(Value::as_u64) != Some(3)
        || value.get("t").and_then(Value::as_str) != Some(kind)
    {
        return Err(INVALID_REQUEST);
    }
    Ok(value)
}

fn encode_frame(mut fields: Value, kind: &str) -> Result<Vec<u8>> {
    fields["v"] = Value::from(3);
    fields["t"] = Value::from(kind);
    serde_json::to_vec(&fields).map_err(|_| INVALID_REQUEST)
}

fn encode_revision<S>(value: &u64, serializer: S) -> std::result::Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    serializer.collect_str(value)
}

fn encode_optional_revision<S>(
    value: &Option<u64>,
    serializer: S,
) -> std::result::Result<S::Ok, S::Error>
where
    S: serde::Serializer,
{
    match value {
        Some(value) => encode_revision(value, serializer),
        None => serializer.serialize_none(),
    }
}

fn present<'de, D, T>(deserializer: D) -> std::result::Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    T::deserialize(deserializer).map(Some)
}

fn canonical_uuid<'de, D>(deserializer: D) -> std::result::Result<Uuid, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = String::deserialize(deserializer)?;
    let uuid = Uuid::parse_str(&value).map_err(de::Error::custom)?;
    if uuid.get_variant() != uuid::Variant::RFC4122 || uuid.to_string() != value {
        return Err(de::Error::custom("expected a lowercase RFC 4122 UUID"));
    }
    Ok(uuid)
}

fn revision<'de, D>(deserializer: D) -> std::result::Result<u64, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let value = String::deserialize(deserializer)?;
    if value.is_empty()
        || (value.len() > 1 && value.starts_with('0'))
        || !value.bytes().all(|byte| byte.is_ascii_digit())
    {
        return Err(de::Error::custom("expected a canonical decimal string"));
    }
    let parsed = value.parse::<u64>().map_err(de::Error::custom)?;
    if parsed > MAX_REVISION {
        return Err(de::Error::custom("revision exceeds signed SQLite range"));
    }
    Ok(parsed)
}

fn optional_revision<'de, D>(deserializer: D) -> std::result::Result<Option<u64>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    revision(deserializer).map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn protocol(error: Error, expected: &'static str) {
        assert!(matches!(error, Error::Protocol(code) if code == expected));
    }

    #[test]
    fn rejects_unknown_execution_stage() {
        let error = StatusFrame::decode(br#"{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","request_rev":"1","st":"executing","execution":{"stage":"future","method":"unicode","confirmed":false}}"#).unwrap_err();
        protocol(error, "INVALID_REQUEST");
    }

    #[test]
    fn rejects_duplicate_keys_in_nested_execution() {
        let error = StatusFrame::decode(br#"{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","request_rev":"1","st":"accepted","execution":{"stage":"not_started","stage":"not_started","method":"none"}}"#).unwrap_err();
        protocol(error, "INVALID_REQUEST");
    }

    #[test]
    fn rejects_non_object_root() {
        protocol(
            StatusFrame::decode(br#"[]"#).unwrap_err(),
            "INVALID_REQUEST",
        );
    }

    #[test]
    fn rejects_integer_revision_instead_of_decimal_string() {
        let error = StatusFrame::decode(br#"{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","request_rev":1,"st":"accepted"}"#).unwrap_err();
        protocol(error, "INVALID_REQUEST");
    }

    #[test]
    fn rejects_revision_over_u64_max() {
        let error = StatusFrame::decode(br#"{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","request_rev":"18446744073709551616","st":"accepted"}"#).unwrap_err();
        protocol(error, "INVALID_REQUEST");
    }

    #[test]
    fn accepts_migrated_receipt_without_execution() {
        let status = StatusFrame::decode(br#"{"v":3,"t":"status","session_id":"a9c265c2-7819-4866-a3d8-31c090f6fc25","id":"dcd90d23-d73f-46a6-a998-ad136cd72de9","accept_order":"42","request_rev":"0","st":"expired","reason":"restart_confirmation"}"#).unwrap();
        let StatusFrame::Receipt(status) = status else {
            panic!("expected a migrated receipt");
        };
        assert!(status.execution.is_none());
        assert_eq!(status.request_rev, 0);
    }
}

#[cfg(test)]
mod envelope_tests {
    use super::*;

    fn migrated() -> Value {
        serde_json::json!({
            "v": 3,
            "t": "status",
            "session_id": "a9c265c2-7819-4866-a3d8-31c090f6fc25",
            "id": "f47ac10b-58cc-11cf-a447-001122334455",
            "accept_order": "9223372036854775807",
            "request_rev": "0",
            "st": "expired",
            "reason": "restart_confirmation"
        })
    }

    #[test]
    fn accepts_canonical_rfc4122_migrated_receipt_at_sqlite_boundary() {
        assert!(StatusFrame::decode(migrated().to_string().as_bytes()).is_ok());
    }

    #[test]
    fn rejects_missing_and_noncanonical_session_ids() {
        for session_id in [
            Value::Null,
            Value::from("A9c265c2-7819-4866-a3d8-31c090f6fc25"),
        ] {
            let mut frame = migrated();
            frame["id"] = Value::from("dcd90d23-d73f-46a6-a998-ad136cd72de9");
            if session_id.is_null() {
                frame.as_object_mut().unwrap().remove("session_id");
            } else {
                frame["session_id"] = session_id;
            }
            assert!(StatusFrame::decode(frame.to_string().as_bytes()).is_err());
        }
    }

    #[test]
    fn rejects_noncanonical_decimal_fields_and_accept_order_over_sqlite_max() {
        for value in [
            Value::from("9223372036854775808"),
            Value::from("01"),
            Value::from("+1"),
            Value::from("１"),
            Value::from(1),
            Value::Null,
        ] {
            let mut frame = migrated();
            frame["id"] = Value::from("dcd90d23-d73f-46a6-a998-ad136cd72de9");
            frame["accept_order"] = value;
            assert!(StatusFrame::decode(frame.to_string().as_bytes()).is_err());
        }
    }

    #[test]
    fn rejects_wrong_types_in_optional_status_metadata() {
        for (key, value) in [
            ("server_ms", serde_json::json!({"queue": -1})),
            ("application", serde_json::json!({"name": 7})),
            ("uncertain_scope", serde_json::json!("future")),
        ] {
            let mut frame = migrated();
            frame["id"] = Value::from("dcd90d23-d73f-46a6-a998-ad136cd72de9");
            frame[key] = value;
            assert!(
                StatusFrame::decode(frame.to_string().as_bytes()).is_err(),
                "{key}"
            );
        }
    }

    #[test]
    fn rejects_duplicate_root_keys_and_trailing_objects() {
        let original = migrated().to_string();
        let duplicate = format!("{{\"v\":3,{}", &original[1..]);
        let trailing = format!("{original}{{}}");
        for frame in [duplicate, trailing] {
            assert!(StatusFrame::decode(frame.as_bytes()).is_err());
        }
    }
}

#[cfg(test)]
mod golden_tests {
    use super::*;
    use serde_json::json;
    use std::collections::HashSet;

    const COMBINATIONS: &str =
        include_str!("../../../../docs/research/91-golden/status-combinations.json");
    const UNKNOWN: &[u8] =
        include_bytes!("../../../../docs/research/91-golden/status-unknown.json");
    const WITH_ICON: &[u8] =
        include_bytes!("../../../../docs/research/91-golden/status-budget-with-icon.json");
    const OMITTED_ICON: &[u8] =
        include_bytes!("../../../../docs/research/91-golden/status-budget-icon-omitted.json");

    fn round_trip(frame: &Value) {
        let decoded = StatusFrame::decode(frame.to_string().as_bytes()).unwrap();
        let encoded = decoded.encode().unwrap();
        assert_eq!(serde_json::from_slice::<Value>(&encoded).unwrap(), *frame);
    }

    #[test]
    fn round_trips_all_golden_combinations_and_rejects_the_complement() {
        let rows: HashSet<(String, String, String, bool)> =
            serde_json::from_str(COMBINATIONS).unwrap();
        let envelope: Value = serde_json::from_slice(UNKNOWN).unwrap();
        let states = [
            "accepted",
            "held",
            "executing",
            "injected",
            "failed",
            "cancelled",
            "expired",
            "uncertain",
            "uncertain_acknowledged",
        ];
        let stages = [
            "not_started",
            "prepared",
            "input_submitted",
            "file_saved",
            "paste_submitted",
            "paste_confirmed",
        ];
        let methods = [
            "unicode",
            "key",
            "clipboard_image",
            "clipboard_image_path",
            "none",
        ];
        let mut matched = 0;
        for state in states {
            for stage in stages {
                for method in methods {
                    for confirmed in [false, true] {
                        let mut frame = envelope.clone();
                        frame["st"] = json!(state);
                        frame["accept_order"] = json!("42");
                        frame["request_rev"] = json!("7");
                        frame["execution"] =
                            json!({"stage":stage, "method":method, "confirmed":confirmed});
                        if state.starts_with("uncertain") {
                            frame["uncertain_scope"] = json!("global");
                            if matches!(stage, "prepared" | "file_saved") {
                                frame["reason"] = json!("restart_during_input");
                            }
                        }
                        if state == "failed" && stage == "file_saved" {
                            frame["reason"] = json!("paste_not_started");
                        }
                        if rows.contains(&(state.into(), stage.into(), method.into(), confirmed)) {
                            round_trip(&frame);
                            matched += 1;
                        } else {
                            assert!(
                                StatusFrame::decode(frame.to_string().as_bytes()).is_err(),
                                "{state}/{stage}/{method}/{confirmed}"
                            );
                        }
                    }
                }
            }
        }
        assert_eq!(matched, rows.len());
    }

    #[test]
    fn round_trips_exact_query_miss_and_rejects_receipt_fields() {
        let frame: Value = serde_json::from_slice(UNKNOWN).unwrap();
        round_trip(&frame);
        for (key, value) in [
            ("request_rev", json!("0")),
            ("accept_order", json!("0")),
            ("reason", Value::Null),
            (
                "execution",
                json!({"stage":"not_started","method":"none","confirmed":false}),
            ),
        ] {
            let mut malformed = frame.clone();
            malformed[key] = value;
            assert!(StatusFrame::decode(malformed.to_string().as_bytes()).is_err());
        }
    }

    #[test]
    fn preserves_exact_budget_and_omits_only_the_icon_at_one_extra_byte() {
        assert_eq!(WITH_ICON.len(), 8192);
        let frame: Value = serde_json::from_slice(WITH_ICON).unwrap();
        round_trip(&frame);
        let mut decoded = StatusFrame::decode(WITH_ICON).unwrap();
        let StatusFrame::Receipt(ref mut receipt) = decoded else {
            panic!("expected a receipt");
        };
        receipt.reason.as_mut().unwrap().push('x');
        let mut expected: Value = serde_json::from_slice(OMITTED_ICON).unwrap();
        expected["reason"] = json!(receipt.reason);
        let encoded = decoded.encode().unwrap();
        assert_eq!(serde_json::from_slice::<Value>(&encoded).unwrap(), expected);

        let oversized = [WITH_ICON, b" "].concat();
        assert!(StatusFrame::decode(&oversized).is_err());
    }

    #[test]
    fn can_round_trip_a_cancel_tombstone_and_a_migrated_receipt() {
        let mut frame: Value = serde_json::from_slice(UNKNOWN).unwrap();
        frame["st"] = json!("cancelled");
        frame["request_rev"] = json!("1");
        frame["execution"] = json!({"stage":"not_started","method":"none","confirmed":false});
        round_trip(&frame);

        frame["st"] = json!("expired");
        frame["accept_order"] = json!("9223372036854775807");
        frame["request_rev"] = json!("0");
        frame["reason"] = json!("restart_confirmation");
        frame.as_object_mut().unwrap().remove("execution");
        round_trip(&frame);
    }
}

#[cfg(test)]
mod error_tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn error_enum_and_wire_codes_match_every_normative_entry() {
        let spec = include_str!("../../../../docs/86-multi-host-multi-device-design.md");
        let section = spec
            .split_once("### 6.7 ")
            .unwrap()
            .1
            .split_once("### 6.8 ")
            .unwrap()
            .0;
        let normative: Vec<_> = section
            .lines()
            .filter_map(|row| {
                row.strip_prefix("| `")
                    .and_then(|row| row.split_once("` |"))
                    .map(|(name, _)| name)
            })
            .collect();
        assert_eq!(normative.len(), 17);
        let codes: Vec<_> = ErrorCode::ALL
            .iter()
            .map(|code| serde_json::to_value(code).unwrap())
            .collect();
        assert_eq!(
            codes,
            normative.iter().map(|name| json!(name)).collect::<Vec<_>>()
        );
        for (code, name) in ErrorCode::ALL.iter().zip(normative) {
            let frame = ErrorFrame {
                code: *code,
                session_id: Some(Uuid::parse_str("a9c265c2-7819-4866-a3d8-31c090f6fc25").unwrap()),
                reference: Some(Uuid::parse_str("dcd90d23-d73f-46a6-a998-ad136cd72de9").unwrap()),
                id: Some(Uuid::parse_str("f47ac10b-58cc-11cf-a447-001122334455").unwrap()),
            };
            let raw = json!({"v":3,"t":"err","code":name,"session_id":frame.session_id,
                "ref":frame.reference,"id":frame.id});
            if name == "PAIR_ALREADY_EXISTS" {
                assert!(frame.encode().is_err());
                assert!(ErrorFrame::decode(raw.to_string().as_bytes()).is_err());
            } else {
                let encoded = frame.encode().unwrap();
                assert_eq!(serde_json::from_slice::<Value>(&encoded).unwrap(), raw);
                assert_eq!(ErrorFrame::decode(&encoded).unwrap(), frame);
            }
        }
    }

    #[test]
    fn rejects_unlisted_wrongly_typed_and_noncanonical_error_fields() {
        for code in [
            json!("PAIR_RETIRED"),
            json!("LEGACY_RECEIPT_QUERY_ONLY"),
            json!("unauthorized"),
            json!(42),
        ] {
            let frame = json!({"v":3,"t":"err","code":code});
            assert!(ErrorFrame::decode(frame.to_string().as_bytes()).is_err());
        }
        let frame = json!({"v":3,"t":"err","code":"UNAUTHORIZED","ref":"DCD90D23-D73F-46A6-A998-AD136CD72DE9"});
        assert!(ErrorFrame::decode(frame.to_string().as_bytes()).is_err());
    }
}
