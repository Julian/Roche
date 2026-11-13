#![allow(clippy::unwrap_used, clippy::too_many_lines)]

use std::collections::HashSet;

use chrono::{DateTime, Utc};
use serde::Deserialize;
use url::Url;

use super::*;
use crate::db::{
    ContentType, Db, Feed, FeedType, ItemFilter, ItemStatus, PollBatch, PollItemCandidate,
};

#[test]
fn test_deterministic_uuid_and_hrw_work_splitting() {
    let f_id1 = deterministic_feed_id("https://example.com/feed.xml");
    let f_id2 = deterministic_feed_id("https://example.com/feed.xml");
    assert_eq!(f_id1, f_id2);
    assert_eq!(f_id1.len(), 36);

    let i_id1 =
        deterministic_item_id(Some("https://example.com/feed.xml"), "https://example.com/1");
    let i_id2 =
        deterministic_item_id(Some("https://example.com/feed.xml"), "https://example.com/1");
    assert_eq!(i_id1, i_id2);
    assert_ne!(f_id1, i_id1);

    let now = DateTime::parse_from_rfc3339("2026-06-01T12:00:00Z").unwrap().with_timezone(&Utc);
    let mut peers = vec![
        PeerNode {
            peer_id: "server".to_string(),
            device_name: "Headless".to_string(),
            network_tier: NetworkTier::Wifi,
            weight: compute_throughput_weight(0, 0, 0.0),
            last_seen_at: now,
        },
        PeerNode {
            peer_id: "android-1".to_string(),
            device_name: "Pixel".to_string(),
            network_tier: NetworkTier::Wifi,
            weight: compute_throughput_weight(0, 0, 0.0),
            last_seen_at: now,
        },
    ];

    // 1. Preliminary allocation: both online peers start at weight 1.0 and split feeds roughly
    // equally, with exactly one peer claiming each due feed.
    let mut server_wins = 0;
    let mut android_wins = 0;
    for idx in 0..100 {
        let feed_url = Url::parse(&format!("https://example.com/feed-{idx}.xml")).unwrap();
        let feed = Feed {
            id: deterministic_feed_id(feed_url.as_str()),
            feed_type: FeedType::Rss,
            url: feed_url,
            title: Some(format!("Feed {idx}")),
            description: None,
            language: None,
            poll_interval: 30,
            last_polled: None,
            last_polled_by: None,
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: now,
            unread_count: 0,
        };
        let s_polls = should_peer_poll_feed("server", &peers, &feed, now);
        let a_polls = should_peer_poll_feed("android-1", &peers, &feed, now);
        assert_ne!(s_polls, a_polls, "Exactly one peer must claim each healthy due feed");
        if s_polls {
            server_wins += 1;
        } else {
            android_wins += 1;
        }
    }
    assert!(server_wins > 35 && server_wins < 65, "server_wins={server_wins}");
    assert_eq!(server_wins + android_wins, 100);

    // 2. Dynamic throughput reallocation: when the faster peer processes more feeds and items
    // per second, it is dynamically allocated a much larger portion of the remaining feeds.
    peers[0].weight = compute_throughput_weight(8, 200, 2.0);
    peers[1].weight = compute_throughput_weight(2, 25, 3.0);
    assert!(peers[0].weight > peers[1].weight * 4.0);
    let mut fast_wins = 0;
    for idx in 0..100 {
        let feed_url = Url::parse(&format!("https://example.com/feed-{idx}.xml")).unwrap();
        let feed = Feed {
            id: deterministic_feed_id(feed_url.as_str()),
            feed_type: FeedType::Rss,
            url: feed_url,
            title: Some(format!("Feed {idx}")),
            description: None,
            language: None,
            poll_interval: 30,
            last_polled: None,
            last_polled_by: None,
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: now,
            unread_count: 0,
        };
        if should_peer_poll_feed("server", &peers, &feed, now) {
            fast_wins += 1;
        }
    }
    assert!(
        fast_wins > server_wins && fast_wins >= 75,
        "Faster peer must dynamically receive a larger portion: fast_wins={fast_wins}, initial={server_wins}"
    );

    // When the server fails on a feed (e.g. HTTP 403), Android immediately rescues it even
    // though `last_polled` was 1 minute ago (within the 30m backoff window for the server).
    let blocked_url = Url::parse("https://blocked.example.com/rss.xml").unwrap();
    let blocked_feed = Feed {
        id: deterministic_feed_id(blocked_url.as_str()),
        feed_type: FeedType::Rss,
        url: blocked_url,
        title: Some("Blocked".to_string()),
        description: None,
        language: None,
        poll_interval: 30,
        last_polled: Some(now - chrono::Duration::minutes(1)),
        last_polled_by: Some("server".to_string()),
        last_error: Some("Upstream server returned HTTP 403 Forbidden".to_string()),
        last_error_by: Some("server".to_string()),
        is_active: true,
        created_at: now,
        unread_count: 0,
    };
    assert!(!should_peer_poll_feed("server", &peers, &blocked_feed, now));
    assert!(should_peer_poll_feed("android-1", &peers, &blocked_feed, now));
}

#[tokio::test]
async fn test_peer_sync_merges_without_duplication_and_respects_tombstones() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/rss.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Example"), None).await.unwrap();

    let item1_url = Url::parse("https://example.com/post-1").unwrap();
    let item2_url = Url::parse("https://example.com/post-2").unwrap();
    db.feeds()
        .record_poll_batch(
            &feed.id,
            &PollBatch {
                title: Some("Example".to_string()),
                description: None,
                language: None,
                items: vec![
                    PollItemCandidate {
                        title: "Post 1".to_string(),
                        url: item1_url.clone(),
                        author: None,
                        published_at: None,
                        summary: "Short".to_string(),
                        content_html: Some("<p>Short</p>".to_string()),
                        thumbnail_url: None,
                        audio_url: None,
                        duration: None,
                        content_type: ContentType::Article,
                    },
                    PollItemCandidate {
                        title: "Post 2".to_string(),
                        url: item2_url.clone(),
                        author: None,
                        published_at: None,
                        summary: "To be deleted".to_string(),
                        content_html: None,
                        thumbnail_url: None,
                        audio_url: None,
                        duration: None,
                        content_type: ContentType::Article,
                    },
                ],
            },
        )
        .await
        .unwrap();

    // Delete Post 2 on server; verify re-polling the feed does NOT resurrect Post 2!
    let post2_id = deterministic_item_id(Some(feed_url.as_str()), item2_url.as_str());
    assert!(db.items().delete(&post2_id).await.unwrap());
    db.feeds()
        .record_poll_batch(
            &feed.id,
            &PollBatch {
                title: None,
                description: None,
                language: None,
                items: vec![PollItemCandidate {
                    title: "Post 2".to_string(),
                    url: item2_url.clone(),
                    author: None,
                    published_at: None,
                    summary: "To be deleted".to_string(),
                    content_html: None,
                    thumbnail_url: None,
                    audio_url: None,
                    duration: None,
                    content_type: ContentType::Article,
                }],
            },
        )
        .await
        .unwrap();
    assert!(db.items().get(&post2_id).await.unwrap().is_none());

    // Android peer syncs:
    // 1. Sends richer content + read/star state for Post 1 (using a different local feed_id!)
    // 2. Sends Post 2 (which was deleted on server -> must remain suppressed by tombstone)
    // 3. Sends Post 3 (fetched by Android directly from feed -> must be added to server)
    let now = SyncRepo::now_utc();
    let item3_url = Url::parse("https://example.com/post-3").unwrap();
    let req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel 10 Pro".to_string()),
        network_tier: NetworkTier::Wifi,
        weight: Some(4.2),
        updated_after: None,
        feeds: vec![PeerFeedRecord {
            id: "android-local-feed-id".to_string(),
            feed_type: FeedType::Rss,
            url: feed_url.clone(),
            title: Some("Example".to_string()),
            description: Some("Enriched description".to_string()),
            language: Some("en".to_string()),
            poll_interval: 30,
            last_polled: Some(now),
            last_polled_by: Some("android-peer-1".to_string()),
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: None,
        }],
        items: vec![
            PeerItemRecord {
                id: "android-local-item-1".to_string(),
                feed_id: Some("android-local-feed-id".to_string()),
                feed_url: Some(feed_url.clone()),
                title: "Post 1".to_string(),
                url: item1_url.clone(),
                author: Some("Alice".to_string()),
                published_at: Some(now),
                created_at: Some(now),
                summary: Some("Much longer and richer summary from peer".to_string()),
                content_html: Some("<p>Much longer full article body from peer</p>".to_string()),
                thumbnail_url: Some("https://example.com/hero.jpg".to_string()),
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                status: ItemStatus::Archived,
                is_favorite: true,
                read_at: Some(now),
                read_progress: 0.9,
                interaction_updated_at: Some(now),
                tag_names: vec!["distributed".to_string(), "systems".to_string()],
            },
            PeerItemRecord {
                id: post2_id.clone(),
                feed_id: Some(feed.id.clone()),
                feed_url: Some(feed_url.clone()),
                title: "Post 2".to_string(),
                url: item2_url.clone(),
                author: None,
                published_at: None,
                created_at: Some(now - chrono::Duration::minutes(5)),
                summary: Some("Old".to_string()),
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                status: ItemStatus::Unread,
                is_favorite: false,
                read_at: None,
                read_progress: 0.0,
                interaction_updated_at: None,
                tag_names: vec![],
            },
            PeerItemRecord {
                id: String::new(),
                feed_id: Some("android-local-feed-id".to_string()),
                feed_url: Some(feed_url.clone()),
                title: "Post 3 Fetched On Android".to_string(),
                url: item3_url.clone(),
                author: Some("Bob".to_string()),
                published_at: Some(now),
                created_at: Some(now),
                summary: Some("Fetched by phone".to_string()),
                content_html: Some("<p>Fetched by phone</p>".to_string()),
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                status: ItemStatus::Unread,
                is_favorite: false,
                read_at: None,
                read_progress: 0.0,
                interaction_updated_at: None,
                tag_names: vec!["mobile".to_string()],
            },
        ],
        tags: vec![],
        tombstones: vec![],
        ..PeerSyncRequest::default()
    };

    let (resp, inserted) = db.sync().merge_peer_state(&req).await.unwrap();
    assert_eq!(inserted.len(), 1);
    assert!(!resp.has_more);
    assert_eq!(resp.feeds.len(), 1);
    assert_eq!(
        resp.items.len(),
        2,
        "Only Post 1 and Post 3 should exist (no duplicates, Post 2 tombstoned)"
    );
    assert_eq!(resp.active_peers.len(), 2);

    let post1_id = deterministic_item_id(Some(feed_url.as_str()), item1_url.as_str());
    let merged_post1 = db.items().get(&post1_id).await.unwrap().unwrap();
    assert_eq!(merged_post1.author.as_deref(), Some("Alice"));
    assert_eq!(
        merged_post1.content_html.as_deref(),
        Some("<p>Much longer full article body from peer</p>")
    );
    assert_eq!(merged_post1.thumbnail_url.as_deref(), Some("https://example.com/hero.jpg"));
    assert_eq!(merged_post1.status, ItemStatus::Archived);
    assert!(merged_post1.is_favorite);
    assert!(merged_post1.read_at.is_some());
    assert!((merged_post1.read_progress - 0.9).abs() < 1e-6);
    assert_eq!(merged_post1.tag_names.0, vec!["distributed".to_string(), "systems".to_string()]);

    // Delta sync: backdate Post 3 so only items modified at or after `watermark` are returned
    let watermark = now + chrono::Duration::seconds(10);
    let post3_id = deterministic_item_id(Some(feed_url.as_str()), item3_url.as_str());
    sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
        .bind(now - chrono::Duration::seconds(60))
        .bind(&post3_id)
        .execute(db.pool())
        .await
        .unwrap();
    sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
        .bind(watermark)
        .bind(&post1_id)
        .execute(db.pool())
        .await
        .unwrap();

    let delta_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel 10 Pro".to_string()),
        network_tier: NetworkTier::Wifi,
        weight: None,
        updated_after: Some(watermark),
        feeds: vec![],
        items: vec![],
        tags: vec![],
        tombstones: vec![],
        ..PeerSyncRequest::default()
    };
    let (delta_resp, _) = db.sync().merge_peer_state(&delta_req).await.unwrap();
    assert!(!delta_resp.has_more);
    let android_peer =
        delta_resp.active_peers.iter().find(|p| p.peer_id == "android-peer-1").unwrap();
    assert!(
        (android_peer.weight - 4.2).abs() < 1e-6,
        "Delta sync without a new poll sample must preserve previously measured throughput weight"
    );
    assert_eq!(
        delta_resp.items.len(),
        1,
        "Delta sync must return only Post 1 (modified at watermark) and omit unchanged Post 3"
    );
    assert_eq!(delta_resp.items[0].id, post1_id);

    // Routine server poll bumps feeds.updated_at, but peer archive with an older cursor must still succeed
    db.feeds().mark_error(&feed.id, "transient error").await.unwrap();
    let archive_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel 10 Pro".to_string()),
        network_tier: NetworkTier::Wifi,
        weight: None,
        updated_after: Some(now - chrono::Duration::seconds(30)),
        feeds: vec![PeerFeedRecord {
            id: feed.id.clone(),
            url: feed_url.clone(),
            title: Some("Example Blog".to_string()),
            description: None,
            language: None,
            feed_type: FeedType::Rss,
            poll_interval: 30,
            last_polled: Some(now),
            last_polled_by: Some("android-peer-1".to_string()),
            last_error: None,
            last_error_by: None,
            is_active: false,
            created_at: None,
        }],
        items: vec![],
        tags: vec![],
        tombstones: vec![],
        ..PeerSyncRequest::default()
    };
    let (archive_resp, _) = db.sync().merge_peer_state(&archive_req).await.unwrap();
    assert!(!archive_resp.feeds[0].is_active);

    // A second peer doing an initial cursorless sync (updated_after: None) with default is_active: true
    // must NOT overwrite the server's explicit archive state
    let peer2_req = PeerSyncRequest {
        peer_id: "android-peer-2".to_string(),
        device_name: Some("Tablet".to_string()),
        network_tier: NetworkTier::Wifi,
        weight: None,
        updated_after: None,
        feeds: vec![PeerFeedRecord {
            id: feed.id.clone(),
            url: feed_url,
            title: Some("Example Blog".to_string()),
            description: None,
            language: None,
            feed_type: FeedType::Rss,
            poll_interval: 30,
            last_polled: None,
            last_polled_by: None,
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: None,
        }],
        items: vec![],
        tags: vec![],
        tombstones: vec![],
        ..PeerSyncRequest::default()
    };
    let (peer2_resp, _) = db.sync().merge_peer_state(&peer2_req).await.unwrap();
    assert!(
        !peer2_resp.feeds[0].is_active,
        "Cursorless initial sync from a second peer must not unarchive a server-archived feed"
    );
}

#[test]
fn test_canonical_sync_urls_and_deterministic_ids() {
    assert_eq!(canonicalize_sync_url("  HTTPS://Example.COM:443  "), "https://example.com/");
    assert_eq!(
        canonicalize_sync_url("http://Example.COM:80/Feed.xml"),
        "http://example.com/Feed.xml"
    );
    assert_eq!(
        canonicalize_sync_url("https://Example.COM:8443/demo"),
        "https://example.com:8443/demo"
    );
    assert_eq!(canonicalize_sync_url("  NOT A VALID URL  "), "not a valid url");

    assert_eq!(
        feed_natural_key("HTTPS://Example.COM:443"),
        feed_natural_key("https://example.com/")
    );
    assert_eq!(
        deterministic_feed_id("HTTPS://Example.COM:443"),
        deterministic_feed_id("https://example.com/")
    );
    assert_eq!(
        deterministic_item_id(
            Some("HTTP://Example.COM:80/rss.xml"),
            "HTTPS://Example.COM:443/post-1"
        ),
        deterministic_item_id(Some("http://example.com/rss.xml"), "https://example.com/post-1")
    );
}

#[tokio::test]
async fn test_subsecond_delta_sync_and_multipage_content_html() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/subsec-rss.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Subsec"), None).await.unwrap();

    let batch_items: Vec<PollItemCandidate> = (0..205)
        .map(|idx| PollItemCandidate {
            title: format!("Article {idx}"),
            url: Url::parse(&format!("https://example.com/article-{idx}")).unwrap(),
            author: None,
            published_at: None,
            summary: format!("Summary {idx}"),
            content_html: Some(format!("<p>Body content {idx}</p>")),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
        })
        .collect();

    db.feeds()
        .record_poll_batch(
            &feed.id,
            &PollBatch {
                title: Some("Subsec".to_string()),
                description: None,
                language: None,
                items: batch_items,
            },
        )
        .await
        .unwrap();

    // Full sync with >200 items must include content_html on ALL pages (not just page 1)
    let full_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        ..PeerSyncRequest::default()
    };
    let (full_resp, _) = db.sync().merge_peer_state(&full_req).await.unwrap();
    assert_eq!(full_resp.items.len(), 205);
    assert!(!full_resp.has_more);
    assert!(
        full_resp.items.iter().all(|item| item.content_html.is_some()),
        "Every synced item across page 1 and page 2 must retain inline content_html"
    );

    // Subsecond updated_after: item updated 500ms after `since` within the same second MUST be returned
    let old_ts = DateTime::parse_from_rfc3339("2026-11-11T20:00:00Z").unwrap().with_timezone(&Utc)
        - chrono::Duration::seconds(30);
    sqlx::query("UPDATE items SET updated_at = ?").bind(old_ts).execute(db.pool()).await.unwrap();

    let since =
        DateTime::parse_from_rfc3339("2026-11-11T20:00:00.100Z").unwrap().with_timezone(&Utc);
    let before_in_same_sec =
        DateTime::parse_from_rfc3339("2026-11-11T20:00:00.050Z").unwrap().with_timezone(&Utc);
    let after_500ms = since + chrono::Duration::milliseconds(500);

    let id0 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-0");
    let id1 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-1");

    sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
        .bind(before_in_same_sec)
        .bind(&id0)
        .execute(db.pool())
        .await
        .unwrap();
    sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
        .bind(after_500ms)
        .bind(&id1)
        .execute(db.pool())
        .await
        .unwrap();

    let filtered = db
        .items()
        .list_filtered_page(&ItemFilter { updated_after: Some(since), ..ItemFilter::default() })
        .await
        .unwrap();
    assert_eq!(filtered.items.len(), 1);
    assert_eq!(filtered.items[0].id, id1);

    let delta_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        updated_after: Some(since),
        ..PeerSyncRequest::default()
    };
    let (delta_resp, _) = db.sync().merge_peer_state(&delta_req).await.unwrap();
    assert!(
        delta_resp.items.iter().any(|i| i.id == id1 && i.content_html.is_some()),
        "Delta merge_peer_state must return item updated 500ms after since within same second"
    );
}

#[tokio::test]
async fn test_tombstone_resurrection_requires_newer_created_at_and_preserves_custom_feed_title() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/tombstone-feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Original"), None).await.unwrap();
    let tag = db.tags().create("ephemeral", None).await.unwrap();

    assert!(db.feeds().delete(&feed.id).await.unwrap());
    assert!(db.tags().delete(&tag.id).await.unwrap());
    let del_after = SyncRepo::now_utc();

    // Stale offline poll (last_polled > del_at, created_at: None / older) must NOT resurrect feed or tag
    let stale_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        feeds: vec![PeerFeedRecord {
            id: feed.id.clone(),
            feed_type: FeedType::Rss,
            url: feed_url.clone(),
            title: Some("Stale Offline Copy".to_string()),
            description: None,
            language: None,
            poll_interval: 30,
            last_polled: Some(del_after + chrono::Duration::minutes(5)),
            last_polled_by: Some("android-peer-1".to_string()),
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: None,
        }],
        tags: vec![PeerTagRecord {
            id: tag.id.clone(),
            name: "ephemeral".to_string(),
            color: None,
            created_at: Some(del_after - chrono::Duration::minutes(5)),
        }],
        ..PeerSyncRequest::default()
    };
    let (stale_resp, _) = db.sync().merge_peer_state(&stale_req).await.unwrap();
    assert!(stale_resp.feeds.is_empty(), "Stale last_polled must not resurrect deleted feed");
    assert!(stale_resp.tags.is_empty(), "Older created_at must not resurrect deleted tag");

    // Explicit re-creation with created_at > del_at clears tombstones and recreates feed + tag
    let recreate_ts = del_after + chrono::Duration::seconds(10);
    let recreate_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        feeds: vec![PeerFeedRecord {
            id: feed.id.clone(),
            feed_type: FeedType::Rss,
            url: feed_url.clone(),
            title: Some("Recreated Feed".to_string()),
            description: None,
            language: None,
            poll_interval: 30,
            last_polled: Some(recreate_ts),
            last_polled_by: Some("android-peer-1".to_string()),
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: Some(recreate_ts),
        }],
        tags: vec![PeerTagRecord {
            id: tag.id.clone(),
            name: "ephemeral".to_string(),
            color: None,
            created_at: Some(recreate_ts),
        }],
        ..PeerSyncRequest::default()
    };
    let (recreate_resp, _) = db.sync().merge_peer_state(&recreate_req).await.unwrap();
    assert_eq!(recreate_resp.feeds.len(), 1);
    assert_eq!(recreate_resp.tags.len(), 1);

    // User renames feed on server; subsequent peer poll with raw RSS title must NOT clobber custom title
    assert!(db.feeds().update(&feed.id, Some("My Custom Feed Name"), None, None).await.unwrap());
    let poll_bump_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        feeds: vec![PeerFeedRecord {
            id: feed.id.clone(),
            feed_type: FeedType::Rss,
            url: feed_url,
            title: Some("Raw Upstream RSS Title".to_string()),
            description: Some("Updated desc".to_string()),
            language: None,
            poll_interval: 30,
            last_polled: Some(recreate_ts + chrono::Duration::minutes(30)),
            last_polled_by: Some("android-peer-1".to_string()),
            last_error: None,
            last_error_by: None,
            is_active: true,
            created_at: Some(recreate_ts),
        }],
        ..PeerSyncRequest::default()
    };
    let (bump_resp, _) = db.sync().merge_peer_state(&poll_bump_req).await.unwrap();
    assert_eq!(bump_resp.feeds[0].title.as_deref(), Some("My Custom Feed Name"));
    assert_eq!(bump_resp.feeds[0].description.as_deref(), Some("Updated desc"));
}

#[tokio::test]
async fn test_delta_sync_cursor_safety_when_outgoing_items_exceed_cap() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/delta-cap-rss.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Delta Cap"), None).await.unwrap();

    let batch_items: Vec<PollItemCandidate> = (0..5)
        .map(|idx| PollItemCandidate {
            title: format!("Cap Item {idx}"),
            url: Url::parse(&format!("https://example.com/cap-item-{idx}")).unwrap(),
            author: None,
            published_at: None,
            summary: format!("Summary {idx}"),
            content_html: Some(format!("<p>Body {idx}</p>")),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
        })
        .collect();

    db.feeds()
        .record_poll_batch(
            &feed.id,
            &PollBatch {
                title: Some("Delta Cap".to_string()),
                description: None,
                language: None,
                items: batch_items,
            },
        )
        .await
        .unwrap();

    let base_ts =
        DateTime::parse_from_rfc3339("2026-06-01T10:00:00.000Z").unwrap().with_timezone(&Utc);
    let mut expected_ids = Vec::new();
    for idx in 0..5 {
        let item_id = deterministic_item_id(
            Some(feed_url.as_str()),
            &format!("https://example.com/cap-item-{idx}"),
        );
        let item_ts = base_ts + chrono::Duration::seconds((idx + 1) * 5);
        sqlx::query("UPDATE items SET updated_at = ? WHERE id = ?")
            .bind(item_ts)
            .bind(&item_id)
            .execute(db.pool())
            .await
            .unwrap();
        expected_ids.push(item_id);
    }

    // Round 1: 5 updated items exist after `base_ts`, outgoing cap is 3.
    let req1 = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        updated_after: Some(base_ts),
        ..PeerSyncRequest::default()
    };
    let (resp1, _) = db.sync().merge_peer_state_with_limit(&req1, 3).await.unwrap();
    assert!(resp1.has_more, "Exceeding outgoing item cap on delta sync must set has_more = true");
    assert_eq!(resp1.items.len(), 3);
    assert_eq!(resp1.items[0].id, expected_ids[0]);
    assert_eq!(resp1.items[1].id, expected_ids[1]);
    assert_eq!(resp1.items[2].id, expected_ids[2]);
    assert_eq!(
        resp1.sync_timestamp,
        base_ts + chrono::Duration::seconds(15),
        "sync_timestamp must match the batch boundary (last returned item's updated_at), not now"
    );
    assert_eq!(resp1.next_after_item_id.as_deref(), Some(expected_ids[2].as_str()));

    // Round 2: Using resp1.sync_timestamp + next_after_item_id fetches remaining items cleanly.
    let before_round2 = SyncRepo::now_utc();
    let req2 = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        updated_after: Some(resp1.sync_timestamp),
        after_item_id: resp1.next_after_item_id.clone(),
        ..PeerSyncRequest::default()
    };
    let (resp2, _) = db.sync().merge_peer_state_with_limit(&req2, 3).await.unwrap();
    assert!(!resp2.has_more, "Final delta sync batch must finish with has_more == false");
    assert_eq!(resp2.next_after_item_id, None);
    assert_eq!(resp2.items.len(), 2);
    assert!(resp2.sync_timestamp >= before_round2);

    let mut received_ids: HashSet<String> = resp1.items.into_iter().map(|i| i.id).collect();
    received_ids.extend(resp2.items.into_iter().map(|i| i.id));
    let expected_set: HashSet<String> = expected_ids.iter().cloned().collect();
    assert_eq!(
        received_ids, expected_set,
        "Multi-round delta sync must deliver every updated item without dropping any"
    );

    // Tied-timestamp batch test: all 5 items share the exact same `updated_at = tied_ts` (e.g.
    // bulk archive or OPML import), paginated at cap = 2 across 3 rounds with zero duplicates
    // and zero drops.
    let tied_ts = base_ts + chrono::Duration::minutes(5);
    sqlx::query("UPDATE items SET updated_at = ?").bind(tied_ts).execute(db.pool()).await.unwrap();

    let mut tied_sorted_ids = expected_ids.clone();
    tied_sorted_ids.sort();

    let mut cursor_ts = tied_ts - chrono::Duration::seconds(10);
    let mut after_id: Option<String> = None;
    let mut collected_tied_ids = Vec::new();
    let mut rounds = 0;
    loop {
        rounds += 1;
        let page_req = PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            updated_after: Some(cursor_ts),
            after_item_id: after_id.clone(),
            ..PeerSyncRequest::default()
        };
        let (page_resp, _) = db.sync().merge_peer_state_with_limit(&page_req, 2).await.unwrap();
        collected_tied_ids.extend(page_resp.items.iter().map(|i| i.id.clone()));
        if !page_resp.has_more {
            assert_eq!(page_resp.next_after_item_id, None);
            break;
        }
        assert!(page_resp.next_after_item_id.is_some());
        cursor_ts = page_resp.sync_timestamp;
        after_id = page_resp.next_after_item_id;
        assert!(rounds < 10, "Tied-timestamp pagination must terminate cleanly");
    }
    assert_eq!(rounds, 3, "5 tied-timestamp items at cap = 2 must take 3 rounds (2 + 2 + 1)");
    assert_eq!(
        collected_tied_ids, tied_sorted_ids,
        "Keyset continuation must deliver every tied-timestamp item in deterministic id order without duplicates"
    );
}

#[test]
fn test_cross_stack_golden_vectors() {
    #[derive(Deserialize)]
    struct CanonicalUrlVector {
        input: String,
        expected: String,
    }

    #[derive(Deserialize)]
    struct EntityIdVector {
        feed_url: String,
        item_url: String,
        tag_name: String,
        expected_feed_id: String,
        expected_item_id: String,
        expected_tag_id: String,
    }

    #[derive(Deserialize)]
    struct HrwPeerInput {
        peer_id: String,
        weight: f64,
    }

    #[derive(Deserialize)]
    struct HrwElectionVector {
        feed_url: String,
        peers: Vec<HrwPeerInput>,
        expected_winner: String,
    }

    #[derive(Deserialize)]
    struct GoldenVectors {
        canonical_urls: Vec<CanonicalUrlVector>,
        entity_ids: Vec<EntityIdVector>,
        hrw_elections: Vec<HrwElectionVector>,
    }

    let vectors_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../testdata/cross_stack_vectors.json");
    let raw = std::fs::read_to_string(&vectors_path).unwrap();
    let vectors: GoldenVectors = serde_json::from_str(&raw).unwrap();

    assert!(vectors.canonical_urls.len() >= 6);
    for case in &vectors.canonical_urls {
        assert_eq!(
            canonicalize_sync_url(&case.input),
            case.expected,
            "canonical_urls mismatch for input '{}'",
            case.input
        );
    }

    assert!(vectors.entity_ids.len() >= 3);
    for case in &vectors.entity_ids {
        assert_eq!(
            deterministic_feed_id(&case.feed_url),
            case.expected_feed_id,
            "feed_id mismatch for '{}'",
            case.feed_url
        );
        assert_eq!(
            deterministic_item_id(Some(&case.feed_url), &case.item_url),
            case.expected_item_id,
            "item_id mismatch for ('{}', '{}')",
            case.feed_url,
            case.item_url
        );
        assert_eq!(
            deterministic_tag_id(&case.tag_name),
            case.expected_tag_id,
            "tag_id mismatch for '{}'",
            case.tag_name
        );
    }

    assert!(vectors.hrw_elections.len() >= 3);
    let epoch_zero = DateTime::UNIX_EPOCH;
    for case in &vectors.hrw_elections {
        let peer_nodes: Vec<PeerNode> = case
            .peers
            .iter()
            .map(|p| PeerNode {
                peer_id: p.peer_id.clone(),
                device_name: p.peer_id.clone(),
                network_tier: NetworkTier::Wifi,
                weight: p.weight,
                last_seen_at: epoch_zero,
            })
            .collect();
        let winner =
            select_feed_poller(&peer_nodes, &case.feed_url, 30, epoch_zero, None, None, None, None);
        assert_eq!(
            winner,
            Some(case.expected_winner.as_str()),
            "hrw_elections mismatch for '{}'",
            case.feed_url
        );
    }
}
