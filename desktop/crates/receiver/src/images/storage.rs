//! File commit precedes the receipt transaction. Only ciphertext touches disk.
use super::{ImageMetadata, ImageReference, MAX_IMAGE_BYTES};
use crate::{
    Error, Result,
    ledger::Protector,
    protocol::{strict_json, validate_uuid},
};
use atomic_write_file::AtomicWriteFile;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
};
use uuid::Uuid;
use zeroize::Zeroizing;

const MAGIC: &[u8; 8] = b"SIIMAGE1";
const MAX_ENVELOPE: usize = 4096;
const MAX_PROTECTED: u64 = MAX_IMAGE_BYTES as u64 + 65536;

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    host_id: String,
    device_id: String,
    resource: ImageReference,
}

#[derive(Clone)]
pub struct ImageStore {
    directory: PathBuf,
    host: String,
}
impl ImageStore {
    /// Caller supplies a private, ACL-restricted directory inside its own host namespace.
    pub fn open(directory: &Path, host: &str) -> Result<Self> {
        validate_uuid(host)?;
        fs::create_dir_all(directory)?;
        Ok(Self {
            directory: directory.to_path_buf(),
            host: host.to_owned(),
        })
    }
    fn path(&self, id: &str) -> Result<PathBuf> {
        validate_uuid(id)?;
        Ok(self.directory.join(format!("{id}.image")))
    }
    /// Off the coordinator/UI thread. A successful return means protected bytes
    /// were synced and atomically published, NOT that a receipt was accepted.
    pub fn write(
        &self,
        device: &str,
        metadata: ImageMetadata,
        bytes: &[u8],
        protector: &dyn Protector,
    ) -> Result<ImageReference> {
        validate_uuid(device)?;
        metadata.verify_image(bytes)?;
        let resource = ImageReference {
            resource_id: Uuid::new_v4().to_string(),
            metadata,
        };
        let envelope = serde_json::to_vec(&Envelope {
            host_id: self.host.clone(),
            device_id: device.to_owned(),
            resource: resource.clone(),
        })
        .map_err(|_| Error::Protocol("IMAGE_ENCODING"))?;
        if envelope.len() > MAX_ENVELOPE {
            return Err(Error::Protocol("IMAGE_ENCODING"));
        }
        let mut plaintext = Zeroizing::new(Vec::with_capacity(12 + envelope.len() + bytes.len()));
        plaintext.extend_from_slice(MAGIC);
        plaintext.extend_from_slice(&(envelope.len() as u32).to_be_bytes());
        plaintext.extend_from_slice(&envelope);
        plaintext.extend_from_slice(bytes);
        // Current-user DPAPI is authenticated encryption, already used by the
        // ledger. A bounded 10MiB resource needs no second key hierarchy.
        let protected = protector.protect(&plaintext)?;
        if protected.len() as u64 > MAX_PROTECTED {
            return Err(Error::Protocol("IMAGE_PROTECTED_LIMIT"));
        }
        let path = self.path(&resource.resource_id)?;
        if path.exists() {
            return Err(Error::Protocol("IMAGE_ID_CONFLICT"));
        }
        let mut file = AtomicWriteFile::open(path)?;
        file.write_all(&protected)?;
        file.sync_all()?;
        file.commit()?;
        Ok(resource)
    }
    pub fn read(
        &self,
        device: &str,
        resource: &ImageReference,
        protector: &dyn Protector,
    ) -> Result<Zeroizing<Vec<u8>>> {
        validate_uuid(device)?;
        resource.validate()?;
        let path = self.path(&resource.resource_id)?;
        let mut protected = Vec::new();
        fs::File::open(path)?
            .take(MAX_PROTECTED + 1)
            .read_to_end(&mut protected)?;
        if protected.len() as u64 > MAX_PROTECTED {
            return Err(Error::Protocol("IMAGE_PROTECTED_LIMIT"));
        }
        let plaintext = protector.unprotect(&protected)?;
        if plaintext.len() < 12 || &plaintext[..8] != MAGIC {
            return Err(Error::Protocol("IMAGE_STORAGE_FORMAT"));
        }
        let size = u32::from_be_bytes(plaintext[8..12].try_into().expect("fixed slice")) as usize;
        if size > MAX_ENVELOPE || 12 + size > plaintext.len() {
            return Err(Error::Protocol("IMAGE_STORAGE_FORMAT"));
        }
        let envelope: Envelope = serde_json::from_value(strict_json(&plaintext[12..12 + size])?)
            .map_err(|_| Error::Protocol("IMAGE_STORAGE_FORMAT"))?;
        if envelope.host_id != self.host
            || envelope.device_id != device
            || envelope.resource != *resource
        {
            return Err(Error::Protocol("IMAGE_RESOURCE_SCOPE"));
        }
        let bytes = Zeroizing::new(plaintext[12 + size..].to_vec());
        resource.metadata.verify_bytes(&bytes)?;
        Ok(bytes)
    }
    pub fn remove(&self, id: &str) -> Result<()> {
        match fs::remove_file(self.path(id)?) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error.into()),
        }
    }
    /// Startup only, before upload tasks exist. No scan on the accept hot path.
    pub fn clean_orphans(&self, mut referenced: impl FnMut(&str) -> Result<bool>) -> Result<()> {
        for entry in fs::read_dir(&self.directory)? {
            let entry = entry?;
            if !entry.file_type()?.is_file() {
                return Err(Error::Protocol("IMAGE_STORAGE_LAYOUT"));
            }
            let name = entry.file_name();
            let id = name.to_str().and_then(|s| s.strip_suffix(".image"));
            let keep = if let Some(id) = id.filter(|id| validate_uuid(id).is_ok()) {
                referenced(id)?
            } else {
                false
            };
            if !keep {
                fs::remove_file(entry.path())?;
            }
        }
        Ok(())
    }
}
