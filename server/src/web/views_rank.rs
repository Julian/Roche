use std::{collections::HashSet, fmt::Write as _, sync::Arc};

use axum::{
    body::Bytes,
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
    db::{CatalogEntry, ClusterSelectFilter, CollectionRole, CollectionSummary},
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
};

const MAX_FOCUSED_ROUNDS: usize = 5;
const MAX_COVERED_CLUSTERS: usize = 12;

#[derive(Debug, Default, Deserialize)]
pub(super) struct RankSessionQuery {
    #[serde(default)]
    pub want: bool,
    pub focus: Option<String>,
    pub round: Option<usize>,
    pub random: Option<usize>,
    pub covered: Option<String>,
    pub notice: Option<String>,
    pub err: Option<String>,
}

pub(super) async fn rank_picker_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Query(flash): Query<FlashQuery>,
) -> Response {
    let collections = match service.catalog().list_collections(true, false).await {
        Ok(c) => c,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };

    let quality_lists: Vec<&CollectionSummary> = collections
        .iter()
        .filter(|c| c.role != CollectionRole::Want && c.member_count >= 2)
        .collect();
    let want_queues: Vec<&CollectionSummary> = collections
        .iter()
        .filter(|c| c.role == CollectionRole::Want && c.member_count >= 2)
        .collect();

    let mut body = String::with_capacity(4_096);
    body.push_str(
        "<h2>Compare &amp; Rank</h2>\n\
         <p class=\"lede\">Select a collection to refine via active-learning k-way comparisons (keyboard <code>j/k</code>, <code>J/K</code>, <code>1&ndash;5</code>, <code>=</code>, <code>t</code>, <code>s</code>, <code>Enter</code> or touch/mouse).</p>\n",
    );

    if quality_lists.is_empty() && want_queues.is_empty() {
        body.push_str(
            "<p class=\"empty\">At least two works are needed in a list or Want queue before comparing. \
             <a href=\"/queue\">Log works or add to a Want queue &rarr;</a></p>\n",
        );
    } else {
        if !quality_lists.is_empty() {
            body.push_str("<h3>Global Quality Lists</h3>\n<table class=\"lists\">\n<tbody>\n");
            for col in quality_lists {
                let slug = col.slug.as_deref().unwrap_or(&col.id);
                let _ = writeln!(
                    body,
                    "<tr>\
                       <td class=\"name\"><a href=\"/rank/{s}\">{title}</a></td>\
                       <td class=\"stat\">{m} works</td>\
                       <td class=\"stat\">{c} comparisons</td>\
                       <td class=\"stat\"><a class=\"btn btn-primary btn-small\" href=\"/rank/{s}\">Start Session</a></td>\
                     </tr>",
                    s = esc(&urlencode(slug)),
                    title = esc(&col.title),
                    m = col.member_count,
                    c = col.comparison_count,
                );
            }
            body.push_str("</tbody>\n</table>\n");
        }

        if !want_queues.is_empty() {
            body.push_str(
                "<h3>Want Queues (Local Priority)</h3>\n<table class=\"lists\">\n<tbody>\n",
            );
            for col in want_queues {
                let raw_slug = col.slug.as_deref().unwrap_or(&col.id);
                let base_slug = raw_slug.strip_prefix("want:").unwrap_or(raw_slug);
                let _ = writeln!(
                    body,
                    "<tr>\
                       <td class=\"name\"><a href=\"/rank/{s}?want=true\">{title}</a></td>\
                       <td class=\"stat\">{m} queued</td>\
                       <td class=\"stat\">{c} priority comparisons</td>\
                       <td class=\"stat\"><a class=\"btn btn-small\" href=\"/rank/{s}?want=true\">Prioritize</a></td>\
                     </tr>",
                    s = esc(&urlencode(base_slug)),
                    title = esc(&col.title),
                    m = col.member_count,
                    c = col.comparison_count,
                );
            }
            body.push_str("</tbody>\n</table>\n");
        }
    }

    render_page(&PageShell {
        title: "Rank",
        active: NavSection::Rank,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

pub(super) async fn rank_session_get<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Query(q): Query<RankSessionQuery>,
) -> Response {
    let round = q.round.unwrap_or(0);
    let focus_clean = q.focus.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let covered_sets = parse_covered_signatures(q.covered.as_deref());

    let ranked_view = match service.catalog().get_ranked_collection(&slug, q.want).await {
        Ok(v) => v,
        Err(err) => {
            return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
        }
    };

    let focus_is_member =
        focus_clean.is_some_and(|fid| ranked_view.rows.iter().any(|r| r.entry.id == fid));
    let focus_missing_from_list = focus_clean.is_some() && !focus_is_member;

    let focus_ids: Vec<String> = if q.random == Some(1) || !focus_is_member {
        Vec::new()
    } else {
        focus_clean.into_iter().map(ToOwned::to_owned).collect()
    };

    let cluster = if focus_missing_from_list || (focus_is_member && round >= MAX_FOCUSED_ROUNDS) {
        Vec::new()
    } else {
        let iter_idx = if q.random == Some(1) { 5 } else { round };
        let filter = ClusterSelectFilter {
            max_k: 5,
            iteration: iter_idx,
            focus_ids,
            ..ClusterSelectFilter::default()
        };
        match service
            .catalog()
            .select_next_uncovered_cluster(&slug, q.want, &filter, |cand| {
                is_cluster_covered(cand, &covered_sets)
            })
            .await
        {
            Ok((_, c, _)) => c,
            Err(err) => {
                return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
            }
        }
    };

    let effective_want = q.want || ranked_view.is_priority_scope;
    let mut body = String::with_capacity(8_192);
    if cluster.len() < 2 {
        render_settled_rank_view(
            &mut body,
            &slug,
            effective_want,
            focus_clean,
            round,
            &ranked_view,
        );
    } else {
        render_active_cluster_view(
            &mut body,
            &slug,
            &q,
            effective_want,
            &ranked_view.collection,
            &cluster,
            &session.csrf_token,
        );
    }

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: &format!("Rank: {}", ranked_view.collection.title),
        active: NavSection::Rank,
        csrf_token: &session.csrf_token,
        flash: &flash,
        body_html: &body,
    })
    .into_response()
}

fn render_settled_rank_view(
    out: &mut String,
    slug: &str,
    want: bool,
    focus_id: Option<&str>,
    round: usize,
    ranked_view: &crate::db::RankedCollectionView,
) {
    let _ = writeln!(out, "<h2>{}</h2>", esc(&ranked_view.collection.title));
    out.push_str("<div class=\"settled-card\">\n");

    if let Some(fid) = focus_id
        && let Some(row) = ranked_view.rows.iter().find(|r| r.entry.id == fid)
    {
        let tier_part = row.tier.map_or_else(String::new, |t| {
            format!(" &middot; <strong class=\"tier-{t}\">Tier {t}</strong>")
        });
        let _ = writeln!(
            out,
            "<div class=\"album-kicker\">Placement Settled</div>\n\
             <h3 style=\"border:none;margin:0.2rem 0 0.5rem;font-size:1.2rem;text-transform:none;letter-spacing:0;color:var(--ink)\">{}</h3>\n\
             <p class=\"lede\" style=\"margin-bottom:0.85rem\">Settled at <strong>#{}</strong> of {}{} after {round} comparison round(s).</p>",
            esc(&row.entry.title),
            row.rank,
            ranked_view.rows.len(),
            tier_part,
        );
    } else if let Some(fid) = focus_id {
        let _ = writeln!(
            out,
            "<p class=\"lede\" style=\"margin-bottom:0.85rem\">This work is not in <strong>{}</strong> yet. Mark it Finished / Consumed on its <a href=\"/entries/{}\">entry page</a> before ranking it.</p>",
            esc(&ranked_view.collection.title),
            esc(&urlencode(fid)),
        );
    } else if ranked_view.rows.len() < 2 {
        out.push_str(
            "<p class=\"lede\" style=\"margin-bottom:0.85rem\">Need at least 2 works in this collection before running comparisons.</p>\n",
        );
    } else {
        let _ = writeln!(
            out,
            "<p class=\"lede\" style=\"margin-bottom:0.85rem\">Rankings are well-calibrated after {round} round(s).</p>"
        );
    }

    out.push_str("<div style=\"display:flex;gap:0.5rem;flex-wrap:wrap\">\n");
    if want {
        out.push_str("<a class=\"btn btn-primary\" href=\"/queue\">Back to Queue</a>\n");
    } else {
        let list_slug = ranked_view.collection.slug.as_deref().unwrap_or(slug);
        let _ = writeln!(
            out,
            "<a class=\"btn btn-primary\" href=\"/lists/{}\">View Ranked List</a>",
            esc(&urlencode(list_slug))
        );
    }
    if ranked_view.rows.len() >= 2 {
        let want_q = if want { "&amp;want=true" } else { "" };
        let _ = writeln!(
            out,
            "<a class=\"btn\" href=\"/rank/{}?random=1{want_q}\">Keep Comparing</a>",
            esc(&urlencode(slug))
        );
    }
    out.push_str("<a class=\"btn btn-ghost\" href=\"/\">Home</a>\n</div>\n</div>\n");
}

fn render_active_cluster_view(
    out: &mut String,
    slug: &str,
    q: &RankSessionQuery,
    is_want: bool,
    collection: &CollectionSummary,
    cluster: &[CatalogEntry],
    csrf: &str,
) {
    let round = q.round.unwrap_or(0);
    let mode_label = if is_want { "Local Queue Priority" } else { "Global Quality" };
    let _ = writeln!(
        out,
        "<div style=\"display:flex;justify-content:space-between;align-items:baseline;flex-wrap:wrap;gap:1rem\">\
           <h2>{}</h2>\
           <span class=\"badge\">{} &middot; Round {}</span>\
         </div>\n\
         <p class=\"lede\">Order from best (#1) to worst (#{}). Mark genuinely equal works with <strong>= Tie</strong>.</p>",
        esc(&collection.title),
        mode_label,
        round + 1,
        cluster.len(),
    );

    let default_groups: Vec<Vec<&str>> = cluster.iter().map(|e| vec![e.id.as_str()]).collect();
    let default_json = serde_json::to_string(&default_groups).unwrap_or_else(|_| "[]".to_owned());
    let cluster_sig = cluster_signature(cluster);
    let next_covered = append_covered_signature(q.covered.as_deref(), &cluster_sig);

    let _ = write!(
        out,
        "<form method=\"post\" action=\"/rank/{slug_enc}\" class=\"js-rank-form\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{round}\">\n\
           <input type=\"hidden\" name=\"covered\" value=\"{covered}\">\n\
           <input type=\"hidden\" name=\"ordering_json\" value=\"{ord_json}\">\n\
           <ul class=\"rank-board js-rank-list\">\n",
        slug_enc = esc(&urlencode(slug)),
        csrf = esc(csrf),
        want = if is_want { "true" } else { "false" },
        focus = esc(q.focus.as_deref().unwrap_or("")),
        covered = esc(&next_covered),
        ord_json = esc(&default_json),
    );

    for (idx, item) in cluster.iter().enumerate() {
        render_cluster_card(out, idx, item, cluster.len());
    }

    out.push_str(
        "</ul>\n\
         <div style=\"display:flex;gap:0.5rem;flex-wrap:wrap;align-items:center\">\n\
           <button type=\"submit\" class=\"btn btn-primary\">Submit Comparison</button>\n\
           <button type=\"button\" class=\"btn js-rank-tie-all\">= Tie All</button>\n\
         </div>\n\
         </form>\n",
    );

    // Skip form (GET to next round with covered signature recorded so cluster doesn't immediately repeat)
    let _ = write!(
        out,
        "<form method=\"get\" action=\"/rank/{slug_enc}\" class=\"js-rank-skip-form\" style=\"margin-top:0.55rem\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{next_round}\">\n\
           <input type=\"hidden\" name=\"random\" value=\"1\">\n\
           <input type=\"hidden\" name=\"covered\" value=\"{covered}\">\n\
           <button type=\"submit\" class=\"btn btn-small btn-ghost\">Skip Cluster (s)</button>\n\
         </form>\n\
         <p class=\"rank-shortcuts\">Shortcuts: j/k select &middot; J/K move &middot; 1&ndash;{k} jump &middot; = tie &middot; t tie all &middot; s skip &middot; o open link &middot; Enter submit</p>\n",
        slug_enc = esc(&urlencode(slug)),
        want = if is_want { "true" } else { "false" },
        focus = esc(q.focus.as_deref().unwrap_or("")),
        next_round = round + 1,
        covered = esc(&next_covered),
        k = cluster.len(),
    );
}

fn render_cluster_card(out: &mut String, idx: usize, item: &CatalogEntry, cluster_len: usize) {
    let rank_num = idx + 1;
    let sel_cls = if idx == 0 { " is-selected" } else { "" };
    let open_url = item
        .primary_open_url()
        .as_deref()
        .and_then(safe_external_href)
        .filter(|u| !u.starts_with("https://roche.local/"))
        .unwrap_or("")
        .to_owned();

    let _ = write!(
        out,
        "<li class=\"rank-card js-rank-card{sel_cls}\" data-entry-id=\"{id}\" data-tied=\"0\" data-open-url=\"{open_url}\">\n\
           <div class=\"rank-left\">\n\
             <span class=\"rank-pos js-rank-pos\">#{rank_num}</span>\n\
             <div>\n\
               <strong><a href=\"/entries/{id_enc}\" target=\"_blank\">{title}</a></strong>",
        id = esc(&item.id),
        open_url = esc(&open_url),
        id_enc = esc(&urlencode(&item.id)),
        title = esc(&item.title),
    );
    if !item.creators.is_empty() {
        let _ = write!(
            out,
            " <span style=\"color:var(--ink-soft)\">&mdash; {}</span>",
            esc(&item.creators.join(", "))
        );
    }
    if let Some(yr) = item.release_year {
        let _ = write!(out, " <span style=\"color:var(--ink-faint)\">({yr})</span>");
    }
    out.push_str("</div>\n</div>\n<div class=\"rank-controls\">\n");

    let _ =
        write!(out, "<noscript><select name=\"pos_{}\" class=\"js-noscript-pos\">", esc(&item.id));
    for p in 1..=cluster_len {
        let sel = if p == rank_num { " selected" } else { "" };
        let _ = write!(out, "<option value=\"{p}\"{sel}>#{p}</option>");
    }
    out.push_str("</select></noscript>\n");

    let tie_disabled = if idx == 0 { " disabled" } else { "" };
    let _ = writeln!(
        out,
        "<button type=\"button\" class=\"btn btn-small js-card-up\" title=\"Move Up (K)\">&uarr;</button>\
         <button type=\"button\" class=\"btn btn-small js-card-down\" title=\"Move Down (J)\">&darr;</button>\
         <button type=\"button\" class=\"btn btn-small btn-ghost js-card-tie\"{tie_disabled} title=\"Tie with above (=)\">= Tie</button>\
         </div>\n</li>"
    );
}

pub(super) async fn rank_session_post<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    body: Bytes,
) -> Response {
    let pairs: Vec<(String, String)> =
        url::form_urlencoded::parse(&body).map(|(k, v)| (k.into_owned(), v.into_owned())).collect();

    let find_val = |key: &str| -> Option<&str> {
        pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    };

    let want = matches!(find_val("want"), Some("true" | "1"));
    let focus = find_val("focus").map(str::trim).filter(|s| !s.is_empty());
    let round = find_val("round").and_then(|r| r.parse::<usize>().ok()).unwrap_or(0);
    let covered = find_val("covered").unwrap_or("");

    let ordering = parse_submitted_ordering(&pairs);
    let Some(groups) = ordering else {
        return redirect_see_other(&format!(
            "/rank/{}?err=Invalid+comparison+ordering",
            urlencode(&slug)
        ));
    };

    let (effective_want, scope_col_id) = if want {
        match service.catalog().get_or_create_want_collection(&slug).await {
            Ok(col) => (true, Some(col.id)),
            Err(err) => {
                return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
            }
        }
    } else {
        match service.catalog().get_ranked_collection(&slug, false).await {
            Ok(view) if view.is_priority_scope => (true, Some(view.collection.id)),
            Ok(_) => (false, None),
            Err(err) => {
                return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
            }
        }
    };

    if let Err(err) =
        service.catalog().append_comparison(scope_col_id.as_deref(), &groups, None).await
    {
        return web_api_error_response(&ApiError::from(err), Some(&session.csrf_token));
    }

    let mut query_parts = vec![format!("round={}", round + 1)];
    if effective_want {
        query_parts.push("want=true".to_owned());
    }
    if let Some(fid) = focus {
        query_parts.push(format!("focus={}", urlencode(fid)));
    }
    if !covered.is_empty() {
        query_parts.push(format!("covered={}", urlencode(covered)));
    }
    redirect_see_other(&format!("/rank/{}?{}", urlencode(&slug), query_parts.join("&")))
}

fn parse_submitted_ordering(pairs: &[(String, String)]) -> Option<Vec<Vec<String>>> {
    // Controls inside `<noscript>` are only submitted by browsers with scripting disabled,
    // so `pos_<id>` fields must take precedence over the default `ordering_json` hidden field.
    let mut pos_entries: Vec<(usize, String)> = Vec::new();
    for (k, v) in pairs {
        if let Some(entry_id) = k.strip_prefix("pos_")
            && let Ok(pos) = v.parse::<usize>()
            && !entry_id.trim().is_empty()
        {
            pos_entries.push((pos, entry_id.to_owned()));
        }
    }

    if pos_entries.len() >= 2 {
        pos_entries.sort_by_key(|(pos, _)| *pos);
        let mut groups: Vec<(usize, Vec<String>)> = Vec::new();
        for (pos, eid) in pos_entries {
            if let Some((last_pos, bucket)) = groups.last_mut()
                && *last_pos == pos
            {
                bucket.push(eid);
            } else {
                groups.push((pos, vec![eid]));
            }
        }
        return Some(groups.into_iter().map(|(_, g)| g).collect());
    }

    if let Some((_, ord_raw)) = pairs.iter().find(|(k, _)| k == "ordering_json")
        && let Ok(parsed) = serde_json::from_str::<Vec<Vec<String>>>(ord_raw)
        && parsed.iter().map(Vec::len).sum::<usize>() >= 2
    {
        return Some(parsed);
    }

    None
}

fn short_cluster_token(id: &str) -> String {
    id.chars().take(8).collect()
}

fn cluster_signature(cluster: &[CatalogEntry]) -> String {
    let mut tokens: Vec<String> = cluster.iter().map(|c| short_cluster_token(&c.id)).collect();
    tokens.sort_unstable();
    tokens.join(".")
}

fn parse_covered_signatures(raw: Option<&str>) -> Vec<HashSet<String>> {
    raw.map(str::trim).filter(|s| !s.is_empty()).map_or_default(|s| {
        s.split('|')
            .filter(|part| !part.is_empty())
            .map(|part| part.split('.').map(ToOwned::to_owned).collect::<HashSet<_>>())
            .collect()
    })
}

fn append_covered_signature(existing: Option<&str>, sig: &str) -> String {
    let mut list: Vec<&str> = existing
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map_or_default(|s| s.split('|').filter(|p| !p.is_empty()).collect());
    if !list.contains(&sig) {
        list.push(sig);
    }
    if list.len() > MAX_COVERED_CLUSTERS {
        list.drain(0..(list.len() - MAX_COVERED_CLUSTERS));
    }
    list.join("|")
}

fn is_cluster_covered(cluster: &[CatalogEntry], covered: &[HashSet<String>]) -> bool {
    if cluster.is_empty() || covered.is_empty() {
        return false;
    }
    let prefixes: Vec<String> = cluster.iter().map(|c| short_cluster_token(&c.id)).collect();
    covered.iter().any(|prev| prefixes.iter().all(|p| prev.contains(p)))
}
