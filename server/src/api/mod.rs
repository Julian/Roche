mod auth;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;
#[cfg(test)]
mod tests;

use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Request, State},
    middleware::{self, Next},
    response::Response,
    routing::{delete, get, patch, post},
};
use axum_extra::{TypedHeader, typed_header::TypedHeaderRejection};
use headers::{Authorization, authorization::Bearer};
use tower_http::{
    compression::{
        CompressionLayer,
        predicate::{DefaultPredicate, NotForContentType, Predicate},
    },
    limit::RequestBodyLimitLayer,
    trace::TraceLayer,
};

use crate::{error::ApiError, scheduler::FeedFetcher, service::RocheService};

pub struct ApiRoutes;

impl ApiRoutes {
    #[cfg(test)]
    pub fn router_with_fetcher<F: FeedFetcher>(db: &crate::db::Db, fetcher: F) -> Router {
        let service = RocheService::new(db.clone(), fetcher, crate::config::Config::default());
        Self::router_with_service(service)
    }

    pub fn router_with_service<F: FeedFetcher>(service: RocheService<F>) -> Router {
        let service = Arc::new(service);

        let large_body_routes = Router::new()
            .route("/api/v1/import/opml", post(feeds::import_opml::<F>))
            .route("/api/v1/sync/peer", post(sync::sync_peer::<F>))
            .layer(DefaultBodyLimit::max(10 * 1024 * 1024))
            .layer(RequestBodyLimitLayer::new(10 * 1024 * 1024));

        let protected = Router::new()
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
            .layer(DefaultBodyLimit::max(64 * 1024))
            .layer(RequestBodyLimitLayer::new(64 * 1024))
            .merge(large_body_routes)
            .route_layer(middleware::from_fn_with_state(
                Arc::clone(&service),
                Self::require_auth::<F>,
            ));

        let public_pair = Router::new()
            .route("/auth/pair", post(auth::auth_pair::<F>))
            .route("/api/v1/auth/pair", post(auth::auth_pair::<F>))
            .layer(DefaultBodyLimit::max(16 * 1024))
            .layer(RequestBodyLimitLayer::new(16 * 1024));

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
            .fallback(Self::handle_not_found)
            .method_not_allowed_fallback(Self::handle_method_not_allowed)
            .layer(TraceLayer::new_for_http())
            .layer(compression)
            .with_state(service)
    }

    fn handle_not_found() -> std::future::Ready<ApiError> {
        std::future::ready(ApiError::not_found("The requested endpoint does not exist"))
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
