use std::sync::Arc;

use axum::{
    Json,
    extract::State,
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::error::{ApiError, ProblemJson, ProblemPath, ProblemQuery, ProblemString};
use crate::{
    db::{Feed, ItemFilter, ItemPage},
    domain::FeedType,
    service::{ExportBundle, FeedService, RocheService},
};

#[derive(Debug, Deserialize)]
pub(super) struct CreateFeedRequest {
    url: String,
    #[serde(default)]
    feed_type: FeedType,
    title: Option<String>,
    description: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct UpdateFeedRequest {
    title: Option<String>,
    poll_interval: Option<i64>,
    is_active: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct OpmlImportRequest {
    opml: String,
}

pub(super) async fn feeds_list(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let feeds = service.feeds().list().await?;
    Ok(Json(serde_json::json!({ "feeds": feeds })))
}

pub(super) async fn feeds_create(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<CreateFeedRequest>,
) -> Result<(StatusCode, Json<Feed>), ApiError> {
    let source = FeedService::parse_source(&req.url)?;
    let feed = service
        .feeds()
        .create(req.feed_type, source, req.title.as_deref(), req.description.as_deref())
        .await?;
    Ok((StatusCode::CREATED, Json(feed)))
}

pub(super) async fn feeds_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Feed>, ApiError> {
    let feed = service.feeds().get(&id).await?;
    Ok(Json(feed))
}

pub(super) async fn feeds_update(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<UpdateFeedRequest>,
) -> Result<Json<Feed>, ApiError> {
    let updated =
        service.feeds().update(&id, req.title.as_deref(), req.poll_interval, req.is_active).await?;
    Ok(Json(updated))
}

pub(super) async fn feeds_delete(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.feeds().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn feeds_archive(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Feed>, ApiError> {
    let archived = service.feeds().archive(&id).await?;
    Ok(Json(archived))
}

pub(super) async fn feeds_unarchive(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Feed>, ApiError> {
    let unarchived = service.feeds().unarchive(&id).await?;
    Ok(Json(unarchived))
}

pub(super) async fn feeds_refresh(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Feed>, ApiError> {
    let refreshed = service.feeds().refresh(&id).await?;
    Ok(Json(refreshed))
}

pub(super) async fn feeds_items(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemQuery(query): ProblemQuery<super::items::ItemListQuery>,
) -> Result<Json<ItemPage>, ApiError> {
    // Verify feed exists first
    let _ = service.feeds().get(&id).await?;
    let page = service
        .items()
        .query()
        .with_filter(ItemFilter::from(query))
        .feed_id(id)
        .list_page()
        .await?;
    Ok(Json(page))
}

pub(super) async fn import_opml(
    State(service): State<Arc<RocheService>>,
    ProblemString(body): ProblemString,
) -> Result<Json<serde_json::Value>, ApiError> {
    let opml_str = tokio::task::spawn_blocking(move || -> Result<String, ApiError> {
        if body.trim_start().starts_with('{') {
            serde_json::from_str::<OpmlImportRequest>(&body)
                .map(|r| r.opml)
                .map_err(|e| ApiError::bad_request(format!("Invalid JSON payload: {e}")))
        } else {
            Ok(body)
        }
    })
    .await
    .map_err(|e| ApiError::Internal(anyhow::anyhow!("OPML request decode task failed: {e}")))??;

    let imported = service.feeds().import_opml(opml_str).await?;
    Ok(Json(serde_json::json!({
        "imported_count": imported.len(),
        "feeds": imported,
    })))
}

pub(super) async fn export_feeds_opml(
    State(service): State<Arc<RocheService>>,
) -> Result<Response, ApiError> {
    let xml = service.feeds().export_opml().await?;
    Ok(([(header::CONTENT_TYPE, "text/x-opml; charset=utf-8")], xml).into_response())
}

pub(super) async fn export_data(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<ExportBundle>, ApiError> {
    let bundle = service.export_data().await?;
    Ok(Json(bundle))
}
