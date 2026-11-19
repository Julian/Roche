use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};

use super::{
    AddListEntryForm, CreateListForm, ListPageQuery, ListsOverviewQuery,
    load_existing_reader_item_ids, parse_csv_list, parse_optional_year, render_kind_options,
    render_search_kind_options,
};
use crate::{
    db::{
        CatalogEntry, CollectionGeneratorState, CollectionMode, CollectionRole, EntryKind,
        RankedCollectionView, UpsertEntryInput,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, rank_collection_href, redirect_see_other,
            render_inline_csrf_form, render_page, urlencode, web_api_error_response,
        },
        links::{load_artist_id_by_lower_name, render_creator_links},
        views_home::render_collection_draw_section,
    },
};

const LIST_PAGE_SIZE: usize = 200;
const CATALOG_SEARCH_LIMIT: usize = 80;

pub(in crate::web) async fn lists_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(query): Query<ListsOverviewQuery>,
) -> Response {
    let collections = match service.catalog().list_collections(false, false).await {
        Ok(cols) => cols,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };

    let clean_q = query.q.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let raw_kind = query.kind.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let (parsed_kind, invalid_kind) =
        raw_kind.map_or((None, None), |raw| match EntryKind::parse_flexible(raw) {
            Ok(k) if k != EntryKind::Collection => (Some(k), None),
            _ => (None, Some(raw)),
        });
    let is_searching = clean_q.is_some() || parsed_kind.is_some() || invalid_kind.is_some();

    let search_bundle = if invalid_kind.is_some() {
        Some((Vec::new(), HashMap::new(), false))
    } else if is_searching {
        let fetch_limit = if parsed_kind.is_none() { 500 } else { CATALOG_SEARCH_LIMIT + 1 };
        let non_collections =
            match service.catalog().search_entries(clean_q, parsed_kind, fetch_limit).await {
                Ok(rows) => rows
                    .into_iter()
                    .filter(|e| e.kind != EntryKind::Collection)
                    .take(CATALOG_SEARCH_LIMIT + 1)
                    .collect::<Vec<_>>(),
                Err(err) => {
                    return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
                }
            };
        let truncated = non_collections.len() > CATALOG_SEARCH_LIMIT;
        let matched: Vec<CatalogEntry> =
            non_collections.into_iter().take(CATALOG_SEARCH_LIMIT).collect();
        let creators: Vec<String> =
            matched.iter().flat_map(|e| e.creators.iter().cloned()).collect();
        let artist_links = load_artist_id_by_lower_name(&service, &creators).await;
        Some((matched, artist_links, truncated))
    } else {
        None
    };

    let mut body = String::with_capacity(6_144);
    body.push_str(
        "<h2>Lists</h2>\n\
         <p class=\"lede\">Global quality rankings across media collections, inferred from tie-aware k-way comparisons.</p>\n",
    );
    render_catalog_search_form(&mut body, clean_q, parsed_kind, is_searching);
    if let Some((ref matched, ref artist_links, truncated)) = search_bundle {
        render_catalog_search_results(&mut body, matched, artist_links, truncated);
    }
    render_new_manual_list_panel(&mut body, &session.csrf_token);
    if is_searching {
        body.push_str("<h3>All Lists</h3>\n");
    }
    render_lists_overview_table(&mut body, &collections);

    let err =
        query.err.or_else(|| invalid_kind.map(|bad| format!("Unknown entry kind filter '{bad}'")));
    let flash = FlashQuery { notice: query.notice, err };
    render_page(&PageShell {
        title: "Lists",
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
}

fn render_catalog_search_form(
    out: &mut String,
    clean_q: Option<&str>,
    parsed_kind: Option<EntryKind>,
    is_searching: bool,
) {
    let _ = write!(
        out,
        "<form method=\"get\" action=\"/lists\" class=\"search-form\">\n\
           <input type=\"search\" name=\"q\" aria-label=\"Search catalog\" value=\"{}\" placeholder=\"Search catalog works, artists, creators, identifiers...\" class=\"search-input\">\n\
           <select name=\"kind\" aria-label=\"Filter by kind\">{}</select>\n\
           <button type=\"submit\" class=\"btn\">Search</button>",
        esc(clean_q.unwrap_or("")),
        render_search_kind_options(parsed_kind),
    );
    if is_searching {
        out.push_str("\n<a class=\"btn btn-ghost\" href=\"/lists\">Clear</a>");
    }
    out.push_str("\n</form>\n");
}

fn render_catalog_search_results(
    out: &mut String,
    results: &[CatalogEntry],
    artist_links: &HashMap<String, String>,
    truncated: bool,
) {
    let count_suffix = if truncated { "+" } else { "" };
    let _ = writeln!(
        out,
        "<div class=\"section-head\"><h3>Search Results ({}{count_suffix})</h3>\
         <a class=\"section-action\" href=\"/lists\">Clear &times;</a></div>",
        results.len()
    );
    if results.is_empty() {
        out.push_str("<p class=\"empty\">No matching works in catalog.</p>\n");
        return;
    }
    out.push_str("<ul class=\"plain mb-xl\">\n");
    for entry in results {
        let _ = write!(
            out,
            "<li><a href=\"/entries/{}\">{}</a>",
            esc(&urlencode(&entry.id)),
            esc(&entry.title),
        );
        if !entry.creators.is_empty() {
            out.push_str(" <span class=\"text-soft\">&mdash; ");
            render_creator_links(out, &entry.creators, artist_links, false);
            out.push_str("</span>");
        }
        if let Some(yr) = entry.release_year {
            let _ = write!(out, " <span class=\"text-faint\">({yr})</span>");
        }
        let _ =
            writeln!(out, " <span class=\"badge\">{}</span></li>", esc(entry.kind.display_label()));
    }
    out.push_str("</ul>\n");
}

fn render_new_manual_list_panel(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<details class=\"editorial-panel\">\n\
           <summary>+ New Manual List</summary>\n\
           <form method=\"post\" action=\"/web/lists/create\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"form-grid\">\n\
               <div class=\"field\"><label for=\"list-title\">Title</label>\
                 <input id=\"list-title\" name=\"title\" type=\"text\" required placeholder=\"e.g. Sci-Fi Canon\"></div>\n\
               <div class=\"field\"><label for=\"list-summary\">Description (optional)</label>\
                 <input id=\"list-summary\" name=\"summary\" type=\"text\" placeholder=\"Favorite speculative fiction\"></div>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Create List</button>\n\
           </form>\n\
         </details>\n",
        csrf = esc(csrf),
    );
}

fn render_lists_overview_table(out: &mut String, collections: &[crate::db::CollectionSummary]) {
    if collections.is_empty() {
        out.push_str("<p class=\"empty\">No lists yet.</p>\n");
        return;
    }
    out.push_str(
        "<table class=\"lists\">\n\
         <thead class=\"sr-only\"><tr>\
           <th scope=\"col\">List</th>\
           <th scope=\"col\">Works</th>\
           <th scope=\"col\">Comparisons</th>\
           <th scope=\"col\">Actions</th>\
         </tr></thead>\n\
         <tbody>\n",
    );
    for col in collections {
        let slug = col.slug.as_deref().unwrap_or(&col.id);
        let _ = write!(
            out,
            "<tr>\n\
               <td class=\"name\"><a href=\"/lists/{slug_enc}\">{title}</a></td>\n\
               <td class=\"stat\">{members} works</td>\n\
               <td class=\"stat\">{comps} comparisons</td>\n\
               <td class=\"stat\">",
            slug_enc = esc(&urlencode(slug)),
            title = esc(&col.title),
            members = col.member_count,
            comps = col.comparison_count,
        );
        if col.member_count >= 2 {
            let _ = write!(
                out,
                "<a class=\"btn btn-small\" href=\"/rank/{}\">Compare</a>",
                esc(&urlencode(slug))
            );
        }
        out.push_str("</td>\n</tr>\n");
    }
    out.push_str("</tbody>\n</table>\n");
}

pub(in crate::web) async fn post_list_create<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<CreateListForm>,
) -> Response {
    let created = service
        .catalog()
        .create_manual_collection(
            &form.title,
            None,
            CollectionRole::List,
            form.summary.as_deref(),
            None,
        )
        .await;
    match created {
        Ok(col) => {
            let slug = col.slug.as_deref().unwrap_or(&col.id);
            redirect_see_other(&format!(
                "/lists/{}?notice={}",
                urlencode(slug),
                urlencode("Created manual list")
            ))
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn list_detail_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Query(q): Query<ListPageQuery>,
) -> Response {
    let view = match service.catalog().get_ranked_collection(&slug, false).await {
        Ok(v) => v,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };
    let total_rows = view.rows.len();
    let total_pages = total_rows.max(1).div_ceil(LIST_PAGE_SIZE);
    let page_num = q.page.unwrap_or(1).clamp(1, total_pages);
    let start_idx = (page_num - 1) * LIST_PAGE_SIZE;
    let end_idx = (page_num * LIST_PAGE_SIZE).min(total_rows);
    let gen_state = if view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List
    {
        service.catalog().get_collection_generator(&view.collection.id).await.ok()
    } else {
        None
    };

    let mut page_creators: Vec<String> = view.rows[start_idx..end_idx]
        .iter()
        .flat_map(|r| r.entry.creators.iter().cloned())
        .collect();
    if let Some(drawn) = gen_state.as_ref().and_then(|gs| gs.current_entry.as_ref()) {
        page_creators.extend(drawn.creators.iter().cloned());
    }
    let (artist_links, reader_item_ids) = tokio::join!(
        load_artist_id_by_lower_name(&service, &page_creators),
        load_existing_reader_item_ids(
            &service,
            view.rows[start_idx..end_idx].iter().map(|r| &r.entry),
        ),
    );

    let mut body = String::with_capacity(12_288);
    render_ranked_list_body(
        &mut body,
        &view,
        gen_state.as_ref(),
        page_num,
        total_pages,
        &artist_links,
        &reader_item_ids,
        &session.csrf_token,
    );

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: &view.collection.title,
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
}

#[allow(clippy::too_many_arguments)]
fn render_ranked_list_body(
    out: &mut String,
    view: &RankedCollectionView,
    gen_state: Option<&CollectionGeneratorState>,
    page_num: usize,
    total_pages: usize,
    artist_links: &HashMap<String, String>,
    reader_item_ids: &HashSet<String>,
    csrf: &str,
) {
    let slug = view.collection.slug.as_deref().unwrap_or(&view.collection.id);
    let slug_enc = urlencode(slug);
    let is_manual_list = view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List;
    let is_editable_manual = is_manual_list && !view.collection.has_generator;

    let _ = write!(
        out,
        "<div class=\"page-head\">\n\
           <h2>{}</h2>\n\
           <div class=\"flex-row flex-center\">",
        esc(&view.collection.title)
    );
    if let Some(gs) = gen_state {
        let next_enabled = if gs.enabled { "false" } else { "true" };
        let label = if gs.enabled { "Pause Draw" } else { "Enable Draw" };
        let toggle_action = format!("/web/lists/{slug_enc}/generator/toggle");
        let btn_html = format!("<button type=\"submit\" class=\"btn btn-small\">{label}</button>");
        render_inline_csrf_form(
            out,
            &toggle_action,
            csrf,
            &[("enabled", next_enabled), ("redirect_list", "true")],
            None,
            &btn_html,
        );
    }
    if view.rows.len() >= 2 {
        let rank_href = rank_collection_href(slug, view.collection.role == CollectionRole::Want);
        let _ = write!(
            out,
            "<a class=\"btn btn-primary btn-small\" href=\"{}\">Compare &amp; Rank</a>",
            esc(&rank_href)
        );
    }
    out.push_str("</div></div>\n");

    let _ = writeln!(
        out,
        "<p class=\"lede\"><em>{}</em> works &middot; <em>{}</em> comparisons{}</p>",
        view.rows.len(),
        view.comparison_count,
        view.collection
            .summary
            .as_deref()
            .filter(|s| !s.is_empty())
            .map_or_else(String::new, |s| format!(" &mdash; {}", esc(s))),
    );

    if let Some(gs) = gen_state
        && gs.enabled
    {
        render_collection_draw_section(out, gs, artist_links, csrf);
    }

    if is_editable_manual {
        render_manual_list_add_panel(out, slug, csrf);
    }

    if total_pages > 1 {
        render_list_pager(out, slug, page_num, total_pages, "Pagination (top)");
    }

    if view.rows.is_empty() {
        out.push_str("<p class=\"empty\">No works in this list yet.</p>\n");
        return;
    }

    let start = (page_num - 1) * LIST_PAGE_SIZE;
    let end = (start + LIST_PAGE_SIZE).min(view.rows.len());
    let slice = &view.rows[start..end];

    out.push_str(
        "<table class=\"ranked\">\n\
         <thead><tr>\
           <th scope=\"col\" class=\"num\">#</th>\
           <th scope=\"col\">Tier</th>\
           <th scope=\"col\">Work</th>\
           <th scope=\"col\" class=\"score num\">Score</th>",
    );
    if is_editable_manual {
        out.push_str("<th scope=\"col\"><span class=\"sr-only\">Actions</span></th>");
    }
    out.push_str("</tr></thead>\n<tbody>\n");

    let mut prev_tier: Option<char> = None;
    for (idx, row) in slice.iter().enumerate() {
        let tier_break = idx > 0 && row.tier != prev_tier;
        prev_tier = row.tier;
        render_ranked_table_row(
            out,
            row,
            tier_break,
            is_editable_manual,
            slug,
            artist_links,
            reader_item_ids,
            csrf,
        );
    }
    out.push_str("</tbody>\n</table>\n");

    if total_pages > 1 {
        render_list_pager(out, slug, page_num, total_pages, "Pagination (bottom)");
    }
}

#[allow(clippy::too_many_arguments)]
fn render_ranked_table_row(
    out: &mut String,
    row: &crate::db::RankedEntryRow,
    tier_break: bool,
    is_editable_manual: bool,
    slug: &str,
    artist_links: &HashMap<String, String>,
    reader_item_ids: &HashSet<String>,
    csrf: &str,
) {
    let tr_cls = if tier_break { " class=\"tier-break\"" } else { "" };
    let tier_char = row.tier.unwrap_or('·');
    let tier_cls = row.tier.map_or_else(String::new, |t| format!(" tier-{t}"));
    let tick_pct = row.bar_pct.clamp(4.0, 96.0);
    let sigma = row.score.variance.max(0.0).sqrt();

    let _ = write!(
        out,
        "<tr{tr_cls}>\
           <td class=\"rank num\">{rank}</td>\
           <td class=\"tier{tier_cls}\">{tier_char}</td>\
           <td class=\"work\"><span class=\"title\"><a href=\"/entries/{id}\">{title}</a></span>",
        rank = row.rank,
        id = esc(&urlencode(&row.entry.id)),
        title = esc(&row.entry.title),
    );
    render_creators_span(out, &row.entry.creators, artist_links);
    if let Some(yr) = row.entry.release_year {
        let _ = write!(out, "<span class=\"year\">({yr})</span>");
    }
    if reader_item_ids.contains(&row.entry.id) {
        let _ = write!(
            out,
            " <a class=\"badge ml-xs\" href=\"/reader/{id}\">Read &rarr;</a>",
            id = esc(&urlencode(&row.entry.id)),
        );
    }
    let _ = write!(
        out,
        "</td>\
         <td class=\"score\"><div class=\"score-mark-wrap\" title=\"μ = {mean:.2}, σ = {sigma:.2}\">\
           <span class=\"sr-only\">Score {mean:.2} (±{sigma:.2})</span>\
           <svg class=\"score-mark\" viewBox=\"0 0 100 12\" width=\"100\" height=\"12\" aria-hidden=\"true\">\
             <line class=\"score-axis\" x1=\"0\" y1=\"6\" x2=\"100\" y2=\"6\"/>\
             <line class=\"score-mid\" x1=\"50\" y1=\"2\" x2=\"50\" y2=\"10\"/>\
             <circle class=\"tick\" cx=\"{tick_pct:.1}\" cy=\"6\" r=\"3.5\"/>\
           </svg></div></td>",
        mean = row.score.mean,
    );
    if is_editable_manual {
        let remove_action =
            format!("/web/lists/{}/remove/{}", urlencode(slug), urlencode(&row.entry.id));
        let remove_btn = format!(
            "<button type=\"submit\" class=\"link-btn\" title=\"Remove from list\" aria-label=\"Remove {} from list\"><span aria-hidden=\"true\">&times;</span></button>",
            esc(&row.entry.title)
        );
        out.push_str("<td class=\"num\">");
        render_inline_csrf_form(out, &remove_action, csrf, &[], None, &remove_btn);
        out.push_str("</td>");
    }
    out.push_str("</tr>\n");
}

fn render_manual_list_add_panel(out: &mut String, slug: &str, csrf: &str) {
    let _ = write!(
        out,
        "<details class=\"editorial-panel\">\n\
           <summary>+ Add Work to List</summary>\n\
           <form method=\"post\" action=\"/web/lists/{slug_enc}/add\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"form-grid\">\n\
               <div class=\"field\"><label for=\"add-title\">Title or Existing Entry ID</label>\
                 <input id=\"add-title\" name=\"title_or_id\" type=\"text\" required placeholder=\"e.g. Stalker\"></div>\n\
               <div class=\"field\"><label for=\"add-kind\">Kind (if creating new)</label>\
                 <select id=\"add-kind\" name=\"kind\">{kind_opts}</select></div>\n\
               <div class=\"field\"><label for=\"add-creators\">Creators (comma-separated)</label>\
                 <input id=\"add-creators\" name=\"creators\" type=\"text\" placeholder=\"Andrei Tarkovsky\"></div>\n\
               <div class=\"field\"><label for=\"add-year\">Year</label>\
                 <input id=\"add-year\" name=\"release_year\" type=\"number\" min=\"1000\" max=\"2100\" placeholder=\"1979\"></div>\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Add to List</button>\n\
           </form>\n\
         </details>\n",
        slug_enc = esc(&urlencode(slug)),
        csrf = esc(csrf),
        kind_opts = render_kind_options(EntryKind::Movie),
    );
}

fn render_list_pager(
    out: &mut String,
    slug: &str,
    page_num: usize,
    total_pages: usize,
    aria_label: &str,
) {
    let _ = write!(out, "<nav class=\"pager\" aria-label=\"{}\">", esc(aria_label));
    if page_num > 1 {
        let _ = write!(
            out,
            "<a href=\"/lists/{}?page={}\"><span aria-hidden=\"true\">&larr;</span> Prev</a>",
            esc(&urlencode(slug)),
            page_num - 1
        );
    }
    for p in 1..=total_pages {
        if p == page_num {
            let _ = write!(out, "<span class=\"current\" aria-current=\"page\">{p}</span>");
        } else if p == 1 || p == total_pages || p.abs_diff(page_num) <= 2 {
            let _ = write!(out, "<a href=\"/lists/{}?page={p}\">{p}</a>", esc(&urlencode(slug)));
        } else if p.abs_diff(page_num) == 3 {
            out.push_str("<span aria-hidden=\"true\">&hellip;</span>");
        }
    }
    if page_num < total_pages {
        let _ = write!(
            out,
            "<a href=\"/lists/{}?page={}\">Next <span aria-hidden=\"true\">&rarr;</span></a>",
            esc(&urlencode(slug)),
            page_num + 1
        );
    }
    out.push_str("</nav>\n");
}

fn render_creators_span(
    out: &mut String,
    creators: &[String],
    artist_links: &HashMap<String, String>,
) {
    if creators.is_empty() {
        return;
    }
    out.push_str("<span class=\"by\">");
    render_creator_links(out, creators, artist_links, false);
    out.push_str("</span>");
}

pub(in crate::web) async fn post_list_add_entry<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<AddListEntryForm>,
) -> Response {
    let query = form.title_or_id.trim();
    if query.is_empty() {
        return redirect_see_other(&format!(
            "/lists/{}?err=Title+cannot+be+empty",
            urlencode(&slug)
        ));
    }

    let parsed_kind = form
        .kind
        .as_deref()
        .map(str::trim)
        .filter(|k| !k.is_empty())
        .and_then(|k| EntryKind::parse_flexible(k).ok());
    let creators = parse_csv_list(form.creators.as_deref());
    let year = parse_optional_year(form.release_year.as_deref());
    let is_explicit_ref = uuid::Uuid::parse_str(query).is_ok()
        || query.contains("://")
        || query.contains(':')
        || query.contains('=');
    let can_fuzzy_resolve = is_explicit_ref || (creators.is_empty() && year.is_none());

    let entry_id = if can_fuzzy_resolve
        && let Ok(existing) = service.catalog().resolve_entry(query, parsed_kind, None).await
    {
        existing.id
    } else {
        let kind = parsed_kind.unwrap_or(EntryKind::Movie);
        let input = UpsertEntryInput {
            kind: Some(kind),
            title: query.to_owned(),
            creators,
            release_year: year,
            ..UpsertEntryInput::default()
        };
        match service.catalog().upsert_entry(&input).await {
            Ok(created) => created.id,
            Err(err) => {
                return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
            }
        }
    };

    match service.catalog().add_collection_entry(&slug, &entry_id, None).await {
        Ok(()) => redirect_see_other(&format!(
            "/lists/{}?notice={}",
            urlencode(&slug),
            urlencode("Added work to list")
        )),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_list_remove_entry<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path((slug, entry_id)): Path<(String, String)>,
) -> Response {
    match service.catalog().remove_collection_entry(&slug, &entry_id).await {
        Ok(_) => redirect_see_other(&format!(
            "/lists/{}?notice={}",
            urlencode(&slug),
            urlencode("Removed work from list")
        )),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}
