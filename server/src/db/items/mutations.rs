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

#[derive(sqlx::FromRow)]
struct ExistingItemStateRow {
    kind: EntryKind,
    content_type: ContentType,
    has_audio_media: bool,
    inbox_state: InboxState,
    is_favorite: bool,
    in_want_to_read: bool,
    progress: f64,
    read_at: Option<DateTime<Utc>>,
}

struct EntryMetadataBackfill<'a> {
    title: Option<&'a str>,
    creators_json: Option<String>,
    published_at: Option<DateTime<Utc>>,
    summary: Option<&'a str>,
    thumbnail_url: Option<&'a str>,
}

impl EntryMetadataBackfill<'_> {
    const fn is_empty(&self) -> bool {
        self.title.is_none()
            && self.creators_json.is_none()
            && self.published_at.is_none()
            && self.summary.is_none()
            && self.thumbnail_url.is_none()
    }
}
#[cfg(test)]
use crate::domain::FeedType;
use crate::{
    db::{
        ActivityAction, ActivityEntityType, CatalogError, CatalogRepo, CatalogResult,
        ConsumptionLogView, ConsumptionStatus, ContentType, EntryKind, InboxState, ItemStatus,
        MediaRepo, ReadAtPatch, SyncRepo, Tag, TagRepo, WriteTx,
        entries_core::{self, EntryInsertParams, EntryStateUpsert},
        feeds::resolve_dedup_entry_id,
        sync::{
            STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID, WANT_TO_READ_COLLECTION_ID,
            canonicalize_sync_url, deterministic_item_id, item_natural_key,
        },
    },
    domain::{ProgressSource, READER_AUTO_READ_THRESHOLD},
    util::{clock::now_utc, media_urls::extract_arxiv_id},
};

/// Returns whether reaching `progress` from `progress_source` should automatically mark
/// the entry **Finished** (logging `'consumed'` and clearing it from New / Want queues).
///
/// Rules:
/// - Must reach `progress >= READER_AUTO_READ_THRESHOLD` (`0.98`).
/// - **Videos** NEVER auto-finish (explicit Finish only).
/// - **Podcast episodes** and audio-only items auto-finish on audio playback (`Audio` or `None`),
///   never from scrolling show notes (`Reader`).
/// - **Articles / Papers** auto-finish on reader scroll (`Reader` / `None`) or audio playback (`Audio` when playable).
/// - **Dismiss** never calls this — dismissing an item never logs `'consumed'`.
pub(super) fn should_auto_finish_from_progress(
    entry_kind: EntryKind,
    content_type: ContentType,
    has_audio_media: bool,
    progress: f64,
    progress_source: Option<ProgressSource>,
) -> bool {
    if !progress.is_finite() || progress < READER_AUTO_READ_THRESHOLD {
        return false;
    }
    if entry_kind == EntryKind::Video || content_type == ContentType::Video {
        return false;
    }
    let is_audio_only = content_type == ContentType::Audio;
    let has_audio = is_audio_only || has_audio_media;
    match entry_kind {
        EntryKind::PodcastEpisode => {
            has_audio && !matches!(progress_source, Some(ProgressSource::Reader))
        }
        EntryKind::Article | EntryKind::Paper => match progress_source {
            Some(ProgressSource::Audio) => has_audio,
            Some(ProgressSource::Reader) => !is_audio_only,
            None => !is_audio_only || has_audio,
        },
        _ => false,
    }
}

async fn ensure_auto_finished_in_tx(
    tx: &mut WriteTx<'_>,
    entry_id: &str,
    ts: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    match CatalogRepo::ensure_auto_consumed_with_parents_in_tx(tx, entry_id, None, ts, ts).await {
        Ok(_) => Ok(()),
        Err(CatalogError::Db(e)) => Err(e),
        Err(other) => Err(sqlx::Error::Protocol(other.to_string())),
    }
}

pub async fn set_collection_flag_membership_in_tx(
    tx: &mut WriteTx<'_>,
    collection_id: &str,
    entry_id: &str,
    present: bool,
    now: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let nat_key = crate::db::sync::collection_entry_natural_key(collection_id, entry_id);
    if present {
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::CollectionEntry, &nat_key).await?;
        let ce_hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at, hlc)
             VALUES (?, ?, ?, ?)",
        )
        .bind(collection_id)
        .bind(entry_id)
        .bind(now)
        .bind(&ce_hlc)
        .execute(tx.conn())
        .await?;
    } else {
        let res =
            sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
                .bind(collection_id)
                .bind(entry_id)
                .execute(tx.conn())
                .await?;
        if res.rows_affected() > 0 {
            SyncRepo::record_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key, now)
                .await?;
        }
    }
    Ok(())
}

pub async fn set_favorite_membership_in_tx(
    tx: &mut WriteTx<'_>,
    entry_id: &str,
    is_favorite: bool,
    now: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    set_collection_flag_membership_in_tx(tx, STARRED_COLLECTION_ID, entry_id, is_favorite, now)
        .await
}

impl ItemRepo<'_> {
    #[cfg(test)]
    pub async fn create(&self, new_item: &NewItem<'_>) -> Result<Item> {
        let feed_meta: Option<(String, FeedType)> = match new_item.feed_id {
            Some(fid) => {
                sqlx::query_as(
                    "SELECT feed_url, source_type FROM feed_sources WHERE collection_id = ?",
                )
                .bind(fid)
                .fetch_optional(self.pool)
                .await?
            }
            None => None,
        };
        let feed_url = feed_meta.as_ref().map(|(u, _)| u.clone());
        let feed_type = feed_meta.as_ref().map(|(_, ft)| *ft);
        let url_str = new_item.url.as_str();
        let id = deterministic_item_id(feed_url.as_deref(), url_str);
        let canon_url = canonicalize_sync_url(url_str);
        let now = now_utc();
        let entry_kind = EntryKind::for_feed_item(feed_type, new_item.content_type);
        let creators_json = new_item
            .author
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|a| serde_json::to_string(&[a]).ok())
            .unwrap_or_else(|| "[]".to_owned());

        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
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
            entry_kind,
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
            is_finished: false,
            read_at: None,
            read_progress: 0.0,
            interaction_updated_at: None,
            thumbnail_media_id: None,
            duration: None,
            tag_names: TagNames::default(),
            discussions: Vec::new(),
        })
    }

    #[cfg(test)]
    async fn insert_created_item_rows_in_tx(
        tx: &mut WriteTx<'_>,
        id: &str,
        new_item: &NewItem<'_>,
        entry_kind: EntryKind,
        creators_json: &str,
        canon_url: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        entries_core::insert_entry_row_in_tx(
            tx,
            &EntryInsertParams {
                creators_json,
                url: Some(new_item.url.as_str()),
                published_at: new_item.published_at,
                summary: new_item.summary,
                ..EntryInsertParams::minimal(id, entry_kind, new_item.title, now, now)
            },
        )
        .await?;

        entries_core::insert_entry_content_in_tx(tx.conn(), id, None, new_item.content_type)
            .await?;
        entries_core::ensure_entry_state_in_tx(tx, id, InboxState::Unread, now).await?;
        entries_core::upsert_entry_identifier_in_tx(tx.conn(), id, "url", canon_url).await?;

        if let Some(fid) = new_item.feed_id {
            let ce_hlc = tx.next_hlc().await?;
            sqlx::query(
                "INSERT INTO collection_entries (collection_id, entry_id, added_at, hlc)
                 VALUES (?, ?, ?, ?)",
            )
            .bind(fid)
            .bind(id)
            .bind(now)
            .bind(&ce_hlc)
            .execute(tx.conn())
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

    pub async fn save_manual_input_with_tags(
        &self,
        input: &ManualItemInput<'_>,
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        let url_str = input.url.as_str();
        let trimmed_title = input.title.trim();
        let det_item_id = deterministic_item_id(None, url_str);
        let canon_url = canonicalize_sync_url(url_str);
        let arxiv_id = extract_arxiv_id(url_str);
        let now = now_utc();
        let clean_duration = input.duration.filter(|&d| d > 0);
        let entry_kind = EntryKind::for_feed_item(None, input.content_type);
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        SyncRepo::clear_tombstone(
            tx.conn(),
            ActivityEntityType::Item,
            &item_natural_key(None, url_str),
        )
        .await?;

        // Upfront deduplication: if an entry already owns this deterministic ID, canonical URL,
        // or arXiv ID (whether unread, archived, or catalog-only), idempotently update/unarchive
        // it rather than attempting a duplicate `INSERT INTO entries`.
        let existing_dedup =
            resolve_dedup_entry_id(tx.conn(), &det_item_id, &canon_url, arxiv_id.as_deref())
                .await?;

        let (final_item_id, activity_action) = if let Some(existing_id) = existing_dedup {
            Self::update_existing_manual_item_in_tx(
                &mut tx,
                &existing_id,
                &det_item_id,
                trimmed_title,
                input,
                now,
            )
            .await?;
            (existing_id, ActivityAction::Update)
        } else {
            Self::insert_new_manual_item_in_tx(&mut tx, &det_item_id, entry_kind, input, now)
                .await?;
            (det_item_id, ActivityAction::Create)
        };

        set_collection_flag_membership_in_tx(
            &mut tx,
            WANT_TO_READ_COLLECTION_ID,
            &final_item_id,
            true,
            now,
        )
        .await?;

        entries_core::upsert_url_and_arxiv_identifiers_in_tx(
            tx.conn(),
            &final_item_id,
            &canon_url,
            arxiv_id.as_deref(),
        )
        .await?;

        MediaRepo::upsert_audio_enclosure_in_tx(
            tx.conn(),
            &final_item_id,
            input.audio_url,
            clean_duration,
            now,
        )
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Item,
            &final_item_id,
            activity_action,
            now,
        )
        .await?;

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
        let item: Item = qb.build_query_as().fetch_one(tx.conn()).await?;

        tx.commit().await?;
        Ok((item, attached_tags))
    }

    async fn insert_new_manual_item_in_tx(
        tx: &mut WriteTx<'_>,
        det_item_id: &str,
        entry_kind: EntryKind,
        input: &ManualItemInput<'_>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        entries_core::insert_entry_row_in_tx(
            tx,
            &EntryInsertParams {
                url: Some(input.url.as_str()),
                summary: input.summary,
                thumbnail_url: input.thumbnail_url,
                ..EntryInsertParams::minimal(det_item_id, entry_kind, input.title, now, now)
            },
        )
        .await?;
        entries_core::insert_entry_content_in_tx(
            tx.conn(),
            det_item_id,
            input.content_html,
            input.content_type,
        )
        .await?;
        entries_core::ensure_entry_state_in_tx(tx, det_item_id, InboxState::None, now).await
    }

    async fn update_existing_manual_item_in_tx(
        tx: &mut WriteTx<'_>,
        existing_id: &str,
        det_item_id: &str,
        trimmed_title: &str,
        input: &ManualItemInput<'_>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let has_feed_source: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM collection_entries ce
             JOIN feed_sources fs ON fs.collection_id = ce.collection_id
             WHERE ce.entry_id = ? LIMIT 1",
        )
        .bind(existing_id)
        .fetch_optional(tx.conn())
        .await?;

        let url_str = input.url.as_str();
        let has_explicit_non_url_title = !trimmed_title.is_empty() && trimmed_title != url_str;
        let entry_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE entries
             SET title = CASE
                     WHEN ? = ? AND ? THEN ?
                     WHEN title = url AND ? != '' THEN ?
                     ELSE title
                 END,
                 summary = COALESCE(summary, ?),
                 thumbnail_url = COALESCE(thumbnail_url, ?),
                 updated_at = ?,
                 hlc = CASE
                     WHEN (? = ? AND ? AND title IS NOT ?)
                       OR (title = url AND ? != '' AND title IS NOT ?)
                       OR (summary IS NULL AND ? IS NOT NULL)
                       OR (thumbnail_url IS NULL AND ? IS NOT NULL)
                     THEN ?
                     ELSE hlc
                 END
             WHERE id = ?",
        )
        .bind(existing_id)
        .bind(det_item_id)
        .bind(has_explicit_non_url_title)
        .bind(input.title)
        .bind(trimmed_title)
        .bind(input.title)
        .bind(input.summary)
        .bind(input.thumbnail_url)
        .bind(now)
        .bind(existing_id)
        .bind(det_item_id)
        .bind(has_explicit_non_url_title)
        .bind(input.title)
        .bind(trimmed_title)
        .bind(input.title)
        .bind(input.summary)
        .bind(input.thumbnail_url)
        .bind(&entry_hlc)
        .bind(existing_id)
        .execute(tx.conn())
        .await?;

        sqlx::query(
            "INSERT INTO entry_content (entry_id, content_html, content_type)
             VALUES (?, ?, ?)
             ON CONFLICT(entry_id) DO UPDATE SET
                 content_html = COALESCE(NULLIF(excluded.content_html, ''), entry_content.content_html)",
        )
        .bind(existing_id)
        .bind(input.content_html)
        .bind(input.content_type)
        .execute(tx.conn())
        .await?;

        let state_hlc = tx.next_hlc().await?;
        let state_sql = if has_feed_source.is_some() {
            "INSERT INTO entry_state (entry_id, inbox_state, progress, interaction_updated_at, updated_at, hlc)
             VALUES (?, 'unread', 0.0, ?, ?, ?)
             ON CONFLICT(entry_id) DO UPDATE SET
                 read_at = NULL,
                 interaction_updated_at = excluded.interaction_updated_at,
                 updated_at = excluded.updated_at,
                 hlc = excluded.hlc"
        } else {
            "INSERT INTO entry_state (entry_id, inbox_state, progress, interaction_updated_at, updated_at, hlc)
             VALUES (?, 'none', 0.0, ?, ?, ?)
             ON CONFLICT(entry_id) DO UPDATE SET
                 inbox_state = 'none',
                 read_at = NULL,
                 interaction_updated_at = excluded.interaction_updated_at,
                 updated_at = excluded.updated_at,
                 hlc = excluded.hlc"
        };
        sqlx::query(state_sql)
            .bind(existing_id)
            .bind(now)
            .bind(now)
            .bind(&state_hlc)
            .execute(tx.conn())
            .await?;
        Ok(())
    }

    pub async fn update(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
        read_at: ReadAtPatch,
    ) -> Result<bool> {
        self.update_with_source(id, status, is_favorite, read_progress, read_at, None).await
    }

    pub async fn update_with_source(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
        read_at: ReadAtPatch,
        progress_source: Option<ProgressSource>,
    ) -> Result<bool> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let found = Self::update_in_tx(
            &mut tx,
            id,
            status,
            is_favorite,
            read_progress,
            read_at,
            progress_source,
            now,
            true,
        )
        .await?;
        if found {
            tx.commit().await?;
        }
        Ok(found)
    }

    /// Atomically marks a live reader item finished (`Archived`, `read_progress = 1.0`, `read_at = now`)
    /// and logs a `'consumed'` diary entry inside a single SQLite write transaction.
    ///
    /// Returns `Ok(None)` if `id` is not a live reader item, or `Ok(Some((has_feed, logged)))`.
    pub async fn finish_reader_item(
        &self,
        id: &str,
        notes: Option<&str>,
    ) -> CatalogResult<Option<(bool, ConsumptionLogView)>> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        let has_feed = sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM collection_entries ce
             JOIN feed_sources fs ON fs.collection_id = ce.collection_id
             WHERE ce.entry_id = ? LIMIT 1",
        )
        .bind(id)
        .fetch_optional(tx.conn())
        .await?
        .is_some();

        let found = Self::update_in_tx(
            &mut tx,
            id,
            Some(ItemStatus::Archived),
            None,
            Some(1.0),
            ReadAtPatch::MarkRead(now),
            None,
            now,
            false,
        )
        .await?;
        if !found {
            return Ok(None);
        }

        let (log_id, canon_id) = CatalogRepo::log_consumption_in_tx(
            &mut tx,
            id,
            ConsumptionStatus::Consumed,
            notes,
            now,
            now,
        )
        .await?;
        tx.commit().await?;

        let logged = CatalogRepo::new(self.pool, self.write_lock)
            .load_consumption_log_view(&log_id, &canon_id)
            .await?;
        Ok(Some((has_feed, logged)))
    }

    /// Atomically undoes reader completion in a single SQLite write transaction:
    /// deletes `consumed` diary logs (recording sync tombstones), resets the item to
    /// `Unread` / `progress = 0.0` / `read_at = NULL`, and re-attaches saved non-feed items
    /// to `Want to Read`.
    pub async fn unfinish_reader_item(&self, id: &str) -> CatalogResult<bool> {
        self.unfinish_reader_item_with_options(id, true).await
    }

    /// Atomically undoes reader completion in a single SQLite write transaction:
    /// deletes `consumed` diary logs (recording sync tombstones) and resets `progress = 0.0` /
    /// `read_at = NULL`.
    ///
    /// When `requeue_non_feed` is `true`, non-feed items are re-attached to `Want to Read`.
    /// When `requeue_non_feed` is `false` (for example, unfinishing a promoted Board story),
    /// non-feed items keep `inbox_state = 'none'` and preserve their existing `Want to Read`
    /// membership without being forcibly added to the Queue.
    pub async fn unfinish_reader_item_with_options(
        &self,
        id: &str,
        requeue_non_feed: bool,
    ) -> CatalogResult<bool> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        let has_feed = sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM collection_entries ce
             JOIN feed_sources fs ON fs.collection_id = ce.collection_id
             WHERE ce.entry_id = ? LIMIT 1",
        )
        .bind(id)
        .fetch_optional(tx.conn())
        .await?
        .is_some();

        let status_patch =
            if has_feed || requeue_non_feed { Some(ItemStatus::Unread) } else { None };
        let found = Self::update_in_tx(
            &mut tx,
            id,
            status_patch,
            None,
            Some(0.0),
            ReadAtPatch::MarkUnread,
            None,
            now,
            false,
        )
        .await?;
        if !found {
            return Ok(false);
        }

        let _ = CatalogRepo::delete_consumed_logs_for_entry_in_tx(&mut tx, id, now).await?;
        if !has_feed && requeue_non_feed {
            set_collection_flag_membership_in_tx(
                &mut tx,
                WANT_TO_READ_COLLECTION_ID,
                id,
                true,
                now,
            )
            .await?;
        }
        tx.commit().await?;
        Ok(true)
    }

    /// Atomically toggles `is_favorite` (Starred membership) for a live item in one transaction,
    /// returning `Ok(Some(new_is_favorite))` or `Ok(None)` if not found.
    pub async fn toggle_star(&self, id: &str) -> Result<Option<bool>> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT EXISTS (
                 SELECT 1 FROM collection_entries ce_fav
                 WHERE ce_fav.collection_id = ",
        );
        qb.push_bind(STARRED_COLLECTION_ID)
            .push(
                " AND ce_fav.entry_id = i.id
             )
             FROM entries i
             LEFT JOIN entry_state es ON es.entry_id = i.id
             LEFT JOIN entry_content ec ON ec.entry_id = i.id
             WHERE i.id = ",
            )
            .push_bind(id)
            .push(" AND i.merged_into_id IS NULL AND ")
            .push(IS_LIVE_ITEM_SQL);
        let current_fav: Option<bool> = qb.build_query_scalar().fetch_optional(tx.conn()).await?;
        let Some(cur) = current_fav else {
            return Ok(None);
        };
        let next = !cur;
        let found = Self::update_in_tx(
            &mut tx,
            id,
            None,
            Some(next),
            None,
            ReadAtPatch::Unchanged,
            None,
            now,
            true,
        )
        .await?;
        if !found {
            return Ok(None);
        }
        tx.commit().await?;
        Ok(Some(next))
    }

    /// Strictly dismisses a live reader item without ever logging `'consumed'`.
    ///
    /// If the item was previously Finished, deletes its `'consumed'` diary logs (recording
    /// sync tombstones) and resets `progress = 0.0` so it moves cleanly into **Dismissed**.
    /// Feed items move to `inbox_state = 'dismissed'` with `read_at = NULL`; saved `Want to Read`
    /// items are removed from `Want to Read`.
    pub async fn dismiss(&self, id: &str) -> Result<bool> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let deleted_logs = CatalogRepo::delete_consumed_logs_for_entry_in_tx(&mut tx, id, now)
            .await
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        let progress_patch = (deleted_logs > 0).then_some(0.0);
        let found = Self::update_in_tx(
            &mut tx,
            id,
            Some(ItemStatus::Archived),
            None,
            progress_patch,
            ReadAtPatch::MarkUnread,
            None,
            now,
            false,
        )
        .await?;
        if !found {
            return Ok(false);
        }
        tx.commit().await?;
        Ok(true)
    }

    #[allow(clippy::too_many_arguments, clippy::too_many_lines)]
    async fn update_in_tx(
        tx: &mut WriteTx<'_>,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
        read_at: ReadAtPatch,
        progress_source: Option<ProgressSource>,
        now: DateTime<Utc>,
        allow_auto_log: bool,
    ) -> std::result::Result<bool, sqlx::Error> {
        let mut check_qb = QueryBuilder::<Sqlite>::new(
            "SELECT i.kind,
                    COALESCE(ec.content_type, CASE i.kind
                        WHEN 'video' THEN 'video'
                        WHEN 'paper' THEN 'paper'
                        WHEN 'podcast_episode' THEN 'audio'
                        ELSE 'article'
                    END) AS content_type,
                    EXISTS (
                        SELECT 1 FROM entry_media m_aud
                        WHERE m_aud.entry_id = i.id
                          AND m_aud.media_type = 'audio'
                          AND (m_aud.remote_url IS NOT NULL OR m_aud.local_path IS NOT NULL)
                    ) AS has_audio_media,
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

        let existing: Option<ExistingItemStateRow> =
            check_qb.build_query_as().fetch_optional(tx.conn()).await?;

        let Some(ExistingItemStateRow {
            kind: entry_kind,
            content_type,
            has_audio_media,
            inbox_state: cur_inbox,
            is_favorite: current_favorite,
            in_want_to_read: current_in_wtr,
            progress: current_progress,
            read_at: current_read_at,
        }) = existing
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
        let auto_finish = allow_auto_log
            && status.is_none()
            && read_at != ReadAtPatch::MarkUnread
            && read_progress.is_some_and(|p| {
                should_auto_finish_from_progress(
                    entry_kind,
                    content_type,
                    has_audio_media,
                    p,
                    progress_source,
                )
            });
        let effective_status = if auto_finish { Some(ItemStatus::Archived) } else { status };
        let target_status = effective_status.unwrap_or(current_status);
        let target_inbox = if cur_inbox == InboxState::None {
            InboxState::None
        } else {
            InboxState::from(target_status)
        };
        let target_in_wtr = if cur_inbox == InboxState::None {
            match effective_status {
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
                ReadAtPatch::MarkUnread => {
                    if cur_inbox == InboxState::None && status == Some(ItemStatus::Archived) {
                        current_read_at.or(Some(now))
                    } else {
                        None
                    }
                }
                ReadAtPatch::Unchanged => {
                    if auto_finish || cur_inbox == InboxState::None {
                        current_read_at.or(Some(now))
                    } else {
                        current_read_at
                    }
                }
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
            if auto_finish {
                ensure_auto_finished_in_tx(tx, id, target_read_at.unwrap_or(now)).await?;
            }
            return Ok(true);
        }

        entries_core::upsert_entry_state_in_tx(
            tx,
            &EntryStateUpsert {
                entry_id: id,
                inbox_state: target_inbox,
                read_at: target_read_at,
                progress: target_progress,
                interaction_updated_at: Some(now),
                updated_at: now,
            },
        )
        .await?;

        sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
            .bind(now)
            .bind(id)
            .execute(tx.conn())
            .await?;

        if fav_changed {
            set_favorite_membership_in_tx(tx, id, target_favorite, now).await?;
        }
        if wtr_changed {
            set_collection_flag_membership_in_tx(
                tx,
                WANT_TO_READ_COLLECTION_ID,
                id,
                target_in_wtr,
                now,
            )
            .await?;
        }

        if auto_finish {
            ensure_auto_finished_in_tx(tx, id, target_read_at.unwrap_or(now)).await?;
        }

        if status_changed {
            let action = match target_status {
                ItemStatus::Archived => ActivityAction::Archive,
                ItemStatus::Unread => ActivityAction::Unarchive,
            };
            SyncRepo::record_activity(tx.conn(), ActivityEntityType::Item, id, action, now).await?;
        }

        if fav_changed {
            let action =
                if target_favorite { ActivityAction::Favorite } else { ActivityAction::Unfavorite };
            SyncRepo::record_activity(tx.conn(), ActivityEntityType::Item, id, action, now).await?;
        }

        if (read_at_changed && !status_changed) || progress_changed {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Item,
                id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        Ok(true)
    }

    pub async fn archive(&self, id: &str) -> Result<bool> {
        self.dismiss(id).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let deleted_logs = CatalogRepo::delete_consumed_logs_for_entry_in_tx(&mut tx, id, now)
            .await
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        let progress_patch = (deleted_logs > 0).then_some(0.0);
        let found = Self::update_in_tx(
            &mut tx,
            id,
            Some(ItemStatus::Unread),
            None,
            progress_patch,
            ReadAtPatch::MarkUnread,
            None,
            now,
            false,
        )
        .await?;
        if !found {
            return Ok(false);
        }
        tx.commit().await?;
        Ok(true)
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
        tx: &mut WriteTx<'_>,
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
            .fetch_all(tx.conn())
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
            .execute(tx.conn())
            .await?;
            for col_id in detached_item_cols {
                let nat_key = crate::db::sync::collection_entry_natural_key(&col_id, item_id);
                SyncRepo::record_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key, now)
                    .await?;
            }
        }

        if Self::entry_has_catalog_history_in_tx(tx.conn(), item_id).await? {
            let res = sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(item_id)
                .execute(tx.conn())
                .await?;
            if res.rows_affected() == 0 {
                return Ok(ItemDeleteOutcome { deleted: false, readable_content_removed: false });
            }
            let state_hlc = tx.next_hlc().await?;
            sqlx::query(
                "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at, hlc)
                 VALUES (?, 'none', NULL, 0.0, ?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     inbox_state = 'none',
                     read_at = NULL,
                     interaction_updated_at = excluded.interaction_updated_at,
                     updated_at = excluded.updated_at,
                     hlc = excluded.hlc",
            )
            .bind(item_id)
            .bind(now)
            .bind(now)
            .bind(&state_hlc)
            .execute(tx.conn())
            .await?;

            let keep_readable_content: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries ce
                 JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                 WHERE ce.entry_id = ? AND cs.collection_role IN ('list', 'want')
                 LIMIT 1",
            )
            .bind(item_id)
            .fetch_optional(tx.conn())
            .await?;

            if keep_readable_content.is_none() {
                sqlx::query("DELETE FROM entry_content WHERE entry_id = ?")
                    .bind(item_id)
                    .execute(tx.conn())
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
            .fetch_all(tx.conn())
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
            .execute(tx.conn())
            .await?;
            for col_id in detached_cols {
                let nat_key = crate::db::sync::collection_entry_natural_key(&col_id, item_id);
                SyncRepo::record_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key, now)
                    .await?;
            }
            Ok(ItemDeleteOutcome {
                deleted: true,
                readable_content_removed: keep_readable_content.is_none(),
            })
        } else {
            let res = sqlx::query("DELETE FROM entries WHERE id = ?")
                .bind(item_id)
                .execute(tx.conn())
                .await?;
            let deleted = res.rows_affected() > 0;
            Ok(ItemDeleteOutcome { deleted, readable_content_removed: deleted })
        }
    }

    pub(crate) async fn delete_inbox_item_preserving_history_in_tx(
        tx: &mut WriteTx<'_>,
        item_id: &str,
        detach_want_queues: bool,
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        Ok(Self::delete_inbox_item_preserving_history_outcome_in_tx(
            tx,
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
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

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
            key_qb.build_query_as().fetch_optional(tx.conn()).await?;
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
            tx.conn(),
            ActivityEntityType::Item,
            id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(outcome)
    }

    async fn backfill_entry_fields_in_tx(
        tx: &mut WriteTx<'_>,
        id: &str,
        backfill: &EntryMetadataBackfill<'_>,
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        if backfill.is_empty() {
            return Ok(false);
        }
        let creators_ref = backfill.creators_json.as_deref();
        let entry_hlc = tx.next_hlc().await?;
        let entry_res = sqlx::query(
            "UPDATE entries
             SET title = CASE WHEN title = url AND ? IS NOT NULL THEN ? ELSE title END,
                 creators_json = CASE WHEN creators_json = '[]' AND ? IS NOT NULL THEN ? ELSE creators_json END,
                 published_at = COALESCE(published_at, ?),
                 summary = COALESCE(summary, ?),
                 thumbnail_url = COALESCE(thumbnail_url, ?),
                 updated_at = ?,
                 hlc = ?
             WHERE id = ?
               AND ((title = url AND ? IS NOT NULL)
                    OR (creators_json = '[]' AND ? IS NOT NULL)
                    OR (published_at IS NULL AND ? IS NOT NULL)
                    OR (summary IS NULL AND ? IS NOT NULL)
                    OR (thumbnail_url IS NULL AND ? IS NOT NULL))",
        )
        .bind(backfill.title)
        .bind(backfill.title)
        .bind(creators_ref)
        .bind(creators_ref)
        .bind(backfill.published_at)
        .bind(backfill.summary)
        .bind(backfill.thumbnail_url)
        .bind(now)
        .bind(&entry_hlc)
        .bind(id)
        .bind(backfill.title)
        .bind(creators_ref)
        .bind(backfill.published_at)
        .bind(backfill.summary)
        .bind(backfill.thumbnail_url)
        .execute(tx.conn())
        .await?;
        Ok(entry_res.rows_affected() > 0)
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn apply_background_enrichment(
        &self,
        id: &str,
        title: Option<&str>,
        authors: &[String],
        published_at: Option<DateTime<Utc>>,
        summary: Option<&str>,
        thumbnail_url: Option<&str>,
        content_html: Option<&str>,
    ) -> Result<()> {
        let backfill = EntryMetadataBackfill {
            title: title.map(str::trim).filter(|s| !s.is_empty()),
            creators_json: crate::db::serialize_creators_json(authors),
            published_at,
            summary: summary.map(str::trim).filter(|s| !s.is_empty()),
            thumbnail_url: thumbnail_url.map(str::trim).filter(|s| !s.is_empty()),
        };
        let clean_html = content_html.filter(|s| !s.trim().is_empty());
        if backfill.is_empty() && clean_html.is_none() {
            return Ok(());
        }

        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        let live: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM entries e
             JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.id = ? AND e.merged_into_id IS NULL",
        )
        .bind(id)
        .fetch_optional(tx.conn())
        .await?;
        if live.is_none() {
            return Ok(());
        }

        let entry_updated = Self::backfill_entry_fields_in_tx(&mut tx, id, &backfill, now).await?;

        let mut content_updated = false;
        if let Some(html) = clean_html {
            let content_res = sqlx::query(
                "UPDATE entry_content
                 SET content_html = ?
                 WHERE entry_id = ? AND (content_html IS NULL OR content_html = '')",
            )
            .bind(html)
            .bind(id)
            .execute(tx.conn())
            .await?;
            if content_res.rows_affected() > 0 {
                content_updated = true;
                sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                    .bind(now)
                    .bind(id)
                    .execute(tx.conn())
                    .await?;
            }
        }

        if entry_updated || content_updated {
            SyncRepo::record_activity(
                tx.conn(),
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
        let backfill = EntryMetadataBackfill {
            title: title.map(str::trim).filter(|s| !s.is_empty()),
            creators_json: author
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .and_then(|a| serde_json::to_string(&[a]).ok()),
            published_at: None,
            summary: summary.map(str::trim).filter(|s| !s.is_empty()),
            thumbnail_url: None,
        };
        if backfill.is_empty() {
            return Ok(());
        }

        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        if Self::backfill_entry_fields_in_tx(&mut tx, id, &backfill, now).await? {
            SyncRepo::record_activity(
                tx.conn(),
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
