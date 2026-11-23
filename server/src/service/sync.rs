use std::collections::HashSet;

use chrono::{DateTime, Utc};
use serde::Serialize;
use url::Url;

use super::{AppError, RocheService};
use crate::{
    content::{HtmlSanitizer, MAX_SUMMARY_CHARS},
    db::{
        ActivityEntry, PeerSyncRequest, PeerSyncResponse, PreparedSyncEntry, SyncEntryRecord,
        deterministic_feed_id,
    },
    domain::ItemStatus,
    feeds::FeedSource,
    ingest::FeedIngester,
    util::{
        clock::now_utc,
        url_guard::{parse_public_url, validate_public_url},
    },
};

const MAX_SYNC_PEER_ID_LEN: usize = 128;
const MAX_SYNC_URL_LEN: usize = 2048;

const MAX_SYNC_ENTRIES: usize = 15_000;
const MAX_SYNC_TOMBSTONES: usize = 10_000;
const MAX_SYNC_COLLECTION_ENTRIES: usize = 25_000;
const MAX_SYNC_GENERATORS: usize = 64;
const MAX_SYNC_CONSUMPTION_LOG: usize = 25_000;
const MAX_SYNC_COMPARISONS: usize = 25_000;

fn is_valid_sync_item_url(url: &Url) -> bool {
    matches!(url.scheme(), "http" | "https")
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.as_str().len() <= MAX_SYNC_URL_LEN
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncStatus {
    pub status: &'static str,
    pub feeds_count: usize,
    pub unread_items_count: i64,
    pub archived_items_count: i64,
    pub latest_activity_id: i64,
    pub is_refreshing: bool,
    pub active_refresh_count: usize,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SyncChanges {
    pub changes: Vec<ActivityEntry>,
    pub next_since_id: i64,
    pub latest_activity_id: i64,
    pub has_more: bool,
}

pub struct SyncService<'a> {
    pub(super) service: &'a RocheService,
}

impl SyncService<'_> {
    pub async fn status(&self) -> Result<SyncStatus, AppError> {
        let feeds_count = self.service.db.feeds().count().await?;
        let unread_items_count = self.service.db.items().count_unread().await?;
        let archived_items_count =
            self.service.db.items().count_by_status(ItemStatus::Archived).await?;
        let latest_activity_id = self.service.db.sync().latest_activity_id().await?;

        let active_refresh_count = self.service.active_refresh_count();
        Ok(SyncStatus {
            status: "ok",
            feeds_count,
            unread_items_count,
            archived_items_count,
            latest_activity_id,
            is_refreshing: active_refresh_count > 0,
            active_refresh_count,
            timestamp: now_utc(),
        })
    }

    pub async fn changes(
        &self,
        since_id: Option<i64>,
        limit: Option<i64>,
    ) -> Result<SyncChanges, AppError> {
        let since = since_id.unwrap_or(0).max(0);
        let lim = limit.unwrap_or(500).clamp(1, 1000);
        let mut changes =
            self.service.db.sync().list_changes_since(since, lim.saturating_add(1)).await?;
        let limit_usize = usize::try_from(lim).unwrap_or(500);
        let has_more = changes.len() > limit_usize;
        changes.truncate(limit_usize);
        let next_since_id = changes.last().map_or(since, |c| c.id);
        let latest_activity_id = self.service.db.sync().latest_activity_id().await?;
        Ok(SyncChanges { changes, next_since_id, latest_activity_id, has_more })
    }

    pub async fn peer_sync(&self, mut req: PeerSyncRequest) -> Result<PeerSyncResponse, AppError> {
        validate_peer_sync_limits(&req)?;
        sanitize_peer_sync_request(&mut req);

        // Run HTML sanitization and URL parsing off the SQLite write lock
        let prepared_entries = if req.entries.is_empty() {
            Vec::new()
        } else {
            let raw = req.entries.clone();
            tokio::task::spawn_blocking(move || prepare_sync_entries(raw)).await.map_err(|e| {
                AppError::Internal(anyhow::anyhow!("Sync entry preparation failed: {e}"))
            })?
        };

        let (response, mut inserted_candidates) =
            self.service.db.sync().merge_peer_state_prepared(&req, &prepared_entries).await?;
        inserted_candidates.retain(|(_, c)| validate_public_url(&c.url).is_ok());

        if !inserted_candidates.is_empty() {
            let service_clone = self.service.clone();
            self.service.spawn_background(async move {
                let ctx = service_clone.media_ingest_context();
                FeedIngester::ingest_items_concurrently(&ctx, &inserted_candidates).await;
            });
        }

        Ok(response)
    }
}

fn validate_peer_sync_limits(req: &PeerSyncRequest) -> Result<(), AppError> {
    let trimmed_peer = req.peer_id.trim();
    if trimmed_peer.is_empty() || trimmed_peer.len() > MAX_SYNC_PEER_ID_LEN {
        return Err(AppError::bad_request("peer_id must be non-empty and at most 128 characters"));
    }

    for (field, len, limit) in [
        ("entries", req.entries.len(), MAX_SYNC_ENTRIES),
        ("tombstones", req.tombstones.len(), MAX_SYNC_TOMBSTONES),
        ("collection_entries", req.collection_entries.len(), MAX_SYNC_COLLECTION_ENTRIES),
        ("collection_generators", req.collection_generators.len(), MAX_SYNC_GENERATORS),
        ("consumption_log", req.consumption_log.len(), MAX_SYNC_CONSUMPTION_LOG),
        ("comparisons", req.comparisons.len(), MAX_SYNC_COMPARISONS),
    ] {
        if len > limit {
            return Err(AppError::payload_too_large(format!(
                "peer_sync '{field}' array ({len}) exceeds per-request limit ({limit}); paginate into smaller batches"
            )));
        }
    }
    Ok(())
}

fn sanitize_peer_sync_request(req: &mut PeerSyncRequest) {
    let mut skipped_feed_ids = HashSet::new();
    req.entries.retain_mut(|entry| sanitize_sync_entry_urls(entry, &mut skipped_feed_ids));

    if !skipped_feed_ids.is_empty() {
        let mut orphaned_feed_items = HashSet::new();
        for ce in &req.collection_entries {
            if skipped_feed_ids.contains(ce.collection_id.trim()) {
                orphaned_feed_items.insert(ce.entry_id.trim().to_owned());
            }
        }
        req.collection_entries.retain(|ce| !skipped_feed_ids.contains(ce.collection_id.trim()));
        if !orphaned_feed_items.is_empty() {
            let still_linked: HashSet<&str> =
                req.collection_entries.iter().map(|ce| ce.entry_id.trim()).collect();
            req.entries.retain(|e| {
                let id = e.id.trim();
                !orphaned_feed_items.contains(id) || still_linked.contains(id)
            });
        }
    }
}

fn sanitize_sync_entry_urls(
    entry: &mut SyncEntryRecord,
    skipped_feed_ids: &mut HashSet<String>,
) -> bool {
    if let Some(ref mut fs) = entry.feed_source {
        let Ok(parsed_feed_url) = Url::parse(fs.feed_url.trim()) else {
            if !entry.id.trim().is_empty() {
                skipped_feed_ids.insert(entry.id.trim().to_owned());
            }
            return false;
        };
        if let Err(err) = validate_public_url(&parsed_feed_url) {
            tracing::warn!(
                url = %parsed_feed_url,
                error = %err,
                "Skipping non-public or disallowed feed URL in peer_sync"
            );
            if !entry.id.trim().is_empty() {
                skipped_feed_ids.insert(entry.id.trim().to_owned());
            }
            skipped_feed_ids.insert(deterministic_feed_id(parsed_feed_url.as_str()));
            return false;
        }
        return match FeedSource::Url(parsed_feed_url).into_feed_url(fs.source_type) {
            Ok((resolved_type, validated_url)) => {
                fs.source_type = resolved_type;
                fs.feed_url = validated_url.to_string();
                true
            }
            Err(err) => {
                tracing::warn!(
                    url = %fs.feed_url,
                    error = %err,
                    "Skipping incompatible feed URL in peer_sync"
                );
                if !entry.id.trim().is_empty() {
                    skipped_feed_ids.insert(entry.id.trim().to_owned());
                }
                false
            }
        };
    }

    if let Some(raw_url) = entry.url.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let valid = Url::parse(raw_url).as_ref().is_ok_and(is_valid_sync_item_url);
        if !valid {
            tracing::warn!(
                url = %raw_url,
                "Skipping or clearing invalid entry URL in peer_sync"
            );
            if entry.state.is_some() || entry.content.is_some() {
                return false;
            }
            entry.url = None;
        }
    }

    if let Some(ref mut ct) = entry.content
        && ct.audio_url.as_deref().map(str::trim).filter(|s| !s.is_empty()).is_some_and(|u| {
            !Url::parse(u).is_ok_and(|parsed| {
                is_valid_sync_item_url(&parsed) && validate_public_url(&parsed).is_ok()
            })
        })
    {
        tracing::warn!(
            id = %entry.id,
            "Clearing invalid or disallowed audio_url on synced entry in peer_sync"
        );
        ct.audio_url = None;
    }

    if entry.thumbnail_url.as_deref().is_some_and(|u| u.trim().len() > MAX_SYNC_URL_LEN) {
        tracing::warn!(
            id = %entry.id,
            "Clearing oversized thumbnail_url on synced entry in peer_sync"
        );
        entry.thumbnail_url = None;
    }
    true
}

#[must_use]
pub fn prepare_sync_entries(entries: Vec<SyncEntryRecord>) -> Vec<PreparedSyncEntry> {
    entries.into_iter().map(prepare_sync_entry).collect()
}

#[must_use]
pub fn prepare_sync_entry(rec: SyncEntryRecord) -> PreparedSyncEntry {
    let parsed_url = rec
        .url
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .and_then(|u| Url::parse(u).ok());

    let raw_html = rec.content.as_ref().and_then(|c| c.content_html.as_deref());
    let clean_html = raw_html
        .map(|h| HtmlSanitizer::clean_with_base(h, parsed_url.as_ref()))
        .filter(|h| !h.trim().is_empty());

    let clean_summary = if rec.content.is_some() || rec.state.is_some() {
        rec.summary
            .as_deref()
            .map(|s| HtmlSanitizer::plain_text_excerpt(s, MAX_SUMMARY_CHARS))
            .filter(|s| !s.is_empty())
            .or_else(|| {
                clean_html
                    .as_deref()
                    .map(|h| HtmlSanitizer::plain_text_excerpt_from_clean(h, MAX_SUMMARY_CHARS))
                    .filter(|s| !s.is_empty())
            })
    } else {
        rec.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned)
    };

    let clean_thumb = rec
        .thumbnail_url
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .and_then(|u| {
            HtmlSanitizer::resolve_and_validate_thumbnail_url(u, parsed_url.as_ref())
                .or_else(|| parse_public_url(u).ok().map(String::from))
        })
        .or_else(|| {
            clean_html.as_deref().and_then(|h| {
                HtmlSanitizer::extract_first_image_url_with_base(h, parsed_url.as_ref())
            })
        });

    PreparedSyncEntry::from_sanitized_fields(
        rec,
        parsed_url,
        clean_html,
        clean_summary,
        clean_thumb,
    )
}

#[cfg(test)]
mod tests {
    use super::prepare_sync_entry;
    use crate::{
        db::{SyncEntryContent, SyncEntryRecord},
        domain::{ContentType, EntryKind},
    };

    #[test]
    fn test_prepare_sync_entry_sanitizes_html_and_extracts_excerpt_and_thumbnail() {
        let rec = SyncEntryRecord {
            id: "entry-1".to_owned(),
            kind: EntryKind::Article,
            title: "Example".to_owned(),
            creators: Vec::new(),
            url: Some("https://example.com/posts/1".to_owned()),
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata: None,
            identifiers: Vec::new(),
            merged_into_id: None,
            created_at: None,
            updated_at: None,
            hlc: String::new(),
            content: Some(SyncEntryContent {
                content_type: ContentType::Article,
                content_html: Some(
                    "<script>alert(1)</script><p>Hello <b>world</b></p><img src=\"/hero.png\">"
                        .to_owned(),
                ),
                audio_url: None,
                duration: None,
            }),
            state: None,
            collection_spec: None,
            feed_source: None,
        };

        let prepared = prepare_sync_entry(rec);
        let html = prepared.clean_html.as_deref().unwrap_or("");
        assert!(!html.contains("<script"));
        assert!(html.contains("<p>Hello <b>world</b></p>"));
        assert_eq!(prepared.clean_summary.as_deref(), Some("Hello world"));
        assert_eq!(prepared.clean_thumb.as_deref(), Some("https://example.com/hero.png"));
    }
}
