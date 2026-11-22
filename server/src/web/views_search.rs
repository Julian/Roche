use std::{collections::HashMap, fmt::Write as _, sync::Arc};

use axum::{
    extract::{Extension, Query, State},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    auth::WebSession,
    html::{
        CreatorYearStyle, NavSection, PageShell, WebError, WithWebCsrf as _,
        consumption_status_label, content_type_badge_class, content_type_label, esc,
        render_creator_year_meta, render_page, urlencode,
    },
    links::load_artist_id_by_lower_name,
};
use crate::{
    domain::{ConsumptionStatus, ContentType, ItemStatus},
    service::{RocheService, WorkspaceSearchResults},
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct SearchQuery {
    pub q: Option<String>,
}

pub(super) async fn search_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(query): Query<SearchQuery>,
) -> Result<Response, WebError> {
    let raw_q = query.q.as_deref().map_or("", str::trim);
    let results = if raw_q.is_empty() {
        WorkspaceSearchResults::default()
    } else {
        service.search_workspace(raw_q, 24).await.web_err(&session)?
    };

    let mut creators: Vec<String> = results
        .catalog_entries
        .iter()
        .flat_map(|e| e.creators.iter().cloned())
        .chain(results.want_entries.iter().flat_map(|w| w.entry.creators.iter().cloned()))
        .chain(results.diary_logs.iter().flat_map(|d| d.entry.creators.iter().cloned()))
        .collect();
    creators.sort_unstable();
    creators.dedup();
    let artist_links = load_artist_id_by_lower_name(&service, &creators).await;

    let mut body = String::with_capacity(8_192);
    render_search_body(&mut body, &results, &artist_links);

    let title = if results.query.is_empty() {
        "Search".to_owned()
    } else {
        format!("Search: {}", results.query)
    };
    Ok(render_page(&PageShell {
        title: &title,
        active: NavSection::None,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: (!results.query.is_empty()).then_some(results.query.as_str()),
        body_html: &body,
    })
    .into_response())
}

fn render_search_body(
    out: &mut String,
    res: &WorkspaceSearchResults,
    artist_links: &HashMap<String, String>,
) {
    let total_hits = res.catalog_entries.len()
        + res.collections.len()
        + res.want_entries.len()
        + res.inbox_items.len()
        + res.diary_logs.len();

    let _ = write!(
        out,
        "<section class=\"search-page\" aria-label=\"Global search results\">\n\
           <div class=\"list-detail-head\">\n\
             <div class=\"list-detail-title-group\">\n\
               <h2>Search Roche</h2>\n",
    );
    if !res.query.is_empty() {
        let _ = write!(out, "<span class=\"list-count-badge\">{total_hits}</span>");
    }
    out.push_str("</div>\n</div>\n");

    let _ = write!(
        out,
        "<form method=\"get\" action=\"/search\" class=\"search-page-form\" role=\"search\" aria-label=\"Workspace search\">\n\
           <input type=\"search\" name=\"q\" value=\"{}\" placeholder=\"Search catalog, lists, queue, feed inbox, and diary…\" aria-label=\"Search across all of Roche\" autofocus>\n\
           <button type=\"submit\" class=\"btn btn-primary btn-small\">Search</button>",
        esc(&res.query),
    );
    if !res.query.is_empty() {
        out.push_str("<a class=\"btn btn-small btn-ghost\" href=\"/search\">Clear</a>");
    }
    out.push_str("</form>\n");

    if res.query.is_empty() {
        out.push_str(
            "<p class=\"empty\">Type a title, artist, author, list name, feed item, or diary note to search across your entire Roche library.</p>\n</section>\n",
        );
        return;
    }

    if total_hits == 0 {
        let _ = writeln!(
            out,
            "<p class=\"empty\">No results matched &ldquo;{}&rdquo; across Catalog, Lists, Queue, Inbox, or Diary. <a href=\"/search\">Clear search</a>.</p>\n</section>",
            esc(&res.query),
        );
        return;
    }

    render_search_jump_pills(out, res);

    let return_to = format!("/search?q={}", urlencode(&res.query));
    render_search_catalog_section(out, res, artist_links);
    render_search_lists_section(out, res);
    render_search_want_section(out, res, artist_links, &return_to);
    render_search_inbox_section(out, res, &return_to);
    render_search_diary_section(out, res, artist_links);

    out.push_str("</section>\n");
}

fn render_search_jump_pills(out: &mut String, res: &WorkspaceSearchResults) {
    out.push_str("<nav class=\"pill-bar mb-lg\" aria-label=\"Result sections\">\n");
    for (id, label, count) in [
        ("search-catalog", "Catalog", res.catalog_entries.len()),
        ("search-lists", "Lists", res.collections.len()),
        ("search-queue", "Queue", res.want_entries.len()),
        ("search-inbox", "Inbox & Archive", res.inbox_items.len()),
        ("search-diary", "Diary", res.diary_logs.len()),
    ] {
        if count > 0 {
            let _ = writeln!(out, "<a class=\"pill\" href=\"#{id}\">{label} ({count})</a>");
        }
    }
    out.push_str("</nav>\n");
}

fn render_search_catalog_section(
    out: &mut String,
    res: &WorkspaceSearchResults,
    artist_links: &HashMap<String, String>,
) {
    if res.catalog_entries.is_empty() {
        return;
    }
    let _ = writeln!(
        out,
        "<section id=\"search-catalog\" class=\"search-group\">\n\
           <div class=\"section-head\"><h3>Catalog Works ({})</h3>\
             <a class=\"section-action\" href=\"/lists?q={}\">Filter in Lists &rarr;</a></div>\n\
           <ul class=\"search-result-list\">",
        res.catalog_entries.len(),
        esc(&urlencode(&res.query)),
    );
    for entry in &res.catalog_entries {
        let _ = write!(
            out,
            "<li class=\"search-result-row\">\
               <div class=\"search-result-main\">\
                 <a class=\"search-result-title\" href=\"/entries/{}\">{}</a>",
            esc(&urlencode(&entry.id)),
            esc(&entry.title),
        );
        render_creator_year_meta(
            out,
            &entry.creators,
            entry.release_year,
            artist_links,
            CreatorYearStyle::InlineSoft { open_new_tab: false },
        );
        let _ = writeln!(
            out,
            "</div><span class=\"badge\">{}</span></li>",
            esc(entry.kind.display_label()),
        );
    }
    out.push_str("</ul>\n</section>\n");
}

fn render_search_lists_section(out: &mut String, res: &WorkspaceSearchResults) {
    if res.collections.is_empty() {
        return;
    }
    let _ = writeln!(
        out,
        "<section id=\"search-lists\" class=\"search-group\">\n\
           <div class=\"section-head\"><h3>Lists ({})</h3>\
             <a class=\"section-action\" href=\"/lists\">All Lists &rarr;</a></div>\n\
           <ul class=\"search-result-list\">",
        res.collections.len(),
    );
    for col in &res.collections {
        let slug = col.slug.as_deref().unwrap_or(&col.id);
        let _ = write!(
            out,
            "<li class=\"search-result-row\">\
               <div class=\"search-result-main\">\
                 <a class=\"search-result-title\" href=\"/lists/{}\">{}</a>",
            esc(&urlencode(slug)),
            esc(&col.title),
        );
        if let Some(sum) = col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            let _ = write!(out, " <span class=\"text-soft\">&mdash; {}</span>", esc(sum));
        }
        let _ = writeln!(
            out,
            "</div><span class=\"list-count-badge\">{}</span></li>",
            col.member_count,
        );
    }
    out.push_str("</ul>\n</section>\n");
}

fn render_search_want_section(
    out: &mut String,
    res: &WorkspaceSearchResults,
    artist_links: &HashMap<String, String>,
    return_to: &str,
) {
    if res.want_entries.is_empty() {
        return;
    }
    let _ = writeln!(
        out,
        "<section id=\"search-queue\" class=\"search-group\">\n\
           <div class=\"section-head\"><h3>Queue &amp; Want ({})</h3>\
             <a class=\"section-action\" href=\"/queue\">Open Queue &rarr;</a></div>\n\
           <ul class=\"search-result-list\">",
        res.want_entries.len(),
    );
    let ret_enc = urlencode(return_to);
    for wm in &res.want_entries {
        let href = if wm.entry.kind.is_reader_openable() && wm.entry.url.is_some() {
            format!("/reader/{}?return_to={ret_enc}", urlencode(&wm.entry.id))
        } else {
            format!("/entries/{}", urlencode(&wm.entry.id))
        };
        let _ = write!(
            out,
            "<li class=\"search-result-row\">\
               <div class=\"search-result-main\">\
                 <a class=\"search-result-title\" href=\"{}\">{}</a>",
            esc(&href),
            esc(&wm.entry.title),
        );
        render_creator_year_meta(
            out,
            &wm.entry.creators,
            wm.entry.release_year,
            artist_links,
            CreatorYearStyle::InlineSoft { open_new_tab: false },
        );
        let _ = writeln!(out, "</div><span class=\"badge\">{}</span></li>", esc(&wm.queue_title));
    }
    out.push_str("</ul>\n</section>\n");
}

fn render_search_inbox_section(out: &mut String, res: &WorkspaceSearchResults, return_to: &str) {
    if res.inbox_items.is_empty() {
        return;
    }
    let _ = writeln!(
        out,
        "<section id=\"search-inbox\" class=\"search-group\">\n\
           <div class=\"section-head\"><h3>Feed Inbox &amp; Archive ({})</h3>\
             <a class=\"section-action\" href=\"/inbox?status=all&amp;q={}\">Filter in Inbox &rarr;</a></div>\n\
           <ul class=\"search-result-list\">",
        res.inbox_items.len(),
        esc(&urlencode(&res.query)),
    );
    let ret_enc = urlencode(return_to);
    for item in &res.inbox_items {
        let reader_href = format!("/reader/{}?return_to={ret_enc}", urlencode(&item.id));
        let _ = write!(
            out,
            "<li class=\"search-result-row\">\
               <div class=\"search-result-main\">\
                 <a class=\"search-result-title\" href=\"{}\">{}</a>\
                 <div class=\"item-byline\">",
            esc(&reader_href),
            esc(&item.title),
        );
        if item.content_type != ContentType::Article {
            let _ = write!(
                out,
                "<span class=\"badge badge-kind{}\">{}</span>",
                content_type_badge_class(item.content_type),
                esc(content_type_label(item.content_type)),
            );
        }
        if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
            let _ = write!(out, "<span class=\"byline-item\">{}</span>", esc(ft));
        }
        if let Some(dt) = item.published_at {
            let _ = write!(out, "<span class=\"byline-item\">{}</span>", dt.format("%b %d, %Y"));
        }
        let status_badge = if item.status == ItemStatus::Archived { "Archived" } else { "Unread" };
        let _ = writeln!(out, "</div></div><span class=\"badge\">{status_badge}</span></li>");
    }
    out.push_str("</ul>\n</section>\n");
}

fn render_search_diary_section(
    out: &mut String,
    res: &WorkspaceSearchResults,
    artist_links: &HashMap<String, String>,
) {
    if res.diary_logs.is_empty() {
        return;
    }
    let _ = writeln!(
        out,
        "<section id=\"search-diary\" class=\"search-group\">\n\
           <div class=\"section-head\"><h3>Diary ({})</h3>\
             <a class=\"section-action\" href=\"/diary\">Full Diary &rarr;</a></div>\n\
           <ul class=\"search-result-list\">",
        res.diary_logs.len(),
    );
    for log in &res.diary_logs {
        let _ = write!(
            out,
            "<li class=\"search-result-row\">\
               <div class=\"search-result-main\">\
                 <div><a class=\"search-result-title\" href=\"/entries/{}\">{}</a>",
            esc(&urlencode(&log.entry.id)),
            esc(&log.entry.title),
        );
        render_creator_year_meta(
            out,
            &log.entry.creators,
            log.entry.release_year,
            artist_links,
            CreatorYearStyle::InlineSoft { open_new_tab: false },
        );
        out.push_str("</div>");
        if let Some(notes) = log.notes.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            let _ = write!(out, "<p class=\"diary-entry-note\">{}</p>", esc(notes));
        }
        let status_label = consumption_status_label(log.status);
        let badge = if log.status == ConsumptionStatus::Consumed {
            format!("<span class=\"byline-item\">{}</span>", log.ts.format("%b %-d, %Y"))
        } else {
            format!(
                "<span class=\"byline-item\">{}</span> <span class=\"badge\">{status_label}</span>",
                log.ts.format("%b %-d, %Y")
            )
        };
        let _ = writeln!(out, "</div><div class=\"search-result-meta\">{badge}</div></li>");
    }
    out.push_str("</ul>\n</section>\n");
}
