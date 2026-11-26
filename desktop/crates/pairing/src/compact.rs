use super::{Error, Result, decode, encode, fingerprint};
use serde::Serialize;
use std::net::{IpAddr, Ipv4Addr};
use uuid::Uuid;
use zeroize::Zeroizing;

const PREFIX: &str = "SAYIN3:";
const ALPHABET: &[u8; 45] = b"0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
pub const INVITATION_BYTES: usize = 71;
const BASE45_BYTES: usize = 107;

#[derive(Clone, Serialize)]
#[non_exhaustive]
pub struct Invitation {
    pub v: u8,
    pub host_id: String,
    pub tls_name: String,
    pub address: IpAddr,
    pub port: u16,
    pub sha256: String,
    pub secret: String,
}

pub(super) fn parse_uuid(value: &str) -> Result<Uuid> {
    let uuid = Uuid::parse_str(value).map_err(|_| Error::Invalid)?;
    if uuid.get_version_num() != 4
        || uuid.get_variant() != uuid::Variant::RFC4122
        || uuid.to_string() != value
    {
        return Err(Error::Invalid);
    }
    Ok(uuid)
}

impl Invitation {
    pub fn new(
        host_id: &str,
        tls_name: &str,
        address: IpAddr,
        port: u16,
        cert: &[u8],
        secret: &[u8; 16],
    ) -> Result<Self> {
        if cert.is_empty() || cert.len() > 4096 {
            return Err(Error::Invalid);
        }
        let invitation = Self {
            v: 3,
            host_id: host_id.into(),
            tls_name: tls_name.into(),
            address,
            port,
            sha256: fingerprint(cert),
            secret: encode(secret),
        };
        invitation.to_bytes()?;
        Ok(invitation)
    }

    pub fn to_bytes(&self) -> Result<Zeroizing<[u8; INVITATION_BYTES]>> {
        let id = parse_uuid(&self.host_id)?;
        let prefix = if self.tls_name == format!("vl-{id}.local") {
            0
        } else if self.tls_name == format!("sayin-{id}.local") {
            1
        } else {
            return Err(Error::Invalid);
        };
        let IpAddr::V4(address) = self.address else {
            return Err(Error::Invalid);
        };
        if self.v != 3
            || self.port == 0
            || address.is_unspecified()
            || address.is_multicast()
            || address.is_broadcast()
        {
            return Err(Error::Invalid);
        }
        let digest: [u8; 32] = decode(&self.sha256)?
            .try_into()
            .map_err(|_| Error::Invalid)?;
        let secret = Zeroizing::new(decode(&self.secret)?);
        if secret.len() != 16 {
            return Err(Error::Invalid);
        }
        let mut bytes = Zeroizing::new([0; INVITATION_BYTES]);
        bytes[0] = prefix;
        bytes[1..5].copy_from_slice(&address.octets());
        bytes[5..7].copy_from_slice(&self.port.to_be_bytes());
        bytes[7..23].copy_from_slice(id.as_bytes());
        bytes[23..55].copy_from_slice(&digest);
        bytes[55..].copy_from_slice(&secret);
        Ok(bytes)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self> {
        let bytes: &[u8; INVITATION_BYTES] = bytes.try_into().map_err(|_| Error::Invalid)?;
        let prefix = match bytes[0] {
            0 => "vl",
            1 => "sayin",
            _ => return Err(Error::Invalid),
        };
        let id = Uuid::from_slice(&bytes[7..23]).map_err(|_| Error::Invalid)?;
        let invitation = Self {
            v: 3,
            host_id: id.to_string(),
            tls_name: format!("{prefix}-{id}.local"),
            address: Ipv4Addr::new(bytes[1], bytes[2], bytes[3], bytes[4]).into(),
            port: u16::from_be_bytes([bytes[5], bytes[6]]),
            sha256: encode(&bytes[23..55]),
            secret: encode(&bytes[55..]),
        };
        invitation.to_bytes()?;
        Ok(invitation)
    }

    pub fn qr(&self) -> Result<String> {
        let bytes = self.to_bytes()?;
        let mut text = String::with_capacity(PREFIX.len() + BASE45_BYTES);
        text.push_str(PREFIX);
        for chunk in bytes.chunks(2) {
            let value = match chunk {
                [high, low] => u16::from_be_bytes([*high, *low]),
                [last] => u16::from(*last),
                _ => unreachable!(),
            } as usize;
            text.push(char::from(ALPHABET[value % 45]));
            text.push(char::from(ALPHABET[value / 45 % 45]));
            if chunk.len() == 2 {
                text.push(char::from(ALPHABET[value / (45 * 45)]));
            }
        }
        Ok(text)
    }

    pub fn from_qr(text: &str) -> Result<Self> {
        let text = text.strip_prefix(PREFIX).ok_or(Error::Invalid)?.as_bytes();
        if text.len() != BASE45_BYTES {
            return Err(Error::Invalid);
        }
        let mut bytes = Zeroizing::new([0; INVITATION_BYTES]);
        let digit = |byte| {
            ALPHABET
                .iter()
                .position(|&candidate| candidate == byte)
                .ok_or(Error::Invalid)
        };
        for (chunk, decoded) in text.chunks(3).zip(bytes.chunks_mut(2)) {
            let pair = digit(chunk[0])? + 45 * digit(chunk[1])?;
            if let Some(&third) = chunk.get(2) {
                let value =
                    u16::try_from(pair + 45 * 45 * digit(third)?).map_err(|_| Error::Invalid)?;
                decoded.copy_from_slice(&value.to_be_bytes());
            } else {
                decoded[0] = u8::try_from(pair).map_err(|_| Error::Invalid)?;
            }
        }
        Self::from_bytes(&*bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compact_golden_vector_and_invalid_groups() {
        let qr = "SAYIN3:N80000G60 TDXR638FDR6WS6AQ6XR6*AFIC2X507H0QS00+0J61%H1CT1V+1572OI2/T2H-2 73AJ3TU3G-3X507H0QS00+0J61%H1CT1F0";
        let invitation = Invitation::from_qr(qr).unwrap();
        let bytes = invitation.to_bytes().unwrap();
        assert_eq!(&bytes[..7], &[1, 127, 0, 0, 1, 30, 108]);
        assert_eq!(&bytes[23..55], &(0..32).collect::<Vec<_>>());
        assert_eq!(&bytes[55..], &(0..16).collect::<Vec<_>>());
        assert_eq!(invitation.qr().unwrap(), qr);
        assert_eq!(
            invitation.tls_name,
            "sayin-12345678-1234-4234-8234-123456789012.local"
        );
        let invalid_qr = [
            qr[..113].to_owned(),
            qr.replacen("N80", ":::", 1),
            format!("{}::", &qr[..112]),
            qr.replacen("N80", "n80", 1),
        ];
        for malformed in invalid_qr {
            assert!(Invitation::from_qr(&malformed).is_err());
        }
        for (offset, value) in [(0, 2), (1, 224), (13, 0), (15, 0)] {
            let mut malformed = *bytes;
            malformed[offset] = value;
            assert!(Invitation::from_bytes(&malformed).is_err());
        }
        let mut no_port = *bytes;
        no_port[5..7].fill(0);
        assert!(Invitation::from_bytes(&no_port).is_err());
        assert!(Invitation::from_bytes(&bytes[..70]).is_err());
    }
}
