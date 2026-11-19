use std::sync::Arc;

use axum::{Json, extract::State, http::StatusCode};
use serde::Deserialize;

use crate::{
    db::Tag,
    domain::HexColor,
    error::{ApiError, ProblemJson, ProblemPath},
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

pub(super) async fn tags_list(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let tags = service.tags().list().await?;
    Ok(Json(serde_json::json!({ "tags": tags })))
}

pub(super) async fn tags_create(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<CreateTagRequest>,
) -> Result<(StatusCode, Json<Tag>), ApiError> {
    let tag = service.tags().create(&req.name, req.color.as_ref()).await?;
    Ok((StatusCode::CREATED, Json(tag)))
}

pub(super) async fn tags_update(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<UpdateTagRequest>,
) -> Result<Json<Tag>, ApiError> {
    let updated = service.tags().update(&id, req.name.as_deref(), req.color.as_ref()).await?;
    Ok(Json(updated))
}

pub(super) async fn tags_delete(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.tags().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
