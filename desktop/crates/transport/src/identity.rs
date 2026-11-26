use crate::{Error, Result};
use atomic_write_file::AtomicWriteFile;
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair};
use rustls::{
    ServerConfig,
    pki_types::{CertificateDer, PrivatePkcs8KeyDer},
};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{Read, Write},
    path::Path,
    sync::Arc,
};
use time::{Duration, OffsetDateTime};
use uuid::Uuid;
use voicelink_receiver::{ledger::Protector, windows_input::Dpapi};
use zeroize::{Zeroize, Zeroizing};

#[derive(Serialize, Deserialize)]
struct Material {
    version: u8,
    host_id: String,
    cert: Vec<u8>,
    key: Vec<u8>,
    expires: i64,
    ledger_key_fingerprint: String,
}
impl Drop for Material {
    fn drop(&mut self) {
        self.key.zeroize();
    }
}

#[derive(Clone)]
pub struct HostIdentity {
    pub(crate) host_id: String,
    pub(crate) cert: Vec<u8>,
    pub(crate) tls_name: String,
    pub(crate) tls: Arc<ServerConfig>,
}

impl HostIdentity {
    pub fn host_id(&self) -> &str {
        &self.host_id
    }
    pub fn open(directory: &Path, paired_devices: u32) -> Result<Self> {
        let path = directory.join("host-identity.dpapi");
        let ledger_key = fs::read(directory.join("receiver-key.dpapi"))?;
        let ledger_key_fingerprint = voicelink_pairing::fingerprint(&ledger_key);
        let material = if path.exists() {
            let mut bytes = Vec::new();
            fs::File::open(&path)?.take(16385).read_to_end(&mut bytes)?;
            if bytes.len() > 16384 {
                return Err(Error::Protocol("HOST_IDENTITY_INVALID"));
            }
            let plaintext = Dpapi.unprotect(&bytes)?;
            let material: Material = serde_json::from_slice(&plaintext)?;
            if !matches!(material.version, 1 | 2)
                || material.ledger_key_fingerprint != ledger_key_fingerprint
                || Uuid::parse_str(&material.host_id).is_err()
                || material.expires <= OffsetDateTime::now_utc().unix_timestamp()
            {
                return Err(Error::Protocol("HOST_IDENTITY_INVALID"));
            }
            material
        } else {
            if paired_devices > 0 {
                return Err(Error::Protocol("HOST_IDENTITY_LOST"));
            }
            let host_id = Uuid::new_v4().to_string();
            let key =
                KeyPair::generate().map_err(|_| Error::Protocol("CERTIFICATE_CREATE_FAILED"))?;
            let mut params = CertificateParams::new(vec![format!("sayin-{host_id}.local")])
                .map_err(|_| Error::Protocol("CERTIFICATE_CREATE_FAILED"))?;
            params.not_before = OffsetDateTime::now_utc() - Duration::days(1);
            params.not_after = OffsetDateTime::now_utc() + Duration::days(3650);
            params.distinguished_name = DistinguishedName::new();
            params
                .distinguished_name
                .push(DnType::CommonName, "VoiceLink");
            let certificate = params
                .self_signed(&key)
                .map_err(|_| Error::Protocol("CERTIFICATE_CREATE_FAILED"))?;
            let material = Material {
                version: 2,
                host_id,
                cert: certificate.der().to_vec(),
                key: key.serialize_der(),
                expires: params.not_after.unix_timestamp(),
                ledger_key_fingerprint,
            };
            let plaintext = Zeroizing::new(serde_json::to_vec(&material)?);
            let encrypted = Dpapi.protect(&plaintext)?;
            let mut file = AtomicWriteFile::open(&path)?;
            file.write_all(&encrypted)?;
            file.commit()?;
            material
        };
        let mut tls =
            ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(&[&rustls::version::TLS13])?
                .with_no_client_auth()
                .with_single_cert(
                    vec![CertificateDer::from(material.cert.clone())],
                    PrivatePkcs8KeyDer::from(material.key.clone()).into(),
                )?;
        tls.max_early_data_size = 0;
        tls.send_tls13_tickets = 0;
        Ok(Self {
            host_id: material.host_id.clone(),
            cert: material.cert.clone(),
            tls_name: format!(
                "{}-{}.local",
                if material.version == 1 { "vl" } else { "sayin" },
                material.host_id
            ),
            tls: Arc::new(tls),
        })
    }
}
