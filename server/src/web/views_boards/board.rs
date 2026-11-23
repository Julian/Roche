use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form, Json,
    extract::{Extension, Path, Query, State},
    http::HeaderMap,
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Utc};
use roche_core::boards::{
    BOARD_DEFAULT_PAGE_SIZE, BOARD_SNAPSHOT_BUFFER_LIMIT, BoardOrderMode, BoardType,
};
use serde::Deserialize;

use super::{
    board_has_active_error, board_item_is_self_post, board_item_kind_badge, board_source_badge,
    clean_board_error, clean_plain_excerpt, format_comments_label, format_relative_age, wants_json,
};
use crate::{
    db::{Board, BoardItemView, BoardPromoteAction, WANT_TO_READ_COLLECTION_ID},
    service::{BoardItemsPage, CreateBoardInput, RocheService},
    util::clock::now_utc,
    web::{
        auth::WebSession,
        html::{
            DISMISS_SVG, NavSection, PageShell, REFRESH_SVG, RESTORE_BOX_SVG, WebError,
            WithWebCsrf as _, de_opt_from_str, de_query_bool, esc, parse_form_bool,
            redirect_see_other, redirect_with_flash, render_inline_csrf_form,
            render_inline_csrf_form_with_class, render_page, render_triage_form,
            safe_local_redirect, safe_web_error_message, urlencode,
        },
        links::sanitize_non_local_href,
    },
};

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct BoardsQuery {
    #[serde(default)]
    pub board: Option<String>,
    #[serde(default)]
    pub view: Option<String>,
    #[serde(default)]
    pub q: Option<String>,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub page: Option<usize>,
    #[serde(default, deserialize_with = "de_query_bool")]
    pub live_more: bool,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub live_offset: Option<usize>,
}

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct BoardItemsFragmentQuery {
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub page: Option<usize>,
    #[serde(default, deserialize_with = "de_query_bool")]
    pub live_more: bool,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub live_offset: Option<usize>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct CreateBoardForm {
    #[serde(default)]
    pub adapter_type: Option<String>,
    #[serde(default)]
    pub section: Option<String>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub poll_interval: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct EditBoardForm {
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub order_mode: Option<String>,
    #[serde(default)]
    pub poll_interval: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(in crate::web) struct BoardToggleForm {
    pub active: String,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct BoardReturnForm {
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct BoardDismissForm {
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub backfill_offset: Option<usize>,
    #[serde(default)]
    pub skip_backfill: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(in crate::web) struct BoardPromoteForm {
    pub action: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub tag_name: Option<String>,
    #[serde(default)]
    pub list_slug_or_id: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

pub(in crate::web) async fn boards_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(query): Query<BoardsQuery>,
) -> Result<Response, WebError> {
    render_boards_view(&service, &session, None, &query).await
}

pub(in crate::web) async fn board_detail_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(query): Query<BoardsQuery>,
) -> Result<Response, WebError> {
    render_boards_view(&service, &session, Some(&id), &query).await
}

async fn resolve_selected_board(
    service: &RocheService,
    session: &WebSession,
    boards: &[Board],
    path_board_id: Option<&str>,
    query_board_id: Option<&str>,
) -> Result<Board, WebError> {
    if let Some(explicit_id) = path_board_id {
        if let Some(found) = boards.iter().find(|b| b.id == explicit_id) {
            return Ok(found.clone());
        }
        return service.boards().get(explicit_id).await.web_err(session);
    }
    if let Some(q_id) = query_board_id.map(str::trim).filter(|s| !s.is_empty()) {
        if let Some(found) = boards.iter().find(|b| b.id == q_id) {
            return Ok(found.clone());
        }
        return service.boards().get(q_id).await.web_err(session);
    }
    boards.iter().find(|b| b.is_active).or_else(|| boards.first()).cloned().ok_or_else(|| {
        WebError::from_app(
            crate::service::AppError::not_found("Board not found"),
            Some(&session.csrf_token),
            session.theme,
        )
    })
}

async fn load_board_view_page(
    service: &RocheService,
    session: &WebSession,
    board_id: &str,
    is_dismissed_view: bool,
    has_query: bool,
    query: &BoardsQuery,
) -> Result<(BoardItemsPage, Option<String>), WebError> {
    if is_dismissed_view {
        let mut all_page = service
            .boards()
            .list_items(board_id, Some(1), Some(BOARD_SNAPSHOT_BUFFER_LIMIT), true, false, None)
            .await
            .web_err(session)?;
        all_page.items.retain(|item| item.is_dismissed);
        all_page.has_more_buffered = false;
        all_page.has_more_live = false;
        all_page.next_live_offset = None;
        return Ok((all_page, None));
    }
    if has_query {
        let mut full_page = service
            .boards()
            .list_items(board_id, Some(1), Some(BOARD_SNAPSHOT_BUFFER_LIMIT), false, false, None)
            .await
            .web_err(session)?;
        full_page.has_more_buffered = false;
        full_page.has_more_live = false;
        full_page.next_live_offset = None;
        return Ok((full_page, None));
    }
    match service
        .boards()
        .list_items(
            board_id,
            query.page,
            Some(BOARD_DEFAULT_PAGE_SIZE),
            false,
            query.live_more,
            query.live_offset,
        )
        .await
    {
        Ok(p) => Ok((p, None)),
        Err(err) if query.live_more => {
            let notice = safe_web_error_message(&err);
            let fallback = service
                .boards()
                .list_items(board_id, Some(1), Some(BOARD_DEFAULT_PAGE_SIZE), false, false, None)
                .await
                .web_err(session)?;
            Ok((fallback, Some(notice)))
        }
        Err(err) => Err(WebError::from_app(err, Some(&session.csrf_token), session.theme)),
    }
}

async fn render_boards_view(
    service: &RocheService,
    session: &WebSession,
    path_board_id: Option<&str>,
    query: &BoardsQuery,
) -> Result<Response, WebError> {
    let boards = service.boards().list().await.web_err(session)?;

    if boards.is_empty() && path_board_id.is_none() {
        let mut body = String::with_capacity(4_096);
        render_empty_boards_onboarding(&mut body, &session.csrf_token);
        return Ok(render_page(&PageShell {
            title: "Boards",
            active: NavSection::Boards,
            csrf_token: &session.csrf_token,
            flash: &session.flash,
            is_refreshing: service.is_refreshing(),
            theme: session.theme,
            search_query: None,
            body_html: &body,
        })
        .into_response());
    }

    let selected_board =
        resolve_selected_board(service, session, &boards, path_board_id, query.board.as_deref())
            .await?;
    let is_dismissed_view =
        query.view.as_deref().is_some_and(|v| v.trim().eq_ignore_ascii_case("dismissed"));
    let clean_q = query.q.as_deref().map(str::trim).filter(|s| !s.is_empty());

    let (page_data, live_error_notice) = load_board_view_page(
        service,
        session,
        &selected_board.id,
        is_dismissed_view,
        clean_q.is_some(),
        query,
    )
    .await?;

    let filtered_items: Vec<BoardItemView> = if let Some(q_str) = clean_q {
        let needle = q_str.to_ascii_lowercase();
        page_data
            .items
            .iter()
            .filter(|item| board_item_matches_query(item, &needle))
            .cloned()
            .collect()
    } else {
        page_data.items.clone()
    };

    let return_url = build_board_href(
        &selected_board.id,
        is_dismissed_view,
        clean_q,
        page_data.page,
        page_data.is_live_page,
        query.live_offset,
    );

    let now = now_utc();
    let mut body = String::with_capacity(18_432);
    render_boards_layout(
        &mut body,
        &boards,
        &selected_board,
        &page_data,
        &filtered_items,
        is_dismissed_view,
        clean_q,
        live_error_notice.as_deref(),
        &return_url,
        &session.csrf_token,
        now,
    );

    Ok(render_page(&PageShell {
        title: &selected_board.title,
        active: NavSection::Boards,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

fn board_item_matches_query(item: &BoardItemView, needle_lower: &str) -> bool {
    item.title.to_ascii_lowercase().contains(needle_lower)
        || item.domain.as_deref().is_some_and(|d| d.to_ascii_lowercase().contains(needle_lower))
        || item.author.as_deref().is_some_and(|a| a.to_ascii_lowercase().contains(needle_lower))
        || item.summary.as_deref().is_some_and(|s| s.to_ascii_lowercase().contains(needle_lower))
}

fn build_board_href(
    board_id: &str,
    is_dismissed_view: bool,
    q: Option<&str>,
    page: usize,
    is_live_page: bool,
    live_offset: Option<usize>,
) -> String {
    let base = format!("/boards/{}", urlencode(board_id));
    let mut parts = Vec::new();
    if is_dismissed_view {
        parts.push("view=dismissed".to_owned());
    }
    if let Some(search) = q.map(str::trim).filter(|s| !s.is_empty()) {
        parts.push(format!("q={}", urlencode(search)));
    }
    if is_live_page {
        parts.push("live_more=1".to_owned());
        if let Some(off) = live_offset {
            parts.push(format!("live_offset={off}"));
        }
    } else if !is_dismissed_view && page > 1 {
        parts.push(format!("page={page}"));
    }
    if parts.is_empty() { base } else { format!("{base}?{}", parts.join("&")) }
}

fn render_empty_boards_onboarding(out: &mut String, csrf: &str) {
    out.push_str(
        "<section class=\"board-empty-onboarding\" aria-label=\"Get started with Boards\">\n\
           <p class=\"entry-kicker\">Ephemeral Front Pages</p>\n\
           <h2>Boards</h2>\n\
           <p class=\"lede\">Follow ranked front pages and community discussions without cluttering your Feed Inbox. Stories stay lightweight until you Star, Queue, or Finish them.</p>\n\
           <h3>Quick Add Hacker News</h3>\n\
           <div class=\"board-quick-add-grid\">\n",
    );
    for (section, title, desc) in [
        ("top", "Hacker News (Front Page)", "Top ranked stories on the front page"),
        ("best", "HN Best", "Highest-voted recent stories &amp; essays"),
        ("ask", "Ask HN", "Community questions &amp; text discussions"),
        ("show", "Show HN", "Projects, tools &amp; launches from builders"),
    ] {
        let btn = format!(
            "<button type=\"submit\" class=\"board-quick-add-btn\">\
               <span class=\"board-quick-add-title\">+ {title}</span>\
               <span class=\"board-quick-add-desc\">{desc}</span>\
             </button>"
        );
        render_inline_csrf_form_with_class(
            out,
            "/web/boards/create",
            csrf,
            Some("board-quick-add-form"),
            &[("adapter_type", "hacker_news"), ("section", section)],
            None,
            &btn,
        );
    }
    out.push_str(
        "</div>\n<div class=\"board-onboarding-custom\">\n<h3>Or Configure a Custom Board</h3>\n",
    );
    render_add_board_form(out, csrf, "ob");
    out.push_str("</div>\n</section>\n");
}

fn render_add_board_form(out: &mut String, csrf: &str, id_prefix: &str) {
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/boards/create\" class=\"rail-form mt-sm\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <input type=\"hidden\" name=\"adapter_type\" value=\"hacker_news\">\n\
           <div class=\"field\">\n\
             <label for=\"{p}-board-section\">Hacker News Section</label>\n\
             <select id=\"{p}-board-section\" name=\"section\">\n\
               <option value=\"top\">Front Page (Top)</option>\n\
               <option value=\"best\">Best</option>\n\
               <option value=\"ask\">Ask HN</option>\n\
               <option value=\"show\">Show HN</option>\n\
               <option value=\"new\">New</option>\n\
             </select>\n\
           </div>\n\
           <div class=\"field\">\n\
             <label for=\"{p}-board-url\">Or Paste HN URL (optional)</label>\n\
             <input id=\"{p}-board-url\" name=\"url\" type=\"text\" inputmode=\"url\" placeholder=\"https://news.ycombinator.com/ask\">\n\
           </div>\n\
           <div class=\"field\">\n\
             <label for=\"{p}-board-title\">Custom Title (optional)</label>\n\
             <input id=\"{p}-board-title\" name=\"title\" type=\"text\" placeholder=\"Default section title\">\n\
           </div>\n\
           <div class=\"field\">\n\
             <label for=\"{p}-board-interval\">Poll Every (min)</label>\n\
             <input id=\"{p}-board-interval\" name=\"poll_interval\" type=\"number\" min=\"5\" max=\"10080\" value=\"30\">\n\
           </div>\n\
           <button type=\"submit\" class=\"btn btn-primary btn-small\">+ Add Board</button>\n\
         </form>\n",
        csrf = esc(csrf),
        p = esc(id_prefix),
    );
}

#[allow(clippy::too_many_arguments)]
fn render_boards_layout(
    out: &mut String,
    boards: &[Board],
    selected: &Board,
    page: &BoardItemsPage,
    items: &[BoardItemView],
    is_dismissed_view: bool,
    q: Option<&str>,
    live_error_notice: Option<&str>,
    return_url: &str,
    csrf: &str,
    now: DateTime<Utc>,
) {
    out.push_str("<div class=\"inbox-layout boards-layout\">\n");
    render_boards_sidebar(out, boards, selected, is_dismissed_view, csrf);
    out.push_str("<section class=\"inbox-stream boards-stream\" aria-label=\"Board stories\">\n");
    render_boards_mobile_controls(out, boards, selected, is_dismissed_view, csrf);
    render_board_toolbar(out, selected, is_dismissed_view, q, return_url, csrf, now);
    if let Some(err_msg) = live_error_notice {
        let _ = writeln!(
            out,
            "<div class=\"board-error-banner\" role=\"alert\">&#9888; {}</div>",
            esc(err_msg)
        );
    } else if board_has_active_error(selected)
        && let Some(raw_err) = selected.last_error.as_deref()
    {
        let _ = writeln!(
            out,
            "<div class=\"board-error-banner\" role=\"status\">&#9888; Last poll error: {}</div>",
            esc(&clean_board_error(raw_err))
        );
    }
    render_board_story_list(
        out,
        selected,
        page,
        items,
        is_dismissed_view,
        q,
        return_url,
        csrf,
        now,
    );
    out.push_str("</section>\n</div>\n");
}

fn render_boards_sidebar(
    out: &mut String,
    boards: &[Board],
    selected: &Board,
    is_dismissed_view: bool,
    csrf: &str,
) {
    let dismissed_count = (selected.item_count - selected.visible_count).max(0);
    let _ = writeln!(
        out,
        "<div class=\"inbox-sidebar boards-sidebar\">\n\
           <div class=\"sidebar-group\">\n\
             <div class=\"sidebar-heading\">Boards ({})</div>\n\
             <ul class=\"sidebar-list\">",
        boards.len(),
    );
    for b in boards {
        let is_cur = b.id == selected.id;
        let attrs = if is_cur { " class=\"is-active\" aria-current=\"true\"" } else { "" };
        let href = format!("/boards/{}", urlencode(&b.id));
        let status_indicator = if !b.is_active {
            "<span class=\"sidebar-board-flag\" title=\"Paused\">Paused</span>"
        } else if board_has_active_error(b) {
            "<span class=\"sidebar-board-err\" title=\"Last poll failed\" aria-label=\"Poll error\">&#9888;</span>"
        } else {
            ""
        };
        let count_html =
            if b.visible_count > 0 { b.visible_count.to_string() } else { String::new() };
        let _ = writeln!(
            out,
            "<li><a href=\"{}\"{attrs}>\
               <span class=\"sidebar-item-main\">\
                 <span class=\"sidebar-label\">{}</span>\
                 {status_indicator}\
               </span>\
               <span class=\"sidebar-count js-board-sidebar-count\" data-board-id=\"{}\">{count_html}</span>\
             </a></li>",
            esc(&href),
            esc(&b.title),
            esc(&b.id),
        );
    }
    let active_href = format!("/boards/{}", urlencode(&selected.id));
    let dismissed_href = format!("/boards/{}?view=dismissed", urlencode(&selected.id));
    let active_attrs =
        if is_dismissed_view { "" } else { " class=\"is-active\" aria-current=\"true\"" };
    let dismissed_attrs =
        if is_dismissed_view { " class=\"is-active\" aria-current=\"true\"" } else { "" };
    let _ = writeln!(
        out,
        "</ul>\n\
         </div>\n\
         <div class=\"sidebar-group\">\n\
           <div class=\"sidebar-heading\">Views</div>\n\
           <ul class=\"sidebar-list\">\n\
             <li><a href=\"{active_href}\"{active_attrs}><span class=\"sidebar-label\">Active</span><span class=\"sidebar-count js-board-active-count\">{active_cnt}</span></a></li>\n\
             <li><a href=\"{dismissed_href}\"{dismissed_attrs}><span class=\"sidebar-label\">Dismissed</span><span class=\"sidebar-count js-board-dismissed-count\">{dismissed_count}</span></a></li>\n\
           </ul>\n\
         </div>\n\
         <div class=\"sidebar-group\">\n\
           <details class=\"sidebar-add-board\">\n\
             <summary>+ Add Board</summary>",
        active_href = esc(&active_href),
        dismissed_href = esc(&dismissed_href),
        active_cnt = selected.visible_count.max(0),
    );
    render_add_board_form(out, csrf, "sb");
    out.push_str("</details>\n</div>\n</div>\n");
}

fn render_boards_mobile_controls(
    out: &mut String,
    boards: &[Board],
    selected: &Board,
    is_dismissed_view: bool,
    csrf: &str,
) {
    out.push_str("<div class=\"boards-mobile-controls\">\n<div class=\"boards-mobile-bar\">\n");
    out.push_str(
        "<select class=\"inbox-mobile-feed-select js-mobile-feed-select\" aria-label=\"Switch board\">\n",
    );
    for b in boards {
        let href = format!("/boards/{}", urlencode(&b.id));
        let sel = if b.id == selected.id { " selected" } else { "" };
        let suffix = if !b.is_active {
            " (Paused)".to_owned()
        } else if b.visible_count > 0 {
            format!(" ({})", b.visible_count)
        } else {
            String::new()
        };
        let _ = writeln!(
            out,
            "<option value=\"{}\"{sel}>{}{suffix}</option>",
            esc(&href),
            esc(&b.title),
        );
    }
    out.push_str("</select>\n<div class=\"pill-bar boards-mobile-views\">\n");
    let active_href = format!("/boards/{}", urlencode(&selected.id));
    let dismissed_href = format!("/boards/{}?view=dismissed", urlencode(&selected.id));
    let active_cls = if is_dismissed_view {
        "class=\"pill\""
    } else {
        "class=\"pill is-active\" aria-current=\"true\""
    };
    let dismissed_cls = if is_dismissed_view {
        "class=\"pill is-active\" aria-current=\"true\""
    } else {
        "class=\"pill\""
    };
    let _ = writeln!(
        out,
        "<a {active_cls} href=\"{}\">Active</a>\n<a {dismissed_cls} href=\"{}\">Dismissed</a>\n</div>\n</div>\n\
         <details class=\"editorial-panel boards-mobile-add\">\n\
           <summary>+ Add Board</summary>",
        esc(&active_href),
        esc(&dismissed_href),
    );
    render_add_board_form(out, csrf, "m");
    out.push_str("</details>\n</div>\n");
}

#[allow(clippy::too_many_arguments)]
fn render_board_toolbar(
    out: &mut String,
    board: &Board,
    is_dismissed_view: bool,
    q: Option<&str>,
    return_url: &str,
    csrf: &str,
    now: DateTime<Utc>,
) {
    let id_enc = urlencode(&board.id);
    let badge = board_source_badge(board);
    let freshness_html = board.snapshot_fetched_at.or(board.last_polled_at).map_or_else(
        || "<span class=\"board-freshness\">Never polled</span>".to_owned(),
        |dt| {
            let rel = format_relative_age(dt, now);
            format!(
                "<span class=\"board-freshness\">Updated <time datetime=\"{}\" title=\"{}\">{}</time></span>",
                esc(&dt.to_rfc3339()),
                esc(&dt.format("%Y-%m-%d %H:%M UTC").to_string()),
                esc(&rel),
            )
        },
    );

    let _ = write!(
        out,
        "<div class=\"inbox-toolbar board-toolbar\">\n\
           <div class=\"board-toolbar-head\">\n\
             <div class=\"inbox-toolbar-title\">\n\
               <h2>{}</h2>\n\
               <span class=\"badge badge-kind\">{}</span>",
        esc(&board.title),
        esc(&badge),
    );
    if !board.is_active {
        out.push_str("<span class=\"badge\">Paused</span>");
    }
    if is_dismissed_view {
        out.push_str("<span class=\"badge badge-dismissed\">Dismissed</span>");
    }
    let _ = writeln!(
        out,
        "</div>\n<div class=\"board-toolbar-sub\">{freshness_html}</div>\n</div>\n\
         <div class=\"inbox-toolbar-actions\">\n\
           <form method=\"get\" action=\"/boards/{id_enc}\" class=\"inbox-toolbar-search board-toolbar-search\">",
    );
    if is_dismissed_view {
        out.push_str("<input type=\"hidden\" name=\"view\" value=\"dismissed\">");
    }
    let _ = write!(
        out,
        "<input type=\"search\" id=\"roche-board-search\" name=\"q\" aria-label=\"Filter stories\" value=\"{}\" placeholder=\"Filter stories…\">\n\
         <button type=\"submit\" class=\"btn btn-small btn-ghost\">Filter</button>",
        esc(q.unwrap_or("")),
    );
    if q.is_some() {
        let clear_href = build_board_href(&board.id, is_dismissed_view, None, 1, false, None);
        let _ = write!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"{}\" aria-label=\"Clear story filter\" title=\"Clear story filter\">&times;</a>",
            esc(&clear_href),
        );
    }
    out.push_str("</form>\n");

    let refresh_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost\" title=\"Refresh {t}\" aria-label=\"Refresh {t}\">{REFRESH_SVG} Refresh</button>",
        t = esc(&board.title),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/boards/{id_enc}/refresh"),
        csrf,
        &[("return_to", return_url)],
        None,
        &refresh_btn,
    );

    render_board_settings_drawer(out, board, &id_enc, return_url, csrf);
    out.push_str("</div>\n</div>\n");
}

fn render_board_settings_drawer(
    out: &mut String,
    board: &Board,
    id_enc: &str,
    return_url: &str,
    csrf: &str,
) {
    let ranked_sel = if board.order_mode == BoardOrderMode::Ranked { " selected" } else { "" };
    let chrono_sel =
        if board.order_mode == BoardOrderMode::Chronological { " selected" } else { "" };
    let _ = write!(
        out,
        "<details class=\"board-settings-details\">\n\
           <summary class=\"btn btn-small btn-ghost\">Settings</summary>\n\
           <div class=\"board-settings-panel\">\n\
             <form method=\"post\" action=\"/web/boards/{id_enc}/edit\" class=\"rail-form\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\n\
               <div class=\"board-settings-grid\">\n\
                 <div class=\"field\">\n\
                   <label for=\"be-title-{id_enc}\">Title</label>\n\
                   <input id=\"be-title-{id_enc}\" name=\"title\" type=\"text\" value=\"{title}\" required>\n\
                 </div>\n\
                 <div class=\"field\">\n\
                   <label for=\"be-order-{id_enc}\">Order</label>\n\
                   <select id=\"be-order-{id_enc}\" name=\"order_mode\">\n\
                     <option value=\"ranked\"{ranked_sel}>Ranked</option>\n\
                     <option value=\"chronological\"{chrono_sel}>Chronological</option>\n\
                   </select>\n\
                 </div>\n\
                 <div class=\"field\">\n\
                   <label for=\"be-interval-{id_enc}\">Poll every (min)</label>\n\
                   <input id=\"be-interval-{id_enc}\" name=\"poll_interval\" type=\"number\" min=\"5\" max=\"10080\" value=\"{interval}\">\n\
                 </div>\n\
               </div>\n\
               <div class=\"board-settings-footer\">\n\
                 <button type=\"submit\" class=\"btn btn-primary btn-small\">Save Settings</button>\n\
               </div>\n\
             </form>\n\
             <div class=\"board-settings-danger-row\">\n",
        id_enc = esc(id_enc),
        csrf = esc(csrf),
        ret = esc(return_url),
        title = esc(&board.title),
        interval = board.poll_interval,
    );
    let (next_active, toggle_label) =
        if board.is_active { ("false", "Pause Board") } else { ("true", "Resume Board") };
    let toggle_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost\">{toggle_label}</button>"
    );
    render_inline_csrf_form(
        out,
        &format!("/web/boards/{id_enc}/toggle-active"),
        csrf,
        &[("active", next_active), ("return_to", return_url)],
        None,
        &toggle_btn,
    );
    render_inline_csrf_form(
        out,
        &format!("/web/boards/{id_enc}/delete"),
        csrf,
        &[],
        Some("Delete this board? Any stories you saved, starred, or finished will be preserved."),
        "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-danger-quiet\">Delete Board</button>",
    );
    out.push_str("</div>\n</div>\n</details>\n");
}

#[allow(clippy::too_many_arguments)]
fn render_board_story_list(
    out: &mut String,
    board: &Board,
    page: &BoardItemsPage,
    items: &[BoardItemView],
    is_dismissed_view: bool,
    q: Option<&str>,
    return_url: &str,
    csrf: &str,
    now: DateTime<Utc>,
) {
    if items.is_empty() {
        let msg = if q.is_some() {
            "No stories match your filter."
        } else if is_dismissed_view {
            "No dismissed stories on this board."
        } else {
            "No stories loaded yet. Click Refresh to fetch the latest snapshot."
        };
        let _ = writeln!(out, "<p class=\"empty\">{msg}</p>");
        if !is_dismissed_view && q.is_none() && (page.page > 1 || page.is_live_page) {
            let first_href = build_board_href(&board.id, false, None, 1, false, None);
            let _ = writeln!(
                out,
                "<p><a class=\"btn btn-small btn-ghost\" href=\"{}\">&larr; Back to top stories</a></p>",
                esc(&first_href),
            );
        }
        return;
    }

    let view_name = if is_dismissed_view { "dismissed" } else { "active" };
    let base_offset = if is_dismissed_view || page.is_live_page {
        0
    } else {
        page.page.saturating_sub(1).saturating_mul(page.per_page)
    };
    let clean_clear_href = build_board_href(&board.id, is_dismissed_view, None, 1, false, None);
    let _ = writeln!(
        out,
        "<ol class=\"inbox-list board-list\" id=\"roche-board-list\" data-board-id=\"{}\" data-view=\"{view_name}\" data-base-offset=\"{base_offset}\" data-return-to=\"{}\">",
        esc(&board.id),
        esc(return_url),
    );
    for item in items {
        render_board_item_row(
            out,
            &board.id,
            Some(board.source_key.as_str()),
            item,
            is_dismissed_view,
            page.is_live_page,
            return_url,
            csrf,
            now,
        );
    }
    let _ = writeln!(
        out,
        "</ol>\n\
         <div id=\"roche-board-filter-empty\" class=\"filter-empty-state\" hidden>\n\
           <p class=\"empty\">No stories match this filter.</p>\n\
           <button type=\"button\" class=\"btn btn-small btn-ghost js-board-filter-clear\" data-clear-href=\"{}\">Clear filter</button>\n\
         </div>",
        esc(&clean_clear_href),
    );
    if is_dismissed_view {
        out.push_str(
            "<p id=\"roche-board-dismissed-empty\" class=\"empty\" hidden>No dismissed stories on this board.</p>\n",
        );
    }

    if !is_dismissed_view && q.is_none() {
        render_board_load_more(out, board, page, return_url);
    }
}

fn render_board_load_more(
    out: &mut String,
    board: &Board,
    page: &BoardItemsPage,
    return_url: &str,
) {
    let show_prev = page.page > 1 || page.is_live_page;
    let next_spec = next_load_more_spec(&board.id, page, return_url);
    if !show_prev && next_spec.is_none() {
        return;
    }
    out.push_str("<div class=\"board-load-more\" id=\"roche-board-load-more\">\n");
    if show_prev {
        let top_href = build_board_href(&board.id, false, None, 1, false, None);
        let _ = writeln!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"{}\">&larr; Top 30</a>",
            esc(&top_href),
        );
    }
    if let Some((href, load_url, label)) = next_spec {
        let _ = writeln!(
            out,
            "<a class=\"btn btn-ghost js-board-load-more\" href=\"{}\" data-load-url=\"{}\">{label}</a>",
            esc(&href),
            esc(&load_url),
        );
    }
    out.push_str("</div>\n");
}

fn next_load_more_spec(
    board_id: &str,
    page: &BoardItemsPage,
    return_url: &str,
) -> Option<(String, String, &'static str)> {
    let id_enc = urlencode(board_id);
    let ret_enc = urlencode(return_url);
    if page.has_more_buffered {
        let next_page = page.page.saturating_add(1);
        let href = format!("/boards/{id_enc}?page={next_page}");
        let load_url = format!("/web/boards/{id_enc}/items?page={next_page}&return_to={ret_enc}");
        Some((href, load_url, "Load more stories"))
    } else if page.has_more_live
        && let Some(next_off) = page.next_live_offset
    {
        let href = format!("/boards/{id_enc}?live_more=1&live_offset={next_off}");
        let load_url = format!(
            "/web/boards/{id_enc}/items?live_more=1&live_offset={next_off}&return_to={ret_enc}"
        );
        Some((href, load_url, "Load more from Hacker News"))
    } else {
        None
    }
}

#[allow(clippy::too_many_arguments)]
pub(super) fn render_board_item_row(
    out: &mut String,
    board_id: &str,
    board_source_key: Option<&str>,
    item: &BoardItemView,
    is_dismissed_view: bool,
    is_live_row: bool,
    return_to: &str,
    csrf: &str,
    now: DateTime<Utc>,
) {
    let board_enc = urlencode(board_id);
    let ext_enc = urlencode(&item.external_id);
    let reader_href =
        format!("/boards/{board_enc}/read/{ext_enc}?return_to={}", urlencode(return_to));
    let is_self_post = board_item_is_self_post(item);

    let mut row_cls = String::from("inbox-row board-row js-triage-row js-kbd-row");
    if item.is_seen {
        row_cls.push_str(" board-seen");
    }
    if item.is_finished {
        row_cls.push_str(" is-finished");
    }
    if item.is_dismissed {
        row_cls.push_str(" is-dismissed");
    }
    let live_attr = if is_live_row { " data-live-row=\"1\"" } else { "" };
    let summary_plain = item.summary.as_deref().map_or_default(clean_plain_excerpt);
    let search_blob = format!(
        "{} {} {} {}",
        item.title,
        item.domain.as_deref().unwrap_or(""),
        item.author.as_deref().unwrap_or(""),
        summary_plain,
    )
    .to_ascii_lowercase();

    let _ = writeln!(
        out,
        "<li class=\"{row_cls}\" data-board-id=\"{}\" data-external-id=\"{}\" data-rank=\"{}\" data-board-search=\"{}\"{live_attr}>\n\
           <div class=\"board-rank-col\" aria-hidden=\"true\">{}</div>\n\
           <div class=\"inbox-main\">\n\
             <div class=\"inbox-title\"><a class=\"js-kbd-open\" href=\"{}\">{}</a></div>",
        esc(board_id),
        esc(&item.external_id),
        item.rank,
        esc(&search_blob),
        item.rank,
        esc(&reader_href),
        esc(&item.title),
    );

    render_board_item_byline(out, item, board_source_key, is_self_post, now);

    let show_excerpt = is_self_post
        || item.title.trim().to_ascii_lowercase().starts_with("ask hn:")
        || item.title.trim().to_ascii_lowercase().starts_with("show hn:");
    if show_excerpt && !summary_plain.is_empty() {
        let _ = writeln!(out, "<p class=\"item-excerpt-clamped\">{}</p>", esc(&summary_plain));
    }
    out.push_str("</div>\n");

    render_board_item_controls(out, &board_enc, &ext_enc, item, is_dismissed_view, return_to, csrf);
    out.push_str("</li>\n");
}

fn render_board_item_byline(
    out: &mut String,
    item: &BoardItemView,
    board_source_key: Option<&str>,
    is_self_post: bool,
    now: DateTime<Utc>,
) {
    out.push_str("<div class=\"item-byline board-byline\">");
    if item.is_finished {
        out.push_str("<span class=\"badge badge-finished\">&#10003; Finished</span>");
    }
    if item.in_want_to_read {
        out.push_str("<span class=\"badge badge-queued js-board-queue-badge\">In Queue</span>");
    }
    if let Some((badge_cls, label)) = board_item_kind_badge(item, board_source_key) {
        let _ = write!(out, "<span class=\"badge badge-kind{badge_cls}\">{}</span>", esc(label));
    }
    if !is_self_post
        && let Some(dom) = item.domain.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        let _ = write!(out, "<span class=\"byline-item board-meta-domain\">{}</span>", esc(dom));
    }
    if let Some(score) = item.score {
        let _ = write!(out, "<span class=\"byline-item board-meta-points\">{score} pts</span>");
    }
    if let Some(disc_href) = item.discussion_url.as_deref().and_then(sanitize_non_local_href) {
        let comments_label = format_comments_label(item.comment_count);
        let aria_label = format!("Open {comments_label} on Hacker News for {}", item.title);
        let _ = write!(
            out,
            "<a class=\"board-comments-link js-kbd-discuss\" href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\" aria-label=\"{}\">{} &#8599;</a>",
            esc(disc_href),
            esc(&aria_label),
            esc(&comments_label),
        );
    }
    if let Some(author) = item.author.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = write!(out, "<span class=\"byline-item\">by {}</span>", esc(author));
    }
    if let Some(dt) = item.published_at {
        let rel = format_relative_age(dt, now);
        let _ = write!(
            out,
            "<span class=\"byline-item\"><time datetime=\"{}\" title=\"{}\">{}</time></span>",
            esc(&dt.to_rfc3339()),
            esc(&dt.format("%Y-%m-%d %H:%M UTC").to_string()),
            esc(&rel),
        );
    }
    out.push_str("</div>\n");
}

fn render_board_item_controls(
    out: &mut String,
    board_enc: &str,
    ext_enc: &str,
    item: &BoardItemView,
    is_dismissed_view: bool,
    return_to: &str,
    csrf: &str,
) {
    out.push_str("<div class=\"inbox-controls inbox-controls-subtle\">\n");

    let (star_glyph, star_pressed, star_cls, star_action_word) = if item.is_favorite {
        ("&#9733;", "true", "btn btn-small btn-ghost inbox-star-btn is-starred", "Unstar")
    } else {
        ("&#9734;", "false", "btn btn-small btn-ghost inbox-star-btn", "Star")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Toggle Star\" data-title=\"{title}\" aria-label=\"{star_action_word} {title}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        title = esc(&item.title),
    );
    if item.is_favorite
        && let Some(eid) = item.linked_entry_id.as_deref()
    {
        render_inline_csrf_form(
            out,
            &format!("/web/items/{}/star", urlencode(eid)),
            csrf,
            &[("return_to", return_to)],
            None,
            &star_btn,
        );
    } else {
        render_inline_csrf_form(
            out,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/promote"),
            csrf,
            &[("action", "star"), ("return_to", return_to)],
            None,
            &star_btn,
        );
    }

    render_board_item_queue_control(out, board_enc, ext_enc, item, return_to, csrf);

    out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>");

    if is_dismissed_view || item.is_dismissed {
        let restore_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\" title=\"Restore story\" aria-label=\"Restore {}\">{RESTORE_BOX_SVG} Restore</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/undismiss"),
            csrf,
            &[("return_to", return_to)],
            "board-restore",
            &item.title,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/dismiss"),
            &restore_btn,
        );
    } else {
        let dismiss_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-icon\" title=\"Dismiss story\" aria-label=\"Dismiss {}\">{DISMISS_SVG}</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/dismiss"),
            csrf,
            &[("return_to", return_to)],
            "board-dismiss",
            &item.title,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/undismiss"),
            &dismiss_btn,
        );
    }
    out.push_str("\n</div>\n");
}

fn render_board_item_queue_control(
    out: &mut String,
    board_enc: &str,
    ext_enc: &str,
    item: &BoardItemView,
    return_to: &str,
    csrf: &str,
) {
    if item.in_want_to_read
        && let Some(eid) = item.linked_entry_id.as_deref()
    {
        let eid_enc = urlencode(eid);
        let queue_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet is-in-queue\" title=\"Remove from Queue\" aria-label=\"In Queue — remove {} from Queue\">&#10003; In Queue</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/want/read/remove/{eid_enc}"),
            csrf,
            &[("return_to", return_to)],
            "unqueue",
            &item.title,
            &format!("/web/entries/{eid_enc}/list-add"),
            &queue_btn,
        );
    } else if let Some(eid) = item.linked_entry_id.as_deref() {
        let eid_enc = urlencode(eid);
        let queue_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\" title=\"Add to Queue\" aria-label=\"Add {} to Queue\">+ Queue</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/entries/{eid_enc}/list-add"),
            csrf,
            &[("list_slug_or_id", WANT_TO_READ_COLLECTION_ID), ("return_to", return_to)],
            "queue",
            &item.title,
            &format!("/web/want/read/remove/{eid_enc}"),
            &queue_btn,
        );
    } else {
        let queue_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\" title=\"Add to Queue\" aria-label=\"Add {} to Queue\">+ Queue</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/promote"),
            csrf,
            &[("action", "queue"), ("return_to", return_to)],
            "board-queue",
            &item.title,
            "",
            &queue_btn,
        );
    }
}

pub(in crate::web) async fn board_items_fragment_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(query): Query<BoardItemsFragmentQuery>,
) -> Result<Response, WebError> {
    let default_ret = format!("/boards/{}", urlencode(&id));
    let return_to = safe_local_redirect(query.return_to.as_deref(), &default_ret);

    let mut page_data = service
        .boards()
        .list_items(
            &id,
            query.page,
            Some(BOARD_DEFAULT_PAGE_SIZE),
            false,
            query.live_more,
            query.live_offset,
        )
        .await
        .web_err(&session)?;

    // If buffered slice was already exhausted by dismissals/backfill, transparently fall through
    // to live page fetching so the user never has to click Load More twice.
    if !query.live_more
        && page_data.items.is_empty()
        && page_data.has_more_live
        && let Some(next_off) = page_data.next_live_offset
        && let Ok(live_page) = service
            .boards()
            .list_items(&id, Some(1), Some(BOARD_DEFAULT_PAGE_SIZE), false, true, Some(next_off))
            .await
    {
        page_data = live_page;
    }

    let now = now_utc();
    let mut rows_html = String::with_capacity(page_data.items.len().saturating_mul(640));
    for item in &page_data.items {
        render_board_item_row(
            &mut rows_html,
            &page_data.board.id,
            Some(page_data.board.source_key.as_str()),
            item,
            false,
            page_data.is_live_page,
            &return_to,
            &session.csrf_token,
            now,
        );
    }
    let next_spec = next_load_more_spec(&page_data.board.id, &page_data, &return_to);
    let (has_more, next_href, next_load_url, next_label) = match next_spec {
        Some((href, load_url, label)) => (true, Some(href), Some(load_url), Some(label)),
        None => (false, None, None, None),
    };

    Ok(Json(serde_json::json!({
        "rows_html": rows_html,
        "count": page_data.items.len(),
        "is_live_page": page_data.is_live_page,
        "has_more": has_more,
        "next_href": next_href,
        "next_load_url": next_load_url,
        "next_label": next_label,
    }))
    .into_response())
}

pub(in crate::web) async fn post_board_create(
    State(service): State<Arc<RocheService>>,
    Form(form): Form<CreateBoardForm>,
) -> Response {
    let adapter_type = match form.adapter_type.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw) => match BoardType::parse_flexible(raw) {
            Ok(t) => Some(t),
            Err(e) => return redirect_with_flash("/boards", "err", &e.to_string()),
        },
        None => None,
    };
    let clean_url = form.url.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
    let clean_section =
        form.section.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
    let clean_title =
        form.title.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
    let poll_interval = match form.poll_interval.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        Some(raw) => match raw.parse::<i64>() {
            Ok(v) => Some(v),
            Err(_) => {
                return redirect_with_flash(
                    "/boards",
                    "err",
                    "Poll interval must be a number of minutes",
                );
            }
        },
        None => None,
    };

    match service
        .boards()
        .create(CreateBoardInput {
            adapter_type,
            url: clean_url,
            section: clean_section,
            source_config: None,
            title: clean_title,
            order_mode: None,
            poll_interval,
        })
        .await
    {
        Ok(board) => redirect_with_flash(
            &format!("/boards/{}", urlencode(&board.id)),
            "notice",
            &format!("Added board {}", board.title),
        ),
        Err(err) => redirect_with_flash("/boards", "err", &safe_web_error_message(&err)),
    }
}

pub(in crate::web) async fn post_board_refresh_all(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<BoardReturnForm>,
) -> Result<Response, WebError> {
    let dest = safe_local_redirect(form.return_to.as_deref(), "/boards");
    let count = service.boards().refresh_all_in_background().await.web_err(&session)?;
    let msg = if count == 0 {
        "No active boards to refresh".to_owned()
    } else {
        let noun = if count == 1 { "board" } else { "boards" };
        format!("Refreshing {count} {noun} in background…")
    };
    Ok(redirect_with_flash(&dest, "notice", &msg))
}

pub(in crate::web) async fn post_board_refresh(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<BoardReturnForm>,
) -> Result<Response, WebError> {
    let board = service.boards().get(&id).await.web_err(&session)?;
    let service_bg = Arc::clone(&service);
    let board_id = board.id.clone();
    service.spawn_background(async move {
        let _ = service_bg.boards().refresh(&board_id).await;
    });
    let fallback = format!("/boards/{}", urlencode(&board.id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    Ok(redirect_with_flash(&dest, "notice", &format!("Refreshing {} in background…", board.title)))
}

pub(in crate::web) async fn post_board_edit(
    State(service): State<Arc<RocheService>>,
    Path(id): Path<String>,
    Form(form): Form<EditBoardForm>,
) -> Response {
    let fallback = format!("/boards/{}", urlencode(&id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    let clean_title = form.title.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let order_mode = match form.order_mode.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw) => match raw.parse::<BoardOrderMode>() {
            Ok(mode) => Some(mode),
            Err(e) => return redirect_with_flash(&dest, "err", &e.to_string()),
        },
        None => None,
    };
    let poll_interval = match form.poll_interval.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        Some(raw) => match raw.parse::<i64>() {
            Ok(v) => Some(v),
            Err(_) => {
                return redirect_with_flash(
                    &dest,
                    "err",
                    "Poll interval must be a number of minutes",
                );
            }
        },
        None => None,
    };
    match service.boards().update(&id, clean_title, order_mode, poll_interval, None).await {
        Ok(_) => redirect_with_flash(&dest, "notice", "Updated board settings"),
        Err(err) => redirect_with_flash(&dest, "err", &safe_web_error_message(&err)),
    }
}

pub(in crate::web) async fn post_board_toggle_active(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<BoardToggleForm>,
) -> Result<Response, WebError> {
    let want_active = parse_form_bool(Some(&form.active));
    let msg = if want_active {
        let _ = service.boards().unarchive(&id).await.web_err(&session)?;
        "Resumed board polling"
    } else {
        let _ = service.boards().archive(&id).await.web_err(&session)?;
        "Paused board polling"
    };
    let fallback = format!("/boards/{}", urlencode(&id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    Ok(redirect_with_flash(&dest, "notice", msg))
}

pub(in crate::web) async fn post_board_delete(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
) -> Result<Response, WebError> {
    service.boards().delete(&id).await.web_err(&session)?;
    Ok(redirect_with_flash(
        "/boards",
        "notice",
        "Deleted board (saved, starred & finished entries preserved)",
    ))
}

pub(in crate::web) async fn post_board_item_dismiss(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    headers: HeaderMap,
    Path((board_id, external_id)): Path<(String, String)>,
    Form(form): Form<BoardDismissForm>,
) -> Result<Response, WebError> {
    let outcome = service
        .boards()
        .dismiss(&board_id, &external_id, form.backfill_offset)
        .await
        .web_err(&session)?;
    let fallback = format!("/boards/{}", urlencode(&board_id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);

    if wants_json(&headers) {
        let now = now_utc();
        let skip_backfill = parse_form_bool(form.skip_backfill.as_deref());
        let board_meta = service.boards().get(&board_id).await.ok();
        let source_key = board_meta.as_ref().map(|b| b.source_key.as_str());
        let replacement_item = if skip_backfill { None } else { outcome.replacement_item };
        let replacement_html = replacement_item.as_ref().map(|item| {
            let mut row_buf = String::with_capacity(1_024);
            render_board_item_row(
                &mut row_buf,
                &board_id,
                source_key,
                item,
                false,
                false,
                &dest,
                &session.csrf_token,
                now,
            );
            row_buf
        });
        let replacement_external_id = replacement_item.as_ref().map(|i| i.external_id.clone());
        let board_counts =
            board_meta.map(|b| (b.visible_count.max(0), (b.item_count - b.visible_count).max(0)));
        return Ok(Json(serde_json::json!({
            "ok": true,
            "board_id": outcome.board_id,
            "external_id": outcome.external_id,
            "replacement_external_id": replacement_external_id,
            "replacement_html": replacement_html,
            "replacement_item": replacement_item,
            "visible_count": board_counts.map(|c| c.0),
            "dismissed_count": board_counts.map(|c| c.1),
        }))
        .into_response());
    }

    Ok(redirect_with_flash(&dest, "notice", "Dismissed story"))
}

pub(in crate::web) async fn post_board_item_undismiss(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    headers: HeaderMap,
    Path((board_id, external_id)): Path<(String, String)>,
    Form(form): Form<BoardReturnForm>,
) -> Result<Response, WebError> {
    let state = service.boards().undismiss(&board_id, &external_id).await.web_err(&session)?;

    if wants_json(&headers) {
        let board_counts = service
            .boards()
            .get(&board_id)
            .await
            .ok()
            .map(|b| (b.visible_count.max(0), (b.item_count - b.visible_count).max(0)));
        return Ok(Json(serde_json::json!({
            "ok": true,
            "board_id": state.board_id,
            "external_id": state.external_id,
            "visible_count": board_counts.map(|c| c.0),
            "dismissed_count": board_counts.map(|c| c.1),
        }))
        .into_response());
    }

    let fallback = format!("/boards/{}", urlencode(&board_id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    Ok(redirect_with_flash(&dest, "notice", "Restored story"))
}

pub(in crate::web) async fn post_board_item_promote(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    headers: HeaderMap,
    Path((board_id, external_id)): Path<(String, String)>,
    Form(form): Form<BoardPromoteForm>,
) -> Result<Response, WebError> {
    let action_norm = form.action.trim().to_ascii_lowercase();
    let promote_action = match action_norm.as_str() {
        "queue" => BoardPromoteAction::Queue,
        "star" => BoardPromoteAction::Star,
        "finish" => BoardPromoteAction::Finish {
            notes: form
                .notes
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned),
        },
        "tag" => BoardPromoteAction::Tag {
            tag_id: None,
            tag_name: form
                .tag_name
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(str::to_owned),
        },
        "list" => BoardPromoteAction::List {
            list_slug_or_id: form.list_slug_or_id.as_deref().unwrap_or("").trim().to_owned(),
        },
        _ => {
            return Err(WebError::from_app(
                crate::service::AppError::bad_request("Unsupported board promote action"),
                Some(&session.csrf_token),
                session.theme,
            ));
        }
    };

    let result = service
        .boards()
        .promote(&board_id, &external_id, promote_action)
        .await
        .web_err(&session)?;
    let eid_enc = urlencode(&result.entry_id);

    if wants_json(&headers) {
        let rank_href = if action_norm == "finish"
            && let Ok(Some(ed)) = service.catalog().get_entry_detail(&result.entry_id).await
            && let Ok(Some(slug)) = service.catalog().rank_target_after_consumption(&ed.entry).await
        {
            Some(format!("/rank/{}?focus={eid_enc}", urlencode(&slug)))
        } else {
            None
        };
        return Ok(Json(serde_json::json!({
            "ok": true,
            "entry_id": result.entry_id,
            "created_new": result.created_new,
            "is_favorite": result.item.is_favorite,
            "in_want_to_read": result.item.in_want_to_read,
            "is_finished": result.item.is_finished,
            "star_action": format!("/web/items/{eid_enc}/star"),
            "queue_action": format!("/web/entries/{eid_enc}/list-add"),
            "unqueue_action": format!("/web/want/read/remove/{eid_enc}"),
            "unfinish_action": format!("/web/items/{eid_enc}/unfinish"),
            "entry_href": format!("/entries/{eid_enc}"),
            "rank_href": rank_href,
        }))
        .into_response());
    }

    if action_norm == "finish"
        && parse_form_bool(form.rank_after.as_deref())
        && let Ok(Some(ed)) = service.catalog().get_entry_detail(&result.entry_id).await
        && let Ok(Some(slug)) = service.catalog().rank_target_after_consumption(&ed.entry).await
    {
        return Ok(redirect_with_flash(
            &format!("/rank/{}?focus={eid_enc}", urlencode(&slug)),
            "notice",
            "Marked finished & logged to Diary",
        ));
    }

    let fallback = format!("/boards/{}", urlencode(&board_id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    match action_norm.as_str() {
        "queue" => Ok(redirect_with_flash(&dest, "notice", "Added to Queue")),
        "finish" => Ok(redirect_with_flash(&dest, "notice", "Marked finished & logged to Diary")),
        "tag" => Ok(redirect_with_flash(&dest, "notice", "Added tag")),
        "list" => Ok(redirect_with_flash(&dest, "notice", "Added to list")),
        _ => Ok(redirect_see_other(&dest)),
    }
}
