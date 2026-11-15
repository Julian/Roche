use std::{path::Path, str::FromStr, time::Duration};

use anyhow::Result;
use sqlx::sqlite::{
    SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions, SqliteSynchronous,
};

mod auth;
mod catalog;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;
mod types;

pub use auth::{AuthRepo, DeviceToken};
pub use catalog::{
    CatalogEntry, CatalogError, CatalogRepo, ClusterSelectFilter, CollectionSummary,
    ComparisonRecord, ConsumptionLogView, EntryDetailView, EntryIdentifier, Generator1001State,
    RankedCollectionView, RankedEntryRow, UpsertEntryInput,
};
pub use feeds::{Feed, FeedRepo, PollBatch, PollItemCandidate};
#[cfg(test)]
pub use items::NewItem;
pub use items::{Item, ItemFilter, ItemPage, ItemRepo, ManualItemInput};
pub use media::{Media, MediaRepo};
pub use sync::{
    ALBUMS_1001_COLLECTION_ID, ActivityEntry, DEFAULT_ONLINE_PEER_WEIGHT, NetworkTier,
    PeerSyncRequest, PeerSyncResponse, SERVER_PEER_ID, STARRED_COLLECTION_ID, SyncRepo,
    UP_NEXT_COLLECTION_ID, compute_throughput_weight, should_peer_poll_feed,
};
pub use tags::{ItemTag, Tag, TagRepo};
pub use types::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, CollectionMode, CollectionRole,
    ConsumptionStatus, ContentType, EntryKind, FeedType, HexColor, InboxState, ItemSortField,
    ItemStatus, ItemStatusFilter, MediaType, ReadAtPatch, SortOrder,
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
    pub const fn catalog(&self) -> CatalogRepo<'_> {
        CatalogRepo::new(&self.pool)
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

/// Verifies `entries_fts` integrity against `entries` + `entry_content` and rebuilds the FTS5 index when needed.
///
/// Rebuilds automatically if implicit `rowid` compaction (e.g. from an external SQLite `VACUUM`
/// or dump/restore) or b-tree corruption desynchronized it.
pub async fn verify_and_heal_fts_index(pool: &SqlitePool) -> Result<bool> {
    let btree_ok = sqlx::query("INSERT INTO entries_fts(entries_fts) VALUES('integrity-check')")
        .execute(pool)
        .await
        .is_ok();

    let desynced = if btree_ok {
        let mismatch: bool = sqlx::query_scalar(
            "SELECT EXISTS (
                 SELECT 1 FROM entries e
                 LEFT JOIN entries_fts f ON f.rowid = e.rowid AND f.entry_id = e.id
                 WHERE e.merged_into_id IS NULL AND f.rowid IS NULL
             ) OR (
                 (SELECT COUNT(*) FROM entries_fts) !=
                 (SELECT COUNT(*) FROM entries WHERE merged_into_id IS NULL)
             )",
        )
        .fetch_one(pool)
        .await?;
        mismatch
    } else {
        true
    };

    if !desynced {
        return Ok(false);
    }

    tracing::warn!(
        "entries_fts integrity/rowid check failed (likely after external VACUUM); rebuilding FTS5 index"
    );
    let mut tx = pool.begin().await?;
    sqlx::query("DELETE FROM entries_fts").execute(&mut *tx).await?;
    sqlx::query(
        "INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
         SELECT e.rowid, e.id, e.title, e.creators_json, COALESCE(e.summary, ''), COALESCE(ec.content_html, '')
         FROM entries e
         LEFT JOIN entry_content ec ON ec.entry_id = e.id
         WHERE e.merged_into_id IS NULL",
    )
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(true)
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
        sqlx::query(
            "UPDATE entry_content SET content_html = '<img src=\"https://example.com/skip.jpg\"/>' WHERE entry_id = ?",
        )
        .bind(&item2.id)
        .execute(db2.pool())
        .await
        .unwrap();
        sqlx::query("UPDATE entries SET thumbnail_url = NULL WHERE id = ?")
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
        let fts_rowid_2: i64 =
            sqlx::query_scalar("SELECT rowid FROM entries_fts WHERE entry_id = ?")
                .bind(&item2.id)
                .fetch_one(db.pool())
                .await
                .unwrap();
        let entry_rowid_2: i64 = sqlx::query_scalar("SELECT rowid FROM entries WHERE id = ?")
            .bind(&item2.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
        if entry_rowid_2 == fts_rowid_2 {
            sqlx::query("UPDATE entries SET rowid = rowid - 1 WHERE id IN (?, ?)")
                .bind(&item2.id)
                .bind(&item3.id)
                .execute(db.pool())
                .await
                .unwrap();
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

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_v009_to_v010_populated_schema_migration_is_lossless() {
        let temp_dir =
            std::env::temp_dir().join(format!("roche-v010-mig-test-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&temp_dir).await.unwrap();
        let db_path = temp_dir.join("roche.db");

        // Connect manually and apply SQLx migrations ONLY up to version 9
        let opts = SqliteConnectOptions::new()
            .filename(&db_path)
            .create_if_missing(true)
            .foreign_keys(true);
        let pre_pool =
            SqlitePoolOptions::new().max_connections(1).connect_with(opts).await.unwrap();
        let mut migrator = sqlx::migrate!("./migrations");
        migrator.migrations.to_mut().retain(|m| m.version <= 9);
        migrator.run(&pre_pool).await.unwrap();

        let rss_feed_id = crate::db::sync::deterministic_feed_id("https://blog.example.com/rss");
        let pod_feed_id =
            crate::db::sync::deterministic_feed_id("https://pod.example.com/feed.xml");
        let art_item_id = crate::db::sync::deterministic_item_id(
            Some("https://blog.example.com/rss"),
            "https://blog.example.com/post-1",
        );
        let pod_item_id = crate::db::sync::deterministic_item_id(
            Some("https://pod.example.com/feed.xml"),
            "https://pod.example.com/ep-1",
        );
        let tag_id = crate::db::sync::deterministic_tag_id("systems");

        sqlx::query(
            "INSERT INTO feeds (id, url, title, feed_type, is_active)
             VALUES (?, 'https://blog.example.com/rss', 'Systems Blog', 'rss', 1),
                    (?, 'https://pod.example.com/feed.xml', 'Systems Pod', 'rss', 1)",
        )
        .bind(&rss_feed_id)
        .bind(&pod_feed_id)
        .execute(&pre_pool)
        .await
        .unwrap();

        sqlx::query(
            "INSERT INTO items
                (id, feed_id, title, url, author, summary, content_html, content_type,
                 status, is_favorite, read_progress, audio_url, duration)
             VALUES
                (?, ?, 'Zero-Copy Parsing', 'https://blog.example.com/post-1', 'Alice',
                 'Fast parser design', '<p>Zero-copy body</p>', 'article',
                 'unread', 1, 0.75, NULL, NULL),
                (?, ?, 'Episode 1: Consensus', 'https://pod.example.com/ep-1', 'Bob',
                 'Raft vs Paxos', '<p>Show notes</p>', 'audio',
                 'archived', 0, 1.0, 'https://cdn.example.com/ep-1.mp3', 1845)",
        )
        .bind(&art_item_id)
        .bind(&rss_feed_id)
        .bind(&pod_item_id)
        .bind(&pod_feed_id)
        .execute(&pre_pool)
        .await
        .unwrap();

        sqlx::query("INSERT INTO tags (id, name, color) VALUES (?, 'systems', '#336699')")
            .bind(&tag_id)
            .execute(&pre_pool)
            .await
            .unwrap();
        sqlx::query("INSERT INTO item_tags (item_id, tag_id) VALUES (?, ?)")
            .bind(&art_item_id)
            .bind(&tag_id)
            .execute(&pre_pool)
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO media (id, item_id, media_type, path, file_size, duration)
             VALUES ('media-1', ?, 'audio', 'audio/ep-1.mp3', 123456, 1845)",
        )
        .bind(&pod_item_id)
        .execute(&pre_pool)
        .await
        .unwrap();

        // Mark startup maintenance done so summary/thumbnail stays exact
        sqlx::query(
            "INSERT INTO maintenance_runs (task_name, completed_at) VALUES
                ('normalize_legacy_html_summaries', '2026-01-01T00:00:00Z'),
                ('backfill_missing_thumbnails', '2026-01-01T00:00:00Z')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        pre_pool.close().await;
        drop(pre_pool);

        // Now run full Db::connect which applies 010_unified_entries_model.sql!
        let db = Db::connect(&db_path).await.unwrap();

        let feeds = db.feeds().list().await.unwrap();
        assert_eq!(feeds.len(), 2);

        // Verify podcast feed upgraded its entry kind to `podcast` and source_type to `podcast`
        let pod_entry = db.catalog().get_entry(&pod_feed_id).await.unwrap().unwrap();
        assert_eq!(pod_entry.kind, EntryKind::Podcast);

        // Verify article item preserved favorite (Starred collection), progress, tag, and FTS
        let art = db.items().get(&art_item_id).await.unwrap().unwrap();
        assert!(art.is_favorite);
        assert_eq!(art.status, ItemStatus::Unread);
        assert!((art.read_progress - 0.75).abs() < 1e-6);

        let item_tags = db.tags().list_for_item(&art_item_id).await.unwrap();
        assert_eq!(item_tags.len(), 1);
        assert_eq!(item_tags[0].name, "systems");

        // Verify podcast episode preserved enclosure audio_url + duration + local cached media
        let ep = db.items().get(&pod_item_id).await.unwrap().unwrap();
        assert_eq!(ep.status, ItemStatus::Archived);
        assert_eq!(ep.audio_url.as_deref(), Some("https://cdn.example.com/ep-1.mp3"));
        assert_eq!(ep.duration, Some(1845));
        let media_rows = db.media().list_for_item(&pod_item_id).await.unwrap();
        assert_eq!(media_rows.len(), 1);
        assert_eq!(media_rows[0].path, "audio/ep-1.mp3");

        // Verify FTS search works on migrated content
        let search_res = db
            .items()
            .list_filtered_page(&ItemFilter {
                search: Some("zero-copy".to_owned()),
                ..ItemFilter::default()
            })
            .await
            .unwrap();
        assert_eq!(search_res.items.len(), 1);
        assert_eq!(search_res.items[0].id, art_item_id);

        db.pool().close().await;
        drop(db);
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
