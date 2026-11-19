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
        ARCHIVE_BOX_SVG, FlashQuery, NavSection, PageShell, content_type_badge_class,
        content_type_label, esc, redirect_with_flash, render_diary_row_with_tier,
        render_feed_favicon, render_inline_csrf_form, render_page, urlencode,
        web_api_error_response,
    },
    links::{
        WebLinkIcon, collect_web_external_links, extract_entry_tags, load_artist_id_by_lower_name,
        render_creator_links, sanitize_non_local_href,
    },
    views_reader::render_audio_play_button,
};
use crate::{
    db::{
        CollectionGeneratorState, ConsumptionLogView, ContentType, Item, ItemFilter,
        ItemStatusFilter, WANT_TO_READ_COLLECTION_ID,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
};

const WIKI_MARK_SVG: &str = "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\" aria-hidden=\"true\"><path d=\"M12.09 13.119c-.936 1.932-2.217 4.548-2.853 5.728-.616 1.074-1.127.931-1.532.029-1.406-3.321-4.293-9.144-5.651-12.409-.251-.601-.441-.987-.619-1.139-.181-.15-.554-.24-1.122-.271C.103 5.033 0 4.982 0 4.898v-.455l.052-.045c.924-.005 5.401 0 5.401 0l.051.045v.434c0 .119-.075.176-.225.176l-.564.031c-.485.029-.727.164-.727.436 0 .135.053.33.166.601 1.082 2.646 4.818 10.521 4.818 10.521l.136.046 2.411-4.81-.482-1.067-1.658-3.264s-.318-.654-.428-.872c-.728-1.443-.712-1.518-1.447-1.617-.207-.023-.313-.05-.313-.149v-.468l.06-.045h4.292l.113.037v.451c0 .105-.076.15-.227.15l-.308.047c-.792.061-.661.381-.136 1.422l1.582 3.252 1.758-3.504c.293-.64.233-.801.111-.947-.07-.084-.305-.22-.812-.24l-.201-.021c-.052 0-.098-.015-.145-.051-.045-.031-.067-.076-.067-.129v-.427l.061-.045c1.247-.008 4.043 0 4.043 0l.059.045v.436c0 .121-.059.178-.193.178-.646.03-.782.095-1.023.439-.12.186-.375.589-.646 1.039l-2.301 4.273-.065.135 2.792 5.712.17.048 4.396-10.438c.154-.422.129-.722-.064-.895-.197-.172-.346-.273-.857-.295l-.42-.016c-.061 0-.105-.014-.152-.045-.043-.029-.072-.075-.072-.119v-.436l.059-.045h4.961l.041.045v.437c0 .119-.074.18-.209.18-.648.03-1.127.18-1.443.421-.314.255-.557.616-.736 1.067 0 0-4.043 9.258-5.426 12.339-.525 1.007-1.053.917-1.503-.031-.571-1.171-1.773-3.786-2.646-5.711l.053-.036z\"/></svg>";

const SPOTIFY_MARK_SVG: &str = "<svg viewBox=\"0 0 24 24\" fill=\"currentColor\" aria-hidden=\"true\"><path d=\"M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm4.58 14.42a.62.62 0 0 1-.86.2c-2.35-1.44-5.31-1.76-8.8-.96a.62.62 0 1 1-.28-1.22c3.82-.87 7.1-.5 9.74 1.12a.62.62 0 0 1 .2.86zm1.22-2.72a.78.78 0 0 1-1.07.26c-2.69-1.65-6.79-2.13-9.97-1.17a.78.78 0 1 1-.45-1.49c3.63-1.1 8.15-.57 11.23 1.33a.78.78 0 0 1 .26 1.07zm.1-2.83C14.68 8.95 9.37 8.78 6.29 9.71a.94.94 0 1 1-.54-1.8c3.53-1.07 9.4-.87 13.11 1.34a.94.94 0 0 1-.96 1.62z\"/></svg>";

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

pub(super) async fn home_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    match render_home_inner(&service, &session, &flash).await {
        Ok(resp) => resp,
        Err(err) => web_api_error_response(&err, Some(&session.csrf_token)),
    }
}

async fn render_home_inner<F: FeedFetcher>(
    service: &RocheService<F>,
    session: &WebSession,
    flash: &FlashQuery,
) -> Result<Response, ApiError> {
    let unread_filter = ItemFilter {
        status: Some(ItemStatusFilter::Unread),
        inbox_only: Some(true),
        include_content: Some(false),
        per_page: Some(20),
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
        service.sync_status(),
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
    let (artist_links, diary_tiers) = tokio::join!(
        load_artist_id_by_lower_name(service, &home_creators),
        load_diary_tiers(service, &recent_diary),
    );

    let mut body = String::with_capacity(16_384);
    body.push_str(
        "<div class=\"home-grid\">\n<section class=\"home-main\" aria-label=\"Feed Inbox\">\n",
    );
    render_in_progress_section(&mut body, &active_readable_page.items);
    render_unread_summary_section(
        &mut body,
        &unread_page.items,
        sync_status.unread_items_count,
        &session.csrf_token,
    );
    body.push_str("</section>\n<div class=\"home-rail\">\n");

    let wtr_total = collections
        .iter()
        .find(|c| c.id == WANT_TO_READ_COLLECTION_ID)
        .map_or(want_to_read_page.items.len(), |c| c.member_count);
    let other_want_total: usize = collections
        .iter()
        .filter(|c| c.role == crate::db::CollectionRole::Want && c.id != WANT_TO_READ_COLLECTION_ID)
        .map(|c| c.member_count)
        .sum();
    render_want_to_read_section(
        &mut body,
        &want_to_read_page.items,
        wtr_total,
        other_want_total,
        &session.csrf_token,
    );

    for gen_state in &active_generators {
        render_collection_draw_section(&mut body, gen_state, &artist_links, &session.csrf_token);
    }

    render_recent_diary_section(&mut body, &recent_diary, &artist_links, &diary_tiers);
    body.push_str("</div>\n</div>\n");

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
            "<section class=\"rail-block album-sleeve album-sleeve-single\">\n\
               <div>\n\
                 <div class=\"album-kicker\">{} Completed</div>\n\
                 <h2 class=\"album-title\">All {} Works Completed</h2>\n\
                 <p class=\"meta\">{} finished &middot; {} skipped. <a href=\"/lists/{}\">View ranked list &rarr;</a></p>\n\
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

    let ext_links = collect_web_external_links(entry, None);
    let spotify_href =
        ext_links.iter().find(|l| l.icon == WebLinkIcon::Spotify).map(|l| l.href.as_str());
    let wiki_href =
        ext_links.iter().find(|l| l.icon == WebLinkIcon::Wikipedia).map(|l| l.href.as_str());
    let entry_href = format!("/entries/{}", urlencode(&entry.id));

    let _ = writeln!(
        out,
        "<section class=\"rail-block album-sleeve\" aria-label=\"Active draw: {}\">\n<div class=\"sleeve-body\">",
        esc(&state.collection_title),
    );
    render_sleeve_cover(out, entry, &entry_href, spotify_href);
    out.push_str("<div class=\"sleeve-info\">\n<div class=\"sleeve-head-row\">\n<div class=\"album-title-row\">\n");
    let _ = writeln!(
        out,
        "<h2 class=\"album-title\"><a href=\"{}\">{}</a></h2>",
        esc(&entry_href),
        esc(&entry.title),
    );
    if let Some(wk) = wiki_href {
        let _ = writeln!(
            out,
            "<a class=\"wiki-icon-link\" href=\"{}\" target=\"_blank\" rel=\"noopener\" title=\"Wikipedia\" aria-label=\"Open {} on Wikipedia\">{WIKI_MARK_SVG}</a>",
            esc(wk),
            esc(&entry.title),
        );
    }
    let _ = write!(
        out,
        "</div>\n\
         <details class=\"sleeve-skip-details\">\n\
           <summary title=\"Skip this work and draw next\">Skip</summary>\n\
           <form class=\"album-inline-form sleeve-skip-form-wrap\" method=\"post\" action=\"/web/lists/{list}/generator/abandon\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"field mb-sm\">\n\
               <label for=\"skip-notes-{list}\">Skip note (optional)</label>\n\
               <textarea id=\"skip-notes-{list}\" class=\"textarea-compact\" name=\"notes\" placeholder=\"Optional note…\"></textarea>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-small\">Skip &amp; Draw Next</button>\n\
           </form>\n\
         </details>\n\
         </div>\n",
        list = esc(&list_key_enc),
        csrf = esc(csrf),
    );

    if !entry.creators.is_empty() || entry.release_year.is_some() {
        out.push_str("<div class=\"album-artist\">");
        render_creator_links(out, &entry.creators, artist_links, false);
        if let Some(yr) = entry.release_year {
            if entry.creators.is_empty() {
                let _ = write!(out, "{yr}");
            } else {
                let _ = write!(out, " <span class=\"album-year\">({yr})</span>");
            }
        }
        out.push_str("</div>\n");
    }

    let genres = extract_entry_tags(entry);
    if !genres.is_empty() {
        let _ = writeln!(out, "<div class=\"album-genres\">{}</div>", esc(&genres.join(" · ")));
    }

    render_sleeve_rank_row(out, &list_key_enc, csrf);
    out.push_str("</div>\n</div>\n");

    render_sleeve_progress_foot(out, state, &list_key_enc);
    out.push_str("</section>\n");
}

fn render_sleeve_rank_row(out: &mut String, list_key_enc: &str, csrf: &str) {
    let _ = write!(
        out,
        "<div class=\"sleeve-rank-row\">\n\
           <details class=\"sleeve-rank-details\">\n\
             <summary class=\"btn btn-primary btn-small\">Rank</summary>\n\
             <form class=\"album-inline-form\" method=\"post\" action=\"/web/lists/{list}/generator/finish\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <div class=\"field mb-sm\">\n\
                 <label for=\"rank-notes-{list}\">Notes (optional)</label>\n\
                 <textarea id=\"rank-notes-{list}\" class=\"textarea-compact\" name=\"notes\" placeholder=\"Impressions…\"></textarea>\n\
               </div>\n\
               <button type=\"submit\" class=\"btn btn-primary btn-small\">Log &amp; Rank</button>\n\
             </form>\n\
           </details>\n\
         </div>\n",
        list = esc(list_key_enc),
        csrf = esc(csrf),
    );
}

fn render_sleeve_cover(
    out: &mut String,
    entry: &crate::db::CatalogEntry,
    entry_href: &str,
    spotify_href: Option<&str>,
) {
    out.push_str("<div class=\"sleeve-art\">\n");
    let cover_inner =
        entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href).map_or_else(
            || "<div class=\"album-cover-placeholder\" aria-hidden=\"true\">&#9835;</div>".to_owned(),
            |cover| {
                format!(
                    "<img class=\"album-cover\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">",
                    esc(cover)
                )
            },
        );
    if let Some(sp) = spotify_href {
        let _ = writeln!(
            out,
            "<a class=\"album-cover-link\" href=\"{}\" target=\"_blank\" rel=\"noopener\" title=\"Listen on Spotify\" aria-label=\"Listen to {} on Spotify\">\n\
               {cover_inner}\n\
               <span class=\"cover-spotify-badge\" aria-hidden=\"true\">{SPOTIFY_MARK_SVG}</span>\n\
             </a>",
            esc(sp),
            esc(&entry.title),
        );
    } else {
        let _ = writeln!(
            out,
            "<a class=\"album-cover-link\" href=\"{}\" aria-label=\"Open {}\">{cover_inner}</a>",
            esc(entry_href),
            esc(&entry.title),
        );
    }
    out.push_str("</div>\n");
}

fn render_sleeve_progress_foot(
    out: &mut String,
    state: &CollectionGeneratorState,
    list_key_enc: &str,
) {
    let done_count = state.consumed_count + state.abandoned_count;
    let draw_number = done_count + 1;
    let total_pool = state.total_pool.max(1);
    let pct_done = (done_count * 100) / total_pool;
    let _ = writeln!(
        out,
        "<div class=\"sleeve-progress-foot\">\n\
           <div class=\"album-kicker\">\n\
             <a href=\"/lists/{list}\">{title}</a>\n\
             <span class=\"kicker-count\">#{draw_number} of {pool}</span>\n\
           </div>\n\
           <progress class=\"sleeve-progress\" value=\"{done_count}\" max=\"{total_pool}\" aria-label=\"{title} progress: {done_count} of {total_pool} ({pct_done}%)\"></progress>\n\
         </div>",
        list = esc(list_key_enc),
        title = esc(&state.collection_title),
        pool = state.total_pool,
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
         <ul class=\"inbox-list mb-lg\">\n",
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
            if let Some(fid) = item.feed_id.as_deref() {
                let _ = write!(
                    out,
                    "<a class=\"byline-item\" href=\"/inbox?status=all&amp;feed={}\">{}</a>",
                    esc(&urlencode(fid)),
                    esc(ft)
                );
            } else {
                let _ = write!(out, "<span class=\"byline-item\">{}</span>", esc(ft));
            }
        }
        if pct > 0 {
            let _ = write!(out, "<span class=\"byline-item\">{pct}%</span>");
        }
        out.push_str("</div></div><div class=\"inbox-controls\">");
        render_audio_play_button(out, item);
        out.push_str("</div></li>\n");
    }
    out.push_str("</ul>\n");
}

fn render_want_to_read_section(
    out: &mut String,
    items: &[Item],
    total_count: usize,
    other_want_total: usize,
    csrf: &str,
) {
    let _ = writeln!(
        out,
        "<section class=\"rail-block rail-card\">\n\
         <div class=\"section-head\"><h2>Queue ({total_count})</h2>\
         <a class=\"section-action\" href=\"/queue\">All Queues &rarr;</a></div>"
    );
    let _ = write!(
        out,
        "<form method=\"post\" action=\"/web/items/save\" class=\"quick-save-inline\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <input type=\"hidden\" name=\"return_to\" value=\"/\">\n\
           <input type=\"text\" name=\"url\" required placeholder=\"Add URL to queue…\" aria-label=\"Add URL to Queue\">\n\
           <button type=\"submit\" class=\"btn btn-small\">+ Add</button>\n\
         </form>\n",
        csrf = esc(csrf),
    );
    if items.is_empty() {
        out.push_str(
            "<p class=\"empty\">Queue is clear. Paste a URL above or promote items from your inbox.</p>\n",
        );
    } else {
        out.push_str("<ul class=\"inbox-list\">\n");
        for item in items.iter().take(6) {
            let _ = write!(
                out,
                "<li class=\"inbox-row\"><div class=\"inbox-main\">\
                 <div class=\"inbox-title\"><a href=\"/reader/{id}\">{title}</a></div>\
                 <div class=\"item-byline\">",
                id = esc(&urlencode(&item.id)),
                title = esc(&item.title),
            );
            if item.content_type != ContentType::Article {
                let _ = write!(
                    out,
                    "<span class=\"badge badge-kind\">{}</span>",
                    esc(content_type_label(item.content_type))
                );
            }
            if let Some(author) = item.author.as_deref().filter(|s| !s.is_empty()) {
                let _ = write!(out, "<span class=\"byline-item\">{}</span>", esc(author));
            }
            if let Some(pub_at) = item.published_at {
                let _ =
                    write!(out, "<span class=\"byline-item\">{}</span>", pub_at.format("%b %d"));
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
    if other_want_total > 0 {
        let _ = writeln!(
            out,
            "<p class=\"meta mt-sm mb-0\">+<em>{other_want_total}</em> in other <a href=\"/queue\">Want queues</a>.</p>"
        );
    }
    out.push_str("</section>\n");
}

fn render_unread_summary_section(out: &mut String, items: &[Item], total_unread: i64, csrf: &str) {
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
    for item in items.iter().take(20) {
        render_home_inbox_row(out, item, csrf);
    }
    out.push_str("</ul>\n");
}

fn render_home_inbox_row(out: &mut String, item: &Item, csrf: &str) {
    let id_enc = urlencode(&item.id);
    let (star_glyph, star_pressed, star_cls) = if item.is_favorite {
        ("&#9733;", "true", "btn btn-small btn-ghost inbox-star-btn is-starred")
    } else {
        ("&#9734;", "false", "btn btn-small btn-ghost inbox-star-btn")
    };
    let star_btn = format!(
        "<button type=\"submit\" class=\"{star_cls}\" title=\"Toggle Star\" aria-label=\"Star {}\" aria-pressed=\"{star_pressed}\"><span aria-hidden=\"true\">{star_glyph}</span></button>",
        esc(&item.title),
    );

    out.push_str("<li class=\"inbox-row\"><div class=\"inbox-star-col\">");
    render_inline_csrf_form(
        out,
        &format!("/web/items/{id_enc}/star"),
        csrf,
        &[("return_to", "/")],
        None,
        &star_btn,
    );
    out.push_str("</div>");

    if let Some(thumb) = item.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
        let _ = write!(
            out,
            "<a class=\"inbox-thumb-link\" href=\"/reader/{id_enc}\" tabindex=\"-1\" aria-hidden=\"true\">\
               <img class=\"inbox-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">\
             </a>",
            esc(thumb),
        );
    }

    let _ = write!(
        out,
        "<div class=\"inbox-main\">\
         <div class=\"inbox-title\"><a href=\"/reader/{id_enc}\">{}</a></div>\
         <div class=\"item-byline\">",
        esc(&item.title),
    );
    if item.content_type != ContentType::Article {
        let _ = write!(
            out,
            "<span class=\"badge badge-kind{}\">{}</span>",
            content_type_badge_class(item.content_type),
            esc(content_type_label(item.content_type)),
        );
    }
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
    if let Some(pub_at) = item.published_at {
        let _ = write!(out, "<span class=\"byline-item\">{}</span>", pub_at.format("%b %d"));
    }
    out.push_str("</div></div><div class=\"inbox-controls inbox-controls-subtle\">");
    render_audio_play_button(out, item);

    let queue_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\" title=\"Add to Queue\" aria-label=\"Add {} to Queue\">+ Queue</button>",
        esc(&item.title),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/entries/{id_enc}/list-add"),
        csrf,
        &[("list_slug_or_id", WANT_TO_READ_COLLECTION_ID), ("return_to", "/")],
        None,
        &queue_btn,
    );

    out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>");

    let arch_btn = format!(
        "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-icon\" title=\"Archive (mark read)\" aria-label=\"Archive {}\">{ARCHIVE_BOX_SVG}</button>",
        esc(&item.title),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/items/{id_enc}/archive"),
        csrf,
        &[("return_to", "/")],
        None,
        &arch_btn,
    );
    out.push_str("</div></li>\n");
}

async fn load_diary_tiers<F: FeedFetcher>(
    service: &RocheService<F>,
    diary: &[ConsumptionLogView],
) -> HashMap<String, char> {
    let mut slugs: Vec<&'static str> = diary
        .iter()
        .filter(|d| d.status == crate::db::ConsumptionStatus::Consumed)
        .filter_map(|d| d.entry.kind.default_list_slug())
        .collect();
    slugs.sort_unstable();
    slugs.dedup();

    let mut tiers = HashMap::new();
    for slug in slugs {
        if let Ok(ranked) = service.catalog().get_ranked_collection(slug, false).await {
            for row in ranked.rows {
                if let Some(t) = row.tier {
                    tiers.entry(row.entry.id).or_insert(t);
                }
            }
        }
    }
    tiers
}

fn render_recent_diary_section(
    out: &mut String,
    diary: &[ConsumptionLogView],
    artist_links: &HashMap<String, String>,
    diary_tiers: &HashMap<String, char>,
) {
    out.push_str(
        "<section class=\"rail-block\">\n\
         <div class=\"section-head\"><h2>Recent Diary</h2>\
         <a class=\"section-action\" href=\"/diary\">Full Diary &rarr;</a></div>\n",
    );
    if diary.is_empty() {
        out.push_str(
            "<p class=\"empty\">No media logged yet. <a href=\"/queue\">Log a work or add to a Queue</a>.</p>\n",
        );
    } else {
        for row in diary {
            let tier = if row.status == crate::db::ConsumptionStatus::Consumed {
                diary_tiers.get(&row.entry.id).copied()
            } else {
                None
            };
            render_diary_row_with_tier(out, row, artist_links, false, tier);
        }
    }
    out.push_str("</section>\n");
}

pub(super) async fn post_generator_toggle<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<GeneratorToggleForm>,
) -> Response {
    let enabled = matches!(form.enabled.trim(), "true" | "1" | "on");
    match service.catalog().set_collection_generator_enabled(&slug, enabled).await {
        Ok(state) => {
            let msg = if enabled {
                format!("Enabled active draw for '{}'", state.collection_title)
            } else {
                format!("Paused active draw for '{}'", state.collection_title)
            };
            let to_list = form.redirect_list.as_deref().is_some_and(|v| v == "true" || v == "1");
            if to_list {
                let list_key = state.collection_slug.as_deref().unwrap_or(&state.collection_id);
                redirect_with_flash(&format!("/lists/{}", urlencode(list_key)), "notice", &msg)
            } else {
                redirect_with_flash("/", "notice", &msg)
            }
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(super) async fn post_generator_finish<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<GeneratorNotesForm>,
) -> Response {
    match service.catalog().finish_active_generator_entry(&slug, form.notes.as_deref()).await {
        Ok((completed, next_state)) => {
            let list_key =
                next_state.collection_slug.as_deref().unwrap_or(&next_state.collection_id);
            let rank_slug =
                if service.catalog().can_rank_collection(list_key, false).await.unwrap_or(false) {
                    Some(list_key.to_owned())
                } else {
                    service.catalog().rank_target_after_consumption(&completed).await.ok().flatten()
                };
            if let Some(target_slug) = rank_slug {
                let notice = format!(
                    "Finished '{}' — rank it in {}",
                    completed.title, next_state.collection_title
                );
                redirect_with_flash(
                    &format!(
                        "/rank/{}?focus={}",
                        urlencode(&target_slug),
                        urlencode(&completed.id),
                    ),
                    "notice",
                    &notice,
                )
            } else {
                let next_part = next_state
                    .current_entry
                    .as_ref()
                    .map_or_else(String::new, |n| format!(" · Next draw: {}", n.title));
                let notice = format!("Logged '{}' to Diary{next_part}", completed.title);
                redirect_with_flash("/", "notice", &notice)
            }
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(super) async fn post_generator_abandon<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<GeneratorNotesForm>,
) -> Response {
    match service.catalog().abandon_active_generator_entry(&slug, form.notes.as_deref()).await {
        Ok((abandoned, next_state)) => {
            let next_part = next_state
                .current_entry
                .as_ref()
                .map_or_else(String::new, |n| format!(" · Next draw: {}", n.title));
            let notice = format!("Abandoned '{}'{next_part}", abandoned.title);
            redirect_with_flash("/", "notice", &notice)
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}
