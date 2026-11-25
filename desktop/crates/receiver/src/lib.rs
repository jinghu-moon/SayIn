//! Durable receiver boundary. No GPUI, network transport or localized text.
pub mod application_icon;
pub mod application_name;
pub mod gate;
pub mod images;
pub mod ledger;
pub mod protocol;
pub mod protocol_v3;
#[cfg(windows)]
pub mod windows_clipboard;
#[cfg(windows)]
pub mod windows_image_delivery;
#[cfg(windows)]
pub mod windows_input;
pub mod worker;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Protocol(&'static str),
    #[error("SQLite: {0}")]
    Database(rusqlite::Error),
    #[error("Storage: {0}")]
    Io(#[from] std::io::Error),
    #[error("Operating system: {0}")]
    Platform(String),
    #[error("Receiver is unavailable")]
    Unavailable,
}

impl From<rusqlite::Error> for Error {
    fn from(error: rusqlite::Error) -> Self {
        match &error {
            rusqlite::Error::SqliteFailure(code, Some(message))
                if code.extended_code == rusqlite::ffi::SQLITE_CONSTRAINT_TRIGGER
                    && message == "STATE_CONFLICT" =>
            {
                Self::Protocol("STATE_CONFLICT")
            }
            _ => Self::Database(error),
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;
pub mod history;
