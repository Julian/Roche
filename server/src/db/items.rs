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

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Item {
    pub id: String,
    pub feed_id: Option<String>,
    pub title: String,
    #[sqlx(try_from = "&'a str")]
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub summary: Option<String>,
    pub content_type: ContentType,
    pub status: ItemStatus,
    pub is_favorite: bool,
    pub read_at: Option<DateTime<Utc>>,
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

#[derive(Debug, Default, Clone, Deserialize)]
pub struct ItemFilter {
    pub status: Option<ItemStatusFilter>,
    pub feed_id: Option<String>,
    pub tag_id: Option<String>,
    pub content_type: Option<ContentType>,
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
            title: new_item.title.to_string(),
            url: new_item.url.clone(),
            author: new_item.author.map(String::from),
            published_at: new_item.published_at,
            summary: new_item.summary.map(String::from),
            content_type: new_item.content_type,
            status: ItemStatus::Unread,
            is_favorite: false,
            read_at: None,
        })
    }

    pub async fn save_manual_with_tags(
        &self,
        url: &Url,
        title: &str,
        tags: &[String],
    ) -> std::result::Result<(Item, Vec<Tag>), sqlx::Error> {
        let item_id = Uuid::new_v4().to_string();
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        sqlx::query(
            "INSERT INTO items
             (id, feed_id, title, url, author, published_at, summary, content_type, created_at, updated_at)
             VALUES (?, NULL, ?, ?, NULL, NULL, NULL, ?, ?, ?)",
        )
        .bind(&item_id)
        .bind(title)
        .bind(url.as_str())
        .bind(ContentType::Article)
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
        for tag_name in tags {
            let trimmed = tag_name.trim();
            if trimmed.is_empty() || !seen_names.insert(trimmed.to_owned()) {
                continue;
            }
            let tag =
                TagRepo::get_or_create_and_attach_in_tx(&mut tx, &item_id, trimmed, now).await?;
            attached_tags.push(tag);
        }

        tx.commit().await?;

        let item = Item {
            id: item_id,
            feed_id: None,
            title: title.to_string(),
            url: url.clone(),
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
            status: ItemStatus::Unread,
            is_favorite: false,
            read_at: None,
        };

        Ok((item, attached_tags))
    }

    pub async fn get(&self, id: &str) -> Result<Option<Item>> {
        Ok(sqlx::query_as::<_, Item>("SELECT * FROM items WHERE id = ?")
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
        let mut qb = QueryBuilder::new("SELECT * FROM items WHERE 1=1");
        if let Some(s) = status {
            qb.push(" AND status = ").push_bind(s);
        }
        if let Some(f) = feed_id {
            qb.push(" AND feed_id = ").push_bind(f);
        }
        qb.push(" ORDER BY created_at DESC");
        if let Some(l) = limit {
            qb.push(" LIMIT ").push_bind(i64::try_from(l).unwrap_or(i64::MAX));
        }

        Ok(qb.build_query_as::<Item>().fetch_all(self.pool).await?)
    }

    pub async fn list_filtered(&self, filter: &ItemFilter) -> Result<Vec<Item>> {
        let mut qb = QueryBuilder::new("SELECT DISTINCT i.* FROM items i");
        if filter.tag_id.is_some() {
            qb.push(" JOIN item_tags it ON i.id = it.item_id");
        }
        qb.push(" WHERE 1=1");

        if let Some(s) = filter.status.and_then(ItemStatusFilter::into_status) {
            qb.push(" AND i.status = ").push_bind(s);
        }
        if let Some(f) = &filter.feed_id {
            qb.push(" AND i.feed_id = ").push_bind(f);
        }
        if let Some(t) = &filter.tag_id {
            qb.push(" AND it.tag_id = ").push_bind(t);
        }
        if let Some(ct) = filter.content_type {
            qb.push(" AND i.content_type = ").push_bind(ct);
        }
        if let Some(search) = &filter.search {
            let pattern = format!("%{}%", ItemFilter::escape_like(search));
            qb.push(" AND (i.title LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.summary, '') LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR COALESCE(i.author, '') LIKE ")
                .push_bind(pattern)
                .push(" ESCAPE '\\')");
        }

        let sort_col = filter.sort.unwrap_or_default().as_sql_expr();
        let sort_dir = filter.order.unwrap_or_default().as_sql();
        qb.push(format_args!(" ORDER BY {sort_col} {sort_dir}, i.id DESC"));

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
    ) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let existing: Option<Item> = sqlx::query_as("SELECT * FROM items WHERE id = ?")
            .bind(id)
            .fetch_optional(&mut *tx)
            .await?;

        let Some(current) = existing else {
            return Ok(false);
        };

        if let Some(new_status) = status
            && new_status != current.status
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
            && fav != current.is_favorite
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

        tx.commit().await?;
        Ok(true)
    }

    pub async fn archive(&self, id: &str) -> Result<bool> {
        self.update(id, Some(ItemStatus::Archived), None).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, Some(ItemStatus::Unread), None).await
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
}
