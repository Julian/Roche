use super::models::EntryIdentifier;
use crate::{
    db::{
        sync::{
            canonicalize_sync_url, deterministic_catalog_entry_id, deterministic_title_entry_id,
        },
        types::EntryKind,
    },
    util::{media_urls::extract_arxiv_id, url_guard::decode_percent_utf8},
};

pub const EDITION_KEYWORDS: [&str; 15] = [
    "deluxe",
    "standard",
    "remaster",
    "remastered",
    "expanded",
    "special",
    "limited",
    "anniversary",
    "bonus",
    "edition",
    "version",
    "reissue",
    "explicit",
    "clean",
    "remasters",
];

pub const PREFERRED_IDENTIFIER_SCHEMES: [&str; 14] = [
    "spotify",
    "musicbrainz_release",
    "musicbrainz_recording",
    "musicbrainz_artist",
    "imdb",
    "tmdb_movie",
    "tmdb_tv",
    "isbn",
    "openlibrary",
    "igdb",
    "arxiv",
    "wikipedia",
    "feed_url",
    "url",
];

pub(super) const EXTERNAL_ID_DISPLAY_ORDER: [&str; 22] = [
    "wikipedia",
    "arxiv",
    "musicbrainz_release",
    "musicbrainz_recording",
    "musicbrainz_artist",
    "imdb",
    "tmdb_movie",
    "tmdb_tv",
    "tmdb",
    "openlibrary",
    "isbn",
    "goodreads",
    "igdb",
    "discogs",
    "spotify",
    "youtube_music",
    "apple_music",
    "tidal",
    "deezer",
    "qobuz",
    "amazon_music",
    "bandcamp",
];

#[must_use]
pub fn external_identifier_label(scheme: &str) -> &str {
    match scheme {
        "imdb" => "IMDb",
        "tmdb_movie" | "tmdb_tv" | "tmdb" => "TMDB",
        "isbn" => "ISBN",
        "openlibrary" => "Open Library",
        "goodreads" => "Goodreads",
        "igdb" => "IGDB",
        "discogs" => "Discogs",
        "wikipedia" => "Wikipedia",
        "arxiv" => "arXiv",
        "musicbrainz_release" | "musicbrainz_artist" | "musicbrainz_recording" | "musicbrainz" => {
            "MusicBrainz"
        }
        "spotify" => "Spotify",
        "apple_music" => "Apple Music",
        "tidal" => "Tidal",
        "deezer" => "Deezer",
        "youtube_music" => "YouTube Music",
        "qobuz" => "Qobuz",
        "amazon_music" => "Amazon Music",
        "bandcamp" => "Bandcamp",
        "rym" => "RYM",
        "letterboxd" => "Letterboxd",
        "steam" => "Steam",
        "doi" => "DOI",
        "wikidata" => "Wikidata",
        "youtube" => "YouTube",
        "url" => "Web",
        "feed_url" => "Feed",
        other => other,
    }
}

fn is_http_url(v: &str) -> bool {
    let lower = v.to_ascii_lowercase();
    (lower.starts_with("http://") && v.len() > "http://".len())
        || (lower.starts_with("https://") && v.len() > "https://".len())
}

fn is_disallowed_uri_scheme(v: &str) -> bool {
    let lower = v.to_ascii_lowercase();
    lower.starts_with("javascript:")
        || lower.starts_with("data:")
        || lower.starts_with("vbscript:")
        || lower.starts_with("file:")
        || lower.starts_with("ftp:")
}

fn is_safe_bare_identifier(v: &str) -> bool {
    !v.is_empty()
        && !v.contains("://")
        && !v.contains("..")
        && !v.contains('@')
        && !v.contains('\\')
        && !v.chars().any(|c| c.is_whitespace() || c.is_control())
}

fn validated_branded_https_url(v: &str, allowed_host: impl Fn(&str) -> bool) -> Option<String> {
    let parsed = url::Url::parse(v).ok()?;
    if parsed.scheme() != "https" || !parsed.username().is_empty() || parsed.password().is_some() {
        return None;
    }
    let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
    allowed_host(&host).then(|| parsed.into())
}

fn is_wikipedia_host(host: &str) -> bool {
    host == "wikipedia.org" || host.ends_with(".wikipedia.org")
}

fn is_allowed_host_for_scheme(scheme: &str, host: &str) -> bool {
    match scheme {
        "tmdb_movie" | "tmdb_tv" | "tmdb" => {
            matches!(host, "themoviedb.org" | "www.themoviedb.org")
        }
        "letterboxd" => matches!(host, "letterboxd.com" | "www.letterboxd.com"),
        "isbn" | "openlibrary" => matches!(host, "openlibrary.org" | "www.openlibrary.org"),
        "goodreads" => matches!(host, "goodreads.com" | "www.goodreads.com"),
        "steam" => host == "store.steampowered.com",
        "igdb" => matches!(host, "igdb.com" | "www.igdb.com"),
        "discogs" => matches!(host, "discogs.com" | "www.discogs.com"),
        "arxiv" => crate::util::media_urls::is_arxiv_host(Some(host)),
        "wikidata" => matches!(host, "wikidata.org" | "www.wikidata.org"),
        "musicbrainz_release" | "musicbrainz_artist" | "musicbrainz_recording" | "musicbrainz" => {
            matches!(host, "musicbrainz.org" | "www.musicbrainz.org")
        }
        "apple_music" => matches!(host, "music.apple.com" | "itunes.apple.com"),
        "tidal" => matches!(host, "tidal.com" | "listen.tidal.com" | "www.tidal.com"),
        "deezer" => matches!(host, "deezer.com" | "www.deezer.com"),
        "qobuz" => {
            matches!(host, "open.qobuz.com" | "play.qobuz.com" | "www.qobuz.com" | "qobuz.com")
        }
        "amazon_music" => host == "music.amazon.com",
        _ => false,
    }
}

fn direct_or_prefixed_url(scheme: &str, prefix: &str, v: &str) -> Option<String> {
    if is_http_url(v) {
        validated_branded_https_url(v, |host| is_allowed_host_for_scheme(scheme, host))
    } else if is_safe_bare_identifier(v) {
        Some(format!("{prefix}{v}"))
    } else {
        None
    }
}

fn strip_youtube_playlist_prefix(id: &str) -> &str {
    if id.starts_with("VLOLAK5uy_") || id.starts_with("VLPL") { &id[2..] } else { id }
}

fn sanitize_youtube_music_token(raw: &str) -> String {
    let valid = raw
        .trim()
        .split(|c: char| c.is_whitespace() || matches!(c, '&' | '?'))
        .map(|part| strip_youtube_playlist_prefix(part.trim()))
        .find(|token| {
            !token.is_empty()
                && token.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-'))
                && (token.starts_with("MPREb")
                    || (token.starts_with("OLAK5uy_") && token.len() > 8)
                    || (token.starts_with("PL") && token.len() > 2))
        });
    valid.map_or_else(
        || {
            let head = raw.trim().split(&['&', '?'][..]).next().unwrap_or("").trim();
            strip_youtube_playlist_prefix(head).to_owned()
        },
        str::to_owned,
    )
}

fn format_youtube_music_url(v: &str) -> Option<String> {
    if is_http_url(v) {
        let parsed = url::Url::parse(v).ok()?;
        if parsed.scheme() != "https"
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return None;
        }
        let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
        if host != "music.youtube.com" {
            return None;
        }
        let browse_seg = parsed
            .path()
            .split_once("/browse/")
            .map(|(_, rest)| sanitize_youtube_music_token(rest.trim_matches('/')))
            .filter(|s| !s.is_empty());
        let list_param = parsed
            .query_pairs()
            .find(|(k, _)| k == "list")
            .map(|(_, val)| sanitize_youtube_music_token(&val))
            .filter(|s| !s.is_empty());
        if let Some(ref seg) = browse_seg
            && seg.starts_with("MPREb")
        {
            return Some(format!("https://music.youtube.com/browse/{seg}"));
        }
        if let Some(list) = list_param {
            return Some(format!("https://music.youtube.com/playlist?list={list}"));
        }
        if let Some(seg) = browse_seg {
            return Some(format!("https://music.youtube.com/playlist?list={seg}"));
        }
        return Some(parsed.into());
    }
    let normalized = sanitize_youtube_music_token(v);
    if !is_safe_bare_identifier(&normalized) {
        return None;
    }
    if normalized.starts_with("MPREb") {
        Some(format!("https://music.youtube.com/browse/{normalized}"))
    } else {
        Some(format!("https://music.youtube.com/playlist?list={normalized}"))
    }
}

fn extract_spotify_web_entity_and_id(raw: &str) -> Option<(String, String)> {
    let parsed = url::Url::parse(raw).ok()?;
    let host_ok = parsed.host_str().is_some_and(|h| {
        h.eq_ignore_ascii_case("open.spotify.com") || h.eq_ignore_ascii_case("play.spotify.com")
    });
    if !host_ok {
        return None;
    }
    let segments: Vec<&str> =
        parsed.path_segments().map_or_default(|it| it.filter(|s| !s.is_empty()).collect());
    if segments.len() < 2 {
        return None;
    }
    let entity = segments[segments.len() - 2].to_ascii_lowercase();
    let ident = segments[segments.len() - 1];
    (matches!(entity.as_str(), "album" | "track" | "artist" | "episode" | "show" | "playlist")
        && !ident.is_empty())
    .then(|| (entity, ident.to_owned()))
}

const fn spotify_default_entity_for_kind(kind: Option<EntryKind>) -> &'static str {
    match kind {
        Some(EntryKind::Song) => "track",
        Some(EntryKind::Artist) => "artist",
        Some(EntryKind::Podcast) => "show",
        Some(EntryKind::PodcastEpisode) => "episode",
        Some(EntryKind::Collection) => "playlist",
        _ => "album",
    }
}

const fn tmdb_prefix_for_kind(kind: Option<EntryKind>) -> Option<&'static str> {
    match kind {
        Some(EntryKind::Tv) => Some("https://www.themoviedb.org/tv/"),
        Some(EntryKind::Artist) => Some("https://www.themoviedb.org/person/"),
        Some(EntryKind::Movie) | None => Some("https://www.themoviedb.org/movie/"),
        _ => None,
    }
}

const fn musicbrainz_prefix_for_kind(kind: Option<EntryKind>) -> Option<&'static str> {
    match kind {
        Some(EntryKind::Artist) => Some("https://musicbrainz.org/artist/"),
        Some(EntryKind::Song) => Some("https://musicbrainz.org/recording/"),
        Some(EntryKind::Album) | None => Some("https://musicbrainz.org/release/"),
        _ => None,
    }
}

fn format_spotify_external_url(v: &str, kind: Option<EntryKind>) -> Option<String> {
    if let Some(rest) = v.strip_prefix("spotify:") {
        let parts: Vec<&str> = rest.split(':').filter(|s| !s.is_empty()).collect();
        if parts.len() >= 4
            && parts[0].eq_ignore_ascii_case("user")
            && parts[2].eq_ignore_ascii_case("playlist")
            && is_safe_bare_identifier(parts[3])
        {
            return Some(format!("https://open.spotify.com/playlist/{}", parts[3]));
        }
        return (parts.len() >= 2
            && is_safe_bare_identifier(parts[0])
            && is_safe_bare_identifier(parts[1]))
        .then(|| {
            format!("https://open.spotify.com/{}/{}", parts[0].to_ascii_lowercase(), parts[1])
        });
    }
    if let Some((entity, id)) = extract_spotify_web_entity_and_id(v) {
        return Some(format!("https://open.spotify.com/{entity}/{id}"));
    }
    if is_http_url(v) {
        return validated_branded_https_url(v, |host| {
            matches!(host, "open.spotify.com" | "play.spotify.com")
        });
    }
    if !is_safe_bare_identifier(v) || v.contains(':') || v.contains('/') {
        return None;
    }
    Some(format!("https://open.spotify.com/{}/{v}", spotify_default_entity_for_kind(kind)))
}

fn format_youtube_external_url(v: &str) -> Option<String> {
    if is_http_url(v) {
        validated_branded_https_url(v, |host| {
            crate::util::media_urls::is_youtube_host(Some(host))
                || matches!(host, "youtu.be" | "www.youtu.be")
        })
    } else if crate::util::media_urls::is_valid_youtube_channel_id(v) {
        Some(format!("https://www.youtube.com/channel/{v}"))
    } else if crate::util::media_urls::is_valid_youtube_video_id(v) {
        Some(format!("https://www.youtube.com/watch?v={v}"))
    } else {
        None
    }
}

fn format_doi_external_url(v: &str) -> Option<String> {
    if is_http_url(v) {
        return validated_branded_https_url(v, |host| matches!(host, "doi.org" | "dx.doi.org"));
    }
    let clean = v
        .get(..4)
        .filter(|prefix| prefix.eq_ignore_ascii_case("doi:"))
        .map_or(v, |_| &v[4..])
        .trim();
    is_safe_bare_identifier(clean).then(|| format!("https://doi.org/{clean}"))
}

fn prefixed_external_url(scheme: &str, v: &str, kind: Option<EntryKind>) -> Option<String> {
    let prefix = match scheme {
        "tmdb_movie" => "https://www.themoviedb.org/movie/",
        "tmdb_tv" => "https://www.themoviedb.org/tv/",
        "tmdb" => tmdb_prefix_for_kind(kind)?,
        "letterboxd" => "https://letterboxd.com/film/",
        "isbn" => "https://openlibrary.org/isbn/",
        "openlibrary" => "https://openlibrary.org/works/",
        "goodreads" => "https://www.goodreads.com/book/show/",
        "steam" => "https://store.steampowered.com/app/",
        "igdb" => "https://www.igdb.com/games/",
        "discogs" => "https://www.discogs.com/release/",
        "arxiv" => "https://arxiv.org/abs/",
        "wikidata" => "https://www.wikidata.org/wiki/",
        "musicbrainz_release" => "https://musicbrainz.org/release/",
        "musicbrainz_artist" => "https://musicbrainz.org/artist/",
        "musicbrainz_recording" => "https://musicbrainz.org/recording/",
        "musicbrainz" => musicbrainz_prefix_for_kind(kind)?,
        "apple_music" => "https://music.apple.com/album/",
        "tidal" => "https://tidal.com/browse/album/",
        "deezer" => "https://www.deezer.com/album/",
        "qobuz" => "https://open.qobuz.com/album/",
        "amazon_music" => "https://music.amazon.com/albums/",
        _ => return None,
    };
    direct_or_prefixed_url(scheme, prefix, v)
}

#[must_use]
pub fn external_identifier_url(scheme: &str, value: &str) -> Option<String> {
    external_identifier_url_for_kind(scheme, value, None)
}

#[must_use]
pub fn external_identifier_url_for_kind(
    scheme: &str,
    value: &str,
    kind: Option<EntryKind>,
) -> Option<String> {
    let v = value.trim();
    if v.is_empty() || is_disallowed_uri_scheme(v) {
        return None;
    }
    let norm_scheme = normalize_identifier_scheme(scheme);
    match norm_scheme.as_str() {
        "spotify" => format_spotify_external_url(v, kind),
        "youtube_music" => format_youtube_music_url(v),
        "youtube" => format_youtube_external_url(v),
        "imdb" => {
            if is_http_url(v) {
                validated_branded_https_url(v, |host| {
                    matches!(host, "imdb.com" | "www.imdb.com" | "m.imdb.com")
                })
            } else {
                is_safe_bare_identifier(v).then(|| format!("https://www.imdb.com/title/{v}/"))
            }
        }
        "wikipedia" => {
            if is_http_url(v) {
                validated_branded_https_url(v, is_wikipedia_host)
            } else if !v.contains("://") && !v.contains("..") && !v.chars().any(char::is_control) {
                let encoded = v.replace('#', "%23").replace('?', "%3F");
                Some(format!("https://en.wikipedia.org/wiki/{encoded}"))
            } else {
                None
            }
        }
        "doi" => format_doi_external_url(v),
        "bandcamp" => validated_branded_https_url(v, |host| {
            host == "bandcamp.com" || host.ends_with(".bandcamp.com")
        }),
        "rym" => validated_branded_https_url(v, |host| {
            matches!(host, "rateyourmusic.com" | "www.rateyourmusic.com")
        }),
        "url" | "feed_url" => {
            crate::util::url_guard::sanitize_public_http_href(v).map(str::to_owned)
        }
        other => prefixed_external_url(other, v, kind),
    }
}

/// Normalizes identifier scheme names (`"musicbrainz-release"` -> `"musicbrainz_release"`).
#[must_use]
pub fn normalize_identifier_scheme(raw_scheme: &str) -> String {
    raw_scheme.trim().to_ascii_lowercase().replace('-', "_")
}

/// Normalizes external identifier values so equivalent representations collide deterministically
/// across Rust, Kotlin, and CLI migrations.
#[must_use]
pub fn normalize_identifier_value(kind: EntryKind, scheme: &str, raw_value: &str) -> String {
    let trimmed = raw_value.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    match normalize_identifier_scheme(scheme).as_str() {
        "url" | "feed_url" => canonicalize_sync_url(trimmed),
        "slug" | "tag" => trimmed.to_lowercase(),
        "spotify" => normalize_spotify_identifier(kind, trimmed),
        "imdb" => normalize_imdb_identifier(trimmed),
        "wikipedia" => normalize_wikipedia_identifier(trimmed),
        "arxiv" => extract_arxiv_id(trimmed).unwrap_or_else(|| trimmed.to_owned()),
        _ => trimmed.to_owned(),
    }
}

pub(super) fn normalize_spotify_identifier(kind: EntryKind, raw: &str) -> String {
    if let Some(rest) = raw.strip_prefix("spotify:") {
        let parts: Vec<&str> = rest.split(':').filter(|s| !s.is_empty()).collect();
        if parts.len() >= 4
            && parts[0].eq_ignore_ascii_case("user")
            && parts[2].eq_ignore_ascii_case("playlist")
        {
            return format!("spotify:playlist:{}", parts[3]);
        }
        if parts.len() >= 2 {
            return format!("spotify:{}:{}", parts[0].to_ascii_lowercase(), parts[1]);
        }
        return raw.to_owned();
    }
    if let Some((entity, ident)) = extract_spotify_web_entity_and_id(raw) {
        return format!("spotify:{entity}:{ident}");
    }
    let spotify_entity = match kind {
        EntryKind::Album => Some("album"),
        EntryKind::Song => Some("track"),
        EntryKind::Artist => Some("artist"),
        EntryKind::PodcastEpisode => Some("episode"),
        EntryKind::Podcast => Some("show"),
        _ => None,
    };
    if let Some(entity) = spotify_entity
        && !raw.contains(':')
        && !raw.contains('/')
    {
        return format!("spotify:{entity}:{raw}");
    }
    raw.to_owned()
}

fn normalize_imdb_identifier(raw: &str) -> String {
    // Extract `tt<digits>` from URLs (`https://www.imdb.com/title/tt0079944/...`) or uppercase IDs (`TT0079944`).
    for token in raw.split(|c: char| !c.is_ascii_alphanumeric()) {
        if token.len() >= 4
            && token[..2].eq_ignore_ascii_case("tt")
            && token[2..].bytes().all(|b| b.is_ascii_digit())
        {
            return format!("tt{}", &token[2..]);
        }
    }
    raw.to_owned()
}

fn normalize_wikipedia_identifier(raw: &str) -> String {
    if let Ok(parsed) = url::Url::parse(raw)
        && parsed
            .host_str()
            .is_some_and(|h| is_wikipedia_host(&h.trim_end_matches('.').to_ascii_lowercase()))
        && let Some(wiki_part) = parsed.path().strip_prefix("/wiki/")
    {
        let decoded = decode_percent_utf8(wiki_part.trim_matches('/'));
        let clean = decoded.replace(' ', "_");
        if !clean.is_empty() {
            return clean;
        }
    }
    decode_percent_utf8(raw).replace(' ', "_")
}

/// Strips trailing bracketed/parenthesized edition suffixes (e.g. `(Deluxe Edition)`, `[2019 Remaster]`).
///
/// Uses whole-word keyword matching while preserving distinct works like `(Live)`, `(Demos)`,
/// `"Expedition"`, `"Subversion"`, or `"Cleaner"`, and never stripping `" - ..."` subtitles.
#[must_use]
pub fn strip_album_edition_suffix(title: &str) -> String {
    let mut cur = title.trim();
    while let Some(last_ch) = cur.chars().last() {
        let open_ch = match last_ch {
            ')' => '(',
            ']' => '[',
            _ => break,
        };
        let without_close = &cur[..cur.len() - last_ch.len_utf8()];
        let Some(open_idx) = without_close.rfind(open_ch) else {
            break;
        };
        let inner = &without_close[open_idx + open_ch.len_utf8()..];
        if inner.chars().any(|c| matches!(c, '(' | ')' | '[' | ']')) {
            break;
        }
        let has_edition_word =
            inner.split(|c: char| !c.is_alphanumeric()).filter(|w| !w.is_empty()).any(|w| {
                let lower = w.to_ascii_lowercase();
                EDITION_KEYWORDS.contains(&lower.as_str())
            });
        if !has_edition_word {
            break;
        }
        let prefix = without_close[..open_idx].trim_end();
        if prefix.is_empty() {
            break;
        }
        cur = prefix;
    }
    cur.to_owned()
}

/// Selects a canonical deterministic UUID for a catalog entry across Rust and Kotlin.
#[must_use]
pub fn choose_deterministic_entry_id(
    explicit_id: Option<&str>,
    kind: EntryKind,
    title: &str,
    creators: &[String],
    identifiers: &[EntryIdentifier],
) -> String {
    if let Some(id) = explicit_id.map(str::trim).filter(|s| !s.is_empty()) {
        return id.to_owned();
    }
    for preferred in PREFERRED_IDENTIFIER_SCHEMES {
        if let Some(ident) = identifiers
            .iter()
            .filter(|i| i.scheme == preferred && !i.value.trim().is_empty())
            .min_by(|a, b| a.value.cmp(&b.value))
        {
            return deterministic_catalog_entry_id(kind.as_str(), &ident.scheme, &ident.value);
        }
    }
    if let Some(best_ident) = identifiers
        .iter()
        .filter(|i| !i.scheme.trim().is_empty() && !i.value.trim().is_empty())
        .min_by(|a, b| a.scheme.cmp(&b.scheme).then_with(|| a.value.cmp(&b.value)))
    {
        return deterministic_catalog_entry_id(
            kind.as_str(),
            &best_ident.scheme,
            &best_ident.value,
        );
    }
    let normalized_title = if kind == EntryKind::Album {
        strip_album_edition_suffix(title)
    } else {
        title.trim().to_owned()
    };
    let title_key = creators.iter().map(|c| c.trim()).find(|c| !c.is_empty()).map_or_else(
        || normalized_title.to_lowercase(),
        |primary_creator| {
            format!("{}\0{}", primary_creator.to_lowercase(), normalized_title.to_lowercase())
        },
    );
    deterministic_title_entry_id(kind.as_str(), &title_key)
}

#[cfg(test)]
mod tests {
    use super::external_identifier_url;

    #[test]
    fn test_external_identifier_url_handles_bare_ids_browse_ids_and_full_urls() {
        assert_eq!(
            external_identifier_url("youtube_music", "VLOLAK5uy_abc123").as_deref(),
            Some("https://music.youtube.com/playlist?list=OLAK5uy_abc123")
        );
        assert_eq!(
            external_identifier_url("youtube_music", "MPREb_xyz789").as_deref(),
            Some("https://music.youtube.com/browse/MPREb_xyz789")
        );
        assert_eq!(
            external_identifier_url("apple_music", "https://music.apple.com/us/album/foo/123")
                .as_deref(),
            Some("https://music.apple.com/us/album/foo/123")
        );
        assert_eq!(
            external_identifier_url("qobuz", "0060253775938").as_deref(),
            Some("https://open.qobuz.com/album/0060253775938")
        );
        assert_eq!(
            external_identifier_url("amazon_music", "B001234567").as_deref(),
            Some("https://music.amazon.com/albums/B001234567")
        );
        assert_eq!(
            external_identifier_url("wikipedia", "In_the_Wee_Small_Hours").as_deref(),
            Some("https://en.wikipedia.org/wiki/In_the_Wee_Small_Hours")
        );
        // Reject spoofed hosts masquerading as branded services or evilwikipedia.org
        assert!(external_identifier_url("spotify", "https://evil.example/track/123").is_none());
        assert!(
            external_identifier_url("wikipedia", "https://evilwikipedia.org/wiki/Foo").is_none()
        );
        assert!(external_identifier_url("imdb", "https://evil.example/title/tt0133093/").is_none());
        assert!(
            external_identifier_url("apple_music", "http://music.apple.com/us/album/foo/123")
                .is_none()
        );
        assert!(external_identifier_url("url", "https://secret.tail1234.ts.net/private").is_none());
        assert_eq!(
            super::normalize_wikipedia_identifier("https://evilwikipedia.org/wiki/Fake"),
            "https://evilwikipedia.org/wiki/Fake"
        );
        assert_eq!(
            super::normalize_wikipedia_identifier(
                "https://en.wikipedia.org/wiki/Stalker_(1979_film)"
            ),
            "Stalker_(1979_film)"
        );
    }
}
