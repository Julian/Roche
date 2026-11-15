use url::Url;

use super::*;
use crate::db::{
    Db, FeedType, MediaType,
    sync::{deterministic_auto_consumption_log_id, deterministic_catalog_entry_id},
    types::{CollectionMode, CollectionRole, ConsumptionStatus, EntryKind},
};

#[test]
fn test_strip_album_edition_suffix_and_spotify_normalization() {
    assert_eq!(strip_album_edition_suffix("Abbey Road (Remastered)"), "Abbey Road");
    assert_eq!(
        strip_album_edition_suffix("OK Computer (20th Anniversary Edition) [2017 Remaster]"),
        "OK Computer"
    );
    assert_eq!(strip_album_edition_suffix("Stop Making Sense (Live)"), "Stop Making Sense (Live)");
    assert_eq!(
        strip_album_edition_suffix("MTV Unplugged in New York (Live)"),
        "MTV Unplugged in New York (Live)"
    );
    // Whole-word guard: "Cleaning" / "Subversion" / "Expedition" must NOT be stripped as "clean"/"version"/"edition"
    assert_eq!(strip_album_edition_suffix("Spring (Cleaning)"), "Spring (Cleaning)");
    assert_eq!(strip_album_edition_suffix("Northern (Expedition)"), "Northern (Expedition)");

    let expected_uri = "spotify:album:3GmwKB1tgPZgXeRJZSm9WX";
    assert_eq!(
        normalize_identifier_value(EntryKind::Album, "spotify", "3GmwKB1tgPZgXeRJZSm9WX"),
        expected_uri
    );
    assert_eq!(
        normalize_identifier_value(
            EntryKind::Album,
            "spotify",
            "https://open.spotify.com/album/3GmwKB1tgPZgXeRJZSm9WX?si=123"
        ),
        expected_uri
    );
    assert_eq!(
        normalize_identifier_value(
            EntryKind::Album,
            "spotify",
            "https://open.spotify.com/intl-de/album/3GmwKB1tgPZgXeRJZSm9WX"
        ),
        expected_uri
    );
    assert_eq!(
        normalize_identifier_value(
            EntryKind::Collection,
            "spotify",
            "spotify:user:spotifycharts:playlist:37i9dQZEVXbMDoHDwVN2tF"
        ),
        "spotify:playlist:37i9dQZEVXbMDoHDwVN2tF"
    );
    assert_eq!(
        deterministic_catalog_entry_id("album", "spotify", expected_uri),
        "83f8410d-71bc-5ea4-911e-06869fe1b039"
    );
}

#[tokio::test]
async fn test_upfront_dedup_edition_merging_artist_synthesis_and_song_vs_album_rule() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    // 1. Seed 1001 Albums and verify logging a Deluxe Edition resolves to the exact 1001 Album
    let seeded = catalog.ensure_1001_albums_seeded().await.unwrap();
    assert!(seeded >= 1_000);

    let sinatra_remaster = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "In The Wee Small Hours (Deluxe Remastered Edition)".to_owned(),
            creators: vec!["Frank Sinatra".to_owned()],
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    assert_eq!(sinatra_remaster.id, "83f8410d-71bc-5ea4-911e-06869fe1b039");
    assert_eq!(sinatra_remaster.title, "In The Wee Small Hours");

    // 2. Consuming a single `song` on an unheard 1001 album engages `artist` ("Duke Ellington")
    //    but NEVER marks the parent `album` ("Ellington at Newport") consumed!
    let newport_id = "c8edff07-31ae-5f23-9d42-03468251904a";
    let track = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Song),
            title: "Diminuendo and Crescendo in Blue".to_owned(),
            creators: vec!["Duke Ellington".to_owned()],
            parent_collection_id: Some(newport_id.to_owned()),
            parent_position: Some(4.0),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.log_consumption(&track.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

    let artists_ranked = catalog.get_ranked_collection("artists", false).await.unwrap();
    assert!(
        artists_ranked.rows.iter().any(|r| r.entry.title == "Duke Ellington"),
        "Consuming a song must engage its synthesized Artist entry"
    );
    let albums_ranked = catalog.get_ranked_collection("albums", false).await.unwrap();
    assert!(
        albums_ranked.rows.is_empty(),
        "Consuming a single song must NEVER mark its parent album as consumed"
    );
    let gen_state = catalog.get_1001_generator_state().await.unwrap();
    assert_eq!(gen_state.consumed_count, 0);
    assert_eq!(gen_state.remaining_count, gen_state.total_pool);
}

#[tokio::test]
async fn test_collection_mode_and_cycle_enforcement_on_add_and_upsert() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let movie = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Stalker".to_owned(),
            creators: vec!["Andrei Tarkovsky".to_owned()],
            release_year: Some(1979),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    // Dynamic collection (`movies`) rejects manual add & upsert parent_collection_id
    let dyn_col = catalog.get_collection_summary("movies").await.unwrap();
    let dyn_err = catalog.add_collection_entry("movies", &movie.id, None).await.unwrap_err();
    assert!(matches!(
        dyn_err,
        CatalogError::ImmutableCollection { mode: CollectionMode::Dynamic, .. }
    ));
    let upsert_dyn_err = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Mirror".to_owned(),
            parent_collection_id: Some(dyn_col.id),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap_err();
    assert!(matches!(
        upsert_dyn_err,
        CatalogError::ImmutableCollection { mode: CollectionMode::Dynamic, .. }
    ));

    // Feed collection rejects manual add & upsert parent_collection_id
    let feed_url = Url::parse("https://example.com/feed.xml").unwrap();
    let feed = db.feeds().create(FeedType::Rss, &feed_url, Some("Test Blog"), None).await.unwrap();
    let feed_err = catalog.add_collection_entry(&feed.id, &movie.id, None).await.unwrap_err();
    assert!(matches!(
        feed_err,
        CatalogError::ImmutableCollection { mode: CollectionMode::Feed, .. }
    ));

    // Cycle check: Col A -> Col B -> Col A must fail
    let col_a = catalog
        .create_manual_collection("Outer List", None, CollectionRole::List, None, None)
        .await
        .unwrap();
    let col_b = catalog
        .create_manual_collection("Inner List", None, CollectionRole::List, None, None)
        .await
        .unwrap();
    catalog.add_collection_entry(&col_a.id, &col_b.id, None).await.unwrap();
    let cycle_err = catalog.add_collection_entry(&col_b.id, &col_a.id, None).await.unwrap_err();
    assert!(matches!(cycle_err, CatalogError::InvalidInput(_)));

    // Custom manual collection allows heterogeneous entries (Movie + Book) and global quality bleed
    let custom = catalog
        .create_manual_collection(
            "Desert Island Picks",
            None,
            CollectionRole::List,
            Some("Cross-medium favorites"),
            None,
        )
        .await
        .unwrap();
    let book = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Roadside Picnic".to_owned(),
            creators: vec!["Arkady Strugatsky".to_owned(), "Boris Strugatsky".to_owned()],
            release_year: Some(1972),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    catalog.add_collection_entry(&custom.id, &movie.id, Some(1.0)).await.unwrap();
    catalog.add_collection_entry(&custom.id, &book.id, Some(2.0)).await.unwrap();

    // Want queue + local priority vs global quality isolation + auto-promotion on consume
    let (want_movies, _) = catalog
        .want_entry(
            &UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Solaris".to_owned(),
                release_year: Some(1972),
                ..UpsertEntryInput::default()
            },
            None,
        )
        .await
        .unwrap();
    let solaris = catalog.resolve_entry("Solaris", Some(EntryKind::Movie), None).await.unwrap();
    catalog.add_collection_entry(&want_movies.id, &movie.id, None).await.unwrap();

    // Local priority comparison: Solaris > Stalker inside `want:movies`
    catalog
        .append_comparison(
            Some(&want_movies.id),
            &[vec![solaris.id.clone()], vec![movie.id.clone()]],
            None,
        )
        .await
        .unwrap();
    let want_view = catalog.get_ranked_collection("movies", true).await.unwrap();
    assert!(want_view.is_priority_scope);
    assert_eq!(want_view.rows[0].entry.id, solaris.id);
    assert_eq!(want_view.rows[0].tier, None, "Priority queues must not emit S-D quality tiers");

    // Global quality comparison: Stalker > Book (bleeds into heterogeneous `custom` list, unaffected by want priority)
    catalog
        .append_comparison(None, &[vec![movie.id.clone()], vec![book.id.clone()]], None)
        .await
        .unwrap();
    let custom_view = catalog.get_ranked_collection(&custom.slug.unwrap(), false).await.unwrap();
    assert!(!custom_view.is_priority_scope);
    assert_eq!(custom_view.rows[0].entry.id, movie.id);
    assert_eq!(custom_view.rows[0].tier, Some('S'));
    assert_eq!(custom_view.rows[1].entry.id, book.id);
    assert_eq!(custom_view.rows[1].tier, Some('D'));

    // Consuming Solaris auto-removes it from `want:movies` and places it on `movies`
    catalog
        .log_consumption(&solaris.id, ConsumptionStatus::Consumed, Some("Masterpiece"), None)
        .await
        .unwrap();
    let want_after = catalog.get_ranked_collection("movies", true).await.unwrap();
    assert_eq!(want_after.rows.len(), 1);
    assert_eq!(want_after.rows[0].entry.id, movie.id);
}

#[tokio::test]
async fn test_post_hoc_merge_folds_identifiers_media_logs_and_comparisons_and_guards_system() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    // Protected system collection cannot be merged away
    let movies_sys = catalog.get_collection_summary("movies").await.unwrap();
    let books_sys = catalog.get_collection_summary("books").await.unwrap();
    let sys_merge_err = catalog.merge_entries(&movies_sys.id, &books_sys.id).await.unwrap_err();
    assert!(matches!(sys_merge_err, CatalogError::InvalidInput(_)));

    let a = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::PodcastEpisode),
            title: "Original Episode".to_owned(),
            url: Some("https://author.example.com/ep1".to_owned()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let b = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::PodcastEpisode),
            title: "Mirror Episode Copy".to_owned(),
            url: Some("https://mirror.example.com/ep1".to_owned()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let c = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::PodcastEpisode),
            title: "Other Episode".to_owned(),
            url: Some("https://author.example.com/ep2".to_owned()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    // Attach media to `b` and verify merge migrates it onto `a`
    db.media()
        .create(&b.id, MediaType::Audio, "audio/ep1.mp3", Some(4096), Some(1800))
        .await
        .unwrap();

    catalog.log_consumption(&a.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
    catalog
        .log_consumption(&b.id, ConsumptionStatus::Consumed, Some("Re-listen"), None)
        .await
        .unwrap();
    catalog.log_consumption(&c.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

    // Comparison 1: B > C (must be re-keyed to new deterministic comparison ID for A > C!)
    let old_comp = catalog
        .append_comparison(None, &[vec![b.id.clone()], vec![c.id.clone()]], None)
        .await
        .unwrap();
    // Comparison 2: A > B (becomes trivial 1-item after merge and is dropped)
    catalog.append_comparison(None, &[vec![a.id.clone()], vec![b.id.clone()]], None).await.unwrap();

    let merged = catalog.merge_entries(&b.id, &a.id).await.unwrap();
    assert_eq!(merged.id, a.id);
    assert_eq!(merged.identifiers.len(), 2);

    let migrated_media = db.media().list_for_item(&a.id).await.unwrap();
    assert_eq!(migrated_media.len(), 1);
    assert_eq!(migrated_media[0].path, "audio/ep1.mp3");

    let comp_ids: Vec<String> =
        sqlx::query_scalar("SELECT id FROM comparisons").fetch_all(db.pool()).await.unwrap();
    assert_eq!(comp_ids.len(), 1);
    assert_ne!(
        comp_ids[0], old_comp.id,
        "Merged comparison must get a fresh deterministic ID so additive peer sync converges"
    );

    // Merged artist alias must resolve cleanly when later albums credit the alias
    let alias_artist = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Artist),
            title: "MF DOOM".to_owned(),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let canon_artist = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Artist),
            title: "Viktor Vaughn / MF DOOM".to_owned(),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.merge_entries(&alias_artist.id, &canon_artist.id).await.unwrap();

    let album_under_alias = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "MM..FOOD".to_owned(),
            creators: vec!["MF DOOM".to_owned()],
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let detail = catalog.get_entry_detail(&album_under_alias.id).await.unwrap().unwrap();
    assert!(
        detail.parents.iter().any(|p| p.id == canon_artist.id),
        "Subsequent albums crediting a merged artist alias must link to the canonical artist, not the tombstone"
    );
}

#[tokio::test]
async fn test_diary_jan_1st_space_timestamp_and_auto_consumption_idempotency() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let book = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "New Year's Day Book".to_owned(),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    // Insert space-delimited Jan 1st UTC timestamp directly (as sqlx / legacy SQLite rows do)
    let auto_id = deterministic_auto_consumption_log_id(&book.id);
    sqlx::query(
        "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at)
         VALUES (?, ?, '2026-01-01 08:30:00+00:00', 'consumed', 'Morning read', '2026-01-01 08:30:00+00:00')",
    )
    .bind(&auto_id)
    .bind(&book.id)
    .execute(db.pool())
    .await
    .unwrap();

    let diary_2026 = catalog.list_diary(Some(2026), None, 50).await.unwrap();
    assert_eq!(diary_2026.len(), 1, "Jan 1st space-formatted timestamp must appear in 2026 diary");
    assert_eq!(diary_2026[0].entry.id, book.id);

    // Replaying auto-completion at a different timestamp must NOT duplicate the Diary entry
    let mut tx = db.pool().begin().await.unwrap();
    let now = chrono::Utc::now();
    let returned_id =
        CatalogRepo::ensure_auto_consumed_with_parents_in_tx(&mut tx, &book.id, None, now, now)
            .await
            .unwrap()
            .unwrap();
    tx.commit().await.unwrap();
    assert_eq!(returned_id, auto_id);

    let diary_after = catalog.list_diary(Some(2026), None, 50).await.unwrap();
    assert_eq!(diary_after.len(), 1);
}

#[tokio::test]
async fn test_strict_1001_albums_generator_never_auto_rotates_and_advances_on_finish_or_abandon() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let initial = catalog.get_1001_generator_state().await.unwrap();
    assert!(!initial.enabled);
    assert!(initial.current_entry.is_none());
    assert_eq!(initial.total_pool, 1_101);

    // Enabling draws one album
    let enabled1 = catalog.set_1001_generator_enabled(true).await.unwrap();
    assert!(enabled1.enabled);
    let draw1 = enabled1.current_entry.unwrap();

    // Calling enable again or disable -> enable MUST preserve the exact same active album (never auto-rotates!)
    let enabled_again = catalog.set_1001_generator_enabled(true).await.unwrap();
    assert_eq!(enabled_again.current_entry.unwrap().id, draw1.id);
    catalog.set_1001_generator_enabled(false).await.unwrap();
    let reenabled = catalog.set_1001_generator_enabled(true).await.unwrap();
    assert_eq!(reenabled.current_entry.unwrap().id, draw1.id);

    // Finishing logs `consumed` and draws next
    let (finished, after_finish) =
        catalog.finish_active_1001_album(Some("Loved side B")).await.unwrap();
    assert_eq!(finished.id, draw1.id);
    assert_eq!(after_finish.consumed_count, 1);
    assert_eq!(after_finish.abandoned_count, 0);
    assert_eq!(after_finish.remaining_count, 1_100);
    let draw2 = after_finish.current_entry.unwrap();
    assert_ne!(draw2.id, draw1.id);

    // Abandoning logs `abandoned` and draws next
    let (abandoned, after_abandon) =
        catalog.abandon_active_1001_album(Some("Not for me")).await.unwrap();
    assert_eq!(abandoned.id, draw2.id);
    assert_eq!(after_abandon.consumed_count, 1);
    assert_eq!(after_abandon.abandoned_count, 1);
    assert_eq!(after_abandon.remaining_count, 1_099);
    assert_ne!(after_abandon.current_entry.unwrap().id, draw2.id);
}

#[tokio::test]
async fn test_merge_entries_rejects_collection_target_and_indirect_cycles() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let movie = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Movie),
            title: "Stalker".to_owned(),
            creators: vec!["Andrei Tarkovsky".to_owned()],
            release_year: Some(1979),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let list_x = catalog
        .create_manual_collection("Tarkovsky Watch", None, CollectionRole::List, None, None)
        .await
        .unwrap();

    // Merging a non-collection entry into a manual collection must be rejected
    assert!(catalog.merge_entries(&movie.id, &list_x.id).await.is_err());

    // Indirect cycle via reparenting: A contains B, C contains A -> merging B into C would create A -> C -> A!
    let col_a = catalog
        .create_manual_collection("Outer Group A", None, CollectionRole::List, None, None)
        .await
        .unwrap();
    let col_b = catalog
        .create_manual_collection("Dup Group B", None, CollectionRole::List, None, None)
        .await
        .unwrap();
    let col_c = catalog
        .create_manual_collection("Canon Group C", None, CollectionRole::List, None, None)
        .await
        .unwrap();
    catalog.add_collection_entry(&col_a.id, &col_b.id, None).await.unwrap();
    catalog.add_collection_entry(&col_c.id, &col_a.id, None).await.unwrap();
    assert!(
        catalog.merge_entries(&col_b.id, &col_c.id).await.is_err(),
        "merge_entries must reject indirect collection cycles"
    );

    // arXiv URL extraction on catalog upsert
    let paper = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Paper),
            title: "Attention Is All You Need".to_owned(),
            url: Some("https://arxiv.org/abs/1706.03762v7".to_owned()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    assert!(
        paper.identifiers.iter().any(|i| i.scheme == "arxiv" && i.value == "1706.03762v7"),
        "CatalogRepo::upsert_entry must extract normalized arxiv identifier from arXiv URL"
    );
}
