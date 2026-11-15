//! Image resources are separate from input requests and the text queue budget.
mod ledger;
pub mod payload;
pub mod storage;
pub mod wire;

use crate::{Error, Result, protocol::validate_uuid};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const MAX_IMAGE_BYTES: u32 = 10 * 1024 * 1024;
pub const MAX_IMAGE_PIXELS: u64 = 16_000_000;
pub const MAX_IMAGE_SIDE: u32 = 8192;
pub const MAX_CHUNK_BYTES: usize = 48 * 1024;

/// A single explicit image intent, fenced both by its control connection and
/// local control changes. Resuming reception cannot revive an invalid intent.
pub struct ImageAuthorization {
    connection: std::sync::Arc<std::sync::atomic::AtomicBool>,
    active: std::sync::atomic::AtomicBool,
}
impl ImageAuthorization {
    pub fn new(connection: std::sync::Arc<std::sync::atomic::AtomicBool>) -> Self {
        Self {
            connection,
            active: std::sync::atomic::AtomicBool::new(true),
        }
    }
    pub fn valid(&self) -> bool {
        use std::sync::atomic::Ordering::Acquire;
        self.active.load(Acquire) && self.connection.load(Acquire)
    }
    pub(crate) fn invalidate(&self) {
        self.active
            .store(false, std::sync::atomic::Ordering::Release);
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum ImageMime {
    #[serde(rename = "image/png")]
    Png,
    #[serde(rename = "image/jpeg")]
    Jpeg,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ImageMetadata {
    pub bytes: u32,
    pub mime: ImageMime,
    pub width: u32,
    pub height: u32,
    pub sha256: String,
}
impl ImageMetadata {
    pub fn validate(&self) -> Result<()> {
        if self.bytes == 0 || self.bytes > MAX_IMAGE_BYTES {
            return Err(Error::Protocol("IMAGE_BYTES"));
        }
        if self.width == 0
            || self.height == 0
            || self.width > MAX_IMAGE_SIDE
            || self.height > MAX_IMAGE_SIDE
            || u64::from(self.width) * u64::from(self.height) > MAX_IMAGE_PIXELS
        {
            return Err(Error::Protocol("IMAGE_DIMENSIONS"));
        }
        if self.sha256.len() != 64
            || !self
                .sha256
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(Error::Protocol("IMAGE_DIGEST"));
        }
        Ok(())
    }
    pub fn verify_bytes(&self, bytes: &[u8]) -> Result<()> {
        self.validate()?;
        if bytes.len() != self.bytes as usize || digest(bytes) != self.sha256 {
            return Err(Error::Protocol("IMAGE_INTEGRITY"));
        }
        Ok(())
    }
    pub fn verify_image(&self, bytes: &[u8]) -> Result<payload::PreparedImage> {
        self.verify_bytes(bytes)?;
        let format = image::guess_format(bytes).map_err(|_| Error::Protocol("IMAGE_FORMAT"))?;
        if !matches!(
            (self.mime, format),
            (ImageMime::Png, image::ImageFormat::Png) | (ImageMime::Jpeg, image::ImageFormat::Jpeg)
        ) {
            return Err(Error::Protocol("IMAGE_FORMAT"));
        }
        let prepared = payload::prepare(bytes).map_err(Error::Protocol)?;
        if (self.width, self.height) != (prepared.width, prepared.height) {
            return Err(Error::Protocol("IMAGE_DIMENSIONS"));
        }
        Ok(prepared)
    }
}

pub fn digest(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ImageReference {
    pub resource_id: String,
    pub metadata: ImageMetadata,
}
impl ImageReference {
    pub fn validate(&self) -> Result<()> {
        validate_uuid(&self.resource_id)?;
        self.metadata.validate()
    }
}
