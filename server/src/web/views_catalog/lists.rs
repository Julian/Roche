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
    AddListEntryForm, CreateListForm, ListPageQuery, ListRemoveForm, ListsOverviewQuery,
    load_existing_reader_item_ids, parse_optional_year, render_optional_kind_options,
    render_search_kind_options,
};
use crate::{
    db::{CatalogEntry, CollectionGeneratorState, CollectionSummary, UpsertEntryInput},
    domain::{CollectionMode, CollectionRole, EntryKind},
    service::{
        AppError, RankedCollectionFilter, RankedCollectionView, RankedEntryRow, RocheService,
    },
    web::{
        auth::WebSession,
        html::{
            CreatorYearStyle, FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _, esc,
            parse_csv_list, rank_collection_href, redirect_with_flash, render_creator_year_meta,
            render_inline_csrf_form, render_page, safe_local_redirect, urlencode,
        },
        links::{load_artist_id_by_lower_name, sanitize_non_local_href},
        views_home::render_collection_draw_section,
    },
};

const LIST_PAGE_SIZE: usize = 200;
const CATALOG_SEARCH_LIMIT: usize = 80;
const LIST_PREVIEW_THUMB_LIMIT: usize = 5;

const COMPACT_VIEW_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" aria-hidden=\"true\"><path d=\"M2.5 4h11M2.5 8h11M2.5 12h11\"/></svg>";
const PREVIEWS_VIEW_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.45\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"2\" y=\"2.5\" width=\"5\" height=\"5\" rx=\"0.8\"/><rect x=\"9\" y=\"2.5\" width=\"5\" height=\"5\" rx=\"0.8\"/><rect x=\"2\" y=\"9\" width=\"5\" height=\"4.5\" rx=\"0.8\"/><rect x=\"9\" y=\"9\" width=\"5\" height=\"4.5\" rx=\"0.8\"/></svg>";

struct ListsOverviewData<'a> {
    collections: &'a [CollectionSummary],
    previews: &'a HashMap<String, Vec<(String, String)>>,
    generators: &'a HashMap<String, CollectionGeneratorState>,
    csrf: &'a str,
}

pub(in crate::web) async fn lists_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(query): Query<ListsOverviewQuery>,
) -> Result<Response, WebError> {
    let collections = service.catalog().list_collections(false, false).await.web_err(&session)?;

    let clean_q = query.q.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let raw_kind = query.kind.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let (parsed_kind, invalid_kind) =
        raw_kind.map_or((None, None), |raw| match EntryKind::parse_flexible(raw) {
            Ok(k) if k != EntryKind::Collection => (Some(k), None),
            _ => (None, Some(raw)),
        });
    let is_searching = clean_q.is_some() || parsed_kind.is_some() || invalid_kind.is_some();

    let search_bundle =
        load_catalog_search_bundle(&service, clean_q, parsed_kind, invalid_kind, is_searching)
            .await
            .web_err(&session)?;

    let previews = service
        .catalog()
        .load_collection_preview_thumbnails(&collections, LIST_PREVIEW_THUMB_LIMIT)
        .await
        .unwrap_or_default();
    let generators = load_overview_generators(&service, &collections).await;

    let mut body = String::with_capacity(8_192);
    let data = ListsOverviewData {
        collections: &collections,
        previews: &previews,
        generators: &generators,
        csrf: &session.csrf_token,
    };
    body.push_str("<div class=\"lists-grid\">\n");
    render_lists_main(&mut body, &data, search_bundle.as_ref());
    render_lists_rail(&mut body, &collections, clean_q, parsed_kind, is_searching);
    body.push_str("</div>\n");

    let err = query
        .flash
        .err
        .or_else(|| invalid_kind.map(|bad| format!("Unknown entry kind filter '{bad}'")));
    let flash = FlashQuery { notice: query.flash.notice, err };
    Ok(render_page(&PageShell {
        title: "Lists",
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

type SearchBundle = (Vec<CatalogEntry>, HashMap<String, String>, bool);

async fn load_catalog_search_bundle(
    service: &RocheService,
    clean_q: Option<&str>,
    parsed_kind: Option<EntryKind>,
    invalid_kind: Option<&str>,
    is_searching: bool,
) -> Result<Option<SearchBundle>, AppError> {
    if invalid_kind.is_some() {
        return Ok(Some((Vec::new(), HashMap::new(), false)));
    }
    if !is_searching {
        return Ok(None);
    }
    let fetch_limit = if parsed_kind.is_none() { 500 } else { CATALOG_SEARCH_LIMIT + 1 };
    let non_collections = service
        .catalog()
        .search_entries(clean_q, parsed_kind, fetch_limit)
        .await?
        .into_iter()
        .filter(|e| e.kind != EntryKind::Collection)
        .take(CATALOG_SEARCH_LIMIT + 1)
        .collect::<Vec<_>>();
    let truncated = non_collections.len() > CATALOG_SEARCH_LIMIT;
    let matched: Vec<CatalogEntry> =
        non_collections.into_iter().take(CATALOG_SEARCH_LIMIT).collect();
    let creators: Vec<String> = matched.iter().flat_map(|e| e.creators.iter().cloned()).collect();
    let artist_links = load_artist_id_by_lower_name(service, &creators).await;
    Ok(Some((matched, artist_links, truncated)))
}

async fn load_overview_generators(
    service: &RocheService,
    collections: &[CollectionSummary],
) -> HashMap<String, CollectionGeneratorState> {
    if !collections.iter().any(|c| c.has_generator) {
        return HashMap::new();
    }
    service
        .catalog()
        .list_collection_generators(false)
        .await
        .unwrap_or_default()
        .into_iter()
        .map(|state| (state.collection_id.clone(), state))
        .collect()
}

fn render_lists_main(
    out: &mut String,
    data: &ListsOverviewData<'_>,
    search_bundle: Option<&SearchBundle>,
) {
    let mut manual_lists: Vec<&CollectionSummary> =
        data.collections.iter().filter(|c| c.mode == CollectionMode::Manual).collect();
    manual_lists.sort_by(|a, b| {
        b.comparison_count
            .cmp(&a.comparison_count)
            .then_with(|| b.member_count.cmp(&a.member_count))
            .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
    });

    let mut media_active: Vec<&CollectionSummary> = data
        .collections
        .iter()
        .filter(|c| c.mode == CollectionMode::Dynamic && c.member_count > 0)
        .collect();
    media_active.sort_by(|a, b| {
        b.comparison_count
            .cmp(&a.comparison_count)
            .then_with(|| b.member_count.cmp(&a.member_count))
            .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
    });

    let mut media_empty: Vec<&CollectionSummary> = data
        .collections
        .iter()
        .filter(|c| c.mode == CollectionMode::Dynamic && c.member_count == 0)
        .collect();
    media_empty.sort_by_key(|a| a.title.to_lowercase());

    let _ = write!(
        out,
        "<section class=\"lists-main\" id=\"roche-lists-main\" data-lists-view=\"previews\" aria-label=\"Lists\">\n\
           <div class=\"lists-top-bar\">\n\
             <h2>Lists</h2>\n\
             <div class=\"view-mode-switch\" role=\"group\" aria-label=\"Display density\">\n\
               <button type=\"button\" class=\"view-mode-btn is-active js-lists-view-btn\" data-view=\"previews\" aria-pressed=\"true\">{PREVIEWS_VIEW_SVG}<span>Previews</span></button>\n\
               <button type=\"button\" class=\"view-mode-btn js-lists-view-btn\" data-view=\"compact\" aria-pressed=\"false\">{COMPACT_VIEW_SVG}<span>Compact</span></button>\n\
             </div>\n\
           </div>\n",
    );

    if let Some((matched, artist_links, truncated)) = search_bundle {
        render_catalog_search_results(out, matched, artist_links, *truncated);
    }

    render_new_list_section_head(out, data.csrf);
    if manual_lists.is_empty() {
        out.push_str("<p class=\"empty\">No custom lists yet. Use + New List to start one.</p>\n");
    } else {
        out.push_str("<ul class=\"lists-active-list\">\n");
        for col in &manual_lists {
            render_overview_row(out, col, data);
        }
        out.push_str("</ul>\n");
    }

    if !media_active.is_empty() || !media_empty.is_empty() {
        let _ = writeln!(
            out,
            "<div class=\"section-head\"><h3>By Media Type</h3>\
             <span class=\"section-action\">{} active &middot; {} empty</span></div>\n\
             <ul class=\"lists-media-grid\">",
            media_active.len(),
            media_empty.len(),
        );
        for col in &media_active {
            render_overview_row(out, col, data);
        }
        for col in &media_empty {
            let slug = col.slug.as_deref().unwrap_or(&col.id);
            let _ = writeln!(
                out,
                "<li class=\"list-empty-tile\"><a class=\"empty-list-link\" href=\"/lists/{}\"><span>{}</span><span class=\"empty-list-zero\">0</span></a></li>",
                esc(&urlencode(slug)),
                esc(&col.title),
            );
        }
        out.push_str("</ul>\n");
    }

    out.push_str("</section>\n");
}

fn render_new_list_section_head(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<div class=\"lists-section-head\">\n\
           <h3>Your Lists</h3>\n\
           <button type=\"button\" class=\"new-list-trigger js-new-list-toggle\" aria-expanded=\"false\" aria-controls=\"roche-new-list-form\">+ New List</button>\n\
         </div>\n\
         <form method=\"post\" action=\"/web/lists/create\" id=\"roche-new-list-form\" class=\"new-list-inline-form\" hidden>\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <div class=\"new-list-inline-grid\">\n\
             <div class=\"field\">\n\
               <label for=\"list-title\">Title</label>\n\
               <input id=\"list-title\" name=\"title\" type=\"text\" required placeholder=\"e.g. Sci-Fi Canon\">\n\
             </div>\n\
             <div class=\"field\">\n\
               <label for=\"list-summary\">Description (optional)</label>\n\
               <input id=\"list-summary\" name=\"summary\" type=\"text\" placeholder=\"Favorite speculative fiction\">\n\
             </div>\n\
             <button type=\"submit\" class=\"btn btn-primary btn-small\">Create List</button>\n\
           </div>\n\
         </form>\n",
        csrf = esc(csrf),
    );
}

fn render_overview_row(out: &mut String, col: &CollectionSummary, data: &ListsOverviewData<'_>) {
    let slug = col.slug.as_deref().unwrap_or(&col.id);
    let slug_enc = urlencode(slug);
    let href = format!("/lists/{slug_enc}");

    let _ = write!(
        out,
        "<li class=\"list-overview-row\">\n\
           <div class=\"list-overview-main\">\n\
             <div class=\"list-overview-title-row\">\n\
               <a class=\"list-overview-title\" href=\"{href}\">{title}</a>\n\
               <span class=\"list-overview-title-badges\">\n\
                 <span class=\"list-count-badge\">{members}</span>",
        href = esc(&href),
        title = esc(&col.title),
        members = col.member_count,
    );
    if let Some(gs) = data.generators.get(&col.id) {
        render_overview_draw_popover(out, slug, gs, data.csrf);
    }
    out.push_str("</span>\n</div>\n");
    if let Some(summary) = col.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<p class=\"list-overview-summary\">{}</p>", esc(summary));
    }
    out.push_str("</div>\n<div class=\"list-overview-right\">\n");

    if let Some(thumbs) = data.previews.get(&col.id) {
        let safe_thumbs: Vec<(&str, &str)> = thumbs
            .iter()
            .filter_map(|(title, raw_url)| {
                sanitize_non_local_href(raw_url).map(|safe| (title.as_str(), safe))
            })
            .collect();
        if !safe_thumbs.is_empty() {
            out.push_str("<div class=\"list-preview-strip\">");
            for (item_title, img_url) in safe_thumbs {
                let _ = write!(
                    out,
                    "<a href=\"{}\" title=\"{}\" tabindex=\"-1\" aria-hidden=\"true\"><img class=\"list-preview-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a>",
                    esc(&href),
                    esc(item_title),
                    esc(img_url),
                );
            }
            out.push_str("</div>\n");
        }
    }

    if col.member_count >= 2 {
        let rank_href = rank_collection_href(slug, col.role == CollectionRole::Want);
        if col.comparison_count > 0 {
            let comp_word = if col.comparison_count == 1 { "comparison" } else { "comparisons" };
            let _ = writeln!(
                out,
                "<a class=\"btn btn-small btn-rank-list\" href=\"{href}\" title=\"{c} {comp_word}\" aria-label=\"Rank {t} ({c} {comp_word})\"><span>Rank</span><span class=\"btn-rank-comps\">&middot; {c}</span></a>",
                href = esc(&rank_href),
                t = esc(&col.title),
                c = col.comparison_count,
            );
        } else {
            let _ = writeln!(
                out,
                "<a class=\"btn btn-small btn-rank-list\" href=\"{href}\" aria-label=\"Rank {t}\"><span>Rank</span></a>",
                href = esc(&rank_href),
                t = esc(&col.title),
            );
        }
    } else {
        out.push_str(
            "<button type=\"button\" class=\"btn btn-small btn-rank-list\" disabled title=\"Need at least 2 items to rank\"><span>Rank</span></button>\n",
        );
    }

    out.push_str("</div>\n</li>\n");
}

fn render_overview_draw_popover(
    out: &mut String,
    slug: &str,
    gs: &CollectionGeneratorState,
    csrf: &str,
) {
    let slug_enc = urlencode(slug);
    let paused_cls = if gs.enabled { "" } else { " is-paused" };
    let tip = if gs.enabled {
        "Daily draw active — click to Rank, Skip, or Pause"
    } else {
        "Daily draw paused — click to resume"
    };
    let _ = writeln!(
        out,
        "<details class=\"draw-badge-details{paused_cls}\">\n\
           <summary title=\"{tip}\"><span class=\"draw-dot\" aria-hidden=\"true\"></span><span>Draw</span></summary>\n\
           <div class=\"draw-popover\">",
    );
    if gs.enabled {
        if let Some(entry) = &gs.current_entry {
            let entry_href = format!("/entries/{}", urlencode(&entry.id));
            out.push_str("<div class=\"draw-popover-current\">");
            if let Some(thumb) = entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
                let _ = write!(
                    out,
                    "<a href=\"{}\" title=\"Open {}\" tabindex=\"-1\" aria-hidden=\"true\"><img class=\"draw-popover-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a>",
                    esc(&entry_href),
                    esc(&entry.title),
                    esc(thumb),
                );
            }
            let draw_num = (gs.consumed_count + gs.abandoned_count + 1).min(gs.total_pool.max(1));
            let _ = write!(
                out,
                "<div class=\"draw-popover-info\">\
                   <div class=\"draw-popover-kicker\">Current Draw &middot; #{draw_num} of {}</div>\
                   <div class=\"draw-popover-title\"><a href=\"{}\">{}</a></div>",
                gs.total_pool,
                esc(&entry_href),
                esc(&entry.title),
            );
            if !entry.creators.is_empty() {
                let _ = write!(
                    out,
                    "<div class=\"draw-popover-sub\">{}</div>",
                    esc(&entry.creators.join(", ")),
                );
            }
            out.push_str("</div></div>\n<div class=\"draw-popover-actions\">\n<div class=\"flex-row flex-center\">");
            render_inline_csrf_form(
                out,
                &format!("/web/lists/{slug_enc}/generator/finish"),
                csrf,
                &[],
                None,
                "<button type=\"submit\" class=\"btn btn-small btn-primary\">Finish &amp; Rank</button>",
            );
            render_inline_csrf_form(
                out,
                &format!("/web/lists/{slug_enc}/generator/abandon"),
                csrf,
                &[("return_to", "/lists")],
                Some("Skip this draw and advance to the next item?"),
                "<button type=\"submit\" class=\"btn btn-small btn-ghost\">Skip</button>",
            );
            out.push_str("</div>");
        } else {
            let _ = writeln!(
                out,
                "<p class=\"rail-note\">All {} entries completed.</p>\n<div class=\"draw-popover-actions\">",
                gs.total_pool,
            );
        }
        render_inline_csrf_form(
            out,
            &format!("/web/lists/{slug_enc}/generator/toggle"),
            csrf,
            &[("enabled", "false"), ("return_to", "/lists")],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\">Pause Draw</button>",
        );
    } else {
        let _ = writeln!(
            out,
            "<p class=\"rail-note\">Draw paused &middot; #{} of {} completed.</p>\n<div class=\"draw-popover-actions\">",
            gs.consumed_count, gs.total_pool,
        );
        render_inline_csrf_form(
            out,
            &format!("/web/lists/{slug_enc}/generator/toggle"),
            csrf,
            &[("enabled", "true"), ("return_to", "/lists")],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-primary\">Resume Draw</button>",
        );
    }
    out.push_str("</div>\n</div>\n</details>");
}

fn render_lists_rail(
    out: &mut String,
    collections: &[CollectionSummary],
    clean_q: Option<&str>,
    parsed_kind: Option<EntryKind>,
    is_searching: bool,
) {
    let active_count = collections.iter().filter(|c| c.member_count > 0).count();
    let media_entries: usize = collections
        .iter()
        .filter(|c| c.mode == CollectionMode::Dynamic)
        .map(|c| c.member_count)
        .sum();
    let total_entries = if media_entries > 0 {
        media_entries
    } else {
        collections.iter().map(|c| c.member_count).sum()
    };
    let ranked_lists = collections.iter().filter(|c| c.comparison_count > 0).count();

    let _ = write!(
        out,
        "<div class=\"lists-rail\">\n\
           <section class=\"rail-block rail-card\">\n\
             <div class=\"section-head\"><h2>Search Catalog</h2></div>\n\
             <form method=\"get\" action=\"/lists\" class=\"rail-form\">\n\
               <div class=\"field\">\n\
                 <label for=\"cat-q\">Titles, Creators, or Identifiers</label>\n\
                 <input id=\"cat-q\" name=\"q\" type=\"search\" aria-label=\"Search catalog\" value=\"{q}\" placeholder=\"e.g. Frank Zappa, Abbey Road…\">\n\
               </div>\n\
               <div class=\"field\">\n\
                 <label for=\"cat-kind\">Kind</label>\n\
                 <select id=\"cat-kind\" name=\"kind\" aria-label=\"Filter by kind\">{kind_opts}</select>\n\
               </div>\n\
               <div class=\"flex-row flex-center\">\n\
                 <button type=\"submit\" class=\"btn btn-primary btn-small\">Search</button>",
        q = esc(clean_q.unwrap_or("")),
        kind_opts = render_search_kind_options(parsed_kind),
    );
    if is_searching {
        out.push_str("<a class=\"btn btn-small btn-ghost\" href=\"/lists\">Clear</a>");
    }
    let _ = write!(
        out,
        "      </div>\n\
             </form>\n\
           </section>\n\
           <section class=\"rail-block rail-card\">\n\
             <div class=\"section-head\"><h2>Library</h2></div>\n\
             <div class=\"rail-stats\">\n\
               <div><span class=\"rail-stat-num\">{active_count}</span><span class=\"rail-stat-label\">Active</span></div>\n\
               <div><span class=\"rail-stat-num\">{total_entries}</span><span class=\"rail-stat-label\">Entries</span></div>\n\
               <div title=\"Lists with pairwise rankings\"><span class=\"rail-stat-num\">{ranked_lists}</span><span class=\"rail-stat-label\">Ranked</span></div>\n\
             </div>\n\
           </section>\n\
         </div>\n",
    );
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
        render_creator_year_meta(
            out,
            &entry.creators,
            entry.release_year,
            artist_links,
            CreatorYearStyle::InlineSoft { open_new_tab: false },
        );
        let _ =
            writeln!(out, " <span class=\"badge\">{}</span></li>", esc(entry.kind.display_label()));
    }
    out.push_str("</ul>\n");
}

pub(in crate::web) async fn post_list_create(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<CreateListForm>,
) -> Result<Response, WebError> {
    let col = service
        .catalog()
        .create_manual_collection(
            &form.title,
            None,
            CollectionRole::List,
            form.summary.as_deref(),
            None,
        )
        .await
        .web_err(&session)?;
    let slug = col.slug.as_deref().unwrap_or(&col.id);
    Ok(redirect_with_flash(&format!("/lists/{}", urlencode(slug)), "notice", "Created manual list"))
}

fn build_list_href(slug: &str, page: Option<usize>, tier: Option<char>, q: Option<&str>) -> String {
    let mut params = Vec::new();
    if let Some(t) = tier {
        params.push(format!("tier={t}"));
    }
    if let Some(query) = q.map(str::trim).filter(|s| !s.is_empty()) {
        params.push(format!("q={}", urlencode(query)));
    }
    if let Some(p) = page.filter(|&p| p > 1) {
        params.push(format!("page={p}"));
    }
    let base = format!("/lists/{}", urlencode(slug));
    if params.is_empty() { base } else { format!("{base}?{}", params.join("&")) }
}

fn append_query_param(base: &str, key: &str, val: &str) -> String {
    let (path_and_query, frag) = match base.split_once('#') {
        Some((b, f)) => (b, Some(f)),
        None => (base, None),
    };
    let sep = if path_and_query.contains('?') { '&' } else { '?' };
    let mut out = format!("{path_and_query}{sep}{}={}", urlencode(key), urlencode(val));
    if let Some(f) = frag {
        out.push('#');
        out.push_str(f);
    }
    out
}

struct ListDetailRenderCtx<'a> {
    view: &'a RankedCollectionView,
    full_total: usize,
    full_tier_counts: [usize; 5],
    active_tier: Option<char>,
    active_q: Option<&'a str>,
    undo_entry: Option<(String, String)>,
    gen_state: Option<&'a CollectionGeneratorState>,
    page_num: usize,
    total_pages: usize,
    current_href: &'a str,
    artist_links: &'a HashMap<String, String>,
    reader_item_ids: &'a HashSet<String>,
    csrf: &'a str,
}

pub(in crate::web) async fn list_detail_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Query(q): Query<ListPageQuery>,
) -> Result<Response, WebError> {
    let active_tier = q
        .tier
        .as_deref()
        .map(str::trim)
        .and_then(|s| s.chars().next())
        .map(|c| c.to_ascii_uppercase())
        .filter(|c| matches!(c, 'S' | 'A' | 'B' | 'C' | 'D'));
    let active_q = q.q.as_deref().map(str::trim).filter(|s| !s.is_empty());

    let filtered = service
        .catalog()
        .get_filtered_ranked_collection(
            &slug,
            false,
            &RankedCollectionFilter { tier: active_tier, query: active_q },
        )
        .await
        .web_err(&session)?;
    let view = filtered.view;
    let full_total = filtered.full_total;
    let full_tier_counts = filtered.full_tier_counts;
    let total_rows = view.rows.len();
    let total_pages = total_rows.max(1).div_ceil(LIST_PAGE_SIZE);
    let page_num = q.page.unwrap_or(1).clamp(1, total_pages);
    let start_idx = (page_num - 1) * LIST_PAGE_SIZE;
    let end_idx = (page_num * LIST_PAGE_SIZE).min(total_rows);

    let is_editable_manual = view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List
        && !view.collection.has_generator;

    let gen_state = if view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List
    {
        service.catalog().get_collection_generator(&view.collection.id).await.ok()
    } else {
        None
    };

    let undo_entry = if is_editable_manual
        && let Some(undo_id) = q.undo_remove.as_deref().map(str::trim).filter(|s| !s.is_empty())
        && let Ok(Some(detail)) = service.catalog().get_entry_detail(undo_id).await
    {
        Some((detail.entry.id, detail.entry.title))
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

    let canonical_slug = view.collection.slug.as_deref().unwrap_or(&view.collection.id);
    let current_href = build_list_href(canonical_slug, Some(page_num), active_tier, active_q);

    let ctx = ListDetailRenderCtx {
        view: &view,
        full_total,
        full_tier_counts,
        active_tier,
        active_q,
        undo_entry,
        gen_state: gen_state.as_ref(),
        page_num,
        total_pages,
        current_href: &current_href,
        artist_links: &artist_links,
        reader_item_ids: &reader_item_ids,
        csrf: &session.csrf_token,
    };

    let mut body = String::with_capacity(14_336);
    render_ranked_list_body(&mut body, &ctx);

    let active_nav = if view.collection.slug.as_deref() == Some("starred") {
        NavSection::Starred
    } else {
        NavSection::Lists
    };
    Ok(render_page(&PageShell {
        title: &view.collection.title,
        active: active_nav,
        csrf_token: &session.csrf_token,
        flash: &q.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

fn render_ranked_list_body(out: &mut String, ctx: &ListDetailRenderCtx<'_>) {
    let view = ctx.view;
    let slug = view.collection.slug.as_deref().unwrap_or(&view.collection.id);
    let is_manual_list = view.collection.mode == CollectionMode::Manual
        && view.collection.role == CollectionRole::List;
    let is_editable_manual = is_manual_list && !view.collection.has_generator;
    let ranked_mode = view.comparison_count > 0;
    let is_filtered = ctx.active_tier.is_some() || ctx.active_q.is_some();

    let start = (ctx.page_num - 1) * LIST_PAGE_SIZE;
    let end = (start + LIST_PAGE_SIZE).min(view.rows.len());
    let slice = &view.rows[start..end];

    out.push_str(
        "<div class=\"lists-grid\">\n<section class=\"lists-main\" aria-label=\"List entries\">\n",
    );
    let badge_text = if is_filtered {
        format!("{} of {}", view.rows.len(), ctx.full_total)
    } else {
        ctx.full_total.to_string()
    };
    let _ = write!(
        out,
        "<div class=\"list-detail-crumb\"><a class=\"reader-back-link\" href=\"/lists\">&larr; All Lists</a></div>\n\
         <div class=\"list-detail-head\">\n\
           <div class=\"list-detail-title-group\">\n\
             <h2>{}</h2>\n\
             <span class=\"list-count-badge\">{badge_text}</span>\n\
           </div>",
        esc(&view.collection.title),
    );
    if ctx.full_total >= 2 {
        let rank_href = rank_collection_href(slug, view.collection.role == CollectionRole::Want);
        let _ = write!(
            out,
            "<a class=\"btn btn-primary btn-small\" href=\"{}\">Rank</a>",
            esc(&rank_href),
        );
    }
    out.push_str("</div>\n");

    render_list_detail_lede(out, view);
    if let Some((undo_id, undo_title)) = &ctx.undo_entry {
        render_list_undo_banner(out, slug, undo_id, undo_title, ctx.current_href, ctx.csrf);
    }

    if ctx.total_pages > 1 {
        render_list_pager(
            out,
            slug,
            ctx.page_num,
            ctx.total_pages,
            ctx.active_tier,
            ctx.active_q,
            "Pagination (top)",
        );
    }

    render_list_entries_section(out, ctx, slug, slice, ranked_mode, is_editable_manual);

    if ctx.total_pages > 1 {
        render_list_pager(
            out,
            slug,
            ctx.page_num,
            ctx.total_pages,
            ctx.active_tier,
            ctx.active_q,
            "Pagination (bottom)",
        );
    }
    out.push_str("</section>\n");

    render_list_detail_rail(out, ctx, slug, ranked_mode, is_editable_manual);
    out.push_str("</div>\n");
}

fn render_list_undo_banner(
    out: &mut String,
    slug: &str,
    undo_id: &str,
    undo_title: &str,
    current_href: &str,
    csrf: &str,
) {
    let add_action = format!("/web/lists/{}/add", urlencode(slug));
    out.push_str("<div class=\"undo-banner\" role=\"status\"><span>Removed <strong>");
    let _ = write!(out, "{}", esc(undo_title));
    out.push_str("</strong> from this list.</span>");
    render_inline_csrf_form(
        out,
        &add_action,
        csrf,
        &[("title_or_id", undo_id), ("return_to", current_href)],
        None,
        "<button type=\"submit\" class=\"btn btn-small\">Undo</button>",
    );
    out.push_str("</div>\n");
}

fn render_list_entries_section(
    out: &mut String,
    ctx: &ListDetailRenderCtx<'_>,
    slug: &str,
    slice: &[RankedEntryRow],
    ranked_mode: bool,
    is_editable_manual: bool,
) {
    if ctx.full_total == 0 {
        out.push_str("<p class=\"empty\">No entries in this list yet.</p>\n");
        return;
    }
    render_list_toolbar(
        out,
        slug,
        ctx.full_total,
        ranked_mode,
        ctx.full_tier_counts,
        ctx.active_tier,
        ctx.active_q,
    );
    let clear_href = build_list_href(slug, None, None, None);
    if slice.is_empty() {
        let _ = writeln!(
            out,
            "<div id=\"roche-list-empty\" class=\"filter-empty-state\">\
               <p>No entries match the current filter.</p>\
               <a class=\"btn btn-small\" href=\"{}\">Clear filter</a>\
             </div>",
            esc(&clear_href),
        );
        return;
    }
    render_ranked_table(
        out,
        slice,
        ranked_mode,
        is_editable_manual,
        slug,
        ctx.current_href,
        ctx.artist_links,
        ctx.reader_item_ids,
        ctx.csrf,
    );
    let _ = writeln!(
        out,
        "<div id=\"roche-list-empty\" class=\"filter-empty-state\" hidden>\
           <p>No matching entries on this page.</p>\
           <button type=\"button\" class=\"btn btn-small js-list-clear\">Clear filter</button>\
           <a class=\"btn btn-small btn-ghost\" href=\"{}\">Reset all</a>\
         </div>",
        esc(&clear_href),
    );
}

fn render_list_detail_lede(out: &mut String, view: &RankedCollectionView) {
    let summary = view.collection.summary.as_deref().map(str::trim).filter(|s| !s.is_empty());
    if view.comparison_count == 0 && summary.is_none() {
        return;
    }
    out.push_str("<p class=\"list-detail-lede\">");
    if view.comparison_count > 0 {
        let word = if view.comparison_count == 1 { "comparison" } else { "comparisons" };
        let _ = write!(out, "<em>{}</em> {word}", view.comparison_count);
        if let Some(s) = summary {
            let _ = write!(out, " &mdash; {}", esc(s));
        }
    } else if let Some(s) = summary {
        let _ = write!(out, "{}", esc(s));
    }
    out.push_str("</p>\n");
}

fn render_list_toolbar(
    out: &mut String,
    slug: &str,
    full_total: usize,
    ranked_mode: bool,
    tiers: [usize; 5],
    active_tier: Option<char>,
    active_q: Option<&str>,
) {
    out.push_str("<div class=\"list-toolbar\">\n");
    if ranked_mode {
        let all_href = build_list_href(slug, None, None, active_q);
        let all_attrs = if active_tier.is_none() {
            "class=\"pill is-active\" aria-current=\"true\""
        } else {
            "class=\"pill\""
        };
        let _ = writeln!(
            out,
            "<div class=\"list-tier-pills\" role=\"group\" aria-label=\"Filter by tier\">\n\
               <a {all_attrs} href=\"{}\">All ({full_total})</a>",
            esc(&all_href),
        );
        for (idx, letter) in ['S', 'A', 'B', 'C', 'D'].into_iter().enumerate() {
            if tiers[idx] > 0 || active_tier == Some(letter) {
                let is_cur = active_tier == Some(letter);
                let href = build_list_href(slug, None, Some(letter), active_q);
                let active_cls = if is_cur { " is-active" } else { "" };
                let aria_cur = if is_cur { " aria-current=\"true\"" } else { "" };
                let _ = writeln!(
                    out,
                    "<a class=\"pill pill-tier-{letter}{active_cls}\"{aria_cur} href=\"{}\">{letter} ({})</a>",
                    esc(&href),
                    tiers[idx],
                );
            }
        }
        out.push_str("</div>\n");
    }
    let form_action = format!("/lists/{}", urlencode(slug));
    let _ = write!(
        out,
        "<form method=\"get\" action=\"{}\" class=\"list-filter-form\">",
        esc(&form_action),
    );
    if let Some(t) = active_tier {
        let _ = write!(out, "<input type=\"hidden\" name=\"tier\" value=\"{t}\">");
    }
    let _ = write!(
        out,
        "<input type=\"search\" id=\"roche-list-search\" name=\"q\" value=\"{}\" class=\"list-filter-input\" placeholder=\"Filter list…\" aria-label=\"Filter entries in this list\">\
         <button type=\"submit\" class=\"btn btn-small btn-ghost\">Filter</button>",
        esc(active_q.unwrap_or("")),
    );
    if active_tier.is_some() || active_q.is_some() {
        let clear_href = build_list_href(slug, None, None, None);
        let _ = write!(
            out,
            "<a class=\"btn btn-small btn-ghost\" href=\"{}\">Clear</a>",
            esc(&clear_href),
        );
    }
    out.push_str("</form>\n</div>\n");
}

#[allow(clippy::too_many_arguments)]
fn render_ranked_table(
    out: &mut String,
    slice: &[RankedEntryRow],
    ranked_mode: bool,
    is_editable_manual: bool,
    slug: &str,
    current_href: &str,
    artist_links: &HashMap<String, String>,
    reader_item_ids: &HashSet<String>,
    csrf: &str,
) {
    out.push_str("<table class=\"ranked\">\n<thead><tr><th scope=\"col\" class=\"num\">#</th>");
    if ranked_mode {
        out.push_str(
            "<th scope=\"col\">Tier</th><th scope=\"col\">Title</th><th scope=\"col\" class=\"score num\">Score</th>",
        );
    } else {
        out.push_str("<th scope=\"col\">Title</th>");
    }
    if is_editable_manual {
        out.push_str("<th scope=\"col\"><span class=\"sr-only\">Actions</span></th>");
    }
    out.push_str("</tr></thead>\n<tbody id=\"roche-ranked-tbody\">\n");

    let mut prev_tier: Option<char> = None;
    for (idx, row) in slice.iter().enumerate() {
        let tier_break = ranked_mode && idx > 0 && row.tier != prev_tier;
        prev_tier = row.tier;
        render_ranked_table_row(
            out,
            row,
            ranked_mode,
            tier_break,
            is_editable_manual,
            slug,
            current_href,
            artist_links,
            reader_item_ids,
            csrf,
        );
    }
    out.push_str("</tbody>\n</table>\n");
}

#[allow(clippy::too_many_arguments)]
fn render_ranked_table_row(
    out: &mut String,
    row: &RankedEntryRow,
    ranked_mode: bool,
    tier_break: bool,
    is_editable_manual: bool,
    slug: &str,
    current_href: &str,
    artist_links: &HashMap<String, String>,
    reader_item_ids: &HashSet<String>,
    csrf: &str,
) {
    let tr_cls = if tier_break { " class=\"tier-break\"" } else { "" };
    let tier_attr = row.tier.map_or_else(String::new, |t| t.to_string());
    let creators_joined = row.entry.creators.join(" ");
    let year_str = row.entry.release_year.map_or_else(String::new, |y| y.to_string());
    let search_blob = format!("{} {creators_joined} {year_str}", row.entry.title);
    let entry_id_enc = urlencode(&row.entry.id);
    let entry_href = format!("/entries/{entry_id_enc}");

    let _ = write!(
        out,
        "<tr{tr_cls} data-tier=\"{tier_attr}\" data-work-search=\"{}\"><td class=\"rank num\">{}</td>",
        esc(&search_blob),
        row.rank,
    );
    if ranked_mode {
        let tier_char = row.tier.unwrap_or('·');
        let tier_cls = row.tier.map_or_else(String::new, |t| format!(" tier-{t}"));
        let _ = write!(out, "<td class=\"tier{tier_cls}\">{tier_char}</td>");
    }

    out.push_str("<td class=\"work\"><div class=\"ranked-work-cell\">");
    if let Some(thumb) = row.entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
        let _ = write!(
            out,
            "<a class=\"ranked-thumb-link\" href=\"{}\" tabindex=\"-1\" aria-hidden=\"true\"><img class=\"ranked-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a>",
            esc(&entry_href),
            esc(thumb),
        );
    }
    let _ = write!(
        out,
        "<div class=\"ranked-work-text\"><span class=\"title\"><a href=\"{}\">{}</a></span>",
        esc(&entry_href),
        esc(&row.entry.title),
    );
    render_creator_year_meta(
        out,
        &row.entry.creators,
        row.entry.release_year,
        artist_links,
        CreatorYearStyle::TableByYear,
    );
    if reader_item_ids.contains(&row.entry.id) {
        let _ = write!(
            out,
            " <a class=\"badge ml-xs\" href=\"/reader/{}?return_to={}\">Read &rarr;</a>",
            esc(&entry_id_enc),
            esc(&urlencode(current_href)),
        );
    }
    out.push_str("</div></div></td>");

    if ranked_mode {
        let tick_pct = row.bar_pct.clamp(4.0, 96.0);
        let sigma = row.score.variance.max(0.0).sqrt();
        let half_w = (sigma * 14.0).clamp(3.0, 22.0);
        let ci_x1 = (tick_pct - half_w).max(2.0);
        let ci_x2 = (tick_pct + half_w).min(98.0);
        let _ = write!(
            out,
            "<td class=\"score\"><div class=\"score-mark-wrap\" title=\"μ = {mean:.2}, σ = {sigma:.2}\">\
               <span class=\"sr-only\">Score {mean:.2} (±{sigma:.2})</span>\
               <svg class=\"score-mark\" viewBox=\"0 0 100 12\" width=\"100\" height=\"12\" aria-hidden=\"true\">\
                 <line class=\"score-axis\" x1=\"0\" y1=\"6\" x2=\"100\" y2=\"6\"/>\
                 <line class=\"score-mid\" x1=\"50\" y1=\"2\" x2=\"50\" y2=\"10\"/>\
                 <line class=\"score-ci\" x1=\"{ci_x1:.1}\" y1=\"6\" x2=\"{ci_x2:.1}\" y2=\"6\"/>\
                 <circle class=\"tick\" cx=\"{tick_pct:.1}\" cy=\"6\" r=\"3.4\"/>\
               </svg></div></td>",
            mean = row.score.mean,
        );
    }

    if is_editable_manual {
        let remove_action = format!("/web/lists/{}/remove/{entry_id_enc}", urlencode(slug));
        let remove_btn = format!(
            "<button type=\"submit\" class=\"link-btn\" title=\"Remove from list\" aria-label=\"Remove {} from list\"><span aria-hidden=\"true\">&times;</span></button>",
            esc(&row.entry.title)
        );
        out.push_str("<td class=\"num\">");
        render_inline_csrf_form(
            out,
            &remove_action,
            csrf,
            &[("return_to", current_href)],
            None,
            &remove_btn,
        );
        out.push_str("</td>");
    }
    out.push_str("</tr>\n");
}

fn render_list_detail_rail(
    out: &mut String,
    ctx: &ListDetailRenderCtx<'_>,
    slug: &str,
    ranked_mode: bool,
    is_editable_manual: bool,
) {
    out.push_str("<div class=\"lists-rail\">\n");
    if let Some(gs) = ctx.gen_state
        && gs.enabled
    {
        render_collection_draw_section(out, gs, ctx.artist_links, ctx.csrf, ctx.current_href);
    }

    if ranked_mode {
        let max_tc = ctx.full_tier_counts.into_iter().max().unwrap_or(1).max(1);
        out.push_str(
            "<section class=\"rail-block rail-card\">\n\
               <div class=\"section-head\"><h3>Tier Distribution</h3></div>\n\
               <div class=\"tier-dist\">\n",
        );
        for (idx, letter) in ['S', 'A', 'B', 'C', 'D'].into_iter().enumerate() {
            let cnt = ctx.full_tier_counts[idx];
            let is_cur = ctx.active_tier == Some(letter);
            let target_tier = if is_cur { None } else { Some(letter) };
            let href = build_list_href(slug, None, target_tier, ctx.active_q);
            let active_cls = if is_cur { " is-active" } else { "" };
            let aria_cur = if is_cur { " aria-current=\"true\"" } else { "" };
            let _ = writeln!(
                out,
                "<a class=\"tier-dist-row{active_cls}\"{aria_cur} href=\"{}\">\
                   <span class=\"tier-dist-letter tier-{letter}\">{letter}</span>\
                   <progress class=\"tier-bar tier-bar-{letter}\" value=\"{cnt}\" max=\"{max_tc}\"></progress>\
                   <span class=\"tier-dist-count\">{cnt}</span>\
                 </a>",
                esc(&href),
            );
        }
        out.push_str("</div>\n</section>\n");
    }

    if let Some(gs) = ctx.gen_state {
        let slug_enc = urlencode(slug);
        let next_enabled = if gs.enabled { "false" } else { "true" };
        let label = if gs.enabled { "Pause Draw" } else { "Enable Draw" };
        let status_txt = if gs.enabled { "Daily draw active" } else { "Daily draw paused" };
        let toggle_action = format!("/web/lists/{slug_enc}/generator/toggle");
        let btn_html =
            format!("<button type=\"submit\" class=\"btn btn-small btn-ghost\">{label}</button>");
        let _ = write!(
            out,
            "<section class=\"rail-block rail-card\">\n\
               <div class=\"section-head\"><h3>Collection Draw</h3></div>\n\
               <div class=\"rail-opml-actions\">\n\
                 <span class=\"text-soft rail-opml-note\">{status_txt}</span>",
        );
        render_inline_csrf_form(
            out,
            &toggle_action,
            ctx.csrf,
            &[("enabled", next_enabled), ("redirect_list", "true")],
            None,
            &btn_html,
        );
        out.push_str("</div>\n</section>\n");
    } else if !ranked_mode {
        out.push_str(
            "<section class=\"rail-block rail-card\">\n\
               <div class=\"section-head\"><h3>Rankings</h3></div>\n\
               <p class=\"rail-note\">Compare entries head-to-head to infer S–D tiers and scores.</p>",
        );
        if ctx.full_total >= 2 {
            let rank_href =
                rank_collection_href(slug, ctx.view.collection.role == CollectionRole::Want);
            let _ = writeln!(
                out,
                "<a class=\"btn btn-primary btn-small\" href=\"{}\">Start Ranking</a>",
                esc(&rank_href),
            );
        } else {
            out.push_str(
                "<span class=\"text-faint rail-opml-note\">Add at least 2 entries to rank.</span>",
            );
        }
        out.push_str("</section>\n");
    }

    if is_editable_manual {
        render_manual_list_add_panel(out, slug, ctx.full_total == 0, ctx.csrf);
    }
    out.push_str("</div>\n");
}

fn render_manual_list_add_panel(out: &mut String, slug: &str, default_open: bool, csrf: &str) {
    let open_attrs = if default_open { " open data-default-open" } else { "" };
    let _ = write!(
        out,
        "<section class=\"rail-block rail-card\">\n\
           <details class=\"editorial-panel rail-editorial-panel\"{open_attrs}>\n\
             <summary>+ Add Work to List</summary>\n\
             <form method=\"post\" action=\"/web/lists/{slug_enc}/add\" class=\"rail-form\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <div class=\"field\"><label for=\"add-title\">Title or Existing Entry ID</label>\n\
                 <input id=\"add-title\" name=\"title_or_id\" type=\"text\" required placeholder=\"e.g. Stalker\"></div>\n\
               <div class=\"field\"><label for=\"add-kind\">Kind (Auto matches existing)</label>\n\
                 <select id=\"add-kind\" name=\"kind\">{kind_opts}</select></div>\n\
               <div class=\"field\"><label for=\"add-creators\">Creators (comma-separated)</label>\n\
                 <input id=\"add-creators\" name=\"creators\" type=\"text\" placeholder=\"Andrei Tarkovsky\"></div>\n\
               <div class=\"field\"><label for=\"add-year\">Year</label>\n\
                 <input id=\"add-year\" name=\"release_year\" type=\"number\" min=\"1000\" max=\"2100\" placeholder=\"1979\"></div>\n\
               <button type=\"submit\" class=\"btn btn-primary btn-small\">Add to List</button>\n\
             </form>\n\
           </details>\n\
         </section>\n",
        slug_enc = esc(&urlencode(slug)),
        csrf = esc(csrf),
        kind_opts = render_optional_kind_options(),
    );
}

fn render_list_pager(
    out: &mut String,
    slug: &str,
    page_num: usize,
    total_pages: usize,
    active_tier: Option<char>,
    active_q: Option<&str>,
    aria_label: &str,
) {
    let _ = write!(out, "<nav class=\"pager\" aria-label=\"{}\">", esc(aria_label));
    if page_num > 1 {
        let href = build_list_href(slug, Some(page_num - 1), active_tier, active_q);
        let _ = write!(
            out,
            "<a href=\"{}\"><span aria-hidden=\"true\">&larr;</span> Prev</a>",
            esc(&href),
        );
    }
    for p in 1..=total_pages {
        if p == page_num {
            let _ = write!(out, "<span class=\"current\" aria-current=\"page\">{p}</span>");
        } else if p == 1 || p == total_pages || p.abs_diff(page_num) <= 2 {
            let href = build_list_href(slug, Some(p), active_tier, active_q);
            let _ = write!(out, "<a href=\"{}\">{p}</a>", esc(&href));
        } else if p.abs_diff(page_num) == 3 {
            out.push_str("<span aria-hidden=\"true\">&hellip;</span>");
        }
    }
    if page_num < total_pages {
        let href = build_list_href(slug, Some(page_num + 1), active_tier, active_q);
        let _ = write!(
            out,
            "<a href=\"{}\">Next <span aria-hidden=\"true\">&rarr;</span></a>",
            esc(&href),
        );
    }
    out.push_str("</nav>\n");
}

async fn infer_dominant_list_kind(service: &RocheService, slug: &str) -> EntryKind {
    let Ok(view) = service.catalog().get_ranked_collection(slug, false).await else {
        return EntryKind::Movie;
    };
    let mut counts: HashMap<EntryKind, usize> = HashMap::new();
    for row in &view.rows {
        if row.entry.kind != EntryKind::Collection {
            *counts.entry(row.entry.kind).or_insert(0) += 1;
        }
    }
    counts.into_iter().max_by_key(|(_, c)| *c).map_or(EntryKind::Movie, |(k, _)| k)
}

pub(in crate::web) async fn post_list_add_entry(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Form(form): Form<AddListEntryForm>,
) -> Result<Response, WebError> {
    let fallback = format!("/lists/{}", urlencode(&slug));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    let query = form.title_or_id.trim();
    if query.is_empty() {
        return Ok(redirect_with_flash(&dest, "err", "Title cannot be empty"));
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

    let (entry, linked_existing) = if can_fuzzy_resolve
        && let Ok(existing) = service.catalog().resolve_entry(query, parsed_kind, None).await
    {
        (existing, true)
    } else {
        let kind = match parsed_kind {
            Some(k) => k,
            None => infer_dominant_list_kind(&service, &slug).await,
        };
        let input = UpsertEntryInput {
            kind: Some(kind),
            title: query.to_owned(),
            creators,
            release_year: year,
            ..UpsertEntryInput::default()
        };
        (service.catalog().upsert_entry(&input).await.web_err(&session)?, false)
    };

    service.catalog().add_collection_entry(&slug, &entry.id, None).await.web_err(&session)?;
    let msg = if linked_existing {
        format!("Linked existing {} '{}' to list", entry.kind.display_label(), entry.title)
    } else {
        format!("Created new {} '{}' and added to list", entry.kind.display_label(), entry.title)
    };
    Ok(redirect_with_flash(&dest, "notice", &msg))
}

pub(in crate::web) async fn post_list_remove_entry(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path((slug, entry_id)): Path<(String, String)>,
    Form(form): Form<ListRemoveForm>,
) -> Result<Response, WebError> {
    let _ = service.catalog().remove_collection_entry(&slug, &entry_id).await.web_err(&session)?;
    let fallback = format!("/lists/{}", urlencode(&slug));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    let final_dest = if dest.starts_with("/lists/") {
        append_query_param(&dest, "undo_remove", &entry_id)
    } else {
        dest
    };
    Ok(redirect_with_flash(&final_dest, "notice", "Removed work from list"))
}
