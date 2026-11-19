use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};

use super::{AddTagForm, FinishItemForm, ItemReturnForm, ProgressForm, render_audio_play_button};
use crate::{
    content::fetcher::HtmlSanitizer,
    db::{
        ConsumptionStatus, EntryKind, ItemStatus, MediaType, ReadAtPatch, SyncRepo,
        WANT_TO_READ_COLLECTION_ID,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::{ItemDetail, RocheService},
    web::{
        auth::WebSession,
        html::{
            ARCHIVE_BOX_SVG, FlashQuery, NavSection, PageShell, esc, redirect_see_other,
            redirect_with_flash, render_feed_favicon, render_inline_csrf_form,
            render_inline_csrf_form_with_class, render_page, safe_local_redirect, urlencode,
            web_api_error_response,
        },
        links::sanitize_non_local_href,
    },
};

const QUEUE_ADD_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M3.5 2.5h9v11l-4.5-3-4.5 3v-11Z\"/><path d=\"M8 5.2v3.6M6.2 7h3.6\"/></svg>";
const QUEUE_CHECK_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M3.5 2.5h9v11l-4.5-3-4.5 3v-11Z\"/><path d=\"m6 6.8 1.5 1.5 2.6-2.6\"/></svg>";
const TOP_ARROW_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M8 13V3.5\"/><path d=\"M4 7.5 8 3.5l4 4\"/></svg>";

pub(in crate::web) async fn reader_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let detail = match service.items().get(&id).await {
        Ok(d) => d,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };

    let (is_consumed, rank_slug) = match service.catalog().get_entry_detail(&id).await {
        Ok(Some(ed)) => {
            let consumed = ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed);
            let slug = if consumed {
                service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
            } else {
                None
            };
            (consumed, slug)
        }
        _ => (false, None),
    };

    let active_nav = if detail.item.in_want_to_read {
        NavSection::Queue
    } else if detail.item.feed_id.is_none() && detail.item.is_favorite {
        NavSection::Starred
    } else {
        NavSection::Inbox
    };
    let is_finished =
        is_consumed || detail.item.read_at.is_some() || detail.item.read_progress >= 0.98;
    let suppress_auto_finish =
        flash.notice.as_deref().is_some_and(|n| n.eq_ignore_ascii_case("Marked unfinished"));

    let mut body = String::with_capacity(14_336);
    render_reader_article(
        &mut body,
        &detail,
        is_finished,
        suppress_auto_finish,
        rank_slug.as_deref(),
        &session.csrf_token,
    );

    render_page(&PageShell {
        title: &detail.item.title,
        active: active_nav,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
}

fn estimate_reading_minutes(detail: &ItemDetail) -> Option<usize> {
    let src = detail
        .item
        .content_html
        .as_deref()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| detail.item.summary.as_deref().filter(|s| !s.trim().is_empty()))?;
    let mut words = 0usize;
    let mut in_tag = false;
    let mut in_word = false;
    for ch in src.chars() {
        match ch {
            '<' => {
                in_tag = true;
                in_word = false;
            }
            '>' => in_tag = false,
            c if !in_tag => {
                if c.is_whitespace() {
                    in_word = false;
                } else if !in_word {
                    words += 1;
                    in_word = true;
                }
            }
            _ => {}
        }
    }
    if words == 0 { None } else { Some((words / 230).max(1)) }
}

fn render_reader_article(
    out: &mut String,
    detail: &ItemDetail,
    is_finished: bool,
    suppress_auto_finish: bool,
    rank_slug: Option<&str>,
    csrf: &str,
) {
    let item = &detail.item;
    let entry_kind = EntryKind::from_content_type(item.content_type);
    let id_enc = urlencode(&item.id);
    let reader_path = format!("/reader/{id_enc}");
    let suppress_attr = if suppress_auto_finish { " data-suppress-auto-finish=\"1\"" } else { "" };

    let _ = writeln!(
        out,
        "<div class=\"reader-layout\">\n\
         <article class=\"reader-article js-reader-root\" data-item-id=\"{}\" data-kind=\"{}\" data-progress=\"{:.4}\" data-finished=\"{}\"{suppress_attr}>",
        esc(&item.id),
        entry_kind.as_str(),
        item.read_progress.clamp(0.0, 1.0),
        if is_finished { "1" } else { "0" },
    );

    render_reader_header(out, detail, &id_enc, &reader_path, csrf);
    render_reader_media_and_prose(out, detail);
    render_reader_end_card(out, detail, &id_enc, &reader_path, is_finished, csrf);
    render_reader_tags(out, detail, &id_enc, csrf);
    out.push_str("</article>\n");
    render_reader_rail(out, detail, &id_enc, &reader_path, rank_slug, csrf);
    out.push_str("</div>\n");
}

fn render_reader_crumb_row(out: &mut String, detail: &ItemDetail) {
    let item = &detail.item;
    let (back_href, back_label) = if item.in_want_to_read {
        ("/queue", "Back to Queue")
    } else {
        ("/inbox", "Back to Inbox")
    };
    let _ = write!(
        out,
        "<div class=\"reader-crumb-row\">\n\
           <a class=\"reader-back-link\" href=\"{back_href}\">&larr; {back_label}</a>\n\
           <span>",
    );
    let read_mins = estimate_reading_minutes(detail);
    if let Some(mins) = read_mins {
        let _ = write!(out, "{mins} min read");
    }
    if let Some(ext_href) = sanitize_non_local_href(item.url.as_str()) {
        if read_mins.is_some() {
            out.push_str(" &middot; ");
        }
        let _ = write!(
            out,
            "<a href=\"{}\" target=\"_blank\" rel=\"noopener\">Original &#8599;</a>",
            esc(ext_href)
        );
    }
    out.push_str("</span>\n</div>\n");
}

fn render_reader_header(
    out: &mut String,
    detail: &ItemDetail,
    id_enc: &str,
    reader_path: &str,
    csrf: &str,
) {
    let item = &detail.item;
    render_reader_crumb_row(out, detail);
    out.push_str("<div class=\"reader-header\">\n<div class=\"reader-title-row\">\n");

    let (star_glyph, star_pressed, star_cls) = if item.is_favorite {
        ("&#9733;", "true", "btn btn-ghost reader-star-btn is-starred")
    } else {
        ("&#9734;", "false", "btn btn-ghost reader-star-btn")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Toggle Star\" aria-label=\"Star {}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        esc(&item.title),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/items/{id_enc}/star"),
        csrf,
        &[("return_to", reader_path)],
        None,
        &star_btn,
    );
    let _ = writeln!(
        out,
        "<h2>{}</h2>\n</div>\n<div class=\"reader-meta-row\">\n<div class=\"item-byline\">",
        esc(&item.title),
    );
    if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
        let icon_url = item.feed_url.as_deref().or(Some(item.url.as_str()));
        if let Some(fid) = item.feed_id.as_deref() {
            let _ = write!(
                out,
                "<a class=\"byline-item byline-feed\" href=\"/inbox?status=all&amp;feed={}\">",
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
    if let Some(author) = item.author.as_deref().filter(|s| !s.is_empty()) {
        let _ = write!(out, "<span class=\"byline-item\">by {}</span>", esc(author));
    }
    if let Some(dt) = item.published_at {
        let _ = write!(out, "<span class=\"byline-item\">{}</span>", dt.format("%B %d, %Y"));
    }
    out.push_str("</div>\n<div class=\"reader-quick-tools\">\n");
    render_audio_play_button(out, item);
    if item.in_want_to_read {
        render_inline_csrf_form(
            out,
            &format!("/web/want/read/remove/{id_enc}"),
            csrf,
            &[],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">In Queue &times;</button>",
        );
    } else {
        render_inline_csrf_form(
            out,
            &format!("/web/entries/{id_enc}/list-add"),
            csrf,
            &[("list_slug_or_id", WANT_TO_READ_COLLECTION_ID), ("return_to", reader_path)],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\">+ Queue</button>",
        );
    }
    out.push_str("</div>\n</div>\n</div>\n");
}

fn render_reader_media_and_prose(out: &mut String, detail: &ItemDetail) {
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
        let cleaned = HtmlSanitizer::clean_with_base(raw_html, Some(&item.url));
        out.push_str(&harden_reader_prose_html(&cleaned));
    } else if let Some(summary) = item.summary.as_deref().filter(|s| !s.trim().is_empty()) {
        for para in summary.split("\n\n").map(str::trim).filter(|p| !p.is_empty()) {
            let _ = writeln!(out, "<p>{}</p>", esc(para));
        }
    } else {
        out.push_str("<p class=\"empty\">No extracted article text stored for this entry.</p>\n");
    }
    out.push_str("</div>\n");
}

fn render_reader_end_card(
    out: &mut String,
    _detail: &ItemDetail,
    id_enc: &str,
    reader_path: &str,
    is_finished: bool,
    csrf: &str,
) {
    let finish_action = format!("/web/items/{id_enc}/finish");
    let unfinish_action = format!("/web/items/{id_enc}/unfinish");
    out.push_str("<div class=\"reader-end-card\">\n<div class=\"reader-end-center\">\n");
    render_inline_csrf_form(
        out,
        &finish_action,
        csrf,
        &[("rank_after", "1")],
        None,
        "<button type=\"submit\" class=\"btn btn-primary reader-end-rank-btn\">Rank</button>",
    );
    out.push_str("\n</div>\n<div class=\"reader-end-right\">\n");
    let (toggle_action, btn_cls, pressed, label, title) = if is_finished {
        (
            unfinish_action.as_str(),
            "btn btn-small btn-ghost btn-finished is-done js-finish-toggle-btn",
            "true",
            "&#10003; Finished",
            "Marked finished — click to unfinish",
        )
    } else {
        (
            finish_action.as_str(),
            "btn btn-small btn-ghost btn-finished js-finish-toggle-btn",
            "false",
            "Mark Finished",
            "Mark this item as finished",
        )
    };
    let toggle_btn = format!(
        "<button type=\"submit\" class=\"{btn_cls}\" aria-pressed=\"{pressed}\" title=\"{title}\">{label}</button>"
    );
    let _ = writeln!(
        out,
        "<form method=\"post\" action=\"{}\" class=\"inline-form js-finish-toggle-form\" data-finish-action=\"{}\" data-unfinish-action=\"{}\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
           <input type=\"hidden\" name=\"return_to\" value=\"{}\">\
           {toggle_btn}\
         </form>",
        esc(toggle_action),
        esc(&finish_action),
        esc(&unfinish_action),
        esc(csrf),
        esc(reader_path),
    );
    out.push_str("</div>\n</div>\n");
}

fn render_reader_tags(out: &mut String, detail: &ItemDetail, id_enc: &str, csrf: &str) {
    out.push_str("<h3>Tags</h3>\n<div class=\"pill-bar\">\n");
    for tag in &detail.tags {
        let _ = write!(out, "<span class=\"pill\">#{} ", esc(&tag.name));
        let remove_btn = format!(
            "<button type=\"submit\" class=\"link-btn ml-xs\" aria-label=\"Remove tag {}\" title=\"Remove tag\"><span aria-hidden=\"true\">&times;</span></button>",
            esc(&tag.name),
        );
        render_inline_csrf_form(
            out,
            &format!("/web/items/{id_enc}/tags/{}/remove", urlencode(&tag.id)),
            csrf,
            &[],
            None,
            &remove_btn,
        );
        out.push_str("</span>\n");
    }
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/{}/tags\" class=\"tag-add-form\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
           <input type=\"text\" name=\"name\" required aria-label=\"Add tag\" placeholder=\"Add tag…\" class=\"tag-add-input\">\
           <button type=\"submit\" class=\"btn btn-small\">+ Tag</button>\
         </form>\n</div>\n",
        esc(id_enc),
        esc(csrf),
    );
}

fn render_reader_rail(
    out: &mut String,
    detail: &ItemDetail,
    id_enc: &str,
    reader_path: &str,
    rank_slug: Option<&str>,
    csrf: &str,
) {
    let item = &detail.item;
    out.push_str(
        "<div class=\"reader-rail\">\n\
           <div class=\"reader-rail-group\" id=\"roche-reader-toc-group\" hidden>\n\
             <div class=\"reader-rail-title\">Contents</div>\n\
             <ul class=\"reader-toc\" id=\"roche-reader-toc\"></ul>\n\
           </div>\n\
           <div class=\"reader-rail-group\">\n\
             <div class=\"reader-rail-title\">Typography</div>\n\
             <div class=\"reader-size-controls\">\n\
               <button type=\"button\" class=\"btn btn-small btn-ghost js-font-dec\" aria-label=\"Decrease text size\">A&minus;</button>\n\
               <button type=\"button\" class=\"btn btn-small btn-ghost js-font-inc\" aria-label=\"Increase text size\">A+</button>\n\
             </div>\n\
           </div>\n\
           <div class=\"reader-rail-group\">\n\
             <div class=\"reader-rail-title\">Actions</div>\n\
             <div class=\"reader-rail-actions\">\n",
    );

    let (star_glyph, star_pressed, star_cls) = if item.is_favorite {
        ("&#9733;", "true", "rail-icon-btn js-star-toggle is-starred")
    } else {
        ("&#9734;", "false", "rail-icon-btn js-star-toggle")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Star\" aria-label=\"Star {}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        esc(&item.title),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/items/{id_enc}/star"),
        csrf,
        &[("return_to", reader_path)],
        None,
        &star_btn,
    );

    if item.in_want_to_read {
        let q_btn = format!(
            "<button type=\"submit\" class=\"rail-icon-btn\" title=\"Remove from Queue\" aria-label=\"Remove {} from Queue\">{QUEUE_CHECK_SVG}</button>",
            esc(&item.title),
        );
        render_inline_csrf_form(
            out,
            &format!("/web/want/read/remove/{id_enc}"),
            csrf,
            &[],
            None,
            &q_btn,
        );
    } else {
        let q_btn = format!(
            "<button type=\"submit\" class=\"rail-icon-btn\" title=\"Add to Queue\" aria-label=\"Add {} to Queue\">{QUEUE_ADD_SVG}</button>",
            esc(&item.title),
        );
        render_inline_csrf_form(
            out,
            &format!("/web/entries/{id_enc}/list-add"),
            csrf,
            &[("list_slug_or_id", WANT_TO_READ_COLLECTION_ID), ("return_to", reader_path)],
            None,
            &q_btn,
        );
    }

    out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>\n");
    render_reader_rail_archive(out, item, id_enc, reader_path, csrf);

    let _ = writeln!(
        out,
        "<a class=\"rail-icon-btn\" href=\"#roche-masthead\" title=\"Back to top\" aria-label=\"Back to top\">{TOP_ARROW_SVG}</a>\n\
         </div>\n\
         <a class=\"reader-rail-link\" href=\"/entries/{}\">History &amp; Lists &rarr;</a>",
        esc(id_enc),
    );
    if let Some(slug) = rank_slug {
        let _ = writeln!(
            out,
            "<br><a class=\"reader-rail-link\" href=\"/rank/{slug}?focus={id}\">Compare &rarr;</a>",
            slug = esc(&urlencode(slug)),
            id = esc(&urlencode(&item.id)),
        );
    }
    out.push_str("</div>\n</div>\n");
}

fn render_reader_rail_archive(
    out: &mut String,
    item: &crate::db::Item,
    id_enc: &str,
    reader_path: &str,
    csrf: &str,
) {
    let (arch_action, arch_title, arch_return) = if item.feed_id.is_some() {
        if item.status == ItemStatus::Unread {
            ("archive", "Archive (mark read)", "/inbox")
        } else {
            ("unarchive", "Move back to Inbox", "/inbox")
        }
    } else if item.in_want_to_read {
        ("archive", "Remove from Queue", "/queue")
    } else {
        ("unarchive", "Add to Queue", reader_path)
    };
    let prog_str = format!("{:.4}", item.read_progress.clamp(0.0, 1.0));
    let arch_btn = format!(
        "<button type=\"submit\" class=\"rail-icon-btn\" title=\"{arch_title}\" aria-label=\"{arch_title}\">{ARCHIVE_BOX_SVG}</button>"
    );
    render_inline_csrf_form_with_class(
        out,
        &format!("/web/items/{id_enc}/{arch_action}"),
        csrf,
        Some("js-reader-archive-form"),
        &[("progress", &prog_str), ("return_to", arch_return)],
        None,
        &arch_btn,
    );
}

pub(in crate::web) async fn item_audio_get<F: FeedFetcher>(
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
    if let Some(ext_audio) = detail.item.audio_url.as_deref().and_then(sanitize_non_local_href) {
        let mut resp = StatusCode::SEE_OTHER.into_response();
        if let Ok(val) = header::HeaderValue::from_str(ext_audio) {
            resp.headers_mut().insert(header::LOCATION, val);
            resp.headers_mut()
                .insert(header::REFERRER_POLICY, header::HeaderValue::from_static("no-referrer"));
            return resp;
        }
    }
    web_api_error_response(
        &ApiError::not_found("Audio stream not found for this item"),
        Some(&session.csrf_token),
    )
}

pub(in crate::web) async fn web_media_get<F: FeedFetcher>(
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

pub(in crate::web) async fn post_item_progress<F: FeedFetcher>(
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

pub(in crate::web) async fn post_item_finish<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<FinishItemForm>,
) -> Response {
    let existing = match service.items().get(&id).await {
        Ok(d) => d,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };
    let default_return = if existing.item.feed_id.is_some() { "/inbox" } else { "/queue" };
    let logged = match service
        .catalog()
        .log_consumption(&id, ConsumptionStatus::Consumed, form.notes.as_deref(), None)
        .await
    {
        Ok(l) => l,
        Err(err) => return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    };
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

    if form.rank_after.as_deref() == Some("1")
        && let Ok(Some(list_slug)) =
            service.catalog().rank_target_after_consumption(&logged.entry).await
    {
        redirect_with_flash(
            &format!("/rank/{}?focus={}", urlencode(&list_slug), urlencode(&logged.entry.id)),
            "notice",
            "Marked finished & logged to Diary",
        )
    } else {
        let base = safe_local_redirect(form.return_to.as_deref(), default_return);
        redirect_with_flash(&base, "notice", "Marked finished & logged to Diary")
    }
}

pub(in crate::web) async fn post_item_unfinish<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Response {
    let existing = match service.items().get(&id).await {
        Ok(d) => d,
        Err(err) => return web_api_error_response(&err, Some(&session.csrf_token)),
    };
    if let Err(err) = service.catalog().delete_consumed_logs_for_entry(&id).await {
        return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
    }
    if let Err(err) = service
        .items()
        .update(&id, Some(ItemStatus::Unread), None, Some(0.0), ReadAtPatch::MarkUnread)
        .await
    {
        return web_api_error_response(&err, Some(&session.csrf_token));
    }
    if existing.item.feed_id.is_none() {
        let _ = service.catalog().add_collection_entry(WANT_TO_READ_COLLECTION_ID, &id, None).await;
    }
    let fallback = format!("/reader/{}", urlencode(&id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    redirect_with_flash(&dest, "notice", "Marked unfinished")
}

pub(in crate::web) async fn post_item_tag_add<F: FeedFetcher>(
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

pub(in crate::web) async fn post_item_tag_remove<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path((id, tag_id)): Path<(String, String)>,
) -> Response {
    match service.items().remove_tag(&id, &tag_id).await {
        Ok(()) => redirect_see_other(&format!("/reader/{}", urlencode(&id))),
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

/// Normalizes already-sanitized article HTML for embedding under the Reader's `<h2>` title:
/// - Demotes embedded `<h1>`..`<h4>` headings by 2 levels (capped at `<h6>`) so the page retains
///   a single `<h1>` and a valid heading hierarchy.
/// - Ensures `<pre>` blocks carry `tabindex="0"` for keyboard scrolling.
/// - Ensures `<img>` tags without an `alt` attribute default to `alt=""`.
fn harden_reader_prose_html(clean_html: &str) -> String {
    let mut out = String::with_capacity(clean_html.len() + 64);
    let mut rest = clean_html;
    while let Some(lt) = rest.find('<') {
        out.push_str(&rest[..lt]);
        let after_lt = &rest[lt + 1..];
        let Some(gt) = after_lt.find('>') else {
            out.push('<');
            out.push_str(after_lt);
            return out;
        };
        let raw_tag = &after_lt[..gt];
        rest = &after_lt[gt + 1..];

        let (is_closing, tag_body) =
            raw_tag.strip_prefix('/').map_or((false, raw_tag), |stripped| (true, stripped));
        let name_end =
            tag_body.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(tag_body.len());
        let tag_name = &tag_body[..name_end];
        let attrs = &tag_body[name_end..];

        let demoted_heading = match tag_name.to_ascii_lowercase().as_str() {
            "h1" => Some("h3"),
            "h2" => Some("h4"),
            "h3" => Some("h5"),
            "h4" | "h5" | "h6" => Some("h6"),
            _ => None,
        };

        out.push('<');
        if is_closing {
            out.push('/');
            out.push_str(demoted_heading.unwrap_or(tag_name));
            out.push_str(attrs);
        } else if let Some(new_tag) = demoted_heading {
            out.push_str(new_tag);
            out.push_str(attrs);
        } else if tag_name.eq_ignore_ascii_case("pre") {
            out.push_str(tag_name);
            out.push_str(attrs);
            if !attrs.to_ascii_lowercase().contains("tabindex=") {
                out.push_str(" tabindex=\"0\"");
            }
        } else if tag_name.eq_ignore_ascii_case("img") {
            out.push_str(tag_name);
            let has_alt = attrs.to_ascii_lowercase().contains(" alt=")
                || attrs.to_ascii_lowercase().starts_with("alt=");
            if let Some(before_slash) = attrs.strip_suffix('/') {
                out.push_str(before_slash);
                if !has_alt {
                    out.push_str(" alt=\"\"");
                }
                out.push('/');
            } else {
                out.push_str(attrs);
                if !has_alt {
                    out.push_str(" alt=\"\"");
                }
            }
        } else {
            out.push_str(tag_body);
        }
        out.push('>');
    }
    out.push_str(rest);
    out
}
