use std::net::SocketAddr;

use axum::{
    http::{HeaderMap, HeaderValue, StatusCode, header, uri::Authority},
    response::{IntoResponse, Response},
};

use crate::service::{RocheService, WEB_SESSION_MAX_AGE_SECS};

pub const SESSION_COOKIE_NAME: &str = "roche_session";
pub const HOST_SESSION_COOKIE_NAME: &str = "__Host-roche_session";
pub const LOGGED_OUT_COOKIE_NAME: &str = "roche_logged_out";
pub const HOST_LOGGED_OUT_COOKIE_NAME: &str = "__Host-roche_logged_out";
const FLASH_COOKIE_NAME: &str = "roche_flash";
const HOST_FLASH_COOKIE_NAME: &str = "__Host-roche_flash";
pub const THEME_COOKIE_NAME: &str = "roche_theme";
pub const RANK_K_COOKIE_NAME: &str = "roche_rank_k";
pub const CSRF_HEADER_NAME: &str = "x-roche-csrf";
const TAILSCALE_LOGIN_HEADER: &str = "tailscale-user-login";
const PREF_COOKIE_MAX_AGE_SECS: u64 = 365 * 24 * 60 * 60;

/// Extension inserted into authenticated `/api/v1/*` requests that arrived with a valid
/// browser `web_sessions` cookie (and valid `X-Roche-Csrf` header on state-changing methods).
#[derive(Debug, Clone)]
pub struct AuthenticatedWebSession {
    pub raw_token: String,
}

#[must_use]
pub(super) fn urlencode(raw: &str) -> String {
    url::form_urlencoded::byte_serialize(raw.as_bytes()).collect()
}

/// Validates a relative redirect target so user-supplied `return_to` values cannot trigger open redirects.
#[must_use]
pub fn safe_local_redirect(candidate: Option<&str>, fallback: &str) -> String {
    if let Some(raw) = candidate.map(str::trim)
        && raw.starts_with('/')
        && !raw.starts_with("//")
        && !raw.contains('\\')
        && raw.is_ascii()
        && !raw.bytes().any(|b| b < 0x20 || b == 0x7f)
    {
        return raw.to_owned();
    }
    fallback.to_owned()
}

#[must_use]
pub fn safe_login_return_to(candidate: Option<&str>) -> String {
    let dest = safe_local_redirect(candidate, "/");
    let path = dest.split(['?', '#']).next().unwrap_or("/");
    if matches!(path, "/login" | "/login/tailscale" | "/logout" | "/api" | "/web")
        || path.starts_with("/web/")
        || path.starts_with("/api/")
    {
        "/".to_owned()
    } else {
        dest
    }
}

pub(super) fn redirect_see_other(location: &str) -> Response {
    let safe_loc = safe_local_redirect(Some(location), "/");
    let mut resp = StatusCode::SEE_OTHER.into_response();
    let val = HeaderValue::from_str(&safe_loc).unwrap_or_else(|_| HeaderValue::from_static("/"));
    resp.headers_mut().insert(header::LOCATION, val);
    resp
}

#[must_use]
pub fn extract_named_cookie(headers: &HeaderMap, cookie_name: &str) -> Option<String> {
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
pub async fn authenticate_request_session(
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
pub fn has_logged_out_cookie(headers: &HeaderMap, secure: bool) -> bool {
    if secure {
        extract_named_cookie(headers, HOST_LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
    } else {
        extract_named_cookie(headers, LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
            || extract_named_cookie(headers, HOST_LOGGED_OUT_COOKIE_NAME).is_some_and(|v| v == "1")
    }
}

#[must_use]
pub fn extract_theme_cookie(headers: &HeaderMap) -> Option<&'static str> {
    match extract_named_cookie(headers, THEME_COOKIE_NAME).as_deref() {
        Some("dark") => Some("dark"),
        Some("light") => Some("light"),
        _ => None,
    }
}

#[must_use]
pub fn extract_rank_k_cookie(headers: &HeaderMap) -> Option<usize> {
    let raw = extract_named_cookie(headers, RANK_K_COOKIE_NAME)?;
    let parsed = raw.parse::<usize>().ok()?;
    (crate::ranking::MIN_K..=crate::ranking::MAX_K_DEFAULT).contains(&parsed).then_some(parsed)
}

pub fn append_rank_k_set_cookie(resp: &mut Response, rank_k: usize, secure: bool) {
    let clamped = rank_k.clamp(crate::ranking::MIN_K, crate::ranking::MAX_K_DEFAULT);
    let secure_part = if secure { "; Secure" } else { "" };
    let val = format!(
        "{RANK_K_COOKIE_NAME}={clamped}; Path=/; Max-Age={PREF_COOKIE_MAX_AGE_SECS}; SameSite=Lax{secure_part}"
    );
    append_set_cookie(resp, &val);
}

/// Returns `Some` only when there is a single valid `Tailscale-User-Login` header value
/// (rejecting duplicate/ambiguous headers or header smuggling).
#[must_use]
pub fn extract_single_tailscale_login(headers: &HeaderMap) -> Option<&str> {
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
pub fn extract_last_forwarded_for(headers: &HeaderMap) -> Option<&str> {
    headers.get_all("x-forwarded-for").iter().next_back().and_then(|v| v.to_str().ok())
}

/// Returns `Ok(None)` when `header_name` is absent, `Ok(Some(trimmed))` when present once with
/// valid ASCII text, or `Err(())` when duplicated or containing non-ASCII/invalid bytes (fail closed).
fn single_ascii_header<'a>(
    headers: &'a HeaderMap,
    header_name: &str,
) -> Result<Option<&'a str>, ()> {
    let mut iter = headers.get_all(header_name).iter();
    let Some(first) = iter.next() else {
        return Ok(None);
    };
    if iter.next().is_some() {
        return Err(());
    }
    let Ok(raw) = first.to_str() else {
        return Err(());
    };
    if !raw.is_ascii() {
        return Err(());
    }
    Ok(Some(raw.trim()))
}

/// Requires browser Fetch Metadata headers proving a direct/same-origin top-level document
/// navigation before passively minting a `roche_session` from `Tailscale-User-Login`.
///
/// This blocks cross-site subresource `GET` requests (e.g. `<img>`, `no-cors` `fetch`) that omit
/// `SameSite=Lax` `roche_logged_out=1` cookies from silently re-authenticating an explicitly
/// logged-out browser.
#[must_use]
pub fn is_navigational_tailscale_auto_login(headers: &HeaderMap) -> bool {
    let Ok(Some(mode)) = single_ascii_header(headers, "sec-fetch-mode") else {
        return false;
    };
    let Ok(Some(dest)) = single_ascii_header(headers, "sec-fetch-dest") else {
        return false;
    };
    let Ok(Some(site)) = single_ascii_header(headers, "sec-fetch-site") else {
        return false;
    };
    mode.eq_ignore_ascii_case("navigate")
        && dest.eq_ignore_ascii_case("document")
        && (site.eq_ignore_ascii_case("none") || site.eq_ignore_ascii_case("same-origin"))
}

/// Defense-in-depth check for browser cookie-authenticated state-changing API requests:
/// if `Sec-Fetch-Site` is present, it must appear once, be valid ASCII, and equal `same-origin`
/// or `none` (rejecting both `cross-site` and `same-site` requests).
#[must_use]
pub fn is_valid_same_origin_fetch_metadata(headers: &HeaderMap) -> bool {
    match single_ascii_header(headers, "sec-fetch-site") {
        Ok(None) => true,
        Ok(Some(site)) => {
            site.eq_ignore_ascii_case("same-origin") || site.eq_ignore_ascii_case("none")
        }
        Err(()) => false,
    }
}

#[must_use]
fn build_named_cookie(name: &str, value: &str, max_age_secs: u64, secure: bool) -> String {
    let secure_attr = if secure { "; Secure" } else { "" };
    format!("{name}={value}; Path=/; HttpOnly; SameSite=Lax; Max-Age={max_age_secs}{secure_attr}")
}

pub fn append_session_set_cookies(
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

pub fn append_clear_session_cookies(resp: &mut Response, req_headers: &HeaderMap, secure: bool) {
    if secure || extract_named_cookie(req_headers, HOST_SESSION_COOKIE_NAME).is_some() {
        append_set_cookie(resp, &build_named_cookie(HOST_SESSION_COOKIE_NAME, "", 0, true));
    }
    append_set_cookie(resp, &build_named_cookie(SESSION_COOKIE_NAME, "", 0, secure));
}

pub fn append_logged_out_cookie(resp: &mut Response, secure: bool) {
    let name = if secure { HOST_LOGGED_OUT_COOKIE_NAME } else { LOGGED_OUT_COOKIE_NAME };
    append_set_cookie(resp, &build_named_cookie(name, "1", WEB_SESSION_MAX_AGE_SECS, secure));
}

pub fn append_clear_logged_out_cookies(resp: &mut Response, req_headers: &HeaderMap, secure: bool) {
    if secure || extract_named_cookie(req_headers, HOST_LOGGED_OUT_COOKIE_NAME).is_some() {
        append_set_cookie(resp, &build_named_cookie(HOST_LOGGED_OUT_COOKIE_NAME, "", 0, true));
    }
    append_set_cookie(resp, &build_named_cookie(LOGGED_OUT_COOKIE_NAME, "", 0, secure));
}

pub fn append_clear_flash_cookies_on_logout(
    resp: &mut Response,
    req_headers: &HeaderMap,
    secure: bool,
) {
    if secure || extract_named_cookie(req_headers, HOST_FLASH_COOKIE_NAME).is_some() {
        append_set_cookie(resp, &build_named_cookie(HOST_FLASH_COOKIE_NAME, "", 0, true));
    }
    append_set_cookie(resp, &build_named_cookie(FLASH_COOKIE_NAME, "", 0, secure));
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
pub fn is_same_origin_post(headers: &HeaderMap, expected_https: bool) -> bool {
    match single_ascii_header(headers, "sec-fetch-site") {
        Ok(Some(site)) => {
            return site.eq_ignore_ascii_case("same-origin") || site.eq_ignore_ascii_case("none");
        }
        Err(()) => return false,
        Ok(None) => {}
    }
    let Ok(Some(host)) = single_ascii_header(headers, header::HOST.as_str()) else {
        return false;
    };
    match single_ascii_header(headers, header::ORIGIN.as_str()) {
        Ok(Some(origin)) => return url_matches_host(origin, host, expected_https),
        Err(()) => return false,
        Ok(None) => {}
    }
    match single_ascii_header(headers, header::REFERER.as_str()) {
        Ok(Some(referer)) => url_matches_host(referer, host, expected_https),
        Ok(None) | Err(()) => false,
    }
}

/// Checks whether `Tailscale-User-Login` can be trusted:
/// strictly requires a loopback TCP peer (`127.0.0.1` / `::1` / `::ffff:127.0.0.1`, i.e. local
/// `tailscale serve` reverse proxy), `tailscale.enabled == true`, a single valid header value,
/// and membership in `tailscale.auto_auth_users`.
#[must_use]
pub fn verified_tailscale_login(
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
