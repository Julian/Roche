use std::collections::{BTreeMap, HashMap, HashSet};

use chrono::{DateTime, Utc};
use rand::RngExt;
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, Sqlite, SqliteConnection, SqlitePool};
use thiserror::Error;

use super::{
    sync::{
        ALBUMS_1001_COLLECTION_ID, SyncRepo, canonical_ordering_json, canonicalize_sync_url,
        collection_entry_natural_key, deterministic_catalog_entry_id, deterministic_comparison_id,
        deterministic_consumption_log_id, deterministic_system_collection_id,
        deterministic_title_entry_id,
    },
    types::{ActivityEntityType, CollectionMode, CollectionRole, ConsumptionStatus, EntryKind},
};
use crate::{
    albums_pool::bundled_1001_albums,
    ranking::{
        MAX_K_DEFAULT, N_QUANTILES, RANDOM_SEED_EVERY, Score, infer_scores, rescale_to_quantiles,
        select_cluster, tier_label, validate_ordering,
    },
};

const EDITION_KEYWORDS: [&str; 15] = [
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

const PREFERRED_IDENTIFIER_SCHEMES: [&str; 14] = [
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
    "url",
];

const EXTERNAL_ID_DISPLAY_ORDER: [&str; 22] = [
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
    "apple_music",
    "tidal",
    "deezer",
    "youtube_music",
    "qobuz",
    "bandcamp",
    "1001albums",
];

#[derive(Debug, Error)]
pub enum CatalogError {
    #[error("Database error: {0}")]
    Db(#[from] sqlx::Error),

    #[error("{0} not found")]
    NotFound(String),

    #[error("Ambiguous lookup for '{query}': matches {matches}")]
    AmbiguousLookup { query: String, matches: String },

    #[error("Cannot manually modify entries of {mode:?} collection '{title}'")]
    ImmutableCollection { title: String, mode: CollectionMode },

    #[error("Invalid catalog input: {0}")]
    InvalidInput(String),

    #[error("Conflict: {0}")]
    Conflict(String),
}

pub type CatalogResult<T> = std::result::Result<T, CatalogError>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct EntryIdentifier {
    pub scheme: String,
    pub value: String,
}

impl EntryIdentifier {
    #[must_use]
    pub fn label(&self) -> &str {
        external_identifier_label(&self.scheme)
    }

    #[must_use]
    pub fn external_url(&self) -> Option<String> {
        external_identifier_url(&self.scheme, &self.value)
    }
}

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
        "1001albums" => "1001 Albums",
        "bandcamp" => "Bandcamp",
        "url" => "Web",
        "feed_url" => "Feed",
        other => other,
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
                let (entity, ident) = rest.split_once(':')?;
                if !entity.is_empty() && !ident.is_empty() {
                    return Some(format!("https://open.spotify.com/{entity}/{ident}"));
                }
                None
            } else if v.starts_with("http://") || v.starts_with("https://") {
                Some(v.to_owned())
            } else {
                Some(format!("https://open.spotify.com/album/{v}"))
            }
        }
        "imdb" => Some(format!("https://www.imdb.com/title/{v}/")),
        "tmdb_movie" => Some(format!("https://www.themoviedb.org/movie/{v}")),
        "tmdb_tv" => Some(format!("https://www.themoviedb.org/tv/{v}")),
        "isbn" => Some(format!("https://openlibrary.org/isbn/{v}")),
        "openlibrary" => Some(format!("https://openlibrary.org/works/{v}")),
        "goodreads" => Some(format!("https://www.goodreads.com/book/show/{v}")),
        "igdb" => Some(format!("https://www.igdb.com/games/{v}")),
        "discogs" => Some(format!("https://www.discogs.com/release/{v}")),
        "wikipedia" => Some(format!("https://en.wikipedia.org/wiki/{v}")),
        "arxiv" => Some(format!("https://arxiv.org/abs/{v}")),
        "musicbrainz_release" => Some(format!("https://musicbrainz.org/release/{v}")),
        "musicbrainz_artist" => Some(format!("https://musicbrainz.org/artist/{v}")),
        "musicbrainz_recording" => Some(format!("https://musicbrainz.org/recording/{v}")),
        "apple_music" => Some(format!("https://music.apple.com/album/{v}")),
        "tidal" => Some(format!("https://tidal.com/browse/album/{v}")),
        "deezer" => Some(format!("https://www.deezer.com/album/{v}")),
        "youtube_music" => Some(format!("https://music.youtube.com/playlist?list={v}")),
        "url" | "feed_url" if v.starts_with("http://") || v.starts_with("https://") => {
            Some(v.to_owned())
        }
        _ => None,
    }
}

/// Normalizes external identifier values so equivalent representations collide deterministically.
#[must_use]
pub fn normalize_identifier_value(kind: EntryKind, scheme: &str, raw_value: &str) -> String {
    let trimmed = raw_value.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    match scheme.trim().to_ascii_lowercase().as_str() {
        "url" | "feed_url" => canonicalize_sync_url(trimmed),
        "slug" | "tag" | "1001albums" => trimmed.to_lowercase(),
        "spotify" => normalize_spotify_identifier(kind, trimmed),
        _ => trimmed.to_owned(),
    }
}

fn normalize_spotify_identifier(kind: EntryKind, raw: &str) -> String {
    if raw.starts_with("spotify:") {
        let parts: Vec<&str> = raw.split(':').collect();
        if parts.len() >= 3 && !parts[1].is_empty() && !parts[2].is_empty() {
            return format!("spotify:{}:{}", parts[1].to_ascii_lowercase(), parts[2]);
        }
        return raw.to_owned();
    }
    if let Ok(parsed) = url::Url::parse(raw)
        && parsed.host_str().is_some_and(|h| h.eq_ignore_ascii_case("open.spotify.com"))
    {
        let segments: Vec<&str> = parsed
            .path_segments()
            .map(|it| it.filter(|s| !s.is_empty()).collect())
            .unwrap_or_default();
        if segments.len() >= 2 {
            let entity = segments[segments.len() - 2].to_ascii_lowercase();
            let ident = segments[segments.len() - 1];
            if matches!(
                entity.as_str(),
                "album" | "track" | "artist" | "episode" | "show" | "playlist"
            ) {
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

/// Strips trailing bracketed/parenthesized edition suffixes (e.g. `(Deluxe Edition)`, `[2019 Remaster]`)
/// while preserving distinct works like `(Live)`, `(Demos)`, or `(Soundtrack)`.
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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CatalogEntry {
    pub id: String,
    pub kind: EntryKind,
    pub title: String,
    pub creators: Vec<String>,
    pub url: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
    pub summary: Option<String>,
    pub thumbnail_url: Option<String>,
    pub metadata: serde_json::Value,
    pub identifiers: Vec<EntryIdentifier>,
    pub merged_into_id: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl CatalogEntry {
    #[must_use]
    pub fn identifier(&self, scheme: &str) -> Option<&str> {
        self.identifiers
            .iter()
            .find(|id| id.scheme.eq_ignore_ascii_case(scheme))
            .map(|id| id.value.as_str())
    }

    /// Resolves the best web/open URL for this entry (`entries.url` first, then ordered `identifiers`).
    #[must_use]
    pub fn primary_open_url(&self) -> Option<String> {
        if let Some(u) = self.url.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            return Some(u.to_owned());
        }
        for preferred in EXTERNAL_ID_DISPLAY_ORDER {
            if let Some(ident) = self.identifiers.iter().find(|i| i.scheme == preferred)
                && let Some(ext_url) = ident.external_url()
            {
                return Some(ext_url);
            }
        }
        self.identifiers.iter().find_map(EntryIdentifier::external_url)
    }

    #[must_use]
    pub fn sorted_external_identifiers(&self) -> Vec<&EntryIdentifier> {
        let mut list: Vec<&EntryIdentifier> = self
            .identifiers
            .iter()
            .filter(|i| !matches!(i.scheme.as_str(), "slug" | "tag"))
            .collect();
        list.sort_by(|a, b| {
            let ra = EXTERNAL_ID_DISPLAY_ORDER
                .iter()
                .position(|&s| s == a.scheme)
                .unwrap_or(EXTERNAL_ID_DISPLAY_ORDER.len());
            let rb = EXTERNAL_ID_DISPLAY_ORDER
                .iter()
                .position(|&s| s == b.scheme)
                .unwrap_or(EXTERNAL_ID_DISPLAY_ORDER.len());
            ra.cmp(&rb).then_with(|| a.scheme.cmp(&b.scheme)).then_with(|| a.value.cmp(&b.value))
        });
        list
    }
}

#[derive(Debug, Clone, sqlx::FromRow)]
struct RawEntryRow {
    id: String,
    kind: EntryKind,
    title: String,
    creators_json: String,
    url: Option<String>,
    published_at: Option<DateTime<Utc>>,
    release_year: Option<i32>,
    summary: Option<String>,
    thumbnail_url: Option<String>,
    metadata_json: String,
    merged_into_id: Option<String>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

impl RawEntryRow {
    fn into_entry(self, identifiers: Vec<EntryIdentifier>) -> CatalogEntry {
        let creators: Vec<String> = serde_json::from_str(&self.creators_json).unwrap_or_default();
        let metadata: serde_json::Value = serde_json::from_str(&self.metadata_json)
            .unwrap_or_else(|_| serde_json::Value::Object(serde_json::Map::new()));
        CatalogEntry {
            id: self.id,
            kind: self.kind,
            title: self.title,
            creators,
            url: self.url,
            published_at: self.published_at,
            release_year: self.release_year,
            summary: self.summary,
            thumbnail_url: self.thumbnail_url,
            metadata,
            identifiers,
            merged_into_id: self.merged_into_id,
            created_at: self.created_at,
            updated_at: self.updated_at,
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UpsertEntryInput {
    #[serde(default)]
    pub id: Option<String>,
    pub kind: Option<EntryKind>,
    pub title: String,
    #[serde(default)]
    pub creators: Vec<String>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub published_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub release_year: Option<i32>,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub metadata: Option<serde_json::Value>,
    #[serde(default)]
    pub identifiers: Vec<EntryIdentifier>,
    #[serde(default)]
    pub parent_collection_id: Option<String>,
    #[serde(default)]
    pub parent_position: Option<f64>,
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DynamicCollectionRule {
    #[serde(default)]
    pub kind: Option<EntryKind>,
    #[serde(default)]
    pub kinds: Vec<EntryKind>,
    #[serde(default)]
    pub consumed: Option<bool>,
    #[serde(default)]
    pub status: Option<ConsumptionStatus>,
    #[serde(default)]
    pub creator: Option<String>,
    #[serde(default)]
    pub release_year: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CollectionSummary {
    pub id: String,
    pub slug: Option<String>,
    pub title: String,
    pub summary: Option<String>,
    pub kind: EntryKind,
    pub mode: CollectionMode,
    pub role: CollectionRole,
    pub rule_json: Option<String>,
    pub show_in_lists: bool,
    pub color: Option<String>,
    pub member_count: usize,
    pub comparison_count: usize,
    pub has_generator: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RankedEntryRow {
    pub rank: usize,
    pub entry: CatalogEntry,
    pub score: Score,
    pub tier: Option<char>,
    pub bar_pct: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RankedCollectionView {
    pub collection: CollectionSummary,
    pub is_priority_scope: bool,
    pub comparison_count: usize,
    pub rows: Vec<RankedEntryRow>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConsumptionLogView {
    pub id: String,
    pub entry: CatalogEntry,
    pub ts: DateTime<Utc>,
    pub status: ConsumptionStatus,
    pub notes: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct ConsumptionLogRow {
    pub id: String,
    pub entry_id: String,
    pub ts: DateTime<Utc>,
    pub status: ConsumptionStatus,
    pub notes: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ComparisonRecord {
    pub id: String,
    pub ts: DateTime<Utc>,
    pub scope_collection_id: Option<String>,
    pub ordering: Vec<Vec<String>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Generator1001State {
    pub collection_id: String,
    pub enabled: bool,
    pub current_entry: Option<CatalogEntry>,
    pub drawn_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
    pub total_pool: usize,
    pub consumed_count: usize,
    pub abandoned_count: usize,
    pub remaining_count: usize,
}

#[derive(Debug, Clone, Default)]
pub struct ClusterSelectFilter {
    pub max_k: usize,
    pub iteration: usize,
    pub focus_ids: Vec<String>,
    pub recent_limit: Option<usize>,
    pub by_creator: Option<String>,
    pub from_parent_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryDetailView {
    pub entry: CatalogEntry,
    pub children: Vec<CatalogEntry>,
    pub parents: Vec<CatalogEntry>,
    pub log_history: Vec<ConsumptionLogRow>,
}

#[derive(Debug, Clone, sqlx::FromRow)]
struct RawCollectionRow {
    id: String,
    slug: Option<String>,
    title: String,
    summary: Option<String>,
    kind: EntryKind,
    mode: CollectionMode,
    collection_role: CollectionRole,
    rule_json: Option<String>,
    show_in_lists: bool,
    color: Option<String>,
    has_generator: bool,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

/// Removes `entry_id` from all `Want` queues inside `tx` and records `collection_entry` tombstones
/// so peer sync propagates the promotion out of offline Want queues.
pub(super) async fn remove_from_want_queues_in_tx(
    conn: &mut SqliteConnection,
    entry_id: &str,
    ts: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let want_col_ids: Vec<String> = sqlx::query_scalar(
        "SELECT ce.collection_id
         FROM collection_entries ce
         JOIN collection_specs cs ON cs.collection_id = ce.collection_id
         WHERE ce.entry_id = ? AND cs.collection_role = 'want'",
    )
    .bind(entry_id)
    .fetch_all(&mut *conn)
    .await?;

    for col_id in want_col_ids {
        sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
            .bind(&col_id)
            .bind(entry_id)
            .execute(&mut *conn)
            .await?;
        let nat_key = collection_entry_natural_key(&col_id, entry_id);
        SyncRepo::record_tombstone(conn, ActivityEntityType::CollectionEntry, &nat_key, ts).await?;
    }
    Ok(())
}

pub struct CatalogRepo<'a> {
    pool: &'a SqlitePool,
}

impl<'a> CatalogRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn get_entry(&self, id: &str) -> CatalogResult<Option<CatalogEntry>> {
        let canonical_id = self.follow_merged_id(id).await?;
        let Some(target_id) = canonical_id else {
            return Ok(None);
        };
        let row: Option<RawEntryRow> = sqlx::query_as(
            "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                    thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
             FROM entries WHERE id = ? AND merged_into_id IS NULL",
        )
        .bind(&target_id)
        .fetch_optional(self.pool)
        .await?;
        let Some(raw) = row else {
            return Ok(None);
        };
        let idents = self.load_identifiers_for_one(&raw.id).await?;
        Ok(Some(raw.into_entry(idents)))
    }

    pub async fn get_entry_detail(&self, id: &str) -> CatalogResult<Option<EntryDetailView>> {
        let Some(entry) = self.get_entry(id).await? else {
            return Ok(None);
        };
        let child_rows: Vec<RawEntryRow> = sqlx::query_as(
            "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year,
                    e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, e.updated_at
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
             ORDER BY ce.position ASC NULLS LAST, COALESCE(e.release_year, 0) DESC, LOWER(e.title) ASC",
        )
        .bind(&entry.id)
        .fetch_all(self.pool)
        .await?;
        let children = self.hydrate_entries(child_rows).await?;

        let parent_rows: Vec<RawEntryRow> = sqlx::query_as(
            "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year,
                    e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, e.updated_at
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.collection_id
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE ce.entry_id = ?
               AND e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system'))
             ORDER BY LOWER(e.title) ASC",
        )
        .bind(&entry.id)
        .fetch_all(self.pool)
        .await?;
        let parents = self.hydrate_entries(parent_rows).await?;

        let log_history: Vec<ConsumptionLogRow> = sqlx::query_as(
            "SELECT id, entry_id, ts, status, notes, created_at
             FROM consumption_log
             WHERE entry_id = ?
             ORDER BY ts DESC, id DESC",
        )
        .bind(&entry.id)
        .fetch_all(self.pool)
        .await?;

        Ok(Some(EntryDetailView { entry, children, parents, log_history }))
    }

    pub async fn search_entries(
        &self,
        query: Option<&str>,
        kind: Option<EntryKind>,
        limit: usize,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        let cap = i64::try_from(limit.clamp(1, 500)).unwrap_or(100);
        let trimmed = query.map(str::trim).filter(|s| !s.is_empty());
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at, e.release_year,
                    e.summary, e.thumbnail_url, e.metadata_json, e.merged_into_id, e.created_at, e.updated_at
             FROM entries e
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'want', 'tag'))",
        );
        if let Some(k) = kind {
            qb.push(" AND e.kind = ").push_bind(k);
        }
        if let Some(q) = trimmed {
            let escaped = q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
            let pattern = format!("%{escaped}%");
            qb.push(" AND (e.title LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR e.creators_json LIKE ")
                .push_bind(pattern.clone())
                .push(" ESCAPE '\\' OR EXISTS (SELECT 1 FROM entry_identifiers ei WHERE ei.entry_id = e.id AND ei.value LIKE ")
                .push_bind(pattern)
                .push(" ESCAPE '\\'))");
        }
        qb.push(" ORDER BY e.updated_at DESC, LOWER(e.title) ASC LIMIT ").push_bind(cap);
        let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;
        self.hydrate_entries(rows).await
    }

    /// Resolves an entry by UUID, external identifier value, or exact case-insensitive title,
    /// mirroring Great's `resolve_item` semantics (with optional `kind` and `only_collection_id` scoping).
    pub async fn resolve_entry(
        &self,
        query: &str,
        kind: Option<EntryKind>,
        only_collection_id: Option<&str>,
    ) -> CatalogResult<CatalogEntry> {
        let q = query.trim();
        if q.is_empty() {
            return Err(CatalogError::InvalidInput("Lookup query cannot be empty".to_owned()));
        }

        if let Some(by_id) = self.get_entry(q).await?
            && kind.is_none_or(|k| by_id.kind == k)
        {
            if let Some(col_id) = only_collection_id {
                let in_col: Option<i64> = sqlx::query_scalar(
                    "SELECT 1 FROM collection_entries WHERE collection_id = ? AND entry_id = ?",
                )
                .bind(col_id)
                .bind(&by_id.id)
                .fetch_optional(self.pool)
                .await?;
                if in_col.is_some() {
                    return Ok(by_id);
                }
            } else {
                return Ok(by_id);
            }
        }

        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT DISTINCT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at,
                    e.release_year, e.summary, e.thumbnail_url, e.metadata_json,
                    e.merged_into_id, e.created_at, e.updated_at
             FROM entries e
             LEFT JOIN collection_specs cs ON cs.collection_id = e.id
             WHERE e.merged_into_id IS NULL
               AND (cs.collection_role IS NULL OR cs.collection_role NOT IN ('system', 'want', 'tag'))",
        );
        if let Some(k) = kind {
            qb.push(" AND e.kind = ").push_bind(k);
        }
        if let Some(col_id) = only_collection_id {
            qb.push(" AND EXISTS (SELECT 1 FROM collection_entries ce WHERE ce.collection_id = ")
                .push_bind(col_id)
                .push(" AND ce.entry_id = e.id)");
        }
        let norm_spotify = kind.map(|k| normalize_spotify_identifier(k, q));
        qb.push(" AND (LOWER(e.title) = LOWER(").push_bind(q).push(")");
        qb.push(" OR EXISTS (SELECT 1 FROM entry_identifiers ei WHERE ei.entry_id = e.id AND (ei.value = ")
            .push_bind(q)
            .push(" OR LOWER(ei.value) = LOWER(")
            .push_bind(q)
            .push(")");
        if let Some(sp) = norm_spotify {
            qb.push(" OR ei.value = ").push_bind(sp);
        }
        qb.push("))) ORDER BY e.created_at ASC, e.id ASC");

        let mut candidates: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;

        // Fallback for album edition-stripped title lookup
        if candidates.is_empty() && kind == Some(EntryKind::Album) {
            let stripped_q = strip_album_edition_suffix(q).to_lowercase();
            let albums: Vec<RawEntryRow> = sqlx::query_as(
                "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                        thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
                 FROM entries WHERE kind = 'album' AND merged_into_id IS NULL",
            )
            .fetch_all(self.pool)
            .await?;
            candidates = albums
                .into_iter()
                .filter(|a| strip_album_edition_suffix(&a.title).to_lowercase() == stripped_q)
                .collect();
        }

        if candidates.is_empty() {
            return Err(CatalogError::NotFound(format!("Entry '{q}'")));
        }
        if candidates.len() > 1 {
            let matches = candidates
                .iter()
                .map(|c| format!("'{}' (kind={}, id={})", c.title, c.kind.as_str(), c.id))
                .collect::<Vec<_>>()
                .join(", ");
            return Err(CatalogError::AmbiguousLookup { query: q.to_owned(), matches });
        }
        let hydrated = self.hydrate_entries(candidates).await?;
        hydrated.into_iter().next().ok_or_else(|| CatalogError::NotFound(format!("Entry '{q}'")))
    }

    /// Upserts a catalog entry with upfront `entry_identifiers` deduplication, album edition-variant
    /// matching, and automatic `artist` synthesis for music works.
    pub async fn upsert_entry(&self, input: &UpsertEntryInput) -> CatalogResult<CatalogEntry> {
        let kind = input
            .kind
            .ok_or_else(|| CatalogError::InvalidInput("Entry kind is required".to_owned()))?;
        let title = input.title.trim();
        if title.is_empty() {
            return Err(CatalogError::InvalidInput("Entry title cannot be empty".to_owned()));
        }

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let entry_id = Self::upsert_entry_in_tx(&mut tx, input, kind, title, now).await?;
        tx.commit().await?;

        self.get_entry(&entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))
    }

    #[allow(clippy::too_many_lines)]
    pub(crate) async fn upsert_entry_in_tx(
        tx: &mut SqliteConnection,
        input: &UpsertEntryInput,
        kind: EntryKind,
        title: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
        let clean_creators: Vec<String> = input
            .creators
            .iter()
            .map(|c| c.trim())
            .filter(|c| !c.is_empty())
            .map(ToOwned::to_owned)
            .collect();
        let clean_url =
            input.url.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(ToOwned::to_owned);

        let mut normalized_idents: Vec<EntryIdentifier> = Vec::new();
        let mut seen_idents: HashSet<(String, String)> = HashSet::new();
        if let Some(u) = &clean_url {
            let val = normalize_identifier_value(kind, "url", u);
            if !val.is_empty() && seen_idents.insert(("url".to_owned(), val.clone())) {
                normalized_idents.push(EntryIdentifier { scheme: "url".to_owned(), value: val });
            }
        }
        for ident in &input.identifiers {
            let scheme = ident.scheme.trim().to_ascii_lowercase();
            let val = normalize_identifier_value(kind, &scheme, &ident.value);
            if !scheme.is_empty()
                && !val.is_empty()
                && seen_idents.insert((scheme.clone(), val.clone()))
            {
                normalized_idents.push(EntryIdentifier { scheme, value: val });
            }
        }

        // 1. Check explicit ID (following merged_into_id)
        let mut matched_id = if let Some(explicit_id) =
            input.id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            Self::follow_merged_id_in_tx(tx, explicit_id).await?
        } else {
            None
        };

        // 2. Upfront deduplication across all normalized identifiers
        if matched_id.is_none() {
            for ident in &normalized_idents {
                let found: Option<String> = sqlx::query_scalar(
                    "SELECT e.id
                     FROM entry_identifiers ei
                     JOIN entries e ON e.id = COALESCE(
                         (SELECT merged_into_id FROM entries WHERE id = ei.entry_id),
                         ei.entry_id
                     )
                     WHERE ei.scheme = ? AND ei.value = ? AND e.merged_into_id IS NULL
                     LIMIT 1",
                )
                .bind(&ident.scheme)
                .bind(&ident.value)
                .fetch_optional(&mut *tx)
                .await?;
                if found.is_some() {
                    matched_id = found;
                    break;
                }
            }
        }

        // 3. Album edition-variant matching (`(artist.casefold(), strip_album_edition_suffix(title).casefold())`)
        if matched_id.is_none()
            && kind == EntryKind::Album
            && let Some(primary_artist) = clean_creators.first()
        {
            let target_artist = primary_artist.to_lowercase();
            let target_stripped = strip_album_edition_suffix(title).to_lowercase();
            let existing_albums: Vec<(String, String, String)> = sqlx::query_as(
                "SELECT id, title, creators_json
                 FROM entries
                 WHERE kind = 'album' AND merged_into_id IS NULL",
            )
            .fetch_all(&mut *tx)
            .await?;
            for (cand_id, cand_title, cand_creators_json) in existing_albums {
                let cand_creators: Vec<String> =
                    serde_json::from_str(&cand_creators_json).unwrap_or_default();
                let same_artist =
                    cand_creators.first().is_some_and(|a| a.trim().to_lowercase() == target_artist);
                if same_artist
                    && strip_album_edition_suffix(&cand_title).to_lowercase() == target_stripped
                {
                    matched_id = Some(cand_id);
                    break;
                }
            }
        }

        // 4. Exact title match for Artists when creators/identifiers are absent
        if matched_id.is_none() && kind == EntryKind::Artist {
            matched_id = sqlx::query_scalar(
                "SELECT id FROM entries
                 WHERE kind = 'artist' AND merged_into_id IS NULL AND LOWER(title) = LOWER(?)
                 LIMIT 1",
            )
            .bind(title)
            .fetch_optional(&mut *tx)
            .await?;
        }

        let creators_json =
            serde_json::to_string(&clean_creators).unwrap_or_else(|_| "[]".to_owned());
        let metadata_json = input
            .metadata
            .as_ref()
            .filter(|v| v.is_object())
            .and_then(|v| serde_json::to_string(v).ok())
            .unwrap_or_else(|| "{}".to_owned());

        let final_id = if let Some(existing_id) = matched_id {
            // Enrich missing fields on existing entry without clobbering curated titles
            let clean_existing_title = if kind == EntryKind::Album {
                let current_title: String =
                    sqlx::query_scalar("SELECT title FROM entries WHERE id = ?")
                        .bind(&existing_id)
                        .fetch_one(&mut *tx)
                        .await?;
                let cur_stripped = strip_album_edition_suffix(&current_title);
                let inc_stripped = strip_album_edition_suffix(title);
                if current_title.len() > cur_stripped.len() && title.len() == inc_stripped.len() {
                    Some(title)
                } else {
                    None
                }
            } else {
                None
            };

            sqlx::query(
                "UPDATE entries
                 SET title = COALESCE(?, title),
                     creators_json = CASE WHEN creators_json = '[]' AND ? != '[]' THEN ? ELSE creators_json END,
                     url = COALESCE(url, ?),
                     published_at = COALESCE(published_at, ?),
                     release_year = COALESCE(release_year, ?),
                     summary = COALESCE(summary, ?),
                     thumbnail_url = COALESCE(thumbnail_url, ?),
                     metadata_json = CASE WHEN metadata_json = '{}' AND ? != '{}' THEN ? ELSE metadata_json END,
                     updated_at = ?
                 WHERE id = ?",
            )
            .bind(clean_existing_title)
            .bind(&creators_json)
            .bind(&creators_json)
            .bind(&clean_url)
            .bind(input.published_at)
            .bind(input.release_year)
            .bind(input.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()))
            .bind(input.thumbnail_url.as_deref().map(str::trim).filter(|s| !s.is_empty()))
            .bind(&metadata_json)
            .bind(&metadata_json)
            .bind(now)
            .bind(&existing_id)
            .execute(&mut *tx)
            .await?;

            existing_id
        } else {
            let det_id = Self::choose_deterministic_entry_id(
                input.id.as_deref(),
                kind,
                title,
                &clean_creators,
                &normalized_idents,
            );
            // Guard against deterministic ID already existing
            let existing_det: Option<String> = Self::follow_merged_id_in_tx(tx, &det_id).await?;
            if let Some(found_det) = existing_det {
                found_det
            } else {
                sqlx::query(
                    "INSERT INTO entries
                     (id, kind, title, creators_json, url, published_at, release_year, summary,
                      thumbnail_url, metadata_json, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
                .bind(&det_id)
                .bind(kind)
                .bind(title)
                .bind(&creators_json)
                .bind(&clean_url)
                .bind(input.published_at)
                .bind(input.release_year)
                .bind(input.summary.as_deref().map(str::trim).filter(|s| !s.is_empty()))
                .bind(input.thumbnail_url.as_deref().map(str::trim).filter(|s| !s.is_empty()))
                .bind(&metadata_json)
                .bind(now)
                .bind(now)
                .execute(&mut *tx)
                .await?;
                det_id
            }
        };

        for ident in &normalized_idents {
            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES (?, ?, ?)",
            )
            .bind(&ident.scheme)
            .bind(&ident.value)
            .bind(&final_id)
            .execute(&mut *tx)
            .await?;
        }

        if let Some(parent_id) =
            input.parent_collection_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
            && let Some(canon_parent) = Self::follow_merged_id_in_tx(tx, parent_id).await?
        {
            sqlx::query(
                "INSERT INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                    position = COALESCE(excluded.position, collection_entries.position)",
            )
            .bind(&canon_parent)
            .bind(&final_id)
            .bind(input.parent_position)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }

        // Synthesize `artist` entries for music `album` and `song` creators and link them
        if matches!(kind, EntryKind::Album | EntryKind::Song) {
            for creator in &clean_creators {
                let artist_id = Self::ensure_artist_entry_in_tx(tx, creator, now).await?;
                sqlx::query(
                    "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
                     VALUES (?, ?, ?)",
                )
                .bind(&artist_id)
                .bind(&final_id)
                .bind(now)
                .execute(&mut *tx)
                .await?;
            }
        }

        Ok(final_id)
    }

    fn choose_deterministic_entry_id(
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
            if let Some(ident) = identifiers.iter().find(|i| i.scheme == preferred) {
                return deterministic_catalog_entry_id(kind.as_str(), &ident.scheme, &ident.value);
            }
        }
        if let Some(first_ident) = identifiers.first() {
            return deterministic_catalog_entry_id(
                kind.as_str(),
                &first_ident.scheme,
                &first_ident.value,
            );
        }
        let normalized_title = if kind == EntryKind::Album {
            strip_album_edition_suffix(title)
        } else {
            title.to_owned()
        };
        let title_key = creators.first().map_or_else(
            || normalized_title.to_lowercase(),
            |primary_creator| {
                format!("{}\0{}", primary_creator.to_lowercase(), normalized_title.to_lowercase())
            },
        );
        deterministic_title_entry_id(kind.as_str(), &title_key)
    }

    pub(crate) async fn ensure_artist_entry_in_tx(
        tx: &mut SqliteConnection,
        artist_name: &str,
        now: DateTime<Utc>,
    ) -> CatalogResult<String> {
        let trimmed = artist_name.trim();
        if let Some(existing) = sqlx::query_scalar::<_, String>(
            "SELECT id FROM entries
             WHERE kind = 'artist' AND merged_into_id IS NULL AND LOWER(title) = LOWER(?)
             LIMIT 1",
        )
        .bind(trimmed)
        .fetch_optional(&mut *tx)
        .await?
        {
            return Ok(existing);
        }

        let artist_id = deterministic_title_entry_id("artist", &trimmed.to_lowercase());
        sqlx::query(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'artist', ?, ?, ?)",
        )
        .bind(&artist_id)
        .bind(trimmed)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;
        Ok(artist_id)
    }

    /// Post-hoc deduplication: folds `duplicate_id` into `canonical_id`, rewriting identifiers,
    /// collection memberships, consumption logs, comparisons, and active generators atomically.
    #[allow(clippy::too_many_lines)]
    pub async fn merge_entries(
        &self,
        duplicate_id: &str,
        canonical_id: &str,
    ) -> CatalogResult<CatalogEntry> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let dup_resolved = Self::follow_merged_id_in_tx(&mut tx, duplicate_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Duplicate entry '{duplicate_id}'")))?;
        let canon_resolved = Self::follow_merged_id_in_tx(&mut tx, canonical_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Canonical entry '{canonical_id}'")))?;

        if dup_resolved == canon_resolved {
            return Err(CatalogError::InvalidInput("Cannot merge an entry into itself".to_owned()));
        }

        Self::fold_live_duplicate_in_tx(&mut tx, &dup_resolved, &canon_resolved, now).await?;

        tx.commit().await?;
        self.get_entry(&canon_resolved)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_resolved}'")))
    }

    #[allow(clippy::too_many_lines)]
    pub(crate) async fn fold_live_duplicate_in_tx(
        tx: &mut SqliteConnection,
        dup_resolved: &str,
        canon_resolved: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        type DupStateRow = (String, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>);

        // Compress any prior chains pointing to `dup_resolved`
        sqlx::query(
            "UPDATE entries SET merged_into_id = ?, updated_at = ? WHERE merged_into_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .execute(&mut *tx)
        .await?;

        // Backfill missing metadata onto canonical from duplicate
        let dup_row: RawEntryRow = sqlx::query_as(
            "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                    thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
             FROM entries WHERE id = ?",
        )
        .bind(dup_resolved)
        .fetch_one(&mut *tx)
        .await?;

        sqlx::query(
            "UPDATE entries
             SET creators_json = CASE WHEN creators_json = '[]' AND ? != '[]' THEN ? ELSE creators_json END,
                 url = COALESCE(url, ?),
                 published_at = COALESCE(published_at, ?),
                 release_year = COALESCE(release_year, ?),
                 summary = COALESCE(summary, ?),
                 thumbnail_url = COALESCE(thumbnail_url, ?),
                 metadata_json = CASE WHEN metadata_json = '{}' AND ? != '{}' THEN ? ELSE metadata_json END,
                 updated_at = ?
             WHERE id = ?",
        )
        .bind(&dup_row.creators_json)
        .bind(&dup_row.creators_json)
        .bind(&dup_row.url)
        .bind(dup_row.published_at)
        .bind(dup_row.release_year)
        .bind(&dup_row.summary)
        .bind(&dup_row.thumbnail_url)
        .bind(&dup_row.metadata_json)
        .bind(&dup_row.metadata_json)
        .bind(now)
        .bind(canon_resolved)
        .execute(&mut *tx)
        .await?;

        // Re-point all identifiers (preserving multi-valued schemes such as syndicated URLs)
        sqlx::query("UPDATE entry_identifiers SET entry_id = ? WHERE entry_id = ?")
            .bind(canon_resolved)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Merge reader content & state if present
        let dup_content: Option<(Option<String>, String)> = sqlx::query_as(
            "SELECT content_html, content_type FROM entry_content WHERE entry_id = ?",
        )
        .bind(dup_resolved)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some((dup_html, dup_ct)) = dup_content {
            sqlx::query(
                "INSERT INTO entry_content (entry_id, content_html, content_type)
                 VALUES (?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                    content_html = COALESCE(entry_content.content_html, excluded.content_html)",
            )
            .bind(canon_resolved)
            .bind(dup_html)
            .bind(dup_ct)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM entry_content WHERE entry_id = ?")
                .bind(dup_resolved)
                .execute(&mut *tx)
                .await?;
        }

        let dup_state: Option<DupStateRow> = sqlx::query_as(
            "SELECT inbox_state, read_at, progress, interaction_updated_at
             FROM entry_state WHERE entry_id = ?",
        )
        .bind(dup_resolved)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some((inbox_state, read_at, progress, interaction_ts)) = dup_state {
            sqlx::query(
                "INSERT INTO entry_state (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(entry_id) DO UPDATE SET
                    inbox_state = CASE
                        WHEN entry_state.inbox_state = 'dismissed' OR excluded.inbox_state = 'dismissed' THEN 'dismissed'
                        WHEN entry_state.inbox_state = 'unread' OR excluded.inbox_state = 'unread' THEN 'unread'
                        ELSE 'none'
                    END,
                    read_at = COALESCE(entry_state.read_at, excluded.read_at),
                    progress = MAX(entry_state.progress, excluded.progress),
                    interaction_updated_at = COALESCE(entry_state.interaction_updated_at, excluded.interaction_updated_at),
                    updated_at = excluded.updated_at",
            )
            .bind(canon_resolved)
            .bind(inbox_state)
            .bind(read_at)
            .bind(progress)
            .bind(interaction_ts)
            .bind(now)
            .execute(&mut *tx)
            .await?;
            sqlx::query("DELETE FROM entry_state WHERE entry_id = ?")
                .bind(dup_resolved)
                .execute(&mut *tx)
                .await?;
        }

        // Move collection memberships (both as member and as parent collection)
        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
             SELECT collection_id, ?, position, ?
             FROM collection_entries
             WHERE entry_id = ? AND collection_id != ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .bind(canon_resolved)
        .execute(&mut *tx)
        .await?;
        sqlx::query("DELETE FROM collection_entries WHERE entry_id = ?")
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
             SELECT ?, entry_id, position, ?
             FROM collection_entries
             WHERE collection_id = ? AND entry_id != ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .bind(canon_resolved)
        .execute(&mut *tx)
        .await?;
        sqlx::query("DELETE FROM collection_entries WHERE collection_id = ?")
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Move consumption logs
        sqlx::query("UPDATE consumption_log SET entry_id = ? WHERE entry_id = ?")
            .bind(canon_resolved)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        // Rewrite comparisons involving `dup_resolved`
        sqlx::query("UPDATE comparisons SET scope_collection_id = ? WHERE scope_collection_id = ?")
            .bind(canon_resolved)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        let like_dup = format!("%{dup_resolved}%");
        let comp_rows: Vec<(String, String)> =
            sqlx::query_as("SELECT id, ordering_json FROM comparisons WHERE ordering_json LIKE ?")
                .bind(&like_dup)
                .fetch_all(&mut *tx)
                .await?;
        for (comp_id, ord_json) in comp_rows {
            let parsed: Vec<Vec<String>> = serde_json::from_str(&ord_json).unwrap_or_default();
            let rewritten = rewrite_ordering_after_merge(&parsed, dup_resolved, canon_resolved);
            let total_items: usize = rewritten.iter().map(Vec::len).sum();
            if total_items < 2 {
                sqlx::query("DELETE FROM comparisons WHERE id = ?")
                    .bind(&comp_id)
                    .execute(&mut *tx)
                    .await?;
            } else {
                let new_json = canonical_ordering_json(&rewritten);
                sqlx::query("UPDATE comparisons SET ordering_json = ? WHERE id = ?")
                    .bind(&new_json)
                    .bind(&comp_id)
                    .execute(&mut *tx)
                    .await?;
            }
        }

        // Update active generator pointer if needed
        sqlx::query(
            "UPDATE collection_generators
             SET current_entry_id = ?, updated_at = ?
             WHERE current_entry_id = ?",
        )
        .bind(canon_resolved)
        .bind(now)
        .bind(dup_resolved)
        .execute(&mut *tx)
        .await?;

        // Mark duplicate as merged tombstone (`entries_fts_au` removes it from FTS automatically)
        sqlx::query("UPDATE entries SET merged_into_id = ?, updated_at = ? WHERE id = ?")
            .bind(canon_resolved)
            .bind(now)
            .bind(dup_resolved)
            .execute(&mut *tx)
            .await?;

        Ok(())
    }

    // =========================================================================
    // Collections & Lists
    // =========================================================================

    pub async fn list_collections(
        &self,
        include_want: bool,
        include_system: bool,
    ) -> CatalogResult<Vec<CollectionSummary>> {
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT e.id, cs.slug, e.title, e.summary, e.kind, cs.mode, cs.collection_role,
                    cs.rule_json, cs.show_in_lists, cs.color,
                    EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) AS has_generator,
                    e.created_at, e.updated_at
             FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL AND cs.collection_role != 'tag' AND cs.collection_role != 'feed'",
        );
        if !include_want {
            qb.push(" AND cs.collection_role != 'want'");
        }
        if !include_system {
            qb.push(" AND (cs.show_in_lists = 1 OR cs.collection_role IN ('list', 'want') OR cs.slug = '1001_albums')");
        }
        qb.push(" ORDER BY cs.collection_role ASC, LOWER(e.title) ASC");
        let rows: Vec<RawCollectionRow> = qb.build_query_as().fetch_all(self.pool).await?;
        let mut out = Vec::with_capacity(rows.len());
        for row in rows {
            out.push(self.summarize_collection_row(row).await?);
        }
        Ok(out)
    }

    pub async fn create_manual_collection(
        &self,
        title: &str,
        slug: Option<&str>,
        role: CollectionRole,
        summary: Option<&str>,
        color: Option<&str>,
    ) -> CatalogResult<CollectionSummary> {
        let clean_title = title.trim();
        if clean_title.is_empty() {
            return Err(CatalogError::InvalidInput("Collection title cannot be empty".to_owned()));
        }
        if !matches!(role, CollectionRole::List | CollectionRole::Want) {
            return Err(CatalogError::InvalidInput(
                "Custom collections must have role 'list' or 'want'".to_owned(),
            ));
        }

        let derived_slug = slug
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map_or_else(|| slugify_title(clean_title), slugify_title);
        if derived_slug.is_empty() {
            return Err(CatalogError::InvalidInput("Collection slug cannot be empty".to_owned()));
        }

        let existing: Option<String> =
            sqlx::query_scalar("SELECT collection_id FROM collection_specs WHERE slug = ?")
                .bind(&derived_slug)
                .fetch_optional(self.pool)
                .await?;
        if existing.is_some() {
            return Err(CatalogError::Conflict(format!(
                "Collection with slug '{derived_slug}' already exists"
            )));
        }

        let id = deterministic_system_collection_id(&format!("custom:{derived_slug}"));
        let now = SyncRepo::now_utc();
        let show_in_lists = role == CollectionRole::List;

        let mut tx = self.pool.begin().await?;
        sqlx::query(
            "INSERT INTO entries (id, kind, title, summary, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(clean_title)
        .bind(summary.map(str::trim).filter(|s| !s.is_empty()))
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT INTO collection_specs
             (collection_id, slug, mode, collection_role, rule_json, show_in_lists, color)
             VALUES (?, ?, 'manual', ?, NULL, ?, ?)",
        )
        .bind(&id)
        .bind(&derived_slug)
        .bind(role)
        .bind(show_in_lists)
        .bind(color.map(str::trim).filter(|s| !s.is_empty()))
        .execute(&mut *tx)
        .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('slug', ?, ?)",
        )
        .bind(&derived_slug)
        .bind(&id)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        self.get_collection_summary(&id).await
    }

    pub async fn get_collection_summary(
        &self,
        slug_or_id: &str,
    ) -> CatalogResult<CollectionSummary> {
        let raw = self.lookup_collection_row(slug_or_id, false).await?;
        self.summarize_collection_row(raw).await
    }

    /// Resolves or deterministically creates the `Want` queue collection for `target`
    /// (e.g. `"movie"` / `"movies"` -> `"want:movies"`, or an existing `want` collection).
    pub async fn get_or_create_want_collection(
        &self,
        target: &str,
    ) -> CatalogResult<CollectionSummary> {
        let trimmed = target.trim();
        if let Ok(existing) = self.lookup_collection_row(trimmed, true).await
            && existing.collection_role == CollectionRole::Want
        {
            return self.summarize_collection_row(existing).await;
        }

        let base_slug = if let Ok(kind) = EntryKind::parse_flexible(trimmed) {
            kind.default_list_slug()
                .ok_or_else(|| {
                    CatalogError::InvalidInput(format!(
                        "Kind '{}' does not have a default want queue",
                        kind.as_str()
                    ))
                })?
                .to_owned()
        } else {
            trimmed.strip_prefix("want:").unwrap_or(trimmed).to_ascii_lowercase().replace('-', "_")
        };

        let want_slug = format!("want:{base_slug}");
        if let Ok(existing) = self.lookup_collection_row(&want_slug, false).await {
            return self.summarize_collection_row(existing).await;
        }

        let id = deterministic_system_collection_id(&want_slug);
        let pretty_label = EntryKind::parse_flexible(&base_slug).map_or_else(
            |_| format!("Want: {base_slug}"),
            |k| format!("Want: {}", k.display_label()),
        );
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        sqlx::query(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?)",
        )
        .bind(&id)
        .bind(&pretty_label)
        .bind(now)
        .bind(now)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "INSERT OR IGNORE INTO collection_specs
             (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
             VALUES (?, ?, 'manual', 'want', NULL, 0)",
        )
        .bind(&id)
        .bind(&want_slug)
        .execute(&mut *tx)
        .await?;
        sqlx::query(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES ('slug', ?, ?)",
        )
        .bind(&want_slug)
        .bind(&id)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;

        self.get_collection_summary(&id).await
    }

    /// Manually adds an entry to a collection, strictly rejecting `feed` and `dynamic` collections.
    pub async fn add_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
        position: Option<f64>,
    ) -> CatalogResult<()> {
        let col = self.lookup_collection_row(slug_or_id, false).await?;
        if col.mode != CollectionMode::Manual {
            return Err(CatalogError::ImmutableCollection { title: col.title, mode: col.mode });
        }
        let target_entry_id = self
            .follow_merged_id(entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))?;
        if target_entry_id == col.id {
            return Err(CatalogError::InvalidInput(
                "A collection cannot contain itself".to_owned(),
            ));
        }

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let nat_key = collection_entry_natural_key(&col.id, &target_entry_id);
        SyncRepo::clear_tombstone(&mut tx, ActivityEntityType::CollectionEntry, &nat_key).await?;

        sqlx::query(
            "INSERT INTO collection_entries (collection_id, entry_id, position, added_at)
             VALUES (?, ?, ?, ?)
             ON CONFLICT(collection_id, entry_id) DO UPDATE SET
                position = COALESCE(excluded.position, collection_entries.position)",
        )
        .bind(&col.id)
        .bind(&target_entry_id)
        .bind(position)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
            .bind(now)
            .bind(&col.id)
            .execute(&mut *tx)
            .await?;

        tx.commit().await?;
        Ok(())
    }

    /// Manually removes an entry from a collection, strictly rejecting `feed` and `dynamic` collections.
    pub async fn remove_collection_entry(
        &self,
        slug_or_id: &str,
        entry_id: &str,
    ) -> CatalogResult<bool> {
        let col = self.lookup_collection_row(slug_or_id, false).await?;
        if col.mode != CollectionMode::Manual {
            return Err(CatalogError::ImmutableCollection { title: col.title, mode: col.mode });
        }
        let target_entry_id =
            self.follow_merged_id(entry_id).await?.unwrap_or_else(|| entry_id.trim().to_owned());

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res =
            sqlx::query("DELETE FROM collection_entries WHERE collection_id = ? AND entry_id = ?")
                .bind(&col.id)
                .bind(&target_entry_id)
                .execute(&mut *tx)
                .await?;
        let removed = res.rows_affected() > 0;
        if removed {
            let nat_key = collection_entry_natural_key(&col.id, &target_entry_id);
            SyncRepo::record_tombstone(&mut tx, ActivityEntityType::CollectionEntry, &nat_key, now)
                .await?;
            sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(&col.id)
                .execute(&mut *tx)
                .await?;
        }
        tx.commit().await?;
        Ok(removed)
    }

    pub async fn want_entry(
        &self,
        input: &UpsertEntryInput,
        target_queue: Option<&str>,
    ) -> CatalogResult<(CollectionSummary, CatalogEntry)> {
        let entry = self.upsert_entry(input).await?;
        let queue_key = match target_queue.map(str::trim).filter(|s| !s.is_empty()) {
            Some(explicit) => explicit.to_owned(),
            None => entry
                .kind
                .default_list_slug()
                .ok_or_else(|| {
                    CatalogError::InvalidInput(format!(
                        "Specify a target want queue for kind '{}'",
                        entry.kind.as_str()
                    ))
                })?
                .to_owned(),
        };
        let queue = self.get_or_create_want_collection(&queue_key).await?;
        self.add_collection_entry(&queue.id, &entry.id, None).await?;
        let refreshed_queue = self.get_collection_summary(&queue.id).await?;
        Ok((refreshed_queue, entry))
    }

    pub async fn unwant_entry(
        &self,
        target_queue: &str,
        query_or_id: &str,
    ) -> CatalogResult<CatalogEntry> {
        let queue = self.get_or_create_want_collection(target_queue).await?;
        let entry = self.resolve_entry(query_or_id, None, Some(&queue.id)).await?;
        self.remove_collection_entry(&queue.id, &entry.id).await?;
        Ok(entry)
    }

    // =========================================================================
    // Consumption Log & Diary
    // =========================================================================

    pub async fn log_consumption(
        &self,
        entry_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ConsumptionLogView> {
        let now = SyncRepo::now_utc();
        let event_ts = ts.unwrap_or(now);
        let mut tx = self.pool.begin().await?;
        let (log_id, canon_id) =
            Self::log_consumption_in_tx(&mut tx, entry_id, status, notes, event_ts, now).await?;
        tx.commit().await?;

        let entry = self
            .get_entry(&canon_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_id}'")))?;
        let row: ConsumptionLogRow = sqlx::query_as(
            "SELECT id, entry_id, ts, status, notes, created_at FROM consumption_log WHERE id = ?",
        )
        .bind(&log_id)
        .fetch_one(self.pool)
        .await?;
        Ok(ConsumptionLogView {
            id: row.id,
            entry,
            ts: row.ts,
            status: row.status,
            notes: row.notes,
            created_at: row.created_at,
        })
    }

    pub(crate) async fn log_consumption_in_tx(
        tx: &mut SqliteConnection,
        entry_id: &str,
        status: ConsumptionStatus,
        notes: Option<&str>,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> CatalogResult<(String, String)> {
        let canon_id = Self::follow_merged_id_in_tx(tx, entry_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{entry_id}'")))?;
        let row_meta: (EntryKind, String) =
            sqlx::query_as("SELECT kind, creators_json FROM entries WHERE id = ?")
                .bind(&canon_id)
                .fetch_one(&mut *tx)
                .await?;
        let (entry_kind, creators_json) = row_meta;

        let clean_notes = notes.map(str::trim).filter(|s| !s.is_empty());
        let log_id = deterministic_consumption_log_id(
            &canon_id,
            event_ts.timestamp_millis(),
            status.as_str(),
        );

        sqlx::query(
            "INSERT INTO consumption_log (id, entry_id, ts, status, notes, created_at)
             VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                notes = COALESCE(excluded.notes, consumption_log.notes)",
        )
        .bind(&log_id)
        .bind(&canon_id)
        .bind(event_ts)
        .bind(status)
        .bind(clean_notes)
        .bind(now)
        .execute(&mut *tx)
        .await?;

        if status == ConsumptionStatus::Consumed {
            remove_from_want_queues_in_tx(tx, &canon_id, now).await?;

            // Engage parent `podcast` / `publication` / `channel` when an episode/article/video is consumed.
            // Note: consuming a `song` intentionally NEVER auto-consumes its parent `album`!
            let parent_feed_cols: Vec<String> = sqlx::query_scalar(
                "SELECT ce.collection_id
                 FROM collection_entries ce
                 JOIN entries pe ON pe.id = ce.collection_id
                 WHERE ce.entry_id = ?
                   AND pe.merged_into_id IS NULL
                   AND pe.kind IN ('podcast', 'publication', 'channel')",
            )
            .bind(&canon_id)
            .fetch_all(&mut *tx)
            .await?;
            for parent_id in parent_feed_cols {
                Self::ensure_once_consumed_in_tx(tx, &parent_id, event_ts, now).await?;
            }

            // Engage `artist` creators when an `album` or `song` is consumed
            if matches!(entry_kind, EntryKind::Album | EntryKind::Song) {
                let creators: Vec<String> =
                    serde_json::from_str(&creators_json).unwrap_or_default();
                for creator in creators {
                    if !creator.trim().is_empty() {
                        let artist_id = Self::ensure_artist_entry_in_tx(tx, &creator, now).await?;
                        sqlx::query(
                            "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, added_at)
                             VALUES (?, ?, ?)",
                        )
                        .bind(&artist_id)
                        .bind(&canon_id)
                        .bind(now)
                        .execute(&mut *tx)
                        .await?;
                        Self::ensure_once_consumed_in_tx(tx, &artist_id, event_ts, now).await?;
                    }
                }
            }
        }

        Ok((log_id, canon_id))
    }

    async fn ensure_once_consumed_in_tx(
        tx: &mut SqliteConnection,
        entry_id: &str,
        event_ts: DateTime<Utc>,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let already: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM consumption_log WHERE entry_id = ? AND status = 'consumed' LIMIT 1",
        )
        .bind(entry_id)
        .fetch_optional(&mut *tx)
        .await?;
        if already.is_none() {
            let log_id =
                deterministic_consumption_log_id(entry_id, event_ts.timestamp_millis(), "consumed");
            sqlx::query(
                "INSERT OR IGNORE INTO consumption_log (id, entry_id, ts, status, notes, created_at)
                 VALUES (?, ?, ?, 'consumed', NULL, ?)",
            )
            .bind(&log_id)
            .bind(entry_id)
            .bind(event_ts)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }
        Ok(())
    }

    pub async fn list_diary(
        &self,
        year: Option<i32>,
        status: Option<ConsumptionStatus>,
        limit: usize,
    ) -> CatalogResult<Vec<ConsumptionLogView>> {
        let cap = i64::try_from(limit.clamp(1, 2_000)).unwrap_or(200);
        let mut qb = QueryBuilder::<Sqlite>::new(
            "SELECT cl.id, cl.entry_id, cl.ts, cl.status, cl.notes, cl.created_at
             FROM consumption_log cl
             JOIN entries e ON e.id = cl.entry_id
             WHERE e.merged_into_id IS NULL
               AND e.kind NOT IN ('artist', 'podcast', 'publication', 'channel')",
        );
        if let Some(y) = year {
            let prefix = format!("{y:04}-");
            qb.push(" AND cl.ts >= ")
                .push_bind(format!("{prefix}01-01T00:00:00Z"))
                .push(" AND cl.ts < ")
                .push_bind(format!("{:04}-01-01T00:00:00Z", y + 1));
        }
        if let Some(st) = status {
            qb.push(" AND cl.status = ").push_bind(st);
        }
        qb.push(" ORDER BY cl.ts DESC, cl.id DESC LIMIT ").push_bind(cap);

        let log_rows: Vec<ConsumptionLogRow> = qb.build_query_as().fetch_all(self.pool).await?;
        let unique_entry_ids: Vec<String> = log_rows
            .iter()
            .map(|r| r.entry_id.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();
        let entry_map = self.load_entries_by_ids(&unique_entry_ids).await?;

        let mut out = Vec::with_capacity(log_rows.len());
        for row in log_rows {
            if let Some(entry) = entry_map.get(&row.entry_id) {
                out.push(ConsumptionLogView {
                    id: row.id,
                    entry: entry.clone(),
                    ts: row.ts,
                    status: row.status,
                    notes: row.notes,
                    created_at: row.created_at,
                });
            }
        }
        Ok(out)
    }

    // =========================================================================
    // Comparisons & EP Ranking
    // =========================================================================

    pub async fn append_comparison(
        &self,
        scope_collection_id: Option<&str>,
        ordering: &[Vec<String>],
        ts: Option<DateTime<Utc>>,
    ) -> CatalogResult<ComparisonRecord> {
        let mut resolved_ordering: Vec<Vec<String>> = Vec::with_capacity(ordering.len());
        for group in ordering {
            let mut resolved_group = Vec::with_capacity(group.len());
            for raw_id in group {
                let canon_id = self
                    .follow_merged_id(raw_id)
                    .await?
                    .ok_or_else(|| CatalogError::NotFound(format!("Entry '{raw_id}'")))?;
                resolved_group.push(canon_id);
            }
            resolved_ordering.push(resolved_group);
        }
        validate_ordering(&resolved_ordering).map_err(CatalogError::InvalidInput)?;

        let clean_scope = match scope_collection_id.map(str::trim).filter(|s| !s.is_empty()) {
            Some(sc) => {
                let col = self.lookup_collection_row(sc, false).await?;
                Some(col.id)
            }
            None => None,
        };

        let now = SyncRepo::now_utc();
        let event_ts = ts.unwrap_or(now);
        let comp_id = deterministic_comparison_id(
            clean_scope.as_deref(),
            event_ts.timestamp_millis(),
            &resolved_ordering,
        );
        let ord_json = canonical_ordering_json(&resolved_ordering);

        sqlx::query(
            "INSERT OR IGNORE INTO comparisons (id, ts, scope_collection_id, ordering_json, created_at)
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(&comp_id)
        .bind(event_ts)
        .bind(&clean_scope)
        .bind(&ord_json)
        .bind(now)
        .execute(self.pool)
        .await?;

        let canonical_groups: Vec<Vec<String>> =
            serde_json::from_str(&ord_json).unwrap_or(resolved_ordering);
        Ok(ComparisonRecord {
            id: comp_id,
            ts: event_ts,
            scope_collection_id: clean_scope,
            ordering: canonical_groups,
            created_at: now,
        })
    }

    pub async fn get_ranked_collection(
        &self,
        slug_or_id: &str,
        want: bool,
    ) -> CatalogResult<RankedCollectionView> {
        let raw_col = if want {
            let summary = self.get_or_create_want_collection(slug_or_id).await?;
            self.lookup_collection_row(&summary.id, false).await?
        } else {
            self.lookup_collection_row(slug_or_id, true).await?
        };
        let is_priority = raw_col.collection_role == CollectionRole::Want;
        let members = self.resolve_rankable_members(&raw_col).await?;
        let member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();

        let orderings = self
            .load_orderings_for_scope(if is_priority { Some(raw_col.id.as_str()) } else { None })
            .await?;
        let member_set: HashSet<&str> = member_ids.iter().map(String::as_str).collect();
        let relevant_comparison_count = orderings
            .iter()
            .filter(|ord| {
                ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
            })
            .count();

        let scores = infer_scores(&member_ids, &orderings);
        let tiers_by_id = if !is_priority && relevant_comparison_count > 0 {
            rescale_to_quantiles(&scores, N_QUANTILES)
                .into_iter()
                .map(|(id, q)| (id, tier_label(q)))
                .collect::<HashMap<_, _>>()
        } else {
            HashMap::new()
        };

        let mut sorted_members = members;
        sorted_members.sort_by(|a, b| {
            let sa = scores.get(&a.id).copied().unwrap_or_default().mean;
            let sb = scores.get(&b.id).copied().unwrap_or_default().mean;
            sb.total_cmp(&sa)
                .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
                .then_with(|| a.id.cmp(&b.id))
        });

        let max_abs = scores.values().map(|s| s.mean.abs()).fold(0.0_f64, f64::max);
        let scale = if max_abs > 0.0 { max_abs } else { 1.0 };

        let rows: Vec<RankedEntryRow> = sorted_members
            .into_iter()
            .enumerate()
            .map(|(idx, entry)| {
                let score = scores.get(&entry.id).copied().unwrap_or_default();
                let tier = tiers_by_id.get(&entry.id).copied();
                let bar_pct = ((score.mean / scale + 1.0) * 50.0).clamp(2.0, 100.0);
                RankedEntryRow { rank: idx + 1, entry, score, tier, bar_pct }
            })
            .collect();

        let summary = self.summarize_collection_row(raw_col).await?;
        Ok(RankedCollectionView {
            collection: summary,
            is_priority_scope: is_priority,
            comparison_count: relevant_comparison_count,
            rows,
        })
    }

    pub async fn select_next_cluster(
        &self,
        slug_or_id: &str,
        want: bool,
        filter: &ClusterSelectFilter,
    ) -> CatalogResult<(CollectionSummary, Vec<CatalogEntry>, HashMap<String, Score>)> {
        let raw_col = if want {
            let summary = self.get_or_create_want_collection(slug_or_id).await?;
            self.lookup_collection_row(&summary.id, false).await?
        } else {
            self.lookup_collection_row(slug_or_id, true).await?
        };
        let is_priority = raw_col.collection_role == CollectionRole::Want;
        let mut members = self.resolve_rankable_members(&raw_col).await?;

        if let Some(creator_q) =
            filter.by_creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let needle = creator_q.to_lowercase();
            members.retain(|e| e.creators.iter().any(|c| c.to_lowercase().contains(&needle)));
        }
        if let Some(parent_id) =
            filter.from_parent_id.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let child_ids: HashSet<String> = sqlx::query_scalar(
                "SELECT entry_id FROM collection_entries WHERE collection_id = ?",
            )
            .bind(parent_id)
            .fetch_all(self.pool)
            .await?
            .into_iter()
            .collect();
            members.retain(|e| child_ids.contains(&e.id));
        }

        let member_ids: Vec<String> = members.iter().map(|m| m.id.clone()).collect();
        let orderings = self
            .load_orderings_for_scope(if is_priority { Some(raw_col.id.as_str()) } else { None })
            .await?;
        let scores = infer_scores(&member_ids, &orderings);

        let mut seed_pool: Option<Vec<String>> = None;
        if !filter.focus_ids.is_empty() {
            let mut resolved_focus = Vec::new();
            for fid in &filter.focus_ids {
                if let Some(canon) = self.follow_merged_id(fid).await? {
                    resolved_focus.push(canon);
                }
            }
            seed_pool = Some(resolved_focus);
        } else if let Some(recent_n) = filter.recent_limit.filter(|&n| n > 0) {
            let recent_ids: Vec<String> = sqlx::query_scalar(
                "SELECT DISTINCT entry_id
                 FROM consumption_log
                 WHERE status = 'consumed'
                 ORDER BY ts DESC
                 LIMIT ?",
            )
            .bind(i64::try_from(recent_n).unwrap_or(50))
            .fetch_all(self.pool)
            .await?;
            if !recent_ids.is_empty() {
                seed_pool = Some(recent_ids);
            }
        }

        let max_k = if filter.max_k == 0 { MAX_K_DEFAULT } else { filter.max_k };
        let force_random = seed_pool.is_none()
            && filter.iteration > 0
            && filter.iteration.is_multiple_of(RANDOM_SEED_EVERY);
        let cluster_ids = {
            let mut rng = rand::rng();
            select_cluster(
                &scores,
                &member_ids,
                max_k,
                force_random,
                seed_pool.as_deref(),
                &mut rng,
            )
        };

        let by_id: HashMap<String, CatalogEntry> =
            members.into_iter().map(|e| (e.id.clone(), e)).collect();
        let cluster_entries: Vec<CatalogEntry> =
            cluster_ids.iter().filter_map(|id| by_id.get(id).cloned()).collect();
        let summary = self.summarize_collection_row(raw_col).await?;
        Ok((summary, cluster_entries, scores))
    }

    // =========================================================================
    // Strict 1001-Albums Generator
    // =========================================================================

    /// Idempotently seeds the bundled 1,101 albums into `entries`, `entry_identifiers`, and
    /// `collection_entries(ALBUMS_1001_COLLECTION_ID)`. Fast no-op once already seeded.
    pub async fn ensure_1001_albums_seeded(&self) -> CatalogResult<usize> {
        let pool_albums = bundled_1001_albums();
        let current_count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM collection_entries WHERE collection_id = ?")
                .bind(ALBUMS_1001_COLLECTION_ID)
                .fetch_one(self.pool)
                .await?;
        if usize::try_from(current_count).unwrap_or(0) >= pool_albums.len() {
            return Ok(0);
        }

        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let mut seeded = 0usize;

        for (idx, album) in pool_albums.iter().enumerate() {
            let creators_json =
                serde_json::to_string(&[album.artist.as_str()]).unwrap_or_else(|_| "[]".to_owned());
            let metadata_json = serde_json::json!({
                "genres": album.genres,
                "styles": album.styles,
            })
            .to_string();
            let spotify_uri = format!("spotify:album:{}", album.spotify_id);

            // Check if an album with this Spotify URI already exists under another ID
            let existing_id: Option<String> = sqlx::query_scalar(
                "SELECT e.id
                 FROM entry_identifiers ei
                 JOIN entries e ON e.id = COALESCE(
                     (SELECT merged_into_id FROM entries WHERE id = ei.entry_id),
                     ei.entry_id
                 )
                 WHERE ei.scheme = 'spotify' AND ei.value = ? AND e.merged_into_id IS NULL
                 LIMIT 1",
            )
            .bind(&spotify_uri)
            .fetch_optional(&mut *tx)
            .await?;

            let target_id = if let Some(eid) = existing_id {
                eid
            } else {
                let res = sqlx::query(
                    "INSERT OR IGNORE INTO entries
                     (id, kind, title, creators_json, url, release_year, thumbnail_url,
                      metadata_json, created_at, updated_at)
                     VALUES (?, 'album', ?, ?, NULL, ?, ?, ?, ?, ?)",
                )
                .bind(&album.id)
                .bind(&album.title)
                .bind(&creators_json)
                .bind(album.year)
                .bind(&album.cover_url)
                .bind(&metadata_json)
                .bind(now)
                .bind(now)
                .execute(&mut *tx)
                .await?;
                if res.rows_affected() > 0 {
                    seeded += 1;
                }
                album.id.clone()
            };

            sqlx::query(
                "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
                 VALUES ('spotify', ?, ?), ('1001albums', ?, ?)",
            )
            .bind(&spotify_uri)
            .bind(&target_id)
            .bind(&album.slug)
            .bind(&target_id)
            .execute(&mut *tx)
            .await?;

            #[allow(clippy::cast_precision_loss)]
            let pos = (idx + 1) as f64;
            sqlx::query(
                "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)",
            )
            .bind(ALBUMS_1001_COLLECTION_ID)
            .bind(&target_id)
            .bind(pos)
            .bind(now)
            .execute(&mut *tx)
            .await?;
        }

        tx.commit().await?;
        Ok(seeded)
    }

    pub async fn get_1001_generator_state(&self) -> CatalogResult<Generator1001State> {
        self.ensure_1001_albums_seeded().await?;
        let gen_row: (bool, Option<String>, Option<DateTime<Utc>>, DateTime<Utc>) = sqlx::query_as(
            "SELECT enabled, current_entry_id, drawn_at, updated_at
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_one(self.pool)
        .await?;
        let (enabled, raw_current_id, drawn_at, updated_at) = gen_row;

        let current_entry = match raw_current_id {
            Some(cid) => self.get_entry(&cid).await?,
            None => None,
        };

        let total_pool: i64 = sqlx::query_scalar(
            "SELECT COUNT(*)
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ? AND e.merged_into_id IS NULL",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_one(self.pool)
        .await?;

        let consumed_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(DISTINCT ce.entry_id)
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ?
               AND e.merged_into_id IS NULL
               AND EXISTS (
                   SELECT 1 FROM consumption_log cl
                   WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
               )",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_one(self.pool)
        .await?;

        let abandoned_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(DISTINCT ce.entry_id)
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ?
               AND e.merged_into_id IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM consumption_log cl
                   WHERE cl.entry_id = ce.entry_id AND cl.status = 'consumed'
               )
               AND EXISTS (
                   SELECT 1 FROM consumption_log cl
                   WHERE cl.entry_id = ce.entry_id AND cl.status = 'abandoned'
               )",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_one(self.pool)
        .await?;

        let total = usize::try_from(total_pool).unwrap_or(0);
        let consumed = usize::try_from(consumed_count).unwrap_or(0);
        let abandoned = usize::try_from(abandoned_count).unwrap_or(0);
        let remaining = total.saturating_sub(consumed + abandoned);

        Ok(Generator1001State {
            collection_id: ALBUMS_1001_COLLECTION_ID.to_owned(),
            enabled,
            current_entry,
            drawn_at,
            updated_at,
            total_pool: total,
            consumed_count: consumed,
            abandoned_count: abandoned,
            remaining_count: remaining,
        })
    }

    /// Enables or disables the `1001 Albums` generator.
    /// Never auto-rotates: if `current_entry_id` is already drawn (and still unconsumed/unabandoned),
    /// enabling preserves that exact active album; only draws when `current_entry_id IS NULL`.
    pub async fn set_1001_generator_enabled(
        &self,
        enabled: bool,
    ) -> CatalogResult<Generator1001State> {
        self.ensure_1001_albums_seeded().await?;
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let cur: (Option<String>, Option<DateTime<Utc>>) = sqlx::query_as(
            "SELECT current_entry_id, drawn_at
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_one(&mut *tx)
        .await?;
        let (mut current_id, mut drawn_at) = cur;

        // If `current_id` was already finished or abandoned externally (e.g. via CLI `roche consumed`),
        // clear it so enabling draws a fresh album; otherwise strictly preserve `current_id`.
        if let Some(cid) = &current_id {
            let done: Option<i64> = sqlx::query_scalar(
                "SELECT 1 FROM consumption_log
                 WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
                 LIMIT 1",
            )
            .bind(cid)
            .fetch_optional(&mut *tx)
            .await?;
            if done.is_some() {
                current_id = None;
            }
        }

        if enabled && current_id.is_none() {
            current_id = Self::draw_random_remaining_1001_in_tx(&mut tx).await?;
            if current_id.is_some() {
                drawn_at = Some(now);
            }
        }

        sqlx::query(
            "UPDATE collection_generators
             SET enabled = ?, current_entry_id = ?, drawn_at = ?, updated_at = ?
             WHERE collection_id = ?",
        )
        .bind(enabled)
        .bind(&current_id)
        .bind(drawn_at)
        .bind(now)
        .bind(ALBUMS_1001_COLLECTION_ID)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        self.get_1001_generator_state().await
    }

    pub async fn finish_active_1001_album(
        &self,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, Generator1001State)> {
        self.advance_active_1001_album(ConsumptionStatus::Consumed, notes).await
    }

    pub async fn abandon_active_1001_album(
        &self,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, Generator1001State)> {
        self.advance_active_1001_album(ConsumptionStatus::Abandoned, notes).await
    }

    async fn advance_active_1001_album(
        &self,
        status: ConsumptionStatus,
        notes: Option<&str>,
    ) -> CatalogResult<(CatalogEntry, Generator1001State)> {
        self.ensure_1001_albums_seeded().await?;
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

        let gen_row: (bool, Option<String>) = sqlx::query_as(
            "SELECT enabled, current_entry_id
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_one(&mut *tx)
        .await?;
        let (enabled, current_id) = gen_row;
        let active_id = current_id.ok_or_else(|| {
            CatalogError::InvalidInput("No active 1001 Album is currently drawn".to_owned())
        })?;

        let (_, canon_finished_id) =
            Self::log_consumption_in_tx(&mut tx, &active_id, status, notes, now, now).await?;

        let next_id =
            if enabled { Self::draw_random_remaining_1001_in_tx(&mut tx).await? } else { None };
        let next_drawn_at = next_id.as_ref().map(|_| now);

        sqlx::query(
            "UPDATE collection_generators
             SET current_entry_id = ?, drawn_at = ?, updated_at = ?
             WHERE collection_id = ?",
        )
        .bind(&next_id)
        .bind(next_drawn_at)
        .bind(now)
        .bind(ALBUMS_1001_COLLECTION_ID)
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;

        let finished_entry = self
            .get_entry(&canon_finished_id)
            .await?
            .ok_or_else(|| CatalogError::NotFound(format!("Entry '{canon_finished_id}'")))?;
        let next_state = self.get_1001_generator_state().await?;
        Ok((finished_entry, next_state))
    }

    pub(crate) async fn draw_random_remaining_1001_in_tx(
        tx: &mut SqliteConnection,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let remaining: Vec<String> = sqlx::query_scalar(
            "SELECT ce.entry_id
             FROM collection_entries ce
             JOIN entries e ON e.id = ce.entry_id
             WHERE ce.collection_id = ?
               AND e.merged_into_id IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM consumption_log cl
                   WHERE cl.entry_id = ce.entry_id AND cl.status IN ('consumed', 'abandoned')
               )
             ORDER BY ce.position ASC, ce.entry_id ASC",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_all(&mut *tx)
        .await?;

        if remaining.is_empty() {
            return Ok(None);
        }
        let mut rng = rand::rng();
        let idx = rng.random_range(0..remaining.len());
        Ok(Some(remaining[idx].clone()))
    }

    // =========================================================================
    // Internal Query Helpers
    // =========================================================================

    async fn follow_merged_id(&self, id: &str) -> CatalogResult<Option<String>> {
        let mut conn = self.pool.acquire().await?;
        Ok(Self::follow_merged_id_in_tx(&mut conn, id).await?)
    }

    pub(crate) async fn follow_merged_id_in_tx(
        conn: &mut SqliteConnection,
        id: &str,
    ) -> std::result::Result<Option<String>, sqlx::Error> {
        let mut cur = id.trim().to_owned();
        if cur.is_empty() {
            return Ok(None);
        }
        for _ in 0..8 {
            let row: Option<(String, Option<String>)> =
                sqlx::query_as("SELECT id, merged_into_id FROM entries WHERE id = ?")
                    .bind(&cur)
                    .fetch_optional(&mut *conn)
                    .await?;
            match row {
                None => return Ok(None),
                Some((found_id, None)) => return Ok(Some(found_id)),
                Some((_, Some(next_id))) => cur = next_id,
            }
        }
        Ok(Some(cur))
    }

    async fn lookup_collection_row(
        &self,
        slug_or_id: &str,
        allow_kind_alias: bool,
    ) -> CatalogResult<RawCollectionRow> {
        let q = slug_or_id.trim();
        let norm_slug = q.to_ascii_lowercase().replace('-', "_");
        if let Some(row) = sqlx::query_as::<_, RawCollectionRow>(
            "SELECT e.id, cs.slug, e.title, e.summary, e.kind, cs.mode, cs.collection_role,
                    cs.rule_json, cs.show_in_lists, cs.color,
                    EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) AS has_generator,
                    e.created_at, e.updated_at
             FROM collection_specs cs
             JOIN entries e ON e.id = cs.collection_id
             WHERE e.merged_into_id IS NULL
               AND (e.id = ? OR LOWER(cs.slug) = LOWER(?) OR LOWER(cs.slug) = ? OR LOWER(e.title) = LOWER(?))
             LIMIT 1",
        )
        .bind(q)
        .bind(q)
        .bind(&norm_slug)
        .bind(q)
        .fetch_optional(self.pool)
        .await?
        {
            return Ok(row);
        }

        if allow_kind_alias
            && let Ok(kind) = EntryKind::parse_flexible(q)
            && let Some(default_slug) = kind.default_list_slug()
            && let Some(row) = sqlx::query_as::<_, RawCollectionRow>(
                "SELECT e.id, cs.slug, e.title, e.summary, e.kind, cs.mode, cs.collection_role,
                        cs.rule_json, cs.show_in_lists, cs.color,
                        EXISTS (SELECT 1 FROM collection_generators cg WHERE cg.collection_id = e.id) AS has_generator,
                        e.created_at, e.updated_at
                 FROM collection_specs cs
                 JOIN entries e ON e.id = cs.collection_id
                 WHERE e.merged_into_id IS NULL AND cs.slug = ?
                 LIMIT 1",
            )
            .bind(default_slug)
            .fetch_optional(self.pool)
            .await?
        {
            return Ok(row);
        }

        Err(CatalogError::NotFound(format!("Collection '{q}'")))
    }

    async fn summarize_collection_row(
        &self,
        raw: RawCollectionRow,
    ) -> CatalogResult<CollectionSummary> {
        let members = self.resolve_rankable_members(&raw).await?;
        let member_set: HashSet<&str> = members.iter().map(|m| m.id.as_str()).collect();
        let is_priority = raw.collection_role == CollectionRole::Want;
        let orderings = self
            .load_orderings_for_scope(if is_priority { Some(raw.id.as_str()) } else { None })
            .await?;
        let comp_count = orderings
            .iter()
            .filter(|ord| {
                ord.iter().flat_map(|g| g.iter()).any(|id| member_set.contains(id.as_str()))
            })
            .count();

        Ok(CollectionSummary {
            id: raw.id,
            slug: raw.slug,
            title: raw.title,
            summary: raw.summary,
            kind: raw.kind,
            mode: raw.mode,
            role: raw.collection_role,
            rule_json: raw.rule_json,
            show_in_lists: raw.show_in_lists,
            color: raw.color,
            member_count: members.len(),
            comparison_count: comp_count,
            has_generator: raw.has_generator,
            created_at: raw.created_at,
            updated_at: raw.updated_at,
        })
    }

    async fn resolve_rankable_members(
        &self,
        col: &RawCollectionRow,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        let raw_rows: Vec<RawEntryRow> = match col.mode {
            CollectionMode::Manual | CollectionMode::Feed => {
                if col.has_generator {
                    // Generator-backed collections (e.g. `1001_albums`) rank their *consumed* members
                    sqlx::query_as(
                        "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at,
                                e.release_year, e.summary, e.thumbnail_url, e.metadata_json,
                                e.merged_into_id, e.created_at, e.updated_at
                         FROM collection_entries ce
                         JOIN entries e ON e.id = ce.entry_id
                         WHERE ce.collection_id = ?
                           AND e.merged_into_id IS NULL
                           AND EXISTS (
                               SELECT 1 FROM consumption_log cl
                               WHERE cl.entry_id = e.id AND cl.status = 'consumed'
                           )
                         ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC",
                    )
                    .bind(&col.id)
                    .fetch_all(self.pool)
                    .await?
                } else {
                    sqlx::query_as(
                        "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at,
                                e.release_year, e.summary, e.thumbnail_url, e.metadata_json,
                                e.merged_into_id, e.created_at, e.updated_at
                         FROM collection_entries ce
                         JOIN entries e ON e.id = ce.entry_id
                         WHERE ce.collection_id = ? AND e.merged_into_id IS NULL
                         ORDER BY ce.position ASC NULLS LAST, ce.added_at DESC, e.id ASC",
                    )
                    .bind(&col.id)
                    .fetch_all(self.pool)
                    .await?
                }
            }
            CollectionMode::Dynamic => {
                let rule: DynamicCollectionRule = col
                    .rule_json
                    .as_deref()
                    .and_then(|j| serde_json::from_str(j).ok())
                    .unwrap_or_default();
                let mut qb = QueryBuilder::<Sqlite>::new(
                    "SELECT e.id, e.kind, e.title, e.creators_json, e.url, e.published_at,
                            e.release_year, e.summary, e.thumbnail_url, e.metadata_json,
                            e.merged_into_id, e.created_at, e.updated_at
                     FROM entries e
                     WHERE e.merged_into_id IS NULL",
                );
                if let Some(k) = rule.kind {
                    qb.push(" AND e.kind = ").push_bind(k);
                } else if !rule.kinds.is_empty() {
                    qb.push(" AND e.kind IN (");
                    let mut sep = qb.separated(", ");
                    for k in &rule.kinds {
                        sep.push_bind(*k);
                    }
                    sep.push_unseparated(")");
                }
                if let Some(consumed_flag) = rule.consumed {
                    if consumed_flag {
                        qb.push(" AND EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id AND cl.status = 'consumed')");
                    } else {
                        qb.push(" AND NOT EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id AND cl.status = 'consumed')");
                    }
                }
                if let Some(st) = rule.status {
                    qb.push(" AND EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id AND cl.status = ")
                        .push_bind(st)
                        .push(")");
                }
                if let Some(creator_filter) =
                    rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty())
                {
                    let escaped = creator_filter
                        .to_lowercase()
                        .replace('\\', "\\\\")
                        .replace('%', "\\%")
                        .replace('_', "\\_");
                    qb.push(" AND LOWER(e.creators_json) LIKE ")
                        .push_bind(format!("%{escaped}%"))
                        .push(" ESCAPE '\\'");
                }
                if let Some(yr) = rule.release_year {
                    qb.push(" AND e.release_year = ").push_bind(yr);
                }
                qb.push(" ORDER BY LOWER(e.title) ASC, e.id ASC");
                qb.build_query_as().fetch_all(self.pool).await?
            }
        };

        self.hydrate_entries(raw_rows).await
    }

    async fn load_orderings_for_scope(
        &self,
        scope_collection_id: Option<&str>,
    ) -> CatalogResult<Vec<Vec<Vec<String>>>> {
        let json_rows: Vec<String> = if let Some(scope_id) = scope_collection_id {
            sqlx::query_scalar(
                "SELECT ordering_json FROM comparisons
                 WHERE scope_collection_id = ?
                 ORDER BY ts ASC, id ASC",
            )
            .bind(scope_id)
            .fetch_all(self.pool)
            .await?
        } else {
            sqlx::query_scalar(
                "SELECT ordering_json FROM comparisons
                 WHERE scope_collection_id IS NULL
                 ORDER BY ts ASC, id ASC",
            )
            .fetch_all(self.pool)
            .await?
        };
        Ok(json_rows
            .into_iter()
            .filter_map(|j| serde_json::from_str::<Vec<Vec<String>>>(&j).ok())
            .collect())
    }

    async fn load_identifiers_for_one(
        &self,
        entry_id: &str,
    ) -> CatalogResult<Vec<EntryIdentifier>> {
        Ok(sqlx::query_as::<_, EntryIdentifier>(
            "SELECT scheme, value FROM entry_identifiers
             WHERE entry_id = ?
             ORDER BY scheme ASC, value ASC",
        )
        .bind(entry_id)
        .fetch_all(self.pool)
        .await?)
    }

    async fn hydrate_entries(
        &self,
        raw_rows: Vec<RawEntryRow>,
    ) -> CatalogResult<Vec<CatalogEntry>> {
        if raw_rows.is_empty() {
            return Ok(Vec::new());
        }
        let ids: Vec<&str> = raw_rows.iter().map(|r| r.id.as_str()).collect();
        let mut ident_map: BTreeMap<String, Vec<EntryIdentifier>> = BTreeMap::new();
        for chunk in ids.chunks(400) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT entry_id, scheme, value FROM entry_identifiers WHERE entry_id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(*id);
            }
            sep.push_unseparated(") ORDER BY scheme ASC, value ASC");
            let rows: Vec<(String, String, String)> =
                qb.build_query_as().fetch_all(self.pool).await?;
            for (eid, scheme, value) in rows {
                ident_map.entry(eid).or_default().push(EntryIdentifier { scheme, value });
            }
        }

        Ok(raw_rows
            .into_iter()
            .map(|r| {
                let idents = ident_map.remove(&r.id).unwrap_or_default();
                r.into_entry(idents)
            })
            .collect())
    }

    async fn load_entries_by_ids(
        &self,
        ids: &[String],
    ) -> CatalogResult<HashMap<String, CatalogEntry>> {
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let mut raw_rows = Vec::with_capacity(ids.len());
        for chunk in ids.chunks(400) {
            let mut qb = QueryBuilder::<Sqlite>::new(
                "SELECT id, kind, title, creators_json, url, published_at, release_year, summary,
                        thumbnail_url, metadata_json, merged_into_id, created_at, updated_at
                 FROM entries WHERE id IN (",
            );
            let mut sep = qb.separated(", ");
            for id in chunk {
                sep.push_bind(id);
            }
            sep.push_unseparated(")");
            let rows: Vec<RawEntryRow> = qb.build_query_as().fetch_all(self.pool).await?;
            raw_rows.extend(rows);
        }
        let hydrated = self.hydrate_entries(raw_rows).await?;
        Ok(hydrated.into_iter().map(|e| (e.id.clone(), e)).collect())
    }
}

pub(super) fn rewrite_ordering_after_merge(
    ordering: &[Vec<String>],
    duplicate_id: &str,
    canonical_id: &str,
) -> Vec<Vec<String>> {
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(ordering.len());
    for group in ordering {
        let mut new_group = Vec::new();
        for id in group {
            let mapped = if id == duplicate_id { canonical_id } else { id.as_str() };
            if seen.insert(mapped.to_owned()) {
                new_group.push(mapped.to_owned());
            }
        }
        if !new_group.is_empty() {
            out.push(new_group);
        }
    }
    out
}

fn slugify_title(title: &str) -> String {
    let mut out = String::with_capacity(title.len());
    let mut prev_sep = false;
    for ch in title.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            prev_sep = false;
        } else if !prev_sep && !out.is_empty() {
            out.push('_');
            prev_sep = true;
        }
    }
    while out.ends_with('_') {
        out.pop();
    }
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::float_cmp, clippy::too_many_lines)]
mod tests {
    use url::Url;

    use super::*;
    use crate::db::{Db, FeedType};

    #[test]
    fn test_strip_album_edition_suffix_and_spotify_normalization() {
        assert_eq!(strip_album_edition_suffix("Abbey Road (Remastered)"), "Abbey Road");
        assert_eq!(
            strip_album_edition_suffix("OK Computer (20th Anniversary Edition) [2017 Remaster]"),
            "OK Computer"
        );
        assert_eq!(
            strip_album_edition_suffix("Stop Making Sense (Live)"),
            "Stop Making Sense (Live)"
        );
        assert_eq!(
            strip_album_edition_suffix("MTV Unplugged in New York (Live)"),
            "MTV Unplugged in New York (Live)"
        );

        let expected_uri = "spotify:album:3GmwKB1tgPZgXeRJZSm9WX";
        assert_eq!(
            normalize_identifier_value(EntryKind::Album, "spotify", "3GmwKB1tgPZgXeRJZSm9WX"),
            expected_uri
        );
        assert_eq!(
            normalize_identifier_value(
                EntryKind::Album,
                "spotify",
                "https://open.spotify.com/album/3GmwKB1tgPZgXeRJZSm9WX?si=123"
            ),
            expected_uri
        );
        assert_eq!(
            deterministic_catalog_entry_id("album", "spotify", expected_uri),
            "83f8410d-71bc-5ea4-911e-06869fe1b039"
        );
    }

    #[tokio::test]
    async fn test_upfront_dedup_edition_merging_artist_synthesis_and_song_vs_album_rule() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let catalog = db.catalog();

        // 1. Seed 1001 Albums and verify logging a Deluxe Edition resolves to the exact 1001 Album
        let seeded = catalog.ensure_1001_albums_seeded().await.unwrap();
        assert!(seeded >= 1_000);

        let sinatra_remaster = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Album),
                title: "In The Wee Small Hours (Deluxe Remastered Edition)".to_owned(),
                creators: vec!["Frank Sinatra".to_owned()],
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        assert_eq!(sinatra_remaster.id, "83f8410d-71bc-5ea4-911e-06869fe1b039");
        assert_eq!(sinatra_remaster.title, "In The Wee Small Hours");

        // 2. Consuming a single `song` on an unheard 1001 album engages `artist` ("Duke Ellington")
        //    but NEVER marks the parent `album` ("Ellington at Newport") consumed!
        let newport_id = "c8edff07-31ae-5f23-9d42-03468251904a";
        let track = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Song),
                title: "Diminuendo and Crescendo in Blue".to_owned(),
                creators: vec!["Duke Ellington".to_owned()],
                parent_collection_id: Some(newport_id.to_owned()),
                parent_position: Some(4.0),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        catalog.log_consumption(&track.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        let artists_ranked = catalog.get_ranked_collection("artists", false).await.unwrap();
        assert!(
            artists_ranked.rows.iter().any(|r| r.entry.title == "Duke Ellington"),
            "Consuming a song must engage its synthesized Artist entry"
        );
        let albums_ranked = catalog.get_ranked_collection("albums", false).await.unwrap();
        assert!(
            albums_ranked.rows.is_empty(),
            "Consuming a single song must NEVER mark its parent album as consumed"
        );
        let gen_state = catalog.get_1001_generator_state().await.unwrap();
        assert_eq!(gen_state.consumed_count, 0);
        assert_eq!(gen_state.remaining_count, gen_state.total_pool);
    }

    #[tokio::test]
    async fn test_collection_mode_enforcement_rejects_feed_and_dynamic_mutations() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let catalog = db.catalog();

        let movie = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Movie),
                title: "Stalker".to_owned(),
                creators: vec!["Andrei Tarkovsky".to_owned()],
                release_year: Some(1979),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        // Dynamic collection (`movies`) rejects manual add & remove
        let dyn_err = catalog.add_collection_entry("movies", &movie.id, None).await.unwrap_err();
        assert!(matches!(
            dyn_err,
            CatalogError::ImmutableCollection { mode: CollectionMode::Dynamic, .. }
        ));

        // Feed collection rejects manual add & remove
        let feed_url = Url::parse("https://example.com/feed.xml").unwrap();
        let feed =
            db.feeds().create(FeedType::Rss, &feed_url, Some("Test Blog"), None).await.unwrap();
        let feed_err = catalog.add_collection_entry(&feed.id, &movie.id, None).await.unwrap_err();
        assert!(matches!(
            feed_err,
            CatalogError::ImmutableCollection { mode: CollectionMode::Feed, .. }
        ));

        // Custom manual collection allows heterogeneous entries (Movie + Book) and global quality bleed
        let custom = catalog
            .create_manual_collection(
                "Desert Island Picks",
                None,
                CollectionRole::List,
                Some("Cross-medium favorites"),
                None,
            )
            .await
            .unwrap();
        let book = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Book),
                title: "Roadside Picnic".to_owned(),
                creators: vec!["Arkady Strugatsky".to_owned(), "Boris Strugatsky".to_owned()],
                release_year: Some(1972),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        catalog.add_collection_entry(&custom.id, &movie.id, Some(1.0)).await.unwrap();
        catalog.add_collection_entry(&custom.id, &book.id, Some(2.0)).await.unwrap();

        // Want queue + local priority vs global quality isolation + auto-promotion on consume
        let (want_movies, _) = catalog
            .want_entry(
                &UpsertEntryInput {
                    kind: Some(EntryKind::Movie),
                    title: "Solaris".to_owned(),
                    release_year: Some(1972),
                    ..UpsertEntryInput::default()
                },
                None,
            )
            .await
            .unwrap();
        let solaris = catalog.resolve_entry("Solaris", Some(EntryKind::Movie), None).await.unwrap();
        catalog.add_collection_entry(&want_movies.id, &movie.id, None).await.unwrap();

        // Local priority comparison: Solaris > Stalker inside `want:movies`
        catalog
            .append_comparison(
                Some(&want_movies.id),
                &[vec![solaris.id.clone()], vec![movie.id.clone()]],
                None,
            )
            .await
            .unwrap();
        let want_view = catalog.get_ranked_collection("movies", true).await.unwrap();
        assert!(want_view.is_priority_scope);
        assert_eq!(want_view.rows[0].entry.id, solaris.id);
        assert_eq!(want_view.rows[0].tier, None, "Priority queues must not emit S-D quality tiers");

        // Global quality comparison: Stalker > Book (bleeds into heterogeneous `custom` list, unaffected by want priority)
        catalog
            .append_comparison(None, &[vec![movie.id.clone()], vec![book.id.clone()]], None)
            .await
            .unwrap();
        let custom_view =
            catalog.get_ranked_collection(&custom.slug.unwrap(), false).await.unwrap();
        assert!(!custom_view.is_priority_scope);
        assert_eq!(custom_view.rows[0].entry.id, movie.id);
        assert_eq!(custom_view.rows[0].tier, Some('S'));
        assert_eq!(custom_view.rows[1].entry.id, book.id);
        assert_eq!(custom_view.rows[1].tier, Some('D'));

        // Consuming Solaris auto-removes it from `want:movies` and places it on `movies`
        catalog
            .log_consumption(&solaris.id, ConsumptionStatus::Consumed, Some("Masterpiece"), None)
            .await
            .unwrap();
        let want_after = catalog.get_ranked_collection("movies", true).await.unwrap();
        assert_eq!(want_after.rows.len(), 1);
        assert_eq!(want_after.rows[0].entry.id, movie.id);
    }

    #[tokio::test]
    async fn test_post_hoc_merge_folds_identifiers_logs_and_comparisons() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let catalog = db.catalog();

        let a = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Article),
                title: "Original Essay".to_owned(),
                url: Some("https://author.example.com/essay".to_owned()),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let b = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Article),
                title: "Syndicated Essay Copy".to_owned(),
                url: Some("https://syndicate.example.com/essay".to_owned()),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();
        let c = catalog
            .upsert_entry(&UpsertEntryInput {
                kind: Some(EntryKind::Article),
                title: "Other Essay".to_owned(),
                url: Some("https://author.example.com/other".to_owned()),
                ..UpsertEntryInput::default()
            })
            .await
            .unwrap();

        catalog.log_consumption(&a.id, ConsumptionStatus::Consumed, None, None).await.unwrap();
        catalog
            .log_consumption(&b.id, ConsumptionStatus::Consumed, Some("Re-read"), None)
            .await
            .unwrap();
        catalog.log_consumption(&c.id, ConsumptionStatus::Consumed, None, None).await.unwrap();

        // Comparison 1: B > C; Comparison 2: A > B (becomes trivial 1-item after merge and is dropped)
        catalog
            .append_comparison(None, &[vec![b.id.clone()], vec![c.id.clone()]], None)
            .await
            .unwrap();
        catalog
            .append_comparison(None, &[vec![a.id.clone()], vec![b.id.clone()]], None)
            .await
            .unwrap();

        let merged = catalog.merge_entries(&b.id, &a.id).await.unwrap();
        assert_eq!(merged.id, a.id);
        assert_eq!(
            merged.identifiers.len(),
            2,
            "Both canonical and syndicated URLs must attach to A"
        );

        // Looking up `b.id` transparently resolves to `a.id`
        let resolved_b = catalog.get_entry(&b.id).await.unwrap().unwrap();
        assert_eq!(resolved_b.id, a.id);

        // Ranked articles now has A > C cleanly with B gone
        let ranked = catalog.get_ranked_collection("articles", false).await.unwrap();
        assert_eq!(ranked.rows.len(), 2);
        assert_eq!(ranked.rows[0].entry.id, a.id);
        assert_eq!(ranked.rows[1].entry.id, c.id);
    }

    #[tokio::test]
    async fn test_strict_1001_albums_generator_never_auto_rotates_and_advances_on_finish_or_abandon()
     {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let catalog = db.catalog();

        let initial = catalog.get_1001_generator_state().await.unwrap();
        assert!(!initial.enabled);
        assert!(initial.current_entry.is_none());
        assert_eq!(initial.total_pool, 1_101);

        // Enabling draws one album
        let enabled1 = catalog.set_1001_generator_enabled(true).await.unwrap();
        assert!(enabled1.enabled);
        let draw1 = enabled1.current_entry.unwrap();

        // Calling enable again or disable -> enable MUST preserve the exact same active album (never auto-rotates!)
        let enabled_again = catalog.set_1001_generator_enabled(true).await.unwrap();
        assert_eq!(enabled_again.current_entry.unwrap().id, draw1.id);
        catalog.set_1001_generator_enabled(false).await.unwrap();
        let reenabled = catalog.set_1001_generator_enabled(true).await.unwrap();
        assert_eq!(reenabled.current_entry.unwrap().id, draw1.id);

        // Finishing logs `consumed` and draws next
        let (finished, after_finish) =
            catalog.finish_active_1001_album(Some("Loved side B")).await.unwrap();
        assert_eq!(finished.id, draw1.id);
        assert_eq!(after_finish.consumed_count, 1);
        assert_eq!(after_finish.abandoned_count, 0);
        assert_eq!(after_finish.remaining_count, 1_100);
        let draw2 = after_finish.current_entry.unwrap();
        assert_ne!(draw2.id, draw1.id);

        // Abandoning logs `abandoned` and draws next
        let (abandoned, after_abandon) =
            catalog.abandon_active_1001_album(Some("Not for me")).await.unwrap();
        assert_eq!(abandoned.id, draw2.id);
        assert_eq!(after_abandon.consumed_count, 1);
        assert_eq!(after_abandon.abandoned_count, 1);
        assert_eq!(after_abandon.remaining_count, 1_099);
        assert_ne!(after_abandon.current_entry.unwrap().id, draw2.id);
    }
}
