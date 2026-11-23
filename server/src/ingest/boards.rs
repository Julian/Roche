use std::{
    future::Future,
    sync::{
        Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

use anyhow::Result;
use roche_core::boards::{
    BOARD_SNAPSHOT_BUFFER_LIMIT, BoardSourceSpec, BoardType, HackerNewsSection, ParsedHnItem,
    extract_hn_item_id, hn_item_api_url, parse_hn_item_json, parse_hn_story_ids,
};
use url::Url;

use super::{FeedFetcher, PollError};
use crate::{
    content::{HtmlSanitizer, MAX_SUMMARY_CHARS},
    db::{Board, BoardItem, BoardPollBatch, BoardPollCandidate, Db},
    util::clock::now_utc,
};

pub const BOARD_ITEM_FETCH_CONCURRENCY: usize = 16;
const OVERSCAN_EXTRA_IDS: usize = 12;

#[derive(Debug, Clone)]
pub struct BoardPageFetch {
    pub items: Vec<BoardPollCandidate>,
    pub has_more: bool,
    pub next_offset: Option<usize>,
}

#[derive(Debug, Clone, Copy)]
pub struct BoardPollOutcome {
    pub item_count: usize,
}

#[must_use]
pub fn board_poll_lock_key(board_id: &str) -> String {
    format!("board:{board_id}")
}

/// Pluggable external board adapter abstraction (starting with Hacker News; extensible to other
/// snapshot-based front-page / trending boards).
pub trait BoardAdapter: Send + Sync {
    fn fetch_snapshot<F: FeedFetcher>(
        &self,
        fetcher: &F,
        limit: usize,
    ) -> impl Future<Output = Result<BoardPollBatch>> + Send;

    fn fetch_page<F: FeedFetcher>(
        &self,
        fetcher: &F,
        offset: usize,
        limit: usize,
    ) -> impl Future<Output = Result<BoardPageFetch>> + Send;

    fn fetch_single_item<F: FeedFetcher>(
        &self,
        fetcher: &F,
        external_id: &str,
    ) -> impl Future<Output = Result<Option<BoardPollCandidate>>> + Send;
}

#[derive(Debug, Clone, Copy)]
pub struct HackerNewsAdapter {
    section: HackerNewsSection,
}

impl HackerNewsAdapter {
    #[must_use]
    pub const fn new(section: HackerNewsSection) -> Self {
        Self { section }
    }

    pub fn for_board(board: &Board) -> Result<Self> {
        let section = BoardSourceSpec::parse_hn_section(
            board.adapter_type,
            &board.source_key,
            &board.source_config_json,
        )?;
        Ok(Self::new(section))
    }
}

impl BoardAdapter for HackerNewsAdapter {
    async fn fetch_snapshot<F: FeedFetcher>(
        &self,
        fetcher: &F,
        limit: usize,
    ) -> Result<BoardPollBatch> {
        let page = self.fetch_page(fetcher, 0, limit).await?;
        Ok(BoardPollBatch { items: page.items })
    }

    async fn fetch_page<F: FeedFetcher>(
        &self,
        fetcher: &F,
        offset: usize,
        limit: usize,
    ) -> Result<BoardPageFetch> {
        let safe_limit = limit.max(1);
        let stories_url = Url::parse(self.section.api_stories_url())?;
        let stories_json = fetcher.fetch_feed(&stories_url).await?;
        let story_ids =
            tokio::task::spawn_blocking(move || parse_hn_story_ids(&stories_json)).await??;
        let total_upstream = story_ids.len();

        if offset >= total_upstream {
            return Ok(BoardPageFetch { items: Vec::new(), has_more: false, next_offset: None });
        }

        let remaining = total_upstream - offset;
        let window_len = safe_limit.saturating_add(OVERSCAN_EXTRA_IDS).min(remaining);
        let window = &story_ids[offset..offset + window_len];

        let mut indexed = fetch_hn_items_bounded(fetcher, window).await;
        indexed.sort_by_key(|(idx, _)| *idx);

        let mut items = Vec::with_capacity(safe_limit.min(indexed.len()));
        let mut last_used_rel_idx = 0usize;
        for (pos, (rel_idx, mut cand)) in indexed.into_iter().take(safe_limit).enumerate() {
            cand.rank =
                i64::try_from(offset.saturating_add(pos).saturating_add(1)).unwrap_or(i64::MAX);
            last_used_rel_idx = rel_idx;
            items.push(cand);
        }

        let consumed_end = if items.is_empty() {
            offset.saturating_add(window_len)
        } else {
            offset.saturating_add(last_used_rel_idx).saturating_add(1)
        };
        let has_more = consumed_end < total_upstream;
        let next_offset = has_more.then_some(consumed_end);

        Ok(BoardPageFetch { items, has_more, next_offset })
    }

    async fn fetch_single_item<F: FeedFetcher>(
        &self,
        fetcher: &F,
        external_id: &str,
    ) -> Result<Option<BoardPollCandidate>> {
        let Some(clean_id) = extract_hn_item_id(external_id) else {
            return Ok(None);
        };
        let Ok(story_id) = clean_id.parse::<u64>() else {
            return Ok(None);
        };
        fetch_and_parse_hn_item(fetcher, story_id).await
    }
}

async fn fetch_hn_items_bounded<F: FeedFetcher>(
    fetcher: &F,
    story_ids: &[u64],
) -> Vec<(usize, BoardPollCandidate)> {
    const _: () = assert!(BOARD_ITEM_FETCH_CONCURRENCY == 16);
    if story_ids.is_empty() {
        return Vec::new();
    }
    let next_idx = AtomicUsize::new(0);
    let results: Mutex<Vec<(usize, BoardPollCandidate)>> =
        Mutex::new(Vec::with_capacity(story_ids.len()));

    let worker = || async {
        loop {
            let idx = next_idx.fetch_add(1, Ordering::Relaxed);
            let Some(&story_id) = story_ids.get(idx) else {
                break;
            };
            match fetch_and_parse_hn_item(fetcher, story_id).await {
                Ok(Some(cand)) => {
                    let mut guard =
                        results.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                    guard.push((idx, cand));
                }
                Ok(None) => {}
                Err(err) => {
                    tracing::debug!(
                        hn_story_id = story_id,
                        error = %err,
                        "Skipping Hacker News item that failed to fetch or parse"
                    );
                }
            }
        }
    };

    // Bounded concurrency of BOARD_ITEM_FETCH_CONCURRENCY (16) in-flight item requests.
    tokio::join!(
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
        worker(),
    );

    results.into_inner().unwrap_or_else(std::sync::PoisonError::into_inner)
}

async fn fetch_and_parse_hn_item<F: FeedFetcher>(
    fetcher: &F,
    story_id: u64,
) -> Result<Option<BoardPollCandidate>> {
    let item_url = Url::parse(&hn_item_api_url(story_id))?;
    let body = fetcher.fetch_feed(&item_url).await?;
    let parsed = tokio::task::spawn_blocking(move || -> Result<Option<BoardPollCandidate>> {
        let Some(item) = parse_hn_item_json(&body)? else {
            return Ok(None);
        };
        Ok(Some(convert_parsed_hn_item(item)))
    })
    .await??;
    Ok(parsed)
}

fn convert_parsed_hn_item(item: ParsedHnItem) -> BoardPollCandidate {
    let self_text_html = item
        .raw_self_text
        .as_deref()
        .map(|raw| HtmlSanitizer::clean_with_base(raw, Some(&item.url)))
        .filter(|h| !h.trim().is_empty());
    let summary = self_text_html
        .as_deref()
        .map(|clean| HtmlSanitizer::plain_text_excerpt_from_clean(clean, MAX_SUMMARY_CHARS))
        .filter(|s| !s.is_empty());
    let metadata_json = if item.item_type == "story" {
        "{}".to_owned()
    } else {
        serde_json::json!({ "hn_type": item.item_type }).to_string()
    };

    BoardPollCandidate {
        external_id: item.external_id,
        rank: 1,
        title: item.title,
        url: item.url.to_string(),
        discussion_url: Some(item.discussion_url),
        author: item.author,
        domain: item.domain,
        published_at: item.published_at,
        score: item.score,
        comment_count: item.comment_count,
        summary,
        self_text_html,
        metadata_json,
    }
}

pub struct BoardIngester;

impl BoardIngester {
    /// Polls a board snapshot (`BOARD_SNAPSHOT_BUFFER_LIMIT = 90`) and persists it atomically into
    /// `board_items`. Never inserts into `entries` and never downloads PDF/video media.
    pub async fn poll_and_record<F: FeedFetcher>(
        db: &Db,
        fetcher: &F,
        board: &Board,
        context_label: &str,
        record_db_errors: bool,
    ) -> std::result::Result<BoardPollOutcome, PollError> {
        let repo = db.boards();
        let batch_res = match board.adapter_type {
            BoardType::HackerNews => {
                let adapter = HackerNewsAdapter::for_board(board).map_err(PollError::Upstream)?;
                adapter
                    .fetch_snapshot(fetcher, BOARD_SNAPSHOT_BUFFER_LIMIT)
                    .await
                    .map_err(PollError::Upstream)
            }
        };

        let batch = match batch_res {
            Ok(batch) => batch,
            Err(err) => {
                Self::handle_poll_failure(&repo, board, context_label, record_db_errors, &err)
                    .await;
                return Err(err);
            }
        };

        match repo.record_poll_batch(&board.id, &batch).await {
            Ok(item_count) => {
                tracing::info!(
                    board_id = %board.id,
                    board_title = %board.title,
                    adapter_type = board.adapter_type.as_str(),
                    source_key = %board.source_key,
                    context = context_label,
                    item_count,
                    "Board polled successfully"
                );
                Ok(BoardPollOutcome { item_count })
            }
            Err(err) => {
                let poll_err = PollError::Upstream(err);
                Self::handle_poll_failure(&repo, board, context_label, record_db_errors, &poll_err)
                    .await;
                Err(poll_err)
            }
        }
    }

    async fn handle_poll_failure(
        repo: &crate::db::BoardRepo<'_>,
        board: &Board,
        context_label: &str,
        record_db_errors: bool,
        err: &PollError,
    ) {
        tracing::error!(
            board_id = %board.id,
            board_title = %board.title,
            source_url = %board.source_url,
            context = context_label,
            error = %err,
            "Board poll failed"
        );
        if record_db_errors || matches!(err, PollError::Upstream(_)) {
            let stored_err = match err {
                PollError::Upstream(upstream) => {
                    crate::util::url_guard::sanitize_upstream_error(upstream)
                }
                PollError::Database(db_err) => db_err.to_string(),
            };
            let _ = repo.mark_error(&board.id, &stored_err).await;
        }
    }

    /// Fetches a live page beyond the cached snapshot buffer without persisting to SQLite.
    pub async fn fetch_live_page<F: FeedFetcher>(
        fetcher: &F,
        board: &Board,
        offset: usize,
        limit: usize,
    ) -> Result<BoardPageFetch> {
        match board.adapter_type {
            BoardType::HackerNews => {
                let adapter = HackerNewsAdapter::for_board(board)?;
                adapter.fetch_page(fetcher, offset, limit).await
            }
        }
    }

    /// Fetches a single board item directly from upstream when not present in `board_items`
    /// (for example, opening Reader or Promoting a lazy-loaded page 2+ item).
    pub async fn fetch_single_item<F: FeedFetcher>(
        fetcher: &F,
        board: &Board,
        external_id: &str,
    ) -> Result<Option<BoardItem>> {
        let cand_opt = match board.adapter_type {
            BoardType::HackerNews => {
                let adapter = HackerNewsAdapter::for_board(board)?;
                adapter.fetch_single_item(fetcher, external_id).await?
            }
        };
        let fetched_at = now_utc();
        Ok(cand_opt.map(|c| c.into_board_item(&board.id, fetched_at)))
    }
}
