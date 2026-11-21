use std::time::Duration;

use anyhow::Result;

use crate::{db::Db, media::MediaStore};

pub struct WorkerManager {
    db: Db,
    op_log_prune_after_days: i64,
    media_store: MediaStore,
    download_dir: std::path::PathBuf,
}

impl WorkerManager {
    #[must_use]
    pub fn new(
        db: Db,
        op_log_prune_after_days: i64,
        media_dir: impl Into<std::path::PathBuf>,
    ) -> Self {
        let store = MediaStore::new(media_dir);
        let download_dir = store.base_dir().join("downloads");
        Self { db, op_log_prune_after_days, media_store: store, download_dir }
    }

    #[must_use]
    pub fn with_download_dir(mut self, download_dir: impl Into<std::path::PathBuf>) -> Self {
        self.download_dir = download_dir.into();
        self
    }

    /// Cleans leftover partial `downloads/<item_id>` directories and stale `.tmp-*` files on startup
    /// before any background download workers begin.
    pub async fn cleanup_startup_partials(&self) -> Result<()> {
        let _ = self.media_store.cleanup_stale_temp_files(Duration::ZERO).await;
        let registered = self.db.media().list_registered_item_ids().await?;
        let _ = self
            .media_store
            .cleanup_unregistered_download_dirs(&self.download_dir, &registered, Duration::ZERO)
            .await;
        Ok(())
    }

    pub async fn run_all_until_shutdown(
        &self,
        mut shutdown: tokio::sync::watch::Receiver<bool>,
    ) -> Result<()> {
        let mut interval = tokio::time::interval(Duration::from_secs(3600));
        loop {
            if *shutdown.borrow_and_update() {
                break;
            }
            tokio::select! {
                _ = interval.tick() => {
                    if let Err(e) = self.run_once().await {
                        tracing::error!(error = %e, "WorkerManager maintenance pass failed");
                    }
                }
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow_and_update() {
                        break;
                    }
                }
            }
        }
        Ok(())
    }

    pub async fn run_once(&self) -> Result<()> {
        let (op_res, peer_res, media_res) =
            tokio::join!(self.run_op_pruner(), self.run_peer_pruner(), self.run_media_cleaner());
        let mut first_err: Option<anyhow::Error> = None;
        for (worker, res) in
            [("op_pruner", op_res), ("peer_pruner", peer_res), ("media_cleaner", media_res)]
        {
            if let Err(e) = res {
                tracing::error!(worker = worker, error = %e, "WorkerManager task failed");
                if first_err.is_none() {
                    first_err = Some(e);
                }
            }
        }
        if let Some(err) = first_err {
            return Err(err);
        }
        Ok(())
    }

    async fn run_op_pruner(&self) -> Result<()> {
        self.db.sync().prune_activity_older_than_days(self.op_log_prune_after_days).await?;
        Ok(())
    }

    async fn run_peer_pruner(&self) -> Result<()> {
        let _ = self.db.sync().prune_stale_peer_nodes(self.op_log_prune_after_days.max(30)).await?;
        Ok(())
    }

    async fn run_media_cleaner(&self) -> Result<()> {
        let _ = self.media_store.cleanup_stale_temp_files(Duration::from_secs(600)).await;
        let media_repo = self.db.media();
        let orphans = media_repo.find_orphaned().await?;
        let _ = crate::service::unlink_and_delete_media_rows(&self.db, &self.media_store, orphans)
            .await?;
        if let Ok(registered) = media_repo.list_registered_item_ids().await {
            let _ = self
                .media_store
                .cleanup_unregistered_download_dirs(
                    &self.download_dir,
                    &registered,
                    Duration::from_mins(11),
                )
                .await;
        }
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use url::Url;

    use super::*;
    use crate::{
        db::{Db, NewItem},
        domain::{ContentType, MediaType},
    };

    #[tokio::test]
    async fn test_worker_manager_prunes_old_activity_logs_and_orphaned_media() {
        let db = Db::connect("sqlite::memory:").await.unwrap();

        sqlx::query!(
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

        let remaining: i64 = sqlx::query_scalar!(
            "SELECT COUNT(*) AS \"count!: i64\" FROM activity_log WHERE entity_id IN ('1', '2')",
        )
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(remaining, 1);
        assert!(db.media().get(&media.id).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn test_run_all_until_shutdown_exits_immediately_when_already_true() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        sqlx::query(
            "INSERT INTO activity_log (entity_type, entity_id, action, timestamp)
             VALUES ('item', 'stale-1', 'archive', strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-60 days'))",
        )
        .execute(db.pool())
        .await
        .unwrap();

        let workers = WorkerManager::new(db.clone(), 30, "/tmp/roche-test-media-shutdown");
        let (tx, rx) = tokio::sync::watch::channel(true);
        let res =
            tokio::time::timeout(Duration::from_secs(1), workers.run_all_until_shutdown(rx)).await;
        assert!(res.is_ok(), "shutdown loop must exit immediately when shutdown starts as true");
        assert!(res.unwrap().is_ok());
        drop(tx);

        // Must exit before executing the initial maintenance tick.
        let count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM activity_log WHERE entity_id = 'stale-1'")
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    async fn test_worker_manager_retains_db_row_on_unlink_failure_and_prunes_stale_peers() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let item_url = Url::parse("https://example.com/escape-media").unwrap();
        let item = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Escape Media",
                url: &item_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Video,
            })
            .await
            .unwrap();
        let bad_media = db
            .media()
            .create(&item.id, MediaType::Video, "../outside-media-dir.mp4", Some(512), None)
            .await
            .unwrap();
        db.items().delete(&item.id).await.unwrap();

        let old_ts = chrono::Utc::now() - chrono::Duration::days(45);
        db.sync()
            .upsert_peer_node(
                "stale-peer-99",
                "Old Phone",
                crate::db::NetworkTier::Wifi,
                1.0,
                old_ts,
            )
            .await
            .unwrap();

        let workers = WorkerManager::new(db.clone(), 30, "/tmp/roche-test-media-m16");
        workers.run_once().await.unwrap();

        // Failed unlink retains the DB row so future passes can retry rather than stranding files
        assert!(db.media().get(&bad_media.id).await.unwrap().is_some());
        // Stale peer was pruned independently in the same pass
        assert!(db.sync().get_peer_weight("stale-peer-99").await.unwrap().is_none());
    }
}
