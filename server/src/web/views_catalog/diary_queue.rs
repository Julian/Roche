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
            CreatorYearStyle, FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _, esc,
            parse_csv_list, parse_form_bool, rank_collection_href, redirect_with_flash,
            render_creator_year_meta, render_diary_row, render_inline_csrf_form, render_page,
            urlencode,
        },
        links::load_artist_id_by_lower_name,
    },
};

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
    let entries =
        service.catalog().list_diary(q.year, status_filter, 500).await.web_err(&session)?;

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
        "<div class=\"page-head\">\
           <h2>Diary</h2>\
           <a class=\"btn btn-small\" href=\"/queue\">+ Log Media</a>\
         </div>\n\
         <p class=\"lede\">{} logged entries across books, albums, films, articles, podcasts, and videos.</p>",
        entries.len()
    );

    let active_status = status_filter.map_or("", ConsumptionStatus::as_str);
    body.push_str("<div class=\"pill-bar\">\n");
    for (slug, label) in
        [("", "All"), ("consumed", "Consumed"), ("started", "Started"), ("abandoned", "Abandoned")]
    {
        let is_cur = active_status == slug;
        let attrs = if is_cur {
            "class=\"pill is-active\" aria-current=\"true\""
        } else {
            "class=\"pill\""
        };
        let mut params = Vec::new();
        if let Some(yr) = q.year {
            params.push(format!("year={yr}"));
        }
        if !slug.is_empty() {
            params.push(format!("status={slug}"));
        }
        let href = if params.is_empty() {
            "/diary".to_owned()
        } else {
            format!("/diary?{}", params.join("&"))
        };
        let _ = writeln!(body, "<a {attrs} href=\"{}\">{label}</a>", esc(&href));
    }
    body.push_str("</div>\n");

    render_diary_months(&mut body, &months, &artist_links);

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
            render_diary_row(out, row, artist_links, true);
        }
        out.push_str("</section>\n");
    }
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
            render_want_queue_section(
                &mut body,
                &qview,
                &artist_links,
                &reader_item_ids,
                &session.csrf_token,
            );
        }
    }

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

fn render_quick_log_panel(out: &mut String, csrf: &str) {
    let _ = write!(
        out,
        "<details class=\"editorial-panel\" open data-default-open>\n\
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
             <div class=\"field mb-sm\">\
               <label for=\"ql-notes\">Notes (optional)</label>\
               <textarea id=\"ql-notes\" name=\"notes\" class=\"textarea-compact\" placeholder=\"Optional notes...\"></textarea></div>\n\
             <div class=\"field field-check mb-md\">\
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
    reader_item_ids: &HashSet<String>,
    csrf: &str,
) {
    let q_slug = qview.collection.slug.as_deref().unwrap_or(&qview.collection.id);
    let _ = write!(
        out,
        "<div class=\"section-head\"><h3>{} ({})</h3>",
        esc(&qview.collection.title),
        qview.rows.len()
    );
    if qview.rows.len() >= 2 {
        let rank_href = rank_collection_href(q_slug, true);
        let _ = write!(
            out,
            "<a class=\"section-action\" href=\"{}\">Prioritize Queue &rarr;</a>",
            esc(&rank_href)
        );
    }
    out.push_str("</div>\n<ol class=\"queue\">\n");

    for row in &qview.rows {
        let mut by = String::new();
        render_creator_year_meta(
            &mut by,
            &row.entry.creators,
            None,
            artist_links,
            CreatorYearStyle::QueueDash,
        );
        let read_btn = if reader_item_ids.contains(&row.entry.id) {
            format!(
                "<a class=\"btn btn-small\" href=\"/reader/{}\">Read</a>",
                esc(&urlencode(&row.entry.id))
            )
        } else {
            String::new()
        };
        let id_enc = urlencode(&row.entry.id);
        let q_enc = urlencode(q_slug);
        let complete_action = format!("/web/want/{q_enc}/complete/{id_enc}");
        let remove_action = format!("/web/want/{q_enc}/remove/{id_enc}");
        let _ = write!(
            out,
            "<li><div class=\"queue-row\">\
               <span><a href=\"/entries/{id}\">{title}</a>{by}</span>\
               <span class=\"inbox-controls\">{read_btn}",
            id = esc(&id_enc),
            title = esc(&row.entry.title),
        );
        render_inline_csrf_form(
            out,
            &complete_action,
            csrf,
            &[("rank_after", "1")],
            None,
            "<button type=\"submit\" class=\"btn btn-small btn-primary\">&#10003; Done &amp; Rank</button>",
        );
        render_inline_csrf_form(
            out,
            &complete_action,
            csrf,
            &[],
            None,
            "<button type=\"submit\" class=\"btn btn-small\">&#10003; Done</button>",
        );
        let remove_btn = format!(
            "<button type=\"submit\" class=\"btn btn-small btn-ghost\" aria-label=\"Remove {} from queue\" title=\"Remove from queue\">&times;</button>",
            esc(&row.entry.title)
        );
        render_inline_csrf_form(out, &remove_action, csrf, &[], None, &remove_btn);
        out.push_str("</span></div></li>\n");
    }
    out.push_str("</ol>\n");
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
                "Added to Want queue",
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
