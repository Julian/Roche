use std::collections::HashSet;

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::super::{
    STARRED_COLLECTION_ID, SyncRepo, WANT_TO_READ_COLLECTION_ID, WANT_TO_READ_SLUG,
    hlc::{
        HlcClock, clamp_incoming_timestamp, clamp_incoming_timestamp_opt,
        clamp_incoming_upper_timestamp, resolve_or_synthesize_hlc,
    },
    identity::{
        canonical_ordering_json, collection_entry_natural_key,
        deterministic_auto_consumption_log_id, deterministic_comparison_id,
        deterministic_consumption_log_id, deterministic_system_collection_id,
        is_protected_system_collection_id,
    },
    models::{
        PreparedSyncEntry, SyncCollectionEntryRecord, SyncCollectionGeneratorRecord,
        SyncCollectionSpec, SyncComparisonRecord, SyncConsumptionLogRecord,
    },
};
use super::MergeContext;
use crate::{
    db::{
        ActivityAction, ActivityEntityType, CatalogRepo, CollectionMode, CollectionRole,
        ConsumptionStatus, EntryKind, HexColor,
        entries_core::{self, EntryInsertParams},
    },
    ranking::validate_ordering,
};

#[derive(sqlx::FromRow)]
struct ExistingCollectionSpecRow {
    collection_id: String,
    mode: CollectionMode,
    collection_role: CollectionRole,
}

#[derive(sqlx::FromRow)]
struct CollectionSpecModeRole {
    mode: CollectionMode,
    collection_role: CollectionRole,
}

#[derive(sqlx::FromRow)]
struct ExistingGeneratorRow {
    enabled: bool,
    current_entry_id: Option<String>,
    drawn_at: Option<DateTime<Utc>>,
    updated_at: DateTime<Utc>,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct ActiveGeneratorRow {
    collection_id: String,
    enabled: bool,
    current_entry_id: String,
}

struct GeneratorTargetState {
    enabled: bool,
    current_entry_id: Option<String>,
    drawn_at: Option<DateTime<Utc>>,
    updated_at: DateTime<Utc>,
    hlc: String,
    local_won_conflict: bool,
}

impl SyncRepo<'_> {
    #[allow(clippy::too_many_lines)]
    pub(super) async fn merge_single_collection_entry_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        cs: &SyncCollectionSpec,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let rec = &prep.record;
        let title = rec.title.trim();
        if title.is_empty() {
            return Ok(());
        }
        let slug_opt =
            cs.slug.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_lowercase);
        let Some(target_id) = Self::resolve_manual_collection_target_id_in_tx(
            tx,
            rec.id.trim(),
            slug_opt.as_deref(),
            ctx,
        )
        .await?
        else {
            return Ok(());
        };

        let created = clamp_incoming_timestamp(rec.created_at.unwrap_or(ctx.now), ctx.now);
        let entry_hlc = resolve_or_synthesize_hlc(
            &rec.hlc,
            rec.updated_at.or(rec.created_at),
            ctx.now,
            ctx.peer_id,
        );

        let existing_entry: Option<(EntryKind, Option<String>, String)> =
            sqlx::query_as("SELECT kind, merged_into_id, hlc FROM entries WHERE id = ?")
                .bind(&target_id)
                .fetch_optional(&mut *tx)
                .await?;
        if let Some((ex_kind, ex_merged, _)) = &existing_entry
            && (*ex_kind != EntryKind::Collection || ex_merged.is_some())
        {
            return Ok(());
        }
        let is_protected = is_protected_system_collection_id(&target_id);
        let incoming_wins = existing_entry
            .as_ref()
            .is_none_or(|(_, _, ex_hlc)| entry_hlc.as_str() >= ex_hlc.as_str());

        // Check `'entry'` tombstone before upserting manual collection
        if !is_protected
            && Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::Entry,
                &target_id,
                &entry_hlc,
            )
            .await?
        {
            return Ok(());
        }

        let validated_color = HexColor::from_stored_tolerant(cs.color.as_deref());
        let clean_color = validated_color.as_ref().map(HexColor::as_str);

        if is_protected {
            // Protected collections (`Starred`, `Want to Read`, `Up Next`) keep immutable
            // title/slug/role/visibility; only allow updating optional summary/color if empty.
            if existing_entry.is_none() {
                sqlx::query(
                    "INSERT INTO entries (id, kind, title, summary, created_at, updated_at, hlc)
                     VALUES (?, 'collection', ?, ?, ?, ?, ?)",
                )
                .bind(&target_id)
                .bind(title)
                .bind(prep.clean_summary.as_deref())
                .bind(created)
                .bind(ctx.now)
                .bind(&entry_hlc)
                .execute(&mut *tx)
                .await?;
            }
        } else {
            sqlx::query(
                "INSERT INTO entries (id, kind, title, summary, created_at, updated_at, hlc)
                 VALUES (?, 'collection', ?, ?, ?, ?, ?)
                 ON CONFLICT(id) DO UPDATE SET
                     title = CASE WHEN excluded.hlc >= entries.hlc THEN excluded.title ELSE entries.title END,
                     summary = COALESCE(excluded.summary, entries.summary),
                     updated_at = CASE WHEN excluded.hlc >= entries.hlc THEN excluded.updated_at ELSE entries.updated_at END,
                     hlc = CASE WHEN excluded.hlc >= entries.hlc THEN excluded.hlc ELSE entries.hlc END",
            )
            .bind(&target_id)
            .bind(title)
            .bind(prep.clean_summary.as_deref())
            .bind(created)
            .bind(ctx.now)
            .bind(&entry_hlc)
            .execute(&mut *tx)
            .await?;
        }

        let (effective_slug, effective_role, effective_show_in_lists) =
            if target_id == STARRED_COLLECTION_ID {
                (Some("starred".to_owned()), CollectionRole::List, true)
            } else if target_id == WANT_TO_READ_COLLECTION_ID {
                (Some(WANT_TO_READ_SLUG.to_owned()), CollectionRole::Want, false)
            } else {
                (slug_opt, cs.role, cs.show_in_lists)
            };

        if existing_entry.is_none() || (!is_protected && incoming_wins) {
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
            .bind(&effective_slug)
            .bind(effective_role)
            .bind(effective_show_in_lists)
            .bind(clean_color)
            .execute(&mut *tx)
            .await?;

            if let Some(slug) = effective_slug {
                entries_core::upsert_entry_identifier_in_tx(tx, &target_id, "slug", &slug).await?;
            }
        }
        Ok(())
    }

    async fn resolve_manual_collection_target_id_in_tx(
        tx: &mut SqliteConnection,
        rec_id: &str,
        slug_opt: Option<&str>,
        ctx: &mut MergeContext<'_>,
    ) -> Result<Option<String>> {
        let requested_id = if !rec_id.is_empty() {
            rec_id.to_owned()
        } else if let Some(s) = slug_opt {
            if s == "starred" || s == "up_next" || s.starts_with("want:") {
                deterministic_system_collection_id(s)
            } else {
                deterministic_system_collection_id(&format!("custom:{s}"))
            }
        } else {
            return Ok(None);
        };

        let existing_spec: Option<ExistingCollectionSpecRow> = sqlx::query_as(
            "SELECT collection_id, mode, collection_role
             FROM collection_specs
             WHERE collection_id = ? OR (slug IS NOT NULL AND LOWER(slug) = LOWER(?))
             ORDER BY CASE WHEN collection_id = ? THEN 0 ELSE 1 END ASC
             LIMIT 1",
        )
        .bind(&requested_id)
        .bind(slug_opt)
        .bind(&requested_id)
        .fetch_optional(&mut *tx)
        .await?;

        let target_id = if let Some(ex) = existing_spec {
            if ex.mode != CollectionMode::Manual
                || matches!(
                    ex.collection_role,
                    CollectionRole::Feed | CollectionRole::Tag | CollectionRole::System
                )
            {
                return Ok(None);
            }
            if ex.collection_id != requested_id {
                ctx.id_resolver.record_alias(&requested_id, &ex.collection_id);
            }
            ex.collection_id
        } else {
            requested_id
        };
        ctx.id_resolver.record_alias(rec_id, &target_id);
        Ok(Some(target_id))
    }

    pub(super) async fn apply_pending_merges_in_tx(
        tx: &mut SqliteConnection,
        pending_merges: &[(&PreparedSyncEntry, String)],
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        for (merged_prep, raw_target) in pending_merges {
            let rec = &merged_prep.record;
            let raw_dup = rec.id.trim();
            if raw_dup.is_empty() {
                continue;
            }
            let Some(canon_resolved) = ctx.id_resolver.resolve_live_in_tx(tx, raw_target).await?
            else {
                continue;
            };
            if raw_dup == canon_resolved || is_protected_system_collection_id(raw_dup) {
                continue;
            }
            let entry_hlc =
                resolve_or_synthesize_hlc(&rec.hlc, rec.updated_at, ctx.now, ctx.peer_id);
            let existing_dup: Option<(Option<String>, EntryKind, String)> =
                sqlx::query_as("SELECT merged_into_id, kind, hlc FROM entries WHERE id = ?")
                    .bind(raw_dup)
                    .fetch_optional(&mut *tx)
                    .await?;
            let canon_kind: Option<EntryKind> =
                sqlx::query_scalar("SELECT kind FROM entries WHERE id = ?")
                    .bind(&canon_resolved)
                    .fetch_optional(&mut *tx)
                    .await?;
            let Some(canon_kind) = canon_kind else {
                continue;
            };
            match existing_dup {
                None => {
                    if (rec.kind == EntryKind::Collection) != (canon_kind == EntryKind::Collection)
                    {
                        continue;
                    }
                    let clean_title = rec.title.trim();
                    let title = if clean_title.is_empty() { raw_dup } else { clean_title };
                    let created =
                        clamp_incoming_timestamp(rec.created_at.unwrap_or(ctx.now), ctx.now);
                    entries_core::insert_entry_row_in_tx(
                        tx,
                        &EntryInsertParams {
                            merged_into_id: Some(&canon_resolved),
                            hlc: Some(&entry_hlc),
                            ..EntryInsertParams::minimal(raw_dup, rec.kind, title, created, ctx.now)
                        },
                    )
                    .await?;
                }
                Some((None, dup_kind, dup_hlc)) => {
                    if (dup_kind == EntryKind::Collection) != (canon_kind == EntryKind::Collection)
                        || entry_hlc.as_str() < dup_hlc.as_str()
                    {
                        continue;
                    }
                    if CatalogRepo::validate_mergeable_specs_in_tx(tx, raw_dup, &canon_resolved)
                        .await
                        .is_ok()
                    {
                        CatalogRepo::fold_live_duplicate_in_tx(
                            tx,
                            raw_dup,
                            &canon_resolved,
                            ctx.now,
                        )
                        .await?;
                    }
                }
                Some((Some(_), _, _)) => {}
            }
            ctx.id_resolver.record_alias(raw_dup, &canon_resolved);
        }
        Ok(())
    }

    pub(super) async fn merge_incoming_collection_entries_in_tx(
        tx: &mut SqliteConnection,
        memberships: &[SyncCollectionEntryRecord],
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        for ce in memberships {
            let Some(col_id) = ctx.id_resolver.resolve_live_in_tx(tx, &ce.collection_id).await?
            else {
                continue;
            };
            let Some(entry_id) = ctx.id_resolver.resolve_live_in_tx(tx, &ce.entry_id).await? else {
                continue;
            };
            if col_id == entry_id {
                continue;
            }

            let spec_info: Option<CollectionSpecModeRole> = sqlx::query_as(
                "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
            )
            .bind(&col_id)
            .fetch_optional(&mut *tx)
            .await?;
            if spec_info.as_ref().is_some_and(|s| s.mode == CollectionMode::Dynamic) {
                continue;
            }
            if CatalogRepo::would_introduce_collection_cycle_in_tx(tx, &col_id, &entry_id).await? {
                continue;
            }

            let safe_added_at = clamp_incoming_timestamp(ce.added_at, ctx.now);
            let ce_hlc =
                resolve_or_synthesize_hlc(&ce.hlc, Some(safe_added_at), ctx.now, ctx.peer_id);
            let nat_key = collection_entry_natural_key(&col_id, &entry_id);
            if Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::CollectionEntry,
                &nat_key,
                &ce_hlc,
            )
            .await?
            {
                continue;
            }

            sqlx::query(
                "INSERT INTO collection_entries (collection_id, entry_id, position, added_at, hlc)
                 VALUES (?, ?, ?, ?, ?)
                 ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                     position = CASE
                         WHEN excluded.hlc >= collection_entries.hlc
                         THEN COALESCE(excluded.position, collection_entries.position)
                         ELSE collection_entries.position
                     END,
                     added_at = CASE
                         WHEN excluded.hlc > collection_entries.hlc THEN excluded.added_at
                         ELSE collection_entries.added_at
                     END,
                     hlc = CASE
                         WHEN excluded.hlc > collection_entries.hlc THEN excluded.hlc
                         ELSE collection_entries.hlc
                     END
                 WHERE excluded.hlc > collection_entries.hlc
                    OR (excluded.position IS NOT NULL AND excluded.position IS NOT collection_entries.position)",
            )
            .bind(&col_id)
            .bind(&entry_id)
            .bind(ce.position)
            .bind(safe_added_at)
            .bind(&ce_hlc)
            .execute(&mut *tx)
            .await?;

            // Auto-upgrade RSS publication feed to Podcast when an audio episode joins it
            if spec_info.is_some_and(|s| {
                s.mode == CollectionMode::Feed && s.collection_role == CollectionRole::Feed
            }) {
                Self::maybe_upgrade_feed_to_podcast_in_tx(tx, &col_id, &entry_id).await?;
            }
        }
        Ok(())
    }

    async fn maybe_upgrade_feed_to_podcast_in_tx(
        tx: &mut SqliteConnection,
        col_id: &str,
        entry_id: &str,
    ) -> Result<()> {
        let is_audio_entry: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM entries e
             LEFT JOIN entry_content ec ON ec.entry_id = e.id
             WHERE e.id = ? AND (e.kind = 'podcast_episode' OR ec.content_type = 'audio')
             LIMIT 1",
        )
        .bind(entry_id)
        .fetch_optional(&mut *tx)
        .await?;
        if is_audio_entry.is_some() {
            sqlx::query(
                "UPDATE feed_sources SET source_type = 'podcast'
                 WHERE collection_id = ? AND source_type = 'rss'",
            )
            .bind(col_id)
            .execute(&mut *tx)
            .await?;
            sqlx::query(
                "UPDATE entries SET kind = 'podcast'
                 WHERE id = ? AND kind = 'publication'",
            )
            .bind(col_id)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    pub(super) async fn merge_incoming_consumption_log_in_tx(
        tx: &mut SqliteConnection,
        logs: &[SyncConsumptionLogRecord],
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        for log in logs {
            Self::merge_single_consumption_log_in_tx(tx, log, ctx).await?;
        }
        Ok(())
    }

    async fn merge_single_consumption_log_in_tx(
        tx: &mut SqliteConnection,
        log: &SyncConsumptionLogRecord,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let raw_ent = log.entry_id.trim();
        let Some(entry_id) = ctx.id_resolver.resolve_live_in_tx(tx, raw_ent).await? else {
            return Ok(());
        };
        let log_hlc = resolve_or_synthesize_hlc(
            &log.hlc,
            log.created_at.or(Some(log.ts)),
            ctx.now,
            ctx.peer_id,
        );

        let trimmed_id = log.id.trim();
        if !trimmed_id.is_empty()
            && Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::ConsumptionLog,
                trimmed_id,
                &log_hlc,
            )
            .await?
        {
            return Ok(());
        }
        let is_auto_id = log.status == ConsumptionStatus::Consumed
            && (trimmed_id == deterministic_auto_consumption_log_id(raw_ent)
                || trimmed_id == deterministic_auto_consumption_log_id(&entry_id));
        let resolved_log_id = if is_auto_id {
            deterministic_auto_consumption_log_id(&entry_id)
        } else if trimmed_id.is_empty()
            || (raw_ent != entry_id
                && trimmed_id
                    == deterministic_consumption_log_id(
                        raw_ent,
                        log.ts.timestamp_millis(),
                        log.status.as_str(),
                    ))
        {
            deterministic_consumption_log_id(
                &entry_id,
                log.ts.timestamp_millis(),
                log.status.as_str(),
            )
        } else {
            trimmed_id.to_owned()
        };
        if resolved_log_id != trimmed_id
            && Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::ConsumptionLog,
                &resolved_log_id,
                &log_hlc,
            )
            .await?
        {
            return Ok(());
        }

        let safe_log_ts = clamp_incoming_upper_timestamp(log.ts, ctx.now);
        Self::upsert_synced_consumption_log_in_tx(
            tx,
            &resolved_log_id,
            &entry_id,
            log,
            safe_log_ts,
            &log_hlc,
            ctx.now,
        )
        .await
    }

    async fn upsert_synced_consumption_log_in_tx(
        tx: &mut SqliteConnection,
        resolved_log_id: &str,
        entry_id: &str,
        log: &SyncConsumptionLogRecord,
        safe_log_ts: DateTime<Utc>,
        log_hlc: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let existing_row: Option<(String, ConsumptionStatus)> =
            sqlx::query_as("SELECT hlc, status FROM consumption_log WHERE id = ?")
                .bind(resolved_log_id)
                .fetch_optional(&mut *tx)
                .await?;
        if let Some((ex_hlc, ex_status)) = existing_row {
            if log_hlc > ex_hlc.as_str() {
                let clean_notes = log.notes.as_deref().map(str::trim).filter(|s| !s.is_empty());
                sqlx::query(
                    "UPDATE consumption_log
                     SET status = ?, notes = ?, ts = ?, hlc = ?
                     WHERE id = ?",
                )
                .bind(log.status)
                .bind(clean_notes)
                .bind(safe_log_ts)
                .bind(log_hlc)
                .bind(resolved_log_id)
                .execute(&mut *tx)
                .await?;
                if ex_status != ConsumptionStatus::Consumed
                    && log.status == ConsumptionStatus::Consumed
                {
                    CatalogRepo::promote_entry_consumed_side_effects_in_tx(
                        tx,
                        entry_id,
                        safe_log_ts,
                        now,
                    )
                    .await?;
                }
            }
            return Ok(());
        }

        let (saved_id, _, inserted_new) = CatalogRepo::log_consumption_with_id_in_tx(
            tx,
            resolved_log_id,
            entry_id,
            log.status,
            log.notes.as_deref(),
            safe_log_ts,
            now,
        )
        .await?;
        if inserted_new {
            sqlx::query("UPDATE consumption_log SET hlc = ? WHERE id = ?")
                .bind(log_hlc)
                .bind(&saved_id)
                .execute(&mut *tx)
                .await?;
        } else {
            sqlx::query(
                "UPDATE consumption_log
                 SET hlc = CASE WHEN ? > hlc THEN ? ELSE hlc END
                 WHERE id = ?",
            )
            .bind(log_hlc)
            .bind(log_hlc)
            .bind(&saved_id)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    pub(super) async fn merge_incoming_comparisons_in_tx(
        tx: &mut SqliteConnection,
        comparisons: &[SyncComparisonRecord],
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        for comp in comparisons {
            let raw_scope_opt =
                comp.scope_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
            let scope_id = match raw_scope_opt {
                Some(raw_s) => {
                    let Some(resolved) = ctx.id_resolver.resolve_live_in_tx(tx, raw_s).await?
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
                    let Some(canon) = ctx.id_resolver.resolve_live_in_tx(tx, raw_id).await? else {
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
            let comp_hlc = resolve_or_synthesize_hlc(
                &comp.hlc,
                comp.created_at.or(Some(comp.ts)),
                ctx.now,
                ctx.peer_id,
            );
            if Self::is_comparison_tombstoned_in_tx(
                tx,
                &comp_id,
                comp.id.trim(),
                was_rewritten,
                &comp_hlc,
            )
            .await?
            {
                continue;
            }

            let safe_comp_ts = clamp_incoming_upper_timestamp(comp.ts, ctx.now);
            let safe_comp_created =
                clamp_incoming_timestamp(comp.created_at.unwrap_or(ctx.now), ctx.now);
            let res = sqlx::query(
                "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?)",
            )
            .bind(&comp_id)
            .bind(safe_comp_ts)
            .bind(scope_id)
            .bind(&ord_json)
            .bind(safe_comp_created)
            .bind(&comp_hlc)
            .execute(&mut *tx)
            .await?;
            if res.rows_affected() > 0 {
                Self::record_activity(
                    tx,
                    ActivityEntityType::Comparison,
                    &comp_id,
                    ActivityAction::Create,
                    ctx.now,
                )
                .await?;
            }
        }
        Ok(())
    }

    async fn is_comparison_tombstoned_in_tx(
        tx: &mut SqliteConnection,
        canonical_id: &str,
        raw_id: &str,
        was_rewritten: bool,
        comp_hlc: &str,
    ) -> Result<bool> {
        if Self::check_and_clear_tombstone_in_tx(
            tx,
            ActivityEntityType::Comparison,
            canonical_id,
            comp_hlc,
        )
        .await?
        {
            return Ok(true);
        }
        if was_rewritten && !raw_id.is_empty() && raw_id != canonical_id {
            return Ok(Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::Comparison,
                raw_id,
                comp_hlc,
            )
            .await?);
        }
        Ok(false)
    }

    pub(super) async fn merge_incoming_generators_in_tx(
        tx: &mut SqliteConnection,
        generators: &[SyncCollectionGeneratorRecord],
        clock: &mut HlcClock,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        for inc in generators {
            Self::merge_single_generator_in_tx(tx, inc, clock, ctx).await?;
        }
        Self::advance_stale_local_generators_in_tx(tx, clock, ctx.now).await
    }

    async fn merge_single_generator_in_tx(
        tx: &mut SqliteConnection,
        inc: &SyncCollectionGeneratorRecord,
        clock: &mut HlcClock,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let Some(col_id) = ctx.id_resolver.resolve_live_in_tx(tx, &inc.collection_id).await? else {
            return Ok(());
        };
        let spec_ok: Option<CollectionSpecModeRole> = sqlx::query_as(
            "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
        )
        .bind(&col_id)
        .fetch_optional(&mut *tx)
        .await?;
        if !spec_ok
            .is_some_and(|s| CatalogRepo::is_generator_eligible(&col_id, s.mode, s.collection_role))
        {
            return Ok(());
        }

        let inc_hlc =
            resolve_or_synthesize_hlc(&inc.hlc, Some(inc.updated_at), ctx.now, ctx.peer_id);
        if Self::check_and_clear_tombstone_in_tx(
            tx,
            ActivityEntityType::CollectionGenerator,
            &col_id,
            &inc_hlc,
        )
        .await?
        {
            return Ok(());
        }

        let local_gen: Option<ExistingGeneratorRow> = sqlx::query_as(
            "SELECT enabled, current_entry_id, drawn_at, updated_at, hlc
             FROM collection_generators WHERE collection_id = ?",
        )
        .bind(&col_id)
        .fetch_optional(&mut *tx)
        .await?;

        let mapped_inc_entry = inc
            .current_entry_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(|eid| ctx.id_resolver.mapped(eid).to_owned());

        let mut target = choose_winning_generator_state(
            local_gen.as_ref(),
            inc,
            mapped_inc_entry,
            inc_hlc,
            ctx.now,
        );
        Self::reconcile_generator_target_in_tx(tx, &col_id, &mut target, clock, ctx.now).await?;

        let needs_upsert = local_gen.as_ref().is_none_or(|local| {
            target.enabled != local.enabled
                || target.current_entry_id != local.current_entry_id
                || target.drawn_at != local.drawn_at
                || target.hlc != local.hlc
        });

        if needs_upsert {
            sqlx::query(
                "INSERT INTO collection_generators
                 (collection_id, enabled, current_entry_id, drawn_at, updated_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(collection_id) DO UPDATE SET
                     enabled = excluded.enabled,
                     current_entry_id = excluded.current_entry_id,
                     drawn_at = excluded.drawn_at,
                     updated_at = excluded.updated_at,
                     hlc = excluded.hlc",
            )
            .bind(&col_id)
            .bind(target.enabled)
            .bind(target.current_entry_id.as_deref())
            .bind(target.drawn_at)
            .bind(target.updated_at)
            .bind(&target.hlc)
            .execute(&mut *tx)
            .await?;
        } else if target.local_won_conflict {
            sqlx::query("UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1")
                .execute(&mut *tx)
                .await?;
            sqlx::query(
                "UPDATE collection_generators
                 SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
                 WHERE collection_id = ?",
            )
            .bind(&col_id)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    async fn reconcile_generator_target_in_tx(
        tx: &mut SqliteConnection,
        col_id: &str,
        target: &mut GeneratorTargetState,
        clock: &mut HlcClock,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let now_ms = now.timestamp_millis();
        if let Some(ref cur_id) = target.current_entry_id {
            if let Some(canon_entry_id) = CatalogRepo::follow_merged_id_in_tx(tx, cur_id).await? {
                let in_pool: Option<i64> = sqlx::query_scalar(
                    "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                )
                .bind(col_id)
                .bind(&canon_entry_id)
                .fetch_optional(&mut *tx)
                .await?;
                if in_pool.is_none()
                    || CatalogRepo::is_entry_terminal_in_tx(tx, &canon_entry_id).await?
                {
                    target.current_entry_id = None;
                    target.drawn_at = None;
                    target.updated_at = now;
                    target.hlc = clock.tick(now_ms).encode();
                } else if canon_entry_id.as_str() != cur_id.as_str() {
                    target.current_entry_id = Some(canon_entry_id);
                    target.updated_at = now;
                    target.hlc = clock.tick(now_ms).encode();
                }
            } else {
                target.current_entry_id = None;
                target.drawn_at = None;
                target.updated_at = now;
                target.hlc = clock.tick(now_ms).encode();
            }
        }

        if target.enabled && target.current_entry_id.is_none() {
            if let Some(drawn_id) = CatalogRepo::draw_random_remaining_in_tx(tx, col_id).await? {
                target.current_entry_id = Some(drawn_id);
                target.drawn_at = Some(now);
                target.updated_at = now;
                target.hlc = clock.tick(now_ms).encode();
            } else if target.drawn_at.is_some() {
                target.drawn_at = None;
                target.updated_at = now;
                target.hlc = clock.tick(now_ms).encode();
            }
        }
        Ok(())
    }

    async fn advance_stale_local_generators_in_tx(
        tx: &mut SqliteConnection,
        clock: &mut HlcClock,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let now_ms = now.timestamp_millis();
        // Advance any active local generator whose drawn entry became terminal or was removed from
        // its collection pool during this sync
        let active: Vec<ActiveGeneratorRow> = sqlx::query_as(
            "SELECT collection_id, enabled, current_entry_id
             FROM collection_generators WHERE current_entry_id IS NOT NULL",
        )
        .fetch_all(&mut *tx)
        .await?;
        for row in active {
            let in_pool: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries ce
                 JOIN entries e ON e.id = ce.entry_id
                 WHERE ce.collection_id = ? AND ce.entry_id = ? AND e.merged_into_id IS NULL",
            )
            .bind(&row.collection_id)
            .bind(&row.current_entry_id)
            .fetch_optional(&mut *tx)
            .await?;
            if in_pool.is_none()
                || CatalogRepo::is_entry_terminal_in_tx(tx, &row.current_entry_id).await?
            {
                let next_id = if row.enabled {
                    CatalogRepo::draw_random_remaining_in_tx(tx, &row.collection_id).await?
                } else {
                    None
                };
                let next_drawn = next_id.as_ref().map(|_| now);
                let next_hlc = clock.tick(now_ms).encode();
                sqlx::query(
                    "UPDATE collection_generators
                     SET current_entry_id = ?, drawn_at = ?, updated_at = ?, hlc = ?
                     WHERE collection_id = ?",
                )
                .bind(next_id.as_deref())
                .bind(next_drawn)
                .bind(now)
                .bind(&next_hlc)
                .bind(&row.collection_id)
                .execute(&mut *tx)
                .await?;
            }
        }
        Ok(())
    }
}

fn choose_winning_generator_state(
    local_gen: Option<&ExistingGeneratorRow>,
    inc: &SyncCollectionGeneratorRecord,
    mapped_inc_entry: Option<String>,
    inc_hlc: String,
    now: DateTime<Utc>,
) -> GeneratorTargetState {
    let safe_drawn_at = clamp_incoming_timestamp_opt(inc.drawn_at, now);
    let safe_updated_at = clamp_incoming_timestamp(inc.updated_at, now);
    match local_gen {
        None => GeneratorTargetState {
            enabled: inc.enabled,
            current_entry_id: mapped_inc_entry,
            drawn_at: safe_drawn_at,
            updated_at: safe_updated_at,
            hlc: inc_hlc,
            local_won_conflict: false,
        },
        Some(local) if inc_hlc.as_str() >= local.hlc.as_str() => GeneratorTargetState {
            enabled: inc.enabled,
            current_entry_id: mapped_inc_entry,
            drawn_at: safe_drawn_at,
            updated_at: safe_updated_at,
            hlc: inc_hlc,
            local_won_conflict: false,
        },
        Some(local) => {
            let differs =
                inc.enabled != local.enabled || mapped_inc_entry != local.current_entry_id;
            GeneratorTargetState {
                enabled: local.enabled,
                current_entry_id: local.current_entry_id.clone(),
                drawn_at: local.drawn_at,
                updated_at: local.updated_at,
                hlc: local.hlc.clone(),
                local_won_conflict: differs,
            }
        }
    }
}
