use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::super::{
    SyncRepo,
    activity::SyncTombstone,
    hlc::{clamp_incoming_timestamp, resolve_or_synthesize_hlc},
    identity::{
        canonicalize_sync_url, collection_entry_natural_key, deterministic_feed_id,
        deterministic_item_id, feed_natural_key, is_protected_system_collection_id,
        item_natural_key, tag_natural_key,
    },
};
use super::MergeContext;
use crate::db::{ActivityAction, ActivityEntityType, FeedRepo, ItemRepo};

#[derive(sqlx::FromRow)]
struct FeedTombstoneTarget {
    collection_id: String,
    fs_hlc: String,
    entry_hlc: String,
}

#[derive(sqlx::FromRow)]
struct ItemTombstoneCandidate {
    id: String,
    state_hlc: String,
}

impl SyncRepo<'_> {
    /// Applies entry-level tombstones (`feed`, `item`, `tag`, `entry`) prior to
    /// `merge_incoming_entries_in_tx` so tombstones suppress resurrected entries in the same batch.
    pub(super) async fn apply_incoming_entry_tombstones(
        conn: &mut SqliteConnection,
        tombstones: &[SyncTombstone],
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        for tomb in tombstones {
            let key = tomb.natural_key.trim();
            if key.is_empty() {
                continue;
            }
            let safe_deleted_at = clamp_incoming_timestamp(tomb.deleted_at, ctx.now);
            let tomb_hlc =
                resolve_or_synthesize_hlc(&tomb.hlc, Some(safe_deleted_at), ctx.now, ctx.peer_id);

            match tomb.entity_type {
                ActivityEntityType::Feed => {
                    Self::apply_feed_tombstone_in_tx(
                        conn,
                        key,
                        safe_deleted_at,
                        &tomb_hlc,
                        ctx.now,
                    )
                    .await?;
                }
                ActivityEntityType::Item => {
                    Self::apply_item_tombstone_in_tx(
                        conn,
                        key,
                        safe_deleted_at,
                        &tomb_hlc,
                        ctx.now,
                    )
                    .await?;
                }
                ActivityEntityType::Tag => {
                    Self::apply_tag_tombstone_in_tx(conn, key, safe_deleted_at, &tomb_hlc, ctx)
                        .await?;
                }
                ActivityEntityType::Entry => {
                    Self::apply_entry_tombstone_in_tx(conn, key, safe_deleted_at, &tomb_hlc, ctx)
                        .await?;
                }
                ActivityEntityType::CollectionEntry
                | ActivityEntityType::CollectionGenerator
                | ActivityEntityType::ConsumptionLog
                | ActivityEntityType::Media
                | ActivityEntityType::Collection
                | ActivityEntityType::Comparison => {}
            }
        }
        Ok(())
    }

    /// Applies graph-level tombstones (`collection_entry`, `collection_generator`,
    /// `consumption_log`) *after* `merge_incoming_entries_in_tx` populates `ctx.id_resolver`
    /// and *before* incoming memberships, consumption logs, and generators merge, then completes
    /// any deferred `entry` deletions whose catalog references were removed in this phase.
    pub(super) async fn apply_incoming_graph_tombstones(
        conn: &mut SqliteConnection,
        tombstones: &[SyncTombstone],
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        for tomb in tombstones {
            let key = tomb.natural_key.trim();
            if key.is_empty() {
                continue;
            }
            let safe_deleted_at = clamp_incoming_timestamp(tomb.deleted_at, ctx.now);
            let tomb_hlc =
                resolve_or_synthesize_hlc(&tomb.hlc, Some(safe_deleted_at), ctx.now, ctx.peer_id);

            match tomb.entity_type {
                ActivityEntityType::CollectionEntry => {
                    Self::apply_collection_entry_tombstone_in_tx(
                        conn,
                        key,
                        safe_deleted_at,
                        &tomb_hlc,
                        ctx,
                    )
                    .await?;
                }
                ActivityEntityType::CollectionGenerator => {
                    Self::apply_collection_generator_tombstone_in_tx(
                        conn,
                        key,
                        safe_deleted_at,
                        &tomb_hlc,
                        ctx,
                    )
                    .await?;
                }
                ActivityEntityType::ConsumptionLog => {
                    Self::apply_consumption_log_tombstone_in_tx(
                        conn,
                        key,
                        safe_deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::Comparison => {
                    Self::apply_comparison_tombstone_in_tx(conn, key, safe_deleted_at, &tomb_hlc)
                        .await?;
                }
                ActivityEntityType::Feed
                | ActivityEntityType::Item
                | ActivityEntityType::Tag
                | ActivityEntityType::Entry
                | ActivityEntityType::Media
                | ActivityEntityType::Collection => {}
            }
        }
        Self::finalize_deferred_entry_deletes_in_tx(conn, ctx).await?;
        Ok(())
    }

    async fn finalize_deferred_entry_deletes_in_tx(
        conn: &mut SqliteConnection,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let deferred = std::mem::take(&mut ctx.deferred_entry_deletes);
        for (raw_key, deleted_at, tomb_hlc) in deferred {
            let key = ctx.id_resolver.resolve_or_fallback_in_tx(&mut *conn, &raw_key).await?;
            if is_protected_system_collection_id(&key)
                || Self::is_protected_collection_spec_in_tx(&mut *conn, &key).await?
            {
                continue;
            }
            let existing_hlc: Option<String> =
                sqlx::query_scalar("SELECT hlc FROM entries WHERE id = ?")
                    .bind(&key)
                    .fetch_optional(&mut *conn)
                    .await?;
            if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc.as_str()) {
                continue;
            }
            if !ItemRepo::entry_has_catalog_history_in_tx(&mut *conn, &key).await? {
                sqlx::query("DELETE FROM entries WHERE id = ?")
                    .bind(&key)
                    .execute(&mut *conn)
                    .await?;
                if ctx.tag_index.is_loaded() {
                    ctx.tag_index.remove(&key);
                }
                Self::record_tombstone_with_hlc(
                    &mut *conn,
                    ActivityEntityType::Entry,
                    &key,
                    deleted_at,
                    &tomb_hlc,
                )
                .await?;
            }
        }
        Ok(())
    }

    async fn apply_feed_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let canon_key = feed_natural_key(key);
        let det_id = deterministic_feed_id(&canon_key);
        let existing: Option<FeedTombstoneTarget> = sqlx::query_as(
            "SELECT fs.collection_id, fs.hlc AS fs_hlc, e.hlc AS entry_hlc
             FROM feed_sources fs
             JOIN entries e ON e.id = fs.collection_id
             WHERE fs.feed_url = ? OR fs.collection_id = ?
             LIMIT 1",
        )
        .bind(&canon_key)
        .bind(&det_id)
        .fetch_optional(&mut *conn)
        .await?;

        if let Some(target) = existing {
            let live_hlc = target.fs_hlc.as_str().max(target.entry_hlc.as_str());
            if live_hlc > tomb_hlc {
                return Ok(());
            }
            FeedRepo::delete_feed_preserving_history_in_tx(&mut *conn, &target.collection_id, now)
                .await?;
            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Feed,
                &target.collection_id,
                ActivityAction::Delete,
                now,
            )
            .await?;
        }
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::Feed,
            &canon_key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn apply_item_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let (canon_key, det_id, canon_feed_opt, canon_item) =
            if let Some(item_url) = key.strip_prefix("manual\0") {
                let ci = canonicalize_sync_url(item_url);
                let ck = item_natural_key(None, &ci);
                let did = deterministic_item_id(None, &ci);
                (ck, did, None, ci)
            } else if let Some((feed_url, item_url)) = key.split_once('\0') {
                let cf = canonicalize_sync_url(feed_url);
                let ci = canonicalize_sync_url(item_url);
                let ck = item_natural_key(Some(&cf), &ci);
                let did = deterministic_item_id(Some(&cf), &ci);
                (ck, did, Some(cf), ci)
            } else {
                Self::record_tombstone_with_hlc(
                    &mut *conn,
                    ActivityEntityType::Item,
                    key,
                    deleted_at,
                    tomb_hlc,
                )
                .await?;
                return Ok(());
            };

        let candidates: Vec<ItemTombstoneCandidate> = if let Some(ref cf) = canon_feed_opt {
            let parent_feed_id = deterministic_feed_id(cf);
            sqlx::query_as(
                "SELECT e.id, es.hlc AS state_hlc
                 FROM entries e
                 JOIN entry_state es ON es.entry_id = e.id
                 WHERE es.inbox_state IN ('unread', 'dismissed')
                   AND (e.id = ? OR (e.url = ? AND EXISTS (
                       SELECT 1 FROM collection_entries ce
                       JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                       WHERE ce.entry_id = e.id
                         AND (fs.feed_url = ? OR fs.collection_id = ?)
                   )))",
            )
            .bind(&det_id)
            .bind(&canon_item)
            .bind(cf)
            .bind(&parent_feed_id)
            .fetch_all(&mut *conn)
            .await?
        } else {
            sqlx::query_as(
                "SELECT e.id, es.hlc AS state_hlc
                 FROM entries e
                 JOIN entry_state es ON es.entry_id = e.id
                 WHERE es.inbox_state IN ('unread', 'dismissed', 'none')
                   AND (e.id = ? OR (e.url = ? AND NOT EXISTS (
                       SELECT 1 FROM collection_entries ce
                       JOIN feed_sources fs ON fs.collection_id = ce.collection_id
                       WHERE ce.entry_id = e.id
                   )))",
            )
            .bind(&det_id)
            .bind(&canon_item)
            .fetch_all(&mut *conn)
            .await?
        };

        if candidates.iter().any(|cand| cand.state_hlc.as_str() > tomb_hlc) {
            return Ok(());
        }
        for cand in candidates {
            if ItemRepo::delete_inbox_item_preserving_history_in_tx(&mut *conn, &cand.id, true, now)
                .await?
            {
                Self::record_activity(
                    &mut *conn,
                    ActivityEntityType::Item,
                    &cand.id,
                    ActivityAction::Delete,
                    now,
                )
                .await?;
            }
        }
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::Item,
            &canon_key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn apply_tag_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let canon_key = tag_natural_key(key);
        ctx.tag_index.ensure_loaded(&mut *conn).await?;
        let matched = ctx.tag_index.find_all_for_tombstone(&canon_key);

        if matched.iter().any(|tag| tag.hlc.as_str() > tomb_hlc) {
            return Ok(());
        }
        for tag in matched {
            sqlx::query("DELETE FROM entries WHERE id = ?")
                .bind(&tag.id)
                .execute(&mut *conn)
                .await?;
            ctx.tag_index.remove(&tag.id);
            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Tag,
                &tag.id,
                ActivityAction::Delete,
                ctx.now,
            )
            .await?;
        }
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::Tag,
            &canon_key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn apply_collection_entry_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let Some((raw_col, raw_entry)) = key.split_once('\0') else {
            return Ok(());
        };
        let col_id = ctx.id_resolver.resolve_or_fallback_in_tx(&mut *conn, raw_col).await?;
        let entry_id = ctx.id_resolver.resolve_or_fallback_in_tx(&mut *conn, raw_entry).await?;
        let canon_key = collection_entry_natural_key(&col_id, &entry_id);

        let existing_hlc: Option<String> = sqlx::query_scalar(
            "SELECT hlc FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
        )
        .bind(&col_id)
        .bind(&entry_id)
        .fetch_optional(&mut *conn)
        .await?;

        if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc) {
            return Ok(());
        }
        // Generator advancement for any collection whose `current_entry_id` was removed
        // is intentionally deferred to `merge_incoming_generators_in_tx` (after peer
        // generator state merges) so peer-drawn replacements are not overwritten by
        // server `now` HLCs.
        sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
            .bind(&col_id)
            .bind(&entry_id)
            .execute(&mut *conn)
            .await?;
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::CollectionEntry,
            &canon_key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn is_protected_collection_spec_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
    ) -> Result<bool> {
        let protected_spec: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM collection_specs
             WHERE collection_id = ?
               AND (mode = 'dynamic' OR collection_role IN ('system', 'feed', 'tag'))",
        )
        .bind(key)
        .fetch_optional(&mut *conn)
        .await?;
        Ok(protected_spec.is_some())
    }

    async fn apply_entry_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        if is_protected_system_collection_id(key)
            || Self::is_protected_collection_spec_in_tx(&mut *conn, key).await?
        {
            return Ok(());
        }
        let existing_hlc: Option<String> =
            sqlx::query_scalar("SELECT hlc FROM entries WHERE id = ?")
                .bind(key)
                .fetch_optional(&mut *conn)
                .await?;
        if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc) {
            return Ok(());
        }
        if ItemRepo::entry_has_catalog_history_in_tx(&mut *conn, key).await? {
            ctx.deferred_entry_deletes.push((key.to_owned(), deleted_at, tomb_hlc.to_owned()));
            return Ok(());
        }
        sqlx::query("DELETE FROM entries WHERE id = ?").bind(key).execute(&mut *conn).await?;
        if ctx.tag_index.is_loaded() {
            ctx.tag_index.remove(key);
        }
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::Entry,
            key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn apply_collection_generator_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let col_id = ctx.id_resolver.resolve_or_fallback_in_tx(&mut *conn, key).await?;
        let existing_hlc: Option<String> =
            sqlx::query_scalar("SELECT hlc FROM collection_generators WHERE collection_id = ?")
                .bind(&col_id)
                .fetch_optional(&mut *conn)
                .await?;
        if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc) {
            return Ok(());
        }
        let res = sqlx::query("DELETE FROM collection_generators WHERE collection_id = ?")
            .bind(&col_id)
            .execute(&mut *conn)
            .await?;
        if res.rows_affected() > 0 {
            Self::record_activity(
                &mut *conn,
                ActivityEntityType::CollectionGenerator,
                &col_id,
                ActivityAction::Delete,
                ctx.now,
            )
            .await?;
        }
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::CollectionGenerator,
            &col_id,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn apply_consumption_log_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
    ) -> Result<()> {
        let existing_hlc: Option<String> =
            sqlx::query_scalar("SELECT hlc FROM consumption_log WHERE id = ?")
                .bind(key)
                .fetch_optional(&mut *conn)
                .await?;
        if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc) {
            return Ok(());
        }
        sqlx::query("DELETE FROM consumption_log WHERE id = ?")
            .bind(key)
            .execute(&mut *conn)
            .await?;
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::ConsumptionLog,
            key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }

    async fn apply_comparison_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
    ) -> Result<()> {
        let existing_hlc: Option<String> =
            sqlx::query_scalar("SELECT hlc FROM comparisons WHERE id = ?")
                .bind(key)
                .fetch_optional(&mut *conn)
                .await?;
        if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc) {
            return Ok(());
        }
        sqlx::query("DELETE FROM comparisons WHERE id = ?").bind(key).execute(&mut *conn).await?;
        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::Comparison,
            key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }
}
