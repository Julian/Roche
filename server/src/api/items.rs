use std::sync::Arc;

use axum::{Json, extract::State, http::StatusCode};
use chrono::{DateTime, Utc};
use serde::Deserialize;
use url::Url;

use crate::{
    db::{ItemFilter, ItemPage, ItemStatus, ReadAtPatch, SyncRepo},
    error::{ApiError, ProblemJson, ProblemPath, ProblemQuery},
    scheduler::FeedFetcher,
    service::{ItemDetail, RocheService},
};

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

pub(super) async fn items_list<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemQuery(filter): ProblemQuery<ItemFilter>,
) -> Result<Json<ItemPage>, ApiError> {
    let page = service.items().query().with_filter(filter).list_page().await?;
    Ok(Json(page))
}

pub(super) async fn items_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail = service.items().get(&id).await?;
    Ok(Json(detail))
}

pub(super) async fn items_save<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemJson(req): ProblemJson<SaveItemRequest>,
) -> Result<(StatusCode, Json<ItemDetail>), ApiError> {
    let detail = service.items().save(&req.url, req.title.as_deref(), &req.tags).await?;
    Ok((StatusCode::CREATED, Json(detail)))
}

pub(super) async fn items_update<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<UpdateItemRequest>,
) -> Result<Json<ItemDetail>, ApiError> {
    let resolved_read_at = match req.read_at {
        Some(Some(ts)) => ReadAtPatch::MarkRead(ts),
        Some(None) => ReadAtPatch::MarkUnread,
        None => match req.is_read {
            Some(true) => ReadAtPatch::MarkRead(SyncRepo::now_utc()),
            Some(false) => ReadAtPatch::MarkUnread,
            None => ReadAtPatch::Unchanged,
        },
    };
    let detail = service
        .items()
        .update(&id, req.status, req.is_favorite, req.read_progress, resolved_read_at)
        .await?;
    Ok(Json(detail))
}

pub(super) async fn items_delete<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.items().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn items_archive<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail = service.items().archive(&id).await?;
    Ok(Json(detail))
}

pub(super) async fn items_archive_batch<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemJson(req): ProblemJson<ArchiveBatchRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let archived_count = service
        .items()
        .archive_batch(&req.item_ids, req.feed_id.as_deref(), req.all_unread)
        .await?;
    Ok(Json(serde_json::json!({ "archived_count": archived_count })))
}

pub(super) async fn items_unarchive<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail = service.items().unarchive(&id).await?;
    Ok(Json(detail))
}

pub(super) async fn items_add_tag<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(item_id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<AddItemTagRequest>,
) -> Result<Json<ItemDetail>, ApiError> {
    let detail =
        service.items().add_tag(&item_id, req.tag_id.as_deref(), req.name.as_deref()).await?;
    Ok(Json(detail))
}

pub(super) async fn items_remove_tag<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath((item_id, tag_id)): ProblemPath<(String, String)>,
) -> Result<StatusCode, ApiError> {
    service.items().remove_tag(&item_id, &tag_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn items_media_list<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(item_id): ProblemPath<String>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let media = service.items().list_media(&item_id).await?;
    Ok(Json(serde_json::json!({ "media": media })))
}
