#![allow(clippy::unwrap_used)]

use std::net::IpAddr;

use chrono::{DateTime, Utc};
use serde::Deserialize;
use url::Url;

use super::{
    PeerSyncRequest, PreparedSyncEntry, SyncCollectionEntryRecord, SyncCollectionGeneratorRecord,
    SyncComparisonRecord, SyncConsumptionLogRecord, SyncEntryRecord, SyncTombstone,
    canonical_ordering_json,
};
use crate::{
    content::classify::{classify_url, infer_feed_type},
    db::{
        ActivityEntityType, CollectionRole, ContentType, Db, EntryKind, InboxState,
        derive_manual_collection_slug_and_id, external_identifier_url_for_kind,
    },
    ranking::RankBoard,
    service::enrich::{
        WikipediaSearchHit, extract_spotify_artist_uri_from_album_html,
        score_artist_wikipedia_hit_for_artist, select_best_artist_wikipedia_slug,
    },
    util::{
        media_urls::{extract_youtube_video_id, is_arxiv_host, is_youtube_feed_url},
        url_guard::{is_global_ip, parse_public_url},
    },
};

#[derive(Deserialize)]
struct CustomCollectionIdVector {
    title: String,
    role: CollectionRole,
    expected_slug: Option<String>,
    expected_collection_id: Option<String>,
}

#[derive(Deserialize)]
struct UrlClassificationVector {
    url: String,
    expected_content_type: String,
    expected_reader_class: String,
    expected_feed_type: String,
    expected_is_youtube_feed: bool,
    expected_youtube_video_id: Option<String>,
    expected_is_arxiv: bool,
}

#[derive(Deserialize)]
struct IpValidationVector {
    ip: String,
    expected_allowed: bool,
}

#[derive(Deserialize)]
struct UrlValidationVector {
    url: String,
    expected_allowed: bool,
}

#[derive(Deserialize)]
struct SsrfValidationSuite {
    ips: Vec<IpValidationVector>,
    urls: Vec<UrlValidationVector>,
}

#[derive(Deserialize)]
struct ExternalIdentifierUrlVector {
    scheme: String,
    value: String,
    kind: Option<EntryKind>,
    expected_url: Option<String>,
}

#[derive(Deserialize)]
struct RankBoardOpFixture {
    op: String,
    entry_id: Option<String>,
    target_slot: Option<usize>,
}

#[derive(Deserialize)]
struct RankBoardScenarioVector {
    name: String,
    initial_items: Vec<String>,
    ops: Vec<RankBoardOpFixture>,
    expected_ordering: Vec<Vec<String>>,
    expected_display_ranks: Vec<usize>,
    expected_tied_with_above: Vec<bool>,
}

#[derive(Deserialize)]
struct WikiHitFixture {
    title: String,
    snippet: String,
}

#[derive(Deserialize)]
struct WikiScoringVector {
    name: String,
    artist_name: String,
    child_titles: Vec<String>,
    hits: Vec<WikiHitFixture>,
    expected_scores: Vec<i32>,
    expected_winner: Option<String>,
}

#[derive(Deserialize)]
struct SpotifyHtmlVector {
    name: String,
    artist_name: String,
    allow_single_fallback: bool,
    html: String,
    expected_uri: Option<String>,
}

#[derive(Deserialize)]
struct ArtistEnrichmentSuite {
    wikipedia_scoring: Vec<WikiScoringVector>,
    spotify_html_extraction: Vec<SpotifyHtmlVector>,
}

#[derive(Deserialize)]
struct InitialSyncFixture {
    #[serde(default)]
    entries: Vec<SyncEntryRecord>,
    #[serde(default)]
    collection_entries: Vec<SyncCollectionEntryRecord>,
    #[serde(default)]
    consumption_log: Vec<SyncConsumptionLogRecord>,
    #[serde(default)]
    comparisons: Vec<SyncComparisonRecord>,
    #[serde(default)]
    collection_generators: Vec<SyncCollectionGeneratorRecord>,
    #[serde(default)]
    tombstones: Vec<SyncTombstone>,
}

#[derive(Deserialize)]
struct IncomingSyncFixture {
    peer_id: String,
    #[serde(default)]
    entries: Vec<SyncEntryRecord>,
    #[serde(default)]
    collection_entries: Vec<SyncCollectionEntryRecord>,
    #[serde(default)]
    consumption_log: Vec<SyncConsumptionLogRecord>,
    #[serde(default)]
    comparisons: Vec<SyncComparisonRecord>,
    #[serde(default)]
    collection_generators: Vec<SyncCollectionGeneratorRecord>,
    #[serde(default)]
    tombstones: Vec<SyncTombstone>,
}

#[derive(Deserialize)]
struct ExpectedEntryStateFixture {
    entry_id: String,
    inbox_state: InboxState,
    read_at_present: bool,
    progress: f64,
    hlc: String,
}

#[derive(Deserialize)]
struct ExpectedEntryPresenceFixture {
    entry_id: String,
    exists: bool,
    merged_into_id: Option<String>,
}

#[derive(Deserialize)]
struct ExpectedCollectionMembersFixture {
    collection_id: String,
    entry_ids: Vec<String>,
}

#[derive(Deserialize)]
struct ExpectedLogCountFixture {
    entry_id: String,
    count: i64,
}

#[derive(Deserialize)]
struct ExpectedGeneratorFixture {
    collection_id: String,
    enabled: bool,
    current_entry_id: Option<String>,
}

#[derive(Deserialize)]
struct ExpectedTombstonePresenceFixture {
    entity_type: ActivityEntityType,
    natural_key: String,
    exists: bool,
}

#[derive(Deserialize)]
struct ExpectedSyncOutcomeFixture {
    #[serde(default)]
    entry_states: Vec<ExpectedEntryStateFixture>,
    #[serde(default)]
    entry_presence: Vec<ExpectedEntryPresenceFixture>,
    #[serde(default)]
    collection_members: Vec<ExpectedCollectionMembersFixture>,
    #[serde(default)]
    consumption_log_counts: Vec<ExpectedLogCountFixture>,
    #[serde(default)]
    comparison_orderings: Vec<Vec<Vec<String>>>,
    #[serde(default)]
    generators: Vec<ExpectedGeneratorFixture>,
    #[serde(default)]
    tombstone_presence: Vec<ExpectedTombstonePresenceFixture>,
}

#[derive(Deserialize)]
struct PeerSyncScenarioVector {
    name: String,
    initial: InitialSyncFixture,
    incoming: IncomingSyncFixture,
    expected: ExpectedSyncOutcomeFixture,
}

#[derive(Deserialize)]
struct ExpandedCrossStackVectors {
    custom_collection_ids: Vec<CustomCollectionIdVector>,
    url_classification: Vec<UrlClassificationVector>,
    ssrf_url_validation: SsrfValidationSuite,
    external_identifier_urls: Vec<ExternalIdentifierUrlVector>,
    rank_board_operations: Vec<RankBoardScenarioVector>,
    artist_wikipedia_scoring: ArtistEnrichmentSuite,
    peer_sync_merge_scenarios: Vec<PeerSyncScenarioVector>,
}

fn load_expanded_vectors() -> ExpandedCrossStackVectors {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../testdata/cross_stack_vectors.json");
    let raw = std::fs::read_to_string(&path).unwrap();
    serde_json::from_str(&raw).unwrap()
}

#[test]
fn test_cross_stack_custom_collection_ids() {
    let vectors = load_expanded_vectors();
    assert!(vectors.custom_collection_ids.len() >= 15);
    for case in &vectors.custom_collection_ids {
        let derived = derive_manual_collection_slug_and_id(&case.title, None, case.role);
        match (&case.expected_slug, &case.expected_collection_id) {
            (Some(exp_slug), Some(exp_id)) => {
                let (_, actual_slug, actual_id) = derived.unwrap_or_else(|e| {
                    panic!(
                        "expected Ok for title='{}' role={:?}, got err: {e}",
                        case.title, case.role
                    )
                });
                assert_eq!(actual_slug, *exp_slug, "slug mismatch for '{}'", case.title);
                assert_eq!(actual_id, *exp_id, "collection_id mismatch for '{}'", case.title);
            }
            (None, None) => {
                assert!(
                    derived.is_err(),
                    "expected Err for title='{}' role={:?}, got {derived:?}",
                    case.title,
                    case.role
                );
            }
            _ => panic!("inconsistent vector expectation for '{}'", case.title),
        }
    }
}

#[test]
fn test_cross_stack_url_classification() {
    let vectors = load_expanded_vectors();
    assert!(vectors.url_classification.len() >= 15);
    for case in &vectors.url_classification {
        let parsed = Url::parse(&case.url).unwrap();
        let ct = classify_url(&parsed);
        let ct_wire = serde_json::to_value(ct).unwrap();
        assert_eq!(
            ct_wire.as_str(),
            Some(case.expected_content_type.as_str()),
            "content_type mismatch for {}",
            case.url
        );
        let reader_class = match ct {
            ContentType::Paper | ContentType::Pdf => "pdf",
            ContentType::Video => "video",
            ContentType::Audio => "audio",
            ContentType::Article => "article",
        };
        assert_eq!(reader_class, case.expected_reader_class, "reader_class for {}", case.url);
        let feed_wire = serde_json::to_value(infer_feed_type(&parsed)).unwrap();
        assert_eq!(
            feed_wire.as_str(),
            Some(case.expected_feed_type.as_str()),
            "feed_type mismatch for {}",
            case.url
        );
        assert_eq!(
            is_youtube_feed_url(&parsed),
            case.expected_is_youtube_feed,
            "is_youtube_feed mismatch for {}",
            case.url
        );
        assert_eq!(
            extract_youtube_video_id(&parsed),
            case.expected_youtube_video_id,
            "youtube_video_id mismatch for {}",
            case.url
        );
        assert_eq!(
            is_arxiv_host(parsed.host_str()),
            case.expected_is_arxiv,
            "is_arxiv mismatch for {}",
            case.url
        );
    }
}

#[test]
fn test_cross_stack_ssrf_url_validation() {
    let suite = load_expanded_vectors().ssrf_url_validation;
    assert!(suite.ips.len() >= 25);
    assert!(suite.urls.len() >= 20);
    for case in &suite.ips {
        let ip: IpAddr = case.ip.parse().unwrap();
        assert_eq!(
            is_global_ip(ip),
            case.expected_allowed,
            "is_global_ip mismatch for {}",
            case.ip
        );
    }
    for case in &suite.urls {
        assert_eq!(
            parse_public_url(&case.url).is_ok(),
            case.expected_allowed,
            "parse_public_url mismatch for {}",
            case.url
        );
    }
}

#[test]
fn test_cross_stack_external_identifier_urls() {
    let vectors = load_expanded_vectors().external_identifier_urls;
    assert!(vectors.len() >= 30);
    for case in &vectors {
        let actual = external_identifier_url_for_kind(&case.scheme, &case.value, case.kind);
        assert_eq!(
            actual, case.expected_url,
            "external_identifier_url mismatch for ({}, '{}', {:?})",
            case.scheme, case.value, case.kind
        );
    }
}

#[test]
fn test_cross_stack_rank_board_operations() {
    let vectors = load_expanded_vectors().rank_board_operations;
    assert!(vectors.len() >= 4);
    for case in &vectors {
        let mut board = RankBoard::new(case.initial_items.clone());
        for step in &case.ops {
            match step.op.as_str() {
                "move_up" => {
                    assert!(board.select_entry_id(step.entry_id.as_deref().unwrap()));
                    board.move_up();
                }
                "move_down" => {
                    assert!(board.select_entry_id(step.entry_id.as_deref().unwrap()));
                    board.move_down();
                }
                "toggle_tie_with_above" => {
                    assert!(board.select_entry_id(step.entry_id.as_deref().unwrap()));
                    board.toggle_tie_with_above();
                }
                "move_to_rank_slot" => {
                    assert!(board.select_entry_id(step.entry_id.as_deref().unwrap()));
                    board.move_to_rank_slot(step.target_slot.unwrap());
                }
                "toggle_tie_all" => board.toggle_tie_all(),
                other => panic!("unknown RankBoard op: {other}"),
            }
        }
        assert_eq!(board.to_ordering(), case.expected_ordering, "ordering for {}", case.name);
        let rows = board.flat_rows();
        let ranks: Vec<usize> = rows.iter().map(|r| r.display_rank).collect();
        let tied: Vec<bool> = rows.iter().map(|r| r.is_tied_with_above).collect();
        assert_eq!(ranks, case.expected_display_ranks, "display_ranks for {}", case.name);
        assert_eq!(tied, case.expected_tied_with_above, "tied_with_above for {}", case.name);
    }
}

#[test]
fn test_cross_stack_artist_wikipedia_scoring_and_spotify_html() {
    let suite = load_expanded_vectors().artist_wikipedia_scoring;
    assert!(suite.wikipedia_scoring.len() >= 5);
    assert!(suite.spotify_html_extraction.len() >= 6);

    for case in &suite.wikipedia_scoring {
        let hits: Vec<WikipediaSearchHit> = case
            .hits
            .iter()
            .map(|h| WikipediaSearchHit { title: h.title.clone(), snippet: h.snippet.clone() })
            .collect();
        let actual_scores: Vec<i32> = hits
            .iter()
            .map(|h| {
                score_artist_wikipedia_hit_for_artist(h, &case.artist_name, &case.child_titles)
            })
            .collect();
        assert_eq!(actual_scores, case.expected_scores, "scores for {}", case.name);
        let winner =
            select_best_artist_wikipedia_slug(&hits, &case.artist_name, &case.child_titles);
        assert_eq!(winner, case.expected_winner, "winner for {}", case.name);
    }

    for case in &suite.spotify_html_extraction {
        let actual = extract_spotify_artist_uri_from_album_html(
            &case.html,
            &case.artist_name,
            case.allow_single_fallback,
        );
        assert_eq!(actual, case.expected_uri, "spotify html for {}", case.name);
    }
}

async fn seed_initial_sync_entries(db: &Db, entries: &[SyncEntryRecord]) {
    let now = Utc::now();
    for entry in entries {
        let created = entry.created_at.unwrap_or(now);
        let updated = entry.updated_at.unwrap_or(created);
        let creators_json = serde_json::to_string(&entry.creators).unwrap();
        sqlx::query(
            "INSERT INTO entries
             (id, kind, title, creators_json, url, published_at, release_year, summary,
              thumbnail_url, merged_into_id, created_at, updated_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&entry.id)
        .bind(entry.kind)
        .bind(&entry.title)
        .bind(&creators_json)
        .bind(entry.url.as_deref())
        .bind(entry.published_at)
        .bind(entry.release_year)
        .bind(entry.summary.as_deref())
        .bind(entry.thumbnail_url.as_deref())
        .bind(entry.merged_into_id.as_deref())
        .bind(created)
        .bind(updated)
        .bind(&entry.hlc)
        .execute(db.pool())
        .await
        .unwrap();

        if let Some(ref spec) = entry.collection_spec {
            sqlx::query(
                "INSERT INTO collection_specs
                 (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
                 VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&entry.id)
            .bind(spec.slug.as_deref())
            .bind(spec.mode)
            .bind(spec.role)
            .bind(spec.rule_json.as_deref())
            .bind(spec.show_in_lists)
            .bind(spec.color.as_deref())
            .execute(db.pool())
            .await
            .unwrap();
        }
        if let Some(ref st) = entry.state {
            sqlx::query(
                "INSERT INTO entry_state
                 (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at, hlc)
                 VALUES (?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&entry.id)
            .bind(st.inbox_state)
            .bind(st.read_at)
            .bind(st.progress)
            .bind(st.interaction_updated_at)
            .bind(st.interaction_updated_at.unwrap_or(updated))
            .bind(&st.hlc)
            .execute(db.pool())
            .await
            .unwrap();
        }
    }
}

async fn seed_initial_sync_edges(db: &Db, initial: &InitialSyncFixture) {
    for mem in &initial.collection_entries {
        sqlx::query(
            "INSERT INTO collection_entries (collection_id, entry_id, position, added_at, hlc)
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(&mem.collection_id)
        .bind(&mem.entry_id)
        .bind(mem.position)
        .bind(mem.added_at)
        .bind(&mem.hlc)
        .execute(db.pool())
        .await
        .unwrap();
    }
    for log in &initial.consumption_log {
        sqlx::query(
            "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&log.id)
        .bind(&log.entry_id)
        .bind(log.ts)
        .bind(log.status)
        .bind(log.notes.as_deref())
        .bind(log.created_at.unwrap_or(log.ts))
        .bind(&log.hlc)
        .execute(db.pool())
        .await
        .unwrap();
    }
    for comp in &initial.comparisons {
        let ord_json = canonical_ordering_json(&comp.ordering);
        sqlx::query(
            "INSERT INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(&comp.id)
        .bind(comp.ts)
        .bind(comp.scope_collection_id.as_deref())
        .bind(&ord_json)
        .bind(comp.created_at.unwrap_or(comp.ts))
        .bind(&comp.hlc)
        .execute(db.pool())
        .await
        .unwrap();
    }
    for gen_row in &initial.collection_generators {
        sqlx::query(
            "INSERT INTO collection_generators
             (collection_id, enabled, current_entry_id, drawn_at, updated_at, hlc)
             VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(&gen_row.collection_id)
        .bind(gen_row.enabled)
        .bind(gen_row.current_entry_id.as_deref())
        .bind(gen_row.drawn_at)
        .bind(gen_row.updated_at)
        .bind(&gen_row.hlc)
        .execute(db.pool())
        .await
        .unwrap();
    }
    for tomb in &initial.tombstones {
        sqlx::query(
            "INSERT INTO sync_tombstones (entity_type, natural_key, deleted_at, hlc)
             VALUES (?, ?, ?, ?)",
        )
        .bind(tomb.entity_type)
        .bind(&tomb.natural_key)
        .bind(tomb.deleted_at)
        .bind(&tomb.hlc)
        .execute(db.pool())
        .await
        .unwrap();
    }
}

async fn assert_expected_entries_and_states(
    db: &Db,
    name: &str,
    expected: &ExpectedSyncOutcomeFixture,
) {
    for exp_st in &expected.entry_states {
        let row: (InboxState, Option<DateTime<Utc>>, f64, String) = sqlx::query_as(
            "SELECT inbox_state, read_at, progress, hlc FROM entry_state WHERE entry_id = ?",
        )
        .bind(&exp_st.entry_id)
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(row.0, exp_st.inbox_state, "{name}: inbox_state for {}", exp_st.entry_id);
        assert_eq!(
            row.1.is_some(),
            exp_st.read_at_present,
            "{name}: read_at for {}",
            exp_st.entry_id
        );
        assert!(
            (row.2 - exp_st.progress).abs() < 1e-9,
            "{name}: progress mismatch for {} (got {}, want {})",
            exp_st.entry_id,
            row.2,
            exp_st.progress
        );
        assert_eq!(row.3, exp_st.hlc, "{name}: state hlc for {}", exp_st.entry_id);
    }
    for exp_entry in &expected.entry_presence {
        let row: Option<(Option<String>,)> =
            sqlx::query_as("SELECT merged_into_id FROM entries WHERE id = ?")
                .bind(&exp_entry.entry_id)
                .fetch_optional(db.pool())
                .await
                .unwrap();
        assert_eq!(row.is_some(), exp_entry.exists, "{name}: exists for {}", exp_entry.entry_id);
        if let Some((merged_into,)) = row {
            assert_eq!(
                merged_into, exp_entry.merged_into_id,
                "{name}: merged_into_id for {}",
                exp_entry.entry_id
            );
        }
    }
}

async fn assert_expected_graph_and_tombstones(
    db: &Db,
    name: &str,
    expected: &ExpectedSyncOutcomeFixture,
) {
    for exp_col in &expected.collection_members {
        let actual: Vec<String> = sqlx::query_scalar(
            "SELECT entry_id FROM collection_entries WHERE collection_id = ? ORDER BY entry_id ASC",
        )
        .bind(&exp_col.collection_id)
        .fetch_all(db.pool())
        .await
        .unwrap();
        assert_eq!(actual, exp_col.entry_ids, "{name}: members for {}", exp_col.collection_id);
    }
    for exp_log in &expected.consumption_log_counts {
        let count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM consumption_log WHERE entry_id = ?")
                .bind(&exp_log.entry_id)
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(count, exp_log.count, "{name}: log count for {}", exp_log.entry_id);
    }
    if !expected.comparison_orderings.is_empty() {
        let mut actual_orderings: Vec<Vec<Vec<String>>> =
            sqlx::query_scalar::<_, String>("SELECT ordering_json FROM comparisons")
                .fetch_all(db.pool())
                .await
                .unwrap()
                .into_iter()
                .map(|raw| serde_json::from_str(&raw).unwrap())
                .collect();
        actual_orderings.sort();
        let mut wanted = expected.comparison_orderings.clone();
        wanted.sort();
        assert_eq!(actual_orderings, wanted, "{name}: comparisons");
    }
    for exp_gen in &expected.generators {
        let (enabled, cur): (bool, Option<String>) = sqlx::query_as(
            "SELECT enabled, current_entry_id FROM collection_generators WHERE collection_id = ?",
        )
        .bind(&exp_gen.collection_id)
        .fetch_one(db.pool())
        .await
        .unwrap();
        assert_eq!(enabled, exp_gen.enabled, "{name}: gen enabled for {}", exp_gen.collection_id);
        assert_eq!(
            cur, exp_gen.current_entry_id,
            "{name}: gen current_entry_id for {}",
            exp_gen.collection_id
        );
    }
    for exp_tomb in &expected.tombstone_presence {
        let found: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM sync_tombstones WHERE entity_type = ? AND natural_key = ?",
        )
        .bind(exp_tomb.entity_type)
        .bind(&exp_tomb.natural_key)
        .fetch_optional(db.pool())
        .await
        .unwrap();
        assert_eq!(
            found.is_some(),
            exp_tomb.exists,
            "{name}: tombstone ({:?}, {:?})",
            exp_tomb.entity_type,
            exp_tomb.natural_key
        );
    }
}

#[tokio::test]
async fn test_cross_stack_peer_sync_merge_scenarios() {
    let scenarios = load_expanded_vectors().peer_sync_merge_scenarios;
    assert!(scenarios.len() >= 4);

    for scenario in scenarios {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        seed_initial_sync_entries(&db, &scenario.initial.entries).await;
        seed_initial_sync_edges(&db, &scenario.initial).await;

        let req = PeerSyncRequest {
            peer_id: scenario.incoming.peer_id,
            entries: scenario.incoming.entries,
            collection_entries: scenario.incoming.collection_entries,
            collection_generators: scenario.incoming.collection_generators,
            consumption_log: scenario.incoming.consumption_log,
            comparisons: scenario.incoming.comparisons,
            tombstones: scenario.incoming.tombstones,
            ..PeerSyncRequest::default()
        };
        let prepared: Vec<PreparedSyncEntry> =
            req.entries.iter().cloned().map(PreparedSyncEntry::from_pre_cleaned_record).collect();
        db.sync().merge_peer_state_prepared(&req, &prepared).await.unwrap();

        assert_expected_entries_and_states(&db, &scenario.name, &scenario.expected).await;
        assert_expected_graph_and_tombstones(&db, &scenario.name, &scenario.expected).await;
    }
}
