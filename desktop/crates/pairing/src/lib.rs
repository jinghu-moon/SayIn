//! Shared code-pairing bootstrap. No custom curve operations or password hashes.
//! CPace messages and mutual key confirmation use the pinned 1Password library.
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use chacha20poly1305::{
    ChaCha20Poly1305, KeyInit,
    aead::{Aead, Payload},
};
use hkdf::Hkdf;
use pake_cpace::{CPace, ChannelIdentifier, NoAD, SessionAD, UnverifiedNegotiation};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    net::{IpAddr, SocketAddr, TcpStream},
    time::Duration,
};
use zeroize::Zeroizing;

pub const MAGIC: &[u8; 6] = b"VLCP3\n";
pub const MAX_BOOTSTRAP_BYTES: usize = 8192;
const DOMAIN: &[u8] = b"SayIn/v3/code-pairing/CPace-draft06/Ristretto255/SHA512";
mod compact;
pub use compact::Invitation;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("PAIRING_INVALID")]
    Invalid,
    #[error("PAIRING_AUTH_FAILED")]
    Authentication,
    #[error("PAIRING_CRYPTO_FAILED")]
    Crypto,
    #[error("PAIRING_IO_FAILED")]
    Io(#[from] std::io::Error),
    #[error("PAIRING_JSON_INVALID")]
    Json(#[from] serde_json::Error),
}
pub type Result<T> = std::result::Result<T, Error>;

pub fn encode(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}
pub fn decode(text: &str) -> Result<Vec<u8>> {
    if text.len() > MAX_BOOTSTRAP_BYTES {
        return Err(Error::Invalid);
    }
    let bytes = URL_SAFE_NO_PAD.decode(text).map_err(|_| Error::Invalid)?;
    if encode(&bytes) != text {
        return Err(Error::Invalid);
    }
    Ok(bytes)
}
pub fn random<const N: usize>() -> Result<[u8; N]> {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).map_err(|_| Error::Crypto)?;
    Ok(bytes)
}
pub fn fingerprint(certificate: &[u8]) -> String {
    encode(&Sha256::digest(certificate))
}

/// Generate eight digits without modulo bias. Leading zeroes are significant.
pub fn pairing_code() -> Result<Zeroizing<String>> {
    let mut code = String::with_capacity(8);
    while code.len() < 8 {
        for value in random::<16>()? {
            if value < 250 {
                code.push(char::from(b'0' + value % 10));
            }
            if code.len() == 8 {
                break;
            }
        }
    }
    Ok(Zeroizing::new(code))
}

#[derive(Clone, Serialize, Deserialize)]
#[non_exhaustive]
pub struct BootstrapInfo {
    pub v: u8,
    pub host_id: String,
    pub window_id: String,
}
impl BootstrapInfo {
    pub fn new(host_id: String, window_id: String) -> Self {
        Self {
            v: 3,
            host_id,
            window_id,
        }
    }
    fn validate(&self) -> Result<()> {
        if self.v != 3 {
            return Err(Error::Invalid);
        }
        for value in [&self.host_id, &self.window_id] {
            compact::parse_uuid(value)?;
        }
        Ok(())
    }
}

fn context<'a>(info: &'a BootstrapInfo, extra: &'a [&'a [u8]]) -> ChannelIdentifier<'a> {
    ChannelIdentifier {
        id_a: b"VoiceLink-phone",
        id_b: info.host_id.as_bytes(),
        extra_context: extra,
    }
}

pub struct Initiator {
    state: CPace,
}
pub struct Negotiation {
    state: UnverifiedNegotiation,
}
pub struct VerifiedKey(Zeroizing<[u8; 32]>);

impl Initiator {
    pub fn start(info: &BootstrapInfo, code: &str) -> Result<(Self, Vec<u8>)> {
        validate_code(code)?;
        info.validate()?;
        let session = random::<16>()?;
        let extra = [DOMAIN, info.window_id.as_bytes()];
        let state = CPace::new(
            &session,
            code.as_bytes(),
            &context(info, &extra),
            &SessionAD {
                session_id: session,
            },
        )
        .map_err(|_| Error::Crypto)?;
        let message = state.message().to_vec();
        Ok((Self { state }, message))
    }
    pub fn finish(self, message: Vec<u8>) -> Result<Negotiation> {
        if message.len() != 32 {
            return Err(Error::Invalid);
        }
        let (state, _) = self
            .state
            .initiator_process_message::<NoAD>(message)
            .map_err(|_| Error::Authentication)?;
        Ok(Negotiation { state })
    }
}
pub fn respond(info: &BootstrapInfo, code: &str, message: Vec<u8>) -> Result<Negotiation> {
    validate_code(code)?;
    info.validate()?;
    if message.len() != 48 {
        return Err(Error::Invalid);
    }
    let (processed, ad) = CPace::responder_process_message::<SessionAD>(message)
        .map_err(|_| Error::Authentication)?;
    let extra = [DOMAIN, info.window_id.as_bytes()];
    let state = CPace::new(
        &ad.session_id,
        code.as_bytes(),
        &context(info, &extra),
        &NoAD {},
    )
    .map_err(|_| Error::Crypto)?;
    Ok(Negotiation {
        state: processed.finish(state).map_err(|_| Error::Authentication)?,
    })
}
fn validate_code(code: &str) -> Result<()> {
    if code.len() != 8 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(Error::Invalid);
    }
    Ok(())
}
impl Negotiation {
    pub fn message(&self) -> &[u8] {
        self.state.own_message()
    }
    pub fn tag(&self) -> [u8; 64] {
        self.state.tag()
    }
    pub fn verify(self, tag: &[u8]) -> Result<VerifiedKey> {
        let key = Zeroizing::new(
            self.state
                .verify_tag(tag)
                .map_err(|_| Error::Authentication)?,
        );
        let mut derived = Zeroizing::new([0; 32]);
        Hkdf::<Sha256>::new(Some(DOMAIN), &*key)
            .expand(b"authenticated-certificate-envelope", &mut *derived)
            .map_err(|_| Error::Crypto)?;
        Ok(VerifiedKey(derived))
    }
}
impl VerifiedKey {
    pub fn seal(&self, info: &BootstrapInfo, plaintext: &[u8]) -> Result<Vec<u8>> {
        if plaintext.len() > 4096 {
            return Err(Error::Invalid);
        }
        let nonce = random::<12>()?;
        let aad = serde_json::to_vec(info)?;
        let cipher = ChaCha20Poly1305::new((&*self.0).into());
        let mut encrypted = cipher
            .encrypt(
                (&nonce).into(),
                Payload {
                    msg: plaintext,
                    aad: &aad,
                },
            )
            .map_err(|_| Error::Crypto)?;
        let mut result = nonce.to_vec();
        result.append(&mut encrypted);
        Ok(result)
    }
    pub fn open(&self, info: &BootstrapInfo, ciphertext: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
        if ciphertext.len() < 28 || ciphertext.len() > 4124 {
            return Err(Error::Invalid);
        }
        let nonce: &[u8; 12] = ciphertext[..12].try_into().map_err(|_| Error::Invalid)?;
        let aad = serde_json::to_vec(info)?;
        let cipher = ChaCha20Poly1305::new((&*self.0).into());
        cipher
            .decrypt(
                nonce.into(),
                Payload {
                    msg: &ciphertext[12..],
                    aad: &aad,
                },
            )
            .map(Zeroizing::new)
            .map_err(|_| Error::Authentication)
    }
}

#[derive(Serialize, Deserialize)]
#[non_exhaustive]
pub struct Exchange {
    pub message: String,
    pub tag: String,
}
impl Exchange {
    pub fn new(message: &[u8], tag: &[u8]) -> Self {
        Self {
            message: encode(message),
            tag: encode(tag),
        }
    }
}
pub fn write_frame(stream: &mut impl Write, bytes: &[u8]) -> Result<()> {
    if bytes.len() > MAX_BOOTSTRAP_BYTES {
        return Err(Error::Invalid);
    }
    stream.write_all(&(bytes.len() as u32).to_be_bytes())?;
    stream.write_all(bytes)?;
    stream.flush()?;
    Ok(())
}
pub fn read_frame(stream: &mut impl Read) -> Result<Vec<u8>> {
    let mut length = [0; 4];
    stream.read_exact(&mut length)?;
    let length = u32::from_be_bytes(length) as usize;
    if length > MAX_BOOTSTRAP_BYTES {
        return Err(Error::Invalid);
    }
    let mut bytes = vec![0; length];
    stream.read_exact(&mut bytes)?;
    Ok(bytes)
}

/// Blocking bootstrap for Android IO dispatchers and interoperability tests.
/// Only numeric IPs are accepted, avoiding ambient DNS redirects during pairing.
pub fn pair_code(address: IpAddr, port: u16, code: &str) -> Result<Invitation> {
    let mut stream =
        TcpStream::connect_timeout(&SocketAddr::new(address, port), Duration::from_secs(5))?;
    stream.set_nodelay(true)?;
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    stream.set_write_timeout(Some(Duration::from_secs(5)))?;
    stream.write_all(MAGIC)?;
    let info: BootstrapInfo = serde_json::from_slice(&read_frame(&mut stream)?)?;
    let (initiator, message) = Initiator::start(&info, code)?;
    write_frame(&mut stream, &message)?;
    let response: Exchange = serde_json::from_slice(&read_frame(&mut stream)?)?;
    let negotiation = initiator.finish(decode(&response.message)?)?;
    let own_tag = negotiation.tag();
    let key = negotiation.verify(&decode(&response.tag)?)?;
    write_frame(&mut stream, &own_tag)?;
    let ciphertext = read_frame(&mut stream)?;
    let plaintext = key.open(&info, &ciphertext)?;
    let mut invitation = Invitation::from_bytes(&plaintext)?;
    if invitation.host_id != info.host_id || invitation.port != port {
        return Err(Error::Authentication);
    }
    // Route to the IP the user selected; authenticated host identity stays fixed.
    invitation.address = address;
    Ok(invitation)
}

#[cfg(target_os = "android")]
mod android;

#[cfg(test)]
mod tests {
    use super::*;
    fn info() -> BootstrapInfo {
        BootstrapInfo::new(
            "12345678-1234-4234-8234-123456789012".into(),
            "87654321-4321-4321-8321-210987654321".into(),
        )
    }
    #[test]
    fn correct_code_mutually_confirms_and_authenticates_certificate_envelope() {
        let info = info();
        let (client, message) = Initiator::start(&info, "00123456").unwrap();
        let server = respond(&info, "00123456", message).unwrap();
        let client = client.finish(server.message().to_vec()).unwrap();
        let (a, b) = (client.tag(), server.tag());
        let client = client.verify(&b).unwrap();
        let server = server.verify(&a).unwrap();
        let encrypted = server.seal(&info, b"certificate material").unwrap();
        assert_eq!(
            &**client.open(&info, &encrypted).unwrap(),
            b"certificate material"
        );
        let mut bad = encrypted.clone();
        bad[20] ^= 1;
        assert!(client.open(&info, &bad).is_err());
        let mut other = info.clone();
        other.window_id.replace_range(..1, "a");
        assert!(client.open(&other, &encrypted).is_err());
    }
    #[test]
    fn wrong_code_context_and_invalid_curve_point_do_not_confirm() {
        let info = info();
        let (client, message) = Initiator::start(&info, "00123456").unwrap();
        let server = respond(&info, "99999999", message).unwrap();
        let client = client.finish(server.message().to_vec()).unwrap();
        let (a, b) = (client.tag(), server.tag());
        assert!(client.verify(&b).is_err());
        assert!(server.verify(&a).is_err());
        assert!(respond(&info, "00123456", vec![0; 48]).is_err());
        let (client, message) = Initiator::start(&info, "00123456").unwrap();
        let mut other = info.clone();
        other.window_id.replace_range(..1, "a");
        let server = respond(&other, "00123456", message).unwrap();
        let client = client.finish(server.message().to_vec()).unwrap();
        assert!(client.verify(&server.tag()).is_err());
    }
    #[test]
    fn qr_roundtrip_fingerprint_and_input_bounds() {
        let invitation = Invitation::new(
            &info().host_id,
            "vl-12345678-1234-4234-8234-123456789012.local",
            "127.0.0.1".parse().unwrap(),
            7788,
            b"certificate",
            &[1; 16],
        )
        .unwrap();
        let qr = invitation.qr().unwrap();
        assert_eq!(qr.len(), 114);
        assert!(qr.starts_with("SAYIN3:"));
        assert_eq!(
            Invitation::from_qr(&qr).unwrap().to_bytes().unwrap(),
            invitation.to_bytes().unwrap()
        );
        assert_eq!(invitation.sha256, fingerprint(b"certificate"));
        assert!(Initiator::start(&info(), "1234").is_err());
        assert!(read_frame(&mut &u32::MAX.to_be_bytes()[..]).is_err());
        for _ in 0..20 {
            let code = pairing_code().unwrap();
            assert_eq!(code.len(), 8);
            assert!(code.bytes().all(|b| b.is_ascii_digit()));
        }
    }
}
