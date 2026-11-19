use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};

use super::{AddTagForm, FinishItemForm, ProgressForm, render_audio_play_button};
use crate::{
    content::fetcher::HtmlSanitizer,
    db::{ConsumptionStatus, EntryKind, ItemStatus, MediaType, ReadAtPatch, SyncRepo},
    error::ApiError,
    scheduler::FeedFetcher,
    service::{ItemDetail, RocheService},
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, redirect_see_other, redirect_with_flash,
            render_inline_csrf_form, render_inline_csrf_form_with_class, render_page,
            safe_local_redirect, urlencode, web_api_error_response,
        },
        links::sanitize_non_local_href,
    },
};

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

    let rank_slug = match service.catalog().get_entry_detail(&id).await {
        Ok(Some(ed)) if ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed) => {
            service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
        }
        _ => None,
    };

    let active_nav = if detail.item.in_want_to_read {
        NavSection::Queue
    } else if detail.item.feed_id.is_none() && detail.item.is_favorite {
        NavSection::Lists
    } else {
        NavSection::Inbox
    };

    let mut body = String::with_capacity(12_288);
    render_reader_article(&mut body, &detail, rank_slug.as_deref(), &session.csrf_token);

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

fn render_reader_article(
    out: &mut String,
    detail: &ItemDetail,
    rank_slug: Option<&str>,
    csrf: &str,
) {
    let item = &detail.item;
    let entry_kind = EntryKind::from_content_type(item.content_type);
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
    if let Some(ext_href) = sanitize_non_local_href(item.url.as_str()) {
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

    let id_enc = urlencode(&item.id);
    let finish_action = format!("/web/items/{id_enc}/finish");
    // Explicit "Finished" & "Finished & Rank" controls (essential for Videos, available on all feed items)
    render_inline_csrf_form(
        out,
        &finish_action,
        csrf,
        &[("return_to", &reader_path)],
        None,
        "<button type=\"submit\" class=\"btn btn-primary btn-small\">&#10003; Finished</button>",
    );
    render_inline_csrf_form(
        out,
        &finish_action,
        csrf,
        &[("rank_after", "1")],
        None,
        "<button type=\"submit\" class=\"btn btn-small\">Finished &amp; Rank</button>",
    );

    let (arch_action, arch_label, arch_return) = if item.feed_id.is_some() {
        if item.status == ItemStatus::Unread {
            ("archive", "Archive", "/inbox")
        } else {
            ("unarchive", "Move to Inbox", "/inbox")
        }
    } else if item.in_want_to_read {
        ("archive", "Remove from Want to Read", "/queue")
    } else {
        ("unarchive", "+ Want to Read", reader_path.as_str())
    };
    let prog_str = format!("{:.4}", item.read_progress.clamp(0.0, 1.0));
    let arch_btn = format!("<button type=\"submit\" class=\"btn btn-small\">{arch_label}</button>");
    render_inline_csrf_form_with_class(
        out,
        &format!("/web/items/{id_enc}/{arch_action}"),
        csrf,
        Some("js-reader-archive-form"),
        &[("progress", &prog_str), ("return_to", arch_return)],
        None,
        &arch_btn,
    );

    let star_label = if item.is_favorite { "&#9733; Starred" } else { "&#9734; Star" };
    let star_pressed = if item.is_favorite { "true" } else { "false" };
    let star_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost\" aria-pressed=\"{star_pressed}\">{star_label}</button>"
    );
    render_inline_csrf_form(
        out,
        &format!("/web/items/{id_enc}/star"),
        csrf,
        &[("return_to", &reader_path)],
        None,
        &star_btn,
    );
    let _ = write!(
        out,
        "<a class=\"btn btn-small btn-ghost\" href=\"/entries/{}\">Catalog Entry</a>",
        esc(&id_enc),
    );
    if let Some(slug) = rank_slug {
        let _ = write!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"/rank/{slug}?focus={id}\">Compare</a>",
            slug = esc(&urlencode(slug)),
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
        out.push_str(&HtmlSanitizer::clean_with_base(raw_html, Some(&item.url)));
    } else if let Some(summary) = item.summary.as_deref().filter(|s| !s.trim().is_empty()) {
        for para in summary.split("\n\n").map(str::trim).filter(|p| !p.is_empty()) {
            let _ = writeln!(out, "<p>{}</p>", esc(para));
        }
    } else {
        out.push_str("<p class=\"empty\">No extracted article text stored for this entry.</p>\n");
    }
    out.push_str("</div>\n");

    out.push_str("<h3>Tags</h3>\n<div class=\"pill-bar\">\n");
    let item_id_enc = urlencode(&item.id);
    for tag in &detail.tags {
        let _ = write!(out, "<span class=\"pill\">#{} ", esc(&tag.name));
        let remove_btn = format!(
            "<button type=\"submit\" class=\"link-btn ml-xs\" aria-label=\"Remove tag {}\" title=\"Remove tag\">&times;</button>",
            esc(&tag.name),
        );
        render_inline_csrf_form(
            out,
            &format!("/web/items/{item_id_enc}/tags/{}/remove", urlencode(&tag.id)),
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
           <input type=\"text\" name=\"name\" required aria-label=\"Add tag\" placeholder=\"Add tag...\" class=\"tag-add-input\">\
           <button type=\"submit\" class=\"btn btn-small\">+ Tag</button>\
         </form>\n</div>\n</article>\n",
        esc(&item_id_enc),
        esc(csrf),
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
