#![allow(clippy::unwrap_used)]

use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use axum::{
    Router,
    body::{Body, to_bytes},
    extract::ConnectInfo,
    http::{Request, StatusCode, header},
    response::Response,
};
use tower::ServiceExt;
use url::Url;

use crate::{
    api::ApiRoutes,
    auth::pairing::PairingManager,
    config::Config,
    db::{ConsumptionStatus, ContentType, Db, EntryKind, FeedType, NewItem},
    scheduler::FeedFetcher,
    service::RocheService,
};

#[derive(Clone)]
struct DummyWebFetcher;

impl FeedFetcher for DummyWebFetcher {
    fn fetch_feed(
        &self,
        _url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
        std::future::ready(Ok(r#"<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Editorial Feed</title>
            <link>https://editorial.example</link>
            <description>Warm essays</description>
            <item>
              <title>Longform Essay</title>
              <link>https://editorial.example/essay-1</link>
              <description>Paragraph one.</description>
            </item>
          </channel>
        </rss>"#
            .to_string()))
    }

    fn fetch_html(
        &self,
        _url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> + Send {
        std::future::ready(Ok(Some(
            "<html><head><title>Saved Essay</title></head><body><script>evil()</script><p>Clean body.</p></body></html>"
                .to_string(),
        )))
    }

    fn fetch_pdf(
        &self,
        _url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<Option<Vec<u8>>>> + Send {
        std::future::ready(Ok(None))
    }
}

async fn setup_web_app(cfg: Config) -> (Db, Router) {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let svc = RocheService::new(db.clone(), DummyWebFetcher, cfg);
    let app = ApiRoutes::router_with_service(svc);
    (db, app)
}

fn extract_cookie_header(set_cookie: &str) -> String {
    set_cookie.split(';').next().unwrap().trim().to_string()
}

fn extract_csrf_token(html: &str) -> String {
    let needle = "data-csrf=\"";
    let start = html.find(needle).expect("body data-csrf attribute present") + needle.len();
    let end = html[start..].find('"').unwrap() + start;
    html[start..end].to_string()
}

async fn get_authed(app: &Router, uri: &str, cookie: &str) -> Response {
    app.clone()
        .oneshot(
            Request::builder().uri(uri).header(header::COOKIE, cookie).body(Body::empty()).unwrap(),
        )
        .await
        .unwrap()
}

fn encode_form(fields: &[(&str, &str)]) -> String {
    let mut ser = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in fields {
        ser.append_pair(k, v);
    }
    ser.finish()
}

async fn post_form(app: &Router, uri: &str, cookie: &str, fields: &[(&str, &str)]) -> Response {
    let body = encode_form(fields);
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(uri)
                .header(header::COOKIE, cookie)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(Body::from(body))
                .unwrap(),
        )
        .await
        .unwrap()
}

async fn login_browser(db: &Db, app: &Router) -> (String, String) {
    let code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
    let body = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("pairing_code", &code)
        .append_pair("device_name", "Test Browser")
        .finish();
    let mut req = Request::builder()
        .method("POST")
        .uri("/login")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header("x-forwarded-proto", "https")
        .body(Body::from(body))
        .unwrap();
    req.extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 40000)));
    let resp = app.clone().oneshot(req).await.unwrap();
    assert_eq!(resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(resp.headers().get(header::LOCATION).unwrap(), "/");

    let set_cookie = resp.headers().get(header::SET_COOKIE).unwrap().to_str().unwrap();
    assert!(set_cookie.contains("roche_session="));
    assert!(set_cookie.contains("HttpOnly"));
    assert!(set_cookie.contains("SameSite=Lax"));
    assert!(set_cookie.contains("Secure"));
    let cookie = extract_cookie_header(set_cookie);

    let home_resp = get_authed(app, "/", &cookie).await;
    assert_eq!(home_resp.status(), StatusCode::OK);
    let home_bytes = to_bytes(home_resp.into_body(), 256 * 1024).await.unwrap();
    let home_html = String::from_utf8(home_bytes.to_vec()).unwrap();
    let csrf = extract_csrf_token(&home_html);
    (cookie, csrf)
}

#[tokio::test]
async fn test_web_static_login_csrf_and_logout_revocation() {
    let (db, app) = setup_web_app(Config::default()).await;

    let css_resp = app
        .clone()
        .oneshot(Request::builder().uri("/static/roche.css").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(css_resp.status(), StatusCode::OK);
    assert_eq!(css_resp.headers().get(header::X_CONTENT_TYPE_OPTIONS).unwrap(), "nosniff");
    assert_eq!(css_resp.headers().get(header::X_FRAME_OPTIONS).unwrap(), "SAMEORIGIN");
    assert_eq!(css_resp.headers().get(header::REFERRER_POLICY).unwrap(), "same-origin");
    let css_body =
        String::from_utf8(to_bytes(css_resp.into_body(), 128 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(css_body.contains("#fbf7ed"));

    let js_resp = app
        .clone()
        .oneshot(Request::builder().uri("/static/roche.js").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(js_resp.status(), StatusCode::OK);

    let unauth = app
        .clone()
        .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(unauth.status(), StatusCode::SEE_OTHER);
    assert_eq!(unauth.headers().get(header::LOCATION).unwrap(), "/login");

    let (cookie, csrf) = login_browser(&db, &app).await;
    for path in ["/inbox", "/starred", "/feeds", "/lists", "/diary", "/queue", "/rank"] {
        let resp = get_authed(&app, path, &cookie).await;
        assert_eq!(resp.status(), StatusCode::OK, "expected 200 OK on {path}");
    }

    let missing_csrf = post_form(&app, "/web/lists/create", &cookie, &[("title", "My List")]).await;
    assert_eq!(missing_csrf.status(), StatusCode::FORBIDDEN);

    let bad_csrf = post_form(
        &app,
        "/web/lists/create",
        &cookie,
        &[("title", "My List"), ("_csrf", "deadbeef")],
    )
    .await;
    assert_eq!(bad_csrf.status(), StatusCode::FORBIDDEN);

    let logout_resp = post_form(&app, "/logout", &cookie, &[("_csrf", &csrf)]).await;
    assert_eq!(logout_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(logout_resp.headers().get(header::LOCATION).unwrap(), "/login");

    let after_logout = get_authed(&app, "/", &cookie).await;
    assert_eq!(after_logout.status(), StatusCode::SEE_OTHER);
    assert_eq!(after_logout.headers().get(header::LOCATION).unwrap(), "/login");
}

#[tokio::test]
async fn test_tailscale_auto_auth_strictly_requires_loopback_and_allowlist() {
    let mut cfg = Config::default();
    cfg.tailscale.enabled = true;
    cfg.tailscale.auto_auth_users = vec!["reader@example.ts.net".to_string()];
    let (_db, app) = setup_web_app(cfg).await;

    let mut remote_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .body(Body::empty())
        .unwrap();
    remote_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(100, 64, 0, 9)), 51234)));
    let remote_resp = app.clone().oneshot(remote_req).await.unwrap();
    assert_eq!(remote_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(remote_resp.headers().get(header::LOCATION).unwrap(), "/login");

    let mut stranger_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "intruder@example.ts.net")
        .body(Body::empty())
        .unwrap();
    stranger_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51235)));
    assert_eq!(app.clone().oneshot(stranger_req).await.unwrap().status(), StatusCode::SEE_OTHER);

    let mut forged_post = Request::builder()
        .method("POST")
        .uri("/web/1001/toggle")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body(Body::from("enabled=true"))
        .unwrap();
    forged_post
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51236)));
    assert_eq!(app.clone().oneshot(forged_post).await.unwrap().status(), StatusCode::UNAUTHORIZED);

    let mut valid_ts_req = Request::builder()
        .uri("/")
        .header("Tailscale-User-Login", "READER@example.ts.net")
        .body(Body::empty())
        .unwrap();
    valid_ts_req
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51237)));
    let valid_ts_resp = app.clone().oneshot(valid_ts_req).await.unwrap();
    assert_eq!(valid_ts_resp.status(), StatusCode::OK);
    assert!(
        valid_ts_resp
            .headers()
            .get(header::SET_COOKIE)
            .unwrap()
            .to_str()
            .unwrap()
            .contains("roche_session=")
    );
}

#[tokio::test]
async fn test_web_1001_albums_finish_rank_and_abandon_flows() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let toggle_resp =
        post_form(&app, "/web/1001/toggle", &cookie, &[("_csrf", &csrf), ("enabled", "true")])
            .await;
    assert_eq!(toggle_resp.status(), StatusCode::SEE_OTHER);

    let state1 = db.catalog().get_1001_generator_state().await.unwrap();
    assert!(state1.enabled);
    let first_id = state1.current_entry.as_ref().unwrap().id.clone();

    let finish1_resp = post_form(
        &app,
        "/web/1001/finish",
        &cookie,
        &[("_csrf", &csrf), ("notes", "Great opener <script>xss()</script>")],
    )
    .await;
    assert_eq!(finish1_resp.status(), StatusCode::SEE_OTHER);
    assert!(
        finish1_resp
            .headers()
            .get(header::LOCATION)
            .unwrap()
            .to_str()
            .unwrap()
            .starts_with("/?notice=")
    );

    let state2 = db.catalog().get_1001_generator_state().await.unwrap();
    let second_id = state2.current_entry.as_ref().unwrap().id.clone();
    assert_ne!(first_id, second_id);
    assert_eq!(state2.consumed_count, 1);

    let finish2_resp = post_form(&app, "/web/1001/finish", &cookie, &[("_csrf", &csrf)]).await;
    assert_eq!(finish2_resp.status(), StatusCode::SEE_OTHER);
    let loc2 = finish2_resp.headers().get(header::LOCATION).unwrap().to_str().unwrap();
    assert!(loc2.starts_with(&format!("/rank/albums?focus={second_id}")), "got: {loc2}");

    let ord_json = serde_json::to_string(&vec![vec![&second_id], vec![&first_id]]).unwrap();
    let rank_post_resp = post_form(
        &app,
        "/rank/albums",
        &cookie,
        &[
            ("_csrf", &csrf),
            ("ordering_json", &ord_json),
            ("want", "false"),
            ("focus", &second_id),
            ("round", "0"),
        ],
    )
    .await;
    assert_eq!(rank_post_resp.status(), StatusCode::SEE_OTHER);

    let ranked_albums = db.catalog().get_ranked_collection("1001-albums", false).await.unwrap();
    assert_eq!(ranked_albums.rows.len(), 2);
    assert_eq!(ranked_albums.rows[0].entry.id, second_id);

    let third_id = db.catalog().get_1001_generator_state().await.unwrap().current_entry.unwrap().id;
    let abandon_resp = post_form(
        &app,
        "/web/1001/abandon",
        &cookie,
        &[("_csrf", &csrf), ("notes", "Not my style")],
    )
    .await;
    assert_eq!(abandon_resp.status(), StatusCode::SEE_OTHER);
    let state4 = db.catalog().get_1001_generator_state().await.unwrap();
    assert_eq!(state4.consumed_count, 2);
    assert_eq!(state4.abandoned_count, 1);
    assert_ne!(state4.current_entry.as_ref().unwrap().id, third_id);

    let diary_resp = get_authed(&app, "/diary", &cookie).await;
    let diary_html =
        String::from_utf8(to_bytes(diary_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(!diary_html.contains("<script>xss()</script>"));
    assert!(diary_html.contains("&lt;script&gt;xss()&lt;/script&gt;"));
}

#[tokio::test]
async fn test_web_feed_completion_auto_logging_podcast_and_article() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let podcast_feed_url = Url::parse("https://podcasts.example/rss").unwrap();
    let podcast_feed = db
        .feeds()
        .create(FeedType::Rss, &podcast_feed_url, Some("Systems Cast"), None)
        .await
        .unwrap();
    let ep_url = Url::parse("https://podcasts.example/ep-1").unwrap();
    let ep_item = db
        .items()
        .create(&NewItem {
            feed_id: Some(&podcast_feed.id),
            title: "Episode 1: Storage Engines",
            url: &ep_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Audio,
        })
        .await
        .unwrap();

    let prog_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/web/items/{}/progress", ep_item.id))
                .header(header::COOKIE, &cookie)
                .header("x-roche-csrf", &csrf)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(Body::from("progress=0.99"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(prog_resp.status(), StatusCode::NO_CONTENT);

    let read_url = Url::parse("https://blog.example/read").unwrap();
    let read_article = db
        .items()
        .create(&NewItem {
            feed_id: None,
            title: "Read Essay",
            url: &read_url,
            author: Some("A. Writer"),
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    let unread_url = Url::parse("https://blog.example/unread").unwrap();
    let unread_article = db
        .items()
        .create(&NewItem {
            feed_id: None,
            title: "Unread Skipped Essay",
            url: &unread_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();

    assert_eq!(
        post_form(
            &app,
            &format!("/web/items/{}/archive", read_article.id),
            &cookie,
            &[("_csrf", &csrf), ("progress", "0.95")],
        )
        .await
        .status(),
        StatusCode::SEE_OTHER
    );
    assert_eq!(
        post_form(
            &app,
            &format!("/web/items/{}/archive", unread_article.id),
            &cookie,
            &[("_csrf", &csrf)],
        )
        .await
        .status(),
        StatusCode::SEE_OTHER
    );

    let diary = db.catalog().list_diary(None, None, 20).await.unwrap();
    assert!(
        diary.iter().any(|d| d.entry.id == ep_item.id && d.status == ConsumptionStatus::Consumed)
    );
    assert!(diary.iter().any(|d| d.entry.id == read_article.id));
    assert!(!diary.iter().any(|d| d.entry.id == unread_article.id));
}

#[tokio::test]
async fn test_web_video_archive_never_logs_while_explicit_finish_logs() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let vid1_url = Url::parse("https://videos.example/v1").unwrap();
    let vid_archived = db
        .items()
        .create(&NewItem {
            feed_id: None,
            title: "Dismissed Video",
            url: &vid1_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Video,
        })
        .await
        .unwrap();
    let vid2_url = Url::parse("https://videos.example/v2").unwrap();
    let vid_finished = db
        .items()
        .create(&NewItem {
            feed_id: None,
            title: "Watched Lecture",
            url: &vid2_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Video,
        })
        .await
        .unwrap();

    let _ = post_form(
        &app,
        &format!("/web/items/{}/archive", vid_archived.id),
        &cookie,
        &[("_csrf", &csrf), ("progress", "1.0")],
    )
    .await;
    assert_eq!(
        post_form(
            &app,
            &format!("/web/items/{}/finish", vid_finished.id),
            &cookie,
            &[("_csrf", &csrf)],
        )
        .await
        .status(),
        StatusCode::SEE_OTHER
    );

    let diary = db.catalog().list_diary(None, None, 20).await.unwrap();
    assert!(!diary.iter().any(|d| d.entry.id == vid_archived.id));
    assert!(diary.iter().any(|d| d.entry.id == vid_finished.id));
}

#[tokio::test]
async fn test_web_want_queue_local_priority_noscript_and_entry_merge() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    for title in ["Stalker", "Solaris"] {
        let resp = post_form(
            &app,
            "/web/log",
            &cookie,
            &[
                ("_csrf", &csrf),
                ("kind", "movie"),
                ("action", "want"),
                ("title", title),
                ("creators", "Andrei Tarkovsky"),
            ],
        )
        .await;
        assert_eq!(resp.status(), StatusCode::SEE_OTHER);
    }

    let want_detail = db.catalog().get_ranked_collection("movies", true).await.unwrap();
    assert_eq!(want_detail.rows.len(), 2);
    let m1 = want_detail.rows[0].entry.id.clone();
    let m2 = want_detail.rows[1].entry.id.clone();

    // Test `<noscript>` `pos_<id>` override precedence over default `ordering_json`
    let default_ord = serde_json::to_string(&vec![vec![&m1], vec![&m2]]).unwrap();
    let pos_m1 = format!("pos_{m1}");
    let pos_m2 = format!("pos_{m2}");
    let prio_resp = post_form(
        &app,
        "/rank/want%3Amovies",
        &cookie,
        &[
            ("_csrf", &csrf),
            ("ordering_json", &default_ord),
            (&pos_m2, "1"),
            (&pos_m1, "2"),
            ("want", "true"),
            ("round", "0"),
        ],
    )
    .await;
    assert_eq!(prio_resp.status(), StatusCode::SEE_OTHER);

    let want_ranked = db.catalog().get_ranked_collection("movies", true).await.unwrap();
    assert_eq!(want_ranked.rows[0].entry.id, m2);
    assert!(want_ranked.rows[0].tier.is_none(), "want queues never assign S-D tiers");

    let _ = post_form(
        &app,
        "/web/log",
        &cookie,
        &[
            ("_csrf", &csrf),
            ("kind", "movie"),
            ("action", "consumed"),
            ("title", "Solaris (Duplicate)"),
        ],
    )
    .await;
    let found_dup = db
        .catalog()
        .search_entries(Some("Solaris (Duplicate)"), Some(EntryKind::Movie), 5)
        .await
        .unwrap();
    let dup_id = found_dup[0].id.clone();

    let merge_resp = post_form(
        &app,
        &format!("/web/entries/{dup_id}/merge"),
        &cookie,
        &[("_csrf", &csrf), ("canonical_query", &m2)],
    )
    .await;
    assert_eq!(merge_resp.status(), StatusCode::SEE_OTHER);
    assert!(
        merge_resp
            .headers()
            .get(header::LOCATION)
            .unwrap()
            .to_str()
            .unwrap()
            .starts_with(&format!("/entries/{m2}"))
    );
}

#[tokio::test]
async fn test_web_filtered_inbox_batch_archive_unconsumed_focus_and_restart_csrf() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let feed_url = Url::parse("https://blog.example/rss").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Essay Feed"), None).await.unwrap();
    let art_url = Url::parse("https://blog.example/a1").unwrap();
    let art = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Keep Me Unread",
            url: &art_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    let vid_url = Url::parse("https://videos.example/v-only").unwrap();
    let vid = db
        .items()
        .create(&NewItem {
            feed_id: None,
            title: "Filtered Video",
            url: &vid_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Video,
        })
        .await
        .unwrap();

    // Feed filter stays preserved in Inbox filter links & search form
    let feed_inbox =
        get_authed(&app, &format!("/inbox?status=all&feed={}", feed.id), &cookie).await;
    let feed_inbox_html =
        String::from_utf8(to_bytes(feed_inbox.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(feed_inbox_html.contains(&format!("name=\"feed\" value=\"{}\"", feed.id)));

    // Scoped batch archive with explicit `ids` archives only the filtered video, leaving article unread
    let batch_resp = post_form(
        &app,
        "/web/items/archive-batch",
        &cookie,
        &[("_csrf", &csrf), ("ids", &vid.id), ("return_to", "/inbox?kind=video")],
    )
    .await;
    assert_eq!(batch_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(
        db.items().get(&art.id).await.unwrap().unwrap().status,
        crate::db::ItemStatus::Unread
    );
    assert_eq!(
        db.items().get(&vid.id).await.unwrap().unwrap().status,
        crate::db::ItemStatus::Archived
    );

    // Unconsumed item omits "Compare" in Reader and shows non-member guidance on /rank/articles?focus=...
    let reader_resp = get_authed(&app, &format!("/reader/{}", art.id), &cookie).await;
    let reader_html =
        String::from_utf8(to_bytes(reader_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(!reader_html.contains(">Compare</a>"));
    let rank_focus_resp =
        get_authed(&app, &format!("/rank/articles?focus={}", art.id), &cookie).await;
    let rank_focus_html = String::from_utf8(
        to_bytes(rank_focus_resp.into_body(), 256 * 1024).await.unwrap().to_vec(),
    )
    .unwrap();
    assert!(rank_focus_html.contains("not in <strong>Articles</strong> yet"));

    // Simulated server restart preserves session + CSRF token validity without requiring tab reload
    let restarted_app = ApiRoutes::router_with_service(RocheService::new(
        db.clone(),
        DummyWebFetcher,
        Config::default(),
    ));
    let post_after_restart = post_form(
        &restarted_app,
        "/web/lists/create",
        &cookie,
        &[("_csrf", &csrf), ("title", "Survives Restart")],
    )
    .await;
    assert_eq!(post_after_restart.status(), StatusCode::SEE_OTHER);
}

#[tokio::test]
async fn test_web_cache_control_tailscale_session_reuse_and_explicit_logout() {
    let mut cfg = Config::default();
    cfg.tailscale.enabled = true;
    cfg.tailscale.auto_auth_users = vec!["reader@example.ts.net".to_string()];
    let (db, app) = setup_web_app(cfg).await;

    // Static assets keep public caching, while HTML pages enforce `private, no-store`
    let js_resp = app
        .clone()
        .oneshot(Request::builder().uri("/static/roche.js").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(js_resp.headers().get(header::CACHE_CONTROL).unwrap(), "public, max-age=3600");

    let ts_get = |cookie_hdr: Option<String>| {
        let mut builder =
            Request::builder().uri("/").header("Tailscale-User-Login", "reader@example.ts.net");
        if let Some(c) = cookie_hdr {
            builder = builder.header(header::COOKIE, c);
        }
        let mut req = builder.body(Body::empty()).unwrap();
        req.extensions_mut()
            .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51900)));
        req
    };

    let r1 = app.clone().oneshot(ts_get(None)).await.unwrap();
    assert_eq!(r1.status(), StatusCode::OK);
    assert_eq!(r1.headers().get(header::CACHE_CONTROL).unwrap(), "private, no-store");
    let cookie1 =
        extract_cookie_header(r1.headers().get(header::SET_COOKIE).unwrap().to_str().unwrap());
    let html1 =
        String::from_utf8(to_bytes(r1.into_body(), 256 * 1024).await.unwrap().to_vec()).unwrap();
    let csrf = extract_csrf_token(&html1);

    // Second unauthenticated Tailscale GET reuses the cached session token instead of minting duplicates
    let r2 = app.clone().oneshot(ts_get(None)).await.unwrap();
    assert_eq!(r2.status(), StatusCode::OK);
    let cookie2 =
        extract_cookie_header(r2.headers().get(header::SET_COOKIE).unwrap().to_str().unwrap());
    assert_eq!(cookie1, cookie2);
    assert_eq!(db.auth().list_devices().await.unwrap().len(), 1);

    // Explicit POST /logout revokes the session AND sets `roche_logged_out=1`
    let logout_resp = post_form(&app, "/logout", &cookie1, &[("_csrf", &csrf)]).await;
    assert_eq!(logout_resp.status(), StatusCode::SEE_OTHER);
    let set_cookies: Vec<String> = logout_resp
        .headers()
        .get_all(header::SET_COOKIE)
        .iter()
        .map(|v| extract_cookie_header(v.to_str().unwrap()))
        .collect();
    assert!(set_cookies.iter().any(|c| c == "roche_logged_out=1"));

    // With `roche_logged_out=1`, Tailscale auto-auth does NOT silently re-login on `GET /`
    let after_logout =
        app.clone().oneshot(ts_get(Some("roche_logged_out=1".to_string()))).await.unwrap();
    assert_eq!(after_logout.status(), StatusCode::SEE_OTHER);
    assert_eq!(after_logout.headers().get(header::LOCATION).unwrap(), "/login");

    // Explicit `POST /login/tailscale` clears `roche_logged_out` and issues a fresh session
    let mut ts_relogin = Request::builder()
        .method("POST")
        .uri("/login/tailscale")
        .header("Tailscale-User-Login", "reader@example.ts.net")
        .header("sec-fetch-site", "same-origin")
        .header(header::COOKIE, "roche_logged_out=1")
        .body(Body::empty())
        .unwrap();
    ts_relogin
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 51901)));
    let relogin_resp = app.clone().oneshot(ts_relogin).await.unwrap();
    assert_eq!(relogin_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(relogin_resp.headers().get(header::LOCATION).unwrap(), "/");
}

#[tokio::test]
async fn test_entry_detail_excludes_tag_and_feed_collections_from_rankings() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let feed_url = Url::parse("https://essays.example/rss").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("UniqueFeedTitle"), None).await.unwrap();
    let art_url = Url::parse("https://essays.example/post-1").unwrap();
    let art = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Tagged Essay",
            url: &art_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    let tag = db.tags().create("UniqueTagBadge", None).await.unwrap();
    db.tags().add_to_item(&art.id, &tag.id).await.unwrap();
    db.catalog().log_consumption(&art.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

    let detail_resp = get_authed(&app, &format!("/entries/{}", art.id), &cookie).await;
    assert_eq!(detail_resp.status(), StatusCode::OK);
    let html =
        String::from_utf8(to_bytes(detail_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    // Appears in system `Articles` ranking, never in `tag:` or `feed` collections!
    assert!(html.contains(">Articles</a>"));
    assert!(!html.contains(">UniqueTagBadge</a>"));
}

#[tokio::test]
async fn test_web_csp_and_sanitized_reader_image_privacy_attributes() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let art_url = Url::parse("https://essays.example/with-image").unwrap();
    let art = db
        .items()
        .create(&NewItem {
            feed_id: None,
            title: "Illustrated Essay",
            url: &art_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    sqlx::query("INSERT OR REPLACE INTO entry_content (entry_id, content_html) VALUES (?, ?)")
        .bind(&art.id)
        .bind("<p>Body with <img src=\"/fig1.png\" referrerpolicy=\"unsafe-url\"></p>")
        .execute(db.pool())
        .await
        .unwrap();

    let resp = get_authed(&app, &format!("/reader/{}", art.id), &cookie).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let csp = resp
        .headers()
        .get(header::CONTENT_SECURITY_POLICY)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    assert!(csp.contains("default-src 'self'"));
    assert!(csp.contains("script-src 'self'"));
    assert!(csp.contains("frame-ancestors 'sameorigin'"));

    let html =
        String::from_utf8(to_bytes(resp.into_body(), 256 * 1024).await.unwrap().to_vec()).unwrap();
    assert!(html.contains("src=\"https://essays.example/fig1.png\""));
    assert!(html.contains("referrerpolicy=\"no-referrer\""));
    assert!(html.contains("loading=\"lazy\""));
    assert!(!html.contains("unsafe-url"));
}
