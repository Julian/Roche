use chrono::{DateTime, Utc};
use roche_core::crdt::{
    EntryStateSnapshot, merge_entry_state_for_duplicate_fold, rewrite_ordering_after_merge,
};
use sqlx::SqliteConnection;

use super::{
    CatalogRepo,
    models::{
        CatalogEntry, CatalogError, CatalogResult, RawEntryRow, is_dynamic_rule, raw_entry_columns,
    },
};
use crate::db::{
    MediaRepo, WriteTx,
    entries_core::{self, EntryStateUpsert},
    sync::{
        SyncRepo, canonical_ordering_json, deterministic_auto_consumption_log_id,
        deterministic_comparison_id,
    },
    types::{ActivityAction, ActivityEntityType, CollectionRole, InboxState},
};

impl CatalogRepo<'_> {
    /// Post-hoc deduplication: folds `duplicate_id` into `canonical_id`, rewriting identifiers,
    /// media, collection memberships, consumption logs, comparisons, and active generators atomically.
    pub async fn merge_entries(
        &self,
        duplicate_id: &str,
        canonical_id: &str,
    ) -> CatalogResult<CatalogEntry> {
        let now = crate::util::clock::now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let dup_resolved = Self::follow_merged_id_in_tx(tx.conn(), duplicate_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Duplicate entry '{duplicate_id}'")))?;
        let canon_resolved = Self::follow_merged_id_in_tx(tx.conn(), canonical_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Canonical entry '{canonical_id}'")))?;

        if dup_resolved == canon_resolved {
            return Err(CatalogError::InvalidInput("Cannot merge an entry into itself".to_owned()));
        }

        Self::validate_mergeable_specs_in_tx(tx.conn(), &dup_resolved, &canon_resolved).await?;
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
            r#"SELECT collection_role as "collection_role!: CollectionRole",
                      rule_json as "rule_json?"
               FROM collection_specs
               WHERE collection_id = ?"#,
            dup_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?
        .map(|r| (r.collection_role, is_dynamic_rule(r.rule_json.as_deref())));
        let canon_spec = sqlx::query!(
            r#"SELECT collection_role as "collection_role!: CollectionRole",
                      rule_json as "rule_json?"
               FROM collection_specs
               WHERE collection_id = ?"#,
            canon_resolved,
        )
        .fetch_optional(&mut *tx)
        .await?
        .map(|r| (r.collection_role, is_dynamic_rule(r.rule_json.as_deref())));

        match (dup_spec, canon_spec) {
            (None, None) => {}
            (Some((dup_role, dup_dynamic)), _)
                if dup_dynamic
                    || crate::db::is_protected_system_collection_id(dup_resolved)
                    || matches!(
                        dup_role,
                        CollectionRole::System | CollectionRole::Feed | CollectionRole::Tag
                    ) =>
            {
                let kind_label = if dup_dynamic { "dynamic" } else { "protected" };
                return Err(CatalogError::InvalidInput(format!(
                    "Cannot merge {kind_label} {dup_role:?} collection '{dup_resolved}' as duplicate"
                )));
            }
            (Some((dup_role, false)), Some((canon_role, false))) if dup_role == canon_role => {}
            (Some(_), _) => {
                return Err(CatalogError::InvalidInput(
                    "Manual collections can only be merged into another manual collection of the same role"
                        .to_owned(),
                ));
            }
            (None, Some((canon_role, canon_dynamic))) => {
                let kind_label = if canon_dynamic { "dynamic" } else { "manual" };
                return Err(CatalogError::InvalidInput(format!(
                    "Cannot merge non-collection entry '{dup_resolved}' into {kind_label} {canon_role:?} collection '{canon_resolved}'"
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
        tx: &mut WriteTx<'_>,
        dup_resolved: &str,
        canon_resolved: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        // Compress any prior chains pointing to `dup_resolved`
        let chain_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE entries SET merged_into_id = ?, updated_at = ?, hlc = ? WHERE merged_into_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(&chain_hlc)
        .bind(dup_resolved)
        .execute(tx.conn())
        .await?;

        // Backfill missing metadata onto canonical from duplicate
        let dup_row: RawEntryRow =
            sqlx::query_as(concat!("SELECT ", raw_entry_columns!(), " FROM entries WHERE id = ?"))
                .bind(dup_resolved)
                .fetch_one(tx.conn())
                .await?;

        let dup_url = dup_row.url.as_deref();
        let dup_summary = dup_row.summary.as_deref();
        let dup_thumb = dup_row.thumbnail_url.as_deref();
        let backfill_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE entries
             SET creators_json = CASE WHEN creators_json = '[]' AND ? != '[]' THEN ? ELSE creators_json END,
                 url = COALESCE(url, ?),
                 published_at = COALESCE(published_at, ?),
                 release_year = COALESCE(release_year, ?),
                 summary = COALESCE(summary, ?),
                 thumbnail_url = COALESCE(thumbnail_url, ?),
                 metadata_json = CASE WHEN metadata_json = '{}' AND ? != '{}' THEN ? ELSE metadata_json END,
                 updated_at = ?,
                 hlc = CASE
                     WHEN (creators_json = '[]' AND ? != '[]')
                       OR (url IS NULL AND ? IS NOT NULL)
                       OR (published_at IS NULL AND ? IS NOT NULL)
                       OR (release_year IS NULL AND ? IS NOT NULL)
                       OR (summary IS NULL AND ? IS NOT NULL)
                       OR (thumbnail_url IS NULL AND ? IS NOT NULL)
                       OR (metadata_json = '{}' AND ? != '{}')
                     THEN ?
                     ELSE hlc
                 END
             WHERE id = ?",
        )
        .bind(&dup_row.creators_json)
        .bind(&dup_row.creators_json)
        .bind(dup_url)
        .bind(dup_row.published_at)
        .bind(dup_row.release_year)
        .bind(dup_summary)
        .bind(dup_thumb)
        .bind(&dup_row.metadata_json)
        .bind(&dup_row.metadata_json)
        .bind(now)
        .bind(&dup_row.creators_json)
        .bind(dup_url)
        .bind(dup_row.published_at)
        .bind(dup_row.release_year)
        .bind(dup_summary)
        .bind(dup_thumb)
        .bind(&dup_row.metadata_json)
        .bind(&backfill_hlc)
        .bind(canon_resolved)
        .execute(tx.conn())
        .await?;

        // Remove duplicate manual collection spec if present (identifiers re-point below)
        sqlx::query!(
            "DELETE FROM collection_specs
             WHERE collection_id = ?
               AND rule_json IS NULL
               AND collection_role IN ('list', 'want')",
            dup_resolved,
        )
        .execute(tx.conn())
        .await?;

        // Re-point all identifiers (preserving multi-valued schemes such as syndicated URLs)
        sqlx::query!(
            "UPDATE OR IGNORE entry_identifiers SET entry_id = ? WHERE entry_id = ?",
            canon_resolved,
            dup_resolved,
        )
        .execute(tx.conn())
        .await?;
        sqlx::query!("DELETE FROM entry_identifiers WHERE entry_id = ?", dup_resolved)
            .execute(tx.conn())
            .await?;

        // Migrate `entry_media` (both audio enclosures and downloaded local files)
        let dup_enclosure = sqlx::query!(
            r#"SELECT remote_url as "remote_url?", duration as "duration?: i64"
               FROM entry_media
               WHERE entry_id = ? AND local_path IS NULL
               LIMIT 1"#,
            dup_resolved,
        )
        .fetch_optional(tx.conn())
        .await?;
        if let Some(enc) = dup_enclosure {
            MediaRepo::upsert_audio_enclosure_in_tx(
                tx.conn(),
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
            .execute(tx.conn())
            .await?;
        }
        sqlx::query!(
            "UPDATE entry_media
             SET entry_id = ?
             WHERE entry_id = ? AND local_path IS NOT NULL",
            canon_resolved,
            dup_resolved,
        )
        .execute(tx.conn())
        .await?;

        // Merge reader content & state if present
        let dup_content = sqlx::query!(
            r#"SELECT content_html as "content_html?", content_type as "content_type!"
               FROM entry_content WHERE entry_id = ?"#,
            dup_resolved,
        )
        .fetch_optional(tx.conn())
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
            .execute(tx.conn())
            .await?;
            sqlx::query!("DELETE FROM entry_content WHERE entry_id = ?", dup_resolved)
                .execute(tx.conn())
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
        .fetch_optional(tx.conn())
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
            .fetch_optional(tx.conn())
            .await?;

            let parse_inbox = |raw: &str| match raw {
                "dismissed" => InboxState::Dismissed,
                "unread" => InboxState::Unread,
                _ => InboxState::None,
            };
            let dup_snap = EntryStateSnapshot {
                inbox_state: parse_inbox(&ds.inbox_state),
                read_at: ds.read_at,
                progress: ds.progress,
                interaction_updated_at: ds.interaction_updated_at,
                hlc: String::new(),
            };
            let canon_snap = canon_state.map(|cs| EntryStateSnapshot {
                inbox_state: parse_inbox(&cs.inbox_state),
                read_at: cs.read_at,
                progress: cs.progress,
                interaction_updated_at: cs.interaction_updated_at,
                hlc: String::new(),
            });
            let (merged_inbox, merged_read_at, merged_progress, merged_inter_ts) =
                merge_entry_state_for_duplicate_fold(canon_snap.as_ref(), &dup_snap);

            entries_core::upsert_entry_state_in_tx(
                tx,
                &EntryStateUpsert {
                    entry_id: canon_resolved,
                    inbox_state: merged_inbox,
                    read_at: merged_read_at,
                    progress: merged_progress,
                    interaction_updated_at: merged_inter_ts,
                    updated_at: now,
                },
            )
            .await?;
            sqlx::query!("DELETE FROM entry_state WHERE entry_id = ?", dup_resolved)
                .execute(tx.conn())
                .await?;
        }

        // Move collection memberships (both as member and as parent collection)
        let ce_member_hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at, hlc)
             SELECT collection_id, ?, position, ?, ?
             FROM collection_entries
             WHERE entry_id = ? AND collection_id != ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(&ce_member_hlc)
        .bind(dup_resolved)
        .bind(canon_resolved)
        .execute(tx.conn())
        .await?;
        sqlx::query!("DELETE FROM collection_entries WHERE entry_id = ?", dup_resolved)
            .execute(tx.conn())
            .await?;

        let ce_parent_hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at, hlc)
             SELECT ?, entry_id, position, ?, ?
             FROM collection_entries
             WHERE collection_id = ? AND entry_id != ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(&ce_parent_hlc)
        .bind(dup_resolved)
        .bind(canon_resolved)
        .execute(tx.conn())
        .await?;
        sqlx::query!("DELETE FROM collection_entries WHERE collection_id = ?", dup_resolved)
            .execute(tx.conn())
            .await?;

        // Canonicalize auto-completion consumption_log ID if present, then re-point manual logs
        let dup_auto_id = deterministic_auto_consumption_log_id(dup_resolved);
        let canon_auto_id = deterministic_auto_consumption_log_id(canon_resolved);
        let dup_auto_row = sqlx::query!(
            r#"SELECT ts as "ts!: DateTime<Utc>", notes as "notes?"
               FROM consumption_log WHERE id = ?"#,
            &dup_auto_id,
        )
        .fetch_optional(tx.conn())
        .await?;
        if let Some(auto_row) = dup_auto_row {
            let auto_notes = auto_row.notes.as_deref();
            let auto_hlc = tx.next_hlc().await?;
            sqlx::query(
                "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at, hlc)
                 VALUES (?, ?, ?, 'consumed', ?, ?, ?)
                 ON CONFLICT(id) DO UPDATE SET
                    notes = COALESCE(consumption_log.notes, excluded.notes),
                    created_at = excluded.created_at,
                    hlc = excluded.hlc",
            )
            .bind(&canon_auto_id)
            .bind(canon_resolved)
            .bind(auto_row.ts)
            .bind(auto_notes)
            .bind(now)
            .bind(&auto_hlc)
            .execute(tx.conn())
            .await?;
            sqlx::query!("DELETE FROM consumption_log WHERE id = ?", &dup_auto_id)
                .execute(tx.conn())
                .await?;
        }

        let log_repoint_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE consumption_log SET entry_id = ?, created_at = ?, hlc = ? WHERE entry_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(&log_repoint_hlc)
        .bind(dup_resolved)
        .execute(tx.conn())
        .await?;

        // Rewrite comparisons involving `dup_resolved` (in ordering OR scope_collection_id)
        // using new deterministic comparison IDs + causal HLC so additive peer sync converges!
        let like_dup = crate::db::json_string_like_pattern(dup_resolved);
        let comp_rows: Vec<(String, DateTime<Utc>, Option<String>, String)> = sqlx::query_as(
            "SELECT id, ts, scope_collection_id, ordering_json
             FROM comparisons
             WHERE ordering_json LIKE ? ESCAPE '\\' OR scope_collection_id = ?",
        )
        .bind(&like_dup)
        .bind(dup_resolved)
        .fetch_all(tx.conn())
        .await?;

        for (comp_id, comp_ts, scope_collection_id, ordering_json) in comp_rows {
            let scope_matches = scope_collection_id.as_deref() == Some(dup_resolved);
            let parsed: Vec<Vec<String>> = serde_json::from_str(&ordering_json).unwrap_or_default();
            let ordering_matches =
                parsed.iter().any(|group| group.iter().any(|id| id == dup_resolved));
            if !scope_matches && !ordering_matches {
                continue;
            }

            sqlx::query!("DELETE FROM comparisons WHERE id = ?", &comp_id)
                .execute(tx.conn())
                .await?;

            let new_scope = scope_collection_id
                .map(|sc| if sc == dup_resolved { canon_resolved.to_owned() } else { sc });
            let rewritten = rewrite_ordering_after_merge(&parsed, dup_resolved, canon_resolved);
            let total_items: usize = rewritten.iter().map(Vec::len).sum();
            if total_items >= 2 {
                let new_comp_id = deterministic_comparison_id(
                    new_scope.as_deref(),
                    comp_ts.timestamp_millis(),
                    &rewritten,
                );
                let new_json = canonical_ordering_json(&rewritten);
                let new_scope_ref = new_scope.as_deref();
                let comp_hlc = tx.next_hlc().await?;
                sqlx::query(
                    "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at, hlc)
                     VALUES (?, ?, ?, ?, ?, ?)",
                )
                .bind(&new_comp_id)
                .bind(comp_ts)
                .bind(new_scope_ref)
                .bind(&new_json)
                .bind(now)
                .bind(&comp_hlc)
                .execute(tx.conn())
                .await?;
            }
        }

        // Update active generator pointer if needed
        let gen_ptr_hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE collection_generators
             SET current_entry_id = ?, updated_at = ?, hlc = ?
             WHERE current_entry_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(&gen_ptr_hlc)
        .bind(dup_resolved)
        .execute(tx.conn())
        .await?;

        // Migrate or delete any `collection_generators` row owned by `dup_resolved`
        let dup_gen: Option<(bool, Option<String>, Option<DateTime<Utc>>)> = sqlx::query_as(
            "SELECT enabled, current_entry_id, drawn_at
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(dup_resolved)
        .fetch_optional(tx.conn())
        .await?;

        if let Some((dup_enabled, mut dup_cur_entry, mut dup_drawn_at)) = dup_gen {
            let canon_spec: Option<(CollectionRole, Option<String>)> = sqlx::query_as(
                "SELECT collection_role, rule_json FROM collection_specs WHERE collection_id = ?",
            )
            .bind(canon_resolved)
            .fetch_optional(tx.conn())
            .await?;
            let canon_eligible = canon_spec.is_some_and(|(role, rule_json)| {
                Self::is_generator_eligible(
                    canon_resolved,
                    role,
                    is_dynamic_rule(rule_json.as_deref()),
                )
            });
            let canon_gen_exists: Option<i64> =
                sqlx::query_scalar("SELECT 1 FROM collection_generators WHERE collection_id = ?")
                    .bind(canon_resolved)
                    .fetch_optional(tx.conn())
                    .await?;

            sqlx::query("DELETE FROM collection_generators WHERE collection_id = ?")
                .bind(dup_resolved)
                .execute(tx.conn())
                .await?;

            if canon_eligible && canon_gen_exists.is_none() {
                if let Some(ref cur_id) = dup_cur_entry {
                    let in_pool: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                    )
                    .bind(canon_resolved)
                    .bind(cur_id)
                    .fetch_optional(tx.conn())
                    .await?;
                    if in_pool.is_none() || Self::is_entry_terminal_in_tx(tx.conn(), cur_id).await?
                    {
                        dup_cur_entry = None;
                        dup_drawn_at = None;
                    }
                }
                if dup_enabled && dup_cur_entry.is_none() {
                    dup_cur_entry =
                        Self::draw_random_remaining_in_tx(tx.conn(), canon_resolved).await?;
                    dup_drawn_at = dup_cur_entry.as_ref().map(|_| now);
                }
                SyncRepo::clear_tombstone(
                    tx.conn(),
                    ActivityEntityType::CollectionGenerator,
                    canon_resolved,
                )
                .await?;
                let migrated_gen_hlc = tx.next_hlc().await?;
                sqlx::query(
                    "INSERT INTO collection_generators
                     (collection_id, enabled, current_entry_id, drawn_at, updated_at, hlc)
                     VALUES (?, ?, ?, ?, ?, ?)",
                )
                .bind(canon_resolved)
                .bind(dup_enabled)
                .bind(dup_cur_entry.as_deref())
                .bind(dup_drawn_at)
                .bind(now)
                .bind(&migrated_gen_hlc)
                .execute(tx.conn())
                .await?;
            } else {
                SyncRepo::record_tombstone(
                    tx,
                    ActivityEntityType::CollectionGenerator,
                    dup_resolved,
                    now,
                )
                .await?;
                SyncRepo::record_activity(
                    tx.conn(),
                    ActivityEntityType::CollectionGenerator,
                    dup_resolved,
                    ActivityAction::Delete,
                    now,
                )
                .await?;
                // Ensure canonical's existing generator HLC strictly exceeds `dup_resolved`'s
                // tombstone HLC via the next causal HLC tick (same ms, counter + 1).
                if canon_gen_exists.is_some() {
                    let bump_hlc = tx.next_hlc().await?;
                    sqlx::query(
                        "UPDATE collection_generators
                         SET updated_at = ?, hlc = ?
                         WHERE collection_id = ?",
                    )
                    .bind(now)
                    .bind(&bump_hlc)
                    .bind(canon_resolved)
                    .execute(tx.conn())
                    .await?;
                }
            }
        }

        // Mark duplicate as merged tombstone (`entries_fts_au` removes it from FTS automatically)
        let dup_tomb_hlc = tx.next_hlc().await?;
        sqlx::query("UPDATE entries SET merged_into_id = ?, updated_at = ?, hlc = ? WHERE id = ?")
            .bind(canon_resolved)
            .bind(now)
            .bind(&dup_tomb_hlc)
            .bind(dup_resolved)
            .execute(tx.conn())
            .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            canon_resolved,
            ActivityAction::Update,
            now,
        )
        .await?;
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            dup_resolved,
            ActivityAction::Delete,
            now,
        )
        .await?;

        Ok(())
    }
}
