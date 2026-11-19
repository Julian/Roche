use std::collections::{HashMap, HashSet, VecDeque};

use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use super::{
    CatalogRepo,
    models::{
        CatalogEntry, CatalogError, CatalogResult, CollectionSummary, DynamicCollectionRule,
        RawCollectionRow, RawEntryRow, UpsertEntryInput,
    },
};
use crate::db::{
    sync::{SyncRepo, collection_entry_natural_key, deterministic_system_collection_id},
    types::{ActivityAction, ActivityEntityType, CollectionMode, CollectionRole, EntryKind},
};

impl CatalogRepo<'_> {
    #[allow(clippy::too_many_lines)]
    pub async fn list_collections(
        &self,
        include_want: bool,
        include_system: bool,
    ) -> CatalogResult<Vec<CollectionSummary>> {
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.id, cs.slug, e.title, e.summary, e.kind, cs.mode, cs.collection_role,
                    cs.rule_json, cs.show_in_lists, cs.color,
                    EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) AS has_generator,
                    e.created_at, e.updated_at
             FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL AND cs.collection_role != 'tag' AND cs.collection_role != 'feed'",
        );
        if !include_want {
            qb.push(" AND cs.collection_role != 'want'");
        }
        if !include_system {
            qb.push(" AND (cs.show_in_lists = 1 OR cs.collection_role = 'want')");
        }
        qb.push(" ORDER BY cs.collection_role ASC, LOWER(e.title) ASC");
        let rows: Vec<RawCollectionRow> = qb.build_query_as().fetch_all(self.pool).await?;
        if rows.is_empty() {
            return Ok(Vec::new());
        }

        // Load global quality orderings ONCE across all collections instead of N times.
        let global_orderings = self.load_orderings_for_scope(None).await?;
        let mut global_comp_indices_by_entry: HashMap<String, Vec<usize>> = HashMap::new();
        for (comp_idx, ord) in global_orderings.iter().enumerate() {
            let mut seen_in_comp = HashSet::new();
            for id in ord.iter().flat_map(|g| g.iter()) {
                if seen_in_comp.insert(id.as_str()) {
                    global_comp_indices_by_entry.entry(id.clone()).or_default().push(comp_idx);
                }
            }
        }

        // Batch-load members for all Manual/Feed collections in a single query instead of N queries.
        let manual_or_feed_ids: Vec<&str> = rows
            .iter()
            .filter(|r| matches!(r.mode, CollectionMode::Manual | CollectionMode::Feed))
            .map(|r| r.id.as_str())
            .collect();
        let mut manual_members_by_col: HashMap<String, Vec<(String, bool)>> = HashMap::new();
        for chunk in manual_or_feed_ids.chunks(450) {
            let mut mqb = QueryBuilder::<Sqlite>::new(
                "SELECT ce.collection_id,
                        e.id,
                        EXISTS (
                            SELECT 1 FROM consumption_log cl
                            WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                        ) AS is_consumed
                 FROM collection_entries ce
                 JOIN entries e ON e.id = ce.entry_id
                 WHERE e.merged_into_id IS NULL AND ce.collection_id IN (",
            );
            let mut sep = mqb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(")");
            let member_rows: Vec<(String, String, bool)> =
                mqb.build_query_as().fetch_all(self.pool).await?;
            for (cid, eid, is_consumed) in member_rows {
                manual_members_by_col.entry(cid).or_default().push((eid, is_consumed));
            }
        }

        // Batch-load scoped comparisons for all Want collections in a single query.
        let want_ids: Vec<&str> = rows
            .iter()
            .filter(|r| r.collection_role == CollectionRole::Want)
            .map(|r| r.id.as_str())
            .collect();
        let mut want_orderings_by_col: HashMap<String, Vec<Vec<Vec<String>>>> = HashMap::new();
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
                cqb.build_query_as().fetch_all(self.pool).await?;
            for (cid, json_str) in comp_rows {
                if let Ok(ord) = serde_json::from_str::<Vec<Vec<String>>>(&json_str) {
                    want_orderings_by_col.entry(cid).or_default().push(ord);
                }
            }
        }

        let mut out = Vec::with_capacity(rows.len());
        for raw in rows {
            let member_ids: Vec<String> = match raw.mode {
                CollectionMode::Manual | CollectionMode::Feed => manual_members_by_col
                    .remove(&raw.id)
                    .unwrap_or_default()
                    .into_iter()
                    .filter_map(|(eid, is_consumed)| {
                        (!raw.has_generator || is_consumed).then_some(eid)
                    })
                    .collect(),
                CollectionMode::Dynamic => self.resolve_rankable_member_ids(&raw).await?,
            };
            let comp_count = if raw.collection_role == CollectionRole::Want {
                if member_ids.is_empty() {
                    0
                } else {
                    let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
                    want_orderings_by_col.get(&raw.id).map_or(0, |scoped| {
                        scoped
                            .iter()
                            .filter(|ord| {
                                ord.iter()
                                    .flat_map(|g| g.iter())
                                    .any(|id| member_set.contains(id.as_str()))
                            })
                            .count()
                    })
                }
            } else {
                let mut matched_comps: HashSet<usize> = HashSet::new();
                for mid in &member_ids {
                    if let Some(indices) = global_comp_indices_by_entry.get(mid) {
                        matched_comps.extend(indices.iter().copied());
                    }
                }
                matched_comps.len()
            };

            out.push(CollectionSummary {
                id: raw.id,
                slug: raw.slug,
                title: raw.title,
                summary: raw.summary,
                kind: raw.kind,
                mode: raw.mode,
                role: raw.collection_role,
                rule_json: raw.rule_json,
                show_in_lists: raw.show_in_lists,
                color: raw.color,
                member_count: member_ids.len(),
                comparison_count: comp_count,
                has_generator: raw.has_generator,
                created_at: raw.created_at,
                updated_at: raw.updated_at,
            });
        }
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
        let clean_title = title.trim();
        if clean_title.is_empty() {
            return Err(CatalogError::InvalidInput("Collection title cannot be empty".to_owned()));
        }
        if !matches!(role, CollectionRole::List | CollectionRole::Want) {
            return Err(CatalogError::InvalidInput(
                "Custom collections must have role 'list' or 'want'".to_owned(),
            ));
        }

        let derived_slug = slug
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map_or_else(|| slugify_title(clean_title), slugify_title);
        if derived_slug.is_empty() {
            return Err(CatalogError::InvalidInput("Collection slug cannot be empty".to_owned()));
        }

        let id = deterministic_system_collection_id(&format!("custom:{derived_slug}"));
        let now = SyncRepo::now_utc();
        let show_in_lists = role == CollectionRole::List;
        let clean_summary = summary.map(str::trim).filter(|s| !s.is_empty());
        let clean_color = color.map(str::trim).filter(|s| !s.is_empty());

        let mut tx = crate::db::begin_write(self.pool).await?;
        let existing = sqlx::query_scalar!(
            r#"SELECT collection_id as "collection_id!" FROM collection_specs WHERE slug = ?"#,
            &derived_slug,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if existing.is_some() {
            return Err(CatalogError::Conflict(format!(
                "Collection with slug '{derived_slug}' already exists"
            )));
        }
        sqlx::query!(
            "INSERT INTO entries (id, kind, title, summary, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?, ?)",
            &id,
            clean_title,
            clean_summary,
            now as _,
            now as _,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!(
            "INSERT INTO collection_specs
             (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
             VALUES (?, ?, 'manual', ?, NULL, ?, ?)",
            &id,
            &derived_slug,
            role as _,
            show_in_lists as _,
            clean_color,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('slug', ?, ?)",
            &derived_slug,
            &id,
        )
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
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
        let raw = self.lookup_collection_row(slug_or_id, false).await?;
        self.summarize_collection_row(raw).await
    }

    /// Resolves or deterministically creates the `Want` queue collection for `target`
    /// (e.g. `"movie"` / `"movies"` -> `"want:movies"`, or an existing `want` collection).
    pub async fn get_or_create_want_collection(
        &self,
        target: &str,
    ) -> CatalogResult<CollectionSummary> {
        let trimmed = target.trim();
        if let Ok(existing) = self.lookup_collection_row(trimmed, true).await
            && existing.collection_role == CollectionRole::Want
        {
            return self.summarize_collection_row(existing).await;
        }

        let (want_slug, pretty_label) = if let Ok(kind) = EntryKind::parse_flexible(trimmed) {
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
            (slug, label)
        } else {
            let base_slug = trimmed
                .strip_prefix("want:")
                .unwrap_or(trimmed)
                .to_ascii_lowercase()
                .replace('-', "_");
            if base_slug == "read" || base_slug == "articles" || base_slug == "papers" {
                (crate::db::WANT_TO_READ_SLUG.to_owned(), crate::db::WANT_TO_READ_TITLE.to_owned())
            } else {
                let slug = format!("want:{base_slug}");
                let label = EntryKind::parse_flexible(&base_slug).map_or_else(
                    |_| format!("Want: {base_slug}"),
                    |k| format!("Want: {}", k.display_label()),
                );
                (slug, label)
            }
        };

        if let Ok(existing) = self.lookup_collection_row(&want_slug, false).await {
            return self.summarize_collection_row(existing).await;
        }

        let id = deterministic_system_collection_id(&want_slug);
        let now = SyncRepo::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let res = sqlx::query!(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?)",
            &id,
            &pretty_label,
            now as _,
            now as _,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!(
            "INSERT OR IGNORE INTO collection_specs
             (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
             VALUES (?, ?, 'manual', 'want', NULL, 0)",
            &id,
            &want_slug,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('slug', ?, ?)",
            &want_slug,
            &id,
        )
        .execute(&mut *tx)
        .await?;
        if res.rows_affected() > 0 {
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Entry,
                &id,
                ActivityAction::Create,
                now,
            )
            .await?;
        }
        tx.commit().await?;

        self.get_collection_summary(&id).await
    }

    /// Manually adds an entry to a collection, strictly rejecting `feed` and `dynamic` collections
    /// as well as self-containment and ancestor cycles.
    pub async fn add_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
        position: Option<f64>,
    ) -> CatalogResult<()> {
        let now = SyncRepo::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let col = Self::lookup_collection_row_in_tx(&mut tx, slug_or_id, false).await?;
        if col.mode != CollectionMode::Manual {
            return Err(CatalogError::ImmutableCollection { title: col.title, mode: col.mode });
        }
        let target_entry_id = Self::follow_merged_id_in_tx(&mut tx, entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))?;
        if target_entry_id == col.id {
            return Err(CatalogError::InvalidInput(
                "A collection cannot contain itself".to_owned(),
            ));
        }
        if Self::would_introduce_collection_cycle_in_tx(&mut tx, &col.id, &target_entry_id).await? {
            return Err(CatalogError::InvalidInput(
                "Adding entry to collection would create a cycle".to_owned(),
            ));
        }

        let nat_key = collection_entry_natural_key(&col.id, &target_entry_id);
        SyncRepo::clear_tombstone(&mut tx, ActivityEntityType::CollectionEntry, &nat_key).await?;

        sqlx::query!(
            "INSERT INTO collection_entries (collection_id, entry_id, position, added_at)
             VALUES (?, ?, ?, ?)
             ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                position = COALESCE(excluded.position, collection_entries.position)",
            &col.id,
            &target_entry_id,
            position,
            now as _,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, &col.id,)
            .execute(&mut *tx)
            .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::CollectionEntry,
            &nat_key,
            ActivityAction::Create,
            now,
        )
        .await?;
        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Entry,
            &col.id,
            ActivityAction::Update,
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
        let now = SyncRepo::now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let col = Self::lookup_collection_row_in_tx(&mut tx, slug_or_id, false).await?;
        if col.mode != CollectionMode::Manual {
            return Err(CatalogError::ImmutableCollection { title: col.title, mode: col.mode });
        }
        let target_entry_id = Self::follow_merged_id_in_tx(&mut tx, entry_id)
            .await?
            .unwrap_or_else(|| entry_id.trim().to_owned());
        let res = sqlx::query!(
            "DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
            &col.id,
            &target_entry_id,
        )
        .execute(&mut *tx)
        .await?;
        let removed = res.rows_affected() > 0;
        if removed {
            Self::advance_generator_if_drawing_in_tx(&mut tx, &col.id, &target_entry_id, now)
                .await?;
            let nat_key = collection_entry_natural_key(&col.id, &target_entry_id);
            SyncRepo::record_tombstone(&mut tx, ActivityEntityType::CollectionEntry, &nat_key, now)
                .await?;
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::CollectionEntry,
                &nat_key,
                ActivityAction::Delete,
                now,
            )
            .await?;
            sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", now as _, &col.id,)
                .execute(&mut *tx)
                .await?;
            SyncRepo::record_activity(
                &mut tx,
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
        let entry = self.upsert_entry(input).await?;
        let queue_key = match target_queue.map(str::trim).filter(|s| !s.is_empty()) {
            Some(explicit) => explicit.to_owned(),
            None => entry
                .kind
                .default_want_slug()
                .ok_or_else(|| {
                    CatalogError::InvalidInput(format!(
                        "Specify a target want queue for kind '{}'",
                        entry.kind.as_str()
                    ))
                })?
                .to_owned(),
        };
        let queue = self.get_or_create_want_collection(&queue_key).await?;
        self.add_collection_entry(&queue.id, &entry.id, None).await?;
        let refreshed_queue = self.get_collection_summary(&queue.id).await?;
        Ok((refreshed_queue, entry))
    }

    pub async fn unwant_entry(
        &self,
        target_queue: &str,
        query_or_id: &str,
    ) -> CatalogResult<CatalogEntry> {
        let queue = self.get_or_create_want_collection(target_queue).await?;
        let entry = self.resolve_entry(query_or_id, None, Some(&queue.id)).await?;
        self.remove_collection_entry(&queue.id, &entry.id).await?;
        Ok(entry)
    }

    pub(crate) async fn would_introduce_collection_cycle_in_tx(
        conn: &mut SqliteConnection,
        parent_col_id: &str,
        child_entry_id: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        if parent_col_id == child_entry_id {
            return Ok(true);
        }
        let mut visited: HashSet<String> = HashSet::from([parent_col_id.to_owned()]);
        let mut queue: VecDeque<String> = VecDeque::from([parent_col_id.to_owned()]);

        while let Some(cur) = queue.pop_front() {
            let ancestors = sqlx::query_scalar!(
                r#"SELECT collection_id as "collection_id!" FROM collection_entries WHERE entry_id = ?"#,
                &cur,
            )
            .fetch_all(&mut *conn)
            .await?;

            for anc in ancestors {
                if anc == child_entry_id {
                    return Ok(true);
                }
                if visited.len() < 1_024 && visited.insert(anc.clone()) {
                    queue.push_back(anc);
                }
            }
        }
        Ok(false)
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
        if let Some(row) = sqlx::query_as!(
            RawCollectionRow,
            r#"SELECT e.id as "id!", cs.slug as "slug?", e.title as "title!",
                      e.summary as "summary?", e.kind as "kind!: EntryKind",
                      cs.mode as "mode!: CollectionMode",
                      cs.collection_role as "collection_role!: CollectionRole",
                      cs.rule_json as "rule_json?",
                      cs.show_in_lists as "show_in_lists!: bool",
                      cs.color as "color?",
                      EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) as "has_generator!: bool",
                      e.created_at as "created_at!: DateTime<Utc>",
                      e.updated_at as "updated_at!: DateTime<Utc>"
               FROM collection_specs cs
               JOIN entries e ON e.id = cs.collection_id
               WHERE e.merged_into_id IS NULL
                 AND (e.id = ? OR LOWER(cs.slug) = LOWER(?) OR LOWER(cs.slug) = ? OR LOWER(e.title) = LOWER(?))
               LIMIT 1"#,
            q,
            q,
            &norm_slug,
            q,
        )
        .fetch_optional(&mut *conn)
        .await?
        {
            return Ok(row);
        }

        if allow_kind_alias
            && let Ok(kind) = EntryKind::parse_flexible(q)
            && let Some(default_slug) = kind.default_list_slug()
            && let Some(row) = sqlx::query_as!(
                RawCollectionRow,
                r#"SELECT e.id as "id!", cs.slug as "slug?", e.title as "title!",
                          e.summary as "summary?", e.kind as "kind!: EntryKind",
                          cs.mode as "mode!: CollectionMode",
                          cs.collection_role as "collection_role!: CollectionRole",
                          cs.rule_json as "rule_json?",
                          cs.show_in_lists as "show_in_lists!: bool",
                          cs.color as "color?",
                          EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) as "has_generator!: bool",
                          e.created_at as "created_at!: DateTime<Utc>",
                          e.updated_at as "updated_at!: DateTime<Utc>"
                   FROM collection_specs cs
                   JOIN entries e ON e.id = cs.collection_id
                   WHERE e.merged_into_id IS NULL AND cs.slug = ?
                   LIMIT 1"#,
                default_slug,
            )
            .fetch_optional(&mut *conn)
            .await?
        {
            return Ok(row);
        }

        Err(CatalogError::NotFound(format!("Collection '{q}'")))
    }

    pub(super) async fn summarize_collection_row(
        &self,
        raw: RawCollectionRow,
    ) -> CatalogResult<CollectionSummary> {
        let member_ids = self.resolve_rankable_member_ids(&raw).await?;
        let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
        let is_priority = raw.collection_role == CollectionRole::Want;
        let comp_count = if member_set.is_empty() {
            0
        } else {
            let orderings = self
                .load_orderings_for_scope(if is_priority { Some(raw.id.as_str()) } else { None })
                .await?;
            orderings
                .iter()
                .filter(|ord| {
                    ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
                })
                .count()
        };

        Ok(CollectionSummary {
            id: raw.id,
            slug: raw.slug,
            title: raw.title,
            summary: raw.summary,
            kind: raw.kind,
            mode: raw.mode,
            role: raw.collection_role,
            rule_json: raw.rule_json,
            show_in_lists: raw.show_in_lists,
            color: raw.color,
            member_count: member_ids.len(),
            comparison_count: comp_count,
            has_generator: raw.has_generator,
            created_at: raw.created_at,
            updated_at: raw.updated_at,
        })
    }

    pub(super) async fn resolve_rankable_member_ids(
        &self,
        col: &RawCollectionRow,
    ) -> CatalogResult<Vec<String>> {
        match col.mode {
            CollectionMode::Manual | CollectionMode::Feed => {
                if col.has_generator {
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
                    .fetch_all(self.pool)
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
                    .fetch_all(self.pool)
                    .await?)
                }
            }
            CollectionMode::Dynamic => {
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
                    let rows: Vec<(String, String)> =
                        qb.build_query_as().fetch_all(self.pool).await?;
                    let lower_needle = creator_needle.to_lowercase();
                    Ok(rows
                        .into_iter()
                        .filter_map(|(id, creators_json)| {
                            let creators: Vec<String> =
                                serde_json::from_str(&creators_json).unwrap_or_default();
                            creators
                                .iter()
                                .any(|c| c.to_lowercase().contains(&lower_needle))
                                .then_some(id)
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
                    Ok(qb.build_query_scalar().fetch_all(self.pool).await?)
                }
            }
        }
    }

    pub(super) async fn resolve_rankable_members(
        &self,
        col: &RawCollectionRow,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        let raw_rows: Vec<RawEntryRow> = match col.mode {
            CollectionMode::Manual | CollectionMode::Feed => {
                if col.has_generator {
                    sqlx::query_as!(
                        RawEntryRow,
                        r#"SELECT e.id as "id!", e.kind as "kind!: EntryKind", e.title as "title!",
                                  e.creators_json as "creators_json!", e.url as "url?",
                                  e.published_at as "published_at?: DateTime<Utc>",
                                  e.release_year as "release_year?: i32", e.summary as "summary?",
                                  e.thumbnail_url as "thumbnail_url?", e.metadata_json as "metadata_json!",
                                  e.merged_into_id as "merged_into_id?",
                                  e.created_at as "created_at!: DateTime<Utc>",
                                  e.updated_at as "updated_at!: DateTime<Utc>"
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
                    .fetch_all(self.pool)
                    .await?
                } else {
                    sqlx::query_as!(
                        RawEntryRow,
                        r#"SELECT e.id as "id!", e.kind as "kind!: EntryKind", e.title as "title!",
                                  e.creators_json as "creators_json!", e.url as "url?",
                                  e.published_at as "published_at?: DateTime<Utc>",
                                  e.release_year as "release_year?: i32", e.summary as "summary?",
                                  e.thumbnail_url as "thumbnail_url?", e.metadata_json as "metadata_json!",
                                  e.merged_into_id as "merged_into_id?",
                                  e.created_at as "created_at!: DateTime<Utc>",
                                  e.updated_at as "updated_at!: DateTime<Utc>"
                           FROM collection_entries ce
                           JOIN entries e ON e.id = ce.entry_id
                           WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
                           ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC"#,
                        &col.id,
                    )
                    .fetch_all(self.pool)
                    .await?
                }
            }
            CollectionMode::Dynamic => {
                let Some(rule) = parse_dynamic_collection_rule(col.rule_json.as_deref()) else {
                    return Ok(Vec::new());
                };
                let mut qb = QueryBuilder::<Sqlite>::new(
                    "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at,
                            e.release_year, e.summary, e.thumbnail_url, e.metadata_json,
                            e.merged_into_id, e.created_at, e.updated_at
                     FROM entries e
                     WHERE e.merged_into_id IS NULL",
                );
                append_dynamic_rule_sql_filters(&mut qb, &rule);
                qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC");
                let mut fetched: Vec<RawEntryRow> =
                    qb.build_query_as().fetch_all(self.pool).await?;
                if let Some(creator_needle) =
                    rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
                {
                    let lower_needle = creator_needle.to_lowercase();
                    fetched.retain(|r| {
                        let creators: Vec<String> =
                            serde_json::from_str(&r.creators_json).unwrap_or_default();
                        creators.iter().any(|c| c.to_lowercase().contains(&lower_needle))
                    });
                }
                fetched
            }
        };

        self.hydrate_entries(raw_rows).await
    }
}

pub fn parse_dynamic_collection_rule(rule_json: Option<&str>) -> Option<DynamicCollectionRule> {
    let trimmed = rule_json.map(str::trim).filter(|s| !s.is_empty())?;
    serde_json::from_str::<DynamicCollectionRule>(trimmed).ok()
}

/// Evaluates whether an entry with `statuses` (its recorded `consumption_log` statuses) satisfies
/// `rule`, matching the SQL + creator semantics used across Rust and Kotlin.
#[must_use]
pub fn matches_dynamic_collection_rule(
    rule: &DynamicCollectionRule,
    kind: EntryKind,
    creators: &[String],
    release_year: Option<i32>,
    statuses: &[crate::db::types::ConsumptionStatus],
) -> bool {
    let mut target_kinds: Vec<EntryKind> = Vec::new();
    if let Some(k) = rule.kind {
        target_kinds.push(k);
    }
    for &k in &rule.kinds {
        if !target_kinds.contains(&k) {
            target_kinds.push(k);
        }
    }
    if !target_kinds.is_empty() && !target_kinds.contains(&kind) {
        return false;
    }
    if let Some(consumed_flag) = rule.consumed {
        let has_consumed = statuses.contains(&crate::db::types::ConsumptionStatus::Consumed);
        if consumed_flag != has_consumed {
            return false;
        }
    }
    if let Some(st) = rule.status
        && !statuses.contains(&st)
    {
        return false;
    }
    if let Some(yr) = rule.release_year
        && release_year != Some(yr)
    {
        return false;
    }
    if let Some(creator_needle) = rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let lower_needle = creator_needle.to_lowercase();
        if !creators.iter().any(|c| c.to_lowercase().contains(&lower_needle)) {
            return false;
        }
    }
    true
}

fn append_dynamic_rule_sql_filters(qb: &mut QueryBuilder<Sqlite>, rule: &DynamicCollectionRule) {
    let mut target_kinds: Vec<EntryKind> = Vec::new();
    if let Some(k) = rule.kind {
        target_kinds.push(k);
    }
    for &k in &rule.kinds {
        if !target_kinds.contains(&k) {
            target_kinds.push(k);
        }
    }
    if target_kinds.len() == 1 {
        qb.push(" AND e.kind = ").push_bind(target_kinds[0]);
    } else if !target_kinds.is_empty() {
        qb.push(" AND e.kind IN (");
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

pub(super) fn slugify_title(title: &str) -> String {
    let mut out = String::with_capacity(title.len());
    let mut prev_sep = false;
    for ch in title.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            prev_sep = false;
        } else if !prev_sep && !out.is_empty() {
            out.push('_');
            prev_sep = true;
        }
    }
    while out.ends_with('_') {
        out.pop();
    }
    out
}
