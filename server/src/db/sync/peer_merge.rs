use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, SubsecRound, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqliteConnection;
use url::Url;

use super::{
    SyncRepo,
    activity::SyncTombstone,
    hrw::{NetworkTier, PeerNode, SERVER_PEER_ID},
    identity::{
        canonicalize_sync_url, deterministic_feed_id, deterministic_item_id, deterministic_tag_id,
        feed_natural_key, item_natural_key, tag_natural_key,
    },
};
use crate::{
    content::fetcher::{HtmlSanitizer, MAX_SUMMARY_CHARS, SafeHttpClient, is_direct_audio_url},
    db::{
        ActivityAction, ActivityEntityType, ContentType, Feed, FeedRepo, FeedType, HexColor, Item,
        ItemFilter, ItemRepo, ItemStatus, PollItemCandidate, Tag, TagRepo,
    },
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

        let mut tx = self.pool.begin().await?;

        // Begin `tx` with an immediate write (`INSERT INTO peer_nodes`) so SQLite acquires a
        // write lock right away instead of upgrading a deferred read snapshot later.
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

        Self::apply_incoming_tombstones(&mut tx, &req.tombstones, now).await?;
        Self::merge_incoming_tags(&mut tx, &req.tags, now).await?;
        let feed_map =
            Self::merge_incoming_feeds(&mut tx, &req.feeds, req.updated_after, now).await?;
        let inserted_candidates =
            Self::merge_incoming_items(&mut tx, &req.items, &feed_map, now).await?;

        tx.commit().await?;

        let is_delta_sync = req.updated_after.is_some();
        let clean_after_item_id = req
            .after_item_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_owned);
        let inclusive_since = req.updated_after.map(|ts| ts - chrono::Duration::seconds(1));
        let item_since =
            if clean_after_item_id.is_some() { req.updated_after } else { inclusive_since };
        let active_peers = self.list_active_peers(now).await?;
        let feeds = FeedRepo::new(self.pool).list().await?;
        let cap = max_outgoing_items.max(1);
        let per_page = cap.min(200);
        let mut items = Vec::new();
        let mut page = 1;
        let mut has_more_from_repo = false;
        loop {
            let res = ItemRepo::new(self.pool)
                .list_filtered_page(&ItemFilter {
                    updated_after: item_since,
                    updated_after_item_id: clean_after_item_id.clone(),
                    include_content: Some(true),
                    page: Some(page),
                    per_page: Some(per_page),
                    sort_updated_asc: is_delta_sync,
                    ..ItemFilter::default()
                })
                .await?;
            items.extend(res.items);
            if items.len() >= cap {
                has_more_from_repo = items.len() > cap || res.has_more;
                items.truncate(cap);
                break;
            }
            if !res.has_more {
                break;
            }
            page += 1;
        }

        let (has_more, sync_timestamp, next_after_item_id) = if is_delta_sync
            && has_more_from_repo
            && let Some(last_item) = items.last()
        {
            let last_updated_at: Option<DateTime<Utc>> =
                sqlx::query_scalar("SELECT updated_at FROM items WHERE id = ?")
                    .bind(&last_item.id)
                    .fetch_optional(self.pool)
                    .await?;
            let boundary_ts = last_updated_at.map_or(now, |last_ts| last_ts.trunc_subsecs(3));
            (true, boundary_ts, Some(last_item.id.clone()))
        } else {
            (false, now, None)
        };

        let tags = TagRepo::new(self.pool).list().await?;
        let tombstones = self.list_tombstones(inclusive_since).await?;

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
            },
            inserted_candidates,
        ))
    }

    #[allow(clippy::too_many_lines)]
    async fn apply_incoming_tombstones(
        conn: &mut SqliteConnection,
        tombstones: &[SyncTombstone],
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        for tomb in tombstones {
            let key = tomb.natural_key.trim();
            if key.is_empty() {
                continue;
            }
            match tomb.entity_type {
                ActivityEntityType::Feed => {
                    let canon_key = feed_natural_key(key);
                    let det_id = deterministic_feed_id(&canon_key);
                    let newer_live: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM feeds
                         WHERE (url = ? OR id = ?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         LIMIT 1",
                    )
                    .bind(&canon_key)
                    .bind(&det_id)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if newer_live.is_some() {
                        continue;
                    }
                    Self::record_tombstone(
                        &mut *conn,
                        tomb.entity_type,
                        &canon_key,
                        tomb.deleted_at,
                    )
                    .await?;
                    let deleted_id: Option<String> = sqlx::query_scalar(
                        "DELETE FROM feeds
                         WHERE (url = ? OR id = ?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         RETURNING id",
                    )
                    .bind(&canon_key)
                    .bind(&det_id)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if let Some(fid) = deleted_id {
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::Feed,
                            &fid,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                }
                ActivityEntityType::Item => {
                    if let Some(item_url) = key.strip_prefix("manual\0") {
                        let canon_item = canonicalize_sync_url(item_url);
                        let canon_key = item_natural_key(None, &canon_item);
                        let det_id = deterministic_item_id(None, &canon_item);
                        let newer_live: Option<i64> = sqlx::query_scalar(
                            "SELECT 1 FROM items
                             WHERE (id = ? OR (feed_id IS NULL AND url = ?))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             LIMIT 1",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if newer_live.is_some() {
                            continue;
                        }
                        Self::record_tombstone(
                            &mut *conn,
                            tomb.entity_type,
                            &canon_key,
                            tomb.deleted_at,
                        )
                        .await?;
                        let deleted_id: Option<String> = sqlx::query_scalar(
                            "DELETE FROM items
                             WHERE (id = ? OR (feed_id IS NULL AND url = ?))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             RETURNING id",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if let Some(iid) = deleted_id {
                            Self::record_activity(
                                &mut *conn,
                                ActivityEntityType::Item,
                                &iid,
                                ActivityAction::Delete,
                                now,
                            )
                            .await?;
                        }
                    } else if let Some((feed_url, item_url)) = key.split_once('\0') {
                        let canon_feed = canonicalize_sync_url(feed_url);
                        let canon_item = canonicalize_sync_url(item_url);
                        let canon_key = item_natural_key(Some(&canon_feed), &canon_item);
                        let det_feed_id = deterministic_feed_id(&canon_feed);
                        let det_id = deterministic_item_id(Some(&canon_feed), &canon_item);
                        let newer_live: Option<i64> = sqlx::query_scalar(
                            "SELECT 1 FROM items
                             WHERE (id = ? OR (url = ? AND feed_id IN (SELECT id FROM feeds WHERE url = ? OR id = ?)))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             LIMIT 1",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(&canon_feed)
                        .bind(&det_feed_id)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if newer_live.is_some() {
                            continue;
                        }
                        Self::record_tombstone(
                            &mut *conn,
                            tomb.entity_type,
                            &canon_key,
                            tomb.deleted_at,
                        )
                        .await?;
                        let deleted_id: Option<String> = sqlx::query_scalar(
                            "DELETE FROM items
                             WHERE (id = ? OR (url = ? AND feed_id IN (SELECT id FROM feeds WHERE url = ? OR id = ?)))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             RETURNING id",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(&canon_feed)
                        .bind(&det_feed_id)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if let Some(iid) = deleted_id {
                            Self::record_activity(
                                &mut *conn,
                                ActivityEntityType::Item,
                                &iid,
                                ActivityAction::Delete,
                                now,
                            )
                            .await?;
                        }
                    } else {
                        Self::record_tombstone(&mut *conn, tomb.entity_type, key, tomb.deleted_at)
                            .await?;
                    }
                }
                ActivityEntityType::Tag => {
                    let canon_key = tag_natural_key(key);
                    let newer_live: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM tags
                         WHERE LOWER(name) = LOWER(?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         LIMIT 1",
                    )
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if newer_live.is_some() {
                        continue;
                    }
                    Self::record_tombstone(
                        &mut *conn,
                        tomb.entity_type,
                        &canon_key,
                        tomb.deleted_at,
                    )
                    .await?;
                    sqlx::query(
                        "UPDATE items SET updated_at = ?
                         WHERE id IN (
                             SELECT it.item_id FROM item_tags it
                             JOIN tags t ON t.id = it.tag_id
                             WHERE LOWER(t.name) = LOWER(?)
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', t.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         )",
                    )
                    .bind(now)
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .execute(&mut *conn)
                    .await?;
                    let deleted_id: Option<String> = sqlx::query_scalar(
                        "DELETE FROM tags
                         WHERE LOWER(name) = LOWER(?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         RETURNING id",
                    )
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if let Some(tid) = deleted_id {
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::Tag,
                            &tid,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                }
                ActivityEntityType::Media => {}
            }
        }
        Ok(())
    }

    async fn merge_incoming_tags(
        conn: &mut SqliteConnection,
        tags: &[PeerTagRecord],
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        for t in tags {
            let clean_name = t.name.trim();
            if clean_name.is_empty() {
                continue;
            }
            let nat_key = tag_natural_key(clean_name);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'tag' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts {
                if t.created_at.is_none_or(|c| c <= del_at) {
                    continue;
                }
                Self::clear_tombstone(&mut *conn, ActivityEntityType::Tag, &nat_key).await?;
            }

            let det_id = deterministic_tag_id(clean_name);
            let existing: Option<(String, Option<HexColor>)> = sqlx::query_as(
                "SELECT id, color FROM tags WHERE id = ? OR LOWER(name) = LOWER(?) LIMIT 1",
            )
            .bind(&det_id)
            .bind(clean_name)
            .fetch_optional(&mut *conn)
            .await?;

            if let Some((tag_id, existing_color)) = existing {
                if t.color.is_some() && existing_color != t.color {
                    sqlx::query("UPDATE tags SET color = ? WHERE id = ?")
                        .bind(&t.color)
                        .bind(&tag_id)
                        .execute(&mut *conn)
                        .await?;
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Tag,
                        &tag_id,
                        ActivityAction::Update,
                        now,
                    )
                    .await?;
                }
            } else {
                let created_at = t.created_at.unwrap_or(now);
                let res = sqlx::query(
                    "INSERT OR IGNORE INTO tags (id, name, color, created_at) VALUES (?, ?, ?, ?)",
                )
                .bind(&det_id)
                .bind(clean_name)
                .bind(&t.color)
                .bind(created_at)
                .execute(&mut *conn)
                .await?;
                if res.rows_affected() > 0 {
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Tag,
                        &det_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                }
            }
        }
        Ok(())
    }

    async fn merge_incoming_feeds(
        conn: &mut SqliteConnection,
        feeds: &[PeerFeedRecord],
        req_updated_after: Option<DateTime<Utc>>,
        now: DateTime<Utc>,
    ) -> std::result::Result<HashMap<String, (String, String)>, sqlx::Error> {
        let mut feed_map = HashMap::new();
        for f in feeds {
            Self::merge_single_feed_in_tx(&mut *conn, f, req_updated_after, &mut feed_map, now)
                .await?;
        }
        Ok(feed_map)
    }

    async fn merge_single_feed_in_tx(
        conn: &mut SqliteConnection,
        f: &PeerFeedRecord,
        req_updated_after: Option<DateTime<Utc>>,
        feed_map: &mut HashMap<String, (String, String)>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        type ExistingFeedRow = (String, String, Option<DateTime<Utc>>, bool);
        let feed_url = f.url.as_str();
        let nat_key = feed_natural_key(feed_url);
        let det_id = deterministic_feed_id(feed_url);
        let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
            "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'feed' AND natural_key = ?",
        )
        .bind(&nat_key)
        .fetch_optional(&mut *conn)
        .await?;
        if let Some(del_at) = tomb_ts {
            if f.created_at.is_none_or(|c| c <= del_at) {
                return Ok(());
            }
            Self::clear_tombstone(&mut *conn, ActivityEntityType::Feed, &nat_key).await?;
        }

        let existing: Option<ExistingFeedRow> = sqlx::query_as(
            "SELECT id, url, last_polled, is_active
             FROM feeds
             WHERE url = ? OR id = ? OR id = ?
             LIMIT 1",
        )
        .bind(feed_url)
        .bind(&f.id)
        .bind(&det_id)
        .fetch_optional(&mut *conn)
        .await?;

        let clean_title = f.title.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_desc = f.description.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_lang = f.language.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err = f.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_polled_by = f.last_polled_by.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err_by = if clean_err.is_some() {
            f.last_error_by.as_deref().map(str::trim).filter(|s| !s.is_empty())
        } else {
            None
        };

        if let Some((local_id, local_url, local_last_polled, local_is_active)) = existing {
            Self::update_existing_feed_in_tx(
                &mut *conn,
                &local_id,
                f,
                local_last_polled,
                local_is_active,
                req_updated_after,
                clean_title,
                clean_desc,
                clean_lang,
                clean_err,
                clean_polled_by,
                clean_err_by,
                now,
            )
            .await?;
            if !f.id.is_empty() {
                feed_map.insert(f.id.clone(), (local_id.clone(), local_url.clone()));
            }
            feed_map.insert(local_id.clone(), (local_id, local_url));
        } else {
            let local_id = det_id;
            let interval = f.poll_interval.clamp(5, 1440);
            let created_at = f.created_at.unwrap_or(now);
            sqlx::query(
                "INSERT OR IGNORE INTO feeds
                 (id, feed_type, url, title, description, language, poll_interval, last_polled, last_polled_by, last_error, last_error_by, is_active, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&local_id)
            .bind(f.feed_type)
            .bind(feed_url)
            .bind(clean_title)
            .bind(clean_desc)
            .bind(clean_lang)
            .bind(interval)
            .bind(f.last_polled)
            .bind(clean_polled_by)
            .bind(clean_err)
            .bind(clean_err_by)
            .bind(f.is_active)
            .bind(created_at)
            .bind(now)
            .execute(&mut *conn)
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Feed,
                &local_id,
                ActivityAction::Create,
                now,
            )
            .await?;
            if !f.id.is_empty() {
                feed_map.insert(f.id.clone(), (local_id.clone(), feed_url.to_owned()));
            }
            feed_map.insert(local_id.clone(), (local_id, feed_url.to_owned()));
        }
        Ok(())
    }

    #[allow(clippy::too_many_arguments)]
    async fn update_existing_feed_in_tx(
        conn: &mut SqliteConnection,
        local_id: &str,
        f: &PeerFeedRecord,
        local_last_polled: Option<DateTime<Utc>>,
        local_is_active: bool,
        req_updated_after: Option<DateTime<Utc>>,
        clean_title: Option<&str>,
        clean_desc: Option<&str>,
        clean_lang: Option<&str>,
        clean_err: Option<&str>,
        clean_polled_by: Option<&str>,
        clean_err_by: Option<&str>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let incoming_poll_newer = match (f.last_polled, local_last_polled) {
            (Some(inc), Some(loc)) => inc > loc,
            (Some(_), None) => true,
            _ => false,
        };
        if incoming_poll_newer {
            sqlx::query(
                "UPDATE feeds
                 SET title = COALESCE(title, ?),
                     description = COALESCE(?, description),
                     language = COALESCE(?, language),
                     last_polled = ?,
                     last_polled_by = ?,
                     last_error = ?,
                     last_error_by = ?,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(clean_title)
            .bind(clean_desc)
            .bind(clean_lang)
            .bind(f.last_polled)
            .bind(clean_polled_by)
            .bind(clean_err)
            .bind(clean_err_by)
            .bind(now)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;
        } else {
            sqlx::query(
                "UPDATE feeds
                 SET title = COALESCE(title, ?),
                     description = COALESCE(description, ?),
                     language = COALESCE(language, ?)
                 WHERE id = ?",
            )
            .bind(clean_title)
            .bind(clean_desc)
            .bind(clean_lang)
            .bind(local_id)
            .execute(&mut *conn)
            .await?;
        }

        if f.is_active != local_is_active {
            let last_archive_change_at: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT MAX(timestamp) FROM activity_log
                 WHERE entity_type = 'feed' AND entity_id = ? AND action IN ('archive', 'unarchive')",
            )
            .bind(local_id)
            .fetch_optional(&mut *conn)
            .await?
            .flatten();

            let should_update_active = match (req_updated_after, last_archive_change_at) {
                (Some(ua), Some(server_ts)) => server_ts <= ua,
                (Some(_), None) => true,
                (None, None) => !f.is_active,
                (None, Some(_)) => false,
            };

            if should_update_active {
                sqlx::query("UPDATE feeds SET is_active = ?, updated_at = ? WHERE id = ?")
                    .bind(f.is_active)
                    .bind(now)
                    .bind(local_id)
                    .execute(&mut *conn)
                    .await?;
                let action =
                    if f.is_active { ActivityAction::Unarchive } else { ActivityAction::Archive };
                Self::record_activity(&mut *conn, ActivityEntityType::Feed, local_id, action, now)
                    .await?;
            }
        }
        Ok(())
    }

    async fn resolve_peer_item_feed(
        conn: &mut SqliteConnection,
        inc: &PeerItemRecord,
        feed_map: &HashMap<String, (String, String)>,
    ) -> std::result::Result<Option<(Option<String>, Option<String>)>, sqlx::Error> {
        if inc.feed_url.is_none() && inc.feed_id.is_none() {
            return Ok(Some((None, None)));
        }
        if let Some(ref f_url) = inc.feed_url {
            let det_fid = deterministic_feed_id(f_url.as_str());
            let found: Option<(String, String)> =
                sqlx::query_as("SELECT id, url FROM feeds WHERE url = ? OR id = ? LIMIT 1")
                    .bind(f_url.as_str())
                    .bind(&det_fid)
                    .fetch_optional(&mut *conn)
                    .await?;
            if let Some((fid, url)) = found {
                return Ok(Some((Some(fid), Some(url))));
            }
        }
        if let Some(ref fid) = inc.feed_id {
            if let Some((mapped_id, mapped_url)) = feed_map.get(fid) {
                return Ok(Some((Some(mapped_id.clone()), Some(mapped_url.clone()))));
            }
            let found: Option<(String, String)> =
                sqlx::query_as("SELECT id, url FROM feeds WHERE id = ? LIMIT 1")
                    .bind(fid)
                    .fetch_optional(&mut *conn)
                    .await?;
            if let Some((local_fid, url)) = found {
                return Ok(Some((Some(local_fid), Some(url))));
            }
        }
        Ok(None)
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_incoming_items(
        conn: &mut SqliteConnection,
        items: &[PeerItemRecord],
        feed_map: &HashMap<String, (String, String)>,
        now: DateTime<Utc>,
    ) -> std::result::Result<Vec<InsertedItemCandidate>, sqlx::Error> {
        let mut inserted_candidates = Vec::new();

        for inc in items {
            let Some((resolved_feed_id, resolved_feed_url)) =
                Self::resolve_peer_item_feed(&mut *conn, inc, feed_map).await?
            else {
                // Item belongs to a feed that was deleted/unsubscribed; skip it.
                continue;
            };

            let nat_key = item_natural_key(resolved_feed_url.as_deref(), inc.url.as_str());
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts {
                let inc_created = inc.created_at.unwrap_or(del_at);
                let inc_interacted = inc.interaction_updated_at.unwrap_or(del_at);
                if inc_created <= del_at && inc_interacted <= del_at {
                    continue;
                }
                Self::clear_tombstone(&mut *conn, ActivityEntityType::Item, &nat_key).await?;
            }

            let clean_title = inc.title.trim();
            let final_title = if clean_title.is_empty() { inc.url.as_str() } else { clean_title };
            let clean_author = inc.author.as_deref().map(str::trim).filter(|s| !s.is_empty());
            let clean_html = inc
                .content_html
                .as_deref()
                .map(|h| HtmlSanitizer::clean_with_base(h, Some(&inc.url)))
                .filter(|h| !h.trim().is_empty());
            let clean_summary = inc
                .summary
                .as_deref()
                .map(|s| HtmlSanitizer::plain_text_excerpt(s, MAX_SUMMARY_CHARS))
                .filter(|s| !s.is_empty())
                .or_else(|| {
                    clean_html
                        .as_deref()
                        .map(|h| HtmlSanitizer::plain_text_excerpt_from_clean(h, MAX_SUMMARY_CHARS))
                        .filter(|s| !s.is_empty())
                });
            let clean_thumb = inc
                .thumbnail_url
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .and_then(|u| HtmlSanitizer::resolve_and_validate_thumbnail_url(u, Some(&inc.url)))
                .or_else(|| {
                    clean_html.as_deref().and_then(|h| {
                        HtmlSanitizer::extract_first_image_url_with_base(h, Some(&inc.url))
                    })
                });
            let clean_audio_url = inc
                .audio_url
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .and_then(|u| SafeHttpClient::parse_url(u).ok())
                .map(String::from)
                .or_else(|| {
                    (inc.content_type == ContentType::Audio && is_direct_audio_url(&inc.url))
                        .then(|| inc.url.as_str().to_owned())
                });
            let clean_duration = inc.duration.filter(|&d| d > 0);
            let resolved_content_type =
                if clean_audio_url.is_some() { ContentType::Audio } else { inc.content_type };
            let clamped_progress =
                if inc.read_progress.is_finite() { inc.read_progress.clamp(0.0, 1.0) } else { 0.0 };

            let det_item_id = deterministic_item_id(resolved_feed_url.as_deref(), inc.url.as_str());
            let existing: Option<ExistingItemRow> = if let Some(ref fid) = resolved_feed_id {
                sqlx::query_as(
                    "SELECT id, title, author, published_at, summary, content_html, thumbnail_url,
                            audio_url, duration, content_type, status, is_favorite, read_at, read_progress, interaction_updated_at
                     FROM items
                     WHERE (feed_id = ? AND url = ?) OR id = ? OR id = ?
                     LIMIT 1",
                )
                .bind(fid)
                .bind(inc.url.as_str())
                .bind(&inc.id)
                .bind(&det_item_id)
                .fetch_optional(&mut *conn)
                .await?
            } else {
                sqlx::query_as(
                    "SELECT id, title, author, published_at, summary, content_html, thumbnail_url,
                            audio_url, duration, content_type, status, is_favorite, read_at, read_progress, interaction_updated_at
                     FROM items
                     WHERE (feed_id IS NULL AND url = ?) OR id = ? OR id = ?
                     LIMIT 1",
                )
                .bind(inc.url.as_str())
                .bind(&inc.id)
                .bind(&det_item_id)
                .fetch_optional(&mut *conn)
                .await?
            };

            if let Some(ex) = existing {
                Self::merge_existing_item_in_tx(
                    &mut *conn,
                    &ex,
                    inc,
                    final_title,
                    clean_author,
                    clean_summary.as_deref(),
                    clean_html.as_deref(),
                    clean_thumb.as_deref(),
                    clean_audio_url.as_deref(),
                    clean_duration,
                    resolved_content_type,
                    clamped_progress,
                    now,
                )
                .await?;
            } else {
                let item_id = det_item_id;
                let created_at = inc.created_at.unwrap_or(now);
                let insert_inter_ts = inc.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
                let insert_read_at = if inc.status == ItemStatus::Archived {
                    inc.read_at.or(insert_inter_ts).or(Some(now))
                } else {
                    inc.read_at
                };
                let res = sqlx::query(
                    "INSERT OR IGNORE INTO items
                     (id, feed_id, title, url, author, published_at, summary, content_html, thumbnail_url, audio_url, duration, content_type, status, is_favorite, read_at, read_progress, interaction_updated_at, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
                .bind(&item_id)
                .bind(resolved_feed_id.as_deref())
                .bind(final_title)
                .bind(inc.url.as_str())
                .bind(clean_author)
                .bind(inc.published_at)
                .bind(clean_summary.as_deref())
                .bind(clean_html.as_deref())
                .bind(clean_thumb.as_deref())
                .bind(clean_audio_url.as_deref())
                .bind(clean_duration)
                .bind(resolved_content_type)
                .bind(inc.status)
                .bind(inc.is_favorite)
                .bind(insert_read_at)
                .bind(clamped_progress)
                .bind(insert_inter_ts)
                .bind(created_at)
                .bind(now)
                .execute(&mut *conn)
                .await?;

                if res.rows_affected() > 0 {
                    Self::record_activity(
                        &mut *conn,
                        ActivityEntityType::Item,
                        &item_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                    Self::replace_item_tags_in_tx(
                        &mut *conn,
                        &item_id,
                        &inc.tag_names,
                        now,
                        false,
                        insert_inter_ts,
                    )
                    .await?;
                    inserted_candidates.push((
                        item_id,
                        PollItemCandidate {
                            title: final_title.to_owned(),
                            url: inc.url.clone(),
                            author: clean_author.map(str::to_owned),
                            published_at: inc.published_at,
                            summary: clean_summary.unwrap_or_default(),
                            content_html: clean_html,
                            thumbnail_url: clean_thumb,
                            audio_url: clean_audio_url,
                            duration: clean_duration,
                            content_type: resolved_content_type,
                        },
                    ));
                }
            }
        }

        Ok(inserted_candidates)
    }

    #[allow(clippy::too_many_arguments)]
    async fn merge_existing_item_in_tx(
        conn: &mut SqliteConnection,
        ex: &ExistingItemRow,
        inc: &PeerItemRecord,
        final_title: &str,
        clean_author: Option<&str>,
        clean_summary: Option<&str>,
        clean_html: Option<&str>,
        clean_thumb: Option<&str>,
        clean_audio_url: Option<&str>,
        clean_duration: Option<i64>,
        resolved_content_type: ContentType,
        clamped_progress: f64,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let merged_title = if (ex.title.trim().is_empty() || ex.title == inc.url.as_str())
            && !final_title.is_empty()
        {
            final_title
        } else {
            ex.title.as_str()
        };
        let merged_author = ex.author.as_deref().or(clean_author);
        let merged_pub = ex.published_at.or(inc.published_at);
        let merged_summary = pick_richer_text(ex.summary.as_deref(), clean_summary);
        let merged_html = pick_richer_text(ex.content_html.as_deref(), clean_html);
        let merged_thumb = ex.thumbnail_url.as_deref().or(clean_thumb);
        let merged_audio_url = ex.audio_url.as_deref().or(clean_audio_url);
        let merged_duration = ex.duration.or(clean_duration);
        let merged_content_type = if merged_audio_url.is_some()
            || ex.content_type == ContentType::Audio
            || resolved_content_type == ContentType::Audio
        {
            ContentType::Audio
        } else {
            ex.content_type
        };

        let content_changed = merged_title != ex.title
            || merged_author != ex.author.as_deref()
            || merged_pub != ex.published_at
            || merged_summary != ex.summary.as_deref()
            || merged_html != ex.content_html.as_deref()
            || merged_thumb != ex.thumbnail_url.as_deref()
            || merged_audio_url != ex.audio_url.as_deref()
            || merged_duration != ex.duration
            || merged_content_type != ex.content_type;

        let (target_status, target_fav, target_read_at, target_prog, target_inter_ts, tag_mode) =
            resolve_merged_interaction_state(ex, inc, clamped_progress, now);

        let interaction_changed = target_status != ex.status
            || target_fav != ex.is_favorite
            || target_read_at != ex.read_at
            || (target_prog - ex.read_progress).abs() > f64::EPSILON
            || target_inter_ts != ex.interaction_updated_at;

        if content_changed || interaction_changed {
            sqlx::query(
                "UPDATE items
                 SET title = ?, author = ?, published_at = ?, summary = ?, content_html = ?,
                     thumbnail_url = ?, audio_url = ?, duration = ?, content_type = ?, status = ?,
                     is_favorite = ?, read_at = ?, read_progress = ?, interaction_updated_at = ?,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(merged_title)
            .bind(merged_author)
            .bind(merged_pub)
            .bind(merged_summary)
            .bind(merged_html)
            .bind(merged_thumb)
            .bind(merged_audio_url)
            .bind(merged_duration)
            .bind(merged_content_type)
            .bind(target_status)
            .bind(target_fav)
            .bind(target_read_at)
            .bind(target_prog)
            .bind(target_inter_ts)
            .bind(now)
            .bind(&ex.id)
            .execute(&mut *conn)
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Item,
                &ex.id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        if let TagMergeMode::Replace | TagMergeMode::Union = tag_mode {
            let clear_unlisted = matches!(tag_mode, TagMergeMode::Replace);
            Self::replace_item_tags_in_tx(
                &mut *conn,
                &ex.id,
                &inc.tag_names,
                now,
                clear_unlisted,
                target_inter_ts,
            )
            .await?;
        }

        Ok(())
    }

    async fn replace_item_tags_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        tag_names: &[String],
        now: DateTime<Utc>,
        clear_unlisted: bool,
        preserved_interaction_ts: Option<DateTime<Utc>>,
    ) -> std::result::Result<(), sqlx::Error> {
        let mut normalized_set = HashSet::new();
        let mut clean_names = Vec::new();
        for raw in tag_names {
            let trimmed = raw.trim();
            if !trimmed.is_empty() && normalized_set.insert(trimmed.to_lowercase()) {
                clean_names.push(trimmed.to_owned());
            }
        }

        if clear_unlisted {
            sqlx::query("DELETE FROM item_tags WHERE item_id = ?")
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
        }

        for name in clean_names {
            let nat_key = tag_natural_key(&name);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones WHERE entity_type = 'tag' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(del_at) = tomb_ts
                && preserved_interaction_ts.is_none_or(|ts| ts <= del_at)
            {
                continue;
            }
            let _ =
                TagRepo::get_or_create_and_attach_in_tx(&mut *conn, item_id, &name, now).await?;
        }

        sqlx::query("UPDATE items SET interaction_updated_at = ?, updated_at = ? WHERE id = ?")
            .bind(preserved_interaction_ts)
            .bind(now)
            .bind(item_id)
            .execute(&mut *conn)
            .await?;
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TagMergeMode {
    Replace,
    Union,
    KeepExisting,
}

#[derive(Debug, sqlx::FromRow)]
struct ExistingItemRow {
    id: String,
    title: String,
    author: Option<String>,
    published_at: Option<DateTime<Utc>>,
    summary: Option<String>,
    content_html: Option<String>,
    thumbnail_url: Option<String>,
    audio_url: Option<String>,
    duration: Option<i64>,
    content_type: ContentType,
    status: ItemStatus,
    is_favorite: bool,
    read_at: Option<DateTime<Utc>>,
    read_progress: f64,
    interaction_updated_at: Option<DateTime<Utc>>,
}

fn pick_richer_text<'a>(existing: Option<&'a str>, incoming: Option<&'a str>) -> Option<&'a str> {
    match (
        existing.map(str::trim).filter(|s| !s.is_empty()),
        incoming.map(str::trim).filter(|s| !s.is_empty()),
    ) {
        (Some(ex), Some(inc)) => {
            if inc.len() > ex.len() {
                incoming
            } else {
                existing
            }
        }
        (Some(_), None) => existing,
        (None, Some(_)) => incoming,
        (None, None) => None,
    }
}

type MergedInteractionState =
    (ItemStatus, bool, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, TagMergeMode);

fn resolve_merged_interaction_state(
    ex: &ExistingItemRow,
    inc: &PeerItemRecord,
    clamped_progress: f64,
    now: DateTime<Utc>,
) -> MergedInteractionState {
    let inc_norm = inc.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let loc_norm = ex.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let (status, fav, raw_read_at, prog, inter_ts, tag_mode) = match (inc_norm, loc_norm) {
        (Some(inc_ts), None) => (
            inc.status,
            inc.is_favorite,
            inc.read_at,
            clamped_progress,
            Some(inc_ts),
            TagMergeMode::Replace,
        ),
        (Some(inc_ts), Some(loc_ts)) if inc_ts > loc_ts => (
            inc.status,
            inc.is_favorite,
            inc.read_at,
            clamped_progress,
            Some(inc_ts),
            TagMergeMode::Replace,
        ),
        (Some(inc_ts), Some(loc_ts)) if inc_ts == loc_ts => (
            if ex.status == ItemStatus::Archived || inc.status == ItemStatus::Archived {
                ItemStatus::Archived
            } else {
                ItemStatus::Unread
            },
            ex.is_favorite || inc.is_favorite,
            ex.read_at.max(inc.read_at),
            ex.read_progress.max(clamped_progress),
            Some(loc_ts),
            TagMergeMode::Union,
        ),
        _ => (
            ex.status,
            ex.is_favorite,
            ex.read_at,
            ex.read_progress,
            ex.interaction_updated_at,
            if ex.interaction_updated_at.is_none() && !inc.tag_names.is_empty() {
                TagMergeMode::Union
            } else {
                TagMergeMode::KeepExisting
            },
        ),
    };
    let read_at = if status == ItemStatus::Archived {
        raw_read_at.or(ex.read_at).or(inc.read_at).or(inter_ts).or(Some(now))
    } else {
        raw_read_at
    };
    (status, fav, read_at, prog, inter_ts, tag_mode)
}
