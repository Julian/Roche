#![allow(clippy::unwrap_used, clippy::too_many_lines)]

use std::collections::HashSet;

use chrono::{DateTime, Utc};
use serde::Deserialize;
use url::Url;

use super::*;
use crate::db::{
    ActivityEntityType, ConsumptionStatus, ContentType, Db, EntryKind, Feed, FeedType, ItemFilter,
    ItemStatus, ManualItemInput, NewItem, PollBatch, PollItemCandidate,
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

    assert_eq!(STARRED_COLLECTION_ID, deterministic_system_collection_id("starred"));
    assert_eq!(UP_NEXT_COLLECTION_ID, deterministic_system_collection_id("up_next"));
    assert_eq!(ALBUMS_1001_COLLECTION_ID, deterministic_system_collection_id("1001_albums"));

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
    let post3_ts = now - chrono::Duration::seconds(60);
    let post3_id = deterministic_item_id(Some(feed_url.as_str()), item3_url.as_str());
    sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", post3_ts as _, post3_id)
        .execute(db.pool())
        .await
        .unwrap();
    sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", watermark as _, post1_id)
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
    sqlx::query!("UPDATE entries SET updated_at = ?", old_ts as _)
        .execute(db.pool())
        .await
        .unwrap();

    let since =
        DateTime::parse_from_rfc3339("2026-11-11T20:00:00.100Z").unwrap().with_timezone(&Utc);
    let before_in_same_sec =
        DateTime::parse_from_rfc3339("2026-11-11T20:00:00.050Z").unwrap().with_timezone(&Utc);
    let after_500ms = since + chrono::Duration::milliseconds(500);

    let id0 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-0");
    let id1 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-1");

    sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", before_in_same_sec as _, id0,)
        .execute(db.pool())
        .await
        .unwrap();
    sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", after_500ms as _, id1)
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
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", item_ts as _, item_id)
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
    sqlx::query!("UPDATE entries SET updated_at = ?", tied_ts as _)
        .execute(db.pool())
        .await
        .unwrap();

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
    struct IdentifierNormVector {
        kind: crate::db::EntryKind,
        scheme: String,
        raw_value: String,
        expected_scheme: String,
        expected_value: String,
    }

    #[derive(Deserialize)]
    struct EditionStripVector {
        input: String,
        expected: String,
    }

    #[derive(Deserialize)]
    struct CatalogEntryIdVector {
        kind: crate::db::EntryKind,
        title: String,
        creators: Vec<String>,
        identifiers: Vec<crate::db::EntryIdentifier>,
        expected_id: String,
    }

    #[derive(Deserialize)]
    struct AutoLogIdVector {
        entry_id: String,
        expected_log_id: String,
    }

    #[derive(Deserialize)]
    struct DynamicRuleEntryInput {
        kind: crate::db::EntryKind,
        creators: Vec<String>,
        release_year: Option<i32>,
        statuses: Vec<crate::db::ConsumptionStatus>,
    }

    #[derive(Deserialize)]
    struct DynamicRuleVector {
        rule_json: String,
        entry: DynamicRuleEntryInput,
        expected_match: bool,
    }

    #[derive(Deserialize)]
    struct RankedEntryInput {
        id: String,
        title: String,
    }

    #[derive(Deserialize)]
    struct RankedParityVector {
        name: String,
        is_priority_scope: bool,
        entries: Vec<RankedEntryInput>,
        orderings: Vec<Vec<Vec<String>>>,
        expected_ids: Vec<String>,
        expected_tiers: Vec<Option<char>>,
    }

    #[derive(Deserialize)]
    struct GoldenVectors {
        canonical_urls: Vec<CanonicalUrlVector>,
        entity_ids: Vec<EntityIdVector>,
        hrw_elections: Vec<HrwElectionVector>,
        preferred_identifier_schemes: Vec<String>,
        identifier_normalizations: Vec<IdentifierNormVector>,
        album_edition_stripping: Vec<EditionStripVector>,
        catalog_entry_ids: Vec<CatalogEntryIdVector>,
        auto_consumption_log_ids: Vec<AutoLogIdVector>,
        dynamic_collection_rules: Vec<DynamicRuleVector>,
        ranked_collection_parity: Vec<RankedParityVector>,
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

    assert_eq!(
        vectors.preferred_identifier_schemes,
        crate::db::PREFERRED_IDENTIFIER_SCHEMES.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>()
    );

    for case in &vectors.identifier_normalizations {
        assert_eq!(
            crate::db::normalize_identifier_scheme(&case.scheme),
            case.expected_scheme,
            "scheme norm mismatch for '{}'",
            case.scheme
        );
        assert_eq!(
            crate::db::normalize_identifier_value(case.kind, &case.scheme, &case.raw_value),
            case.expected_value,
            "value norm mismatch for ({:?}, '{}', '{}')",
            case.kind,
            case.scheme,
            case.raw_value
        );
    }

    for case in &vectors.album_edition_stripping {
        assert_eq!(
            crate::db::strip_album_edition_suffix(&case.input),
            case.expected,
            "edition strip mismatch for '{}'",
            case.input
        );
    }

    for case in &vectors.catalog_entry_ids {
        assert_eq!(
            crate::db::choose_deterministic_entry_id(
                None,
                case.kind,
                &case.title,
                &case.creators,
                &case.identifiers,
            ),
            case.expected_id,
            "catalog_entry_id mismatch for '{}'",
            case.title
        );
    }

    for case in &vectors.auto_consumption_log_ids {
        assert_eq!(
            deterministic_auto_consumption_log_id(&case.entry_id),
            case.expected_log_id,
            "auto_consumption_log_id mismatch for '{}'",
            case.entry_id
        );
    }

    for case in &vectors.dynamic_collection_rules {
        let rule = crate::db::parse_dynamic_collection_rule(Some(&case.rule_json)).unwrap();
        assert_eq!(
            crate::db::matches_dynamic_collection_rule(
                &rule,
                case.entry.kind,
                &case.entry.creators,
                case.entry.release_year,
                &case.entry.statuses,
            ),
            case.expected_match,
            "dynamic rule mismatch for '{}'",
            case.rule_json
        );
    }

    for case in &vectors.ranked_collection_parity {
        let ids: Vec<String> = case.entries.iter().map(|e| e.id.clone()).collect();
        let scores = crate::ranking::infer_scores(&ids, &case.orderings);
        let tiers = if !case.is_priority_scope && !case.orderings.is_empty() {
            crate::ranking::rescale_to_quantiles(&scores, crate::ranking::N_QUANTILES)
                .into_iter()
                .map(|(id, q)| (id, crate::ranking::tier_label(q)))
                .collect::<std::collections::HashMap<_, _>>()
        } else {
            std::collections::HashMap::new()
        };
        let mut sorted: Vec<&RankedEntryInput> = case.entries.iter().collect();
        sorted.sort_by(|a, b| {
            let sa = scores.get(&a.id).copied().unwrap_or_default().mean;
            let sb = scores.get(&b.id).copied().unwrap_or_default().mean;
            sb.total_cmp(&sa)
                .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
                .then_with(|| a.id.cmp(&b.id))
        });
        let mut i = 0usize;
        while i < sorted.len() {
            let base = scores.get(&sorted[i].id).copied().unwrap_or_default().mean;
            let mut j = i + 1;
            while j < sorted.len() {
                let cur = scores.get(&sorted[j].id).copied().unwrap_or_default().mean;
                if (cur - base).abs() <= crate::ranking::MEAN_TIE_EPSILON {
                    j += 1;
                } else {
                    break;
                }
            }
            if j - i > 1 {
                sorted[i..j].sort_by(|a, b| {
                    a.title
                        .to_lowercase()
                        .cmp(&b.title.to_lowercase())
                        .then_with(|| a.id.cmp(&b.id))
                });
            }
            i = j;
        }
        assert_eq!(
            sorted.iter().map(|e| e.id.clone()).collect::<Vec<_>>(),
            case.expected_ids,
            "ranked_collection_parity IDs mismatch for '{}'",
            case.name
        );
        assert_eq!(
            sorted.iter().map(|e| tiers.get(&e.id).copied()).collect::<Vec<_>>(),
            case.expected_tiers,
            "ranked_collection_parity tiers mismatch for '{}'",
            case.name
        );
    }
}

#[tokio::test]
async fn test_offline_peer_catalog_write_propagates_across_delta_sync_cursors() {
    use crate::db::{CollectionMode, CollectionRole, EntryKind};

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let sync = db.sync();

    let t1 = DateTime::parse_from_rfc3339("2026-01-01T10:00:00Z").unwrap().with_timezone(&Utc);

    // Peer B performs an initial sync FIRST, capturing a server cursor `cursor_b > t1`.
    let (init_b, _) = sync
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            device_name: Some("Phone B".to_owned()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: None,
            after_item_id: None,
            feeds: Vec::new(),
            items: Vec::new(),
            tags: Vec::new(),
            tombstones: Vec::new(),
            collections: Vec::new(),
            catalog_entries: Vec::new(),
            collection_entries: Vec::new(),
            collection_generators: Vec::new(),
            consumption_log: Vec::new(),
            comparisons: Vec::new(),
        })
        .await
        .unwrap();
    let cursor_b = init_b.sync_timestamp;
    assert!(cursor_b > t1);

    // Peer A now reconnects and pushes an offline catalog entry + collection + membership
    // whose client-side timestamps were recorded earlier at `t1 < cursor_b`.
    let col_id = "11111111-2222-3333-4444-555555555555".to_owned();
    let movie_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee".to_owned();
    let (_, _) = sync
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            device_name: Some("Tablet A".to_owned()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: None,
            after_item_id: None,
            feeds: Vec::new(),
            items: Vec::new(),
            tags: Vec::new(),
            tombstones: Vec::new(),
            collections: vec![PeerCollectionSpecRecord {
                collection_id: col_id.clone(),
                title: "Sci-Fi Classics".to_owned(),
                summary: None,
                slug: Some("sci-fi-classics".to_owned()),
                mode: CollectionMode::Manual,
                role: CollectionRole::List,
                rule_json: None,
                show_in_lists: true,
                color: None,
                created_at: Some(t1),
                updated_at: Some(t1),
            }],
            catalog_entries: vec![PeerCatalogEntryRecord {
                id: movie_id.clone(),
                kind: EntryKind::Movie,
                title: "Solaris".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1972),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(t1),
                updated_at: Some(t1),
            }],
            collection_entries: vec![PeerCollectionEntryRecord {
                collection_id: col_id.clone(),
                entry_id: movie_id.clone(),
                position: Some(1.0),
                added_at: t1,
            }],
            collection_generators: Vec::new(),
            consumption_log: Vec::new(),
            comparisons: Vec::new(),
        })
        .await
        .unwrap();

    // Peer B delta-syncs using `updated_after = Some(cursor_b)` and MUST receive Peer A's offline writes!
    let (delta_b, _) = sync
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            device_name: Some("Phone B".to_owned()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: Some(cursor_b),
            after_item_id: None,
            feeds: Vec::new(),
            items: Vec::new(),
            tags: Vec::new(),
            tombstones: Vec::new(),
            collections: Vec::new(),
            catalog_entries: Vec::new(),
            collection_entries: Vec::new(),
            collection_generators: Vec::new(),
            consumption_log: Vec::new(),
            comparisons: Vec::new(),
        })
        .await
        .unwrap();

    assert!(delta_b.collections.iter().any(|c| c.collection_id == col_id));
    assert!(delta_b.catalog_entries.iter().any(|e| e.id == movie_id));
    assert!(
        delta_b
            .collection_entries
            .iter()
            .any(|ce| ce.collection_id == col_id && ce.entry_id == movie_id)
    );
}

#[tokio::test]
async fn test_deleting_inbox_item_or_feed_preserves_diary_and_rankings() {
    use crate::db::{ConsumptionStatus, EntryKind, NewItem, ReadAtPatch, UpsertEntryInput};

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/podcast.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("Great Podcast"), None).await.unwrap();

    let ep_consumed_url = Url::parse("https://example.com/ep1").unwrap();
    let ep_consumed = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Episode 1 (Consumed)",
            url: &ep_consumed_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Audio,
        })
        .await
        .unwrap();

    let ep_unread_url = Url::parse("https://example.com/ep2").unwrap();
    let ep_unread = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Episode 2 (Unread)",
            url: &ep_unread_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Audio,
        })
        .await
        .unwrap();

    // Complete Episode 1 (`progress = 1.0`) -> auto-logs `consumed` on Episode 1 + parent Podcast!
    assert!(
        db.items()
            .update(
                &ep_consumed.id,
                Some(ItemStatus::Archived),
                None,
                Some(1.0),
                ReadAtPatch::Unchanged
            )
            .await
            .unwrap()
    );

    // Add a comparison ranking Episode 1 against another podcast episode.
    let other_ep = db
        .catalog()
        .upsert_entry(&UpsertEntryInput {
            id: None,
            kind: Some(EntryKind::PodcastEpisode),
            title: "Other Episode".to_owned(),
            creators: Vec::new(),
            url: None,
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata: None,
            identifiers: Vec::new(),
            parent_collection_id: None,
            parent_position: None,
        })
        .await
        .unwrap();
    db.catalog()
        .append_comparison(None, &[vec![ep_consumed.id.clone()], vec![other_ep.id.clone()]], None)
        .await
        .unwrap();

    // Delete Episode 1 from Inbox: vanishes from ItemRepo, stays intact in Catalog/Diary/Rankings!
    assert!(db.items().delete(&ep_consumed.id).await.unwrap());
    assert!(db.items().get(&ep_consumed.id).await.unwrap().is_none());
    let detail_after_item_del = db.catalog().get_entry_detail(&ep_consumed.id).await.unwrap();
    assert!(detail_after_item_del.is_some());
    assert!(
        detail_after_item_del
            .unwrap()
            .log_history
            .iter()
            .any(|l| l.status == ConsumptionStatus::Consumed)
    );

    // Delete the Feed itself: unconsumed Episode 2 is deleted, Feed vanishes from FeedRepo,
    // while Episode 1 + Podcast entry + consumption history + rankings remain intact!
    assert!(db.feeds().delete(&feed.id).await.unwrap());
    assert!(db.feeds().get(&feed.id).await.unwrap().is_none());
    assert!(db.items().get(&ep_unread.id).await.unwrap().is_none());
    assert!(db.catalog().get_entry_detail(&ep_unread.id).await.unwrap().is_none());

    let ep_detail = db.catalog().get_entry_detail(&ep_consumed.id).await.unwrap().unwrap();
    assert!(ep_detail.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed));
    let podcast_detail = db.catalog().get_entry_detail(&feed.id).await.unwrap();
    assert!(podcast_detail.is_some());

    // Re-subscribing to the same feed URL later works cleanly and restores the active feed!
    let resub = db
        .feeds()
        .create(FeedType::Rss, &feed_url, Some("Great Podcast Returns"), None)
        .await
        .unwrap();
    assert_eq!(resub.id, feed.id);
    assert!(resub.is_active);
}

#[tokio::test]
async fn test_peer_sync_preserves_single_consumption_log_and_never_auto_logs_bulk_archive() {
    use crate::db::{
        ConsumptionStatus, NewItem,
        sync::{PeerConsumptionLogRecord, deterministic_consumption_log_id},
    };

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Blog"), None).await.unwrap();

    let ep_url = Url::parse("https://example.com/podcast-ep1").unwrap();
    let ep = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Podcast Ep 1",
            url: &ep_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Audio,
        })
        .await
        .unwrap();

    let bulk_url = Url::parse("https://example.com/bulk-article").unwrap();
    let bulk_art = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Bulk Archived Article",
            url: &bulk_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();

    let t_android =
        DateTime::parse_from_rfc3339("2026-06-15T09:12:34.567Z").unwrap().with_timezone(&Utc);
    let log_id = deterministic_consumption_log_id(&ep.id, t_android.timestamp_millis(), "consumed");

    let (resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            device_name: Some("Android".to_owned()),
            network_tier: NetworkTier::Wifi,
            weight: None,
            updated_after: None,
            after_item_id: None,
            feeds: Vec::new(),
            items: vec![
                PeerItemRecord {
                    id: ep.id.clone(),
                    feed_id: Some(feed.id.clone()),
                    feed_url: Some(feed_url.clone()),
                    title: ep.title.clone(),
                    url: ep_url,
                    author: None,
                    published_at: None,
                    created_at: Some(t_android),
                    summary: None,
                    content_html: None,
                    thumbnail_url: None,
                    audio_url: Some("https://example.com/ep1.mp3".to_owned()),
                    duration: Some(1800),
                    content_type: ContentType::Audio,
                    status: ItemStatus::Unread,
                    is_favorite: false,
                    read_at: None,
                    read_progress: 1.0,
                    interaction_updated_at: Some(t_android),
                    tag_names: Vec::new(),
                },
                PeerItemRecord {
                    id: bulk_art.id.clone(),
                    feed_id: Some(feed.id.clone()),
                    feed_url: Some(feed_url),
                    title: bulk_art.title.clone(),
                    url: bulk_url,
                    author: None,
                    published_at: None,
                    created_at: Some(t_android),
                    summary: None,
                    content_html: None,
                    thumbnail_url: None,
                    audio_url: None,
                    duration: None,
                    content_type: ContentType::Article,
                    status: ItemStatus::Archived,
                    is_favorite: false,
                    read_at: Some(t_android),
                    read_progress: 0.95,
                    interaction_updated_at: Some(t_android),
                    tag_names: Vec::new(),
                },
            ],
            tags: Vec::new(),
            tombstones: Vec::new(),
            collections: Vec::new(),
            catalog_entries: Vec::new(),
            collection_entries: Vec::new(),
            collection_generators: Vec::new(),
            consumption_log: vec![PeerConsumptionLogRecord {
                id: log_id.clone(),
                entry_id: ep.id.clone(),
                ts: t_android,
                status: ConsumptionStatus::Consumed,
                notes: None,
                created_at: Some(t_android),
            }],
            comparisons: Vec::new(),
        })
        .await
        .unwrap();

    // Ep 1 MUST have exactly 1 consumption_log row (Android's `log_id`), NOT 2!
    let ep_logs: Vec<_> = resp.consumption_log.iter().filter(|l| l.entry_id == ep.id).collect();
    assert_eq!(ep_logs.len(), 1);
    assert_eq!(ep_logs[0].id, log_id);

    // Bulk-archived article without an explicit consumption_log row MUST NOT be auto-logged!
    assert!(!resp.consumption_log.iter().any(|l| l.entry_id == bulk_art.id));
}

#[tokio::test]
async fn test_peer_sync_initial_pagination_referenced_entries_slug_collision_and_tag_tie_stability()
{
    use crate::db::{CollectionMode, CollectionRole, ConsumptionStatus, EntryKind, NewItem};

    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/paged-feed.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Podcast, &feed_url, Some("Paged Podcast"), None).await.unwrap();
    assert_eq!(feed.feed_type, FeedType::Podcast);

    let mut created_ids = Vec::new();
    let base_ts =
        DateTime::parse_from_rfc3339("2026-07-01T12:00:00.000Z").unwrap().with_timezone(&Utc);
    for idx in 0..4 {
        let ep_url = Url::parse(&format!("https://example.com/ep-{idx}")).unwrap();
        let ep = db
            .items()
            .create(&NewItem {
                feed_id: Some(&feed.id),
                title: &format!("Episode {idx}"),
                url: &ep_url,
                author: Some("Host"),
                published_at: Some(base_ts),
                summary: Some("Episode summary"),
                content_type: ContentType::Audio,
            })
            .await
            .unwrap();
        let ep_updated = base_ts + chrono::Duration::seconds(idx * 10);
        sqlx::query!("UPDATE entries SET updated_at = ? WHERE id = ?", ep_updated as _, ep.id)
            .execute(db.pool())
            .await
            .unwrap();
        created_ids.push(ep.id);
    }

    // Log consumption on the LAST item (`created_ids[3]`, which will be on page 2 of initial sync!)
    // AND delete `created_ids[2]` after logging consumption so it's no longer in `items` at all!
    db.catalog()
        .log_consumption(&created_ids[3], ConsumptionStatus::Consumed, None, Some(base_ts))
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&created_ids[2], ConsumptionStatus::Consumed, None, Some(base_ts))
        .await
        .unwrap();
    assert!(db.items().delete(&created_ids[2]).await.unwrap());

    // Remaining inbox items are `[0, 1, 3]`. Initial sync (`updated_after: None`) with cap = 2:
    let (page1, _) = db
        .sync()
        .merge_peer_state_with_limit(
            &PeerSyncRequest {
                peer_id: "peer-init".to_owned(),
                device_name: Some("Tablet".to_owned()),
                network_tier: NetworkTier::Wifi,
                updated_after: None,
                ..PeerSyncRequest::default()
            },
            2,
        )
        .await
        .unwrap();
    assert!(page1.has_more, "Initial sync exceeding cap must set has_more = true");
    assert_eq!(page1.items.len(), 2);
    assert!(page1.next_after_item_id.is_some());
    assert!(
        page1.catalog_entries.iter().any(|e| e.id == created_ids[3]),
        "Page 1 catalog_entries must materialize entry referenced by consumption_log even when its inbox item is on page 2"
    );
    assert!(
        page1.catalog_entries.iter().any(|e| e.id == created_ids[2]),
        "Page 1 catalog_entries must materialize entry referenced by consumption_log even when deleted from Inbox"
    );

    // Continuation page (`after_item_id` set) delivers the remaining item while skipping duplicate
    // catalog/tombstone serialization and retaining feeds/tags.
    let (page2, _) = db
        .sync()
        .merge_peer_state_with_limit(
            &PeerSyncRequest {
                peer_id: "peer-init".to_owned(),
                device_name: Some("Tablet".to_owned()),
                network_tier: NetworkTier::Wifi,
                updated_after: Some(page1.sync_timestamp),
                after_item_id: page1.next_after_item_id.clone(),
                ..PeerSyncRequest::default()
            },
            2,
        )
        .await
        .unwrap();
    assert!(!page2.has_more);
    assert_eq!(page2.items.len(), 1);
    assert_eq!(page2.items[0].id, created_ids[3]);
    assert_eq!(page2.feeds.len(), 1);
    assert!(page2.collections.is_empty(), "Continuation page must not re-emit page-1 collections");
    assert!(page2.tombstones.is_empty(), "Continuation page must not re-emit page-1 tombstones");

    // Manual collection slug collision: Peer A and Peer B create `"weekend-movies"` offline with
    // distinct UUIDs; both collection_entries must merge into the surviving collection without UNIQUE errors!
    let movie_a = "11111111-aaaa-4444-8888-111111111111".to_owned();
    let movie_b = "22222222-bbbb-4444-8888-222222222222".to_owned();
    let col_a = "aaaaaaaa-0000-4000-8000-aaaaaaaaaaaa".to_owned();
    let col_b = "bbbbbbbb-0000-4000-8000-bbbbbbbbbbbb".to_owned();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            collections: vec![PeerCollectionSpecRecord {
                collection_id: col_a.clone(),
                title: "Weekend Movies".to_owned(),
                summary: None,
                slug: Some("weekend-movies".to_owned()),
                mode: CollectionMode::Manual,
                role: CollectionRole::List,
                rule_json: None,
                show_in_lists: true,
                color: None,
                created_at: Some(base_ts),
                updated_at: Some(base_ts),
            }],
            catalog_entries: vec![PeerCatalogEntryRecord {
                id: movie_a.clone(),
                kind: EntryKind::Movie,
                title: "Heat".to_owned(),
                creators: vec!["Michael Mann".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1995),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(base_ts),
                updated_at: Some(base_ts),
            }],
            collection_entries: vec![PeerCollectionEntryRecord {
                collection_id: col_a.clone(),
                entry_id: movie_a.clone(),
                position: Some(1.0),
                added_at: base_ts,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let (slug_merged_resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            collections: vec![PeerCollectionSpecRecord {
                collection_id: col_b,
                title: "Weekend Movies".to_owned(),
                summary: Some("From Peer B".to_owned()),
                slug: Some("  WEEKEND-MOVIES  ".to_owned()),
                mode: CollectionMode::Manual,
                role: CollectionRole::List,
                rule_json: None,
                show_in_lists: true,
                color: None,
                created_at: Some(base_ts),
                updated_at: Some(base_ts + chrono::Duration::seconds(5)),
            }],
            catalog_entries: vec![PeerCatalogEntryRecord {
                id: movie_b.clone(),
                kind: EntryKind::Movie,
                title: "Collateral".to_owned(),
                creators: vec!["Michael Mann".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(2004),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(base_ts),
                updated_at: Some(base_ts),
            }],
            collection_entries: vec![PeerCollectionEntryRecord {
                collection_id: "bbbbbbbb-0000-4000-8000-bbbbbbbbbbbb".to_owned(),
                entry_id: movie_b.clone(),
                position: Some(2.0),
                added_at: base_ts,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(
        slug_merged_resp
            .collection_entries
            .iter()
            .any(|ce| ce.collection_id == col_a && ce.entry_id == movie_b),
        "Colliding manual collection slug must remap collection_entries onto existing collection_id"
    );

    // Tag-tie stability: syncing an item with the same `interaction_updated_at` and same `tag_names`
    // must NOT bump `entries.updated_at` on tie!
    let tie_ts = base_ts + chrono::Duration::minutes(10);
    let ep0_url = Url::parse("https://example.com/ep-0").unwrap();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            items: vec![PeerItemRecord {
                id: created_ids[0].clone(),
                feed_id: Some(feed.id.clone()),
                feed_url: Some(feed_url.clone()),
                title: "Episode 0".to_owned(),
                url: ep0_url.clone(),
                author: Some("Host".to_owned()),
                published_at: Some(base_ts),
                created_at: Some(base_ts),
                summary: Some("Episode summary".to_owned()),
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Audio,
                status: ItemStatus::Unread,
                is_favorite: false,
                read_at: None,
                read_progress: 0.25,
                interaction_updated_at: Some(tie_ts),
                tag_names: vec!["queue".to_owned()],
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let ep0_id = &created_ids[0];
    let updated_before_tie: DateTime<Utc> = sqlx::query_scalar!(
        "SELECT updated_at AS \"updated_at: DateTime<Utc>\" FROM entries WHERE id = ?",
        ep0_id,
    )
    .fetch_one(db.pool())
    .await
    .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(15)).await;

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            items: vec![PeerItemRecord {
                id: created_ids[0].clone(),
                feed_id: Some(feed.id.clone()),
                feed_url: Some(feed_url),
                title: "Episode 0".to_owned(),
                url: ep0_url,
                author: Some("Host".to_owned()),
                published_at: Some(base_ts),
                created_at: Some(base_ts),
                summary: Some("Episode summary".to_owned()),
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Audio,
                status: ItemStatus::Unread,
                is_favorite: false,
                read_at: None,
                read_progress: 0.25,
                interaction_updated_at: Some(tie_ts),
                tag_names: vec!["QUEUE".to_owned()],
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let ep0_id = &created_ids[0];
    let updated_after_tie: DateTime<Utc> = sqlx::query_scalar!(
        "SELECT updated_at AS \"updated_at: DateTime<Utc>\" FROM entries WHERE id = ?",
        ep0_id,
    )
    .fetch_one(db.pool())
    .await
    .unwrap();
    assert_eq!(
        updated_after_tie, updated_before_tie,
        "Unchanged tag-tie sync must not bump entries.updated_at and cause delta ping-pong"
    );
}

#[tokio::test]
async fn test_deleted_feed_with_history_hides_from_lists_and_inbox_sync_materializes_catalog_only_entry()
 {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://podcast.example.com/feed.xml").unwrap();
    let feed = db
        .feeds()
        .create(FeedType::Podcast, &feed_url, None, Some("Deep Tech Podcast"))
        .await
        .unwrap();
    let ep_url = Url::parse("https://podcast.example.com/ep-1").unwrap();
    let ep = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Episode 1",
            url: &ep_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Audio,
        })
        .await
        .unwrap();
    sqlx::query!(
        "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id) VALUES (?, ?)",
        feed.id,
        ep.id,
    )
    .execute(db.pool())
    .await
    .unwrap();
    db.catalog()
        .log_consumption(&ep.id, ConsumptionStatus::Consumed, Some("Great episode"), None)
        .await
        .unwrap();

    // Deleting the feed while episode has consumption history preserves parent entry as `podcast`
    // WITHOUT leaking it into `list_collections(false, false)` or coercing kind to `collection` in peer sync!
    assert!(db.feeds().delete(&feed.id).await.unwrap());
    let visible_lists = db.catalog().list_collections(false, false).await.unwrap();
    assert!(
        visible_lists.iter().all(|c| c.id != feed.id),
        "Deleted feed with preserved history must not appear in user lists"
    );
    let (sync_resp, _) = db.sync().merge_peer_state(&PeerSyncRequest::default()).await.unwrap();
    assert!(sync_resp.collections.iter().all(|c| c.collection_id != feed.id));
    let synced_podcast =
        sync_resp.catalog_entries.iter().find(|e| e.id == feed.id).expect("podcast entry synced");
    assert_eq!(synced_podcast.kind, EntryKind::Podcast);

    // Pre-existing catalog-only article entry (no `entry_content` / `entry_state` row) must be
    // materialized into the Inbox when an incoming `PeerItemRecord` arrives via peer sync!
    let pre_url = Url::parse("https://blog.example.com/preexisting-essay").unwrap();
    let pre_entry = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Article),
            title: "Preexisting Essay".to_owned(),
            url: Some(pre_url.to_string()),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    assert!(db.items().get(&pre_entry.id).await.unwrap().is_none());

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-mobile".to_owned(),
            items: vec![PeerItemRecord {
                id: String::new(),
                feed_id: None,
                feed_url: None,
                title: "Preexisting Essay".to_owned(),
                url: pre_url,
                author: None,
                published_at: None,
                created_at: None,
                summary: None,
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
                tag_names: Vec::new(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let materialized = db
        .items()
        .get(&pre_entry.id)
        .await
        .unwrap()
        .expect("Catalog-only entry must be materialized as an Inbox item on peer sync");
    assert_eq!(materialized.status, ItemStatus::Unread);
}

#[tokio::test]
async fn test_unicode_tags_readd_tombstone_and_multipage_concurrent_catalog_sync() {
    let db = Db::connect("sqlite::memory:").await.unwrap();

    // 1. Create ASCII tag, rename to non-ASCII uppercase (`"Äpfel"`), then attach `"äpfel"` via sync:
    //    must reuse existing tag without 500 `RowNotFound`, converge idempotently without bumping
    //    `updated_at` on repeat sync, and delete cleanly on incoming tombstone!
    let tag = db.tags().create("apfel", None).await.unwrap();
    assert!(db.tags().update(&tag.id, Some("Äpfel"), None).await.unwrap());

    let item_url = Url::parse("https://example.com/unicode-tag").unwrap();
    let initial_tags = vec!["Äpfel".to_owned()];
    let (item, _) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &item_url,
            title: "Unicode Tag Test",
            summary: None,
            content_html: None,
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &initial_tags,
        })
        .await
        .unwrap();

    let inter_ts = Utc::now();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-u".to_owned(),
            items: vec![PeerItemRecord {
                id: item.id.clone(),
                feed_id: None,
                feed_url: None,
                title: "Unicode Tag Test".to_owned(),
                url: Url::parse("https://example.com/unicode-tag").unwrap(),
                author: None,
                published_at: None,
                created_at: None,
                summary: None,
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                status: ItemStatus::Unread,
                is_favorite: false,
                read_at: None,
                read_progress: 0.0,
                interaction_updated_at: Some(inter_ts),
                tag_names: vec!["äpfel".to_owned()],
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let updated_before: DateTime<Utc> = sqlx::query_scalar!(
        "SELECT updated_at AS \"updated_at: DateTime<Utc>\" FROM entries WHERE id = ?",
        item.id,
    )
    .fetch_one(db.pool())
    .await
    .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    // Second identical sync must be a complete no-op on `entries.updated_at`!
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-u".to_owned(),
            items: vec![PeerItemRecord {
                id: item.id.clone(),
                feed_id: None,
                feed_url: None,
                title: "Unicode Tag Test".to_owned(),
                url: Url::parse("https://example.com/unicode-tag").unwrap(),
                author: None,
                published_at: None,
                created_at: None,
                summary: None,
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                status: ItemStatus::Unread,
                is_favorite: false,
                read_at: None,
                read_progress: 0.0,
                interaction_updated_at: Some(inter_ts),
                tag_names: vec!["Äpfel".to_owned()],
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let updated_after: DateTime<Utc> = sqlx::query_scalar!(
        "SELECT updated_at AS \"updated_at: DateTime<Utc>\" FROM entries WHERE id = ?",
        item.id,
    )
    .fetch_one(db.pool())
    .await
    .unwrap();
    assert_eq!(updated_after, updated_before, "Non-ASCII tag sync must not write-amplify");

    // Incoming tombstone for `"ÄPFEL"` deletes `"Äpfel"`:
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-u".to_owned(),
            tombstones: vec![crate::db::sync::SyncTombstone {
                entity_type: ActivityEntityType::Tag,
                natural_key: "ÄPFEL".to_owned(),
                deleted_at: Utc::now() + chrono::Duration::seconds(5),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.tags().list().await.unwrap().len(), 0);

    // 2. Multi-page sync: catalog entry written between page 1 and page 2 is delivered on final page!
    for idx in 0..2 {
        let mp_url = Url::parse(&format!("https://example.com/mp-{idx}")).unwrap();
        let mp_title = format!("MP {idx}");
        db.items()
            .save_manual_input_with_tags(&ManualItemInput {
                url: &mp_url,
                title: &mp_title,
                summary: None,
                content_html: None,
                thumbnail_url: None,
                audio_url: None,
                duration: None,
                content_type: ContentType::Article,
                tags: &[],
            })
            .await
            .unwrap();
    }
    let (p1, _) = db
        .sync()
        .merge_peer_state_with_limit(
            &PeerSyncRequest {
                peer_id: "peer-mp".to_owned(),
                device_name: Some("Phone".to_owned()),
                network_tier: NetworkTier::Wifi,
                ..PeerSyncRequest::default()
            },
            2,
        )
        .await
        .unwrap();
    assert!(p1.has_more);
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let mid_entry = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Concurrent Mid-Pagination Book".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(10)).await;

    let (p2, _) = db
        .sync()
        .merge_peer_state_with_limit(
            &PeerSyncRequest {
                peer_id: "peer-mp".to_owned(),
                device_name: Some("Phone".to_owned()),
                network_tier: NetworkTier::Wifi,
                updated_after: Some(p1.sync_timestamp),
                after_item_id: p1.next_after_item_id.clone(),
                ..PeerSyncRequest::default()
            },
            5,
        )
        .await
        .unwrap();
    assert!(!p2.has_more);
    assert!(
        p2.catalog_entries.iter().any(|e| e.id == mid_entry.id),
        "Catalog mutation committed between page 1 and final page must be delivered on final page"
    );
}
