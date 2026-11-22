#![allow(clippy::unwrap_used, clippy::too_many_lines)]

use std::collections::HashSet;

use chrono::{DateTime, Utc};
use serde::Deserialize;
use url::Url;

use super::*;
use crate::db::{
    ActivityEntityType, CollectionMode, CollectionRole, ConsumptionStatus, ContentType, Db,
    EntryKind, Feed, FeedType, InboxState, ItemFilter, ItemStatus, ManualItemInput, NewItem,
    PollBatch, PollItemCandidate,
};

#[allow(clippy::too_many_arguments)]
fn make_feed_sync_entry(
    id: &str,
    url: &str,
    feed_type: FeedType,
    feed_title: Option<&str>,
    description: Option<&str>,
    is_active: bool,
    last_polled_at: Option<DateTime<Utc>>,
    last_polled_by: Option<&str>,
    hlc: &str,
) -> SyncEntryRecord {
    let entry_title = feed_title.unwrap_or(url);
    let kind = match feed_type {
        FeedType::Youtube => EntryKind::Channel,
        FeedType::Arxiv => EntryKind::Collection,
        FeedType::Podcast => EntryKind::Podcast,
        FeedType::Rss => EntryKind::Publication,
    };
    SyncEntryRecord {
        id: id.to_owned(),
        kind,
        title: entry_title.to_owned(),
        creators: Vec::new(),
        url: None,
        published_at: None,
        release_year: None,
        summary: description.map(str::to_owned),
        thumbnail_url: None,
        metadata: None,
        identifiers: Vec::new(),
        merged_into_id: None,
        created_at: None,
        updated_at: None,
        hlc: hlc.to_owned(),
        content: None,
        state: None,
        collection_spec: Some(SyncCollectionSpec {
            slug: None,
            mode: CollectionMode::Feed,
            role: CollectionRole::Feed,
            rule_json: None,
            show_in_lists: false,
            color: None,
        }),
        feed_source: Some(SyncFeedSource {
            feed_url: url.to_owned(),
            source_type: feed_type,
            feed_title: feed_title.map(str::to_owned),
            language: Some("en".to_owned()),
            poll_interval: 30,
            is_active,
            last_polled_at,
            last_polled_by: last_polled_by.map(str::to_owned),
            last_error: None,
            last_error_by: None,
            consecutive_failures: 0,
            hlc: hlc.to_owned(),
        }),
    }
}

fn make_tag_sync_entry(id: &str, name: &str, color: Option<&str>, hlc: &str) -> SyncEntryRecord {
    SyncEntryRecord {
        id: id.to_owned(),
        kind: EntryKind::Collection,
        title: name.to_owned(),
        creators: Vec::new(),
        url: None,
        published_at: None,
        release_year: None,
        summary: None,
        thumbnail_url: None,
        metadata: None,
        identifiers: Vec::new(),
        merged_into_id: None,
        created_at: None,
        updated_at: None,
        hlc: hlc.to_owned(),
        content: None,
        state: None,
        collection_spec: Some(SyncCollectionSpec {
            slug: Some(format!("tag:{}", name.trim().to_lowercase())),
            mode: CollectionMode::Manual,
            role: CollectionRole::Tag,
            rule_json: None,
            show_in_lists: false,
            color: color.map(str::to_owned),
        }),
        feed_source: None,
    }
}

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
            consecutive_failures: 0,
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
            consecutive_failures: 0,
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
        consecutive_failures: 1,
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
                        creators: Vec::new(),
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
                        creators: Vec::new(),
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

    let post2_id = deterministic_item_id(Some(feed_url.as_str()), item2_url.as_str());
    assert!(db.items().delete(&post2_id).await.unwrap());

    let now = SyncRepo::now_utc();
    let mut peer_clock = HlcClock::new("android-peer-1", None);
    let stale_hlc =
        HlcTimestamp::from_datetime(now - chrono::Duration::minutes(10), "android-peer-1").encode();
    let fresh_hlc =
        peer_clock.tick((now + chrono::Duration::seconds(5)).timestamp_millis()).encode();

    let item3_url = Url::parse("https://example.com/post-3").unwrap();
    let req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel 10 Pro".to_string()),
        network_tier: NetworkTier::Wifi,
        weight: Some(4.2),
        since_seq: 0,
        client_hlc: Some(fresh_hlc.clone()),
        entries: vec![
            make_feed_sync_entry(
                "android-local-feed-id",
                feed_url.as_str(),
                FeedType::Rss,
                Some("Example"),
                Some("Enriched description"),
                true,
                Some(now),
                Some("android-peer-1"),
                &fresh_hlc,
            ),
            make_tag_sync_entry("android-tag-dist", "distributed", None, &fresh_hlc),
            make_tag_sync_entry("android-tag-sys", "systems", None, &fresh_hlc),
            make_tag_sync_entry("android-tag-mob", "mobile", None, &fresh_hlc),
            SyncEntryRecord {
                id: "android-local-item-1".to_string(),
                kind: EntryKind::Article,
                title: "Post 1".to_string(),
                creators: vec!["Alice".to_string()],
                url: Some(item1_url.to_string()),
                published_at: Some(now),
                release_year: None,
                summary: Some("Much longer and richer summary from peer".to_string()),
                thumbnail_url: Some("https://example.com/hero.jpg".to_string()),
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: fresh_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some(
                        "<p>Much longer full article body from peer</p>".to_string(),
                    ),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::Dismissed,
                    read_at: Some(now),
                    progress: 0.9,
                    interaction_updated_at: Some(now),
                    hlc: fresh_hlc.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            },
            SyncEntryRecord {
                id: post2_id.clone(),
                kind: EntryKind::Article,
                title: "Post 2".to_string(),
                creators: Vec::new(),
                url: Some(item2_url.to_string()),
                published_at: None,
                release_year: None,
                summary: Some("Old".to_string()),
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now - chrono::Duration::minutes(10)),
                updated_at: Some(now - chrono::Duration::minutes(10)),
                hlc: stale_hlc.clone(),
                content: None,
                state: Some(SyncEntryState {
                    inbox_state: InboxState::Unread,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: None,
                    hlc: stale_hlc.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            },
            SyncEntryRecord {
                id: "android-local-item-3".to_string(),
                kind: EntryKind::Article,
                title: "Post 3 Fetched On Android".to_string(),
                creators: vec!["Bob".to_string()],
                url: Some(item3_url.to_string()),
                published_at: Some(now),
                release_year: None,
                summary: Some("Fetched by phone".to_string()),
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: fresh_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>Fetched by phone</p>".to_string()),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::Unread,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: None,
                    hlc: fresh_hlc.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            },
        ],
        collection_entries: vec![
            SyncCollectionEntryRecord {
                collection_id: "android-local-feed-id".to_string(),
                entry_id: "android-local-item-1".to_string(),
                position: None,
                added_at: now,
                hlc: fresh_hlc.clone(),
            },
            SyncCollectionEntryRecord {
                collection_id: STARRED_COLLECTION_ID.to_string(),
                entry_id: "android-local-item-1".to_string(),
                position: None,
                added_at: now,
                hlc: fresh_hlc.clone(),
            },
            SyncCollectionEntryRecord {
                collection_id: "android-tag-dist".to_string(),
                entry_id: "android-local-item-1".to_string(),
                position: None,
                added_at: now,
                hlc: fresh_hlc.clone(),
            },
            SyncCollectionEntryRecord {
                collection_id: "android-tag-sys".to_string(),
                entry_id: "android-local-item-1".to_string(),
                position: None,
                added_at: now,
                hlc: fresh_hlc.clone(),
            },
            SyncCollectionEntryRecord {
                collection_id: feed.id.clone(),
                entry_id: post2_id.clone(),
                position: None,
                added_at: now - chrono::Duration::minutes(10),
                hlc: stale_hlc,
            },
            SyncCollectionEntryRecord {
                collection_id: "android-local-feed-id".to_string(),
                entry_id: "android-local-item-3".to_string(),
                position: None,
                added_at: now,
                hlc: fresh_hlc.clone(),
            },
            SyncCollectionEntryRecord {
                collection_id: "android-tag-mob".to_string(),
                entry_id: "android-local-item-3".to_string(),
                position: None,
                added_at: now,
                hlc: fresh_hlc,
            },
        ],
        ..PeerSyncRequest::default()
    };

    let (resp, inserted) = db.sync().merge_peer_state(&req).await.unwrap();
    assert_eq!(inserted.len(), 1);
    assert!(!resp.has_more);
    assert!(resp.sync_seq > 0);
    assert_eq!(resp.active_peers.len(), 2);

    let inbox_count = resp
        .entries
        .iter()
        .filter(|e| {
            e.state.as_ref().is_some_and(|s| {
                matches!(s.inbox_state, InboxState::Unread | InboxState::Dismissed)
            })
        })
        .count();
    assert_eq!(inbox_count, 2, "Only Post 1 and Post 3 should exist in Inbox");

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

    // Idle delta sync at `resp.sync_seq` transfers zero rows while preserving measured peer weight
    let idle_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel 10 Pro".to_string()),
        network_tier: NetworkTier::Wifi,
        weight: None,
        since_seq: resp.sync_seq,
        ..PeerSyncRequest::default()
    };
    let (idle_resp, _) = db.sync().merge_peer_state(&idle_req).await.unwrap();
    assert!(idle_resp.entries.is_empty());
    assert!(idle_resp.collection_entries.is_empty());
    let android_peer =
        idle_resp.active_peers.iter().find(|p| p.peer_id == "android-peer-1").unwrap();
    assert!((android_peer.weight - 4.2).abs() < 1e-6);

    // Routine server poll error updates telemetry WITHOUT bumping feed config HLC, so peer archive wins
    db.feeds().mark_error(&feed.id, "transient error").await.unwrap();
    let archive_hlc =
        peer_clock.tick((now + chrono::Duration::seconds(10)).timestamp_millis()).encode();
    let archive_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel 10 Pro".to_string()),
        network_tier: NetworkTier::Wifi,
        since_seq: idle_resp.sync_seq,
        client_hlc: Some(archive_hlc.clone()),
        entries: vec![make_feed_sync_entry(
            &feed.id,
            feed_url.as_str(),
            FeedType::Rss,
            Some("Example Blog"),
            None,
            false,
            Some(now),
            Some("android-peer-1"),
            &archive_hlc,
        )],
        ..PeerSyncRequest::default()
    };
    let (archive_resp, _) = db.sync().merge_peer_state(&archive_req).await.unwrap();
    let synced_feed = archive_resp
        .entries
        .iter()
        .find(|e| e.id == feed.id)
        .unwrap()
        .feed_source
        .as_ref()
        .unwrap();
    assert!(!synced_feed.is_active);

    // Second peer sending an older HLC with `is_active: true` must NOT unarchive the feed
    let older_peer2_hlc =
        HlcTimestamp::from_datetime(now - chrono::Duration::minutes(1), "android-peer-2").encode();
    let peer2_req = PeerSyncRequest {
        peer_id: "android-peer-2".to_string(),
        device_name: Some("Tablet".to_string()),
        network_tier: NetworkTier::Wifi,
        since_seq: 0,
        entries: vec![make_feed_sync_entry(
            &feed.id,
            feed_url.as_str(),
            FeedType::Rss,
            Some("Example Blog"),
            None,
            true,
            None,
            None,
            &older_peer2_hlc,
        )],
        ..PeerSyncRequest::default()
    };
    let (peer2_resp, _) = db.sync().merge_peer_state(&peer2_req).await.unwrap();
    let peer2_feed =
        peer2_resp.entries.iter().find(|e| e.id == feed.id).unwrap().feed_source.as_ref().unwrap();
    assert!(!peer2_feed.is_active);
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
async fn test_sync_seq_delta_and_multipage_content_html() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/subsec-rss.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Subsec"), None).await.unwrap();

    let batch_items: Vec<PollItemCandidate> = (0..205)
        .map(|idx| PollItemCandidate {
            title: format!("Article {idx}"),
            url: Url::parse(&format!("https://example.com/article-{idx}")).unwrap(),
            creators: Vec::new(),
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

    let mut since_seq = 0;
    let mut synced_articles = Vec::new();
    loop {
        let (page, _) = db
            .sync()
            .merge_peer_state_with_limit(
                &PeerSyncRequest {
                    peer_id: "android-peer-1".to_string(),
                    device_name: Some("Pixel".to_string()),
                    network_tier: NetworkTier::Wifi,
                    since_seq,
                    ..PeerSyncRequest::default()
                },
                120,
            )
            .await
            .unwrap();
        for entry in page.entries {
            if entry.kind == EntryKind::Article {
                assert!(
                    entry.content.as_ref().and_then(|c| c.content_html.as_ref()).is_some(),
                    "Every synced article across all pages must retain inline content_html"
                );
                synced_articles.push(entry.id);
            }
        }
        since_seq = page.sync_seq;
        if !page.has_more {
            break;
        }
    }
    assert_eq!(synced_articles.len(), 205);

    // REST `ItemFilter.updated_after` subsecond check + monotonic `sync_seq` single-item delta check
    let id1 = deterministic_item_id(Some(feed_url.as_str()), "https://example.com/article-1");
    assert!(db.items().archive(&id1).await.unwrap());

    let (delta_resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "android-peer-1".to_string(),
            device_name: Some("Pixel".to_string()),
            network_tier: NetworkTier::Wifi,
            since_seq,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(delta_resp.entries.len(), 1);
    assert_eq!(delta_resp.entries[0].id, id1);
    assert_eq!(delta_resp.entries[0].state.as_ref().unwrap().inbox_state, InboxState::Dismissed);

    let since =
        DateTime::parse_from_rfc3339("2026-11-11T20:00:00.100Z").unwrap().with_timezone(&Utc);
    let after_500ms = since + chrono::Duration::milliseconds(500);
    let old_ts = since - chrono::Duration::seconds(30);
    sqlx::query("UPDATE entries SET updated_at = ?").bind(old_ts).execute(db.pool()).await.unwrap();
    sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
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
}

#[tokio::test]
async fn test_tombstone_resurrection_requires_newer_hlc_and_preserves_custom_feed_title() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/tombstone-feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Original"), None).await.unwrap();
    let tag = db.tags().create("ephemeral", None).await.unwrap();

    assert!(db.feeds().delete(&feed.id).await.unwrap());
    assert!(db.tags().delete(&tag.id).await.unwrap());
    let del_after = SyncRepo::now_utc();
    let stale_hlc =
        HlcTimestamp::from_datetime(del_after - chrono::Duration::minutes(5), "android-peer-1")
            .encode();

    // Stale offline poll (`last_polled_at > del_after`, but config `hlc < tomb.hlc`) must NOT resurrect
    let stale_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        entries: vec![
            make_feed_sync_entry(
                &feed.id,
                feed_url.as_str(),
                FeedType::Rss,
                Some("Stale Offline Copy"),
                None,
                true,
                Some(del_after + chrono::Duration::minutes(5)),
                Some("android-peer-1"),
                &stale_hlc,
            ),
            make_tag_sync_entry(&tag.id, "ephemeral", None, &stale_hlc),
        ],
        ..PeerSyncRequest::default()
    };
    let (stale_resp, _) = db.sync().merge_peer_state(&stale_req).await.unwrap();
    assert!(stale_resp.entries.is_empty(), "Stale HLC must not resurrect deleted feed or tag");

    // Explicit re-creation with newer HLC clears tombstones and recreates feed + tag
    let recreate_ts = del_after + chrono::Duration::seconds(10);
    let recreate_hlc = HlcTimestamp::from_datetime(recreate_ts, "android-peer-1").encode();
    let recreate_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        since_seq: stale_resp.sync_seq,
        entries: vec![
            make_feed_sync_entry(
                &feed.id,
                feed_url.as_str(),
                FeedType::Rss,
                Some("Recreated Feed"),
                None,
                true,
                Some(recreate_ts),
                Some("android-peer-1"),
                &recreate_hlc,
            ),
            make_tag_sync_entry(&tag.id, "ephemeral", None, &recreate_hlc),
        ],
        ..PeerSyncRequest::default()
    };
    let (recreate_resp, _) = db.sync().merge_peer_state(&recreate_req).await.unwrap();
    assert_eq!(recreate_resp.entries.len(), 2);

    // User renames feed on server; subsequent peer poll with older config HLC must NOT clobber custom title
    tokio::time::sleep(std::time::Duration::from_millis(2)).await;
    assert!(
        db.feeds().update(&feed.id, Some("My Custom Feed Name"), None, None, None).await.unwrap()
    );
    let poll_bump_req = PeerSyncRequest {
        peer_id: "android-peer-1".to_string(),
        device_name: Some("Pixel".to_string()),
        network_tier: NetworkTier::Wifi,
        entries: vec![make_feed_sync_entry(
            &feed.id,
            feed_url.as_str(),
            FeedType::Rss,
            Some("Raw Upstream RSS Title"),
            Some("Updated desc"),
            true,
            Some(recreate_ts + chrono::Duration::minutes(30)),
            Some("android-peer-1"),
            &recreate_hlc,
        )],
        ..PeerSyncRequest::default()
    };
    let _ = db.sync().merge_peer_state(&poll_bump_req).await.unwrap();
    let updated_feed = db.feeds().get(&feed.id).await.unwrap().unwrap();
    assert_eq!(updated_feed.title.as_deref(), Some("My Custom Feed Name"));
    assert_eq!(
        updated_feed.last_polled,
        Some(recreate_ts + chrono::Duration::minutes(30)),
        "Poll telemetry advances even when config HLC preserves custom feed title"
    );
}

#[tokio::test]
async fn test_sync_seq_pagination_and_pruned_cursor_reset() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/delta-cap-rss.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Delta Cap"), None).await.unwrap();

    let (baseline, _) = db.sync().merge_peer_state(&PeerSyncRequest::default()).await.unwrap();
    let start_seq = baseline.sync_seq;

    let batch_items: Vec<PollItemCandidate> = (0..5)
        .map(|idx| PollItemCandidate {
            title: format!("Cap Item {idx}"),
            url: Url::parse(&format!("https://example.com/cap-item-{idx}")).unwrap(),
            creators: Vec::new(),
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

    let mut expected_ids = HashSet::new();
    for idx in 0..5 {
        expected_ids.insert(deterministic_item_id(
            Some(feed_url.as_str()),
            &format!("https://example.com/cap-item-{idx}"),
        ));
    }

    let mut cursor = start_seq;
    let mut received_articles = HashSet::new();
    let mut pages = 0;
    loop {
        pages += 1;
        let (resp, _) = db
            .sync()
            .merge_peer_state_with_limit(
                &PeerSyncRequest {
                    peer_id: "android-peer-1".to_string(),
                    since_seq: cursor,
                    ..PeerSyncRequest::default()
                },
                3,
            )
            .await
            .unwrap();
        assert!(resp.sync_seq > cursor);
        for e in resp.entries {
            if e.kind == EntryKind::Article {
                assert!(
                    received_articles.insert(e.id),
                    "No duplicate article across sync_seq pages"
                );
            }
        }
        cursor = resp.sync_seq;
        if !resp.has_more {
            break;
        }
        assert!(pages < 10);
    }
    assert!(pages >= 3);
    assert_eq!(received_articles, expected_ids);

    // Tombstone pruning advances `min_retained_seq`; stale `since_seq < min_retained_seq` signals `reset_required`
    let del_id = expected_ids.iter().next().unwrap().clone();
    assert!(db.items().delete(&del_id).await.unwrap());
    let old_del = Utc::now() - chrono::Duration::days(40);
    sqlx::query("UPDATE sync_tombstones SET deleted_at = ?, recorded_at = ?")
        .bind(old_del)
        .bind(old_del)
        .execute(db.pool())
        .await
        .unwrap();
    db.sync().prune_activity_older_than_days(30).await.unwrap();

    let (reset_resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "stale-offline-peer".to_string(),
            since_seq: 1,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(
        reset_resp.reset_required,
        "Stale cursor below min_retained_seq must flag reset_required"
    );
    assert!(!reset_resp.entries.is_empty());
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
    struct HlcStepVector {
        node_id: String,
        initial_hlc: Option<String>,
        op: String,
        remote_hlc: Option<String>,
        now_ms: i64,
        expected: String,
    }

    #[derive(Deserialize)]
    struct HlcTotalOrderVector {
        unsorted: Vec<String>,
        expected_sorted: Vec<String>,
    }

    #[derive(Deserialize)]
    struct HlcVectors {
        sequence_steps: Vec<HlcStepVector>,
        total_order: HlcTotalOrderVector,
    }

    #[derive(Deserialize)]
    struct FeedPhaseOffsetVector {
        feed_url: String,
        poll_interval_mins: i64,
        expected_phase_secs: i64,
    }

    #[derive(Deserialize)]
    struct BackoffScheduleVector {
        poll_interval_mins: i64,
        consecutive_failures: i64,
        expected_target_delay_secs: i64,
    }

    #[derive(Deserialize)]
    struct StaggeredPollScheduleVector {
        feed_url: String,
        poll_interval_mins: i64,
        last_polled_epoch_secs: i64,
        consecutive_failures: i64,
        expected_next_due_epoch_secs: i64,
    }

    #[derive(Deserialize)]
    #[allow(clippy::struct_field_names)]
    struct ThroughputEwmaStepVector {
        current_weight: f64,
        sample_weight: f64,
        expected_weight: f64,
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
        hlc_vectors: HlcVectors,
        feed_phase_offsets: Vec<FeedPhaseOffsetVector>,
        backoff_schedules: Vec<BackoffScheduleVector>,
        staggered_poll_schedules: Vec<StaggeredPollScheduleVector>,
        throughput_ewma_steps: Vec<ThroughputEwmaStepVector>,
    }

    let vectors_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../testdata/cross_stack_vectors.json");
    let raw = std::fs::read_to_string(&vectors_path).unwrap();
    let vectors: GoldenVectors = serde_json::from_str(&raw).unwrap();

    assert!(vectors.canonical_urls.len() >= 6);
    for case in &vectors.canonical_urls {
        assert_eq!(canonicalize_sync_url(&case.input), case.expected);
    }

    assert!(vectors.entity_ids.len() >= 3);
    for case in &vectors.entity_ids {
        assert_eq!(deterministic_feed_id(&case.feed_url), case.expected_feed_id);
        assert_eq!(
            deterministic_item_id(Some(&case.feed_url), &case.item_url),
            case.expected_item_id
        );
        assert_eq!(deterministic_tag_id(&case.tag_name), case.expected_tag_id);
    }

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
        assert_eq!(winner, Some(case.expected_winner.as_str()));
    }

    assert_eq!(
        vectors.preferred_identifier_schemes,
        crate::db::PREFERRED_IDENTIFIER_SCHEMES.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>()
    );

    for case in &vectors.identifier_normalizations {
        assert_eq!(crate::db::normalize_identifier_scheme(&case.scheme), case.expected_scheme);
        assert_eq!(
            crate::db::normalize_identifier_value(case.kind, &case.scheme, &case.raw_value),
            case.expected_value
        );
    }

    for case in &vectors.album_edition_stripping {
        assert_eq!(crate::db::strip_album_edition_suffix(&case.input), case.expected);
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
            case.expected_id
        );
    }

    for case in &vectors.auto_consumption_log_ids {
        assert_eq!(deterministic_auto_consumption_log_id(&case.entry_id), case.expected_log_id);
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
            case.expected_match
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
        crate::ranking::sort_by_score_with_ties(
            &mut sorted,
            &scores,
            |entry| entry.id.as_str(),
            |a, b| {
                a.title.to_lowercase().cmp(&b.title.to_lowercase()).then_with(|| a.id.cmp(&b.id))
            },
        );
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

    for step in &vectors.hlc_vectors.sequence_steps {
        let mut clock = HlcClock::new(&step.node_id, step.initial_hlc.as_deref());
        let out = match step.op.as_str() {
            "tick" => clock.tick(step.now_ms).encode(),
            "witness" => {
                clock.witness_str(step.remote_hlc.as_deref().unwrap(), step.now_ms).encode()
            }
            other => panic!("unknown HLC op: {other}"),
        };
        assert_eq!(out, step.expected);
    }

    let mut raw_strings = vectors.hlc_vectors.total_order.unsorted.clone();
    raw_strings.sort();
    assert_eq!(raw_strings, vectors.hlc_vectors.total_order.expected_sorted);

    let mut parsed_hlcs: Vec<HlcTimestamp> = vectors
        .hlc_vectors
        .total_order
        .unsorted
        .iter()
        .map(|s| HlcTimestamp::parse(s).unwrap())
        .collect();
    parsed_hlcs.sort();
    let encoded_sorted: Vec<String> = parsed_hlcs.iter().map(HlcTimestamp::encode).collect();
    assert_eq!(encoded_sorted, vectors.hlc_vectors.total_order.expected_sorted);

    assert!(vectors.feed_phase_offsets.len() >= 5);
    for case in &vectors.feed_phase_offsets {
        assert_eq!(
            feed_phase_offset_secs(&case.feed_url, case.poll_interval_mins),
            case.expected_phase_secs,
            "feed_phase_offset_secs mismatch for {} ({}m)",
            case.feed_url,
            case.poll_interval_mins
        );
    }

    assert!(vectors.backoff_schedules.len() >= 10);
    for case in &vectors.backoff_schedules {
        assert_eq!(
            target_poll_interval_secs(case.poll_interval_mins, case.consecutive_failures),
            case.expected_target_delay_secs,
            "target_poll_interval_secs mismatch for {}m, k={}",
            case.poll_interval_mins,
            case.consecutive_failures
        );
    }

    assert!(vectors.staggered_poll_schedules.len() >= 7);
    for case in &vectors.staggered_poll_schedules {
        assert_eq!(
            next_scheduled_poll_epoch_secs(
                &case.feed_url,
                case.poll_interval_mins,
                case.last_polled_epoch_secs,
                case.consecutive_failures,
            ),
            case.expected_next_due_epoch_secs,
            "next_scheduled_poll_epoch_secs mismatch for {} ({}m, lp={}, k={})",
            case.feed_url,
            case.poll_interval_mins,
            case.last_polled_epoch_secs,
            case.consecutive_failures
        );
    }

    assert!(vectors.throughput_ewma_steps.len() >= 5);
    for case in &vectors.throughput_ewma_steps {
        let actual = update_throughput_ewma(case.current_weight, case.sample_weight);
        assert!(
            (actual - case.expected_weight).abs() < 1e-9,
            "update_throughput_ewma({}, {}): got {}, expected {}",
            case.current_weight,
            case.sample_weight,
            actual,
            case.expected_weight
        );
    }
}

#[tokio::test]
async fn test_offline_peer_write_propagates_across_sync_seq_regardless_of_wall_clock() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let sync = db.sync();

    // Peer A wrote offline 2 years ago on a badly skewed wall clock (`t1`)
    let t1 = DateTime::parse_from_rfc3339("2024-01-01T10:00:00Z").unwrap().with_timezone(&Utc);
    let hlc_a = HlcTimestamp::from_datetime(t1, "peer-a").encode();

    // Peer B performs an initial sync FIRST, capturing monotonic `seq_b`
    let (init_b, _) = sync
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            device_name: Some("Phone B".to_owned()),
            network_tier: NetworkTier::Wifi,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let seq_b = init_b.sync_seq;

    let col_id = "11111111-2222-3333-4444-555555555555".to_owned();
    let movie_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee".to_owned();
    sync.merge_peer_state(&PeerSyncRequest {
        peer_id: "peer-a".to_owned(),
        device_name: Some("Tablet A".to_owned()),
        network_tier: NetworkTier::Wifi,
        entries: vec![
            SyncEntryRecord {
                id: col_id.clone(),
                kind: EntryKind::Collection,
                title: "Sci-Fi Classics".to_owned(),
                creators: Vec::new(),
                url: None,
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(t1),
                updated_at: Some(t1),
                hlc: hlc_a.clone(),
                content: None,
                state: None,
                collection_spec: Some(SyncCollectionSpec {
                    slug: Some("sci-fi-classics".to_owned()),
                    mode: CollectionMode::Manual,
                    role: CollectionRole::List,
                    rule_json: None,
                    show_in_lists: true,
                    color: None,
                }),
                feed_source: None,
            },
            SyncEntryRecord {
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
                hlc: hlc_a.clone(),
                content: None,
                state: None,
                collection_spec: None,
                feed_source: None,
            },
        ],
        collection_entries: vec![SyncCollectionEntryRecord {
            collection_id: col_id.clone(),
            entry_id: movie_id.clone(),
            position: Some(1.0),
            added_at: t1,
            hlc: hlc_a,
        }],
        ..PeerSyncRequest::default()
    })
    .await
    .unwrap();

    // Peer B delta-syncs using `since_seq = seq_b` and receives Peer A's writes despite 2-year wall-clock skew!
    let (delta_b, _) = sync
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            device_name: Some("Phone B".to_owned()),
            network_tier: NetworkTier::Wifi,
            since_seq: seq_b,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert!(delta_b.entries.iter().any(|e| e.id == col_id && e.collection_spec.is_some()));
    assert!(delta_b.entries.iter().any(|e| e.id == movie_id && e.kind == EntryKind::Movie));
    assert!(
        delta_b
            .collection_entries
            .iter()
            .any(|ce| ce.collection_id == col_id && ce.entry_id == movie_id)
    );
}

#[tokio::test]
async fn test_deleting_inbox_item_or_feed_preserves_diary_and_rankings() {
    use crate::db::{ReadAtPatch, UpsertEntryInput};

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

    let other_ep = db
        .catalog()
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::PodcastEpisode),
            title: "Other Episode".to_owned(),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog()
        .append_comparison(None, &[vec![ep_consumed.id.clone()], vec![other_ep.id.clone()]], None)
        .await
        .unwrap();

    assert!(db.items().delete(&ep_consumed.id).await.unwrap());
    assert!(db.items().get(&ep_consumed.id).await.unwrap().is_none());
    let detail_after_item_del = db.catalog().get_entry_detail(&ep_consumed.id).await.unwrap();
    assert!(
        detail_after_item_del
            .unwrap()
            .log_history
            .iter()
            .any(|l| l.status == ConsumptionStatus::Consumed)
    );

    assert!(db.feeds().delete(&feed.id).await.unwrap());
    assert!(db.feeds().get(&feed.id).await.unwrap().is_none());
    assert!(db.items().get(&ep_unread.id).await.unwrap().is_none());
    assert!(db.catalog().get_entry_detail(&ep_unread.id).await.unwrap().is_none());

    let ep_detail = db.catalog().get_entry_detail(&ep_consumed.id).await.unwrap().unwrap();
    assert!(ep_detail.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed));
    assert!(db.catalog().get_entry_detail(&feed.id).await.unwrap().is_some());

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
        DateTime::parse_from_rfc3339("2026-11-20T09:12:34.567Z").unwrap().with_timezone(&Utc);
    let hlc_android = HlcTimestamp::from_datetime(t_android, "phone-1").encode();
    let log_id = deterministic_consumption_log_id(&ep.id, t_android.timestamp_millis(), "consumed");

    let (resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            device_name: Some("Android".to_owned()),
            network_tier: NetworkTier::Wifi,
            entries: vec![
                SyncEntryRecord {
                    id: ep.id.clone(),
                    kind: EntryKind::PodcastEpisode,
                    title: ep.title.clone(),
                    creators: Vec::new(),
                    url: Some(ep_url.to_string()),
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(t_android),
                    updated_at: Some(t_android),
                    hlc: hlc_android.clone(),
                    content: Some(SyncEntryContent {
                        content_type: ContentType::Audio,
                        content_html: None,
                        audio_url: Some("https://example.com/ep1.mp3".to_owned()),
                        duration: Some(1800),
                    }),
                    state: Some(SyncEntryState {
                        inbox_state: InboxState::Unread,
                        read_at: None,
                        progress: 1.0,
                        interaction_updated_at: Some(t_android),
                        hlc: hlc_android.clone(),
                    }),
                    collection_spec: None,
                    feed_source: None,
                },
                SyncEntryRecord {
                    id: bulk_art.id.clone(),
                    kind: EntryKind::Article,
                    title: bulk_art.title.clone(),
                    creators: Vec::new(),
                    url: Some(bulk_url.to_string()),
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(t_android),
                    updated_at: Some(t_android),
                    hlc: hlc_android.clone(),
                    content: None,
                    state: Some(SyncEntryState {
                        inbox_state: InboxState::Dismissed,
                        read_at: Some(t_android),
                        progress: 0.95,
                        interaction_updated_at: Some(t_android),
                        hlc: hlc_android.clone(),
                    }),
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            consumption_log: vec![SyncConsumptionLogRecord {
                id: log_id.clone(),
                entry_id: ep.id.clone(),
                ts: t_android,
                status: ConsumptionStatus::Consumed,
                notes: None,
                created_at: Some(t_android),
                hlc: hlc_android,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let ep_logs: Vec<_> = resp.consumption_log.iter().filter(|l| l.entry_id == ep.id).collect();
    assert_eq!(ep_logs.len(), 1);
    assert_eq!(ep_logs[0].id, log_id);
    assert!(!resp.consumption_log.iter().any(|l| l.entry_id == bulk_art.id));
}

#[tokio::test]
async fn test_peer_sync_referenced_entries_slug_collision_and_idempotent_stability() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://example.com/paged-feed.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Podcast, &feed_url, Some("Paged Podcast"), None).await.unwrap();

    let base_ts =
        DateTime::parse_from_rfc3339("2026-07-01T12:00:00.000Z").unwrap().with_timezone(&Utc);
    let mut created_ids = Vec::new();
    for idx in 0..3 {
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
        created_ids.push(ep.id);
    }

    // Log consumption on `created_ids[2]`, then update `created_ids[2]` AFTER the consumption_log row
    // so `entries.sync_seq` for `created_ids[2]` moves ahead of `consumption_log.sync_seq`!
    db.catalog()
        .log_consumption(&created_ids[2], ConsumptionStatus::Consumed, None, Some(base_ts))
        .await
        .unwrap();
    for idx in 0..4 {
        let _ = db
            .catalog()
            .upsert_entry(&crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: format!("Spacer Book {idx}"),
                ..crate::db::UpsertEntryInput::default()
            })
            .await
            .unwrap();
    }
    assert!(db.items().archive(&created_ids[2]).await.unwrap());

    // Paginate from `since_seq = 0` in small pages; every page containing `consumption_log` for
    // `created_ids[2]` must already have `created_ids[2]` in `entries` (FK self-contained)!
    let mut seen_entries = HashSet::new();
    let mut cursor = 0;
    loop {
        let (page, _) = db
            .sync()
            .merge_peer_state_with_limit(
                &PeerSyncRequest {
                    peer_id: "peer-init".to_owned(),
                    since_seq: cursor,
                    ..PeerSyncRequest::default()
                },
                4,
            )
            .await
            .unwrap();
        for e in &page.entries {
            seen_entries.insert(e.id.clone());
        }
        for log in &page.consumption_log {
            assert!(
                seen_entries.contains(&log.entry_id),
                "Every sync page must materialize referenced entries before or alongside child consumption_log rows"
            );
        }
        cursor = page.sync_seq;
        if !page.has_more {
            break;
        }
    }

    // Manual collection slug collision: Peer A and Peer B create `"weekend-movies"` offline with distinct UUIDs
    let movie_a = "11111111-aaaa-4444-8888-111111111111".to_owned();
    let movie_b = "22222222-bbbb-4444-8888-222222222222".to_owned();
    let col_a = "aaaaaaaa-0000-4000-8000-aaaaaaaaaaaa".to_owned();
    let col_b = "bbbbbbbb-0000-4000-8000-bbbbbbbbbbbb".to_owned();
    let hlc_a = HlcTimestamp::from_datetime(base_ts, "peer-a").encode();
    let hlc_b =
        HlcTimestamp::from_datetime(base_ts + chrono::Duration::seconds(5), "peer-b").encode();

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            entries: vec![
                SyncEntryRecord {
                    id: col_a.clone(),
                    kind: EntryKind::Collection,
                    title: "Weekend Movies".to_owned(),
                    creators: Vec::new(),
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(base_ts),
                    updated_at: Some(base_ts),
                    hlc: hlc_a.clone(),
                    content: None,
                    state: None,
                    collection_spec: Some(SyncCollectionSpec {
                        slug: Some("weekend-movies".to_owned()),
                        mode: CollectionMode::Manual,
                        role: CollectionRole::List,
                        rule_json: None,
                        show_in_lists: true,
                        color: None,
                    }),
                    feed_source: None,
                },
                SyncEntryRecord {
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
                    hlc: hlc_a.clone(),
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: col_a.clone(),
                entry_id: movie_a.clone(),
                position: Some(1.0),
                added_at: base_ts,
                hlc: hlc_a,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let (slug_merged_resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            entries: vec![
                SyncEntryRecord {
                    id: col_b.clone(),
                    kind: EntryKind::Collection,
                    title: "Weekend Movies".to_owned(),
                    creators: Vec::new(),
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: Some("From Peer B".to_owned()),
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(base_ts),
                    updated_at: Some(base_ts + chrono::Duration::seconds(5)),
                    hlc: hlc_b.clone(),
                    content: None,
                    state: None,
                    collection_spec: Some(SyncCollectionSpec {
                        slug: Some("  WEEKEND-MOVIES  ".to_owned()),
                        mode: CollectionMode::Manual,
                        role: CollectionRole::List,
                        rule_json: None,
                        show_in_lists: true,
                        color: None,
                    }),
                    feed_source: None,
                },
                SyncEntryRecord {
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
                    hlc: hlc_b.clone(),
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: col_b,
                entry_id: movie_b.clone(),
                position: Some(2.0),
                added_at: base_ts,
                hlc: hlc_b,
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
    db.catalog()
        .log_consumption(&ep.id, ConsumptionStatus::Consumed, Some("Great episode"), None)
        .await
        .unwrap();

    assert!(db.feeds().delete(&feed.id).await.unwrap());
    let visible_lists = db.catalog().list_collections(false, false).await.unwrap();
    assert!(visible_lists.iter().all(|c| c.id != feed.id));

    let (sync_resp, _) = db.sync().merge_peer_state(&PeerSyncRequest::default()).await.unwrap();
    let synced_podcast =
        sync_resp.entries.iter().find(|e| e.id == feed.id).expect("podcast entry synced");
    assert_eq!(synced_podcast.kind, EntryKind::Podcast);
    assert!(synced_podcast.collection_spec.is_none());
    assert!(synced_podcast.feed_source.is_none());

    // Pre-existing catalog-only article entry materialized into Inbox on incoming `SyncEntryState`
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

    let mobile_hlc = HlcTimestamp::from_datetime(Utc::now(), "peer-mobile").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-mobile".to_owned(),
            entries: vec![SyncEntryRecord {
                id: String::new(),
                kind: EntryKind::Article,
                title: "Preexisting Essay".to_owned(),
                creators: Vec::new(),
                url: Some(pre_url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: None,
                updated_at: None,
                hlc: mobile_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>Essay body</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::Unread,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: None,
                    hlc: mobile_hlc,
                }),
                collection_spec: None,
                feed_source: None,
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
async fn test_unicode_tags_membership_tombstones_and_mid_pagination_writes() {
    let db = Db::connect("sqlite::memory:").await.unwrap();

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

    // Syncing `"äpfel"` from a peer maps to existing `"Äpfel"` tag without duplicating
    let mut peer_clock = HlcClock::new("peer-u", None);
    let t1_hlc =
        peer_clock.tick((Utc::now() + chrono::Duration::seconds(2)).timestamp_millis()).encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-u".to_owned(),
            entries: vec![make_tag_sync_entry("peer-local-apfel", "äpfel", None, &t1_hlc)],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: "peer-local-apfel".to_owned(),
                entry_id: item.id.clone(),
                position: None,
                added_at: Utc::now(),
                hlc: t1_hlc.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.tags().list().await.unwrap().len(), 1);

    // Repeat identical sync must NOT advance `sync_seq` on the server!
    let (before_repeat, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let seq_before = before_repeat.sync_seq;

    let (repeat_resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-u".to_owned(),
            since_seq: seq_before,
            entries: vec![make_tag_sync_entry("peer-local-apfel", "äpfel", None, &t1_hlc)],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: "peer-local-apfel".to_owned(),
                entry_id: item.id.clone(),
                position: None,
                added_at: Utc::now(),
                hlc: t1_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(repeat_resp.sync_seq, seq_before, "No-op repeat sync must not bump sync_seq");
    assert!(repeat_resp.entries.is_empty());

    // Unstarring and untagging on server emit `collection_entry` tombstones via delta sync!
    assert!(
        db.items()
            .update(&item.id, None, Some(true), None, crate::db::ReadAtPatch::Unchanged)
            .await
            .unwrap()
    );
    let (after_star, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            since_seq: seq_before,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(
        after_star
            .collection_entries
            .iter()
            .any(|ce| { ce.collection_id == STARRED_COLLECTION_ID && ce.entry_id == item.id })
    );

    assert!(
        db.items()
            .update(&item.id, None, Some(false), None, crate::db::ReadAtPatch::Unchanged)
            .await
            .unwrap()
    );
    assert!(db.tags().remove_from_item(&item.id, &tag.id).await.unwrap());
    let (after_unstar_untag, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            since_seq: after_star.sync_seq,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(after_unstar_untag.tombstones.len(), 2);

    // Incoming tombstone for `"ÄPFEL"` with newer HLC deletes `"Äpfel"` tag
    let del_hlc =
        peer_clock.tick((Utc::now() + chrono::Duration::seconds(10)).timestamp_millis()).encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-u".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::Tag,
                natural_key: "ÄPFEL".to_owned(),
                deleted_at: Utc::now() + chrono::Duration::seconds(10),
                hlc: del_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.tags().list().await.unwrap().len(), 0);

    // Mid-pagination write: catalog entry committed between page 1 and page 2 is delivered cleanly via `sync_seq`
    for idx in 0..3 {
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
                since_seq: 0,
                ..PeerSyncRequest::default()
            },
            2,
        )
        .await
        .unwrap();
    assert!(p1.has_more);

    let mid_entry = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Concurrent Mid-Pagination Book".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();

    let (p2, _) = db
        .sync()
        .merge_peer_state_with_limit(
            &PeerSyncRequest {
                peer_id: "peer-mp".to_owned(),
                since_seq: p1.sync_seq,
                ..PeerSyncRequest::default()
            },
            50,
        )
        .await
        .unwrap();
    assert!(!p2.has_more);
    assert!(p2.entries.iter().any(|e| e.id == mid_entry.id));
}

#[test]
fn test_sync_entry_state_defaults_missing_inbox_state_to_none() {
    let parsed: SyncEntryState =
        serde_json::from_str(r#"{"hlc":"001780000001000-0000-peer-android"}"#).unwrap();
    assert_eq!(parsed.inbox_state, InboxState::None);
    assert!((parsed.progress - 0.0).abs() < f64::EPSILON);
    assert_eq!(parsed.hlc, "001780000001000-0000-peer-android");
}

#[tokio::test]
async fn test_manual_item_tombstone_deletes_want_to_read_item() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let url = Url::parse("https://example.com/saved-article").unwrap();
    let (saved, _) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &url,
            title: "Saved WTR Article",
            summary: None,
            content_html: Some("<p>body</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    assert_eq!(saved.inbox_state, InboxState::None);
    assert!(db.items().get(&saved.id).await.unwrap().is_some());

    let future_ts = Utc::now() + chrono::Duration::seconds(30);
    let tomb_hlc = HlcTimestamp::from_datetime(future_ts, "peer-android").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-android".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::Item,
                natural_key: item_natural_key(None, url.as_str()),
                deleted_at: future_ts,
                hlc: tomb_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert!(db.items().get(&saved.id).await.unwrap().is_none());
}

#[test]
fn test_hlc_drift_cap_and_saturation_without_wrap() {
    use super::hlc::{MAX_CLOCK_DRIFT_MS, MAX_HLC_WALL_MS, resolve_or_synthesize_hlc};

    let now_ms: i64 = 1_794_920_000_000;
    let far_future = HlcTimestamp::new(now_ms + 365 * 86_400_000, 42, "skewed-peer");
    let mut clock = HlcClock::new("server", None);
    let witnessed = clock.witness(&far_future, now_ms);
    assert!(witnessed.wall_ms <= now_ms + MAX_CLOCK_DRIFT_MS);

    let now_dt = DateTime::from_timestamp_millis(now_ms).unwrap();
    let resolved = resolve_or_synthesize_hlc(&far_future.encode(), None, now_dt, "skewed-peer");
    let parsed = HlcTimestamp::parse(&resolved).unwrap();
    assert_eq!(parsed.wall_ms, now_ms + MAX_CLOCK_DRIFT_MS);
    assert_eq!(parsed.counter, 0);

    // Saturation at MAX_HLC_WALL_MS with counter == u16::MAX must not wrap to 0
    let max_ts = HlcTimestamp::new(MAX_HLC_WALL_MS, u16::MAX, "server");
    let mut sat_clock = HlcClock::new("server", Some(&max_ts.encode()));
    let next = sat_clock.tick(MAX_HLC_WALL_MS);
    assert_eq!(next.wall_ms, MAX_HLC_WALL_MS);
    assert_eq!(next.counter, u16::MAX);

    let remote_max = HlcTimestamp::new(MAX_HLC_WALL_MS, u16::MAX, "peer-a");
    let next_w = sat_clock.witness(&remote_max, MAX_HLC_WALL_MS);
    assert_eq!(next_w.wall_ms, MAX_HLC_WALL_MS);
    assert_eq!(next_w.counter, u16::MAX);
}

#[tokio::test]
async fn test_collection_generator_tombstone_prevents_resurrection_and_syncs_deletion() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let col = db
        .catalog()
        .create_manual_collection(
            "Weekend Picks",
            Some("weekend-picks"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();

    db.catalog().set_collection_generator_enabled(&col.id, true).await.unwrap();
    assert_eq!(db.catalog().list_collection_generators(false).await.unwrap().len(), 1);

    let (before_del, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            since_seq: 0,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert!(db.catalog().remove_collection_generator(&col.id).await.unwrap());
    assert_eq!(db.catalog().list_collection_generators(false).await.unwrap().len(), 0);

    let (after_del, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            since_seq: before_del.sync_seq,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(after_del.tombstones.iter().any(|t| {
        t.entity_type == ActivityEntityType::CollectionGenerator && t.natural_key == col.id
    }));

    // Stale incoming generator with older HLC must NOT resurrect deleted generator
    let stale_ts = Utc::now() - chrono::Duration::minutes(5);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "stale-peer").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "stale-peer".to_owned(),
            collection_generators: vec![crate::db::SyncCollectionGeneratorRecord {
                collection_id: col.id.clone(),
                enabled: true,
                current_entry_id: None,
                drawn_at: None,
                updated_at: stale_ts,
                hlc: stale_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.catalog().list_collection_generators(false).await.unwrap().len(), 0);

    // Newer incoming generator clears tombstone and restores generator
    let newer_ts = Utc::now() + chrono::Duration::seconds(30);
    let newer_hlc = HlcTimestamp::from_datetime(newer_ts, "peer-a").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            collection_generators: vec![crate::db::SyncCollectionGeneratorRecord {
                collection_id: col.id.clone(),
                enabled: true,
                current_entry_id: None,
                drawn_at: None,
                updated_at: newer_ts,
                hlc: newer_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.catalog().list_collection_generators(false).await.unwrap().len(), 1);

    // Incoming CollectionGenerator tombstone with even newer HLC deletes local generator
    let del_ts = Utc::now() + chrono::Duration::seconds(60);
    let del_hlc = HlcTimestamp::from_datetime(del_ts, "peer-b").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::CollectionGenerator,
                natural_key: col.id.clone(),
                deleted_at: del_ts,
                hlc: del_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.catalog().list_collection_generators(false).await.unwrap().len(), 0);
}

#[tokio::test]
async fn test_tag_unread_count_unified_and_entry_merge_bumps_log_sync_seq() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let url = Url::parse("https://example.com/tagged-wtr").unwrap();
    let (item, tags) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &url,
            title: "Tagged WTR",
            summary: None,
            content_html: Some("<p>hi</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &["essays".to_owned()],
        })
        .await
        .unwrap();
    assert_eq!(tags.len(), 1);
    let tag_id = &tags[0].id;
    assert_eq!(tags[0].unread_count, 1);
    assert_eq!(db.tags().get(tag_id).await.unwrap().unwrap().unread_count, 1);
    assert_eq!(db.tags().list().await.unwrap()[0].unread_count, 1);
    assert_eq!(db.tags().list_for_item(&item.id).await.unwrap()[0].unread_count, 1);

    // Entry merge must bump consumption_log.sync_seq via consumption_log_sync_seq_au
    let canon = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Canonical Film".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let dup = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Duplicate Film".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let log = db
        .catalog()
        .log_consumption(&dup.id, ConsumptionStatus::Consumed, Some("Great"), None)
        .await
        .unwrap();

    let (before_merge, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            since_seq: 0,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    db.catalog().merge_entries(&dup.id, &canon.id).await.unwrap();

    let (after_merge, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "probe".to_owned(),
            since_seq: before_merge.sync_seq,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let expected_entry_id = canon.id.as_str();
    assert!(
        after_merge
            .consumption_log
            .iter()
            .any(|l| l.id == log.id && l.entry_id == expected_entry_id),
        "re-pointed consumption_log row must appear in incremental sync after entry merge"
    );
}

#[tokio::test]
async fn test_starred_batch_generator_rejected_and_collection_tombstone_enforced() {
    let db = Db::connect("sqlite::memory:").await.unwrap();

    // #10: Reject attaching a generator to Starred in apply_batch
    let err = db
        .catalog()
        .apply_batch(&crate::db::CatalogBatchInput {
            generators: vec![crate::db::BatchGeneratorInput {
                collection_ref: "starred".to_owned(),
                enabled: Some(true),
                current_entry_ref: None,
                drawn_at: None,
            }],
            ..crate::db::CatalogBatchInput::default()
        })
        .await
        .unwrap_err();
    assert!(matches!(err, crate::db::CatalogError::InvalidInput(_)));

    // #11: Entry tombstone blocks stale manual collection resurrection
    let tomb_id = deterministic_system_collection_id("custom:deleted-list");
    let tomb_ts = Utc::now() + chrono::Duration::seconds(60);
    let tomb_hlc = HlcTimestamp::from_datetime(tomb_ts, "peer-a").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::Entry,
                natural_key: tomb_id.clone(),
                deleted_at: tomb_ts,
                hlc: tomb_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let stale_ts = Utc::now() - chrono::Duration::minutes(5);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "peer-b").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            entries: vec![crate::db::SyncEntryRecord {
                id: tomb_id.clone(),
                kind: EntryKind::Collection,
                title: "Deleted List".to_owned(),
                creators: vec![],
                url: None,
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                merged_into_id: None,
                identifiers: vec![],
                content: None,
                state: None,
                collection_spec: Some(crate::db::SyncCollectionSpec {
                    slug: Some("deleted-list".to_owned()),
                    mode: CollectionMode::Manual,
                    role: CollectionRole::List,
                    rule_json: None,
                    show_in_lists: true,
                    color: None,
                }),
                feed_source: None,
                created_at: Some(stale_ts),
                updated_at: Some(stale_ts),
                hlc: stale_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(db.catalog().get_collection_summary(&tomb_id).await.is_err());

    // #5: ItemRepo::delete_returning_outcome preserves readable content when in a manual list
    let list_col = db
        .catalog()
        .create_manual_collection(
            "Reading List",
            Some("reading-list"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let url = Url::parse("https://example.com/list-preserved").unwrap();
    let (saved, _) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &url,
            title: "Preserved In List",
            summary: None,
            content_html: Some("<p>keep me</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    db.catalog().add_collection_entry(&list_col.id, &saved.id, None).await.unwrap();
    let outcome = db.items().delete_returning_outcome(&saved.id).await.unwrap();
    assert!(outcome.deleted);
    assert!(!outcome.readable_content_removed);

    // #12: count_rankable_members matches actual member count
    assert_eq!(db.catalog().count_rankable_members(&list_col.id, false).await.unwrap(), 1);
}

#[tokio::test]
async fn test_peer_finish_draw_preserves_peer_next_draw_and_returns_conflict_winner() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let col = db
        .catalog()
        .create_manual_collection(
            "1001 Albums",
            Some("1001-albums"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();

    let mut album_ids = Vec::new();
    for title in ["Album A", "Album B", "Album C", "Album D"] {
        let entry = db
            .catalog()
            .upsert_entry(&crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Album),
                title: title.to_owned(),
                creators: vec!["Artist".to_owned()],
                ..crate::db::UpsertEntryInput::default()
            })
            .await
            .unwrap();
        db.catalog().add_collection_entry(&col.id, &entry.id, None).await.unwrap();
        album_ids.push(entry.id);
    }

    // Initialize generator explicitly on Album A with an older timestamp so the peer's offline
    // finish (also earlier than server `now`) is strictly newer than the initial draw.
    let initial_ts = Utc::now() - chrono::Duration::minutes(10);
    db.catalog()
        .update_collection_generator(&col.id, Some(true), Some(&album_ids[0]), Some(initial_ts))
        .await
        .unwrap();
    let initial_hlc = HlcTimestamp::from_datetime(initial_ts, "server").encode();
    sqlx::query("UPDATE collection_generators SET updated_at = ?, hlc = ? WHERE collection_id = ?")
        .bind(initial_ts)
        .bind(&initial_hlc)
        .bind(&col.id)
        .execute(db.pool())
        .await
        .unwrap();

    // Peer finished Album A 2 minutes ago (earlier than server `now`!) and drew Album B.
    let peer_finish_ts = Utc::now() - chrono::Duration::minutes(2);
    let peer_log_hlc = HlcTimestamp::from_datetime(peer_finish_ts, "android").encode();
    let peer_gen_hlc =
        HlcTimestamp::from_datetime(peer_finish_ts + chrono::Duration::milliseconds(1), "android")
            .encode();

    let (resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "android-peer".to_owned(),
            consumption_log: vec![crate::db::SyncConsumptionLogRecord {
                id: "log-finish-a".to_owned(),
                entry_id: album_ids[0].clone(),
                ts: peer_finish_ts,
                status: ConsumptionStatus::Consumed,
                notes: None,
                created_at: Some(peer_finish_ts),
                hlc: peer_log_hlc,
            }],
            collection_generators: vec![crate::db::SyncCollectionGeneratorRecord {
                collection_id: col.id.clone(),
                enabled: true,
                current_entry_id: Some(album_ids[1].clone()),
                drawn_at: Some(peer_finish_ts),
                updated_at: peer_finish_ts,
                hlc: peer_gen_hlc.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    // Server must NOT have prematurely drawn a different album during consumption_log merge;
    // it must adopt the peer's Album B draw.
    let gen_after = db.catalog().get_collection_generator(&col.id).await.unwrap();
    assert_eq!(
        gen_after.current_entry.as_ref().map(|e| e.id.as_str()),
        Some(album_ids[1].as_str()),
        "server must preserve the peer's drawn entry instead of advancing with server `now`"
    );

    // Now a second peer with an already up-to-date `since_seq` sends a stale generator pointing
    // at Album C with an older HLC. Server must keep Album B AND echo it back in the response so
    // the stale peer converges.
    let stale_ts = peer_finish_ts - chrono::Duration::seconds(30);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "stale-peer").encode();
    let (conflict_resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "stale-peer".to_owned(),
            since_seq: resp.sync_seq,
            collection_generators: vec![crate::db::SyncCollectionGeneratorRecord {
                collection_id: col.id.clone(),
                enabled: true,
                current_entry_id: Some(album_ids[2].clone()),
                drawn_at: Some(stale_ts),
                updated_at: stale_ts,
                hlc: stale_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let returned = conflict_resp
        .collection_generators
        .iter()
        .find(|g| g.collection_id == col.id)
        .expect("server must return winning generator when rejecting a stale differing draw");
    assert_eq!(returned.current_entry_id.as_deref(), Some(album_ids[1].as_str()));
    assert_eq!(returned.hlc, peer_gen_hlc);
}

#[test]
fn test_staggered_phase_distribution_and_zero_post_refresh_all_pileup() {
    let refresh_all_ts = 1_700_000_000_i64;
    let interval_mins = 30_i64;
    let interval_secs = interval_mins * 60;
    let min_gap = 120_i64;

    let mut phase_buckets = [0usize; 6]; // 5-minute buckets across 30m
    let mut due_buckets = [0usize; 6];

    for idx in 0..120 {
        let url = format!("https://feeds.example.org/blog-{idx}/rss.xml");
        let phase = feed_phase_offset_secs(&url, interval_mins);
        assert!((0..interval_secs).contains(&phase));
        let p_bucket = usize::try_from(phase / 300).unwrap();
        phase_buckets[p_bucket] += 1;

        // Post-Refresh-All at `refresh_all_ts`: every feed must land in [T + 2m, T + 32m)
        // on its exact permanent phase slot, eliminating synchronized pileups at T + 30m.
        let next_due = next_scheduled_poll_epoch_secs(&url, interval_mins, refresh_all_ts, 0);
        assert!(
            next_due >= refresh_all_ts + min_gap
                && next_due < refresh_all_ts + min_gap + interval_secs,
            "next_due={next_due} outside [T+2m, T+32m) for {url}"
        );
        assert_eq!(next_due.rem_euclid(interval_secs), phase);
        let offset_from_earliest = next_due - (refresh_all_ts + min_gap);
        let d_bucket = usize::try_from(offset_from_earliest / 300).unwrap();
        due_buckets[d_bucket] += 1;

        // Steady-state on-phase poll (5s after phase slot) advances by exactly 1 interval.
        let steady_next = next_scheduled_poll_epoch_secs(&url, interval_mins, next_due + 5, 0);
        assert_eq!(steady_next, next_due + interval_secs);
    }

    for (b, count) in phase_buckets.iter().enumerate() {
        assert!(*count >= 8, "Phase bucket {b} under-populated ({count}/120)");
    }
    for (b, count) in due_buckets.iter().enumerate() {
        assert!(*count >= 8, "Post-Refresh-All bucket {b} under-populated ({count}/120)");
    }

    // Phase-aligned HRW epoch keeps the same winner across the entire [slot_start, slot_start + interval)
    // window even when crossing a wall-clock `:00` / `:30` boundary!
    let url = "https://blog.rust-lang.org/feed.xml";
    let phase = feed_phase_offset_secs(url, 30);
    assert!(phase > 0 && phase < 1800);
    let slot_start = next_phase_slot_at_or_after(1_700_000_000, phase, 1800);
    let base_dt = DateTime::from_timestamp(slot_start, 0).unwrap();
    let peers = vec![
        PeerNode {
            peer_id: "server".to_owned(),
            device_name: "Server".to_owned(),
            network_tier: NetworkTier::Wifi,
            weight: 1.0,
            last_seen_at: base_dt,
        },
        PeerNode {
            peer_id: "android-pixel".to_owned(),
            device_name: "Pixel".to_owned(),
            network_tier: NetworkTier::Wifi,
            weight: 1.0,
            last_seen_at: base_dt,
        },
        PeerNode {
            peer_id: "android-tablet".to_owned(),
            device_name: "Tablet".to_owned(),
            network_tier: NetworkTier::Wifi,
            weight: 1.0,
            last_seen_at: base_dt,
        },
    ];
    let expected_winner =
        select_feed_poller(&peers, url, 30, base_dt, None, None, None, None).unwrap();
    for delta in [1_i64, 60, 600, 1200, 1799] {
        let t = DateTime::from_timestamp(slot_start + delta, 0).unwrap();
        let fresh_peers: Vec<PeerNode> = peers
            .iter()
            .cloned()
            .map(|mut p| {
                p.last_seen_at = t;
                p
            })
            .collect();
        let winner = select_feed_poller(&fresh_peers, url, 30, t, None, None, None, None);
        assert_eq!(
            winner,
            Some(expected_winner),
            "HRW winner flipped mid-slot at +{delta}s from slot_start"
        );
    }
}

#[test]
fn test_exponential_backoff_cap_and_cross_peer_rescue_prevents_ping_pong() {
    // 30m feed progression: 1h, 2h, 4h, 8h, 16h, 24h cap
    let expected_30m = [1800, 3600, 7200, 14400, 28800, 57600, 86400, 86400];
    for (k, &exp) in expected_30m.iter().enumerate() {
        assert_eq!(target_poll_interval_secs(30, i64::try_from(k).unwrap()), exp);
    }
    // 24h (1440m) feed: 48h, 96h (4x cap), 96h
    assert_eq!(target_poll_interval_secs(1440, 0), 86_400);
    assert_eq!(target_poll_interval_secs(1440, 1), 172_800);
    assert_eq!(target_poll_interval_secs(1440, 2), 345_600);
    assert_eq!(target_poll_interval_secs(1440, 5), 345_600);
    // 48h (2880m) feed hits 7-day absolute cap
    assert_eq!(target_poll_interval_secs(2880, 1), 345_600);
    assert_eq!(target_poll_interval_secs(2880, 2), ABSOLUTE_MAX_BACKOFF_SECS);

    let url = Url::parse("https://broken.example.org/rss.xml").unwrap();
    let phase = feed_phase_offset_secs(url.as_str(), 30);
    let slot_start = next_phase_slot_at_or_after(1_700_000_000, phase, 1800);
    let last_polled = DateTime::from_timestamp(slot_start, 0).unwrap();
    let now = DateTime::from_timestamp(slot_start + 60, 0).unwrap();
    let peers = vec![
        PeerNode {
            peer_id: "server".to_owned(),
            device_name: "Server".to_owned(),
            network_tier: NetworkTier::Wifi,
            weight: 1.0,
            last_seen_at: now,
        },
        PeerNode {
            peer_id: "android-1".to_owned(),
            device_name: "Phone".to_owned(),
            network_tier: NetworkTier::Wifi,
            weight: 1.0,
            last_seen_at: now,
        },
    ];

    // Failure #1 by `server`: `android-1` gets immediate cross-peer rescue; `server` backs off.
    let fail1 = Feed {
        id: deterministic_feed_id(url.as_str()),
        feed_type: FeedType::Rss,
        url: url.clone(),
        title: Some("Broken".to_owned()),
        description: None,
        language: None,
        poll_interval: 30,
        last_polled: Some(last_polled),
        last_polled_by: Some("server".to_owned()),
        last_error: Some("HTTP 500".to_owned()),
        last_error_by: Some("server".to_owned()),
        consecutive_failures: 1,
        is_active: true,
        created_at: last_polled,
        unread_count: 0,
    };
    assert!(!should_peer_poll_feed("server", &peers, &fail1, now));
    assert!(should_peer_poll_feed("android-1", &peers, &fail1, now));

    // Failure #2 after `android-1` ALSO failed: immediate cross-peer rescue MUST STOP for BOTH peers!
    let fail2 = Feed {
        last_polled: Some(now),
        last_error_by: Some("android-1".to_owned()),
        consecutive_failures: 2,
        ..fail1
    };
    let check_1m_later = DateTime::from_timestamp(now.timestamp() + 60, 0).unwrap();
    let fresh_peers_1m: Vec<PeerNode> = peers
        .iter()
        .cloned()
        .map(|mut p| {
            p.last_seen_at = check_1m_later;
            p
        })
        .collect();
    assert!(!should_peer_poll_feed("server", &fresh_peers_1m, &fail2, check_1m_later));
    assert!(!should_peer_poll_feed("android-1", &fresh_peers_1m, &fail2, check_1m_later));

    // Still backed off at +70m (2x interval < 2h target delay; must NOT prematurely overdue-rescue!).
    let due_at_k2 = next_scheduled_poll_epoch_secs(url.as_str(), 30, now.timestamp(), 2);
    let before_due = DateTime::from_timestamp(due_at_k2 - 60, 0).unwrap();
    assert!(before_due.timestamp() - now.timestamp() > 3600);
    let fresh_peers_before: Vec<PeerNode> = peers
        .iter()
        .cloned()
        .map(|mut p| {
            p.last_seen_at = before_due;
            p
        })
        .collect();
    assert!(!should_peer_poll_feed("server", &fresh_peers_before, &fail2, before_due));
    assert!(!should_peer_poll_feed("android-1", &fresh_peers_before, &fail2, before_due));

    // At `due_at_k2`, exactly one peer claims the retry; at `due_at_k2 + 30m`, either peer can rescue.
    let at_due = DateTime::from_timestamp(due_at_k2, 0).unwrap();
    let fresh_peers_due: Vec<PeerNode> = peers
        .iter()
        .cloned()
        .map(|mut p| {
            p.last_seen_at = at_due;
            p
        })
        .collect();
    let server_polls_due = should_peer_poll_feed("server", &fresh_peers_due, &fail2, at_due);
    let android_polls_due = should_peer_poll_feed("android-1", &fresh_peers_due, &fail2, at_due);
    assert_ne!(
        server_polls_due, android_polls_due,
        "Exactly one peer should own the backed-off retry at due_at"
    );

    let overdue_rescue_time = DateTime::from_timestamp(due_at_k2 + 1800, 0).unwrap();
    let fresh_peers_overdue: Vec<PeerNode> = peers
        .iter()
        .cloned()
        .map(|mut p| {
            p.last_seen_at = overdue_rescue_time;
            p
        })
        .collect();
    assert!(should_peer_poll_feed("server", &fresh_peers_overdue, &fail2, overdue_rescue_time));
    assert!(should_peer_poll_feed("android-1", &fresh_peers_overdue, &fail2, overdue_rescue_time));
}

#[tokio::test]
async fn test_peer_sync_consecutive_failures_normalization_and_remote_unarchive_reset() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://sync-backoff.example.org/rss.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("Backoff Feed"), None).await.unwrap();
    let base_ts = SyncRepo::now_utc();
    let hlc1 =
        HlcTimestamp::from_datetime(base_ts + chrono::Duration::seconds(5), "phone-1").encode();

    // 1. Wire backwards-compat: deserialize raw JSON `SyncFeedSource` OMITTING `"consecutive_failures"`
    //    from a pre-upgrade peer with non-blank `last_error` -> serde defaults to 0, server normalizes
    //    to 1 on merge and echoes 1 back.
    let t1 = base_ts + chrono::Duration::seconds(5);
    let legacy_fs_json = serde_json::json!({
        "feed_url": feed_url.as_str(),
        "source_type": "rss",
        "feed_title": "Backoff Feed",
        "language": "en",
        "poll_interval": 30,
        "is_active": true,
        "last_polled_at": t1.to_rfc3339(),
        "last_polled_by": "phone-1",
        "last_error": "HTTP 503",
        "last_error_by": "phone-1",
        "hlc": hlc1
    })
    .to_string();
    let legacy_fs: SyncFeedSource = serde_json::from_str(&legacy_fs_json).unwrap();
    assert_eq!(legacy_fs.consecutive_failures, 0, "Omitted JSON field must deserialize to 0");
    let mut legacy_entry = make_feed_sync_entry(
        &feed.id,
        feed_url.as_str(),
        FeedType::Rss,
        Some("Backoff Feed"),
        None,
        true,
        Some(t1),
        Some("phone-1"),
        &hlc1,
    );
    legacy_entry.feed_source = Some(legacy_fs);
    let (resp1, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            entries: vec![legacy_entry],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let fs1 = resp1.entries.iter().find(|e| e.id == feed.id).unwrap().feed_source.as_ref().unwrap();
    assert_eq!(fs1.consecutive_failures, 1);

    // 2. Peer sends over-cap `consecutive_failures = 99` -> clamped to MAX_CONSECUTIVE_FAILURES (20).
    let t2 = base_ts + chrono::Duration::seconds(10);
    let mut cap_entry = make_feed_sync_entry(
        &feed.id,
        feed_url.as_str(),
        FeedType::Rss,
        Some("Backoff Feed"),
        None,
        true,
        Some(t2),
        Some("phone-1"),
        &hlc1,
    );
    if let Some(ref mut fs) = cap_entry.feed_source {
        fs.last_error = Some("HTTP 503".to_owned());
        fs.last_error_by = Some("phone-1".to_owned());
        fs.consecutive_failures = 99;
    }
    let _ = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            since_seq: resp1.sync_seq,
            entries: vec![cap_entry],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(
        db.feeds().get(&feed.id).await.unwrap().unwrap().consecutive_failures,
        MAX_CONSECUTIVE_FAILURES
    );

    // 3. Archive locally, then remote peer unarchives with newer config HLC and cleared error
    //    (without a newer `last_polled_at`) -> server clears stale failure state!
    assert!(db.feeds().archive(&feed.id).await.unwrap());
    let unarchive_hlc =
        HlcTimestamp::from_datetime(base_ts + chrono::Duration::minutes(2), "phone-1").encode();
    let unarchive_entry = make_feed_sync_entry(
        &feed.id,
        feed_url.as_str(),
        FeedType::Rss,
        Some("Backoff Feed"),
        None,
        true,
        Some(t2),
        Some("phone-1"),
        &unarchive_hlc,
    );
    let _ = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            entries: vec![unarchive_entry],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let unarchived = db.feeds().get(&feed.id).await.unwrap().unwrap();
    assert!(unarchived.is_active);
    assert_eq!(unarchived.consecutive_failures, 0);
    assert!(unarchived.last_error.is_none());
    assert!(unarchived.last_error_by.is_none());
}

#[tokio::test]
async fn test_migration_017_backfill_and_trigger_hlc_separation() {
    use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};

    let temp_dir =
        std::env::temp_dir().join(format!("roche-v017-mig-test-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&temp_dir).await.unwrap();
    let db_path = temp_dir.join("roche.db");

    // Apply migrations ONLY up to version 16
    let opts =
        SqliteConnectOptions::new().filename(&db_path).create_if_missing(true).foreign_keys(true);
    let pre_pool = SqlitePoolOptions::new().max_connections(1).connect_with(opts).await.unwrap();
    let mut migrator = sqlx::migrate!("./migrations");
    migrator.migrations.to_mut().retain(|m| m.version <= 16);
    migrator.run(&pre_pool).await.unwrap();

    let healthy_id = deterministic_feed_id("https://healthy.example.org/rss.xml");
    let failing_id = deterministic_feed_id("https://failing.example.org/rss.xml");
    for (id, url, err, err_by) in [
        (&healthy_id, "https://healthy.example.org/rss.xml", None, None),
        (&failing_id, "https://failing.example.org/rss.xml", Some("HTTP 503"), Some("server")),
    ] {
        sqlx::query("INSERT INTO entries (id, kind, title) VALUES (?, 'publication', ?)")
            .bind(id)
            .bind(url)
            .execute(&pre_pool)
            .await
            .unwrap();
        sqlx::query(
            "INSERT INTO collection_specs (collection_id, mode, collection_role, show_in_lists)
             VALUES (?, 'feed', 'feed', 0)",
        )
        .bind(id)
        .execute(&pre_pool)
        .await
        .unwrap();
        sqlx::query(
            "INSERT INTO feed_sources
             (collection_id, feed_url, source_type, poll_interval, is_active, last_error, last_error_by)
             VALUES (?, ?, 'rss', 30, 1, ?, ?)",
        )
        .bind(id)
        .bind(url)
        .bind(err)
        .bind(err_by)
        .execute(&pre_pool)
        .await
        .unwrap();
    }

    let failing_hlc_before: String =
        sqlx::query_scalar("SELECT hlc FROM feed_sources WHERE collection_id = ?")
            .bind(&failing_id)
            .fetch_one(&pre_pool)
            .await
            .unwrap();
    pre_pool.close().await;

    // Connect with `Db::connect` to apply migration 017
    let db = Db::connect(&db_path).await.unwrap();
    let healthy = db.feeds().get(&healthy_id).await.unwrap().unwrap();
    let failing = db.feeds().get(&failing_id).await.unwrap().unwrap();
    assert_eq!(healthy.consecutive_failures, 0);
    assert_eq!(failing.consecutive_failures, 1);

    let (hlc_after_mig, seq_before): (String, i64) = sqlx::query_as(
        "SELECT fs.hlc, e.sync_seq
         FROM feed_sources fs
         JOIN entries e ON e.id = fs.collection_id
         WHERE fs.collection_id = ?",
    )
    .bind(&failing_id)
    .fetch_one(db.pool())
    .await
    .unwrap();
    assert_eq!(hlc_after_mig, failing_hlc_before, "Migration 017 must not bump config HLC");

    // Updating ONLY `consecutive_failures` bumps `entries.sync_seq` WITHOUT bumping `feed_sources.hlc`
    sqlx::query("UPDATE feed_sources SET consecutive_failures = 2 WHERE collection_id = ?")
        .bind(&failing_id)
        .execute(db.pool())
        .await
        .unwrap();
    let (hlc_after_bump, seq_after): (String, i64) = sqlx::query_as(
        "SELECT fs.hlc, e.sync_seq
         FROM feed_sources fs
         JOIN entries e ON e.id = fs.collection_id
         WHERE fs.collection_id = ?",
    )
    .bind(&failing_id)
    .fetch_one(db.pool())
    .await
    .unwrap();
    assert_eq!(hlc_after_bump, failing_hlc_before, "Backoff telemetry must not bump config HLC");
    assert!(seq_after > seq_before, "Updating consecutive_failures must bump entries.sync_seq");

    // CHECK constraint rejects negative consecutive_failures
    let neg_res =
        sqlx::query("UPDATE feed_sources SET consecutive_failures = -1 WHERE collection_id = ?")
            .bind(&failing_id)
            .execute(db.pool())
            .await;
    assert!(neg_res.is_err(), "CHECK (consecutive_failures >= 0) must reject negative values");

    db.pool().close().await;
    drop(db);
    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_manual_wtr_and_feed_items_cannot_resurrect_past_item_tombstones() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let url = Url::parse("https://example.com/manual-wtr-item").unwrap();
    let (saved, _) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &url,
            title: "Manual WTR Item",
            summary: None,
            content_html: Some("<p>saved body</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    assert_eq!(saved.inbox_state, InboxState::None);
    assert!(db.items().delete(&saved.id).await.unwrap());
    assert!(db.items().get(&saved.id).await.unwrap().is_none());

    let stale_ts = Utc::now() - chrono::Duration::minutes(10);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "peer-stale").encode();

    // 1. Stale manual/WTR item carrying `InboxState::None` + content must NOT resurrect
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            entries: vec![SyncEntryRecord {
                id: saved.id.clone(),
                kind: EntryKind::Article,
                title: "Manual WTR Item".to_owned(),
                creators: Vec::new(),
                url: Some(url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(stale_ts),
                updated_at: Some(stale_ts),
                hlc: stale_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>stale body</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::None,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: Some(stale_ts),
                    hlc: stale_hlc.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(db.items().get(&saved.id).await.unwrap().is_none());
    let entry_exists: Option<i64> = sqlx::query_scalar("SELECT 1 FROM entries WHERE id = ?")
        .bind(&saved.id)
        .fetch_optional(db.pool())
        .await
        .unwrap();
    assert!(entry_exists.is_none());

    // 2. Stale content-only record (state = None, content = Some) must also be suppressed
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            entries: vec![SyncEntryRecord {
                id: saved.id.clone(),
                kind: EntryKind::Article,
                title: "Manual WTR Item".to_owned(),
                creators: Vec::new(),
                url: Some(url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(stale_ts),
                updated_at: Some(stale_ts),
                hlc: stale_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>stale content-only</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: None,
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(db.items().get(&saved.id).await.unwrap().is_none());

    // 3. Stale feed-scoped item sent WITHOUT `collection_entries` (`resolved_feed_url == None`)
    // must still match its feed-scoped tombstone via deterministic item ID.
    let feed_url = Url::parse("https://example.com/feed-for-tomb.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Tomb Feed"), None).await.unwrap();
    let post_url = Url::parse("https://example.com/feed-post-1").unwrap();
    let feed_item = db
        .items()
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Feed Post 1",
            url: &post_url,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    assert!(db.items().delete(&feed_item.id).await.unwrap());
    assert!(db.items().get(&feed_item.id).await.unwrap().is_none());

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            entries: vec![SyncEntryRecord {
                id: feed_item.id.clone(),
                kind: EntryKind::Article,
                title: "Feed Post 1".to_owned(),
                creators: Vec::new(),
                url: Some(post_url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(stale_ts),
                updated_at: Some(stale_ts),
                hlc: stale_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>stale feed body</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::None,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: Some(stale_ts),
                    hlc: stale_hlc.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(db.items().get(&feed_item.id).await.unwrap().is_none());

    // 4. Stale manual item WITH catalog metadata preserves metadata without resurrecting
    // `entry_state` or `entry_content` as a live reader item.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            entries: vec![SyncEntryRecord {
                id: saved.id.clone(),
                kind: EntryKind::Article,
                title: "Manual WTR Item".to_owned(),
                creators: Vec::new(),
                url: Some(url.to_string()),
                published_at: None,
                release_year: Some(2025),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(stale_ts),
                updated_at: Some(stale_ts),
                hlc: stale_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>should stay suppressed</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::None,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: Some(stale_ts),
                    hlc: stale_hlc,
                }),
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(db.items().get(&saved.id).await.unwrap().is_none());

    // 5. Newer manual item clears the `manual\0` tombstone and resurrects cleanly
    let newer_ts = Utc::now() + chrono::Duration::seconds(30);
    let newer_hlc = HlcTimestamp::from_datetime(newer_ts, "peer-fresh").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-fresh".to_owned(),
            entries: vec![SyncEntryRecord {
                id: saved.id.clone(),
                kind: EntryKind::Article,
                title: "Manual WTR Item Resurrected".to_owned(),
                creators: Vec::new(),
                url: Some(url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(newer_ts),
                updated_at: Some(newer_ts),
                hlc: newer_hlc.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>fresh body</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::None,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: Some(newer_ts),
                    hlc: newer_hlc,
                }),
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(db.items().get(&saved.id).await.unwrap().is_some());
    let tomb_left: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ?",
    )
    .bind(item_natural_key(None, url.as_str()))
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(tomb_left.is_none());
}

#[tokio::test]
async fn test_renamed_tag_sync_updates_in_place_without_duplication() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let old_color = crate::db::HexColor::parse("#112233").unwrap();
    let tag = db.tags().create("OldTopic", Some(&old_color)).await.unwrap();
    let item_url = Url::parse("https://example.com/tagged-article").unwrap();
    let (item, _) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &item_url,
            title: "Tagged Article",
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
    db.tags().add_to_item(&item.id, &tag.id).await.unwrap();

    // Stale rename must be ignored
    let stale_ts = Utc::now() - chrono::Duration::minutes(10);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "peer-stale").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            entries: vec![make_tag_sync_entry(&tag.id, "IgnoredName", Some("#999999"), &stale_hlc)],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let tags_after_stale = db.tags().list().await.unwrap();
    assert_eq!(tags_after_stale.len(), 1);
    assert_eq!(tags_after_stale[0].name, "OldTopic");

    // Newer rename with the original tag ID updates title, slug, color, and entry_identifiers in-place
    let newer_ts = Utc::now() + chrono::Duration::seconds(30);
    let newer_hlc = HlcTimestamp::from_datetime(newer_ts, "peer-rename").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-rename".to_owned(),
            entries: vec![make_tag_sync_entry(&tag.id, "NewTopic", Some("#445566"), &newer_hlc)],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let tags_after_rename = db.tags().list().await.unwrap();
    assert_eq!(tags_after_rename.len(), 1, "renaming a tag over sync must not create a duplicate");
    assert_eq!(tags_after_rename[0].id, tag.id);
    assert_eq!(tags_after_rename[0].name, "NewTopic");
    assert_eq!(tags_after_rename[0].color, Some(crate::db::HexColor::parse("#445566").unwrap()));

    let slug: Option<String> =
        sqlx::query_scalar("SELECT slug FROM collection_specs WHERE collection_id = ?")
            .bind(&tag.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert_eq!(slug.as_deref(), Some("tag:newtopic"));

    let ident_vals: Vec<String> = sqlx::query_scalar(
        "SELECT value FROM entry_identifiers WHERE scheme = 'tag' AND entry_id = ?",
    )
    .bind(&tag.id)
    .fetch_all(db.pool())
    .await
    .unwrap();
    assert_eq!(ident_vals, vec!["newtopic".to_owned()]);

    let item_tags = db.tags().list_for_item(&item.id).await.unwrap();
    assert_eq!(item_tags.len(), 1);
    assert_eq!(item_tags[0].name, "NewTopic");
}

#[tokio::test]
async fn test_collection_entry_tombstone_clears_or_redraws_dangling_generator() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let col = db
        .catalog()
        .create_manual_collection(
            "Sync Gen Pool",
            Some("sync-gen-pool"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let book_a = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book A".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let book_b = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book B".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog().add_collection_entry(&col.id, &book_a.id, None).await.unwrap();
    db.catalog().add_collection_entry(&col.id, &book_b.id, None).await.unwrap();
    db.catalog()
        .update_collection_generator(&col.id, Some(true), Some(&book_a.id), None)
        .await
        .unwrap();

    // Incoming CollectionEntry tombstone removes Book A -> generator must redraw Book B
    let first_delete_ts = Utc::now() + chrono::Duration::seconds(20);
    let first_delete_hlc = HlcTimestamp::from_datetime(first_delete_ts, "peer-a").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::CollectionEntry,
                natural_key: collection_entry_natural_key(&col.id, &book_a.id),
                deleted_at: first_delete_ts,
                hlc: first_delete_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let after_a = db.catalog().get_collection_generator(&col.id).await.unwrap();
    assert_eq!(after_a.current_entry.as_ref().map(|e| e.id.as_str()), Some(book_b.id.as_str()));

    // Incoming CollectionEntry tombstone removes Book B (last entry) -> generator must clear to None
    let second_delete_ts = Utc::now() + chrono::Duration::seconds(40);
    let second_delete_hlc = HlcTimestamp::from_datetime(second_delete_ts, "peer-a").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::CollectionEntry,
                natural_key: collection_entry_natural_key(&col.id, &book_b.id),
                deleted_at: second_delete_ts,
                hlc: second_delete_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let after_b = db.catalog().get_collection_generator(&col.id).await.unwrap();
    assert!(after_b.current_entry.is_none());
}

#[tokio::test]
async fn test_generator_noop_sync_does_not_bump_sync_seq_or_overwrite_hlc() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let col = db
        .catalog()
        .create_manual_collection("Noop Gen", Some("noop-gen"), CollectionRole::List, None, None)
        .await
        .unwrap();
    let book_a = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book A".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog().add_collection_entry(&col.id, &book_a.id, None).await.unwrap();
    db.catalog()
        .update_collection_generator(&col.id, Some(true), Some(&book_a.id), None)
        .await
        .unwrap();

    // Store a Z-formatted timestamp & explicit peer HLC so naive re-binding (+00:00) would trip the AU trigger
    let local_ts = Utc::now() - chrono::Duration::minutes(1);
    let local_hlc = HlcTimestamp::from_datetime(local_ts, "peer-winner").encode();
    let z_formatted = local_ts.format("%Y-%m-%dT%H:%M:%SZ").to_string();
    sqlx::query("UPDATE collection_generators SET updated_at = ?, hlc = ? WHERE collection_id = ?")
        .bind(&z_formatted)
        .bind(&local_hlc)
        .bind(&col.id)
        .execute(db.pool())
        .await
        .unwrap();

    let (seq_before, hlc_before): (i64, String) =
        sqlx::query_as("SELECT sync_seq, hlc FROM collection_generators WHERE collection_id = ?")
            .bind(&col.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert_eq!(hlc_before, local_hlc);

    // Stale incoming generator with older HLC and identical (enabled, current_entry_id)
    let stale_ts = local_ts - chrono::Duration::minutes(5);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "peer-stale").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            collection_generators: vec![crate::db::SyncCollectionGeneratorRecord {
                collection_id: col.id.clone(),
                enabled: true,
                current_entry_id: Some(book_a.id.clone()),
                drawn_at: Some(stale_ts),
                updated_at: stale_ts,
                hlc: stale_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let (seq_after, hlc_after): (i64, String) =
        sqlx::query_as("SELECT sync_seq, hlc FROM collection_generators WHERE collection_id = ?")
            .bind(&col.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert_eq!(seq_after, seq_before, "stale no-op generator sync must not bump sync_seq");
    assert_eq!(hlc_after, hlc_before, "stale no-op generator sync must not overwrite hlc");
}

#[tokio::test]
async fn test_consumption_log_tombstone_cleared_on_newer_sync_resurrection() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let book = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Resurrected Log Book".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let log_id = "custom-log-id-123";
    let tomb_ts = Utc::now();
    let tomb_hlc = HlcTimestamp::from_datetime(tomb_ts, "peer-del").encode();

    // Apply incoming ConsumptionLog tombstone
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-del".to_owned(),
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::ConsumptionLog,
                natural_key: log_id.to_owned(),
                deleted_at: tomb_ts,
                hlc: tomb_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    // Stale log must remain suppressed and tombstone must remain present
    let stale_ts = tomb_ts - chrono::Duration::seconds(30);
    let stale_hlc = HlcTimestamp::from_datetime(stale_ts, "peer-stale").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-stale".to_owned(),
            consumption_log: vec![crate::db::SyncConsumptionLogRecord {
                id: log_id.to_owned(),
                entry_id: book.id.clone(),
                ts: stale_ts,
                status: ConsumptionStatus::Consumed,
                notes: None,
                created_at: Some(stale_ts),
                hlc: stale_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let log_exists: Option<i64> = sqlx::query_scalar("SELECT 1 FROM consumption_log WHERE id = ?")
        .bind(log_id)
        .fetch_optional(db.pool())
        .await
        .unwrap();
    assert!(log_exists.is_none());

    // Newer log resurrects row AND clears the ConsumptionLog tombstone
    let newer_ts = tomb_ts + chrono::Duration::seconds(30);
    let newer_hlc = HlcTimestamp::from_datetime(newer_ts, "peer-new").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-new".to_owned(),
            consumption_log: vec![crate::db::SyncConsumptionLogRecord {
                id: log_id.to_owned(),
                entry_id: book.id.clone(),
                ts: newer_ts,
                status: ConsumptionStatus::Consumed,
                notes: Some("Logged again".to_owned()),
                created_at: Some(newer_ts),
                hlc: newer_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let resurrected: Option<i64> = sqlx::query_scalar("SELECT 1 FROM consumption_log WHERE id = ?")
        .bind(log_id)
        .fetch_optional(db.pool())
        .await
        .unwrap();
    assert!(resurrected.is_some());

    let tomb_remaining: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sync_tombstones WHERE entity_type = 'consumption_log' AND natural_key = ?",
    )
    .bind(log_id)
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(
        tomb_remaining.is_none(),
        "ConsumptionLog tombstone must be cleared on newer resurrection"
    );
}

#[tokio::test]
async fn test_graph_tombstones_resolve_remapped_ids_in_same_sync_batch() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    // Verify retired 1001_albums placeholder UUID is no longer protected
    assert!(
        !crate::db::is_protected_system_collection_id("9eb44de7-bdfb-5bea-85d1-be913c542d8b"),
        "retired 1001_albums placeholder must not be protected after migration 013"
    );

    let list = catalog
        .create_manual_collection(
            "Weekend Watch",
            Some("weekend_watch"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let canon = catalog
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Stalker".to_owned(),
            release_year: Some(1979),
            identifiers: vec![crate::db::EntryIdentifier {
                scheme: "imdb".to_owned(),
                value: "tt0079944".to_owned(),
            }],
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let dup = catalog
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Stalker (Dup)".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.add_collection_entry(&list.id, &canon.id, None).await.unwrap();
    catalog.update_collection_generator(&list.id, Some(true), Some(&canon.id), None).await.unwrap();

    // A peer sends in ONE batch:
    //  1. `dup` merged into `canon` (populating `id_resolver` during `merge_incoming_entries_in_tx`)
    //  2. `CollectionEntry` tombstone referencing `(list_alias -> list.id, dup.id)`
    //  3. `CollectionGenerator` tombstone referencing a remapped manual collection alias
    let future_ts = Utc::now() + chrono::Duration::seconds(30);
    let merge_hlc = HlcTimestamp::from_datetime(future_ts, "peer-merge").encode();
    let tomb_hlc =
        HlcTimestamp::from_datetime(future_ts + chrono::Duration::seconds(5), "peer-merge")
            .encode();
    let list_alias_id = "peer-local-weekend-watch-alias";

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-merge".to_owned(),
            entries: vec![
                SyncEntryRecord {
                    id: list_alias_id.to_owned(),
                    kind: EntryKind::Collection,
                    title: "Weekend Watch".to_owned(),
                    creators: Vec::new(),
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(future_ts),
                    updated_at: Some(future_ts),
                    hlc: merge_hlc.clone(),
                    content: None,
                    state: None,
                    collection_spec: Some(SyncCollectionSpec {
                        slug: Some("weekend_watch".to_owned()),
                        mode: CollectionMode::Manual,
                        role: CollectionRole::List,
                        rule_json: None,
                        show_in_lists: true,
                        color: None,
                    }),
                    feed_source: None,
                },
                SyncEntryRecord {
                    id: dup.id.clone(),
                    kind: EntryKind::Movie,
                    title: "Stalker (Dup)".to_owned(),
                    creators: Vec::new(),
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: Some(canon.id.clone()),
                    created_at: Some(future_ts),
                    updated_at: Some(future_ts),
                    hlc: merge_hlc,
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            tombstones: vec![
                SyncTombstone {
                    entity_type: ActivityEntityType::CollectionEntry,
                    natural_key: crate::db::collection_entry_natural_key(list_alias_id, &dup.id),
                    deleted_at: future_ts + chrono::Duration::seconds(5),
                    hlc: tomb_hlc.clone(),
                },
                SyncTombstone {
                    entity_type: ActivityEntityType::CollectionGenerator,
                    natural_key: list_alias_id.to_owned(),
                    deleted_at: future_ts + chrono::Duration::seconds(5),
                    hlc: tomb_hlc,
                },
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let member_still_present: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
    )
    .bind(&list.id)
    .bind(&canon.id)
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(
        member_still_present.is_none(),
        "CollectionEntry tombstone using remapped list & merged entry IDs must remove canonical membership"
    );

    let gen_still_present: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM collection_generators WHERE collection_id = ?")
            .bind(&list.id)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert!(
        gen_still_present.is_none(),
        "CollectionGenerator tombstone using remapped list ID must delete canonical generator"
    );
}

#[tokio::test]
async fn test_hlc_sanitization_timestamp_clamping_and_recorded_at_pruning() {
    // 1. HlcTimestamp::parse rejects invalid node_id characters and >64-char node IDs
    let valid = HlcTimestamp::new(1_700_000_000_000, 3, "peer-alpha_01").encode();
    assert!(HlcTimestamp::parse(&valid).is_some());
    for bad_node in ["", "bad space", "bad/slash", "bad\nline", &"x".repeat(65)] {
        let raw = format!("001700000000000-0001-{bad_node}");
        assert!(HlcTimestamp::parse(&raw).is_none(), "must reject node_id '{bad_node}'");
    }

    // 2. HlcClock::witness clamps u16::MAX rollover at the drift ceiling
    let mut clock = HlcClock::new("server", None);
    let now_ms = 1_700_000_000_000i64;
    let drift_ceiling = now_ms + hlc::MAX_CLOCK_DRIFT_MS;
    let overflow_remote = HlcTimestamp::new(drift_ceiling, u16::MAX, "rogue");
    let witnessed = clock.witness(&overflow_remote, now_ms);
    assert_eq!(witnessed.wall_ms, drift_ceiling, "rollover must not exceed max_allowed_wall");
    assert_eq!(witnessed.counter, u16::MAX);

    // 3. PeerSync clamps future timestamps and keeps tombstones with ancient deleted_at alive via recorded_at
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();
    let far_future = now + chrono::Duration::days(3650);
    let ancient =
        chrono::DateTime::parse_from_rfc3339("1970-01-02T00:00:00Z").unwrap().with_timezone(&Utc);
    let hlc = HlcTimestamp::new(now.timestamp_millis(), 1, "peer-a").encode();

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-a".to_owned(),
            network_tier: NetworkTier::Wifi,
            weight: Some(f64::NAN),
            entries: vec![SyncEntryRecord {
                id: "future-work-1".to_owned(),
                kind: EntryKind::Movie,
                title: "Future Timestamp Movie".to_owned(),
                creators: Vec::new(),
                url: None,
                published_at: Some(far_future),
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(far_future),
                updated_at: Some(far_future),
                hlc: hlc.clone(),
                content: None,
                state: None,
                collection_spec: None,
                feed_source: None,
            }],
            tombstones: vec![SyncTombstone {
                entity_type: ActivityEntityType::Entry,
                natural_key: "phantom-deleted-entry".to_owned(),
                deleted_at: ancient,
                hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let (created_at, updated_at, published_at): (
        DateTime<Utc>,
        DateTime<Utc>,
        Option<DateTime<Utc>>,
    ) = sqlx::query_as(
        "SELECT created_at, updated_at, published_at FROM entries WHERE id = 'future-work-1'",
    )
    .fetch_one(db.pool())
    .await
    .unwrap();
    let max_allowed = Utc::now() + chrono::Duration::milliseconds(hlc::MAX_CLOCK_DRIFT_MS + 5_000);
    assert!(created_at <= max_allowed);
    assert!(updated_at <= max_allowed);
    assert!(published_at.unwrap() <= max_allowed);

    // Pruning 30-day-old tombstones MUST NOT delete the freshly recorded tombstone whose deleted_at was 1970
    db.sync().prune_activity_older_than_days(30).await.unwrap();
    let tomb_exists: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sync_tombstones WHERE entity_type = 'entry' AND natural_key = 'phantom-deleted-entry'",
    )
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(
        tomb_exists.is_some(),
        "Freshly recorded tombstone must not be prematurely pruned by backdated deleted_at"
    );
}

#[tokio::test]
async fn test_protected_collections_history_tombstone_and_peer_pruning() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();
    let movie = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Stalker".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&movie.id, ConsumptionStatus::Consumed, Some("Great"), None)
        .await
        .unwrap();

    let movies_col_id = deterministic_system_collection_id("movies");
    let tomb_hlc =
        HlcTimestamp::new((now + chrono::Duration::seconds(30)).timestamp_millis(), 1, "peer-b")
            .encode();

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-b".to_owned(),
            network_tier: NetworkTier::Cellular,
            weight: Some(999.0),
            entries: vec![SyncEntryRecord {
                id: STARRED_COLLECTION_ID.to_owned(),
                kind: EntryKind::Article,
                title: "Hijacked Starred".to_owned(),
                creators: Vec::new(),
                url: None,
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: None,
                updated_at: None,
                hlc: tomb_hlc.clone(),
                content: None,
                state: None,
                collection_spec: None,
                feed_source: None,
            }],
            tombstones: vec![
                SyncTombstone {
                    entity_type: ActivityEntityType::Entry,
                    natural_key: movies_col_id.clone(),
                    deleted_at: now,
                    hlc: tomb_hlc.clone(),
                },
                SyncTombstone {
                    entity_type: ActivityEntityType::Entry,
                    natural_key: movie.id.clone(),
                    deleted_at: now,
                    hlc: tomb_hlc,
                },
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    // Protected Starred & dynamic Movies collection remain intact
    let starred_title: String = sqlx::query_scalar("SELECT title FROM entries WHERE id = ?")
        .bind(STARRED_COLLECTION_ID)
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert_ne!(starred_title, "Hijacked Starred");
    let movies_still_exists: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM collection_specs WHERE collection_id = ?")
            .bind(&movies_col_id)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert!(movies_still_exists.is_some());

    // Movie with consumption history was preserved AND no Entry tombstone was persisted for it
    assert!(db.catalog().get_entry(&movie.id).await.unwrap().is_some());
    let movie_tomb: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sync_tombstones WHERE entity_type = 'entry' AND natural_key = ?",
    )
    .bind(&movie.id)
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(movie_tomb.is_none());

    // Cellular weight was bounded to <= 2.0 and stale peers prune cleanly while preserving SERVER_PEER_ID
    let peer_b_weight = db.sync().get_peer_weight("peer-b").await.unwrap().unwrap();
    assert!((0.2..=2.0).contains(&peer_b_weight));

    let stale_ts = now - chrono::Duration::days(45);
    sqlx::query("UPDATE peer_nodes SET last_seen_at = ? WHERE peer_id IN ('peer-b', ?)")
        .bind(stale_ts)
        .bind(SERVER_PEER_ID)
        .execute(db.pool())
        .await
        .unwrap();
    let pruned = db.sync().prune_stale_peer_nodes(30).await.unwrap();
    assert_eq!(pruned, 1);
    assert!(db.sync().get_peer_weight("peer-b").await.unwrap().is_none());
    assert!(db.sync().get_peer_weight(SERVER_PEER_ID).await.unwrap().is_some());
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_sync_hlc_monotonicity_reserved_schemes_and_page_budget() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();

    // 1. Incoming new work carrying reserved schemes (`slug`, `tag`, `feed_url`) must not abort sync,
    //    and headless `kind = 'collection'` entries without `collection_spec` must be skipped.
    let newer_hlc = HlcTimestamp::from_datetime(now, "peer-c").encode();
    let resp = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-c".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![
                crate::db::SyncEntryRecord {
                    id: "work-with-reserved-scheme".to_owned(),
                    kind: EntryKind::Book,
                    title: "Clean Book".to_owned(),
                    creators: vec!["Author".to_owned()],
                    url: None,
                    published_at: None,
                    release_year: Some(2020),
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    merged_into_id: None,
                    identifiers: vec![
                        crate::db::EntryIdentifier {
                            scheme: "slug".to_owned(),
                            value: "movies".to_owned(),
                        },
                        crate::db::EntryIdentifier {
                            scheme: "isbn".to_owned(),
                            value: "9780143127550".to_owned(),
                        },
                    ],
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                    created_at: Some(now),
                    updated_at: Some(now),
                    hlc: newer_hlc.clone(),
                },
                crate::db::SyncEntryRecord {
                    id: "headless-col-entry".to_owned(),
                    kind: EntryKind::Collection,
                    title: "Headless Collection".to_owned(),
                    creators: vec![],
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    merged_into_id: None,
                    identifiers: vec![],
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                    created_at: Some(now),
                    updated_at: Some(now),
                    hlc: newer_hlc.clone(),
                },
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(resp.0.sync_seq > 0);
    let saved_book = db.catalog().get_entry("work-with-reserved-scheme").await.unwrap().unwrap();
    assert!(saved_book.identifiers.iter().any(|i| i.scheme == "isbn"));
    assert!(!saved_book.identifiers.iter().any(|i| i.scheme == "slug"));
    assert!(db.catalog().get_entry("headless-col-entry").await.unwrap().is_none());

    // 2. Album edition-variant dedup with older HLC must NOT regress `entries.hlc` or `created_at`
    let album = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "OK Computer".to_owned(),
            creators: vec!["Radiohead".to_owned()],
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let high_hlc = HlcTimestamp::from_datetime(now + chrono::Duration::seconds(30), "srv").encode();
    sqlx::query("UPDATE entries SET hlc = ? WHERE id = ?")
        .bind(&high_hlc)
        .bind(&album.id)
        .execute(db.pool())
        .await
        .unwrap();
    let orig_created: DateTime<Utc> =
        sqlx::query_scalar("SELECT created_at FROM entries WHERE id = ?")
            .bind(&album.id)
            .fetch_one(db.pool())
            .await
            .unwrap();

    let older_ts = now - chrono::Duration::hours(5);
    let older_hlc = HlcTimestamp::from_datetime(older_ts, "peer-old").encode();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-old".to_owned(),
            entries: vec![crate::db::SyncEntryRecord {
                id: "dup-ok-computer-deluxe".to_owned(),
                kind: EntryKind::Album,
                title: "OK Computer (Deluxe Edition)".to_owned(),
                creators: vec!["Radiohead".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1997),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                merged_into_id: None,
                identifiers: vec![],
                content: None,
                state: None,
                collection_spec: None,
                feed_source: None,
                created_at: Some(older_ts),
                updated_at: Some(older_ts),
                hlc: older_hlc.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let (after_hlc, after_created): (String, DateTime<Utc>) =
        sqlx::query_as("SELECT hlc, created_at FROM entries WHERE id = ?")
            .bind(&album.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert!(
        after_hlc >= high_hlc && after_hlc > older_hlc,
        "deduplicated insert must never regress entries.hlc (got {after_hlc}, expected >= {high_hlc})"
    );
    assert_eq!(after_created, orig_created, "deduplicated insert must never overwrite created_at");

    // 3. Auto-log dedup onto an existing manual consumed log must NOT regress `consumption_log.hlc`
    let manual_log = db
        .catalog()
        .log_consumption(&album.id, ConsumptionStatus::Consumed, Some("Great album"), Some(now))
        .await
        .unwrap();
    sqlx::query("UPDATE consumption_log SET hlc = ? WHERE id = ?")
        .bind(&high_hlc)
        .bind(&manual_log.id)
        .execute(db.pool())
        .await
        .unwrap();
    let auto_id = crate::db::sync::deterministic_auto_consumption_log_id(&album.id);
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-old".to_owned(),
            consumption_log: vec![crate::db::SyncConsumptionLogRecord {
                id: auto_id,
                entry_id: album.id.clone(),
                ts: older_ts,
                status: ConsumptionStatus::Consumed,
                notes: None,
                created_at: Some(older_ts),
                hlc: older_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let manual_hlc_after: String =
        sqlx::query_scalar("SELECT hlc FROM consumption_log WHERE id = ?")
            .bind(&manual_log.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert_eq!(manual_hlc_after, high_hlc, "auto-log dedup must never regress manual log HLC");

    // 4. HRW `NaN` weight guard + Cellular cap on existing Wifi peer without explicit weight
    let nan_peer = PeerNode {
        peer_id: "nan-peer".to_owned(),
        device_name: "Bad".to_owned(),
        network_tier: NetworkTier::Wifi,
        weight: f64::NAN,
        last_seen_at: now,
    };
    let good_peer = PeerNode {
        peer_id: "good-peer".to_owned(),
        device_name: "Good".to_owned(),
        network_tier: NetworkTier::Wifi,
        weight: 1.0,
        last_seen_at: now,
    };
    assert_eq!(effective_peer_weight(&nan_peer, None, None, None), 0.0);
    assert_eq!(
        select_feed_poller(
            &[nan_peer, good_peer],
            "https://example.com/rss",
            60,
            now,
            None,
            None,
            None,
            None
        ),
        Some("good-peer")
    );
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "mobile-1".to_owned(),
            device_name: Some("Phone".to_owned()),
            network_tier: NetworkTier::Wifi,
            weight: Some(8.0),
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "mobile-1".to_owned(),
            device_name: Some("Phone".to_owned()),
            network_tier: NetworkTier::Cellular,
            weight: None,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let capped_w = db.sync().get_peer_weight("mobile-1").await.unwrap().unwrap();
    assert!(
        capped_w <= crate::db::sync::hrw::MAX_CELLULAR_PEER_WEIGHT,
        "Cellular switch must cap prior Wifi weight"
    );

    // 5. Referential integrity under tight `page_budget`: child rows referencing future entries
    //    must trigger cutoff shrinking rather than omitting referenced entries!
    let list = db
        .catalog()
        .create_manual_collection(
            "Budget List",
            Some("budget-list"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let mut member_ids = Vec::new();
    for i in 0..4 {
        let e = db
            .catalog()
            .upsert_entry(&crate::db::UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: format!("Budget Book {i} {}", "x".repeat(400)),
                summary: Some("s".repeat(400)),
                ..crate::db::UpsertEntryInput::default()
            })
            .await
            .unwrap();
        db.catalog().add_collection_entry(&list.id, &e.id, None).await.unwrap();
        member_ids.push(e.id);
    }
    // Bump the entries' sync_seq past their collection_entries so they become future-referenced!
    for id in &member_ids {
        sqlx::query("UPDATE entries SET title = title || '!' WHERE id = ?")
            .bind(id)
            .execute(db.pool())
            .await
            .unwrap();
    }
    let tight_page = db
        .sync()
        .collect_peer_sync_response_with_budget(
            0,
            200,
            2_048,
            HlcTimestamp::from_datetime(now, SERVER_PEER_ID).encode(),
            now,
        )
        .await
        .unwrap();
    let returned_entry_ids: std::collections::HashSet<&str> =
        tight_page.entries.iter().map(|e| e.id.as_str()).collect();
    for ce in &tight_page.collection_entries {
        if member_ids.contains(&ce.entry_id) {
            assert!(
                returned_entry_ids.contains(ce.entry_id.as_str()),
                "every emitted collection_entry must have its future-referenced entry included"
            );
        }
    }
    assert!(tight_page.has_more, "tight budget must paginate via cutoff shrinking");
}

#[tokio::test]
async fn test_incremental_generator_delta_bundles_drawn_entry_and_membership() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();

    let list = db
        .catalog()
        .create_manual_collection(
            "1001 Albums",
            Some("1001_albums"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let album1 = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Devotional Songs".to_owned(),
            creators: vec!["Nusrat Fateh Ali Khan".to_owned()],
            ..Default::default()
        })
        .await
        .unwrap();
    let album2 = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Remain in Light".to_owned(),
            creators: vec!["Talking Heads".to_owned()],
            ..Default::default()
        })
        .await
        .unwrap();
    db.catalog().add_collection_entry(&list.id, &album1.id, None).await.unwrap();
    db.catalog().add_collection_entry(&list.id, &album2.id, None).await.unwrap();
    db.catalog()
        .update_collection_generator(&list.id, Some(true), Some(&album1.id), Some(now))
        .await
        .unwrap();

    // Record cursor AFTER both albums and memberships were synced.
    let cursor_before_finish: i64 =
        sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
            .fetch_one(db.pool())
            .await
            .unwrap();

    // Finishing album1 advances the generator to album2 without modifying album2 or its membership.
    let (_, next_gen) =
        db.catalog().finish_active_generator_entry(&list.id, Some("Loved it")).await.unwrap();
    assert_eq!(next_gen.current_entry.as_ref().map(|e| e.id.as_str()), Some(album2.id.as_str()));

    let (resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "android-peer".to_owned(),
            since_seq: cursor_before_finish,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert!(
        resp.collection_generators.iter().any(|g| {
            g.collection_id == list.id && g.current_entry_id.as_deref() == Some(album2.id.as_str())
        }),
        "incremental sync must emit the updated generator draw"
    );
    assert!(
        resp.entries.iter().any(|e| e.id == album2.id),
        "incremental sync must bundle the drawn entry even when its sync_seq <= since_seq"
    );
    assert!(
        resp.collection_entries
            .iter()
            .any(|ce| ce.collection_id == list.id && ce.entry_id == album2.id),
        "incremental sync must bundle the drawn entry's collection membership"
    );
}

#[tokio::test]
async fn test_comparison_tombstone_sync_deletes_and_blocks_stale_resurrection() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();

    let a = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Comparison Alpha".to_owned(),
            ..Default::default()
        })
        .await
        .unwrap();
    let b = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Comparison Beta".to_owned(),
            ..Default::default()
        })
        .await
        .unwrap();

    let comp = db
        .catalog()
        .append_comparison(None, &[vec![a.id.clone()], vec![b.id.clone()]], None)
        .await
        .unwrap();
    let deleted = db.catalog().delete_comparison(&comp.id).await.unwrap();
    assert!(deleted);

    let (resp, _) = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "android-peer".to_owned(),
            since_seq: 0,
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert!(
        resp.tombstones
            .iter()
            .any(|t| t.entity_type == ActivityEntityType::Comparison && t.natural_key == comp.id),
        "deleted comparison must emit a comparison sync tombstone"
    );

    // A stale incoming comparison from another peer must not resurrect the deleted row.
    let stale_hlc =
        HlcTimestamp { wall_ms: 1_000_000_000_000, counter: 0, node_id: "peer".into() }.to_string();
    let _ = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "stale-peer".to_owned(),
            since_seq: 0,
            comparisons: vec![crate::db::SyncComparisonRecord {
                id: comp.id.clone(),
                ts: now,
                scope_collection_id: None,
                ordering: vec![vec![a.id.clone()], vec![b.id.clone()]],
                created_at: Some(now),
                hlc: stale_hlc,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let remaining: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM comparisons WHERE id = ?")
        .bind(&comp.id)
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert_eq!(remaining, 0, "tombstone must suppress stale comparison resurrection");
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_rust_hlc_causal_monotonicity_under_peer_clock_skew() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();
    // Simulate a peer whose local wall clock is 5 minutes ahead of the server (within MAX_CLOCK_DRIFT_MS).
    let skewed_future = now + chrono::Duration::minutes(5);
    let skewed_hlc = HlcTimestamp::from_datetime(skewed_future, "android-skewed").encode();

    let feed_url = "https://skewed.example.com/rss.xml";
    let feed_id = deterministic_feed_id(feed_url);
    let item_url = "https://skewed.example.com/post-1";
    let item_id = deterministic_item_id(Some(feed_url), item_url);

    // 1. Skewed peer pushes a feed + unread item with +5m HLCs.
    let _ = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "android-skewed".to_owned(),
            client_hlc: Some(skewed_hlc.clone()),
            since_seq: 0,
            entries: vec![
                SyncEntryRecord {
                    id: feed_id.clone(),
                    kind: EntryKind::Publication,
                    title: "Skewed Feed".to_owned(),
                    creators: Vec::new(),
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(skewed_future),
                    hlc: skewed_hlc.clone(),
                    identifiers: Vec::new(),
                    content: None,
                    state: None,
                    collection_spec: Some(SyncCollectionSpec {
                        slug: None,
                        mode: CollectionMode::Feed,
                        role: CollectionRole::Feed,
                        rule_json: None,
                        show_in_lists: false,
                        color: None,
                    }),
                    feed_source: Some(SyncFeedSource {
                        feed_url: feed_url.to_owned(),
                        source_type: FeedType::Rss,
                        feed_title: Some("Skewed Feed".to_owned()),
                        language: None,
                        poll_interval: 30,
                        is_active: true,
                        last_polled_at: None,
                        last_polled_by: None,
                        last_error: None,
                        last_error_by: None,
                        consecutive_failures: 0,
                        hlc: skewed_hlc.clone(),
                    }),
                },
                SyncEntryRecord {
                    id: item_id.clone(),
                    kind: EntryKind::Article,
                    title: "Skewed Post".to_owned(),
                    creators: Vec::new(),
                    url: Some(item_url.to_owned()),
                    published_at: Some(now),
                    release_year: None,
                    summary: Some("Initial summary".to_owned()),
                    thumbnail_url: None,
                    metadata: None,
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(skewed_future),
                    hlc: skewed_hlc.clone(),
                    identifiers: Vec::new(),
                    content: Some(SyncEntryContent {
                        content_html: Some("<p>Body</p>".to_owned()),
                        content_type: ContentType::Article,
                        audio_url: None,
                        duration: None,
                    }),
                    state: Some(SyncEntryState {
                        inbox_state: InboxState::Unread,
                        read_at: None,
                        progress: 0.0,
                        interaction_updated_at: Some(skewed_future),
                        hlc: skewed_hlc.clone(),
                    }),
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: feed_id.clone(),
                entry_id: item_id.clone(),
                position: None,
                added_at: now,
                hlc: skewed_hlc.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    // 2. Server locally updates feed title and deletes the item using normal wall clock (`now`).
    assert!(
        db.feeds().update(&feed_id, Some("Server Renamed Feed"), None, None, None).await.unwrap()
    );
    assert!(db.items().delete(&item_id).await.unwrap());

    let local_feed_hlc: String =
        sqlx::query_scalar("SELECT hlc FROM feed_sources WHERE collection_id = ?")
            .bind(&feed_id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert!(
        local_feed_hlc > skewed_hlc,
        "local feed config HLC ({local_feed_hlc}) must strictly dominate +5m peer HLC ({skewed_hlc})"
    );

    let item_tomb_key = item_natural_key(Some(feed_url), item_url);
    let local_tomb_hlc: String = sqlx::query_scalar(
        "SELECT hlc FROM sync_tombstones WHERE entity_type = 'item' AND natural_key = ?",
    )
    .bind(&item_tomb_key)
    .fetch_one(db.pool())
    .await
    .unwrap();
    assert!(
        local_tomb_hlc > skewed_hlc,
        "local item tombstone HLC ({local_tomb_hlc}) must strictly dominate +5m peer HLC ({skewed_hlc})"
    );

    // 3. Replaying the skewed peer's stale feed title + deleted item must NOT resurrect or overwrite.
    let _ = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "android-skewed".to_owned(),
            since_seq: 0,
            entries: vec![
                SyncEntryRecord {
                    id: feed_id.clone(),
                    kind: EntryKind::Publication,
                    title: "Skewed Feed".to_owned(),
                    creators: Vec::new(),
                    url: None,
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(skewed_future),
                    hlc: skewed_hlc.clone(),
                    identifiers: Vec::new(),
                    content: None,
                    state: None,
                    collection_spec: Some(SyncCollectionSpec {
                        slug: None,
                        mode: CollectionMode::Feed,
                        role: CollectionRole::Feed,
                        rule_json: None,
                        show_in_lists: false,
                        color: None,
                    }),
                    feed_source: Some(SyncFeedSource {
                        feed_url: feed_url.to_owned(),
                        source_type: FeedType::Rss,
                        feed_title: Some("Skewed Feed".to_owned()),
                        language: None,
                        poll_interval: 30,
                        is_active: true,
                        last_polled_at: None,
                        last_polled_by: None,
                        last_error: None,
                        last_error_by: None,
                        consecutive_failures: 0,
                        hlc: skewed_hlc.clone(),
                    }),
                },
                SyncEntryRecord {
                    id: item_id.clone(),
                    kind: EntryKind::Article,
                    title: "Skewed Post".to_owned(),
                    creators: Vec::new(),
                    url: Some(item_url.to_owned()),
                    published_at: Some(now),
                    release_year: None,
                    summary: Some("Initial summary".to_owned()),
                    thumbnail_url: None,
                    metadata: None,
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(skewed_future),
                    hlc: skewed_hlc.clone(),
                    identifiers: Vec::new(),
                    content: Some(SyncEntryContent {
                        content_html: Some("<p>Body</p>".to_owned()),
                        content_type: ContentType::Article,
                        audio_url: None,
                        duration: None,
                    }),
                    state: Some(SyncEntryState {
                        inbox_state: InboxState::Unread,
                        read_at: None,
                        progress: 0.0,
                        interaction_updated_at: Some(skewed_future),
                        hlc: skewed_hlc.clone(),
                    }),
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert_eq!(
        db.feeds().get(&feed_id).await.unwrap().unwrap().title.as_deref(),
        Some("Server Renamed Feed"),
        "stale skewed feed config must not overwrite server's causally newer rename"
    );
    assert!(
        db.items().get(&item_id).await.unwrap().is_none(),
        "stale skewed item must not resurrect after server local delete"
    );
}

#[tokio::test]
async fn test_migration_019_to_020_backfills_last_hlc_and_removes_julianday_triggers() {
    let temp_dir = std::env::temp_dir().join(format!("roche-mig020-test-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&temp_dir).await.unwrap();
    let db_path = temp_dir.join("roche.db");

    // 1. Apply migrations 001..=019 only and seed a row with a high HLC while `sync_clock.last_hlc` is empty.
    let pre_opts = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&db_path)
        .create_if_missing(true)
        .foreign_keys(true);
    let pre_pool = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(pre_opts)
        .await
        .unwrap();
    let mut migrator_v19 = sqlx::migrate!("./migrations");
    migrator_v19.migrations.to_mut().retain(|m| m.version <= 19);
    migrator_v19.run(&pre_pool).await.unwrap();

    let high_hlc = "001999999999999-000a-legacy-peer";
    sqlx::query(
        "INSERT INTO entries (id, kind, title, created_at, updated_at, hlc)
         VALUES ('pre-020-entry', 'book', 'Legacy Book', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', ?)",
    )
    .bind(high_hlc)
    .execute(&pre_pool)
    .await
    .unwrap();

    let last_hlc_before: String =
        sqlx::query_scalar("SELECT last_hlc FROM sync_clock WHERE id = 1")
            .fetch_one(&pre_pool)
            .await
            .unwrap();
    assert!(last_hlc_before.is_empty(), "pre-020 local insert left sync_clock.last_hlc empty");
    pre_pool.close().await;

    // 2. Connect via `Db::connect` (applying migration 020).
    let db = Db::connect(&db_path).await.unwrap();

    let last_hlc_after: String = sqlx::query_scalar("SELECT last_hlc FROM sync_clock WHERE id = 1")
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert_eq!(
        last_hlc_after, high_hlc,
        "migration 020 must backfill sync_clock.last_hlc to the highest HLC across all HLC tables"
    );

    let julianday_triggers: Vec<String> = sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE type = 'trigger' AND sql LIKE '%julianday%'",
    )
    .fetch_all(db.pool())
    .await
    .unwrap();
    assert!(
        julianday_triggers.is_empty(),
        "migration 020 must remove all julianday HLC triggers, found: {julianday_triggers:?}"
    );

    let has_comp_au: Option<String> = sqlx::query_scalar(
        "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name = 'comparisons_sync_seq_au'",
    )
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(has_comp_au.is_some(), "migration 020 must install comparisons_sync_seq_au");

    db.pool().close().await;
    drop(db);
    let _ = tokio::fs::remove_dir_all(&temp_dir).await;
}

#[tokio::test]
async fn test_merge_entries_orders_generator_hlc_above_tombstone_without_wall_clock_bump() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let canon_col = catalog
        .create_manual_collection(
            "Canonical List",
            Some("canon_list"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let dup_col = catalog
        .create_manual_collection(
            "Duplicate List",
            Some("dup_list"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let album = catalog
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Kind of Blue".to_owned(),
            creators: vec!["Miles Davis".to_owned()],
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.add_collection_entry(&canon_col.id, &album.id, None).await.unwrap();
    catalog.add_collection_entry(&dup_col.id, &album.id, None).await.unwrap();
    catalog.set_collection_generator_enabled(&canon_col.id, true).await.unwrap();
    catalog.set_collection_generator_enabled(&dup_col.id, true).await.unwrap();

    catalog.merge_entries(&dup_col.id, &canon_col.id).await.unwrap();

    let (tomb_deleted_at, tomb_hlc): (DateTime<Utc>, String) = sqlx::query_as(
        "SELECT deleted_at, hlc FROM sync_tombstones
         WHERE entity_type = 'collection_generator' AND natural_key = ?",
    )
    .bind(&dup_col.id)
    .fetch_one(db.pool())
    .await
    .unwrap();
    let (canon_updated_at, canon_hlc): (DateTime<Utc>, String) =
        sqlx::query_as("SELECT updated_at, hlc FROM collection_generators WHERE collection_id = ?")
            .bind(&canon_col.id)
            .fetch_one(db.pool())
            .await
            .unwrap();

    assert!(
        canon_hlc > tomb_hlc,
        "canonical generator HLC ({canon_hlc}) must strictly exceed duplicate generator tombstone HLC ({tomb_hlc})"
    );
    assert_eq!(
        canon_updated_at, tomb_deleted_at,
        "causal HLC counter increment must preserve exact updated_at without +1ms wall-clock hack"
    );
}

#[tokio::test]
async fn test_all_local_repo_writes_stamp_valid_causal_hlcs_and_single_insert_merge_in() {
    let db = Db::connect("sqlite::memory:").await.unwrap();

    // Exercise local writes across all 8 HLC tables.
    let feed = db
        .feeds()
        .create(
            FeedType::Rss,
            &Url::parse("https://hlc.example.com/feed.xml").unwrap(),
            Some("HLC Feed"),
            None,
        )
        .await
        .unwrap();
    let (item, tags) = db
        .items()
        .save_manual_input_with_tags(&ManualItemInput {
            url: &Url::parse("https://hlc.example.com/post").unwrap(),
            title: "HLC Post",
            summary: Some("Summary"),
            content_html: Some("<p>Body</p>"),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &["hlc-tag".to_owned()],
        })
        .await
        .unwrap();
    assert_eq!(tags.len(), 1);
    db.items().toggle_star(&item.id).await.unwrap();

    let col = db
        .catalog()
        .create_manual_collection("HLC Queue", Some("hlc_queue"), CollectionRole::List, None, None)
        .await
        .unwrap();
    let a = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book A".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let b = db
        .catalog()
        .upsert_entry(&crate::db::UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book B".to_owned(),
            ..crate::db::UpsertEntryInput::default()
        })
        .await
        .unwrap();
    db.catalog().add_collection_entry(&col.id, &a.id, None).await.unwrap();
    db.catalog().add_collection_entry(&col.id, &b.id, None).await.unwrap();
    db.catalog().set_collection_generator_enabled(&col.id, true).await.unwrap();
    let log = db
        .catalog()
        .log_consumption(&a.id, ConsumptionStatus::Consumed, Some("Great"), None)
        .await
        .unwrap();
    db.catalog().update_consumption_log(&log.id, None, Some(Some("Updated")), None).await.unwrap();
    let comp = db
        .catalog()
        .append_comparison(None, &[vec![a.id.clone()], vec![b.id.clone()]], None)
        .await
        .unwrap();
    assert!(db.catalog().delete_comparison(&comp.id).await.unwrap());
    let _ = db
        .catalog()
        .append_comparison(None, &[vec![b.id.clone()], vec![a.id.clone()]], None)
        .await
        .unwrap();
    assert!(db.feeds().update(&feed.id, Some("HLC Feed 2"), Some(45), None, None).await.unwrap());

    let last_hlc: String = sqlx::query_scalar("SELECT last_hlc FROM sync_clock WHERE id = 1")
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert!(HlcTimestamp::parse(&last_hlc).is_some(), "sync_clock.last_hlc must be valid HLC");

    for (table, query) in [
        ("entries", "SELECT hlc FROM entries"),
        ("entry_state", "SELECT hlc FROM entry_state"),
        ("feed_sources", "SELECT hlc FROM feed_sources"),
        ("collection_entries", "SELECT hlc FROM collection_entries"),
        ("collection_generators", "SELECT hlc FROM collection_generators"),
        ("consumption_log", "SELECT hlc FROM consumption_log"),
        ("comparisons", "SELECT hlc FROM comparisons"),
        ("sync_tombstones", "SELECT hlc FROM sync_tombstones"),
    ] {
        let hlcs: Vec<String> = sqlx::query_scalar(query).fetch_all(db.pool()).await.unwrap();
        assert!(!hlcs.is_empty(), "expected at least one row in {table}");
        for h in hlcs {
            assert!(
                HlcTimestamp::parse(&h).is_some(),
                "table {table} contains invalid/empty HLC: '{h}'"
            );
            assert!(
                h <= last_hlc,
                "table {table} row HLC ({h}) must not exceed sync_clock.last_hlc ({last_hlc})"
            );
        }
    }

    // Verify `merge_in` inserts incoming work + consumption log with exact incoming HLC in one write
    // without post-insert double-writes.
    let now = Utc::now();
    let seq_before: i64 = sqlx::query_scalar("SELECT current_seq FROM sync_clock WHERE id = 1")
        .fetch_one(db.pool())
        .await
        .unwrap();
    let inc_hlc =
        HlcTimestamp::from_datetime(now - chrono::Duration::seconds(5), "peer-one").encode();
    let work_id = "single-insert-work-1";
    let log_id = "single-insert-log-1";
    let _ = db
        .sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "peer-one".to_owned(),
            since_seq: seq_before,
            entries: vec![SyncEntryRecord {
                id: work_id.to_owned(),
                kind: EntryKind::Movie,
                title: "Stalker".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                url: None,
                published_at: None,
                release_year: Some(1979),
                summary: None,
                thumbnail_url: None,
                metadata: None,
                merged_into_id: None,
                created_at: Some(now - chrono::Duration::seconds(10)),
                updated_at: Some(now - chrono::Duration::seconds(5)),
                hlc: inc_hlc.clone(),
                identifiers: Vec::new(),
                content: None,
                state: None,
                collection_spec: None,
                feed_source: None,
            }],
            consumption_log: vec![SyncConsumptionLogRecord {
                id: log_id.to_owned(),
                entry_id: work_id.to_owned(),
                ts: now - chrono::Duration::seconds(5),
                status: ConsumptionStatus::Consumed,
                notes: Some("Masterpiece".to_owned()),
                created_at: Some(now - chrono::Duration::seconds(5)),
                hlc: inc_hlc.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let (stored_work_hlc, stored_work_seq): (String, i64) =
        sqlx::query_as("SELECT hlc, sync_seq FROM entries WHERE id = ?")
            .bind(work_id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    let (stored_log_hlc, stored_log_seq): (String, i64) =
        sqlx::query_as("SELECT hlc, sync_seq FROM consumption_log WHERE id = ?")
            .bind(log_id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert_eq!(stored_work_hlc, inc_hlc);
    assert_eq!(stored_log_hlc, inc_hlc);
    assert_eq!(stored_work_seq, seq_before + 1, "entry insert must bump sync_seq exactly once");
    assert_eq!(
        stored_log_seq,
        seq_before + 2,
        "consumption_log insert must bump sync_seq exactly once"
    );
}

#[tokio::test]
async fn test_sync_never_auto_upgrades_rss_to_podcast_and_reverts_via_newer_hlc() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let feed_url = Url::parse("https://newsletter.example.com/feed").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Newsletter"), None).await.unwrap();

    let ep_url = Url::parse("https://newsletter.example.com/p/audio-post").unwrap();
    let ep_id = deterministic_item_id(Some(feed_url.as_str()), ep_url.as_str());
    let now = crate::util::clock::now_utc();
    let hlc_t1 =
        HlcTimestamp::from_datetime(now + chrono::Duration::seconds(10), "phone-1").encode();
    let hlc_t2 =
        HlcTimestamp::from_datetime(now + chrono::Duration::seconds(20), "phone-1").encode();
    let hlc_t3 =
        HlcTimestamp::from_datetime(now + chrono::Duration::seconds(30), "phone-1").encode();

    // 1. Syncing an audio entry into an RSS feed must NOT auto-upgrade the feed to Podcast.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            device_name: Some("Android".to_owned()),
            network_tier: NetworkTier::Wifi,
            entries: vec![SyncEntryRecord {
                id: ep_id.clone(),
                kind: EntryKind::PodcastEpisode,
                title: "Audio Post".to_owned(),
                creators: Vec::new(),
                url: Some(ep_url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: hlc_t1.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Audio,
                    content_html: Some("<p>Show notes</p>".to_owned()),
                    audio_url: Some("https://cdn.example.com/audio.mp3".to_owned()),
                    duration: Some(600),
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::Unread,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: Some(now),
                    hlc: hlc_t1.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            }],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: feed.id.clone(),
                entry_id: ep_id.clone(),
                position: None,
                added_at: now,
                hlc: hlc_t1.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert_eq!(db.feeds().get(&feed.id).await.unwrap().unwrap().feed_type, FeedType::Rss);
    assert_eq!(
        db.catalog().get_entry(&feed.id).await.unwrap().unwrap().kind,
        EntryKind::Publication
    );

    // 2. Newer HLC can explicitly switch feed Podcast -> RSS and item PodcastEpisode -> Article,
    //    clearing remote-only audio enclosure, while an older HLC cannot revert it back.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            device_name: Some("Android".to_owned()),
            network_tier: NetworkTier::Wifi,
            entries: vec![SyncEntryRecord {
                id: feed.id.clone(),
                kind: EntryKind::Podcast,
                title: "Newsletter".to_owned(),
                creators: Vec::new(),
                url: Some(feed_url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: hlc_t2.clone(),
                content: None,
                state: None,
                collection_spec: None,
                feed_source: Some(SyncFeedSource {
                    feed_url: feed_url.to_string(),
                    source_type: FeedType::Podcast,
                    feed_title: Some("Newsletter".to_owned()),
                    language: None,
                    poll_interval: 30,
                    is_active: true,
                    last_polled_at: None,
                    last_polled_by: None,
                    last_error: None,
                    last_error_by: None,
                    consecutive_failures: 0,
                    hlc: hlc_t2.clone(),
                }),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    assert_eq!(db.feeds().get(&feed.id).await.unwrap().unwrap().feed_type, FeedType::Podcast);

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            device_name: Some("Android".to_owned()),
            network_tier: NetworkTier::Wifi,
            entries: vec![
                SyncEntryRecord {
                    id: feed.id.clone(),
                    kind: EntryKind::Publication,
                    title: "Newsletter".to_owned(),
                    creators: Vec::new(),
                    url: Some(feed_url.to_string()),
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(now),
                    hlc: hlc_t3.clone(),
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: Some(SyncFeedSource {
                        feed_url: feed_url.to_string(),
                        source_type: FeedType::Rss,
                        feed_title: Some("Newsletter".to_owned()),
                        language: None,
                        poll_interval: 30,
                        is_active: true,
                        last_polled_at: None,
                        last_polled_by: None,
                        last_error: None,
                        last_error_by: None,
                        consecutive_failures: 0,
                        hlc: hlc_t3.clone(),
                    }),
                },
                SyncEntryRecord {
                    id: ep_id.clone(),
                    kind: EntryKind::Article,
                    title: "Audio Post".to_owned(),
                    creators: Vec::new(),
                    url: Some(ep_url.to_string()),
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(now),
                    hlc: hlc_t3.clone(),
                    content: Some(SyncEntryContent {
                        content_type: ContentType::Article,
                        content_html: Some("<p>Show notes</p>".to_owned()),
                        audio_url: None,
                        duration: None,
                    }),
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert_eq!(db.feeds().get(&feed.id).await.unwrap().unwrap().feed_type, FeedType::Rss);
    assert_eq!(
        db.catalog().get_entry(&feed.id).await.unwrap().unwrap().kind,
        EntryKind::Publication
    );
    let reverted_item = db.items().get(&ep_id).await.unwrap().unwrap();
    assert_eq!(reverted_item.content_type, ContentType::Article);
    assert!(reverted_item.audio_url.is_none());
    assert_eq!(db.catalog().get_entry(&ep_id).await.unwrap().unwrap().kind, EntryKind::Article);

    // 3. Replaying the stale t1/t2 Podcast & Audio payload must NOT re-corrupt the repaired rows.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-old".to_owned(),
            device_name: Some("Old Phone".to_owned()),
            network_tier: NetworkTier::Wifi,
            entries: vec![
                SyncEntryRecord {
                    id: feed.id.clone(),
                    kind: EntryKind::Podcast,
                    title: "Newsletter".to_owned(),
                    creators: Vec::new(),
                    url: Some(feed_url.to_string()),
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(now),
                    hlc: hlc_t2.clone(),
                    content: None,
                    state: None,
                    collection_spec: None,
                    feed_source: Some(SyncFeedSource {
                        feed_url: feed_url.to_string(),
                        source_type: FeedType::Podcast,
                        feed_title: Some("Newsletter".to_owned()),
                        language: None,
                        poll_interval: 30,
                        is_active: true,
                        last_polled_at: None,
                        last_polled_by: None,
                        last_error: None,
                        last_error_by: None,
                        consecutive_failures: 0,
                        hlc: hlc_t2,
                    }),
                },
                SyncEntryRecord {
                    id: ep_id.clone(),
                    kind: EntryKind::PodcastEpisode,
                    title: "Audio Post".to_owned(),
                    creators: Vec::new(),
                    url: Some(ep_url.to_string()),
                    published_at: None,
                    release_year: None,
                    summary: None,
                    thumbnail_url: None,
                    metadata: None,
                    identifiers: Vec::new(),
                    merged_into_id: None,
                    created_at: Some(now),
                    updated_at: Some(now),
                    hlc: hlc_t1,
                    content: Some(SyncEntryContent {
                        content_type: ContentType::Audio,
                        content_html: Some("<p>Show notes</p>".to_owned()),
                        audio_url: Some(ep_url.to_string()),
                        duration: None,
                    }),
                    state: None,
                    collection_spec: None,
                    feed_source: None,
                },
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert_eq!(db.feeds().get(&feed.id).await.unwrap().unwrap().feed_type, FeedType::Rss);
    let still_article = db.items().get(&ep_id).await.unwrap().unwrap();
    assert_eq!(still_article.content_type, ContentType::Article);
    assert!(still_article.audio_url.is_none());
    assert_eq!(db.catalog().get_entry(&ep_id).await.unwrap().unwrap().kind, EntryKind::Article);
}

#[tokio::test]
#[allow(clippy::too_many_lines)]
async fn test_sync_audio_reclassification_duration_paper_video_and_feed_split_brain() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();

    let feed_url = Url::parse("https://notes.example.com/feed").unwrap();
    let feed = db.feeds().create(FeedType::Podcast, &feed_url, Some("Notes"), None).await.unwrap();

    // Seed feed with newer entry HLC (e.g. from a title rename) than feed_sources HLC
    let hlc_t1 = "001794000000000-0001-server".to_owned();
    let hlc_t2 = "001795000000000-0001-server".to_owned();
    let hlc_t3 = "001796000000000-0001-phone".to_owned();
    let hlc_t4 = "001797000000000-0001-phone".to_owned();
    let hlc_t5 = "001798000000000-0001-phone".to_owned();

    sqlx::query("UPDATE feed_sources SET hlc = ? WHERE collection_id = ?")
        .bind(&hlc_t1)
        .bind(&feed.id)
        .execute(db.pool())
        .await
        .unwrap();
    sqlx::query("UPDATE entries SET hlc = ? WHERE id = ?")
        .bind(&hlc_t4)
        .bind(&feed.id)
        .execute(db.pool())
        .await
        .unwrap();

    // 1. Incoming feed_source HLC (t3 > t1) wins while incoming entry HLC (t2 < t4) loses:
    //    source_type reverts Podcast -> RSS AND entries.kind MUST align to Publication (no split-brain),
    //    keeping entries.hlc >= max(t4, t3) = t4.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            device_name: Some("Android".to_owned()),
            network_tier: NetworkTier::Wifi,
            entries: vec![SyncEntryRecord {
                id: feed.id.clone(),
                kind: EntryKind::Publication,
                title: "Stale Title".to_owned(),
                creators: Vec::new(),
                url: Some(feed_url.to_string()),
                published_at: None,
                release_year: None,
                summary: None,
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: hlc_t2.clone(),
                content: None,
                state: None,
                collection_spec: None,
                feed_source: Some(SyncFeedSource {
                    feed_url: feed_url.to_string(),
                    source_type: FeedType::Rss,
                    feed_title: Some("Notes".to_owned()),
                    language: None,
                    poll_interval: 30,
                    is_active: true,
                    last_polled_at: None,
                    last_polled_by: None,
                    last_error: None,
                    last_error_by: None,
                    consecutive_failures: 0,
                    hlc: hlc_t3.clone(),
                }),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    assert_eq!(db.feeds().get(&feed.id).await.unwrap().unwrap().feed_type, FeedType::Rss);
    let aligned_feed_entry = db.catalog().get_entry(&feed.id).await.unwrap().unwrap();
    assert_eq!(aligned_feed_entry.kind, EntryKind::Publication);
    let stored_feed_entry_hlc: String = sqlx::query_scalar("SELECT hlc FROM entries WHERE id = ?")
        .bind(&feed.id)
        .fetch_one(db.pool())
        .await
        .unwrap();
    assert!(stored_feed_entry_hlc >= hlc_t3);

    // 2. Syncing a repaired Article (even with stray leftover duration) onto a bogus Audio item
    //    deletes bogus entry_media and does NOT insert a duration-only audio row;
    //    syncing PodcastEpisode -> Paper and PodcastEpisode -> Video updates entries.kind;
    //    syncing a Video with duration does NOT insert a duration-only audio media row;
    //    legitimately upgrading Article -> PodcastEpisode with real .mp3 via newer HLC still works.
    let art_id = deterministic_item_id(Some(feed_url.as_str()), "https://notes.example.com/post-1");
    let paper_id =
        deterministic_item_id(Some(feed_url.as_str()), "https://arxiv.org/pdf/2501.00001.pdf");
    let video_id = deterministic_item_id(
        Some(feed_url.as_str()),
        "https://www.youtube.com/watch?v=abc123def45",
    );

    let make_item = |id: &str,
                     kind: EntryKind,
                     url: &str,
                     hlc: &str,
                     ct: ContentType,
                     audio_url: Option<&str>,
                     duration: Option<i64>| SyncEntryRecord {
        id: id.to_owned(),
        kind,
        title: id.to_owned(),
        creators: Vec::new(),
        url: Some(url.to_owned()),
        published_at: None,
        release_year: None,
        summary: None,
        thumbnail_url: None,
        metadata: None,
        identifiers: Vec::new(),
        merged_into_id: None,
        created_at: Some(now),
        updated_at: Some(now),
        hlc: hlc.to_owned(),
        content: Some(SyncEntryContent {
            content_type: ct,
            content_html: Some("<p>Body</p>".to_owned()),
            audio_url: audio_url.map(str::to_owned),
            duration,
        }),
        state: None,
        collection_spec: None,
        feed_source: None,
    };

    // Initial misclassified state at t1
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![
                make_item(
                    &art_id,
                    EntryKind::PodcastEpisode,
                    "https://notes.example.com/post-1",
                    &hlc_t1,
                    ContentType::Audio,
                    Some("https://notes.example.com/post-1"),
                    Some(600),
                ),
                make_item(
                    &paper_id,
                    EntryKind::PodcastEpisode,
                    "https://arxiv.org/pdf/2501.00001.pdf",
                    &hlc_t1,
                    ContentType::Audio,
                    Some("https://arxiv.org/pdf/2501.00001.pdf"),
                    None,
                ),
                make_item(
                    &video_id,
                    EntryKind::PodcastEpisode,
                    "https://www.youtube.com/watch?v=abc123def45",
                    &hlc_t1,
                    ContentType::Audio,
                    Some("https://www.youtube.com/watch?v=abc123def45"),
                    None,
                ),
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    // Repaired state at t3 (Article carries stray leftover duration=600; Video carries duration=300)
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![
                make_item(
                    &art_id,
                    EntryKind::Article,
                    "https://notes.example.com/post-1",
                    &hlc_t3,
                    ContentType::Article,
                    None,
                    Some(600),
                ),
                make_item(
                    &paper_id,
                    EntryKind::Paper,
                    "https://arxiv.org/pdf/2501.00001.pdf",
                    &hlc_t3,
                    ContentType::Paper,
                    None,
                    None,
                ),
                make_item(
                    &video_id,
                    EntryKind::Video,
                    "https://www.youtube.com/watch?v=abc123def45",
                    &hlc_t3,
                    ContentType::Video,
                    None,
                    Some(300),
                ),
            ],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    for (id, expected_kind, expected_ct) in [
        (&art_id, EntryKind::Article, ContentType::Article),
        (&paper_id, EntryKind::Paper, ContentType::Paper),
        (&video_id, EntryKind::Video, ContentType::Video),
    ] {
        let entry = db.catalog().get_entry(id).await.unwrap().unwrap();
        assert_eq!(entry.kind, expected_kind, "kind mismatch for {id}");
        let item = db.items().get(id).await.unwrap().unwrap();
        assert_eq!(item.content_type, expected_ct, "content_type mismatch for {id}");
        assert!(item.audio_url.is_none(), "audio_url should be cleared for {id}");
        let audio_media_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM entry_media WHERE entry_id = ? AND media_type = 'audio'",
        )
        .bind(id)
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(audio_media_count, 0, "must not retain/create audio entry_media for {id}");
    }

    // Legitimately upgrading Article -> PodcastEpisode with real .mp3 enclosure at t5 still works
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![make_item(
                &art_id,
                EntryKind::PodcastEpisode,
                "https://notes.example.com/post-1",
                &hlc_t5,
                ContentType::Audio,
                Some("https://cdn.example.com/post-1.mp3"),
                Some(900),
            )],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let upgraded = db.items().get(&art_id).await.unwrap().unwrap();
    assert_eq!(upgraded.content_type, ContentType::Audio);
    assert_eq!(upgraded.audio_url.as_deref(), Some("https://cdn.example.com/post-1.mp3"));
    assert_eq!(upgraded.duration, Some(900));
    assert_eq!(
        db.catalog().get_entry(&art_id).await.unwrap().unwrap().kind,
        EntryKind::PodcastEpisode
    );
}

#[tokio::test]
async fn test_sync_narrated_article_preserves_article_kind_and_audio_enclosure() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let now = Utc::now();
    let feed_url = Url::parse("https://substack.example.com/feed").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Essayist"), None).await.unwrap();
    let item_url = "https://substack.example.com/p/read-aloud";
    let item_id = deterministic_item_id(Some(feed_url.as_str()), item_url);

    let hlc_t1 = "001794000000000-0001-phone".to_owned();
    let hlc_t2 = "001795000000000-0001-phone".to_owned();

    // 1. Syncing a narrated Article (kind=Article, content_type=Article, audio_url=Some(.mp3))
    //    MUST NOT upgrade it to PodcastEpisode/Audio and MUST preserve audio_url + duration.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![SyncEntryRecord {
                id: item_id.clone(),
                kind: EntryKind::Article,
                title: "Read Aloud Essay".to_owned(),
                creators: Vec::new(),
                url: Some(item_url.to_owned()),
                published_at: Some(now),
                release_year: None,
                summary: Some("Summary".to_owned()),
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: hlc_t1.clone(),
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>Essay prose</p>".to_owned()),
                    audio_url: Some("https://cdn.substack.example.com/essay.mp3".to_owned()),
                    duration: Some(1080),
                }),
                state: Some(SyncEntryState {
                    inbox_state: InboxState::Unread,
                    read_at: None,
                    progress: 0.0,
                    interaction_updated_at: Some(now),
                    hlc: hlc_t1.clone(),
                }),
                collection_spec: None,
                feed_source: None,
            }],
            collection_entries: vec![SyncCollectionEntryRecord {
                collection_id: feed.id.clone(),
                entry_id: item_id.clone(),
                position: None,
                added_at: now,
                hlc: hlc_t1.clone(),
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();

    let synced = db.items().get(&item_id).await.unwrap().unwrap();
    assert_eq!(synced.entry_kind, EntryKind::Article);
    assert_eq!(synced.content_type, ContentType::Article);
    assert_eq!(synced.audio_url.as_deref(), Some("https://cdn.substack.example.com/essay.mp3"));
    assert_eq!(synced.duration, Some(1080));

    // Verify outbound sync delta preserves all decoupled fields
    let out = db
        .sync()
        .collect_peer_sync_response_with_budget(0, 100, usize::MAX, String::new(), now)
        .await
        .unwrap();
    let wire = out.entries.iter().find(|e| e.id == item_id).unwrap();
    assert_eq!(wire.kind, EntryKind::Article);
    assert_eq!(wire.content.as_ref().unwrap().content_type, ContentType::Article);
    assert_eq!(
        wire.content.as_ref().unwrap().audio_url.as_deref(),
        Some("https://cdn.substack.example.com/essay.mp3")
    );
    assert_eq!(wire.content.as_ref().unwrap().duration, Some(1080));

    // 2. Newer HLC removing audio_url clears remote-only entry_media; replaying stale t1 cannot re-attach.
    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-1".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![SyncEntryRecord {
                id: item_id.clone(),
                kind: EntryKind::Article,
                title: "Read Aloud Essay".to_owned(),
                creators: Vec::new(),
                url: Some(item_url.to_owned()),
                published_at: Some(now),
                release_year: None,
                summary: Some("Summary".to_owned()),
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: hlc_t2,
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>Essay prose</p>".to_owned()),
                    audio_url: None,
                    duration: None,
                }),
                state: None,
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let cleared = db.items().get(&item_id).await.unwrap().unwrap();
    assert!(cleared.audio_url.is_none());
    assert!(cleared.duration.is_none());

    db.sync()
        .merge_peer_state(&PeerSyncRequest {
            peer_id: "phone-old".to_owned(),
            network_tier: NetworkTier::Wifi,
            entries: vec![SyncEntryRecord {
                id: item_id.clone(),
                kind: EntryKind::Article,
                title: "Read Aloud Essay".to_owned(),
                creators: Vec::new(),
                url: Some(item_url.to_owned()),
                published_at: Some(now),
                release_year: None,
                summary: Some("Summary".to_owned()),
                thumbnail_url: None,
                metadata: None,
                identifiers: Vec::new(),
                merged_into_id: None,
                created_at: Some(now),
                updated_at: Some(now),
                hlc: hlc_t1,
                content: Some(SyncEntryContent {
                    content_type: ContentType::Article,
                    content_html: Some("<p>Essay prose</p>".to_owned()),
                    audio_url: Some("https://cdn.substack.example.com/essay.mp3".to_owned()),
                    duration: Some(1080),
                }),
                state: None,
                collection_spec: None,
                feed_source: None,
            }],
            ..PeerSyncRequest::default()
        })
        .await
        .unwrap();
    let still_cleared = db.items().get(&item_id).await.unwrap().unwrap();
    assert!(still_cleared.audio_url.is_none());
}
