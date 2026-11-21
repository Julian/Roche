use std::sync::Arc;

use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    response::Response,
};

use super::error::{ApiError, ProblemPath};
use crate::{service::RocheService, util::http_media::serve_media_stream};

pub(super) async fn media_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    Ok(serve_media_stream(&service, &id, &headers).await?)
}

pub(super) async fn media_delete(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.media().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
