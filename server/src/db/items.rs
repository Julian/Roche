use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, SqlitePool};
use url::Url;

use super::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, ContentType, ItemSortField, ItemStatus,
    ItemStatusFilter, ReadAtPatch, SortOrder, SyncRepo, Tag, TagRepo,
    sync::{deterministic_item_id, item_natural_key},
};

const MAX_SEARCH_TERMS: usize = 16;

macro_rules! item_select_sql {
    ($content_html_expr:literal) => {
        concat!(
            "SELECT i.id, i.feed_id, f.url AS feed_url, f.title AS feed_title, i.title, i.url, i.author,
       i.published_at, i.created_at, i.summary, ",
            $content_html_expr,
            " AS content_html, i.thumbnail_url, i.audio_url, i.content_type, i.status,
       i.is_favorite, i.read_at, i.read_progress, i.interaction_updated_at,
       (SELECT m.id FROM media m WHERE m.item_id = i.id AND m.media_type = 'thumbnail' ORDER BY m.created_at DESC LIMIT 1) AS thumbnail_media_id,
       COALESCE(i.duration, (SELECT m.duration FROM media m WHERE m.item_id = i.id AND m.duration IS NOT NULL ORDER BY m.created_at DESC LIMIT 1)) AS duration,
       COALESCE((SELECT json_group_array(name) FROM (SELECT t.name AS name FROM item_tags it JOIN tags t ON t.id = it.tag_id WHERE it.item_id = i.id ORDER BY t.name ASC)), '[]') AS tag_names
FROM items i
LEFT JOIN feeds f ON i.feed_id = f.id"
        )
    };
}

const ITEM_SELECT: &str = item_select_sql!("i.content_html");

/// List query projection that caps inline `content_html` at 64 KB so multi-hundred-KB full-book
/// essays do not blow up bulk sync page responses or Android's 2 MB SQLite `CursorWindow`;
/// oversized articles are lazy-hydrated via `GET /api/v1/items/{id}` (`ItemRepo::get`).
const ITEM_SELECT_LIST: &str =
    item_select_sql!("CASE WHEN length(i.content_html) <= 65536 THEN i.content_html ELSE NULL END");

/// Lightweight list projection used when `include_content=false` is requested during bulk sync.
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
    pub sort: Option<ItemSortField>,
    pub order: Option<SortOrder>,
    pub page: Option<usize>,
    pub per_page: Option<usize>,
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

    /// Parses user search input into structured [`SearchTerm`]s for multi-field matching across
    /// `items_fts`, `feeds.title`, and `tags.name`.
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

    /// Converts user search input into a safe SQLite FTS5 `MATCH` expression.
    ///
    /// - Balanced double-quoted segments (`"distributed systems"`) become exact FTS5 phrase queries.
    /// - Individual words (`quant`) become `("quant" OR "quant"*)` so both Porter-stemmed matches
    ///   (`computing` -> `compute`) and prefix matches (`quant` -> `quantum`) succeed.
    /// - Special FTS5 syntax characters (`:`, `*`, `^`, `-`, `(`, `)`, unclosed `"`, boolean keywords)
    ///   are sanitized so arbitrary user input never triggers an FTS5 syntax error.
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
                sqlx::query_scalar("SELECT url FROM feeds WHERE id = ?")
                    .bind(fid)
                    .fetch_optional(self.pool)
                    .await?
            }
            None => None,
        };
        let id = deterministic_item_id(feed_url.as_deref(), new_item.url.as_str());
        let now = SyncRepo::now_utc();

        sqlx::query(
            "INSERT INTO items (id, feed_id, title, url, author, published_at, summary, content_type, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(new_item.feed_id)
        .bind(new_item.title)
        .bind(new_item.url.as_str())
        .bind(new_item.author)
        .bind(new_item.published_at)
        .bind(new_item.summary)
        .bind(new_item.content_type)
        .bind(now)
        .bind(now)
        .execute(self.pool)
        .await?;

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
            content_html: new_item.summary.map(String::from),
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

    pub async fn save_manual_input_with_tags(
        &self,
        input: &ManualItemInput<'_>,
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        let item_id = deterministic_item_id(None, input.url.as_str());
        let now = SyncRepo::now_utc();
        let clean_duration = input.duration.filter(|&d| d > 0);
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(
            &mut tx,
            ActivityEntityType::Item,
            &item_natural_key(None, input.url.as_str()),
        )
        .await?;

        sqlx::query(
            "INSERT INTO items
             (id, feed_id, title, url, author, published_at, summary, content_html, thumbnail_url, audio_url, duration, content_type, created_at, updated_at)
             VALUES (?, NULL, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&item_id)
        .bind(input.title)
        .bind(input.url.as_str())
        .bind(input.summary)
        .bind(input.content_html)
        .bind(input.thumbnail_url)
        .bind(input.audio_url)
        .bind(clean_duration)
        .bind(input.content_type)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Item,
            &item_id,
            ActivityAction::Create,
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
                TagRepo::get_or_create_and_attach_in_tx(&mut tx, &item_id, trimmed, now).await?;
            attached_tags.push(tag);
        }

        tx.commit().await?;

        let tag_names = TagNames(attached_tags.iter().map(|t| t.name.clone()).collect());
        let item = Item {
            id: item_id,
            feed_id: None,
            feed_url: None,
            feed_title: None,
            title: input.title.to_string(),
            url: input.url.clone(),
            author: None,
            published_at: None,
            created_at: now,
            summary: input.summary.map(String::from),
            content_html: input.content_html.map(String::from),
            thumbnail_url: input.thumbnail_url.map(String::from),
            audio_url: input.audio_url.map(String::from),
            content_type: input.content_type,
            status: ItemStatus::Unread,
            is_favorite: false,
            read_at: None,
            read_progress: 0.0,
            interaction_updated_at: None,
            thumbnail_media_id: None,
            duration: clean_duration,
            tag_names,
        };

        Ok((item, attached_tags))
    }

    pub async fn get(&self, id: &str) -> Result<Option<Item>> {
        let sql = format!("{ITEM_SELECT} WHERE i.id = ?");
        Ok(sqlx::query_as::<_, Item>(&sql).bind(id).fetch_optional(self.pool).await?)
    }

    pub async fn list(
        &self,
        status: Option<ItemStatus>,
        feed_id: Option<&str>,
        limit: Option<usize>,
    ) -> Result<Vec<Item>> {
        let mut qb = QueryBuilder::new(ITEM_SELECT_LIST);
        qb.push(" WHERE 1=1");
        if let Some(s) = status {
            qb.push(" AND i.status = ").push_bind(s);
        }
        if let Some(f) = feed_id {
            qb.push(" AND i.feed_id = ").push_bind(f);
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
        let search_terms =
            trimmed_search.map(ItemFilter::tokenize_search_terms).unwrap_or_default();
        let fts_query = trimmed_search.and_then(ItemFilter::build_fts_query);

        let base_select = if filter.include_content == Some(false) {
            ITEM_SELECT_METADATA_ONLY
        } else {
            ITEM_SELECT_LIST
        };
        let mut qb = QueryBuilder::new(base_select);
        if let Some(fts_match) = &fts_query {
            qb.push(
                " LEFT JOIN (SELECT rowid AS fts_rowid, bm25(items_fts, 10.0, 5.0, 2.0, 1.0) AS fts_rank FROM items_fts WHERE items_fts MATCH ",
            )
            .push_bind(fts_match.clone())
            .push(") fts ON fts.fts_rowid = i.rowid");
        }
        qb.push(" WHERE 1=1");

        if let Some(s) = filter.status.and_then(ItemStatusFilter::into_status) {
            qb.push(" AND i.status = ").push_bind(s);
        }
        if let Some(is_read) = filter.is_read {
            if is_read {
                qb.push(" AND i.read_at IS NOT NULL");
            } else {
                qb.push(" AND i.read_at IS NULL");
            }
        }
        if let Some(f) = &filter.feed_id {
            qb.push(" AND i.feed_id = ").push_bind(f);
        }
        if let Some(t) = &filter.tag_id {
            qb.push(
                " AND EXISTS (SELECT 1 FROM item_tags it WHERE it.item_id = i.id AND it.tag_id = ",
            )
            .push_bind(t)
            .push(")");
        }
        if let Some(ct) = filter.content_type {
            qb.push(" AND i.content_type = ").push_bind(ct);
        }
        if let Some(fav) = filter.is_favorite {
            qb.push(" AND i.is_favorite = ").push_bind(fav);
        }
        if let Some(ts) = filter.updated_after {
            qb.push(" AND datetime(i.updated_at) > datetime(").push_bind(ts).push(")");
        }
        if let Some(search) = trimmed_search {
            Self::append_search_where_clause(&mut qb, search, &search_terms);
        }

        let sort_by_relevance =
            fts_query.is_some() && matches!(filter.sort, None | Some(ItemSortField::Relevance));
        if sort_by_relevance {
            let rank_dir = match filter.order.unwrap_or_default() {
                // bm25() returns negative scores where more negative = higher relevance
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

    fn append_search_where_clause(
        qb: &mut QueryBuilder<'_, sqlx::Sqlite>,
        raw_search: &str,
        search_terms: &[SearchTerm],
    ) {
        if search_terms.is_empty() {
            let pattern = format!("%{}%", ItemFilter::escape_like(raw_search));
            qb.push(" AND (i.title LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.summary, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.author, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.content_html, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(f.title, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM item_tags it_s JOIN tags t_s ON t_s.id = it_s.tag_id WHERE it_s.item_id = i.id AND t_s.name LIKE ")
                .push_bind(pattern)
                .push(" ESCAPE '\\'))");
        } else {
            qb.push(" AND (fts.fts_rowid IS NOT NULL OR (");
            for (idx, term) in search_terms.iter().enumerate() {
                if idx > 0 {
                    qb.push(" AND ");
                }
                qb.push("(i.rowid IN (SELECT rowid FROM items_fts WHERE items_fts MATCH ")
                    .push_bind(term.fts_clause.clone())
                    .push(") OR COALESCE(f.title, '') LIKE ")
                    .push_bind(term.like_pattern.clone())
                    .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM item_tags it_s JOIN tags t_s ON t_s.id = it_s.tag_id WHERE it_s.item_id = i.id AND t_s.name LIKE ")
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
        let now = SyncRepo::now_utc();

        // Read existing row state before starting the transaction so `tx` begins directly
        // with an `UPDATE` statement (acquiring a write lock immediately without upgrading
        // a deferred read snapshot, which prevents SQLITE_BUSY_SNAPSHOT under concurrency).
        let existing: Option<(ItemStatus, bool, f64, Option<DateTime<Utc>>)> = sqlx::query_as(
            "SELECT status, is_favorite, read_progress, read_at FROM items WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(self.pool)
        .await?;

        let Some((current_status, current_favorite, current_progress, current_read_at)) = existing
        else {
            return Ok(false);
        };

        let target_status = status.unwrap_or(current_status);
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
            "UPDATE items
             SET status = ?,
                 read_at = ?,
                 is_favorite = ?,
                 read_progress = ?,
                 interaction_updated_at = ?,
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(target_status)
        .bind(target_read_at)
        .bind(target_favorite)
        .bind(target_progress)
        .bind(now)
        .bind(now)
        .bind(id)
        .execute(&mut *tx)
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
                        "SELECT id FROM items WHERE status != 'archived' AND id IN (",
                    );
                    let mut sep = qb.separated(", ");
                    for id in chunk {
                        sep.push_bind(*id);
                    }
                    sep.push_unseparated(")");
                    if let Some(fid) = feed_id.as_deref() {
                        qb.push(" AND feed_id = ").push_bind(fid);
                    }
                    let rows: Vec<String> = qb.build_query_scalar().fetch_all(self.pool).await?;
                    matched.extend(rows);
                }
                matched
            }
            ArchiveBatchScope::Feed { feed_id, unread_only } => {
                let sql = if *unread_only {
                    "SELECT id FROM items WHERE status = 'unread' AND read_at IS NULL AND feed_id = ? LIMIT ?"
                } else {
                    "SELECT id FROM items WHERE status != 'archived' AND feed_id = ? LIMIT ?"
                };
                sqlx::query_scalar(sql)
                    .bind(feed_id.as_str())
                    .bind(i64::try_from(MAX_BATCH_IDS).unwrap_or(5_000))
                    .fetch_all(self.pool)
                    .await?
            }
            ArchiveBatchScope::AllUnread => {
                sqlx::query_scalar(
                    "SELECT id FROM items WHERE status = 'unread' AND read_at IS NULL LIMIT ?",
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
                "UPDATE items SET status = 'archived', read_at = COALESCE(read_at, ",
            );
            qb.push_bind(now)
                .push("), interaction_updated_at = ")
                .push_bind(now)
                .push(", updated_at = ")
                .push_bind(now)
                .push(" WHERE status != 'archived' AND id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id.as_str());
            }
            sep.push_unseparated(") RETURNING id");
            let updated: Vec<String> = qb.build_query_scalar().fetch_all(&mut *tx).await?;
            archived_ids.extend(updated);
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
            "SELECT id, url, summary FROM items
             WHERE summary IS NOT NULL
               AND (
                 length(summary) > 320
                 OR (
                   (content_html IS NULL OR content_html = '' OR summary = content_html)
                   AND (summary LIKE '%<%' OR summary LIKE '%&%;%')
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
                "UPDATE items
                 SET content_html = COALESCE(NULLIF(content_html, ''), summary),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
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
            "SELECT id, url, content_html, thumbnail_url FROM items
             WHERE (
                 thumbnail_url IS NULL
                 AND (
                   (content_html IS NOT NULL AND (content_html LIKE '%<img%' OR content_html LIKE '%<IMG%'))
                   OR url LIKE '%youtube.com/%'
                   OR url LIKE '%youtu.be/%'
                 )
               )
               OR thumbnail_url LIKE '%.svg'
               OR thumbnail_url LIKE '%.svg?%'
               OR thumbnail_url LIKE '%/images/core/emoji/%'
               OR thumbnail_url LIKE '%/wpcom-smileys/%'
               OR thumbnail_url LIKE '%/twemoji/%'",
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
            sqlx::query(
                "UPDATE items
                 SET thumbnail_url = ?
                 WHERE id = ?",
            )
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

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let existing_keys: Option<(String, Option<String>)> = sqlx::query_as(
            "SELECT i.url, f.url FROM items i LEFT JOIN feeds f ON i.feed_id = f.id WHERE i.id = ?",
        )
        .bind(id)
        .fetch_optional(self.pool)
        .await?;
        let Some((item_url, feed_url)) = existing_keys else {
            return Ok(false);
        };

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res = sqlx::query("DELETE FROM items WHERE id = ?").bind(id).execute(&mut *tx).await?;
        if res.rows_affected() == 0 {
            return Ok(false);
        }
        SyncRepo::record_tombstone(
            &mut tx,
            ActivityEntityType::Item,
            &item_natural_key(feed_url.as_deref(), &item_url),
            now,
        )
        .await?;
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
        Ok(sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM items WHERE status = ?")
            .bind(status)
            .fetch_one(self.pool)
            .await?)
    }

    pub async fn count_unread(&self) -> Result<i64> {
        Ok(sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM items WHERE status = 'unread' AND read_at IS NULL",
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

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let result = sqlx::query(
            "UPDATE items
             SET title = CASE WHEN title = url AND ? IS NOT NULL THEN ? ELSE title END,
                 author = COALESCE(author, ?),
                 summary = COALESCE(summary, ?),
                 updated_at = ?
             WHERE id = ?
               AND ((title = url AND ? IS NOT NULL)
                    OR (author IS NULL AND ? IS NOT NULL)
                    OR (summary IS NULL AND ? IS NOT NULL))",
        )
        .bind(clean_title)
        .bind(clean_title)
        .bind(clean_author)
        .bind(clean_summary)
        .bind(now)
        .bind(id)
        .bind(clean_title)
        .bind(clean_author)
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
        // Manual item deduplication (feed_id IS NULL)
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

        // Feed item deduplication per (feed_id, url)
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

        // Item 1: matches "distributed systems" only in content_html body
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

        // Item 2: matches "distributed systems" directly in the title (higher BM25 weight)
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

        // 1. Exact phrase search + BM25 ranking (title match ranks ahead of body-only match)
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

        // 2. Porter stemming ("optimize" matches "optimizing" in body_item's content_html)
        let stemmed = items
            .list_filtered(&ItemFilter {
                search: Some("optimize".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(stemmed.len(), 1);
        assert_eq!(stemmed[0].id, body_item.id);

        // 3. Prefix matching ("lamp" matches author "Lamport")
        let prefix = items
            .list_filtered(&ItemFilter { search: Some("lamp".to_string()), ..Default::default() })
            .await
            .unwrap();
        assert_eq!(prefix.len(), 1);
        assert_eq!(prefix[0].id, title_item.id);

        // 4. Feed title fallback ("Engineering Digest" matches feed_item via f.title)
        let by_feed = items
            .list_filtered(&ItemFilter {
                search: Some("Engineering Digest".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(by_feed.len(), 1);
        assert_eq!(by_feed[0].id, title_item.id);

        // 5. UPDATE trigger: backfill_metadata updates summary and makes new terms searchable
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

        // 6. Adversarial FTS syntax characters never cause SQL errors
        let special = items
            .list_filtered(&ItemFilter {
                search: Some("c++ \"unclosed AND OR NOT (foo:bar) -baz * ^".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert!(special.is_empty());

        // 7. DELETE trigger (including ON DELETE CASCADE when feed is deleted) removes from FTS
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

        // 1. Tag search (both plain and hashtag-prefixed)
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

        // 2. Multi-token search spanning feed title ("Rust") + item title ("Async")
        let cross_feed_item = items
            .list_filtered(&ItemFilter {
                search: Some("rust async".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(cross_feed_item.len(), 1);
        assert_eq!(cross_feed_item[0].id, feed_item.id);

        // 3. Multi-token search spanning tag ("inference") + item title ("Closures")
        let cross_tag_item = items
            .list_filtered(&ItemFilter {
                search: Some("#inference closures".to_string()),
                ..Default::default()
            })
            .await
            .unwrap();
        assert_eq!(cross_tag_item.len(), 1);
        assert_eq!(cross_tag_item[0].id, feed_item.id);

        // 4. backfill_metadata records ActivityAction::Update only when a field is actually backfilled
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

        // Calling backfill_metadata again when author and summary are already populated is a no-op
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

        // Second normalization call is idempotent even when plain-text summary has literal `<`
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

        // Oversized (> 64 KB) content_html is omitted in list_filtered_page but returned by get()
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

        // include_content=false omits content_html for all items while keeping summary and thumbnail_url
        let metadata_only = items
            .list_filtered(&ItemFilter { include_content: Some(false), ..Default::default() })
            .await
            .unwrap();
        let meta_new = metadata_only.iter().find(|i| i.id == item_new.id).unwrap();
        assert!(meta_new.content_html.is_none());
        assert_eq!(meta_new.summary.as_deref(), Some("Clean & fast summary"));
        assert_eq!(meta_new.thumbnail_url.as_deref(), Some("https://example.com/a.png"));

        // Independent read state (read_at) vs archive state (status)
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

        // Archiving with an explicit client read_at preserves the client timestamp,
        // and Undo Archive (status=Unread + read_at=Some(custom_read_ts)) restores both
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

        // Whitespace-only item_ids do not shadow feed_id in archive_batch
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
