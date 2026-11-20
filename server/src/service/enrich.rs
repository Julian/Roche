use std::{
    collections::{HashMap, HashSet},
    fmt::Write as _,
    future::Future,
    pin::Pin,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use serde_json::Value;
use url::Url;

use super::RocheService;
use crate::{
    content::http::SafeHttpClient,
    db::{
        CatalogEntry, EntryDetailView, EntryIdentifier, UpsertEntryInput,
        strip_album_edition_suffix,
    },
    domain::EntryKind,
    util::url_guard::{decode_percent_utf8, sanitize_public_http_href},
};

const MAX_WIKIPEDIA_BYTES: usize = 128 * 1024;
const MAX_SPOTIFY_HTML_BYTES: usize = 2 * 1024 * 1024;
const MAX_SUMMARY_CHARS: usize = 600;
const MAX_LEAD_SENTENCE_CHARS: usize = 180;
const MAX_ENRICH_ATTEMPT_CACHE: usize = 4_096;
pub const MAX_CONCURRENT_ENRICH_TASKS: usize = 4;
const FAILED_ENRICH_TTL: Duration = Duration::from_mins(30);

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

pub trait ArtistMetadataFetcher: Send + Sync {
    fn fetch_text<'a>(
        &'a self,
        url: &'a Url,
        max_bytes: usize,
    ) -> Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>>;
}

impl ArtistMetadataFetcher for SafeHttpClient {
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

#[derive(Debug, Default)]
pub struct EnrichAttemptCache {
    entries: HashMap<String, Instant>,
}

impl EnrichAttemptCache {
    pub fn should_attempt_and_mark(&mut self, key: String, now: Instant, ttl: Duration) -> bool {
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

/// Instance-scoped background artist enrichment coordinator held on [`RocheService`]
/// (replacing process-global `LazyLock` singletons so tests stay isolated and deterministic).
#[derive(Debug)]
pub struct ArtistEnricher {
    enabled: bool,
    http_client: Option<SafeHttpClient>,
    attempt_cache: Mutex<EnrichAttemptCache>,
    semaphore: Arc<tokio::sync::Semaphore>,
    failed_ttl: Duration,
}

impl ArtistEnricher {
    #[must_use]
    pub fn new(enabled: bool) -> Self {
        let http_client =
            if enabled { SafeHttpClient::new(Duration::from_millis(2_500)).ok() } else { None };
        Self {
            enabled,
            http_client,
            attempt_cache: Mutex::new(EnrichAttemptCache::default()),
            semaphore: Arc::new(tokio::sync::Semaphore::new(MAX_CONCURRENT_ENRICH_TASKS)),
            failed_ttl: FAILED_ENRICH_TTL,
        }
    }

    #[must_use]
    pub fn default_for_runtime() -> Self {
        Self::new(!cfg!(test))
    }

    /// Reserves a bounded concurrency permit and records `attempt_key` in the TTL cache
    /// only after acquiring a permit, so back-pressured requests remain retryable once slots free.
    pub fn try_reserve_slot(
        &self,
        attempt_key: String,
        now: Instant,
        ttl: Duration,
    ) -> Option<tokio::sync::OwnedSemaphorePermit> {
        let permit = Arc::clone(&self.semaphore).try_acquire_owned().ok()?;
        let should_attempt = {
            let Ok(mut guard) = self.attempt_cache.lock() else {
                return None;
            };
            guard.should_attempt_and_mark(attempt_key, now, ttl)
        };
        should_attempt.then_some(permit)
    }

    #[cfg(test)]
    #[must_use]
    pub fn available_permits(&self) -> usize {
        self.semaphore.available_permits()
    }
}

pub struct EnrichService<'a> {
    pub(super) service: &'a RocheService,
}

impl EnrichService<'_> {
    pub fn spawn_artist_enrichment_if_needed(&self, detail: &EntryDetailView) {
        let enricher = &self.service.artist_enricher;
        if !enricher.enabled || !should_attempt_enrichment(detail) {
            return;
        }
        if enricher.http_client.is_none() {
            return;
        }
        let can_resolve_spotify = detail.entry.identifier("spotify").is_none()
            && detail.children.iter().any(|c| child_spotify_web_url(c).is_some());
        let attempt_key = format!("{}|sp={can_resolve_spotify}", detail.entry.id);
        let Some(permit) =
            enricher.try_reserve_slot(attempt_key, Instant::now(), enricher.failed_ttl)
        else {
            return;
        };
        let svc = self.service.clone();
        let mut detail_bg = detail.clone();
        tokio::spawn(async move {
            let _permit = permit;
            let Some(client) = svc.artist_enricher.http_client.as_ref() else {
                return;
            };
            let _ = tokio::time::timeout(
                Duration::from_secs(4),
                enrich_artist_detail_with_fetcher(&svc, &mut detail_bg, client),
            )
            .await;
        });
    }

    pub fn spawn_album_tracklist_enrichment_if_needed(&self, detail: &EntryDetailView) {
        let enricher = &self.service.artist_enricher;
        if !enricher.enabled || !should_attempt_album_tracklist_enrichment(detail) {
            return;
        }
        if enricher.http_client.is_none() {
            return;
        }
        let attempt_key = format!("album-tracks|{}", detail.entry.id);
        let Some(permit) =
            enricher.try_reserve_slot(attempt_key, Instant::now(), enricher.failed_ttl)
        else {
            return;
        };
        let svc = self.service.clone();
        let mut detail_bg = detail.clone();
        tokio::spawn(async move {
            let _permit = permit;
            let Some(client) = svc.artist_enricher.http_client.as_ref() else {
                return;
            };
            let _ = tokio::time::timeout(
                Duration::from_secs(4),
                enrich_album_tracklist_with_fetcher(&svc, &mut detail_bg, client),
            )
            .await;
        });
    }
}

pub async fn enrich_artist_detail_with_fetcher<M>(
    service: &RocheService,
    detail: &mut EntryDetailView,
    fetcher: &M,
) where
    M: ArtistMetadataFetcher,
{
    if !should_attempt_enrichment(detail) {
        return;
    }

    let missing_wiki = detail.entry.identifier("wikipedia").is_none();
    let missing_spotify = detail.entry.identifier("spotify").is_none();
    let missing_thumb =
        detail.entry.thumbnail_url.as_deref().and_then(sanitize_public_http_href).is_none();
    let missing_summary = detail.entry.summary.as_deref().map(str::trim).is_none_or(str::is_empty);
    let missing_discog = detail.entry.metadata.get("discography").is_none();

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
    let discog_fut = async {
        if missing_discog {
            resolve_itunes_artist_discography(&detail.entry.title, fetcher).await
        } else {
            Vec::new()
        }
    };

    let (spotify_uri, (wiki_slug, wiki_summary), discog) =
        tokio::join!(spotify_fut, wiki_fut, discog_fut);

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
    let new_metadata = if discog.is_empty() {
        None
    } else {
        let mut merged = match &detail.entry.metadata {
            Value::Object(map) => map.clone(),
            _ => serde_json::Map::new(),
        };
        merged.insert("discography".to_owned(), Value::Array(serialize_discography(&discog)));
        Some(Value::Object(merged))
    };

    if new_identifiers.is_empty()
        && new_thumb.is_none()
        && new_extract.is_none()
        && new_metadata.is_none()
    {
        return;
    }

    let entry_id = detail.entry.id.clone();
    let input = UpsertEntryInput {
        id: Some(entry_id.clone()),
        kind: Some(EntryKind::Artist),
        title: detail.entry.title.clone(),
        summary: new_extract,
        thumbnail_url: new_thumb,
        metadata: new_metadata,
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
    let missing_thumb =
        entry.thumbnail_url.as_deref().and_then(sanitize_public_http_href).is_none();
    let missing_summary = entry.summary.as_deref().map(str::trim).is_none_or(str::is_empty);
    let missing_discog = entry.metadata.get("discography").is_none();
    missing_wiki || missing_spotify || missing_thumb || missing_summary || missing_discog
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

fn contains_word_sequence(haystack: &[&str], needle: &[&str]) -> bool {
    !needle.is_empty() && haystack.windows(needle.len()).any(|win| win == needle)
}

#[must_use]
pub fn extract_spotify_artist_uri_from_album_html(
    html: &str,
    artist_name: &str,
    allow_single_fallback: bool,
) -> Option<String> {
    if artist_name.trim().eq_ignore_ascii_case("various artists") {
        return None;
    }
    let cleaned_html = strip_script_and_style_blocks(html);
    let target_norm = normalize_artist_tokens(artist_name);
    if !target_norm.is_empty()
        && let Some(id) = find_anchor_matching_artist_id(&cleaned_html, &target_norm)
    {
        return Some(format!("spotify:artist:{id}"));
    }

    if !allow_single_fallback {
        return None;
    }

    let mut unique_ids = HashSet::new();
    for marker in ["open.spotify.com/artist/", "href=\"/artist/", "href='/artist/"] {
        let mut rest = cleaned_html.as_str();
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

fn strip_script_and_style_blocks(html: &str) -> String {
    let lower = html.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let mut out = String::with_capacity(html.len());
    let mut pos = 0;
    while pos < html.len() {
        let rest = &lower[pos..];
        let found = match (rest.find("<script"), rest.find("<style")) {
            (Some(script_off), Some(style_off)) => {
                if script_off <= style_off {
                    Some((pos + script_off, "<script", "</script>"))
                } else {
                    Some((pos + style_off, "<style", "</style>"))
                }
            }
            (Some(script_off), None) => Some((pos + script_off, "<script", "</script>")),
            (None, Some(style_off)) => Some((pos + style_off, "<style", "</style>")),
            (None, None) => None,
        };
        let Some((tag_start, open_tag, close_tag)) = found else {
            out.push_str(&html[pos..]);
            break;
        };
        let after_name = tag_start + open_tag.len();
        let is_boundary = bytes
            .get(after_name)
            .is_some_and(|b| b.is_ascii_whitespace() || matches!(b, b'>' | b'/'));
        if !is_boundary {
            out.push_str(&html[pos..after_name]);
            pos = after_name;
            continue;
        }
        out.push_str(&html[pos..tag_start]);
        let Some(rel_close) = lower[after_name..].find(close_tag) else {
            out.push_str(&html[tag_start..]);
            break;
        };
        out.push(' ');
        pos = after_name + rel_close + close_tag.len();
    }
    out
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

#[must_use]
pub fn decode_html_entities(raw: &str) -> String {
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

/* --- Album Tracklist Extraction & Lazy Enrichment --- */

const MAX_ITUNES_JSON_BYTES: usize = 512 * 1024;
const MAX_ALBUM_TRACKS: usize = 120;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlbumTrack {
    pub number: u32,
    pub disc: u32,
    pub title: String,
    pub duration_ms: Option<u64>,
    pub artists: Vec<String>,
    pub spotify_uri: Option<String>,
    pub entry_id: Option<String>,
}

impl AlbumTrack {
    #[must_use]
    pub fn spotify_web_url(&self) -> Option<String> {
        let uri = self.spotify_uri.as_deref()?.trim();
        let id = uri.strip_prefix("spotify:track:")?;
        let valid =
            !id.is_empty() && id.len() <= 64 && id.bytes().all(|b| b.is_ascii_alphanumeric());
        valid.then(|| format!("https://open.spotify.com/track/{id}"))
    }
}

/// Returns the album's tracks from either explicit child `Song` entries or cached `metadata.tracks`.
#[must_use]
pub fn extract_album_tracks(detail: &EntryDetailView) -> Vec<AlbumTrack> {
    let song_children: Vec<&CatalogEntry> =
        detail.children.iter().filter(|c| c.kind == EntryKind::Song).collect();
    if !song_children.is_empty() {
        return song_children
            .into_iter()
            .enumerate()
            .map(|(idx, child)| {
                let disc = child
                    .metadata
                    .get("disc_number")
                    .and_then(Value::as_u64)
                    .and_then(|v| u32::try_from(v).ok())
                    .filter(|&d| d > 0)
                    .unwrap_or(1);
                let number = child
                    .metadata
                    .get("track_number")
                    .and_then(Value::as_u64)
                    .and_then(|v| u32::try_from(v).ok())
                    .filter(|&n| n > 0)
                    .unwrap_or_else(|| u32::try_from(idx + 1).unwrap_or(1));
                let duration_ms =
                    child.metadata.get("duration_ms").and_then(Value::as_u64).filter(|&ms| ms > 0);
                let spotify_uri = child
                    .identifier("spotify")
                    .map(str::trim)
                    .filter(|s| s.starts_with("spotify:track:"))
                    .map(ToOwned::to_owned);
                AlbumTrack {
                    number,
                    disc,
                    title: child.title.clone(),
                    duration_ms,
                    artists: child.creators.clone(),
                    spotify_uri,
                    entry_id: Some(child.id.clone()),
                }
            })
            .collect();
    }

    let Some(arr) = detail.entry.metadata.get("tracks").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut tracks = Vec::with_capacity(arr.len().min(MAX_ALBUM_TRACKS));
    for (idx, item) in arr.iter().take(MAX_ALBUM_TRACKS).enumerate() {
        let Some(title) = item.get("title").and_then(Value::as_str).map(str::trim) else {
            continue;
        };
        if title.is_empty() {
            continue;
        }
        let disc = item
            .get("disc")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&d| d > 0)
            .unwrap_or(1);
        let number = item
            .get("number")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&n| n > 0)
            .unwrap_or_else(|| u32::try_from(idx + 1).unwrap_or(1));
        let duration_ms = item.get("duration_ms").and_then(Value::as_u64).filter(|&ms| ms > 0);
        let artists = item.get("artists").and_then(Value::as_array).map_or_default(|a| {
            a.iter()
                .filter_map(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned)
                .collect()
        });
        let spotify_uri = item
            .get("spotify_uri")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| s.starts_with("spotify:track:"))
            .map(ToOwned::to_owned);
        tracks.push(AlbumTrack {
            number,
            disc,
            title: title.to_owned(),
            duration_ms,
            artists,
            spotify_uri,
            entry_id: None,
        });
    }
    tracks
}

fn should_attempt_album_tracklist_enrichment(detail: &EntryDetailView) -> bool {
    if detail.entry.kind != EntryKind::Album {
        return false;
    }
    if !extract_album_tracks(detail).is_empty() {
        return false;
    }
    extract_spotify_album_id(&detail.entry).is_some()
        || extract_apple_music_album_id(&detail.entry).is_some()
}

pub async fn enrich_album_tracklist_with_fetcher<M>(
    service: &RocheService,
    detail: &mut EntryDetailView,
    fetcher: &M,
) where
    M: ArtistMetadataFetcher,
{
    if !should_attempt_album_tracklist_enrichment(detail) {
        return;
    }

    let mut tracks = if let Some(sp_id) = extract_spotify_album_id(&detail.entry)
        && let Ok(url) = Url::parse(&format!("https://open.spotify.com/embed/album/{sp_id}"))
        && let Some(html) = fetcher.fetch_text(&url, MAX_SPOTIFY_HTML_BYTES).await
    {
        parse_spotify_embed_tracks(&html)
    } else {
        Vec::new()
    };

    if tracks.is_empty()
        && let Some(am_id) = extract_apple_music_album_id(&detail.entry)
        && let Ok(url) =
            Url::parse(&format!("https://itunes.apple.com/lookup?id={am_id}&entity=song"))
        && let Some(json_str) = fetcher.fetch_text(&url, MAX_ITUNES_JSON_BYTES).await
    {
        tracks = parse_itunes_lookup_tracks(&json_str);
    }

    if tracks.is_empty() {
        return;
    }

    let serialized: Vec<Value> = tracks
        .iter()
        .map(|t| {
            let mut obj = serde_json::Map::new();
            obj.insert("number".to_owned(), Value::from(t.number));
            obj.insert("disc".to_owned(), Value::from(t.disc));
            obj.insert("title".to_owned(), Value::from(t.title.clone()));
            if let Some(ms) = t.duration_ms {
                obj.insert("duration_ms".to_owned(), Value::from(ms));
            }
            if !t.artists.is_empty() {
                obj.insert("artists".to_owned(), Value::from(t.artists.clone()));
            }
            if let Some(uri) = &t.spotify_uri {
                obj.insert("spotify_uri".to_owned(), Value::from(uri.clone()));
            }
            Value::Object(obj)
        })
        .collect();

    let mut merged_meta = match &detail.entry.metadata {
        Value::Object(map) => map.clone(),
        _ => serde_json::Map::new(),
    };
    merged_meta.insert("tracks".to_owned(), Value::Array(serialized));

    let entry_id = detail.entry.id.clone();
    let input = UpsertEntryInput {
        id: Some(entry_id.clone()),
        kind: Some(EntryKind::Album),
        title: detail.entry.title.clone(),
        metadata: Some(Value::Object(merged_meta)),
        ..UpsertEntryInput::default()
    };
    if service.catalog().upsert_entry(&input).await.is_ok()
        && let Ok(Some(refreshed)) = service.catalog().get_entry_detail(&entry_id).await
    {
        *detail = refreshed;
    }
}

fn extract_spotify_album_id(entry: &CatalogEntry) -> Option<String> {
    let raw = entry.identifier("spotify")?.trim();
    let owned = if let Some(rest) = raw.strip_prefix("spotify:album:") {
        rest.to_owned()
    } else if let Ok(url) = Url::parse(raw) {
        url.path().strip_prefix("/album/")?.split('/').next()?.to_owned()
    } else {
        return None;
    };
    let clean = owned.split('?').next().unwrap_or(&owned).trim();
    let valid =
        !clean.is_empty() && clean.len() <= 64 && clean.bytes().all(|b| b.is_ascii_alphanumeric());
    valid.then(|| clean.to_owned())
}

fn extract_apple_music_album_id(entry: &CatalogEntry) -> Option<String> {
    let raw = entry.identifier("apple_music")?.trim();
    let clean = raw.rsplit('/').next().unwrap_or(raw).split('?').next().unwrap_or(raw).trim();
    let valid = !clean.is_empty() && clean.len() <= 32 && clean.bytes().all(|b| b.is_ascii_digit());
    valid.then(|| clean.to_owned())
}

#[must_use]
pub fn parse_spotify_embed_tracks(html: &str) -> Vec<AlbumTrack> {
    let Some(script_idx) = html.find("id=\"__NEXT_DATA__\"") else {
        return Vec::new();
    };
    let after_id = &html[script_idx..];
    let Some(open_end) = after_id.find('>') else {
        return Vec::new();
    };
    let json_start = &after_id[open_end + 1..];
    let Some(close_idx) = json_start.find("</script>") else {
        return Vec::new();
    };
    let Ok(val) = serde_json::from_str::<Value>(json_start[..close_idx].trim()) else {
        return Vec::new();
    };
    let Some(list) =
        val.pointer("/props/pageProps/state/data/entity/trackList").and_then(Value::as_array)
    else {
        return Vec::new();
    };

    let mut out = Vec::with_capacity(list.len().min(MAX_ALBUM_TRACKS));
    for (idx, item) in list.iter().take(MAX_ALBUM_TRACKS).enumerate() {
        let Some(title) = item.get("title").and_then(Value::as_str).map(str::trim) else {
            continue;
        };
        if title.is_empty() {
            continue;
        }
        let duration_ms = item.get("duration").and_then(Value::as_u64).filter(|&ms| ms > 0);
        let subtitle = item.get("subtitle").and_then(Value::as_str).unwrap_or("");
        let artists: Vec<String> = subtitle
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ToOwned::to_owned)
            .collect();
        let spotify_uri = item
            .get("uri")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| s.starts_with("spotify:track:"))
            .map(ToOwned::to_owned);
        out.push(AlbumTrack {
            number: u32::try_from(idx + 1).unwrap_or(1),
            disc: 1,
            title: title.to_owned(),
            duration_ms,
            artists,
            spotify_uri,
            entry_id: None,
        });
    }
    out
}

#[must_use]
pub fn parse_itunes_lookup_tracks(json_str: &str) -> Vec<AlbumTrack> {
    let Ok(val) = serde_json::from_str::<Value>(json_str) else {
        return Vec::new();
    };
    let Some(results) = val.get("results").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for item in results {
        if item.get("wrapperType").and_then(Value::as_str) != Some("track") {
            continue;
        }
        if item.get("kind").and_then(Value::as_str).is_some_and(|k| k != "song") {
            continue;
        }
        let Some(title) = item.get("trackName").and_then(Value::as_str).map(str::trim) else {
            continue;
        };
        if title.is_empty() {
            continue;
        }
        let disc = item
            .get("discNumber")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&d| d > 0)
            .unwrap_or(1);
        let number = item
            .get("trackNumber")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&n| n > 0)
            .unwrap_or_else(|| u32::try_from(out.len() + 1).unwrap_or(1));
        let duration_ms = item.get("trackTimeMillis").and_then(Value::as_u64).filter(|&ms| ms > 0);
        let artists = item
            .get("artistName")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map_or_default(|a| vec![a.to_owned()]);
        out.push(AlbumTrack {
            number,
            disc,
            title: title.to_owned(),
            duration_ms,
            artists,
            spotify_uri: None,
            entry_id: None,
        });
        if out.len() >= MAX_ALBUM_TRACKS {
            break;
        }
    }
    out.sort_by_key(|t| (t.disc, t.number));
    out
}

/* --- Artist External Discography Enrichment --- */

const MAX_DISCOGRAPHY_ALBUMS: usize = 40;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscographyAlbum {
    pub title: String,
    pub release_year: Option<i32>,
    pub thumbnail_url: Option<String>,
    pub apple_music_id: Option<String>,
}

#[must_use]
pub fn normalize_album_match_key(title: &str) -> String {
    let stripped = strip_album_edition_suffix(title);
    let norm = normalize_artist_tokens(&stripped);
    strip_leading_the(&norm).to_owned()
}

#[must_use]
pub fn extract_artist_discography(entry: &CatalogEntry) -> Vec<DiscographyAlbum> {
    let Some(arr) = entry.metadata.get("discography").and_then(Value::as_array) else {
        return Vec::new();
    };
    arr.iter()
        .take(MAX_DISCOGRAPHY_ALBUMS)
        .filter_map(|item| {
            let title = item.get("title").and_then(Value::as_str).map(str::trim)?;
            if title.is_empty() {
                return None;
            }
            let release_year = item
                .get("release_year")
                .and_then(Value::as_i64)
                .and_then(|y| i32::try_from(y).ok())
                .filter(|&y| (1900..=2100).contains(&y));
            let thumbnail_url = item
                .get("thumbnail_url")
                .and_then(Value::as_str)
                .and_then(sanitize_public_http_href)
                .map(ToOwned::to_owned);
            let apple_music_id = item
                .get("apple_music_id")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
                .map(ToOwned::to_owned);
            Some(DiscographyAlbum {
                title: title.to_owned(),
                release_year,
                thumbnail_url,
                apple_music_id,
            })
        })
        .collect()
}

fn serialize_discography(albums: &[DiscographyAlbum]) -> Vec<Value> {
    albums
        .iter()
        .map(|a| {
            let mut obj = serde_json::Map::new();
            obj.insert("title".to_owned(), Value::from(a.title.clone()));
            if let Some(yr) = a.release_year {
                obj.insert("release_year".to_owned(), Value::from(yr));
            }
            if let Some(thumb) = &a.thumbnail_url {
                obj.insert("thumbnail_url".to_owned(), Value::from(thumb.clone()));
            }
            if let Some(am_id) = &a.apple_music_id {
                obj.insert("apple_music_id".to_owned(), Value::from(am_id.clone()));
            }
            Value::Object(obj)
        })
        .collect()
}

async fn resolve_itunes_artist_discography<M: ArtistMetadataFetcher>(
    artist_name: &str,
    fetcher: &M,
) -> Vec<DiscographyAlbum> {
    let trimmed = artist_name.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let encoded: String = url::form_urlencoded::byte_serialize(trimmed.as_bytes()).collect();
    let url_str = format!(
        "https://itunes.apple.com/search?term={encoded}&entity=album&attribute=artistTerm&limit=50",
    );
    let Ok(url) = Url::parse(&url_str) else {
        return Vec::new();
    };
    let Some(json_str) = fetcher.fetch_text(&url, MAX_ITUNES_JSON_BYTES).await else {
        return Vec::new();
    };
    parse_itunes_artist_discography(&json_str, trimmed)
}

#[must_use]
pub fn parse_itunes_artist_discography(json_str: &str, artist_name: &str) -> Vec<DiscographyAlbum> {
    let Ok(val) = serde_json::from_str::<Value>(json_str) else {
        return Vec::new();
    };
    let Some(results) = val.get("results").and_then(Value::as_array) else {
        return Vec::new();
    };
    let target_artist = strip_leading_the(&normalize_artist_tokens(artist_name)).to_owned();
    if target_artist.is_empty() {
        return Vec::new();
    }

    let mut by_key: HashMap<String, DiscographyAlbum> = HashMap::new();
    for item in results {
        if item.get("wrapperType").and_then(Value::as_str) != Some("collection") {
            continue;
        }
        if item.get("collectionType").and_then(Value::as_str).is_some_and(|t| t != "Album") {
            continue;
        }
        let Some(found_artist) = item.get("artistName").and_then(Value::as_str) else {
            continue;
        };
        if strip_leading_the(&normalize_artist_tokens(found_artist)) != target_artist {
            continue;
        }
        let Some(raw_title) = item.get("collectionName").and_then(Value::as_str).map(str::trim)
        else {
            continue;
        };
        let lower_title = raw_title.to_ascii_lowercase();
        if raw_title.is_empty()
            || lower_title.ends_with("- single")
            || lower_title.ends_with("- ep")
            || lower_title.contains("karaoke")
        {
            continue;
        }
        let clean_title = strip_album_edition_suffix(raw_title);
        let display_title = if clean_title.is_empty() { raw_title.to_owned() } else { clean_title };
        let key = normalize_album_match_key(&display_title);
        if key.is_empty() {
            continue;
        }
        let release_year = item
            .get("releaseDate")
            .and_then(Value::as_str)
            .and_then(|d| d.get(..4))
            .and_then(|y| y.parse::<i32>().ok())
            .filter(|&y| (1900..=2100).contains(&y));
        let thumbnail_url = item
            .get("artworkUrl100")
            .and_then(Value::as_str)
            .map(|u| u.replace("100x100bb", "600x600bb"))
            .and_then(|u| sanitize_public_http_href(&u).map(ToOwned::to_owned));
        let apple_music_id =
            item.get("collectionId").and_then(Value::as_u64).map(|id| id.to_string());

        let candidate =
            DiscographyAlbum { title: display_title, release_year, thumbnail_url, apple_music_id };
        match by_key.get_mut(&key) {
            Some(existing) => {
                // Keep earliest original release year and prefer entries with artwork
                let earlier = match (candidate.release_year, existing.release_year) {
                    (Some(cy), Some(ey)) => cy < ey,
                    (Some(_), None) => true,
                    _ => false,
                };
                if earlier {
                    existing.release_year = candidate.release_year;
                }
                if existing.thumbnail_url.is_none() && candidate.thumbnail_url.is_some() {
                    existing.thumbnail_url = candidate.thumbnail_url;
                }
            }
            None => {
                by_key.insert(key, candidate);
            }
        }
    }

    let mut out: Vec<DiscographyAlbum> = by_key.into_values().collect();
    out.sort_by(|a, b| {
        b.release_year
            .cmp(&a.release_year)
            .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
    });
    out.truncate(MAX_DISCOGRAPHY_ALBUMS);
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_artist_enricher_instances_have_isolated_state_and_ttl() {
        let enricher_a = ArtistEnricher::new(false);
        let enricher_b = ArtistEnricher::new(false);
        let now = Instant::now();
        let ttl = Duration::from_secs(60);

        let permit_a = enricher_a.try_reserve_slot("artist-1|sp=false".to_owned(), now, ttl);
        assert!(permit_a.is_some());
        drop(permit_a);

        // Same instance rejects duplicate within TTL
        assert!(enricher_a.try_reserve_slot("artist-1|sp=false".to_owned(), now, ttl).is_none());

        // Separate instance has independent cache (no process-global state)
        assert!(enricher_b.try_reserve_slot("artist-1|sp=false".to_owned(), now, ttl).is_some());
        assert_eq!(enricher_a.available_permits(), MAX_CONCURRENT_ENRICH_TASKS);
        assert_eq!(enricher_b.available_permits(), MAX_CONCURRENT_ENRICH_TASKS);
    }
}
