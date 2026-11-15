use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use serde::Deserialize;
use url::Url;

use super::{
    auth::WebSession,
    html::{
        FlashQuery, NavSection, PageShell, esc, redirect_see_other, render_page,
        safe_external_href, safe_local_redirect, urlencode, web_api_error_response,
    },
};
use crate::{
    content::fetcher::HtmlSanitizer,
    db::{
        ConsumptionStatus, ContentType, EntryKind, Feed, FeedType, Item, ItemFilter, ItemStatus,
        ItemStatusFilter, MediaType, ReadAtPatch, SyncRepo,
    },
    error::ApiError,
    feeds::FeedSource,
    scheduler::FeedFetcher,
    service::{ItemDetail, RocheService},
};

#[derive(Debug, Default, Deserialize)]
pub(super) struct InboxQuery {
    pub status: Option<String>,
    pub kind: Option<String>,
    pub feed: Option<String>,
    pub tag: Option<String>,
    pub q: Option<String>,
    pub page: Option<usize>,
    pub notice: Option<String>,
    pub err: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct SaveUrlForm {
    pub url: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub tags: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ArchiveBatchForm {
    #[serde(default)]
    pub feed_id: Option<String>,
    #[serde(default)]
    pub ids: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ItemReturnForm {
    #[serde(default)]
    pub progress: Option<f64>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ProgressForm {
    pub progress: f64,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct FinishItemForm {
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
    #[serde(default)]
    pub return_to: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct AddTagForm {
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct CreateFeedForm {
    pub url: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub feed_type: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct ImportOpmlForm {
    pub opml_xml: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct FeedToggleForm {
    pub active: String,
}

pub(super) fn render_audio_play_button(out: &mut String, item: &Item) {
    if item.content_type != ContentType::Audio && item.audio_url.is_none() {
        return;
    }
    let feed_label = item.feed_title.as_deref().unwrap_or("Podcast");
    let dur = item.duration.unwrap_or(0);
    let _ = write!(
        out,
        "<button type=\"button\" class=\"btn btn-small js-play-audio\" \
         data-item-id=\"{}\" data-title=\"{}\" data-feed=\"{}\" \
         data-src=\"/web/items/{}/audio\" data-progress=\"{:.4}\" data-duration=\"{dur}\">\
         &#9654; Play</button>",
        esc(&item.id),
        esc(&item.title),
        esc(feed_label),
        esc(&urlencode(&item.id)),
        item.read_progress.clamp(0.0, 1.0),
    );
}

pub(super) async fn inbox_get<F: FeedFetcher>(
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
    body.push_str("<h2>Inbox</h2>\n");
    render_inbox_filter_bar(&mut body, &nav_ctx);
    render_inbox_save_and_batch_controls(
        &mut body,
        &session.csrf_token,
        &nav_ctx,
        &page.items,
        &return_url,
    );
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

fn render_inbox_save_and_batch_controls(
    out: &mut String,
    csrf: &str,
    p: &InboxNavParams<'_>,
    items: &[Item],
    return_to: &str,
) {
    let _ = write!(
        out,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;gap:0.75rem;flex-wrap:wrap;margin-bottom:0.75rem\">\n\
           <details class=\"editorial-panel\" style=\"margin:0;flex:1;min-width:16rem\">\n\
             <summary>+ Save URL to Inbox</summary>\n\
             <form method=\"post\" action=\"/web/items/save\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <div class=\"form-grid\">\n\
                 <div class=\"field\"><label for=\"save-url\">URL</label>\
                   <input id=\"save-url\" name=\"url\" type=\"url\" required placeholder=\"https://...\"></div>\n\
                 <div class=\"field\"><label for=\"save-title\">Title (optional)</label>\
                   <input id=\"save-title\" name=\"title\" type=\"text\" placeholder=\"Auto-extracted if blank\"></div>\n\
                 <div class=\"field\"><label for=\"save-tags\">Tags (comma-separated)</label>\
                   <input id=\"save-tags\" name=\"tags\" type=\"text\" placeholder=\"systems, ml\"></div>\n\
               </div>\n\
               <button type=\"submit\" class=\"btn btn-primary\">Save Item</button>\n\
             </form>\n\
           </details>\n",
        csrf = esc(csrf),
    );

    let has_narrowing = p.kind.is_some() || p.tag.is_some() || p.q.is_some();
    if has_narrowing {
        let unread_ids: Vec<&str> = items
            .iter()
            .filter(|i| i.status == ItemStatus::Unread)
            .map(|i| i.id.as_str())
            .collect();
        if !unread_ids.is_empty() {
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
        }
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

fn render_item_rows(out: &mut String, items: &[Item], csrf: &str, return_to: &str) {
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

pub(super) async fn starred_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let filter = ItemFilter {
        status: Some(ItemStatusFilter::All),
        is_favorite: Some(true),
        include_content: Some(false),
        per_page: Some(100),
        ..ItemFilter::default()
    };
    let page = match service.items().query().with_filter(filter).list_page().await {
        Ok(p) => p,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };

    let mut body = String::with_capacity(4_096);
    let _ = writeln!(
        body,
        "<h2>Starred</h2>\n<p class=\"lede\">{} starred items across your feeds and saved links.</p>",
        page.items.len()
    );
    render_item_rows(&mut body, &page.items, &session.csrf_token, "/starred");

    render_page(&PageShell {
        title: "Starred",
        active: NavSection::Starred,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

pub(super) async fn feeds_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let feeds = match service.feeds().list().await {
        Ok(f) => f,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };

    let mut body = String::with_capacity(6_144);
    let _ = writeln!(
        body,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:1rem\">\
           <h2>Feeds</h2>\
           <a class=\"btn btn-small\" href=\"/web/feeds/export.opml\" download=\"roche-feeds.opml\">Export OPML</a>\
         </div>\n\
         <p class=\"lede\">{} subscribed sources (RSS/Atom, Podcasts, YouTube channels, arXiv).</p>",
        feeds.len()
    );

    let _ = write!(
        body,
        "<details class=\"editorial-panel\" open>\n\
           <summary>+ Subscribe to Feed</summary>\n\
           <form method=\"post\" action=\"/web/feeds/create\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"form-grid\">\n\
               <div class=\"field\"><label for=\"feed-url\">Feed URL or YouTube Channel ID</label>\
                 <input id=\"feed-url\" name=\"url\" type=\"text\" required placeholder=\"https://example.com/feed.xml or UC...\"></div>\n\
               <div class=\"field\"><label for=\"feed-title\">Custom Title (optional)</label>\
                 <input id=\"feed-title\" name=\"title\" type=\"text\" placeholder=\"Leave blank to use feed title\"></div>\n\
               <div class=\"field\"><label for=\"feed-type\">Source Kind</label>\
                 <select id=\"feed-type\" name=\"feed_type\">\
                   <option value=\"rss\">Auto / RSS / Podcast</option>\
                   <option value=\"youtube\">YouTube</option>\
                   <option value=\"arxiv\">arXiv</option>\
                 </select></div>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Subscribe</button>\n\
           </form>\n\
         </details>\n\
         <details class=\"editorial-panel\">\n\
           <summary>Import OPML</summary>\n\
           <form method=\"post\" action=\"/web/feeds/opml\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"field\" style=\"margin-bottom:0.6rem\">\n\
               <label for=\"opml-file\">Choose .opml / .xml file (or paste XML below)</label>\n\
               <input id=\"opml-file\" type=\"file\" accept=\".opml,.xml,text/xml\" class=\"js-opml-file\">\n\
             </div>\n\
             <div class=\"field\" style=\"margin-bottom:0.65rem\">\n\
               <label for=\"opml-xml\">OPML XML</label>\n\
               <textarea id=\"opml-xml\" name=\"opml_xml\" required class=\"js-opml-textarea\" placeholder=\"&lt;opml version=&quot;2.0&quot;&gt;...\"></textarea>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Import Subscriptions</button>\n\
           </form>\n\
         </details>\n",
        csrf = esc(&session.csrf_token),
    );

    render_feeds_table(&mut body, &feeds, &session.csrf_token);

    render_page(&PageShell {
        title: "Feeds",
        active: NavSection::Feeds,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

fn render_feeds_table(out: &mut String, feeds: &[Feed], csrf: &str) {
    if feeds.is_empty() {
        out.push_str("<p class=\"empty\">No feeds subscribed yet.</p>\n");
        return;
    }

    out.push_str("<ul class=\"inbox-list\">\n");
    for f in feeds {
        let title = f.title.as_deref().filter(|s| !s.is_empty()).unwrap_or_else(|| f.url.as_str());
        let _ = write!(
            out,
            "<li class=\"inbox-row\">\n\
               <div class=\"inbox-main\">\n\
                 <div class=\"inbox-title\"><a href=\"/inbox?status=all&amp;feed={id}\">{title}</a></div>\n\
                 <div class=\"item-byline\">\n\
                   <span class=\"badge\">{ftype:?}</span>\n\
                   <span>{unread} unread</span>",
            id = esc(&urlencode(&f.id)),
            title = esc(title),
            ftype = f.feed_type,
            unread = f.unread_count,
        );
        if !f.is_active {
            out.push_str("<span class=\"badge\">Paused</span>");
        }
        if let Some(polled) = f.last_polled {
            let _ = write!(out, "<span>Polled {}</span>", polled.format("%Y-%m-%d %H:%M"));
        }
        if let Some(err) = f.last_error.as_deref().filter(|s| !s.is_empty()) {
            let short_err: String = err.chars().take(90).collect();
            let _ = write!(out, "<span class=\"badge badge-warn\">{}</span>", esc(&short_err));
        }
        out.push_str("</div>\n</div>\n<div class=\"inbox-controls\">\n");

        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/feeds/{id}/refresh\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <button type=\"submit\" class=\"btn btn-small\">Refresh</button>\
             </form>",
            id = esc(&urlencode(&f.id)),
            csrf = esc(csrf),
        );

        let (next_active, toggle_label) =
            if f.is_active { ("false", "Pause") } else { ("true", "Resume") };
        let _ = writeln!(
            out,
            "<form method=\"post\" action=\"/web/feeds/{id}/toggle-active\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <input type=\"hidden\" name=\"active\" value=\"{next_active}\">\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\">{toggle_label}</button>\
             </form>\
             <form method=\"post\" action=\"/web/feeds/{id}/delete\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <button type=\"submit\" class=\"btn btn-small btn-ghost\">Delete</button>\
             </form>\n</div>\n</li>",
            id = esc(&urlencode(&f.id)),
            csrf = esc(csrf),
        );
    }
    out.push_str("</ul>\n");
}

pub(super) async fn reader_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let detail = match service.items().get(&id).await {
        Ok(d) => d,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };

    let is_consumed =
        service.catalog().get_entry_detail(&id).await.ok().flatten().is_some_and(|ed| {
            ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed)
        });

    let mut body = String::with_capacity(12_288);
    render_reader_article(&mut body, &detail, is_consumed, &session.csrf_token);

    render_page(&PageShell {
        title: &detail.item.title,
        active: NavSection::Inbox,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

fn render_reader_article(out: &mut String, detail: &ItemDetail, is_consumed: bool, csrf: &str) {
    let item = &detail.item;
    let entry_kind = EntryKind::from_content_type(item.content_type);
    let list_slug = entry_kind.default_list_slug().unwrap_or("articles");
    let reader_path = format!("/reader/{}", urlencode(&item.id));

    let _ = writeln!(
        out,
        "<article class=\"reader-article js-reader-root\" data-item-id=\"{}\" data-kind=\"{}\" data-progress=\"{:.4}\">\n\
         <div class=\"reader-header\">\n\
           <div class=\"item-byline\">",
        esc(&item.id),
        entry_kind.as_str(),
        item.read_progress.clamp(0.0, 1.0),
    );
    if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
        let _ = write!(out, "<strong>{}</strong>", esc(ft));
    }
    if let Some(author) = item.author.as_deref().filter(|s| !s.is_empty()) {
        let _ = write!(out, "<span>by {}</span>", esc(author));
    }
    if let Some(dt) = item.published_at {
        let _ = write!(out, "<span>{}</span>", dt.format("%B %d, %Y"));
    }
    if let Some(ext_href) = safe_external_href(item.url.as_str())
        && !ext_href.starts_with("https://roche.local/")
    {
        let _ = write!(
            out,
            "<a href=\"{}\" target=\"_blank\" rel=\"noopener\">Original &#8599;</a>",
            esc(ext_href)
        );
    }
    out.push_str("</div>\n");

    let _ = writeln!(out, "<h2>{}</h2>", esc(&item.title));

    out.push_str("<div class=\"reader-toolbar\">\n");
    render_audio_play_button(out, item);

    // Explicit "Finished" & "Finished & Rank" controls (essential for Videos, available on all feed items)
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/{id}/finish\" class=\"inline-form\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
           <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\
           <button type=\"submit\" class=\"btn btn-primary btn-small\">&#10003; Finished</button>\
         </form>\
         <form method=\"post\" action=\"/web/items/{id}/finish\" class=\"inline-form\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
           <input type=\"hidden\" name=\"rank_after\" value=\"1\">\
           <button type=\"submit\" class=\"btn btn-small\">Finished &amp; Rank</button>\
         </form>",
        id = esc(&urlencode(&item.id)),
        csrf = esc(csrf),
        ret = esc(&reader_path),
    );

    let (arch_action, arch_label) = if item.status == ItemStatus::Unread {
        ("archive", "Archive")
    } else {
        ("unarchive", "Move to Inbox")
    };
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/{id}/{arch_action}\" class=\"inline-form js-reader-archive-form\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
           <input type=\"hidden\" name=\"progress\" value=\"{prog:.4}\">\
           <input type=\"hidden\" name=\"return_to\" value=\"/inbox\">\
           <button type=\"submit\" class=\"btn btn-small\">{arch_label}</button>\
         </form>",
        id = esc(&urlencode(&item.id)),
        csrf = esc(csrf),
        prog = item.read_progress.clamp(0.0, 1.0),
    );

    let star_label = if item.is_favorite { "&#9733; Starred" } else { "&#9734; Star" };
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/{id}/star\" class=\"inline-form\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
           <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\
           <button type=\"submit\" class=\"btn btn-small btn-ghost\">{star_label}</button>\
         </form>\
         <a class=\"btn btn-small btn-ghost\" href=\"/entries/{id}\">Catalog Entry</a>",
        id = esc(&urlencode(&item.id)),
        csrf = esc(csrf),
        ret = esc(&reader_path),
    );
    if is_consumed {
        let _ = write!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"/rank/{slug}?focus={id}\">Compare</a>",
            slug = esc(&urlencode(list_slug)),
            id = esc(&urlencode(&item.id)),
        );
    }
    out.push_str("</div>\n</div>\n");

    render_reader_media_and_tags(out, detail, csrf);
}

fn render_reader_media_and_tags(out: &mut String, detail: &ItemDetail, csrf: &str) {
    let item = &detail.item;
    for m in &detail.media {
        match m.media_type {
            MediaType::Video => {
                let _ = writeln!(
                    out,
                    "<div class=\"reader-media-box\">\
                       <video controls preload=\"metadata\" src=\"/web/media/{}\"></video>\
                     </div>",
                    esc(&urlencode(&m.id))
                );
            }
            MediaType::Pdf => {
                let _ = writeln!(
                    out,
                    "<div class=\"reader-media-box\">\
                       <a class=\"btn\" href=\"/web/media/{}\" target=\"_blank\" rel=\"noopener\">Open Cached PDF &#8599;</a>\
                     </div>",
                    esc(&urlencode(&m.id))
                );
            }
            MediaType::Audio | MediaType::Thumbnail => {}
        }
    }

    out.push_str("<div class=\"reader-prose\">\n");
    if let Some(raw_html) = item.content_html.as_deref().filter(|s| !s.trim().is_empty()) {
        out.push_str(&HtmlSanitizer::clean(raw_html));
    } else if let Some(summary) = item.summary.as_deref().filter(|s| !s.trim().is_empty()) {
        for para in summary.split("\n\n").map(str::trim).filter(|p| !p.is_empty()) {
            let _ = writeln!(out, "<p>{}</p>", esc(para));
        }
    } else {
        out.push_str("<p class=\"empty\">No extracted article text stored for this entry.</p>\n");
    }
    out.push_str("</div>\n");

    out.push_str("<h3>Tags</h3>\n<div class=\"pill-bar\">\n");
    for tag in &detail.tags {
        let _ = writeln!(
            out,
            "<span class=\"pill\">#{} \
               <form method=\"post\" action=\"/web/items/{}/tags/{}/remove\" class=\"inline-form\">\
                 <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
                 <button type=\"submit\" class=\"link-btn\" style=\"margin-left:0.25rem\">&times;</button>\
               </form>\
             </span>",
            esc(&tag.name),
            esc(&urlencode(&item.id)),
            esc(&urlencode(&tag.id)),
            esc(csrf),
        );
    }
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/{}/tags\" style=\"display:inline-flex;gap:0.35rem\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
           <input type=\"text\" name=\"name\" required placeholder=\"Add tag...\" style=\"width:9rem;padding:0.22rem 0.45rem;font-size:0.82rem\">\
           <button type=\"submit\" class=\"btn btn-small\">+ Tag</button>\
         </form>\n</div>\n</article>\n",
        esc(&urlencode(&item.id)),
        esc(csrf),
    );
}

pub(super) async fn item_audio_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Response {
    let detail = match service.items().get(&id).await {
        Ok(d) => d,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };
    if let Some(local_audio) = detail.media.iter().find(|m| m.media_type == MediaType::Audio)
        && let Ok(resp) = crate::api::serve_media_stream(&service, &local_audio.id, &headers).await
    {
        return resp;
    }
    if let Some(ext_audio) = detail.item.audio_url.as_deref().and_then(safe_external_href) {
        let mut resp = StatusCode::SEE_OTHER.into_response();
        if let Ok(val) = header::HeaderValue::from_str(ext_audio) {
            resp.headers_mut().insert(header::LOCATION, val);
            return resp;
        }
    }
    web_api_error_response(
        &ApiError::not_found("Audio stream not found for this item"),
        Some(&session.csrf_token),
    )
}

pub(super) async fn web_media_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Response {
    match crate::api::serve_media_stream(&service, &id, &headers).await {
        Ok(resp) => resp,
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_item_save<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<SaveUrlForm>,
) -> Response {
    let Ok(parsed_url) = Url::parse(form.url.trim()) else {
        return redirect_see_other("/inbox?err=Please+enter+a+valid+http(s)+URL");
    };
    let tags: Vec<String> = form
        .tags
        .as_deref()
        .map(|s| {
            s.split(',').map(str::trim).filter(|t| !t.is_empty()).map(ToOwned::to_owned).collect()
        })
        .unwrap_or_default();

    match service.items().save(&parsed_url, form.title.as_deref(), &tags).await {
        Ok(saved) => redirect_see_other(&format!(
            "/reader/{}?notice={}",
            urlencode(&saved.item.id),
            urlencode("Saved to Inbox")
        )),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_item_archive_batch<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ArchiveBatchForm>,
) -> Response {
    let explicit_ids: Vec<String> = form
        .ids
        .as_deref()
        .map(|raw| {
            raw.split(',').map(str::trim).filter(|s| !s.is_empty()).map(ToOwned::to_owned).collect()
        })
        .unwrap_or_default();
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

pub(super) async fn post_item_star<F: FeedFetcher>(
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

pub(super) async fn post_item_archive<F: FeedFetcher>(
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

pub(super) async fn post_item_unarchive<F: FeedFetcher>(
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

pub(super) async fn post_item_progress<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ProgressForm>,
) -> Response {
    let p = if form.progress.is_finite() { form.progress.clamp(0.0, 1.0) } else { 0.0 };
    let patch =
        if p >= 0.98 { ReadAtPatch::MarkRead(SyncRepo::now_utc()) } else { ReadAtPatch::Unchanged };
    match service.items().update(&id, None, None, Some(p), patch).await {
        Ok(_) => StatusCode::NO_CONTENT.into_response(),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_item_finish<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<FinishItemForm>,
) -> Response {
    let detail = match service.items().get(&id).await {
        Ok(d) => d,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };
    if let Err(err) = service
        .catalog()
        .log_consumption(&id, ConsumptionStatus::Consumed, form.notes.as_deref(), None)
        .await
    {
        return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
    }
    let _ = service
        .items()
        .update(
            &id,
            Some(ItemStatus::Archived),
            None,
            Some(1.0),
            ReadAtPatch::MarkRead(SyncRepo::now_utc()),
        )
        .await;

    let entry_kind = EntryKind::from_content_type(detail.item.content_type);
    let list_slug = entry_kind.default_list_slug().unwrap_or("articles");
    if form.rank_after.as_deref() == Some("1") {
        redirect_see_other(&format!(
            "/rank/{}?focus={}&notice={}",
            urlencode(list_slug),
            urlencode(&id),
            urlencode("Marked finished & logged to Diary")
        ))
    } else {
        let base = safe_local_redirect(form.return_to.as_deref(), "/inbox");
        let sep = if base.contains('?') { '&' } else { '?' };
        redirect_see_other(&format!(
            "{base}{sep}notice={}",
            urlencode("Marked finished & logged to Diary")
        ))
    }
}

pub(super) async fn post_item_tag_add<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<AddTagForm>,
) -> Response {
    match service.items().add_tag(&id, None, Some(&form.name)).await {
        Ok(_) => redirect_see_other(&format!("/reader/{}", urlencode(&id))),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_item_tag_remove<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path((id, tag_id)): Path<(String, String)>,
) -> Response {
    match service.items().remove_tag(&id, &tag_id).await {
        Ok(()) => redirect_see_other(&format!("/reader/{}", urlencode(&id))),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_feed_create<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<CreateFeedForm>,
) -> Response {
    let source = match FeedSource::parse(&form.url) {
        Ok(s) => s,
        Err(e) => {
            return redirect_see_other(&format!("/feeds?err={}", urlencode(&e.to_string())));
        }
    };
    let req_type = match form.feed_type.as_deref() {
        Some("youtube") => FeedType::Youtube,
        Some("arxiv") => FeedType::Arxiv,
        _ => FeedType::Rss,
    };
    match service.feeds().create(req_type, source, form.title.as_deref(), None).await {
        Ok(feed) => {
            let service_bg = Arc::clone(&service);
            let feed_id = feed.id;
            tokio::spawn(async move {
                let _ = service_bg.feeds().refresh(&feed_id).await;
            });
            redirect_see_other(&format!(
                "/feeds?notice={}",
                urlencode("Subscribed to feed (refreshing in background)")
            ))
        }
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_feed_opml<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ImportOpmlForm>,
) -> Response {
    match service.feeds().import_opml(form.opml_xml).await {
        Ok(imported) => {
            let count = imported.len();
            if !imported.is_empty() {
                let service_bg = Arc::clone(&service);
                tokio::spawn(async move {
                    service_bg.feeds().poll_feeds_concurrently(imported).await;
                });
            }
            redirect_see_other(&format!(
                "/feeds?notice={}",
                urlencode(&format!("Imported {count} feeds from OPML"))
            ))
        }
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn export_opml_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
) -> Response {
    match service.feeds().export_opml().await {
        Ok(xml) => (
            StatusCode::OK,
            [
                (header::CONTENT_TYPE, "text/x-opml; charset=utf-8"),
                (header::CONTENT_DISPOSITION, "attachment; filename=\"roche-feeds.opml\""),
            ],
            xml,
        )
            .into_response(),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_feed_refresh<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
) -> Response {
    match service.feeds().refresh(&id).await {
        Ok(_) => redirect_see_other(&format!("/feeds?notice={}", urlencode("Refreshed feed"))),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_feed_toggle_active<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<FeedToggleForm>,
) -> Response {
    let want_active = matches!(form.active.trim(), "true" | "1" | "on");
    let res = if want_active {
        service.feeds().unarchive(&id).await
    } else {
        service.feeds().archive(&id).await
    };
    match res {
        Ok(_) => redirect_see_other("/feeds"),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(super) async fn post_feed_delete<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
) -> Response {
    match service.feeds().delete(&id).await {
        Ok(()) => redirect_see_other(&format!(
            "/feeds?notice={}",
            urlencode("Removed feed subscription (diary & rankings preserved)")
        )),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}
