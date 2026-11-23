use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, Utc};
use roche_core::{
    boards::{BOARD_STATE_GRACE_HOURS, BoardOrderMode, BoardSourceSpec, BoardType},
    crdt::{BoardItemStateSnapshot, resolve_merged_board_item_state},
    identity::{board_natural_key, canonicalize_sync_url, deterministic_board_id},
    url_guard::parse_public_url,
};
use sqlx::SqliteConnection;

use super::{
    super::{
        SyncRepo,
        hlc::{clamp_incoming_timestamp, clamp_incoming_timestamp_opt, resolve_or_synthesize_hlc},
        hrw::normalize_consecutive_failures,
        models::{SyncBoardItemRecord, SyncBoardItemStateRecord, SyncBoardRecord},
    },
    MergeContext,
};
use crate::db::{ActivityAction, ActivityEntityType, BoardRepo};

#[derive(sqlx::FromRow)]
struct ExistingBoardRow {
    id: String,
    title: String,
    source_url: String,
    source_config_json: String,
    order_mode: BoardOrderMode,
    poll_interval: i64,
    is_active: bool,
    last_polled_at: Option<DateTime<Utc>>,
    last_polled_by: Option<String>,
    last_error: Option<String>,
    last_error_by: Option<String>,
    consecutive_failures: i64,
    snapshot_hlc: String,
    hlc: String,
}

#[derive(sqlx::FromRow)]
struct ExistingBoardItemStateRow {
    seen_at: Option<DateTime<Utc>>,
    dismissed_at: Option<DateTime<Utc>>,
    last_seen_in_snapshot_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
    hlc: String,
}

impl SyncRepo<'_> {
    pub(super) async fn merge_incoming_boards_in_tx(
        conn: &mut SqliteConnection,
        boards: &[SyncBoardRecord],
        ctx: &MergeContext<'_>,
    ) -> Result<HashMap<String, String>> {
        let mut board_id_remap = HashMap::new();
        for board in boards {
            Self::merge_single_board_in_tx(conn, board, ctx, &mut board_id_remap).await?;
        }
        Ok(board_id_remap)
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_board_in_tx(
        conn: &mut SqliteConnection,
        board: &SyncBoardRecord,
        ctx: &MergeContext<'_>,
        board_id_remap: &mut HashMap<String, String>,
    ) -> Result<()> {
        let Ok(spec) = BoardSourceSpec::validate_config(
            board.adapter_type,
            &board.source_key,
            &board.source_url,
            &board.source_config_json,
        ) else {
            return Ok(());
        };

        let nat_key = board_natural_key(spec.adapter_type.as_str(), &spec.source_key);
        let det_id = deterministic_board_id(spec.adapter_type.as_str(), &spec.source_key);
        let raw_id = board.id.trim();
        let canon_source_url = canonicalize_sync_url(&spec.source_url);
        let safe_created = clamp_incoming_timestamp(board.created_at, ctx.now);
        let safe_updated = clamp_incoming_timestamp(board.updated_at, ctx.now);
        let config_hlc =
            resolve_or_synthesize_hlc(&board.hlc, Some(safe_updated), ctx.now, ctx.peer_id);

        if Self::check_and_clear_tombstone_in_tx(
            conn,
            ActivityEntityType::Board,
            &nat_key,
            &config_hlc,
        )
        .await?
        {
            return Ok(());
        }
        if Self::check_and_clear_tombstone_in_tx(
            conn,
            ActivityEntityType::Board,
            &det_id,
            &config_hlc,
        )
        .await?
        {
            return Ok(());
        }
        if !raw_id.is_empty()
            && raw_id != det_id
            && Self::check_and_clear_tombstone_in_tx(
                conn,
                ActivityEntityType::Board,
                raw_id,
                &config_hlc,
            )
            .await?
        {
            return Ok(());
        }

        let safe_title = {
            let trimmed = board.title.trim();
            if trimmed.is_empty() { spec.default_title.as_str() } else { trimmed }
        };
        let safe_interval = board.poll_interval.max(1);
        let inc_last_polled = clamp_incoming_timestamp_opt(board.last_polled_at, ctx.now);
        let inc_last_error =
            board.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        let inc_last_error_by = inc_last_error.as_ref().and_then(|_| {
            board
                .last_error_by
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned)
        });
        let inc_last_polled_by = board
            .last_polled_by
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_owned);
        let inc_failures =
            normalize_consecutive_failures(inc_last_error.as_deref(), board.consecutive_failures);

        let existing: Option<ExistingBoardRow> = sqlx::query_as(
            "SELECT id, title, source_url, source_config_json, order_mode, poll_interval,
                    is_active, last_polled_at, last_polled_by, last_error, last_error_by,
                    consecutive_failures, snapshot_hlc, hlc
             FROM boards
             WHERE (adapter_type = ? AND source_key = ?) OR id = ? OR id = ?
             ORDER BY CASE WHEN adapter_type = ? AND source_key = ? THEN 0 ELSE 1 END
             LIMIT 1",
        )
        .bind(spec.adapter_type)
        .bind(&spec.source_key)
        .bind(&det_id)
        .bind(raw_id)
        .bind(spec.adapter_type)
        .bind(&spec.source_key)
        .fetch_optional(&mut *conn)
        .await?;

        let (target_board_id, current_snap_hlc) = if let Some(ex) = existing {
            if !raw_id.is_empty() {
                board_id_remap.insert(raw_id.to_owned(), ex.id.clone());
            }
            board_id_remap.insert(det_id.clone(), ex.id.clone());

            // 1. Board Config: LWW by `hlc`
            if config_hlc.as_str() > ex.hlc.as_str() {
                let changed = safe_title != ex.title
                    || spec.source_config_json != ex.source_config_json
                    || canon_source_url != ex.source_url
                    || board.order_mode != ex.order_mode
                    || safe_interval != ex.poll_interval
                    || board.is_active != ex.is_active
                    || config_hlc != ex.hlc;
                if changed {
                    sqlx::query(
                        "UPDATE boards
                         SET title = ?,
                             source_config_json = ?,
                             source_url = ?,
                             order_mode = ?,
                             poll_interval = ?,
                             is_active = ?,
                             updated_at = ?,
                             hlc = ?
                         WHERE id = ?",
                    )
                    .bind(safe_title)
                    .bind(&spec.source_config_json)
                    .bind(&canon_source_url)
                    .bind(board.order_mode)
                    .bind(safe_interval)
                    .bind(board.is_active)
                    .bind(safe_updated)
                    .bind(&config_hlc)
                    .bind(&ex.id)
                    .execute(&mut *conn)
                    .await?;
                }
            }

            // 2. Board Poll Telemetry: newer `last_polled_at` wins
            let inc_telemetry_newer = match (inc_last_polled, ex.last_polled_at) {
                (Some(inc_lp), Some(ex_lp)) => inc_lp > ex_lp,
                (Some(_), None) => true,
                (None, _) => false,
            };
            if inc_telemetry_newer {
                let telemetry_changed = inc_last_polled != ex.last_polled_at
                    || inc_last_polled_by != ex.last_polled_by
                    || inc_last_error != ex.last_error
                    || inc_last_error_by != ex.last_error_by
                    || inc_failures != ex.consecutive_failures;
                if telemetry_changed {
                    sqlx::query(
                        "UPDATE boards
                         SET last_polled_at = ?,
                             last_polled_by = ?,
                             last_error = ?,
                             last_error_by = ?,
                             consecutive_failures = ?
                         WHERE id = ?",
                    )
                    .bind(inc_last_polled)
                    .bind(inc_last_polled_by.as_deref())
                    .bind(inc_last_error.as_deref())
                    .bind(inc_last_error_by.as_deref())
                    .bind(inc_failures)
                    .bind(&ex.id)
                    .execute(&mut *conn)
                    .await?;
                }
            }

            (ex.id, ex.snapshot_hlc)
        } else {
            let target_id = det_id.clone();
            if !raw_id.is_empty() {
                board_id_remap.insert(raw_id.to_owned(), target_id.clone());
            }
            board_id_remap.insert(det_id, target_id.clone());

            sqlx::query(
                "INSERT INTO boards
                 (id, adapter_type, source_key, source_url, source_config_json, title, order_mode,
                  poll_interval, is_active, last_polled_at, last_polled_by, last_error, last_error_by,
                  consecutive_failures, snapshot_fetched_at, snapshot_hlc, created_at, updated_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, '', ?, ?, ?)",
            )
            .bind(&target_id)
            .bind(spec.adapter_type)
            .bind(&spec.source_key)
            .bind(&canon_source_url)
            .bind(&spec.source_config_json)
            .bind(safe_title)
            .bind(board.order_mode)
            .bind(safe_interval)
            .bind(board.is_active)
            .bind(inc_last_polled)
            .bind(inc_last_polled_by.as_deref())
            .bind(inc_last_error.as_deref())
            .bind(inc_last_error_by.as_deref())
            .bind(inc_failures)
            .bind(safe_created)
            .bind(safe_updated)
            .bind(&config_hlc)
            .execute(&mut *conn)
            .await?;

            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Board,
                &target_id,
                ActivityAction::Create,
                ctx.now,
            )
            .await?;

            (target_id, String::new())
        };

        // 3. Board Snapshot replacement
        if let Some(ref items) = board.snapshot_items
            && !board.snapshot_hlc.trim().is_empty()
        {
            Self::merge_board_snapshot_in_tx(
                conn,
                &target_board_id,
                &current_snap_hlc,
                board.snapshot_fetched_at,
                &board.snapshot_hlc,
                items,
                ctx,
            )
            .await?;
        }

        Ok(())
    }

    async fn merge_board_snapshot_in_tx(
        conn: &mut SqliteConnection,
        board_id: &str,
        existing_snap_hlc: &str,
        raw_snapshot_fetched_at: Option<DateTime<Utc>>,
        raw_snapshot_hlc: &str,
        items: &[SyncBoardItemRecord],
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let safe_snap_fetched =
            clamp_incoming_timestamp_opt(raw_snapshot_fetched_at, ctx.now).unwrap_or(ctx.now);
        let inc_snap_hlc = resolve_or_synthesize_hlc(
            raw_snapshot_hlc,
            Some(safe_snap_fetched),
            ctx.now,
            ctx.peer_id,
        );

        let local_item_count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM board_items WHERE board_id = ?")
                .bind(board_id)
                .fetch_one(&mut *conn)
                .await?;

        let should_replace = inc_snap_hlc.as_str() > existing_snap_hlc
            || (inc_snap_hlc.as_str() == existing_snap_hlc && local_item_count == 0);
        if !should_replace {
            return Ok(());
        }

        sqlx::query("DELETE FROM board_items WHERE board_id = ?")
            .bind(board_id)
            .execute(&mut *conn)
            .await?;

        let mut inserted_ids = Vec::with_capacity(items.len());
        let mut seen = HashSet::with_capacity(items.len());
        let mut fallback_rank: i64 = 0;

        for item in items {
            if let Some(ext_id) = Self::insert_synced_board_item_in_tx(
                conn,
                board_id,
                item,
                &mut seen,
                &mut fallback_rank,
                ctx.now,
            )
            .await?
            {
                inserted_ids.push(ext_id);
            }
        }

        BoardRepo::touch_snapshot_seen_states_in_tx(
            conn,
            board_id,
            &inserted_ids,
            safe_snap_fetched,
        )
        .await?;

        sqlx::query(
            "UPDATE boards
             SET snapshot_fetched_at = ?,
                 snapshot_hlc = ?
             WHERE id = ?",
        )
        .bind(safe_snap_fetched)
        .bind(&inc_snap_hlc)
        .bind(board_id)
        .execute(&mut *conn)
        .await?;

        Ok(())
    }

    async fn insert_synced_board_item_in_tx<'a>(
        conn: &mut SqliteConnection,
        board_id: &str,
        item: &'a SyncBoardItemRecord,
        seen: &mut HashSet<&'a str>,
        fallback_rank: &mut i64,
        now: DateTime<Utc>,
    ) -> Result<Option<String>> {
        let ext_id = item.external_id.trim();
        let title = item.title.trim();
        if ext_id.is_empty() || title.is_empty() || !seen.insert(ext_id) {
            return Ok(None);
        }
        let Ok(parsed_url) = parse_public_url(&item.url) else {
            return Ok(None);
        };
        let clean_disc = item
            .discussion_url
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .and_then(|u| parse_public_url(u).ok().map(String::from));
        *fallback_rank = fallback_rank.saturating_add(1);
        let rank = if item.rank >= 1 { item.rank } else { *fallback_rank };
        let fetched_at = clamp_incoming_timestamp(item.fetched_at, now);
        let published_at = clamp_incoming_timestamp_opt(item.published_at, now);
        let meta =
            if item.metadata_json.trim().is_empty() { "{}" } else { item.metadata_json.as_str() };
        let clean_self_text = item
            .self_text_html
            .as_deref()
            .map(|raw| crate::content::HtmlSanitizer::clean_with_base(raw, Some(&parsed_url)))
            .filter(|h| !h.trim().is_empty());

        sqlx::query(
            "INSERT INTO board_items
             (board_id, external_id, rank, title, url, discussion_url, author, domain,
              published_at, score, comment_count, summary, self_text_html, metadata_json,
              fetched_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(board_id)
        .bind(ext_id)
        .bind(rank)
        .bind(title)
        .bind(parsed_url.as_str())
        .bind(clean_disc.as_deref())
        .bind(item.author.as_deref())
        .bind(item.domain.as_deref())
        .bind(published_at)
        .bind(item.score)
        .bind(item.comment_count)
        .bind(item.summary.as_deref())
        .bind(clean_self_text.as_deref())
        .bind(meta)
        .bind(fetched_at)
        .execute(&mut *conn)
        .await?;

        Ok(Some(ext_id.to_owned()))
    }

    pub(super) async fn merge_incoming_board_item_states_in_tx(
        conn: &mut SqliteConnection,
        states: &[SyncBoardItemStateRecord],
        board_id_remap: &HashMap<String, String>,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let grace_cutoff = ctx.now - chrono::Duration::hours(BOARD_STATE_GRACE_HOURS);
        let mut known_boards: HashMap<String, bool> = HashMap::new();

        for st in states {
            Self::merge_single_board_item_state_in_tx(
                conn,
                st,
                board_id_remap,
                &mut known_boards,
                grace_cutoff,
                ctx,
            )
            .await?;
        }
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn merge_single_board_item_state_in_tx(
        conn: &mut SqliteConnection,
        st: &SyncBoardItemStateRecord,
        board_id_remap: &HashMap<String, String>,
        known_boards: &mut HashMap<String, bool>,
        grace_cutoff: DateTime<Utc>,
        ctx: &MergeContext<'_>,
    ) -> Result<()> {
        let raw_board_id = st.board_id.trim();
        let ext_id = st.external_id.trim();
        if raw_board_id.is_empty() || ext_id.is_empty() {
            return Ok(());
        }
        let target_board_id = board_id_remap.get(raw_board_id).map_or(raw_board_id, String::as_str);

        let board_exists = if let Some(&exists) = known_boards.get(target_board_id) {
            exists
        } else {
            let exists = sqlx::query_scalar::<_, i64>("SELECT 1 FROM boards WHERE id = ? LIMIT 1")
                .bind(target_board_id)
                .fetch_optional(&mut *conn)
                .await?
                .is_some();
            known_boards.insert(target_board_id.to_owned(), exists);
            exists
        };
        if !board_exists {
            return Ok(());
        }

        let inc_seen = clamp_incoming_timestamp_opt(st.seen_at, ctx.now);
        let inc_dismissed = clamp_incoming_timestamp_opt(st.dismissed_at, ctx.now);
        let inc_last_seen = clamp_incoming_timestamp(st.last_seen_in_snapshot_at, ctx.now);
        let inc_updated = clamp_incoming_timestamp(st.updated_at, ctx.now);
        let inc_hlc = resolve_or_synthesize_hlc(&st.hlc, Some(inc_updated), ctx.now, ctx.peer_id);

        let in_snapshot = sqlx::query_scalar::<_, i64>(
            "SELECT 1 FROM board_items WHERE board_id = ? AND external_id = ? LIMIT 1",
        )
        .bind(target_board_id)
        .bind(ext_id)
        .fetch_optional(&mut *conn)
        .await?
        .is_some();

        // Ignore incoming expired states if NOT in current snapshot AND older than 48h grace window.
        if !in_snapshot && inc_last_seen.max(inc_updated) < grace_cutoff {
            return Ok(());
        }

        let existing: Option<ExistingBoardItemStateRow> = sqlx::query_as(
            "SELECT seen_at, dismissed_at, last_seen_in_snapshot_at, updated_at, hlc
             FROM board_item_states
             WHERE board_id = ? AND external_id = ?",
        )
        .bind(target_board_id)
        .bind(ext_id)
        .fetch_optional(&mut *conn)
        .await?;

        if let Some(ex) = existing {
            let ex_snap = BoardItemStateSnapshot {
                seen_at: ex.seen_at,
                dismissed_at: ex.dismissed_at,
                last_seen_in_snapshot_at: ex.last_seen_in_snapshot_at,
                updated_at: ex.updated_at,
                hlc: ex.hlc,
            };
            let inc_snap = BoardItemStateSnapshot {
                seen_at: inc_seen,
                dismissed_at: inc_dismissed,
                last_seen_in_snapshot_at: inc_last_seen,
                updated_at: inc_updated,
                hlc: inc_hlc,
            };
            let merged = resolve_merged_board_item_state(&ex_snap, &inc_snap, ctx.now);
            if merged != ex_snap {
                sqlx::query(
                    "UPDATE board_item_states
                     SET seen_at = ?,
                         dismissed_at = ?,
                         last_seen_in_snapshot_at = ?,
                         updated_at = ?,
                         hlc = ?
                     WHERE board_id = ? AND external_id = ?",
                )
                .bind(merged.seen_at)
                .bind(merged.dismissed_at)
                .bind(merged.last_seen_in_snapshot_at)
                .bind(merged.updated_at)
                .bind(&merged.hlc)
                .bind(target_board_id)
                .bind(ext_id)
                .execute(&mut *conn)
                .await?;
            }
        } else {
            sqlx::query(
                "INSERT INTO board_item_states
                 (board_id, external_id, seen_at, dismissed_at,
                  last_seen_in_snapshot_at, updated_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(target_board_id)
            .bind(ext_id)
            .bind(inc_seen)
            .bind(inc_dismissed)
            .bind(inc_last_seen)
            .bind(inc_updated)
            .bind(&inc_hlc)
            .execute(&mut *conn)
            .await?;
        }
        Ok(())
    }

    pub(super) async fn apply_board_tombstone_in_tx(
        conn: &mut SqliteConnection,
        key: &str,
        deleted_at: DateTime<Utc>,
        tomb_hlc: &str,
        now: DateTime<Utc>,
    ) -> Result<()> {
        let trimmed = key.trim();
        let lower_key = trimmed.to_ascii_lowercase();
        let existing: Option<(String, BoardType, String, String)> = sqlx::query_as(
            "SELECT id, adapter_type, source_key, hlc
             FROM boards
             WHERE id = ? OR (LOWER(adapter_type) || char(0) || LOWER(source_key)) = ?
             ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END
             LIMIT 1",
        )
        .bind(trimmed)
        .bind(&lower_key)
        .bind(trimmed)
        .fetch_optional(&mut *conn)
        .await?;

        let record_key = if let Some((board_id, adapter_type, source_key, live_hlc)) = existing {
            if live_hlc.as_str() > tomb_hlc {
                return Ok(());
            }
            sqlx::query("DELETE FROM boards WHERE id = ?")
                .bind(&board_id)
                .execute(&mut *conn)
                .await?;
            Self::record_activity(
                &mut *conn,
                ActivityEntityType::Board,
                &board_id,
                ActivityAction::Delete,
                now,
            )
            .await?;
            board_natural_key(adapter_type.as_str(), &source_key)
        } else if let Some((adapter_part, source_part)) = trimmed.split_once('\0') {
            board_natural_key(adapter_part, source_part)
        } else {
            trimmed.to_owned()
        };

        Self::record_tombstone_with_hlc(
            &mut *conn,
            ActivityEntityType::Board,
            &record_key,
            deleted_at,
            tomb_hlc,
        )
        .await?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::too_many_lines)]
mod tests {
    use roche_core::boards::{BoardSourceSpec, HackerNewsSection};

    use super::*;
    use crate::db::{
        BoardPollBatch, BoardPollCandidate, Db, EntryKind, PeerSyncRequest, PreparedSyncEntry,
        SyncEntryRecord, SyncTombstone,
    };

    #[tokio::test]
    async fn test_bidirectional_board_sync_config_telemetry_snapshots_states_and_tombstones() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let now = Utc::now();
        let t_old = now - chrono::Duration::minutes(30);
        let t_new = now - chrono::Duration::minutes(5);
        let board_id = deterministic_board_id("hacker_news", "top");

        // 1. Peer pushes a new board with snapshot + 2 states (one active, one >48h expired absent)
        let req1 = PeerSyncRequest {
            peer_id: "android-1".to_owned(),
            since_seq: 0,
            boards: vec![SyncBoardRecord {
                id: board_id.clone(),
                adapter_type: BoardType::HackerNews,
                source_key: "top".to_owned(),
                source_url: "https://news.ycombinator.com/".to_owned(),
                source_config_json: r#"{"section":"top"}"#.to_owned(),
                title: "Hacker News".to_owned(),
                order_mode: BoardOrderMode::Ranked,
                poll_interval: 30,
                is_active: true,
                last_polled_at: Some(t_old),
                last_polled_by: Some("android-1".to_owned()),
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                snapshot_fetched_at: Some(t_old),
                snapshot_hlc: "001795460400000-0001-android-1".to_owned(),
                created_at: t_old,
                updated_at: t_old,
                hlc: "001795460400000-0000-android-1".to_owned(),
                snapshot_items: Some(vec![SyncBoardItemRecord {
                    external_id: "42001".to_owned(),
                    rank: 1,
                    title: "Synced HN Story".to_owned(),
                    url: "https://example.com/synced".to_owned(),
                    discussion_url: Some("https://news.ycombinator.com/item?id=42001".to_owned()),
                    author: Some("pg".to_owned()),
                    domain: Some("example.com".to_owned()),
                    published_at: Some(t_old),
                    score: Some(200),
                    comment_count: Some(80),
                    summary: None,
                    self_text_html: None,
                    metadata_json: "{}".to_owned(),
                    fetched_at: t_old,
                }]),
            }],
            board_item_states: vec![
                SyncBoardItemStateRecord {
                    board_id: board_id.clone(),
                    external_id: "42001".to_owned(),
                    seen_at: Some(t_old),
                    dismissed_at: None,
                    last_seen_in_snapshot_at: t_old,
                    updated_at: t_old,
                    hlc: "001795460400000-0002-android-1".to_owned(),
                },
                SyncBoardItemStateRecord {
                    board_id: board_id.clone(),
                    external_id: "expired-999".to_owned(),
                    seen_at: Some(now - chrono::Duration::hours(60)),
                    dismissed_at: Some(now - chrono::Duration::hours(60)),
                    last_seen_in_snapshot_at: now - chrono::Duration::hours(60),
                    updated_at: now - chrono::Duration::hours(60),
                    hlc: "001795400000000-0000-android-1".to_owned(),
                },
            ],
            ..PeerSyncRequest::default()
        };

        let (resp1, _) = db.sync().merge_peer_state_prepared(&req1, &[]).await.unwrap();
        assert_eq!(resp1.boards.len(), 1);
        assert_eq!(resp1.boards[0].snapshot_items.as_ref().unwrap().len(), 1);
        assert_eq!(resp1.board_item_states.len(), 1, "expired >48h absent state must be ignored");
        assert_eq!(resp1.board_item_states[0].external_id, "42001");

        // 2. Peer 2 sends newer config HLC + newer telemetry + newer snapshot HLC + equal-HLC state join
        let req2 = PeerSyncRequest {
            peer_id: "android-2".to_owned(),
            since_seq: resp1.sync_seq,
            boards: vec![SyncBoardRecord {
                id: board_id.clone(),
                adapter_type: BoardType::HackerNews,
                source_key: "top".to_owned(),
                source_url: "https://news.ycombinator.com/".to_owned(),
                source_config_json: r#"{"section":"top"}"#.to_owned(),
                title: "HN Renamed".to_owned(),
                order_mode: BoardOrderMode::Chronological,
                poll_interval: 15,
                is_active: true,
                last_polled_at: Some(t_new),
                last_polled_by: Some("android-2".to_owned()),
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                snapshot_fetched_at: Some(t_new),
                snapshot_hlc: "001795460900000-0001-android-2".to_owned(),
                created_at: t_old,
                updated_at: t_new,
                hlc: "001795460900000-0000-android-2".to_owned(),
                snapshot_items: Some(vec![SyncBoardItemRecord {
                    external_id: "42002".to_owned(),
                    rank: 1,
                    title: "Newer Story".to_owned(),
                    url: "https://example.com/newer".to_owned(),
                    discussion_url: Some("https://news.ycombinator.com/item?id=42002".to_owned()),
                    author: None,
                    domain: Some("example.com".to_owned()),
                    published_at: Some(t_new),
                    score: Some(300),
                    comment_count: Some(90),
                    summary: None,
                    self_text_html: None,
                    metadata_json: "{}".to_owned(),
                    fetched_at: t_new,
                }]),
            }],
            board_item_states: vec![SyncBoardItemStateRecord {
                board_id: board_id.clone(),
                external_id: "42001".to_owned(),
                seen_at: None,
                dismissed_at: Some(t_new),
                last_seen_in_snapshot_at: t_new,
                updated_at: t_new,
                hlc: "001795460400000-0002-android-1".to_owned(),
            }],
            ..PeerSyncRequest::default()
        };

        let (resp2, _) = db.sync().merge_peer_state_prepared(&req2, &[]).await.unwrap();
        let b = db.boards().get(&board_id).await.unwrap().unwrap();
        assert_eq!(b.title, "HN Renamed");
        assert_eq!(b.order_mode, BoardOrderMode::Chronological);
        assert_eq!(b.last_polled_by.as_deref(), Some("android-2"));
        assert_eq!(b.item_count, 1);
        let cur_item = db.boards().get_item(&board_id, "42002").await.unwrap().unwrap();
        assert_eq!(cur_item.title, "Newer Story");

        // Equal-HLC state join preserved seen_at AND added dismissed_at
        let st_42001 = resp2.board_item_states.iter().find(|s| s.external_id == "42001").unwrap();
        assert!(st_42001.seen_at.is_some());
        assert!(st_42001.dismissed_at.is_some());

        // 3. Board tombstone with higher HLC deletes board and suppresses older incoming board
        let tomb_req = PeerSyncRequest {
            peer_id: "android-1".to_owned(),
            since_seq: resp2.sync_seq,
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::Board,
                natural_key: board_natural_key("hacker_news", "top"),
                deleted_at: now,
                hlc: "001795461500000-0000-android-1".to_owned(),
            }],
            boards: vec![SyncBoardRecord {
                id: board_id.clone(),
                adapter_type: BoardType::HackerNews,
                source_key: "top".to_owned(),
                source_url: "https://news.ycombinator.com/".to_owned(),
                source_config_json: r#"{"section":"top"}"#.to_owned(),
                title: "Stale Resurrection".to_owned(),
                order_mode: BoardOrderMode::Ranked,
                poll_interval: 30,
                is_active: true,
                last_polled_at: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                snapshot_fetched_at: None,
                snapshot_hlc: String::new(),
                created_at: t_old,
                updated_at: t_new,
                hlc: "001795461000000-0000-android-2".to_owned(),
                snapshot_items: None,
            }],
            ..PeerSyncRequest::default()
        };
        let (tomb_resp, _) = db.sync().merge_peer_state_prepared(&tomb_req, &[]).await.unwrap();
        assert!(db.boards().get(&board_id).await.unwrap().is_none());
        assert!(tomb_resp.tombstones.iter().any(|t| {
            t.entity_type == ActivityEntityType::Board
                && t.natural_key == board_natural_key("hacker_news", "top")
        }));

        // 4. Newer board config HLC clears tombstone and recreates board
        let recreate_req = PeerSyncRequest {
            peer_id: "android-2".to_owned(),
            since_seq: tomb_resp.sync_seq,
            boards: vec![SyncBoardRecord {
                id: board_id.clone(),
                adapter_type: BoardType::HackerNews,
                source_key: "top".to_owned(),
                source_url: "https://news.ycombinator.com/".to_owned(),
                source_config_json: r#"{"section":"top"}"#.to_owned(),
                title: "HN Back Online".to_owned(),
                order_mode: BoardOrderMode::Ranked,
                poll_interval: 30,
                is_active: true,
                last_polled_at: None,
                last_polled_by: None,
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                snapshot_fetched_at: None,
                snapshot_hlc: String::new(),
                created_at: now,
                updated_at: now,
                hlc: "001795462000000-0000-android-2".to_owned(),
                snapshot_items: None,
            }],
            ..PeerSyncRequest::default()
        };
        let _ = db.sync().merge_peer_state_prepared(&recreate_req, &[]).await.unwrap();
        let recreated = db.boards().get(&board_id).await.unwrap().unwrap();
        assert_eq!(recreated.title, "HN Back Online");
    }

    #[tokio::test]
    async fn test_peer_sync_unions_discussions_metadata_across_peers() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let now = Utc::now();
        let entry_id =
            roche_core::identity::deterministic_item_id(None, "https://example.com/disc");

        // Peer A promotes with HN discussion #100
        let rec_a = SyncEntryRecord {
            id: entry_id.clone(),
            kind: EntryKind::Article,
            title: "Shared Article".to_owned(),
            creators: Vec::new(),
            url: Some("https://example.com/disc".to_owned()),
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata: Some(serde_json::json!({
                "discussions": [{
                    "source": "hacker_news",
                    "url": "https://news.ycombinator.com/item?id=100",
                    "external_id": "100",
                    "label": "Hacker News"
                }]
            })),
            identifiers: Vec::new(),
            merged_into_id: None,
            created_at: Some(now),
            updated_at: Some(now),
            hlc: "001795462000000-0000-peer-a".to_owned(),
            content: None,
            state: None,
            collection_spec: None,
            feed_source: None,
        };
        let sync_req_a = PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            entries: vec![rec_a.clone()],
            ..PeerSyncRequest::default()
        };
        db.sync()
            .merge_peer_state_prepared(
                &sync_req_a,
                &[PreparedSyncEntry::from_pre_cleaned_record(rec_a)],
            )
            .await
            .unwrap();

        // Peer B has an OLDER HLC but adds a second discussion link #200 -> union must keep BOTH!
        let rec_b = SyncEntryRecord {
            id: entry_id.clone(),
            kind: EntryKind::Article,
            title: "Shared Article".to_owned(),
            creators: Vec::new(),
            url: Some("https://example.com/disc".to_owned()),
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata: Some(serde_json::json!({
                "discussions": [{
                    "source": "hacker_news",
                    "url": "https://news.ycombinator.com/item?id=200",
                    "external_id": "200",
                    "label": "Hacker News"
                }]
            })),
            identifiers: Vec::new(),
            merged_into_id: None,
            created_at: Some(now),
            updated_at: Some(now),
            hlc: "001795461000000-0000-peer-b".to_owned(),
            content: None,
            state: None,
            collection_spec: None,
            feed_source: None,
        };
        let sync_req_b = PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            entries: vec![rec_b.clone()],
            ..PeerSyncRequest::default()
        };
        db.sync()
            .merge_peer_state_prepared(
                &sync_req_b,
                &[PreparedSyncEntry::from_pre_cleaned_record(rec_b)],
            )
            .await
            .unwrap();

        let meta_str: String = sqlx::query_scalar("SELECT metadata_json FROM entries WHERE id = ?")
            .bind(&entry_id)
            .fetch_one(db.pool())
            .await
            .unwrap();
        let links = roche_core::boards::extract_discussions_from_metadata_json(&meta_str);
        assert_eq!(links.len(), 2);
        assert_eq!(links[0].external_id.as_deref(), Some("100"));
        assert_eq!(links[1].external_id.as_deref(), Some("200"));
    }

    #[tokio::test]
    async fn test_pollable_feed_trait_on_board_and_outgoing_sync_after_local_poll() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Best);
        let board = db.boards().create(&spec, None, None, 30).await.unwrap();
        db.boards()
            .record_poll_batch(
                &board.id,
                &BoardPollBatch {
                    items: vec![BoardPollCandidate {
                        external_id: "777".to_owned(),
                        rank: 1,
                        title: "Best Story".to_owned(),
                        url: "https://example.com/best".to_owned(),
                        discussion_url: Some("https://news.ycombinator.com/item?id=777".to_owned()),
                        author: Some("bob".to_owned()),
                        domain: Some("example.com".to_owned()),
                        published_at: None,
                        score: Some(500),
                        comment_count: Some(123),
                        summary: None,
                        self_text_html: None,
                        metadata_json: "{}".to_owned(),
                    }],
                },
            )
            .await
            .unwrap();

        let refreshed = db.boards().get(&board.id).await.unwrap().unwrap();
        assert!(!crate::db::should_peer_poll_feed(
            crate::db::SERVER_PEER_ID,
            &[],
            &refreshed,
            Utc::now()
        ));

        let (resp, _) = db
            .sync()
            .merge_peer_state_prepared(
                &PeerSyncRequest {
                    peer_id: "fresh-peer".to_owned(),
                    since_seq: 0,
                    ..PeerSyncRequest::default()
                },
                &[],
            )
            .await
            .unwrap();
        assert_eq!(resp.boards.len(), 1);
        assert_eq!(resp.boards[0].id, board.id);
        assert_eq!(resp.boards[0].snapshot_items.as_ref().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn test_merge_board_snapshot_sanitizes_self_text_html_defense_in_depth() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let now = Utc::now();
        let board_id = deterministic_board_id("hacker_news", "ask");

        let req = PeerSyncRequest {
            peer_id: "untrusted-peer".to_owned(),
            boards: vec![SyncBoardRecord {
                id: board_id.clone(),
                adapter_type: BoardType::HackerNews,
                source_key: "ask".to_owned(),
                source_url: "https://news.ycombinator.com/ask".to_owned(),
                source_config_json: r#"{"section":"ask"}"#.to_owned(),
                title: "Ask HN".to_owned(),
                order_mode: BoardOrderMode::Ranked,
                poll_interval: 30,
                is_active: true,
                last_polled_at: Some(now),
                last_polled_by: Some("untrusted-peer".to_owned()),
                last_error: None,
                last_error_by: None,
                consecutive_failures: 0,
                snapshot_fetched_at: Some(now),
                snapshot_hlc: "001795463000000-0001-untrusted-peer".to_owned(),
                created_at: now,
                updated_at: now,
                hlc: "001795463000000-0000-untrusted-peer".to_owned(),
                snapshot_items: Some(vec![SyncBoardItemRecord {
                    external_id: "9001".to_owned(),
                    rank: 1,
                    title: "Ask HN: Security test".to_owned(),
                    url: "https://news.ycombinator.com/item?id=9001".to_owned(),
                    discussion_url: Some("https://news.ycombinator.com/item?id=9001".to_owned()),
                    author: Some("mallory".to_owned()),
                    domain: Some("news.ycombinator.com".to_owned()),
                    published_at: Some(now),
                    score: Some(10),
                    comment_count: Some(2),
                    summary: None,
                    self_text_html: Some(
                        r#"<p>Safe text</p><script>alert(1)</script><img src="https://example.com/a.png" onerror="steal()">"#
                            .to_owned(),
                    ),
                    metadata_json: "{}".to_owned(),
                    fetched_at: now,
                }]),
            }],
            ..PeerSyncRequest::default()
        };

        db.sync().merge_peer_state_prepared(&req, &[]).await.unwrap();

        let stored = db.boards().get_item(&board_id, "9001").await.unwrap().unwrap();
        let html = stored.self_text_html.as_deref().unwrap();
        assert!(html.contains("Safe text"));
        assert!(!html.contains("<script"), "script tag must be stripped: {html}");
        assert!(!html.contains("onerror"), "inline event handler must be stripped: {html}");
    }
}
