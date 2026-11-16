use std::{collections::HashMap, fmt::Write as _, sync::Arc};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};

use super::{
    AddListEntryForm, CreateListForm, ListPageQuery, load_artist_id_by_lower_name, parse_csv_list,
    parse_optional_year, render_kind_options,
};
use crate::{
    db::{
        CollectionGeneratorState, CollectionMode, CollectionRole, EntryKind, RankedCollectionView,
        UpsertEntryInput,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, redirect_see_other, render_page, urlencode,
            web_api_error_response,
        },
        views_home::render_collection_draw_section,
    },
};

const LIST_PAGE_SIZE: usize = 200;

pub(in crate::web) async fn lists_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let collections = match service.catalog().list_collections(false, false).await {
        Ok(cols) => cols,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };

    let mut body = String::with_capacity(4_096);
    body.push_str(
        "<h2>Lists</h2>\n\
         <p class=\"lede\">Global quality rankings across media collections, inferred from tie-aware k-way comparisons.</p>\n",
    );

    let _ = write!(
        body,
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
        csrf = esc(&session.csrf_token),
    );

    if collections.is_empty() {
        body.push_str("<p class=\"empty\">No lists yet.</p>\n");
    } else {
        body.push_str("<table class=\"lists\">\n<tbody>\n");
        for col in &collections {
            let slug = col.slug.as_deref().unwrap_or(&col.id);
            let _ = write!(
                body,
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
                    body,
                    "<a class=\"btn btn-small\" href=\"/rank/{}\">Compare</a>",
                    esc(&urlencode(slug))
                );
            }
            body.push_str("</td>\n</tr>\n");
        }
        body.push_str("</tbody>\n</table>\n");
    }

    render_page(&PageShell {
        title: "Lists",
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
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
    let page_creators: Vec<String> = view.rows[start_idx..end_idx]
        .iter()
        .flat_map(|r| r.entry.creators.iter().cloned())
        .collect();
    let artist_links = load_artist_id_by_lower_name(&service, &page_creators).await;

    let gen_state = if view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List
    {
        service.catalog().get_collection_generator(&view.collection.id).await.ok()
    } else {
        None
    };

    let mut body = String::with_capacity(12_288);
    render_ranked_list_body(
        &mut body,
        &view,
        gen_state.as_ref(),
        page_num,
        total_pages,
        &artist_links,
        &session.csrf_token,
    );

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: &view.collection.title,
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

fn render_ranked_list_body(
    out: &mut String,
    view: &RankedCollectionView,
    gen_state: Option<&CollectionGeneratorState>,
    page_num: usize,
    total_pages: usize,
    artist_links: &HashMap<String, String>,
    csrf: &str,
) {
    let slug = view.collection.slug.as_deref().unwrap_or(&view.collection.id);
    let slug_enc = urlencode(slug);
    let is_manual_list = view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List;
    let is_editable_manual = is_manual_list && !view.collection.has_generator;

    let _ = write!(
        out,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:1rem\">\n\
           <h2>{}</h2>\n\
           <div style=\"display:flex;gap:0.5rem;align-items:center;flex-wrap:wrap\">",
        esc(&view.collection.title)
    );
    if let Some(gs) = gen_state {
        let next_enabled = if gs.enabled { "false" } else { "true" };
        let label = if gs.enabled { "Pause Draw" } else { "Enable Draw" };
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/lists/{}/generator/toggle\" class=\"inline-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\
               <input type=\"hidden\" name=\"enabled\" value=\"{next_enabled}\">\
               <input type=\"hidden\" name=\"redirect_list\" value=\"true\">\
               <button type=\"submit\" class=\"btn btn-small\">{label}</button>\
             </form>",
            esc(&slug_enc),
            esc(csrf),
        );
    }
    if view.rows.len() >= 2 {
        let want_suffix =
            if view.collection.role == CollectionRole::Want { "?want=true" } else { "" };
        let _ = write!(
            out,
            "<a class=\"btn btn-primary btn-small\" href=\"/rank/{}{want_suffix}\">Compare &amp; Rank</a>",
            esc(&slug_enc)
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
        render_collection_draw_section(out, gs, csrf);
    }

    if is_manual_list {
        render_manual_list_add_panel(out, slug, csrf);
    }

    if total_pages > 1 {
        render_list_pager(out, slug, page_num, total_pages);
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
           <th class=\"num\">#</th>\
           <th>Tier</th>\
           <th>Work</th>\
           <th class=\"score num\">Score</th>",
    );
    if is_editable_manual {
        out.push_str("<th></th>");
    }
    out.push_str("</tr></thead>\n<tbody>\n");

    let mut prev_tier: Option<char> = None;
    for (idx, row) in slice.iter().enumerate() {
        let tier_break = idx > 0 && row.tier != prev_tier;
        prev_tier = row.tier;
        render_ranked_table_row(out, row, tier_break, is_editable_manual, slug, artist_links, csrf);
    }
    out.push_str("</tbody>\n</table>\n");

    if total_pages > 1 {
        render_list_pager(out, slug, page_num, total_pages);
    }
}

fn render_ranked_table_row(
    out: &mut String,
    row: &crate::db::RankedEntryRow,
    tier_break: bool,
    is_editable_manual: bool,
    slug: &str,
    artist_links: &HashMap<String, String>,
    csrf: &str,
) {
    let tr_cls = if tier_break { " class=\"tier-break\"" } else { "" };
    let tier_char = row.tier.unwrap_or('·');
    let tier_cls = row.tier.map_or_else(String::new, |t| format!(" tier-{t}"));
    let tick_pct = row.bar_pct.clamp(4.0, 96.0);

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
    let _ = write!(
        out,
        "</td>\
         <td class=\"score\"><div class=\"score-mark\" title=\"μ = {:.2}, σ = {:.2}\">\
           <span class=\"tick\" style=\"left:{tick_pct:.1}%\"></span></div></td>",
        row.score.mean,
        row.score.variance.max(0.0).sqrt(),
    );
    if is_editable_manual {
        let _ = write!(
            out,
            "<td class=\"num\">\
               <form method=\"post\" action=\"/web/lists/{slug_enc}/remove/{id_enc}\" class=\"inline-form\">\
                 <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
                 <button type=\"submit\" class=\"link-btn\" title=\"Remove from list\">&times;</button>\
               </form>\
             </td>",
            slug_enc = esc(&urlencode(slug)),
            id_enc = esc(&urlencode(&row.entry.id)),
            csrf = esc(csrf),
        );
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

fn render_list_pager(out: &mut String, slug: &str, page_num: usize, total_pages: usize) {
    out.push_str("<nav class=\"pager\">");
    if page_num > 1 {
        let _ = write!(
            out,
            "<a href=\"/lists/{}?page={}\">&larr; Prev</a>",
            esc(&urlencode(slug)),
            page_num - 1
        );
    }
    for p in 1..=total_pages {
        if p == page_num {
            let _ = write!(out, "<span class=\"current\">{p}</span>");
        } else if p == 1 || p == total_pages || p.abs_diff(page_num) <= 2 {
            let _ = write!(out, "<a href=\"/lists/{}?page={p}\">{p}</a>", esc(&urlencode(slug)));
        } else if p.abs_diff(page_num) == 3 {
            out.push_str("<span>&hellip;</span>");
        }
    }
    if page_num < total_pages {
        let _ = write!(
            out,
            "<a href=\"/lists/{}?page={}\">Next &rarr;</a>",
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
    for (idx, creator) in creators.iter().enumerate() {
        if idx > 0 {
            out.push_str(", ");
        }
        if let Some(artist_id) = artist_links.get(&creator.to_lowercase()) {
            let _ = write!(
                out,
                "<a href=\"/entries/{}\">{}</a>",
                esc(&urlencode(artist_id)),
                esc(creator)
            );
        } else {
            let _ = write!(out, "{}", esc(creator));
        }
    }
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
