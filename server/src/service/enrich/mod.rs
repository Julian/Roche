mod itunes;
mod spotify;
mod wikipedia;

use std::{
    collections::HashMap,
    future::Future,
    pin::Pin,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use serde_json::Value;
use url::Url;

pub use self::itunes::{
    AlbumTrack, DiscographyAlbum, extract_album_tracks, extract_artist_discography,
    normalize_album_match_key,
};
#[cfg(test)]
pub use self::{
    itunes::{parse_itunes_artist_discography, parse_itunes_lookup_tracks},
    spotify::{extract_spotify_artist_uri_from_album_html, parse_spotify_embed_tracks},
    wikipedia::{
        WikipediaSearchHit, encode_wiki_path_segment, parse_wikipedia_artist_summary_json,
        score_artist_wikipedia_hit_for_artist, select_best_artist_wikipedia_slug,
    },
};
use super::{AppError, RocheService};
use crate::{
    content::http::SafeHttpClient,
    db::{EntryDetailView, EntryIdentifier, UpsertEntryInput},
    domain::EntryKind,
    util::url_guard::sanitize_public_http_href,
};

const MAX_SUMMARY_CHARS: usize = 600;
const MAX_ENRICH_ATTEMPT_CACHE: usize = 4_096;
pub const MAX_CONCURRENT_ENRICH_TASKS: usize = 4;
const FAILED_ENRICH_TTL: Duration = Duration::from_mins(30);
const ON_DEMAND_ENRICH_COOLDOWN: Duration = Duration::from_secs(60);

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

/// Instance-scoped background artist & album enrichment coordinator held on [`RocheService`].
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
            && detail.children.iter().any(|c| spotify::child_spotify_web_url(c).is_some());
        let attempt_key = format!("{}|sp={can_resolve_spotify}", detail.entry.id);
        let Some(permit) =
            enricher.try_reserve_slot(attempt_key, Instant::now(), enricher.failed_ttl)
        else {
            return;
        };
        let svc = self.service.clone();
        let mut detail_bg = detail.clone();
        self.service.spawn_background(async move {
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
        self.service.spawn_background(async move {
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

    /// Triggers bounded on-demand metadata enrichment for an `Artist` (bio, portrait, Spotify/Wikipedia
    /// links, iTunes discography) or `Album` (tracklist) and returns the refreshed [`EntryDetailView`].
    ///
    /// Rate-limited by [`ArtistEnricher`]'s concurrency semaphore and cooldown cache so repeated
    /// client requests (from Android or Web) return immediately without hammering upstream APIs.
    pub async fn enrich_entry_now(&self, id: &str) -> Result<EntryDetailView, AppError> {
        let mut detail = self
            .service
            .catalog()
            .get_entry_detail(id)
            .await?
            .ok_or_else(|| AppError::not_found("Entry not found"))?;

        let enricher = &self.service.artist_enricher;
        if !enricher.enabled {
            return Ok(detail);
        }
        let Some(client) = enricher.http_client.as_ref() else {
            return Ok(detail);
        };

        match detail.entry.kind {
            EntryKind::Artist if should_attempt_enrichment(&detail) => {
                let attempt_key = format!("on-demand|artist|{}", detail.entry.id);
                if let Some(_permit) = enricher.try_reserve_slot(
                    attempt_key,
                    Instant::now(),
                    ON_DEMAND_ENRICH_COOLDOWN,
                ) {
                    let _ = tokio::time::timeout(
                        Duration::from_secs(6),
                        enrich_artist_detail_with_fetcher(self.service, &mut detail, client),
                    )
                    .await;
                }
            }
            EntryKind::Album if should_attempt_album_tracklist_enrichment(&detail) => {
                let attempt_key = format!("on-demand|album|{}", detail.entry.id);
                if let Some(_permit) = enricher.try_reserve_slot(
                    attempt_key,
                    Instant::now(),
                    ON_DEMAND_ENRICH_COOLDOWN,
                ) {
                    let _ = tokio::time::timeout(
                        Duration::from_secs(6),
                        enrich_album_tracklist_with_fetcher(self.service, &mut detail, client),
                    )
                    .await;
                }
            }
            _ => {}
        }

        Ok(detail)
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
            spotify::resolve_spotify_artist_from_children(
                &detail.entry.title,
                &detail.children,
                fetcher,
            )
            .await
        } else {
            None
        }
    };
    let wiki_fut = async {
        if missing_wiki || missing_thumb || missing_summary {
            wikipedia::resolve_wikipedia_artist_bundle(detail, fetcher).await
        } else {
            (None, None)
        }
    };
    let discog_fut = async {
        if missing_discog {
            itunes::resolve_itunes_artist_discography(&detail.entry.title, fetcher).await
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
        merged
            .insert("discography".to_owned(), Value::Array(itunes::serialize_discography(&discog)));
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

    let mut tracks = if let Some(sp_id) = spotify::extract_spotify_album_id(&detail.entry)
        && let Ok(url) = Url::parse(&format!("https://open.spotify.com/embed/album/{sp_id}"))
        && let Some(html) = fetcher.fetch_text(&url, spotify::MAX_SPOTIFY_HTML_BYTES).await
    {
        spotify::parse_spotify_embed_tracks(&html)
    } else {
        Vec::new()
    };

    if tracks.is_empty()
        && let Some(am_id) = itunes::extract_apple_music_album_id(&detail.entry)
        && let Ok(url) =
            Url::parse(&format!("https://itunes.apple.com/lookup?id={am_id}&entity=song"))
        && let Some(json_str) = fetcher.fetch_text(&url, itunes::MAX_ITUNES_JSON_BYTES).await
    {
        tracks = itunes::parse_itunes_lookup_tracks(&json_str);
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
        && detail.children.iter().any(|c| spotify::child_spotify_web_url(c).is_some());
    let missing_thumb =
        entry.thumbnail_url.as_deref().and_then(sanitize_public_http_href).is_none();
    let missing_summary = entry.summary.as_deref().map(str::trim).is_none_or(str::is_empty);
    let missing_discog = entry.metadata.get("discography").is_none();
    missing_wiki || missing_spotify || missing_thumb || missing_summary || missing_discog
}

fn should_attempt_album_tracklist_enrichment(detail: &EntryDetailView) -> bool {
    if detail.entry.kind != EntryKind::Album {
        return false;
    }
    if !itunes::extract_album_tracks(detail).is_empty() {
        return false;
    }
    spotify::extract_spotify_album_id(&detail.entry).is_some()
        || itunes::extract_apple_music_album_id(&detail.entry).is_some()
}

pub(super) fn normalize_artist_tokens(raw: &str) -> String {
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

pub(super) fn strip_leading_the(s: &str) -> &str {
    s.strip_prefix("the ").unwrap_or(s)
}

pub(super) fn truncate_summary(raw: &str) -> String {
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

/// Decodes standard XML/HTML character and numeric entity references using `quick_xml`,
/// preserving unknown or malformed `&...` sequences verbatim without panicking on multibyte UTF-8.
#[cfg(test)]
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
            let candidate = &rest[amp_idx..=amp_idx + 1 + semi_idx];
            if let Ok(decoded) = quick_xml::escape::unescape_with(candidate, |ent| {
                quick_xml::escape::resolve_xml_entity(ent)
                    .or_else(|| (ent == "nbsp").then_some(" "))
            }) {
                out.push_str(&decoded);
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

        assert!(enricher_a.try_reserve_slot("artist-1|sp=false".to_owned(), now, ttl).is_none());
        assert!(enricher_b.try_reserve_slot("artist-1|sp=false".to_owned(), now, ttl).is_some());
        assert_eq!(enricher_a.available_permits(), MAX_CONCURRENT_ENRICH_TASKS);
        assert_eq!(enricher_b.available_permits(), MAX_CONCURRENT_ENRICH_TASKS);
    }
}
