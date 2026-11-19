use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};
#[cfg(test)]
use url::Url;

use super::{
    IS_LIVE_ITEM_SQL, Item, ItemContentProjection, ItemRepo, ManualItemInput,
    item_select_query_builder,
};
#[cfg(test)]
use super::{NewItem, TagNames};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ItemDeleteOutcome {
    pub deleted: bool,
    pub readable_content_removed: bool,
}
use crate::{
    db::{
        ActivityAction, ActivityEntityType, CatalogError, CatalogRepo, EntryKind, InboxState,
        ItemStatus, MediaRepo, ReadAtPatch, SyncRepo, Tag, TagRepo,
        feeds::resolve_dedup_entry_id,
        sync::{
            STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID, WANT_TO_READ_COLLECTION_ID,
            canonicalize_sync_url, deterministic_item_id, item_natural_key,
        },
    },
    feeds::arxiv::ArxivParser,
};

/// Auto-logs feed / reader completion (`consumed`) when:
/// - a `podcast_episode` reaches audio playback completion (`progress >= 0.98`, or archived/finished with `>= 0.9`), or
/// - an `article` or `paper` is archived/finished with `progress >= 0.9`.
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
    let is_dismissed_or_done = matches!(inbox_state, InboxState::Dismissed | InboxState::None);
    let should_log = match entry_kind {
        EntryKind::PodcastEpisode => progress >= 0.98 || (is_dismissed_or_done && progress >= 0.9),
        EntryKind::Article | EntryKind::Paper => is_dismissed_or_done && progress >= 0.9,
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

pub(super) async fn set_collection_flag_membership_in_tx(
    conn: &mut SqliteConnection,
    collection_id: &str,
    entry_id: &str,
    present: bool,
    now: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let nat_key = crate::db::sync::collection_entry_natural_key(collection_id, entry_id);
    if present {
        SyncRepo::clear_tombstone(&mut *conn, ActivityEntityType::CollectionEntry, &nat_key)
            .await?;
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
             VALUES (?, ?, ?)",
        )
        .bind(collection_id)
        .bind(entry_id)
        .bind(now)
        .execute(&mut *conn)
        .await?;
    } else {
        let res =
            sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
                .bind(collection_id)
                .bind(entry_id)
                .execute(&mut *conn)
                .await?;
        if res.rows_affected() > 0 {
            SyncRepo::record_tombstone(
                &mut *conn,
                ActivityEntityType::CollectionEntry,
                &nat_key,
                now,
            )
            .await?;
        }
    }
    Ok(())
}

pub(super) async fn set_favorite_membership_in_tx(
    conn: &mut SqliteConnection,
    entry_id: &str,
    is_favorite: bool,
    now: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    set_collection_flag_membership_in_tx(conn, STARRED_COLLECTION_ID, entry_id, is_favorite, now)
        .await
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

        let mut tx = crate::db::begin_write(self.pool).await?;
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
            inbox_state: InboxState::Unread,
            in_want_to_read: false,
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
        let mut tx = crate::db::begin_write(self.pool).await?;

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
            let has_feed_source: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries ce
                 JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                 WHERE ce.entry_id = ? LIMIT 1",
            )
            .bind(&existing_id)
            .fetch_optional(&mut *tx)
            .await?;

            let has_explicit_non_url_title = !trimmed_title.is_empty() && trimmed_title != url_str;
            sqlx::query(
                "UPDATE entries
                 SET title = CASE
                         WHEN ? = ? AND ? THEN ?
                         WHEN title = url AND ? != '' THEN ?
                         ELSE title
                     END,
                     summary = COALESCE(summary, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(&existing_id)
            .bind(&det_item_id)
            .bind(has_explicit_non_url_title)
            .bind(input.title)
            .bind(trimmed_title)
            .bind(input.title)
            .bind(input.summary)
            .bind(input.thumbnail_url)
            .bind(now)
            .bind(&existing_id)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = COALESCE(NULLIF(excluded.content_html, ''), entry_content.content_html)",
            )
            .bind(&existing_id)
            .bind(input.content_html)
            .bind(input.content_type)
            .execute(&mut *tx)
            .await?;

            if has_feed_source.is_some() {
                sqlx::query(
                    "INSERT INTO entry_state (entry_id, inbox_state, progress, interaction_updated_at, updated_at)
                     VALUES (?, 'unread', 0.0, ?, ?)
                     ON CONFLICT(entry_id) DO UPDATE SET
                         read_at = NULL,
                         interaction_updated_at = excluded.interaction_updated_at,
                         updated_at = excluded.updated_at",
                )
                .bind(&existing_id)
                .bind(now)
                .bind(now)
                .execute(&mut *tx)
                .await?;
            } else {
                sqlx::query(
                    "INSERT INTO entry_state (entry_id, inbox_state, progress, interaction_updated_at, updated_at)
                     VALUES (?, 'none', 0.0, ?, ?)
                     ON CONFLICT(entry_id) DO UPDATE SET
                         inbox_state = 'none',
                         read_at = NULL,
                         interaction_updated_at = excluded.interaction_updated_at,
                         updated_at = excluded.updated_at",
                )
                .bind(&existing_id)
                .bind(now)
                .bind(now)
                .execute(&mut *tx)
                .await?;
            }

            set_collection_flag_membership_in_tx(
                &mut tx,
                WANT_TO_READ_COLLECTION_ID,
                &existing_id,
                true,
                now,
            )
            .await?;

            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('url', ?, ?)",
            )
            .bind(&canon_url)
            .bind(&existing_id)
            .execute(&mut *tx)
            .await?;

            if let Some(ref aid) = arxiv_id {
                sqlx::query(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('arxiv', ?, ?)",
                )
                .bind(aid)
                .bind(&existing_id)
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
            sqlx::query(
                "INSERT INTO entries
                 (id, kind, title, creators_json, url, published_at, summary, thumbnail_url, created_at, updated_at)
                 VALUES (?, ?, ?, '[]', ?, NULL, ?, ?, ?, ?)",
            )
            .bind(&det_item_id)
            .bind(entry_kind)
            .bind(input.title)
            .bind(url_str)
            .bind(input.summary)
            .bind(input.thumbnail_url)
            .bind(now)
            .bind(now)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)",
            )
            .bind(&det_item_id)
            .bind(input.content_html)
            .bind(input.content_type)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO entry_state (entry_id, inbox_state, progress, updated_at)
                 VALUES (?, 'none', 0.0, ?)",
            )
            .bind(&det_item_id)
            .bind(now)
            .execute(&mut *tx)
            .await?;

            set_collection_flag_membership_in_tx(
                &mut tx,
                WANT_TO_READ_COLLECTION_ID,
                &det_item_id,
                true,
                now,
            )
            .await?;

            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('url', ?, ?)",
            )
            .bind(&canon_url)
            .bind(&det_item_id)
            .execute(&mut *tx)
            .await?;

            if let Some(ref aid) = arxiv_id {
                sqlx::query(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('arxiv', ?, ?)",
                )
                .bind(aid)
                .bind(&det_item_id)
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

        let mut qb = item_select_query_builder(ItemContentProjection::Full);
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
        type ExistingItemStateTuple =
            (EntryKind, InboxState, bool, bool, f64, Option<DateTime<Utc>>);
        let now = SyncRepo::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;

        let mut check_qb = QueryBuilder::<Sqlite>::new(
            "SELECT i.kind,
                    COALESCE(es.inbox_state, 'none') AS inbox_state,
                    EXISTS (
                        SELECT 1 FROM collection_entries ce_fav
                        WHERE ce_fav.collection_id = ",
        );
        check_qb
            .push_bind(STARRED_COLLECTION_ID)
            .push(
                " AND ce_fav.entry_id = i.id
                    ) AS is_favorite,
                    EXISTS (
                        SELECT 1 FROM collection_entries ce_wtr
                        WHERE ce_wtr.collection_id = ",
            )
            .push_bind(WANT_TO_READ_COLLECTION_ID)
            .push(
                " AND ce_wtr.entry_id = i.id
                    ) AS in_want_to_read,
                    COALESCE(es.progress, 0.0) AS progress,
                    es.read_at
             FROM entries i
             LEFT JOIN entry_state es ON es.entry_id = i.id
             LEFT JOIN entry_content ec ON ec.entry_id = i.id
             WHERE i.id = ",
            )
            .push_bind(id)
            .push(" AND i.merged_into_id IS NULL AND ")
            .push(IS_LIVE_ITEM_SQL);

        let existing: Option<ExistingItemStateTuple> =
            check_qb.build_query_as().fetch_optional(&mut *tx).await?;

        let Some((
            entry_kind,
            cur_inbox,
            current_favorite,
            current_in_wtr,
            current_progress,
            current_read_at,
        )) = existing
        else {
            return Ok(false);
        };

        let current_status = if cur_inbox == InboxState::Dismissed
            || (cur_inbox == InboxState::None && current_read_at.is_some() && !current_in_wtr)
        {
            ItemStatus::Archived
        } else {
            ItemStatus::Unread
        };
        let target_status = status.unwrap_or(current_status);
        let target_inbox = if cur_inbox == InboxState::None {
            InboxState::None
        } else {
            InboxState::from(target_status)
        };
        let target_in_wtr = if cur_inbox == InboxState::None {
            match status {
                Some(ItemStatus::Archived) => false,
                Some(ItemStatus::Unread) => true,
                None => current_in_wtr,
            }
        } else {
            current_in_wtr
        };
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
        let wtr_changed = target_in_wtr != current_in_wtr;
        let read_at_changed = target_read_at != current_read_at;
        let fav_changed = target_favorite != current_favorite;
        let progress_changed = (target_progress - current_progress).abs() > f64::EPSILON;

        if !status_changed && !wtr_changed && !read_at_changed && !fav_changed && !progress_changed
        {
            return Ok(true);
        }

        sqlx::query(
            "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(entry_id) DO UPDATE SET
                 inbox_state = excluded.inbox_state,
                 read_at = excluded.read_at,
                 progress = excluded.progress,
                 interaction_updated_at = excluded.interaction_updated_at,
                 updated_at = excluded.updated_at",
        )
        .bind(id)
        .bind(target_inbox)
        .bind(target_read_at)
        .bind(target_progress)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
            .bind(now)
            .bind(id)
            .execute(&mut *tx)
            .await?;

        if fav_changed {
            set_favorite_membership_in_tx(&mut tx, id, target_favorite, now).await?;
        }
        if wtr_changed {
            set_collection_flag_membership_in_tx(
                &mut tx,
                WANT_TO_READ_COLLECTION_ID,
                id,
                target_in_wtr,
                now,
            )
            .await?;
        }

        let completion_signal = if target_status == ItemStatus::Archived {
            InboxState::Dismissed
        } else {
            InboxState::Unread
        };
        maybe_auto_log_feed_completion_in_tx(
            &mut tx,
            id,
            entry_kind,
            completion_signal,
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
        let non_comp_preserved: Option<i64> = sqlx::query_scalar(
            "SELECT 1
             WHERE EXISTS (SELECT 1 FROM consumption_log WHERE entry_id = ?)
                OR EXISTS (
                    SELECT 1 FROM collection_entries ce
                    JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                    WHERE ce.entry_id = ? AND cs.collection_role IN ('list', 'want')
                )
                OR EXISTS (SELECT 1 FROM collection_generators WHERE current_entry_id = ?)",
        )
        .bind(entry_id)
        .bind(entry_id)
        .bind(entry_id)
        .fetch_optional(&mut *conn)
        .await?;
        if non_comp_preserved.is_some() {
            return Ok(true);
        }

        let quoted_id_pattern = crate::db::json_string_like_pattern(entry_id);
        let candidate_orderings: Vec<String> = sqlx::query_scalar(
            "SELECT ordering_json FROM comparisons WHERE ordering_json LIKE ? ESCAPE '\\'",
        )
        .bind(&quoted_id_pattern)
        .fetch_all(&mut *conn)
        .await?;
        Ok(candidate_orderings.iter().any(|raw| {
            serde_json::from_str::<Vec<Vec<String>>>(raw)
                .is_ok_and(|groups| groups.iter().flatten().any(|id| id == entry_id))
        }))
    }

    /// Removes an item from Inbox (or deletes a saved item) while preserving its `entries` record
    /// when catalog history (consumption log, comparisons, lists/want queues, or active generator)
    /// references it.
    #[allow(clippy::too_many_lines)]
    pub(crate) async fn delete_inbox_item_preserving_history_outcome_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        detach_want_queues: bool,
        now: DateTime<Utc>,
    ) -> std::result::Result<ItemDeleteOutcome, sqlx::Error> {
        if detach_want_queues {
            let detached_item_cols: Vec<String> = sqlx::query_scalar(
                "SELECT ce.collection_id
                 FROM collection_entries ce
                 JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                 WHERE ce.entry_id = ?
                   AND (cs.collection_role = 'want' OR ce.collection_id = ?)",
            )
            .bind(item_id)
            .bind(STARRED_COLLECTION_ID)
            .fetch_all(&mut *conn)
            .await?;
            sqlx::query(
                "DELETE FROM collection_entries
                 WHERE entry_id = ?
                   AND (
                       collection_id = ?
                       OR collection_id IN (
                           SELECT collection_id FROM collection_specs WHERE collection_role = 'want'
                       )
                   )",
            )
            .bind(item_id)
            .bind(STARRED_COLLECTION_ID)
            .execute(&mut *conn)
            .await?;
            for col_id in detached_item_cols {
                let nat_key = crate::db::sync::collection_entry_natural_key(&col_id, item_id);
                SyncRepo::record_tombstone(
                    &mut *conn,
                    ActivityEntityType::CollectionEntry,
                    &nat_key,
                    now,
                )
                .await?;
            }
        }

        if Self::entry_has_catalog_history_in_tx(&mut *conn, item_id).await? {
            let res = sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            if res.rows_affected() == 0 {
                return Ok(ItemDeleteOutcome { deleted: false, readable_content_removed: false });
            }
            sqlx::query(
                "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                 VALUES (?, 'none', NULL, 0.0, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     inbox_state = 'none',
                     read_at = NULL,
                     interaction_updated_at = excluded.interaction_updated_at,
                     updated_at = excluded.updated_at",
            )
            .bind(item_id)
            .bind(now)
            .bind(now)
            .execute(&mut *conn)
            .await?;

            let keep_readable_content: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries ce
                 JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                 WHERE ce.entry_id = ? AND cs.collection_role IN ('list', 'want')
                 LIMIT 1",
            )
            .bind(item_id)
            .fetch_optional(&mut *conn)
            .await?;

            if keep_readable_content.is_none() {
                sqlx::query("DELETE FROM entry_content WHERE entry_id = ?")
                    .bind(item_id)
                    .execute(&mut *conn)
                    .await?;
            }

            let detached_cols: Vec<String> = sqlx::query_scalar(
                "SELECT collection_id FROM collection_entries
                 WHERE entry_id = ?
                   AND (
                       collection_id = ?
                       OR (? AND collection_id IN (
                           SELECT collection_id FROM collection_specs WHERE collection_role = 'tag'
                       ))
                   )",
            )
            .bind(item_id)
            .bind(UP_NEXT_COLLECTION_ID)
            .bind(keep_readable_content.is_none())
            .fetch_all(&mut *conn)
            .await?;
            sqlx::query(
                "DELETE FROM collection_entries
                 WHERE entry_id = ?
                   AND (
                       collection_id = ?
                       OR (? AND collection_id IN (
                           SELECT collection_id FROM collection_specs WHERE collection_role = 'tag'
                       ))
                   )",
            )
            .bind(item_id)
            .bind(UP_NEXT_COLLECTION_ID)
            .bind(keep_readable_content.is_none())
            .execute(&mut *conn)
            .await?;
            for col_id in detached_cols {
                let nat_key = crate::db::sync::collection_entry_natural_key(&col_id, item_id);
                SyncRepo::record_tombstone(
                    &mut *conn,
                    ActivityEntityType::CollectionEntry,
                    &nat_key,
                    now,
                )
                .await?;
            }
            Ok(ItemDeleteOutcome {
                deleted: true,
                readable_content_removed: keep_readable_content.is_none(),
            })
        } else {
            let res = sqlx::query("DELETE FROM entries WHERE id = ?")
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            let deleted = res.rows_affected() > 0;
            Ok(ItemDeleteOutcome { deleted, readable_content_removed: deleted })
        }
    }

    pub(crate) async fn delete_inbox_item_preserving_history_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        detach_want_queues: bool,
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        Ok(Self::delete_inbox_item_preserving_history_outcome_in_tx(
            conn,
            item_id,
            detach_want_queues,
            now,
        )
        .await?
        .deleted)
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        Ok(self.delete_returning_outcome(id).await?.deleted)
    }

    pub(crate) async fn delete_returning_outcome(&self, id: &str) -> Result<ItemDeleteOutcome> {
        let now = SyncRepo::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;

        let mut key_qb = QueryBuilder::<Sqlite>::new(
            "SELECT i.url,
                    (SELECT fs.feed_url
                     FROM collection_entries ce
                     JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                     WHERE ce.entry_id = i.id
                     ORDER BY ce.added_at ASC, ce.collection_id ASC
                     LIMIT 1) AS feed_url
             FROM entries i
             LEFT JOIN entry_state es ON es.entry_id = i.id
             LEFT JOIN entry_content ec ON ec.entry_id = i.id
             WHERE i.id = ",
        );
        key_qb.push_bind(id).push(" AND i.merged_into_id IS NULL AND ").push(IS_LIVE_ITEM_SQL);
        let existing_keys: Option<(Option<String>, Option<String>)> =
            key_qb.build_query_as().fetch_optional(&mut *tx).await?;
        let Some((item_url_opt, feed_url)) = existing_keys else {
            return Ok(ItemDeleteOutcome { deleted: false, readable_content_removed: false });
        };
        let item_url = item_url_opt.unwrap_or_default();

        let outcome =
            Self::delete_inbox_item_preserving_history_outcome_in_tx(&mut tx, id, true, now)
                .await?;
        if !outcome.deleted {
            return Ok(outcome);
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
        Ok(outcome)
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn apply_background_enrichment(
        &self,
        id: &str,
        title: Option<&str>,
        author: Option<&str>,
        published_at: Option<DateTime<Utc>>,
        summary: Option<&str>,
        thumbnail_url: Option<&str>,
        content_html: Option<&str>,
    ) -> Result<()> {
        let clean_title = title.map(str::trim).filter(|s| !s.is_empty());
        let clean_author = author.map(str::trim).filter(|s| !s.is_empty());
        let clean_summary = summary.map(str::trim).filter(|s| !s.is_empty());
        let clean_thumb = thumbnail_url.map(str::trim).filter(|s| !s.is_empty());
        let clean_html = content_html.filter(|s| !s.trim().is_empty());
        if clean_title.is_none()
            && clean_author.is_none()
            && published_at.is_none()
            && clean_summary.is_none()
            && clean_thumb.is_none()
            && clean_html.is_none()
        {
            return Ok(());
        }

        let new_creators_json = clean_author.and_then(|a| serde_json::to_string(&[a]).ok());
        let creators_ref = new_creators_json.as_deref();
        let now = SyncRepo::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;

        let live: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM entries e
             JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.id = ? AND e.merged_into_id IS NULL",
        )
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        if live.is_none() {
            return Ok(());
        }

        let entry_res = sqlx::query(
            "UPDATE entries
             SET title = CASE WHEN title = url AND ? IS NOT NULL THEN ? ELSE title END,
                 creators_json = CASE WHEN creators_json = '[]' AND ? IS NOT NULL THEN ? ELSE creators_json END,
                 published_at = COALESCE(published_at, ?),
                 summary = COALESCE(summary, ?),
                 thumbnail_url = COALESCE(thumbnail_url, ?),
                 updated_at = ?
             WHERE id = ?
               AND ((title = url AND ? IS NOT NULL)
                    OR (creators_json = '[]' AND ? IS NOT NULL)
                    OR (published_at IS NULL AND ? IS NOT NULL)
                    OR (summary IS NULL AND ? IS NOT NULL)
                    OR (thumbnail_url IS NULL AND ? IS NOT NULL))",
        )
        .bind(clean_title)
        .bind(clean_title)
        .bind(creators_ref)
        .bind(creators_ref)
        .bind(published_at)
        .bind(clean_summary)
        .bind(clean_thumb)
        .bind(now)
        .bind(id)
        .bind(clean_title)
        .bind(creators_ref)
        .bind(published_at)
        .bind(clean_summary)
        .bind(clean_thumb)
        .execute(&mut *tx)
        .await?;

        let mut content_updated = false;
        if let Some(html) = clean_html {
            let content_res = sqlx::query(
                "UPDATE entry_content
                 SET content_html = ?
                 WHERE entry_id = ? AND (content_html IS NULL OR content_html = '')",
            )
            .bind(html)
            .bind(id)
            .execute(&mut *tx)
            .await?;
            if content_res.rows_affected() > 0 {
                content_updated = true;
                sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                    .bind(now)
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
            }
        }

        if entry_res.rows_affected() > 0 || content_updated {
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
        let mut tx = crate::db::begin_write(self.pool).await?;
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
