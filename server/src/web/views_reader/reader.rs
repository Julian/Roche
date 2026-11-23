use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use url::Url;

use super::{
    AddTagForm, FinishItemForm, ItemReturnForm, ProgressForm, ReaderQuery, render_audio_play_button,
};
use crate::{
    content::{HtmlSanitizer, semantic_html},
    db::WANT_TO_READ_COLLECTION_ID,
    domain::{
        ConsumptionStatus, ContentType, EntryKind, ItemStatus, MediaType, ProgressSource,
        READER_AUTO_READ_THRESHOLD,
    },
    service::{AppError, ItemDetail, RocheService},
    web::{
        auth::WebSession,
        html::{
            ARCHIVE_BOX_SVG, NavSection, PageShell, RESTORE_BOX_SVG, WebError, WithWebCsrf as _,
            esc, format_audio_duration, parse_form_bool, redirect_see_other, redirect_with_flash,
            render_feed_favicon, render_inline_csrf_form, render_inline_csrf_form_with_class,
            render_page, safe_local_redirect, urlencode,
        },
        links::sanitize_non_local_href,
    },
};

const QUEUE_ADD_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M3.5 2.5h9v11l-4.5-3-4.5 3v-11Z\"/><path d=\"M8 5.2v3.6M6.2 7h3.6\"/></svg>";
const QUEUE_CHECK_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M3.5 2.5h9v11l-4.5-3-4.5 3v-11Z\"/><path d=\"m6 6.8 1.5 1.5 2.6-2.6\"/></svg>";
const TOP_ARROW_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M8 13V3.5\"/><path d=\"M4 7.5 8 3.5l4 4\"/></svg>";

pub(in crate::web) async fn reader_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(query): Query<ReaderQuery>,
) -> Result<Response, WebError> {
    let detail = service.items().get(&id).await.web_err(&session)?;

    let (is_consumed, rank_slug, can_rank) = match service.catalog().get_entry_detail(&id).await {
        Ok(Some(ed)) => {
            let consumed = ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed);
            let slug = if consumed {
                service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
            } else {
                None
            };
            let rankable = slug.is_some()
                || ed.entry.kind.default_list_slug().is_some()
                || ed.parents.iter().any(|p| p.kind == EntryKind::Collection);
            (consumed, slug, rankable)
        }
        _ => (false, None, false),
    };

    let default_back = if detail.item.in_want_to_read { "/queue" } else { "/inbox" };
    let return_to = safe_local_redirect(query.return_to.as_deref(), default_back);

    let active_nav = if return_to.starts_with("/queue") || detail.item.in_want_to_read {
        NavSection::Queue
    } else if detail.item.feed_id.is_none() && detail.item.is_favorite {
        NavSection::Starred
    } else {
        NavSection::Inbox
    };
    let is_finished = is_consumed
        || detail.item.read_at.is_some()
        || detail.item.read_progress >= READER_AUTO_READ_THRESHOLD;
    let suppress_auto_finish = session
        .flash
        .notice
        .as_deref()
        .is_some_and(|n| n.eq_ignore_ascii_case("Marked unfinished"));

    let mut body = String::with_capacity(14_336);
    render_reader_article(
        &mut body,
        &detail,
        is_finished,
        suppress_auto_finish,
        can_rank,
        rank_slug.as_deref(),
        &return_to,
        &session.csrf_token,
    );

    Ok(render_page(&PageShell {
        title: &detail.item.title,
        active: active_nav,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
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

fn back_label_for_return_to(return_to: &str) -> &'static str {
    if return_to == "/" {
        "Back to Home"
    } else if return_to.starts_with("/queue") {
        "Back to Queue"
    } else if return_to.starts_with("/search") {
        "Back to Search"
    } else if return_to.starts_with("/lists/starred") || return_to.starts_with("/starred") {
        "Back to Starred"
    } else if return_to.starts_with("/lists") {
        "Back to List"
    } else {
        "Back to Inbox"
    }
}

#[allow(clippy::too_many_arguments)]
fn render_reader_article(
    out: &mut String,
    detail: &ItemDetail,
    is_finished: bool,
    suppress_auto_finish: bool,
    can_rank: bool,
    rank_slug: Option<&str>,
    return_to: &str,
    csrf: &str,
) {
    let item = &detail.item;
    let entry_kind = item.entry_kind;
    let id_enc = urlencode(&item.id);
    let reader_path = if return_to == "/inbox" || return_to == "/queue" {
        format!("/reader/{id_enc}")
    } else {
        format!("/reader/{id_enc}?return_to={}", urlencode(return_to))
    };
    let suppress_attr = if suppress_auto_finish { " data-suppress-auto-finish=\"1\"" } else { "" };

    let _ = writeln!(
        out,
        "<div class=\"reader-layout\">\n\
         <article class=\"reader-article js-reader-root\" data-item-id=\"{}\" data-kind=\"{}\" data-content-type=\"{}\" data-has-audio=\"{}\" data-progress=\"{:.4}\" data-finished=\"{}\" data-back-href=\"{}\"{suppress_attr}>",
        esc(&item.id),
        entry_kind.as_str(),
        item.content_type.as_str(),
        if item.has_audio() { "1" } else { "0" },
        item.read_progress.clamp(0.0, 1.0),
        if is_finished { "1" } else { "0" },
        esc(return_to),
    );

    render_reader_header(out, detail, &id_enc, &reader_path, return_to, rank_slug, csrf);
    render_reader_media_and_prose(out, detail);
    render_reader_end_card(out, detail, &id_enc, &reader_path, is_finished, can_rank, csrf);
    render_reader_tags(out, detail, &id_enc, csrf);
    out.push_str("</article>\n");
    render_reader_rail(out, detail, &id_enc, &reader_path, return_to, rank_slug, csrf);
    out.push_str("</div>\n");
}

fn render_reader_crumb_row(out: &mut String, detail: &ItemDetail, return_to: &str) {
    let item = &detail.item;
    let back_label = back_label_for_return_to(return_to);
    let _ = write!(
        out,
        "<div class=\"reader-crumb-row\">\n\
           <a class=\"reader-back-link js-reader-back-link\" href=\"{}\">&larr; {back_label}</a>\n\
           <span>",
        esc(return_to),
    );
    let mut wrote_meta = false;
    if item.has_audio()
        && let Some(dur) = item.duration.filter(|&d| d > 0)
    {
        let _ = write!(out, "{} listen", esc(&format_audio_duration(dur)));
        wrote_meta = true;
        if item.content_type == ContentType::Audio
            && item.read_progress >= 0.03
            && item.read_progress < 0.98
        {
            #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
            let pct = (item.read_progress * 100.0).round().clamp(1.0, 99.0) as u32;
            let _ = write!(out, " &middot; {pct}% listened");
        }
    }
    if item.content_type != ContentType::Audio
        && let Some(mins) = estimate_reading_minutes(detail)
    {
        if wrote_meta {
            out.push_str(" &middot; ");
        }
        let _ = write!(out, "{mins} min read");
        wrote_meta = true;
    }
    if matches!(item.content_type, ContentType::Article | ContentType::Paper)
        && item.read_progress >= 0.05
        && item.read_progress < 0.95
    {
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let pct = (item.read_progress * 100.0).round().clamp(1.0, 99.0) as u32;
        if wrote_meta {
            out.push_str(" &middot; ");
        }
        let _ = write!(
            out,
            "<button type=\"button\" class=\"pill js-reader-resume-btn\" data-resume-progress=\"{:.4}\">Resume at {pct}%</button>",
            item.read_progress.clamp(0.0, 1.0),
        );
        wrote_meta = true;
    }
    if let Some(ext_href) = sanitize_non_local_href(item.url.as_str()) {
        if wrote_meta {
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

#[allow(clippy::too_many_arguments)]
fn render_reader_header(
    out: &mut String,
    detail: &ItemDetail,
    id_enc: &str,
    reader_path: &str,
    return_to: &str,
    rank_slug: Option<&str>,
    csrf: &str,
) {
    let item = &detail.item;
    render_reader_crumb_row(out, detail, return_to);
    out.push_str("<div class=\"reader-header\">\n<div class=\"reader-title-row\">\n");

    let (star_glyph, star_pressed, star_cls, star_action_word) = if item.is_favorite {
        ("&#9733;", "true", "btn btn-ghost reader-star-btn is-starred", "Unstar")
    } else {
        ("&#9734;", "false", "btn btn-ghost reader-star-btn", "Star")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Toggle Star\" data-title=\"{title}\" aria-label=\"{star_action_word} {title}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        title = esc(&item.title),
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
            &[("return_to", reader_path)],
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
    out.push_str("</div>\n</div>\n");
    render_reader_mobile_tools(out, item, id_enc, return_to, rank_slug, csrf);
    out.push_str("</div>\n");
}

fn render_reader_mobile_tools(
    out: &mut String,
    item: &crate::db::Item,
    id_enc: &str,
    return_to: &str,
    rank_slug: Option<&str>,
    csrf: &str,
) {
    out.push_str("<div class=\"reader-mobile-tools\">\n");
    if item.feed_id.is_some() {
        render_reader_archive_action(out, item, id_enc, return_to, csrf, false);
    }
    out.push_str(
        "<div class=\"reader-size-controls\">\n\
           <button type=\"button\" class=\"btn btn-small btn-ghost js-font-dec\" aria-label=\"Decrease text size\">A&minus;</button>\n\
           <button type=\"button\" class=\"btn btn-small btn-ghost js-font-inc\" aria-label=\"Increase text size\">A+</button>\n\
         </div>\n\
         <details class=\"reader-mobile-drawer\">\n\
           <summary class=\"btn btn-small btn-ghost\">Contents &amp; Info</summary>\n\
           <div class=\"reader-mobile-drawer-body\">\n\
             <div class=\"js-reader-mobile-toc-group\" hidden>\n\
               <div class=\"reader-rail-title\">Contents</div>\n\
               <ul class=\"reader-toc reader-mobile-toc-list js-reader-mobile-toc\"></ul>\n\
             </div>\n\
             <div class=\"reader-mobile-drawer-links\">\n",
    );
    let _ = writeln!(
        out,
        "<a class=\"reader-rail-link\" href=\"/entries/{}\">History &amp; Lists &rarr;</a>",
        esc(id_enc),
    );
    if let Some(slug) = rank_slug {
        let _ = writeln!(
            out,
            "<a class=\"reader-rail-link\" href=\"/rank/{slug}?focus={id}\">Compare &rarr;</a>",
            slug = esc(&urlencode(slug)),
            id = esc(&urlencode(&item.id)),
        );
    }
    out.push_str("</div>\n</div>\n</details>\n</div>\n");
}

fn render_reader_media_and_prose(out: &mut String, detail: &ItemDetail) {
    let item = &detail.item;
    let mut has_local_video = false;
    let mut has_local_pdf = false;
    for m in &detail.media {
        match m.media_type {
            MediaType::Video => {
                has_local_video = true;
                let _ = writeln!(
                    out,
                    "<div class=\"reader-media-box\">\
                       <video class=\"js-reader-video\" data-item-id=\"{}\" data-progress=\"{:.4}\" controls preload=\"metadata\" src=\"/web/media/{}\"></video>\
                     </div>",
                    esc(&item.id),
                    item.read_progress.clamp(0.0, 1.0),
                    esc(&urlencode(&m.id)),
                );
            }
            MediaType::Pdf => {
                has_local_pdf = true;
                let media_href = format!("/web/media/{}", urlencode(&m.id));
                let _ = writeln!(
                    out,
                    "<div class=\"reader-media-box reader-pdf-box\">\
                       <div class=\"reader-pdf-bar\">\
                         <span class=\"meta\">Cached PDF</span>\
                         <a class=\"btn btn-small btn-ghost\" href=\"{href}\" target=\"_blank\" rel=\"noopener\">Open PDF in new tab &#8599;</a>\
                       </div>\
                       <iframe class=\"reader-pdf-frame\" src=\"{href}\" title=\"PDF viewer: {title}\" loading=\"lazy\"></iframe>\
                     </div>",
                    href = esc(&media_href),
                    title = esc(&item.title),
                );
            }
            MediaType::Audio | MediaType::Thumbnail => {}
        }
    }

    if item.content_type == ContentType::Video
        && !has_local_video
        && let Some(ext_href) = sanitize_non_local_href(item.url.as_str())
    {
        out.push_str("<div class=\"reader-video-hero\">\n");
        if let Some(thumb) = item.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
            let _ = writeln!(
                out,
                "<a class=\"reader-video-thumb-link\" href=\"{}\" target=\"_blank\" rel=\"noopener\">\
                   <img class=\"reader-video-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">\
                   <span class=\"reader-video-play-badge\" aria-hidden=\"true\">&#9654;</span>\
                 </a>",
                esc(ext_href),
                esc(thumb),
            );
        }
        let _ = writeln!(
            out,
            "<div class=\"reader-video-actions\">\
               <a class=\"btn btn-primary btn-small\" href=\"{}\" target=\"_blank\" rel=\"noopener\">Watch Original &#8599;</a>\
             </div>\n</div>",
            esc(ext_href),
        );
    }

    out.push_str("<div class=\"reader-prose\">\n");
    if let Some(raw_html) = item.content_html.as_deref().filter(|s| !s.trim().is_empty()) {
        let cleaned = HtmlSanitizer::clean_with_base(raw_html, Some(&item.url));
        out.push_str(&harden_reader_prose_html_with_context(
            &cleaned,
            Some(&item.url),
            Some(&item.title),
        ));
    } else if let Some(summary) = item.summary.as_deref().filter(|s| !s.trim().is_empty()) {
        for para in summary.split("\n\n").map(str::trim).filter(|p| !p.is_empty()) {
            let _ = writeln!(out, "<p>{}</p>", esc(para));
        }
    } else if !has_local_pdf && !has_local_video && item.content_type != ContentType::Video {
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
    can_rank: bool,
    csrf: &str,
) {
    let finish_action = format!("/web/items/{id_enc}/finish");
    let unfinish_action = format!("/web/items/{id_enc}/unfinish");
    out.push_str("<div class=\"reader-end-card\">\n<div class=\"reader-end-center\">\n");
    if can_rank {
        render_inline_csrf_form(
            out,
            &finish_action,
            csrf,
            &[("rank_after", "1")],
            None,
            "<button type=\"submit\" class=\"btn btn-primary reader-end-rank-btn\">Finish &amp; Rank</button>",
        );
    }
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
    let confirm_attr = if is_finished {
        " data-confirm=\"Mark unfinished and delete any saved Diary log and notes for this item?\""
    } else {
        ""
    };
    let _ = writeln!(
        out,
        "<form method=\"post\" action=\"{}\" class=\"inline-form js-finish-toggle-form\" data-finish-action=\"{}\" data-unfinish-action=\"{}\"{confirm_attr}>\
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
        let tag_href = format!("/inbox?status=all&tag={}", urlencode(&tag.id));
        let _ = write!(
            out,
            "<span class=\"pill\"><a class=\"tag-pill-link\" href=\"{}\">#{}</a> ",
            esc(&tag_href),
            esc(&tag.name),
        );
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

#[allow(clippy::too_many_arguments)]
fn render_reader_rail(
    out: &mut String,
    detail: &ItemDetail,
    id_enc: &str,
    reader_path: &str,
    return_to: &str,
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

    let (star_glyph, star_pressed, star_cls, star_action_word) = if item.is_favorite {
        ("&#9733;", "true", "rail-icon-btn js-star-toggle is-starred", "Unstar")
    } else {
        ("&#9734;", "false", "rail-icon-btn js-star-toggle", "Star")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Star\" data-title=\"{title}\" aria-label=\"{star_action_word} {title}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        title = esc(&item.title),
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
            &[("return_to", reader_path)],
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

    if item.feed_id.is_some() {
        out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>\n");
        render_reader_archive_action(out, item, id_enc, return_to, csrf, true);
    }

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

fn render_reader_archive_action(
    out: &mut String,
    item: &crate::db::Item,
    id_enc: &str,
    return_to: &str,
    csrf: &str,
    icon_only: bool,
) {
    let (arch_action, arch_title, arch_label, icon_svg) = if item.status == ItemStatus::Unread {
        ("archive", "Archive (mark read)", "Archive", ARCHIVE_BOX_SVG)
    } else {
        ("unarchive", "Move back to Inbox", "Restore", RESTORE_BOX_SVG)
    };
    let prog_str = format!("{:.4}", item.read_progress.clamp(0.0, 1.0));
    let arch_btn = if icon_only {
        format!(
            "<button type=\"submit\" class=\"rail-icon-btn js-reader-archive-btn\" title=\"{arch_title}\" aria-label=\"{arch_title}\">{icon_svg}</button>"
        )
    } else {
        format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost js-reader-archive-btn\" title=\"{arch_title}\" aria-label=\"{arch_title}\">{icon_svg} {arch_label}</button>"
        )
    };
    render_inline_csrf_form_with_class(
        out,
        &format!("/web/items/{id_enc}/{arch_action}"),
        csrf,
        Some("js-reader-archive-form"),
        &[("progress", &prog_str), ("return_to", return_to)],
        None,
        &arch_btn,
    );
}

pub(in crate::web) async fn item_audio_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, WebError> {
    let detail = service.items().get(&id).await.web_err(&session)?;
    if let Some(local_audio) = detail.media.iter().find(|m| m.media_type == MediaType::Audio)
        && let Ok(resp) =
            crate::util::http_media::serve_media_stream(&service, &local_audio.id, &headers).await
    {
        return Ok(resp);
    }
    if let Some(ext_audio) = detail.item.audio_url.as_deref().and_then(sanitize_non_local_href) {
        let mut resp = StatusCode::SEE_OTHER.into_response();
        if let Ok(val) = header::HeaderValue::from_str(ext_audio) {
            resp.headers_mut().insert(header::LOCATION, val);
            resp.headers_mut()
                .insert(header::REFERRER_POLICY, header::HeaderValue::from_static("no-referrer"));
            return Ok(resp);
        }
    }
    Err(WebError::from_app(
        AppError::not_found("Audio stream not found for this item"),
        Some(&session.csrf_token),
        session.theme,
    ))
}

pub(in crate::web) async fn web_media_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, WebError> {
    crate::util::http_media::serve_media_stream(&service, &id, &headers).await.web_err(&session)
}

pub(in crate::web) async fn post_item_progress(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ProgressForm>,
) -> Result<Response, WebError> {
    let source = match form.source.as_deref().map(str::trim) {
        Some("audio") => Some(ProgressSource::Audio),
        Some("reader") => Some(ProgressSource::Reader),
        _ => None,
    };
    let _ = service
        .items()
        .update_progress_with_auto_read_source(&id, form.progress, source)
        .await
        .web_err(&session)?;
    Ok(StatusCode::NO_CONTENT.into_response())
}

pub(in crate::web) async fn post_item_finish(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<FinishItemForm>,
) -> Result<Response, WebError> {
    let finished =
        service.items().finish_reader_item(&id, form.notes.as_deref()).await.web_err(&session)?;
    let default_return = if finished.has_feed { "/inbox" } else { "/queue" };

    if parse_form_bool(form.rank_after.as_deref())
        && let Ok(Some(list_slug)) =
            service.catalog().rank_target_after_consumption(&finished.logged.entry).await
    {
        Ok(redirect_with_flash(
            &format!(
                "/rank/{}?focus={}",
                urlencode(&list_slug),
                urlencode(&finished.logged.entry.id)
            ),
            "notice",
            "Marked finished & logged to Diary",
        ))
    } else {
        let base = safe_local_redirect(form.return_to.as_deref(), default_return);
        Ok(redirect_with_flash(&base, "notice", "Marked finished & logged to Diary"))
    }
}

pub(in crate::web) async fn post_item_unfinish(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ItemReturnForm>,
) -> Result<Response, WebError> {
    service.items().unfinish_reader_item(&id).await.web_err(&session)?;
    let fallback = format!("/reader/{}", urlencode(&id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    Ok(redirect_with_flash(&dest, "notice", "Marked unfinished"))
}

pub(in crate::web) async fn post_item_tag_add(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<AddTagForm>,
) -> Result<Response, WebError> {
    let _ = service.items().add_tag(&id, None, Some(&form.name)).await.web_err(&session)?;
    Ok(redirect_see_other(&format!("/reader/{}", urlencode(&id))))
}

pub(in crate::web) async fn post_item_tag_remove(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path((id, tag_id)): Path<(String, String)>,
) -> Result<Response, WebError> {
    service.items().remove_tag(&id, &tag_id).await.web_err(&session)?;
    Ok(redirect_see_other(&format!("/reader/{}", urlencode(&id))))
}

/// Finds the byte index of the closing `>` of an HTML tag starting immediately after `<`,
/// ignoring any `>` characters inside single- or double-quoted attribute values.
fn find_html_tag_end(after_lt: &str) -> Option<usize> {
    let mut quote: Option<u8> = None;
    for (idx, b) in after_lt.bytes().enumerate() {
        match quote {
            Some(q) if b == q => quote = None,
            None if b == b'"' || b == b'\'' => quote = Some(b),
            None if b == b'>' => return Some(idx),
            Some(_) | None => {}
        }
    }
    None
}

/// Checks whether an HTML tag's attribute string defines an attribute named `target`
/// (ASCII case-insensitive), skipping quoted attribute values so text inside another
/// attribute value (e.g. `title="alt=foo"`) is never mistaken for an attribute name.
fn has_html_attr(attrs: &str, target: &str) -> bool {
    let bytes = attrs.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let name_start = i;
        while i < bytes.len()
            && !bytes[i].is_ascii_whitespace()
            && bytes[i] != b'='
            && bytes[i] != b'/'
        {
            i += 1;
        }
        let matched = attrs[name_start..i].eq_ignore_ascii_case(target);

        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < bytes.len() && (bytes[i] == b'"' || bytes[i] == b'\'') {
                let quote = bytes[i];
                i += 1;
                while i < bytes.len() && bytes[i] != quote {
                    i += 1;
                }
                if i < bytes.len() {
                    i += 1;
                }
            } else {
                while i < bytes.len() && !bytes[i].is_ascii_whitespace() {
                    i += 1;
                }
            }
        }
        if matched {
            return true;
        }
    }
    false
}

fn parse_heading_level(tag_name: &str) -> Option<u8> {
    let bytes = tag_name.as_bytes();
    if bytes.len() == 2 && bytes[0].eq_ignore_ascii_case(&b'h') && (b'1'..=b'6').contains(&bytes[1])
    {
        Some(bytes[1] - b'0')
    } else {
        None
    }
}

const fn heading_tag_for_level(level: u8) -> &'static str {
    match level {
        1 => "h1",
        2 => "h2",
        3 => "h3",
        4 => "h4",
        5 => "h5",
        _ => "h6",
    }
}

fn find_min_heading_level(clean_html: &str) -> Option<u8> {
    let mut min_lvl: Option<u8> = None;
    let mut rest = clean_html;
    while let Some(lt) = rest.find('<') {
        let after_lt = &rest[lt + 1..];
        let Some(gt) = find_html_tag_end(after_lt) else {
            break;
        };
        let raw_tag = &after_lt[..gt];
        rest = &after_lt[gt + 1..];
        let tag_body = raw_tag.strip_prefix('/').unwrap_or(raw_tag);
        let name_end =
            tag_body.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(tag_body.len());
        if let Some(lvl) = parse_heading_level(&tag_body[..name_end]) {
            min_lvl = Some(min_lvl.map_or(lvl, |cur| cur.min(lvl)));
            if lvl == 1 {
                break;
            }
        }
    }
    min_lvl
}

fn parse_html_attrs(attrs: &str) -> Vec<(String, Option<String>)> {
    let mut out = Vec::new();
    let bytes = attrs.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let name_start = i;
        while i < bytes.len()
            && !bytes[i].is_ascii_whitespace()
            && bytes[i] != b'='
            && bytes[i] != b'/'
        {
            i += 1;
        }
        let name = attrs[name_start..i].to_owned();
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let value = if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < bytes.len() && (bytes[i] == b'"' || bytes[i] == b'\'') {
                let quote = bytes[i];
                i += 1;
                let val_start = i;
                while i < bytes.len() && bytes[i] != quote {
                    i += 1;
                }
                let raw = &attrs[val_start..i];
                if i < bytes.len() {
                    i += 1;
                }
                Some(decode_attr_entities(raw))
            } else {
                let val_start = i;
                while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b'/' {
                    i += 1;
                }
                Some(decode_attr_entities(&attrs[val_start..i]))
            }
        } else {
            None
        };
        if !name.is_empty() {
            out.push((name, value));
        }
    }
    out
}

fn decode_attr_entities(raw: &str) -> String {
    quick_xml::escape::unescape(raw).map_or_else(|_| raw.to_owned(), std::borrow::Cow::into_owned)
}

fn get_parsed_attr<'a>(attrs: &'a [(String, Option<String>)], target: &str) -> Option<&'a str> {
    attrs.iter().find_map(|(k, v)| k.eq_ignore_ascii_case(target).then_some(v.as_deref()).flatten())
}

fn is_inline_emoji_img(src: &str, alt: &str) -> bool {
    let lower = src.to_ascii_lowercase();
    lower.contains("/images/core/emoji/")
        || lower.contains("/wp-includes/images/smilies/")
        || lower.contains("/wpcom-smileys/")
        || lower.contains("twemoji.maxcdn.com/")
        || lower.contains("/twemoji/")
        || is_footnote_backarrow_emoji(src, alt)
}

fn is_footnote_backarrow_emoji(src: &str, alt: &str) -> bool {
    let trimmed = alt.trim().trim_end_matches(['\u{FE0E}', '\u{FE0F}']);
    if trimmed == "↩" {
        return true;
    }
    let lower = src.to_ascii_lowercase();
    trimmed.is_empty() && (lower.ends_with("/21a9.png") || lower.ends_with("/21a9.svg"))
}

fn is_latex_img_src(src: &str) -> bool {
    src.to_ascii_lowercase().contains("/latex.php")
}

fn is_tracking_pixel_img(src: &str, width: Option<&str>, height: Option<&str>) -> bool {
    let is_tiny = |raw: &str| {
        let trimmed = raw.trim();
        !trimmed.ends_with('%')
            && trimmed
                .trim_end_matches("px")
                .trim_end_matches("PX")
                .trim()
                .parse::<u32>()
                .is_ok_and(|px| px <= 4)
    };
    if width.is_some_and(is_tiny) || height.is_some_and(is_tiny) {
        return true;
    }
    let lower = src.to_ascii_lowercase();
    lower.contains("feeds.feedburner.com/~")
        || lower.contains("/1x1.gif")
        || lower.contains("/pixel.gif")
        || lower.contains("/spacer.gif")
}

fn is_empty_html_text(raw: &str) -> bool {
    let mut rest = raw;
    while !rest.is_empty() {
        let trimmed = rest.trim_start();
        if trimmed.is_empty() {
            return true;
        }
        if let Some(after) = trimmed
            .strip_prefix("&nbsp;")
            .or_else(|| trimmed.strip_prefix("&#160;"))
            .or_else(|| trimmed.strip_prefix("&#xA0;"))
            .or_else(|| trimmed.strip_prefix("&#xa0;"))
            .or_else(|| trimmed.strip_prefix('\u{00a0}'))
        {
            rest = after;
        } else {
            return false;
        }
    }
    true
}

fn normalize_title_for_compare(raw: &str) -> String {
    raw.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

fn strip_duplicate_leading_h1<'a>(clean_html: &'a str, article_title: Option<&str>) -> &'a str {
    let Some(title) = article_title.map(str::trim).filter(|t| !t.is_empty()) else {
        return clean_html;
    };
    let trimmed = clean_html.trim_start();
    let Some(after_lt) = trimmed.strip_prefix('<') else {
        return clean_html;
    };
    let Some(gt) = find_html_tag_end(after_lt) else {
        return clean_html;
    };
    let open_tag = &after_lt[..gt];
    let name_end =
        open_tag.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(open_tag.len());
    if !open_tag[..name_end].eq_ignore_ascii_case("h1") {
        return clean_html;
    }
    let body_start = &after_lt[gt + 1..];
    let lower_rest = body_start.to_ascii_lowercase();
    let Some(close_pos) = lower_rest.find("</h1>") else {
        return clean_html;
    };
    let inner_html = &body_start[..close_pos];
    let heading_text = HtmlSanitizer::plain_text_excerpt_from_clean(inner_html, 512);
    if normalize_title_for_compare(&heading_text) == normalize_title_for_compare(title) {
        &body_start[close_pos + "</h1>".len()..]
    } else {
        clean_html
    }
}

fn extract_same_page_fragment<'a>(
    href: &'a str,
    article_url: Option<&Url>,
) -> Option<std::borrow::Cow<'a, str>> {
    let trimmed = href.trim();
    let raw_frag: std::borrow::Cow<'_, str> = if let Some(frag) = trimmed.strip_prefix('#') {
        std::borrow::Cow::Borrowed(frag)
    } else {
        let base = article_url?;
        let parsed = Url::parse(trimmed).ok()?;
        let frag = parsed.fragment()?;
        let same_host =
            parsed.host_str().zip(base.host_str()).is_some_and(|(a, b)| a.eq_ignore_ascii_case(b));
        let same_path = parsed.path().trim_end_matches('/') == base.path().trim_end_matches('/');
        if !same_host || !same_path || parsed.query() != base.query() {
            return None;
        }
        std::borrow::Cow::Owned(frag.to_owned())
    };
    (!raw_frag.is_empty()).then_some(raw_frag)
}

fn reciprocal_footnote_candidates(raw_frag: &str) -> Vec<String> {
    let frag = raw_frag.strip_prefix("prose-").unwrap_or(raw_frag);
    if let Some(base) = frag.strip_suffix("-link") {
        return vec![base.to_owned()];
    }
    if let Some(rest) = frag.strip_prefix("footnote_") {
        return vec![format!("identifier_{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("identifier_") {
        return vec![format!("footnote_{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("fnref") {
        return vec![format!("fn{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("fn") {
        return vec![format!("fnref{rest}"), format!("{frag}-link")];
    }
    if let Some(rest) =
        frag.strip_prefix("footnote-anchor-").or_else(|| frag.strip_prefix("footnote-ref-"))
    {
        return vec![format!("footnote-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("footnote-") {
        return vec![
            format!("footnote-anchor-{rest}"),
            format!("footnote-ref-{rest}"),
            format!("{frag}-link"),
        ];
    }
    if let Some(rest) = frag.strip_prefix("cite_note-") {
        return vec![format!("cite_ref-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("cite_ref-") {
        return vec![format!("cite_note-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("noteref-") {
        return vec![format!("note-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("note-") {
        return vec![format!("noteref-{rest}"), format!("{frag}-link")];
    }
    vec![format!("{frag}-link")]
}

fn synthesize_paired_footnote_ids(
    html: &str,
    article_url: Option<&Url>,
    known_ids: &mut HashSet<String>,
) -> HashMap<String, String> {
    let mut referenced_frags = HashSet::new();
    let mut rest = html;
    while let Some(lt) = rest.find('<') {
        let after_lt = &rest[lt + 1..];
        let Some(gt) = find_html_tag_end(after_lt) else {
            break;
        };
        let raw_tag = &after_lt[..gt];
        rest = &after_lt[gt + 1..];
        if raw_tag.starts_with('/') {
            continue;
        }
        let name_end =
            raw_tag.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(raw_tag.len());
        if !raw_tag[..name_end].eq_ignore_ascii_case("a") {
            continue;
        }
        let parsed = parse_html_attrs(&raw_tag[name_end..]);
        if let Some(href) = get_parsed_attr(&parsed, "href")
            && let Some(frag) = extract_same_page_fragment(href, article_url)
        {
            let frag_str = frag.as_ref();
            let norm = frag_str.strip_prefix("prose-").unwrap_or(frag_str);
            if !norm.is_empty() {
                referenced_frags.insert(norm.to_owned());
            }
        }
    }

    let mut synthetic_by_href_frag = HashMap::new();
    for frag in &referenced_frags {
        for recip in reciprocal_footnote_candidates(frag) {
            let prose_recip = format!("prose-{recip}");
            if referenced_frags.contains(&recip)
                && !known_ids.contains(&recip)
                && !known_ids.contains(&prose_recip)
            {
                known_ids.insert(prose_recip.clone());
                synthetic_by_href_frag.insert(frag.clone(), prose_recip);
                break;
            }
        }
    }
    synthetic_by_href_frag
}

fn resolve_local_prose_fragment<'a>(
    href: &str,
    article_url: Option<&Url>,
    known_ids: &'a HashSet<String>,
) -> Option<&'a str> {
    let raw_frag = extract_same_page_fragment(href, article_url)?;
    let raw_str = raw_frag.as_ref();
    if let Some(exact) = known_ids.get(raw_str) {
        return Some(exact.as_str());
    }
    let stripped = raw_str.strip_prefix("prose-").unwrap_or(raw_str);
    if let Some(exact) = known_ids.get(stripped) {
        return Some(exact.as_str());
    }
    let prefixed = format!("prose-{stripped}");
    known_ids.get(&prefixed).map(String::as_str)
}

fn strip_duplicate_id_attr(attrs: &str, seen_ids: &mut HashSet<String>) -> Option<String> {
    let parsed = parse_html_attrs(attrs);
    let id_val = get_parsed_attr(&parsed, "id")?;
    if seen_ids.insert(id_val.to_owned()) {
        return None;
    }
    let mut rebuilt = String::new();
    for (k, v) in parsed {
        if k.eq_ignore_ascii_case("id") {
            continue;
        }
        rebuilt.push(' ');
        rebuilt.push_str(&k);
        if let Some(val) = v {
            let _ = write!(rebuilt, "=\"{}\"", esc(&val));
        }
    }
    Some(rebuilt)
}

fn rewrite_anchor_attrs_for_local_fragment(
    attrs: &str,
    local_id: &str,
    inject_id: Option<&str>,
) -> String {
    let parsed = parse_html_attrs(attrs);
    let mut rebuilt = String::new();
    if let Some(id_val) = inject_id {
        let _ = write!(rebuilt, " id=\"{}\"", esc(id_val));
    }
    for (k, v) in parsed {
        if k.eq_ignore_ascii_case("href") {
            let _ = write!(rebuilt, " href=\"#{}\"", esc(local_id));
        } else if !k.eq_ignore_ascii_case("target") && !k.eq_ignore_ascii_case("rel") {
            rebuilt.push(' ');
            rebuilt.push_str(&k);
            if let Some(val) = v {
                let _ = write!(rebuilt, "=\"{}\"", esc(&val));
            }
        }
    }
    rebuilt
}

/// Normalizes already-sanitized article HTML for embedding under the Reader's `<h2>` title:
/// - Strips a duplicate leading `<h1>` when it repeats the article title.
/// - Replaces WordPress/Twemoji emoji `<img>` tags with Unicode `alt` text, converts `latex.php`
///   math images into readable `<em>` math, and strips tracking pixels.
/// - Drops empty `<p></p>` blocks, deduplicates `id` attributes, and rewires in-page footnote
///   links (`#fn1` / `<article-url>#fn1`) to matching `#prose-...` targets inside the article.
/// - Dynamically demotes headings from the highest heading level present so top article
///   headings land on `<h3>` (and subheadings on `<h4>`/`<h5>`, capped at `<h6>`), preserving
///   a single `<h1>` and a clean prose heading hierarchy without skipping levels.
/// - Ensures `<pre>` blocks carry `tabindex="0"` for keyboard scrolling.
/// - Ensures `<img>` tags without an `alt` attribute default to `alt=""`.
#[cfg(test)]
fn harden_reader_prose_html(clean_html: &str) -> String {
    harden_reader_prose_html_with_context(clean_html, None, None)
}

#[allow(clippy::too_many_lines)]
fn harden_reader_prose_html_with_context(
    clean_html: &str,
    article_url: Option<&Url>,
    article_title: Option<&str>,
) -> String {
    let without_dup_h1 = strip_duplicate_leading_h1(clean_html, article_title);
    let heading_shift =
        find_min_heading_level(without_dup_h1).map_or(0, |min_lvl| 3u8.saturating_sub(min_lvl));
    let mut known_ids = semantic_html::collect_html_ids(without_dup_h1);
    let synthetic_ids = synthesize_paired_footnote_ids(without_dup_h1, article_url, &mut known_ids);
    let mut seen_ids = HashSet::new();
    let mut out = String::with_capacity(without_dup_h1.len() + 64);
    let mut rest = without_dup_h1;

    while let Some(lt) = rest.find('<') {
        out.push_str(&rest[..lt]);
        let after_lt = &rest[lt + 1..];
        let Some(gt) = find_html_tag_end(after_lt) else {
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

        // Drop empty whitespace-only `<p></p>` blocks so articles don't render awkward blank gaps.
        if !is_closing
            && tag_name.eq_ignore_ascii_case("p")
            && !attrs.ends_with('/')
            && let Some(next_lt) = rest.find('<')
            && is_empty_html_text(&rest[..next_lt])
            && let Some(next_gt) = find_html_tag_end(&rest[next_lt + 1..])
            && rest[next_lt + 1..next_lt + 1 + next_gt].trim().eq_ignore_ascii_case("/p")
        {
            rest = &rest[next_lt + 1 + next_gt + 1..];
            continue;
        }

        // Convert inline emoji images to Unicode text, WordPress latex.php to readable math, and drop tracking pixels.
        if !is_closing && tag_name.eq_ignore_ascii_case("img") {
            let parsed_attrs = parse_html_attrs(attrs);
            let src = get_parsed_attr(&parsed_attrs, "src").unwrap_or("");
            let alt = get_parsed_attr(&parsed_attrs, "alt").unwrap_or("");
            let width = get_parsed_attr(&parsed_attrs, "width");
            let height = get_parsed_attr(&parsed_attrs, "height");
            if is_latex_img_src(src) && !alt.trim().is_empty() {
                let formatted = semantic_html::format_latex_alt_to_html(alt);
                if !formatted.is_empty() {
                    let _ = write!(out, "<em>{formatted}</em>");
                }
                continue;
            }
            if is_inline_emoji_img(src, alt) {
                if is_footnote_backarrow_emoji(src, alt) {
                    out.push_str("↩\u{FE0E}");
                    rest = rest.trim_start_matches(['\u{FE0E}', '\u{FE0F}']);
                } else {
                    let _ = write!(out, "{}", esc(alt.trim()));
                }
                continue;
            }
            if is_tracking_pixel_img(src, width, height) {
                continue;
            }
        }

        let demoted_heading = parse_heading_level(tag_name)
            .map(|lvl| heading_tag_for_level((lvl + heading_shift).min(6)));

        let deduped_attrs_owned = if !is_closing && has_html_attr(attrs, "id") {
            strip_duplicate_id_attr(attrs, &mut seen_ids)
        } else {
            None
        };
        let effective_attrs = deduped_attrs_owned.as_deref().unwrap_or(attrs);

        out.push('<');
        if is_closing {
            out.push('/');
            out.push_str(demoted_heading.unwrap_or(tag_name));
            out.push_str(attrs);
        } else if let Some(new_tag) = demoted_heading {
            out.push_str(new_tag);
            out.push_str(effective_attrs);
        } else if tag_name.eq_ignore_ascii_case("a")
            && !known_ids.is_empty()
            && let Some(href) = get_parsed_attr(&parse_html_attrs(attrs), "href")
            && let Some(local_id) = resolve_local_prose_fragment(href, article_url, &known_ids)
        {
            let inject_id = if has_html_attr(effective_attrs, "id") {
                None
            } else {
                extract_same_page_fragment(href, article_url).and_then(|frag| {
                    let frag_str = frag.as_ref();
                    let norm = frag_str.strip_prefix("prose-").unwrap_or(frag_str);
                    synthetic_ids
                        .get(norm)
                        .and_then(|synth| seen_ids.insert(synth.clone()).then_some(synth.as_str()))
                })
            };
            out.push_str(tag_name);
            out.push_str(&rewrite_anchor_attrs_for_local_fragment(
                effective_attrs,
                local_id,
                inject_id,
            ));
        } else if tag_name.eq_ignore_ascii_case("pre") {
            out.push_str(tag_name);
            out.push_str(effective_attrs);
            if !has_html_attr(effective_attrs, "tabindex") {
                out.push_str(" tabindex=\"0\"");
            }
        } else if tag_name.eq_ignore_ascii_case("img") {
            out.push_str(tag_name);
            let has_alt = has_html_attr(effective_attrs, "alt");
            if let Some(before_slash) = effective_attrs.strip_suffix('/') {
                out.push_str(before_slash);
                if !has_alt {
                    out.push_str(" alt=\"\"");
                }
                out.push('/');
            } else {
                out.push_str(effective_attrs);
                if !has_alt {
                    out.push_str(" alt=\"\"");
                }
            }
        } else {
            out.push_str(tag_name);
            out.push_str(effective_attrs);
        }
        out.push('>');
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_harden_reader_prose_html_preserves_gt_inside_quoted_attributes() {
        let img = r#"<img src="https://example.com/a.png" alt="1 > 0">"#;
        assert_eq!(harden_reader_prose_html(img), img);

        let img_single_quote = "<img src='https://example.com/a.png' alt='a > b'>";
        assert_eq!(harden_reader_prose_html(img_single_quote), img_single_quote);

        let img_missing_alt = r#"<img src="https://example.com/a.png" title="1 > 0">"#;
        assert_eq!(
            harden_reader_prose_html(img_missing_alt),
            r#"<img src="https://example.com/a.png" title="1 > 0" alt="">"#
        );

        let img_fake_alt_in_title = r#"<img src="https://example.com/a.png" title="alt=fake">"#;
        assert_eq!(
            harden_reader_prose_html(img_fake_alt_in_title),
            r#"<img src="https://example.com/a.png" title="alt=fake" alt="">"#
        );

        let heading_with_gt = r#"<h1 title="a > b">Title</h1>"#;
        assert_eq!(harden_reader_prose_html(heading_with_gt), r#"<h3 title="a > b">Title</h3>"#);
    }

    #[test]
    fn test_harden_reader_prose_html_dynamically_demotes_from_highest_heading() {
        let h2_top = "<h2>Section</h2><h3>Sub</h3><h4>Note</h4>";
        assert_eq!(harden_reader_prose_html(h2_top), "<h3>Section</h3><h4>Sub</h4><h5>Note</h5>");

        let h1_top = "<h1>Part</h1><h2>Section</h2><h3>Sub</h3>";
        assert_eq!(harden_reader_prose_html(h1_top), "<h3>Part</h3><h4>Section</h4><h5>Sub</h5>");

        let h3_top = "<h3>Already Three</h3><h4>Four</h4>";
        assert_eq!(harden_reader_prose_html(h3_top), "<h3>Already Three</h3><h4>Four</h4>");
    }

    #[test]
    fn test_harden_reader_prose_html_rewires_footnotes_emojis_latex_and_dup_h1() {
        let base = Url::parse("https://proofsandprompts.com/2026/11/22/a-mathematical-community/")
            .expect("valid test URL");
        let raw_rss = r##"
            <h1>A mathematical community?</h1>
            <p class="author-line wp-block-paragraph"><a href="https://www.jm-kantor.fr/">Jean-Michel Kantor</a>, Emeritus from IMJ</p>
            <p></p>
            <p>Assume <img src="https://s0.wp.com/latex.php?latex=p%5Cgeq+17" alt="p\geq 17">.<img src="https://example.com/pixel.gif" width="1" height="1"></p>
            <p>See note<sup><a href="#fn-1" id="fn-1-link">1</a></sup>.</p>
            <h2>1. Section</h2>
            <ol><li id="fn-1">Source <a href="#fn-1-link"><img src="https://s.w.org/images/core/emoji/17.0.2/72x72/21a9.png" alt="↩"></a></li></ol>
        "##;
        let cleaned = HtmlSanitizer::clean_with_base(raw_rss, Some(&base));
        let rendered = harden_reader_prose_html_with_context(
            &cleaned,
            Some(&base),
            Some("A mathematical community?"),
        );

        assert!(!rendered.contains("<h3>A mathematical community?</h3>"));
        assert!(rendered.contains("<h3>1. Section</h3>"));
        assert!(rendered.contains("<p><em><a href=\"https://www.jm-kantor.fr/\""));
        assert!(rendered.contains("<em>p ≥ 17</em>"));
        assert!(!rendered.contains("pixel.gif"));
        assert!(!rendered.contains("<p></p>"));
        assert!(!rendered.contains("21a9.png"));
        assert!(rendered.contains("href=\"#prose-fn-1\""));
        assert!(rendered.contains("id=\"prose-fn-1-link\""));
        assert!(rendered.contains("id=\"prose-fn-1\""));
        assert!(rendered.contains("href=\"#prose-fn-1-link\""));
        assert!(rendered.contains(">↩\u{FE0E}</a>"));

        // Legacy stored HTML (before re-poll, missing `id` attributes) still pairs footnote refs
        // and WordPress 72x72 arrow emoji backlinks into live local `#prose-...` jumps.
        let legacy_stored = r#"<p>See note<sup><a href="https://proofsandprompts.com/2026/11/22/a-mathematical-community/#3f6c1a52-8d4e" rel="noopener noreferrer">1</a></sup>.</p><ol><li>Source <a href="https://proofsandprompts.com/2026/11/22/a-mathematical-community/#3f6c1a52-8d4e-link" rel="noopener noreferrer"><img src="https://s.w.org/images/core/emoji/17.0.2/72x72/21a9.png" alt="↩">︎</a></li></ol>"#;
        let legacy_rendered = harden_reader_prose_html_with_context(
            legacy_stored,
            Some(&base),
            Some("A mathematical community?"),
        );
        assert!(!legacy_rendered.contains("21a9.png"));
        assert!(
            legacy_rendered
                .contains(r##"id="prose-3f6c1a52-8d4e-link" href="#prose-3f6c1a52-8d4e">1</a>"##)
        );
        assert!(
            legacy_rendered
                .contains(r##"id="prose-3f6c1a52-8d4e" href="#prose-3f6c1a52-8d4e-link">↩︎</a>"##)
        );
        assert!(!legacy_rendered.contains("↩︎︎"));

        // Duplicate IDs must be deduplicated for accessibility compliance.
        let dup_ids = r#"<p id="prose-dup">A</p><p id="prose-dup">B</p>"#;
        let deduped = harden_reader_prose_html(dup_ids);
        assert_eq!(deduped.matches("id=\"prose-dup\"").count(), 1);
    }
}
