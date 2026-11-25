#![allow(clippy::unwrap_used, clippy::too_many_lines)]

use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    time::{Duration, Instant},
};

use axum::{
    Router,
    body::{Body, to_bytes},
    extract::ConnectInfo,
    http::{HeaderMap, Request, StatusCode, header},
    response::Response,
};
use tower::ServiceExt as _;
use url::Url;

use crate::{
    auth::pairing::PairingManager,
    config::Config,
    db::{CatalogEntry, Db, EntryIdentifier, NewItem},
    domain::{ContentType, EntryKind, FeedType},
    service::{
        RocheService,
        enrich::{
            ArtistEnricher, EnrichAttemptCache, MAX_CONCURRENT_ENRICH_TASKS, WikipediaSearchHit,
            decode_html_entities, encode_wiki_path_segment,
            extract_spotify_artist_uri_from_album_html, parse_itunes_artist_discography,
            parse_itunes_lookup_tracks, parse_spotify_embed_tracks,
            parse_wikipedia_artist_summary_json, select_best_artist_wikipedia_slug,
        },
    },
    web::{
        auth::{
            CSRF_HEADER_NAME, is_same_origin_post, redirect_see_other, safe_local_redirect,
            safe_login_return_to,
        },
        links::{WebLinkIcon, collect_web_external_links, sanitize_non_local_href},
    },
};

struct DummyWebFetcher;

impl crate::ingest::FeedFetcher for DummyWebFetcher {
    fn fetch_feed(&self, _url: &Url) -> impl std::future::Future<Output = anyhow::Result<String>> {
        std::future::ready(Err(anyhow::anyhow!("no network in tests")))
    }

    fn fetch_html(
        &self,
        _url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> {
        std::future::ready(Ok(None))
    }
}

async fn setup_web_app(cfg: Config) -> (Db, Router) {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let service = RocheService::new(db.clone(), DummyWebFetcher, cfg);
    let app = crate::api::ApiRoutes::router_with_service(service);
    (db, app)
}

fn extract_cookie_header(set_cookie: &str) -> String {
    set_cookie.split(';').next().unwrap_or("").trim().to_owned()
}

fn find_set_cookie<'a>(resp: &'a Response, prefix: &str) -> Option<&'a str> {
    resp.headers()
        .get_all(header::SET_COOKIE)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .find(|v| v.starts_with(prefix))
}

async fn read_body_text(resp: Response) -> String {
    String::from_utf8(to_bytes(resp.into_body(), 1024 * 1024).await.unwrap().to_vec()).unwrap()
}

async fn read_body_json(resp: Response) -> serde_json::Value {
    serde_json::from_slice(&to_bytes(resp.into_body(), 1024 * 1024).await.unwrap()).unwrap()
}

async fn get_authed(app: &Router, uri: &str, cookie: &str) -> Response {
    app.clone()
        .oneshot(
            Request::builder().uri(uri).header(header::COOKIE, cookie).body(Body::empty()).unwrap(),
        )
        .await
        .unwrap()
}

async fn post_json_authed(
    app: &Router,
    uri: &str,
    cookie: &str,
    csrf: Option<&str>,
    body: serde_json::Value,
) -> Response {
    let mut builder = Request::builder()
        .method("POST")
        .uri(uri)
        .header(header::COOKIE, cookie)
        .header(header::CONTENT_TYPE, "application/json")
        .header("sec-fetch-site", "same-origin");
    if let Some(tok) = csrf {
        builder = builder.header(CSRF_HEADER_NAME, tok);
    }
    app.clone().oneshot(builder.body(Body::from(body.to_string())).unwrap()).await.unwrap()
}

async fn login_browser(db: &Db, app: &Router) -> (String, String) {
    let code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
    let mut login_req = Request::builder()
        .method("POST")
        .uri("/api/v1/auth/login")
        .header(header::CONTENT_TYPE, "application/json")
        .header("sec-fetch-site", "same-origin")
        .header("x-forwarded-proto", "https")
        .body(Body::from(serde_json::json!({ "pairing_code": code }).to_string()))
        .unwrap();
    login_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 41000)));
    let login_resp = app.clone().oneshot(login_req).await.unwrap();
    assert_eq!(login_resp.status(), StatusCode::OK);
    let set_cookie = find_set_cookie(&login_resp, "__Host-roche_session=")
        .expect("missing __Host-roche_session Set-Cookie")
        .to_owned();
    assert!(set_cookie.contains("HttpOnly"));
    assert!(set_cookie.contains("SameSite=Lax"));
    assert!(set_cookie.contains("Secure"));
    let cookie = extract_cookie_header(&set_cookie);
    let payload = read_body_json(login_resp).await;
    assert_eq!(payload["authenticated"], true);
    let csrf = payload["csrf_token"].as_str().unwrap().to_owned();
    assert_eq!(csrf.len(), 128);
    (cookie, csrf)
}

#[tokio::test]
async fn test_spa_assets_etag_immutable_cache_and_login_csrf_logout() {
    let (db, app) = setup_web_app(Config::default()).await;

    // 1. Embedded hashed Vite CSS & JS assets serve with weak ETag, immutable cache, and 304 support
    let css_path = super::spa_assets::MAIN_CSS_HREF.expect("expected embedded Vite CSS bundle");
    let js_path = super::spa_assets::EMBEDDED_ASSETS
        .iter()
        .find(|a| {
            a.path.starts_with("/assets/")
                && std::path::Path::new(a.path)
                    .extension()
                    .is_some_and(|ext| ext.eq_ignore_ascii_case("js"))
        })
        .expect("expected embedded Vite JS bundle")
        .path;

    let css_resp = app
        .clone()
        .oneshot(Request::builder().uri(css_path).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(css_resp.status(), StatusCode::OK);
    assert_eq!(css_resp.headers().get(header::X_CONTENT_TYPE_OPTIONS).unwrap(), "nosniff");
    assert_eq!(css_resp.headers().get(header::X_FRAME_OPTIONS).unwrap(), "SAMEORIGIN");
    assert_eq!(css_resp.headers().get(header::REFERRER_POLICY).unwrap(), "same-origin");
    assert_eq!(
        css_resp.headers().get(header::CACHE_CONTROL).unwrap(),
        "public, max-age=31536000, immutable"
    );
    let css_etag = css_resp.headers().get(header::ETAG).unwrap().to_str().unwrap().to_owned();
    assert!(css_etag.starts_with("W/\""), "expected weak ETag under compression, got {css_etag}");
    assert_eq!(css_resp.headers().get(header::VARY).unwrap(), "Accept-Encoding");
    let css_body = read_body_text(css_resp).await;
    assert!(css_body.contains("#fbf7ed"));

    for inm in [css_etag.as_str(), css_etag.trim_start_matches("W/")] {
        let css_304 = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(css_path)
                    .header(header::IF_NONE_MATCH, inm)
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(css_304.status(), StatusCode::NOT_MODIFIED);
        assert_eq!(css_304.headers().get(header::VARY).unwrap(), "Accept-Encoding");
    }

    let js_resp = app
        .clone()
        .oneshot(Request::builder().uri(js_path).body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(js_resp.status(), StatusCode::OK);
    assert_eq!(
        js_resp.headers().get(header::CACHE_CONTROL).unwrap(),
        "public, max-age=31536000, immutable"
    );

    let fav_resp = app
        .clone()
        .oneshot(Request::builder().uri("/favicon.svg").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(fav_resp.status(), StatusCode::OK);
    assert_eq!(fav_resp.headers().get(header::CONTENT_TYPE).unwrap(), "image/svg+xml");
    assert_eq!(fav_resp.headers().get(header::CACHE_CONTROL).unwrap(), "public, max-age=3600");

    // 2. Unauthenticated login shell serves strict CSP with zero inline scripts/styles + FOUC-free theme
    let login_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/login")
                .header(header::COOKIE, "roche_theme=dark")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login_resp.status(), StatusCode::OK);
    let csp = login_resp.headers().get(header::CONTENT_SECURITY_POLICY).unwrap().to_str().unwrap();
    assert!(csp.contains("script-src 'self'"));
    assert!(csp.contains("style-src 'self'"));
    assert!(!csp.contains("'unsafe-inline'"));
    let login_html = read_body_text(login_resp).await;
    assert!(login_html.contains("<html lang=\"en\" data-theme=\"dark\">"));
    assert!(login_html.contains("<div id=\"app\"></div>"));
    assert!(!login_html.contains("style=\""));

    // 3. Unauthenticated protected routes redirect to /login with safe return_to
    let unauth = app
        .clone()
        .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(unauth.status(), StatusCode::SEE_OTHER);
    assert_eq!(unauth.headers().get(header::LOCATION).unwrap(), "/login");

    let unauth_inbox = app
        .clone()
        .oneshot(Request::builder().uri("/inbox?q=rust").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(unauth_inbox.status(), StatusCode::SEE_OTHER);
    assert_eq!(
        unauth_inbox.headers().get(header::LOCATION).unwrap(),
        "/login?return_to=%2Finbox%3Fq%3Drust"
    );

    // 4. Authenticated SPA shell routes & canonical redirects
    let (cookie, csrf) = login_browser(&db, &app).await;
    for path in [
        "/",
        "/inbox",
        "/boards",
        "/lists/starred",
        "/feeds",
        "/lists",
        "/diary",
        "/queue",
        "/search",
    ] {
        let resp = get_authed(&app, path, &cookie).await;
        assert_eq!(resp.status(), StatusCode::OK, "expected 200 OK on {path}");
        let html = read_body_text(resp).await;
        assert!(html.contains("<div id=\"app\"></div>"), "missing SPA root on {path}");
    }
    let starred_redir = get_authed(&app, "/starred", &cookie).await;
    assert_eq!(starred_redir.status(), StatusCode::SEE_OTHER);
    assert_eq!(starred_redir.headers().get(header::LOCATION).unwrap(), "/lists/starred");

    let rank_redir = get_authed(&app, "/rank", &cookie).await;
    assert_eq!(rank_redir.status(), StatusCode::SEE_OTHER);
    assert_eq!(rank_redir.headers().get(header::LOCATION).unwrap(), "/lists");

    // 5. CSRF enforcement on mutating /api/v1/* requests with browser cookies
    let missing_csrf = post_json_authed(
        &app,
        "/api/v1/catalog/lists",
        &cookie,
        None,
        serde_json::json!({"title": "No CSRF"}),
    )
    .await;
    assert_eq!(missing_csrf.status(), StatusCode::FORBIDDEN);

    let bad_csrf = post_json_authed(
        &app,
        "/api/v1/catalog/lists",
        &cookie,
        Some("deadbeef"),
        serde_json::json!({"title": "Bad CSRF"}),
    )
    .await;
    assert_eq!(bad_csrf.status(), StatusCode::FORBIDDEN);

    let cross_site = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/catalog/lists")
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "application/json")
                .header(CSRF_HEADER_NAME, &csrf)
                .header("sec-fetch-site", "cross-site")
                .body(Body::from(serde_json::json!({"title": "Cross Site"}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(cross_site.status(), StatusCode::FORBIDDEN);

    let valid_create = post_json_authed(
        &app,
        "/api/v1/catalog/lists",
        &cookie,
        Some(&csrf),
        serde_json::json!({"title": "Valid SPA List"}),
    )
    .await;
    assert_eq!(valid_create.status(), StatusCode::CREATED);

    // 6. Logout revokes session and sets logged_out cookie
    let logout_resp =
        post_json_authed(&app, "/api/v1/auth/logout", &cookie, Some(&csrf), serde_json::json!({}))
            .await;
    assert_eq!(logout_resp.status(), StatusCode::NO_CONTENT);
    assert!(find_set_cookie(&logout_resp, "roche_logged_out=1").is_some());

    let after_logout_page = get_authed(&app, "/", &cookie).await;
    assert_eq!(after_logout_page.status(), StatusCode::SEE_OTHER);
    assert_eq!(after_logout_page.headers().get(header::LOCATION).unwrap(), "/login");

    let after_logout_api = get_authed(&app, "/api/v1/items", &cookie).await;
    assert_eq!(after_logout_api.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn test_dual_auth_isolation_precedence_peer_sync_expiry_and_masked_csrf() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (web_cookie, csrf_first) = login_browser(&db, &app).await;

    // 1. Browser login mints a `web_sessions` row, NEVER a `device_tokens` row
    assert_eq!(db.auth().count_web_sessions().await.unwrap(), 1);
    assert_eq!(db.auth().count_devices().await.unwrap(), 0);

    // 2. Web session raw token CANNOT authenticate as Authorization: Bearer
    let web_raw_token = web_cookie.split_once('=').unwrap().1;
    let api_with_web_bearer = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/items")
                .header(header::AUTHORIZATION, format!("Bearer {web_raw_token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(api_with_web_bearer.status(), StatusCode::UNAUTHORIZED);

    // 3. Strict precedence: invalid Authorization header alongside valid browser cookie MUST fail (never fall back)
    let bad_bearer_with_cookie = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/api/v1/items")
                .header(header::AUTHORIZATION, "Bearer invalid-device-token")
                .header(header::COOKIE, &web_cookie)
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bad_bearer_with_cookie.status(), StatusCode::UNAUTHORIZED);

    // 4. Browser session cookie + valid CSRF is FORBIDDEN from calling peer-to-peer sync
    let peer_sync_cookie = post_json_authed(
        &app,
        "/api/v1/sync/peer",
        &web_cookie,
        Some(&csrf_first),
        serde_json::json!({"peer_id": "browser", "since_seq": 0}),
    )
    .await;
    assert_eq!(peer_sync_cookie.status(), StatusCode::FORBIDDEN);

    // 5. Device token CANNOT authenticate browser routes or browser logout
    let pair_code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
    let pair_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/pair")
                .header(header::CONTENT_TYPE, "application/json")
                .body(Body::from(
                    serde_json::json!({
                        "device_name": "Android Phone",
                        "pairing_code": pair_code,
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(pair_resp.status(), StatusCode::OK);
    let pair_json = read_body_json(pair_resp).await;
    let api_token = pair_json["token"].as_str().unwrap();

    let web_with_api_token = get_authed(&app, "/", &format!("roche_session={api_token}")).await;
    assert_eq!(web_with_api_token.status(), StatusCode::SEE_OTHER);
    assert_eq!(web_with_api_token.headers().get(header::LOCATION).unwrap(), "/login");

    let device_logout = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/logout")
                .header(header::AUTHORIZATION, format!("Bearer {api_token}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(device_logout.status(), StatusCode::FORBIDDEN);

    // 6. Per-request masked CSRF tokens differ across GET /api/v1/auth/session and BOTH verify
    let session2_resp = get_authed(&app, "/api/v1/auth/session", &web_cookie).await;
    assert_eq!(session2_resp.status(), StatusCode::OK);
    let session2 = read_body_json(session2_resp).await;
    assert_eq!(session2["authenticated"], true);
    let csrf_second = session2["csrf_token"].as_str().unwrap().to_owned();
    assert_eq!(csrf_second.len(), 128);
    assert_ne!(csrf_first, csrf_second, "CSRF token must be freshly masked per request");

    let post_first = post_json_authed(
        &app,
        "/api/v1/catalog/lists",
        &web_cookie,
        Some(&csrf_first),
        serde_json::json!({"title": "List One"}),
    )
    .await;
    assert_eq!(post_first.status(), StatusCode::CREATED);
    let post_second = post_json_authed(
        &app,
        "/api/v1/catalog/lists",
        &web_cookie,
        Some(&csrf_second),
        serde_json::json!({"title": "List Two"}),
    )
    .await;
    assert_eq!(post_second.status(), StatusCode::CREATED);

    // 7. Expired web sessions are rejected immediately and pruned by WorkerManager
    sqlx::query(
        "UPDATE web_sessions SET expires_at = strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-1 minute')",
    )
    .execute(db.pool())
    .await
    .unwrap();
    let expired_get = get_authed(&app, "/", &web_cookie).await;
    assert_eq!(expired_get.status(), StatusCode::SEE_OTHER);
    let expired_api = get_authed(&app, "/api/v1/items", &web_cookie).await;
    assert_eq!(expired_api.status(), StatusCode::UNAUTHORIZED);

    let workers =
        crate::workers::WorkerManager::new(db.clone(), 30, "/tmp/roche-test-session-prune");
    workers.run_once().await.unwrap();
    assert_eq!(db.auth().count_web_sessions().await.unwrap(), 0);
}

#[tokio::test]
async fn test_tailscale_auto_auth_strictly_requires_loopback_navigation_and_allowlist() {
    let mut cfg = Config::default();
    cfg.tailscale.enabled = true;
    cfg.tailscale.auto_auth_users = vec!["reader@example.ts.net".to_owned()];
    let (_db, app) = setup_web_app(cfg).await;

    // Non-loopback peer is rejected
    let mut remote_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Site", "none")
        .body(Body::empty())
        .unwrap();
    remote_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(100, 64, 0, 9)), 51234)));
    let remote_resp = app.clone().oneshot(remote_req).await.unwrap();
    assert_eq!(remote_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(remote_resp.headers().get(header::LOCATION).unwrap(), "/login");

    // Non-allowlisted loopback user is rejected
    let mut stranger_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "intruder@example.ts.net")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Site", "none")
        .body(Body::empty())
        .unwrap();
    stranger_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51235)));
    assert_eq!(app.clone().oneshot(stranger_req).await.unwrap().status(), StatusCode::SEE_OTHER);

    // Passive GET /api/v1/auth/session does NOT auto-mint a cookie (reports tailscale_user for explicit button)
    let mut session_probe = Request::builder()
        .uri("/api/v1/auth/session")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .body(Body::empty())
        .unwrap();
    session_probe
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51236)));
    let probe_resp = app.clone().oneshot(session_probe).await.unwrap();
    assert_eq!(probe_resp.status(), StatusCode::OK);
    assert!(probe_resp.headers().get(header::SET_COOKIE).is_none());
    let probe_json = read_body_json(probe_resp).await;
    assert_eq!(probe_json["authenticated"], false);
    assert_eq!(probe_json["tailscale_user"], "reader@example.ts.net");

    // Forged state-changing API POST cannot ride Tailscale header
    let mut forged_post = Request::builder()
        .method("POST")
        .uri("/api/v1/catalog/lists")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(serde_json::json!({"title": "Forged"}).to_string()))
        .unwrap();
    forged_post
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51237)));
    assert_eq!(app.clone().oneshot(forged_post).await.unwrap().status(), StatusCode::UNAUTHORIZED);

    // Valid top-level navigational GET mints a session cookie
    let mut valid_ts_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "READER@example.ts.net")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Site", "none")
        .body(Body::empty())
        .unwrap();
    valid_ts_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51238)));
    let valid_ts_resp = app.clone().oneshot(valid_ts_req).await.unwrap();
    assert_eq!(valid_ts_resp.status(), StatusCode::OK);
    assert!(find_set_cookie(&valid_ts_resp, "roche_session=").is_some());

    // Explicit roche_logged_out=1 suppresses passive auto-login; explicit POST /api/v1/auth/login/tailscale re-authenticates
    let mut logged_out_nav = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header(header::COOKIE, "roche_logged_out=1")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Site", "none")
        .body(Body::empty())
        .unwrap();
    logged_out_nav
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51239)));
    let logged_out_resp = app.clone().oneshot(logged_out_nav).await.unwrap();
    assert_eq!(logged_out_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(logged_out_resp.headers().get(header::LOCATION).unwrap(), "/login");

    let mut ts_explicit = Request::builder()
        .method("POST")
        .uri("/api/v1/auth/login/tailscale")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header("sec-fetch-site", "same-origin")
        .header(header::COOKIE, "roche_logged_out=1")
        .body(Body::empty())
        .unwrap();
    ts_explicit
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51240)));
    let ts_explicit_resp = app.clone().oneshot(ts_explicit).await.unwrap();
    assert_eq!(ts_explicit_resp.status(), StatusCode::OK);
    assert!(find_set_cookie(&ts_explicit_resp, "roche_session=").is_some());
    assert!(
        find_set_cookie(&ts_explicit_resp, "roche_logged_out=;").unwrap().contains("Max-Age=0")
    );
}

#[tokio::test]
async fn test_tailscale_auto_auth_blocks_subresource_dup_headers_and_accepts_mapped_ipv6() {
    let mut cfg = Config::default();
    cfg.tailscale.enabled = true;
    cfg.tailscale.auto_auth_users = vec!["reader@example.ts.net".to_owned()];
    let (_db, app) = setup_web_app(cfg).await;

    // Cross-site subresource GET must NOT mint a session
    let mut cross_site_img = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header("Sec-Fetch-Mode", "no-cors")
        .header("Sec-Fetch-Dest", "image")
        .header("Sec-Fetch-Site", "cross-site")
        .body(Body::empty())
        .unwrap();
    cross_site_img
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51241)));
    let cross_site_resp = app.clone().oneshot(cross_site_img).await.unwrap();
    assert_eq!(cross_site_resp.status(), StatusCode::SEE_OTHER);
    assert!(cross_site_resp.headers().get(header::SET_COOKIE).is_none());

    // Duplicate Tailscale-User-Login headers are rejected
    let mut dup_ts_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header("Tailscale-User-Login", "other@example.ts.net")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Site", "none")
        .body(Body::empty())
        .unwrap();
    dup_ts_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51242)));
    assert_eq!(app.clone().oneshot(dup_ts_req).await.unwrap().status(), StatusCode::SEE_OTHER);

    // IPv4-mapped IPv6 loopback (::ffff:127.0.0.1) is recognized as loopback on dual-stack hosts
    let mut mapped_v6_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header("Sec-Fetch-Mode", "navigate")
        .header("Sec-Fetch-Dest", "document")
        .header("Sec-Fetch-Site", "none")
        .header("X-Forwarded-Proto", "http, https")
        .body(Body::empty())
        .unwrap();
    mapped_v6_req.extensions_mut().insert(ConnectInfo(SocketAddr::new(
        IpAddr::V6(Ipv4Addr::LOCALHOST.to_ipv6_mapped()),
        51243,
    )));
    let mapped_v6_resp = app.clone().oneshot(mapped_v6_req).await.unwrap();
    assert_eq!(mapped_v6_resp.status(), StatusCode::OK);
    let set_cookie_str =
        mapped_v6_resp.headers().get(header::SET_COOKIE).unwrap().to_str().unwrap();
    assert!(set_cookie_str.starts_with("__Host-roche_session="), "got: {set_cookie_str}");
    assert!(set_cookie_str.contains("; Secure"));
    assert_eq!(
        mapped_v6_resp.headers().get(header::STRICT_TRANSPORT_SECURITY).unwrap(),
        "max-age=31536000"
    );
}

#[tokio::test]
async fn test_security_headers_breach_and_host_cookie_enforcement() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (host_cookie, _csrf) = login_browser(&db, &app).await;
    let raw_token = host_cookie.split_once('=').unwrap().1;

    // 1. HTML responses are never compressed (BREACH mitigation) and carry Permissions-Policy + HSTS over forwarded HTTPS
    let mut https_home_req = Request::builder()
        .uri("/")
        .header(header::COOKIE, &host_cookie)
        .header(header::ACCEPT_ENCODING, "gzip, br, deflate")
        .header("x-forwarded-proto", "https")
        .body(Body::empty())
        .unwrap();
    https_home_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 42001)));
    let https_home = app.clone().oneshot(https_home_req).await.unwrap();
    assert_eq!(https_home.status(), StatusCode::OK);
    assert!(
        https_home.headers().get(header::CONTENT_ENCODING).is_none(),
        "text/html must not be compressed"
    );
    assert_eq!(
        https_home.headers().get("permissions-policy").unwrap(),
        "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
    );
    assert_eq!(
        https_home.headers().get(header::STRICT_TRANSPORT_SECURITY).unwrap(),
        "max-age=31536000"
    );

    // 2. Over forwarded HTTPS, unprefixed `roche_session` cookie is rejected (blocks HTTP cookie-tossing)
    for (uri, expected_status) in
        [("/", StatusCode::SEE_OTHER), ("/api/v1/items", StatusCode::UNAUTHORIZED)]
    {
        let mut tossed_req = Request::builder()
            .uri(uri)
            .header(header::COOKIE, format!("roche_session={raw_token}"))
            .header("x-forwarded-proto", "https")
            .body(Body::empty())
            .unwrap();
        tossed_req
            .extensions_mut()
            .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 42002)));
        let tossed_resp = app.clone().oneshot(tossed_req).await.unwrap();
        assert_eq!(tossed_resp.status(), expected_status, "cookie tossing failed on {uri}");
    }

    // 3. Direct non-loopback peer spoofing X-Forwarded-Proto: https does NOT receive HSTS
    let mut spoofed_https_req = Request::builder()
        .uri("/health")
        .header("x-forwarded-proto", "https")
        .body(Body::empty())
        .unwrap();
    spoofed_https_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 55)), 42003)));
    let spoofed_health = app.clone().oneshot(spoofed_https_req).await.unwrap();
    assert_eq!(spoofed_health.status(), StatusCode::OK);
    assert_eq!(spoofed_health.headers().get(header::X_CONTENT_TYPE_OPTIONS).unwrap(), "nosniff");
    assert_eq!(spoofed_health.headers().get(header::CACHE_CONTROL).unwrap(), "no-store");
    assert!(spoofed_health.headers().get(header::STRICT_TRANSPORT_SECURITY).is_none());

    // 4. Browser HTML 404 fallback renders escaped HTML with full security headers and zero inline CSS/JS
    let not_found_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .uri("/does-not-exist-route")
                .header(header::ACCEPT, "text/html")
                .header(header::COOKIE, "roche_theme=dark")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(not_found_resp.status(), StatusCode::NOT_FOUND);
    assert!(not_found_resp.headers().get(header::CONTENT_SECURITY_POLICY).is_some());
    let nf_html = read_body_text(not_found_resp).await;
    assert!(nf_html.contains("data-theme=\"dark\""));
    assert!(nf_html.contains("Not Found"));
    assert!(!nf_html.contains("<style"));
    assert!(!nf_html.contains("<script"));
}

#[tokio::test]
async fn test_mixed_https_http_localhost_cookie_precedence_and_cleanup() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (dead_host_cookie, csrf1) = login_browser(&db, &app).await;

    // 1. HTTP logout while carrying __Host-roche_session and legacy __Host-roche_flash clears BOTH
    let logout_resp = post_json_authed(
        &app,
        "/api/v1/auth/logout",
        &format!("{dead_host_cookie}; __Host-roche_flash=n:Leftover"),
        Some(&csrf1),
        serde_json::json!({}),
    )
    .await;
    assert_eq!(logout_resp.status(), StatusCode::NO_CONTENT);
    assert!(find_set_cookie(&logout_resp, "__Host-roche_session=;").unwrap().contains("Max-Age=0"));
    assert!(find_set_cookie(&logout_resp, "roche_session=;").unwrap().contains("Max-Age=0"));
    assert!(find_set_cookie(&logout_resp, "__Host-roche_flash=;").unwrap().contains("Max-Age=0"));
    assert!(find_set_cookie(&logout_resp, "roche_flash=;").unwrap().contains("Max-Age=0"));

    // 2. HTTP login while browser still sends stale __Host-roche_session and __Host-roche_logged_out=1
    //    clears both stale __Host-* cookies and mints a fresh unprefixed roche_session
    let code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
    let http_login_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/auth/login")
                .header(header::CONTENT_TYPE, "application/json")
                .header("sec-fetch-site", "same-origin")
                .header(header::COOKIE, format!("{dead_host_cookie}; __Host-roche_logged_out=1"))
                .body(Body::from(serde_json::json!({"pairing_code": code}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(http_login_resp.status(), StatusCode::OK);
    assert!(
        find_set_cookie(&http_login_resp, "__Host-roche_session=;").unwrap().contains("Max-Age=0")
    );
    assert!(
        find_set_cookie(&http_login_resp, "__Host-roche_logged_out=;")
            .unwrap()
            .contains("Max-Age=0")
    );
    let fresh_plain_cookie =
        extract_cookie_header(find_set_cookie(&http_login_resp, "roche_session=").unwrap());

    // 3. Even if a browser sends revoked __Host-roche_session alongside valid roche_session over HTTP,
    //    both SPA shell and API authenticate cleanly without looping to /login
    let combined = format!("{dead_host_cookie}; {fresh_plain_cookie}");
    assert_eq!(get_authed(&app, "/", &combined).await.status(), StatusCode::OK);
    assert_eq!(get_authed(&app, "/api/v1/items", &combined).await.status(), StatusCode::OK);
}

#[tokio::test]
async fn test_item_audio_blocks_local_redirect_and_allows_public() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let local_url = Url::parse("https://pod.example/ep-local").unwrap();
    let (local_item, _) = db
        .items()
        .save_manual_input_with_tags(&crate::db::ManualItemInput {
            url: &local_url,
            title: "Local Audio Ep",
            summary: None,
            content_html: None,
            thumbnail_url: None,
            audio_url: Some("http://127.0.0.1:9999/secret.mp3"),
            duration: None,
            content_type: ContentType::Audio,
            tags: &[],
        })
        .await
        .unwrap();

    let blocked_resp =
        get_authed(&app, &format!("/api/v1/items/{}/audio", local_item.id), &cookie).await;
    assert_eq!(blocked_resp.status(), StatusCode::NOT_FOUND);

    let pub_url = Url::parse("https://pod.example/ep-public").unwrap();
    let (pub_item, _) = db
        .items()
        .save_manual_input_with_tags(&crate::db::ManualItemInput {
            url: &pub_url,
            title: "Public Audio Ep",
            summary: None,
            content_html: None,
            thumbnail_url: None,
            audio_url: Some("https://cdn.pod.example/ep1.mp3"),
            duration: None,
            content_type: ContentType::Audio,
            tags: &[],
        })
        .await
        .unwrap();

    let ok_resp = get_authed(&app, &format!("/api/v1/items/{}/audio", pub_item.id), &cookie).await;
    assert_eq!(ok_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(ok_resp.headers().get(header::LOCATION).unwrap(), "https://cdn.pod.example/ep1.mp3");
}

#[tokio::test]
async fn test_spa_api_home_reader_finish_unfinish_and_rank_session() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    // Seed feed + article item with heading/code/image/script for reader sanitization test
    let feed_url = Url::parse("https://editorial.example/rss.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("Editorial Feed"), None).await.unwrap();
    let item_url = Url::parse("https://editorial.example/spa-essay").unwrap();
    let item = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "SPA Architecture Notes",
            url: &item_url,
            author: Some("Ada Lovelace"),
            published_at: None,
            summary: Some("Short abstract."),
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    db.items()
        .apply_background_enrichment(
            &item.id,
            None,
            &[],
            None,
            None,
            None,
            Some(
                "<h1>Embedded Heading</h1><script>alert(1)</script><p>Body</p><pre><code>fn main() {}</code></pre><img src=\"https://editorial.example/fig.png\">",
            ),
        )
        .await
        .unwrap();

    // 1. GET /api/v1/home
    let home_resp = get_authed(&app, "/api/v1/home", &cookie).await;
    assert_eq!(home_resp.status(), StatusCode::OK);
    let home_json = read_body_json(home_resp).await;
    assert_eq!(home_json["unread_total"], 1);

    // 2. GET /api/v1/items/{id}/reader verifies sanitization & accessibility hardening
    let reader_resp = get_authed(&app, &format!("/api/v1/items/{}/reader", item.id), &cookie).await;
    assert_eq!(reader_resp.status(), StatusCode::OK);
    let reader_json = read_body_json(reader_resp).await;
    let prose = reader_json["content_html"].as_str().unwrap();
    assert!(!prose.contains("<script"), "script tag leaked into reader HTML");
    assert!(prose.contains("<h3>Embedded Heading</h3>"), "embedded h1 must demote to h3");
    assert!(prose.contains("<pre tabindex=\"0\">"), "pre blocks must be keyboard focusable");
    assert!(prose.contains("alt=\"\""), "untitled images must receive empty alt");
    assert!(prose.contains("referrerpolicy=\"no-referrer\""), "images must protect referrer");

    // 3. Finish & Unfinish reader item
    let fin_resp = post_json_authed(
        &app,
        &format!("/api/v1/items/{}/finish", item.id),
        &cookie,
        Some(&csrf),
        serde_json::json!({}),
    )
    .await;
    assert_eq!(fin_resp.status(), StatusCode::OK);
    let fin_json = read_body_json(fin_resp).await;
    assert_eq!(fin_json["has_feed"], true);
    assert_eq!(fin_json["item"]["is_finished"], true);

    let unfin_resp = post_json_authed(
        &app,
        &format!("/api/v1/items/{}/unfinish", item.id),
        &cookie,
        Some(&csrf),
        serde_json::json!({"requeue": true}),
    )
    .await;
    assert_eq!(unfin_resp.status(), StatusCode::OK);

    // 4. Rank session GET + submit + roche_rank_k preference cookie
    let col = db
        .catalog()
        .create_manual_collection(
            "SPA Rank List",
            None,
            crate::db::CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let slug = col.slug.as_deref().unwrap_or(&col.id).to_owned();
    let mut ids = Vec::new();
    for title in ["Work Alpha", "Work Beta", "Work Gamma"] {
        let entry = db
            .catalog()
            .upsert_entry(&crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: title.to_owned(),
                ..Default::default()
            })
            .await
            .unwrap();
        db.catalog().add_collection_entry(&slug, &entry.id, None).await.unwrap();
        ids.push(entry.id);
    }

    let rank_get = get_authed(&app, &format!("/api/v1/catalog/rank/{slug}/session"), &cookie).await;
    assert_eq!(rank_get.status(), StatusCode::OK);
    let rank_json = read_body_json(rank_get).await;
    assert_eq!(rank_json["cluster"].as_array().unwrap().len(), 3);

    let rank_submit = post_json_authed(
        &app,
        &format!("/api/v1/catalog/rank/{slug}/submit"),
        &cookie,
        Some(&csrf),
        serde_json::json!({
            "ordering": [[&ids[0]], [&ids[1], &ids[2]]],
            "k": 3
        }),
    )
    .await;
    assert_eq!(rank_submit.status(), StatusCode::CREATED);
    assert!(find_set_cookie(&rank_submit, "roche_rank_k=3").is_some());
    let submit_json = read_body_json(rank_submit).await;
    assert!(submit_json["comparison"]["id"].as_str().is_some());

    // 5. Rich read endpoints for SPA views
    for uri in [
        "/api/v1/search/rich?q=Alpha".to_owned(),
        "/api/v1/catalog/lists/overview".to_owned(),
        format!("/api/v1/catalog/lists/{slug}/detail"),
        format!("/api/v1/catalog/entries/{}/detail", ids[0]),
        "/api/v1/catalog/diary/page".to_owned(),
        "/api/v1/catalog/queues".to_owned(),
    ] {
        let resp = get_authed(&app, &uri, &cookie).await;
        assert_eq!(resp.status(), StatusCode::OK, "expected 200 OK on {uri}");
    }
}

#[tokio::test]
async fn test_spa_api_contracts_security_and_background_refresh() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    // 1. Fetch-metadata enforcement: same-site & duplicate Sec-Fetch-Site must be blocked on mutations
    for headers in [vec!["same-site"], vec!["same-origin", "same-origin"]] {
        let mut req = Request::builder()
            .method("POST")
            .uri("/api/v1/catalog/lists")
            .header(header::COOKIE, &cookie)
            .header(header::CONTENT_TYPE, "application/json")
            .header(CSRF_HEADER_NAME, &csrf);
        for val in headers {
            req = req.header("sec-fetch-site", val);
        }
        let resp = app
            .clone()
            .oneshot(
                req.body(Body::from(serde_json::json!({"title": "Blocked"}).to_string())).unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(resp.status(), StatusCode::FORBIDDEN);
    }

    // 2. Seed feed + article + local media row -> test /api/v1/items/inbox, /reader sanitization & media path hiding
    let feed_url = Url::parse("https://journal.example/feed.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("Journal Feed"), None).await.unwrap();
    let item_url = Url::parse("https://journal.example/math-essay").unwrap();
    let item = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Essay on $E=mc^2$ & <Markup>",
            url: &item_url,
            author: Some("Emmy Noether"),
            published_at: None,
            summary: Some("Abstract"),
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    db.items()
        .apply_background_enrichment(
            &item.id,
            None,
            &[],
            None,
            None,
            None,
            Some("<p>Safe</p><script>alert(1)</script>"),
        )
        .await
        .unwrap();
    db.media()
        .create_batch(
            &item.id,
            &[crate::db::NewMediaSpec {
                media_type: crate::domain::MediaType::Pdf,
                path: "/var/lib/roche/secret/internal.pdf",
                file_size: Some(4096),
                duration: None,
            }],
        )
        .await
        .unwrap();

    let inbox_resp =
        get_authed(&app, "/api/v1/items/inbox?status=unread&inbox_only=true", &cookie).await;
    assert_eq!(inbox_resp.status(), StatusCode::OK);
    let inbox_json = read_body_json(inbox_resp).await;
    assert_eq!(inbox_json["items"].as_array().unwrap().len(), 1);
    let inbox_title_html = inbox_json["title_html"][&item.id].as_str().unwrap();
    assert!(inbox_title_html.contains("&lt;Markup&gt;"));

    let reader_resp = get_authed(&app, &format!("/api/v1/items/{}/reader", item.id), &cookie).await;
    assert_eq!(reader_resp.status(), StatusCode::OK);
    let reader_json = read_body_json(reader_resp).await;
    assert!(reader_json["content_html"].as_str().unwrap().contains("<p>Safe</p>"));
    assert!(!reader_json["content_html"].as_str().unwrap().contains("<script"));
    assert!(
        reader_json["detail"]["content_html"].is_null(),
        "nested detail.content_html must be cleared to prevent raw HTML leakage"
    );
    let media_arr = reader_json["detail"]["media"].as_array().unwrap();
    assert_eq!(media_arr.len(), 1);
    assert!(
        media_arr[0].get("path").is_none(),
        "Media.path must not serialize server filesystem paths"
    );

    // 3. OptionalProblemJson rejects malformed JSON / wrong Content-Type with RFC 9457 Problem Details
    let bad_json_finish = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/items/{}/finish", item.id))
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "application/json")
                .header(CSRF_HEADER_NAME, &csrf)
                .header("sec-fetch-site", "same-origin")
                .body(Body::from("{broken json"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bad_json_finish.status(), StatusCode::BAD_REQUEST);
    let bad_ct_finish = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/items/{}/finish", item.id))
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "text/plain")
                .header(CSRF_HEADER_NAME, &csrf)
                .header("sec-fetch-site", "same-origin")
                .body(Body::from("hello"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(bad_ct_finish.status(), StatusCode::UNSUPPORTED_MEDIA_TYPE);

    // 4. Quick-log + Lists overview/detail contracts + Diary cadence + Queues + Search + Want complete
    let ql_want = post_json_authed(
        &app,
        "/api/v1/catalog/quick-log",
        &cookie,
        Some(&csrf),
        serde_json::json!({
            "title": "Design Patterns Queued",
            "kind": "book",
            "action": "want",
            "creators": ["Gamma"],
            "release_year": 1994
        }),
    )
    .await;
    assert_eq!(ql_want.status(), StatusCode::CREATED);
    let ql_want_json = read_body_json(ql_want).await;
    let queued_entry_id = ql_want_json["entry"]["id"].as_str().unwrap().to_owned();

    let queues_resp = get_authed(&app, "/api/v1/catalog/queues", &cookie).await;
    assert_eq!(queues_resp.status(), StatusCode::OK);
    let queues_json = read_body_json(queues_resp).await;
    assert_eq!(queues_json["total_items"], 1);
    assert!(queues_json["title_html"][&queued_entry_id].is_string());

    let search_resp = get_authed(&app, "/api/v1/search/rich?q=Design+Patterns", &cookie).await;
    assert_eq!(search_resp.status(), StatusCode::OK);
    let search_json = read_body_json(search_resp).await;
    let want_hits = search_json["results"]["want_entries"].as_array().unwrap();
    assert_eq!(want_hits.len(), 1);
    assert!(want_hits[0]["queue_id"].is_string());
    assert!(want_hits[0]["queue_title"].is_string());
    let actual_queue_slug = want_hits[0]["queue_slug"].as_str().unwrap().to_owned();

    // Unknown queue slug returns 404; valid queue slug succeeds with empty body
    let bad_queue_complete = post_json_authed(
        &app,
        &format!("/api/v1/catalog/want/nonexistent-queue/complete/{queued_entry_id}"),
        &cookie,
        Some(&csrf),
        serde_json::json!({}),
    )
    .await;
    assert_eq!(bad_queue_complete.status(), StatusCode::NOT_FOUND);

    let ok_queue_complete = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/api/v1/catalog/want/{actual_queue_slug}/complete/{queued_entry_id}"))
                .header(header::COOKIE, &cookie)
                .header(CSRF_HEADER_NAME, &csrf)
                .header("sec-fetch-site", "same-origin")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(ok_queue_complete.status(), StatusCode::OK);

    // Log second book + manual list + comparison to check RankedEntryRow & is_dynamic / dynamic_collection_ids
    let ql_book2 = post_json_authed(
        &app,
        "/api/v1/catalog/quick-log",
        &cookie,
        Some(&csrf),
        serde_json::json!({
            "title": "Structure and Interpretation",
            "kind": "book",
            "action": "consumed",
            "creators": ["Abelson"],
            "release_year": 1985
        }),
    )
    .await;
    assert_eq!(ql_book2.status(), StatusCode::CREATED);
    let book2_id = read_body_json(ql_book2).await["entry"]["id"].as_str().unwrap().to_owned();

    let manual = db
        .catalog()
        .create_manual_collection(
            "Manual Contract List",
            None,
            crate::db::CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let manual_slug = manual.slug.as_deref().unwrap_or(&manual.id).to_owned();
    db.catalog().add_collection_entry(&manual_slug, &queued_entry_id, None).await.unwrap();
    db.catalog().add_collection_entry(&manual_slug, &book2_id, None).await.unwrap();

    // GET rank session must NOT emit Set-Cookie even with ?k=2
    let rank_get =
        get_authed(&app, &format!("/api/v1/catalog/rank/{manual_slug}/session?k=2"), &cookie).await;
    assert_eq!(rank_get.status(), StatusCode::OK);
    assert!(
        rank_get.headers().get(header::SET_COOKIE).is_none(),
        "GET rank session must not mutate cookies"
    );

    let rank_post = post_json_authed(
        &app,
        &format!("/api/v1/catalog/rank/{manual_slug}/submit"),
        &cookie,
        Some(&csrf),
        serde_json::json!({"ordering": [[&queued_entry_id], [&book2_id]], "k": 2}),
    )
    .await;
    assert_eq!(rank_post.status(), StatusCode::CREATED);

    let overview_resp = get_authed(&app, "/api/v1/catalog/lists/overview", &cookie).await;
    assert_eq!(overview_resp.status(), StatusCode::OK);
    let overview_json = read_body_json(overview_resp).await;
    let dyn_ids: Vec<&str> = overview_json["dynamic_collection_ids"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(serde_json::Value::as_str)
        .collect();
    assert!(
        !dyn_ids.is_empty(),
        "built-in media collections must appear in dynamic_collection_ids"
    );
    assert!(!dyn_ids.contains(&manual.id.as_str()), "manual list must not be marked dynamic");

    let manual_detail = read_body_json(
        get_authed(&app, &format!("/api/v1/catalog/lists/{manual_slug}/detail"), &cookie).await,
    )
    .await;
    assert_eq!(manual_detail["is_dynamic"], false);
    let first_row = &manual_detail["view"]["rows"][0];
    assert!(first_row["score"]["mean"].is_number());
    assert!(first_row["score"]["variance"].is_number());
    assert!(first_row["bar_pct"].is_number());

    let books_detail =
        read_body_json(get_authed(&app, "/api/v1/catalog/lists/books/detail", &cookie).await).await;
    assert_eq!(books_detail["is_dynamic"], true);

    // Entry detail merge preview error is sanitized + log_history has ConsumptionLogRow fields
    let entry_detail = read_body_json(
        get_authed(
            &app,
            &format!(
                "/api/v1/catalog/entries/{queued_entry_id}/detail?merge_preview=no-such-entry-xyz"
            ),
            &cookie,
        )
        .await,
    )
    .await;
    assert!(entry_detail["merge_preview_error"].as_str().is_some());
    assert_eq!(entry_detail["detail"]["log_history"].as_array().unwrap().len(), 1);

    // Diary page includes SQL-grouped active-month cadence counts summing to our 2 logged books
    let diary_json =
        read_body_json(get_authed(&app, "/api/v1/catalog/diary/page", &cookie).await).await;
    let cadence = diary_json["cadence_months"].as_array().unwrap();
    assert_eq!(cadence.len(), 1);
    let cadence_sum: i64 = cadence.iter().filter_map(|m| m[1].as_i64()).sum();
    assert_eq!(cadence_sum, 2);

    // 5. Board dismissed_only=true returns dismissed item past rank #30 + background refresh returns 200 OK
    let board_spec = roche_core::boards::BoardSourceSpec::for_hacker_news(
        roche_core::boards::HackerNewsSection::Top,
    );
    let board = db.boards().create(&board_spec, Some("HN Contract"), None, 30).await.unwrap();
    let candidates: Vec<crate::db::BoardPollCandidate> = (1..=35_i64)
        .map(|rank| crate::db::BoardPollCandidate {
            external_id: format!("ext-{rank}"),
            rank,
            title: format!("Story #{rank}"),
            url: format!("https://example.org/story-{rank}"),
            discussion_url: Some(format!("https://news.ycombinator.com/item?id={rank}")),
            author: Some("alice".to_owned()),
            domain: Some("example.org".to_owned()),
            published_at: None,
            score: Some(100 - rank),
            comment_count: Some(rank),
            summary: None,
            self_text_html: None,
            metadata_json: "{}".to_owned(),
        })
        .collect();
    db.boards()
        .record_poll_batch(&board.id, &crate::db::BoardPollBatch { items: candidates })
        .await
        .unwrap();
    db.boards().dismiss_item(&board.id, "ext-35").await.unwrap();

    let dismissed_resp = get_authed(
        &app,
        &format!("/api/v1/boards/{}/items?page=1&per_page=30&dismissed_only=true", board.id),
        &cookie,
    )
    .await;
    assert_eq!(dismissed_resp.status(), StatusCode::OK);
    let dismissed_json = read_body_json(dismissed_resp).await;
    let dismissed_items = dismissed_json["items"].as_array().unwrap();
    assert_eq!(dismissed_items.len(), 1);
    assert_eq!(dismissed_items[0]["external_id"], "ext-35");
    assert_eq!(dismissed_items[0]["is_dismissed"], true);
    assert!(dismissed_items[0]["is_favorite"].is_boolean());
    assert!(dismissed_items[0]["in_want_to_read"].is_boolean());

    let feed_bg = post_json_authed(
        &app,
        &format!("/api/v1/feeds/{}/refresh?background=true", feed.id),
        &cookie,
        Some(&csrf),
        serde_json::json!({}),
    )
    .await;
    assert_eq!(feed_bg.status(), StatusCode::OK);
    assert_eq!(read_body_json(feed_bg).await["id"], feed.id);

    let board_bg = post_json_authed(
        &app,
        &format!("/api/v1/boards/{}/refresh?background=true", board.id),
        &cookie,
        Some(&csrf),
        serde_json::json!({}),
    )
    .await;
    assert_eq!(board_bg.status(), StatusCode::OK);
    assert_eq!(read_body_json(board_bg).await["id"], board.id);
}

// ---------------------------------------------------------------------------
// WCAG 2.1 AA CSS Palette Contrast Guard (against web/src/styles/*)
// ---------------------------------------------------------------------------

const WEB_TOKENS_CSS: &str = include_str!("../../../web/src/styles/tokens.css");
const WEB_BASE_CSS: &str = include_str!("../../../web/src/styles/base.css");
const WEB_PRIMITIVES_CSS: &str = include_str!("../../../web/src/styles/primitives.css");

fn strip_css_comments(css: &str) -> String {
    let mut out = String::with_capacity(css.len());
    let mut rest = css;
    while let Some(start) = rest.find("/*") {
        out.push_str(&rest[..start]);
        if let Some(end) = rest[start + 2..].find("*/") {
            rest = &rest[start + 2 + end + 2..];
        } else {
            return out;
        }
    }
    out.push_str(rest);
    out
}

fn parse_css_theme_blocks(css: &str) -> Vec<(String, std::collections::HashMap<String, String>)> {
    let cleaned = strip_css_comments(css);
    let mut blocks = Vec::new();
    let mut search = cleaned.as_str();
    while let Some(root_start) = search.find(":root") {
        let after_root = &search[root_start..];
        let Some(open) = after_root.find('{') else {
            break;
        };
        let selector = after_root[..open].trim().to_owned();
        let Some(close) = after_root[open + 1..].find('}') else {
            break;
        };
        let mut vars = std::collections::HashMap::new();
        for decl in after_root[open + 1..open + 1 + close].split(';') {
            let trimmed = decl.trim();
            if let Some(prop) = trimmed.strip_prefix("--")
                && let Some((name, value)) = prop.split_once(':')
            {
                vars.insert(format!("--{}", name.trim()), value.trim().to_owned());
            }
        }
        if !vars.is_empty() {
            blocks.push((selector, vars));
        }
        search = &after_root[open + 1 + close + 1..];
    }
    blocks
}

fn parse_hex_rgb(hex: &str) -> (f64, f64, f64) {
    let h = hex.strip_prefix('#').expect("hex color starts with #");
    assert_eq!(h.len(), 6, "expected 6-digit hex color, got {hex}");
    let r = u8::from_str_radix(&h[0..2], 16).unwrap();
    let g = u8::from_str_radix(&h[2..4], 16).unwrap();
    let b = u8::from_str_radix(&h[4..6], 16).unwrap();
    (f64::from(r) / 255.0, f64::from(g) / 255.0, f64::from(b) / 255.0)
}

fn wcag_relative_luminance(hex: &str) -> f64 {
    fn linearize(channel: f64) -> f64 {
        if channel <= 0.04045 { channel / 12.92 } else { ((channel + 0.055) / 1.055).powf(2.4) }
    }
    let (r, g, b) = parse_hex_rgb(hex);
    0.2126_f64.mul_add(linearize(r), 0.7152_f64.mul_add(linearize(g), 0.0722 * linearize(b)))
}

fn wcag_contrast_ratio(foreground_hex: &str, background_hex: &str) -> f64 {
    let lum_a = wcag_relative_luminance(foreground_hex);
    let lum_b = wcag_relative_luminance(background_hex);
    let (lighter, darker) = if lum_a >= lum_b { (lum_a, lum_b) } else { (lum_b, lum_a) };
    (lighter + 0.05) / (darker + 0.05)
}

#[test]
fn web_css_palette_meets_wcag_aa_contrast() {
    let blocks = parse_css_theme_blocks(WEB_TOKENS_CSS);
    assert!(
        blocks.len() >= 2,
        "web/src/styles/tokens.css must define light and dark :root theme blocks"
    );

    let normal_text_tokens = [
        "--ink",
        "--ink-soft",
        "--ink-faint",
        "--link",
        "--link-hover",
        "--accent",
        "--tier-S",
        "--tier-A",
        "--tier-B",
        "--tier-C",
        "--tier-D",
    ];

    for (selector, vars) in &blocks {
        let get = |name: &str| -> &str {
            vars.get(name)
                .map_or_else(|| panic!("missing CSS token {name} in {selector}"), String::as_str)
        };

        let surfaces = [
            ("--paper", get("--paper")),
            ("--paper-deep", get("--paper-deep")),
            ("--paper-input", get("--paper-input")),
        ];

        for (surface_label, surface_hex) in surfaces {
            for text_token in normal_text_tokens {
                let text_hex = get(text_token);
                let ratio = wcag_contrast_ratio(text_hex, surface_hex);
                assert!(
                    ratio >= 4.5,
                    "tokens.css ({selector}) {text_token} ({text_hex}) on {surface_label} ({surface_hex}) has contrast {ratio:.2}:1 (WCAG AA requires >= 4.5:1)"
                );
            }

            for ui_token in ["--control-border", "--accent"] {
                let ui_hex = get(ui_token);
                let ratio = wcag_contrast_ratio(ui_hex, surface_hex);
                assert!(
                    ratio >= 3.0,
                    "tokens.css ({selector}) {ui_token} ({ui_hex}) on {surface_label} ({surface_hex}) has contrast {ratio:.2}:1 (WCAG 1.4.11 requires >= 3.0:1)"
                );
            }
        }

        for (foreground_token, background_token) in [
            ("--paper", "--ink"),
            ("--accent-on-fill", "--accent-fill"),
            ("--accent-on-fill", "--accent-fill-hover"),
        ] {
            let ratio = wcag_contrast_ratio(get(foreground_token), get(background_token));
            assert!(
                ratio >= 4.5,
                "tokens.css ({selector}) {foreground_token} on {background_token} has contrast {ratio:.2}:1 (WCAG AA requires >= 4.5:1)"
            );
        }
    }

    let combined_global_css = format!("{WEB_BASE_CSS}\n{WEB_PRIMITIVES_CSS}");
    assert!(
        combined_global_css.contains("::placeholder"),
        "global CSS must explicitly style ::placeholder"
    );
    assert!(
        combined_global_css.contains(":disabled"),
        "global CSS must explicitly style :disabled controls"
    );
    assert!(
        combined_global_css.contains("scroll-padding-bottom"),
        "global CSS must reserve scroll-padding-bottom when fixed player bar is visible"
    );
}

// ---------------------------------------------------------------------------
// Pure Security, Sanitization, Link Filtering & Enrichment Unit Tests
// ---------------------------------------------------------------------------

#[test]
fn test_artist_enrichment_wikipedia_and_spotify_parsers() {
    assert_eq!(sanitize_non_local_href("HTTPS://ROCHE.LOCAL/entry/123"), None);
    assert_eq!(sanitize_non_local_href("http://127.0.0.1:8080/thumb.jpg"), None);
    assert_eq!(encode_wiki_path_segment("AC/DC"), "AC%2FDC");

    let nirvana_hits = vec![
        WikipediaSearchHit {
            title: "Nirvana".to_owned(),
            snippet: "In Indian religions, nirvana is the liberation from samsara.".to_owned(),
        },
        WikipediaSearchHit {
            title: "Nirvana (band)".to_owned(),
            snippet: "American rock band formed in Aberdeen in 1987.".to_owned(),
        },
        WikipediaSearchHit {
            title: "Nirvana (UK band)".to_owned(),
            snippet: "British progressive rock band formed in London in 1967.".to_owned(),
        },
    ];
    assert_eq!(
        select_best_artist_wikipedia_slug(&nirvana_hits, "Nirvana", &[]),
        Some("Nirvana_(band)".to_owned())
    );

    let elvis_hits = vec![
        WikipediaSearchHit {
            title: "Elvis Presley (album)".to_owned(),
            snippet: "Debut studio album by American singer Elvis Presley.".to_owned(),
        },
        WikipediaSearchHit {
            title: "Elvis Presley".to_owned(),
            snippet: "American singer and actor (1935–1977).".to_owned(),
        },
    ];
    assert_eq!(
        select_best_artist_wikipedia_slug(&elvis_hits, "Elvis Presley", &["Elvis Presley".into()]),
        Some("Elvis_Presley".to_owned())
    );

    let collab_html = r#"
        <script>if (x<a && y>z) { console.log("ok"); }</script>
        <meta name="music:musician" content="https://open.spotify.com/artist/1111111111111111111111"/>
        <meta name="music:musician" content="https://open.spotify.com/artist/2222222222222222222222"/>
        <a href="/artist/1111111111111111111111">Guns N&#8217; Roses</a>
        <a href="/artist/2222222222222222222222">Brian Eno</a>
    "#;
    assert_eq!(
        extract_spotify_artist_uri_from_album_html(collab_html, "Guns N' Roses", false),
        Some("spotify:artist:1111111111111111111111".to_owned())
    );
    assert_eq!(
        extract_spotify_artist_uri_from_album_html(collab_html, "Brian Eno", false),
        Some("spotify:artist:2222222222222222222222".to_owned())
    );
    assert_eq!(extract_spotify_artist_uri_from_album_html(collab_html, "Unknown", true), None);

    let arctic_json = r#"{"type":"standard","description":"English rock band","extract":"Arctic Monkeys are an English rock band formed in Sheffield in 2002. Moving step by step, their 2006 record became the fastest-selling debut album by a band in UK chart history."}"#;
    assert!(parse_wikipedia_artist_summary_json(arctic_json, true).is_some());

    let furniture_json = r#"{"type":"standard","description":"Item of furniture","extract":"A table is an item of furniture with a flat top."}"#;
    assert_eq!(parse_wikipedia_artist_summary_json(furniture_json, true), None);
}

#[test]
fn test_decode_html_entities_multibyte_utf8_no_panic() {
    let input = "A &0123456789\u{2014}end &amp; &quot;Bj\u{00f6}rk&quot; &#x27;ok&#x27;";
    let decoded = decode_html_entities(input);
    assert_eq!(decoded, "A &0123456789\u{2014}end & \"Bj\u{00f6}rk\" 'ok'");

    let no_semi = "&日本語テスト文字列";
    assert_eq!(decode_html_entities(no_semi), no_semi);
}

#[test]
fn test_sanitize_non_local_href_blocks_ipv6_ula_link_local_mapped_6to4_and_cgnat() {
    for blocked in [
        "http://0.0.0.0/a",
        "http://0.1.2.3/a",
        "http://127.0.0.1/a",
        "http://10.0.0.1/a",
        "http://169.254.169.254/latest/meta-data",
        "http://100.64.0.1/tailscale",
        "http://100.127.255.254/cgnat",
        "http://192.0.2.1/doc",
        "http://198.18.0.1/benchmark",
        "http://198.51.100.1/doc",
        "http://203.0.113.1/doc",
        "http://240.0.0.1/reserved",
        "http://255.255.255.255/broadcast",
        "http://[::1]/a",
        "http://[::127.0.0.1]/ipv4-compat-loopback",
        "http://[::169.254.169.254]/ipv4-compat-meta",
        "http://[::8.8.8.8]/ipv4-compat",
        "http://[::ffff:0:127.0.0.1]/siit",
        "http://[64:ff9b::127.0.0.1]/nat64-wkp",
        "http://[64:ff9b:1::1]/nat64-local",
        "http://[100::1]/discard",
        "http://[2001:0000:4136:e378:8000:63bf:3fff:fdd2]/teredo",
        "http://[2001:20::1]/orchidv2",
        "http://[2001:db8::1]/doc",
        "http://[fc00::1]/ula",
        "http://[fd12:3456::1]/ula",
        "http://[fe80::1]/link-local",
        "http://[fec0::1]/site-local",
        "http://[::ffff:127.0.0.1]/mapped-loopback",
        "http://[::ffff:10.0.0.1]/mapped-private",
        "http://[::ffff:169.254.169.254]/mapped-meta",
        "http://[::ffff:100.64.0.5]/mapped-cgnat",
        "http://[::ffff:0.1.2.3]/mapped-this-network",
        "http://[::ffff:240.0.0.1]/mapped-reserved",
        "http://[2002:7f00:0001::1]/6to4",
        "https://localhost/foo",
        "https://printer.local/foo",
        "http://roche.local/entry",
        "https://metadata.google.internal/computeMetadata/v1/",
        "http://home.arpa/admin",
        "http://router.home.arpa/status",
        "http://nas.lan/status",
        "http://router.home/status",
        "https://my-box.tail1234.ts.net/status",
        "https://user:pass@example.com/secret",
    ] {
        assert!(sanitize_non_local_href(blocked).is_none(), "expected {blocked} to be blocked");
    }

    for allowed in [
        "https://example.com/audio.mp3",
        "https://example.com:8444/audio.mp3",
        "https://8.8.8.8/file.mp3",
        "https://[2606:4700:4700::1111]/file.mp3",
        "https://[::ffff:8.8.8.8]/file.mp3",
    ] {
        assert!(sanitize_non_local_href(allowed).is_some(), "expected {allowed} to be allowed");
    }
}

#[test]
fn test_is_same_origin_post_origin_and_referer_fallback() {
    let mut h = HeaderMap::new();
    h.insert("sec-fetch-site", "same-origin".parse().unwrap());
    assert!(is_same_origin_post(&h, true));
    assert!(is_same_origin_post(&h, false));

    let mut h = HeaderMap::new();
    h.insert("sec-fetch-site", "cross-site".parse().unwrap());
    assert!(!is_same_origin_post(&h, true));

    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example:8080".parse().unwrap());
    h.insert(header::ORIGIN, "https://roche.example:8080".parse().unwrap());
    assert!(is_same_origin_post(&h, true));
    assert!(!is_same_origin_post(&h, false));

    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example:8080".parse().unwrap());
    h.insert(header::ORIGIN, "http://roche.example:8080".parse().unwrap());
    assert!(is_same_origin_post(&h, false));
    assert!(!is_same_origin_post(&h, true));

    for bad_host in
        ["roche.example?x=1", "roche.example#frag", "roche.example/path", "u@roche.example"]
    {
        let mut h = HeaderMap::new();
        h.insert(header::HOST, bad_host.parse().unwrap());
        h.insert(header::ORIGIN, "https://roche.example".parse().unwrap());
        assert!(
            !is_same_origin_post(&h, true),
            "expected malformed host {bad_host} to be rejected"
        );
    }

    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example".parse().unwrap());
    h.insert(header::REFERER, "https://roche.example/login".parse().unwrap());
    assert!(is_same_origin_post(&h, true));
    assert!(!is_same_origin_post(&h, false));

    assert!(!is_same_origin_post(&HeaderMap::new(), true));
    assert!(!is_same_origin_post(&HeaderMap::new(), false));
}

#[test]
fn test_safe_local_redirect_and_login_return_to() {
    assert_eq!(safe_local_redirect(Some("/inbox?q=caf\u{00e9}"), "/fallback"), "/fallback");
    assert_eq!(safe_local_redirect(Some("/inbox?q=ok"), "/fallback"), "/inbox?q=ok");
    assert_eq!(safe_local_redirect(Some("//evil.example/x"), "/fallback"), "/fallback");
    assert_eq!(safe_local_redirect(Some("https://evil.example"), "/fallback"), "/fallback");

    let non_ascii_resp = redirect_see_other("/inbox?q=caf\u{00e9}");
    assert_eq!(non_ascii_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(non_ascii_resp.headers().get(header::LOCATION).unwrap(), "/");

    assert_eq!(safe_login_return_to(Some("/login")), "/");
    assert_eq!(safe_login_return_to(Some("/api")), "/");
    assert_eq!(safe_login_return_to(Some("/api/v1/items")), "/");
    assert_eq!(safe_login_return_to(Some("/web")), "/");
    assert_eq!(safe_login_return_to(Some("/web/foo")), "/");
    assert_eq!(safe_login_return_to(Some("/inbox?kind=article")), "/inbox?kind=article");
}

#[test]
fn test_enrich_attempt_cache_ttl() {
    let mut cache = EnrichAttemptCache::default();
    let t0 = Instant::now();
    let ttl = Duration::from_secs(60);

    assert!(cache.should_attempt_and_mark("a1|sp=false".to_owned(), t0, ttl));
    assert!(!cache.should_attempt_and_mark(
        "a1|sp=false".to_owned(),
        t0 + Duration::from_secs(30),
        ttl
    ));
    assert!(cache.should_attempt_and_mark(
        "a1|sp=false".to_owned(),
        t0 + Duration::from_secs(61),
        ttl
    ));
}

#[test]
fn test_try_reserve_enrichment_slot_bounds_concurrency_before_marking_cache() {
    let enricher = ArtistEnricher::new(false);
    let now = Instant::now();
    let ttl = Duration::from_secs(60);

    let mut held = Vec::new();
    for idx in 0..MAX_CONCURRENT_ENRICH_TASKS {
        let permit = enricher
            .try_reserve_slot(format!("artist-{idx}|sp=false"), now, ttl)
            .unwrap_or_else(|| panic!("expected slot {idx} to be reserved"));
        held.push(permit);
    }
    assert_eq!(enricher.available_permits(), 0);
    assert!(enricher.try_reserve_slot("artist-overflow|sp=false".to_owned(), now, ttl).is_none());

    drop(held.pop());
    assert_eq!(enricher.available_permits(), 1);
    let overflow_permit =
        enricher.try_reserve_slot("artist-overflow|sp=false".to_owned(), now, ttl);
    assert!(overflow_permit.is_some());
    drop(overflow_permit);

    assert_eq!(enricher.available_permits(), 1);
    assert!(enricher.try_reserve_slot("artist-overflow|sp=false".to_owned(), now, ttl).is_none());
    drop(held);
    assert_eq!(enricher.available_permits(), MAX_CONCURRENT_ENRICH_TASKS);
}

#[test]
fn test_web_music_links_only_show_wikipedia_and_spotify() {
    let now = chrono::Utc::now();
    let entry = CatalogEntry {
        id: "alb-1".to_owned(),
        kind: EntryKind::Album,
        title: "Kind of Blue".to_owned(),
        creators: vec!["Miles Davis".to_owned()],
        release_year: Some(1959),
        url: Some("https://music.apple.com/album/123".to_owned()),
        published_at: None,
        summary: None,
        thumbnail_url: None,
        metadata: serde_json::json!({}),
        merged_into_id: None,
        identifiers: vec![
            EntryIdentifier {
                scheme: "spotify".to_owned(),
                value: "spotify:album:1weenld61qoidwYuZ1GESA".to_owned(),
            },
            EntryIdentifier { scheme: "wikipedia".to_owned(), value: "Kind_of_Blue".to_owned() },
            EntryIdentifier { scheme: "apple_music".to_owned(), value: "123".to_owned() },
        ],
        created_at: now,
        updated_at: now,
    };

    let links = collect_web_external_links(&entry, Some("Source"));
    assert_eq!(links.len(), 2);
    assert!(links.iter().any(|l| l.icon == WebLinkIcon::Spotify));
    assert!(links.iter().any(|l| l.icon == WebLinkIcon::Wikipedia));
}

#[test]
fn test_album_tracklist_and_artist_discography_parsers() {
    let spotify_embed_html = r#"<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[{"title":"So What","subtitle":"Miles Davis","duration":562000,"uri":"spotify:track:4vLYewWIvqHfKtJDk8c8tq"},{"title":"Freddie Freeloader","subtitle":"Miles Davis","duration":586000,"uri":"spotify:track:2x91iJc0UKFmcR4z5xS2yX"}]}}}}}}</script></body></html>"#;
    let sp_tracks = parse_spotify_embed_tracks(spotify_embed_html);
    assert_eq!(sp_tracks.len(), 2);
    assert_eq!(sp_tracks[0].title, "So What");
    assert_eq!(sp_tracks[0].duration_ms, Some(562_000));

    let itunes_tracks_json = r#"{"resultCount":2,"results":[{"wrapperType":"collection","collectionName":"Kind of Blue"},{"wrapperType":"track","trackNumber":1,"discNumber":1,"trackName":"So What","trackTimeMillis":562000,"artistName":"Miles Davis"}]}"#;
    let it_tracks = parse_itunes_lookup_tracks(itunes_tracks_json);
    assert_eq!(it_tracks.len(), 1);
    assert_eq!(it_tracks[0].title, "So What");

    let itunes_discog_json = r#"{"resultCount":2,"results":[{"wrapperType":"artist","artistName":"Miles Davis"},{"wrapperType":"collection","collectionType":"Album","collectionName":"Kind of Blue","artistName":"Miles Davis","releaseDate":"1959-08-17T07:00:00Z","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/100x100bb.jpg","trackCount":5}]}"#;
    let discog = parse_itunes_artist_discography(itunes_discog_json, "Miles Davis");
    assert_eq!(discog.len(), 1);
    assert_eq!(discog[0].title, "Kind of Blue");
    assert_eq!(discog[0].release_year, Some(1959));
}

#[tokio::test]
async fn test_unified_in_progress_home_queues_and_diary_default_finished() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    // 1. Seed a feed article at 42% progress + a queued saved article at 30% progress + a fresh queued article
    let feed_url = Url::parse("https://notes.example/feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Notes Feed"), None).await.unwrap();
    let art_url = Url::parse("https://notes.example/half-read").unwrap();
    let art = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Half-Read Essay",
            url: &art_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    db.items()
        .update_with_source(
            &art.id,
            None,
            None,
            Some(0.42),
            crate::domain::ReadAtPatch::Unchanged,
            Some(crate::domain::ProgressSource::Reader),
        )
        .await
        .unwrap();

    let saved_in_prog_url = Url::parse("https://notes.example/queued-midway").unwrap();
    let (saved_in_prog, _) = db
        .items()
        .save_manual_with_tags(&saved_in_prog_url, "Queued Midway Paper", &[])
        .await
        .unwrap();
    db.items()
        .update_with_source(
            &saved_in_prog.id,
            None,
            None,
            Some(0.30),
            crate::domain::ReadAtPatch::Unchanged,
            Some(crate::domain::ProgressSource::Reader),
        )
        .await
        .unwrap();

    let saved_fresh_url = Url::parse("https://notes.example/queued-fresh").unwrap();
    let (saved_fresh, _) = db
        .items()
        .save_manual_with_tags(&saved_fresh_url, "Queued Fresh Article", &[])
        .await
        .unwrap();

    // 2. Log a started book (catalog work without reader progress) + a finished book
    let book_started = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "The Brothers Karamazov".to_owned(),
            creators: vec!["Fyodor Dostoevsky".to_owned()],
            ..Default::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(
            &book_started.id,
            crate::db::ConsumptionStatus::Started,
            Some("Part I"),
            None,
        )
        .await
        .unwrap();

    let book_finished = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Crime and Punishment".to_owned(),
            creators: vec!["Fyodor Dostoevsky".to_owned()],
            ..Default::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&book_finished.id, crate::db::ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();

    // 3. GET /api/v1/home includes unified in_progress (2 reader items + 1 started book),
    //    deduplicates shown in_progress from unread_items AND want_to_read_items,
    //    and filters recent_diary to Finished only.
    let home_json = read_body_json(get_authed(&app, "/api/v1/home", &cookie).await).await;
    let home_in_prog = home_json["in_progress"].as_array().unwrap();
    assert_eq!(home_in_prog.len(), 3);
    assert!(home_in_prog.iter().any(|r| r["source"] == "item" && r["item"]["id"] == art.id));
    assert!(
        home_in_prog.iter().any(|r| r["source"] == "item" && r["item"]["id"] == saved_in_prog.id)
    );
    assert!(
        home_in_prog
            .iter()
            .any(|r| r["source"] == "catalog" && r["entry"]["id"] == book_started.id)
    );

    let home_unread = home_json["unread_items"].as_array().unwrap();
    assert!(
        !home_unread.iter().any(|i| i["id"] == art.id),
        "in-progress feed item must be deduplicated from Home unread_items"
    );
    let home_wtr = home_json["want_to_read_items"].as_array().unwrap();
    assert!(
        !home_wtr.iter().any(|i| i["id"] == saved_in_prog.id),
        "in-progress queued item must be deduplicated from Home want_to_read_items preview"
    );
    assert!(home_wtr.iter().any(|i| i["id"] == saved_fresh.id));
    assert_eq!(home_json["want_to_read_total"], 2);

    let recent_diary = home_json["recent_diary"].as_array().unwrap();
    assert_eq!(recent_diary.len(), 1);
    assert_eq!(recent_diary[0]["entry"]["id"], book_finished.id);

    // 4. GET /api/v1/catalog/queues surfaces unified in_progress + Want queues without double-counting
    let queues_json =
        read_body_json(get_authed(&app, "/api/v1/catalog/queues", &cookie).await).await;
    let q_in_prog = queues_json["in_progress"].as_array().unwrap();
    assert_eq!(q_in_prog.len(), 3);
    // 2 want:read items + 2 non-queued in-progress items (art + book_started) = 4 unique items
    assert_eq!(queues_json["total_items"], 4);

    // 5. GET /api/v1/catalog/diary/page defaults to Finished, while ?status=all and ?status=started expose started logs
    let diary_default =
        read_body_json(get_authed(&app, "/api/v1/catalog/diary/page", &cookie).await).await;
    assert_eq!(diary_default["total_for_filter"], 1);
    assert_eq!(diary_default["entries"][0]["entry"]["id"], book_finished.id);

    let diary_started = read_body_json(
        get_authed(&app, "/api/v1/catalog/diary/page?status=started", &cookie).await,
    )
    .await;
    assert_eq!(diary_started["total_for_filter"], 1);
    assert_eq!(diary_started["entries"][0]["entry"]["id"], book_started.id);

    let diary_all =
        read_body_json(get_authed(&app, "/api/v1/catalog/diary/page?status=all", &cookie).await)
            .await;
    assert_eq!(diary_all["total_for_filter"], 2);

    // 6. POST /api/v1/catalog/entries/{id}/log with status=abandoned clears book_started from In Progress
    let skip_resp = post_json_authed(
        &app,
        &format!("/api/v1/catalog/entries/{}/log", book_started.id),
        &cookie,
        Some(&csrf),
        serde_json::json!({ "status": "abandoned" }),
    )
    .await;
    assert_eq!(skip_resp.status(), StatusCode::OK);
    let queues_after_skip =
        read_body_json(get_authed(&app, "/api/v1/catalog/queues", &cookie).await).await;
    assert!(
        !queues_after_skip["in_progress"]
            .as_array()
            .unwrap()
            .iter()
            .any(|r| r["source"] == "catalog" && r["entry"]["id"] == book_started.id),
        "logging abandoned must clear started catalog entry from in_progress"
    );
}

#[tokio::test]
async fn test_in_progress_api_boundaries_log_validation_and_up_next_isolation() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let feed_url = Url::parse("https://edges.example/feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Edges Feed"), None).await.unwrap();

    // 1. Progress threshold boundaries: 0.029 (out), 0.03 (in), 0.979 (in), 0.98 (out)
    let mut boundary_ids = Vec::new();
    for (slug, prog) in
        [("below", 0.029_f64), ("min-in", 0.03_f64), ("max-in", 0.979_f64), ("above", 0.98_f64)]
    {
        let u = Url::parse(&format!("https://edges.example/{slug}")).unwrap();
        let it = db
            .items()
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: &format!("Boundary {slug}"),
                url: &u,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        db.items()
            .update_with_source(
                &it.id,
                None,
                None,
                Some(prog),
                crate::domain::ReadAtPatch::Unchanged,
                Some(crate::domain::ProgressSource::Reader),
            )
            .await
            .unwrap();
        boundary_ids.push(it.id);
    }

    // 2. Overlap item: reader progress (0.40) + newer `started` log -> deduped as `source: "item"`
    //    with boosted `activity_at`, plus 3 more started books to exceed Home's 4-item cap
    let overlap_url = Url::parse("https://edges.example/overlap").unwrap();
    let overlap_item = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Overlap Article".to_owned().as_str(),
            url: &overlap_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    db.items()
        .update_with_source(
            &overlap_item.id,
            None,
            None,
            Some(0.40),
            crate::domain::ReadAtPatch::Unchanged,
            Some(crate::domain::ProgressSource::Reader),
        )
        .await
        .unwrap();
    let future_ts = chrono::Utc::now() + chrono::Duration::hours(2);
    db.catalog()
        .log_consumption(
            &overlap_item.id,
            crate::db::ConsumptionStatus::Started,
            Some("Started note"),
            Some(future_ts),
        )
        .await
        .unwrap();

    let mut started_books = Vec::new();
    for idx in 1..=3_i64 {
        let b = db
            .catalog()
            .upsert_entry(&crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: format!("Started Book {idx}"),
                ..Default::default()
            })
            .await
            .unwrap();
        db.catalog()
            .log_consumption(
                &b.id,
                crate::db::ConsumptionStatus::Started,
                None,
                Some(chrono::Utc::now() + chrono::Duration::minutes(idx)),
            )
            .await
            .unwrap();
        started_books.push(b.id);
    }
    // Also place started_books[0] into Want to Read so finishing it clears Want membership
    db.catalog()
        .add_collection_entry(crate::db::WANT_TO_READ_COLLECTION_ID, &started_books[0], None)
        .await
        .unwrap();

    // Seed a legacy `up_next` member and ensure it is completely hidden from Home and Queues
    let legacy_up_next = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Legacy Up Next Film".to_owned(),
            ..Default::default()
        })
        .await
        .unwrap();
    db.catalog()
        .add_collection_entry(crate::db::UP_NEXT_COLLECTION_ID, &legacy_up_next.id, None)
        .await
        .unwrap();

    // 3. Check /api/v1/catalog/queues: exact 6 in_progress (min-in, max-in, overlap, 3 books),
    //    overlap appears once as source="item" at index 0 due to boosted started ts,
    //    and up_next is hidden.
    let queues_json =
        read_body_json(get_authed(&app, "/api/v1/catalog/queues", &cookie).await).await;
    let q_in_prog = queues_json["in_progress"].as_array().unwrap();
    let q_ids: Vec<&str> = q_in_prog
        .iter()
        .map(|r| {
            if r["source"] == "item" {
                r["item"]["id"].as_str().unwrap()
            } else {
                r["entry"]["id"].as_str().unwrap()
            }
        })
        .collect();
    assert_eq!(q_ids.len(), 6);
    assert_eq!(q_ids[0], overlap_item.id, "newer started ts must boost overlap reader item first");
    assert_eq!(q_in_prog[0]["source"], "item");
    assert!(q_ids.contains(&boundary_ids[1].as_str()), "0.03 must be included");
    assert!(q_ids.contains(&boundary_ids[2].as_str()), "0.979 must be included");
    assert!(!q_ids.contains(&boundary_ids[0].as_str()), "0.029 must be excluded");
    assert!(!q_ids.contains(&boundary_ids[3].as_str()), "0.98 must be excluded");
    let queues_arr = queues_json["queues"].as_array().unwrap();
    assert!(!queues_arr.is_empty(), "want:read queue should be present");
    assert!(
        queues_arr.iter().all(|q| {
            q["collection"]["id"].as_str().expect("queue.collection.id")
                != crate::db::UP_NEXT_COLLECTION_ID
        }),
        "up_next must be hidden from queues"
    );

    // 4. Check /api/v1/home: capped at 4 in_progress, unshown 5th/6th reader items stay in unread_items,
    //    and legacy up_next does not inflate other_want_total
    let home_json = read_body_json(get_authed(&app, "/api/v1/home", &cookie).await).await;
    let home_in_prog = home_json["in_progress"].as_array().unwrap();
    assert_eq!(home_in_prog.len(), 4);
    assert_eq!(home_json["other_want_total"], 0);
    let home_unread_ids: Vec<&str> = home_json["unread_items"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|i| i["id"].as_str())
        .collect();
    assert!(
        home_unread_ids.contains(&boundary_ids[1].as_str())
            || home_unread_ids.contains(&boundary_ids[2].as_str()),
        "in-progress reader item past Home's 4-item cap must remain visible in unread_items"
    );

    // 5. POST /api/v1/catalog/entries/{id}/log validation: missing CSRF -> 403, invalid status -> 400
    let no_csrf = post_json_authed(
        &app,
        &format!("/api/v1/catalog/entries/{}/log", started_books[1]),
        &cookie,
        None,
        serde_json::json!({ "status": "abandoned" }),
    )
    .await;
    assert_eq!(no_csrf.status(), StatusCode::FORBIDDEN);

    let bad_status = post_json_authed(
        &app,
        &format!("/api/v1/catalog/entries/{}/log", started_books[1]),
        &cookie,
        Some(&csrf),
        serde_json::json!({ "status": "bogus_status" }),
    )
    .await;
    assert_eq!(bad_status.status(), StatusCode::BAD_REQUEST);

    // "skipped" alias must log `abandoned`, NOT coerce to `consumed`
    let skipped_resp = post_json_authed(
        &app,
        &format!("/api/v1/catalog/entries/{}/log", started_books[1]),
        &cookie,
        Some(&csrf),
        serde_json::json!({ "status": "skipped" }),
    )
    .await;
    assert_eq!(skipped_resp.status(), StatusCode::OK);
    let skipped_json = read_body_json(skipped_resp).await;
    assert_eq!(skipped_json["logged"]["status"], "abandoned");

    // Case-insensitive diary status filter (`?status=ALL`, `?status=Started`, `?status=SKIPPED`)
    let diary_skipped = read_body_json(
        get_authed(&app, "/api/v1/catalog/diary/page?status=SKIPPED", &cookie).await,
    )
    .await;
    assert_eq!(diary_skipped["total_for_filter"], 1);
    assert_eq!(diary_skipped["entries"][0]["entry"]["id"], started_books[1]);

    let diary_started_ci = read_body_json(
        get_authed(&app, "/api/v1/catalog/diary/page?status=Started", &cookie).await,
    )
    .await;
    assert!(diary_started_ci["total_for_filter"].as_u64().unwrap() >= 3);

    // 6. Finishing a queued started book with `rank_after: true` (with >=2 consumed books so
    //    `books` is rankable) returns `rank_slug = "books"` and removes it from Want
    let seed_finished_book = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Already Finished Seed Book".to_owned(),
            ..Default::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&seed_finished_book.id, crate::db::ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();
    let finish_book = post_json_authed(
        &app,
        &format!("/api/v1/catalog/entries/{}/log", started_books[0]),
        &cookie,
        Some(&csrf),
        serde_json::json!({ "status": "consumed", "rank_after": true }),
    )
    .await;
    assert_eq!(finish_book.status(), StatusCode::OK);
    let finish_book_json = read_body_json(finish_book).await;
    assert_eq!(finish_book_json["rank_slug"], "books");
    assert!(
        db.catalog()
            .want_entry_ids_in(std::slice::from_ref(&started_books[0]))
            .await
            .unwrap()
            .is_empty()
    );

    // 7. Finishing a live reader item via /entries/{id}/log archives `entry_state` & sets progress = 1.0
    let finish_reader = post_json_authed(
        &app,
        &format!("/api/v1/catalog/entries/{}/log", overlap_item.id),
        &cookie,
        Some(&csrf),
        serde_json::json!({ "status": "consumed" }),
    )
    .await;
    assert_eq!(finish_reader.status(), StatusCode::OK);
    let updated_reader = db.items().get(&overlap_item.id).await.unwrap().unwrap();
    assert_eq!(updated_reader.status, crate::domain::ItemStatus::Archived);
    assert!((updated_reader.read_progress - 1.0).abs() < 1e-6);

    let diary_all_ci =
        read_body_json(get_authed(&app, "/api/v1/catalog/diary/page?status=ALL", &cookie).await)
            .await;
    assert!(diary_all_ci["total_for_filter"].as_u64().unwrap() >= 5);
}
