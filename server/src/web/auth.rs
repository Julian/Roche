use std::{net::SocketAddr, sync::Arc};

use axum::{
    Form,
    body::{Body, to_bytes},
    extract::{
        ConnectInfo, Extension, Query, Request, State,
        rejection::{ExtensionRejection, QueryRejection},
    },
    http::{HeaderMap, HeaderValue, Method, StatusCode, header, uri::Authority},
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    MAX_WEB_FORM_BODY_BYTES, MAX_WEB_OPML_BODY_BYTES, OPML_UPLOAD_PATH,
    html::{
        FlashKind, FlashQuery, LoginPageArgs, PendingFlash, redirect_see_other, render_error_page,
        render_login_page, safe_local_redirect, sanitize_flash_message, urlencode,
    },
};
use crate::{
    service::{AppError, RocheService, WEB_SESSION_MAX_AGE_SECS},
    util::http_security::is_forwarded_https,
};

pub(super) const SESSION_COOKIE_NAME: &str = "roche_session";
pub(super) const HOST_SESSION_COOKIE_NAME: &str = "__Host-roche_session";
pub(super) const LOGGED_OUT_COOKIE_NAME: &str = "roche_logged_out";
pub(super) const HOST_LOGGED_OUT_COOKIE_NAME: &str = "__Host-roche_logged_out";
pub(super) const FLASH_COOKIE_NAME: &str = "roche_flash";
pub(super) const HOST_FLASH_COOKIE_NAME: &str = "__Host-roche_flash";
pub(super) const THEME_COOKIE_NAME: &str = "roche_theme";
pub(super) const RANK_K_COOKIE_NAME: &str = "roche_rank_k";
pub(super) const CSRF_HEADER_NAME: &str = "x-roche-csrf";
const TAILSCALE_LOGIN_HEADER: &str = "tailscale-user-login";
const FLASH_MAX_AGE_SECS: u64 = 60;
const PREF_COOKIE_MAX_AGE_SECS: u64 = 365 * 24 * 60 * 60;

#[derive(Debug, Clone)]
pub(super) struct WebSession {
    pub raw_token: String,
    pub csrf_token: String,
    pub theme: Option<&'static str>,
    pub rank_k: Option<usize>,
    pub secure_cookie: bool,
    pub flash: FlashQuery,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct LoginQuery {
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct LoginForm {
    pub pairing_code: String,
    #[serde(default)]
    pub device_name: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct TailscaleLoginForm {
    #[serde(default)]
    pub return_to: Option<String>,
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

/// Authenticates the incoming request's session cookie against `web_sessions`.
/// - Over HTTPS (`secure == true`), strictly accepts `__Host-roche_session` only
///   to block HTTP cookie-tossing.
/// - Over HTTP (`secure == false`), prefers unprefixed `roche_session` first and falls back
///   to `__Host-roche_session` only if absent or invalid, preventing a revoked/expired
///   `__Host-roche_session` from shadowing a newly minted HTTP session on `localhost`.
async fn authenticate_request_session(
    service: &RocheService,
    headers: &HeaderMap,
    secure: bool,
) -> Option<String> {
    if secure {
        let token = extract_named_cookie(headers, HOST_SESSION_COOKIE_NAME)?;
        return service.auth().authenticate_web_session(&token).await.ok().map(|_| token);
    }
    let plain = extract_named_cookie(headers, SESSION_COOKIE_NAME);
    if let Some(ref token) = plain
        && service.auth().authenticate_web_session(token).await.is_ok()
    {
        return plain;
    }
    if let Some(host_tok) = extract_named_cookie(headers, HOST_SESSION_COOKIE_NAME)
        && plain.as_deref() != Some(host_tok.as_str())
        && service.auth().authenticate_web_session(&host_tok).await.is_ok()
    {
        return Some(host_tok);
    }
    None
}

#[must_use]
pub(super) fn has_logged_out_cookie(headers: &HeaderMap, secure: bool) -> bool {
    if secure {
        extract_named_cookie(headers, HOST_LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
    } else {
        extract_named_cookie(headers, LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
            || extract_named_cookie(headers, HOST_LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
    }
}

#[must_use]
fn decode_flash_cookie(raw: &str) -> FlashQuery {
    let Some((prefix, encoded)) = raw.split_once(':') else {
        return FlashQuery::default();
    };
    let decoded = url::form_urlencoded::parse(format!("v={encoded}").as_bytes())
        .next()
        .map_or_default(|(_, val)| val.into_owned());
    let clean = sanitize_flash_message(&decoded);
    if clean.is_empty() {
        return FlashQuery::default();
    }
    match prefix {
        "e" => FlashQuery { notice: None, err: Some(clean) },
        "n" => FlashQuery { notice: Some(clean), err: None },
        _ => FlashQuery::default(),
    }
}

#[derive(Debug, Default, Clone, Copy)]
struct FlashCookiesPresent {
    host: bool,
    plain: bool,
}

/// Extracts the one-shot flash cookie and reports which flash cookie names were present
/// so they can be cleared on a `2xx` HTML page response.
/// - Over HTTPS (`secure == true`), strictly reads `__Host-roche_flash` only.
/// - Over HTTP (`secure == false`), prefers `roche_flash` first so a stale `__Host-roche_flash`
///   never shadows a freshly written HTTP flash.
#[must_use]
fn extract_flash_cookie(headers: &HeaderMap, secure: bool) -> (FlashQuery, FlashCookiesPresent) {
    let host_raw = extract_named_cookie(headers, HOST_FLASH_COOKIE_NAME);
    if secure {
        let present = FlashCookiesPresent { host: host_raw.is_some(), plain: false };
        let flash = host_raw.as_deref().map_or_default(decode_flash_cookie);
        return (flash, present);
    }
    let plain_raw = extract_named_cookie(headers, FLASH_COOKIE_NAME);
    let present = FlashCookiesPresent { host: host_raw.is_some(), plain: plain_raw.is_some() };
    if let Some(raw) = plain_raw.as_deref() {
        let parsed = decode_flash_cookie(raw);
        if parsed.notice.is_some() || parsed.err.is_some() {
            return (parsed, present);
        }
    }
    let flash = host_raw.as_deref().map_or_default(decode_flash_cookie);
    (flash, present)
}

#[must_use]
pub(super) fn extract_theme_cookie(headers: &HeaderMap) -> Option<&'static str> {
    match extract_named_cookie(headers, THEME_COOKIE_NAME).as_deref() {
        Some("dark") => Some("dark"),
        Some("light") => Some("light"),
        _ => None,
    }
}

#[must_use]
pub(super) fn extract_rank_k_cookie(headers: &HeaderMap) -> Option<usize> {
    let raw = extract_named_cookie(headers, RANK_K_COOKIE_NAME)?;
    let parsed = raw.parse::<usize>().ok()?;
    (crate::ranking::MIN_K..=crate::ranking::MAX_K_DEFAULT).contains(&parsed).then_some(parsed)
}

pub(super) fn append_rank_k_set_cookie(resp: &mut Response, rank_k: usize, secure: bool) {
    let clamped = rank_k.clamp(crate::ranking::MIN_K, crate::ranking::MAX_K_DEFAULT);
    let secure_part = if secure { "; Secure" } else { "" };
    let val = format!(
        "{RANK_K_COOKIE_NAME}={clamped}; Path=/; Max-Age={PREF_COOKIE_MAX_AGE_SECS}; SameSite=Lax{secure_part}"
    );
    append_set_cookie(resp, &val);
}

#[must_use]
fn safe_login_return_to(candidate: Option<&str>) -> String {
    let dest = safe_local_redirect(candidate, "/");
    let path = dest.split(['?', '#']).next().unwrap_or("/");
    if matches!(path, "/login" | "/login/tailscale" | "/logout")
        || path.starts_with("/web/")
        || path.starts_with("/api/")
    {
        "/".to_owned()
    } else {
        dest
    }
}

/// Returns `Some` only when there is a single valid `Tailscale-User-Login` header value
/// (rejecting duplicate/ambiguous headers or header smuggling).
#[must_use]
fn extract_single_tailscale_login(headers: &HeaderMap) -> Option<&str> {
    let mut iter = headers.get_all(TAILSCALE_LOGIN_HEADER).iter();
    let first = iter.next()?.to_str().ok()?;
    if iter.next().is_some() {
        return None;
    }
    let trimmed = first.trim();
    if trimmed.is_empty()
        || trimmed.len() > 256
        || trimmed.contains(',')
        || trimmed.chars().any(char::is_control)
    {
        return None;
    }
    Some(trimmed)
}

#[must_use]
fn extract_last_forwarded_for(headers: &HeaderMap) -> Option<&str> {
    headers.get_all("x-forwarded-for").iter().next_back().and_then(|v| v.to_str().ok())
}

/// Requires browser Fetch Metadata headers proving a direct/same-origin top-level document
/// navigation before passively minting a `roche_session` from `Tailscale-User-Login`.
///
/// This blocks cross-site subresource `GET` requests (e.g. `<img>`, `no-cors` `fetch`) that omit
/// `SameSite=Lax` `roche_logged_out=1` cookies from silently re-authenticating an explicitly
/// logged-out browser.
#[must_use]
fn is_navigational_tailscale_auto_login(headers: &HeaderMap) -> bool {
    let mode_ok = headers
        .get("sec-fetch-mode")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.trim().eq_ignore_ascii_case("navigate"));
    let dest_ok = headers
        .get("sec-fetch-dest")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.trim().eq_ignore_ascii_case("document"));
    let site_ok = headers.get("sec-fetch-site").and_then(|v| v.to_str().ok()).is_some_and(|v| {
        let clean = v.trim();
        clean.eq_ignore_ascii_case("none") || clean.eq_ignore_ascii_case("same-origin")
    });
    mode_ok && dest_ok && site_ok
}

#[must_use]
fn build_named_cookie(name: &str, value: &str, max_age_secs: u64, secure: bool) -> String {
    let secure_attr = if secure { "; Secure" } else { "" };
    format!("{name}={value}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age_secs}{secure_attr}")
}

fn append_session_set_cookies(
    resp: &mut Response,
    raw_token: &str,
    secure: bool,
    had_host_session: bool,
) {
    let primary_name = if secure { HOST_SESSION_COOKIE_NAME } else { SESSION_COOKIE_NAME };
    append_set_cookie(
        resp,
        &build_named_cookie(primary_name, raw_token, WEB_SESSION_MAX_AGE_SECS, secure),
    );
    if secure {
        append_set_cookie(resp, &build_named_cookie(SESSION_COOKIE_NAME, "", 0, true));
    } else if had_host_session {
        append_set_cookie(resp, &build_named_cookie(HOST_SESSION_COOKIE_NAME, "", 0, true));
    }
}

fn append_clear_session_cookies(resp: &mut Response, req_headers: &HeaderMap, secure: bool) {
    if secure || extract_named_cookie(req_headers, HOST_SESSION_COOKIE_NAME).is_some() {
        append_set_cookie(resp, &build_named_cookie(HOST_SESSION_COOKIE_NAME, "", 0, true));
    }
    append_set_cookie(resp, &build_named_cookie(SESSION_COOKIE_NAME, "", 0, secure));
}

fn append_logged_out_cookie(resp: &mut Response, secure: bool) {
    let name = if secure { HOST_LOGGED_OUT_COOKIE_NAME } else { LOGGED_OUT_COOKIE_NAME };
    append_set_cookie(resp, &build_named_cookie(name, "1", WEB_SESSION_MAX_AGE_SECS, secure));
}

fn append_clear_logged_out_cookies(resp: &mut Response, req_headers: &HeaderMap, secure: bool) {
    if secure || extract_named_cookie(req_headers, HOST_LOGGED_OUT_COOKIE_NAME).is_some() {
        append_set_cookie(resp, &build_named_cookie(HOST_LOGGED_OUT_COOKIE_NAME, "", 0, true));
    }
    append_set_cookie(resp, &build_named_cookie(LOGGED_OUT_COOKIE_NAME, "", 0, secure));
}

pub(super) fn append_flash_set_cookie(resp: &mut Response, pending: &PendingFlash, secure: bool) {
    let prefix = match pending.kind {
        FlashKind::Err => "e",
        FlashKind::Notice => "n",
    };
    let name = if secure { HOST_FLASH_COOKIE_NAME } else { FLASH_COOKIE_NAME };
    let val = format!("{prefix}:{}", urlencode(&pending.message));
    append_set_cookie(resp, &build_named_cookie(name, &val, FLASH_MAX_AGE_SECS, secure));
}

fn append_clear_flash_cookies_on_logout(
    resp: &mut Response,
    req_headers: &HeaderMap,
    secure: bool,
) {
    if secure || extract_named_cookie(req_headers, HOST_FLASH_COOKIE_NAME).is_some() {
        append_set_cookie(resp, &build_named_cookie(HOST_FLASH_COOKIE_NAME, "", 0, true));
    }
    append_set_cookie(resp, &build_named_cookie(FLASH_COOKIE_NAME, "", 0, secure));
}

fn append_clear_consumed_flash_cookies(
    resp: &mut Response,
    present: FlashCookiesPresent,
    secure: bool,
) {
    if present.host {
        append_set_cookie(resp, &build_named_cookie(HOST_FLASH_COOKIE_NAME, "", 0, true));
    }
    if present.plain {
        append_set_cookie(resp, &build_named_cookie(FLASH_COOKIE_NAME, "", 0, secure));
    }
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
/// strictly requires a loopback TCP peer (`127.0.0.1` / `::1` / `::ffff:127.0.0.1`, i.e. local
/// `tailscale serve` reverse proxy), `tailscale.enabled == true`, a single valid header value,
/// and membership in `tailscale.auto_auth_users`.
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
    if !addr.ip().to_canonical().is_loopback() {
        return None;
    }
    let raw_login = extract_single_tailscale_login(headers)?;
    let normalized = raw_login.to_ascii_lowercase();
    cfg.auto_auth_users.iter().any(|allowed| allowed == &normalized).then_some(normalized)
}

pub(super) async fn login_get(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    query: Result<Query<LoginQuery>, QueryRejection>,
) -> Response {
    let return_to =
        safe_login_return_to(query.ok().as_ref().and_then(|Query(q)| q.return_to.as_deref()));
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let theme = extract_theme_cookie(&headers);
    if authenticate_request_session(&service, &headers, secure).await.is_some() {
        return redirect_see_other(&return_to);
    }

    let had_host_session = extract_named_cookie(&headers, HOST_SESSION_COOKIE_NAME).is_some();
    let ts_login = verified_tailscale_login(&service, peer_addr, &headers);
    if !has_logged_out_cookie(&headers, secure)
        && is_navigational_tailscale_auto_login(&headers)
        && let Some(ref login) = ts_login
        && let Ok(minted) = service.auth().mint_tailscale_web_session(login).await
    {
        let mut resp = redirect_see_other(&return_to);
        append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
        return resp;
    }

    render_login_page(&LoginPageArgs {
        error_msg: None,
        tailscale_enabled: service.config().tailscale.enabled,
        tailscale_user: ts_login.as_deref(),
        return_to: &return_to,
        device_name: None,
        theme,
    })
    .into_response()
}

pub(super) async fn login_post(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    Form(form): Form<LoginForm>,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let theme = extract_theme_cookie(&headers);
    let return_to = safe_login_return_to(form.return_to.as_deref());
    if !is_same_origin_post(&headers, secure) {
        return render_error_page(
            StatusCode::FORBIDDEN,
            "Forbidden",
            "Cross-site sign-in requests are not permitted.",
            None,
            theme,
        );
    }
    let ts_login = verified_tailscale_login(&service, peer_addr, &headers);
    let submitted_device_name =
        form.device_name.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let label = submitted_device_name.unwrap_or("Roche Web");
    let raw_ts_login_hdr = extract_single_tailscale_login(&headers);
    let x_forwarded_for = extract_last_forwarded_for(&headers);

    match service
        .auth()
        .pair_web_session_for_request(
            label,
            &form.pairing_code,
            peer_addr,
            raw_ts_login_hdr,
            x_forwarded_for,
        )
        .await
    {
        Ok(raw_token) => {
            let had_host_session =
                extract_named_cookie(&headers, HOST_SESSION_COOKIE_NAME).is_some();
            let mut resp = redirect_see_other(&return_to);
            append_session_set_cookies(&mut resp, &raw_token, secure, had_host_session);
            append_clear_logged_out_cookies(&mut resp, &headers, secure);
            resp
        }
        Err(err) => {
            let (status, msg, retry_after) = match &err {
                AppError::PairingRateLimited { detail, retry_after_seconds } => {
                    (StatusCode::TOO_MANY_REQUESTS, detail.as_str(), Some(*retry_after_seconds))
                }
                AppError::TokenInvalid(m) | AppError::PairingCodeInvalid(m) => {
                    (StatusCode::UNAUTHORIZED, m.as_str(), None)
                }
                AppError::BadRequest(m) | AppError::SsrfBlocked(m) => {
                    (StatusCode::BAD_REQUEST, m.as_str(), None)
                }
                _ => {
                    (StatusCode::INTERNAL_SERVER_ERROR, "Unable to complete browser pairing.", None)
                }
            };
            let mut resp = (
                status,
                render_login_page(&LoginPageArgs {
                    error_msg: Some(msg),
                    tailscale_enabled: service.config().tailscale.enabled,
                    tailscale_user: ts_login.as_deref(),
                    return_to: &return_to,
                    device_name: submitted_device_name,
                    theme,
                }),
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
    form: Result<Form<TailscaleLoginForm>, axum::extract::rejection::FormRejection>,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let theme = extract_theme_cookie(&headers);
    let return_to =
        safe_login_return_to(form.ok().as_ref().and_then(|Form(f)| f.return_to.as_deref()));
    if !is_same_origin_post(&headers, secure) {
        return render_error_page(
            StatusCode::FORBIDDEN,
            "Forbidden",
            "Cross-site sign-in requests are not permitted.",
            None,
            theme,
        );
    }
    let Some(ts_login) = verified_tailscale_login(&service, peer_addr, &headers) else {
        return (
            StatusCode::UNAUTHORIZED,
            render_login_page(&LoginPageArgs {
                error_msg: Some(
                    "Tailscale automatic sign-in is not available for this connection.",
                ),
                tailscale_enabled: service.config().tailscale.enabled,
                tailscale_user: None,
                return_to: &return_to,
                device_name: None,
                theme,
            }),
        )
            .into_response();
    };
    let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await else {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            render_login_page(&LoginPageArgs {
                error_msg: Some("Unable to start Tailscale browser session."),
                tailscale_enabled: service.config().tailscale.enabled,
                tailscale_user: Some(&ts_login),
                return_to: &return_to,
                device_name: None,
                theme,
            }),
        )
            .into_response();
    };
    let had_host_session = extract_named_cookie(&headers, HOST_SESSION_COOKIE_NAME).is_some();
    let mut resp = redirect_see_other(&return_to);
    append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
    append_clear_logged_out_cookies(&mut resp, &headers, secure);
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
    append_clear_session_cookies(&mut resp, &headers, secure);
    append_logged_out_cookie(&mut resp, secure);
    append_clear_flash_cookies_on_logout(&mut resp, &headers, secure);
    resp
}

/// Protects all web routes:
/// - Authenticates scoped `web_sessions` cookie (or loopback `Tailscale-User-Login` on safe
///   `GET`/`HEAD` requests when the user has not explicitly logged out).
/// - Loads and consumes one-shot `HttpOnly` flash cookies on HTML page `GET` responses.
/// - Enforces per-request masked CSRF token validation on all state-changing requests.
pub(super) async fn require_web_auth_and_csrf(
    State(service): State<Arc<RocheService>>,
    req: Request,
    next: Next,
) -> Response {
    let is_safe_method = matches!(*req.method(), Method::GET | Method::HEAD | Method::OPTIONS);
    let path = req.uri().path();
    let is_page_get =
        *req.method() == Method::GET && !path.starts_with("/web/") && !path.starts_with("/static/");
    let peer_addr = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|ci| ci.0);
    let secure_cookie = is_forwarded_https(peer_addr, req.headers());
    let theme = extract_theme_cookie(req.headers());
    let rank_k = extract_rank_k_cookie(req.headers());
    let had_host_session = extract_named_cookie(req.headers(), HOST_SESSION_COOKIE_NAME).is_some();

    let mut active_token =
        authenticate_request_session(&service, req.headers(), secure_cookie).await;
    let mut newly_minted_token: Option<String> = None;

    // Only auto-mint a Tailscale session on direct/same-origin top-level navigational GETs when
    // not explicitly logged out; cross-site subresource GETs and state-changing POSTs cannot ride
    // Tailscale headers to bypass logout or CSRF.
    if active_token.is_none()
        && is_safe_method
        && !has_logged_out_cookie(req.headers(), secure_cookie)
        && is_navigational_tailscale_auto_login(req.headers())
        && let Some(ts_login) = verified_tailscale_login(&service, peer_addr, req.headers())
        && let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await
    {
        newly_minted_token = Some(minted.clone());
        active_token = Some(minted);
    }

    let Some(raw_token) = active_token else {
        return if is_safe_method {
            let return_to = safe_login_return_to(
                req.uri().path_and_query().map(axum::http::uri::PathAndQuery::as_str),
            );
            let dest = if return_to == "/" {
                "/login".to_owned()
            } else {
                format!("/login?return_to={}", urlencode(&return_to))
            };
            redirect_see_other(&dest)
        } else {
            render_error_page(
                StatusCode::UNAUTHORIZED,
                "Session Required",
                "Please sign in before submitting changes.",
                None,
                theme,
            )
        };
    };

    let (flash, flash_present) = if is_page_get {
        extract_flash_cookie(req.headers(), secure_cookie)
    } else {
        (FlashQuery::default(), FlashCookiesPresent::default())
    };
    let csrf_token = service.auth().csrf_token_for_session(&raw_token);
    let session = WebSession {
        raw_token: raw_token.clone(),
        csrf_token: csrf_token.clone(),
        theme,
        rank_k,
        secure_cookie,
        flash,
    };

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
    if let Some(minted) = newly_minted_token {
        append_session_set_cookies(&mut resp, &minted, secure_cookie, had_host_session);
    }
    if (flash_present.host || flash_present.plain)
        && resp.status().is_success()
        && resp.extensions().get::<PendingFlash>().is_none()
    {
        append_clear_consumed_flash_cookies(&mut resp, flash_present, secure_cookie);
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
            session.theme,
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
            session.theme,
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
            session.theme,
        )));
    };

    if !service.auth().verify_csrf_token(raw_token, &candidate) {
        return Err(Box::new(render_error_page(
            StatusCode::FORBIDDEN,
            "Invalid CSRF Token",
            "Expired or mismatched security token; please reload the page and try again.",
            Some(&session.csrf_token),
            session.theme,
        )));
    }

    parts.extensions.insert(session);
    Ok(Request::from_parts(parts, Body::from(bytes)))
}
