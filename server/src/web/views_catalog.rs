use std::{
    collections::{BTreeMap, HashMap},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    auth::WebSession,
    html::{
        FlashQuery, NavSection, PageShell, esc, redirect_see_other, render_page,
        safe_external_href, urlencode, web_api_error_response,
    },
};
use crate::{
    db::{
        CatalogEntry, CollectionMode, CollectionRole, CollectionSummary, ConsumptionLogView,
        ConsumptionStatus, EntryDetailView, EntryIdentifier, EntryKind, RankedCollectionView,
        UpsertEntryInput,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
};

const LIST_PAGE_SIZE: usize = 200;

#[derive(Debug, Default, Deserialize)]
pub(super) struct ListPageQuery {
    pub page: Option<usize>,
    pub notice: Option<String>,
    pub err: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct DiaryFilterQuery {
    pub year: Option<i32>,
    pub status: Option<String>,
    pub notice: Option<String>,
    pub err: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct CreateListForm {
    pub title: String,
    #[serde(default)]
    pub summary: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct AddListEntryForm {
    pub title_or_id: String,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub creators: Option<String>,
    #[serde(default)]
    pub release_year: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct EntryLogForm {
    pub status: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(super) struct EntryAddToListForm {
    pub list_slug_or_id: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct EntryMergeForm {
    pub canonical_query: String,
}

#[derive(Debug, Deserialize)]
pub(super) struct QuickLogForm {
    pub title: String,
    pub kind: String,
    #[serde(default)]
    pub creators: Option<String>,
    #[serde(default)]
    pub release_year: Option<String>,
    #[serde(default)]
    pub identifier: Option<String>,
    pub action: String,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub rank_after: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct WantCompleteForm {
    #[serde(default)]
    pub rank_after: Option<String>,
}

pub(super) async fn lists_get<F: FeedFetcher>(
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

pub(super) async fn post_list_create<F: FeedFetcher>(
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

pub(super) async fn list_detail_get<F: FeedFetcher>(
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
    let artist_links = load_artist_id_by_lower_name(&service).await;

    let total_rows = view.rows.len();
    let total_pages = total_rows.max(1).div_ceil(LIST_PAGE_SIZE);
    let page_num = q.page.unwrap_or(1).clamp(1, total_pages);

    let mut body = String::with_capacity(12_288);
    render_ranked_list_body(
        &mut body,
        &view,
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
    page_num: usize,
    total_pages: usize,
    artist_links: &HashMap<String, String>,
    csrf: &str,
) {
    let slug = view.collection.slug.as_deref().unwrap_or(&view.collection.id);
    let is_editable_manual = view.collection.mode == CollectionMode::Manual
        && !view.collection.has_generator
        && view.collection.role == CollectionRole::List;

    let _ = write!(
        out,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:1rem\">\n\
           <h2>{}</h2>\n",
        esc(&view.collection.title)
    );
    if view.rows.len() >= 2 {
        let _ = write!(
            out,
            "<a class=\"btn btn-primary btn-small\" href=\"/rank/{}\">Compare &amp; Rank</a>",
            esc(&urlencode(slug))
        );
    }
    out.push_str("</div>\n");

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

    if is_editable_manual {
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

pub(super) async fn post_list_add_entry<F: FeedFetcher>(
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

    let entry_id = if let Ok(existing) = service.catalog().resolve_entry(query, None, None).await {
        existing.id
    } else {
        let kind = form
            .kind
            .as_deref()
            .and_then(|k| EntryKind::parse_flexible(k).ok())
            .unwrap_or(EntryKind::Movie);
        let creators = parse_csv_list(form.creators.as_deref());
        let year = parse_optional_year(form.release_year.as_deref());
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

pub(super) async fn post_list_remove_entry<F: FeedFetcher>(
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

pub(super) async fn entry_detail_get<F: FeedFetcher>(
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

    let artist_links = load_artist_id_by_lower_name(&service).await;
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
        if let Ok(ranked) = service.catalog().get_ranked_collection(&key, false).await
            && !ranked.is_priority_scope
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
    let _ = writeln!(out, "<h2>{}</h2>", esc(&entry.title));

    out.push_str("<dl class=\"facts\">\n");
    let _ = writeln!(out, "<dt>Kind</dt><dd>{}</dd>", esc(entry.kind.display_label()));
    if !entry.creators.is_empty() {
        out.push_str("<dt>By</dt><dd>");
        for (idx, creator) in entry.creators.iter().enumerate() {
            if idx > 0 {
                out.push_str(", ");
            }
            if let Some(aid) = artist_links.get(&creator.to_lowercase()) {
                let _ = write!(
                    out,
                    "<a href=\"/entries/{}\">{}</a>",
                    esc(&urlencode(aid)),
                    esc(creator)
                );
            } else {
                let _ = write!(out, "{}", esc(creator));
            }
        }
        out.push_str("</dd>\n");
    }
    if let Some(yr) = entry.release_year {
        let _ = writeln!(out, "<dt>Year</dt><dd>{yr}</dd>");
    }
    if !appearances.is_empty() {
        out.push_str("<dt>Rankings</dt><dd><ul class=\"plain\">");
        for app in appearances {
            let tier_badge = app.tier.map_or_else(String::new, |t| {
                format!(" &middot; <strong class=\"tier-{t}\">Tier {t}</strong>")
            });
            let _ = write!(
                out,
                "<li><a href=\"/lists/{slug}\">{title}</a>: <strong>#{rank}</strong> of {total}{tier_badge} \
                 <a class=\"btn btn-small btn-ghost\" style=\"margin-left:0.5rem\" href=\"/rank/{slug}?focus={id}\">Compare</a></li>",
                slug = esc(&urlencode(&app.slug)),
                title = esc(&app.title),
                rank = app.rank,
                total = app.total,
                id = esc(&urlencode(&entry.id)),
            );
        }
        out.push_str("</ul></dd>\n");
    }
    out.push_str("</dl>\n");

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
    render_entry_relations(out, detail);
    render_entry_diary_and_actions(out, detail, manual_lists, csrf);
}

fn render_entry_external_links(out: &mut String, entry: &CatalogEntry) {
    let mut links: Vec<(String, String)> = Vec::new();
    let mut seen_urls = std::collections::HashSet::new();
    if let Some(u) = entry.url.as_deref().and_then(safe_external_href)
        && !u.starts_with("https://roche.local/")
        && seen_urls.insert(u.to_owned())
    {
        links.push(("Open Source".to_owned(), u.to_owned()));
    }
    for ident in entry.sorted_external_identifiers() {
        if let Some(ext_url) = ident.external_url()
            && let Some(safe_href) = safe_external_href(&ext_url)
            && !safe_href.starts_with("https://roche.local/")
            && seen_urls.insert(safe_href.to_owned())
        {
            links.push((ident.label().to_owned(), safe_href.to_owned()));
        }
    }
    if links.is_empty() {
        return;
    }
    out.push_str("<ul class=\"external-links\">\n");
    for (label, href) in links {
        let _ = writeln!(
            out,
            "<li><a href=\"{}\" target=\"_blank\" rel=\"noopener\">{}</a></li>",
            esc(&href),
            esc(&label)
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
             <div class=\"field\" style=\"margin-bottom:0.65rem\">\
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
            "<form method=\"post\" action=\"/web/entries/{}/list-add\" style=\"display:flex;gap:0.5rem;flex-wrap:wrap\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\n\
               <select name=\"list_slug_or_id\">",
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
           <form method=\"post\" action=\"/web/entries/{id_enc}/merge\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <p class=\"meta\" style=\"margin-top:0\">Folds identifiers, collection memberships, diary logs, and comparisons from this duplicate into the target canonical entry.</p>\n\
             <div style=\"display:flex;gap:0.5rem;flex-wrap:wrap\">\n\
               <input name=\"canonical_query\" type=\"text\" required placeholder=\"Canonical Entry UUID, URL, or exact title\" style=\"flex:1\">\n\
               <button type=\"submit\" class=\"btn\">Merge Duplicate</button>\n\
             </div>\n\
           </form>\n\
         </details>\n",
        id_enc = esc(&id_enc),
        csrf = esc(csrf),
    );
}

pub(super) async fn post_entry_log<F: FeedFetcher>(
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
                && let Some(slug) = logged.entry.kind.default_list_slug()
            {
                redirect_see_other(&format!(
                    "/rank/{}?focus={}&notice={}",
                    urlencode(slug),
                    urlencode(&logged.entry.id),
                    urlencode("Logged to Diary")
                ))
            } else {
                redirect_see_other(&format!(
                    "/entries/{}?notice={}",
                    urlencode(&logged.entry.id),
                    urlencode("Logged to Diary")
                ))
            }
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(super) async fn post_entry_list_add<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryAddToListForm>,
) -> Response {
    match service.catalog().add_collection_entry(&form.list_slug_or_id, &id, None).await {
        Ok(()) => redirect_see_other(&format!(
            "/entries/{}?notice={}",
            urlencode(&id),
            urlencode("Added to list")
        )),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(super) async fn post_entry_merge<F: FeedFetcher>(
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
        Ok(canon) => redirect_see_other(&format!(
            "/entries/{}?notice={}",
            urlencode(&canon.id),
            urlencode("Merged duplicate into canonical entry")
        )),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(super) async fn diary_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(q): Query<DiaryFilterQuery>,
) -> Response {
    let status_filter = match q.status.as_deref() {
        Some("consumed") => Some(ConsumptionStatus::Consumed),
        Some("started") => Some(ConsumptionStatus::Started),
        Some("abandoned") => Some(ConsumptionStatus::Abandoned),
        _ => None,
    };
    let entries = match service.catalog().list_diary(q.year, status_filter, 500).await {
        Ok(list) => list,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };

    // Group chronologically by YYYY-MM (newest first) while preserving order within each month
    let mut months: Vec<(String, Vec<&ConsumptionLogView>)> = Vec::new();
    for item in &entries {
        let month_label = item.ts.format("%B %Y").to_string();
        if let Some((last_label, bucket)) = months.last_mut()
            && *last_label == month_label
        {
            bucket.push(item);
        } else {
            months.push((month_label, vec![item]));
        }
    }

    let mut body = String::with_capacity(8_192);
    let _ = writeln!(
        body,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:1rem\">\
           <h2>Diary</h2>\
           <a class=\"btn btn-small\" href=\"/queue\">+ Log Media</a>\
         </div>\n\
         <p class=\"lede\">{} logged entries across books, albums, films, articles, podcasts, and videos.</p>",
        entries.len()
    );

    body.push_str("<div class=\"pill-bar\">\n");
    for (slug, label) in
        [("", "All"), ("consumed", "Consumed"), ("started", "Started"), ("abandoned", "Abandoned")]
    {
        let is_cur = q.status.as_deref().unwrap_or("") == slug;
        let cls = if is_cur { "pill is-active" } else { "pill" };
        let href =
            if slug.is_empty() { "/diary".to_owned() } else { format!("/diary?status={slug}") };
        let _ = writeln!(body, "<a class=\"{cls}\" href=\"{href}\">{label}</a>");
    }
    body.push_str("</div>\n");

    if months.is_empty() {
        body.push_str("<p class=\"empty\">No diary entries match this filter.</p>\n");
    } else {
        for (month_heading, rows) in months {
            let _ =
                writeln!(body, "<section class=\"diary-month\">\n<h3>{}</h3>", esc(&month_heading));
            for row in rows {
                let _ = write!(
                    body,
                    "<div class=\"diary-row\">\
                       <span class=\"date\">{}</span>\
                       <span><a href=\"/entries/{}\">{}</a>",
                    row.ts.format("%b %d"),
                    esc(&urlencode(&row.entry.id)),
                    esc(&row.entry.title),
                );
                if !row.entry.creators.is_empty() {
                    let _ = write!(
                        body,
                        " <span style=\"color:var(--ink-soft)\">&mdash; {}</span>",
                        esc(&row.entry.creators.join(", "))
                    );
                }
                if row.status != ConsumptionStatus::Consumed {
                    let _ = write!(
                        body,
                        " <span class=\"status-tag\">({})</span>",
                        row.status.as_str()
                    );
                }
                let _ = writeln!(
                    body,
                    "</span><span class=\"kind\">{}</span>",
                    esc(row.entry.kind.display_label())
                );
                if let Some(notes) = row.notes.as_deref().filter(|s| !s.is_empty()) {
                    let _ = writeln!(body, "<div class=\"notes\">{}</div>", esc(notes));
                }
                body.push_str("</div>\n");
            }
            body.push_str("</section>\n");
        }
    }

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: "Diary",
        active: NavSection::Diary,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

pub(super) async fn queue_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let all_cols = match service.catalog().list_collections(true, false).await {
        Ok(cols) => cols,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };

    let want_cols: Vec<CollectionSummary> = all_cols
        .into_iter()
        .filter(|c| c.role == CollectionRole::Want && c.member_count > 0)
        .collect();

    let mut ranked_queues: BTreeMap<String, RankedCollectionView> = BTreeMap::new();
    for wc in &want_cols {
        if let Ok(view) = service.catalog().get_ranked_collection(&wc.id, true).await {
            ranked_queues.insert(wc.title.clone(), view);
        }
    }

    let mut body = String::with_capacity(8_192);
    body.push_str(
        "<h2>Queue &amp; Quick Log</h2>\n\
         <p class=\"lede\">Local priority Want queues (ranked independently of global quality tiers) and quick media logging.</p>\n",
    );

    render_quick_log_panel(&mut body, &session.csrf_token);

    if ranked_queues.is_empty() {
        body.push_str("<p class=\"empty\">Your Want queues are empty. Use the form above to add a movie, book, album, paper, or game.</p>\n");
    } else {
        for (_, qview) in ranked_queues {
            render_want_queue_section(&mut body, &qview, &session.csrf_token);
        }
    }

    render_page(&PageShell {
        title: "Queue",
        active: NavSection::Queue,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

fn render_quick_log_panel(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<details class=\"editorial-panel\" open>\n\
           <summary>+ Log Work or Add to Want Queue</summary>\n\
           <form method=\"post\" action=\"/web/log\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <div class=\"form-grid\">\n\
               <div class=\"field\"><label for=\"ql-title\">Title</label>\
                 <input id=\"ql-title\" name=\"title\" type=\"text\" required placeholder=\"e.g. Drive My Car\"></div>\n\
               <div class=\"field\"><label for=\"ql-kind\">Kind</label>\
                 <select id=\"ql-kind\" name=\"kind\">{kind_opts}</select></div>\n\
               <div class=\"field\"><label for=\"ql-creators\">Creator / Artist / Author</label>\
                 <input id=\"ql-creators\" name=\"creators\" type=\"text\" placeholder=\"Ryusuke Hamaguchi\"></div>\n\
               <div class=\"field\"><label for=\"ql-year\">Release Year</label>\
                 <input id=\"ql-year\" name=\"release_year\" type=\"number\" min=\"1000\" max=\"2100\" placeholder=\"2021\"></div>\n\
               <div class=\"field\"><label for=\"ql-ident\">URL / Spotify / IMDb / ISBN (optional)</label>\
                 <input id=\"ql-ident\" name=\"identifier\" type=\"text\" placeholder=\"https://..., tt14039582, spotify:album:...\"></div>\n\
               <div class=\"field\"><label for=\"ql-action\">Action</label>\
                 <select id=\"ql-action\" name=\"action\">\
                   <option value=\"want\">Add to Want Queue</option>\
                   <option value=\"consumed\">Log Consumed / Finished</option>\
                   <option value=\"started\">Log Started</option>\
                   <option value=\"abandoned\">Log Abandoned</option>\
                 </select></div>\n\
             </div>\n\
             <div class=\"field\" style=\"margin-bottom:0.55rem\">\
               <label for=\"ql-notes\">Notes (when logging to Diary)</label>\
               <textarea id=\"ql-notes\" name=\"notes\" style=\"min-height:2.8rem\" placeholder=\"Optional notes...\"></textarea></div>\n\
             <div class=\"field field-check\" style=\"margin-bottom:0.75rem\">\
               <input id=\"ql-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked>\
               <label for=\"ql-rank\">Open comparison ranking immediately when applicable</label></div>\n\
             <button type=\"submit\" class=\"btn btn-primary\">Save</button>\n\
           </form>\n\
         </details>\n",
        csrf = esc(csrf),
        kind_opts = render_kind_options(EntryKind::Movie),
    );
}

fn render_want_queue_section(out: &mut String, qview: &RankedCollectionView, csrf: &str) {
    let q_slug = qview.collection.slug.as_deref().unwrap_or(&qview.collection.id);
    let base_slug = q_slug.strip_prefix("want:").unwrap_or(q_slug);
    let _ = write!(
        out,
        "<div class=\"section-head\"><h3>{} ({})</h3>",
        esc(&qview.collection.title),
        qview.rows.len()
    );
    if qview.rows.len() >= 2 {
        let _ = write!(
            out,
            "<a class=\"section-action\" href=\"/rank/{}?want=true\">Prioritize Queue &rarr;</a>",
            esc(&urlencode(base_slug))
        );
    }
    out.push_str("</div>\n<ol class=\"queue\">\n");

    for row in &qview.rows {
        let by = if row.entry.creators.is_empty() {
            String::new()
        } else {
            format!(" <span class=\"by\">&mdash; {}</span>", esc(&row.entry.creators.join(", ")))
        };
        let _ = writeln!(
            out,
            "<li><div class=\"queue-row\">\
               <span><a href=\"/entries/{id}\">{title}</a>{by}</span>\
               <span class=\"inbox-controls\">\
                 <form method=\"post\" action=\"/web/want/{q}/complete/{id}\" class=\"inline-form\">\
                   <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
                   <input type=\"hidden\" name=\"rank_after\" value=\"1\">\
                   <button type=\"submit\" class=\"btn btn-small btn-primary\">&#10003; Done &amp; Rank</button>\
                 </form>\
                 <form method=\"post\" action=\"/web/want/{q}/complete/{id}\" class=\"inline-form\">\
                   <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
                   <button type=\"submit\" class=\"btn btn-small\">&#10003; Done</button>\
                 </form>\
                 <form method=\"post\" action=\"/web/want/{q}/remove/{id}\" class=\"inline-form\">\
                   <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
                   <button type=\"submit\" class=\"btn btn-small btn-ghost\">&times;</button>\
                 </form>\
               </span>\
             </div></li>",
            id = esc(&urlencode(&row.entry.id)),
            title = esc(&row.entry.title),
            q = esc(&urlencode(q_slug)),
            csrf = esc(csrf),
        );
    }
    out.push_str("</ol>\n");
}

pub(super) async fn post_quick_log<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<QuickLogForm>,
) -> Response {
    let kind = match EntryKind::parse_flexible(&form.kind) {
        Ok(k) => k,
        Err(e) => return redirect_see_other(&format!("/queue?err={}", urlencode(&e.to_string()))),
    };
    let creators = parse_csv_list(form.creators.as_deref());
    let release_year = parse_optional_year(form.release_year.as_deref());
    let (url, identifiers) = parse_smart_identifier(form.identifier.as_deref());

    let input = UpsertEntryInput {
        kind: Some(kind),
        title: form.title,
        creators,
        url,
        release_year,
        identifiers,
        ..UpsertEntryInput::default()
    };

    if form.action == "want" {
        match service.catalog().want_entry(&input, None).await {
            Ok((queue, entry)) => {
                if form.rank_after.as_deref() == Some("1") && queue.member_count >= 2 {
                    let q_slug = queue.slug.as_deref().unwrap_or(&queue.id);
                    let base = q_slug.strip_prefix("want:").unwrap_or(q_slug);
                    redirect_see_other(&format!(
                        "/rank/{}?want=true&focus={}&notice={}",
                        urlencode(base),
                        urlencode(&entry.id),
                        urlencode("Added to Want queue")
                    ))
                } else {
                    redirect_see_other(&format!(
                        "/queue?notice={}",
                        urlencode(&format!("Added '{}' to {}", entry.title, queue.title))
                    ))
                }
            }
            Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
        }
    } else {
        let entry = match service.catalog().upsert_entry(&input).await {
            Ok(e) => e,
            Err(err) => {
                return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
            }
        };
        let status = parse_consumption_status(&form.action);
        match service
            .catalog()
            .log_consumption(&entry.id, status, form.notes.as_deref(), None)
            .await
        {
            Ok(logged) => {
                if status == ConsumptionStatus::Consumed
                    && form.rank_after.as_deref() == Some("1")
                    && let Some(slug) = logged.entry.kind.default_list_slug()
                    && service
                        .catalog()
                        .get_ranked_collection(slug, false)
                        .await
                        .is_ok_and(|v| v.rows.len() >= 2)
                {
                    redirect_see_other(&format!(
                        "/rank/{}?focus={}&notice={}",
                        urlencode(slug),
                        urlencode(&logged.entry.id),
                        urlencode("Logged to Diary")
                    ))
                } else {
                    redirect_see_other(&format!(
                        "/diary?notice={}",
                        urlencode(&format!("Logged '{}' to Diary", logged.entry.title))
                    ))
                }
            }
            Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
        }
    }
}

pub(super) async fn post_want_complete<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path((_queue, entry_id)): Path<(String, String)>,
    Form(form): Form<WantCompleteForm>,
) -> Response {
    match service
        .catalog()
        .log_consumption(&entry_id, ConsumptionStatus::Consumed, None, None)
        .await
    {
        Ok(logged) => {
            if form.rank_after.as_deref() == Some("1")
                && let Some(slug) = logged.entry.kind.default_list_slug()
                && service
                    .catalog()
                    .get_ranked_collection(slug, false)
                    .await
                    .is_ok_and(|v| v.rows.len() >= 2)
            {
                redirect_see_other(&format!(
                    "/rank/{}?focus={}&notice={}",
                    urlencode(slug),
                    urlencode(&logged.entry.id),
                    urlencode("Completed from queue — rank globally")
                ))
            } else {
                redirect_see_other(&format!(
                    "/queue?notice={}",
                    urlencode(&format!("Completed '{}' & logged to Diary", logged.entry.title))
                ))
            }
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(super) async fn post_want_remove<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path((queue, entry_id)): Path<(String, String)>,
) -> Response {
    match service.catalog().unwant_entry(&queue, &entry_id).await {
        Ok(removed) => redirect_see_other(&format!(
            "/queue?notice={}",
            urlencode(&format!("Removed '{}' from queue", removed.title))
        )),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

async fn load_artist_id_by_lower_name<F: FeedFetcher>(
    service: &RocheService<F>,
) -> HashMap<String, String> {
    service
        .catalog()
        .search_entries(None, Some(EntryKind::Artist), 500)
        .await
        .unwrap_or_default()
        .into_iter()
        .map(|a| (a.title.to_lowercase(), a.id))
        .collect()
}

fn render_kind_options(selected: EntryKind) -> String {
    const LOGGABLE: [EntryKind; 10] = [
        EntryKind::Movie,
        EntryKind::Album,
        EntryKind::Book,
        EntryKind::Tv,
        EntryKind::Game,
        EntryKind::Song,
        EntryKind::Article,
        EntryKind::Paper,
        EntryKind::Video,
        EntryKind::PodcastEpisode,
    ];
    let mut out = String::with_capacity(512);
    for k in LOGGABLE {
        let sel = if k == selected { " selected" } else { "" };
        let _ = write!(
            out,
            "<option value=\"{}\"{sel}>{}</option>",
            k.as_str(),
            esc(k.display_label())
        );
    }
    out
}

fn parse_csv_list(raw: Option<&str>) -> Vec<String> {
    raw.map(|s| {
        s.split(',').map(str::trim).filter(|c| !c.is_empty()).map(ToOwned::to_owned).collect()
    })
    .unwrap_or_default()
}

fn parse_optional_year(raw: Option<&str>) -> Option<i32> {
    raw.map(str::trim).filter(|s| !s.is_empty()).and_then(|s| s.parse::<i32>().ok())
}

fn parse_consumption_status(raw: &str) -> ConsumptionStatus {
    match raw.trim().to_ascii_lowercase().as_str() {
        "started" => ConsumptionStatus::Started,
        "abandoned" => ConsumptionStatus::Abandoned,
        _ => ConsumptionStatus::Consumed,
    }
}

fn parse_smart_identifier(raw: Option<&str>) -> (Option<String>, Vec<EntryIdentifier>) {
    let Some(val) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
        return (None, Vec::new());
    };
    if val.starts_with("spotify:") || val.contains("open.spotify.com/") {
        return (
            None,
            vec![EntryIdentifier { scheme: "spotify".to_owned(), value: val.to_owned() }],
        );
    }
    if val.starts_with("https://") || val.starts_with("http://") {
        return (Some(val.to_owned()), Vec::new());
    }
    if let Some((scheme, rest)) = val.split_once(':')
        && !scheme.is_empty()
        && !rest.trim().is_empty()
    {
        return (
            None,
            vec![EntryIdentifier {
                scheme: scheme.trim().to_ascii_lowercase(),
                value: rest.trim().to_owned(),
            }],
        );
    }
    if val.starts_with("tt") && val[2..].chars().all(|c| c.is_ascii_digit()) {
        return (None, vec![EntryIdentifier { scheme: "imdb".to_owned(), value: val.to_owned() }]);
    }
    if val.chars().all(|c| c.is_ascii_digit() || c == '-' || c == 'X' || c == 'x')
        && val.len() >= 10
    {
        return (None, vec![EntryIdentifier { scheme: "isbn".to_owned(), value: val.to_owned() }]);
    }
    (None, vec![EntryIdentifier { scheme: "external".to_owned(), value: val.to_owned() }])
}
