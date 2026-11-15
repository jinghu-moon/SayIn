//! Single-owner SQLite ledger. A receipt is returned only after commit succeeds.
use crate::{
    Error, Result,
    protocol::{Operation, Receipt, State, Submit, validate_uuid},
};
use hmac::{Hmac, KeyInit, Mac};
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::{
    collections::HashMap,
    path::Path,
    time::{Duration, Instant},
};
use uuid::Uuid;
use zeroize::Zeroizing;

pub trait Protector: Send + 'static {
    fn protect(&self, plaintext: &[u8]) -> Result<Vec<u8>>;
    fn unprotect(&self, ciphertext: &[u8]) -> Result<Zeroizing<Vec<u8>>>;
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[non_exhaustive]
pub struct ControlState {
    pub state_rev: String,
    pub paused: bool,
    pub pause_reason: Option<String>,
    pub active_device_id: Option<String>,
    pub target: Option<String>,
}

/// Local presentation metadata; never includes payload text, token or HWND.
#[derive(Clone, Debug)]
#[non_exhaustive]
pub struct ReceiverSnapshot {
    pub control: ControlState,
    pub storage_healthy: bool,
    pub paired_devices: u32,
    pub device_ids: Vec<String>,
    pub device_names: std::collections::BTreeMap<String, String>,
    pub pending: u32,
    pub blocked: Option<Receipt>,
    pub recent: Vec<Receipt>,
}

#[derive(Clone, Debug)]
pub struct Session {
    device: String,
    id: String,
}
impl Session {
    pub fn device(&self) -> &str {
        &self.device
    }
    pub fn id(&self) -> &str {
        &self.id
    }
}

pub struct Execution {
    device: String,
    id: String,
    target: String,
    operation: Operation,
    image_authorization: Option<std::sync::Arc<crate::images::ImageAuthorization>>,
}
impl Drop for Execution {
    fn drop(&mut self) {
        use zeroize::Zeroize as _;
        if let Operation::Text { text, .. } = &mut self.operation {
            text.zeroize();
        }
    }
}
impl Execution {
    pub fn image_authorized(&self) -> bool {
        !matches!(self.operation, Operation::Image { .. })
            || self
                .image_authorization
                .as_ref()
                .is_some_and(|value| value.valid())
    }
    pub fn device(&self) -> &str {
        &self.device
    }
    pub fn id(&self) -> &str {
        &self.id
    }
    pub fn target(&self) -> &str {
        &self.target
    }
    pub fn operation(&self) -> &Operation {
        &self.operation
    }
}

#[derive(Clone, Debug)]
pub enum InputOutcome {
    Injected { method: &'static str },
    Failed { reason: &'static str },
    Uncertain { reason: &'static str },
}

struct Bucket {
    tokens: f64,
    updated: Instant,
}

struct PendingRow {
    device: String,
    id: String,
    state: String,
    payload: Option<Vec<u8>>,
    target: Option<String>,
}
impl Bucket {
    fn take(&mut self, now: Instant) -> bool {
        self.tokens = (self.tokens + now.duration_since(self.updated).as_secs_f64() * 20.).min(4.);
        self.updated = now;
        if self.tokens < 1. {
            return false;
        }
        self.tokens -= 1.;
        true
    }
}

pub struct Ledger {
    pub(crate) database: Connection,
    key: Zeroizing<Vec<u8>>,
    pub(crate) protector: Box<dyn Protector>,
    sessions: HashMap<String, String>,
    rates: HashMap<String, Bucket>,
    deadlines: HashMap<(String, String), Instant>,
    healthy: bool,
    pub(crate) image_store: Option<crate::images::storage::ImageStore>,
    pub(crate) prepared_images: HashMap<
        String,
        (
            crate::images::wire::UploadScope,
            crate::images::storage::PreparedResource,
            Instant,
        ),
    >,
    pub(crate) image_authorizations:
        HashMap<String, (String, std::sync::Arc<crate::images::ImageAuthorization>)>,
    // Fields drop in declaration order: keep the namespace lock until SQLite
    // has closed/checkpointed and all protected state has been released.
    _lock: std::fs::File,
}

pub(crate) fn receipt(row: &Row<'_>) -> rusqlite::Result<Receipt> {
    let state: String = row.get(2)?;
    let st = match state.as_str() {
        "accepted" => State::Accepted,
        "held" => State::Held,
        "executing" => State::Executing,
        "injected" => State::Injected,
        "cancelled" => State::Cancelled,
        "expired" => State::Expired,
        "failed" => State::Failed,
        "uncertain" => State::Uncertain,
        _ => return Err(rusqlite::Error::InvalidQuery),
    };
    Ok(Receipt {
        id: row.get(0)?,
        order: row.get::<_, i64>(1)?.to_string(),
        st,
        reason: row.get(3)?,
        method: row.get(4)?,
    })
}

impl Ledger {
    pub fn open(
        path: &Path,
        key: Zeroizing<Vec<u8>>,
        protector: Box<dyn Protector>,
    ) -> Result<Self> {
        if key.len() != 32 {
            return Err(Error::Protocol("INVALID_LEDGER_KEY"));
        }
        let lock = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(path.with_extension("lock"))?;
        lock.try_lock()
            .map_err(|_| Error::Protocol("LEDGER_IN_USE"))?;
        let existing_database = path.exists();
        let flags = OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let database = Connection::open_with_flags(
            path,
            if existing_database {
                flags
            } else {
                flags | OpenFlags::SQLITE_OPEN_CREATE
            },
        )?;
        database.busy_timeout(Duration::from_millis(250))?;
        if existing_database {
            let version: u32 =
                database.pragma_query_value(None, "user_version", |row| row.get(0))?;
            if !matches!(version, 1..=6) {
                return Err(Error::Protocol("LEDGER_SCHEMA_UNSUPPORTED"));
            }
            let tables: u32 = database.query_row(
                "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name IN ('devices','control','receipts','ledger_meta','queue_totals')",
                [], |row| row.get(0))?;
            let triggers: u32 = database.query_row(
                "SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger' AND name IN ('receipt_insert','receipt_update','receipt_delete')",
                [], |row| row.get(0))?;
            if tables != 5 || triggers != 3 {
                return Err(Error::Protocol("LEDGER_IDENTITY_LOST"));
            }
        }
        database.pragma_update(None, "journal_mode", "WAL")?;
        database.pragma_update(None, "synchronous", "FULL")?;
        database.pragma_update(None, "foreign_keys", true)?;
        if !existing_database {
            database.execute_batch(
            "BEGIN IMMEDIATE;
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY, token_mac BLOB NOT NULL, revoked INTEGER NOT NULL DEFAULT 0,
                receipt_count INTEGER NOT NULL DEFAULT 0 CHECK(receipt_count>=0),
                pending_count INTEGER NOT NULL DEFAULT 0 CHECK(pending_count>=0),
                pending_bytes INTEGER NOT NULL DEFAULT 0 CHECK(pending_bytes>=0)
            ) STRICT;
            CREATE INDEX active_devices ON devices(id) WHERE revoked=0;
            CREATE TABLE queue_totals (
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                pending_count INTEGER NOT NULL CHECK(pending_count>=0),
                pending_bytes INTEGER NOT NULL CHECK(pending_bytes>=0)
            ) STRICT;
            INSERT INTO queue_totals VALUES(1,0,0);
            CREATE TABLE IF NOT EXISTS control (
                singleton INTEGER PRIMARY KEY CHECK(singleton=1), rev INTEGER NOT NULL,
                paused INTEGER NOT NULL, pause_reason TEXT, active_device TEXT, target TEXT
            ) STRICT;
            INSERT OR IGNORE INTO control VALUES(1,1,1,'startup',NULL,NULL);
            CREATE TABLE IF NOT EXISTS receipts (
                accept_order INTEGER PRIMARY KEY AUTOINCREMENT,
                device TEXT NOT NULL REFERENCES devices(id), id TEXT NOT NULL,
                payload_mac BLOB, payload BLOB, original_target TEXT, execute_target TEXT,
                bytes INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL,
                reason TEXT, method TEXT, UNIQUE(device,id)
            ) STRICT;
            CREATE INDEX pending_order ON receipts(accept_order) WHERE state IN ('accepted','held','executing');
            CREATE INDEX receipt_state ON receipts(state,accept_order);
            CREATE TRIGGER receipt_insert AFTER INSERT ON receipts BEGIN
                UPDATE devices SET receipt_count=receipt_count+1,
                    pending_count=pending_count+(NEW.state IN ('accepted','held','executing')),
                    pending_bytes=pending_bytes+NEW.bytes WHERE id=NEW.device;
                UPDATE queue_totals SET pending_count=pending_count+(NEW.state IN ('accepted','held','executing')),
                    pending_bytes=pending_bytes+NEW.bytes;
            END;
            CREATE TRIGGER receipt_update AFTER UPDATE OF state,bytes ON receipts BEGIN
                UPDATE devices SET pending_count=pending_count+(NEW.state IN ('accepted','held','executing'))-(OLD.state IN ('accepted','held','executing')),
                    pending_bytes=pending_bytes+NEW.bytes-OLD.bytes WHERE id=NEW.device;
                UPDATE queue_totals SET pending_count=pending_count+(NEW.state IN ('accepted','held','executing'))-(OLD.state IN ('accepted','held','executing')),
                    pending_bytes=pending_bytes+NEW.bytes-OLD.bytes;
            END;
            CREATE TRIGGER receipt_delete AFTER DELETE ON receipts BEGIN
                UPDATE devices SET receipt_count=receipt_count-1,
                    pending_count=pending_count-(OLD.state IN ('accepted','held','executing')),
                    pending_bytes=pending_bytes-OLD.bytes WHERE id=OLD.device;
                UPDATE queue_totals SET pending_count=pending_count-(OLD.state IN ('accepted','held','executing')),
                    pending_bytes=pending_bytes-OLD.bytes;
            END;
            CREATE TABLE IF NOT EXISTS ledger_meta (key TEXT PRIMARY KEY, value BLOB NOT NULL) STRICT;
            PRAGMA user_version=1;
            COMMIT;"
        )?;
        }
        let mut ledger = Self {
            _lock: lock,
            database,
            key,
            protector,
            sessions: HashMap::new(),
            rates: HashMap::new(),
            deadlines: HashMap::new(),
            healthy: true,
            image_store: None,
            prepared_images: HashMap::new(),
            image_authorizations: HashMap::new(),
        };
        let expected = ledger.mac(b"ledger-key-check", b"voicelink-v2");
        let existing: Option<Vec<u8>> = ledger
            .database
            .query_row(
                "SELECT value FROM ledger_meta WHERE key='key-check'",
                [],
                |row| row.get(0),
            )
            .optional()?;
        if let Some(existing) = existing {
            if !ledger.verify(b"ledger-key-check", b"voicelink-v2", &existing) {
                return Err(Error::Protocol("LEDGER_KEY_MISMATCH"));
            }
        } else if !existing_database {
            ledger.database.execute(
                "INSERT INTO ledger_meta VALUES('key-check',?1)",
                [&expected],
            )?;
        } else {
            return Err(Error::Protocol("LEDGER_IDENTITY_LOST"));
        }
        // Keep the existing receipt identity. Upgrade in one transaction; once
        // installed, a missing history table is corruption, never silently reset.
        let version: u32 = ledger
            .database
            .pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version == 1 {
            ledger.database.execute_batch("BEGIN IMMEDIATE; CREATE TABLE message_history(accept_order INTEGER PRIMARY KEY REFERENCES receipts(accept_order),body BLOB NOT NULL,received_at INTEGER,executed_at INTEGER,target BLOB) STRICT; INSERT INTO message_history(accept_order,body) SELECT accept_order,payload FROM receipts WHERE payload IS NOT NULL; ALTER TABLE devices ADD COLUMN display_name TEXT NOT NULL DEFAULT ''; PRAGMA user_version=3; COMMIT;")?;
        } else {
            ledger.database.prepare(
                "SELECT body,received_at,executed_at,target FROM message_history LIMIT 0",
            )?;
            if version == 2 {
                ledger.database.execute_batch("BEGIN IMMEDIATE; ALTER TABLE devices ADD COLUMN display_name TEXT NOT NULL DEFAULT ''; PRAGMA user_version=3; COMMIT;")?;
            }
        }
        ledger
            .database
            .prepare("SELECT display_name FROM devices LIMIT 0")?;
        let version: u32 = ledger
            .database
            .pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version == 3 {
            ledger.database.execute_batch("BEGIN IMMEDIATE; ALTER TABLE message_history ADD COLUMN deleted_at INTEGER; CREATE INDEX history_deleted ON message_history(deleted_at) WHERE deleted_at IS NOT NULL; CREATE INDEX history_received ON message_history(received_at); CREATE TABLE history_policy(singleton INTEGER PRIMARY KEY CHECK(singleton=1),enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),days INTEGER CHECK(days IN (7,30))) STRICT; INSERT INTO history_policy VALUES(1,1,NULL); PRAGMA user_version=4; COMMIT;")?;
        } else if !matches!(version, 4..=6) {
            return Err(Error::Protocol("LEDGER_SCHEMA_UNSUPPORTED"));
        }
        ledger
            .database
            .prepare("SELECT deleted_at FROM message_history LIMIT 0")?;
        ledger.history_policy()?;
        let version: u32 = ledger
            .database
            .pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version < 5 {
            ledger.database.execute_batch("BEGIN IMMEDIATE; ALTER TABLE message_history ADD COLUMN source_name TEXT NOT NULL DEFAULT ''; UPDATE message_history SET source_name=COALESCE((SELECT display_name FROM devices JOIN receipts ON receipts.device=devices.id WHERE receipts.accept_order=message_history.accept_order),''); PRAGMA user_version=5; COMMIT;")?;
        }
        ledger
            .database
            .prepare("SELECT source_name FROM message_history LIMIT 0")?;
        let version: u32 = ledger
            .database
            .pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version < 6 {
            ledger.database.execute_batch("BEGIN IMMEDIATE;
                CREATE TABLE image_resources(resource_id TEXT PRIMARY KEY,device TEXT NOT NULL,metadata TEXT NOT NULL,accept_order INTEGER NOT NULL UNIQUE REFERENCES receipts(accept_order)) STRICT;
                CREATE TABLE image_garbage(resource_id TEXT PRIMARY KEY) STRICT;
                ALTER TABLE receipts ADD COLUMN image_resource TEXT;
                CREATE INDEX image_pending ON receipts(accept_order) WHERE image_resource IS NOT NULL AND state IN ('accepted','held','executing');
                CREATE TRIGGER image_history_retired AFTER DELETE ON message_history BEGIN
                    INSERT OR IGNORE INTO image_garbage(resource_id) SELECT image_resource FROM receipts WHERE accept_order=OLD.accept_order AND image_resource IS NOT NULL AND state NOT IN ('accepted','held','executing');
                END;
                CREATE TRIGGER image_receipt_retired AFTER UPDATE OF state ON receipts WHEN NEW.image_resource IS NOT NULL AND NEW.state NOT IN ('accepted','held','executing') BEGIN
                    INSERT OR IGNORE INTO image_garbage(resource_id) SELECT NEW.image_resource WHERE NOT EXISTS(SELECT 1 FROM message_history WHERE accept_order=NEW.accept_order);
                END;
                PRAGMA user_version=6; COMMIT;")?;
        }
        ledger.database.prepare(
            "SELECT resource_id,device,metadata,accept_order FROM image_resources LIMIT 0",
        )?;
        ledger
            .database
            .prepare("SELECT image_resource FROM receipts LIMIT 0")?;
        ledger
            .database
            .prepare("SELECT resource_id FROM image_garbage LIMIT 0")?;
        let image_triggers: u32 = ledger.database.query_row("SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger' AND name IN ('image_history_retired','image_receipt_retired')", [], |r|r.get(0))?;
        if image_triggers != 2 {
            return Err(Error::Protocol("LEDGER_SCHEMA_CORRUPT"));
        }
        ledger.recover()?;
        Ok(ledger)
    }

    fn mac(&self, domain: &[u8], bytes: &[u8]) -> Vec<u8> {
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.key).expect("HMAC key length");
        mac.update(domain);
        mac.update(&[0]);
        mac.update(bytes);
        mac.finalize().into_bytes().to_vec()
    }
    pub(crate) fn verify(&self, domain: &[u8], bytes: &[u8], expected: &[u8]) -> bool {
        let mut mac = Hmac::<Sha256>::new_from_slice(&self.key).expect("HMAC key length");
        mac.update(domain);
        mac.update(&[0]);
        mac.update(bytes);
        mac.verify_slice(expected).is_ok()
    }

    fn recover(&mut self) -> Result<()> {
        let previous = self.state()?.pause_reason;
        let tx = self.database.transaction()?;
        let interrupted = tx.execute("UPDATE receipts SET state='uncertain',reason='restart_during_input',payload=NULL,bytes=0 WHERE state='executing'", [])?;
        tx.execute("UPDATE receipts SET state='held',reason='restart_confirmation' WHERE state IN ('accepted','held')", [])?;
        let review: bool = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM control WHERE pause_reason='outcome_review_required')",
            [],
            |row| row.get(0),
        )?;
        let reason = if interrupted > 0 || review {
            "outcome_review_required"
        } else if previous.as_deref() == Some("paused") {
            "paused"
        } else {
            "startup"
        };
        tx.execute(
            "UPDATE control SET paused=1,pause_reason=?1,target=NULL,rev=rev+1",
            [reason],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn state(&self) -> Result<ControlState> {
        Ok(self.database.query_row(
            "SELECT rev,paused,pause_reason,active_device,target FROM control WHERE singleton=1",
            [],
            |row| {
                Ok(ControlState {
                    state_rev: row.get::<_, i64>(0)?.to_string(),
                    paused: row.get(1)?,
                    pause_reason: row.get(2)?,
                    active_device_id: row.get(3)?,
                    target: row.get(4)?,
                })
            },
        )?)
    }

    pub fn snapshot(&self) -> Result<ReceiverSnapshot> {
        let mut devices = self
            .database
            .prepare("SELECT id,display_name FROM devices WHERE revoked=0 ORDER BY id LIMIT 8")?;
        let device_names = devices
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<rusqlite::Result<std::collections::BTreeMap<_, _>>>()?;
        let device_ids = device_names.keys().cloned().collect();
        let paired_devices =
            self.database
                .query_row("SELECT COUNT(*) FROM devices WHERE revoked=0", [], |row| {
                    row.get(0)
                })?;
        let pending = self.database.query_row(
            "SELECT pending_count FROM queue_totals WHERE singleton=1",
            [],
            |row| row.get(0),
        )?;
        let mut statement = self.database.prepare(
            "SELECT id,accept_order,state,reason,method FROM receipts ORDER BY accept_order DESC LIMIT 100")?;
        let recent = statement
            .query_map([], receipt)?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let blocked = self.database.query_row(
            "SELECT id,accept_order,state,reason,method FROM receipts INDEXED BY pending_order WHERE state IN ('accepted','held','executing') ORDER BY accept_order LIMIT 1", [], receipt
        ).optional()?.filter(|r| r.st == State::Held);
        Ok(ReceiverSnapshot {
            control: self.state()?,
            storage_healthy: self.healthy,
            paired_devices,
            device_ids,
            device_names,
            pending,
            blocked,
            recent,
        })
    }

    /// Called only after local human approval, never from an unapproved pair frame.
    pub fn provision_device(&mut self, device: &str, token: &[u8], name: String) -> Result<()> {
        validate_uuid(device)?;
        if token.len() != 32 {
            return Err(Error::Protocol("INVALID_TOKEN"));
        }
        if name.chars().count() > 64 || name.chars().any(char::is_control) {
            return Err(Error::Protocol("INVALID_NAME"));
        }
        let digest = self.mac(b"device-token", token);
        let tx = self.database.transaction()?;
        let count: u32 =
            tx.query_row("SELECT COUNT(*) FROM devices WHERE revoked=0", [], |row| {
                row.get(0)
            })?;
        if count >= 8 {
            return Err(Error::Protocol("DEVICE_LIMIT"));
        }
        tx.execute(
            "INSERT INTO devices(id,token_mac,display_name) VALUES(?1,?2,?3)",
            params![device, digest, name],
        )?;
        tx.execute(
            "UPDATE control SET active_device=COALESCE(active_device,?1),rev=rev+1",
            [device],
        )?;
        tx.commit()?;
        Ok(())
    }

    pub fn authenticate(&mut self, device: &str, token: &[u8]) -> Result<Session> {
        validate_uuid(device)?;
        let digest: Option<Vec<u8>> = self
            .database
            .query_row(
                "SELECT token_mac FROM devices WHERE id=?1 AND revoked=0",
                [device],
                |row| row.get(0),
            )
            .optional()?;
        if !digest
            .as_ref()
            .is_some_and(|digest| self.verify(b"device-token", token, digest))
        {
            return Err(Error::Protocol("UNAUTHORIZED"));
        }
        self.invalidate_device_images(device);
        let id = Uuid::new_v4().to_string();
        self.sessions.insert(device.to_owned(), id.clone());
        Ok(Session {
            device: device.to_owned(),
            id,
        })
    }

    /// Authentication and metadata refresh share the ledger owner. Renaming
    /// never changes control revision, pause, target, receipt or device identity.
    pub fn authenticate_named(
        &mut self,
        device: &str,
        token: &[u8],
        name: &str,
    ) -> Result<Session> {
        if name.trim().is_empty() || name.chars().count() > 64 || name.chars().any(char::is_control)
        {
            return Err(Error::Protocol("INVALID_NAME"));
        }
        let session = self.authenticate(device, token)?;
        self.database.execute(
            "UPDATE devices SET display_name=?1 WHERE id=?2 AND revoked=0 AND display_name<>?1",
            params![name, device],
        )?;
        Ok(session)
    }

    fn authorize(&self, session: &Session) -> Result<()> {
        if !self.healthy {
            return Err(Error::Protocol("STORAGE_UNAVAILABLE"));
        }
        if self.sessions.get(&session.device) != Some(&session.id) {
            return Err(Error::Protocol("STALE_SESSION"));
        }
        let authorized: bool = self.database.query_row(
            "SELECT EXISTS(SELECT 1 FROM devices WHERE id=?1 AND revoked=0)",
            [&session.device],
            |row| row.get(0),
        )?;
        if !authorized {
            return Err(Error::Protocol("UNAUTHORIZED"));
        }
        Ok(())
    }

    pub fn check_session(&self, session: &Session) -> Result<()> {
        self.authorize(session)
    }

    pub fn submit(&mut self, session: &Session, submit: &Submit) -> Result<Receipt> {
        self.submit_at(session, submit, Instant::now())
    }

    fn submit_at(&mut self, session: &Session, submit: &Submit, now: Instant) -> Result<Receipt> {
        self.authorize(session)?;
        submit.validate()?;
        if submit.session_id != session.id {
            return Err(Error::Protocol("STALE_SESSION"));
        }
        let digest = self.mac(b"payload", &submit.canonical_payload());
        // Auth and basic validation first, then dedup before target/quota/rate.
        if let Some(existing) = self.query(session, &submit.id)? {
            let original: Option<Vec<u8>> = self.database.query_row(
                "SELECT payload_mac FROM receipts WHERE device=?1 AND id=?2",
                params![session.device, submit.id],
                |row| row.get(0),
            )?;
            if original.is_some_and(|original| original != digest) {
                return Err(Error::Protocol("ID_CONFLICT"));
            }
            return Ok(existing);
        }
        let control = self.state()?;
        if control.active_device_id.as_deref() != Some(&session.device) {
            return Err(Error::Protocol("NOT_ACTIVE_DEVICE"));
        }
        if control.target.as_deref() != Some(&submit.target) {
            return Err(Error::Protocol("TARGET_MISMATCH"));
        }
        if let Operation::Image { image } = &submit.op {
            let (scope, original, expires) = self
                .prepared_images
                .get(&image.resource_id)
                .ok_or(Error::Protocol("IMAGE_NOT_READY"))?;
            if !self
                .image_authorizations
                .get(&image.resource_id)
                .is_some_and(|(_, value)| value.valid())
                || self.image_store.is_none()
                || *expires <= now
                || original.image() != image
                || scope.device != session.device
                || scope.session != session.id
                || scope.target != submit.target
                || scope.revision != control.state_rev
                || control.paused
            {
                return Err(Error::Protocol("IMAGE_SCOPE"));
            }
        }
        let rate = self
            .rates
            .entry(session.device.clone())
            .or_insert_with(|| Bucket {
                tokens: 4.,
                updated: now,
            });
        if !rate.take(now) {
            return Err(Error::Protocol("RATE_LIMITED"));
        }
        let protected = self.protector.protect(&Zeroizing::new(
            serde_json::to_vec(&submit.op).expect("operation JSON"),
        ))?;
        let history = protected.clone();
        let tx = self.database.transaction()?;
        let (device_count, device_bytes, receipts): (i64, i64, i64) = tx.query_row(
            "SELECT pending_count,pending_bytes,receipt_count FROM devices WHERE id=?1",
            [&session.device],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )?;
        let (global_count, global_bytes): (i64, i64) = tx.query_row(
            "SELECT pending_count,pending_bytes FROM queue_totals WHERE singleton=1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let bytes = submit.op.bytes() as i64;
        if matches!(submit.op, Operation::Image { .. }) {
            let (total, device): (u32, u32) = tx.query_row("SELECT COUNT(*),COALESCE(SUM(device=?1),0) FROM receipts INDEXED BY image_pending WHERE image_resource IS NOT NULL AND state IN ('accepted','held','executing')", [&session.device], |r| Ok((r.get(0)?,r.get(1)?)))?;
            if total >= 2 || device >= 1 {
                return Err(Error::Protocol("IMAGE_QUEUE_FULL"));
            }
        }
        if device_count >= 16
            || global_count >= 32
            || device_bytes + bytes > 65536
            || global_bytes + bytes > 131072
        {
            return Err(Error::Protocol("QUEUE_FULL"));
        }
        if receipts >= 1_000_000 {
            return Err(Error::Protocol("RECEIPT_LIMIT"));
        }
        let state = if control.paused {
            State::Held
        } else {
            State::Accepted
        };
        let reason = control.paused.then_some("paused");
        tx.execute("INSERT INTO receipts(device,id,payload_mac,payload,original_target,execute_target,bytes,state,reason) VALUES(?1,?2,?3,?4,?5,?5,?6,?7,?8)",
            params![session.device, submit.id, digest, protected, submit.target, bytes, state.key(), reason])?;
        let accept_order = tx.last_insert_rowid();
        let order = accept_order.to_string();
        if let Operation::Image { image } = &submit.op {
            tx.execute("INSERT INTO image_resources(resource_id,device,metadata,accept_order) VALUES(?1,?2,?3,?4)", params![image.resource_id,session.device,serde_json::to_string(&image.metadata).expect("metadata"),accept_order])?;
            tx.execute(
                "UPDATE receipts SET image_resource=?1 WHERE device=?2 AND id=?3",
                params![image.resource_id, session.device, submit.id],
            )?;
        }
        tx.execute(
            "INSERT INTO message_history(accept_order,body,received_at,source_name) SELECT ?1,?2,?3,d.display_name FROM history_policy p JOIN devices d ON d.id=?4 WHERE p.singleton=1 AND p.enabled=1",
            params![
                accept_order,
                history,
                crate::history::now_millis(),
                session.device
            ],
        )?;
        tx.commit()?;
        if let Operation::Image { image } = &submit.op
            && let Some((_, resource, _)) = self.prepared_images.remove(&image.resource_id)
        {
            resource.accept();
        }
        self.deadlines.insert(
            (session.device.clone(), submit.id.clone()),
            Instant::now() + Duration::from_secs(30),
        );
        Ok(Receipt {
            id: submit.id.clone(),
            order,
            st: state,
            reason: reason.map(str::to_owned),
            method: None,
        })
    }

    pub fn query(&self, session: &Session, id: &str) -> Result<Option<Receipt>> {
        self.authorize(session)?;
        validate_uuid(id)?;
        self.find(&session.device, id)
    }

    fn find(&self, device: &str, id: &str) -> Result<Option<Receipt>> {
        Ok(self.database.query_row("SELECT id,accept_order,state,reason,method FROM receipts WHERE device=?1 AND id=?2", params![device,id], receipt).optional()?)
    }

    pub fn pause(&mut self) -> Result<ControlState> {
        self.invalidate_all_images();
        let tx = self.database.transaction()?;
        tx.execute("UPDATE control SET paused=1,pause_reason=CASE WHEN pause_reason='outcome_review_required' THEN pause_reason ELSE 'paused' END,rev=rev+1", [])?;
        tx.execute(
            "UPDATE receipts SET state='held',reason='paused' WHERE state='accepted'",
            [],
        )?;
        tx.commit()?;
        self.state()
    }

    /// Suspend a live input scope without replacing an existing user/review pause.
    /// Previously accepted text stays held; reconnect never implicitly releases it.
    pub fn network_lost(&mut self) -> Result<ControlState> {
        let tx = self.database.transaction()?;
        tx.execute("UPDATE control SET pause_reason=CASE WHEN paused=0 THEN 'network_unavailable' ELSE pause_reason END,paused=1,rev=rev+1", [])?;
        tx.execute(
            "UPDATE receipts SET state='held',reason='network_unavailable' WHERE state='accepted'",
            [],
        )?;
        tx.commit()?;
        self.state()
    }

    pub fn bind_target(&mut self, target: &str) -> Result<ControlState> {
        validate_uuid(target)?;
        self.invalidate_all_images();
        let tx = self.database.transaction()?;
        tx.execute("UPDATE control SET target=?1,paused=1,pause_reason=CASE WHEN pause_reason='outcome_review_required' THEN pause_reason ELSE 'target_changed' END,rev=rev+1", [target])?;
        tx.execute(
            "UPDATE receipts SET state='held',reason='target_changed' WHERE state='accepted'",
            [],
        )?;
        tx.commit()?;
        self.state()
    }

    pub fn resume(&mut self, expected_rev: &str, locally_reviewed: bool) -> Result<ControlState> {
        let state = self.state()?;
        if state.state_rev != expected_rev || state.target.is_none() {
            return Err(Error::Protocol("STATE_CONFLICT"));
        }
        if state.pause_reason.as_deref() == Some("outcome_review_required") && !locally_reviewed {
            return Err(Error::Protocol("STATE_CONFLICT"));
        }
        let tx = self.database.transaction()?;
        tx.execute(
            "UPDATE control SET paused=0,pause_reason=NULL,rev=rev+1",
            [],
        )?;
        // Current-cursor input has no enduring HWND binding. Reopening the gate
        // cannot authorize old text at a cursor that may have moved while paused.
        // Every held operation requires its own explicit release, even in the
        // same activation scope and after local uncertainty review.
        tx.commit()?;
        self.state()
    }

    pub fn release(
        &mut self,
        session: &Session,
        id: &str,
        target: &str,
        expected_rev: &str,
    ) -> Result<Receipt> {
        self.authorize(session)?;
        self.expire_pending(Instant::now())?;
        let control = self.state()?;
        if control.paused
            || control.state_rev != expected_rev
            || control.target.as_deref() != Some(target)
            || control.active_device_id.as_deref() != Some(&session.device)
        {
            return Err(Error::Protocol("STATE_CONFLICT"));
        }
        let row = self
            .query(session, id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))?;
        let is_image: bool = self.database.query_row(
            "SELECT image_resource IS NOT NULL FROM receipts WHERE device=?1 AND id=?2",
            params![session.device, id],
            |r| r.get(0),
        )?;
        if is_image {
            return Err(Error::Protocol("IMAGE_REAUTHORIZE_REQUIRED"));
        }
        if row.st != State::Held {
            return Err(Error::Protocol("TOO_LATE"));
        }
        let tx = self.database.transaction()?;
        tx.execute("UPDATE receipts SET state='accepted',reason=NULL,execute_target=?1 WHERE device=?2 AND id=?3 AND state='held'", params![target,session.device,id])?;
        tx.execute("UPDATE control SET rev=rev+1", [])?;
        tx.commit()?;
        // Releasing old text never renews its original acceptance deadline.
        self.find(&session.device, id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))
    }

    pub fn cancel(&mut self, session: &Session, id: &str) -> Result<Receipt> {
        self.authorize(session)?;
        validate_uuid(id)?;
        if let Some(existing) = self.query(session, id)? {
            if existing.st == State::Cancelled {
                return Ok(existing);
            }
            if !matches!(existing.st, State::Accepted | State::Held) {
                return Err(Error::Protocol("TOO_LATE"));
            }
            self.database.execute("UPDATE receipts SET state='cancelled',reason='user_cancelled',payload=NULL,bytes=0 WHERE device=?1 AND id=?2", params![session.device,id])?;
        } else {
            let count: i64 = self.database.query_row(
                "SELECT receipt_count FROM devices WHERE id=?1",
                [&session.device],
                |row| row.get(0),
            )?;
            if count >= 1_000_000 {
                return Err(Error::Protocol("RECEIPT_LIMIT"));
            }
            self.database.execute("INSERT INTO receipts(device,id,state,reason) VALUES(?1,?2,'cancelled','cancelled_before_accept')", params![session.device,id])?;
        }
        self.deadlines
            .remove(&(session.device.clone(), id.to_owned()));
        self.find(&session.device, id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))
    }

    pub fn revoke(&mut self, device: &str) -> Result<()> {
        self.invalidate_device_images(device);
        let tx = self.database.transaction()?;
        tx.execute("UPDATE devices SET revoked=1 WHERE id=?1", [device])?;
        tx.execute("UPDATE receipts SET state='cancelled',reason='device_revoked',payload=NULL,bytes=0 WHERE device=?1 AND state IN ('accepted','held')", [device])?;
        tx.execute("UPDATE control SET paused=1,pause_reason='device_revoked',active_device=NULL,rev=rev+1 WHERE active_device=?1", [device])?;
        tx.commit()?;
        self.sessions.remove(device);
        self.rates.remove(device);
        self.deadlines.retain(|(owner, _), _| owner != device);
        Ok(())
    }

    /// Expire only bounded live pending identities, never an executing input.
    /// No history scan and no timer-based transition can resurrect old text.
    pub(crate) fn expire_pending(&mut self, now: Instant) -> Result<bool> {
        self.prune_images(now)?;
        let due = self
            .deadlines
            .iter()
            .filter(|(_, deadline)| now >= **deadline)
            .map(|(key, _)| key.clone())
            .collect::<Vec<_>>();
        if due.is_empty() {
            return Ok(false);
        }
        let tx = self.database.transaction()?;
        let mut changed = false;
        for (device, id) in &due {
            changed |= tx.execute("UPDATE receipts SET state='expired',reason=CASE WHEN state='held' AND reason IS NOT NULL THEN reason ELSE 'expired_before_input' END,payload=NULL,bytes=0 WHERE device=?1 AND id=?2 AND state IN ('accepted','held')", params![device,id])? > 0;
        }
        tx.commit()?;
        for key in due {
            self.deadlines.remove(&key);
        }
        Ok(changed)
    }

    /// Reserve the oldest candidate without touching external input. Native
    /// preflight runs outside the DB thread, before its execution marker.
    pub fn prepare_next(&mut self) -> Result<Option<Execution>> {
        if !self.healthy {
            return Err(Error::Protocol("STORAGE_UNAVAILABLE"));
        }
        self.expire_pending(Instant::now())?;
        let control = self.state()?;
        if control.paused {
            return Ok(None);
        }
        let row: Option<PendingRow> = self.database.query_row(
            "SELECT device,id,state,payload,execute_target FROM receipts INDEXED BY pending_order WHERE state IN ('accepted','held','executing') ORDER BY accept_order LIMIT 1", [],
            |row| Ok(PendingRow { device:row.get(0)?,id:row.get(1)?,state:row.get(2)?,payload:row.get(3)?,target:row.get(4)? })).optional()?;
        let Some(PendingRow {
            device,
            id,
            state,
            payload,
            target,
        }) = row
        else {
            return Ok(None);
        };
        if state == "executing" {
            return Ok(None);
        }
        let deadline = self.deadlines.get(&(device.clone(), id.clone()));
        if deadline.is_none() || deadline.is_some_and(|deadline| Instant::now() >= *deadline) {
            self.database.execute("UPDATE receipts SET state='expired',reason=CASE WHEN state='held' AND reason IS NOT NULL THEN reason ELSE 'expired_before_input' END,payload=NULL,bytes=0 WHERE device=?1 AND id=?2", params![device,id])?;
            self.deadlines.remove(&(device, id));
            return self.prepare_next();
        }
        if state != "accepted" {
            return Ok(None);
        }
        if control.active_device_id.as_deref() != Some(&device) || control.target != target {
            self.database.execute("UPDATE receipts SET state='held',reason='target_changed' WHERE device=?1 AND id=?2", params![device,id])?;
            return Ok(None);
        }
        let ciphertext = payload.ok_or(Error::Protocol("MISSING_PENDING_PAYLOAD"))?;
        let plaintext = self.protector.unprotect(&ciphertext)?;
        let operation: Operation = serde_json::from_slice(&plaintext)
            .map_err(|_| Error::Protocol("CORRUPT_PENDING_PAYLOAD"))?;
        operation.validate()?;
        let image_authorization = if let Operation::Image { image } = &operation {
            let alive = self
                .image_authorizations
                .get(&image.resource_id)
                .map(|(_, value)| value.clone());
            if !alive.as_ref().is_some_and(|value| value.valid()) {
                self.database.execute("UPDATE receipts SET state='held',reason='image_session_ended' WHERE device=?1 AND id=?2", params![device,id])?;
                return Ok(None);
            }
            alive
        } else {
            None
        };
        Ok(Some(Execution {
            device,
            id,
            target: target.ok_or(Error::Protocol("MISSING_TARGET"))?,
            operation,
            image_authorization,
        }))
    }

    pub fn hold(&mut self, job: &Execution, reason: &str) -> Result<()> {
        self.database.execute("UPDATE receipts SET state='held',reason=?1 WHERE device=?2 AND id=?3 AND state='accepted'",params![reason,job.device,job.id])?;
        Ok(())
    }

    /// The executor may touch input only after this transaction commits.
    pub fn mark_executing(&mut self, job: &Execution) -> Result<bool> {
        self.mark_executing_at(job, None)
    }

    pub fn mark_executing_at(
        &mut self,
        job: &Execution,
        target: Option<crate::history::TargetApplication>,
    ) -> Result<bool> {
        if !self.healthy {
            return Err(Error::Protocol("STORAGE_UNAVAILABLE"));
        }
        let state = self.state()?;
        if !job.image_authorized() {
            self.hold(job, "image_session_ended")?;
            return Ok(false);
        }
        if state.paused
            || state.target.as_deref() != Some(&job.target)
            || state.active_device_id.as_deref() != Some(&job.device)
        {
            self.hold(job, "paused_or_target_changed")?;
            return Ok(false);
        }
        let expired = self
            .deadlines
            .get(&(job.device.clone(), job.id.clone()))
            .is_none_or(|deadline| Instant::now() >= *deadline);
        if expired {
            self.database.execute("UPDATE receipts SET state='expired',reason='expired_before_input',payload=NULL,bytes=0 WHERE device=?1 AND id=?2 AND state='accepted'",params![job.device,job.id])?;
            self.deadlines.remove(&(job.device.clone(), job.id.clone()));
            return Ok(false);
        }
        let protected = target
            .map(|t| self.target_payload(&job.device, &job.id, t))
            .transpose()?;
        let tx = self.database.transaction()?;
        let changed=tx.execute("UPDATE receipts SET state='executing',reason=NULL WHERE device=?1 AND id=?2 AND state='accepted'",params![job.device,job.id])?;
        if changed == 1 {
            tx.execute("UPDATE message_history SET target=?1,executed_at=?2 WHERE accept_order=(SELECT accept_order FROM receipts WHERE device=?3 AND id=?4)",params![protected,crate::history::now_millis(),job.device,job.id])?;
        }
        tx.commit()?;
        Ok(changed == 1)
    }

    pub fn finish(&mut self, job: &Execution, outcome: InputOutcome) -> Result<Receipt> {
        let (state, reason, method) = match outcome {
            InputOutcome::Injected { method } => (State::Injected, None, Some(method)),
            InputOutcome::Failed { reason } => (State::Failed, Some(reason), None),
            InputOutcome::Uncertain { reason } => (State::Uncertain, Some(reason), None),
        };
        let result = (|| -> Result<()> {
            let tx = self.database.transaction()?;
            let changed = tx.execute("UPDATE receipts SET state=?1,reason=?2,method=?3,payload=NULL,bytes=0 WHERE device=?4 AND id=?5 AND state='executing'", params![state.key(),reason,method,job.device,job.id])?;
            if changed != 1 {
                return Err(Error::Protocol("INVALID_EXECUTION_STATE"));
            }
            if state == State::Uncertain {
                tx.execute(
                    "UPDATE control SET paused=1,pause_reason='outcome_review_required',rev=rev+1",
                    [],
                )?;
                tx.execute("UPDATE receipts SET state='held',reason='outcome_review_required' WHERE state='accepted'", [])?;
            }
            tx.commit()?;
            Ok(())
        })();
        if let Err(error) = result {
            self.healthy = false;
            return Err(error);
        }
        self.deadlines.remove(&(job.device.clone(), job.id.clone()));
        if let Operation::Image { image } = &job.operation {
            self.image_authorizations.remove(&image.resource_id);
        }
        self.find(&job.device, &job.id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct TestProtector;
    impl Protector for TestProtector {
        fn protect(&self, input: &[u8]) -> Result<Vec<u8>> {
            Ok(input.to_vec())
        }
        fn unprotect(&self, input: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
            Ok(Zeroizing::new(input.to_vec()))
        }
    }
    fn fixture() -> (Ledger, Session, String, std::path::PathBuf) {
        let directory =
            std::env::temp_dir().join(format!("voicelink-ledger-limits-{}", Uuid::new_v4()));
        std::fs::create_dir(&directory).unwrap();
        let mut ledger = Ledger::open(
            &directory.join("test.db"),
            Zeroizing::new(vec![1; 32]),
            Box::new(TestProtector),
        )
        .unwrap();
        let device = Uuid::new_v4().to_string();
        ledger
            .provision_device(&device, &[2; 32], String::new())
            .unwrap();
        let session = ledger.authenticate(&device, &[2; 32]).unwrap();
        let target = Uuid::new_v4().to_string();
        let state = ledger.bind_target(&target).unwrap();
        ledger.resume(&state.state_rev, false).unwrap();
        (ledger, session, target, directory)
    }
    fn submit(session: &Session, target: &str, text: String) -> Submit {
        Submit {
            v: 2,
            t: "submit".into(),
            session_id: session.id.clone(),
            id: Uuid::new_v4().to_string(),
            target: target.into(),
            op: Operation::Text {
                text,
                src: crate::protocol::TextSource::Ime,
            },
        }
    }
    fn refill(ledger: &mut Ledger, session: &Session) {
        ledger.rates.remove(&session.device);
    }

    #[test]
    fn full_wal_policy_and_count_quota_are_not_bypassed_by_dedup() {
        let (mut ledger, session, target, directory) = fixture();
        assert_eq!(
            ledger
                .database
                .pragma_query_value(None, "synchronous", |row| row.get::<_, u32>(0))
                .unwrap(),
            2
        );
        assert_eq!(
            ledger
                .database
                .pragma_query_value(None, "journal_mode", |row| row.get::<_, String>(0))
                .unwrap(),
            "wal"
        );
        let first = submit(&session, &target, "first".into());
        ledger.submit(&session, &first).unwrap();
        for _ in 0..15 {
            refill(&mut ledger, &session);
            ledger
                .submit(&session, &submit(&session, &target, "small".into()))
                .unwrap();
        }
        refill(&mut ledger, &session);
        assert!(matches!(
            ledger.submit(&session, &submit(&session, &target, "overflow".into())),
            Err(Error::Protocol("QUEUE_FULL"))
        ));
        assert_eq!(ledger.submit(&session, &first).unwrap().st, State::Accepted);
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn byte_quota_and_expiry_at_execution_boundary_are_enforced() {
        let (mut ledger, session, target, directory) = fixture();
        let first = submit(&session, &target, "😀".repeat(4000));
        ledger.submit(&session, &first).unwrap();
        for _ in 0..3 {
            refill(&mut ledger, &session);
            ledger
                .submit(&session, &submit(&session, &target, "😀".repeat(4000)))
                .unwrap();
        }
        refill(&mut ledger, &session);
        assert!(matches!(
            ledger.submit(&session, &submit(&session, &target, "😀".repeat(4000))),
            Err(Error::Protocol("QUEUE_FULL"))
        ));
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.deadlines.insert(
            (session.device.clone(), first.id.clone()),
            Instant::now() - Duration::from_secs(1),
        );
        assert!(!ledger.mark_executing(&job).unwrap());
        assert!(
            !ledger
                .deadlines
                .contains_key(&(session.device.clone(), first.id.clone()))
        );
        assert_eq!(
            ledger.query(&session, &first.id).unwrap().unwrap().st,
            State::Expired
        );
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rate_limit_applies_only_to_new_operations_not_dedup_or_query() {
        let (mut ledger, session, target, directory) = fixture();
        // Disk flushes and parallel rendering can exceed the 50ms refill
        // interval. Exercise the production path with a fixed monotonic time.
        let now = Instant::now();
        let first = submit(&session, &target, "first".into());
        ledger.submit_at(&session, &first, now).unwrap();
        for _ in 0..20 {
            assert!(ledger.submit_at(&session, &first, now).is_ok());
            assert!(ledger.query(&session, &first.id).unwrap().is_some());
        }
        for _ in 0..3 {
            ledger
                .submit_at(&session, &submit(&session, &target, "burst".into()), now)
                .unwrap();
        }
        let next = submit(&session, &target, "over limit".into());
        assert!(matches!(
            ledger.submit_at(&session, &next, now),
            Err(Error::Protocol("RATE_LIMITED"))
        ));
        assert!(matches!(
            ledger.submit_at(&session, &next, now + Duration::from_millis(49)),
            Err(Error::Protocol("RATE_LIMITED"))
        ));
        assert!(
            ledger
                .submit_at(&session, &next, now + Duration::from_millis(50))
                .is_ok()
        );
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn pending_deadlines_cover_held_and_paused_and_release_cannot_renew() {
        let (mut ledger, session, target, directory) = fixture();
        let first = submit(&session, &target, "held".into());
        ledger.submit(&session, &first).unwrap();
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.hold(&job, "receiver_focused").unwrap();
        let key = (session.device.clone(), first.id.clone());
        let deadline = ledger.deadlines[&key];
        let revision = ledger.state().unwrap().state_rev;
        ledger
            .release(&session, &first.id, &target, &revision)
            .unwrap();
        assert_eq!(
            ledger.deadlines[&key], deadline,
            "release must not grant another 30 seconds"
        );
        ledger.hold(&job, "receiver_focused").unwrap();
        let second = submit(&session, &target, "queued".into());
        ledger.submit(&session, &second).unwrap();
        ledger.pause().unwrap();
        assert!(
            ledger
                .expire_pending(deadline + Duration::from_secs(1))
                .unwrap()
        );
        for id in [&first.id, &second.id] {
            assert_eq!(
                ledger.query(&session, id).unwrap().unwrap().st,
                State::Expired
            );
        }
        assert!(
            ledger
                .release(
                    &session,
                    &first.id,
                    &target,
                    &ledger.state().unwrap().state_rev
                )
                .is_err()
        );
        assert_eq!(ledger.snapshot().unwrap().pending, 0);
        assert_eq!(
            ledger
                .history(&crate::history::HistoryQuery::default())
                .unwrap()
                .entries
                .len(),
            2
        );
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn expiration_does_not_reclassify_executing_or_uncertain_input() {
        let (mut ledger, session, target, directory) = fixture();
        let message = submit(&session, &target, "input".into());
        ledger.submit(&session, &message).unwrap();
        let deadline = ledger.deadlines[&(session.device.clone(), message.id.clone())];
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.mark_executing(&job).unwrap();
        assert!(!ledger.expire_pending(deadline).unwrap());
        assert_eq!(
            ledger.query(&session, &message.id).unwrap().unwrap().st,
            State::Executing
        );
        ledger
            .finish(
                &job,
                InputOutcome::Uncertain {
                    reason: "test_partial",
                },
            )
            .unwrap();
        assert!(
            !ledger
                .expire_pending(deadline + Duration::from_secs(1))
                .unwrap()
        );
        assert_eq!(
            ledger.query(&session, &message.id).unwrap().unwrap().st,
            State::Uncertain
        );
        assert!(ledger.state().unwrap().paused);
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn expiry_retains_preflight_cause_for_diagnosis_without_removing_history() {
        let (mut ledger, session, target, directory) = fixture();
        let message = submit(&session, &target, "diagnostic cause".into());
        ledger.submit(&session, &message).unwrap();
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.hold(&job, "elevated_target").unwrap();
        let deadline = ledger.deadlines[&(session.device.clone(), message.id.clone())];
        assert!(ledger.expire_pending(deadline).unwrap());
        let receipt = ledger.query(&session, &message.id).unwrap().unwrap();
        assert_eq!(receipt.st, State::Expired);
        assert_eq!(receipt.reason.as_deref(), Some("elevated_target"));
        assert_eq!(ledger.snapshot().unwrap().pending, 0);
        let history = ledger.history(&Default::default()).unwrap();
        assert_eq!(history.entries[0].receipt.reason, receipt.reason);
        assert!(history.entries[0].executed_at.is_none());
        assert!(
            matches!(&history.entries[0].operation, Some(Operation::Text { text, .. }) if text == "diagnostic cause")
        );
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn retained_history_uses_transactional_counters_and_an_indexed_pending_head() {
        let (mut ledger, session, target, directory) = fixture();
        let tx = ledger.database.transaction().unwrap();
        for _ in 0..10_000 {
            tx.execute(
                "INSERT INTO receipts(device,id,state) VALUES(?1,?2,'cancelled')",
                params![session.device, Uuid::new_v4().to_string()],
            )
            .unwrap();
        }
        tx.commit().unwrap();
        assert_eq!(ledger.snapshot().unwrap().pending, 0);
        let submit = submit(&session, &target, "new text".into());
        ledger.submit(&session, &submit).unwrap();
        let counts: (u32, u32, u32) = ledger
            .database
            .query_row(
                "SELECT receipt_count,pending_count,pending_bytes FROM devices WHERE id=?1",
                [&session.device],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(counts, (10_001, 1, 8));
        assert_eq!(ledger.snapshot().unwrap().pending, 1);
        let plan: String = ledger.database.query_row(
            "EXPLAIN QUERY PLAN SELECT accept_order FROM receipts INDEXED BY pending_order WHERE state IN ('accepted','held','executing') ORDER BY accept_order LIMIT 1",
            [], |row| row.get(3)).unwrap();
        assert!(plan.contains("pending_order"), "{plan}");
        let job = ledger.prepare_next().unwrap().unwrap();
        assert!(ledger.mark_executing(&job).unwrap());
        ledger
            .finish(&job, InputOutcome::Injected { method: "test" })
            .unwrap();
        let snapshot = ledger.snapshot().unwrap();
        assert_eq!(snapshot.pending, 0);
        assert_eq!(snapshot.recent.len(), 100);
        assert_eq!(snapshot.recent[0].st, State::Injected);
        let counts: (u32, u32, u32) = ledger
            .database
            .query_row(
                "SELECT receipt_count,pending_count,pending_bytes FROM devices WHERE id=?1",
                [&session.device],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(counts, (10_001, 0, 0));
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
