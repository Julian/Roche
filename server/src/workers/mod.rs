use std::time::Duration;

use anyhow::Result;

use crate::{db::Db, media::MediaStore};

pub struct WorkerManager {
    db: Db,
    op_log_prune_after_days: i64,
    media_store: MediaStore,
}

impl WorkerManager {
    #[must_use]
    pub fn new(
        db: Db,
        op_log_prune_after_days: i64,
        media_dir: impl Into<std::path::PathBuf>,
    ) -> Self {
        Self { db, op_log_prune_after_days, media_store: MediaStore::new(media_dir) }
    }

    pub async fn run_all(&self) -> Result<()> {
        let mut interval = tokio::time::interval(Duration::from_secs(3600));
        loop {
            interval.tick().await;
            if let Err(e) = self.run_once().await {
                tracing::error!(error = %e, "WorkerManager maintenance pass failed");
            }
        }
    }

    pub async fn run_once(&self) -> Result<()> {
        tokio::try_join!(self.run_op_pruner(), self.run_media_cleaner())?;
        Ok(())
    }

    async fn run_op_pruner(&self) -> Result<()> {
        self.db.sync().prune_activity_older_than_days(self.op_log_prune_after_days).await?;
        Ok(())
    }

    async fn run_media_cleaner(&self) -> Result<()> {
        let _ = self.media_store.cleanup_stale_temp_files(Duration::from_secs(600)).await;
        let media_repo = self.db.media();
        let orphans = media_repo.find_orphaned().await?;
        media_repo.delete_with_files(&self.media_store, orphans).await
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use url::Url;

    use super::*;
    use crate::db::{ContentType, Db, MediaType, NewItem};

    #[tokio::test]
    async fn test_worker_manager_prunes_old_activity_logs_and_orphaned_media() {
        let db = Db::connect("sqlite::memory:").await.unwrap();

        sqlx::query(
            "INSERT INTO activity_log (entity_type, entity_id, action, timestamp)
             VALUES ('item', '1', 'archive', strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-60 days')),
                    ('item', '2', 'archive', strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))",
        )
        .execute(db.pool())
        .await
        .unwrap();

        let item_url = Url::parse("https://example.com/media-item").unwrap();
        let item = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Media Item",
                url: &item_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Video,
            })
            .await
            .unwrap();
        let media = db
            .media()
            .create(
                &item.id,
                MediaType::Video,
                "/tmp/roche-test-media/nonexistent-roche-orphan.mp4",
                Some(1024),
                None,
            )
            .await
            .unwrap();
        db.items().delete(&item.id).await.unwrap();
        // Deleting the item also records a delete activity entry
        assert_eq!(db.media().find_orphaned().await.unwrap().len(), 1);

        let workers = WorkerManager::new(db.clone(), 30, "/tmp/roche-test-media");
        workers.run_once().await.unwrap();

        let remaining: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM activity_log WHERE entity_id IN ('1', '2')")
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(remaining, 1);
        assert!(db.media().get(&media.id).await.unwrap().is_none());
    }
}
