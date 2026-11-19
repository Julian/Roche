#[cfg(not(test))]
use std::sync::{Arc, LazyLock, Mutex};
use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    future::Future,
    pin::Pin,
    time::{Duration, Instant},
};

use serde_json::Value;
use url::Url;

use super::{html::urlencode, links::sanitize_non_local_href};
use crate::{
    db::{
        CatalogEntry, EntryDetailView, EntryIdentifier, EntryKind, UpsertEntryInput,
        strip_album_edition_suffix,
    },
    scheduler::FeedFetcher,
    service::RocheService,
};

const MAX_WIKIPEDIA_BYTES: usize = 128 * 1024;
const MAX_SPOTIFY_HTML_BYTES: usize = 2 * 1024 * 1024;
const MAX_SUMMARY_CHARS: usize = 600;
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
pub(in crate::web) struct WikipediaSearchHit {
    pub title: String,
    pub snippet: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(in crate::web) struct WikipediaArtistSummary {
    pub thumbnail_url: Option<String>,
    pub extract: Option<String>,
}

pub(in crate::web) trait ArtistMetadataFetcher: Send + Sync {
    fn fetch_text<'a>(
        &'a self,
        url: &'a Url,
        max_bytes: usize,
    ) -> Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>>;
}

#[cfg(not(test))]
impl ArtistMetadataFetcher for crate::content::http::SafeHttpClient {
    fn fetch_text<'a>(
        &'a self,
        url: &'a Url,
        max_bytes: usize,
    ) -> Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>> {
        Box::pin(async move { self.fetch_text(url, max_bytes).await.ok() })
    }
}

#[cfg(test)]
impl<T> ArtistMetadataFetcher for T
where
    T: Fn(&Url) -> Option<String> + Send + Sync,
{
    fn fetch_text<'a>(
        &'a self,
        url: &'a Url,
        _max_bytes: usize,
    ) -> Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>> {
        let res = self(url);
        Box::pin(async move { res })
    }
}

const MAX_ENRICH_ATTEMPT_CACHE: usize = 4_096;
#[cfg(not(test))]
const FAILED_ENRICH_TTL: Duration = Duration::from_mins(30);

#[derive(Debug, Default)]
pub(in crate::web) struct EnrichAttemptCache {
    entries: HashMap<String, Instant>,
}

impl EnrichAttemptCache {
    pub(in crate::web) fn should_attempt_and_mark(
        &mut self,
        key: String,
        now: Instant,
        ttl: Duration,
    ) -> bool {
        if let Some(&prev) = self.entries.get(&key)
            && now.saturating_duration_since(prev) < ttl
        {
            return false;
        }
        if self.entries.len() >= MAX_ENRICH_ATTEMPT_CACHE {
            self.entries.retain(|_, ts| now.saturating_duration_since(*ts) < ttl);
            if self.entries.len() >= MAX_ENRICH_ATTEMPT_CACHE {
                self.entries.clear();
            }
        }
        self.entries.insert(key, now);
        true
    }
}

#[cfg(not(test))]
static ENRICH_HTTP_CLIENT: LazyLock<Option<crate::content::http::SafeHttpClient>> =
    LazyLock::new(|| crate::content::http::SafeHttpClient::new(Duration::from_millis(2_500)).ok());

#[cfg(not(test))]
static ATTEMPTED_ARTIST_KEYS: LazyLock<Mutex<EnrichAttemptCache>> =
    LazyLock::new(|| Mutex::new(EnrichAttemptCache::default()));

#[cfg(not(test))]
pub(in crate::web) fn spawn_artist_enrichment_if_needed<F: FeedFetcher>(
    service: &Arc<RocheService<F>>,
    detail: &EntryDetailView,
) {
    if !should_attempt_enrichment(detail) {
        return;
    }
    let Some(client) = ENRICH_HTTP_CLIENT.as_ref() else {
        return;
    };
    let can_resolve_spotify = detail.entry.identifier("spotify").is_none()
        && detail.children.iter().any(|c| child_spotify_web_url(c).is_some());
    let attempt_key = format!("{}|sp={can_resolve_spotify}", detail.entry.id);
    {
        let Ok(mut cache) = ATTEMPTED_ARTIST_KEYS.lock() else {
            return;
        };
        if !cache.should_attempt_and_mark(attempt_key, Instant::now(), FAILED_ENRICH_TTL) {
            return;
        }
    }
    let svc = Arc::clone(service);
    let mut detail_bg = detail.clone();
    tokio::spawn(async move {
        let _ = tokio::time::timeout(
            Duration::from_secs(4),
            enrich_artist_detail_with_fetcher(&svc, &mut detail_bg, client),
        )
        .await;
    });
}

#[cfg(test)]
pub(in crate::web) const fn spawn_artist_enrichment_if_needed<F: FeedFetcher>(
    _service: &std::sync::Arc<RocheService<F>>,
    _detail: &EntryDetailView,
) {
}

pub(in crate::web) async fn enrich_artist_detail_with_fetcher<F, M>(
    service: &RocheService<F>,
    detail: &mut EntryDetailView,
    fetcher: &M,
) where
    F: FeedFetcher,
    M: ArtistMetadataFetcher,
{
    if !should_attempt_enrichment(detail) {
        return;
    }

    let missing_wiki = detail.entry.identifier("wikipedia").is_none();
    let missing_spotify = detail.entry.identifier("spotify").is_none();
    let missing_thumb =
        detail.entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href).is_none();
    let missing_summary = detail.entry.summary.as_deref().map(str::trim).is_none_or(str::is_empty);

    let spotify_fut = async {
        if missing_spotify {
            resolve_spotify_artist_from_children(&detail.entry.title, &detail.children, fetcher)
                .await
        } else {
            None
        }
    };
    let wiki_fut = async {
        if missing_wiki || missing_thumb || missing_summary {
            resolve_wikipedia_artist_bundle(detail, fetcher).await
        } else {
            (None, None)
        }
    };

    let (spotify_uri, (wiki_slug, wiki_summary)) = tokio::join!(spotify_fut, wiki_fut);

    let mut new_identifiers = Vec::new();
    if let Some(slug) = wiki_slug {
        new_identifiers.push(EntryIdentifier { scheme: "wikipedia".to_owned(), value: slug });
    }
    if let Some(uri) = spotify_uri {
        new_identifiers.push(EntryIdentifier { scheme: "spotify".to_owned(), value: uri });
    }
    let new_thumb =
        wiki_summary.as_ref().and_then(|s| s.thumbnail_url.clone()).filter(|_| missing_thumb);
    let new_extract = wiki_summary.and_then(|s| s.extract).filter(|_| missing_summary);

    if new_identifiers.is_empty() && new_thumb.is_none() && new_extract.is_none() {
        return;
    }

    let entry_id = detail.entry.id.clone();
    let input = UpsertEntryInput {
        id: Some(entry_id.clone()),
        kind: Some(EntryKind::Artist),
        title: detail.entry.title.clone(),
        summary: new_extract,
        thumbnail_url: new_thumb,
        identifiers: new_identifiers,
        ..UpsertEntryInput::default()
    };
    if service.catalog().upsert_entry(&input).await.is_ok()
        && let Ok(Some(refreshed)) = service.catalog().get_entry_detail(&entry_id).await
    {
        *detail = refreshed;
    }
}

fn should_attempt_enrichment(detail: &EntryDetailView) -> bool {
    let entry = &detail.entry;
    if entry.kind != EntryKind::Artist {
        return false;
    }
    let clean_title = entry.title.trim();
    if clean_title.is_empty() || clean_title.eq_ignore_ascii_case("various artists") {
        return false;
    }
    let missing_wiki = entry.identifier("wikipedia").is_none();
    let missing_spotify = entry.identifier("spotify").is_none()
        && detail.children.iter().any(|c| child_spotify_web_url(c).is_some());
    let missing_thumb = entry.thumbnail_url.as_deref().and_then(sanitize_non_local_href).is_none();
    let missing_summary = entry.summary.as_deref().map(str::trim).is_none_or(str::is_empty);
    missing_wiki || missing_spotify || missing_thumb || missing_summary
}

async fn resolve_spotify_artist_from_children<M: ArtistMetadataFetcher>(
    artist_name: &str,
    children: &[CatalogEntry],
    fetcher: &M,
) -> Option<String> {
    for child in children.iter().filter(|c| child_spotify_web_url(c).is_some()).take(4) {
        let Some(url) = child_spotify_web_url(child) else {
            continue;
        };
        let Some(html) = fetcher.fetch_text(&url, MAX_SPOTIFY_HTML_BYTES).await else {
            continue;
        };
        let allow_single_fallback = child.creators.len() <= 1;
        if let Some(uri) =
            extract_spotify_artist_uri_from_album_html(&html, artist_name, allow_single_fallback)
        {
            return Some(uri);
        }
    }
    None
}

fn child_spotify_web_url(child: &CatalogEntry) -> Option<Url> {
    let ident = child.identifiers.iter().find(|i| i.scheme.eq_ignore_ascii_case("spotify"))?;
    let ext = ident.external_url()?;
    let parsed = Url::parse(&ext).ok()?;
    let host = parsed.host_str()?.to_ascii_lowercase();
    if host != "open.spotify.com" && host != "play.spotify.com" {
        return None;
    }
    let path = parsed.path();
    (path.starts_with("/album/") || path.starts_with("/track/")).then_some(parsed)
}

async fn resolve_wikipedia_artist_bundle<M: ArtistMetadataFetcher>(
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

pub(in crate::web) fn existing_wikipedia_slug(entry: &CatalogEntry) -> Option<String> {
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
    let full = format!(
        "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=8&utf8=1&format=json&srsearch={}",
        urlencode(&srsearch)
    );
    Url::parse(&full).ok()
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

pub(in crate::web) fn encode_wiki_path_segment(slug: &str) -> String {
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

fn decode_percent_utf8(input: &str) -> String {
    if !input.contains('%') {
        return input.to_owned();
    }
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(hi), Some(lo)) =
                ((bytes[i + 1] as char).to_digit(16), (bytes[i + 2] as char).to_digit(16))
        {
            if let Ok(val) = u8::try_from((hi << 4) | lo) {
                out.push(val);
            }
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[must_use]
pub(in crate::web) fn parse_wikipedia_search_json(raw_json: &str) -> Vec<WikipediaSearchHit> {
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
pub(in crate::web) fn select_best_artist_wikipedia_slug(
    hits: &[WikipediaSearchHit],
    artist_name: &str,
    child_titles: &[String],
) -> Option<String> {
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

    let clean_snippet = decode_html_entities(&strip_html_tags(&hit.snippet));
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
pub(in crate::web) fn parse_wikipedia_artist_summary_json(
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
        .and_then(sanitize_non_local_href)
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

fn contains_word_sequence(haystack: &[&str], needle: &[&str]) -> bool {
    !needle.is_empty() && haystack.windows(needle.len()).any(|win| win == needle)
}

#[must_use]
pub(in crate::web) fn extract_spotify_artist_uri_from_album_html(
    html: &str,
    artist_name: &str,
    allow_single_fallback: bool,
) -> Option<String> {
    let target_norm = normalize_artist_tokens(artist_name);
    if !target_norm.is_empty()
        && let Some(id) = find_anchor_matching_artist_id(html, &target_norm)
    {
        return Some(format!("spotify:artist:{id}"));
    }

    if !allow_single_fallback {
        return None;
    }

    let mut unique_ids = HashSet::new();
    for marker in ["open.spotify.com/artist/", "href=\"/artist/", "href='/artist/"] {
        let mut rest = html;
        while let Some(idx) = rest.find(marker) {
            rest = &rest[idx + marker.len()..];
            let token: String = rest.chars().take_while(char::is_ascii_alphanumeric).collect();
            if is_valid_spotify_id(&token) {
                unique_ids.insert(token);
                if unique_ids.len() > 1 {
                    return None;
                }
            }
        }
    }
    unique_ids.into_iter().next().map(|id| format!("spotify:artist:{id}"))
}

fn find_anchor_matching_artist_id(html: &str, target_norm: &str) -> Option<String> {
    let mut search_from = 0;
    while let Some(rel_pos) = html[search_from..].find("/artist/") {
        let marker_pos = search_from + rel_pos;
        let id_start = marker_pos + "/artist/".len();
        let artist_id: String =
            html[id_start..].chars().take_while(char::is_ascii_alphanumeric).collect();
        search_from = id_start + artist_id.len();
        if !is_valid_spotify_id(&artist_id) {
            continue;
        }

        let before = &html[..marker_pos];
        let Some(tag_open_idx) = before.rfind('<') else {
            continue;
        };
        if before[tag_open_idx..].contains('>') {
            continue;
        }
        let after_lt = &before[tag_open_idx + 1..];
        let is_anchor = after_lt.as_bytes().first().is_some_and(|b| b.eq_ignore_ascii_case(&b'a'))
            && after_lt.as_bytes().get(1).is_some_and(u8::is_ascii_whitespace);
        if !is_anchor {
            continue;
        }

        let after_id = &html[search_from..];
        let Some(tag_end_rel) = after_id.find('>') else {
            continue;
        };
        let body = &after_id[tag_end_rel + 1..];
        let Some(close_rel) = find_anchor_close(body) else {
            continue;
        };
        let inner_html = &body[..close_rel];
        let clean_inner = decode_html_entities(&strip_html_tags(inner_html));
        if normalize_artist_tokens(&clean_inner) == target_norm {
            return Some(artist_id);
        }
    }
    None
}

fn find_anchor_close(s: &str) -> Option<usize> {
    let lower_idx = s.find("</a>");
    let upper_idx = s.find("</A>");
    match (lower_idx, upper_idx) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    }
}

fn is_valid_spotify_id(id: &str) -> bool {
    (15..=30).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric())
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

fn normalize_artist_tokens(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut prev_space = true;
    for ch in raw.chars().flat_map(char::to_lowercase) {
        if ch.is_alphanumeric() {
            out.push(ch);
            prev_space = false;
        } else if !prev_space && !matches!(ch, '\'' | '\u{2019}') {
            out.push(' ');
            prev_space = true;
        }
    }
    out.trim().to_owned()
}

fn strip_leading_the(s: &str) -> &str {
    s.strip_prefix("the ").unwrap_or(s)
}

fn contains_word(haystack: &str, word: &str) -> bool {
    haystack.split_whitespace().any(|tok| tok == word)
}

fn strip_html_tags(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_tag = false;
    for ch in raw.chars() {
        match ch {
            '<' => {
                in_tag = true;
                out.push(' ');
            }
            '>' => in_tag = false,
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }
    out
}

pub(in crate::web) fn decode_html_entities(raw: &str) -> String {
    if !raw.contains('&') {
        return raw.to_owned();
    }
    let mut out = String::with_capacity(raw.len());
    let mut rest = raw;
    while let Some(amp_idx) = rest.find('&') {
        out.push_str(&rest[..amp_idx]);
        let after_amp = &rest[amp_idx + 1..];
        if let Some(semi_idx) = after_amp.find(';').filter(|&i| i <= 12) {
            let entity = &after_amp[..semi_idx];
            if let Some(decoded) = decode_single_entity(entity) {
                out.push(decoded);
                rest = &after_amp[semi_idx + 1..];
                continue;
            }
        }
        out.push('&');
        rest = after_amp;
    }
    out.push_str(rest);
    out
}

fn decode_single_entity(entity: &str) -> Option<char> {
    if let Some(hex) = entity.strip_prefix("#x").or_else(|| entity.strip_prefix("#X")) {
        return u32::from_str_radix(hex, 16).ok().and_then(char::from_u32);
    }
    if let Some(dec) = entity.strip_prefix('#') {
        return dec.parse::<u32>().ok().and_then(char::from_u32);
    }
    match entity {
        "amp" => Some('&'),
        "quot" => Some('"'),
        "apos" => Some('\''),
        "lt" => Some('<'),
        "gt" => Some('>'),
        "nbsp" => Some(' '),
        _ => None,
    }
}

fn truncate_summary(raw: &str) -> String {
    if raw.chars().count() <= MAX_SUMMARY_CHARS {
        return raw.to_owned();
    }
    let collected: String = raw.chars().take(MAX_SUMMARY_CHARS).collect();
    if let Some((sentence_prefix, _)) = collected.rsplit_once(". ") {
        format!("{sentence_prefix}.")
    } else {
        format!("{}…", collected.trim_end())
    }
}
