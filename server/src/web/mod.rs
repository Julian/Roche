pub mod auth;
pub mod links;
#[cfg(test)]
mod tests;

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{Arc, OnceLock},
};

use axum::{
    Router,
    extract::{
        ConnectInfo, Path, Query, Request, State,
        rejection::{ExtensionRejection, QueryRejection},
    },
    http::{HeaderMap, HeaderName, HeaderValue, StatusCode, Uri, header},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::get,
};
use serde::Deserialize;

use self::auth::{
    HOST_SESSION_COOKIE_NAME, append_session_set_cookies, authenticate_request_session,
    extract_named_cookie, extract_theme_cookie, has_logged_out_cookie,
    is_navigational_tailscale_auto_login, redirect_see_other, safe_login_return_to, urlencode,
    verified_tailscale_login,
};
use crate::{
    content::reader_html::Esc,
    service::RocheService,
    util::{hash::sha256_bytes_hex, http_security::is_forwarded_https},
};

mod spa_assets {
    include!(concat!(env!("OUT_DIR"), "/spa_assets.rs"));
}

struct CompiledAsset {
    asset: &'static spa_assets::EmbeddedAsset,
    etag: HeaderValue,
}

fn asset_index() -> &'static HashMap<&'static str, CompiledAsset> {
    static INDEX: OnceLock<HashMap<&'static str, CompiledAsset>> = OnceLock::new();
    INDEX.get_or_init(|| {
        let mut map = HashMap::with_capacity(spa_assets::EMBEDDED_ASSETS.len());
        for asset in spa_assets::EMBEDDED_ASSETS {
            let digest = sha256_bytes_hex(asset.bytes);
            let version = &digest[..12];
            // Weak ETag per RFC 9110 §8.8.3.3 because `CompressionLayer` may serve gzip/br/zstd or
            // identity representations for the same underlying asset.
            let etag_raw = format!("W/\"{version}\"");
            let etag = HeaderValue::from_str(&etag_raw)
                .unwrap_or_else(|_| HeaderValue::from_static("W/\"roche\""));
            map.insert(asset.path, CompiledAsset { asset, etag });
        }
        map
    })
}

#[derive(Debug, Default, Deserialize)]
struct LoginQuery {
    #[serde(default)]
    return_to: Option<String>,
}

pub fn router(service: Arc<RocheService>) -> Router<Arc<RocheService>> {
    Router::new()
        // Embedded Vite static assets
        .route("/assets/{*path}", get(serve_vite_asset))
        .route("/favicon.svg", get(serve_favicon))
        // Public login SPA route (redirects away if already authenticated)
        .route("/login", get(spa_login_get))
        // Canonical redirects for legacy shortcuts
        .route("/starred", get(spa_redirect_starred))
        .route("/rank", get(spa_redirect_rank))
        // Protected SPA routes
        .route("/", get(spa_shell_get))
        .route("/search", get(spa_shell_get))
        .route("/inbox", get(spa_shell_get))
        .route("/feeds", get(spa_shell_get))
        .route("/reader/{id}", get(spa_shell_get))
        .route("/boards", get(spa_shell_get))
        .route("/boards/{id}", get(spa_shell_get))
        .route("/boards/{id}/read/{external_id}", get(spa_shell_get))
        .route("/lists", get(spa_shell_get))
        .route("/lists/{slug}", get(spa_shell_get))
        .route("/entries/{id}", get(spa_shell_get))
        .route("/diary", get(spa_shell_get))
        .route("/queue", get(spa_shell_get))
        .route("/rank/{slug}", get(spa_shell_get))
        .route_layer(middleware::from_fn(attach_web_security_headers))
        .with_state(service)
}

fn strip_weak_etag_prefix(tag: &str) -> &str {
    tag.strip_prefix("W/").unwrap_or(tag)
}

fn if_none_match_matches(headers: &HeaderMap, etag: &HeaderValue) -> bool {
    let Ok(expected) = etag.to_str() else {
        return false;
    };
    let expected_opaque = strip_weak_etag_prefix(expected);
    headers.get_all(header::IF_NONE_MATCH).iter().filter_map(|v| v.to_str().ok()).any(|raw| {
        raw.split(',').any(|candidate| {
            let trimmed = candidate.trim();
            trimmed == "*" || strip_weak_etag_prefix(trimmed) == expected_opaque
        })
    })
}

fn serve_embedded_path(path: &str, headers: &HeaderMap) -> Response {
    let Some(entry) = asset_index().get(path) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let cache_control = if entry.asset.immutable {
        HeaderValue::from_static("public, max-age=31536000, immutable")
    } else {
        HeaderValue::from_static("public, max-age=3600")
    };
    let vary = HeaderValue::from_static("Accept-Encoding");
    if if_none_match_matches(headers, &entry.etag) {
        return (
            StatusCode::NOT_MODIFIED,
            [
                (header::ETAG, entry.etag.clone()),
                (header::CACHE_CONTROL, cache_control),
                (header::VARY, vary),
            ],
        )
            .into_response();
    }
    (
        [
            (header::CONTENT_TYPE, HeaderValue::from_static(entry.asset.content_type)),
            (header::ETAG, entry.etag.clone()),
            (header::CACHE_CONTROL, cache_control),
            (header::VARY, vary),
        ],
        entry.asset.bytes,
    )
        .into_response()
}

async fn serve_vite_asset(Path(path): Path<String>, headers: HeaderMap) -> Response {
    let full = format!("/assets/{path}");
    serve_embedded_path(&full, &headers)
}

async fn serve_favicon(headers: HeaderMap) -> Response {
    serve_embedded_path("/favicon.svg", &headers)
}

fn render_spa_index_html(theme: Option<&'static str>) -> Html<String> {
    let html = theme.map_or_else(
        || spa_assets::INDEX_HTML.to_owned(),
        |t| {
            let replacement = format!("<html lang=\"en\" data-theme=\"{t}\">");
            spa_assets::INDEX_HTML.replacen("<html lang=\"en\">", &replacement, 1)
        },
    );
    Html(html)
}

enum ShellAuthOutcome {
    Authenticated { newly_minted_token: Option<String>, secure: bool, had_host_session: bool },
    RedirectToLogin(Response),
}

async fn check_spa_shell_auth(
    service: &RocheService,
    peer_addr: Option<SocketAddr>,
    headers: &HeaderMap,
    uri: &Uri,
) -> ShellAuthOutcome {
    let secure = is_forwarded_https(peer_addr, headers);
    let had_host_session = extract_named_cookie(headers, HOST_SESSION_COOKIE_NAME).is_some();

    if authenticate_request_session(service, headers, secure).await.is_some() {
        return ShellAuthOutcome::Authenticated {
            newly_minted_token: None,
            secure,
            had_host_session,
        };
    }

    if !has_logged_out_cookie(headers, secure)
        && is_navigational_tailscale_auto_login(headers)
        && let Some(ts_login) = verified_tailscale_login(service, peer_addr, headers)
        && let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await
    {
        return ShellAuthOutcome::Authenticated {
            newly_minted_token: Some(minted),
            secure,
            had_host_session,
        };
    }

    let return_to =
        safe_login_return_to(uri.path_and_query().map(axum::http::uri::PathAndQuery::as_str));
    let dest = if return_to == "/" {
        "/login".to_owned()
    } else {
        format!("/login?return_to={}", urlencode(&return_to))
    };
    ShellAuthOutcome::RedirectToLogin(redirect_see_other(&dest))
}

async fn spa_login_get(
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
    if !has_logged_out_cookie(&headers, secure)
        && is_navigational_tailscale_auto_login(&headers)
        && let Some(ts_login) = verified_tailscale_login(&service, peer_addr, &headers)
        && let Ok(minted) = service.auth().mint_tailscale_web_session(&ts_login).await
    {
        let mut resp = redirect_see_other(&return_to);
        append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
        return resp;
    }

    render_spa_index_html(theme).into_response()
}

async fn spa_shell_get(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    uri: Uri,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let theme = extract_theme_cookie(&headers);
    match check_spa_shell_auth(&service, peer_addr, &headers, &uri).await {
        ShellAuthOutcome::RedirectToLogin(resp) => resp,
        ShellAuthOutcome::Authenticated { newly_minted_token, secure, had_host_session } => {
            let mut resp = render_spa_index_html(theme).into_response();
            if let Some(minted) = newly_minted_token {
                append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
            }
            resp
        }
    }
}

async fn spa_redirect_starred(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    uri: Uri,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    match check_spa_shell_auth(&service, peer_addr, &headers, &uri).await {
        ShellAuthOutcome::RedirectToLogin(resp) => resp,
        ShellAuthOutcome::Authenticated { newly_minted_token, secure, had_host_session } => {
            let mut resp = redirect_see_other("/lists/starred");
            if let Some(minted) = newly_minted_token {
                append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
            }
            resp
        }
    }
}

async fn spa_redirect_rank(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    uri: Uri,
) -> Response {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    match check_spa_shell_auth(&service, peer_addr, &headers, &uri).await {
        ShellAuthOutcome::RedirectToLogin(resp) => resp,
        ShellAuthOutcome::Authenticated { newly_minted_token, secure, had_host_session } => {
            let mut resp = redirect_see_other("/lists");
            if let Some(minted) = newly_minted_token {
                append_session_set_cookies(&mut resp, &minted, secure, had_host_session);
            }
            resp
        }
    }
}

const WEB_CONTENT_SECURITY_POLICY: &str = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https: http: data:; media-src 'self' https: http:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'";
const WEB_PERMISSIONS_POLICY: &str = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";
const PERMISSIONS_POLICY_HEADER: HeaderName = HeaderName::from_static("permissions-policy");

fn apply_web_security_headers(resp: &mut Response, forwarded_https: bool) {
    let headers = resp.headers_mut();
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("SAMEORIGIN"));
    if !headers.contains_key(header::REFERRER_POLICY) {
        headers.insert(header::REFERRER_POLICY, HeaderValue::from_static("same-origin"));
    }
    if !headers.contains_key(header::CONTENT_SECURITY_POLICY) {
        headers.insert(
            header::CONTENT_SECURITY_POLICY,
            HeaderValue::from_static(WEB_CONTENT_SECURITY_POLICY),
        );
    }
    if !headers.contains_key(&PERMISSIONS_POLICY_HEADER) {
        headers.insert(PERMISSIONS_POLICY_HEADER, HeaderValue::from_static(WEB_PERMISSIONS_POLICY));
    }
    if !headers.contains_key(header::CACHE_CONTROL) {
        headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, no-store"));
    }
    if forwarded_https && !headers.contains_key(header::STRICT_TRANSPORT_SECURITY) {
        headers.insert(
            header::STRICT_TRANSPORT_SECURITY,
            HeaderValue::from_static("max-age=31536000"),
        );
    }
}

/// Renders a browser-friendly HTML error page with full web security headers and zero inline CSS/JS.
#[must_use]
pub fn render_status_html(
    status: StatusCode,
    heading: &str,
    detail: &str,
    headers: &HeaderMap,
) -> Response {
    let theme_attr =
        extract_theme_cookie(headers).map_or_else(String::new, |t| format!(" data-theme=\"{t}\""));
    let css_link = spa_assets::MAIN_CSS_HREF
        .map_or_default(|href| format!("<link rel=\"stylesheet\" href=\"{href}\" />"));
    let code = status.as_u16();
    let body = format!(
        "<!doctype html>\
         <html lang=\"en\"{theme_attr}>\
         <head>\
           <meta charset=\"utf-8\" />\
           <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\
           <title>{heading} — Roche</title>\
           <link rel=\"icon\" type=\"image/svg+xml\" href=\"/favicon.svg\" />\
           {css_link}\
         </head>\
         <body>\
           <main class=\"page-shell\">\
             <section class=\"empty-state\">\
               <p class=\"eyebrow\">HTTP {code}</p>\
               <h1>{heading}</h1>\
               <p>{detail}</p>\
               <p><a class=\"btn btn-primary\" href=\"/\">Return to Roche</a></p>\
             </section>\
           </main>\
         </body>\
         </html>",
        heading = Esc(heading),
        detail = Esc(detail),
    );
    let mut resp = (status, Html(body)).into_response();
    apply_web_security_headers(&mut resp, false);
    resp
}

/// Renders a browser-friendly HTML 404 page with full web security headers.
#[must_use]
pub fn render_not_found_html(headers: &HeaderMap) -> Response {
    render_status_html(
        StatusCode::NOT_FOUND,
        "Not Found",
        "The requested page could not be found.",
        headers,
    )
}

async fn attach_web_security_headers(req: Request, next: Next) -> Response {
    let peer_addr = req.extensions().get::<ConnectInfo<SocketAddr>>().map(|ci| ci.0);
    let forwarded_https = is_forwarded_https(peer_addr, req.headers());
    let mut resp = next.run(req).await;
    apply_web_security_headers(&mut resp, forwarded_https);
    resp
}
