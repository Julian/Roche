use std::sync::Arc;

use axum::{Json, extract::State, http::StatusCode};
use serde::Deserialize;

use crate::{
    db::{HexColor, Tag},
    error::{ApiError, ProblemJson, ProblemPath},
    scheduler::FeedFetcher,
    service::RocheService,
};

#[derive(Debug, Deserialize)]
pub(super) struct CreateTagRequest {
    name: String,
    color: Option<HexColor>,
}

#[derive(Debug, Deserialize)]
pub(super) struct UpdateTagRequest {
    name: Option<String>,
    color: Option<HexColor>,
}

pub(super) async fn tags_list<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let tags = service.tags().list().await?;
    Ok(Json(serde_json::json!({ "tags": tags })))
}

pub(super) async fn tags_create<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemJson(req): ProblemJson<CreateTagRequest>,
) -> Result<(StatusCode, Json<Tag>), ApiError> {
    let tag = service.tags().create(&req.name, req.color.as_ref()).await?;
    Ok((StatusCode::CREATED, Json(tag)))
}

pub(super) async fn tags_update<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<UpdateTagRequest>,
) -> Result<Json<Tag>, ApiError> {
    let updated = service.tags().update(&id, req.name.as_deref(), req.color.as_ref()).await?;
    Ok(Json(updated))
}

pub(super) async fn tags_delete<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.tags().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
