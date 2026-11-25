//! Strict v2 decoding and immutable payload normalization.
use crate::{Error, Result};
use serde::{
    Deserialize, Serialize,
    de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor},
};
use serde_json::Value;
use std::collections::HashSet;
use uuid::Uuid;

pub const MAX_MESSAGE_BYTES: usize = 65536;
pub const MAX_SUBMIT_BYTES: usize = 32768;
pub const MAX_TEXT_BYTES: usize = 16384;
pub const MAX_TEXT_SCALARS: usize = 4000;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Operation {
    Text {
        text: String,
        src: TextSource,
    },
    #[serde(rename = "auto_text")]
    AutoText {
        text: String,
        application: String,
    },
    Key {
        name: KeyName,
    },
    Image {
        image: crate::images::ImageReference,
    },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TextSource {
    Ime,
    Asr,
    Manual,
    Favorite,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum KeyName {
    Enter,
}

impl Operation {
    pub fn text(&self) -> Option<&str> {
        match self {
            Self::Text { text, .. } | Self::AutoText { text, .. } => Some(text),
            _ => None,
        }
    }

    pub fn bytes(&self) -> usize {
        self.text().map_or(0, str::len)
    }
    pub fn validate(&self) -> Result<()> {
        if let Self::Image { image } = self {
            return image.validate();
        }
        if let Self::AutoText { application, .. } = self
            && (application.trim().is_empty()
                || application.chars().count() > 96
                || application.chars().any(char::is_control))
        {
            return Err(Error::Protocol("INVALID_APPLICATION"));
        }
        if let Some(text) = self.text() {
            if text.is_empty()
                || text.len() > MAX_TEXT_BYTES
                || text.chars().count() > MAX_TEXT_SCALARS
            {
                return Err(Error::Protocol("TEXT_LIMIT"));
            }
            // The phone must preview normalization before committing its Outbox.
            // Reject unnormalized controls instead of silently changing payload.
            if normalize_text(text)? != *text {
                return Err(Error::Protocol("TEXT_NOT_NORMALIZED"));
            }
        }
        Ok(())
    }
}

pub fn normalize_text(text: &str) -> Result<String> {
    let mut normalized = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    while let Some(character) = chars.next() {
        match character {
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                normalized.push(' ');
            }
            '\n' | '\t' => normalized.push(' '),
            '\0'..='\u{1f}' | '\u{7f}' => return Err(Error::Protocol("TEXT_CONTROL_CHARACTER")),
            _ => normalized.push(character),
        }
    }
    Ok(normalized)
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub struct Submit {
    pub v: u8,
    pub t: String,
    pub session_id: String,
    pub id: String,
    pub target: String,
    pub op: Operation,
}

impl Submit {
    pub fn new(session_id: String, id: String, target: String, op: Operation) -> Self {
        Self {
            v: 2,
            t: "submit".into(),
            session_id,
            id,
            target,
            op,
        }
    }

    pub fn validate(&self) -> Result<()> {
        if self.v != 2 || self.t != "submit" {
            return Err(Error::Protocol("VERSION_OR_OPERATION"));
        }
        validate_uuid(&self.id)?;
        validate_uuid(&self.session_id)?;
        if self.target.is_empty() || self.target.len() > 128 {
            return Err(Error::Protocol("INVALID_TARGET"));
        }
        self.op.validate()
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_SUBMIT_BYTES {
            return Err(Error::Protocol("MESSAGE_LIMIT"));
        }
        let value = strict_json(bytes)?;
        let submit: Self =
            serde_json::from_value(value).map_err(|_| Error::Protocol("INVALID_SUBMIT"))?;
        submit.validate()?;
        Ok(submit)
    }

    /// No session or raw JSON ordering in the fingerprint.
    pub fn canonical_payload(&self) -> Vec<u8> {
        serde_json::to_vec(&(&self.target, &self.op)).expect("serializable payload")
    }
}

pub fn validate_uuid(value: &str) -> Result<()> {
    let uuid = Uuid::parse_str(value).map_err(|_| Error::Protocol("INVALID_ID"))?;
    if uuid.get_version_num() != 4
        || uuid.get_variant() != uuid::Variant::RFC4122
        || uuid.to_string() != value
    {
        return Err(Error::Protocol("INVALID_ID"));
    }
    Ok(())
}

pub fn strict_json(bytes: &[u8]) -> Result<Value> {
    if bytes.len() > MAX_MESSAGE_BYTES {
        return Err(Error::Protocol("MESSAGE_LIMIT"));
    }
    let mut decoder = serde_json::Deserializer::from_slice(bytes);
    let value = StrictValue(0)
        .deserialize(&mut decoder)
        .map_err(|_| Error::Protocol("INVALID_JSON"))?;
    decoder.end().map_err(|_| Error::Protocol("INVALID_JSON"))?;
    if !value.is_object() {
        return Err(Error::Protocol("INVALID_JSON"));
    }
    Ok(value)
}

struct StrictValue(u8);
impl<'de> DeserializeSeed<'de> for StrictValue {
    type Value = Value;
    fn deserialize<D: de::Deserializer<'de>>(
        self,
        deserializer: D,
    ) -> std::result::Result<Value, D::Error> {
        if self.0 > 8 {
            return Err(de::Error::custom("nesting limit"));
        }
        deserializer.deserialize_any(self)
    }
}
impl<'de> Visitor<'de> for StrictValue {
    type Value = Value;
    fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
        formatter.write_str("a bounded JSON value with unique keys")
    }
    fn visit_bool<E: de::Error>(self, value: bool) -> std::result::Result<Value, E> {
        Ok(value.into())
    }
    fn visit_i64<E: de::Error>(self, value: i64) -> std::result::Result<Value, E> {
        Ok(value.into())
    }
    fn visit_u64<E: de::Error>(self, value: u64) -> std::result::Result<Value, E> {
        Ok(value.into())
    }
    fn visit_f64<E: de::Error>(self, value: f64) -> std::result::Result<Value, E> {
        serde_json::Number::from_f64(value)
            .map(Value::Number)
            .ok_or_else(|| E::custom("non-finite number"))
    }
    fn visit_str<E: de::Error>(self, value: &str) -> std::result::Result<Value, E> {
        Ok(value.into())
    }
    fn visit_string<E: de::Error>(self, value: String) -> std::result::Result<Value, E> {
        Ok(value.into())
    }
    fn visit_none<E: de::Error>(self) -> std::result::Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_unit<E: de::Error>(self) -> std::result::Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> std::result::Result<Value, A::Error> {
        let mut values = Vec::new();
        while let Some(value) = sequence.next_element_seed(StrictValue(self.0 + 1))? {
            values.push(value);
        }
        Ok(Value::Array(values))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> std::result::Result<Value, A::Error> {
        let mut names = HashSet::new();
        let mut values = serde_json::Map::new();
        while let Some(name) = map.next_key::<String>()? {
            if !names.insert(name.clone()) {
                return Err(de::Error::custom("duplicate key"));
            }
            let value = map.next_value_seed(StrictValue(self.0 + 1))?;
            values.insert(name, value);
        }
        Ok(Value::Object(values))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum State {
    Accepted,
    Held,
    Executing,
    Injected,
    Cancelled,
    Expired,
    Failed,
    Uncertain,
    #[serde(rename = "uncertain_acknowledged")]
    UncertainAcknowledged,
}
impl State {
    pub fn key(self) -> &'static str {
        match self {
            Self::Accepted => "accepted",
            Self::Held => "held",
            Self::Executing => "executing",
            Self::Injected => "injected",
            Self::Cancelled => "cancelled",
            Self::Expired => "expired",
            Self::Failed => "failed",
            Self::Uncertain => "uncertain",
            Self::UncertainAcknowledged => "uncertain_acknowledged",
        }
    }
    pub fn is_terminal(self) -> bool {
        matches!(
            self,
            Self::Injected
                | Self::Cancelled
                | Self::Expired
                | Self::Failed
                | Self::Uncertain
                | Self::UncertainAcknowledged
        )
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[non_exhaustive]
pub struct Receipt {
    pub id: String,
    pub order: String,
    pub st: State,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uncertain_scope: Option<crate::protocol_v3::UncertainScope>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_rev: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub execution_stage: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirmed: Option<bool>,
    /// Execution-time display only. Never a route, scope or proof of insertion.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub application: Option<crate::application_icon::ApplicationDisplay>,
}
