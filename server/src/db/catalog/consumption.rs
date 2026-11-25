use std::collections::HashSet;

use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite};

use super::{
    CatalogRepo,
    models::{
        CatalogError, CatalogResult, ConsumptionLogRow, ConsumptionLogView, UpsertEntryInput,
    },
};
use crate::{
    db::{
        WriteTx,
        sync::{
            SyncRepo, collection_entry_natural_key, deterministic_auto_consumption_log_id,
            deterministic_consumption_log_id,
        },
        types::{ActivityAction, ActivityEntityType, ConsumptionStatus, EntryKind},
    },
    util::clock::now_utc,
};

/// Removes `entry_id` from all `Want` queues inside `tx` and records `collection_entry`
/// tombstones + activity events so peer sync propagates the promotion out of offline Want queues.
pub(super) async fn remove_from_want_queues_in_tx(
    tx: &mut WriteTx<'_>,
    entry_id: &str,
    ts: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let want_col_ids = sqlx::query_scalar!(
        r#"SELECT ce.collection_id as "collection_id!"
           FROM collection_entries ce
           JOIN collection_specs cs ON cs.collection_id = ce.collection_id
           WHERE ce.entry_id = ? AND cs.collection_role = 'want'"#,
        entry_id,
    )
    .fetch_all(tx.conn())
    .await?;

    for col_id in want_col_ids {
        sqlx::query!(
            "DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
            &col_id,
            entry_id,
        )
        .execute(tx.conn())
        .await?;
        let nat_key = collection_entry_natural_key(&col_id, entry_id);
        SyncRepo::record_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key, ts).await?;
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::CollectionEntry,
            &nat_key,
            ActivityAction::Delete,
            ts,
        )
        .await?;
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", ts as _, &col_id,)
            .execute(tx.conn())
            .await?;
    }
    Ok(())
}

impl CatalogRepo<'_> {
    pub async fn log_consumption(
        &self,
        entry_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ConsumptionLogView> {
        let now = now_utc();
        let event_ts = ts.unwrap_or(now);
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let (log_id, canon_id) =
            Self::log_consumption_in_tx(&mut tx, entry_id, status, notes, event_ts, now).await?;
        tx.commit().await?;

        self.load_consumption_log_view(&log_id, &canon_id).await
    }

    /// Atomically upserts a catalog entry and records a consumption log event in a single
    /// SQLite write transaction.
    pub async fn upsert_and_log_consumption(
        &self,
        input: &UpsertEntryInput,
        status: ConsumptionStatus,
        notes: Option<&str>,
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ConsumptionLogView> {
        let kind = input
            .kind
            .ok_or_else(|| CatalogError::InvalidInput("Entry kind is required".to_owned()))?;
        let title = input.title.trim();
        if title.is_empty() {
            return Err(CatalogError::InvalidInput("Entry title cannot be empty".to_owned()));
        }

        let now = now_utc();
        let event_ts = ts.unwrap_or(now);
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let entry_id = Self::upsert_entry_in_tx(&mut tx, input, kind, title, now).await?;
        let (log_id, canon_id) =
            Self::log_consumption_in_tx(&mut tx, &entry_id, status, notes, event_ts, now).await?;
        tx.commit().await?;

        self.load_consumption_log_view(&log_id, &canon_id).await
    }

    pub(crate) async fn load_consumption_log_view(
        &self,
        log_id: &str,
        canon_id: &str,
    ) -> CatalogResult<ConsumptionLogView> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let entry = Self::get_entry_in_tx(tx.conn(), canon_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_id}'")))?;
        let row = sqlx::query_as!(
            ConsumptionLogRow,
            r#"SELECT id as "id!", entry_id as "entry_id!", ts as "ts!: DateTime<Utc>",
                      status as "status!: ConsumptionStatus", notes as "notes?",
                      created_at as "created_at!: DateTime<Utc>"
               FROM consumption_log
               WHERE id = ?"#,
            log_id,
        )
        .fetch_one(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(ConsumptionLogView {
            id: row.id,
            entry,
            ts: row.ts,
            status: row.status,
            notes: row.notes,
            created_at: row.created_at,
        })
    }

    pub(crate) async fn log_consumption_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> CatalogResult<(String, String)> {
        let canon_id = Self::follow_merged_id_in_tx(tx.conn(), entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))?;
        let clean_notes = notes.map(str::trim).filter(|s| !s.is_empty());
        let log_id = deterministic_consumption_log_id(
            &canon_id,
            event_ts.timestamp_millis(),
            status.as_str(),
        );
        Self::insert_consumption_and_engage_in_tx(
            tx,
            &log_id,
            &canon_id,
            status,
            clean_notes,
            event_ts,
            now,
            true,
            None,
        )
        .await?;
        Ok((log_id, canon_id))
    }

    /// Replays a peer-synced `consumption_log` record while preserving custom/timestamped log IDs,
    /// canonicalizing merged entry IDs, and deduplicating auto-completion log IDs across devices.
    ///
    /// Returns `(saved_log_id, canonical_entry_id, inserted_new)` so callers know whether a new
    /// log row was created or an existing row was deduplicated onto.
    ///
    /// Intentionally defers generator advancement so `merge_incoming_generators_in_tx` can first
    /// merge the peer's own generator state (with its HLC) before advancing any remaining terminal
    /// local draws.
    #[allow(clippy::too_many_arguments)]
    pub(crate) async fn log_consumption_with_id_in_tx(
        tx: &mut WriteTx<'_>,
        incoming_log_id: &str,
        raw_entry_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
        explicit_hlc: Option<&str>,
    ) -> CatalogResult<(String, String, bool)> {
        let canon_id = Self::follow_merged_id_in_tx(tx.conn(), raw_entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{raw_entry_id}'")))?;
        let trimmed_incoming = incoming_log_id.trim();

        let is_auto_id = status == ConsumptionStatus::Consumed
            && (trimmed_incoming == deterministic_auto_consumption_log_id(raw_entry_id.trim())
                || trimmed_incoming == deterministic_auto_consumption_log_id(&canon_id));
        if is_auto_id
            && let Some((auto_id, inserted_new)) =
                Self::ensure_auto_consumed_with_parents_inner_in_tx(
                    tx,
                    &canon_id,
                    notes,
                    event_ts,
                    now,
                    false,
                    explicit_hlc,
                )
                .await?
        {
            return Ok((auto_id, canon_id, inserted_new));
        }

        let clean_notes = notes.map(str::trim).filter(|s| !s.is_empty());
        let needs_canon_det_id = trimmed_incoming.is_empty()
            || (raw_entry_id.trim() != canon_id
                && trimmed_incoming
                    == deterministic_consumption_log_id(
                        raw_entry_id.trim(),
                        event_ts.timestamp_millis(),
                        status.as_str(),
                    ));
        let final_log_id = if needs_canon_det_id {
            deterministic_consumption_log_id(
                &canon_id,
                event_ts.timestamp_millis(),
                status.as_str(),
            )
        } else {
            trimmed_incoming.to_owned()
        };

        let inserted_new = Self::insert_consumption_and_engage_in_tx(
            tx,
            &final_log_id,
            &canon_id,
            status,
            clean_notes,
            event_ts,
            now,
            false,
            explicit_hlc,
        )
        .await?;
        Ok((final_log_id, canon_id, inserted_new))
    }

    /// Runs Want-queue cleanup and parent engagement side effects when an existing log transitions
    /// to `Consumed` (e.g., via synced LWW update).
    pub(crate) async fn promote_entry_consumed_side_effects_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        remove_from_want_queues_in_tx(tx, entry_id, now).await?;
        Self::engage_consumed_parents_in_tx(tx, entry_id, event_ts, now).await?;
        Ok(())
    }

    /// Idempotently ensures `entry_id` has a `'consumed'` log using `deterministic_auto_consumption_log_id`
    /// (unless it already has a `'consumed'` log), removes it from `Want` queues, and engages parent
    /// `podcast`/`publication`/`channel`/`artist` entries.
    pub(crate) async fn ensure_auto_consumed_with_parents_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        notes: Option<&str>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> CatalogResult<Option<String>> {
        Ok(Self::ensure_auto_consumed_with_parents_inner_in_tx(
            tx, entry_id, notes, event_ts, now, true, None,
        )
        .await?
        .map(|(id, _)| id))
    }

    async fn ensure_auto_consumed_with_parents_inner_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        notes: Option<&str>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
        advance_generators: bool,
        explicit_hlc: Option<&str>,
    ) -> CatalogResult<Option<(String, bool)>> {
        let Some(canon_id) = Self::follow_merged_id_in_tx(tx.conn(), entry_id).await? else {
            return Ok(None);
        };
        let clean_notes = notes.map(str::trim).filter(|s| !s.is_empty());

        let existing_consumed_id = sqlx::query_scalar!(
            r#"SELECT id as "id!" FROM consumption_log WHERE entry_id = ? AND status = 'consumed' LIMIT 1"#,
            &canon_id,
        )
        .fetch_optional(tx.conn())
        .await?;

        if let Some(existing_id) = existing_consumed_id {
            if let Some(n) = clean_notes {
                let use_max = explicit_hlc.is_some_and(|h| !h.is_empty());
                let hlc = match explicit_hlc {
                    Some(h) if !h.is_empty() => h.to_owned(),
                    _ => tx.next_hlc().await?,
                };
                sqlx::query(
                    "UPDATE consumption_log
                     SET notes = COALESCE(notes, ?),
                         hlc = CASE
                             WHEN ? THEN CASE WHEN ? > hlc THEN ? ELSE hlc END
                             WHEN notes IS NULL THEN ?
                             ELSE hlc
                         END
                     WHERE id = ?",
                )
                .bind(n)
                .bind(use_max)
                .bind(&hlc)
                .bind(&hlc)
                .bind(&hlc)
                .bind(&existing_id)
                .execute(tx.conn())
                .await?;
            } else if let Some(h) = explicit_hlc.filter(|s| !s.is_empty()) {
                sqlx::query(
                    "UPDATE consumption_log
                     SET hlc = CASE WHEN ? > hlc THEN ? ELSE hlc END
                     WHERE id = ?",
                )
                .bind(h)
                .bind(h)
                .bind(&existing_id)
                .execute(tx.conn())
                .await?;
            }
            remove_from_want_queues_in_tx(tx, &canon_id, now).await?;
            Self::engage_consumed_parents_in_tx(tx, &canon_id, event_ts, now).await?;
            return Ok(Some((existing_id, false)));
        }

        let auto_id = deterministic_auto_consumption_log_id(&canon_id);
        let inserted_new = Self::insert_consumption_and_engage_in_tx(
            tx,
            &auto_id,
            &canon_id,
            ConsumptionStatus::Consumed,
            clean_notes,
            event_ts,
            now,
            advance_generators,
            explicit_hlc,
        )
        .await?;
        Ok(Some((auto_id, inserted_new)))
    }

    #[allow(clippy::too_many_arguments)]
    async fn insert_consumption_and_engage_in_tx(
        tx: &mut WriteTx<'_>,
        log_id: &str,
        canon_id: &str,
        status: ConsumptionStatus,
        clean_notes: Option<&str>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
        advance_generators: bool,
        explicit_hlc: Option<&str>,
    ) -> CatalogResult<bool> {
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::ConsumptionLog, log_id).await?;

        let existed_before: Option<i64> =
            sqlx::query_scalar("SELECT 1 FROM consumption_log WHERE id = ?")
                .bind(log_id)
                .fetch_optional(tx.conn())
                .await?;

        let log_hlc = match explicit_hlc {
            Some(h) if !h.is_empty() => h.to_owned(),
            _ => tx.next_hlc().await?,
        };
        sqlx::query(
            "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                notes = COALESCE(excluded.notes, consumption_log.notes),
                hlc = CASE
                    WHEN excluded.hlc > consumption_log.hlc THEN excluded.hlc
                    ELSE consumption_log.hlc
                END",
        )
        .bind(log_id)
        .bind(canon_id)
        .bind(event_ts)
        .bind(status)
        .bind(clean_notes)
        .bind(now)
        .bind(&log_hlc)
        .execute(tx.conn())
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::ConsumptionLog,
            log_id,
            ActivityAction::Create,
            now,
        )
        .await?;

        if status == ConsumptionStatus::Consumed {
            remove_from_want_queues_in_tx(tx, canon_id, now).await?;
            Self::engage_consumed_parents_in_tx(tx, canon_id, event_ts, now).await?;
        }
        if advance_generators
            && matches!(status, ConsumptionStatus::Consumed | ConsumptionStatus::Abandoned)
        {
            Self::advance_generators_for_entry_in_tx(tx, canon_id, now).await?;
        }
        Ok(existed_before.is_none())
    }

    async fn engage_consumed_parents_in_tx(
        tx: &mut WriteTx<'_>,
        canon_id: &str,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        let row_meta = sqlx::query!(
            r#"SELECT kind as "kind!: EntryKind", creators_json as "creators_json!"
               FROM entries WHERE id = ?"#,
            canon_id,
        )
        .fetch_one(tx.conn())
        .await?;
        let entry_kind = row_meta.kind;
        let creators_json = row_meta.creators_json;

        // Engage parent `podcast` / `publication` / `channel` when an episode/article/video is consumed.
        // Note: consuming a `song` intentionally NEVER auto-consumes its parent `album`!
        let parent_feed_cols = sqlx::query_scalar!(
            r#"SELECT ce.collection_id as "collection_id!"
               FROM collection_entries ce
               JOIN entries pe ON pe.id = ce.collection_id
               WHERE ce.entry_id = ?
                 AND pe.merged_into_id IS NULL
                 AND pe.kind IN ('podcast', 'publication', 'channel')"#,
            canon_id,
        )
        .fetch_all(tx.conn())
        .await?;
        for parent_id in parent_feed_cols {
            Self::ensure_once_consumed_in_tx(tx, &parent_id, event_ts, now).await?;
        }

        // Engage `artist` creators when an `album` or `song` is consumed
        if matches!(entry_kind, EntryKind::Album | EntryKind::Song) {
            let creators: Vec<String> = serde_json::from_str(&creators_json).unwrap_or_default();
            for creator in creators {
                if !creator.trim().is_empty() {
                    let artist_id = Self::ensure_artist_entry_in_tx(tx, &creator, now).await?;
                    let ce_hlc = tx.next_hlc().await?;
                    sqlx::query(
                        "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at, hlc)
                         VALUES (?, ?, ?, ?)",
                    )
                    .bind(&artist_id)
                    .bind(canon_id)
                    .bind(now)
                    .bind(&ce_hlc)
                    .execute(tx.conn())
                    .await?;
                    Self::ensure_once_consumed_in_tx(tx, &artist_id, event_ts, now).await?;
                }
            }
        }
        Ok(())
    }

    pub(crate) async fn ensure_once_consumed_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let already = sqlx::query_scalar!(
            r#"SELECT 1 as "found!: i64" FROM consumption_log WHERE entry_id = ? AND status = 'consumed' LIMIT 1"#,
            entry_id,
        )
        .fetch_optional(tx.conn())
        .await?;
        if already.is_none() {
            let log_id = deterministic_auto_consumption_log_id(entry_id);
            let log_hlc = tx.next_hlc().await?;
            let res = sqlx::query(
                "INSERT OR IGNORE INTO consumption_log (id, entry_id, ts, status, notes, created_at, hlc)
                 VALUES (?, ?, ?, 'consumed', NULL, ?, ?)",
            )
            .bind(&log_id)
            .bind(entry_id)
            .bind(event_ts)
            .bind(now)
            .bind(&log_hlc)
            .execute(tx.conn())
            .await?;
            if res.rows_affected() > 0 {
                SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::ConsumptionLog, &log_id)
                    .await?;
                SyncRepo::record_activity(
                    tx.conn(),
                    ActivityEntityType::ConsumptionLog,
                    &log_id,
                    ActivityAction::Create,
                    now,
                )
                .await?;
            }
        }
        Ok(())
    }

    pub async fn list_diary(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        limit: usize,
    ) -> CatalogResult<Vec<ConsumptionLogView>> {
        self.list_diary_page_with_kind(
            year,
            status,
            super::models::DiaryKindFilter::DefaultNonSong,
            limit,
            0,
        )
        .await
    }

    pub async fn list_diary_with_kind(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        kind_filter: super::models::DiaryKindFilter,
        limit: usize,
    ) -> CatalogResult<Vec<ConsumptionLogView>> {
        self.list_diary_page_with_kind(year, status, kind_filter, limit, 0).await
    }

    pub async fn list_diary_page(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        limit: usize,
        offset: usize,
    ) -> CatalogResult<Vec<ConsumptionLogView>> {
        self.list_diary_page_with_kind(
            year,
            status,
            super::models::DiaryKindFilter::DefaultNonSong,
            limit,
            offset,
        )
        .await
    }

    pub async fn list_diary_page_with_kind(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        kind_filter: super::models::DiaryKindFilter,
        limit: usize,
        offset: usize,
    ) -> CatalogResult<Vec<ConsumptionLogView>> {
        use super::models::DiaryKindFilter;

        let cap = i64::try_from(limit.clamp(1, 2_000)).unwrap_or(200);
        let skip = i64::try_from(offset).unwrap_or(0).max(0);
        validate_diary_year(year)?;

        // Drive from `entries CROSS JOIN consumption_log` for sparse non-song views (so 200k
        // song plays are never scanned to find books/albums/films), and from
        // `consumption_log CROSS JOIN entries` for `All` / `Only(Song)` (walking `ts_iso`
        // backwards in O(limit) with zero temp B-tree).
        let drive_from_entries = match kind_filter {
            DiaryKindFilter::DefaultNonSong => true,
            DiaryKindFilter::Only(k) => k != EntryKind::Song,
            DiaryKindFilter::All => false,
        };

        let mut qb = if drive_from_entries {
            QueryBuilder::<Sqlite>::new(
                "SELECT cl.id, cl.entry_id, cl.ts, cl.status, cl.notes, cl.created_at
                 FROM entries e
                 CROSS JOIN consumption_log cl ON cl.entry_id = e.id
                 WHERE e.merged_into_id IS NULL",
            )
        } else {
            QueryBuilder::<Sqlite>::new(
                "SELECT cl.id, cl.entry_id, cl.ts, cl.status, cl.notes, cl.created_at
                 FROM consumption_log cl
                 CROSS JOIN entries e ON e.id = cl.entry_id
                 WHERE e.merged_into_id IS NULL",
            )
        };
        append_diary_kind_filter_sql(&mut qb, kind_filter);
        if let Some(y) = year {
            qb.push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) >= ")
                .push_bind(format!("{y:04}-01-01T00:00:00.000Z"))
                .push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) < ")
                .push_bind(format!("{:04}-01-01T00:00:00.000Z", y + 1));
        }
        if let Some(st) = status {
            // When driving from `entries` with a year range, prevent SQLite (without ANALYZE)
            // from choosing `idx_consumption_log_status_ts_iso` in the inner loop.
            if drive_from_entries && year.is_some() {
                qb.push(" AND +cl.status = ").push_bind(st);
            } else {
                qb.push(" AND cl.status = ").push_bind(st);
            }
        }
        qb.push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) DESC, cl.id DESC LIMIT ")
            .push_bind(cap)
            .push(" OFFSET ")
            .push_bind(skip);

        let mut tx = crate::db::begin_read(self.pool).await?;
        let log_rows: Vec<ConsumptionLogRow> = qb.build_query_as().fetch_all(tx.conn()).await?;
        let unique_entry_ids: Vec<String> = log_rows
            .iter()
            .map(|r| r.entry_id.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();
        let entry_map = Self::load_entries_by_ids_in_tx(tx.conn(), &unique_entry_ids).await?;
        tx.commit().await?;

        let mut out = Vec::with_capacity(log_rows.len());
        for row in log_rows {
            if let Some(entry) = entry_map.get(&row.entry_id) {
                out.push(ConsumptionLogView {
                    id: row.id,
                    entry: entry.clone(),
                    ts: row.ts,
                    status: row.status,
                    notes: row.notes,
                    created_at: row.created_at,
                });
            }
        }
        Ok(out)
    }

    /// Searches diary logs matching `query` via `entries_fts` (`title`/`creators`/`summary`)
    /// or `consumption_log.notes`, returning newest entries first up to `limit`.
    pub async fn search_diary_logs(
        &self,
        query: &str,
        limit: usize,
    ) -> CatalogResult<Vec<ConsumptionLogView>> {
        let trimmed = query.trim();
        let terms = crate::db::ItemFilter::tokenize_search_terms(trimmed);
        if terms.is_empty() {
            return Ok(Vec::new());
        }
        let cap = i64::try_from(limit.clamp(1, 200)).unwrap_or(20);
        let fts_query = terms
            .iter()
            .map(|t| format!("{{title creators summary}}: {}", t.fts_clause))
            .collect::<Vec<_>>()
            .join(" AND ");
        let bounded_q: String = trimmed.chars().take(256).collect();
        let notes_pattern = format!("%{}%", crate::db::escape_like_literal(&bounded_q));

        let mut tx = crate::db::begin_read(self.pool).await?;
        // Split FTS and notes branches with UNION so FTS drives its branch and the partial index
        // `idx_consumption_log_notes_id` drives the notes branch without scanning 200k NULL notes.
        let log_rows: Vec<ConsumptionLogRow> = sqlx::query_as(
            "SELECT id, entry_id, ts, status, notes, created_at
             FROM (
                 SELECT cl.id, cl.entry_id, cl.ts, cl.status, cl.notes, cl.created_at
                 FROM entries_fts f
                 JOIN entries e ON e.rowid = f.rowid
                 CROSS JOIN consumption_log cl ON cl.entry_id = e.id
                 WHERE entries_fts MATCH ?
                   AND e.merged_into_id IS NULL
                   AND e.kind NOT IN ('artist', 'podcast', 'publication', 'channel')
                 UNION
                 SELECT cl.id, cl.entry_id, cl.ts, cl.status, cl.notes, cl.created_at
                 FROM consumption_log cl
                 JOIN entries e ON e.id = cl.entry_id
                 WHERE cl.notes IS NOT NULL
                   AND cl.notes LIKE ? ESCAPE '\\'
                   AND e.merged_into_id IS NULL
                   AND e.kind NOT IN ('artist', 'podcast', 'publication', 'channel')
             )
             ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ts) DESC, id DESC
             LIMIT ?",
        )
        .bind(&fts_query)
        .bind(&notes_pattern)
        .bind(cap)
        .fetch_all(tx.conn())
        .await?;

        let unique_entry_ids: Vec<String> = log_rows
            .iter()
            .map(|r| r.entry_id.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();
        let entry_map = Self::load_entries_by_ids_in_tx(tx.conn(), &unique_entry_ids).await?;
        tx.commit().await?;

        let mut out = Vec::with_capacity(log_rows.len());
        for row in log_rows {
            if let Some(entry) = entry_map.get(&row.entry_id) {
                out.push(ConsumptionLogView {
                    id: row.id,
                    entry: entry.clone(),
                    ts: row.ts,
                    status: row.status,
                    notes: row.notes,
                    created_at: row.created_at,
                });
            }
        }
        Ok(out)
    }

    /// Returns exact `(year, count)` totals across the default non-song diary history.
    pub async fn list_diary_year_counts(
        &self,
        status: Option<ConsumptionStatus>,
    ) -> CatalogResult<Vec<(i32, i64)>> {
        self.list_diary_year_counts_with_kind(
            status,
            super::models::DiaryKindFilter::DefaultNonSong,
        )
        .await
    }

    /// Returns exact `(year, count)` totals for the specified status and kind filter.
    pub async fn list_diary_year_counts_with_kind(
        &self,
        status: Option<ConsumptionStatus>,
        kind_filter: super::models::DiaryKindFilter,
    ) -> CatalogResult<Vec<(i32, i64)>> {
        use super::models::DiaryKindFilter;

        let drive_from_entries = match kind_filter {
            DiaryKindFilter::DefaultNonSong => true,
            DiaryKindFilter::Only(k) => k != EntryKind::Song,
            DiaryKindFilter::All => false,
        };
        let mut qb = if drive_from_entries {
            QueryBuilder::<Sqlite>::new(
                "SELECT CAST(strftime('%Y', cl.ts) AS INTEGER) AS yr, COUNT(*) AS cnt
                 FROM entries e
                 CROSS JOIN consumption_log cl ON cl.entry_id = e.id
                 WHERE e.merged_into_id IS NULL
                   AND strftime('%Y', cl.ts) IS NOT NULL",
            )
        } else {
            QueryBuilder::<Sqlite>::new(
                "SELECT CAST(strftime('%Y', cl.ts) AS INTEGER) AS yr, COUNT(*) AS cnt
                 FROM consumption_log cl
                 CROSS JOIN entries e ON e.id = cl.entry_id
                 WHERE e.merged_into_id IS NULL
                   AND strftime('%Y', cl.ts) IS NOT NULL",
            )
        };
        append_diary_kind_filter_sql(&mut qb, kind_filter);
        if let Some(st) = status {
            // Almost all rows are `consumed`; sequential table scan avoids 200k random index-to-
            // table bounces through `idx_consumption_log_status_ts_iso`, while sparse `started`/
            // `abandoned` filters still use the index.
            if !drive_from_entries && st == ConsumptionStatus::Consumed {
                qb.push(" AND +cl.status = ").push_bind(st);
            } else {
                qb.push(" AND cl.status = ").push_bind(st);
            }
        }
        qb.push(" GROUP BY yr ORDER BY yr DESC");
        let rows: Vec<(i32, i64)> = qb.build_query_as().fetch_all(self.pool).await?;
        Ok(rows)
    }

    /// Fast count of diary rows per non-song kind plus whether any `song` logs exist, without
    /// scanning hundreds of thousands of `song` rows on default `/diary` loads.
    pub async fn list_diary_kind_summary(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
    ) -> CatalogResult<(Vec<(EntryKind, usize)>, bool)> {
        validate_diary_year(year)?;
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.kind, COUNT(*) AS cnt
             FROM entries e
             CROSS JOIN consumption_log cl ON cl.entry_id = e.id
             WHERE e.merged_into_id IS NULL",
        );
        append_diary_kind_filter_sql(&mut qb, super::models::DiaryKindFilter::DefaultNonSong);
        if let Some(y) = year {
            qb.push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) >= ")
                .push_bind(format!("{y:04}-01-01T00:00:00.000Z"))
                .push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) < ")
                .push_bind(format!("{:04}-01-01T00:00:00.000Z", y + 1));
        }
        if let Some(st) = status {
            if year.is_some() {
                qb.push(" AND +cl.status = ").push_bind(st);
            } else {
                qb.push(" AND cl.status = ").push_bind(st);
            }
        }
        qb.push(" GROUP BY e.kind ORDER BY cnt DESC, e.kind ASC");
        let rows: Vec<(EntryKind, i64)> = qb.build_query_as().fetch_all(self.pool).await?;
        let non_song_counts: Vec<(EntryKind, usize)> = rows
            .into_iter()
            .filter_map(|(k, c)| usize::try_from(c).ok().filter(|&n| n > 0).map(|n| (k, n)))
            .collect();

        // Check in O(1) via `EXISTS` whether at least one matching `song` log exists.
        // Without year/status filters, drive from `entries` so 0-song catalogs never scan logs.
        let mut sqb = if year.is_none() && status.is_none() {
            QueryBuilder::<Sqlite>::new(
                "SELECT EXISTS(
                    SELECT 1
                    FROM entries e
                    CROSS JOIN consumption_log cl ON cl.entry_id = e.id
                    WHERE e.merged_into_id IS NULL AND e.kind = 'song'",
            )
        } else {
            QueryBuilder::<Sqlite>::new(
                "SELECT EXISTS(
                    SELECT 1
                    FROM consumption_log cl
                    CROSS JOIN entries e ON e.id = cl.entry_id
                    WHERE e.merged_into_id IS NULL AND e.kind = 'song'",
            )
        };
        if let Some(y) = year {
            sqb.push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) >= ")
                .push_bind(format!("{y:04}-01-01T00:00:00.000Z"))
                .push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', cl.ts) < ")
                .push_bind(format!("{:04}-01-01T00:00:00.000Z", y + 1));
        }
        if let Some(st) = status {
            sqb.push(" AND cl.status = ").push_bind(st);
        }
        sqb.push(")");
        let has_songs: i64 = sqb.build_query_scalar().fetch_one(self.pool).await?;
        Ok((non_song_counts, has_songs != 0))
    }

    /// Updates an existing diary log's status, notes, and/or timestamp in a single transaction,
    /// recording an update activity event so `PeerSync` propagates the edit via LWW HLC.
    #[allow(clippy::option_option)]
    pub async fn update_consumption_log(
        &self,
        log_id: &str,
        status: Option<ConsumptionStatus>,
        notes: Option<Option<&str>>,
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ConsumptionLogView> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let existing: Option<ConsumptionLogRow> = sqlx::query_as(
            "SELECT id, entry_id, ts, status, notes, created_at
             FROM consumption_log WHERE id = ?",
        )
        .bind(log_id)
        .fetch_optional(tx.conn())
        .await?;
        let Some(row) = existing else {
            return Err(CatalogError::NotFound(format!("Consumption log '{log_id}'")));
        };
        let next_status = status.unwrap_or(row.status);
        let next_notes = match notes {
            Some(Some(raw)) => {
                let trimmed = raw.trim();
                (!trimmed.is_empty()).then(|| trimmed.to_owned())
            }
            Some(None) => None,
            None => row.notes,
        };
        let next_ts = ts.unwrap_or(row.ts);
        let log_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE consumption_log
             SET status = ?, notes = ?, ts = ?, hlc = ?
             WHERE id = ?",
        )
        .bind(next_status)
        .bind(next_notes.as_deref())
        .bind(next_ts)
        .bind(&log_hlc)
        .bind(&row.id)
        .execute(tx.conn())
        .await?;
        if next_status == ConsumptionStatus::Consumed && row.status != ConsumptionStatus::Consumed {
            remove_from_want_queues_in_tx(&mut tx, &row.entry_id, now).await?;
            Self::engage_consumed_parents_in_tx(&mut tx, &row.entry_id, next_ts, now).await?;
        }
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::ConsumptionLog,
            &row.id,
            ActivityAction::Update,
            now,
        )
        .await?;
        tx.commit().await?;
        self.load_consumption_log_view(&row.id, &row.entry_id).await
    }

    /// Deletes a single diary log by ID, recording a sync tombstone and delete activity event.
    pub async fn delete_consumption_log(&self, log_id: &str) -> CatalogResult<bool> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let res = sqlx::query("DELETE FROM consumption_log WHERE id = ?")
            .bind(log_id)
            .execute(tx.conn())
            .await?;
        if res.rows_affected() == 0 {
            return Ok(false);
        }
        SyncRepo::record_tombstone(&mut tx, ActivityEntityType::ConsumptionLog, log_id, now)
            .await?;
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::ConsumptionLog,
            log_id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub(crate) async fn delete_consumed_logs_for_entry_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let Some(canon_id) = Self::follow_merged_id_in_tx(tx.conn(), entry_id).await? else {
            return Ok(0);
        };
        let log_ids: Vec<String> = sqlx::query_scalar(
            "SELECT id FROM consumption_log WHERE entry_id = ? AND status = 'consumed'",
        )
        .bind(&canon_id)
        .fetch_all(tx.conn())
        .await?;
        for log_id in &log_ids {
            sqlx::query("DELETE FROM consumption_log WHERE id = ?")
                .bind(log_id)
                .execute(tx.conn())
                .await?;
            SyncRepo::record_tombstone(tx, ActivityEntityType::ConsumptionLog, log_id, now).await?;
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::ConsumptionLog,
                log_id,
                ActivityAction::Delete,
                now,
            )
            .await?;
        }
        Ok(log_ids.len())
    }
}

fn validate_diary_year(year: Option<i32>) -> CatalogResult<()> {
    if let Some(y) = year
        && !(1..=9998).contains(&y)
    {
        return Err(CatalogError::InvalidInput(
            "Year filter must be between 1 and 9998".to_owned(),
        ));
    }
    Ok(())
}

fn append_diary_kind_filter_sql(
    qb: &mut QueryBuilder<Sqlite>,
    kind_filter: super::models::DiaryKindFilter,
) {
    use super::models::DiaryKindFilter;

    match kind_filter {
        DiaryKindFilter::DefaultNonSong => {
            // Positive `IN (...)` list enables SQLite to seek `idx_entries_kind_lower_title_id`
            // directly when driving from `entries CROSS JOIN consumption_log`.
            qb.push(
                " AND e.kind IN ('article', 'paper', 'video', 'podcast_episode', \
                 'movie', 'book', 'game', 'album', 'tv', 'collection')",
            );
        }
        DiaryKindFilter::All => {
            qb.push(" AND e.kind NOT IN ('artist', 'podcast', 'publication', 'channel')");
        }
        DiaryKindFilter::Only(kind) => {
            qb.push(" AND e.kind = ").push_bind(kind);
        }
    }
}
