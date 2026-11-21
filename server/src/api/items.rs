use std::sync::Arc;

use axum::{Json, extract::State, http::StatusCode};
use chrono::{DateTime, Utc};
use serde::Deserialize;
use url::Url;

use super::error::{ApiError, ProblemJson, ProblemPath, ProblemQuery};
use crate::{
    db::{ItemFilter, ItemPage},
    domain::{
        ContentType, ItemSortField, ItemStatus, ItemStatusFilter, READER_AUTO_READ_THRESHOLD,
        ReadAtPatch, SortOrder,
    },
    service::{ItemDetail, RocheService},
    util::clock::now_utc,
};

fn deserialize_optional_rfc3339<'de, D>(
    deserializer: D,
) -> std::result::Result<Option<DateTime<Utc>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let opt = Option::<String>::deserialize(deserializer)?;
    let Some(raw) = opt else {
        return Ok(None);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(serde::de::Error::custom("updated_after cannot be empty"));
    }
    if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
        return Ok(Some(dt.with_timezone(&Utc)));
    }
    if trimmed.contains(' ')
        && let Ok(dt) = DateTime::parse_from_rfc3339(&trimmed.replace(' ', "+"))
    {
        return Ok(Some(dt.with_timezone(&Utc)));
    }
    Err(serde::de::Error::custom(format!(
        "Invalid RFC 3339 timestamp for updated_after: '{trimmed}'"
    )))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ItemListQuery {
    status: Option<ItemStatusFilter>,
    feed_id: Option<String>,
    tag_id: Option<String>,
    #[serde(default)]
    collection_id: Option<String>,
    #[serde(default)]
    inbox_only: Option<bool>,
    #[serde(default)]
    in_progress_only: Option<bool>,
    content_type: Option<ContentType>,
    is_favorite: Option<bool>,
    is_read: Option<bool>,
    include_content: Option<bool>,
    search: Option<String>,
    #[serde(default, deserialize_with = "deserialize_optional_rfc3339")]
    updated_after: Option<DateTime<Utc>>,
    #[serde(default)]
    updated_after_item_id: Option<String>,
    sort: Option<ItemSortField>,
    order: Option<SortOrder>,
    page: Option<usize>,
    per_page: Option<usize>,
    #[serde(default)]
    sort_updated_asc: bool,
}

impl From<ItemListQuery> for ItemFilter {
    fn from(q: ItemListQuery) -> Self {
        Self {
            status: q.status,
            feed_id: q.feed_id,
            tag_id: q.tag_id,
            collection_id: q.collection_id,
            inbox_only: q.inbox_only,
            in_progress_only: q.in_progress_only,
            content_type: q.content_type,
            is_favorite: q.is_favorite,
            is_read: q.is_read,
            include_content: q.include_content,
            search: q.search,
            updated_after: q.updated_after,
            updated_after_item_id: q.updated_after_item_id,
            sort: q.sort,
            order: q.order,
            page: q.page,
            per_page: q.per_page,
            sort_updated_asc: q.sort_updated_asc,
        }
    }
}

#[derive(Debug, Deserialize)]
pub(super) struct SaveItemRequest {
    url: Url,
    title: Option<String>,
    #[serde(default)]
    tags: Vec<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ArchiveBatchRequest {
    #[serde(default)]
    item_ids: Vec<String>,
    feed_id: Option<String>,
    #[serde(default)]
    all_unread: bool,
}

#[derive(Debug, Deserialize)]
pub(super) struct UpdateItemRequest {
    status: Option<ItemStatus>,
    is_favorite: Option<bool>,
    read_progress: Option<f64>,
    #[allow(clippy::option_option)]
    #[serde(default, deserialize_with = "deserialize_optional_nullable_datetime")]
    read_at: Option<Option<DateTime<Utc>>>,
    is_read: Option<bool>,
}

#[allow(clippy::option_option)]
fn deserialize_optional_nullable_datetime<'de, D>(
    deserializer: D,
) -> std::result::Result<Option<Option<DateTime<Utc>>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Option::<DateTime<Utc>>::deserialize(deserializer).map(Some)
}

#[derive(Debug, Deserialize)]
pub(super) struct AddItemTagRequest {
    tag_id: Option<String>,
    name: Option<String>,
}

pub(super) async fn items_list(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(query): ProblemQuery<ItemListQuery>,
) -> Result<Json<ItemPage>, ApiError> {
    let page = service.items().query().with_filter(ItemFilter::from(query)).list_page().await?;
    Ok(Json(page))
}

pub(super) async fn items_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail = service.items().get(&id).await?;
    Ok(Json(detail))
}

pub(super) async fn items_save(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<SaveItemRequest>,
) -> Result<(StatusCode, Json<ItemDetail>), ApiError> {
    let detail = service.items().save(&req.url, req.title.as_deref(), &req.tags).await?;
    Ok((StatusCode::CREATED, Json(detail)))
}

pub(super) async fn items_update(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<UpdateItemRequest>,
) -> Result<Json<ItemDetail>, ApiError> {
    let resolved_read_at = match req.read_at {
        Some(Some(ts)) => ReadAtPatch::MarkRead(ts),
        Some(None) => ReadAtPatch::MarkUnread,
        None => match req.is_read {
            Some(true) => ReadAtPatch::MarkRead(now_utc()),
            Some(false) => ReadAtPatch::MarkUnread,
            None if req.read_progress.is_some_and(|p| p >= READER_AUTO_READ_THRESHOLD) => {
                ReadAtPatch::MarkRead(now_utc())
            }
            None => ReadAtPatch::Unchanged,
        },
    };
    let detail = service
        .items()
        .update(&id, req.status, req.is_favorite, req.read_progress, resolved_read_at)
        .await?;
    Ok(Json(detail))
}

pub(super) async fn items_delete(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.items().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn items_archive(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail = service.items().archive(&id).await?;
    Ok(Json(detail))
}

pub(super) async fn items_archive_batch(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<ArchiveBatchRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let archived_count = service
        .items()
        .archive_batch(&req.item_ids, req.feed_id.as_deref(), req.all_unread)
        .await?;
    Ok(Json(serde_json::json!({ "archived_count": archived_count })))
}

pub(super) async fn items_unarchive(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail = service.items().unarchive(&id).await?;
    Ok(Json(detail))
}

pub(super) async fn items_add_tag(
    State(service): State<Arc<RocheService>>,
    ProblemPath(item_id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<AddItemTagRequest>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail =
        service.items().add_tag(&item_id, req.tag_id.as_deref(), req.name.as_deref()).await?;
    Ok(Json(detail))
}

pub(super) async fn items_remove_tag(
    State(service): State<Arc<RocheService>>,
    ProblemPath((item_id, tag_id)): ProblemPath<(String, String)>,
) -> Result<StatusCode, ApiError> {
    service.items().remove_tag(&item_id, &tag_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn items_media_list(
    State(service): State<Arc<RocheService>>,
    ProblemPath(item_id): ProblemPath<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let media = service.items().list_media(&item_id).await?;
    Ok(Json(serde_json::json!({ "media": media })))
}
