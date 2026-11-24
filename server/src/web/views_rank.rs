use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    Form,
    body::Bytes,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};
use serde::Deserialize;

use super::{
    auth::{WebSession, append_rank_k_set_cookie},
    html::{
        NavSection, PageShell, WebError, WithWebCsrf as _, de_opt_from_str, de_query_bool, esc,
        parse_form_bool, redirect_see_other, redirect_with_flash, render_inline_csrf_form,
        render_page, safe_local_redirect, urlencode,
    },
    links::{
        load_artist_id_by_lower_name, render_creator_links, sanitize_non_local_href,
        web_primary_open_url,
    },
};
use crate::{
    db::CatalogEntry,
    ranking::{MAX_FOCUSED_ROUNDS, MAX_K_DEFAULT, MIN_K},
    service::{
        ClusterSelectFilter, PrioritizedCluster, RankedCollectionView, RankedEntryRow, RocheService,
    },
};

const MAX_CUSTOM_ROUNDS: usize = 50;
const MAX_COVERED_CLUSTERS: usize = 12;
const MAX_EXCLUDED_TOKENS: usize = 24;

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
    pub skip: Option<usize>,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub max_rounds: Option<usize>,
    #[serde(default, deserialize_with = "de_query_bool")]
    pub done: bool,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub random: Option<usize>,
    pub covered: Option<String>,
    pub excluded: Option<String>,
    #[serde(default, deserialize_with = "de_opt_from_str")]
    pub k: Option<usize>,
    pub undo_comp: Option<String>,
    pub undo_excluded: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
pub(super) struct ComparisonUndoForm {
    #[serde(default)]
    pub return_to: Option<String>,
}

pub(super) async fn rank_picker_get() -> Response {
    redirect_see_other("/lists")
}

pub(super) async fn rank_session_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(slug): Path<String>,
    Query(q): Query<RankSessionQuery>,
) -> Result<Response, WebError> {
    let round = q.round.unwrap_or(0);
    let skip_count = q.skip.unwrap_or(0);
    let effective_max_rounds =
        q.max_rounds.unwrap_or(MAX_FOCUSED_ROUNDS).clamp(MAX_FOCUSED_ROUNDS, MAX_CUSTOM_ROUNDS);
    let explicit_k = q.k.map(|v| v.clamp(MIN_K, MAX_K_DEFAULT));
    let effective_k =
        explicit_k.or(session.rank_k).unwrap_or(MAX_K_DEFAULT).clamp(MIN_K, MAX_K_DEFAULT);
    let focus_clean = q.focus.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let undo_comp_clean = q.undo_comp.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let covered_sets = parse_covered_signatures(q.covered.as_deref());
    let excluded_tokens = parse_excluded_tokens(q.excluded.as_deref());

    let ranked_view =
        service.catalog().get_ranked_collection(&slug, q.want).await.web_err(&session)?;

    let focus_row = focus_clean.and_then(|fid| ranked_view.rows.iter().find(|r| r.entry.id == fid));
    let selected = select_rank_session_cluster(
        &service,
        &session,
        &ranked_view,
        &q,
        round,
        skip_count,
        effective_max_rounds,
        effective_k,
        focus_clean,
        focus_row.is_some(),
        &covered_sets,
        &excluded_tokens,
    )
    .await?;

    let effective_want = q.want || ranked_view.is_priority_scope;
    let undo_return_to = build_undo_return_to(
        &slug,
        &q,
        round,
        effective_want,
        effective_max_rounds,
        effective_k,
        focus_clean,
    );

    let mut body = String::with_capacity(8_192);
    if selected.entries.len() < 2 {
        render_settled_rank_view(
            &mut body,
            &SettledRankCtx {
                slug: &slug,
                want: effective_want,
                focus_id: focus_clean,
                round,
                effective_k,
                excluded: q.excluded.as_deref(),
                ranked_view: &ranked_view,
                undo_comp: undo_comp_clean,
                undo_return_to: &undo_return_to,
                csrf: &session.csrf_token,
            },
        );
    } else {
        let cluster_creators: Vec<String> =
            selected.entries.iter().flat_map(|e| e.creators.iter().cloned()).collect();
        let artist_links = load_artist_id_by_lower_name(&service, &cluster_creators).await;
        let active_focus = (q.random != Some(1)).then_some(()).and(focus_clean);
        let active_focus_row = (q.random != Some(1)).then_some(()).and(focus_row);
        render_active_cluster_view(
            &mut body,
            &ActiveClusterCtx {
                slug: &slug,
                q: &q,
                is_want: effective_want,
                effective_max_rounds,
                effective_k,
                skip_count,
                ranked_view: &ranked_view,
                focus_id: active_focus,
                focus_row: active_focus_row,
                undo_comp: undo_comp_clean,
                undo_return_to: &undo_return_to,
                cluster: &selected.entries,
                priority_ids: &selected.priority_ids,
                artist_links: &artist_links,
                csrf: &session.csrf_token,
            },
        );
    }

    Ok(build_rank_session_response(
        &service,
        &session,
        &ranked_view.collection.title,
        effective_want,
        &body,
        explicit_k,
    ))
}

fn build_undo_return_to(
    slug: &str,
    q: &RankSessionQuery,
    round: usize,
    effective_want: bool,
    effective_max_rounds: usize,
    effective_k: usize,
    focus_clean: Option<&str>,
) -> String {
    let undo_excluded_target = q
        .undo_excluded
        .as_deref()
        .map_or(q.excluded.as_deref(), |prev| (!prev.is_empty()).then_some(prev));
    build_rank_session_href(
        slug,
        &RankHrefOpts {
            round: Some(round.saturating_sub(1)),
            want: effective_want,
            focus: if q.random == Some(1) { None } else { focus_clean },
            max_rounds: (effective_max_rounds != MAX_FOCUSED_ROUNDS)
                .then_some(effective_max_rounds),
            excluded: undo_excluded_target,
            k: (effective_k != MAX_K_DEFAULT).then_some(effective_k),
            ..RankHrefOpts::default()
        },
    )
}

fn build_rank_session_response(
    service: &RocheService,
    session: &WebSession,
    collection_title: &str,
    effective_want: bool,
    body: &str,
    explicit_k: Option<usize>,
) -> Response {
    let active = if effective_want { NavSection::Queue } else { NavSection::Lists };
    let mut resp = render_page(&PageShell {
        title: &format!("Rank: {collection_title}"),
        active,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: body,
    })
    .into_response();
    if let Some(explicit_k_val) = explicit_k {
        append_rank_k_set_cookie(&mut resp, explicit_k_val, session.secure_cookie);
    }
    resp
}

#[allow(clippy::too_many_arguments)]
async fn select_rank_session_cluster(
    service: &RocheService,
    session: &WebSession,
    ranked_view: &RankedCollectionView,
    q: &RankSessionQuery,
    round: usize,
    skip_count: usize,
    effective_max_rounds: usize,
    effective_k: usize,
    focus_clean: Option<&str>,
    focus_is_member: bool,
    covered_sets: &[HashSet<String>],
    excluded_tokens: &HashSet<String>,
) -> Result<PrioritizedCluster, WebError> {
    let focus_missing_from_list = focus_clean.is_some() && !focus_is_member;
    if q.done || focus_missing_from_list || (focus_is_member && round >= effective_max_rounds) {
        return Ok(PrioritizedCluster::default());
    }
    let focus_ids: Vec<String> = if q.random == Some(1) || !focus_is_member {
        Vec::new()
    } else {
        focus_clean.into_iter().map(ToOwned::to_owned).collect()
    };
    let excluded_ids: Vec<String> = if excluded_tokens.is_empty() {
        Vec::new()
    } else {
        ranked_view
            .rows
            .iter()
            .filter(|r| excluded_tokens.contains(&short_cluster_token(&r.entry.id)))
            .map(|r| r.entry.id.clone())
            .collect()
    };
    let iter_idx =
        if q.random == Some(1) { 5 + skip_count } else { round.saturating_add(skip_count) };
    let filter = ClusterSelectFilter {
        max_k: effective_k,
        iteration: iter_idx,
        focus_ids,
        excluded_ids,
        ..ClusterSelectFilter::default()
    };
    service
        .catalog()
        .select_uncovered_cluster_prioritized_from_ranked_view(ranked_view, &filter, |cand| {
            is_cluster_covered(cand, covered_sets)
        })
        .await
        .web_err(session)
}

fn render_rank_undo_bar(out: &mut String, undo_comp: Option<&str>, return_to: &str, csrf: &str) {
    let Some(comp_id) = undo_comp.map(str::trim).filter(|s| !s.is_empty()) else {
        return;
    };
    let action = format!("/web/comparisons/{}/undo", urlencode(comp_id));
    out.push_str(
        "<div class=\"undo-banner rank-undo-bar\" role=\"status\"><span>Comparison saved.</span>",
    );
    render_inline_csrf_form(
        out,
        &action,
        csrf,
        &[("return_to", return_to)],
        None,
        "<button type=\"submit\" class=\"btn btn-small\">Undo</button>",
    );
    out.push_str("</div>\n");
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

struct SettledRankCtx<'a> {
    slug: &'a str,
    want: bool,
    focus_id: Option<&'a str>,
    round: usize,
    effective_k: usize,
    excluded: Option<&'a str>,
    ranked_view: &'a RankedCollectionView,
    undo_comp: Option<&'a str>,
    undo_return_to: &'a str,
    csrf: &'a str,
}

fn render_settled_rank_view(out: &mut String, ctx: &SettledRankCtx<'_>) {
    render_rank_breadcrumb(out, ctx.slug, ctx.want, ctx.ranked_view);
    let _ = writeln!(out, "<h2>{}</h2>", esc(&ctx.ranked_view.collection.title));
    render_rank_undo_bar(out, ctx.undo_comp, ctx.undo_return_to, ctx.csrf);
    out.push_str("<div class=\"settled-card\">\n");

    let settled_focus_row =
        ctx.focus_id.and_then(|fid| ctx.ranked_view.rows.iter().find(|r| r.entry.id == fid));

    if let Some(row) = settled_focus_row {
        let tier_part = row.tier.map_or_else(String::new, |t| {
            format!(" &middot; <strong class=\"tier-{t}\">Tier {t}</strong>")
        });
        let _ = writeln!(
            out,
            "<div class=\"album-kicker\">Placement Settled</div>\n\
             <h3 class=\"settled-title\">{}</h3>\n\
             <p class=\"lede mb-md\">Settled at <strong>#{}</strong> of {}{} after {} comparison round(s).</p>",
            roche_core::document_meta::render_inline_math_html(&row.entry.title),
            row.rank,
            ctx.ranked_view.rows.len(),
            tier_part,
            ctx.round,
        );
    } else if let Some(fid) = ctx.focus_id {
        let _ = writeln!(
            out,
            "<p class=\"lede mb-md\">This work is not in <strong>{}</strong> yet. Mark it Finished on its <a href=\"/entries/{}\">entry page</a> before ranking it.</p>",
            esc(&ctx.ranked_view.collection.title),
            esc(&urlencode(fid)),
        );
    } else if ctx.ranked_view.rows.len() < 2 {
        out.push_str(
            "<p class=\"lede mb-md\">Need at least 2 works in this collection before running comparisons.</p>\n",
        );
    } else {
        let _ = writeln!(
            out,
            "<p class=\"lede mb-md\">Rankings are well-calibrated after {} round(s).</p>",
            ctx.round,
        );
    }

    let persisted_k_param = (ctx.effective_k != MAX_K_DEFAULT).then_some(ctx.effective_k);
    out.push_str("<div class=\"flex-row\">\n");
    if ctx.want {
        out.push_str("<a class=\"btn btn-primary\" href=\"/queue\">Back to Queue</a>\n");
    } else {
        let list_slug = ctx.ranked_view.collection.slug.as_deref().unwrap_or(ctx.slug);
        let _ = writeln!(
            out,
            "<a class=\"btn btn-primary\" href=\"/lists/{}\">View Ranked List</a>",
            esc(&urlencode(list_slug))
        );
    }
    if let Some(row) = settled_focus_row
        && ctx.ranked_view.rows.len() >= 2
        && ctx.round < MAX_CUSTOM_ROUNDS
    {
        let next_max = (ctx.round + MAX_FOCUSED_ROUNDS).min(MAX_CUSTOM_ROUNDS);
        let refine_href = build_rank_session_href(
            ctx.slug,
            &RankHrefOpts {
                round: Some(ctx.round),
                want: ctx.want,
                focus: Some(&row.entry.id),
                max_rounds: Some(next_max),
                excluded: ctx.excluded,
                k: persisted_k_param,
                ..RankHrefOpts::default()
            },
        );
        let _ = writeln!(out, "<a class=\"btn\" href=\"{}\">Keep Refining</a>", esc(&refine_href));
    }
    if ctx.ranked_view.rows.len() >= 2 {
        let keep_href = build_rank_session_href(
            ctx.slug,
            &RankHrefOpts {
                want: ctx.want,
                random: Some(1),
                k: persisted_k_param,
                ..RankHrefOpts::default()
            },
        );
        let _ = writeln!(out, "<a class=\"btn\" href=\"{}\">Keep Comparing</a>", esc(&keep_href));
    }
    out.push_str("<a class=\"btn btn-ghost\" href=\"/\">Home</a>\n</div>\n</div>\n");
}

struct ActiveClusterCtx<'a> {
    slug: &'a str,
    q: &'a RankSessionQuery,
    is_want: bool,
    effective_max_rounds: usize,
    effective_k: usize,
    skip_count: usize,
    ranked_view: &'a RankedCollectionView,
    focus_id: Option<&'a str>,
    focus_row: Option<&'a RankedEntryRow>,
    undo_comp: Option<&'a str>,
    undo_return_to: &'a str,
    cluster: &'a [CatalogEntry],
    priority_ids: &'a [String],
    artist_links: &'a HashMap<String, String>,
    csrf: &'a str,
}

fn render_active_cluster_view(out: &mut String, ctx: &ActiveClusterCtx<'_>) {
    let round = ctx.q.round.unwrap_or(0);
    let max_rounds = ctx.effective_max_rounds;
    let round_label = if ctx.focus_row.is_some() {
        format!("Round {} of {max_rounds}", round + 1)
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
    render_rank_undo_bar(out, ctx.undo_comp, ctx.undo_return_to, ctx.csrf);

    let total_members = ctx.ranked_view.rows.len();
    let max_selectable_k = total_members.min(MAX_K_DEFAULT);
    if max_selectable_k > MIN_K {
        render_cluster_size_bar(out, ctx, round, max_selectable_k);
    }

    let default_groups: Vec<Vec<&str>> = ctx.cluster.iter().map(|e| vec![e.id.as_str()]).collect();
    let default_json = serde_json::to_string(&default_groups).unwrap_or_else(|_| "[]".to_owned());
    let cluster_sig = cluster_signature(ctx.cluster);
    let prev_covered = ctx.q.covered.as_deref().unwrap_or("");
    let skip_covered = append_covered_signature(ctx.q.covered.as_deref(), &cluster_sig);
    let prev_excluded = ctx.q.excluded.as_deref().unwrap_or("");
    let priority_by_id: HashMap<&str, usize> =
        ctx.priority_ids.iter().enumerate().map(|(i, id)| (id.as_str(), i)).collect();

    let _ = write!(
        out,
        "<form method=\"post\" action=\"/rank/{slug_enc}\" class=\"js-rank-form\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{round}\">\n\
           <input type=\"hidden\" name=\"max_rounds\" value=\"{max_rounds}\">\n\
           <input type=\"hidden\" name=\"covered\" value=\"{prev_covered}\">\n\
           <input type=\"hidden\" name=\"excluded\" value=\"{prev_excluded}\">\n\
           <input type=\"hidden\" name=\"round_excluded\" value=\"\">\n\
           <input type=\"hidden\" name=\"k\" value=\"{effective_k}\">\n\
           <input type=\"hidden\" name=\"ordering_json\" value=\"{ord_json}\">\n\
           <div id=\"rank-live-status\" class=\"sr-only\" role=\"status\" aria-live=\"polite\" aria-atomic=\"true\"></div>\n\
           <ol id=\"roche-rank-board\" class=\"rank-board js-rank-list\" data-loaded-max-k=\"{effective_k}\" aria-label=\"Ranking comparison board\">\n",
        slug_enc = esc(&urlencode(ctx.slug)),
        csrf = esc(ctx.csrf),
        want = if ctx.is_want { "true" } else { "false" },
        focus = esc(ctx.focus_id.unwrap_or("")),
        prev_covered = esc(prev_covered),
        prev_excluded = esc(prev_excluded),
        effective_k = ctx.effective_k,
        ord_json = esc(&default_json),
    );

    for (idx, item) in ctx.cluster.iter().enumerate() {
        let is_focus = ctx.focus_id == Some(item.id.as_str());
        let priority = priority_by_id.get(item.id.as_str()).copied().unwrap_or(idx);
        render_cluster_card(
            out,
            idx,
            item,
            ctx.cluster.len(),
            priority,
            is_focus,
            ctx.artist_links,
        );
    }

    out.push_str(
        "</ol>\n\
         <div class=\"rank-excluded-tray js-rank-excluded-tray\" hidden>\n\
           <div class=\"rank-excluded-head\">\n\
             <span class=\"rank-excluded-label js-rank-excluded-label\">Excluded from this comparison</span>\n\
             <button type=\"button\" class=\"btn btn-small btn-ghost js-rank-restore-all\">Restore all</button>\n\
           </div>\n\
           <div class=\"rank-excluded-chips js-rank-excluded-chips\"></div>\n\
         </div>\n\
         <div class=\"rank-action-bar\">\n\
           <div class=\"rank-action-primary\">\n\
             <button type=\"submit\" class=\"btn btn-primary\">Submit Comparison</button>\n\
             <button type=\"button\" class=\"btn js-rank-tie-all\" aria-pressed=\"false\"><span aria-hidden=\"true\">=</span> Tie All</button>\n\
           </div>\n\
           <button type=\"submit\" form=\"rank-skip-form\" class=\"btn btn-small btn-ghost btn-quiet rank-skip-btn\" title=\"Skip this matchup without using a round (s)\"><span>Skip</span><kbd aria-hidden=\"true\">s</kbd></button>\n\
         </div>\n\
         </form>\n",
    );

    render_rank_skip_form(out, ctx, round, prev_covered, prev_excluded, &skip_covered);
    render_rank_session_rail(out, ctx, round);
    out.push_str("</div>\n");
}

fn render_rank_skip_form(
    out: &mut String,
    ctx: &ActiveClusterCtx<'_>,
    round: usize,
    prev_covered: &str,
    prev_excluded: &str,
    skip_covered: &str,
) {
    // Skip form: preserves `focus`, `round`, `k`, and `excluded`, increments `skip`, and appends
    // `covered` so fresh opponents are drawn without burning a focused round or losing focus.
    let _ = write!(
        out,
        "<form id=\"rank-skip-form\" method=\"get\" action=\"/rank/{slug_enc}\" class=\"js-rank-skip-form\" data-prev-covered=\"{prev_covered}\" data-prev-excluded=\"{prev_excluded}\">\n\
           <input type=\"hidden\" name=\"want\" value=\"{want}\">\n\
           <input type=\"hidden\" name=\"focus\" value=\"{focus}\">\n\
           <input type=\"hidden\" name=\"round\" value=\"{round}\">\n\
           <input type=\"hidden\" name=\"skip\" value=\"{next_skip}\">\n\
           <input type=\"hidden\" name=\"max_rounds\" value=\"{max_rounds}\">\n\
           <input type=\"hidden\" name=\"k\" value=\"{effective_k}\">\n\
           <input type=\"hidden\" name=\"excluded\" value=\"{prev_excluded}\">\n",
        slug_enc = esc(&urlencode(ctx.slug)),
        prev_covered = esc(prev_covered),
        prev_excluded = esc(prev_excluded),
        want = if ctx.is_want { "true" } else { "false" },
        focus = esc(ctx.focus_id.unwrap_or("")),
        next_skip = ctx.skip_count + 1,
        max_rounds = ctx.effective_max_rounds,
        effective_k = ctx.effective_k,
    );
    if ctx.q.random == Some(1) {
        out.push_str("<input type=\"hidden\" name=\"random\" value=\"1\">\n");
    }
    let _ = write!(
        out,
        "  <input type=\"hidden\" name=\"covered\" value=\"{covered}\">\n\
         </form>\n\
         </section>\n",
        covered = esc(skip_covered),
    );
}

fn render_cluster_size_bar(
    out: &mut String,
    ctx: &ActiveClusterCtx<'_>,
    round: usize,
    max_selectable_k: usize,
) {
    let active_k = ctx.effective_k.min(max_selectable_k);
    out.push_str(
        "<div class=\"rank-size-bar\">\n\
           <span class=\"rank-size-label\" id=\"rank-size-label\">Compare</span>\n\
           <div class=\"rank-size-pills js-rank-size-group\" role=\"group\" aria-labelledby=\"rank-size-label\">\n",
    );
    for size in MIN_K..=max_selectable_k {
        let is_active = size == active_k;
        let href = build_rank_session_href(
            ctx.slug,
            &RankHrefOpts {
                round: (round > 0).then_some(round),
                skip: (ctx.skip_count > 0).then_some(ctx.skip_count),
                want: ctx.is_want,
                focus: ctx.focus_id,
                max_rounds: (ctx.effective_max_rounds != MAX_FOCUSED_ROUNDS)
                    .then_some(ctx.effective_max_rounds),
                covered: ctx.q.covered.as_deref(),
                excluded: ctx.q.excluded.as_deref(),
                k: Some(size),
                random: ctx.q.random,
                ..RankHrefOpts::default()
            },
        );
        let active_cls = if is_active { " is-active" } else { "" };
        let aria_cur = if is_active { " aria-current=\"true\"" } else { "" };
        let _ = writeln!(
            out,
            "    <a class=\"pill rank-size-opt js-rank-size-opt{active_cls}\"{aria_cur} href=\"{}\" data-rank-k=\"{size}\" aria-label=\"Compare {size} items\">{size}</a>",
            esc(&href),
        );
    }
    let _ = writeln!(
        out,
        "  </div>\n\
           <span class=\"rank-size-hint js-rank-size-hint\">Comparing {} items</span>\n\
         </div>",
        ctx.cluster.len(),
    );
}

fn render_rank_session_rail(out: &mut String, ctx: &ActiveClusterCtx<'_>, round: usize) {
    let total_items = ctx.ranked_view.rows.len();
    let comp_count = ctx.ranked_view.comparison_count;
    let item_word = if total_items == 1 { "item" } else { "items" };
    let comp_word = if comp_count == 1 { "comparison" } else { "comparisons" };
    let k = ctx.cluster.len();
    let max_rounds = ctx.effective_max_rounds;
    let persisted_k_param = (ctx.effective_k != MAX_K_DEFAULT).then_some(ctx.effective_k);

    out.push_str("<div class=\"rank-session-rail\">\n<section class=\"rail-block rail-card\">\n<div class=\"section-head\"><h3>Session</h3></div>\n");
    let _ = writeln!(
        out,
        "<p class=\"rank-rail-meta\">{total_items} {item_word} &middot; {comp_count} {comp_word}</p>",
    );

    if let Some(focus_row) = ctx.focus_row {
        let cur_round = (round + 1).min(max_rounds);
        let _ = writeln!(
            out,
            "<div class=\"rank-focus-box\">\n\
               <div class=\"rank-focus-kicker\">Placing &middot; Round {cur_round} of {max_rounds}</div>\n\
               <div class=\"rank-focus-title\">{}</div>\n\
               <progress class=\"sleeve-progress rank-focus-progress\" value=\"{cur_round}\" max=\"{max_rounds}\"></progress>",
            roche_core::document_meta::render_inline_math_html(&focus_row.entry.title),
        );
        if round >= 1 {
            let tier_part = focus_row.tier.map_or_else(String::new, |t| {
                format!(" &middot; <strong class=\"tier-{t}\">Tier {t}</strong>")
            });
            let done_href = build_rank_session_href(
                ctx.slug,
                &RankHrefOpts {
                    round: Some(round),
                    want: ctx.is_want,
                    focus: Some(&focus_row.entry.id),
                    k: persisted_k_param,
                    done: true,
                    ..RankHrefOpts::default()
                },
            );
            let _ = writeln!(
                out,
                "<div class=\"rank-focus-prov\">Provisional: <strong>#{}</strong> of {total_items}{tier_part}</div>\n\
                 <a class=\"btn btn-small btn-ghost rank-done-early\" href=\"{}\">Done Placing</a>",
                focus_row.rank,
                esc(&done_href),
            );
        }
        out.push_str("</div>\n");
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

    let max_selectable_k = total_items.min(MAX_K_DEFAULT);
    let exclude_tip = if max_selectable_k > MIN_K {
        format!(
            "             <li><strong>Exclude:</strong> click <em>&times;</em> on a card or pick {MIN_K}&ndash;{max_selectable_k} above to leave unfamiliar items out.</li>\n"
        )
    } else {
        String::new()
    };
    let _ = writeln!(
        out,
        "<section class=\"rail-block rail-card\">\n\
           <div class=\"section-head\"><h3>How to Order</h3></div>\n\
           <ul class=\"rank-tips-list\">\n\
             <li><strong>Reorder:</strong> drag a card between rows (or use &uarr; / &darr;).</li>\n\
             <li><strong>Tie:</strong> drop a card directly onto another row to place them side-by-side.</li>\n\
             <li><strong>Untie:</strong> drag a card to the top or bottom edge, or click <em>Untie</em>.</li>\n\
{exclude_tip}\
           </ul>\n\
           <details class=\"rank-kbd-details\">\n\
             <summary>Keyboard shortcuts</summary>\n\
             <dl class=\"rank-kbd-list\">\n\
               <dt><kbd>j</kbd><kbd>k</kbd></dt><dd>Select item</dd>\n\
               <dt><kbd>J</kbd><kbd>K</kbd></dt><dd>Move up / down</dd>\n\
               <dt><kbd>1</kbd>&ndash;<kbd class=\"js-rank-kbd-max\">{k}</kbd></dt><dd>Jump to rank</dd>\n\
               <dt><kbd>=</kbd></dt><dd>Tie / untie</dd>\n\
               <dt><kbd>t</kbd></dt><dd>Tie all</dd>\n\
               <dt><kbd>x</kbd></dt><dd>Exclude item</dd>\n\
               <dt><kbd>-</kbd><kbd>+</kbd></dt><dd>Cluster size</dd>\n\
               <dt><kbd>s</kbd></dt><dd>Skip matchup</dd>\n\
               <dt><kbd>o</kbd></dt><dd>Open link</dd>\n\
               <dt><kbd>Enter</kbd></dt><dd>Submit</dd>\n\
             </dl>\n\
           </details>\n\
         </section>\n</div>",
    );
}

#[allow(clippy::too_many_arguments)]
fn render_cluster_card(
    out: &mut String,
    idx: usize,
    item: &CatalogEntry,
    cluster_len: usize,
    priority: usize,
    is_focus: bool,
    artist_links: &HashMap<String, String>,
) {
    let rank_num = idx + 1;
    let sel_cls = if idx == 0 { " is-selected" } else { "" };
    let aria_cur = if idx == 0 { " aria-current=\"true\"" } else { "" };
    let open_url = web_primary_open_url(item).unwrap_or_default();
    let focus_attr = if is_focus { "1" } else { "0" };

    let plain_title = roche_core::document_meta::strip_inline_math_delimiters(&item.title);
    let _ = write!(
        out,
        "<li class=\"rank-bucket\" data-bucket-index=\"{idx}\">\n\
           <div class=\"rank-bucket-gutter\" aria-hidden=\"true\">\n\
             <span class=\"rank-pos js-rank-pos\">#{rank_num}</span>\n\
             <span class=\"rank-tie-tag\">tie</span>\n\
           </div>\n\
           <div class=\"rank-bucket-cards\">\n\
             <div class=\"rank-card js-rank-card{sel_cls}\" tabindex=\"0\" draggable=\"true\"{aria_cur} data-entry-id=\"{id}\" data-title=\"{title}\" data-tied=\"0\" data-priority=\"{priority}\" data-focus=\"{focus_attr}\" data-init-idx=\"{idx}\" data-open-url=\"{open_url}\">\n\
               <div class=\"rank-left\">\n\
                 <span class=\"rank-grip\" aria-hidden=\"true\" title=\"Drag to reorder or drop onto another card to tie\">{GRIP_SVG}</span>\n",
        id = esc(&item.id),
        open_url = esc(&open_url),
        title = esc(&plain_title),
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
        title = roche_core::document_meta::render_inline_math_html(&item.title),
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
                esc(&roche_core::document_meta::strip_inline_math_delimiters(&item.title)),
            );
        }
        out.push_str("</div>\n");
    }
    out.push_str("</div>\n</div>\n<div class=\"rank-controls\">\n");
    render_cluster_card_controls(out, idx, item, cluster_len, is_focus);
    out.push_str("</div>\n</div>\n</div>\n</li>\n");
}

fn render_cluster_card_controls(
    out: &mut String,
    idx: usize,
    item: &CatalogEntry,
    cluster_len: usize,
    is_focus: bool,
) {
    let rank_num = idx + 1;
    let plain_title = roche_core::document_meta::strip_inline_math_delimiters(&item.title);
    let _ = write!(
        out,
        "<noscript><select name=\"pos_{}\" class=\"js-noscript-pos\" aria-label=\"Rank position for {}\">",
        esc(&item.id),
        esc(&plain_title),
    );
    for p in 1..=cluster_len {
        let sel = if p == rank_num { " selected" } else { "" };
        let _ = write!(out, "<option value=\"{p}\"{sel}>#{p}</option>");
    }
    if !is_focus {
        out.push_str("<option value=\"exclude\">Exclude</option>");
    }
    out.push_str("</select></noscript>\n");

    let up_disabled = if idx == 0 { " disabled" } else { "" };
    let down_disabled = if idx + 1 == cluster_len { " disabled" } else { "" };
    let tie_disabled = if idx == 0 { " disabled" } else { "" };
    let _ = write!(
        out,
        "<button type=\"button\" class=\"btn btn-small btn-ghost js-card-up\"{up_disabled} title=\"Move Up (K)\" aria-label=\"Move {title} up\"><span aria-hidden=\"true\">&uarr;</span></button>\
         <button type=\"button\" class=\"btn btn-small btn-ghost js-card-down\"{down_disabled} title=\"Move Down (J)\" aria-label=\"Move {title} down\"><span aria-hidden=\"true\">&darr;</span></button>\
         <button type=\"button\" class=\"btn btn-small btn-ghost js-card-tie\"{tie_disabled} title=\"Tie with above (=)\" aria-label=\"Tie {title} with above\" aria-pressed=\"false\"><span aria-hidden=\"true\">=</span> Tie</button>",
        title = esc(&plain_title),
    );
    if !is_focus {
        let ex_disabled = if cluster_len <= MIN_K { " disabled" } else { "" };
        let _ = write!(
            out,
            "<button type=\"button\" class=\"btn btn-small btn-ghost js-card-exclude\"{ex_disabled} title=\"Exclude from this comparison (x)\" aria-label=\"Exclude {title} from comparison\"><span aria-hidden=\"true\">&times;</span></button>",
            title = esc(&plain_title),
        );
    }
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
    let max_rounds = find_val("max_rounds")
        .and_then(|v| v.parse::<usize>().ok())
        .filter(|&m| m > MAX_FOCUSED_ROUNDS && m <= MAX_CUSTOM_ROUNDS);
    let k_opt =
        find_val("k").and_then(|v| v.parse::<usize>().ok()).map(|v| v.clamp(MIN_K, MAX_K_DEFAULT));
    let k_param = k_opt.filter(|&v| v != MAX_K_DEFAULT);
    let prev_covered = find_val("covered").unwrap_or("");
    let prev_excluded = find_val("excluded").unwrap_or("");
    let round_excluded_raw = find_val("round_excluded").unwrap_or("");

    let ordering = parse_submitted_ordering(&pairs);
    let Some(groups) = ordering else {
        let base = build_rank_session_href(
            &slug,
            &RankHrefOpts {
                round: round_opt,
                want,
                focus,
                max_rounds,
                covered: (!prev_covered.is_empty()).then_some(prev_covered),
                excluded: (!prev_excluded.is_empty()).then_some(prev_excluded),
                k: k_param,
                ..RankHrefOpts::default()
            },
        );
        let mut resp = redirect_with_flash(&base, "err", "Invalid comparison ordering");
        if let Some(k_val) = k_opt {
            append_rank_k_set_cookie(&mut resp, k_val, session.secure_cookie);
        }
        return Ok(resp);
    };

    let (effective_want, scope_col_id) = if want {
        let col = service.catalog().get_or_create_want_collection(&slug).await.web_err(&session)?;
        (true, Some(col.id))
    } else {
        service.catalog().resolve_ranking_scope(&slug, false).await.web_err(&session)?
    };

    let comp = service
        .catalog()
        .append_comparison(scope_col_id.as_deref(), &groups, None)
        .await
        .web_err(&session)?;

    let submitted_ids: Vec<&str> =
        groups.iter().flat_map(|g| g.iter().map(String::as_str)).collect();
    let submitted_sig = cluster_signature_from_ids(&submitted_ids);
    let next_covered = append_covered_signature(Some(prev_covered), &submitted_sig);
    let submitted_tokens: HashSet<String> =
        submitted_ids.iter().map(|id| short_cluster_token(id)).collect();

    let mut newly_excluded: Vec<&str> =
        round_excluded_raw.split(',').map(str::trim).filter(|s| !s.is_empty()).collect();
    for (k, v) in &pairs {
        if let Some(eid) = k.strip_prefix("pos_")
            && v == "exclude"
            && !eid.trim().is_empty()
        {
            newly_excluded.push(eid);
        }
    }
    let next_excluded =
        merge_excluded_tokens(Some(prev_excluded), &newly_excluded, &submitted_tokens);
    let undo_excluded = (next_excluded != prev_excluded).then_some(prev_excluded);

    let mut resp = redirect_see_other(&build_rank_session_href(
        &slug,
        &RankHrefOpts {
            round: Some(round + 1),
            want: effective_want,
            focus,
            max_rounds,
            covered: Some(&next_covered),
            excluded: (!next_excluded.is_empty()).then_some(next_excluded.as_str()),
            k: k_param,
            undo_comp: Some(&comp.id),
            undo_excluded,
            ..RankHrefOpts::default()
        },
    ));
    if let Some(k_val) = k_opt {
        append_rank_k_set_cookie(&mut resp, k_val, session.secure_cookie);
    }
    Ok(resp)
}

pub(super) async fn post_comparison_undo(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<ComparisonUndoForm>,
) -> Result<Response, WebError> {
    let dest = safe_local_redirect(form.return_to.as_deref(), "/lists");
    let _ = service.catalog().delete_comparison(&id).await.web_err(&session)?;
    Ok(redirect_with_flash(&dest, "notice", "Undid last comparison"))
}

#[derive(Default)]
struct RankHrefOpts<'a> {
    round: Option<usize>,
    skip: Option<usize>,
    want: bool,
    focus: Option<&'a str>,
    max_rounds: Option<usize>,
    covered: Option<&'a str>,
    excluded: Option<&'a str>,
    k: Option<usize>,
    undo_comp: Option<&'a str>,
    undo_excluded: Option<&'a str>,
    random: Option<usize>,
    done: bool,
}

fn build_rank_session_href(slug: &str, opts: &RankHrefOpts<'_>) -> String {
    let mut query_parts = Vec::new();
    if let Some(r) = opts.round {
        query_parts.push(format!("round={r}"));
    }
    if let Some(s) = opts.skip {
        query_parts.push(format!("skip={s}"));
    }
    if opts.want {
        query_parts.push("want=true".to_owned());
    }
    if let Some(fid) = opts.focus {
        query_parts.push(format!("focus={}", urlencode(fid)));
    }
    if let Some(mr) = opts.max_rounds {
        query_parts.push(format!("max_rounds={mr}"));
    }
    if let Some(rnd) = opts.random {
        query_parts.push(format!("random={rnd}"));
    }
    if let Some(k_val) = opts.k {
        query_parts.push(format!("k={}", k_val.clamp(MIN_K, MAX_K_DEFAULT)));
    }
    if let Some(cov) = opts.covered.filter(|c| !c.is_empty()) {
        query_parts.push(format!("covered={}", urlencode(cov)));
    }
    if let Some(exc) = opts.excluded.filter(|e| !e.is_empty()) {
        query_parts.push(format!("excluded={}", urlencode(exc)));
    }
    if let Some(uc) = opts.undo_comp.filter(|u| !u.is_empty()) {
        query_parts.push(format!("undo_comp={}", urlencode(uc)));
    }
    if let Some(ue) = opts.undo_excluded {
        query_parts.push(format!("undo_excluded={}", urlencode(ue)));
    }
    if opts.done {
        query_parts.push("done=true".to_owned());
    }
    if query_parts.is_empty() {
        format!("/rank/{}", urlencode(slug))
    } else {
        format!("/rank/{}?{}", urlencode(slug), query_parts.join("&"))
    }
}

fn parse_submitted_ordering(pairs: &[(String, String)]) -> Option<Vec<Vec<String>>> {
    // Controls inside `<noscript>` are only submitted by browsers with scripting disabled,
    // so `pos_<id>` fields must take precedence over the default `ordering_json` hidden field
    // (and must never fall back to `ordering_json` if exclusions dropped the count below MIN_K).
    let mut has_pos_fields = false;
    let mut pos_entries: Vec<(usize, String)> = Vec::new();
    for (k, v) in pairs {
        if let Some(entry_id) = k.strip_prefix("pos_") {
            has_pos_fields = true;
            if let Ok(pos) = v.parse::<usize>()
                && !entry_id.trim().is_empty()
            {
                pos_entries.push((pos, entry_id.to_owned()));
            }
        }
    }

    if has_pos_fields {
        if pos_entries.len() < MIN_K {
            return None;
        }
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
        && parsed.iter().map(Vec::len).sum::<usize>() >= MIN_K
    {
        return Some(parsed);
    }

    None
}

fn short_cluster_token(id: &str) -> String {
    id.chars().take(8).collect()
}

fn cluster_signature(cluster: &[CatalogEntry]) -> String {
    let ids: Vec<&str> = cluster.iter().map(|c| c.id.as_str()).collect();
    cluster_signature_from_ids(&ids)
}

fn cluster_signature_from_ids(ids: &[&str]) -> String {
    let mut tokens: Vec<String> = ids.iter().map(|id| short_cluster_token(id)).collect();
    tokens.sort_unstable();
    tokens.dedup();
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
    if !sig.is_empty() && !list.contains(&sig) {
        list.push(sig);
    }
    if list.len() > MAX_COVERED_CLUSTERS {
        list.drain(0..(list.len() - MAX_COVERED_CLUSTERS));
    }
    list.join("|")
}

fn parse_excluded_tokens(raw: Option<&str>) -> HashSet<String> {
    raw.map(str::trim).filter(|s| !s.is_empty()).map_or_default(|s| {
        let mut list: Vec<&str> = s.split('.').filter(|p| !p.is_empty()).collect();
        if list.len() > MAX_EXCLUDED_TOKENS {
            list.drain(0..(list.len() - MAX_EXCLUDED_TOKENS));
        }
        list.into_iter().map(ToOwned::to_owned).collect()
    })
}

fn merge_excluded_tokens(
    existing: Option<&str>,
    newly_excluded_ids: &[&str],
    submitted_tokens: &HashSet<String>,
) -> String {
    let mut list: Vec<String> =
        existing.map(str::trim).filter(|s| !s.is_empty()).map_or_default(|s| {
            s.split('.')
                .filter(|p| !p.is_empty() && !submitted_tokens.contains(*p))
                .map(ToOwned::to_owned)
                .collect()
        });
    for &raw_id in newly_excluded_ids {
        let tok = short_cluster_token(raw_id);
        if !tok.is_empty() && !submitted_tokens.contains(&tok) && !list.contains(&tok) {
            list.push(tok);
        }
    }
    if list.len() > MAX_EXCLUDED_TOKENS {
        list.drain(0..(list.len() - MAX_EXCLUDED_TOKENS));
    }
    list.join(".")
}

fn is_cluster_covered(cluster: &[CatalogEntry], covered: &[HashSet<String>]) -> bool {
    if cluster.is_empty() || covered.is_empty() {
        return false;
    }
    let prefixes: Vec<String> = cluster.iter().map(|c| short_cluster_token(&c.id)).collect();
    covered.iter().any(|prev| prefixes.iter().all(|p| prev.contains(p)))
}
