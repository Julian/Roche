use std::fmt::Write as _;

use serde_json::Value;
use url::Url;

use super::{ArtistMetadataFetcher, normalize_artist_tokens, strip_leading_the, truncate_summary};
use crate::{
    content::HtmlSanitizer,
    db::{CatalogEntry, EntryDetailView, strip_album_edition_suffix},
    util::url_guard::{decode_percent_utf8, sanitize_public_http_href},
};

const MAX_WIKIPEDIA_BYTES: usize = 128 * 1024;
const MAX_LEAD_SENTENCE_CHARS: usize = 180;

const ARTIST_DISAMBIG_WORDS: &[&str] = &[
    "band",
    "musician",
    "musicians",
    "singer",
    "rapper",
    "group",
    "duo",
    "trio",
    "quartet",
    "ensemble",
    "orchestra",
    "composer",
    "dj",
    "producer",
    "vocalist",
    "guitarist",
    "pianist",
    "saxophonist",
    "trumpeter",
    "drummer",
    "bassist",
    "songwriter",
    "artist",
    "collective",
    "choir",
    "act",
];

const REJECTED_DISAMBIG_WORDS: &[&str] = &[
    "album",
    "song",
    "single",
    "ep",
    "film",
    "movie",
    "novel",
    "book",
    "television",
    "tv",
    "series",
    "episode",
    "tour",
    "soundtrack",
    "video",
    "game",
    "company",
    "magazine",
    "journal",
    "surname",
    "mythology",
    "planet",
    "asteroid",
    "painting",
    "disambiguation",
    "footballer",
    "politician",
    "municipality",
];

const MUSIC_BIO_WORDS: &[&str] = &[
    "band",
    "musician",
    "musicians",
    "singer",
    "songwriter",
    "rapper",
    "vocalist",
    "composer",
    "guitarist",
    "pianist",
    "saxophonist",
    "trumpeter",
    "drummer",
    "bassist",
    "producer",
    "dj",
    "duo",
    "trio",
    "quartet",
    "orchestra",
    "ensemble",
    "musical",
    "recording",
    "albums",
    "grammy",
    "discography",
    "rock",
    "jazz",
    "pop",
    "folk",
    "electronic",
    "metal",
    "punk",
    "country",
    "soul",
    "blues",
    "classical",
    "artist",
    "lyricist",
    "conductor",
    "collective",
];

const WORK_OPENING_PHRASES: &[&[&str]] = &[
    &["studio", "album"],
    &["live", "album"],
    &["compilation", "album"],
    &["debut", "album"],
    &["soundtrack", "album"],
    &["remix", "album"],
    &["double", "album"],
    &["concept", "album"],
    &["extended", "play"],
    &["is", "an", "album"],
    &["is", "a", "song"],
    &["is", "a", "single"],
    &["is", "an", "ep"],
    &["song", "written"],
    &["song", "recorded"],
    &["single", "recorded"],
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WikipediaSearchHit {
    pub title: String,
    pub snippet: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WikipediaArtistSummary {
    pub thumbnail_url: Option<String>,
    pub extract: Option<String>,
}

pub(super) async fn resolve_wikipedia_artist_bundle<M: ArtistMetadataFetcher>(
    detail: &EntryDetailView,
    fetcher: &M,
) -> (Option<String>, Option<WikipediaArtistSummary>) {
    if let Some(existing_slug) = existing_wikipedia_slug(&detail.entry) {
        let summary = fetch_wikipedia_artist_summary(&existing_slug, false, fetcher).await;
        return (None, summary);
    }

    let artist_name = detail.entry.title.trim();
    if artist_name.is_empty() {
        return (None, None);
    }
    let Some(search_url) = build_wikipedia_artist_search_url(artist_name) else {
        return (None, None);
    };
    let Some(search_json) = fetcher.fetch_text(&search_url, MAX_WIKIPEDIA_BYTES).await else {
        return (None, None);
    };
    let hits = parse_wikipedia_search_json(&search_json);
    let child_titles: Vec<String> = detail.children.iter().map(|c| c.title.clone()).collect();
    let Some(candidate_slug) = select_best_artist_wikipedia_slug(&hits, artist_name, &child_titles)
    else {
        return (None, None);
    };

    fetch_wikipedia_artist_summary(&candidate_slug, true, fetcher)
        .await
        .map_or((None, None), |summary| (Some(candidate_slug), Some(summary)))
}

fn existing_wikipedia_slug(entry: &CatalogEntry) -> Option<String> {
    let raw = entry.identifier("wikipedia")?.trim();
    if raw.is_empty() {
        return None;
    }
    let without_origin = Url::parse(raw).map_or_else(
        |_| raw.split('#').next().unwrap_or(raw).split('?').next().unwrap_or(raw).to_owned(),
        |parsed| parsed.path().strip_prefix("/wiki/").unwrap_or("").to_owned(),
    );
    let decoded = decode_percent_utf8(without_origin.trim_matches('/')).replace(' ', "_");
    (!decoded.is_empty()).then_some(decoded)
}

fn build_wikipedia_artist_search_url(artist_name: &str) -> Option<Url> {
    let clean_name: String =
        artist_name.chars().map(|c| if matches!(c, '"' | '(' | ')') { ' ' } else { c }).collect();
    let trimmed = clean_name.split_whitespace().collect::<Vec<_>>().join(" ");
    if trimmed.is_empty() {
        return None;
    }
    let srsearch = format!(
        "\"{trimmed}\" (band OR musician OR singer OR rapper OR composer OR songwriter OR vocalist OR duo OR group OR orchestra)"
    );
    Url::parse_with_params(
        "https://en.wikipedia.org/w/api.php",
        [
            ("action", "query"),
            ("list", "search"),
            ("srlimit", "8"),
            ("utf8", "1"),
            ("format", "json"),
            ("srsearch", srsearch.as_str()),
        ],
    )
    .ok()
}

async fn fetch_wikipedia_artist_summary<M: ArtistMetadataFetcher>(
    slug: &str,
    require_music_context: bool,
    fetcher: &M,
) -> Option<WikipediaArtistSummary> {
    let canonical = decode_percent_utf8(slug.split('#').next().unwrap_or(slug)).replace(' ', "_");
    let encoded_slug = encode_wiki_path_segment(&canonical);
    if encoded_slug.is_empty() {
        return None;
    }
    let url =
        Url::parse(&format!("https://en.wikipedia.org/api/rest_v1/page/summary/{encoded_slug}"))
            .ok()?;
    let raw_json = fetcher.fetch_text(&url, MAX_WIKIPEDIA_BYTES).await?;
    parse_wikipedia_artist_summary_json(&raw_json, require_music_context)
}

#[must_use]
pub fn encode_wiki_path_segment(slug: &str) -> String {
    let mut out = String::with_capacity(slug.len());
    for &b in slug.as_bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~' | b'(' | b')' | b':')
        {
            out.push(b as char);
        } else {
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

#[must_use]
pub fn parse_wikipedia_search_json(raw_json: &str) -> Vec<WikipediaSearchHit> {
    let Ok(root) = serde_json::from_str::<Value>(raw_json) else {
        return Vec::new();
    };
    let Some(arr) = root.get("query").and_then(|q| q.get("search")).and_then(Value::as_array)
    else {
        return Vec::new();
    };
    arr.iter()
        .filter_map(|item| {
            let title = item.get("title").and_then(Value::as_str)?.trim();
            if title.is_empty() {
                return None;
            }
            let snippet = item.get("snippet").and_then(Value::as_str).unwrap_or("");
            Some(WikipediaSearchHit { title: title.to_owned(), snippet: snippet.to_owned() })
        })
        .collect()
}

#[must_use]
pub fn select_best_artist_wikipedia_slug(
    hits: &[WikipediaSearchHit],
    artist_name: &str,
    child_titles: &[String],
) -> Option<String> {
    if artist_name.trim().eq_ignore_ascii_case("various artists") {
        return None;
    }
    let norm_artist = normalize_artist_tokens(artist_name);
    if norm_artist.is_empty() || hits.is_empty() {
        return None;
    }
    let norm_children: Vec<String> = child_titles
        .iter()
        .map(|t| normalize_artist_tokens(&strip_album_edition_suffix(t)))
        .filter(|t| t.len() >= 3)
        .collect();

    let mut best_hit: Option<&WikipediaSearchHit> = None;
    let mut best_score = 0;
    for hit in hits {
        let score = score_artist_wikipedia_hit(hit, &norm_artist, &norm_children);
        if score > best_score {
            best_score = score;
            best_hit = Some(hit);
        }
    }
    best_hit.map(|h| h.title.trim().replace(' ', "_")).filter(|slug| !slug.is_empty())
}

#[cfg(test)]
#[must_use]
pub fn score_artist_wikipedia_hit_for_artist(
    hit: &WikipediaSearchHit,
    artist_name: &str,
    child_titles: &[String],
) -> i32 {
    if artist_name.trim().eq_ignore_ascii_case("various artists") {
        return 0;
    }
    let norm_artist = normalize_artist_tokens(artist_name);
    if norm_artist.is_empty() {
        return 0;
    }
    let norm_children: Vec<String> = child_titles
        .iter()
        .map(|t| normalize_artist_tokens(&strip_album_edition_suffix(t)))
        .filter(|t| t.len() >= 3)
        .collect();
    score_artist_wikipedia_hit(hit, &norm_artist, &norm_children)
}

fn score_artist_wikipedia_hit(
    hit: &WikipediaSearchHit,
    norm_artist: &str,
    norm_children: &[String],
) -> i32 {
    let raw_title = hit.title.trim();
    if raw_title.starts_with("List of ")
        || raw_title.starts_with("Category:")
        || raw_title.starts_with("Draft:")
    {
        return 0;
    }
    let (base_title, disambiguator) = split_wikipedia_disambiguator(raw_title);
    if base_title.to_lowercase().ends_with(" discography") {
        return 0;
    }

    let norm_base = normalize_artist_tokens(base_title);
    let exact_base = norm_base == norm_artist;
    let the_equivalent = strip_leading_the(&norm_base) == strip_leading_the(norm_artist)
        && !strip_leading_the(&norm_base).is_empty();
    if !exact_base && !the_equivalent {
        return 0;
    }

    let norm_disambig = normalize_artist_tokens(disambiguator);
    if REJECTED_DISAMBIG_WORDS.iter().any(|w| contains_word(&norm_disambig, w)) {
        return 0;
    }

    let clean_snippet = HtmlSanitizer::plain_text_excerpt(&hit.snippet, 1_024);
    let norm_snippet = normalize_artist_tokens(&clean_snippet);
    if disambiguator.is_empty() && is_work_opening_clause("", &clean_snippet) {
        return 0;
    }

    let has_artist_disambig =
        ARTIST_DISAMBIG_WORDS.iter().any(|w| contains_word(&norm_disambig, w));
    let has_music_snippet = MUSIC_BIO_WORDS.iter().any(|w| contains_word(&norm_snippet, w))
        || norm_snippet.contains("hip hop");
    if !has_artist_disambig && !has_music_snippet {
        return 0;
    }

    let mut score = if exact_base { 24 } else { 20 };
    if has_artist_disambig {
        score += 18;
    }
    if has_music_snippet {
        score += 8;
    }
    if norm_children.iter().any(|child| norm_snippet.contains(child.as_str())) {
        score += 8;
    }
    score
}

#[must_use]
pub fn parse_wikipedia_artist_summary_json(
    raw_json: &str,
    require_music_context: bool,
) -> Option<WikipediaArtistSummary> {
    let root = serde_json::from_str::<Value>(raw_json).ok()?;
    let page_type = root.get("type").and_then(Value::as_str).unwrap_or("standard");
    if matches!(page_type, "disambiguation" | "no-extract") {
        return None;
    }

    let description = root.get("description").and_then(Value::as_str).unwrap_or("");
    let extract_raw = root.get("extract").and_then(Value::as_str).unwrap_or("").trim();

    if require_music_context {
        if is_work_opening_clause(description, extract_raw) {
            return None;
        }
        let norm_lead = normalize_artist_tokens(&format!("{description} {extract_raw}"));
        let is_music_bio = MUSIC_BIO_WORDS.iter().any(|w| contains_word(&norm_lead, w))
            || norm_lead.contains("hip hop")
            || norm_lead.contains("multi instrumentalist");
        if !is_music_bio {
            return None;
        }
    }

    let thumbnail_url = root
        .get("thumbnail")
        .and_then(|t| t.get("source"))
        .and_then(Value::as_str)
        .and_then(sanitize_public_http_href)
        .filter(|u| u.to_ascii_lowercase().starts_with("https://"))
        .map(ToOwned::to_owned);
    let extract = (!extract_raw.is_empty()).then(|| truncate_summary(extract_raw));
    Some(WikipediaArtistSummary { thumbnail_url, extract })
}

fn is_work_opening_clause(description: &str, extract: &str) -> bool {
    let norm_desc = normalize_artist_tokens(description);
    if !norm_desc.is_empty() {
        let desc_words: Vec<&str> = norm_desc.split_whitespace().collect();
        if WORK_OPENING_PHRASES.iter().any(|phrase| contains_word_sequence(&desc_words, phrase))
            || matches!(
                desc_words.last().copied(),
                Some("album" | "song" | "single" | "ep" | "soundtrack" | "film")
            )
        {
            return true;
        }
    }

    let first_sentence = extract.split(". ").next().unwrap_or(extract);
    let bounded: String = first_sentence.chars().take(MAX_LEAD_SENTENCE_CHARS).collect();
    let norm_open = normalize_artist_tokens(&bounded);
    let open_words: Vec<&str> = norm_open.split_whitespace().collect();
    WORK_OPENING_PHRASES.iter().any(|phrase| contains_word_sequence(&open_words, phrase))
}

fn split_wikipedia_disambiguator(raw_title: &str) -> (&str, &str) {
    let trimmed = raw_title.trim();
    if let Some(without_close) = trimmed.strip_suffix(')')
        && let Some((base, disambig)) = without_close.rsplit_once(" (")
        && !base.trim().is_empty()
    {
        return (base.trim(), disambig.trim());
    }
    (trimmed, "")
}

fn contains_word(haystack: &str, word: &str) -> bool {
    haystack.split_whitespace().any(|tok| tok == word)
}

fn contains_word_sequence(haystack: &[&str], needle: &[&str]) -> bool {
    !needle.is_empty() && haystack.windows(needle.len()).any(|win| win == needle)
}
