use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, SqlitePool};
use url::Url;
use uuid::Uuid;

use super::{ActivityAction, ActivityEntityType, ContentType, FeedType, SyncRepo};
use crate::feeds::OpmlOutline;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
#[allow(clippy::struct_field_names)]
pub struct Feed {
    pub id: String,
    pub feed_type: FeedType,
    #[sqlx(try_from = "&'a str")]
    pub url: Url,
    pub title: Option<String>,
    pub description: Option<String>,
    pub language: Option<String>,
    pub poll_interval: i64,
    pub last_polled: Option<DateTime<Utc>>,
    pub last_error: Option<String>,
    pub is_active: bool,
}

#[derive(Debug, Clone)]
pub struct PollItemCandidate {
    pub title: String,
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub summary: String,
    pub content_type: ContentType,
}

#[derive(Debug, Clone)]
pub struct PollBatch {
    pub title: Option<String>,
    pub description: Option<String>,
    pub language: Option<String>,
    pub items: Vec<PollItemCandidate>,
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

    pub async fn create_with_interval(
        &self,
        feed_type: FeedType,
        url: &Url,
        title: Option<&str>,
        description: Option<&str>,
        poll_interval: i64,
    ) -> Result<Feed> {
        let id = Uuid::new_v4().to_string();
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        sqlx::query(
            "INSERT INTO feeds (id, feed_type, url, title, description, poll_interval, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(feed_type)
        .bind(url.as_str())
        .bind(title)
        .bind(description)
        .bind(poll_interval)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Feed,
            &id,
            ActivityAction::Create,
            now,
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
            last_error: None,
            is_active: true,
        })
    }

    pub async fn import_outlines(
        &self,
        outlines: Vec<OpmlOutline>,
        default_interval: i64,
    ) -> Result<Vec<Feed>> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let mut imported = Vec::new();

        for outline in outlines {
            let id = Uuid::new_v4().to_string();
            let res = sqlx::query(
                "INSERT OR IGNORE INTO feeds
                 (id, feed_type, url, title, description, poll_interval, created_at, updated_at)
                 VALUES (?, ?, ?, ?, NULL, ?, ?, ?)",
            )
            .bind(&id)
            .bind(outline.feed_type)
            .bind(outline.xml_url.as_str())
            .bind(outline.title.as_deref())
            .bind(default_interval)
            .bind(now)
            .bind(now)
            .execute(&mut *tx)
            .await?;

            if res.rows_affected() > 0 {
                SyncRepo::record_activity(
                    &mut tx,
                    ActivityEntityType::Feed,
                    &id,
                    ActivityAction::Create,
                    now,
                )
                .await?;

                imported.push(Feed {
                    id,
                    feed_type: outline.feed_type,
                    url: outline.xml_url,
                    title: outline.title,
                    description: None,
                    language: None,
                    poll_interval: default_interval,
                    last_polled: None,
                    last_error: None,
                    is_active: true,
                });
            }
        }

        tx.commit().await?;
        Ok(imported)
    }

    pub async fn get(&self, id: &str) -> Result<Option<Feed>> {
        Ok(sqlx::query_as::<_, Feed>("SELECT * FROM feeds WHERE id = ?")
            .bind(id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn list(&self) -> Result<Vec<Feed>> {
        Ok(sqlx::query_as::<_, Feed>("SELECT * FROM feeds ORDER BY created_at DESC")
            .fetch_all(self.pool)
            .await?)
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

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let mut qb = QueryBuilder::new("UPDATE feeds SET updated_at = ");
        qb.push_bind(now);
        if let Some(t) = title {
            qb.push(", title = ").push_bind(t);
        }
        if let Some(interval) = poll_interval {
            qb.push(", poll_interval = ").push_bind(interval);
        }
        if let Some(active) = is_active {
            qb.push(", is_active = ").push_bind(active);
        }
        qb.push(" WHERE id = ").push_bind(id);

        let result = qb.build().execute(&mut *tx).await?;
        if result.rows_affected() == 0 {
            return Ok(false);
        }

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Feed,
            id,
            ActivityAction::Update,
            now,
        )
        .await?;

        tx.commit().await?;
        Ok(true)
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let result =
            sqlx::query("DELETE FROM feeds WHERE id = ?").bind(id).execute(&mut *tx).await?;
        if result.rows_affected() == 0 {
            return Ok(false);
        }
        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Feed,
            id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn get_active_for_polling(&self) -> Result<Vec<Feed>> {
        Ok(sqlx::query_as::<_, Feed>(
            "SELECT * FROM feeds
             WHERE is_active = 1
               AND (last_polled IS NULL OR datetime(last_polled) < datetime('now', '-' || poll_interval || ' minutes'))",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn record_poll_batch(
        &self,
        feed_id: &str,
        batch: &PollBatch,
    ) -> std::result::Result<(), sqlx::Error> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        sqlx::query(
            "UPDATE feeds
             SET title = COALESCE(title, ?),
                 description = COALESCE(description, ?),
                 language = COALESCE(language, ?),
                 last_polled = ?,
                 last_error = NULL,
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(batch.title.as_deref().filter(|s| !s.is_empty()))
        .bind(batch.description.as_deref().filter(|s| !s.is_empty()))
        .bind(batch.language.as_deref().filter(|s| !s.is_empty()))
        .bind(now)
        .bind(now)
        .bind(feed_id)
        .execute(&mut *tx)
        .await?;

        for item in &batch.items {
            let id = Uuid::new_v4().to_string();
            sqlx::query(
                "INSERT OR IGNORE INTO items
                 (id, feed_id, title, url, author, published_at, summary, content_type, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&id)
            .bind(feed_id)
            .bind(&item.title)
            .bind(item.url.as_str())
            .bind(item.author.as_deref())
            .bind(item.published_at)
            .bind(&item.summary)
            .bind(item.content_type)
            .bind(now)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(())
    }

    pub async fn mark_error(&self, id: &str, error: &str) -> Result<()> {
        let now = SyncRepo::now_utc();
        sqlx::query(
            "UPDATE feeds SET last_error = ?, last_polled = ?, updated_at = ? WHERE id = ?",
        )
        .bind(error)
        .bind(now)
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
    use url::Url;

    use crate::db::{ContentType, Db, FeedType, NewItem};

    #[tokio::test]
    async fn test_polling_backoff_and_cascade_delete() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_repo = db.feeds();

        let feed_url = Url::parse("https://example.com/rss.xml").unwrap();
        let feed = feed_repo.create(FeedType::Rss, &feed_url, Some("Example"), None).await.unwrap();

        // Newly created feed has last_polled = NULL so it is eligible for polling
        let active = feed_repo.get_active_for_polling().await.unwrap();
        assert_eq!(active.len(), 1);

        // Marking an error sets last_polled so it backs off until poll_interval elapses
        feed_repo.mark_error(&feed.id, "timeout").await.unwrap();
        let active_after_err = feed_repo.get_active_for_polling().await.unwrap();
        assert!(active_after_err.is_empty());

        // Create an item under this feed and verify ON DELETE CASCADE removes it
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
}
