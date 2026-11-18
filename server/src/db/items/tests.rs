use chrono::{DateTime, Utc};
use url::Url;

use super::*;
use crate::db::{
    ActivityAction, ActivityEntityType, ArchiveBatchScope, ConsumptionStatus, ContentType, Db,
    FeedType, ItemStatus, MediaType, ReadAtPatch,
};

#[tokio::test]
async fn test_unique_constraints_for_feed_and_manual_items() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let items = db.items();

    let saved_url = Url::parse("https://example.com/saved").unwrap();
    let manual = NewItem {
        feed_id: None,
        title: "Saved Article",
        url: &saved_url,
        author: None,
        published_at: None,
        summary: None,
        content_type: ContentType::Article,
    };
    items.create(&manual).await.unwrap();
    assert!(items.create(&manual).await.is_err());

    let feed_url = Url::parse("https://example.com/feed").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, None, None).await.unwrap();
    let feed_item = NewItem {
        feed_id: Some(&feed.id),
        title: "Feed Article",
        url: &saved_url,
        author: None,
        published_at: None,
        summary: None,
        content_type: ContentType::Article,
    };
    items.create(&feed_item).await.unwrap();
    assert!(items.create(&feed_item).await.is_err());
}

#[tokio::test]
async fn test_manual_save_idempotency_and_media_orphan_retention_on_history_delete() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let items = db.items();

    let url = Url::parse("https://example.com/essay").unwrap();
    let (saved1, _) =
        items.save_manual_with_tags(&url, "Initial Title", &["rust".to_owned()]).await.unwrap();
    assert_eq!(saved1.status, ItemStatus::Unread);

    // Re-saving while still `Unread` updates title/tags idempotently instead of failing UNIQUE constraint
    let (saved2, _) =
        items.save_manual_with_tags(&url, "Refined Title", &["systems".to_owned()]).await.unwrap();
    assert_eq!(saved2.id, saved1.id);
    assert_eq!(saved2.title, "Refined Title");
    assert_eq!(saved2.tag_names.0, vec!["rust".to_owned(), "systems".to_owned()]);

    // Archiving then re-saving unarchives back to `Unread` cleanly
    items.archive(&saved1.id).await.unwrap();
    let (saved3, _) = items.save_manual_with_tags(&url, "Refined Title", &[]).await.unwrap();
    assert_eq!(saved3.id, saved1.id);
    assert_eq!(saved3.status, ItemStatus::Unread);

    // Attach local media + consumption history, then delete from Inbox:
    // `entry_media` row must survive until `MediaRepo::find_orphaned()` unlinks the disk file!
    db.media()
        .create(&saved1.id, MediaType::Thumbnail, "thumbnails/essay.jpg", Some(1024), None)
        .await
        .unwrap();
    db.catalog()
        .log_consumption(&saved1.id, ConsumptionStatus::Consumed, None, None)
        .await
        .unwrap();

    assert!(items.delete(&saved1.id).await.unwrap());
    assert!(items.get(&saved1.id).await.unwrap().is_none());
    let orphans = db.media().find_orphaned().await.unwrap();
    assert_eq!(orphans.len(), 1);
    assert_eq!(orphans[0].path, "thumbnails/essay.jpg");
}

#[tokio::test]
async fn test_fts5_search_stemming_phrases_ranking_and_trigger_sync() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let items = db.items();

    let feed_url = Url::parse("https://example.com/feed.xml").unwrap();
    let feed = db
        .feeds()
        .create(FeedType::Rss, &feed_url, Some("Systems Engineering Digest"), None)
        .await
        .unwrap();

    let url1 = Url::parse("https://example.com/1").unwrap();
    let (body_item, _) = items
        .save_manual_input_with_tags(&ManualItemInput {
            url: &url1,
            title: "Notes on Consensus",
            summary: Some("A brief overview of Raft and Paxos."),
            content_html: Some(
                "<p>Modern distributed systems rely on state machine replication and optimizing latency.</p>",
            ),
            thumbnail_url: None,
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();

    let url2 = Url::parse("https://example.com/2").unwrap();
    let title_item = items
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Distributed Systems in Practice",
            url: &url2,
            author: Some("Leslie Lamport"),
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();

    let results = items
        .list_filtered(&ItemFilter {
            search: Some("\"distributed systems\"".to_string()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(results.len(), 2);
    assert_eq!(results[0].id, title_item.id);
    assert_eq!(results[1].id, body_item.id);

    let stemmed = items
        .list_filtered(&ItemFilter { search: Some("optimize".to_string()), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(stemmed.len(), 1);
    assert_eq!(stemmed[0].id, body_item.id);

    let prefix = items
        .list_filtered(&ItemFilter { search: Some("lamp".to_string()), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(prefix.len(), 1);
    assert_eq!(prefix[0].id, title_item.id);

    let by_feed = items
        .list_filtered(&ItemFilter {
            search: Some("Engineering Digest".to_string()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(by_feed.len(), 1);
    assert_eq!(by_feed[0].id, title_item.id);

    items
        .backfill_metadata(&title_item.id, None, None, Some("Byzantine quorum verification"))
        .await
        .unwrap();
    let after_update = items
        .list_filtered(&ItemFilter { search: Some("byzantine".to_string()), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(after_update.len(), 1);
    assert_eq!(after_update[0].id, title_item.id);

    let special = items
        .list_filtered(&ItemFilter {
            search: Some("c++ \"unclosed AND OR NOT (foo:bar) -baz * ^".to_string()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert!(special.is_empty());

    db.feeds().delete(&feed.id).await.unwrap();
    let after_delete = items
        .list_filtered(&ItemFilter {
            search: Some("distributed".to_string()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(after_delete.len(), 1);
    assert_eq!(after_delete[0].id, body_item.id);
}

#[tokio::test]
async fn test_multi_token_search_across_tags_feed_title_and_backfill_activity() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let items = db.items();

    let feed_url = Url::parse("https://example.com/rust-weekly.xml").unwrap();
    let feed =
        db.feeds().create(FeedType::Rss, &feed_url, Some("Rust Weekly"), None).await.unwrap();

    let url1 = Url::parse("https://example.com/async-closures").unwrap();
    let feed_item = items
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Stabilizing Async Closures",
            url: &url1,
            author: None,
            published_at: None,
            summary: None,
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    db.tags().add_named_to_item(&feed_item.id, "llm-inference").await.unwrap();

    for query in ["llm-inference", "#llm-inference"] {
        let by_tag = items
            .list_filtered(&ItemFilter { search: Some(query.to_string()), ..Default::default() })
            .await
            .unwrap();
        assert_eq!(by_tag.len(), 1);
        assert_eq!(by_tag[0].id, feed_item.id);
    }

    let cross_feed_item = items
        .list_filtered(&ItemFilter { search: Some("rust async".to_string()), ..Default::default() })
        .await
        .unwrap();
    assert_eq!(cross_feed_item.len(), 1);
    assert_eq!(cross_feed_item[0].id, feed_item.id);

    let cross_tag_item = items
        .list_filtered(&ItemFilter {
            search: Some("#inference closures".to_string()),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(cross_tag_item.len(), 1);
    assert_eq!(cross_tag_item[0].id, feed_item.id);

    let before_id = db.sync().latest_activity_id().await.unwrap();
    items
        .backfill_metadata(&feed_item.id, Some("Updated Title"), Some("Niko"), Some("Deep dive"))
        .await
        .unwrap();
    let changes = db.sync().list_changes_since(before_id, 10).await.unwrap();
    assert_eq!(changes.len(), 1);
    assert_eq!(changes[0].entity_type, ActivityEntityType::Item);
    assert_eq!(changes[0].entity_id, feed_item.id);
    assert_eq!(changes[0].action, ActivityAction::Update);

    let after_first_backfill_id = db.sync().latest_activity_id().await.unwrap();
    items
        .backfill_metadata(&feed_item.id, Some("Updated Title"), Some("Niko"), Some("Deep dive"))
        .await
        .unwrap();
    let noop_changes = db.sync().list_changes_since(after_first_backfill_id, 10).await.unwrap();
    assert_eq!(noop_changes.len(), 0);
}

#[tokio::test]
async fn test_default_published_sort_summary_normalization_and_archive_batch() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let items = db.items();

    let feed_url = Url::parse("https://example.com/archive.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Archive"), None).await.unwrap();

    let older_pub =
        DateTime::parse_from_rfc3339("2013-04-21T08:00:00Z").unwrap().with_timezone(&Utc);
    let newer_pub =
        DateTime::parse_from_rfc3339("2026-11-06T22:00:00Z").unwrap().with_timezone(&Utc);

    let url_new = Url::parse("https://example.com/2026-post").unwrap();
    let item_new = items
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Yesterday Post",
            url: &url_new,
            author: None,
            published_at: Some(newer_pub),
            summary: Some("<figure><img src=\"https://example.com/a.png\"></figure><p>Clean &amp; fast summary</p>"),
            content_type: ContentType::Article,
        })
        .await
        .unwrap();

    let url_old = Url::parse("https://example.com/2013-post").unwrap();
    let item_old = items
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "2013 Post",
            url: &url_old,
            author: None,
            published_at: Some(older_pub),
            summary: Some("Old summary"),
            content_type: ContentType::Article,
        })
        .await
        .unwrap();

    let default_sorted = items.list_filtered(&ItemFilter::default()).await.unwrap();
    assert_eq!(default_sorted.len(), 2);
    assert_eq!(default_sorted[0].id, item_new.id);
    assert_eq!(default_sorted[1].id, item_old.id);

    let list_sorted = items.list(None, None, None).await.unwrap();
    assert_eq!(list_sorted[0].id, item_new.id);
    assert_eq!(list_sorted[1].id, item_old.id);

    let normalized_count = items.normalize_legacy_html_summaries().await.unwrap();
    assert_eq!(normalized_count, 1);
    let refreshed_new = items.get(&item_new.id).await.unwrap().unwrap();
    assert_eq!(refreshed_new.summary.as_deref(), Some("Clean & fast summary"));
    assert!(refreshed_new.content_html.as_deref().unwrap().contains("<figure>"));
    assert_eq!(refreshed_new.thumbnail_url.as_deref(), Some("https://example.com/a.png"));

    let url_angle = Url::parse("https://example.com/angle-post").unwrap();
    let item_angle = items
        .create(&NewItem {
            feed_id: Some(&feed.id),
            title: "Angle Post",
            url: &url_angle,
            author: None,
            published_at: None,
            summary: Some("<p>Use &lt;div&gt; or x &lt; y &gt; z</p>"),
            content_type: ContentType::Article,
        })
        .await
        .unwrap();
    assert_eq!(items.normalize_legacy_html_summaries().await.unwrap(), 1);
    let after_first = items.get(&item_angle.id).await.unwrap().unwrap();
    assert_eq!(after_first.summary.as_deref(), Some("Use <div> or x < y > z"));
    assert_eq!(items.normalize_legacy_html_summaries().await.unwrap(), 0);
    let after_second = items.get(&item_angle.id).await.unwrap().unwrap();
    assert_eq!(after_second.summary.as_deref(), Some("Use <div> or x < y > z"));

    let url_huge = Url::parse("https://example.com/huge-essay").unwrap();
    let huge_html = format!("<p>{}</p>", "a".repeat(70_000));
    let (huge_item, _) = items
        .save_manual_input_with_tags(&ManualItemInput {
            url: &url_huge,
            title: "Book-Length Essay",
            summary: Some("Short summary"),
            content_html: Some(&huge_html),
            thumbnail_url: Some("https://example.com/cover.jpg"),
            audio_url: None,
            duration: None,
            content_type: ContentType::Article,
            tags: &[],
        })
        .await
        .unwrap();
    let listed = items.list_filtered(&ItemFilter::default()).await.unwrap();
    let listed_huge = listed.iter().find(|i| i.id == huge_item.id).unwrap();
    assert!(listed_huge.content_html.is_none());
    assert_eq!(listed_huge.thumbnail_url.as_deref(), Some("https://example.com/cover.jpg"));
    let fetched_huge = items.get(&huge_item.id).await.unwrap().unwrap();
    assert_eq!(fetched_huge.content_html.as_deref(), Some(huge_html.as_str()));

    let metadata_only = items
        .list_filtered(&ItemFilter { include_content: Some(false), ..Default::default() })
        .await
        .unwrap();
    let meta_new = metadata_only.iter().find(|i| i.id == item_new.id).unwrap();
    assert!(meta_new.content_html.is_none());
    assert_eq!(meta_new.summary.as_deref(), Some("Clean & fast summary"));
    assert_eq!(meta_new.thumbnail_url.as_deref(), Some("https://example.com/a.png"));

    let custom_read_ts =
        DateTime::parse_from_rfc3339("2026-03-31T09:30:00Z").unwrap().with_timezone(&Utc);
    assert!(
        items
            .update(&item_new.id, None, None, None, ReadAtPatch::MarkRead(custom_read_ts))
            .await
            .unwrap()
    );
    let read_new = items.get(&item_new.id).await.unwrap().unwrap();
    assert_eq!(read_new.status, ItemStatus::Unread);
    assert_eq!(read_new.read_at, Some(custom_read_ts));
    // `count_unread` counts Feed Inbox unread items (`inbox_state = 'unread'`), excluding manual `want:read` items
    assert_eq!(items.count_unread().await.unwrap(), 3);

    let unread_only = items
        .list_filtered(&ItemFilter { is_read: Some(false), ..Default::default() })
        .await
        .unwrap();
    assert!(!unread_only.iter().any(|i| i.id == item_new.id));
    let read_only = items
        .list_filtered(&ItemFilter { is_read: Some(true), ..Default::default() })
        .await
        .unwrap();
    assert!(read_only.iter().any(|i| i.id == item_new.id));

    assert!(items.update(&item_new.id, None, None, None, ReadAtPatch::MarkUnread).await.unwrap());
    let unread_again = items.get(&item_new.id).await.unwrap().unwrap();
    assert!(unread_again.read_at.is_none());

    assert!(
        items
            .update(
                &item_new.id,
                Some(ItemStatus::Archived),
                None,
                None,
                ReadAtPatch::MarkRead(custom_read_ts),
            )
            .await
            .unwrap()
    );
    let archived_with_ts = items.get(&item_new.id).await.unwrap().unwrap();
    assert_eq!(archived_with_ts.status, ItemStatus::Archived);
    assert_eq!(archived_with_ts.read_at, Some(custom_read_ts));

    assert!(
        items
            .update(
                &item_new.id,
                Some(ItemStatus::Unread),
                None,
                None,
                ReadAtPatch::MarkRead(custom_read_ts),
            )
            .await
            .unwrap()
    );
    let restored_read = items.get(&item_new.id).await.unwrap().unwrap();
    assert_eq!(restored_read.status, ItemStatus::Unread);
    assert_eq!(restored_read.read_at, Some(custom_read_ts));

    // `AllUnread` / `Feed { unread_only: true }` archives items in Inbox even if `read_at` is set
    let before_archive_id = db.sync().latest_activity_id().await.unwrap();
    let scope =
        ArchiveBatchScope::from_request(&["   ".to_string()], Some(&feed.id), true).unwrap();
    let archived = items.archive_batch(&scope).await.unwrap();
    assert_eq!(archived, 3);
    assert_eq!(items.count_by_status(ItemStatus::Unread).await.unwrap(), 0);
    assert_eq!(items.count_by_status(ItemStatus::Archived).await.unwrap(), 3);
    let still_in_wtr = items.get(&huge_item.id).await.unwrap().unwrap();
    assert!(still_in_wtr.in_want_to_read);

    let archive_changes = db.sync().list_changes_since(before_archive_id, 10).await.unwrap();
    assert_eq!(archive_changes.len(), 3);
    assert!(archive_changes.iter().all(|c| c.action == ActivityAction::Archive));
}
