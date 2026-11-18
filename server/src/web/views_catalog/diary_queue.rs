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

use super::{
    DiaryFilterQuery, QuickLogForm, WantCompleteForm, parse_consumption_status, parse_csv_list,
    parse_optional_year, render_kind_options,
};
use crate::{
    db::{
        CollectionRole, CollectionSummary, ConsumptionLogView, ConsumptionStatus, EntryKind,
        RankedCollectionView, UpsertEntryInput,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::{CatalogService, RocheService},
    web::{
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, redirect_see_other, render_page, urlencode,
            web_api_error_response,
        },
        links::{load_artist_id_by_lower_name, render_creator_links},
    },
};

pub(in crate::web) async fn diary_get<F: FeedFetcher>(
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

    let diary_creators: Vec<String> =
        entries.iter().flat_map(|e| e.entry.creators.iter().cloned()).collect();
    let artist_links = load_artist_id_by_lower_name(&service, &diary_creators).await;

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

    render_diary_months(&mut body, &months, &artist_links);

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: "Diary",
        active: NavSection::Diary,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
}

fn render_diary_months(
    out: &mut String,
    months: &[(String, Vec<&ConsumptionLogView>)],
    artist_links: &HashMap<String, String>,
) {
    if months.is_empty() {
        out.push_str("<p class=\"empty\">No diary entries match this filter.</p>\n");
        return;
    }
    for (month_heading, rows) in months {
        let _ = writeln!(out, "<section class=\"diary-month\">\n<h3>{}</h3>", esc(month_heading));
        for row in rows {
            let _ = write!(
                out,
                "<div class=\"diary-row\">\
                   <span class=\"date\">{}</span>\
                   <span><a href=\"/entries/{}\">{}</a>",
                row.ts.format("%b %d"),
                esc(&urlencode(&row.entry.id)),
                esc(&row.entry.title),
            );
            if !row.entry.creators.is_empty() {
                out.push_str(" <span style=\"color:var(--ink-soft)\">&mdash; ");
                render_creator_links(out, &row.entry.creators, artist_links, false);
                out.push_str("</span>");
            }
            if row.status != ConsumptionStatus::Consumed {
                let _ = write!(out, " <span class=\"status-tag\">({})</span>", row.status.as_str());
            }
            let _ = writeln!(
                out,
                "</span><span class=\"kind\">{}</span>",
                esc(row.entry.kind.display_label())
            );
            if let Some(notes) = row.notes.as_deref().filter(|s| !s.is_empty()) {
                let _ = writeln!(out, "<div class=\"notes\">{}</div>", esc(notes));
            }
            out.push_str("</div>\n");
        }
        out.push_str("</section>\n");
    }
}

pub(in crate::web) async fn queue_get<F: FeedFetcher>(
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

    let queue_creators: Vec<String> = ranked_queues
        .values()
        .flat_map(|v| v.rows.iter().flat_map(|r| r.entry.creators.iter().cloned()))
        .collect();
    let artist_links = load_artist_id_by_lower_name(&service, &queue_creators).await;

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
            render_want_queue_section(&mut body, &qview, &artist_links, &session.csrf_token);
        }
    }

    render_page(&PageShell {
        title: "Queue",
        active: NavSection::Queue,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
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
               <label for=\"ql-notes\">Notes (optional)</label>\
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

fn render_want_queue_section(
    out: &mut String,
    qview: &RankedCollectionView,
    artist_links: &HashMap<String, String>,
    csrf: &str,
) {
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
        let mut by = String::new();
        if !row.entry.creators.is_empty() {
            by.push_str(" <span class=\"by\">&mdash; ");
            render_creator_links(&mut by, &row.entry.creators, artist_links, false);
            by.push_str("</span>");
        }
        let read_btn = if row.entry.kind.is_reader_openable() && row.entry.url.is_some() {
            format!(
                "<a class=\"btn btn-small\" href=\"/reader/{}\">Read</a>",
                esc(&urlencode(&row.entry.id))
            )
        } else {
            String::new()
        };
        let _ = writeln!(
            out,
            "<li><div class=\"queue-row\">\
               <span><a href=\"/entries/{id}\">{title}</a>{by}</span>\
               <span class=\"inbox-controls\">\
                 {read_btn}\
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

pub(in crate::web) async fn post_quick_log<F: FeedFetcher>(
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
    let (url, identifiers) = CatalogService::parse_smart_identifier(form.identifier.as_deref());
    let has_explicit_meta =
        !creators.is_empty() || release_year.is_some() || url.is_some() || !identifiers.is_empty();

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
        match service
            .catalog()
            .want_resolved_or_upsert(&input, has_explicit_meta, form.notes.as_deref(), None)
            .await
        {
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
                    && let Ok(Some(slug)) =
                        service.catalog().rank_target_after_consumption(&logged.entry).await
                {
                    redirect_see_other(&format!(
                        "/rank/{}?focus={}&notice={}",
                        urlencode(&slug),
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

pub(in crate::web) async fn post_want_complete<F: FeedFetcher>(
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
                && let Ok(Some(slug)) =
                    service.catalog().rank_target_after_consumption(&logged.entry).await
            {
                redirect_see_other(&format!(
                    "/rank/{}?focus={}&notice={}",
                    urlencode(&slug),
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

pub(in crate::web) async fn post_want_remove<F: FeedFetcher>(
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
