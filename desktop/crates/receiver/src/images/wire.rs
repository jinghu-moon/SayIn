//! One binary message is one sequential chunk; never interpreted on control WSS.
use super::{ImageMetadata, MAX_CHUNK_BYTES};
use crate::{Error, Result, protocol::validate_uuid};
use std::time::{Duration, Instant};
use uuid::Uuid;
use zeroize::{Zeroize, Zeroizing};

pub const CHUNK_HEADER_BYTES: usize = 32;
pub const UPLOAD_IDLE: Duration = Duration::from_secs(60);
pub const UPLOAD_LIFETIME: Duration = Duration::from_secs(300);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UploadScope {
    pub device: String,
    pub session: String,
    pub target: String,
    pub revision: String,
}
impl UploadScope {
    pub fn validate(&self) -> Result<()> {
        validate_uuid(&self.device)?;
        validate_uuid(&self.session)?;
        if self.target.is_empty()
            || self.target.len() > 128
            || self.revision.is_empty()
            || self.revision.len() > 32
        {
            return Err(Error::Protocol("IMAGE_SCOPE"));
        }
        Ok(())
    }
}

pub fn encode_chunk(id: &str, offset: u64, bytes: &[u8]) -> Result<Vec<u8>> {
    validate_uuid(id)?;
    if bytes.is_empty()
        || bytes.len() > MAX_CHUNK_BYTES
        || offset
            .checked_add(bytes.len() as u64)
            .is_none_or(|end| end > u64::from(super::MAX_IMAGE_BYTES))
    {
        return Err(Error::Protocol("IMAGE_CHUNK"));
    }
    let mut frame = Vec::with_capacity(CHUNK_HEADER_BYTES + bytes.len());
    frame.extend_from_slice(b"SIMG");
    frame.extend_from_slice(Uuid::parse_str(id).expect("validated UUID").as_bytes());
    frame.extend_from_slice(&offset.to_be_bytes());
    frame.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    frame.extend_from_slice(bytes);
    Ok(frame)
}

pub struct Upload {
    pub id: String,
    pub metadata: ImageMetadata,
    scope: UploadScope,
    bytes: Zeroizing<Vec<u8>>,
    created: Instant,
    updated: Instant,
    invalid: bool,
}
impl Upload {
    pub fn new(
        id: String,
        scope: UploadScope,
        metadata: ImageMetadata,
        now: Instant,
    ) -> Result<Self> {
        validate_uuid(&id)?;
        scope.validate()?;
        metadata.validate()?;
        Ok(Self {
            id,
            scope,
            metadata,
            bytes: Zeroizing::new(Vec::new()),
            created: now,
            updated: now,
            invalid: false,
        })
    }
    pub fn received(&self) -> usize {
        self.bytes.len()
    }
    pub fn expired(&self, now: Instant) -> bool {
        now.saturating_duration_since(self.created) >= UPLOAD_LIFETIME
            || now.saturating_duration_since(self.updated) >= UPLOAD_IDLE
    }
    pub fn push(&mut self, scope: &UploadScope, frame: &[u8], now: Instant) -> Result<usize> {
        let result = self.push_checked(scope, frame, now);
        if result.is_err() {
            self.invalid = true;
            self.bytes.zeroize();
            self.bytes.clear();
        }
        result
    }
    fn push_checked(&mut self, scope: &UploadScope, frame: &[u8], now: Instant) -> Result<usize> {
        if self.invalid || &self.scope != scope || self.expired(now) {
            return Err(Error::Protocol("IMAGE_SCOPE"));
        }
        if frame.len() <= CHUNK_HEADER_BYTES
            || frame.len() > CHUNK_HEADER_BYTES + MAX_CHUNK_BYTES
            || &frame[..4] != b"SIMG"
        {
            return Err(Error::Protocol("IMAGE_CHUNK"));
        }
        let id = Uuid::from_slice(&frame[4..20]).map_err(|_| Error::Protocol("IMAGE_CHUNK"))?;
        let offset = u64::from_be_bytes(frame[20..28].try_into().expect("fixed slice"));
        let length = u32::from_be_bytes(frame[28..32].try_into().expect("fixed slice")) as usize;
        if id.to_string() != self.id
            || offset != self.bytes.len() as u64
            || length != frame.len() - CHUNK_HEADER_BYTES
            || self
                .bytes
                .len()
                .checked_add(length)
                .is_none_or(|end| end > self.metadata.bytes as usize)
        {
            return Err(Error::Protocol("IMAGE_CHUNK"));
        }
        self.bytes
            .try_reserve(length)
            .map_err(|_| Error::Protocol("IMAGE_ALLOCATION"))?;
        self.bytes.extend_from_slice(&frame[CHUNK_HEADER_BYTES..]);
        self.updated = now;
        Ok(self.bytes.len())
    }
    pub fn finish(self, scope: &UploadScope, now: Instant) -> Result<Zeroizing<Vec<u8>>> {
        if self.invalid || &self.scope != scope || self.expired(now) {
            return Err(Error::Protocol("IMAGE_SCOPE"));
        }
        self.metadata.verify_bytes(&self.bytes)?;
        Ok(self.bytes)
    }
}
