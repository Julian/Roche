use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite};

use super::{
    SyncRepo,
    activity::SyncTombstone,
    hrw::SERVER_PEER_ID,
    identity::{STARRED_COLLECTION_ID, UP_NEXT_COLLECTION_ID},
    models::{
        MAX_PEER_SYNC_PAGE_BYTES, MAX_SYNC_INLINE_HTML_BYTES, PeerSyncResponse,
        SyncCollectionEntryRecord, SyncCollectionGeneratorRecord, SyncCollectionSpec,
        SyncComparisonRecord, SyncConsumptionLogRecord, SyncEntryContent, SyncEntryRecord,
        SyncEntryState, SyncFeedSource,
    },
};
use crate::db::{
    CollectionMode, CollectionRole, ContentType, EntryIdentifier, EntryKind, FeedType, InboxState,
};

const SQLITE_BIND_CHUNK: usize = 450;

type RawEntryRow = (
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
    String,
);

type RawContentRow = (String, Option<String>, ContentType, Option<String>, Option<i64>);
type RawStateRow = (String, InboxState, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>, String);
type RawSpecRow =
    (String, Option<String>, CollectionMode, CollectionRole, Option<String>, bool, Option<String>);
type RawFeedSourceRow = (
    String,
    String,
    FeedType,
    Option<String>,
    Option<String>,
    i64,
    bool,
    Option<DateTime<Utc>>,
    Option<String>,
    Option<String>,
    Option<String>,
    String,
);

type RawComparisonTuple = (String, DateTime<Utc>, Option<String>, String, DateTime<Utc>, String);

impl SyncRepo<'_> {
    #[allow(clippy::too_many_lines)]
    pub(super) async fn collect_peer_sync_response(
        &self,
        requested_since_seq: i64,
        max_page_changes: usize,
        server_hlc: String,
        now: DateTime<Utc>,
    ) -> Result<PeerSyncResponse> {
        let (current_seq, min_retained_seq): (i64, i64) =
            sqlx::query_as("SELECT current_seq, min_retained_seq FROM sync_clock WHERE id = 1")
                .fetch_one(self.pool)
                .await?;

        let clamped_since = requested_since_seq.max(0);
        let (reset_required, effective_since) =
            if clamped_since > 0 && clamped_since < min_retained_seq {
                (true, 0)
            } else {
                (false, clamped_since)
            };

        let active_peers = self.list_active_peers(now).await?;

        if effective_since >= current_seq {
            return Ok(PeerSyncResponse {
                server_peer_id: SERVER_PEER_ID.to_owned(),
                sync_seq: current_seq,
                server_hlc,
                sync_timestamp: now,
                has_more: false,
                reset_required,
                active_peers,
                entries: Vec::new(),
                collection_entries: Vec::new(),
                collection_generators: Vec::new(),
                consumption_log: Vec::new(),
                comparisons: Vec::new(),
                tombstones: Vec::new(),
            });
        }

        let probe_limit = i64::try_from(max_page_changes.saturating_add(1)).unwrap_or(i64::MAX);
        let candidates: Vec<(i64, i64)> = sqlx::query_as(
            "SELECT sync_seq,
                    256 + LENGTH(title) + COALESCE(LENGTH(summary), 0)
                        + COALESCE((
                            SELECT MIN(LENGTH(ec.content_html), 512000)
                            FROM entry_content ec WHERE ec.entry_id = entries.id
                        ), 0) AS est_bytes
             FROM entries
             WHERE sync_seq > ?
               AND NOT EXISTS (
                   SELECT 1 FROM collection_specs cs
                   WHERE cs.collection_id = entries.id
                     AND (cs.mode = 'dynamic' OR cs.collection_role = 'system')
               )
             UNION ALL
             SELECT sync_seq, 128 AS est_bytes FROM collection_entries WHERE sync_seq > ?
             UNION ALL
             SELECT sync_seq, 128 AS est_bytes FROM collection_generators WHERE sync_seq > ?
             UNION ALL
             SELECT sync_seq, 192 + COALESCE(LENGTH(notes), 0) AS est_bytes
             FROM consumption_log WHERE sync_seq > ?
             UNION ALL
             SELECT sync_seq, 192 + LENGTH(ordering_json) AS est_bytes
             FROM comparisons WHERE sync_seq > ?
             UNION ALL
             SELECT sync_seq, 128 + LENGTH(natural_key) AS est_bytes
             FROM sync_tombstones WHERE sync_seq > ?
             ORDER BY sync_seq ASC
             LIMIT ?",
        )
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(probe_limit)
        .fetch_all(self.pool)
        .await?;

        if candidates.is_empty() {
            return Ok(PeerSyncResponse {
                server_peer_id: SERVER_PEER_ID.to_owned(),
                sync_seq: current_seq,
                server_hlc,
                sync_timestamp: now,
                has_more: false,
                reset_required,
                active_peers,
                entries: Vec::new(),
                collection_entries: Vec::new(),
                collection_generators: Vec::new(),
                consumption_log: Vec::new(),
                comparisons: Vec::new(),
                tombstones: Vec::new(),
            });
        }

        let mut used_bytes: usize = 0;
        let mut last_seq = effective_since;
        let mut byte_truncated = false;
        for (accepted_count, &(seq, raw_bytes)) in candidates.iter().enumerate() {
            if accepted_count >= max_page_changes {
                break;
            }
            let row_bytes = usize::try_from(raw_bytes.max(0)).unwrap_or(0);
            if accepted_count > 0 && used_bytes.saturating_add(row_bytes) > MAX_PEER_SYNC_PAGE_BYTES
            {
                byte_truncated = true;
                break;
            }
            used_bytes = used_bytes.saturating_add(row_bytes);
            last_seq = seq;
        }

        let has_more = byte_truncated || candidates.len() > max_page_changes;
        let cutoff_seq = if has_more { last_seq } else { current_seq };

        let collection_entries: Vec<SyncCollectionEntryRecord> = sqlx::query_as(
            "SELECT ce.collection_id, ce.entry_id, ce.position, ce.added_at, ce.hlc
             FROM collection_entries ce
             LEFT JOIN collection_specs cs ON cs.collection_id = ce.collection_id
             WHERE ce.sync_seq > ? AND ce.sync_seq <= ?
               AND (cs.mode IS NULL OR cs.mode != 'dynamic')
             ORDER BY ce.sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(self.pool)
        .await?;

        let collection_generators: Vec<SyncCollectionGeneratorRecord> = sqlx::query_as(
            "SELECT collection_id, enabled, current_entry_id, drawn_at, updated_at, hlc
             FROM collection_generators
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(self.pool)
        .await?;

        let consumption_log: Vec<SyncConsumptionLogRecord> = sqlx::query_as(
            "SELECT id, entry_id, ts, status, notes, created_at, hlc
             FROM consumption_log
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(self.pool)
        .await?;

        let raw_comparisons: Vec<RawComparisonTuple> = sqlx::query_as(
            "SELECT id, ts, scope_collection_id, ordering_json, created_at, hlc
             FROM comparisons
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(self.pool)
        .await?;

        let comparisons: Vec<SyncComparisonRecord> = raw_comparisons
            .into_iter()
            .filter_map(|(id, ts, scope_collection_id, ordering_json, created_at, hlc)| {
                let ordering: Vec<Vec<String>> = serde_json::from_str(&ordering_json).ok()?;
                Some(SyncComparisonRecord {
                    id,
                    ts,
                    scope_collection_id,
                    ordering,
                    created_at: Some(created_at),
                    hlc,
                })
            })
            .collect();

        let tombstones: Vec<SyncTombstone> = sqlx::query_as(
            "SELECT entity_type, natural_key, deleted_at, hlc
             FROM sync_tombstones
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(self.pool)
        .await?;

        let mut raw_entries: Vec<RawEntryRow> = sqlx::query_as(
            "SELECT id, kind, title, creators_json, url, published_at, release_year,
                    summary, thumbnail_url, metadata_json, merged_into_id,
                    created_at, updated_at, hlc
             FROM entries
             WHERE sync_seq > ? AND sync_seq <= ?
               AND NOT EXISTS (
                   SELECT 1 FROM collection_specs cs
                   WHERE cs.collection_id = entries.id
                     AND (cs.mode = 'dynamic' OR cs.collection_role = 'system')
               )
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(self.pool)
        .await?;

        // Ensure every outgoing child record on this page has its referenced `entries` present
        let present_ids: HashSet<String> = raw_entries.iter().map(|r| r.0.clone()).collect();
        let missing_refs = collect_missing_referenced_entry_ids(
            &present_ids,
            &collection_entries,
            &collection_generators,
            &consumption_log,
            &comparisons,
        );
        if !missing_refs.is_empty() {
            let extra = self.fetch_future_referenced_entries(&missing_refs, cutoff_seq).await?;
            raw_entries.extend(extra);
        }

        let entries = self.hydrate_outgoing_entries(raw_entries).await?;

        Ok(PeerSyncResponse {
            server_peer_id: SERVER_PEER_ID.to_owned(),
            sync_seq: cutoff_seq,
            server_hlc,
            sync_timestamp: now,
            has_more,
            reset_required,
            active_peers,
            entries,
            collection_entries,
            collection_generators,
            consumption_log,
            comparisons,
            tombstones,
        })
    }

    async fn fetch_future_referenced_entries(
        &self,
        candidate_ids: &[String],
        cutoff_seq: i64,
    ) -> Result<Vec<RawEntryRow>> {
        let mut out = Vec::new();
        for chunk in candidate_ids.chunks(SQLITE_BIND_CHUNK) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT id, kind, title, creators_json, url, published_at, release_year,
                        summary, thumbnail_url, metadata_json, merged_into_id,
                        created_at, updated_at, hlc
                 FROM entries
                 WHERE sync_seq > ",
            );
            qb.push_bind(cutoff_seq).push(" AND id IN (");
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(
                ") AND NOT EXISTS (
                    SELECT 1 FROM collection_specs cs
                    WHERE cs.collection_id = entries.id
                      AND (cs.mode = 'dynamic' OR cs.collection_role = 'system')
                )",
            );
            let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;
            out.extend(rows);
        }
        Ok(out)
    }

    #[allow(clippy::too_many_lines)]
    async fn hydrate_outgoing_entries(
        &self,
        raw_entries: Vec<RawEntryRow>,
    ) -> Result<Vec<SyncEntryRecord>> {
        if raw_entries.is_empty() {
            return Ok(Vec::new());
        }
        let ids: Vec<String> = raw_entries.iter().map(|r| r.0.clone()).collect();

        let mut idents_map: HashMap<String, Vec<EntryIdentifier>> = HashMap::new();
        let mut content_map: HashMap<String, SyncEntryContent> = HashMap::new();
        let mut state_map: HashMap<String, SyncEntryState> = HashMap::new();
        let mut spec_map: HashMap<String, SyncCollectionSpec> = HashMap::new();
        let mut feed_map: HashMap<String, SyncFeedSource> = HashMap::new();

        for chunk in ids.chunks(SQLITE_BIND_CHUNK) {
            // 1. Identifiers
            let mut id_qb = QueryBuilder::<Sqlite>::new(
                "SELECT entry_id, scheme, value FROM entry_identifiers WHERE entry_id IN (",
            );
            let mut sep = id_qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(") ORDER BY scheme ASC, value ASC");
            let id_rows: Vec<(String, String, String)> =
                id_qb.build_query_as().fetch_all(self.pool).await?;
            for (eid, scheme, value) in id_rows {
                idents_map.entry(eid).or_default().push(EntryIdentifier { scheme, value });
            }

            // 2. Content + remote audio enclosure (`local_path IS NULL`)
            let mut ct_qb = QueryBuilder::<Sqlite>::new(
                "SELECT ec.entry_id,
                        ec.content_html,
                        ec.content_type,
                        (SELECT m.remote_url FROM entry_media m
                         WHERE m.entry_id = ec.entry_id AND m.media_type = 'audio' AND m.remote_url IS NOT NULL
                         ORDER BY m.created_at DESC LIMIT 1) AS audio_url,
                        (SELECT m.duration FROM entry_media m
                         WHERE m.entry_id = ec.entry_id AND m.duration IS NOT NULL
                         ORDER BY m.created_at DESC LIMIT 1) AS duration
                 FROM entry_content ec
                 WHERE ec.entry_id IN (",
            );
            let mut ct_sep = ct_qb.separated(", ");
            for id in chunk {
                ct_sep.push_bind(id);
            }
            ct_sep.push_unseparated(")");
            let ct_rows: Vec<RawContentRow> = ct_qb.build_query_as().fetch_all(self.pool).await?;
            for (eid, html, content_type, audio_url, duration) in ct_rows {
                let bounded_html = html.map(|h| {
                    if h.len() <= MAX_SYNC_INLINE_HTML_BYTES {
                        h
                    } else {
                        let boundary = h.floor_char_boundary(MAX_SYNC_INLINE_HTML_BYTES);
                        h[..boundary].to_owned()
                    }
                });
                content_map.insert(
                    eid,
                    SyncEntryContent {
                        content_type,
                        content_html: bounded_html,
                        audio_url,
                        duration,
                    },
                );
            }

            // 3. Inbox / reading state
            let mut st_qb = QueryBuilder::<Sqlite>::new(
                "SELECT entry_id, inbox_state, read_at, progress, interaction_updated_at, hlc
                 FROM entry_state WHERE entry_id IN (",
            );
            let mut st_sep = st_qb.separated(", ");
            for id in chunk {
                st_sep.push_bind(id);
            }
            st_sep.push_unseparated(")");
            let st_rows: Vec<RawStateRow> = st_qb.build_query_as().fetch_all(self.pool).await?;
            for (eid, inbox_state, read_at, progress, interaction_updated_at, hlc) in st_rows {
                state_map.insert(
                    eid,
                    SyncEntryState { inbox_state, read_at, progress, interaction_updated_at, hlc },
                );
            }

            // 4. Collection specs
            let mut spec_qb = QueryBuilder::<Sqlite>::new(
                "SELECT collection_id, slug, mode, collection_role, rule_json, show_in_lists, color
                 FROM collection_specs WHERE collection_id IN (",
            );
            let mut spec_sep = spec_qb.separated(", ");
            for id in chunk {
                spec_sep.push_bind(id);
            }
            spec_sep.push_unseparated(")");
            let spec_rows: Vec<RawSpecRow> = spec_qb.build_query_as().fetch_all(self.pool).await?;
            for (cid, slug, mode, role, rule_json, show_in_lists, color) in spec_rows {
                spec_map.insert(
                    cid,
                    SyncCollectionSpec { slug, mode, role, rule_json, show_in_lists, color },
                );
            }

            // 5. Feed sources
            let mut fs_qb = QueryBuilder::<Sqlite>::new(
                "SELECT collection_id, feed_url, source_type, feed_title, language,
                        poll_interval, is_active, last_polled_at, last_polled_by,
                        last_error, last_error_by, hlc
                 FROM feed_sources WHERE collection_id IN (",
            );
            let mut fs_sep = fs_qb.separated(", ");
            for id in chunk {
                fs_sep.push_bind(id);
            }
            fs_sep.push_unseparated(")");
            let fs_rows: Vec<RawFeedSourceRow> =
                fs_qb.build_query_as().fetch_all(self.pool).await?;
            for (
                cid,
                feed_url,
                source_type,
                feed_title,
                language,
                poll_interval,
                is_active,
                last_polled_at,
                last_polled_by,
                last_error,
                last_error_by,
                hlc,
            ) in fs_rows
            {
                feed_map.insert(
                    cid,
                    SyncFeedSource {
                        feed_url,
                        source_type,
                        feed_title,
                        language,
                        poll_interval,
                        is_active,
                        last_polled_at,
                        last_polled_by,
                        last_error,
                        last_error_by,
                        hlc,
                    },
                );
            }
        }

        let mut out = Vec::with_capacity(raw_entries.len());
        for (
            id,
            kind,
            title,
            creators_json,
            url,
            published_at,
            release_year,
            summary,
            thumbnail_url,
            metadata_json,
            merged_into_id,
            created_at,
            updated_at,
            hlc,
        ) in raw_entries
        {
            let creators: Vec<String> = serde_json::from_str(&creators_json).unwrap_or_default();
            let metadata: Option<serde_json::Value> =
                if metadata_json.trim().is_empty() || metadata_json.trim() == "{}" {
                    None
                } else {
                    serde_json::from_str(&metadata_json).ok()
                };
            let identifiers = idents_map.remove(&id).unwrap_or_default();
            let content = content_map.remove(&id);
            let state = state_map.remove(&id);
            let collection_spec = spec_map.remove(&id);
            let feed_source = feed_map.remove(&id);

            out.push(SyncEntryRecord {
                id,
                kind,
                title,
                creators,
                url,
                published_at,
                release_year,
                summary,
                thumbnail_url,
                metadata,
                identifiers,
                merged_into_id,
                created_at: Some(created_at),
                updated_at: Some(updated_at),
                hlc,
                content,
                state,
                collection_spec,
                feed_source,
            });
        }
        Ok(out)
    }
}

fn collect_missing_referenced_entry_ids(
    present_ids: &HashSet<String>,
    collection_entries: &[SyncCollectionEntryRecord],
    collection_generators: &[SyncCollectionGeneratorRecord],
    consumption_log: &[SyncConsumptionLogRecord],
    comparisons: &[SyncComparisonRecord],
) -> Vec<String> {
    let mut missing = HashSet::new();
    let mut push_ref = |id: &str| {
        let trimmed = id.trim();
        if !trimmed.is_empty()
            && trimmed != STARRED_COLLECTION_ID
            && trimmed != UP_NEXT_COLLECTION_ID
            && !present_ids.contains(trimmed)
        {
            missing.insert(trimmed.to_owned());
        }
    };

    for ce in collection_entries {
        push_ref(&ce.collection_id);
        push_ref(&ce.entry_id);
    }
    for g in collection_generators {
        push_ref(&g.collection_id);
        if let Some(ref cur) = g.current_entry_id {
            push_ref(cur);
        }
    }
    for log in consumption_log {
        push_ref(&log.entry_id);
    }
    for comp in comparisons {
        if let Some(ref sc) = comp.scope_collection_id {
            push_ref(sc);
        }
        for group in &comp.ordering {
            for eid in group {
                push_ref(eid);
            }
        }
    }
    missing.into_iter().collect()
}
