use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, Utc};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection};

use super::{
    SyncRepo,
    activity::SyncTombstone,
    hrw::{PeerNode, SERVER_PEER_ID, normalize_consecutive_failures},
    identity::UP_NEXT_COLLECTION_ID,
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

macro_rules! sync_entry_columns {
    () => {
        "id, kind, title, creators_json, url, published_at, release_year, \
         summary, thumbnail_url, metadata_json, merged_into_id, \
         created_at, updated_at, hlc"
    };
}

macro_rules! exclude_dynamic_or_system_collection_sql {
    () => {
        "NOT EXISTS ( \
            SELECT 1 FROM collection_specs cs \
            WHERE cs.collection_id = entries.id \
              AND (cs.mode = 'dynamic' OR cs.collection_role = 'system') \
        )"
    };
}

#[derive(sqlx::FromRow)]
struct SyncClockRow {
    current_seq: i64,
    min_retained_seq: i64,
}

#[derive(sqlx::FromRow)]
struct SyncCandidateRow {
    sync_seq: i64,
    est_bytes: i64,
}

#[derive(sqlx::FromRow)]
struct RawSyncEntryRow {
    id: String,
    kind: EntryKind,
    title: String,
    creators_json: String,
    url: Option<String>,
    published_at: Option<DateTime<Utc>>,
    release_year: Option<i32>,
    summary: Option<String>,
    thumbnail_url: Option<String>,
    metadata_json: String,
    merged_into_id: Option<String>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct RawSyncIdentifierRow {
    entry_id: String,
    scheme: String,
    value: String,
}

#[derive(sqlx::FromRow)]
struct RawSyncContentRow {
    entry_id: String,
    content_html: Option<String>,
    content_type: ContentType,
    audio_url: Option<String>,
    duration: Option<i64>,
}

#[derive(sqlx::FromRow)]
struct RawSyncStateRow {
    entry_id: String,
    inbox_state: InboxState,
    read_at: Option<DateTime<Utc>>,
    progress: f64,
    interaction_updated_at: Option<DateTime<Utc>>,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct RawSyncSpecRow {
    collection_id: String,
    slug: Option<String>,
    mode: CollectionMode,
    collection_role: CollectionRole,
    rule_json: Option<String>,
    show_in_lists: bool,
    color: Option<String>,
}

#[derive(sqlx::FromRow)]
struct RawSyncFeedSourceRow {
    collection_id: String,
    feed_url: String,
    source_type: FeedType,
    feed_title: Option<String>,
    language: Option<String>,
    poll_interval: i64,
    is_active: bool,
    last_polled_at: Option<DateTime<Utc>>,
    last_polled_by: Option<String>,
    last_error: Option<String>,
    last_error_by: Option<String>,
    consecutive_failures: i64,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct RawSyncComparisonRow {
    id: String,
    ts: DateTime<Utc>,
    scope_collection_id: Option<String>,
    ordering_json: String,
    created_at: DateTime<Utc>,
    hlc: String,
}

struct SyncPageDelta {
    entries: Vec<SyncEntryRecord>,
    collection_entries: Vec<SyncCollectionEntryRecord>,
    collection_generators: Vec<SyncCollectionGeneratorRecord>,
    consumption_log: Vec<SyncConsumptionLogRecord>,
    comparisons: Vec<SyncComparisonRecord>,
    tombstones: Vec<SyncTombstone>,
}

impl SyncRepo<'_> {
    pub(super) async fn collect_peer_sync_response(
        &self,
        requested_since_seq: i64,
        max_page_changes: usize,
        server_hlc: String,
        now: DateTime<Utc>,
    ) -> Result<PeerSyncResponse> {
        let mut tx = crate::db::begin_read(self.pool).await?;

        let clock: SyncClockRow =
            sqlx::query_as("SELECT current_seq, min_retained_seq FROM sync_clock WHERE id = 1")
                .fetch_one(&mut *tx)
                .await?;

        let clamped_since = requested_since_seq.max(0);
        let (reset_required, effective_since) =
            if clamped_since > 0 && clamped_since < clock.min_retained_seq {
                (true, 0)
            } else {
                (false, clamped_since)
            };

        let active_peers = Self::list_active_peers_in_tx(&mut tx, now).await?;
        if effective_since >= clock.current_seq {
            return Ok(empty_peer_sync_response(
                clock.current_seq,
                server_hlc,
                now,
                reset_required,
                active_peers,
            ));
        }

        let candidates =
            Self::probe_outgoing_candidates_in_tx(&mut tx, effective_since, max_page_changes)
                .await?;
        if candidates.is_empty() {
            return Ok(empty_peer_sync_response(
                clock.current_seq,
                server_hlc,
                now,
                reset_required,
                active_peers,
            ));
        }

        let (cutoff_seq, has_more) = compute_sync_page_cutoff(
            &candidates,
            effective_since,
            clock.current_seq,
            max_page_changes,
        );
        let delta = Self::load_sync_page_delta_in_tx(&mut tx, effective_since, cutoff_seq).await?;
        tx.commit().await?;

        Ok(PeerSyncResponse {
            server_peer_id: SERVER_PEER_ID.to_owned(),
            sync_seq: cutoff_seq,
            server_hlc,
            sync_timestamp: now,
            has_more,
            reset_required,
            active_peers,
            entries: delta.entries,
            collection_entries: delta.collection_entries,
            collection_generators: delta.collection_generators,
            consumption_log: delta.consumption_log,
            comparisons: delta.comparisons,
            tombstones: delta.tombstones,
        })
    }

    async fn probe_outgoing_candidates_in_tx(
        conn: &mut SqliteConnection,
        effective_since: i64,
        max_page_changes: usize,
    ) -> Result<Vec<SyncCandidateRow>> {
        let probe_limit = i64::try_from(max_page_changes.saturating_add(1)).unwrap_or(i64::MAX);
        let rows: Vec<SyncCandidateRow> = sqlx::query_as(concat!(
            "SELECT sync_seq,
                    256 + LENGTH(title) + COALESCE(LENGTH(summary), 0)
                        + COALESCE((
                            SELECT MIN(LENGTH(ec.content_html), 512000)
                            FROM entry_content ec WHERE ec.entry_id = entries.id
                        ), 0) AS est_bytes
             FROM entries
             WHERE sync_seq > ? AND ",
            exclude_dynamic_or_system_collection_sql!(),
            " UNION ALL
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
             LIMIT ?"
        ))
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(effective_since)
        .bind(probe_limit)
        .fetch_all(&mut *conn)
        .await?;
        Ok(rows)
    }

    async fn load_sync_page_delta_in_tx(
        conn: &mut SqliteConnection,
        effective_since: i64,
        cutoff_seq: i64,
    ) -> Result<SyncPageDelta> {
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
        .fetch_all(&mut *conn)
        .await?;

        let collection_generators: Vec<SyncCollectionGeneratorRecord> = sqlx::query_as(
            "SELECT collection_id, enabled, current_entry_id, drawn_at, updated_at, hlc
             FROM collection_generators
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(&mut *conn)
        .await?;

        let consumption_log: Vec<SyncConsumptionLogRecord> = sqlx::query_as(
            "SELECT id, entry_id, ts, status, notes, created_at, hlc
             FROM consumption_log
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(&mut *conn)
        .await?;

        let comparisons = Self::load_comparisons_in_tx(conn, effective_since, cutoff_seq).await?;

        let tombstones: Vec<SyncTombstone> = sqlx::query_as(
            "SELECT entity_type, natural_key, deleted_at, hlc
             FROM sync_tombstones
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(&mut *conn)
        .await?;

        let mut raw_entries: Vec<RawSyncEntryRow> = sqlx::query_as(concat!(
            "SELECT ",
            sync_entry_columns!(),
            " FROM entries
             WHERE sync_seq > ? AND sync_seq <= ? AND ",
            exclude_dynamic_or_system_collection_sql!(),
            " ORDER BY sync_seq ASC"
        ))
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(&mut *conn)
        .await?;

        // Ensure every outgoing child record on this page has its referenced `entries` present
        let present_ids: HashSet<String> = raw_entries.iter().map(|r| r.id.clone()).collect();
        let missing_refs = collect_missing_referenced_entry_ids(
            &present_ids,
            &collection_entries,
            &collection_generators,
            &consumption_log,
            &comparisons,
        );
        if !missing_refs.is_empty() {
            let extra =
                Self::fetch_future_referenced_entries(conn, &missing_refs, cutoff_seq).await?;
            raw_entries.extend(extra);
        }

        let entries = Self::hydrate_outgoing_entries(conn, raw_entries).await?;
        Ok(SyncPageDelta {
            entries,
            collection_entries,
            collection_generators,
            consumption_log,
            comparisons,
            tombstones,
        })
    }

    async fn load_comparisons_in_tx(
        conn: &mut SqliteConnection,
        effective_since: i64,
        cutoff_seq: i64,
    ) -> Result<Vec<SyncComparisonRecord>> {
        let raw_comparisons: Vec<RawSyncComparisonRow> = sqlx::query_as(
            "SELECT id, ts, scope_collection_id, ordering_json, created_at, hlc
             FROM comparisons
             WHERE sync_seq > ? AND sync_seq <= ?
             ORDER BY sync_seq ASC",
        )
        .bind(effective_since)
        .bind(cutoff_seq)
        .fetch_all(&mut *conn)
        .await?;

        Ok(raw_comparisons
            .into_iter()
            .filter_map(|row| {
                let ordering: Vec<Vec<String>> = serde_json::from_str(&row.ordering_json).ok()?;
                Some(SyncComparisonRecord {
                    id: row.id,
                    ts: row.ts,
                    scope_collection_id: row.scope_collection_id,
                    ordering,
                    created_at: Some(row.created_at),
                    hlc: row.hlc,
                })
            })
            .collect())
    }

    async fn fetch_future_referenced_entries(
        conn: &mut SqliteConnection,
        candidate_ids: &[String],
        cutoff_seq: i64,
    ) -> Result<Vec<RawSyncEntryRow>> {
        let mut out = Vec::new();
        for chunk in candidate_ids.chunks(SQLITE_BIND_CHUNK) {
            let mut qb = QueryBuilder::<Sqlite>::new(concat!(
                "SELECT ",
                sync_entry_columns!(),
                " FROM entries WHERE sync_seq > "
            ));
            qb.push_bind(cutoff_seq).push(" AND id IN (");
            push_bound_id_list(&mut qb, chunk);
            qb.push(concat!(") AND ", exclude_dynamic_or_system_collection_sql!()));
            let rows: Vec<RawSyncEntryRow> = qb.build_query_as().fetch_all(&mut *conn).await?;
            out.extend(rows);
        }
        Ok(out)
    }

    async fn hydrate_outgoing_entries(
        conn: &mut SqliteConnection,
        raw_entries: Vec<RawSyncEntryRow>,
    ) -> Result<Vec<SyncEntryRecord>> {
        if raw_entries.is_empty() {
            return Ok(Vec::new());
        }
        let ids: Vec<String> = raw_entries.iter().map(|r| r.id.clone()).collect();

        let mut idents_map = load_entry_identifiers_map(conn, &ids).await?;
        let mut content_map = load_entry_content_map(conn, &ids).await?;
        let mut state_map = load_entry_state_map(conn, &ids).await?;
        let mut spec_map = load_collection_spec_map(conn, &ids).await?;
        let mut feed_map = load_feed_source_map(conn, &ids).await?;

        let mut out = Vec::with_capacity(raw_entries.len());
        for row in raw_entries {
            let creators: Vec<String> =
                serde_json::from_str(&row.creators_json).unwrap_or_default();
            let metadata: Option<serde_json::Value> =
                if row.metadata_json.trim().is_empty() || row.metadata_json.trim() == "{}" {
                    None
                } else {
                    serde_json::from_str(&row.metadata_json).ok()
                };
            let identifiers = idents_map.remove(&row.id).unwrap_or_default();
            let content = content_map.remove(&row.id);
            let state = state_map.remove(&row.id);
            let collection_spec = spec_map.remove(&row.id);
            let feed_source = feed_map.remove(&row.id);

            out.push(SyncEntryRecord {
                id: row.id,
                kind: row.kind,
                title: row.title,
                creators,
                url: row.url,
                published_at: row.published_at,
                release_year: row.release_year,
                summary: row.summary,
                thumbnail_url: row.thumbnail_url,
                metadata,
                identifiers,
                merged_into_id: row.merged_into_id,
                created_at: Some(row.created_at),
                updated_at: Some(row.updated_at),
                hlc: row.hlc,
                content,
                state,
                collection_spec,
                feed_source,
            });
        }
        Ok(out)
    }
}

async fn load_entry_identifiers_map(
    conn: &mut SqliteConnection,
    ids: &[String],
) -> Result<HashMap<String, Vec<EntryIdentifier>>> {
    let rows: Vec<RawSyncIdentifierRow> = fetch_chunked_by_ids(
        conn,
        ids,
        "SELECT entry_id, scheme, value FROM entry_identifiers WHERE entry_id IN (",
        ") ORDER BY scheme ASC, value ASC",
    )
    .await?;
    let mut map: HashMap<String, Vec<EntryIdentifier>> = HashMap::new();
    for row in rows {
        map.entry(row.entry_id)
            .or_default()
            .push(EntryIdentifier { scheme: row.scheme, value: row.value });
    }
    Ok(map)
}

async fn load_entry_content_map(
    conn: &mut SqliteConnection,
    ids: &[String],
) -> Result<HashMap<String, SyncEntryContent>> {
    let rows: Vec<RawSyncContentRow> = fetch_chunked_by_ids(
        conn,
        ids,
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
        ")",
    )
    .await?;
    Ok(rows
        .into_iter()
        .map(|row| {
            let bounded_html = row.content_html.map(|h| {
                if h.len() <= MAX_SYNC_INLINE_HTML_BYTES {
                    h
                } else {
                    let boundary = h.floor_char_boundary(MAX_SYNC_INLINE_HTML_BYTES);
                    h[..boundary].to_owned()
                }
            });
            (
                row.entry_id,
                SyncEntryContent {
                    content_type: row.content_type,
                    content_html: bounded_html,
                    audio_url: row.audio_url,
                    duration: row.duration,
                },
            )
        })
        .collect())
}

async fn load_entry_state_map(
    conn: &mut SqliteConnection,
    ids: &[String],
) -> Result<HashMap<String, SyncEntryState>> {
    let rows: Vec<RawSyncStateRow> = fetch_chunked_by_ids(
        conn,
        ids,
        "SELECT entry_id, inbox_state, read_at, progress, interaction_updated_at, hlc
         FROM entry_state WHERE entry_id IN (",
        ")",
    )
    .await?;
    Ok(rows
        .into_iter()
        .map(|row| {
            (
                row.entry_id,
                SyncEntryState {
                    inbox_state: row.inbox_state,
                    read_at: row.read_at,
                    progress: row.progress,
                    interaction_updated_at: row.interaction_updated_at,
                    hlc: row.hlc,
                },
            )
        })
        .collect())
}

async fn load_collection_spec_map(
    conn: &mut SqliteConnection,
    ids: &[String],
) -> Result<HashMap<String, SyncCollectionSpec>> {
    let rows: Vec<RawSyncSpecRow> = fetch_chunked_by_ids(
        conn,
        ids,
        "SELECT collection_id, slug, mode, collection_role, rule_json, show_in_lists, color
         FROM collection_specs WHERE collection_id IN (",
        ")",
    )
    .await?;
    Ok(rows
        .into_iter()
        .map(|row| {
            (
                row.collection_id,
                SyncCollectionSpec {
                    slug: row.slug,
                    mode: row.mode,
                    role: row.collection_role,
                    rule_json: row.rule_json,
                    show_in_lists: row.show_in_lists,
                    color: row.color,
                },
            )
        })
        .collect())
}

async fn load_feed_source_map(
    conn: &mut SqliteConnection,
    ids: &[String],
) -> Result<HashMap<String, SyncFeedSource>> {
    let rows: Vec<RawSyncFeedSourceRow> = fetch_chunked_by_ids(
        conn,
        ids,
        "SELECT collection_id, feed_url, source_type, feed_title, language,
                poll_interval, is_active, last_polled_at, last_polled_by,
                last_error, last_error_by, consecutive_failures, hlc
         FROM feed_sources WHERE collection_id IN (",
        ")",
    )
    .await?;
    Ok(rows
        .into_iter()
        .map(|row| {
            let norm_failures =
                normalize_consecutive_failures(row.last_error.as_deref(), row.consecutive_failures);
            (
                row.collection_id,
                SyncFeedSource {
                    feed_url: row.feed_url,
                    source_type: row.source_type,
                    feed_title: row.feed_title,
                    language: row.language,
                    poll_interval: row.poll_interval,
                    is_active: row.is_active,
                    last_polled_at: row.last_polled_at,
                    last_polled_by: row.last_polled_by,
                    last_error: row.last_error,
                    last_error_by: row.last_error_by,
                    consecutive_failures: norm_failures,
                    hlc: row.hlc,
                },
            )
        })
        .collect())
}

fn empty_peer_sync_response(
    sync_seq: i64,
    server_hlc: String,
    sync_timestamp: DateTime<Utc>,
    reset_required: bool,
    active_peers: Vec<PeerNode>,
) -> PeerSyncResponse {
    PeerSyncResponse {
        server_peer_id: SERVER_PEER_ID.to_owned(),
        sync_seq,
        server_hlc,
        sync_timestamp,
        has_more: false,
        reset_required,
        active_peers,
        entries: Vec::new(),
        collection_entries: Vec::new(),
        collection_generators: Vec::new(),
        consumption_log: Vec::new(),
        comparisons: Vec::new(),
        tombstones: Vec::new(),
    }
}

fn compute_sync_page_cutoff(
    candidates: &[SyncCandidateRow],
    effective_since: i64,
    current_seq: i64,
    max_page_changes: usize,
) -> (i64, bool) {
    let mut used_bytes: usize = 0;
    let mut last_seq = effective_since;
    let mut byte_truncated = false;
    for (accepted_count, cand) in candidates.iter().enumerate() {
        if accepted_count >= max_page_changes {
            break;
        }
        let row_bytes = usize::try_from(cand.est_bytes.max(0)).unwrap_or(0);
        if accepted_count > 0 && used_bytes.saturating_add(row_bytes) > MAX_PEER_SYNC_PAGE_BYTES {
            byte_truncated = true;
            break;
        }
        used_bytes = used_bytes.saturating_add(row_bytes);
        last_seq = cand.sync_seq;
    }
    let has_more = byte_truncated || candidates.len() > max_page_changes;
    let cutoff_seq = if has_more { last_seq } else { current_seq };
    (cutoff_seq, has_more)
}

fn push_bound_id_list(qb: &mut QueryBuilder<Sqlite>, ids: &[String]) {
    let mut sep = qb.separated(", ");
    for id in ids {
        sep.push_bind(id.clone());
    }
}

async fn fetch_chunked_by_ids<T>(
    conn: &mut SqliteConnection,
    ids: &[String],
    prefix: &str,
    suffix: &str,
) -> Result<Vec<T>>
where
    T: for<'r> sqlx::FromRow<'r, sqlx::sqlite::SqliteRow> + Send + Unpin,
{
    let mut out = Vec::new();
    for chunk in ids.chunks(SQLITE_BIND_CHUNK) {
        let mut qb = QueryBuilder::<Sqlite>::new(prefix);
        push_bound_id_list(&mut qb, chunk);
        qb.push(suffix);
        let rows: Vec<T> = qb.build_query_as().fetch_all(&mut *conn).await?;
        out.extend(rows);
    }
    Ok(out)
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
        if !trimmed.is_empty() && trimmed != UP_NEXT_COLLECTION_ID && !present_ids.contains(trimmed)
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
