use std::{collections::HashSet, time::Duration};

use serde::Serialize;
use url::Url;

use super::{ActiveRefreshGuard, AppError, RocheService};
use crate::{
    content::{HtmlSanitizer, MAX_SUMMARY_CHARS, classify_url},
    db::{ConsumptionLogView, Db, Item, ItemFilter, ItemPage, ManualItemInput, Media, Tag},
    domain::{ArchiveBatchScope, ContentType, ItemStatus, READER_AUTO_READ_THRESHOLD, ReadAtPatch},
    feeds::ArxivParser,
    ingest::{FeedFetcher, FeedIngester},
    util::{
        clock::now_utc,
        media_urls::{extract_arxiv_id, is_valid_arxiv_id, youtube_thumbnail_url},
        url_guard::validate_public_url,
    },
};

#[derive(Debug, Clone, Serialize)]
pub struct ItemDetail {
    #[serde(flatten)]
    pub item: Item,
    pub tags: Vec<Tag>,
    pub media: Vec<Media>,
}

/// Outcome of atomically finishing a reader item (archived + `consumed` diary log).
#[derive(Debug, Clone)]
pub struct FinishedReaderItem {
    pub has_feed: bool,
    pub logged: ConsumptionLogView,
}

pub struct ItemService<'a> {
    pub(super) service: &'a RocheService,
}

impl<'a> ItemService<'a> {
    /// Starts a chainable query builder for filtering, searching, sorting, and paginating items.
    #[must_use]
    pub fn query(&self) -> ItemQuery<'a> {
        ItemQuery { db: &self.service.db, filter: ItemFilter::default() }
    }

    pub async fn get(&self, id: &str) -> Result<ItemDetail, AppError> {
        let item = self
            .service
            .db
            .items()
            .get(id)
            .await?
            .ok_or_else(|| AppError::not_found("Item not found"))?;
        let tags = self.service.db.tags().list_for_item(id).await?;
        let media = self.service.db.media().list_for_item(id).await?;
        Ok(ItemDetail { item, tags, media })
    }

    pub async fn existing_ids(&self, ids: &[String]) -> Result<HashSet<String>, AppError> {
        Ok(self.service.db.items().existing_ids(ids).await?)
    }

    pub async fn save(
        &self,
        url: &Url,
        title: Option<&str>,
        tags: &[String],
    ) -> Result<ItemDetail, AppError> {
        validate_public_url(url)
            .map_err(|e| AppError::ssrf_blocked(format!("Invalid item URL: {e}")))?;

        let arxiv_id = extract_arxiv_id(url.as_str());
        let content_type = if arxiv_id.is_some() { ContentType::Paper } else { classify_url(url) };
        let explicit_title = title.map(str::trim).filter(|t| !t.is_empty());
        let has_explicit_title = explicit_title.is_some();
        let initial_title = explicit_title.unwrap_or_else(|| url.as_str());
        let yt_thumb_fallback = youtube_thumbnail_url(url);
        let audio_url = (content_type == ContentType::Audio).then(|| url.as_str());

        let (item, _) = self
            .service
            .db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url,
                title: initial_title,
                summary: None,
                content_html: None,
                thumbnail_url: yt_thumb_fallback.as_deref(),
                audio_url,
                duration: None,
                content_type,
                tags,
            })
            .await?;

        let service_clone = self.service.clone();
        let item_id = item.id.clone();
        let item_url = url.clone();
        let refresh_guard = ActiveRefreshGuard::acquire(&self.service.active_refreshes);

        self.service.spawn_background(async move {
            let _guard = refresh_guard;
            Self::enrich_and_ingest_saved_item(
                &service_clone,
                &item_id,
                &item_url,
                content_type,
                arxiv_id.as_deref(),
                has_explicit_title,
            )
            .await;
        });

        self.get(&item.id).await
    }

    async fn enrich_and_ingest_saved_item(
        service: &RocheService,
        item_id: &str,
        item_url: &Url,
        content_type: ContentType,
        arxiv_id: Option<&str>,
        has_explicit_title: bool,
    ) {
        if let Some(aid) = arxiv_id
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
                    item_id,
                    (!clean_title.is_empty()).then_some(clean_title.as_str()),
                    &paper.authors,
                    paper.published_at,
                    (!clean_summary.is_empty()).then_some(clean_summary.as_str()),
                    None,
                    None,
                )
                .await;
        }

        let should_fetch_html = arxiv_id.is_none()
            && (content_type == ContentType::Article
                || (!has_explicit_title
                    && !matches!(content_type, ContentType::Pdf | ContentType::Audio)));
        if should_fetch_html {
            match tokio::time::timeout(
                Duration::from_secs(10),
                service.fetcher.fetch_html(item_url),
            )
            .await
            {
                Ok(Ok(Some(raw_html))) => {
                    let base_url = item_url.clone();
                    if let Ok(snapshot) = tokio::task::spawn_blocking(move || {
                        HtmlSanitizer::extract_snapshot_with_base(&raw_html, Some(&base_url))
                    })
                    .await
                    {
                        let body_html = (content_type == ContentType::Article)
                            .then_some(snapshot.content_html.as_str())
                            .filter(|h| !h.trim().is_empty());
                        let _ = service
                            .db
                            .items()
                            .apply_background_enrichment(
                                item_id,
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
                Ok(Ok(None)) => {}
                Ok(Err(e)) => {
                    tracing::warn!(url = %item_url, error = %e, "Failed to fetch HTML snapshot in background");
                }
                Err(_) => {
                    tracing::warn!(url = %item_url, "Timed out fetching HTML snapshot in background");
                }
            }
        }

        let ctx = service.media_ingest_context();
        FeedIngester::ingest_item_media(&ctx, item_id, content_type, item_url).await;
    }

    pub async fn update(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
        read_progress: Option<f64>,
        read_at: ReadAtPatch,
    ) -> Result<ItemDetail, AppError> {
        if let Some(p) = read_progress
            && (!p.is_finite() || !(0.0..=1.0).contains(&p))
        {
            return Err(AppError::bad_request("read_progress must be between 0.0 and 1.0"));
        }
        if !self.service.db.items().update(id, status, is_favorite, read_progress, read_at).await? {
            return Err(AppError::not_found("Item not found"));
        }
        self.get(id).await
    }

    /// Atomically archives/completes a reader item (`read_progress = 1.0`, `read_at = now`)
    /// and logs a `'consumed'` diary entry in a single transaction.
    pub async fn finish_reader_item(
        &self,
        id: &str,
        notes: Option<&str>,
    ) -> Result<FinishedReaderItem, AppError> {
        let Some((has_feed, logged)) =
            self.service.db.items().finish_reader_item(id, notes).await?
        else {
            return Err(AppError::not_found("Item not found"));
        };
        Ok(FinishedReaderItem { has_feed, logged })
    }

    /// Atomically unfinishes a reader item (deleting `consumed` logs, resetting progress/status,
    /// and restoring non-feed items to `Want to Read`).
    pub async fn unfinish_reader_item(&self, id: &str) -> Result<(), AppError> {
        if !self.service.db.items().unfinish_reader_item(id).await? {
            return Err(AppError::not_found("Item not found"));
        }
        Ok(())
    }

    /// Atomically toggles Starred status for an item and returns the new `is_favorite` value.
    pub async fn toggle_star(&self, id: &str) -> Result<bool, AppError> {
        self.service
            .db
            .items()
            .toggle_star(id)
            .await?
            .ok_or_else(|| AppError::not_found("Item not found"))
    }

    /// Persists clamped reading progress and marks `read_at` once progress reaches
    /// `READER_AUTO_READ_THRESHOLD`.
    pub async fn update_progress_with_auto_read(
        &self,
        id: &str,
        raw_progress: f64,
    ) -> Result<ItemDetail, AppError> {
        let p = if raw_progress.is_finite() { raw_progress.clamp(0.0, 1.0) } else { 0.0 };
        let patch = if p >= READER_AUTO_READ_THRESHOLD {
            ReadAtPatch::MarkRead(now_utc())
        } else {
            ReadAtPatch::Unchanged
        };
        self.update(id, None, None, Some(p), patch).await
    }

    /// Archives an item atomically while retaining at least `max(existing_progress, submitted_progress)`
    /// when a finite progress value is supplied.
    pub async fn archive_with_min_progress(
        &self,
        id: &str,
        submitted_progress: Option<f64>,
    ) -> Result<ItemDetail, AppError> {
        if !self.service.db.items().archive_with_min_progress(id, submitted_progress).await? {
            return Err(AppError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn archive(&self, id: &str) -> Result<ItemDetail, AppError> {
        if !self.service.db.items().archive(id).await? {
            return Err(AppError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn archive_batch(
        &self,
        item_ids: &[String],
        feed_id: Option<&str>,
        all_unread: bool,
    ) -> Result<u64, AppError> {
        let Some(scope) = ArchiveBatchScope::from_request(item_ids, feed_id, all_unread) else {
            return Err(AppError::bad_request(
                "Specify non-empty item_ids, feed_id, or all_unread: true",
            ));
        };
        if let Some(fid) = scope.feed_id() {
            let _ = self.service.feeds().get(fid).await?;
        }
        Ok(self.service.db.items().archive_batch(&scope).await?)
    }

    pub async fn unarchive(&self, id: &str) -> Result<ItemDetail, AppError> {
        if !self.service.db.items().unarchive(id).await? {
            return Err(AppError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), AppError> {
        let item_repo = self.service.db.items();
        let media_list = self.service.db.media().list_for_item(id).await?;
        let outcome = item_repo.delete_returning_outcome(id).await?;
        if !outcome.deleted {
            return Err(AppError::not_found("Item not found"));
        }

        if outcome.readable_content_removed {
            self.service.media().remove_media_files_and_rows(media_list).await?;
        }
        Ok(())
    }

    pub async fn add_tag(
        &self,
        item_id: &str,
        tag_id: Option<&str>,
        name: Option<&str>,
    ) -> Result<ItemDetail, AppError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(AppError::not_found("Item not found"));
        }

        if let Some(tid) = tag_id {
            let tag = self.service.tags().get(tid).await?;
            self.service.db.tags().add_to_item(item_id, &tag.id).await?;
        } else if let Some(name) = name {
            let trimmed = name.trim();
            if trimmed.is_empty() {
                return Err(AppError::bad_request("Tag name cannot be empty"));
            }
            self.service.db.tags().add_named_to_item(item_id, trimmed).await?;
        } else {
            return Err(AppError::bad_request("Either tag_id or name must be provided"));
        }

        self.get(item_id).await
    }

    pub async fn remove_tag(&self, item_id: &str, tag_id: &str) -> Result<(), AppError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(AppError::not_found("Item not found"));
        }
        let _ = self.service.db.tags().remove_from_item(item_id, tag_id).await?;
        Ok(())
    }

    pub async fn list_media(&self, item_id: &str) -> Result<Vec<Media>, AppError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(AppError::not_found("Item not found"));
        }
        Ok(self.service.db.media().list_for_item(item_id).await?)
    }
}

/// Fluent, chainable query builder for items (`service.items().query().with_filter(...).feed_id(...).list_page()`).
pub struct ItemQuery<'a> {
    db: &'a Db,
    filter: ItemFilter,
}

impl ItemQuery<'_> {
    #[must_use]
    pub fn with_filter(mut self, filter: ItemFilter) -> Self {
        self.filter = filter;
        self
    }

    #[must_use]
    pub fn feed_id(mut self, feed_id: impl Into<String>) -> Self {
        self.filter.feed_id = Some(feed_id.into());
        self
    }

    pub async fn list_page(self) -> Result<ItemPage, AppError> {
        Ok(self.db.items().list_filtered_page(&self.filter).await?)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::collections::HashMap;

    use super::*;
    use crate::config::Config;

    struct DummyItemFetcher;

    impl FeedFetcher for DummyItemFetcher {
        fn fetch_feed(
            &self,
            _url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            std::future::ready(Ok(String::new()))
        }
    }

    struct MapItemFetcher(HashMap<String, String>);

    impl FeedFetcher for MapItemFetcher {
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
    async fn test_item_service_existing_ids() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let service = RocheService::new(db.clone(), DummyItemFetcher, Config::default());
        let url = Url::parse("https://example.com/service-existing-id").unwrap();
        let (saved, _) = db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url: &url,
                title: "Service Existing Item",
                summary: None,
                content_html: Some("<p>body</p>"),
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                tags: &[],
            })
            .await
            .unwrap();

        let existing = service
            .items()
            .existing_ids(&[saved.id.clone(), "missing-item-id".to_owned()])
            .await
            .unwrap();
        assert_eq!(existing.len(), 1);
        assert!(existing.contains(&saved.id));
    }

    #[tokio::test]
    async fn test_finish_reader_item_atomically_archives_and_logs_consumption() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let service = RocheService::new(db.clone(), DummyItemFetcher, Config::default());
        let url = Url::parse("https://example.com/finish-atomically").unwrap();
        let (saved, _) = db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url: &url,
                title: "Finish Atomically",
                summary: None,
                content_html: Some("<p>body</p>"),
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                tags: &[],
            })
            .await
            .unwrap();

        let outcome =
            service.items().finish_reader_item(&saved.id, Some("Great read")).await.unwrap();
        assert!(!outcome.has_feed);
        assert_eq!(outcome.logged.entry.id, saved.id);
        assert_eq!(outcome.logged.notes.as_deref(), Some("Great read"));

        let updated = service.items().get(&saved.id).await.unwrap();
        assert_eq!(updated.item.status, ItemStatus::Archived);
        assert!((updated.item.read_progress - 1.0).abs() < f64::EPSILON);

        let missing_err = service.items().finish_reader_item("no-such-id", None).await.unwrap_err();
        assert!(matches!(missing_err, AppError::NotFound(_)));
    }

    #[tokio::test]
    async fn test_arxiv_save_enrichment_preserves_separate_authors_in_creators_json() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let paper_url = Url::parse("https://arxiv.org/abs/2402.54321").unwrap();
        let api_url = "https://export.arxiv.org/api/query?id_list=2402.54321";
        let arxiv_xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>ArXiv Query</title>
          <entry>
            <id>http://arxiv.org/abs/2402.54321v1</id>
            <title>Multi Author Paper</title>
            <summary>Abstract text.</summary>
            <author><name>Alice Smith</name></author>
            <author><name>Bob Jones</name></author>
            <link rel="alternate" type="text/html" href="https://arxiv.org/abs/2402.54321"/>
          </entry>
        </feed>"#;

        let fetcher = MapItemFetcher(HashMap::from([(api_url.to_owned(), arxiv_xml.to_owned())]));
        let service = RocheService::new(db.clone(), fetcher, Config::default());
        let (saved, _) = db
            .items()
            .save_manual_input_with_tags(&ManualItemInput {
                url: &paper_url,
                title: paper_url.as_str(),
                summary: None,
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Paper,
                tags: &[],
            })
            .await
            .unwrap();

        ItemService::enrich_and_ingest_saved_item(
            &service,
            &saved.id,
            &paper_url,
            ContentType::Paper,
            Some("2402.54321"),
            false,
        )
        .await;

        let creators_json: String =
            sqlx::query_scalar("SELECT creators_json FROM entries WHERE id = ?")
                .bind(&saved.id)
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(creators_json, r#"["Alice Smith","Bob Jones"]"#);

        let item = db.items().get(&saved.id).await.unwrap().unwrap();
        assert_eq!(item.title, "Multi Author Paper");
        assert_eq!(item.author.as_deref(), Some("Alice Smith, Bob Jones"));
    }

    #[tokio::test]
    async fn test_content_startup_maintenance_normalizes_html_summaries_and_skips_rerun() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let url1 = Url::parse("https://example.com/legacy-post").unwrap();
        let item1 = db
            .items()
            .create(&crate::db::NewItem {
                feed_id: None,
                title: "Legacy Post",
                url: &url1,
                author: None,
                published_at: None,
                summary: Some(
                    "<figure><img src=\"https://example.com/a.png\"></figure><p>Clean &amp; fast summary</p>",
                ),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        let url2 = Url::parse("https://example.com/angle-post").unwrap();
        let item2 = db
            .items()
            .create(&crate::db::NewItem {
                feed_id: None,
                title: "Angle Post",
                url: &url2,
                author: None,
                published_at: None,
                summary: Some("<p>Use &lt;div&gt; or x &lt; y &gt; z</p>"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();

        crate::service::run_content_startup_maintenance(&db).await;

        let refreshed1 = db.items().get(&item1.id).await.unwrap().unwrap();
        assert_eq!(refreshed1.summary.as_deref(), Some("Clean & fast summary"));
        assert!(refreshed1.content_html.as_deref().unwrap().contains("<figure>"));
        assert_eq!(refreshed1.thumbnail_url.as_deref(), Some("https://example.com/a.png"));

        let refreshed2 = db.items().get(&item2.id).await.unwrap().unwrap();
        assert_eq!(refreshed2.summary.as_deref(), Some("Use <div> or x < y > z"));

        // Inject raw HTML after maintenance completion; re-running skips completed tasks
        let url3 = Url::parse("https://example.com/after-maint").unwrap();
        let item3 = db
            .items()
            .create(&crate::db::NewItem {
                feed_id: None,
                title: "After Maint",
                url: &url3,
                author: None,
                published_at: None,
                summary: Some("<p>Should NOT be rescanned</p>"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        crate::service::run_content_startup_maintenance(&db).await;
        let untouched = db.items().get(&item3.id).await.unwrap().unwrap();
        assert_eq!(untouched.summary.as_deref(), Some("<p>Should NOT be rescanned</p>"));
    }
}
