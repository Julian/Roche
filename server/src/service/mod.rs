use chrono::{DateTime, Utc};
use serde::Serialize;
use url::Url;

use crate::{
    auth::{pairing::PairingManager, tokens::TokenManager},
    config::Config,
    content::fetcher::SafeHttpClient,
    db::{
        ActivityEntry, Db, Feed, FeedSource, FeedType, HexColor, Item, ItemFilter, ItemStatus,
        ItemTag, Media, SyncRepo, Tag,
    },
    error::ApiError,
    feeds::OpmlDocument,
    media::MediaStore,
    scheduler::{FeedFetcher, FeedIngester, HttpFeedFetcher, PollError},
};

const MAX_DEVICE_NAME_LEN: usize = 128;

#[derive(Debug, Clone, Serialize)]
pub struct ItemDetail {
    #[serde(flatten)]
    pub item: Item,
    pub tags: Vec<Tag>,
    pub media: Vec<Media>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ExportBundle {
    pub version: &'static str,
    pub exported_at: DateTime<Utc>,
    pub feeds: Vec<Feed>,
    pub items: Vec<Item>,
    pub tags: Vec<Tag>,
    pub item_tags: Vec<ItemTag>,
    pub activity: Vec<ActivityEntry>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncStatus {
    pub status: &'static str,
    pub feeds_count: usize,
    pub unread_items_count: i64,
    pub archived_items_count: i64,
    pub timestamp: DateTime<Utc>,
}

/// Application service layer coordinating repositories, domain validation, and external fetchers.
#[derive(Debug, Clone)]
pub struct RocheService<F = HttpFeedFetcher> {
    db: Db,
    fetcher: F,
    config: Config,
    media_store: MediaStore,
}

impl<F: FeedFetcher> RocheService<F> {
    #[must_use]
    pub fn new(db: Db, fetcher: F, config: Config) -> Self {
        let media_store = MediaStore::new(&config.storage.media_dir);
        Self { db, fetcher, config, media_store }
    }

    #[must_use]
    pub const fn auth(&self) -> AuthService<'_, F> {
        AuthService { service: self }
    }

    #[must_use]
    pub const fn feeds(&self) -> FeedService<'_, F> {
        FeedService { service: self }
    }

    #[must_use]
    pub const fn items(&self) -> ItemService<'_, F> {
        ItemService { service: self }
    }

    #[must_use]
    pub const fn tags(&self) -> TagService<'_, F> {
        TagService { service: self }
    }

    #[must_use]
    pub const fn media(&self) -> MediaService<'_, F> {
        MediaService { service: self }
    }

    pub async fn export_data(&self) -> Result<ExportBundle, ApiError> {
        let feeds = self.db.feeds().list().await?;
        let items = self.db.items().list(None, None, None).await?;
        let tags = self.db.tags().list().await?;
        let item_tags = self.db.tags().list_all_item_tags().await?;
        let activity = self.db.sync().list_activity().await?;

        Ok(ExportBundle {
            version: "1.0",
            exported_at: SyncRepo::now_utc(),
            feeds,
            items,
            tags,
            item_tags,
            activity,
        })
    }

    pub async fn sync_status(&self) -> Result<SyncStatus, ApiError> {
        let feeds = self.db.feeds().list().await?;
        let unread_items_count = self.db.items().count_by_status(ItemStatus::Unread).await?;
        let archived_items_count = self.db.items().count_by_status(ItemStatus::Archived).await?;

        Ok(SyncStatus {
            status: "ok",
            feeds_count: feeds.len(),
            unread_items_count,
            archived_items_count,
            timestamp: SyncRepo::now_utc(),
        })
    }
}

pub struct AuthService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> AuthService<'_, F> {
    pub async fn issue_pairing_code(&self) -> Result<String, ApiError> {
        let pairing = PairingManager::new(self.service.db.auth());
        Ok(pairing.create_code(self.service.config.auth.pairing_code_ttl).await?)
    }

    pub async fn pair_device(
        &self,
        device_name: &str,
        pairing_code: &str,
    ) -> Result<String, ApiError> {
        let trimmed_name = device_name.trim();
        if trimmed_name.is_empty() {
            return Err(ApiError::bad_request("device_name cannot be empty"));
        }
        if trimmed_name.len() > MAX_DEVICE_NAME_LEN {
            return Err(ApiError::bad_request(format!(
                "device_name cannot exceed {MAX_DEVICE_NAME_LEN} characters"
            )));
        }

        let pairing = PairingManager::new(self.service.db.auth());
        let consumed = pairing.consume_valid_code(pairing_code.trim()).await?;
        if !consumed {
            return Err(ApiError::unauthorized("Invalid or expired pairing code"));
        }

        let tokens = TokenManager::new(self.service.db.auth());
        let token = tokens.create(trimmed_name, self.service.config.auth.token_length).await?;
        Ok(token)
    }

    pub async fn authenticate(&self, bearer_token: &str) -> Result<(), ApiError> {
        let tokens = TokenManager::new(self.service.db.auth());
        if !tokens.validate(bearer_token).await? {
            return Err(ApiError::unauthorized("Invalid or revoked bearer token"));
        }
        Ok(())
    }
}

pub struct FeedService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> FeedService<'_, F> {
    pub async fn list(&self) -> Result<Vec<Feed>, ApiError> {
        Ok(self.service.db.feeds().list().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Feed, ApiError> {
        self.service.db.feeds().get(id).await?.ok_or_else(|| ApiError::not_found("Feed not found"))
    }

    pub async fn create(
        &self,
        feed_type: FeedType,
        source: FeedSource,
        title: Option<&str>,
        description: Option<&str>,
    ) -> Result<Feed, ApiError> {
        let validated_url = source
            .into_feed_url(feed_type)
            .map_err(|e| ApiError::bad_request(format!("Invalid feed URL: {e}")))?;

        let feed = self
            .service
            .db
            .feeds()
            .create_with_interval(
                feed_type,
                &validated_url,
                title.map(str::trim).filter(|t| !t.is_empty()),
                description.map(str::trim).filter(|d| !d.is_empty()),
                self.service.config.feeds.default_poll_interval,
            )
            .await?;

        Ok(feed)
    }

    pub async fn update(
        &self,
        id: &str,
        title: Option<&str>,
        poll_interval: Option<i64>,
        is_active: Option<bool>,
    ) -> Result<Feed, ApiError> {
        let clean_title = match title {
            Some(t) => {
                let trimmed = t.trim();
                if trimmed.is_empty() {
                    return Err(ApiError::bad_request("Feed title cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };

        if let Some(interval) = poll_interval {
            let min = self.service.config.feeds.min_poll_interval;
            let max = self.service.config.feeds.max_poll_interval;
            if !(min..=max).contains(&interval) {
                return Err(ApiError::bad_request(format!(
                    "poll_interval must be between {min} and {max} minutes"
                )));
            }
        }

        let repo = self.service.db.feeds();
        if !repo.update(id, clean_title, poll_interval, is_active).await? {
            return Err(ApiError::not_found("Feed not found"));
        }

        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let repo = self.service.db.feeds();
        let media_repo = self.service.db.media();
        let media_list = media_repo.list_for_feed(id).await?;

        if !repo.delete(id).await? {
            return Err(ApiError::not_found("Feed not found"));
        }

        media_repo.delete_with_files(&self.service.media_store, media_list).await?;
        Ok(())
    }

    pub async fn refresh(&self, id: &str) -> Result<Feed, ApiError> {
        let repo = self.service.db.feeds();
        let feed = self.get(id).await?;

        if let Err(err) = FeedIngester::poll(&repo, &feed, &self.service.fetcher).await {
            let _ = repo.mark_error(&feed.id, &err.to_string()).await;
            return match err {
                PollError::Upstream(upstream) => {
                    Err(ApiError::bad_gateway(format!("Failed to refresh feed: {upstream}")))
                }
                PollError::Database(db_err) => Err(ApiError::Database(db_err)),
            };
        }

        self.get(id).await
    }

    pub async fn import_opml(&self, opml_str: String) -> Result<Vec<Feed>, ApiError> {
        let outlines = tokio::task::spawn_blocking(move || OpmlDocument::parse(&opml_str))
            .await
            .map_err(|e| anyhow::anyhow!("OPML parse task failed: {e}"))?;
        let default_interval = self.service.config.feeds.default_poll_interval;
        Ok(self.service.db.feeds().import_outlines(outlines, default_interval).await?)
    }

    pub async fn export_opml(&self) -> Result<String, ApiError> {
        let feeds = self.list().await?;
        Ok(OpmlDocument::to_xml(&feeds))
    }
}

pub struct ItemService<'a, F> {
    service: &'a RocheService<F>,
}

impl<'a, F: FeedFetcher> ItemService<'a, F> {
    /// Starts a chainable query builder for filtering, searching, sorting, and paginating items.
    #[must_use]
    pub fn query(&self) -> ItemQuery<'a> {
        ItemQuery { db: &self.service.db, filter: ItemFilter::default() }
    }

    pub async fn get(&self, id: &str) -> Result<ItemDetail, ApiError> {
        let item = self
            .service
            .db
            .items()
            .get(id)
            .await?
            .ok_or_else(|| ApiError::not_found("Item not found"))?;
        let tags = self.service.db.tags().list_for_item(id).await?;
        let media = self.service.db.media().list_for_item(id).await?;
        Ok(ItemDetail { item, tags, media })
    }

    pub async fn save(
        &self,
        url: &Url,
        title: Option<&str>,
        tags: &[String],
    ) -> Result<ItemDetail, ApiError> {
        SafeHttpClient::validate_url(url)
            .map_err(|e| ApiError::bad_request(format!("Invalid item URL: {e}")))?;

        let resolved_title =
            title.map(str::trim).filter(|t| !t.is_empty()).unwrap_or_else(|| url.as_str());

        let (item, attached_tags) =
            self.service.db.items().save_manual_with_tags(url, resolved_title, tags).await?;

        Ok(ItemDetail { item, tags: attached_tags, media: Vec::new() })
    }

    pub async fn update(
        &self,
        id: &str,
        status: Option<ItemStatus>,
        is_favorite: Option<bool>,
    ) -> Result<ItemDetail, ApiError> {
        if !self.service.db.items().update(id, status, is_favorite).await? {
            return Err(ApiError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn archive(&self, id: &str) -> Result<ItemDetail, ApiError> {
        if !self.service.db.items().archive(id).await? {
            return Err(ApiError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn unarchive(&self, id: &str) -> Result<ItemDetail, ApiError> {
        if !self.service.db.items().unarchive(id).await? {
            return Err(ApiError::not_found("Item not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let item_repo = self.service.db.items();
        if !item_repo.delete(id).await? {
            return Err(ApiError::not_found("Item not found"));
        }

        let media_repo = self.service.db.media();
        let media_list = media_repo.list_for_item(id).await?;
        media_repo.delete_with_files(&self.service.media_store, media_list).await?;
        Ok(())
    }

    pub async fn add_tag(
        &self,
        item_id: &str,
        tag_id: Option<&str>,
        name: Option<&str>,
    ) -> Result<ItemDetail, ApiError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(ApiError::not_found("Item not found"));
        }

        if let Some(tid) = tag_id {
            let tag = self.service.tags().get(tid).await?;
            self.service.db.tags().add_to_item(item_id, &tag.id).await?;
        } else if let Some(name) = name {
            let trimmed = name.trim();
            if trimmed.is_empty() {
                return Err(ApiError::bad_request("Tag name cannot be empty"));
            }
            self.service.db.tags().add_named_to_item(item_id, trimmed).await?;
        } else {
            return Err(ApiError::bad_request("Either tag_id or name must be provided"));
        }

        self.get(item_id).await
    }

    pub async fn remove_tag(&self, item_id: &str, tag_id: &str) -> Result<(), ApiError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(ApiError::not_found("Item not found"));
        }
        let _ = self.service.db.tags().remove_from_item(item_id, tag_id).await?;
        Ok(())
    }

    pub async fn list_media(&self, item_id: &str) -> Result<Vec<Media>, ApiError> {
        if self.service.db.items().get(item_id).await?.is_none() {
            return Err(ApiError::not_found("Item not found"));
        }
        Ok(self.service.db.media().list_for_item(item_id).await?)
    }
}

/// Fluent, chainable query builder for items (`service.items().query().with_filter(...).feed_id(...).list()`).
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

    pub async fn list(self) -> Result<Vec<Item>, ApiError> {
        Ok(self.db.items().list_filtered(&self.filter).await?)
    }
}

pub struct TagService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> TagService<'_, F> {
    pub async fn list(&self) -> Result<Vec<Tag>, ApiError> {
        Ok(self.service.db.tags().list().await?)
    }

    pub async fn get(&self, id: &str) -> Result<Tag, ApiError> {
        self.service.db.tags().get(id).await?.ok_or_else(|| ApiError::not_found("Tag not found"))
    }

    pub async fn create(&self, name: &str, color: Option<&HexColor>) -> Result<Tag, ApiError> {
        let trimmed = name.trim();
        if trimmed.is_empty() {
            return Err(ApiError::bad_request("Tag name cannot be empty"));
        }
        Ok(self.service.db.tags().create(trimmed, color).await?)
    }

    pub async fn update(
        &self,
        id: &str,
        name: Option<&str>,
        color: Option<&HexColor>,
    ) -> Result<Tag, ApiError> {
        let clean_name = match name {
            Some(n) => {
                let trimmed = n.trim();
                if trimmed.is_empty() {
                    return Err(ApiError::bad_request("Tag name cannot be empty"));
                }
                Some(trimmed)
            }
            None => None,
        };

        if !self.service.db.tags().update(id, clean_name, color).await? {
            return Err(ApiError::not_found("Tag not found"));
        }
        self.get(id).await
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let repo = self.service.db.tags();
        if !repo.delete(id).await? {
            return Err(ApiError::not_found("Tag not found"));
        }
        Ok(())
    }
}

pub struct MediaService<'a, F> {
    service: &'a RocheService<F>,
}

impl<F: FeedFetcher> MediaService<'_, F> {
    pub async fn read_bytes(&self, id: &str) -> Result<(&'static str, Vec<u8>), ApiError> {
        let repo = self.service.db.media();
        let media = repo.get(id).await?.ok_or_else(|| ApiError::not_found("Media not found"))?;
        let content_type = MediaStore::content_type_for(media.media_type, &media.path);
        let bytes = self.service.media_store.read(&media.path).await.map_err(|e| {
            if e.downcast_ref::<std::io::Error>()
                .is_some_and(|io_err| io_err.kind() == std::io::ErrorKind::NotFound)
            {
                ApiError::not_found("Media file not found on disk")
            } else {
                ApiError::Internal(e)
            }
        })?;
        Ok((content_type, bytes))
    }

    pub async fn delete(&self, id: &str) -> Result<(), ApiError> {
        let repo = self.service.db.media();
        let media = repo.get(id).await?.ok_or_else(|| ApiError::not_found("Media not found"))?;

        self.service.media_store.remove(&media.path).await?;
        repo.delete(id).await?;
        Ok(())
    }
}
