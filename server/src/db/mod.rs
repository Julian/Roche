use std::{path::Path, str::FromStr, time::Duration};

use anyhow::Result;
use sqlx::sqlite::{
    SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions, SqliteSynchronous,
};

mod auth;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;
mod types;

pub use auth::{AuthRepo, DeviceToken};
pub use feeds::{Feed, FeedRepo, PollBatch, PollItemCandidate};
#[cfg(test)]
pub use items::NewItem;
pub use items::{Item, ItemFilter, ItemPage, ItemRepo, ManualItemInput};
pub use media::{Media, MediaRepo};
pub use sync::{
    ActivityEntry, DEFAULT_ONLINE_PEER_WEIGHT, NetworkTier, PeerSyncRequest, PeerSyncResponse,
    SERVER_PEER_ID, SyncRepo, compute_throughput_weight, should_peer_poll_feed,
};
pub use tags::{ItemTag, Tag, TagRepo};
pub use types::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, ContentType, FeedType, HexColor,
    ItemSortField, ItemStatus, ItemStatusFilter, MediaType, ReadAtPatch, SortOrder,
};

#[derive(Debug, Clone)]
pub struct Db {
    pool: SqlitePool,
}

impl Db {
    /// Connects to the SQLite database at `database_path` and runs all embedded migrations.
    pub async fn connect(database_path: impl AsRef<Path>) -> Result<Self> {
        let path = database_path.as_ref();
        let base_options = match path.to_str() {
            Some(s) if s.starts_with("sqlite:") => SqliteConnectOptions::from_str(s)?,
            _ => {
                if let Some(parent) = path.parent()
                    && !parent.as_os_str().is_empty()
                {
                    tokio::fs::create_dir_all(parent).await?;
                }
                SqliteConnectOptions::new().filename(path)
            }
        };

        let connect_options = base_options
            .create_if_missing(true)
            .foreign_keys(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Normal)
            .busy_timeout(Duration::from_secs(30));

        let pool =
            SqlitePoolOptions::new().max_connections(5).connect_with(connect_options).await?;

        sqlx::migrate!("./migrations").run(&pool).await?;

        let db = Self { pool };
        db.run_startup_maintenance().await;
        verify_and_heal_fts_index(&db.pool).await?;

        Ok(db)
    }

    async fn run_startup_maintenance(&self) {
        const NORMALIZE_SUMMARIES_TASK: &str = "normalize_legacy_html_summaries";
        const BACKFILL_THUMBNAILS_TASK: &str = "backfill_missing_thumbnails";

        self.run_maintenance_task_once(NORMALIZE_SUMMARIES_TASK, async {
            self.items().normalize_legacy_html_summaries().await.map(|_| ())
        })
        .await;
        self.run_maintenance_task_once(BACKFILL_THUMBNAILS_TASK, async {
            self.items().backfill_missing_thumbnails().await.map(|_| ())
        })
        .await;
    }

    async fn run_maintenance_task_once<Fut>(&self, task_name: &str, task: Fut)
    where
        Fut: std::future::Future<Output = Result<()>>,
    {
        match self.is_maintenance_completed(task_name).await {
            Ok(true) => return,
            Ok(false) => {}
            Err(e) => {
                tracing::warn!(
                    task = task_name,
                    error = %e,
                    "Failed to check maintenance_runs state"
                );
            }
        }

        match task.await {
            Ok(()) => {
                if let Err(e) = self.record_maintenance_completed(task_name).await {
                    tracing::warn!(
                        task = task_name,
                        error = %e,
                        "Failed to record completed maintenance task"
                    );
                }
            }
            Err(e) => {
                tracing::warn!(task = task_name, error = %e, "Startup maintenance task failed");
            }
        }
    }

    async fn is_maintenance_completed(&self, task_name: &str) -> Result<bool> {
        let found: Option<i64> =
            sqlx::query_scalar("SELECT 1 FROM maintenance_runs WHERE task_name = ? LIMIT 1")
                .bind(task_name)
                .fetch_optional(&self.pool)
                .await?;
        Ok(found.is_some())
    }

    async fn record_maintenance_completed(&self, task_name: &str) -> Result<()> {
        let completed_at = SyncRepo::now_utc().to_rfc3339();
        sqlx::query(
            "INSERT OR REPLACE INTO maintenance_runs (task_name, completed_at) VALUES (?, ?)",
        )
        .bind(task_name)
        .bind(completed_at)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    #[cfg(test)]
    #[must_use]
    pub const fn pool(&self) -> &SqlitePool {
        &self.pool
    }

    #[must_use]
    pub const fn feeds(&self) -> FeedRepo<'_> {
        FeedRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn items(&self) -> ItemRepo<'_> {
        ItemRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn tags(&self) -> TagRepo<'_> {
        TagRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn media(&self) -> MediaRepo<'_> {
        MediaRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn auth(&self) -> AuthRepo<'_> {
        AuthRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn sync(&self) -> SyncRepo<'_> {
        SyncRepo::new(&self.pool)
    }
}

/// Verifies `items_fts` integrity against the external-content `items` table and rebuilds the
/// FTS5 index if implicit `rowid` compaction (e.g. from an external SQLite `VACUUM` or dump/restore)
/// desynchronized it.
///
/// Note: For external-content FTS5 tables (`content='items'`), `rank = 1` is required so SQLite
/// compares the inverted index against the external `items` table rather than only checking
/// internal FTS5 b-tree structure (`rank = 0` default for external-content tables).
pub async fn verify_and_heal_fts_index(pool: &SqlitePool) -> Result<bool> {
    match sqlx::query("INSERT INTO items_fts(items_fts, rank) VALUES('integrity-check', 1)")
        .execute(pool)
        .await
    {
        Ok(_) => Ok(false),
        Err(e) => {
            tracing::warn!(
                error = %e,
                "items_fts external-content integrity check failed (likely after external VACUUM); rebuilding FTS5 index"
            );
            sqlx::query("INSERT INTO items_fts(items_fts) VALUES('rebuild')").execute(pool).await?;
            Ok(true)
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use url::Url;

    use super::*;

    #[tokio::test]
    async fn test_startup_maintenance_marks_completion_and_skips_on_reconnect() {
        let temp_dir =
            std::env::temp_dir().join(format!("roche-maint-test-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&temp_dir).await.unwrap();
        let db_path = temp_dir.join("roche.db");

        // 1. Initial connect creates maintenance_runs records for both startup tasks
        let db1 = Db::connect(&db_path).await.unwrap();
        let recorded_count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM maintenance_runs")
            .fetch_one(db1.pool())
            .await
            .unwrap();
        assert_eq!(recorded_count, 2);

        // Simulate a pre-009 database with unnormalized HTML summary and missing thumbnail
        let url1 = Url::parse("https://example.com/legacy-post").unwrap();
        let item1 = db1
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Legacy Post",
                url: &url1,
                author: None,
                published_at: None,
                summary: Some(
                    "<p>Legacy &amp; HTML summary <img src=\"https://example.com/hero.jpg\"/></p>",
                ),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        sqlx::query("DELETE FROM maintenance_runs").execute(db1.pool()).await.unwrap();
        db1.pool().close().await;
        drop(db1);

        // 2. Reconnecting runs both tasks once and records completion timestamps
        let db2 = Db::connect(&db_path).await.unwrap();
        let normalized_item = db2.items().get(&item1.id).await.unwrap().unwrap();
        assert_eq!(normalized_item.summary.as_deref(), Some("Legacy & HTML summary"));
        assert_eq!(normalized_item.thumbnail_url.as_deref(), Some("https://example.com/hero.jpg"));

        let first_runs: Vec<(String, String)> = sqlx::query_as(
            "SELECT task_name, completed_at FROM maintenance_runs ORDER BY task_name ASC",
        )
        .fetch_all(db2.pool())
        .await
        .unwrap();
        assert_eq!(first_runs.len(), 2);

        // Inject raw unnormalized state while maintenance_runs already marks both tasks completed
        let url2 = Url::parse("https://example.com/post-after-maint").unwrap();
        let item2 = db2
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Post After Maint",
                url: &url2,
                author: None,
                published_at: None,
                summary: Some("<p>Should NOT be rescanned on reconnect</p>"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        sqlx::query("UPDATE items SET content_html = '<img src=\"https://example.com/skip.jpg\"/>', thumbnail_url = NULL WHERE id = ?")
            .bind(&item2.id)
            .execute(db2.pool())
            .await
            .unwrap();
        db2.pool().close().await;
        drop(db2);

        // 3. Subsequent Db::connect skips both full-table scans and preserves completed_at
        let db3 = Db::connect(&db_path).await.unwrap();
        let untouched = db3.items().get(&item2.id).await.unwrap().unwrap();
        assert_eq!(
            untouched.summary.as_deref(),
            Some("<p>Should NOT be rescanned on reconnect</p>")
        );
        assert!(untouched.thumbnail_url.is_none());

        let second_runs: Vec<(String, String)> = sqlx::query_as(
            "SELECT task_name, completed_at FROM maintenance_runs ORDER BY task_name ASC",
        )
        .fetch_all(db3.pool())
        .await
        .unwrap();
        assert_eq!(second_runs, first_runs);

        db3.pool().close().await;
        drop(db3);
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_verify_and_heal_fts_index_recovers_after_external_vacuum() {
        let temp_dir =
            std::env::temp_dir().join(format!("roche-fts-vacuum-test-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&temp_dir).await.unwrap();
        let db_path = temp_dir.join("roche.db");

        let db = Db::connect(&db_path).await.unwrap();
        let items = db.items();

        let url1 = Url::parse("https://example.com/vacuum-1").unwrap();
        let item1 = items
            .create(&NewItem {
                feed_id: None,
                title: "Alpha Ephemeral First Entry",
                url: &url1,
                author: Some("Author One"),
                published_at: None,
                summary: Some("First item to be deleted before vacuum"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        let url2 = Url::parse("https://example.com/vacuum-2").unwrap();
        let item2 = items
            .create(&NewItem {
                feed_id: None,
                title: "Bravo Distributed Consensus",
                url: &url2,
                author: Some("Author Two"),
                published_at: None,
                summary: Some("Second item surviving rowid compaction"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        let url3 = Url::parse("https://example.com/vacuum-3").unwrap();
        let item3 = items
            .create(&NewItem {
                feed_id: None,
                title: "Charlie Quantum Cryptography",
                url: &url3,
                author: Some("Author Three"),
                published_at: None,
                summary: Some("Third item surviving rowid compaction"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        assert!(items.delete(&item1.id).await.unwrap());
        assert!(!verify_and_heal_fts_index(db.pool()).await.unwrap());

        // Legacy external SQLite (< 3.32) `VACUUM` or dump/restore compacts implicit `rowid`s on
        // `items` (`TEXT PRIMARY KEY`) from (2, 3) -> (1, 2) without firing `items_fts_au`
        // (`AFTER UPDATE OF title, author, summary, content_html ON items`). Bundled modern SQLite
        // preserves `rowid` during in-process `VACUUM`, so if `min(rowid)` is still > 1 after
        // `VACUUM`, compact `rowid` directly to reproduce the exact trigger-bypassing desync.
        sqlx::query("VACUUM").execute(db.pool()).await.unwrap();
        let min_rowid: i64 =
            sqlx::query_scalar("SELECT MIN(rowid) FROM items").fetch_one(db.pool()).await.unwrap();
        if min_rowid > 1 {
            sqlx::query("UPDATE items SET rowid = rowid - 1").execute(db.pool()).await.unwrap();
        }

        // Before self-healing, `items_fts` rowid=3 (`item3`) no longer matches any `items.rowid`.
        let broken_charlie = items
            .list_filtered_page(&ItemFilter {
                search: Some("charlie quantum".to_string()),
                ..ItemFilter::default()
            })
            .await
            .unwrap();
        assert!(broken_charlie.items.is_empty());

        assert!(
            verify_and_heal_fts_index(db.pool()).await.unwrap(),
            "verify_and_heal_fts_index must detect rowid desync after VACUUM and rebuild items_fts"
        );
        assert!(
            !verify_and_heal_fts_index(db.pool()).await.unwrap(),
            "Rebuilt items_fts must pass integrity-check cleanly"
        );

        let bravo_page = items
            .list_filtered_page(&ItemFilter {
                search: Some("bravo consensus".to_string()),
                ..ItemFilter::default()
            })
            .await
            .unwrap();
        assert_eq!(bravo_page.items.len(), 1);
        assert_eq!(bravo_page.items[0].id, item2.id);

        let charlie_page = items
            .list_filtered_page(&ItemFilter {
                search: Some("charlie quantum".to_string()),
                ..ItemFilter::default()
            })
            .await
            .unwrap();
        assert_eq!(charlie_page.items.len(), 1);
        assert_eq!(charlie_page.items[0].id, item3.id);

        let deleted_page = items
            .list_filtered_page(&ItemFilter {
                search: Some("ephemeral".to_string()),
                ..ItemFilter::default()
            })
            .await
            .unwrap();
        assert!(deleted_page.items.is_empty());

        db.pool().close().await;
        drop(db);
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
