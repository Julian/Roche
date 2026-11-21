use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    Form,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use chrono::Datelike as _;

use super::{
    DiaryDeleteForm, DiaryEditForm, DiaryFilterQuery, QueuePageQuery, QuickLogForm,
    WantCompleteForm, WantRemoveForm, load_existing_reader_item_ids, parse_consumption_status,
    parse_diary_date_input, parse_optional_year, render_kind_options,
};
use crate::{
    db::{CatalogEntry, CollectionSummary, ConsumptionLogView, UpsertEntryInput},
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    service::{CatalogService, RankedCollectionView, RocheService},
    web::{
        auth::WebSession,
        html::{
            NavSection, PageShell, WebError, WithWebCsrf as _, consumption_status_label, esc,
            parse_csv_list, parse_form_bool, rank_collection_href, redirect_with_flash,
            render_inline_csrf_form, render_page, safe_local_redirect, urlencode,
        },
        links::{load_artist_id_by_lower_name, render_creator_links, sanitize_non_local_href},
    },
};

const DIARY_PAGE_SIZE: usize = 200;

struct DiaryStats {
    total_all_years: usize,
    year_counts: BTreeMap<i32, usize>,
    cadence_year: Option<i32>,
    cadence_months: Vec<(String, usize)>,
}

impl DiaryStats {
    fn from_sql_years_and_entries(
        sql_year_counts: &[(i32, i64)],
        visible_entries: &[ConsumptionLogView],
        selected_year: Option<i32>,
    ) -> Self {
        let mut year_counts: BTreeMap<i32, usize> = BTreeMap::new();
        let mut total_all_years = 0usize;
        for &(yr, cnt) in sql_year_counts {
            let n = usize::try_from(cnt).unwrap_or(0);
            year_counts.insert(yr, n);
            total_all_years = total_all_years.saturating_add(n);
        }
        let cadence_year = selected_year.or_else(|| year_counts.keys().next_back().copied());
        let mut by_month: BTreeMap<u32, (String, usize)> = BTreeMap::new();
        if let Some(target_yr) = cadence_year {
            for item in visible_entries {
                if item.ts.year() == target_yr {
                    let m = item.ts.month();
                    let label = item.ts.format("%b").to_string();
                    by_month.entry(m).or_insert((label, 0)).1 += 1;
                }
            }
        }
        let cadence_months: Vec<(String, usize)> = by_month.into_values().rev().take(8).collect();
        Self { total_all_years, year_counts, cadence_year, cadence_months }
    }
}

pub(in crate::web) async fn diary_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(q): Query<DiaryFilterQuery>,
) -> Result<Response, WebError> {
    let status_filter = match q.status.as_deref() {
        Some("consumed") => Some(ConsumptionStatus::Consumed),
        Some("started") => Some(ConsumptionStatus::Started),
        Some("abandoned") => Some(ConsumptionStatus::Abandoned),
        _ => None,
    };
    let catalog = service.catalog();
    let sql_year_counts = catalog.list_diary_year_counts(status_filter).await.web_err(&session)?;
    let total_all_years: usize = sql_year_counts
        .iter()
        .map(|(_, c)| usize::try_from(*c).unwrap_or(0))
        .fold(0usize, usize::saturating_add);
    let total_for_filter: usize = q.year.map_or(total_all_years, |yr| {
        sql_year_counts
            .iter()
            .find(|(y, _)| *y == yr)
            .map_or(0, |(_, c)| usize::try_from(*c).unwrap_or(0))
    });

    let total_pages = total_for_filter.max(1).div_ceil(DIARY_PAGE_SIZE);
    let page_num = q.page.unwrap_or(1).clamp(1, total_pages);
    let offset = (page_num - 1) * DIARY_PAGE_SIZE;

    let entries = catalog
        .list_diary_page(q.year, status_filter, DIARY_PAGE_SIZE, offset)
        .await
        .web_err(&session)?;
    let stats = DiaryStats::from_sql_years_and_entries(&sql_year_counts, &entries, q.year);

    let diary_creators: Vec<String> =
        entries.iter().flat_map(|e| e.entry.creators.iter().cloned()).collect();
    let (artist_links, diary_tiers) = tokio::join!(
        load_artist_id_by_lower_name(&service, &diary_creators),
        load_diary_entry_tiers(&service, &entries),
    );
    let months = group_diary_by_month(&entries);
    let active_status = status_filter.map_or("", ConsumptionStatus::as_str);
    let current_href =
        build_diary_href_page(q.year, active_status, (page_num > 1).then_some(page_num));

    let mut body = String::with_capacity(12_288);
    body.push_str(
        "<div class=\"diary-grid\">\n<section class=\"diary-main\" aria-label=\"Diary log\">\n",
    );
    let _ = writeln!(
        body,
        "<div class=\"list-detail-head\">\n\
           <div class=\"list-detail-title-group\">\n\
             <h2>Diary</h2>\n\
             <span class=\"list-count-badge\">{total_for_filter}</span>\n\
           </div>\n\
         </div>",
    );

    render_diary_toolbar(&mut body, q.year, status_filter);
    if total_pages > 1 {
        render_diary_pager(
            &mut body,
            q.year,
            active_status,
            page_num,
            total_pages,
            "Diary pagination (top)",
        );
    }
    render_diary_months(
        &mut body,
        &months,
        &artist_links,
        &diary_tiers,
        &session.csrf_token,
        &current_href,
    );
    if total_pages > 1 {
        render_diary_pager(
            &mut body,
            q.year,
            active_status,
            page_num,
            total_pages,
            "Diary pagination (bottom)",
        );
    }
    body.push_str("</section>\n");

    render_diary_rail(&mut body, &stats, q.year, status_filter, &session.csrf_token);
    body.push_str("</div>\n");

    Ok(render_page(&PageShell {
        title: "Diary",
        active: NavSection::Diary,
        csrf_token: &session.csrf_token,
        flash: &q.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

fn group_diary_by_month(entries: &[ConsumptionLogView]) -> Vec<(String, Vec<&ConsumptionLogView>)> {
    let mut months: Vec<(String, Vec<&ConsumptionLogView>)> = Vec::new();
    for item in entries {
        let month_label = item.ts.format("%B %Y").to_string();
        if let Some((last_label, bucket)) = months.last_mut()
            && *last_label == month_label
        {
            bucket.push(item);
        } else {
            months.push((month_label, vec![item]));
        }
    }
    months
}

async fn load_diary_entry_tiers(
    service: &RocheService,
    entries: &[ConsumptionLogView],
) -> HashMap<String, char> {
    let consumed: Vec<CatalogEntry> = entries
        .iter()
        .filter(|d| d.status == ConsumptionStatus::Consumed)
        .map(|d| d.entry.clone())
        .collect();
    service.catalog().load_tiers_for_entries(&consumed).await
}

fn build_diary_href(year: Option<i32>, status: &str) -> String {
    build_diary_href_page(year, status, None)
}

fn build_diary_href_page(year: Option<i32>, status: &str, page: Option<usize>) -> String {
    let mut params = Vec::new();
    if let Some(yr) = year {
        params.push(format!("year={yr}"));
    }
    if !status.is_empty() {
        params.push(format!("status={status}"));
    }
    if let Some(p) = page.filter(|&p| p > 1) {
        params.push(format!("page={p}"));
    }
    if params.is_empty() { "/diary".to_owned() } else { format!("/diary?{}", params.join("&")) }
}

fn render_diary_pager(
    out: &mut String,
    year: Option<i32>,
    status: &str,
    page_num: usize,
    total_pages: usize,
    aria_label: &str,
) {
    let _ = write!(out, "<nav class=\"pager\" aria-label=\"{}\">", esc(aria_label));
    if page_num > 1 {
        let href = build_diary_href_page(year, status, Some(page_num - 1));
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
            let href = build_diary_href_page(year, status, Some(p));
            let _ = write!(out, "<a href=\"{}\">{p}</a>", esc(&href));
        } else if p.abs_diff(page_num) == 3 {
            out.push_str("<span aria-hidden=\"true\">&hellip;</span>");
        }
    }
    if page_num < total_pages {
        let href = build_diary_href_page(year, status, Some(page_num + 1));
        let _ = write!(
            out,
            "<a href=\"{}\">Next <span aria-hidden=\"true\">&rarr;</span></a>",
            esc(&href),
        );
    }
    out.push_str("</nav>\n");
}

fn render_diary_toolbar(
    out: &mut String,
    selected_year: Option<i32>,
    status_filter: Option<ConsumptionStatus>,
) {
    let active_status = status_filter.map_or("", ConsumptionStatus::as_str);
    out.push_str(
        "<div class=\"diary-toolbar\">\n\
           <div class=\"diary-toolbar-pills\" role=\"group\" aria-label=\"Filter by status\">\n",
    );
    for (slug, label) in
        [("", "All"), ("consumed", "Finished"), ("started", "Started"), ("abandoned", "Skipped")]
    {
        let is_cur = active_status == slug;
        let attrs = if is_cur {
            "class=\"pill is-active\" aria-current=\"true\""
        } else {
            "class=\"pill\""
        };
        let href = build_diary_href(selected_year, slug);
        let _ = writeln!(out, "<a {attrs} href=\"{}\">{label}</a>", esc(&href));
    }
    out.push_str(
        "</div>\n\
         <input type=\"search\" id=\"roche-diary-search\" class=\"diary-filter-input\" placeholder=\"Filter diary…\" aria-label=\"Filter diary entries\">\n\
       </div>\n",
    );
}

fn render_diary_months(
    out: &mut String,
    months: &[(String, Vec<&ConsumptionLogView>)],
    artist_links: &HashMap<String, String>,
    diary_tiers: &HashMap<String, char>,
    csrf: &str,
    return_to: &str,
) {
    if months.is_empty() {
        out.push_str("<p class=\"empty\">No diary entries match this filter.</p>\n");
        return;
    }
    out.push_str("<div id=\"roche-diary-months\">\n");
    for (month_heading, rows) in months {
        let _ = writeln!(
            out,
            "<section class=\"diary-month\">\n\
               <div class=\"diary-month-head\">\n\
                 <h3>{}</h3>\n\
                 <span class=\"diary-month-count\">{}</span>\n\
               </div>",
            esc(month_heading),
            rows.len(),
        );
        for row in rows {
            render_diary_page_row(out, row, artist_links, diary_tiers, csrf, return_to);
        }
        out.push_str("</section>\n");
    }
    out.push_str(
        "</div>\n\
         <div id=\"roche-diary-empty\" class=\"filter-empty-state\" hidden>\n\
           <p>No diary entries match your search on this page.</p>\n\
           <button type=\"button\" class=\"btn btn-small js-diary-clear\">Clear filter</button>\n\
         </div>\n",
    );
}

fn render_diary_page_row(
    out: &mut String,
    row: &ConsumptionLogView,
    artist_links: &HashMap<String, String>,
    diary_tiers: &HashMap<String, char>,
    csrf: &str,
    return_to: &str,
) {
    let entry_href = format!("/entries/{}", urlencode(&row.entry.id));
    let creators_str = row.entry.creators.join(" ");
    let note_str = row.notes.as_deref().map_or("", str::trim);
    let search_blob =
        format!("{} {creators_str} {} {note_str}", row.entry.title, row.entry.kind.display_label());
    let log_id_enc = urlencode(&row.id);
    let date_iso = row.ts.format("%Y-%m-%d").to_string();
    let sel_finished = if row.status == ConsumptionStatus::Consumed { " selected" } else { "" };
    let sel_started = if row.status == ConsumptionStatus::Started { " selected" } else { "" };
    let sel_skipped = if row.status == ConsumptionStatus::Abandoned { " selected" } else { "" };

    let _ = write!(
        out,
        "<div class=\"diary-entry-row\" data-diary-search=\"{}\">\n\
           <span class=\"diary-entry-date\">{}</span>\n\
           <div class=\"diary-entry-body\">",
        esc(&search_blob),
        row.ts.format("%b %-d"),
    );
    if let Some(thumb) = row.entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
        let _ = write!(
            out,
            "<a class=\"diary-thumb-link\" href=\"{}\" tabindex=\"-1\" aria-hidden=\"true\"><img class=\"diary-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a>",
            esc(&entry_href),
            esc(thumb),
        );
    }
    let _ = write!(
        out,
        "<div class=\"diary-entry-text\">\
           <div class=\"diary-entry-title-line\"><a class=\"diary-entry-title\" href=\"{}\">{}</a>",
        esc(&entry_href),
        esc(&row.entry.title),
    );
    if !row.entry.creators.is_empty() {
        out.push_str(" <span class=\"diary-entry-by\">&mdash; ");
        render_creator_links(out, &row.entry.creators, artist_links, false);
        out.push_str("</span>");
    }
    out.push_str("</div>");
    if !note_str.is_empty() {
        let _ = write!(out, "<p class=\"diary-entry-note\">{}</p>", esc(note_str));
    }
    let _ = write!(
        out,
        "</div></div>\n\
           <div class=\"diary-entry-right\">\
             <span class=\"diary-entry-kind\">{}</span>",
        esc(row.entry.kind.display_label()),
    );
    let tier = if row.status == ConsumptionStatus::Consumed {
        diary_tiers.get(&row.entry.id).copied()
    } else {
        None
    };
    if let Some(t) = tier {
        let _ = write!(out, "<span class=\"tier-pill tier-{t}\" title=\"Tier {t}\">{t}</span>");
    } else if row.status != ConsumptionStatus::Consumed {
        let _ =
            write!(out, "<span class=\"badge\">{}</span>", consumption_status_label(row.status));
    }
    let _ = write!(
        out,
        "<div class=\"diary-row-actions\">\
           <details class=\"diary-edit-details\">\
             <summary class=\"link-btn\" aria-label=\"Edit diary entry for {title}\">Edit</summary>\
             <form method=\"post\" action=\"/web/diary/{log_id_enc}/edit\" class=\"diary-edit-form\">\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\
               <input type=\"hidden\" name=\"return_to\" value=\"{ret}\">\
               <div class=\"diary-edit-row\">\
                 <select name=\"status\" aria-label=\"Diary status\">\
                   <option value=\"consumed\"{sel_finished}>Finished</option>\
                   <option value=\"started\"{sel_started}>Started</option>\
                   <option value=\"abandoned\"{sel_skipped}>Skipped</option>\
                 </select>\
                 <input type=\"date\" name=\"date\" value=\"{date_iso}\" aria-label=\"Diary date\">\
               </div>\
               <textarea name=\"notes\" class=\"textarea-compact\" placeholder=\"Note…\" aria-label=\"Diary note\">{note_esc}</textarea>\
               <button type=\"submit\" class=\"btn btn-primary btn-small\">Save</button>\
             </form>\
           </details>",
        title = esc(&row.entry.title),
        csrf = esc(csrf),
        ret = esc(return_to),
        note_esc = esc(note_str),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/diary/{log_id_enc}/delete"),
        csrf,
        &[("return_to", return_to)],
        Some("Delete this diary entry?"),
        "<button type=\"submit\" class=\"link-btn link-btn-danger\" aria-label=\"Delete diary entry\">&times;</button>",
    );
    out.push_str("</div></div>\n</div>\n");
}

fn render_diary_rail(
    out: &mut String,
    stats: &DiaryStats,
    selected_year: Option<i32>,
    status_filter: Option<ConsumptionStatus>,
    csrf: &str,
) {
    let active_status = status_filter.map_or("", ConsumptionStatus::as_str);
    out.push_str("<div class=\"diary-rail\">\n");

    if let Some(yr) = stats.cadence_year
        && !stats.cadence_months.is_empty()
    {
        let yr_total = stats.year_counts.get(&yr).copied().unwrap_or(0);
        let max_m = stats.cadence_months.iter().map(|(_, c)| *c).max().unwrap_or(1).max(1);
        let _ = writeln!(
            out,
            "<section class=\"rail-block rail-card\">\n\
               <div class=\"section-head\"><h3>{yr} Pace</h3><span class=\"section-action\">{yr_total} logged</span></div>\n\
               <div class=\"diary-cadence\">",
        );
        for (lbl, cnt) in &stats.cadence_months {
            let _ = writeln!(
                out,
                "<div class=\"diary-cadence-row\">\
                   <span class=\"diary-cadence-label\">{}</span>\
                   <progress class=\"diary-cadence-bar\" value=\"{cnt}\" max=\"{max_m}\"></progress>\
                   <span class=\"diary-cadence-val\">{cnt}</span>\
                 </div>",
                esc(lbl),
            );
        }
        out.push_str("</div>\n</section>\n");
    }

    if !stats.year_counts.is_empty() {
        out.push_str(
            "<section class=\"rail-block rail-card\">\n\
               <div class=\"section-head\"><h3>By Year</h3></div>\n\
               <ul class=\"year-links\">\n",
        );
        let all_cls = if selected_year.is_none() { " class=\"is-active\"" } else { "" };
        let all_href = build_diary_href(None, active_status);
        let _ = writeln!(
            out,
            "<li><a{all_cls} href=\"{}\"><span>All Years</span><span class=\"list-count-badge\">{}</span></a></li>",
            esc(&all_href),
            stats.total_all_years,
        );
        for (&yr, &cnt) in stats.year_counts.iter().rev() {
            let cls = if selected_year == Some(yr) { " class=\"is-active\"" } else { "" };
            let href = build_diary_href(Some(yr), active_status);
            let _ = writeln!(
                out,
                "<li><a{cls} href=\"{}\"><span>{yr}</span><span class=\"list-count-badge\">{cnt}</span></a></li>",
                esc(&href),
            );
        }
        out.push_str("</ul>\n</section>\n");
    }

    let _ = write!(
        out,
        "<section class=\"rail-block rail-card\">\n\
           <div class=\"section-head\"><h3>Quick Log</h3></div>\n\
           <form method=\"post\" action=\"/web/log\" class=\"rail-form\">\n\
             <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
             <input type=\"hidden\" name=\"action\" value=\"consumed\">\n\
             <div class=\"field\"><label for=\"d-title\">Title</label>\n\
               <input id=\"d-title\" name=\"title\" type=\"text\" required placeholder=\"e.g. Drive My Car\"></div>\n\
             <div class=\"field\"><label for=\"d-kind\">Kind</label>\n\
               <select id=\"d-kind\" name=\"kind\">{kind_opts}</select></div>\n\
             <div class=\"field\"><label for=\"d-creators\">Creator</label>\n\
               <input id=\"d-creators\" name=\"creators\" type=\"text\" placeholder=\"Artist / Author / Director\"></div>\n\
             <div class=\"field\"><label for=\"d-notes\">Notes (optional)</label>\n\
               <textarea id=\"d-notes\" name=\"notes\" class=\"textarea-compact\" placeholder=\"Optional note…\"></textarea></div>\n\
             <div class=\"field field-check\">\n\
               <input id=\"d-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked>\n\
               <label for=\"d-rank\">Rank after logging</label></div>\n\
             <button type=\"submit\" class=\"btn btn-primary btn-small\">Save to Diary</button>\n\
           </form>\n\
         </section>\n\
       </div>\n",
        csrf = esc(csrf),
        kind_opts = render_kind_options(EntryKind::Album),
    );
}

pub(in crate::web) async fn queue_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Query(q): Query<QueuePageQuery>,
) -> Result<Response, WebError> {
    let all_cols = service.catalog().list_collections(true, false).await.web_err(&session)?;

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

    let undo_banner = if let Some(eid) =
        q.undo_entry.as_deref().map(str::trim).filter(|s| !s.is_empty())
        && let Some(q_slug) = q.undo_queue.as_deref().map(str::trim).filter(|s| !s.is_empty())
        && let Ok(Some(detail)) = service.catalog().get_entry_detail(eid).await
    {
        Some((detail.entry.id, detail.entry.title, q_slug.to_owned()))
    } else {
        None
    };

    let queue_creators: Vec<String> = ranked_queues
        .values()
        .flat_map(|v| v.rows.iter().flat_map(|r| r.entry.creators.iter().cloned()))
        .collect();
    let queue_entries: Vec<&crate::db::CatalogEntry> =
        ranked_queues.values().flat_map(|v| v.rows.iter().map(|r| &r.entry)).collect();
    let (artist_links, reader_item_ids) = tokio::join!(
        load_artist_id_by_lower_name(&service, &queue_creators),
        load_existing_reader_item_ids(&service, queue_entries),
    );
    let total_queued: usize = ranked_queues.values().map(|v| v.rows.len()).sum();

    let mut body = String::with_capacity(10_240);
    body.push_str(
        "<div class=\"queue-grid\">\n<section class=\"queue-main\" aria-label=\"Queues\">\n",
    );
    let _ = writeln!(
        body,
        "<div class=\"list-detail-head\">\n\
           <div class=\"list-detail-title-group\">\n\
             <h2>Queue</h2>\n\
             <span class=\"list-count-badge\">{total_queued}</span>\n\
           </div>\n\
         </div>",
    );

    if let Some((undo_id, undo_title, undo_queue)) = &undo_banner {
        let toggle_action = format!("/web/entries/{}/queue-toggle", urlencode(undo_id));
        body.push_str("<div class=\"undo-banner\" role=\"status\"><span>Removed <strong>");
        let _ = write!(body, "{}", esc(undo_title));
        body.push_str("</strong> from Queue.</span>");
        render_inline_csrf_form(
            &mut body,
            &toggle_action,
            &session.csrf_token,
            &[("in_queue", "true"), ("queue_slug", undo_queue.as_str()), ("return_to", "/queue")],
            None,
            "<button type=\"submit\" class=\"btn btn-small\">Undo</button>",
        );
        body.push_str("</div>\n");
    }

    if ranked_queues.is_empty() {
        body.push_str(
            "<p class=\"empty\">Your queues are empty. Save a URL or add a movie, book, album, paper, or game using the Quick Save or Add to Queue panel.</p>\n",
        );
    } else {
        for (_, qview) in ranked_queues {
            render_want_queue_section(
                &mut body,
                &qview,
                &artist_links,
                &reader_item_ids,
                &session.csrf_token,
            );
        }
    }
    body.push_str("</section>\n");
    render_queue_rail(&mut body, &session.csrf_token);
    body.push_str("</div>\n");

    Ok(render_page(&PageShell {
        title: "Queue",
        active: NavSection::Queue,
        csrf_token: &session.csrf_token,
        flash: &q.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

fn render_queue_rail(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<div class=\"queue-rail\">\n\
           <section class=\"rail-block rail-card\">\n\
             <div class=\"section-head\"><h3>Quick Save URL</h3></div>\n\
             <form method=\"post\" action=\"/web/items/save\" class=\"quick-save-inline\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <input type=\"hidden\" name=\"return_to\" value=\"/queue\">\n\
               <input type=\"url\" name=\"url\" required placeholder=\"https://example.com/article\" aria-label=\"Save article or paper URL\">\n\
               <button type=\"submit\" class=\"btn btn-small btn-primary\">Save</button>\n\
             </form>\n\
           </section>\n\
           <section class=\"rail-block rail-card\">\n\
             <details class=\"editorial-panel\" open data-default-open>\n\
               <summary>+ Add to Queue or Log</summary>\n\
               <form method=\"post\" action=\"/web/log\" class=\"rail-form\">\n\
                 <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
                 <div class=\"field\"><label for=\"ql-title\">Title</label>\n\
                   <input id=\"ql-title\" name=\"title\" type=\"text\" required placeholder=\"e.g. Drive My Car\"></div>\n\
                 <div class=\"field\"><label for=\"ql-kind\">Kind</label>\n\
                   <select id=\"ql-kind\" name=\"kind\">{kind_opts}</select></div>\n\
                 <div class=\"field\"><label for=\"ql-creators\">Creator / Author / Director</label>\n\
                   <input id=\"ql-creators\" name=\"creators\" type=\"text\" placeholder=\"Ryusuke Hamaguchi\"></div>\n\
                 <div class=\"field\"><label for=\"ql-year\">Release Year</label>\n\
                   <input id=\"ql-year\" name=\"release_year\" type=\"number\" min=\"1000\" max=\"2100\" placeholder=\"2021\"></div>\n\
                 <div class=\"field\"><label for=\"ql-ident\">URL / Spotify / IMDb / ISBN</label>\n\
                   <input id=\"ql-ident\" name=\"identifier\" type=\"text\" placeholder=\"Optional link or ID\"></div>\n\
                 <div class=\"field\"><label for=\"ql-action\">Action</label>\n\
                   <select id=\"ql-action\" name=\"action\" class=\"js-log-status-select\">\n\
                     <option value=\"want\">Add to Queue</option>\n\
                     <option value=\"consumed\">Log Finished</option>\n\
                     <option value=\"started\">Log Started</option>\n\
                     <option value=\"abandoned\">Log Skipped</option>\n\
                   </select></div>\n\
                 <div class=\"field\"><label for=\"ql-notes\">Notes (optional)</label>\n\
                   <textarea id=\"ql-notes\" name=\"notes\" class=\"textarea-compact\" placeholder=\"Optional notes…\"></textarea></div>\n\
                 <div class=\"field field-check\">\n\
                   <input id=\"ql-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked class=\"js-log-rank-check\">\n\
                   <label for=\"ql-rank\">Rank immediately when applicable</label></div>\n\
                 <button type=\"submit\" class=\"btn btn-primary btn-small\">Save</button>\n\
               </form>\n\
             </details>\n\
           </section>\n\
         </div>\n",
        csrf = esc(csrf),
        kind_opts = render_kind_options(EntryKind::Movie),
    );
}

fn render_want_queue_section(
    out: &mut String,
    qview: &RankedCollectionView,
    artist_links: &HashMap<String, String>,
    reader_item_ids: &HashSet<String>,
    csrf: &str,
) {
    let q_slug = qview.collection.slug.as_deref().unwrap_or(&qview.collection.id);
    let _ = write!(
        out,
        "<section class=\"queue-section\">\n\
           <div class=\"section-head\">\n\
             <h3>{} <span class=\"list-count-badge\">{}</span></h3>",
        esc(&qview.collection.title),
        qview.rows.len(),
    );
    if qview.rows.len() >= 2 {
        let rank_href = rank_collection_href(q_slug, true);
        let _ = write!(
            out,
            "<a class=\"section-action\" href=\"{}\">Rank Queue &rarr;</a>",
            esc(&rank_href),
        );
    }
    out.push_str("</div>\n<ol class=\"queue-list\">\n");

    let q_enc = urlencode(q_slug);
    for (idx, row) in qview.rows.iter().enumerate() {
        let id_enc = urlencode(&row.entry.id);
        let is_reader = reader_item_ids.contains(&row.entry.id);
        let title_href = if is_reader {
            format!("/reader/{id_enc}?return_to=%2Fqueue")
        } else {
            format!("/entries/{id_enc}")
        };
        let host = row
            .entry
            .url
            .as_deref()
            .and_then(|u| url::Url::parse(u).ok())
            .and_then(|u| u.host_str().map(str::to_owned));
        let display_host = host.as_deref().map(|h| h.strip_prefix("www.").unwrap_or(h));

        let _ = write!(
            out,
            "<li class=\"queue-item-row\">\n\
               <div class=\"queue-item-left\">\n\
                 <span class=\"queue-rank-num\">{}</span>\n\
                 <div class=\"queue-item-main\">\n\
                   <div class=\"queue-item-title\"><a href=\"{}\">{}</a></div>\n\
                   <div class=\"queue-item-meta\">",
            idx + 1,
            esc(&title_href),
            esc(&row.entry.title),
        );
        if !row.entry.creators.is_empty() {
            out.push_str("<span class=\"byline-item\">");
            render_creator_links(out, &row.entry.creators, artist_links, false);
            out.push_str("</span>");
        }
        if let Some(h) = display_host.filter(|s| !s.is_empty()) {
            let _ = write!(out, "<span class=\"byline-item\">{}</span>", esc(h));
        }
        let _ = write!(
            out,
            "<span class=\"byline-item\">{}</span></div>\n\
             </div>\n\
             </div>\n\
             <div class=\"inbox-controls inbox-controls-subtle\">\n",
            esc(row.entry.kind.display_label()),
        );
        if is_reader {
            let _ = write!(
                out,
                "<a class=\"btn btn-small btn-ghost\" href=\"/reader/{}?return_to=%2Fqueue\">Read</a>",
                esc(&id_enc),
            );
        }
        let complete_action = format!("/web/want/{q_enc}/complete/{id_enc}");
        let remove_action = format!("/web/want/{q_enc}/remove/{id_enc}");
        render_inline_csrf_form(
            out,
            &complete_action,
            csrf,
            &[],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-quiet\" title=\"Mark finished without ranking\">&#10003; Done</button>",
        );
        render_inline_csrf_form(
            out,
            &complete_action,
            csrf,
            &[("rank_after", "1")],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-primary\" title=\"Mark finished and rank\">Finish &amp; Rank</button>",
        );
        out.push_str("<span class=\"controls-sep\" aria-hidden=\"true\"></span>");
        let remove_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost btn-danger-quiet\" aria-label=\"Remove {} from queue\" title=\"Remove from queue\">&times;</button>",
            esc(&row.entry.title),
        );
        render_inline_csrf_form(out, &remove_action, csrf, &[], None, &remove_btn);
        out.push_str("\n</div>\n</li>\n");
    }
    out.push_str("</ol>\n</section>\n");
}

pub(in crate::web) async fn post_diary_edit(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<DiaryEditForm>,
) -> Result<Response, WebError> {
    let dest = safe_local_redirect(form.return_to.as_deref(), "/diary");
    let status = form.status.as_deref().map(parse_consumption_status);
    let ts = parse_diary_date_input(form.date.as_deref());
    let notes: Option<Option<&str>> = form.notes.as_deref().map(|raw| {
        let trimmed = raw.trim();
        (!trimmed.is_empty()).then_some(trimmed)
    });
    service.catalog().update_consumption_log(&id, status, notes, ts).await.web_err(&session)?;
    Ok(redirect_with_flash(&dest, "notice", "Updated diary entry"))
}

pub(in crate::web) async fn post_diary_delete(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<DiaryDeleteForm>,
) -> Result<Response, WebError> {
    let dest = safe_local_redirect(form.return_to.as_deref(), "/diary");
    let _ = service.catalog().delete_consumption_log(&id).await.web_err(&session)?;
    Ok(redirect_with_flash(&dest, "notice", "Deleted diary entry"))
}

pub(in crate::web) async fn post_quick_log(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<QuickLogForm>,
) -> Result<Response, WebError> {
    let err_dest = safe_local_redirect(form.return_to.as_deref(), "/queue");
    let kind = match EntryKind::parse_flexible(&form.kind) {
        Ok(k) => k,
        Err(e) => return Ok(redirect_with_flash(&err_dest, "err", &e.to_string())),
    };
    let creators = parse_csv_list(form.creators.as_deref());
    let release_year = parse_optional_year(form.release_year.as_deref());
    let (url, identifiers) = CatalogService::parse_smart_identifier(form.identifier.as_deref());

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
        let (queue, entry) = service
            .catalog()
            .want_resolved_or_upsert(&input, form.notes.as_deref(), None)
            .await
            .web_err(&session)?;
        if parse_form_bool(form.rank_after.as_deref()) && queue.member_count >= 2 {
            let q_slug = queue.slug.as_deref().unwrap_or(&queue.id);
            let base = q_slug.strip_prefix("want:").unwrap_or(q_slug);
            Ok(redirect_with_flash(
                &format!("/rank/{}?want=true&focus={}", urlencode(base), urlencode(&entry.id)),
                "notice",
                "Added to Queue",
            ))
        } else {
            let dest = safe_local_redirect(form.return_to.as_deref(), "/queue");
            Ok(redirect_with_flash(
                &dest,
                "notice",
                &format!("Added '{}' to {}", entry.title, queue.title),
            ))
        }
    } else {
        let status = parse_consumption_status(&form.action);
        let logged = service
            .catalog()
            .log_entry_resolved_or_upsert(None, Some(&input), status, form.notes.as_deref(), None)
            .await
            .web_err(&session)?;
        if status == ConsumptionStatus::Consumed
            && parse_form_bool(form.rank_after.as_deref())
            && let Ok(Some(slug)) =
                service.catalog().rank_target_after_consumption(&logged.entry).await
        {
            Ok(redirect_with_flash(
                &format!("/rank/{}?focus={}", urlencode(&slug), urlencode(&logged.entry.id)),
                "notice",
                "Logged to Diary",
            ))
        } else {
            let dest = safe_local_redirect(form.return_to.as_deref(), "/diary");
            Ok(redirect_with_flash(
                &dest,
                "notice",
                &format!("Logged '{}' to Diary", logged.entry.title),
            ))
        }
    }
}

pub(in crate::web) async fn post_want_complete(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path((_queue, entry_id)): Path<(String, String)>,
    Form(form): Form<WantCompleteForm>,
) -> Result<Response, WebError> {
    let logged = service
        .catalog()
        .log_consumption(&entry_id, ConsumptionStatus::Consumed, None, None)
        .await
        .web_err(&session)?;
    if parse_form_bool(form.rank_after.as_deref())
        && let Ok(Some(slug)) = service.catalog().rank_target_after_consumption(&logged.entry).await
    {
        Ok(redirect_with_flash(
            &format!("/rank/{}?focus={}", urlencode(&slug), urlencode(&logged.entry.id)),
            "notice",
            "Completed from queue — rank globally",
        ))
    } else {
        Ok(redirect_with_flash(
            "/queue",
            "notice",
            &format!("Completed '{}' & logged to Diary", logged.entry.title),
        ))
    }
}

pub(in crate::web) async fn post_want_remove(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path((queue, entry_id)): Path<(String, String)>,
    Form(form): Form<WantRemoveForm>,
) -> Result<Response, WebError> {
    let removed = service.catalog().unwant_entry(&queue, &entry_id).await.web_err(&session)?;
    let dest = safe_local_redirect(form.return_to.as_deref(), "/queue");
    let final_dest = if dest == "/queue" {
        format!("/queue?undo_entry={}&undo_queue={}", urlencode(&entry_id), urlencode(&queue))
    } else {
        dest
    };
    Ok(redirect_with_flash(
        &final_dest,
        "notice",
        &format!("Removed '{}' from queue", removed.title),
    ))
}
