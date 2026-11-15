mod auth;
mod html;
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
    http::{HeaderValue, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use tower_http::limit::RequestBodyLimitLayer;

use crate::{scheduler::FeedFetcher, service::RocheService};

const ROCHE_CSS: &str = include_str!("roche.css");
const ROCHE_JS: &str = include_str!("roche.js");

pub fn router<F: FeedFetcher>(service: Arc<RocheService<F>>) -> Router<Arc<RocheService<F>>> {
    let static_routes = Router::new()
        .route("/static/roche.css", get(serve_css))
        .route("/static/roche.js", get(serve_js));

    let public_login = Router::new()
        .route("/login", get(auth::login_get::<F>).post(auth::login_post::<F>))
        .layer(DefaultBodyLimit::max(16 * 1024))
        .layer(RequestBodyLimitLayer::new(16 * 1024));

    let opml_upload = Router::new()
        .route("/web/feeds/opml", post(views_reader::post_feed_opml::<F>))
        .layer(DefaultBodyLimit::max(4 * 1024 * 1024))
        .layer(RequestBodyLimitLayer::new(4 * 1024 * 1024));

    let protected = Router::new()
        .route("/logout", post(auth::logout_post::<F>))
        // Home & 1001 Albums Generator
        .route("/", get(views_home::home_get::<F>))
        .route("/web/1001/toggle", post(views_home::post_1001_toggle::<F>))
        .route("/web/1001/finish", post(views_home::post_1001_finish::<F>))
        .route("/web/1001/abandon", post(views_home::post_1001_abandon::<F>))
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
        .route("/web/feeds/{id}/refresh", post(views_reader::post_feed_refresh::<F>))
        .route("/web/feeds/{id}/toggle-active", post(views_reader::post_feed_toggle_active::<F>))
        .route("/web/feeds/{id}/delete", post(views_reader::post_feed_delete::<F>))
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
        .layer(DefaultBodyLimit::max(128 * 1024))
        .layer(RequestBodyLimitLayer::new(128 * 1024))
        .merge(opml_upload)
        .route_layer(middleware::from_fn_with_state(service, auth::require_web_auth_and_csrf::<F>));

    Router::new()
        .merge(static_routes)
        .merge(public_login)
        .merge(protected)
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

async fn attach_web_security_headers(req: Request, next: Next) -> Response {
    let mut resp = next.run(req).await;
    let headers = resp.headers_mut();
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    headers.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("SAMEORIGIN"));
    headers.insert(header::REFERRER_POLICY, HeaderValue::from_static("same-origin"));
    resp
}
