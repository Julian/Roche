use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form, Json,
    extract::{Extension, Path, Query, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
};

use super::{CreateFeedForm, FeedToggleForm, ImportOpmlForm, ItemReturnForm};
use crate::{
    db::{Feed, FeedType},
    feeds::FeedSource,
    scheduler::FeedFetcher,
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, feed_type_label, redirect_see_other,
            redirect_with_flash, render_inline_csrf_form, render_page, safe_local_redirect,
            urlencode, web_api_error_response,
        },
    },
};

pub(in crate::web) async fn feeds_get<F: FeedFetcher>(
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
        "<div class=\"page-head\">\
           <h2>Feeds</h2>\
           <div class=\"flex-row flex-center\">\
             <form method=\"post\" action=\"/web/feeds/refresh-all\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <input type=\"hidden\" name=\"return_to\" value=\"/feeds\">\
               <button type=\"submit\" class=\"btn btn-small\">&#8635; Refresh All</button>\
             </form>\
             <a class=\"btn btn-small btn-ghost\" href=\"/web/feeds/export.opml\" download=\"roche-feeds.opml\">Export OPML</a>\
           </div>\
         </div>\n\
         <p class=\"lede\">{} subscribed sources (RSS/Atom, Podcasts, YouTube channels, arXiv). Paste a site homepage or YouTube channel to autodiscover its feed.</p>",
        feeds.len(),
        csrf = esc(&session.csrf_token),
    );

    let _ = write!(
        body,
        "<details class=\"editorial-panel\" open>\n\
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

    render_page(&PageShell {
        title: "Feeds",
        active: NavSection::Feeds,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
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

pub(in crate::web) async fn post_feed_create<F: FeedFetcher>(
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
        Some("podcast") => FeedType::Podcast,
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

pub(in crate::web) async fn post_feed_opml<F: FeedFetcher>(
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

pub(in crate::web) async fn export_opml_get<F: FeedFetcher>(
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

pub(in crate::web) async fn post_feed_refresh<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
) -> Response {
    if let Err(err) = service.feeds().get(&id).await {
        return web_api_error_response(&err, Some(&session.csrf_token));
    }
    let service_bg = Arc::clone(&service);
    tokio::spawn(async move {
        let _ = service_bg.feeds().refresh(&id).await;
    });
    redirect_see_other(&format!("/feeds?notice={}", urlencode("Refreshing feed in background…")))
}

pub(in crate::web) async fn post_feed_refresh_all<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<ItemReturnForm>,
) -> Response {
    let base = safe_local_redirect(form.return_to.as_deref(), "/feeds");
    match service.feeds().refresh_all_in_background().await {
        Ok(count) => {
            let msg = if count == 0 {
                "No active feeds to refresh".to_owned()
            } else {
                format!("Refreshing {count} feeds in background…")
            };
            redirect_with_flash(&base, "notice", &msg)
        }
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn refresh_status_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
) -> Response {
    let active_count = service.active_refresh_count();
    let unread = service.sync_status().await.map_or(0, |s| s.unread_items_count);
    Json(serde_json::json!({
        "is_refreshing": active_count > 0,
        "active_refresh_count": active_count,
        "unread_items_count": unread,
    }))
    .into_response()
}

pub(in crate::web) async fn post_feed_toggle_active<F: FeedFetcher>(
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

pub(in crate::web) async fn post_feed_delete<F: FeedFetcher>(
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
