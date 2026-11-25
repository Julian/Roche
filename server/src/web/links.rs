use std::collections::{HashMap, HashSet};

pub use crate::util::url_guard::sanitize_public_http_href as sanitize_non_local_href;
use crate::{
    db::{CatalogEntry, canonicalize_sync_url},
    domain::EntryKind,
    service::RocheService,
};

const IGNORED_WEB_MUSIC_SCHEMES: [&str; 7] =
    ["youtube_music", "apple_music", "tidal", "deezer", "qobuz", "amazon_music", "bandcamp"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WebLinkIcon {
    Wikipedia,
    Spotify,
    Film,
    Book,
    Paper,
    Game,
    Rss,
    Discussion,
    External,
}

impl WebLinkIcon {
    #[must_use]
    pub(super) fn for_scheme(scheme: &str) -> Self {
        match scheme {
            "wikipedia" => Self::Wikipedia,
            "spotify" => Self::Spotify,
            "imdb" | "tmdb_movie" | "tmdb_tv" | "tmdb" => Self::Film,
            "isbn" | "openlibrary" | "goodreads" => Self::Book,
            "arxiv" => Self::Paper,
            "igdb" => Self::Game,
            "feed_url" => Self::Rss,
            "hacker_news" | "lobsters" | "reddit" | "discussion" => Self::Discussion,
            _ => Self::External,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct WebExternalLink {
    pub label: String,
    pub href: String,
    pub icon: WebLinkIcon,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum UrlHostClass {
    Wikipedia,
    Spotify,
    IgnoredMusic,
}

const fn is_music_kind(kind: EntryKind) -> bool {
    matches!(kind, EntryKind::Album | EntryKind::Song | EntryKind::Artist)
}

fn is_web_visible_scheme(kind: EntryKind, scheme: &str) -> bool {
    if IGNORED_WEB_MUSIC_SCHEMES.contains(&scheme) || matches!(scheme, "slug" | "tag") {
        return false;
    }
    if is_music_kind(kind) {
        return matches!(scheme, "wikipedia" | "spotify");
    }
    true
}

fn is_ignored_music_host(host: &str) -> bool {
    matches!(
        host,
        "music.apple.com"
            | "itunes.apple.com"
            | "tidal.com"
            | "listen.tidal.com"
            | "deezer.com"
            | "www.deezer.com"
            | "open.qobuz.com"
            | "play.qobuz.com"
            | "www.qobuz.com"
            | "qobuz.com"
            | "music.youtube.com"
            | "music.amazon.com"
            | "bandcamp.com"
    ) || host.ends_with(".bandcamp.com")
}

fn classify_url_host(raw_url: &str) -> Option<UrlHostClass> {
    let parsed = url::Url::parse(raw_url).ok()?;
    let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
    if is_ignored_music_host(&host) {
        return Some(UrlHostClass::IgnoredMusic);
    }
    if host == "open.spotify.com" || host == "play.spotify.com" {
        return Some(UrlHostClass::Spotify);
    }
    if host == "wikipedia.org" || host.ends_with(".wikipedia.org") {
        return Some(UrlHostClass::Wikipedia);
    }
    None
}

fn has_valid_scheme(entry: &CatalogEntry, target_scheme: &str) -> bool {
    entry
        .identifiers
        .iter()
        .any(|i| i.scheme.eq_ignore_ascii_case(target_scheme) && i.external_url().is_some())
}

fn build_standalone_url_link(
    kind: EntryKind,
    href: &str,
    generic_label: &str,
    defer_wikipedia: bool,
    defer_spotify: bool,
) -> Option<(Option<UrlHostClass>, WebExternalLink)> {
    match classify_url_host(href) {
        Some(UrlHostClass::Wikipedia) if !defer_wikipedia => Some((
            Some(UrlHostClass::Wikipedia),
            WebExternalLink {
                label: "Wikipedia".to_owned(),
                href: href.to_owned(),
                icon: WebLinkIcon::Wikipedia,
            },
        )),
        Some(UrlHostClass::Spotify) if !defer_spotify => Some((
            Some(UrlHostClass::Spotify),
            WebExternalLink {
                label: "Spotify".to_owned(),
                href: href.to_owned(),
                icon: WebLinkIcon::Spotify,
            },
        )),
        None if !is_music_kind(kind) => Some((
            None,
            WebExternalLink {
                label: generic_label.to_owned(),
                href: href.to_owned(),
                icon: WebLinkIcon::External,
            },
        )),
        _ => None,
    }
}

/// Collects deduplicated external links to render in the web UI.
///
/// Music entries (`Album`, `Song`, `Artist`) only display Wikipedia and Spotify; unused music
/// streaming services are suppressed across all entry kinds.
#[must_use]
pub fn collect_web_external_links(
    entry: &CatalogEntry,
    source_label: Option<&str>,
) -> Vec<WebExternalLink> {
    let mut links = Vec::new();
    let mut seen_urls = HashSet::new();
    let mut seen_wikipedia = false;
    let mut seen_spotify = false;

    if let Some(src_lbl) = source_label
        && let Some(u) = entry.url.as_deref().and_then(sanitize_non_local_href)
        && let Some((cls, link)) = build_standalone_url_link(
            entry.kind,
            u,
            src_lbl,
            has_valid_scheme(entry, "wikipedia"),
            has_valid_scheme(entry, "spotify"),
        )
        && seen_urls.insert(canonicalize_sync_url(u))
    {
        seen_wikipedia |= cls == Some(UrlHostClass::Wikipedia);
        seen_spotify |= cls == Some(UrlHostClass::Spotify);
        links.push(link);
    }

    for ident in entry.sorted_external_identifiers() {
        let scheme = ident.scheme.trim().to_ascii_lowercase();
        if !is_web_visible_scheme(entry.kind, &scheme) {
            continue;
        }
        let Some(ext_url) = ident.external_url() else {
            continue;
        };
        let Some(safe_href) = sanitize_non_local_href(&ext_url) else {
            continue;
        };
        let host_cls = classify_url_host(safe_href);
        match host_cls {
            Some(UrlHostClass::IgnoredMusic) => continue,
            Some(UrlHostClass::Wikipedia) if seen_wikipedia => continue,
            Some(UrlHostClass::Spotify) if seen_spotify => continue,
            Some(UrlHostClass::Wikipedia) => seen_wikipedia = true,
            Some(UrlHostClass::Spotify) => seen_spotify = true,
            None => {}
        }
        if !seen_urls.insert(canonicalize_sync_url(safe_href)) {
            continue;
        }
        let (label, icon) = match (scheme.as_str(), host_cls) {
            ("url", Some(UrlHostClass::Wikipedia)) => ("Wikipedia", WebLinkIcon::Wikipedia),
            ("url", Some(UrlHostClass::Spotify)) => ("Spotify", WebLinkIcon::Spotify),
            _ => (ident.label(), WebLinkIcon::for_scheme(&scheme)),
        };
        links.push(WebExternalLink { label: label.to_owned(), href: safe_href.to_owned(), icon });
    }

    if !is_music_kind(entry.kind)
        && let Some(arr) = entry.metadata.get("discussions").and_then(serde_json::Value::as_array)
    {
        for raw in arr {
            let Ok(disc) =
                serde_json::from_value::<roche_core::boards::DiscussionLink>(raw.clone())
            else {
                continue;
            };
            let Some(safe_href) = sanitize_non_local_href(&disc.url) else {
                continue;
            };
            if !seen_urls.insert(canonicalize_sync_url(safe_href)) {
                continue;
            }
            let label = disc.label.as_deref().map(str::trim).filter(|s| !s.is_empty()).map_or_else(
                || {
                    let src = disc.source.trim();
                    if src.is_empty() {
                        "Discussion".to_owned()
                    } else {
                        roche_core::external_links::external_identifier_label(src).to_owned()
                    }
                },
                ToOwned::to_owned,
            );
            links.push(WebExternalLink {
                label,
                href: safe_href.to_owned(),
                icon: WebLinkIcon::Discussion,
            });
        }
    }

    if source_label.is_none()
        && links.is_empty()
        && let Some(u) = entry.url.as_deref().and_then(sanitize_non_local_href)
        && let Some((_, link)) = build_standalone_url_link(entry.kind, u, "Web", false, false)
    {
        links.push(link);
    }

    links
}

/// Resolves the primary external URL for keyboard-opening an entry in the web ranking UI.
#[must_use]
pub fn web_primary_open_url(entry: &CatalogEntry) -> Option<String> {
    collect_web_external_links(entry, Some("Open Source")).into_iter().next().map(|l| l.href)
}

pub async fn load_artist_id_by_lower_name(
    service: &RocheService,
    creator_names: &[String],
) -> HashMap<String, String> {
    service.catalog().find_artist_ids_by_names(creator_names).await.unwrap_or_default()
}

#[must_use]
pub fn extract_entry_tags(entry: &CatalogEntry) -> Vec<String> {
    let mut tags = Vec::new();
    for key in ["genres", "styles", "topics"] {
        if let Some(arr) = entry.metadata.get(key).and_then(serde_json::Value::as_array) {
            for val in arr {
                if let Some(s) = val.as_str().map(str::trim).filter(|s| !s.is_empty())
                    && !tags.iter().any(|existing: &String| existing.eq_ignore_ascii_case(s))
                {
                    tags.push(s.to_owned());
                    if tags.len() >= 5 {
                        return tags;
                    }
                }
            }
        }
    }
    tags
}
