use std::{sync::Arc, time::Duration};

use url::Url;

use super::{ActiveRefreshGuard, AppError, RocheService};
use crate::{
    content::HtmlSanitizer,
    db::Feed,
    domain::{FeedType, OpmlOutline},
    feeds::{FeedSource, OpmlDocument},
    ingest::{FeedFetcher, FeedIngester, PollError},
    util::{
        media_urls::{is_arxiv_host, is_youtube_feed_url},
        url_guard::{SsrfBlockedError, validate_public_url},
    },
};

pub struct FeedService<'a> {
    pub(super) service: &'a RocheService,
}

impl FeedService<'_> {
    pub async fn list(&self) -> Result<Vec<Feed>, AppError> {
        Ok(self.service.db.feeds().list().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Feed, AppError> {
        self.service.db.feeds().get(id).await?.ok_or_else(|| AppError::not_found("Feed not found"))
    }

    pub fn parse_source(raw: &str) -> Result<FeedSource, AppError> {
        FeedSource::parse(raw).map_err(|err| {
            if err.downcast_ref::<SsrfBlockedError>().is_some() {
                AppError::ssrf_blocked(format!("Invalid feed URL: {err}"))
            } else {
                AppError::bad_request(format!("Invalid feed URL: {err}"))
            }
        })
    }

    pub async fn create(
        &self,
        feed_type: FeedType,
        source: FeedSource,
        title: Option<&str>,
        description: Option<&str>,
    ) -> Result<Feed, AppError> {
        let (initial_type, validated_url) = source.into_feed_url(feed_type).map_err(|e| {
            if e.downcast_ref::<SsrfBlockedError>().is_some() {
                AppError::ssrf_blocked(format!("Invalid feed URL: {e}"))
            } else {
                AppError::bad_request(format!("Invalid feed URL: {e}"))
            }
        })?;
        validate_public_url(&validated_url)
            .map_err(|e| AppError::ssrf_blocked(format!("Invalid feed URL: {e}")))?;

        let (resolved_type, final_url) =
            self.discover_feed_url_if_needed(initial_type, validated_url).await?;

        let feed = self
            .service
            .db
            .feeds()
            .create_with_interval(
                resolved_type,
                &final_url,
                title.map(str::trim).filter(|t| !t.is_empty()),
                description.map(str::trim).filter(|d| !d.is_empty()),
                self.service.config.feeds.default_poll_interval,
            )
            .await?;

        Ok(feed)
    }

    async fn discover_feed_url_if_needed(
        &self,
        initial_type: FeedType,
        validated_url: Url,
    ) -> Result<(FeedType, Url), AppError> {
        let fallback_url = validated_url.clone();
        tokio::time::timeout(
            Duration::from_secs(15),
            self.discover_feed_url_inner(initial_type, validated_url),
        )
        .await
        .unwrap_or_else(|_| {
            tracing::warn!(url = %fallback_url, "Feed discovery timed out after 15s; using submitted URL");
            Ok((initial_type, fallback_url))
        })
    }

    async fn discover_feed_url_inner(
        &self,
        initial_type: FeedType,
        validated_url: Url,
    ) -> Result<(FeedType, Url), AppError> {
        if is_youtube_feed_url(&validated_url) || is_arxiv_host(validated_url.host_str()) {
            return Ok((initial_type, validated_url));
        }

        let probe_type = match initial_type {
            FeedType::Youtube => FeedType::Rss,
            other => other,
        };

        let mut html_candidate = if let Ok(Ok(raw_body)) = tokio::time::timeout(
            Duration::from_secs(8),
            self.service.fetcher.fetch_feed(&validated_url),
        )
        .await
        {
            let body_clone = raw_body.clone();
            let parsed_ok = tokio::task::spawn_blocking(move || {
                FeedIngester::parse_feed_content(probe_type, &body_clone).is_ok()
            })
            .await
            .unwrap_or(false);
            if parsed_ok {
                return Ok((initial_type, validated_url));
            }
            Some(raw_body)
        } else {
            None
        };

        if html_candidate.is_none()
            && let Ok(Ok(Some(fetched_html))) = tokio::time::timeout(
                Duration::from_secs(8),
                self.service.fetcher.fetch_html(&validated_url),
            )
            .await
        {
            html_candidate = Some(fetched_html);
        }

        let Some(raw_html) = html_candidate else {
            return Ok((initial_type, validated_url));
        };

        let base_clone = validated_url.clone();
        let html_for_disc = raw_html.clone();
        let candidates = tokio::task::spawn_blocking(move || {
            HtmlSanitizer::discover_feed_candidates(&html_for_disc, &base_clone)
        })
        .await
        .unwrap_or_default();

        for cand in candidates {
            if is_youtube_feed_url(&cand) {
                return Ok((FeedType::Youtube, cand));
            }
            if let Ok(Ok(cand_body)) =
                tokio::time::timeout(Duration::from_secs(6), self.service.fetcher.fetch_feed(&cand))
                    .await
            {
                let cand_parse_type = match initial_type {
                    FeedType::Podcast => FeedType::Podcast,
                    _ => FeedType::Rss,
                };
                let ok = tokio::task::spawn_blocking(move || {
                    FeedIngester::parse_feed_content(cand_parse_type, &cand_body).is_ok()
                })
                .await
                .unwrap_or(false);
                if ok {
                    let final_type = match initial_type {
                        FeedType::Podcast => FeedType::Podcast,
                        _ => FeedType::Rss,
                    };
                    return Ok((final_type, cand));
                }
            }
        }

        let lower_prefix =
            raw_html.trim_start().get(..256).unwrap_or(&raw_html).to_ascii_lowercase();
        if lower_prefix.contains("<!doctype html") || lower_prefix.contains("<html") {
            return Err(AppError::bad_request(format!(
                "No RSS or Atom feed found at {validated_url}"
            )));
        }

        Ok((initial_type, validated_url))
    }

    pub async fn update(
        &self,
        id: &str,
        title: Option<&str>,
        poll_interval: Option<i64>,
        is_active: Option<bool>,
        feed_type: Option<FeedType>,
        url: Option<&str>,
    ) -> Result<Feed, AppError> {
        let clean_title = match title {
            Some(t) => {
                let trimmed = t.trim();
                if trimmed.is_empty() {
                    return Err(AppError::bad_request("Feed title cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };

        if let Some(interval) = poll_interval {
            let min = self.service.config.feeds.min_poll_interval;
            let max = self.service.config.feeds.max_poll_interval;
            if !(min..=max).contains(&interval) {
                return Err(AppError::bad_request(format!(
                    "poll_interval must be between {min} and {max} minutes"
                )));
            }
        }

        let repo = self.service.db.feeds();
        let existing =
            if feed_type.is_some() || url.is_some() { Some(self.get(id).await?) } else { None };

        if let Some(target_type) = feed_type
            && let Some(ref existing) = existing
            && existing.feed_type != target_type
        {
            let valid_toggle = matches!(
                (existing.feed_type, target_type),
                (FeedType::Rss, FeedType::Podcast) | (FeedType::Podcast, FeedType::Rss)
            );
            if !valid_toggle {
                return Err(AppError::bad_request(
                    "Only RSS and Podcast feed types can be toggled between each other",
                ));
            }
        }

        let validated_url = if let Some(raw_url) = url {
            let trimmed_url = raw_url.trim();
            if trimmed_url.is_empty() {
                return Err(AppError::bad_request("Feed URL cannot be empty"));
            }
            let existing =
                existing.as_ref().ok_or_else(|| AppError::not_found("Feed not found"))?;
            if trimmed_url == existing.url.as_str() {
                None
            } else {
                let effective_type = feed_type.unwrap_or(existing.feed_type);
                let source = Self::parse_source(trimmed_url)?;
                let (resolved_type, parsed_url) =
                    source.into_feed_url(effective_type).map_err(|e| {
                        if e.downcast_ref::<SsrfBlockedError>().is_some() {
                            AppError::ssrf_blocked(format!("Invalid feed URL: {e}"))
                        } else {
                            AppError::bad_request(format!("Invalid feed URL: {e}"))
                        }
                    })?;
                validate_public_url(&parsed_url)
                    .map_err(|e| AppError::ssrf_blocked(format!("Invalid feed URL: {e}")))?;
                if resolved_type != effective_type {
                    return Err(AppError::bad_request(format!(
                        "Feed URL resolves to a {resolved_type:?} feed, which does not match this {effective_type:?} feed"
                    )));
                }
                if effective_type == FeedType::Youtube && !is_youtube_feed_url(&parsed_url) {
                    return Err(AppError::bad_request(
                        "YouTube feed URL must be a channel ID, /channel/UC... URL, or youtube.com/feeds/videos.xml URL",
                    ));
                }
                if parsed_url.as_str() == existing.url.as_str() { None } else { Some(parsed_url) }
            }
        } else {
            None
        };

        if !repo
            .update(id, clean_title, poll_interval, is_active, feed_type, validated_url.as_ref())
            .await?
        {
            return Err(AppError::not_found("Feed not found"));
        }

        self.get(id).await
    }

    pub async fn archive(&self, id: &str) -> Result<Feed, AppError> {
        if !self.service.db.feeds().archive(id).await? {
            return Err(AppError::not_found("Feed not found"));
        }
        self.get(id).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<Feed, AppError> {
        if !self.service.db.feeds().unarchive(id).await? {
            return Err(AppError::not_found("Feed not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), AppError> {
        let repo = self.service.db.feeds();
        let media_list = self.service.db.media().list_exclusively_owned_by_feed(id).await?;

        if !repo.delete(id).await? {
            return Err(AppError::not_found("Feed not found"));
        }

        self.service.media().remove_media_files_and_rows(media_list).await?;
        Ok(())
    }

    pub async fn refresh(&self, id: &str) -> Result<Feed, AppError> {
        let _guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
        let poll_guard = self.service.feed_poll_locks.acquire(id).await;
        let feed = self.get(id).await?;
        let ctx = self.service.media_ingest_context();

        let poll_res = FeedIngester::poll_xml_and_record_outcome(
            ctx.db,
            ctx.fetcher,
            &feed,
            "manual_refresh",
            true,
        )
        .await;
        drop(poll_guard);

        match poll_res {
            Ok(outcome) => {
                FeedIngester::ingest_items_concurrently(&ctx, &outcome.inserted).await;
            }
            Err(err) => {
                return match err {
                    PollError::Upstream(upstream) => {
                        let safe_msg = crate::util::url_guard::sanitize_upstream_error(&upstream);
                        Err(AppError::bad_gateway(format!("Failed to refresh feed: {safe_msg}")))
                    }
                    PollError::Database(db_err) => Err(AppError::Database(db_err)),
                };
            }
        }

        self.get(id).await
    }

    pub async fn refresh_all_in_background(&self) -> Result<usize, AppError> {
        let active_feeds: Vec<Feed> =
            self.list().await?.into_iter().filter(|f| f.is_active).collect();
        let count = active_feeds.len();
        if !active_feeds.is_empty() {
            let service_bg = self.service.clone();
            self.service.spawn_background(async move {
                service_bg.feeds().poll_feeds_concurrently(active_feeds).await;
            });
        }
        Ok(count)
    }

    pub async fn import_opml(&self, opml_str: String) -> Result<Vec<Feed>, AppError> {
        let outlines = tokio::task::spawn_blocking(move || OpmlDocument::parse(&opml_str))
            .await
            .map_err(|e| anyhow::anyhow!("OPML parse task failed: {e}"))?;
        let default_interval = self.service.config.feeds.default_poll_interval;
        Ok(self.service.db.feeds().import_outlines(outlines, default_interval).await?)
    }

    pub async fn poll_feeds_concurrently(&self, feeds: Vec<Feed>) {
        if feeds.is_empty() {
            return;
        }
        let poll_sem = Arc::clone(&self.service.poll_semaphore);
        let mut join_set = tokio::task::JoinSet::new();

        for feed in feeds {
            let Some(poll_guard) = self.service.feed_poll_locks.try_acquire(&feed.id) else {
                continue;
            };
            let service_clone = self.service.clone();
            let p_sem = Arc::clone(&poll_sem);
            let refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);
            join_set.spawn(async move {
                let _task_guard = refresh_guard;
                let ctx = service_clone.media_ingest_context();
                let poll_res = {
                    let Ok(_permit) = p_sem.acquire_owned().await else {
                        drop(poll_guard);
                        return;
                    };
                    FeedIngester::poll_xml_and_record_outcome(
                        ctx.db,
                        ctx.fetcher,
                        &feed,
                        "background_refresh",
                        true,
                    )
                    .await
                };
                drop(poll_guard);
                if let Ok(outcome) = poll_res {
                    FeedIngester::ingest_items_concurrently(&ctx, &outcome.inserted).await;
                }
            });
        }
        while join_set.join_next().await.is_some() {}
    }

    pub async fn export_opml(&self) -> Result<String, AppError> {
        let feeds = self.list().await?;
        let outlines: Vec<OpmlOutline> = feeds
            .into_iter()
            .map(|f| OpmlOutline { feed_type: f.feed_type, xml_url: f.url, title: f.title })
            .collect();
        Ok(OpmlDocument::to_xml(&outlines))
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::collections::HashMap;

    use super::*;
    use crate::{config::Config, db::Db};

    struct MapFeedFetcher(HashMap<String, String>);

    impl FeedFetcher for MapFeedFetcher {
        fn fetch_feed(
            &self,
            url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            let res = self
                .0
                .get(url.as_str())
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("unexpected URL: {url}"));
            std::future::ready(res)
        }
    }

    #[tokio::test]
    async fn test_discover_feed_url_does_not_bypass_or_misclassify_spoofed_youtube_url() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let spoofed_page = "https://evil.example/page?q=youtube.com/feeds/videos.xml";
        let spoofed_cand = "https://evil.example/feed?q=youtube.com/feeds/videos.xml";
        let plain_html =
            "<!DOCTYPE html><html><head><title>Not a feed</title></head><body>Hi</body></html>";
        let html_with_spoofed_rss = format!(
            "<!DOCTYPE html><html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"{spoofed_cand}\"></head><body>Hi</body></html>"
        );
        let valid_rss = r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Evil Blog</title><link>https://evil.example/</link>
            <item><title>Post</title><link>https://evil.example/post</link></item></channel></rss>"#;

        // Case 1: Initial URL containing "youtube.com/feeds/videos.xml" in query must NOT bypass discovery
        let fetcher_plain =
            MapFeedFetcher(HashMap::from([(spoofed_page.to_owned(), plain_html.to_owned())]));
        let svc_plain = RocheService::new(db.clone(), fetcher_plain, Config::default());
        let err = svc_plain
            .feeds()
            .discover_feed_url_if_needed(FeedType::Rss, Url::parse(spoofed_page).unwrap())
            .await
            .unwrap_err();
        assert!(matches!(err, AppError::BadRequest(_)));

        // Case 2: Discovered candidate containing "youtube.com/feeds/videos.xml" in query must NOT
        // be misclassified as FeedType::Youtube; it is parsed as normal RSS instead.
        let fetcher_rss = MapFeedFetcher(HashMap::from([
            (spoofed_page.to_owned(), html_with_spoofed_rss),
            (spoofed_cand.to_owned(), valid_rss.to_owned()),
        ]));
        let svc_rss = RocheService::new(db, fetcher_rss, Config::default());
        let (feed_type, discovered_url) = svc_rss
            .feeds()
            .discover_feed_url_if_needed(FeedType::Rss, Url::parse(spoofed_page).unwrap())
            .await
            .unwrap();
        assert_eq!(feed_type, FeedType::Rss);
        assert_eq!(discovered_url.as_str(), spoofed_cand);
    }
}
