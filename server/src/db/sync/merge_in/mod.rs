mod entries;
mod graph;
mod tombstones;

use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    SyncRepo,
    hlc::HlcClock,
    hrw::{NetworkTier, SERVER_PEER_ID, bound_peer_weight},
    models::{
        InsertedItemCandidate, MAX_PEER_SYNC_PAGE_CHANGES, PeerSyncRequest, PeerSyncResponse,
        PreparedSyncEntry,
    },
};
use crate::db::{ActivityEntityType, CatalogRepo};

pub(super) struct MergeContext<'a> {
    pub peer_id: &'a str,
    pub now: DateTime<Utc>,
    pub id_resolver: IdResolver,
    pub tag_index: TagIndex,
    pub deferred_entry_deletes: Vec<(String, DateTime<Utc>, String)>,
}

impl<'a> MergeContext<'a> {
    pub fn new(peer_id: &'a str, now: DateTime<Utc>) -> Self {
        Self {
            peer_id,
            now,
            id_resolver: IdResolver::default(),
            tag_index: TagIndex::default(),
            deferred_entry_deletes: Vec::new(),
        }
    }
}

#[derive(Debug, Default)]
pub(super) struct IdResolver {
    remap: HashMap<String, String>,
}

impl IdResolver {
    pub fn record(&mut self, raw_id: &str, canonical_id: &str) {
        let trimmed = raw_id.trim();
        if !trimmed.is_empty() {
            self.remap.insert(trimmed.to_owned(), canonical_id.to_owned());
        }
        self.remap.insert(canonical_id.to_owned(), canonical_id.to_owned());
    }

    pub fn record_alias(&mut self, from_id: &str, to_id: &str) {
        let trimmed = from_id.trim();
        if !trimmed.is_empty() {
            self.remap.insert(trimmed.to_owned(), to_id.to_owned());
        }
    }

    pub fn mapped<'a>(&'a self, raw_id: &'a str) -> &'a str {
        let trimmed = raw_id.trim();
        self.remap.get(trimmed).map_or(trimmed, String::as_str)
    }

    pub async fn resolve_live_in_tx(
        &self,
        tx: &mut SqliteConnection,
        raw_id: &str,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let mapped = self.mapped(raw_id);
        if mapped.is_empty() {
            return Ok(None);
        }
        CatalogRepo::follow_merged_id_in_tx(tx, mapped).await
    }

    pub async fn resolve_or_fallback_in_tx(
        &self,
        tx: &mut SqliteConnection,
        raw_id: &str,
    ) -> std::result::Result<String, sqlx::Error> {
        let trimmed = raw_id.trim();
        Ok(self.resolve_live_in_tx(tx, trimmed).await?.unwrap_or_else(|| trimmed.to_owned()))
    }
}

#[derive(Debug, Clone, sqlx::FromRow)]
pub(super) struct IndexedTag {
    pub id: String,
    pub title: String,
    pub slug: Option<String>,
    pub color: Option<String>,
    pub hlc: String,
    pub merged_into_id: Option<String>,
}

#[derive(Debug, Default)]
pub(super) struct TagIndex {
    loaded: bool,
    by_id: HashMap<String, IndexedTag>,
    by_slug: HashMap<String, Vec<String>>,
    by_name: HashMap<String, Vec<String>>,
}

impl TagIndex {
    pub const fn is_loaded(&self) -> bool {
        self.loaded
    }

    pub async fn ensure_loaded(
        &mut self,
        tx: &mut SqliteConnection,
    ) -> std::result::Result<(), sqlx::Error> {
        if self.loaded {
            return Ok(());
        }
        let rows: Vec<IndexedTag> = sqlx::query_as(
            "SELECT e.id, e.title, cs.slug, cs.color, e.hlc, e.merged_into_id
             FROM entries e
             JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE cs.collection_role = 'tag'",
        )
        .fetch_all(&mut *tx)
        .await?;
        for row in rows {
            self.insert_or_replace(row);
        }
        self.loaded = true;
        Ok(())
    }

    pub fn insert_or_replace(&mut self, tag: IndexedTag) {
        self.remove(&tag.id);
        let id = tag.id.clone();
        if let Some(slug) = tag.slug.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            self.by_slug.entry(slug.to_lowercase()).or_default().push(id.clone());
        }
        let name_key = tag.title.trim().to_lowercase();
        if !name_key.is_empty() {
            self.by_name.entry(name_key).or_default().push(id.clone());
        }
        self.by_id.insert(id, tag);
    }

    pub fn remove(&mut self, id: &str) {
        let Some(old) = self.by_id.remove(id) else {
            return;
        };
        if let Some(slug) = old.slug.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            let key = slug.to_lowercase();
            if let Some(ids) = self.by_slug.get_mut(&key) {
                ids.retain(|existing| existing != id);
                if ids.is_empty() {
                    self.by_slug.remove(&key);
                }
            }
        }
        let name_key = old.title.trim().to_lowercase();
        if let Some(ids) = self.by_name.get_mut(&name_key) {
            ids.retain(|existing| existing != id);
            if ids.is_empty() {
                self.by_name.remove(&name_key);
            }
        }
    }

    pub fn find_all_for_tombstone(&self, canon_key: &str) -> Vec<IndexedTag> {
        let want_slug = format!("tag:{canon_key}");
        let mut seen = HashSet::new();
        let mut matched = Vec::new();
        for id in self
            .by_slug
            .get(&want_slug)
            .into_iter()
            .flatten()
            .chain(self.by_name.get(canon_key).into_iter().flatten())
        {
            if seen.insert(id.as_str())
                && let Some(tag) = self.by_id.get(id)
            {
                matched.push(tag.clone());
            }
        }
        matched
    }

    pub fn find_live_for_merge(
        &self,
        det_id: &str,
        tag_slug: &str,
        nat_key: &str,
        rec_id: &str,
    ) -> Option<IndexedTag> {
        if let Some(tag) = self.by_id.get(det_id)
            && tag.merged_into_id.is_none()
        {
            return Some(tag.clone());
        }
        if let Some(ids) = self.by_slug.get(tag_slug) {
            for id in ids {
                if let Some(tag) = self.by_id.get(id)
                    && tag.merged_into_id.is_none()
                {
                    return Some(tag.clone());
                }
            }
        }
        if let Some(ids) = self.by_name.get(nat_key) {
            for id in ids {
                if let Some(tag) = self.by_id.get(id)
                    && tag.merged_into_id.is_none()
                {
                    return Some(tag.clone());
                }
            }
        }
        if !rec_id.is_empty()
            && let Some(tag) = self.by_id.get(rec_id)
            && tag.merged_into_id.is_none()
        {
            return Some(tag.clone());
        }
        None
    }
}

impl SyncRepo<'_> {
    pub async fn merge_peer_state_prepared(
        &self,
        req: &PeerSyncRequest,
        prepared_entries: &[PreparedSyncEntry],
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        self.merge_peer_state_prepared_with_limit(req, prepared_entries, MAX_PEER_SYNC_PAGE_CHANGES)
            .await
    }

    pub async fn merge_peer_state_prepared_with_limit(
        &self,
        req: &PeerSyncRequest,
        prepared_entries: &[PreparedSyncEntry],
        max_page_changes: usize,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        let now = Self::now_utc();
        let now_ms = now.timestamp_millis();
        let peer_id = req.peer_id.trim();
        let safe_peer = if peer_id.is_empty() { "peer" } else { peer_id };
        let device_name = req
            .device_name
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(safe_peer);
        let measured_weight = if req.network_tier == NetworkTier::Offline {
            Some(0.0)
        } else {
            req.weight.map(|w| bound_peer_weight(req.network_tier, Some(w)))
        };

        let mut tx = crate::db::begin_write(self.pool).await?;

        Self::ensure_server_peer_in_tx(&mut tx, now).await?;
        if !peer_id.is_empty() && peer_id != SERVER_PEER_ID {
            Self::upsert_peer_in_tx(
                &mut tx,
                peer_id,
                device_name,
                req.network_tier,
                measured_weight,
                now,
            )
            .await?;
        }

        let stored_hlc: String = sqlx::query_scalar("SELECT last_hlc FROM sync_clock WHERE id = 1")
            .fetch_one(&mut *tx)
            .await?;
        let mut clock =
            HlcClock::new(SERVER_PEER_ID, (!stored_hlc.is_empty()).then_some(stored_hlc.as_str()));
        if let Some(ref client_hlc) = req.client_hlc {
            clock.witness_str(client_hlc, now_ms);
        }
        witness_request_hlcs(&mut clock, req, now_ms);

        let mut ctx = MergeContext::new(safe_peer, now);

        Self::apply_incoming_entry_tombstones(&mut tx, &req.tombstones, &mut ctx).await?;

        let inserted_candidates = Self::merge_incoming_entries_in_tx(
            &mut tx,
            prepared_entries,
            &req.collection_entries,
            &mut ctx,
        )
        .await?;

        Self::apply_incoming_graph_tombstones(&mut tx, &req.tombstones, &mut ctx).await?;

        if !req.collection_entries.is_empty() {
            Self::merge_incoming_collection_entries_in_tx(&mut tx, &req.collection_entries, &ctx)
                .await?;
        }
        if !req.consumption_log.is_empty() {
            Self::merge_incoming_consumption_log_in_tx(&mut tx, &req.consumption_log, &ctx).await?;
        }
        if !req.comparisons.is_empty() {
            Self::merge_incoming_comparisons_in_tx(&mut tx, &req.comparisons, &ctx).await?;
        }
        Self::merge_incoming_generators_in_tx(
            &mut tx,
            &req.collection_generators,
            &mut clock,
            &ctx,
        )
        .await?;

        let server_hlc = clock.tick(now_ms).encode();
        sqlx::query("UPDATE sync_clock SET last_hlc = ? WHERE id = 1")
            .bind(&server_hlc)
            .execute(&mut *tx)
            .await?;

        tx.commit().await?;

        let response = self
            .collect_peer_sync_response(req.since_seq, max_page_changes.max(1), server_hlc, now)
            .await?;

        Ok((response, inserted_candidates))
    }

    #[cfg(test)]
    pub(super) async fn merge_peer_state(
        &self,
        req: &PeerSyncRequest,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        self.merge_peer_state_with_limit(req, MAX_PEER_SYNC_PAGE_CHANGES).await
    }

    #[cfg(test)]
    pub(super) async fn merge_peer_state_with_limit(
        &self,
        req: &PeerSyncRequest,
        max_page_changes: usize,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        let prepared: Vec<PreparedSyncEntry> =
            req.entries.iter().cloned().map(PreparedSyncEntry::from_pre_cleaned_record).collect();
        self.merge_peer_state_prepared_with_limit(req, &prepared, max_page_changes).await
    }

    /// Checks whether an incoming record with `incoming_hlc` is suppressed by an existing
    /// tombstone for `(entity_type, natural_key)`.
    ///
    /// Returns `Ok(true)` when the tombstone wins (`incoming_hlc <= tomb_hlc`) and the incoming
    /// record must be skipped; otherwise clears any stale tombstone and returns `Ok(false)`.
    pub(super) async fn check_and_clear_tombstone_in_tx(
        tx: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
        incoming_hlc: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        let tomb_hlc: Option<String> = sqlx::query_scalar(
            "SELECT hlc FROM sync_tombstones WHERE entity_type = ? AND natural_key = ?",
        )
        .bind(entity_type)
        .bind(natural_key)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(th) = tomb_hlc {
            if incoming_hlc <= th.as_str() {
                return Ok(true);
            }
            Self::clear_tombstone(tx, entity_type, natural_key).await?;
        }
        Ok(false)
    }
}

fn witness_request_hlcs(clock: &mut HlcClock, req: &PeerSyncRequest, now_ms: i64) {
    for entry in &req.entries {
        if !entry.hlc.is_empty() {
            clock.witness_str(&entry.hlc, now_ms);
        }
        if let Some(ref st) = entry.state
            && !st.hlc.is_empty()
        {
            clock.witness_str(&st.hlc, now_ms);
        }
        if let Some(ref fs) = entry.feed_source
            && !fs.hlc.is_empty()
        {
            clock.witness_str(&fs.hlc, now_ms);
        }
    }
    for ce in &req.collection_entries {
        if !ce.hlc.is_empty() {
            clock.witness_str(&ce.hlc, now_ms);
        }
    }
    for gen_rec in &req.collection_generators {
        if !gen_rec.hlc.is_empty() {
            clock.witness_str(&gen_rec.hlc, now_ms);
        }
    }
    for log in &req.consumption_log {
        if !log.hlc.is_empty() {
            clock.witness_str(&log.hlc, now_ms);
        }
    }
    for comp in &req.comparisons {
        if !comp.hlc.is_empty() {
            clock.witness_str(&comp.hlc, now_ms);
        }
    }
    for tomb in &req.tombstones {
        if !tomb.hlc.is_empty() {
            clock.witness_str(&tomb.hlc, now_ms);
        }
    }
}
