use std::sync::Arc;

use axum::{
    Json, Router,
    extract::{Request, State},
    http::{StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, patch, post},
};
use axum_extra::{TypedHeader, typed_header::TypedHeaderRejection};
use headers::{Authorization, authorization::Bearer};
use serde::Deserialize;
use tower_http::{
    compression::CompressionLayer, cors::CorsLayer, limit::RequestBodyLimitLayer, trace::TraceLayer,
};
use url::Url;

use crate::{
    db::{Feed, FeedSource, FeedType, HexColor, ItemFilter, ItemStatus, Tag},
    error::{ApiError, ProblemJson, ProblemPath, ProblemQuery, ProblemString},
    scheduler::FeedFetcher,
    service::{ExportBundle, ItemDetail, RocheService, SyncStatus},
};

pub struct ApiRoutes;

impl ApiRoutes {
    #[cfg(test)]
    pub fn router_with_fetcher<F: FeedFetcher>(db: &crate::db::Db, fetcher: F) -> Router {
        let service = RocheService::new(db.clone(), fetcher, crate::config::Config::default());
        Self::router_with_service(service)
    }

    pub fn router_with_service<F: FeedFetcher>(service: RocheService<F>) -> Router {
        let service = Arc::new(service);

        let protected = Router::new()
            // Feeds
            .route("/api/v1/feeds", get(Self::feeds_list::<F>).post(Self::feeds_create::<F>))
            .route(
                "/api/v1/feeds/{id}",
                get(Self::feeds_get::<F>)
                    .patch(Self::feeds_update::<F>)
                    .delete(Self::feeds_delete::<F>),
            )
            .route("/api/v1/feeds/{id}/refresh", post(Self::feeds_refresh::<F>))
            .route("/api/v1/feeds/{id}/items", get(Self::feeds_items::<F>))
            // Items
            .route("/api/v1/items", get(Self::items_list::<F>))
            .route("/api/v1/items/save", post(Self::items_save::<F>))
            .route(
                "/api/v1/items/{id}",
                get(Self::items_get::<F>)
                    .patch(Self::items_update::<F>)
                    .delete(Self::items_delete::<F>),
            )
            .route("/api/v1/items/{id}/archive", post(Self::items_archive::<F>))
            .route("/api/v1/items/{id}/unarchive", post(Self::items_unarchive::<F>))
            .route("/api/v1/items/{id}/tags", post(Self::items_add_tag::<F>))
            .route("/api/v1/items/{id}/tags/{tag_id}", delete(Self::items_remove_tag::<F>))
            .route("/api/v1/items/{id}/media", get(Self::items_media_list::<F>))
            // Tags
            .route("/api/v1/tags", get(Self::tags_list::<F>).post(Self::tags_create::<F>))
            .route(
                "/api/v1/tags/{id}",
                patch(Self::tags_update::<F>).delete(Self::tags_delete::<F>),
            )
            // Media
            .route("/api/v1/media/{id}", delete(Self::media_delete::<F>))
            // Import / Export
            .route("/api/v1/import/opml", post(Self::import_opml::<F>))
            .route("/api/v1/export/feeds", get(Self::export_feeds_opml::<F>))
            .route("/api/v1/export/data", get(Self::export_data::<F>))
            // Sync
            .route("/api/v1/sync/status", get(Self::sync_status::<F>))
            .route_layer(middleware::from_fn_with_state(
                Arc::clone(&service),
                Self::require_auth::<F>,
            ));

        Router::new()
            .route("/health", get(Self::health))
            .route("/auth/pair", post(Self::auth_pair::<F>))
            .route("/api/v1/auth/pair", post(Self::auth_pair::<F>))
            .merge(protected)
            .fallback(Self::handle_not_found)
            .method_not_allowed_fallback(Self::handle_method_not_allowed)
            .layer(TraceLayer::new_for_http())
            .layer(CompressionLayer::new())
            .layer(CorsLayer::new())
            .layer(RequestBodyLimitLayer::new(10 * 1024 * 1024))
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

    fn health() -> std::future::Ready<Json<serde_json::Value>> {
        std::future::ready(Json(serde_json::json!({
            "status": "ok",
            "version": env!("CARGO_PKG_VERSION"),
        })))
    }

    // --- Auth ---

    async fn auth_pair<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemJson(req): ProblemJson<PairRequest>,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        let token = service.auth().pair_device(&req.device_name, &req.pairing_code).await?;
        Ok(Json(serde_json::json!({
            "token": token,
            "expires_at": null,
        })))
    }

    // --- Feeds ---

    async fn feeds_list<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        let feeds = service.feeds().list().await?;
        Ok(Json(serde_json::json!({ "feeds": feeds })))
    }

    async fn feeds_create<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemJson(req): ProblemJson<CreateFeedRequest>,
    ) -> Result<(StatusCode, Json<Feed>), ApiError> {
        let feed = service
            .feeds()
            .create(req.feed_type, req.url, req.title.as_deref(), req.description.as_deref())
            .await?;
        Ok((StatusCode::CREATED, Json(feed)))
    }

    async fn feeds_get<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<Json<Feed>, ApiError> {
        let feed = service.feeds().get(&id).await?;
        Ok(Json(feed))
    }

    async fn feeds_update<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
        ProblemJson(req): ProblemJson<UpdateFeedRequest>,
    ) -> Result<Json<Feed>, ApiError> {
        let updated = service
            .feeds()
            .update(&id, req.title.as_deref(), req.poll_interval, req.is_active)
            .await?;
        Ok(Json(updated))
    }

    async fn feeds_delete<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<StatusCode, ApiError> {
        service.feeds().delete(&id).await?;
        Ok(StatusCode::NO_CONTENT)
    }

    async fn feeds_refresh<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<Json<Feed>, ApiError> {
        let refreshed = service.feeds().refresh(&id).await?;
        Ok(Json(refreshed))
    }

    async fn feeds_items<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
        ProblemQuery(filter): ProblemQuery<ItemFilter>,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        // Verify feed exists first
        let _ = service.feeds().get(&id).await?;
        let items = service.items().query().with_filter(filter).feed_id(id).list().await?;
        Ok(Json(serde_json::json!({ "items": items })))
    }

    // --- Items ---

    async fn items_list<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemQuery(filter): ProblemQuery<ItemFilter>,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        let items = service.items().query().with_filter(filter).list().await?;
        Ok(Json(serde_json::json!({ "items": items })))
    }

    async fn items_get<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<Json<ItemDetail>, ApiError> {
        let detail = service.items().get(&id).await?;
        Ok(Json(detail))
    }

    async fn items_save<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemJson(req): ProblemJson<SaveItemRequest>,
    ) -> Result<(StatusCode, Json<ItemDetail>), ApiError> {
        let detail = service.items().save(&req.url, req.title.as_deref(), &req.tags).await?;
        Ok((StatusCode::CREATED, Json(detail)))
    }

    async fn items_update<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
        ProblemJson(req): ProblemJson<UpdateItemRequest>,
    ) -> Result<Json<ItemDetail>, ApiError> {
        let detail = service.items().update(&id, req.status, req.is_favorite).await?;
        Ok(Json(detail))
    }

    async fn items_delete<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<StatusCode, ApiError> {
        service.items().delete(&id).await?;
        Ok(StatusCode::NO_CONTENT)
    }

    async fn items_archive<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<Json<ItemDetail>, ApiError> {
        let detail = service.items().archive(&id).await?;
        Ok(Json(detail))
    }

    async fn items_unarchive<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<Json<ItemDetail>, ApiError> {
        let detail = service.items().unarchive(&id).await?;
        Ok(Json(detail))
    }

    // --- Tags ---

    async fn tags_list<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        let tags = service.tags().list().await?;
        Ok(Json(serde_json::json!({ "tags": tags })))
    }

    async fn tags_create<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemJson(req): ProblemJson<CreateTagRequest>,
    ) -> Result<(StatusCode, Json<Tag>), ApiError> {
        let tag = service.tags().create(&req.name, req.color.as_ref()).await?;
        Ok((StatusCode::CREATED, Json(tag)))
    }

    async fn tags_update<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
        ProblemJson(req): ProblemJson<UpdateTagRequest>,
    ) -> Result<Json<Tag>, ApiError> {
        let updated = service.tags().update(&id, req.name.as_deref(), req.color.as_ref()).await?;
        Ok(Json(updated))
    }

    async fn tags_delete<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<StatusCode, ApiError> {
        service.tags().delete(&id).await?;
        Ok(StatusCode::NO_CONTENT)
    }

    async fn items_add_tag<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(item_id): ProblemPath<String>,
        ProblemJson(req): ProblemJson<AddItemTagRequest>,
    ) -> Result<Json<ItemDetail>, ApiError> {
        let detail =
            service.items().add_tag(&item_id, req.tag_id.as_deref(), req.name.as_deref()).await?;
        Ok(Json(detail))
    }

    async fn items_remove_tag<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath((item_id, tag_id)): ProblemPath<(String, String)>,
    ) -> Result<StatusCode, ApiError> {
        service.items().remove_tag(&item_id, &tag_id).await?;
        Ok(StatusCode::NO_CONTENT)
    }

    // --- Media ---

    async fn items_media_list<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(item_id): ProblemPath<String>,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        let media = service.items().list_media(&item_id).await?;
        Ok(Json(serde_json::json!({ "media": media })))
    }

    async fn media_delete<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemPath(id): ProblemPath<String>,
    ) -> Result<StatusCode, ApiError> {
        service.media().delete(&id).await?;
        Ok(StatusCode::NO_CONTENT)
    }

    // --- OPML Import / Export & Data Export ---

    async fn import_opml<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
        ProblemString(body): ProblemString,
    ) -> Result<Json<serde_json::Value>, ApiError> {
        let opml_str = if body.trim_start().starts_with('{') {
            serde_json::from_str::<OpmlImportRequest>(&body)
                .map(|r| r.opml)
                .map_err(|e| ApiError::bad_request(format!("Invalid JSON payload: {e}")))?
        } else {
            body
        };

        let imported = service.feeds().import_opml(opml_str).await?;
        Ok(Json(serde_json::json!({
            "imported_count": imported.len(),
            "feeds": imported,
        })))
    }

    async fn export_feeds_opml<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
    ) -> Result<Response, ApiError> {
        let xml = service.feeds().export_opml().await?;
        Ok(([(header::CONTENT_TYPE, "text/x-opml; charset=utf-8")], xml).into_response())
    }

    async fn export_data<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
    ) -> Result<Json<ExportBundle>, ApiError> {
        let bundle = service.export_data().await?;
        Ok(Json(bundle))
    }

    // --- Sync Status ---

    async fn sync_status<F: FeedFetcher>(
        State(service): State<Arc<RocheService<F>>>,
    ) -> Result<Json<SyncStatus>, ApiError> {
        let status = service.sync_status().await?;
        Ok(Json(status))
    }
}

#[derive(Debug, Deserialize)]
struct PairRequest {
    device_name: String,
    pairing_code: String,
}

#[derive(Debug, Deserialize)]
struct CreateFeedRequest {
    url: FeedSource,
    #[serde(default)]
    feed_type: FeedType,
    title: Option<String>,
    description: Option<String>,
}

#[derive(Debug, Deserialize)]
struct UpdateFeedRequest {
    title: Option<String>,
    poll_interval: Option<i64>,
    is_active: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct SaveItemRequest {
    url: Url,
    title: Option<String>,
    #[serde(default)]
    tags: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct UpdateItemRequest {
    status: Option<ItemStatus>,
    is_favorite: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct CreateTagRequest {
    name: String,
    color: Option<HexColor>,
}

#[derive(Debug, Deserialize)]
struct UpdateTagRequest {
    name: Option<String>,
    color: Option<HexColor>,
}

#[derive(Debug, Deserialize)]
struct AddItemTagRequest {
    tag_id: Option<String>,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct OpmlImportRequest {
    opml: String,
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use axum::{
        body::{Body, to_bytes},
        http::Request,
    };
    use tower::ServiceExt;

    use crate::{
        auth::{pairing::PairingManager, tokens::TokenManager},
        db::Db,
    };

    #[derive(Clone)]
    struct FakeFeedFetcher;

    impl FeedFetcher for FakeFeedFetcher {
        fn fetch_feed(
            &self,
            _url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            std::future::ready(Ok(r#"<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Fake Feed</title>
                <link>https://example.com</link>
                <description>Fake</description>
                <item>
                  <title>Refreshed Article</title>
                  <link>https://example.com/refreshed</link>
                  <description>Summary</description>
                </item>
              </channel>
            </rss>"#
                .to_string()))
        }
    }

    async fn setup_app() -> (Router, String) {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
        (ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher), format!("Bearer {token}"))
    }

    #[tokio::test]
    async fn test_auth_required_and_rfc9457_problem_details_on_all_errors() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
        let app = ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher);

        // Unauthenticated request returns 401 with application/problem+json and WWW-Authenticate: Bearer
        let req = Request::builder().uri("/api/v1/feeds").body(Body::empty()).unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(res.headers().get(header::CONTENT_TYPE).unwrap(), "application/problem+json");
        assert_eq!(res.headers().get(header::WWW_AUTHENTICATE).unwrap(), "Bearer");
        let problem: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(problem["type"], "urn:roche:problem:unauthorized");
        assert_eq!(problem["title"], "Unauthorized");
        assert_eq!(problem["status"], 401);

        // Unknown route returns 404 with application/problem+json
        let not_found_request =
            Request::builder().uri("/does-not-exist").body(Body::empty()).unwrap();
        let not_found_response = app.clone().oneshot(not_found_request).await.unwrap();
        assert_eq!(not_found_response.status(), StatusCode::NOT_FOUND);
        assert_eq!(
            not_found_response.headers().get(header::CONTENT_TYPE).unwrap(),
            "application/problem+json"
        );

        // Wrong HTTP method returns 405 with application/problem+json
        let method_request =
            Request::builder().method("DELETE").uri("/health").body(Body::empty()).unwrap();
        let method_response = app.clone().oneshot(method_request).await.unwrap();
        assert_eq!(method_response.status(), StatusCode::METHOD_NOT_ALLOWED);
        assert_eq!(
            method_response.headers().get(header::CONTENT_TYPE).unwrap(),
            "application/problem+json"
        );

        // Missing Content-Type on JSON endpoint returns 415 with application/problem+json
        let bad_ct_request = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .body(Body::from(r#"{"device_name":"x","pairing_code":"y"}"#))
            .unwrap();
        let bad_ct_response = app.clone().oneshot(bad_ct_request).await.unwrap();
        assert_eq!(bad_ct_response.status(), StatusCode::UNSUPPORTED_MEDIA_TYPE);
        assert_eq!(
            bad_ct_response.headers().get(header::CONTENT_TYPE).unwrap(),
            "application/problem+json"
        );

        // Pair with valid pairing code
        let req = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .header("content-type", "application/json")
            .body(Body::from(format!(r#"{{"device_name":"my-mac","pairing_code":"{code}"}}"#)))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let pair_body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        let token = pair_body["token"].as_str().unwrap();

        // Reusing the single-use pairing code fails with 401
        let req = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .header("content-type", "application/json")
            .body(Body::from(format!(r#"{{"device_name":"my-mac-2","pairing_code":"{code}"}}"#)))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);

        // Valid Bearer token is accepted
        let req = Request::builder()
            .uri("/api/v1/feeds")
            .header(header::AUTHORIZATION, format!("Bearer {token}"))
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn test_feeds_crud_refresh_and_conflict() {
        let (app, auth) = setup_app().await;

        // Create feed
        let req = Request::builder()
            .method("POST")
            .uri("/api/v1/feeds")
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(
                r#"{"url":"https://example.com/feed.xml","feed_type":"rss","title":"Example"}"#,
            ))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        let feed_id = body["id"].as_str().unwrap().to_string();
        assert_eq!(body["title"], "Example");

        // Creating the same feed URL again returns 409 Conflict Problem Details
        let duplicate_request = Request::builder()
            .method("POST")
            .uri("/api/v1/feeds")
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(r#"{"url":"https://example.com/feed.xml","feed_type":"rss"}"#))
            .unwrap();
        let duplicate_response = app.clone().oneshot(duplicate_request).await.unwrap();
        assert_eq!(duplicate_response.status(), StatusCode::CONFLICT);
        assert_eq!(
            duplicate_response.headers().get(header::CONTENT_TYPE).unwrap(),
            "application/problem+json"
        );

        // SSRF URL is rejected with 400 Bad Request
        let ssrf_request = Request::builder()
            .method("POST")
            .uri("/api/v1/feeds")
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(r#"{"url":"http://127.0.0.1:8080/internal","feed_type":"rss"}"#))
            .unwrap();
        let ssrf_response = app.clone().oneshot(ssrf_request).await.unwrap();
        assert_eq!(ssrf_response.status(), StatusCode::BAD_REQUEST);

        // Trigger refresh via injected FakeFeedFetcher
        let req = Request::builder()
            .method("POST")
            .uri(format!("/api/v1/feeds/{feed_id}/refresh"))
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);

        // Verify refreshed item appears under feed items
        let req = Request::builder()
            .uri(format!("/api/v1/feeds/{feed_id}/items"))
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(body["items"].as_array().unwrap().len(), 1);
        assert_eq!(body["items"][0]["title"], "Refreshed Article");

        // Invalid poll_interval is rejected with 400 Bad Request
        let bad_patch = Request::builder()
            .method("PATCH")
            .uri(format!("/api/v1/feeds/{feed_id}"))
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(r#"{"poll_interval":-10}"#))
            .unwrap();
        let bad_res = app.clone().oneshot(bad_patch).await.unwrap();
        assert_eq!(bad_res.status(), StatusCode::BAD_REQUEST);

        // Update feed
        let req = Request::builder()
            .method("PATCH")
            .uri(format!("/api/v1/feeds/{feed_id}"))
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(r#"{"title":"Updated Title","poll_interval":60}"#))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(body["title"], "Updated Title");
        assert_eq!(body["poll_interval"], 60);

        // Delete feed
        let req = Request::builder()
            .method("DELETE")
            .uri(format!("/api/v1/feeds/{feed_id}"))
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::NO_CONTENT);
    }

    #[tokio::test]
    async fn test_items_save_archive_tags_and_export() {
        let (app, auth) = setup_app().await;

        // Save manual item with tags (including duplicate tag name to verify deduplication)
        let req = Request::builder()
            .method("POST")
            .uri("/api/v1/items/save")
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(
                r#"{"url":"https://example.com/post","title":"My Post","tags":["rust","crdt","rust"]}"#,
            ))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::CREATED);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        let item_id = body["id"].as_str().unwrap().to_string();
        assert_eq!(body["tags"].as_array().unwrap().len(), 2);

        // Reject invalid status on PATCH /api/v1/items/{id}
        let bad_status = Request::builder()
            .method("PATCH")
            .uri(format!("/api/v1/items/{item_id}"))
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(r#"{"status":"bogus"}"#))
            .unwrap();
        let bad_res = app.clone().oneshot(bad_status).await.unwrap();
        assert_eq!(bad_res.status(), StatusCode::BAD_REQUEST);

        // Reject invalid query filter status on GET /api/v1/items?status=bogus
        let bad_query = Request::builder()
            .uri("/api/v1/items?status=bogus")
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let bad_query_res = app.clone().oneshot(bad_query).await.unwrap();
        assert_eq!(bad_query_res.status(), StatusCode::BAD_REQUEST);

        // Archive item
        let req = Request::builder()
            .method("POST")
            .uri(format!("/api/v1/items/{item_id}/archive"))
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(body["status"], "archived");
        assert!(body["read_at"].is_string());

        // Filter items by status=archived
        let req = Request::builder()
            .uri("/api/v1/items?status=archived")
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(body["items"].as_array().unwrap().len(), 1);

        // Export data includes item_tags and non-empty activity log
        let export_request = Request::builder()
            .uri("/api/v1/export/data")
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let export_response = app.oneshot(export_request).await.unwrap();
        assert_eq!(export_response.status(), StatusCode::OK);
        let exp_body: serde_json::Value = serde_json::from_slice(
            &to_bytes(export_response.into_body(), usize::MAX).await.unwrap(),
        )
        .unwrap();
        assert_eq!(exp_body["item_tags"].as_array().unwrap().len(), 2);
        assert!(exp_body["activity"].as_array().unwrap().len() >= 2);
    }

    #[tokio::test]
    async fn test_opml_import_and_export_with_entities() {
        let (app, auth) = setup_app().await;
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <outline type="rss" subtitle="Ignored" text="R &amp; D > News" title="R &amp; D > News" xmlUrl="https://blog.rust-lang.org/feed.xml?a=1&amp;b=2" />
            <outline type="rss" text="arXiv CS" xmlUrl="https://rss.arxiv.org/rss/cs.AI" />
          </body>
        </opml>"#;

        let req = Request::builder()
            .method("POST")
            .uri("/api/v1/import/opml")
            .header(header::AUTHORIZATION, &auth)
            .body(Body::from(opml))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(body["imported_count"], 2);
        assert_eq!(body["feeds"][0]["title"], "R & D > News");
        assert_eq!(body["feeds"][0]["url"], "https://blog.rust-lang.org/feed.xml?a=1&b=2");

        let req = Request::builder()
            .uri("/api/v1/export/feeds")
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let res = app.oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let exported =
            String::from_utf8(to_bytes(res.into_body(), usize::MAX).await.unwrap().to_vec())
                .unwrap();
        assert!(exported.contains("https://blog.rust-lang.org/feed.xml?a=1&amp;b=2"));
        assert!(exported.contains("R &amp; D &gt; News"));
        assert!(exported.contains("https://rss.arxiv.org/rss/cs.AI"));
    }
}
