use std::collections::HashSet;

use chrono::{DateTime, Utc};
use serde::Serialize;
use url::Url;

use super::{AppError, RocheService};
use crate::{
    db::{ActivityEntry, PeerSyncRequest, PeerSyncResponse, deterministic_feed_id},
    domain::ItemStatus,
    feeds::FeedSource,
    scheduler::FeedIngester,
    util::{clock::now_utc, url_guard::validate_public_url},
};

const MAX_SYNC_PEER_ID_LEN: usize = 128;
const MAX_SYNC_URL_LEN: usize = 2048;

const MAX_SYNC_ENTRIES: usize = 15_000;
const MAX_SYNC_TOMBSTONES: usize = 10_000;
const MAX_SYNC_COLLECTION_ENTRIES: usize = 25_000;
const MAX_SYNC_GENERATORS: usize = 64;
const MAX_SYNC_CONSUMPTION_LOG: usize = 25_000;
const MAX_SYNC_COMPARISONS: usize = 25_000;

fn is_valid_sync_item_url(url: &Url) -> bool {
    matches!(url.scheme(), "http" | "https")
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.as_str().len() <= MAX_SYNC_URL_LEN
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncStatus {
    pub status: &'static str,
    pub feeds_count: usize,
    pub unread_items_count: i64,
    pub archived_items_count: i64,
    pub latest_activity_id: i64,
    pub is_refreshing: bool,
    pub active_refresh_count: usize,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncChanges {
    pub changes: Vec<ActivityEntry>,
    pub next_since_id: i64,
    pub latest_activity_id: i64,
    pub has_more: bool,
}

pub struct SyncService<'a> {
    pub(super) service: &'a RocheService,
}

impl SyncService<'_> {
    pub async fn status(&self) -> Result<SyncStatus, AppError> {
        let feeds = self.service.db.feeds().list().await?;
        let unread_items_count = self.service.db.items().count_unread().await?;
        let archived_items_count =
            self.service.db.items().count_by_status(ItemStatus::Archived).await?;
        let latest_activity_id = self.service.db.sync().latest_activity_id().await?;

        let active_refresh_count = self.service.active_refresh_count();
        Ok(SyncStatus {
            status: "ok",
            feeds_count: feeds.len(),
            unread_items_count,
            archived_items_count,
            latest_activity_id,
            is_refreshing: active_refresh_count > 0,
            active_refresh_count,
            timestamp: now_utc(),
        })
    }

    pub async fn changes(
        &self,
        since_id: Option<i64>,
        limit: Option<i64>,
    ) -> Result<SyncChanges, AppError> {
        let since = since_id.unwrap_or(0).max(0);
        let lim = limit.unwrap_or(500).clamp(1, 1000);
        let mut changes =
            self.service.db.sync().list_changes_since(since, lim.saturating_add(1)).await?;
        let limit_usize = usize::try_from(lim).unwrap_or(500);
        let has_more = changes.len() > limit_usize;
        changes.truncate(limit_usize);
        let next_since_id = changes.last().map_or(since, |c| c.id);
        let latest_activity_id = self.service.db.sync().latest_activity_id().await?;
        Ok(SyncChanges { changes, next_since_id, latest_activity_id, has_more })
    }

    #[allow(clippy::too_many_lines)]
    pub async fn peer_sync(&self, mut req: PeerSyncRequest) -> Result<PeerSyncResponse, AppError> {
        let trimmed_peer = req.peer_id.trim();
        if trimmed_peer.is_empty() || trimmed_peer.len() > MAX_SYNC_PEER_ID_LEN {
            return Err(AppError::bad_request(
                "peer_id must be non-empty and at most 128 characters",
            ));
        }

        for (field, len, limit) in [
            ("entries", req.entries.len(), MAX_SYNC_ENTRIES),
            ("tombstones", req.tombstones.len(), MAX_SYNC_TOMBSTONES),
            ("collection_entries", req.collection_entries.len(), MAX_SYNC_COLLECTION_ENTRIES),
            ("collection_generators", req.collection_generators.len(), MAX_SYNC_GENERATORS),
            ("consumption_log", req.consumption_log.len(), MAX_SYNC_CONSUMPTION_LOG),
            ("comparisons", req.comparisons.len(), MAX_SYNC_COMPARISONS),
        ] {
            if len > limit {
                return Err(AppError::payload_too_large(format!(
                    "peer_sync '{field}' array ({len}) exceeds per-request limit ({limit}); paginate into smaller batches"
                )));
            }
        }

        let mut skipped_feed_ids = HashSet::new();
        req.entries.retain_mut(|entry| {
            if let Some(ref mut fs) = entry.feed_source {
                let Ok(parsed_feed_url) = Url::parse(fs.feed_url.trim()) else {
                    if !entry.id.trim().is_empty() {
                        skipped_feed_ids.insert(entry.id.trim().to_owned());
                    }
                    return false;
                };
                if let Err(err) = validate_public_url(&parsed_feed_url) {
                    tracing::warn!(
                        url = %parsed_feed_url,
                        error = %err,
                        "Skipping non-public or disallowed feed URL in peer_sync"
                    );
                    if !entry.id.trim().is_empty() {
                        skipped_feed_ids.insert(entry.id.trim().to_owned());
                    }
                    skipped_feed_ids.insert(deterministic_feed_id(parsed_feed_url.as_str()));
                    return false;
                }
                match FeedSource::Url(parsed_feed_url).into_feed_url(fs.source_type) {
                    Ok((resolved_type, validated_url)) => {
                        fs.source_type = resolved_type;
                        fs.feed_url = validated_url.to_string();
                        return true;
                    }
                    Err(err) => {
                        tracing::warn!(
                            url = %fs.feed_url,
                            error = %err,
                            "Skipping incompatible feed URL in peer_sync"
                        );
                        if !entry.id.trim().is_empty() {
                            skipped_feed_ids.insert(entry.id.trim().to_owned());
                        }
                        return false;
                    }
                }
            }

            if let Some(raw_url) = entry.url.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                let valid = Url::parse(raw_url).as_ref().is_ok_and(is_valid_sync_item_url);
                if !valid {
                    tracing::warn!(
                        url = %raw_url,
                        "Skipping or clearing invalid entry URL in peer_sync"
                    );
                    if entry.state.is_some() || entry.content.is_some() {
                        return false;
                    }
                    entry.url = None;
                }
            }

            if let Some(ref mut ct) = entry.content
                && ct.audio_url.as_deref().map(str::trim).filter(|s| !s.is_empty()).is_some_and(
                    |u| {
                        !Url::parse(u).is_ok_and(|parsed| {
                            is_valid_sync_item_url(&parsed) && validate_public_url(&parsed).is_ok()
                        })
                    },
                )
            {
                tracing::warn!(
                    id = %entry.id,
                    "Clearing invalid or disallowed audio_url on synced entry in peer_sync"
                );
                ct.audio_url = None;
            }

            if entry.thumbnail_url.as_deref().is_some_and(|u| u.trim().len() > MAX_SYNC_URL_LEN) {
                tracing::warn!(
                    id = %entry.id,
                    "Clearing oversized thumbnail_url on synced entry in peer_sync"
                );
                entry.thumbnail_url = None;
            }
            true
        });

        if !skipped_feed_ids.is_empty() {
            let mut orphaned_feed_items = HashSet::new();
            for ce in &req.collection_entries {
                if skipped_feed_ids.contains(ce.collection_id.trim()) {
                    orphaned_feed_items.insert(ce.entry_id.trim().to_owned());
                }
            }
            req.collection_entries.retain(|ce| !skipped_feed_ids.contains(ce.collection_id.trim()));
            if !orphaned_feed_items.is_empty() {
                let still_linked: HashSet<&str> =
                    req.collection_entries.iter().map(|ce| ce.entry_id.trim()).collect();
                req.entries.retain(|e| {
                    let id = e.id.trim();
                    !orphaned_feed_items.contains(id) || still_linked.contains(id)
                });
            }
        }

        let (response, mut inserted_candidates) =
            self.service.db.sync().merge_peer_state(&req).await?;
        inserted_candidates.retain(|(_, c)| validate_public_url(&c.url).is_ok());

        if !inserted_candidates.is_empty() {
            let service_clone = self.service.clone();
            tokio::spawn(async move {
                let ctx = service_clone.media_ingest_context();
                FeedIngester::ingest_items_concurrently(&ctx, &inserted_candidates).await;
            });
        }

        Ok(response)
    }
}
