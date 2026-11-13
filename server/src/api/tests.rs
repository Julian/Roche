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

use super::{
    ApiRoutes,
    media::{ByteRangeParse, parse_byte_range},
};
use crate::{
    auth::{pairing::PairingManager, tokens::TokenManager},
    db::{Db, ReadAtPatch},
    scheduler::FeedFetcher,
    service::RocheService,
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
    let not_found_request = Request::builder().uri("/does-not-exist").body(Body::empty()).unwrap();
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

    // Revoked token is now rejected with 401
    let post_revoke_request = Request::builder()
        .uri("/api/v1/feeds")
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .body(Body::empty())
        .unwrap();
    let post_revoke_response = app.oneshot(post_revoke_request).await.unwrap();
    assert_eq!(post_revoke_response.status(), StatusCode::UNAUTHORIZED);
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

    // 1. Save article without explicit title -> extracts <title>, meta summary, and sanitized HTML
    let article_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://example.com/deep-dive"}"#))
        .unwrap();
    let article_response = app.clone().oneshot(article_request).await.unwrap();
    assert_eq!(article_response.status(), StatusCode::CREATED);
    let article_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(article_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(article_body["title"], "Fetched Page Title");
    assert_eq!(article_body["summary"], "Extracted meta summary");
    assert_eq!(article_body["content_type"], "article");
    let content_html = article_body["content_html"].as_str().unwrap();
    assert!(content_html.contains("<p>Full article body.</p>"));
    assert!(!content_html.contains("<script>"));

    // 2. Save arXiv abstract URL -> classifies as "paper", downloads PDF, and attaches media
    let arxiv_request = Request::builder()
        .method("POST")
        .uri("/api/v1/items/save")
        .header(header::AUTHORIZATION, &auth)
        .header("content-type", "application/json")
        .body(Body::from(r#"{"url":"https://arxiv.org/abs/2301.07041","title":"Attention Paper"}"#))
        .unwrap();
    let arxiv_response = app.clone().oneshot(arxiv_request).await.unwrap();
    assert_eq!(arxiv_response.status(), StatusCode::CREATED);
    let arxiv_body: serde_json::Value =
        serde_json::from_slice(&to_bytes(arxiv_response.into_body(), usize::MAX).await.unwrap())
            .unwrap();
    assert_eq!(arxiv_body["content_type"], "paper");
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
        hostname: "roche".to_string(),
        dns_name: Some("roche.tail9876.ts.net".to_string()),
        magic_dns_suffix: Some("tail9876.ts.net".to_string()),
        ipv4: Some("100.64.0.12".to_string()),
        ipv6: None,
        service_name: Some("svc:roche".to_string()),
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
    assert_eq!(urls.len(), 4);
    assert_eq!(urls[0], "https://roche.tail9876.ts.net");
    assert_eq!(urls[1], "http://roche.tail9876.ts.net:8080");
    assert_eq!(urls[2], "http://roche:8080");
    assert_eq!(urls[3], "http://100.64.0.12:8080");
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
    assert_eq!(batch_archive_body["archived_count"], 2);
}

#[tokio::test]
async fn test_pairing_rate_limit_protects_active_code_from_rapid_burst() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let service = RocheService::new(db.clone(), FakeFeedFetcher, crate::config::Config::default());
    let app = ApiRoutes::router_with_service(service.clone());

    // Bogus attempts before any pairing code exists return 401 without pre-filling the rate limiter
    for i in 0..6 {
        let unissued_request = Request::builder()
            .method("POST")
            .uri("/auth/pair")
            .header("content-type", "application/json")
            .body(Body::from(format!(r#"{{"device_name":"pre-{i}","pairing_code":"0000-wrong"}}"#)))
            .unwrap();
        let unissued_response = app.clone().oneshot(unissued_request).await.unwrap();
        assert_eq!(unissued_response.status(), StatusCode::UNAUTHORIZED);
    }

    let valid_code = service.auth().issue_pairing_code().await.unwrap();

    // 4 rapid wrong attempts return 401 Unauthorized
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
    }

    // 5th rapid attempt is throttled with 429 Too Many Requests before touching the DB
    let throttled_request = Request::builder()
        .method("POST")
        .uri("/auth/pair")
        .header("content-type", "application/json")
        .body(Body::from(r#"{"device_name":"attacker-5","pairing_code":"0000-wrong"}"#))
        .unwrap();
    let throttled_response = app.clone().oneshot(throttled_request).await.unwrap();
    assert_eq!(throttled_response.status(), StatusCode::TOO_MANY_REQUESTS);
    let problem: serde_json::Value = serde_json::from_slice(
        &to_bytes(throttled_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(problem["type"], "urn:roche:problem:too-many-requests");

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

    let sync_payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 9",
        "network_tier": "wifi",
        "feeds": [{
            "url": "https://blog.example.com/rss.xml",
            "title": "Example Blog",
            "feed_type": "rss",
            "poll_interval": 30,
            "last_polled": "2026-06-01T12:00:00Z",
            "last_polled_by": "android-peer-1",
            "is_active": true
        }],
        "items": [{
            "feed_url": "https://blog.example.com/rss.xml",
            "url": "https://blog.example.com/post-1",
            "title": "Post 1 fetched on Android",
            "content_html": "<p>Full body from phone</p>",
            "summary": "Full body from phone",
            "content_type": "article",
            "status": "unread",
            "is_favorite": true,
            "read_progress": 0.9,
            "read_at": "2026-06-01T12:05:00Z",
            "interaction_updated_at": "2026-06-01T12:05:00Z",
            "tag_names": ["distributed"]
        }],
        "tags": [{
            "name": "distributed",
            "color": "#10b981"
        }],
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
    assert_eq!(body["feeds"].as_array().unwrap().len(), 1);
    assert_eq!(body["items"].as_array().unwrap().len(), 1);
    assert_eq!(body["items"][0]["status"], "unread");
    assert_eq!(body["items"][0]["read_at"], "2026-06-01T12:05:00Z");
    assert_eq!(body["items"][0]["is_favorite"], true);
    assert_eq!(body["tags"].as_array().unwrap().len(), 1);
    assert_eq!(body["tags"][0]["color"], "#10b981");
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
    let all_feeds = db.feeds().list().await.unwrap();
    assert_eq!(all_feeds.len(), 1);
    let all_items = db.items().list(None, None, Some(50)).await.unwrap();
    assert_eq!(all_items.len(), 1);

    // Skip non-public SSRF feed URLs in peer sync without failing the sync request
    let bad_payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 9",
        "network_tier": "wifi",
        "feeds": [{
            "url": "http://127.0.0.1:8080/private.xml",
            "title": "Internal",
            "feed_type": "rss"
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

    // Peer sync should propagate feed archival (is_active = false) and unarchival without losing reading history
    let archive_sync_payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 9",
        "network_tier": "wifi",
        "feeds": [{
            "url": "https://blog.example.com/rss.xml",
            "title": "Example Blog",
            "feed_type": "rss",
            "poll_interval": 30,
            "last_polled": "2026-06-01T12:00:00Z",
            "last_polled_by": "android-peer-1",
            "is_active": false
        }],
        "items": [],
        "tags": [],
        "tombstones": []
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
    let archive_sync_body: serde_json::Value = serde_json::from_slice(
        &to_bytes(archive_sync_response.into_body(), usize::MAX).await.unwrap(),
    )
    .unwrap();
    assert_eq!(archive_sync_body["feeds"][0]["is_active"], false);
    let feed_id = archive_sync_body["feeds"][0]["id"].as_str().unwrap().to_string();

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

    // Reading history on items remains untouched
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
    let items: Vec<serde_json::Value> = (0..60)
        .map(|idx| {
            serde_json::json!({
                "url": format!("https://example.com/large-sync-item-{idx}"),
                "title": format!("Large Item {idx}"),
                "content_html": html_chunk,
                "content_type": "article",
                "status": "unread"
            })
        })
        .collect();
    let large_sync_json = serde_json::json!({
        "peer_id": "android-large-sync",
        "device_name": "Pixel 10",
        "network_tier": "wifi",
        "items": items
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
async fn test_peer_sync_skips_lan_feeds_and_accepts_non_standard_port_items() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let token = TokenManager::new(db.auth()).create("test-suite", 32).await.unwrap();
    let auth = format!("Bearer {token}");
    let app = ApiRoutes::router_with_fetcher(&db, FakeFeedFetcher);

    let payload = serde_json::json!({
        "peer_id": "android-peer-1",
        "device_name": "Pixel 10",
        "network_tier": "wifi",
        "feeds": [
            {
                "url": "http://192.168.1.50/rss.xml",
                "title": "Home Router Feed",
                "feed_type": "rss"
            },
            {
                "url": "https://valid.example.com/rss.xml",
                "title": "Valid Public Feed",
                "feed_type": "rss"
            }
        ],
        "items": [
            {
                "url": "https://example.com:8443/demo",
                "title": "Port 8443 Demo",
                "content_type": "article",
                "status": "unread"
            },
            {
                "url": "https://intranet.tailnet.ts.net:9443/internal-note",
                "title": "Tailnet Non-Standard Port Item",
                "content_type": "article",
                "status": "unread"
            },
            {
                "feed_url": "https://valid.example.com/rss.xml",
                "url": "https://valid.example.com:8443/post-1",
                "title": "Valid Feed Item With Port",
                "content_type": "article",
                "status": "unread"
            },
            {
                "url": "https://user:secret@example.com/disallowed-creds",
                "title": "Credentials In Permalink",
                "content_type": "article",
                "status": "unread"
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

    let feeds = body["feeds"].as_array().unwrap();
    assert_eq!(feeds.len(), 1);
    assert_eq!(feeds[0]["url"], "https://valid.example.com/rss.xml");

    let items = body["items"].as_array().unwrap();
    assert_eq!(items.len(), 3);
    let synced_urls: Vec<&str> = items.iter().map(|i| i["url"].as_str().unwrap()).collect();
    assert!(synced_urls.contains(&"https://example.com:8443/demo"));
    assert!(synced_urls.contains(&"https://intranet.tailnet.ts.net:9443/internal-note"));
    assert!(synced_urls.contains(&"https://valid.example.com:8443/post-1"));
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
