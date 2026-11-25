use std::{net::SocketAddr, sync::Arc};

use axum::{
    Extension, Json,
    extract::{ConnectInfo, State, rejection::ExtensionRejection},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use serde::{Deserialize, Serialize};

use super::error::{ApiError, ProblemJson, ProblemPath};
use crate::{
    service::RocheService,
    util::http_security::is_forwarded_https,
    web::auth::{
        AuthenticatedWebSession, HOST_SESSION_COOKIE_NAME, append_clear_flash_cookies_on_logout,
        append_clear_logged_out_cookies, append_clear_session_cookies, append_logged_out_cookie,
        append_session_set_cookies, authenticate_request_session, extract_last_forwarded_for,
        extract_named_cookie, extract_single_tailscale_login, is_same_origin_post,
        verified_tailscale_login,
    },
};

#[derive(Debug, Deserialize)]
pub(super) struct PairRequest {
    device_name: String,
    pairing_code: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct WebLoginRequest {
    pairing_code: String,
    #[serde(default)]
    device_name: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct SessionStatusResponse {
    authenticated: bool,
    csrf_token: Option<String>,
    tailscale_enabled: bool,
    tailscale_user: Option<String>,
    is_refreshing: bool,
    active_refresh_count: usize,
    unread_items_count: i64,
}

#[derive(Debug, Serialize)]
pub(super) struct WebLoginResponse {
    authenticated: bool,
    csrf_token: String,
}

pub(super) async fn auth_pair(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    ProblemJson(req): ProblemJson<PairRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let tailscale_user_login = extract_single_tailscale_login(&headers);
    let x_forwarded_for = extract_last_forwarded_for(&headers);
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

/// Public session bootstrap endpoint for the SPA.
///
/// Intentionally does NOT perform passive Tailscale auto-login (`fetch` requests are not
/// top-level document navigations). Passive auto-login stays strictly on navigational
/// HTML `GET` requests in the SPA shell handler.
pub(super) async fn auth_session_get(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
) -> Json<SessionStatusResponse> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let raw_token = authenticate_request_session(&service, &headers, secure).await;
    let tailscale_user = verified_tailscale_login(&service, peer_addr, &headers);
    let tailscale_enabled = service.config().tailscale.enabled;

    let (authenticated, csrf_token, is_refreshing, active_refresh_count, unread_items_count) =
        if let Some(ref tok) = raw_token {
            let csrf = service.auth().csrf_token_for_session(tok);
            let active = service.active_refresh_count();
            let unread = service.items().count_unread().await.unwrap_or(0);
            (true, Some(csrf), service.is_refreshing(), active, unread)
        } else {
            (false, None, false, 0, 0)
        };

    Json(SessionStatusResponse {
        authenticated,
        csrf_token,
        tailscale_enabled,
        tailscale_user,
        is_refreshing,
        active_refresh_count,
        unread_items_count,
    })
}

pub(super) async fn auth_login_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    ProblemJson(req): ProblemJson<WebLoginRequest>,
) -> Result<Response, ApiError> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    if !is_same_origin_post(&headers, secure) {
        return Err(ApiError::forbidden("Cross-site sign-in requests are not permitted."));
    }
    let label =
        req.device_name.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or("Roche Web");
    let raw_ts_login_hdr = extract_single_tailscale_login(&headers);
    let x_forwarded_for = extract_last_forwarded_for(&headers);

    let raw_token = service
        .auth()
        .pair_web_session_for_request(
            label,
            &req.pairing_code,
            peer_addr,
            raw_ts_login_hdr,
            x_forwarded_for,
        )
        .await?;

    let csrf_token = service.auth().csrf_token_for_session(&raw_token);
    let had_host_session = extract_named_cookie(&headers, HOST_SESSION_COOKIE_NAME).is_some();
    let mut resp = Json(WebLoginResponse { authenticated: true, csrf_token }).into_response();
    append_session_set_cookies(&mut resp, &raw_token, secure, had_host_session);
    append_clear_logged_out_cookies(&mut resp, &headers, secure);
    Ok(resp)
}

pub(super) async fn auth_login_tailscale_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    if !is_same_origin_post(&headers, secure) {
        return Err(ApiError::forbidden("Cross-site sign-in requests are not permitted."));
    }
    let Some(ts_login) = verified_tailscale_login(&service, peer_addr, &headers) else {
        return Err(ApiError::unauthorized(
            "Tailscale automatic sign-in is not available for this connection.",
        ));
    };
    let minted = service.auth().mint_tailscale_web_session(&ts_login).await?;
    let csrf_token = service.auth().csrf_token_for_session(&minted);
    let had_host_session = extract_named_cookie(&headers, HOST_SESSION_COOKIE_NAME).is_some();
    let mut resp = Json(WebLoginResponse { authenticated: true, csrf_token }).into_response();
    append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
    append_clear_logged_out_cookies(&mut resp, &headers, secure);
    Ok(resp)
}

pub(super) async fn auth_logout_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    web_session: Option<Extension<AuthenticatedWebSession>>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let Extension(session) = web_session
        .ok_or_else(|| ApiError::forbidden("Browser session cookie required for logout"))?;
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let _ = service.auth().revoke_session_token(&session.raw_token).await;
    let mut resp = StatusCode::NO_CONTENT.into_response();
    append_clear_session_cookies(&mut resp, &headers, secure);
    append_logged_out_cookie(&mut resp, secure);
    append_clear_flash_cookies_on_logout(&mut resp, &headers, secure);
    Ok(resp)
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
