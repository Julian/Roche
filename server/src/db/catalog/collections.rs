use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use roche_core::domain::{is_want_to_read_base_slug, parse_dynamic_collection_rule};

use super::{
    CatalogRepo,
    models::{
        CatalogEntry, CatalogError, CatalogResult, CollectionSummary, DynamicCollectionRule,
        RawCollectionRow, RawEntryRow, UpsertEntryInput, WantSearchMatch, raw_collection_columns,
        raw_entry_columns,
    },
};
use crate::db::{
    WriteTx,
    entries_core::{self, EntryInsertParams},
    sync::{SyncRepo, collection_entry_natural_key, deterministic_system_collection_id},
    types::{ActivityAction, ActivityEntityType, CollectionRole, EntryKind, HexColor},
};

impl CatalogRepo<'_> {
    #[allow(clippy::too_many_lines)]
    pub async fn list_collections(
        &self,
        include_want: bool,
        include_system: bool,
    ) -> CatalogResult<Vec<CollectionSummary>> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let mut qb = QueryBuilder::<Sqlite>::new(concat!(
            "SELECT ",
            raw_collection_columns!(),
            " FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL AND cs.collection_role != 'tag' AND cs.collection_role != 'feed'"
        ));
        if !include_want {
            qb.push(" AND cs.collection_role != 'want'");
        }
        if !include_system {
            qb.push(" AND cs.collection_role IN ('list', 'want')");
        }
        qb.push(" ORDER BY cs.collection_role ASC, LOWER(e.title) ASC");
        let rows: Vec<RawCollectionRow> = qb.build_query_as().fetch_all(tx.conn()).await?;
        if rows.is_empty() {
            tx.commit().await?;
            return Ok(Vec::new());
        }

        // Load global quality orderings ONCE across all collections instead of N times.
        let global_orderings = Self::load_orderings_for_scope_in_tx(tx.conn(), None).await?;
        let mut global_comp_indices_by_entry: HashMap<String, Vec<usize>> = HashMap::new();
        for (comp_idx, ord) in global_orderings.iter().enumerate() {
            let mut seen_in_comp = HashSet::new();
            for id in ord.iter().flat_map(|g| g.iter()) {
                if seen_in_comp.insert(id.as_str()) {
                    global_comp_indices_by_entry.entry(id.clone()).or_default().push(comp_idx);
                }
            }
        }

        // Load member counts via SQL `GROUP BY` aggregates instead of materializing tens of
        // thousands of entry IDs (e.g. 30k songs + 5k starred items) into Rust RAM.
        let consumed_counts_by_kind = Self::count_consumed_entries_by_kind_in_tx(tx.conn()).await?;
        let manual_non_gen_ids: Vec<&str> = rows
            .iter()
            .filter(|r| !r.is_dynamic() && !r.has_generator)
            .map(|r| r.id.as_str())
            .collect();
        let manual_gen_ids: Vec<&str> = rows
            .iter()
            .filter(|r| !r.is_dynamic() && r.has_generator)
            .map(|r| r.id.as_str())
            .collect();
        let mut manual_counts =
            Self::count_manual_members_by_col_in_tx(tx.conn(), &manual_non_gen_ids, false).await?;
        manual_counts.extend(
            Self::count_manual_members_by_col_in_tx(tx.conn(), &manual_gen_ids, true).await?,
        );

        // Load metadata + memberships ONLY for entries that actually appear in global comparisons
        // (typically dozens/hundreds, never 30k uncompared songs) so `comparison_count` is exact.
        let global_compared_ids: Vec<String> =
            global_comp_indices_by_entry.keys().cloned().collect();
        let (compared_entry_meta, compared_statuses, compared_manual_cols) =
            Self::load_compared_entry_memberships_in_tx(tx.conn(), &global_compared_ids).await?;

        // Batch-load scoped comparisons for all Want collections in a single query.
        let want_ids: Vec<&str> = rows
            .iter()
            .filter(|r| r.collection_role == CollectionRole::Want)
            .map(|r| r.id.as_str())
            .collect();
        let mut want_orderings_by_col: HashMap<String, Vec<Vec<Vec<String>>>> = HashMap::new();
        let mut want_compared_ids: HashSet<String> = HashSet::new();
        for chunk in want_ids.chunks(450) {
            let mut cqb = QueryBuilder::<Sqlite>::new(
                "SELECT scope_collection_id, ordering_json
                 FROM comparisons
                 WHERE scope_collection_id IN (",
            );
            let mut sep = cqb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(")");
            let comp_rows: Vec<(String, String)> =
                cqb.build_query_as().fetch_all(tx.conn()).await?;
            for (cid, json_str) in comp_rows {
                if let Ok(ord) = serde_json::from_str::<Vec<Vec<String>>>(&json_str) {
                    for id in ord.iter().flat_map(|g| g.iter()) {
                        want_compared_ids.insert(id.clone());
                    }
                    want_orderings_by_col.entry(cid).or_default().push(ord);
                }
            }
        }
        let want_compared_vec: Vec<String> = want_compared_ids.into_iter().collect();
        let want_member_pairs =
            Self::load_active_membership_pairs_for_entries_in_tx(tx.conn(), &want_compared_vec)
                .await?;

        let mut out = Vec::with_capacity(rows.len());
        for raw in rows {
            let member_count = if raw.is_dynamic() {
                if let Some(simple_kind) =
                    extract_simple_consumed_kind_rule(raw.rule_json.as_deref())
                {
                    consumed_counts_by_kind.get(&simple_kind).copied().unwrap_or(0)
                } else {
                    Self::count_dynamic_members_in_tx(tx.conn(), &raw).await?
                }
            } else {
                manual_counts.get(&raw.id).copied().unwrap_or(0)
            };

            let comp_count = if member_count == 0 {
                0
            } else if raw.collection_role == CollectionRole::Want {
                want_orderings_by_col.get(&raw.id).map_or(0, |scoped| {
                    scoped
                        .iter()
                        .filter(|ord| {
                            ord.iter()
                                .flat_map(|g| g.iter())
                                .any(|id| want_member_pairs.contains(&(raw.id.clone(), id.clone())))
                        })
                        .count()
                })
            } else if raw.is_dynamic() {
                let mut matched_comps: HashSet<usize> = HashSet::new();
                if let Some(rule) = parse_dynamic_collection_rule(raw.rule_json.as_deref()) {
                    for (eid, (kind, creators, release_year)) in &compared_entry_meta {
                        let statuses = compared_statuses.get(eid).map_or(&[][..], Vec::as_slice);
                        if roche_core::domain::matches_dynamic_collection_rule(
                            &rule,
                            *kind,
                            creators,
                            *release_year,
                            statuses,
                        ) && let Some(indices) = global_comp_indices_by_entry.get(eid)
                        {
                            matched_comps.extend(indices.iter().copied());
                        }
                    }
                }
                matched_comps.len()
            } else {
                let mut matched_comps: HashSet<usize> = HashSet::new();
                if let Some(eids) = compared_manual_cols.get(&raw.id) {
                    for eid in eids {
                        let eligible = !raw.has_generator
                            || compared_statuses.get(eid).is_some_and(|st| {
                                st.contains(&crate::db::types::ConsumptionStatus::Consumed)
                            });
                        if eligible && let Some(indices) = global_comp_indices_by_entry.get(eid) {
                            matched_comps.extend(indices.iter().copied());
                        }
                    }
                }
                matched_comps.len()
            };

            out.push(raw.into_summary(member_count, comp_count));
        }
        tx.commit().await?;
        Ok(out)
    }

    pub async fn create_manual_collection(
        &self,
        title: &str,
        slug: Option<&str>,
        role: CollectionRole,
        summary: Option<&str>,
        color: Option<&str>,
    ) -> CatalogResult<CollectionSummary> {
        let (clean_title, derived_slug, id) =
            derive_manual_collection_slug_and_id(title, slug, role)?;
        let clean_title = clean_title.as_str();
        let now = crate::util::clock::now_utc();
        let clean_summary = summary.map(str::trim).filter(|s| !s.is_empty());
        let validated_color = HexColor::parse_optional(color)
            .map_err(|e| CatalogError::InvalidInput(e.to_string()))?;
        let clean_color = validated_color.as_ref().map(HexColor::as_str);

        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let existing = sqlx::query_scalar!(
            r#"SELECT collection_id as "collection_id!" FROM collection_specs WHERE slug = ?"#,
            &derived_slug,
        )
        .fetch_optional(tx.conn())
        .await?;
        if existing.is_some() {
            return Err(CatalogError::Conflict(format!(
                "Collection with slug '{derived_slug}' already exists"
            )));
        }
        entries_core::insert_entry_row_in_tx(
            &mut tx,
            &EntryInsertParams {
                summary: clean_summary,
                ..EntryInsertParams::minimal(&id, EntryKind::Collection, clean_title, now, now)
            },
        )
        .await?;

        sqlx::query!(
            "INSERT INTO collection_specs
             (collection_id, slug, collection_role, rule_json, color)
             VALUES (?, ?, ?, NULL, ?)",
            &id,
            &derived_slug,
            role as _,
            clean_color,
        )
        .execute(tx.conn())
        .await?;

        entries_core::upsert_entry_identifier_in_tx(tx.conn(), &id, "slug", &derived_slug).await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            &id,
            ActivityAction::Create,
            now,
        )
        .await?;

        tx.commit().await?;
        self.get_collection_summary(&id).await
    }

    pub async fn get_collection_summary(
        &self,
        slug_or_id: &str,
    ) -> CatalogResult<CollectionSummary> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let raw = Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, false).await?;
        let summary = Self::summarize_collection_row_in_tx(tx.conn(), raw).await?;
        tx.commit().await?;
        Ok(summary)
    }

    /// Resolves or deterministically creates the `Want` queue collection for `target`
    /// (e.g. `"movie"` / `"movies"` -> `"want:movies"`, or an existing `want` collection).
    pub async fn get_or_create_want_collection(
        &self,
        target: &str,
    ) -> CatalogResult<CollectionSummary> {
        let raw = self.ensure_want_collection_row(target).await?;
        let mut tx = crate::db::begin_read(self.pool).await?;
        let summary = Self::summarize_collection_row_in_tx(tx.conn(), raw).await?;
        tx.commit().await?;
        Ok(summary)
    }

    /// Read-only lookup of an existing `Want` queue collection for `target` without creating rows.
    pub(super) async fn lookup_want_collection_row_in_tx(
        conn: &mut SqliteConnection,
        target: &str,
    ) -> CatalogResult<Option<RawCollectionRow>> {
        let trimmed = target.trim();
        if let Ok(existing) = Self::lookup_collection_row_in_tx(&mut *conn, trimmed, true).await
            && existing.collection_role == CollectionRole::Want
        {
            return Ok(Some(existing));
        }

        let (want_slug, _) = derive_want_collection_slug_and_label(trimmed)?;
        match Self::lookup_collection_row_in_tx(&mut *conn, &want_slug, false).await {
            Ok(existing) => Ok(Some(existing)),
            Err(CatalogError::NotFound(_)) => Ok(None),
            Err(other) => Err(other),
        }
    }

    pub(super) async fn ensure_want_collection_row(
        &self,
        target: &str,
    ) -> CatalogResult<RawCollectionRow> {
        let trimmed = target.trim();
        if let Ok(existing) = self.lookup_collection_row(trimmed, true).await
            && existing.collection_role == CollectionRole::Want
        {
            return Ok(existing);
        }

        let (want_slug, _) = derive_want_collection_slug_and_label(trimmed)?;
        if let Ok(existing) = self.lookup_collection_row(&want_slug, false).await {
            return Ok(existing);
        }

        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let raw = Self::ensure_want_collection_in_tx(&mut tx, trimmed, now).await?;
        tx.commit().await?;
        Ok(raw)
    }

    pub(super) async fn ensure_want_collection_in_tx(
        tx: &mut WriteTx<'_>,
        target: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<RawCollectionRow> {
        let trimmed = target.trim();
        if let Ok(existing) = Self::lookup_collection_row_in_tx(tx.conn(), trimmed, true).await
            && existing.collection_role == CollectionRole::Want
        {
            return Ok(existing);
        }

        let (want_slug, pretty_label) = derive_want_collection_slug_and_label(trimmed)?;
        if let Ok(existing) = Self::lookup_collection_row_in_tx(tx.conn(), &want_slug, false).await
        {
            return Ok(existing);
        }

        let id = deterministic_system_collection_id(&want_slug);
        let inserted = entries_core::insert_entry_row_or_ignore_in_tx(
            tx,
            &EntryInsertParams::minimal(&id, EntryKind::Collection, &pretty_label, now, now),
        )
        .await?;
        sqlx::query!(
            "INSERT OR IGNORE INTO collection_specs
             (collection_id, slug, collection_role, rule_json)
             VALUES (?, ?, 'want', NULL)",
            &id,
            &want_slug,
        )
        .execute(tx.conn())
        .await?;
        entries_core::upsert_entry_identifier_in_tx(tx.conn(), &id, "slug", &want_slug).await?;
        if inserted {
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Entry,
                &id,
                ActivityAction::Create,
                now,
            )
            .await?;
        }

        Self::lookup_collection_row_in_tx(tx.conn(), &id, false).await
    }

    pub(super) async fn attach_collection_member_in_tx(
        tx: &mut WriteTx<'_>,
        col_id: &str,
        member_id: &str,
        position: Option<f64>,
        now: DateTime<Utc>,
        touch_parent_entry: bool,
    ) -> CatalogResult<()> {
        if col_id == member_id {
            return Err(CatalogError::InvalidInput(
                "A collection cannot contain itself".to_owned(),
            ));
        }
        if Self::would_introduce_collection_cycle_in_tx(tx.conn(), col_id, member_id).await? {
            return Err(CatalogError::InvalidInput(
                "Adding entry to collection would create a cycle".to_owned(),
            ));
        }

        let nat_key = collection_entry_natural_key(col_id, member_id);
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::CollectionEntry, &nat_key).await?;

        let ce_hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT INTO collection_entries (collection_id, entry_id, position, added_at, hlc)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                position = COALESCE(excluded.position, collection_entries.position),
                hlc = CASE
                    WHEN excluded.position IS NOT NULL
                      AND excluded.position IS NOT collection_entries.position
                    THEN excluded.hlc
                    ELSE collection_entries.hlc
                END",
        )
        .bind(col_id)
        .bind(member_id)
        .bind(position)
        .bind(now)
        .bind(&ce_hlc)
        .execute(tx.conn())
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::CollectionEntry,
            &nat_key,
            ActivityAction::Create,
            now,
        )
        .await?;

        if touch_parent_entry {
            sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, col_id,)
                .execute(tx.conn())
                .await?;
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Entry,
                col_id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }
        Ok(())
    }

    /// Resolves a collection by slug or ID, verifies it is manually mutable, and attaches
    /// `entry_id` inside an existing write transaction.
    pub(crate) async fn add_entry_to_manual_collection_in_tx(
        tx: &mut WriteTx<'_>,
        slug_or_id: &str,
        entry_id: &str,
        position: Option<f64>,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        let col = Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, false).await?;
        if let Some(reason) = col.immutable_reason() {
            return Err(CatalogError::ImmutableCollection { title: col.title, reason });
        }
        Self::attach_collection_member_in_tx(tx, &col.id, entry_id, position, now, true).await
    }

    /// Manually adds an entry to a collection, strictly rejecting `feed` and `dynamic` collections
    /// as well as self-containment and ancestor cycles.
    pub async fn add_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
        position: Option<f64>,
    ) -> CatalogResult<()> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let target_entry_id = Self::follow_merged_id_in_tx(tx.conn(), entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))?;
        Self::add_entry_to_manual_collection_in_tx(
            &mut tx,
            slug_or_id,
            &target_entry_id,
            position,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(())
    }

    /// Manually removes an entry from a collection, strictly rejecting `feed` and `dynamic` collections.
    pub async fn remove_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
    ) -> CatalogResult<bool> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let col = Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, false).await?;
        if let Some(reason) = col.immutable_reason() {
            return Err(CatalogError::ImmutableCollection { title: col.title, reason });
        }
        let target_entry_id = Self::follow_merged_id_in_tx(tx.conn(), entry_id)
            .await?
            .unwrap_or_else(|| entry_id.trim().to_owned());
        let res = sqlx::query!(
            "DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
            &col.id,
            &target_entry_id,
        )
        .execute(tx.conn())
        .await?;
        let removed = res.rows_affected() > 0;
        if removed {
            Self::advance_generator_if_drawing_in_tx(&mut tx, &col.id, &target_entry_id, now)
                .await?;
            let nat_key = collection_entry_natural_key(&col.id, &target_entry_id);
            SyncRepo::record_tombstone(&mut tx, ActivityEntityType::CollectionEntry, &nat_key, now)
                .await?;
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::CollectionEntry,
                &nat_key,
                ActivityAction::Delete,
                now,
            )
            .await?;
            sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, &col.id,)
                .execute(tx.conn())
                .await?;
            SyncRepo::record_activity(
                tx.conn(),
                ActivityEntityType::Entry,
                &col.id,
                ActivityAction::Update,
                now,
            )
            .await?;
        }
        tx.commit().await?;
        Ok(removed)
    }

    pub async fn want_entry(
        &self,
        input: &UpsertEntryInput,
        target_queue: Option<&str>,
    ) -> CatalogResult<(CollectionSummary, CatalogEntry)> {
        self.want_resolved_or_upsert(input, None, target_queue, false).await
    }

    /// Atomically resolves (when `try_resolve_existing` is `true`) or upserts an entry,
    /// ensures the target `Want` queue exists, and attaches the entry to that queue in a
    /// single SQLite write transaction.
    pub async fn want_resolved_or_upsert(
        &self,
        input: &UpsertEntryInput,
        notes: Option<&str>,
        target_queue: Option<&str>,
        try_resolve_existing: bool,
    ) -> CatalogResult<(CollectionSummary, CatalogEntry)> {
        let clean_notes = notes.map(str::trim).filter(|s| !s.is_empty());
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        let resolved_existing = if try_resolve_existing {
            Self::resolve_entry_raw_in_tx(tx.conn(), &input.title, input.kind, None).await.ok()
        } else {
            None
        };

        let (upsert_input, kind, title) = if let Some(existing) = resolved_existing {
            let metadata =
                clean_notes.map(|note_text| with_want_notes(input.metadata.clone(), note_text));
            let resolved_kind = existing.kind;
            let resolved_title = existing.title;
            let resolved_in = UpsertEntryInput {
                id: Some(existing.id),
                kind: Some(resolved_kind),
                title: resolved_title.clone(),
                metadata,
                ..UpsertEntryInput::default()
            };
            (resolved_in, resolved_kind, resolved_title)
        } else {
            let kind = input
                .kind
                .ok_or_else(|| CatalogError::InvalidInput("Entry kind is required".to_owned()))?;
            let title = input.title.trim();
            if title.is_empty() {
                return Err(CatalogError::InvalidInput("Entry title cannot be empty".to_owned()));
            }
            let mut enriched = input.clone();
            if let Some(note_text) = clean_notes {
                enriched.metadata = Some(with_want_notes(enriched.metadata.take(), note_text));
            }
            (enriched, kind, title.to_owned())
        };

        let entry_id = Self::upsert_entry_in_tx(&mut tx, &upsert_input, kind, &title, now).await?;
        let persisted_kind: EntryKind = sqlx::query_scalar("SELECT kind FROM entries WHERE id = ?")
            .bind(&entry_id)
            .fetch_one(tx.conn())
            .await?;
        let queue_key = match target_queue.map(str::trim).filter(|s| !s.is_empty()) {
            Some(explicit) => explicit.to_owned(),
            None => persisted_kind
                .default_want_slug()
                .ok_or_else(|| {
                    CatalogError::InvalidInput(format!(
                        "Specify a target want queue for kind '{}'",
                        persisted_kind.as_str()
                    ))
                })?
                .to_owned(),
        };
        let queue = Self::ensure_want_collection_in_tx(&mut tx, &queue_key, now).await?;
        Self::attach_collection_member_in_tx(&mut tx, &queue.id, &entry_id, None, now, true)
            .await?;
        tx.commit().await?;

        let refreshed_queue = self.get_collection_summary(&queue.id).await?;
        let entry = self
            .get_entry(&entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))?;
        Ok((refreshed_queue, entry))
    }

    pub async fn unwant_entry(
        &self,
        target_queue: &str,
        query_or_id: &str,
    ) -> CatalogResult<CatalogEntry> {
        let queue = self.ensure_want_collection_row(target_queue).await?;
        let entry = self.resolve_entry(query_or_id, None, Some(&queue.id)).await?;
        self.remove_collection_entry(&queue.id, &entry.id).await?;
        Ok(entry)
    }

    pub(crate) async fn would_introduce_collection_cycle_in_tx(
        conn: &mut SqliteConnection,
        parent_col_id: &str,
        child_entry_id: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        const MAX_ANCESTOR_DEPTH: i64 = 128;
        if parent_col_id == child_entry_id {
            return Ok(true);
        }
        let blocked: i64 = sqlx::query_scalar(
            "WITH RECURSIVE ancestors(id, depth) AS (
                 SELECT ?, 0
                 UNION
                 SELECT ce.collection_id, a.depth + 1
                 FROM collection_entries ce
                 JOIN ancestors a ON ce.entry_id = a.id
                 WHERE a.depth < ?
             )
             SELECT EXISTS(
                 SELECT 1 FROM ancestors WHERE id = ? OR depth >= ?
             )",
        )
        .bind(parent_col_id)
        .bind(MAX_ANCESTOR_DEPTH)
        .bind(child_entry_id)
        .bind(MAX_ANCESTOR_DEPTH)
        .fetch_one(&mut *conn)
        .await?;
        Ok(blocked != 0)
    }

    pub(super) async fn lookup_collection_row(
        &self,
        slug_or_id: &str,
        allow_kind_alias: bool,
    ) -> CatalogResult<RawCollectionRow> {
        let mut conn = self.pool.acquire().await?;
        Self::lookup_collection_row_in_tx(&mut conn, slug_or_id, allow_kind_alias).await
    }

    pub(super) async fn lookup_collection_row_in_tx(
        conn: &mut SqliteConnection,
        slug_or_id: &str,
        allow_kind_alias: bool,
    ) -> CatalogResult<RawCollectionRow> {
        let q = slug_or_id.trim();
        let norm_slug = q.to_ascii_lowercase().replace('-', "_");
        if let Some(row) = sqlx::query_as::<_, RawCollectionRow>(concat!(
            "SELECT ",
            raw_collection_columns!(),
            " FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL
               AND (e.id = ? OR LOWER(cs.slug) = LOWER(?) OR LOWER(cs.slug) = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1"
        ))
        .bind(q)
        .bind(q)
        .bind(&norm_slug)
        .bind(q)
        .fetch_optional(&mut *conn)
        .await?
        {
            return Ok(row);
        }

        if allow_kind_alias
            && let Ok(kind) = EntryKind::parse_flexible(q)
            && let Some(default_slug) = kind.default_list_slug()
            && let Some(row) = sqlx::query_as::<_, RawCollectionRow>(concat!(
                "SELECT ",
                raw_collection_columns!(),
                " FROM collection_specs cs
                 JOIN entries e ON e.id = cs.collection_id
                 WHERE e.merged_into_id IS NULL AND cs.slug = ?
                 LIMIT 1"
            ))
            .bind(default_slug)
            .fetch_optional(&mut *conn)
            .await?
        {
            return Ok(row);
        }

        Err(CatalogError::NotFound(format!("Collection '{q}'")))
    }

    pub(super) async fn summarize_collection_row_in_tx(
        conn: &mut SqliteConnection,
        raw: RawCollectionRow,
    ) -> CatalogResult<CollectionSummary> {
        let member_ids = Self::resolve_rankable_member_ids_in_tx(&mut *conn, &raw).await?;
        let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
        let is_priority = raw.collection_role == CollectionRole::Want;
        let comp_count = if member_set.is_empty() {
            0
        } else {
            let orderings = Self::load_orderings_for_scope_in_tx(
                &mut *conn,
                if is_priority { Some(raw.id.as_str()) } else { None },
            )
            .await?;
            orderings
                .iter()
                .filter(|ord| {
                    ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
                })
                .count()
        };

        Ok(raw.into_summary(member_ids.len(), comp_count))
    }

    pub(super) async fn resolve_rankable_member_ids_in_tx(
        conn: &mut SqliteConnection,
        col: &RawCollectionRow,
    ) -> CatalogResult<Vec<String>> {
        if col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(col.rule_json.as_deref()) else {
                return Ok(Vec::new());
            };
            if let Some(creator_needle) =
                rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
            {
                let mut qb = QueryBuilder::<Sqlite>::new(
                    "SELECT e.id, e.creators_json
                     FROM entries e
                     WHERE e.merged_into_id IS NULL",
                );
                append_dynamic_rule_sql_filters(&mut qb, &rule);
                qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC");
                let rows: Vec<(String, String)> = qb.build_query_as().fetch_all(&mut *conn).await?;
                let lower_needle = creator_needle.to_lowercase();
                Ok(rows
                    .into_iter()
                    .filter_map(|(id, creators_json)| {
                        creators_json_matches_needle(&creators_json, &lower_needle).then_some(id)
                    })
                    .collect())
            } else {
                let mut qb = QueryBuilder::<Sqlite>::new(
                    "SELECT e.id
                     FROM entries e
                     WHERE e.merged_into_id IS NULL",
                );
                append_dynamic_rule_sql_filters(&mut qb, &rule);
                qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC");
                Ok(qb.build_query_scalar().fetch_all(&mut *conn).await?)
            }
        } else if col.has_generator {
            Ok(sqlx::query_scalar!(
                r#"SELECT e.id as "id!"
                   FROM collection_entries ce
                   JOIN entries e ON e.id = ce.entry_id
                   WHERE ce.collection_id = ?
                     AND e.merged_into_id IS NULL
                     AND EXISTS (
                         SELECT 1 FROM consumption_log cl
                         WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                     )
                   ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC"#,
                &col.id,
            )
            .fetch_all(&mut *conn)
            .await?)
        } else {
            Ok(sqlx::query_scalar!(
                r#"SELECT e.id as "id!"
                   FROM collection_entries ce
                   JOIN entries e ON e.id = ce.entry_id
                   WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
                   ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC"#,
                &col.id,
            )
            .fetch_all(&mut *conn)
            .await?)
        }
    }

    pub(super) async fn resolve_rankable_members_in_tx(
        conn: &mut SqliteConnection,
        col: &RawCollectionRow,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        let raw_rows: Vec<RawEntryRow> = if col.is_dynamic() {
            let Some(rule) = parse_dynamic_collection_rule(col.rule_json.as_deref()) else {
                return Ok(Vec::new());
            };
            let mut qb = QueryBuilder::<Sqlite>::new(concat!(
                "SELECT ",
                raw_entry_columns!(e),
                " FROM entries e WHERE e.merged_into_id IS NULL"
            ));
            append_dynamic_rule_sql_filters(&mut qb, &rule);
            qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC");
            let mut fetched: Vec<RawEntryRow> = qb.build_query_as().fetch_all(&mut *conn).await?;
            if let Some(creator_needle) =
                rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
            {
                let lower_needle = creator_needle.to_lowercase();
                fetched.retain(|r| creators_json_matches_needle(&r.creators_json, &lower_needle));
            }
            fetched
        } else {
            let sql = if col.has_generator {
                concat!(
                    "SELECT ",
                    raw_entry_columns!(e),
                    " FROM collection_entries ce
                     JOIN entries e ON e.id = ce.entry_id
                     WHERE ce.collection_id = ?
                       AND e.merged_into_id IS NULL
                       AND EXISTS (
                           SELECT 1 FROM consumption_log cl
                           WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                       )
                     ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC"
                )
            } else {
                concat!(
                    "SELECT ",
                    raw_entry_columns!(e),
                    " FROM collection_entries ce
                     JOIN entries e ON e.id = ce.entry_id
                     WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
                     ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC"
                )
            };
            sqlx::query_as(sql).bind(&col.id).fetch_all(&mut *conn).await?
        };

        Self::hydrate_entries_in_tx(&mut *conn, raw_rows).await
    }

    /// Loads up to `per_collection_limit` `(title, thumbnail_url)` previews for each
    /// non-empty collection in `collections`, honoring generator consumption filters and
    /// dynamic collection rules.
    pub async fn load_collection_preview_thumbnails(
        &self,
        collections: &[CollectionSummary],
        per_collection_limit: usize,
    ) -> CatalogResult<HashMap<String, Vec<(String, String)>>> {
        let mut out: HashMap<String, Vec<(String, String)>> = HashMap::new();
        if per_collection_limit == 0 {
            return Ok(out);
        }
        let mut tx = crate::db::begin_read(self.pool).await?;
        let limit_i64 = i64::try_from(per_collection_limit).unwrap_or(5);
        for col in collections {
            if col.member_count == 0 {
                continue;
            }
            let rows: Vec<(String, String)> = if col.is_dynamic() {
                let Some(rule) = parse_dynamic_collection_rule(col.rule_json.as_deref()) else {
                    continue;
                };
                Self::load_dynamic_preview_thumbnails_in_tx(
                    tx.conn(),
                    &rule,
                    per_collection_limit,
                    limit_i64,
                )
                .await?
            } else {
                let mut qb = QueryBuilder::<Sqlite>::new(
                    "SELECT e.title, e.thumbnail_url
                     FROM collection_entries ce
                     JOIN entries e ON e.id = ce.entry_id
                     WHERE ce.collection_id = ",
                );
                qb.push_bind(&col.id).push(
                    " AND e.merged_into_id IS NULL
                      AND e.thumbnail_url IS NOT NULL
                      AND TRIM(e.thumbnail_url) != ''",
                );
                if col.has_generator {
                    qb.push(
                        " AND EXISTS (
                            SELECT 1 FROM consumption_log cl
                            WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                          )
                          ORDER BY (
                            SELECT MAX(cl.ts) FROM consumption_log cl
                            WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                          ) DESC, e.title ASC LIMIT ",
                    );
                } else {
                    qb.push(
                        " ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC LIMIT ",
                    );
                }
                qb.push_bind(limit_i64);
                qb.build_query_as().fetch_all(tx.conn()).await?
            };
            if !rows.is_empty() {
                out.insert(col.id.clone(), rows);
            }
        }
        tx.commit().await?;
        Ok(out)
    }

    async fn load_dynamic_preview_thumbnails_in_tx(
        conn: &mut SqliteConnection,
        rule: &DynamicCollectionRule,
        per_collection_limit: usize,
        limit_i64: i64,
    ) -> CatalogResult<Vec<(String, String)>> {
        if let Some(creator_needle) =
            rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let lower_needle = creator_needle.to_lowercase();
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT e.title, e.thumbnail_url, e.creators_json
                 FROM entries e
                 WHERE e.merged_into_id IS NULL
                   AND e.kind != 'collection'
                   AND e.thumbnail_url IS NOT NULL
                   AND TRIM(e.thumbnail_url) != ''",
            );
            append_dynamic_rule_sql_filters(&mut qb, rule);
            qb.push(
                " ORDER BY COALESCE(
                    (SELECT MAX(cl.ts) FROM consumption_log cl WHERE cl.entry_id = e.id),
                    e.updated_at
                  ) DESC, e.title ASC",
            );
            let candidates: Vec<(String, String, String)> =
                qb.build_query_as().fetch_all(&mut *conn).await?;
            return Ok(candidates
                .into_iter()
                .filter(|(_, _, creators_json)| {
                    creators_json_matches_needle(creators_json, &lower_needle)
                })
                .take(per_collection_limit)
                .map(|(title, thumb, _)| (title, thumb))
                .collect());
        }

        // Fast indexed path for standard dynamic kind collections using
        // `idx_entries_kind_thumb_updated` so 30k songs stop after 5 rows instead of scanning
        // all `consumption_log` timestamps.
        if rule.kind.is_some()
            && rule.kinds.is_empty()
            && rule.release_year.is_none()
            && rule.status.is_none()
        {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT e.title, e.thumbnail_url
                 FROM entries e
                 WHERE e.merged_into_id IS NULL
                   AND e.kind != 'collection'
                   AND e.thumbnail_url IS NOT NULL
                   AND TRIM(e.thumbnail_url) != ''",
            );
            append_dynamic_rule_sql_filters(&mut qb, rule);
            qb.push(" ORDER BY e.updated_at DESC, e.id ASC LIMIT ").push_bind(limit_i64);
            return Ok(qb.build_query_as().fetch_all(&mut *conn).await?);
        }

        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.title, e.thumbnail_url
             FROM entries e
             WHERE e.merged_into_id IS NULL
               AND e.kind != 'collection'
               AND e.thumbnail_url IS NOT NULL
               AND TRIM(e.thumbnail_url) != ''",
        );
        append_dynamic_rule_sql_filters(&mut qb, rule);
        qb.push(
            " ORDER BY COALESCE(
                (SELECT MAX(cl.ts) FROM consumption_log cl WHERE cl.entry_id = e.id),
                e.updated_at
              ) DESC, e.title ASC LIMIT ",
        )
        .push_bind(limit_i64);
        Ok(qb.build_query_as().fetch_all(&mut *conn).await?)
    }

    /// Returns the most common non-collection [`EntryKind`] in `slug_or_id` via SQL `GROUP BY`
    /// without hydrating the collection.
    pub async fn infer_dominant_kind_in_collection(
        &self,
        slug_or_id: &str,
    ) -> CatalogResult<Option<EntryKind>> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let Ok(raw_col) = Self::lookup_collection_row_in_tx(tx.conn(), slug_or_id, true).await
        else {
            return Ok(None);
        };
        if raw_col.is_dynamic()
            && let Some(rule) = parse_dynamic_collection_rule(raw_col.rule_json.as_deref())
            && let Some(k) = rule.kind.or_else(|| rule.kinds.first().copied())
        {
            tx.commit().await?;
            return Ok(Some(k));
        }
        let kind: Option<EntryKind> = sqlx::query_scalar(
            "SELECT e.kind
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ?
               AND e.merged_into_id IS NULL
               AND e.kind != 'collection'
             GROUP BY e.kind
             ORDER BY COUNT(*) DESC, e.kind ASC
             LIMIT 1",
        )
        .bind(&raw_col.id)
        .fetch_optional(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(kind)
    }

    pub(super) async fn count_consumed_entries_by_kind_in_tx(
        conn: &mut SqliteConnection,
    ) -> CatalogResult<HashMap<EntryKind, usize>> {
        let rows: Vec<(EntryKind, i64)> = sqlx::query_as(
            "SELECT e.kind, COUNT(*)
             FROM entries e
             WHERE e.merged_into_id IS NULL
               AND EXISTS (
                   SELECT 1 FROM consumption_log cl
                   WHERE cl.entry_id = e.id AND cl.status = 'consumed'
               )
             GROUP BY e.kind",
        )
        .fetch_all(&mut *conn)
        .await?;
        Ok(rows.into_iter().map(|(k, c)| (k, usize::try_from(c).unwrap_or(0))).collect())
    }

    async fn count_manual_members_by_col_in_tx(
        conn: &mut SqliteConnection,
        col_ids: &[&str],
        require_consumed: bool,
    ) -> CatalogResult<HashMap<String, usize>> {
        let mut out = HashMap::new();
        for chunk in col_ids.chunks(450) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT ce.collection_id, COUNT(*)
                 FROM collection_entries ce
                 JOIN entries e ON e.id = ce.entry_id
                 WHERE e.merged_into_id IS NULL AND ce.collection_id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(")");
            if require_consumed {
                qb.push(
                    " AND EXISTS (
                        SELECT 1 FROM consumption_log cl
                        WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                      )",
                );
            }
            qb.push(" GROUP BY ce.collection_id");
            let rows: Vec<(String, i64)> = qb.build_query_as().fetch_all(&mut *conn).await?;
            for (cid, cnt) in rows {
                out.insert(cid, usize::try_from(cnt).unwrap_or(0));
            }
        }
        Ok(out)
    }

    pub(super) async fn count_dynamic_members_in_tx(
        conn: &mut SqliteConnection,
        raw: &RawCollectionRow,
    ) -> CatalogResult<usize> {
        let Some(rule) = parse_dynamic_collection_rule(raw.rule_json.as_deref()) else {
            return Ok(0);
        };
        if rule.creator.as_deref().map(str::trim).is_some_and(|s| !s.is_empty()) {
            return Ok(Self::resolve_rankable_member_ids_in_tx(conn, raw).await?.len());
        }
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT COUNT(*) FROM entries e WHERE e.merged_into_id IS NULL",
        );
        append_dynamic_rule_sql_filters(&mut qb, &rule);
        let cnt: i64 = qb.build_query_scalar().fetch_one(&mut *conn).await?;
        Ok(usize::try_from(cnt).unwrap_or(0))
    }

    #[allow(clippy::type_complexity)]
    async fn load_compared_entry_memberships_in_tx(
        conn: &mut SqliteConnection,
        entry_ids: &[String],
    ) -> CatalogResult<(
        HashMap<String, (EntryKind, Vec<String>, Option<i32>)>,
        HashMap<String, Vec<crate::db::types::ConsumptionStatus>>,
        HashMap<String, Vec<String>>,
    )> {
        let mut meta = HashMap::new();
        let mut statuses: HashMap<String, Vec<crate::db::types::ConsumptionStatus>> =
            HashMap::new();
        let mut by_col: HashMap<String, Vec<String>> = HashMap::new();
        if entry_ids.is_empty() {
            return Ok((meta, statuses, by_col));
        }
        for chunk in entry_ids.chunks(450) {
            let mut eqb = QueryBuilder::<Sqlite>::new(
                "SELECT id, kind, creators_json, release_year
                 FROM entries
                 WHERE merged_into_id IS NULL AND id IN (",
            );
            let mut entry_sep = eqb.separated(", ");
            for id in chunk {
                entry_sep.push_bind(id);
            }
            entry_sep.push_unseparated(")");
            let rows: Vec<(String, EntryKind, String, Option<i32>)> =
                eqb.build_query_as().fetch_all(&mut *conn).await?;
            let mut active_chunk_ids = Vec::with_capacity(rows.len());
            for (id, kind, creators_json, release_year) in rows {
                let creators: Vec<String> =
                    serde_json::from_str(&creators_json).unwrap_or_default();
                active_chunk_ids.push(id.clone());
                meta.insert(id, (kind, creators, release_year));
            }
            if active_chunk_ids.is_empty() {
                continue;
            }

            let mut sqb = QueryBuilder::<Sqlite>::new(
                "SELECT DISTINCT entry_id, status FROM consumption_log WHERE entry_id IN (",
            );
            let mut status_sep = sqb.separated(", ");
            for id in &active_chunk_ids {
                status_sep.push_bind(id);
            }
            status_sep.push_unseparated(")");
            let st_rows: Vec<(String, crate::db::types::ConsumptionStatus)> =
                sqb.build_query_as().fetch_all(&mut *conn).await?;
            for (eid, st) in st_rows {
                statuses.entry(eid).or_default().push(st);
            }

            let mut mqb = QueryBuilder::<Sqlite>::new(
                "SELECT collection_id, entry_id FROM collection_entries WHERE entry_id IN (",
            );
            let mut mem_sep = mqb.separated(", ");
            for id in &active_chunk_ids {
                mem_sep.push_bind(id);
            }
            mem_sep.push_unseparated(")");
            let mem_rows: Vec<(String, String)> =
                mqb.build_query_as().fetch_all(&mut *conn).await?;
            for (cid, eid) in mem_rows {
                by_col.entry(cid).or_default().push(eid);
            }
        }
        Ok((meta, statuses, by_col))
    }

    async fn load_active_membership_pairs_for_entries_in_tx(
        conn: &mut SqliteConnection,
        entry_ids: &[String],
    ) -> CatalogResult<HashSet<(String, String)>> {
        let mut pairs = HashSet::new();
        for chunk in entry_ids.chunks(450) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT ce.collection_id, ce.entry_id
                 FROM collection_entries ce
                 JOIN entries e ON e.id = ce.entry_id
                 WHERE e.merged_into_id IS NULL AND ce.entry_id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(")");
            let rows: Vec<(String, String)> = qb.build_query_as().fetch_all(&mut *conn).await?;
            pairs.extend(rows);
        }
        Ok(pairs)
    }
}

fn extract_simple_consumed_kind_rule(rule_json: Option<&str>) -> Option<EntryKind> {
    let rule = parse_dynamic_collection_rule(rule_json)?;
    if rule.consumed == Some(true)
        && rule.kinds.is_empty()
        && rule.status.is_none()
        && rule.release_year.is_none()
        && rule.creator.as_deref().map(str::trim).is_none_or(str::is_empty)
    {
        rule.kind
    } else {
        None
    }
}

pub(super) fn creators_json_matches_needle(creators_json: &str, lower_needle: &str) -> bool {
    let creators: Vec<String> = serde_json::from_str(creators_json).unwrap_or_default();
    creators.iter().any(|c| c.to_lowercase().contains(lower_needle))
}

fn with_want_notes(base: Option<serde_json::Value>, note_text: &str) -> serde_json::Value {
    let mut meta = base.unwrap_or_else(|| serde_json::json!({}));
    if let Some(obj) = meta.as_object_mut() {
        obj.insert("want_notes".to_owned(), serde_json::Value::String(note_text.to_owned()));
    }
    meta
}

fn derive_want_collection_slug_and_label(trimmed: &str) -> CatalogResult<(String, String)> {
    if let Ok(kind) = EntryKind::parse_flexible(trimmed) {
        let slug = kind
            .default_want_slug()
            .ok_or_else(|| {
                CatalogError::InvalidInput(format!(
                    "Kind '{}' does not have a default want queue",
                    kind.as_str()
                ))
            })?
            .to_owned();
        let label = if slug == crate::db::WANT_TO_READ_SLUG {
            crate::db::WANT_TO_READ_TITLE.to_owned()
        } else {
            format!("Want: {}", kind.display_label())
        };
        Ok((slug, label))
    } else {
        let base_slug =
            trimmed.strip_prefix("want:").unwrap_or(trimmed).to_ascii_lowercase().replace('-', "_");
        if is_want_to_read_base_slug(&base_slug) {
            Ok((crate::db::WANT_TO_READ_SLUG.to_owned(), crate::db::WANT_TO_READ_TITLE.to_owned()))
        } else {
            let slug = format!("want:{base_slug}");
            let label = EntryKind::parse_flexible(&base_slug).map_or_else(
                |_| format!("Want: {base_slug}"),
                |k| format!("Want: {}", k.display_label()),
            );
            Ok((slug, label))
        }
    }
}

pub(super) fn append_dynamic_rule_sql_filters(
    qb: &mut QueryBuilder<Sqlite>,
    rule: &DynamicCollectionRule,
) {
    append_dynamic_rule_sql_filters_opts(qb, rule, false);
}

pub(super) fn append_dynamic_rule_sql_filters_opts(
    qb: &mut QueryBuilder<Sqlite>,
    rule: &DynamicCollectionRule,
    suppress_kind_index: bool,
) {
    let mut target_kinds: Vec<EntryKind> = Vec::new();
    if let Some(k) = rule.kind {
        target_kinds.push(k);
    }
    for &k in &rule.kinds {
        if !target_kinds.contains(&k) {
            target_kinds.push(k);
        }
    }
    let kind_col = if suppress_kind_index { "+e.kind" } else { "e.kind" };
    if target_kinds.len() == 1 {
        qb.push(" AND ").push(kind_col).push(" = ").push_bind(target_kinds[0]);
    } else if !target_kinds.is_empty() {
        qb.push(" AND ").push(kind_col).push(" IN (");
        let mut sep = qb.separated(", ");
        for k in target_kinds {
            sep.push_bind(k);
        }
        sep.push_unseparated(")");
    }
    if let Some(consumed_flag) = rule.consumed {
        if consumed_flag {
            qb.push(" AND EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id AND cl.status = 'consumed')");
        } else {
            qb.push(" AND NOT EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id AND cl.status = 'consumed')");
        }
    }
    if let Some(st) = rule.status {
        qb.push(" AND EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id AND cl.status = ")
            .push_bind(st)
            .push(")");
    }
    if let Some(yr) = rule.release_year {
        qb.push(" AND e.release_year = ").push_bind(yr);
    }
}

pub fn derive_manual_collection_slug_and_id(
    title: &str,
    slug: Option<&str>,
    role: CollectionRole,
) -> CatalogResult<(String, String, String)> {
    roche_core::domain::derive_manual_collection_slug_and_id(title, slug, role)
        .map_err(CatalogError::InvalidInput)
}

impl CatalogRepo<'_> {
    /// Searches entries across all Want queues using `entries_fts` (`title`/`creators`/`summary`)
    /// or `want_notes` in `metadata_json`, returning queue metadata alongside each matched entry.
    pub async fn search_want_entries(
        &self,
        query: &str,
        limit: usize,
    ) -> CatalogResult<Vec<WantSearchMatch>> {
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
        let rows: Vec<(String, String, String, String)> = sqlx::query_as(
            "SELECT qc.id, COALESCE(cs.slug, qc.id), qc.title, e.id
             FROM collection_specs cs
             JOIN entries qc ON qc.id = cs.collection_id
             JOIN collection_entries ce ON ce.collection_id = cs.collection_id
             JOIN entries e ON e.id = ce.entry_id
             WHERE cs.collection_role = 'want'
               AND qc.merged_into_id IS NULL
               AND e.merged_into_id IS NULL
               AND (
                   e.rowid IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)
                   OR COALESCE(json_extract(e.metadata_json, '$.want_notes'), '') LIKE ? ESCAPE '\\'
               )
             ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ce.added_at) DESC, LOWER(e.title) ASC
             LIMIT ?",
        )
        .bind(&fts_query)
        .bind(&notes_pattern)
        .bind(cap)
        .fetch_all(tx.conn())
        .await?;

        let entry_ids: Vec<String> = rows.iter().map(|(_, _, _, eid)| eid.clone()).collect();
        let entry_map = Self::load_entries_by_ids_in_tx(tx.conn(), &entry_ids).await?;
        tx.commit().await?;
        let mut out = Vec::with_capacity(rows.len());
        for (queue_id, queue_slug, queue_title, entry_id) in rows {
            if let Some(entry) = entry_map.get(&entry_id) {
                out.push(WantSearchMatch {
                    queue_id,
                    queue_slug,
                    queue_title,
                    entry: entry.clone(),
                });
            }
        }
        Ok(out)
    }
}
