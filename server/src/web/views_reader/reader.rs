use std::{fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};

use super::{AddTagForm, FinishItemForm, ProgressForm, render_audio_play_button};
use crate::{
    content::HtmlSanitizer,
    domain::{
        ConsumptionStatus, EntryKind, ItemStatus, MediaType, READER_AUTO_READ_THRESHOLD,
        ReadAtPatch,
    },
    service::{AppError, ItemDetail, RocheService},
    util::clock::now_utc,
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _, esc, parse_form_bool,
            redirect_see_other, redirect_with_flash, render_inline_csrf_form,
            render_inline_csrf_form_with_class, render_page, safe_local_redirect, urlencode,
        },
        links::sanitize_non_local_href,
    },
};

pub(in crate::web) async fn reader_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(flash): Query<FlashQuery>,
) -> Result<Response, WebError> {
    let detail = service.items().get(&id).await.web_err(&session)?;

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

    Ok(render_page(&PageShell {
        title: &detail.item.title,
        active: active_nav,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response())
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

    let (star_glyph, star_pressed) =
        if item.is_favorite { ("&#9733;", "true") } else { ("&#9734;", "false") };
    let star_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span> Star</button>"
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

    out.push_str("<h3>Tags</h3>\n<div class=\"pill-bar\">\n");
    let item_id_enc = urlencode(&item.id);
    for tag in &detail.tags {
        let _ = write!(out, "<span class=\"pill\">#{} ", esc(&tag.name));
        let remove_btn = format!(
            "<button type=\"submit\" class=\"link-btn ml-xs\" aria-label=\"Remove tag {}\" title=\"Remove tag\"><span aria-hidden=\"true\">&times;</span></button>",
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

pub(in crate::web) async fn item_audio_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, WebError> {
    let detail = service.items().get(&id).await.web_err(&session)?;
    if let Some(local_audio) = detail.media.iter().find(|m| m.media_type == MediaType::Audio)
        && let Ok(resp) = crate::api::serve_media_stream(&service, &local_audio.id, &headers).await
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
    ))
}

pub(in crate::web) async fn web_media_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> Result<Response, WebError> {
    crate::api::serve_media_stream(&service, &id, &headers).await.web_err(&session)
}

pub(in crate::web) async fn post_item_progress(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ProgressForm>,
) -> Result<Response, WebError> {
    let p = if form.progress.is_finite() { form.progress.clamp(0.0, 1.0) } else { 0.0 };
    let patch = if p >= READER_AUTO_READ_THRESHOLD {
        ReadAtPatch::MarkRead(now_utc())
    } else {
        ReadAtPatch::Unchanged
    };
    let _ = service.items().update(&id, None, None, Some(p), patch).await.web_err(&session)?;
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
            if !has_html_attr(attrs, "tabindex") {
                out.push_str(" tabindex=\"0\"");
            }
        } else if tag_name.eq_ignore_ascii_case("img") {
            out.push_str(tag_name);
            let has_alt = has_html_attr(attrs, "alt");
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
}
