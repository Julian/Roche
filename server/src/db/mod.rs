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
    BatchCollectionInput, BatchCollectionMemberInput, BatchComparisonInput, BatchEntryInput,
    BatchGeneratorInput, BatchLogInput, CatalogBatchInput, CatalogBatchResult, CatalogEntry,
    CatalogError, CatalogRepo, CatalogResult, ClusterSelectFilter, CollectionGeneratorState,
    CollectionSummary, ComparisonRecord, ConsumptionLogRow, ConsumptionLogView,
    DynamicCollectionRule, EntryDetailView, EntryIdentifier, PREFERRED_IDENTIFIER_SCHEMES,
    RankedCollectionView, RankedEntryRow, UpsertEntryInput, choose_deterministic_entry_id,
    external_identifier_label, external_identifier_url, matches_dynamic_collection_rule,
    normalize_identifier_scheme, normalize_identifier_value, parse_dynamic_collection_rule,
    strip_album_edition_suffix,
};
pub use feeds::{Feed, FeedRepo, PollBatch, PollItemCandidate};
#[cfg(test)]
pub use items::NewItem;
pub use items::{Item, ItemFilter, ItemPage, ItemRepo, ManualItemInput};
pub use media::{Media, MediaRepo, NewMediaSpec};
pub use sync::{
    ActivityEntry, DEFAULT_ONLINE_PEER_WEIGHT, NetworkTier, PeerSyncRequest, PeerSyncResponse,
    SERVER_PEER_ID, STARRED_COLLECTION_ID, SyncRepo, UP_NEXT_COLLECTION_ID,
    canonical_ordering_json, canonicalize_sync_url, collection_entry_natural_key,
    compute_throughput_weight, deterministic_comparison_id, deterministic_consumption_log_id,
    deterministic_system_collection_id, deterministic_title_entry_id, should_peer_poll_feed,
};
pub use tags::{ItemTag, Tag, TagRepo};
pub use types::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, CollectionMode, CollectionRole,
    ConsumptionStatus, ContentType, EntryKind, FeedType, HexColor, InboxState, ItemSortField,
    ItemStatus, ItemStatusFilter, MediaType, ReadAtPatch, SortOrder,
};

/// Domain conflict error mapped to `409 Conflict` by `ApiError`.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{0}")]
pub struct ConflictError(pub String);

#[derive(Debug, Clone)]
pub struct Db {
    pool: SqlitePool,
}

impl Db {
    /// Connects to the SQLite database at `database_path` and runs all embedded migrations.
    pub async fn connect(database_path: impl AsRef<Path>) -> Result<Self> {
        let path = database_path.as_ref();
        let file_path_for_perms = match path.to_str() {
            Some(":memory:") => None,
            Some(s) if s.starts_with("sqlite:") => None,
            _ => Some(path),
        };
        let base_options = match path.to_str() {
            Some(s) if s.starts_with("sqlite:") => SqliteConnectOptions::from_str(s)?,
            _ => {
                if let Some(file_path) = file_path_for_perms {
                    if let Some(parent) = file_path.parent()
                        && !parent.as_os_str().is_empty()
                    {
                        crate::media::MediaStore::ensure_private_dir(parent).await?;
                    }
                    crate::media::MediaStore::precreate_or_tighten_private_file(file_path).await?;
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

        prepare_pre_v010_schema_if_needed(&pool).await?;
        sqlx::migrate!("./migrations").run(&pool).await?;
        verify_foreign_key_integrity(&pool).await?;
        if let Some(file_path) = file_path_for_perms {
            tighten_sqlite_sidecar_permissions(file_path).await;
        }

        let db = Self { pool };
        db.run_startup_maintenance().await;
        verify_and_heal_fts_index(&db.pool).await?;

        Ok(db)
    }

    async fn run_startup_maintenance(&self) {
        const NORMALIZE_SUMMARIES_TASK: &str = "normalize_legacy_html_summaries";
        const BACKFILL_THUMBNAILS_TASK: &str = "backfill_missing_thumbnails";
        const CANONICALIZE_URL_IDENTIFIERS_TASK: &str = "canonicalize_entry_url_identifiers";

        self.run_maintenance_task_once(NORMALIZE_SUMMARIES_TASK, async {
            self.items().normalize_legacy_html_summaries().await.map(|_| ())
        })
        .await;
        self.run_maintenance_task_once(BACKFILL_THUMBNAILS_TASK, async {
            self.items().backfill_missing_thumbnails().await.map(|_| ())
        })
        .await;
        self.run_maintenance_task_once(CANONICALIZE_URL_IDENTIFIERS_TASK, async {
            self.canonicalize_entry_url_identifiers().await
        })
        .await;
    }

    async fn canonicalize_entry_url_identifiers(&self) -> Result<()> {
        let entry_urls = sqlx::query!(
            "SELECT id, url AS \"url!: String\" FROM entries
             WHERE url IS NOT NULL AND TRIM(url) != '' AND merged_into_id IS NULL",
        )
        .fetch_all(&self.pool)
        .await?;

        let feed_urls = sqlx::query!(
            "SELECT collection_id, feed_url FROM feed_sources WHERE TRIM(feed_url) != ''",
        )
        .fetch_all(&self.pool)
        .await?;

        let mut tx = self.pool.begin().await?;
        for row in entry_urls {
            let entry_id = row.id;
            let raw_url = row.url;
            let canon = sync::canonicalize_sync_url(&raw_url);
            if canon.is_empty() {
                continue;
            }
            let legacy_lower = raw_url.trim().to_lowercase();
            if legacy_lower != canon {
                sqlx::query!(
                    "DELETE FROM entry_identifiers
                     WHERE entry_id = ? AND scheme = 'url' AND value = ?",
                    entry_id,
                    legacy_lower,
                )
                .execute(&mut *tx)
                .await?;
            }
            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('url', ?, ?)",
                canon,
                entry_id,
            )
            .execute(&mut *tx)
            .await?;
        }

        for row in feed_urls {
            let collection_id = row.collection_id;
            let raw_feed_url = row.feed_url;
            let canon = sync::canonicalize_sync_url(&raw_feed_url);
            if canon.is_empty() {
                continue;
            }
            let legacy_lower = raw_feed_url.trim().to_lowercase();
            if legacy_lower != canon {
                sqlx::query!(
                    "DELETE FROM entry_identifiers
                     WHERE entry_id = ? AND scheme = 'feed_url' AND value = ?",
                    collection_id,
                    legacy_lower,
                )
                .execute(&mut *tx)
                .await?;
            }
            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('feed_url', ?, ?)",
                canon,
                collection_id,
            )
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(())
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
        let found = sqlx::query_scalar!(
            "SELECT 1 AS \"found!: i64\" FROM maintenance_runs WHERE task_name = ? LIMIT 1",
            task_name,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(found.is_some())
    }

    async fn record_maintenance_completed(&self, task_name: &str) -> Result<()> {
        let completed_at = SyncRepo::now_utc().to_rfc3339();
        sqlx::query!(
            "INSERT OR REPLACE INTO maintenance_runs (task_name, completed_at) VALUES (?, ?)",
            task_name,
            completed_at,
        )
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

/// Deduplicates case-variant legacy `tags` (e.g. `"Systems"` + `"systems"`) on pre-v010 databases
/// before `010_unified_entries_model.sql` migrates `tags` into `collection_specs(slug = 'tag:' || LOWER(TRIM(name)))`,
/// preserving the committed `010` migration checksum while preventing `UNIQUE` slug crashes.
pub(crate) async fn prepare_pre_v010_schema_if_needed(pool: &SqlitePool) -> Result<()> {
    let has_legacy_tags = sqlx::query_scalar!(
        "SELECT 1 AS \"found!: i64\" FROM sqlite_master WHERE type = 'table' AND name = 'tags' LIMIT 1",
    )
    .fetch_optional(pool)
    .await?;
    if has_legacy_tags.is_none() {
        return Ok(());
    }

    let raw_tags: Vec<(String, String)> =
        sqlx::query_as("SELECT id, name FROM tags ORDER BY created_at ASC, id ASC")
            .fetch_all(pool)
            .await?;
    if raw_tags.len() < 2 {
        return Ok(());
    }

    let mut groups: std::collections::BTreeMap<String, Vec<(String, String)>> =
        std::collections::BTreeMap::new();
    for (id, name) in raw_tags {
        let key = name.trim().to_lowercase();
        groups.entry(key).or_default().push((id, name));
    }

    let has_duplicates = groups.values().any(|g| g.len() > 1);
    if !has_duplicates {
        return Ok(());
    }

    let mut tx = pool.begin().await?;
    for (_, mut group) in groups {
        if group.len() < 2 {
            continue;
        }
        // Prefer deterministic tag ID if one of the rows already uses it; otherwise keep earliest.
        group.sort_by_key(|(id, name)| usize::from(id != &sync::deterministic_tag_id(name)));
        let survivor_id = &group[0].0;
        for (dup_id, _) in &group[1..] {
            sqlx::query(
                "INSERT OR IGNORE INTO item_tags (item_id, tag_id)
                 SELECT item_id, ? FROM item_tags WHERE tag_id = ?",
            )
            .bind(survivor_id)
            .bind(dup_id)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM item_tags WHERE tag_id = ?")
                .bind(dup_id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("DELETE FROM tags WHERE id = ?").bind(dup_id).execute(&mut *tx).await?;
        }
    }
    tx.commit().await?;
    Ok(())
}

/// Verifies `PRAGMA foreign_key_check` reports zero orphaned foreign-key references after migrations.
pub(crate) async fn verify_foreign_key_integrity(pool: &SqlitePool) -> Result<()> {
    let violations: Vec<(String, Option<i64>, String, i64)> =
        sqlx::query_as("PRAGMA foreign_key_check").fetch_all(pool).await?;
    if !violations.is_empty() {
        anyhow::bail!(
            "SQLite foreign_key_check failed with {} violation(s): {:?}",
            violations.len(),
            violations.first()
        );
    }
    Ok(())
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
        sqlx::query_scalar!(
            "SELECT (EXISTS (
                 SELECT 1 FROM entries e
                 LEFT JOIN entries_fts f ON f.rowid = e.rowid AND f.entry_id = e.id
                 WHERE e.merged_into_id IS NULL AND f.rowid IS NULL
             ) OR (
                 (SELECT COUNT(*) FROM entries_fts) !=
                 (SELECT COUNT(*) FROM entries WHERE merged_into_id IS NULL)
             )) AS \"mismatch!: bool\"",
        )
        .fetch_one(pool)
        .await?
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
    sqlx::query!("DELETE FROM entries_fts").execute(&mut *tx).await?;
    sqlx::query!(
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

pub(crate) async fn tighten_sqlite_sidecar_permissions(db_path: &Path) {
    crate::media::MediaStore::tighten_private_file_if_exists(db_path).await;
    for suffix in ["-wal", "-shm"] {
        let mut os = db_path.as_os_str().to_os_string();
        os.push(suffix);
        crate::media::MediaStore::tighten_private_file_if_exists(Path::new(&os)).await;
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

        // 1. Initial connect creates maintenance_runs records for all startup tasks
        let db1 = Db::connect(&db_path).await.unwrap();
        let recorded_count =
            sqlx::query_scalar!("SELECT COUNT(*) AS \"count!: i64\" FROM maintenance_runs")
                .fetch_one(db1.pool())
                .await
                .unwrap();
        assert_eq!(recorded_count, 3);

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
        sqlx::query!("DELETE FROM maintenance_runs").execute(db1.pool()).await.unwrap();
        db1.pool().close().await;
        drop(db1);

        // 2. Reconnecting runs all tasks once and records completion timestamps
        let db2 = Db::connect(&db_path).await.unwrap();
        let normalized_item = db2.items().get(&item1.id).await.unwrap().unwrap();
        assert_eq!(normalized_item.summary.as_deref(), Some("Legacy & HTML summary"));
        assert_eq!(normalized_item.thumbnail_url.as_deref(), Some("https://example.com/hero.jpg"));

        let first_runs: Vec<(String, String)> = sqlx::query!(
            "SELECT task_name, completed_at FROM maintenance_runs ORDER BY task_name ASC",
        )
        .fetch_all(db2.pool())
        .await
        .unwrap()
        .into_iter()
        .map(|r| (r.task_name, r.completed_at))
        .collect();
        assert_eq!(first_runs.len(), 3);

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
        sqlx::query!(
            "UPDATE entry_content SET content_html = '<img src=\"https://example.com/skip.jpg\"/>' WHERE entry_id = ?",
            item2.id,
        )
        .execute(db2.pool())
        .await
        .unwrap();
        sqlx::query!("UPDATE entries SET thumbnail_url = NULL WHERE id = ?", item2.id)
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

        let second_runs: Vec<(String, String)> = sqlx::query!(
            "SELECT task_name, completed_at FROM maintenance_runs ORDER BY task_name ASC",
        )
        .fetch_all(db3.pool())
        .await
        .unwrap()
        .into_iter()
        .map(|r| (r.task_name, r.completed_at))
        .collect();
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
        let fts_rowid_2 = sqlx::query_scalar!(
            "SELECT rowid AS \"rowid!: i64\" FROM entries_fts WHERE entry_id = ?",
            item2.id,
        )
        .fetch_one(db.pool())
        .await
        .unwrap();
        let entry_rowid_2 = sqlx::query_scalar!(
            "SELECT rowid AS \"rowid!: i64\" FROM entries WHERE id = ?",
            item2.id,
        )
        .fetch_one(db.pool())
        .await
        .unwrap();
        if entry_rowid_2 == fts_rowid_2 {
            sqlx::query!(
                "UPDATE entries SET rowid = rowid - 1 WHERE id IN (?, ?)",
                item2.id,
                item3.id,
            )
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
            crate::db::sync::deterministic_feed_id("https://pod.example.com/FeedCase.xml");
        let art_item_id = crate::db::sync::deterministic_item_id(
            Some("https://blog.example.com/rss"),
            "https://YouTube.com/watch?v=dQw4w9WgXcQ",
        );
        let pod_item_id = crate::db::sync::deterministic_item_id(
            Some("https://pod.example.com/FeedCase.xml"),
            "https://pod.example.com/ep-1",
        );
        let tag_id = crate::db::sync::deterministic_tag_id("systems");
        let dup_case_tag_id = "legacy-dup-tag-systems-upper";

        sqlx::query(
            "INSERT INTO feeds (id, url, title, feed_type, is_active)
             VALUES (?, 'https://blog.example.com/rss', 'Systems Blog', 'rss', 1),
                    (?, 'https://pod.example.com/FeedCase.xml', 'Systems Pod', 'rss', 1)",
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
                (?, ?, 'Zero-Copy Parsing', 'https://YouTube.com/watch?v=dQw4w9WgXcQ', 'Alice',
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

        sqlx::query(
            "INSERT INTO tags (id, name, color) VALUES
                (?, 'systems', '#336699'),
                (?, 'Systems', '#663399')",
        )
        .bind(&tag_id)
        .bind(dup_case_tag_id)
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query("INSERT INTO item_tags (item_id, tag_id) VALUES (?, ?), (?, ?)")
            .bind(&art_item_id)
            .bind(&tag_id)
            .bind(&pod_item_id)
            .bind(dup_case_tag_id)
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

        // Mark legacy HTML/thumbnail maintenance done while allowing URL canonicalization to run
        sqlx::query!(
            "INSERT INTO maintenance_runs (task_name, completed_at) VALUES
                ('normalize_legacy_html_summaries', '2026-01-01T00:00:00Z'),
                ('backfill_missing_thumbnails', '2026-01-01T00:00:00Z')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        pre_pool.close().await;
        drop(pre_pool);

        // Now run full Db::connect which deduplicates case-variant tags, applies 010 + 011,
        // and canonicalizes entry_identifiers URLs without lowercasing case-sensitive paths/queries!
        let db = Db::connect(&db_path).await.unwrap();

        let feeds = db.feeds().list().await.unwrap();
        assert_eq!(feeds.len(), 2);

        // Verify podcast feed upgraded its entry kind to `podcast` and preserved feed_url case in identifiers
        let pod_entry = db.catalog().get_entry(&pod_feed_id).await.unwrap().unwrap();
        assert_eq!(pod_entry.kind, EntryKind::Podcast);
        assert!(pod_entry.identifiers.iter().any(|id| {
            id.scheme == "feed_url" && id.value == "https://pod.example.com/FeedCase.xml"
        }));

        // Verify article item preserved favorite (Starred collection), progress, tag, FTS, and case-sensitive URL identifier
        let art = db.items().get(&art_item_id).await.unwrap().unwrap();
        assert!(art.is_favorite);
        assert_eq!(art.status, ItemStatus::Unread);
        assert!((art.read_progress - 0.75).abs() < 1e-6);
        let art_entry = db.catalog().get_entry(&art_item_id).await.unwrap().unwrap();
        assert!(art_entry.identifiers.iter().any(|id| {
            id.scheme == "url" && id.value == "https://youtube.com/watch?v=dQw4w9WgXcQ"
        }));
        assert!(!art_entry.identifiers.iter().any(|id| {
            id.scheme == "url" && id.value == "https://youtube.com/watch?v=dqw4w9wgxcq"
        }));

        let item_tags = db.tags().list_for_item(&art_item_id).await.unwrap();
        assert_eq!(item_tags.len(), 1);
        assert_eq!(item_tags[0].name, "systems");

        // Verify the item tagged with case-variant "Systems" was remapped to surviving "systems" tag
        let pod_tags = db.tags().list_for_item(&pod_item_id).await.unwrap();
        assert_eq!(pod_tags.len(), 1);
        assert_eq!(pod_tags[0].id, tag_id);

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

    #[cfg(unix)]
    #[tokio::test]
    async fn test_db_connect_enforces_private_unix_permissions() {
        use std::os::unix::fs::PermissionsExt;

        let temp_dir =
            std::env::temp_dir().join(format!("roche-db-perms-test-{}", uuid::Uuid::new_v4()));
        let data_dir = temp_dir.join("data");
        tokio::fs::create_dir_all(&data_dir).await.unwrap();
        tokio::fs::set_permissions(&data_dir, std::fs::Permissions::from_mode(0o755))
            .await
            .unwrap();
        let db_path = data_dir.join("roche.db");
        tokio::fs::write(&db_path, b"").await.unwrap();
        tokio::fs::set_permissions(&db_path, std::fs::Permissions::from_mode(0o644)).await.unwrap();

        let db = Db::connect(&db_path).await.unwrap();
        let dir_mode = tokio::fs::metadata(&data_dir).await.unwrap().permissions().mode() & 0o777;
        let db_mode = tokio::fs::metadata(&db_path).await.unwrap().permissions().mode() & 0o777;
        assert_eq!(dir_mode, 0o700);
        assert_eq!(db_mode, 0o600);
        let wal_path = data_dir.join("roche.db-wal");
        if tokio::fs::try_exists(&wal_path).await.unwrap_or(false) {
            let wal_mode =
                tokio::fs::metadata(&wal_path).await.unwrap().permissions().mode() & 0o777;
            assert_eq!(wal_mode, 0o600);
        }

        db.pool().close().await;
        drop(db);
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_v012_strict_tables_enforced_and_migration_preserves_fks_and_fts() {
        let temp_dir =
            std::env::temp_dir().join(format!("roche-v012-strict-test-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&temp_dir).await.unwrap();
        let db_path = temp_dir.join("roche.db");

        // 1. Connect manually and apply migrations up to 011, then populate rows with rowid gaps,
        //    foreign keys, FTS content, and pruned high-watermark activity_log sqlite_sequence.
        let pre_opts = SqliteConnectOptions::new()
            .filename(&db_path)
            .create_if_missing(true)
            .foreign_keys(true);
        let pre_pool =
            SqlitePoolOptions::new().max_connections(1).connect_with(pre_opts).await.unwrap();
        let mut migrator_v11 = sqlx::migrate!("./migrations");
        migrator_v11.migrations.to_mut().retain(|m| m.version <= 11);
        migrator_v11.run(&pre_pool).await.unwrap();

        sqlx::query!(
            "INSERT INTO entries (id, kind, title, summary)
             VALUES ('ephemeral-gap-row', 'article', 'Gap Row', 'Deleted before 012')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query!(
            "INSERT INTO entries (id, kind, title, url, release_year, summary)
             VALUES ('strict-entry-1', 'article', 'Strict Migration Essay',
                     'https://example.com/strict-1', 2026, 'Verifying rowid FTS alignment')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query!("DELETE FROM entries WHERE id = 'ephemeral-gap-row'")
            .execute(&pre_pool)
            .await
            .unwrap();

        sqlx::query!(
            "INSERT INTO entry_content (entry_id, content_html, content_type)
             VALUES ('strict-entry-1', '<p>Uniquestrictftstoken body paragraph</p>', 'article')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query!(
            "INSERT INTO entry_state (entry_id, inbox_state, progress)
             VALUES ('strict-entry-1', 'unread', 0.4)",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query!(
            "INSERT INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('url', 'https://example.com/strict-1', 'strict-entry-1')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query!(
            "INSERT INTO consumption_log (id, entry_id, ts, status)
             VALUES ('log-strict-1', 'strict-entry-1', '2026-06-01T12:00:00+00:00', 'started')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();

        // Advance activity_log sqlite_sequence to 77 and delete the high row so MAX(id) < seq.
        sqlx::query!(
            "INSERT INTO activity_log (id, entity_type, entity_id, action)
             VALUES (1, 'item', 'strict-entry-1', 'create'),
                    (77, 'item', 'ephemeral-gap-row', 'delete')",
        )
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query!("DELETE FROM activity_log WHERE id = 77").execute(&pre_pool).await.unwrap();

        // Apply 012_strict_tables.sql directly before Db::connect so we verify 012 itself
        // preserved FTS rowid alignment without needing verify_and_heal_fts_index to rebuild.
        sqlx::migrate!("./migrations").run(&pre_pool).await.unwrap();

        assert!(
            !verify_and_heal_fts_index(&pre_pool).await.unwrap(),
            "012_strict_tables.sql must preserve entries.rowid <-> entries_fts alignment without rebuild"
        );

        let seq_after_v12 = sqlx::query_scalar!(
            "SELECT CAST(seq AS INTEGER) AS \"seq!: i64\"
             FROM sqlite_sequence
             WHERE name = 'activity_log'",
        )
        .fetch_one(&pre_pool)
        .await
        .unwrap();
        assert_eq!(
            seq_after_v12, 77,
            "012_strict_tables.sql must preserve sqlite_sequence high-water mark for activity_log"
        );

        pre_pool.close().await;
        drop(pre_pool);

        // 2. Connect via Db::connect and verify (a) all 17 regular tables end with STRICT,
        //    (b) wrong storage class is rejected, and (c) FK check + FTS + sequence work end-to-end.
        let db = Db::connect(&db_path).await.unwrap();

        let regular_tables = sqlx::query!(
            "SELECT name AS \"name!: String\", sql AS \"sql!: String\"
             FROM sqlite_master
             WHERE type = 'table'
               AND name NOT IN ('sqlite_sequence', '_sqlx_migrations')
               AND name NOT LIKE 'entries_fts%'
             ORDER BY name ASC",
        )
        .fetch_all(db.pool())
        .await
        .unwrap();
        assert_eq!(regular_tables.len(), 17, "Expected all 17 regular tables in schema");
        for tbl in &regular_tables {
            let trimmed_ddl = tbl.sql.trim().trim_end_matches(';').trim_end();
            assert!(
                trimmed_ddl.ends_with("STRICT"),
                "Table '{}' DDL must end with STRICT, got: {}",
                tbl.name,
                tbl.sql
            );
        }

        let bad_storage_class = sqlx::query(
            "INSERT INTO entries (id, kind, title, release_year)
             VALUES ('invalid-strict-entry', 'movie', 'Wrong Storage Class', 'not-an-int')",
        )
        .execute(db.pool())
        .await;
        assert!(
            bad_storage_class.is_err(),
            "SQLite STRICT must reject inserting TEXT 'not-an-int' into INTEGER column entries.release_year"
        );

        let fk_violations =
            sqlx::query("PRAGMA foreign_key_check").fetch_all(db.pool()).await.unwrap();
        assert!(
            fk_violations.is_empty(),
            "PRAGMA foreign_key_check must return 0 rows after 012 migration"
        );

        let fts_hits = db
            .items()
            .list_filtered_page(&ItemFilter {
                search: Some("uniquestrictftstoken".to_owned()),
                ..ItemFilter::default()
            })
            .await
            .unwrap();
        assert_eq!(fts_hits.items.len(), 1);
        assert_eq!(fts_hits.items[0].id, "strict-entry-1");

        sqlx::query!(
            "INSERT INTO activity_log (entity_type, entity_id, action)
             VALUES ('item', 'strict-entry-1', 'update')",
        )
        .execute(db.pool())
        .await
        .unwrap();
        let next_activity_id = db.sync().latest_activity_id().await.unwrap();
        assert_eq!(next_activity_id, 78);

        db.pool().close().await;
        drop(db);
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
