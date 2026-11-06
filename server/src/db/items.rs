use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, SqlitePool};
use url::Url;
use uuid::Uuid;

use super::{
    ActivityAction, ActivityEntityType, ContentType, ItemSortField, ItemStatus, ItemStatusFilter,
    SortOrder, SyncRepo, Tag, TagRepo,
};

const ITEM_SELECT: &str = "SELECT i.id, i.feed_id, f.title AS feed_title, i.title, i.url, i.author,
       i.published_at, i.created_at, i.summary, i.content_html, i.content_type, i.status,
       i.is_favorite, i.read_at, i.read_progress,
       (SELECT m.id FROM media m WHERE m.item_id = i.id AND m.media_type = 'thumbnail' ORDER BY m.created_at DESC LIMIT 1) AS thumbnail_media_id,
       (SELECT m.duration FROM media m WHERE m.item_id = i.id AND m.duration IS NOT NULL ORDER BY m.created_at DESC LIMIT 1) AS duration,
       COALESCE((SELECT json_group_array(name) FROM (SELECT t.name AS name FROM item_tags it JOIN tags t ON t.id = it.tag_id WHERE it.item_id = i.id ORDER BY t.name ASC)), '[]') AS tag_names
FROM items i
LEFT JOIN feeds f ON i.feed_id = f.id";

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
    pub feed_title: Option<String>,
    pub title: String,
    #[sqlx(try_from = "&'a str")]
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub summary: Option<String>,
    pub content_html: Option<String>,
    pub content_type: ContentType,
    pub status: ItemStatus,
    pub is_favorite: bool,
    pub read_at: Option<DateTime<Utc>>,
    pub read_progress: f64,
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
    pub content_type: ContentType,
    pub tags: &'a [String],
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct ItemFilter {
    pub status: Option<ItemStatusFilter>,
    pub feed_id: Option<String>,
    pub tag_id: Option<String>,
    pub content_type: Option<ContentType>,
    pub is_favorite: Option<bool>,
    pub search: Option<String>,
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

    /// Converts user search input into a safe SQLite FTS5 `MATCH` expression.
    ///
    /// - Balanced double-quoted segments (`"distributed systems"`) become exact FTS5 phrase queries.
    /// - Individual words (`quant`) become `("quant" OR "quant"*)` so both Porter-stemmed matches
    ///   (`computing` -> `compute`) and prefix matches (`quant` -> `quantum`) succeed.
    /// - Special FTS5 syntax characters (`:`, `*`, `^`, `-`, `(`, `)`, unclosed `"`, boolean keywords)
    ///   are sanitized so arbitrary user input never triggers an FTS5 syntax error.
    #[must_use]
    pub fn build_fts_query(input: &str) -> Option<String> {
        let mut clauses = Vec::new();
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
                    clauses.push(format!("\"{}\"", words.join(" ")));
                } else {
                    for word in words {
                        clauses.push(format!("(\"{word}\" OR \"{word}\"*)"));
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
                    clauses.push(format!("(\"{word}\" OR \"{word}\"*)"));
                }
            }
        }

        if clauses.is_empty() { None } else { Some(clauses.join(" AND ")) }
    }

    fn extract_word_tokens(segment: &str) -> Vec<&str> {
        segment.split(|c: char| !c.is_alphanumeric()).filter(|tok| !tok.is_empty()).collect()
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
        let id = Uuid::new_v4().to_string();
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
            feed_title: None,
            title: new_item.title.to_string(),
            url: new_item.url.clone(),
            author: new_item.author.map(String::from),
            published_at: new_item.published_at,
            created_at: now,
            summary: new_item.summary.map(String::from),
            content_html: new_item.summary.map(String::from),
            content_type: new_item.content_type,
            status: ItemStatus::Unread,
            is_favorite: false,
            read_at: None,
            read_progress: 0.0,
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
            content_type: ContentType::Article,
            tags,
        })
        .await
    }

    pub async fn save_manual_input_with_tags(
        &self,
        input: &ManualItemInput<'_>,
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        let item_id = Uuid::new_v4().to_string();
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        sqlx::query(
            "INSERT INTO items
             (id, feed_id, title, url, author, published_at, summary, content_html, content_type, created_at, updated_at)
             VALUES (?, NULL, ?, ?, NULL, NULL, ?, ?, ?, ?, ?)",
        )
        .bind(&item_id)
        .bind(input.title)
        .bind(input.url.as_str())
        .bind(input.summary)
        .bind(input.content_html)
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
            feed_title: None,
            title: input.title.to_string(),
            url: input.url.clone(),
            author: None,
            published_at: None,
            created_at: now,
            summary: input.summary.map(String::from),
            content_html: input.content_html.map(String::from),
            content_type: input.content_type,
            status: ItemStatus::Unread,
            is_favorite: false,
            read_at: None,
            read_progress: 0.0,
            thumbnail_media_id: None,
            duration: None,
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
        let mut qb = QueryBuilder::new(ITEM_SELECT);
        qb.push(" WHERE 1=1");
        if let Some(s) = status {
            qb.push(" AND i.status = ").push_bind(s);
        }
        if let Some(f) = feed_id {
            qb.push(" AND i.feed_id = ").push_bind(f);
        }
        qb.push(" ORDER BY i.created_at DESC");
        if let Some(l) = limit {
            qb.push(" LIMIT ").push_bind(i64::try_from(l).unwrap_or(i64::MAX));
        }

        Ok(qb.build_query_as::<Item>().fetch_all(self.pool).await?)
    }

    pub async fn list_filtered(&self, filter: &ItemFilter) -> Result<Vec<Item>> {
        let trimmed_search = filter.search.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let fts_query = trimmed_search.and_then(ItemFilter::build_fts_query);

        let mut qb = QueryBuilder::new(ITEM_SELECT);
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
        if let Some(search) = trimmed_search {
            let pattern = format!("%{}%", ItemFilter::escape_like(search));
            if fts_query.is_some() {
                qb.push(" AND (fts.fts_rowid IS NOT NULL OR COALESCE(f.title, '') LIKE ")
                    .push_bind(pattern)
                    .push(" ESCAPE '\\')");
            } else {
                qb.push(" AND (i.title LIKE ")
                    .push_bind(pattern.clone())
                    .push(" ESCAPE '\\' OR COALESCE(i.summary, '') LIKE ")
                    .push_bind(pattern.clone())
                    .push(" ESCAPE '\\' OR COALESCE(i.author, '') LIKE ")
                    .push_bind(pattern.clone())
                    .push(" ESCAPE '\\' OR COALESCE(i.content_html, '') LIKE ")
                    .push_bind(pattern.clone())
                    .push(" ESCAPE '\\' OR COALESCE(f.title, '') LIKE ")
                    .push_bind(pattern)
                    .push(" ESCAPE '\\')");
            }
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
                " ORDER BY CASE WHEN fts.fts_rank IS NULL THEN 1 ELSE 0 END ASC, fts.fts_rank {rank_dir}, i.created_at DESC, i.id DESC"
            ));
        } else {
            let sort_col = filter.sort.unwrap_or_default().as_sql_expr();
            let sort_dir = filter.order.unwrap_or_default().as_sql();
            qb.push(format_args!(" ORDER BY {sort_col} {sort_dir}, i.id DESC"));
        }

        let per_page = filter.per_page.unwrap_or(50).clamp(1, 200);
        let page = filter.page.unwrap_or(1).max(1);
        let offset = page.saturating_sub(1).saturating_mul(per_page);
        qb.push(" LIMIT ")
            .push_bind(i64::try_from(per_page).unwrap_or(50))
            .push(" OFFSET ")
            .push_bind(i64::try_from(offset).unwrap_or(0));

        Ok(qb.build_query_as::<Item>().fetch_all(self.pool).await?)
    }

    pub async fn update(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
    ) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let existing: Option<(ItemStatus, bool, f64)> =
            sqlx::query_as("SELECT status, is_favorite, read_progress FROM items WHERE id = ?")
                .bind(id)
                .fetch_optional(&mut *tx)
                .await?;

        let Some((current_status, current_favorite, current_progress)) = existing else {
            return Ok(false);
        };

        if let Some(new_status) = status
            && new_status != current_status
        {
            match new_status {
                ItemStatus::Archived => {
                    sqlx::query(
                        "UPDATE items SET status = 'archived', read_at = COALESCE(read_at, ?), updated_at = ? WHERE id = ?",
                    )
                    .bind(now)
                    .bind(now)
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
                    SyncRepo::record_activity(
                        &mut tx,
                        ActivityEntityType::Item,
                        id,
                        ActivityAction::Archive,
                        now,
                    )
                    .await?;
                }
                ItemStatus::Unread => {
                    sqlx::query(
                        "UPDATE items SET status = 'unread', read_at = NULL, updated_at = ? WHERE id = ?",
                    )
                    .bind(now)
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
                    SyncRepo::record_activity(
                        &mut tx,
                        ActivityEntityType::Item,
                        id,
                        ActivityAction::Unarchive,
                        now,
                    )
                    .await?;
                }
            }
        }

        if let Some(fav) = is_favorite
            && fav != current_favorite
        {
            let action = if fav { ActivityAction::Favorite } else { ActivityAction::Unfavorite };
            sqlx::query("UPDATE items SET is_favorite = ?, updated_at = ? WHERE id = ?")
                .bind(fav)
                .bind(now)
                .bind(id)
                .execute(&mut *tx)
                .await?;
            SyncRepo::record_activity(&mut tx, ActivityEntityType::Item, id, action, now).await?;
        }

        if let Some(progress) = read_progress
            && (progress - current_progress).abs() > f64::EPSILON
        {
            sqlx::query("UPDATE items SET read_progress = ?, updated_at = ? WHERE id = ?")
                .bind(progress)
                .bind(now)
                .bind(id)
                .execute(&mut *tx)
                .await?;
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
        self.update(id, Some(ItemStatus::Archived), None, None).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, Some(ItemStatus::Unread), None, None).await
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res = sqlx::query("DELETE FROM items WHERE id = ?").bind(id).execute(&mut *tx).await?;
        if res.rows_affected() == 0 {
            return Ok(false);
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
        Ok(sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM items WHERE status = ?")
            .bind(status)
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
        let now = SyncRepo::now_utc();
        sqlx::query(
            "UPDATE items
             SET title = CASE WHEN title = url AND ? IS NOT NULL THEN ? ELSE title END,
                 author = COALESCE(author, ?),
                 summary = COALESCE(summary, ?),
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(title)
        .bind(title)
        .bind(author)
        .bind(summary)
        .bind(now)
        .bind(id)
        .execute(self.pool)
        .await?;
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
}
