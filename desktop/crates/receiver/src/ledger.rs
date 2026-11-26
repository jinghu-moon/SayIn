//! Single-owner SQLite ledger. A receipt is returned only after commit succeeds.
use crate::{
    Error, Result,
    protocol::{Operation, Receipt, State, Submit, validate_uuid},
    protocol_v3::UncertainScope,
};
use hmac::{Hmac, KeyInit, Mac};
use rusqlite::{Connection, OpenFlags, OptionalExtension, Row, params};
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use std::{
    collections::{HashMap, HashSet, hash_map::Entry},
    path::Path,
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;
use zeroize::Zeroizing;

const EXECUTION_WINDOW: Duration = Duration::from_secs(30);
pub const DEFAULT_HOLD_RETENTION: Duration = Duration::from_secs(10 * 60);

pub trait Protector: Send + 'static {
    fn protect(&self, plaintext: &[u8]) -> Result<Vec<u8>>;
    fn unprotect(&self, ciphertext: &[u8]) -> Result<Zeroizing<Vec<u8>>>;
}

/// Monotonic execution time paired with a wall-clock value for durable
/// deadlines. Tests inject this trait so expiry never depends on sleeping.
pub trait Clock: Send + Sync + 'static {
    fn now(&self) -> Instant;
    fn unix_millis(&self) -> i64;
}

pub(crate) struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> Instant {
        Instant::now()
    }

    fn unix_millis(&self) -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock before unix epoch")
            .as_millis() as i64
    }
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
    pub review: Option<ReviewTicket>,
}

/// Local-only authority to acknowledge the exact receipt currently shown for review.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ReviewTicket {
    pair_id: String,
    request_id: String,
    request_rev: i64,
    state_rev: i64,
    pub(crate) accept_order: i64,
    pub scope: UncertainScope,
}

impl ReviewTicket {
    pub fn request_id(&self) -> &str {
        &self.request_id
    }

    pub fn request_rev(&self) -> i64 {
        self.request_rev
    }

    pub fn state_rev(&self) -> i64 {
        self.state_rev
    }

    pub fn scope(&self) -> UncertainScope {
        self.scope
    }
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
    request_rev: i64,
    target: String,
    operation: Operation,
    image_authorization: Option<std::sync::Arc<crate::images::ImageAuthorization>>,
}
impl Drop for Execution {
    fn drop(&mut self) {
        use zeroize::Zeroize as _;
        if let Operation::Text { text, .. } | Operation::AutoText { text, .. } = &mut self.operation
        {
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

/// The native input target validated immediately before the execution marker.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct InputWindow {
    executable: String,
    process_created: u64,
    handle: usize,
}

impl InputWindow {
    pub fn new(executable: String, process_created: u64, handle: usize) -> Self {
        Self {
            executable,
            process_created,
            handle,
        }
    }
}

#[derive(Clone, Debug)]
pub enum InputOutcome {
    Injected {
        method: &'static str,
    },
    Failed {
        reason: &'static str,
    },
    Uncertain {
        reason: &'static str,
    },
    /// The native adapter observed a partial Unicode-only submission to its prepared window.
    PartialUnicode,
}

fn classify_uncertain(
    operation: &Operation,
    outcome: &InputOutcome,
    known_window: bool,
) -> UncertainScope {
    if known_window
        && matches!(
            (operation, outcome),
            (Operation::Text { .. }, InputOutcome::PartialUnicode)
        )
    {
        UncertainScope::Target
    } else {
        UncertainScope::Global
    }
}

struct Bucket {
    tokens: f64,
    updated: Instant,
}

struct PendingRow {
    device: String,
    id: String,
    state: State,
}

/// Input is in acceptance order. The first pending row of a pair owns its turn.
fn select_oldest_pair_head(rows: &[PendingRow]) -> Option<&PendingRow> {
    let mut seen = HashSet::new();
    rows.iter()
        .find(|row| seen.insert(row.device.as_str()) && row.state == State::Accepted)
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

type CommitObserver = Box<dyn Fn(&Operation, &str, State, Instant) + Send>;

pub struct Ledger {
    pub(crate) database: Connection,
    key: Zeroizing<Vec<u8>>,
    pub(crate) protector: Box<dyn Protector>,
    sessions: HashMap<String, String>,
    rates: HashMap<String, Bucket>,
    deadlines: HashMap<(String, String), Instant>,
    hold_deadlines: HashMap<(String, String), Instant>,
    clock: Arc<dyn Clock>,
    hold_retention: Duration,
    healthy: bool,
    commit_observer: Option<CommitObserver>,
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

fn state_from_db(state: &str) -> rusqlite::Result<State> {
    Ok(match state {
        "accepted" => State::Accepted,
        "held" => State::Held,
        "executing" => State::Executing,
        "injected" => State::Injected,
        "cancelled" => State::Cancelled,
        "expired" => State::Expired,
        "failed" => State::Failed,
        "uncertain" => State::Uncertain,
        "uncertain_acknowledged" => State::UncertainAcknowledged,
        _ => return Err(rusqlite::Error::InvalidQuery),
    })
}

pub(crate) fn receipt(row: &Row<'_>) -> rusqlite::Result<Receipt> {
    Ok(Receipt {
        id: row.get(0)?,
        order: row.get::<_, i64>(1)?.to_string(),
        st: state_from_db(&row.get::<_, String>(2)?)?,
        reason: row.get(3)?,
        method: row.get(4)?,
        uncertain_scope: match row.get::<_, Option<String>>("uncertain_scope")?.as_deref() {
            None => None,
            Some("target") => Some(UncertainScope::Target),
            Some("global") => Some(UncertainScope::Global),
            Some(_) => return Err(rusqlite::Error::InvalidQuery),
        },
        request_rev: row
            .get::<_, Option<i64>>("request_rev")?
            .map(|value| value.to_string()),
        execution_stage: row.get("execution_stage")?,
        confirmed: row
            .get::<_, Option<i64>>("confirmed")?
            .map(|value| value != 0),
        application: None,
    })
}

impl Ledger {
    pub fn open(
        path: &Path,
        key: Zeroizing<Vec<u8>>,
        protector: Box<dyn Protector>,
    ) -> Result<Self> {
        Self::open_with_clock(path, key, protector, Arc::new(SystemClock))
    }

    pub fn open_with_clock(
        path: &Path,
        key: Zeroizing<Vec<u8>>,
        protector: Box<dyn Protector>,
        clock: Arc<dyn Clock>,
    ) -> Result<Self> {
        Self::open_with_clock_and_retention(path, key, protector, clock, DEFAULT_HOLD_RETENTION)
    }

    pub fn open_with_clock_and_retention(
        path: &Path,
        key: Zeroizing<Vec<u8>>,
        protector: Box<dyn Protector>,
        clock: Arc<dyn Clock>,
        hold_retention: Duration,
    ) -> Result<Self> {
        if hold_retention.is_zero() {
            return Err(Error::Protocol("INVALID_HOLD_RETENTION"));
        }
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
            if !matches!(version, 1..=7) {
                return Err(Error::Protocol("LEDGER_SCHEMA_UNSUPPORTED"));
            }
            let pair_table = if version < 7 { "devices" } else { "pairs" };
            let tables: u32 = database.query_row(
                "SELECT COUNT(*) FROM sqlite_schema WHERE type='table' AND name IN (?1,'control','receipts','ledger_meta','queue_totals')",
                [pair_table], |row| row.get(0))?;
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
            hold_deadlines: HashMap::new(),
            clock,
            hold_retention,
            healthy: true,
            commit_observer: None,
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
        let pair_display_query = if version < 7 {
            "SELECT display_name FROM devices LIMIT 0"
        } else {
            "SELECT display_name FROM pairs LIMIT 0"
        };
        ledger.database.prepare(pair_display_query)?;
        let version: u32 = ledger
            .database
            .pragma_query_value(None, "user_version", |r| r.get(0))?;
        if version == 3 {
            ledger.database.execute_batch("BEGIN IMMEDIATE; ALTER TABLE message_history ADD COLUMN deleted_at INTEGER; CREATE INDEX history_deleted ON message_history(deleted_at) WHERE deleted_at IS NOT NULL; CREATE INDEX history_received ON message_history(received_at); CREATE TABLE history_policy(singleton INTEGER PRIMARY KEY CHECK(singleton=1),enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),days INTEGER CHECK(days IN (7,30))) STRICT; INSERT INTO history_policy VALUES(1,1,NULL); PRAGMA user_version=4; COMMIT;")?;
        } else if !matches!(version, 4..=7) {
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
        let image_resources_query = if version < 7 {
            "SELECT resource_id,device,metadata,accept_order FROM image_resources LIMIT 0"
        } else {
            "SELECT resource_id,pair_id,metadata,accept_order FROM image_resources LIMIT 0"
        };
        ledger.database.prepare(image_resources_query)?;
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
        if version < 7 {
            let migration = ledger.database.transaction()?;
            // SQLite promotes overflowing integer arithmetic to REAL. State
            // updates cast the next revision to INTEGER so the guard reports a
            // conflict at the ceiling instead of a STRICT type error.
            migration.execute_batch(
                "ALTER TABLE devices RENAME TO pairs;
                 ALTER TABLE receipts RENAME COLUMN device TO pair_id;
                 ALTER TABLE image_resources RENAME COLUMN device TO pair_id;
                 ALTER TABLE pairs ADD COLUMN cancel_count INTEGER NOT NULL DEFAULT 0 CHECK(cancel_count>=0);
                 ALTER TABLE receipts ADD COLUMN request_rev INTEGER NOT NULL DEFAULT 0 CHECK(request_rev>=0);
                 ALTER TABLE receipts ADD COLUMN execution_stage TEXT CHECK(execution_stage IN ('not_started','prepared','input_submitted','file_saved','paste_submitted','paste_confirmed'));
                 ALTER TABLE receipts ADD COLUMN confirmed INTEGER CHECK(confirmed IN (0,1));
                 ALTER TABLE receipts ADD COLUMN uncertain_scope TEXT CHECK(uncertain_scope IN ('target','global'));
                 ALTER TABLE receipts ADD COLUMN hold_expires_at INTEGER;
                 ALTER TABLE receipts ADD COLUMN executable_at INTEGER;
                 ALTER TABLE receipts ADD COLUMN input_window_mac BLOB;
                 CREATE TABLE transferred_receipts (
                     pair_id TEXT NOT NULL REFERENCES pairs(id), id TEXT NOT NULL,
                     source_order INTEGER NOT NULL REFERENCES receipts(accept_order),
                     PRIMARY KEY(pair_id,id)
                 ) STRICT;
                 UPDATE receipts SET uncertain_scope='global' WHERE state='uncertain';
                 UPDATE pairs SET cancel_count=(
                     SELECT COUNT(*) FROM receipts
                     WHERE pair_id=pairs.id AND state='cancelled' AND reason='cancelled_before_accept'
                 );
                 CREATE TRIGGER cancellation_insert AFTER INSERT ON receipts
                 WHEN NEW.state='cancelled' AND NEW.reason='cancelled_before_accept'
                 BEGIN
                     UPDATE pairs SET cancel_count=cancel_count+1 WHERE id=NEW.pair_id;
                 END;
                 CREATE TRIGGER cancellation_delete AFTER DELETE ON receipts
                 WHEN OLD.state='cancelled' AND OLD.reason='cancelled_before_accept'
                 BEGIN
                     UPDATE pairs SET cancel_count=cancel_count-1 WHERE id=OLD.pair_id;
                 END;
                 CREATE TRIGGER receipt_state_insert BEFORE INSERT ON receipts
                 WHEN NEW.state NOT IN ('accepted','held','executing','injected','failed','cancelled',
                                        'expired','uncertain','uncertain_acknowledged')
                 BEGIN
                     SELECT RAISE(ABORT,'STATE_CONFLICT');
                 END;
                 CREATE TRIGGER receipt_state_transition BEFORE UPDATE OF state,request_rev ON receipts
                 WHEN OLD.request_rev=9223372036854775807 OR NEW.request_rev <> OLD.request_rev+1 OR NOT (
                     (OLD.state='accepted' AND NEW.state IN ('held','executing','expired','cancelled'))
                     OR (OLD.state='held' AND NEW.state IN ('accepted','expired','cancelled'))
                     OR (OLD.state='executing' AND NEW.state IN ('injected','failed','uncertain'))
                     OR (OLD.state='uncertain' AND NEW.state='uncertain_acknowledged')
                 )
                 BEGIN
                     SELECT RAISE(ABORT,'STATE_CONFLICT');
                 END;
                 CREATE TRIGGER receipt_terminal_immutable BEFORE UPDATE ON receipts
                 WHEN OLD.state IN ('injected','failed','cancelled','expired','uncertain_acknowledged')
                 BEGIN
                     SELECT RAISE(ABORT,'STATE_CONFLICT');
                 END;
                 PRAGMA user_version=7;",
            )?;
            migration.commit()?;
        }
        // Complete databases created by the first schema-7 implementation in
        // place. This is an idempotent repair for development snapshots that
        // already advertise version 7 but predate replacement/read-only grants.
        let has_cancel_count: bool = ledger.database.query_row(
            "SELECT EXISTS(SELECT 1 FROM pragma_table_info('pairs') WHERE name='cancel_count')",
            [],
            |row| row.get(0),
        )?;
        if !has_cancel_count {
            ledger.database.execute_batch(
                "ALTER TABLE pairs ADD COLUMN cancel_count INTEGER NOT NULL DEFAULT 0 CHECK(cancel_count>=0);
                 UPDATE pairs SET cancel_count=(
                   SELECT COUNT(*) FROM receipts
                   WHERE pair_id=pairs.id AND state='cancelled' AND reason='cancelled_before_accept'
                 );",
            )?;
        }
        let has_executable_at: bool = ledger.database.query_row(
            "SELECT EXISTS(SELECT 1 FROM pragma_table_info('receipts') WHERE name='executable_at')",
            [],
            |row| row.get(0),
        )?;
        if !has_executable_at {
            ledger
                .database
                .execute_batch("ALTER TABLE receipts ADD COLUMN executable_at INTEGER;")?;
        }
        let has_input_window: bool = ledger.database.query_row(
            "SELECT EXISTS(SELECT 1 FROM pragma_table_info('receipts') WHERE name='input_window_mac')",
            [],
            |row| row.get(0),
        )?;
        if !has_input_window {
            ledger
                .database
                .execute_batch("ALTER TABLE receipts ADD COLUMN input_window_mac BLOB;")?;
        }
        ledger.database.execute_batch(
            "CREATE TABLE IF NOT EXISTS transferred_receipts (
                pair_id TEXT NOT NULL REFERENCES pairs(id), id TEXT NOT NULL,
                source_order INTEGER NOT NULL REFERENCES receipts(accept_order),
                PRIMARY KEY(pair_id,id)
             ) STRICT;
             CREATE TRIGGER IF NOT EXISTS cancellation_insert AFTER INSERT ON receipts
             WHEN NEW.state='cancelled' AND NEW.reason='cancelled_before_accept'
             BEGIN UPDATE pairs SET cancel_count=cancel_count+1 WHERE id=NEW.pair_id; END;
             CREATE TRIGGER IF NOT EXISTS cancellation_delete AFTER DELETE ON receipts
             WHEN OLD.state='cancelled' AND OLD.reason='cancelled_before_accept'
             BEGIN UPDATE pairs SET cancel_count=cancel_count-1 WHERE id=OLD.pair_id; END;
             CREATE INDEX IF NOT EXISTS uncertain_receipts ON receipts(uncertain_scope,accept_order) WHERE state='uncertain';
             CREATE INDEX IF NOT EXISTS uncertain_windows ON receipts(input_window_mac) WHERE state='uncertain' AND uncertain_scope='target';",
        )?;
        ledger.database.prepare(
            "SELECT pair_id,request_rev,execution_stage,confirmed,uncertain_scope,hold_expires_at,executable_at,input_window_mac FROM receipts LIMIT 0",
        )?;
        ledger
            .database
            .prepare("SELECT cancel_count FROM pairs LIMIT 0")?;
        ledger
            .database
            .prepare("SELECT pair_id,id,source_order FROM transferred_receipts LIMIT 0")?;
        let state_triggers: u32 = ledger.database.query_row(
            "SELECT COUNT(*) FROM sqlite_schema WHERE type='trigger' AND
             name IN ('receipt_state_insert','receipt_state_transition','receipt_terminal_immutable',
                      'cancellation_insert','cancellation_delete')",
            [], |row| row.get(0),
        )?;
        if state_triggers != 5 {
            return Err(Error::Protocol("LEDGER_SCHEMA_CORRUPT"));
        }
        ledger.recover()?;
        ledger.refresh_hold_deadlines()?;
        ledger.expire_pending(ledger.now())?;
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

    pub(crate) fn now(&self) -> Instant {
        self.clock.now()
    }

    fn unix_millis(&self) -> i64 {
        self.clock.unix_millis()
    }

    fn hold_expiry(&self) -> i64 {
        self.unix_millis() + self.hold_retention.as_millis() as i64
    }

    fn refresh_hold_deadlines(&mut self) -> Result<()> {
        let now_millis = self.unix_millis();
        let now = self.now();
        let mut rows = self.database.prepare(
            "SELECT pair_id,id,hold_expires_at FROM receipts INDEXED BY pending_order
             WHERE state IN ('accepted','held','executing') AND state='held'",
        )?;
        let held = rows
            .query_map([], |row| {
                Ok((
                    (row.get::<_, String>(0)?, row.get::<_, String>(1)?),
                    row.get::<_, i64>(2)?,
                ))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        for (key, expiry) in held {
            self.deadlines.remove(&key);
            self.hold_deadlines.entry(key).or_insert_with(|| {
                let remaining = expiry.saturating_sub(now_millis).max(0) as u64;
                now + Duration::from_millis(remaining).min(self.hold_retention)
            });
        }
        Ok(())
    }

    fn recover(&mut self) -> Result<()> {
        let previous: Option<String> =
            self.database
                .query_row("SELECT pause_reason FROM control", [], |row| row.get(0))?;
        let hold_expires_at = self.hold_expiry();
        let tx = self.database.transaction()?;
        tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='uncertain',reason='restart_during_input',uncertain_scope='global',input_window_mac=NULL,executable_at=NULL,hold_expires_at=NULL,payload=NULL,bytes=0 WHERE state='executing'", [])?;
        tx.execute(
            "UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='expired',reason='restart_confirmation',executable_at=NULL,hold_expires_at=NULL,payload=NULL,bytes=0
             WHERE state='accepted'",
            [],
        )?;
        tx.execute(
            "UPDATE receipts SET hold_expires_at=?1
             WHERE state='held' AND hold_expires_at IS NULL",
            [hold_expires_at],
        )?;
        let reason = if previous.as_deref() == Some("paused") {
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
            "WITH review AS (SELECT EXISTS(SELECT 1 FROM receipts WHERE state='uncertain' AND uncertain_scope='global') AS blocked)
             SELECT rev,paused OR blocked,CASE WHEN blocked THEN 'outcome_review_required' ELSE pause_reason END,active_device,target
             FROM control CROSS JOIN review WHERE singleton=1",
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
            .prepare("SELECT id,display_name FROM pairs WHERE revoked=0 ORDER BY id LIMIT 8")?;
        let device_names = devices
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<rusqlite::Result<std::collections::BTreeMap<_, _>>>()?;
        let device_ids = device_names.keys().cloned().collect();
        let paired_devices =
            self.database
                .query_row("SELECT COUNT(*) FROM pairs WHERE revoked=0", [], |row| {
                    row.get(0)
                })?;
        let pending = self.database.query_row(
            "SELECT pending_count FROM queue_totals WHERE singleton=1",
            [],
            |row| row.get(0),
        )?;
        let mut statement = self.database.prepare(
            "SELECT id,accept_order,state,reason,method,uncertain_scope,request_rev,execution_stage,confirmed FROM receipts ORDER BY accept_order DESC LIMIT 100")?;
        let recent = statement
            .query_map([], receipt)?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let blocked = self.database.query_row(
            "SELECT id,accept_order,state,reason,method,uncertain_scope,request_rev,execution_stage,confirmed FROM receipts INDEXED BY pending_order WHERE state IN ('accepted','held','executing') ORDER BY accept_order LIMIT 1", [], receipt
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
            review: self.review_ticket()?,
        })
    }

    pub fn review_ticket(&self) -> Result<Option<ReviewTicket>> {
        Ok(self
            .database
            .query_row(
                "SELECT pair_id,id,request_rev,rev,accept_order,uncertain_scope
             FROM receipts CROSS JOIN control WHERE state='uncertain'
             ORDER BY uncertain_scope,accept_order DESC LIMIT 1",
                [],
                |row| {
                    Ok(ReviewTicket {
                        pair_id: row.get(0)?,
                        request_id: row.get(1)?,
                        request_rev: row.get(2)?,
                        state_rev: row.get(3)?,
                        accept_order: row.get(4)?,
                        scope: match row.get::<_, String>(5)?.as_str() {
                            "global" => UncertainScope::Global,
                            "target" => UncertainScope::Target,
                            _ => return Err(rusqlite::Error::InvalidQuery),
                        },
                    })
                },
            )
            .optional()?)
    }

    pub fn acknowledge_uncertain(&mut self, ticket: &ReviewTicket) -> Result<ControlState> {
        if !self.healthy {
            return Err(Error::Protocol("STORAGE_UNAVAILABLE"));
        }
        self.expire_pending(self.now())?;
        if self.review_ticket()?.as_ref() != Some(ticket) {
            return Err(Error::Protocol("STATE_CONFLICT"));
        }
        let tx = self.database.transaction()?;
        let changed = tx.execute(
            "UPDATE receipts SET state='uncertain_acknowledged',request_rev=CAST(request_rev+1 AS INTEGER),input_window_mac=NULL
             WHERE pair_id=?1 AND id=?2 AND request_rev=?3 AND state='uncertain'",
            params![ticket.pair_id, ticket.request_id, ticket.request_rev],
        )?;
        if changed != 1 {
            return Err(Error::Protocol("STATE_CONFLICT"));
        }
        tx.execute(
            "UPDATE control SET paused=CASE WHEN EXISTS(SELECT 1 FROM receipts WHERE state='uncertain' AND uncertain_scope='global') THEN 1 ELSE 0 END,
             pause_reason=CASE WHEN EXISTS(SELECT 1 FROM receipts WHERE state='uncertain' AND uncertain_scope='global') THEN 'outcome_review_required' ELSE NULL END,
             rev=rev+1",
            [],
        )?;
        tx.execute("UPDATE receipts AS pending SET state='accepted',request_rev=CAST(request_rev+1 AS INTEGER),reason=NULL,hold_expires_at=NULL,input_window_mac=NULL
                    WHERE state='held' AND reason='uncertain_target' AND NOT EXISTS (
                        SELECT 1 FROM receipts AS review WHERE review.state='uncertain' AND review.uncertain_scope='target'
                        AND (pending.input_window_mac IS NULL OR review.input_window_mac=pending.input_window_mac)
                    )", [])?;
        tx.commit()?;
        self.hold_deadlines.retain(|(pair, id), _| {
            self.database
                .query_row(
                    "SELECT state='held' FROM receipts WHERE pair_id=?1 AND id=?2",
                    params![pair, id],
                    |row| row.get(0),
                )
                .unwrap_or(true)
        });
        self.state()
    }

    /// Called only after local human approval, never from an unapproved pair frame.
    pub fn provision_device(&mut self, device: &str, token: &[u8], name: String) -> Result<()> {
        let digest = self.pair_token_digest(device, token, &name)?;
        let tx = self.database.transaction()?;
        let count: u32 = tx.query_row("SELECT COUNT(*) FROM pairs WHERE revoked=0", [], |row| {
            row.get(0)
        })?;
        if count >= 8 {
            return Err(Error::Protocol("DEVICE_LIMIT"));
        }
        tx.execute(
            "INSERT INTO pairs(id,token_mac,display_name) VALUES(?1,?2,?3)",
            params![device, digest, name],
        )?;
        tx.execute(
            "UPDATE control SET active_device=COALESCE(active_device,?1),rev=rev+1",
            [device],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// Called after local approval of a replacement proven by the old session.
    pub fn replace_pair(
        &mut self,
        old: &Session,
        new_pair: &str,
        new_token: &[u8],
        name: String,
    ) -> Result<()> {
        self.authorize(old)?;
        self.replace_pair_rows(old.device(), new_pair, new_token, name)
    }

    /// The old token is rechecked inside the same ledger command as replacement.
    pub fn replace_pair_by_token(
        &mut self,
        old_pair: &str,
        old_token: &[u8],
        new_pair: &str,
        new_token: &[u8],
        name: String,
    ) -> Result<()> {
        self.verify_pair_token(old_pair, old_token)?;
        self.replace_pair_rows(old_pair, new_pair, new_token, name)
    }

    fn replace_pair_rows(
        &mut self,
        old_pair: &str,
        new_pair: &str,
        new_token: &[u8],
        name: String,
    ) -> Result<()> {
        let digest = self.pair_token_digest(new_pair, new_token, &name)?;
        let tx = self.database.transaction()?;
        let pending: bool = tx.query_row(
            "SELECT EXISTS(SELECT 1 FROM receipts
             WHERE pair_id=?1 AND state IN ('accepted','held','executing','uncertain'))",
            [old_pair],
            |row| row.get(0),
        )?;
        if pending {
            return Err(Error::Protocol("PAIR_REPLACE_BLOCKED"));
        }
        tx.execute(
            "INSERT INTO pairs(id,token_mac,display_name) VALUES(?1,?2,?3)",
            params![new_pair, digest, name],
        )?;
        // Terminal rows are immutable. Transfer read access while preserving
        // the original acceptance sequence and keeping the counters unchanged.
        tx.execute(
            "INSERT INTO transferred_receipts(pair_id,id,source_order)
             SELECT ?1,id,accept_order FROM receipts
             WHERE pair_id=?2 AND state IN ('injected','failed','cancelled','expired','uncertain_acknowledged')
             UNION ALL
             SELECT ?1,id,source_order FROM transferred_receipts WHERE pair_id=?2",
            params![new_pair, old_pair],
        )?;
        tx.execute("UPDATE pairs SET revoked=1 WHERE id=?1", [old_pair])?;
        tx.execute(
            "UPDATE control SET
                active_device=CASE WHEN active_device=?2 THEN ?1 ELSE active_device END,
                paused=CASE WHEN active_device=?2 THEN 1 ELSE paused END,
                pause_reason=CASE WHEN active_device=?2 THEN COALESCE(pause_reason,'startup') ELSE pause_reason END,
                target=CASE WHEN active_device=?2 THEN NULL ELSE target END,
                rev=rev+1",
            params![new_pair, old_pair],
        )?;
        tx.commit()?;
        self.invalidate_device_images(old_pair);
        self.sessions.remove(old_pair);
        self.rates.remove(old_pair);
        Ok(())
    }

    fn pair_token_digest(&self, device: &str, token: &[u8], name: &str) -> Result<Vec<u8>> {
        validate_uuid(device)?;
        if token.len() != 32 {
            return Err(Error::Protocol("INVALID_TOKEN"));
        }
        if name.chars().count() > 64 || name.chars().any(char::is_control) {
            return Err(Error::Protocol("INVALID_NAME"));
        }
        Ok(self.mac(b"device-token", token))
    }

    pub fn verify_pair_token(&self, device: &str, token: &[u8]) -> Result<()> {
        validate_uuid(device)?;
        let digest: Option<Vec<u8>> = self
            .database
            .query_row(
                "SELECT token_mac FROM pairs WHERE id=?1 AND revoked=0",
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
        Ok(())
    }

    pub fn authenticate(&mut self, device: &str, token: &[u8]) -> Result<Session> {
        self.verify_pair_token(device, token)?;
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
            "UPDATE pairs SET display_name=?1 WHERE id=?2 AND revoked=0 AND display_name<>?1",
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
            "SELECT EXISTS(SELECT 1 FROM pairs WHERE id=?1 AND revoked=0)",
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

    /// Optional diagnostic observer for newly committed request transitions.
    pub fn observe_commits(
        &mut self,
        observer: impl Fn(&Operation, &str, State, Instant) + Send + 'static,
    ) {
        self.commit_observer = Some(Box::new(observer));
    }

    fn observe_commit(&self, operation: &Operation, id: &str, state: State) {
        if let Some(observer) = &self.commit_observer {
            observer(operation, id, state, Instant::now());
        }
    }

    pub fn submit(&mut self, session: &Session, submit: &Submit) -> Result<Receipt> {
        self.submit_at(session, submit, self.now())
    }

    fn submit_at(&mut self, session: &Session, submit: &Submit, now: Instant) -> Result<Receipt> {
        self.authorize(session)?;
        submit.validate()?;
        if submit.session_id != session.id {
            return Err(Error::Protocol("STALE_SESSION"));
        }
        let digest = self.mac(b"payload", &submit.canonical_payload());
        // Auth and basic validation first, then dedup before target/quota/rate.
        if let Some(existing) = self.find(&session.device, &submit.id)? {
            let original: Option<Vec<u8>> = self.database.query_row(
                "SELECT payload_mac FROM receipts WHERE pair_id=?1 AND id=?2",
                params![session.device, submit.id],
                |row| row.get(0),
            )?;
            if original.is_some_and(|original| original != digest) {
                return Err(Error::Protocol("ID_CONFLICT"));
            }
            return Ok(existing);
        }
        if self
            .transferred_source(&session.device, &submit.id)?
            .is_some()
        {
            return Err(Error::Protocol("ID_CONFLICT"));
        }
        let control = self.state()?;
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
        let hold_expires_at = control.paused.then(|| self.hold_expiry());
        let tx = self.database.transaction()?;
        let (device_count, device_bytes, receipts): (i64, i64, i64) = tx.query_row(
            "SELECT pending_count,pending_bytes,receipt_count-cancel_count FROM pairs WHERE id=?1",
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
            let (total, device): (u32, u32) = tx.query_row("SELECT COUNT(*),COALESCE(SUM(pair_id=?1),0) FROM receipts INDEXED BY image_pending WHERE image_resource IS NOT NULL AND state IN ('accepted','held','executing')", [&session.device], |r| Ok((r.get(0)?,r.get(1)?)))?;
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
        tx.execute("INSERT INTO receipts(pair_id,id,payload_mac,payload,original_target,execute_target,bytes,state,reason,hold_expires_at,method,execution_stage,confirmed) VALUES(?1,?2,?3,?4,?5,?5,?6,?7,?8,?9,'none','not_started',0)",
            params![session.device, submit.id, digest, protected, submit.target, bytes, state.key(), reason, hold_expires_at])?;
        let accept_order = tx.last_insert_rowid();
        let order = accept_order.to_string();
        if let Operation::Image { image } = &submit.op {
            tx.execute("INSERT INTO image_resources(resource_id,pair_id,metadata,accept_order) VALUES(?1,?2,?3,?4)", params![image.resource_id,session.device,serde_json::to_string(&image.metadata).expect("metadata"),accept_order])?;
            tx.execute(
                "UPDATE receipts SET image_resource=?1 WHERE pair_id=?2 AND id=?3",
                params![image.resource_id, session.device, submit.id],
            )?;
        }
        tx.execute(
            "INSERT INTO message_history(accept_order,body,received_at,source_name) SELECT ?1,?2,?3,d.display_name FROM history_policy p JOIN pairs d ON d.id=?4 WHERE p.singleton=1 AND p.enabled=1",
            params![
                accept_order,
                history,
                crate::history::now_millis(),
                session.device
            ],
        )?;
        tx.commit()?;
        self.observe_commit(&submit.op, &submit.id, state);
        if hold_expires_at.is_some() {
            self.hold_deadlines.insert(
                (session.device.clone(), submit.id.clone()),
                now + self.hold_retention,
            );
        }
        if let Operation::Image { image } = &submit.op
            && let Some((_, resource, _)) = self.prepared_images.remove(&image.resource_id)
        {
            resource.accept();
        }
        Ok(Receipt {
            id: submit.id.clone(),
            order,
            st: state,
            reason: reason.map(str::to_owned),
            method: None,
            uncertain_scope: None,
            request_rev: Some("0".into()),
            execution_stage: Some("not_started".into()),
            confirmed: Some(false),
            application: None,
        })
    }

    pub fn query(&self, session: &Session, id: &str) -> Result<Option<Receipt>> {
        self.authorize(session)?;
        validate_uuid(id)?;
        if let Some(source) = self.transferred_source(&session.device, id)? {
            return self.find(&source, id);
        }
        self.find(&session.device, id)
    }

    fn transferred_source(&self, pair_id: &str, id: &str) -> Result<Option<String>> {
        Ok(self
            .database
            .query_row(
                "SELECT r.pair_id FROM transferred_receipts t
             JOIN receipts r ON r.accept_order=t.source_order
             WHERE t.pair_id=?1 AND t.id=?2",
                params![pair_id, id],
                |row| row.get(0),
            )
            .optional()?)
    }

    fn find(&self, device: &str, id: &str) -> Result<Option<Receipt>> {
        let mut result = self.database.query_row("SELECT id,accept_order,state,reason,method,uncertain_scope,request_rev,execution_stage,confirmed FROM receipts WHERE pair_id=?1 AND id=?2", params![device,id], receipt).optional()?;
        if let Some(receipt) = &mut result {
            // Only the same authenticated device's retained history can supply
            // display metadata. Never look up the current foreground process.
            let target: Option<Vec<u8>> = self.database.query_row(
                "SELECT h.target FROM message_history h JOIN receipts r ON r.accept_order=h.accept_order WHERE r.pair_id=?1 AND r.id=?2 AND h.deleted_at IS NULL",
                params![device,id], |row| row.get(0),
            ).optional()?.flatten();
            receipt.application = target
                .and_then(|bytes| self.read_target(&bytes, device, id).ok())
                .and_then(|target| target.display)
                .filter(|display| display.valid());
        }
        Ok(result)
    }

    pub fn pause(&mut self) -> Result<ControlState> {
        self.invalidate_all_images();
        let hold_expires_at = self.hold_expiry();
        let tx = self.database.transaction()?;
        tx.execute(
            "UPDATE control SET paused=1,pause_reason='paused',rev=rev+1",
            [],
        )?;
        tx.execute(
            "UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='held',reason='paused',executable_at=NULL,hold_expires_at=?1 WHERE state='accepted'",
            [hold_expires_at],
        )?;
        tx.commit()?;
        self.refresh_hold_deadlines()?;
        self.state()
    }

    /// Suspend a live input scope without replacing an existing user/review pause.
    /// Previously accepted text stays held; reconnect never implicitly releases it.
    pub fn network_lost(&mut self) -> Result<ControlState> {
        let hold_expires_at = self.hold_expiry();
        let tx = self.database.transaction()?;
        tx.execute("UPDATE control SET pause_reason=CASE WHEN paused=0 THEN 'network_unavailable' ELSE pause_reason END,paused=1,rev=rev+1", [])?;
        tx.execute(
            "UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='held',reason='network_unavailable',executable_at=NULL,hold_expires_at=?1 WHERE state='accepted'",
            [hold_expires_at],
        )?;
        tx.commit()?;
        self.refresh_hold_deadlines()?;
        self.state()
    }

    pub fn bind_target(&mut self, target: &str) -> Result<ControlState> {
        validate_uuid(target)?;
        self.invalidate_all_images();
        let hold_expires_at = self.hold_expiry();
        let tx = self.database.transaction()?;
        tx.execute(
            "UPDATE control SET target=?1,paused=1,pause_reason='target_changed',rev=rev+1",
            [target],
        )?;
        tx.execute(
            "UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='held',reason='target_changed',executable_at=NULL,hold_expires_at=?1 WHERE state='accepted'",
            [hold_expires_at],
        )?;
        tx.commit()?;
        self.refresh_hold_deadlines()?;
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
        if locally_reviewed {
            let ticket = self
                .review_ticket()?
                .ok_or(Error::Protocol("STATE_CONFLICT"))?;
            if ticket.state_rev.to_string() != expected_rev {
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            return self.acknowledge_uncertain(&ticket);
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
        self.release_checked(session, id, target, expected_rev, None)
    }

    pub fn release_checked(
        &mut self,
        session: &Session,
        id: &str,
        target: &str,
        expected_rev: &str,
        expected_request_rev: Option<&str>,
    ) -> Result<Receipt> {
        self.authorize(session)?;
        self.expire_pending(self.now())?;
        let control = self.state()?;
        if control.paused
            || control.state_rev != expected_rev
            || control.target.as_deref() != Some(target)
        {
            return Err(Error::Protocol("STATE_CONFLICT"));
        }
        validate_uuid(id)?;
        let row = self
            .find(&session.device, id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))?;
        let is_image: bool = self.database.query_row(
            "SELECT image_resource IS NOT NULL FROM receipts WHERE pair_id=?1 AND id=?2",
            params![session.device, id],
            |r| r.get(0),
        )?;
        if is_image {
            return Err(Error::Protocol("IMAGE_REAUTHORIZE_REQUIRED"));
        }
        if row.st != State::Held {
            return Err(Error::Protocol("TOO_LATE"));
        }
        if row.reason.as_deref() == Some("uncertain_target") {
            return Err(Error::Protocol("BLOCKED_REVIEW"));
        }
        if let Some(expected_request_rev) = expected_request_rev {
            let current_request_rev: i64 = self.database.query_row(
                "SELECT request_rev FROM receipts WHERE pair_id=?1 AND id=?2",
                params![session.device, id],
                |row| row.get(0),
            )?;
            if expected_request_rev.parse::<i64>().ok() != Some(current_request_rev) {
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
        }
        let tx = self.database.transaction()?;
        tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='accepted',reason=NULL,execute_target=?1,hold_expires_at=NULL,executable_at=NULL WHERE pair_id=?2 AND id=?3 AND state='held'", params![target,session.device,id])?;
        tx.execute("UPDATE control SET rev=rev+1", [])?;
        tx.commit()?;
        self.hold_deadlines
            .remove(&(session.device.clone(), id.to_owned()));
        self.find(&session.device, id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))
    }

    pub fn cancel(&mut self, session: &Session, id: &str) -> Result<Receipt> {
        self.authorize(session)?;
        validate_uuid(id)?;
        if let Some(existing) = self.find(&session.device, id)? {
            if existing.st == State::Cancelled {
                return Ok(existing);
            }
            if !matches!(existing.st, State::Accepted | State::Held) {
                return Err(Error::Protocol("TOO_LATE"));
            }
            self.database.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='cancelled',reason='user_cancelled',payload=NULL,bytes=0,executable_at=NULL,hold_expires_at=NULL WHERE pair_id=?1 AND id=?2", params![session.device,id])?;
        } else {
            if self.transferred_source(&session.device, id)?.is_some() {
                return Err(Error::Protocol("TOO_LATE"));
            }
            let count: i64 = self.database.query_row(
                "SELECT cancel_count FROM pairs WHERE id=?1",
                [&session.device],
                |row| row.get(0),
            )?;
            if count >= 1_000_000 {
                return Err(Error::Protocol("RECEIPT_LIMIT"));
            }
            self.database.execute(
                "INSERT INTO receipts(pair_id,id,state,reason,execution_stage,confirmed)
                 VALUES(?1,?2,'cancelled','cancelled_before_accept','not_started',0)",
                params![session.device, id],
            )?;
        }
        self.deadlines
            .remove(&(session.device.clone(), id.to_owned()));
        self.hold_deadlines
            .remove(&(session.device.clone(), id.to_owned()));
        self.find(&session.device, id)?
            .ok_or(Error::Protocol("UNKNOWN_ID"))
    }

    pub fn revoke(&mut self, device: &str) -> Result<()> {
        self.invalidate_device_images(device);
        let tx = self.database.transaction()?;
        tx.execute("UPDATE pairs SET revoked=1 WHERE id=?1", [device])?;
        tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='cancelled',reason='device_revoked',payload=NULL,bytes=0,executable_at=NULL,hold_expires_at=NULL WHERE pair_id=?1 AND state IN ('accepted','held')", [device])?;
        tx.execute("UPDATE control SET paused=1,pause_reason='device_revoked',active_device=NULL,rev=rev+1 WHERE active_device=?1", [device])?;
        tx.commit()?;
        self.sessions.remove(device);
        self.rates.remove(device);
        self.deadlines.retain(|(owner, _), _| owner != device);
        self.hold_deadlines.retain(|(owner, _), _| owner != device);
        Ok(())
    }

    /// Expire only bounded live pending identities, never an executing input.
    /// No history scan and no timer-based transition can resurrect old text.
    pub(crate) fn expire_pending(&mut self, now: Instant) -> Result<bool> {
        self.prune_images(now)?;
        let mut due = self
            .deadlines
            .iter()
            .filter(|(_, deadline)| now > **deadline)
            .map(|(key, _)| (key.clone(), false))
            .collect::<Vec<_>>();
        due.extend(
            self.hold_deadlines
                .iter()
                .filter(|(_, deadline)| now >= **deadline)
                .map(|(key, _)| (key.clone(), true)),
        );
        if due.is_empty() {
            return Ok(false);
        }
        let tx = self.database.transaction()?;
        let mut changed = false;
        for ((device, id), held) in &due {
            let (reason, state): (&str, &str) = if *held {
                ("hold_expired", "held")
            } else {
                ("expired_before_input", "accepted")
            };
            changed |= tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='expired',reason=?1,executable_at=NULL,hold_expires_at=NULL,payload=NULL,bytes=0 WHERE pair_id=?2 AND id=?3 AND state=?4", params![reason,device,id,state])? > 0;
        }
        tx.commit()?;
        for (key, held) in due {
            if held {
                self.hold_deadlines.remove(&key);
            } else {
                self.deadlines.remove(&key);
            }
        }
        Ok(changed)
    }

    /// Reserve the oldest candidate without touching external input. Native
    /// preflight runs outside the DB thread, before its execution marker.
    pub fn prepare_next(&mut self) -> Result<Option<Execution>> {
        if !self.healthy {
            return Err(Error::Protocol("STORAGE_UNAVAILABLE"));
        }
        loop {
            let now = self.now();
            self.expire_pending(now)?;
            let control = self.state()?;
            if control.paused {
                return Ok(None);
            }
            let rows = {
                let mut statement = self.database.prepare(
                    "SELECT pair_id,id,state FROM receipts INDEXED BY pending_order
                     WHERE state IN ('accepted','held','executing') ORDER BY accept_order",
                )?;
                statement
                    .query_map([], |row| {
                        Ok(PendingRow {
                            device: row.get(0)?,
                            id: row.get(1)?,
                            state: state_from_db(&row.get::<_, String>(2)?)?,
                        })
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>()?
            };
            let Some(PendingRow { device, id, .. }) = select_oldest_pair_head(&rows) else {
                return Ok(None);
            };
            let (request_rev, payload, target): (i64, Option<Vec<u8>>, Option<String>) =
                self.database.query_row(
                    "SELECT request_rev,payload,execute_target FROM receipts WHERE pair_id=?1 AND id=?2",
                    params![device, id],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )?;
            if control.target != target {
                self.hold_request(device, id, request_rev, "target_changed", None)?;
                continue;
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
                    self.hold_request(device, id, request_rev, "image_session_ended", None)?;
                    continue;
                }
                alive
            } else {
                None
            };
            let key = (device.clone(), id.clone());
            let executable_at = self.unix_millis();
            let deadline = self.now() + EXECUTION_WINDOW;
            if let Entry::Vacant(entry) = self.deadlines.entry(key) {
                self.database.execute(
                    "UPDATE receipts SET executable_at=?1 WHERE pair_id=?2 AND id=?3 AND state='accepted'",
                    params![executable_at, device, id],
                )?;
                entry.insert(deadline);
            }
            return Ok(Some(Execution {
                device: device.clone(),
                id: id.clone(),
                request_rev,
                target: target.ok_or(Error::Protocol("MISSING_TARGET"))?,
                operation,
                image_authorization,
            }));
        }
    }

    pub fn hold(&mut self, job: &Execution, reason: &str) -> Result<()> {
        self.hold_request(&job.device, &job.id, job.request_rev, reason, None)
    }

    fn hold_request(
        &mut self,
        device: &str,
        id: &str,
        request_rev: i64,
        reason: &str,
        window_mac: Option<&[u8]>,
    ) -> Result<()> {
        let hold_expires_at = self.hold_expiry();
        let deadline = self.now() + self.hold_retention;
        let changed = self.database.execute(
            "UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='held',reason=?1,executable_at=NULL,hold_expires_at=?2,input_window_mac=?3
             WHERE pair_id=?4 AND id=?5 AND request_rev=?6 AND state='accepted'",
            params![reason, hold_expires_at, window_mac, device, id, request_rev],
        )?;
        if changed == 1 {
            let key = (device.to_owned(), id.to_owned());
            self.deadlines.remove(&key);
            self.hold_deadlines.insert(key, deadline);
        }
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
        self.mark_executing_in(job, target, None)
    }

    pub fn mark_executing_in(
        &mut self,
        job: &Execution,
        target: Option<crate::history::TargetApplication>,
        window: Option<InputWindow>,
    ) -> Result<bool> {
        if !self.healthy {
            return Err(Error::Protocol("STORAGE_UNAVAILABLE"));
        }
        let state = self.state()?;
        if !job.image_authorized() {
            self.hold(job, "image_session_ended")?;
            return Ok(false);
        }
        if state.paused || state.target.as_deref() != Some(&job.target) {
            self.hold(job, "paused_or_target_changed")?;
            return Ok(false);
        }
        let protected = target
            .map(|t| self.target_payload(&job.device, &job.id, t))
            .transpose()?;
        let window_mac = window.map(|window| {
            let encoded = serde_json::to_vec(&window).expect("input window serialization");
            self.mac(b"input-window", &encoded)
        });
        let key = (job.device.clone(), job.id.clone());
        self.expire_pending(self.now())?;
        if !self.deadlines.contains_key(&key) {
            return Ok(false);
        }
        let blocked = if let Some(mac) = &window_mac {
            self.database.query_row(
                "SELECT EXISTS(SELECT 1 FROM receipts WHERE state='uncertain' AND uncertain_scope='target' AND input_window_mac=?1)",
                [mac], |row| row.get(0),
            )?
        } else {
            self.database.query_row(
                "SELECT EXISTS(SELECT 1 FROM receipts WHERE state='uncertain' AND uncertain_scope='target')",
                [], |row| row.get(0),
            )?
        };
        if blocked {
            self.hold_request(
                &job.device,
                &job.id,
                job.request_rev,
                "uncertain_target",
                window_mac.as_deref(),
            )?;
            return Ok(false);
        }
        let (method, stage) = match job.operation() {
            Operation::Key { .. } => ("key", "prepared"),
            Operation::Image { .. } => ("clipboard_image_path", "prepared"),
            Operation::Text { .. } | Operation::AutoText { .. } => ("unicode", "prepared"),
        };
        let tx = self.database.transaction()?;
        let changed=tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='executing',reason=NULL,input_window_mac=?4,method=?5,execution_stage=?6,confirmed=0 WHERE pair_id=?1 AND id=?2 AND request_rev=?3 AND state='accepted'",params![job.device,job.id,job.request_rev,window_mac,method,stage])?;
        if changed == 1 {
            tx.execute("UPDATE message_history SET target=?1,executed_at=?2 WHERE accept_order=(SELECT accept_order FROM receipts WHERE pair_id=?3 AND id=?4)",params![protected,crate::history::now_millis(),job.device,job.id])?;
        }
        tx.commit()?;
        if changed == 1 {
            self.observe_commit(job.operation(), job.id(), State::Executing);
            self.deadlines.remove(&key);
        }
        Ok(changed == 1)
    }

    pub fn finish(&mut self, job: &Execution, outcome: InputOutcome) -> Result<Receipt> {
        let (state, reason, method, stage, confirmed) = match outcome {
            InputOutcome::Injected { method } => {
                let stage = match job.operation() {
                    Operation::Image { .. } => "paste_submitted",
                    _ => "input_submitted",
                };
                (State::Injected, None, Some(method), stage, false)
            }
            InputOutcome::Failed { reason } => {
                (State::Failed, Some(reason), None, "prepared", false)
            }
            InputOutcome::Uncertain { reason } => {
                let stage = match job.operation() {
                    Operation::Image { .. } => "paste_submitted",
                    _ => "input_submitted",
                };
                (State::Uncertain, Some(reason), None, stage, false)
            }
            InputOutcome::PartialUnicode => (
                State::Uncertain,
                Some("partial_input_or_focus_changed"),
                Some("unicode"),
                "input_submitted",
                false,
            ),
        };
        let review_hold_expires_at = self.hold_expiry();
        let result = (|| -> Result<()> {
            let tx = self.database.transaction()?;
            let uncertainty = if state == State::Uncertain {
                let known_window = tx.query_row(
                    "SELECT input_window_mac IS NOT NULL FROM receipts WHERE pair_id=?1 AND id=?2",
                    params![job.device, job.id],
                    |row| row.get(0),
                )?;
                Some(
                    match classify_uncertain(&job.operation, &outcome, known_window) {
                        UncertainScope::Target => "target",
                        UncertainScope::Global => "global",
                    },
                )
            } else {
                None
            };
            let changed = tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state=?1,reason=?2,method=COALESCE(?3,method),execution_stage=?4,confirmed=?5,uncertain_scope=?6,input_window_mac=CASE WHEN ?6='target' THEN input_window_mac END,executable_at=NULL,hold_expires_at=NULL,payload=NULL,bytes=0 WHERE pair_id=?7 AND id=?8 AND state='executing'", params![state.key(),reason,method,stage,confirmed,uncertainty,job.device,job.id])?;
            if changed != 1 {
                return Err(Error::Protocol("STATE_CONFLICT"));
            }
            if uncertainty == Some("global") {
                tx.execute("UPDATE control SET rev=rev+1", [])?;
                tx.execute("UPDATE receipts SET request_rev=CAST(request_rev+1 AS INTEGER),state='held',reason='outcome_review_required',executable_at=NULL,hold_expires_at=?1 WHERE state='accepted'", [review_hold_expires_at])?;
            } else if uncertainty.is_some() {
                tx.execute("UPDATE control SET rev=rev+1", [])?;
            }
            tx.commit()?;
            Ok(())
        })();
        if let Err(error) = result {
            if !matches!(error, Error::Protocol("STATE_CONFLICT")) {
                self.healthy = false;
            }
            return Err(error);
        }
        self.observe_commit(job.operation(), job.id(), state);
        self.deadlines.remove(&(job.device.clone(), job.id.clone()));
        if state == State::Uncertain {
            self.refresh_hold_deadlines()?;
        }
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
    fn pair_head_selection_checks_each_pending_state_in_acceptance_order() {
        let states = [State::Accepted, State::Held, State::Executing];
        let row = |device: &str, id: &str, state| PendingRow {
            device: device.into(),
            id: id.into(),
            state,
        };
        assert!(select_oldest_pair_head(&[]).is_none());
        for first in states {
            for second in states {
                for successor in states {
                    let rows = [
                        row("A", "A1", first),
                        row("A", "A2", successor),
                        row("B", "B1", second),
                        row("B", "B2", successor),
                    ];
                    let expected = match (first, second) {
                        (State::Accepted, _) => Some("A1"),
                        (_, State::Accepted) => Some("B1"),
                        _ => None,
                    };
                    assert_eq!(
                        select_oldest_pair_head(&rows).map(|row| row.id.as_str()),
                        expected,
                        "{first:?}, {second:?}, {successor:?}",
                    );
                }
            }
        }
    }

    #[test]
    fn only_observed_partial_unicode_text_with_a_known_window_is_target_scoped() {
        use crate::{
            images::{ImageMetadata, ImageMime, ImageReference},
            protocol::{KeyName, TextSource},
        };
        let operations = [
            Operation::Text {
                text: "text".into(),
                src: TextSource::Manual,
            },
            Operation::AutoText {
                text: "text".into(),
                application: "Editor".into(),
            },
            Operation::Key {
                name: KeyName::Enter,
            },
            Operation::Image {
                image: ImageReference {
                    resource_id: Uuid::new_v4().to_string(),
                    metadata: ImageMetadata {
                        bytes: 1,
                        mime: ImageMime::Png,
                        width: 1,
                        height: 1,
                        sha256: "0".repeat(64),
                    },
                },
            },
        ];
        for (index, operation) in operations.iter().enumerate() {
            assert_eq!(
                classify_uncertain(operation, &InputOutcome::PartialUnicode, true),
                if index == 0 {
                    UncertainScope::Target
                } else {
                    UncertainScope::Global
                }
            );
            assert_eq!(
                classify_uncertain(operation, &InputOutcome::PartialUnicode, false),
                UncertainScope::Global
            );
            assert_eq!(
                classify_uncertain(
                    operation,
                    &InputOutcome::Uncertain {
                        reason: "unclassified"
                    },
                    true
                ),
                UncertainScope::Global
            );
        }
    }

    #[test]
    fn global_count_allows_thirty_two_across_pairs_and_reuses_terminal_slot() {
        let (mut ledger, first, target, directory) = fixture();
        let mut sessions = vec![first];
        for token in [[3; 32], [4; 32]] {
            let device = Uuid::new_v4().to_string();
            ledger
                .provision_device(&device, &token, String::new())
                .unwrap();
            sessions.push(ledger.authenticate(&device, &token).unwrap());
        }
        let mut earliest = None;
        let mut last_order = 0;
        for (session, count) in sessions.iter().zip([16, 15, 1]) {
            for _ in 0..count {
                refill(&mut ledger, session);
                let message = submit(session, &target, "pending".into());
                let order: i64 = ledger
                    .submit(session, &message)
                    .unwrap()
                    .order
                    .parse()
                    .unwrap();
                assert!(order > last_order);
                last_order = order;
                earliest.get_or_insert(message);
            }
        }
        let third = &sessions[2];
        let overflow = submit(third, &target, "global count overflow".into());
        assert!(matches!(
            ledger.submit(third, &overflow),
            Err(Error::Protocol("QUEUE_FULL"))
        ));
        ledger.cancel(&sessions[0], &earliest.unwrap().id).unwrap();
        assert_eq!(ledger.submit(third, &overflow).unwrap().st, State::Accepted);
        assert_eq!(ledger.snapshot().unwrap().pending, 32);
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn byte_quotas_allow_exact_caps_and_reject_one_more_byte() {
        let (mut ledger, first, target, directory) = fixture();
        let mut sessions = vec![first];
        for token in [[3; 32], [4; 32]] {
            let device = Uuid::new_v4().to_string();
            ledger
                .provision_device(&device, &token, String::new())
                .unwrap();
            sessions.push(ledger.authenticate(&device, &token).unwrap());
        }
        for session in &sessions[..2] {
            for bytes in [16_000, 16_000, 16_000, 16_000, 1536] {
                refill(&mut ledger, session);
                ledger
                    .submit(session, &submit(session, &target, "😀".repeat(bytes / 4)))
                    .unwrap();
            }
            let extra = submit(session, &target, "x".into());
            assert!(matches!(
                ledger.submit(session, &extra),
                Err(Error::Protocol("QUEUE_FULL"))
            ));
        }
        let third = &sessions[2];
        assert!(matches!(
            ledger.submit(third, &submit(third, &target, "x".into())),
            Err(Error::Protocol("QUEUE_FULL"))
        ));
        assert_eq!(ledger.snapshot().unwrap().pending, 10);
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn cancellation_keys_are_counted_separately_from_accepted_requests() {
        let (mut ledger, session, target, directory) = fixture();
        let message = submit(&session, &target, "accepted".into());
        ledger.submit(&session, &message).unwrap();
        ledger.cancel(&session, &message.id).unwrap();
        let unseen = Uuid::new_v4().to_string();
        let cancelled = ledger.cancel(&session, &unseen).unwrap();
        assert_eq!(
            ledger.cancel(&session, &unseen).unwrap().order,
            cancelled.order
        );
        let counts: (i64, i64, i64, i64) = ledger.database.query_row(
            "SELECT receipt_count,cancel_count,pending_count,pending_bytes FROM pairs WHERE id=?1",
            [session.device()],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        ).unwrap();
        assert_eq!(counts, (2, 1, 0, 0));

        // Reach each storage limit independently without manufacturing a
        // million receipts or measuring wall-clock time.
        ledger
            .database
            .execute(
                "UPDATE pairs SET receipt_count=1000000,cancel_count=0 WHERE id=?1",
                [session.device()],
            )
            .unwrap();
        ledger
            .cancel(&session, &Uuid::new_v4().to_string())
            .unwrap();
        assert!(matches!(
            ledger.submit(
                &session,
                &submit(&session, &target, "normal receipt cap".into())
            ),
            Err(Error::Protocol("RECEIPT_LIMIT"))
        ));
        ledger
            .database
            .execute(
                "UPDATE pairs SET receipt_count=1000001,cancel_count=1000000 WHERE id=?1",
                [session.device()],
            )
            .unwrap();
        assert!(matches!(
            ledger.cancel(&session, &Uuid::new_v4().to_string()),
            Err(Error::Protocol("RECEIPT_LIMIT"))
        ));
        ledger
            .submit(
                &session,
                &submit(&session, &target, "normal space available".into()),
            )
            .unwrap();
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
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
        let revision = ledger.state().unwrap().state_rev;
        ledger
            .release(&session, &first.id, &target, &revision)
            .unwrap();
        assert!(!ledger.deadlines.contains_key(&key));
        assert!(!ledger.hold_deadlines.contains_key(&key));
        let released = ledger.prepare_next().unwrap().unwrap();
        let executable_deadline = ledger.deadlines[&key];
        assert!(executable_deadline <= ledger.now() + EXECUTION_WINDOW);
        ledger.hold(&released, "receiver_focused").unwrap();
        let second = submit(&session, &target, "queued".into());
        ledger.submit(&session, &second).unwrap();
        ledger.pause().unwrap();
        let second_hold_deadline =
            ledger.hold_deadlines[&(session.device.clone(), second.id.clone())];
        assert!(
            ledger
                .expire_pending(second_hold_deadline + Duration::from_secs(1))
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
        let job = ledger.prepare_next().unwrap().unwrap();
        let deadline = ledger.deadlines[&(session.device.clone(), message.id.clone())];
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
    fn hold_expiry_records_terminal_reason_without_removing_history() {
        let (mut ledger, session, target, directory) = fixture();
        let message = submit(&session, &target, "diagnostic cause".into());
        ledger.submit(&session, &message).unwrap();
        let job = ledger.prepare_next().unwrap().unwrap();
        ledger.hold(&job, "elevated_target").unwrap();
        let deadline = ledger.hold_deadlines[&(session.device.clone(), message.id.clone())];
        assert!(ledger.expire_pending(deadline).unwrap());
        let receipt = ledger.query(&session, &message.id).unwrap().unwrap();
        assert_eq!(receipt.st, State::Expired);
        assert_eq!(receipt.reason.as_deref(), Some("hold_expired"));
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
                "INSERT INTO receipts(pair_id,id,state) VALUES(?1,?2,'cancelled')",
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
                "SELECT receipt_count,pending_count,pending_bytes FROM pairs WHERE id=?1",
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
                "SELECT receipt_count,pending_count,pending_bytes FROM pairs WHERE id=?1",
                [&session.device],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(counts, (10_001, 0, 0));
        drop(ledger);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
