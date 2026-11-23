use std::{
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use anyhow::Result;
use tokio::sync::Semaphore;

use crate::{
    db::{
        Board, DEFAULT_ONLINE_PEER_WEIGHT, Db, Feed, MAX_PEER_WEIGHT, MIN_PEER_WEIGHT, NetworkTier,
        SERVER_PEER_ID, compute_throughput_weight, should_peer_poll_feed, update_throughput_ewma,
    },
    ingest::{
        BoardIngester, FeedConditionalCache, FeedFetcher, FeedIngester, FeedPollGuard,
        FeedPollLocks, HttpFeedFetcher, MAX_CONCURRENT_POLLS, MediaIngestContext,
        board_poll_lock_key,
    },
    media::{MediaStore, ytdlp::YtDlpWorker},
    util::clock::now_utc,
};

#[derive(Debug)]
struct ThroughputEwmaState {
    weight: f64,
    seeded: bool,
}

#[derive(Debug, Clone)]
struct ThroughputEwma {
    state: Arc<Mutex<ThroughputEwmaState>>,
}

impl ThroughputEwma {
    fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(ThroughputEwmaState {
                weight: DEFAULT_ONLINE_PEER_WEIGHT,
                seeded: false,
            })),
        }
    }

    fn is_seeded(&self) -> bool {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).seeded
    }

    fn seed(&self, persisted_weight: Option<f64>) {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.seeded {
            return;
        }
        if let Some(w) = persisted_weight
            && w.is_finite()
            && w > 0.0
        {
            state.weight = w.clamp(MIN_PEER_WEIGHT, MAX_PEER_WEIGHT);
        }
        state.seeded = true;
    }

    fn record_sample(&self, sample_weight: f64) -> f64 {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.weight = update_throughput_ewma(state.weight, sample_weight);
        state.seeded = true;
        state.weight
    }

    fn current(&self) -> f64 {
        self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner).weight
    }
}

pub struct PollScheduler<F = HttpFeedFetcher> {
    db: Db,
    fetcher: Arc<F>,
    media_store: Option<MediaStore>,
    ytdlp_worker: Option<YtDlpWorker>,
    download_semaphore: Arc<Semaphore>,
    semaphore: Arc<Semaphore>,
    feed_poll_locks: FeedPollLocks,
    conditional_cache: FeedConditionalCache,
    throughput_ewma: ThroughputEwma,
    tasks: tokio::sync::Mutex<tokio::task::JoinSet<()>>,
}

impl<F: FeedFetcher> PollScheduler<F> {
    #[must_use]
    pub fn with_arc_fetcher(db: Db, fetcher: Arc<F>) -> Self {
        Self {
            db,
            fetcher,
            media_store: None,
            ytdlp_worker: None,
            download_semaphore: Arc::new(Semaphore::new(2)),
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_POLLS)),
            feed_poll_locks: FeedPollLocks::new(),
            conditional_cache: FeedConditionalCache::new(),
            throughput_ewma: ThroughputEwma::new(),
            tasks: tokio::sync::Mutex::new(tokio::task::JoinSet::new()),
        }
    }

    #[must_use]
    pub fn with_feed_poll_locks(mut self, locks: FeedPollLocks) -> Self {
        self.feed_poll_locks = locks;
        self
    }

    #[must_use]
    pub fn with_poll_semaphore(mut self, semaphore: Arc<Semaphore>) -> Self {
        self.semaphore = semaphore;
        self
    }

    #[must_use]
    pub fn with_media(
        mut self,
        media_store: MediaStore,
        ytdlp_worker: YtDlpWorker,
        download_semaphore: Arc<Semaphore>,
    ) -> Self {
        self.media_store = Some(media_store);
        self.ytdlp_worker = Some(ytdlp_worker);
        self.download_semaphore = download_semaphore;
        self
    }

    #[cfg(test)]
    #[must_use]
    pub fn current_weight(&self) -> f64 {
        self.throughput_ewma.current()
    }

    pub async fn run_until_shutdown(
        &self,
        mut shutdown: tokio::sync::watch::Receiver<bool>,
    ) -> Result<()> {
        let mut interval = tokio::time::interval(Duration::from_secs(60));
        loop {
            if *shutdown.borrow_and_update() {
                break;
            }
            tokio::select! {
                _ = interval.tick() => {
                    if let Err(e) = self.check_and_poll().await {
                        tracing::error!(error = %e, "Scheduler check_and_poll failed");
                    }
                }
                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow_and_update() {
                        break;
                    }
                }
            }
        }
        self.shutdown_tasks(Duration::from_secs(10)).await;
        Ok(())
    }

    async fn reap_finished_tasks(&self) {
        let mut tasks = self.tasks.lock().await;
        while tasks.try_join_next().is_some() {}
    }

    pub async fn shutdown_tasks(&self, grace_period: Duration) {
        let mut tasks = self.tasks.lock().await;
        let drained = tokio::time::timeout(grace_period, async {
            while tasks.join_next().await.is_some() {}
        })
        .await;
        if drained.is_err() {
            tasks.abort_all();
            while tasks.join_next().await.is_some() {}
        }
    }

    pub async fn check_and_poll(&self) -> Result<()> {
        self.reap_finished_tasks().await;
        let now = now_utc();
        if !self.throughput_ewma.is_seeded()
            && let Ok(persisted) = self.db.sync().get_peer_weight(SERVER_PEER_ID).await
        {
            self.throughput_ewma.seed(persisted);
        }
        if self.throughput_ewma.is_seeded() {
            let current_weight = self.throughput_ewma.current();
            let _ = self
                .db
                .sync()
                .upsert_peer_node(
                    SERVER_PEER_ID,
                    "Roche Headless Peer",
                    NetworkTier::Wifi,
                    current_weight,
                    now,
                )
                .await;
        }
        let active_peers = self.db.sync().list_active_peers(now).await.unwrap_or_default();
        let feeds = self.db.feeds().get_active_for_cooperative_polling().await?;
        let mut due_feeds = Vec::new();
        for feed in feeds {
            if !should_peer_poll_feed(SERVER_PEER_ID, &active_peers, &feed, now) {
                continue;
            }
            let Some(poll_guard) = self.feed_poll_locks.try_acquire(&feed.id) else {
                continue;
            };
            due_feeds.push((feed, poll_guard));
        }

        let boards = self.db.boards().get_active_for_cooperative_polling().await?;
        let mut due_boards = Vec::new();
        for board in boards {
            if !should_peer_poll_feed(SERVER_PEER_ID, &active_peers, &board, now) {
                continue;
            }
            let lock_key = board_poll_lock_key(&board.id);
            let Some(poll_guard) = self.feed_poll_locks.try_acquire(&lock_key) else {
                continue;
            };
            due_boards.push((board, poll_guard));
        }

        if due_feeds.is_empty() && due_boards.is_empty() {
            return Ok(());
        }

        let mut tasks = self.tasks.lock().await;
        for (feed, poll_guard) in due_feeds {
            self.spawn_feed_poll_task(&mut tasks, feed, poll_guard);
        }
        for (board, poll_guard) in due_boards {
            self.spawn_board_poll_task(&mut tasks, board, poll_guard);
        }

        Ok(())
    }

    fn spawn_board_poll_task(
        &self,
        tasks: &mut tokio::task::JoinSet<()>,
        board: Board,
        poll_guard: FeedPollGuard,
    ) {
        let db = self.db.clone();
        let fetcher = Arc::clone(&self.fetcher);
        let semaphore = Arc::clone(&self.semaphore);
        let throughput_ewma = self.throughput_ewma.clone();

        tasks.spawn(async move {
            let (poll_res, elapsed_secs) = {
                let Ok(_permit) = semaphore.acquire_owned().await else {
                    drop(poll_guard);
                    return;
                };
                let started_at = Instant::now();
                let res = BoardIngester::poll_and_record(
                    &db,
                    fetcher.as_ref(),
                    &board,
                    "scheduled",
                    false,
                )
                .await;
                (res, started_at.elapsed().as_secs_f64())
            };

            if let Ok(outcome) = poll_res {
                let sample_weight = compute_throughput_weight(1, outcome.item_count, elapsed_secs);
                let _ = throughput_ewma.record_sample(sample_weight);
            }

            // Board polling NEVER invokes media ingestion (`yt-dlp` / PDF downloads).
            drop(poll_guard);
        });
    }

    fn spawn_feed_poll_task(
        &self,
        tasks: &mut tokio::task::JoinSet<()>,
        feed: Feed,
        poll_guard: FeedPollGuard,
    ) {
        let db = self.db.clone();
        let fetcher = Arc::clone(&self.fetcher);
        let media_store = self.media_store.clone();
        let ytdlp_worker = self.ytdlp_worker.clone();
        let download_semaphore = Arc::clone(&self.download_semaphore);
        let semaphore = Arc::clone(&self.semaphore);
        let conditional_cache = self.conditional_cache.clone();
        let throughput_ewma = self.throughput_ewma.clone();

        tasks.spawn(async move {
            let (poll_res, elapsed_secs) = {
                let Ok(_permit) = semaphore.acquire_owned().await else {
                    drop(poll_guard);
                    return;
                };
                let started_at = Instant::now();
                let res = FeedIngester::poll_xml_and_record_outcome_with_cache(
                    &db,
                    fetcher.as_ref(),
                    &feed,
                    "scheduled",
                    false,
                    Some(&conditional_cache),
                )
                .await;
                (res, started_at.elapsed().as_secs_f64())
            };

            if let Ok(ref outcome) = poll_res
                && !outcome.unchanged
            {
                let sample_weight =
                    compute_throughput_weight(1, outcome.parsed_entries, elapsed_secs);
                let _ = throughput_ewma.record_sample(sample_weight);
            }

            // Release the per-feed poll lock before potentially long-running media downloads
            // (e.g. yt-dlp) so subsequent polls or manual refreshes are not blocked.
            drop(poll_guard);

            if let Ok(outcome) = poll_res
                && let Some(ref store) = media_store
            {
                let ctx = MediaIngestContext {
                    db: &db,
                    media_store: store,
                    ytdlp_worker: ytdlp_worker.as_ref(),
                    download_semaphore: Some(&download_semaphore),
                    fetcher: fetcher.as_ref(),
                };
                FeedIngester::ingest_items_concurrently(&ctx, &outcome.inserted).await;
            }
        });
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use url::Url;

    use super::*;
    use crate::{db::Db, domain::FeedType};

    #[tokio::test]
    async fn test_throughput_ewma_convergence_failure_exclusion_and_seeding_persistence() {
        struct SelectiveFetcher {
            ok_url: String,
            xml: String,
        }
        impl FeedFetcher for SelectiveFetcher {
            fn fetch_feed(&self, url: &Url) -> impl std::future::Future<Output = Result<String>> {
                let res = if url.as_str() == self.ok_url {
                    Ok(self.xml.clone())
                } else {
                    Err(anyhow::anyhow!("simulated upstream failure"))
                };
                std::future::ready(res)
            }
        }

        let ewma = ThroughputEwma::new();
        assert!((ewma.current() - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9);
        let w1 = ewma.record_sample(10.0);
        assert!((w1 - 2.8).abs() < 1e-9);
        let w2 = ewma.record_sample(10.0);
        assert!((w2 - 4.24).abs() < 1e-9);
        let w3 = ewma.record_sample(f64::NAN);
        assert!((w3 - 4.24).abs() < 1e-9);

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let fail_url = Url::parse("https://example.com/fail-only.xml").unwrap();
        let fail_feed = db.feeds().create(FeedType::Rss, &fail_url, None, None).await.unwrap();

        let fetcher = Arc::new(SelectiveFetcher {
            ok_url: "https://example.com/ok-only.xml".to_owned(),
            xml: r#"<?xml version="1.0" encoding="UTF-8" ?>
            <rss version="2.0"><channel><title>OK</title>
                <item><title>Post</title><link>https://example.com/ok-1</link><description>Body</description></item>
            </channel></rss>"#
                .to_owned(),
        });
        let scheduler = PollScheduler::with_arc_fetcher(db.clone(), Arc::clone(&fetcher));
        scheduler.check_and_poll().await.unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;

        assert!(
            (scheduler.current_weight() - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9,
            "Failed feed must not modify throughput EWMA"
        );
        let updated_fail = db.feeds().get(&fail_feed.id).await.unwrap().unwrap();
        assert_eq!(updated_fail.consecutive_failures, 1);

        let ok_url = Url::parse("https://example.com/ok-only.xml").unwrap();
        let _ok_feed = db.feeds().create(FeedType::Rss, &ok_url, None, None).await.unwrap();
        scheduler.check_and_poll().await.unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        let in_mem_weight = scheduler.current_weight();
        assert!(
            in_mem_weight > DEFAULT_ONLINE_PEER_WEIGHT,
            "Fast succeeded feed should advance EWMA above 1.0, got {in_mem_weight}"
        );
        let persisted_before_tick =
            db.sync().get_peer_weight(SERVER_PEER_ID).await.unwrap().unwrap();
        assert!((persisted_before_tick - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9);

        scheduler.check_and_poll().await.unwrap();
        let persisted_after_tick =
            db.sync().get_peer_weight(SERVER_PEER_ID).await.unwrap().unwrap();
        assert!((persisted_after_tick - in_mem_weight).abs() < 1e-9);

        let restarted = PollScheduler::with_arc_fetcher(db.clone(), fetcher);
        assert!((restarted.current_weight() - DEFAULT_ONLINE_PEER_WEIGHT).abs() < 1e-9);
        restarted.check_and_poll().await.unwrap();
        assert!(
            (restarted.current_weight() - persisted_after_tick).abs() < 1e-9,
            "Restarted scheduler must seed EWMA from persisted peer_nodes.weight"
        );

        let (tx, rx) = tokio::sync::watch::channel(false);
        let handle = tokio::spawn(async move { restarted.run_until_shutdown(rx).await });
        tx.send(true).unwrap();
        let res = tokio::time::timeout(Duration::from_secs(2), handle).await.unwrap().unwrap();
        assert!(res.is_ok());
    }

    #[tokio::test]
    async fn test_feed_poll_locks_deduplication_and_release_before_media_download() {
        struct SlowPdfFetcher {
            pdf_started: Arc<tokio::sync::Notify>,
            pdf_release: Arc<tokio::sync::Notify>,
        }
        impl FeedFetcher for SlowPdfFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                std::future::ready(Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                <rss version="2.0"><channel><title>Papers</title>
                    <item><title>Paper</title><link>https://example.com/slow.pdf</link><description>PDF</description></item>
                </channel></rss>"#
                    .to_owned()))
            }

            fn fetch_pdf(
                &self,
                _url: &Url,
            ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>>> {
                let started = Arc::clone(&self.pdf_started);
                let release = Arc::clone(&self.pdf_release);
                async move {
                    started.notify_one();
                    release.notified().await;
                    Ok(Some(b"%PDF-1.5\n%slow\n%%EOF".to_vec()))
                }
            }
        }

        let locks = FeedPollLocks::new();
        let g1 = locks.try_acquire("feed-1").expect("initial try_acquire must succeed");
        assert!(locks.try_acquire("feed-1").is_none(), "concurrent try_acquire must return None");
        assert!(locks.try_acquire("feed-2").is_some(), "distinct feed must not be blocked");

        let locks_clone = locks.clone();
        let waiter = tokio::spawn(async move {
            let _g2 = locks_clone.acquire("feed-1").await;
        });
        tokio::task::yield_now().await;
        assert!(!waiter.is_finished(), "acquire().await must wait while guard is held");
        drop(g1);
        tokio::time::timeout(Duration::from_secs(2), waiter).await.unwrap().unwrap();
        assert_eq!(locks.tracked_len(), 0, "Weak lock map must prune dropped entries cleanly");

        let pdf_started = Arc::new(tokio::sync::Notify::new());
        let pdf_release = Arc::new(tokio::sync::Notify::new());

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/slow-pdf-feed.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let temp_dir =
            std::env::temp_dir().join(format!("roche-lock-release-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_dir);
        let ytdlp = YtDlpWorker::new(crate::config::MediaConfig::default(), store.clone());
        let fetcher = Arc::new(SlowPdfFetcher {
            pdf_started: Arc::clone(&pdf_started),
            pdf_release: Arc::clone(&pdf_release),
        });
        let shared_locks = FeedPollLocks::new();
        let scheduler = PollScheduler::with_arc_fetcher(db.clone(), fetcher)
            .with_feed_poll_locks(shared_locks.clone())
            .with_media(store, ytdlp, Arc::new(Semaphore::new(2)));

        scheduler.check_and_poll().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), pdf_started.notified()).await.unwrap();

        let lock_during_media = shared_locks.try_acquire(&feed.id);
        assert!(
            lock_during_media.is_some(),
            "PollScheduler must release per-feed lock before slow media downloads"
        );
        drop(lock_during_media);

        pdf_release.notify_one();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[tokio::test]
    async fn test_scheduler_does_not_increment_active_refresh_count_while_manual_and_refresh_all_do()
     {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct BarrierFetcher {
            calls: Arc<AtomicUsize>,
            entered: Arc<tokio::sync::Notify>,
            release: Arc<tokio::sync::Notify>,
        }
        impl FeedFetcher for BarrierFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                let calls = Arc::clone(&self.calls);
                let entered = Arc::clone(&self.entered);
                let release = Arc::clone(&self.release);
                async move {
                    calls.fetch_add(1, Ordering::SeqCst);
                    entered.notify_one();
                    release.notified().await;
                    Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0"><channel><title>Barrier Feed</title>
                        <item><title>Item</title><link>https://example.com/b1</link><description>Hi</description></item>
                    </channel></rss>"#
                        .to_owned())
                }
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/banner-test.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let calls = Arc::new(AtomicUsize::new(0));
        let entered = Arc::new(tokio::sync::Notify::new());
        let release = Arc::new(tokio::sync::Notify::new());
        let fetcher = BarrierFetcher {
            calls: Arc::clone(&calls),
            entered: Arc::clone(&entered),
            release: Arc::clone(&release),
        };
        let service = crate::service::RocheService::new(
            db.clone(),
            fetcher,
            crate::config::Config::default(),
        );
        let scheduler = PollScheduler::with_arc_fetcher(db.clone(), Arc::new(service.fetcher()))
            .with_feed_poll_locks(service.feed_poll_locks())
            .with_poll_semaphore(service.poll_semaphore())
            .with_media(
                service.media_store(),
                service.ytdlp_worker(),
                service.download_semaphore(),
            );

        scheduler.check_and_poll().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert_eq!(service.active_refresh_count(), 0);
        assert!(!service.is_refreshing());
        assert_eq!(calls.load(Ordering::SeqCst), 1);

        service.feeds().poll_feeds_concurrently(vec![feed.clone()]).await;
        assert_eq!(calls.load(Ordering::SeqCst), 1, "Refresh All must skip feed already mid-poll");

        let service_for_manual = service.clone();
        let feed_id = feed.id.clone();
        let manual_task =
            tokio::spawn(async move { service_for_manual.feeds().refresh(&feed_id).await });
        for _ in 0..50 {
            if service.active_refresh_count() > 0 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(service.active_refresh_count(), 1);
        assert_eq!(calls.load(Ordering::SeqCst), 1, "Manual refresh must wait on in-flight lock");

        release.notify_one();
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        release.notify_one();

        manual_task.await.unwrap().unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        assert_eq!(service.active_refresh_count(), 0);

        let count = service.feeds().refresh_all_in_background().await.unwrap();
        assert_eq!(count, 1);
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert!(
            service.is_refreshing() && service.active_refresh_count() == 1,
            "refresh_all_in_background must keep ActiveRefreshGuard active"
        );
        release.notify_one();
        for _ in 0..50 {
            if !service.is_refreshing() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(service.active_refresh_count(), 0);
    }

    #[tokio::test]
    async fn test_manual_refresh_bypasses_backoff_increments_on_failure_and_resets_on_success() {
        use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

        struct ToggleFetcher {
            should_fail: Arc<AtomicBool>,
            calls: Arc<AtomicUsize>,
        }
        impl FeedFetcher for ToggleFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                let res = if self.should_fail.load(Ordering::SeqCst) {
                    Err(anyhow::anyhow!("upstream HTTP 503"))
                } else {
                    Ok(r#"<?xml version="1.0" encoding="UTF-8" ?>
                    <rss version="2.0"><channel><title>Recovered Feed</title>
                        <item><title>Fresh Post</title><link>https://example.com/fresh-1</link><description>Back online</description></item>
                    </channel></rss>"#
                        .to_owned())
                };
                std::future::ready(res)
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/backoff-manual.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        db.feeds().mark_error(&feed.id, "HTTP 500").await.unwrap();
        db.feeds().mark_error(&feed.id, "HTTP 500").await.unwrap();
        let backed_off = db.feeds().get(&feed.id).await.unwrap().unwrap();
        assert_eq!(backed_off.consecutive_failures, 2);
        assert!(
            !should_peer_poll_feed(SERVER_PEER_ID, &[], &backed_off, now_utc()),
            "Scheduled poller must keep feed in backoff before due_at"
        );

        let should_fail = Arc::new(AtomicBool::new(true));
        let calls = Arc::new(AtomicUsize::new(0));
        let service = crate::service::RocheService::new(
            db.clone(),
            ToggleFetcher { should_fail: Arc::clone(&should_fail), calls: Arc::clone(&calls) },
            crate::config::Config::default(),
        );

        let err = service.feeds().refresh(&feed.id).await.unwrap_err();
        assert!(matches!(err, crate::service::AppError::BadGateway(_)));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        let after_manual_fail = db.feeds().get(&feed.id).await.unwrap().unwrap();
        assert_eq!(after_manual_fail.consecutive_failures, 3);
        assert!(after_manual_fail.last_error.is_some());
        assert_eq!(after_manual_fail.last_error_by.as_deref(), Some(SERVER_PEER_ID));

        should_fail.store(false, Ordering::SeqCst);
        let recovered = service.feeds().refresh(&feed.id).await.unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        assert_eq!(recovered.consecutive_failures, 0);
        assert!(recovered.last_error.is_none());
        assert!(recovered.last_error_by.is_none());
        let items = db.items().list(None, Some(&feed.id), None).await.unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "Fresh Post");
    }

    #[tokio::test]
    #[allow(clippy::significant_drop_tightening)]
    async fn test_spawn_feed_poll_task_drops_guards_when_aborted_before_first_poll() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct CountingFetcher {
            calls: Arc<AtomicUsize>,
        }

        impl FeedFetcher for CountingFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                std::future::ready(Ok(String::new()))
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/abort-before-poll.xml").unwrap();
        let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let fetch_calls = Arc::new(AtomicUsize::new(0));
        let shared_locks = FeedPollLocks::new();
        let scheduler = PollScheduler::with_arc_fetcher(
            db,
            Arc::new(CountingFetcher { calls: Arc::clone(&fetch_calls) }),
        )
        .with_feed_poll_locks(shared_locks.clone());

        let feed_id = feed.id.clone();
        let poll_guard =
            shared_locks.try_acquire(&feed_id).expect("initial feed lock acquire must succeed");
        assert!(shared_locks.try_acquire(&feed_id).is_none());

        let mut tasks = tokio::task::JoinSet::new();
        scheduler.spawn_feed_poll_task(&mut tasks, feed, poll_guard);
        tasks.abort_all();
        while tasks.join_next().await.is_some() {}

        assert_eq!(fetch_calls.load(Ordering::SeqCst), 0);
        let reacquired = shared_locks.try_acquire(&feed_id);
        assert!(reacquired.is_some(), "poll_guard must be dropped when unpolled task is aborted");
        drop(reacquired);
        assert_eq!(shared_locks.tracked_len(), 0);
    }

    #[tokio::test]
    async fn test_run_until_shutdown_exits_immediately_when_already_true() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        struct CountingFetcher {
            calls: Arc<AtomicUsize>,
        }

        impl FeedFetcher for CountingFetcher {
            fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = Result<String>> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                std::future::ready(Ok(String::new()))
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let feed_url = Url::parse("https://example.com/pre-shutdown.xml").unwrap();
        db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let fetch_calls = Arc::new(AtomicUsize::new(0));
        let scheduler = PollScheduler::with_arc_fetcher(
            db,
            Arc::new(CountingFetcher { calls: Arc::clone(&fetch_calls) }),
        );
        let (tx, rx) = tokio::sync::watch::channel(true);
        let res =
            tokio::time::timeout(Duration::from_secs(1), scheduler.run_until_shutdown(rx)).await;
        assert!(res.is_ok(), "shutdown loop must exit immediately when shutdown starts as true");
        assert!(res.unwrap().is_ok());
        drop(tx);
        assert_eq!(fetch_calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn test_scheduler_polls_due_boards_via_hrw_without_media_downloads() {
        use std::sync::atomic::{AtomicUsize, Ordering};

        use roche_core::boards::{BoardSourceSpec, HackerNewsSection};

        struct BoardAndFeedFetcher {
            board_list_calls: Arc<AtomicUsize>,
            pdf_calls: Arc<AtomicUsize>,
        }

        impl FeedFetcher for BoardAndFeedFetcher {
            fn fetch_feed(&self, url: &Url) -> impl std::future::Future<Output = Result<String>> {
                let s = url.as_str().to_owned();
                let list_calls = Arc::clone(&self.board_list_calls);
                async move {
                    if s == HackerNewsSection::Top.api_stories_url() {
                        list_calls.fetch_add(1, Ordering::SeqCst);
                        Ok("[5001, 5002]".to_owned())
                    } else if s.ends_with("/item/5001.json") {
                        Ok(r#"{"id":5001,"type":"story","title":"Attention Paper","url":"https://arxiv.org/abs/2401.00001","by":"alice","score":420,"descendants":99,"time":1732300000}"#.to_owned())
                    } else if s.ends_with("/item/5002.json") {
                        Ok(r#"{"id":5002,"type":"story","title":"Systems Talk","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","by":"bob","score":120,"descendants":31,"time":1732300100}"#.to_owned())
                    } else if s == "https://example.com/rss-feed.xml" {
                        Ok(r#"<?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0"><channel><title>Regular Feed</title>
                            <item><title>Feed Entry</title><link>https://example.com/post-1</link><description>Hi</description></item>
                        </channel></rss>"#
                            .to_owned())
                    } else {
                        Err(anyhow::anyhow!("unexpected url: {s}"))
                    }
                }
            }

            fn fetch_pdf(
                &self,
                _url: &Url,
            ) -> impl std::future::Future<Output = Result<Option<Vec<u8>>>> {
                self.pdf_calls.fetch_add(1, Ordering::SeqCst);
                std::future::ready(Ok(Some(b"%PDF-1.5\n%%EOF".to_vec())))
            }
        }

        let db = Db::connect("sqlite::memory:").await.unwrap();
        let top_spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Top);
        let active_board = db.boards().create(&top_spec, None, None, 30).await.unwrap();
        let ask_spec = BoardSourceSpec::for_hacker_news(HackerNewsSection::Ask);
        let archived_board = db.boards().create(&ask_spec, None, None, 30).await.unwrap();
        db.boards().archive(&archived_board.id).await.unwrap();

        let feed_url = Url::parse("https://example.com/rss-feed.xml").unwrap();
        db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();

        let board_list_calls = Arc::new(AtomicUsize::new(0));
        let pdf_calls = Arc::new(AtomicUsize::new(0));
        let temp_dir =
            std::env::temp_dir().join(format!("roche-board-sched-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_dir);
        let ytdlp = YtDlpWorker::new(crate::config::MediaConfig::default(), store.clone());

        let scheduler = PollScheduler::with_arc_fetcher(
            db.clone(),
            Arc::new(BoardAndFeedFetcher {
                board_list_calls: Arc::clone(&board_list_calls),
                pdf_calls: Arc::clone(&pdf_calls),
            }),
        )
        .with_media(store, ytdlp, Arc::new(Semaphore::new(2)));

        scheduler.check_and_poll().await.unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;

        assert_eq!(board_list_calls.load(Ordering::SeqCst), 1);
        assert_eq!(
            pdf_calls.load(Ordering::SeqCst),
            0,
            "board polling must NEVER download PDFs or invoke media ingestion"
        );

        let polled = db.boards().get(&active_board.id).await.unwrap().unwrap();
        assert!(polled.last_polled_at.is_some());
        assert!(polled.snapshot_fetched_at.is_some());
        assert_eq!(polled.item_count, 2);

        let still_archived = db.boards().get(&archived_board.id).await.unwrap().unwrap();
        assert!(still_archived.last_polled_at.is_none());
        assert_eq!(still_archived.item_count, 0);

        // Entries table must contain only the RSS feed entry, never unpromoted board items.
        let all_items = db.items().list(None, None, None).await.unwrap();
        assert_eq!(all_items.len(), 1);
        assert_eq!(all_items[0].title, "Feed Entry");

        // Second tick immediately after polling must skip the board (not due yet under HRW).
        scheduler.check_and_poll().await.unwrap();
        scheduler.shutdown_tasks(Duration::from_secs(2)).await;
        assert_eq!(board_list_calls.load(Ordering::SeqCst), 1);

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
