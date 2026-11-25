use std::{fmt::Write as _, sync::Arc};

use axum::{
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use serde::Deserialize;
use url::Url;

use super::{board_item_content_type, board_item_kind_badge, format_comments_label};
use crate::{
    content::HtmlSanitizer,
    db::{Tag, WANT_TO_READ_COLLECTION_ID},
    domain::{ConsumptionStatus, EntryKind},
    service::{AppError, BoardReaderFallbackReason, BoardReaderView, RocheService},
    web::{
        auth::WebSession,
        html::{
            DISMISS_SVG, NavSection, PageShell, RESTORE_BOX_SVG, WebError, WithWebCsrf as _,
            content_type_label, esc, render_inline_csrf_form, render_inline_csrf_form_with_class,
            render_page, safe_local_redirect, urlencode,
        },
        links::sanitize_non_local_href,
        views_reader::{
            QUEUE_ADD_SVG, QUEUE_CHECK_SVG, TOP_ARROW_SVG, harden_reader_prose_html_with_context,
        },
    },
};

#[derive(Debug, Default, Deserialize)]
pub(in crate::web) struct BoardReaderQuery {
    #[serde(default)]
    pub return_to: Option<String>,
}

struct BoardReaderRenderCtx<'a> {
    view: &'a BoardReaderView,
    hardened_prose_html: Option<&'a str>,
    tags: &'a [Tag],
    can_rank: bool,
    rank_slug: Option<&'a str>,
    suppress_auto_finish: bool,
    return_to: &'a str,
    reader_path: &'a str,
    csrf: &'a str,
}

pub(in crate::web) async fn board_reader_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path((board_id, external_id)): Path<(String, String)>,
    Query(query): Query<BoardReaderQuery>,
) -> Result<Response, WebError> {
    let view = service.boards().open_reader(&board_id, &external_id).await.web_err(&session)?;

    let board_enc = urlencode(&view.board.id);
    let ext_enc = urlencode(&view.item.external_id);
    let default_back = format!("/boards/{board_enc}");
    let return_to = safe_local_redirect(query.return_to.as_deref(), &default_back);
    let reader_path = if return_to == default_back {
        format!("/boards/{board_enc}/read/{ext_enc}")
    } else {
        format!("/boards/{board_enc}/read/{ext_enc}?return_to={}", urlencode(&return_to))
    };

    let content_type = board_item_content_type(&view.item);
    let default_kind = EntryKind::from_content_type(content_type);

    let (tags, rank_slug, can_rank) = if let Some(ref linked) = view.linked_entry {
        let detail_tags = service.items().get(&linked.id).await.ok().map_or_default(|d| d.tags);
        let (slug, rankable) = match service.catalog().get_entry_detail(&linked.id).await {
            Ok(Some(ed)) => {
                let consumed = ed.has_consumed_log
                    || ed.log_history.iter().any(|l| l.status == ConsumptionStatus::Consumed);
                let slug = if consumed {
                    service.catalog().rank_target_after_consumption(&ed.entry).await.ok().flatten()
                } else {
                    None
                };
                let rankable = slug.is_some()
                    || ed.entry.kind.default_list_slug().is_some()
                    || ed.parents.iter().any(|p| p.kind == EntryKind::Collection);
                (slug, rankable)
            }
            _ => (None, default_kind.default_list_slug().is_some()),
        };
        (detail_tags, slug, rankable)
    } else {
        (Vec::new(), None, default_kind.default_list_slug().is_some())
    };

    let suppress_auto_finish = session.flash.notice.as_deref().is_some_and(|n| {
        n.eq_ignore_ascii_case("Marked unfinished")
            || n.eq_ignore_ascii_case("Dismissed story")
            || n.eq_ignore_ascii_case("Dismissed")
    });

    let hardened_prose_html = if let Some(raw_html) =
        view.content_html.as_deref().filter(|s| !s.trim().is_empty()).map(ToOwned::to_owned)
    {
        let parsed_url = Url::parse(&view.item.url).ok();
        let item_title = view.item.title.clone();
        let rendered = tokio::task::spawn_blocking(move || {
            let cleaned = HtmlSanitizer::clean_with_base(&raw_html, parsed_url.as_ref());
            harden_reader_prose_html_with_context(&cleaned, parsed_url.as_ref(), Some(&item_title))
        })
        .await
        .map_err(|e| AppError::Internal(anyhow::anyhow!("Board reader HTML render failed: {e}")))
        .web_err(&session)?;
        (!rendered.trim().is_empty()).then_some(rendered)
    } else {
        None
    };

    let ctx = BoardReaderRenderCtx {
        view: &view,
        hardened_prose_html: hardened_prose_html.as_deref(),
        tags: &tags,
        can_rank,
        rank_slug: rank_slug.as_deref(),
        suppress_auto_finish,
        return_to: &return_to,
        reader_path: &reader_path,
        csrf: &session.csrf_token,
    };

    let mut body = String::with_capacity(14_336);
    render_board_reader_article(&mut body, &ctx);

    Ok(render_page(&PageShell {
        title: &view.item.title,
        active: NavSection::Boards,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

fn estimate_html_reading_minutes(html: &str) -> Option<usize> {
    let mut words = 0usize;
    let mut in_tag = false;
    let mut in_word = false;
    for ch in html.chars() {
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

fn format_hn_discussion_label(comment_count: Option<i64>) -> String {
    match comment_count {
        Some(1) => "Hacker News (1 comment)".to_owned(),
        Some(n) if n >= 0 => format!("Hacker News ({n} comments)"),
        _ => "Hacker News".to_owned(),
    }
}

fn render_board_reader_article(out: &mut String, ctx: &BoardReaderRenderCtx<'_>) {
    let view = ctx.view;
    let item = &view.item;
    let board_enc = urlencode(&view.board.id);
    let ext_enc = urlencode(&item.external_id);
    let content_type = board_item_content_type(item);
    let entry_kind = EntryKind::from_content_type(content_type);
    let has_readable_body = ctx.hardened_prose_html.is_some();
    let linked_id = item.linked_entry_id.as_deref().unwrap_or("");
    let progress = view.linked_entry.as_ref().map_or(0.0, |e| e.read_progress.clamp(0.0, 1.0));
    let suppress_attr =
        if ctx.suppress_auto_finish { " data-suppress-auto-finish=\"1\"" } else { "" };
    let promote_action = format!("/web/boards/{board_enc}/items/{ext_enc}/promote");

    let _ = writeln!(
        out,
        "<div class=\"reader-layout\">\n\
         <article class=\"reader-article js-reader-root\" data-board-reader=\"1\" data-board-id=\"{}\" data-external-id=\"{}\" data-promote-finish-action=\"{}\" data-has-readable-body=\"{}\" data-item-id=\"{}\" data-kind=\"{}\" data-content-type=\"{}\" data-has-audio=\"0\" data-progress=\"{:.4}\" data-finished=\"{}\" data-dismissed=\"{}\" data-back-href=\"{}\"{suppress_attr}>",
        esc(&view.board.id),
        esc(&item.external_id),
        esc(&promote_action),
        if has_readable_body { "1" } else { "0" },
        esc(linked_id),
        entry_kind.as_str(),
        content_type.as_str(),
        progress,
        if item.is_finished { "1" } else { "0" },
        if item.is_dismissed { "1" } else { "0" },
        esc(ctx.return_to),
    );

    render_board_reader_header(out, ctx, &board_enc, &ext_enc, has_readable_body);

    if let Some(prose_html) = ctx.hardened_prose_html {
        out.push_str("<div class=\"reader-prose\">\n");
        out.push_str(prose_html);
        out.push_str("</div>\n");
        render_board_reader_end_card(out, ctx, &board_enc, &ext_enc);
    } else {
        render_board_reader_fallback_card(out, ctx, &board_enc, &ext_enc);
    }

    if let Some(eid) = item.linked_entry_id.as_deref() {
        render_board_reader_tags(out, ctx.tags, &urlencode(eid), ctx.reader_path, ctx.csrf);
    }

    out.push_str("</article>\n");
    render_board_reader_rail(out, ctx, &board_enc, &ext_enc, has_readable_body);
    out.push_str("</div>\n");
}

fn render_board_reader_crumb_row(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    has_readable_body: bool,
) {
    let view = ctx.view;
    let item = &view.item;
    let _ = write!(
        out,
        "<div class=\"reader-crumb-row\">\n\
           <a class=\"reader-back-link js-reader-back-link\" href=\"{}\">&larr; Back to {}</a>\n\
           <span>",
        esc(ctx.return_to),
        esc(&view.board.title),
    );
    let mut wrote_meta = if has_readable_body
        && let Some(prose) = ctx.hardened_prose_html
        && let Some(mins) = estimate_html_reading_minutes(prose)
    {
        let _ = write!(out, "{mins} min read");
        true
    } else {
        false
    };
    if !view.is_self_post
        && let Some(orig_href) = sanitize_non_local_href(&item.url)
    {
        if wrote_meta {
            out.push_str(" &middot; ");
        }
        let _ = write!(
            out,
            "<a href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\">Original &#8599;</a>",
            esc(orig_href),
        );
        wrote_meta = true;
    }
    if let Some(disc_href) = item.discussion_url.as_deref().and_then(sanitize_non_local_href) {
        if wrote_meta {
            out.push_str(" &middot; ");
        }
        let disc_label = format_hn_discussion_label(item.comment_count);
        let _ = write!(
            out,
            "<a class=\"reader-discussion-link js-reader-discussion-link\" href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\">{} &#8599;</a>",
            esc(disc_href),
            esc(&disc_label),
        );
    }
    out.push_str("</span>\n</div>\n");
}

fn render_board_reader_header(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    board_enc: &str,
    ext_enc: &str,
    has_readable_body: bool,
) {
    let view = ctx.view;
    let item = &view.item;
    render_board_reader_crumb_row(out, ctx, has_readable_body);
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
    render_board_star_form(out, board_enc, ext_enc, item, ctx.reader_path, ctx.csrf, &star_btn);

    let _ = writeln!(
        out,
        "<h2>{}</h2>\n</div>\n<div class=\"reader-meta-row\">\n<div class=\"item-byline js-reader-byline\">",
        esc(&item.title),
    );
    if item.is_finished {
        out.push_str(
            "<span class=\"badge badge-finished js-reader-state-badge\">&#10003; Finished</span>",
        );
    } else if item.is_dismissed {
        out.push_str(
            "<span class=\"badge badge-dismissed js-reader-state-badge\">&times; Dismissed</span>",
        );
    }
    if let Some((badge_cls, label)) =
        board_item_kind_badge(item, Some(view.board.source_key.as_str()))
    {
        let _ = write!(out, "<span class=\"badge badge-kind{badge_cls}\">{}</span>", esc(label));
    }
    let _ = write!(
        out,
        "<a class=\"byline-item byline-feed\" href=\"/boards/{board_enc}\">{}</a>",
        esc(&view.board.title),
    );
    if let Some(author) = item.author.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = write!(out, "<span class=\"byline-item\">by {}</span>", esc(author));
    }
    if let Some(score) = item.score {
        let _ = write!(out, "<span class=\"byline-item board-meta-points\">{score} pts</span>");
    }
    if !view.is_self_post
        && let Some(dom) = item.domain.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        let _ = write!(out, "<span class=\"byline-item\">{}</span>", esc(dom));
    }
    if let Some(dt) = item.published_at {
        let _ = write!(out, "<span class=\"byline-item\">{}</span>", dt.format("%B %d, %Y"));
    }
    out.push_str("</div>\n<div class=\"reader-quick-tools\">\n");
    render_board_queue_button(out, board_enc, ext_enc, item, ctx.reader_path, ctx.csrf, false);
    out.push_str("</div>\n</div>\n");
    render_board_reader_mobile_tools(out, ctx, board_enc, ext_enc, has_readable_body);
    out.push_str("</div>\n");
}

fn render_board_star_form(
    out: &mut String,
    board_enc: &str,
    ext_enc: &str,
    item: &crate::db::BoardItemView,
    reader_path: &str,
    csrf: &str,
    button_html: &str,
) {
    if item.is_favorite
        && let Some(eid) = item.linked_entry_id.as_deref()
    {
        render_inline_csrf_form(
            out,
            &format!("/web/items/{}/star", urlencode(eid)),
            csrf,
            &[("return_to", reader_path)],
            None,
            button_html,
        );
    } else {
        render_inline_csrf_form(
            out,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/promote"),
            csrf,
            &[("action", "star"), ("return_to", reader_path)],
            None,
            button_html,
        );
    }
}

fn render_board_queue_button(
    out: &mut String,
    board_enc: &str,
    ext_enc: &str,
    item: &crate::db::BoardItemView,
    reader_path: &str,
    csrf: &str,
    icon_only: bool,
) {
    let form_cls = if icon_only { "js-reader-queue-rail-form" } else { "js-reader-queue-form" };
    if item.in_want_to_read
        && let Some(eid) = item.linked_entry_id.as_deref()
    {
        let eid_enc = urlencode(eid);
        let btn = if icon_only {
            format!(
                "<button type=\"submit\" class=\"rail-icon-btn js-reader-queue-btn\" data-title=\"{title}\" title=\"Remove from Queue\" aria-label=\"Remove {title} from Queue\">{QUEUE_CHECK_SVG}</button>",
                title = esc(&item.title),
            )
        } else {
            "<button type=\"submit\" class=\"btn btn-small btn-ghost is-in-queue js-reader-queue-btn\">In Queue &times;</button>"
                .to_owned()
        };
        render_inline_csrf_form_with_class(
            out,
            &format!("/web/want/read/remove/{eid_enc}"),
            csrf,
            Some(form_cls),
            &[("return_to", reader_path)],
            None,
            &btn,
        );
    } else if let Some(eid) = item.linked_entry_id.as_deref() {
        let eid_enc = urlencode(eid);
        let btn = if icon_only {
            format!(
                "<button type=\"submit\" class=\"rail-icon-btn js-reader-queue-btn\" data-title=\"{title}\" title=\"Add to Queue\" aria-label=\"Add {title} to Queue\">{QUEUE_ADD_SVG}</button>",
                title = esc(&item.title),
            )
        } else {
            "<button type=\"submit\" class=\"btn btn-small btn-ghost js-reader-queue-btn\">+ Queue</button>"
                .to_owned()
        };
        render_inline_csrf_form_with_class(
            out,
            &format!("/web/entries/{eid_enc}/list-add"),
            csrf,
            Some(form_cls),
            &[("list_slug_or_id", WANT_TO_READ_COLLECTION_ID), ("return_to", reader_path)],
            None,
            &btn,
        );
    } else {
        let btn = if icon_only {
            format!(
                "<button type=\"submit\" class=\"rail-icon-btn js-reader-queue-btn\" data-title=\"{title}\" title=\"Add to Queue\" aria-label=\"Add {title} to Queue\">{QUEUE_ADD_SVG}</button>",
                title = esc(&item.title),
            )
        } else {
            "<button type=\"submit\" class=\"btn btn-small btn-ghost js-reader-queue-btn\">+ Queue</button>"
                .to_owned()
        };
        render_inline_csrf_form_with_class(
            out,
            &format!("/web/boards/{board_enc}/items/{ext_enc}/promote"),
            csrf,
            Some(form_cls),
            &[("action", "queue"), ("return_to", reader_path)],
            None,
            &btn,
        );
    }
}

fn render_board_dismiss_action(
    out: &mut String,
    board_enc: &str,
    ext_enc: &str,
    is_dismissed: bool,
    return_to: &str,
    csrf: &str,
    icon_only: bool,
) {
    let (action_slug, title, label, icon_svg) = if is_dismissed {
        ("undismiss", "Restore story to board", "Restore", RESTORE_BOX_SVG)
    } else {
        ("dismiss", "Dismiss story from board", "Dismiss", DISMISS_SVG)
    };
    let btn_html = if icon_only {
        format!(
            "<button type=\"submit\" class=\"rail-icon-btn js-reader-dismiss-btn\" title=\"{title}\" aria-label=\"{title}\">{icon_svg}</button>"
        )
    } else {
        format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost js-reader-dismiss-btn\" title=\"{title}\" aria-label=\"{title}\">{icon_svg} {label}</button>"
        )
    };
    render_inline_csrf_form_with_class(
        out,
        &format!("/web/boards/{board_enc}/items/{ext_enc}/{action_slug}"),
        csrf,
        Some("js-reader-dismiss-form"),
        &[("return_to", return_to)],
        None,
        &btn_html,
    );
}

fn render_board_finish_toggle_form(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    board_enc: &str,
    ext_enc: &str,
) {
    let item = &ctx.view.item;
    let finish_action = format!("/web/boards/{board_enc}/items/{ext_enc}/promote");
    let unfinish_action = item
        .linked_entry_id
        .as_deref()
        .map(|eid| format!("/web/items/{}/unfinish", urlencode(eid)));

    if item.is_finished
        && let Some(ref unfin) = unfinish_action
    {
        let _ = writeln!(
            out,
            "<form method=\"post\" action=\"{}\" class=\"inline-form js-finish-toggle-form\" data-finish-action=\"{}\" data-unfinish-action=\"{}\" data-confirm=\"Undo Finished and remove this item from your Diary?\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
               <input type=\"hidden\" name=\"requeue\" value=\"0\">\
               <input type=\"hidden\" name=\"return_to\" value=\"{}\">\
               <button type=\"submit\" class=\"btn btn-small btn-ghost btn-finished is-done js-finish-toggle-btn\" aria-pressed=\"true\" title=\"Marked finished — click to undo\">&#10003; Finished</button>\
             </form>",
            esc(unfin),
            esc(&finish_action),
            esc(unfin),
            esc(ctx.csrf),
            esc(ctx.reader_path),
        );
    } else {
        let unfin_attr = unfinish_action
            .as_deref()
            .map_or_else(String::new, |u| format!(" data-unfinish-action=\"{}\"", esc(u)));
        let _ = writeln!(
            out,
            "<form method=\"post\" action=\"{}\" class=\"inline-form js-finish-toggle-form\" data-finish-action=\"{}\"{unfin_attr}>\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
               <input type=\"hidden\" name=\"action\" value=\"finish\">\
               <input type=\"hidden\" name=\"return_to\" value=\"{}\">\
               <button type=\"submit\" class=\"btn btn-primary btn-finished js-finish-toggle-btn\" aria-pressed=\"false\" title=\"Mark this story as finished and log to Diary\">&#10003; Mark Finished</button>\
             </form>",
            esc(&finish_action),
            esc(&finish_action),
            esc(ctx.csrf),
            esc(ctx.reader_path),
        );
    }
}

fn fallback_card_copy(
    reason: Option<BoardReaderFallbackReason>,
    is_self_post: bool,
    domain: Option<&str>,
    fallback_kicker: &str,
    board_title: &str,
) -> (String, &'static str, &'static str, Option<&'static str>, bool) {
    if is_self_post || reason == Some(BoardReaderFallbackReason::SelfPostEmpty) {
        return (
            board_title.to_owned(),
            "Discussion post",
            "This Hacker News post has no body text — join the discussion below.",
            None,
            false,
        );
    }
    let kicker = domain.map_or_else(|| fallback_kicker.to_owned(), str::to_owned);
    match reason {
        Some(BoardReaderFallbackReason::Pdf) => (
            kicker,
            "PDF document",
            "This story links directly to a PDF document. Open the file in a new tab or join the discussion below.",
            Some("Open PDF"),
            false,
        ),
        Some(BoardReaderFallbackReason::Video) => (
            kicker,
            "Video link",
            "This story links to a video. Watch it on the original site or join the discussion below.",
            Some("Watch Video"),
            false,
        ),
        Some(BoardReaderFallbackReason::Paper) => (
            kicker,
            "Research paper",
            "This story links to a research paper. Open the paper directly or join the discussion below.",
            Some("Open Paper"),
            false,
        ),
        Some(BoardReaderFallbackReason::FetchFailed) => (
            kicker,
            "Couldn\u{2019}t load article right now",
            "The source page didn\u{2019}t respond in time or blocked automated fetching. Try reloading or open the link directly.",
            Some("Open Original"),
            true,
        ),
        Some(
            BoardReaderFallbackReason::Unextractable | BoardReaderFallbackReason::SelfPostEmpty,
        )
        | None => (
            kicker,
            "Readable article text wasn\u{2019}t extracted for this link",
            "This story may point to an interactive page, repository, media file, or a host that blocks article extraction. Open the link directly or join the discussion below.",
            Some("Open Original"),
            false,
        ),
    }
}

fn render_board_reader_fallback_card(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    board_enc: &str,
    ext_enc: &str,
) {
    let view = ctx.view;
    let item = &view.item;
    let ct = board_item_content_type(item);
    let clean_domain = item.domain.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let (kicker, heading, lede, primary_orig_label, show_retry) = fallback_card_copy(
        view.fallback_reason,
        view.is_self_post,
        clean_domain,
        content_type_label(ct),
        &view.board.title,
    );

    let _ = write!(
        out,
        "<section class=\"board-reader-fallback\" aria-label=\"External link fallback\">\n\
           <p class=\"entry-kicker\">{}</p>\n\
           <h3>{}</h3>\n\
           <p class=\"lede\">{}</p>\n",
        esc(&kicker),
        esc(heading),
        esc(lede),
    );
    if let Some(sum) = view.extracted_summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<p class=\"board-fallback-summary\">{}</p>", esc(sum));
    }

    out.push_str("<div class=\"board-fallback-links\">\n");
    let rendered_primary_original = if let Some(orig_label) = primary_orig_label
        && !view.is_self_post
        && let Some(orig_href) = sanitize_non_local_href(&item.url)
    {
        let _ = writeln!(
            out,
            "<a class=\"btn btn-primary\" href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\">{} &#8599;</a>",
            esc(orig_href),
            esc(orig_label),
        );
        true
    } else {
        false
    };
    if show_retry {
        let _ = writeln!(out, "<a class=\"btn\" href=\"{}\">Retry</a>", esc(ctx.reader_path));
    }
    if let Some(disc_href) = item.discussion_url.as_deref().and_then(sanitize_non_local_href) {
        let comments_text = match item.comment_count {
            Some(1) => "Open Hacker News Discussion (1 comment)".to_owned(),
            Some(n) if n >= 0 => format!("Open Hacker News Discussion ({n} comments)"),
            _ => "Open Hacker News Discussion".to_owned(),
        };
        let btn_cls = if rendered_primary_original { "btn" } else { "btn btn-primary" };
        let _ = writeln!(
            out,
            "<a class=\"{btn_cls}\" href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\">{} &#8599;</a>",
            esc(disc_href),
            esc(&comments_text),
        );
    }
    out.push_str("</div>\n<div class=\"board-fallback-triage\">\n");
    render_board_finish_toggle_form(out, ctx, board_enc, ext_enc);
    if item.is_finished
        && let Some(slug) = ctx.rank_slug
        && let Some(eid) = item.linked_entry_id.as_deref()
    {
        let _ = writeln!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"/rank/{}?focus={}\">Compare &rarr;</a>",
            esc(&urlencode(slug)),
            esc(&urlencode(eid)),
        );
    }
    render_board_queue_button(out, board_enc, ext_enc, item, ctx.reader_path, ctx.csrf, false);
    render_board_dismiss_action(
        out,
        board_enc,
        ext_enc,
        item.is_dismissed,
        ctx.return_to,
        ctx.csrf,
        false,
    );
    out.push_str("</div>\n</section>\n");
}

fn render_board_reader_end_card(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    board_enc: &str,
    ext_enc: &str,
) {
    let item = &ctx.view.item;
    let finish_action = format!("/web/boards/{board_enc}/items/{ext_enc}/promote");
    out.push_str("<div class=\"reader-end-card\">\n<div class=\"reader-end-primary\">\n");
    render_board_finish_toggle_form(out, ctx, board_enc, ext_enc);
    if !item.is_finished && ctx.can_rank {
        render_inline_csrf_form(
            out,
            &finish_action,
            ctx.csrf,
            &[("action", "finish"), ("rank_after", "1")],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-ghost reader-end-rank-btn\">Finish &amp; Rank</button>",
        );
    } else if item.is_finished
        && let Some(slug) = ctx.rank_slug
        && let Some(eid) = item.linked_entry_id.as_deref()
    {
        let _ = writeln!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"/rank/{}?focus={}\">Compare &rarr;</a>",
            esc(&urlencode(slug)),
            esc(&urlencode(eid)),
        );
    }
    out.push_str("</div>\n<div class=\"reader-end-secondary\">\n");
    render_board_dismiss_action(
        out,
        board_enc,
        ext_enc,
        item.is_dismissed,
        ctx.return_to,
        ctx.csrf,
        false,
    );
    out.push_str("</div>\n</div>\n");
}

fn render_board_reader_tags(
    out: &mut String,
    tags: &[Tag],
    entry_id_enc: &str,
    reader_path: &str,
    csrf: &str,
) {
    out.push_str("<h3>Tags</h3>\n<div class=\"pill-bar\">\n");
    for tag in tags {
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
            &format!("/web/items/{entry_id_enc}/tags/{}/remove", urlencode(&tag.id)),
            csrf,
            &[("return_to", reader_path)],
            None,
            &remove_btn,
        );
        out.push_str("</span>\n");
    }
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/{}/tags\" class=\"tag-add-form\">\
           <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
           <input type=\"hidden\" name=\"return_to\" value=\"{}\">\
           <input type=\"text\" name=\"name\" required aria-label=\"Add tag\" placeholder=\"Add tag…\" class=\"tag-add-input\">\
           <button type=\"submit\" class=\"btn btn-small\">+ Tag</button>\
         </form>\n</div>\n",
        esc(entry_id_enc),
        esc(csrf),
        esc(reader_path),
    );
}

fn render_board_reader_mobile_tools(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    board_enc: &str,
    ext_enc: &str,
    has_readable_body: bool,
) {
    let item = &ctx.view.item;
    out.push_str("<div class=\"reader-mobile-tools\">\n");
    render_board_dismiss_action(
        out,
        board_enc,
        ext_enc,
        item.is_dismissed,
        ctx.return_to,
        ctx.csrf,
        false,
    );
    if has_readable_body {
        out.push_str(
            "<div class=\"reader-size-controls\">\n\
               <button type=\"button\" class=\"btn btn-small btn-ghost js-font-dec\" title=\"Decrease text size\" aria-label=\"A&minus; Decrease text size\">A&minus;</button>\n\
               <button type=\"button\" class=\"btn btn-small btn-ghost js-font-inc\" title=\"Increase text size\" aria-label=\"A+ Increase text size\">A+</button>\n\
             </div>\n",
        );
    }
    out.push_str(
        "<details class=\"reader-mobile-drawer\">\n\
           <summary class=\"btn btn-small btn-ghost\">Contents &amp; Info</summary>\n\
           <div class=\"reader-mobile-drawer-body\">\n\
             <div class=\"js-reader-mobile-toc-group\" hidden>\n\
               <div class=\"reader-rail-title\">Contents</div>\n\
               <ul class=\"reader-toc reader-mobile-toc-list js-reader-mobile-toc\"></ul>\n\
             </div>\n\
             <div class=\"reader-mobile-drawer-links js-reader-mobile-links\">\n",
    );
    if let Some(disc_href) = item.discussion_url.as_deref().and_then(sanitize_non_local_href) {
        let label = format_hn_discussion_label(item.comment_count);
        let _ = writeln!(
            out,
            "<a class=\"reader-rail-link\" href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\">{} &#8599;</a>",
            esc(disc_href),
            esc(&label),
        );
    }
    if let Some(eid) = item.linked_entry_id.as_deref() {
        let _ = writeln!(
            out,
            "<a class=\"reader-rail-link js-reader-history-link\" href=\"/entries/{}\">History &amp; Lists &rarr;</a>",
            esc(&urlencode(eid)),
        );
        if let Some(slug) = ctx.rank_slug {
            let _ = writeln!(
                out,
                "<a class=\"reader-rail-link js-reader-compare-link\" href=\"/rank/{}?focus={}\">Compare &rarr;</a>",
                esc(&urlencode(slug)),
                esc(&urlencode(eid)),
            );
        }
    }
    out.push_str("</div>\n</div>\n</details>\n</div>\n");
}

fn render_board_reader_rail(
    out: &mut String,
    ctx: &BoardReaderRenderCtx<'_>,
    board_enc: &str,
    ext_enc: &str,
    has_readable_body: bool,
) {
    let item = &ctx.view.item;
    out.push_str(
        "<div class=\"reader-rail\">\n\
           <div class=\"reader-rail-group\" id=\"roche-reader-toc-group\" hidden>\n\
             <div class=\"reader-rail-title\">Contents</div>\n\
             <ul class=\"reader-toc\" id=\"roche-reader-toc\"></ul>\n\
           </div>\n",
    );
    if has_readable_body {
        out.push_str(
            "<div class=\"reader-rail-group\">\n\
               <div class=\"reader-rail-title\">Typography</div>\n\
               <div class=\"reader-size-controls\">\n\
                 <button type=\"button\" class=\"btn btn-small btn-ghost js-font-dec\" title=\"Decrease text size\" aria-label=\"A&minus; Decrease text size\">A&minus;</button>\n\
                 <button type=\"button\" class=\"btn btn-small btn-ghost js-font-inc\" title=\"Increase text size\" aria-label=\"A+ Increase text size\">A+</button>\n\
               </div>\n\
             </div>\n",
        );
    }
    out.push_str(
        "<div class=\"reader-rail-group\">\n\
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
    render_board_star_form(out, board_enc, ext_enc, item, ctx.reader_path, ctx.csrf, &star_btn);
    render_board_queue_button(out, board_enc, ext_enc, item, ctx.reader_path, ctx.csrf, true);
    out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>\n");
    render_board_dismiss_action(
        out,
        board_enc,
        ext_enc,
        item.is_dismissed,
        ctx.return_to,
        ctx.csrf,
        true,
    );
    let _ = writeln!(
        out,
        "<a class=\"rail-icon-btn\" href=\"#roche-masthead\" title=\"Back to top\" aria-label=\"Back to top\">{TOP_ARROW_SVG}</a>\n\
         </div>\n\
         <div class=\"js-reader-rail-links\">"
    );

    if let Some(disc_href) = item.discussion_url.as_deref().and_then(sanitize_non_local_href) {
        let label = format_comments_label(item.comment_count);
        let _ = writeln!(
            out,
            "<a class=\"reader-rail-link\" href=\"{}\" target=\"_blank\" rel=\"noopener noreferrer\">Hacker News ({}) &#8599;</a>",
            esc(disc_href),
            esc(&label),
        );
    }
    if let Some(eid) = item.linked_entry_id.as_deref() {
        let _ = writeln!(
            out,
            "<br><a class=\"reader-rail-link js-reader-history-link\" href=\"/entries/{}\">History &amp; Lists &rarr;</a>",
            esc(&urlencode(eid)),
        );
        if let Some(slug) = ctx.rank_slug {
            let _ = writeln!(
                out,
                "<br><a class=\"reader-rail-link js-reader-compare-link\" href=\"/rank/{}?focus={}\">Compare &rarr;</a>",
                esc(&urlencode(slug)),
                esc(&urlencode(eid)),
            );
        }
    }
    out.push_str("</div>\n</div>\n</div>\n");
}
