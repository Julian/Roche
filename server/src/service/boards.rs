use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use chrono::{DateTime, Utc};
use roche_core::{
    boards::{BOARD_DEFAULT_PAGE_SIZE, BoardOrderMode, BoardSourceSpec, BoardType},
    classify::classify_url,
    identity::canonicalize_sync_url,
    media_urls::{extract_arxiv_id, is_valid_arxiv_id},
    url_guard::{SsrfBlockedError, parse_public_url, validate_public_url},
};
use serde::Serialize;
use url::Url;

use super::{ActiveRefreshGuard, AppError, RocheService};
use crate::{
    content::{HtmlSanitizer, MAX_SUMMARY_CHARS},
    db::{
        Board, BoardItem, BoardItemState, BoardItemView, BoardPromoteAction, BoardPromoteContent,
        Item,
    },
    domain::ContentType,
    feeds::ArxivParser,
    ingest::{BoardIngester, FeedFetcher, FeedIngester, PollError, board_poll_lock_key},
    util::clock::now_utc,
};

pub const BOARD_READER_CACHE_CAPACITY: usize = 64;
pub const BOARD_READER_CACHE_TTL: Duration = Duration::from_mins(30);

#[derive(Debug, Clone)]
pub struct CachedBoardReaderSnapshot {
    pub author: Option<String>,
    pub summary: Option<String>,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
}

#[derive(Debug)]
struct CacheEntry {
    snapshot: CachedBoardReaderSnapshot,
    inserted_at: Instant,
    last_accessed: u64,
}

#[derive(Debug, Default)]
struct ReaderCacheInner {
    clock: u64,
    entries: HashMap<String, CacheEntry>,
}

/// Bounded in-memory LRU + TTL cache (64 entries / 30 min) for lazily extracted board reader HTML
/// so opening or promoting a board article reuses extracted HTML without writing ephemeral items
/// into `entries`.
#[derive(Debug, Clone, Default)]
pub struct BoardReaderCache {
    inner: Arc<Mutex<ReaderCacheInner>>,
}

impl BoardReaderCache {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    #[must_use]
    pub fn get(&self, canonical_url: &str) -> Option<CachedBoardReaderSnapshot> {
        let now = Instant::now();
        let mut guard = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let expired = guard
            .entries
            .get(canonical_url)
            .is_some_and(|e| now.duration_since(e.inserted_at) >= BOARD_READER_CACHE_TTL);
        if expired {
            guard.entries.remove(canonical_url);
            return None;
        }
        guard.clock = guard.clock.saturating_add(1);
        let tick = guard.clock;
        let snapshot = guard.entries.get_mut(canonical_url).map(|entry| {
            entry.last_accessed = tick;
            entry.snapshot.clone()
        });
        drop(guard);
        snapshot
    }

    pub fn insert(&self, canonical_url: String, snapshot: CachedBoardReaderSnapshot) {
        let now = Instant::now();
        let mut guard = self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.entries.retain(|_, e| now.duration_since(e.inserted_at) < BOARD_READER_CACHE_TTL);
        if guard.entries.len() >= BOARD_READER_CACHE_CAPACITY
            && !guard.entries.contains_key(&canonical_url)
            && let Some(lru_key) =
                guard.entries.iter().min_by_key(|(_, e)| e.last_accessed).map(|(k, _)| k.clone())
        {
            guard.entries.remove(&lru_key);
        }
        guard.clock = guard.clock.saturating_add(1);
        let tick = guard.clock;
        guard
            .entries
            .insert(canonical_url, CacheEntry { snapshot, inserted_at: now, last_accessed: tick });
    }

    #[cfg(test)]
    #[must_use]
    pub(crate) fn len(&self) -> usize {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner).entries.len()
    }
}

#[derive(Debug, Default, Clone)]
pub struct CreateBoardInput {
    pub adapter_type: Option<BoardType>,
    pub url: Option<String>,
    pub section: Option<String>,
    pub source_config: Option<serde_json::Value>,
    pub title: Option<String>,
    pub order_mode: Option<BoardOrderMode>,
    pub poll_interval: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BoardItemsPage {
    pub board: Board,
    pub items: Vec<BoardItemView>,
    pub page: usize,
    pub per_page: usize,
    pub total_buffered: usize,
    pub has_more_buffered: bool,
    pub has_more_live: bool,
    pub is_live_page: bool,
    pub next_live_offset: Option<usize>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum BoardReaderFallbackReason {
    SelfPostEmpty,
    Pdf,
    Video,
    Paper,
    FetchFailed,
    Unextractable,
}

#[derive(Debug, Clone, Serialize)]
pub struct BoardReaderView {
    pub board: Board,
    pub item: BoardItemView,
    pub content_html: Option<String>,
    pub extracted_summary: Option<String>,
    pub thumbnail_url: Option<String>,
    pub is_self_post: bool,
    pub from_cache: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fallback_reason: Option<BoardReaderFallbackReason>,
    pub linked_entry: Option<Item>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BoardDismissOutcome {
    pub board_id: String,
    pub external_id: String,
    pub dismissed_at: DateTime<Utc>,
    pub replacement_item: Option<BoardItemView>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BoardPromoteResult {
    pub entry_id: String,
    pub created_new: bool,
    pub item: Item,
    pub board_item: Option<BoardItemView>,
}

pub struct BoardService<'a> {
    pub(super) service: &'a RocheService,
}

impl BoardService<'_> {
    pub async fn list(&self) -> Result<Vec<Board>, AppError> {
        Ok(self.service.db.boards().list().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Board, AppError> {
        self.service
            .db
            .boards()
            .get(id)
            .await?
            .ok_or_else(|| AppError::not_found("Board not found"))
    }

    pub async fn create(&self, input: CreateBoardInput) -> Result<Board, AppError> {
        let spec = BoardSourceSpec::resolve(
            input.adapter_type,
            input.url.as_deref(),
            input.section.as_deref(),
            input.source_config.as_ref(),
        )
        .map_err(|err| {
            if err.downcast_ref::<SsrfBlockedError>().is_some() {
                AppError::ssrf_blocked(format!("Invalid board URL: {err}"))
            } else {
                AppError::bad_request(format!("Invalid board configuration: {err}"))
            }
        })?;

        let poll_interval = match input.poll_interval {
            Some(interval) => {
                self.validate_poll_interval(interval)?;
                interval
            }
            None => self.service.config.feeds.default_poll_interval,
        };

        let board = self
            .service
            .db
            .boards()
            .create(&spec, input.title.as_deref(), input.order_mode, poll_interval)
            .await?;

        // Auto-poll newly created board immediately (bounded timeout; upstream errors are recorded
        // on the board rather than failing creation).
        let _refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
        let lock_key = board_poll_lock_key(&board.id);
        let poll_guard = self.service.feed_poll_locks.acquire(&lock_key).await;
        if let Ok(_permit) = self.service.poll_semaphore.acquire().await {
            let _ = tokio::time::timeout(
                Duration::from_secs(10),
                BoardIngester::poll_and_record(
                    &self.service.db,
                    &self.service.fetcher,
                    &board,
                    "create_auto_poll",
                    true,
                ),
            )
            .await;
        }
        drop(poll_guard);

        self.get(&board.id).await
    }

    pub async fn update(
        &self,
        id: &str,
        title: Option<&str>,
        order_mode: Option<BoardOrderMode>,
        poll_interval: Option<i64>,
        is_active: Option<bool>,
    ) -> Result<Board, AppError> {
        let clean_title = match title {
            Some(raw) => {
                let trimmed = raw.trim();
                if trimmed.is_empty() {
                    return Err(AppError::bad_request("Board title cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };
        if let Some(interval) = poll_interval {
            self.validate_poll_interval(interval)?;
        }

        if !self
            .service
            .db
            .boards()
            .update(id, clean_title, order_mode, poll_interval, is_active)
            .await?
        {
            return Err(AppError::not_found("Board not found"));
        }
        self.get(id).await
    }

    pub async fn archive(&self, id: &str) -> Result<Board, AppError> {
        if !self.service.db.boards().archive(id).await? {
            return Err(AppError::not_found("Board not found"));
        }
        self.get(id).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<Board, AppError> {
        if !self.service.db.boards().unarchive(id).await? {
            return Err(AppError::not_found("Board not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), AppError> {
        if !self.service.db.boards().delete(id).await? {
            return Err(AppError::not_found("Board not found"));
        }
        Ok(())
    }

    pub async fn refresh(&self, id: &str) -> Result<Board, AppError> {
        let _refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
        let lock_key = board_poll_lock_key(id);
        let poll_guard = self.service.feed_poll_locks.acquire(&lock_key).await;
        let board = self.get(id).await?;

        let poll_res = BoardIngester::poll_and_record(
            &self.service.db,
            &self.service.fetcher,
            &board,
            "manual_refresh",
            true,
        )
        .await;
        drop(poll_guard);

        match poll_res {
            Ok(_) => self.get(id).await,
            Err(PollError::Upstream(upstream)) => {
                let safe_msg = crate::util::url_guard::sanitize_upstream_error(&upstream);
                Err(AppError::bad_gateway(format!("Failed to refresh board: {safe_msg}")))
            }
            Err(PollError::Database(db_err)) => Err(AppError::Database(db_err)),
        }
    }

    pub async fn refresh_all_in_background(&self) -> Result<usize, AppError> {
        let active_boards: Vec<Board> =
            self.list().await?.into_iter().filter(|b| b.is_active).collect();
        let count = active_boards.len();
        if !active_boards.is_empty() {
            let service_bg = self.service.clone();
            self.service.spawn_background(async move {
                service_bg.boards().poll_boards_concurrently(active_boards).await;
            });
        }
        Ok(count)
    }

    pub async fn poll_boards_concurrently(&self, boards: Vec<Board>) {
        if boards.is_empty() {
            return;
        }
        let poll_sem = Arc::clone(&self.service.poll_semaphore);
        let mut join_set = tokio::task::JoinSet::new();

        for board in boards {
            let lock_key = board_poll_lock_key(&board.id);
            let Some(poll_guard) = self.service.feed_poll_locks.try_acquire(&lock_key) else {
                continue;
            };
            let service_clone = self.service.clone();
            let p_sem = Arc::clone(&poll_sem);
            let refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
            join_set.spawn(async move {
                let _task_guard = refresh_guard;
                let Ok(_permit) = p_sem.acquire_owned().await else {
                    drop(poll_guard);
                    return;
                };
                let _ = BoardIngester::poll_and_record(
                    &service_clone.db,
                    &service_clone.fetcher,
                    &board,
                    "background_refresh",
                    true,
                )
                .await;
                drop(poll_guard);
            });
        }
        while join_set.join_next().await.is_some() {}
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn list_items(
        &self,
        board_id: &str,
        page: Option<usize>,
        per_page: Option<usize>,
        include_dismissed: bool,
        live_more: bool,
        live_offset: Option<usize>,
    ) -> Result<BoardItemsPage, AppError> {
        let board = self.get(board_id).await?;
        let safe_per_page = per_page.unwrap_or(BOARD_DEFAULT_PAGE_SIZE).clamp(1, 90);
        let safe_page = page.unwrap_or(1).max(1);
        let offset = safe_page.saturating_sub(1).saturating_mul(safe_per_page);

        if live_more {
            let buffered_count = usize::try_from(board.item_count.max(0)).unwrap_or(0);
            let upstream_offset = live_offset.unwrap_or_else(|| offset.max(buffered_count));
            let live_fetch = BoardIngester::fetch_live_page(
                &self.service.fetcher,
                &board,
                upstream_offset,
                safe_per_page,
            )
            .await
            .map_err(|e| {
                let safe_msg = crate::util::url_guard::sanitize_upstream_error(&e);
                AppError::bad_gateway(format!("Failed to load more board items: {safe_msg}"))
            })?;

            let fetched_at = now_utc();
            let raw_items: Vec<BoardItem> = live_fetch
                .items
                .into_iter()
                .map(|c| c.into_board_item(&board.id, fetched_at))
                .collect();
            let mut hydrated =
                self.service.db.boards().hydrate_item_views(&board.id, raw_items).await?;
            if !include_dismissed {
                hydrated.retain(|v| !v.is_dismissed);
            }
            let next_live_offset = live_fetch.next_offset;

            return Ok(BoardItemsPage {
                board,
                items: hydrated,
                page: safe_page,
                per_page: safe_per_page,
                total_buffered: buffered_count,
                has_more_buffered: false,
                has_more_live: live_fetch.has_more,
                is_live_page: true,
                next_live_offset,
            });
        }

        let slice = self
            .service
            .db
            .boards()
            .list_items_view(&board.id, board.order_mode, include_dismissed, offset, safe_per_page)
            .await?;

        let buffered_raw_count = usize::try_from(board.item_count.max(0)).unwrap_or(0);
        let has_more_live = !slice.has_more_buffered && buffered_raw_count > 0;
        let next_live_offset = has_more_live.then_some(buffered_raw_count);

        Ok(BoardItemsPage {
            board,
            items: slice.items,
            page: safe_page,
            per_page: safe_per_page,
            total_buffered: slice.total_buffered,
            has_more_buffered: slice.has_more_buffered,
            has_more_live,
            is_live_page: false,
            next_live_offset,
        })
    }

    pub async fn open_reader(
        &self,
        board_id: &str,
        external_id: &str,
    ) -> Result<BoardReaderView, AppError> {
        let board = self.get(board_id).await?;
        let raw_item = self.resolve_board_item(&board, external_id).await?;

        let _ = self.service.db.boards().mark_seen(&board.id, &raw_item.external_id).await?;

        let mut hydrated =
            self.service.db.boards().hydrate_item_views(&board.id, vec![raw_item.clone()]).await?;
        let item_view = hydrated
            .pop()
            .ok_or_else(|| AppError::not_found("Board item could not be hydrated"))?;

        let linked_entry = match item_view.linked_entry_id.as_deref() {
            Some(eid) => self.service.db.items().get(eid).await?,
            None => None,
        };

        let is_self_post = raw_item.discussion_url.as_deref() == Some(raw_item.url.as_str());
        if is_self_post {
            let content_html = raw_item.self_text_html.filter(|h| !h.trim().is_empty());
            let fallback_reason =
                content_html.is_none().then_some(BoardReaderFallbackReason::SelfPostEmpty);
            return Ok(BoardReaderView {
                board,
                content_html,
                extracted_summary: raw_item.summary,
                thumbnail_url: None,
                item: item_view,
                is_self_post: true,
                from_cache: true,
                fallback_reason,
                linked_entry,
            });
        }

        if let Some(ref entry) = linked_entry
            && let Some(ref existing_html) = entry.content_html
            && !existing_html.trim().is_empty()
        {
            return Ok(BoardReaderView {
                board,
                content_html: Some(existing_html.clone()),
                extracted_summary: entry.summary.clone().or_else(|| raw_item.summary.clone()),
                thumbnail_url: entry.thumbnail_url.clone(),
                item: item_view,
                is_self_post: false,
                from_cache: true,
                fallback_reason: None,
                linked_entry,
            });
        }

        let canon_url = canonicalize_sync_url(&raw_item.url);
        if let Some(cached) = self.service.board_reader_cache.get(&canon_url)
            && cached.content_html.as_deref().is_some_and(|h| !h.trim().is_empty())
        {
            return Ok(BoardReaderView {
                board,
                content_html: cached.content_html,
                extracted_summary: cached.summary.or_else(|| raw_item.summary.clone()),
                thumbnail_url: cached.thumbnail_url,
                item: item_view,
                is_self_post: false,
                from_cache: true,
                fallback_reason: None,
                linked_entry,
            });
        }

        match self.fetch_and_extract_reader_snapshot(&raw_item.url).await {
            Ok(snap) => {
                if snap.content_html.as_deref().is_some_and(|h| !h.trim().is_empty()) {
                    self.service.board_reader_cache.insert(canon_url, snap.clone());
                }
                Ok(BoardReaderView {
                    board,
                    item: item_view,
                    content_html: snap.content_html,
                    extracted_summary: snap.summary.or_else(|| raw_item.summary.clone()),
                    thumbnail_url: snap.thumbnail_url,
                    is_self_post: false,
                    from_cache: false,
                    fallback_reason: None,
                    linked_entry,
                })
            }
            Err(reason) => Ok(BoardReaderView {
                board,
                item: item_view,
                content_html: None,
                extracted_summary: raw_item.summary.clone(),
                thumbnail_url: None,
                is_self_post: false,
                from_cache: false,
                fallback_reason: Some(reason),
                linked_entry,
            }),
        }
    }

    pub async fn mark_seen(
        &self,
        board_id: &str,
        external_id: &str,
    ) -> Result<BoardItemState, AppError> {
        let _ = self.get(board_id).await?;
        let trimmed = external_id.trim();
        if trimmed.is_empty() {
            return Err(AppError::bad_request("external_id cannot be empty"));
        }
        Ok(self.service.db.boards().mark_seen(board_id, trimmed).await?)
    }

    pub async fn dismiss(
        &self,
        board_id: &str,
        external_id: &str,
        backfill_offset: Option<usize>,
    ) -> Result<BoardDismissOutcome, AppError> {
        let board = self.get(board_id).await?;
        let trimmed = external_id.trim();
        if trimmed.is_empty() {
            return Err(AppError::bad_request("external_id cannot be empty"));
        }
        let state = self.service.db.boards().dismiss_item(&board.id, trimmed).await?;
        let offset = backfill_offset.unwrap_or(BOARD_DEFAULT_PAGE_SIZE.saturating_sub(1));
        let backfill_slice = self
            .service
            .db
            .boards()
            .list_items_view(&board.id, board.order_mode, false, offset, 1)
            .await?;
        let replacement_item = backfill_slice.items.into_iter().next();

        Ok(BoardDismissOutcome {
            board_id: board.id,
            external_id: state.external_id,
            dismissed_at: state.dismissed_at.unwrap_or(state.updated_at),
            replacement_item,
        })
    }

    pub async fn undismiss(
        &self,
        board_id: &str,
        external_id: &str,
    ) -> Result<BoardItemState, AppError> {
        let _ = self.get(board_id).await?;
        let trimmed = external_id.trim();
        if trimmed.is_empty() {
            return Err(AppError::bad_request("external_id cannot be empty"));
        }
        Ok(self.service.db.boards().undismiss_item(board_id, trimmed).await?)
    }

    pub async fn promote(
        &self,
        board_id: &str,
        external_id: &str,
        action: BoardPromoteAction,
    ) -> Result<BoardPromoteResult, AppError> {
        let board = self.get(board_id).await?;
        let raw_item = self.resolve_board_item(&board, external_id).await?;
        let canon_url = canonicalize_sync_url(&raw_item.url);

        let cached = self.service.board_reader_cache.get(&canon_url);
        let content =
            cached.map_or_else(BoardPromoteContent::default, |snap| BoardPromoteContent {
                content_html: snap.content_html,
                summary: snap.summary,
                thumbnail_url: snap.thumbnail_url,
                author: snap.author,
            });

        let is_finish = matches!(action, BoardPromoteAction::Finish { .. });
        let outcome = self
            .service
            .db
            .boards()
            .promote_item_to_entry(&board, &raw_item, &content, &action)
            .await?;

        let should_run_media =
            outcome.created_new && !is_finish && outcome.content_type != ContentType::Article;
        if (outcome.needs_html_enrichment || should_run_media)
            && let Ok(parsed_url) = parse_public_url(&raw_item.url)
        {
            let service_clone = self.service.clone();
            let entry_id = outcome.entry_id.clone();
            let content_type = outcome.content_type;
            let needs_html = outcome.needs_html_enrichment;
            self.service.spawn_background(async move {
                Self::enrich_promoted_entry_in_background(
                    &service_clone,
                    &entry_id,
                    &parsed_url,
                    content_type,
                    needs_html,
                    should_run_media,
                )
                .await;
            });
        }

        let board_item = self
            .service
            .db
            .boards()
            .hydrate_item_views(&board.id, vec![raw_item])
            .await?
            .into_iter()
            .next();

        Ok(BoardPromoteResult {
            entry_id: outcome.entry_id,
            created_new: outcome.created_new,
            item: outcome.item,
            board_item,
        })
    }

    /// Undoes completion on a promoted board entry (deleting `'consumed'` logs and resetting
    /// `read_at = NULL`, `read_progress = 0.0`, preserving `inbox_state = 'none'`) WITHOUT
    /// forcibly adding the entry to `Want to Read`.
    #[allow(dead_code)]
    pub async fn unfinish_board_entry(&self, entry_id: &str) -> Result<(), AppError> {
        self.service.items().unfinish_reader_item_with_options(entry_id, false).await
    }

    async fn resolve_board_item(
        &self,
        board: &Board,
        external_id: &str,
    ) -> Result<BoardItem, AppError> {
        let trimmed = external_id.trim();
        if trimmed.is_empty() {
            return Err(AppError::bad_request("external_id cannot be empty"));
        }
        if let Some(item) = self.service.db.boards().get_item(&board.id, trimmed).await? {
            return Ok(item);
        }
        BoardIngester::fetch_single_item(&self.service.fetcher, board, trimmed)
            .await
            .map_err(|e| {
                let safe_msg = crate::util::url_guard::sanitize_upstream_error(&e);
                AppError::bad_gateway(format!(
                    "Failed to load board item from upstream: {safe_msg}"
                ))
            })?
            .ok_or_else(|| AppError::not_found("Board item not found"))
    }

    async fn fetch_and_extract_reader_snapshot(
        &self,
        raw_url: &str,
    ) -> Result<CachedBoardReaderSnapshot, BoardReaderFallbackReason> {
        let parsed =
            parse_public_url(raw_url).map_err(|_| BoardReaderFallbackReason::FetchFailed)?;

        if let Some(aid) = extract_arxiv_id(parsed.as_str())
            && is_valid_arxiv_id(&aid)
        {
            return self.fetch_arxiv_reader_snapshot(&aid).await;
        }

        match classify_url(&parsed) {
            ContentType::Article => {}
            ContentType::Pdf => return Err(BoardReaderFallbackReason::Pdf),
            ContentType::Video => return Err(BoardReaderFallbackReason::Video),
            ContentType::Paper => return Err(BoardReaderFallbackReason::Paper),
            ContentType::Audio => return Err(BoardReaderFallbackReason::Unextractable),
        }

        let fetched = match tokio::time::timeout(
            Duration::from_secs(12),
            self.service.fetcher.fetch_html(&parsed),
        )
        .await
        {
            Ok(Ok(Some(html))) => html,
            Ok(Ok(None)) => return Err(BoardReaderFallbackReason::Unextractable),
            Ok(Err(err)) => {
                if err.to_string().contains("application/pdf") {
                    return Err(BoardReaderFallbackReason::Pdf);
                }
                return Err(BoardReaderFallbackReason::FetchFailed);
            }
            Err(_) => return Err(BoardReaderFallbackReason::FetchFailed),
        };
        if fetched.trim().is_empty() {
            return Err(BoardReaderFallbackReason::Unextractable);
        }
        let base_url = parsed;
        let snap = tokio::task::spawn_blocking(move || {
            HtmlSanitizer::extract_snapshot_with_base(&fetched, Some(&base_url))
        })
        .await
        .map_err(|_| BoardReaderFallbackReason::Unextractable)?;
        if snap.content_html.trim().is_empty() {
            return Err(BoardReaderFallbackReason::Unextractable);
        }
        Ok(CachedBoardReaderSnapshot {
            author: snap.author,
            summary: snap.summary,
            content_html: Some(snap.content_html),
            thumbnail_url: snap.thumbnail_url,
        })
    }

    async fn fetch_arxiv_reader_snapshot(
        &self,
        arxiv_id: &str,
    ) -> Result<CachedBoardReaderSnapshot, BoardReaderFallbackReason> {
        let api_url =
            Url::parse_with_params("https://export.arxiv.org/api/query", &[("id_list", arxiv_id)])
                .map_err(|_| BoardReaderFallbackReason::Paper)?;
        validate_public_url(&api_url).map_err(|_| BoardReaderFallbackReason::Paper)?;
        let xml = tokio::time::timeout(
            Duration::from_secs(10),
            self.service.fetcher.fetch_feed(&api_url),
        )
        .await
        .map_err(|_| BoardReaderFallbackReason::Paper)?
        .map_err(|_| BoardReaderFallbackReason::Paper)?;
        let parsed = tokio::task::spawn_blocking(move || ArxivParser::parse(&xml))
            .await
            .map_err(|_| BoardReaderFallbackReason::Paper)?
            .map_err(|_| BoardReaderFallbackReason::Paper)?;
        let paper = parsed.items.into_iter().next().ok_or(BoardReaderFallbackReason::Paper)?;
        let abstract_html =
            format_arxiv_abstract_html(&paper.summary).ok_or(BoardReaderFallbackReason::Paper)?;
        let summary_text = HtmlSanitizer::plain_text_truncate(&paper.summary, MAX_SUMMARY_CHARS);
        let summary = (!summary_text.is_empty()).then_some(summary_text);
        let author = (!paper.authors.is_empty()).then(|| paper.authors.join(", "));
        Ok(CachedBoardReaderSnapshot {
            author,
            summary,
            content_html: Some(abstract_html),
            thumbnail_url: None,
        })
    }

    async fn enrich_promoted_entry_in_background(
        service: &RocheService,
        entry_id: &str,
        item_url: &Url,
        content_type: ContentType,
        needs_html: bool,
        run_media_ingest: bool,
    ) {
        let arxiv_id = extract_arxiv_id(item_url.as_str());
        if let Some(ref aid) = arxiv_id
            && is_valid_arxiv_id(aid)
            && let Ok(api_url) =
                Url::parse_with_params("https://export.arxiv.org/api/query", &[("id_list", aid)])
            && validate_public_url(&api_url).is_ok()
            && let Ok(Ok(xml)) =
                tokio::time::timeout(Duration::from_secs(10), service.fetcher.fetch_feed(&api_url))
                    .await
            && let Ok(Ok(parsed)) =
                tokio::task::spawn_blocking(move || ArxivParser::parse(&xml)).await
            && let Some(paper) = parsed.items.into_iter().next()
        {
            let clean_title = paper.title.split_whitespace().collect::<Vec<_>>().join(" ");
            let clean_summary =
                HtmlSanitizer::plain_text_truncate(&paper.summary, MAX_SUMMARY_CHARS);
            let _ = service
                .db
                .items()
                .apply_background_enrichment(
                    entry_id,
                    (!clean_title.is_empty()).then_some(clean_title.as_str()),
                    &paper.authors,
                    paper.published_at,
                    (!clean_summary.is_empty()).then_some(clean_summary.as_str()),
                    None,
                    None,
                )
                .await;
        }

        if needs_html
            && arxiv_id.is_none()
            && let Ok(Ok(Some(raw_html))) =
                tokio::time::timeout(Duration::from_secs(10), service.fetcher.fetch_html(item_url))
                    .await
        {
            let base_url = item_url.clone();
            if let Ok(snapshot) = tokio::task::spawn_blocking(move || {
                HtmlSanitizer::extract_snapshot_with_base(&raw_html, Some(&base_url))
            })
            .await
            {
                let body_html = (!snapshot.content_html.trim().is_empty())
                    .then_some(snapshot.content_html.as_str());
                let _ = service
                    .db
                    .items()
                    .apply_background_enrichment(
                        entry_id,
                        snapshot.title.as_deref(),
                        snapshot.author.as_slice(),
                        None,
                        snapshot.summary.as_deref(),
                        snapshot.thumbnail_url.as_deref(),
                        body_html,
                    )
                    .await;
            }
        }

        if run_media_ingest {
            let ctx = service.media_ingest_context();
            FeedIngester::ingest_item_media(&ctx, entry_id, content_type, item_url).await;
        }
    }

    fn validate_poll_interval(&self, interval: i64) -> Result<(), AppError> {
        let min = self.service.config.feeds.min_poll_interval;
        let max = self.service.config.feeds.max_poll_interval;
        if !(min..=max).contains(&interval) {
            return Err(AppError::bad_request(format!(
                "poll_interval must be between {min} and {max} minutes"
            )));
        }
        Ok(())
    }
}

fn format_arxiv_abstract_html(raw_abstract: &str) -> Option<String> {
    let normalized = raw_abstract.replace("\r\n", "\n");
    let mut paragraphs = Vec::new();
    for block in normalized.split("\n\n") {
        let collapsed = block.split_whitespace().collect::<Vec<_>>().join(" ");
        if collapsed.is_empty() {
            continue;
        }
        let mut escaped = String::with_capacity(collapsed.len());
        for ch in collapsed.chars() {
            match ch {
                '&' => escaped.push_str("&amp;"),
                '<' => escaped.push_str("&lt;"),
                '>' => escaped.push_str("&gt;"),
                '"' => escaped.push_str("&quot;"),
                '\'' => escaped.push_str("&#39;"),
                other => escaped.push(other),
            }
        }
        paragraphs.push(format!("<p>{escaped}</p>"));
    }
    if paragraphs.is_empty() {
        return None;
    }
    let cleaned = HtmlSanitizer::clean(&paragraphs.join("\n"));
    (!cleaned.trim().is_empty()).then_some(cleaned)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};

    use roche_core::boards::HackerNewsSection;

    use super::*;
    use crate::{
        config::Config,
        db::Db,
        domain::FeedType,
        ingest::{BoardAdapter, HackerNewsAdapter},
    };

    #[derive(Clone, Default)]
    struct MockHnServiceFetcher {
        html_calls: Arc<AtomicUsize>,
    }

    impl FeedFetcher for MockHnServiceFetcher {
        fn fetch_feed(
            &self,
            url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            let s = url.as_str().to_owned();
            async move {
                if s == HackerNewsSection::Top.api_stories_url() {
                    Ok("[101, 102, 103, 104, 105, 106, 107, 108]".to_owned())
                } else if s.ends_with("/item/101.json") {
                    Ok(r#"{"id":101,"type":"story","title":"Deep Dive Article","url":"https://example.com/deep-article","by":"alice","score":300,"descendants":80,"time":1732300000}"#.to_owned())
                } else if s.ends_with("/item/102.json") {
                    // Deleted item — must be skipped with contiguous rank assignment
                    Ok(r#"{"id":102,"type":"story","title":"Deleted","deleted":true}"#.to_owned())
                } else if s.ends_with("/item/103.json") {
                    // SSRF target URL — must be skipped
                    Ok(r#"{"id":103,"type":"story","title":"Internal Admin","url":"http://127.0.0.1/admin","by":"mallory"}"#.to_owned())
                } else if s.ends_with("/item/104.json") {
                    // Ask HN self-post with script tag that must be sanitized
                    Ok(r#"{"id":104,"type":"story","title":"Ask HN: Favorite Rust crates?","text":"<p>Share your <b>favorites</b></p><script>alert(1)</script>","by":"bob","score":150,"descendants":64,"time":1732300200}"#.to_owned())
                } else if s.ends_with("/item/105.json") {
                    // Unextractable / empty HTML URL
                    Ok(r#"{"id":105,"type":"story","title":"Opaque App","url":"https://example.com/empty-app","by":"carol","score":90,"descendants":12,"time":1732300300}"#.to_owned())
                } else if s.ends_with("/item/106.json") {
                    Ok(r#"{"id":106,"type":"story","title":"Story Four","url":"https://example.com/story-4","by":"dave","score":75,"descendants":9,"time":1732300400}"#.to_owned())
                } else if s.ends_with("/item/107.json") {
                    Ok(r#"{"id":107,"type":"story","title":"Story Five","url":"https://example.com/story-5","by":"eve","score":60,"descendants":5,"time":1732300500}"#.to_owned())
                } else if s.ends_with("/item/108.json") {
                    Ok(r#"{"id":108,"type":"story","title":"Lazy Page Story","url":"https://example.com/lazy-story","by":"frank","score":45,"descendants":3,"time":1732300600}"#.to_owned())
                } else if s == "https://example.com/rss.xml" {
                    Ok(r#"<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Feed</title><item><title>RSS Item</title><link>https://example.com/rss-1</link></item></channel></rss>"#.to_owned())
                } else {
                    Err(anyhow::anyhow!("unexpected fetch_feed url: {s}"))
                }
            }
        }

        fn fetch_html(
            &self,
            url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> + Send {
            let calls = Arc::clone(&self.html_calls);
            let s = url.as_str().to_owned();
            async move {
                calls.fetch_add(1, Ordering::SeqCst);
                if s == "https://example.com/deep-article" {
                    Ok(Some(
                        r#"<html><head><title>Deep Dive</title><meta name="description" content="Concise summary"/></head><body><article><script>evil()</script><p>Extracted full body text.</p></article></body></html>"#
                            .to_owned(),
                    ))
                } else {
                    Ok(None)
                }
            }
        }
    }

    #[test]
    fn test_board_reader_cache_lru_eviction_at_capacity_64() {
        let cache = BoardReaderCache::new();
        let make_snap = |idx: usize| CachedBoardReaderSnapshot {
            author: None,
            summary: Some(format!("sum-{idx}")),
            content_html: Some(format!("<p>{idx}</p>")),
            thumbnail_url: None,
        };

        for i in 0..BOARD_READER_CACHE_CAPACITY {
            cache.insert(format!("https://example.com/{i}"), make_snap(i));
        }
        assert_eq!(cache.len(), BOARD_READER_CACHE_CAPACITY);

        // Touch entry 0 so entry 1 becomes least-recently-used.
        assert!(cache.get("https://example.com/0").is_some());

        cache.insert("https://example.com/64".to_owned(), make_snap(64));
        assert_eq!(cache.len(), BOARD_READER_CACHE_CAPACITY);
        assert!(cache.get("https://example.com/0").is_some(), "MRU entry 0 must survive");
        assert!(cache.get("https://example.com/1").is_none(), "LRU entry 1 must be evicted");
        assert!(cache.get("https://example.com/64").is_some(), "new entry 64 must be present");
    }

    #[tokio::test]
    async fn test_hn_adapter_snapshot_lazy_page_single_item_ask_hn_and_ssrf_skip() {
        let fetcher = MockHnServiceFetcher::default();
        let adapter = HackerNewsAdapter::new(HackerNewsSection::Top);

        let snap = adapter.fetch_snapshot(&fetcher, 3).await.unwrap();
        assert_eq!(snap.items.len(), 3);
        let ids: Vec<&str> = snap.items.iter().map(|i| i.external_id.as_str()).collect();
        // 102 (deleted) and 103 (SSRF 127.0.0.1) are skipped; ranks stay contiguous 1..=3.
        assert_eq!(ids, vec!["101", "104", "105"]);
        let ranks: Vec<i64> = snap.items.iter().map(|i| i.rank).collect();
        assert_eq!(ranks, vec![1, 2, 3]);

        // Ask HN self-post sanitized (no <script>) and falls back to discussion_url.
        let ask = &snap.items[1];
        assert_eq!(ask.url, "https://news.ycombinator.com/item?id=104");
        assert_eq!(ask.discussion_url.as_deref(), Some("https://news.ycombinator.com/item?id=104"));
        let self_html = ask.self_text_html.as_deref().unwrap();
        assert!(self_html.contains("favorites"));
        assert!(!self_html.contains("<script>"));
        assert!(ask.summary.as_deref().unwrap().contains("Share your favorites"));

        // Lazy page beyond offset 5 fetches remaining live stories without DB persistence.
        let page = adapter.fetch_page(&fetcher, 5, 2).await.unwrap();
        assert_eq!(page.items.len(), 2);
        assert_eq!(page.items[0].external_id, "106");
        assert_eq!(page.items[1].external_id, "107");
        assert!(page.has_more);
        assert_eq!(page.next_offset, Some(7));

        // Verify next_offset advances past skipped deleted/SSRF items (102, 103) so pages never overlap.
        let page_from_0 = adapter.fetch_page(&fetcher, 0, 2).await.unwrap();
        assert_eq!(page_from_0.items.len(), 2);
        assert_eq!(page_from_0.items[0].external_id, "101");
        assert_eq!(page_from_0.items[1].external_id, "104");
        assert!(page_from_0.has_more);
        assert_eq!(
            page_from_0.next_offset,
            Some(4),
            "next_offset must skip past deleted/SSRF items at indices 1 and 2"
        );
        let page_next = adapter.fetch_page(&fetcher, 4, 2).await.unwrap();
        assert_eq!(page_next.items[0].external_id, "105");
        assert_eq!(page_next.items[1].external_id, "106");

        // Single-item fallback fetches an arbitrary item ID cleanly.
        let single = adapter.fetch_single_item(&fetcher, "108").await.unwrap().unwrap();
        assert_eq!(single.title, "Lazy Page Story");
        assert_eq!(single.url, "https://example.com/lazy-story");
    }

    #[tokio::test]
    async fn test_board_service_reader_cache_dismiss_backfill_promote_and_refresh_all_sources() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let fetcher = MockHnServiceFetcher::default();
        let html_calls = Arc::clone(&fetcher.html_calls);
        let service = RocheService::new(db.clone(), fetcher, Config::default());

        // Create board via HN URL auto-detection -> auto-polls immediately.
        let board = service
            .boards()
            .create(CreateBoardInput {
                url: Some("https://news.ycombinator.com/".to_owned()),
                ..CreateBoardInput::default()
            })
            .await
            .unwrap();
        assert_eq!(board.item_count, 6);

        // Open reader on Ask HN self-post: uses self_text_html, marks seen, 0 HTML fetches, 0 entries.
        let ask_reader = service.boards().open_reader(&board.id, "104").await.unwrap();
        assert!(ask_reader.is_self_post);
        assert!(ask_reader.item.is_seen);
        assert!(ask_reader.fallback_reason.is_none());
        assert!(ask_reader.content_html.as_deref().unwrap().contains("favorites"));
        assert_eq!(html_calls.load(Ordering::SeqCst), 0);
        assert!(db.items().list(None, None, None).await.unwrap().is_empty());

        // Open reader on external article: extracts HTML once, second call hits in-memory cache.
        let ext_reader_1 = service.boards().open_reader(&board.id, "101").await.unwrap();
        assert!(!ext_reader_1.is_self_post);
        assert!(!ext_reader_1.from_cache);
        assert!(ext_reader_1.fallback_reason.is_none());
        let body = ext_reader_1.content_html.as_deref().unwrap();
        assert!(body.contains("Extracted full body text."));
        assert!(!body.contains("evil()"));
        assert_eq!(html_calls.load(Ordering::SeqCst), 1);

        let ext_reader_2 = service.boards().open_reader(&board.id, "101").await.unwrap();
        assert!(ext_reader_2.from_cache);
        assert!(ext_reader_2.fallback_reason.is_none());
        assert_eq!(html_calls.load(Ordering::SeqCst), 1);
        assert!(db.items().list(None, None, None).await.unwrap().is_empty());

        // Unextractable URL returns graceful None content_html fallback AND is NOT cached.
        let empty_reader_1 = service.boards().open_reader(&board.id, "105").await.unwrap();
        assert!(empty_reader_1.content_html.is_none());
        assert_eq!(empty_reader_1.fallback_reason, Some(BoardReaderFallbackReason::Unextractable));
        assert!(empty_reader_1.item.is_seen);
        assert_eq!(html_calls.load(Ordering::SeqCst), 2);
        let empty_reader_2 = service.boards().open_reader(&board.id, "105").await.unwrap();
        assert!(!empty_reader_2.from_cache, "failed/empty extraction must not be cached");
        assert_eq!(html_calls.load(Ordering::SeqCst), 3);

        // Dismiss item 101 with backfill_offset = 1 -> returns next visible item (rank #3 -> 105).
        let dismissed = service.boards().dismiss(&board.id, "101", Some(1)).await.unwrap();
        assert_eq!(dismissed.external_id, "101");
        assert_eq!(
            dismissed.replacement_item.as_ref().map(|v| v.external_id.as_str()),
            Some("105")
        );

        // Undismiss restores 101 at rank #1.
        let undismissed = service.boards().undismiss(&board.id, "101").await.unwrap();
        assert!(undismissed.dismissed_at.is_none());

        // Promote cached item 101 to Queue: reuses cached HTML without refetching, inbox_state='none'.
        let calls_before_promote = html_calls.load(Ordering::SeqCst);
        let promoted =
            service.boards().promote(&board.id, "101", BoardPromoteAction::Queue).await.unwrap();
        assert!(promoted.created_new);
        assert!(
            promoted.item.content_html.as_deref().unwrap().contains("Extracted full body text.")
        );
        assert_eq!(promoted.item.discussions.len(), 1);
        assert_eq!(promoted.item.discussions[0].external_id.as_deref(), Some("101"));
        assert!(promoted.board_item.as_ref().unwrap().in_want_to_read);
        let inbox_state: String =
            sqlx::query_scalar("SELECT inbox_state FROM entry_state WHERE entry_id = ?")
                .bind(&promoted.entry_id)
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(inbox_state, "none", "promoted board entry must keep inbox_state='none'");
        assert_eq!(html_calls.load(Ordering::SeqCst), calls_before_promote);

        // Unified masthead refresh covers both active feeds and active boards.
        let feed_url = Url::parse("https://example.com/rss.xml").unwrap();
        db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();
        let outcome = service.refresh_all_sources_in_background().await.unwrap();
        assert_eq!(outcome.feeds, 1);
        assert_eq!(outcome.boards, 1);
        assert_eq!(outcome.total, 2);
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_board_reader_edge_cases_bodyless_ask_arxiv_fallbacks_and_unfinish_no_requeue() {
        #[derive(Clone, Default)]
        struct EdgeCaseFetcher {
            html_calls: Arc<AtomicUsize>,
            arxiv_calls: Arc<AtomicUsize>,
        }

        impl FeedFetcher for EdgeCaseFetcher {
            fn fetch_feed(
                &self,
                url: &Url,
            ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
                let s = url.as_str().to_owned();
                let arxiv_calls = Arc::clone(&self.arxiv_calls);
                async move {
                    if s == HackerNewsSection::Ask.api_stories_url() {
                        Ok("[501, 502, 503, 504, 505, 506]".to_owned())
                    } else if s.ends_with("/item/501.json") {
                        // Body-less Ask HN self-post (no url, no text)
                        Ok(r#"{"id":501,"type":"story","title":"Ask HN: Best books of 2026?","by":"alice","score":88,"descendants":40,"time":1732300000}"#.to_owned())
                    } else if s.ends_with("/item/502.json") {
                        // arXiv paper
                        Ok(r#"{"id":502,"type":"story","title":"Attention Paper","url":"https://arxiv.org/abs/2401.12345","by":"bob","score":210,"descendants":55,"time":1732300100}"#.to_owned())
                    } else if s.ends_with("/item/503.json") {
                        // Direct PDF
                        Ok(r#"{"id":503,"type":"story","title":"Spec PDF","url":"https://example.com/spec.pdf","by":"carol","score":50,"descendants":4,"time":1732300200}"#.to_owned())
                    } else if s.ends_with("/item/504.json") {
                        // YouTube video
                        Ok(r#"{"id":504,"type":"story","title":"Talk Video","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","by":"dave","score":70,"descendants":12,"time":1732300300}"#.to_owned())
                    } else if s.ends_with("/item/505.json") {
                        // Article whose upstream HTML fetch fails (500 / timeout)
                        Ok(r#"{"id":505,"type":"story","title":"Broken Site","url":"https://example.com/error-500","by":"eve","score":40,"descendants":3,"time":1732300400}"#.to_owned())
                    } else if s.ends_with("/item/506.json") {
                        // Article whose HTML extracts empty
                        Ok(r#"{"id":506,"type":"story","title":"Blank Page","url":"https://example.com/blank-page","by":"frank","score":30,"descendants":2,"time":1732300500}"#.to_owned())
                    } else if s == "https://export.arxiv.org/api/query?id_list=2401.12345" {
                        arxiv_calls.fetch_add(1, Ordering::SeqCst);
                        Ok(r#"<?xml version="1.0" encoding="UTF-8"?>
                        <feed xmlns="http://www.w3.org/2005/Atom">
                          <title>ArXiv Query</title>
                          <entry>
                            <id>http://arxiv.org/abs/2401.12345v1</id>
                            <title>Attention Paper</title>
                            <summary>We show x &lt; y &amp; z &gt; 0 in transformers.

Second paragraph of the abstract.</summary>
                            <author><name>Ada Lovelace</name></author>
                            <author><name>Alan Turing</name></author>
                            <link rel="alternate" href="https://arxiv.org/abs/2401.12345"/>
                          </entry>
                        </feed>"#
                            .to_owned())
                    } else {
                        Err(anyhow::anyhow!("unexpected feed url: {s}"))
                    }
                }
            }

            fn fetch_html(
                &self,
                url: &Url,
            ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> + Send
            {
                let calls = Arc::clone(&self.html_calls);
                let s = url.as_str().to_owned();
                async move {
                    calls.fetch_add(1, Ordering::SeqCst);
                    if s == "https://example.com/error-500" {
                        Err(anyhow::anyhow!("Upstream server returned HTTP 500"))
                    } else if s == "https://example.com/blank-page" {
                        Ok(Some(
                            "<html><body><script>onlyScript()</script></body></html>".to_owned(),
                        ))
                    } else {
                        Err(anyhow::anyhow!("fetch_html should not be called for {s}"))
                    }
                }
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let fetcher = EdgeCaseFetcher::default();
        let html_calls = Arc::clone(&fetcher.html_calls);
        let arxiv_calls = Arc::clone(&fetcher.arxiv_calls);
        let service = RocheService::new(db.clone(), fetcher, Config::default());

        let board = service
            .boards()
            .create(CreateBoardInput {
                section: Some("ask".to_owned()),
                ..CreateBoardInput::default()
            })
            .await
            .unwrap();
        assert_eq!(board.item_count, 6);

        // 1. Body-less Ask HN must return SelfPostEmpty with ZERO HTTP calls to news.ycombinator.com.
        let bodyless = service.boards().open_reader(&board.id, "501").await.unwrap();
        assert!(bodyless.is_self_post);
        assert!(bodyless.content_html.is_none());
        assert_eq!(bodyless.fallback_reason, Some(BoardReaderFallbackReason::SelfPostEmpty));
        assert_eq!(html_calls.load(Ordering::SeqCst), 0);

        // 2. arXiv paper extracts sanitized abstract HTML (preserving math < > &) & caches it.
        let paper = service.boards().open_reader(&board.id, "502").await.unwrap();
        assert!(!paper.is_self_post);
        assert!(!paper.from_cache);
        assert!(paper.fallback_reason.is_none());
        let paper_html = paper.content_html.as_deref().unwrap();
        assert!(paper_html.contains("x &lt; y &amp; z &gt; 0"));
        assert!(paper_html.contains("Second paragraph of the abstract."));
        assert_eq!(arxiv_calls.load(Ordering::SeqCst), 1);
        assert_eq!(html_calls.load(Ordering::SeqCst), 0);

        let paper_cached = service.boards().open_reader(&board.id, "502").await.unwrap();
        assert!(paper_cached.from_cache);
        assert_eq!(arxiv_calls.load(Ordering::SeqCst), 1);

        // 3. PDF, Video, FetchFailed, and Unextractable return accurate fallback reasons & never cache.
        let pdf = service.boards().open_reader(&board.id, "503").await.unwrap();
        assert!(pdf.content_html.is_none());
        assert_eq!(pdf.fallback_reason, Some(BoardReaderFallbackReason::Pdf));
        assert_eq!(html_calls.load(Ordering::SeqCst), 0);

        let video = service.boards().open_reader(&board.id, "504").await.unwrap();
        assert!(video.content_html.is_none());
        assert_eq!(video.fallback_reason, Some(BoardReaderFallbackReason::Video));
        assert_eq!(html_calls.load(Ordering::SeqCst), 0);

        let failed = service.boards().open_reader(&board.id, "505").await.unwrap();
        assert!(failed.content_html.is_none());
        assert_eq!(failed.fallback_reason, Some(BoardReaderFallbackReason::FetchFailed));
        assert_eq!(html_calls.load(Ordering::SeqCst), 1);
        let failed_retry = service.boards().open_reader(&board.id, "505").await.unwrap();
        assert!(!failed_retry.from_cache);
        assert_eq!(html_calls.load(Ordering::SeqCst), 2);

        let blank = service.boards().open_reader(&board.id, "506").await.unwrap();
        assert!(blank.content_html.is_none());
        assert_eq!(blank.fallback_reason, Some(BoardReaderFallbackReason::Unextractable));
        assert_eq!(html_calls.load(Ordering::SeqCst), 3);
        let blank_retry = service.boards().open_reader(&board.id, "506").await.unwrap();
        assert!(!blank_retry.from_cache);
        assert_eq!(html_calls.load(Ordering::SeqCst), 4);

        // 4. Promote(Finish) followed by unfinish_board_entry clears consumed log & read_at
        //    WITHOUT adding the board entry to Want to Read Queue.
        let finished = service
            .boards()
            .promote(&board.id, "502", BoardPromoteAction::Finish { notes: None })
            .await
            .unwrap();
        assert!(finished.board_item.as_ref().unwrap().is_finished);
        assert!(!finished.board_item.as_ref().unwrap().in_want_to_read);

        service.boards().unfinish_board_entry(&finished.entry_id).await.unwrap();

        let after_unfinish = db.items().get(&finished.entry_id).await.unwrap().unwrap();
        assert!(!after_unfinish.is_finished);
        assert!(after_unfinish.read_at.is_none());
        assert!((after_unfinish.read_progress - 0.0).abs() < f64::EPSILON);
        assert!(
            !after_unfinish.in_want_to_read,
            "unfinish_board_entry must NOT forcibly insert promoted board story into Want to Read"
        );
        assert_eq!(after_unfinish.inbox_state, crate::domain::InboxState::None);
    }
}
