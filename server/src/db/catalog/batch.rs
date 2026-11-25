use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;
use tokio::io::{AsyncBufRead, AsyncBufReadExt as _};

use roche_core::identity_norm::{
    normalize_identifier_scheme, normalize_identifier_value, normalize_spotify_identifier,
};

use super::{
    CatalogRepo,
    collections::derive_manual_collection_slug_and_id,
    models::{
        BatchCollectionInput, BatchComparisonInput, BatchGeneratorInput, CatalogBatchInput,
        CatalogBatchResult, CatalogBatchStreamResult, CatalogError, CatalogResult, is_dynamic_rule,
    },
};
use crate::{
    db::{
        WriteTx, entries_core,
        sync::SyncRepo,
        types::{ActivityAction, ActivityEntityType, CollectionRole, EntryKind, HexColor},
    },
    ranking::validate_ordering,
};

const MAX_BATCH_ENTRIES: usize = 5_000;
const MAX_BATCH_COLLECTIONS: usize = 500;
const MAX_BATCH_MEMBERS_PER_COLLECTION: usize = 25_000;
const MAX_BATCH_TOTAL_MEMBERS: usize = 50_000;
const MAX_BATCH_LOGS: usize = 5_000;
const MAX_BATCH_COMPARISONS: usize = 2_000;
const MAX_BATCH_GENERATORS: usize = 200;
const MAX_BATCH_IDENTIFIERS_PER_ENTRY: usize = 32;

/// Transaction-scoped lookup cache shared across operations within a single `WriteTx` to avoid
/// redundant database lookups when applying large batches (e.g. thousands of songs and plays).
#[derive(Debug, Default)]
pub(super) struct BatchTxCache {
    pub resolved_refs: HashMap<String, String>,
    pub resolved_collection_refs: HashMap<String, String>,
    pub artist_ids: HashMap<String, String>,
    pub engaged_consumed_entries: HashSet<String>,
    pub consumed_artists: HashSet<String>,
}

fn validate_batch_limits(batch: &CatalogBatchInput) -> CatalogResult<()> {
    if batch.entries.len() > MAX_BATCH_ENTRIES {
        return Err(CatalogError::InvalidInput(format!(
            "Batch exceeds maximum of {MAX_BATCH_ENTRIES} entries"
        )));
    }
    if batch.collections.len() > MAX_BATCH_COLLECTIONS {
        return Err(CatalogError::InvalidInput(format!(
            "Batch exceeds maximum of {MAX_BATCH_COLLECTIONS} collections"
        )));
    }
    if batch.logs.len() > MAX_BATCH_LOGS {
        return Err(CatalogError::InvalidInput(format!(
            "Batch exceeds maximum of {MAX_BATCH_LOGS} consumption logs"
        )));
    }
    if batch.comparisons.len() > MAX_BATCH_COMPARISONS {
        return Err(CatalogError::InvalidInput(format!(
            "Batch exceeds maximum of {MAX_BATCH_COMPARISONS} comparisons"
        )));
    }
    if batch.generators.len() > MAX_BATCH_GENERATORS {
        return Err(CatalogError::InvalidInput(format!(
            "Batch exceeds maximum of {MAX_BATCH_GENERATORS} generators"
        )));
    }
    for item in &batch.entries {
        if item.entry.identifiers.len() > MAX_BATCH_IDENTIFIERS_PER_ENTRY {
            return Err(CatalogError::InvalidInput(format!(
                "Batch entry exceeds maximum of {MAX_BATCH_IDENTIFIERS_PER_ENTRY} identifiers"
            )));
        }
    }
    let mut total_members = 0usize;
    for col in &batch.collections {
        if col.members.len() > MAX_BATCH_MEMBERS_PER_COLLECTION {
            return Err(CatalogError::InvalidInput(format!(
                "Batch collection exceeds maximum of {MAX_BATCH_MEMBERS_PER_COLLECTION} members"
            )));
        }
        total_members = total_members.saturating_add(col.members.len());
        if total_members > MAX_BATCH_TOTAL_MEMBERS {
            return Err(CatalogError::InvalidInput(format!(
                "Batch exceeds maximum of {MAX_BATCH_TOTAL_MEMBERS} total collection members"
            )));
        }
    }
    Ok(())
}

impl CatalogRepo<'_> {
    /// Applies a declarative batch of entries, manual collections, consumption logs, comparisons,
    /// and collection generator updates inside a single atomic SQLite transaction.
    ///
    /// When `batch.dry_run` is `true`, all validation and database writes run inside the
    /// transaction and are rolled back cleanly before returning.
    pub async fn apply_batch(
        &self,
        batch: &CatalogBatchInput,
    ) -> CatalogResult<CatalogBatchResult> {
        validate_batch_limits(batch)?;
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let mut cache = BatchTxCache::default();
        let mut result = Self::apply_batch_in_tx(&mut tx, batch, now, &mut cache).await?;
        result.dry_run = batch.dry_run;

        if batch.dry_run {
            tx.rollback().await?;
        } else {
            tx.commit().await?;
        }

        Ok(result)
    }

    /// Streams newline-delimited JSON (`CatalogBatchInput` per non-empty line) from `reader`.
    ///
    /// - When `dry_run` is `true`, all lines execute inside a single write transaction that is
    ///   rolled back at the end so cross-line identifier references (such as `song -> album` and
    ///   `log -> song`) validate accurately without modifying disk.
    /// - When `dry_run` is `false`, each non-empty line commits atomically in its own bounded
    ///   write transaction.
    pub async fn apply_batch_ndjson<R, F>(
        &self,
        mut reader: R,
        dry_run: bool,
        mut on_batch: F,
    ) -> CatalogResult<CatalogBatchStreamResult>
    where
        R: AsyncBufRead + Unpin,
        F: FnMut(usize, &CatalogBatchResult),
    {
        let mut summary = CatalogBatchStreamResult { dry_run, ..Default::default() };
        let mut line = String::new();
        let mut line_num = 0usize;

        if dry_run {
            let now = crate::util::clock::now_utc();
            let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
            let mut cache = BatchTxCache::default();
            loop {
                line.clear();
                let bytes = reader.read_line(&mut line).await.map_err(|err| {
                    CatalogError::InvalidInput(format!("Failed reading NDJSON stream: {err}"))
                })?;
                if bytes == 0 {
                    break;
                }
                line_num += 1;
                let trimmed = line.trim();
                if trimmed.is_empty() {
                    continue;
                }
                let batch: CatalogBatchInput = serde_json::from_str(trimmed).map_err(|err| {
                    CatalogError::InvalidInput(format!("Invalid NDJSON on line {line_num}: {err}"))
                })?;
                validate_batch_limits(&batch).map_err(|err| prefix_line_error(line_num, err))?;
                cache.engaged_consumed_entries.clear();
                let mut res = Self::apply_batch_in_tx(&mut tx, &batch, now, &mut cache)
                    .await
                    .map_err(|err| prefix_line_error(line_num, err))?;
                res.dry_run = true;
                summary.accumulate(&res);
                on_batch(summary.batches_processed, &res);
            }
            tx.rollback().await?;
            return Ok(summary);
        }

        loop {
            line.clear();
            let bytes = reader.read_line(&mut line).await.map_err(|err| {
                CatalogError::InvalidInput(format!("Failed reading NDJSON stream: {err}"))
            })?;
            if bytes == 0 {
                break;
            }
            line_num += 1;
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }
            let batch: CatalogBatchInput = serde_json::from_str(trimmed).map_err(|err| {
                CatalogError::InvalidInput(format!("Invalid NDJSON on line {line_num}: {err}"))
            })?;
            if batch.dry_run {
                return Err(CatalogError::InvalidInput(format!(
                    "NDJSON line {line_num} sets dry_run=true during live apply; pass --dry-run instead"
                )));
            }
            let res =
                self.apply_batch(&batch).await.map_err(|err| prefix_line_error(line_num, err))?;
            summary.accumulate(&res);
            on_batch(summary.batches_processed, &res);
        }

        Ok(summary)
    }

    async fn apply_batch_in_tx(
        tx: &mut WriteTx<'_>,
        batch: &CatalogBatchInput,
        now: DateTime<Utc>,
        cache: &mut BatchTxCache,
    ) -> CatalogResult<CatalogBatchResult> {
        let mut ref_map: HashMap<String, String> =
            HashMap::with_capacity(batch.entries.len() + batch.collections.len());

        // Phase 1: Upsert collection specs first so entries in the same batch can link to them
        // via `parent_collection_id`.
        let mut resolved_col_ids: Vec<String> = Vec::with_capacity(batch.collections.len());
        for col in &batch.collections {
            let col_id = Self::upsert_batch_collection_spec_in_tx(tx, col, now).await?;
            if let Some(key) = col.ref_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                ref_map.insert(key.to_owned(), col_id.clone());
            }
            resolved_col_ids.push(col_id);
        }

        // Phase 2: Upsert entries (resolving `parent_collection_id` through refs/identifiers/slugs).
        let mut entries_processed = 0usize;
        for item in &batch.entries {
            let kind = item.entry.kind.ok_or_else(|| {
                CatalogError::InvalidInput("Batch entry `kind` is required".to_owned())
            })?;
            let title = item.entry.title.trim();
            if title.is_empty() {
                return Err(CatalogError::InvalidInput(
                    "Batch entry `title` cannot be empty".to_owned(),
                ));
            }
            let resolved_entry;
            let entry_input = if let Some(raw_parent) =
                item.entry.parent_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
            {
                let parent_id =
                    Self::resolve_batch_parent_ref_in_tx(tx.conn(), &ref_map, cache, raw_parent)
                        .await?;
                let mut cloned = item.entry.clone();
                cloned.parent_collection_id = Some(parent_id);
                resolved_entry = cloned;
                &resolved_entry
            } else {
                &item.entry
            };
            let entry_id =
                Self::upsert_entry_cached_in_tx(tx, entry_input, kind, title, now, Some(cache))
                    .await?;
            if let Some(key) = item.ref_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                ref_map.insert(key.to_owned(), entry_id.clone());
            }
            cache.resolved_refs.insert(entry_id.clone(), entry_id);
            entries_processed += 1;
        }

        // Phase 3: Attach collection members (and prune unlisted members when `replace_members`).
        let collections_processed = Self::apply_batch_collection_members_in_tx(
            tx,
            &ref_map,
            cache,
            &batch.collections,
            &resolved_col_ids,
            now,
        )
        .await?;

        // Phase 4: Record consumption logs.
        let mut logs_recorded = 0usize;
        for log in &batch.logs {
            let target_id =
                Self::resolve_batch_ref_in_tx(tx.conn(), &ref_map, cache, &log.entry_ref).await?;
            let event_ts = log.ts.unwrap_or(now);
            Self::log_consumption_cached_in_tx(
                tx,
                &target_id,
                log.status,
                log.notes.as_deref(),
                event_ts,
                now,
                Some(cache),
            )
            .await?;
            logs_recorded += 1;
        }

        // Phase 5: Append comparisons.
        let comparisons_recorded =
            Self::apply_batch_comparisons_in_tx(tx, &ref_map, cache, &batch.comparisons, now)
                .await?;

        // Phase 6: Update collection generators.
        let generators_updated =
            Self::apply_batch_generators_in_tx(tx, &ref_map, cache, &batch.generators, now).await?;

        Ok(CatalogBatchResult {
            collections_processed,
            entries_processed,
            logs_recorded,
            comparisons_recorded,
            generators_updated,
            dry_run: batch.dry_run,
        })
    }

    async fn apply_batch_collection_members_in_tx(
        tx: &mut WriteTx<'_>,
        ref_map: &HashMap<String, String>,
        cache: &mut BatchTxCache,
        collections: &[BatchCollectionInput],
        resolved_col_ids: &[String],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut count = 0usize;
        for (col, col_id) in collections.iter().zip(resolved_col_ids.iter()) {
            // Deduplicate resolved members in memory (preserving last non-None position/added_at
            // semantics) so playlists with repeated tracks do not bounce and churn sync_seq on
            // idempotent re-imports.
            let mut order: Vec<String> = Vec::with_capacity(col.members.len());
            let mut merged: HashMap<String, (Option<f64>, Option<DateTime<Utc>>)> =
                HashMap::with_capacity(col.members.len());
            for member in &col.members {
                let member_id =
                    Self::resolve_batch_ref_in_tx(tx.conn(), ref_map, cache, &member.entry_ref)
                        .await?;
                if let Some((pos, added)) = merged.get_mut(&member_id) {
                    if member.position.is_some() {
                        *pos = member.position;
                    }
                    if member.added_at.is_some() {
                        *added = member.added_at;
                    }
                } else {
                    order.push(member_id.clone());
                    merged.insert(member_id, (member.position, member.added_at));
                }
            }
            for member_id in &order {
                let (position, added_at) = merged.get(member_id).copied().unwrap_or((None, None));
                Self::attach_collection_member_with_added_at_in_tx(
                    tx, col_id, member_id, position, added_at, now, false,
                )
                .await?;
            }
            if col.replace_members {
                Self::prune_unlisted_collection_members_in_tx(tx, col_id, &merged, now).await?;
            }
            count += 1;
        }
        Ok(count)
    }

    async fn prune_unlisted_collection_members_in_tx(
        tx: &mut WriteTx<'_>,
        col_id: &str,
        keep: &HashMap<String, (Option<f64>, Option<DateTime<Utc>>)>,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        let existing_members: Vec<String> =
            sqlx::query_scalar("SELECT entry_id FROM collection_entries WHERE collection_id = ?")
                .bind(col_id)
                .fetch_all(tx.conn())
                .await?;
        let mut removed_ids: HashSet<String> = HashSet::new();
        for existing_eid in existing_members {
            if !keep.contains_key(&existing_eid)
                && Self::remove_collection_member_defer_generator_in_tx(
                    tx,
                    col_id,
                    &existing_eid,
                    now,
                )
                .await?
            {
                removed_ids.insert(existing_eid);
            }
        }
        if !removed_ids.is_empty() {
            let current_draw: Option<String> = sqlx::query_scalar(
                "SELECT current_entry_id FROM collection_generators WHERE collection_id = ?",
            )
            .bind(col_id)
            .fetch_optional(tx.conn())
            .await?
            .flatten();
            if let Some(drawn_id) = current_draw
                && removed_ids.contains(&drawn_id)
            {
                Self::advance_generator_if_drawing_in_tx(tx, col_id, &drawn_id, now).await?;
            }
        }
        Ok(())
    }

    async fn lookup_existing_batch_collection_in_tx(
        conn: &mut SqliteConnection,
        explicit_id: Option<&str>,
        explicit_slug: Option<&str>,
    ) -> CatalogResult<
        Option<(String, CollectionRole, Option<String>, Option<String>, Option<String>)>,
    > {
        if explicit_id.is_none() && explicit_slug.is_none() {
            return Ok(None);
        }
        let id_arg = explicit_id.unwrap_or("");
        let slug_arg = explicit_slug.unwrap_or("");
        let norm_slug = slug_arg.to_ascii_lowercase().replace('-', "_");
        let row = sqlx::query_as(
            "SELECT collection_id, collection_role, rule_json, slug, color
             FROM collection_specs
             WHERE (? != '' AND collection_id = ?)
                OR (? != '' AND slug IS NOT NULL AND (LOWER(slug) = LOWER(?) OR LOWER(slug) = ?))
             ORDER BY CASE WHEN ? != '' AND collection_id = ? THEN 0 ELSE 1 END ASC
             LIMIT 1",
        )
        .bind(id_arg)
        .bind(id_arg)
        .bind(slug_arg)
        .bind(slug_arg)
        .bind(&norm_slug)
        .bind(id_arg)
        .bind(id_arg)
        .fetch_optional(&mut *conn)
        .await?;
        Ok(row)
    }

    async fn upsert_batch_collection_spec_in_tx(
        tx: &mut WriteTx<'_>,
        col: &BatchCollectionInput,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
        let explicit_id = col.id.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let explicit_slug = col.slug.as_deref().map(str::trim).filter(|s| !s.is_empty());

        let mut existing =
            Self::lookup_existing_batch_collection_in_tx(tx.conn(), explicit_id, explicit_slug)
                .await?;

        let derived = if existing.is_some() && col.title.trim().is_empty() {
            None
        } else {
            let parts = derive_batch_collection_slug_and_id(col)?;
            if existing.is_none() {
                existing = Self::lookup_existing_batch_collection_in_tx(
                    tx.conn(),
                    Some(&parts.2),
                    Some(&parts.1),
                )
                .await?;
            }
            Some(parts)
        };

        if let Some((ex_id, ex_role, ex_rule, ex_slug, ex_color)) = existing {
            if is_dynamic_rule(ex_rule.as_deref())
                || matches!(ex_role, CollectionRole::Feed | CollectionRole::Tag)
            {
                let label = explicit_slug.or(ex_slug.as_deref()).unwrap_or(&ex_id);
                return Err(CatalogError::Conflict(format!(
                    "Collection '{label}' conflicts with an immutable dynamic/feed/tag collection"
                )));
            }
            if crate::db::is_protected_system_collection_id(&ex_id) {
                Self::validate_protected_collection_batch_target_in_tx(
                    tx.conn(),
                    &ex_id,
                    ex_role,
                    explicit_id,
                    explicit_slug,
                    col,
                )
                .await?;
                return Ok(ex_id);
            }
            if ex_role == CollectionRole::System {
                let label = explicit_slug.or(ex_slug.as_deref()).unwrap_or(&ex_id);
                return Err(CatalogError::Conflict(format!(
                    "Collection '{label}' conflicts with an immutable system collection"
                )));
            }
            Self::update_existing_manual_batch_collection_in_tx(
                tx,
                &ex_id,
                ex_role,
                ex_color.as_deref(),
                derived.as_ref().map(|(t, _, _)| t.as_str()),
                col,
                now,
            )
            .await?;
            return Ok(ex_id);
        }

        let (clean_title, derived_slug, target_id) = match derived {
            Some(parts) => parts,
            None => derive_batch_collection_slug_and_id(col)?,
        };
        Self::insert_new_batch_collection_in_tx(
            tx,
            col,
            &clean_title,
            &derived_slug,
            &target_id,
            now,
        )
        .await?;
        Ok(target_id)
    }

    async fn validate_protected_collection_batch_target_in_tx(
        conn: &mut SqliteConnection,
        ex_id: &str,
        ex_role: CollectionRole,
        explicit_id: Option<&str>,
        explicit_slug: Option<&str>,
        col: &BatchCollectionInput,
    ) -> CatalogResult<()> {
        if explicit_id.is_none() && explicit_slug.is_none() {
            return Err(CatalogError::Conflict(format!(
                "Collection '{}' conflicts with a protected system collection; pass explicit `slug` or `id` to attach members",
                col.title.trim()
            )));
        }
        if col.replace_members {
            return Err(CatalogError::Conflict(format!(
                "Protected system collection '{ex_id}' does not support replace_members"
            )));
        }
        let validated_color = HexColor::parse_optional(col.color.as_deref())
            .map_err(|e| CatalogError::InvalidInput(e.to_string()))?;
        let has_summary = col.summary.as_deref().map(str::trim).is_some_and(|s| !s.is_empty());
        let role_conflict = col.role != ex_role && col.role != CollectionRole::List;
        let requested_title = col.title.trim();
        let title_conflict = if requested_title.is_empty() {
            false
        } else {
            let stored_title: Option<String> =
                sqlx::query_scalar("SELECT title FROM entries WHERE id = ?")
                    .bind(ex_id)
                    .fetch_optional(&mut *conn)
                    .await?;
            stored_title.as_deref() != Some(requested_title)
        };
        if has_summary || validated_color.is_some() || role_conflict || title_conflict {
            return Err(CatalogError::Conflict(format!(
                "Protected system collection '{ex_id}' metadata cannot be modified via batch"
            )));
        }
        Ok(())
    }

    async fn insert_new_batch_collection_in_tx(
        tx: &mut WriteTx<'_>,
        col: &BatchCollectionInput,
        clean_title: &str,
        derived_slug: &str,
        target_id: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        if crate::db::is_protected_system_collection_id(target_id) {
            return Err(CatalogError::Conflict(format!(
                "Collection '{derived_slug}' conflicts with a protected system collection"
            )));
        }

        let existing_entry: Option<(EntryKind, Option<String>)> =
            sqlx::query_as("SELECT kind, merged_into_id FROM entries WHERE id = ?")
                .bind(target_id)
                .fetch_optional(tx.conn())
                .await?;
        if let Some((ex_kind, ex_merged)) = existing_entry
            && (ex_kind != EntryKind::Collection || ex_merged.is_some())
        {
            return Err(CatalogError::Conflict(format!(
                "Collection '{derived_slug}' targets an existing non-collection or merged entry"
            )));
        }

        let clean_summary = col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let validated_color = HexColor::parse_optional(col.color.as_deref())
            .map_err(|e| CatalogError::InvalidInput(e.to_string()))?;
        let clean_color = validated_color.as_ref().map(HexColor::as_str);

        let entry_hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT INTO entries (id, kind, title, summary, created_at, updated_at, hlc)
             VALUES (?, 'collection', ?, ?, ?, ?, ?)",
        )
        .bind(target_id)
        .bind(clean_title)
        .bind(clean_summary)
        .bind(now)
        .bind(now)
        .bind(&entry_hlc)
        .execute(tx.conn())
        .await?;

        sqlx::query(
            "INSERT INTO collection_specs
             (collection_id, slug, collection_role, rule_json, color)
             VALUES (?, ?, ?, NULL, ?)",
        )
        .bind(target_id)
        .bind(derived_slug)
        .bind(col.role)
        .bind(clean_color)
        .execute(tx.conn())
        .await?;

        entries_core::upsert_entry_identifier_in_tx(tx.conn(), target_id, "slug", derived_slug)
            .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            target_id,
            ActivityAction::Create,
            now,
        )
        .await?;

        Ok(())
    }

    async fn update_existing_manual_batch_collection_in_tx(
        tx: &mut WriteTx<'_>,
        target_id: &str,
        ex_role: CollectionRole,
        ex_color: Option<&str>,
        requested_title: Option<&str>,
        col: &BatchCollectionInput,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        let existing_entry: Option<(EntryKind, Option<String>, String, Option<String>)> =
            sqlx::query_as("SELECT kind, merged_into_id, title, summary FROM entries WHERE id = ?")
                .bind(target_id)
                .fetch_optional(tx.conn())
                .await?;
        let Some((ex_kind, ex_merged, cur_title, cur_summary)) = existing_entry else {
            return Err(CatalogError::NotFound(format!("Collection entry '{target_id}'")));
        };
        if ex_kind != EntryKind::Collection || ex_merged.is_some() {
            return Err(CatalogError::Conflict(format!(
                "Collection '{target_id}' targets an existing non-collection or merged entry"
            )));
        }

        let clean_summary = col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let validated_color = HexColor::parse_optional(col.color.as_deref())
            .map_err(|e| CatalogError::InvalidInput(e.to_string()))?;
        let clean_color = validated_color.as_ref().map(HexColor::as_str);

        let next_title = requested_title.unwrap_or(&cur_title);
        let next_summary = clean_summary.or(cur_summary.as_deref());
        let entry_changed = next_title != cur_title || next_summary != cur_summary.as_deref();
        if entry_changed {
            let entry_hlc = tx.next_hlc().await?;
            sqlx::query(
                "UPDATE entries
                 SET title = ?, summary = ?, updated_at = ?, hlc = ?
                 WHERE id = ?",
            )
            .bind(next_title)
            .bind(next_summary)
            .bind(now)
            .bind(&entry_hlc)
            .bind(target_id)
            .execute(tx.conn())
            .await?;
        }

        let next_color = clean_color.or(ex_color);
        let role_changed = requested_title.is_some() && col.role != ex_role;
        let color_changed = next_color != ex_color;
        if role_changed || color_changed {
            let next_role = if requested_title.is_some() { col.role } else { ex_role };
            sqlx::query(
                "UPDATE collection_specs
                 SET collection_role = ?, color = ?
                 WHERE collection_id = ?",
            )
            .bind(next_role)
            .bind(next_color)
            .bind(target_id)
            .execute(tx.conn())
            .await?;
        }

        if entry_changed || role_changed || color_changed {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Entry,
                target_id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }
        Ok(())
    }

    async fn apply_batch_comparisons_in_tx(
        tx: &mut WriteTx<'_>,
        ref_map: &HashMap<String, String>,
        cache: &mut BatchTxCache,
        comparisons: &[BatchComparisonInput],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut recorded = 0usize;
        for cmp in comparisons {
            let mut resolved_ordering: Vec<Vec<String>> = Vec::with_capacity(cmp.ordering.len());
            for bucket in &cmp.ordering {
                let mut resolved_bucket = Vec::with_capacity(bucket.len());
                for item_ref in bucket {
                    let id =
                        Self::resolve_batch_ref_in_tx(tx.conn(), ref_map, cache, item_ref).await?;
                    if !resolved_bucket.contains(&id) {
                        resolved_bucket.push(id);
                    }
                }
                if !resolved_bucket.is_empty() {
                    resolved_ordering.push(resolved_bucket);
                }
            }
            validate_ordering(&resolved_ordering).map_err(CatalogError::InvalidInput)?;

            let scope_id =
                match cmp.scope_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                    Some(raw_scope) => Some(
                        Self::resolve_batch_collection_ref_in_tx(tx.conn(), ref_map, raw_scope)
                            .await?
                            .0,
                    ),
                    None => None,
                };
            let cmp_ts = cmp.ts.unwrap_or(now);
            let (_, inserted) =
                Self::append_comparison_in_tx(tx, scope_id, resolved_ordering, cmp_ts, now).await?;
            if inserted {
                recorded += 1;
            }
        }
        Ok(recorded)
    }

    async fn apply_batch_generators_in_tx(
        tx: &mut WriteTx<'_>,
        ref_map: &HashMap<String, String>,
        cache: &mut BatchTxCache,
        generators: &[BatchGeneratorInput],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut updated = 0usize;
        for gen_input in generators {
            let (col_id, col_title, role, rule_json) = Self::resolve_batch_collection_ref_in_tx(
                tx.conn(),
                ref_map,
                &gen_input.collection_ref,
            )
            .await?;
            Self::ensure_generator_eligible(
                &col_id,
                &col_title,
                role,
                is_dynamic_rule(rule_json.as_deref()),
            )?;
            let explicit_entry_id = match gen_input
                .current_entry_ref
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                Some(r) => Some(Self::resolve_batch_ref_in_tx(tx.conn(), ref_map, cache, r).await?),
                None => None,
            };
            Self::update_collection_generator_in_tx(
                tx,
                &col_id,
                &col_title,
                gen_input.enabled,
                explicit_entry_id.as_deref(),
                gen_input.drawn_at,
                now,
            )
            .await?;
            updated += 1;
        }
        Ok(updated)
    }

    async fn resolve_batch_parent_ref_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        cache: &mut BatchTxCache,
        raw_parent: &str,
    ) -> CatalogResult<String> {
        match Self::resolve_batch_ref_in_tx(&mut *tx, ref_map, cache, raw_parent).await {
            Ok(id) => Ok(id),
            Err(CatalogError::NotFound(_)) => {
                let trimmed = raw_parent.trim();
                if let Some(cached) = cache.resolved_collection_refs.get(trimmed) {
                    return Ok(cached.clone());
                }
                let (col_id, _, _, _) =
                    Self::resolve_batch_collection_ref_in_tx(&mut *tx, ref_map, trimmed).await?;
                cache.resolved_collection_refs.insert(trimmed.to_owned(), col_id.clone());
                Ok(col_id)
            }
            Err(other) => Err(other),
        }
    }

    async fn resolve_batch_collection_ref_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        raw_ref: &str,
    ) -> CatalogResult<(String, String, CollectionRole, Option<String>)> {
        let trimmed = raw_ref.trim();
        let candidate = ref_map.get(trimmed).map_or(trimmed, String::as_str);
        let norm_slug = candidate.to_ascii_lowercase().replace('-', "_");
        let row: Option<(String, String, CollectionRole, Option<String>)> = sqlx::query_as(
            "SELECT e.id, e.title, cs.collection_role, cs.rule_json
             FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL
               AND (e.id = ? OR LOWER(cs.slug) = LOWER(?) OR LOWER(cs.slug) = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
        )
        .bind(candidate)
        .bind(candidate)
        .bind(&norm_slug)
        .bind(candidate)
        .fetch_optional(&mut *tx)
        .await?;

        row.ok_or_else(|| CatalogError::NotFound(format!("Batch collection reference '{trimmed}'")))
    }

    async fn resolve_batch_ref_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        cache: &mut BatchTxCache,
        raw_ref: &str,
    ) -> CatalogResult<String> {
        let trimmed = raw_ref.trim();
        if trimmed.is_empty() {
            return Err(CatalogError::InvalidInput("Batch entry reference cannot be empty".into()));
        }
        if let Some(mapped) = ref_map.get(trimmed) {
            if let Some(cached) = cache.resolved_refs.get(mapped) {
                return Ok(cached.clone());
            }
            if let Some(canon) = Self::follow_merged_id_in_tx(&mut *tx, mapped).await? {
                cache.resolved_refs.insert(mapped.clone(), canon.clone());
                return Ok(canon);
            }
        }
        if let Some(cached) = cache.resolved_refs.get(trimmed) {
            return Ok(cached.clone());
        }
        if let Some(canon) = Self::follow_merged_id_in_tx(&mut *tx, trimmed).await? {
            cache.resolved_refs.insert(trimmed.to_owned(), canon.clone());
            return Ok(canon);
        }
        if let Some((raw_scheme, raw_val)) = trimmed.split_once(':') {
            let scheme = normalize_identifier_scheme(raw_scheme);
            if !scheme.is_empty() && !raw_val.trim().is_empty() {
                if scheme == "spotify" {
                    // Direct lookup for full Spotify URIs (`spotify:track:...`, `spotify:album:...`,
                    // `spotify:artist:...`, etc.), normalizing uppercase `SPOTIFY:` schemes too.
                    let lower_uri = format!("spotify:{}", raw_val.trim());
                    let full_norm = normalize_spotify_identifier(EntryKind::Song, &lower_uri);
                    if let Some(found) = entries_core::find_entry_id_by_identifier_in_tx(
                        &mut *tx, "spotify", &full_norm,
                    )
                    .await?
                        && let Some(canon) = Self::follow_merged_id_in_tx(&mut *tx, &found).await?
                    {
                        cache.resolved_refs.insert(trimmed.to_owned(), canon.clone());
                        return Ok(canon);
                    }
                }
                let mut tried_vals = HashSet::new();
                for candidate_kind in [
                    EntryKind::Album,
                    EntryKind::Song,
                    EntryKind::Artist,
                    EntryKind::Podcast,
                    EntryKind::PodcastEpisode,
                    EntryKind::Book,
                    EntryKind::Movie,
                ] {
                    let norm_val = normalize_identifier_value(candidate_kind, &scheme, raw_val);
                    if !tried_vals.insert(norm_val.clone()) {
                        continue;
                    }
                    if let Some(found) = entries_core::find_entry_id_by_identifier_in_tx(
                        &mut *tx, &scheme, &norm_val,
                    )
                    .await?
                        && let Some(canon) = Self::follow_merged_id_in_tx(&mut *tx, &found).await?
                    {
                        cache.resolved_refs.insert(trimmed.to_owned(), canon.clone());
                        return Ok(canon);
                    }
                }
            }
        }
        Err(CatalogError::NotFound(format!("Batch entry reference '{trimmed}'")))
    }
}

fn prefix_line_error(line_num: usize, err: CatalogError) -> CatalogError {
    match err {
        CatalogError::InvalidInput(msg) => {
            CatalogError::InvalidInput(format!("NDJSON line {line_num}: {msg}"))
        }
        CatalogError::NotFound(msg) => {
            CatalogError::NotFound(format!("NDJSON line {line_num}: {msg}"))
        }
        CatalogError::Conflict(msg) => {
            CatalogError::Conflict(format!("NDJSON line {line_num}: {msg}"))
        }
        CatalogError::ImmutableCollection { title, reason } => CatalogError::ImmutableCollection {
            title: format!("{title} (NDJSON line {line_num})"),
            reason,
        },
        CatalogError::AmbiguousLookup { query, candidates } => CatalogError::AmbiguousLookup {
            query: format!("{query} (NDJSON line {line_num})"),
            candidates,
        },
        CatalogError::Internal(msg) => {
            CatalogError::Internal(format!("NDJSON line {line_num}: {msg}"))
        }
        CatalogError::Db(db_err) => CatalogError::Db(db_err),
    }
}

fn derive_batch_collection_slug_and_id(
    col: &BatchCollectionInput,
) -> CatalogResult<(String, String, String)> {
    let (clean_title, derived_slug, default_id) =
        derive_manual_collection_slug_and_id(&col.title, col.slug.as_deref(), col.role)?;
    let requested_id = col
        .id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map_or(default_id, str::to_owned);
    Ok((clean_title, derived_slug, requested_id))
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::too_many_lines)]
mod tests {
    use chrono::TimeZone;

    use crate::db::{
        BatchCollectionInput, BatchCollectionMemberInput, BatchComparisonInput, BatchEntryInput,
        BatchGeneratorInput, BatchLogInput, CatalogBatchInput, CatalogError, CollectionRole,
        ConsumptionStatus, Db, EntryIdentifier, EntryKind, STARRED_COLLECTION_ID, UpsertEntryInput,
    };

    fn sample_catalog_batch(dry_run: bool) -> CatalogBatchInput {
        let ts1 = chrono::Utc.with_ymd_and_hms(2025, 4, 1, 10, 0, 0).unwrap();
        let ts2 = chrono::Utc.with_ymd_and_hms(2025, 4, 2, 10, 0, 0).unwrap();
        let ts3 = chrono::Utc.with_ymd_and_hms(2025, 4, 3, 10, 0, 0).unwrap();
        CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                ref_key: Some("col:canon".to_owned()),
                id: None,
                title: "Album Canon".to_owned(),
                slug: Some("album_canon".to_owned()),
                role: CollectionRole::List,
                summary: Some("Curated listening queue".to_owned()),
                color: None,
                members: vec![
                    BatchCollectionMemberInput {
                        entry_ref: "alb:sinatra".to_owned(),
                        position: Some(1.0),
                        added_at: None,
                    },
                    BatchCollectionMemberInput {
                        entry_ref: "alb:ellington".to_owned(),
                        position: Some(2.0),
                        added_at: None,
                    },
                    BatchCollectionMemberInput {
                        entry_ref: "alb:elvis".to_owned(),
                        position: Some(3.0),
                        added_at: None,
                    },
                ],
                replace_members: false,
            }],
            entries: vec![
                BatchEntryInput {
                    ref_key: Some("alb:sinatra".to_owned()),
                    entry: UpsertEntryInput {
                        kind: Some(EntryKind::Album),
                        title: "In The Wee Small Hours".to_owned(),
                        creators: vec!["Frank Sinatra".to_owned()],
                        identifiers: vec![EntryIdentifier {
                            scheme: "youtube_music".to_owned(),
                            value: "OLAK5uy_sinatra".to_owned(),
                        }],
                        metadata: Some(serde_json::json!({ "global_rating": 3.81 })),
                        ..Default::default()
                    },
                },
                BatchEntryInput {
                    ref_key: Some("alb:ellington".to_owned()),
                    entry: UpsertEntryInput {
                        kind: Some(EntryKind::Album),
                        title: "Ellington at Newport".to_owned(),
                        creators: vec!["Duke Ellington".to_owned()],
                        ..Default::default()
                    },
                },
                BatchEntryInput {
                    ref_key: Some("alb:elvis".to_owned()),
                    entry: UpsertEntryInput {
                        kind: Some(EntryKind::Album),
                        title: "Elvis Presley".to_owned(),
                        creators: vec!["Elvis Presley".to_owned()],
                        ..Default::default()
                    },
                },
            ],
            logs: vec![
                BatchLogInput {
                    entry_ref: "alb:sinatra".to_owned(),
                    status: ConsumptionStatus::Consumed,
                    notes: Some("★★★★★ — Late night classic".to_owned()),
                    ts: Some(ts1),
                },
                BatchLogInput {
                    entry_ref: "alb:ellington".to_owned(),
                    status: ConsumptionStatus::Consumed,
                    notes: Some("★★★★☆ — Blistering live energy".to_owned()),
                    ts: Some(ts2),
                },
            ],
            comparisons: vec![BatchComparisonInput {
                scope_collection_id: None,
                ordering: vec![vec!["alb:sinatra".to_owned()], vec!["alb:ellington".to_owned()]],
                ts: Some(ts2),
            }],
            generators: vec![BatchGeneratorInput {
                collection_ref: "col:canon".to_owned(),
                enabled: Some(true),
                current_entry_ref: Some("alb:elvis".to_owned()),
                drawn_at: Some(ts3),
            }],
            dry_run,
        }
    }

    #[tokio::test]
    async fn test_apply_batch_dry_run_and_commit_creates_collection_pool_and_ranks() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let mut batch = sample_catalog_batch(true);

        let dry_res = db.catalog().apply_batch(&batch).await.unwrap();
        assert!(dry_res.dry_run);
        assert_eq!(dry_res.collections_processed, 1);
        assert_eq!(dry_res.entries_processed, 3);
        assert_eq!(dry_res.logs_recorded, 2);
        assert_eq!(dry_res.comparisons_recorded, 1);
        assert_eq!(dry_res.generators_updated, 1);
        assert_eq!(db.catalog().list_diary(None, None, 50).await.unwrap().len(), 0);

        batch.dry_run = false;
        let live_res = db.catalog().apply_batch(&batch).await.unwrap();
        assert!(!live_res.dry_run);

        let gen_state = db.catalog().get_collection_generator("album_canon").await.unwrap();
        assert!(gen_state.enabled);
        assert_eq!(gen_state.total_pool, 3);
        assert_eq!(gen_state.consumed_count, 2);
        assert_eq!(gen_state.remaining_count, 1);
        assert_eq!(gen_state.current_entry.as_ref().unwrap().title, "Elvis Presley");

        let ranked = crate::service::CatalogService::new(&db)
            .get_ranked_collection("album_canon", false)
            .await
            .unwrap();
        assert_eq!(ranked.rows.len(), 2);
        assert_eq!(ranked.rows[0].entry.title, "In The Wee Small Hours");
        assert_eq!(ranked.rows[1].entry.title, "Ellington at Newport");
    }

    #[tokio::test]
    async fn test_custom_want_collection_slug_and_id_converge_across_manual_want_and_batch() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let catalog = db.catalog();
        let expected_id = crate::db::deterministic_system_collection_id("want:weekend_watch");

        let manual = catalog
            .create_manual_collection("Weekend Watch", None, CollectionRole::Want, None, None)
            .await
            .unwrap();
        assert_eq!(manual.slug.as_deref(), Some("want:weekend_watch"));
        assert_eq!(manual.id, expected_id);

        let resolved = catalog.get_or_create_want_collection("weekend_watch").await.unwrap();
        assert_eq!(resolved.id, expected_id);
        assert_eq!(resolved.slug.as_deref(), Some("want:weekend_watch"));

        let prefixed = catalog
            .create_manual_collection(
                "Autumn Queue",
                Some("WANT:Autumn Queue"),
                CollectionRole::Want,
                None,
                None,
            )
            .await
            .unwrap();
        assert_eq!(prefixed.slug.as_deref(), Some("want:autumn_queue"));
        assert_eq!(prefixed.id, crate::db::deterministic_system_collection_id("want:autumn_queue"));

        let empty_err = catalog
            .create_manual_collection("Empty Want", Some("WANT:"), CollectionRole::Want, None, None)
            .await
            .unwrap_err();
        assert!(matches!(empty_err, CatalogError::InvalidInput(_)));

        let batch_db = Db::connect("sqlite::memory:").await.unwrap();
        let batch = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                ref_key: Some("col:ww".to_owned()),
                id: None,
                title: "Weekend Watch".to_owned(),
                slug: Some("want:weekend_watch".to_owned()),
                role: CollectionRole::Want,
                summary: None,
                color: None,
                members: Vec::new(),
                replace_members: false,
            }],
            ..Default::default()
        };
        batch_db.catalog().apply_batch(&batch).await.unwrap();
        let batched =
            batch_db.catalog().get_collection_summary("want:weekend_watch").await.unwrap();
        assert_eq!(batched.id, expected_id);
        assert_eq!(batched.slug.as_deref(), Some("want:weekend_watch"));

        let custom_id_batch = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                ref_key: None,
                id: Some("explicit-want-id".to_owned()),
                title: "Explicit Queue".to_owned(),
                slug: Some("want:explicit_queue".to_owned()),
                role: CollectionRole::Want,
                summary: None,
                color: None,
                members: Vec::new(),
                replace_members: false,
            }],
            ..Default::default()
        };
        batch_db.catalog().apply_batch(&custom_id_batch).await.unwrap();
        let explicit =
            batch_db.catalog().get_collection_summary("want:explicit_queue").await.unwrap();
        assert_eq!(explicit.id, "explicit-want-id");
        assert_eq!(explicit.slug.as_deref(), Some("want:explicit_queue"));
    }

    #[tokio::test]
    async fn test_ndjson_streaming_cross_line_refs_starred_added_at_replace_and_idempotency() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let catalog = db.catalog();

        // Line 1: Album with Spotify URI; Line 2: Songs linking to album via `spotify:album:...`;
        // Line 3: Attach songs to protected `starred` with explicit `added_at` + create `saved_albums`
        // + create manual playlist; Line 4: Log song plays using `spotify:track:...` refs!
        let ndjson_payload = [
            r#"{"entries":[{"kind":"album","title":"In Rainbows","creators":["Radiohead"],"identifiers":[{"scheme":"spotify","value":"spotify:album:5vkqYmiPBYLaalcmjujWxK"}]}]}"#,
            r#"{"entries":[{"kind":"song","title":"15 Step","creators":["Radiohead"],"identifiers":[{"scheme":"spotify","value":"spotify:track:6dsq7oc5tQr品15step"}],"parent_collection_id":"spotify:album:5vkqYmiPBYLaalcmjujWxK","parent_position":1},{"kind":"song","title":"Weird Fishes/Arpeggi","creators":["Radiohead"],"identifiers":[{"scheme":"spotify","value":"spotify:track:4wajJ1o7jWIg62YqpkHC7S"}],"parent_collection_id":"spotify:album:5vkqYmiPBYLaalcmjujWxK","parent_position":4}]}"#,
            r#"{"collections":[{"slug":"starred","members":[{"entry_ref":"spotify:track:4wajJ1o7jWIg62YqpkHC7S","added_at":"2024-03-15T18:30:00Z"}]},{"title":"Saved Albums","slug":"saved_albums","members":[{"entry_ref":"spotify:album:5vkqYmiPBYLaalcmjujWxK","added_at":"2024-01-10T12:00:00Z"}]},{"title":"Late Night","slug":"late_night","members":[{"entry_ref":"spotify:track:6dsq7oc5tQr品15step","position":1},{"entry_ref":"spotify:track:4wajJ1o7jWIg62YqpkHC7S","position":2}]}]}"#,
            r#"{"logs":[{"entry_ref":"spotify:track:4wajJ1o7jWIg62YqpkHC7S","status":"consumed","ts":"2024-05-01T21:15:00Z"},{"entry_ref":"spotify:track:4wajJ1o7jWIg62YqpkHC7S","status":"consumed","ts":"2024-05-02T22:00:00Z"}]}"#,
        ]
        .join("\n\n");

        // 1. Dry-run validates cross-line references in ONE rolled-back tx without modifying DB!
        let mut dry_callbacks = 0usize;
        let dry_res = catalog
            .apply_batch_ndjson(ndjson_payload.as_bytes(), true, |_, _| dry_callbacks += 1)
            .await
            .unwrap();
        assert!(dry_res.dry_run);
        assert_eq!(dry_res.batches_processed, 4);
        assert_eq!(dry_callbacks, 4);
        assert_eq!(dry_res.entries_processed, 3);
        assert_eq!(dry_res.collections_processed, 3);
        assert_eq!(dry_res.logs_recorded, 2);
        assert!(catalog.get_collection_summary("saved_albums").await.is_err());

        // 2. Live run commits each line cleanly
        let live_res =
            catalog.apply_batch_ndjson(ndjson_payload.as_bytes(), false, |_, _| {}).await.unwrap();
        assert!(!live_res.dry_run);
        assert_eq!(live_res.batches_processed, 4);

        // Verify album -> song parent linkage
        let album =
            catalog.resolve_entry("In Rainbows", Some(EntryKind::Album), None).await.unwrap();
        let album_detail = catalog.get_entry_detail(&album.id).await.unwrap().unwrap();
        assert_eq!(album_detail.children.len(), 2);
        assert_eq!(album_detail.children[0].title, "15 Step");
        assert_eq!(album_detail.children[1].title, "Weird Fishes/Arpeggi");

        // Verify `starred` kept its protected spec and gained the song with exact `added_at`
        let starred_summary = catalog.get_collection_summary("starred").await.unwrap();
        assert_eq!(starred_summary.id, STARRED_COLLECTION_ID);
        assert_eq!(starred_summary.title, "Starred");
        assert_eq!(starred_summary.member_count, 1);
        let song = catalog
            .resolve_entry("Weird Fishes/Arpeggi", Some(EntryKind::Song), None)
            .await
            .unwrap();
        let stored_added_at: chrono::DateTime<chrono::Utc> = sqlx::query_scalar(
            "SELECT added_at FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
        )
        .bind(STARRED_COLLECTION_ID)
        .bind(&song.id)
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(stored_added_at, chrono::Utc.with_ymd_and_hms(2024, 3, 15, 18, 30, 0).unwrap());

        // 3. Idempotent re-run must NOT bump sync_seq on entries, collection_entries, or logs!
        let seq_before: i64 = sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
            .fetch_one(db.pool())
            .await
            .unwrap();
        let activity_before: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM activity_log")
            .fetch_one(db.pool())
            .await
            .unwrap();

        let rerun_res =
            catalog.apply_batch_ndjson(ndjson_payload.as_bytes(), false, |_, _| {}).await.unwrap();
        assert_eq!(rerun_res.batches_processed, 4);

        let seq_after: i64 = sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
            .fetch_one(db.pool())
            .await
            .unwrap();
        let activity_after: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM activity_log")
            .fetch_one(db.pool())
            .await
            .unwrap();
        assert_eq!(seq_before, seq_after, "idempotent re-import must not churn sync_seq");
        assert_eq!(
            activity_before, activity_after,
            "idempotent re-import must not spam activity_log"
        );

        // 4. `replace_members: true` on user playlist removes unlisted tracks and records tombstone
        let replace_batch = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                slug: Some("late_night".to_owned()),
                members: vec![BatchCollectionMemberInput {
                    entry_ref: "spotify:track:4wajJ1o7jWIg62YqpkHC7S".to_owned(),
                    position: Some(1.0),
                    added_at: None,
                }],
                replace_members: true,
                ..Default::default()
            }],
            ..Default::default()
        };
        catalog.apply_batch(&replace_batch).await.unwrap();
        let late_night = catalog.get_collection_summary("late_night").await.unwrap();
        assert_eq!(late_night.title, "Late Night");
        assert_eq!(late_night.member_count, 1);

        let tomb_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sync_tombstones WHERE entity_type = 'collection_entry'",
        )
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(tomb_count, 1);

        // 5. `replace_members: true` on protected `starred` is rejected with Conflict
        let wipe_starred = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                slug: Some("starred".to_owned()),
                replace_members: true,
                ..Default::default()
            }],
            ..Default::default()
        };
        assert!(matches!(catalog.apply_batch(&wipe_starred).await, Err(CatalogError::Conflict(_))));

        // 6. Dynamic system collections remain immutable in batch
        let mutate_dynamic = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                slug: Some("songs".to_owned()),
                title: "Songs".to_owned(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert!(matches!(
            catalog.apply_batch(&mutate_dynamic).await,
            Err(CatalogError::Conflict(_))
        ));

        // 7. Protected system collections reject title-only hijacking and metadata mutation
        let hijack_starred = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                title: "Starred".to_owned(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert!(matches!(
            catalog.apply_batch(&hijack_starred).await,
            Err(CatalogError::Conflict(_))
        ));
        let mutate_starred = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                slug: Some("starred".to_owned()),
                title: "Hacked".to_owned(),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert!(matches!(
            catalog.apply_batch(&mutate_starred).await,
            Err(CatalogError::Conflict(_))
        ));
        let bad_color_starred = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                slug: Some("starred".to_owned()),
                color: Some("not-a-color".to_owned()),
                ..Default::default()
            }],
            ..Default::default()
        };
        assert!(matches!(
            catalog.apply_batch(&bad_color_starred).await,
            Err(CatalogError::InvalidInput(_))
        ));

        // 8. Duplicate members, -0.0 positions, uppercase SPOTIFY:, hyphenated slugs, and note edits
        let dup_batch = CatalogBatchInput {
            collections: vec![BatchCollectionInput {
                slug: Some("late-night".to_owned()),
                members: vec![
                    BatchCollectionMemberInput {
                        entry_ref: "SPOTIFY:track:4wajJ1o7jWIg62YqpkHC7S".to_owned(),
                        position: Some(-0.0),
                        added_at: None,
                    },
                    BatchCollectionMemberInput {
                        entry_ref: "spotify:track:4wajJ1o7jWIg62YqpkHC7S".to_owned(),
                        position: Some(1.0),
                        added_at: None,
                    },
                ],
                ..Default::default()
            }],
            logs: vec![BatchLogInput {
                entry_ref: "SPOTIFY:track:4wajJ1o7jWIg62YqpkHC7S".to_owned(),
                status: ConsumptionStatus::Consumed,
                notes: Some("Updated live note".to_owned()),
                ts: Some(chrono::Utc.with_ymd_and_hms(2024, 5, 1, 21, 15, 0).unwrap()),
            }],
            ..Default::default()
        };
        catalog.apply_batch(&dup_batch).await.unwrap();
        let seq_after_dup1: i64 =
            sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
                .fetch_one(db.pool())
                .await
                .unwrap();
        catalog.apply_batch(&dup_batch).await.unwrap();
        let seq_after_dup2: i64 =
            sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(seq_after_dup1, seq_after_dup2, "duplicate members must not bounce sync_seq");

        let updated_note: Option<String> = sqlx::query_scalar(
            "SELECT notes FROM consumption_log WHERE entry_id = ? AND notes IS NOT NULL LIMIT 1",
        )
        .bind(&song.id)
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(updated_note.as_deref(), Some("Updated live note"));

        // 9. Parent collection slug resolution does not poison entry_ref cache across NDJSON lines
        let bad_ndjson = [
            r#"{"collections":[{"title":"Jazz","slug":"jazz"}],"entries":[{"kind":"album","title":"Kind of Blue","parent_collection_id":"jazz"}]}"#,
            r#"{"logs":[{"entry_ref":"jazz","status":"consumed"}]}"#,
        ]
        .join("\n");
        let dry_err =
            catalog.apply_batch_ndjson(bad_ndjson.as_bytes(), true, |_, _| {}).await.unwrap_err();
        assert!(matches!(dry_err, CatalogError::NotFound(_)));
    }
}
