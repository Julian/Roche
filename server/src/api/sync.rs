use std::sync::Arc;

use axum::{Extension, Json, extract::State};
use serde::Deserialize;

use super::{
    AuthenticatedDevice,
    error::{ApiError, ProblemJson, ProblemQuery},
};
use crate::{
    db::{PeerSyncRequest, PeerSyncResponse},
    service::{RocheService, SyncChanges, SyncStatus},
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct SyncChangesQuery {
    since_id: Option<i64>,
    limit: Option<i64>,
}

pub(super) async fn sync_status(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<SyncStatus>, ApiError> {
    let status = service.sync().status().await?;
    Ok(Json(status))
}

pub(super) async fn sync_changes(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(query): ProblemQuery<SyncChangesQuery>,
) -> Result<Json<SyncChanges>, ApiError> {
    let changes = service.sync().changes(query.since_id, query.limit).await?;
    Ok(Json(changes))
}

pub(super) async fn sync_peer(
    State(service): State<Arc<RocheService>>,
    Extension(device): Extension<AuthenticatedDevice>,
    ProblemJson(req): ProblemJson<PeerSyncRequest>,
) -> Result<Json<PeerSyncResponse>, ApiError> {
    service.auth().verify_sync_peer_binding(&device.token_id, &req.peer_id).await?;
    let res = service.sync().peer_sync(req).await?;
    Ok(Json(res))
}
