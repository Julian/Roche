use std::collections::{HashMap, HashSet};

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    collections::derive_manual_collection_slug_and_id,
    identity_norm::{normalize_identifier_scheme, normalize_identifier_value},
    models::{
        BatchCollectionInput, BatchComparisonInput, BatchGeneratorInput, CatalogBatchInput,
        CatalogBatchResult, CatalogError, CatalogResult,
    },
};
use crate::{
    db::{
        entries_core,
        sync::SyncRepo,
        types::{
            ActivityAction, ActivityEntityType, CollectionMode, CollectionRole, EntryKind, HexColor,
        },
    },
    ranking::validate_ordering,
};

const MAX_BATCH_ENTRIES: usize = 5_000;
const MAX_BATCH_COLLECTIONS: usize = 500;
const MAX_BATCH_MEMBERS_PER_COLLECTION: usize = 5_000;
const MAX_BATCH_TOTAL_MEMBERS: usize = 50_000;
const MAX_BATCH_LOGS: usize = 5_000;
const MAX_BATCH_COMPARISONS: usize = 2_000;
const MAX_BATCH_GENERATORS: usize = 200;
const MAX_BATCH_IDENTIFIERS_PER_ENTRY: usize = 32;

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
        let mut tx = crate::db::begin_write(self.pool).await?;
        let mut ref_map: HashMap<String, String> =
            HashMap::with_capacity(batch.entries.len() + batch.collections.len());

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
            let entry_id = Self::upsert_entry_in_tx(&mut tx, &item.entry, kind, title, now).await?;
            if let Some(key) = item.ref_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                ref_map.insert(key.to_owned(), entry_id);
            }
            entries_processed += 1;
        }

        let collections_processed =
            Self::apply_batch_collections_in_tx(&mut tx, &mut ref_map, &batch.collections, now)
                .await?;

        let mut logs_recorded = 0usize;
        for log in &batch.logs {
            let target_id =
                Self::resolve_batch_ref_in_tx(&mut tx, &ref_map, &log.entry_ref).await?;
            let event_ts = log.ts.unwrap_or(now);
            Self::log_consumption_in_tx(
                &mut tx,
                &target_id,
                log.status,
                log.notes.as_deref(),
                event_ts,
                now,
            )
            .await?;
            logs_recorded += 1;
        }

        let comparisons_recorded =
            Self::apply_batch_comparisons_in_tx(&mut tx, &ref_map, &batch.comparisons, now).await?;

        let generators_updated =
            Self::apply_batch_generators_in_tx(&mut tx, &ref_map, &batch.generators, now).await?;

        if batch.dry_run {
            tx.rollback().await?;
        } else {
            tx.commit().await?;
        }

        Ok(CatalogBatchResult {
            collections_processed,
            entries_processed,
            logs_recorded,
            comparisons_recorded,
            generators_updated,
            dry_run: batch.dry_run,
        })
    }

    async fn apply_batch_collections_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &mut HashMap<String, String>,
        collections: &[BatchCollectionInput],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut count = 0usize;
        for col in collections {
            let col_id = Self::upsert_batch_collection_spec_in_tx(tx, col, now).await?;
            if let Some(key) = col.ref_key.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                ref_map.insert(key.to_owned(), col_id.clone());
            }
            for member in &col.members {
                let member_id =
                    Self::resolve_batch_ref_in_tx(tx, ref_map, &member.entry_ref).await?;
                Self::attach_collection_member_in_tx(
                    tx,
                    &col_id,
                    &member_id,
                    member.position,
                    now,
                    false,
                )
                .await?;
            }
            count += 1;
        }
        Ok(count)
    }

    async fn upsert_batch_collection_spec_in_tx(
        tx: &mut SqliteConnection,
        col: &BatchCollectionInput,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
        let (clean_title, derived_slug, requested_id) = derive_batch_collection_slug_and_id(col)?;

        let existing: Option<(String, CollectionMode, CollectionRole)> = sqlx::query_as(
            "SELECT collection_id, mode, collection_role
             FROM collection_specs
             WHERE collection_id = ? OR (slug IS NOT NULL AND LOWER(slug) = LOWER(?))
             ORDER BY CASE WHEN collection_id = ? THEN 0 ELSE 1 END ASC
             LIMIT 1",
        )
        .bind(&requested_id)
        .bind(&derived_slug)
        .bind(&requested_id)
        .fetch_optional(&mut *tx)
        .await?;

        let target_id = if let Some((ex_id, ex_mode, ex_role)) = existing {
            if crate::db::is_protected_system_collection_id(&ex_id)
                || ex_mode != CollectionMode::Manual
                || matches!(
                    ex_role,
                    CollectionRole::Feed | CollectionRole::Tag | CollectionRole::System
                )
            {
                return Err(CatalogError::Conflict(format!(
                    "Collection '{derived_slug}' conflicts with an immutable system/feed/tag collection"
                )));
            }
            ex_id
        } else {
            if crate::db::is_protected_system_collection_id(&requested_id) {
                return Err(CatalogError::Conflict(format!(
                    "Collection '{derived_slug}' conflicts with a protected system collection"
                )));
            }
            requested_id
        };

        let existing_entry: Option<(EntryKind, Option<String>)> =
            sqlx::query_as("SELECT kind, merged_into_id FROM entries WHERE id = ?")
                .bind(&target_id)
                .fetch_optional(&mut *tx)
                .await?;
        if let Some((ex_kind, ex_merged)) = existing_entry
            && (ex_kind != EntryKind::Collection || ex_merged.is_some())
        {
            return Err(CatalogError::Conflict(format!(
                "Collection '{derived_slug}' targets an existing non-collection or merged entry"
            )));
        }

        let show_in_lists = col.role == CollectionRole::List;
        let clean_summary = col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let validated_color = HexColor::parse_optional(col.color.as_deref())
            .map_err(|e| CatalogError::InvalidInput(e.to_string()))?;
        let clean_color = validated_color.as_ref().map(HexColor::as_str);

        sqlx::query(
            "INSERT INTO entries (id, kind, title, summary, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                summary = COALESCE(excluded.summary, entries.summary),
                updated_at = excluded.updated_at",
        )
        .bind(&target_id)
        .bind(&clean_title)
        .bind(clean_summary)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT INTO collection_specs
             (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
             VALUES (?, ?, 'manual', ?, NULL, ?, ?)
             ON CONFLICT(collection_id) DO UPDATE SET
                collection_role = excluded.collection_role,
                show_in_lists = excluded.show_in_lists,
                color = COALESCE(excluded.color, collection_specs.color)",
        )
        .bind(&target_id)
        .bind(&derived_slug)
        .bind(col.role)
        .bind(show_in_lists)
        .bind(clean_color)
        .execute(&mut *tx)
        .await?;

        entries_core::upsert_entry_identifier_in_tx(tx, &target_id, "slug", &derived_slug).await?;

        SyncRepo::record_activity(
            tx,
            ActivityEntityType::Entry,
            &target_id,
            ActivityAction::Update,
            now,
        )
        .await?;

        Ok(target_id)
    }

    async fn apply_batch_comparisons_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        comparisons: &[BatchComparisonInput],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut recorded = 0usize;
        for cmp in comparisons {
            let mut resolved_ordering: Vec<Vec<String>> = Vec::with_capacity(cmp.ordering.len());
            for bucket in &cmp.ordering {
                let mut resolved_bucket = Vec::with_capacity(bucket.len());
                for item_ref in bucket {
                    let id = Self::resolve_batch_ref_in_tx(tx, ref_map, item_ref).await?;
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
                        Self::resolve_batch_collection_ref_in_tx(tx, ref_map, raw_scope).await?.0,
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
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        generators: &[BatchGeneratorInput],
        now: DateTime<Utc>,
    ) -> CatalogResult<usize> {
        let mut updated = 0usize;
        for gen_input in generators {
            let (col_id, col_title, mode, role) =
                Self::resolve_batch_collection_ref_in_tx(tx, ref_map, &gen_input.collection_ref)
                    .await?;
            Self::ensure_generator_eligible(&col_id, &col_title, mode, role)?;
            let explicit_entry_id = match gen_input
                .current_entry_ref
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                Some(r) => Some(Self::resolve_batch_ref_in_tx(tx, ref_map, r).await?),
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

    async fn resolve_batch_collection_ref_in_tx(
        tx: &mut SqliteConnection,
        ref_map: &HashMap<String, String>,
        raw_ref: &str,
    ) -> CatalogResult<(String, String, CollectionMode, CollectionRole)> {
        let trimmed = raw_ref.trim();
        let candidate = ref_map.get(trimmed).map_or(trimmed, String::as_str);
        let norm_slug = candidate.to_ascii_lowercase().replace('-', "_");
        let row: Option<(String, String, CollectionMode, CollectionRole)> = sqlx::query_as(
            "SELECT e.id, e.title, cs.mode, cs.collection_role
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
        raw_ref: &str,
    ) -> CatalogResult<String> {
        let trimmed = raw_ref.trim();
        if trimmed.is_empty() {
            return Err(CatalogError::InvalidInput("Batch entry reference cannot be empty".into()));
        }
        if let Some(mapped) = ref_map.get(trimmed)
            && let Some(canon) = Self::follow_merged_id_in_tx(tx, mapped).await?
        {
            return Ok(canon);
        }
        if let Some(canon) = Self::follow_merged_id_in_tx(tx, trimmed).await? {
            return Ok(canon);
        }
        if let Some((raw_scheme, raw_val)) = trimmed.split_once(':') {
            let scheme = normalize_identifier_scheme(raw_scheme);
            if !scheme.is_empty() && !raw_val.trim().is_empty() {
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
                    if let Some(found) =
                        entries_core::find_entry_id_by_identifier_in_tx(tx, &scheme, &norm_val)
                            .await?
                        && let Some(canon) = Self::follow_merged_id_in_tx(tx, &found).await?
                    {
                        return Ok(canon);
                    }
                }
            }
        }
        Err(CatalogError::NotFound(format!("Batch entry reference '{trimmed}'")))
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
#[allow(clippy::unwrap_used)]
mod tests {
    use chrono::TimeZone;

    use crate::db::{
        BatchCollectionInput, BatchCollectionMemberInput, BatchComparisonInput, BatchEntryInput,
        BatchGeneratorInput, BatchLogInput, CatalogBatchInput, CollectionRole, ConsumptionStatus,
        Db, EntryIdentifier, EntryKind, UpsertEntryInput,
    };

    fn sample_catalog_batch(dry_run: bool) -> CatalogBatchInput {
        let t1 = chrono::Utc.with_ymd_and_hms(2025, 4, 1, 10, 0, 0).unwrap();
        let t2 = chrono::Utc.with_ymd_and_hms(2025, 4, 2, 10, 0, 0).unwrap();
        let t3 = chrono::Utc.with_ymd_and_hms(2025, 4, 3, 10, 0, 0).unwrap();
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
                    },
                    BatchCollectionMemberInput {
                        entry_ref: "alb:ellington".to_owned(),
                        position: Some(2.0),
                    },
                    BatchCollectionMemberInput {
                        entry_ref: "alb:elvis".to_owned(),
                        position: Some(3.0),
                    },
                ],
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
                    ts: Some(t1),
                },
                BatchLogInput {
                    entry_ref: "alb:ellington".to_owned(),
                    status: ConsumptionStatus::Consumed,
                    notes: Some("★★★★☆ — Blistering live energy".to_owned()),
                    ts: Some(t2),
                },
            ],
            comparisons: vec![BatchComparisonInput {
                scope_collection_id: None,
                ordering: vec![vec!["alb:sinatra".to_owned()], vec!["alb:ellington".to_owned()]],
                ts: Some(t2),
            }],
            generators: vec![BatchGeneratorInput {
                collection_ref: "col:canon".to_owned(),
                enabled: Some(true),
                current_entry_ref: Some("alb:elvis".to_owned()),
                drawn_at: Some(t3),
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

        // `get_or_create_want_collection("weekend_watch")` resolves to the exact same collection
        let resolved = catalog.get_or_create_want_collection("weekend_watch").await.unwrap();
        assert_eq!(resolved.id, expected_id);
        assert_eq!(resolved.slug.as_deref(), Some("want:weekend_watch"));

        // Case-insensitive "WANT:" prefix on an explicit slug is stripped before slugifying
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

        // Empty base slug after stripping "want:" is rejected
        let empty_err = catalog
            .create_manual_collection("Empty Want", Some("WANT:"), CollectionRole::Want, None, None)
            .await
            .unwrap_err();
        assert!(matches!(empty_err, crate::db::CatalogError::InvalidInput(_)));

        // `apply_batch` with `slug: Some("want:weekend_watch")` converges to the same ID and slug
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
            }],
            entries: Vec::new(),
            logs: Vec::new(),
            comparisons: Vec::new(),
            generators: Vec::new(),
            dry_run: false,
        };
        batch_db.catalog().apply_batch(&batch).await.unwrap();
        let batched =
            batch_db.catalog().get_collection_summary("want:weekend_watch").await.unwrap();
        assert_eq!(batched.id, expected_id);
        assert_eq!(batched.slug.as_deref(), Some("want:weekend_watch"));

        // Explicit `col.id` override in batch is still honored
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
            }],
            entries: Vec::new(),
            logs: Vec::new(),
            comparisons: Vec::new(),
            generators: Vec::new(),
            dry_run: false,
        };
        batch_db.catalog().apply_batch(&custom_id_batch).await.unwrap();
        let explicit =
            batch_db.catalog().get_collection_summary("want:explicit_queue").await.unwrap();
        assert_eq!(explicit.id, "explicit-want-id");
        assert_eq!(explicit.slug.as_deref(), Some("want:explicit_queue"));
    }
}
