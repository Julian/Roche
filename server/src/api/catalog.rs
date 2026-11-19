use std::{collections::HashMap, sync::Arc};

use axum::{Json, extract::State, http::StatusCode};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::{
    db::{
        CatalogBatchInput, CatalogBatchResult, CatalogEntry, ClusterSelectFilter,
        CollectionGeneratorState, CollectionSummary, ComparisonRecord, ConsumptionLogView,
        EntryDetailView, RankedCollectionView, UpsertEntryInput,
    },
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    error::{ApiError, ProblemJson, ProblemPath, ProblemQuery},
    ranking::Score,
    service::RocheService,
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct ListsQuery {
    #[serde(default = "default_true")]
    include_want: bool,
    #[serde(default)]
    include_system: bool,
}

const fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize)]
pub(super) struct CreateCollectionRequest {
    title: String,
    slug: Option<String>,
    #[serde(default)]
    role: CollectionRole,
    summary: Option<String>,
    color: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct RankedListQuery {
    #[serde(default)]
    want: bool,
}

#[derive(Debug, Deserialize)]
pub(super) struct AddCollectionEntryRequest {
    entry_id: String,
    position: Option<f64>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct SearchEntriesQuery {
    q: Option<String>,
    kind: Option<String>,
    limit: Option<usize>,
}

#[derive(Debug, Deserialize)]
pub(super) struct MergeEntryRequest {
    canonical_id: String,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct DiaryQuery {
    year: Option<i32>,
    status: Option<ConsumptionStatus>,
    limit: Option<usize>,
}

#[derive(Debug, Deserialize)]
pub(super) struct LogConsumptionRequest {
    #[serde(default)]
    entry_id: Option<String>,
    #[serde(default)]
    entry: Option<UpsertEntryInput>,
    status: ConsumptionStatus,
    #[serde(default)]
    notes: Option<String>,
    #[serde(default)]
    ts: Option<DateTime<Utc>>,
}

#[derive(Debug, Deserialize)]
pub(super) struct WantEntryRequest {
    entry: UpsertEntryInput,
    #[serde(default)]
    queue: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct WantEntryResponse {
    queue: CollectionSummary,
    entry: CatalogEntry,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ClusterQuery {
    #[serde(default)]
    want: bool,
    max_k: Option<usize>,
    iteration: Option<usize>,
    focus: Option<String>,
    recent: Option<usize>,
    by: Option<String>,
    from: Option<String>,
}

#[derive(Debug, Serialize)]
pub(super) struct ClusterResponse {
    collection: CollectionSummary,
    cluster: Vec<CatalogEntry>,
    scores: HashMap<String, Score>,
}

#[derive(Debug, Deserialize)]
pub(super) struct CreateComparisonRequest {
    #[serde(default)]
    scope_collection_id: Option<String>,
    ordering: Vec<Vec<String>>,
    #[serde(default)]
    ts: Option<DateTime<Utc>>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct GeneratorUpdateRequest {
    #[serde(default)]
    enabled: Option<bool>,
    #[serde(default)]
    current_entry_id: Option<String>,
    #[serde(default)]
    drawn_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct GeneratorAdvanceRequest {
    #[serde(default)]
    notes: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct GeneratorsListQuery {
    #[serde(default)]
    enabled: bool,
}

#[derive(Debug, Serialize)]
pub(super) struct GeneratorAdvanceResponse {
    completed_entry: CatalogEntry,
    generator: CollectionGeneratorState,
}

pub(super) async fn catalog_lists(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(q): ProblemQuery<ListsQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let collections = service.catalog().list_collections(q.include_want, q.include_system).await?;
    Ok(Json(serde_json::json!({ "collections": collections })))
}

pub(super) async fn catalog_lists_create(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<CreateCollectionRequest>,
) -> Result<(StatusCode, Json<CollectionSummary>), ApiError> {
    let created = service
        .catalog()
        .create_manual_collection(
            &req.title,
            req.slug.as_deref(),
            req.role,
            req.summary.as_deref(),
            req.color.as_deref(),
        )
        .await?;
    Ok((StatusCode::CREATED, Json(created)))
}

pub(super) async fn catalog_list_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<RankedListQuery>,
) -> Result<Json<RankedCollectionView>, ApiError> {
    let view = service.catalog().get_ranked_collection(&slug_or_id, q.want).await?;
    Ok(Json(view))
}

pub(super) async fn catalog_list_add_entry(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<AddCollectionEntryRequest>,
) -> Result<StatusCode, ApiError> {
    service.catalog().add_collection_entry(&slug_or_id, &req.entry_id, req.position).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn catalog_list_remove_entry(
    State(service): State<Arc<RocheService>>,
    ProblemPath((slug_or_id, entry_id)): ProblemPath<(String, String)>,
) -> Result<StatusCode, ApiError> {
    if !service.catalog().remove_collection_entry(&slug_or_id, &entry_id).await? {
        return Err(ApiError::not_found("Entry is not in the specified collection"));
    }
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn catalog_entries_search(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(q): ProblemQuery<SearchEntriesQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let kind = match q.kind.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw) => {
            Some(EntryKind::parse_flexible(raw).map_err(|e| ApiError::bad_request(e.to_string()))?)
        }
        None => None,
    };
    let entries =
        service.catalog().search_entries(q.q.as_deref(), kind, q.limit.unwrap_or(50)).await?;
    Ok(Json(serde_json::json!({ "entries": entries })))
}

pub(super) async fn catalog_entries_upsert(
    State(service): State<Arc<RocheService>>,
    ProblemJson(input): ProblemJson<UpsertEntryInput>,
) -> Result<(StatusCode, Json<CatalogEntry>), ApiError> {
    let entry = service.catalog().upsert_entry(&input).await?;
    Ok((StatusCode::CREATED, Json(entry)))
}

pub(super) async fn catalog_entry_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<EntryDetailView>, ApiError> {
    let detail = service
        .catalog()
        .get_entry_detail(&id)
        .await?
        .ok_or_else(|| ApiError::not_found(format!("Entry '{id}' not found")))?;
    Ok(Json(detail))
}

pub(super) async fn catalog_entry_merge(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<MergeEntryRequest>,
) -> Result<Json<CatalogEntry>, ApiError> {
    let merged = service.catalog().merge_entries(&id, &req.canonical_id).await?;
    Ok(Json(merged))
}

pub(super) async fn catalog_diary_list(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(q): ProblemQuery<DiaryQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let entries = service.catalog().list_diary(q.year, q.status, q.limit.unwrap_or(100)).await?;
    Ok(Json(serde_json::json!({ "entries": entries })))
}

pub(super) async fn catalog_log_create(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<LogConsumptionRequest>,
) -> Result<(StatusCode, Json<ConsumptionLogView>), ApiError> {
    let logged = service
        .catalog()
        .log_entry_resolved_or_upsert(
            req.entry_id.as_deref(),
            req.entry.as_ref(),
            req.status,
            req.notes.as_deref(),
            req.ts,
        )
        .await?;
    Ok((StatusCode::CREATED, Json(logged)))
}

pub(super) async fn catalog_want_add(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<WantEntryRequest>,
) -> Result<(StatusCode, Json<WantEntryResponse>), ApiError> {
    let (queue, entry) =
        service.catalog().want_resolved_or_upsert(&req.entry, None, req.queue.as_deref()).await?;
    Ok((StatusCode::CREATED, Json(WantEntryResponse { queue, entry })))
}

pub(super) async fn catalog_want_remove(
    State(service): State<Arc<RocheService>>,
    ProblemPath((queue, entry_id_or_query)): ProblemPath<(String, String)>,
) -> Result<Json<CatalogEntry>, ApiError> {
    let removed = service.catalog().unwant_entry(&queue, &entry_id_or_query).await?;
    Ok(Json(removed))
}

pub(super) async fn catalog_rank_cluster(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<ClusterQuery>,
) -> Result<Json<ClusterResponse>, ApiError> {
    let focus_ids: Vec<String> = q.focus.as_deref().map_or_default(|raw| {
        raw.split(',').map(str::trim).filter(|s| !s.is_empty()).map(ToOwned::to_owned).collect()
    });
    let filter = ClusterSelectFilter {
        max_k: q.max_k.unwrap_or(5),
        iteration: q.iteration.unwrap_or(0),
        focus_ids,
        recent_limit: q.recent,
        by_creator: q.by,
        from_parent_id: q.from,
    };
    let (collection, cluster, scores) =
        service.catalog().select_next_cluster(&slug_or_id, q.want, &filter).await?;
    Ok(Json(ClusterResponse { collection, cluster, scores }))
}

pub(super) async fn catalog_comparisons_create(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<CreateComparisonRequest>,
) -> Result<(StatusCode, Json<ComparisonRecord>), ApiError> {
    let record = service
        .catalog()
        .append_comparison(req.scope_collection_id.as_deref(), &req.ordering, req.ts)
        .await?;
    Ok((StatusCode::CREATED, Json(record)))
}

pub(super) async fn catalog_batch_apply(
    State(service): State<Arc<RocheService>>,
    ProblemJson(batch): ProblemJson<CatalogBatchInput>,
) -> Result<Json<CatalogBatchResult>, ApiError> {
    let result = service.catalog().apply_batch(&batch).await?;
    Ok(Json(result))
}

pub(super) async fn catalog_generators_list(
    State(service): State<Arc<RocheService>>,
    ProblemQuery(q): ProblemQuery<GeneratorsListQuery>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let generators = service.catalog().list_collection_generators(q.enabled).await?;
    Ok(Json(serde_json::json!({ "generators": generators })))
}

pub(super) async fn catalog_list_generator_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
) -> Result<Json<CollectionGeneratorState>, ApiError> {
    let state = service.catalog().get_collection_generator(&slug_or_id).await?;
    Ok(Json(state))
}

pub(super) async fn catalog_list_generator_patch(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<GeneratorUpdateRequest>,
) -> Result<Json<CollectionGeneratorState>, ApiError> {
    let state = service
        .catalog()
        .update_collection_generator(
            &slug_or_id,
            req.enabled,
            req.current_entry_id.as_deref(),
            req.drawn_at,
        )
        .await?;
    Ok(Json(state))
}

pub(super) async fn catalog_list_generator_delete(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    let _ = service.catalog().remove_collection_generator(&slug_or_id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn catalog_list_generator_finish(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<GeneratorAdvanceRequest>,
) -> Result<Json<GeneratorAdvanceResponse>, ApiError> {
    let (completed_entry, generator) =
        service.catalog().finish_active_generator_entry(&slug_or_id, req.notes.as_deref()).await?;
    Ok(Json(GeneratorAdvanceResponse { completed_entry, generator }))
}

pub(super) async fn catalog_list_generator_abandon(
    State(service): State<Arc<RocheService>>,
    ProblemPath(slug_or_id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<GeneratorAdvanceRequest>,
) -> Result<Json<GeneratorAdvanceResponse>, ApiError> {
    let (completed_entry, generator) =
        service.catalog().abandon_active_generator_entry(&slug_or_id, req.notes.as_deref()).await?;
    Ok(Json(GeneratorAdvanceResponse { completed_entry, generator }))
}
