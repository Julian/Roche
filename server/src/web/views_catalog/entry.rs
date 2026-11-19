use std::{collections::HashMap, fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};

use super::{EntryAddToListForm, EntryLogForm, EntryMergeForm, parse_consumption_status};
use crate::{
    db::{
        CatalogEntry, CollectionMode, CollectionRole, CollectionSummary, ConsumptionStatus,
        EntryDetailView, EntryKind,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
    web::{
        artist_enrich::spawn_artist_enrichment_if_needed,
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, redirect_with_flash, render_page,
            safe_local_redirect, urlencode, web_api_error_response,
        },
        links::{
            collect_web_external_links, extract_entry_tags, load_artist_id_by_lower_name,
            render_creator_links, sanitize_non_local_href,
        },
    },
};

pub(in crate::web) async fn entry_detail_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let detail = match service.catalog().get_entry_detail(&id).await {
        Ok(Some(d)) => d,
        Ok(None) => {
            return web_api_error_response(
                &ApiError::not_found(format!("Entry '{id}' not found")),
                Some(&session.csrf_token),
            );
        }
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };
    spawn_artist_enrichment_if_needed(&service, &detail);

    let mut relevant_creators: Vec<String> = detail.entry.creators.clone();
    relevant_creators.extend(detail.children.iter().flat_map(|c| c.creators.iter().cloned()));
    relevant_creators.extend(detail.parents.iter().flat_map(|p| p.creators.iter().cloned()));
    let artist_links = load_artist_id_by_lower_name(&service, &relevant_creators).await;
    let appearances = load_entry_ranked_appearances(&service, &detail).await;
    let manual_lists = service
        .catalog()
        .list_collections(false, false)
        .await
        .unwrap_or_default()
        .into_iter()
        .filter(|c| {
            c.mode == CollectionMode::Manual && !c.has_generator && c.role == CollectionRole::List
        })
        .collect::<Vec<_>>();

    // Check if entry also has an inbox reader view
    let has_reader_item = service.items().get(&detail.entry.id).await.is_ok();

    let mut body = String::with_capacity(8_192);
    render_entry_detail_body(
        &mut body,
        &detail,
        &appearances,
        &manual_lists,
        &artist_links,
        has_reader_item,
        &session.csrf_token,
    );

    render_page(&PageShell {
        title: &detail.entry.title,
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
}

struct EntryListAppearance {
    slug: String,
    title: String,
    rank: usize,
    total: usize,
    tier: Option<char>,
}

async fn load_entry_ranked_appearances<F: FeedFetcher>(
    service: &RocheService<F>,
    detail: &EntryDetailView,
) -> Vec<EntryListAppearance> {
    let mut candidate_slugs: Vec<String> = Vec::new();
    if let Some(default_slug) = detail.entry.kind.default_list_slug() {
        candidate_slugs.push(default_slug.to_owned());
    }
    for parent in &detail.parents {
        if parent.kind == EntryKind::Collection {
            candidate_slugs.push(parent.id.clone());
        }
    }

    let mut out = Vec::new();
    let mut seen_col_ids = std::collections::HashSet::new();
    for key in candidate_slugs {
        // Only show genuine global Lists (`List` / `System`), never `Want`, `Tag`, or `Feed` collections.
        if let Ok(ranked) = service.catalog().get_ranked_collection(&key, false).await
            && matches!(ranked.collection.role, CollectionRole::List | CollectionRole::System)
            && seen_col_ids.insert(ranked.collection.id.clone())
            && let Some(found) = ranked.rows.iter().find(|r| r.entry.id == detail.entry.id)
        {
            out.push(EntryListAppearance {
                slug: ranked.collection.slug.unwrap_or(ranked.collection.id),
                title: ranked.collection.title,
                rank: found.rank,
                total: ranked.rows.len(),
                tier: found.tier,
            });
        }
    }
    out
}

fn render_entry_detail_body(
    out: &mut String,
    detail: &EntryDetailView,
    appearances: &[EntryListAppearance],
    manual_lists: &[CollectionSummary],
    artist_links: &HashMap<String, String>,
    has_reader_item: bool,
    csrf: &str,
) {
    let entry = &detail.entry;
    let artwork_url = entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href);
    if let Some(thumb) = artwork_url {
        let _ = write!(
            out,
            "<div class=\"entry-hero\">\n\
               <img class=\"entry-artwork\" src=\"{}\" alt=\"{}\" loading=\"lazy\" referrerpolicy=\"no-referrer\">\n\
               <div class=\"entry-hero-main\">\n",
            esc(thumb),
            esc(&entry.title),
        );
    }

    let _ = writeln!(out, "<h2>{}</h2>", esc(&entry.title));
    render_entry_facts(out, entry, appearances, artist_links);

    if has_reader_item {
        let _ = writeln!(
            out,
            "<p><a class=\"btn btn-primary btn-small\" href=\"/reader/{}\">Open in Reader &rarr;</a></p>",
            esc(&urlencode(&entry.id))
        );
    }

    if let Some(sum) = entry.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<p class=\"meta\">{}</p>", esc(sum));
    }

    render_entry_external_links(out, entry);
    if artwork_url.is_some() {
        out.push_str("</div>\n</div>\n");
    }

    render_entry_relations(out, detail);
    render_entry_diary_and_actions(out, detail, manual_lists, csrf);
}

fn render_entry_facts(
    out: &mut String,
    entry: &CatalogEntry,
    appearances: &[EntryListAppearance],
    artist_links: &HashMap<String, String>,
) {
    out.push_str("<dl class=\"facts\">\n");
    let _ = writeln!(out, "<dt>Kind</dt><dd>{}</dd>", esc(entry.kind.display_label()));
    if !entry.creators.is_empty() {
        out.push_str("<dt>By</dt><dd>");
        render_creator_links(out, &entry.creators, artist_links, false);
        out.push_str("</dd>\n");
    }
    if let Some(yr) = entry.release_year {
        let _ = writeln!(out, "<dt>Year</dt><dd>{yr}</dd>");
    }
    let genres = extract_entry_tags(entry);
    if !genres.is_empty() {
        let _ = writeln!(out, "<dt>Genres</dt><dd>{}</dd>", esc(&genres.join(" · ")));
    }
    if !appearances.is_empty() {
        out.push_str("<dt>Rankings</dt><dd><ul class=\"plain\">");
        for app in appearances {
            let tier_badge = app.tier.map_or_else(String::new, |t| {
                format!(" &middot; <strong class=\"tier-{t}\">Tier {t}</strong>")
            });
            let compare_btn = if app.total >= 2 {
                format!(
                    " <a class=\"btn btn-small btn-ghost ml-sm\" href=\"/rank/{}?focus={}\">Compare</a>",
                    esc(&urlencode(&app.slug)),
                    esc(&urlencode(&entry.id)),
                )
            } else {
                String::new()
            };
            let _ = write!(
                out,
                "<li><a href=\"/lists/{slug}\">{title}</a>: <strong>#{rank}</strong> of {total}{tier_badge}{compare_btn}</li>",
                slug = esc(&urlencode(&app.slug)),
                title = esc(&app.title),
                rank = app.rank,
                total = app.total,
            );
        }
        out.push_str("</ul></dd>\n");
    }
    out.push_str("</dl>\n");
}

fn render_entry_external_links(out: &mut String, entry: &CatalogEntry) {
    let links = collect_web_external_links(entry, Some("Open Source"));
    if links.is_empty() {
        return;
    }
    out.push_str("<ul class=\"external-links\">\n");
    for link in links {
        let _ = writeln!(
            out,
            "<li><a href=\"{}\" target=\"_blank\" rel=\"noopener\">{}<span>{}</span></a></li>",
            esc(&link.href),
            link.icon.svg(),
            esc(&link.label)
        );
    }
    out.push_str("</ul>\n");
}

fn render_entry_relations(out: &mut String, detail: &EntryDetailView) {
    if !detail.children.is_empty() {
        let heading = if detail.entry.kind == EntryKind::Artist { "Works" } else { "Contains" };
        let _ =
            writeln!(out, "<h3>{heading} ({})</h3>\n<ul class=\"plain\">", detail.children.len());
        for child in detail.children.iter().take(100) {
            let yr = child.release_year.map_or_else(String::new, |y| format!(" ({y})"));
            let _ = writeln!(
                out,
                "<li><a href=\"/entries/{}\">{}</a>{yr} <span class=\"badge\">{}</span></li>",
                esc(&urlencode(&child.id)),
                esc(&child.title),
                esc(child.kind.display_label()),
            );
        }
        out.push_str("</ul>\n");
    }

    if !detail.parents.is_empty() {
        out.push_str("<h3>Appears In</h3>\n<ul class=\"plain\">");
        for parent in &detail.parents {
            let _ = writeln!(
                out,
                "<li><a href=\"/entries/{}\">{}</a> <span class=\"badge\">{}</span></li>",
                esc(&urlencode(&parent.id)),
                esc(&parent.title),
                esc(parent.kind.display_label()),
            );
        }
        out.push_str("</ul>\n");
    }
}

fn render_entry_diary_and_actions(
    out: &mut String,
    detail: &EntryDetailView,
    manual_lists: &[CollectionSummary],
    csrf: &str,
) {
    out.push_str("<h3>Consumption History</h3>\n");
    if detail.log_history.is_empty() {
        out.push_str("<p class=\"empty\">Not logged in Diary yet.</p>\n");
    } else {
        out.push_str("<ul class=\"plain\">\n");
        for log in &detail.log_history {
            let _ = write!(
                out,
                "<li><strong>{}</strong> &middot; {}",
                esc(log.status.as_str()),
                log.ts.format("%Y-%m-%d %H:%M UTC"),
            );
            if let Some(notes) = log.notes.as_deref().filter(|s| !s.is_empty()) {
                let _ = write!(out, " &mdash; <em>{}</em>", esc(notes));
            }
            out.push_str("</li>\n");
        }
        out.push_str("</ul>\n");
    }

    let id_enc = urlencode(&detail.entry.id);
    let _ = write!(
        out,
        "<details class=\"editorial-panel\">\n\
           <summary>+ Log Activity on This Work</summary>\n\
           <form method=\"post\" action=\"/web/entries/{id_enc}/log\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"form-grid\">\n\
               <div class=\"field\"><label for=\"log-status\">Status</label>\
                 <select id=\"log-status\" name=\"status\">\
                   <option value=\"consumed\">Consumed / Finished</option>\
                   <option value=\"started\">Started</option>\
                   <option value=\"abandoned\">Abandoned</option>\
                 </select></div>\n\
               <div class=\"field field-check\">\
                 <input id=\"log-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked>\
                 <label for=\"log-rank\">Compare &amp; rank after logging</label></div>\n\
             </div>\n\
             <div class=\"field mb-sm\">\
               <label for=\"log-notes\">Notes (optional)</label>\
               <textarea id=\"log-notes\" name=\"notes\"></textarea></div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Save Log</button>\n\
           </form>\n\
         </details>\n",
        id_enc = esc(&id_enc),
        csrf = esc(csrf),
    );

    if !manual_lists.is_empty() {
        out.push_str(
            "<details class=\"editorial-panel\">\n\
               <summary>+ Add to Custom List</summary>\n",
        );
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/entries/{}/list-add\" class=\"flex-row\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\n\
               <select name=\"list_slug_or_id\" aria-label=\"Target custom list\">",
            esc(&id_enc),
            esc(csrf),
        );
        for col in manual_lists {
            let key = col.slug.as_deref().unwrap_or(&col.id);
            let _ = write!(out, "<option value=\"{}\">{}</option>", esc(key), esc(&col.title));
        }
        out.push_str(
            "</select>\n<button type=\"submit\" class=\"btn btn-primary\">Add</button>\n</form>\n</details>\n",
        );
    }

    let _ = write!(
        out,
        "<details class=\"editorial-panel\">\n\
           <summary>Deduplicate: Merge Into Canonical Work&hellip;</summary>\n\
           <form method=\"post\" action=\"/web/entries/{id_enc}/merge\" data-confirm=\"Merge this duplicate into the target canonical entry? This action cannot be undone.\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <p class=\"meta mt-0\">Folds identifiers, collection memberships, diary logs, and comparisons from this duplicate into the target canonical entry.</p>\n\
             <div class=\"flex-row\">\n\
               <input name=\"canonical_query\" type=\"text\" required aria-label=\"Canonical entry UUID, URL, or exact title\" placeholder=\"Canonical Entry UUID, URL, or exact title\" class=\"flex-1\">\n\
               <button type=\"submit\" class=\"btn\">Merge Duplicate</button>\n\
             </div>\n\
           </form>\n\
         </details>\n",
        id_enc = esc(&id_enc),
        csrf = esc(csrf),
    );
}

pub(in crate::web) async fn post_entry_log<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryLogForm>,
) -> Response {
    let status = parse_consumption_status(&form.status);
    match service.catalog().log_consumption(&id, status, form.notes.as_deref(), None).await {
        Ok(logged) => {
            if status == ConsumptionStatus::Consumed
                && form.rank_after.as_deref() == Some("1")
                && let Ok(Some(slug)) =
                    service.catalog().rank_target_after_consumption(&logged.entry).await
            {
                redirect_with_flash(
                    &format!("/rank/{}?focus={}", urlencode(&slug), urlencode(&logged.entry.id)),
                    "notice",
                    "Logged to Diary",
                )
            } else {
                redirect_with_flash(
                    &format!("/entries/{}", urlencode(&logged.entry.id)),
                    "notice",
                    "Logged to Diary",
                )
            }
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_entry_list_add<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryAddToListForm>,
) -> Response {
    match service.catalog().add_collection_entry(&form.list_slug_or_id, &id, None).await {
        Ok(()) => {
            let fallback = format!("/entries/{}", urlencode(&id));
            let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
            let msg = if form.list_slug_or_id == crate::db::WANT_TO_READ_COLLECTION_ID {
                "Added to Want to Read"
            } else {
                "Added to list"
            };
            redirect_with_flash(&dest, "notice", msg)
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_entry_merge<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryMergeForm>,
) -> Response {
    let target = match service.catalog().resolve_entry(&form.canonical_query, None, None).await {
        Ok(e) => e,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };
    match service.catalog().merge_entries(&id, &target.id).await {
        Ok(canon) => redirect_with_flash(
            &format!("/entries/{}", urlencode(&canon.id)),
            "notice",
            "Merged duplicate into canonical entry",
        ),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}
