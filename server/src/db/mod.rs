use std::{path::Path, str::FromStr, time::Duration};

use anyhow::Result;
use sqlx::sqlite::{
    SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions, SqliteSynchronous,
};

mod auth;
mod catalog;
pub(crate) mod entries_core;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;
pub(crate) mod tx;
mod types;

pub use auth::{AuthRepo, DeviceToken};
pub use catalog::{
    BatchCollectionInput, BatchCollectionMemberInput, BatchComparisonInput, BatchEntryInput,
    BatchGeneratorInput, BatchLogInput, CatalogBatchInput, CatalogBatchResult, CatalogEntry,
    CatalogError, CatalogExportSnapshot, CatalogRepo, CatalogResult, CollectionGeneratorState,
    CollectionRankingInputs, CollectionSummary, ComparisonRecord, ConsumptionLogRow,
    ConsumptionLogView, DynamicCollectionRule, EntryDetailView, EntryIdentifier,
    ExportCatalogEntry, ExportCollectionEntry, ExportCollectionGenerator, ExportComparison,
    ExportConsumptionLog, PREFERRED_IDENTIFIER_SCHEMES, UpsertEntryInput, WantSearchMatch,
    choose_deterministic_entry_id, derive_manual_collection_slug_and_id, external_identifier_label,
    external_identifier_url, external_identifier_url_for_kind, matches_dynamic_collection_rule,
    normalize_identifier_scheme, normalize_identifier_value, parse_dynamic_collection_rule,
    strip_album_edition_suffix,
};
pub use feeds::{Feed, FeedRepo, PollBatch, PollItemCandidate};
#[cfg(test)]
pub use items::NewItem;
pub use items::{Item, ItemFilter, ItemPage, ItemRepo, ManualItemInput};
pub use media::{Media, MediaRepo, NewMediaSpec};
pub use sync::{
    ABSOLUTE_MAX_BACKOFF_SECS, ActivityEntry, DEFAULT_MAX_BACKOFF_SECS, DEFAULT_ONLINE_PEER_WEIGHT,
    HlcClock, HlcTimestamp, MAX_CONSECUTIVE_FAILURES, MAX_PEER_WEIGHT, MIN_PEER_WEIGHT,
    NetworkTier, PeerSyncRequest, PeerSyncResponse, PreparedSyncEntry, SERVER_PEER_ID,
    STARRED_COLLECTION_ID, SyncCollectionEntryRecord, SyncCollectionGeneratorRecord,
    SyncCollectionSpec, SyncComparisonRecord, SyncConsumptionLogRecord, SyncEntryContent,
    SyncEntryRecord, SyncEntryState, SyncFeedSource, SyncRepo, SyncTombstone,
    THROUGHPUT_EWMA_ALPHA, UP_NEXT_COLLECTION_ID, WANT_TO_READ_COLLECTION_ID, WANT_TO_READ_SLUG,
    WANT_TO_READ_TITLE, canonical_ordering_json, canonicalize_sync_url,
    collection_entry_natural_key, compute_throughput_weight, deterministic_comparison_id,
    deterministic_consumption_log_id, deterministic_feed_id, deterministic_system_collection_id,
    deterministic_title_entry_id, feed_phase_offset_secs, is_protected_system_collection_id,
    next_phase_slot_at_or_after, next_scheduled_poll_epoch_secs, should_peer_poll_feed,
    target_poll_interval_secs, update_throughput_ewma,
};
pub use tags::{ItemTag, Tag, TagRepo};
pub use types::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, CollectionMode, CollectionRole,
    ConsumptionStatus, ContentType, EntryKind, EntryTriageState, FeedType, HexColor, InboxState,
    ItemSortField, ItemStatus, ItemStatusFilter, MediaType, ReadAtPatch, SortOrder,
};

/// Domain conflict error mapped to `409 Conflict` by `ApiError`.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{0}")]
pub struct ConflictError(pub String);

/// Escapes SQLite `LIKE` wildcard characters (`\`, `%`, `_`) for use with `ESCAPE '\'`.
pub(crate) fn escape_like_literal(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        if matches!(ch, '\\' | '%' | '_') {
            out.push('\\');
        }
        out.push(ch);
    }
    out
}

/// Builds a SQLite `LIKE` pattern (for use with `ESCAPE '\'`) matching a JSON-encoded string literal.
pub(crate) fn json_string_like_pattern(value: &str) -> String {
    let json_quoted = serde_json::to_string(value).unwrap_or_else(|_| format!("\"{value}\""));
    format!("%{}%", escape_like_literal(&json_quoted))
}

/// Trims, removes empty names, and JSON-encodes a slice of creator names for `entries.creators_json`.
/// Returns `None` if no non-empty creator names remain.
pub(crate) fn serialize_creators_json(creators: &[String]) -> Option<String> {
    let cleaned: Vec<&str> =
        creators.iter().map(String::as_str).map(str::trim).filter(|s| !s.is_empty()).collect();
    if cleaned.is_empty() {
        return None;
    }
    serde_json::to_string(&cleaned).ok()
}

pub(crate) use tx::{ReadTx, WriteTx};

/// Begins a SQLite write transaction using `BEGIN IMMEDIATE` wrapped in [`WriteTx`] so WAL-mode
/// writers acquire the RESERVED lock up-front and share a single causal [`HlcClock`].
pub(crate) async fn begin_write(
    pool: &SqlitePool,
) -> std::result::Result<WriteTx<'_>, sqlx::Error> {
    WriteTx::begin(pool).await
}

/// Begins a SQLite read-snapshot transaction (`BEGIN DEFERRED`) wrapped in [`ReadTx`] so multiple
/// SELECT queries observe a single consistent WAL point-in-time snapshot.
pub(crate) async fn begin_read(pool: &SqlitePool) -> std::result::Result<ReadTx<'_>, sqlx::Error> {
    ReadTx::begin(pool).await
}

#[derive(Debug, Clone)]
pub struct Db {
    pool: SqlitePool,
}

impl Db {
    #[cfg(test)]
    pub(crate) async fn begin_write(&self) -> std::result::Result<WriteTx<'_>, sqlx::Error> {
        begin_write(&self.pool).await
    }

    pub(crate) async fn begin_read(&self) -> std::result::Result<ReadTx<'_>, sqlx::Error> {
        begin_read(&self.pool).await
    }

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
                        crate::util::fs_perms::ensure_private_dir(parent).await?;
                    }
                    crate::util::fs_perms::precreate_or_tighten_private_file(file_path).await?;
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
        const CANONICALIZE_URL_IDENTIFIERS_TASK: &str = "canonicalize_entry_url_identifiers";
        const REPAIR_IMPLICIT_PODCASTS_TASK: &str = "repair_implicit_podcast_misclassifications_v1";
        const DECOUPLE_RSS_AUDIO_ENTRIES_TASK: &str = "decouple_rss_audio_entries_v1";

        self.run_maintenance_task_once(CANONICALIZE_URL_IDENTIFIERS_TASK, async {
            self.canonicalize_entry_url_identifiers().await
        })
        .await;
        self.run_maintenance_task_once(REPAIR_IMPLICIT_PODCASTS_TASK, async {
            self.repair_implicit_podcast_misclassifications().await
        })
        .await;
        self.run_maintenance_task_once(DECOUPLE_RSS_AUDIO_ENTRIES_TASK, async {
            self.decouple_rss_audio_entries().await
        })
        .await;
    }

    async fn decouple_rss_audio_entries(&self) -> Result<()> {
        let mut tx = begin_write(&self.pool).await?;
        let now = crate::util::clock::now_utc();
        let rows: Vec<(String, EntryKind, ContentType, Option<String>)> = sqlx::query_as(
            "SELECT e.id, e.kind, ec.content_type, e.url
             FROM entries e
             JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (e.kind = 'podcast_episode' OR ec.content_type = 'audio')
               AND EXISTS (
                   SELECT 1 FROM collection_entries ce_f
                   JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
                   WHERE ce_f.entry_id = e.id
               )
               AND NOT EXISTS (
                   SELECT 1 FROM collection_entries ce_p
                   JOIN feed_sources fs_p ON fs_p.collection_id = ce_p.collection_id
                   WHERE ce_p.entry_id = e.id AND fs_p.source_type = 'podcast'
               )",
        )
        .fetch_all(tx.conn())
        .await?;

        for (entry_id, existing_kind, existing_ct, entry_url) in rows {
            let target_ct = entry_url
                .as_deref()
                .and_then(|raw| url::Url::parse(raw.trim()).ok())
                .map_or(ContentType::Article, |parsed| roche_core::classify::classify_url(&parsed));
            let target_kind = EntryKind::for_feed_item(Some(FeedType::Rss), target_ct);
            if existing_ct != target_ct {
                sqlx::query("UPDATE entry_content SET content_type = ? WHERE entry_id = ?")
                    .bind(target_ct)
                    .bind(&entry_id)
                    .execute(tx.conn())
                    .await?;
            }
            if existing_kind != target_kind || existing_ct != target_ct {
                let entry_hlc = tx.next_hlc().await?;
                sqlx::query("UPDATE entries SET kind = ?, updated_at = ?, hlc = ? WHERE id = ?")
                    .bind(target_kind)
                    .bind(now)
                    .bind(&entry_hlc)
                    .bind(&entry_id)
                    .execute(tx.conn())
                    .await?;
            }
        }
        tx.commit().await?;
        Ok(())
    }

    async fn repair_implicit_podcast_misclassifications(&self) -> Result<()> {
        let mut tx = begin_write(&self.pool).await?;
        let now = crate::util::clock::now_utc();
        let deleted_media_entry_ids =
            Self::delete_bogus_webpage_audio_enclosures_in_tx(&mut tx).await?;
        let reverted_entry_ids = Self::revert_orphan_audio_entries_in_tx(&mut tx, now).await?;
        for entry_id in deleted_media_entry_ids.difference(&reverted_entry_ids) {
            let entry_hlc = tx.next_hlc().await?;
            sqlx::query("UPDATE entries SET updated_at = ?, hlc = ? WHERE id = ?")
                .bind(now)
                .bind(&entry_hlc)
                .bind(entry_id)
                .execute(tx.conn())
                .await?;
        }
        Self::revert_majority_non_audio_podcast_feeds_in_tx(&mut tx, now).await?;
        tx.commit().await?;
        Ok(())
    }

    async fn delete_bogus_webpage_audio_enclosures_in_tx(
        tx: &mut WriteTx<'_>,
    ) -> Result<std::collections::HashSet<String>> {
        let audio_media_rows: Vec<(String, String, String, String)> = sqlx::query_as(
            "SELECT em.id, em.entry_id, em.remote_url, e.url
             FROM entry_media em
             JOIN entries e ON e.id = em.entry_id
             WHERE em.media_type = 'audio'
               AND em.local_path IS NULL
               AND (em.duration IS NULL OR em.duration <= 0)
               AND em.remote_url IS NOT NULL
               AND e.url IS NOT NULL",
        )
        .fetch_all(tx.conn())
        .await?;

        let mut affected_entries = std::collections::HashSet::new();
        for (media_id, entry_id, remote_url, entry_url) in audio_media_rows {
            let same_url =
                sync::canonicalize_sync_url(&remote_url) == sync::canonicalize_sync_url(&entry_url);
            let is_direct_audio = url::Url::parse(remote_url.trim())
                .is_ok_and(|parsed| crate::util::media_urls::is_direct_audio_url(&parsed));
            if same_url && !is_direct_audio {
                sqlx::query("DELETE FROM entry_media WHERE id = ?")
                    .bind(&media_id)
                    .execute(tx.conn())
                    .await?;
                affected_entries.insert(entry_id);
            }
        }
        Ok(affected_entries)
    }

    async fn revert_orphan_audio_entries_in_tx(
        tx: &mut WriteTx<'_>,
        now: chrono::DateTime<chrono::Utc>,
    ) -> Result<std::collections::HashSet<String>> {
        let orphan_audio_entries: Vec<(String, EntryKind, Option<String>)> = sqlx::query_as(
            "SELECT e.id, e.kind, e.url
             FROM entries e
             JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.merged_into_id IS NULL
               AND ec.content_type = 'audio'
               AND NOT EXISTS (
                   SELECT 1 FROM entry_media em
                   WHERE em.entry_id = e.id
                     AND em.media_type = 'audio'
                     AND (em.remote_url IS NOT NULL OR em.local_path IS NOT NULL)
               )",
        )
        .fetch_all(tx.conn())
        .await?;

        let mut reverted_entries = std::collections::HashSet::new();
        for (entry_id, existing_kind, entry_url) in orphan_audio_entries {
            let reclassified_ct = entry_url
                .as_deref()
                .and_then(|raw| url::Url::parse(raw.trim()).ok())
                .map_or(ContentType::Article, |parsed| roche_core::classify::classify_url(&parsed));
            if reclassified_ct == ContentType::Audio {
                continue;
            }
            sqlx::query("UPDATE entry_content SET content_type = ? WHERE entry_id = ?")
                .bind(reclassified_ct)
                .bind(&entry_id)
                .execute(tx.conn())
                .await?;
            sqlx::query(
                "DELETE FROM entry_media
                 WHERE entry_id = ?
                   AND media_type = 'audio'
                   AND remote_url IS NULL
                   AND local_path IS NULL",
            )
            .bind(&entry_id)
            .execute(tx.conn())
            .await?;
            let target_kind = if existing_kind == EntryKind::PodcastEpisode {
                EntryKind::from_content_type(reclassified_ct)
            } else {
                existing_kind
            };
            let entry_hlc = tx.next_hlc().await?;
            sqlx::query("UPDATE entries SET kind = ?, updated_at = ?, hlc = ? WHERE id = ?")
                .bind(target_kind)
                .bind(now)
                .bind(&entry_hlc)
                .bind(&entry_id)
                .execute(tx.conn())
                .await?;
            reverted_entries.insert(entry_id);
        }
        Ok(reverted_entries)
    }

    async fn revert_majority_non_audio_podcast_feeds_in_tx(
        tx: &mut WriteTx<'_>,
        now: chrono::DateTime<chrono::Utc>,
    ) -> Result<()> {
        let podcast_feeds: Vec<(String, FeedType, EntryKind)> = sqlx::query_as(
            "SELECT fs.collection_id, fs.source_type, e.kind
             FROM feed_sources fs
             JOIN entries e ON e.id = fs.collection_id
             WHERE e.merged_into_id IS NULL
               AND (fs.source_type = 'podcast' OR e.kind = 'podcast')",
        )
        .fetch_all(tx.conn())
        .await?;

        for (feed_id, source_type, entry_kind) in podcast_feeds {
            let majority_non_audio = if source_type == FeedType::Podcast {
                let (total_items, audio_items): (i64, i64) = sqlx::query_as(
                    "SELECT COUNT(*),
                            COALESCE(SUM(CASE
                                WHEN ec.content_type = 'audio'
                                  OR EXISTS (
                                      SELECT 1 FROM entry_media em
                                      WHERE em.entry_id = ie.id
                                        AND em.media_type = 'audio'
                                        AND (em.remote_url IS NOT NULL OR em.local_path IS NOT NULL)
                                  )
                                THEN 1 ELSE 0 END), 0)
                     FROM collection_entries ce
                     JOIN entries ie ON ie.id = ce.entry_id
                     JOIN entry_content ec ON ec.entry_id = ie.id
                     WHERE ce.collection_id = ?
                       AND ie.merged_into_id IS NULL",
                )
                .bind(&feed_id)
                .fetch_one(tx.conn())
                .await?;
                total_items > 0 && audio_items * 2 < total_items
            } else {
                false
            };

            let target_source_type = if majority_non_audio { FeedType::Rss } else { source_type };
            let expected_kind = feeds::entry_kind_for_feed_type(target_source_type);
            if target_source_type != source_type {
                let fs_hlc = tx.next_hlc().await?;
                sqlx::query(
                    "UPDATE feed_sources SET source_type = ?, hlc = ? WHERE collection_id = ?",
                )
                .bind(target_source_type)
                .bind(&fs_hlc)
                .bind(&feed_id)
                .execute(tx.conn())
                .await?;
            }
            if entry_kind != expected_kind {
                let entry_hlc = tx.next_hlc().await?;
                sqlx::query("UPDATE entries SET kind = ?, updated_at = ?, hlc = ? WHERE id = ?")
                    .bind(expected_kind)
                    .bind(now)
                    .bind(&entry_hlc)
                    .bind(&feed_id)
                    .execute(tx.conn())
                    .await?;
            }
        }
        Ok(())
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

        let mut tx = begin_write(&self.pool).await?;
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
                .execute(tx.conn())
                .await?;
            }
            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('url', ?, ?)",
                canon,
                entry_id,
            )
            .execute(tx.conn())
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
                .execute(tx.conn())
                .await?;
            }
            sqlx::query!(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('feed_url', ?, ?)",
                canon,
                collection_id,
            )
            .execute(tx.conn())
            .await?;
        }

        tx.commit().await?;
        Ok(())
    }

    pub(crate) async fn run_maintenance_task_once<Fut>(&self, task_name: &str, task: Fut)
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
        let completed_at = crate::util::clock::now_utc().to_rfc3339();
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

    let mut tx = begin_write(pool).await?;
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
            .execute(tx.conn())
            .await?;
            sqlx::query("DELETE FROM item_tags WHERE tag_id = ?")
                .bind(dup_id)
                .execute(tx.conn())
                .await?;
            sqlx::query("DELETE FROM tags WHERE id = ?").bind(dup_id).execute(tx.conn()).await?;
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
    let mut tx = begin_write(pool).await?;
    sqlx::query!("DELETE FROM entries_fts").execute(tx.conn()).await?;
    sqlx::query!(
        "INSERT INTO entries_fts (rowid, entry_id, title, creators, summary, content_html)
         SELECT e.rowid, e.id, e.title, e.creators_json, COALESCE(e.summary, ''), COALESCE(ec.content_html, '')
         FROM entries e
         LEFT JOIN entry_content ec ON ec.entry_id = e.id
         WHERE e.merged_into_id IS NULL",
    )
    .execute(tx.conn())
    .await?;
    tx.commit().await?;
    Ok(true)
}

pub(crate) async fn tighten_sqlite_sidecar_permissions(db_path: &Path) {
    crate::util::fs_perms::tighten_private_file_if_exists(db_path).await;
    for suffix in ["-wal", "-shm"] {
        let mut os = db_path.as_os_str().to_os_string();
        os.push(suffix);
        crate::util::fs_perms::tighten_private_file_if_exists(Path::new(&os)).await;
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

        // 1. Initial connect creates maintenance_runs records for DB startup tasks
        let db1 = Db::connect(&db_path).await.unwrap();
        let recorded_count =
            sqlx::query_scalar!("SELECT COUNT(*) AS \"count!: i64\" FROM maintenance_runs")
                .fetch_one(db1.pool())
                .await
                .unwrap();
        assert_eq!(recorded_count, 3);

        // Simulate a legacy database needing one-shot task execution
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

        // 2. Reconnecting runs DB maintenance once and custom one-shot maintenance tasks record completion
        let db2 = Db::connect(&db_path).await.unwrap();
        db2.run_maintenance_task_once("normalize_legacy_html_summaries", async {
            db2.items()
                .normalize_legacy_html_summaries(|_, _| {
                    (
                        "Legacy & HTML summary".to_owned(),
                        Some("https://example.com/hero.jpg".to_owned()),
                    )
                })
                .await
                .map(|_| ())
        })
        .await;
        let normalized_item = db2.items().get(&item1.id).await.unwrap().unwrap();
        assert_eq!(normalized_item.summary.as_deref(), Some("Legacy & HTML summary"));
        assert_eq!(normalized_item.thumbnail_url.as_deref(), Some("https://example.com/hero.jpg"));

        let first_runs = list_maintenance_runs(&db2).await;
        assert_eq!(first_runs.len(), 4);

        // Inject raw unnormalized state while maintenance_runs already marks tasks completed
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
        db2.pool().close().await;
        drop(db2);

        // 3. Subsequent Db::connect + run_maintenance_task_once skips completed tasks and preserves completed_at
        let db3 = Db::connect(&db_path).await.unwrap();
        db3.run_maintenance_task_once("normalize_legacy_html_summaries", async {
            db3.items()
                .normalize_legacy_html_summaries(|_, _| ("OVERWRITTEN".to_owned(), None))
                .await
                .map(|_| ())
        })
        .await;
        let untouched = db3.items().get(&item2.id).await.unwrap().unwrap();
        assert_eq!(
            untouched.summary.as_deref(),
            Some("<p>Should NOT be rescanned on reconnect</p>")
        );
        assert!(untouched.thumbnail_url.is_none());
        assert_eq!(list_maintenance_runs(&db3).await, first_runs);

        db3.pool().close().await;
        drop(db3);
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    async fn list_maintenance_runs(db: &Db) -> Vec<(String, String)> {
        sqlx::query!("SELECT task_name, completed_at FROM maintenance_runs ORDER BY task_name ASC")
            .fetch_all(db.pool())
            .await
            .unwrap()
            .into_iter()
            .map(|r| (r.task_name, r.completed_at))
            .collect()
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

        // 2. Connect via Db::connect and verify (a) all 19 regular tables end with STRICT,
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
        assert_eq!(regular_tables.len(), 19, "Expected all 19 regular tables in schema");
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

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_repair_implicit_podcast_misclassifications_cleans_bogus_audio_and_majority_non_audio_feeds()
     {
        let db = Db::connect("sqlite::memory:").await.unwrap();

        // 1. Substack-style blog accidentally marked podcast: 1 real .mp3, 1 bogus webpage audio URL, 1 orphan audio item
        let blog_url = Url::parse("https://blog.example.com/feed").unwrap();
        let blog = db
            .feeds()
            .create(FeedType::Podcast, &blog_url, Some("Substack Blog"), None)
            .await
            .unwrap();

        let real_ep_url = Url::parse("https://blog.example.com/p/interview").unwrap();
        let bogus_post_url = Url::parse("https://blog.example.com/p/text-post-1").unwrap();
        let orphan_post_url = Url::parse("https://blog.example.com/p/text-post-2").unwrap();
        let batch = PollBatch {
            title: Some("Substack Blog".to_owned()),
            description: None,
            language: None,
            items: vec![
                PollItemCandidate {
                    title: "Real Audio Interview".to_owned(),
                    url: real_ep_url.clone(),
                    creators: Vec::new(),
                    published_at: None,
                    summary: String::new(),
                    content_html: Some("<p>Interview</p>".to_owned()),
                    thumbnail_url: None,
                    audio_url: Some("https://api.substack.com/feed/podcast/1/ep.mp3".to_owned()),
                    duration: Some(1200),
                    content_type: ContentType::Audio,
                },
                PollItemCandidate {
                    title: "Bogus Webpage Audio".to_owned(),
                    url: bogus_post_url.clone(),
                    creators: Vec::new(),
                    published_at: None,
                    summary: String::new(),
                    content_html: Some("<p>Essay 1</p>".to_owned()),
                    thumbnail_url: None,
                    audio_url: Some(bogus_post_url.to_string()),
                    duration: None,
                    content_type: ContentType::Audio,
                },
                PollItemCandidate {
                    title: "Orphan Audio Without Media".to_owned(),
                    url: orphan_post_url.clone(),
                    creators: Vec::new(),
                    published_at: None,
                    summary: String::new(),
                    content_html: Some("<p>Essay 2</p>".to_owned()),
                    thumbnail_url: None,
                    audio_url: None,
                    duration: None,
                    content_type: ContentType::Audio,
                },
            ],
        };
        let inserted = db.feeds().record_poll_batch(&blog.id, &batch).await.unwrap();
        let id_by_title: std::collections::HashMap<_, _> =
            inserted.iter().map(|(id, cand)| (cand.title.as_str(), id.clone())).collect();

        // 2. Genuine majority-audio podcast feed + empty explicit podcast feed must remain untouched
        let real_pod_url = Url::parse("https://shows.example.com/rss.xml").unwrap();
        let real_pod = db
            .feeds()
            .create(FeedType::Podcast, &real_pod_url, Some("True Podcast"), None)
            .await
            .unwrap();
        db.feeds()
            .record_poll_batch(
                &real_pod.id,
                &PollBatch {
                    title: Some("True Podcast".to_owned()),
                    description: None,
                    language: None,
                    items: vec![PollItemCandidate {
                        title: "Ep 1".to_owned(),
                        url: Url::parse("https://shows.example.com/ep-1").unwrap(),
                        creators: Vec::new(),
                        published_at: None,
                        summary: String::new(),
                        content_html: None,
                        thumbnail_url: None,
                        audio_url: Some("https://cdn.example.com/ep-1.mp3".to_owned()),
                        duration: Some(900),
                        content_type: ContentType::Audio,
                    }],
                },
            )
            .await
            .unwrap();

        let empty_pod_url = Url::parse("https://empty.example.com/podcast.xml").unwrap();
        let empty_pod = db
            .feeds()
            .create(FeedType::Podcast, &empty_pod_url, Some("Empty Podcast"), None)
            .await
            .unwrap();

        // 3. Manually logged catalog podcast_episode (no entry_content) must stay podcast_episode
        let manual_ep = db
            .catalog()
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::PodcastEpisode),
                title: "Logged Episode".to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        db.repair_implicit_podcast_misclassifications().await.unwrap();

        // Substack blog reverted to RSS / publication
        let repaired_blog = db.feeds().get(&blog.id).await.unwrap().unwrap();
        assert_eq!(repaired_blog.feed_type, FeedType::Rss);
        let repaired_blog_entry = db.catalog().get_entry(&blog.id).await.unwrap().unwrap();
        assert_eq!(repaired_blog_entry.kind, EntryKind::Publication);

        // Real .mp3 post stayed playable audio
        let real_ep = db.items().get(&id_by_title["Real Audio Interview"]).await.unwrap().unwrap();
        assert_eq!(real_ep.content_type, ContentType::Audio);
        assert_eq!(
            real_ep.audio_url.as_deref(),
            Some("https://api.substack.com/feed/podcast/1/ep.mp3")
        );

        // Both text-only posts reverted to article and bogus audio_url cleared
        for title in ["Bogus Webpage Audio", "Orphan Audio Without Media"] {
            let item = db.items().get(&id_by_title[title]).await.unwrap().unwrap();
            assert_eq!(item.content_type, ContentType::Article, "{title}");
            assert!(item.audio_url.is_none(), "{title}");
            let entry = db.catalog().get_entry(&item.id).await.unwrap().unwrap();
            assert_eq!(entry.kind, EntryKind::Article, "{title}");
        }

        // Genuine podcast, empty podcast, and manual catalog episode stayed intact
        assert_eq!(
            db.feeds().get(&real_pod.id).await.unwrap().unwrap().feed_type,
            FeedType::Podcast
        );
        assert_eq!(
            db.feeds().get(&empty_pod.id).await.unwrap().unwrap().feed_type,
            FeedType::Podcast
        );
        assert_eq!(
            db.catalog().get_entry(&manual_ep.id).await.unwrap().unwrap().kind,
            EntryKind::PodcastEpisode
        );
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_repair_implicit_podcast_edge_cases_and_sync_propagation() {
        let db = Db::connect("sqlite::memory:").await.unwrap();

        // 1. Genuine podcast with 1 linkless extensionless episode (duration > 0)
        //    + 3 deleted-with-history episodes (entry_content deleted) must stay Podcast.
        let pod_url = Url::parse("https://pod-hist.example.com/rss").unwrap();
        let pod = db
            .feeds()
            .create(FeedType::Podcast, &pod_url, Some("History Pod"), None)
            .await
            .unwrap();
        let linkless_url = Url::parse("https://stream.example.com/episodes/42").unwrap();
        let inserted_pod = db
            .feeds()
            .record_poll_batch(
                &pod.id,
                &PollBatch {
                    title: Some("History Pod".to_owned()),
                    description: None,
                    language: None,
                    items: vec![
                        PollItemCandidate {
                            title: "Linkless Episode".to_owned(),
                            url: linkless_url.clone(),
                            creators: Vec::new(),
                            published_at: None,
                            summary: String::new(),
                            content_html: None,
                            thumbnail_url: None,
                            audio_url: Some(linkless_url.to_string()),
                            duration: Some(1110),
                            content_type: ContentType::Audio,
                        },
                        PollItemCandidate {
                            title: "Deleted Ep 1".to_owned(),
                            url: Url::parse("https://pod-hist.example.com/ep-1").unwrap(),
                            creators: Vec::new(),
                            published_at: None,
                            summary: String::new(),
                            content_html: None,
                            thumbnail_url: None,
                            audio_url: Some("https://cdn.example.com/ep-1.mp3".to_owned()),
                            duration: Some(600),
                            content_type: ContentType::Audio,
                        },
                        PollItemCandidate {
                            title: "Deleted Ep 2".to_owned(),
                            url: Url::parse("https://pod-hist.example.com/ep-2").unwrap(),
                            creators: Vec::new(),
                            published_at: None,
                            summary: String::new(),
                            content_html: None,
                            thumbnail_url: None,
                            audio_url: Some("https://cdn.example.com/ep-2.mp3".to_owned()),
                            duration: Some(600),
                            content_type: ContentType::Audio,
                        },
                        PollItemCandidate {
                            title: "Deleted Ep 3".to_owned(),
                            url: Url::parse("https://pod-hist.example.com/ep-3").unwrap(),
                            creators: Vec::new(),
                            published_at: None,
                            summary: String::new(),
                            content_html: None,
                            thumbnail_url: None,
                            audio_url: Some("https://cdn.example.com/ep-3.mp3".to_owned()),
                            duration: Some(600),
                            content_type: ContentType::Audio,
                        },
                    ],
                },
            )
            .await
            .unwrap();
        let pod_ids: std::collections::HashMap<_, _> =
            inserted_pod.iter().map(|(id, c)| (c.title.as_str(), id.clone())).collect();
        for title in ["Deleted Ep 1", "Deleted Ep 2", "Deleted Ep 3"] {
            sqlx::query("DELETE FROM entry_content WHERE entry_id = ?")
                .bind(&pod_ids[title])
                .execute(db.pool())
                .await
                .unwrap();
            sqlx::query("DELETE FROM entry_media WHERE entry_id = ?")
                .bind(&pod_ids[title])
                .execute(db.pool())
                .await
                .unwrap();
        }

        // 2. Split-brain RSS feed (source_type='rss', entries.kind='podcast') must heal to Publication.
        let split_url = Url::parse("https://split.example.com/rss").unwrap();
        let split_feed =
            db.feeds().create(FeedType::Rss, &split_url, Some("Split RSS"), None).await.unwrap();
        sqlx::query("UPDATE entries SET kind = 'podcast' WHERE id = ?")
            .bind(&split_feed.id)
            .execute(db.pool())
            .await
            .unwrap();

        // 3. Already-article item with a stray bogus webpage audio entry_media row:
        //    repair must delete the bogus media row AND bump entries.sync_seq so peers receive it!
        let art_url = Url::parse("https://split.example.com/p/bogus-media-only").unwrap();
        let art_item = db
            .items()
            .create(&NewItem {
                feed_id: Some(&split_feed.id),
                title: "Article With Bogus Audio Media",
                url: &art_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, duration)
             VALUES ('bogus-media-row', ?, 'audio', ?, NULL)",
        )
        .bind(&art_item.id)
        .bind(art_url.as_str())
        .execute(db.pool())
        .await
        .unwrap();

        // 4. Orphan audio item with a duration-only entry_media row (no remote_url, no local_path):
        //    repair must revert it to Article AND delete the duration-only entry_media row.
        let orphan_url = Url::parse("https://split.example.com/p/orphan-dur").unwrap();
        let orphan_item = db
            .items()
            .create(&NewItem {
                feed_id: Some(&split_feed.id),
                title: "Orphan With Duration Only",
                url: &orphan_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Audio,
            })
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, duration)
             VALUES ('dur-only-media-row', ?, 'audio', NULL, NULL, 720)",
        )
        .bind(&orphan_item.id)
        .execute(db.pool())
        .await
        .unwrap();

        let seq_before: i64 = sqlx::query_scalar("SELECT COALESCE(MAX(sync_seq), 0) FROM entries")
            .fetch_one(db.pool())
            .await
            .unwrap();

        db.repair_implicit_podcast_misclassifications().await.unwrap();

        // Linkless extensionless episode & deleted-history podcast preserved
        let linkless = db.items().get(&pod_ids["Linkless Episode"]).await.unwrap().unwrap();
        assert_eq!(linkless.content_type, ContentType::Audio);
        assert_eq!(linkless.audio_url.as_deref(), Some(linkless_url.as_str()));
        assert_eq!(linkless.duration, Some(1110));
        assert_eq!(db.feeds().get(&pod.id).await.unwrap().unwrap().feed_type, FeedType::Podcast);
        assert_eq!(
            db.catalog().get_entry(&pod.id).await.unwrap().unwrap().kind,
            EntryKind::Podcast
        );

        // Split-brain RSS feed healed
        assert_eq!(
            db.catalog().get_entry(&split_feed.id).await.unwrap().unwrap().kind,
            EntryKind::Publication
        );

        // Bogus media deleted on already-article item, and duration-only media deleted on orphan audio
        let art_after = db.items().get(&art_item.id).await.unwrap().unwrap();
        assert_eq!(art_after.content_type, ContentType::Article);
        assert!(art_after.audio_url.is_none());
        let orphan_after = db.items().get(&orphan_item.id).await.unwrap().unwrap();
        assert_eq!(orphan_after.content_type, ContentType::Article);
        assert!(orphan_after.audio_url.is_none());
        assert!(orphan_after.duration.is_none());
        assert_eq!(
            db.catalog().get_entry(&orphan_item.id).await.unwrap().unwrap().kind,
            EntryKind::Article
        );

        // Incremental peer sync since seq_before MUST emit split_feed, art_item, AND orphan_item!
        let sync_resp = db
            .sync()
            .collect_peer_sync_response_with_budget(
                seq_before,
                100,
                usize::MAX,
                String::new(),
                crate::util::clock::now_utc(),
            )
            .await
            .unwrap();
        let emitted_ids: std::collections::HashSet<&str> =
            sync_resp.entries.iter().map(|e| e.id.as_str()).collect();
        assert!(emitted_ids.contains(split_feed.id.as_str()), "split_feed must propagate via sync");
        assert!(
            emitted_ids.contains(art_item.id.as_str()),
            "article with deleted bogus entry_media must propagate via sync"
        );
        assert!(
            emitted_ids.contains(orphan_item.id.as_str()),
            "reverted orphan audio item must propagate via sync"
        );
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_decouple_rss_audio_entries_reclassifies_rss_narrated_posts_only() {
        let db = Db::connect("sqlite::memory:").await.unwrap();

        let rss_url = Url::parse("https://substack.example.com/feed").unwrap();
        let rss_feed =
            db.feeds().create(FeedType::Rss, &rss_url, Some("Substack"), None).await.unwrap();
        let pod_url = Url::parse("https://realpod.example.com/rss").unwrap();
        let pod_feed =
            db.feeds().create(FeedType::Podcast, &pod_url, Some("Real Pod"), None).await.unwrap();

        let rss_post_url = Url::parse("https://substack.example.com/p/narrated").unwrap();
        let rss_item = db
            .items()
            .create(&NewItem {
                feed_id: Some(&rss_feed.id),
                title: "Narrated Substack Post",
                url: &rss_post_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Audio,
            })
            .await
            .unwrap();
        // Simulate pre-decoupling state: RSS narrated post stored as podcast_episode + audio
        sqlx::query("UPDATE entries SET kind = 'podcast_episode' WHERE id = ?")
            .bind(&rss_item.id)
            .execute(db.pool())
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, duration)
             VALUES ('rss-mp3', ?, 'audio', 'https://cdn.substack.example.com/p.mp3', 1800)",
        )
        .bind(&rss_item.id)
        .execute(db.pool())
        .await
        .unwrap();

        let pod_ep_url = Url::parse("https://realpod.example.com/ep1").unwrap();
        let pod_item = db
            .items()
            .create(&NewItem {
                feed_id: Some(&pod_feed.id),
                title: "Real Podcast Episode",
                url: &pod_ep_url,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Audio,
            })
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, duration)
             VALUES ('pod-mp3', ?, 'audio', 'https://cdn.realpod.example.com/ep1.mp3', 2400)",
        )
        .bind(&pod_item.id)
        .execute(db.pool())
        .await
        .unwrap();

        let manual_ep = db
            .catalog()
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::PodcastEpisode),
                title: "Manual Logged Episode".to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        let seq_before: i64 = sqlx::query_scalar("SELECT COALESCE(MAX(sync_seq), 0) FROM entries")
            .fetch_one(db.pool())
            .await
            .unwrap();

        db.decouple_rss_audio_entries().await.unwrap();

        // RSS narrated post becomes Article/Article while preserving audio_url + duration
        let rss_after = db.items().get(&rss_item.id).await.unwrap().unwrap();
        assert_eq!(rss_after.entry_kind, EntryKind::Article);
        assert_eq!(rss_after.content_type, ContentType::Article);
        assert_eq!(rss_after.audio_url.as_deref(), Some("https://cdn.substack.example.com/p.mp3"));
        assert_eq!(rss_after.duration, Some(1800));

        // Real podcast episode & manual logged episode untouched
        let pod_after = db.items().get(&pod_item.id).await.unwrap().unwrap();
        assert_eq!(pod_after.entry_kind, EntryKind::PodcastEpisode);
        assert_eq!(pod_after.content_type, ContentType::Audio);
        assert_eq!(
            db.catalog().get_entry(&manual_ep.id).await.unwrap().unwrap().kind,
            EntryKind::PodcastEpisode
        );

        // Reclassified RSS narrated post propagates via incremental sync
        let sync_resp = db
            .sync()
            .collect_peer_sync_response_with_budget(
                seq_before,
                100,
                usize::MAX,
                String::new(),
                crate::util::clock::now_utc(),
            )
            .await
            .unwrap();
        let emitted = sync_resp.entries.iter().find(|e| e.id == rss_item.id).unwrap();
        assert_eq!(emitted.kind, EntryKind::Article);
        assert_eq!(emitted.content.as_ref().unwrap().content_type, ContentType::Article);
        assert_eq!(
            emitted.content.as_ref().unwrap().audio_url.as_deref(),
            Some("https://cdn.substack.example.com/p.mp3")
        );
        assert_eq!(emitted.content.as_ref().unwrap().duration, Some(1800));
    }
}
