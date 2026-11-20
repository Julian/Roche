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
        .header("sec-fetch-site", "same-origin")
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
    for path in ["/inbox", "/lists/starred", "/feeds", "/lists", "/diary", "/queue"] {
        let resp = get_authed(&app, path, &cookie).await;
        assert_eq!(resp.status(), StatusCode::OK, "expected 200 OK on {path}");
    }
    let starred_redir = get_authed(&app, "/starred", &cookie).await;
    assert_eq!(starred_redir.status(), StatusCode::SEE_OTHER);
    assert_eq!(starred_redir.headers().get(header::LOCATION).unwrap(), "/lists/starred");

    let rank_redir = get_authed(&app, "/rank", &cookie).await;
    assert_eq!(rank_redir.status(), StatusCode::SEE_OTHER);
    assert_eq!(rank_redir.headers().get(header::LOCATION).unwrap(), "/lists");

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
        .uri("/web/lists/test_list/generator/toggle")
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

async fn seed_album_queue_pool(db: &crate::db::Db) {
    let pool = db
        .catalog()
        .create_manual_collection(
            "Album Queue",
            Some("album_queue"),
            crate::db::CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    for (pos, title) in
        [(1.0, "Kind of Blue"), (2.0, "Time Out"), (3.0, "Mingus Ah Um"), (4.0, "Blue Train")]
    {
        db.catalog()
            .upsert_entry(&crate::db::UpsertEntryInput {
                kind: Some(crate::db::EntryKind::Album),
                title: title.to_owned(),
                parent_collection_id: Some(pool.id.clone()),
                parent_position: Some(pos),
                ..Default::default()
            })
            .await
            .unwrap();
    }
}

#[tokio::test]
async fn test_web_collection_generator_finish_rank_and_abandon_flows() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;
    seed_album_queue_pool(&db).await;

    let toggle_resp = post_form(
        &app,
        "/web/lists/album_queue/generator/toggle",
        &cookie,
        &[("_csrf", &csrf), ("enabled", "true")],
    )
    .await;
    assert_eq!(toggle_resp.status(), StatusCode::SEE_OTHER);

    let state1 = db.catalog().get_collection_generator("album_queue").await.unwrap();
    assert!(state1.enabled);
    let first_id = state1.current_entry.as_ref().unwrap().id.clone();

    let finish1_resp = post_form(
        &app,
        "/web/lists/album_queue/generator/finish",
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

    let state2 = db.catalog().get_collection_generator("album_queue").await.unwrap();
    let second_id = state2.current_entry.as_ref().unwrap().id.clone();
    assert_ne!(first_id, second_id);
    assert_eq!(state2.consumed_count, 1);

    let finish2_resp =
        post_form(&app, "/web/lists/album_queue/generator/finish", &cookie, &[("_csrf", &csrf)])
            .await;
    assert_eq!(finish2_resp.status(), StatusCode::SEE_OTHER);
    let loc2 = finish2_resp.headers().get(header::LOCATION).unwrap().to_str().unwrap();
    assert!(loc2.starts_with(&format!("/rank/album_queue?focus={second_id}")), "got: {loc2}");

    let ord_json = serde_json::to_string(&vec![vec![&second_id], vec![&first_id]]).unwrap();
    let rank_post_resp = post_form(
        &app,
        "/rank/album_queue",
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

    let ranked_albums = db.catalog().get_ranked_collection("album-queue", false).await.unwrap();
    assert_eq!(ranked_albums.rows.len(), 2);
    assert_eq!(ranked_albums.rows[0].entry.id, second_id);

    let third_id = db
        .catalog()
        .get_collection_generator("album_queue")
        .await
        .unwrap()
        .current_entry
        .unwrap()
        .id;
    let abandon_resp = post_form(
        &app,
        "/web/lists/album_queue/generator/abandon",
        &cookie,
        &[("_csrf", &csrf), ("notes", "Not my style")],
    )
    .await;
    assert_eq!(abandon_resp.status(), StatusCode::SEE_OTHER);
    let state4 = db.catalog().get_collection_generator("album_queue").await.unwrap();
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
    let raw_html = "<p>Body with <img src=\"/fig1.png\" referrerpolicy=\"unsafe-url\"></p>";
    sqlx::query!(
        "INSERT OR REPLACE INTO entry_content (entry_id, content_html) VALUES (?, ?)",
        art.id,
        raw_html,
    )
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

fn ident(scheme: &str, value: &str) -> crate::db::EntryIdentifier {
    crate::db::EntryIdentifier { scheme: scheme.to_owned(), value: value.to_owned() }
}

#[tokio::test]
async fn test_web_music_links_only_show_wikipedia_and_spotify_with_lucide_icons() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let pool = db
        .catalog()
        .create_manual_collection(
            "Album Draw",
            Some("album_draw"),
            crate::db::CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();

    let album1 = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "21".to_owned(),
            creators: vec!["Adele".to_owned()],
            parent_collection_id: Some(pool.id.clone()),
            parent_position: Some(1.0),
            identifiers: vec![
                ident("wikipedia", "21_(Adele_album)"),
                ident("spotify", "spotify:album:5duyQokC4FMcWPYTV9Gpf9"),
                ident("youtube_music", "OLAK5uy_lT9WWb7WZ6MOzwNkItv6Azu835h17W_L0"),
                ident("apple_music", "1544491232"),
                ident("tidal", "165813921"),
                ident("deezer", "746059"),
                ident("qobuz", "0634904052065"),
                ident("amazon_music", "B08QDQMVJ1"),
                ident("musicbrainz_release", "8ef859e3-3204-4b23-85c7-0f3202d8a812"),
                ident("discogs", "2666996"),
            ],
            ..Default::default()
        })
        .await
        .unwrap();

    let toggle = post_form(
        &app,
        "/web/lists/album_draw/generator/toggle",
        &cookie,
        &[("_csrf", &csrf), ("enabled", "true")],
    )
    .await;
    assert_eq!(toggle.status(), StatusCode::SEE_OTHER);

    let artists = db.catalog().find_artist_ids_by_names(&["Adele".to_owned()]).await.unwrap();
    let adele_id = artists.get("adele").expect("synthesized artist entry for Adele");
    let expected_artist_link = format!("<a href=\"/entries/{adele_id}\">Adele</a>");

    for path in ["/", "/lists/album_draw", &format!("/entries/{}", album1.id)] {
        let resp = get_authed(&app, path, &cookie).await;
        assert_eq!(resp.status(), StatusCode::OK);
        let html =
            String::from_utf8(to_bytes(resp.into_body(), 256 * 1024).await.unwrap().to_vec())
                .unwrap();

        assert!(html.contains(&expected_artist_link), "missing artist link on {path}");
        assert!(html.contains("https://en.wikipedia.org/wiki/21_(Adele_album)"));
        assert!(html.contains("https://open.spotify.com/album/5duyQokC4FMcWPYTV9Gpf9"));
        assert!(html.contains("<svg class=\"icon\""));
        if path.starts_with("/entries/") {
            assert!(html.contains("<span>Wikipedia</span>"));
            assert!(html.contains("<span>Spotify</span>"));
        } else {
            assert!(html.contains("title=\"Wikipedia\""));
            assert!(html.contains("title=\"Listen on Spotify\""));
        }
        assert!(!html.contains(">Pause draw<"), "draw card must not show Pause draw on {path}");
        if path == "/" {
            assert!(!html.contains("/generator/toggle"));
        } else if path == "/lists/album_draw" {
            assert!(html.contains(">Pause Draw</button>"));
        }

        for unwanted in [
            "YouTube Music",
            "Apple Music",
            "Tidal",
            "Deezer",
            "Qobuz",
            "Amazon Music",
            "MusicBrainz",
            "Discogs",
            "music.apple.com",
            "tidal.com",
            "deezer.com",
            "open.qobuz.com",
            "music.youtube.com",
            "music.amazon.com",
        ] {
            assert!(!html.contains(unwanted), "unexpected '{unwanted}' rendered on {path}");
        }
    }
}

#[tokio::test]
async fn test_web_unused_music_suppression_spotify_dedup_and_non_music_icons() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let album1 = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "21".to_owned(),
            identifiers: vec![ident("wikipedia", "21_(Adele_album)")],
            ..Default::default()
        })
        .await
        .unwrap();
    let album2 = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Tidal Only Album".to_owned(),
            identifiers: vec![ident("apple_music", "999999"), ident("tidal", "888888")],
            ..Default::default()
        })
        .await
        .unwrap();

    let a2_resp = get_authed(&app, &format!("/entries/{}", album2.id), &cookie).await;
    let a2_html =
        String::from_utf8(to_bytes(a2_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(!a2_html.contains("external-links"));
    assert!(!a2_html.contains("music.apple.com"));
    assert!(!a2_html.contains("tidal.com"));

    db.catalog()
        .log_consumption(&album1.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&album2.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();
    let rank_resp = get_authed(&app, "/rank/albums", &cookie).await;
    let rank_html =
        String::from_utf8(to_bytes(rank_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(rank_html.contains("data-open-url=\"https://en.wikipedia.org/wiki/21_(Adele_album)\""));
    assert!(!rank_html.contains("music.apple.com"));
    assert!(!rank_html.contains("tidal.com"));

    // Quick-logging a Spotify URL deduplicates cleanly into a single Spotify link
    let ql_resp = post_form(
        &app,
        "/web/log",
        &cookie,
        &[
            ("_csrf", &csrf),
            ("kind", "album"),
            ("action", "want"),
            ("title", "The Dark Side of the Moon"),
            ("identifier", "https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv?si=abc123"),
        ],
    )
    .await;
    assert_eq!(ql_resp.status(), StatusCode::SEE_OTHER);
    let dsotm = db
        .catalog()
        .search_entries(Some("The Dark Side of the Moon"), Some(EntryKind::Album), 5)
        .await
        .unwrap();
    let dsotm_resp = get_authed(&app, &format!("/entries/{}", dsotm[0].id), &cookie).await;
    let dsotm_html =
        String::from_utf8(to_bytes(dsotm_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(dsotm_html.contains("https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv"));
    assert!(dsotm_html.contains("<span>Spotify</span>"));
    assert!(!dsotm_html.contains("Open Source"));

    // Non-music entries still show their non-music external identifiers with Lucide icons
    let movie = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Stalker".to_owned(),
            identifiers: vec![
                ident("wikipedia", "Stalker_(1979_film)"),
                ident("imdb", "tt0079944"),
            ],
            ..Default::default()
        })
        .await
        .unwrap();
    let movie_resp = get_authed(&app, &format!("/entries/{}", movie.id), &cookie).await;
    let movie_html =
        String::from_utf8(to_bytes(movie_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(movie_html.contains("<span>Wikipedia</span>"));
    assert!(movie_html.contains("<span>IMDb</span>"));
    assert!(movie_html.contains("<svg class=\"icon\""));
}

#[tokio::test]
async fn test_entry_detail_renders_artwork_and_metadata_genres() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let album = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Kind of Blue".to_owned(),
            creators: vec!["Miles Davis".to_owned()],
            release_year: Some(1959),
            thumbnail_url: Some("https://i.scdn.co/image/ab67616d0000b273kindofblue".to_owned()),
            metadata: Some(serde_json::json!({
                "genres": ["Modal Jazz", "Cool Jazz"],
                "styles": ["Post-Bop", "modal jazz"]
            })),
            ..Default::default()
        })
        .await
        .unwrap();

    let resp = get_authed(&app, &format!("/entries/{}", album.id), &cookie).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let html =
        String::from_utf8(to_bytes(resp.into_body(), 256 * 1024).await.unwrap().to_vec()).unwrap();
    assert!(html.contains("<div class=\"entry-hero\">"));
    assert!(html.contains("class=\"entry-artwork\""));
    assert!(html.contains("src=\"https://i.scdn.co/image/ab67616d0000b273kindofblue\""));
    assert!(html.contains("referrerpolicy=\"no-referrer\""));
    assert!(html.contains("<dt>Genres</dt><dd>Modal Jazz · Cool Jazz · Post-Bop</dd>"));
}

#[tokio::test]
async fn test_lists_overview_catalog_search_and_kind_filter() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let album = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Vespertine".to_owned(),
            creators: vec!["Björk".to_owned()],
            release_year: Some(2001),
            ..Default::default()
        })
        .await
        .unwrap();
    let movie = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Dancer in the Dark".to_owned(),
            creators: vec!["Lars von Trier".to_owned()],
            release_year: Some(2000),
            ..Default::default()
        })
        .await
        .unwrap();

    let artists = db.catalog().find_artist_ids_by_names(&["Björk".to_owned()]).await.unwrap();
    let bjork_id = artists.get("björk").expect("synthesized artist for Björk");

    let q_resp = get_authed(&app, "/lists?q=Vespertine", &cookie).await;
    assert_eq!(q_resp.status(), StatusCode::OK);
    let q_html =
        String::from_utf8(to_bytes(q_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(q_html.contains("Search Results (1)"));
    assert!(q_html.contains(&format!("/entries/{}\">Vespertine</a>", album.id)));
    assert!(q_html.contains(&format!("<a href=\"/entries/{bjork_id}\">Björk</a>")));
    assert!(q_html.contains("(2001)"));
    assert!(q_html.contains("<span class=\"badge\">Album</span>"));
    assert!(!q_html.contains("Dancer in the Dark"));

    let kind_resp = get_authed(&app, "/lists?kind=artist", &cookie).await;
    let kind_html =
        String::from_utf8(to_bytes(kind_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(kind_html.contains(&format!("/entries/{bjork_id}\">Björk</a>")));
    assert!(!kind_html.contains(&format!("/entries/{}\">Dancer in the Dark</a>", movie.id)));

    let bogus_resp = get_authed(&app, "/lists?q=Vespertine&kind=bogus", &cookie).await;
    let bogus_html =
        String::from_utf8(to_bytes(bogus_resp.into_body(), 256 * 1024).await.unwrap().to_vec())
            .unwrap();
    assert!(bogus_html.contains("Unknown entry kind filter &#39;bogus&#39;"));
    assert!(bogus_html.contains("Search Results (0)"));
    assert!(!bogus_html.contains(&format!("/entries/{}\">Vespertine</a>", album.id)));
}

#[test]
fn test_artist_enrichment_wikipedia_and_spotify_parsers() {
    use super::artist_enrich::{
        WikipediaSearchHit, encode_wiki_path_segment, extract_spotify_artist_uri_from_album_html,
        parse_wikipedia_artist_summary_json, select_best_artist_wikipedia_slug,
    };
    use super::links::sanitize_non_local_href;

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
    // Equal-score tie breaks in favor of Wikipedia's top-ranked hit (`Nirvana (band)`)
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

    // `<script>if (x<a && y>z)` must not corrupt anchor scanning or numeric HTML entity decoding
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

    // Mid-paragraph "debut album by" and "step by step" must NOT reject valid artist summaries
    let arctic_json = r#"{"type":"standard","description":"English rock band","extract":"Arctic Monkeys are an English rock band formed in Sheffield in 2002. Moving step by step, their 2006 record became the fastest-selling debut album by a band in UK chart history."}"#;
    assert!(parse_wikipedia_artist_summary_json(arctic_json, true).is_some());

    let furniture_json = r#"{"type":"standard","description":"Item of furniture","extract":"A table is an item of furniture with a flat top."}"#;
    assert_eq!(parse_wikipedia_artist_summary_json(furniture_json, true), None);
}

#[tokio::test]
async fn test_artist_detail_enrichment_persists_links_portrait_and_bio() {
    use super::artist_enrich::enrich_artist_detail_with_fetcher;

    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    db.catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "21".to_owned(),
            creators: vec!["Adele".to_owned()],
            identifiers: vec![ident("spotify", "spotify:album:5duyQokC4FMcWPYTV9Gpf9")],
            ..Default::default()
        })
        .await
        .unwrap();

    let artists = db.catalog().find_artist_ids_by_names(&["Adele".to_owned()]).await.unwrap();
    let adele_id = artists.get("adele").unwrap().clone();
    let mut detail = db.catalog().get_entry_detail(&adele_id).await.unwrap().unwrap();

    let service = RocheService::new(db.clone(), DummyWebFetcher, Config::default());
    let mock_fetcher = |url: &Url| -> Option<String> {
        let s = url.as_str();
        if s.contains("open.spotify.com/album/") {
            Some(r#"<meta name="music:musician" content="https://open.spotify.com/artist/4dpARuHxo51G3z768sgnrY"/><a href="/artist/4dpARuHxo51G3z768sgnrY">Adele</a>"#.to_owned())
        } else if s.contains("w/api.php") {
            Some(r#"{"query":{"search":[{"title":"Adele","snippet":"English singer-songwriter (born 1988)."}]}}"#.to_owned())
        } else if s.contains("api/rest_v1/page/summary/Adele") {
            Some(r#"{"type":"standard","description":"English singer-songwriter","extract":"Adele Laurie Blue Adkins is an English singer-songwriter.","thumbnail":{"source":"https://upload.wikimedia.org/wikipedia/commons/adele.jpg"}}"#.to_owned())
        } else {
            None
        }
    };
    enrich_artist_detail_with_fetcher(&service, &mut detail, &mock_fetcher).await;

    let resp = get_authed(&app, &format!("/entries/{adele_id}"), &cookie).await;
    assert_eq!(resp.status(), StatusCode::OK);
    let html =
        String::from_utf8(to_bytes(resp.into_body(), 256 * 1024).await.unwrap().to_vec()).unwrap();
    assert!(html.contains("https://en.wikipedia.org/wiki/Adele"));
    assert!(html.contains("https://open.spotify.com/artist/4dpARuHxo51G3z768sgnrY"));
    assert!(html.contains("src=\"https://upload.wikimedia.org/wikipedia/commons/adele.jpg\""));
    assert!(html.contains("Adele Laurie Blue Adkins is an English singer-songwriter."));
}

struct DiscoveryWebFetcher;

impl crate::scheduler::FeedFetcher for DiscoveryWebFetcher {
    fn fetch_feed(&self, url: &Url) -> impl std::future::Future<Output = anyhow::Result<String>> {
        let res = match url.as_str() {
            "https://danluu.com/" => Ok(r#"<!doctype html><html><head><title>Dan Luu</title>
                   <link rel="alternate" type="application/atom+xml" href="/atom.xml">
                   </head><body><p>Essays</p></body></html>"#
                .to_owned()),
            "https://danluu.com/atom.xml" => Ok(r#"<?xml version="1.0" encoding="utf-8"?>
                   <feed xmlns="http://www.w3.org/2005/Atom">
                     <title>Dan Luu</title>
                     <entry>
                       <id>https://danluu.com/latency-mitigations/</id>
                       <title>Latency Mitigations</title>
                       <link href="https://danluu.com/latency-mitigations/"/>
                     </entry>
                   </feed>"#
                .to_owned()),
            "https://www.youtube.com/@Computerphile" => Ok(r#"<!doctype html><html><head>
                   <meta itemprop="channelId" content="UC9-y-6csu5WGm29I7JiwpnA">
                   </head><body></body></html>"#
                .to_owned()),
            other => Err(anyhow::anyhow!("unexpected fetch_feed url: {other}")),
        };
        std::future::ready(res)
    }

    fn fetch_html(
        &self,
        url: &Url,
    ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> {
        let res = if url.as_str() == "https://essays.example.com/lsm" {
            Ok(Some(
                r#"<!doctype html><html><head><title>LSM Compaction</title>
                   <meta name="author" content="Pat O'Neil">
                   <meta name="description" content="Write amplification tradeoffs.">
                   </head><body><p>Full saved essay text.</p></body></html>"#
                    .to_owned(),
            ))
        } else {
            Ok(None)
        };
        std::future::ready(res)
    }
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_want_to_read_readonly_inbox_starred_list_and_homepage_feed_discovery() {
    assert_eq!(
        crate::db::deterministic_system_collection_id("want:read"),
        crate::db::WANT_TO_READ_COLLECTION_ID
    );

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let service = RocheService::new(db.clone(), DiscoveryWebFetcher, Config::default());
    let app = crate::api::ApiRoutes::router_with_service(service.clone());
    let (cookie, csrf) = login_browser(&db, &app).await;
    let resp_html = async |resp: axum::response::Response| -> String {
        String::from_utf8(to_bytes(resp.into_body(), 256 * 1024).await.unwrap().to_vec()).unwrap()
    };

    // 1. Save standalone URL from Home -> lands in Want to Read (`inbox_state = 'none'`), NOT Feed Inbox
    let save_resp = post_form(
        &app,
        "/web/items/save",
        &cookie,
        &[("_csrf", &csrf), ("url", "essays.example.com/lsm"), ("return_to", "/")],
    )
    .await;
    assert_eq!(save_resp.status(), StatusCode::SEE_OTHER);

    for _ in 0..30 {
        if !service.is_refreshing() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }

    let home_html = resp_html(get_authed(&app, "/", &cookie).await).await;
    assert!(home_html.contains("Queue (1)"));
    assert!(home_html.contains("LSM Compaction"));
    assert!(home_html.contains("Feed Inbox (0)"));

    let inbox_html = resp_html(get_authed(&app, "/inbox", &cookie).await).await;
    assert!(!inbox_html.contains("Save URL to Inbox"));
    assert!(!inbox_html.contains("LSM Compaction"));

    let queue_html = resp_html(get_authed(&app, "/queue", &cookie).await).await;
    assert!(queue_html.contains("Want to Read <span class=\"list-count-badge\">1</span>"));
    assert!(queue_html.contains("LSM Compaction"));
    assert!(queue_html.contains(">Read</a>"));

    let wtr_items = db
        .items()
        .list_filtered(&crate::db::ItemFilter {
            collection_id: Some(crate::db::WANT_TO_READ_COLLECTION_ID.to_owned()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(wtr_items.len(), 1);
    let saved_id = wtr_items[0].id.clone();
    assert_eq!(wtr_items[0].author.as_deref(), Some("Pat O'Neil"));

    // Media attached to a `want:read` item (`inbox_state = 'none'`) must NEVER be treated as orphaned!
    db.media()
        .create(&saved_id, crate::db::MediaType::Pdf, "pdfs/lsm.pdf", Some(2048), None)
        .await
        .unwrap();
    assert!(db.media().find_orphaned().await.unwrap().is_empty());

    // 2. Star the item -> `/lists` and `/lists/starred` treat Starred as a normal manual list (no Enable Draw)
    let star_resp = post_form(
        &app,
        &format!("/web/items/{saved_id}/star"),
        &cookie,
        &[("_csrf", &csrf), ("return_to", "/lists/starred")],
    )
    .await;
    assert_eq!(star_resp.status(), StatusCode::SEE_OTHER);

    let lists_html = resp_html(get_authed(&app, "/lists", &cookie).await).await;
    assert!(lists_html.contains("/lists/starred"));

    let starred_list_html = resp_html(get_authed(&app, "/lists/starred", &cookie).await).await;
    assert!(starred_list_html.contains("LSM Compaction"));
    assert!(starred_list_html.contains("Read &rarr;"));
    assert!(!starred_list_html.contains("Enable Draw"));

    // 3. Homepage & YouTube `@handle` RSS autodiscovery on `/web/feeds/create`
    let sub_blog = post_form(
        &app,
        "/web/feeds/create",
        &cookie,
        &[("_csrf", &csrf), ("url", "danluu.com"), ("feed_type", "rss")],
    )
    .await;
    assert_eq!(sub_blog.status(), StatusCode::SEE_OTHER);

    let sub_yt = post_form(
        &app,
        "/web/feeds/create",
        &cookie,
        &[
            ("_csrf", &csrf),
            ("url", "https://www.youtube.com/@Computerphile"),
            ("feed_type", "rss"),
        ],
    )
    .await;
    assert_eq!(sub_yt.status(), StatusCode::SEE_OTHER);

    let feeds = db.feeds().list().await.unwrap();
    assert!(feeds.iter().any(|f| f.url.as_str() == "https://danluu.com/atom.xml"));
    assert!(feeds.iter().any(|f| {
        f.feed_type == FeedType::Youtube
            && f.url.as_str()
                == "https://www.youtube.com/feeds/videos.xml?channel_id=UC9-y-6csu5WGm29I7JiwpnA"
    }));

    // 4. Non-blocking background refresh endpoint + JSON status route
    let refresh_all = post_form(
        &app,
        "/web/feeds/refresh-all",
        &cookie,
        &[("_csrf", &csrf), ("return_to", "/inbox")],
    )
    .await;
    assert_eq!(refresh_all.status(), StatusCode::SEE_OTHER);

    let status_resp = get_authed(&app, "/web/refresh-status", &cookie).await;
    assert_eq!(status_resp.status(), StatusCode::OK);
    let status_json: serde_json::Value =
        serde_json::from_slice(&to_bytes(status_resp.into_body(), 16 * 1024).await.unwrap())
            .unwrap();
    assert!(status_json["is_refreshing"].is_boolean());
}

#[test]
fn test_decode_html_entities_multibyte_utf8_no_panic() {
    use super::artist_enrich::decode_html_entities;

    // '&' followed by 10 ASCII bytes + a 3-byte UTF-8 char straddling byte 12 (bytes 10..13)
    let input = "A &0123456789\u{2014}end &amp; &quot;Bj\u{00f6}rk&quot; &#x27;ok&#x27;";
    let decoded = decode_html_entities(input);
    assert_eq!(decoded, "A &0123456789\u{2014}end & \"Bj\u{00f6}rk\" 'ok'");

    // Multi-byte chars immediately after '&' with no semicolon
    let no_semi = "&日本語テスト文字列";
    assert_eq!(decode_html_entities(no_semi), no_semi);
}

#[test]
fn test_sanitize_non_local_href_blocks_ipv6_ula_link_local_mapped_6to4_and_cgnat() {
    use super::links::sanitize_non_local_href;

    for blocked in [
        "http://0.0.0.0/a",
        "http://0.1.2.3/a",
        "http://127.0.0.1/a",
        "http://10.0.0.1/a",
        "http://169.254.169.254/latest/meta-data",
        "http://100.64.0.1/tailscale",
        "http://100.127.255.254/cgnat",
        "http://240.0.0.1/reserved",
        "http://255.255.255.255/broadcast",
        "http://[::1]/a",
        "http://[::127.0.0.1]/ipv4-compat-loopback",
        "http://[::169.254.169.254]/ipv4-compat-meta",
        "http://[::8.8.8.8]/ipv4-compat",
        "http://[::ffff:0:127.0.0.1]/siit",
        "http://[64:ff9b::127.0.0.1]/nat64-wkp",
        "http://[64:ff9b:1::1]/nat64-local",
        "http://[2001:0000:4136:e378:8000:63bf:3fff:fdd2]/teredo",
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
        "https://user:pass@example.com/secret",
    ] {
        assert!(sanitize_non_local_href(blocked).is_none(), "expected {blocked} to be blocked");
    }

    for allowed in [
        "https://example.com/audio.mp3",
        "https://8.8.8.8/file.mp3",
        "https://[2606:4700:4700::1111]/file.mp3",
        "https://[::ffff:8.8.8.8]/file.mp3",
    ] {
        assert!(sanitize_non_local_href(allowed).is_some(), "expected {allowed} to be allowed");
    }
}

#[test]
fn test_is_same_origin_post_origin_and_referer_fallback() {
    use axum::http::HeaderMap;

    use super::auth::is_same_origin_post;

    // Sec-Fetch-Site present
    let mut h = HeaderMap::new();
    h.insert("sec-fetch-site", "same-origin".parse().unwrap());
    assert!(is_same_origin_post(&h, true));
    assert!(is_same_origin_post(&h, false));

    let mut h = HeaderMap::new();
    h.insert("sec-fetch-site", "cross-site".parse().unwrap());
    assert!(!is_same_origin_post(&h, true));

    // Sec-Fetch-Site absent -> falls back to Origin vs Host with strict scheme check
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

    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example:8080".parse().unwrap());
    h.insert(header::ORIGIN, "https://evil.example".parse().unwrap());
    assert!(!is_same_origin_post(&h, true));

    // Reject malformed Host headers containing `?`, `#`, `/`, or `@`
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

    // Sec-Fetch-Site & Origin absent -> falls back to Referer vs Host with strict scheme check
    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example".parse().unwrap());
    h.insert(header::REFERER, "https://roche.example/login".parse().unwrap());
    assert!(is_same_origin_post(&h, true));
    assert!(!is_same_origin_post(&h, false));

    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example".parse().unwrap());
    h.insert(header::REFERER, "http://roche.example/login".parse().unwrap());
    assert!(is_same_origin_post(&h, false));
    assert!(!is_same_origin_post(&h, true));

    let mut h = HeaderMap::new();
    h.insert(header::HOST, "roche.example".parse().unwrap());
    h.insert(header::REFERER, "https://evil.example/login".parse().unwrap());
    assert!(!is_same_origin_post(&h, true));

    // All absent -> rejected
    assert!(!is_same_origin_post(&HeaderMap::new(), true));
    assert!(!is_same_origin_post(&HeaderMap::new(), false));
}

#[test]
fn test_enrich_attempt_cache_ttl() {
    use std::time::{Duration, Instant};

    use super::artist_enrich::EnrichAttemptCache;

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

async fn read_body_html(resp: Response) -> String {
    String::from_utf8(to_bytes(resp.into_body(), 256 * 1024).await.unwrap().to_vec()).unwrap()
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
        get_authed(&app, &format!("/web/items/{}/audio", local_item.id), &cookie).await;
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

    let ok_resp = get_authed(&app, &format!("/web/items/{}/audio", pub_item.id), &cookie).await;
    assert_eq!(ok_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(ok_resp.headers().get(header::LOCATION).unwrap(), "https://cdn.pod.example/ep1.mp3");
}

#[tokio::test]
async fn test_web_csp_a11y_and_reader_link_existence() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    // 1. Strict CSP without 'unsafe-inline' on style-src
    let home_resp = get_authed(&app, "/", &cookie).await;
    let csp = home_resp
        .headers()
        .get(header::CONTENT_SECURITY_POLICY)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    assert!(csp.contains("style-src 'self'"));
    assert!(!csp.contains("'unsafe-inline'"));
    let home_html = read_body_html(home_resp).await;
    assert!(home_html.contains("class=\"skip-link\""));
    assert!(home_html.contains("aria-label=\"Primary\""));
    assert!(home_html.contains("aria-current=\"page\""));
    assert!(home_html.contains("<main id=\"roche-main\" tabindex=\"-1\">"));

    // 2. Unauthenticated error page includes viewport meta tag
    let unauth_post = app
        .clone()
        .oneshot(Request::builder().method("POST").uri("/web/log").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(unauth_post.status(), StatusCode::UNAUTHORIZED);
    let err_html = read_body_html(unauth_post).await;
    assert!(err_html.contains("name=\"viewport\""));

    // 3. Reader link existence check for system list vs manual list
    let quick_logged_art = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Article),
            title: "Quick Logged Article".to_owned(),
            url: Some("https://example.org/quick-logged-article".to_owned()),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&quick_logged_art.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();
    assert_eq!(
        get_authed(&app, &format!("/reader/{}", quick_logged_art.id), &cookie).await.status(),
        StatusCode::NOT_FOUND
    );
    let articles_list_html =
        read_body_html(get_authed(&app, "/lists/articles", &cookie).await).await;
    assert!(articles_list_html.contains("Quick Logged Article"));
    assert!(!articles_list_html.contains(&format!("/reader/{}", quick_logged_art.id)));
    // Unranked lists omit empty Tier/Score columns until comparisons exist
    assert!(!articles_list_html.contains("<span class=\"sr-only\">Score "));

    let second_art = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Article),
            title: "Second Logged Article".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&second_art.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();
    db.catalog()
        .append_comparison(
            None,
            &[vec![quick_logged_art.id.clone()], vec![second_art.id.clone()]],
            None,
        )
        .await
        .unwrap();
    let ranked_articles_html =
        read_body_html(get_authed(&app, "/lists/articles", &cookie).await).await;
    assert!(ranked_articles_html.contains("<span class=\"sr-only\">Score "));

    let manual_list = db
        .catalog()
        .create_manual_collection("Custom List", None, crate::db::CollectionRole::List, None, None)
        .await
        .unwrap();
    db.catalog()
        .add_collection_entry(
            manual_list.slug.as_deref().unwrap_or(&manual_list.id),
            &quick_logged_art.id,
            None,
        )
        .await
        .unwrap();
    let list_html = read_body_html(
        get_authed(
            &app,
            &format!("/lists/{}", manual_list.slug.as_deref().unwrap_or(&manual_list.id)),
            &cookie,
        )
        .await,
    )
    .await;
    assert!(list_html.contains("aria-label=\"Remove Quick Logged Article from list\""));
}

#[tokio::test]
async fn test_web_filters_labels_and_destructive_confirmations() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    // 1. Queue remove button aria-label & Want list rank link stripping `want:` prefix
    let want_video_input = crate::db::UpsertEntryInput {
        kind: Some(EntryKind::Video),
        title: "Queued Reader Video".to_owned(),
        url: Some("https://example.org/reader-video".to_owned()),
        ..crate::db::UpsertEntryInput::default()
    };
    let (_, catalog_only) = db.catalog().want_entry(&want_video_input, None).await.unwrap();
    let queue_html = read_body_html(get_authed(&app, "/queue", &cookie).await).await;
    assert!(queue_html.contains("Queued Reader Video"));
    assert!(queue_html.contains("aria-label=\"Remove Queued Reader Video from queue\""));

    let second_vid_input = crate::db::UpsertEntryInput {
        kind: Some(EntryKind::Video),
        title: "Second Queued Video".to_owned(),
        ..crate::db::UpsertEntryInput::default()
    };
    let (want_col, _) = db.catalog().want_entry(&second_vid_input, None).await.unwrap();
    let want_slug = want_col.slug.as_deref().unwrap_or(&want_col.id);
    let base_want_slug = want_slug.strip_prefix("want:").unwrap_or(want_slug);

    let want_list_html =
        read_body_html(get_authed(&app, &format!("/lists/{want_slug}"), &cookie).await).await;
    assert!(want_list_html.contains(&format!("/rank/{base_want_slug}?want=true")));
    assert!(!want_list_html.contains("/rank/want%3A"));

    // 2. Diary status pills preserve `year` query parameter
    let diary_html = read_body_html(get_authed(&app, "/diary?year=2025", &cookie).await).await;
    assert!(diary_html.contains("/diary?year=2025&amp;status=consumed"));
    assert!(diary_html.contains("/diary?year=2025&amp;status=started"));
    assert!(diary_html.contains("/diary?year=2025&amp;status=abandoned"));

    // 3. Inbox active filter chips, accessible labels, proper ContentType label, and destructive confirm
    let yt_url = Url::parse("https://www.youtube.com/feeds/videos.xml?channel_id=UC123").unwrap();
    let feed =
        db.feeds().create(FeedType::Youtube, &yt_url, Some("Tech Channel"), None).await.unwrap();
    let pdf_url = Url::parse("https://example.org/pdf-item").unwrap();
    db.items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "A PDF Paper",
            url: &pdf_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Pdf,
        })
        .await
        .unwrap();

    let inbox_html = read_body_html(get_authed(&app, "/inbox", &cookie).await).await;
    assert!(inbox_html.contains(">PDF</span>"));
    assert!(inbox_html.contains("aria-label=\"Search inbox\""));
    assert!(inbox_html.contains("aria-pressed=\"false\""));
    assert!(inbox_html.contains("data-confirm=\"Archive all unread items in your inbox?\""));

    let filtered_inbox = read_body_html(
        get_authed(&app, &format!("/inbox?feed={}&kind=article", feed.id), &cookie).await,
    )
    .await;
    assert!(filtered_inbox.contains("active-filters"));
    assert!(filtered_inbox.contains("Clear filters"));

    // 4. Feeds display label (`YouTube`) and destructive delete confirmation
    let feeds_html = read_body_html(get_authed(&app, "/feeds", &cookie).await).await;
    assert!(feeds_html.contains(">YouTube</span>"));
    assert!(feeds_html.contains("data-confirm=\"Delete this feed subscription?"));

    // 5. Entry page merge confirmation and accessible labels
    let entry_html =
        read_body_html(get_authed(&app, &format!("/entries/{}", catalog_only.id), &cookie).await)
            .await;
    assert!(entry_html.contains("data-confirm=\"Merge this duplicate"));
    assert!(entry_html.contains("aria-label=\"Canonical entry UUID, URL, or exact title\""));
}

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
                vars.insert(format!("--{}", name.trim()), value.trim().to_string());
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

fn extract_heading_levels(html: &str) -> Vec<usize> {
    let mut levels = Vec::new();
    let bytes = html.as_bytes();
    let mut i = 0;
    while i + 2 < bytes.len() {
        if bytes[i] == b'<'
            && (bytes[i + 1] == b'h' || bytes[i + 1] == b'H')
            && bytes[i + 2].is_ascii_digit()
        {
            let level = usize::from(bytes[i + 2] - b'0');
            let next = bytes.get(i + 3).copied();
            if (1..=6).contains(&level)
                && matches!(next, Some(b'>' | b' ' | b'\t' | b'\n' | b'\r' | b'/'))
            {
                levels.push(level);
            }
        }
        i += 1;
    }
    levels
}

fn assert_valid_heading_hierarchy(route: &str, html: &str) {
    let levels = extract_heading_levels(html);
    let h1_count = levels.iter().filter(|&&l| l == 1).count();
    assert_eq!(h1_count, 1, "{route} must contain exactly one <h1>, got levels {levels:?}");
    for pair in levels.windows(2) {
        assert!(
            pair[1] <= pair[0] + 1,
            "{route} skips heading levels (h{} -> h{}) in sequence {levels:?}",
            pair[0],
            pair[1]
        );
    }
}

#[test]
fn web_css_palette_meets_wcag_aa_contrast() {
    let blocks = parse_css_theme_blocks(super::ROCHE_CSS);
    assert!(blocks.len() >= 2, "roche.css must define light and dark :root theme blocks");

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

        // All three editorial surfaces for this theme: main paper, recessed paper, and input paper.
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
                    "roche.css ({selector}) {text_token} ({text_hex}) on {surface_label} ({surface_hex}) has contrast {ratio:.2}:1 (WCAG AA requires >= 4.5:1)"
                );
            }

            // WCAG 2.1 AA SC 1.4.11 non-text contrast (>= 3.0:1) for interactive borders and focus ring.
            for ui_token in ["--control-border", "--accent"] {
                let ui_hex = get(ui_token);
                let ratio = wcag_contrast_ratio(ui_hex, surface_hex);
                assert!(
                    ratio >= 3.0,
                    "roche.css ({selector}) {ui_token} ({ui_hex}) on {surface_label} ({surface_hex}) has contrast {ratio:.2}:1 (WCAG 1.4.11 requires >= 3.0:1)"
                );
            }
        }

        // Inverted button / skip-link surfaces (--paper on --ink, --accent-on-fill on --accent-fill / hover).
        for (foreground_token, background_token) in [
            ("--paper", "--ink"),
            ("--accent-on-fill", "--accent-fill"),
            ("--accent-on-fill", "--accent-fill-hover"),
        ] {
            let ratio = wcag_contrast_ratio(get(foreground_token), get(background_token));
            assert!(
                ratio >= 4.5,
                "roche.css ({selector}) {foreground_token} on {background_token} has contrast {ratio:.2}:1 (WCAG AA requires >= 4.5:1)"
            );
        }
    }

    // Guard against missing placeholder, disabled, and sticky-player scroll-padding styles.
    assert!(
        super::ROCHE_CSS.contains("::placeholder"),
        "roche.css must explicitly style ::placeholder"
    );
    assert!(
        super::ROCHE_CSS.contains(":disabled"),
        "roche.css must explicitly style :disabled controls"
    );
    assert!(
        super::ROCHE_CSS.contains("scroll-padding-bottom"),
        "roche.css must reserve scroll-padding-bottom when fixed player bar is visible"
    );
}

async fn seed_a11y_landmark_fixtures(db: &Db) -> (String, String, String) {
    let feed_url = Url::parse("https://editorial.example/rss.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("Editorial Feed"), None).await.unwrap();
    let item_url = Url::parse("https://editorial.example/a11y-essay").unwrap();
    let item = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Accessibility in Editorial Web Design",
            url: &item_url,
            author: Some("Ada Lovelace"),
            published_at: None,
            summary: Some("A short summary."),
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    db.items()
        .apply_background_enrichment(
            &item.id,
            None,
            None,
            None,
            None,
            None,
            Some(
                "<h1>Embedded Article Heading</h1><p>Body</p><pre><code>fn main() {}</code></pre><img src=\"https://editorial.example/fig.png\">",
            ),
        )
        .await
        .unwrap();

    let col = db
        .catalog()
        .create_manual_collection(
            "A11y Classics",
            None,
            crate::db::CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let col_slug = col.slug.as_deref().unwrap_or(&col.id).to_owned();
    let entry_a = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "The Design of Everyday Things".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let entry_b = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Inclusive Components".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog().add_collection_entry(&col_slug, &entry_a.id, None).await.unwrap();
    db.catalog().add_collection_entry(&col_slug, &entry_b.id, None).await.unwrap();

    (col_slug, entry_a.id, item.id)
}

#[tokio::test]
async fn web_pages_include_core_accessibility_landmarks() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let login_resp = app
        .clone()
        .oneshot(Request::builder().uri("/login").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert_eq!(login_resp.status(), StatusCode::OK);
    let login_html = read_body_html(login_resp).await;
    assert!(login_html.contains("<html lang=\"en\""), "/login missing lang attribute");
    assert!(login_html.contains("<main"), "/login missing <main> landmark");
    assert_valid_heading_hierarchy("/login", &login_html);

    let login_err_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/login")
                .header(header::HOST, "127.0.0.1")
                .header(header::ORIGIN, "http://127.0.0.1")
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(Body::from("pairing_code=invalid-code"))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(login_err_resp.status(), StatusCode::UNAUTHORIZED);
    let login_err_html = read_body_html(login_err_resp).await;
    assert!(login_err_html.contains("aria-invalid=\"true\""), "/login error missing aria-invalid");
    assert!(
        login_err_html.contains("aria-describedby=\"login-error\""),
        "/login error missing aria-describedby"
    );
    assert_valid_heading_hierarchy("/login (401)", &login_err_html);

    let unauthed_err_resp =
        super::html::render_error_page(StatusCode::FORBIDDEN, "Forbidden", "Access denied", None);
    assert_eq!(unauthed_err_resp.status(), StatusCode::FORBIDDEN);
    assert_valid_heading_hierarchy(
        "unauthenticated error page",
        &read_body_html(unauthed_err_resp).await,
    );

    let (col_slug, entry_id, item_id) = seed_a11y_landmark_fixtures(&db).await;
    let routes = [
        "/".to_owned(),
        "/inbox".to_owned(),
        "/inbox?kind=article&q=Editorial".to_owned(),
        "/lists/starred".to_owned(),
        "/feeds".to_owned(),
        "/lists".to_owned(),
        format!("/lists/{col_slug}"),
        format!("/entries/{entry_id}"),
        format!("/reader/{item_id}"),
        "/diary".to_owned(),
        "/queue".to_owned(),
        format!("/rank/{col_slug}"),
    ];

    for path in routes {
        let resp = get_authed(&app, &path, &cookie).await;
        assert_eq!(resp.status(), StatusCode::OK, "expected HTTP 200 on {path}");
        let html = read_body_html(resp).await;
        assert!(html.contains("<html lang=\"en\""), "{path} missing lang attribute");
        assert!(html.contains("class=\"skip-link\""), "{path} missing skip link");
        assert!(
            html.contains("<main id=\"roche-main\""),
            "{path} missing <main id=\"roche-main\">"
        );
        assert!(
            html.contains("<aside id=\"roche-player-bar\""),
            "{path} missing <aside> landmark for player bar"
        );
        assert_valid_heading_hierarchy(&path, &html);

        if path.starts_with("/reader/") {
            assert!(
                html.contains("<h3>Embedded Article Heading</h3>"),
                "{path} should demote embedded article <h1> to <h3>"
            );
            assert!(
                html.contains("<pre tabindex=\"0\">"),
                "{path} should add tabindex=\"0\" on <pre> blocks"
            );
            assert!(
                html.contains("alt=\"\""),
                "{path} should add empty alt=\"\" on untitled sanitized images"
            );
        }
    }
}

#[test]
fn test_safe_local_redirect_ascii_fallback_and_fragment_preserving_flash() {
    use super::html::{redirect_see_other, redirect_with_flash, safe_local_redirect};

    // Non-ASCII redirect targets are rejected
    assert_eq!(safe_local_redirect(Some("/inbox?q=caf\u{00e9}"), "/fallback"), "/fallback");
    assert_eq!(safe_local_redirect(Some("/inbox?q=ok"), "/fallback"), "/inbox?q=ok");

    // `redirect_see_other` always emits a Location header (never a Location-less 303)
    let non_ascii_resp = redirect_see_other("/inbox?q=caf\u{00e9}");
    assert_eq!(non_ascii_resp.status(), StatusCode::SEE_OTHER);
    assert_eq!(non_ascii_resp.headers().get(header::LOCATION).unwrap(), "/");

    // `redirect_with_flash` preserves `#fragment` after query parameters
    let frag_only = redirect_with_flash("/entries/abc#notes", "notice", "Saved ok");
    assert_eq!(
        frag_only.headers().get(header::LOCATION).unwrap(),
        "/entries/abc?notice=Saved+ok#notes"
    );

    let query_and_frag = redirect_with_flash("/inbox?status=all#row-5", "err", "Failed");
    assert_eq!(
        query_and_frag.headers().get(header::LOCATION).unwrap(),
        "/inbox?status=all&err=Failed#row-5"
    );

    let question_in_frag = redirect_with_flash("/inbox#frag?x=1", "notice", "Hi");
    assert_eq!(
        question_in_frag.headers().get(header::LOCATION).unwrap(),
        "/inbox?notice=Hi#frag?x=1"
    );
}

#[test]
fn test_try_reserve_enrichment_slot_bounds_concurrency_before_marking_cache() {
    use std::{
        sync::{Arc, Mutex},
        time::{Duration, Instant},
    };

    use super::artist_enrich::{
        EnrichAttemptCache, MAX_CONCURRENT_ENRICH_TASKS, try_reserve_enrichment_slot,
    };

    let sem = Arc::new(tokio::sync::Semaphore::new(MAX_CONCURRENT_ENRICH_TASKS));
    let cache = Mutex::new(EnrichAttemptCache::default());
    let now = Instant::now();
    let ttl = Duration::from_secs(60);

    let mut held = Vec::new();
    for idx in 0..MAX_CONCURRENT_ENRICH_TASKS {
        let permit =
            try_reserve_enrichment_slot(&sem, &cache, format!("artist-{idx}|sp=false"), now, ttl)
                .unwrap_or_else(|| panic!("expected slot {idx} to be reserved"));
        held.push(permit);
    }
    assert_eq!(sem.available_permits(), 0);

    // Exhausted semaphore rejects new artist WITHOUT marking it in the attempt cache
    assert!(
        try_reserve_enrichment_slot(&sem, &cache, "artist-overflow|sp=false".to_owned(), now, ttl)
            .is_none()
    );

    // Releasing one permit lets the previously-throttled artist run immediately
    drop(held.pop());
    assert_eq!(sem.available_permits(), 1);
    let overflow_permit =
        try_reserve_enrichment_slot(&sem, &cache, "artist-overflow|sp=false".to_owned(), now, ttl);
    assert!(overflow_permit.is_some());
    drop(overflow_permit);

    // Duplicate key within TTL returns None and does NOT leak a semaphore permit
    assert_eq!(sem.available_permits(), 1);
    assert!(
        try_reserve_enrichment_slot(&sem, &cache, "artist-overflow|sp=false".to_owned(), now, ttl)
            .is_none()
    );
    assert_eq!(sem.available_permits(), 1);
    drop(held);
    assert_eq!(sem.available_permits(), MAX_CONCURRENT_ENRICH_TASKS);
}

#[tokio::test]
async fn test_web_body_limits_standard_128kib_vs_opml_4mib() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    // 1. Standard form POST > 128 KiB with known body length is rejected with 413
    let big_summary = "a".repeat(140 * 1024);
    let oversized_regular = post_form(
        &app,
        "/web/lists/create",
        &cookie,
        &[("_csrf", &csrf), ("title", "Too Big"), ("summary", &big_summary)],
    )
    .await;
    assert_eq!(oversized_regular.status(), StatusCode::PAYLOAD_TOO_LARGE);

    // 2. Streamed/chunked standard form POST > 128 KiB (unknown size_hint) is capped at 128 KiB
    let raw_form =
        encode_form(&[("_csrf", &csrf), ("title", "Streamed"), ("summary", &big_summary)]);
    let stream_body =
        Body::from_stream(tokio_util::io::ReaderStream::new(std::io::Cursor::new(raw_form)));
    let streamed_resp = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/web/lists/create")
                .header(header::COOKIE, &cookie)
                .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body(stream_body)
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(streamed_resp.status(), StatusCode::PAYLOAD_TOO_LARGE);

    // 3. Authenticated OPML upload > 128 KiB (within 4 MiB) is accepted
    let padding = " ".repeat(160 * 1024);
    let opml_xml = format!(
        "<?xml version=\"1.0\"?><opml version=\"2.0\"><body><!--{padding}--></body></opml>"
    );
    let opml_resp =
        post_form(&app, "/web/feeds/opml", &cookie, &[("_csrf", &csrf), ("opml_xml", &opml_xml)])
            .await;
    assert_eq!(opml_resp.status(), StatusCode::SEE_OTHER);
    assert!(
        opml_resp
            .headers()
            .get(header::LOCATION)
            .unwrap()
            .to_str()
            .unwrap()
            .starts_with("/feeds?notice=")
    );
}

#[tokio::test]
async fn test_round2_web_ux_panels_pills_and_roche_js_guards() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    // 1. Subscribe rail on /feeds is directly visible (no unguarded `<details open>`) and default-open panel on /queue carries `data-default-open`
    let feeds_html = read_body_html(get_authed(&app, "/feeds", &cookie).await).await;
    assert!(feeds_html.contains("action=\"/web/feeds/create\""));
    assert!(!feeds_html.contains("<details class=\"rail-details\" open>"));
    let queue_html = read_body_html(get_authed(&app, "/queue", &cookie).await).await;
    assert!(queue_html.contains("<details class=\"editorial-panel\" open data-default-open>"));
    assert!(super::ROCHE_JS.contains("details[open]:not([data-default-open])"));
    assert!(!super::ROCHE_JS.contains("resp.url?.includes(\"/login\")"));
    assert!(super::ROCHE_JS.contains("pathname === \"/login\""));
    assert!(super::ROCHE_JS.contains("syncCsrfFromDoc"));
    assert!(super::ROCHE_JS.contains("resp.status === 401"));
    assert!(super::ROCHE_JS.contains("resp.status === 403"));

    // 2. Invalid `kind` on `/inbox` does not render a bogus chip or narrow batch controls
    let feed_url = Url::parse("https://blog.example/feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Blog"), None).await.unwrap();
    let art_url = Url::parse("https://blog.example/post-1").unwrap();
    db.items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Feed Post One",
            url: &art_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    let bogus_kind_html =
        read_body_html(get_authed(&app, "/inbox?kind=bogus", &cookie).await).await;
    assert!(!bogus_kind_html.contains("active-filters"));
    assert!(!bogus_kind_html.contains("Kind: bogus"));
    assert!(!bogus_kind_html.contains("name=\"kind\" value=\"bogus\""));
    assert!(bogus_kind_html.contains("Archive All Unread"));

    // 3. Active filter pills on `/inbox` and `/diary` carry `aria-current="true"`
    let valid_kind_html =
        read_body_html(get_authed(&app, "/inbox?kind=article", &cookie).await).await;
    assert!(valid_kind_html.contains(
        "<a class=\"pill is-active\" aria-current=\"true\" href=\"/inbox?status=unread&amp;kind=article\">Unread</a>"
    ));
    assert!(valid_kind_html.contains(
        "<a class=\"pill is-active\" aria-current=\"true\" href=\"/inbox?status=unread\">Articles</a>"
    ));
    assert!(
        valid_kind_html
            .contains("<a class=\"pill\" href=\"/inbox?status=all&amp;kind=article\">All</a>")
    );

    let diary_consumed_html =
        read_body_html(get_authed(&app, "/diary?status=consumed", &cookie).await).await;
    assert!(diary_consumed_html.contains(
        "<a class=\"pill is-active\" aria-current=\"true\" href=\"/diary?status=consumed\">Consumed</a>"
    ));
    assert!(diary_consumed_html.contains("<a class=\"pill\" href=\"/diary\">All</a>"));
}

#[tokio::test]
async fn test_round2_web_security_reader_origin_and_rank_redirect() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    // 1. Login POST enforces HTTPS scheme match on Origin when Sec-Fetch-Site is absent
    let code = PairingManager::new(db.auth()).create_code(300).await.unwrap();
    let login_body = encode_form(&[("pairing_code", &code), ("device_name", "Scheme Check")]);
    let mut http_origin_over_https = Request::builder()
        .method("POST")
        .uri("/login")
        .header(header::CONTENT_TYPE, "application/x-www-form-urlencoded")
        .header(header::HOST, "roche.example")
        .header(header::ORIGIN, "http://roche.example")
        .header("x-forwarded-proto", "https")
        .body(Body::from(login_body))
        .unwrap();
    http_origin_over_https
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 41000)));
    assert_eq!(
        app.clone().oneshot(http_origin_over_https).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );

    // 2. Reader suppresses non-local `Original` links (e.g. http://roche.local or 127.0.0.1)
    let local_item_url = Url::parse("http://roche.local/private-note").unwrap();
    let (local_item, _) = db
        .items()
        .save_manual_input_with_tags(&crate::db::ManualItemInput {
            url: &local_item_url,
            title: "Local Synthetic Note",
            summary: Some("Local body"),
            content_html: None,
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    let local_reader_html =
        read_body_html(get_authed(&app, &format!("/reader/{}", local_item.id), &cookie).await)
            .await;
    assert!(!local_reader_html.contains("Original &#8599;"));
    assert!(local_reader_html.contains("class=\"inline-form js-reader-archive-form\""));

    let pub_item_url = Url::parse("https://example.org/public-essay").unwrap();
    let (pub_item, _) = db
        .items()
        .save_manual_input_with_tags(&crate::db::ManualItemInput {
            url: &pub_item_url,
            title: "Public Essay",
            summary: Some("Public body"),
            content_html: None,
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    let pub_reader_html =
        read_body_html(get_authed(&app, &format!("/reader/{}", pub_item.id), &cookie).await).await;
    assert!(pub_reader_html.contains("href=\"https://example.org/public-essay\""));
    assert!(pub_reader_html.contains("Original &#8599;"));

    // 3. Invalid ranking ordering preserves `want=true`, `focus`, and `round`
    let bad_rank_resp = post_form(
        &app,
        "/rank/movies",
        &cookie,
        &[
            ("_csrf", &csrf),
            ("want", "true"),
            ("focus", "entry-xyz"),
            ("round", "2"),
            ("ordering_json", "not-valid-json"),
        ],
    )
    .await;
    assert_eq!(bad_rank_resp.status(), StatusCode::SEE_OTHER);
    let bad_rank_loc = bad_rank_resp.headers().get(header::LOCATION).unwrap().to_str().unwrap();
    assert!(bad_rank_loc.starts_with("/rank/movies?"), "got {bad_rank_loc}");
    assert!(bad_rank_loc.contains("want=true"), "got {bad_rank_loc}");
    assert!(bad_rank_loc.contains("focus=entry-xyz"), "got {bad_rank_loc}");
    assert!(bad_rank_loc.contains("round=2"), "got {bad_rank_loc}");
    assert!(bad_rank_loc.contains("err=Invalid+comparison+ordering"), "got {bad_rank_loc}");
}

#[tokio::test]
async fn test_load_existing_reader_item_ids_batched_lookup_and_catalog_links() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let svc = RocheService::new(db.clone(), DummyWebFetcher, Config::default());
    let app = ApiRoutes::router_with_service(svc.clone());
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let reader_url = Url::parse("https://example.org/batched-reader-article").unwrap();
    let (live_reader_item, _) = db
        .items()
        .save_manual_input_with_tags(&crate::db::ManualItemInput {
            url: &reader_url,
            title: "Batched Reader Essay",
            summary: Some("Full reader summary"),
            content_html: Some("<p>Readable essay</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&live_reader_item.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();

    let catalog_only_art = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Article),
            title: "Catalog Only Essay".to_owned(),
            url: Some("https://example.org/catalog-only-essay".to_owned()),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&catalog_only_art.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();

    let (_, want_no_url) = db
        .catalog()
        .want_entry(
            &crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Article),
                title: "Queued Article Without URL".to_owned(),
                ..crate::db::UpsertEntryInput::default()
            },
            None,
        )
        .await
        .unwrap();
    let (_, want_with_url) = db
        .catalog()
        .want_entry(
            &crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Article),
                title: "Queued Article With URL".to_owned(),
                url: Some("https://example.org/queued-article".to_owned()),
                ..crate::db::UpsertEntryInput::default()
            },
            None,
        )
        .await
        .unwrap();
    let movie_entry = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Some Film".to_owned(),
            url: Some("https://example.org/film".to_owned()),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();

    let live_entry =
        db.catalog().get_entry_detail(&live_reader_item.id).await.unwrap().unwrap().entry;
    assert!(super::views_catalog::load_existing_reader_item_ids(&svc, []).await.is_empty());

    let found = super::views_catalog::load_existing_reader_item_ids(
        &svc,
        [&live_entry, &live_entry, &catalog_only_art, &want_no_url, &want_with_url, &movie_entry],
    )
    .await;
    assert_eq!(found.len(), 2);
    assert!(found.contains(&live_reader_item.id));
    assert!(found.contains(&want_with_url.id));

    let articles_html = read_body_html(get_authed(&app, "/lists/articles", &cookie).await).await;
    assert!(articles_html.contains(&format!("/reader/{}", live_reader_item.id)));
    assert!(!articles_html.contains(&format!("/reader/{}", catalog_only_art.id)));

    let queue_html = read_body_html(get_authed(&app, "/queue", &cookie).await).await;
    assert!(queue_html.contains(&format!("/reader/{}", want_with_url.id)));
    assert!(!queue_html.contains(&format!("/reader/{}", want_no_url.id)));
}

#[tokio::test]
async fn test_feeds_page_suppresses_paused_errors_and_separates_status_and_kind_filters() {
    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, _csrf) = login_browser(&db, &app).await;

    let active_url = Url::parse("https://active.example/rss.xml").unwrap();
    let active = db
        .feeds()
        .create(FeedType::Rss, &active_url, Some("Active Broken Feed"), None)
        .await
        .unwrap();
    db.feeds().mark_error(&active.id, "Upstream feed error: unable to parse feed").await.unwrap();

    let paused_url = Url::parse("https://paused.example/podcast.xml").unwrap();
    let paused = db
        .feeds()
        .create(FeedType::Podcast, &paused_url, Some("Paused Podcast"), None)
        .await
        .unwrap();
    db.feeds().mark_error(&paused.id, "Upstream server returned HTTP 403 Forbidden").await.unwrap();
    db.feeds().archive(&paused.id).await.unwrap();

    let html = read_body_html(get_authed(&app, "/feeds", &cookie).await).await;
    assert!(html.contains(">All (2)</button>"));
    assert!(html.contains(">Active (1)</button>"));
    assert!(html.contains(">Errors (1)</button>"));
    assert!(html.contains(">Paused (1)</button>"));
    assert!(html.contains("id=\"roche-feeds-kind\""));
    assert!(html.contains("<option value=\"rss\">RSS (1)</option>"));
    assert!(html.contains("<option value=\"podcast\">Podcasts (1)</option>"));
    assert!(html.contains("unable to parse feed"));
    assert!(!html.contains("HTTP 403 Forbidden"));
    assert!(!html.contains(&format!("/web/feeds/{}/refresh", paused.id)));
}

#[tokio::test]
async fn test_album_tracklist_enrichment_and_artist_discography_render() {
    use super::artist_enrich::{
        enrich_album_tracklist_with_fetcher, parse_itunes_lookup_tracks, parse_spotify_embed_tracks,
    };

    let (db, app) = setup_web_app(Config::default()).await;
    let (cookie, csrf) = login_browser(&db, &app).await;

    let sp_html = r#"<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"trackList":[{"title":"Come Together","subtitle":"The Beatles","duration":259000,"uri":"spotify:track:2EqlS6tkEnglzr7tkKAAYD"},{"title":"Something","subtitle":"The Beatles","duration":182000,"uri":"spotify:track:0pNeVovbiZHkulpGeOx1Gj"}]}}}}}}</script></html>"#;
    let sp_tracks = parse_spotify_embed_tracks(sp_html);
    assert_eq!(sp_tracks.len(), 2);
    assert_eq!(sp_tracks[0].title, "Come Together");
    assert_eq!(sp_tracks[0].duration_ms, Some(259_000));

    let itunes_json = r#"{"results":[{"wrapperType":"collection"},{"wrapperType":"track","kind":"song","discNumber":1,"trackNumber":1,"trackName":"Hot Rats Theme","artistName":"Frank Zappa","trackTimeMillis":215000}]}"#;
    let it_tracks = parse_itunes_lookup_tracks(itunes_json);
    assert_eq!(it_tracks.len(), 1);
    assert_eq!(it_tracks[0].title, "Hot Rats Theme");

    let album = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Abbey Road".to_owned(),
            creators: vec!["Beatles".to_owned()],
            release_year: Some(1969),
            thumbnail_url: Some("https://i.scdn.co/image/abbey".to_owned()),
            identifiers: vec![crate::db::EntryIdentifier {
                scheme: "spotify".to_owned(),
                value: "spotify:album:0ETFjACtuP2ADo6LFhL6HN".to_owned(),
            }],
            ..Default::default()
        })
        .await
        .unwrap();

    let svc = crate::service::RocheService::new(db.clone(), DummyWebFetcher, Config::default());
    let mut detail = db.catalog().get_entry_detail(&album.id).await.unwrap().unwrap();
    let mock_fetcher = |url: &Url| -> Option<String> {
        if url.as_str().contains("open.spotify.com/embed/album/0ETFjACtuP2ADo6LFhL6HN") {
            Some(sp_html.to_owned())
        } else {
            None
        }
    };
    enrich_album_tracklist_with_fetcher(&svc, &mut detail, &mock_fetcher).await;

    let album_html =
        read_body_html(get_authed(&app, &format!("/entries/{}", album.id), &cookie).await).await;
    assert!(album_html.contains("table class=\"tracklist\""));
    assert!(album_html.contains("Come Together"));
    assert!(album_html.contains("4:19"));
    assert!(album_html.contains("https://open.spotify.com/track/2EqlS6tkEnglzr7tkKAAYD"));
    assert!(album_html.contains("2 songs, 7 min 21 sec"));

    let artists = db.catalog().find_artist_ids_by_names(&["Beatles".to_owned()]).await.unwrap();
    let beatles_id = artists.get("beatles").expect("synthesized Beatles artist");
    let artist_html =
        read_body_html(get_authed(&app, &format!("/entries/{beatles_id}"), &cookie).await).await;
    assert!(artist_html.contains("class=\"discography-grid\""));
    assert!(artist_html.contains("Abbey Road"));
    assert!(artist_html.contains("1969 &middot; Album"));

    // Artist external discography enrichment + one-click "+ Add" to library
    let itunes_discog = r#"{"results":[
        {"wrapperType":"collection","collectionType":"Album","artistName":"The Beatles","collectionName":"Abbey Road (Remastered)","releaseDate":"1969-09-26T07:00:00Z","artworkUrl100":"https://is1-ssl.mzstatic.com/image/100x100bb.jpg","collectionId":1441164426},
        {"wrapperType":"collection","collectionType":"Album","artistName":"The Beatles","collectionName":"Let It Be","releaseDate":"1970-05-08T07:00:00Z","artworkUrl100":"https://is1-ssl.mzstatic.com/image/letitbe/100x100bb.jpg","collectionId":1441164495},
        {"wrapperType":"collection","collectionType":"Album","artistName":"The Beatles","collectionName":"Hey Jude - Single","releaseDate":"1968-08-26T07:00:00Z","collectionId":999}
    ]}"#;
    let parsed_discog =
        super::artist_enrich::parse_itunes_artist_discography(itunes_discog, "Beatles");
    assert_eq!(parsed_discog.len(), 2);
    assert_eq!(parsed_discog[0].title, "Let It Be");
    assert_eq!(parsed_discog[0].release_year, Some(1970));

    let mut artist_detail = db.catalog().get_entry_detail(beatles_id).await.unwrap().unwrap();
    let discog_fetcher = |url: &Url| -> Option<String> {
        if url.as_str().contains("itunes.apple.com/search") {
            Some(itunes_discog.to_owned())
        } else {
            None
        }
    };
    super::artist_enrich::enrich_artist_detail_with_fetcher(
        &svc,
        &mut artist_detail,
        &discog_fetcher,
    )
    .await;

    let enriched_artist_html =
        read_body_html(get_authed(&app, &format!("/entries/{beatles_id}"), &cookie).await).await;
    assert!(enriched_artist_html.contains("1 of 2 in library"));
    assert!(enriched_artist_html.contains("discography-card-external"));
    assert!(enriched_artist_html.contains("Let It Be"));

    let add_resp = post_form(
        &app,
        &format!("/web/entries/{beatles_id}/add-album"),
        &cookie,
        &[
            ("_csrf", &csrf),
            ("title", "Let It Be"),
            ("release_year", "1970"),
            ("apple_music_id", "1441164495"),
        ],
    )
    .await;
    assert_eq!(add_resp.status(), StatusCode::SEE_OTHER);
    let after_add_html =
        read_body_html(get_authed(&app, &format!("/entries/{beatles_id}"), &cookie).await).await;
    assert!(!after_add_html.contains("discography-card-external"));
    assert!(after_add_html.contains("Let It Be"));
}
