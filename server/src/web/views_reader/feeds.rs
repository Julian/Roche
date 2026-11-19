use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form, Json,
    extract::{Extension, Path, Query, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};

use super::{CreateFeedForm, FeedToggleForm, ImportOpmlForm, ItemReturnForm};
use crate::{
    db::Feed,
    domain::FeedType,
    feeds::FeedSource,
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _, esc, feed_type_label,
            parse_form_bool, redirect_see_other, redirect_with_flash, render_inline_csrf_form,
            render_page, safe_local_redirect, urlencode,
        },
    },
};

pub(in crate::web) async fn feeds_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Result<Response, WebError> {
    let feeds = service.feeds().list().await.web_err(&session)?;

    let mut body = String::with_capacity(6_144);
    body.push_str("<div class=\"page-head\"><h2>Feeds</h2><div class=\"flex-row flex-center\">");
    render_inline_csrf_form(
        &mut body,
        "/web/feeds/refresh-all",
        &session.csrf_token,
        &[("return_to", "/feeds")],
        None,
        "<button type=\"submit\" class=\"btn btn-small\">&#8635; Refresh All</button>",
    );
    let _ = writeln!(
        body,
        "<a class=\"btn btn-small btn-ghost\" href=\"/web/feeds/export.opml\" download=\"roche-feeds.opml\">Export OPML</a>\
           </div>\
         </div>\n\
         <p class=\"lede\">{} subscribed sources (RSS/Atom, Podcasts, YouTube channels, arXiv). Paste a site homepage or YouTube channel to autodiscover its feed.</p>",
        feeds.len(),
    );

    let _ = write!(
        body,
        "<details class=\"editorial-panel\" open data-default-open>\n\
           <summary>+ Subscribe to Feed</summary>\n\
           <form method=\"post\" action=\"/web/feeds/create\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"form-grid\">\n\
               <div class=\"field\"><label for=\"feed-url\">Site Homepage, Feed URL, or YouTube Channel</label>\
                 <input id=\"feed-url\" name=\"url\" type=\"text\" required placeholder=\"https://danluu.com, https://example.com/feed.xml, or UC...\"></div>\n\
               <div class=\"field\"><label for=\"feed-title\">Custom Title (optional)</label>\
                 <input id=\"feed-title\" name=\"title\" type=\"text\" placeholder=\"Leave blank to use feed title\"></div>\n\
               <div class=\"field\"><label for=\"feed-type\">Source Kind</label>\
                 <select id=\"feed-type\" name=\"feed_type\">\
                   <option value=\"rss\">Auto / RSS</option>\
                   <option value=\"podcast\">Podcast</option>\
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
             <div class=\"field mb-sm\">\n\
               <label for=\"opml-file\">Choose .opml / .xml file (or paste XML below)</label>\n\
               <input id=\"opml-file\" type=\"file\" accept=\".opml,.xml,text/xml\" class=\"js-opml-file\">\n\
             </div>\n\
             <div class=\"field mb-sm\">\n\
               <label for=\"opml-xml\">OPML XML</label>\n\
               <textarea id=\"opml-xml\" name=\"opml_xml\" required class=\"js-opml-textarea\" placeholder=\"&lt;opml version=&quot;2.0&quot;&gt;...\"></textarea>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Import Subscriptions</button>\n\
           </form>\n\
         </details>\n",
        csrf = esc(&session.csrf_token),
    );

    render_feeds_table(&mut body, &feeds, &session.csrf_token);

    Ok(render_page(&PageShell {
        title: "Feeds",
        active: NavSection::Feeds,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response())
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
                   <span class=\"badge\">{ftype}</span>\n\
                   <span>{unread} unread</span>",
            id = esc(&urlencode(&f.id)),
            title = esc(title),
            ftype = esc(feed_type_label(f.feed_type)),
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

        let id_enc = urlencode(&f.id);
        render_inline_csrf_form(
            out,
            &format!("/web/feeds/{id_enc}/refresh"),
            csrf,
            &[],
            None,
            "<button type=\"submit\" class=\"btn btn-small\">Refresh</button>",
        );

        let (next_active, toggle_label) =
            if f.is_active { ("false", "Pause") } else { ("true", "Resume") };
        let toggle_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">{toggle_label}</button>"
        );
        render_inline_csrf_form(
            out,
            &format!("/web/feeds/{id_enc}/toggle-active"),
            csrf,
            &[("active", next_active)],
            None,
            &toggle_btn,
        );
        render_inline_csrf_form(
            out,
            &format!("/web/feeds/{id_enc}/delete"),
            csrf,
            &[],
            Some("Delete this feed subscription? Your diary and rankings will be preserved."),
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">Delete</button>",
        );
        out.push_str("\n</div>\n</li>\n");
    }
    out.push_str("</ul>\n");
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
    tokio::spawn(async move {
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
        tokio::spawn(async move {
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
) -> Result<Response, WebError> {
    let _ = service.feeds().get(&id).await.web_err(&session)?;
    let service_bg = Arc::clone(&service);
    tokio::spawn(async move {
        let _ = service_bg.feeds().refresh(&id).await;
    });
    Ok(redirect_with_flash("/feeds", "notice", "Refreshing feed in background…"))
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
    Ok(redirect_see_other("/feeds"))
}

pub(in crate::web) async fn post_feed_delete(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
) -> Result<Response, WebError> {
    service.feeds().delete(&id).await.web_err(&session)?;
    Ok(redirect_with_flash(
        "/feeds",
        "notice",
        "Removed feed subscription (diary & rankings preserved)",
    ))
}
