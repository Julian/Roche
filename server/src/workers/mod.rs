use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use anyhow::Result;

use crate::{config::BackupConfig, db::Db, media::MediaStore};

pub struct WorkerManager {
    db: Db,
    op_log_prune_after_days: i64,
    media_store: MediaStore,
    download_dir: std::path::PathBuf,
    backup_config: Option<BackupConfig>,
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
        Self { db, op_log_prune_after_days, media_store: store, download_dir, backup_config: None }
    }

    #[must_use]
    pub fn with_download_dir(mut self, download_dir: impl Into<std::path::PathBuf>) -> Self {
        self.download_dir = download_dir.into();
        self
    }

    #[must_use]
    pub fn with_backup(mut self, backup_config: BackupConfig) -> Self {
        self.backup_config = Some(backup_config);
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
        let cancel = Arc::new(AtomicBool::new(*shutdown.borrow()));
        let cancel_flag = Arc::clone(&cancel);
        let mut shutdown_for_cancel = shutdown.clone();
        let cancel_watcher = tokio::spawn(async move {
            while shutdown_for_cancel.changed().await.is_ok() {
                if *shutdown_for_cancel.borrow() {
                    cancel_flag.store(true, Ordering::Release);
                    break;
                }
            }
        });

        let mut interval = tokio::time::interval(Duration::from_secs(3600));
        loop {
            if *shutdown.borrow_and_update() {
                cancel.store(true, Ordering::Release);
                break;
            }
            tokio::select! {
                _ = interval.tick() => {
                    if let Err(e) = self.run_once_with_cancel(&cancel).await {
                        tracing::error!(error = %e, "WorkerManager maintenance pass failed");
                    }
                }
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow_and_update() {
                        cancel.store(true, Ordering::Release);
                        break;
                    }
                }
            }
        }
        cancel_watcher.abort();
        Ok(())
    }

    #[cfg(test)]
    pub async fn run_once(&self) -> Result<()> {
        let never_cancel = Arc::new(AtomicBool::new(false));
        self.run_once_with_cancel(&never_cancel).await
    }

    async fn run_once_with_cancel(&self, cancel: &Arc<AtomicBool>) -> Result<()> {
        let (op_res, peer_res, media_res, session_res, board_state_res) = tokio::join!(
            self.run_op_pruner(),
            self.run_peer_pruner(),
            self.run_media_cleaner(),
            self.run_session_pruner(),
            self.run_board_state_pruner(),
        );
        // Run periodic backup after maintenance pruners finish so backups snapshot clean
        // post-prune state and never race `run_media_cleaner` over media files.
        let backup_res = if cancel.load(Ordering::Acquire) {
            Ok(())
        } else {
            self.run_periodic_backup(cancel).await
        };
        let mut first_err: Option<anyhow::Error> = None;
        for (worker, res) in [
            ("op_pruner", op_res),
            ("peer_pruner", peer_res),
            ("media_cleaner", media_res),
            ("session_pruner", session_res),
            ("board_state_pruner", board_state_res),
            ("periodic_backup", backup_res),
        ] {
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

    async fn run_periodic_backup(&self, cancel: &Arc<AtomicBool>) -> Result<()> {
        let Some(ref cfg) = self.backup_config else {
            return Ok(());
        };
        if let Some(outcome) = crate::backup::run_periodic_backup_if_due_with_cancel(
            &self.db,
            cfg,
            self.media_store.base_dir(),
            Arc::clone(cancel),
        )
        .await?
        {
            tracing::info!(
                path = %outcome.path.display(),
                final_bytes = outcome.final_bytes,
                uncompressed_bytes = outcome.uncompressed_bytes,
                pruned = outcome.pruned.len(),
                "Completed periodic database backup"
            );
        }
        Ok(())
    }

    async fn run_board_state_pruner(&self) -> Result<()> {
        let _ = self
            .db
            .boards()
            .prune_expired_item_states(roche_core::boards::BOARD_STATE_GRACE_HOURS)
            .await?;
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

    async fn run_session_pruner(&self) -> Result<()> {
        let _ = self.db.auth().prune_expired_web_sessions().await?;
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

    #[tokio::test]
    async fn test_worker_manager_prunes_expired_board_item_states_after_48_hours() {
        use roche_core::boards::{BoardSourceSpec, HackerNewsSection};

        use crate::db::{BoardPollBatch, BoardPollCandidate};

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Top);
        let board = db.boards().create(&spec, None, None, 30).await.unwrap();

        // Populate snapshot with item 100 only.
        let batch = BoardPollBatch {
            items: vec![BoardPollCandidate {
                external_id: "100".to_owned(),
                rank: 1,
                title: "Still In Snapshot".to_owned(),
                url: "https://example.com/100".to_owned(),
                discussion_url: Some("https://news.ycombinator.com/item?id=100".to_owned()),
                author: Some("alice".to_owned()),
                domain: Some("example.com".to_owned()),
                published_at: None,
                score: Some(50),
                comment_count: Some(10),
                summary: None,
                self_text_html: None,
                metadata_json: "{}".to_owned(),
            }],
        };
        db.boards().record_poll_batch(&board.id, &batch).await.unwrap();

        let old_ts = (chrono::Utc::now() - chrono::Duration::hours(72)).to_rfc3339();
        let recent_ts = (chrono::Utc::now() - chrono::Duration::hours(6)).to_rfc3339();

        // 100: present in board_items, old timestamps -> MUST BE RETAINED.
        // 200: absent from board_items, old last_seen_in_snapshot_at but recent updated_at -> RETAINED.
        // 300: absent from board_items, both timestamps older than 48h -> PRUNED.
        for (ext_id, seen_snap, updated) in
            [("100", &old_ts, &old_ts), ("200", &old_ts, &recent_ts), ("300", &old_ts, &old_ts)]
        {
            sqlx::query(
                "INSERT INTO board_item_states
                     (board_id, external_id, seen_at, dismissed_at, last_seen_in_snapshot_at, updated_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?, '000000000000001-0000-test')",
            )
            .bind(&board.id)
            .bind(ext_id)
            .bind(updated)
            .bind(updated)
            .bind(seen_snap)
            .bind(updated)
            .execute(db.pool())
            .await
            .unwrap();
        }

        let workers = WorkerManager::new(db.clone(), 30, "/tmp/roche-test-board-prune");
        workers.run_once().await.unwrap();

        let remaining: Vec<String> = sqlx::query_scalar(
            "SELECT external_id FROM board_item_states WHERE board_id = ? ORDER BY external_id",
        )
        .bind(&board.id)
        .fetch_all(db.pool())
        .await
        .unwrap();
        assert_eq!(remaining, vec!["100".to_owned(), "200".to_owned()]);

        let tombstones: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM sync_tombstones")
            .fetch_one(db.pool())
            .await
            .unwrap();
        assert_eq!(tombstones, 0, "48h state pruning must not emit sync_tombstones");
    }

    #[tokio::test]
    async fn test_worker_manager_runs_periodic_backup_when_due() {
        let root =
            std::env::temp_dir().join(format!("roche-worker-backup-{}", uuid::Uuid::new_v4()));
        let db_path = root.join("roche.db");
        let media_dir = root.join("media");
        let backup_dir = root.join("backups");
        let db = Db::connect(&db_path).await.unwrap();

        let backup_cfg = BackupConfig {
            enabled: true,
            dir: backup_dir.clone(),
            interval_hours: 24,
            compress: true,
            compression_level: 3,
            include_media: false,
            keep_daily: 3,
            keep_weekly: 1,
            keep_monthly: 1,
        };
        let workers = WorkerManager::new(db, 30, &media_dir).with_backup(backup_cfg);

        workers.run_once().await.unwrap();
        let after_first = crate::backup::list_backups(&backup_dir).await.unwrap();
        assert_eq!(after_first.len(), 1);

        // Second pass within 24h must not create a duplicate backup
        workers.run_once().await.unwrap();
        let after_second = crate::backup::list_backups(&backup_dir).await.unwrap();
        assert_eq!(after_second.len(), 1);
        assert_eq!(after_second[0].file_name, after_first[0].file_name);

        let _ = tokio::fs::remove_dir_all(&root).await;
    }
}
