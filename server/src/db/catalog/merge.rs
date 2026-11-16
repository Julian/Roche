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
    types::{ActivityAction, ActivityEntityType, CollectionMode, CollectionRole, EntryKind},
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
        let dup_spec = sqlx::query!(
            r#"SELECT mode as "mode!: CollectionMode",
                      collection_role as "collection_role!: CollectionRole"
               FROM collection_specs
               WHERE collection_id = ?"#,
            dup_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?
        .map(|r| (r.mode, r.collection_role));
        let canon_spec = sqlx::query!(
            r#"SELECT mode as "mode!: CollectionMode",
                      collection_role as "collection_role!: CollectionRole"
               FROM collection_specs
               WHERE collection_id = ?"#,
            canon_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?
        .map(|r| (r.mode, r.collection_role));

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

        let dup_parents = sqlx::query_scalar!(
            r#"SELECT collection_id as "collection_id!" FROM collection_entries
               WHERE entry_id = ? AND collection_id != ?"#,
            dup_resolved,
            canon_resolved,
        )
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

        let dup_children = sqlx::query_scalar!(
            r#"SELECT entry_id as "entry_id!" FROM collection_entries
               WHERE collection_id = ? AND entry_id != ?"#,
            dup_resolved,
            canon_resolved,
        )
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
        // Compress any prior chains pointing to `dup_resolved`
        sqlx::query!(
            "UPDATE entries SET merged_into_id = ?, updated_at = ? WHERE merged_into_id = ?",
            canon_resolved,
            now as _,
            dup_resolved,
        )
        .execute(&mut *tx)
        .await?;

        // Backfill missing metadata onto canonical from duplicate
        let dup_row = sqlx::query_as!(
            RawEntryRow,
            r#"SELECT id as "id!", kind as "kind!: EntryKind", title as "title!",
                      creators_json as "creators_json!", url as "url?",
                      published_at as "published_at?: DateTime<Utc>",
                      release_year as "release_year?: i32", summary as "summary?",
                      thumbnail_url as "thumbnail_url?", metadata_json as "metadata_json!",
                      merged_into_id as "merged_into_id?",
                      created_at as "created_at!: DateTime<Utc>",
                      updated_at as "updated_at!: DateTime<Utc>"
               FROM entries WHERE id = ?"#,
            dup_resolved,
        )
        .fetch_one(&mut *tx)
        .await?;

        let dup_url = dup_row.url.as_deref();
        let dup_summary = dup_row.summary.as_deref();
        let dup_thumb = dup_row.thumbnail_url.as_deref();
        sqlx::query!(
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
            &dup_row.creators_json,
            &dup_row.creators_json,
            dup_url,
            dup_row.published_at as _,
            dup_row.release_year as _,
            dup_summary,
            dup_thumb,
            &dup_row.metadata_json,
            &dup_row.metadata_json,
            now as _,
            canon_resolved,
        )
        .execute(&mut *tx)
        .await?;

        // Remove duplicate manual collection spec if present (identifiers re-point below)
        sqlx::query!(
            "DELETE FROM collection_specs
             WHERE collection_id = ?
               AND mode = 'manual'
               AND collection_role IN ('list', 'want')",
            dup_resolved,
        )
        .execute(&mut *tx)
        .await?;

        // Re-point all identifiers (preserving multi-valued schemes such as syndicated URLs)
        sqlx::query!(
            "UPDATE OR IGNORE entry_identifiers SET entry_id = ? WHERE entry_id = ?",
            canon_resolved,
            dup_resolved,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!("DELETE FROM entry_identifiers WHERE entry_id = ?", dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Migrate `entry_media` (both audio enclosures and downloaded local files)
        let dup_enclosure = sqlx::query!(
            r#"SELECT remote_url as "remote_url?", duration as "duration?: i64"
               FROM entry_media
               WHERE entry_id = ? AND local_path IS NULL
               LIMIT 1"#,
            dup_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(enc) = dup_enclosure {
            MediaRepo::upsert_audio_enclosure_in_tx(
                &mut *tx,
                canon_resolved,
                enc.remote_url.as_deref(),
                enc.duration,
                now,
            )
            .await?;
            sqlx::query!(
                "DELETE FROM entry_media WHERE entry_id = ? AND local_path IS NULL",
                dup_resolved,
            )
            .execute(&mut *tx)
            .await?;
        }
        sqlx::query!(
            "UPDATE entry_media
             SET entry_id = ?
             WHERE entry_id = ? AND local_path IS NOT NULL",
            canon_resolved,
            dup_resolved,
        )
        .execute(&mut *tx)
        .await?;

        // Merge reader content & state if present
        let dup_content = sqlx::query!(
            r#"SELECT content_html as "content_html?", content_type as "content_type!"
               FROM entry_content WHERE entry_id = ?"#,
            dup_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(dc) = dup_content {
            let dup_html = dc.content_html.as_deref();
            sqlx::query!(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                    content_html = COALESCE(entry_content.content_html, excluded.content_html)",
                canon_resolved,
                dup_html,
                &dc.content_type,
            )
            .execute(&mut *tx)
            .await?;
            sqlx::query!("DELETE FROM entry_content WHERE entry_id = ?", dup_resolved)
                .execute(&mut *tx)
                .await?;
        }

        let dup_state = sqlx::query!(
            r#"SELECT inbox_state as "inbox_state!",
                      read_at as "read_at?: DateTime<Utc>",
                      progress as "progress!: f64",
                      interaction_updated_at as "interaction_updated_at?: DateTime<Utc>"
               FROM entry_state WHERE entry_id = ?"#,
            dup_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(ds) = dup_state {
            let canon_state = sqlx::query!(
                r#"SELECT inbox_state as "inbox_state!",
                          read_at as "read_at?: DateTime<Utc>",
                          progress as "progress!: f64",
                          interaction_updated_at as "interaction_updated_at?: DateTime<Utc>"
                   FROM entry_state WHERE entry_id = ?"#,
                canon_resolved,
            )
            .fetch_optional(&mut *tx)
            .await?;

            let (merged_inbox, merged_read_at, merged_progress, merged_inter_ts) =
                if let Some(cs) = canon_state {
                    let inbox = if cs.inbox_state == "dismissed" || ds.inbox_state == "dismissed" {
                        "dismissed".to_owned()
                    } else if cs.inbox_state == "unread" || ds.inbox_state == "unread" {
                        "unread".to_owned()
                    } else {
                        "none".to_owned()
                    };
                    let read_at = match (cs.read_at, ds.read_at) {
                        (Some(a), Some(b)) => Some(a.min(b)),
                        (Some(a), None) => Some(a),
                        (None, Some(b)) => Some(b),
                        (None, None) => None,
                    };
                    let progress = cs.progress.max(ds.progress);
                    let inter_ts = cs.interaction_updated_at.max(ds.interaction_updated_at);
                    (inbox, read_at, progress, inter_ts)
                } else {
                    (ds.inbox_state, ds.read_at, ds.progress, ds.interaction_updated_at)
                };

            sqlx::query!(
                "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                    inbox_state = excluded.inbox_state,
                    read_at = excluded.read_at,
                    progress = excluded.progress,
                    interaction_updated_at = excluded.interaction_updated_at,
                    updated_at = excluded.updated_at",
                canon_resolved,
                &merged_inbox,
                merged_read_at as _,
                merged_progress,
                merged_inter_ts as _,
                now as _,
            )
            .execute(&mut *tx)
            .await?;
            sqlx::query!("DELETE FROM entry_state WHERE entry_id = ?", dup_resolved)
                .execute(&mut *tx)
                .await?;
        }

        // Move collection memberships (both as member and as parent collection)
        sqlx::query!(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
             SELECT collection_id, ?, position, ?
             FROM collection_entries
             WHERE entry_id = ? AND collection_id != ?",
            canon_resolved,
            now as _,
            dup_resolved,
            canon_resolved,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!("DELETE FROM collection_entries WHERE entry_id = ?", dup_resolved)
            .execute(&mut *tx)
            .await?;

        sqlx::query!(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
             SELECT ?, entry_id, position, ?
             FROM collection_entries
             WHERE collection_id = ? AND entry_id != ?",
            canon_resolved,
            now as _,
            dup_resolved,
            canon_resolved,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!("DELETE FROM collection_entries WHERE collection_id = ?", dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Canonicalize auto-completion consumption_log ID if present, then re-point manual logs
        let dup_auto_id = deterministic_auto_consumption_log_id(dup_resolved);
        let canon_auto_id = deterministic_auto_consumption_log_id(canon_resolved);
        let dup_auto_row = sqlx::query!(
            r#"SELECT ts as "ts!: DateTime<Utc>", notes as "notes?"
               FROM consumption_log WHERE id = ?"#,
            &dup_auto_id,
        )
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(auto_row) = dup_auto_row {
            let auto_notes = auto_row.notes.as_deref();
            sqlx::query!(
                "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at)
                 VALUES (?, ?, ?, 'consumed', ?, ?)
                 ON CONFLICT(id) DO UPDATE SET
                    notes = COALESCE(consumption_log.notes, excluded.notes),
                    created_at = excluded.created_at",
                &canon_auto_id,
                canon_resolved,
                auto_row.ts as _,
                auto_notes,
                now as _,
            )
            .execute(&mut *tx)
            .await?;
            sqlx::query!("DELETE FROM consumption_log WHERE id = ?", &dup_auto_id)
                .execute(&mut *tx)
                .await?;
        }

        sqlx::query!(
            "UPDATE consumption_log SET entry_id = ?, created_at = ? WHERE entry_id = ?",
            canon_resolved,
            now as _,
            dup_resolved,
        )
        .execute(&mut *tx)
        .await?;

        // Rewrite comparisons involving `dup_resolved` (in ordering OR scope_collection_id)
        // using new deterministic comparison IDs + `created_at = now` so additive peer sync converges!
        let like_dup = format!("%{dup_resolved}%");
        let comp_rows = sqlx::query!(
            r#"SELECT id as "id!", ts as "ts!: DateTime<Utc>",
                      scope_collection_id as "scope_collection_id?",
                      ordering_json as "ordering_json!"
               FROM comparisons
               WHERE ordering_json LIKE ? OR scope_collection_id = ?"#,
            &like_dup,
            dup_resolved,
        )
        .fetch_all(&mut *tx)
        .await?;

        for comp in comp_rows {
            sqlx::query!("DELETE FROM comparisons WHERE id = ?", &comp.id)
                .execute(&mut *tx)
                .await?;

            let new_scope = comp
                .scope_collection_id
                .map(|sc| if sc == dup_resolved { canon_resolved.to_owned() } else { sc });
            let parsed: Vec<Vec<String>> =
                serde_json::from_str(&comp.ordering_json).unwrap_or_default();
            let rewritten = rewrite_ordering_after_merge(&parsed, dup_resolved, canon_resolved);
            let total_items: usize = rewritten.iter().map(Vec::len).sum();
            if total_items >= 2 {
                let new_comp_id = deterministic_comparison_id(
                    new_scope.as_deref(),
                    comp.ts.timestamp_millis(),
                    &rewritten,
                );
                let new_json = canonical_ordering_json(&rewritten);
                let new_scope_ref = new_scope.as_deref();
                sqlx::query!(
                    "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at)
                     VALUES (?, ?, ?, ?, ?)",
                    &new_comp_id,
                    comp.ts as _,
                    new_scope_ref,
                    &new_json,
                    now as _,
                )
                .execute(&mut *tx)
                .await?;
            }
        }

        // Update active generator pointer if needed
        sqlx::query!(
            "UPDATE collection_generators
             SET current_entry_id = ?, updated_at = ?
             WHERE current_entry_id = ?",
            canon_resolved,
            now as _,
            dup_resolved,
        )
        .execute(&mut *tx)
        .await?;

        // Mark duplicate as merged tombstone (`entries_fts_au` removes it from FTS automatically)
        sqlx::query!(
            "UPDATE entries SET merged_into_id = ?, updated_at = ? WHERE id = ?",
            canon_resolved,
            now as _,
            dup_resolved,
        )
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
