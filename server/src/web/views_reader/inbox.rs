use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use url::Url;

use super::{ArchiveBatchForm, InboxQuery, ItemReturnForm, SaveUrlForm, render_audio_play_button};
use crate::{
    db::{ContentType, Item, ItemFilter, ItemStatus, ItemStatusFilter, ReadAtPatch},
    scheduler::FeedFetcher,
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, redirect_see_other, render_page,
            safe_local_redirect, urlencode, web_api_error_response,
        },
    },
};

pub(in crate::web) async fn inbox_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(q): Query<InboxQuery>,
) -> Response {
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
    let content_type = match q.kind.as_deref() {
        Some("article") => Some(ContentType::Article),
        Some("audio") => Some(ContentType::Audio),
        Some("video") => Some(ContentType::Video),
        Some("paper") => Some(ContentType::Paper),
        _ => None,
    };
    let clean_feed = q.feed.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let clean_tag = q.tag.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let clean_search = q.q.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let clean_kind = q.kind.as_deref().filter(|s| !s.is_empty());
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

    let page = match service.items().query().with_filter(filter).list_page().await {
        Ok(p) => p,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };

    let nav_ctx = InboxNavParams {
        status: status_slug,
        kind: clean_kind,
        feed: clean_feed,
        tag: clean_tag,
        q: clean_search,
        page: page_num,
    };
    let return_url = build_inbox_href(&nav_ctx);

    let mut body = String::with_capacity(8_192);
    let _ = writeln!(
        body,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:0.75rem\">\
           <h2>Feed Inbox</h2>\
           <form method=\"post\" action=\"/web/feeds/refresh-all\" class=\"inline-form\">\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
             <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\
             <button type=\"submit\" class=\"btn btn-small\">&#8635; Refresh Feeds</button>\
           </form>\
         </div>\n\
         <p class=\"lede\">Read-only chronological stream from your subscribed feeds. Save standalone links or PDFs to <a href=\"/\">Want to Read</a>.</p>",
        csrf = esc(&session.csrf_token),
        ret = esc(&return_url),
    );
    render_inbox_filter_bar(&mut body, &nav_ctx);
    render_inbox_batch_controls(&mut body, &session.csrf_token, &nav_ctx, &page.items, &return_url);
    render_item_rows(&mut body, &page.items, &session.csrf_token, &return_url);

    if page_num > 1 || page.has_more {
        body.push_str("<nav class=\"pager\">");
        if page_num > 1 {
            let prev = build_inbox_href(&InboxNavParams { page: page_num - 1, ..nav_ctx });
            let _ = write!(body, "<a href=\"{}\">&larr; Newer</a>", esc(&prev));
        }
        let _ = write!(body, "<span class=\"current\">Page {page_num}</span>");
        if page.has_more {
            let next = build_inbox_href(&InboxNavParams { page: page_num + 1, ..nav_ctx });
            let _ = write!(body, "<a href=\"{}\">Older &rarr;</a>", esc(&next));
        }
        body.push_str("</nav>\n");
    }

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: "Inbox",
        active: NavSection::Inbox,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
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

fn render_inbox_filter_bar(out: &mut String, p: &InboxNavParams<'_>) {
    out.push_str("<div class=\"pill-bar\">\n");
    for (slug, label) in [("unread", "Unread"), ("all", "All"), ("archived", "Archived")] {
        let cls = if p.status == slug && p.kind.is_none() { "pill is-active" } else { "pill" };
        let href = build_inbox_href(&InboxNavParams { status: slug, kind: None, page: 1, ..*p });
        let _ = writeln!(out, "<a class=\"{cls}\" href=\"{}\">{label}</a>", esc(&href));
    }
    for (k_slug, label) in
        [("article", "Articles"), ("audio", "Audio"), ("video", "Video"), ("paper", "Papers")]
    {
        let cls = if p.kind == Some(k_slug) { "pill is-active" } else { "pill" };
        let href = build_inbox_href(&InboxNavParams { kind: Some(k_slug), page: 1, ..*p });
        let _ = writeln!(out, "<a class=\"{cls}\" href=\"{}\">{label}</a>", esc(&href));
    }
    out.push_str("</div>\n");

    let _ = writeln!(
        out,
        "<form method=\"get\" action=\"/inbox\" style=\"display:flex;gap:0.5rem;margin-bottom:0.9rem\">\n\
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
        "<input type=\"search\" name=\"q\" value=\"{}\" placeholder=\"Search titles, full text, feeds, tags...\" style=\"flex:1\">\n\
         <button type=\"submit\" class=\"btn\">Search</button>\n\
         </form>\n",
        esc(p.q.unwrap_or("")),
    );
}

fn render_inbox_batch_controls(
    out: &mut String,
    csrf: &str,
    p: &InboxNavParams<'_>,
    items: &[Item],
    return_to: &str,
) {
    let has_narrowing = p.kind.is_some() || p.tag.is_some() || p.q.is_some();
    let unread_ids: Vec<&str> =
        items.iter().filter(|i| i.status == ItemStatus::Unread).map(|i| i.id.as_str()).collect();
    if p.status == "archived" || unread_ids.is_empty() {
        return;
    }
    out.push_str(
        "<div style=\"display:flex;justify-content:flex-end;align-items:baseline;gap:0.75rem;flex-wrap:wrap;margin-bottom:0.75rem\">\n",
    );
    if has_narrowing {
        let joined = unread_ids.join(",");
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/items/archive-batch\" class=\"inline-form\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <input type=\"hidden\" name=\"ids\" value=\"{ids}\">\n\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\n\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\">Archive Shown Unread ({n})</button>\n\
             </form>\n",
            csrf = esc(csrf),
            ids = esc(&joined),
            ret = esc(return_to),
            n = unread_ids.len(),
        );
    } else if let Some(fid) = p.feed {
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/items/archive-batch\" class=\"inline-form\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <input type=\"hidden\" name=\"feed_id\" value=\"{feed}\">\n\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\n\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\">Archive Feed Unread</button>\n\
             </form>\n",
            csrf = esc(csrf),
            feed = esc(fid),
            ret = esc(return_to),
        );
    } else {
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/items/archive-batch\" class=\"inline-form\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\n\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\">Archive All Unread</button>\n\
             </form>\n",
            csrf = esc(csrf),
            ret = esc(return_to),
        );
    }
    out.push_str("</div>\n");
}

pub(super) fn render_item_rows(out: &mut String, items: &[Item], csrf: &str, return_to: &str) {
    if items.is_empty() {
        out.push_str("<p class=\"empty\">No matching items.</p>\n");
        return;
    }

    out.push_str("<ul class=\"inbox-list\">\n");
    for item in items {
        let row_cls =
            if item.status == ItemStatus::Archived { "inbox-row is-archived" } else { "inbox-row" };
        let _ = write!(
            out,
            "<li class=\"{row_cls}\">\n\
               <div class=\"inbox-main\">\n\
                 <div class=\"inbox-title\"><a href=\"/reader/{}\">{}</a></div>\n\
                 <div class=\"item-byline\"><span class=\"badge\">{:?}</span>",
            esc(&urlencode(&item.id)),
            esc(&item.title),
            item.content_type,
        );
        if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
            if let Some(fid) = item.feed_id.as_deref() {
                let _ = write!(
                    out,
                    "<a href=\"/inbox?status=all&amp;feed={}\">{}</a>",
                    esc(&urlencode(fid)),
                    esc(ft)
                );
            } else {
                let _ = write!(out, "<span>{}</span>", esc(ft));
            }
        }
        if let Some(dt) = item.published_at {
            let _ = write!(out, "<span>{}</span>", dt.format("%Y-%m-%d"));
        }
        for tag_name in &item.tag_names.0 {
            let _ = write!(out, "<span class=\"badge\">#{}</span>", esc(tag_name));
        }
        out.push_str("</div>\n");
        if let Some(sum) = item.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            let snippet: String = sum.chars().take(180).collect();
            let suffix = if sum.chars().count() > 180 { "…" } else { "" };
            let _ = writeln!(out, "<p class=\"item-excerpt\">{}{suffix}</p>", esc(&snippet));
        }
        out.push_str("</div>\n<div class=\"inbox-controls\">\n");
        render_audio_play_button(out, item);

        let star_label = if item.is_favorite { "&#9733;" } else { "&#9734;" };
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/items/{id}/star\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\" title=\"Toggle Star\">{star_label}</button>\
             </form>",
            id = esc(&urlencode(&item.id)),
            csrf = esc(csrf),
            ret = esc(return_to),
        );

        let (arch_action, arch_label) = if item.status == ItemStatus::Unread {
            ("archive", "Archive")
        } else {
            ("unarchive", "Restore")
        };
        let _ = writeln!(
            out,
            "<form method=\"post\" action=\"/web/items/{id}/{arch_action}\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\">{arch_label}</button>\
             </form>\n</div>\n</li>",
            id = esc(&urlencode(&item.id)),
            csrf = esc(csrf),
            ret = esc(return_to),
        );
    }
    out.push_str("</ul>\n");
}

pub(in crate::web) async fn starred_get<F: FeedFetcher>(
    State(_service): State<Arc<RocheService<F>>>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    if let Some(err) = flash.err.as_deref().filter(|s| !s.is_empty()) {
        redirect_see_other(&format!("/lists/starred?err={}", urlencode(err)))
    } else if let Some(notice) = flash.notice.as_deref().filter(|s| !s.is_empty()) {
        redirect_see_other(&format!("/lists/starred?notice={}", urlencode(notice)))
    } else {
        redirect_see_other("/lists/starred")
    }
}

pub(in crate::web) async fn post_item_save<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<SaveUrlForm>,
) -> Response {
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
        let sep = if fallback_err_dest.contains('?') { '&' } else { '?' };
        return redirect_see_other(&format!(
            "{fallback_err_dest}{sep}err=Please+enter+a+valid+http(s)+URL"
        ));
    };
    let tags: Vec<String> = form.tags.as_deref().map_or_default(|s| {
        s.split(',').map(str::trim).filter(|t| !t.is_empty()).map(ToOwned::to_owned).collect()
    });

    match service.items().save(&parsed_url, form.title.as_deref(), &tags).await {
        Ok(saved) => {
            if let Some(ret) = form.return_to.as_deref().filter(|s| !s.trim().is_empty()) {
                let base = safe_local_redirect(Some(ret), "/");
                let sep = if base.contains('?') { '&' } else { '?' };
                redirect_see_other(&format!(
                    "{base}{sep}notice={}",
                    urlencode("Added to Want to Read")
                ))
            } else {
                redirect_see_other(&format!(
                    "/reader/{}?notice={}",
                    urlencode(&saved.item.id),
                    urlencode("Added to Want to Read")
                ))
            }
        }
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_item_archive_batch<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ArchiveBatchForm>,
) -> Response {
    let explicit_ids: Vec<String> = form.ids.as_deref().map_or_default(|raw| {
        raw.split(',').map(str::trim).filter(|s| !s.is_empty()).map(ToOwned::to_owned).collect()
    });
    let feed_id = form.feed_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let batch_res = if explicit_ids.is_empty() {
        service.items().archive_batch(&[], feed_id, true).await
    } else {
        service.items().archive_batch(&explicit_ids, None, false).await
    };
    match batch_res {
        Ok(count) => {
            let base = safe_local_redirect(form.return_to.as_deref(), "/inbox");
            let sep = if base.contains('?') { '&' } else { '?' };
            redirect_see_other(&format!(
                "{base}{sep}notice={}",
                urlencode(&format!("Archived {count} unread items"))
            ))
        }
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_item_star<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Response {
    let cur = match service.items().get(&id).await {
        Ok(d) => d.item.is_favorite,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };
    match service.items().update(&id, None, Some(!cur), None, ReadAtPatch::Unchanged).await {
        Ok(_) => redirect_see_other(&safe_local_redirect(form.return_to.as_deref(), "/inbox")),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_item_archive<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Response {
    let clamped_prog = form.progress.filter(|p| p.is_finite()).map(|p| p.clamp(0.0, 1.0));
    let res = service
        .items()
        .update(&id, Some(ItemStatus::Archived), None, clamped_prog, ReadAtPatch::Unchanged)
        .await;
    match res {
        Ok(_) => redirect_see_other(&safe_local_redirect(form.return_to.as_deref(), "/inbox")),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_item_unarchive<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Response {
    match service.items().unarchive(&id).await {
        Ok(_) => redirect_see_other(&safe_local_redirect(form.return_to.as_deref(), "/inbox")),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}
