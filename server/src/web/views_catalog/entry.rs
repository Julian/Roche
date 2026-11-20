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

use super::{EntryAddToListForm, EntryLogForm, EntryMergeForm, parse_consumption_status};
use crate::{
    db::{
        CatalogEntry, CollectionMode, CollectionRole, CollectionSummary, ConsumptionStatus,
        EntryDetailView, EntryKind,
    },
    error::ApiError,
    scheduler::FeedFetcher,
    service::RocheService,
    web::{
        artist_enrich::{
            AlbumTrack, extract_album_tracks, spawn_album_tracklist_enrichment_if_needed,
            spawn_artist_enrichment_if_needed,
        },
        auth::WebSession,
        html::{
            FlashQuery, NavSection, PageShell, esc, redirect_with_flash, render_page,
            safe_local_redirect, urlencode, web_api_error_response,
        },
        links::{
            WebLinkIcon, collect_web_external_links, extract_entry_tags,
            load_artist_id_by_lower_name, render_creator_links, sanitize_non_local_href,
        },
    },
};

const CLOCK_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"8\" cy=\"8\" r=\"6\"/><path d=\"M8 4.5v3.75l2.5 1.5\"/></svg>";

struct EntryListAppearance {
    slug: String,
    title: String,
    rank: usize,
    total: usize,
    tier: Option<char>,
}

struct EntryParentListLink {
    slug: String,
    title: String,
    has_generator: bool,
}

struct EntryRenderCtx<'a> {
    detail: &'a EntryDetailView,
    tracks: &'a [AlbumTrack],
    appearances: &'a [EntryListAppearance],
    parent_lists: &'a [EntryParentListLink],
    manual_lists: &'a [CollectionSummary],
    artist_links: &'a HashMap<String, String>,
    child_tiers: &'a HashMap<String, char>,
    has_reader_item: bool,
    csrf: &'a str,
}

pub(in crate::web) async fn entry_detail_get<F: FeedFetcher>(
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
    spawn_artist_enrichment_if_needed(&service, &detail);
    spawn_album_tracklist_enrichment_if_needed(&service, &detail);

    let mut relevant_creators: Vec<String> = detail.entry.creators.clone();
    relevant_creators.extend(detail.children.iter().flat_map(|c| c.creators.iter().cloned()));
    relevant_creators.extend(detail.parents.iter().flat_map(|p| p.creators.iter().cloned()));

    let (artist_links, appearances, all_lists, has_reader_item, child_tiers) = tokio::join!(
        load_artist_id_by_lower_name(&service, &relevant_creators),
        load_entry_ranked_appearances(&service, &detail),
        async { service.catalog().list_collections(false, false).await },
        async { service.items().get(&detail.entry.id).await.is_ok() },
        load_child_entry_tiers(&service, &detail.children),
    );
    let all_lists = all_lists.unwrap_or_default();
    let list_by_id: HashMap<&str, &CollectionSummary> =
        all_lists.iter().map(|c| (c.id.as_str(), c)).collect();
    let parent_lists: Vec<EntryParentListLink> = detail
        .parents
        .iter()
        .filter(|p| p.kind == EntryKind::Collection)
        .filter_map(|p| {
            let col = list_by_id.get(p.id.as_str())?;
            if !matches!(col.role, CollectionRole::List | CollectionRole::System) {
                return None;
            }
            Some(EntryParentListLink {
                slug: col.slug.clone().unwrap_or_else(|| col.id.clone()),
                title: col.title.clone(),
                has_generator: col.has_generator,
            })
        })
        .collect();
    let manual_lists: Vec<CollectionSummary> = all_lists
        .into_iter()
        .filter(|c| {
            c.mode == CollectionMode::Manual && !c.has_generator && c.role == CollectionRole::List
        })
        .collect();
    let tracks = extract_album_tracks(&detail);

    let ctx = EntryRenderCtx {
        detail: &detail,
        tracks: &tracks,
        appearances: &appearances,
        parent_lists: &parent_lists,
        manual_lists: &manual_lists,
        artist_links: &artist_links,
        child_tiers: &child_tiers,
        has_reader_item,
        csrf: &session.csrf_token,
    };
    let mut body = String::with_capacity(10_240);
    render_entry_detail_body(&mut body, &ctx);

    render_page(&PageShell {
        title: &detail.entry.title,
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &flash,
        is_refreshing: service.is_refreshing(),
        body_html: &body,
    })
    .into_response()
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
    let mut seen_col_ids = HashSet::new();
    for key in candidate_slugs {
        // Only show genuine global Lists (`List` / `System`), never `Want`, `Tag`, or `Feed` collections.
        if let Ok(ranked) = service.catalog().get_ranked_collection(&key, false).await
            && matches!(ranked.collection.role, CollectionRole::List | CollectionRole::System)
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

async fn load_child_entry_tiers<F: FeedFetcher>(
    service: &RocheService<F>,
    children: &[CatalogEntry],
) -> HashMap<String, char> {
    let mut slugs: Vec<&'static str> = children
        .iter()
        .filter(|c| c.kind != EntryKind::Song)
        .filter_map(|c| c.kind.default_list_slug())
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

fn render_entry_detail_body(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    out.push_str("<div class=\"entry-grid\">\n<div class=\"entry-main\">\n");
    render_entry_crumb(out, &ctx.detail.entry);
    render_entry_hero(out, ctx);
    render_entry_action_bar(out, &ctx.detail.entry, ctx.has_reader_item);
    if !ctx.tracks.is_empty() {
        render_album_tracklist(out, &ctx.detail.entry, ctx.tracks);
    }
    render_entry_discography_or_children(out, ctx);
    out.push_str("</div>\n");
    render_entry_rail(out, ctx);
    out.push_str("</div>\n");
}

const fn entry_kind_list_label(kind: EntryKind) -> &'static str {
    match kind {
        EntryKind::Article => "Articles",
        EntryKind::Paper => "Papers",
        EntryKind::Video => "Videos",
        EntryKind::PodcastEpisode => "Podcast Episodes",
        EntryKind::Song => "Songs",
        EntryKind::Movie => "Movies",
        EntryKind::Book => "Books",
        EntryKind::Game => "Games",
        EntryKind::Album => "Albums",
        EntryKind::Tv => "TV",
        EntryKind::Artist => "Artists",
        EntryKind::Podcast => "Podcasts",
        EntryKind::Publication => "Publications",
        EntryKind::Channel => "Channels",
        EntryKind::Collection => "All Lists",
    }
}

fn render_entry_crumb(out: &mut String, entry: &CatalogEntry) {
    let (href, label) = entry.kind.default_list_slug().map_or_else(
        || ("/lists".to_owned(), "All Lists"),
        |slug| (format!("/lists/{}", urlencode(slug)), entry_kind_list_label(entry.kind)),
    );
    let _ = writeln!(
        out,
        "<div class=\"list-detail-crumb\"><a class=\"reader-back-link\" href=\"{}\">&larr; {}</a></div>",
        esc(&href),
        esc(label),
    );
}

fn render_entry_hero(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    let entry = &ctx.detail.entry;
    let artwork_url = entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href);
    if let Some(thumb) = artwork_url {
        let art_cls = if entry.kind == EntryKind::Artist {
            "entry-artwork entry-artwork-portrait"
        } else {
            "entry-artwork"
        };
        let _ = write!(
            out,
            "<div class=\"entry-hero\">\n\
               <img class=\"{art_cls}\" src=\"{}\" alt=\"{}\" loading=\"lazy\" referrerpolicy=\"no-referrer\">\n\
               <div class=\"entry-hero-main\">\n",
            esc(thumb),
            esc(&entry.title),
        );
    } else {
        out.push_str(
            "<div class=\"entry-hero entry-hero-text\">\n<div class=\"entry-hero-main\">\n",
        );
    }

    let kicker = if entry.kind == EntryKind::Artist {
        let album_cnt = ctx.detail.children.iter().filter(|c| c.kind == EntryKind::Album).count();
        if album_cnt > 0 {
            let word = if album_cnt == 1 { "album" } else { "albums" };
            format!("Artist &middot; {album_cnt} {word} in library")
        } else {
            "Artist".to_owned()
        }
    } else {
        esc(entry.kind.display_label()).to_string()
    };
    let _ = writeln!(out, "<p class=\"entry-kicker\">{kicker}</p>\n<h2>{}</h2>", esc(&entry.title));

    render_entry_meta_line(out, entry, ctx.tracks, ctx.artist_links);

    if let Some(sum) = entry.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let _ = writeln!(out, "<p class=\"entry-summary\">{}</p>", esc(sum));
    }

    let genres = extract_entry_tags(entry);
    if !genres.is_empty() {
        let _ = writeln!(
            out,
            "<dl class=\"facts\"><dt>Genres</dt><dd>{}</dd></dl>",
            esc(&genres.join(" · ")),
        );
    }

    out.push_str("</div>\n</div>\n");
}

fn render_entry_meta_line(
    out: &mut String,
    entry: &CatalogEntry,
    tracks: &[AlbumTrack],
    artist_links: &HashMap<String, String>,
) {
    let has_meta = !entry.creators.is_empty() || entry.release_year.is_some() || !tracks.is_empty();
    if !has_meta {
        return;
    }
    out.push_str("<p class=\"entry-meta-line\">");
    if !entry.creators.is_empty() {
        out.push_str("<span class=\"byline-item\">");
        render_creator_links(out, &entry.creators, artist_links, false);
        out.push_str("</span>");
    }
    if let Some(yr) = entry.release_year {
        let _ = write!(out, "<span class=\"byline-item\">{yr}</span>");
    }
    if !tracks.is_empty() {
        let song_word = if tracks.len() == 1 { "song" } else { "songs" };
        let total_ms: u64 = tracks.iter().filter_map(|t| t.duration_ms).sum();
        if total_ms > 0 {
            let _ = write!(
                out,
                "<span class=\"byline-item\">{} {song_word}, {}</span>",
                tracks.len(),
                format_total_duration(total_ms),
            );
        } else {
            let _ = write!(out, "<span class=\"byline-item\">{} {song_word}</span>", tracks.len());
        }
    }
    out.push_str("</p>\n");
}

fn render_entry_action_bar(out: &mut String, entry: &CatalogEntry, has_reader_item: bool) {
    let links = collect_web_external_links(entry, Some("Open Source"));
    if !has_reader_item && links.is_empty() {
        return;
    }
    out.push_str("<div class=\"entry-action-bar\">\n");
    if has_reader_item {
        let _ = writeln!(
            out,
            "<a class=\"btn btn-primary btn-small\" href=\"/reader/{}\">Open in Reader &rarr;</a>",
            esc(&urlencode(&entry.id)),
        );
    }
    if !links.is_empty() {
        out.push_str("<ul class=\"external-links\">\n");
        for link in links {
            let cls =
                if link.icon == WebLinkIcon::Spotify { " class=\"ext-link-spotify\"" } else { "" };
            let _ = writeln!(
                out,
                "<li><a{cls} href=\"{}\" target=\"_blank\" rel=\"noopener\">{}<span>{}</span></a></li>",
                esc(&link.href),
                link.icon.svg(),
                esc(&link.label),
            );
        }
        out.push_str("</ul>\n");
    }
    out.push_str("</div>\n");
}

fn render_album_tracklist(out: &mut String, album: &CatalogEntry, tracks: &[AlbumTrack]) {
    let max_disc = tracks.iter().map(|t| t.disc).max().unwrap_or(1);
    let album_spotify = album.identifier("spotify").and_then(|raw| {
        let id = raw.strip_prefix("spotify:album:")?;
        Some(format!("https://open.spotify.com/album/{id}"))
    });

    let _ = write!(
        out,
        "<section class=\"tracklist-wrap\" aria-label=\"Tracklist\">\n\
           <table class=\"tracklist\">\n\
             <thead>\n\
               <tr>\n\
                 <th scope=\"col\" class=\"track-num-col\">#</th>\n\
                 <th scope=\"col\">Title</th>\n\
                 <th scope=\"col\" class=\"track-dur-col\" aria-label=\"Duration\">{CLOCK_SVG}</th>\n\
               </tr>\n\
             </thead>\n\
             <tbody>\n",
    );

    let mut cur_disc = 0u32;
    for track in tracks {
        if max_disc > 1 && track.disc != cur_disc {
            cur_disc = track.disc;
            let _ = writeln!(
                out,
                "<tr class=\"tracklist-disc-row\"><td colspan=\"3\">Disc {cur_disc}</td></tr>",
            );
        }
        let track_href = track
            .entry_id
            .as_deref()
            .map(|id| (format!("/entries/{}", urlencode(id)), false))
            .or_else(|| track.spotify_web_url().map(|u| (u, true)))
            .or_else(|| album_spotify.clone().map(|u| (u, true)));
        let _ = write!(
            out,
            "<tr><td class=\"track-num\">{}</td><td class=\"track-title-cell\">",
            track.number,
        );
        if let Some((href, external)) = track_href {
            let ext_attrs = if external { " target=\"_blank\" rel=\"noopener\"" } else { "" };
            let _ = write!(
                out,
                "<a class=\"track-title\" href=\"{}\"{ext_attrs}>{}</a>",
                esc(&href),
                esc(&track.title),
            );
        } else {
            let _ = write!(out, "<span class=\"track-title\">{}</span>", esc(&track.title));
        }

        let artist_display = if !track.artists.is_empty() {
            Some(track.artists.join(", "))
        } else if !album.creators.is_empty() {
            Some(album.creators.join(", "))
        } else {
            None
        };
        if let Some(artist_str) = artist_display {
            let _ = write!(out, "<span class=\"track-artists\">{}</span>", esc(&artist_str));
        }

        let dur_txt = track.duration_ms.map_or_else(|| "—".to_owned(), format_track_duration);
        let _ = writeln!(out, "</td><td class=\"track-dur\">{dur_txt}</td></tr>");
    }
    out.push_str("</tbody>\n</table>\n");

    let song_word = if tracks.len() == 1 { "song" } else { "songs" };
    let total_ms: u64 = tracks.iter().filter_map(|t| t.duration_ms).sum();
    let mut foot_parts = vec![format!("{} {song_word}", tracks.len())];
    if total_ms > 0 {
        foot_parts.push(format_total_duration(total_ms));
    }
    if let Some(yr) = album.release_year {
        foot_parts.push(format!("Released {yr}"));
    }
    let _ = writeln!(
        out,
        "<p class=\"tracklist-foot\">{}</p>\n</section>",
        foot_parts.join(" &middot; ")
    );
}

fn render_entry_discography_or_children(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    let mut children: Vec<&CatalogEntry> =
        ctx.detail.children.iter().filter(|c| c.kind != EntryKind::Song).collect();
    if children.is_empty() {
        return;
    }

    if ctx.detail.entry.kind == EntryKind::Artist {
        children.sort_by(|a, b| {
            b.release_year
                .cmp(&a.release_year)
                .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
        });
    }

    let heading =
        if ctx.detail.entry.kind == EntryKind::Artist { "Discography" } else { "Contains" };
    let _ = writeln!(
        out,
        "<section class=\"entry-section\" aria-label=\"{heading}\">\n\
           <div class=\"section-head\"><h3>{heading}</h3><span class=\"section-action\">{}</span></div>\n\
           <ul class=\"discography-grid\">",
        children.len(),
    );

    for child in children.into_iter().take(100) {
        let href = format!("/entries/{}", urlencode(&child.id));
        let cover_html = child
            .thumbnail_url
            .as_deref()
            .and_then(sanitize_non_local_href)
            .map_or_else(
                || {
                    let initial = child
                        .title
                        .chars()
                        .find(|c| c.is_alphanumeric())
                        .map_or('?', |c| c.to_ascii_uppercase());
                    format!("<span class=\"discography-cover-fallback\" aria-hidden=\"true\">{initial}</span>")
                },
                |thumb| {
                    format!(
                        "<img class=\"discography-cover\" src=\"{}\" alt=\"\" loading=\"lazy\" referrerpolicy=\"no-referrer\">",
                        esc(thumb),
                    )
                },
            );
        let meta_left = child.release_year.map_or_else(
            || esc(child.kind.display_label()).to_string(),
            |yr| format!("{yr} &middot; {}", esc(child.kind.display_label())),
        );
        let tier_html = ctx.child_tiers.get(&child.id).copied().map_or_else(String::new, |t| {
            format!("<span class=\"discography-tier tier-{t}\" title=\"Tier {t}\">{t}</span>")
        });
        let _ = writeln!(
            out,
            "<li><a class=\"discography-card\" href=\"{}\">\
               <div class=\"discography-cover-wrap\">{cover_html}</div>\
               <span class=\"discography-title\">{}</span>\
               <span class=\"discography-meta\"><span>{meta_left}</span>{tier_html}</span>\
             </a></li>",
            esc(&href),
            esc(&child.title),
        );
    }
    out.push_str("</ul>\n</section>\n");
}

fn render_entry_rail(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    out.push_str("<div class=\"entry-rail\">\n");
    if !ctx.appearances.is_empty() {
        render_rail_rankings_card(out, &ctx.detail.entry.id, ctx.appearances);
    }
    render_rail_diary_card(out, ctx.detail, ctx.csrf);
    render_rail_lists_card(out, &ctx.detail.entry.id, ctx.parent_lists, ctx.manual_lists, ctx.csrf);
    render_rail_merge_card(out, &ctx.detail.entry.id, ctx.csrf);
    out.push_str("</div>\n");
}

fn render_rail_rankings_card(
    out: &mut String,
    entry_id: &str,
    appearances: &[EntryListAppearance],
) {
    out.push_str(
        "<section class=\"rail-block rail-card\" aria-label=\"Rankings\">\n\
           <div class=\"section-head\"><h3>Rankings</h3></div>\n\
           <ul class=\"entry-rank-list entry-rank-list-compact\">\n",
    );
    let entry_enc = urlencode(entry_id);
    for app in appearances {
        let slug_enc = urlencode(&app.slug);
        let tier_pill = app.tier.map_or_else(
            || "<span class=\"entry-tier-pill\" aria-hidden=\"true\">&middot;</span>".to_owned(),
            |t| format!("<span class=\"entry-tier-pill tier-{t}\" title=\"Tier {t}\">{t}</span>"),
        );
        let rank_btn = if app.total >= 2 {
            format!(
                "<a class=\"btn btn-small\" href=\"/rank/{slug_enc}?focus={entry_enc}\">Rank</a>"
            )
        } else {
            String::new()
        };
        let _ = writeln!(
            out,
            "<li class=\"entry-rank-row\">\
               <div class=\"entry-rank-left\">\
                 {tier_pill}\
                 <div>\
                   <a class=\"entry-rank-list-name\" href=\"/lists/{slug_enc}\">{title}</a>\
                   <div class=\"entry-rank-pos\"><strong>#{rank}</strong> of {total}</div>\
                 </div>\
               </div>\
               {rank_btn}\
             </li>",
            title = esc(&app.title),
            rank = app.rank,
            total = app.total,
        );
    }
    out.push_str("</ul>\n</section>\n");
}

fn render_rail_diary_card(out: &mut String, detail: &EntryDetailView, csrf: &str) {
    let is_music = matches!(detail.entry.kind, EntryKind::Album | EntryKind::Song);
    let count = detail.log_history.len();
    let count_badge = if count == 0 {
        String::new()
    } else if is_music {
        let word = if count == 1 { "play" } else { "plays" };
        format!("<span class=\"section-action\">{count} {word}</span>")
    } else {
        let word = if count == 1 { "entry" } else { "entries" };
        format!("<span class=\"section-action\">{count} {word}</span>")
    };

    let _ = writeln!(
        out,
        "<section class=\"rail-block rail-card\" aria-label=\"Diary\">\n\
           <div class=\"section-head\"><h3>Diary</h3>{count_badge}</div>",
    );
    if detail.log_history.is_empty() {
        out.push_str("<p class=\"rail-note\">Not logged in Diary yet.</p>\n");
    } else {
        out.push_str("<ul class=\"entry-history-list\">\n");
        for log in &detail.log_history {
            let status_label = match log.status {
                ConsumptionStatus::Consumed => "Finished",
                ConsumptionStatus::Started => "Started",
                ConsumptionStatus::Abandoned => "Skipped",
            };
            let _ = write!(
                out,
                "<li class=\"entry-history-row\">\
                   <span class=\"entry-history-date\">{}</span>\
                   <span class=\"badge\">{status_label}</span>",
                log.ts.format("%b %-d, %Y"),
            );
            if let Some(notes) = log.notes.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
                let _ = write!(out, "<span class=\"entry-history-note\">{}</span>", esc(notes));
            }
            out.push_str("</li>\n");
        }
        out.push_str("</ul>\n");
    }

    let id_enc = urlencode(&detail.entry.id);
    let subhead = if is_music { "Log a play" } else { "Log activity" };
    let placeholder = if is_music { "Thoughts, standout tracks…" } else { "Optional note…" };
    let _ = write!(
        out,
        "<div class=\"rail-subhead\">{subhead}</div>\n\
         <form method=\"post\" action=\"/web/entries/{id_enc}/log\" class=\"rail-form\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <div class=\"field\">\n\
             <label for=\"log-status\">Status</label>\n\
             <select id=\"log-status\" name=\"status\">\n\
               <option value=\"consumed\">Finished</option>\n\
               <option value=\"started\">Started</option>\n\
               <option value=\"abandoned\">Skipped</option>\n\
             </select>\n\
           </div>\n\
           <div class=\"field\">\n\
             <label for=\"log-notes\">Notes (optional)</label>\n\
             <textarea id=\"log-notes\" name=\"notes\" class=\"textarea-compact\" placeholder=\"{placeholder}\"></textarea>\n\
           </div>\n\
           <div class=\"field field-check\">\n\
             <input id=\"log-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked>\n\
             <label for=\"log-rank\">Rank after logging</label>\n\
           </div>\n\
           <button type=\"submit\" class=\"btn btn-primary btn-small\">Save Log</button>\n\
         </form>\n\
       </section>\n",
        id_enc = esc(&id_enc),
        csrf = esc(csrf),
    );
}

fn render_rail_lists_card(
    out: &mut String,
    entry_id: &str,
    parent_lists: &[EntryParentListLink],
    manual_lists: &[CollectionSummary],
    csrf: &str,
) {
    if parent_lists.is_empty() && manual_lists.is_empty() {
        return;
    }
    out.push_str(
        "<section class=\"rail-block rail-card\" aria-label=\"Lists\">\n\
           <div class=\"section-head\"><h3>Lists</h3></div>\n",
    );
    if !parent_lists.is_empty() {
        out.push_str("<ul class=\"rail-list-links\">\n");
        for parent in parent_lists {
            let draw_badge =
                if parent.has_generator { "<span class=\"badge\">Draw</span>" } else { "" };
            let _ = writeln!(
                out,
                "<li><a href=\"/lists/{}\">{}</a>{draw_badge}</li>",
                esc(&urlencode(&parent.slug)),
                esc(&parent.title),
            );
        }
        out.push_str("</ul>\n");
    }
    if !manual_lists.is_empty() {
        let id_enc = urlencode(entry_id);
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/entries/{}/list-add\" class=\"rail-inline-add\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\n\
               <select name=\"list_slug_or_id\" aria-label=\"Target custom list\">",
            esc(&id_enc),
            esc(csrf),
        );
        for col in manual_lists {
            let key = col.slug.as_deref().unwrap_or(&col.id);
            let _ = write!(out, "<option value=\"{}\">{}</option>", esc(key), esc(&col.title));
        }
        out.push_str(
            "</select>\n<button type=\"submit\" class=\"btn btn-small\">Add</button>\n</form>\n",
        );
    }
    out.push_str("</section>\n");
}

fn render_rail_merge_card(out: &mut String, entry_id: &str, csrf: &str) {
    let id_enc = urlencode(entry_id);
    let _ = write!(
        out,
        "<section class=\"rail-block rail-card\">\n\
           <details class=\"rail-details\">\n\
             <summary>Merge Duplicate&hellip;</summary>\n\
             <form method=\"post\" action=\"/web/entries/{id_enc}/merge\" class=\"rail-form\" data-confirm=\"Merge this duplicate into the target canonical entry? This action cannot be undone.\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
               <p class=\"rail-note\">Folds identifiers, list memberships, diary logs, and comparisons from this duplicate into the target canonical entry.</p>\n\
               <div class=\"field\">\n\
                 <input name=\"canonical_query\" type=\"text\" required aria-label=\"Canonical entry UUID, URL, or exact title\" placeholder=\"Canonical UUID, URL, or exact title\">\n\
               </div>\n\
               <button type=\"submit\" class=\"btn btn-small\">Merge Duplicate</button>\n\
             </form>\n\
           </details>\n\
         </section>\n",
        id_enc = esc(&id_enc),
        csrf = esc(csrf),
    );
}

fn format_track_duration(ms: u64) -> String {
    let total_secs = (ms + 500) / 1_000;
    let mins = total_secs / 60;
    let secs = total_secs % 60;
    format!("{mins}:{secs:02}")
}

fn format_total_duration(ms: u64) -> String {
    let total_secs = (ms + 500) / 1_000;
    let hours = total_secs / 3_600;
    let mins = (total_secs % 3_600) / 60;
    let secs = total_secs % 60;
    if hours > 0 {
        format!("{hours} hr {mins} min")
    } else if mins > 0 {
        format!("{mins} min {secs} sec")
    } else {
        format!("{secs} sec")
    }
}

pub(in crate::web) async fn post_entry_log<F: FeedFetcher>(
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
                && let Ok(Some(slug)) =
                    service.catalog().rank_target_after_consumption(&logged.entry).await
            {
                redirect_with_flash(
                    &format!("/rank/{}?focus={}", urlencode(&slug), urlencode(&logged.entry.id)),
                    "notice",
                    "Logged to Diary",
                )
            } else {
                redirect_with_flash(
                    &format!("/entries/{}", urlencode(&logged.entry.id)),
                    "notice",
                    "Logged to Diary",
                )
            }
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_entry_list_add<F: FeedFetcher>(
    State(service): State<Arc<RocheService<F>>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryAddToListForm>,
) -> Response {
    match service.catalog().add_collection_entry(&form.list_slug_or_id, &id, None).await {
        Ok(()) => {
            let fallback = format!("/entries/{}", urlencode(&id));
            let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
            let msg = if form.list_slug_or_id == crate::db::WANT_TO_READ_COLLECTION_ID {
                "Added to Queue"
            } else {
                "Added to list"
            };
            redirect_with_flash(&dest, "notice", msg)
        }
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}

pub(in crate::web) async fn post_entry_merge<F: FeedFetcher>(
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
        Ok(canon) => redirect_with_flash(
            &format!("/entries/{}", urlencode(&canon.id)),
            "notice",
            "Merged duplicate into canonical entry",
        ),
        Err(err) => web_api_error_response(&ApiError::from(err), Some(&session.csrf_token)),
    }
}
