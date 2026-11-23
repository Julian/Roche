use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, SubsecRound, Utc};
use sqlx::SqliteConnection;

use super::super::{
    STARRED_COLLECTION_ID, SyncRepo,
    hlc::{
        clamp_incoming_timestamp, clamp_incoming_timestamp_opt, clamp_incoming_upper_timestamp,
        resolve_or_synthesize_hlc,
    },
    hrw::normalize_consecutive_failures,
    identity::{
        canonicalize_sync_url, deterministic_entity_uuid, deterministic_feed_id,
        deterministic_item_id, deterministic_tag_id, feed_natural_key,
        is_protected_system_collection_id, item_natural_key, tag_natural_key,
    },
    models::{
        InsertedItemCandidate, PreparedSyncEntry, SyncCollectionEntryRecord, SyncCollectionSpec,
        SyncEntryRecord, SyncEntryState, SyncFeedSource,
    },
};
use super::{IndexedTag, MergeContext};
use crate::db::{
    ActivityAction, ActivityEntityType, CatalogRepo, CollectionRole, ContentType, EntryIdentifier,
    EntryKind, FeedRepo, FeedType, HexColor, InboxState, MediaRepo, PollItemCandidate,
    UpsertEntryInput, WriteTx, entries_core,
    feeds::{NewFeedRow, entry_kind_for_feed_type, resolve_dedup_entry_id},
};

#[derive(sqlx::FromRow)]
struct ExistingFeedRow {
    collection_id: String,
    feed_url: String,
    source_type: FeedType,
    entry_kind: EntryKind,
    feed_title: Option<String>,
    last_polled_at: Option<DateTime<Utc>>,
    is_active: bool,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct ExistingEntryRow {
    kind: EntryKind,
    title: String,
    creators_json: String,
    url: Option<String>,
    published_at: Option<DateTime<Utc>>,
    release_year: Option<i32>,
    summary: Option<String>,
    thumbnail_url: Option<String>,
    metadata_json: String,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct ExistingContentRow {
    content_html: Option<String>,
    content_type: ContentType,
}

#[derive(Debug, Clone, sqlx::FromRow)]
struct EntryStateRow {
    inbox_state: InboxState,
    read_at: Option<DateTime<Utc>>,
    progress: f64,
    interaction_updated_at: Option<DateTime<Utc>>,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct ItemTombstoneRow {
    natural_key: String,
    hlc: String,
}

struct NormalizedFeedFields<'a> {
    feed_url: &'a str,
    det_id: String,
    entry_hlc: String,
    fs_hlc: String,
    clean_feed_title: Option<&'a str>,
    clean_desc: Option<&'a str>,
    clean_lang: Option<&'a str>,
    clean_err: Option<&'a str>,
    clean_polled_by: Option<&'a str>,
    clean_err_by: Option<&'a str>,
    clean_consecutive_failures: i64,
    interval: i64,
}

struct TagMergeInput<'a> {
    clean_name: &'a str,
    nat_key: &'a str,
    tag_slug: String,
    parsed_color: Option<String>,
    entry_hlc: String,
}

struct WorkMergeTarget<'a> {
    target_kind: EntryKind,
    final_title: &'a str,
    entry_hlc: &'a str,
    now: DateTime<Utc>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ItemTombstoneAction {
    Allow,
    SuppressPayload,
    SkipEntry,
}

struct FeedMergeOutcome {
    local_id: String,
    effective_url: String,
    url_changed: bool,
    deferred_url_collision: bool,
}

struct FeedConfigMergeOutcome {
    effective_url: String,
    url_changed: bool,
    deferred_url_collision: bool,
}

type Pass1aPartition<'a> = (
    Vec<(&'a PreparedSyncEntry, &'a SyncCollectionSpec)>,
    Vec<(&'a PreparedSyncEntry, &'a SyncFeedSource)>,
    Vec<(&'a PreparedSyncEntry, &'a SyncFeedSource)>,
);

async fn partition_pass_1a_entries<'a>(
    conn: &mut SqliteConnection,
    entries: &'a [PreparedSyncEntry],
    pending_merges: &mut Vec<(&'a PreparedSyncEntry, String)>,
) -> Result<Pass1aPartition<'a>> {
    let existing_feed_ids: HashSet<String> =
        sqlx::query_scalar("SELECT collection_id FROM feed_sources")
            .fetch_all(conn)
            .await?
            .into_iter()
            .collect();
    let mut existing_feeds = Vec::new();
    let mut new_feeds = Vec::new();
    let mut spec_entries = Vec::new();
    for prep in entries {
        let rec = &prep.record;
        if let Some(target_merge) =
            rec.merged_into_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            pending_merges.push((prep, target_merge.to_owned()));
            continue;
        }
        if let Some(ref fs) = rec.feed_source {
            if existing_feed_ids.contains(rec.id.trim()) {
                existing_feeds.push((prep, fs));
            } else {
                new_feeds.push((prep, fs));
            }
        } else if let Some(ref cs) = rec.collection_spec {
            spec_entries.push((prep, cs));
        }
    }
    Ok((spec_entries, existing_feeds, new_feeds))
}

fn record_feed_url_mapping(
    feed_url_by_id: &mut HashMap<String, String>,
    raw_incoming_id: &str,
    outcome: FeedMergeOutcome,
) {
    feed_url_by_id.insert(outcome.local_id, outcome.effective_url.clone());
    let trimmed = raw_incoming_id.trim();
    if !trimmed.is_empty() {
        feed_url_by_id.insert(trimmed.to_owned(), outcome.effective_url);
    }
}

impl SyncRepo<'_> {
    pub(super) async fn merge_incoming_entries_in_tx(
        tx: &mut WriteTx<'_>,
        entries: &[PreparedSyncEntry],
        incoming_memberships: &[SyncCollectionEntryRecord],
        ctx: &mut MergeContext<'_>,
    ) -> Result<Vec<InsertedItemCandidate>> {
        let mut inserted_candidates = Vec::new();
        let mut pending_merges: Vec<(&PreparedSyncEntry, String)> = Vec::new();
        let mut feed_url_by_id: HashMap<String, String> = HashMap::new();

        // Pass 1a: Feeds (existing local collection_ids first, retrying deferred URL swaps,
        // then new/URL-matched feeds), Tags, and Manual Lists/Want Collections so parent IDs map cleanly
        let (spec_entries, existing_feeds, new_feeds) =
            partition_pass_1a_entries(tx.conn(), entries, &mut pending_merges).await?;
        Self::merge_pass_1a_feeds_in_tx(tx, existing_feeds, new_feeds, &mut feed_url_by_id, ctx)
            .await?;
        for (prep, cs) in spec_entries {
            Self::merge_collection_spec_entry_in_tx(tx.conn(), prep, cs, ctx).await?;
        }

        // Map entry_id -> candidate parent feed_id from incoming `collection_entries`
        let incoming_parent_feed =
            build_incoming_parent_feed_map(incoming_memberships, &feed_url_by_id);

        // Pass 1b: Regular Catalog Works & Inbox Items
        for prep in entries {
            let rec = &prep.record;
            if rec.merged_into_id.as_deref().is_some_and(|s| !s.trim().is_empty())
                || rec.feed_source.is_some()
                || rec.collection_spec.is_some()
                || rec.kind == EntryKind::Collection
            {
                continue;
            }
            if let Some(cand) = Self::merge_single_work_or_item_in_tx(
                tx,
                prep,
                &incoming_parent_feed,
                &feed_url_by_id,
                ctx,
            )
            .await?
            {
                inserted_candidates.push(cand);
            }
        }

        // Pass 2: Merge redirects (`merged_into_id`)
        Self::apply_pending_merges_in_tx(tx, &pending_merges, ctx).await?;

        Ok(inserted_candidates)
    }

    async fn merge_pass_1a_feeds_in_tx(
        tx: &mut WriteTx<'_>,
        existing_feeds: Vec<(&PreparedSyncEntry, &SyncFeedSource)>,
        new_feeds: Vec<(&PreparedSyncEntry, &SyncFeedSource)>,
        feed_url_by_id: &mut HashMap<String, String>,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let mut moved_feeds: Vec<(String, String)> = Vec::new();
        let mut pending_existing = existing_feeds;
        loop {
            let mut next_deferred: Vec<(&PreparedSyncEntry, &SyncFeedSource)> = Vec::new();
            let prev_len = pending_existing.len();
            for (prep, fs) in pending_existing {
                if let Some(outcome) =
                    Self::merge_single_feed_entry_in_tx(tx, prep, fs, ctx).await?
                {
                    if outcome.url_changed {
                        moved_feeds.push((outcome.local_id.clone(), outcome.effective_url.clone()));
                    }
                    if outcome.deferred_url_collision {
                        next_deferred.push((prep, fs));
                    }
                    record_feed_url_mapping(feed_url_by_id, &prep.record.id, outcome);
                }
            }
            if next_deferred.is_empty() || next_deferred.len() >= prev_len {
                break;
            }
            pending_existing = next_deferred;
        }
        for (prep, fs) in new_feeds {
            if let Some(outcome) = Self::merge_single_feed_entry_in_tx(tx, prep, fs, ctx).await? {
                if outcome.url_changed {
                    moved_feeds.push((outcome.local_id.clone(), outcome.effective_url.clone()));
                }
                record_feed_url_mapping(feed_url_by_id, &prep.record.id, outcome);
            }
        }
        for (local_fid, new_url) in moved_feeds {
            Self::enforce_item_tombstones_for_feed_in_tx(tx, &local_fid, &new_url, ctx.now).await?;
        }
        Ok(())
    }

    async fn merge_collection_spec_entry_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        cs: &SyncCollectionSpec,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let rec = &prep.record;
        match cs.role {
            CollectionRole::Tag => {
                Self::merge_single_tag_entry_in_tx(tx, prep, cs, ctx).await?;
            }
            CollectionRole::List | CollectionRole::Want if !cs.is_dynamic() => {
                Self::merge_single_collection_entry_in_tx(tx, prep, cs, ctx).await?;
            }
            CollectionRole::System
                if !cs.is_dynamic()
                    && (rec.id.trim() == STARRED_COLLECTION_ID
                        || cs
                            .slug
                            .as_deref()
                            .is_some_and(|s| s.trim().eq_ignore_ascii_case("starred"))) =>
            {
                let mut upgraded = cs.clone();
                upgraded.role = CollectionRole::List;
                Self::merge_single_collection_entry_in_tx(tx, prep, &upgraded, ctx).await?;
            }
            CollectionRole::Feed
            | CollectionRole::System
            | CollectionRole::List
            | CollectionRole::Want => {}
        }
        Ok(())
    }

    async fn check_and_clear_feed_tombstones_in_tx(
        conn: &mut SqliteConnection,
        nat_key: &str,
        incoming_id: &str,
        effective_hlc: &str,
    ) -> Result<bool> {
        let mut keys = vec![nat_key];
        if !incoming_id.is_empty() && incoming_id != nat_key {
            keys.push(incoming_id);
        }
        let mut stale_keys = Vec::new();
        for key in keys {
            let tomb_hlc: Option<String> = sqlx::query_scalar(
                "SELECT hlc FROM sync_tombstones WHERE entity_type = 'feed' AND natural_key = ? LIMIT 1",
            )
            .bind(key)
            .fetch_optional(&mut *conn)
            .await?;
            if let Some(hlc) = tomb_hlc {
                if hlc.as_str() >= effective_hlc {
                    return Ok(true);
                }
                stale_keys.push(key);
            }
        }
        for stale in stale_keys {
            Self::clear_tombstone(&mut *conn, ActivityEntityType::Feed, stale).await?;
        }
        Ok(false)
    }

    async fn merge_single_feed_entry_in_tx(
        tx: &mut WriteTx<'_>,
        prep: &PreparedSyncEntry,
        fs: &SyncFeedSource,
        ctx: &mut MergeContext<'_>,
    ) -> Result<Option<FeedMergeOutcome>> {
        let rec = &prep.record;
        let feed_url = fs.feed_url.trim();
        if feed_url.is_empty() {
            return Ok(None);
        }
        let nat_key = feed_natural_key(feed_url);
        let entry_hlc = resolve_or_synthesize_hlc(
            &rec.hlc,
            rec.updated_at.or(rec.created_at),
            ctx.now,
            ctx.peer_id,
        );
        let fs_hlc = if fs.hlc.trim().is_empty() {
            entry_hlc.clone()
        } else {
            resolve_or_synthesize_hlc(
                &fs.hlc,
                rec.updated_at.or(rec.created_at),
                ctx.now,
                ctx.peer_id,
            )
        };
        let effective_hlc = if entry_hlc > fs_hlc { &entry_hlc } else { &fs_hlc };

        let incoming_id = rec.id.trim();
        if Self::check_and_clear_feed_tombstones_in_tx(
            tx.conn(),
            &nat_key,
            incoming_id,
            effective_hlc,
        )
        .await?
        {
            return Ok(None);
        }

        let det_id = deterministic_feed_id(feed_url);
        let existing: Option<ExistingFeedRow> = sqlx::query_as(
            "SELECT fs.collection_id, fs.feed_url, fs.source_type, e.kind AS entry_kind,
                    fs.feed_title, fs.last_polled_at, fs.is_active, fs.hlc
             FROM feed_sources fs
             JOIN entries e ON e.id = fs.collection_id
             WHERE fs.collection_id = ?
                OR fs.feed_url = ?
                OR (? = '' AND fs.collection_id = ?)
             ORDER BY CASE
                 WHEN fs.collection_id = ? THEN 0
                 WHEN fs.feed_url = ? THEN 1
                 ELSE 2
             END
             LIMIT 1",
        )
        .bind(incoming_id)
        .bind(feed_url)
        .bind(incoming_id)
        .bind(&det_id)
        .bind(incoming_id)
        .bind(feed_url)
        .fetch_optional(tx.conn())
        .await?;

        let clean_feed_title =
            fs.feed_title.as_deref().map(str::trim).filter(|s| !s.is_empty()).or_else(|| {
                let t = rec.title.trim();
                (!t.is_empty() && t != feed_url).then_some(t)
            });
        let clean_err = fs.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let fields = NormalizedFeedFields {
            feed_url,
            det_id,
            entry_hlc,
            fs_hlc,
            clean_feed_title,
            clean_desc: prep.clean_summary.as_deref(),
            clean_lang: fs.language.as_deref().map(str::trim).filter(|s| !s.is_empty()),
            clean_err,
            clean_polled_by: fs.last_polled_by.as_deref().map(str::trim).filter(|s| !s.is_empty()),
            clean_err_by: clean_err
                .and_then(|_| fs.last_error_by.as_deref().map(str::trim).filter(|s| !s.is_empty())),
            clean_consecutive_failures: normalize_consecutive_failures(
                clean_err,
                fs.consecutive_failures,
            ),
            interval: fs.poll_interval.clamp(5, 1440),
        };

        let outcome = if let Some(existing_feed) = existing {
            Self::update_existing_feed_in_tx(tx.conn(), existing_feed, fs, &fields, ctx.now).await?
        } else {
            Self::insert_new_feed_in_tx(tx, incoming_id, rec.created_at, fs, &fields, ctx.now)
                .await?
        };
        ctx.id_resolver.record(&rec.id, &outcome.local_id);
        Ok(Some(outcome))
    }

    async fn update_existing_feed_in_tx(
        tx: &mut SqliteConnection,
        existing: ExistingFeedRow,
        fs: &SyncFeedSource,
        fields: &NormalizedFeedFields<'_>,
        now: DateTime<Utc>,
    ) -> Result<FeedMergeOutcome> {
        let clamped_polled_at = clamp_incoming_timestamp_opt(fs.last_polled_at, now);
        let raw_poll_newer = match (clamped_polled_at, existing.last_polled_at) {
            (Some(inc_p), Some(loc_p)) => inc_p > loc_p,
            (Some(_), None) => true,
            _ => false,
        };

        let cfg = Self::maybe_update_existing_feed_config_in_tx(
            tx,
            &existing,
            fs,
            fields,
            raw_poll_newer,
            now,
        )
        .await?;

        let poll_matches_effective_url =
            canonicalize_sync_url(fields.feed_url) == canonicalize_sync_url(&cfg.effective_url);
        let apply_incoming_poll = poll_matches_effective_url
            && (raw_poll_newer || (cfg.url_changed && clamped_polled_at.is_some()));
        if apply_incoming_poll {
            sqlx::query(
                "UPDATE feed_sources
                 SET last_polled_at = ?,
                     last_polled_by = ?,
                     last_error = ?,
                     last_error_by = ?,
                     consecutive_failures = ?
                 WHERE collection_id = ?",
            )
            .bind(clamped_polled_at)
            .bind(fields.clean_polled_by)
            .bind(fields.clean_err)
            .bind(fields.clean_err_by)
            .bind(fields.clean_consecutive_failures)
            .bind(&existing.collection_id)
            .execute(&mut *tx)
            .await?;
        }

        Ok(FeedMergeOutcome {
            local_id: existing.collection_id,
            effective_url: cfg.effective_url,
            url_changed: cfg.url_changed,
            deferred_url_collision: cfg.deferred_url_collision,
        })
    }

    async fn can_apply_synced_feed_url_change_in_tx(
        tx: &mut SqliteConnection,
        existing: &ExistingFeedRow,
        incoming_url: &str,
    ) -> Result<bool> {
        let incoming_nat_key = feed_natural_key(incoming_url);
        let conflicting_owner: Option<String> = sqlx::query_scalar(
            "SELECT fs.collection_id FROM feed_sources fs
             WHERE fs.collection_id != ?
               AND (fs.feed_url = ? OR EXISTS (
                   SELECT 1 FROM entry_identifiers ei
                   WHERE ei.entry_id = fs.collection_id
                     AND ei.scheme = 'feed_url'
                     AND ei.value = ?
               ))
             LIMIT 1",
        )
        .bind(&existing.collection_id)
        .bind(incoming_url)
        .bind(&incoming_nat_key)
        .fetch_optional(&mut *tx)
        .await?;
        if conflicting_owner.is_some() {
            tracing::warn!(
                collection_id = %existing.collection_id,
                incoming_url = %incoming_url,
                "Skipping synced feed_url update due to conflict with another subscribed feed"
            );
            return Ok(false);
        }
        Ok(true)
    }

    #[allow(clippy::too_many_lines)]
    async fn maybe_update_existing_feed_config_in_tx(
        tx: &mut SqliteConnection,
        existing: &ExistingFeedRow,
        fs: &SyncFeedSource,
        fields: &NormalizedFeedFields<'_>,
        poll_newer: bool,
        now: DateTime<Utc>,
    ) -> Result<FeedConfigMergeOutcome> {
        let config_newer = fields.fs_hlc.as_str() > existing.hlc.as_str();
        let merged_type = if config_newer { fs.source_type } else { existing.source_type };
        let target_kind = entry_kind_for_feed_type(merged_type);
        let kind_mismatch = existing.entry_kind != target_kind;

        let wants_url_change = config_newer && fields.feed_url != existing.feed_url;
        let apply_url_change = wants_url_change
            && Self::can_apply_synced_feed_url_change_in_tx(tx, existing, fields.feed_url).await?;
        let deferred_url_collision = wants_url_change && !apply_url_change;
        let target_feed_url =
            if apply_url_change { fields.feed_url } else { existing.feed_url.as_str() };

        let target_feed_title = if config_newer {
            fields.clean_feed_title.or(existing.feed_title.as_deref())
        } else {
            existing.feed_title.as_deref().or(fields.clean_feed_title)
        };

        if !config_newer
            && !kind_mismatch
            && !apply_url_change
            && target_feed_title == existing.feed_title.as_deref()
            && merged_type == existing.source_type
        {
            return Ok(FeedConfigMergeOutcome {
                effective_url: existing.feed_url.clone(),
                url_changed: false,
                deferred_url_collision: false,
            });
        }

        let target_active = if config_newer { fs.is_active } else { existing.is_active };
        // Do not advance `feed_sources.hlc` when a URL move was blocked by a collision so
        // convergence succeeds once the colliding feed moves or is deleted.
        let target_hlc = if config_newer && !deferred_url_collision {
            fields.fs_hlc.as_str()
        } else {
            existing.hlc.as_str()
        };
        let clear_stale_errors = apply_url_change
            || (config_newer
                && !existing.is_active
                && target_active
                && !poll_newer
                && fields.clean_err.is_none());
        let clear_stale_polled = apply_url_change;
        sqlx::query(
            "UPDATE feed_sources
             SET feed_url = ?,
                 source_type = ?,
                 feed_title = ?,
                 language = COALESCE(?, language),
                 poll_interval = CASE WHEN ? THEN ? ELSE poll_interval END,
                 is_active = ?,
                 last_polled_at = CASE WHEN ? THEN NULL ELSE last_polled_at END,
                 last_polled_by = CASE WHEN ? THEN NULL ELSE last_polled_by END,
                 last_error = CASE WHEN ? THEN NULL ELSE last_error END,
                 last_error_by = CASE WHEN ? THEN NULL ELSE last_error_by END,
                 consecutive_failures = CASE WHEN ? THEN 0 ELSE consecutive_failures END,
                 hlc = ?
             WHERE collection_id = ?",
        )
        .bind(target_feed_url)
        .bind(merged_type)
        .bind(target_feed_title)
        .bind(fields.clean_lang)
        .bind(config_newer)
        .bind(fields.interval)
        .bind(target_active)
        .bind(clear_stale_polled)
        .bind(clear_stale_polled)
        .bind(clear_stale_errors)
        .bind(clear_stale_errors)
        .bind(clear_stale_errors)
        .bind(target_hlc)
        .bind(&existing.collection_id)
        .execute(&mut *tx)
        .await?;

        if apply_url_change {
            let new_nat_key = feed_natural_key(target_feed_url);
            entries_core::replace_entry_identifier_in_tx(
                &mut *tx,
                &existing.collection_id,
                "feed_url",
                &new_nat_key,
            )
            .await?;
            SyncRepo::copy_item_tombstones_for_feed_url_change(
                &mut *tx,
                &existing.feed_url,
                target_feed_url,
            )
            .await?;
        }

        let entry_title = target_feed_title.unwrap_or(target_feed_url);
        let target_entry_hlc = if deferred_url_collision {
            existing.hlc.as_str()
        } else if config_newer || kind_mismatch {
            fields.entry_hlc.as_str().max(target_hlc)
        } else {
            fields.entry_hlc.as_str()
        };
        sqlx::query(
            "UPDATE entries
             SET kind = ?,
                 title = ?,
                 summary = COALESCE(?, summary),
                 updated_at = ?,
                 hlc = CASE WHEN ? > hlc THEN ? ELSE hlc END
             WHERE id = ?",
        )
        .bind(target_kind)
        .bind(entry_title)
        .bind(fields.clean_desc)
        .bind(now)
        .bind(target_entry_hlc)
        .bind(target_entry_hlc)
        .bind(&existing.collection_id)
        .execute(&mut *tx)
        .await?;
        Ok(FeedConfigMergeOutcome {
            effective_url: target_feed_url.to_owned(),
            url_changed: apply_url_change,
            deferred_url_collision,
        })
    }

    async fn insert_new_feed_in_tx(
        tx: &mut WriteTx<'_>,
        incoming_id: &str,
        rec_created_at: Option<DateTime<Utc>>,
        fs: &SyncFeedSource,
        fields: &NormalizedFeedFields<'_>,
        now: DateTime<Utc>,
    ) -> Result<FeedMergeOutcome> {
        let local_id = if uuid::Uuid::parse_str(incoming_id).is_ok() {
            let incoming_taken: Option<i64> =
                sqlx::query_scalar("SELECT 1 FROM feed_sources WHERE collection_id = ? LIMIT 1")
                    .bind(incoming_id)
                    .fetch_optional(tx.conn())
                    .await?;
            if incoming_taken.is_none() {
                incoming_id.to_owned()
            } else {
                uuid::Uuid::new_v4().to_string()
            }
        } else {
            let det_taken: Option<i64> =
                sqlx::query_scalar("SELECT 1 FROM feed_sources WHERE collection_id = ? LIMIT 1")
                    .bind(&fields.det_id)
                    .fetch_optional(tx.conn())
                    .await?;
            if det_taken.is_some() {
                uuid::Uuid::new_v4().to_string()
            } else {
                fields.det_id.clone()
            }
        };
        let created_at = clamp_incoming_timestamp(rec_created_at.unwrap_or(now), now);
        let clamped_polled_at = clamp_incoming_timestamp_opt(fs.last_polled_at, now);
        FeedRepo::insert_feed_in_tx(
            tx,
            &NewFeedRow {
                id: &local_id,
                feed_url: fields.feed_url,
                feed_type: fs.source_type,
                title: fields.clean_feed_title,
                description: fields.clean_desc,
                language: fields.clean_lang,
                poll_interval: fields.interval,
                is_active: fs.is_active,
                last_polled: clamped_polled_at,
                last_polled_by: fields.clean_polled_by,
                last_error: fields.clean_err,
                last_error_by: fields.clean_err_by,
                consecutive_failures: fields.clean_consecutive_failures,
                created_at,
                updated_at: now,
                entry_hlc: Some(&fields.entry_hlc),
                feed_hlc: Some(&fields.fs_hlc),
            },
        )
        .await?;

        Ok(FeedMergeOutcome {
            local_id,
            effective_url: fields.feed_url.to_owned(),
            url_changed: false,
            deferred_url_collision: false,
        })
    }

    async fn merge_single_tag_entry_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        cs: &SyncCollectionSpec,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let rec = &prep.record;
        let clean_name = rec.title.trim();
        if clean_name.is_empty() {
            return Ok(());
        }
        let nat_key = tag_natural_key(clean_name);
        let entry_hlc = resolve_or_synthesize_hlc(
            &rec.hlc,
            rec.updated_at.or(rec.created_at),
            ctx.now,
            ctx.peer_id,
        );

        if Self::check_and_clear_tombstone_in_tx(tx, ActivityEntityType::Tag, &nat_key, &entry_hlc)
            .await?
        {
            return Ok(());
        }

        let det_id = deterministic_tag_id(clean_name);
        let tag_slug = format!("tag:{nat_key}");
        let rec_id = rec.id.trim();
        let parsed_color: Option<String> =
            HexColor::from_stored_tolerant(cs.color.as_deref()).map(String::from);

        ctx.tag_index.ensure_loaded(&mut *tx).await?;
        let found = ctx.tag_index.find_live_for_merge(&det_id, &tag_slug, &nat_key, rec_id);
        let tag_input =
            TagMergeInput { clean_name, nat_key: &nat_key, tag_slug, parsed_color, entry_hlc };

        let local_id = if let Some(existing_tag) = found {
            let local_id = existing_tag.id.clone();
            if tag_input.entry_hlc.as_str() > existing_tag.hlc.as_str() {
                Self::update_existing_tag_in_tx(tx, existing_tag, tag_input, ctx).await?;
            }
            local_id
        } else {
            Self::insert_new_tag_in_tx(
                tx,
                det_id,
                tag_input,
                rec.created_at.unwrap_or(ctx.now),
                ctx,
            )
            .await?
        };
        ctx.id_resolver.record(rec_id, &local_id);
        Ok(())
    }

    async fn update_existing_tag_in_tx(
        tx: &mut SqliteConnection,
        mut existing: IndexedTag,
        input: TagMergeInput<'_>,
        ctx: &mut MergeContext<'_>,
    ) -> Result<()> {
        let local_id = existing.id.clone();
        if input.parsed_color.is_some() && input.parsed_color != existing.color {
            sqlx::query("UPDATE collection_specs SET color = ? WHERE collection_id = ?")
                .bind(input.parsed_color.as_deref())
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;
            existing.color = input.parsed_color;
        }
        let slug_conflict: Option<String> = sqlx::query_scalar(
            "SELECT collection_id FROM collection_specs WHERE slug = ? AND collection_id != ?",
        )
        .bind(&input.tag_slug)
        .bind(&local_id)
        .fetch_optional(&mut *tx)
        .await?;
        if slug_conflict.is_none() {
            if existing.slug.as_deref() != Some(input.tag_slug.as_str()) {
                sqlx::query("UPDATE collection_specs SET slug = ? WHERE collection_id = ?")
                    .bind(&input.tag_slug)
                    .bind(&local_id)
                    .execute(&mut *tx)
                    .await?;
                entries_core::replace_entry_identifier_in_tx(tx, &local_id, "tag", input.nat_key)
                    .await?;
                existing.slug = Some(input.tag_slug);
            }
            sqlx::query("UPDATE entries SET title = ?, updated_at = ?, hlc = ? WHERE id = ?")
                .bind(input.clean_name)
                .bind(ctx.now)
                .bind(&input.entry_hlc)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;
            existing.title = input.clean_name.to_owned();
        } else {
            sqlx::query("UPDATE entries SET updated_at = ?, hlc = ? WHERE id = ?")
                .bind(ctx.now)
                .bind(&input.entry_hlc)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;
        }
        existing.hlc = input.entry_hlc;
        ctx.tag_index.insert_or_replace(existing);
        Ok(())
    }

    async fn insert_new_tag_in_tx(
        tx: &mut SqliteConnection,
        det_id: String,
        input: TagMergeInput<'_>,
        created_at: DateTime<Utc>,
        ctx: &mut MergeContext<'_>,
    ) -> Result<String> {
        let safe_created_at = clamp_incoming_timestamp(created_at, ctx.now);
        sqlx::query(
            "INSERT INTO entries (id, kind, title, created_at, updated_at, hlc)
             VALUES (?, 'collection', ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                 title = excluded.title,
                 updated_at = excluded.updated_at,
                 hlc = excluded.hlc",
        )
        .bind(&det_id)
        .bind(input.clean_name)
        .bind(safe_created_at)
        .bind(ctx.now)
        .bind(&input.entry_hlc)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT INTO collection_specs (collection_id, slug, collection_role, color)
             VALUES (?, ?, 'tag', ?)
             ON CONFLICT(collection_id) DO UPDATE SET
                 slug = excluded.slug,
                 color = COALESCE(excluded.color, collection_specs.color)",
        )
        .bind(&det_id)
        .bind(&input.tag_slug)
        .bind(input.parsed_color.as_deref())
        .execute(&mut *tx)
        .await?;

        entries_core::upsert_entry_identifier_in_tx(tx, &det_id, "tag", input.nat_key).await?;

        Self::record_activity(
            tx,
            ActivityEntityType::Tag,
            &det_id,
            ActivityAction::Create,
            ctx.now,
        )
        .await?;
        ctx.tag_index.insert_or_replace(IndexedTag {
            id: det_id.clone(),
            title: input.clean_name.to_owned(),
            slug: Some(input.tag_slug),
            color: input.parsed_color,
            hlc: input.entry_hlc,
            merged_into_id: None,
        });
        Ok(det_id)
    }

    async fn merge_single_work_or_item_in_tx(
        tx: &mut WriteTx<'_>,
        prep: &PreparedSyncEntry,
        incoming_parent_feed: &HashMap<&str, &str>,
        feed_url_by_id: &HashMap<String, String>,
        ctx: &mut MergeContext<'_>,
    ) -> Result<Option<InsertedItemCandidate>> {
        let rec = &prep.record;
        let clean_title = rec.title.trim();
        let final_title = if clean_title.is_empty() {
            rec.url.as_deref().map_or("", str::trim)
        } else {
            clean_title
        };
        if final_title.is_empty() {
            return Ok(None);
        }

        let (entry_hlc, state_hlc_opt) = resolve_work_hlcs(rec, ctx);

        if !rec.id.trim().is_empty()
            && Self::check_and_clear_tombstone_in_tx(
                tx.conn(),
                ActivityEntityType::Entry,
                rec.id.trim(),
                &entry_hlc,
            )
            .await?
        {
            return Ok(None);
        }

        let resolved_feed_url = Self::resolve_parent_feed_url_in_tx(
            tx.conn(),
            rec.id.trim(),
            incoming_parent_feed,
            feed_url_by_id,
            ctx,
        )
        .await?;
        let is_active_inbox = rec
            .state
            .as_ref()
            .is_some_and(|s| matches!(s.inbox_state, InboxState::Unread | InboxState::Dismissed));

        let tombstone_action = Self::check_item_tombstone_suppression_in_tx(
            tx.conn(),
            prep,
            resolved_feed_url.as_deref(),
            &entry_hlc,
            state_hlc_opt.as_deref(),
            is_active_inbox,
        )
        .await?;
        if tombstone_action == ItemTombstoneAction::SkipEntry {
            return Ok(None);
        }
        let suppress_payload = tombstone_action == ItemTombstoneAction::SuppressPayload;
        let det_inbox_id = prep
            .canon_url
            .as_deref()
            .map(|u| deterministic_item_id(resolved_feed_url.as_deref(), u));
        let target = WorkMergeTarget {
            target_kind: resolve_work_target_kind(prep),
            final_title,
            entry_hlc: &entry_hlc,
            now: ctx.now,
        };
        let Some((target_id, was_created_new, incoming_won_entry_hlc)) =
            Self::upsert_or_merge_work_entry_in_tx(
                tx,
                prep,
                &target,
                build_work_identifiers(prep),
                det_inbox_id,
                is_active_inbox,
            )
            .await?
        else {
            return Ok(None);
        };
        ctx.id_resolver.record(&rec.id, &target_id);

        if !suppress_payload {
            Self::merge_entry_content_in_tx(
                tx.conn(),
                &target_id,
                prep,
                incoming_won_entry_hlc,
                ctx.now,
            )
            .await?;
        }
        let was_new_inbox_item =
            if let Some(st) = (!suppress_payload).then_some(rec.state.as_ref()).flatten() {
                let st_hlc = state_hlc_opt.as_deref().unwrap_or(entry_hlc.as_str());
                Self::merge_entry_state_in_tx(tx.conn(), &target_id, st, st_hlc, ctx.now).await?
            } else {
                false
            };

        Ok(build_inserted_item_candidate(
            prep,
            target_id,
            final_title,
            was_new_inbox_item,
            was_created_new,
        ))
    }

    async fn upsert_or_merge_work_entry_in_tx(
        tx: &mut WriteTx<'_>,
        prep: &PreparedSyncEntry,
        target: &WorkMergeTarget<'_>,
        idents: Vec<EntryIdentifier>,
        det_inbox_id: Option<String>,
        is_active_inbox: bool,
    ) -> Result<Option<(String, bool, bool)>> {
        let pre_matched_id = Self::resolve_existing_work_or_item_id_in_tx(
            tx.conn(),
            prep,
            target.target_kind,
            &idents,
            det_inbox_id.as_deref(),
        )
        .await?;
        if let Some(existing_id) = pre_matched_id {
            if Self::is_immutable_or_collection_target_in_tx(tx.conn(), &existing_id).await? {
                return Ok(None);
            }
            let incoming_won_hlc = Self::merge_into_existing_entry_row_in_tx(
                tx.conn(),
                &existing_id,
                prep,
                target,
                &idents,
            )
            .await?;
            Ok(Some((existing_id, false, incoming_won_hlc)))
        } else {
            let rec_id = prep.record.id.trim();
            let preferred_id = if (is_active_inbox && det_inbox_id.is_some()) || rec_id.is_empty() {
                det_inbox_id
            } else {
                Some(rec_id.to_owned())
            };
            if let Some(ref pid) = preferred_id
                && Self::is_immutable_or_collection_target_in_tx(tx.conn(), pid).await?
            {
                return Ok(None);
            }
            let created_id =
                Self::insert_new_work_or_item_in_tx(tx, prep, preferred_id, target, idents).await?;
            Ok(Some((created_id, true, true)))
        }
    }

    async fn is_immutable_or_collection_target_in_tx(
        tx: &mut SqliteConnection,
        entry_id: &str,
    ) -> Result<bool> {
        if is_protected_system_collection_id(entry_id) {
            return Ok(true);
        }
        let existing_kind: Option<EntryKind> =
            sqlx::query_scalar("SELECT kind FROM entries WHERE id = ?")
                .bind(entry_id)
                .fetch_optional(&mut *tx)
                .await?;
        if existing_kind == Some(EntryKind::Collection) {
            return Ok(true);
        }
        let has_spec: Option<i64> =
            sqlx::query_scalar("SELECT 1 FROM collection_specs WHERE collection_id = ? LIMIT 1")
                .bind(entry_id)
                .fetch_optional(&mut *tx)
                .await?;
        Ok(has_spec.is_some())
    }

    async fn resolve_parent_feed_url_in_tx(
        tx: &mut SqliteConnection,
        trimmed_entry_id: &str,
        incoming_parent_feed: &HashMap<&str, &str>,
        feed_url_by_id: &HashMap<String, String>,
        ctx: &MergeContext<'_>,
    ) -> Result<Option<String>> {
        let Some(raw_fid) = incoming_parent_feed.get(trimmed_entry_id) else {
            return Ok(None);
        };
        let mapped_fid = ctx.id_resolver.mapped(raw_fid);
        if let Some(u) = feed_url_by_id.get(mapped_fid) {
            return Ok(Some(u.clone()));
        }
        Ok(sqlx::query_scalar("SELECT feed_url FROM feed_sources WHERE collection_id = ?")
            .bind(mapped_fid)
            .fetch_optional(&mut *tx)
            .await?)
    }

    async fn check_item_tombstone_suppression_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        resolved_feed_url: Option<&str>,
        entry_hlc: &str,
        state_hlc_opt: Option<&str>,
        is_active_inbox: bool,
    ) -> Result<ItemTombstoneAction> {
        let rec = &prep.record;
        let carries_item_payload = rec.state.is_some() || rec.content.is_some();
        let Some(ref item_url) = prep.canon_url else {
            return Ok(ItemTombstoneAction::Allow);
        };
        if !carries_item_payload {
            return Ok(ItemTombstoneAction::Allow);
        }

        let nat_feed = item_natural_key(resolved_feed_url, item_url);
        let nat_manual = item_natural_key(None, item_url);
        let item_tombs: Vec<ItemTombstoneRow> = if resolved_feed_url.is_some() {
            sqlx::query_as(
                "SELECT natural_key, hlc FROM sync_tombstones
                 WHERE entity_type = 'item' AND natural_key IN (?, ?)",
            )
            .bind(&nat_feed)
            .bind(&nat_manual)
            .fetch_all(&mut *tx)
            .await?
        } else {
            let suffix_like = format!("%\0{}", crate::db::escape_like_literal(item_url));
            sqlx::query_as(
                "SELECT natural_key, hlc FROM sync_tombstones
                 WHERE entity_type = 'item'
                   AND (natural_key = ? OR natural_key LIKE ? ESCAPE '\\')",
            )
            .bind(&nat_manual)
            .bind(&suffix_like)
            .fetch_all(&mut *tx)
            .await?
        };

        let max_inc_hlc = state_hlc_opt.map_or(entry_hlc, |sh| sh.max(entry_hlc));
        let rec_id = rec.id.trim();
        let det_manual_id = deterministic_entity_uuid("item", &nat_manual);
        let exact_suffix = format!("\0{item_url}");

        let mut suppressed = false;
        for tomb in item_tombs {
            let tk = &tomb.natural_key;
            if tk != &nat_feed && tk != &nat_manual && !tk.ends_with(&exact_suffix) {
                continue;
            }
            let exact_scope = tk == &nat_feed
                || (resolved_feed_url.is_none() && tk == &nat_manual)
                || (!rec_id.is_empty() && deterministic_entity_uuid("item", tk) == rec_id);
            let legacy_feed_fallback =
                resolved_feed_url.is_none() && is_active_inbox && rec_id != det_manual_id;
            if !exact_scope && !legacy_feed_fallback {
                continue;
            }
            if max_inc_hlc <= tomb.hlc.as_str() {
                suppressed = true;
            } else if exact_scope {
                Self::clear_tombstone(tx, ActivityEntityType::Item, tk).await?;
            }
        }
        if !suppressed {
            return Ok(ItemTombstoneAction::Allow);
        }
        // Suppress item resurrection; if pure reader item with no catalog metadata, skip entirely
        if rec.identifiers.iter().all(|id| matches!(id.scheme.as_str(), "url" | "arxiv"))
            && rec.release_year.is_none()
            && rec.metadata.is_none()
        {
            Ok(ItemTombstoneAction::SkipEntry)
        } else {
            Ok(ItemTombstoneAction::SuppressPayload)
        }
    }

    async fn resolve_existing_work_or_item_id_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        target_kind: EntryKind,
        idents: &[EntryIdentifier],
        det_inbox_id: Option<&str>,
    ) -> Result<Option<String>> {
        let rec_id = prep.record.id.trim();
        if !rec_id.is_empty()
            && let Some(by_id) = CatalogRepo::follow_merged_id_in_tx(tx, rec_id).await?
        {
            return Ok(Some(by_id));
        }
        if let Some(by_ident) =
            Self::find_existing_entry_by_identifiers_in_tx(tx, target_kind, idents).await?
        {
            return Ok(Some(by_ident));
        }
        if let (Some(det_id), Some(c_url)) = (det_inbox_id, prep.canon_url.as_deref()) {
            return Ok(resolve_dedup_entry_id(tx, det_id, c_url, prep.arxiv_id.as_deref()).await?);
        }
        Ok(None)
    }

    async fn insert_new_work_or_item_in_tx(
        tx: &mut WriteTx<'_>,
        prep: &PreparedSyncEntry,
        preferred_id: Option<String>,
        target: &WorkMergeTarget<'_>,
        idents: Vec<EntryIdentifier>,
    ) -> Result<String> {
        let rec = &prep.record;
        let clamped_updated =
            clamp_incoming_timestamp(rec.updated_at.unwrap_or(target.now), target.now);
        let clamped_created = clamp_incoming_timestamp_opt(rec.created_at, target.now);
        let clamped_published =
            rec.published_at.map(|ts| clamp_incoming_upper_timestamp(ts, target.now));
        let upsert_input = UpsertEntryInput {
            id: preferred_id,
            kind: Some(target.target_kind),
            title: target.final_title.to_owned(),
            creators: rec.creators.clone(),
            url: rec.url.clone(),
            published_at: clamped_published,
            release_year: rec.release_year,
            summary: prep.clean_summary.clone(),
            thumbnail_url: prep.clean_thumb.clone(),
            metadata: rec.metadata.clone(),
            identifiers: idents,
            parent_collection_id: None,
            parent_position: None,
        };
        let (created_id, _) = CatalogRepo::upsert_entry_with_created_in_tx(
            tx,
            &upsert_input,
            target.target_kind,
            target.final_title,
            clamped_updated,
            clamped_created,
            Some(target.entry_hlc),
        )
        .await?;
        Ok(created_id)
    }

    async fn merge_entry_content_in_tx(
        tx: &mut SqliteConnection,
        target_id: &str,
        prep: &PreparedSyncEntry,
        incoming_won_entry_hlc: bool,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let rec = &prep.record;
        if rec.content.is_none() && prep.clean_html.is_none() && prep.clean_audio_url.is_none() {
            return Ok(());
        }

        let existing_content: Option<ExistingContentRow> = sqlx::query_as(
            "SELECT content_html, content_type FROM entry_content WHERE entry_id = ?",
        )
        .bind(target_id)
        .fetch_optional(&mut *tx)
        .await?;

        let merged_html = existing_content.as_ref().map_or_else(
            || prep.clean_html.clone(),
            |ex| {
                roche_core::crdt::pick_richer_text(
                    ex.content_html.as_deref(),
                    prep.clean_html.as_deref(),
                )
                .map(str::to_owned)
            },
        );
        let merged_ct = match &existing_content {
            None => prep.resolved_content_type,
            Some(ex) if incoming_won_entry_hlc && rec.content.is_some() => {
                prep.resolved_content_type
            }
            Some(ex) => ex.content_type,
        };

        if existing_content.as_ref().is_none_or(|ex| {
            ex.content_html.as_deref() != merged_html.as_deref() || ex.content_type != merged_ct
        }) {
            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = excluded.content_html,
                     content_type = excluded.content_type",
            )
            .bind(target_id)
            .bind(merged_html.as_deref())
            .bind(merged_ct)
            .execute(&mut *tx)
            .await?;
        }

        let has_incoming_audio = prep.clean_audio_url.is_some()
            || (merged_ct == ContentType::Audio && prep.clean_duration.is_some());
        let should_write_audio = has_incoming_audio
            && (existing_content.is_none()
                || incoming_won_entry_hlc
                || merged_ct == ContentType::Audio);
        if should_write_audio {
            let safe_created = clamp_incoming_timestamp(rec.created_at.unwrap_or(now), now);
            if incoming_won_entry_hlc && prep.clean_audio_url.is_some() {
                MediaRepo::upsert_audio_enclosure_overwrite_in_tx(
                    tx,
                    target_id,
                    prep.clean_audio_url.as_deref(),
                    prep.clean_duration,
                    safe_created,
                )
                .await?;
            } else {
                MediaRepo::upsert_audio_enclosure_in_tx(
                    tx,
                    target_id,
                    prep.clean_audio_url.as_deref(),
                    prep.clean_duration,
                    safe_created,
                )
                .await?;
            }
        } else if incoming_won_entry_hlc
            && rec.content.is_some()
            && prep.clean_audio_url.is_none()
            && (merged_ct != ContentType::Audio || prep.clean_duration.is_none())
        {
            sqlx::query(
                "DELETE FROM entry_media
                 WHERE entry_id = ? AND media_type = 'audio' AND local_path IS NULL",
            )
            .bind(target_id)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    async fn merge_entry_state_in_tx(
        tx: &mut SqliteConnection,
        target_id: &str,
        st: &SyncEntryState,
        st_hlc: &str,
        now: DateTime<Utc>,
    ) -> Result<bool> {
        let clamped_prog = if st.progress.is_finite() { st.progress.clamp(0.0, 1.0) } else { 0.0 };
        let existing_state: Option<EntryStateRow> = sqlx::query_as(
            "SELECT inbox_state, read_at, progress, interaction_updated_at, hlc
             FROM entry_state WHERE entry_id = ?",
        )
        .bind(target_id)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(existing) = existing_state {
            let ex_snap = roche_core::crdt::EntryStateSnapshot {
                inbox_state: existing.inbox_state,
                read_at: existing.read_at,
                progress: existing.progress,
                interaction_updated_at: existing.interaction_updated_at,
                hlc: existing.hlc.clone(),
            };
            let inc_snap = roche_core::crdt::EntryStateSnapshot {
                inbox_state: st.inbox_state,
                read_at: st.read_at,
                progress: clamped_prog,
                interaction_updated_at: st.interaction_updated_at,
                hlc: st_hlc.to_owned(),
            };
            let next = roche_core::crdt::resolve_merged_entry_state(&ex_snap, &inc_snap, now);
            if next.inbox_state != existing.inbox_state
                || next.read_at != existing.read_at
                || (next.progress - existing.progress).abs() > f64::EPSILON
                || next.interaction_updated_at != existing.interaction_updated_at
                || next.hlc != existing.hlc
            {
                sqlx::query(
                    "UPDATE entry_state
                     SET inbox_state = ?,
                         read_at = ?,
                         progress = ?,
                         interaction_updated_at = ?,
                         updated_at = ?,
                         hlc = ?
                     WHERE entry_id = ?",
                )
                .bind(next.inbox_state)
                .bind(next.read_at)
                .bind(next.progress)
                .bind(next.interaction_updated_at)
                .bind(now)
                .bind(&next.hlc)
                .bind(target_id)
                .execute(&mut *tx)
                .await?;
            }
            return Ok(false);
        }

        let was_new_inbox_item =
            matches!(st.inbox_state, InboxState::Unread | InboxState::Dismissed);
        let inter_ts = clamp_incoming_timestamp_opt(st.interaction_updated_at, now)
            .map(|ts| ts.trunc_subsecs(3));
        let inc_read_at = clamp_incoming_timestamp_opt(st.read_at, now);
        let read_at = if st.inbox_state == InboxState::Dismissed {
            inc_read_at.or(inter_ts).or(Some(now))
        } else {
            inc_read_at
        };
        sqlx::query(
            "INSERT INTO entry_state
             (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(target_id)
        .bind(st.inbox_state)
        .bind(read_at)
        .bind(clamped_prog)
        .bind(inter_ts)
        .bind(now)
        .bind(st_hlc)
        .execute(&mut *tx)
        .await?;
        Ok(was_new_inbox_item)
    }

    async fn find_existing_entry_by_identifiers_in_tx(
        tx: &mut SqliteConnection,
        kind: EntryKind,
        idents: &[EntryIdentifier],
    ) -> Result<Option<String>> {
        for ident in idents {
            let scheme = crate::db::normalize_identifier_scheme(&ident.scheme);
            if scheme.is_empty() || matches!(scheme.as_str(), "url" | "feed_url" | "tag" | "slug") {
                continue;
            }
            let val = crate::db::normalize_identifier_value(kind, &scheme, &ident.value);
            if val.is_empty() {
                continue;
            }
            let found = entries_core::find_entry_id_by_identifier_in_tx(tx, &scheme, &val).await?;
            if let Some(raw_eid) = found
                && let Some(canon) = CatalogRepo::follow_merged_id_in_tx(tx, &raw_eid).await?
            {
                return Ok(Some(canon));
            }
        }
        Ok(None)
    }

    async fn merge_into_existing_entry_row_in_tx(
        tx: &mut SqliteConnection,
        existing_id: &str,
        prep: &PreparedSyncEntry,
        target: &WorkMergeTarget<'_>,
        idents: &[EntryIdentifier],
    ) -> Result<bool> {
        let Some(existing) = sqlx::query_as::<_, ExistingEntryRow>(
            "SELECT kind, title, creators_json, url, published_at, release_year,
                    summary, thumbnail_url, metadata_json, hlc
             FROM entries WHERE id = ?",
        )
        .bind(existing_id)
        .fetch_optional(&mut *tx)
        .await?
        else {
            return Ok(false);
        };

        let inc_newer = target.entry_hlc > existing.hlc.as_str();
        let (merged, changed) = compute_merged_entry_row(&existing, prep, target);
        if changed {
            sqlx::query(
                "UPDATE entries
                 SET kind = ?,
                     title = ?,
                     creators_json = ?,
                     url = ?,
                     published_at = ?,
                     release_year = ?,
                     summary = ?,
                     thumbnail_url = ?,
                     metadata_json = ?,
                     updated_at = ?,
                     hlc = ?
                 WHERE id = ?",
            )
            .bind(merged.kind)
            .bind(&merged.title)
            .bind(&merged.creators_json)
            .bind(merged.url.as_deref())
            .bind(merged.published_at)
            .bind(merged.release_year)
            .bind(merged.summary.as_deref())
            .bind(merged.thumbnail_url.as_deref())
            .bind(&merged.metadata_json)
            .bind(target.now)
            .bind(&merged.hlc)
            .bind(existing_id)
            .execute(&mut *tx)
            .await?;
        }

        for ident in idents {
            let scheme = crate::db::normalize_identifier_scheme(&ident.scheme);
            if matches!(scheme.as_str(), "slug" | "tag" | "feed_url") {
                continue;
            }
            let val = crate::db::normalize_identifier_value(merged.kind, &scheme, &ident.value);
            if scheme.is_empty() || val.is_empty() {
                continue;
            }
            entries_core::upsert_entry_identifier_in_tx(tx, existing_id, &scheme, &val).await?;
        }
        Ok(inc_newer)
    }
}

fn build_inserted_item_candidate(
    prep: &PreparedSyncEntry,
    target_id: String,
    final_title: &str,
    was_new_inbox_item: bool,
    was_created_new: bool,
) -> Option<InsertedItemCandidate> {
    let rec = &prep.record;
    if !(was_new_inbox_item || (was_created_new && rec.content.is_some())) {
        return None;
    }
    let parsed_u = prep.parsed_url.as_ref()?;
    Some((
        target_id,
        PollItemCandidate {
            title: final_title.to_owned(),
            url: parsed_u.clone(),
            creators: rec.creators.clone(),
            published_at: rec.published_at,
            summary: prep.clean_summary.clone().unwrap_or_default(),
            content_html: prep.clean_html.clone(),
            thumbnail_url: prep.clean_thumb.clone(),
            audio_url: prep.clean_audio_url.clone(),
            duration: prep.clean_duration,
            content_type: prep.resolved_content_type,
        },
    ))
}

fn compute_merged_entry_row(
    existing: &ExistingEntryRow,
    prep: &PreparedSyncEntry,
    target: &WorkMergeTarget<'_>,
) -> (ExistingEntryRow, bool) {
    let rec = &prep.record;
    let inc_newer = target.entry_hlc > existing.hlc.as_str();
    let kind = if inc_newer
        && existing.kind.is_reader_openable()
        && target.target_kind.is_reader_openable()
    {
        target.target_kind
    } else {
        existing.kind
    };
    let title = roche_core::crdt::compute_merged_entry_title(
        &existing.title,
        existing.url.as_deref(),
        rec.url.as_deref(),
        target.target_kind,
        target.final_title,
        inc_newer,
    );

    let clean_creators: Vec<String> =
        rec.creators.iter().map(|c| c.trim().to_owned()).filter(|c| !c.is_empty()).collect();
    let inc_creators_json =
        serde_json::to_string(&clean_creators).unwrap_or_else(|_| "[]".to_owned());
    let creators_json =
        if (existing.creators_json == "[]" || inc_newer) && inc_creators_json != "[]" {
            inc_creators_json
        } else {
            existing.creators_json.clone()
        };

    let clean_inc_url = rec.url.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let url = if inc_newer && clean_inc_url.is_some() {
        clean_inc_url.map(str::to_owned)
    } else {
        existing.url.clone().or_else(|| clean_inc_url.map(str::to_owned))
    };
    let clamped_published =
        rec.published_at.map(|ts| clamp_incoming_upper_timestamp(ts, target.now));
    let published_at = existing.published_at.or(clamped_published);
    let release_year = if inc_newer && rec.release_year.is_some() {
        rec.release_year
    } else {
        existing.release_year.or(rec.release_year)
    };
    let summary = roche_core::crdt::pick_richer_text(
        existing.summary.as_deref(),
        prep.clean_summary.as_deref(),
    )
    .map(str::to_owned);
    let thumbnail_url = existing.thumbnail_url.clone().or_else(|| prep.clean_thumb.clone());
    let metadata_json = roche_core::crdt::merge_entry_metadata_json(
        &existing.metadata_json,
        rec.metadata.as_ref(),
        inc_newer,
    );
    let hlc = if inc_newer { target.entry_hlc.to_owned() } else { existing.hlc.clone() };

    let changed = kind != existing.kind
        || title != existing.title
        || creators_json != existing.creators_json
        || url != existing.url
        || published_at != existing.published_at
        || release_year != existing.release_year
        || summary != existing.summary
        || thumbnail_url != existing.thumbnail_url
        || metadata_json != existing.metadata_json
        || hlc != existing.hlc;

    (
        ExistingEntryRow {
            kind,
            title,
            creators_json,
            url,
            published_at,
            release_year,
            summary,
            thumbnail_url,
            metadata_json,
            hlc,
        },
        changed,
    )
}

fn build_incoming_parent_feed_map<'a>(
    incoming_memberships: &'a [SyncCollectionEntryRecord],
    feed_url_by_id: &HashMap<String, String>,
) -> HashMap<&'a str, &'a str> {
    let mut incoming_parent_feed: HashMap<&'a str, &'a str> = HashMap::new();
    for ce in incoming_memberships {
        let cid = ce.collection_id.trim();
        let eid = ce.entry_id.trim();
        if cid.is_empty() || eid.is_empty() || is_protected_system_collection_id(cid) {
            continue;
        }
        if feed_url_by_id.contains_key(cid) {
            incoming_parent_feed.insert(eid, cid);
        } else {
            incoming_parent_feed.entry(eid).or_insert(cid);
        }
    }
    incoming_parent_feed
}

fn resolve_work_hlcs(rec: &SyncEntryRecord, ctx: &MergeContext<'_>) -> (String, Option<String>) {
    let entry_hlc = resolve_or_synthesize_hlc(
        &rec.hlc,
        rec.updated_at.or(rec.created_at),
        ctx.now,
        ctx.peer_id,
    );
    let state_hlc_opt = rec.state.as_ref().map(|st| {
        resolve_or_synthesize_hlc(
            &st.hlc,
            st.interaction_updated_at.or(rec.updated_at).or(rec.created_at),
            ctx.now,
            ctx.peer_id,
        )
    });
    (entry_hlc, state_hlc_opt)
}

const fn resolve_work_target_kind(prep: &PreparedSyncEntry) -> EntryKind {
    prep.record.kind
}

fn build_work_identifiers(prep: &PreparedSyncEntry) -> Vec<EntryIdentifier> {
    let mut idents: Vec<EntryIdentifier> = prep
        .record
        .identifiers
        .iter()
        .filter(|i| {
            !matches!(
                crate::db::normalize_identifier_scheme(&i.scheme).as_str(),
                "slug" | "tag" | "feed_url"
            )
        })
        .cloned()
        .collect();
    if let Some(ref c_url) = prep.canon_url
        && !idents.iter().any(|i| crate::db::normalize_identifier_scheme(&i.scheme) == "url")
    {
        idents.push(EntryIdentifier { scheme: "url".to_owned(), value: c_url.clone() });
    }
    if let Some(ref aid) = prep.arxiv_id
        && !idents.iter().any(|i| crate::db::normalize_identifier_scheme(&i.scheme) == "arxiv")
    {
        idents.push(EntryIdentifier { scheme: "arxiv".to_owned(), value: aid.clone() });
    }
    idents
}
