use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use crate::db::{
    ActivityAction, ActivityEntityType, FeedRepo, ItemRepo,
    sync::{
        SyncRepo, SyncTombstone,
        identity::{
            canonicalize_sync_url, deterministic_feed_id, deterministic_item_id, feed_natural_key,
            item_natural_key, tag_natural_key,
        },
    },
};

impl SyncRepo<'_> {
    #[allow(clippy::too_many_lines)]
    pub(super) async fn apply_incoming_tombstones(
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
                        "SELECT 1 FROM feed_sources fs
                         JOIN entries e ON e.id = fs.collection_id
                         WHERE (fs.feed_url = ? OR fs.collection_id = ?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
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

                    let target_feed_id: Option<String> = sqlx::query_scalar(
                        "SELECT fs.collection_id
                         FROM feed_sources fs
                         JOIN entries e ON e.id = fs.collection_id
                         WHERE (fs.feed_url = ? OR fs.collection_id = ?)
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         LIMIT 1",
                    )
                    .bind(&canon_key)
                    .bind(&det_id)
                    .bind(tomb.deleted_at)
                    .fetch_optional(&mut *conn)
                    .await?;

                    if let Some(fid) = target_feed_id {
                        FeedRepo::delete_feed_preserving_history_in_tx(&mut *conn, &fid, now)
                            .await?;
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
                            "SELECT 1 FROM entries e
                             JOIN entry_state es ON es.entry_id = e.id
                             WHERE es.inbox_state IN ('unread', 'dismissed')
                               AND (e.id = ? OR (e.url = ? AND NOT EXISTS (
                                   SELECT 1 FROM collection_entries ce
                                   JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                                   WHERE ce.entry_id = e.id
                               )))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
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
                        let target_ids: Vec<String> = sqlx::query_scalar(
                            "SELECT e.id FROM entries e
                             JOIN entry_state es ON es.entry_id = e.id
                             WHERE es.inbox_state IN ('unread', 'dismissed')
                               AND (e.id = ? OR (e.url = ? AND NOT EXISTS (
                                   SELECT 1 FROM collection_entries ce
                                   JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                                   WHERE ce.entry_id = e.id
                               )))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(tomb.deleted_at)
                        .fetch_all(&mut *conn)
                        .await?;
                        for iid in target_ids {
                            if ItemRepo::delete_inbox_item_preserving_history_in_tx(
                                &mut *conn, &iid, now,
                            )
                            .await?
                            {
                                Self::record_activity(
                                    &mut *conn,
                                    ActivityEntityType::Item,
                                    &iid,
                                    ActivityAction::Delete,
                                    now,
                                )
                                .await?;
                            }
                        }
                    } else if let Some((feed_url, item_url)) = key.split_once('\0') {
                        let canon_feed = canonicalize_sync_url(feed_url);
                        let canon_item = canonicalize_sync_url(item_url);
                        let canon_key = item_natural_key(Some(&canon_feed), &canon_item);
                        let det_feed_id = deterministic_feed_id(&canon_feed);
                        let det_id = deterministic_item_id(Some(&canon_feed), &canon_item);
                        let newer_live: Option<i64> = sqlx::query_scalar(
                            "SELECT 1 FROM entries e
                             JOIN entry_state es ON es.entry_id = e.id
                             WHERE es.inbox_state IN ('unread', 'dismissed')
                               AND (e.id = ? OR (e.url = ? AND EXISTS (
                                   SELECT 1 FROM collection_entries ce
                                   JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                                   WHERE ce.entry_id = e.id
                                     AND (fs.feed_url = ? OR fs.collection_id = ?)
                               )))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
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
                        let target_ids: Vec<String> = sqlx::query_scalar(
                            "SELECT e.id FROM entries e
                             JOIN entry_state es ON es.entry_id = e.id
                             WHERE es.inbox_state IN ('unread', 'dismissed')
                               AND (e.id = ? OR (e.url = ? AND EXISTS (
                                   SELECT 1 FROM collection_entries ce
                                   JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                                   WHERE ce.entry_id = e.id
                                     AND (fs.feed_url = ? OR fs.collection_id = ?)
                               )))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
                        )
                        .bind(&det_id)
                        .bind(&canon_item)
                        .bind(&canon_feed)
                        .bind(&det_feed_id)
                        .bind(tomb.deleted_at)
                        .fetch_all(&mut *conn)
                        .await?;
                        for iid in target_ids {
                            if ItemRepo::delete_inbox_item_preserving_history_in_tx(
                                &mut *conn, &iid, now,
                            )
                            .await?
                            {
                                Self::record_activity(
                                    &mut *conn,
                                    ActivityEntityType::Item,
                                    &iid,
                                    ActivityAction::Delete,
                                    now,
                                )
                                .await?;
                            }
                        }
                    } else {
                        Self::record_tombstone(&mut *conn, tomb.entity_type, key, tomb.deleted_at)
                            .await?;
                    }
                }
                ActivityEntityType::Tag => {
                    let canon_key = tag_natural_key(key);
                    let tag_slug = format!("tag:{canon_key}");
                    let newer_live: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM entries e
                         JOIN collection_specs cs ON cs.collection_id = e.id
                         WHERE cs.collection_role = 'tag'
                           AND (cs.slug = ? OR LOWER(e.title) = LOWER(?))
                           AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         LIMIT 1",
                    )
                    .bind(&tag_slug)
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
                        "UPDATE entries SET updated_at = ?
                         WHERE id IN (
                             SELECT ce.entry_id FROM collection_entries ce
                             JOIN entries te ON te.id = ce.collection_id
                             JOIN collection_specs cs ON cs.collection_id = te.id
                             WHERE cs.collection_role = 'tag'
                               AND (cs.slug = ? OR LOWER(te.title) = LOWER(?))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', te.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         )",
                    )
                    .bind(now)
                    .bind(&tag_slug)
                    .bind(&canon_key)
                    .bind(tomb.deleted_at)
                    .execute(&mut *conn)
                    .await?;
                    let deleted_id: Option<String> = sqlx::query_scalar(
                        "DELETE FROM entries
                         WHERE id IN (
                             SELECT e.id FROM entries e
                             JOIN collection_specs cs ON cs.collection_id = e.id
                             WHERE cs.collection_role = 'tag'
                               AND (cs.slug = ? OR LOWER(e.title) = LOWER(?))
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                         )
                         RETURNING id",
                    )
                    .bind(&tag_slug)
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
                ActivityEntityType::CollectionEntry => {
                    if let Some((col_id, entry_id)) = key.split_once('\0') {
                        let newer_live: Option<i64> = sqlx::query_scalar(
                            "SELECT 1 FROM collection_entries
                             WHERE collection_id = ? AND entry_id = ?
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', added_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                             LIMIT 1",
                        )
                        .bind(col_id)
                        .bind(entry_id)
                        .bind(tomb.deleted_at)
                        .fetch_optional(&mut *conn)
                        .await?;
                        if newer_live.is_some() {
                            continue;
                        }
                        Self::record_tombstone(&mut *conn, tomb.entity_type, key, tomb.deleted_at)
                            .await?;
                        sqlx::query(
                            "DELETE FROM collection_entries
                             WHERE collection_id = ? AND entry_id = ?
                               AND strftime('%Y-%m-%dT%H:%M:%fZ', added_at) <= strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
                        )
                        .bind(col_id)
                        .bind(entry_id)
                        .bind(tomb.deleted_at)
                        .execute(&mut *conn)
                        .await?;
                    }
                }
                ActivityEntityType::Media
                | ActivityEntityType::Entry
                | ActivityEntityType::Collection
                | ActivityEntityType::ConsumptionLog
                | ActivityEntityType::Comparison => {}
            }
        }
        Ok(())
    }
}
