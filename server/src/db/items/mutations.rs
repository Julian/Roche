use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};
#[cfg(test)]
use url::Url;

use super::{ITEM_SELECT_FULL, Item, ItemRepo, ManualItemInput};
#[cfg(test)]
use super::{NewItem, TagNames};
use crate::{
    db::{
        ActivityAction, ActivityEntityType, CatalogError, CatalogRepo, EntryKind, InboxState,
        ItemStatus, MediaRepo, ReadAtPatch, SyncRepo, Tag, TagRepo,
        feeds::resolve_dedup_entry_id,
        sync::{
            STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID, canonicalize_sync_url,
            deterministic_item_id, item_natural_key,
        },
    },
    feeds::arxiv::ArxivParser,
};

/// Auto-logs feed completion (`consumed`) when:
/// - a `podcast_episode` reaches audio playback completion (`progress >= 0.98`, or archived with `>= 0.9`), or
/// - an `article` or `paper` is archived (`inbox_state = 'dismissed'`) with `progress >= 0.9`.
///
/// Delegates to `CatalogRepo::ensure_auto_consumed_with_parents_in_tx` so auto-completion uses
/// `deterministic_auto_consumption_log_id`, removes from `Want` queues, engages parent
/// `podcast`/`publication`/`channel` collections, and records sync activity without duplicate SQL.
pub(super) async fn maybe_auto_log_feed_completion_in_tx(
    conn: &mut SqliteConnection,
    entry_id: &str,
    entry_kind: EntryKind,
    inbox_state: InboxState,
    progress: f64,
    ts: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let should_log = match entry_kind {
        EntryKind::PodcastEpisode => {
            progress >= 0.98 || (inbox_state == InboxState::Dismissed && progress >= 0.9)
        }
        EntryKind::Article | EntryKind::Paper => {
            inbox_state == InboxState::Dismissed && progress >= 0.9
        }
        _ => false,
    };
    if !should_log {
        return Ok(());
    }

    match CatalogRepo::ensure_auto_consumed_with_parents_in_tx(conn, entry_id, None, ts, ts).await {
        Ok(_) => Ok(()),
        Err(CatalogError::Db(e)) => Err(e),
        Err(other) => Err(sqlx::Error::Protocol(other.to_string())),
    }
}

pub async fn set_favorite_membership_in_tx(
    conn: &mut SqliteConnection,
    entry_id: &str,
    is_favorite: bool,
    now: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    if is_favorite {
        sqlx::query!(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
             VALUES (?, ?, ?)",
            STARRED_COLLECTION_ID,
            entry_id,
            now as _,
        )
        .execute(&mut *conn)
        .await?;
    } else {
        sqlx::query!(
            "DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
            STARRED_COLLECTION_ID,
            entry_id,
        )
        .execute(&mut *conn)
        .await?;
    }
    Ok(())
}

impl ItemRepo<'_> {
    #[cfg(test)]
    pub async fn create(&self, new_item: &NewItem<'_>) -> Result<Item> {
        let feed_url: Option<String> = match new_item.feed_id {
            Some(fid) => {
                sqlx::query_scalar!(
                    "SELECT feed_url FROM feed_sources WHERE collection_id = ?",
                    fid,
                )
                .fetch_optional(self.pool)
                .await?
            }
            None => None,
        };
        let url_str = new_item.url.as_str();
        let id = deterministic_item_id(feed_url.as_deref(), url_str);
        let canon_url = canonicalize_sync_url(url_str);
        let now = SyncRepo::now_utc();
        let entry_kind = EntryKind::from_content_type(new_item.content_type);
        let creators_json = new_item
            .author
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|a| serde_json::to_string(&[a]).ok())
            .unwrap_or_else(|| "[]".to_owned());

        let mut tx = self.pool.begin().await?;
        Self::insert_created_item_rows_in_tx(
            &mut tx,
            &id,
            new_item,
            entry_kind,
            &creators_json,
            &canon_url,
            now,
        )
        .await?;
        tx.commit().await?;

        Ok(Item {
            id,
            feed_id: new_item.feed_id.map(String::from),
            feed_url,
            feed_title: None,
            title: new_item.title.to_string(),
            url: new_item.url.clone(),
            author: new_item.author.map(String::from),
            published_at: new_item.published_at,
            created_at: now,
            summary: new_item.summary.map(String::from),
            content_html: None,
            thumbnail_url: None,
            audio_url: None,
            content_type: new_item.content_type,
            status: ItemStatus::Unread,
            is_favorite: false,
            read_at: None,
            read_progress: 0.0,
            interaction_updated_at: None,
            thumbnail_media_id: None,
            duration: None,
            tag_names: TagNames::default(),
        })
    }

    #[cfg(test)]
    async fn insert_created_item_rows_in_tx(
        tx: &mut SqliteConnection,
        id: &str,
        new_item: &NewItem<'_>,
        entry_kind: EntryKind,
        creators_json: &str,
        canon_url: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let url_str = new_item.url.as_str();
        sqlx::query!(
            "INSERT INTO entries
             (id, kind, title, creators_json, url, published_at, summary, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id,
            entry_kind as _,
            new_item.title,
            creators_json,
            url_str,
            new_item.published_at as _,
            new_item.summary,
            now as _,
            now as _,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!(
            "INSERT INTO entry_content (entry_id, content_html, content_type)
             VALUES (?, NULL, ?)",
            id,
            new_item.content_type as _,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!(
            "INSERT INTO entry_state (entry_id, inbox_state, progress, updated_at)
             VALUES (?, 'unread', 0.0, ?)",
            id,
            now as _,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('url', ?, ?)",
            canon_url,
            id,
        )
        .execute(&mut *tx)
        .await?;

        if let Some(fid) = new_item.feed_id {
            sqlx::query!(
                "INSERT INTO collection_entries (collection_id, entry_id, added_at)
                 VALUES (?, ?, ?)",
                fid,
                id,
                now as _,
            )
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    #[cfg(test)]
    pub async fn save_manual_with_tags(
        &self,
        url: &Url,
        title: &str,
        tags: &[String],
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        self.save_manual_input_with_tags(&ManualItemInput {
            url,
            title,
            summary: None,
            content_html: None,
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: crate::db::ContentType::Article,
            tags,
        })
        .await
    }

    #[allow(clippy::too_many_lines)]
    pub async fn save_manual_input_with_tags(
        &self,
        input: &ManualItemInput<'_>,
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        let url_str = input.url.as_str();
        let trimmed_title = input.title.trim();
        let det_item_id = deterministic_item_id(None, url_str);
        let canon_url = canonicalize_sync_url(url_str);
        let arxiv_id = ArxivParser::extract_arxiv_id(url_str);
        let now = SyncRepo::now_utc();
        let clean_duration = input.duration.filter(|&d| d > 0);
        let entry_kind = EntryKind::from_content_type(input.content_type);
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(
            &mut tx,
            ActivityEntityType::Item,
            &item_natural_key(None, url_str),
        )
        .await?;

        // Upfront deduplication: if an entry already owns this deterministic ID, canonical URL,
        // or arXiv ID (whether unread, archived, or catalog-only), idempotently update/unarchive
        // it rather than attempting a duplicate `INSERT INTO entries`.
        let existing_dedup =
            resolve_dedup_entry_id(&mut tx, &det_item_id, &canon_url, arxiv_id.as_deref()).await?;

        let final_item_id = if let Some(existing_id) = existing_dedup {
            sqlx::query!(
                "UPDATE entries
                 SET title = CASE WHEN ? = ? AND ? != '' THEN ? ELSE title END,
                     summary = COALESCE(summary, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     updated_at = ?
                 WHERE id = ?",
                existing_id,
                det_item_id,
                trimmed_title,
                input.title,
                input.summary,
                input.thumbnail_url,
                now as _,
                existing_id,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = COALESCE(NULLIF(excluded.content_html, ''), entry_content.content_html)",
                existing_id,
                input.content_html,
                input.content_type as _,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT INTO entry_state (entry_id, inbox_state, progress, interaction_updated_at, updated_at)
                 VALUES (?, 'unread', 0.0, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     inbox_state = 'unread',
                     read_at = NULL,
                     interaction_updated_at = excluded.interaction_updated_at,
                     updated_at = excluded.updated_at",
                existing_id,
                now as _,
                now as _,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('url', ?, ?)",
                canon_url,
                existing_id,
            )
            .execute(&mut *tx)
            .await?;

            if let Some(ref aid) = arxiv_id {
                sqlx::query!(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('arxiv', ?, ?)",
                    aid,
                    existing_id,
                )
                .execute(&mut *tx)
                .await?;
            }

            MediaRepo::upsert_audio_enclosure_in_tx(
                &mut tx,
                &existing_id,
                input.audio_url,
                clean_duration,
                now,
            )
            .await?;

            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Item,
                &existing_id,
                ActivityAction::Update,
                now,
            )
            .await?;
            existing_id
        } else {
            sqlx::query!(
                "INSERT INTO entries
                 (id, kind, title, creators_json, url, published_at, summary, thumbnail_url, created_at, updated_at)
                 VALUES (?, ?, ?, '[]', ?, NULL, ?, ?, ?, ?)",
                det_item_id,
                entry_kind as _,
                input.title,
                url_str,
                input.summary,
                input.thumbnail_url,
                now as _,
                now as _,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)",
                det_item_id,
                input.content_html,
                input.content_type as _,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT INTO entry_state (entry_id, inbox_state, progress, updated_at)
                 VALUES (?, 'unread', 0.0, ?)",
                det_item_id,
                now as _,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('url', ?, ?)",
                canon_url,
                det_item_id,
            )
            .execute(&mut *tx)
            .await?;

            if let Some(ref aid) = arxiv_id {
                sqlx::query!(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('arxiv', ?, ?)",
                    aid,
                    det_item_id,
                )
                .execute(&mut *tx)
                .await?;
            }

            MediaRepo::upsert_audio_enclosure_in_tx(
                &mut tx,
                &det_item_id,
                input.audio_url,
                clean_duration,
                now,
            )
            .await?;

            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Item,
                &det_item_id,
                ActivityAction::Create,
                now,
            )
            .await?;
            det_item_id
        };

        let mut seen_names = HashSet::new();
        let mut attached_tags = Vec::new();
        for tag_name in input.tags {
            let trimmed = tag_name.trim();
            if trimmed.is_empty() || !seen_names.insert(trimmed.to_owned()) {
                continue;
            }
            let tag =
                TagRepo::get_or_create_and_attach_in_tx(&mut tx, &final_item_id, trimmed, now)
                    .await?;
            attached_tags.push(tag);
        }

        let mut qb = QueryBuilder::<Sqlite>::new(ITEM_SELECT_FULL);
        qb.push(" WHERE i.id = ").push_bind(&final_item_id);
        let item: Item = qb.build_query_as().fetch_one(&mut *tx).await?;

        tx.commit().await?;
        Ok((item, attached_tags))
    }

    #[allow(clippy::too_many_lines)]
    pub async fn update(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
        read_at: ReadAtPatch,
    ) -> Result<bool> {
        let now = SyncRepo::now_utc();

        let existing = sqlx::query!(
            "SELECT e.kind AS \"kind: EntryKind\",
                    es.inbox_state AS \"inbox_state: InboxState\",
                    EXISTS (
                        SELECT 1 FROM collection_entries ce_fav
                        WHERE ce_fav.collection_id = ? AND ce_fav.entry_id = e.id
                    ) AS \"is_favorite!: bool\",
                    es.progress,
                    es.read_at AS \"read_at: DateTime<Utc>\"
             FROM entries e
             JOIN entry_state es ON es.entry_id = e.id
             WHERE e.id = ? AND e.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')",
            STARRED_COLLECTION_ID,
            id,
        )
        .fetch_optional(self.pool)
        .await?;

        let Some(existing) = existing else {
            return Ok(false);
        };
        let entry_kind = existing.kind;
        let cur_inbox = existing.inbox_state;
        let current_favorite = existing.is_favorite;
        let current_progress = existing.progress;
        let current_read_at = existing.read_at;

        let current_status = if cur_inbox == InboxState::Dismissed {
            ItemStatus::Archived
        } else {
            ItemStatus::Unread
        };
        let target_status = status.unwrap_or(current_status);
        let target_inbox = InboxState::from(target_status);
        let target_read_at = match target_status {
            ItemStatus::Archived => match read_at {
                ReadAtPatch::MarkRead(explicit_ts) => Some(explicit_ts),
                ReadAtPatch::MarkUnread | ReadAtPatch::Unchanged => current_read_at.or(Some(now)),
            },
            ItemStatus::Unread => match read_at {
                ReadAtPatch::MarkRead(explicit_ts) => Some(explicit_ts),
                ReadAtPatch::MarkUnread => None,
                ReadAtPatch::Unchanged => {
                    if current_status == ItemStatus::Archived {
                        None
                    } else {
                        current_read_at
                    }
                }
            },
        };
        let target_favorite = is_favorite.unwrap_or(current_favorite);
        let target_progress = read_progress.unwrap_or(current_progress);

        let status_changed = target_status != current_status;
        let read_at_changed = target_read_at != current_read_at;
        let fav_changed = target_favorite != current_favorite;
        let progress_changed = (target_progress - current_progress).abs() > f64::EPSILON;

        if !status_changed && !read_at_changed && !fav_changed && !progress_changed {
            return Ok(true);
        }

        let mut tx = self.pool.begin().await?;

        sqlx::query!(
            "UPDATE entry_state
             SET inbox_state = ?,
                 read_at = ?,
                 progress = ?,
                 interaction_updated_at = ?,
                 updated_at = ?
             WHERE entry_id = ?",
            target_inbox as _,
            target_read_at as _,
            target_progress,
            now as _,
            now as _,
            id,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, id)
            .execute(&mut *tx)
            .await?;

        if fav_changed {
            set_favorite_membership_in_tx(&mut tx, id, target_favorite, now).await?;
        }

        maybe_auto_log_feed_completion_in_tx(
            &mut tx,
            id,
            entry_kind,
            target_inbox,
            target_progress,
            target_read_at.unwrap_or(now),
        )
        .await?;

        if status_changed {
            let action = match target_status {
                ItemStatus::Archived => ActivityAction::Archive,
                ItemStatus::Unread => ActivityAction::Unarchive,
            };
            SyncRepo::record_activity(&mut tx, ActivityEntityType::Item, id, action, now).await?;
        }

        if fav_changed {
            let action =
                if target_favorite { ActivityAction::Favorite } else { ActivityAction::Unfavorite };
            SyncRepo::record_activity(&mut tx, ActivityEntityType::Item, id, action, now).await?;
        }

        if (read_at_changed && !status_changed) || progress_changed {
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Item,
                id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        tx.commit().await?;
        Ok(true)
    }

    pub async fn archive(&self, id: &str) -> Result<bool> {
        self.update(id, Some(ItemStatus::Archived), None, None, ReadAtPatch::Unchanged).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, Some(ItemStatus::Unread), None, None, ReadAtPatch::Unchanged).await
    }

    pub(crate) async fn entry_has_catalog_history_in_tx(
        conn: &mut SqliteConnection,
        entry_id: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        let quoted_id_pattern = format!("%\"{entry_id}\"%");
        let preserved: Option<i64> = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\"
             WHERE EXISTS (SELECT 1 FROM consumption_log WHERE entry_id = ?)
                OR EXISTS (SELECT 1 FROM comparisons WHERE ordering_json LIKE ?)
                OR EXISTS (
                    SELECT 1 FROM collection_entries ce
                    JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                    WHERE ce.entry_id = ? AND cs.collection_role IN ('list', 'want')
                )
                OR EXISTS (SELECT 1 FROM collection_generators WHERE current_entry_id = ?)",
            entry_id,
            quoted_id_pattern,
            entry_id,
            entry_id,
        )
        .fetch_optional(&mut *conn)
        .await?;
        Ok(preserved.is_some())
    }

    /// Removes an item from Inbox while preserving its `entries` record when catalog history
    /// (consumption log, comparisons, lists/want queues, or active generator) references it.
    ///
    /// Note: `entry_media` rows with `local_path IS NOT NULL` are intentionally left intact here
    /// so `ItemService::delete` and `MediaRepo::find_orphaned` (`es.inbox_state = 'none'`) can
    /// unlink the physical file from disk before removing the `entry_media` row.
    pub(crate) async fn delete_inbox_item_preserving_history_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        if Self::entry_has_catalog_history_in_tx(&mut *conn, item_id).await? {
            let res =
                sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, item_id)
                    .execute(&mut *conn)
                    .await?;
            if res.rows_affected() == 0 {
                return Ok(false);
            }
            sqlx::query!(
                "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                 VALUES (?, 'none', NULL, 0.0, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     inbox_state = 'none',
                     read_at = NULL,
                     interaction_updated_at = excluded.interaction_updated_at,
                     updated_at = excluded.updated_at",
                item_id,
                now as _,
                now as _,
            )
            .execute(&mut *conn)
            .await?;
            sqlx::query!("DELETE FROM entry_content WHERE entry_id = ?", item_id)
                .execute(&mut *conn)
                .await?;
            sqlx::query!(
                "DELETE FROM collection_entries
                 WHERE entry_id = ?
                   AND (
                       collection_id IN (?, ?)
                       OR collection_id IN (
                           SELECT collection_id FROM collection_specs WHERE collection_role = 'tag'
                       )
                   )",
                item_id,
                STARRED_COLLECTION_ID,
                UP_NEXT_COLLECTION_ID,
            )
            .execute(&mut *conn)
            .await?;
            Ok(true)
        } else {
            let res = sqlx::query!("DELETE FROM entries WHERE id = ?", item_id)
                .execute(&mut *conn)
                .await?;
            Ok(res.rows_affected() > 0)
        }
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let existing_keys = sqlx::query!(
            "SELECT e.url,
                    (SELECT fs.feed_url
                     FROM collection_entries ce
                     JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                     WHERE ce.entry_id = e.id
                     ORDER BY ce.added_at ASC, ce.collection_id ASC
                     LIMIT 1) AS \"feed_url?: String\"
             FROM entries e
             JOIN entry_state es ON es.entry_id = e.id
             WHERE e.id = ? AND es.inbox_state IN ('unread', 'dismissed')",
            id,
        )
        .fetch_optional(self.pool)
        .await?;
        let Some(existing_keys) = existing_keys else {
            return Ok(false);
        };
        let item_url = existing_keys.url.unwrap_or_default();
        let feed_url = existing_keys.feed_url;

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        if !Self::delete_inbox_item_preserving_history_in_tx(&mut tx, id, now).await? {
            return Ok(false);
        }
        if !item_url.is_empty() {
            SyncRepo::record_tombstone(
                &mut tx,
                ActivityEntityType::Item,
                &item_natural_key(feed_url.as_deref(), &item_url),
                now,
            )
            .await?;
        }
        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Item,
            id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn backfill_metadata(
        &self,
        id: &str,
        title: Option<&str>,
        author: Option<&str>,
        summary: Option<&str>,
    ) -> Result<()> {
        let clean_title = title.map(str::trim).filter(|s| !s.is_empty());
        let clean_author = author.map(str::trim).filter(|s| !s.is_empty());
        let clean_summary = summary.map(str::trim).filter(|s| !s.is_empty());
        if clean_title.is_none() && clean_author.is_none() && clean_summary.is_none() {
            return Ok(());
        }

        let new_creators_json = clean_author.and_then(|a| serde_json::to_string(&[a]).ok());
        let creators_ref = new_creators_json.as_deref();
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let result = sqlx::query!(
            "UPDATE entries
             SET title = CASE WHEN title = url AND ? IS NOT NULL THEN ? ELSE title END,
                 creators_json = CASE WHEN creators_json = '[]' AND ? IS NOT NULL THEN ? ELSE creators_json END,
                 summary = COALESCE(summary, ?),
                 updated_at = ?
             WHERE id = ?
               AND ((title = url AND ? IS NOT NULL)
                    OR (creators_json = '[]' AND ? IS NOT NULL)
                    OR (summary IS NULL AND ? IS NOT NULL))",
            clean_title as _,
            clean_title as _,
            creators_ref as _,
            creators_ref as _,
            clean_summary as _,
            now as _,
            id,
            clean_title as _,
            creators_ref as _,
            clean_summary as _,
        )
        .execute(&mut *tx)
        .await?;

        if result.rows_affected() > 0 {
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Item,
                id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }
        tx.commit().await?;
        Ok(())
    }
}
