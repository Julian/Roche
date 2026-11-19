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

    // 1. Seed two album entries in a manual pool and verify logging a Deluxe Edition resolves to the exact canonical album
    let pool = catalog
        .create_manual_collection(
            "Jazz Canon",
            Some("jazz_canon"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let sinatra_orig = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "In The Wee Small Hours".to_owned(),
            creators: vec!["Frank Sinatra".to_owned()],
            parent_collection_id: Some(pool.id.clone()),
            parent_position: Some(1.0),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let newport = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "Ellington at Newport".to_owned(),
            creators: vec!["Duke Ellington".to_owned()],
            parent_collection_id: Some(pool.id.clone()),
            parent_position: Some(2.0),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    let sinatra_remaster = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Album),
            title: "In The Wee Small Hours (Deluxe Remastered Edition)".to_owned(),
            creators: vec!["Frank Sinatra".to_owned()],
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    assert_eq!(sinatra_remaster.id, sinatra_orig.id);
    assert_eq!(sinatra_remaster.title, "In The Wee Small Hours");

    // 2. Consuming a single `song` on an unheard album engages `artist` ("Duke Ellington")
    //    but NEVER marks the parent `album` ("Ellington at Newport") consumed!
    let track = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Song),
            title: "Diminuendo and Crescendo in Blue".to_owned(),
            creators: vec!["Duke Ellington".to_owned()],
            parent_collection_id: Some(newport.id.clone()),
            parent_position: Some(4.0),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.log_consumption(&track.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

    let catalog_svc = crate::service::CatalogService::new(&db);
    let artists_ranked = catalog_svc.get_ranked_collection("artists", false).await.unwrap();
    assert!(
        artists_ranked.rows.iter().any(|r| r.entry.title == "Duke Ellington"),
        "Consuming a song must engage its synthesized Artist entry"
    );
    let albums_ranked = catalog_svc.get_ranked_collection("albums", false).await.unwrap();
    assert!(
        albums_ranked.rows.is_empty(),
        "Consuming a single song must NEVER mark its parent album as consumed"
    );
    let gen_state = catalog.get_collection_generator(&pool.id).await.unwrap();
    assert_eq!(gen_state.consumed_count, 0);
    assert_eq!(gen_state.remaining_count, 2);
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
    let catalog_svc = crate::service::CatalogService::new(&db);
    let want_view = catalog_svc.get_ranked_collection("movies", true).await.unwrap();
    assert!(want_view.is_priority_scope);
    assert_eq!(want_view.rows[0].entry.id, solaris.id);
    assert_eq!(want_view.rows[0].tier, None, "Priority queues must not emit S-D quality tiers");

    // Global quality comparison: Stalker > Book (bleeds into heterogeneous `custom` list, unaffected by want priority)
    catalog
        .append_comparison(None, &[vec![movie.id.clone()], vec![book.id.clone()]], None)
        .await
        .unwrap();
    let custom_view =
        catalog_svc.get_ranked_collection(&custom.slug.unwrap(), false).await.unwrap();
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
    let want_after = catalog_svc.get_ranked_collection("movies", true).await.unwrap();
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

    let comp_ids = sqlx::query_scalar!(r#"SELECT id as "id!" FROM comparisons"#)
        .fetch_all(db.pool())
        .await
        .unwrap();
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
    sqlx::query!(
        "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at)
         VALUES (?, ?, '2026-01-01 08:30:00+00:00', 'consumed', 'Morning read', '2026-01-01 08:30:00+00:00')",
        &auto_id,
        &book.id,
    )
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
async fn test_generic_collection_generator_never_auto_rotates_and_advances_on_finish_or_abandon() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let pool = catalog
        .create_manual_collection(
            "Reading Pile",
            Some("reading_pile"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    for (pos, title) in [(1.0, "Dune"), (2.0, "Solaris"), (3.0, "Roadside Picnic")] {
        catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: title.to_owned(),
                parent_collection_id: Some(pool.id.clone()),
                parent_position: Some(pos),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
    }

    let initial = catalog.get_collection_generator("reading_pile").await.unwrap();
    assert!(!initial.enabled);
    assert!(initial.current_entry.is_none());
    assert_eq!(initial.total_pool, 3);

    // Enabling draws one work
    let enabled1 = catalog.set_collection_generator_enabled("reading_pile", true).await.unwrap();
    assert!(enabled1.enabled);
    let draw1 = enabled1.current_entry.unwrap();

    // Calling enable again or pause -> enable MUST preserve the exact same active draw (never auto-rotates!)
    let enabled_again =
        catalog.set_collection_generator_enabled("reading_pile", true).await.unwrap();
    assert_eq!(enabled_again.current_entry.unwrap().id, draw1.id);
    catalog.set_collection_generator_enabled("reading_pile", false).await.unwrap();
    let reenabled = catalog.set_collection_generator_enabled("reading_pile", true).await.unwrap();
    assert_eq!(reenabled.current_entry.unwrap().id, draw1.id);

    // Finishing logs `consumed` and draws next
    let (finished, after_finish) =
        catalog.finish_active_generator_entry("reading_pile", Some("Great ending")).await.unwrap();
    assert_eq!(finished.id, draw1.id);
    assert_eq!(after_finish.consumed_count, 1);
    assert_eq!(after_finish.abandoned_count, 0);
    assert_eq!(after_finish.remaining_count, 2);
    let draw2 = after_finish.current_entry.unwrap();
    assert_ne!(draw2.id, draw1.id);

    // Abandoning logs `abandoned` and draws the remaining work
    let (abandoned, after_abandon) =
        catalog.abandon_active_generator_entry("reading_pile", Some("Not for me")).await.unwrap();
    assert_eq!(abandoned.id, draw2.id);
    assert_eq!(after_abandon.consumed_count, 1);
    assert_eq!(after_abandon.abandoned_count, 1);
    assert_eq!(after_abandon.remaining_count, 1);
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

#[tokio::test]
async fn test_remove_collection_entry_advances_or_clears_generator() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    let col = catalog
        .create_manual_collection(
            "Removal Pool",
            Some("removal-pool"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let book1 = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book 1".to_owned(),
            parent_collection_id: Some(col.id.clone()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    let book2 = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book 2".to_owned(),
            parent_collection_id: Some(col.id.clone()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    catalog.update_collection_generator(&col.id, Some(true), Some(&book1.id), None).await.unwrap();

    // Removing the currently drawn entry must redraw the remaining entry
    assert!(catalog.remove_collection_entry(&col.id, &book1.id).await.unwrap());
    let after_first = catalog.get_collection_generator(&col.id).await.unwrap();
    assert_eq!(after_first.current_entry.as_ref().map(|e| e.id.as_str()), Some(book2.id.as_str()));

    // Removing the last entry must clear current_entry_id to None
    assert!(catalog.remove_collection_entry(&col.id, &book2.id).await.unwrap());
    let after_second = catalog.get_collection_generator(&col.id).await.unwrap();
    assert!(after_second.current_entry.is_none());
    assert!(after_second.drawn_at.is_none());

    // Disabled generator holding an entry must also clear on removal
    let book3 = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Book 3".to_owned(),
            parent_collection_id: Some(col.id.clone()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.update_collection_generator(&col.id, Some(false), Some(&book3.id), None).await.unwrap();
    assert!(catalog.remove_collection_entry(&col.id, &book3.id).await.unwrap());
    let after_disabled = catalog.get_collection_generator(&col.id).await.unwrap();
    assert!(after_disabled.current_entry.is_none());
}

#[tokio::test]
async fn test_merge_manual_collections_migrates_or_tombstones_generators() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    // Case 1: duplicate has generator, canonical has none -> migrate generator to canonical
    let dup1 = catalog
        .create_manual_collection(
            "Dup List 1",
            Some("dup-list-1"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let canon1 = catalog
        .create_manual_collection(
            "Canon List 1",
            Some("canon-list-1"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    let book1 = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Migrated Book".to_owned(),
            parent_collection_id: Some(dup1.id.clone()),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();
    catalog.update_collection_generator(&dup1.id, Some(true), Some(&book1.id), None).await.unwrap();

    catalog.merge_entries(&dup1.id, &canon1.id).await.unwrap();

    let dup1_gen_row: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM collection_generators WHERE collection_id = ?")
            .bind(&dup1.id)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert!(dup1_gen_row.is_none(), "duplicate generator row must not remain orphaned");
    let canon1_gen = catalog.get_collection_generator(&canon1.id).await.unwrap();
    assert!(canon1_gen.enabled);
    assert_eq!(canon1_gen.current_entry.as_ref().map(|e| e.id.as_str()), Some(book1.id.as_str()));

    // Case 2: both duplicate and canonical have generators -> delete dup generator + record tombstone
    let dup2 = catalog
        .create_manual_collection(
            "Dup List 2",
            Some("dup-list-2"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    catalog.set_collection_generator_enabled(&dup2.id, true).await.unwrap();
    catalog.merge_entries(&dup2.id, &canon1.id).await.unwrap();

    let dup2_gen_row: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM collection_generators WHERE collection_id = ?")
            .bind(&dup2.id)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert!(dup2_gen_row.is_none());
    let dup2_tomb_hlc: Option<String> = sqlx::query_scalar(
        "SELECT hlc FROM sync_tombstones
         WHERE entity_type = 'collection_generator' AND natural_key = ?",
    )
    .bind(&dup2.id)
    .fetch_optional(db.pool())
    .await
    .unwrap();
    let dup2_tomb_hlc = dup2_tomb_hlc.expect("expected CollectionGenerator tombstone for dup2");
    let canon1_hlc: String =
        sqlx::query_scalar("SELECT hlc FROM collection_generators WHERE collection_id = ?")
            .bind(&canon1.id)
            .fetch_one(db.pool())
            .await
            .unwrap();
    assert!(
        canon1_hlc > dup2_tomb_hlc,
        "canonical generator HLC must exceed duplicate tombstone HLC"
    );

    // Case 3: merging into ineligible `STARRED` deletes dup generator + records tombstone
    let dup3 = catalog
        .create_manual_collection(
            "Dup List 3",
            Some("dup-list-3"),
            CollectionRole::List,
            None,
            None,
        )
        .await
        .unwrap();
    catalog.set_collection_generator_enabled(&dup3.id, true).await.unwrap();
    catalog.merge_entries(&dup3.id, crate::db::STARRED_COLLECTION_ID).await.unwrap();

    let dup3_gen_row: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM collection_generators WHERE collection_id = ?")
            .bind(&dup3.id)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert!(dup3_gen_row.is_none());
    let starred_gen_row: Option<i64> =
        sqlx::query_scalar("SELECT 1 FROM collection_generators WHERE collection_id = ?")
            .bind(crate::db::STARRED_COLLECTION_ID)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert!(starred_gen_row.is_none());
    let dup3_tomb: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sync_tombstones
         WHERE entity_type = 'collection_generator' AND natural_key = ?",
    )
    .bind(&dup3.id)
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(dup3_tomb.is_some());
}

#[tokio::test]
async fn test_local_consumption_log_clears_stale_tombstone() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();
    let book = catalog
        .upsert_entry(&UpsertEntryInput {
            kind: Some(EntryKind::Book),
            title: "Re-logged Book".to_owned(),
            ..UpsertEntryInput::default()
        })
        .await
        .unwrap();

    let event_ts = crate::util::clock::now_utc();
    let log_id = crate::db::deterministic_consumption_log_id(
        &book.id,
        event_ts.timestamp_millis(),
        ConsumptionStatus::Consumed.as_str(),
    );
    let mut tx = crate::db::begin_write(db.pool()).await.unwrap();
    crate::db::SyncRepo::record_tombstone(
        &mut tx,
        crate::db::ActivityEntityType::ConsumptionLog,
        &log_id,
        event_ts,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();

    catalog
        .log_consumption(&book.id, ConsumptionStatus::Consumed, None, Some(event_ts))
        .await
        .unwrap();

    let tomb: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sync_tombstones WHERE entity_type = 'consumption_log' AND natural_key = ?",
    )
    .bind(&log_id)
    .fetch_optional(db.pool())
    .await
    .unwrap();
    assert!(
        tomb.is_none(),
        "local consumption log write must clear stale ConsumptionLog tombstone"
    );
}

#[tokio::test]
async fn test_merge_entries_escapes_like_wildcards_in_comparisons() {
    let db = Db::connect("sqlite::memory:").await.unwrap();
    let catalog = db.catalog();

    for (id, title) in [
        ("work_a", "Work A Dup"),
        ("work_canon", "Work Canon"),
        ("workXa", "Work Lookalike"),
        ("other_b", "Other B"),
    ] {
        catalog
            .upsert_entry(&UpsertEntryInput {
                id: Some(id.to_owned()),
                kind: Some(EntryKind::Book),
                title: title.to_owned(),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
    }

    let comp_ts = chrono::Utc::now() - chrono::Duration::hours(2);
    let unrelated_comp = catalog
        .append_comparison(
            None,
            &[vec!["workXa".to_owned()], vec!["other_b".to_owned()]],
            Some(comp_ts),
        )
        .await
        .unwrap();
    let related_comp = catalog
        .append_comparison(
            None,
            &[vec!["work_a".to_owned()], vec!["other_b".to_owned()]],
            Some(comp_ts),
        )
        .await
        .unwrap();

    // Stamp an old `created_at` on the unrelated comparison so we can prove it isn't rewritten
    let old_created = comp_ts;
    sqlx::query("UPDATE comparisons SET created_at = ? WHERE id = ?")
        .bind(old_created)
        .bind(&unrelated_comp.id)
        .execute(db.pool())
        .await
        .unwrap();

    catalog.merge_entries("work_a", "work_canon").await.unwrap();

    // Unrelated comparison containing "workXa" must be completely untouched
    let unrelated_created: Option<chrono::DateTime<chrono::Utc>> =
        sqlx::query_scalar("SELECT created_at FROM comparisons WHERE id = ?")
            .bind(&unrelated_comp.id)
            .fetch_optional(db.pool())
            .await
            .unwrap();
    assert_eq!(unrelated_created, Some(old_created));

    // Related comparison containing "work_a" must be replaced with "work_canon"
    let old_related: Option<i64> = sqlx::query_scalar("SELECT 1 FROM comparisons WHERE id = ?")
        .bind(&related_comp.id)
        .fetch_optional(db.pool())
        .await
        .unwrap();
    assert!(old_related.is_none());
    let rewritten_count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comparisons WHERE ordering_json LIKE '%\"work_canon\"%'",
    )
    .fetch_one(db.pool())
    .await
    .unwrap();
    assert_eq!(rewritten_count, 1);
}
