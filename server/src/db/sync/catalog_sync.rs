use std::collections::{BTreeMap, HashSet};

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
        ActivityEntityType, CatalogRepo, CollectionMode, CollectionRole, ConsumptionStatus,
        EntryIdentifier, EntryKind, UpsertEntryInput,
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
        // 1. Collections (`list` / `want` manual collections)
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
            let col_id = if !col.collection_id.trim().is_empty() {
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

            // Skip if an existing non-manual or feed/tag collection owns this ID or slug
            let existing_role: Option<(CollectionMode, CollectionRole)> = sqlx::query_as(
                "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ? OR (slug IS NOT NULL AND slug = ?) LIMIT 1",
            )
            .bind(&col_id)
            .bind(&slug_opt)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some((ex_mode, ex_role)) = existing_role
                && (ex_mode != CollectionMode::Manual
                    || matches!(
                        ex_role,
                        CollectionRole::Feed | CollectionRole::Tag | CollectionRole::System
                    ))
            {
                continue;
            }

            let created = col.created_at.unwrap_or(now);
            let incoming_updated = col.updated_at.unwrap_or(now);
            let ingested_updated = incoming_updated.max(now);
            sqlx::query(
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
            )
            .bind(&col_id)
            .bind(title)
            .bind(col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()))
            .bind(created)
            .bind(ingested_updated)
            .bind(incoming_updated)
            .bind(incoming_updated)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO collection_specs
                 (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
                 VALUES (?, ?, 'manual', ?, NULL, ?, ?)
                 ON CONFLICT(collection_id) DO UPDATE SET
                    show_in_lists = excluded.show_in_lists,
                    color = COALESCE(excluded.color, collection_specs.color)",
            )
            .bind(&col_id)
            .bind(&slug_opt)
            .bind(col.role)
            .bind(col.show_in_lists)
            .bind(col.color.as_deref().map(str::trim).filter(|s| !s.is_empty()))
            .execute(&mut *tx)
            .await?;

            if let Some(s) = slug_opt {
                sqlx::query(
                    "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                     VALUES ('slug', ?, ?)",
                )
                .bind(s)
                .bind(&col_id)
                .execute(&mut *tx)
                .await?;
            }
        }

        // 2. Active catalog entries (non-merged first, then merge redirects)
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
                sqlx::query_scalar("SELECT merged_into_id FROM entries WHERE id = ?")
                    .bind(dup_id)
                    .fetch_optional(&mut *tx)
                    .await?;
            match existing_dup {
                None => {
                    // Insert lightweight tombstone redirect row
                    sqlx::query(
                        "INSERT INTO entries (id, kind, title, merged_into_id, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?)",
                    )
                    .bind(dup_id)
                    .bind(merged_entry.kind)
                    .bind(merged_entry.title.trim())
                    .bind(&canon_resolved)
                    .bind(merged_entry.created_at.unwrap_or(now))
                    .bind(now)
                    .execute(&mut *tx)
                    .await?;
                }
                Some(None) => {
                    CatalogRepo::fold_live_duplicate_in_tx(tx, dup_id, &canon_resolved, now)
                        .await?;
                }
                Some(Some(_)) => {}
            }
        }

        // 3. Collection memberships (`collection_entries`, respecting tombstones & mode)
        for ce in collection_entries {
            let Some(col_id) = CatalogRepo::follow_merged_id_in_tx(tx, &ce.collection_id).await?
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
            let spec_mode: Option<(CollectionMode, CollectionRole)> = sqlx::query_as(
                "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
            )
            .bind(&col_id)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some((mode, role)) = spec_mode
                && (mode != CollectionMode::Manual
                    || matches!(role, CollectionRole::Feed | CollectionRole::Tag))
            {
                continue;
            }

            let nat_key = collection_entry_natural_key(&col_id, &entry_id);
            let tomb_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
                "SELECT deleted_at FROM sync_tombstones
                 WHERE entity_type = 'collection_entry' AND natural_key = ?",
            )
            .bind(&nat_key)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(del_at) = tomb_ts {
                if ce.added_at <= del_at {
                    continue;
                }
                Self::clear_tombstone(tx, ActivityEntityType::CollectionEntry, &nat_key).await?;
            }

            let ingested_added_at = ce.added_at.max(now);
            sqlx::query(
                "INSERT INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                    position = COALESCE(excluded.position, collection_entries.position),
                    added_at = CASE
                        WHEN excluded.position IS NOT NULL AND excluded.position IS NOT collection_entries.position
                        THEN excluded.added_at
                        ELSE collection_entries.added_at
                    END",
            )
            .bind(&col_id)
            .bind(&entry_id)
            .bind(ce.position)
            .bind(ingested_added_at)
            .execute(&mut *tx)
            .await?;
        }

        // 4. Consumption Log (append-only + want-queue promotion + parent engagement)
        for log in consumption_log {
            let Some(canon_entry) = CatalogRepo::follow_merged_id_in_tx(tx, &log.entry_id).await?
            else {
                continue;
            };
            let _ = CatalogRepo::log_consumption_in_tx(
                tx,
                &canon_entry,
                log.status,
                log.notes.as_deref(),
                log.ts,
                now,
            )
            .await?;
        }

        // 5. Comparisons (append-only, canonicalized through `merged_into_id`)
        for comp in comparisons {
            let scope_id = match comp
                .scope_collection_id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
            {
                Some(raw_scope) => {
                    let Some(resolved) = CatalogRepo::follow_merged_id_in_tx(tx, raw_scope).await?
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
            let comp_id = if comp.id.trim().is_empty() {
                deterministic_comparison_id(
                    scope_id.as_deref(),
                    comp.ts.timestamp_millis(),
                    &resolved_groups,
                )
            } else {
                comp.id.trim().to_owned()
            };
            sqlx::query(
                "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at)
                 VALUES (?, ?, ?, ?, ?)",
            )
            .bind(&comp_id)
            .bind(comp.ts)
            .bind(&scope_id)
            .bind(&ord_json)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }

        // 6. Collection Generators (`1001_albums` LWW + never-revive-consumed-album invariant)
        for inc_gen in collection_generators {
            type LocalGenRow = (bool, Option<String>, Option<DateTime<Utc>>, DateTime<Utc>);
            if inc_gen.collection_id.trim() != ALBUMS_1001_COLLECTION_ID {
                continue;
            }
            let local_gen: Option<LocalGenRow> = sqlx::query_as(
                "SELECT enabled, current_entry_id, drawn_at, updated_at
                 FROM collection_generators WHERE collection_id = ?",
            )
            .bind(ALBUMS_1001_COLLECTION_ID)
            .fetch_optional(&mut *tx)
            .await?;
            let (mut target_enabled, mut target_entry_id, mut target_drawn_at, mut target_updated) =
                if let Some((loc_en, loc_cur, loc_drawn, loc_upd)) = local_gen {
                    if inc_gen.updated_at > loc_upd
                        || (inc_gen.updated_at == loc_upd
                            && loc_cur.is_none()
                            && inc_gen.current_entry_id.is_some())
                    {
                        (
                            inc_gen.enabled,
                            inc_gen.current_entry_id.clone(),
                            inc_gen.drawn_at,
                            inc_gen.updated_at.max(now),
                        )
                    } else {
                        (loc_en, loc_cur, loc_drawn, loc_upd)
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
                    let already_finished: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM consumption_log
                         WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
                         LIMIT 1",
                    )
                    .bind(&cid)
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

            sqlx::query(
                "UPDATE collection_generators
                 SET enabled = ?, current_entry_id = ?, drawn_at = ?, updated_at = ?
                 WHERE collection_id = ?",
            )
            .bind(target_enabled)
            .bind(&target_entry_id)
            .bind(target_drawn_at)
            .bind(target_updated)
            .bind(ALBUMS_1001_COLLECTION_ID)
            .execute(&mut *tx)
            .await?;
        }

        // Also ensure that if an incoming consumption_log just finished the server's active 1001 album
        // (even without an incoming `collection_generators` entry), we advance the active draw cleanly!
        let local_active: Option<(bool, Option<String>)> = sqlx::query_as(
            "SELECT enabled, current_entry_id FROM collection_generators WHERE collection_id = ?",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some((loc_enabled, Some(loc_cur_id))) = local_active {
            let done: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM consumption_log
                 WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
                 LIMIT 1",
            )
            .bind(&loc_cur_id)
            .fetch_optional(&mut *tx)
            .await?;
            if done.is_some() {
                let next_id = if loc_enabled {
                    CatalogRepo::draw_random_remaining_1001_in_tx(tx).await?
                } else {
                    None
                };
                let next_drawn = next_id.as_ref().map(|_| now);
                sqlx::query(
                    "UPDATE collection_generators
                     SET current_entry_id = ?, drawn_at = ?, updated_at = ?
                     WHERE collection_id = ?",
                )
                .bind(&next_id)
                .bind(next_drawn)
                .bind(now)
                .bind(ALBUMS_1001_COLLECTION_ID)
                .execute(&mut *tx)
                .await?;
            }
        }

        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    pub(super) async fn collect_outgoing_catalog(
        &self,
        inclusive_since: Option<DateTime<Utc>>,
    ) -> Result<(
        Vec<PeerCollectionSpecRecord>,
        Vec<PeerCatalogEntryRecord>,
        Vec<PeerCollectionEntryRecord>,
        Vec<PeerCollectionGeneratorRecord>,
        Vec<PeerConsumptionLogRecord>,
        Vec<PeerComparisonRecord>,
    )> {
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

        // 1. User-created & want manual collections
        let mut collections_qb = QueryBuilder::<Sqlite>::new(
            "SELECT cs.collection_id, e.title, e.summary, cs.slug, cs.mode, cs.collection_role,
                    cs.rule_json, cs.show_in_lists, cs.color, e.created_at, e.updated_at
             FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL
               AND cs.mode = 'manual'
               AND cs.collection_role IN ('list', 'want')",
        );
        if let Some(since) = inclusive_since {
            collections_qb
                .push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', e.updated_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                .push_bind(since)
                .push(")");
        }
        collections_qb.push(" ORDER BY e.created_at ASC, cs.collection_id ASC");
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

        // 2. Non-inbox, non-collection_specs catalog entries
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
        if let Some(since) = inclusive_since {
            ent_qb
                .push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', e.updated_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                .push_bind(since)
                .push(")");
        } else {
            // Omit untouched static 1001-album pool rows on initial sync unless active/logged/ranked/merged
            ent_qb.push(
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
        ent_qb.push(" ORDER BY e.updated_at ASC, e.id ASC LIMIT 5000");

        #[allow(clippy::type_complexity)]
        let raw_entries: Vec<(
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
        )> = ent_qb.build_query_as().fetch_all(self.pool).await?;

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

        // 3. Collection entries (excluding `1001_albums`, `starred`, `feed`, and `tag`)
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
        if let Some(since) = inclusive_since {
            memberships_qb
                .push(" AND strftime('%Y-%m-%dT%H:%M:%fZ', ce.added_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                .push_bind(since)
                .push(")");
        }
        memberships_qb
            .push(" ORDER BY ce.added_at ASC, ce.collection_id ASC, ce.entry_id ASC LIMIT 10000");
        let collection_entries: Vec<PeerCollectionEntryRecord> =
            memberships_qb.build_query_as().fetch_all(self.pool).await?;

        // 4. Collection generators (`1001_albums`)
        let mut generators_qb = QueryBuilder::<Sqlite>::new(
            "SELECT collection_id, enabled, current_entry_id, drawn_at, updated_at
             FROM collection_generators",
        );
        if let Some(since) = inclusive_since {
            generators_qb
                .push(" WHERE strftime('%Y-%m-%dT%H:%M:%fZ', updated_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                .push_bind(since)
                .push(")");
        }
        let collection_generators: Vec<PeerCollectionGeneratorRecord> =
            generators_qb.build_query_as().fetch_all(self.pool).await?;

        // 5. Consumption log
        let mut logs_qb = QueryBuilder::<Sqlite>::new(
            "SELECT id, entry_id, ts, status, notes, created_at FROM consumption_log",
        );
        if let Some(since) = inclusive_since {
            logs_qb
                .push(" WHERE strftime('%Y-%m-%dT%H:%M:%fZ', created_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                .push_bind(since)
                .push(")");
        }
        logs_qb.push(" ORDER BY ts ASC, id ASC LIMIT 10000");
        let consumption_log: Vec<PeerConsumptionLogRecord> =
            logs_qb.build_query_as().fetch_all(self.pool).await?;

        // 6. Comparisons
        let mut comparisons_qb = QueryBuilder::<Sqlite>::new(
            "SELECT id, ts, scope_collection_id, ordering_json, created_at FROM comparisons",
        );
        if let Some(since) = inclusive_since {
            comparisons_qb
                .push(" WHERE strftime('%Y-%m-%dT%H:%M:%fZ', created_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ")
                .push_bind(since)
                .push(")");
        }
        comparisons_qb.push(" ORDER BY ts ASC, id ASC LIMIT 10000");
        let raw_cmps: Vec<OutgoingComparisonRow> =
            comparisons_qb.build_query_as().fetch_all(self.pool).await?;
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

        Ok((
            collections,
            catalog_entries,
            collection_entries,
            collection_generators,
            consumption_log,
            comparisons,
        ))
    }
}
