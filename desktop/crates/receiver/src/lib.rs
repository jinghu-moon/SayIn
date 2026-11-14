//! Durable receiver boundary. No GPUI, network transport or localized text.
pub mod gate;
pub mod images;
pub mod ledger;
pub mod protocol;
#[cfg(windows)]
pub mod windows_input;
pub mod worker;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Protocol(&'static str),
    #[error("SQLite: {0}")]
    Database(#[from] rusqlite::Error),
    #[error("Storage: {0}")]
    Io(#[from] std::io::Error),
    #[error("Operating system: {0}")]
    Platform(String),
    #[error("Receiver is unavailable")]
    Unavailable,
}

pub type Result<T> = std::result::Result<T, Error>;
pub mod history;
