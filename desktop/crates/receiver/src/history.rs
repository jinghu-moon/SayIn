//! Protected message history; queries are bounded and run on the ledger owner.
use crate::{
    Error, Result,
    ledger::Ledger,
    protocol::{Operation, Receipt, State},
};
use rusqlite::{OptionalExtension as _, params};
use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};
use zeroize::Zeroizing;

pub const UNDO_MILLIS: i64 = 8_000;

/// Local display retention never removes protocol receipts or deduplication MACs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HistoryPolicy {
    enabled: bool,
    days: Option<u16>,
}
impl Default for HistoryPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            days: None,
        }
    }
}
impl HistoryPolicy {
    pub fn new(enabled: bool, days: Option<u16>) -> Result<Self> {
        if !matches!(days, None | Some(7 | 30)) {
            return Err(Error::Protocol("INVALID_HISTORY_POLICY"));
        }
        Ok(Self { enabled, days })
    }
    pub fn is_enabled(self) -> bool {
        self.enabled
    }
    pub fn days(self) -> Option<u16> {
        self.days
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub struct TargetApplication {
    pub executable: String,
    pub window_title: String,
    pub process_id: u32,
    pub process_created: u64,
}
impl TargetApplication {
    pub fn new(
        executable: String,
        window_title: String,
        process_id: u32,
        process_created: u64,
    ) -> Self {
        Self {
            executable,
            window_title,
            process_id,
            process_created,
        }
    }
    pub fn name(&self) -> &str {
        self.executable
            .rsplit(['\\', '/'])
            .next()
            .unwrap_or(&self.executable)
    }
}

#[derive(Clone)]
#[non_exhaustive]
pub struct HistoryEntry {
    pub device: String,
    pub source_name: String,
    pub receipt: Receipt,
    pub operation: Option<Operation>,
    pub received_at: Option<i64>,
    pub executed_at: Option<i64>,
    pub target: Option<TargetApplication>,
}

#[derive(Clone)]
#[non_exhaustive]
pub struct HistoryQuery {
    pub before: Option<i64>,
    pub search: String,
    pub state: Option<State>,
    /// Remaining matches requested by the asynchronous paged search (1..=30).
    pub limit: usize,
}
impl Default for HistoryQuery {
    fn default() -> Self {
        Self {
            before: None,
            search: String::new(),
            state: None,
            limit: 30,
        }
    }
}
#[derive(Clone, Default)]
#[non_exhaustive]
pub struct HistoryPage {
    pub entries: Vec<HistoryEntry>,
    pub next: Option<i64>,
}

#[derive(Serialize, Deserialize)]
pub(crate) struct ProtectedTarget {
    pub device: String,
    pub id: String,
    pub target: TargetApplication,
}

pub(crate) fn now_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

impl Ledger {
    /// The exact latest uncertain result, independent of paging and filters.
    /// Deleted/expired history must never fall back to a different old message.
    pub fn review_history(&self) -> Result<Option<HistoryEntry>> {
        let order: Option<i64> = self.database.query_row(
            "SELECT accept_order FROM receipts WHERE state='uncertain' ORDER BY accept_order DESC LIMIT 1",
            [], |row| row.get(0),
        ).optional()?;
        let Some(order) = order else { return Ok(None) };
        let page = self.history(&HistoryQuery {
            before: Some(order + 1),
            state: Some(State::Uncertain),
            limit: 1,
            ..Default::default()
        })?;
        Ok(page
            .entries
            .into_iter()
            .find(|entry| entry.receipt.order == order.to_string()))
    }
    pub fn history_policy(&self) -> Result<HistoryPolicy> {
        let (enabled, days) = self.database.query_row(
            "SELECT enabled,days FROM history_policy WHERE singleton=1",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        HistoryPolicy::new(enabled, days)
    }

    pub fn set_history_policy(&mut self, policy: HistoryPolicy) -> Result<()> {
        self.database.execute(
            "UPDATE history_policy SET enabled=?1,days=?2 WHERE singleton=1",
            params![policy.enabled, policy.days],
        )?;
        // A shorter retention period is an explicit local setting change.
        self.clean_history(now_millis())?;
        Ok(())
    }

    pub fn delete_history(&mut self, order: i64) -> Result<bool> {
        Ok(self.database.execute(
            "UPDATE message_history SET deleted_at=?1 WHERE accept_order=?2 AND deleted_at IS NULL",
            params![now_millis(), order],
        )? != 0)
    }

    pub fn restore_history(&mut self, order: i64) -> Result<bool> {
        Ok(self.database.execute(
            "UPDATE message_history SET deleted_at=NULL WHERE accept_order=?1 AND deleted_at>?2",
            params![order, now_millis().saturating_sub(UNDO_MILLIS)],
        )? != 0)
    }

    /// Bounded maintenance on the ledger owner, including when no window is open.
    pub(crate) fn clean_history(&mut self, now: i64) -> Result<bool> {
        let cutoff = self
            .history_policy()?
            .days
            .map(|days| now.saturating_sub(i64::from(days) * 86_400_000));
        let changed=self.database.execute("DELETE FROM message_history WHERE accept_order IN (SELECT accept_order FROM message_history WHERE deleted_at<=?1 LIMIT 256)", [now.saturating_sub(UNDO_MILLIS)])?;
        let expired = if let Some(cutoff) = cutoff {
            self.database.execute("DELETE FROM message_history WHERE accept_order IN (SELECT accept_order FROM message_history WHERE received_at<?1 LIMIT 256)", [cutoff])?
        } else {
            0
        };
        Ok(changed + expired > 0)
    }
    pub(crate) fn target_payload(
        &self,
        device: &str,
        id: &str,
        target: TargetApplication,
    ) -> Result<Vec<u8>> {
        let body = ProtectedTarget {
            device: device.into(),
            id: id.into(),
            target,
        };
        self.protector.protect(&Zeroizing::new(
            serde_json::to_vec(&body).map_err(|_| Error::Protocol("HISTORY_ENCODING"))?,
        ))
    }

    fn read_target(&self, bytes: &[u8], device: &str, id: &str) -> Result<TargetApplication> {
        let raw = self.protector.unprotect(bytes)?;
        let body: ProtectedTarget =
            serde_json::from_slice(&raw).map_err(|_| Error::Protocol("HISTORY_CORRUPT"))?;
        if body.device != device || body.id != id {
            return Err(Error::Protocol("HISTORY_IDENTITY_MISMATCH"));
        }
        Ok(body.target)
    }

    /// Keyset pagination. Search decrypts at most 256 candidates per request;
    /// an empty matching page can still offer Next rather than scanning forever.
    pub fn history(&self, query: &HistoryQuery) -> Result<HistoryPage> {
        let search = query
            .search
            .chars()
            .take(256)
            .collect::<String>()
            .to_lowercase();
        let mut statement=self.database.prepare("SELECT r.id,r.accept_order,r.state,r.reason,r.method,r.device,h.body,h.received_at,h.executed_at,h.target,r.original_target,r.payload_mac,h.source_name FROM receipts r JOIN message_history h ON h.accept_order=r.accept_order WHERE h.deleted_at IS NULL AND r.accept_order<?1 AND (?2 IS NULL OR r.state=?2) ORDER BY r.accept_order DESC LIMIT 257")?;
        let mut rows = statement.query(params![
            query.before.unwrap_or(i64::MAX),
            query.state.map(State::key)
        ])?;
        let mut page = HistoryPage::default();
        let mut scanned = 0;
        let mut last = None;
        while let Some(row) = rows.next()? {
            if scanned == 256 || page.entries.len() == query.limit.clamp(1, 30) {
                page.next = last;
                break;
            }
            scanned += 1;
            let receipt = crate::ledger::receipt(row)?;
            let order = row.get::<_, i64>(1)?;
            last = Some(order);
            let device: String = row.get(5)?;
            let source_name: String = row.get(12)?;
            let bytes: Option<Vec<u8>> = row.get(6)?;
            let operation = bytes
                .map(|b| -> Result<Operation> {
                    let raw = self.protector.unprotect(&b)?;
                    let operation: Operation = serde_json::from_slice(&raw)
                        .map_err(|_| Error::Protocol("HISTORY_CORRUPT"))?;
                    let scope: String = row.get(10)?;
                    let mac: Vec<u8> = row.get(11)?;
                    let canonical = Zeroizing::new(
                        serde_json::to_vec(&(&scope, &operation))
                            .map_err(|_| Error::Protocol("HISTORY_ENCODING"))?,
                    );
                    if !self.verify(b"payload", &canonical, &mac) {
                        return Err(Error::Protocol("HISTORY_IDENTITY_MISMATCH"));
                    }
                    Ok(operation)
                })
                .transpose()?;
            let target = row
                .get::<_, Option<Vec<u8>>>(9)?
                .map(|b| self.read_target(&b, &device, &receipt.id))
                .transpose()?;
            if !search.is_empty() {
                let text = match &operation {
                    Some(Operation::Text { text, .. }) => text.as_str(),
                    _ => "",
                };
                if ![
                    text,
                    device.as_str(),
                    source_name.as_str(),
                    target.as_ref().map_or("", |t| t.executable.as_str()),
                    target.as_ref().map_or("", |t| t.window_title.as_str()),
                ]
                .iter()
                .any(|s| s.to_lowercase().contains(&search))
                {
                    continue;
                }
            }
            page.entries.push(HistoryEntry {
                device,
                source_name,
                receipt,
                operation,
                received_at: row.get(7)?,
                executed_at: row.get(8)?,
                target,
            });
        }
        Ok(page)
    }
}
