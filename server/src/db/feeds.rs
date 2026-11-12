use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, SqlitePool};
use url::Url;

use super::{
    ActivityAction, ActivityEntityType, ContentType, FeedType, SyncRepo,
    sync::{
        SERVER_PEER_ID, deterministic_feed_id, deterministic_item_id, feed_natural_key,
        item_natural_key,
    },
};
use crate::feeds::OpmlOutline;

macro_rules! feed_select_sql {
    ($suffix:literal) => {
        concat!(
            "SELECT f.id, f.feed_type, f.url, f.title, f.description, f.language,
       f.poll_interval, f.last_polled, f.last_polled_by, f.last_error, f.last_error_by, f.is_active,
       f.created_at,
       (SELECT COUNT(*) FROM items i WHERE i.feed_id = f.id AND i.status = 'unread' AND i.read_at IS NULL) AS unread_count
FROM feeds f ",
            $suffix
        )
    };
}

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
    #[serde(default)]
    pub last_polled_by: Option<String>,
    pub last_error: Option<String>,
    #[serde(default)]
    pub last_error_by: Option<String>,
    pub is_active: bool,
    pub created_at: DateTime<Utc>,
    pub unread_count: i64,
}

#[derive(Debug, Clone)]
pub struct PollItemCandidate {
    pub title: String,
    pub url: Url,
    pub author: Option<String>,
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
        let id = deterministic_feed_id(url.as_str());
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        SyncRepo::clear_tombstone(
            &mut tx,
            ActivityEntityType::Feed,
            &feed_natural_key(url.as_str()),
        )
        .await?;

        let existing_archived_id: Option<String> = sqlx::query_scalar(
            "SELECT id FROM feeds WHERE (url = ? OR id = ?) AND is_active = 0 LIMIT 1",
        )
        .bind(url.as_str())
        .bind(&id)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(existing_id) = existing_archived_id {
            sqlx::query(
                "UPDATE feeds
                 SET is_active = 1,
                     title = COALESCE(?, title),
                     description = COALESCE(?, description),
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(title)
            .bind(description)
            .bind(now)
            .bind(&existing_id)
            .execute(&mut *tx)
            .await?;

            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Feed,
                &existing_id,
                ActivityAction::Unarchive,
                now,
            )
            .await?;

            tx.commit().await?;
            return self
                .get(&existing_id)
                .await?
                .ok_or_else(|| anyhow::anyhow!("Feed disappeared after unarchive"));
        }

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
            last_polled_by: None,
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: now,
            unread_count: 0,
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
        tx: &mut sqlx::SqliteConnection,
        outline: OpmlOutline,
        default_interval: i64,
        now: DateTime<Utc>,
    ) -> Result<Option<Feed>> {
        let id = deterministic_feed_id(outline.xml_url.as_str());
        let clean_title = outline.title.as_deref().map(str::trim).filter(|t| !t.is_empty());
        SyncRepo::clear_tombstone(
            &mut *tx,
            ActivityEntityType::Feed,
            &feed_natural_key(outline.xml_url.as_str()),
        )
        .await?;

        let existing_archived_id: Option<String> = sqlx::query_scalar(
            "SELECT id FROM feeds WHERE (url = ? OR id = ?) AND is_active = 0 LIMIT 1",
        )
        .bind(outline.xml_url.as_str())
        .bind(&id)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(existing_id) = existing_archived_id {
            sqlx::query(
                "UPDATE feeds
                 SET is_active = 1,
                     title = COALESCE(title, ?),
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(clean_title)
            .bind(now)
            .bind(&existing_id)
            .execute(&mut *tx)
            .await?;

            SyncRepo::record_activity(
                &mut *tx,
                ActivityEntityType::Feed,
                &existing_id,
                ActivityAction::Unarchive,
                now,
            )
            .await?;

            return Ok(sqlx::query_as::<_, Feed>(feed_select_sql!("WHERE f.id = ?"))
                .bind(&existing_id)
                .fetch_optional(&mut *tx)
                .await?);
        }

        let res = sqlx::query(
            "INSERT OR IGNORE INTO feeds
             (id, feed_type, url, title, description, poll_interval, created_at, updated_at)
             VALUES (?, ?, ?, ?, NULL, ?, ?, ?)",
        )
        .bind(&id)
        .bind(outline.feed_type)
        .bind(outline.xml_url.as_str())
        .bind(clean_title)
        .bind(default_interval)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        if res.rows_affected() == 0 {
            return Ok(None);
        }

        SyncRepo::record_activity(
            &mut *tx,
            ActivityEntityType::Feed,
            &id,
            ActivityAction::Create,
            now,
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
            is_active: true,
            created_at: now,
            unread_count: 0,
        }))
    }

    pub async fn get(&self, id: &str) -> Result<Option<Feed>> {
        Ok(sqlx::query_as::<_, Feed>(feed_select_sql!("WHERE f.id = ?"))
            .bind(id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn list(&self) -> Result<Vec<Feed>> {
        Ok(sqlx::query_as::<_, Feed>(feed_select_sql!("ORDER BY f.created_at DESC"))
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

        let action = match is_active {
            Some(false) => ActivityAction::Archive,
            Some(true) => ActivityAction::Unarchive,
            None => ActivityAction::Update,
        };

        SyncRepo::record_activity(&mut tx, ActivityEntityType::Feed, id, action, now).await?;

        tx.commit().await?;
        Ok(true)
    }

    pub async fn archive(&self, id: &str) -> Result<bool> {
        self.update(id, None, None, Some(false)).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, None, None, Some(true)).await
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let deleted_url: Option<String> =
            sqlx::query_scalar("DELETE FROM feeds WHERE id = ? RETURNING url")
                .bind(id)
                .fetch_optional(&mut *tx)
                .await?;
        let Some(feed_url) = deleted_url else {
            return Ok(false);
        };
        SyncRepo::record_tombstone(
            &mut tx,
            ActivityEntityType::Feed,
            &feed_natural_key(&feed_url),
            now,
        )
        .await?;
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

    pub async fn get_active_for_cooperative_polling(
        &self,
        local_peer_id: &str,
    ) -> Result<Vec<Feed>> {
        Ok(sqlx::query_as::<_, Feed>(feed_select_sql!(
            "WHERE f.is_active = 1
               AND (
                   f.last_polled IS NULL
                   OR datetime(f.last_polled) < datetime('now', '-' || f.poll_interval || ' minutes')
                   OR (f.last_error IS NOT NULL AND f.last_error_by IS NOT NULL AND f.last_error_by != ?)
               )"
        ))
        .bind(local_peer_id)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn record_poll_batch(
        &self,
        feed_id: &str,
        batch: &PollBatch,
    ) -> std::result::Result<Vec<(String, PollItemCandidate)>, sqlx::Error> {
        self.record_poll_batch_by_peer(feed_id, batch, SERVER_PEER_ID).await
    }

    pub async fn record_poll_batch_by_peer(
        &self,
        feed_id: &str,
        batch: &PollBatch,
        peer_id: &str,
    ) -> std::result::Result<Vec<(String, PollItemCandidate)>, sqlx::Error> {
        type FeedMetaRow = (String, Option<String>, Option<String>, Option<String>);
        let now = SyncRepo::now_utc();

        let new_title = batch.title.as_deref().filter(|s| !s.is_empty());
        let new_desc = batch.description.as_deref().filter(|s| !s.is_empty());
        let new_lang = batch.language.as_deref().filter(|s| !s.is_empty());

        // Read existing metadata before starting the transaction so that `tx` begins
        // directly with an `UPDATE` statement (acquiring a write lock immediately
        // instead of upgrading a deferred read snapshot, which avoids SQLITE_BUSY_SNAPSHOT).
        let existing_meta: Option<FeedMetaRow> =
            sqlx::query_as("SELECT url, title, description, language FROM feeds WHERE id = ?")
                .bind(feed_id)
                .fetch_optional(self.pool)
                .await?;

        let feed_url = existing_meta.as_ref().map(|(u, _, _, _)| u.clone());
        let metadata_updated =
            existing_meta.as_ref().is_some_and(|(_, cur_title, cur_desc, cur_lang)| {
                (cur_title.is_none() && new_title.is_some())
                    || (cur_desc.is_none() && new_desc.is_some())
                    || (cur_lang.is_none() && new_lang.is_some())
            });

        let mut tx = self.pool.begin().await?;

        sqlx::query(
            "UPDATE feeds
             SET title = COALESCE(title, ?), description = COALESCE(description, ?),
                 language = COALESCE(language, ?), last_polled = ?, last_polled_by = ?,
                 last_error = NULL, last_error_by = NULL, updated_at = ?
             WHERE id = ?",
        )
        .bind(new_title)
        .bind(new_desc)
        .bind(new_lang)
        .bind(now)
        .bind(peer_id)
        .bind(now)
        .bind(feed_id)
        .execute(&mut *tx)
        .await?;

        if metadata_updated {
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Feed,
                feed_id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }

        let mut inserted = Vec::new();
        for item in &batch.items {
            let natural_key = item_natural_key(feed_url.as_deref(), item.url.as_str());
            let id = deterministic_item_id(feed_url.as_deref(), item.url.as_str());
            let clean_duration = item.duration.filter(|&d| d > 0);
            let result = sqlx::query(
                "INSERT OR IGNORE INTO items
                 (id, feed_id, title, url, author, published_at, summary, content_html, thumbnail_url, audio_url, duration, content_type, created_at, updated_at)
                 SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                 WHERE NOT EXISTS (
                     SELECT 1 FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ?
                 )",
            )
            .bind(&id)
            .bind(feed_id)
            .bind(&item.title)
            .bind(item.url.as_str())
            .bind(item.author.as_deref())
            .bind(item.published_at)
            .bind(&item.summary)
            .bind(item.content_html.as_deref())
            .bind(item.thumbnail_url.as_deref())
            .bind(item.audio_url.as_deref())
            .bind(clean_duration)
            .bind(item.content_type)
            .bind(now)
            .bind(now)
            .bind(&natural_key)
            .execute(&mut *tx)
            .await?;

            if result.rows_affected() > 0 {
                SyncRepo::record_activity(
                    &mut tx,
                    ActivityEntityType::Item,
                    &id,
                    ActivityAction::Create,
                    now,
                )
                .await?;
                inserted.push((id, item.clone()));
            } else {
                Self::backfill_existing_poll_item_in_tx(
                    &mut tx,
                    feed_id,
                    item,
                    clean_duration,
                    now,
                )
                .await?;
            }
        }

        tx.commit().await?;
        Ok(inserted)
    }

    async fn backfill_existing_poll_item_in_tx(
        conn: &mut sqlx::SqliteConnection,
        feed_id: &str,
        item: &PollItemCandidate,
        clean_duration: Option<i64>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        if item.audio_url.is_none()
            && clean_duration.is_none()
            && item.thumbnail_url.is_none()
            && item.author.is_none()
        {
            return Ok(());
        }
        let existing_id: Option<String> =
            sqlx::query_scalar("SELECT id FROM items WHERE feed_id = ? AND url = ? LIMIT 1")
                .bind(feed_id)
                .bind(item.url.as_str())
                .fetch_optional(&mut *conn)
                .await?;
        if let Some(existing_id) = existing_id {
            let update_res = sqlx::query(
                "UPDATE items
                 SET audio_url = COALESCE(audio_url, ?),
                     duration = COALESCE(duration, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     author = COALESCE(author, ?),
                     content_type = CASE WHEN ? IS NOT NULL THEN 'audio' ELSE content_type END,
                     updated_at = ?
                 WHERE id = ?
                   AND ((audio_url IS NULL AND ? IS NOT NULL)
                        OR (duration IS NULL AND ? IS NOT NULL)
                        OR (thumbnail_url IS NULL AND ? IS NOT NULL)
                        OR (author IS NULL AND ? IS NOT NULL))",
            )
            .bind(item.audio_url.as_deref())
            .bind(clean_duration)
            .bind(item.thumbnail_url.as_deref())
            .bind(item.author.as_deref())
            .bind(item.audio_url.as_deref())
            .bind(now)
            .bind(&existing_id)
            .bind(item.audio_url.as_deref())
            .bind(clean_duration)
            .bind(item.thumbnail_url.as_deref())
            .bind(item.author.as_deref())
            .execute(&mut *conn)
            .await?;
            if update_res.rows_affected() > 0 {
                SyncRepo::record_activity(
                    &mut *conn,
                    ActivityEntityType::Item,
                    &existing_id,
                    ActivityAction::Update,
                    now,
                )
                .await?;
            }
        }
        Ok(())
    }

    pub async fn mark_error(&self, id: &str, error: &str) -> Result<()> {
        self.mark_error_by_peer(id, error, SERVER_PEER_ID).await
    }

    pub async fn mark_error_by_peer(&self, id: &str, error: &str, peer_id: &str) -> Result<()> {
        let now = SyncRepo::now_utc();
        sqlx::query(
            "UPDATE feeds SET last_error = ?, last_error_by = ?, last_polled = ?, updated_at = ? WHERE id = ?",
        )
        .bind(error)
        .bind(peer_id)
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

    use crate::{
        db::{ActivityAction, ActivityEntityType, ContentType, Db, FeedType, NewItem},
        feeds::OpmlOutline,
    };

    #[tokio::test]
    async fn test_polling_backoff_and_cascade_delete() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_repo = db.feeds();

        let feed_url = Url::parse("https://example.com/rss.xml").unwrap();
        let feed = feed_repo.create(FeedType::Rss, &feed_url, Some("Example"), None).await.unwrap();

        // Newly created feed has last_polled = NULL so it is eligible for polling
        let active = feed_repo.get_active_for_cooperative_polling("server").await.unwrap();
        assert_eq!(active.len(), 1);

        // Marking an error sets last_polled so it backs off until poll_interval elapses
        feed_repo.mark_error(&feed.id, "timeout").await.unwrap();
        let active_after_err =
            feed_repo.get_active_for_cooperative_polling("server").await.unwrap();
        assert!(active_after_err.is_empty());

        // Another peer IS immediately eligible to rescue a feed that failed on "server"
        let active_for_rescue_peer =
            feed_repo.get_active_for_cooperative_polling("android-peer-1").await.unwrap();
        assert_eq!(active_for_rescue_peer.len(), 1);

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

        assert!(feed_repo.archive(&feed.id).await.unwrap());
        let archived_feed = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert!(!archived_feed.is_active);
        assert_eq!(archived_feed.unread_count, 1);

        let active = feed_repo.get_active_for_cooperative_polling("server").await.unwrap();
        assert!(active.is_empty());

        let preserved = db.items().get(&item.id).await.unwrap().unwrap();
        assert_eq!(preserved.title, "Preserved Item");

        assert!(feed_repo.unarchive(&feed.id).await.unwrap());
        let unarchived_feed = feed_repo.get(&feed.id).await.unwrap().unwrap();
        assert!(unarchived_feed.is_active);
        let active_after = feed_repo.get_active_for_cooperative_polling("server").await.unwrap();
        assert_eq!(active_after.len(), 1);

        // Re-subscribing to an archived feed URL unarchives it and preserves its items
        assert!(feed_repo.archive(&feed.id).await.unwrap());
        let resubscribed = feed_repo
            .create(FeedType::Rss, &feed_url, Some("Resubscribed Title"), None)
            .await
            .unwrap();
        assert_eq!(resubscribed.id, feed.id);
        assert!(resubscribed.is_active);
        assert_eq!(resubscribed.title.as_deref(), Some("Resubscribed Title"));
        assert_eq!(resubscribed.unread_count, 1);
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
