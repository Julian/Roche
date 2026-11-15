use std::{net::SocketAddr, sync::Arc};

use axum::{
    Json,
    extract::{ConnectInfo, State, rejection::ExtensionRejection},
    http::{HeaderMap, StatusCode},
};
use serde::Deserialize;

use crate::{
    error::{ApiError, ProblemJson, ProblemPath},
    scheduler::FeedFetcher,
    service::RocheService,
};

#[derive(Debug, Deserialize)]
pub(super) struct PairRequest {
    device_name: String,
    pairing_code: String,
}

pub(super) async fn auth_pair<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    ProblemJson(req): ProblemJson<PairRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let token = service
        .pair_device_for_request(&req.device_name, &req.pairing_code, peer_addr, &headers)
        .await?;
    Ok(Json(serde_json::json!({
        "token": token,
        "expires_at": null,
    })))
}

pub(super) async fn devices_list<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let devices = service.auth().list_devices().await?;
    Ok(Json(serde_json::json!({ "devices": devices })))
}

pub(super) async fn devices_revoke<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.auth().revoke_device(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
