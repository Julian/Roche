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
        FlashQuery, NavSection, PageShell, WebError, WithWebCsrf as _, de_opt_from_str,
        de_query_bool, esc, parse_form_bool, redirect_see_other, redirect_with_flash, render_page,
        urlencode,
    },
    links::{
        load_artist_id_by_lower_name, render_creator_links, sanitize_non_local_href,
        web_primary_open_url,
    },
};
use crate::{
    db::{CatalogEntry, ClusterSelectFilter, RankedCollectionView},
    service::RocheService,
};

const MAX_FOCUSED_ROUNDS: usize = 5;
const MAX_COVERED_CLUSTERS: usize = 12;

const GRIP_SVG: &str = "<svg class=\"rank-grip-icon\" viewBox=\"0 0 12 18\" fill=\"currentColor\" aria-hidden=\"true\"><circle cx=\"3.5\" cy=\"4\" r=\"1.25\"/><circle cx=\"8.5\" cy=\"4\" r=\"1.25\"/><circle cx=\"3.5\" cy=\"9\" r=\"1.25\"/><circle cx=\"8.5\" cy=\"9\" r=\"1.25\"/><circle cx=\"3.5\" cy=\"14\" r=\"1.25\"/><circle cx=\"8.5\" cy=\"14\" r=\"1.25\"/></svg>";

const EXT_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 3.5H3.5v9h9v-3\"/><path d=\"M9.5 3.5h3v3\"/><path d=\"M8 8l4.5-4.5\"/></svg>";

#[derive(Debug, Default, Deserialize)]
pub(super) struct RankSessionQuery {
    #[serde(default, deserialize_with = "de_query_bool")]
    pub want: bool,
    pub focus: Option<String>,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub round: Option<usize>,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub random: Option<usize>,
    pub covered: Option<String>,
    #[serde(flatten)]
    pub flash: FlashQuery,
}

pub(super) async fn rank_picker_get(Query(flash): Query<FlashQuery>) -> Response {
    if let Some(err) = flash.err.as_deref().filter(|s| !s.is_empty()) {
        return redirect_with_flash("/lists", "err", err);
    }
    if let Some(notice) = flash.notice.as_deref().filter(|s| !s.is_empty()) {
        return redirect_with_flash("/lists", "notice", notice);
    }
    redirect_see_other("/lists")
}

pub(super) async fn rank_session_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Query(q): Query<RankSessionQuery>,
) -> Result<Response, WebError> {
    let round = q.round.unwrap_or(0);
    let focus_clean = q.focus.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let covered_sets = parse_covered_signatures(q.covered.as_deref());

    let ranked_view =
        service.catalog().get_ranked_collection(&slug, q.want).await.web_err(&session)?;

    let focus_row = focus_clean.and_then(|fid| ranked_view.rows.iter().find(|r| r.entry.id == fid));
    let focus_is_member = focus_row.is_some();
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
        service
            .catalog()
            .select_uncovered_cluster_from_ranked_view(&ranked_view, &filter, |cand| {
                is_cluster_covered(cand, &covered_sets)
            })
            .await
            .web_err(&session)?
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
        let focus_title = focus_row.map(|r| r.entry.title.as_str());
        render_active_cluster_view(
            &mut body,
            &ActiveClusterCtx {
                slug: &slug,
                q: &q,
                is_want: effective_want,
                ranked_view: &ranked_view,
                focus_id: if q.random == Some(1) { None } else { focus_clean },
                focus_title: if q.random == Some(1) { None } else { focus_title },
                cluster: &cluster,
                artist_links: &artist_links,
                csrf: &session.csrf_token,
            },
        );
    }

    let active = if effective_want { NavSection::Queue } else { NavSection::Lists };
    Ok(render_page(&PageShell {
        title: &format!("Rank: {}", ranked_view.collection.title),
        active,
        csrf_token: &session.csrf_token,
        flash: &q.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        body_html: &body,
    })
    .into_response())
}

fn render_rank_breadcrumb(out: &mut String, slug: &str, want: bool, view: &RankedCollectionView) {
    out.push_str("<nav class=\"rank-crumb\" aria-label=\"Breadcrumb\">\n");
    if want {
        let _ = writeln!(
            out,
            "<a href=\"/queue\">Queue</a> <span aria-hidden=\"true\">/</span> <span>{}</span>",
            esc(&view.collection.title),
        );
    } else {
        let list_slug = view.collection.slug.as_deref().unwrap_or(slug);
        let _ = writeln!(
            out,
            "<a href=\"/lists\">Lists</a> <span aria-hidden=\"true\">/</span> <a href=\"/lists/{}\">{}</a>",
            esc(&urlencode(list_slug)),
            esc(&view.collection.title),
        );
    }
    out.push_str("</nav>\n");
}

fn render_settled_rank_view(
    out: &mut String,
    slug: &str,
    want: bool,
    focus_id: Option<&str>,
    round: usize,
    ranked_view: &RankedCollectionView,
) {
    render_rank_breadcrumb(out, slug, want, ranked_view);
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
    ranked_view: &'a RankedCollectionView,
    focus_id: Option<&'a str>,
    focus_title: Option<&'a str>,
    cluster: &'a [CatalogEntry],
    artist_links: &'a HashMap<String, String>,
    csrf: &'a str,
}

fn render_active_cluster_view(out: &mut String, ctx: &ActiveClusterCtx<'_>) {
    let round = ctx.q.round.unwrap_or(0);
    let round_label = if ctx.focus_title.is_some() {
        format!("Round {} of {MAX_FOCUSED_ROUNDS}", round + 1)
    } else if ctx.is_want {
        format!("Queue Priority &middot; Round {}", round + 1)
    } else {
        format!("Round {}", round + 1)
    };
    let heading_verb = if ctx.is_want { "Prioritize" } else { "Rank" };
    let lede = if ctx.is_want {
        "Drag cards to order from <strong>#1</strong> (next up) to last &mdash; drop one onto another to <strong>tie side-by-side</strong>."
    } else {
        "Drag cards to order from <strong>#1</strong> (best) to worst &mdash; drop one onto another to <strong>tie side-by-side</strong>."
    };

    out.push_str("<div class=\"rank-session-grid\">\n<section class=\"rank-session-main\" aria-label=\"Comparison board\">\n");
    render_rank_breadcrumb(out, ctx.slug, ctx.is_want, ctx.ranked_view);
    let _ = writeln!(
        out,
        "<div class=\"rank-session-head\">\n\
           <h2>{heading_verb} {}</h2>\n\
           <span class=\"rank-round-pill\">{round_label}</span>\n\
         </div>\n\
         <p class=\"rank-session-lede\">{lede}</p>",
        esc(&ctx.ranked_view.collection.title),
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
           <ol id=\"roche-rank-board\" class=\"rank-board js-rank-list\" aria-label=\"Ranking comparison board\">\n",
        slug_enc = esc(&urlencode(ctx.slug)),
        csrf = esc(ctx.csrf),
        want = if ctx.is_want { "true" } else { "false" },
        focus = esc(ctx.q.focus.as_deref().unwrap_or("")),
        covered = esc(&next_covered),
        ord_json = esc(&default_json),
    );

    for (idx, item) in ctx.cluster.iter().enumerate() {
        let is_focus = ctx.focus_id == Some(item.id.as_str());
        render_cluster_card(out, idx, item, ctx.cluster.len(), is_focus, ctx.artist_links);
    }

    out.push_str(
        "</ol>\n\
         <div class=\"rank-action-bar\">\n\
           <div class=\"rank-action-primary\">\n\
             <button type=\"submit\" class=\"btn btn-primary\">Submit Comparison</button>\n\
             <button type=\"button\" class=\"btn js-rank-tie-all\" aria-pressed=\"false\"><span aria-hidden=\"true\">=</span> Tie All</button>\n\
           </div>\n\
           <button type=\"submit\" form=\"rank-skip-form\" class=\"btn btn-small btn-ghost btn-quiet rank-skip-btn\" title=\"Skip this round (s)\"><span>Skip</span><kbd aria-hidden=\"true\">s</kbd></button>\n\
         </div>\n\
         </form>\n",
    );

    // Skip form (GET to next round with covered signature recorded so cluster doesn't immediately repeat)
    let _ = write!(
        out,
        "<form id=\"rank-skip-form\" method=\"get\" action=\"/rank/{slug_enc}\" class=\"js-rank-skip-form\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{next_round}\">\n\
           <input type=\"hidden\" name=\"random\" value=\"1\">\n\
           <input type=\"hidden\" name=\"covered\" value=\"{covered}\">\n\
         </form>\n\
         </section>\n",
        slug_enc = esc(&urlencode(ctx.slug)),
        want = if ctx.is_want { "true" } else { "false" },
        focus = esc(ctx.q.focus.as_deref().unwrap_or("")),
        next_round = round + 1,
        covered = esc(&next_covered),
    );

    render_rank_session_rail(out, ctx, round);
    out.push_str("</div>\n");
}

fn render_rank_session_rail(out: &mut String, ctx: &ActiveClusterCtx<'_>, round: usize) {
    let total_items = ctx.ranked_view.rows.len();
    let comp_count = ctx.ranked_view.comparison_count;
    let item_word = if total_items == 1 { "item" } else { "items" };
    let comp_word = if comp_count == 1 { "comparison" } else { "comparisons" };
    let k = ctx.cluster.len();

    out.push_str("<div class=\"rank-session-rail\">\n<section class=\"rail-block rail-card\">\n<div class=\"section-head\"><h3>Session</h3></div>\n");
    let _ = writeln!(
        out,
        "<p class=\"rank-rail-meta\">{total_items} {item_word} &middot; {comp_count} {comp_word}</p>",
    );

    if let Some(focus_title) = ctx.focus_title {
        let cur_round = (round + 1).min(MAX_FOCUSED_ROUNDS);
        let _ = writeln!(
            out,
            "<div class=\"rank-focus-box\">\n\
               <div class=\"rank-focus-kicker\">Placing &middot; Round {cur_round} of {MAX_FOCUSED_ROUNDS}</div>\n\
               <div class=\"rank-focus-title\">{}</div>\n\
               <progress class=\"sleeve-progress rank-focus-progress\" value=\"{cur_round}\" max=\"{MAX_FOCUSED_ROUNDS}\"></progress>\n\
             </div>",
            esc(focus_title),
        );
    }

    out.push_str("<div class=\"rank-rail-links\">\n");
    if ctx.is_want {
        out.push_str("<a href=\"/queue\"><span>Back to Queue</span><span aria-hidden=\"true\">&rarr;</span></a>\n");
    } else {
        let list_slug = ctx.ranked_view.collection.slug.as_deref().unwrap_or(ctx.slug);
        let _ = writeln!(
            out,
            "<a href=\"/lists/{}\"><span>View Ranked List</span><span aria-hidden=\"true\">&rarr;</span></a>",
            esc(&urlencode(list_slug)),
        );
    }
    out.push_str(
        "<a href=\"/lists\"><span>All Lists</span><span aria-hidden=\"true\">&rarr;</span></a>\n\
         </div>\n</section>\n",
    );

    let _ = writeln!(
        out,
        "<section class=\"rail-block rail-card\">\n\
           <div class=\"section-head\"><h3>How to Order</h3></div>\n\
           <ul class=\"rank-tips-list\">\n\
             <li><strong>Reorder:</strong> drag a card between rows (or use &uarr; / &darr;).</li>\n\
             <li><strong>Tie:</strong> drop a card directly onto another row to place them side-by-side.</li>\n\
             <li><strong>Untie:</strong> drag a card to the top or bottom edge, or click <em>Untie</em>.</li>\n\
           </ul>\n\
           <details class=\"rank-kbd-details\">\n\
             <summary>Keyboard shortcuts</summary>\n\
             <dl class=\"rank-kbd-list\">\n\
               <dt><kbd>j</kbd><kbd>k</kbd></dt><dd>Select item</dd>\n\
               <dt><kbd>J</kbd><kbd>K</kbd></dt><dd>Move up / down</dd>\n\
               <dt><kbd>1</kbd>&ndash;<kbd>{k}</kbd></dt><dd>Jump to rank</dd>\n\
               <dt><kbd>=</kbd></dt><dd>Tie / untie</dd>\n\
               <dt><kbd>t</kbd></dt><dd>Tie all</dd>\n\
               <dt><kbd>s</kbd></dt><dd>Skip round</dd>\n\
               <dt><kbd>o</kbd></dt><dd>Open link</dd>\n\
               <dt><kbd>Enter</kbd></dt><dd>Submit</dd>\n\
             </dl>\n\
           </details>\n\
         </section>\n</div>",
    );
}

fn render_cluster_card(
    out: &mut String,
    idx: usize,
    item: &CatalogEntry,
    cluster_len: usize,
    is_focus: bool,
    artist_links: &HashMap<String, String>,
) {
    let rank_num = idx + 1;
    let sel_cls = if idx == 0 { " is-selected" } else { "" };
    let aria_cur = if idx == 0 { " aria-current=\"true\"" } else { "" };
    let open_url = web_primary_open_url(item).unwrap_or_default();

    let _ = write!(
        out,
        "<li class=\"rank-bucket\" data-bucket-index=\"{idx}\">\n\
           <div class=\"rank-bucket-gutter\" aria-hidden=\"true\">\n\
             <span class=\"rank-pos js-rank-pos\">#{rank_num}</span>\n\
             <span class=\"rank-tie-tag\">tie</span>\n\
           </div>\n\
           <div class=\"rank-bucket-cards\">\n\
             <div class=\"rank-card js-rank-card{sel_cls}\" tabindex=\"0\" draggable=\"true\"{aria_cur} data-entry-id=\"{id}\" data-title=\"{title}\" data-tied=\"0\" data-open-url=\"{open_url}\">\n\
               <div class=\"rank-left\">\n\
                 <span class=\"rank-grip\" aria-hidden=\"true\" title=\"Drag to reorder or drop onto another card to tie\">{GRIP_SVG}</span>\n",
        id = esc(&item.id),
        open_url = esc(&open_url),
        title = esc(&item.title),
    );

    if let Some(thumb) = item.thumbnail_url.as_deref().and_then(sanitize_non_local_href) {
        let _ = writeln!(
            out,
            "<img class=\"rank-thumb\" src=\"{}\" alt=\"\" loading=\"lazy\" draggable=\"false\">",
            esc(thumb),
        );
    } else {
        let initial = item
            .title
            .chars()
            .find(|c| c.is_alphanumeric())
            .map_or('?', |c| c.to_ascii_uppercase());
        let _ = writeln!(
            out,
            "<span class=\"rank-thumb-fallback\" aria-hidden=\"true\">{initial}</span>",
        );
    }

    let _ = write!(
        out,
        "<div class=\"rank-meta\">\n\
           <div class=\"rank-title-row\">\n\
             <strong><a href=\"/entries/{id_enc}\" target=\"_blank\" rel=\"noopener\" draggable=\"false\">{title}</a></strong>",
        id_enc = esc(&urlencode(&item.id)),
        title = esc(&item.title),
    );
    if is_focus {
        out.push_str(" <span class=\"rank-focus-pill\">Placing</span>");
    }
    out.push_str("</div>\n");

    let has_sub = !item.creators.is_empty()
        || item.release_year.is_some()
        || sanitize_non_local_href(&open_url).is_some();
    if has_sub {
        out.push_str("<div class=\"rank-sub\">");
        if !item.creators.is_empty() {
            render_creator_links(out, &item.creators, artist_links, true);
        }
        if let Some(yr) = item.release_year {
            let _ = write!(out, " <span>({yr})</span>");
        }
        if let Some(href) = sanitize_non_local_href(&open_url) {
            let _ = write!(
                out,
                " <a class=\"rank-ext-link\" href=\"{}\" target=\"_blank\" rel=\"noopener\" draggable=\"false\" title=\"Open link (o)\" aria-label=\"Open {} external link\">{EXT_SVG}</a>",
                esc(href),
                esc(&item.title),
            );
        }
        out.push_str("</div>\n");
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
        "<button type=\"button\" class=\"btn btn-small btn-ghost js-card-up\" title=\"Move Up (K)\" aria-label=\"Move {title} up\"><span aria-hidden=\"true\">&uarr;</span></button>\
         <button type=\"button\" class=\"btn btn-small btn-ghost js-card-down\" title=\"Move Down (J)\" aria-label=\"Move {title} down\"><span aria-hidden=\"true\">&darr;</span></button>\
         <button type=\"button\" class=\"btn btn-small btn-ghost js-card-tie\"{tie_disabled} title=\"Tie with above (=)\" aria-label=\"Tie {title} with above\" aria-pressed=\"false\"><span aria-hidden=\"true\">=</span> Tie</button>\
         </div>\n</div>\n</div>\n</li>",
        title = esc(&item.title),
    );
}

pub(super) async fn rank_session_post(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    body: Bytes,
) -> Result<Response, WebError> {
    let pairs: Vec<(String, String)> =
        url::form_urlencoded::parse(&body).map(|(k, v)| (k.into_owned(), v.into_owned())).collect();

    let find_val = |key: &str| -> Option<&str> {
        pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    };

    let want = parse_form_bool(find_val("want"));
    let focus = find_val("focus").map(str::trim).filter(|s| !s.is_empty());
    let round_opt = find_val("round").and_then(|r| r.parse::<usize>().ok());
    let round = round_opt.unwrap_or(0);
    let covered = find_val("covered").unwrap_or("");

    let ordering = parse_submitted_ordering(&pairs);
    let Some(groups) = ordering else {
        let base = build_rank_session_href(&slug, round_opt, want, focus, None);
        return Ok(redirect_with_flash(&base, "err", "Invalid comparison ordering"));
    };

    let (effective_want, scope_col_id) = if want {
        let col = service.catalog().get_or_create_want_collection(&slug).await.web_err(&session)?;
        (true, Some(col.id))
    } else {
        service.catalog().resolve_ranking_scope(&slug, false).await.web_err(&session)?
    };

    service
        .catalog()
        .append_comparison(scope_col_id.as_deref(), &groups, None)
        .await
        .web_err(&session)?;

    Ok(redirect_see_other(&build_rank_session_href(
        &slug,
        Some(round + 1),
        effective_want,
        focus,
        Some(covered),
    )))
}

fn build_rank_session_href(
    slug: &str,
    round: Option<usize>,
    want: bool,
    focus: Option<&str>,
    covered: Option<&str>,
) -> String {
    let mut query_parts = Vec::new();
    if let Some(r) = round {
        query_parts.push(format!("round={r}"));
    }
    if want {
        query_parts.push("want=true".to_owned());
    }
    if let Some(fid) = focus {
        query_parts.push(format!("focus={}", urlencode(fid)));
    }
    if let Some(cov) = covered.filter(|c| !c.is_empty()) {
        query_parts.push(format!("covered={}", urlencode(cov)));
    }
    if query_parts.is_empty() {
        format!("/rank/{}", urlencode(slug))
    } else {
        format!("/rank/{}?{}", urlencode(slug), query_parts.join("&"))
    }
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
