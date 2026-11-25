use std::{
    collections::{BTreeMap, HashMap, HashSet},
    net::SocketAddr,
    sync::Arc,
};

use axum::{
    Json,
    extract::{ConnectInfo, State, rejection::ExtensionRejection},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use roche_core::document_meta::render_inline_math_html;
use serde::{Deserialize, Serialize};
use url::Url;

use super::{
    error::{ApiError, OptionalProblemJson, ProblemJson, ProblemPath, ProblemQuery},
    items::ItemListQuery,
};
use crate::{
    content::{
        estimate_html_reading_minutes, render_summary_paragraphs_html,
        sanitize_and_harden_reader_html,
    },
    db::{
        BoardItemState, BoardPromoteAction, CatalogEntry, CollectionGeneratorState,
        CollectionSummary, ComparisonRecord, ConsumptionLogView, DiaryKindFilter, EntryDetailView,
        Feed, Item, ItemFilter, ItemPage, Tag, UpsertEntryInput, WANT_TO_READ_COLLECTION_ID,
        WANT_TO_READ_SLUG, canonicalize_sync_url,
    },
    domain::{
        CollectionRole, ConsumptionStatus, EntryKind, EntryTriageState, ItemStatusFilter, MediaType,
    },
    ranking::{MAX_FOCUSED_ROUNDS, MAX_K_DEFAULT, MIN_K},
    service::{
        AppError, BoardDismissOutcome, BoardPromoteResult, BoardReaderView, CatalogService,
        EntryListAppearance, InProgressRow, ItemDetail, RankedCollectionFilter,
        RankedCollectionView, RankedEntryRow, RocheService, WorkspaceSearchResults,
        enrich::{
            AlbumTrack, DiscographyAlbum, EnrichService, extract_album_tracks,
            extract_artist_discography, normalize_album_match_key,
        },
    },
    util::http_security::is_forwarded_https,
    web::{
        auth::{append_rank_k_set_cookie, extract_rank_k_cookie},
        links::{
            WebExternalLink, collect_web_external_links, extract_entry_tags,
            load_artist_id_by_lower_name, sanitize_non_local_href, web_primary_open_url,
        },
    },
};

const LIST_PAGE_SIZE: usize = 200;
const DIARY_PAGE_SIZE: usize = 200;
const CATALOG_SEARCH_LIMIT: usize = 80;
const LIST_PREVIEW_THUMB_LIMIT: usize = 5;
const MAX_CUSTOM_ROUNDS: usize = 50;
const MAX_COVERED_CLUSTERS: usize = 12;
const MAX_COVERED_TOKENS_PER_CLUSTER: usize = MAX_K_DEFAULT * 2;
const MAX_EXCLUDED_TOKENS: usize = 24;
const MONTH_ABBR: [&str; 12] =
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

// ---------------------------------------------------------------------------
// Inbox Rich Page (ItemPage + MathML title_html)
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
pub(super) struct InboxPagePayload {
    #[serde(flatten)]
    page: ItemPage,
    title_html: HashMap<String, String>,
}

pub(super) async fn inbox_page_get(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(query): ProblemQuery<ItemListQuery>,
) -> Result<Json<InboxPagePayload>, ApiError> {
    let page = service.items().query().with_filter(ItemFilter::from(query)).list_page().await?;
    let title_html =
        render_title_html_map(page.items.iter().map(|i| (i.id.as_str(), i.title.as_str())));
    Ok(Json(InboxPagePayload { page, title_html }))
}

// ---------------------------------------------------------------------------
// Shared Helpers
// ---------------------------------------------------------------------------

fn render_title_html_map<'a, I>(pairs: I) -> HashMap<String, String>
where
    I: IntoIterator<Item = (&'a str, &'a str)>,
{
    let mut map = HashMap::new();
    for (id, title) in pairs {
        if !map.contains_key(id) {
            map.insert(id.to_owned(), render_inline_math_html(title));
        }
    }
    map
}

async fn load_existing_reader_item_ids<'a, I>(service: &RocheService, entries: I) -> HashSet<String>
where
    I: IntoIterator<Item = &'a CatalogEntry>,
{
    let candidate_ids: Vec<String> = entries
        .into_iter()
        .filter(|e| e.kind.is_reader_openable() && e.url.is_some())
        .map(|e| e.id.clone())
        .collect();
    if candidate_ids.is_empty() {
        return HashSet::new();
    }
    service.items().existing_ids(&candidate_ids).await.unwrap_or_default()
}

// ---------------------------------------------------------------------------
// Refresh All Sources
// ---------------------------------------------------------------------------

pub(super) async fn refresh_all_sources(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let outcome = service.refresh_all_sources_in_background().await?;
    Ok(Json(serde_json::json!({
        "feeds_refreshed": outcome.feeds,
        "boards_refreshed": outcome.boards,
        "total": outcome.total,
    })))
}

// ---------------------------------------------------------------------------
// Home Dashboard
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
pub(super) struct HomePayload {
    in_progress: Vec<InProgressRow>,
    unread_items: Vec<Item>,
    unread_total: i64,
    want_to_read_items: Vec<Item>,
    want_to_read_total: usize,
    other_want_total: usize,
    active_generators: Vec<CollectionGeneratorState>,
    generator_external_links: HashMap<String, Vec<WebExternalLink>>,
    generator_tags: HashMap<String, Vec<String>>,
    recent_diary: Vec<ConsumptionLogView>,
    artist_links: HashMap<String, String>,
    diary_tiers: HashMap<String, char>,
    reader_item_ids: HashSet<String>,
    title_html: HashMap<String, String>,
    is_refreshing: bool,
}

#[allow(clippy::too_many_lines)]
pub(super) async fn home_get(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<HomePayload>, ApiError> {
    let unread_filter = ItemFilter {
        status: Some(ItemStatusFilter::Unread),
        inbox_only: Some(true),
        include_content: Some(false),
        per_page: Some(20),
        ..ItemFilter::default()
    };
    let wtr_filter = ItemFilter {
        collection_id: Some(WANT_TO_READ_COLLECTION_ID.to_owned()),
        include_content: Some(false),
        per_page: Some(12),
        ..ItemFilter::default()
    };

    let catalog = service.catalog();
    let sync = service.sync();
    let (
        active_generators_res,
        unread_page_res,
        in_progress_res,
        want_to_read_page_res,
        sync_status_res,
        recent_diary_res,
        collections_res,
    ) = tokio::join!(
        catalog.list_collection_generators(true),
        service.items().query().with_filter(unread_filter).list_page(),
        service.list_in_progress(Some(6)),
        service.items().query().with_filter(wtr_filter).list_page(),
        sync.status(),
        catalog.list_diary(None, Some(ConsumptionStatus::Consumed), 8),
        catalog.list_collections(true, false),
    );
    let active_generators = active_generators_res?;
    let unread_page = unread_page_res?;
    let in_progress_all = in_progress_res?;
    let want_to_read_page = want_to_read_page_res?;
    let sync_status = sync_status_res?;
    let recent_diary = recent_diary_res?;
    let collections = collections_res?;

    let in_progress: Vec<InProgressRow> = in_progress_all.into_iter().take(4).collect();
    let in_progress_ids: HashSet<&str> = in_progress.iter().map(InProgressRow::entry_id).collect();
    let unread_items: Vec<Item> = unread_page
        .items
        .into_iter()
        .filter(|i| !in_progress_ids.contains(i.id.as_str()))
        .collect();
    let want_to_read_items: Vec<Item> = want_to_read_page
        .items
        .into_iter()
        .filter(|i| !in_progress_ids.contains(i.id.as_str()))
        .take(6)
        .collect();

    let wtr_total = collections
        .iter()
        .find(|c| c.id == WANT_TO_READ_COLLECTION_ID)
        .map_or(want_to_read_items.len(), |c| c.member_count);
    let other_want_total: usize = collections
        .iter()
        .filter(|c| c.role == CollectionRole::Want && c.id != WANT_TO_READ_COLLECTION_ID)
        .map(|c| c.member_count)
        .sum();

    let mut home_creators: Vec<String> = active_generators
        .iter()
        .filter_map(|g| g.current_entry.as_ref())
        .flat_map(|e| e.creators.iter().cloned())
        .collect();
    home_creators.extend(recent_diary.iter().flat_map(|d| d.entry.creators.iter().cloned()));
    home_creators.extend(in_progress.iter().flat_map(|r| r.creators().iter().cloned()));
    let diary_entries: Vec<CatalogEntry> = recent_diary.iter().map(|d| d.entry.clone()).collect();
    let started_catalog_entries: Vec<&CatalogEntry> = in_progress
        .iter()
        .filter_map(|r| match r {
            InProgressRow::Catalog { entry, .. } => Some(entry),
            InProgressRow::Item { .. } => None,
        })
        .collect();
    let (artist_links, diary_tiers, reader_item_ids) = tokio::join!(
        load_artist_id_by_lower_name(&service, &home_creators),
        catalog.load_tiers_for_entries(&diary_entries),
        load_existing_reader_item_ids(&service, started_catalog_entries),
    );

    let mut generator_external_links = HashMap::new();
    let mut generator_tags = HashMap::new();
    for g in &active_generators {
        if let Some(ref entry) = g.current_entry {
            generator_external_links
                .insert(entry.id.clone(), collect_web_external_links(entry, None));
            generator_tags.insert(entry.id.clone(), extract_entry_tags(entry));
        }
    }

    let mut title_pairs: Vec<(&str, &str)> = Vec::new();
    for row in &in_progress {
        title_pairs.push((row.entry_id(), row.title()));
    }
    for item in &unread_items {
        title_pairs.push((&item.id, &item.title));
    }
    for item in &want_to_read_items {
        title_pairs.push((&item.id, &item.title));
    }
    for g in &active_generators {
        if let Some(ref e) = g.current_entry {
            title_pairs.push((&e.id, &e.title));
        }
    }
    for d in &recent_diary {
        title_pairs.push((&d.entry.id, &d.entry.title));
    }
    let title_html = render_title_html_map(title_pairs);

    Ok(Json(HomePayload {
        in_progress,
        unread_items,
        unread_total: sync_status.unread_items_count,
        want_to_read_items,
        want_to_read_total: wtr_total,
        other_want_total,
        active_generators,
        generator_external_links,
        generator_tags,
        recent_diary,
        artist_links,
        diary_tiers,
        reader_item_ids,
        title_html,
        is_refreshing: sync_status.is_refreshing,
    }))
}

// ---------------------------------------------------------------------------
// Global Search (Rich)
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub(super) struct RichSearchQuery {
    #[serde(default)]
    q: String,
    #[serde(default)]
    limit: Option<usize>,
}

#[derive(Debug, Serialize)]
pub(super) struct RichSearchPayload {
    results: WorkspaceSearchResults,
    artist_links: HashMap<String, String>,
    reader_item_ids: HashSet<String>,
    title_html: HashMap<String, String>,
}

pub(super) async fn search_rich_get(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(q): ProblemQuery<RichSearchQuery>,
) -> Result<Json<RichSearchPayload>, ApiError> {
    let trimmed = q.q.trim();
    if trimmed.is_empty() {
        return Ok(Json(RichSearchPayload {
            results: WorkspaceSearchResults {
                query: String::new(),
                catalog_entries: Vec::new(),
                collections: Vec::new(),
                want_entries: Vec::new(),
                inbox_items: Vec::new(),
                diary_logs: Vec::new(),
            },
            artist_links: HashMap::new(),
            reader_item_ids: HashSet::new(),
            title_html: HashMap::new(),
        }));
    }
    let results = service.search_workspace(trimmed, q.limit.unwrap_or(24)).await?;
    let mut creators: Vec<String> = Vec::new();
    creators.extend(results.catalog_entries.iter().flat_map(|e| e.creators.iter().cloned()));
    creators.extend(results.want_entries.iter().flat_map(|w| w.entry.creators.iter().cloned()));
    creators.extend(results.diary_logs.iter().flat_map(|d| d.entry.creators.iter().cloned()));
    let (artist_links, reader_item_ids) = tokio::join!(
        load_artist_id_by_lower_name(&service, &creators),
        load_existing_reader_item_ids(&service, results.want_entries.iter().map(|w| &w.entry)),
    );
    let mut pairs: Vec<(&str, &str)> = Vec::new();
    for e in &results.catalog_entries {
        pairs.push((&e.id, &e.title));
    }
    for w in &results.want_entries {
        pairs.push((&w.entry.id, &w.entry.title));
    }
    for i in &results.inbox_items {
        pairs.push((&i.id, &i.title));
    }
    for d in &results.diary_logs {
        pairs.push((&d.entry.id, &d.entry.title));
    }
    let title_html = render_title_html_map(pairs);
    Ok(Json(RichSearchPayload { results, artist_links, reader_item_ids, title_html }))
}

// ---------------------------------------------------------------------------
// Item Reader, Audio, Finish & Unfinish
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub(super) struct ItemReaderQuery {
    #[serde(default)]
    view: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct ReaderDiscussionLink {
    label: String,
    url: String,
}

#[derive(Debug, Serialize)]
pub(super) struct ItemReaderPayload {
    detail: ItemDetail,
    feed: Option<Feed>,
    title_html: String,
    summary_html: Option<String>,
    content_html: Option<String>,
    reading_minutes: Option<usize>,
    triage_state: EntryTriageState,
    is_consumed: bool,
    can_rank: bool,
    rank_slug: Option<String>,
    entry_release_year: Option<i32>,
    entry_arxiv_id: Option<String>,
    pdf_media_id: Option<String>,
    video_media_id: Option<String>,
    audio_media_id: Option<String>,
    pdf_mode: Option<&'static str>,
    pdf_unextracted: bool,
    discussions: Vec<ReaderDiscussionLink>,
}

fn extract_item_discussions(item: &Item) -> Vec<ReaderDiscussionLink> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for disc in &item.discussions {
        let Some(safe_href) = sanitize_non_local_href(&disc.url) else {
            continue;
        };
        if !seen.insert(canonicalize_sync_url(safe_href)) {
            continue;
        }
        let label = disc.label.as_deref().map(str::trim).filter(|s| !s.is_empty()).map_or_else(
            || {
                let src = disc.source.trim();
                if src.is_empty() {
                    "Discussion".to_owned()
                } else {
                    roche_core::external_links::external_identifier_label(src).to_owned()
                }
            },
            ToOwned::to_owned,
        );
        out.push(ReaderDiscussionLink { label, url: safe_href.to_owned() });
    }
    out
}

#[allow(clippy::too_many_lines)]
pub(super) async fn item_reader_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<ItemReaderQuery>,
) -> Result<Json<ItemReaderPayload>, ApiError> {
    let mut detail = service.items().get(&id).await?;
    let feed = if let Some(ref fid) = detail.item.feed_id {
        service.feeds().get(fid).await.ok()
    } else {
        None
    };

    let (is_consumed, rank_slug, can_rank, entry_release_year, entry_arxiv_id) =
        match service.catalog().get_entry_detail(&id).await {
            Ok(Some(ed)) => {
                let consumed = ed.has_consumed_log
                    || ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed);
                let slug = if consumed {
                    service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
                } else {
                    None
                };
                let rankable = slug.is_some()
                    || ed.entry.kind.default_list_slug().is_some()
                    || ed.parents.iter().any(|p| p.kind == EntryKind::Collection);
                let arxiv_id = ed.entry.identifier("arxiv").map(str::to_owned);
                (consumed, slug, rankable, ed.entry.release_year, arxiv_id)
            }
            _ => (false, None, false, None, None),
        };

    let triage_state = if is_consumed || detail.item.is_finished {
        EntryTriageState::Finished
    } else {
        detail.item.triage_state()
    };

    let requested_view = q.view.as_deref().map(str::trim);
    let pdf_media_id =
        detail.media.iter().find(|m| m.media_type == MediaType::Pdf).map(|m| m.id.clone());
    let video_media_id =
        detail.media.iter().find(|m| m.media_type == MediaType::Video).map(|m| m.id.clone());
    let audio_media_id =
        detail.media.iter().find(|m| m.media_type == MediaType::Audio).map(|m| m.id.clone());
    let has_local_pdf = pdf_media_id.is_some();

    let mut raw_content_html =
        detail.item.content_html.as_deref().filter(|s| !s.trim().is_empty()).map(ToOwned::to_owned);
    let pdf_unextracted =
        raw_content_html.is_none() && has_local_pdf && requested_view == Some("pdf");
    if raw_content_html.is_none()
        && has_local_pdf
        && !pdf_unextracted
        && let Some(extracted) = service
            .enrich()
            .ensure_paper_reader_html(&id, entry_arxiv_id.as_deref(), Some(&detail.item.title))
            .await
    {
        raw_content_html = Some(extracted);
    }

    let reading_minutes = raw_content_html
        .as_deref()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| detail.item.content_html.as_deref().filter(|s| !s.trim().is_empty()))
        .or_else(|| detail.item.summary.as_deref().filter(|s| !s.trim().is_empty()))
        .and_then(estimate_html_reading_minutes);

    let content_html = if let Some(raw_html) = raw_content_html {
        let item_url = detail.item.url.clone();
        let item_title = detail.item.title.clone();
        let rendered = tokio::task::spawn_blocking(move || {
            sanitize_and_harden_reader_html(&raw_html, Some(&item_url), Some(&item_title))
        })
        .await
        .map_err(|e| ApiError::Internal(anyhow::anyhow!("Reader HTML render task failed: {e}")))?;
        (!rendered.trim().is_empty()).then_some(rendered)
    } else {
        None
    };

    // Strip pre-hardened raw HTML from the nested Item payload so clients only ever receive
    // sanitized+hardened prose HTML once via top-level `content_html`.
    detail.item.content_html = None;

    let pdf_mode = has_local_pdf.then(|| match requested_view {
        Some("pdf") => "pdf",
        Some("split") => "split",
        Some("extracted") => "extracted",
        _ => {
            if content_html.is_some() {
                "extracted"
            } else {
                "pdf"
            }
        }
    });

    let title_html = render_inline_math_html(&detail.item.title);
    let summary_html = detail.item.summary.as_deref().and_then(render_summary_paragraphs_html);
    let discussions = extract_item_discussions(&detail.item);

    Ok(Json(ItemReaderPayload {
        detail,
        feed,
        title_html,
        summary_html,
        content_html,
        reading_minutes,
        triage_state,
        is_consumed,
        can_rank,
        rank_slug,
        entry_release_year,
        entry_arxiv_id,
        pdf_media_id,
        video_media_id,
        audio_media_id,
        pdf_mode,
        pdf_unextracted,
        discussions,
    }))
}

pub(super) async fn item_audio_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let detail = service.items().get(&id).await?;
    if let Some(local_audio) = detail.media.iter().find(|m| m.media_type == MediaType::Audio)
        && let Ok(resp) =
            crate::util::http_media::serve_media_stream(&service, &local_audio.id, &headers).await
    {
        return Ok(resp);
    }
    if let Some(ext_audio) = detail.item.audio_url.as_deref().and_then(sanitize_non_local_href) {
        let mut resp = StatusCode::SEE_OTHER.into_response();
        if let Ok(val) = header::HeaderValue::from_str(ext_audio) {
            resp.headers_mut().insert(header::LOCATION, val);
            resp.headers_mut()
                .insert(header::REFERRER_POLICY, header::HeaderValue::from_static("no-referrer"));
            return Ok(resp);
        }
    }
    Err(ApiError::not_found("Audio stream not found for this item"))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct FinishItemRequest {
    #[serde(default)]
    notes: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct FinishItemResponse {
    item: ItemDetail,
    has_feed: bool,
    rank_slug: Option<String>,
}

pub(super) async fn item_finish_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    OptionalProblemJson(req): OptionalProblemJson<FinishItemRequest>,
) -> Result<Json<FinishItemResponse>, ApiError> {
    let finished = service.items().finish_reader_item(&id, req.notes.as_deref()).await?;
    let rank_slug = service
        .catalog()
        .rank_target_after_consumption(&finished.logged.entry)
        .await
        .ok()
        .flatten();
    let item = service.items().get(&id).await?;
    Ok(Json(FinishItemResponse { item, has_feed: finished.has_feed, rank_slug }))
}

#[derive(Debug, Deserialize)]
pub(super) struct UnfinishItemRequest {
    #[serde(default = "default_true")]
    requeue: bool,
}

const fn default_true() -> bool {
    true
}

impl Default for UnfinishItemRequest {
    fn default() -> Self {
        Self { requeue: true }
    }
}

pub(super) async fn item_unfinish_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    OptionalProblemJson(req): OptionalProblemJson<UnfinishItemRequest>,
) -> Result<Json<ItemDetail>, ApiError> {
    service.items().unfinish_reader_item_with_options(&id, req.requeue).await?;
    let item = service.items().get(&id).await?;
    Ok(Json(item))
}

// ---------------------------------------------------------------------------
// Boards Rich Reader & Mutation Views
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
pub(super) struct BoardReaderRichPayload {
    view: BoardReaderView,
    title_html: String,
    content_html: Option<String>,
    fallback_summary_html: Option<String>,
    reading_minutes: Option<usize>,
    tags: Vec<Tag>,
    can_rank: bool,
    rank_slug: Option<String>,
}

pub(super) async fn board_reader_view_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath((board_id, external_id)): ProblemPath<(String, String)>,
) -> Result<Json<BoardReaderRichPayload>, ApiError> {
    let mut view = service.boards().open_reader(&board_id, &external_id).await?;

    let default_kind = EntryKind::Article;
    let (tags, rank_slug, can_rank) = if let Some(ref linked) = view.linked_entry {
        let detail_tags = service.items().get(&linked.id).await.ok().map_or_default(|d| d.tags);
        let (slug, rankable) = match service.catalog().get_entry_detail(&linked.id).await {
            Ok(Some(ed)) => {
                let consumed = ed.has_consumed_log
                    || ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed);
                let slug = if consumed {
                    service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
                } else {
                    None
                };
                let rankable = slug.is_some()
                    || ed.entry.kind.default_list_slug().is_some()
                    || ed.parents.iter().any(|p| p.kind == EntryKind::Collection);
                (slug, rankable)
            }
            _ => (None, default_kind.default_list_slug().is_some()),
        };
        (detail_tags, slug, rankable)
    } else {
        (Vec::new(), None, default_kind.default_list_slug().is_some())
    };

    let content_html = if let Some(raw_html) =
        view.content_html.take().filter(|s| !s.trim().is_empty())
    {
        let parsed_url = Url::parse(&view.item.url).ok();
        let item_title = view.item.title.clone();
        let rendered = tokio::task::spawn_blocking(move || {
            sanitize_and_harden_reader_html(&raw_html, parsed_url.as_ref(), Some(&item_title))
        })
        .await
        .map_err(|e| ApiError::Internal(anyhow::anyhow!("Board reader HTML render failed: {e}")))?;
        (!rendered.trim().is_empty()).then_some(rendered)
    } else {
        None
    };

    let reading_minutes = content_html.as_deref().and_then(estimate_html_reading_minutes);
    let title_html = render_inline_math_html(&view.item.title);
    let fallback_summary_html = view
        .extracted_summary
        .as_deref()
        .or(view.item.summary.as_deref())
        .and_then(render_summary_paragraphs_html);

    Ok(Json(BoardReaderRichPayload {
        view,
        title_html,
        content_html,
        fallback_summary_html,
        reading_minutes,
        tags,
        can_rank,
        rank_slug,
    }))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct BoardDismissViewRequest {
    #[serde(default)]
    backfill_offset: Option<usize>,
}

#[derive(Debug, Serialize)]
pub(super) struct BoardDismissViewResponse {
    outcome: BoardDismissOutcome,
    visible_count: i64,
    dismissed_count: i64,
}

pub(super) async fn board_dismiss_view_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath((board_id, external_id)): ProblemPath<(String, String)>,
    OptionalProblemJson(req): OptionalProblemJson<BoardDismissViewRequest>,
) -> Result<Json<BoardDismissViewResponse>, ApiError> {
    let outcome = service.boards().dismiss(&board_id, &external_id, req.backfill_offset).await?;
    let board = service.boards().get(&board_id).await?;
    Ok(Json(BoardDismissViewResponse {
        outcome,
        visible_count: board.visible_count.max(0),
        dismissed_count: (board.item_count - board.visible_count).max(0),
    }))
}

#[derive(Debug, Serialize)]
pub(super) struct BoardUndismissViewResponse {
    state: BoardItemState,
    visible_count: i64,
    dismissed_count: i64,
}

pub(super) async fn board_undismiss_view_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath((board_id, external_id)): ProblemPath<(String, String)>,
) -> Result<Json<BoardUndismissViewResponse>, ApiError> {
    let state = service.boards().undismiss(&board_id, &external_id).await?;
    let board = service.boards().get(&board_id).await?;
    Ok(Json(BoardUndismissViewResponse {
        state,
        visible_count: board.visible_count.max(0),
        dismissed_count: (board.item_count - board.visible_count).max(0),
    }))
}

#[derive(Debug, Serialize)]
pub(super) struct BoardPromoteViewResponse {
    result: BoardPromoteResult,
    rank_slug: Option<String>,
}

pub(super) async fn board_promote_view_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath((board_id, external_id)): ProblemPath<(String, String)>,
    ProblemJson(action): ProblemJson<BoardPromoteAction>,
) -> Result<Json<BoardPromoteViewResponse>, ApiError> {
    let is_finish = matches!(action, BoardPromoteAction::Finish { .. });
    let result = service.boards().promote(&board_id, &external_id, action).await?;
    let rank_slug = if is_finish
        && let Ok(Some(ed)) = service.catalog().get_entry_detail(&result.entry_id).await
    {
        service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
    } else {
        None
    };
    Ok(Json(BoardPromoteViewResponse { result, rank_slug }))
}

// ---------------------------------------------------------------------------
// Lists Overview & Detail
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub(super) struct ListsOverviewQuery {
    #[serde(default)]
    q: Option<String>,
    #[serde(default)]
    kind: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct ListsOverviewPayload {
    collections: Vec<CollectionSummary>,
    dynamic_collection_ids: HashSet<String>,
    previews: HashMap<String, Vec<(String, String)>>,
    generators: HashMap<String, CollectionGeneratorState>,
    generator_external_links: HashMap<String, Vec<WebExternalLink>>,
    search_entries: Option<Vec<CatalogEntry>>,
    search_truncated: bool,
    invalid_kind: Option<String>,
    artist_links: HashMap<String, String>,
    title_html: HashMap<String, String>,
}

pub(super) async fn lists_overview_get(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(query): ProblemQuery<ListsOverviewQuery>,
) -> Result<Json<ListsOverviewPayload>, ApiError> {
    let collections = service.catalog().list_collections(false, false).await?;
    let clean_q = query.q.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let raw_kind = query.kind.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let (parsed_kind, invalid_kind) =
        raw_kind.map_or((None, None), |raw| match EntryKind::parse_flexible(raw) {
            Ok(k) if k != EntryKind::Collection => (Some(k), None),
            _ => (None, Some(raw.to_owned())),
        });
    let is_searching = clean_q.is_some() || parsed_kind.is_some() || invalid_kind.is_some();

    let (search_entries, search_truncated, mut creators) = if invalid_kind.is_some() {
        (Some(Vec::new()), false, Vec::new())
    } else if is_searching {
        let fetch_limit = if parsed_kind.is_none() { 500 } else { CATALOG_SEARCH_LIMIT + 1 };
        let non_collections: Vec<CatalogEntry> = service
            .catalog()
            .search_entries(clean_q, parsed_kind, fetch_limit)
            .await?
            .into_iter()
            .filter(|e| e.kind != EntryKind::Collection)
            .take(CATALOG_SEARCH_LIMIT + 1)
            .collect();
        let truncated = non_collections.len() > CATALOG_SEARCH_LIMIT;
        let matched: Vec<CatalogEntry> =
            non_collections.into_iter().take(CATALOG_SEARCH_LIMIT).collect();
        let search_creators: Vec<String> =
            matched.iter().flat_map(|e| e.creators.iter().cloned()).collect();
        (Some(matched), truncated, search_creators)
    } else {
        (None, false, Vec::new())
    };

    let previews = service
        .catalog()
        .load_collection_preview_thumbnails(&collections, LIST_PREVIEW_THUMB_LIMIT)
        .await
        .unwrap_or_default();

    let generators: HashMap<String, CollectionGeneratorState> =
        if collections.iter().any(|c| c.has_generator) {
            service
                .catalog()
                .list_collection_generators(false)
                .await
                .unwrap_or_default()
                .into_iter()
                .map(|st| (st.collection_id.clone(), st))
                .collect()
        } else {
            HashMap::new()
        };

    let mut generator_external_links = HashMap::new();
    for g in generators.values() {
        if let Some(ref e) = g.current_entry {
            creators.extend(e.creators.iter().cloned());
            generator_external_links.insert(e.id.clone(), collect_web_external_links(e, None));
        }
    }
    let artist_links = load_artist_id_by_lower_name(&service, &creators).await;

    let mut pairs: Vec<(&str, &str)> = Vec::new();
    if let Some(ref matched) = search_entries {
        for e in matched {
            pairs.push((&e.id, &e.title));
        }
    }
    for g in generators.values() {
        if let Some(ref e) = g.current_entry {
            pairs.push((&e.id, &e.title));
        }
    }
    let title_html = render_title_html_map(pairs);

    let dynamic_collection_ids: HashSet<String> =
        collections.iter().filter(|c| c.is_dynamic()).map(|c| c.id.clone()).collect();

    Ok(Json(ListsOverviewPayload {
        collections,
        dynamic_collection_ids,
        previews,
        generators,
        generator_external_links,
        search_entries,
        search_truncated,
        invalid_kind,
        artist_links,
        title_html,
    }))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ListDetailQuery {
    #[serde(default)]
    page: Option<usize>,
    #[serde(default)]
    tier: Option<String>,
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    q: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct ListDetailPayload {
    view: RankedCollectionView,
    is_dynamic: bool,
    total_matching: usize,
    full_total: usize,
    full_tier_counts: [usize; 5],
    kind_counts: Vec<(EntryKind, usize)>,
    page_num: usize,
    total_pages: usize,
    reader_item_ids: HashSet<String>,
    artist_links: HashMap<String, String>,
    generator: Option<CollectionGeneratorState>,
    generator_external_links: Vec<WebExternalLink>,
    generator_tags: Vec<String>,
    title_html: HashMap<String, String>,
}

pub(super) async fn list_detail_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<ListDetailQuery>,
) -> Result<Json<ListDetailPayload>, ApiError> {
    let active_tier = q
        .tier
        .as_deref()
        .map(str::trim)
        .filter(|s| s.len() == 1)
        .and_then(|s| s.chars().next())
        .map(|c| c.to_ascii_uppercase())
        .filter(|c| matches!(c, 'S' | 'A' | 'B' | 'C' | 'D'));
    let active_kind = q
        .kind
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .and_then(|k| EntryKind::parse_flexible(k).ok());
    let active_q = q.q.as_deref().map(str::trim).filter(|s| !s.is_empty());

    let filter = RankedCollectionFilter { tier: active_tier, kind: active_kind, query: active_q };
    let page = service
        .catalog()
        .get_ranked_collection_page(
            &slug,
            false,
            &filter,
            q.page.unwrap_or(1),
            LIST_PAGE_SIZE,
            true,
        )
        .await?;

    let generator = if page.view.collection.has_generator {
        let gen_key = page.view.collection.slug.as_deref().unwrap_or(&page.view.collection.id);
        service.catalog().get_collection_generator(gen_key).await.ok()
    } else {
        None
    };

    let mut page_creators: Vec<String> =
        page.view.rows.iter().flat_map(|r| r.entry.creators.iter().cloned()).collect();
    let (generator_external_links, generator_tags) =
        generator.as_ref().and_then(|gs| gs.current_entry.as_ref()).map_or_else(
            || (Vec::new(), Vec::new()),
            |drawn| {
                page_creators.extend(drawn.creators.iter().cloned());
                (collect_web_external_links(drawn, None), extract_entry_tags(drawn))
            },
        );

    let (artist_links, reader_item_ids) = tokio::join!(
        load_artist_id_by_lower_name(&service, &page_creators),
        load_existing_reader_item_ids(&service, page.view.rows.iter().map(|r| &r.entry)),
    );

    let mut pairs: Vec<(&str, &str)> =
        page.view.rows.iter().map(|r| (r.entry.id.as_str(), r.entry.title.as_str())).collect();
    if let Some(drawn) = generator.as_ref().and_then(|gs| gs.current_entry.as_ref()) {
        pairs.push((&drawn.id, &drawn.title));
    }
    let title_html = render_title_html_map(pairs);

    let is_dynamic = page.view.collection.is_dynamic();
    Ok(Json(ListDetailPayload {
        view: page.view,
        is_dynamic,
        total_matching: page.total_matching,
        full_total: page.full_total,
        full_tier_counts: page.full_tier_counts,
        kind_counts: page.kind_counts,
        page_num: page.page_num,
        total_pages: page.total_pages,
        reader_item_ids,
        artist_links,
        generator,
        generator_external_links,
        generator_tags,
        title_html,
    }))
}

#[derive(Debug, Deserialize)]
pub(super) struct ResolveAddListEntryRequest {
    title_or_id: String,
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    creators: Vec<String>,
    #[serde(default)]
    release_year: Option<i32>,
}

#[derive(Debug, Serialize)]
pub(super) struct ResolveAddListEntryResponse {
    entry: CatalogEntry,
    linked_existing: bool,
}

pub(super) async fn list_add_resolved_entry(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug): ProblemPath<String>,
    ProblemJson(req): ProblemJson<ResolveAddListEntryRequest>,
) -> Result<(StatusCode, Json<ResolveAddListEntryResponse>), ApiError> {
    let query = req.title_or_id.trim();
    if query.is_empty() {
        return Err(ApiError::bad_request("Title cannot be empty"));
    }
    let parsed_kind = req
        .kind
        .as_deref()
        .map(str::trim)
        .filter(|k| !k.is_empty())
        .and_then(|k| EntryKind::parse_flexible(k).ok());
    let creators: Vec<String> =
        req.creators.into_iter().map(|c| c.trim().to_owned()).filter(|c| !c.is_empty()).collect();
    let is_explicit_ref = uuid::Uuid::parse_str(query).is_ok()
        || query.contains("://")
        || query.contains(':')
        || query.contains('=');
    let can_fuzzy_resolve = is_explicit_ref || (creators.is_empty() && req.release_year.is_none());

    let (entry, linked_existing) = if can_fuzzy_resolve
        && let Ok(existing) = service.catalog().resolve_entry(query, parsed_kind, None).await
    {
        (existing, true)
    } else {
        let kind = match parsed_kind {
            Some(k) => k,
            None => service
                .catalog()
                .infer_dominant_kind_in_collection(&slug)
                .await
                .ok()
                .flatten()
                .unwrap_or(EntryKind::Movie),
        };
        let input = UpsertEntryInput {
            kind: Some(kind),
            title: query.to_owned(),
            creators,
            release_year: req.release_year,
            ..UpsertEntryInput::default()
        };
        (service.catalog().upsert_entry(&input).await?, false)
    };

    service.catalog().add_collection_entry(&slug, &entry.id, None).await?;
    Ok((StatusCode::CREATED, Json(ResolveAddListEntryResponse { entry, linked_existing })))
}

// ---------------------------------------------------------------------------
// Entry Detail (Rich), Refresh Metadata & Queue Toggle
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub(super) struct EntryDetailRichQuery {
    #[serde(default)]
    merge_preview: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct EntryParentListLink {
    id: String,
    slug: String,
    title: String,
    has_generator: bool,
    is_editable_manual: bool,
}

#[derive(Debug, Serialize)]
pub(super) struct EntryDetailRichPayload {
    detail: EntryDetailView,
    needs_enrich: bool,
    can_refresh: bool,
    appearances: Vec<EntryListAppearance>,
    child_tiers: HashMap<String, char>,
    has_reader_item: bool,
    artist_links: HashMap<String, String>,
    external_links: Vec<WebExternalLink>,
    tags: Vec<String>,
    tracks: Vec<AlbumTrack>,
    external_discography: Vec<DiscographyAlbum>,
    want_queue_slug: Option<String>,
    parent_lists: Vec<EntryParentListLink>,
    available_manual_lists: Vec<CollectionSummary>,
    title_html: String,
    summary_html: Option<String>,
    child_title_html: HashMap<String, String>,
    merge_preview: Option<EntryDetailView>,
    merge_preview_error: Option<String>,
}

#[allow(clippy::too_many_lines)]
pub(super) async fn entry_detail_rich_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<EntryDetailRichQuery>,
) -> Result<Json<EntryDetailRichPayload>, ApiError> {
    let detail = service
        .catalog()
        .get_entry_detail(&id)
        .await?
        .ok_or_else(|| ApiError::not_found(format!("Entry '{id}' not found")))?;

    let needs_enrich = service.enrich().needs_enrichment(&detail);
    let can_refresh = EnrichService::can_manual_refresh(&detail);

    let mut relevant_creators: Vec<String> = detail.entry.creators.clone();
    relevant_creators.extend(detail.children.iter().flat_map(|c| c.creators.iter().cloned()));
    relevant_creators.extend(detail.parents.iter().flat_map(|p| p.creators.iter().cloned()));

    let catalog = service.catalog();
    let items = service.items();
    let (artist_links, appearances, collections_res, reader_ids_res, child_tiers) = tokio::join!(
        load_artist_id_by_lower_name(&service, &relevant_creators),
        catalog.load_entry_ranked_appearances(&detail),
        catalog.list_collections(true, false),
        items.existing_ids(std::slice::from_ref(&detail.entry.id)),
        catalog.load_tiers_for_entries(&detail.children),
    );
    let all_lists = collections_res.unwrap_or_default();
    let list_by_id: HashMap<&str, &CollectionSummary> =
        all_lists.iter().map(|c| (c.id.as_str(), c)).collect();

    let want_queue_slug =
        detail.parents.iter().filter(|p| p.kind == EntryKind::Collection).find_map(|p| {
            let col = list_by_id.get(p.id.as_str())?;
            (col.role == CollectionRole::Want)
                .then(|| col.slug.clone().unwrap_or_else(|| col.id.clone()))
        });

    let parent_lists: Vec<EntryParentListLink> = detail
        .parents
        .iter()
        .filter(|p| p.kind == EntryKind::Collection)
        .filter_map(|p| {
            let col = list_by_id.get(p.id.as_str())?;
            if !matches!(col.role, CollectionRole::List | CollectionRole::System) {
                return None;
            }
            Some(EntryParentListLink {
                id: col.id.clone(),
                slug: col.slug.clone().unwrap_or_else(|| col.id.clone()),
                title: col.title.clone(),
                has_generator: col.has_generator,
                is_editable_manual: col.is_manual()
                    && !col.has_generator
                    && col.role == CollectionRole::List,
            })
        })
        .collect();
    let joined_list_ids: HashSet<&str> = parent_lists.iter().map(|p| p.id.as_str()).collect();
    let available_manual_lists: Vec<CollectionSummary> = all_lists
        .into_iter()
        .filter(|c| {
            c.is_manual()
                && !c.has_generator
                && c.role == CollectionRole::List
                && !joined_list_ids.contains(c.id.as_str())
        })
        .collect();

    let (merge_preview, merge_preview_error) = if let Some(raw_target) =
        q.merge_preview.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        match catalog.resolve_entry(raw_target, None, None).await {
            Ok(target) if target.id == detail.entry.id => {
                (None, Some("Cannot merge an entry into itself.".to_owned()))
            }
            Ok(target) => match catalog.get_entry_detail(&target.id).await {
                Ok(Some(td)) => (Some(td), None),
                _ => (None, Some(format!("Could not load canonical target '{}'.", target.title))),
            },
            Err(err) => {
                let safe_err = match &err {
                    AppError::NotFound(m) | AppError::BadRequest(m) => {
                        format!("Could not find target '{raw_target}': {m}")
                    }
                    AppError::AmbiguousLookup { detail, .. } => detail.clone(),
                    _ => {
                        tracing::error!(error = %err, target = %raw_target, "merge preview lookup failed");
                        "Unable to resolve merge target right now.".to_owned()
                    }
                };
                (None, Some(safe_err))
            }
        }
    } else {
        (None, None)
    };

    let has_reader_item = reader_ids_res.is_ok_and(|ids| ids.contains(&detail.entry.id));
    let tracks = extract_album_tracks(&detail);
    let external_links = collect_web_external_links(&detail.entry, None);
    let tags = extract_entry_tags(&detail.entry);

    // Filter external iTunes discography against already-owned children using normalized match key.
    let owned_keys: HashSet<String> =
        detail.children.iter().map(|c| normalize_album_match_key(&c.title)).collect();
    let external_discography: Vec<DiscographyAlbum> = if detail.entry.kind == EntryKind::Artist {
        extract_artist_discography(&detail.entry)
            .into_iter()
            .filter(|a| !owned_keys.contains(&normalize_album_match_key(&a.title)))
            .collect()
    } else {
        Vec::new()
    };

    let title_html = render_inline_math_html(&detail.entry.title);
    let summary_html = detail.entry.summary.as_deref().and_then(render_summary_paragraphs_html);
    let child_title_html =
        render_title_html_map(detail.children.iter().map(|c| (c.id.as_str(), c.title.as_str())));

    Ok(Json(EntryDetailRichPayload {
        detail,
        needs_enrich,
        can_refresh,
        appearances,
        child_tiers,
        has_reader_item,
        artist_links,
        external_links,
        tags,
        tracks,
        external_discography,
        want_queue_slug,
        parent_lists,
        available_manual_lists,
        title_html,
        summary_html,
        child_title_html,
        merge_preview,
        merge_preview_error,
    }))
}

pub(super) async fn entry_refresh_metadata_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<EntryDetailView>, ApiError> {
    let updated = service.enrich().refresh_entry_metadata_now(&id).await?;
    Ok(Json(updated))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct EntryQueueToggleRequest {
    #[serde(default)]
    queue: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct EntryQueueToggleResponse {
    in_queue: bool,
    queue_slug: String,
    queue_title: String,
}

pub(super) async fn entry_queue_toggle_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    OptionalProblemJson(req): OptionalProblemJson<EntryQueueToggleRequest>,
) -> Result<Json<EntryQueueToggleResponse>, ApiError> {
    let detail = service
        .catalog()
        .get_entry_detail(&id)
        .await?
        .ok_or_else(|| ApiError::not_found(format!("Entry '{id}' not found")))?;
    let all_lists = service.catalog().list_collections(true, false).await?;
    let list_by_id: HashMap<&str, &CollectionSummary> =
        all_lists.iter().map(|c| (c.id.as_str(), c)).collect();

    let existing_want =
        detail.parents.iter().filter(|p| p.kind == EntryKind::Collection).find_map(|p| {
            let col = list_by_id.get(p.id.as_str())?;
            (col.role == CollectionRole::Want).then_some(*col)
        });

    if let Some(col) = existing_want
        && req.queue.as_deref().is_none_or(|q| {
            let slug = col.slug.as_deref().unwrap_or(&col.id);
            q.eq_ignore_ascii_case(slug) || q == col.id
        })
    {
        let slug = col.slug.clone().unwrap_or_else(|| col.id.clone());
        let _ = service.catalog().unwant_entry(&slug, &id).await?;
        return Ok(Json(EntryQueueToggleResponse {
            in_queue: false,
            queue_slug: slug,
            queue_title: col.title.clone(),
        }));
    }

    let target_queue_slug =
        req.queue.as_deref().map(str::trim).filter(|s| !s.is_empty()).map_or_else(
            || detail.entry.kind.default_want_slug().unwrap_or(WANT_TO_READ_SLUG).to_owned(),
            ToOwned::to_owned,
        );
    let queue_col = service.catalog().get_or_create_want_collection(&target_queue_slug).await?;
    service.catalog().add_collection_entry(&queue_col.id, &id, None).await?;
    Ok(Json(EntryQueueToggleResponse {
        in_queue: true,
        queue_slug: queue_col.slug.unwrap_or(queue_col.id),
        queue_title: queue_col.title,
    }))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct EntryLogRequest {
    status: String,
    #[serde(default)]
    notes: Option<String>,
    #[serde(default)]
    rank_after: bool,
}

#[derive(Debug, Serialize)]
pub(super) struct EntryLogResponse {
    logged: ConsumptionLogView,
    rank_slug: Option<String>,
}

pub(super) async fn entry_log_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<EntryLogRequest>,
) -> Result<Json<EntryLogResponse>, ApiError> {
    let status: ConsumptionStatus =
        req.status.parse().map_err(|e: anyhow::Error| ApiError::bad_request(e.to_string()))?;
    let logged = if status == ConsumptionStatus::Consumed
        && service.items().existing_ids(std::slice::from_ref(&id)).await?.contains(&id)
    {
        service.items().finish_reader_item(&id, req.notes.as_deref()).await?.logged
    } else {
        service.catalog().log_consumption(&id, status, req.notes.as_deref(), None).await?
    };
    let rank_slug = if status == ConsumptionStatus::Consumed && req.rank_after {
        service.catalog().rank_target_after_consumption(&logged.entry).await.ok().flatten()
    } else {
        None
    };
    Ok(Json(EntryLogResponse { logged, rank_slug }))
}

// ---------------------------------------------------------------------------
// Diary Page, Queues, Quick Log & Want Complete
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub(super) struct DiaryPageQuery {
    #[serde(default)]
    year: Option<i32>,
    #[serde(default)]
    status: Option<String>,
    #[serde(default)]
    kind: Option<String>,
    #[serde(default)]
    page: Option<usize>,
}

#[derive(Debug, Serialize)]
pub(super) struct DiaryPagePayload {
    entries: Vec<ConsumptionLogView>,
    total_for_filter: usize,
    total_all_years: usize,
    page_num: usize,
    total_pages: usize,
    year_counts: Vec<(i32, usize)>,
    non_song_kind_counts: Vec<(EntryKind, usize)>,
    has_songs: bool,
    cadence_year: Option<i32>,
    cadence_months: Vec<(String, usize)>,
    artist_links: HashMap<String, String>,
    diary_tiers: HashMap<String, char>,
    title_html: HashMap<String, String>,
}

pub(super) async fn diary_page_get(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(q): ProblemQuery<DiaryPageQuery>,
) -> Result<Json<DiaryPagePayload>, ApiError> {
    let status_norm = q.status.as_deref().map(|s| s.trim().to_ascii_lowercase());
    let status_filter = match status_norm.as_deref() {
        Some("all") => None,
        Some("started") => Some(ConsumptionStatus::Started),
        Some("abandoned" | "skipped") => Some(ConsumptionStatus::Abandoned),
        _ => Some(ConsumptionStatus::Consumed),
    };
    let kind_filter = DiaryKindFilter::parse_optional(q.kind.as_deref())
        .unwrap_or(DiaryKindFilter::DefaultNonSong);

    let catalog = service.catalog();
    let (sql_year_counts_res, kind_summary_res) = tokio::join!(
        catalog.list_diary_year_counts_with_kind(status_filter, kind_filter),
        catalog.list_diary_kind_summary(q.year, status_filter),
    );
    let sql_year_counts = sql_year_counts_res?;
    let (non_song_kind_counts, has_songs) = kind_summary_res?;

    let mut year_counts_map: BTreeMap<i32, usize> = BTreeMap::new();
    let mut total_all_years = 0usize;
    for &(yr, cnt) in &sql_year_counts {
        let n = usize::try_from(cnt).unwrap_or(0);
        year_counts_map.insert(yr, n);
        total_all_years = total_all_years.saturating_add(n);
    }
    let total_for_filter =
        q.year.map_or(total_all_years, |yr| year_counts_map.get(&yr).copied().unwrap_or(0));

    let total_pages = total_for_filter.max(1).div_ceil(DIARY_PAGE_SIZE);
    let page_num = q.page.unwrap_or(1).clamp(1, total_pages);
    let offset = (page_num - 1) * DIARY_PAGE_SIZE;

    let entries = catalog
        .list_diary_page_with_kind(q.year, status_filter, kind_filter, DIARY_PAGE_SIZE, offset)
        .await?;

    let cadence_year = q.year.or_else(|| year_counts_map.keys().next_back().copied());
    let cadence_months: Vec<(String, usize)> = if let Some(target_yr) = cadence_year {
        service
            .diary_month_counts_for_year(target_yr, status_filter, kind_filter)
            .await?
            .into_iter()
            .rev()
            .take(8)
            .filter_map(|(mo, cnt)| {
                MONTH_ABBR
                    .get(usize::try_from(mo.checked_sub(1)?).ok()?)
                    .map(|&lbl| (lbl.to_owned(), cnt))
            })
            .collect()
    } else {
        Vec::new()
    };
    let year_counts: Vec<(i32, usize)> = year_counts_map.into_iter().rev().collect();

    let diary_creators: Vec<String> =
        entries.iter().flat_map(|e| e.entry.creators.iter().cloned()).collect();
    let catalog_entries: Vec<CatalogEntry> = entries.iter().map(|e| e.entry.clone()).collect();
    let (artist_links, diary_tiers) = tokio::join!(
        load_artist_id_by_lower_name(&service, &diary_creators),
        catalog.load_tiers_for_entries(&catalog_entries),
    );
    let title_html = render_title_html_map(
        entries.iter().map(|e| (e.entry.id.as_str(), e.entry.title.as_str())),
    );

    Ok(Json(DiaryPagePayload {
        entries,
        total_for_filter,
        total_all_years,
        page_num,
        total_pages,
        year_counts,
        non_song_kind_counts,
        has_songs,
        cadence_year,
        cadence_months,
        artist_links,
        diary_tiers,
        title_html,
    }))
}

#[derive(Debug, Serialize)]
pub(super) struct QueuesPayload {
    in_progress: Vec<InProgressRow>,
    queues: Vec<RankedCollectionView>,
    total_items: usize,
    artist_links: HashMap<String, String>,
    reader_item_ids: HashSet<String>,
    title_html: HashMap<String, String>,
}

pub(super) async fn queues_get(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<QueuesPayload>, ApiError> {
    let catalog = service.catalog();
    let (collections_res, in_progress_res) =
        tokio::join!(catalog.list_collections(true, false), service.list_in_progress(Some(100)),);
    let collections = collections_res?;
    let in_progress = in_progress_res?;
    let mut want_cols: Vec<CollectionSummary> = collections
        .into_iter()
        .filter(|c| c.role == CollectionRole::Want && c.member_count > 0)
        .collect();
    want_cols.sort_by_key(|c| c.title.to_lowercase());

    let mut queues = Vec::new();
    let mut total_items = 0usize;
    let empty_filter = RankedCollectionFilter::default();
    for col in &want_cols {
        let slug = col.slug.as_deref().unwrap_or(&col.id);
        let page = service
            .catalog()
            .get_ranked_collection_page(slug, true, &empty_filter, 1, LIST_PAGE_SIZE, false)
            .await?;
        if !page.view.rows.is_empty() {
            total_items = total_items.saturating_add(page.full_total);
            queues.push(page.view);
        }
    }
    let in_progress_ids: Vec<String> =
        in_progress.iter().map(|row| row.entry_id().to_owned()).collect();
    let in_progress_in_want = service.catalog().want_entry_ids_in(&in_progress_ids).await?;
    let in_progress_only_count =
        in_progress.iter().filter(|row| !in_progress_in_want.contains(row.entry_id())).count();
    total_items = total_items.saturating_add(in_progress_only_count);

    let mut creators: Vec<String> = queues
        .iter()
        .flat_map(|q| q.rows.iter().flat_map(|r| r.entry.creators.iter().cloned()))
        .collect();
    creators.extend(in_progress.iter().flat_map(|r| r.creators().iter().cloned()));
    let mut queue_entries: Vec<&CatalogEntry> =
        queues.iter().flat_map(|q| q.rows.iter().map(|r| &r.entry)).collect();
    for row in &in_progress {
        if let InProgressRow::Catalog { entry, .. } = row {
            queue_entries.push(entry);
        }
    }
    let (artist_links, reader_item_ids) = tokio::join!(
        load_artist_id_by_lower_name(&service, &creators),
        load_existing_reader_item_ids(&service, queue_entries),
    );
    let mut title_pairs: Vec<(&str, &str)> = queues
        .iter()
        .flat_map(|q| q.rows.iter().map(|r| (r.entry.id.as_str(), r.entry.title.as_str())))
        .collect();
    for row in &in_progress {
        title_pairs.push((row.entry_id(), row.title()));
    }
    let title_html = render_title_html_map(title_pairs);

    Ok(Json(QueuesPayload {
        in_progress,
        queues,
        total_items,
        artist_links,
        reader_item_ids,
        title_html,
    }))
}

#[derive(Debug, Deserialize)]
pub(super) struct QuickLogRequest {
    title: String,
    kind: String,
    action: String,
    #[serde(default)]
    creators: Vec<String>,
    #[serde(default)]
    release_year: Option<i32>,
    #[serde(default)]
    identifier: Option<String>,
    #[serde(default)]
    notes: Option<String>,
    #[serde(default)]
    rank_after: bool,
}

#[derive(Debug, Serialize)]
pub(super) struct QuickLogResponse {
    action: String,
    entry: CatalogEntry,
    queue_title: Option<String>,
    rank_slug: Option<String>,
    rank_want: bool,
}

pub(super) async fn quick_log_post(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<QuickLogRequest>,
) -> Result<(StatusCode, Json<QuickLogResponse>), ApiError> {
    let title = req.title.trim();
    if title.is_empty() {
        return Err(ApiError::bad_request("Title cannot be empty"));
    }
    let kind =
        EntryKind::parse_flexible(&req.kind).map_err(|e| ApiError::bad_request(e.to_string()))?;
    let creators: Vec<String> =
        req.creators.into_iter().map(|c| c.trim().to_owned()).filter(|c| !c.is_empty()).collect();
    let (url, identifiers) = CatalogService::parse_smart_identifier(req.identifier.as_deref());
    let input = UpsertEntryInput {
        kind: Some(kind),
        title: title.to_owned(),
        creators,
        url,
        release_year: req.release_year,
        identifiers,
        ..UpsertEntryInput::default()
    };

    if req.action.eq_ignore_ascii_case("want") {
        let (queue, entry) =
            service.catalog().want_resolved_or_upsert(&input, req.notes.as_deref(), None).await?;
        let rank_slug = if req.rank_after && queue.member_count >= 2 {
            let q_slug = queue.slug.as_deref().unwrap_or(&queue.id);
            Some(q_slug.strip_prefix("want:").unwrap_or(q_slug).to_owned())
        } else {
            None
        };
        Ok((
            StatusCode::CREATED,
            Json(QuickLogResponse {
                action: "want".to_owned(),
                entry,
                queue_title: Some(queue.title),
                rank_slug,
                rank_want: true,
            }),
        ))
    } else {
        let status: ConsumptionStatus =
            req.action.parse().map_err(|e: anyhow::Error| ApiError::bad_request(e.to_string()))?;
        let logged = service
            .catalog()
            .log_entry_resolved_or_upsert(None, Some(&input), status, req.notes.as_deref(), None)
            .await?;
        let rank_slug = if status == ConsumptionStatus::Consumed && req.rank_after {
            service.catalog().rank_target_after_consumption(&logged.entry).await.ok().flatten()
        } else {
            None
        };
        Ok((
            StatusCode::CREATED,
            Json(QuickLogResponse {
                action: status.as_str().to_owned(),
                entry: logged.entry,
                queue_title: None,
                rank_slug,
                rank_want: false,
            }),
        ))
    }
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct WantCompleteRequest {
    #[serde(default)]
    rank_after: bool,
}

#[derive(Debug, Serialize)]
pub(super) struct WantCompleteResponse {
    logged: ConsumptionLogView,
    rank_slug: Option<String>,
}

pub(super) async fn want_complete_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath((queue, entry_id)): ProblemPath<(String, String)>,
    OptionalProblemJson(req): OptionalProblemJson<WantCompleteRequest>,
) -> Result<Json<WantCompleteResponse>, ApiError> {
    let trimmed_queue = queue.trim();
    if trimmed_queue.is_empty() {
        return Err(ApiError::bad_request("Queue slug cannot be empty"));
    }
    let normalized_queue = if trimmed_queue.starts_with("want:") {
        trimmed_queue.to_owned()
    } else {
        format!("want:{trimmed_queue}")
    };
    let queues = service.catalog().list_collections(true, false).await?;
    let queue_exists = queues.iter().any(|c| {
        c.role == CollectionRole::Want
            && (c.id == trimmed_queue
                || c.slug.as_deref() == Some(trimmed_queue)
                || c.slug.as_deref() == Some(normalized_queue.as_str()))
    });
    if !queue_exists {
        return Err(ApiError::not_found(format!("Want queue '{trimmed_queue}' not found")));
    }

    let logged = service
        .catalog()
        .log_consumption(&entry_id, ConsumptionStatus::Consumed, None, None)
        .await?;
    let rank_slug = if req.rank_after {
        service.catalog().rank_target_after_consumption(&logged.entry).await.ok().flatten()
    } else {
        None
    };
    Ok(Json(WantCompleteResponse { logged, rank_slug }))
}

// ---------------------------------------------------------------------------
// Interactive Rank Session & Comparison Undo
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Deserialize)]
pub(super) struct RankSessionApiQuery {
    #[serde(default)]
    want: bool,
    #[serde(default)]
    focus: Option<String>,
    #[serde(default)]
    round: Option<usize>,
    #[serde(default)]
    skip: Option<usize>,
    #[serde(default)]
    max_rounds: Option<usize>,
    #[serde(default)]
    done: bool,
    #[serde(default)]
    random: Option<usize>,
    #[serde(default)]
    covered: Option<String>,
    #[serde(default)]
    excluded: Option<String>,
    #[serde(default)]
    k: Option<usize>,
}

#[derive(Debug, Serialize)]
#[allow(clippy::struct_excessive_bools)]
pub(super) struct RankSessionPayload {
    collection: CollectionSummary,
    want: bool,
    is_priority_scope: bool,
    comparison_count: usize,
    total_rows: usize,
    round: usize,
    skip: usize,
    max_rounds: usize,
    effective_k: usize,
    focus_id: Option<String>,
    focus_row: Option<RankedEntryRow>,
    cluster: Vec<CatalogEntry>,
    priority_ids: Vec<String>,
    artist_links: HashMap<String, String>,
    open_urls: HashMap<String, String>,
    title_html: HashMap<String, String>,
    settled: bool,
    focus_missing: bool,
}

fn short_cluster_token_slice(id: &str) -> &str {
    match id.char_indices().nth(8) {
        Some((idx, _)) => &id[..idx],
        None => id,
    }
}

fn short_cluster_token(id: &str) -> String {
    short_cluster_token_slice(id).to_owned()
}

fn cluster_signature_from_ids(ids: &[&str]) -> String {
    let mut tokens: Vec<String> = ids.iter().map(|id| short_cluster_token(id)).collect();
    tokens.sort_unstable();
    tokens.dedup();
    tokens.join(".")
}

fn parse_covered_signatures(raw: Option<&str>) -> Vec<HashSet<String>> {
    raw.map(str::trim).filter(|s| !s.is_empty()).map_or_default(|s| {
        s.split('|')
            .filter(|part| !part.is_empty())
            .take(MAX_COVERED_CLUSTERS)
            .map(|part| {
                part.split('.')
                    .filter(|tok| !tok.is_empty())
                    .take(MAX_COVERED_TOKENS_PER_CLUSTER)
                    .map(ToOwned::to_owned)
                    .collect::<HashSet<_>>()
            })
            .filter(|set| !set.is_empty())
            .collect()
    })
}

fn append_covered_signature(existing: Option<&str>, sig: &str) -> String {
    let mut list: Vec<&str> = existing
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map_or_default(|s| s.split('|').filter(|p| !p.is_empty()).collect());
    if !sig.is_empty() && !list.contains(&sig) {
        list.push(sig);
    }
    if list.len() > MAX_COVERED_CLUSTERS {
        list.drain(0..(list.len() - MAX_COVERED_CLUSTERS));
    }
    list.join("|")
}

fn parse_excluded_tokens(raw: Option<&str>) -> HashSet<String> {
    raw.map(str::trim).filter(|s| !s.is_empty()).map_or_default(|s| {
        let mut list: Vec<&str> = s.split('.').filter(|p| !p.is_empty()).collect();
        if list.len() > MAX_EXCLUDED_TOKENS {
            list.drain(0..(list.len() - MAX_EXCLUDED_TOKENS));
        }
        list.into_iter().map(ToOwned::to_owned).collect()
    })
}

fn merge_excluded_tokens(
    existing: Option<&str>,
    newly_excluded_ids: &[&str],
    submitted_tokens: &HashSet<String>,
) -> String {
    let mut list: Vec<String> =
        existing.map(str::trim).filter(|s| !s.is_empty()).map_or_default(|s| {
            s.split('.')
                .filter(|p| !p.is_empty() && !submitted_tokens.contains(*p))
                .map(ToOwned::to_owned)
                .collect()
        });
    for &raw_id in newly_excluded_ids {
        let tok = short_cluster_token(raw_id);
        if !tok.is_empty() && !submitted_tokens.contains(&tok) && !list.contains(&tok) {
            list.push(tok);
        }
    }
    if list.len() > MAX_EXCLUDED_TOKENS {
        list.drain(0..(list.len() - MAX_EXCLUDED_TOKENS));
    }
    list.join(".")
}

fn is_cluster_ids_covered(cluster_ids: &[String], covered: &[HashSet<String>]) -> bool {
    if cluster_ids.is_empty() || covered.is_empty() {
        return false;
    }
    covered
        .iter()
        .any(|prev| cluster_ids.iter().all(|id| prev.contains(short_cluster_token_slice(id))))
}

pub(super) async fn rank_session_get(
    State(service): State<Arc<RocheService>>,
    headers: HeaderMap,
    ProblemPath(slug): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<RankSessionApiQuery>,
) -> Result<Json<RankSessionPayload>, ApiError> {
    let round = q.round.unwrap_or(0);
    let skip_count = q.skip.unwrap_or(0);
    let effective_max_rounds =
        q.max_rounds.unwrap_or(MAX_FOCUSED_ROUNDS).clamp(MAX_FOCUSED_ROUNDS, MAX_CUSTOM_ROUNDS);
    let explicit_k = q.k.map(|v| v.clamp(MIN_K, MAX_K_DEFAULT));
    let cookie_k = extract_rank_k_cookie(&headers);
    let effective_k = explicit_k.or(cookie_k).unwrap_or(MAX_K_DEFAULT).clamp(MIN_K, MAX_K_DEFAULT);

    let focus_clean = q.focus.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let covered_sets = parse_covered_signatures(q.covered.as_deref());
    let excluded_tokens = parse_excluded_tokens(q.excluded.as_deref());

    let session = service
        .catalog()
        .prepare_rank_session(
            &slug,
            q.want,
            focus_clean,
            q.done,
            q.random == Some(1),
            effective_max_rounds,
            round,
            skip_count,
            effective_k,
            &excluded_tokens,
            short_cluster_token_slice,
            |cand_ids| is_cluster_ids_covered(cand_ids, &covered_sets),
        )
        .await?;

    let effective_want = q.want || session.is_priority_scope;
    let settled = session.cluster.entries.len() < 2;
    let cluster_creators: Vec<String> =
        session.cluster.entries.iter().flat_map(|e| e.creators.iter().cloned()).collect();
    let artist_links = load_artist_id_by_lower_name(&service, &cluster_creators).await;
    let mut open_urls = HashMap::new();
    for entry in &session.cluster.entries {
        if let Some(u) = web_primary_open_url(entry) {
            open_urls.insert(entry.id.clone(), u);
        }
    }
    let mut pairs: Vec<(&str, &str)> =
        session.cluster.entries.iter().map(|e| (e.id.as_str(), e.title.as_str())).collect();
    if let Some(ref fr) = session.focus_row {
        pairs.push((fr.entry.id.as_str(), fr.entry.title.as_str()));
    }
    let title_html = render_title_html_map(pairs);

    Ok(Json(RankSessionPayload {
        collection: session.collection,
        want: effective_want,
        is_priority_scope: session.is_priority_scope,
        comparison_count: session.comparison_count,
        total_rows: session.total_rows,
        round,
        skip: skip_count,
        max_rounds: effective_max_rounds,
        effective_k,
        focus_id: if q.random == Some(1) { None } else { focus_clean.map(ToOwned::to_owned) },
        focus_row: session.focus_row,
        cluster: session.cluster.entries,
        priority_ids: session.cluster.priority_ids,
        artist_links,
        open_urls,
        title_html,
        settled,
        focus_missing: session.focus_missing,
    }))
}

#[derive(Debug, Deserialize)]
pub(super) struct RankSubmitRequest {
    ordering: Vec<Vec<String>>,
    #[serde(default)]
    want: bool,
    #[serde(default)]
    covered: Option<String>,
    #[serde(default)]
    excluded: Option<String>,
    #[serde(default)]
    round_excluded: Vec<String>,
    #[serde(default)]
    k: Option<usize>,
}

#[derive(Debug, Serialize)]
pub(super) struct RankSubmitResponse {
    comparison: ComparisonRecord,
    next_covered: String,
    next_excluded: String,
    undo_excluded: Option<String>,
    want: bool,
}

pub(super) async fn rank_session_submit(
    State(service): State<Arc<RocheService>>,
    connect_info: Result<ConnectInfo<SocketAddr>, ExtensionRejection>,
    headers: HeaderMap,
    ProblemPath(slug): ProblemPath<String>,
    ProblemJson(req): ProblemJson<RankSubmitRequest>,
) -> Result<Response, ApiError> {
    let peer_addr = connect_info.ok().map(|ConnectInfo(addr)| addr);
    let secure = is_forwarded_https(peer_addr, &headers);
    let total_cards: usize = req.ordering.iter().map(Vec::len).sum();
    if total_cards < MIN_K {
        return Err(ApiError::bad_request("Invalid comparison ordering"));
    }

    let (effective_want, scope_col_id) = if req.want {
        let col = service.catalog().get_or_create_want_collection(&slug).await?;
        (true, Some(col.id))
    } else {
        service.catalog().resolve_ranking_scope(&slug, false).await?
    };

    let comparison =
        service.catalog().append_comparison(scope_col_id.as_deref(), &req.ordering, None).await?;

    let submitted_ids: Vec<&str> =
        req.ordering.iter().flat_map(|g| g.iter().map(String::as_str)).collect();
    let submitted_sig = cluster_signature_from_ids(&submitted_ids);
    let next_covered = append_covered_signature(req.covered.as_deref(), &submitted_sig);
    let submitted_tokens: HashSet<String> =
        submitted_ids.iter().map(|id| short_cluster_token(id)).collect();
    let newly_excluded_refs: Vec<&str> =
        req.round_excluded.iter().map(String::as_str).filter(|s| !s.is_empty()).collect();
    let prev_excluded = req.excluded.as_deref().unwrap_or("");
    let next_excluded =
        merge_excluded_tokens(Some(prev_excluded), &newly_excluded_refs, &submitted_tokens);
    let undo_excluded = (next_excluded != prev_excluded).then(|| prev_excluded.to_owned());

    let mut resp = (
        StatusCode::CREATED,
        Json(RankSubmitResponse {
            comparison,
            next_covered,
            next_excluded,
            undo_excluded,
            want: effective_want,
        }),
    )
        .into_response();
    if let Some(k_val) = req.k {
        append_rank_k_set_cookie(&mut resp, k_val.clamp(MIN_K, MAX_K_DEFAULT), secure);
    }
    Ok(resp)
}

pub(super) async fn comparison_undo_post(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    let _ = service.catalog().delete_comparison(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}
