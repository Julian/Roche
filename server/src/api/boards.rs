use std::sync::Arc;

use axum::{Json, extract::State, http::StatusCode};
use roche_core::boards::{BoardOrderMode, BoardType};
use serde::Deserialize;

use super::error::{ApiError, OptionalProblemJson, ProblemJson, ProblemPath, ProblemQuery};
use crate::{
    db::{Board, BoardItemState, BoardPromoteAction},
    service::{
        BoardDismissOutcome, BoardItemsPage, BoardPromoteResult, BoardReaderView, CreateBoardInput,
        RocheService,
    },
};

#[derive(Debug, Deserialize)]
pub(super) struct CreateBoardRequest {
    #[serde(default)]
    adapter_type: Option<BoardType>,
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    section: Option<String>,
    #[serde(default)]
    source_config: Option<serde_json::Value>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    order_mode: Option<BoardOrderMode>,
    #[serde(default)]
    poll_interval: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub(super) struct UpdateBoardRequest {
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    order_mode: Option<BoardOrderMode>,
    #[serde(default)]
    poll_interval: Option<i64>,
    #[serde(default)]
    is_active: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct BoardItemsQuery {
    #[serde(default)]
    page: Option<usize>,
    #[serde(default)]
    per_page: Option<usize>,
    #[serde(default)]
    include_dismissed: Option<bool>,
    #[serde(default)]
    dismissed_only: Option<bool>,
    #[serde(default)]
    live_more: Option<bool>,
    #[serde(default)]
    live_offset: Option<usize>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct DismissBoardItemParams {
    #[serde(default)]
    backfill_offset: Option<usize>,
}

pub(super) async fn boards_list(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let boards = service.boards().list().await?;
    Ok(Json(serde_json::json!({ "boards": boards })))
}

pub(super) async fn boards_create(
    State(service): State<Arc<RocheService>>,
    ProblemJson(req): ProblemJson<CreateBoardRequest>,
) -> Result<(StatusCode, Json<Board>), ApiError> {
    let board = service
        .boards()
        .create(CreateBoardInput {
            adapter_type: req.adapter_type,
            url: req.url,
            section: req.section,
            source_config: req.source_config,
            title: req.title,
            order_mode: req.order_mode,
            poll_interval: req.poll_interval,
        })
        .await?;
    Ok((StatusCode::CREATED, Json(board)))
}

pub(super) async fn boards_get(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Board>, ApiError> {
    let board = service.boards().get(&id).await?;
    Ok(Json(board))
}

pub(super) async fn boards_update(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemJson(req): ProblemJson<UpdateBoardRequest>,
) -> Result<Json<Board>, ApiError> {
    let updated = service
        .boards()
        .update(&id, req.title.as_deref(), req.order_mode, req.poll_interval, req.is_active)
        .await?;
    Ok(Json(updated))
}

pub(super) async fn boards_delete(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<StatusCode, ApiError> {
    service.boards().delete(&id).await?;
    Ok(StatusCode::NO_CONTENT)
}

pub(super) async fn boards_archive(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Board>, ApiError> {
    let archived = service.boards().archive(&id).await?;
    Ok(Json(archived))
}

pub(super) async fn boards_unarchive(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
) -> Result<Json<Board>, ApiError> {
    let unarchived = service.boards().unarchive(&id).await?;
    Ok(Json(unarchived))
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct BoardRefreshQuery {
    #[serde(default)]
    background: bool,
}

pub(super) async fn boards_refresh(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemQuery(q): ProblemQuery<BoardRefreshQuery>,
) -> Result<Json<Board>, ApiError> {
    if q.background {
        let board = service.boards().refresh_in_background(&id).await?;
        return Ok(Json(board));
    }
    let refreshed = service.boards().refresh(&id).await?;
    Ok(Json(refreshed))
}

pub(super) async fn boards_refresh_all(
    State(service): State<Arc<RocheService>>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let refreshed_count = service.boards().refresh_all_in_background().await?;
    Ok(Json(serde_json::json!({ "refreshed_count": refreshed_count })))
}

pub(super) async fn boards_items(
    State(service): State<Arc<RocheService>>,
    ProblemPath(id): ProblemPath<String>,
    ProblemQuery(query): ProblemQuery<BoardItemsQuery>,
) -> Result<Json<BoardItemsPage>, ApiError> {
    let page = service
        .boards()
        .list_items(
            &id,
            query.page,
            query.per_page,
            query.include_dismissed.unwrap_or(false),
            query.dismissed_only.unwrap_or(false),
            query.live_more.unwrap_or(false),
            query.live_offset,
        )
        .await?;
    Ok(Json(page))
}

pub(super) async fn boards_item_reader(
    State(service): State<Arc<RocheService>>,
    ProblemPath((id, external_id)): ProblemPath<(String, String)>,
) -> Result<Json<BoardReaderView>, ApiError> {
    let reader = service.boards().open_reader(&id, &external_id).await?;
    Ok(Json(reader))
}

pub(super) async fn boards_item_seen(
    State(service): State<Arc<RocheService>>,
    ProblemPath((id, external_id)): ProblemPath<(String, String)>,
) -> Result<Json<BoardItemState>, ApiError> {
    let state = service.boards().mark_seen(&id, &external_id).await?;
    Ok(Json(state))
}

pub(super) async fn boards_item_dismiss(
    State(service): State<Arc<RocheService>>,
    ProblemPath((id, external_id)): ProblemPath<(String, String)>,
    ProblemQuery(query): ProblemQuery<DismissBoardItemParams>,
    OptionalProblemJson(body_params): OptionalProblemJson<DismissBoardItemParams>,
) -> Result<Json<BoardDismissOutcome>, ApiError> {
    let outcome = service
        .boards()
        .dismiss(&id, &external_id, body_params.backfill_offset.or(query.backfill_offset))
        .await?;
    Ok(Json(outcome))
}

pub(super) async fn boards_item_undismiss(
    State(service): State<Arc<RocheService>>,
    ProblemPath((id, external_id)): ProblemPath<(String, String)>,
) -> Result<Json<BoardItemState>, ApiError> {
    let state = service.boards().undismiss(&id, &external_id).await?;
    Ok(Json(state))
}

pub(super) async fn boards_item_promote(
    State(service): State<Arc<RocheService>>,
    ProblemPath((id, external_id)): ProblemPath<(String, String)>,
    ProblemJson(action): ProblemJson<BoardPromoteAction>,
) -> Result<Json<BoardPromoteResult>, ApiError> {
    let result = service.boards().promote(&id, &external_id, action).await?;
    Ok(Json(result))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use axum::{
        body::{Body, to_bytes},
        http::{Request, StatusCode, header},
    };
    use roche_core::boards::HackerNewsSection;
    use serde_json::{Value, json};
    use tower::ServiceExt;
    use url::Url;

    use crate::{api::ApiRoutes, auth::tokens::TokenManager, db::Db, ingest::FeedFetcher};

    #[derive(Clone)]
    struct MockBoardApiFetcher;

    impl FeedFetcher for MockBoardApiFetcher {
        fn fetch_feed(
            &self,
            url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> + Send {
            let s = url.as_str().to_owned();
            async move {
                if s == HackerNewsSection::Top.api_stories_url() {
                    Ok("[201, 202, 203, 204]".to_owned())
                } else if s.ends_with("/item/201.json") {
                    Ok(r#"{"id":201,"type":"story","title":"Alpha Post","url":"https://example.com/alpha","by":"alice","score":210,"descendants":44,"time":1732300000}"#.to_owned())
                } else if s.ends_with("/item/202.json") {
                    Ok(r#"{"id":202,"type":"story","title":"Beta Post","url":"https://example.com/beta","by":"bob","score":180,"descendants":31,"time":1732300100}"#.to_owned())
                } else if s.ends_with("/item/203.json") {
                    Ok(r#"{"id":203,"type":"story","title":"Gamma Post","url":"https://example.com/gamma","by":"carol","score":140,"descendants":19,"time":1732300200}"#.to_owned())
                } else if s.ends_with("/item/204.json") {
                    Ok(r#"{"id":204,"type":"story","title":"Delta Post","url":"https://example.com/delta","by":"dave","score":110,"descendants":7,"time":1732300300}"#.to_owned())
                } else {
                    Err(anyhow::anyhow!("unexpected api test url: {s}"))
                }
            }
        }

        fn fetch_html(
            &self,
            url: &Url,
        ) -> impl std::future::Future<Output = anyhow::Result<Option<String>>> + Send {
            let res = if url.as_str() == "https://example.com/alpha" {
                Some(
                    "<html><head><title>Alpha</title></head><body><article><p>Alpha full body</p></article></body></html>"
                        .to_owned(),
                )
            } else {
                None
            };
            std::future::ready(Ok(res))
        }
    }

    async fn send_json(
        app: &axum::Router,
        method: &str,
        uri: &str,
        auth: Option<&str>,
        payload: Option<Value>,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder().method(method).uri(uri);
        if let Some(bearer) = auth {
            builder = builder.header(header::AUTHORIZATION, bearer);
        }
        if payload.is_some() {
            builder = builder.header(header::CONTENT_TYPE, "application/json");
        }
        let body = payload.map_or_else(Body::empty, |v| Body::from(v.to_string()));
        let resp = app.clone().oneshot(builder.body(body).unwrap()).await.unwrap();
        let status = resp.status();
        let bytes = to_bytes(resp.into_body(), usize::MAX).await.unwrap();
        let json_val = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        (status, json_val)
    }

    #[tokio::test]
    #[allow(clippy::too_many_lines)]
    async fn test_boards_rest_api_full_lifecycle_reader_dismiss_and_promote() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let token = TokenManager::new(db.auth()).create("boards-api-test", 32).await.unwrap();
        let auth = format!("Bearer {token}");
        let app = ApiRoutes::router_with_fetcher(&db, MockBoardApiFetcher);

        // 1. Auth required
        let (status, _) = send_json(&app, "GET", "/api/v1/boards", None, None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);

        // 2. SSRF URL rejected with 400
        let (status, problem) = send_json(
            &app,
            "POST",
            "/api/v1/boards",
            Some(&auth),
            Some(json!({ "url": "http://127.0.0.1/hn" })),
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(problem["status"], 400);

        // 3. Create HN board (auto-detects section & auto-polls 4 stories)
        let (status, created) = send_json(
            &app,
            "POST",
            "/api/v1/boards",
            Some(&auth),
            Some(json!({ "url": "https://news.ycombinator.com/" })),
        )
        .await;
        assert_eq!(status, StatusCode::CREATED);
        let board_id = created["id"].as_str().unwrap().to_owned();
        assert_eq!(created["adapter_type"], "hacker_news");
        assert_eq!(created["source_key"], "top");
        assert_eq!(created["item_count"], 4);

        // Duplicate create returns 409 Conflict
        let (status, _) = send_json(
            &app,
            "POST",
            "/api/v1/boards",
            Some(&auth),
            Some(json!({ "section": "top" })),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT);

        // 4. List, Get, Patch, Archive, Unarchive, Refresh, Refresh-All
        let (status, list_body) = send_json(&app, "GET", "/api/v1/boards", Some(&auth), None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(list_body["boards"].as_array().unwrap().len(), 1);

        let (status, patched) = send_json(
            &app,
            "PATCH",
            &format!("/api/v1/boards/{board_id}"),
            Some(&auth),
            Some(json!({ "title": "HN Front Page", "order_mode": "chronological", "poll_interval": 45 })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(patched["title"], "HN Front Page");
        assert_eq!(patched["order_mode"], "chronological");
        assert_eq!(patched["poll_interval"], 45);

        let (status, archived) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/archive"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(archived["is_active"], false);

        let (status, unarchived) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/unarchive"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(unarchived["is_active"], true);

        let (status, _) = send_json(
            &app,
            "PATCH",
            &format!("/api/v1/boards/{board_id}"),
            Some(&auth),
            Some(json!({ "order_mode": "ranked" })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let (status, refreshed) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/refresh"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(refreshed["item_count"], 4);

        let (status, refresh_all) =
            send_json(&app, "POST", "/api/v1/boards/refresh-all", Some(&auth), None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(refresh_all["refreshed_count"], 1);

        // 5. Buffered pagination & live_more lazy page
        let (status, page1) = send_json(
            &app,
            "GET",
            &format!("/api/v1/boards/{board_id}/items?page=1&per_page=2"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(page1["items"].as_array().unwrap().len(), 2);
        assert_eq!(page1["items"][0]["external_id"], "201");
        assert_eq!(page1["has_more_buffered"], true);
        assert_eq!(page1["is_live_page"], false);

        let (status, live_page) = send_json(
            &app,
            "GET",
            &format!("/api/v1/boards/{board_id}/items?live_more=true&live_offset=2&per_page=2"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(live_page["is_live_page"], true);
        assert_eq!(live_page["items"][0]["external_id"], "203");
        assert_eq!(live_page["items"][1]["external_id"], "204");

        // 6. On-demand Reader (marks seen, returns HTML, does NOT insert into entries)
        let (status, reader) = send_json(
            &app,
            "GET",
            &format!("/api/v1/boards/{board_id}/items/201/reader"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(reader["content_html"].as_str().unwrap().contains("Alpha full body"));
        assert_eq!(reader["item"]["is_seen"], true);
        assert!(db.items().list(None, None, None).await.unwrap().is_empty());

        // 7. Seen, Dismiss (empty body & JSON backfill), Undismiss
        let (status, seen) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/202/seen"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(seen["seen_at"].is_string());

        let (status, dismissed_empty) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/202/dismiss"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(dismissed_empty["external_id"], "202");

        let (status, dismissed_backfill) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/dismiss"),
            Some(&auth),
            Some(json!({ "backfill_offset": 1 })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(dismissed_backfill["replacement_item"]["external_id"], "204");

        let (status, undismissed) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/undismiss"),
            Some(&auth),
            None,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(undismissed["dismissed_at"].is_null());

        // 8. Promote across queue / star / finish / tag / list (dedups on same canonical entry)
        let (status, p_queue) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/promote"),
            Some(&auth),
            Some(json!({ "action": "queue" })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(p_queue["created_new"], true);
        let entry_id = p_queue["entry_id"].as_str().unwrap().to_owned();
        assert_eq!(p_queue["board_item"]["in_want_to_read"], true);
        assert_eq!(p_queue["item"]["discussions"][0]["external_id"], "201");

        let (status, p_star) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/promote"),
            Some(&auth),
            Some(json!({ "action": "star" })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(p_star["created_new"], false);
        assert_eq!(p_star["entry_id"], entry_id);
        assert_eq!(p_star["board_item"]["is_favorite"], true);

        let (status, p_finish) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/promote"),
            Some(&auth),
            Some(json!({ "action": "finish" })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(p_finish["board_item"]["is_finished"], true);

        let tag = db.tags().create("systems", None).await.unwrap();
        let (status, _) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/promote"),
            Some(&auth),
            Some(json!({ "action": "tag", "tag_id": tag.id })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        let list = db
            .catalog()
            .create_manual_collection(
                "Reading Club",
                None,
                crate::domain::CollectionRole::List,
                None,
                None,
            )
            .await
            .unwrap();
        let (status, _) = send_json(
            &app,
            "POST",
            &format!("/api/v1/boards/{board_id}/items/201/promote"),
            Some(&auth),
            Some(json!({ "action": "list", "collection_id": list.id })),
        )
        .await;
        assert_eq!(status, StatusCode::OK);

        // Verify GET /api/v1/items/{entry_id} exposes discussions and Feed Inbox excludes promoted item.
        let (status, item_resp) =
            send_json(&app, "GET", &format!("/api/v1/items/{entry_id}"), Some(&auth), None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(item_resp["discussions"][0]["source"], "hacker_news");
        assert_eq!(item_resp["inbox_state"], "none");

        let (status, inbox_resp) =
            send_json(&app, "GET", "/api/v1/items?inbox_only=true", Some(&auth), None).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(inbox_resp["items"].as_array().unwrap().len(), 0);

        // 9. Delete board -> 204, subsequent GET -> 404
        let (status, _) =
            send_json(&app, "DELETE", &format!("/api/v1/boards/{board_id}"), Some(&auth), None)
                .await;
        assert_eq!(status, StatusCode::NO_CONTENT);

        let (status, _) =
            send_json(&app, "GET", &format!("/api/v1/boards/{board_id}"), Some(&auth), None).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
