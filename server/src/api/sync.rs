use std::sync::Arc;

use axum::{Json, extract::State};
use serde::Deserialize;

use crate::{
    db::{PeerSyncRequest, PeerSyncResponse},
    error::{ApiError, ProblemJson, ProblemQuery},
    scheduler::FeedFetcher,
    service::{RocheService, SyncChanges, SyncStatus},
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct SyncChangesQuery {
    since_id: Option<i64>,
    limit: Option<i64>,
}

pub(super) async fn sync_status<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
) -> Result<Json<SyncStatus>, ApiError> {
    let status = service.sync_status().await?;
    Ok(Json(status))
}

pub(super) async fn sync_changes<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemQuery(query): ProblemQuery<SyncChangesQuery>,
) -> Result<Json<SyncChanges>, ApiError> {
    let changes = service.sync_changes(query.since_id, query.limit).await?;
    Ok(Json(changes))
}

pub(super) async fn sync_peer<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemJson(req): ProblemJson<PeerSyncRequest>,
) -> Result<Json<PeerSyncResponse>, ApiError> {
    let res = service.peer_sync(req).await?;
    Ok(Json(res))
}
