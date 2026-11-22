use std::{collections::HashMap, fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use chrono::{DateTime, Datelike as _, NaiveDate, Utc};
use url::Url;

use super::{ArchiveBatchForm, InboxQuery, ItemReturnForm, SaveUrlForm, render_audio_play_button};
use crate::{
    db::{Feed, Item, ItemFilter, WANT_TO_READ_COLLECTION_ID},
    domain::{ContentType, ItemStatus, ItemStatusFilter},
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            ARCHIVE_BOX_SVG, NavSection, PageShell, RESTORE_BOX_SVG, WebError, WithWebCsrf as _,
            content_type_badge_class, content_type_label, esc, format_audio_duration,
            parse_csv_list, redirect_see_other, redirect_with_flash, render_feed_favicon,
            render_inline_csrf_form, render_page, render_triage_form, safe_local_redirect,
            urlencode,
        },
        links::sanitize_non_local_href,
    },
};

fn parse_inbox_kind_filter(raw: Option<&str>) -> (Option<ContentType>, Option<&'static str>) {
    match raw.map(str::trim) {
        Some("article") => (Some(ContentType::Article), Some("article")),
        Some("audio") => (Some(ContentType::Audio), Some("audio")),
        Some("video") => (Some(ContentType::Video), Some("video")),
        Some("paper") => (Some(ContentType::Paper), Some("paper")),
        Some("pdf") => (Some(ContentType::Pdf), Some("pdf")),
        _ => (None, None),
    }
}

pub(in crate::web) async fn inbox_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(q): Query<InboxQuery>,
) -> Result<Response, WebError> {
    let status_filter = match q.status.as_deref() {
        Some("all") => ItemStatusFilter::All,
        Some("archived") => ItemStatusFilter::Archived,
        _ => ItemStatusFilter::Unread,
    };
    let status_slug = match status_filter {
        ItemStatusFilter::All => "all",
        ItemStatusFilter::Archived => "archived",
        ItemStatusFilter::Unread => "unread",
    };
    let (content_type, clean_kind) = parse_inbox_kind_filter(q.kind.as_deref());
    let clean_feed = q.feed.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let clean_tag = q.tag.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let clean_search = q.q.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let page_num = q.page.unwrap_or(1).max(1);
    let filter = ItemFilter {
        status: Some(status_filter),
        feed_id: clean_feed.map(ToOwned::to_owned),
        tag_id: clean_tag.map(ToOwned::to_owned),
        inbox_only: Some(true),
        content_type,
        include_content: Some(false),
        search: clean_search.map(ToOwned::to_owned),
        page: Some(page_num),
        per_page: Some(40),
        ..ItemFilter::default()
    };

    let sync = service.sync();
    let tags_svc = service.tags();
    let (page_res, feeds, sync_res, labels, all_tags) = tokio::join!(
        service.items().query().with_filter(filter).list_page(),
        load_sorted_active_feeds(&service),
        sync.status(),
        load_inbox_filter_labels(&service, clean_feed, clean_tag),
        tags_svc.list(),
    );
    let page = page_res.web_err(&session)?;
    let total_unread = sync_res.map_or_else(
        |_| feeds.iter().map(|f| f.unread_count).sum::<i64>(),
        |s| s.unread_items_count,
    );
    let (feed_label, tag_label) = labels;
    let tag_id_by_lower_name: HashMap<String, String> = all_tags
        .unwrap_or_default()
        .into_iter()
        .map(|t| (t.name.to_ascii_lowercase(), t.id))
        .collect();
    let selected_feed_unread =
        clean_feed.and_then(|fid| feeds.iter().find(|f| f.id == fid)).map(|f| f.unread_count);

    let nav_ctx = InboxNavParams {
        status: status_slug,
        kind: clean_kind,
        feed: clean_feed,
        tag: clean_tag,
        q: clean_search,
        page: page_num,
    };
    let body = render_inbox_body(
        &nav_ctx,
        &feeds,
        total_unread,
        selected_feed_unread,
        &page.items,
        page.has_more,
        feed_label.as_deref(),
        tag_label.as_deref(),
        &tag_id_by_lower_name,
        &session.csrf_token,
    );

    Ok(render_page(&PageShell {
        title: feed_label.as_deref().unwrap_or("Inbox"),
        active: NavSection::Inbox,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

#[allow(clippy::too_many_arguments)]
fn render_inbox_body(
    nav_ctx: &InboxNavParams<'_>,
    feeds: &[Feed],
    total_unread: i64,
    selected_feed_unread: Option<i64>,
    items: &[Item],
    has_more: bool,
    feed_label: Option<&str>,
    tag_label: Option<&str>,
    tag_id_by_lower_name: &HashMap<String, String>,
    csrf: &str,
) -> String {
    let return_url = build_inbox_href(nav_ctx);
    let mut body = String::with_capacity(16_384);
    body.push_str("<div class=\"inbox-layout\">\n");
    render_inbox_sidebar(&mut body, nav_ctx, feeds, total_unread);
    body.push_str("<section class=\"inbox-stream\" aria-label=\"Feed stream\">\n");
    render_inbox_mobile_pills(&mut body, nav_ctx, feeds);
    render_inbox_toolbar(
        &mut body,
        csrf,
        nav_ctx,
        feed_label,
        total_unread,
        selected_feed_unread,
        items,
        has_more,
        &return_url,
    );
    if nav_ctx.feed.is_some()
        || nav_ctx.tag.is_some()
        || nav_ctx.kind.is_some()
        || nav_ctx.q.is_some()
    {
        render_inbox_active_filters(&mut body, nav_ctx, feed_label, tag_label);
    }
    render_item_rows(&mut body, items, csrf, &return_url, tag_id_by_lower_name);
    render_inbox_pager(&mut body, nav_ctx, nav_ctx.page, has_more);
    body.push_str("</section>\n</div>\n");
    body
}

async fn load_sorted_active_feeds(service: &RocheService) -> Vec<Feed> {
    let mut feeds = service.feeds().list().await.unwrap_or_default();
    feeds.retain(|f| f.is_active);
    feeds.sort_by(|a, b| {
        b.unread_count.cmp(&a.unread_count).then_with(|| {
            let ta = a.title.as_deref().unwrap_or(a.url.as_str()).to_ascii_lowercase();
            let tb = b.title.as_deref().unwrap_or(b.url.as_str()).to_ascii_lowercase();
            ta.cmp(&tb)
        })
    });
    feeds
}

async fn load_inbox_filter_labels(
    service: &RocheService,
    clean_feed: Option<&str>,
    clean_tag: Option<&str>,
) -> (Option<String>, Option<String>) {
    let feed_label = match clean_feed {
        Some(fid) => service
            .feeds()
            .get(fid)
            .await
            .ok()
            .and_then(|f| f.title.filter(|t| !t.is_empty()).or_else(|| Some(f.url.to_string())))
            .or_else(|| Some(fid.to_owned())),
        None => None,
    };
    let tag_label = match clean_tag {
        Some(tid) => {
            service.tags().get(tid).await.ok().map(|t| t.name).or_else(|| Some(tid.to_owned()))
        }
        None => None,
    };
    (feed_label, tag_label)
}

fn render_inbox_pager(
    out: &mut String,
    nav_ctx: &InboxNavParams<'_>,
    page_num: usize,
    has_more: bool,
) {
    if page_num <= 1 && !has_more {
        return;
    }
    out.push_str("<nav class=\"pager\" aria-label=\"Pagination\">");
    if page_num > 1 {
        let prev = build_inbox_href(&InboxNavParams { page: page_num - 1, ..*nav_ctx });
        let _ = write!(out, "<a href=\"{}\">&larr; Newer</a>", esc(&prev));
    }
    let _ = write!(out, "<span class=\"current\" aria-current=\"page\">Page {page_num}</span>");
    if has_more {
        let next = build_inbox_href(&InboxNavParams { page: page_num + 1, ..*nav_ctx });
        let _ = write!(out, "<a href=\"{}\">Older &rarr;</a>", esc(&next));
    }
    out.push_str("</nav>\n");
}

#[derive(Clone, Copy)]
struct InboxNavParams<'a> {
    status: &'a str,
    kind: Option<&'a str>,
    feed: Option<&'a str>,
    tag: Option<&'a str>,
    q: Option<&'a str>,
    page: usize,
}

fn build_inbox_href(p: &InboxNavParams<'_>) -> String {
    let mut parts = vec![format!("status={}", urlencode(p.status))];
    if let Some(k) = p.kind.filter(|s| !s.is_empty()) {
        parts.push(format!("kind={}", urlencode(k)));
    }
    if let Some(fid) = p.feed.filter(|s| !s.is_empty()) {
        parts.push(format!("feed={}", urlencode(fid)));
    }
    if let Some(tid) = p.tag.filter(|s| !s.is_empty()) {
        parts.push(format!("tag={}", urlencode(tid)));
    }
    if let Some(search) = p.q.map(str::trim).filter(|s| !s.is_empty()) {
        parts.push(format!("q={}", urlencode(search)));
    }
    if p.page > 1 {
        parts.push(format!("page={}", p.page));
    }
    format!("/inbox?{}", parts.join("&"))
}

fn render_inbox_sidebar(
    out: &mut String,
    p: &InboxNavParams<'_>,
    feeds: &[Feed],
    total_unread: i64,
) {
    out.push_str(
        "<div class=\"inbox-sidebar\">\n\
         <div class=\"sidebar-group\">\n\
           <div class=\"sidebar-heading\">Views</div>\n\
           <ul class=\"sidebar-list\">\n",
    );
    for (slug, label, count_opt) in [
        ("unread", "Unread", Some(total_unread)),
        ("all", "All Items", None),
        ("archived", "Archived", None),
    ] {
        let is_active = p.status == slug && p.feed.is_none();
        let attrs = if is_active { " class=\"is-active\" aria-current=\"true\"" } else { "" };
        let href = build_inbox_href(&InboxNavParams { status: slug, feed: None, page: 1, ..*p });
        let count_html = count_opt.map_or_else(String::new, |c| format!("{c}"));
        let _ = writeln!(
            out,
            "<li><a href=\"{}\"{attrs}><span class=\"sidebar-label\">{label}</span>\
             <span class=\"sidebar-count\">{count_html}</span></a></li>",
            esc(&href),
        );
    }
    out.push_str(
        "</ul>\n</div>\n\
         <div class=\"sidebar-group\">\n\
           <div class=\"sidebar-heading\">Format</div>\n\
           <ul class=\"sidebar-list\">\n",
    );
    // TODO(backend): include per-format unread counts in inbox sidebar
    for (k_slug, label) in [
        ("article", "Articles"),
        ("audio", "Podcasts"),
        ("paper", "Papers"),
        ("pdf", "PDFs"),
        ("video", "Videos"),
    ] {
        let is_cur = p.kind == Some(k_slug);
        let attrs = if is_cur { " class=\"is-active\" aria-current=\"true\"" } else { "" };
        let next_kind = if is_cur { None } else { Some(k_slug) };
        let href = build_inbox_href(&InboxNavParams { kind: next_kind, page: 1, ..*p });
        let _ = writeln!(
            out,
            "<li><a href=\"{}\"{attrs}><span class=\"sidebar-label\">{label}</span></a></li>",
            esc(&href),
        );
    }
    let _ = writeln!(
        out,
        "</ul>\n</div>\n\
         <div class=\"sidebar-group\">\n\
           <div class=\"sidebar-heading\">Feeds ({})</div>\n\
           <input type=\"search\" class=\"sidebar-feed-filter\" placeholder=\"Filter feeds…\" aria-label=\"Filter feeds\">\n\
           <ul class=\"sidebar-list\" id=\"roche-sidebar-feeds\">",
        feeds.len(),
    );
    for feed in feeds {
        let label = feed.title.as_deref().filter(|s| !s.is_empty()).unwrap_or(feed.url.as_str());
        let is_cur = p.feed == Some(feed.id.as_str());
        let attrs = if is_cur { " class=\"is-active\" aria-current=\"true\"" } else { "" };
        let next_feed = if is_cur { None } else { Some(feed.id.as_str()) };
        let next_status = if is_cur {
            p.status
        } else if feed.unread_count > 0 {
            "unread"
        } else {
            "all"
        };
        let href = build_inbox_href(&InboxNavParams {
            status: next_status,
            feed: next_feed,
            page: 1,
            ..*p
        });
        let _ = write!(
            out,
            "<li data-feed-name=\"{}\"><a href=\"{}\"{attrs}><span class=\"sidebar-item-main\">",
            esc(&label.to_ascii_lowercase()),
            esc(&href),
        );
        render_feed_favicon(out, Some(feed.url.as_str()), label, false);
        let cnt = if feed.unread_count > 0 { feed.unread_count.to_string() } else { String::new() };
        let _ = writeln!(
            out,
            "<span class=\"sidebar-label\">{}</span></span><span class=\"sidebar-count\">{cnt}</span></a></li>",
            esc(label),
        );
    }
    out.push_str(
        "</ul>\n\
         <p class=\"sidebar-empty\" id=\"roche-sidebar-feeds-empty\" hidden>No matching feeds.</p>\n\
         </div>\n</div>\n",
    );
}

fn render_inbox_mobile_pills(out: &mut String, p: &InboxNavParams<'_>, feeds: &[Feed]) {
    out.push_str("<div class=\"inbox-mobile-filters pill-bar\">\n");
    for (slug, label) in [("unread", "Unread"), ("all", "All"), ("archived", "Archived")] {
        let attrs = if p.status == slug {
            "class=\"pill is-active\" aria-current=\"true\""
        } else {
            "class=\"pill\""
        };
        let href = build_inbox_href(&InboxNavParams { status: slug, page: 1, ..*p });
        let _ = writeln!(out, "<a {attrs} href=\"{}\">{label}</a>", esc(&href));
    }
    for (k_slug, label) in [
        ("article", "Articles"),
        ("audio", "Podcasts"),
        ("video", "Videos"),
        ("paper", "Papers"),
        ("pdf", "PDFs"),
    ] {
        let is_cur = p.kind == Some(k_slug);
        let attrs = if is_cur {
            "class=\"pill is-active\" aria-current=\"true\""
        } else {
            "class=\"pill\""
        };
        let next_kind = if is_cur { None } else { Some(k_slug) };
        let href = build_inbox_href(&InboxNavParams { kind: next_kind, page: 1, ..*p });
        let _ = writeln!(out, "<a {attrs} href=\"{}\">{label}</a>", esc(&href));
    }
    if !feeds.is_empty() {
        let all_feeds_href = build_inbox_href(&InboxNavParams { feed: None, page: 1, ..*p });
        let _ = writeln!(
            out,
            "<select class=\"inbox-mobile-feed-select js-mobile-feed-select\" aria-label=\"Switch feed\">\n\
               <option value=\"{}\"{}>All Feeds</option>",
            esc(&all_feeds_href),
            if p.feed.is_none() { " selected" } else { "" },
        );
        for feed in feeds {
            let label =
                feed.title.as_deref().filter(|s| !s.is_empty()).unwrap_or(feed.url.as_str());
            let next_status = if feed.unread_count > 0 { "unread" } else { "all" };
            let feed_href = build_inbox_href(&InboxNavParams {
                status: next_status,
                feed: Some(feed.id.as_str()),
                page: 1,
                ..*p
            });
            let sel = if p.feed == Some(feed.id.as_str()) { " selected" } else { "" };
            let count_part = if feed.unread_count > 0 {
                format!(" ({})", feed.unread_count)
            } else {
                String::new()
            };
            let _ = writeln!(
                out,
                "<option value=\"{}\"{sel}>{}{count_part}</option>",
                esc(&feed_href),
                esc(label),
            );
        }
        out.push_str("</select>\n");
    }
    out.push_str("</div>\n");
}

#[allow(clippy::too_many_arguments)]
fn render_inbox_toolbar(
    out: &mut String,
    csrf: &str,
    p: &InboxNavParams<'_>,
    feed_label: Option<&str>,
    total_unread: i64,
    selected_feed_unread: Option<i64>,
    items: &[Item],
    has_more: bool,
    return_to: &str,
) {
    let heading = feed_label.unwrap_or(match p.status {
        "all" => "All Items",
        "archived" => "Archived",
        _ => "Unread",
    });
    let _ = write!(
        out,
        "<div class=\"inbox-toolbar\">\n\
           <div class=\"inbox-toolbar-title\">\n\
             <h2>{}</h2>",
        esc(heading),
    );
    let count_text = if feed_label.is_none()
        && p.status == "unread"
        && p.kind.is_none()
        && p.tag.is_none()
        && p.q.is_none()
    {
        if total_unread == 1 { "1 item".to_owned() } else { format!("{total_unread} items") }
    } else if let Some(feed_unread) = selected_feed_unread
        && p.status == "unread"
        && p.kind.is_none()
        && p.tag.is_none()
        && p.q.is_none()
    {
        if feed_unread == 1 { "1 item".to_owned() } else { format!("{feed_unread} items") }
    } else if has_more {
        format!("{}+ items", items.len())
    } else if items.len() == 1 {
        "1 item".to_owned()
    } else {
        format!("{} items", items.len())
    };
    let _ = write!(out, "<span class=\"inbox-toolbar-count\">{count_text}</span>");
    if p.feed.is_some() {
        out.push_str("<div class=\"inbox-feed-status-toggle pill-bar\" role=\"group\" aria-label=\"Feed status filter\">");
        for (slug, label) in [("unread", "Unread"), ("all", "All"), ("archived", "Archived")] {
            let attrs = if p.status == slug {
                "class=\"pill is-active\" aria-current=\"true\""
            } else {
                "class=\"pill\""
            };
            let href = build_inbox_href(&InboxNavParams { status: slug, page: 1, ..*p });
            let _ = write!(out, "<a {attrs} href=\"{}\">{label}</a>", esc(&href));
        }
        out.push_str("</div>");
    }
    out.push_str("</div>\n<div class=\"inbox-toolbar-actions\">\n");
    render_inbox_inline_search(out, p);
    render_inbox_batch_controls(out, csrf, p, items, selected_feed_unread, return_to);
    out.push_str("</div>\n</div>\n");
}

fn render_inbox_inline_search(out: &mut String, p: &InboxNavParams<'_>) {
    let _ = writeln!(
        out,
        "<form method=\"get\" action=\"/inbox\" class=\"inbox-toolbar-search\">\n\
           <input type=\"hidden\" name=\"status\" value=\"{}\">",
        esc(p.status),
    );
    if let Some(k) = p.kind {
        let _ = writeln!(out, "<input type=\"hidden\" name=\"kind\" value=\"{}\">", esc(k));
    }
    if let Some(fid) = p.feed {
        let _ = writeln!(out, "<input type=\"hidden\" name=\"feed\" value=\"{}\">", esc(fid));
    }
    if let Some(tid) = p.tag {
        let _ = writeln!(out, "<input type=\"hidden\" name=\"tag\" value=\"{}\">", esc(tid));
    }
    let _ = write!(
        out,
        "<input type=\"search\" id=\"roche-inbox-search\" name=\"q\" aria-label=\"Filter stream\" value=\"{}\" placeholder=\"Filter stream…\">\n\
         <button type=\"submit\" class=\"btn btn-small btn-ghost\">Filter</button>",
        esc(p.q.unwrap_or("")),
    );
    if let Some(cur_q) = p.q {
        let clear_href = build_inbox_href(&InboxNavParams { q: None, page: 1, ..*p });
        let _ = write!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"{}\" aria-label=\"Clear stream filter\" title=\"Clear stream filter\">&times;</a>\
             <a class=\"btn btn-small btn-ghost btn-quiet\" href=\"/search?q={}\">Search all &rarr;</a>",
            esc(&clear_href),
            esc(&urlencode(cur_q)),
        );
    }
    out.push_str("</form>\n");
}

fn render_inbox_active_filters(
    out: &mut String,
    p: &InboxNavParams<'_>,
    feed_label: Option<&str>,
    tag_label: Option<&str>,
) {
    out.push_str("<div class=\"pill-bar active-filters\">\n");
    if let Some(label) = feed_label {
        let clear_href = build_inbox_href(&InboxNavParams { feed: None, page: 1, ..*p });
        let _ = writeln!(
            out,
            "<a class=\"pill is-active\" href=\"{}\">Feed: {} <span aria-hidden=\"true\">&times;</span><span class=\"sr-only\"> (clear feed filter)</span></a>",
            esc(&clear_href),
            esc(label),
        );
    }
    if let Some(label) = tag_label {
        let clear_href = build_inbox_href(&InboxNavParams { tag: None, page: 1, ..*p });
        let _ = writeln!(
            out,
            "<a class=\"pill is-active\" href=\"{}\">Tag: #{} <span aria-hidden=\"true\">&times;</span><span class=\"sr-only\"> (clear tag filter)</span></a>",
            esc(&clear_href),
            esc(label),
        );
    }
    if let Some(k) = p.kind {
        let clear_href = build_inbox_href(&InboxNavParams { kind: None, page: 1, ..*p });
        let _ = writeln!(
            out,
            "<a class=\"pill is-active\" href=\"{}\">Kind: {} <span aria-hidden=\"true\">&times;</span><span class=\"sr-only\"> (clear kind filter)</span></a>",
            esc(&clear_href),
            esc(k),
        );
    }
    if let Some(search) = p.q {
        let clear_href = build_inbox_href(&InboxNavParams { q: None, page: 1, ..*p });
        let _ = writeln!(
            out,
            "<a class=\"pill is-active\" href=\"{}\">Search: &ldquo;{}&rdquo; <span aria-hidden=\"true\">&times;</span><span class=\"sr-only\"> (clear search filter)</span></a>",
            esc(&clear_href),
            esc(search),
        );
    }
    let reset_href = build_inbox_href(&InboxNavParams {
        status: p.status,
        kind: None,
        feed: None,
        tag: None,
        q: None,
        page: 1,
    });
    let _ = writeln!(
        out,
        "<a class=\"btn btn-small btn-ghost\" href=\"{}\">Clear filters</a>",
        esc(&reset_href),
    );
    out.push_str("</div>\n");
}

fn render_inbox_batch_controls(
    out: &mut String,
    csrf: &str,
    p: &InboxNavParams<'_>,
    items: &[Item],
    selected_feed_unread: Option<i64>,
    return_to: &str,
) {
    let has_narrowing = p.kind.is_some() || p.tag.is_some() || p.q.is_some();
    let unread_ids: Vec<&str> =
        items.iter().filter(|i| i.status == ItemStatus::Unread).map(|i| i.id.as_str()).collect();
    if p.status == "archived" || unread_ids.is_empty() {
        return;
    }
    if has_narrowing {
        let joined = unread_ids.join(",");
        let btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">Archive Shown Unread ({})</button>",
            unread_ids.len()
        );
        render_inline_csrf_form(
            out,
            "/web/items/archive-batch",
            csrf,
            &[("ids", &joined), ("return_to", return_to)],
            Some(&format!("Archive {} shown unread item(s)?", unread_ids.len())),
            &btn,
        );
    } else if let Some(fid) = p.feed {
        let count = selected_feed_unread
            .and_then(|c| usize::try_from(c).ok())
            .filter(|&c| c > 0)
            .unwrap_or(unread_ids.len());
        let confirm = format!("Archive all {count} unread item(s) in this feed?");
        let btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">Archive Feed Unread ({count})</button>"
        );
        render_inline_csrf_form(
            out,
            "/web/items/archive-batch",
            csrf,
            &[("feed_id", fid), ("return_to", return_to)],
            Some(&confirm),
            &btn,
        );
    } else {
        render_inline_csrf_form(
            out,
            "/web/items/archive-batch",
            csrf,
            &[("return_to", return_to)],
            Some("Archive all unread items in your inbox?"),
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">Archive All Unread</button>",
        );
    }
}

fn day_bucket_label(dt: DateTime<Utc>, today: NaiveDate) -> String {
    let date = dt.date_naive();
    if date == today {
        format!("Today · {}", dt.format("%b %d"))
    } else if today.pred_opt() == Some(date) {
        format!("Yesterday · {}", dt.format("%b %d"))
    } else if date.year() == today.year() {
        dt.format("%A · %b %d").to_string()
    } else {
        dt.format("%b %d, %Y").to_string()
    }
}

fn clean_plain_excerpt(raw: &str) -> String {
    let mut plain = String::with_capacity(raw.len().min(260));
    let mut in_tag = false;
    let mut prev_space = true;
    for ch in raw.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                if !prev_space {
                    plain.push(' ');
                    prev_space = true;
                }
            }
            c if !in_tag => {
                if c.is_whitespace() {
                    if !prev_space {
                        plain.push(' ');
                        prev_space = true;
                    }
                } else {
                    plain.push(c);
                    prev_space = false;
                }
            }
            _ => {}
        }
        if plain.chars().count() >= 220 {
            break;
        }
    }
    let trimmed = plain.trim_end();
    if raw.chars().count() > 220 && !trimmed.is_empty() {
        format!("{trimmed}…")
    } else {
        trimmed.to_owned()
    }
}

pub(super) fn render_item_rows(
    out: &mut String,
    items: &[Item],
    csrf: &str,
    return_to: &str,
    tag_id_by_lower_name: &HashMap<String, String>,
) {
    if items.is_empty() {
        out.push_str("<p class=\"empty\">No matching items.</p>\n");
        return;
    }

    let today = Utc::now().date_naive();
    let mut active_day: Option<String> = None;
    let mut first_group = true;
    for item in items {
        let day_label = day_bucket_label(item.published_at.unwrap_or(item.created_at), today);
        if active_day.as_deref() != Some(day_label.as_str()) {
            if active_day.is_some() {
                out.push_str("</ul>\n");
            }
            let cls = if first_group { "day-divider is-today" } else { "day-divider" };
            let _ = writeln!(
                out,
                "<div class=\"{cls}\">{}</div>\n<ul class=\"inbox-list\">",
                esc(&day_label)
            );
            active_day = Some(day_label);
            first_group = false;
        }
        render_single_inbox_row(out, item, csrf, return_to, tag_id_by_lower_name);
    }
    if active_day.is_some() {
        out.push_str("</ul>\n");
    }
}

fn render_single_inbox_row(
    out: &mut String,
    item: &Item,
    csrf: &str,
    return_to: &str,
    tag_id_by_lower_name: &HashMap<String, String>,
) {
    let row_cls = if item.status == ItemStatus::Archived {
        "inbox-row js-triage-row js-kbd-row is-archived"
    } else {
        "inbox-row js-triage-row js-kbd-row"
    };
    let id_enc = urlencode(&item.id);
    let reader_href = format!("/reader/{id_enc}?return_to={}", urlencode(return_to));
    let (star_glyph, star_pressed, star_cls, star_action_word) = if item.is_favorite {
        ("&#9733;", "true", "btn btn-small btn-ghost inbox-star-btn is-starred", "Unstar")
    } else {
        ("&#9734;", "false", "btn btn-small btn-ghost inbox-star-btn", "Star")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Toggle Star\" data-title=\"{title}\" aria-label=\"{star_action_word} {title}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        title = esc(&item.title),
    );

    let _ = write!(
        out,
        "<li class=\"{row_cls}\" data-item-id=\"{}\">\n<div class=\"inbox-star-col\">",
        esc(&item.id)
    );
    render_inline_csrf_form(
        out,
        &format!("/web/items/{id_enc}/star"),
        csrf,
        &[("return_to", return_to)],
        None,
        &star_btn,
    );
    out.push_str("</div>\n");

    if let Some(thumb) = item.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
        let _ = writeln!(
            out,
            "<a class=\"inbox-thumb-link\" href=\"{}\" tabindex=\"-1\" aria-hidden=\"true\">\
               <img class=\"inbox-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">\
             </a>",
            esc(&reader_href),
            esc(thumb),
        );
    }

    let _ = write!(
        out,
        "<div class=\"inbox-main\">\n\
           <div class=\"inbox-title\"><a class=\"js-kbd-open\" href=\"{}\">{}</a></div>",
        esc(&reader_href),
        esc(&item.title),
    );
    render_inbox_row_byline(out, item, tag_id_by_lower_name);
    if let Some(sum) = item.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let excerpt = clean_plain_excerpt(sum);
        if !excerpt.is_empty() {
            let _ = writeln!(out, "<p class=\"item-excerpt-clamped\">{}</p>", esc(&excerpt));
        }
    }
    out.push_str("</div>\n");
    render_inbox_row_controls(out, item, &id_enc, csrf, return_to);
    out.push_str("</li>\n");
}

fn render_inbox_row_byline(
    out: &mut String,
    item: &Item,
    tag_id_by_lower_name: &HashMap<String, String>,
) {
    out.push_str("<div class=\"item-byline\">");
    if item.content_type != ContentType::Article {
        let _ = write!(
            out,
            "<span class=\"badge badge-kind{}\">{}</span>",
            content_type_badge_class(item.content_type),
            esc(content_type_label(item.content_type)),
        );
    }
    if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
        let icon_url = item.feed_url.as_deref().or(Some(item.url.as_str()));
        if let Some(fid) = item.feed_id.as_deref() {
            let _ = write!(
                out,
                "<a class=\"byline-item byline-feed\" href=\"/inbox?status=unread&amp;feed={}\">",
                esc(&urlencode(fid)),
            );
            render_feed_favicon(out, icon_url, ft, true);
            let _ = write!(out, "<span>{}</span></a>", esc(ft));
        } else {
            out.push_str("<span class=\"byline-item byline-feed\">");
            render_feed_favicon(out, icon_url, ft, true);
            let _ = write!(out, "<span>{}</span></span>", esc(ft));
        }
    }
    if item.content_type == ContentType::Audio
        && let Some(dur) = item.duration.filter(|&d| d > 0)
    {
        if item.read_progress >= 0.03 && item.read_progress < 0.98 {
            #[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation)]
            let rem = ((dur as f64) * (1.0 - item.read_progress.clamp(0.0, 1.0))).round() as i64;
            let _ = write!(
                out,
                "<span class=\"byline-item\">{} &middot; {} left</span>",
                esc(&format_audio_duration(dur)),
                esc(&format_audio_duration(rem.max(1))),
            );
        } else {
            let _ = write!(
                out,
                "<span class=\"byline-item\">{}</span>",
                esc(&format_audio_duration(dur)),
            );
        }
    }
    if let Some(dt) = item.published_at {
        let _ = write!(out, "<span class=\"byline-item\">{}</span>", dt.format("%b %d"));
    }
    for tag_name in &item.tag_names.0 {
        if let Some(tag_id) = tag_id_by_lower_name.get(&tag_name.to_ascii_lowercase()) {
            let tag_href = format!("/inbox?status=all&tag={}", urlencode(tag_id));
            let _ = write!(
                out,
                "<a class=\"badge badge-link\" href=\"{}\">#{}</a>",
                esc(&tag_href),
                esc(tag_name),
            );
        } else {
            let _ = write!(out, "<span class=\"badge\">#{}</span>", esc(tag_name));
        }
    }
    out.push_str("</div>\n");
}

fn render_inbox_row_controls(
    out: &mut String,
    item: &Item,
    id_enc: &str,
    csrf: &str,
    return_to: &str,
) {
    out.push_str("<div class=\"inbox-controls inbox-controls-subtle\">\n");
    render_audio_play_button(out, item);

    if item.in_want_to_read {
        let queue_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet is-in-queue\" title=\"Remove from Queue\" aria-label=\"Remove {} from Queue\">&#10003; In Queue</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/want/read/remove/{id_enc}"),
            csrf,
            &[("return_to", return_to)],
            "unqueue",
            &item.title,
            &format!("/web/entries/{id_enc}/list-add"),
            &queue_btn,
        );
    } else {
        let queue_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\" title=\"Add to Queue\" aria-label=\"Add {} to Queue\">+ Queue</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/entries/{id_enc}/list-add"),
            csrf,
            &[("list_slug_or_id", WANT_TO_READ_COLLECTION_ID), ("return_to", return_to)],
            "queue",
            &item.title,
            &format!("/web/want/read/remove/{id_enc}"),
            &queue_btn,
        );
    }
    out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>");

    if item.status == ItemStatus::Unread {
        let arch_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-icon\" title=\"Archive (mark read)\" aria-label=\"Archive {}\">{ARCHIVE_BOX_SVG}</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/items/{id_enc}/archive"),
            csrf,
            &[("return_to", return_to)],
            "archive",
            &item.title,
            &format!("/web/items/{id_enc}/unarchive"),
            &arch_btn,
        );
    } else {
        let restore_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\" title=\"Restore to Unread\" aria-label=\"Restore {}\">{RESTORE_BOX_SVG} Restore</button>",
            esc(&item.title),
        );
        render_triage_form(
            out,
            &format!("/web/items/{id_enc}/unarchive"),
            csrf,
            &[("return_to", return_to)],
            "unarchive",
            &item.title,
            &format!("/web/items/{id_enc}/archive"),
            &restore_btn,
        );
    }
    out.push_str("\n</div>\n");
}

pub(in crate::web) async fn starred_get() -> Response {
    redirect_see_other("/lists/starred")
}

pub(in crate::web) async fn post_item_save(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<SaveUrlForm>,
) -> Result<Response, WebError> {
    let raw_trimmed = form.url.trim();
    let normalized = if !raw_trimmed.contains("://")
        && raw_trimmed.contains('.')
        && !raw_trimmed.chars().any(char::is_whitespace)
    {
        format!("https://{raw_trimmed}")
    } else {
        raw_trimmed.to_owned()
    };
    let fallback_err_dest = safe_local_redirect(form.return_to.as_deref(), "/");
    let Ok(parsed_url) = Url::parse(&normalized) else {
        return Ok(redirect_with_flash(
            &fallback_err_dest,
            "err",
            "Please enter a valid http(s) URL",
        ));
    };
    let tags = parse_csv_list(form.tags.as_deref());

    let saved =
        service.items().save(&parsed_url, form.title.as_deref(), &tags).await.web_err(&session)?;
    if let Some(ret) = form.return_to.as_deref().filter(|s| !s.trim().is_empty()) {
        Ok(redirect_with_flash(ret, "notice", "Added to Queue"))
    } else {
        Ok(redirect_with_flash(
            &format!("/reader/{}", urlencode(&saved.item.id)),
            "notice",
            "Added to Queue",
        ))
    }
}

pub(in crate::web) async fn post_item_archive_batch(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ArchiveBatchForm>,
) -> Result<Response, WebError> {
    let explicit_ids = parse_csv_list(form.ids.as_deref());
    let feed_id = form.feed_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let count = if explicit_ids.is_empty() {
        service.items().archive_batch(&[], feed_id, true).await
    } else {
        service.items().archive_batch(&explicit_ids, None, false).await
    }
    .web_err(&session)?;
    let base = safe_local_redirect(form.return_to.as_deref(), "/inbox");
    Ok(redirect_with_flash(&base, "notice", &format!("Archived {count} unread items")))
}

pub(in crate::web) async fn post_item_star(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    let _ = service.items().toggle_star(&id).await.web_err(&session)?;
    Ok(redirect_see_other(&safe_local_redirect(form.return_to.as_deref(), "/inbox")))
}

pub(in crate::web) async fn post_item_archive(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    let _ =
        service.items().archive_with_min_progress(&id, form.progress).await.web_err(&session)?;
    Ok(redirect_see_other(&safe_local_redirect(form.return_to.as_deref(), "/inbox")))
}

pub(in crate::web) async fn post_item_unarchive(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    let _ = service.items().unarchive(&id).await.web_err(&session)?;
    Ok(redirect_see_other(&safe_local_redirect(form.return_to.as_deref(), "/inbox")))
}
