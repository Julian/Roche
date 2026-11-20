use std::{net::SocketAddr, sync::Arc};

use axum::{
    Form,
    body::{Body, to_bytes},
    extract::{ConnectInfo, Extension, Request, State, rejection::ExtensionRejection},
    http::{HeaderMap, HeaderValue, Method, StatusCode, header, uri::Authority},
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    MAX_WEB_FORM_BODY_BYTES, MAX_WEB_OPML_BODY_BYTES, OPML_UPLOAD_PATH,
    html::{redirect_see_other, render_error_page, render_login_page},
};
use crate::service::{AppError, RocheService};

pub(super) const SESSION_COOKIE_NAME: &str = "roche_session";
pub(super) const LOGGED_OUT_COOKIE_NAME: &str = "roche_logged_out";
pub(super) const CSRF_HEADER_NAME: &str = "x-roche-csrf";
const TAILSCALE_LOGIN_HEADER: &str = "tailscale-user-login";
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
fn extract_named_cookie(headers: &HeaderMap, cookie_name: &str) -> Option<String> {
    for val in headers.get_all(header::COOKIE) {
        let Ok(raw) = val.to_str() else {
            continue;
        };
        for part in raw.split(';') {
            if let Some((name, value)) = part.trim().split_once('=')
                && name.trim() == cookie_name
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
pub(super) fn extract_session_cookie(headers: &HeaderMap) -> Option<String> {
    extract_named_cookie(headers, SESSION_COOKIE_NAME)
}

#[must_use]
pub(super) fn has_logged_out_cookie(headers: &HeaderMap) -> bool {
    extract_named_cookie(headers, LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
}

/// Trusts `X-Forwarded-Proto: https` ONLY when the immediate TCP peer is loopback
/// (e.g. local `tailscale serve` or local TLS reverse proxy), preventing direct LAN/HTTP
/// peers from spoofing `X-Forwarded-Proto: https` and setting `Secure` cookies that browsers drop over HTTP.
#[must_use]
fn is_forwarded_https(peer_addr: Option<SocketAddr>, headers: &HeaderMap) -> bool {
    peer_addr.is_some_and(|addr| addr.ip().is_loopback())
        && headers
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

#[must_use]
fn build_logged_out_cookie(secure: bool) -> String {
    let secure_attr = if secure { "; Secure" } else { "" };
    format!(
        "{LOGGED_OUT_COOKIE_NAME}=1; Path=/; HttpOnly; SameSite=Lax; Max-Age={SESSION_MAX_AGE_SECS}{secure_attr}"
    )
}

#[must_use]
fn build_clear_logged_out_cookie(secure: bool) -> String {
    let secure_attr = if secure { "; Secure" } else { "" };
    format!("{LOGGED_OUT_COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0{secure_attr}")
}

fn append_set_cookie(resp: &mut Response, cookie: &str) {
    if let Ok(val) = HeaderValue::from_str(cookie) {
        resp.headers_mut().append(header::SET_COOKIE, val);
    }
}

fn url_matches_host(raw_url: &str, host_header: &str, expected_https: bool) -> bool {
    let Ok(parsed) = url::Url::parse(raw_url.trim()) else {
        return false;
    };
    let expected_scheme = if expected_https { "https" } else { "http" };
    if parsed.scheme() != expected_scheme {
        return false;
    }
    let host_clean = host_header.trim();
    if host_clean.is_empty()
        || host_clean.contains('/')
        || host_clean.contains('@')
        || host_clean.contains('?')
        || host_clean.contains('#')
        || host_clean.parse::<Authority>().is_err()
    {
        return false;
    }
    let Ok(expected) = url::Url::parse(&format!("{expected_scheme}://{host_clean}")) else {
        return false;
    };
    let (Some(got_host), Some(exp_host)) = (parsed.host_str(), expected.host_str()) else {
        return false;
    };
    got_host.eq_ignore_ascii_case(exp_host)
        && parsed.port_or_known_default() == expected.port_or_known_default()
}

#[must_use]
pub(super) fn is_same_origin_post(headers: &HeaderMap, expected_https: bool) -> bool {
    if let Some(site) = headers.get("sec-fetch-site").and_then(|v| v.to_str().ok()) {
        let clean = site.trim();
        return clean.eq_ignore_ascii_case("same-origin") || clean.eq_ignore_ascii_case("none");
    }
    let Some(host) = headers.get(header::HOST).and_then(|v| v.to_str().ok()) else {
        return false;
    };
    if let Some(origin) = headers.get(header::ORIGIN).and_then(|v| v.to_str().ok()) {
        return url_matches_host(origin, host, expected_https);
    }
    if let Some(referer) = headers.get(header::REFERER).and_then(|v| v.to_str().ok()) {
        return url_matches_host(referer, host, expected_https);
    }
    false
}

/// Checks whether `Tailscale-User-Login` can be trusted:
/// strictly requires a loopback TCP peer (`127.0.0.1` / `::1`, i.e. local `tailscale serve` reverse proxy),
/// `tailscale.enabled == true`, and membership in `tailscale.auto_auth_users`.
#[must_use]
fn verified_tailscale_login(
    service: &RocheService,
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

pub(super) async fn login_get(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
) -> Response {
    if let Some(token) = extract_session_cookie(&headers)
        && service.auth().authenticate(&token).await.is_ok()
    {
        return redirect_see_other("/");
    }

    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let ts_login = verified_tailscale_login(&service, peer_addr, &headers);
    if !has_logged_out_cookie(&headers)
        && let Some(ref login) = ts_login
        && let Ok(minted) = service.auth().mint_tailscale_web_session(login).await
    {
        let secure = is_forwarded_https(peer_addr, &headers);
        let mut resp = redirect_see_other("/");
        append_set_cookie(&mut resp, &build_session_set_cookie(&minted, secure));
        return resp;
    }

    render_login_page(None, service.config().tailscale.enabled, ts_login.as_deref()).into_response()
}

pub(super) async fn login_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    if !is_same_origin_post(&headers, secure) {
        return render_error_page(
            StatusCode::FORBIDDEN,
            "Forbidden",
            "Cross-site sign-in requests are not permitted.",
            None,
        );
    }
    let ts_login = verified_tailscale_login(&service, peer_addr, &headers);
    let label =
        form.device_name.as_deref().map(str::trim).filter(|s| !s.is_empty()).unwrap_or("Roche Web");
    let raw_ts_login_hdr = headers.get(TAILSCALE_LOGIN_HEADER).and_then(|v| v.to_str().ok());
    let x_forwarded_for = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok());

    match service
        .auth()
        .pair_device_for_request(
            label,
            &form.pairing_code,
            peer_addr,
            raw_ts_login_hdr,
            x_forwarded_for,
        )
        .await
    {
        Ok(raw_token) => {
            let mut resp = redirect_see_other("/");
            append_set_cookie(&mut resp, &build_session_set_cookie(&raw_token, secure));
            append_set_cookie(&mut resp, &build_clear_logged_out_cookie(secure));
            resp
        }
        Err(err) => {
            let (status, msg, retry_after) = match &err {
                AppError::PairingRateLimited { detail, retry_after_seconds } => {
                    (StatusCode::TOO_MANY_REQUESTS, detail.as_str(), Some(*retry_after_seconds))
                }
                AppError::Unauthorized(m)
                | AppError::TokenInvalid(m)
                | AppError::PairingCodeInvalid(m) => (StatusCode::UNAUTHORIZED, m.as_str(), None),
                AppError::BadRequest(m) | AppError::SsrfBlocked(m) => {
                    (StatusCode::BAD_REQUEST, m.as_str(), None)
                }
                _ => {
                    (StatusCode::INTERNAL_SERVER_ERROR, "Unable to complete browser pairing.", None)
                }
            };
            let mut resp = (
                status,
                render_login_page(
                    Some(msg),
                    service.config().tailscale.enabled,
                    ts_login.as_deref(),
                ),
            )
                .into_response();
            if let Some(secs) = retry_after
                && let Ok(val) = HeaderValue::from_str(&secs.to_string())
            {
                resp.headers_mut().insert(header::RETRY_AFTER, val);
            }
            resp
        }
    }
}

pub(super) async fn login_tailscale_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    if !is_same_origin_post(&headers, secure) {
        return render_error_page(
            StatusCode::FORBIDDEN,
            "Forbidden",
            "Cross-site sign-in requests are not permitted.",
            None,
        );
    }
    let Some(ts_login) = verified_tailscale_login(&service, peer_addr, &headers) else {
        return (
            StatusCode::UNAUTHORIZED,
            render_login_page(
                Some("Tailscale automatic sign-in is not available for this connection."),
                service.config().tailscale.enabled,
                None,
            ),
        )
            .into_response();
    };
    let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await else {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            render_login_page(
                Some("Unable to start Tailscale browser session."),
                service.config().tailscale.enabled,
                Some(&ts_login),
            ),
        )
            .into_response();
    };
    let mut resp = redirect_see_other("/");
    append_set_cookie(&mut resp, &build_session_set_cookie(&minted, secure));
    append_set_cookie(&mut resp, &build_clear_logged_out_cookie(secure));
    resp
}

pub(super) async fn logout_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    Extension(session): Extension<WebSession>,
    headers: HeaderMap,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let _ = service.auth().revoke_session_token(&session.raw_token).await;
    let mut resp = redirect_see_other("/login");
    append_set_cookie(&mut resp, &build_clear_session_cookie(secure));
    append_set_cookie(&mut resp, &build_logged_out_cookie(secure));
    resp
}

/// Protects all web routes:
/// - Authenticates `roche_session` cookie (or loopback `Tailscale-User-Login` on safe `GET`/`HEAD` requests
///   when the user has not explicitly logged out).
/// - Enforces per-session CSRF token validation on all state-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`).
pub(super) async fn require_web_auth_and_csrf(
    State(service): State<Arc<RocheService>>,
    req: Request,
    next: Next,
) -> Response {
    let is_safe_method = matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS);
    let peer_addr = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|ci| ci.0);
    let secure_cookie = is_forwarded_https(peer_addr, req.headers());

    let mut active_token: Option<String> = None;
    let mut newly_minted_cookie: Option<String> = None;

    if let Some(cookie_tok) = extract_session_cookie(req.headers())
        && service.auth().authenticate(&cookie_tok).await.is_ok()
    {
        active_token = Some(cookie_tok);
    }

    // Only auto-mint a Tailscale session on safe navigational requests when not explicitly logged out;
    // state-changing POSTs must already hold a session cookie + matching CSRF token so cross-site POSTs
    // cannot ride Tailscale headers.
    if active_token.is_none()
        && is_safe_method
        && !has_logged_out_cookie(req.headers())
        && let Some(ts_login) = verified_tailscale_login(&service, peer_addr, req.headers())
        && let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await
    {
        newly_minted_cookie = Some(build_session_set_cookie(&minted, secure_cookie));
        active_token = Some(minted);
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
    if let Some(cookie_str) = newly_minted_cookie {
        append_set_cookie(&mut resp, &cookie_str);
    }
    resp
}

async fn verify_and_rebuild_mutating_request(
    service: &RocheService,
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

    let max_body = if req.uri().path() == OPML_UPLOAD_PATH {
        MAX_WEB_OPML_BODY_BYTES
    } else {
        MAX_WEB_FORM_BODY_BYTES
    };
    let (mut parts, body) = req.into_parts();
    let Ok(bytes) = to_bytes(body, max_body).await else {
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
