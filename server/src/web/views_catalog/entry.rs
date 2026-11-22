use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    sync::Arc,
};

use axum::{
    Form, Json,
    extract::{Extension, Path, Query, State},
    response::{IntoResponse, Response},
};

use super::{
    AddArtistAlbumForm, EntryAddToListForm, EntryDetailQuery, EntryLogForm, EntryMergeForm,
    EntryQueueToggleForm, parse_consumption_status, parse_optional_year,
};
use crate::{
    db::{
        CatalogEntry, CollectionSummary, EntryDetailView, EntryIdentifier, UpsertEntryInput,
        WANT_TO_READ_SLUG,
    },
    domain::{CollectionMode, CollectionRole, ConsumptionStatus, EntryKind},
    service::{
        AppError, EntryListAppearance, RocheService,
        enrich::{
            AlbumTrack, DiscographyAlbum, extract_album_tracks, extract_artist_discography,
            normalize_album_match_key,
        },
    },
    web::{
        auth::WebSession,
        html::{
            NavSection, PageShell, WebError, WithWebCsrf as _, consumption_status_label, esc,
            parse_form_bool, redirect_with_flash, render_inline_csrf_form,
            render_inline_csrf_form_with_class, render_page, safe_local_redirect,
            safe_web_error_message, urlencode,
        },
        links::{
            WebLinkIcon, collect_web_external_links, extract_entry_tags,
            load_artist_id_by_lower_name, render_creator_links, sanitize_non_local_href,
        },
    },
};

const CLOCK_SVG: &str = "<svg class=\"icon\" viewBox=\"0 0 16 16\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"8\" cy=\"8\" r=\"6\"/><path d=\"M8 4.5v3.75l2.5 1.5\"/></svg>";

struct EntryParentListLink {
    id: String,
    slug: String,
    title: String,
    has_generator: bool,
    is_editable_manual: bool,
}

enum MergePreviewState {
    Ready(Box<EntryDetailView>),
    Error(String),
}

struct EntryRenderCtx<'a> {
    detail: &'a EntryDetailView,
    tracks: &'a [AlbumTrack],
    appearances: &'a [EntryListAppearance],
    parent_lists: &'a [EntryParentListLink],
    available_manual_lists: &'a [CollectionSummary],
    want_queue_slug: Option<&'a str>,
    needs_enrich: bool,
    merge_preview_query: Option<&'a str>,
    merge_preview: Option<&'a MergePreviewState>,
    artist_links: &'a HashMap<String, String>,
    child_tiers: &'a HashMap<String, char>,
    has_reader_item: bool,
    csrf: &'a str,
}

pub(in crate::web) async fn entry_detail_get(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Query(query): Query<EntryDetailQuery>,
) -> Result<Response, WebError> {
    let detail =
        service.catalog().get_entry_detail(&id).await.web_err(&session)?.ok_or_else(|| {
            WebError::from_app(
                AppError::not_found(format!("Entry '{id}' not found")),
                Some(&session.csrf_token),
                session.theme,
            )
        })?;
    let needs_enrich = service.enrich().needs_enrichment(&detail);

    let mut relevant_creators: Vec<String> = detail.entry.creators.clone();
    relevant_creators.extend(detail.children.iter().flat_map(|c| c.creators.iter().cloned()));
    relevant_creators.extend(detail.parents.iter().flat_map(|p| p.creators.iter().cloned()));
    let catalog = service.catalog();
    let items = service.items();
    let (artist_links, appearances, collections_res, reader_ids_res, child_tiers) = tokio::join!(
        load_artist_id_by_lower_name(&service, &relevant_creators),
        catalog.load_entry_ranked_appearances(&detail),
        catalog.list_collections(true, false),
        items.existing_ids(std::slice::from_ref(&detail.entry.id)),
        load_child_entry_tiers(&service, &detail.children),
    );
    let all_lists = collections_res.unwrap_or_default();
    let list_by_id: HashMap<&str, &CollectionSummary> =
        all_lists.iter().map(|c| (c.id.as_str(), c)).collect();

    let want_queue_slug: Option<String> =
        detail.parents.iter().filter(|p| p.kind == EntryKind::Collection).find_map(|p| {
            let col = list_by_id.get(p.id.as_str())?;
            (col.role == CollectionRole::Want)
                .then(|| col.slug.clone().unwrap_or_else(|| col.id.clone()))
        });

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
                id: col.id.clone(),
                slug: col.slug.clone().unwrap_or_else(|| col.id.clone()),
                title: col.title.clone(),
                has_generator: col.has_generator,
                is_editable_manual: col.mode == CollectionMode::Manual
                    && !col.has_generator
                    && col.role == CollectionRole::List,
            })
        })
        .collect();
    let joined_list_ids: HashSet<&str> = parent_lists.iter().map(|p| p.id.as_str()).collect();
    let available_manual_lists: Vec<CollectionSummary> = all_lists
        .iter()
        .filter(|c| {
            c.mode == CollectionMode::Manual
                && !c.has_generator
                && c.role == CollectionRole::List
                && !joined_list_ids.contains(c.id.as_str())
        })
        .cloned()
        .collect();

    let merge_preview_query =
        query.merge_preview.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let merge_preview = load_merge_preview(&service, &detail.entry.id, merge_preview_query).await;

    let has_reader_item = reader_ids_res.is_ok_and(|ids| ids.contains(&detail.entry.id));
    let tracks = extract_album_tracks(&detail);

    let ctx = EntryRenderCtx {
        detail: &detail,
        tracks: &tracks,
        appearances: &appearances,
        parent_lists: &parent_lists,
        available_manual_lists: &available_manual_lists,
        want_queue_slug: want_queue_slug.as_deref(),
        needs_enrich,
        merge_preview_query,
        merge_preview: merge_preview.as_ref(),
        artist_links: &artist_links,
        child_tiers: &child_tiers,
        has_reader_item,
        csrf: &session.csrf_token,
    };
    let mut body = String::with_capacity(10_240);
    render_entry_detail_body(&mut body, &ctx);

    Ok(render_page(&PageShell {
        title: &detail.entry.title,
        active: NavSection::Lists,
        csrf_token: &session.csrf_token,
        flash: &session.flash,
        is_refreshing: service.is_refreshing(),
        theme: session.theme,
        search_query: None,
        body_html: &body,
    })
    .into_response())
}

async fn load_merge_preview(
    service: &RocheService,
    source_id: &str,
    merge_preview_query: Option<&str>,
) -> Option<MergePreviewState> {
    let raw_target = merge_preview_query?;
    Some(match service.catalog().resolve_entry(raw_target, None, None).await {
        Ok(target) if target.id == source_id => {
            MergePreviewState::Error("Cannot merge an entry into itself.".to_owned())
        }
        Ok(target) => match service.catalog().get_entry_detail(&target.id).await {
            Ok(Some(target_detail)) => MergePreviewState::Ready(Box::new(target_detail)),
            _ => MergePreviewState::Error(format!(
                "Could not load canonical target '{}'.",
                target.title
            )),
        },
        Err(err) => {
            let safe_msg = safe_web_error_message(&err);
            MergePreviewState::Error(format!("Could not find target '{raw_target}': {safe_msg}"))
        }
    })
}

async fn load_child_entry_tiers(
    service: &RocheService,
    children: &[CatalogEntry],
) -> HashMap<String, char> {
    service.catalog().load_tiers_for_entries(children).await
}

fn render_entry_detail_body(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    out.push_str("<div class=\"entry-grid\">\n<div class=\"entry-main\">\n");
    render_entry_crumb(out, &ctx.detail.entry);
    if ctx.needs_enrich {
        let msg = if ctx.detail.entry.kind == EntryKind::Artist {
            "Fetching artist bio &amp; discography…"
        } else {
            "Fetching album tracklist…"
        };
        let _ = writeln!(
            out,
            "<div class=\"entry-enrich-banner js-entry-enrich\" data-entry-id=\"{}\" role=\"status\">{msg}</div>",
            esc(&ctx.detail.entry.id),
        );
    }
    render_entry_hero(out, ctx);
    render_entry_action_bar(
        out,
        &ctx.detail.entry,
        ctx.has_reader_item,
        ctx.want_queue_slug,
        ctx.csrf,
    );
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

    let _ = writeln!(
        out,
        "<p class=\"entry-kicker\">{}</p>\n<h2>{}</h2>",
        esc(entry.kind.display_label()),
        esc(&entry.title),
    );

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

fn render_entry_action_bar(
    out: &mut String,
    entry: &CatalogEntry,
    has_reader_item: bool,
    want_queue_slug: Option<&str>,
    csrf: &str,
) {
    let links = collect_web_external_links(entry, Some("Open Source"));
    let can_queue = entry.kind.default_want_slug().is_some();
    if !has_reader_item && !can_queue && links.is_empty() {
        return;
    }
    let id_enc = urlencode(&entry.id);
    let return_to = format!("/entries/{id_enc}");
    out.push_str("<div class=\"entry-action-bar\">\n");
    if has_reader_item {
        let _ = writeln!(
            out,
            "<a class=\"btn btn-primary btn-small\" href=\"/reader/{id_enc}?return_to={}\">Open in Reader &rarr;</a>",
            esc(&urlencode(&return_to)),
        );
    }
    if can_queue {
        let toggle_action = format!("/web/entries/{id_enc}/queue-toggle");
        if let Some(q_slug) = want_queue_slug {
            render_inline_csrf_form(
                out,
                &toggle_action,
                csrf,
                &[("in_queue", "false"), ("queue_slug", q_slug), ("return_to", &return_to)],
                None,
                "<button type=\"submit\" class=\"btn btn-small btn-ghost is-in-queue\" title=\"Remove from Queue\">&#10003; In Queue</button>",
            );
        } else {
            let default_q = entry.kind.default_want_slug().unwrap_or(WANT_TO_READ_SLUG);
            render_inline_csrf_form(
                out,
                &toggle_action,
                csrf,
                &[("in_queue", "true"), ("queue_slug", default_q), ("return_to", &return_to)],
                None,
                "<button type=\"submit\" class=\"btn btn-small btn-ghost\" title=\"Add to Queue\">+ Add to Queue</button>",
            );
        }
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
            let ext_mark = if external {
                " <span class=\"track-ext-mark\" aria-hidden=\"true\">&#8599;</span>"
            } else {
                ""
            };
            let _ = write!(
                out,
                "<a class=\"track-title\" href=\"{}\"{ext_attrs}>{}{ext_mark}</a>",
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

enum DiscographyCardItem<'a> {
    InLibrary(&'a CatalogEntry),
    External(DiscographyAlbum),
}

impl DiscographyCardItem<'_> {
    const fn release_year(&self) -> Option<i32> {
        match self {
            Self::InLibrary(c) => c.release_year,
            Self::External(e) => e.release_year,
        }
    }

    fn title(&self) -> &str {
        match self {
            Self::InLibrary(c) => &c.title,
            Self::External(e) => &e.title,
        }
    }
}

fn render_entry_discography_or_children(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    let is_artist = ctx.detail.entry.kind == EntryKind::Artist;
    let in_lib_children: Vec<&CatalogEntry> =
        ctx.detail.children.iter().filter(|c| c.kind != EntryKind::Song).collect();
    let external_albums: Vec<DiscographyAlbum> = if is_artist {
        let owned_keys: HashSet<String> =
            in_lib_children.iter().map(|c| normalize_album_match_key(&c.title)).collect();
        extract_artist_discography(&ctx.detail.entry)
            .into_iter()
            .filter(|ext| !owned_keys.contains(&normalize_album_match_key(&ext.title)))
            .collect()
    } else {
        Vec::new()
    };

    if in_lib_children.is_empty() && external_albums.is_empty() {
        return;
    }

    let in_lib_count = in_lib_children.len();
    let has_external = !external_albums.is_empty();
    let mut items: Vec<DiscographyCardItem<'_>> = in_lib_children
        .into_iter()
        .map(DiscographyCardItem::InLibrary)
        .chain(external_albums.into_iter().map(DiscographyCardItem::External))
        .collect();
    if is_artist {
        items.sort_by(|a, b| {
            b.release_year()
                .cmp(&a.release_year())
                .then_with(|| a.title().to_lowercase().cmp(&b.title().to_lowercase()))
        });
    }

    let heading = if is_artist { "Discography" } else { "Contains" };
    let count_label = if has_external {
        format!("{in_lib_count} of {} in library", items.len())
    } else {
        in_lib_count.to_string()
    };
    let _ = writeln!(
        out,
        "<section class=\"entry-section\" aria-label=\"{heading}\">\n\
           <div class=\"section-head\"><h3>{heading}</h3><span class=\"section-action\">{count_label}</span></div>\n\
           <ul class=\"discography-grid\">",
    );

    let artist_id_enc = urlencode(&ctx.detail.entry.id);
    for item in items.into_iter().take(100) {
        match item {
            DiscographyCardItem::InLibrary(child) => {
                render_in_library_discog_card(out, child, ctx.child_tiers.get(&child.id).copied());
            }
            DiscographyCardItem::External(ext) => {
                render_external_discog_card(out, &artist_id_enc, &ext, ctx.csrf);
            }
        }
    }
    out.push_str("</ul>\n</section>\n");
}

fn discog_cover_html(title: &str, raw_thumb: Option<&str>) -> String {
    raw_thumb.and_then(sanitize_non_local_href).map_or_else(
        || {
            let initial = title
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
    )
}

fn render_in_library_discog_card(out: &mut String, child: &CatalogEntry, tier: Option<char>) {
    let href = format!("/entries/{}", urlencode(&child.id));
    let cover_html = discog_cover_html(&child.title, child.thumbnail_url.as_deref());
    let meta_left = child.release_year.map_or_else(
        || esc(child.kind.display_label()).to_string(),
        |yr| format!("{yr} &middot; {}", esc(child.kind.display_label())),
    );
    let right_badge = tier.map_or_else(
        || {
            "<span class=\"discography-in-lib-mark\" title=\"In your library\">&#10003;</span>"
                .to_owned()
        },
        |t| format!("<span class=\"discography-tier tier-{t}\" title=\"Tier {t}\">{t}</span>"),
    );
    let _ = writeln!(
        out,
        "<li class=\"discography-card-wrap\"><a class=\"discography-card\" href=\"{}\">\
           <div class=\"discography-cover-wrap\">{cover_html}</div>\
           <span class=\"discography-title\">{}</span>\
           <span class=\"discography-meta\"><span>{meta_left}</span>{right_badge}</span>\
         </a></li>",
        esc(&href),
        esc(&child.title),
    );
}

fn render_external_discog_card(
    out: &mut String,
    artist_id_enc: &str,
    ext: &DiscographyAlbum,
    csrf: &str,
) {
    let cover_html = discog_cover_html(&ext.title, ext.thumbnail_url.as_deref());
    let meta_left =
        ext.release_year.map_or_else(|| "Album".to_owned(), |yr| format!("{yr} &middot; Album"));
    let year_str = ext.release_year.map_or_else(String::new, |y| y.to_string());
    let thumb_str = ext.thumbnail_url.as_deref().unwrap_or("");
    let am_str = ext.apple_music_id.as_deref().unwrap_or("");
    let add_action = format!("/web/entries/{artist_id_enc}/add-album");
    let add_btn = format!(
        "<button type=\"submit\" class=\"discography-add-btn\" title=\"Add {t} to library\" aria-label=\"Add {t} to library\">+ Add</button>",
        t = esc(&ext.title),
    );

    let _ = write!(
        out,
        "<li class=\"discography-card-wrap\"><div class=\"discography-card-external\">\
           <div class=\"discography-cover-wrap\">{cover_html}",
    );
    let mut hidden: Vec<(&str, &str)> = vec![("title", ext.title.as_str())];
    if !year_str.is_empty() {
        hidden.push(("release_year", year_str.as_str()));
    }
    if !thumb_str.is_empty() {
        hidden.push(("thumbnail_url", thumb_str));
    }
    if !am_str.is_empty() {
        hidden.push(("apple_music_id", am_str));
    }
    render_inline_csrf_form_with_class(
        out,
        &add_action,
        csrf,
        Some("discography-add-form"),
        &hidden,
        None,
        &add_btn,
    );
    let _ = writeln!(
        out,
        "</div>\
           <span class=\"discography-title\">{}</span>\
           <span class=\"discography-meta\"><span>{meta_left}</span></span>\
         </div></li>",
        esc(&ext.title),
    );
}

fn render_entry_rail(out: &mut String, ctx: &EntryRenderCtx<'_>) {
    out.push_str("<div class=\"entry-rail\">\n");
    if !ctx.appearances.is_empty() {
        render_rail_rankings_card(out, &ctx.detail.entry.id, ctx.appearances);
    }
    render_rail_diary_card(out, ctx.detail, ctx.csrf);
    render_rail_lists_card(
        out,
        &ctx.detail.entry.id,
        ctx.parent_lists,
        ctx.available_manual_lists,
        ctx.csrf,
    );
    render_rail_merge_card(
        out,
        &ctx.detail.entry.id,
        ctx.merge_preview_query,
        ctx.merge_preview,
        ctx.csrf,
    );
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
    let id_enc = urlencode(&detail.entry.id);
    let return_to = format!("/entries/{id_enc}");

    if detail.log_history.is_empty() {
        out.push_str("<p class=\"rail-note\">Not logged in Diary yet.</p>\n");
    } else {
        out.push_str("<ul class=\"entry-history-list\">\n");
        for log in &detail.log_history {
            render_entry_diary_row(out, log, &return_to, csrf);
        }
        out.push_str("</ul>\n");
    }

    let subhead = if is_music { "Log a play" } else { "Log activity" };
    let placeholder = if is_music { "Thoughts, standout tracks…" } else { "Optional note…" };
    let _ = write!(
        out,
        "<div class=\"rail-subhead\">{subhead}</div>\n\
         <form method=\"post\" action=\"/web/entries/{id_enc}/log\" class=\"rail-form\">\n\
           <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
           <div class=\"field\">\n\
             <label for=\"log-status\">Status</label>\n\
             <select id=\"log-status\" name=\"status\" class=\"js-log-status-select\">\n\
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
             <input id=\"log-rank\" name=\"rank_after\" type=\"checkbox\" value=\"1\" checked class=\"js-log-rank-check\">\n\
             <label for=\"log-rank\">Rank after logging</label>\n\
           </div>\n\
           <button type=\"submit\" class=\"btn btn-primary btn-small\">Save Log</button>\n\
         </form>\n\
       </section>\n",
        id_enc = esc(&id_enc),
        csrf = esc(csrf),
    );
}

fn render_entry_diary_row(
    out: &mut String,
    log: &crate::db::ConsumptionLogRow,
    return_to: &str,
    csrf: &str,
) {
    let status_label = consumption_status_label(log.status);
    let log_id_enc = urlencode(&log.id);
    let date_iso = log.ts.format("%Y-%m-%d").to_string();
    let note_val = log.notes.as_deref().map_or("", str::trim);
    let sel_finished = if log.status == ConsumptionStatus::Consumed { " selected" } else { "" };
    let sel_started = if log.status == ConsumptionStatus::Started { " selected" } else { "" };
    let sel_skipped = if log.status == ConsumptionStatus::Abandoned { " selected" } else { "" };

    let _ = write!(
        out,
        "<li class=\"entry-history-row\">\
           <div class=\"entry-history-main\">\
             <span class=\"entry-history-date\">{}</span>\
             <span class=\"badge\">{status_label}</span>",
        log.ts.format("%b %-d, %Y"),
    );
    if !note_val.is_empty() {
        let _ = write!(out, "<span class=\"entry-history-note\">{}</span>", esc(note_val));
    }
    let _ = write!(
        out,
        "</div>\
         <div class=\"entry-history-actions\">\
           <details class=\"diary-edit-details\">\
             <summary class=\"link-btn\" aria-label=\"Edit diary entry from {}\">Edit</summary>\
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
        log.ts.format("%b %-d, %Y"),
        csrf = esc(csrf),
        ret = esc(return_to),
        note_esc = esc(note_val),
    );
    render_inline_csrf_form(
        out,
        &format!("/web/diary/{log_id_enc}/delete"),
        csrf,
        &[("return_to", return_to)],
        Some("Delete this diary entry?"),
        "<button type=\"submit\" class=\"link-btn link-btn-danger\" aria-label=\"Delete diary entry\">&times;</button>",
    );
    out.push_str("</div></li>\n");
}

fn render_rail_lists_card(
    out: &mut String,
    entry_id: &str,
    parent_lists: &[EntryParentListLink],
    available_manual_lists: &[CollectionSummary],
    csrf: &str,
) {
    if parent_lists.is_empty() && available_manual_lists.is_empty() {
        return;
    }
    let id_enc = urlencode(entry_id);
    let return_to = format!("/entries/{id_enc}");
    out.push_str(
        "<section class=\"rail-block rail-card\" aria-label=\"Lists\">\n\
           <div class=\"section-head\"><h3>Lists</h3></div>\n",
    );
    if !parent_lists.is_empty() {
        out.push_str("<ul class=\"rail-list-links\">\n");
        for parent in parent_lists {
            let draw_badge =
                if parent.has_generator { "<span class=\"badge\">Draw</span>" } else { "" };
            let slug_enc = urlencode(&parent.slug);
            let _ = write!(
                out,
                "<li class=\"rail-list-link-row\"><span class=\"rail-list-link-left\"><a href=\"/lists/{slug_enc}\">{}</a>{draw_badge}</span>",
                esc(&parent.title),
            );
            if parent.is_editable_manual {
                let remove_action = format!("/web/lists/{slug_enc}/remove/{id_enc}");
                let remove_btn = format!(
                    "<button type=\"submit\" class=\"link-btn\" title=\"Remove from {}\" aria-label=\"Remove from {}\"><span aria-hidden=\"true\">&times;</span></button>",
                    esc(&parent.title),
                    esc(&parent.title),
                );
                render_inline_csrf_form(
                    out,
                    &remove_action,
                    csrf,
                    &[("return_to", &return_to)],
                    None,
                    &remove_btn,
                );
            }
            out.push_str("</li>\n");
        }
        out.push_str("</ul>\n");
    }
    if !available_manual_lists.is_empty() {
        let _ = write!(
            out,
            "<form method=\"post\" action=\"/web/entries/{}/list-add\" class=\"rail-inline-add\">\n\
               <input type=\"hidden\" name=\"_csrf\" value=\"{}\">\n\
               <select name=\"list_slug_or_id\" aria-label=\"Target custom list\">",
            esc(&id_enc),
            esc(csrf),
        );
        for col in available_manual_lists {
            let key = col.slug.as_deref().unwrap_or(&col.id);
            let _ = write!(out, "<option value=\"{}\">{}</option>", esc(key), esc(&col.title));
        }
        out.push_str(
            "</select>\n<button type=\"submit\" class=\"btn btn-small\">Add</button>\n</form>\n",
        );
    }
    out.push_str("</section>\n");
}

fn render_rail_merge_card(
    out: &mut String,
    entry_id: &str,
    merge_preview_query: Option<&str>,
    merge_preview: Option<&MergePreviewState>,
    csrf: &str,
) {
    let id_enc = urlencode(entry_id);
    let open_attr = if merge_preview.is_some() { " open" } else { "" };
    let _ = write!(
        out,
        "<section class=\"rail-block rail-card\">\n\
           <details class=\"rail-details\"{open_attr}>\n\
             <summary>Merge Duplicate&hellip;</summary>\n\
             <form method=\"get\" action=\"/entries/{id_enc}\" class=\"rail-form\">\n\
               <p class=\"rail-note\">Folds identifiers, list memberships, diary logs, and comparisons from this duplicate into the target canonical entry.</p>\n\
               <div class=\"field\">\n\
                 <input name=\"merge_preview\" type=\"text\" required value=\"{q}\" aria-label=\"Canonical entry UUID, URL, or exact title\" placeholder=\"Canonical UUID, URL, or exact title\">\n\
               </div>\n\
               <button type=\"submit\" class=\"btn btn-small\">Preview Merge</button>\n\
             </form>\n",
        id_enc = esc(&id_enc),
        q = esc(merge_preview_query.unwrap_or("")),
    );
    if let Some(preview) = merge_preview {
        match preview {
            MergePreviewState::Error(msg) => {
                let _ = writeln!(
                    out,
                    "<p class=\"merge-preview-err\" role=\"alert\">{}</p>",
                    esc(msg),
                );
            }
            MergePreviewState::Ready(target) => {
                let creators = if target.entry.creators.is_empty() {
                    String::new()
                } else {
                    format!(" &mdash; {}", esc(&target.entry.creators.join(", ")))
                };
                let year =
                    target.entry.release_year.map_or_else(String::new, |y| format!(" ({y})"));
                let log_count = target.log_history.len();
                let log_word = if log_count == 1 { "diary log" } else { "diary logs" };
                let _ = write!(
                    out,
                    "<div class=\"merge-preview-card\">\n\
                       <p class=\"merge-preview-kicker\">Merge target</p>\n\
                       <p class=\"merge-preview-title\"><strong>{}</strong>{creators}{year}</p>\n\
                       <p class=\"merge-preview-meta\"><span class=\"badge\">{}</span> &middot; {log_count} {log_word}</p>\n\
                       <form method=\"post\" action=\"/web/entries/{id_enc}/merge\" class=\"merge-confirm-actions\">\n\
                         <input type=\"hidden\" name=\"_csrf\" value=\"{csrf}\">\n\
                         <input type=\"hidden\" name=\"canonical_id\" value=\"{target_id}\">\n\
                         <button type=\"submit\" class=\"btn btn-small btn-danger\">Confirm Merge</button>\n\
                         <a class=\"btn btn-small btn-ghost\" href=\"/entries/{id_enc}\">Cancel</a>\n\
                       </form>\n\
                     </div>\n",
                    esc(&target.entry.title),
                    esc(target.entry.kind.display_label()),
                    id_enc = esc(&id_enc),
                    csrf = esc(csrf),
                    target_id = esc(&target.entry.id),
                );
            }
        }
    }
    out.push_str("</details>\n</section>\n");
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

pub(in crate::web) async fn post_entry_log(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryLogForm>,
) -> Result<Response, WebError> {
    let status = parse_consumption_status(&form.status);
    let logged = service
        .catalog()
        .log_consumption(&id, status, form.notes.as_deref(), None)
        .await
        .web_err(&session)?;
    if status == ConsumptionStatus::Consumed
        && parse_form_bool(form.rank_after.as_deref())
        && let Ok(Some(slug)) = service.catalog().rank_target_after_consumption(&logged.entry).await
    {
        Ok(redirect_with_flash(
            &format!("/rank/{}?focus={}", urlencode(&slug), urlencode(&logged.entry.id)),
            "notice",
            "Logged to Diary",
        ))
    } else {
        Ok(redirect_with_flash(
            &format!("/entries/{}", urlencode(&logged.entry.id)),
            "notice",
            "Logged to Diary",
        ))
    }
}

pub(in crate::web) async fn post_entry_list_add(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryAddToListForm>,
) -> Result<Response, WebError> {
    service
        .catalog()
        .add_collection_entry(&form.list_slug_or_id, &id, None)
        .await
        .web_err(&session)?;
    let fallback = format!("/entries/{}", urlencode(&id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    let msg = if form.list_slug_or_id == crate::db::WANT_TO_READ_COLLECTION_ID {
        "Added to Queue"
    } else {
        "Added to list"
    };
    Ok(redirect_with_flash(&dest, "notice", msg))
}

pub(in crate::web) async fn post_entry_queue_toggle(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryQueueToggleForm>,
) -> Result<Response, WebError> {
    let fallback = format!("/entries/{}", urlencode(&id));
    let dest = safe_local_redirect(form.return_to.as_deref(), &fallback);
    let detail =
        service.catalog().get_entry_detail(&id).await.web_err(&session)?.ok_or_else(|| {
            WebError::from_app(
                AppError::not_found(format!("Entry '{id}' not found")),
                Some(&session.csrf_token),
                session.theme,
            )
        })?;
    let queue_slug = form
        .queue_slug
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .or_else(|| detail.entry.kind.default_want_slug())
        .unwrap_or(WANT_TO_READ_SLUG);

    if parse_form_bool(Some(&form.in_queue)) {
        let col =
            service.catalog().get_or_create_want_collection(queue_slug).await.web_err(&session)?;
        service.catalog().add_collection_entry(&col.id, &id, None).await.web_err(&session)?;
        Ok(redirect_with_flash(&dest, "notice", "Added to Queue"))
    } else {
        let _ = service.catalog().unwant_entry(queue_slug, &id).await.web_err(&session)?;
        Ok(redirect_with_flash(&dest, "notice", "Removed from Queue"))
    }
}

pub(in crate::web) async fn post_entry_enrich(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
) -> Result<Response, WebError> {
    let before =
        service.catalog().get_entry_detail(&id).await.web_err(&session)?.ok_or_else(|| {
            WebError::from_app(
                AppError::not_found(format!("Entry '{id}' not found")),
                Some(&session.csrf_token),
                session.theme,
            )
        })?;
    let after = service.enrich().enrich_entry_now(&id).await.web_err(&session)?;
    let enriched = before.entry.summary != after.entry.summary
        || before.entry.thumbnail_url != after.entry.thumbnail_url
        || before.entry.metadata != after.entry.metadata
        || before.entry.identifiers.len() != after.entry.identifiers.len()
        || before.children.len() != after.children.len();
    Ok(Json(serde_json::json!({ "enriched": enriched })).into_response())
}

pub(in crate::web) async fn post_entry_merge(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(id): Path<String>,
    Form(form): Form<EntryMergeForm>,
) -> Result<Response, WebError> {
    let target_id = if let Some(cid) =
        form.canonical_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        cid.to_owned()
    } else if let Some(q) = form.canonical_query.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        service.catalog().resolve_entry(q, None, None).await.web_err(&session)?.id
    } else {
        return Ok(redirect_with_flash(
            &format!("/entries/{}", urlencode(&id)),
            "err",
            "Specify a canonical entry target first",
        ));
    };
    if target_id == id {
        return Ok(redirect_with_flash(
            &format!("/entries/{}", urlencode(&id)),
            "err",
            "Cannot merge an entry into itself",
        ));
    }
    let canon = service.catalog().merge_entries(&id, &target_id).await.web_err(&session)?;
    Ok(redirect_with_flash(
        &format!("/entries/{}", urlencode(&canon.id)),
        "notice",
        "Merged duplicate into canonical entry",
    ))
}

pub(in crate::web) async fn post_artist_add_album(
    State(service): State<Arc<RocheService>>,
    Extension(session): Extension<WebSession>,
    Path(artist_id): Path<String>,
    Form(form): Form<AddArtistAlbumForm>,
) -> Result<Response, WebError> {
    let artist_detail =
        service.catalog().get_entry_detail(&artist_id).await.web_err(&session)?.ok_or_else(
            || {
                WebError::from_app(
                    AppError::not_found(format!("Entry '{artist_id}' not found")),
                    Some(&session.csrf_token),
                    session.theme,
                )
            },
        )?;
    let clean_title = form.title.trim();
    if clean_title.is_empty() {
        return Ok(redirect_with_flash(
            &format!("/entries/{}", urlencode(&artist_id)),
            "err",
            "Album title cannot be empty",
        ));
    }

    let release_year = parse_optional_year(form.release_year.as_deref());
    let thumbnail_url =
        form.thumbnail_url.as_deref().and_then(sanitize_non_local_href).map(ToOwned::to_owned);
    let mut identifiers = Vec::new();
    if let Some(am_id) = form
        .apple_music_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
    {
        identifiers
            .push(EntryIdentifier { scheme: "apple_music".to_owned(), value: am_id.to_owned() });
    }

    let input = UpsertEntryInput {
        kind: Some(EntryKind::Album),
        title: clean_title.to_owned(),
        creators: vec![artist_detail.entry.title.clone()],
        release_year,
        thumbnail_url,
        identifiers,
        ..UpsertEntryInput::default()
    };
    let album = service.catalog().upsert_entry(&input).await.web_err(&session)?;
    Ok(redirect_with_flash(
        &format!("/entries/{}", urlencode(&artist_id)),
        "notice",
        &format!("Added '{}' to library", album.title),
    ))
}
