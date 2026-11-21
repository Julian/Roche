use std::{net::SocketAddr, sync::Arc};

use axum::{
    Json,
    extract::{ConnectInfo, State, rejection::ExtensionRejection},
    http::{HeaderMap, StatusCode},
};
use serde::Deserialize;

use super::error::{ApiError, ProblemJson, ProblemPath};
use crate::service::RocheService;

#[derive(Debug, Deserialize)]
pub(super) struct PairRequest {
    device_name: String,
    pairing_code: String,
}

pub(super) async fn auth_pair(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    ProblemJson(req): ProblemJson<PairRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let mut ts_headers = headers.get_all("tailscale-user-login").iter();
    let tailscale_user_login = match (ts_headers.next(), ts_headers.next()) {
        (Some(single), None) => single.to_str().ok(),
        _ => None,
    };
    let x_forwarded_for =
        headers.get_all("x-forwarded-for").iter().next_back().and_then(|v| v.to_str().ok());
    let token = service
        .auth()
        .pair_device_for_request(
            &req.device_name,
            &req.pairing_code,
            peer_addr,
            tailscale_user_login,
            x_forwarded_for,
        )
        .await?;
    Ok(Json(serde_json::json!({
        "token": token,
        "expires_at": null,
    })))
}

pub(super) async fn devices_list(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let devices = service.auth().list_devices().await?;
    Ok(Json(serde_json::json!({ "devices": devices })))
}

pub(super) async fn devices_revoke(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.auth().revoke_device(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
