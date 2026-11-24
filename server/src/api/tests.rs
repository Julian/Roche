#![allow(clippy::unwrap_used)]

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use axum::{
    Router,
    body::{Body, to_bytes},
    extract::ConnectInfo,
    http::{Request, StatusCode, header},
};
use tower::ServiceExt;
use url::Url;

use super::ApiRoutes;
use crate::{
    auth::{pairing::PairingManager, tokens::TokenManager},
    db::{Db, ReadAtPatch},
    ingest::FeedFetcher,
    service::RocheService,
    util::http_media::{ByteRangeParse, parse_byte_range},
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

    fn fetch_html(
        &self,
        url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> + Send {
        let res = if url.as_str() == "https://example.com/deep-dive" {
            Some(
                r#"<html><head><title>Fetched Page Title</title><meta name="description" content="Extracted meta summary" /></head><body><script>evil()</script><p>Full article body.</p></body></html>"#
                    .to_string(),
            )
        } else {
            None
        };
        std::future::ready(Ok(res))
    }

    fn fetch_pdf(
        &self,
        url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<Option<Vec<u8>>>> + Send {
        let res = if url.as_str() == "https://arxiv.org/pdf/2301.07041.pdf" {
            Some(b"%PDF-1.5\n%fake-arxiv-pdf-payload\n%%EOF".to_vec())
        } else {
            None
        };
        std::future::ready(Ok(res))
    }
}

async fn setup_app() -> (Router, String) {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    (ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher), format!("Bearer {token}"))
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
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
    assert_eq!(problem["type"], "about:blank");
    assert_eq!(problem["title"], "Unauthorized");
    assert_eq!(problem["status"], 401);
    assert!(problem.get("instance").is_none());

    // Unknown route returns 404 with application/problem+json (about:blank)
    let not_found_request = Request::builder().uri("/does-not-exist").body(Body::empty()).unwrap();
    let not_found_response = app.clone().oneshot(not_found_request).await.unwrap();
    assert_eq!(not_found_response.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        not_found_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let nf_problem: serde_json::Value = serde_json::from_slice(
        &to_bytes(not_found_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(nf_problem["type"], "about:blank");
    assert_eq!(nf_problem["status"], 404);

    // Wrong HTTP method returns 405 with application/problem+json (about:blank)
    let method_request =
        Request::builder().method("DELETE").uri("/health").body(Body::empty()).unwrap();
    let method_response = app.clone().oneshot(method_request).await.unwrap();
    assert_eq!(method_response.status(), StatusCode::METHOD_NOT_ALLOWED);
    assert_eq!(
        method_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let method_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(method_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(method_problem["type"], "about:blank");
    assert_eq!(method_problem["status"], 405);

    // Missing Content-Type on JSON endpoint returns 415 with application/problem+json (about:blank)
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
    let bad_ct_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(bad_ct_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(bad_ct_problem["type"], "about:blank");
    assert_eq!(bad_ct_problem["status"], 415);

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

    // Reusing the single-use pairing code fails with 401 + pairing-code-invalid URN
    let req = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(format!(r#"{{"device_name":"my-mac-2","pairing_code":"{code}"}}"#)))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(res.headers().get(header::WWW_AUTHENTICATE).unwrap(), "Bearer");
    let reuse_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(reuse_problem["type"], "urn:roche:problem:pairing-code-invalid");

    // Valid Bearer token is accepted
    let req = Request::builder()
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);

    // List paired devices and revoke device token
    let list_devices_request = Request::builder()
        .uri("/api/v1/auth/devices")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let list_devices_response = app.clone().oneshot(list_devices_request).await.unwrap();
    assert_eq!(list_devices_response.status(), StatusCode::OK);
    let dev_body: serde_json::Value = serde_json::from_slice(
        &to_bytes(list_devices_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    let dev_id = dev_body["devices"][0]["id"].as_str().unwrap().to_string();
    assert_eq!(dev_body["devices"][0]["device_name"], "my-mac");

    let revoke_request = Request::builder()
        .method("DELETE")
        .uri(format!("/api/v1/auth/devices/{dev_id}"))
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let revoke_response = app.clone().oneshot(revoke_request).await.unwrap();
    assert_eq!(revoke_response.status(), StatusCode::NO_CONTENT);

    // Revoked token is now rejected with 401 + token-invalid URN
    let post_revoke_request = Request::builder()
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let post_revoke_response = app.oneshot(post_revoke_request).await.unwrap();
    assert_eq!(post_revoke_response.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(post_revoke_response.headers().get(header::WWW_AUTHENTICATE).unwrap(), "Bearer");
    let revoked_problem: serde_json::Value = serde_json::from_slice(
        &to_bytes(post_revoke_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(revoked_problem["type"], "urn:roche:problem:token-invalid");
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
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
    let dup_problem: serde_json::Value = serde_json::from_slice(
        &to_bytes(duplicate_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(dup_problem["type"], "urn:roche:problem:conflict");

    // SSRF URL is rejected with 400 Bad Request + ssrf-blocked URN
    let ssrf_request = Request::builder()
        .method("POST")
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"http://127.0.0.1:8080/internal","feed_type":"rss"}"#))
        .unwrap();
    let ssrf_response = app.clone().oneshot(ssrf_request).await.unwrap();
    assert_eq!(ssrf_response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        ssrf_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let ssrf_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(ssrf_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(ssrf_problem["type"], "urn:roche:problem:ssrf-blocked");

    // Trigger refresh via injected FakeFeedFetcher
    let req = Request::builder()
        .method("POST")
        .uri(format!("/api/v1/feeds/{feed_id}/refresh"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);

    // Verify refreshed item appears under feed items with feed_title and content_html
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
    assert_eq!(body["items"][0]["feed_title"], "Example");
    assert_eq!(body["items"][0]["content_html"], "Summary");

    // Verify feed unread_count is 1 after refresh
    let feed_get_request = Request::builder()
        .uri(format!("/api/v1/feeds/{feed_id}"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let feed_get_response = app.clone().oneshot(feed_get_request).await.unwrap();
    let feed_get_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(feed_get_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(feed_get_body["unread_count"], 1);

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
#[allow(clippy::too_many_lines)]
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

    // Reject out-of-range read_progress on PATCH /api/v1/items/{id}
    for invalid_progress in [1.5, -0.1] {
        let bad_progress = Request::builder()
            .method("PATCH")
            .uri(format!("/api/v1/items/{item_id}"))
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(format!(r#"{{"read_progress":{invalid_progress}}}"#)))
            .unwrap();
        let bad_progress_res = app.clone().oneshot(bad_progress).await.unwrap();
        assert_eq!(bad_progress_res.status(), StatusCode::BAD_REQUEST);
    }

    // Update read_progress and read_at via PATCH /api/v1/items/{id} and verify persistence
    let patch_progress = Request::builder()
        .method("PATCH")
        .uri(format!("/api/v1/items/{item_id}"))
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"read_progress":0.75,"read_at":"2026-03-31T09:30:00Z"}"#))
        .unwrap();
    let patch_res = app.clone().oneshot(patch_progress).await.unwrap();
    assert_eq!(patch_res.status(), StatusCode::OK);
    let patch_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(patch_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(patch_body["read_progress"], 0.75);
    assert_eq!(patch_body["read_at"], "2026-03-31T09:30:00Z");

    // Clear read_at back to null via PATCH /api/v1/items/{id}
    let clear_read_at = Request::builder()
        .method("PATCH")
        .uri(format!("/api/v1/items/{item_id}"))
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"read_at":null}"#))
        .unwrap();
    let clear_res = app.clone().oneshot(clear_read_at).await.unwrap();
    assert_eq!(clear_res.status(), StatusCode::OK);
    let clear_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(clear_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert!(clear_body["read_at"].is_null());

    let get_item = Request::builder()
        .uri(format!("/api/v1/items/{item_id}"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let get_res = app.clone().oneshot(get_item).await.unwrap();
    assert_eq!(get_res.status(), StatusCode::OK);
    let get_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(get_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(get_body["read_progress"], 0.75);

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
    assert_eq!(body["items"][0]["read_progress"], 0.75);

    // Export data includes item_tags and non-empty activity log
    let export_request = Request::builder()
        .uri("/api/v1/export/data")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let export_response = app.oneshot(export_request).await.unwrap();
    assert_eq!(export_response.status(), StatusCode::OK);
    let exp_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(export_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(exp_body["item_tags"].as_array().unwrap().len(), 2);
    assert!(exp_body["activity"].as_array().unwrap().len() >= 2);
}

#[tokio::test]
async fn test_media_serving_and_inbox_projections() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    let auth = format!("Bearer {token}");

    let temp_dir = std::env::temp_dir().join(format!("roche-media-test-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&temp_dir).await.unwrap();
    let thumb_path = temp_dir.join("thumb.webp");
    tokio::fs::write(&thumb_path, b"RIFF-fake-webp").await.unwrap();

    let mut config = crate::config::Config::default();
    config.storage.media_dir = temp_dir.clone();
    let service = RocheService::new(db.clone(), FakeFeedFetcher, config);
    let app = ApiRoutes::router_with_service(service);

    let url = Url::parse("https://youtube.com/watch?v=abc").unwrap();
    let (item, _) =
        db.items().save_manual_with_tags(&url, "Video Item", &["rust".to_string()]).await.unwrap();
    let thumb = db
        .media()
        .create(&item.id, crate::db::MediaType::Thumbnail, "thumb.webp", Some(14), None)
        .await
        .unwrap();
    let _video = db
        .media()
        .create(&item.id, crate::db::MediaType::Video, "video.mp4", Some(1024), Some(1694))
        .await
        .unwrap();

    // Star the item and verify ?is_favorite=true filter + thumbnail_media_id + duration projection
    db.items().update(&item.id, None, Some(true), None, ReadAtPatch::Unchanged).await.unwrap();
    let list_request = Request::builder()
        .uri("/api/v1/items?is_favorite=true")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let list_response = app.clone().oneshot(list_request).await.unwrap();
    assert_eq!(list_response.status(), StatusCode::OK);
    let list_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(list_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(list_body["items"][0]["thumbnail_media_id"], thumb.id);
    assert_eq!(list_body["items"][0]["duration"], 1694);
    assert_eq!(list_body["items"][0]["tag_names"], serde_json::json!(["rust"]));

    // Verify GET /api/v1/tags returns unread_count = 1
    let tags_request = Request::builder()
        .uri("/api/v1/tags")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let tags_response = app.clone().oneshot(tags_request).await.unwrap();
    let tags_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(tags_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(tags_body["tags"][0]["unread_count"], 1);

    // Verify GET /api/v1/media/{id} serves bytes with image/webp Content-Type and nosniff
    let media_request = Request::builder()
        .uri(format!("/api/v1/media/{}", thumb.id))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let media_response = app.oneshot(media_request).await.unwrap();
    assert_eq!(media_response.status(), StatusCode::OK);
    assert_eq!(media_response.headers().get(header::CONTENT_TYPE).unwrap(), "image/webp");
    assert_eq!(media_response.headers().get(header::X_CONTENT_TYPE_OPTIONS).unwrap(), "nosniff");
    let media_bytes = to_bytes(media_response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(&media_bytes[..], b"RIFF-fake-webp");

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
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
        String::from_utf8(to_bytes(res.into_body(), usize::MAX).await.unwrap().to_vec()).unwrap();
    assert!(exported.contains("https://blog.rust-lang.org/feed.xml?a=1&amp;b=2"));
    assert!(exported.contains("R &amp; D &gt; News"));
    assert!(exported.contains("https://rss.arxiv.org/rss/cs.AI"));
}

#[tokio::test]
async fn test_save_article_snapshot_and_arxiv_pdf_ingestion() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    let auth = format!("Bearer {token}");

    let temp_dir = std::env::temp_dir().join(format!("roche-save-test-{}", uuid::Uuid::new_v4()));
    let mut config = crate::config::Config::default();
    config.storage.media_dir = temp_dir.clone();
    let service = RocheService::new(db, FakeFeedFetcher, config);
    let app = ApiRoutes::router_with_service(service);

    // 1. Save article without explicit title -> returns immediately in Want to Read (`inbox_state = "none"`)
    //    and enriches `<title>`, meta summary, and sanitized HTML in the background
    let article_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://example.com/deep-dive"}"#))
        .unwrap();
    let article_response = app.clone().oneshot(article_request).await.unwrap();
    assert_eq!(article_response.status(), StatusCode::CREATED);
    let initial_article_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(article_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(initial_article_body["in_want_to_read"], true);
    assert_eq!(initial_article_body["inbox_state"], "none");
    let article_id = initial_article_body["id"].as_str().unwrap().to_string();

    let mut article_body = initial_article_body;
    for _ in 0..40 {
        if article_body["title"] == "Fetched Page Title" && article_body["content_html"].is_string()
        {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        let get_req = Request::builder()
            .uri(format!("/api/v1/items/{article_id}"))
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let get_res = app.clone().oneshot(get_req).await.unwrap();
        article_body =
            serde_json::from_slice(&to_bytes(get_res.into_body(), usize::MAX).await.unwrap())
                .unwrap();
    }
    assert_eq!(article_body["title"], "Fetched Page Title");
    assert_eq!(article_body["summary"], "Extracted meta summary");
    assert_eq!(article_body["content_type"], "article");
    let content_html = article_body["content_html"].as_str().unwrap();
    assert!(content_html.contains("<p>Full article body.</p>"));
    assert!(!content_html.contains("<script>"));

    // 2. Save arXiv abstract URL -> classifies as "paper", returns immediately, and downloads PDF in background
    let arxiv_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://arxiv.org/abs/2301.07041","title":"Attention Paper"}"#))
        .unwrap();
    let arxiv_response = app.clone().oneshot(arxiv_request).await.unwrap();
    assert_eq!(arxiv_response.status(), StatusCode::CREATED);
    let mut arxiv_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(arxiv_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(arxiv_body["content_type"], "paper");
    assert_eq!(arxiv_body["in_want_to_read"], true);
    let arxiv_id = arxiv_body["id"].as_str().unwrap().to_string();
    for _ in 0..40 {
        if arxiv_body["media"].as_array().is_some_and(|m| !m.is_empty()) {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        let get_req = Request::builder()
            .uri(format!("/api/v1/items/{arxiv_id}"))
            .header(header::AUTHORIZATION, &auth)
            .body(Body::empty())
            .unwrap();
        let get_res = app.clone().oneshot(get_req).await.unwrap();
        arxiv_body =
            serde_json::from_slice(&to_bytes(get_res.into_body(), usize::MAX).await.unwrap())
                .unwrap();
    }
    assert_eq!(arxiv_body["media"].as_array().unwrap().len(), 1);
    assert_eq!(arxiv_body["media"][0]["media_type"], "pdf");
    let pdf_media_id = arxiv_body["media"][0]["id"].as_str().unwrap().to_string();

    // 3. Stream the downloaded PDF via GET /api/v1/media/{id}
    let pdf_request = Request::builder()
        .uri(format!("/api/v1/media/{pdf_media_id}"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let pdf_response = app.oneshot(pdf_request).await.unwrap();
    assert_eq!(pdf_response.status(), StatusCode::OK);
    assert_eq!(pdf_response.headers().get(header::CONTENT_TYPE).unwrap(), "application/pdf");
    let pdf_bytes = to_bytes(pdf_response.into_body(), usize::MAX).await.unwrap();
    assert!(pdf_bytes.starts_with(b"%PDF-1.5"));

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_health_exposes_tailscale_discovery_urls() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let identity = crate::tailscale::NodeIdentity {
        hostname: "mac-mini".to_string(),
        dns_name: Some("mac-mini.tail9876.ts.net".to_string()),
        magic_dns_suffix: Some("tail9876.ts.net".to_string()),
        ipv4: Some("100.64.0.12".to_string()),
        ipv6: None,
        service_name: Some("svc:roche".to_string()),
        node_https_port: Some(443),
    };
    let service = RocheService::new(db, FakeFeedFetcher, crate::config::Config::default())
        .with_node_identity(Some(identity));
    let app = ApiRoutes::router_with_service(service);

    let req = Request::builder().uri("/health").body(Body::empty()).unwrap();
    let res = app.oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["status"], "ok");
    assert_eq!(body["service"], "roche");
    assert_eq!(body["canonical_url"], "https://roche.tail9876.ts.net");
    let urls = body["urls"].as_array().unwrap();
    assert_eq!(urls.len(), 2);
    assert_eq!(urls[0], "https://roche.tail9876.ts.net");
    assert_eq!(urls[1], "https://mac-mini.tail9876.ts.net");
}

#[tokio::test]
async fn test_media_byte_range_partial_content_and_416() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    let auth = format!("Bearer {token}");

    let temp_dir = std::env::temp_dir().join(format!("roche-range-test-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&temp_dir).await.unwrap();
    let payload = b"0123456789abcdef"; // 16 bytes
    tokio::fs::write(temp_dir.join("video.mp4"), payload).await.unwrap();

    let mut config = crate::config::Config::default();
    config.storage.media_dir = temp_dir.clone();
    let service = RocheService::new(db.clone(), FakeFeedFetcher, config);
    let app = ApiRoutes::router_with_service(service);

    let url = Url::parse("https://youtube.com/watch?v=abc").unwrap();
    let (item, _) = db.items().save_manual_with_tags(&url, "Video", &[]).await.unwrap();
    let media = db
        .media()
        .create(&item.id, crate::db::MediaType::Video, "video.mp4", Some(16), Some(60))
        .await
        .unwrap();

    // 1. Full GET without Range header -> 200 OK with Accept-Ranges, Content-Length, Content-Disposition
    let full_request = Request::builder()
        .uri(format!("/api/v1/media/{}", media.id))
        .header(header::AUTHORIZATION, &auth)
        .header(header::ACCEPT_ENCODING, "gzip")
        .body(Body::empty())
        .unwrap();
    let full_response = app.clone().oneshot(full_request).await.unwrap();
    assert_eq!(full_response.status(), StatusCode::OK);
    assert_eq!(full_response.headers().get(header::ACCEPT_RANGES).unwrap(), "bytes");
    assert_eq!(full_response.headers().get(header::CONTENT_LENGTH).unwrap(), "16");
    assert_eq!(
        full_response.headers().get(header::CONTENT_DISPOSITION).unwrap(),
        "inline; filename=\"video.mp4\""
    );
    let full_bytes = to_bytes(full_response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(&full_bytes[..], payload);

    // 2. Closed range bytes=0-3 -> 206 Partial Content
    let range_request = Request::builder()
        .uri(format!("/api/v1/media/{}", media.id))
        .header(header::AUTHORIZATION, &auth)
        .header(header::RANGE, "bytes=0-3")
        .body(Body::empty())
        .unwrap();
    let range_response = app.clone().oneshot(range_request).await.unwrap();
    assert_eq!(range_response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(range_response.headers().get(header::ACCEPT_RANGES).unwrap(), "bytes");
    assert_eq!(range_response.headers().get(header::CONTENT_LENGTH).unwrap(), "4");
    assert_eq!(range_response.headers().get(header::CONTENT_RANGE).unwrap(), "bytes 0-3/16");
    let range_bytes = to_bytes(range_response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(&range_bytes[..], b"0123");

    // 3. Open-ended range bytes=10- and clamped range bytes=12-999 -> 206 Partial Content
    for (hdr, expected_cr, expected_slice) in [
        ("bytes=10-", "bytes 10-15/16", &b"abcdef"[..]),
        ("bytes=12-999", "bytes 12-15/16", &b"cdef"[..]),
        ("bytes=-4", "bytes 12-15/16", &b"cdef"[..]),
    ] {
        let req = Request::builder()
            .uri(format!("/api/v1/media/{}", media.id))
            .header(header::AUTHORIZATION, &auth)
            .header(header::RANGE, hdr)
            .body(Body::empty())
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(res.headers().get(header::CONTENT_RANGE).unwrap(), expected_cr);
        assert_eq!(
            res.headers().get(header::CONTENT_LENGTH).unwrap(),
            &expected_slice.len().to_string()
        );
        let bytes = to_bytes(res.into_body(), usize::MAX).await.unwrap();
        assert_eq!(&bytes[..], expected_slice);
    }

    // 4. Unsatisfiable or malformed byte ranges -> 416 Range Not Satisfiable with Content-Range: bytes */16
    for bad_range in ["bytes=16-20", "bytes=10-5", "bytes=-0", "bytes=0-1, 3-4", "bytes=abc"] {
        let bad_request = Request::builder()
            .uri(format!("/api/v1/media/{}", media.id))
            .header(header::AUTHORIZATION, &auth)
            .header(header::RANGE, bad_range)
            .body(Body::empty())
            .unwrap();
        let bad_response = app.clone().oneshot(bad_request).await.unwrap();
        assert_eq!(bad_response.status(), StatusCode::RANGE_NOT_SATISFIABLE);
        assert_eq!(bad_response.headers().get(header::CONTENT_RANGE).unwrap(), "bytes */16");
    }

    // 5. Unknown range unit (RFC 9110 §14.2) -> ignored, falls back to 200 OK full content
    assert_eq!(parse_byte_range("items=0-5", 16), ByteRangeParse::IgnoreUnit);
    let unknown_unit_request = Request::builder()
        .uri(format!("/api/v1/media/{}", media.id))
        .header(header::AUTHORIZATION, &auth)
        .header(header::RANGE, "items=0-5")
        .body(Body::empty())
        .unwrap();
    let unknown_unit_response = app.oneshot(unknown_unit_request).await.unwrap();
    assert_eq!(unknown_unit_response.status(), StatusCode::OK);
    let unknown_unit_bytes = to_bytes(unknown_unit_response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(&unknown_unit_bytes[..], payload);

    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_feed_type_auto_inference_when_omitted() {
    let (app, auth) = setup_app().await;

    // 1. Bare YouTube channel ID with omitted feed_type -> infers youtube & normalizes feed URL
    let yt_request = Request::builder()
        .method("POST")
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"UCX6OQ3DkcsbYNE6H8uQQuVA"}"#))
        .unwrap();
    let yt_response = app.clone().oneshot(yt_request).await.unwrap();
    assert_eq!(yt_response.status(), StatusCode::CREATED);
    let yt_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(yt_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(yt_body["feed_type"], "youtube");
    assert_eq!(
        yt_body["url"],
        "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA"
    );

    // 2. arXiv RSS URL with omitted feed_type -> infers arxiv
    let arxiv_request = Request::builder()
        .method("POST")
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://rss.arxiv.org/rss/cs.LG"}"#))
        .unwrap();
    let arxiv_response = app.clone().oneshot(arxiv_request).await.unwrap();
    assert_eq!(arxiv_response.status(), StatusCode::CREATED);
    let arxiv_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(arxiv_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(arxiv_body["feed_type"], "arxiv");
    assert_eq!(arxiv_body["url"], "https://rss.arxiv.org/rss/cs.LG");

    // 3. Single arXiv paper URL (/abs/ or /pdf/) is rejected as a feed URL with 400 Bad Request
    for paper_url in ["https://arxiv.org/abs/2301.07041", "https://arxiv.org/pdf/2301.07041.pdf"] {
        let bad_feed_request = Request::builder()
            .method("POST")
            .uri("/api/v1/feeds")
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(format!(r#"{{"url":"{paper_url}"}}"#)))
            .unwrap();
        let bad_feed_response = app.clone().oneshot(bad_feed_request).await.unwrap();
        assert_eq!(bad_feed_response.status(), StatusCode::BAD_REQUEST);
    }
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_incremental_sync_changes_pagination_and_updated_after() {
    let (app, auth) = setup_app().await;

    // 1. Initial sync status has latest_activity_id = 0
    let status_request = Request::builder()
        .uri("/api/v1/sync/status")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let status_response = app.clone().oneshot(status_request).await.unwrap();
    assert_eq!(status_response.status(), StatusCode::OK);
    let status_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(status_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(status_body["latest_activity_id"], 0);

    // 2. Create a feed without title -> refresh populates title/description and logs feed update + item create
    let create_feed = Request::builder()
        .method("POST")
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://example.com/feed.xml","feed_type":"rss"}"#))
        .unwrap();
    let create_response = app.clone().oneshot(create_feed).await.unwrap();
    let feed_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(create_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    let feed_id = feed_body["id"].as_str().unwrap().to_string();

    let refresh_request = Request::builder()
        .method("POST")
        .uri(format!("/api/v1/feeds/{feed_id}/refresh"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let refresh_response = app.clone().oneshot(refresh_request).await.unwrap();
    assert_eq!(refresh_response.status(), StatusCode::OK);

    // Save manual item so we have 2 items total
    let save_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://example.com/manual","title":"Manual Item"}"#))
        .unwrap();
    let save_response = app.clone().oneshot(save_request).await.unwrap();
    assert_eq!(save_response.status(), StatusCode::CREATED);

    // 3. Paginate /api/v1/sync/changes with limit=2
    let first_changes_request = Request::builder()
        .uri("/api/v1/sync/changes?since_id=0&limit=2")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let first_changes_response = app.clone().oneshot(first_changes_request).await.unwrap();
    assert_eq!(first_changes_response.status(), StatusCode::OK);
    let changes_page1: serde_json::Value = serde_json::from_slice(
        &to_bytes(first_changes_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    let page1_arr = changes_page1["changes"].as_array().unwrap();
    assert_eq!(page1_arr.len(), 2);
    assert_eq!(changes_page1["has_more"], true);
    assert_eq!(page1_arr[0]["entity_type"], "feed");
    assert_eq!(page1_arr[0]["action"], "create");
    assert_eq!(page1_arr[1]["entity_type"], "feed");
    assert_eq!(page1_arr[1]["action"], "update");
    let cursor = changes_page1["next_since_id"].as_i64().unwrap();
    assert_eq!(cursor, page1_arr[1]["id"].as_i64().unwrap());

    let next_changes_request = Request::builder()
        .uri(format!("/api/v1/sync/changes?since_id={cursor}&limit=10"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let next_changes_response = app.clone().oneshot(next_changes_request).await.unwrap();
    let changes_page2: serde_json::Value = serde_json::from_slice(
        &to_bytes(next_changes_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(changes_page2["has_more"], false);
    assert_eq!(changes_page2["changes"].as_array().unwrap().len(), 2);
    assert_eq!(changes_page2["next_since_id"], changes_page2["latest_activity_id"]);

    // 4. Verify pagination metadata & updated_after on GET /api/v1/items
    let paged_items_request = Request::builder()
        .uri("/api/v1/items?page=1&per_page=1")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let paged_items_response = app.clone().oneshot(paged_items_request).await.unwrap();
    let items_p1: serde_json::Value = serde_json::from_slice(
        &to_bytes(paged_items_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(items_p1["page"], 1);
    assert_eq!(items_p1["per_page"], 1);
    assert_eq!(items_p1["has_more"], true);
    assert_eq!(items_p1["items"].as_array().unwrap().len(), 1);

    let future_request = Request::builder()
        .uri("/api/v1/items?updated_after=2099-01-01T00:00:00Z")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let future_response = app.clone().oneshot(future_request).await.unwrap();
    assert_eq!(future_response.status(), StatusCode::OK);
    let future_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(future_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(future_body["items"].as_array().unwrap().len(), 0);
    assert_eq!(future_body["has_more"], false);

    let invalid_ts_request = Request::builder()
        .uri("/api/v1/items?updated_after=not-a-timestamp")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let invalid_ts_response = app.clone().oneshot(invalid_ts_request).await.unwrap();
    assert_eq!(invalid_ts_response.status(), StatusCode::BAD_REQUEST);

    // 5. Verify POST /api/v1/items/archive-batch rejects empty request and archives unread items
    let empty_batch_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/archive-batch")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from("{}"))
        .unwrap();
    let empty_batch_response = app.clone().oneshot(empty_batch_request).await.unwrap();
    assert_eq!(empty_batch_response.status(), StatusCode::BAD_REQUEST);

    let batch_archive_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/archive-batch")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"all_unread":true}"#))
        .unwrap();
    let batch_archive_response = app.oneshot(batch_archive_request).await.unwrap();
    assert_eq!(batch_archive_response.status(), StatusCode::OK);
    let batch_archive_body: serde_json::Value = serde_json::from_slice(
        &to_bytes(batch_archive_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(batch_archive_body["archived_count"], 1);
}

#[tokio::test]
async fn test_pairing_rate_limit_protects_active_code_from_rapid_burst() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let app = ApiRoutes::router_with_service(service.clone());

    // Bogus attempts before any pairing code exists follow the exact same 4x 401 -> 429 rate
    // limit as an active code (no active-code oracle), and issuing a code resets the limiter.
    for i in 0..4 {
        let unissued_request = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .header("content-type", "application/json")
            .body(Body::from(format!(r#"{{"device_name":"pre-{i}","pairing_code":"0000-wrong"}}"#)))
            .unwrap();
        let unissued_response = app.clone().oneshot(unissued_request).await.unwrap();
        assert_eq!(unissued_response.status(), StatusCode::UNAUTHORIZED);
    }
    let unissued_throttled = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(r#"{"device_name":"pre-4","pairing_code":"0000-wrong"}"#))
        .unwrap();
    assert_eq!(
        app.clone().oneshot(unissued_throttled).await.unwrap().status(),
        StatusCode::TOO_MANY_REQUESTS
    );

    let valid_code = service.auth().issue_pairing_code().await.unwrap();

    // 4 rapid wrong attempts return 401 Unauthorized (pairing-code-invalid)
    for i in 0..4 {
        let req = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .header("content-type", "application/json")
            .body(Body::from(format!(
                r#"{{"device_name":"attacker-{i}","pairing_code":"0000-wrong"}}"#
            )))
            .unwrap();
        let res = app.clone().oneshot(req).await.unwrap();
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
        let p: serde_json::Value =
            serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
        assert_eq!(p["type"], "urn:roche:problem:pairing-code-invalid");
    }

    // 5th rapid attempt is throttled with 429 Too Many Requests + Retry-After before touching the DB
    let throttled_request = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(r#"{"device_name":"attacker-5","pairing_code":"0000-wrong"}"#))
        .unwrap();
    let throttled_response = app.clone().oneshot(throttled_request).await.unwrap();
    assert_eq!(throttled_response.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        throttled_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let retry_after_hdr: u64 = throttled_response
        .headers()
        .get(header::RETRY_AFTER)
        .unwrap()
        .to_str()
        .unwrap()
        .parse()
        .unwrap();
    assert!((1..=60).contains(&retry_after_hdr));
    let problem: serde_json::Value = serde_json::from_slice(
        &to_bytes(throttled_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(problem["type"], "urn:roche:problem:pairing-rate-limited");
    assert_eq!(problem["retry_after_seconds"], retry_after_hdr);

    // Simulating `roche pair` in a separate CLI process (writing directly to SQLite)
    // automatically resets the server's rate-limiter state because the active code hash changed!
    let fresh_code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
    assert_ne!(fresh_code, valid_code);
    let pair_request = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(format!(
            r#"{{"device_name":"legit-phone","pairing_code":"{fresh_code}"}}"#
        )))
        .unwrap();
    let pair_response = app.oneshot(pair_request).await.unwrap();
    assert_eq!(pair_response.status(), StatusCode::OK);
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn peer_sync_endpoint_replicates_feeds_items_tags_and_tombstones() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let app = ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher);

    let hlc_1 = "001780315200000-0001-android-peer-1";
    let hlc_2 = "001780315200000-0002-android-peer-1";
    let sync_payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 9",
        "network_tier": "wifi",
        "since_seq": 0,
        "client_hlc": hlc_1,
        "entries": [
            {
                "id": "local-feed-1",
                "kind": "publication",
                "title": "Example Blog",
                "hlc": hlc_1,
                "collection_spec": {
                    "mode": "feed",
                    "role": "feed",
                    "show_in_lists": false
                },
                "feed_source": {
                    "feed_url": "https://blog.example.com/rss.xml",
                    "source_type": "rss",
                    "feed_title": "Example Blog",
                    "poll_interval": 30,
                    "is_active": true,
                    "last_polled_at": "2026-06-01T12:00:00Z",
                    "last_polled_by": "android-peer-1",
                    "hlc": hlc_1
                }
            },
            {
                "id": "local-tag-1",
                "kind": "collection",
                "title": "distributed",
                "hlc": hlc_1,
                "collection_spec": {
                    "slug": "tag:distributed",
                    "mode": "manual",
                    "role": "tag",
                    "show_in_lists": false,
                    "color": "#10b981"
                }
            },
            {
                "id": "local-item-1",
                "kind": "article",
                "title": "Post 1 fetched on Android",
                "url": "https://blog.example.com/post-1",
                "summary": "Full body from phone",
                "hlc": hlc_1,
                "content": {
                    "content_type": "article",
                    "content_html": "<p>Full body from phone</p>"
                },
                "state": {
                    "inbox_state": "unread",
                    "read_at": "2026-06-01T12:05:00Z",
                    "progress": 0.9,
                    "interaction_updated_at": "2026-06-01T12:05:00Z",
                    "hlc": hlc_1
                }
            }
        ],
        "collection_entries": [
            {
                "collection_id": "local-feed-1",
                "entry_id": "local-item-1",
                "added_at": "2026-06-01T12:00:00Z",
                "hlc": hlc_1
            },
            {
                "collection_id": crate::db::STARRED_COLLECTION_ID,
                "entry_id": "local-item-1",
                "added_at": "2026-06-01T12:05:00Z",
                "hlc": hlc_1
            },
            {
                "collection_id": "local-tag-1",
                "entry_id": "local-item-1",
                "added_at": "2026-06-01T12:05:00Z",
                "hlc": hlc_1
            }
        ],
        "tombstones": []
    });

    let req = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(sync_payload.to_string()))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["server_peer_id"], "server");
    assert_eq!(body["has_more"], false);
    assert!(body["sync_seq"].as_i64().unwrap() > 0);
    assert_ne!(body["server_hlc"].as_str().unwrap(), "");
    assert_eq!(body["entries"].as_array().unwrap().len(), 3);
    assert_eq!(body["collection_entries"].as_array().unwrap().len(), 3);
    assert_eq!(body["active_peers"].as_array().unwrap().len(), 2);

    // Sending the exact same payload again must produce zero duplicates
    let repeat_request = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(sync_payload.to_string()))
        .unwrap();
    let repeat_response = app.clone().oneshot(repeat_request).await.unwrap();
    assert_eq!(repeat_response.status(), StatusCode::OK);
    assert_eq!(db.feeds().list().await.unwrap().len(), 1);
    let all_items = db.items().list(None, None, Some(50)).await.unwrap();
    assert_eq!(all_items.len(), 1);
    assert!(all_items[0].is_favorite);
    assert_eq!(all_items[0].tag_names.0, vec!["distributed".to_string()]);

    // Skip non-public SSRF feed URLs in peer sync without failing the sync request
    let bad_payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 9",
        "network_tier": "wifi",
        "entries": [{
            "id": "ssrf-feed",
            "kind": "publication",
            "title": "Internal",
            "hlc": hlc_2,
            "collection_spec": {"mode": "feed", "role": "feed"},
            "feed_source": {
                "feed_url": "http://127.0.0.1:8080/private.xml",
                "source_type": "rss",
                "hlc": hlc_2
            }
        }]
    });
    let ssrf_request = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(bad_payload.to_string()))
        .unwrap();
    let ssrf_response = app.clone().oneshot(ssrf_request).await.unwrap();
    assert_eq!(ssrf_response.status(), StatusCode::OK);
    assert_eq!(db.feeds().list().await.unwrap().len(), 1);

    // Propagate feed archival (is_active = false) via newer HLC without losing reading history
    let archive_sync_payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 9",
        "network_tier": "wifi",
        "entries": [{
            "id": "local-feed-1",
            "kind": "publication",
            "title": "Example Blog",
            "hlc": hlc_2,
            "collection_spec": {"mode": "feed", "role": "feed"},
            "feed_source": {
                "feed_url": "https://blog.example.com/rss.xml",
                "source_type": "rss",
                "feed_title": "Example Blog",
                "poll_interval": 30,
                "is_active": false,
                "last_polled_at": "2026-06-01T12:00:00Z",
                "last_polled_by": "android-peer-1",
                "hlc": hlc_2
            }
        }]
    });
    let archive_sync_request = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(archive_sync_payload.to_string()))
        .unwrap();
    let archive_sync_response = app.clone().oneshot(archive_sync_request).await.unwrap();
    assert_eq!(archive_sync_response.status(), StatusCode::OK);
    let feeds_now = db.feeds().list().await.unwrap();
    assert!(!feeds_now[0].is_active);
    let feed_id = feeds_now[0].id.clone();

    // Unarchive via POST /api/v1/feeds/{id}/unarchive and archive via POST /api/v1/feeds/{id}/archive
    let unarchive_request = Request::builder()
        .method("POST")
        .uri(format!("/api/v1/feeds/{feed_id}/unarchive"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let unarchive_response = app.clone().oneshot(unarchive_request).await.unwrap();
    assert_eq!(unarchive_response.status(), StatusCode::OK);
    let unarchived_feed: serde_json::Value = serde_json::from_slice(
        &to_bytes(unarchive_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(unarchived_feed["is_active"], true);

    let archive_request = Request::builder()
        .method("POST")
        .uri(format!("/api/v1/feeds/{feed_id}/archive"))
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let archive_response = app.oneshot(archive_request).await.unwrap();
    assert_eq!(archive_response.status(), StatusCode::OK);
    let archived_feed: serde_json::Value =
        serde_json::from_slice(&to_bytes(archive_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(archived_feed["is_active"], false);

    let preserved_items = db.items().list(None, Some(&feed_id), Some(50)).await.unwrap();
    assert_eq!(preserved_items.len(), 1);
    assert!(preserved_items[0].is_favorite);
    assert!(preserved_items[0].read_at.is_some());
}

#[tokio::test]
async fn test_large_body_routes_accept_payloads_over_2_mib() {
    let (app, auth) = setup_app().await;
    let padding = "x".repeat(2_300_000);

    let large_opml = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <!-- {padding} -->
  <body>
    <outline type="rss" text="Large OPML Feed" xmlUrl="https://example.com/large-opml.xml" />
  </body>
</opml>"#
    );
    assert!(large_opml.len() > 2 * 1024 * 1024);

    let opml_request = Request::builder()
        .method("POST")
        .uri("/api/v1/import/opml")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/xml")
        .body(Body::from(large_opml))
        .unwrap();
    let opml_response = app.clone().oneshot(opml_request).await.unwrap();
    assert_eq!(opml_response.status(), StatusCode::OK);
    let opml_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(opml_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(opml_body["imported_count"], 1);

    let html_chunk = format!("<p>{}</p>", "a".repeat(40_000));
    let entries: Vec<serde_json::Value> = (0..60)
        .map(|idx| {
            serde_json::json!({
                "kind": "article",
                "url": format!("https://example.com/large-sync-item-{idx}"),
                "title": format!("Large Item {idx}"),
                "content": {
                    "content_type": "article",
                    "content_html": html_chunk
                },
                "state": {
                    "inbox_state": "unread",
                    "progress": 0.0
                }
            })
        })
        .collect();
    let large_sync_json = serde_json::json!({
        "peer_id": "android-large-sync",
        "device_name": "Pixel 10",
        "network_tier": "wifi",
        "entries": entries
    })
    .to_string();
    assert!(large_sync_json.len() > 2 * 1024 * 1024);

    let sync_request = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(large_sync_json))
        .unwrap();
    let sync_response = app.oneshot(sync_request).await.unwrap();
    assert_eq!(sync_response.status(), StatusCode::OK);
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_peer_sync_skips_lan_feeds_and_accepts_non_standard_port_items() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let app = ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher);

    let payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 10",
        "network_tier": "wifi",
        "entries": [
            {
                "id": "lan-feed",
                "kind": "publication",
                "title": "Home Router Feed",
                "collection_spec": {"mode": "feed", "role": "feed"},
                "feed_source": {
                    "feed_url": "http://192.168.1.50/rss.xml",
                    "source_type": "rss"
                }
            },
            {
                "id": "public-feed",
                "kind": "publication",
                "title": "Valid Public Feed",
                "collection_spec": {"mode": "feed", "role": "feed"},
                "feed_source": {
                    "feed_url": "https://valid.example.com/rss.xml",
                    "source_type": "rss"
                }
            },
            {
                "id": "lan-feed-item",
                "kind": "article",
                "url": "http://192.168.1.50/router-status",
                "title": "Router Status",
                "content": {"content_type": "article"},
                "state": {"inbox_state": "unread"}
            },
            {
                "id": "port-demo",
                "kind": "article",
                "url": "https://example.com:8443/demo",
                "title": "Port 8443 Demo",
                "content": {"content_type": "article"},
                "state": {"inbox_state": "unread"}
            },
            {
                "id": "tailnet-item",
                "kind": "article",
                "url": "https://intranet.tailnet.ts.net:9443/internal-note",
                "title": "Tailnet Non-Standard Port Item",
                "content": {"content_type": "article"},
                "state": {"inbox_state": "unread"}
            },
            {
                "id": "valid-feed-item",
                "kind": "article",
                "url": "https://valid.example.com:8443/post-1",
                "title": "Valid Feed Item With Port",
                "content": {"content_type": "article"},
                "state": {"inbox_state": "unread"}
            },
            {
                "id": "bad-creds",
                "kind": "article",
                "url": "https://user:secret@example.com/disallowed-creds",
                "title": "Credentials In Permalink",
                "content": {"content_type": "article"},
                "state": {"inbox_state": "unread"}
            }
        ],
        "collection_entries": [
            {
                "collection_id": "lan-feed",
                "entry_id": "lan-feed-item",
                "added_at": "2026-06-01T12:00:00Z"
            },
            {
                "collection_id": "public-feed",
                "entry_id": "valid-feed-item",
                "added_at": "2026-06-01T12:00:00Z"
            }
        ]
    });

    let req = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .unwrap();
    let res = app.oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();

    let entries = body["entries"].as_array().unwrap();
    let feeds: Vec<_> = entries.iter().filter(|e| !e["feed_source"].is_null()).collect();
    assert_eq!(feeds.len(), 1);
    assert_eq!(feeds[0]["feed_source"]["feed_url"], "https://valid.example.com/rss.xml");

    let inbox_urls: Vec<&str> = entries
        .iter()
        .filter(|e| !e["state"].is_null())
        .map(|e| e["url"].as_str().unwrap())
        .collect();
    assert_eq!(inbox_urls.len(), 3);
    assert!(inbox_urls.contains(&"https://example.com:8443/demo"));
    assert!(inbox_urls.contains(&"https://intranet.tailnet.ts.net:9443/internal-note"));
    assert!(inbox_urls.contains(&"https://valid.example.com:8443/post-1"));
}

#[tokio::test]
async fn test_pairing_rate_limit_is_atomic_under_concurrent_burst() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let valid_code = service.auth().issue_pairing_code().await.unwrap();
    let app = ApiRoutes::router_with_service(service);

    let mut join_set = tokio::task::JoinSet::new();
    for idx in 0..10 {
        let router = app.clone();
        join_set.spawn(async move {
            let req = Request::builder()
                .method("POST")
                .uri("/auth/pair")
                .header("content-type", "application/json")
                .body(Body::from(format!(
                    r#"{{"device_name":"burst-{idx}","pairing_code":"0000-wrong"}}"#
                )))
                .unwrap();
            router.oneshot(req).await.unwrap().status()
        });
    }

    let mut unauthorized_count = 0usize;
    let mut throttled_count = 0usize;
    while let Some(res) = join_set.join_next().await {
        match res.unwrap() {
            StatusCode::UNAUTHORIZED => unauthorized_count += 1,
            StatusCode::TOO_MANY_REQUESTS => throttled_count += 1,
            other => panic!("unexpected status: {other}"),
        }
    }

    assert_eq!(unauthorized_count, 4);
    assert_eq!(throttled_count, 6);
    assert!(
        db.auth().active_pairing_code_hash().await.unwrap().is_some(),
        "Active pairing code must survive concurrent wrong burst without TOCTOU burnout"
    );
    assert_ne!(valid_code, "");
}

#[tokio::test]
async fn test_per_ip_pairing_rate_limit_isolates_attacker_from_legitimate_ip() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let valid_code = service.auth().issue_pairing_code().await.unwrap();
    let app = ApiRoutes::router_with_service(service);

    let attacker_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 10)), 51000);
    let legit_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 0, 2, 20)), 52000);

    // 5 bad guesses from 192.0.2.10: first 4 return 401, 5th is rate-limited with 429
    for attempt in 0..5 {
        let mut req = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .header("content-type", "application/json")
            .body(Body::from(format!(
                r#"{{"device_name":"attacker-{attempt}","pairing_code":"0000-wrong-guess"}}"#
            )))
            .unwrap();
        req.extensions_mut().insert(ConnectInfo(attacker_addr));
        let res = app.clone().oneshot(req).await.unwrap();
        if attempt < 4 {
            assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
        } else {
            assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
        }
    }

    // Subsequent attempt from 192.0.2.10 remains blocked even with the right code
    let mut blocked_request = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(format!(
            r#"{{"device_name":"attacker-late","pairing_code":"{valid_code}"}}"#
        )))
        .unwrap();
    blocked_request.extensions_mut().insert(ConnectInfo(attacker_addr));
    let blocked_response = app.clone().oneshot(blocked_request).await.unwrap();
    assert_eq!(blocked_response.status(), StatusCode::TOO_MANY_REQUESTS);

    // Legitimate client on 192.0.2.20 is NOT rate-limited and pairs cleanly with active code
    let mut legit_request = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(format!(
            r#"{{"device_name":"legit-pixel","pairing_code":"{valid_code}"}}"#
        )))
        .unwrap();
    legit_request.extensions_mut().insert(ConnectInfo(legit_addr));
    let legit_response = app.oneshot(legit_request).await.unwrap();
    assert_eq!(legit_response.status(), StatusCode::OK);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(legit_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_ne!(body["token"].as_str().unwrap(), "");
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_catalog_api_collection_generator_and_peer_sync_roundtrip() {
    let (app, auth) = setup_app().await;

    let seed_batch = serde_json::json!({
        "collections": [{
            "ref_key": "col:pool",
            "title": "Album Backlog",
            "slug": "album_backlog",
            "role": "list",
            "members": [
                {"entry_ref": "a1", "position": 1.0},
                {"entry_ref": "a2", "position": 2.0},
                {"entry_ref": "a3", "position": 3.0}
            ]
        }],
        "entries": [
            {"ref_key": "a1", "kind": "album", "title": "Kind of Blue", "creators": ["Miles Davis"]},
            {"ref_key": "a2", "kind": "album", "title": "Time Out", "creators": ["Dave Brubeck"]},
            {"ref_key": "a3", "kind": "album", "title": "Mingus Ah Um", "creators": ["Charles Mingus"]}
        ]
    });
    let seed_req = Request::builder()
        .method("POST")
        .uri("/api/v1/catalog/batch")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(seed_batch.to_string()))
        .unwrap();
    assert_eq!(app.clone().oneshot(seed_req).await.unwrap().status(), StatusCode::OK);

    // 1. Enable collection generator -> draws an active album; finishing draws next
    let enable_request = Request::builder()
        .method("PATCH")
        .uri("/api/v1/catalog/lists/album_backlog/generator")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"enabled":true}"#))
        .unwrap();
    let enable_response = app.clone().oneshot(enable_request).await.unwrap();
    assert_eq!(enable_response.status(), StatusCode::OK);
    let enable_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(enable_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(enable_body["enabled"], true);
    let first_album_id = enable_body["current_entry"]["id"].as_str().unwrap().to_string();

    let finish_request = Request::builder()
        .method("POST")
        .uri("/api/v1/catalog/lists/album_backlog/generator/finish")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"notes":"Phenomenal arrangement"}"#))
        .unwrap();
    let finish_response = app.clone().oneshot(finish_request).await.unwrap();
    assert_eq!(finish_response.status(), StatusCode::OK);
    let finish_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(finish_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(finish_body["completed_entry"]["id"], first_album_id);
    assert_eq!(finish_body["generator"]["consumed_count"], 1);
    let second_album_id =
        finish_body["generator"]["current_entry"]["id"].as_str().unwrap().to_string();
    assert_ne!(first_album_id, second_album_id);

    // 2. Reject manual entry mutation on dynamic list (`movies`) with RFC 9457 400 Bad Request
    let dyn_mutate = Request::builder()
        .method("POST")
        .uri("/api/v1/catalog/lists/movies/entries")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(format!(r#"{{"entry_id":"{first_album_id}"}}"#)))
        .unwrap();
    let dyn_res = app.clone().oneshot(dyn_mutate).await.unwrap();
    assert_eq!(dyn_res.status(), StatusCode::BAD_REQUEST);
    assert_eq!(dyn_res.headers().get(header::CONTENT_TYPE).unwrap(), "application/problem+json");
    let dyn_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(dyn_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(dyn_problem["type"], "urn:roche:problem:immutable-collection");

    // 3. Peer sync round-trip: offline peer logs a second album, compares it against `first_album_id`,
    //    and finishes `second_album_id` while offline -> server advances generator automatically!
    let sync_payload = serde_json::json!({
        "peer_id": "android-companion-1",
        "device_name": "Pixel",
        "network_tier": "wifi",
        "entries": [{
            "id": "",
            "kind": "album",
            "title": "Blue Train",
            "creators": ["John Coltrane"],
            "release_year": 1958,
            "identifiers": [{"scheme": "wikipedia", "value": "Blue_Train_(album)"}]
        }],
        "consumption_log": [{
            "id": "",
            "entry_id": second_album_id,
            "ts": "2026-11-14T12:00:00Z",
            "status": "consumed",
            "notes": "Listened on flight"
        }],
        "comparisons": [{
            "id": "",
            "ts": "2026-11-14T12:05:00Z",
            "scope_collection_id": null,
            "ordering": [[first_album_id], [second_album_id]]
        }]
    });
    let peer_request = Request::builder()
        .method("POST")
        .uri("/api/v1/sync/peer")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(sync_payload.to_string()))
        .unwrap();
    let peer_response = app.clone().oneshot(peer_request).await.unwrap();
    assert_eq!(peer_response.status(), StatusCode::OK);
    let peer_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(peer_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(peer_body["comparisons"].as_array().unwrap().len(), 1);
    let gen_out = &peer_body["collection_generators"][0];
    assert_ne!(
        gen_out["current_entry_id"].as_str().unwrap(),
        second_album_id,
        "Peer sync finishing the active drawn entry must advance collection generator to a new draw"
    );

    // 4. Ranked albums endpoint reflects `first_album_id > second_album_id` with S and D tiers
    let ranked_request = Request::builder()
        .uri("/api/v1/catalog/lists/albums")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let ranked_response = app.oneshot(ranked_request).await.unwrap();
    assert_eq!(ranked_response.status(), StatusCode::OK);
    let list_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(ranked_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    let rows = list_body["rows"].as_array().unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0]["entry"]["id"], first_album_id);
    assert_eq!(rows[0]["tier"], "S");
    assert_eq!(rows[1]["entry"]["id"], second_album_id);
    assert_eq!(rows[1]["tier"], "D");
}

#[tokio::test]
async fn test_oversized_payload_returns_rfc9457_problem_details_413() {
    let (app, auth) = setup_app().await;

    // Protected route body limit is 64 KiB; send 70 KiB JSON payload
    let big_title = "a".repeat(70 * 1024);
    let body = serde_json::json!({
        "url": "https://example.com/feed.xml",
        "title": big_title,
    });
    let req = Request::builder()
        .method("POST")
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, &auth)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(res.headers().get(header::CONTENT_TYPE).unwrap(), "application/problem+json");
    let problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(problem["type"], "urn:roche:problem:payload-too-large");
    assert_eq!(problem["status"], 413);
    assert_eq!(problem["max_bytes"], 65_536);

    // Public pairing route body limit is 16 KiB; send 20 KiB JSON payload
    let big_name = "x".repeat(20 * 1024);
    let pair_body = serde_json::json!({
        "device_name": big_name,
        "pairing_code": "1234-5678",
    });
    let pair_request = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(pair_body.to_string()))
        .unwrap();
    let pair_response = app.oneshot(pair_request).await.unwrap();
    assert_eq!(pair_response.status(), StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(
        pair_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let pair_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(pair_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(pair_problem["type"], "urn:roche:problem:payload-too-large");
    assert_eq!(pair_problem["max_bytes"], 16_384);
}

#[tokio::test]
async fn test_ambiguous_lookup_and_item_ssrf_problem_details() {
    let (app, auth) = setup_app().await;

    // SSRF on POST /api/v1/items/save returns 400 + ssrf-blocked URN
    let ssrf_item_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(r#"{"url":"http://169.254.169.254/latest/meta-data"}"#))
        .unwrap();
    let ssrf_item_response = app.clone().oneshot(ssrf_item_request).await.unwrap();
    assert_eq!(ssrf_item_response.status(), StatusCode::BAD_REQUEST);
    assert_eq!(
        ssrf_item_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let ssrf_problem: serde_json::Value = serde_json::from_slice(
        &to_bytes(ssrf_item_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(ssrf_problem["type"], "urn:roche:problem:ssrf-blocked");

    // Seed two distinct movies with identical title "Solaris" into the want-to-watch queue
    for (director, year, tmdb_id) in
        [("Andrei Tarkovsky", 1972, "593"), ("Steven Soderbergh", 2002, "2103")]
    {
        let want_request = Request::builder()
            .method("POST")
            .uri("/api/v1/catalog/want")
            .header(header::AUTHORIZATION, &auth)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                serde_json::json!({
                    "queue": "movies",
                    "entry": {
                        "kind": "movie",
                        "title": "Solaris",
                        "creators": [director],
                        "release_year": year,
                        "identifiers": [{"scheme": "tmdb", "value": tmdb_id}],
                    }
                })
                .to_string(),
            ))
            .unwrap();
        let want_response = app.clone().oneshot(want_request).await.unwrap();
        assert_eq!(want_response.status(), StatusCode::CREATED);
    }

    // Resolving "Solaris" in the movies queue fails with 409 + ambiguous-lookup + candidates array
    let ambig_request = Request::builder()
        .method("DELETE")
        .uri("/api/v1/catalog/want/movies/Solaris")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let ambig_response = app.oneshot(ambig_request).await.unwrap();
    assert_eq!(ambig_response.status(), StatusCode::CONFLICT);
    assert_eq!(
        ambig_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let ambig_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(ambig_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(ambig_problem["type"], "urn:roche:problem:ambiguous-lookup");
    assert_eq!(ambig_problem["status"], 409);
    let candidates = ambig_problem["candidates"].as_array().unwrap();
    assert_eq!(candidates.len(), 2);
    assert!(candidates.iter().all(|c| c.as_str().unwrap().contains("Solaris")));
}

#[tokio::test]
async fn test_not_found_html_vs_api_problem_details_boundary() {
    let (app, _auth) = setup_app().await;

    // Browser Accept: text/html on non-API unknown route returns HTML 404 with web security headers
    let browser_request = Request::builder()
        .uri("/does-not-exist")
        .header(header::ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .body(Body::empty())
        .unwrap();
    let browser_response = app.clone().oneshot(browser_request).await.unwrap();
    assert_eq!(browser_response.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        browser_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "text/html; charset=utf-8"
    );
    assert_eq!(browser_response.headers().get(header::X_CONTENT_TYPE_OPTIONS).unwrap(), "nosniff");
    assert_eq!(browser_response.headers().get(header::X_FRAME_OPTIONS).unwrap(), "SAMEORIGIN");
    let browser_body = String::from_utf8(
        to_bytes(browser_response.into_body(), usize::MAX).await.unwrap().to_vec(),
    )
    .unwrap();
    assert!(browser_body.contains("<!doctype html>"));
    assert!(browser_body.contains("Not Found"));

    // /api/* unknown route ALWAYS returns application/problem+json even with Accept: text/html
    let api_html_request = Request::builder()
        .uri("/api/v1/does-not-exist")
        .header(header::ACCEPT, "text/html,application/xhtml+xml,*/*;q=0.8")
        .body(Body::empty())
        .unwrap();
    let api_html_response = app.clone().oneshot(api_html_request).await.unwrap();
    assert_eq!(api_html_response.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        api_html_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let api_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(api_html_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(api_problem["type"], "about:blank");

    // Non-API unknown route without Accept: text/html (headless/curl) returns application/problem+json
    let headless_request = Request::builder().uri("/does-not-exist").body(Body::empty()).unwrap();
    let headless_response = app.oneshot(headless_request).await.unwrap();
    assert_eq!(headless_response.status(), StatusCode::NOT_FOUND);
    assert_eq!(
        headless_response.headers().get(header::CONTENT_TYPE).unwrap(),
        "application/problem+json"
    );
    let headless_problem: serde_json::Value =
        serde_json::from_slice(&to_bytes(headless_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(headless_problem["type"], "about:blank");
}

#[tokio::test]
async fn test_api_collection_hex_color_validation() {
    let (app, auth) = setup_app().await;

    // POST /api/v1/catalog/lists normalizes valid uppercase hex color to lowercase
    let create_ok = Request::builder()
        .method("POST")
        .uri("/api/v1/catalog/lists")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"title":"Accent List","color":" #3B82F6 "}"#))
        .unwrap();
    let ok_res = app.clone().oneshot(create_ok).await.unwrap();
    assert_eq!(ok_res.status(), StatusCode::CREATED);
    let ok_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(ok_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(ok_body["color"], "#3b82f6");

    // Whitespace-only color normalizes to null
    let create_blank = Request::builder()
        .method("POST")
        .uri("/api/v1/catalog/lists")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"title":"Blank Color","color":"   "}"#))
        .unwrap();
    let blank_res = app.clone().oneshot(create_blank).await.unwrap();
    assert_eq!(blank_res.status(), StatusCode::CREATED);
    let blank_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(blank_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert!(blank_body["color"].is_null());

    // Invalid colors return 400 Bad Request Problem Details
    for invalid in ["not-a-color", "javascript:alert(1)", "#12345"] {
        let create_bad = Request::builder()
            .method("POST")
            .uri("/api/v1/catalog/lists")
            .header(header::AUTHORIZATION, &auth)
            .header("content-type", "application/json")
            .body(Body::from(
                serde_json::json!({ "title": "Bad Color", "color": invalid }).to_string(),
            ))
            .unwrap();
        let bad_res = app.clone().oneshot(create_bad).await.unwrap();
        assert_eq!(bad_res.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            bad_res.headers().get(header::CONTENT_TYPE).unwrap(),
            "application/problem+json"
        );
    }
}

#[tokio::test]
async fn test_api_item_patch_respects_reader_auto_read_threshold() {
    let (app, auth) = setup_app().await;

    let save_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://example.com/threshold-test","title":"Threshold"}"#))
        .unwrap();
    let save_response = app.clone().oneshot(save_request).await.unwrap();
    assert_eq!(save_response.status(), StatusCode::CREATED);
    let save_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(save_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    let item_id = save_body["id"].as_str().unwrap();
    assert!(save_body["read_at"].is_null());

    let patch_done = Request::builder()
        .method("PATCH")
        .uri(format!("/api/v1/items/{item_id}"))
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(
            serde_json::json!({ "read_progress": crate::domain::READER_AUTO_READ_THRESHOLD })
                .to_string(),
        ))
        .unwrap();
    let done_res = app.clone().oneshot(patch_done).await.unwrap();
    assert_eq!(done_res.status(), StatusCode::OK);
    let done_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(done_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert!(done_body["read_at"].is_string());

    // Explicit read_at: null still overrides auto-read threshold
    let patch_unread = Request::builder()
        .method("PATCH")
        .uri(format!("/api/v1/items/{item_id}"))
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"read_progress":1.0,"read_at":null}"#))
        .unwrap();
    let unread_res = app.oneshot(patch_unread).await.unwrap();
    assert_eq!(unread_res.status(), StatusCode::OK);
    let unread_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(unread_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert!(unread_body["read_at"].is_null());
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_export_data_includes_legacy_and_unified_catalog_fields_backward_compatibly() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("export-test", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let app = ApiRoutes::router_with_service(service.clone());

    // Seed legacy feed + saved item + tag
    let feed_url = Url::parse("https://example.com/export-feed.xml").unwrap();
    let feed = db
        .feeds()
        .create(crate::db::FeedType::Rss, &feed_url, Some("Export Feed"), None)
        .await
        .unwrap();
    let item_url = Url::parse("https://example.com/export-article").unwrap();
    let (saved_item, _) = db
        .items()
        .save_manual_with_tags(&item_url, "Exported Post", &["rust".to_owned()])
        .await
        .unwrap();

    // Seed unified catalog entities: manual list, two entries, consumption log, comparison, generator
    let catalog = db.catalog();
    let canon = catalog
        .create_manual_collection(
            "Film Canon",
            Some("film_canon"),
            crate::db::CollectionRole::List,
            Some("Great films"),
            Some("#10B981"),
        )
        .await
        .unwrap();
    let m1 = catalog
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(crate::db::EntryKind::Movie),
            title: "Stalker".to_owned(),
            creators: vec!["Andrei Tarkovsky".to_owned()],
            release_year: Some(1979),
            parent_collection_id: Some(canon.id.clone()),
            parent_position: Some(1.0),
            ..Default::default()
        })
        .await
        .unwrap();
    let m2 = catalog
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(crate::db::EntryKind::Movie),
            title: "Mirror".to_owned(),
            creators: vec!["Andrei Tarkovsky".to_owned()],
            release_year: Some(1975),
            parent_collection_id: Some(canon.id.clone()),
            parent_position: Some(2.0),
            ..Default::default()
        })
        .await
        .unwrap();
    catalog
        .log_consumption(&m1.id, crate::db::ConsumptionStatus::Consumed, Some("Masterpiece"), None)
        .await
        .unwrap();
    catalog
        .append_comparison(None, &[vec![m1.id.clone()], vec![m2.id.clone()]], None)
        .await
        .unwrap();
    catalog.update_collection_generator(&canon.id, Some(true), Some(&m2.id), None).await.unwrap();

    // 1. Verify RocheService::export_data directly
    let direct_bundle = service.export_data().await.unwrap();
    assert_eq!(direct_bundle.version, "1.0");
    assert_eq!(direct_bundle.feeds.len(), 1);
    assert_eq!(direct_bundle.feeds[0].id, feed.id);
    assert!(direct_bundle.items.iter().any(|i| i.id == saved_item.id));
    assert!(direct_bundle.tags.iter().any(|t| t.name == "rust"));
    assert!(direct_bundle.entries.iter().any(|e| e.id == m1.id && e.release_year == Some(1979)));
    assert!(direct_bundle.collections.iter().any(|c| {
        c.id == canon.id && c.color.as_ref().map(crate::domain::HexColor::as_str) == Some("#10b981")
    }));
    assert!(
        direct_bundle
            .collection_entries
            .iter()
            .any(|ce| { ce.collection_id == canon.id && ce.entry_id == m1.id })
    );
    assert!(
        direct_bundle
            .consumption_log
            .iter()
            .any(|cl| { cl.entry_id == m1.id && cl.notes.as_deref() == Some("Masterpiece") })
    );
    assert_eq!(direct_bundle.comparisons.len(), 1);
    assert_eq!(direct_bundle.collection_generators.len(), 1);
    assert_eq!(direct_bundle.collection_generators[0].collection_id, canon.id);

    // 2. Verify GET /api/v1/export/data emits all legacy + unified catalog JSON keys and roundtrips
    let export_request = Request::builder()
        .uri("/api/v1/export/data")
        .header(header::AUTHORIZATION, &auth)
        .body(Body::empty())
        .unwrap();
    let export_response = app.oneshot(export_request).await.unwrap();
    assert_eq!(export_response.status(), StatusCode::OK);
    let bytes = to_bytes(export_response.into_body(), usize::MAX).await.unwrap();
    let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    for key in [
        "version",
        "exported_at",
        "feeds",
        "items",
        "tags",
        "item_tags",
        "activity",
        "entries",
        "collections",
        "collection_entries",
        "consumption_log",
        "comparisons",
        "collection_generators",
    ] {
        assert!(json.get(key).is_some(), "export JSON missing expected key '{key}'");
    }
    let roundtripped: crate::service::ExportBundle = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(roundtripped.comparisons.len(), 1);
    assert_eq!(roundtripped.collection_generators.len(), 1);

    // 3. Verify backward compatibility: legacy export JSON without catalog keys still deserializes
    let legacy_json = serde_json::json!({
        "version": "1.0",
        "exported_at": "2026-01-01T00:00:00Z",
        "feeds": [],
        "items": [],
        "tags": [],
        "item_tags": [],
        "activity": []
    });
    let legacy_bundle: crate::service::ExportBundle = serde_json::from_value(legacy_json).unwrap();
    assert_eq!(legacy_bundle.version, "1.0");
    assert_eq!(legacy_bundle.entries.len(), 0);
    assert_eq!(legacy_bundle.collections.len(), 0);
    assert_eq!(legacy_bundle.collection_entries.len(), 0);
    assert_eq!(legacy_bundle.consumption_log.len(), 0);
    assert_eq!(legacy_bundle.comparisons.len(), 0);
    assert_eq!(legacy_bundle.collection_generators.len(), 0);
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_sync_peer_binding_diary_crud_enrich_and_workspace_search() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let app = ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher);
    let token_a = TokenManager::new(db.auth()).create("Pixel A", 32).await.unwrap();
    let token_b = TokenManager::new(db.auth()).create("Pixel B", 32).await.unwrap();
    let auth_a = format!("Bearer {token_a}");
    let auth_b = format!("Bearer {token_b}");

    // 1. TOFU peer_id binding: token A binds to "android-a", cannot spoof "android-b" or "server",
    //    and token B cannot hijack "android-a".
    let sync_post = |auth: &str, peer_id: &str| {
        Request::builder()
            .method("POST")
            .uri("/api/v1/sync/peer")
            .header(header::AUTHORIZATION, auth)
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(serde_json::json!({ "peer_id": peer_id }).to_string()))
            .unwrap()
    };
    assert_eq!(
        app.clone().oneshot(sync_post(&auth_a, "android-a")).await.unwrap().status(),
        StatusCode::OK
    );
    assert_eq!(
        app.clone().oneshot(sync_post(&auth_a, "android-a")).await.unwrap().status(),
        StatusCode::OK
    );
    assert_eq!(
        app.clone().oneshot(sync_post(&auth_a, "android-b")).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.clone().oneshot(sync_post(&auth_b, "android-a")).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    assert_eq!(
        app.clone().oneshot(sync_post(&auth_b, "server")).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );

    // 2. Diary PATCH / DELETE, POST /entries/{id}/enrich, and GET /api/v1/search
    let entry = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(crate::db::EntryKind::Artist),
            title: "Broadcast".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let logged = db
        .catalog()
        .log_consumption(&entry.id, crate::db::ConsumptionStatus::Started, Some("Initial"), None)
        .await
        .unwrap();

    let enrich_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/catalog/entries/{}/enrich", entry.id))
                .header(header::AUTHORIZATION, &auth_a)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(enrich_resp.status(), StatusCode::OK);

    let patch_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/catalog/diary/{}", logged.id))
                .header(header::AUTHORIZATION, &auth_a)
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::json!({ "status": "consumed", "notes": "Updated note" })
                        .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(patch_resp.status(), StatusCode::OK);
    let patched_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(patch_resp.into_body(), 64 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(patched_json["status"], "consumed");
    assert_eq!(patched_json["notes"], "Updated note");

    let search_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/search?q=Broadcast")
                .header(header::AUTHORIZATION, &auth_a)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(search_resp.status(), StatusCode::OK);
    let search_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(search_resp.into_body(), 64 * 1024).await.unwrap())
            .unwrap();
    assert_eq!(search_json["catalog_entries"].as_array().unwrap().len(), 1);

    let del_resp = app
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri(format!("/api/v1/catalog/diary/{}", logged.id))
                .header(header::AUTHORIZATION, &auth_a)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(del_resp.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn test_concurrency_503_and_timeout_408_json_vs_html() {
    use axum::{Router, middleware, routing::get};

    let zero_permits = std::sync::Arc::new(tokio::sync::Semaphore::new(0));
    let busy_app = Router::new()
        .route("/api/v1/ping", get(|| async { "ok" }))
        .route("/home", get(|| async { "ok" }))
        .layer(middleware::from_fn_with_state(
            zero_permits,
            ApiRoutes::enforce_concurrency_and_timeout,
        ));

    let api_503 = busy_app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/ping")
                .header(header::ACCEPT, "application/json")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(api_503.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(api_503.headers().get(header::RETRY_AFTER).unwrap(), "5");
    assert_eq!(api_503.headers().get(header::CONTENT_TYPE).unwrap(), "application/problem+json");

    let html_503 = busy_app
        .oneshot(
            Request::builder()
                .uri("/home")
                .header(header::ACCEPT, "text/html")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(html_503.status(), StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(html_503.headers().get(header::RETRY_AFTER).unwrap(), "5");
    assert!(
        html_503
            .headers()
            .get(header::CONTENT_TYPE)
            .unwrap()
            .to_str()
            .unwrap()
            .starts_with("text/html")
    );

    // Media streaming is timeout-exempt only on GET/HEAD, never on DELETE
    assert!(
        ApiRoutes::request_timeout_for_path(&axum::http::Method::GET, "/api/v1/media/1").is_none()
    );
    assert!(
        ApiRoutes::request_timeout_for_path(&axum::http::Method::HEAD, "/api/v1/media/1").is_none()
    );
    assert!(
        ApiRoutes::request_timeout_for_path(&axum::http::Method::DELETE, "/api/v1/media/1")
            .is_some()
    );
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_api_feed_type_toggle_item_audio_filters_and_progress_source() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("decouple-api", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let app = ApiRoutes::router_with_service(service);

    let rss_url = Url::parse("https://api-decouple.example/rss.xml").unwrap();
    let rss_feed = db
        .feeds()
        .create(crate::db::FeedType::Rss, &rss_url, Some("Essay Feed"), None)
        .await
        .unwrap();
    let pod_url = Url::parse("https://api-decouple.example/pod.xml").unwrap();
    let pod_feed = db
        .feeds()
        .create(crate::db::FeedType::Podcast, &pod_url, Some("Podcast Feed"), None)
        .await
        .unwrap();
    let yt_url =
        Url::parse("https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA")
            .unwrap();
    let yt_feed = db
        .feeds()
        .create(crate::db::FeedType::Youtube, &yt_url, Some("YT Channel"), None)
        .await
        .unwrap();

    let narrated_url = Url::parse("https://api-decouple.example/narrated").unwrap();
    let narrated = db
        .items()
        .create(&crate::db::NewItem {
            feed_id: Some(&rss_feed.id),
            title: "Narrated API Post",
            url: &narrated_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: crate::domain::ContentType::Article,
        })
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO entry_media (id, entry_id, media_type, remote_url, duration)
         VALUES ('api-narrated-mp3', ?, 'audio', 'https://cdn.api-decouple.example/narrated.mp3', 600)",
    )
    .bind(&narrated.id)
    .execute(db.pool())
    .await
    .unwrap();

    let ep_url = Url::parse("https://api-decouple.example/ep1").unwrap();
    let ep = db
        .items()
        .create(&crate::db::NewItem {
            feed_id: Some(&pod_feed.id),
            title: "API Podcast Ep 1",
            url: &ep_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: crate::domain::ContentType::Audio,
        })
        .await
        .unwrap();
    sqlx::query(
        "INSERT INTO entry_media (id, entry_id, media_type, remote_url, duration)
         VALUES ('api-ep-mp3', ?, 'audio', 'https://cdn.api-decouple.example/ep1.mp3', 1800)",
    )
    .bind(&ep.id)
    .execute(db.pool())
    .await
    .unwrap();

    // 1. GET /api/v1/items?podcast_only=true returns ONLY explicit podcast episode
    let pod_res = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/items?podcast_only=true")
                .header(header::AUTHORIZATION, &auth)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(pod_res.status(), StatusCode::OK);
    let pod_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(pod_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    let pod_items = pod_json["items"].as_array().unwrap();
    assert_eq!(pod_items.len(), 1);
    assert_eq!(pod_items[0]["id"].as_str(), Some(ep.id.as_str()));

    // 2. GET /api/v1/items?has_audio=true returns BOTH podcast episode and narrated RSS article
    let audio_res = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/items?has_audio=true")
                .header(header::AUTHORIZATION, &auth)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(audio_res.status(), StatusCode::OK);
    let audio_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(audio_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(audio_json["items"].as_array().unwrap().len(), 2);

    // 3. PATCH /api/v1/items/{id}:
    //    - progress_source="reader" does NOT auto-finish a podcast episode (show notes scroll)
    //    - progress_source="reader" DOES auto-finish an article (>= 0.98)
    //    - progress_source="audio" DOES auto-finish podcast episodes & narrated articles
    let pod_scroll_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/items/{}", ep.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"read_progress":1.0,"progress_source":"reader"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(pod_scroll_res.status(), StatusCode::OK);
    assert_eq!(db.catalog().get_entry_detail(&ep.id).await.unwrap().unwrap().log_history.len(), 0);

    let pod_audio_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/items/{}", ep.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"read_progress":1.0,"progress_source":"audio"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(pod_audio_res.status(), StatusCode::OK);
    assert!(
        db.catalog()
            .get_entry_detail(&ep.id)
            .await
            .unwrap()
            .unwrap()
            .log_history
            .iter()
            .any(|l| l.status == crate::domain::ConsumptionStatus::Consumed)
    );

    let scroll_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/items/{}", narrated.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"read_progress":1.0,"progress_source":"reader"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(scroll_res.status(), StatusCode::OK);
    let after_scroll = db.catalog().get_entry_detail(&narrated.id).await.unwrap().unwrap();
    assert_eq!(after_scroll.entry.kind, crate::domain::EntryKind::Article);
    assert!(
        after_scroll
            .log_history
            .iter()
            .any(|l| l.status == crate::domain::ConsumptionStatus::Consumed)
    );

    // Dismissing a Finished item deletes its consumed log and moves it to Dismissed
    let dismiss_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/items/{}/dismiss", narrated.id))
                .header(header::AUTHORIZATION, &auth)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(dismiss_res.status(), StatusCode::OK);
    assert_eq!(
        db.catalog().get_entry_detail(&narrated.id).await.unwrap().unwrap().log_history.len(),
        0
    );

    let listen_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/items/{}", narrated.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"read_progress":1.0,"progress_source":"audio"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(listen_res.status(), StatusCode::OK);
    let after_listen = db.catalog().get_entry_detail(&narrated.id).await.unwrap().unwrap();
    assert_eq!(after_listen.entry.kind, crate::domain::EntryKind::Article);
    assert!(
        after_listen
            .log_history
            .iter()
            .any(|l| l.status == crate::domain::ConsumptionStatus::Consumed)
    );

    // 4. PATCH /api/v1/feeds/{id} toggles rss -> podcast and rejects youtube -> podcast
    let toggle_ok = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", rss_feed.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"feed_type":"podcast"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(toggle_ok.status(), StatusCode::OK);
    assert_eq!(
        db.items().get(&narrated.id).await.unwrap().unwrap().entry_kind,
        crate::domain::EntryKind::PodcastEpisode
    );

    let toggle_bad = app
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", yt_feed.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"feed_type":"podcast"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(toggle_bad.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn test_api_patch_feed_url_updates_validates_and_rejects_conflicts() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("feed-url-api", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let app = ApiRoutes::router_with_service(service);

    let url1 = Url::parse("https://alpha.example.com/rss.xml").unwrap();
    let url2 = Url::parse("https://beta.example.com/rss.xml").unwrap();
    let feed1 =
        db.feeds().create(crate::db::FeedType::Rss, &url1, Some("Alpha"), None).await.unwrap();
    let _feed2 =
        db.feeds().create(crate::db::FeedType::Rss, &url2, Some("Beta"), None).await.unwrap();

    // 1. Valid URL update preserves id and returns updated URL
    let ok_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", feed1.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"url":"https://alpha-moved.example.com/atom.xml"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(ok_res.status(), StatusCode::OK);
    let ok_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(ok_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(ok_json["id"], feed1.id);
    assert_eq!(ok_json["url"], "https://alpha-moved.example.com/atom.xml");

    // 2. Rejects duplicate URL owned by another feed (409 Conflict)
    let conflict_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", feed1.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"url":"https://beta.example.com/rss.xml"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(conflict_res.status(), StatusCode::CONFLICT);

    // 3. Rejects SSRF / private IP URL
    let ssrf_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", feed1.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"url":"http://127.0.0.1:8080/feed.xml"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(ssrf_res.status(), StatusCode::BAD_REQUEST);
    let ssrf_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(ssrf_res.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(ssrf_json["title"], "URL Target Blocked");

    // 4. Rejects incompatible source kind (arXiv URL on RSS feed) and non-feed YouTube watch URL
    let mismatch_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", feed1.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"url":"https://rss.arxiv.org/rss/cs.AI"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(mismatch_res.status(), StatusCode::BAD_REQUEST);

    let yt_url =
        Url::parse("https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA")
            .unwrap();
    let yt_feed = db
        .feeds()
        .create(crate::db::FeedType::Youtube, &yt_url, Some("YT Channel"), None)
        .await
        .unwrap();
    let yt_watch_res = app
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/feeds/{}", yt_feed.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(yt_watch_res.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_patch_catalog_entry_and_document_refresh_api() {
    struct TempDirGuard(std::path::PathBuf);
    impl Drop for TempDirGuard {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    let tmp_dir = std::env::temp_dir().join(format!("roche_api_doc_test_{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&tmp_dir).unwrap();
    let _guard = TempDirGuard(tmp_dir.clone());

    let mut cfg = crate::config::Config::default();
    cfg.storage.media_dir = tmp_dir.clone();

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("api-edit-test", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let service = RocheService::new(db.clone(), FakeFeedFetcher, cfg);
    let app = ApiRoutes::router_with_service(service.clone());

    let pdf_url =
        Url::parse("https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf").unwrap();
    let (paper, _) = db
        .items()
        .save_manual_input_with_tags(&crate::db::ManualItemInput {
            url: &pdf_url,
            title: "subnlogn.pdf",
            summary: None,
            content_html: None,
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: crate::domain::ContentType::Pdf,
            tags: &[],
        })
        .await
        .unwrap();

    let stream = "\
BT\n\
/F1 17.28 Tf\n\
100 720 Td (Integer multiplication in time O\\(n log n\\)) Tj\n\
/F1 12 Tf\n\
0 -28 Td (David Harvey and Joris van der Hoeven) Tj\n\
/F1 10 Tf\n\
0 -20 Td (October 20, 2026) Tj\n\
0 -28 Td (Abstract) Tj\n\
0 -14 Td (We prove that n-bit integers can be multiplied in O\\(n log n\\) bit operations.) Tj\n\
ET\n";
    let pdf_bytes = format!(
        "%PDF-1.5\n\
         1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n\
         2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n\
         3 0 obj << /Type /Page /Parent 2 0 R /Contents 4 0 R >> endobj\n\
         4 0 obj << /Length {} >>\nstream\n{}\nendstream\nendobj\n\
         5 0 obj << /Title () /Author () /Producer (pdfTeX) >> endobj\n\
         %%EOF\n",
        stream.len(),
        stream
    );
    let rel_path = format!("pdfs/{}.pdf", paper.id);
    let file_size =
        service.media_store().write_bytes(&rel_path, pdf_bytes.as_bytes()).await.unwrap();
    db.media()
        .create(&paper.id, crate::db::MediaType::Pdf, &rel_path, Some(file_size), None)
        .await
        .unwrap();

    // 1. POST /api/v1/catalog/entries/{id}/enrich discovers metadata from the cached PDF
    let enrich_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/catalog/entries/{}/enrich", paper.id))
                .header(header::AUTHORIZATION, &auth)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(enrich_res.status(), StatusCode::OK);
    let enrich_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(enrich_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(enrich_json["entry"]["title"], "Integer multiplication in time O(n log n)");
    assert_eq!(enrich_json["entry"]["release_year"], 2026);
    assert_eq!(
        enrich_json["entry"]["creators"],
        serde_json::json!(["David Harvey", "Joris van der Hoeven"])
    );

    // 2. PATCH /api/v1/catalog/entries/{id} edits metadata, harmonizes date, attaches DOI & clears summary
    let patch_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/catalog/entries/{}", paper.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(
                    r#"{
                        "title": "Integer multiplication in time O(n log n) — Final",
                        "creators": ["David Harvey", "Joris van der Hoeven"],
                        "published_date": "2021-03-15",
                        "summary": null,
                        "doi": "https://doi.org/10.4007/ANNALS.2021.193.2.4",
                        "isbn": "978-0-306-40615-7"
                    }"#,
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(patch_res.status(), StatusCode::OK);
    let patch_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(patch_res.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(patch_json["entry"]["title"], "Integer multiplication in time O(n log n) — Final");
    assert_eq!(patch_json["entry"]["release_year"], 2021);
    assert!(patch_json["entry"]["summary"].is_null());
    assert!(patch_json["entry"]["metadata"]["edited_hlc"].is_string());

    // 3. Rejects empty title (400 Bad Request)
    let bad_res = app
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri(format!("/api/v1/catalog/entries/{}", paper.id))
                .header(header::AUTHORIZATION, &auth)
                .header("content-type", "application/json")
                .body(Body::from(r#"{"title": "   "}"#))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bad_res.status(), StatusCode::BAD_REQUEST);
}
