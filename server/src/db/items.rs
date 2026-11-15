use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, SqliteConnection, SqlitePool};
use url::Url;

use super::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, ContentType, EntryKind, InboxState,
    ItemSortField, ItemStatus, ItemStatusFilter, MediaRepo, ReadAtPatch, SortOrder, SyncRepo, Tag,
    TagRepo,
    feeds::resolve_dedup_entry_id,
    sync::{
        STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID, canonicalize_sync_url,
        deterministic_consumption_log_id, deterministic_item_id, item_natural_key,
    },
};
use crate::feeds::arxiv::ArxivParser;

const MAX_SEARCH_TERMS: usize = 16;

macro_rules! item_select_sql {
    ($content_html_expr:literal) => {
        concat!(
            "SELECT i.id,
       (SELECT ce_f.collection_id
        FROM collection_entries ce_f
        JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
        WHERE ce_f.entry_id = i.id
        ORDER BY ce_f.added_at ASC, ce_f.collection_id ASC
        LIMIT 1) AS feed_id,
       (SELECT fs_f.feed_url
        FROM collection_entries ce_f
        JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
        WHERE ce_f.entry_id = i.id
        ORDER BY ce_f.added_at ASC, ce_f.collection_id ASC
        LIMIT 1) AS feed_url,
       (SELECT fs_f.feed_title
        FROM collection_entries ce_f
        JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
        WHERE ce_f.entry_id = i.id
        ORDER BY ce_f.added_at ASC, ce_f.collection_id ASC
        LIMIT 1) AS feed_title,
       i.title,
       COALESCE(i.url, 'https://roche.local/entry/' || i.id) AS url,
       (SELECT group_concat(value, ', ') FROM json_each(i.creators_json)) AS author,
       i.published_at,
       i.created_at,
       i.summary, ",
            $content_html_expr,
            " AS content_html,
       i.thumbnail_url,
       (SELECT m.remote_url
        FROM entry_media m
        WHERE m.entry_id = i.id AND m.media_type = 'audio' AND m.remote_url IS NOT NULL
        ORDER BY m.created_at DESC
        LIMIT 1) AS audio_url,
       COALESCE(ec.content_type, 'article') AS content_type,
       CASE es.inbox_state WHEN 'dismissed' THEN 'archived' ELSE 'unread' END AS status,
       EXISTS (
           SELECT 1 FROM collection_entries ce_fav
           WHERE ce_fav.collection_id = '533b1315-6e2e-57d3-ba85-dc88531c5a0b'
             AND ce_fav.entry_id = i.id
       ) AS is_favorite,
       es.read_at,
       COALESCE(es.progress, 0.0) AS read_progress,
       es.interaction_updated_at,
       (SELECT m.id
        FROM entry_media m
        WHERE m.entry_id = i.id AND m.local_path IS NOT NULL AND m.media_type = 'thumbnail'
        ORDER BY m.created_at DESC
        LIMIT 1) AS thumbnail_media_id,
       (SELECT m.duration
        FROM entry_media m
        WHERE m.entry_id = i.id AND m.duration IS NOT NULL
        ORDER BY CASE WHEN m.local_path IS NOT NULL THEN 0 ELSE 1 END ASC, m.created_at DESC
        LIMIT 1) AS duration,
       COALESCE((
           SELECT json_group_array(name) FROM (
               SELECT e_t.title AS name
               FROM collection_entries ce_t
               JOIN collection_specs cs_t ON cs_t.collection_id = ce_t.collection_id
               JOIN entries e_t ON e_t.id = ce_t.collection_id
               WHERE ce_t.entry_id = i.id
                 AND cs_t.collection_role = 'tag'
                 AND e_t.merged_into_id IS NULL
               ORDER BY e_t.title ASC
           )
       ), '[]') AS tag_names
FROM entries i
JOIN entry_state es ON es.entry_id = i.id
LEFT JOIN entry_content ec ON ec.entry_id = i.id"
        )
    };
}

const ITEM_SELECT_LIST: &str = item_select_sql!(
    "CASE WHEN length(ec.content_html) <= 65536 THEN ec.content_html ELSE NULL END"
);

const ITEM_SELECT_METADATA_ONLY: &str = item_select_sql!("NULL");

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct TagNames(pub Vec<String>);

impl TryFrom<&str> for TagNames {
    type Error = serde_json::Error;

    fn try_from(value: &str) -> std::result::Result<Self, Self::Error> {
        serde_json::from_str(value).map(Self)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Item {
    pub id: String,
    pub feed_id: Option<String>,
    #[serde(default)]
    pub feed_url: Option<String>,
    pub feed_title: Option<String>,
    pub title: String,
    #[sqlx(try_from = "&'a str")]
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub summary: Option<String>,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub audio_url: Option<String>,
    pub content_type: ContentType,
    pub status: ItemStatus,
    pub is_favorite: bool,
    pub read_at: Option<DateTime<Utc>>,
    pub read_progress: f64,
    #[serde(default)]
    pub interaction_updated_at: Option<DateTime<Utc>>,
    pub thumbnail_media_id: Option<String>,
    pub duration: Option<i64>,
    #[serde(default)]
    #[sqlx(try_from = "&'a str")]
    pub tag_names: TagNames,
}

#[cfg(test)]
#[derive(Debug, Clone, Copy)]
pub struct NewItem<'a> {
    pub feed_id: Option<&'a str>,
    pub title: &'a str,
    pub url: &'a Url,
    pub author: Option<&'a str>,
    pub published_at: Option<DateTime<Utc>>,
    pub summary: Option<&'a str>,
    pub content_type: ContentType,
}

#[derive(Debug, Clone, Copy)]
pub struct ManualItemInput<'a> {
    pub url: &'a Url,
    pub title: &'a str,
    pub summary: Option<&'a str>,
    pub content_html: Option<&'a str>,
    pub thumbnail_url: Option<&'a str>,
    pub audio_url: Option<&'a str>,
    pub duration: Option<i64>,
    pub content_type: ContentType,
    pub tags: &'a [String],
}

#[derive(Debug, Clone, Serialize)]
pub struct ItemPage {
    pub items: Vec<Item>,
    pub page: usize,
    pub per_page: usize,
    pub has_more: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchTerm {
    pub fts_clause: String,
    pub like_pattern: String,
}

fn deserialize_optional_rfc3339<'de, D>(
    deserializer: D,
) -> std::result::Result<Option<DateTime<Utc>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let opt = Option::<String>::deserialize(deserializer)?;
    let Some(raw) = opt else {
        return Ok(None);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(serde::de::Error::custom("updated_after cannot be empty"));
    }
    if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
        return Ok(Some(dt.with_timezone(&Utc)));
    }
    if trimmed.contains(' ')
        && let Ok(dt) = DateTime::parse_from_rfc3339(&trimmed.replace(' ', "+"))
    {
        return Ok(Some(dt.with_timezone(&Utc)));
    }
    Err(serde::de::Error::custom(format!(
        "Invalid RFC 3339 timestamp for updated_after: '{trimmed}'"
    )))
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct ItemFilter {
    pub status: Option<ItemStatusFilter>,
    pub feed_id: Option<String>,
    pub tag_id: Option<String>,
    pub content_type: Option<ContentType>,
    pub is_favorite: Option<bool>,
    pub is_read: Option<bool>,
    pub include_content: Option<bool>,
    pub search: Option<String>,
    #[serde(default, deserialize_with = "deserialize_optional_rfc3339")]
    pub updated_after: Option<DateTime<Utc>>,
    #[serde(default)]
    pub updated_after_item_id: Option<String>,
    pub sort: Option<ItemSortField>,
    pub order: Option<SortOrder>,
    pub page: Option<usize>,
    pub per_page: Option<usize>,
    #[serde(default)]
    pub sort_updated_asc: bool,
}

impl ItemFilter {
    #[must_use]
    pub fn escape_like(input: &str) -> String {
        let mut out = String::with_capacity(input.len());
        for ch in input.chars() {
            if matches!(ch, '\\' | '%' | '_') {
                out.push('\\');
            }
            out.push(ch);
        }
        out
    }

    #[must_use]
    pub fn tokenize_search_terms(input: &str) -> Vec<SearchTerm> {
        let mut terms = Vec::new();
        let mut chars = input.chars().peekable();

        while let Some(&ch) = chars.peek() {
            if ch == '"' {
                chars.next();
                let mut phrase_buf = String::new();
                let mut closed = false;
                for next_ch in chars.by_ref() {
                    if next_ch == '"' {
                        closed = true;
                        break;
                    }
                    phrase_buf.push(next_ch);
                }
                let words = Self::extract_word_tokens(&phrase_buf);
                if closed && words.len() > 1 {
                    let joined = words.join(" ");
                    terms.push(SearchTerm {
                        fts_clause: format!("\"{joined}\""),
                        like_pattern: format!("%{}%", Self::escape_like(&joined)),
                    });
                } else {
                    for word in words {
                        terms.push(SearchTerm {
                            fts_clause: format!("(\"{word}\" OR \"{word}\"*)"),
                            like_pattern: format!("%{}%", Self::escape_like(word)),
                        });
                    }
                }
            } else {
                let mut unquoted_buf = String::new();
                while let Some(&next_ch) = chars.peek() {
                    if next_ch == '"' {
                        break;
                    }
                    unquoted_buf.push(next_ch);
                    chars.next();
                }
                for word in Self::extract_word_tokens(&unquoted_buf) {
                    terms.push(SearchTerm {
                        fts_clause: format!("(\"{word}\" OR \"{word}\"*)"),
                        like_pattern: format!("%{}%", Self::escape_like(word)),
                    });
                }
            }
        }

        terms.truncate(MAX_SEARCH_TERMS);
        terms
    }

    #[must_use]
    pub fn build_fts_query(input: &str) -> Option<String> {
        let terms = Self::tokenize_search_terms(input);
        if terms.is_empty() {
            None
        } else {
            Some(terms.into_iter().map(|t| t.fts_clause).collect::<Vec<_>>().join(" AND "))
        }
    }

    fn extract_word_tokens(segment: &str) -> Vec<&str> {
        segment
            .trim_start_matches('#')
            .split(|c: char| !c.is_alphanumeric())
            .filter(|tok| !tok.is_empty())
            .collect()
    }
}

/// Auto-logs feed completion (`consumed`) when:
/// - a `podcast_episode` reaches audio playback completion (`progress >= 0.98`, or archived with `>= 0.9`), or
/// - an `article` or `paper` is archived (`inbox_state = 'dismissed'`) with `progress >= 0.9`.
///
/// Also engages any parent `podcast`, `publication`, or `channel` collection so it surfaces in ranked lists.
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

    ensure_consumed_log_and_parent_engagement_in_tx(conn, entry_id, ts).await
}

pub(super) async fn ensure_consumed_log_and_parent_engagement_in_tx(
    conn: &mut SqliteConnection,
    entry_id: &str,
    ts: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let already_consumed: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM consumption_log WHERE entry_id = ? AND status = 'consumed' LIMIT 1",
    )
    .bind(entry_id)
    .fetch_optional(&mut *conn)
    .await?;

    if already_consumed.is_none() {
        let log_id = deterministic_consumption_log_id(entry_id, ts.timestamp_millis(), "consumed");
        sqlx::query(
            "INSERT OR IGNORE INTO consumption_log (id, entry_id, ts, status, notes, created_at)
             VALUES (?, ?, ?, 'consumed', NULL, ?)",
        )
        .bind(&log_id)
        .bind(entry_id)
        .bind(ts)
        .bind(ts)
        .execute(&mut *conn)
        .await?;

        // Auto-promote out of any Want queue on consumption (recording sync tombstones)
        super::catalog::remove_from_want_queues_in_tx(conn, entry_id, ts).await?;
    }

    let parent_ids: Vec<String> = sqlx::query_scalar(
        "SELECT ce.collection_id
         FROM collection_entries ce
         JOIN entries pe ON pe.id = ce.collection_id
         WHERE ce.entry_id = ?
           AND pe.merged_into_id IS NULL
           AND pe.kind IN ('podcast', 'publication', 'channel')",
    )
    .bind(entry_id)
    .fetch_all(&mut *conn)
    .await?;

    for parent_id in parent_ids {
        let parent_consumed: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM consumption_log WHERE entry_id = ? AND status = 'consumed' LIMIT 1",
        )
        .bind(&parent_id)
        .fetch_optional(&mut *conn)
        .await?;
        if parent_consumed.is_none() {
            let parent_log_id =
                deterministic_consumption_log_id(&parent_id, ts.timestamp_millis(), "consumed");
            sqlx::query(
                "INSERT OR IGNORE INTO consumption_log (id, entry_id, ts, status, notes, created_at)
                 VALUES (?, ?, ?, 'consumed', NULL, ?)",
            )
            .bind(&parent_log_id)
            .bind(&parent_id)
            .bind(ts)
            .bind(ts)
            .execute(&mut *conn)
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
    if is_favorite {
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
             VALUES (?, ?, ?)",
        )
        .bind(STARRED_COLLECTION_ID)
        .bind(entry_id)
        .bind(now)
        .execute(&mut *conn)
        .await?;
    } else {
        sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
            .bind(STARRED_COLLECTION_ID)
            .bind(entry_id)
            .execute(&mut *conn)
            .await?;
    }
    Ok(())
}

pub struct ItemRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

impl<'a> ItemRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    #[cfg(test)]
    pub async fn create(&self, new_item: &NewItem<'_>) -> Result<Item> {
        let feed_url: Option<String> = match new_item.feed_id {
            Some(fid) => {
                sqlx::query_scalar("SELECT feed_url FROM feed_sources WHERE collection_id = ?")
                    .bind(fid)
                    .fetch_optional(self.pool)
                    .await?
            }
            None => None,
        };
        let id = deterministic_item_id(feed_url.as_deref(), new_item.url.as_str());
        let canon_url = canonicalize_sync_url(new_item.url.as_str());
        let now = SyncRepo::now_utc();
        let entry_kind = EntryKind::from_content_type(new_item.content_type);
        let creators_json = new_item
            .author
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|a| serde_json::to_string(&[a]).ok())
            .unwrap_or_else(|| "[]".to_owned());

        let mut tx = self.pool.begin().await?;

        sqlx::query(
            "INSERT INTO entries
             (id, kind, title, creators_json, url, published_at, summary, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(entry_kind)
        .bind(new_item.title)
        .bind(&creators_json)
        .bind(new_item.url.as_str())
        .bind(new_item.published_at)
        .bind(new_item.summary)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT INTO entry_content (entry_id, content_html, content_type)
             VALUES (?, NULL, ?)",
        )
        .bind(&id)
        .bind(new_item.content_type)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT INTO entry_state (entry_id, inbox_state, progress, updated_at)
             VALUES (?, 'unread', 0.0, ?)",
        )
        .bind(&id)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('url', ?, ?)",
        )
        .bind(&canon_url)
        .bind(&id)
        .execute(&mut *tx)
        .await?;

        if let Some(fid) = new_item.feed_id {
            sqlx::query(
                "INSERT INTO collection_entries (collection_id, entry_id, added_at)
                 VALUES (?, ?, ?)",
            )
            .bind(fid)
            .bind(&id)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }

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
            content_type: ContentType::Article,
            tags,
        })
        .await
    }

    #[allow(clippy::too_many_lines)]
    pub async fn save_manual_input_with_tags(
        &self,
        input: &ManualItemInput<'_>,
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        let det_item_id = deterministic_item_id(None, input.url.as_str());
        let canon_url = canonicalize_sync_url(input.url.as_str());
        let arxiv_id = ArxivParser::extract_arxiv_id(input.url.as_str());
        let now = SyncRepo::now_utc();
        let clean_duration = input.duration.filter(|&d| d > 0);
        let entry_kind = EntryKind::from_content_type(input.content_type);
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(
            &mut tx,
            ActivityEntityType::Item,
            &item_natural_key(None, input.url.as_str()),
        )
        .await?;

        // Upfront deduplication: if a feed/catalog entry already owns this URL/arXiv ID (or a
        // catalog-only entry exists with inbox_state = 'none'), promote it to unread in inbox and
        // enrich its content rather than creating a duplicate entry.
        let existing_dedup =
            resolve_dedup_entry_id(&mut tx, &det_item_id, &canon_url, arxiv_id.as_deref()).await?;
        let existing_inbox: Option<InboxState> = match existing_dedup.as_deref() {
            Some(eid) => {
                sqlx::query_scalar("SELECT inbox_state FROM entry_state WHERE entry_id = ?")
                    .bind(eid)
                    .fetch_optional(&mut *tx)
                    .await?
            }
            None => None,
        };

        let final_item_id = if let Some(existing_id) = existing_dedup
            && (existing_id != det_item_id
                || !matches!(existing_inbox, Some(InboxState::Unread | InboxState::Dismissed)))
        {
            sqlx::query(
                "UPDATE entries
                 SET title = CASE WHEN ? = ? THEN ? ELSE title END,
                     summary = COALESCE(summary, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     created_at = CASE WHEN ? = ? THEN ? ELSE created_at END,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(&existing_id)
            .bind(&det_item_id)
            .bind(input.title)
            .bind(input.summary)
            .bind(input.thumbnail_url)
            .bind(&existing_id)
            .bind(&det_item_id)
            .bind(now)
            .bind(now)
            .bind(&existing_id)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = COALESCE(entry_content.content_html, excluded.content_html)",
            )
            .bind(&existing_id)
            .bind(input.content_html)
            .bind(input.content_type)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO entry_state (entry_id, inbox_state, progress, interaction_updated_at, updated_at)
                 VALUES (?, 'unread', 0.0, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     inbox_state = 'unread',
                     read_at = NULL,
                     interaction_updated_at = excluded.interaction_updated_at,
                     updated_at = excluded.updated_at",
            )
            .bind(&existing_id)
            .bind(now)
            .bind(now)
            .execute(&mut *tx)
            .await?;

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
            .bind(input.url.as_str())
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
                 VALUES (?, 'unread', 0.0, ?)",
            )
            .bind(&det_item_id)
            .bind(now)
            .execute(&mut *tx)
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

        let item: Item =
            sqlx::query_as(concat!(item_select_sql!("ec.content_html"), " WHERE i.id = ?"))
                .bind(&final_item_id)
                .fetch_one(&mut *tx)
                .await?;

        tx.commit().await?;
        Ok((item, attached_tags))
    }

    pub async fn get(&self, id: &str) -> Result<Option<Item>> {
        Ok(sqlx::query_as::<_, Item>(concat!(
            item_select_sql!("ec.content_html"),
            " WHERE i.id = ? AND i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')"
        ))
        .bind(id)
        .fetch_optional(self.pool)
        .await?)
    }

    pub async fn list(
        &self,
        status: Option<ItemStatus>,
        feed_id: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<Item>> {
        let mut qb = QueryBuilder::new(ITEM_SELECT_LIST);
        qb.push(" WHERE i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')");
        if let Some(s) = status {
            qb.push(" AND es.inbox_state = ").push_bind(InboxState::from(s));
        }
        if let Some(f) = feed_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_f WHERE ce_f.entry_id = i.id AND ce_f.collection_id = ",
            )
            .push_bind(f)
            .push(")");
        }
        qb.push(" ORDER BY COALESCE(i.published_at, i.created_at) DESC, i.id DESC");
        if let Some(l) = limit {
            qb.push(" LIMIT ").push_bind(i64::try_from(l).unwrap_or(i64::MAX));
        }

        Ok(qb.build_query_as::<Item>().fetch_all(self.pool).await?)
    }

    #[cfg(test)]
    pub async fn list_filtered(&self, filter: &ItemFilter) -> Result<Vec<Item>> {
        Ok(self.list_filtered_page(filter).await?.items)
    }

    pub async fn list_filtered_page(&self, filter: &ItemFilter) -> Result<ItemPage> {
        let trimmed_search = filter.search.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let search_terms = trimmed_search.map_or_default(ItemFilter::tokenize_search_terms);
        let fts_query = trimmed_search.and_then(ItemFilter::build_fts_query);

        let base_select = if filter.include_content == Some(false) {
            ITEM_SELECT_METADATA_ONLY
        } else {
            ITEM_SELECT_LIST
        };
        let mut qb = QueryBuilder::new(base_select);
        if let Some(fts_match) = &fts_query {
            qb.push(
                " LEFT JOIN (SELECT rowid AS fts_rowid, bm25(entries_fts, 0.0, 10.0, 5.0, 2.0, 1.0) AS fts_rank FROM entries_fts WHERE entries_fts MATCH ",
            )
            .push_bind(fts_match.clone())
            .push(") fts ON fts.fts_rowid = i.rowid");
        }
        qb.push(" WHERE i.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')");

        if let Some(s) = filter.status.and_then(ItemStatusFilter::into_status) {
            qb.push(" AND es.inbox_state = ").push_bind(InboxState::from(s));
        }
        if let Some(is_read) = filter.is_read {
            if is_read {
                qb.push(" AND es.read_at IS NOT NULL");
            } else {
                qb.push(" AND es.read_at IS NULL");
            }
        }
        if let Some(f) = &filter.feed_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_ff WHERE ce_ff.entry_id = i.id AND ce_ff.collection_id = ",
            )
            .push_bind(f)
            .push(")");
        }
        if let Some(t) = &filter.tag_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM collection_entries ce_tf WHERE ce_tf.entry_id = i.id AND ce_tf.collection_id = ",
            )
            .push_bind(t)
            .push(")");
        }
        if let Some(ct) = filter.content_type {
            qb.push(" AND COALESCE(ec.content_type, 'article') = ").push_bind(ct);
        }
        if let Some(fav) = filter.is_favorite {
            if fav {
                qb.push(
                    " AND EXISTS (SELECT 1 FROM collection_entries ce_fav WHERE ce_fav.collection_id = ",
                )
                .push_bind(STARRED_COLLECTION_ID)
                .push(" AND ce_fav.entry_id = i.id)");
            } else {
                qb.push(
                    " AND NOT EXISTS (SELECT 1 FROM collection_entries ce_fav WHERE ce_fav.collection_id = ",
                )
                .push_bind(STARRED_COLLECTION_ID)
                .push(" AND ce_fav.entry_id = i.id)");
            }
        }
        Self::append_updated_after_where_clause(&mut qb, filter);
        if let Some(search) = trimmed_search {
            Self::append_search_where_clause(&mut qb, search, &search_terms);
        }

        let sort_by_relevance =
            fts_query.is_some() && matches!(filter.sort, None | Some(ItemSortField::Relevance));
        if filter.sort_updated_asc {
            qb.push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) ASC, i.id ASC");
        } else if sort_by_relevance {
            let rank_dir = match filter.order.unwrap_or_default() {
                SortOrder::Desc => "ASC",
                SortOrder::Asc => "DESC",
            };
            qb.push(format_args!(
                " ORDER BY CASE WHEN fts.fts_rank IS NULL THEN 1 ELSE 0 END ASC, fts.fts_rank {rank_dir}, COALESCE(i.published_at, i.created_at) DESC, i.id DESC"
            ));
        } else {
            let sort_col = filter.sort.unwrap_or_default().as_sql_expr();
            let sort_dir = filter.order.unwrap_or_default().as_sql();
            qb.push(format_args!(" ORDER BY {sort_col} {sort_dir}, i.id DESC"));
        }

        let per_page = filter.per_page.unwrap_or(50).clamp(1, 200);
        let page = filter.page.unwrap_or(1).max(1);
        let offset = page.saturating_sub(1).saturating_mul(per_page);
        let fetch_limit = per_page.saturating_add(1);
        qb.push(" LIMIT ")
            .push_bind(i64::try_from(fetch_limit).unwrap_or(51))
            .push(" OFFSET ")
            .push_bind(i64::try_from(offset).unwrap_or(0));

        let mut items = qb.build_query_as::<Item>().fetch_all(self.pool).await?;
        let has_more = items.len() > per_page;
        items.truncate(per_page);
        Ok(ItemPage { items, page, per_page, has_more })
    }

    fn append_updated_after_where_clause(qb: &mut QueryBuilder<sqlx::Sqlite>, filter: &ItemFilter) {
        let Some(ts) = filter.updated_after else {
            return;
        };
        if let Some(after_id) =
            filter.updated_after_item_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            qb.push(
                " AND (strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(ts)
            .push(
                ") OR (strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) = strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(ts)
            .push(") AND i.id > ")
            .push_bind(after_id)
            .push("))");
        } else {
            qb.push(
                " AND strftime('%Y-%m-%dT%H:%M:%fZ', i.updated_at) > strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(ts)
            .push(")");
        }
    }

    fn append_search_where_clause(
        qb: &mut QueryBuilder<sqlx::Sqlite>,
        raw_search: &str,
        search_terms: &[SearchTerm],
    ) {
        if search_terms.is_empty() {
            let pattern = format!("%{}%", ItemFilter::escape_like(raw_search));
            qb.push(" AND (i.title LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.summary, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.creators_json, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(ec.content_html, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM collection_entries ce_fs JOIN feed_sources fs_s ON fs_s.collection_id = ce_fs.collection_id WHERE ce_fs.entry_id = i.id AND COALESCE(fs_s.feed_title, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\') OR EXISTS (SELECT 1 FROM collection_entries ce_ts JOIN collection_specs cs_ts ON cs_ts.collection_id = ce_ts.collection_id JOIN entries e_ts ON e_ts.id = ce_ts.collection_id WHERE ce_ts.entry_id = i.id AND cs_ts.collection_role = 'tag' AND e_ts.title LIKE ")
                .push_bind(pattern)
                .push(" ESCAPE '\\'))");
        } else if let [term] = search_terms {
            qb.push(" AND (fts.fts_rowid IS NOT NULL OR EXISTS (SELECT 1 FROM collection_entries ce_fs JOIN feed_sources fs_s ON fs_s.collection_id = ce_fs.collection_id WHERE ce_fs.entry_id = i.id AND COALESCE(fs_s.feed_title, '') LIKE ")
                .push_bind(term.like_pattern.clone())
                .push(" ESCAPE '\\') OR EXISTS (SELECT 1 FROM collection_entries ce_ts JOIN collection_specs cs_ts ON cs_ts.collection_id = ce_ts.collection_id JOIN entries e_ts ON e_ts.id = ce_ts.collection_id WHERE ce_ts.entry_id = i.id AND cs_ts.collection_role = 'tag' AND e_ts.title LIKE ")
                .push_bind(term.like_pattern.clone())
                .push(" ESCAPE '\\'))");
        } else {
            qb.push(" AND (fts.fts_rowid IS NOT NULL OR (");
            for (idx, term) in search_terms.iter().enumerate() {
                if idx > 0 {
                    qb.push(" AND ");
                }
                qb.push("(i.rowid IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ")
                    .push_bind(term.fts_clause.clone())
                    .push(") OR EXISTS (SELECT 1 FROM collection_entries ce_fs JOIN feed_sources fs_s ON fs_s.collection_id = ce_fs.collection_id WHERE ce_fs.entry_id = i.id AND COALESCE(fs_s.feed_title, '') LIKE ")
                    .push_bind(term.like_pattern.clone())
                    .push(" ESCAPE '\\') OR EXISTS (SELECT 1 FROM collection_entries ce_ts JOIN collection_specs cs_ts ON cs_ts.collection_id = ce_ts.collection_id JOIN entries e_ts ON e_ts.id = ce_ts.collection_id WHERE ce_ts.entry_id = i.id AND cs_ts.collection_role = 'tag' AND e_ts.title LIKE ")
                    .push_bind(term.like_pattern.clone())
                    .push(" ESCAPE '\\'))");
            }
            qb.push("))");
        }
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
        type ExistingRow = (EntryKind, InboxState, bool, f64, Option<DateTime<Utc>>);
        let now = SyncRepo::now_utc();

        let existing: Option<ExistingRow> = sqlx::query_as(
            "SELECT e.kind,
                    es.inbox_state,
                    EXISTS (
                        SELECT 1 FROM collection_entries ce_fav
                        WHERE ce_fav.collection_id = ? AND ce_fav.entry_id = e.id
                    ),
                    es.progress,
                    es.read_at
             FROM entries e
             JOIN entry_state es ON es.entry_id = e.id
             WHERE e.id = ? AND e.merged_into_id IS NULL AND es.inbox_state IN ('unread', 'dismissed')",
        )
        .bind(STARRED_COLLECTION_ID)
        .bind(id)
        .fetch_optional(self.pool)
        .await?;

        let Some((entry_kind, cur_inbox, current_favorite, current_progress, current_read_at)) =
            existing
        else {
            return Ok(false);
        };

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

        sqlx::query(
            "UPDATE entry_state
             SET inbox_state = ?,
                 read_at = ?,
                 progress = ?,
                 interaction_updated_at = ?,
                 updated_at = ?
             WHERE entry_id = ?",
        )
        .bind(target_inbox)
        .bind(target_read_at)
        .bind(target_progress)
        .bind(now)
        .bind(now)
        .bind(id)
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

    #[allow(clippy::too_many_lines)]
    pub async fn archive_batch(&self, scope: &ArchiveBatchScope) -> Result<u64> {
        const MAX_BATCH_IDS: usize = 5_000;
        const CHUNK_SIZE: usize = 400;

        let candidate_ids: Vec<String> = match scope {
            ArchiveBatchScope::Items { item_ids, feed_id } => {
                let mut unique_ids = Vec::new();
                let mut seen = HashSet::new();
                for raw_id in item_ids {
                    let trimmed = raw_id.trim();
                    if !trimmed.is_empty() && seen.insert(trimmed) {
                        unique_ids.push(trimmed);
                        if unique_ids.len() >= MAX_BATCH_IDS {
                            break;
                        }
                    }
                }
                if unique_ids.is_empty() {
                    return Ok(0);
                }
                let mut matched = Vec::new();
                for chunk in unique_ids.chunks(CHUNK_SIZE) {
                    let mut qb = QueryBuilder::new(
                        "SELECT es.entry_id FROM entry_state es
                         JOIN entries e ON e.id = es.entry_id
                         WHERE e.merged_into_id IS NULL
                           AND es.inbox_state = 'unread'
                           AND es.entry_id IN (",
                    );
                    let mut sep = qb.separated(", ");
                    for id in chunk {
                        sep.push_bind(*id);
                    }
                    sep.push_unseparated(")");
                    if let Some(fid) = feed_id.as_deref() {
                        qb.push(
                            " AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.entry_id = es.entry_id AND ce.collection_id = ",
                        )
                        .push_bind(fid)
                        .push(")");
                    }
                    let rows: Vec<String> = qb.build_query_scalar().fetch_all(self.pool).await?;
                    matched.extend(rows);
                }
                matched
            }
            ArchiveBatchScope::Feed { feed_id, unread_only } => {
                let sql = if *unread_only {
                    "SELECT es.entry_id FROM entry_state es
                     JOIN entries e ON e.id = es.entry_id
                     WHERE e.merged_into_id IS NULL
                       AND es.inbox_state = 'unread'
                       AND es.read_at IS NULL
                       AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.entry_id = es.entry_id AND ce.collection_id = ?)
                     LIMIT ?"
                } else {
                    "SELECT es.entry_id FROM entry_state es
                     JOIN entries e ON e.id = es.entry_id
                     WHERE e.merged_into_id IS NULL
                       AND es.inbox_state = 'unread'
                       AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.entry_id = es.entry_id AND ce.collection_id = ?)
                     LIMIT ?"
                };
                sqlx::query_scalar(sql)
                    .bind(feed_id.as_str())
                    .bind(i64::try_from(MAX_BATCH_IDS).unwrap_or(5_000))
                    .fetch_all(self.pool)
                    .await?
            }
            ArchiveBatchScope::AllUnread => {
                sqlx::query_scalar(
                    "SELECT es.entry_id FROM entry_state es
                     JOIN entries e ON e.id = es.entry_id
                     WHERE e.merged_into_id IS NULL
                       AND es.inbox_state = 'unread'
                       AND es.read_at IS NULL
                     LIMIT ?",
                )
                .bind(i64::try_from(MAX_BATCH_IDS).unwrap_or(5_000))
                .fetch_all(self.pool)
                .await?
            }
        };

        if candidate_ids.is_empty() {
            return Ok(0);
        }

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let mut archived_ids = Vec::with_capacity(candidate_ids.len());

        for chunk in candidate_ids.chunks(CHUNK_SIZE) {
            let mut qb = QueryBuilder::new(
                "UPDATE entry_state SET inbox_state = 'dismissed', read_at = COALESCE(read_at, ",
            );
            qb.push_bind(now)
                .push("), interaction_updated_at = ")
                .push_bind(now)
                .push(", updated_at = ")
                .push_bind(now)
                .push(" WHERE inbox_state = 'unread' AND entry_id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id.as_str());
            }
            sep.push_unseparated(") RETURNING entry_id");
            let updated: Vec<String> = qb.build_query_scalar().fetch_all(&mut *tx).await?;
            archived_ids.extend(updated);
        }

        for chunk in archived_ids.chunks(CHUNK_SIZE) {
            let mut qb = QueryBuilder::new("UPDATE entries SET updated_at = ");
            qb.push_bind(now).push(" WHERE id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id.as_str());
            }
            sep.push_unseparated(")");
            qb.build().execute(&mut *tx).await?;
        }

        // Note: batch-archived items whose progress had already reached >= 0.9 are auto-logged,
        // while unopened / skimmed bulk-archived items (progress < 0.9) never touch consumption_log.
        for id in &archived_ids {
            let row: Option<(EntryKind, f64, Option<DateTime<Utc>>)> = sqlx::query_as(
                "SELECT e.kind, es.progress, es.read_at
                 FROM entries e
                 JOIN entry_state es ON es.entry_id = e.id
                 WHERE e.id = ?",
            )
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some((kind, prog, r_at)) = row
                && prog >= 0.9
            {
                maybe_auto_log_feed_completion_in_tx(
                    &mut tx,
                    id,
                    kind,
                    InboxState::Dismissed,
                    prog,
                    r_at.unwrap_or(now),
                )
                .await?;
            }
        }

        for chunk in archived_ids.chunks(CHUNK_SIZE) {
            let mut qb = QueryBuilder::new(
                "INSERT INTO activity_log (entity_type, entity_id, action, timestamp) ",
            );
            qb.push_values(chunk, |mut b, id| {
                b.push_bind(ActivityEntityType::Item)
                    .push_bind(id.as_str())
                    .push_bind(ActivityAction::Archive)
                    .push_bind(now);
            });
            qb.build().execute(&mut *tx).await?;
        }

        tx.commit().await?;
        Ok(u64::try_from(archived_ids.len()).unwrap_or(u64::MAX))
    }

    pub async fn normalize_legacy_html_summaries(&self) -> Result<u64> {
        use crate::content::fetcher::{HtmlSanitizer, MAX_SUMMARY_CHARS};

        let candidates: Vec<(String, String, String)> = sqlx::query_as(
            "SELECT e.id, COALESCE(e.url, ''), e.summary
             FROM entries e
             LEFT JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.summary IS NOT NULL
               AND (
                 length(e.summary) > 320
                 OR (
                   (ec.content_html IS NULL OR ec.content_html = '' OR e.summary = ec.content_html)
                   AND (e.summary LIKE '%<%' OR e.summary LIKE '%&%;%')
                 )
               )",
        )
        .fetch_all(self.pool)
        .await?;

        if candidates.is_empty() {
            return Ok(0);
        }

        let normalized: Vec<(String, String, Option<String>)> =
            tokio::task::spawn_blocking(move || {
                candidates
                    .into_iter()
                    .filter_map(|(id, url_str, raw_summary)| {
                        let excerpt =
                            HtmlSanitizer::plain_text_excerpt(&raw_summary, MAX_SUMMARY_CHARS);
                        if excerpt == raw_summary {
                            None
                        } else {
                            let base_url = Url::parse(&url_str).ok();
                            let thumb = HtmlSanitizer::extract_first_image_url_with_base(
                                &raw_summary,
                                base_url.as_ref(),
                            );
                            Some((id, excerpt, thumb))
                        }
                    })
                    .collect()
            })
            .await?;

        if normalized.is_empty() {
            return Ok(0);
        }

        let count = u64::try_from(normalized.len()).unwrap_or(0);
        let mut tx = self.pool.begin().await?;
        for (id, excerpt, thumb) in normalized {
            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 SELECT id, summary, 'article' FROM entries WHERE id = ?
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = COALESCE(NULLIF(entry_content.content_html, ''), (SELECT summary FROM entries WHERE id = ?))",
            )
            .bind(&id)
            .bind(&id)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "UPDATE entries
                 SET thumbnail_url = COALESCE(thumbnail_url, ?),
                     summary = ?
                 WHERE id = ?",
            )
            .bind(thumb)
            .bind(&excerpt)
            .bind(&id)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok(count)
    }

    pub async fn backfill_missing_thumbnails(&self) -> Result<u64> {
        use crate::{content::fetcher::HtmlSanitizer, feeds::youtube::YouTubeParser};

        let candidates: Vec<(String, String, Option<String>, Option<String>)> = sqlx::query_as(
            "SELECT e.id, COALESCE(e.url, ''), ec.content_html, e.thumbnail_url
             FROM entries e
             LEFT JOIN entry_content ec ON ec.entry_id = e.id
             WHERE (
                 e.thumbnail_url IS NULL
                 AND (
                   (ec.content_html IS NOT NULL AND (ec.content_html LIKE '%<img%' OR ec.content_html LIKE '%<IMG%'))
                   OR e.url LIKE '%youtube.com/%'
                   OR e.url LIKE '%youtu.be/%'
                 )
               )
               OR e.thumbnail_url LIKE '%.svg'
               OR e.thumbnail_url LIKE '%.svg?%'
               OR e.thumbnail_url LIKE '%/images/core/emoji/%'
               OR e.thumbnail_url LIKE '%/wpcom-smileys/%'
               OR e.thumbnail_url LIKE '%/twemoji/%'",
        )
        .fetch_all(self.pool)
        .await?;

        if candidates.is_empty() {
            return Ok(0);
        }

        let updates: Vec<(String, Option<String>)> = tokio::task::spawn_blocking(move || {
            candidates
                .into_iter()
                .filter_map(|(id, url_str, html_opt, old_thumb)| {
                    let base_url = Url::parse(&url_str).ok();
                    let new_thumb = html_opt
                        .as_deref()
                        .and_then(|html| {
                            HtmlSanitizer::extract_first_image_url_with_base(
                                html,
                                base_url.as_ref(),
                            )
                        })
                        .or_else(|| {
                            base_url
                                .as_ref()
                                .and_then(YouTubeParser::extract_video_id)
                                .map(|vid| format!("https://i.ytimg.com/vi/{vid}/hqdefault.jpg"))
                        });
                    (new_thumb != old_thumb).then_some((id, new_thumb))
                })
                .collect()
        })
        .await?;

        if updates.is_empty() {
            return Ok(0);
        }

        let count = u64::try_from(updates.len()).unwrap_or(0);
        let mut tx = self.pool.begin().await?;
        for (id, thumb) in updates {
            sqlx::query("UPDATE entries SET thumbnail_url = ? WHERE id = ?")
                .bind(thumb)
                .bind(&id)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok(count)
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, Some(ItemStatus::Unread), None, None, ReadAtPatch::Unchanged).await
    }

    pub(crate) async fn entry_has_catalog_history_in_tx(
        conn: &mut SqliteConnection,
        entry_id: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        let quoted_id_pattern = format!("%\"{entry_id}\"%");
        let preserved: Option<i64> = sqlx::query_scalar(
            "SELECT 1
             WHERE EXISTS (SELECT 1 FROM consumption_log WHERE entry_id = ?)
                OR EXISTS (SELECT 1 FROM comparisons WHERE ordering_json LIKE ?)
                OR EXISTS (
                    SELECT 1 FROM collection_entries ce
                    JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                    WHERE ce.entry_id = ? AND cs.collection_role IN ('list', 'want')
                )
                OR EXISTS (SELECT 1 FROM collection_generators WHERE current_entry_id = ?)",
        )
        .bind(entry_id)
        .bind(&quoted_id_pattern)
        .bind(entry_id)
        .bind(entry_id)
        .fetch_optional(&mut *conn)
        .await?;
        Ok(preserved.is_some())
    }

    pub(crate) async fn delete_inbox_item_preserving_history_in_tx(
        conn: &mut SqliteConnection,
        item_id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        if Self::entry_has_catalog_history_in_tx(&mut *conn, item_id).await? {
            let res = sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            if res.rows_affected() == 0 {
                return Ok(false);
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
            sqlx::query("DELETE FROM entry_content WHERE entry_id = ?")
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            sqlx::query("DELETE FROM entry_media WHERE entry_id = ? AND local_path IS NOT NULL")
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            sqlx::query(
                "DELETE FROM collection_entries
                 WHERE entry_id = ?
                   AND (
                       collection_id IN (?, ?)
                       OR collection_id IN (
                           SELECT collection_id FROM collection_specs WHERE collection_role = 'tag'
                       )
                   )",
            )
            .bind(item_id)
            .bind(STARRED_COLLECTION_ID)
            .bind(UP_NEXT_COLLECTION_ID)
            .execute(&mut *conn)
            .await?;
            Ok(true)
        } else {
            let res = sqlx::query("DELETE FROM entries WHERE id = ?")
                .bind(item_id)
                .execute(&mut *conn)
                .await?;
            Ok(res.rows_affected() > 0)
        }
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let existing_keys: Option<(Option<String>, Option<String>)> = sqlx::query_as(
            "SELECT e.url,
                    (SELECT fs.feed_url
                     FROM collection_entries ce
                     JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                     WHERE ce.entry_id = e.id
                     ORDER BY ce.added_at ASC, ce.collection_id ASC
                     LIMIT 1)
             FROM entries e
             JOIN entry_state es ON es.entry_id = e.id
             WHERE e.id = ? AND es.inbox_state IN ('unread', 'dismissed')",
        )
        .bind(id)
        .fetch_optional(self.pool)
        .await?;
        let Some((item_url_opt, feed_url)) = existing_keys else {
            return Ok(false);
        };
        let item_url = item_url_opt.unwrap_or_default();

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

    pub async fn count_by_status(&self, status: ItemStatus) -> Result<i64> {
        Ok(sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*)
             FROM entry_state es
             JOIN entries e ON e.id = es.entry_id
             WHERE e.merged_into_id IS NULL AND es.inbox_state = ?",
        )
        .bind(InboxState::from(status))
        .fetch_one(self.pool)
        .await?)
    }

    pub async fn count_unread(&self) -> Result<i64> {
        Ok(sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*)
             FROM entry_state es
             JOIN entries e ON e.id = es.entry_id
             WHERE e.merged_into_id IS NULL
               AND es.inbox_state = 'unread'
               AND es.read_at IS NULL",
        )
        .fetch_one(self.pool)
        .await?)
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
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let result = sqlx::query(
            "UPDATE entries
             SET title = CASE WHEN title = url AND ? IS NOT NULL THEN ? ELSE title END,
                 creators_json = CASE WHEN creators_json = '[]' AND ? IS NOT NULL THEN ? ELSE creators_json END,
                 summary = COALESCE(summary, ?),
                 updated_at = ?
             WHERE id = ?
               AND ((title = url AND ? IS NOT NULL)
                    OR (creators_json = '[]' AND ? IS NOT NULL)
                    OR (summary IS NULL AND ? IS NOT NULL))",
        )
        .bind(clean_title)
        .bind(clean_title)
        .bind(new_creators_json.as_deref())
        .bind(new_creators_json.as_deref())
        .bind(clean_summary)
        .bind(now)
        .bind(id)
        .bind(clean_title)
        .bind(new_creators_json.as_deref())
        .bind(clean_summary)
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

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::{Db, FeedType};

    #[tokio::test]
    async fn test_unique_constraints_for_feed_and_manual_items() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let items = db.items();

        let saved_url = Url::parse("https://example.com/saved").unwrap();
        let manual = NewItem {
            feed_id: None,
            title: "Saved Article",
            url: &saved_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        };
        items.create(&manual).await.unwrap();
        assert!(items.create(&manual).await.is_err());

        let feed_url = Url::parse("https://example.com/feed").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();
        let feed_item = NewItem {
            feed_id: Some(&feed.id),
            title: "Feed Article",
            url: &saved_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        };
        items.create(&feed_item).await.unwrap();
        assert!(items.create(&feed_item).await.is_err());
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_fts5_search_stemming_phrases_ranking_and_trigger_sync() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let items = db.items();

        let feed_url = Url::parse("https://example.com/feed.xml").unwrap();
        let feed = db
            .feeds()
            .create(FeedType::Rss, &feed_url, Some("Systems Engineering Digest"), None)
            .await
            .unwrap();

        let url1 = Url::parse("https://example.com/1").unwrap();
        let (body_item, _) = items
            .save_manual_input_with_tags(&ManualItemInput {
                url: &url1,
                title: "Notes on Consensus",
                summary: Some("A brief overview of Raft and Paxos."),
                content_html: Some(
                    "<p>Modern distributed systems rely on state machine replication and optimizing latency.</p>",
                ),
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                tags: &[],
            })
            .await
            .unwrap();

        let url2 = Url::parse("https://example.com/2").unwrap();
        let title_item = items
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "Distributed Systems in Practice",
                url: &url2,
                author: Some("Leslie Lamport"),
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        let results = items
            .list_filtered(&ItemFilter {
                search: Some("\"distributed systems\"".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].id, title_item.id);
        assert_eq!(results[1].id, body_item.id);

        let stemmed = items
            .list_filtered(&ItemFilter {
                search: Some("optimize".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(stemmed.len(), 1);
        assert_eq!(stemmed[0].id, body_item.id);

        let prefix = items
            .list_filtered(&ItemFilter { search: Some("lamp".to_string()), ..Default::default() })
            .await
            .unwrap();
        assert_eq!(prefix.len(), 1);
        assert_eq!(prefix[0].id, title_item.id);

        let by_feed = items
            .list_filtered(&ItemFilter {
                search: Some("Engineering Digest".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(by_feed.len(), 1);
        assert_eq!(by_feed[0].id, title_item.id);

        items
            .backfill_metadata(&title_item.id, None, None, Some("Byzantine quorum verification"))
            .await
            .unwrap();
        let after_update = items
            .list_filtered(&ItemFilter {
                search: Some("byzantine".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(after_update.len(), 1);
        assert_eq!(after_update[0].id, title_item.id);

        let special = items
            .list_filtered(&ItemFilter {
                search: Some("c++ \"unclosed AND OR NOT (foo:bar) -baz * ^".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert!(special.is_empty());

        db.feeds().delete(&feed.id).await.unwrap();
        let after_delete = items
            .list_filtered(&ItemFilter {
                search: Some("distributed".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(after_delete.len(), 1);
        assert_eq!(after_delete[0].id, body_item.id);
    }

    #[tokio::test]
    async fn test_multi_token_search_across_tags_feed_title_and_backfill_activity() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let items = db.items();

        let feed_url = Url::parse("https://example.com/rust-weekly.xml").unwrap();
        let feed =
            db.feeds().create(FeedType::Rss, &feed_url, Some("Rust Weekly"), None).await.unwrap();

        let url1 = Url::parse("https://example.com/async-closures").unwrap();
        let feed_item = items
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "Stabilizing Async Closures",
                url: &url1,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        db.tags().add_named_to_item(&feed_item.id, "llm-inference").await.unwrap();

        for query in ["llm-inference", "#llm-inference"] {
            let by_tag = items
                .list_filtered(&ItemFilter {
                    search: Some(query.to_string()),
                    ..Default::default()
                })
                .await
                .unwrap();
            assert_eq!(by_tag.len(), 1);
            assert_eq!(by_tag[0].id, feed_item.id);
        }

        let cross_feed_item = items
            .list_filtered(&ItemFilter {
                search: Some("rust async".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(cross_feed_item.len(), 1);
        assert_eq!(cross_feed_item[0].id, feed_item.id);

        let cross_tag_item = items
            .list_filtered(&ItemFilter {
                search: Some("#inference closures".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(cross_tag_item.len(), 1);
        assert_eq!(cross_tag_item[0].id, feed_item.id);

        let before_id = db.sync().latest_activity_id().await.unwrap();
        items
            .backfill_metadata(
                &feed_item.id,
                Some("Updated Title"),
                Some("Niko"),
                Some("Deep dive"),
            )
            .await
            .unwrap();
        let changes = db.sync().list_changes_since(before_id, 10).await.unwrap();
        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].entity_type, ActivityEntityType::Item);
        assert_eq!(changes[0].entity_id, feed_item.id);
        assert_eq!(changes[0].action, ActivityAction::Update);

        let after_first_backfill_id = db.sync().latest_activity_id().await.unwrap();
        items
            .backfill_metadata(
                &feed_item.id,
                Some("Updated Title"),
                Some("Niko"),
                Some("Deep dive"),
            )
            .await
            .unwrap();
        let noop_changes = db.sync().list_changes_since(after_first_backfill_id, 10).await.unwrap();
        assert_eq!(noop_changes.len(), 0);
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_default_published_sort_summary_normalization_and_archive_batch() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let items = db.items();

        let feed_url = Url::parse("https://example.com/archive.xml").unwrap();
        let feed =
            db.feeds().create(FeedType::Rss, &feed_url, Some("Archive"), None).await.unwrap();

        let older_pub =
            DateTime::parse_from_rfc3339("2013-04-21T08:00:00Z").unwrap().with_timezone(&Utc);
        let newer_pub =
            DateTime::parse_from_rfc3339("2026-11-06T22:00:00Z").unwrap().with_timezone(&Utc);

        let url_new = Url::parse("https://example.com/2026-post").unwrap();
        let item_new = items
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "Yesterday Post",
                url: &url_new,
                author: None,
                published_at: Some(newer_pub),
                summary: Some("<figure><img src=\"https://example.com/a.png\"></figure><p>Clean &amp; fast summary</p>"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        let url_old = Url::parse("https://example.com/2013-post").unwrap();
        let item_old = items
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "2013 Post",
                url: &url_old,
                author: None,
                published_at: Some(older_pub),
                summary: Some("Old summary"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        let default_sorted = items.list_filtered(&ItemFilter::default()).await.unwrap();
        assert_eq!(default_sorted.len(), 2);
        assert_eq!(default_sorted[0].id, item_new.id);
        assert_eq!(default_sorted[1].id, item_old.id);

        let list_sorted = items.list(None, None, None).await.unwrap();
        assert_eq!(list_sorted[0].id, item_new.id);
        assert_eq!(list_sorted[1].id, item_old.id);

        let normalized_count = items.normalize_legacy_html_summaries().await.unwrap();
        assert_eq!(normalized_count, 1);
        let refreshed_new = items.get(&item_new.id).await.unwrap().unwrap();
        assert_eq!(refreshed_new.summary.as_deref(), Some("Clean & fast summary"));
        assert!(refreshed_new.content_html.as_deref().unwrap().contains("<figure>"));
        assert_eq!(refreshed_new.thumbnail_url.as_deref(), Some("https://example.com/a.png"));

        let url_angle = Url::parse("https://example.com/angle-post").unwrap();
        let item_angle = items
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: "Angle Post",
                url: &url_angle,
                author: None,
                published_at: None,
                summary: Some("<p>Use &lt;div&gt; or x &lt; y &gt; z</p>"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        assert_eq!(items.normalize_legacy_html_summaries().await.unwrap(), 1);
        let after_first = items.get(&item_angle.id).await.unwrap().unwrap();
        assert_eq!(after_first.summary.as_deref(), Some("Use <div> or x < y > z"));
        assert_eq!(items.normalize_legacy_html_summaries().await.unwrap(), 0);
        let after_second = items.get(&item_angle.id).await.unwrap().unwrap();
        assert_eq!(after_second.summary.as_deref(), Some("Use <div> or x < y > z"));

        let url_huge = Url::parse("https://example.com/huge-essay").unwrap();
        let huge_html = format!("<p>{}</p>", "a".repeat(70_000));
        let (huge_item, _) = items
            .save_manual_input_with_tags(&ManualItemInput {
                url: &url_huge,
                title: "Book-Length Essay",
                summary: Some("Short summary"),
                content_html: Some(&huge_html),
                thumbnail_url: Some("https://example.com/cover.jpg"),
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                tags: &[],
            })
            .await
            .unwrap();
        let listed = items.list_filtered(&ItemFilter::default()).await.unwrap();
        let listed_huge = listed.iter().find(|i| i.id == huge_item.id).unwrap();
        assert!(listed_huge.content_html.is_none());
        assert_eq!(listed_huge.thumbnail_url.as_deref(), Some("https://example.com/cover.jpg"));
        let fetched_huge = items.get(&huge_item.id).await.unwrap().unwrap();
        assert_eq!(fetched_huge.content_html.as_deref(), Some(huge_html.as_str()));

        let metadata_only = items
            .list_filtered(&ItemFilter { include_content: Some(false), ..Default::default() })
            .await
            .unwrap();
        let meta_new = metadata_only.iter().find(|i| i.id == item_new.id).unwrap();
        assert!(meta_new.content_html.is_none());
        assert_eq!(meta_new.summary.as_deref(), Some("Clean & fast summary"));
        assert_eq!(meta_new.thumbnail_url.as_deref(), Some("https://example.com/a.png"));

        let custom_read_ts =
            DateTime::parse_from_rfc3339("2026-03-31T09:30:00Z").unwrap().with_timezone(&Utc);
        assert!(
            items
                .update(&item_new.id, None, None, None, ReadAtPatch::MarkRead(custom_read_ts))
                .await
                .unwrap()
        );
        let read_new = items.get(&item_new.id).await.unwrap().unwrap();
        assert_eq!(read_new.status, ItemStatus::Unread);
        assert_eq!(read_new.read_at, Some(custom_read_ts));
        let unread_only = items
            .list_filtered(&ItemFilter { is_read: Some(false), ..Default::default() })
            .await
            .unwrap();
        assert!(!unread_only.iter().any(|i| i.id == item_new.id));
        let read_only = items
            .list_filtered(&ItemFilter { is_read: Some(true), ..Default::default() })
            .await
            .unwrap();
        assert!(read_only.iter().any(|i| i.id == item_new.id));

        assert!(
            items.update(&item_new.id, None, None, None, ReadAtPatch::MarkUnread).await.unwrap()
        );
        let unread_again = items.get(&item_new.id).await.unwrap().unwrap();
        assert!(unread_again.read_at.is_none());

        assert!(
            items
                .update(
                    &item_new.id,
                    Some(ItemStatus::Archived),
                    None,
                    None,
                    ReadAtPatch::MarkRead(custom_read_ts),
                )
                .await
                .unwrap()
        );
        let archived_with_ts = items.get(&item_new.id).await.unwrap().unwrap();
        assert_eq!(archived_with_ts.status, ItemStatus::Archived);
        assert_eq!(archived_with_ts.read_at, Some(custom_read_ts));

        assert!(
            items
                .update(
                    &item_new.id,
                    Some(ItemStatus::Unread),
                    None,
                    None,
                    ReadAtPatch::MarkRead(custom_read_ts),
                )
                .await
                .unwrap()
        );
        let restored_read = items.get(&item_new.id).await.unwrap().unwrap();
        assert_eq!(restored_read.status, ItemStatus::Unread);
        assert_eq!(restored_read.read_at, Some(custom_read_ts));

        assert!(
            items.update(&item_new.id, None, None, None, ReadAtPatch::MarkUnread).await.unwrap()
        );

        let before_archive_id = db.sync().latest_activity_id().await.unwrap();
        let scope =
            ArchiveBatchScope::from_request(&["   ".to_string()], Some(&feed.id), false).unwrap();
        let archived = items.archive_batch(&scope).await.unwrap();
        assert_eq!(archived, 3);
        assert_eq!(items.count_by_status(ItemStatus::Unread).await.unwrap(), 1);
        assert_eq!(items.count_by_status(ItemStatus::Archived).await.unwrap(), 3);

        let archive_changes = db.sync().list_changes_since(before_archive_id, 10).await.unwrap();
        assert_eq!(archive_changes.len(), 3);
        assert!(archive_changes.iter().all(|c| c.action == ActivityAction::Archive));
    }
}
