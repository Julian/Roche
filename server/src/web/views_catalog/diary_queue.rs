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
    DiaryFilterQuery, QuickLogForm, WantCompleteForm, load_existing_reader_item_ids,
    parse_consumption_status, parse_optional_year, render_kind_options,
};
use crate::{
    db::{CollectionSummary, ConsumptionLogView, RankedCollectionView, UpsertEntryInput},
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    service::{CatalogService, RocheService},
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _, esc, parse_csv_list,
            parse_form_bool, rank_collection_href, redirect_with_flash, render_inline_csrf_form,
            render_page, urlencode,
        },
        links::{load_artist_id_by_lower_name, render_creator_links, sanitize_non_local_href},
    },
};

struct DiaryStats {
    total_all_years: usize,
    year_counts: BTreeMap<i32, usize>,
    cadence_year: Option<i32>,
    cadence_months: Vec<(String, usize)>,
}

impl DiaryStats {
    fn from_entries(all_entries: &[ConsumptionLogView], selected_year: Option<i32>) -> Self {
        let mut year_counts: BTreeMap<i32, usize> = BTreeMap::new();
        for item in all_entries {
            *year_counts.entry(item.ts.year()).or_insert(0) += 1;
        }
        let cadence_year = selected_year.or_else(|| year_counts.keys().next_back().copied());
        let mut by_month: BTreeMap<u32, (String, usize)> = BTreeMap::new();
        if let Some(target_yr) = cadence_year {
            for item in all_entries {
                if item.ts.year() == target_yr {
                    let m = item.ts.month();
                    let label = item.ts.format("%b").to_string();
                    by_month.entry(m).or_insert((label, 0)).1 += 1;
                }
            }
        }
        let cadence_months: Vec<(String, usize)> = by_month.into_values().rev().take(8).collect();
        Self { total_all_years: all_entries.len(), year_counts, cadence_year, cadence_months }
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
    let (filtered_res, all_for_stats_res) = tokio::join!(
        async { service.catalog().list_diary(q.year, status_filter, 500).await },
        async {
            if q.year.is_some() || status_filter.is_some() {
                service.catalog().list_diary(None, status_filter, 2_000).await.ok()
            } else {
                None
            }
        },
    );
    let entries = filtered_res.web_err(&session)?;
    let stats = DiaryStats::from_entries(all_for_stats_res.as_deref().unwrap_or(&entries), q.year);

    let diary_creators: Vec<String> =
        entries.iter().flat_map(|e| e.entry.creators.iter().cloned()).collect();
    let (artist_links, diary_tiers) = tokio::join!(
        load_artist_id_by_lower_name(&service, &diary_creators),
        load_diary_entry_tiers(&service, &entries),
    );

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

    let mut body = String::with_capacity(12_288);
    body.push_str(
        "<div class=\"diary-grid\">\n<section class=\"diary-main\" aria-label=\"Diary log\">\n",
    );
    let _ = writeln!(
        body,
        "<div class=\"list-detail-head\">\n\
           <div class=\"list-detail-title-group\">\n\
             <h2>Diary</h2>\n\
             <span class=\"list-count-badge\">{}</span>\n\
           </div>\n\
         </div>",
        entries.len(),
    );

    render_diary_toolbar(&mut body, q.year, status_filter);
    render_diary_months(&mut body, &months, &artist_links, &diary_tiers);
    body.push_str("</section>\n");

    render_diary_rail(&mut body, &stats, q.year, status_filter, &session.csrf_token);
    body.push_str("</div>\n");

    Ok(render_page(&PageShell {
        title: "Diary",
        active: NavSection::Diary,
        csrf_token: &session.csrf_token,
        flash: &q.flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response())
}

async fn load_diary_entry_tiers(
    service: &RocheService,
    entries: &[ConsumptionLogView],
) -> HashMap<String, char> {
    let mut slugs: Vec<&'static str> = entries
        .iter()
        .filter(|d| d.status == ConsumptionStatus::Consumed)
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

fn build_diary_href(year: Option<i32>, status: &str) -> String {
    let mut params = Vec::new();
    if let Some(yr) = year {
        params.push(format!("year={yr}"));
    }
    if !status.is_empty() {
        params.push(format!("status={status}"));
    }
    if params.is_empty() { "/diary".to_owned() } else { format!("/diary?{}", params.join("&")) }
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
        [("", "All"), ("consumed", "Consumed"), ("started", "Started"), ("abandoned", "Abandoned")]
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
            render_diary_page_row(out, row, artist_links, diary_tiers);
        }
        out.push_str("</section>\n");
    }
    out.push_str("</div>\n");
}

fn render_diary_page_row(
    out: &mut String,
    row: &ConsumptionLogView,
    artist_links: &HashMap<String, String>,
    diary_tiers: &HashMap<String, char>,
) {
    let entry_href = format!("/entries/{}", urlencode(&row.entry.id));
    let creators_str = row.entry.creators.join(" ");
    let note_str = row.notes.as_deref().map_or("", str::trim);
    let search_blob =
        format!("{} {creators_str} {} {note_str}", row.entry.title, row.entry.kind.display_label());

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
            "<a class=\"diary-thumb-link\" href=\"{}\"><img class=\"diary-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a>",
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
        let st_lbl = match row.status {
            ConsumptionStatus::Started => "Started",
            ConsumptionStatus::Abandoned => "Abandoned",
            ConsumptionStatus::Consumed => "",
        };
        let _ = write!(out, "<span class=\"badge\">{st_lbl}</span>");
    }
    out.push_str("</div>\n</div>\n");
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
    Query(flash): Query<FlashQuery>,
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

    if ranked_queues.is_empty() {
        body.push_str(
            "<p class=\"empty\">Your queues are empty. Save a URL or add a movie, book, album, paper, or game from the panel on the right.</p>\n",
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
        flash: &flash,
        is_refreshing: service.is_refreshing(),
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
                   <select id=\"ql-action\" name=\"action\">\n\
                     <option value=\"want\">Add to Queue</option>\n\
                     <option value=\"consumed\">Log Finished</option>\n\
                     <option value=\"started\">Log Started</option>\n\
                     <option value=\"abandoned\">Log Skipped</option>\n\
                   </select></div>\n\
                 <div class=\"field\"><label for=\"ql-notes\">Notes (optional)</label>\n\
                   <textarea id=\"ql-notes\" name=\"notes\" class=\"textarea-compact\" placeholder=\"Optional notes…\"></textarea></div>\n\
                 <div class=\"field field-check\">\n\
                   <input id=\"ql-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked>\n\
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
        let title_href =
            if is_reader { format!("/reader/{id_enc}") } else { format!("/entries/{id_enc}") };
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
                "<a class=\"btn btn-small btn-ghost\" href=\"/reader/{}\">Read</a>",
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
            "<button type=\"submit\" class=\"btn btn-small btn-primary\" title=\"Mark finished and rank\">Rank</button>",
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

pub(in crate::web) async fn post_quick_log(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Form(form): Form<QuickLogForm>,
) -> Result<Response, WebError> {
    let kind = match EntryKind::parse_flexible(&form.kind) {
        Ok(k) => k,
        Err(e) => return Ok(redirect_with_flash("/queue", "err", &e.to_string())),
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
            Ok(redirect_with_flash(
                "/queue",
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
            Ok(redirect_with_flash(
                "/diary",
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
) -> Result<Response, WebError> {
    let removed = service.catalog().unwant_entry(&queue, &entry_id).await.web_err(&session)?;
    Ok(redirect_with_flash("/queue", "notice", &format!("Removed '{}' from queue", removed.title)))
}
