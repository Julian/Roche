mod feed_tag_merge;
mod inbox_merge;
mod tombstone_merge;

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use url::Url;

use self::inbox_merge::PreparedPeerItem;
use super::{
    SyncRepo,
    activity::SyncTombstone,
    catalog_sync::{
        PeerCatalogEntryRecord, PeerCollectionEntryRecord, PeerCollectionGeneratorRecord,
        PeerCollectionSpecRecord, PeerComparisonRecord, PeerConsumptionLogRecord,
    },
    hrw::{NetworkTier, PeerNode, SERVER_PEER_ID},
};
use crate::db::{
    CatalogRepo, ContentType, Feed, FeedRepo, FeedType, HexColor, Item, ItemRepo, ItemStatus,
    PollItemCandidate, Tag, TagRepo,
};

pub const MAX_PEER_SYNC_OUTGOING_ITEMS: usize = 5_000;

pub type InsertedItemCandidate = (String, PollItemCandidate);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerFeedRecord {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub feed_type: FeedType,
    pub url: Url,
    pub title: Option<String>,
    pub description: Option<String>,
    pub language: Option<String>,
    #[serde(default = "default_poll_interval")]
    pub poll_interval: i64,
    pub last_polled: Option<DateTime<Utc>>,
    pub last_polled_by: Option<String>,
    pub last_error: Option<String>,
    pub last_error_by: Option<String>,
    #[serde(default = "default_true")]
    pub is_active: bool,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
}

const fn default_poll_interval() -> i64 {
    30
}

const fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerItemRecord {
    #[serde(default)]
    pub id: String,
    pub feed_id: Option<String>,
    pub feed_url: Option<Url>,
    pub title: String,
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub created_at: Option<DateTime<Utc>>,
    pub summary: Option<String>,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub audio_url: Option<String>,
    #[serde(default)]
    pub duration: Option<i64>,
    #[serde(default)]
    pub content_type: ContentType,
    #[serde(default)]
    pub status: ItemStatus,
    #[serde(default)]
    pub is_favorite: bool,
    pub read_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub read_progress: f64,
    pub interaction_updated_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub tag_names: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerTagRecord {
    #[serde(default)]
    pub id: String,
    pub name: String,
    pub color: Option<HexColor>,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PeerSyncRequest {
    pub peer_id: String,
    pub device_name: Option<String>,
    #[serde(default)]
    pub network_tier: NetworkTier,
    pub weight: Option<f64>,
    pub updated_after: Option<DateTime<Utc>>,
    #[serde(default)]
    pub after_item_id: Option<String>,
    #[serde(default)]
    pub feeds: Vec<PeerFeedRecord>,
    #[serde(default)]
    pub items: Vec<PeerItemRecord>,
    #[serde(default)]
    pub tags: Vec<PeerTagRecord>,
    #[serde(default)]
    pub tombstones: Vec<SyncTombstone>,
    #[serde(default)]
    pub collections: Vec<PeerCollectionSpecRecord>,
    #[serde(default)]
    pub catalog_entries: Vec<PeerCatalogEntryRecord>,
    #[serde(default)]
    pub collection_entries: Vec<PeerCollectionEntryRecord>,
    #[serde(default)]
    pub collection_generators: Vec<PeerCollectionGeneratorRecord>,
    #[serde(default)]
    pub consumption_log: Vec<PeerConsumptionLogRecord>,
    #[serde(default)]
    pub comparisons: Vec<PeerComparisonRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerSyncResponse {
    pub server_peer_id: String,
    pub sync_timestamp: DateTime<Utc>,
    #[serde(default)]
    pub has_more: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_after_item_id: Option<String>,
    pub active_peers: Vec<PeerNode>,
    pub feeds: Vec<Feed>,
    pub items: Vec<Item>,
    pub tags: Vec<Tag>,
    pub tombstones: Vec<SyncTombstone>,
    #[serde(default)]
    pub collections: Vec<PeerCollectionSpecRecord>,
    #[serde(default)]
    pub catalog_entries: Vec<PeerCatalogEntryRecord>,
    #[serde(default)]
    pub collection_entries: Vec<PeerCollectionEntryRecord>,
    #[serde(default)]
    pub collection_generators: Vec<PeerCollectionGeneratorRecord>,
    #[serde(default)]
    pub consumption_log: Vec<PeerConsumptionLogRecord>,
    #[serde(default)]
    pub comparisons: Vec<PeerComparisonRecord>,
}

impl SyncRepo<'_> {
    pub async fn merge_peer_state(
        &self,
        req: &PeerSyncRequest,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        self.merge_peer_state_with_limit(req, MAX_PEER_SYNC_OUTGOING_ITEMS).await
    }

    #[allow(clippy::too_many_lines)]
    pub async fn merge_peer_state_with_limit(
        &self,
        req: &PeerSyncRequest,
        max_outgoing_items: usize,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        let now = Self::now_utc();
        let peer_id = req.peer_id.trim();
        let device_name =
            req.device_name.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or(peer_id);
        let measured_weight = if req.network_tier == NetworkTier::Offline {
            Some(0.0)
        } else {
            req.weight.map(|w| w.clamp(0.2, 10.0))
        };

        let has_catalog_payload = Self::has_incoming_catalog_payload(
            &req.collections,
            &req.catalog_entries,
            &req.collection_entries,
            &req.collection_generators,
            &req.consumption_log,
            &req.comparisons,
        );
        if has_catalog_payload {
            let _ = CatalogRepo::new(self.pool).ensure_1001_albums_seeded().await?;
        }

        // Sanitize HTML, resolve URLs, and compute excerpts on a blocking thread BEFORE opening
        // the SQLite write transaction so CPU-bound `ammonia` work never holds the DB write lock.
        let prepared_items = if req.items.is_empty() {
            Vec::new()
        } else {
            let raw_items = req.items.clone();
            tokio::task::spawn_blocking(move || {
                raw_items.into_iter().map(PreparedPeerItem::from_record).collect::<Vec<_>>()
            })
            .await?
        };

        let clean_after_item_id = req
            .after_item_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .filter(|_| req.updated_after.is_some())
            .map(str::to_owned);
        let is_continuation_page = clean_after_item_id.is_some();

        let mut tx = self.pool.begin().await?;

        Self::ensure_server_peer_in_tx(&mut tx, now).await?;
        let page1_seen_at: Option<DateTime<Utc>> =
            if is_continuation_page && !peer_id.is_empty() && peer_id != SERVER_PEER_ID {
                sqlx::query_scalar("SELECT last_seen_at FROM peer_nodes WHERE peer_id = ?")
                    .bind(peer_id)
                    .fetch_optional(&mut *tx)
                    .await?
            } else {
                None
            };
        if !is_continuation_page && !peer_id.is_empty() && peer_id != SERVER_PEER_ID {
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

        Self::apply_incoming_tombstones(&mut tx, &req.tombstones, now).await?;
        Self::merge_incoming_tags(&mut tx, &req.tags, now).await?;
        let feed_map =
            Self::merge_incoming_feeds(&mut tx, &req.feeds, req.updated_after, now).await?;
        let inserted_candidates =
            Self::merge_incoming_items(&mut tx, &prepared_items, &feed_map, now).await?;
        if has_catalog_payload {
            Self::merge_incoming_catalog_in_tx(
                &mut tx,
                &req.collections,
                &req.catalog_entries,
                &req.collection_entries,
                &req.collection_generators,
                &req.consumption_log,
                &req.comparisons,
                now,
            )
            .await?;
        }

        tx.commit().await?;

        let inclusive_since = req.updated_after.map(|ts| ts - chrono::Duration::seconds(1));
        let item_since = if is_continuation_page { req.updated_after } else { inclusive_since };

        let cap = max_outgoing_items.max(1);
        let (items, has_more_from_repo, boundary_ts) = ItemRepo::new(self.pool)
            .list_for_peer_sync(item_since, clean_after_item_id.as_deref(), cap)
            .await?;

        let (has_more, mut sync_timestamp, next_after_item_id) =
            if has_more_from_repo && let Some(last_item) = items.last() {
                (true, boundary_ts.unwrap_or(now), Some(last_item.id.clone()))
            } else {
                (false, now, None)
            };

        if is_continuation_page && !has_more && !peer_id.is_empty() && peer_id != SERVER_PEER_ID {
            let mut touch_tx = self.pool.begin().await?;
            Self::upsert_peer_in_tx(
                &mut touch_tx,
                peer_id,
                device_name,
                req.network_tier,
                measured_weight,
                now,
            )
            .await?;
            touch_tx.commit().await?;
        }

        let active_peers = self.list_active_peers(now).await?;
        let feeds = FeedRepo::new(self.pool).list().await?;
        let tags = TagRepo::new(self.pool).list().await?;

        let outgoing_catalog_mode = if !is_continuation_page {
            Some(inclusive_since.map_or(
                super::activity::OutgoingSince::All,
                super::activity::OutgoingSince::Inclusive,
            ))
        } else if !has_more {
            page1_seen_at.map(super::activity::OutgoingSince::StrictlyAfter)
        } else {
            None
        };

        let (
            tombstones,
            collections,
            catalog_entries,
            collection_entries,
            collection_generators,
            consumption_log,
            comparisons,
        ) = if let Some(since_mode) = outgoing_catalog_mode {
            let (tombstones, tomb_trunc) = self.list_tombstones_bounded(since_mode).await?;
            let (
                (
                    collections,
                    catalog_entries,
                    collection_entries,
                    collection_generators,
                    consumption_log,
                    comparisons,
                ),
                cat_trunc,
            ) = self.collect_outgoing_catalog(since_mode).await?;
            if let Some(t) = tomb_trunc {
                sync_timestamp = sync_timestamp.min(t);
            }
            if let Some(t) = cat_trunc {
                sync_timestamp = sync_timestamp.min(t);
            }
            (
                tombstones,
                collections,
                catalog_entries,
                collection_entries,
                collection_generators,
                consumption_log,
                comparisons,
            )
        } else {
            (Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new(), Vec::new())
        };

        Ok((
            PeerSyncResponse {
                server_peer_id: SERVER_PEER_ID.to_owned(),
                sync_timestamp,
                has_more,
                next_after_item_id,
                active_peers,
                feeds,
                items,
                tags,
                tombstones,
                collections,
                catalog_entries,
                collection_entries,
                collection_generators,
                consumption_log,
                comparisons,
            },
            inserted_candidates,
        ))
    }
}
