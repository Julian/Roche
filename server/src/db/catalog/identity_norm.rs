use super::models::EntryIdentifier;
use crate::db::{
    sync::{canonicalize_sync_url, deterministic_catalog_entry_id, deterministic_title_entry_id},
    types::EntryKind,
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

pub const PREFERRED_IDENTIFIER_SCHEMES: [&str; 15] = [
    "spotify",
    "1001albums",
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

pub(super) const EXTERNAL_ID_DISPLAY_ORDER: [&str; 23] = [
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
    "1001albums",
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
        "1001albums" => "1001 Albums",
        "bandcamp" => "Bandcamp",
        "url" => "Web",
        "feed_url" => "Feed",
        other => other,
    }
}

fn is_http_url(v: &str) -> bool {
    let lower = v.to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

fn direct_or_prefixed_url(prefix: &str, v: &str) -> String {
    if is_http_url(v) { v.to_owned() } else { format!("{prefix}{v}") }
}

fn format_youtube_music_url(v: &str) -> String {
    if is_http_url(v) {
        return v.to_owned();
    }
    let stripped = if v.starts_with("VLOLAK5uy_") || v.starts_with("VLPL") { &v[2..] } else { v };
    if stripped.starts_with("MPREb") {
        format!("https://music.youtube.com/browse/{stripped}")
    } else {
        format!("https://music.youtube.com/playlist?list={stripped}")
    }
}

#[must_use]
pub fn external_identifier_url(scheme: &str, value: &str) -> Option<String> {
    let v = value.trim();
    if v.is_empty() {
        return None;
    }
    match scheme {
        "spotify" => {
            if let Some(rest) = v.strip_prefix("spotify:") {
                let parts: Vec<&str> = rest.split(':').filter(|s| !s.is_empty()).collect();
                if parts.len() >= 4
                    && parts[0].eq_ignore_ascii_case("user")
                    && parts[2].eq_ignore_ascii_case("playlist")
                {
                    return Some(format!("https://open.spotify.com/playlist/{}", parts[3]));
                }
                if parts.len() >= 2 {
                    return Some(format!(
                        "https://open.spotify.com/{}/{}",
                        parts[0].to_ascii_lowercase(),
                        parts[1]
                    ));
                }
                None
            } else {
                Some(direct_or_prefixed_url("https://open.spotify.com/album/", v))
            }
        }
        "imdb" => {
            if is_http_url(v) {
                Some(v.to_owned())
            } else {
                Some(format!("https://www.imdb.com/title/{v}/"))
            }
        }
        "tmdb_movie" => Some(direct_or_prefixed_url("https://www.themoviedb.org/movie/", v)),
        "tmdb_tv" => Some(direct_or_prefixed_url("https://www.themoviedb.org/tv/", v)),
        "isbn" => Some(direct_or_prefixed_url("https://openlibrary.org/isbn/", v)),
        "openlibrary" => Some(direct_or_prefixed_url("https://openlibrary.org/works/", v)),
        "goodreads" => Some(direct_or_prefixed_url("https://www.goodreads.com/book/show/", v)),
        "igdb" => Some(direct_or_prefixed_url("https://www.igdb.com/games/", v)),
        "discogs" => Some(direct_or_prefixed_url("https://www.discogs.com/release/", v)),
        "wikipedia" => {
            if is_http_url(v) {
                Some(v.to_owned())
            } else {
                let encoded = v.replace('#', "%23").replace('?', "%3F");
                Some(format!("https://en.wikipedia.org/wiki/{encoded}"))
            }
        }
        "arxiv" => Some(direct_or_prefixed_url("https://arxiv.org/abs/", v)),
        "musicbrainz_release" => {
            Some(direct_or_prefixed_url("https://musicbrainz.org/release/", v))
        }
        "musicbrainz_artist" => Some(direct_or_prefixed_url("https://musicbrainz.org/artist/", v)),
        "musicbrainz_recording" => {
            Some(direct_or_prefixed_url("https://musicbrainz.org/recording/", v))
        }
        "apple_music" => Some(direct_or_prefixed_url("https://music.apple.com/album/", v)),
        "tidal" => Some(direct_or_prefixed_url("https://tidal.com/browse/album/", v)),
        "deezer" => Some(direct_or_prefixed_url("https://www.deezer.com/album/", v)),
        "qobuz" => Some(direct_or_prefixed_url("https://open.qobuz.com/album/", v)),
        "amazon_music" => Some(direct_or_prefixed_url("https://music.amazon.com/albums/", v)),
        "youtube_music" => Some(format_youtube_music_url(v)),
        "url" | "feed_url" if is_http_url(v) => Some(v.to_owned()),
        _ => None,
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
        "slug" | "tag" | "1001albums" => trimmed.to_lowercase(),
        "spotify" => normalize_spotify_identifier(kind, trimmed),
        "imdb" => normalize_imdb_identifier(trimmed),
        "wikipedia" => normalize_wikipedia_identifier(trimmed),
        "arxiv" => crate::feeds::arxiv::ArxivParser::extract_arxiv_id(trimmed)
            .unwrap_or_else(|| trimmed.to_owned()),
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
    if let Ok(parsed) = url::Url::parse(raw)
        && parsed.host_str().is_some_and(|h| {
            h.eq_ignore_ascii_case("open.spotify.com") || h.eq_ignore_ascii_case("play.spotify.com")
        })
    {
        let segments: Vec<&str> =
            parsed.path_segments().map_or_default(|it| it.filter(|s| !s.is_empty()).collect());
        if segments.len() >= 2 {
            let entity = segments[segments.len() - 2].to_ascii_lowercase();
            let ident = segments[segments.len() - 1];
            if matches!(
                entity.as_str(),
                "album" | "track" | "artist" | "episode" | "show" | "playlist"
            ) && !ident.is_empty()
            {
                return format!("spotify:{entity}:{ident}");
            }
        }
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
        && parsed.host_str().is_some_and(|h| h.to_ascii_lowercase().ends_with("wikipedia.org"))
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

fn decode_percent_utf8(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(hi), Some(lo)) = (hex_nibble(bytes[i + 1]), hex_nibble(bytes[i + 2]))
        {
            out.push((hi << 4) | lo);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8(out).unwrap_or_else(|_| input.replace("%20", "_"))
}

const fn hex_nibble(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
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
    }
}
