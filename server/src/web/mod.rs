mod artist_enrich;
mod auth;
mod html;
mod links;
#[cfg(test)]
mod tests;
mod views_catalog;
mod views_home;
mod views_rank;
mod views_reader;

use std::sync::Arc;

use axum::{
    Router,
    extract::{DefaultBodyLimit, Request},
    http::{HeaderValue, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use tower_http::limit::RequestBodyLimitLayer;

use crate::{scheduler::FeedFetcher, service::RocheService};

const ROCHE_CSS: &str = include_str!("roche.css");
const ROCHE_JS: &str = include_str!("roche.js");
const MAX_WEB_FORM_BODY_BYTES: usize = 128 * 1024;
const MAX_WEB_OPML_BODY_BYTES: usize = 4 * 1024 * 1024;
const OPML_UPLOAD_PATH: &str = "/web/feeds/opml";

pub fn router<F: FeedFetcher>(service: Arc<RocheService<F>>) -> Router<Arc<RocheService<F>>> {
    let static_routes = Router::new()
        .route("/static/roche.css", get(serve_css))
        .route("/static/roche.js", get(serve_js));

    let public_login = Router::new()
        .route("/login", get(auth::login_get::<F>).post(auth::login_post::<F>))
        .route("/login/tailscale", post(auth::login_tailscale_post::<F>))
        .layer(DefaultBodyLimit::max(16 * 1024))
        .layer(RequestBodyLimitLayer::new(16 * 1024));

    let opml_upload = Router::new()
        .route(OPML_UPLOAD_PATH, post(views_reader::post_feed_opml::<F>))
        .route_layer(middleware::from_fn_with_state(
            Arc::clone(&service),
            auth::require_web_auth_and_csrf::<F>,
        ))
        .layer(DefaultBodyLimit::max(MAX_WEB_OPML_BODY_BYTES))
        .layer(RequestBodyLimitLayer::new(MAX_WEB_OPML_BODY_BYTES));

    let protected = Router::new()
        .route("/logout", post(auth::logout_post::<F>))
        // Home & Active Collection Draws
        .route("/", get(views_home::home_get::<F>))
        .route("/web/lists/{slug}/generator/toggle", post(views_home::post_generator_toggle::<F>))
        .route("/web/lists/{slug}/generator/finish", post(views_home::post_generator_finish::<F>))
        .route("/web/lists/{slug}/generator/abandon", post(views_home::post_generator_abandon::<F>))
        // Reader, Inbox, Starred, Feeds & Media Streaming
        .route("/inbox", get(views_reader::inbox_get::<F>))
        .route("/starred", get(views_reader::starred_get::<F>))
        .route("/feeds", get(views_reader::feeds_get::<F>))
        .route("/reader/{id}", get(views_reader::reader_get::<F>))
        .route("/web/items/{id}/audio", get(views_reader::item_audio_get::<F>))
        .route("/web/media/{id}", get(views_reader::web_media_get::<F>))
        .route("/web/feeds/export.opml", get(views_reader::export_opml_get::<F>))
        .route("/web/items/save", post(views_reader::post_item_save::<F>))
        .route("/web/items/archive-batch", post(views_reader::post_item_archive_batch::<F>))
        .route("/web/items/{id}/star", post(views_reader::post_item_star::<F>))
        .route("/web/items/{id}/archive", post(views_reader::post_item_archive::<F>))
        .route("/web/items/{id}/unarchive", post(views_reader::post_item_unarchive::<F>))
        .route("/web/items/{id}/progress", post(views_reader::post_item_progress::<F>))
        .route("/web/items/{id}/finish", post(views_reader::post_item_finish::<F>))
        .route("/web/items/{id}/tags", post(views_reader::post_item_tag_add::<F>))
        .route(
            "/web/items/{id}/tags/{tag_id}/remove",
            post(views_reader::post_item_tag_remove::<F>),
        )
        .route("/web/feeds/create", post(views_reader::post_feed_create::<F>))
        .route("/web/feeds/refresh-all", post(views_reader::post_feed_refresh_all::<F>))
        .route("/web/feeds/{id}/refresh", post(views_reader::post_feed_refresh::<F>))
        .route("/web/feeds/{id}/toggle-active", post(views_reader::post_feed_toggle_active::<F>))
        .route("/web/feeds/{id}/delete", post(views_reader::post_feed_delete::<F>))
        .route("/web/refresh-status", get(views_reader::refresh_status_get::<F>))
        // Catalog Lists, Entries, Diary, Want Queues & Ranking
        .route("/lists", get(views_catalog::lists_get::<F>))
        .route("/web/lists/create", post(views_catalog::post_list_create::<F>))
        .route("/lists/{slug}", get(views_catalog::list_detail_get::<F>))
        .route("/web/lists/{slug}/add", post(views_catalog::post_list_add_entry::<F>))
        .route(
            "/web/lists/{slug}/remove/{entry_id}",
            post(views_catalog::post_list_remove_entry::<F>),
        )
        .route("/entries/{id}", get(views_catalog::entry_detail_get::<F>))
        .route("/web/entries/{id}/log", post(views_catalog::post_entry_log::<F>))
        .route("/web/entries/{id}/list-add", post(views_catalog::post_entry_list_add::<F>))
        .route("/web/entries/{id}/merge", post(views_catalog::post_entry_merge::<F>))
        .route("/diary", get(views_catalog::diary_get::<F>))
        .route("/queue", get(views_catalog::queue_get::<F>))
        .route("/web/log", post(views_catalog::post_quick_log::<F>))
        .route(
            "/web/want/{queue}/complete/{entry_id}",
            post(views_catalog::post_want_complete::<F>),
        )
        .route("/web/want/{queue}/remove/{entry_id}", post(views_catalog::post_want_remove::<F>))
        .route("/rank", get(views_rank::rank_picker_get::<F>))
        .route(
            "/rank/{slug}",
            get(views_rank::rank_session_get::<F>).post(views_rank::rank_session_post::<F>),
        )
        .route_layer(middleware::from_fn_with_state(service, auth::require_web_auth_and_csrf::<F>))
        .layer(DefaultBodyLimit::max(MAX_WEB_FORM_BODY_BYTES))
        .layer(RequestBodyLimitLayer::new(MAX_WEB_FORM_BODY_BYTES));

    Router::new()
        .merge(static_routes)
        .merge(public_login)
        .merge(protected)
        .merge(opml_upload)
        .route_layer(middleware::from_fn(attach_web_security_headers))
}

async fn serve_css() -> Response {
    (
        [
            (header::CONTENT_TYPE, HeaderValue::from_static("text/css; charset=utf-8")),
            (header::CACHE_CONTROL, HeaderValue::from_static("public, max-age=3600")),
        ],
        ROCHE_CSS,
    )
        .into_response()
}

async fn serve_js() -> Response {
    (
        [
            (
                header::CONTENT_TYPE,
                HeaderValue::from_static("application/javascript; charset=utf-8"),
            ),
            (header::CACHE_CONTROL, HeaderValue::from_static("public, max-age=3600")),
        ],
        ROCHE_JS,
    )
        .into_response()
}

const WEB_CONTENT_SECURITY_POLICY: &str = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https: http: data:; media-src 'self' https: http:; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'sameorigin'";

fn apply_web_security_headers(resp: &mut Response) {
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
    if !headers.contains_key(header::CACHE_CONTROL) {
        headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("private, no-store"));
    }
}

/// Renders a browser-friendly HTML 404 page with full web security headers.
#[must_use]
pub fn render_not_found_html() -> Response {
    let mut resp = html::render_error_page(
        StatusCode::NOT_FOUND,
        "Not Found",
        "The requested page could not be found.",
        None,
    );
    apply_web_security_headers(&mut resp);
    resp
}

async fn attach_web_security_headers(req: Request, next: Next) -> Response {
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
            );
        }
    }
    apply_web_security_headers(&mut resp);
    resp
}
