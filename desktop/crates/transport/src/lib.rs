//! Bounded local-network pairing + TLS1.3/WebSocket transport.
mod identity;
pub mod interfaces;
pub mod server;
pub mod storage;
pub use identity::HostIdentity;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Protocol(&'static str),
    #[error("Network unavailable")]
    Io(#[from] std::io::Error),
    #[error("Receiver rejected operation")]
    Receiver(#[from] voicelink_receiver::Error),
    #[error("Invalid message")]
    Json(#[from] serde_json::Error),
    #[error("TLS configuration failed")]
    Tls(#[from] rustls::Error),
    #[error("Pairing failed")]
    Pairing(#[from] voicelink_pairing::Error),
    #[error("WebSocket closed")]
    WebSocket(#[from] tokio_tungstenite::tungstenite::Error),
}
pub type Result<T> = std::result::Result<T, Error>;
