use std::collections::HashSet;

use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    models::{CatalogEntry, CatalogError, CatalogResult, RawEntryRow},
};
use crate::db::{
    MediaRepo,
    sync::{
        SyncRepo, canonical_ordering_json, deterministic_auto_consumption_log_id,
        deterministic_comparison_id,
    },
    types::{ActivityAction, ActivityEntityType, CollectionMode, CollectionRole},
};

impl CatalogRepo<'_> {
    /// Post-hoc deduplication: folds `duplicate_id` into `canonical_id`, rewriting identifiers,
    /// media, collection memberships, consumption logs, comparisons, and active generators atomically.
    pub async fn merge_entries(
        &self,
        duplicate_id: &str,
        canonical_id: &str,
    ) -> CatalogResult<CatalogEntry> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let dup_resolved = Self::follow_merged_id_in_tx(&mut tx, duplicate_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Duplicate entry '{duplicate_id}'")))?;
        let canon_resolved = Self::follow_merged_id_in_tx(&mut tx, canonical_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Canonical entry '{canonical_id}'")))?;

        if dup_resolved == canon_resolved {
            return Err(CatalogError::InvalidInput("Cannot merge an entry into itself".to_owned()));
        }

        Self::validate_mergeable_specs_in_tx(&mut tx, &dup_resolved, &canon_resolved).await?;
        Self::fold_live_duplicate_in_tx(&mut tx, &dup_resolved, &canon_resolved, now).await?;

        tx.commit().await?;
        self.get_entry(&canon_resolved)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_resolved}'")))
    }

    pub(crate) async fn validate_mergeable_specs_in_tx(
        tx: &mut SqliteConnection,
        dup_resolved: &str,
        canon_resolved: &str,
    ) -> CatalogResult<()> {
        let dup_spec: Option<(CollectionMode, CollectionRole)> = sqlx::query_as(
            "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
        )
        .bind(dup_resolved)
        .fetch_optional(&mut *tx)
        .await?;
        let canon_spec: Option<(CollectionMode, CollectionRole)> = sqlx::query_as(
            "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
        )
        .bind(canon_resolved)
        .fetch_optional(&mut *tx)
        .await?;

        match (dup_spec, canon_spec) {
            (None, None) => {}
            (Some((dup_mode, dup_role)), _)
                if dup_mode != CollectionMode::Manual
                    || matches!(
                        dup_role,
                        CollectionRole::System | CollectionRole::Feed | CollectionRole::Tag
                    ) =>
            {
                return Err(CatalogError::InvalidInput(format!(
                    "Cannot merge protected {dup_role:?}/{dup_mode:?} collection '{dup_resolved}' as duplicate"
                )));
            }
            (
                Some((CollectionMode::Manual, dup_role)),
                Some((CollectionMode::Manual, canon_role)),
            ) if dup_role == canon_role => {}
            (Some(_), _) => {
                return Err(CatalogError::InvalidInput(
                    "Manual collections can only be merged into another manual collection of the same role"
                        .to_owned(),
                ));
            }
            (None, Some((canon_mode, canon_role))) => {
                return Err(CatalogError::InvalidInput(format!(
                    "Cannot merge non-collection entry '{dup_resolved}' into {canon_role:?}/{canon_mode:?} collection '{canon_resolved}'"
                )));
            }
        }

        let dup_parents: Vec<String> = sqlx::query_scalar(
            "SELECT collection_id FROM collection_entries
             WHERE entry_id = ? AND collection_id != ?",
        )
        .bind(dup_resolved)
        .bind(canon_resolved)
        .fetch_all(&mut *tx)
        .await?;
        for parent_col_id in dup_parents {
            if Self::would_introduce_collection_cycle_in_tx(tx, &parent_col_id, canon_resolved)
                .await?
            {
                return Err(CatalogError::InvalidInput(
                    "Merging entries would create a collection cycle".to_owned(),
                ));
            }
        }

        let dup_children: Vec<String> = sqlx::query_scalar(
            "SELECT entry_id FROM collection_entries
             WHERE collection_id = ? AND entry_id != ?",
        )
        .bind(dup_resolved)
        .bind(canon_resolved)
        .fetch_all(&mut *tx)
        .await?;
        for child_entry_id in dup_children {
            if Self::would_introduce_collection_cycle_in_tx(tx, canon_resolved, &child_entry_id)
                .await?
            {
                return Err(CatalogError::InvalidInput(
                    "Merging entries would create a collection cycle".to_owned(),
                ));
            }
        }

        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    pub(crate) async fn fold_live_duplicate_in_tx(
        tx: &mut SqliteConnection,
        dup_resolved: &str,
        canon_resolved: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        type StateRow = (String, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>);
        type CompRow = (String, DateTime<Utc>, Option<String>, String);

        // Compress any prior chains pointing to `dup_resolved`
        sqlx::query(
            "UPDATE entries SET merged_into_id = ?, updated_at = ? WHERE merged_into_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .execute(&mut *tx)
        .await?;

        // Backfill missing metadata onto canonical from duplicate
        let dup_row: RawEntryRow = sqlx::query_as(
            "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                    thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
             FROM entries WHERE id = ?",
        )
        .bind(dup_resolved)
        .fetch_one(&mut *tx)
        .await?;

        sqlx::query(
            "UPDATE entries
             SET creators_json = CASE WHEN creators_json = '[]' AND ? != '[]' THEN ? ELSE creators_json END,
                 url = COALESCE(url, ?),
                 published_at = COALESCE(published_at, ?),
                 release_year = COALESCE(release_year, ?),
                 summary = COALESCE(summary, ?),
                 thumbnail_url = COALESCE(thumbnail_url, ?),
                 metadata_json = CASE WHEN metadata_json = '{}' AND ? != '{}' THEN ? ELSE metadata_json END,
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(&dup_row.creators_json)
        .bind(&dup_row.creators_json)
        .bind(&dup_row.url)
        .bind(dup_row.published_at)
        .bind(dup_row.release_year)
        .bind(&dup_row.summary)
        .bind(&dup_row.thumbnail_url)
        .bind(&dup_row.metadata_json)
        .bind(&dup_row.metadata_json)
        .bind(now)
        .bind(canon_resolved)
        .execute(&mut *tx)
        .await?;

        // Remove duplicate manual collection spec if present (identifiers re-point below)
        sqlx::query(
            "DELETE FROM collection_specs
             WHERE collection_id = ?
               AND mode = 'manual'
               AND collection_role IN ('list', 'want')",
        )
        .bind(dup_resolved)
        .execute(&mut *tx)
        .await?;

        // Re-point all identifiers (preserving multi-valued schemes such as syndicated URLs)
        sqlx::query("UPDATE OR IGNORE entry_identifiers SET entry_id = ? WHERE entry_id = ?")
            .bind(canon_resolved)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM entry_identifiers WHERE entry_id = ?")
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Migrate `entry_media` (both audio enclosures and downloaded local files)
        let dup_enclosure: Option<(Option<String>, Option<i64>)> = sqlx::query_as(
            "SELECT remote_url, duration
             FROM entry_media
             WHERE entry_id = ? AND local_path IS NULL
             LIMIT 1",
        )
        .bind(dup_resolved)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some((remote_url, duration)) = dup_enclosure {
            MediaRepo::upsert_audio_enclosure_in_tx(
                &mut *tx,
                canon_resolved,
                remote_url.as_deref(),
                duration,
                now,
            )
            .await?;
            sqlx::query("DELETE FROM entry_media WHERE entry_id = ? AND local_path IS NULL")
                .bind(dup_resolved)
                .execute(&mut *tx)
                .await?;
        }
        sqlx::query(
            "UPDATE entry_media
             SET entry_id = ?
             WHERE entry_id = ? AND local_path IS NOT NULL",
        )
        .bind(canon_resolved)
        .bind(dup_resolved)
        .execute(&mut *tx)
        .await?;

        // Merge reader content & state if present
        let dup_content: Option<(Option<String>, String)> = sqlx::query_as(
            "SELECT content_html, content_type FROM entry_content WHERE entry_id = ?",
        )
        .bind(dup_resolved)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some((dup_html, dup_ct)) = dup_content {
            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                    content_html = COALESCE(entry_content.content_html, excluded.content_html)",
            )
            .bind(canon_resolved)
            .bind(dup_html)
            .bind(dup_ct)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM entry_content WHERE entry_id = ?")
                .bind(dup_resolved)
                .execute(&mut *tx)
                .await?;
        }

        let dup_state: Option<StateRow> = sqlx::query_as(
            "SELECT inbox_state, read_at, progress, interaction_updated_at
             FROM entry_state WHERE entry_id = ?",
        )
        .bind(dup_resolved)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some((dup_inbox, dup_read_at, dup_progress, dup_inter_ts)) = dup_state {
            let canon_state: Option<StateRow> = sqlx::query_as(
                "SELECT inbox_state, read_at, progress, interaction_updated_at
                 FROM entry_state WHERE entry_id = ?",
            )
            .bind(canon_resolved)
            .fetch_optional(&mut *tx)
            .await?;

            let (merged_inbox, merged_read_at, merged_progress, merged_inter_ts) =
                if let Some((can_inbox, can_read_at, can_progress, can_inter_ts)) = canon_state {
                    let inbox = if can_inbox == "dismissed" || dup_inbox == "dismissed" {
                        "dismissed".to_owned()
                    } else if can_inbox == "unread" || dup_inbox == "unread" {
                        "unread".to_owned()
                    } else {
                        "none".to_owned()
                    };
                    let read_at = match (can_read_at, dup_read_at) {
                        (Some(a), Some(b)) => Some(a.min(b)),
                        (Some(a), None) => Some(a),
                        (None, Some(b)) => Some(b),
                        (None, None) => None,
                    };
                    let progress = can_progress.max(dup_progress);
                    let inter_ts = can_inter_ts.max(dup_inter_ts);
                    (inbox, read_at, progress, inter_ts)
                } else {
                    (dup_inbox, dup_read_at, dup_progress, dup_inter_ts)
                };

            sqlx::query(
                "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                    inbox_state = excluded.inbox_state,
                    read_at = excluded.read_at,
                    progress = excluded.progress,
                    interaction_updated_at = excluded.interaction_updated_at,
                    updated_at = excluded.updated_at",
            )
            .bind(canon_resolved)
            .bind(merged_inbox)
            .bind(merged_read_at)
            .bind(merged_progress)
            .bind(merged_inter_ts)
            .bind(now)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM entry_state WHERE entry_id = ?")
                .bind(dup_resolved)
                .execute(&mut *tx)
                .await?;
        }

        // Move collection memberships (both as member and as parent collection)
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
             SELECT collection_id, ?, position, ?
             FROM collection_entries
             WHERE entry_id = ? AND collection_id != ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .bind(canon_resolved)
        .execute(&mut *tx)
        .await?;
        sqlx::query("DELETE FROM collection_entries WHERE entry_id = ?")
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
             SELECT ?, entry_id, position, ?
             FROM collection_entries
             WHERE collection_id = ? AND entry_id != ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .bind(canon_resolved)
        .execute(&mut *tx)
        .await?;
        sqlx::query("DELETE FROM collection_entries WHERE collection_id = ?")
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Canonicalize auto-completion consumption_log ID if present, then re-point manual logs
        let dup_auto_id = deterministic_auto_consumption_log_id(dup_resolved);
        let canon_auto_id = deterministic_auto_consumption_log_id(canon_resolved);
        let dup_auto_row: Option<(DateTime<Utc>, Option<String>)> =
            sqlx::query_as("SELECT ts, notes FROM consumption_log WHERE id = ?")
                .bind(&dup_auto_id)
                .fetch_optional(&mut *tx)
                .await?;
        if let Some((auto_ts, auto_notes)) = dup_auto_row {
            sqlx::query(
                "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at)
                 VALUES (?, ?, ?, 'consumed', ?, ?)
                 ON CONFLICT(id) DO UPDATE SET
                    notes = COALESCE(consumption_log.notes, excluded.notes),
                    created_at = excluded.created_at",
            )
            .bind(&canon_auto_id)
            .bind(canon_resolved)
            .bind(auto_ts)
            .bind(auto_notes)
            .bind(now)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM consumption_log WHERE id = ?")
                .bind(&dup_auto_id)
                .execute(&mut *tx)
                .await?;
        }

        sqlx::query("UPDATE consumption_log SET entry_id = ?, created_at = ? WHERE entry_id = ?")
            .bind(canon_resolved)
            .bind(now)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Rewrite comparisons involving `dup_resolved` (in ordering OR scope_collection_id)
        // using new deterministic comparison IDs + `created_at = now` so additive peer sync converges!
        let like_dup = format!("%{dup_resolved}%");
        let comp_rows: Vec<CompRow> = sqlx::query_as(
            "SELECT id, ts, scope_collection_id, ordering_json
             FROM comparisons
             WHERE ordering_json LIKE ? OR scope_collection_id = ?",
        )
        .bind(&like_dup)
        .bind(dup_resolved)
        .fetch_all(&mut *tx)
        .await?;

        for (old_comp_id, comp_ts, old_scope, ord_json) in comp_rows {
            sqlx::query("DELETE FROM comparisons WHERE id = ?")
                .bind(&old_comp_id)
                .execute(&mut *tx)
                .await?;

            let new_scope =
                old_scope.map(|sc| if sc == dup_resolved { canon_resolved.to_owned() } else { sc });
            let parsed: Vec<Vec<String>> = serde_json::from_str(&ord_json).unwrap_or_default();
            let rewritten = rewrite_ordering_after_merge(&parsed, dup_resolved, canon_resolved);
            let total_items: usize = rewritten.iter().map(Vec::len).sum();
            if total_items >= 2 {
                let new_comp_id = deterministic_comparison_id(
                    new_scope.as_deref(),
                    comp_ts.timestamp_millis(),
                    &rewritten,
                );
                let new_json = canonical_ordering_json(&rewritten);
                sqlx::query(
                    "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at)
                     VALUES (?, ?, ?, ?, ?)",
                )
                .bind(&new_comp_id)
                .bind(comp_ts)
                .bind(&new_scope)
                .bind(&new_json)
                .bind(now)
                .execute(&mut *tx)
                .await?;
            }
        }

        // Update active generator pointer if needed
        sqlx::query(
            "UPDATE collection_generators
             SET current_entry_id = ?, updated_at = ?
             WHERE current_entry_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .execute(&mut *tx)
        .await?;

        // Mark duplicate as merged tombstone (`entries_fts_au` removes it from FTS automatically)
        sqlx::query("UPDATE entries SET merged_into_id = ?, updated_at = ? WHERE id = ?")
            .bind(canon_resolved)
            .bind(now)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        SyncRepo::record_activity(
            tx,
            ActivityEntityType::Entry,
            canon_resolved,
            ActivityAction::Update,
            now,
        )
        .await?;
        SyncRepo::record_activity(
            tx,
            ActivityEntityType::Entry,
            dup_resolved,
            ActivityAction::Delete,
            now,
        )
        .await?;

        Ok(())
    }
}

pub(super) fn rewrite_ordering_after_merge(
    ordering: &[Vec<String>],
    duplicate_id: &str,
    canonical_id: &str,
) -> Vec<Vec<String>> {
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(ordering.len());
    for group in ordering {
        let mut new_group = Vec::new();
        for id in group {
            let mapped = if id == duplicate_id { canonical_id } else { id.as_str() };
            if seen.insert(mapped.to_owned()) {
                new_group.push(mapped.to_owned());
            }
        }
        if !new_group.is_empty() {
            out.push(new_group);
        }
    }
    out
}
