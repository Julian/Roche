use std::collections::{HashMap, HashSet};

use anyhow::Result;
use chrono::{DateTime, Utc};
use roche_core::{
    boards::{
        BoardOrderMode, BoardSourceSpec, BoardType, DiscussionLink, extract_hn_item_id,
        merge_discussions_into_metadata_json,
    },
    classify::classify_url,
    identity::{
        board_natural_key, canonicalize_sync_url, deterministic_board_id, deterministic_item_id,
        item_natural_key,
    },
    media_urls::{canonical_arxiv_abstract_url, extract_arxiv_id, strip_arxiv_version},
};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection, SqlitePool};
use url::Url;

use super::{
    ActivityAction, ActivityEntityType, CatalogError, CatalogRepo, CatalogResult,
    ConsumptionStatus, ContentType, EntryKind, InboxState, Item, STARRED_COLLECTION_ID, SyncRepo,
    TagRepo, WANT_TO_READ_COLLECTION_ID, WriteLock, WriteTx,
    entries_core::{self, EntryInsertParams, EntryStateUpsert},
    feeds::resolve_dedup_entry_id,
    items::{
        ItemContentProjection, item_select_query_builder, set_collection_flag_membership_in_tx,
        set_favorite_membership_in_tx,
    },
    serialize_creators_json,
    sync::{MAX_CONSECUTIVE_FAILURES, SERVER_PEER_ID, normalize_consecutive_failures},
};
use crate::util::clock::now_utc;

const SQLITE_BIND_CHUNK: usize = 450;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Board {
    pub id: String,
    pub adapter_type: BoardType,
    pub source_key: String,
    pub source_url: String,
    pub source_config_json: String,
    pub title: String,
    pub order_mode: BoardOrderMode,
    pub poll_interval: i64,
    pub is_active: bool,
    pub last_polled_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub last_polled_by: Option<String>,
    pub last_error: Option<String>,
    #[serde(default)]
    pub last_error_by: Option<String>,
    #[serde(default)]
    pub consecutive_failures: i64,
    pub snapshot_fetched_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub snapshot_hlc: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub item_count: i64,
    #[serde(default)]
    pub visible_count: i64,
}

#[derive(sqlx::FromRow)]
struct RawBoardRow {
    id: String,
    adapter_type: BoardType,
    source_key: String,
    source_url: String,
    source_config_json: String,
    title: String,
    order_mode: BoardOrderMode,
    poll_interval: i64,
    is_active: bool,
    last_polled_at: Option<DateTime<Utc>>,
    last_polled_by: Option<String>,
    last_error: Option<String>,
    last_error_by: Option<String>,
    consecutive_failures: i64,
    snapshot_fetched_at: Option<DateTime<Utc>>,
    snapshot_hlc: String,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
    item_count: i64,
    visible_count: i64,
}

impl From<RawBoardRow> for Board {
    fn from(raw: RawBoardRow) -> Self {
        let consecutive_failures =
            normalize_consecutive_failures(raw.last_error.as_deref(), raw.consecutive_failures);
        Self {
            id: raw.id,
            adapter_type: raw.adapter_type,
            source_key: raw.source_key,
            source_url: raw.source_url,
            source_config_json: raw.source_config_json,
            title: raw.title,
            order_mode: raw.order_mode,
            poll_interval: raw.poll_interval,
            is_active: raw.is_active,
            last_polled_at: raw.last_polled_at,
            last_polled_by: raw.last_polled_by,
            last_error: raw.last_error,
            last_error_by: raw.last_error_by,
            consecutive_failures,
            snapshot_fetched_at: raw.snapshot_fetched_at,
            snapshot_hlc: raw.snapshot_hlc,
            created_at: raw.created_at,
            updated_at: raw.updated_at,
            item_count: raw.item_count,
            visible_count: raw.visible_count,
        }
    }
}

macro_rules! board_select_sql {
    ($suffix:literal) => {
        concat!(
            "SELECT b.id,
       b.adapter_type,
       b.source_key,
       b.source_url,
       b.source_config_json,
       b.title,
       b.order_mode,
       b.poll_interval,
       b.is_active,
       b.last_polled_at,
       b.last_polled_by,
       b.last_error,
       b.last_error_by,
       b.consecutive_failures,
       b.snapshot_fetched_at,
       b.snapshot_hlc,
       b.created_at,
       b.updated_at,
       (SELECT COUNT(*) FROM board_items bi WHERE bi.board_id = b.id) AS item_count,
       (SELECT COUNT(*)
        FROM board_items bi
        LEFT JOIN board_item_states bis
          ON bis.board_id = bi.board_id AND bis.external_id = bi.external_id
        WHERE bi.board_id = b.id AND bis.dismissed_at IS NULL) AS visible_count
FROM boards b ",
            $suffix
        )
    };
}

const BOARD_SELECT_BY_ID_SQL: &str = board_select_sql!("WHERE b.id = ?");
const BOARD_SELECT_ALL_SQL: &str =
    board_select_sql!("ORDER BY b.is_active DESC, b.created_at ASC, b.id ASC");

const BOARD_SELECT_ACTIVE_POLL_SQL: &str = "SELECT b.id,
       b.adapter_type,
       b.source_key,
       b.source_url,
       b.source_config_json,
       b.title,
       b.order_mode,
       b.poll_interval,
       b.is_active,
       b.last_polled_at,
       b.last_polled_by,
       b.last_error,
       b.last_error_by,
       b.consecutive_failures,
       b.snapshot_fetched_at,
       b.snapshot_hlc,
       b.created_at,
       b.updated_at,
       0 AS item_count,
       0 AS visible_count
FROM boards b
WHERE b.is_active = 1";

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct BoardItem {
    pub board_id: String,
    pub external_id: String,
    pub rank: i64,
    pub title: String,
    pub url: String,
    pub discussion_url: Option<String>,
    pub author: Option<String>,
    pub domain: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub score: Option<i64>,
    pub comment_count: Option<i64>,
    pub summary: Option<String>,
    pub self_text_html: Option<String>,
    pub metadata_json: String,
    pub fetched_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct BoardItemState {
    pub board_id: String,
    pub external_id: String,
    pub seen_at: Option<DateTime<Utc>>,
    pub dismissed_at: Option<DateTime<Utc>>,
    pub last_seen_in_snapshot_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(clippy::struct_excessive_bools)]
pub struct BoardItemView {
    pub board_id: String,
    pub external_id: String,
    pub rank: i64,
    pub title: String,
    pub url: String,
    pub discussion_url: Option<String>,
    pub author: Option<String>,
    pub domain: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub score: Option<i64>,
    pub comment_count: Option<i64>,
    pub summary: Option<String>,
    pub self_text_html: Option<String>,
    pub fetched_at: DateTime<Utc>,
    pub is_seen: bool,
    pub seen_at: Option<DateTime<Utc>>,
    pub is_dismissed: bool,
    pub dismissed_at: Option<DateTime<Utc>>,
    pub linked_entry_id: Option<String>,
    pub in_want_to_read: bool,
    pub is_favorite: bool,
    pub is_finished: bool,
}

#[derive(Debug, Clone)]
pub struct BoardItemsSlice {
    pub items: Vec<BoardItemView>,
    pub total_buffered: usize,
    pub has_more_buffered: bool,
}

#[derive(Debug, Clone)]
pub struct BoardPollCandidate {
    pub external_id: String,
    pub rank: i64,
    pub title: String,
    pub url: String,
    pub discussion_url: Option<String>,
    pub author: Option<String>,
    pub domain: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub score: Option<i64>,
    pub comment_count: Option<i64>,
    pub summary: Option<String>,
    pub self_text_html: Option<String>,
    pub metadata_json: String,
}

impl BoardPollCandidate {
    #[must_use]
    pub fn into_board_item(self, board_id: &str, fetched_at: DateTime<Utc>) -> BoardItem {
        BoardItem {
            board_id: board_id.to_owned(),
            external_id: self.external_id,
            rank: self.rank,
            title: self.title,
            url: self.url,
            discussion_url: self.discussion_url,
            author: self.author,
            domain: self.domain,
            published_at: self.published_at,
            score: self.score,
            comment_count: self.comment_count,
            summary: self.summary,
            self_text_html: self.self_text_html,
            metadata_json: self.metadata_json,
            fetched_at,
        }
    }
}

#[derive(Debug, Clone)]
pub struct BoardPollBatch {
    pub items: Vec<BoardPollCandidate>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum BoardPromoteAction {
    Queue,
    Star,
    Finish {
        #[serde(default)]
        notes: Option<String>,
    },
    Tag {
        #[serde(default)]
        tag_id: Option<String>,
        #[serde(default, alias = "name")]
        tag_name: Option<String>,
    },
    List {
        #[serde(alias = "collection_id", alias = "slug_or_id", alias = "list_id")]
        list_slug_or_id: String,
    },
}

#[derive(Debug, Default, Clone)]
pub struct BoardPromoteContent {
    pub content_html: Option<String>,
    pub summary: Option<String>,
    pub thumbnail_url: Option<String>,
    pub author: Option<String>,
}

#[derive(Debug, Clone)]
pub struct BoardPromoteOutcome {
    pub entry_id: String,
    pub created_new: bool,
    pub content_type: ContentType,
    pub needs_html_enrichment: bool,
    pub item: Item,
}

#[derive(sqlx::FromRow)]
struct EntryStatusFlags {
    id: String,
    in_want_to_read: bool,
    is_favorite: bool,
    is_finished: bool,
}

#[derive(sqlx::FromRow)]
struct ExistingPromoteEntry {
    summary: Option<String>,
    thumbnail_url: Option<String>,
    metadata_json: String,
    existing_html: Option<String>,
}

pub struct BoardRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
    pub(crate) write_lock: &'a WriteLock,
}

impl<'a> BoardRepo<'a> {
    #[must_use]
    pub(crate) const fn new(pool: &'a SqlitePool, write_lock: &'a WriteLock) -> Self {
        Self { pool, write_lock }
    }

    pub async fn create(
        &self,
        spec: &BoardSourceSpec,
        title: Option<&str>,
        order_mode: Option<BoardOrderMode>,
        poll_interval: i64,
    ) -> Result<Board> {
        let adapter_str = spec.adapter_type.as_str();
        let det_id = deterministic_board_id(adapter_str, &spec.source_key);
        let nat_key = board_natural_key(adapter_str, &spec.source_key);
        let canon_source_url = canonicalize_sync_url(&spec.source_url);
        let final_title =
            title.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(spec.default_title.as_str());
        let final_order = order_mode.unwrap_or(spec.default_order_mode);
        let safe_interval = poll_interval.max(1);
        let now = now_utc();

        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::Board, &nat_key).await?;
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::Board, &det_id).await?;

        let existing: Option<(String, bool)> = sqlx::query_as(
            "SELECT id, is_active FROM boards WHERE adapter_type = ? AND source_key = ? LIMIT 1",
        )
        .bind(spec.adapter_type)
        .bind(&spec.source_key)
        .fetch_optional(tx.conn())
        .await?;

        if let Some((existing_id, is_active)) = existing {
            if is_active {
                return Err(super::ConflictError(format!(
                    "Board '{}' ({}) already exists",
                    spec.default_title, spec.source_key
                ))
                .into());
            }
            let board = Self::reactivate_archived_board_in_tx(
                &mut tx,
                &existing_id,
                spec,
                final_title,
                final_order,
                safe_interval,
                &canon_source_url,
                now,
            )
            .await?;
            tx.commit().await?;
            return Ok(board);
        }

        let hlc = tx.next_hlc().await?;
        sqlx::query(
            "INSERT INTO boards
             (id, adapter_type, source_key, source_url, source_config_json, title, order_mode,
              poll_interval, is_active, created_at, updated_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
        )
        .bind(&det_id)
        .bind(spec.adapter_type)
        .bind(&spec.source_key)
        .bind(&canon_source_url)
        .bind(&spec.source_config_json)
        .bind(final_title)
        .bind(final_order)
        .bind(safe_interval)
        .bind(now)
        .bind(now)
        .bind(&hlc)
        .execute(tx.conn())
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Board,
            &det_id,
            ActivityAction::Create,
            now,
        )
        .await?;

        let board = Self::get_in_conn(tx.conn(), &det_id)
            .await?
            .ok_or_else(|| anyhow::anyhow!("Board disappeared after insert"))?;
        tx.commit().await?;
        Ok(board)
    }

    #[allow(clippy::too_many_arguments)]
    async fn reactivate_archived_board_in_tx(
        tx: &mut WriteTx<'_>,
        existing_id: &str,
        spec: &BoardSourceSpec,
        title: &str,
        order_mode: BoardOrderMode,
        poll_interval: i64,
        canon_source_url: &str,
        now: DateTime<Utc>,
    ) -> Result<Board> {
        SyncRepo::clear_tombstone(tx.conn(), ActivityEntityType::Board, existing_id).await?;
        let hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE boards
             SET is_active = 1,
                 title = ?,
                 order_mode = ?,
                 poll_interval = ?,
                 source_url = ?,
                 source_config_json = ?,
                 last_error = NULL,
                 last_error_by = NULL,
                 consecutive_failures = 0,
                 updated_at = ?,
                 hlc = ?
             WHERE id = ?",
        )
        .bind(title)
        .bind(order_mode)
        .bind(poll_interval)
        .bind(canon_source_url)
        .bind(&spec.source_config_json)
        .bind(now)
        .bind(&hlc)
        .bind(existing_id)
        .execute(tx.conn())
        .await?;

        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Board,
            existing_id,
            ActivityAction::Unarchive,
            now,
        )
        .await?;
        Self::get_in_conn(tx.conn(), existing_id)
            .await?
            .ok_or_else(|| anyhow::anyhow!("Board disappeared after reactivation"))
    }

    pub async fn get(&self, id: &str) -> Result<Option<Board>> {
        let mut conn = self.pool.acquire().await?;
        Self::get_in_conn(&mut conn, id).await
    }

    async fn get_in_conn(conn: &mut SqliteConnection, id: &str) -> Result<Option<Board>> {
        let raw = sqlx::query_as::<_, RawBoardRow>(BOARD_SELECT_BY_ID_SQL)
            .bind(id)
            .fetch_optional(&mut *conn)
            .await?;
        Ok(raw.map(Board::from))
    }

    pub async fn list(&self) -> Result<Vec<Board>> {
        let rows =
            sqlx::query_as::<_, RawBoardRow>(BOARD_SELECT_ALL_SQL).fetch_all(self.pool).await?;
        Ok(rows.into_iter().map(Board::from).collect())
    }

    pub async fn count(&self) -> Result<usize> {
        let count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM boards").fetch_one(self.pool).await?;
        Ok(usize::try_from(count).unwrap_or(0))
    }

    pub async fn get_active_for_cooperative_polling(&self) -> Result<Vec<Board>> {
        let rows = sqlx::query_as::<_, RawBoardRow>(BOARD_SELECT_ACTIVE_POLL_SQL)
            .fetch_all(self.pool)
            .await?;
        Ok(rows.into_iter().map(Board::from).collect())
    }

    pub async fn update(
        &self,
        id: &str,
        title: Option<&str>,
        order_mode: Option<BoardOrderMode>,
        poll_interval: Option<i64>,
        is_active: Option<bool>,
    ) -> Result<bool> {
        if title.is_none() && order_mode.is_none() && poll_interval.is_none() && is_active.is_none()
        {
            return Ok(self.get(id).await?.is_some());
        }

        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let existing: Option<(String, BoardOrderMode, i64, bool)> = sqlx::query_as(
            "SELECT title, order_mode, poll_interval, is_active FROM boards WHERE id = ?",
        )
        .bind(id)
        .fetch_optional(tx.conn())
        .await?;
        let Some((cur_title, cur_order, cur_interval, cur_active)) = existing else {
            return Ok(false);
        };

        let next_title = title.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(&cur_title);
        let next_order = order_mode.unwrap_or(cur_order);
        let next_interval = poll_interval.map_or(cur_interval, |v| v.max(1));
        let next_active = is_active.unwrap_or(cur_active);

        if next_title == cur_title
            && next_order == cur_order
            && next_interval == cur_interval
            && next_active == cur_active
        {
            return Ok(true);
        }

        let hlc = tx.next_hlc().await?;
        sqlx::query(
            "UPDATE boards
             SET title = ?,
                 order_mode = ?,
                 poll_interval = ?,
                 is_active = ?,
                 updated_at = ?,
                 hlc = ?
             WHERE id = ?",
        )
        .bind(next_title)
        .bind(next_order)
        .bind(next_interval)
        .bind(next_active)
        .bind(now)
        .bind(&hlc)
        .bind(id)
        .execute(tx.conn())
        .await?;

        let action = match (cur_active, next_active) {
            (true, false) => ActivityAction::Archive,
            (false, true) => ActivityAction::Unarchive,
            _ => ActivityAction::Update,
        };
        SyncRepo::record_activity(tx.conn(), ActivityEntityType::Board, id, action, now).await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn archive(&self, id: &str) -> Result<bool> {
        self.update(id, None, None, None, Some(false)).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<bool> {
        self.update(id, None, None, None, Some(true)).await
    }

    pub async fn delete(&self, id: &str) -> Result<bool> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let existing: Option<(BoardType, String)> =
            sqlx::query_as("SELECT adapter_type, source_key FROM boards WHERE id = ?")
                .bind(id)
                .fetch_optional(tx.conn())
                .await?;
        let Some((adapter_type, source_key)) = existing else {
            return Ok(false);
        };

        sqlx::query("DELETE FROM boards WHERE id = ?").bind(id).execute(tx.conn()).await?;

        let nat_key = board_natural_key(adapter_type.as_str(), &source_key);
        SyncRepo::record_tombstone(&mut tx, ActivityEntityType::Board, &nat_key, now).await?;
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Board,
            id,
            ActivityAction::Delete,
            now,
        )
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn record_poll_batch(&self, board_id: &str, batch: &BoardPollBatch) -> Result<usize> {
        self.record_poll_batch_by_peer(board_id, batch, SERVER_PEER_ID).await
    }

    pub async fn record_poll_batch_by_peer(
        &self,
        board_id: &str,
        batch: &BoardPollBatch,
        polled_by: &str,
    ) -> Result<usize> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let exists: Option<i64> = sqlx::query_scalar("SELECT 1 FROM boards WHERE id = ? LIMIT 1")
            .bind(board_id)
            .fetch_optional(tx.conn())
            .await?;
        if exists.is_none() {
            return Ok(0);
        }

        sqlx::query("DELETE FROM board_items WHERE board_id = ?")
            .bind(board_id)
            .execute(tx.conn())
            .await?;

        let mut inserted_ids: Vec<String> = Vec::with_capacity(batch.items.len());
        let mut seen_ids: HashSet<&str> = HashSet::with_capacity(batch.items.len());
        let mut rank_counter: i64 = 0;

        for item in &batch.items {
            let ext_id = item.external_id.trim();
            if ext_id.is_empty() || !seen_ids.insert(ext_id) {
                continue;
            }
            rank_counter = rank_counter.saturating_add(1);
            let rank = if item.rank >= 1 { item.rank } else { rank_counter };
            let meta = if item.metadata_json.trim().is_empty() {
                "{}"
            } else {
                item.metadata_json.as_str()
            };
            sqlx::query(
                "INSERT INTO board_items
                 (board_id, external_id, rank, title, url, discussion_url, author, domain,
                  published_at, score, comment_count, summary, self_text_html, metadata_json, fetched_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(board_id)
            .bind(ext_id)
            .bind(rank)
            .bind(&item.title)
            .bind(&item.url)
            .bind(item.discussion_url.as_deref())
            .bind(item.author.as_deref())
            .bind(item.domain.as_deref())
            .bind(item.published_at)
            .bind(item.score)
            .bind(item.comment_count)
            .bind(item.summary.as_deref())
            .bind(item.self_text_html.as_deref())
            .bind(meta)
            .bind(now)
            .execute(tx.conn())
            .await?;
            inserted_ids.push(ext_id.to_owned());
        }

        Self::touch_snapshot_seen_states_in_tx(tx.conn(), board_id, &inserted_ids, now).await?;

        let snapshot_hlc = tx.next_hlc().await?;
        let clean_poller = polled_by.trim();
        let peer_str = if clean_poller.is_empty() { SERVER_PEER_ID } else { clean_poller };
        sqlx::query(
            "UPDATE boards
             SET last_polled_at = ?,
                 last_polled_by = ?,
                 last_error = NULL,
                 last_error_by = NULL,
                 consecutive_failures = 0,
                 snapshot_fetched_at = ?,
                 snapshot_hlc = ?,
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(now)
        .bind(peer_str)
        .bind(now)
        .bind(&snapshot_hlc)
        .bind(now)
        .bind(board_id)
        .execute(tx.conn())
        .await?;

        tx.commit().await?;
        Ok(inserted_ids.len())
    }

    pub(crate) async fn touch_snapshot_seen_states_in_tx(
        conn: &mut SqliteConnection,
        board_id: &str,
        external_ids: &[String],
        snapshot_ts: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        for chunk in external_ids.chunks(SQLITE_BIND_CHUNK) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "UPDATE board_item_states
                 SET last_seen_in_snapshot_at = ",
            );
            qb.push_bind(snapshot_ts)
                .push(" WHERE board_id = ")
                .push_bind(board_id)
                .push(" AND external_id IN (");
            let mut sep = qb.separated(", ");
            for ext_id in chunk {
                sep.push_bind(ext_id);
            }
            qb.push(
                ") AND strftime('%Y-%m-%dT%H:%M:%fZ', last_seen_in_snapshot_at) \
                 < strftime('%Y-%m-%dT%H:%M:%fZ', ",
            )
            .push_bind(snapshot_ts)
            .push(")");
            qb.build().execute(&mut *conn).await?;
        }
        Ok(())
    }

    pub async fn mark_error(&self, board_id: &str, error_msg: &str) -> Result<()> {
        self.mark_error_by_peer(board_id, error_msg, SERVER_PEER_ID).await
    }

    pub async fn mark_error_by_peer(
        &self,
        board_id: &str,
        error_msg: &str,
        peer_id: &str,
    ) -> Result<()> {
        let now = now_utc();
        let clean_peer = peer_id.trim();
        let peer_str = if clean_peer.is_empty() { SERVER_PEER_ID } else { clean_peer };
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        sqlx::query(
            "UPDATE boards
             SET last_polled_at = ?,
                 last_polled_by = ?,
                 last_error = ?,
                 last_error_by = ?,
                 consecutive_failures = MIN(consecutive_failures + 1, ?),
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(now)
        .bind(peer_str)
        .bind(error_msg)
        .bind(peer_str)
        .bind(MAX_CONSECUTIVE_FAILURES)
        .bind(now)
        .bind(board_id)
        .execute(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn get_item(&self, board_id: &str, external_id: &str) -> Result<Option<BoardItem>> {
        let row: Option<BoardItem> = sqlx::query_as(
            "SELECT board_id, external_id, rank, title, url, discussion_url, author, domain,
                    published_at, score, comment_count, summary, self_text_html, metadata_json,
                    fetched_at
             FROM board_items
             WHERE board_id = ? AND external_id = ?
             LIMIT 1",
        )
        .bind(board_id)
        .bind(external_id)
        .fetch_optional(self.pool)
        .await?;
        Ok(row)
    }

    pub async fn list_items_view(
        &self,
        board_id: &str,
        order_mode: BoardOrderMode,
        include_dismissed: bool,
        offset: usize,
        limit: usize,
    ) -> Result<BoardItemsSlice> {
        let safe_limit = limit.max(1);
        let mut tx = crate::db::begin_read(self.pool).await?;

        let count_sql = if include_dismissed {
            "SELECT COUNT(*) FROM board_items bi WHERE bi.board_id = ?"
        } else {
            "SELECT COUNT(*)
             FROM board_items bi
             LEFT JOIN board_item_states bis
               ON bis.board_id = bi.board_id AND bis.external_id = bi.external_id
             WHERE bi.board_id = ? AND bis.dismissed_at IS NULL"
        };
        let total_i64: i64 =
            sqlx::query_scalar(count_sql).bind(board_id).fetch_one(tx.conn()).await?;
        let total_buffered = usize::try_from(total_i64).unwrap_or(0);

        let order_clause = match order_mode {
            BoardOrderMode::Ranked => "ORDER BY bi.rank ASC, bi.external_id ASC",
            BoardOrderMode::Chronological => {
                "ORDER BY strftime('%Y-%m-%dT%H:%M:%fZ', COALESCE(bi.published_at, bi.fetched_at)) DESC, bi.rank ASC"
            }
        };
        let fetch_limit = i64::try_from(safe_limit.saturating_add(1)).unwrap_or(i64::MAX);
        let fetch_offset = i64::try_from(offset).unwrap_or(i64::MAX);
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT bi.board_id, bi.external_id, bi.rank, bi.title, bi.url, bi.discussion_url,
                    bi.author, bi.domain, bi.published_at, bi.score, bi.comment_count,
                    bi.summary, bi.self_text_html, bi.metadata_json, bi.fetched_at
             FROM board_items bi
             LEFT JOIN board_item_states bis
               ON bis.board_id = bi.board_id AND bis.external_id = bi.external_id
             WHERE bi.board_id = ",
        );
        qb.push_bind(board_id);
        if !include_dismissed {
            qb.push(" AND bis.dismissed_at IS NULL");
        }
        qb.push(" ")
            .push(order_clause)
            .push(" LIMIT ")
            .push_bind(fetch_limit)
            .push(" OFFSET ")
            .push_bind(fetch_offset);
        let mut raw_items: Vec<BoardItem> = qb.build_query_as().fetch_all(tx.conn()).await?;

        let has_more_buffered = raw_items.len() > safe_limit;
        raw_items.truncate(safe_limit);

        let views = Self::hydrate_item_views_in_conn(tx.conn(), board_id, raw_items).await?;
        tx.commit().await?;
        Ok(BoardItemsSlice { items: views, total_buffered, has_more_buffered })
    }

    /// Hydrates a list of [`BoardItem`]s (either from `board_items` or live-fetched pages) with
    /// `board_item_states` and permanent library dedup badges (`linked_entry_id`,
    /// `in_want_to_read`, `is_favorite`, `is_finished`).
    pub async fn hydrate_item_views(
        &self,
        board_id: &str,
        items: Vec<BoardItem>,
    ) -> Result<Vec<BoardItemView>> {
        let mut tx = crate::db::begin_read(self.pool).await?;
        let views = Self::hydrate_item_views_in_conn(tx.conn(), board_id, items).await?;
        tx.commit().await?;
        Ok(views)
    }

    async fn hydrate_item_views_in_conn(
        conn: &mut SqliteConnection,
        board_id: &str,
        items: Vec<BoardItem>,
    ) -> Result<Vec<BoardItemView>> {
        if items.is_empty() {
            return Ok(Vec::new());
        }

        let ext_ids: Vec<String> = items.iter().map(|i| i.external_id.clone()).collect();
        let states_map = Self::load_item_states_map_in_conn(conn, board_id, &ext_ids).await?;

        let mut url_keys: Vec<String> = Vec::with_capacity(items.len());
        let mut arxiv_keys: Vec<String> = Vec::new();
        let mut item_lookup_keys: Vec<(String, Option<String>, Option<String>)> =
            Vec::with_capacity(items.len());

        for item in &items {
            let canon_url = canonicalize_sync_url(&item.url);
            if !canon_url.is_empty() {
                url_keys.push(canon_url.clone());
            }
            let arxiv_exact = extract_arxiv_id(&item.url);
            let arxiv_base = arxiv_exact
                .as_deref()
                .map(strip_arxiv_version)
                .filter(|b| arxiv_exact.as_deref() != Some(*b))
                .map(str::to_owned);
            if let Some(ref aid) = arxiv_exact {
                arxiv_keys.push(aid.clone());
            }
            if let Some(ref base) = arxiv_base {
                arxiv_keys.push(base.clone());
            }
            item_lookup_keys.push((canon_url, arxiv_exact, arxiv_base));
        }

        let url_to_entry = Self::lookup_entry_ids_by_scheme_in_conn(conn, "url", &url_keys).await?;
        let arxiv_to_entry =
            Self::lookup_entry_ids_by_scheme_in_conn(conn, "arxiv", &arxiv_keys).await?;

        let mut resolved_entry_ids: Vec<Option<String>> = Vec::with_capacity(items.len());
        let mut unique_entry_ids: HashSet<String> = HashSet::new();
        for (canon_url, arxiv_exact, arxiv_base) in &item_lookup_keys {
            let raw_entry_id = arxiv_exact
                .as_deref()
                .and_then(|aid| arxiv_to_entry.get(aid))
                .or_else(|| arxiv_base.as_deref().and_then(|aid| arxiv_to_entry.get(aid)))
                .or_else(|| url_to_entry.get(canon_url))
                .cloned();
            let canonical_id = match raw_entry_id {
                Some(ref raw_id) => CatalogRepo::follow_merged_id_in_tx(&mut *conn, raw_id).await?,
                None => None,
            };
            if let Some(ref cid) = canonical_id {
                unique_entry_ids.insert(cid.clone());
            }
            resolved_entry_ids.push(canonical_id);
        }

        let entry_id_list: Vec<String> = unique_entry_ids.into_iter().collect();
        let flags_map = Self::load_entry_status_flags_in_conn(conn, &entry_id_list).await?;

        let mut out = Vec::with_capacity(items.len());
        for (item, linked_entry_id) in items.into_iter().zip(resolved_entry_ids) {
            let state = states_map.get(&item.external_id);
            let seen_at = state.and_then(|s| s.seen_at);
            let dismissed_at = state.and_then(|s| s.dismissed_at);
            let (in_want_to_read, is_favorite, is_finished) = linked_entry_id
                .as_deref()
                .and_then(|eid| flags_map.get(eid))
                .map_or((false, false, false), |f| {
                    (f.in_want_to_read, f.is_favorite, f.is_finished)
                });
            out.push(BoardItemView {
                board_id: item.board_id,
                external_id: item.external_id,
                rank: item.rank,
                title: item.title,
                url: item.url,
                discussion_url: item.discussion_url,
                author: item.author,
                domain: item.domain,
                published_at: item.published_at,
                score: item.score,
                comment_count: item.comment_count,
                summary: item.summary,
                self_text_html: item.self_text_html,
                fetched_at: item.fetched_at,
                is_seen: seen_at.is_some(),
                seen_at,
                is_dismissed: dismissed_at.is_some(),
                dismissed_at,
                linked_entry_id,
                in_want_to_read,
                is_favorite,
                is_finished,
            });
        }
        Ok(out)
    }

    async fn load_item_states_map_in_conn(
        conn: &mut SqliteConnection,
        board_id: &str,
        external_ids: &[String],
    ) -> Result<HashMap<String, BoardItemState>> {
        let mut out = HashMap::new();
        for chunk in external_ids.chunks(SQLITE_BIND_CHUNK) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT board_id, external_id, seen_at, dismissed_at,
                        last_seen_in_snapshot_at, updated_at, hlc
                 FROM board_item_states
                 WHERE board_id = ",
            );
            qb.push_bind(board_id).push(" AND external_id IN (");
            let mut sep = qb.separated(", ");
            for ext_id in chunk {
                sep.push_bind(ext_id);
            }
            qb.push(")");
            let rows: Vec<BoardItemState> = qb.build_query_as().fetch_all(&mut *conn).await?;
            for row in rows {
                out.insert(row.external_id.clone(), row);
            }
        }
        Ok(out)
    }

    async fn lookup_entry_ids_by_scheme_in_conn(
        conn: &mut SqliteConnection,
        scheme: &str,
        values: &[String],
    ) -> Result<HashMap<String, String>> {
        let mut unique: Vec<&str> = Vec::new();
        let mut seen: HashSet<&str> = HashSet::new();
        for v in values {
            if !v.is_empty() && seen.insert(v.as_str()) {
                unique.push(v.as_str());
            }
        }
        let mut out = HashMap::new();
        for chunk in unique.chunks(SQLITE_BIND_CHUNK) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT value, entry_id FROM entry_identifiers WHERE scheme = ",
            );
            qb.push_bind(scheme).push(" AND value IN (");
            let mut sep = qb.separated(", ");
            for val in chunk {
                sep.push_bind(*val);
            }
            qb.push(")");
            let rows: Vec<(String, String)> = qb.build_query_as().fetch_all(&mut *conn).await?;
            for (val, entry_id) in rows {
                out.insert(val, entry_id);
            }
        }
        Ok(out)
    }

    async fn load_entry_status_flags_in_conn(
        conn: &mut SqliteConnection,
        entry_ids: &[String],
    ) -> Result<HashMap<String, EntryStatusFlags>> {
        let mut out = HashMap::new();
        for chunk in entry_ids.chunks(SQLITE_BIND_CHUNK) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT e.id,
                        EXISTS (
                            SELECT 1 FROM collection_entries ce_wtr
                            WHERE ce_wtr.collection_id = ",
            );
            qb.push_bind(WANT_TO_READ_COLLECTION_ID)
                .push(
                    " AND ce_wtr.entry_id = e.id
                        ) AS in_want_to_read,
                        EXISTS (
                            SELECT 1 FROM collection_entries ce_fav
                            WHERE ce_fav.collection_id = ",
                )
                .push_bind(STARRED_COLLECTION_ID)
                .push(
                    " AND ce_fav.entry_id = e.id
                        ) AS is_favorite,
                        EXISTS (
                            SELECT 1 FROM consumption_log cl
                            WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                        ) AS is_finished
                 FROM entries e
                 WHERE e.merged_into_id IS NULL AND e.id IN (",
                );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            qb.push(")");
            let rows: Vec<EntryStatusFlags> = qb.build_query_as().fetch_all(&mut *conn).await?;
            for row in rows {
                out.insert(row.id.clone(), row);
            }
        }
        Ok(out)
    }

    async fn resolve_snapshot_ts_in_tx(
        conn: &mut SqliteConnection,
        board_id: &str,
        external_id: &str,
        fallback: DateTime<Utc>,
    ) -> std::result::Result<DateTime<Utc>, sqlx::Error> {
        let item_ts: Option<DateTime<Utc>> = sqlx::query_scalar(
            "SELECT fetched_at FROM board_items WHERE board_id = ? AND external_id = ? LIMIT 1",
        )
        .bind(board_id)
        .bind(external_id)
        .fetch_optional(&mut *conn)
        .await?;
        if let Some(ts) = item_ts {
            return Ok(ts);
        }
        let board_ts: Option<Option<DateTime<Utc>>> =
            sqlx::query_scalar("SELECT snapshot_fetched_at FROM boards WHERE id = ? LIMIT 1")
                .bind(board_id)
                .fetch_optional(&mut *conn)
                .await?;
        Ok(board_ts.flatten().unwrap_or(fallback))
    }

    pub async fn mark_seen(&self, board_id: &str, external_id: &str) -> Result<BoardItemState> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let snap_ts =
            Self::resolve_snapshot_ts_in_tx(tx.conn(), board_id, external_id, now).await?;
        let hlc = tx.next_hlc().await?;

        sqlx::query(
            "INSERT INTO board_item_states
             (board_id, external_id, seen_at, dismissed_at, last_seen_in_snapshot_at, updated_at, hlc)
             VALUES (?, ?, ?, NULL, ?, ?, ?)
             ON CONFLICT(board_id, external_id) DO UPDATE SET
                 seen_at = COALESCE(board_item_states.seen_at, excluded.seen_at),
                 last_seen_in_snapshot_at = CASE
                     WHEN strftime('%Y-%m-%dT%H:%M:%fZ', excluded.last_seen_in_snapshot_at)
                        > strftime('%Y-%m-%dT%H:%M:%fZ', board_item_states.last_seen_in_snapshot_at)
                     THEN excluded.last_seen_in_snapshot_at
                     ELSE board_item_states.last_seen_in_snapshot_at
                 END,
                 updated_at = CASE
                     WHEN board_item_states.seen_at IS NULL THEN excluded.updated_at
                     ELSE board_item_states.updated_at
                 END,
                 hlc = CASE
                     WHEN board_item_states.seen_at IS NULL THEN excluded.hlc
                     ELSE board_item_states.hlc
                 END",
        )
        .bind(board_id)
        .bind(external_id)
        .bind(now)
        .bind(snap_ts)
        .bind(now)
        .bind(&hlc)
        .execute(tx.conn())
        .await?;

        let state = Self::load_single_state_in_tx(tx.conn(), board_id, external_id).await?;
        tx.commit().await?;
        Ok(state)
    }

    pub async fn dismiss_item(&self, board_id: &str, external_id: &str) -> Result<BoardItemState> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let snap_ts =
            Self::resolve_snapshot_ts_in_tx(tx.conn(), board_id, external_id, now).await?;
        let hlc = tx.next_hlc().await?;

        sqlx::query(
            "INSERT INTO board_item_states
             (board_id, external_id, seen_at, dismissed_at, last_seen_in_snapshot_at, updated_at, hlc)
             VALUES (?, ?, NULL, ?, ?, ?, ?)
             ON CONFLICT(board_id, external_id) DO UPDATE SET
                 dismissed_at = excluded.dismissed_at,
                 last_seen_in_snapshot_at = CASE
                     WHEN strftime('%Y-%m-%dT%H:%M:%fZ', excluded.last_seen_in_snapshot_at)
                        > strftime('%Y-%m-%dT%H:%M:%fZ', board_item_states.last_seen_in_snapshot_at)
                     THEN excluded.last_seen_in_snapshot_at
                     ELSE board_item_states.last_seen_in_snapshot_at
                 END,
                 updated_at = excluded.updated_at,
                 hlc = excluded.hlc",
        )
        .bind(board_id)
        .bind(external_id)
        .bind(now)
        .bind(snap_ts)
        .bind(now)
        .bind(&hlc)
        .execute(tx.conn())
        .await?;

        let state = Self::load_single_state_in_tx(tx.conn(), board_id, external_id).await?;
        tx.commit().await?;
        Ok(state)
    }

    pub async fn undismiss_item(
        &self,
        board_id: &str,
        external_id: &str,
    ) -> Result<BoardItemState> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let snap_ts =
            Self::resolve_snapshot_ts_in_tx(tx.conn(), board_id, external_id, now).await?;
        let hlc = tx.next_hlc().await?;

        sqlx::query(
            "INSERT INTO board_item_states
             (board_id, external_id, seen_at, dismissed_at, last_seen_in_snapshot_at, updated_at, hlc)
             VALUES (?, ?, NULL, NULL, ?, ?, ?)
             ON CONFLICT(board_id, external_id) DO UPDATE SET
                 dismissed_at = NULL,
                 last_seen_in_snapshot_at = CASE
                     WHEN strftime('%Y-%m-%dT%H:%M:%fZ', excluded.last_seen_in_snapshot_at)
                        > strftime('%Y-%m-%dT%H:%M:%fZ', board_item_states.last_seen_in_snapshot_at)
                     THEN excluded.last_seen_in_snapshot_at
                     ELSE board_item_states.last_seen_in_snapshot_at
                 END,
                 updated_at = excluded.updated_at,
                 hlc = excluded.hlc",
        )
        .bind(board_id)
        .bind(external_id)
        .bind(snap_ts)
        .bind(now)
        .bind(&hlc)
        .execute(tx.conn())
        .await?;

        let state = Self::load_single_state_in_tx(tx.conn(), board_id, external_id).await?;
        tx.commit().await?;
        Ok(state)
    }

    async fn load_single_state_in_tx(
        conn: &mut SqliteConnection,
        board_id: &str,
        external_id: &str,
    ) -> std::result::Result<BoardItemState, sqlx::Error> {
        sqlx::query_as(
            "SELECT board_id, external_id, seen_at, dismissed_at,
                    last_seen_in_snapshot_at, updated_at, hlc
             FROM board_item_states
             WHERE board_id = ? AND external_id = ?",
        )
        .bind(board_id)
        .bind(external_id)
        .fetch_one(&mut *conn)
        .await
    }

    /// Deletes expired `board_item_states` rows that have fallen out of `board_items` and have not
    /// been seen in a snapshot or updated within `grace_hours` (default 48h). No tombstones are
    /// recorded.
    pub async fn prune_expired_item_states(&self, grace_hours: i64) -> Result<u64> {
        let cutoff = now_utc() - chrono::Duration::hours(grace_hours.max(1));
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let res = sqlx::query(
            "DELETE FROM board_item_states
             WHERE NOT EXISTS (
                 SELECT 1 FROM board_items bi
                 WHERE bi.board_id = board_item_states.board_id
                   AND bi.external_id = board_item_states.external_id
             )
             AND strftime('%Y-%m-%dT%H:%M:%fZ', last_seen_in_snapshot_at)
                 < strftime('%Y-%m-%dT%H:%M:%fZ', ?)
             AND strftime('%Y-%m-%dT%H:%M:%fZ', updated_at)
                 < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .bind(cutoff)
        .execute(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(res.rows_affected())
    }

    /// Atomically promotes a board item into the permanent `entries` graph (deduplicating via
    /// canonical URL / arXiv ID), attaching external identifiers and union-merging `discussions`
    /// metadata without ever putting new entries into Feed Inbox (`inbox_state = 'none'`), and
    /// applies the requested promotion action (`Queue`, `Star`, `Finish`, `Tag`, or `List`).
    #[allow(clippy::too_many_lines)]
    pub async fn promote_item_to_entry(
        &self,
        board: &Board,
        item: &BoardItem,
        content: &BoardPromoteContent,
        action: &BoardPromoteAction,
    ) -> CatalogResult<BoardPromoteOutcome> {
        let parsed_raw = Url::parse(item.url.trim())
            .map_err(|e| CatalogError::InvalidInput(format!("Invalid board item URL: {e}")))?;
        let canonical_url = canonical_arxiv_abstract_url(&parsed_raw).unwrap_or(parsed_raw);
        let url_str = canonical_url.as_str();
        let canon_url = canonicalize_sync_url(url_str);
        let arxiv_id = extract_arxiv_id(url_str);
        let content_type =
            if arxiv_id.is_some() { ContentType::Paper } else { classify_url(&canonical_url) };
        let entry_kind = EntryKind::from_content_type(content_type);
        let det_item_id = deterministic_item_id(None, url_str);
        let now = now_utc();

        let discussion_links = build_board_discussion_links(board, item);
        let effective_html = content
            .content_html
            .as_deref()
            .filter(|h| !h.trim().is_empty())
            .or_else(|| item.self_text_html.as_deref().filter(|h| !h.trim().is_empty()));
        let effective_summary = content
            .summary
            .as_deref()
            .filter(|s| !s.trim().is_empty())
            .or_else(|| item.summary.as_deref().filter(|s| !s.trim().is_empty()));
        let effective_thumb = content.thumbnail_url.as_deref().filter(|s| !s.trim().is_empty());
        let creators_vec: Vec<String> = content
            .author
            .as_deref()
            .or(item.author.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map_or_default(|a| vec![a.to_owned()]);
        let creators_json =
            serialize_creators_json(&creators_vec).unwrap_or_else(|| "[]".to_owned());

        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        SyncRepo::clear_tombstone(
            tx.conn(),
            ActivityEntityType::Item,
            &item_natural_key(None, url_str),
        )
        .await?;

        let existing_dedup =
            resolve_dedup_entry_id(tx.conn(), &det_item_id, &canon_url, arxiv_id.as_deref())
                .await?;

        let (entry_id, created_new, has_html_after) = if let Some(existing_id) = existing_dedup {
            let has_html = Self::enrich_existing_promoted_entry_in_tx(
                &mut tx,
                &existing_id,
                effective_summary,
                effective_thumb,
                effective_html,
                content_type,
                &discussion_links,
                now,
            )
            .await?;
            (existing_id, false, has_html)
        } else {
            Self::insert_new_promoted_entry_in_tx(
                &mut tx,
                &det_item_id,
                entry_kind,
                content_type,
                item,
                url_str,
                &creators_json,
                effective_summary,
                effective_thumb,
                effective_html,
                &discussion_links,
                now,
            )
            .await?;
            (det_item_id, true, effective_html.is_some())
        };

        entries_core::upsert_url_and_arxiv_identifiers_in_tx(
            tx.conn(),
            &entry_id,
            &canon_url,
            arxiv_id.as_deref(),
        )
        .await?;
        if board.adapter_type == BoardType::HackerNews
            && let Some(hn_id) = extract_hn_item_id(&item.external_id)
        {
            entries_core::upsert_entry_identifier_in_tx(
                tx.conn(),
                &entry_id,
                "hacker_news",
                &hn_id,
            )
            .await?;
        }

        Self::apply_promote_action_in_tx(&mut tx, &entry_id, action, now).await?;

        let mut qb = item_select_query_builder(ItemContentProjection::Full);
        qb.push(" WHERE i.id = ").push_bind(&entry_id);
        let projected_item: Item = qb.build_query_as().fetch_one(tx.conn()).await?;
        tx.commit().await?;

        let is_self_post = item.discussion_url.as_deref() == Some(item.url.as_str());
        let needs_html_enrichment =
            !has_html_after && !is_self_post && content_type == ContentType::Article;

        Ok(BoardPromoteOutcome {
            entry_id,
            created_new,
            content_type,
            needs_html_enrichment,
            item: projected_item,
        })
    }

    #[allow(clippy::too_many_arguments)]
    async fn insert_new_promoted_entry_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        entry_kind: EntryKind,
        content_type: ContentType,
        item: &BoardItem,
        url_str: &str,
        creators_json: &str,
        summary: Option<&str>,
        thumbnail_url: Option<&str>,
        content_html: Option<&str>,
        discussion_links: &[DiscussionLink],
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let meta_json = merge_discussions_into_metadata_json("{}", discussion_links);
        entries_core::insert_entry_row_in_tx(
            tx,
            &EntryInsertParams {
                creators_json,
                url: Some(url_str),
                published_at: item.published_at,
                summary,
                thumbnail_url,
                metadata_json: &meta_json,
                ..EntryInsertParams::minimal(entry_id, entry_kind, &item.title, now, now)
            },
        )
        .await?;
        entries_core::insert_entry_content_in_tx(tx.conn(), entry_id, content_html, content_type)
            .await?;
        entries_core::ensure_entry_state_in_tx(tx, entry_id, InboxState::None, now).await?;
        SyncRepo::record_activity(
            tx.conn(),
            ActivityEntityType::Entry,
            entry_id,
            ActivityAction::Create,
            now,
        )
        .await
    }

    #[allow(clippy::too_many_arguments)]
    async fn enrich_existing_promoted_entry_in_tx(
        tx: &mut WriteTx<'_>,
        existing_id: &str,
        summary: Option<&str>,
        thumbnail_url: Option<&str>,
        content_html: Option<&str>,
        content_type: ContentType,
        discussion_links: &[DiscussionLink],
        now: DateTime<Utc>,
    ) -> std::result::Result<bool, sqlx::Error> {
        let cur: ExistingPromoteEntry = sqlx::query_as(
            "SELECT e.summary, e.thumbnail_url, e.metadata_json,
                    (SELECT ec.content_html FROM entry_content ec WHERE ec.entry_id = e.id) AS existing_html
             FROM entries e
             WHERE e.id = ?",
        )
        .bind(existing_id)
        .fetch_one(tx.conn())
        .await?;

        let merged_meta =
            merge_discussions_into_metadata_json(&cur.metadata_json, discussion_links);
        let fill_summary =
            cur.summary.as_deref().is_none_or(|s| s.trim().is_empty()) && summary.is_some();
        let fill_thumb = cur.thumbnail_url.as_deref().is_none_or(|s| s.trim().is_empty())
            && thumbnail_url.is_some();
        let meta_changed = merged_meta != cur.metadata_json;

        if fill_summary || fill_thumb || meta_changed {
            let entry_hlc = tx.next_hlc().await?;
            sqlx::query(
                "UPDATE entries
                 SET summary = COALESCE(NULLIF(TRIM(summary), ''), ?),
                     thumbnail_url = COALESCE(NULLIF(TRIM(thumbnail_url), ''), ?),
                     metadata_json = ?,
                     updated_at = ?,
                     hlc = ?
                 WHERE id = ?",
            )
            .bind(summary)
            .bind(thumbnail_url)
            .bind(&merged_meta)
            .bind(now)
            .bind(&entry_hlc)
            .bind(existing_id)
            .execute(tx.conn())
            .await?;
        }

        let existing_has_html = cur.existing_html.as_deref().is_some_and(|h| !h.trim().is_empty());
        if let Some(html) = content_html {
            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                     content_html = COALESCE(NULLIF(TRIM(entry_content.content_html), ''), excluded.content_html)",
            )
            .bind(existing_id)
            .bind(html)
            .bind(content_type)
            .execute(tx.conn())
            .await?;
        } else {
            sqlx::query(
                "INSERT OR IGNORE INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, NULL, ?)",
            )
            .bind(existing_id)
            .bind(content_type)
            .execute(tx.conn())
            .await?;
        }

        entries_core::ensure_entry_state_in_tx(tx, existing_id, InboxState::None, now).await?;
        Ok(existing_has_html || content_html.is_some())
    }

    async fn apply_promote_action_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        action: &BoardPromoteAction,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        match action {
            BoardPromoteAction::Queue => {
                set_collection_flag_membership_in_tx(
                    tx,
                    WANT_TO_READ_COLLECTION_ID,
                    entry_id,
                    true,
                    now,
                )
                .await?;
            }
            BoardPromoteAction::Star => {
                set_favorite_membership_in_tx(tx, entry_id, true, now).await?;
            }
            BoardPromoteAction::Finish { notes } => {
                Self::apply_promote_finish_in_tx(tx, entry_id, notes.as_deref(), now).await?;
            }
            BoardPromoteAction::Tag { tag_id, tag_name } => {
                Self::apply_promote_tag_in_tx(
                    tx,
                    entry_id,
                    tag_id.as_deref(),
                    tag_name.as_deref(),
                    now,
                )
                .await?;
            }
            BoardPromoteAction::List { list_slug_or_id } => {
                CatalogRepo::add_entry_to_manual_collection_in_tx(
                    tx,
                    list_slug_or_id,
                    entry_id,
                    None,
                    now,
                )
                .await?;
            }
        }
        Ok(())
    }

    async fn apply_promote_finish_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        notes: Option<&str>,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        let cur_inbox: Option<InboxState> =
            sqlx::query_scalar("SELECT inbox_state FROM entry_state WHERE entry_id = ?")
                .bind(entry_id)
                .fetch_optional(tx.conn())
                .await?;
        let next_inbox = match cur_inbox.unwrap_or(InboxState::None) {
            InboxState::Unread => InboxState::Dismissed,
            other => other,
        };
        entries_core::upsert_entry_state_in_tx(
            tx,
            &EntryStateUpsert {
                entry_id,
                inbox_state: next_inbox,
                read_at: Some(now),
                progress: 1.0,
                interaction_updated_at: Some(now),
                updated_at: now,
            },
        )
        .await?;

        set_collection_flag_membership_in_tx(tx, WANT_TO_READ_COLLECTION_ID, entry_id, false, now)
            .await?;

        let has_notes = notes.is_some_and(|n| !n.trim().is_empty());
        let already_consumed = if has_notes {
            false
        } else {
            sqlx::query_scalar::<_, i64>(
                "SELECT 1 FROM consumption_log WHERE entry_id = ? AND status = 'consumed' LIMIT 1",
            )
            .bind(entry_id)
            .fetch_optional(tx.conn())
            .await?
            .is_some()
        };

        if !already_consumed {
            let _ = CatalogRepo::log_consumption_in_tx(
                tx,
                entry_id,
                ConsumptionStatus::Consumed,
                notes,
                now,
                now,
            )
            .await?;
        }
        Ok(())
    }

    async fn apply_promote_tag_in_tx(
        tx: &mut WriteTx<'_>,
        entry_id: &str,
        tag_id: Option<&str>,
        tag_name: Option<&str>,
        now: DateTime<Utc>,
    ) -> CatalogResult<()> {
        if let Some(tid) = tag_id.map(str::trim).filter(|s| !s.is_empty()) {
            let exists: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM collection_specs WHERE collection_id = ? AND collection_role = 'tag' LIMIT 1",
            )
            .bind(tid)
            .fetch_optional(tx.conn())
            .await?;
            if exists.is_none() {
                return Err(CatalogError::NotFound(format!("Tag '{tid}'")));
            }
            TagRepo::attach_in_tx(tx, entry_id, tid, now).await?;
            Ok(())
        } else if let Some(name) = tag_name.map(str::trim).filter(|s| !s.is_empty()) {
            let _ = TagRepo::get_or_create_and_attach_in_tx(tx, entry_id, name, now).await?;
            Ok(())
        } else {
            Err(CatalogError::InvalidInput(
                "Either tag_id or tag_name must be provided when promoting with action 'tag'"
                    .to_owned(),
            ))
        }
    }
}

fn build_board_discussion_links(board: &Board, item: &BoardItem) -> Vec<DiscussionLink> {
    let mut links = Vec::new();
    if let Some(disc_url) = item.discussion_url.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        links.push(DiscussionLink {
            source: board.adapter_type.as_str().to_owned(),
            url: disc_url.to_owned(),
            external_id: Some(item.external_id.clone()),
            label: Some(board.adapter_type.display_label().to_owned()),
        });
    } else if board.adapter_type == BoardType::HackerNews
        && let Some(link) = DiscussionLink::hacker_news(&item.external_id)
    {
        links.push(link);
    }
    links
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::too_many_lines)]
mod tests {
    use chrono::TimeZone;
    use roche_core::boards::{BOARD_STATE_GRACE_HOURS, HackerNewsSection};

    use super::*;
    use crate::db::{CollectionRole, Db, NewItem};

    fn sample_candidate(
        external_id: &str,
        rank: i64,
        title: &str,
        url: &str,
        published_ts: i64,
    ) -> BoardPollCandidate {
        BoardPollCandidate {
            external_id: external_id.to_owned(),
            rank,
            title: title.to_owned(),
            url: url.to_owned(),
            discussion_url: Some(format!("https://news.ycombinator.com/item?id={external_id}")),
            author: Some("pg".to_owned()),
            domain: Some("example.com".to_owned()),
            published_at: Some(Utc.timestamp_opt(published_ts, 0).unwrap()),
            score: Some(120),
            comment_count: Some(45),
            summary: None,
            self_text_html: None,
            metadata_json: "{}".to_owned(),
        }
    }

    #[tokio::test]
    async fn test_board_crud_archive_reactivate_and_delete_tombstone() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Top);

        let board = db.boards().create(&spec, None, None, 30).await.unwrap();
        assert_eq!(board.id, deterministic_board_id("hacker_news", "top"));
        assert_eq!(board.title, "Hacker News");
        assert!(board.is_active);
        assert_eq!(db.boards().count().await.unwrap(), 1);

        // Duplicate active creation conflicts
        let dup_err = db.boards().create(&spec, None, None, 30).await.unwrap_err();
        assert!(dup_err.downcast_ref::<crate::db::ConflictError>().is_some());

        // Update + archive + reactivate via create
        assert!(
            db.boards()
                .update(
                    &board.id,
                    Some("HN Front"),
                    Some(BoardOrderMode::Chronological),
                    Some(15),
                    None
                )
                .await
                .unwrap()
        );
        let updated = db.boards().get(&board.id).await.unwrap().unwrap();
        assert_eq!(updated.title, "HN Front");
        assert_eq!(updated.order_mode, BoardOrderMode::Chronological);
        assert_eq!(updated.poll_interval, 15);

        assert!(db.boards().archive(&board.id).await.unwrap());
        assert!(!db.boards().get(&board.id).await.unwrap().unwrap().is_active);

        let reactivated = db.boards().create(&spec, Some("HN Restored"), None, 20).await.unwrap();
        assert_eq!(reactivated.id, board.id);
        assert!(reactivated.is_active);
        assert_eq!(reactivated.title, "HN Restored");

        // Delete records `'board'` tombstone with natural_key
        assert!(db.boards().delete(&board.id).await.unwrap());
        assert!(db.boards().get(&board.id).await.unwrap().is_none());
        let tomb_exists: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM sync_tombstones WHERE entity_type = 'board' AND natural_key = ?",
        )
        .bind(board_natural_key("hacker_news", "top"))
        .fetch_optional(db.pool())
        .await
        .unwrap();
        assert!(tomb_exists.is_some());
    }

    #[tokio::test]
    async fn test_snapshot_replace_isolation_dismiss_backfill_and_48h_pruning() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Top);
        let board = db.boards().create(&spec, None, None, 30).await.unwrap();

        let batch1 = BoardPollBatch {
            items: vec![
                sample_candidate("101", 1, "Story One", "https://example.com/1", 1_700_000_100),
                sample_candidate("102", 2, "Story Two", "https://example.com/2", 1_700_000_300),
                sample_candidate("103", 3, "Story Three", "https://example.com/3", 1_700_000_200),
            ],
        };
        let inserted = db.boards().record_poll_batch(&board.id, &batch1).await.unwrap();
        assert_eq!(inserted, 3);

        // Strict isolation: board items NEVER pollute entries, entries_fts, or unread_items_count
        let entry_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM entries WHERE url LIKE 'https://example.com/%'",
        )
        .fetch_one(db.pool())
        .await
        .unwrap();
        let fts_hits: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM entries_fts WHERE entries_fts MATCH 'Story'")
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(entry_count, 0);
        assert_eq!(fts_hits, 0);
        assert_eq!(db.items().count_unread().await.unwrap(), 0);

        // Ranked vs Chronological ordering
        let ranked = db
            .boards()
            .list_items_view(&board.id, BoardOrderMode::Ranked, false, 0, 10)
            .await
            .unwrap();
        assert_eq!(
            ranked.items.iter().map(|i| i.external_id.as_str()).collect::<Vec<_>>(),
            vec!["101", "102", "103"]
        );
        let chrono = db
            .boards()
            .list_items_view(&board.id, BoardOrderMode::Chronological, false, 0, 10)
            .await
            .unwrap();
        assert_eq!(
            chrono.items.iter().map(|i| i.external_id.as_str()).collect::<Vec<_>>(),
            vec!["102", "103", "101"]
        );

        // Mark seen + dismiss + undismiss
        let seen = db.boards().mark_seen(&board.id, "101").await.unwrap();
        assert!(seen.seen_at.is_some());
        let dismissed = db.boards().dismiss_item(&board.id, "102").await.unwrap();
        assert!(dismissed.dismissed_at.is_some());
        let visible = db
            .boards()
            .list_items_view(&board.id, BoardOrderMode::Ranked, false, 0, 10)
            .await
            .unwrap();
        assert_eq!(
            visible.items.iter().map(|i| i.external_id.as_str()).collect::<Vec<_>>(),
            vec!["101", "103"]
        );
        let undismissed = db.boards().undismiss_item(&board.id, "102").await.unwrap();
        assert!(undismissed.dismissed_at.is_none());

        // Atomic snapshot replace removes "103", keeps "101" and adds "104"
        let _ = db.boards().dismiss_item(&board.id, "103").await.unwrap();
        let batch2 = BoardPollBatch {
            items: vec![
                sample_candidate("101", 1, "Story One", "https://example.com/1", 1_700_000_100),
                sample_candidate("104", 2, "Story Four", "https://example.com/4", 1_700_000_400),
            ],
        };
        db.boards().record_poll_batch(&board.id, &batch2).await.unwrap();
        let after_replace = db
            .boards()
            .list_items_view(&board.id, BoardOrderMode::Ranked, true, 0, 10)
            .await
            .unwrap();
        assert_eq!(
            after_replace.items.iter().map(|i| i.external_id.as_str()).collect::<Vec<_>>(),
            vec!["101", "104"]
        );

        // Age "103" (absent from snapshot) and "101" (still in snapshot) beyond 48h
        let old_ts = Utc::now() - chrono::Duration::hours(BOARD_STATE_GRACE_HOURS + 5);
        sqlx::query(
            "UPDATE board_item_states SET last_seen_in_snapshot_at = ?, updated_at = ?
             WHERE board_id = ? AND external_id IN ('101', '103')",
        )
        .bind(old_ts)
        .bind(old_ts)
        .bind(&board.id)
        .execute(db.pool())
        .await
        .unwrap();

        let pruned = db.boards().prune_expired_item_states(BOARD_STATE_GRACE_HOURS).await.unwrap();
        assert_eq!(pruned, 1, "only absent >48h state '103' should be pruned");

        let remaining_ids: Vec<String> = sqlx::query_scalar(
            "SELECT external_id FROM board_item_states WHERE board_id = ? ORDER BY external_id ASC",
        )
        .bind(&board.id)
        .fetch_all(db.pool())
        .await
        .unwrap();
        assert_eq!(remaining_ids, vec!["101", "102"]);
        let state_tombs: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sync_tombstones WHERE natural_key LIKE '%103%'",
        )
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(state_tombs, 0, "pruning board_item_states must never record tombstones");
    }

    #[tokio::test]
    async fn test_dedup_and_promotion_all_actions_and_discussions_projection() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Top);
        let board = db.boards().create(&spec, None, None, 30).await.unwrap();

        // Pre-seed an existing arXiv paper entry so canonical arXiv dedup triggers
        let existing_paper_url = Url::parse("https://arxiv.org/abs/2301.07041").unwrap();
        let existing_paper = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Existing Paper",
                url: &existing_paper_url,
                author: Some("Researcher"),
                published_at: None,
                summary: Some("Paper abstract"),
                content_type: ContentType::Paper,
            })
            .await
            .unwrap();
        sqlx::query("INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id) VALUES ('arxiv', '2301.07041', ?)")
            .bind(&existing_paper.id)
            .execute(db.pool())
            .await
            .unwrap();

        let batch = BoardPollBatch {
            items: vec![
                sample_candidate(
                    "201",
                    1,
                    "HN Paper Link",
                    "https://arxiv.org/abs/2301.07041",
                    1_700_000_100,
                ),
                sample_candidate(
                    "202",
                    2,
                    "Fresh Article",
                    "https://example.com/fresh",
                    1_700_000_200,
                ),
                sample_candidate(
                    "203",
                    3,
                    "Star Article",
                    "https://example.com/star",
                    1_700_000_300,
                ),
                sample_candidate("204", 4, "Tag Article", "https://example.com/tag", 1_700_000_400),
                sample_candidate(
                    "205",
                    5,
                    "List Article",
                    "https://example.com/list",
                    1_700_000_500,
                ),
            ],
        };
        db.boards().record_poll_batch(&board.id, &batch).await.unwrap();

        // Board list view shows canonical dedup link to existing paper immediately
        let views = db
            .boards()
            .list_items_view(&board.id, BoardOrderMode::Ranked, false, 0, 10)
            .await
            .unwrap();
        assert_eq!(views.items[0].linked_entry_id.as_deref(), Some(existing_paper.id.as_str()));

        // 1. Promote existing paper via Queue -> reuses existing canonical entry, attaches HN identifier & discussions!
        let item_201 = db.boards().get_item(&board.id, "201").await.unwrap().unwrap();
        let promo_paper = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_201,
                &BoardPromoteContent::default(),
                &BoardPromoteAction::Queue,
            )
            .await
            .unwrap();
        assert!(!promo_paper.created_new);
        assert_eq!(promo_paper.entry_id, existing_paper.id);
        assert!(promo_paper.item.in_want_to_read);
        assert_eq!(promo_paper.item.discussions.len(), 1);
        assert_eq!(promo_paper.item.discussions[0].source, "hacker_news");
        assert_eq!(promo_paper.item.discussions[0].url, "https://news.ycombinator.com/item?id=201");

        // 2. Promote fresh item via Queue -> inbox_state stays 'none' (NEVER Feed Inbox!)
        let item_202 = db.boards().get_item(&board.id, "202").await.unwrap().unwrap();
        let promo_queue = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_202,
                &BoardPromoteContent {
                    content_html: Some("<p>Cached reader HTML</p>".to_owned()),
                    summary: Some("Cached summary".to_owned()),
                    thumbnail_url: None,
                    author: Some("Author A".to_owned()),
                },
                &BoardPromoteAction::Queue,
            )
            .await
            .unwrap();
        assert!(promo_queue.created_new);
        assert!(!promo_queue.needs_html_enrichment);
        assert_eq!(promo_queue.item.inbox_state, InboxState::None);
        assert!(promo_queue.item.in_want_to_read);
        assert_eq!(promo_queue.item.content_html.as_deref(), Some("<p>Cached reader HTML</p>"));
        assert_eq!(promo_queue.item.discussions.len(), 1);

        // 3. Promote same item via Finish -> logs 'consumed', progress=1.0, removes from Queue, idempotent on repeat
        let promo_finish = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_202,
                &BoardPromoteContent::default(),
                &BoardPromoteAction::Finish { notes: None },
            )
            .await
            .unwrap();
        assert!(!promo_finish.created_new);
        assert!(promo_finish.item.is_finished);
        assert!(!promo_finish.item.in_want_to_read);
        assert!((promo_finish.item.read_progress - 1.0).abs() < f64::EPSILON);

        let _ = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_202,
                &BoardPromoteContent::default(),
                &BoardPromoteAction::Finish { notes: None },
            )
            .await
            .unwrap();
        let consumed_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM consumption_log WHERE entry_id = ? AND status = 'consumed'",
        )
        .bind(&promo_finish.entry_id)
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(consumed_count, 1, "repeated auto-finish without notes must be idempotent");

        // 4. Promote via Star
        let item_203 = db.boards().get_item(&board.id, "203").await.unwrap().unwrap();
        let promo_star = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_203,
                &BoardPromoteContent::default(),
                &BoardPromoteAction::Star,
            )
            .await
            .unwrap();
        assert!(promo_star.item.is_favorite);
        assert_eq!(promo_star.item.inbox_state, InboxState::None);

        // 5. Promote via Tag
        let item_204 = db.boards().get_item(&board.id, "204").await.unwrap().unwrap();
        let promo_tag = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_204,
                &BoardPromoteContent::default(),
                &BoardPromoteAction::Tag { tag_id: None, tag_name: Some("systems".to_owned()) },
            )
            .await
            .unwrap();
        assert_eq!(promo_tag.item.tag_names.0, vec!["systems"]);

        // 6. Promote via List
        let custom_list = db
            .catalog()
            .create_manual_collection("Weekend Reads", None, CollectionRole::List, None, None)
            .await
            .unwrap();
        let item_205 = db.boards().get_item(&board.id, "205").await.unwrap().unwrap();
        let promo_list = db
            .boards()
            .promote_item_to_entry(
                &board,
                &item_205,
                &BoardPromoteContent::default(),
                &BoardPromoteAction::List { list_slug_or_id: custom_list.id.clone() },
            )
            .await
            .unwrap();
        let in_list: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
        )
        .bind(&custom_list.id)
        .bind(&promo_list.entry_id)
        .fetch_optional(db.pool())
        .await
        .unwrap();
        assert!(in_list.is_some());

        // Board list now reflects inline status badges for all promoted items!
        let views_after = db
            .boards()
            .list_items_view(&board.id, BoardOrderMode::Ranked, false, 0, 10)
            .await
            .unwrap();
        assert!(views_after.items[0].in_want_to_read);
        assert!(views_after.items[1].is_finished);
        assert!(views_after.items[2].is_favorite);
    }
}
