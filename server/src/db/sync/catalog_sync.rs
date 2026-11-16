use std::collections::{BTreeMap, HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use super::{
    SyncRepo,
    identity::{
        ALBUMS_1001_COLLECTION_ID, STARRED_COLLECTION_ID, canonical_ordering_json,
        collection_entry_natural_key, deterministic_comparison_id,
        deterministic_system_collection_id,
    },
};
use crate::{
    db::{
        ActivityAction, ActivityEntityType, CatalogRepo, CollectionMode, CollectionRole,
        ConsumptionStatus, EntryIdentifier, EntryKind, UpsertEntryInput,
    },
    ranking::validate_ordering,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerCatalogEntryRecord {
    #[serde(default)]
    pub id: String,
    pub kind: EntryKind,
    pub title: String,
    #[serde(default)]
    pub creators: Vec<String>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub published_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub release_year: Option<i32>,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub metadata: Option<serde_json::Value>,
    #[serde(default)]
    pub identifiers: Vec<EntryIdentifier>,
    #[serde(default)]
    pub merged_into_id: Option<String>,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub updated_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerCollectionSpecRecord {
    #[serde(default)]
    pub collection_id: String,
    pub title: String,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub slug: Option<String>,
    #[serde(default = "default_manual_mode")]
    pub mode: CollectionMode,
    #[serde(default)]
    pub role: CollectionRole,
    #[serde(default)]
    pub rule_json: Option<String>,
    #[serde(default)]
    pub show_in_lists: bool,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub updated_at: Option<DateTime<Utc>>,
}

const fn default_manual_mode() -> CollectionMode {
    CollectionMode::Manual
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct PeerCollectionEntryRecord {
    pub collection_id: String,
    pub entry_id: String,
    #[serde(default)]
    pub position: Option<f64>,
    pub added_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct PeerCollectionGeneratorRecord {
    pub collection_id: String,
    pub enabled: bool,
    #[serde(default)]
    pub current_entry_id: Option<String>,
    #[serde(default)]
    pub drawn_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct PeerConsumptionLogRecord {
    #[serde(default)]
    pub id: String,
    pub entry_id: String,
    pub ts: DateTime<Utc>,
    pub status: ConsumptionStatus,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerComparisonRecord {
    #[serde(default)]
    pub id: String,
    pub ts: DateTime<Utc>,
    #[serde(default)]
    pub scope_collection_id: Option<String>,
    pub ordering: Vec<Vec<String>>,
    #[serde(default)]
    pub created_at: Option<DateTime<Utc>>,
}

type OutgoingRawEntryRow = (
    String,
    EntryKind,
    String,
    String,
    Option<String>,
    Option<DateTime<Utc>>,
    Option<i32>,
    Option<String>,
    Option<String>,
    String,
    Option<String>,
    DateTime<Utc>,
    DateTime<Utc>,
);

impl SyncRepo<'_> {
    pub(super) const fn has_incoming_catalog_payload(
        collections: &[PeerCollectionSpecRecord],
        catalog_entries: &[PeerCatalogEntryRecord],
        collection_entries: &[PeerCollectionEntryRecord],
        collection_generators: &[PeerCollectionGeneratorRecord],
        consumption_log: &[PeerConsumptionLogRecord],
        comparisons: &[PeerComparisonRecord],
    ) -> bool {
        !collections.is_empty()
            || !catalog_entries.is_empty()
            || !collection_entries.is_empty()
            || !collection_generators.is_empty()
            || !consumption_log.is_empty()
            || !comparisons.is_empty()
    }

    #[allow(clippy::too_many_arguments, clippy::too_many_lines)]
    pub(super) async fn merge_incoming_catalog_in_tx(
        tx: &mut SqliteConnection,
        collections: &[PeerCollectionSpecRecord],
        catalog_entries: &[PeerCatalogEntryRecord],
        collection_entries: &[PeerCollectionEntryRecord],
        collection_generators: &[PeerCollectionGeneratorRecord],
        consumption_log: &[PeerConsumptionLogRecord],
        comparisons: &[PeerComparisonRecord],
        now: DateTime<Utc>,
    ) -> Result<()> {
        let mut collection_id_remap: HashMap<String, String> = HashMap::new();

        // 1. Collections (`list` / `want` manual collections, guarding slug collisions)
        for col in collections {
            let title = col.title.trim();
            if title.is_empty() || matches!(col.role, CollectionRole::Feed | CollectionRole::Tag) {
                continue;
            }
            if col.mode != CollectionMode::Manual {
                continue;
            }
            let slug_opt =
                col.slug.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_lowercase);
            let requested_col_id = if !col.collection_id.trim().is_empty() {
                col.collection_id.trim().to_owned()
            } else if let Some(ref s) = slug_opt {
                if s.starts_with("want:") {
                    deterministic_system_collection_id(s)
                } else {
                    deterministic_system_collection_id(&format!("custom:{s}"))
                }
            } else {
                continue;
            };

            let existing_spec = sqlx::query!(
                "SELECT collection_id,
                        mode AS \"mode: CollectionMode\",
                        collection_role AS \"collection_role: CollectionRole\"
                 FROM collection_specs
                 WHERE collection_id = ? OR (slug IS NOT NULL AND LOWER(slug) = LOWER(?))
                 ORDER BY CASE WHEN collection_id = ? THEN 0 ELSE 1 END ASC
                 LIMIT 1",
                requested_col_id,
                slug_opt,
                requested_col_id,
            )
            .fetch_optional(&mut *tx)
            .await?;

            let target_col_id = if let Some(ex) = existing_spec {
                let existing_col_id = ex.collection_id;
                let ex_mode = ex.mode;
                let ex_role = ex.collection_role;
                if ex_mode != CollectionMode::Manual
                    || matches!(
                        ex_role,
                        CollectionRole::Feed | CollectionRole::Tag | CollectionRole::System
                    )
                {
                    continue;
                }
                if existing_col_id != requested_col_id {
                    collection_id_remap.insert(requested_col_id.clone(), existing_col_id.clone());
                }
                existing_col_id
            } else {
                requested_col_id
            };

            let created = col.created_at.unwrap_or(now);
            let incoming_updated = col.updated_at.unwrap_or(now);
            let ingested_updated = incoming_updated.max(now);
            let clean_summary = col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty());
            let clean_color = col.color.as_deref().map(str::trim).filter(|s| !s.is_empty());
            sqlx::query!(
                "INSERT INTO entries (id, kind, title, summary, created_at, updated_at)
                 VALUES (?, 'collection', ?, ?, ?, ?)
                 ON CONFLICT(id) DO UPDATE SET
                    title = CASE
                        WHEN strftime('%Y-%m-%dT%H:%M:%fZ', ?) >= strftime('%Y-%m-%dT%H:%M:%fZ', entries.updated_at)
                        THEN excluded.title
                        ELSE entries.title
                    END,
                    summary = COALESCE(excluded.summary, entries.summary),
                    updated_at = CASE
                        WHEN strftime('%Y-%m-%dT%H:%M:%fZ', ?) >= strftime('%Y-%m-%dT%H:%M:%fZ', entries.updated_at)
                        THEN excluded.updated_at
                        ELSE entries.updated_at
                    END",
                target_col_id,
                title,
                clean_summary,
                created as _,
                ingested_updated as _,
                incoming_updated as _,
                incoming_updated as _,
            )
            .execute(&mut *tx)
            .await?;

            sqlx::query!(
                "INSERT INTO collection_specs
                 (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
                 VALUES (?, ?, 'manual', ?, NULL, ?, ?)
                 ON CONFLICT(collection_id) DO UPDATE SET
                    show_in_lists = excluded.show_in_lists,
                    color = COALESCE(excluded.color, collection_specs.color)",
                target_col_id,
                slug_opt,
                col.role as _,
                col.show_in_lists as _,
                clean_color,
            )
            .execute(&mut *tx)
            .await?;

            if let Some(s) = slug_opt {
                sqlx::query!(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('slug', ?, ?)",
                    s,
                    target_col_id,
                )
                .execute(&mut *tx)
                .await?;
            }
        }

        // 2. Active catalog entries (non-merged first, then validated merge redirects)
        let mut pending_merges: Vec<(&PeerCatalogEntryRecord, String)> = Vec::new();
        for entry in catalog_entries {
            let title = entry.title.trim();
            if title.is_empty() {
                continue;
            }
            if let Some(target_merge) =
                entry.merged_into_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
            {
                pending_merges.push((entry, target_merge.to_owned()));
                continue;
            }
            let input = UpsertEntryInput {
                id: (!entry.id.trim().is_empty()).then(|| entry.id.trim().to_owned()),
                kind: Some(entry.kind),
                title: title.to_owned(),
                creators: entry.creators.clone(),
                url: entry.url.clone(),
                published_at: entry.published_at,
                release_year: entry.release_year,
                summary: entry.summary.clone(),
                thumbnail_url: entry.thumbnail_url.clone(),
                metadata: entry.metadata.clone(),
                identifiers: entry.identifiers.clone(),
                parent_collection_id: None,
                parent_position: None,
            };
            let _ = CatalogRepo::upsert_entry_in_tx(
                tx,
                &input,
                entry.kind,
                title,
                entry.updated_at.unwrap_or(now).max(now),
            )
            .await?;
        }

        for (merged_entry, target_canon) in pending_merges {
            let dup_id = merged_entry.id.trim();
            if dup_id.is_empty() || dup_id == target_canon {
                continue;
            }
            let Some(canon_resolved) =
                CatalogRepo::follow_merged_id_in_tx(tx, &target_canon).await?
            else {
                continue;
            };
            if dup_id == canon_resolved {
                continue;
            }
            let existing_dup: Option<Option<String>> =
                sqlx::query_scalar!("SELECT merged_into_id FROM entries WHERE id = ?", dup_id)
                    .fetch_optional(&mut *tx)
                    .await?;
            match existing_dup {
                None => {
                    let merged_title = merged_entry.title.trim();
                    let merged_created = merged_entry.created_at.unwrap_or(now);
                    sqlx::query!(
                        "INSERT INTO entries (id, kind, title, merged_into_id, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?)",
                        dup_id,
                        merged_entry.kind as _,
                        merged_title,
                        canon_resolved,
                        merged_created as _,
                        now as _,
                    )
                    .execute(&mut *tx)
                    .await?;
                }
                Some(None) => {
                    if CatalogRepo::validate_mergeable_specs_in_tx(tx, dup_id, &canon_resolved)
                        .await
                        .is_ok()
                    {
                        CatalogRepo::fold_live_duplicate_in_tx(tx, dup_id, &canon_resolved, now)
                            .await?;
                    }
                }
                Some(Some(_)) => {}
            }
        }

        // 3. Collection memberships (`collection_entries`, respecting tombstones, mode & cycle guard)
        for ce in collection_entries {
            let mapped_col_raw = collection_id_remap
                .get(ce.collection_id.trim())
                .map_or(ce.collection_id.as_str(), String::as_str);
            let Some(col_id) = CatalogRepo::follow_merged_id_in_tx(tx, mapped_col_raw).await?
            else {
                continue;
            };
            let Some(entry_id) = CatalogRepo::follow_merged_id_in_tx(tx, &ce.entry_id).await?
            else {
                continue;
            };
            if col_id == entry_id {
                continue;
            }
            let spec_mode = sqlx::query!(
                "SELECT mode AS \"mode: CollectionMode\",
                        collection_role AS \"collection_role: CollectionRole\"
                 FROM collection_specs WHERE collection_id = ?",
                col_id,
            )
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(spec) = spec_mode
                && (spec.mode != CollectionMode::Manual
                    || matches!(spec.collection_role, CollectionRole::Feed | CollectionRole::Tag))
            {
                continue;
            }
            if CatalogRepo::would_introduce_collection_cycle_in_tx(tx, &col_id, &entry_id).await? {
                continue;
            }

            let nat_key = collection_entry_natural_key(&col_id, &entry_id);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar!(
                "SELECT deleted_at AS \"deleted_at: DateTime<Utc>\" FROM sync_tombstones
                 WHERE entity_type = 'collection_entry' AND natural_key = ?",
                nat_key,
            )
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(del_at) = tomb_ts {
                if ce.added_at <= del_at {
                    continue;
                }
                Self::clear_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key).await?;
            }

            let ingested_added_at = ce.added_at.max(now);
            sqlx::query!(
                "INSERT INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                    position = COALESCE(excluded.position, collection_entries.position),
                    added_at = CASE
                        WHEN strftime('%Y-%m-%dT%H:%M:%fZ', ?) > strftime('%Y-%m-%dT%H:%M:%fZ', collection_entries.added_at)
                          OR (excluded.position IS NOT NULL AND excluded.position IS NOT collection_entries.position)
                        THEN excluded.added_at
                        ELSE collection_entries.added_at
                    END",
                col_id,
                entry_id,
                ce.position,
                ingested_added_at as _,
                ce.added_at as _,
            )
            .execute(&mut *tx)
            .await?;
        }

        // 4. Consumption Log (preserving custom/timestamped IDs & deduplicating auto-completion IDs)
        for log in consumption_log {
            if CatalogRepo::follow_merged_id_in_tx(tx, &log.entry_id).await?.is_none() {
                continue;
            }
            let _ = CatalogRepo::log_consumption_with_id_in_tx(
                tx,
                &log.id,
                &log.entry_id,
                log.status,
                log.notes.as_deref(),
                log.ts,
                now,
            )
            .await?;
        }

        // 5. Comparisons (append-only, canonicalized through `merged_into_id` & `collection_id_remap`)
        for comp in comparisons {
            let raw_scope_opt =
                comp.scope_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
            let scope_id = match raw_scope_opt {
                Some(raw_scope) => {
                    let remapped =
                        collection_id_remap.get(raw_scope).map_or(raw_scope, String::as_str);
                    let Some(resolved) = CatalogRepo::follow_merged_id_in_tx(tx, remapped).await?
                    else {
                        continue;
                    };
                    Some(resolved)
                }
                None => None,
            };

            let mut seen = HashSet::new();
            let mut resolved_groups = Vec::with_capacity(comp.ordering.len());
            let mut missing_entry = false;
            for group in &comp.ordering {
                let mut g_out = Vec::new();
                for raw_id in group {
                    let Some(canon) = CatalogRepo::follow_merged_id_in_tx(tx, raw_id).await? else {
                        missing_entry = true;
                        break;
                    };
                    if seen.insert(canon.clone()) {
                        g_out.push(canon);
                    }
                }
                if missing_entry {
                    break;
                }
                if !g_out.is_empty() {
                    resolved_groups.push(g_out);
                }
            }
            if missing_entry || validate_ordering(&resolved_groups).is_err() {
                continue;
            }
            let ord_json = canonical_ordering_json(&resolved_groups);
            let was_rewritten =
                resolved_groups != comp.ordering || scope_id.as_deref() != raw_scope_opt;
            let comp_id = if comp.id.trim().is_empty() || was_rewritten {
                deterministic_comparison_id(
                    scope_id.as_deref(),
                    comp.ts.timestamp_millis(),
                    &resolved_groups,
                )
            } else {
                comp.id.trim().to_owned()
            };
            let res = sqlx::query!(
                "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at)
                 VALUES (?, ?, ?, ?, ?)",
                comp_id,
                comp.ts as _,
                scope_id,
                ord_json,
                now as _,
            )
            .execute(&mut *tx)
            .await?;
            if res.rows_affected() > 0 {
                Self::record_activity(
                    tx,
                    ActivityEntityType::Comparison,
                    &comp_id,
                    ActivityAction::Create,
                    now,
                )
                .await?;
            }
        }

        // 6. Collection Generators (`1001_albums` LWW + never-revive-consumed-album invariant)
        for inc_gen in collection_generators {
            if inc_gen.collection_id.trim() != ALBUMS_1001_COLLECTION_ID {
                continue;
            }
            let local_gen = sqlx::query!(
                "SELECT enabled AS \"enabled: bool\",
                        current_entry_id,
                        drawn_at AS \"drawn_at: DateTime<Utc>\",
                        updated_at AS \"updated_at: DateTime<Utc>\"
                 FROM collection_generators WHERE collection_id = ?",
                ALBUMS_1001_COLLECTION_ID,
            )
            .fetch_optional(&mut *tx)
            .await?;
            let (mut target_enabled, mut target_entry_id, mut target_drawn_at, mut target_updated) =
                if let Some(loc) = local_gen {
                    if inc_gen.updated_at > loc.updated_at
                        || (inc_gen.updated_at == loc.updated_at
                            && loc.current_entry_id.is_none()
                            && inc_gen.current_entry_id.is_some())
                    {
                        (
                            inc_gen.enabled,
                            inc_gen.current_entry_id.clone(),
                            inc_gen.drawn_at,
                            inc_gen.updated_at.max(now),
                        )
                    } else {
                        (loc.enabled, loc.current_entry_id, loc.drawn_at, loc.updated_at)
                    }
                } else {
                    (
                        inc_gen.enabled,
                        inc_gen.current_entry_id.clone(),
                        inc_gen.drawn_at,
                        inc_gen.updated_at.max(now),
                    )
                };

            if let Some(ref cur_id) = target_entry_id {
                let canon_cur = CatalogRepo::follow_merged_id_in_tx(tx, cur_id).await?;
                if let Some(cid) = canon_cur {
                    let already_finished: Option<i64> = sqlx::query_scalar!(
                        "SELECT 1 AS \"found!: i64\" FROM consumption_log
                         WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
                         LIMIT 1",
                        cid,
                    )
                    .fetch_optional(&mut *tx)
                    .await?;
                    if already_finished.is_some() {
                        target_entry_id = None;
                        target_updated = now;
                    } else {
                        target_entry_id = Some(cid);
                    }
                } else {
                    target_entry_id = None;
                }
            }

            if target_enabled && target_entry_id.is_none() {
                target_entry_id = CatalogRepo::draw_random_remaining_1001_in_tx(tx).await?;
                target_drawn_at = target_entry_id.as_ref().map(|_| now);
                target_updated = now;
            }
            let _ = &mut target_enabled;

            sqlx::query!(
                "UPDATE collection_generators
                 SET enabled = ?, current_entry_id = ?, drawn_at = ?, updated_at = ?
                 WHERE collection_id = ?",
                target_enabled as _,
                target_entry_id,
                target_drawn_at as _,
                target_updated as _,
                ALBUMS_1001_COLLECTION_ID,
            )
            .execute(&mut *tx)
            .await?;
        }

        // Also ensure that if an incoming consumption_log just finished the server's active 1001 album
        // (even without an incoming `collection_generators` entry), we advance the active draw cleanly!
        let local_active = sqlx::query!(
            "SELECT enabled AS \"enabled: bool\", current_entry_id
             FROM collection_generators WHERE collection_id = ?",
            ALBUMS_1001_COLLECTION_ID,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(loc) = local_active
            && let Some(loc_cur_id) = loc.current_entry_id
        {
            let done: Option<i64> = sqlx::query_scalar!(
                "SELECT 1 AS \"found!: i64\" FROM consumption_log
                 WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
                 LIMIT 1",
                loc_cur_id,
            )
            .fetch_optional(&mut *tx)
            .await?;
            if done.is_some() {
                let next_id = if loc.enabled {
                    CatalogRepo::draw_random_remaining_1001_in_tx(tx).await?
                } else {
                    None
                };
                let next_drawn = next_id.as_ref().map(|_| now);
                sqlx::query!(
                    "UPDATE collection_generators
                     SET current_entry_id = ?, drawn_at = ?, updated_at = ?
                     WHERE collection_id = ?",
                    next_id,
                    next_drawn as _,
                    now as _,
                    ALBUMS_1001_COLLECTION_ID,
                )
                .execute(&mut *tx)
                .await?;
            }
        }

        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    pub(super) async fn collect_outgoing_catalog(
        &self,
        since_mode: super::activity::OutgoingSince,
    ) -> Result<(
        (
            Vec<PeerCollectionSpecRecord>,
            Vec<PeerCatalogEntryRecord>,
            Vec<PeerCollectionEntryRecord>,
            Vec<PeerCollectionGeneratorRecord>,
            Vec<PeerConsumptionLogRecord>,
            Vec<PeerComparisonRecord>,
        ),
        Option<DateTime<Utc>>,
    )> {
        use super::activity::{OUTGOING_SYNC_ROW_CAP, OutgoingSince};

        type OutgoingCollectionRow = (
            String,
            String,
            Option<String>,
            Option<String>,
            CollectionMode,
            CollectionRole,
            Option<String>,
            bool,
            Option<String>,
            DateTime<Utc>,
            DateTime<Utc>,
        );
        type OutgoingComparisonRow = (String, DateTime<Utc>, Option<String>, String, DateTime<Utc>);

        let fetch_limit =
            i64::try_from(OUTGOING_SYNC_ROW_CAP.saturating_add(1)).unwrap_or(i64::MAX);
        let mut min_trunc_ts: Option<DateTime<Utc>> = None;
        let mut record_trunc = |ts: Option<DateTime<Utc>>| {
            if let Some(t) = ts {
                min_trunc_ts = Some(min_trunc_ts.map_or(t, |cur| cur.min(t)));
            }
        };
        let push_since = |qb: &mut QueryBuilder<Sqlite>, col: &str, prefix_and: bool| {
            let conj = if prefix_and { " AND " } else { " WHERE " };
            match since_mode {
                OutgoingSince::All => {}
                OutgoingSince::Inclusive(ts) => {
                    qb.push(conj)
                        .push("strftime('%Y-%m-%dT%H:%M:%fZ', ")
                        .push(col)
                        .push(") >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                        .push_bind(ts)
                        .push(")");
                }
                OutgoingSince::StrictlyAfter(ts) => {
                    qb.push(conj)
                        .push("strftime('%Y-%m-%dT%H:%M:%fZ', ")
                        .push(col)
                        .push(") > strftime('%Y-%m-%dT%H:%M:%fZ', ")
                        .push_bind(ts)
                        .push(")");
                }
            }
        };

        // 1. User-created & want manual collections
        let mut collections_qb = QueryBuilder::<Sqlite>::new(
            "SELECT cs.collection_id, e.title, e.summary, cs.slug, cs.mode, cs.collection_role,
                    cs.rule_json, cs.show_in_lists, cs.color, e.created_at, e.updated_at
             FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL
               AND cs.mode = 'manual'
               AND (
                   cs.collection_role = 'want'
                   OR (cs.collection_role = 'list' AND cs.show_in_lists = 1)
               )",
        );
        push_since(&mut collections_qb, "e.updated_at", true);
        collections_qb.push(
            " ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', e.created_at) ASC, cs.collection_id ASC",
        );
        let raw_cols: Vec<OutgoingCollectionRow> =
            collections_qb.build_query_as().fetch_all(self.pool).await?;
        let collections: Vec<PeerCollectionSpecRecord> = raw_cols
            .into_iter()
            .map(|r| PeerCollectionSpecRecord {
                collection_id: r.0,
                title: r.1,
                summary: r.2,
                slug: r.3,
                mode: r.4,
                role: r.5,
                rule_json: r.6,
                show_in_lists: r.7,
                color: r.8,
                created_at: Some(r.9),
                updated_at: Some(r.10),
            })
            .collect();

        // 2. Collection entries (excluding `1001_albums`, `starred`, `feed`, and `tag`)
        let mut memberships_qb = QueryBuilder::<Sqlite>::new(
            "SELECT ce.collection_id, ce.entry_id, ce.position, ce.added_at
             FROM collection_entries ce
             LEFT JOIN collection_specs cs ON cs.collection_id = ce.collection_id
             WHERE ce.collection_id != ",
        );
        memberships_qb
            .push_bind(ALBUMS_1001_COLLECTION_ID)
            .push(" AND ce.collection_id != ")
            .push_bind(STARRED_COLLECTION_ID)
            .push(" AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('feed', 'tag'))");
        push_since(&mut memberships_qb, "ce.added_at", true);
        memberships_qb
            .push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', ce.added_at) ASC, ce.collection_id ASC, ce.entry_id ASC LIMIT ")
            .push_bind(fetch_limit);
        let mut collection_entries: Vec<PeerCollectionEntryRecord> =
            memberships_qb.build_query_as().fetch_all(self.pool).await?;
        if collection_entries.len() > OUTGOING_SYNC_ROW_CAP {
            collection_entries.truncate(OUTGOING_SYNC_ROW_CAP);
            record_trunc(collection_entries.last().map(|r| r.added_at));
        }

        // 3. Collection generators (`1001_albums`)
        let mut generators_qb = QueryBuilder::<Sqlite>::new(
            "SELECT collection_id, enabled, current_entry_id, drawn_at, updated_at
             FROM collection_generators",
        );
        push_since(&mut generators_qb, "updated_at", false);
        let collection_generators: Vec<PeerCollectionGeneratorRecord> =
            generators_qb.build_query_as().fetch_all(self.pool).await?;

        // 4. Consumption log
        let mut logs_qb = QueryBuilder::<Sqlite>::new(
            "SELECT id, entry_id, ts, status, notes, created_at FROM consumption_log",
        );
        push_since(&mut logs_qb, "created_at", false);
        logs_qb
            .push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', created_at) ASC, id ASC LIMIT ")
            .push_bind(fetch_limit);
        let mut consumption_log: Vec<PeerConsumptionLogRecord> =
            logs_qb.build_query_as().fetch_all(self.pool).await?;
        if consumption_log.len() > OUTGOING_SYNC_ROW_CAP {
            consumption_log.truncate(OUTGOING_SYNC_ROW_CAP);
            record_trunc(consumption_log.last().and_then(|r| r.created_at));
        }

        // 5. Comparisons
        let mut comparisons_qb = QueryBuilder::<Sqlite>::new(
            "SELECT id, ts, scope_collection_id, ordering_json, created_at FROM comparisons",
        );
        push_since(&mut comparisons_qb, "created_at", false);
        comparisons_qb
            .push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', created_at) ASC, id ASC LIMIT ")
            .push_bind(fetch_limit);
        let mut raw_cmps: Vec<OutgoingComparisonRow> =
            comparisons_qb.build_query_as().fetch_all(self.pool).await?;
        if raw_cmps.len() > OUTGOING_SYNC_ROW_CAP {
            raw_cmps.truncate(OUTGOING_SYNC_ROW_CAP);
            record_trunc(raw_cmps.last().map(|r| r.4));
        }
        let comparisons: Vec<PeerComparisonRecord> = raw_cmps
            .into_iter()
            .filter_map(|(id, ts, scope, ord_json, created)| {
                let ordering: Vec<Vec<String>> = serde_json::from_str(&ord_json).ok()?;
                Some(PeerComparisonRecord {
                    id,
                    ts,
                    scope_collection_id: scope,
                    ordering,
                    created_at: Some(created),
                })
            })
            .collect();

        // 6. Non-collection_specs catalog entries + any entries referenced by outgoing
        //    `collection_entries`, `collection_generators`, `consumption_log`, or `comparisons`
        //    (even if they also have an inbox_state or older `updated_at`, preventing dangling FK/IDs!)
        let mut ent_qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year,
                    e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, e.updated_at
             FROM entries e
             WHERE NOT EXISTS (
                 SELECT 1 FROM entry_state es
                 WHERE es.entry_id = e.id AND es.inbox_state IN ('unread', 'dismissed')
             )
               AND NOT EXISTS (
                 SELECT 1 FROM collection_specs cs WHERE cs.collection_id = e.id
             )",
        );
        match since_mode {
            OutgoingSince::Inclusive(_) | OutgoingSince::StrictlyAfter(_) => {
                push_since(&mut ent_qb, "e.updated_at", true);
            }
            OutgoingSince::All => {
                // Omit untouched static 1001-album pool rows on initial sync unless active/logged/ranked/merged
                ent_qb
                    .push(
                        " AND (
                        e.merged_into_id IS NOT NULL
                        OR NOT EXISTS (
                            SELECT 1 FROM collection_entries ce_pool
                            WHERE ce_pool.collection_id = ",
                    )
                    .push_bind(ALBUMS_1001_COLLECTION_ID)
                    .push(
                        " AND ce_pool.entry_id = e.id
                        )
                        OR EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id)
                        OR EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.current_entry_id = e.id)
                        OR EXISTS (
                            SELECT 1 FROM collection_entries ce_other
                            WHERE ce_other.entry_id = e.id AND ce_other.collection_id != ",
                    )
                    .push_bind(ALBUMS_1001_COLLECTION_ID)
                    .push("))");
            }
        }
        ent_qb
            .push(" ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', e.updated_at) ASC, e.id ASC LIMIT ")
            .push_bind(fetch_limit);

        let mut raw_entries: Vec<OutgoingRawEntryRow> =
            ent_qb.build_query_as().fetch_all(self.pool).await?;
        if raw_entries.len() > OUTGOING_SYNC_ROW_CAP {
            raw_entries.truncate(OUTGOING_SYNC_ROW_CAP);
            record_trunc(raw_entries.last().map(|r| r.12));
        }

        let mut present_ids: HashSet<String> = raw_entries.iter().map(|r| r.0.clone()).collect();
        let mut referenced_ids: Vec<String> = Vec::new();
        let mut seen_ref: HashSet<String> = HashSet::new();
        let mut record_ref = |id: &str| {
            if !id.is_empty() && !present_ids.contains(id) && seen_ref.insert(id.to_owned()) {
                referenced_ids.push(id.to_owned());
            }
        };
        for ce in &collection_entries {
            record_ref(&ce.collection_id);
            record_ref(&ce.entry_id);
        }
        for cg in &collection_generators {
            if let Some(ref cur_id) = cg.current_entry_id {
                record_ref(cur_id);
            }
        }
        for log in &consumption_log {
            record_ref(&log.entry_id);
        }
        for cmp in &comparisons {
            if let Some(ref sc) = cmp.scope_collection_id {
                record_ref(sc);
            }
            for grp in &cmp.ordering {
                for id in grp {
                    record_ref(id);
                }
            }
        }
        for row in &raw_entries {
            if let Some(ref mid) = row.10 {
                record_ref(mid);
            }
        }

        let mut pending_refs = referenced_ids;
        for _ in 0..4 {
            if pending_refs.is_empty() {
                break;
            }
            let mut next_hop_refs: Vec<String> = Vec::new();
            for chunk in pending_refs.chunks(400) {
                let mut rqb = QueryBuilder::<Sqlite>::new(
                    "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year,
                            e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, e.updated_at
                     FROM entries e
                     WHERE NOT EXISTS (SELECT 1 FROM collection_specs cs WHERE cs.collection_id = e.id)
                       AND e.id IN (",
                );
                let mut sep = rqb.separated(", ");
                for id in chunk {
                    sep.push_bind(id.as_str());
                }
                sep.push_unseparated(")");
                let extra_rows: Vec<OutgoingRawEntryRow> =
                    rqb.build_query_as().fetch_all(self.pool).await?;
                for row in extra_rows {
                    if let Some(ref mid) = row.10
                        && !mid.is_empty()
                        && !present_ids.contains(mid)
                        && seen_ref.insert(mid.clone())
                    {
                        next_hop_refs.push(mid.clone());
                    }
                    if present_ids.insert(row.0.clone()) {
                        raw_entries.push(row);
                    }
                }
            }
            pending_refs = next_hop_refs;
        }

        let entry_ids: Vec<&str> = raw_entries.iter().map(|r| r.0.as_str()).collect();
        let mut ident_map: BTreeMap<String, Vec<EntryIdentifier>> = BTreeMap::new();
        for chunk in entry_ids.chunks(400) {
            let mut iqb = QueryBuilder::<Sqlite>::new(
                "SELECT entry_id, scheme, value FROM entry_identifiers WHERE entry_id IN (",
            );
            let mut sep = iqb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(") ORDER BY scheme ASC, value ASC");
            let irows: Vec<(String, String, String)> =
                iqb.build_query_as().fetch_all(self.pool).await?;
            for (eid, scheme, value) in irows {
                ident_map.entry(eid).or_default().push(EntryIdentifier { scheme, value });
            }
        }

        let catalog_entries: Vec<PeerCatalogEntryRecord> = raw_entries
            .into_iter()
            .map(|r| {
                let creators: Vec<String> = serde_json::from_str(&r.3).unwrap_or_default();
                let metadata: Option<serde_json::Value> =
                    serde_json::from_str::<serde_json::Value>(&r.9)
                        .ok()
                        .filter(|v| v.as_object().is_some_and(|m| !m.is_empty()));
                let identifiers = ident_map.remove(&r.0).unwrap_or_default();
                PeerCatalogEntryRecord {
                    id: r.0,
                    kind: r.1,
                    title: r.2,
                    creators,
                    url: r.4,
                    published_at: r.5,
                    release_year: r.6,
                    summary: r.7,
                    thumbnail_url: r.8,
                    metadata,
                    identifiers,
                    merged_into_id: r.10,
                    created_at: Some(r.11),
                    updated_at: Some(r.12),
                }
            })
            .collect();

        Ok((
            (
                collections,
                catalog_entries,
                collection_entries,
                collection_generators,
                consumption_log,
                comparisons,
            ),
            min_trunc_ts,
        ))
    }
}
