mod auth;
mod html;
mod links;
#[cfg(test)]
mod tests;
mod views_catalog;
mod views_home;
mod views_rank;
mod views_reader;
mod views_search;

use std::{
    net::SocketAddr,
    sync::{Arc, OnceLock},
};

use axum::{
    Router,
    extract::{ConnectInfo, DefaultBodyLimit, Query, Request, rejection::QueryRejection},
    http::{HeaderMap, HeaderName, HeaderValue, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::Deserialize;
use tower_http::limit::RequestBodyLimitLayer;

use crate::{
    service::RocheService,
    util::{hash::sha256_hex, http_security::is_forwarded_https},
};

const ROCHE_CSS: &str = include_str!("roche.css");
const ROCHE_JS: &str = include_str!("roche.js");
const MAX_WEB_FORM_BODY_BYTES: usize = 128 * 1024;
const MAX_WEB_OPML_BODY_BYTES: usize = 4 * 1024 * 1024;
const OPML_UPLOAD_PATH: &str = "/web/feeds/opml";

struct StaticAssetMeta {
    version: String,
    etag: HeaderValue,
    href: String,
}

fn build_asset_meta(path: &str, body: &str) -> StaticAssetMeta {
    let digest = sha256_hex(body);
    let version = digest[..12].to_owned();
    // Weak ETag per RFC 9110 §8.8.3.3 because `CompressionLayer` may serve gzip/br/zstd or
    // identity representations for the same underlying asset.
    let etag_raw = format!("W/\"{version}\"");
    let etag = HeaderValue::from_str(&etag_raw)
        .unwrap_or_else(|_| HeaderValue::from_static("W/\"roche\""));
    let href = format!("{path}?v={version}");
    StaticAssetMeta { version, etag, href }
}

fn css_asset_meta() -> &'static StaticAssetMeta {
    static META: OnceLock<StaticAssetMeta> = OnceLock::new();
    META.get_or_init(|| build_asset_meta("/static/roche.css", ROCHE_CSS))
}

fn js_asset_meta() -> &'static StaticAssetMeta {
    static META: OnceLock<StaticAssetMeta> = OnceLock::new();
    META.get_or_init(|| build_asset_meta("/static/roche.js", ROCHE_JS))
}

fn css_href() -> &'static str {
    &css_asset_meta().href
}

fn js_href() -> &'static str {
    &js_asset_meta().href
}

#[derive(Debug, Default, Deserialize)]
struct StaticAssetQuery {
    #[serde(default)]
    v: Option<String>,
}

pub fn router(service: Arc<RocheService>) -> Router<Arc<RocheService>> {
    let static_routes = Router::new()
        .route("/static/roche.css", get(serve_css))
        .route("/static/roche.js", get(serve_js));

    let public_login = Router::new()
        .route("/login", get(auth::login_get).post(auth::login_post))
        .route("/login/tailscale", post(auth::login_tailscale_post))
        .layer(DefaultBodyLimit::max(16 * 1024))
        .layer(RequestBodyLimitLayer::new(16 * 1024));

    let opml_upload = Router::new()
        .route(OPML_UPLOAD_PATH, post(views_reader::post_feed_opml))
        .route_layer(middleware::from_fn_with_state(
            Arc::clone(&service),
            auth::require_web_auth_and_csrf,
        ))
        .layer(DefaultBodyLimit::max(MAX_WEB_OPML_BODY_BYTES))
        .layer(RequestBodyLimitLayer::new(MAX_WEB_OPML_BODY_BYTES));

    let protected = Router::new()
        .route("/logout", post(auth::logout_post))
        // Home, Global Search & Active Collection Draws
        .route("/", get(views_home::home_get))
        .route("/search", get(views_search::search_get))
        .route("/web/lists/{slug}/generator/toggle", post(views_home::post_generator_toggle))
        .route("/web/lists/{slug}/generator/finish", post(views_home::post_generator_finish))
        .route("/web/lists/{slug}/generator/abandon", post(views_home::post_generator_abandon))
        // Reader, Inbox, Starred, Feeds & Media Streaming
        .route("/inbox", get(views_reader::inbox_get))
        .route("/starred", get(views_reader::starred_get))
        .route("/feeds", get(views_reader::feeds_get))
        .route("/reader/{id}", get(views_reader::reader_get))
        .route("/web/items/{id}/audio", get(views_reader::item_audio_get))
        .route("/web/media/{id}", get(views_reader::web_media_get))
        .route("/web/feeds/export.opml", get(views_reader::export_opml_get))
        .route("/web/items/save", post(views_reader::post_item_save))
        .route("/web/items/archive-batch", post(views_reader::post_item_archive_batch))
        .route("/web/items/dismiss-batch", post(views_reader::post_item_archive_batch))
        .route("/web/items/{id}/star", post(views_reader::post_item_star))
        .route("/web/items/{id}/archive", post(views_reader::post_item_archive))
        .route("/web/items/{id}/dismiss", post(views_reader::post_item_archive))
        .route("/web/items/{id}/unarchive", post(views_reader::post_item_unarchive))
        .route("/web/items/{id}/mark-new", post(views_reader::post_item_unarchive))
        .route("/web/items/{id}/progress", post(views_reader::post_item_progress))
        .route("/web/items/{id}/finish", post(views_reader::post_item_finish))
        .route("/web/items/{id}/unfinish", post(views_reader::post_item_unfinish))
        .route("/web/items/{id}/tags", post(views_reader::post_item_tag_add))
        .route("/web/items/{id}/tags/{tag_id}/remove", post(views_reader::post_item_tag_remove))
        .route("/web/feeds/create", post(views_reader::post_feed_create))
        .route("/web/feeds/refresh-all", post(views_reader::post_feed_refresh_all))
        .route("/web/feeds/{id}/refresh", post(views_reader::post_feed_refresh))
        .route("/web/feeds/{id}/edit", post(views_reader::post_feed_edit))
        .route("/web/feeds/{id}/toggle-active", post(views_reader::post_feed_toggle_active))
        .route("/web/feeds/{id}/delete", post(views_reader::post_feed_delete))
        .route("/web/refresh-status", get(views_reader::refresh_status_get))
        // Catalog Lists, Entries, Diary, Want Queues & Ranking
        .route("/lists", get(views_catalog::lists_get))
        .route("/web/lists/create", post(views_catalog::post_list_create))
        .route("/lists/{slug}", get(views_catalog::list_detail_get))
        .route("/web/lists/{slug}/add", post(views_catalog::post_list_add_entry))
        .route("/web/lists/{slug}/remove/{entry_id}", post(views_catalog::post_list_remove_entry))
        .route("/entries/{id}", get(views_catalog::entry_detail_get))
        .route("/web/entries/{id}/log", post(views_catalog::post_entry_log))
        .route("/web/entries/{id}/list-add", post(views_catalog::post_entry_list_add))
        .route("/web/entries/{id}/queue-toggle", post(views_catalog::post_entry_queue_toggle))
        .route("/web/entries/{id}/enrich", post(views_catalog::post_entry_enrich))
        .route("/web/entries/{id}/add-album", post(views_catalog::post_artist_add_album))
        .route("/web/entries/{id}/merge", post(views_catalog::post_entry_merge))
        .route("/diary", get(views_catalog::diary_get))
        .route("/web/diary/{id}/edit", post(views_catalog::post_diary_edit))
        .route("/web/diary/{id}/delete", post(views_catalog::post_diary_delete))
        .route("/queue", get(views_catalog::queue_get))
        .route("/web/log", post(views_catalog::post_quick_log))
        .route("/web/want/{queue}/complete/{entry_id}", post(views_catalog::post_want_complete))
        .route("/web/want/{queue}/remove/{entry_id}", post(views_catalog::post_want_remove))
        .route("/rank", get(views_rank::rank_picker_get))
        .route(
            "/rank/{slug}",
            get(views_rank::rank_session_get).post(views_rank::rank_session_post),
        )
        .route("/web/comparisons/{id}/undo", post(views_rank::post_comparison_undo))
        .route_layer(middleware::from_fn_with_state(service, auth::require_web_auth_and_csrf))
        .layer(DefaultBodyLimit::max(MAX_WEB_FORM_BODY_BYTES))
        .layer(RequestBodyLimitLayer::new(MAX_WEB_FORM_BODY_BYTES));

    Router::new()
        .merge(static_routes)
        .merge(public_login)
        .merge(protected)
        .merge(opml_upload)
        .route_layer(middleware::from_fn(attach_web_security_headers))
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

fn serve_static_asset(
    headers: &HeaderMap,
    query: Option<&StaticAssetQuery>,
    meta: &'static StaticAssetMeta,
    content_type: &'static str,
    body: &'static str,
) -> Response {
    let cache_control = if query.and_then(|q| q.v.as_deref()) == Some(meta.version.as_str()) {
        HeaderValue::from_static("public, max-age=31536000, immutable")
    } else {
        HeaderValue::from_static("public, max-age=3600")
    };
    let vary = HeaderValue::from_static("Accept-Encoding");
    if if_none_match_matches(headers, &meta.etag) {
        return (
            StatusCode::NOT_MODIFIED,
            [
                (header::ETAG, meta.etag.clone()),
                (header::CACHE_CONTROL, cache_control),
                (header::VARY, vary),
            ],
        )
            .into_response();
    }
    (
        [
            (header::CONTENT_TYPE, HeaderValue::from_static(content_type)),
            (header::ETAG, meta.etag.clone()),
            (header::CACHE_CONTROL, cache_control),
            (header::VARY, vary),
        ],
        body,
    )
        .into_response()
}

async fn serve_css(
    headers: HeaderMap,
    query: Result<Query<StaticAssetQuery>, QueryRejection>,
) -> Response {
    let q = query.ok().map(|Query(q)| q);
    serve_static_asset(&headers, q.as_ref(), css_asset_meta(), "text/css; charset=utf-8", ROCHE_CSS)
}

async fn serve_js(
    headers: HeaderMap,
    query: Result<Query<StaticAssetQuery>, QueryRejection>,
) -> Response {
    let q = query.ok().map(|Query(q)| q);
    serve_static_asset(
        &headers,
        q.as_ref(),
        js_asset_meta(),
        "application/javascript; charset=utf-8",
        ROCHE_JS,
    )
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

/// Renders a browser-friendly HTML error page with full web security headers.
#[must_use]
pub fn render_status_html(
    status: StatusCode,
    heading: &str,
    detail: &str,
    headers: &axum::http::HeaderMap,
) -> Response {
    let theme = auth::extract_theme_cookie(headers);
    let mut resp = html::render_error_page(status, heading, detail, None, theme);
    apply_web_security_headers(&mut resp, false);
    resp
}

/// Renders a browser-friendly HTML 404 page with full web security headers.
#[must_use]
pub fn render_not_found_html(headers: &axum::http::HeaderMap) -> Response {
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
    let theme = auth::extract_theme_cookie(req.headers());
    let mut resp = next.run(req).await;
    if resp.status() == StatusCode::PAYLOAD_TOO_LARGE {
        let is_html = resp
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_some_and(|ct| ct.to_ascii_lowercase().starts_with("text/html"));
        if !is_html {
            resp = html::render_error_page(
                StatusCode::PAYLOAD_TOO_LARGE,
                "Payload Too Large",
                "The submitted request exceeded the maximum allowed size.",
                None,
                theme,
            );
        }
    }
    if let Some(pending_flash) = resp.extensions_mut().remove::<html::PendingFlash>() {
        auth::append_flash_set_cookie(&mut resp, &pending_flash, forwarded_https);
    }
    apply_web_security_headers(&mut resp, forwarded_https);
    resp
}
