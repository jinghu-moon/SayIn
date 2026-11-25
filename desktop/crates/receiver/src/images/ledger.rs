use super::{
    storage::{ImageStore, PreparedResource},
    wire::UploadScope,
};
use crate::{
    Error, Result,
    ledger::{Ledger, Session},
};
use std::time::{Duration, Instant};

impl Ledger {
    /// History removal releases only terminal image resources. The durable
    /// garbage row bridges the DB commit and background unlink, including a
    /// crash between them. Receipts, MACs and original outcomes never change.
    pub(crate) fn retire_history_images(&mut self) -> Result<()> {
        let Some(store) = self.image_store.clone() else {
            return Ok(());
        };
        for id in store.retired() {
            self.database
                .execute("DELETE FROM image_garbage WHERE resource_id=?1", [&id])?;
        }
        let tx = self.database.transaction()?;
        tx.execute("DELETE FROM image_resources WHERE resource_id IN (SELECT resource_id FROM image_garbage)", [])?;
        tx.commit()?;
        let pending = self
            .database
            .prepare("SELECT resource_id FROM image_garbage LIMIT 2")?
            .query_map([], |r| r.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        for id in pending {
            store.retire(&id)?;
        }
        Ok(())
    }
    /// Called on the startup worker, before Receiver::start. Missing referenced
    /// files are a storage failure, never a reason to forget receipts.
    pub fn attach_images(&mut self, store: ImageStore) -> Result<()> {
        let ids = self
            .database
            .prepare("SELECT resource_id FROM image_resources")?
            .query_map([], |row| row.get::<_, String>(0))?
            .collect::<std::result::Result<std::collections::HashSet<_>, _>>()?;
        for id in &ids {
            if !store.contains(id)? {
                return Err(Error::Protocol("IMAGE_RESOURCE_MISSING"));
            }
        }
        store.clean_orphans(|id| Ok(ids.contains(id)))?;
        self.image_store = Some(store);
        Ok(())
    }
    pub fn register_image(
        &mut self,
        session: &Session,
        scope: UploadScope,
        resource: PreparedResource,
        authorization: std::sync::Arc<crate::images::ImageAuthorization>,
    ) -> Result<()> {
        self.check_session(session)?;
        let image = resource.image();
        scope.validate()?;
        image.validate()?;
        let state = self.state()?;
        if !authorization.valid()
            || !self
                .image_store
                .as_ref()
                .is_some_and(|store| resource.belongs_to(store))
            || scope.device != session.device()
            || scope.session != session.id()
            || state.paused
            || state.target.as_deref() != Some(&scope.target)
            || state.state_rev != scope.revision
        {
            return Err(Error::Protocol("IMAGE_SCOPE"));
        }
        self.prune_images(Instant::now())?;
        if self.prepared_images.len() >= 2
            || self
                .prepared_images
                .values()
                .any(|(scope, _, _)| scope.device == session.device())
        {
            return Err(Error::Protocol("IMAGE_UPLOAD_LIMIT"));
        }
        if self.prepared_images.contains_key(&image.resource_id) {
            return Err(Error::Protocol("IMAGE_ID_CONFLICT"));
        }
        self.image_authorizations.insert(
            image.resource_id.clone(),
            (session.device().to_owned(), authorization),
        );
        self.prepared_images.insert(
            image.resource_id.clone(),
            (scope, resource, Instant::now() + Duration::from_secs(60)),
        );
        Ok(())
    }
    pub(crate) fn invalidate_device_images(&mut self, device: &str) {
        for (owner, alive) in self.image_authorizations.values() {
            if owner == device {
                alive.invalidate();
            }
        }
        self.prepared_images
            .retain(|_, (scope, _, _)| scope.device != device);
    }
    pub(crate) fn invalidate_all_images(&mut self) {
        for (_, alive) in self.image_authorizations.values() {
            alive.invalidate();
        }
        self.prepared_images.clear();
    }
    pub(crate) fn prune_images(&mut self, now: Instant) -> Result<()> {
        self.prepared_images.retain(|id, (_, _, expires)| {
            *expires > now
                && self
                    .image_authorizations
                    .get(id)
                    .is_some_and(|(_, alive)| alive.valid())
        });
        let mut remove = Vec::new();
        for id in self.image_authorizations.keys() {
            if self.prepared_images.contains_key(id) {
                continue;
            }
            let pending: bool = self.database.query_row("SELECT EXISTS(SELECT 1 FROM image_resources i JOIN receipts r ON r.accept_order=i.accept_order WHERE i.resource_id=?1 AND r.state IN ('accepted','held','executing'))", [id], |r|r.get(0))?;
            if !pending {
                remove.push(id.clone());
            }
        }
        for id in remove {
            self.image_authorizations.remove(&id);
        }
        Ok(())
    }
    pub fn discard_image(&mut self, session: &Session, id: &str) -> Result<bool> {
        if self.prepared_images.get(id).is_some_and(|(scope, _, _)| {
            scope.session == session.id() && scope.device == session.device()
        }) {
            self.prepared_images.remove(id);
            self.image_authorizations.remove(id);
            return Ok(true);
        }
        Ok(false)
    }
}
