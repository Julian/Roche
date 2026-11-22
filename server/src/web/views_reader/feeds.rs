use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form, Json,
    extract::{Extension, Path, Query, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};

use super::{
    CreateFeedForm, EditFeedForm, FeedToggleForm, FeedsQuery, ImportOpmlForm, ItemReturnForm,
};
use crate::{
    db::Feed,
    domain::FeedType,
    feeds::FeedSource,
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            NavSection, PageShell, REFRESH_SVG, WebError, WithWebCsrf as _, esc, parse_form_bool,
            redirect_see_other, redirect_with_flash, render_feed_favicon_with_class,
            render_inline_csrf_form_with_class, render_page, safe_local_redirect,
            safe_web_error_message, urlencode,
        },
    },
};

struct FeedStats {
    total: usize,
    active: usize,
    paused: usize,
    failing: usize,
    rss: usize,
    podcast: usize,
    youtube: usize,
    arxiv: usize,
}

impl FeedStats {
    fn from_feeds(feeds: &[Feed]) -> Self {
        let mut stats = Self {
            total: feeds.len(),
            active: 0,
            paused: 0,
            failing: 0,
            rss: 0,
            podcast: 0,
            youtube: 0,
            arxiv: 0,
        };
        for f in feeds {
            if f.is_active {
                stats.active += 1;
                if has_active_error(f) {
                    stats.failing += 1;
                }
            } else {
                stats.paused += 1;
            }
            match f.feed_type {
                FeedType::Rss => stats.rss += 1,
                FeedType::Podcast => stats.podcast += 1,
                FeedType::Youtube => stats.youtube += 1,
                FeedType::Arxiv => stats.arxiv += 1,
            }
        }
        stats
    }
}

fn has_active_error(feed: &Feed) -> bool {
    feed.is_active && feed.last_error.as_deref().is_some_and(|s| !s.trim().is_empty())
}

const fn feed_kind_key(ft: FeedType) -> &'static str {
    match ft {
        FeedType::Rss => "rss",
        FeedType::Podcast => "podcast",
        FeedType::Youtube => "youtube",
        FeedType::Arxiv => "arxiv",
    }
}

const fn feed_kind_badge(ft: FeedType) -> Option<(&'static str, &'static str)> {
    match ft {
        FeedType::Rss => None,
        FeedType::Podcast => Some(("badge-podcast", "Podcast")),
        FeedType::Youtube => Some(("badge-video", "YouTube")),
        FeedType::Arxiv => Some(("badge-paper", "arXiv")),
    }
}

fn clean_feed_error(raw: &str) -> String {
    let mut txt = raw.trim();
    for prefix in ["Upstream feed error: ", "Upstream server returned "] {
        if let Some(stripped) = txt.strip_prefix(prefix) {
            txt = stripped;
        }
    }
    let mut chars = txt.chars();
    let short: String = chars.by_ref().take(110).collect();
    if chars.next().is_some() { format!("{short}…") } else { short }
}

fn display_feed_title(feed: &Feed) -> &str {
    feed.title.as_deref().filter(|s| !s.trim().is_empty()).unwrap_or_else(|| feed.url.as_str())
}

fn display_feed_host(feed: &Feed) -> &str {
    let host = feed.url.host_str().unwrap_or("");
    host.strip_prefix("www.").unwrap_or(host)
}

fn build_feeds_return_url(status: &str, kind: &str, q: &str) -> String {
    let mut parts = Vec::new();
    if !status.is_empty() && status != "all" {
        parts.push(format!("status={}", urlencode(status)));
    }
    if !kind.is_empty() && kind != "all" {
        parts.push(format!("kind={}", urlencode(kind)));
    }
    if !q.is_empty() {
        parts.push(format!("q={}", urlencode(q)));
    }
    if parts.is_empty() { "/feeds".to_owned() } else { format!("/feeds?{}", parts.join("&")) }
}

pub(in crate::web) async fn feeds_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(query): Query<FeedsQuery>,
) -> Result<Response, WebError> {
    let feeds = service.feeds().list().await.web_err(&session)?;

    let initial_status = match query.status.as_deref().map(str::trim) {
        Some("active") => "active",
        Some("errors") => "errors",
        Some("paused") => "paused",
        _ => "all",
    };
    let initial_kind = match query.kind.as_deref().map(str::trim) {
        Some("rss") => "rss",
        Some("podcast") => "podcast",
        Some("youtube") => "youtube",
        Some("arxiv") => "arxiv",
        _ => "all",
    };
    let initial_q = query.q.as_deref().map_or("", str::trim);
    let return_url = build_feeds_return_url(initial_status, initial_kind, initial_q);

    let stats = FeedStats::from_feeds(&feeds);
    let mut sorted: Vec<&Feed> = feeds.iter().collect();
    sorted.sort_by(|a, b| {
        let bucket = |f: &Feed| -> u8 {
            if has_active_error(f) {
                0
            } else if f.is_active {
                1
            } else {
                2
            }
        };
        bucket(a).cmp(&bucket(b)).then_with(|| {
            display_feed_title(a).to_lowercase().cmp(&display_feed_title(b).to_lowercase())
        })
    });

    let mut body = String::with_capacity(8_192);
    body.push_str(
        "<div class=\"feeds-grid\">\n<section class=\"feeds-main\" aria-label=\"Subscribed feeds\">\n",
    );
    render_feeds_mobile_subscribe(&mut body, &session.csrf_token);
    if sorted.is_empty() {
        body.push_str(
            "<p class=\"empty\">No feeds subscribed yet. Paste a site homepage, feed URL, or YouTube channel in the Subscribe panel to get started.</p>\n",
        );
    } else {
        render_feeds_toolbar(&mut body, &stats, initial_status, initial_kind, initial_q);
        render_feeds_list(&mut body, &sorted, &return_url, &session.csrf_token);
    }
    body.push_str("</section>\n");
    render_feeds_rail(&mut body, &stats, &session.csrf_token);
    body.push_str("</div>\n");

    Ok(render_page(&PageShell {
        title: "Feeds",
        active: NavSection::Feeds,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

fn render_feeds_mobile_subscribe(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<details class=\"editorial-panel feeds-mobile-subscribe\">\n\
           <summary>+ Subscribe to Feed</summary>\n\
           <form method=\"post\" action=\"/web/feeds/create\" class=\"rail-form mt-sm\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"field\">\n\
               <label for=\"m-feed-url\">Site, Feed URL, or YouTube Channel</label>\n\
               <input id=\"m-feed-url\" name=\"url\" type=\"text\" required placeholder=\"https://danluu.com or feed.xml\">\n\
             </div>\n\
             <div class=\"field\">\n\
               <label for=\"m-feed-title\">Custom Title (optional)</label>\n\
               <input id=\"m-feed-title\" name=\"title\" type=\"text\" placeholder=\"Auto-detected from feed\">\n\
             </div>\n\
             <div class=\"field\">\n\
               <label for=\"m-feed-type\">Source Kind</label>\n\
               <select id=\"m-feed-type\" name=\"feed_type\">\n\
                 <option value=\"rss\">Auto / RSS</option>\n\
                 <option value=\"podcast\">Podcast</option>\n\
                 <option value=\"youtube\">YouTube</option>\n\
                 <option value=\"arxiv\">arXiv</option>\n\
               </select>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary btn-small\">Subscribe</button>\n\
           </form>\n\
         </details>\n",
        csrf = esc(csrf),
    );
}

fn render_feeds_toolbar(
    out: &mut String,
    stats: &FeedStats,
    initial_status: &str,
    initial_kind: &str,
    initial_q: &str,
) {
    let pill = |status: &str, label: &str, count: usize, extra_cls: &str| -> String {
        let active = initial_status == status;
        let cls = if active {
            format!("pill {extra_cls} is-active js-feeds-pill")
        } else {
            format!("pill {extra_cls} js-feeds-pill")
        };
        let pressed = if active { "true" } else { "false" };
        format!(
            "<button type=\"button\" class=\"{}\" data-status=\"{status}\" aria-pressed=\"{pressed}\">{label} ({count})</button>",
            cls.trim(),
        )
    };
    let _ = writeln!(
        out,
        "<div class=\"feeds-toolbar\" data-initial-status=\"{initial_status}\">\n\
           <div class=\"feeds-toolbar-pills\" role=\"group\" aria-label=\"Filter by status\">\n\
             {}",
        pill("all", "All", stats.total, ""),
    );
    if stats.paused > 0 {
        let _ = writeln!(out, "{}", pill("active", "Active", stats.active, ""));
    }
    if stats.failing > 0 {
        let _ = writeln!(out, "{}", pill("errors", "Errors", stats.failing, "pill-warn"));
    }
    if stats.paused > 0 {
        let _ = writeln!(out, "{}", pill("paused", "Paused", stats.paused, ""));
    }
    out.push_str(
        "</div>\n\
         <div class=\"feeds-toolbar-controls\">\n\
           <select id=\"roche-feeds-kind\" class=\"feeds-kind-select\" aria-label=\"Filter by feed type\">\n\
             <option value=\"all\">All types</option>",
    );
    for (val, label, count) in [
        ("rss", "RSS", stats.rss),
        ("podcast", "Podcasts", stats.podcast),
        ("youtube", "YouTube", stats.youtube),
        ("arxiv", "arXiv", stats.arxiv),
    ] {
        if count > 0 {
            let sel = if initial_kind == val { " selected" } else { "" };
            let _ = write!(out, "<option value=\"{val}\"{sel}>{label} ({count})</option>");
        }
    }
    let _ = write!(
        out,
        "</select>\n\
           <input type=\"search\" id=\"roche-feeds-search\" class=\"feeds-filter-input\" value=\"{}\" placeholder=\"Filter feeds…\" aria-label=\"Filter subscribed feeds\">\n\
         </div>\n\
       </div>\n",
        esc(initial_q),
    );
}

fn render_feeds_list(out: &mut String, feeds: &[&Feed], return_url: &str, csrf: &str) {
    out.push_str("<ul class=\"inbox-list\" id=\"roche-feeds-list\">\n");
    for f in feeds {
        render_feed_row(out, f, return_url, csrf);
    }
    out.push_str(
        "</ul>\n\
         <div id=\"roche-feeds-empty\" class=\"filter-empty-state\" hidden>\n\
           <p class=\"empty\">No subscribed feeds match this filter.</p>\n\
           <button type=\"button\" class=\"btn btn-small btn-ghost js-feeds-clear\">Clear filters</button>\n\
         </div>\n",
    );
}

fn render_feed_row(out: &mut String, f: &Feed, return_url: &str, csrf: &str) {
    let title = display_feed_title(f);
    let host = display_feed_host(f);
    let kind_key = feed_kind_key(f.feed_type);
    let paused = !f.is_active;
    let full_err = if paused {
        None
    } else {
        f.last_error.as_deref().map(str::trim).filter(|s| !s.is_empty())
    };
    let active_err = full_err.map(clean_feed_error);
    let row_cls = if paused { "inbox-row feed-row is-paused" } else { "inbox-row feed-row" };
    let search_blob = format!("{title} {host} {kind_key}");

    let _ = writeln!(
        out,
        "<li class=\"{row_cls}\" data-feed-search=\"{}\" data-feed-kind=\"{kind_key}\" data-feed-paused=\"{}\" data-feed-error=\"{}\">",
        esc(&search_blob),
        if paused { "1" } else { "0" },
        if active_err.is_some() { "1" } else { "0" },
    );
    render_feed_favicon_with_class(out, Some(f.url.as_str()), title, "feed-favicon-lg");

    let id_enc = urlencode(&f.id);
    let feed_inbox_status = if f.unread_count > 0 { "unread" } else { "all" };
    let _ = write!(
        out,
        "<div class=\"inbox-main\">\n\
           <div class=\"inbox-title\"><a href=\"/inbox?status={feed_inbox_status}&amp;feed={id}\">{title}</a></div>\n\
           <div class=\"item-byline\">",
        id = esc(&id_enc),
        title = esc(title),
    );
    if let Some((badge_cls, label)) = feed_kind_badge(f.feed_type) {
        let _ = write!(out, "<span class=\"badge badge-kind {badge_cls}\">{label}</span>");
    }
    if paused {
        out.push_str("<span class=\"badge\">Paused</span>");
    }
    if f.unread_count > 0 {
        let _ = write!(
            out,
            "<span class=\"byline-item feed-row-unread\">{} unread</span>",
            f.unread_count,
        );
    } else {
        out.push_str("<span class=\"byline-item\">0 unread</span>");
    }
    if !host.is_empty() {
        let _ = write!(out, "<span class=\"byline-item\">{}</span>", esc(host));
    }
    if let Some(polled) = f.last_polled {
        let _ = write!(
            out,
            "<span class=\"byline-item\">Polled {}</span>",
            polled.format("%b %d, %H:%M"),
        );
    }
    out.push_str("</div>\n");
    if let Some(err_txt) = active_err {
        let _ = writeln!(out, "<p class=\"feed-row-error\">&#9888; {}</p>", esc(&err_txt));
    }
    render_feed_row_details(out, f, title, &id_enc, full_err, return_url, csrf);
    out.push_str("</div>\n");
    render_feed_row_controls(out, f, title, &id_enc, return_url, csrf);
    out.push_str("</li>\n");
}

fn render_feed_row_details(
    out: &mut String,
    f: &Feed,
    title: &str,
    id_enc: &str,
    full_err: Option<&str>,
    return_url: &str,
    csrf: &str,
) {
    let _ = write!(
        out,
        "<details class=\"feed-row-details\">\n\
           <summary>Details &amp; Edit</summary>\n\
           <div class=\"feed-row-details-body\">\n\
             <div class=\"feed-url-bar\">\n\
               <input type=\"text\" readonly value=\"{url}\" class=\"feed-url-code\" aria-label=\"Feed URL for {title}\">\n\
               <button type=\"button\" class=\"btn btn-small btn-ghost js-copy-text\" data-copy-text=\"{url}\">Copy URL</button>\n\
             </div>",
        url = esc(f.url.as_str()),
        title = esc(title),
    );
    if let Some(raw_err) = full_err {
        let _ = writeln!(
            out,
            "<p class=\"feed-error-full\"><strong>Last error:</strong> {}</p>",
            esc(raw_err),
        );
    }
    let feed_type_field = if matches!(f.feed_type, FeedType::Rss | FeedType::Podcast) {
        let rss_sel = if f.feed_type == FeedType::Rss { " selected" } else { "" };
        let pod_sel = if f.feed_type == FeedType::Podcast { " selected" } else { "" };
        format!(
            "<div class=\"field\">\n\
               <label for=\"fe-type-{id_enc}\">Feed Type</label>\n\
               <select id=\"fe-type-{id_enc}\" name=\"feed_type\">\n\
                 <option value=\"rss\"{rss_sel}>RSS / Newsletter</option>\n\
                 <option value=\"podcast\"{pod_sel}>Podcast</option>\n\
               </select>\n\
             </div>\n",
            id_enc = esc(id_enc),
        )
    } else {
        String::new()
    };
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/feeds/{id_enc}/edit\" class=\"feed-edit-form js-feeds-preserve-form\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\n\
           <div class=\"feed-edit-grid\">\n\
             <div class=\"field\">\n\
               <label for=\"fe-title-{id_enc}\">Title</label>\n\
               <input id=\"fe-title-{id_enc}\" name=\"title\" type=\"text\" value=\"{title}\" required>\n\
             </div>\n\
             <div class=\"field\">\n\
               <label for=\"fe-interval-{id_enc}\">Poll every (min)</label>\n\
               <input id=\"fe-interval-{id_enc}\" name=\"poll_interval\" type=\"number\" min=\"5\" max=\"10080\" value=\"{interval}\">\n\
             </div>\n\
             {feed_type_field}\
             <button type=\"submit\" class=\"btn btn-primary btn-small\">Save</button>\n\
           </div>\n\
         </form>\n\
         </div>\n\
         </details>\n",
        id_enc = esc(id_enc),
        csrf = esc(csrf),
        ret = esc(return_url),
        title = esc(title),
        interval = f.poll_interval,
    );
}

fn render_feed_row_controls(
    out: &mut String,
    f: &Feed,
    title: &str,
    id_enc: &str,
    return_url: &str,
    csrf: &str,
) {
    out.push_str("<div class=\"inbox-controls inbox-controls-subtle\">\n");
    if f.is_active {
        let refresh_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-icon\" title=\"Refresh {t}\" aria-label=\"Refresh {t}\">{REFRESH_SVG}</button>",
            t = esc(title),
        );
        render_inline_csrf_form_with_class(
            out,
            &format!("/web/feeds/{id_enc}/refresh"),
            csrf,
            Some("js-feeds-preserve-form"),
            &[("return_to", return_url)],
            None,
            &refresh_btn,
        );
    }
    let (next_active, toggle_label) =
        if f.is_active { ("false", "Pause") } else { ("true", "Resume") };
    let toggle_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\">{toggle_label}</button>"
    );
    render_inline_csrf_form_with_class(
        out,
        &format!("/web/feeds/{id_enc}/toggle-active"),
        csrf,
        Some("js-feeds-preserve-form"),
        &[("active", next_active), ("return_to", return_url)],
        None,
        &toggle_btn,
    );
    out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>");
    render_inline_csrf_form_with_class(
        out,
        &format!("/web/feeds/{id_enc}/delete"),
        csrf,
        Some("js-feeds-preserve-form"),
        &[("return_to", return_url)],
        Some("Delete this feed subscription? Your diary and rankings will be preserved."),
        "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-danger-quiet\">Delete</button>",
    );
    out.push_str("\n</div>\n");
}

fn render_feeds_rail(out: &mut String, stats: &FeedStats, csrf: &str) {
    let warn_cls = if stats.failing > 0 { " is-warn" } else { "" };
    let _ = write!(
        out,
        "<div class=\"feeds-rail\">\n\
           <section class=\"rail-block rail-card\">\n\
             <div class=\"section-head\"><h2>Subscribe</h2></div>\n\
             <form method=\"post\" action=\"/web/feeds/create\" class=\"rail-form\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <div class=\"field\">\n\
                 <label for=\"feed-url\">Site, Feed URL, or YouTube Channel</label>\n\
                 <input id=\"feed-url\" name=\"url\" type=\"text\" required placeholder=\"https://danluu.com or feed.xml\">\n\
               </div>\n\
               <div class=\"field\">\n\
                 <label for=\"feed-title\">Custom Title (optional)</label>\n\
                 <input id=\"feed-title\" name=\"title\" type=\"text\" placeholder=\"Auto-detected from feed\">\n\
               </div>\n\
               <div class=\"field\">\n\
                 <label for=\"feed-type\">Source Kind</label>\n\
                 <select id=\"feed-type\" name=\"feed_type\">\n\
                   <option value=\"rss\">Auto / RSS</option>\n\
                   <option value=\"podcast\">Podcast</option>\n\
                   <option value=\"youtube\">YouTube</option>\n\
                   <option value=\"arxiv\">arXiv</option>\n\
                 </select>\n\
               </div>\n\
               <button type=\"submit\" class=\"btn btn-primary btn-small\">Subscribe</button>\n\
             </form>\n\
           </section>\n\
           <section class=\"rail-block rail-card\">\n\
             <div class=\"section-head\"><h2>Feed Health</h2></div>\n\
             <div class=\"rail-stats\">\n\
               <button type=\"button\" class=\"rail-stat-btn js-feeds-stat-btn\" data-status=\"active\"><span class=\"rail-stat-num\">{active}</span><span class=\"rail-stat-label\">Active</span></button>\n\
               <button type=\"button\" class=\"rail-stat-btn js-feeds-stat-btn\" data-status=\"paused\"><span class=\"rail-stat-num\">{paused}</span><span class=\"rail-stat-label\">Paused</span></button>\n\
               <button type=\"button\" class=\"rail-stat-btn js-feeds-stat-btn\" data-status=\"errors\"><span class=\"rail-stat-num{warn_cls}\">{failing}</span><span class=\"rail-stat-label\">Failing</span></button>\n\
             </div>\n\
           </section>\n\
           <section class=\"rail-block rail-card\">\n\
             <div class=\"section-head\"><h2>OPML Backup</h2></div>\n\
             <div class=\"rail-opml-actions\">\n\
               <span class=\"text-soft rail-opml-note\">Portable subscription list</span>\n\
               <a class=\"btn btn-small btn-ghost\" href=\"/web/feeds/export.opml\" download=\"roche-feeds.opml\">Export OPML</a>\n\
             </div>\n\
             <details class=\"rail-details\">\n\
               <summary>Import OPML</summary>\n\
               <form method=\"post\" action=\"/web/feeds/opml\" class=\"rail-form\">\n\
                 <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
                 <div class=\"field\">\n\
                   <label for=\"opml-file\">Choose .opml / .xml file</label>\n\
                   <input id=\"opml-file\" type=\"file\" accept=\".opml,.xml,text/xml\" class=\"js-opml-file\">\n\
                 </div>\n\
                 <div class=\"field\">\n\
                   <label for=\"opml-xml\">Or paste XML</label>\n\
                   <textarea id=\"opml-xml\" name=\"opml_xml\" required class=\"textarea-compact js-opml-textarea\" placeholder=\"&lt;opml version=&quot;2.0&quot;&gt;…\"></textarea>\n\
                 </div>\n\
                 <button type=\"submit\" class=\"btn btn-small\">Import Subscriptions</button>\n\
               </form>\n\
             </details>\n\
           </section>\n\
         </div>\n",
        csrf = esc(csrf),
        active = stats.active,
        paused = stats.paused,
        failing = stats.failing,
    );
}

pub(in crate::web) async fn post_feed_create(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<CreateFeedForm>,
) -> Result<Response, WebError> {
    let source = match FeedSource::parse(&form.url) {
        Ok(s) => s,
        Err(e) => {
            return Ok(redirect_with_flash("/feeds", "err", &e.to_string()));
        }
    };
    let req_type = match form.feed_type.as_deref() {
        Some("podcast") => FeedType::Podcast,
        Some("youtube") => FeedType::Youtube,
        Some("arxiv") => FeedType::Arxiv,
        _ => FeedType::Rss,
    };
    let feed = service
        .feeds()
        .create(req_type, source, form.title.as_deref(), None)
        .await
        .web_err(&session)?;
    let service_bg = Arc::clone(&service);
    let feed_id = feed.id;
    service.spawn_background(async move {
        let _ = service_bg.feeds().refresh(&feed_id).await;
    });
    Ok(redirect_with_flash("/feeds", "notice", "Subscribed to feed (refreshing in background)"))
}

pub(in crate::web) async fn post_feed_opml(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ImportOpmlForm>,
) -> Result<Response, WebError> {
    let imported = service.feeds().import_opml(form.opml_xml).await.web_err(&session)?;
    let count = imported.len();
    if !imported.is_empty() {
        let service_bg = Arc::clone(&service);
        service.spawn_background(async move {
            service_bg.feeds().poll_feeds_concurrently(imported).await;
        });
    }
    Ok(redirect_with_flash("/feeds", "notice", &format!("Imported {count} feeds from OPML")))
}

pub(in crate::web) async fn export_opml_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
) -> Result<Response, WebError> {
    let xml = service.feeds().export_opml().await.web_err(&session)?;
    Ok((
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, "text/x-opml; charset=utf-8"),
            (header::CONTENT_DISPOSITION, "attachment; filename=\"roche-feeds.opml\""),
        ],
        xml,
    )
        .into_response())
}

pub(in crate::web) async fn post_feed_refresh(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    let _ = service.feeds().get(&id).await.web_err(&session)?;
    let service_bg = Arc::clone(&service);
    service.spawn_background(async move {
        let _ = service_bg.feeds().refresh(&id).await;
    });
    let dest = safe_local_redirect(form.return_to.as_deref(), "/feeds");
    Ok(redirect_with_flash(&dest, "notice", "Refreshing feed in background…"))
}

pub(in crate::web) async fn post_feed_edit(
    State(service): State<Arc<RocheService>>,
    Path(id): Path<String>,
    Form(form): Form<EditFeedForm>,
) -> Response {
    let dest = safe_local_redirect(form.return_to.as_deref(), "/feeds");
    let clean_title = form.title.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let parsed_interval =
        match form.poll_interval.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
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
    let parsed_feed_type = match form.feed_type.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        Some("rss") => Some(FeedType::Rss),
        Some("podcast") => Some(FeedType::Podcast),
        Some(_) => {
            return redirect_with_flash(&dest, "err", "Unsupported feed type change");
        }
        None => None,
    };
    if let Err(err) =
        service.feeds().update(&id, clean_title, parsed_interval, None, parsed_feed_type).await
    {
        return redirect_with_flash(&dest, "err", &safe_web_error_message(&err));
    }
    redirect_with_flash(&dest, "notice", "Updated feed settings")
}

pub(in crate::web) async fn post_feed_refresh_all(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    let base = safe_local_redirect(form.return_to.as_deref(), "/feeds");
    let count = service.feeds().refresh_all_in_background().await.web_err(&session)?;
    let msg = if count == 0 {
        "No active feeds to refresh".to_owned()
    } else {
        format!("Refreshing {count} feeds in background…")
    };
    Ok(redirect_with_flash(&base, "notice", &msg))
}

pub(in crate::web) async fn refresh_status_get(
    State(service): State<Arc<RocheService>>,
) -> Response {
    let active_count = service.active_refresh_count();
    let unread = service.sync().status().await.map_or(0, |s| s.unread_items_count);
    Json(serde_json::json!({
        "is_refreshing": active_count > 0,
        "active_refresh_count": active_count,
        "unread_items_count": unread,
    }))
    .into_response()
}

pub(in crate::web) async fn post_feed_toggle_active(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<FeedToggleForm>,
) -> Result<Response, WebError> {
    let want_active = parse_form_bool(Some(&form.active));
    if want_active {
        let _ = service.feeds().unarchive(&id).await.web_err(&session)?;
    } else {
        let _ = service.feeds().archive(&id).await.web_err(&session)?;
    }
    let dest = safe_local_redirect(form.return_to.as_deref(), "/feeds");
    Ok(redirect_see_other(&dest))
}

pub(in crate::web) async fn post_feed_delete(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    service.feeds().delete(&id).await.web_err(&session)?;
    let dest = safe_local_redirect(form.return_to.as_deref(), "/feeds");
    Ok(redirect_with_flash(
        &dest,
        "notice",
        "Removed feed subscription (diary & rankings preserved)",
    ))
}
