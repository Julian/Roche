use crate::{
    boards::extract_hn_item_id,
    document_meta::{normalize_doi, normalize_isbn},
    domain::{EntryIdentifier, EntryKind},
    identity::{
        canonicalize_sync_url, deterministic_catalog_entry_id, deterministic_title_entry_id,
    },
    media_urls::extract_arxiv_id,
    url_guard::decode_percent_utf8,
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
    "doi",
    "wikipedia",
    "feed_url",
    "url",
];

pub(crate) fn is_wikipedia_host(host: &str) -> bool {
    host == "wikipedia.org" || host.ends_with(".wikipedia.org")
}

pub(crate) fn extract_spotify_web_entity_and_id(raw: &str) -> Option<(String, String)> {
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
        "doi" => normalize_doi(trimmed).unwrap_or_else(|| trimmed.to_owned()),
        "isbn" => normalize_isbn(trimmed).unwrap_or_else(|| trimmed.to_owned()),
        "hacker_news" => extract_hn_item_id(trimmed).unwrap_or_else(|| trimmed.to_owned()),
        _ => trimmed.to_owned(),
    }
}

#[must_use]
pub fn normalize_spotify_identifier(kind: EntryKind, raw: &str) -> String {
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

#[must_use]
pub fn normalize_wikipedia_identifier(raw: &str) -> String {
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
    use super::*;

    #[test]
    fn test_normalize_wikipedia_identifier_rejects_spoofed_host() {
        assert_eq!(
            normalize_wikipedia_identifier("https://evilwikipedia.org/wiki/Fake"),
            "https://evilwikipedia.org/wiki/Fake"
        );
        assert_eq!(
            normalize_wikipedia_identifier("https://en.wikipedia.org/wiki/Stalker_(1979_film)"),
            "Stalker_(1979_film)"
        );
    }
}
