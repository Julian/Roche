use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    body::Bytes,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    auth::WebSession,
    html::{
        FlashQuery, NavSection, PageShell, esc, rank_collection_href, redirect_see_other,
        render_page, urlencode, web_api_error_response,
    },
    links::{load_artist_id_by_lower_name, render_creator_links, web_primary_open_url},
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
            body.push_str(
                "<h3>Global Quality Lists</h3>\n\
                 <table class=\"lists\">\n\
                 <thead class=\"sr-only\"><tr>\
                   <th scope=\"col\">List</th>\
                   <th scope=\"col\">Works</th>\
                   <th scope=\"col\">Comparisons</th>\
                   <th scope=\"col\">Actions</th>\
                 </tr></thead>\n\
                 <tbody>\n",
            );
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
                "<h3>Want Queues (Local Priority)</h3>\n\
                 <table class=\"lists\">\n\
                 <thead class=\"sr-only\"><tr>\
                   <th scope=\"col\">Queue</th>\
                   <th scope=\"col\">Queued</th>\
                   <th scope=\"col\">Comparisons</th>\
                   <th scope=\"col\">Actions</th>\
                 </tr></thead>\n\
                 <tbody>\n",
            );
            for col in want_queues {
                let raw_slug = col.slug.as_deref().unwrap_or(&col.id);
                let href = rank_collection_href(raw_slug, true);
                let _ = writeln!(
                    body,
                    "<tr>\
                       <td class=\"name\"><a href=\"{href}\">{title}</a></td>\
                       <td class=\"stat\">{m} queued</td>\
                       <td class=\"stat\">{c} priority comparisons</td>\
                       <td class=\"stat\"><a class=\"btn btn-small\" href=\"{href}\">Prioritize</a></td>\
                     </tr>",
                    href = esc(&href),
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
        is_refreshing: service.is_refreshing(),
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
        let cluster_creators: Vec<String> =
            cluster.iter().flat_map(|e| e.creators.iter().cloned()).collect();
        let artist_links = load_artist_id_by_lower_name(&service, &cluster_creators).await;
        render_active_cluster_view(
            &mut body,
            &ActiveClusterCtx {
                slug: &slug,
                q: &q,
                is_want: effective_want,
                collection_title: &ranked_view.collection.title,
                cluster: &cluster,
                artist_links: &artist_links,
                csrf: &session.csrf_token,
            },
        );
    }

    let flash = FlashQuery { notice: q.notice, err: q.err };
    render_page(&PageShell {
        title: &format!("Rank: {}", ranked_view.collection.title),
        active: NavSection::Rank,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
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
             <h3 class=\"settled-title\">{}</h3>\n\
             <p class=\"lede mb-md\">Settled at <strong>#{}</strong> of {}{} after {round} comparison round(s).</p>",
            esc(&row.entry.title),
            row.rank,
            ranked_view.rows.len(),
            tier_part,
        );
    } else if let Some(fid) = focus_id {
        let _ = writeln!(
            out,
            "<p class=\"lede mb-md\">This work is not in <strong>{}</strong> yet. Mark it Finished / Consumed on its <a href=\"/entries/{}\">entry page</a> before ranking it.</p>",
            esc(&ranked_view.collection.title),
            esc(&urlencode(fid)),
        );
    } else if ranked_view.rows.len() < 2 {
        out.push_str(
            "<p class=\"lede mb-md\">Need at least 2 works in this collection before running comparisons.</p>\n",
        );
    } else {
        let _ = writeln!(
            out,
            "<p class=\"lede mb-md\">Rankings are well-calibrated after {round} round(s).</p>"
        );
    }

    out.push_str("<div class=\"flex-row\">\n");
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

struct ActiveClusterCtx<'a> {
    slug: &'a str,
    q: &'a RankSessionQuery,
    is_want: bool,
    collection_title: &'a str,
    cluster: &'a [CatalogEntry],
    artist_links: &'a HashMap<String, String>,
    csrf: &'a str,
}

fn render_active_cluster_view(out: &mut String, ctx: &ActiveClusterCtx<'_>) {
    let round = ctx.q.round.unwrap_or(0);
    let mode_label = if ctx.is_want { "Local Queue Priority" } else { "Global Quality" };
    let _ = writeln!(
        out,
        "<div class=\"page-head\">\
           <h2>{}</h2>\
           <span class=\"badge\">{} &middot; Round {}</span>\
         </div>\n\
         <p class=\"lede\">Order from best (#1) to worst (#{}). Mark genuinely equal works with <strong>= Tie</strong>.</p>",
        esc(ctx.collection_title),
        mode_label,
        round + 1,
        ctx.cluster.len(),
    );

    let default_groups: Vec<Vec<&str>> = ctx.cluster.iter().map(|e| vec![e.id.as_str()]).collect();
    let default_json = serde_json::to_string(&default_groups).unwrap_or_else(|_| "[]".to_owned());
    let cluster_sig = cluster_signature(ctx.cluster);
    let next_covered = append_covered_signature(ctx.q.covered.as_deref(), &cluster_sig);

    let _ = write!(
        out,
        "<form method=\"post\" action=\"/rank/{slug_enc}\" class=\"js-rank-form\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{round}\">\n\
           <input type=\"hidden\" name=\"covered\" value=\"{covered}\">\n\
           <input type=\"hidden\" name=\"ordering_json\" value=\"{ord_json}\">\n\
           <div id=\"rank-live-status\" class=\"sr-only\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\"></div>\n\
           <ul class=\"rank-board js-rank-list\" aria-label=\"Ranking comparison board\">\n",
        slug_enc = esc(&urlencode(ctx.slug)),
        csrf = esc(ctx.csrf),
        want = if ctx.is_want { "true" } else { "false" },
        focus = esc(ctx.q.focus.as_deref().unwrap_or("")),
        covered = esc(&next_covered),
        ord_json = esc(&default_json),
    );

    for (idx, item) in ctx.cluster.iter().enumerate() {
        render_cluster_card(out, idx, item, ctx.cluster.len(), ctx.artist_links);
    }

    out.push_str(
        "</ul>\n\
         <div class=\"flex-row flex-center\">\n\
           <button type=\"submit\" class=\"btn btn-primary\">Submit Comparison</button>\n\
           <button type=\"button\" class=\"btn js-rank-tie-all\" aria-pressed=\"false\"><span aria-hidden=\"true\">=</span> Tie All</button>\n\
         </div>\n\
         </form>\n",
    );

    // Skip form (GET to next round with covered signature recorded so cluster doesn't immediately repeat)
    let _ = write!(
        out,
        "<form method=\"get\" action=\"/rank/{slug_enc}\" class=\"js-rank-skip-form mt-sm\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{next_round}\">\n\
           <input type=\"hidden\" name=\"random\" value=\"1\">\n\
           <input type=\"hidden\" name=\"covered\" value=\"{covered}\">\n\
           <button type=\"submit\" class=\"btn btn-small btn-ghost\">Skip Cluster (s)</button>\n\
         </form>\n\
         <p class=\"rank-shortcuts\">Shortcuts (when board focused): j/k select &middot; J/K move &middot; 1&ndash;{k} jump &middot; = tie &middot; t tie all &middot; s skip &middot; o open link &middot; Enter submit</p>\n",
        slug_enc = esc(&urlencode(ctx.slug)),
        want = if ctx.is_want { "true" } else { "false" },
        focus = esc(ctx.q.focus.as_deref().unwrap_or("")),
        next_round = round + 1,
        covered = esc(&next_covered),
        k = ctx.cluster.len(),
    );
}

fn render_cluster_card(
    out: &mut String,
    idx: usize,
    item: &CatalogEntry,
    cluster_len: usize,
    artist_links: &HashMap<String, String>,
) {
    let rank_num = idx + 1;
    let sel_cls = if idx == 0 { " is-selected" } else { "" };
    let aria_cur = if idx == 0 { " aria-current=\"true\"" } else { "" };
    let open_url = web_primary_open_url(item).unwrap_or_default();

    let _ = write!(
        out,
        "<li class=\"rank-card js-rank-card{sel_cls}\" tabindex=\"0\"{aria_cur} data-entry-id=\"{id}\" data-title=\"{title}\" data-tied=\"0\" data-open-url=\"{open_url}\">\n\
           <div class=\"rank-left\">\n\
             <span class=\"rank-pos js-rank-pos\">#{rank_num}</span>\n\
             <div>\n\
               <strong><a href=\"/entries/{id_enc}\" target=\"_blank\" rel=\"noopener\">{title}</a></strong>",
        id = esc(&item.id),
        open_url = esc(&open_url),
        id_enc = esc(&urlencode(&item.id)),
        title = esc(&item.title),
    );
    if !item.creators.is_empty() {
        out.push_str(" <span class=\"text-soft\">&mdash; ");
        render_creator_links(out, &item.creators, artist_links, true);
        out.push_str("</span>");
    }
    if let Some(yr) = item.release_year {
        let _ = write!(out, " <span class=\"text-faint\">({yr})</span>");
    }
    out.push_str("</div>\n</div>\n<div class=\"rank-controls\">\n");

    let _ = write!(
        out,
        "<noscript><select name=\"pos_{}\" class=\"js-noscript-pos\" aria-label=\"Rank position for {}\">",
        esc(&item.id),
        esc(&item.title),
    );
    for p in 1..=cluster_len {
        let sel = if p == rank_num { " selected" } else { "" };
        let _ = write!(out, "<option value=\"{p}\"{sel}>#{p}</option>");
    }
    out.push_str("</select></noscript>\n");

    let tie_disabled = if idx == 0 { " disabled" } else { "" };
    let _ = writeln!(
        out,
        "<button type=\"button\" class=\"btn btn-small js-card-up\" title=\"Move Up (K)\" aria-label=\"Move {title} up\"><span aria-hidden=\"true\">&uarr;</span></button>\
         <button type=\"button\" class=\"btn btn-small js-card-down\" title=\"Move Down (J)\" aria-label=\"Move {title} down\"><span aria-hidden=\"true\">&darr;</span></button>\
         <button type=\"button\" class=\"btn btn-small btn-ghost js-card-tie\"{tie_disabled} title=\"Tie with above (=)\" aria-label=\"Tie {title} with above\" aria-pressed=\"false\"><span aria-hidden=\"true\">=</span> Tie</button>\
         </div>\n</li>",
        title = esc(&item.title),
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
