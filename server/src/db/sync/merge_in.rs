use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, SubsecRound, Utc};
use sqlx::SqliteConnection;
use url::Url;

use super::{
    SyncRepo,
    activity::SyncTombstone,
    hlc::{HlcClock, resolve_or_synthesize_hlc},
    hrw::{NetworkTier, SERVER_PEER_ID, normalize_consecutive_failures},
    identity::{
        canonical_ordering_json, canonicalize_sync_url, collection_entry_natural_key,
        deterministic_auto_consumption_log_id, deterministic_comparison_id,
        deterministic_consumption_log_id, deterministic_entity_uuid, deterministic_feed_id,
        deterministic_item_id, deterministic_system_collection_id, deterministic_tag_id,
        feed_natural_key, item_natural_key, tag_natural_key,
    },
    models::{
        InsertedItemCandidate, PeerSyncRequest, PeerSyncResponse, SyncCollectionEntryRecord,
        SyncCollectionGeneratorRecord, SyncCollectionSpec, SyncComparisonRecord,
        SyncConsumptionLogRecord, SyncEntryRecord, SyncEntryState, SyncFeedSource,
    },
};
use crate::{
    content::{HtmlSanitizer, MAX_SUMMARY_CHARS, SafeHttpClient, is_direct_audio_url},
    db::{
        ActivityAction, ActivityEntityType, CatalogRepo, CollectionMode, CollectionRole,
        ConsumptionStatus, ContentType, EntryKind, FeedRepo, FeedType, HexColor, InboxState,
        ItemRepo, MediaRepo, PollItemCandidate, UpsertEntryInput,
        feeds::{NewFeedRow, entry_kind_for_feed_type, resolve_dedup_entry_id},
    },
    feeds::arxiv::ArxivParser,
    ranking::validate_ordering,
};

type ExistingFeedTuple =
    (String, String, FeedType, Option<String>, Option<DateTime<Utc>>, bool, String);
type ExistingTagTuple = (String, String, Option<String>, Option<String>, String);
type ExistingStateTuple = (InboxState, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, String);
type ExistingStateRef<'a> =
    (InboxState, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, &'a str);
type MergedStateTuple = (InboxState, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, String);
type ExistingGeneratorTuple = (bool, Option<String>, Option<DateTime<Utc>>, DateTime<Utc>, String);
type ExistingEntryRowTuple = (
    EntryKind,
    String,
    String,
    Option<String>,
    Option<DateTime<Utc>>,
    Option<i32>,
    Option<String>,
    Option<String>,
    String,
    String,
);

#[derive(Debug)]
pub(super) struct PreparedSyncEntry {
    pub record: SyncEntryRecord,
    pub parsed_url: Option<Url>,
    pub canon_url: Option<String>,
    pub arxiv_id: Option<String>,
    pub clean_summary: Option<String>,
    pub clean_html: Option<String>,
    pub clean_thumb: Option<String>,
    pub clean_audio_url: Option<String>,
    pub clean_duration: Option<i64>,
    pub resolved_content_type: ContentType,
}

impl PreparedSyncEntry {
    pub(super) fn from_record(rec: SyncEntryRecord) -> Self {
        let parsed_url = rec
            .url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| Url::parse(u).ok());
        let canon_url = rec
            .url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(canonicalize_sync_url)
            .filter(|s| !s.is_empty());
        let arxiv_id = rec.url.as_deref().and_then(ArxivParser::extract_arxiv_id);

        let raw_html = rec.content.as_ref().and_then(|c| c.content_html.as_deref());
        let clean_html = raw_html
            .map(|h| HtmlSanitizer::clean_with_base(h, parsed_url.as_ref()))
            .filter(|h| !h.trim().is_empty());

        let clean_summary = if rec.content.is_some() || rec.state.is_some() {
            rec.summary
                .as_deref()
                .map(|s| HtmlSanitizer::plain_text_excerpt(s, MAX_SUMMARY_CHARS))
                .filter(|s| !s.is_empty())
                .or_else(|| {
                    clean_html
                        .as_deref()
                        .map(|h| HtmlSanitizer::plain_text_excerpt_from_clean(h, MAX_SUMMARY_CHARS))
                        .filter(|s| !s.is_empty())
                })
        } else {
            rec.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned)
        };

        let clean_thumb = rec
            .thumbnail_url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| {
                HtmlSanitizer::resolve_and_validate_thumbnail_url(u, parsed_url.as_ref())
                    .or_else(|| SafeHttpClient::parse_url(u).ok().map(String::from))
            })
            .or_else(|| {
                clean_html.as_deref().and_then(|h| {
                    HtmlSanitizer::extract_first_image_url_with_base(h, parsed_url.as_ref())
                })
            });

        let base_content_type = rec.content.as_ref().map_or_else(
            || match rec.kind {
                EntryKind::Video => ContentType::Video,
                EntryKind::Paper => ContentType::Paper,
                EntryKind::PodcastEpisode => ContentType::Audio,
                _ => ContentType::Article,
            },
            |c| c.content_type,
        );

        let clean_audio_url = rec
            .content
            .as_ref()
            .and_then(|c| c.audio_url.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| Url::parse(u).ok())
            .map(String::from)
            .or_else(|| {
                parsed_url
                    .as_ref()
                    .filter(|u| base_content_type == ContentType::Audio && is_direct_audio_url(u))
                    .map(|u| u.as_str().to_owned())
            });

        let clean_duration = rec.content.as_ref().and_then(|c| c.duration).filter(|&d| d > 0);
        let resolved_content_type =
            if clean_audio_url.is_some() { ContentType::Audio } else { base_content_type };

        Self {
            record: rec,
            parsed_url,
            canon_url,
            arxiv_id,
            clean_summary,
            clean_html,
            clean_thumb,
            clean_audio_url,
            clean_duration,
            resolved_content_type,
        }
    }
}

impl SyncRepo<'_> {
    pub async fn merge_peer_state(
        &self,
        req: &PeerSyncRequest,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        self.merge_peer_state_with_limit(req, super::models::MAX_PEER_SYNC_PAGE_CHANGES).await
    }

    #[allow(clippy::too_many_lines)]
    pub async fn merge_peer_state_with_limit(
        &self,
        req: &PeerSyncRequest,
        max_page_changes: usize,
    ) -> Result<(PeerSyncResponse, Vec<InsertedItemCandidate>)> {
        let now = Self::now_utc();
        let now_ms = now.timestamp_millis();
        let peer_id = req.peer_id.trim();
        let safe_peer = if peer_id.is_empty() { "peer" } else { peer_id };
        let device_name = req
            .device_name
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(safe_peer);
        let measured_weight = if req.network_tier == NetworkTier::Offline {
            Some(0.0)
        } else {
            req.weight.map(|w| w.clamp(0.2, 10.0))
        };

        // Run HTML sanitization and URL parsing off the SQLite write lock
        let prepared_entries = if req.entries.is_empty() {
            Vec::new()
        } else {
            let raw = req.entries.clone();
            tokio::task::spawn_blocking(move || {
                raw.into_iter().map(PreparedSyncEntry::from_record).collect::<Vec<_>>()
            })
            .await?
        };

        let mut tx = crate::db::begin_write(self.pool).await?;

        Self::ensure_server_peer_in_tx(&mut tx, now).await?;
        if !peer_id.is_empty() && peer_id != SERVER_PEER_ID {
            Self::upsert_peer_in_tx(
                &mut tx,
                peer_id,
                device_name,
                req.network_tier,
                measured_weight,
                now,
            )
            .await?;
        }

        let stored_hlc: String = sqlx::query_scalar("SELECT last_hlc FROM sync_clock WHERE id = 1")
            .fetch_one(&mut *tx)
            .await?;
        let mut clock =
            HlcClock::new(SERVER_PEER_ID, (!stored_hlc.is_empty()).then_some(stored_hlc.as_str()));
        if let Some(ref c_hlc) = req.client_hlc {
            clock.witness_str(c_hlc, now_ms);
        }
        witness_request_hlcs(&mut clock, req, now_ms);

        Self::apply_incoming_tombstones(&mut tx, &req.tombstones, safe_peer, now).await?;

        let mut id_remap: HashMap<String, String> = HashMap::new();
        let inserted_candidates = Self::merge_incoming_entries_in_tx(
            &mut tx,
            &prepared_entries,
            &req.collection_entries,
            &mut id_remap,
            safe_peer,
            now,
        )
        .await?;

        if !req.collection_entries.is_empty() {
            Self::merge_incoming_collection_entries_in_tx(
                &mut tx,
                &req.collection_entries,
                &id_remap,
                safe_peer,
                now,
            )
            .await?;
        }
        if !req.consumption_log.is_empty() {
            Self::merge_incoming_consumption_log_in_tx(
                &mut tx,
                &req.consumption_log,
                &id_remap,
                safe_peer,
                now,
            )
            .await?;
        }
        if !req.comparisons.is_empty() {
            Self::merge_incoming_comparisons_in_tx(
                &mut tx,
                &req.comparisons,
                &id_remap,
                safe_peer,
                now,
            )
            .await?;
        }
        Self::merge_incoming_generators_in_tx(
            &mut tx,
            &req.collection_generators,
            &id_remap,
            &mut clock,
            safe_peer,
            now,
        )
        .await?;

        let server_hlc = clock.tick(now_ms).encode();
        sqlx::query("UPDATE sync_clock SET last_hlc = ? WHERE id = 1")
            .bind(&server_hlc)
            .execute(&mut *tx)
            .await?;

        tx.commit().await?;

        let response = self
            .collect_peer_sync_response(req.since_seq, max_page_changes.max(1), server_hlc, now)
            .await?;

        Ok((response, inserted_candidates))
    }

    /// Checks whether an incoming record with `incoming_hlc` is suppressed by an existing
    /// tombstone for `(entity_type, natural_key)`.
    ///
    /// Returns `Ok(true)` when the tombstone wins (`incoming_hlc <= tomb_hlc`) and the incoming
    /// record must be skipped; otherwise clears any stale tombstone and returns `Ok(false)`.
    async fn check_and_clear_tombstone_in_tx(
        tx: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
        incoming_hlc: &str,
    ) -> std::result::Result<bool, sqlx::Error> {
        let tomb_hlc: Option<String> = sqlx::query_scalar(
            "SELECT hlc FROM sync_tombstones WHERE entity_type = ? AND natural_key = ?",
        )
        .bind(entity_type)
        .bind(natural_key)
        .fetch_optional(&mut *tx)
        .await?;

        if let Some(th) = tomb_hlc {
            if incoming_hlc <= th.as_str() {
                return Ok(true);
            }
            Self::clear_tombstone(tx, entity_type, natural_key).await?;
        }
        Ok(false)
    }

    #[allow(clippy::too_many_lines)]
    async fn apply_incoming_tombstones(
        conn: &mut SqliteConnection,
        tombstones: &[SyncTombstone],
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        for tomb in tombstones {
            let key = tomb.natural_key.trim();
            if key.is_empty() {
                continue;
            }
            let tomb_hlc =
                resolve_or_synthesize_hlc(&tomb.hlc, Some(tomb.deleted_at), now, peer_id);

            match tomb.entity_type {
                ActivityEntityType::Feed => {
                    let canon_key = feed_natural_key(key);
                    let det_id = deterministic_feed_id(&canon_key);
                    let existing: Option<(String, String, String)> = sqlx::query_as(
                        "SELECT fs.collection_id, fs.hlc, e.hlc
                         FROM feed_sources fs
                         JOIN entries e ON e.id = fs.collection_id
                         WHERE fs.feed_url = ? OR fs.collection_id = ?
                         LIMIT 1",
                    )
                    .bind(&canon_key)
                    .bind(&det_id)
                    .fetch_optional(&mut *conn)
                    .await?;

                    if let Some((fid, fs_hlc, e_hlc)) = existing {
                        let live_hlc = fs_hlc.as_str().max(e_hlc.as_str());
                        if live_hlc > tomb_hlc.as_str() {
                            continue;
                        }
                        FeedRepo::delete_feed_preserving_history_in_tx(&mut *conn, &fid, now)
                            .await?;
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::Feed,
                            &fid,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                    Self::record_tombstone_with_hlc(
                        &mut *conn,
                        ActivityEntityType::Feed,
                        &canon_key,
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::Item => {
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
                                tomb.deleted_at,
                                &tomb_hlc,
                            )
                            .await?;
                            continue;
                        };

                    let candidates: Vec<(String, String)> = if let Some(ref cf) = canon_feed_opt {
                        let parent_feed_id = deterministic_feed_id(cf);
                        sqlx::query_as(
                            "SELECT e.id, es.hlc
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
                            "SELECT e.id, es.hlc
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

                    if candidates.iter().any(|(_, es_hlc)| es_hlc.as_str() > tomb_hlc.as_str()) {
                        continue;
                    }
                    for (iid, _) in candidates {
                        if ItemRepo::delete_inbox_item_preserving_history_in_tx(
                            &mut *conn, &iid, true, now,
                        )
                        .await?
                        {
                            Self::record_activity(
                                &mut *conn,
                                ActivityEntityType::Item,
                                &iid,
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
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::Tag => {
                    let canon_key = tag_natural_key(key);
                    let tag_rows: Vec<(String, String, Option<String>, String)> = sqlx::query_as(
                        "SELECT e.id, e.title, cs.slug, e.hlc
                         FROM entries e
                         JOIN collection_specs cs ON cs.collection_id = e.id
                         WHERE cs.collection_role = 'tag'",
                    )
                    .fetch_all(&mut *conn)
                    .await?;

                    let want_slug = format!("tag:{canon_key}");
                    let matched: Vec<(String, String)> = tag_rows
                        .into_iter()
                        .filter(|(_, title, slug, _)| {
                            slug.as_deref().is_some_and(|s| s.trim().to_lowercase() == want_slug)
                                || title.trim().to_lowercase() == canon_key
                        })
                        .map(|(id, _, _, hlc)| (id, hlc))
                        .collect();

                    if matched.iter().any(|(_, hlc)| hlc.as_str() > tomb_hlc.as_str()) {
                        continue;
                    }
                    for (tid, _) in matched {
                        sqlx::query("DELETE FROM entries WHERE id = ?")
                            .bind(&tid)
                            .execute(&mut *conn)
                            .await?;
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::Tag,
                            &tid,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                    Self::record_tombstone_with_hlc(
                        &mut *conn,
                        ActivityEntityType::Tag,
                        &canon_key,
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::CollectionEntry => {
                    let Some((raw_col, raw_entry)) = key.split_once('\0') else {
                        continue;
                    };
                    let col_id = CatalogRepo::follow_merged_id_in_tx(&mut *conn, raw_col)
                        .await?
                        .unwrap_or_else(|| raw_col.trim().to_owned());
                    let entry_id = CatalogRepo::follow_merged_id_in_tx(&mut *conn, raw_entry)
                        .await?
                        .unwrap_or_else(|| raw_entry.trim().to_owned());
                    let canon_key = collection_entry_natural_key(&col_id, &entry_id);

                    let existing_hlc: Option<String> = sqlx::query_scalar(
                        "SELECT hlc FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                    )
                    .bind(&col_id)
                    .bind(&entry_id)
                    .fetch_optional(&mut *conn)
                    .await?;

                    if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc.as_str()) {
                        continue;
                    }
                    // Generator advancement for any collection whose `current_entry_id` was removed
                    // is intentionally deferred to `merge_incoming_generators_in_tx` (after peer
                    // generator state merges) so peer-drawn replacements are not overwritten by
                    // server `now` HLCs.
                    sqlx::query(
                        "DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                    )
                    .bind(&col_id)
                    .bind(&entry_id)
                    .execute(&mut *conn)
                    .await?;
                    Self::record_tombstone_with_hlc(
                        &mut *conn,
                        ActivityEntityType::CollectionEntry,
                        &canon_key,
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::Entry => {
                    if super::is_protected_system_collection_id(key) {
                        continue;
                    }
                    let protected_spec: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM collection_specs
                         WHERE collection_id = ? AND (mode = 'dynamic' OR collection_role = 'system')",
                    )
                    .bind(key)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if protected_spec.is_some() {
                        continue;
                    }
                    let existing_hlc: Option<String> =
                        sqlx::query_scalar("SELECT hlc FROM entries WHERE id = ?")
                            .bind(key)
                            .fetch_optional(&mut *conn)
                            .await?;
                    if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc.as_str()) {
                        continue;
                    }
                    if !ItemRepo::entry_has_catalog_history_in_tx(&mut *conn, key).await? {
                        sqlx::query("DELETE FROM entries WHERE id = ?")
                            .bind(key)
                            .execute(&mut *conn)
                            .await?;
                    }
                    Self::record_tombstone_with_hlc(
                        &mut *conn,
                        ActivityEntityType::Entry,
                        key,
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::CollectionGenerator => {
                    let col_id = CatalogRepo::follow_merged_id_in_tx(&mut *conn, key)
                        .await?
                        .unwrap_or_else(|| key.to_owned());
                    let existing_hlc: Option<String> = sqlx::query_scalar(
                        "SELECT hlc FROM collection_generators WHERE collection_id = ?",
                    )
                    .bind(&col_id)
                    .fetch_optional(&mut *conn)
                    .await?;
                    if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc.as_str()) {
                        continue;
                    }
                    let res =
                        sqlx::query("DELETE FROM collection_generators WHERE collection_id = ?")
                            .bind(&col_id)
                            .execute(&mut *conn)
                            .await?;
                    if res.rows_affected() > 0 {
                        Self::record_activity(
                            &mut *conn,
                            ActivityEntityType::CollectionGenerator,
                            &col_id,
                            ActivityAction::Delete,
                            now,
                        )
                        .await?;
                    }
                    Self::record_tombstone_with_hlc(
                        &mut *conn,
                        ActivityEntityType::CollectionGenerator,
                        &col_id,
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::ConsumptionLog => {
                    let existing_hlc: Option<String> =
                        sqlx::query_scalar("SELECT hlc FROM consumption_log WHERE id = ?")
                            .bind(key)
                            .fetch_optional(&mut *conn)
                            .await?;
                    if existing_hlc.as_deref().is_some_and(|h| h > tomb_hlc.as_str()) {
                        continue;
                    }
                    sqlx::query("DELETE FROM consumption_log WHERE id = ?")
                        .bind(key)
                        .execute(&mut *conn)
                        .await?;
                    Self::record_tombstone_with_hlc(
                        &mut *conn,
                        ActivityEntityType::ConsumptionLog,
                        key,
                        tomb.deleted_at,
                        &tomb_hlc,
                    )
                    .await?;
                }
                ActivityEntityType::Media
                | ActivityEntityType::Collection
                | ActivityEntityType::Comparison => {}
            }
        }
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_incoming_entries_in_tx(
        tx: &mut SqliteConnection,
        entries: &[PreparedSyncEntry],
        incoming_memberships: &[SyncCollectionEntryRecord],
        id_remap: &mut HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<Vec<InsertedItemCandidate>> {
        let mut inserted_candidates = Vec::new();
        let mut pending_merges: Vec<(&PreparedSyncEntry, String)> = Vec::new();
        let mut feed_url_by_id: HashMap<String, String> = HashMap::new();

        // Pass 1a: Feeds, Tags, and Manual Lists/Want Collections first so parent IDs map cleanly
        for prep in entries {
            let rec = &prep.record;
            if let Some(target_merge) =
                rec.merged_into_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
            {
                pending_merges.push((prep, target_merge.to_owned()));
                continue;
            }
            if let Some(ref fs) = rec.feed_source {
                if let Some((local_fid, feed_url)) =
                    Self::merge_single_feed_entry_in_tx(tx, prep, fs, id_remap, peer_id, now)
                        .await?
                {
                    feed_url_by_id.insert(local_fid, feed_url.clone());
                    if !rec.id.trim().is_empty() {
                        feed_url_by_id.insert(rec.id.trim().to_owned(), feed_url);
                    }
                }
            } else if let Some(ref cs) = rec.collection_spec {
                match cs.role {
                    CollectionRole::Tag => {
                        Self::merge_single_tag_entry_in_tx(tx, prep, cs, id_remap, peer_id, now)
                            .await?;
                    }
                    CollectionRole::List | CollectionRole::Want
                        if cs.mode == CollectionMode::Manual =>
                    {
                        Self::merge_single_collection_entry_in_tx(
                            tx, prep, cs, id_remap, peer_id, now,
                        )
                        .await?;
                    }
                    CollectionRole::System
                        if cs.mode == CollectionMode::Manual
                            && (rec.id.trim() == super::STARRED_COLLECTION_ID
                                || cs
                                    .slug
                                    .as_deref()
                                    .is_some_and(|s| s.trim().eq_ignore_ascii_case("starred"))) =>
                    {
                        let mut upgraded = cs.clone();
                        upgraded.role = CollectionRole::List;
                        upgraded.show_in_lists = true;
                        Self::merge_single_collection_entry_in_tx(
                            tx, prep, &upgraded, id_remap, peer_id, now,
                        )
                        .await?;
                    }
                    CollectionRole::Feed
                    | CollectionRole::System
                    | CollectionRole::List
                    | CollectionRole::Want => {}
                }
            }
        }

        // Map entry_id -> candidate parent feed_id from incoming `collection_entries`
        let mut incoming_parent_feed: HashMap<&str, &str> = HashMap::new();
        for ce in incoming_memberships {
            let cid = ce.collection_id.trim();
            let eid = ce.entry_id.trim();
            if cid.is_empty() || eid.is_empty() || super::is_protected_system_collection_id(cid) {
                continue;
            }
            if feed_url_by_id.contains_key(cid) {
                incoming_parent_feed.insert(eid, cid);
            } else {
                incoming_parent_feed.entry(eid).or_insert(cid);
            }
        }

        // Pass 1b: Regular Catalog Works & Inbox Items
        for prep in entries {
            let rec = &prep.record;
            if rec.merged_into_id.as_deref().is_some_and(|s| !s.trim().is_empty())
                || rec.feed_source.is_some()
                || rec.collection_spec.is_some()
            {
                continue;
            }
            if let Some(cand) = Self::merge_single_work_or_item_in_tx(
                tx,
                prep,
                &incoming_parent_feed,
                &feed_url_by_id,
                id_remap,
                peer_id,
                now,
            )
            .await?
            {
                inserted_candidates.push(cand);
            }
        }

        // Pass 2: Merge redirects (`merged_into_id`)
        for (merged_prep, raw_target) in pending_merges {
            let rec = &merged_prep.record;
            let raw_dup = rec.id.trim();
            if raw_dup.is_empty() {
                continue;
            }
            let mapped_target =
                id_remap.get(&raw_target).map_or(raw_target.as_str(), String::as_str);
            let Some(canon_resolved) =
                CatalogRepo::follow_merged_id_in_tx(tx, mapped_target).await?
            else {
                continue;
            };
            if raw_dup == canon_resolved {
                continue;
            }
            let entry_hlc = resolve_or_synthesize_hlc(&rec.hlc, rec.updated_at, now, peer_id);
            let existing_dup: Option<Option<String>> =
                sqlx::query_scalar("SELECT merged_into_id FROM entries WHERE id = ?")
                    .bind(raw_dup)
                    .fetch_optional(&mut *tx)
                    .await?;
            match existing_dup {
                None => {
                    let clean_title = rec.title.trim();
                    let title = if clean_title.is_empty() { raw_dup } else { clean_title };
                    let created = rec.created_at.unwrap_or(now);
                    sqlx::query(
                        "INSERT INTO entries (id, kind, title, merged_into_id, created_at, updated_at, hlc)
                         VALUES (?, ?, ?, ?, ?, ?, ?)",
                    )
                    .bind(raw_dup)
                    .bind(rec.kind)
                    .bind(title)
                    .bind(&canon_resolved)
                    .bind(created)
                    .bind(now)
                    .bind(&entry_hlc)
                    .execute(&mut *tx)
                    .await?;
                }
                Some(None) => {
                    if CatalogRepo::validate_mergeable_specs_in_tx(tx, raw_dup, &canon_resolved)
                        .await
                        .is_ok()
                    {
                        CatalogRepo::fold_live_duplicate_in_tx(tx, raw_dup, &canon_resolved, now)
                            .await?;
                    }
                }
                Some(Some(_)) => {}
            }
            id_remap.insert(raw_dup.to_owned(), canon_resolved);
        }

        Ok(inserted_candidates)
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_feed_entry_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        fs: &SyncFeedSource,
        id_remap: &mut HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<Option<(String, String)>> {
        let rec = &prep.record;
        let feed_url = fs.feed_url.trim();
        if feed_url.is_empty() {
            return Ok(None);
        }
        let nat_key = feed_natural_key(feed_url);
        let det_id = deterministic_feed_id(feed_url);
        let entry_hlc =
            resolve_or_synthesize_hlc(&rec.hlc, rec.updated_at.or(rec.created_at), now, peer_id);
        let fs_hlc = if fs.hlc.trim().is_empty() {
            entry_hlc.clone()
        } else {
            resolve_or_synthesize_hlc(&fs.hlc, rec.updated_at.or(rec.created_at), now, peer_id)
        };
        let effective_hlc = if entry_hlc > fs_hlc { &entry_hlc } else { &fs_hlc };

        if Self::check_and_clear_tombstone_in_tx(
            tx,
            ActivityEntityType::Feed,
            &nat_key,
            effective_hlc,
        )
        .await?
        {
            return Ok(None);
        }

        let existing: Option<ExistingFeedTuple> = sqlx::query_as(
            "SELECT collection_id, feed_url, source_type, feed_title, last_polled_at, is_active, hlc
             FROM feed_sources
             WHERE feed_url = ? OR collection_id = ? OR collection_id = ?
             LIMIT 1",
        )
        .bind(feed_url)
        .bind(rec.id.trim())
        .bind(&det_id)
        .fetch_optional(&mut *tx)
        .await?;

        let clean_feed_title =
            fs.feed_title.as_deref().map(str::trim).filter(|s| !s.is_empty()).or_else(|| {
                let t = rec.title.trim();
                (!t.is_empty() && t != feed_url).then_some(t)
            });
        let clean_desc = prep.clean_summary.as_deref();
        let clean_lang = fs.language.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err = fs.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_polled_by = fs.last_polled_by.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let clean_err_by = clean_err
            .and_then(|_| fs.last_error_by.as_deref().map(str::trim).filter(|s| !s.is_empty()));
        let clean_consecutive_failures =
            normalize_consecutive_failures(clean_err, fs.consecutive_failures);
        let interval = fs.poll_interval.clamp(5, 1440);

        if let Some((
            local_id,
            local_url,
            local_type,
            local_feed_title,
            local_last_polled,
            local_active,
            local_hlc,
        )) = existing
        {
            let config_newer = fs_hlc.as_str() > local_hlc.as_str();
            let merged_type =
                if local_type == FeedType::Podcast || fs.source_type == FeedType::Podcast {
                    FeedType::Podcast
                } else if config_newer {
                    fs.source_type
                } else {
                    local_type
                };

            let target_feed_title = if config_newer {
                clean_feed_title.or(local_feed_title.as_deref())
            } else {
                local_feed_title.as_deref().or(clean_feed_title)
            };

            let poll_newer = match (fs.last_polled_at, local_last_polled) {
                (Some(inc_p), Some(loc_p)) => inc_p > loc_p,
                (Some(_), None) => true,
                _ => false,
            };

            if config_newer
                || target_feed_title != local_feed_title.as_deref()
                || merged_type != local_type
            {
                let target_active = if config_newer { fs.is_active } else { local_active };
                let target_hlc = if config_newer { fs_hlc.as_str() } else { local_hlc.as_str() };
                let clear_stale_errors_on_unarchive = config_newer
                    && !local_active
                    && target_active
                    && !poll_newer
                    && clean_err.is_none();
                sqlx::query(
                    "UPDATE feed_sources
                     SET source_type = ?,
                         feed_title = ?,
                         language = COALESCE(?, language),
                         poll_interval = CASE WHEN ? THEN ? ELSE poll_interval END,
                         is_active = ?,
                         last_error = CASE WHEN ? THEN NULL ELSE last_error END,
                         last_error_by = CASE WHEN ? THEN NULL ELSE last_error_by END,
                         consecutive_failures = CASE WHEN ? THEN 0 ELSE consecutive_failures END,
                         hlc = ?
                     WHERE collection_id = ?",
                )
                .bind(merged_type)
                .bind(target_feed_title)
                .bind(clean_lang)
                .bind(config_newer)
                .bind(interval)
                .bind(target_active)
                .bind(clear_stale_errors_on_unarchive)
                .bind(clear_stale_errors_on_unarchive)
                .bind(clear_stale_errors_on_unarchive)
                .bind(target_hlc)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;

                let entry_title = target_feed_title.unwrap_or(&local_url);
                let target_kind = entry_kind_for_feed_type(merged_type);
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
                .bind(clean_desc)
                .bind(now)
                .bind(&entry_hlc)
                .bind(&entry_hlc)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;
            }

            if poll_newer {
                sqlx::query(
                    "UPDATE feed_sources
                     SET last_polled_at = ?,
                         last_polled_by = ?,
                         last_error = ?,
                         last_error_by = ?,
                         consecutive_failures = ?
                     WHERE collection_id = ?",
                )
                .bind(fs.last_polled_at)
                .bind(clean_polled_by)
                .bind(clean_err)
                .bind(clean_err_by)
                .bind(clean_consecutive_failures)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;
            }

            if !rec.id.trim().is_empty() {
                id_remap.insert(rec.id.trim().to_owned(), local_id.clone());
            }
            id_remap.insert(local_id.clone(), local_id.clone());
            Ok(Some((local_id, local_url)))
        } else {
            let local_id = det_id;
            let created_at = rec.created_at.unwrap_or(now);
            FeedRepo::insert_feed_in_tx(
                tx,
                &NewFeedRow {
                    id: &local_id,
                    feed_url,
                    feed_type: fs.source_type,
                    title: clean_feed_title,
                    description: clean_desc,
                    language: clean_lang,
                    poll_interval: interval,
                    is_active: fs.is_active,
                    last_polled: fs.last_polled_at,
                    last_polled_by: clean_polled_by,
                    last_error: clean_err,
                    last_error_by: clean_err_by,
                    consecutive_failures: clean_consecutive_failures,
                    created_at,
                    updated_at: now,
                },
            )
            .await?;

            sqlx::query("UPDATE entries SET hlc = ? WHERE id = ?")
                .bind(&entry_hlc)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;
            sqlx::query("UPDATE feed_sources SET hlc = ? WHERE collection_id = ?")
                .bind(&fs_hlc)
                .bind(&local_id)
                .execute(&mut *tx)
                .await?;

            Self::record_activity(
                tx,
                ActivityEntityType::Feed,
                &local_id,
                ActivityAction::Create,
                now,
            )
            .await?;
            if !rec.id.trim().is_empty() {
                id_remap.insert(rec.id.trim().to_owned(), local_id.clone());
            }
            id_remap.insert(local_id.clone(), local_id.clone());
            Ok(Some((local_id, feed_url.to_owned())))
        }
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_tag_entry_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        cs: &SyncCollectionSpec,
        id_remap: &mut HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let rec = &prep.record;
        let clean_name = rec.title.trim();
        if clean_name.is_empty() {
            return Ok(());
        }
        let nat_key = tag_natural_key(clean_name);
        let entry_hlc =
            resolve_or_synthesize_hlc(&rec.hlc, rec.updated_at.or(rec.created_at), now, peer_id);

        if Self::check_and_clear_tombstone_in_tx(tx, ActivityEntityType::Tag, &nat_key, &entry_hlc)
            .await?
        {
            return Ok(());
        }

        let det_id = deterministic_tag_id(clean_name);
        let tag_slug = format!("tag:{nat_key}");
        let rec_id = rec.id.trim();
        let parsed_color_str: Option<String> = cs
            .color
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|c| HexColor::parse(c).ok())
            .map(String::from);

        let existing_tags: Vec<ExistingTagTuple> = sqlx::query_as(
            "SELECT e.id, e.title, cs.slug, cs.color, e.hlc
                 FROM entries e
                 JOIN collection_specs cs ON cs.collection_id = e.id
                 WHERE cs.collection_role = 'tag' AND e.merged_into_id IS NULL",
        )
        .fetch_all(&mut *tx)
        .await?;

        let by_name_or_slug = existing_tags.iter().find(|(id, title, slug, _, _)| {
            id == &det_id
                || slug.as_deref().is_some_and(|s| s.trim().to_lowercase() == tag_slug)
                || title.trim().to_lowercase() == nat_key
        });
        let by_id = (!rec_id.is_empty())
            .then(|| existing_tags.iter().find(|(id, _, _, _, _)| id == rec_id))
            .flatten();
        let found = by_name_or_slug.or(by_id).cloned();

        if let Some((local_id, _, existing_slug, existing_color, existing_hlc)) = found {
            if entry_hlc.as_str() > existing_hlc.as_str() {
                let color_str = parsed_color_str.as_deref();
                if color_str.is_some() && color_str != existing_color.as_deref() {
                    sqlx::query("UPDATE collection_specs SET color = ? WHERE collection_id = ?")
                        .bind(color_str)
                        .bind(&local_id)
                        .execute(&mut *tx)
                        .await?;
                }
                let slug_conflict: Option<String> = sqlx::query_scalar(
                    "SELECT collection_id FROM collection_specs WHERE slug = ? AND collection_id != ?",
                )
                .bind(&tag_slug)
                .bind(&local_id)
                .fetch_optional(&mut *tx)
                .await?;
                if slug_conflict.is_none() {
                    let slug_changed = existing_slug.as_deref() != Some(tag_slug.as_str());
                    if slug_changed {
                        sqlx::query("UPDATE collection_specs SET slug = ? WHERE collection_id = ?")
                            .bind(&tag_slug)
                            .bind(&local_id)
                            .execute(&mut *tx)
                            .await?;
                        sqlx::query(
                            "DELETE FROM entry_identifiers WHERE scheme = 'tag' AND entry_id = ?",
                        )
                        .bind(&local_id)
                        .execute(&mut *tx)
                        .await?;
                        sqlx::query(
                            "INSERT OR REPLACE INTO entry_identifiers (scheme, value, entry_id)
                             VALUES ('tag', ?, ?)",
                        )
                        .bind(&nat_key)
                        .bind(&local_id)
                        .execute(&mut *tx)
                        .await?;
                    }
                    sqlx::query(
                        "UPDATE entries SET title = ?, updated_at = ?, hlc = ? WHERE id = ?",
                    )
                    .bind(clean_name)
                    .bind(now)
                    .bind(&entry_hlc)
                    .bind(&local_id)
                    .execute(&mut *tx)
                    .await?;
                } else {
                    sqlx::query("UPDATE entries SET updated_at = ?, hlc = ? WHERE id = ?")
                        .bind(now)
                        .bind(&entry_hlc)
                        .bind(&local_id)
                        .execute(&mut *tx)
                        .await?;
                }
            }
            if !rec_id.is_empty() {
                id_remap.insert(rec_id.to_owned(), local_id.clone());
            }
            id_remap.insert(local_id.clone(), local_id);
        } else {
            let created_at = rec.created_at.unwrap_or(now);
            let color_str = parsed_color_str.as_deref();
            sqlx::query(
                "INSERT INTO entries (id, kind, title, created_at, updated_at, hlc)
                 VALUES (?, 'collection', ?, ?, ?, ?)
                 ON CONFLICT(id) DO UPDATE SET
                     title = excluded.title,
                     updated_at = excluded.updated_at,
                     hlc = excluded.hlc",
            )
            .bind(&det_id)
            .bind(clean_name)
            .bind(created_at)
            .bind(now)
            .bind(&entry_hlc)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT INTO collection_specs (collection_id, slug, mode, collection_role, show_in_lists, color)
                 VALUES (?, ?, 'manual', 'tag', 0, ?)
                 ON CONFLICT(collection_id) DO UPDATE SET
                     slug = excluded.slug,
                     color = COALESCE(excluded.color, collection_specs.color)",
            )
            .bind(&det_id)
            .bind(&tag_slug)
            .bind(color_str)
            .execute(&mut *tx)
            .await?;

            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('tag', ?, ?)",
            )
            .bind(&nat_key)
            .bind(&det_id)
            .execute(&mut *tx)
            .await?;

            Self::record_activity(
                tx,
                ActivityEntityType::Tag,
                &det_id,
                ActivityAction::Create,
                now,
            )
            .await?;
            if !rec.id.trim().is_empty() {
                id_remap.insert(rec.id.trim().to_owned(), det_id.clone());
            }
            id_remap.insert(det_id.clone(), det_id);
        }
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_collection_entry_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        cs: &SyncCollectionSpec,
        id_remap: &mut HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let rec = &prep.record;
        let title = rec.title.trim();
        if title.is_empty() {
            return Ok(());
        }
        let slug_opt =
            cs.slug.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_lowercase);
        let requested_id = if !rec.id.trim().is_empty() {
            rec.id.trim().to_owned()
        } else if let Some(ref s) = slug_opt {
            if s == "starred" || s == "up_next" || s.starts_with("want:") {
                deterministic_system_collection_id(s)
            } else {
                deterministic_system_collection_id(&format!("custom:{s}"))
            }
        } else {
            return Ok(());
        };

        let existing_spec: Option<(String, CollectionMode, CollectionRole)> = sqlx::query_as(
            "SELECT collection_id, mode, collection_role
             FROM collection_specs
             WHERE collection_id = ? OR (slug IS NOT NULL AND LOWER(slug) = LOWER(?))
             ORDER BY CASE WHEN collection_id = ? THEN 0 ELSE 1 END ASC
             LIMIT 1",
        )
        .bind(&requested_id)
        .bind(&slug_opt)
        .bind(&requested_id)
        .fetch_optional(&mut *tx)
        .await?;

        let target_id = if let Some((existing_id, ex_mode, ex_role)) = existing_spec {
            if ex_mode != CollectionMode::Manual
                || matches!(
                    ex_role,
                    CollectionRole::Feed | CollectionRole::Tag | CollectionRole::System
                )
            {
                return Ok(());
            }
            if existing_id != requested_id {
                id_remap.insert(requested_id.clone(), existing_id.clone());
            }
            existing_id
        } else {
            requested_id.clone()
        };
        if !rec.id.trim().is_empty() {
            id_remap.insert(rec.id.trim().to_owned(), target_id.clone());
        }

        let created = rec.created_at.unwrap_or(now);
        let entry_hlc =
            resolve_or_synthesize_hlc(&rec.hlc, rec.updated_at.or(rec.created_at), now, peer_id);

        // Check `'entry'` tombstone before upserting manual collection
        if !super::is_protected_system_collection_id(&target_id)
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

        let clean_color = cs.color.as_deref().map(str::trim).filter(|s| !s.is_empty());

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
        .bind(now)
        .bind(&entry_hlc)
        .execute(&mut *tx)
        .await?;

        let (effective_slug, effective_role, effective_show_in_lists) =
            if target_id == super::STARRED_COLLECTION_ID {
                (Some("starred".to_owned()), CollectionRole::List, true)
            } else if target_id == super::WANT_TO_READ_COLLECTION_ID {
                (Some(super::WANT_TO_READ_SLUG.to_owned()), CollectionRole::Want, false)
            } else {
                (slug_opt, cs.role, cs.show_in_lists)
            };

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

        if let Some(s) = effective_slug {
            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('slug', ?, ?)",
            )
            .bind(s)
            .bind(&target_id)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_work_or_item_in_tx(
        tx: &mut SqliteConnection,
        prep: &PreparedSyncEntry,
        incoming_parent_feed: &HashMap<&str, &str>,
        feed_url_by_id: &HashMap<String, String>,
        id_remap: &mut HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
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

        let entry_hlc =
            resolve_or_synthesize_hlc(&rec.hlc, rec.updated_at.or(rec.created_at), now, peer_id);
        let state_hlc_opt = rec.state.as_ref().map(|st| {
            resolve_or_synthesize_hlc(
                &st.hlc,
                st.interaction_updated_at.or(rec.updated_at).or(rec.created_at),
                now,
                peer_id,
            )
        });

        let resolved_feed_url: Option<String> = match incoming_parent_feed.get(rec.id.trim()) {
            Some(raw_fid) => {
                let mapped_fid = id_remap.get(*raw_fid).map_or(*raw_fid, String::as_str);
                if let Some(u) = feed_url_by_id.get(mapped_fid) {
                    Some(u.clone())
                } else {
                    sqlx::query_scalar("SELECT feed_url FROM feed_sources WHERE collection_id = ?")
                        .bind(mapped_fid)
                        .fetch_optional(&mut *tx)
                        .await?
                }
            }
            None => None,
        };

        let is_active_inbox = rec
            .state
            .as_ref()
            .is_some_and(|s| matches!(s.inbox_state, InboxState::Unread | InboxState::Dismissed));
        let carries_item_payload = rec.state.is_some() || rec.content.is_some();

        // Check `'entry'` tombstone
        if !rec.id.trim().is_empty()
            && Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::Entry,
                rec.id.trim(),
                &entry_hlc,
            )
            .await?
        {
            return Ok(None);
        }

        // Check `'item'` tombstone whenever incoming carries item state or content
        let mut effective_state = rec.state.clone();
        let mut suppress_content = false;
        if carries_item_payload && let Some(ref item_url) = prep.canon_url {
            let nat_feed = item_natural_key(resolved_feed_url.as_deref(), item_url);
            let nat_manual = item_natural_key(None, item_url);
            let item_tombs: Vec<(String, String)> = if resolved_feed_url.is_some() {
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

            let max_inc_hlc = state_hlc_opt
                .as_deref()
                .map_or(entry_hlc.as_str(), |sh| sh.max(entry_hlc.as_str()));
            let rec_id = rec.id.trim();
            let det_manual_id = deterministic_entity_uuid("item", &nat_manual);
            let exact_suffix = format!("\0{item_url}");

            let mut suppressed = false;
            for (tk, th) in item_tombs {
                if tk != nat_feed && tk != nat_manual && !tk.ends_with(&exact_suffix) {
                    continue;
                }
                let exact_scope = tk == nat_feed
                    || (resolved_feed_url.is_none() && tk == nat_manual)
                    || (!rec_id.is_empty() && deterministic_entity_uuid("item", &tk) == rec_id);
                let legacy_feed_fallback =
                    resolved_feed_url.is_none() && is_active_inbox && rec_id != det_manual_id;
                if !exact_scope && !legacy_feed_fallback {
                    continue;
                }
                if max_inc_hlc <= th.as_str() {
                    suppressed = true;
                } else if exact_scope {
                    Self::clear_tombstone(tx, ActivityEntityType::Item, &tk).await?;
                }
            }
            if suppressed {
                // Suppress item resurrection; if pure reader item with no catalog metadata, skip entirely
                if rec.identifiers.iter().all(|id| matches!(id.scheme.as_str(), "url" | "arxiv"))
                    && rec.release_year.is_none()
                    && rec.metadata.is_none()
                {
                    return Ok(None);
                }
                effective_state = None;
                suppress_content = true;
            }
        }

        // Determine canonical / deduplicated entry ID
        let det_inbox_id = prep
            .canon_url
            .as_deref()
            .map(|u| deterministic_item_id(resolved_feed_url.as_deref(), u));

        let mut idents = rec.identifiers.clone();
        if let Some(ref c_url) = prep.canon_url
            && !idents.iter().any(|i| i.scheme == "url")
        {
            idents.push(crate::db::EntryIdentifier {
                scheme: "url".to_owned(),
                value: c_url.clone(),
            });
        }
        if let Some(ref aid) = prep.arxiv_id
            && !idents.iter().any(|i| i.scheme == "arxiv")
        {
            idents.push(crate::db::EntryIdentifier {
                scheme: "arxiv".to_owned(),
                value: aid.clone(),
            });
        }

        let target_kind =
            if prep.resolved_content_type == ContentType::Audio && rec.kind == EntryKind::Article {
                EntryKind::PodcastEpisode
            } else {
                rec.kind
            };

        let pre_matched_id: Option<String> = {
            let by_id = if rec.id.trim().is_empty() {
                None
            } else {
                CatalogRepo::follow_merged_id_in_tx(tx, rec.id.trim()).await?
            };
            if by_id.is_some() {
                by_id
            } else if let Some(by_ident) =
                Self::find_existing_entry_by_identifiers_in_tx(tx, target_kind, &idents).await?
            {
                Some(by_ident)
            } else if let (Some(det_id), Some(c_url)) = (&det_inbox_id, &prep.canon_url) {
                resolve_dedup_entry_id(tx, det_id, c_url, prep.arxiv_id.as_deref()).await?
            } else {
                None
            }
        };

        let preferred_id = pre_matched_id.as_ref().map_or_else(
            || {
                if (is_active_inbox && det_inbox_id.is_some()) || rec.id.trim().is_empty() {
                    det_inbox_id
                } else {
                    Some(rec.id.trim().to_owned())
                }
            },
            |existing_id| Some(existing_id.clone()),
        );

        let was_created_new = pre_matched_id.is_none();
        let target_id = if let Some(existing_id) = pre_matched_id {
            Self::merge_into_existing_entry_row_in_tx(
                tx,
                &existing_id,
                prep,
                target_kind,
                final_title,
                &idents,
                &entry_hlc,
                now,
            )
            .await?;
            existing_id
        } else {
            let upsert_input = UpsertEntryInput {
                id: preferred_id,
                kind: Some(target_kind),
                title: final_title.to_owned(),
                creators: rec.creators.clone(),
                url: rec.url.clone(),
                published_at: rec.published_at,
                release_year: rec.release_year,
                summary: prep.clean_summary.clone(),
                thumbnail_url: prep.clean_thumb.clone(),
                metadata: rec.metadata.clone(),
                identifiers: idents,
                parent_collection_id: None,
                parent_position: None,
            };
            let created_id = CatalogRepo::upsert_entry_in_tx(
                tx,
                &upsert_input,
                target_kind,
                final_title,
                rec.updated_at.unwrap_or(now),
            )
            .await?;
            if let Some(c_at) = rec.created_at {
                sqlx::query("UPDATE entries SET created_at = ?, hlc = ? WHERE id = ?")
                    .bind(c_at)
                    .bind(&entry_hlc)
                    .bind(&created_id)
                    .execute(&mut *tx)
                    .await?;
            } else {
                sqlx::query(
                    "UPDATE entries SET hlc = CASE WHEN ? > hlc THEN ? ELSE hlc END WHERE id = ?",
                )
                .bind(&entry_hlc)
                .bind(&entry_hlc)
                .bind(&created_id)
                .execute(&mut *tx)
                .await?;
            }
            created_id
        };

        if !rec.id.trim().is_empty() {
            id_remap.insert(rec.id.trim().to_owned(), target_id.clone());
        }
        id_remap.insert(target_id.clone(), target_id.clone());

        // Merge `entry_content` + remote audio enclosure if present
        if !suppress_content
            && (rec.content.is_some()
                || prep.clean_html.is_some()
                || prep.clean_audio_url.is_some())
        {
            let existing_content: Option<(Option<String>, ContentType)> = sqlx::query_as(
                "SELECT content_html, content_type FROM entry_content WHERE entry_id = ?",
            )
            .bind(&target_id)
            .fetch_optional(&mut *tx)
            .await?;

            let merged_html = match &existing_content {
                Some((ex_html, _)) => {
                    pick_richer_text(ex_html.as_deref(), prep.clean_html.as_deref())
                        .map(str::to_owned)
                }
                None => prep.clean_html.clone(),
            };
            let merged_ct = match &existing_content {
                Some((_, ex_ct))
                    if *ex_ct == ContentType::Audio
                        || prep.resolved_content_type == ContentType::Audio =>
                {
                    ContentType::Audio
                }
                Some((_, ex_ct)) if rec.content.is_none() => *ex_ct,
                _ => prep.resolved_content_type,
            };

            if existing_content.as_ref().is_none_or(|(eh, ect)| {
                eh.as_deref() != merged_html.as_deref() || *ect != merged_ct
            }) {
                sqlx::query(
                    "INSERT INTO entry_content (entry_id, content_html, content_type)
                     VALUES (?, ?, ?)
                     ON CONFLICT(entry_id) DO UPDATE SET
                         content_html = excluded.content_html,
                         content_type = excluded.content_type",
                )
                .bind(&target_id)
                .bind(merged_html.as_deref())
                .bind(merged_ct)
                .execute(&mut *tx)
                .await?;
            }

            if prep.clean_audio_url.is_some() || prep.clean_duration.is_some() {
                MediaRepo::upsert_audio_enclosure_in_tx(
                    tx,
                    &target_id,
                    prep.clean_audio_url.as_deref(),
                    prep.clean_duration,
                    rec.created_at.unwrap_or(now),
                )
                .await?;
            }
        }

        // Merge `entry_state` with causal HLC ordering
        let mut was_new_inbox_item = false;
        if let Some(ref st) = effective_state {
            let st_hlc = state_hlc_opt.unwrap_or_else(|| entry_hlc.clone());
            let clamped_prog =
                if st.progress.is_finite() { st.progress.clamp(0.0, 1.0) } else { 0.0 };
            let existing_state: Option<ExistingStateTuple> = sqlx::query_as(
                "SELECT inbox_state, read_at, progress, interaction_updated_at, hlc
                 FROM entry_state WHERE entry_id = ?",
            )
            .bind(&target_id)
            .fetch_optional(&mut *tx)
            .await?;

            if let Some((ex_inbox, ex_read_at, ex_prog, ex_inter, ex_hlc)) = existing_state {
                let (next_inbox, next_read_at, next_prog, next_inter, next_hlc) =
                    resolve_merged_entry_state(
                        (ex_inbox, ex_read_at, ex_prog, ex_inter, &ex_hlc),
                        st,
                        clamped_prog,
                        &st_hlc,
                        now,
                    );
                if next_inbox != ex_inbox
                    || next_read_at != ex_read_at
                    || (next_prog - ex_prog).abs() > f64::EPSILON
                    || next_inter != ex_inter
                    || next_hlc != ex_hlc
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
                    .bind(next_inbox)
                    .bind(next_read_at)
                    .bind(next_prog)
                    .bind(next_inter)
                    .bind(now)
                    .bind(&next_hlc)
                    .bind(&target_id)
                    .execute(&mut *tx)
                    .await?;
                }
            } else {
                was_new_inbox_item =
                    matches!(st.inbox_state, InboxState::Unread | InboxState::Dismissed);
                let inter_ts = st.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
                let read_at = if st.inbox_state == InboxState::Dismissed {
                    st.read_at.or(inter_ts).or(Some(now))
                } else {
                    st.read_at
                };
                sqlx::query(
                    "INSERT INTO entry_state
                     (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at, hlc)
                     VALUES (?, ?, ?, ?, ?, ?, ?)",
                )
                .bind(&target_id)
                .bind(st.inbox_state)
                .bind(read_at)
                .bind(clamped_prog)
                .bind(inter_ts)
                .bind(now)
                .bind(&st_hlc)
                .execute(&mut *tx)
                .await?;
            }
        }

        if (was_new_inbox_item || (was_created_new && rec.content.is_some()))
            && let Some(ref parsed_u) = prep.parsed_url
        {
            return Ok(Some((
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
            )));
        }

        Ok(None)
    }

    async fn find_existing_entry_by_identifiers_in_tx(
        tx: &mut SqliteConnection,
        kind: EntryKind,
        idents: &[crate::db::EntryIdentifier],
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
            let found: Option<String> = sqlx::query_scalar(
                "SELECT entry_id FROM entry_identifiers WHERE scheme = ? AND value = ? LIMIT 1",
            )
            .bind(&scheme)
            .bind(&val)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some(raw_eid) = found
                && let Some(canon) = CatalogRepo::follow_merged_id_in_tx(tx, &raw_eid).await?
            {
                return Ok(Some(canon));
            }
        }
        Ok(None)
    }

    #[allow(clippy::too_many_arguments, clippy::too_many_lines)]
    async fn merge_into_existing_entry_row_in_tx(
        tx: &mut SqliteConnection,
        existing_id: &str,
        prep: &PreparedSyncEntry,
        target_kind: EntryKind,
        final_title: &str,
        idents: &[crate::db::EntryIdentifier],
        entry_hlc: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let rec = &prep.record;
        let Some((
            ex_kind,
            ex_title,
            ex_creators_json,
            ex_url,
            ex_pub,
            ex_year,
            ex_summary,
            ex_thumb,
            ex_meta_json,
            ex_hlc,
        )) = sqlx::query_as::<_, ExistingEntryRowTuple>(
            "SELECT kind, title, creators_json, url, published_at, release_year,
                    summary, thumbnail_url, metadata_json, hlc
             FROM entries WHERE id = ?",
        )
        .bind(existing_id)
        .fetch_optional(&mut *tx)
        .await?
        else {
            return Ok(());
        };

        let inc_newer = entry_hlc > ex_hlc.as_str();
        let merged_kind =
            if target_kind == EntryKind::PodcastEpisode && ex_kind == EntryKind::Article {
                EntryKind::PodcastEpisode
            } else {
                ex_kind
            };

        let ex_is_url_placeholder = ex_title.trim().is_empty()
            || ex_url.as_deref().is_some_and(|u| ex_title == u)
            || rec.url.as_deref().is_some_and(|u| ex_title == u.trim());
        let inc_is_url_placeholder = rec.url.as_deref().is_some_and(|u| final_title == u.trim());
        let merged_title = if (ex_is_url_placeholder || (inc_newer && !inc_is_url_placeholder))
            && !final_title.is_empty()
        {
            final_title.to_owned()
        } else if target_kind == EntryKind::Album {
            let cur_stripped = crate::db::strip_album_edition_suffix(&ex_title);
            let inc_stripped = crate::db::strip_album_edition_suffix(final_title);
            if ex_title.len() > cur_stripped.len() && final_title.len() == inc_stripped.len() {
                final_title.to_owned()
            } else {
                ex_title.clone()
            }
        } else {
            ex_title.clone()
        };

        let clean_creators: Vec<String> =
            rec.creators.iter().map(|c| c.trim().to_owned()).filter(|c| !c.is_empty()).collect();
        let inc_creators_json =
            serde_json::to_string(&clean_creators).unwrap_or_else(|_| "[]".to_owned());
        let merged_creators_json =
            if (ex_creators_json == "[]" || inc_newer) && inc_creators_json != "[]" {
                inc_creators_json
            } else {
                ex_creators_json.clone()
            };

        let clean_inc_url = rec.url.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let merged_url = if inc_newer && clean_inc_url.is_some() {
            clean_inc_url.map(str::to_owned)
        } else {
            ex_url.clone().or_else(|| clean_inc_url.map(str::to_owned))
        };
        let merged_pub = ex_pub.or(rec.published_at);
        let merged_year = if inc_newer && rec.release_year.is_some() {
            rec.release_year
        } else {
            ex_year.or(rec.release_year)
        };
        let merged_summary = pick_richer_text(ex_summary.as_deref(), prep.clean_summary.as_deref())
            .map(str::to_owned);
        let merged_thumb = ex_thumb.clone().or_else(|| prep.clean_thumb.clone());

        let merged_meta_json = if let Some(serde_json::Value::Object(inc_map)) = &rec.metadata
            && !inc_map.is_empty()
        {
            let mut cur_obj = serde_json::from_str::<serde_json::Value>(&ex_meta_json)
                .ok()
                .and_then(|v| v.as_object().cloned())
                .unwrap_or_default();
            for (k, v) in inc_map {
                if inc_newer || !cur_obj.contains_key(k) {
                    cur_obj.insert(k.clone(), v.clone());
                }
            }
            serde_json::Value::Object(cur_obj).to_string()
        } else {
            ex_meta_json.clone()
        };

        let target_hlc = if inc_newer { entry_hlc } else { ex_hlc.as_str() };
        let changed = merged_kind != ex_kind
            || merged_title != ex_title
            || merged_creators_json != ex_creators_json
            || merged_url != ex_url
            || merged_pub != ex_pub
            || merged_year != ex_year
            || merged_summary != ex_summary
            || merged_thumb != ex_thumb
            || merged_meta_json != ex_meta_json
            || target_hlc != ex_hlc.as_str();

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
            .bind(merged_kind)
            .bind(&merged_title)
            .bind(&merged_creators_json)
            .bind(merged_url.as_deref())
            .bind(merged_pub)
            .bind(merged_year)
            .bind(merged_summary.as_deref())
            .bind(merged_thumb.as_deref())
            .bind(&merged_meta_json)
            .bind(now)
            .bind(target_hlc)
            .bind(existing_id)
            .execute(&mut *tx)
            .await?;
        }

        for ident in idents {
            let scheme = crate::db::normalize_identifier_scheme(&ident.scheme);
            let val = crate::db::normalize_identifier_value(merged_kind, &scheme, &ident.value);
            if scheme.is_empty() || val.is_empty() {
                continue;
            }
            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES (?, ?, ?)",
            )
            .bind(&scheme)
            .bind(&val)
            .bind(existing_id)
            .execute(&mut *tx)
            .await?;
        }

        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_incoming_collection_entries_in_tx(
        tx: &mut SqliteConnection,
        memberships: &[SyncCollectionEntryRecord],
        id_remap: &HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        for ce in memberships {
            let raw_col = ce.collection_id.trim();
            let raw_ent = ce.entry_id.trim();
            if raw_col.is_empty() || raw_ent.is_empty() {
                continue;
            }
            let mapped_col = id_remap.get(raw_col).map_or(raw_col, String::as_str);
            let mapped_ent = id_remap.get(raw_ent).map_or(raw_ent, String::as_str);
            let Some(col_id) = CatalogRepo::follow_merged_id_in_tx(tx, mapped_col).await? else {
                continue;
            };
            let Some(entry_id) = CatalogRepo::follow_merged_id_in_tx(tx, mapped_ent).await? else {
                continue;
            };
            if col_id == entry_id {
                continue;
            }

            let spec_info: Option<(CollectionMode, CollectionRole)> = sqlx::query_as(
                "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
            )
            .bind(&col_id)
            .fetch_optional(&mut *tx)
            .await?;
            if let Some((mode, _)) = spec_info
                && mode == CollectionMode::Dynamic
            {
                continue;
            }
            if CatalogRepo::would_introduce_collection_cycle_in_tx(tx, &col_id, &entry_id).await? {
                continue;
            }

            let ce_hlc = resolve_or_synthesize_hlc(&ce.hlc, Some(ce.added_at), now, peer_id);
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
            .bind(ce.added_at)
            .bind(&ce_hlc)
            .execute(&mut *tx)
            .await?;

            // Auto-upgrade RSS publication feed to Podcast when an audio episode joins it
            if matches!(spec_info, Some((CollectionMode::Feed, CollectionRole::Feed))) {
                let is_audio_entry: Option<i64> = sqlx::query_scalar(
                    "SELECT 1 FROM entries e
                     LEFT JOIN entry_content ec ON ec.entry_id = e.id
                     WHERE e.id = ? AND (e.kind = 'podcast_episode' OR ec.content_type = 'audio')
                     LIMIT 1",
                )
                .bind(&entry_id)
                .fetch_optional(&mut *tx)
                .await?;
                if is_audio_entry.is_some() {
                    sqlx::query(
                        "UPDATE feed_sources SET source_type = 'podcast'
                         WHERE collection_id = ? AND source_type = 'rss'",
                    )
                    .bind(&col_id)
                    .execute(&mut *tx)
                    .await?;
                    sqlx::query(
                        "UPDATE entries SET kind = 'podcast'
                         WHERE id = ? AND kind = 'publication'",
                    )
                    .bind(&col_id)
                    .execute(&mut *tx)
                    .await?;
                }
            }
        }
        Ok(())
    }

    async fn merge_incoming_consumption_log_in_tx(
        tx: &mut SqliteConnection,
        logs: &[SyncConsumptionLogRecord],
        id_remap: &HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        for log in logs {
            let raw_ent = log.entry_id.trim();
            let mapped_ent = id_remap.get(raw_ent).map_or(raw_ent, String::as_str);
            let Some(entry_id) = CatalogRepo::follow_merged_id_in_tx(tx, mapped_ent).await? else {
                continue;
            };
            let log_hlc =
                resolve_or_synthesize_hlc(&log.hlc, log.created_at.or(Some(log.ts)), now, peer_id);

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
                continue;
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
                continue;
            }

            let saved = CatalogRepo::log_consumption_with_id_in_tx(
                tx,
                &resolved_log_id,
                &entry_id,
                log.status,
                log.notes.as_deref(),
                log.ts,
                now,
            )
            .await?;

            sqlx::query(
                "UPDATE consumption_log SET hlc = CASE WHEN ? > hlc THEN ? ELSE hlc END WHERE id = ?",
            )
            .bind(&log_hlc)
            .bind(&log_hlc)
            .bind(&saved.0)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    async fn merge_incoming_comparisons_in_tx(
        tx: &mut SqliteConnection,
        comparisons: &[SyncComparisonRecord],
        id_remap: &HashMap<String, String>,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        for comp in comparisons {
            let raw_scope_opt =
                comp.scope_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
            let scope_id = match raw_scope_opt {
                Some(raw_s) => {
                    let mapped = id_remap.get(raw_s).map_or(raw_s, String::as_str);
                    let Some(resolved) = CatalogRepo::follow_merged_id_in_tx(tx, mapped).await?
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
                    let trimmed = raw_id.trim();
                    let mapped = id_remap.get(trimmed).map_or(trimmed, String::as_str);
                    let Some(canon) = CatalogRepo::follow_merged_id_in_tx(tx, mapped).await? else {
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
                now,
                peer_id,
            );

            let res = sqlx::query(
                "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?)",
            )
            .bind(&comp_id)
            .bind(comp.ts)
            .bind(scope_id)
            .bind(&ord_json)
            .bind(comp.created_at.unwrap_or(now))
            .bind(&comp_hlc)
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
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_incoming_generators_in_tx(
        tx: &mut SqliteConnection,
        generators: &[SyncCollectionGeneratorRecord],
        id_remap: &HashMap<String, String>,
        clock: &mut HlcClock,
        peer_id: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let now_ms = now.timestamp_millis();
        for inc in generators {
            let raw_col = inc.collection_id.trim();
            if raw_col.is_empty() {
                continue;
            }
            let mapped_col = id_remap.get(raw_col).map_or(raw_col, String::as_str);
            let Some(col_id) = CatalogRepo::follow_merged_id_in_tx(tx, mapped_col).await? else {
                continue;
            };
            let spec_ok: Option<(CollectionMode, CollectionRole)> = sqlx::query_as(
                "SELECT mode, collection_role FROM collection_specs WHERE collection_id = ?",
            )
            .bind(&col_id)
            .fetch_optional(&mut *tx)
            .await?;
            if col_id == super::STARRED_COLLECTION_ID
                || !matches!(spec_ok, Some((CollectionMode::Manual, CollectionRole::List)))
            {
                continue;
            }

            let inc_hlc = resolve_or_synthesize_hlc(&inc.hlc, Some(inc.updated_at), now, peer_id);

            if Self::check_and_clear_tombstone_in_tx(
                tx,
                ActivityEntityType::CollectionGenerator,
                &col_id,
                &inc_hlc,
            )
            .await?
            {
                continue;
            }

            let local_gen: Option<ExistingGeneratorTuple> = sqlx::query_as(
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
                .map(|eid| id_remap.get(eid).map_or_else(|| eid.to_owned(), Clone::clone));

            let (
                target_enabled,
                mut target_entry_id,
                mut target_drawn_at,
                mut target_updated,
                mut target_hlc,
                local_won_conflict,
            ) = match &local_gen {
                None => {
                    (inc.enabled, mapped_inc_entry, inc.drawn_at, inc.updated_at, inc_hlc, false)
                }
                Some((l_en, l_eid, l_drawn, l_upd, l_hlc)) => {
                    if inc_hlc.as_str() >= l_hlc.as_str() {
                        (
                            inc.enabled,
                            mapped_inc_entry,
                            inc.drawn_at,
                            inc.updated_at,
                            inc_hlc,
                            false,
                        )
                    } else {
                        let differs = inc.enabled != *l_en || mapped_inc_entry != *l_eid;
                        (*l_en, l_eid.clone(), *l_drawn, *l_upd, l_hlc.clone(), differs)
                    }
                }
            };

            if let Some(ref cur_id) = target_entry_id {
                if let Some(cid) = CatalogRepo::follow_merged_id_in_tx(tx, cur_id).await? {
                    let in_pool: Option<i64> = sqlx::query_scalar(
                        "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                    )
                    .bind(&col_id)
                    .bind(&cid)
                    .fetch_optional(&mut *tx)
                    .await?;
                    if in_pool.is_none() || CatalogRepo::is_entry_terminal_in_tx(tx, &cid).await? {
                        target_entry_id = None;
                        target_drawn_at = None;
                        target_updated = now;
                        target_hlc = clock.tick(now_ms).encode();
                    } else if cid.as_str() != cur_id.as_str() {
                        target_entry_id = Some(cid);
                        target_updated = now;
                        target_hlc = clock.tick(now_ms).encode();
                    }
                } else {
                    target_entry_id = None;
                    target_drawn_at = None;
                    target_updated = now;
                    target_hlc = clock.tick(now_ms).encode();
                }
            }

            if target_enabled && target_entry_id.is_none() {
                if let Some(drawn_id) =
                    CatalogRepo::draw_random_remaining_in_tx(tx, &col_id).await?
                {
                    target_entry_id = Some(drawn_id);
                    target_drawn_at = Some(now);
                    target_updated = now;
                    target_hlc = clock.tick(now_ms).encode();
                } else if target_drawn_at.is_some() {
                    target_drawn_at = None;
                    target_updated = now;
                    target_hlc = clock.tick(now_ms).encode();
                }
            }

            let needs_upsert =
                local_gen.as_ref().is_none_or(|(l_en, l_eid, l_drawn, _l_upd, l_hlc)| {
                    target_enabled != *l_en
                        || target_entry_id != *l_eid
                        || target_drawn_at != *l_drawn
                        || target_hlc != *l_hlc
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
                .bind(target_enabled)
                .bind(target_entry_id.as_deref())
                .bind(target_drawn_at)
                .bind(target_updated)
                .bind(&target_hlc)
                .execute(&mut *tx)
                .await?;
            } else if local_won_conflict {
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
        }

        // Advance any active local generator whose drawn entry became terminal or was removed from
        // its collection pool during this sync
        let active: Vec<(String, bool, String)> = sqlx::query_as(
            "SELECT collection_id, enabled, current_entry_id
             FROM collection_generators WHERE current_entry_id IS NOT NULL",
        )
        .fetch_all(&mut *tx)
        .await?;
        for (col_id, enabled, cur_id) in active {
            let in_pool: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_entries ce
                 JOIN entries e ON e.id = ce.entry_id
                 WHERE ce.collection_id = ? AND ce.entry_id = ? AND e.merged_into_id IS NULL",
            )
            .bind(&col_id)
            .bind(&cur_id)
            .fetch_optional(&mut *tx)
            .await?;
            if in_pool.is_none() || CatalogRepo::is_entry_terminal_in_tx(tx, &cur_id).await? {
                let next_id = if enabled {
                    CatalogRepo::draw_random_remaining_in_tx(tx, &col_id).await?
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
                .bind(&col_id)
                .execute(&mut *tx)
                .await?;
            }
        }
        Ok(())
    }
}

fn witness_request_hlcs(clock: &mut HlcClock, req: &PeerSyncRequest, now_ms: i64) {
    for e in &req.entries {
        if !e.hlc.is_empty() {
            clock.witness_str(&e.hlc, now_ms);
        }
        if let Some(ref st) = e.state
            && !st.hlc.is_empty()
        {
            clock.witness_str(&st.hlc, now_ms);
        }
        if let Some(ref fs) = e.feed_source
            && !fs.hlc.is_empty()
        {
            clock.witness_str(&fs.hlc, now_ms);
        }
    }
    for ce in &req.collection_entries {
        if !ce.hlc.is_empty() {
            clock.witness_str(&ce.hlc, now_ms);
        }
    }
    for g in &req.collection_generators {
        if !g.hlc.is_empty() {
            clock.witness_str(&g.hlc, now_ms);
        }
    }
    for l in &req.consumption_log {
        if !l.hlc.is_empty() {
            clock.witness_str(&l.hlc, now_ms);
        }
    }
    for c in &req.comparisons {
        if !c.hlc.is_empty() {
            clock.witness_str(&c.hlc, now_ms);
        }
    }
    for t in &req.tombstones {
        if !t.hlc.is_empty() {
            clock.witness_str(&t.hlc, now_ms);
        }
    }
}

fn pick_richer_text<'a>(existing: Option<&'a str>, incoming: Option<&'a str>) -> Option<&'a str> {
    match (
        existing.map(str::trim).filter(|s| !s.is_empty()),
        incoming.map(str::trim).filter(|s| !s.is_empty()),
    ) {
        (Some(ex), Some(inc)) => {
            if inc.len() > ex.len() {
                incoming
            } else {
                existing
            }
        }
        (Some(_), None) => existing,
        (None, Some(_)) => incoming,
        (None, None) => None,
    }
}

fn resolve_merged_entry_state(
    existing: ExistingStateRef<'_>,
    incoming: &SyncEntryState,
    inc_progress: f64,
    inc_hlc: &str,
    now: DateTime<Utc>,
) -> MergedStateTuple {
    let (ex_inbox, ex_read_at, ex_prog, ex_inter, ex_hlc) = existing;
    let inc_inter = incoming.interaction_updated_at.map(|ts| ts.trunc_subsecs(3));
    let (inbox, raw_read_at, prog, inter, hlc) = match inc_hlc.cmp(ex_hlc) {
        std::cmp::Ordering::Greater => {
            (incoming.inbox_state, incoming.read_at, inc_progress, inc_inter, inc_hlc.to_owned())
        }
        std::cmp::Ordering::Equal => {
            let merged_inbox = if ex_inbox == InboxState::Dismissed
                || incoming.inbox_state == InboxState::Dismissed
            {
                InboxState::Dismissed
            } else if ex_inbox == InboxState::Unread || incoming.inbox_state == InboxState::Unread {
                InboxState::Unread
            } else {
                InboxState::None
            };
            (
                merged_inbox,
                ex_read_at.max(incoming.read_at),
                ex_prog.max(inc_progress),
                ex_inter.max(inc_inter),
                ex_hlc.to_owned(),
            )
        }
        std::cmp::Ordering::Less => (ex_inbox, ex_read_at, ex_prog, ex_inter, ex_hlc.to_owned()),
    };

    let read_at = if inbox == InboxState::Dismissed {
        raw_read_at.or(ex_read_at).or(incoming.read_at).or(inter).or(Some(now))
    } else {
        raw_read_at
    };
    (inbox, read_at, prog, inter, hlc)
}
