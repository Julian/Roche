use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::Arc,
};

use axum::{
    Form,
    body::{Body, to_bytes},
    extract::{ConnectInfo, Extension, Request, State, rejection::ExtensionRejection},
    http::{HeaderMap, HeaderValue, Method, StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::html::{redirect_see_other, render_error_page, render_login_page};
use crate::{error::ApiError, scheduler::FeedFetcher, service::RocheService};

pub(super) const SESSION_COOKIE_NAME: &str = "roche_session";
pub(super) const CSRF_HEADER_NAME: &str = "x-roche-csrf";
const TAILSCALE_LOGIN_HEADER: &str = "tailscale-user-login";
const MAX_FORM_BODY_FOR_CSRF: usize = 4 * 1024 * 1024;
const SESSION_MAX_AGE_SECS: u64 = 365 * 24 * 60 * 60;

#[derive(Debug, Clone)]
pub(super) struct WebSession {
    pub raw_token: String,
    pub csrf_token: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct LoginForm {
    pub pairing_code: String,
    #[serde(default)]
    pub device_name: Option<String>,
}

#[must_use]
pub(super) fn extract_session_cookie(headers: &HeaderMap) -> Option<String> {
    for val in headers.get_all(header::COOKIE) {
        let Ok(raw) = val.to_str() else {
            continue;
        };
        for part in raw.split(';') {
            if let Some((name, value)) = part.trim().split_once('=')
                && name.trim() == SESSION_COOKIE_NAME
            {
                let clean = value.trim().trim_matches('"');
                if !clean.is_empty() {
                    return Some(clean.to_owned());
                }
            }
        }
    }
    None
}

#[must_use]
fn is_forwarded_https(headers: &HeaderMap) -> bool {
    headers
        .get("x-forwarded-proto")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.split(',').next())
        .is_some_and(|proto| proto.trim().eq_ignore_ascii_case("https"))
}

#[must_use]
fn build_session_set_cookie(raw_token: &str, secure: bool) -> String {
    let secure_attr = if secure { "; Secure" } else { "" };
    format!(
        "{SESSION_COOKIE_NAME}={raw_token}; Path=/; HttpOnly; SameSite=Lax; Max-Age={SESSION_MAX_AGE_SECS}{secure_attr}"
    )
}

#[must_use]
fn build_clear_session_cookie(secure: bool) -> String {
    let secure_attr = if secure { "; Secure" } else { "" };
    format!("{SESSION_COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0{secure_attr}")
}

/// Checks whether `Tailscale-User-Login` can be trusted:
/// strictly requires a loopback TCP peer (`127.0.0.1` / `::1`, i.e. local `tailscale serve` reverse proxy),
/// `tailscale.enabled == true`, and membership in `tailscale.auto_auth_users`.
#[must_use]
fn verified_tailscale_login<F: FeedFetcher>(
    service: &RocheService<F>,
    peer_addr: Option<SocketAddr>,
    headers: &HeaderMap,
) -> Option<String> {
    let cfg = &service.config().tailscale;
    if !cfg.enabled || cfg.auto_auth_users.is_empty() {
        return None;
    }
    let addr = peer_addr?;
    if !addr.ip().is_loopback() {
        return None;
    }
    let raw_login = headers.get(TAILSCALE_LOGIN_HEADER)?.to_str().ok()?;
    let normalized = raw_login.trim().to_ascii_lowercase();
    if normalized.is_empty() {
        return None;
    }
    cfg.auto_auth_users.iter().any(|allowed| allowed == &normalized).then_some(normalized)
}

pub(super) async fn login_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
) -> Response {
    if let Some(token) = extract_session_cookie(&headers)
        && service.auth().authenticate(&token).await.is_ok()
    {
        return redirect_see_other("/");
    }

    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    if let Some(ts_login) = verified_tailscale_login(&service, peer_addr, &headers)
        && let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await
    {
        let mut resp = redirect_see_other("/");
        let cookie = build_session_set_cookie(&minted, is_forwarded_https(&headers));
        if let Ok(val) = HeaderValue::from_str(&cookie) {
            resp.headers_mut().insert(header::SET_COOKIE, val);
        }
        return resp;
    }

    render_login_page(None, service.config().tailscale.enabled).into_response()
}

pub(super) async fn login_post<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> Response {
    let client_ip =
        connect_info.map_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED), |ConnectInfo(addr)| addr.ip());
    let label =
        form.device_name.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or("Roche Web");

    match service.pair_device(label, &form.pairing_code, client_ip).await {
        Ok(raw_token) => {
            let mut resp = redirect_see_other("/");
            let cookie = build_session_set_cookie(&raw_token, is_forwarded_https(&headers));
            if let Ok(val) = HeaderValue::from_str(&cookie) {
                resp.headers_mut().insert(header::SET_COOKIE, val);
            }
            resp
        }
        Err(err) => {
            let (status, msg) = match &err {
                ApiError::TooManyRequests(m) => (StatusCode::TOO_MANY_REQUESTS, m.as_str()),
                ApiError::Unauthorized(m) => (StatusCode::UNAUTHORIZED, m.as_str()),
                ApiError::BadRequest(m) => (StatusCode::BAD_REQUEST, m.as_str()),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, "Unable to complete browser pairing."),
            };
            (status, render_login_page(Some(msg), service.config().tailscale.enabled))
                .into_response()
        }
    }
}

pub(super) async fn logout_post<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    headers: HeaderMap,
) -> Response {
    let _ = service.auth().revoke_session_token(&session.raw_token).await;
    let mut resp = redirect_see_other("/login");
    let clear_cookie = build_clear_session_cookie(is_forwarded_https(&headers));
    if let Ok(val) = HeaderValue::from_str(&clear_cookie) {
        resp.headers_mut().insert(header::SET_COOKIE, val);
    }
    resp
}

/// Protects all web routes:
/// - Authenticates `roche_session` cookie (or loopback `Tailscale-User-Login` on safe `GET`/`HEAD` requests).
/// - Enforces per-session CSRF token validation on all state-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`).
pub(super) async fn require_web_auth_and_csrf<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    req: Request,
    next: Next,
) -> Response {
    let is_safe_method = matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS);
    let secure_cookie = is_forwarded_https(req.headers());

    let mut active_token: Option<String> = None;
    let mut newly_minted_cookie: Option<String> = None;

    if let Some(cookie_tok) = extract_session_cookie(req.headers())
        && service.auth().authenticate(&cookie_tok).await.is_ok()
    {
        active_token = Some(cookie_tok);
    }

    // Only auto-mint a Tailscale session on safe navigational requests; state-changing POSTs
    // must already hold a session cookie + matching CSRF token so cross-site POSTs cannot ride Tailscale headers.
    if active_token.is_none() && is_safe_method {
        let peer_addr = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|ci| ci.0);
        if let Some(ts_login) = verified_tailscale_login(&service, peer_addr, req.headers())
            && let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await
        {
            newly_minted_cookie = Some(build_session_set_cookie(&minted, secure_cookie));
            active_token = Some(minted);
        }
    }

    let Some(raw_token) = active_token else {
        return if is_safe_method {
            redirect_see_other("/login")
        } else {
            render_error_page(
                StatusCode::UNAUTHORIZED,
                "Session Required",
                "Please sign in before submitting changes.",
                None,
            )
        };
    };

    let csrf_token = service.auth().csrf_token_for_session(&raw_token);
    let session = WebSession { raw_token: raw_token.clone(), csrf_token: csrf_token.clone() };

    let final_req = if is_safe_method {
        let mut req = req;
        req.extensions_mut().insert(session);
        req
    } else {
        match verify_and_rebuild_mutating_request(&service, &raw_token, session, req).await {
            Ok(rebuilt) => rebuilt,
            Err(resp) => return *resp,
        }
    };

    let mut resp = next.run(final_req).await;
    if let Some(cookie_str) = newly_minted_cookie
        && let Ok(val) = HeaderValue::from_str(&cookie_str)
    {
        resp.headers_mut().insert(header::SET_COOKIE, val);
    }
    resp
}

async fn verify_and_rebuild_mutating_request<F: FeedFetcher>(
    service: &RocheService<F>,
    raw_token: &str,
    session: WebSession,
    req: Request,
) -> Result<Request, Box<Response>> {
    if let Some(hdr_val) = req.headers().get(CSRF_HEADER_NAME).and_then(|v| v.to_str().ok())
        && service.auth().verify_csrf_token(raw_token, hdr_val)
    {
        let mut req = req;
        req.extensions_mut().insert(session);
        return Ok(req);
    }

    let is_form =
        req.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).is_some_and(|ct| {
            ct.to_ascii_lowercase().starts_with("application/x-www-form-urlencoded")
        });

    if !is_form {
        return Err(Box::new(render_error_page(
            StatusCode::FORBIDDEN,
            "Invalid CSRF Token",
            "Missing or invalid security token on form submission.",
            Some(&session.csrf_token),
        )));
    }

    let (mut parts, body) = req.into_parts();
    let Ok(bytes) = to_bytes(body, MAX_FORM_BODY_FOR_CSRF).await else {
        return Err(Box::new(render_error_page(
            StatusCode::PAYLOAD_TOO_LARGE,
            "Payload Too Large",
            "Submitted form exceeded the maximum allowed size.",
            Some(&session.csrf_token),
        )));
    };

    let form_csrf = url::form_urlencoded::parse(&bytes)
        .find(|(k, _)| k == "_csrf")
        .map(|(_, v)| v.into_owned());

    let Some(candidate) = form_csrf else {
        return Err(Box::new(render_error_page(
            StatusCode::FORBIDDEN,
            "Invalid CSRF Token",
            "Missing security token on form submission.",
            Some(&session.csrf_token),
        )));
    };

    if !service.auth().verify_csrf_token(raw_token, &candidate) {
        return Err(Box::new(render_error_page(
            StatusCode::FORBIDDEN,
            "Invalid CSRF Token",
            "Expired or mismatched security token; please reload the page and try again.",
            Some(&session.csrf_token),
        )));
    }

    parts.extensions.insert(session);
    Ok(Request::from_parts(parts, Body::from(bytes)))
}
