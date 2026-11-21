use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};
use url::Url;

use super::{
    ActivityAction, ActivityEntityType, CatalogRepo, ContentType, EntryKind, FeedType, InboxState,
    ItemRepo, MediaRepo, SyncRepo, WriteTx,
    entries_core::{self, EntryInsertParams},
    sync::{
        MAX_CONSECUTIVE_FAILURES, SERVER_PEER_ID, canonicalize_sync_url, deterministic_feed_id,
        deterministic_item_id, feed_natural_key, item_natural_key, normalize_consecutive_failures,
    },
};
use crate::{
    domain::OpmlOutline,
    util::{clock::now_utc, media_urls::extract_arxiv_id},
};

/// Read-only feed subscription projection over the unified `entries` + `collection_specs`
/// (`mode = 'feed'`, `collection_role = 'feed'`) + `feed_sources` tables.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(clippy::struct_field_names)]
pub struct Feed {
    pub id: String,
    pub feed_type: FeedType,
    pub url: Url,
    pub title: Option<String>,
    pub description: Option<String>,
    pub language: Option<String>,
    pub poll_interval: i64,
    pub last_polled: Option<DateTime<Utc>>,
    #[serde(default)]
    pub last_polled_by: Option<String>,
    pub last_error: Option<String>,
    #[serde(default)]
    pub last_error_by: Option<String>,
    #[serde(default)]
    pub consecutive_failures: i64,
    pub is_active: bool,
    pub created_at: DateTime<Utc>,
    pub unread_count: i64,
}

macro_rules! feed_select_sql {
    ($suffix:literal) => {
        concat!(
            "SELECT fs.collection_id AS id,
       fs.source_type AS feed_type,
       fs.feed_url AS url,
       fs.feed_title AS title,
       e.summary AS description,
       fs.language,
       fs.poll_interval,
       fs.last_polled_at AS last_polled,
       fs.last_polled_by,
       fs.last_error,
       fs.last_error_by,
       fs.consecutive_failures,
       fs.is_active,
       e.created_at,
       (SELECT COUNT(*)
        FROM collection_entries ce
        JOIN entry_state es ON es.entry_id = ce.entry_id
        JOIN entries ie ON ie.id = ce.entry_id
        WHERE ce.collection_id = fs.collection_id
          AND ie.merged_into_id IS NULL
          AND es.inbox_state = 'unread') AS unread_count
FROM feed_sources fs
JOIN entries e ON e.id = fs.collection_id ",
            $suffix
        )
    };
}

const FEED_SELECT_BY_ID_SQL: &str = feed_select_sql!("WHERE fs.collection_id = ?");
const FEED_SELECT_ALL_SQL: &str = feed_select_sql!("ORDER BY e.created_at DESC");
const FEED_SELECT_ACTIVE_POLL_SQL: &str = "SELECT fs.collection_id AS id,
       fs.source_type AS feed_type,
       fs.feed_url AS url,
       fs.feed_title AS title,
       e.summary AS description,
       fs.language,
       fs.poll_interval,
       fs.last_polled_at AS last_polled,
       fs.last_polled_by,
       fs.last_error,
       fs.last_error_by,
       fs.consecutive_failures,
       fs.is_active,
       e.created_at,
       0 AS unread_count
FROM feed_sources fs
JOIN entries e ON e.id = fs.collection_id
WHERE fs.is_active = 1";

#[derive(sqlx::FromRow)]
struct RawFeedRow {
    id: String,
    feed_type: FeedType,
    url: String,
    title: Option<String>,
    description: Option<String>,
    language: Option<String>,
    poll_interval: i64,
    last_polled: Option<DateTime<Utc>>,
    last_polled_by: Option<String>,
    last_error: Option<String>,
    last_error_by: Option<String>,
    consecutive_failures: i64,
    is_active: bool,
    created_at: DateTime<Utc>,
    unread_count: i64,
}

impl TryFrom<RawFeedRow> for Feed {
    type Error = sqlx::Error;

    fn try_from(raw: RawFeedRow) -> std::result::Result<Self, Self::Error> {
        let url = Url::parse(&raw.url).map_err(|e| sqlx::Error::ColumnDecode {
            index: "url".to_owned(),
            source: Box::new(e),
        })?;
        let consecutive_failures =
            normalize_consecutive_failures(raw.last_error.as_deref(), raw.consecutive_failures);
        Ok(Self {
            id: raw.id,
            feed_type: raw.feed_type,
            url,
            title: raw.title,
            description: raw.description,
            language: raw.language,
            poll_interval: raw.poll_interval,
            last_polled: raw.last_polled,
            last_polled_by: raw.last_polled_by,
            last_error: raw.last_error,
            last_error_by: raw.last_error_by,
            consecutive_failures,
            is_active: raw.is_active,
            created_at: raw.created_at,
            unread_count: raw.unread_count,
        })
    }
}

#[derive(Debug, Clone)]
pub struct PollItemCandidate {
    pub title: String,
    pub url: Url,
    pub creators: Vec<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub summary: String,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
    pub audio_url: Option<String>,
    pub duration: Option<i64>,
    pub content_type: ContentType,
}

#[derive(Debug, Clone)]
pub struct PollBatch {
    pub title: Option<String>,
    pub description: Option<String>,
    pub language: Option<String>,
    pub items: Vec<PollItemCandidate>,
}

pub(super) const fn entry_kind_for_feed_type(feed_type: FeedType) -> EntryKind {
    match feed_type {
        FeedType::Rss => EntryKind::Publication,
        FeedType::Podcast => EntryKind::Podcast,
        FeedType::Youtube => EntryKind::Channel,
        FeedType::Arxiv => EntryKind::Collection,
    }
}

pub(super) struct NewFeedRow<'a> {
    pub id: &'a str,
    pub feed_url: &'a str,
    pub feed_type: FeedType,
    pub title: Option<&'a str>,
    pub description: Option<&'a str>,
    pub language: Option<&'a str>,
    pub poll_interval: i64,
    pub is_active: bool,
    pub last_polled: Option<DateTime<Utc>>,
    pub last_polled_by: Option<&'a str>,
    pub last_error: Option<&'a str>,
    pub last_error_by: Option<&'a str>,
    pub consecutive_failures: i64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub entry_hlc: Option<&'a str>,
    pub feed_hlc: Option<&'a str>,
}

pub(super) async fn resolve_dedup_entry_id(
    conn: &mut SqliteConnection,
    det_id: &str,
    canon_url: &str,
    arxiv_id: Option<&str>,
) -> std::result::Result<Option<String>, sqlx::Error> {
    if let Some(found) = CatalogRepo::follow_merged_id_in_tx(&mut *conn, det_id).await? {
        return Ok(Some(found));
    }

    if let Some(aid) = arxiv_id
        && let Some(raw_id) =
            entries_core::find_entry_id_by_identifier_in_tx(&mut *conn, "arxiv", aid).await?
        && let Some(canon) = CatalogRepo::follow_merged_id_in_tx(&mut *conn, &raw_id).await?
    {
        return Ok(Some(canon));
    }

    if let Some(raw_id) =
        entries_core::find_entry_id_by_identifier_in_tx(&mut *conn, "url", canon_url).await?
        && let Some(canon) = CatalogRepo::follow_merged_id_in_tx(&mut *conn, &raw_id).await?
    {
        return Ok(Some(canon));
    }

    Ok(None)
}

#[derive(sqlx::FromRow)]
struct ExistingFeedPollMeta {
    feed_url: String,
    source_type: FeedType,
    feed_title: Option<String>,
    kind: EntryKind,
    summary: Option<String>,
    language: Option<String>,
}

pub struct FeedRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

impl<'a> FeedRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    #[cfg(test)]
    pub async fn create(
        &self,
        feed_type: FeedType,
        url: &Url,
        title: Option<&str>,
        description: Option<&str>,
    ) -> Result<Feed> {
        self.create_with_interval(feed_type, url, title, description, 30).await
    }

    async fn reactivate_archived_feed_in_tx(
        tx: &mut WriteTx<'_>,
        url_str: &str,
        det_id: &str,
        title: Option<&str>,
        description: Option<&str>,
        prefer_incoming_title: bool,
        now: DateTime<Utc>,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let existing_archived_id: Option<String> = sqlx::query_scalar(
            "SELECT collection_id FROM feed_sources
             WHERE (feed_url = ? OR collection_id = ?) AND is_active = 0
             LIMIT 1",
        )
        .bind(url_str)
        .bind(det_id)
        .fetch_optional(tx.conn())
        .await?;

        let Some(existing_id) = existing_archived_id else {
            return Ok(None);
        };

        let feed_hlc = tx.next_hlc().await?;
        let feed_sql = if prefer_incoming_title {
            "UPDATE feed_sources
             SET is_active = 1,
                 feed_title = COALESCE(?, feed_title),
                 consecutive_failures = 0,
                 last_error = NULL,
                 last_error_by = NULL,
                 hlc = ?
             WHERE collection_id = ?"
        } else {
            "UPDATE feed_sources
             SET is_active = 1,
                 feed_title = COALESCE(feed_title, ?),
                 consecutive_failures = 0,
                 last_error = NULL,
                 last_error_by = NULL,
                 hlc = ?
             WHERE collection_id = ?"
        };
        sqlx::query(feed_sql)
            .bind(title)
            .bind(&feed_hlc)
            .bind(&existing_id)
            .execute(tx.conn())
            .await?;

        let entry_hlc = tx.next_hlc().await?;
        if prefer_incoming_title {
            sqlx::query(
                "UPDATE entries
                 SET title = COALESCE(?, title),
                     summary = COALESCE(?, summary),
                     updated_at = ?,
                     hlc = ?
                 WHERE id = ?",
            )
            .bind(title)
            .bind(description)
            .bind(now)
            .bind(&entry_hlc)
            .bind(&existing_id)
            .execute(tx.conn())
            .await?;
        } else {
            sqlx::query(
                "UPDATE entries
                 SET title = COALESCE((SELECT feed_title FROM feed_sources WHERE collection_id = ?), title),
                     updated_at = ?,
                     hlc = ?
                 WHERE id = ?",
            )
            .bind(&existing_id)
            .bind(now)
            .bind(&entry_hlc)
            .bind(&existing_id)
            .execute(tx.conn())
            .await?;
        }

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Feed,
            &existing_id,
            ActivityAction::Unarchive,
            now,
        )
        .await?;

        Ok(Some(existing_id))
    }

    pub async fn create_with_interval(
        &self,
        feed_type: FeedType,
        url: &Url,
        title: Option<&str>,
        description: Option<&str>,
        poll_interval: i64,
    ) -> Result<Feed> {
        let url_str = url.as_str();
        let id = deterministic_feed_id(url_str);
        let nat_key = feed_natural_key(url_str);
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;

        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::Feed, &nat_key).await?;

        if let Some(existing_id) = Self::reactivate_archived_feed_in_tx(
            &mut tx,
            url_str,
            &id,
            title,
            description,
            true,
            now,
        )
        .await?
        {
            tx.commit().await?;
            return self
                .get(&existing_id)
                .await?
                .ok_or_else(|| anyhow::anyhow!("Feed disappeared after unarchive"));
        }

        let active_feed_exists: Option<i64> = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\" FROM feed_sources WHERE collection_id = ? OR feed_url = ? LIMIT 1",
            id,
            url_str,
        )
        .fetch_optional(tx.conn())
        .await?;

        if active_feed_exists.is_some() {
            return Err(super::ConflictError(format!("Feed '{url_str}' already exists")).into());
        }

        Self::insert_feed_in_tx(
            &mut tx,
            &NewFeedRow {
                id: &id,
                feed_url: url_str,
                feed_type,
                title,
                description,
                language: None,
                poll_interval,
                is_active: true,
                last_polled: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                created_at: now,
                updated_at: now,
                entry_hlc: None,
                feed_hlc: None,
            },
        )
        .await?;

        tx.commit().await?;

        Ok(Feed {
            id,
            feed_type,
            url: url.clone(),
            title: title.map(String::from),
            description: description.map(String::from),
            language: None,
            poll_interval,
            last_polled: None,
            last_polled_by: None,
            last_error: None,
            last_error_by: None,
            consecutive_failures: 0,
            is_active: true,
            created_at: now,
            unread_count: 0,
        })
    }

    pub(super) async fn insert_feed_in_tx(
        tx: &mut WriteTx<'_>,
        row: &NewFeedRow<'_>,
    ) -> std::result::Result<(), sqlx::Error> {
        let entry_kind = entry_kind_for_feed_type(row.feed_type);
        let entry_title =
            row.title.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(row.feed_url);
        let nat_key = feed_natural_key(row.feed_url);
        let norm_failures =
            normalize_consecutive_failures(row.last_error, row.consecutive_failures);
        let entry_hlc = match row.entry_hlc {
            Some(h) if !h.is_empty() => h.to_owned(),
            _ => tx.next_hlc().await?,
        };
        let feed_hlc = match row.feed_hlc {
            Some(h) if !h.is_empty() => h.to_owned(),
            _ => tx.next_hlc().await?,
        };

        sqlx::query(
            "INSERT INTO entries (id, kind, title, summary, created_at, updated_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                 kind = excluded.kind,
                 title = excluded.title,
                 summary = COALESCE(excluded.summary, entries.summary),
                 created_at = excluded.created_at,
                 updated_at = excluded.updated_at,
                 hlc = excluded.hlc",
        )
        .bind(row.id)
        .bind(entry_kind)
        .bind(entry_title)
        .bind(row.description)
        .bind(row.created_at)
        .bind(row.updated_at)
        .bind(&entry_hlc)
        .execute(tx.conn())
        .await?;

        sqlx::query!(
            "INSERT INTO collection_specs (collection_id, mode, collection_role, show_in_lists)
             VALUES (?, 'feed', 'feed', 0)
             ON CONFLICT(collection_id) DO UPDATE SET
                 mode = 'feed',
                 collection_role = 'feed'",
            row.id,
        )
        .execute(tx.conn())
        .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO feed_sources
             (collection_id, feed_url, source_type, feed_title, language, poll_interval,
              is_active, last_polled_at, last_polled_by, last_error, last_error_by,
              consecutive_failures, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(row.id)
        .bind(row.feed_url)
        .bind(row.feed_type)
        .bind(row.title)
        .bind(row.language)
        .bind(row.poll_interval)
        .bind(row.is_active)
        .bind(row.last_polled)
        .bind(row.last_polled_by)
        .bind(row.last_error)
        .bind(row.last_error_by)
        .bind(norm_failures)
        .bind(&feed_hlc)
        .execute(tx.conn())
        .await?;

        entries_core::upsert_entry_identifier_in_tx(tx.conn(), row.id, "feed_url", &nat_key)
            .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Feed,
            row.id,
            ActivityAction::Create,
            row.updated_at,
        )
        .await
    }

    pub async fn import_outlines(
        &self,
        outlines: Vec<OpmlOutline>,
        default_interval: i64,
    ) -> Result<Vec<Feed>> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let mut imported = Vec::new();

        for outline in outlines {
            if let Some(feed) =
                Self::import_single_outline_in_tx(&mut tx, outline, default_interval, now).await?
            {
                imported.push(feed);
            }
        }

        tx.commit().await?;
        Ok(imported)
    }

    async fn import_single_outline_in_tx(
        tx: &mut WriteTx<'_>,
        outline: OpmlOutline,
        default_interval: i64,
        now: DateTime<Utc>,
    ) -> Result<Option<Feed>> {
        let xml_url_str = outline.xml_url.as_str();
        let id = deterministic_feed_id(xml_url_str);
        let nat_key = feed_natural_key(xml_url_str);
        let clean_title = outline.title.as_deref().map(str::trim).filter(|t| !t.is_empty());
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::Feed, &nat_key).await?;

        if let Some(existing_id) = Self::reactivate_archived_feed_in_tx(
            tx,
            xml_url_str,
            &id,
            clean_title,
            None,
            false,
            now,
        )
        .await?
        {
            let raw = sqlx::query_as::<_, RawFeedRow>(FEED_SELECT_BY_ID_SQL)
                .bind(&existing_id)
                .fetch_optional(tx.conn())
                .await?;
            return Ok(raw.map(Feed::try_from).transpose()?);
        }

        let already_exists: Option<i64> = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\" FROM feed_sources WHERE feed_url = ? OR collection_id = ? LIMIT 1",
            xml_url_str,
            id,
        )
        .fetch_optional(tx.conn())
        .await?;
        if already_exists.is_some() {
            return Ok(None);
        }

        Self::insert_feed_in_tx(
            tx,
            &NewFeedRow {
                id: &id,
                feed_url: xml_url_str,
                feed_type: outline.feed_type,
                title: clean_title,
                description: None,
                language: None,
                poll_interval: default_interval,
                is_active: true,
                last_polled: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                created_at: now,
                updated_at: now,
                entry_hlc: None,
                feed_hlc: None,
            },
        )
        .await?;

        Ok(Some(Feed {
            id,
            feed_type: outline.feed_type,
            url: outline.xml_url,
            title: clean_title.map(String::from),
            description: None,
            language: None,
            poll_interval: default_interval,
            last_polled: None,
            last_polled_by: None,
            last_error: None,
            last_error_by: None,
            consecutive_failures: 0,
            is_active: true,
            created_at: now,
            unread_count: 0,
        }))
    }

    pub async fn get(&self, id: &str) -> Result<Option<Feed>> {
        let raw = sqlx::query_as::<_, RawFeedRow>(FEED_SELECT_BY_ID_SQL)
            .bind(id)
            .fetch_optional(self.pool)
            .await?;
        Ok(raw.map(Feed::try_from).transpose()?)
    }

    pub async fn list(&self) -> Result<Vec<Feed>> {
        let mut conn = self.pool.acquire().await?;
        Self::list_in_tx(&mut conn).await
    }

    pub(crate) async fn list_in_tx(conn: &mut SqliteConnection) -> Result<Vec<Feed>> {
        let rows = sqlx::query_as::<_, RawFeedRow>(FEED_SELECT_ALL_SQL).fetch_all(conn).await?;
        Ok(rows.into_iter().map(Feed::try_from).collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub async fn update(
        &self,
        id: &str,
        title: Option<&str>,
        poll_interval: Option<i64>,
        is_active: Option<bool>,
    ) -> Result<bool> {
        if title.is_none() && poll_interval.is_none() && is_active.is_none() {
            return Ok(self.get(id).await?.is_some());
        }

        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;

        let existing_url: Option<String> =
            sqlx::query_scalar!("SELECT feed_url FROM feed_sources WHERE collection_id = ?", id,)
                .fetch_optional(tx.conn())
                .await?;
        let Some(feed_url) = existing_url else {
            return Ok(false);
        };

        let feed_hlc = tx.next_hlc().await?;
        if let Some(t) = title {
            let entry_title = if t.trim().is_empty() { feed_url.as_str() } else { t };
            sqlx::query("UPDATE feed_sources SET feed_title = ?, hlc = ? WHERE collection_id = ?")
                .bind(t)
                .bind(&feed_hlc)
                .bind(id)
                .execute(tx.conn())
                .await?;
            let entry_hlc = tx.next_hlc().await?;
            sqlx::query("UPDATE entries SET title = ?, updated_at = ?, hlc = ? WHERE id = ?")
                .bind(entry_title)
                .bind(now)
                .bind(&entry_hlc)
                .bind(id)
                .execute(tx.conn())
                .await?;
        }
        if let Some(interval) = poll_interval {
            sqlx::query(
                "UPDATE feed_sources SET poll_interval = ?, hlc = ? WHERE collection_id = ?",
            )
            .bind(interval)
            .bind(&feed_hlc)
            .bind(id)
            .execute(tx.conn())
            .await?;
        }
        if let Some(active) = is_active {
            if active {
                sqlx::query(
                    "UPDATE feed_sources
                     SET is_active = 1,
                         consecutive_failures = 0,
                         last_error = NULL,
                         last_error_by = NULL,
                         hlc = ?
                     WHERE collection_id = ?",
                )
                .bind(&feed_hlc)
                .bind(id)
                .execute(tx.conn())
                .await?;
            } else {
                sqlx::query(
                    "UPDATE feed_sources SET is_active = 0, hlc = ? WHERE collection_id = ?",
                )
                .bind(&feed_hlc)
                .bind(id)
                .execute(tx.conn())
                .await?;
            }
        }
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, id)
            .execute(tx.conn())
            .await?;

        let action = match is_active {
            Some(false) => ActivityAction::Archive,
            Some(true) => ActivityAction::Unarchive,
            None => ActivityAction::Update,
        };

        SyncRepo::record_activity(tx.conn(), ActivityEntityType::Feed, id, action, now).await?;

        tx.commit().await?;
        Ok(true)
    }

    pub async fn archive(&self, id: &str) -> Result<bool> {
        self.update(id, None, None, Some(false)).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, None, None, Some(true)).await
    }

    pub(crate) async fn delete_feed_preserving_history_in_tx(
        tx: &mut WriteTx<'_>,
        feed_id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let member_rows: Vec<(String, bool)> = sqlx::query_as(
            "SELECT ce.entry_id,
                    EXISTS (
                        SELECT 1 FROM collection_entries ce2
                        JOIN feed_sources fs2 ON fs2.collection_id = ce2.collection_id
                        WHERE ce2.entry_id = ce.entry_id
                          AND ce2.collection_id != ?
                    ) AS keep_in_inbox
             FROM collection_entries ce
             WHERE ce.collection_id = ?",
        )
        .bind(feed_id)
        .bind(feed_id)
        .fetch_all(tx.conn())
        .await?;

        for (entry_id, keep_in_inbox) in member_rows {
            if keep_in_inbox {
                sqlx::query(
                    "DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                )
                .bind(feed_id)
                .bind(&entry_id)
                .execute(tx.conn())
                .await?;
            } else {
                let _ =
                    ItemRepo::delete_inbox_item_preserving_history_in_tx(tx, &entry_id, false, now)
                        .await?;
            }
        }

        sqlx::query!("DELETE FROM feed_sources WHERE collection_id = ?", feed_id)
            .execute(tx.conn())
            .await?;

        let has_preserved_children: Option<i64> = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\" FROM collection_entries WHERE collection_id = ? LIMIT 1",
            feed_id,
        )
        .fetch_optional(tx.conn())
        .await?;

        if has_preserved_children.is_some()
            || ItemRepo::entry_has_catalog_history_in_tx(tx.conn(), feed_id).await?
        {
            sqlx::query!("DELETE FROM collection_specs WHERE collection_id = ?", feed_id)
                .execute(tx.conn())
                .await?;
            sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, feed_id)
                .execute(tx.conn())
                .await?;
        } else {
            sqlx::query!("DELETE FROM entries WHERE id = ?", feed_id).execute(tx.conn()).await?;
        }
        Ok(())
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let deleted_url: Option<String> =
            sqlx::query_scalar!("SELECT feed_url FROM feed_sources WHERE collection_id = ?", id,)
                .fetch_optional(tx.conn())
                .await?;
        let Some(feed_url) = deleted_url else {
            return Ok(false);
        };

        Self::delete_feed_preserving_history_in_tx(&mut tx, id, now).await?;

        SyncRepo::record_tombstone(
            &mut tx,
            ActivityEntityType::Feed,
            &feed_natural_key(&feed_url),
            now,
        )
        .await?;
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Feed,
            id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn get_active_for_cooperative_polling(&self) -> Result<Vec<Feed>> {
        let rows = sqlx::query_as::<_, RawFeedRow>(FEED_SELECT_ACTIVE_POLL_SQL)
            .fetch_all(self.pool)
            .await?;
        Ok(rows.into_iter().map(Feed::try_from).collect::<std::result::Result<Vec<_>, _>>()?)
    }

    pub async fn record_poll_batch(
        &self,
        feed_id: &str,
        batch: &PollBatch,
    ) -> std::result::Result<Vec<(String, PollItemCandidate)>, sqlx::Error> {
        self.record_poll_batch_by_peer(feed_id, batch, SERVER_PEER_ID).await
    }

    #[allow(clippy::too_many_lines)]
    pub async fn record_poll_batch_by_peer(
        &self,
        feed_id: &str,
        batch: &PollBatch,
        peer_id: &str,
    ) -> std::result::Result<Vec<(String, PollItemCandidate)>, sqlx::Error> {
        let now = now_utc();

        let new_title = batch.title.as_deref().filter(|s| !s.is_empty());
        let new_desc = batch.description.as_deref().filter(|s| !s.is_empty());
        let new_lang = batch.language.as_deref().filter(|s| !s.is_empty());

        let has_audio_items = batch
            .items
            .iter()
            .any(|it| it.content_type == ContentType::Audio || it.audio_url.is_some());

        let mut tx = crate::db::begin_write(self.pool).await?;

        let existing_meta: Option<ExistingFeedPollMeta> = sqlx::query_as(
            "SELECT fs.feed_url, fs.source_type, fs.feed_title, e.kind, e.summary, fs.language
             FROM feed_sources fs
             JOIN entries e ON e.id = fs.collection_id
             WHERE fs.collection_id = ?",
        )
        .bind(feed_id)
        .fetch_optional(tx.conn())
        .await?;

        let feed_url = existing_meta.as_ref().map(|r| r.feed_url.clone());
        let feed_config_changed = existing_meta.as_ref().is_some_and(|meta| {
            (meta.feed_title.is_none() && new_title.is_some())
                || (meta.language.is_none() && new_lang.is_some())
                || (has_audio_items && meta.source_type == FeedType::Rss)
        });
        let entry_meta_changed = existing_meta.as_ref().is_some_and(|meta| {
            (meta.feed_title.is_none() && new_title.is_some())
                || (meta.summary.is_none() && new_desc.is_some())
                || (has_audio_items && meta.kind == EntryKind::Publication)
        });
        let metadata_updated = existing_meta.as_ref().is_some_and(|meta| {
            (meta.feed_title.is_none() && new_title.is_some())
                || (meta.summary.is_none() && new_desc.is_some())
                || (meta.language.is_none() && new_lang.is_some())
        });

        let feed_hlc_opt = if feed_config_changed { Some(tx.next_hlc().await?) } else { None };
        sqlx::query(
            "UPDATE feed_sources
             SET feed_title = COALESCE(feed_title, ?),
                 language = COALESCE(language, ?),
                 source_type = CASE WHEN ? AND source_type = 'rss' THEN 'podcast' ELSE source_type END,
                 last_polled_at = ?,
                 last_polled_by = ?,
                 last_error = NULL,
                 last_error_by = NULL,
                 consecutive_failures = 0,
                 hlc = COALESCE(?, hlc)
             WHERE collection_id = ?",
        )
        .bind(new_title)
        .bind(new_lang)
        .bind(has_audio_items)
        .bind(now)
        .bind(peer_id)
        .bind(feed_hlc_opt.as_deref())
        .bind(feed_id)
        .execute(tx.conn())
        .await?;

        let entry_hlc_opt = if entry_meta_changed { Some(tx.next_hlc().await?) } else { None };
        sqlx::query(
            "UPDATE entries
             SET title = COALESCE((SELECT feed_title FROM feed_sources WHERE collection_id = ?), title),
                 summary = COALESCE(summary, ?),
                 kind = CASE WHEN ? AND kind = 'publication' THEN 'podcast' ELSE kind END,
                 updated_at = ?,
                 hlc = COALESCE(?, hlc)
             WHERE id = ?",
        )
        .bind(feed_id)
        .bind(new_desc)
        .bind(has_audio_items)
        .bind(now)
        .bind(entry_hlc_opt.as_deref())
        .bind(feed_id)
        .execute(tx.conn())
        .await?;

        if metadata_updated {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Feed,
                feed_id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        let mut inserted = Vec::new();
        for item in &batch.items {
            let item_url_str = item.url.as_str();
            let item_thumb = item.thumbnail_url.as_deref();
            let item_content_html = item.content_html.as_deref();
            let natural_key = item_natural_key(feed_url.as_deref(), item_url_str);
            let tombstoned: Option<i64> = sqlx::query_scalar!(
                "SELECT 1 AS \"found!: i64\" FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ? LIMIT 1",
                natural_key,
            )
            .fetch_optional(tx.conn())
            .await?;
            if tombstoned.is_some() {
                continue;
            }

            let det_id = deterministic_item_id(feed_url.as_deref(), item_url_str);
            let canon_url = canonicalize_sync_url(item_url_str);
            let arxiv_id = extract_arxiv_id(item_url_str);
            let clean_duration = item.duration.filter(|&d| d > 0);

            if let Some(existing_id) =
                resolve_dedup_entry_id(tx.conn(), &det_id, &canon_url, arxiv_id.as_deref()).await?
            {
                let existing_state: Option<InboxState> = sqlx::query_scalar!(
                    "SELECT inbox_state AS \"inbox_state: InboxState\" FROM entry_state WHERE entry_id = ? LIMIT 1",
                    existing_id,
                )
                .fetch_optional(tx.conn())
                .await?;

                let ce_hlc = tx.next_hlc().await?;
                sqlx::query(
                    "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at, hlc)
                     VALUES (?, ?, ?, ?)",
                )
                .bind(feed_id)
                .bind(&existing_id)
                .bind(now)
                .bind(&ce_hlc)
                .execute(tx.conn())
                .await?;

                if existing_state.is_none() {
                    let clean_summary =
                        Some(item.summary.as_str()).filter(|s| !s.trim().is_empty());
                    let entry_hlc = tx.next_hlc().await?;
                    sqlx::query(
                        "UPDATE entries
                         SET url = COALESCE(url, ?),
                             published_at = COALESCE(published_at, ?),
                             summary = COALESCE(summary, ?),
                             thumbnail_url = COALESCE(thumbnail_url, ?),
                             updated_at = ?,
                             hlc = CASE
                                 WHEN url IS NULL
                                   OR (published_at IS NULL AND ? IS NOT NULL)
                                   OR (summary IS NULL AND ? IS NOT NULL)
                                   OR (thumbnail_url IS NULL AND ? IS NOT NULL)
                                 THEN ?
                                 ELSE hlc
                             END
                         WHERE id = ?",
                    )
                    .bind(item_url_str)
                    .bind(item.published_at)
                    .bind(clean_summary)
                    .bind(item_thumb)
                    .bind(now)
                    .bind(item.published_at)
                    .bind(clean_summary)
                    .bind(item_thumb)
                    .bind(&entry_hlc)
                    .bind(&existing_id)
                    .execute(tx.conn())
                    .await?;

                    sqlx::query!(
                        "INSERT INTO entry_content (entry_id, content_html, content_type)
                         VALUES (?, ?, ?)
                         ON CONFLICT(entry_id) DO UPDATE SET
                             content_html = COALESCE(entry_content.content_html, excluded.content_html),
                             content_type = excluded.content_type",
                        existing_id,
                        item_content_html,
                        item.content_type as _,
                    )
                    .execute(tx.conn())
                    .await?;

                    entries_core::ensure_entry_state_in_tx(
                        &mut tx,
                        &existing_id,
                        InboxState::Unread,
                        now,
                    )
                    .await?;

                    entries_core::upsert_url_and_arxiv_identifiers_in_tx(
                        tx.conn(),
                        &existing_id,
                        &canon_url,
                        arxiv_id.as_deref(),
                    )
                    .await?;

                    MediaRepo::upsert_audio_enclosure_in_tx(
                        tx.conn(),
                        &existing_id,
                        item.audio_url.as_deref(),
                        clean_duration,
                        now,
                    )
                    .await?;

                    Self::backfill_existing_poll_item_in_tx(
                        &mut tx,
                        &existing_id,
                        item,
                        clean_duration,
                        now,
                    )
                    .await?;

                    SyncRepo::record_activity(
                        tx.conn(),
                        ActivityEntityType::Item,
                        &existing_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                    inserted.push((existing_id, item.clone()));
                } else {
                    Self::backfill_existing_poll_item_in_tx(
                        &mut tx,
                        &existing_id,
                        item,
                        clean_duration,
                        now,
                    )
                    .await?;
                }
            } else {
                let entry_kind = EntryKind::from_content_type(item.content_type);
                let creators_json = crate::db::serialize_creators_json(&item.creators)
                    .unwrap_or_else(|| "[]".to_owned());

                let inserted_entry = entries_core::insert_entry_row_or_ignore_in_tx(
                    &mut tx,
                    &EntryInsertParams {
                        creators_json: &creators_json,
                        url: Some(item_url_str),
                        published_at: item.published_at,
                        summary: Some(item.summary.as_str()),
                        thumbnail_url: item_thumb,
                        ..EntryInsertParams::minimal(&det_id, entry_kind, &item.title, now, now)
                    },
                )
                .await?;

                if inserted_entry {
                    entries_core::insert_entry_content_in_tx(
                        tx.conn(),
                        &det_id,
                        item_content_html,
                        item.content_type,
                    )
                    .await?;

                    entries_core::ensure_entry_state_in_tx(
                        &mut tx,
                        &det_id,
                        InboxState::Unread,
                        now,
                    )
                    .await?;

                    entries_core::upsert_url_and_arxiv_identifiers_in_tx(
                        tx.conn(),
                        &det_id,
                        &canon_url,
                        arxiv_id.as_deref(),
                    )
                    .await?;

                    let ce_hlc = tx.next_hlc().await?;
                    sqlx::query(
                        "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at, hlc)
                         VALUES (?, ?, ?, ?)",
                    )
                    .bind(feed_id)
                    .bind(&det_id)
                    .bind(now)
                    .bind(&ce_hlc)
                    .execute(tx.conn())
                    .await?;

                    MediaRepo::upsert_audio_enclosure_in_tx(
                        tx.conn(),
                        &det_id,
                        item.audio_url.as_deref(),
                        clean_duration,
                        now,
                    )
                    .await?;

                    SyncRepo::record_activity(
                        tx.conn(),
                        ActivityEntityType::Item,
                        &det_id,
                        ActivityAction::Create,
                        now,
                    )
                    .await?;
                    inserted.push((det_id, item.clone()));
                }
            }
        }

        tx.commit().await?;
        Ok(inserted)
    }

    async fn backfill_existing_poll_item_in_tx(
        tx: &mut WriteTx<'_>,
        existing_id: &str,
        item: &PollItemCandidate,
        clean_duration: Option<i64>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        if item.audio_url.is_none()
            && clean_duration.is_none()
            && item.thumbnail_url.is_none()
            && item.creators.is_empty()
        {
            return Ok(());
        }

        let new_creators_json = crate::db::serialize_creators_json(&item.creators);
        let thumb_ref = item.thumbnail_url.as_deref();
        let creators_ref = new_creators_json.as_deref();
        let audio_ref = item.audio_url.as_deref();

        let mut changed = false;

        let entry_hlc = tx.next_hlc().await?;
        let entry_res = sqlx::query(
            "UPDATE entries
             SET thumbnail_url = COALESCE(thumbnail_url, ?),
                 creators_json = CASE WHEN creators_json = '[]' AND ? IS NOT NULL THEN ? ELSE creators_json END,
                 kind = CASE WHEN ? IS NOT NULL AND kind = 'article' THEN 'podcast_episode' ELSE kind END,
                 updated_at = ?,
                 hlc = ?
             WHERE id = ?
               AND ((thumbnail_url IS NULL AND ? IS NOT NULL)
                    OR (creators_json = '[]' AND ? IS NOT NULL)
                    OR (kind = 'article' AND ? IS NOT NULL))",
        )
        .bind(thumb_ref)
        .bind(creators_ref)
        .bind(creators_ref)
        .bind(audio_ref)
        .bind(now)
        .bind(&entry_hlc)
        .bind(existing_id)
        .bind(thumb_ref)
        .bind(creators_ref)
        .bind(audio_ref)
        .execute(tx.conn())
        .await?;
        if entry_res.rows_affected() > 0 {
            changed = true;
        }

        if item.audio_url.is_some() {
            let ct_res = sqlx::query!(
                "UPDATE entry_content
                 SET content_type = 'audio'
                 WHERE entry_id = ? AND content_type != 'audio'",
                existing_id,
            )
            .execute(tx.conn())
            .await?;
            if ct_res.rows_affected() > 0 {
                changed = true;
            }
        }

        if item.audio_url.is_some() || clean_duration.is_some() {
            let enclosure_updated = Self::backfill_poll_audio_enclosure_in_tx(
                tx.conn(),
                existing_id,
                item.audio_url.as_deref(),
                clean_duration,
                now,
            )
            .await?;
            changed |= enclosure_updated;
        }

        if changed {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Item,
                existing_id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }
        Ok(())
    }

    async fn backfill_poll_audio_enclosure_in_tx(
        conn: &mut SqliteConnection,
        existing_id: &str,
        audio_url: Option<&str>,
        clean_duration: Option<i64>,
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        let before_enclosure = sqlx::query!(
            "SELECT remote_url, duration FROM entry_media
             WHERE entry_id = ? AND local_path IS NULL AND media_type = 'audio'
             LIMIT 1",
            existing_id,
        )
        .fetch_optional(&mut *conn)
        .await?;

        let needs_enclosure_update = match before_enclosure {
            None => true,
            Some(row) => {
                (row.remote_url.is_none() && audio_url.is_some())
                    || (row.duration.is_none() && clean_duration.is_some())
            }
        };
        if !needs_enclosure_update {
            return Ok(false);
        }
        MediaRepo::upsert_audio_enclosure_in_tx(
            &mut *conn,
            existing_id,
            audio_url,
            clean_duration,
            now,
        )
        .await?;
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, existing_id)
            .execute(&mut *conn)
            .await?;
        Ok(true)
    }

    pub async fn mark_error(&self, id: &str, error: &str) -> Result<()> {
        self.mark_error_by_peer(id, error, SERVER_PEER_ID).await
    }

    pub async fn mark_error_by_peer(&self, id: &str, error: &str, peer_id: &str) -> Result<()> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        sqlx::query(
            "UPDATE feed_sources
             SET last_error = ?,
                 last_error_by = ?,
                 last_polled_at = ?,
                 consecutive_failures = MIN(consecutive_failures + 1, ?)
             WHERE collection_id = ?",
        )
        .bind(error)
        .bind(peer_id)
        .bind(now)
        .bind(MAX_CONSECUTIVE_FAILURES)
        .bind(id)
        .execute(tx.conn())
        .await?;
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, id)
            .execute(tx.conn())
            .await?;
        tx.commit().await?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use url::Url;

    use crate::{
        db::{ActivityAction, ActivityEntityType, ContentType, Db, FeedType, NewItem},
        domain::OpmlOutline,
    };

    #[tokio::test]
    async fn test_polling_backoff_and_cascade_delete() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_repo = db.feeds();

        let feed_url = Url::parse("https://example.com/rss.xml").unwrap();
        let feed = feed_repo.create(FeedType::Rss, &feed_url, Some("Example"), None).await.unwrap();
        assert_eq!(feed.consecutive_failures, 0);

        let active = feed_repo.get_active_for_cooperative_polling().await.unwrap();
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].unread_count, 0);

        // `get_active_for_cooperative_polling` returns all active feeds so `should_peer_poll_feed`
        // is the single source of truth; `mark_error` increments `consecutive_failures` up to cap.
        feed_repo.mark_error(&feed.id, "timeout").await.unwrap();
        let after_err1 = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert_eq!(after_err1.consecutive_failures, 1);
        assert_eq!(after_err1.last_error.as_deref(), Some("timeout"));
        assert_eq!(after_err1.last_error_by.as_deref(), Some("server"));

        let active_after_err = feed_repo.get_active_for_cooperative_polling().await.unwrap();
        assert_eq!(active_after_err.len(), 1);
        assert_eq!(active_after_err[0].consecutive_failures, 1);

        // Increment past cap -> capped at MAX_CONSECUTIVE_FAILURES (20).
        for _ in 0..25 {
            feed_repo.mark_error(&feed.id, "still failing").await.unwrap();
        }
        let capped = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert_eq!(capped.consecutive_failures, crate::db::MAX_CONSECUTIVE_FAILURES);

        // Poll success resets consecutive_failures, last_error, last_error_by.
        feed_repo
            .record_poll_batch(
                &feed.id,
                &crate::db::PollBatch {
                    title: Some("Example".to_owned()),
                    description: None,
                    language: None,
                    items: Vec::new(),
                },
            )
            .await
            .unwrap();
        let recovered = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert_eq!(recovered.consecutive_failures, 0);
        assert!(recovered.last_error.is_none());
        assert!(recovered.last_error_by.is_none());

        let item_url = Url::parse("https://example.com/1").unwrap();
        let item = db
            .items()
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "Cascaded Item",
                url: &item_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        assert!(feed_repo.delete(&feed.id).await.unwrap());
        assert!(db.items().get(&item.id).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn test_archive_feed_stops_polling_and_preserves_items() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_repo = db.feeds();

        let feed_url = Url::parse("https://example.com/archive-feed.xml").unwrap();
        let feed = feed_repo
            .create(FeedType::Rss, &feed_url, Some("Archivable Feed"), None)
            .await
            .unwrap();

        let item_url = Url::parse("https://example.com/archive-item-1").unwrap();
        let item = db
            .items()
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "Preserved Item",
                url: &item_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        // Accumulate failures before archiving so we can verify unarchive & re-subscribe reset them.
        feed_repo.mark_error(&feed.id, "500").await.unwrap();
        feed_repo.mark_error(&feed.id, "500").await.unwrap();
        assert_eq!(feed_repo.get(&feed.id).await.unwrap().unwrap().consecutive_failures, 2);

        assert!(feed_repo.archive(&feed.id).await.unwrap());
        let archived_feed = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert!(!archived_feed.is_active);
        assert_eq!(archived_feed.unread_count, 1);

        let active = feed_repo.get_active_for_cooperative_polling().await.unwrap();
        assert!(active.is_empty());

        let preserved = db.items().get(&item.id).await.unwrap().unwrap();
        assert_eq!(preserved.title, "Preserved Item");

        assert!(feed_repo.unarchive(&feed.id).await.unwrap());
        let unarchived_feed = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert!(unarchived_feed.is_active);
        assert_eq!(unarchived_feed.consecutive_failures, 0);
        assert!(unarchived_feed.last_error.is_none());
        assert!(unarchived_feed.last_error_by.is_none());
        let active_after = feed_repo.get_active_for_cooperative_polling().await.unwrap();
        assert_eq!(active_after.len(), 1);

        feed_repo.mark_error(&feed.id, "503").await.unwrap();
        feed_repo.mark_error(&feed.id, "503").await.unwrap();
        feed_repo.mark_error(&feed.id, "503").await.unwrap();
        assert!(feed_repo.archive(&feed.id).await.unwrap());
        let resubscribed = feed_repo
            .create(FeedType::Rss, &feed_url, Some("Resubscribed Title"), None)
            .await
            .unwrap();
        assert_eq!(resubscribed.id, feed.id);
        assert!(resubscribed.is_active);
        assert_eq!(resubscribed.title.as_deref(), Some("Resubscribed Title"));
        assert_eq!(resubscribed.unread_count, 1);
        assert_eq!(resubscribed.consecutive_failures, 0);
        assert!(resubscribed.last_error.is_none());
        assert!(resubscribed.last_error_by.is_none());
    }

    #[tokio::test]
    async fn test_import_outlines_unarchives_archived_feed_and_records_activity() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_repo = db.feeds();

        let renamed_url = Url::parse("https://example.com/custom-title.xml").unwrap();
        let renamed_feed = feed_repo
            .create(FeedType::Rss, &renamed_url, Some("My Custom Title"), None)
            .await
            .unwrap();

        let item_url = Url::parse("https://example.com/item-1").unwrap();
        db.items()
            .create(&NewItem {
                feed_id: Some(&renamed_feed.id),
                title: "Existing Item",
                url: &item_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        let untitled_url = Url::parse("https://example.com/untitled.xml").unwrap();
        let untitled_feed =
            feed_repo.create(FeedType::Rss, &untitled_url, None, None).await.unwrap();

        feed_repo.mark_error(&renamed_feed.id, "upstream 502").await.unwrap();
        feed_repo.mark_error(&renamed_feed.id, "upstream 502").await.unwrap();
        assert!(feed_repo.archive(&renamed_feed.id).await.unwrap());
        assert!(feed_repo.archive(&untitled_feed.id).await.unwrap());

        let before_activity_id = db.sync().latest_activity_id().await.unwrap();
        let canon_variant_url = Url::parse("HTTPS://EXAMPLE.COM:443/custom-title.xml").unwrap();

        let imported = feed_repo
            .import_outlines(
                vec![
                    OpmlOutline {
                        feed_type: FeedType::Rss,
                        xml_url: canon_variant_url,
                        title: Some("OPML Title".to_string()),
                    },
                    OpmlOutline {
                        feed_type: FeedType::Rss,
                        xml_url: untitled_url,
                        title: Some("Backfilled Title".to_string()),
                    },
                ],
                30,
            )
            .await
            .unwrap();

        assert_eq!(imported.len(), 2);
        assert_eq!(imported[0].id, renamed_feed.id);
        assert!(imported[0].is_active);
        assert_eq!(imported[0].title.as_deref(), Some("My Custom Title"));
        assert_eq!(imported[0].unread_count, 1);
        assert_eq!(imported[0].consecutive_failures, 0);
        assert!(imported[0].last_error.is_none());
        assert!(imported[0].last_error_by.is_none());

        assert_eq!(imported[1].id, untitled_feed.id);
        assert!(imported[1].is_active);
        assert_eq!(imported[1].title.as_deref(), Some("Backfilled Title"));

        let changes = db.sync().list_changes_since(before_activity_id, 10).await.unwrap();
        assert_eq!(changes.len(), 2);
        assert!(changes.iter().all(|c| {
            c.entity_type == ActivityEntityType::Feed && c.action == ActivityAction::Unarchive
        }));
    }
}
