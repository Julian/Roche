mod auth;
mod catalog;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;
#[cfg(test)]
mod tests;

pub use media::serve_media_stream;

use std::sync::Arc;

use axum::{
    Extension, Json, Router,
    extract::{DefaultBodyLimit, Request, State},
    http::{HeaderMap, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, patch, post},
};
use axum_extra::{TypedHeader, typed_header::TypedHeaderRejection};
use headers::{Authorization, authorization::Bearer};
use tower_http::{
    compression::{
        CompressionLayer,
        predicate::{DefaultPredicate, NotForContentType, Predicate},
    },
    trace::TraceLayer,
};

use crate::{
    error::{ApiError, RouteBodyLimit},
    scheduler::FeedFetcher,
    service::RocheService,
};

const API_PAIR_BODY_LIMIT: usize = 16 * 1024;
const API_STANDARD_BODY_LIMIT: usize = 64 * 1024;
const API_OPML_BODY_LIMIT: usize = 16 * 1024 * 1024;
const API_LARGE_BODY_LIMIT: usize = 64 * 1024 * 1024;

pub struct ApiRoutes;

impl ApiRoutes {
    fn with_body_limit<S: Clone + Send + Sync + 'static>(
        router: Router<S>,
        max_bytes: usize,
    ) -> Router<S> {
        router
            .layer(DefaultBodyLimit::max(max_bytes))
            .route_layer(Extension(RouteBodyLimit(u64::try_from(max_bytes).unwrap_or(u64::MAX))))
    }

    #[cfg(test)]
    pub fn router_with_fetcher<F: FeedFetcher>(db: &crate::db::Db, fetcher: F) -> Router {
        let service = RocheService::new(db.clone(), fetcher, crate::config::Config::default());
        Self::router_with_service(service)
    }

    pub fn router_with_service<F: FeedFetcher>(service: RocheService<F>) -> Router {
        let service = Arc::new(service);

        let opml_routes = Self::with_body_limit(
            Router::new().route("/api/v1/import/opml", post(feeds::import_opml::<F>)),
            API_OPML_BODY_LIMIT,
        );

        let large_body_routes = Self::with_body_limit(
            Router::new()
                .route("/api/v1/sync/peer", post(sync::sync_peer::<F>))
                .route("/api/v1/catalog/batch", post(catalog::catalog_batch_apply::<F>)),
            API_LARGE_BODY_LIMIT,
        );

        let protected = Self::with_body_limit(
            Router::new()
                // Auth Devices
                .route("/api/v1/auth/devices", get(auth::devices_list::<F>))
                .route("/api/v1/auth/devices/{id}", delete(auth::devices_revoke::<F>))
                // Feeds
                .route("/api/v1/feeds", get(feeds::feeds_list::<F>).post(feeds::feeds_create::<F>))
                .route(
                    "/api/v1/feeds/{id}",
                    get(feeds::feeds_get::<F>)
                        .patch(feeds::feeds_update::<F>)
                        .delete(feeds::feeds_delete::<F>),
                )
                .route("/api/v1/feeds/{id}/archive", post(feeds::feeds_archive::<F>))
                .route("/api/v1/feeds/{id}/unarchive", post(feeds::feeds_unarchive::<F>))
                .route("/api/v1/feeds/{id}/refresh", post(feeds::feeds_refresh::<F>))
                .route("/api/v1/feeds/{id}/items", get(feeds::feeds_items::<F>))
                // Items
                .route("/api/v1/items", get(items::items_list::<F>))
                .route("/api/v1/items/save", post(items::items_save::<F>))
                .route("/api/v1/items/archive-batch", post(items::items_archive_batch::<F>))
                .route(
                    "/api/v1/items/{id}",
                    get(items::items_get::<F>)
                        .patch(items::items_update::<F>)
                        .delete(items::items_delete::<F>),
                )
                .route("/api/v1/items/{id}/archive", post(items::items_archive::<F>))
                .route("/api/v1/items/{id}/unarchive", post(items::items_unarchive::<F>))
                .route("/api/v1/items/{id}/tags", post(items::items_add_tag::<F>))
                .route("/api/v1/items/{id}/tags/{tag_id}", delete(items::items_remove_tag::<F>))
                .route("/api/v1/items/{id}/media", get(items::items_media_list::<F>))
                // Tags
                .route("/api/v1/tags", get(tags::tags_list::<F>).post(tags::tags_create::<F>))
                .route(
                    "/api/v1/tags/{id}",
                    patch(tags::tags_update::<F>).delete(tags::tags_delete::<F>),
                )
                // Media
                .route(
                    "/api/v1/media/{id}",
                    get(media::media_get::<F>).delete(media::media_delete::<F>),
                )
                // Export
                .route("/api/v1/export/feeds", get(feeds::export_feeds_opml::<F>))
                .route("/api/v1/export/data", get(feeds::export_data::<F>))
                // Sync
                .route("/api/v1/sync/status", get(sync::sync_status::<F>))
                .route("/api/v1/sync/changes", get(sync::sync_changes::<F>))
                .merge(Self::catalog_routes::<F>()),
            API_STANDARD_BODY_LIMIT,
        )
        .merge(opml_routes)
        .merge(large_body_routes)
        .route_layer(middleware::from_fn_with_state(Arc::clone(&service), Self::require_auth::<F>));

        let public_pair = Self::with_body_limit(
            Router::new()
                .route("/auth/pair", post(auth::auth_pair::<F>))
                .route("/api/v1/auth/pair", post(auth::auth_pair::<F>)),
            API_PAIR_BODY_LIMIT,
        );

        let compression = CompressionLayer::new().compress_when(
            DefaultPredicate::new()
                .and(NotForContentType::new("video/"))
                .and(NotForContentType::new("audio/"))
                .and(NotForContentType::new("application/pdf")),
        );

        Router::new()
            .route("/health", get(Self::health::<F>))
            .merge(public_pair)
            .merge(protected)
            .merge(crate::web::router(Arc::clone(&service)))
            .fallback(Self::handle_not_found)
            .method_not_allowed_fallback(Self::handle_method_not_allowed)
            .layer(TraceLayer::new_for_http())
            .layer(compression)
            .with_state(service)
    }

    fn catalog_routes<F: FeedFetcher>() -> Router<Arc<RocheService<F>>> {
        Router::new()
            .route(
                "/api/v1/catalog/lists",
                get(catalog::catalog_lists::<F>).post(catalog::catalog_lists_create::<F>),
            )
            .route("/api/v1/catalog/lists/{slug_or_id}", get(catalog::catalog_list_get::<F>))
            .route(
                "/api/v1/catalog/lists/{slug_or_id}/entries",
                post(catalog::catalog_list_add_entry::<F>),
            )
            .route(
                "/api/v1/catalog/lists/{slug_or_id}/entries/{entry_id}",
                delete(catalog::catalog_list_remove_entry::<F>),
            )
            .route(
                "/api/v1/catalog/entries",
                get(catalog::catalog_entries_search::<F>)
                    .post(catalog::catalog_entries_upsert::<F>),
            )
            .route("/api/v1/catalog/entries/{id}", get(catalog::catalog_entry_get::<F>))
            .route("/api/v1/catalog/entries/{id}/merge", post(catalog::catalog_entry_merge::<F>))
            .route("/api/v1/catalog/diary", get(catalog::catalog_diary_list::<F>))
            .route("/api/v1/catalog/log", post(catalog::catalog_log_create::<F>))
            .route("/api/v1/catalog/want", post(catalog::catalog_want_add::<F>))
            .route(
                "/api/v1/catalog/want/{queue}/{entry_id}",
                delete(catalog::catalog_want_remove::<F>),
            )
            .route(
                "/api/v1/catalog/rank/{slug_or_id}/cluster",
                get(catalog::catalog_rank_cluster::<F>),
            )
            .route("/api/v1/catalog/comparisons", post(catalog::catalog_comparisons_create::<F>))
            .route("/api/v1/catalog/generators", get(catalog::catalog_generators_list::<F>))
            .route(
                "/api/v1/catalog/lists/{slug_or_id}/generator",
                get(catalog::catalog_list_generator_get::<F>)
                    .patch(catalog::catalog_list_generator_patch::<F>)
                    .delete(catalog::catalog_list_generator_delete::<F>),
            )
            .route(
                "/api/v1/catalog/lists/{slug_or_id}/generator/finish",
                post(catalog::catalog_list_generator_finish::<F>),
            )
            .route(
                "/api/v1/catalog/lists/{slug_or_id}/generator/abandon",
                post(catalog::catalog_list_generator_abandon::<F>),
            )
    }

    fn accepts_html(headers: &HeaderMap) -> bool {
        headers.get_all(header::ACCEPT).iter().filter_map(|v| v.to_str().ok()).any(|raw| {
            raw.split(',').any(|item| {
                let mut parts = item.split(';');
                let Some(media_type) = parts.next().map(str::trim) else {
                    return false;
                };
                if !media_type.eq_ignore_ascii_case("text/html") {
                    return false;
                }
                !parts.any(|param| {
                    param.trim().split_once('=').is_some_and(|(k, v)| {
                        k.trim().eq_ignore_ascii_case("q")
                            && v.trim().parse::<f32>().is_ok_and(|q| q <= 0.0)
                    })
                })
            })
        })
    }

    fn handle_not_found(req: Request) -> std::future::Ready<Response> {
        let (parts, _body) = req.into_parts();
        let path = parts.uri.path();
        let is_api_path = path == "/api" || path.starts_with("/api/");
        let resp = if !is_api_path && Self::accepts_html(&parts.headers) {
            crate::web::render_not_found_html()
        } else {
            ApiError::not_found("The requested endpoint does not exist").into_response()
        };
        std::future::ready(resp)
    }

    fn handle_method_not_allowed() -> std::future::Ready<ApiError> {
        std::future::ready(ApiError::method_not_allowed(
            "HTTP method is not allowed for this endpoint",
        ))
    }

    async fn require_auth<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        auth: Result<TypedHeader<Authorization<Bearer>>, TypedHeaderRejection>,
        req: Request,
        next: Next,
    ) -> Result<Response, ApiError> {
        let TypedHeader(Authorization(bearer)) =
            auth.map_err(|_| ApiError::unauthorized("Missing or invalid Authorization header"))?;

        service.auth().authenticate(bearer.token()).await?;
        Ok(next.run(req).await)
    }

    fn health<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
    ) -> std::future::Ready<Json<serde_json::Value>> {
        std::future::ready(Json(service.health_payload()))
    }
}
