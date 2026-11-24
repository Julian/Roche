use crate::{
    boards::{hacker_news::HN_WEB_HOST, is_valid_hn_item_id},
    domain::EntryKind,
    identity_norm::{
        extract_spotify_web_entity_and_id, is_wikipedia_host, normalize_identifier_scheme,
    },
    media_urls::{
        is_arxiv_host, is_valid_youtube_channel_id, is_valid_youtube_video_id, is_youtube_host,
    },
    url_guard::sanitize_public_http_href,
};

pub const EXTERNAL_ID_DISPLAY_ORDER: [&str; 24] = [
    "wikipedia",
    "arxiv",
    "doi",
    "hacker_news",
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
        "hacker_news" => "Hacker News",
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
        "arxiv" => is_arxiv_host(Some(host)),
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
            is_youtube_host(Some(host)) || matches!(host, "youtu.be" | "www.youtu.be")
        })
    } else if is_valid_youtube_channel_id(v) {
        Some(format!("https://www.youtube.com/channel/{v}"))
    } else if is_valid_youtube_video_id(v) {
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

fn format_hacker_news_external_url(v: &str) -> Option<String> {
    if is_valid_hn_item_id(v) {
        return Some(format!("https://news.ycombinator.com/item?id={v}"));
    }
    if is_http_url(v) {
        let parsed = url::Url::parse(v).ok()?;
        if parsed.scheme() != "https"
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return None;
        }
        let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
        if host != HN_WEB_HOST || parsed.path().trim_end_matches('/') != "/item" {
            return None;
        }
        let id = parsed
            .query_pairs()
            .find(|(k, _)| k == "id")
            .map(|(_, val)| val.trim().to_owned())
            .filter(|val| is_valid_hn_item_id(val))?;
        return Some(format!("https://news.ycombinator.com/item?id={id}"));
    }
    None
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
        "hacker_news" => format_hacker_news_external_url(v),
        "bandcamp" => validated_branded_https_url(v, |host| {
            host == "bandcamp.com" || host.ends_with(".bandcamp.com")
        }),
        "rym" => validated_branded_https_url(v, |host| {
            matches!(host, "rateyourmusic.com" | "www.rateyourmusic.com")
        }),
        "url" | "feed_url" => sanitize_public_http_href(v).map(str::to_owned),
        other => prefixed_external_url(other, v, kind),
    }
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
    }
}
