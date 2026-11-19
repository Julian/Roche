use std::{collections::HashMap, fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    auth::WebSession,
    html::{
        CreatorYearStyle, FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _,
        content_type_label, esc, parse_form_bool, redirect_with_flash, render_creator_year_meta,
        render_diary_row, render_inline_csrf_form, render_page, urlencode,
    },
    links::{
        collect_web_external_links, extract_entry_tags, load_artist_id_by_lower_name,
        sanitize_non_local_href,
    },
    views_reader::render_audio_play_button,
};
use crate::{
    db::{CollectionGeneratorState, ConsumptionLogView, Item, ItemFilter},
    domain::{CollectionRole, ContentType, ItemStatusFilter},
    service::{AppError, RocheService},
};

#[derive(Debug, Deserialize)]
pub(super) struct GeneratorToggleForm {
    pub enabled: String,
    #[serde(default)]
    pub redirect_list: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct GeneratorNotesForm {
    #[serde(default)]
    pub notes: Option<String>,
}

pub(super) async fn home_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Result<Response, WebError> {
    render_home_inner(&service, &session, &flash).await.web_err(&session)
}

async fn render_home_inner(
    service: &RocheService,
    session: &WebSession,
    flash: &FlashQuery,
) -> Result<Response, AppError> {
    let unread_filter = ItemFilter {
        status: Some(ItemStatusFilter::Unread),
        inbox_only: Some(true),
        include_content: Some(false),
        per_page: Some(12),
        ..ItemFilter::default()
    };
    let readable_filter = ItemFilter {
        status: Some(ItemStatusFilter::Unread),
        include_content: Some(false),
        per_page: Some(20),
        ..ItemFilter::default()
    };
    let wtr_filter = ItemFilter {
        collection_id: Some(crate::db::WANT_TO_READ_COLLECTION_ID.to_owned()),
        include_content: Some(false),
        per_page: Some(8),
        ..ItemFilter::default()
    };
    let catalog = service.catalog();
    let sync = service.sync();
    let (
        active_generators_res,
        unread_page_res,
        active_readable_page_res,
        want_to_read_page_res,
        sync_status_res,
        recent_diary_res,
        collections_res,
    ) = tokio::join!(
        catalog.list_collection_generators(true),
        service.items().query().with_filter(unread_filter).list_page(),
        service.items().query().with_filter(readable_filter).list_page(),
        service.items().query().with_filter(wtr_filter).list_page(),
        sync.status(),
        catalog.list_diary(None, None, 8),
        catalog.list_collections(true, false),
    );
    let active_generators = active_generators_res?;
    let unread_page = unread_page_res?;
    let active_readable_page = active_readable_page_res?;
    let want_to_read_page = want_to_read_page_res?;
    let sync_status = sync_status_res?;
    let recent_diary = recent_diary_res?;
    let collections = collections_res?;

    let mut home_creators: Vec<String> = active_generators
        .iter()
        .filter_map(|g| g.current_entry.as_ref())
        .flat_map(|e| e.creators.iter().cloned())
        .collect();
    home_creators.extend(recent_diary.iter().flat_map(|d| d.entry.creators.iter().cloned()));
    let artist_links = load_artist_id_by_lower_name(service, &home_creators).await;

    let mut body = String::with_capacity(10_240);
    render_home_quick_actions_bar(&mut body, &session.csrf_token);
    for gen_state in &active_generators {
        render_collection_draw_section(&mut body, gen_state, &artist_links, &session.csrf_token);
    }
    render_in_progress_section(&mut body, &active_readable_page.items);
    let wtr_total = collections
        .iter()
        .find(|c| c.id == crate::db::WANT_TO_READ_COLLECTION_ID)
        .map_or(want_to_read_page.items.len(), |c| c.member_count);
    render_want_to_read_section(
        &mut body,
        &want_to_read_page.items,
        wtr_total,
        &session.csrf_token,
    );

    let other_want_total: usize = collections
        .iter()
        .filter(|c| c.role == CollectionRole::Want && c.id != crate::db::WANT_TO_READ_COLLECTION_ID)
        .map(|c| c.member_count)
        .sum();
    if other_want_total > 0 {
        let _ = writeln!(
            body,
            "<div class=\"section-head\"><h2>Up Next Queues ({other_want_total})</h2>\
             <a class=\"section-action\" href=\"/queue\">Manage Queues &rarr;</a></div>\n\
             <p class=\"meta\">You have <em>{other_want_total}</em> queued movies, books, albums, or games in your Want lists.</p>"
        );
    }

    render_unread_summary_section(&mut body, &unread_page.items, sync_status.unread_items_count);
    render_recent_diary_section(&mut body, &recent_diary, &artist_links);

    Ok(render_page(&PageShell {
        title: "Roche",
        active: NavSection::Home,
        csrf_token: &session.csrf_token,
        flash,
        is_refreshing: sync_status.is_refreshing,
        body_html: &body,
    })
    .into_response())
}

pub(in crate::web) fn render_collection_draw_section(
    out: &mut String,
    state: &CollectionGeneratorState,
    artist_links: &HashMap<String, String>,
    csrf: &str,
) {
    let list_key = state.collection_slug.as_deref().unwrap_or(&state.collection_id);
    let list_key_enc = urlencode(list_key);
    if !state.enabled {
        return;
    }

    let Some(entry) = &state.current_entry else {
        let _ = write!(
            out,
            "<section class=\"album-sleeve album-sleeve-single\">\n\
               <div>\n\
                 <div class=\"album-kicker\">{} Completed</div>\n\
                 <h2 class=\"album-title\">All {} Works Completed</h2>\n\
                 <p class=\"meta\">{} finished &middot; {} abandoned. <a href=\"/lists/{}\">View ranked list &rarr;</a></p>\n\
               </div>\n\
             </section>\n",
            esc(&state.collection_title),
            state.total_pool,
            state.consumed_count,
            state.abandoned_count,
            esc(&list_key_enc),
        );
        return;
    };

    out.push_str("<section class=\"album-sleeve\">\n<div>\n");
    if let Some(cover) = entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
        let _ = writeln!(
            out,
            "<img class=\"album-cover\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">",
            esc(cover),
        );
    } else {
        out.push_str("<div class=\"album-cover-placeholder\" aria-hidden=\"true\">&#9835;</div>\n");
    }
    out.push_str("</div>\n<div>\n");

    let draw_number = state.consumed_count + state.abandoned_count + 1;
    let _ = writeln!(
        out,
        "<div class=\"album-kicker\"><a href=\"/lists/{}\">{}</a> &middot; #{} of {} ({} remaining)</div>",
        esc(&list_key_enc),
        esc(&state.collection_title),
        draw_number,
        state.total_pool,
        state.remaining_count
    );
    let _ = writeln!(
        out,
        "<h2 class=\"album-title\"><a href=\"/entries/{}\">{}</a></h2>",
        esc(&urlencode(&entry.id)),
        esc(&entry.title)
    );

    render_creator_year_meta(
        out,
        &entry.creators,
        entry.release_year,
        artist_links,
        CreatorYearStyle::DrawArtistBlock,
    );

    let genres = extract_entry_tags(entry);
    if !genres.is_empty() {
        let _ = writeln!(out, "<div class=\"album-genres\">{}</div>", esc(&genres.join(" · ")));
    }

    let ext_links = collect_web_external_links(entry, None);
    if !ext_links.is_empty() {
        out.push_str("<div class=\"album-actions\">\n");
        for link in ext_links {
            let _ = writeln!(
                out,
                "<a class=\"btn btn-small\" href=\"{}\" target=\"_blank\" rel=\"noopener\">{}<span>{}</span></a>",
                esc(&link.href),
                link.icon.svg(),
                esc(&link.label)
            );
        }
        out.push_str("</div>\n");
    }

    render_draw_mutation_forms(out, &list_key_enc, csrf);
    out.push_str("</div>\n</section>\n");
}

fn render_draw_mutation_forms(out: &mut String, list_key_enc: &str, csrf: &str) {
    let _ = write!(
        out,
        "<details class=\"album-inline-details\">\n\
           <summary class=\"btn btn-primary\">Finish &amp; Rank</summary>\n\
           <form class=\"album-inline-form\" method=\"post\" action=\"/web/lists/{list}/generator/finish\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"field mb-sm\">\n\
               <label for=\"finish-notes-{list}\">Notes (optional)</label>\n\
               <textarea id=\"finish-notes-{list}\" name=\"notes\" placeholder=\"Impressions, standout moments...\"></textarea>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Log Finished &amp; Rank</button>\n\
           </form>\n\
         </details>\n\
         <details class=\"album-inline-details\">\n\
           <summary class=\"btn btn-ghost btn-small\">Abandon</summary>\n\
           <form class=\"album-inline-form\" method=\"post\" action=\"/web/lists/{list}/generator/abandon\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"field mb-sm\">\n\
               <label for=\"abandon-notes-{list}\">Why abandon? (optional)</label>\n\
               <textarea id=\"abandon-notes-{list}\" name=\"notes\" placeholder=\"Optional note...\"></textarea>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn\">Abandon &amp; Draw Next</button>\n\
           </form>\n\
         </details>\n",
        list = esc(list_key_enc),
        csrf = esc(csrf),
    );
}

fn render_in_progress_section(out: &mut String, unread_items: &[Item]) {
    let in_progress: Vec<&Item> = unread_items
        .iter()
        .filter(|i| {
            i.read_progress >= 0.03 || i.content_type == ContentType::Audio || i.audio_url.is_some()
        })
        .take(4)
        .collect();
    if in_progress.is_empty() {
        return;
    }

    out.push_str(
        "<div class=\"section-head\"><h2>Continue Reading &amp; Listening</h2>\
         <a class=\"section-action\" href=\"/inbox\">Inbox &rarr;</a></div>\n\
         <ul class=\"inbox-list\">\n",
    );
    for item in in_progress {
        #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
        let pct = (item.read_progress.clamp(0.0, 1.0) * 100.0).round() as u32;
        let _ = write!(
            out,
            "<li class=\"inbox-row\"><div class=\"inbox-main\">\
             <div class=\"inbox-title\"><a href=\"/reader/{}\">{}</a></div>\
             <div class=\"item-byline\">",
            esc(&urlencode(&item.id)),
            esc(&item.title),
        );
        if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
            let _ = write!(out, "<span>{}</span>", esc(ft));
        }
        if pct > 0 {
            let _ = write!(out, "<span>{pct}%</span>");
        }
        out.push_str("</div></div><div class=\"inbox-controls\">");
        render_audio_play_button(out, item);
        out.push_str("</div></li>\n");
    }
    out.push_str("</ul>\n");
}

fn render_home_quick_actions_bar(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<div class=\"quick-save-bar\">\n\
           <form method=\"post\" action=\"/web/items/save\" class=\"quick-save-form\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <input type=\"hidden\" name=\"return_to\" value=\"/\">\n\
             <input type=\"text\" name=\"url\" required placeholder=\"Save article, PDF, arXiv, or video URL to Want to Read...\" aria-label=\"URL to save to Want to Read\">\n\
             <button type=\"submit\" class=\"btn btn-primary btn-small\">+ Want to Read</button>\n\
           </form>\n",
        csrf = esc(csrf),
    );
    render_inline_csrf_form(
        out,
        "/web/feeds/refresh-all",
        csrf,
        &[("return_to", "/")],
        None,
        "<button type=\"submit\" class=\"btn btn-small\"><span aria-hidden=\"true\">&#8635;</span> Refresh Feeds</button>",
    );
    out.push_str("\n</div>\n");
}

fn render_want_to_read_section(out: &mut String, items: &[Item], total_count: usize, csrf: &str) {
    let _ = writeln!(
        out,
        "<div class=\"section-head\"><h2>Want to Read ({total_count})</h2>\
         <a class=\"section-action\" href=\"/queue\">Full Queue &rarr;</a></div>"
    );
    if items.is_empty() {
        out.push_str(
            "<p class=\"empty\">Your reading list is clear. Paste a link or PDF URL above, or share from Android to save for later.</p>\n",
        );
        return;
    }

    out.push_str("<ul class=\"inbox-list\">\n");
    for item in items.iter().take(6) {
        let _ = write!(
            out,
            "<li class=\"inbox-row\"><div class=\"inbox-main\">\
             <div class=\"inbox-title\"><a href=\"/reader/{id}\">{title}</a></div>\
             <div class=\"item-byline\"><span class=\"badge\">{ctype}</span>",
            id = esc(&urlencode(&item.id)),
            title = esc(&item.title),
            ctype = esc(content_type_label(item.content_type)),
        );
        if let Some(author) = item.author.as_deref().filter(|s| !s.is_empty()) {
            let _ = write!(out, "<span>{}</span>", esc(author));
        }
        for tag_name in &item.tag_names.0 {
            let _ = write!(out, "<span class=\"badge\">#{}</span>", esc(tag_name));
        }
        out.push_str("</div></div><div class=\"inbox-controls\">");
        render_audio_play_button(out, item);
        let action = format!("/web/items/{}/finish", urlencode(&item.id));
        render_inline_csrf_form(
            out,
            &action,
            csrf,
            &[("return_to", "/")],
            None,
            &format!(
                "<button type=\"submit\" class=\"btn btn-small btn-ghost\" aria-label=\"Mark {} as done\"><span aria-hidden=\"true\">&#10003;</span> Done</button>",
                esc(&item.title),
            ),
        );
        out.push_str("</div></li>\n");
    }
    out.push_str("</ul>\n");
}

fn render_unread_summary_section(out: &mut String, items: &[Item], total_unread: i64) {
    let _ = writeln!(
        out,
        "<div class=\"section-head\"><h2>Feed Inbox ({total_unread})</h2>\
         <a class=\"section-action\" href=\"/inbox\">Open Feed Inbox &rarr;</a></div>"
    );
    if items.is_empty() {
        out.push_str(
            "<p class=\"empty\">Feed Inbox clear. <a href=\"/feeds\">Manage subscribed feeds</a>.</p>\n",
        );
        return;
    }

    out.push_str("<ul class=\"inbox-list\">\n");
    for item in items.iter().take(8) {
        let _ = write!(
            out,
            "<li class=\"inbox-row\"><div class=\"inbox-main\">\
             <div class=\"inbox-title\"><a href=\"/reader/{}\">{}</a></div>\
             <div class=\"item-byline\"><span class=\"badge\">{}</span>",
            esc(&urlencode(&item.id)),
            esc(&item.title),
            esc(content_type_label(item.content_type)),
        );
        if let Some(ft) = item.feed_title.as_deref().filter(|s| !s.is_empty()) {
            let _ = write!(out, "<span>{}</span>", esc(ft));
        }
        if let Some(pub_at) = item.published_at {
            let _ = write!(out, "<span>{}</span>", pub_at.format("%b %d"));
        }
        out.push_str("</div></div><div class=\"inbox-controls\">");
        render_audio_play_button(out, item);
        out.push_str("</div></li>\n");
    }
    out.push_str("</ul>\n");
}

fn render_recent_diary_section(
    out: &mut String,
    diary: &[ConsumptionLogView],
    artist_links: &HashMap<String, String>,
) {
    out.push_str(
        "<div class=\"section-head\"><h2>Recent Diary</h2>\
         <a class=\"section-action\" href=\"/diary\">Full Diary &rarr;</a></div>\n",
    );
    if diary.is_empty() {
        out.push_str(
            "<p class=\"empty\">No media logged yet. <a href=\"/queue\">Log a work or add to a Want queue</a>.</p>\n",
        );
        return;
    }

    for row in diary {
        render_diary_row(out, row, artist_links, false);
    }
}

pub(super) async fn post_generator_toggle(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<GeneratorToggleForm>,
) -> Result<Response, WebError> {
    let enabled = parse_form_bool(Some(&form.enabled));
    let state = service
        .catalog()
        .set_collection_generator_enabled(&slug, enabled)
        .await
        .web_err(&session)?;
    let msg = if enabled {
        format!("Enabled active draw for '{}'", state.collection_title)
    } else {
        format!("Paused active draw for '{}'", state.collection_title)
    };
    let to_list = parse_form_bool(form.redirect_list.as_deref());
    if to_list {
        let list_key = state.collection_slug.as_deref().unwrap_or(&state.collection_id);
        Ok(redirect_with_flash(&format!("/lists/{}", urlencode(list_key)), "notice", &msg))
    } else {
        Ok(redirect_with_flash("/", "notice", &msg))
    }
}

pub(super) async fn post_generator_finish(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<GeneratorNotesForm>,
) -> Result<Response, WebError> {
    let (completed, next_state) = service
        .catalog()
        .finish_active_generator_entry(&slug, form.notes.as_deref())
        .await
        .web_err(&session)?;
    let list_key = next_state.collection_slug.as_deref().unwrap_or(&next_state.collection_id);
    let rank_slug = service
        .catalog()
        .rank_target_after_generator_finish(list_key, &completed)
        .await
        .ok()
        .flatten();
    if let Some(target_slug) = rank_slug {
        let notice =
            format!("Finished '{}' — rank it in {}", completed.title, next_state.collection_title);
        Ok(redirect_with_flash(
            &format!("/rank/{}?focus={}", urlencode(&target_slug), urlencode(&completed.id)),
            "notice",
            &notice,
        ))
    } else {
        let next_part = next_state
            .current_entry
            .as_ref()
            .map_or_else(String::new, |n| format!(" · Next draw: {}", n.title));
        let notice = format!("Logged '{}' to Diary{next_part}", completed.title);
        Ok(redirect_with_flash("/", "notice", &notice))
    }
}

pub(super) async fn post_generator_abandon(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<GeneratorNotesForm>,
) -> Result<Response, WebError> {
    let (abandoned, next_state) = service
        .catalog()
        .abandon_active_generator_entry(&slug, form.notes.as_deref())
        .await
        .web_err(&session)?;
    let next_part = next_state
        .current_entry
        .as_ref()
        .map_or_else(String::new, |n| format!(" · Next draw: {}", n.title));
    let notice = format!("Abandoned '{}'{next_part}", abandoned.title);
    Ok(redirect_with_flash("/", "notice", &notice))
}
