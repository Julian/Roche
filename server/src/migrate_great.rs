use std::{
    collections::{BTreeMap, HashMap, HashSet},
    path::{Path, PathBuf},
    str::FromStr,
    time::Duration,
};

use anyhow::{Context, Result};
use chrono::{DateTime, NaiveDate, NaiveDateTime, TimeZone, Utc};
use figment::{
    Figment,
    providers::{Format, Toml},
};
use serde::{Deserialize, Serialize};
use sqlx::{
    SqliteConnection, SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions, SqliteSynchronous},
};

use crate::{
    albums_pool::{BundledAlbumEntry, bundled_1001_albums},
    db::{
        ALBUMS_1001_COLLECTION_ID, ActivityAction, ActivityEntityType, ConsumptionStatus,
        EntryIdentifier, EntryKind, SyncRepo, canonical_ordering_json, canonicalize_sync_url,
        choose_deterministic_entry_id, collection_entry_natural_key, deterministic_comparison_id,
        deterministic_consumption_log_id, deterministic_system_collection_id,
        deterministic_title_entry_id, normalize_identifier_scheme, normalize_identifier_value,
        prepare_pre_v010_schema_if_needed, strip_album_edition_suffix,
    },
};

const READ_PLAYED: i64 = 1;
const MAX_STAR_RATING: i64 = 5;

const KIND_PLURAL_PAIRS: [(EntryKind, &str, &str); 9] = [
    (EntryKind::Movie, "movies", "Movies"),
    (EntryKind::Tv, "tv", "TV"),
    (EntryKind::Song, "songs", "Songs"),
    (EntryKind::Album, "albums", "Albums"),
    (EntryKind::Artist, "artists", "Artists"),
    (EntryKind::Podcast, "podcasts", "Podcasts"),
    (EntryKind::PodcastEpisode, "podcast_episodes", "Podcast Episodes"),
    (EntryKind::Book, "books", "Books"),
    (EntryKind::Game, "games", "Games"),
];

const ORDERED_USER_ITEM_KINDS: [EntryKind; 9] = [
    EntryKind::Artist,
    EntryKind::Album,
    EntryKind::Podcast,
    EntryKind::Movie,
    EntryKind::Tv,
    EntryKind::Book,
    EntryKind::Game,
    EntryKind::Song,
    EntryKind::PodcastEpisode,
];

/// Summary statistics returned by [`migrate_great_database`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MigrationStats {
    pub seeded_1001_albums: usize,
    pub entries_upserted: usize,
    pub want_entries_added: usize,
    pub consumption_logs_added: usize,
    pub comparisons_added: usize,
    pub active_1001_album_id: Option<String>,
}

impl MigrationStats {
    #[must_use]
    const fn has_mutations(&self) -> bool {
        self.seeded_1001_albums > 0
            || self.entries_upserted > 0
            || self.want_entries_added > 0
            || self.consumption_logs_added > 0
            || self.comparisons_added > 0
    }
}

/// Migrates a Great repository (`great.toml`, `items/*.toml`, `want/*.toml`, `log/*.jsonl`,
/// `comparisons/*.jsonl`, `sources/*.json`) into Roche's unified SQLite database.
///
/// When `dry_run` is `true`, all SQL statements execute inside a transaction that is rolled back
/// before returning, leaving `db_path` untouched on disk.
pub async fn migrate_great_database(
    db_path: &Path,
    great_dir: &Path,
    pool_json: Option<&Path>,
    dry_run: bool,
) -> Result<MigrationStats> {
    let great_toml = great_dir.join("great.toml");
    if !great_toml.is_file() {
        anyhow::bail!("No great.toml found in {}", great_dir.display());
    }

    let custom_pool = if let Some(pool_path) = pool_json {
        if !pool_path.is_file() {
            anyhow::bail!("Missing 1001 albums pool JSON at {}", pool_path.display());
        }
        let raw = tokio::fs::read_to_string(pool_path)
            .await
            .with_context(|| format!("Failed to read {}", pool_path.display()))?;
        let parsed: Vec<BundledAlbumEntry> = serde_json::from_str(&raw)
            .with_context(|| format!("Invalid 1001 albums pool JSON in {}", pool_path.display()))?;
        Some(parsed)
    } else {
        None
    };
    let pool_slice: &[BundledAlbumEntry] =
        custom_pool.as_deref().unwrap_or_else(|| bundled_1001_albums());

    let pool = open_migration_pool(db_path, dry_run).await?;
    let mut tx = pool.begin().await?;
    let mut migrator = GreatMigrator::new(&mut tx, great_dir, pool_slice);
    let stats = migrator.run().await?;

    if dry_run {
        tx.rollback().await?;
    } else {
        if stats.has_mutations() {
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Entry,
                ALBUMS_1001_COLLECTION_ID,
                ActivityAction::Update,
                SyncRepo::now_utc(),
            )
            .await?;
        }
        tx.commit().await?;
    }
    pool.close().await;
    Ok(stats)
}

async fn open_migration_pool(db_path: &Path, dry_run: bool) -> Result<SqlitePool> {
    let db_str = db_path.to_str().unwrap_or_default();
    let use_memory = dry_run && !db_str.starts_with("sqlite:") && !db_path.exists();

    let file_path_for_perms =
        (!use_memory && !db_str.starts_with("sqlite:") && !dry_run).then_some(db_path);
    let connect_options = if use_memory {
        SqliteConnectOptions::from_str("sqlite::memory:")?
            .foreign_keys(true)
            .busy_timeout(Duration::from_secs(30))
    } else if db_str.starts_with("sqlite:") {
        SqliteConnectOptions::from_str(db_str)?
            .create_if_missing(!dry_run)
            .foreign_keys(true)
            .busy_timeout(Duration::from_secs(30))
    } else {
        if let Some(file_path) = file_path_for_perms {
            if let Some(parent) = file_path.parent()
                && !parent.as_os_str().is_empty()
            {
                crate::media::MediaStore::ensure_private_dir(parent).await?;
            }
            crate::media::MediaStore::precreate_or_tighten_private_file(file_path).await?;
        }
        let mut opts = SqliteConnectOptions::new()
            .filename(db_path)
            .create_if_missing(!dry_run)
            .foreign_keys(true)
            .busy_timeout(Duration::from_secs(30));
        if !dry_run {
            opts = opts.journal_mode(SqliteJournalMode::Wal).synchronous(SqliteSynchronous::Normal);
        }
        opts
    };

    let pool = SqlitePoolOptions::new().max_connections(1).connect_with(connect_options).await?;

    let has_entries_table: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'entries' LIMIT 1",
    )
    .fetch_optional(&pool)
    .await?;
    let has_sqlx_migrations: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '_sqlx_migrations' LIMIT 1",
    )
    .fetch_optional(&pool)
    .await?;

    // Run embedded migrations unless the database was already initialized out-of-band via raw SQL
    // scripts without `_sqlx_migrations` (e.g. standalone test fixtures).
    if has_entries_table.is_none() || has_sqlx_migrations.is_some() {
        prepare_pre_v010_schema_if_needed(&pool).await?;
        sqlx::migrate!("./migrations").run(&pool).await?;
    }
    if let Some(file_path) = file_path_for_perms {
        crate::db::tighten_sqlite_sidecar_permissions(file_path).await;
    }

    Ok(pool)
}

#[derive(Debug, Clone)]
struct RawGreatItem {
    id: String,
    kind: EntryKind,
    title: String,
    year: Option<i32>,
    creators: Vec<String>,
    parent_id: Option<String>,
    external_ids: BTreeMap<String, String>,
    metadata: serde_json::Map<String, serde_json::Value>,
    thumbnail_url: Option<String>,
    url: Option<String>,
    is_user_source: bool,
}

#[derive(Debug, Default, Deserialize)]
struct GreatConfigFile {
    #[serde(default)]
    lists: Vec<GreatListDef>,
}

#[derive(Debug, Default, Deserialize)]
struct GreatListDef {
    #[serde(default)]
    name: String,
    #[serde(default)]
    kind: String,
}

#[derive(Debug, Default, Deserialize)]
struct GreatItemsToml {
    #[serde(default)]
    items: Vec<GreatTomlItem>,
}

#[derive(Debug, Default, Deserialize)]
struct GreatTomlItem {
    #[serde(default)]
    id: Option<String>,
    title: String,
    #[serde(default)]
    year: Option<i32>,
    #[serde(default)]
    creators: Vec<String>,
    #[serde(default)]
    parent_id: Option<String>,
    #[serde(default)]
    external_ids: BTreeMap<String, serde_json::Value>,
    #[serde(default)]
    metadata: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AlbumsGeneratorFile {
    #[serde(default)]
    paused: bool,
    #[serde(default)]
    current_album: Option<AgAlbumObj>,
    #[serde(default)]
    history: Vec<AgHistoryRow>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgHistoryRow {
    #[serde(default)]
    generated_at: Option<String>,
    #[serde(default)]
    revealed_album: bool,
    #[serde(default)]
    rating: Option<i64>,
    #[serde(default)]
    review: Option<String>,
    #[serde(default)]
    album: Option<AgAlbumObj>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AgAlbumObj {
    #[serde(default)]
    uuid: Option<String>,
    #[serde(default)]
    slug: Option<String>,
    #[serde(default)]
    spotify_id: Option<String>,
    #[serde(default)]
    apple_music_id: Option<String>,
    #[serde(default)]
    tidal_id: Option<String>,
    #[serde(default)]
    youtube_music_id: Option<String>,
    #[serde(default)]
    qobuz_id: Option<String>,
    #[serde(default)]
    deezer_id: Option<String>,
    #[serde(default)]
    wikipedia_url: Option<String>,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    artist: Option<String>,
    #[serde(default)]
    release_date: Option<String>,
    #[serde(default)]
    images: Vec<AgImageObj>,
    #[serde(default)]
    image: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct AgImageObj {
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    width: Option<i64>,
}

#[derive(Debug, Default, Deserialize)]
struct AntennaPodFile {
    #[serde(default)]
    feeds: Vec<AntennaPodFeed>,
    #[serde(default)]
    episodes: Vec<AntennaPodEpisode>,
}

#[derive(Debug, Default, Deserialize)]
struct AntennaPodFeed {
    #[serde(default)]
    feed_url: Option<String>,
    #[serde(default)]
    feed_identifier: Option<serde_json::Value>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    author: Option<String>,
    #[serde(default)]
    image_url: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct AntennaPodEpisode {
    #[serde(default)]
    feed_url: Option<String>,
    #[serde(default)]
    item_identifier: Option<String>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    duration_ms: Option<i64>,
    #[serde(default)]
    read: Option<i64>,
    #[serde(default)]
    completed_at_ms: Option<i64>,
    #[serde(default)]
    is_favorite: Option<bool>,
    #[serde(default)]
    image_url: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct SpotifySourceFile {
    #[serde(default)]
    albums: Vec<SpotifyAlbumRow>,
    #[serde(default)]
    tracks: Vec<SpotifyTrackRow>,
    #[serde(default)]
    completions: Vec<SpotifyCompletionRow>,
}

#[derive(Debug, Default, Deserialize)]
struct SpotifyAlbumRow {
    id: String,
    title: String,
    #[serde(default)]
    artist: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct SpotifyTrackRow {
    uri: String,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    artist: Option<String>,
    #[serde(default)]
    album_id: Option<String>,
}

#[derive(Debug, Default, Deserialize)]
struct SpotifyCompletionRow {
    track_uri: String,
    ts: String,
}

#[derive(Debug, Deserialize)]
struct GreatLogRecord {
    ts: String,
    kind: String,
    item: String,
    status: String,
    #[serde(default)]
    notes: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GreatComparisonRecord {
    ts: String,
    ordering: Vec<Vec<String>>,
}

struct GreatMigrator<'a> {
    tx: &'a mut SqliteConnection,
    great_root: &'a Path,
    pool_albums: &'a [BundledAlbumEntry],
    now: DateTime<Utc>,
    stats: MigrationStats,
    id_map: HashMap<(EntryKind, String), String>,
    any_id_map: HashMap<String, String>,
    album_edition_index: HashMap<(String, String), String>,
}

impl<'a> GreatMigrator<'a> {
    fn new(
        tx: &'a mut SqliteConnection,
        great_root: &'a Path,
        pool_albums: &'a [BundledAlbumEntry],
    ) -> Self {
        Self {
            tx,
            great_root,
            pool_albums,
            now: SyncRepo::now_utc(),
            stats: MigrationStats::default(),
            id_map: HashMap::new(),
            any_id_map: HashMap::new(),
            album_edition_index: HashMap::new(),
        }
    }

    async fn run(&mut self) -> Result<MigrationStats> {
        self.seed_1001_albums_pool().await?;
        self.index_existing_albums().await?;

        let list_name_to_kind = self.load_great_config_lists()?;
        let user_source_entry_ids = self.import_user_items().await?;
        let pending_wants = self.import_want_items().await?;
        self.import_albumsgenerator_source().await?;
        self.import_antennapod_source().await?;
        self.import_spotify_source().await?;
        self.import_user_logs().await?;
        self.ensure_user_catalog_consumed_logs(&user_source_entry_ids).await?;
        self.engage_music_artists_from_consumed_works().await?;
        self.attach_want_memberships(&pending_wants).await?;
        self.link_music_works_to_existing_artists().await?;
        self.import_comparisons(&list_name_to_kind).await?;

        Ok(self.stats.clone())
    }

    async fn seed_1001_albums_pool(&mut self) -> Result<()> {
        sqlx::query(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'collection', '1001 Albums', ?, ?)",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .bind(self.now)
        .bind(self.now)
        .execute(&mut *self.tx)
        .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO collection_specs
                (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
             VALUES (?, '1001_albums', 'manual', 'system', NULL, 0)",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .execute(&mut *self.tx)
        .await?;

        sqlx::query(
            "INSERT OR IGNORE INTO collection_generators (collection_id, enabled, updated_at)
             VALUES (?, 0, ?)",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .bind(self.now)
        .execute(&mut *self.tx)
        .await?;

        for (idx, album) in self.pool_albums.iter().enumerate() {
            let spotify_uri = format!("spotify:album:{}", album.spotify_id);
            let existing =
                self.lookup_entry_by_identifier(EntryKind::Album, "spotify", &spotify_uri).await?;
            let target_id = if let Some(eid) = existing {
                eid
            } else if let Some(merged) =
                self.follow_merged_id(&album.id, Some(EntryKind::Album)).await?
            {
                merged
            } else {
                let creators_json = serde_json::to_string(&[album.artist.as_str()])?;
                let metadata_json = serde_json::json!({
                    "genres": album.genres,
                    "styles": album.styles,
                })
                .to_string();
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
                .bind(self.now)
                .bind(self.now)
                .execute(&mut *self.tx)
                .await?;
                if res.rows_affected() > 0 {
                    self.stats.seeded_1001_albums += 1;
                }
                album.id.clone()
            };

            self.attach_identifier(&target_id, EntryKind::Album, "spotify", &spotify_uri).await?;
            self.attach_identifier(&target_id, EntryKind::Album, "1001albums", &album.slug).await?;
            #[allow(clippy::cast_precision_loss)]
            let position = (idx + 1) as f64;
            sqlx::query(
                "INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)",
            )
            .bind(ALBUMS_1001_COLLECTION_ID)
            .bind(&target_id)
            .bind(position)
            .bind(self.now)
            .execute(&mut *self.tx)
            .await?;

            self.register_id_mapping(EntryKind::Album, &spotify_uri, &target_id);
        }

        Ok(())
    }

    async fn index_existing_albums(&mut self) -> Result<()> {
        let rows: Vec<(String, String, String)> = sqlx::query_as(
            "SELECT id, title, creators_json
             FROM entries
             WHERE kind = 'album' AND merged_into_id IS NULL",
        )
        .fetch_all(&mut *self.tx)
        .await?;

        for (entry_id, title, creators_json) in rows {
            let creators: Vec<String> = serde_json::from_str(&creators_json).unwrap_or_default();
            if let Some(key) = Self::album_match_key(&creators, &title) {
                self.album_edition_index.entry(key).or_insert(entry_id);
            }
        }
        Ok(())
    }

    fn album_match_key(creators: &[String], title: &str) -> Option<(String, String)> {
        let artist = creators.first()?.trim().to_lowercase();
        let base = strip_album_edition_suffix(title).trim().to_lowercase();
        if artist.is_empty() || base.is_empty() { None } else { Some((artist, base)) }
    }

    fn load_great_config_lists(&self) -> Result<HashMap<String, EntryKind>> {
        let mut mapping: HashMap<String, EntryKind> = KIND_PLURAL_PAIRS
            .iter()
            .map(|(kind, plural, _)| ((*plural).to_owned(), *kind))
            .collect();

        let cfg_path = self.great_root.join("great.toml");
        if !cfg_path.is_file() {
            return Ok(mapping);
        }
        let raw = std::fs::read_to_string(&cfg_path)
            .with_context(|| format!("Failed to read {}", cfg_path.display()))?;
        let parsed: GreatConfigFile = Figment::from(Toml::string(&raw))
            .extract()
            .with_context(|| format!("Failed to parse {}", cfg_path.display()))?;

        for lst in parsed.lists {
            let name = lst.name.trim();
            let raw_kind = lst.kind.trim();
            if !name.is_empty()
                && !raw_kind.is_empty()
                && let Ok(kind) = EntryKind::parse_flexible(raw_kind)
            {
                mapping.insert(name.to_owned(), kind);
            }
        }
        Ok(mapping)
    }

    fn read_toml_items(
        path: &Path,
        kind: EntryKind,
        is_user_source: bool,
    ) -> Result<Vec<RawGreatItem>> {
        if !path.is_file() {
            return Ok(Vec::new());
        }
        let raw = std::fs::read_to_string(path)
            .with_context(|| format!("Failed to read {}", path.display()))?;
        let parsed: GreatItemsToml = Figment::from(Toml::string(&raw))
            .extract()
            .with_context(|| format!("Failed to parse {}", path.display()))?;

        let mut out = Vec::with_capacity(parsed.items.len());
        for item in parsed.items {
            let title = item.title.trim().to_owned();
            if title.is_empty() {
                continue;
            }
            let item_id = item
                .id
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .unwrap_or(&title)
                .to_owned();
            let creators: Vec<String> = item
                .creators
                .into_iter()
                .map(|c| c.trim().to_owned())
                .filter(|c| !c.is_empty())
                .collect();

            let mut ext_ids = BTreeMap::new();
            for (k, v) in item.external_ids {
                let val_str = json_scalar_to_string(&v);
                if !k.trim().is_empty() && !val_str.is_empty() {
                    ext_ids.insert(k.trim().to_owned(), val_str);
                }
            }

            let mut metadata = item.metadata;
            let thumbnail_url = metadata
                .remove("image_url")
                .and_then(|v| v.as_str().map(str::trim).map(str::to_owned))
                .filter(|s| !s.is_empty());
            let url = ext_ids.get("url").map(|s| s.trim().to_owned()).filter(|s| !s.is_empty());

            out.push(RawGreatItem {
                id: item_id,
                kind,
                title,
                year: item.year,
                creators,
                parent_id: item.parent_id.map(|p| p.trim().to_owned()).filter(|p| !p.is_empty()),
                external_ids: ext_ids,
                metadata,
                thumbnail_url,
                url,
                is_user_source,
            });
        }
        Ok(out)
    }

    async fn import_user_items(&mut self) -> Result<Vec<String>> {
        let mut user_entry_ids = Vec::new();
        for kind in ORDERED_USER_ITEM_KINDS {
            let Some(plural) = kind_plural(kind) else {
                continue;
            };
            let path = self.great_root.join("items").join(format!("{plural}.toml"));
            let items = Self::read_toml_items(&path, kind, true)?;
            for item in items {
                let entry_id = self.upsert_great_item(item).await?;
                user_entry_ids.push(entry_id);
            }
        }
        Ok(user_entry_ids)
    }

    #[allow(clippy::too_many_lines)]
    async fn import_albumsgenerator_source(&mut self) -> Result<()> {
        let path = self.great_root.join("sources").join("albumsgenerator.json");
        if !path.is_file() {
            return Ok(());
        }
        let raw = std::fs::read_to_string(&path)
            .with_context(|| format!("Failed to read {}", path.display()))?;
        let data: AlbumsGeneratorFile = serde_json::from_str(&raw)
            .with_context(|| format!("Invalid JSON in {}", path.display()))?;

        let existing_gen: Option<(bool, Option<String>, Option<DateTime<Utc>>)> = sqlx::query_as(
            "SELECT enabled, current_entry_id, drawn_at
             FROM collection_generators
             WHERE collection_id = ?",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_optional(&mut *self.tx)
        .await?;

        let (existing_enabled, existing_cur_id, existing_drawn_at) =
            existing_gen.unwrap_or((false, None, None));
        let roche_active_valid = if let Some(ref cur_id) = existing_cur_id {
            !self.is_consumed_or_abandoned(cur_id).await?
        } else {
            false
        };

        let mut unrevealed_candidate: Option<(String, DateTime<Utc>)> = None;

        for hist in &data.history {
            let Some(ref album_obj) = hist.album else {
                continue;
            };
            let Some(raw_item) = Self::raw_item_from_1001ag_album(album_obj) else {
                continue;
            };
            let entry_id = self.upsert_great_item(raw_item).await?;
            let gen_dt =
                hist.generated_at.as_deref().map(parse_iso_utc).transpose()?.unwrap_or(self.now);

            if hist.revealed_album {
                let notes = format_1001ag_notes(hist.rating, hist.review.as_deref());
                self.insert_consumption_log(
                    &entry_id,
                    gen_dt,
                    ConsumptionStatus::Consumed,
                    notes.as_deref(),
                )
                .await?;
            } else {
                unrevealed_candidate = Some((entry_id, gen_dt));
            }
        }

        if let Some(ref current_album_obj) = data.current_album
            && let Some(raw_cur) = Self::raw_item_from_1001ag_album(current_album_obj)
        {
            let cur_entry_id = self.upsert_great_item(raw_cur).await?;
            if !self.is_consumed_or_abandoned(&cur_entry_id).await? {
                let fallback_dt = if existing_cur_id.as_deref() == Some(cur_entry_id.as_str()) {
                    existing_drawn_at.unwrap_or(self.now)
                } else {
                    self.now
                };
                let drawn_dt = unrevealed_candidate.as_ref().map_or(fallback_dt, |(_, dt)| *dt);
                unrevealed_candidate = Some((cur_entry_id, drawn_dt));
            }
        }

        // Preserve Roche's native active draw if Great's `currentAlbum` was already finished
        // or abandoned inside Roche and Roche has already drawn a fresh album!
        if roche_active_valid {
            let cand_finished = match unrevealed_candidate {
                Some((ref cid, _)) => self.is_consumed_or_abandoned(cid).await?,
                None => true,
            };
            if cand_finished {
                self.stats.active_1001_album_id = existing_cur_id;
                let _ = (existing_enabled, existing_drawn_at);
                return Ok(());
            }
        }

        let (mut active_id, mut drawn_at) = if let Some((cand_id, cand_dt)) = unrevealed_candidate
            && !self.is_consumed_or_abandoned(&cand_id).await?
        {
            (Some(cand_id), Some(cand_dt))
        } else {
            (None, None)
        };

        let enabled = !data.paused;
        if enabled && active_id.is_none() {
            let rem_row: Option<String> = sqlx::query_scalar(
                "SELECT ce.entry_id
                 FROM collection_entries ce
                 JOIN entries e ON e.id = ce.entry_id
                 WHERE ce.collection_id = ?
                   AND e.merged_into_id IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM consumption_log cl
                       WHERE cl.entry_id = ce.entry_id AND cl.status IN ('consumed', 'abandoned')
                   )
                 ORDER BY ce.position ASC, ce.entry_id ASC
                 LIMIT 1",
            )
            .bind(ALBUMS_1001_COLLECTION_ID)
            .fetch_optional(&mut *self.tx)
            .await?;
            if let Some(drawn_id) = rem_row {
                active_id = Some(drawn_id);
                drawn_at = Some(self.now);
            }
        }

        let prev_cur_id = existing_cur_id.clone();
        let final_cur_id = active_id.or(existing_cur_id);
        let final_drawn_at = drawn_at.or(existing_drawn_at);
        if enabled != existing_enabled
            || final_cur_id != prev_cur_id
            || final_drawn_at != existing_drawn_at
        {
            sqlx::query(
                "UPDATE collection_generators
                 SET enabled = ?,
                     current_entry_id = ?,
                     drawn_at = ?,
                     updated_at = ?
                 WHERE collection_id = ?",
            )
            .bind(enabled)
            .bind(&final_cur_id)
            .bind(final_drawn_at)
            .bind(self.now)
            .bind(ALBUMS_1001_COLLECTION_ID)
            .execute(&mut *self.tx)
            .await?;
        }

        self.stats.active_1001_album_id = final_cur_id;
        Ok(())
    }

    fn raw_item_from_1001ag_album(album: &AgAlbumObj) -> Option<RawGreatItem> {
        let name = album.name.as_deref()?.trim();
        if name.is_empty() {
            return None;
        }
        let spotify_id = album.spotify_id.as_deref().map_or_default(str::trim);
        let ag_uuid = album.uuid.as_deref().map_or_default(str::trim);
        let ag_slug = album.slug.as_deref().map_or_default(str::trim);
        let great_id = if !spotify_id.is_empty() {
            format!("spotify:album:{spotify_id}")
        } else if !ag_uuid.is_empty() {
            format!("1001albums:{ag_uuid}")
        } else {
            name.to_owned()
        };

        let mut ext_ids = BTreeMap::new();
        if !ag_slug.is_empty() {
            ext_ids.insert("1001albums".to_owned(), ag_slug.to_owned());
        }
        if !ag_uuid.is_empty() {
            ext_ids.insert("1001albums_uuid".to_owned(), ag_uuid.to_owned());
        }
        if !spotify_id.is_empty() {
            ext_ids.insert("spotify".to_owned(), format!("spotify:album:{spotify_id}"));
        }
        for (scheme, opt_val) in [
            ("apple_music", album.apple_music_id.as_deref()),
            ("tidal", album.tidal_id.as_deref()),
            ("youtube_music", album.youtube_music_id.as_deref()),
            ("qobuz", album.qobuz_id.as_deref()),
            ("deezer", album.deezer_id.as_deref()),
        ] {
            if let Some(v) = opt_val.map(str::trim).filter(|s| !s.is_empty()) {
                ext_ids.insert(scheme.to_owned(), v.to_owned());
            }
        }
        if let Some(wiki_url) =
            album.wikipedia_url.as_deref().map(str::trim).filter(|s| !s.is_empty())
        {
            let slug = normalize_identifier_value(EntryKind::Album, "wikipedia", wiki_url);
            if !slug.is_empty() {
                ext_ids.insert("wikipedia".to_owned(), slug);
            }
        }

        let creators = album
            .artist
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map_or_default(|a| vec![a.to_owned()]);

        Some(RawGreatItem {
            id: great_id,
            kind: EntryKind::Album,
            title: name.to_owned(),
            year: parse_release_year(album.release_date.as_deref()),
            creators,
            parent_id: None,
            external_ids: ext_ids,
            metadata: serde_json::Map::new(),
            thumbnail_url: select_1001ag_cover_url(album),
            url: None,
            is_user_source: false,
        })
    }

    #[allow(clippy::too_many_lines)]
    async fn import_antennapod_source(&mut self) -> Result<()> {
        let path = self.great_root.join("sources").join("antennapod.json");
        if !path.is_file() {
            return Ok(());
        }
        let raw = std::fs::read_to_string(&path)
            .with_context(|| format!("Failed to read {}", path.display()))?;
        let data: AntennaPodFile = serde_json::from_str(&raw)
            .with_context(|| format!("Invalid JSON in {}", path.display()))?;

        let mut feeds_by_url: HashMap<String, &AntennaPodFeed> = HashMap::new();
        let mut podcast_entry_by_url: HashMap<String, String> = HashMap::new();

        for feed in &data.feeds {
            let Some(feed_url) = feed.feed_url.as_deref().map(str::trim).filter(|s| !s.is_empty())
            else {
                continue;
            };
            feeds_by_url.insert(feed_url.to_owned(), feed);
            let mut ext_ids = BTreeMap::new();
            ext_ids.insert("feed_url".to_owned(), feed_url.to_owned());
            if let Some(ref fid_val) = feed.feed_identifier {
                let fid_str = json_scalar_to_string(fid_val);
                if !fid_str.is_empty() {
                    ext_ids.insert("feed_identifier".to_owned(), fid_str);
                }
            }
            let creators = feed
                .author
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map_or_default(|a| vec![a.to_owned()]);
            let title = feed
                .title
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .unwrap_or(feed_url)
                .to_owned();
            let pod_item = RawGreatItem {
                id: feed_url.to_owned(),
                kind: EntryKind::Podcast,
                title,
                year: None,
                creators,
                parent_id: None,
                external_ids: ext_ids,
                metadata: serde_json::Map::new(),
                thumbnail_url: feed
                    .image_url
                    .as_deref()
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned),
                url: None,
                is_user_source: false,
            };
            let pod_entry_id = self.upsert_great_item(pod_item).await?;
            podcast_entry_by_url.insert(feed_url.to_owned(), pod_entry_id);
        }

        let mut engaged_podcasts: HashMap<String, DateTime<Utc>> = HashMap::new();
        for ep in &data.episodes {
            let Some(feed_url) = ep.feed_url.as_deref().map(str::trim).filter(|s| !s.is_empty())
            else {
                continue;
            };
            let Some(parent_feed) = feeds_by_url.get(feed_url) else {
                continue;
            };
            let guid = ep.item_identifier.as_deref().map_or_default(str::trim);
            if guid.is_empty() {
                continue;
            }
            let great_ep_id = format!("{feed_url}#{guid}");
            let mut meta = serde_json::Map::new();
            if let Some(ptitle) =
                parent_feed.title.as_deref().map(str::trim).filter(|s| !s.is_empty())
            {
                meta.insert(
                    "parent_title".to_owned(),
                    serde_json::Value::String(ptitle.to_owned()),
                );
            }
            if let Some(dur) = ep.duration_ms.filter(|d| *d > 0) {
                meta.insert("duration_ms".to_owned(), serde_json::Value::Number(dur.into()));
            }
            if ep.is_favorite.unwrap_or(false) {
                meta.insert("favorite".to_owned(), serde_json::Value::Bool(true));
            }

            // CRITICAL FIX: Never attach bare `feed_url` or bare `guid` onto `podcast_episode`!
            // `feed_url` belongs exclusively to the parent `podcast` entry; attaching it to
            // episodes previously caused episodes to overwrite the parent podcast entry!
            let canon_feed = canonicalize_sync_url(feed_url);
            let mut ext_ids = BTreeMap::new();
            ext_ids.insert("rss_guid".to_owned(), format!("{canon_feed}\0{guid}"));
            ext_ids.insert("antennapod_episode".to_owned(), great_ep_id.clone());

            let title = ep
                .title
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .unwrap_or(guid)
                .to_owned();
            let ep_item = RawGreatItem {
                id: great_ep_id,
                kind: EntryKind::PodcastEpisode,
                title,
                year: None,
                creators: Vec::new(),
                parent_id: Some(feed_url.to_owned()),
                external_ids: ext_ids,
                metadata: meta,
                thumbnail_url: ep
                    .image_url
                    .as_deref()
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned),
                url: None,
                is_user_source: false,
            };
            let ep_entry_id = self.upsert_great_item(ep_item).await?;

            if ep.read == Some(READ_PLAYED)
                && let Some(completed_ms) = ep.completed_at_ms.filter(|ms| *ms > 0)
                && let Some(ts) = Utc.timestamp_millis_opt(completed_ms).single()
            {
                let notes = ep.is_favorite.unwrap_or(false).then_some("★ favorite");
                self.insert_consumption_log(&ep_entry_id, ts, ConsumptionStatus::Consumed, notes)
                    .await?;
                if let Some(pod_id) = podcast_entry_by_url.get(feed_url) {
                    engaged_podcasts
                        .entry(pod_id.clone())
                        .and_modify(|prev| {
                            if ts < *prev {
                                *prev = ts;
                            }
                        })
                        .or_insert(ts);
                }
            }
        }

        for (pod_id, first_ts) in engaged_podcasts {
            self.ensure_entry_consumed_once(&pod_id, first_ts).await?;
        }
        Ok(())
    }

    #[allow(clippy::too_many_lines)]
    async fn import_spotify_source(&mut self) -> Result<()> {
        let path = self.great_root.join("sources").join("spotify.json");
        if !path.is_file() {
            return Ok(());
        }
        let raw = std::fs::read_to_string(&path)
            .with_context(|| format!("Failed to read {}", path.display()))?;
        let data: SpotifySourceFile = serde_json::from_str(&raw)
            .with_context(|| format!("Invalid JSON in {}", path.display()))?;

        for album in data.albums {
            let creators = album
                .artist
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map_or_default(|a| vec![a.to_owned()]);
            let alb_item = RawGreatItem {
                id: album.id.trim().to_owned(),
                kind: EntryKind::Album,
                title: album.title.trim().to_owned(),
                year: None,
                creators,
                parent_id: None,
                external_ids: BTreeMap::new(),
                metadata: serde_json::Map::new(),
                thumbnail_url: None,
                url: None,
                is_user_source: false,
            };
            self.upsert_great_item(alb_item).await?;
        }

        for track in data.tracks {
            let uri = track.uri.trim().to_owned();
            if uri.is_empty() {
                continue;
            }
            let creators = track
                .artist
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map_or_default(|a| vec![a.to_owned()]);
            let mut ext_ids = BTreeMap::new();
            ext_ids.insert("spotify".to_owned(), uri.clone());
            let title = track
                .title
                .as_deref()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .unwrap_or(&uri)
                .to_owned();
            let song_item = RawGreatItem {
                id: uri,
                kind: EntryKind::Song,
                title,
                year: None,
                creators,
                parent_id: track.album_id.map(|a| a.trim().to_owned()).filter(|a| !a.is_empty()),
                external_ids: ext_ids,
                metadata: serde_json::Map::new(),
                thumbnail_url: None,
                url: None,
                is_user_source: false,
            };
            self.upsert_great_item(song_item).await?;
        }

        // CRITICAL INVARIANT: Streaming completions log `consumed` ONLY on `song` entries!
        // Parent `album` entries are linked via `collection_entries` and NEVER marked `consumed`.
        for completion in data.completions {
            let uri = completion.track_uri.trim();
            if uri.is_empty() {
                continue;
            }
            let norm_spotify = normalize_identifier_value(EntryKind::Song, "spotify", uri);
            let song_entry_id = if let Some(cached) =
                self.id_map.get(&(EntryKind::Song, uri.to_owned()))
            {
                cached.clone()
            } else if let Some(found) =
                self.lookup_entry_by_identifier(EntryKind::Song, "spotify", &norm_spotify).await?
            {
                found
            } else {
                let mut ext_ids = BTreeMap::new();
                ext_ids.insert("spotify".to_owned(), uri.to_owned());
                self.upsert_great_item(RawGreatItem {
                    id: uri.to_owned(),
                    kind: EntryKind::Song,
                    title: uri.to_owned(),
                    year: None,
                    creators: Vec::new(),
                    parent_id: None,
                    external_ids: ext_ids,
                    metadata: serde_json::Map::new(),
                    thumbnail_url: None,
                    url: None,
                    is_user_source: false,
                })
                .await?
            };
            let ts = parse_iso_utc(&completion.ts)?;
            self.insert_consumption_log(&song_entry_id, ts, ConsumptionStatus::Consumed, None)
                .await?;
        }

        Ok(())
    }

    async fn import_user_logs(&mut self) -> Result<()> {
        let log_dir = self.great_root.join("log");
        if !log_dir.is_dir() {
            return Ok(());
        }
        for path in sorted_jsonl_files(&log_dir)? {
            let content = std::fs::read_to_string(&path)
                .with_context(|| format!("Failed to read {}", path.display()))?;
            for raw_line in content.lines() {
                let line = raw_line.trim();
                if line.is_empty() {
                    continue;
                }
                let rec: GreatLogRecord = serde_json::from_str(line)
                    .with_context(|| format!("Invalid JSONL record in {}", path.display()))?;
                let kind = EntryKind::parse_flexible(&rec.kind).with_context(|| {
                    format!("Unknown kind '{}' in {}", rec.kind, path.display())
                })?;
                let status = parse_flexible_consumption_status(&rec.status).with_context(|| {
                    format!("Unknown status '{}' in {}", rec.status, path.display())
                })?;
                let great_item_id = rec.item.trim();
                if great_item_id.is_empty() {
                    continue;
                }
                let notes = rec.notes.as_deref().map(str::trim).filter(|s| !s.is_empty());
                let ts = parse_iso_utc(&rec.ts)?;
                let entry_id = self.resolve_or_create_placeholder(kind, great_item_id).await?;
                self.insert_consumption_log(&entry_id, ts, status, notes).await?;
            }
        }
        Ok(())
    }

    async fn ensure_user_catalog_consumed_logs(&mut self, user_entry_ids: &[String]) -> Result<()> {
        // Use UNIX_EPOCH (`1970-01-01T00:00:00Z`) for undated legacy `items/*.toml` entries so they
        // never flood today's Diary and produce byte-identical `consumption_log.id` UUIDs across runs.
        for entry_id in user_entry_ids {
            self.ensure_entry_consumed_once(entry_id, DateTime::UNIX_EPOCH).await?;
        }
        Ok(())
    }

    async fn engage_music_artists_from_consumed_works(&mut self) -> Result<()> {
        // Pass 1: Synthesize & engage `artist` entries ONLY for creators of consumed songs/albums
        // (avoiding ~750 unengaged artist entries for unheard 1001-pool albums!).
        let consumed_works: Vec<(String, String, DateTime<Utc>)> = sqlx::query_as(
            "SELECT e.id, e.creators_json, MIN(cl.ts) AS first_ts
             FROM entries e
             JOIN consumption_log cl ON cl.entry_id = e.id AND cl.status = 'consumed'
             WHERE e.kind IN ('album', 'song') AND e.merged_into_id IS NULL
             GROUP BY e.id",
        )
        .fetch_all(&mut *self.tx)
        .await?;

        for (_work_id, creators_json, first_ts) in consumed_works {
            let creators: Vec<String> = serde_json::from_str(&creators_json).unwrap_or_default();
            for raw_name in creators {
                let name = raw_name.trim();
                if name.is_empty() {
                    continue;
                }
                let artist_id = self.ensure_artist_entry(name).await?;
                self.ensure_entry_consumed_once(&artist_id, first_ts).await?;
            }
        }

        Ok(())
    }

    async fn link_music_works_to_existing_artists(&mut self) -> Result<()> {
        // Link engaged/explicit songs & albums into `collection_entries` ONLY for artists that
        // exist in `entries`, while leaving untouched static `1001_albums` pool albums unlinked
        // so unconsumed 1001-pool albums neither pollute artist discographies nor defeat the
        // initial peer-sync static-pool filter.
        let engaged_music_works: Vec<(String, String)> = sqlx::query_as(
            "SELECT e.id, e.creators_json
             FROM entries e
             WHERE e.kind IN ('album', 'song')
               AND e.merged_into_id IS NULL
               AND (
                   e.kind = 'song'
                   OR EXISTS (SELECT 1 FROM consumption_log cl WHERE cl.entry_id = e.id)
                   OR EXISTS (
                       SELECT 1 FROM collection_entries ce_child
                       WHERE ce_child.collection_id = e.id
                   )
                   OR EXISTS (
                       SELECT 1 FROM collection_entries ce_list
                       JOIN collection_specs cs_list ON cs_list.collection_id = ce_list.collection_id
                       WHERE ce_list.entry_id = e.id AND cs_list.collection_role IN ('want', 'list')
                   )
                   OR NOT EXISTS (
                       SELECT 1 FROM collection_entries ce_pool
                       WHERE ce_pool.collection_id = ? AND ce_pool.entry_id = e.id
                   )
               )",
        )
        .bind(ALBUMS_1001_COLLECTION_ID)
        .fetch_all(&mut *self.tx)
        .await?;

        for (work_id, creators_json) in engaged_music_works {
            let creators: Vec<String> = serde_json::from_str(&creators_json).unwrap_or_default();
            for raw_name in creators {
                let name = raw_name.trim();
                if name.is_empty() {
                    continue;
                }
                if let Some(artist_id) = self.lookup_existing_artist_by_name(name).await?
                    && artist_id != work_id
                {
                    sqlx::query(
                        "INSERT OR IGNORE INTO collection_entries
                            (collection_id, entry_id, position, added_at)
                         VALUES (?, ?, 0, ?)",
                    )
                    .bind(&artist_id)
                    .bind(&work_id)
                    .bind(self.now)
                    .execute(&mut *self.tx)
                    .await?;
                }
            }
        }

        Ok(())
    }

    async fn lookup_existing_artist_by_name(&mut self, name: &str) -> Result<Option<String>> {
        if let Some(cached) = self.id_map.get(&(EntryKind::Artist, name.to_owned())) {
            return Ok(Some(cached.clone()));
        }
        let trimmed = name.trim();
        let lower_name = trimmed.to_lowercase();
        let det_id = deterministic_title_entry_id("artist", &lower_name);
        let mut found: Option<String> = sqlx::query_scalar(
            "SELECT id FROM entries
             WHERE kind = 'artist' AND merged_into_id IS NULL
               AND (id = ? OR LOWER(title) = LOWER(?))
             LIMIT 1",
        )
        .bind(&det_id)
        .bind(trimmed)
        .fetch_optional(&mut *self.tx)
        .await?;
        if found.is_none() && !trimmed.is_ascii() {
            let rows: Vec<(String, String)> = sqlx::query_as(
                "SELECT id, title FROM entries
                 WHERE kind = 'artist' AND merged_into_id IS NULL
                 ORDER BY updated_at DESC",
            )
            .fetch_all(&mut *self.tx)
            .await?;
            for (id, title) in rows {
                if title.trim().to_lowercase() == lower_name {
                    found = Some(id);
                    break;
                }
            }
        }
        if let Some(ref artist_id) = found {
            self.register_id_mapping(EntryKind::Artist, name, artist_id);
        }
        Ok(found)
    }

    async fn ensure_artist_entry(&mut self, name: &str) -> Result<String> {
        if let Some(existing) = self.lookup_existing_artist_by_name(name).await? {
            return Ok(existing);
        }
        let artist_id = deterministic_title_entry_id("artist", &name.trim().to_lowercase());
        let res = sqlx::query(
            "INSERT OR IGNORE INTO entries
                (id, kind, title, creators_json, metadata_json, created_at, updated_at)
             VALUES (?, 'artist', ?, '[]', '{}', ?, ?)",
        )
        .bind(&artist_id)
        .bind(name)
        .bind(self.now)
        .bind(self.now)
        .execute(&mut *self.tx)
        .await?;
        if res.rows_affected() > 0 {
            self.stats.entries_upserted += 1;
        }
        self.register_id_mapping(EntryKind::Artist, name, &artist_id);
        Ok(artist_id)
    }

    async fn import_want_items(&mut self) -> Result<Vec<(String, String, f64)>> {
        let want_dir = self.great_root.join("want");
        if !want_dir.is_dir() {
            return Ok(Vec::new());
        }
        let mut pending = Vec::new();
        for (kind, plural, _) in KIND_PLURAL_PAIRS {
            let path = want_dir.join(format!("{plural}.toml"));
            let items = Self::read_toml_items(&path, kind, true)?;
            if items.is_empty() {
                continue;
            }
            let want_col_id = self.ensure_want_collection(kind, plural).await?;
            for (idx, item) in items.into_iter().enumerate() {
                let entry_id = self.upsert_great_item(item).await?;
                #[allow(clippy::cast_precision_loss)]
                let pos = (idx + 1) as f64;
                pending.push((want_col_id.clone(), entry_id, pos));
            }
        }
        Ok(pending)
    }

    async fn attach_want_memberships(&mut self, pending: &[(String, String, f64)]) -> Result<()> {
        for (want_col_id, entry_id, pos) in pending {
            if self.has_status(entry_id, ConsumptionStatus::Consumed).await? {
                continue;
            }
            let res = sqlx::query(
                "INSERT OR IGNORE INTO collection_entries
                    (collection_id, entry_id, position, added_at)
                 VALUES (?, ?, ?, ?)",
            )
            .bind(want_col_id)
            .bind(entry_id)
            .bind(*pos)
            .bind(self.now)
            .execute(&mut *self.tx)
            .await?;
            if res.rows_affected() > 0 {
                self.stats.want_entries_added += 1;
            }
        }
        Ok(())
    }

    async fn ensure_want_collection(&mut self, kind: EntryKind, plural: &str) -> Result<String> {
        let want_slug = format!("want:{plural}");
        let existing: Option<String> =
            sqlx::query_scalar("SELECT collection_id FROM collection_specs WHERE slug = ?")
                .bind(&want_slug)
                .fetch_optional(&mut *self.tx)
                .await?;
        if let Some(col_id) = existing {
            return Ok(col_id);
        }

        let col_id = deterministic_system_collection_id(&want_slug);
        let label = format!("Want: {}", kind_plural_display(kind).unwrap_or(plural));
        sqlx::query(
            "INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
             VALUES (?, 'collection', ?, ?, ?)",
        )
        .bind(&col_id)
        .bind(&label)
        .bind(self.now)
        .bind(self.now)
        .execute(&mut *self.tx)
        .await?;
        sqlx::query(
            "INSERT OR IGNORE INTO collection_specs
                (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
             VALUES (?, ?, 'manual', 'want', NULL, 0)",
        )
        .bind(&col_id)
        .bind(&want_slug)
        .execute(&mut *self.tx)
        .await?;
        self.attach_identifier(&col_id, EntryKind::Collection, "slug", &want_slug).await?;
        Ok(col_id)
    }

    async fn import_comparisons(
        &mut self,
        list_name_to_kind: &HashMap<String, EntryKind>,
    ) -> Result<()> {
        let comp_dir = self.great_root.join("comparisons");
        if !comp_dir.is_dir() {
            return Ok(());
        }

        for path in sorted_jsonl_files(&comp_dir)? {
            let list_name = path.file_stem().and_then(|s| s.to_str()).unwrap_or_default();
            let expected_kind = list_name_to_kind.get(list_name).copied();
            self.import_comparison_file(&path, expected_kind, None).await?;
        }

        let want_comp_dir = comp_dir.join("want");
        if want_comp_dir.is_dir() {
            for path in sorted_jsonl_files(&want_comp_dir)? {
                let plural = path.file_stem().and_then(|s| s.to_str()).unwrap_or_default();
                let Some(kind) = plural_to_kind(plural) else {
                    continue;
                };
                let want_col_id = self.ensure_want_collection(kind, plural).await?;
                self.import_comparison_file(&path, Some(kind), Some(&want_col_id)).await?;
            }
        }

        Ok(())
    }

    async fn import_comparison_file(
        &mut self,
        path: &Path,
        expected_kind: Option<EntryKind>,
        scope_collection_id: Option<&str>,
    ) -> Result<()> {
        let content = std::fs::read_to_string(path)
            .with_context(|| format!("Failed to read {}", path.display()))?;
        for raw_line in content.lines() {
            let line = raw_line.trim();
            if line.is_empty() {
                continue;
            }
            let rec: GreatComparisonRecord = serde_json::from_str(line)
                .with_context(|| format!("Invalid comparison JSONL in {}", path.display()))?;
            let ts = parse_iso_utc(&rec.ts)?;

            let mut mapped_ordering: Vec<Vec<String>> = Vec::new();
            let mut seen_ids: HashSet<String> = HashSet::new();
            for group in rec.ordering {
                let mut mapped_group = Vec::new();
                for raw_item_id in group {
                    let entry_id =
                        self.resolve_comparison_item_id(expected_kind, &raw_item_id).await?;
                    if seen_ids.insert(entry_id.clone()) {
                        mapped_group.push(entry_id);
                    }
                }
                if !mapped_group.is_empty() {
                    mapped_ordering.push(mapped_group);
                }
            }

            if seen_ids.len() < 2 {
                continue;
            }

            let ts_ms = ts.timestamp_millis();
            let comp_id = deterministic_comparison_id(scope_collection_id, ts_ms, &mapped_ordering);
            let canon_ord = canonical_ordering_json(&mapped_ordering);
            let res = sqlx::query(
                "INSERT OR IGNORE INTO comparisons
                    (id, ts, scope_collection_id, ordering_json, created_at)
                 VALUES (?, ?, ?, ?, ?)",
            )
            .bind(&comp_id)
            .bind(ts)
            .bind(scope_collection_id)
            .bind(&canon_ord)
            .bind(self.now)
            .execute(&mut *self.tx)
            .await?;
            if res.rows_affected() > 0 {
                self.stats.comparisons_added += 1;
            }
        }
        Ok(())
    }

    async fn resolve_comparison_item_id(
        &mut self,
        expected_kind: Option<EntryKind>,
        raw_id: &str,
    ) -> Result<String> {
        let clean = raw_id.trim();
        if let Some(kind) = expected_kind {
            if let Some(direct) = self.id_map.get(&(kind, clean.to_owned())) {
                return Ok(direct.clone());
            }
            return self.resolve_or_create_placeholder(kind, clean).await;
        }
        if let Some(any_hit) = self.any_id_map.get(clean) {
            return Ok(any_hit.clone());
        }
        self.resolve_or_create_placeholder(EntryKind::Movie, clean).await
    }

    async fn upsert_great_item(&mut self, mut item: RawGreatItem) -> Result<String> {
        let item_kind = item.kind;
        let parent_raw_id = item.parent_id.take();
        let resolved_id = self.upsert_great_item_core(item).await?;

        if let Some(ref pid) = parent_raw_id
            && let Some(parent_kind) = migration_parent_kind(item_kind)
        {
            let parent_entry_id = if let Some(cached) = self.id_map.get(&(parent_kind, pid.clone()))
            {
                cached.clone()
            } else {
                self.resolve_or_create_placeholder(parent_kind, pid).await?
            };
            if parent_entry_id != resolved_id {
                sqlx::query(
                    "INSERT OR IGNORE INTO collection_entries
                        (collection_id, entry_id, position, added_at)
                     VALUES (?, ?, NULL, ?)",
                )
                .bind(&parent_entry_id)
                .bind(&resolved_id)
                .bind(self.now)
                .execute(&mut *self.tx)
                .await?;
            }
        }
        Ok(resolved_id)
    }

    #[allow(clippy::too_many_lines)]
    async fn upsert_great_item_core(&mut self, item: RawGreatItem) -> Result<String> {
        let mut norm_idents: Vec<EntryIdentifier> = Vec::new();
        if let Some((scheme, val)) = infer_identifier_from_great_id(item.kind, &item.id)
            && !scheme.is_empty()
            && !val.is_empty()
        {
            norm_idents.push(EntryIdentifier { scheme, value: val });
        }
        for (raw_scheme, raw_val) in &item.external_ids {
            let scheme = normalize_identifier_scheme(raw_scheme);
            let value = normalize_identifier_value(item.kind, &scheme, raw_val);
            if !scheme.is_empty()
                && !value.is_empty()
                && !norm_idents.iter().any(|i| i.scheme == scheme && i.value == value)
            {
                norm_idents.push(EntryIdentifier { scheme, value });
            }
        }

        let mut target_id: Option<String> = self.id_map.get(&(item.kind, item.id.clone())).cloned();

        // 1a. Match subscribed podcast feeds by canonical `feed_url`
        if item.kind == EntryKind::Podcast {
            for ident in &norm_idents {
                if ident.scheme == "feed_url" {
                    let feed_col: Option<String> = sqlx::query_scalar(
                        "SELECT collection_id FROM feed_sources WHERE feed_url = ? LIMIT 1",
                    )
                    .bind(&ident.value)
                    .fetch_optional(&mut *self.tx)
                    .await?;
                    if let Some(fid) = feed_col
                        && let Some(canon) =
                            self.follow_merged_id(&fid, Some(EntryKind::Podcast)).await?
                    {
                        target_id = Some(canon);
                        break;
                    }
                }
            }
        }

        // 1b. Match existing kind-compatible entry by normalized identifier
        if target_id.is_none() {
            for ident in &norm_idents {
                if let Some(found) =
                    self.lookup_entry_by_identifier(item.kind, &ident.scheme, &ident.value).await?
                {
                    target_id = Some(found);
                    break;
                }
            }
        }

        // 2. Collapse album edition variants (`(Expanded Edition)`, `[Remastered]`, etc.)
        let album_key = (item.kind == EntryKind::Album)
            .then(|| Self::album_match_key(&item.creators, &item.title))
            .flatten();
        if target_id.is_none()
            && let Some(ref key) = album_key
            && let Some(existing_alb) = self.album_edition_index.get(key)
        {
            target_id = Some(existing_alb.clone());
        }

        // 3. Match artist entries case-insensitively by title
        if target_id.is_none() && item.kind == EntryKind::Artist {
            target_id = self.lookup_existing_artist_by_name(&item.title).await?;
        }

        let clean_title = if item.kind == EntryKind::Album {
            strip_album_edition_suffix(&item.title)
        } else {
            item.title.trim().to_owned()
        };

        let mut existing_entry_updated = false;
        let was_existing = target_id.is_some();
        let resolved_id = if let Some(existing_id) = target_id {
            type ExistingFieldsRow =
                (String, String, Option<String>, Option<i32>, Option<String>, String);
            let row: Option<ExistingFieldsRow> = sqlx::query_as(
                "SELECT title, creators_json, url, release_year, thumbnail_url, metadata_json
                     FROM entries WHERE id = ?",
            )
            .bind(&existing_id)
            .fetch_optional(&mut *self.tx)
            .await?;

            if let Some((
                cur_title,
                cur_creators_json,
                cur_url,
                cur_year,
                cur_thumb,
                cur_meta_json,
            )) = row
            {
                let cur_creators: Vec<String> =
                    serde_json::from_str(&cur_creators_json).unwrap_or_default();
                let mut cur_meta: serde_json::Map<String, serde_json::Value> =
                    serde_json::from_str(&cur_meta_json).unwrap_or_default();
                for (k, v) in &item.metadata {
                    cur_meta.entry(k.clone()).or_insert_with(|| v.clone());
                }

                let new_title = if item.is_user_source && !clean_title.is_empty() {
                    clean_title.clone()
                } else {
                    cur_title.clone()
                };
                let new_creators = if (item.is_user_source && !item.creators.is_empty())
                    || cur_creators.is_empty()
                {
                    item.creators.clone()
                } else {
                    cur_creators
                };
                let new_year = if (item.is_user_source && item.year.is_some()) || cur_year.is_none()
                {
                    item.year
                } else {
                    cur_year
                };
                let new_url = cur_url.clone().or_else(|| item.url.clone());
                let new_thumb = cur_thumb.clone().or_else(|| item.thumbnail_url.clone());
                let new_creators_json = serde_json::to_string(&new_creators)?;
                let new_meta_json = serde_json::to_string(&cur_meta)?;

                let entry_changed = new_title != cur_title
                    || new_creators_json != cur_creators_json
                    || new_url != cur_url
                    || new_year != cur_year
                    || new_thumb != cur_thumb
                    || new_meta_json != cur_meta_json;

                if entry_changed {
                    sqlx::query(
                        "UPDATE entries
                         SET title = ?,
                             creators_json = ?,
                             url = ?,
                             release_year = ?,
                             thumbnail_url = ?,
                             metadata_json = ?,
                             updated_at = ?
                         WHERE id = ?",
                    )
                    .bind(&new_title)
                    .bind(&new_creators_json)
                    .bind(&new_url)
                    .bind(new_year)
                    .bind(&new_thumb)
                    .bind(&new_meta_json)
                    .bind(self.now)
                    .bind(&existing_id)
                    .execute(&mut *self.tx)
                    .await?;
                    existing_entry_updated = true;
                }
            }
            existing_id
        } else {
            let creators_for_key: &[String] =
                if item.kind == EntryKind::Artist { &[] } else { &item.creators };
            let det_id = choose_deterministic_entry_id(
                None,
                item.kind,
                &clean_title,
                creators_for_key,
                &norm_idents,
            );
            let resolved_det_id =
                self.follow_merged_id(&det_id, Some(item.kind)).await?.unwrap_or(det_id);
            let creators_json = serde_json::to_string(&item.creators)?;
            let metadata_json = if item.metadata.is_empty() {
                "{}".to_owned()
            } else {
                serde_json::to_string(&item.metadata)?
            };
            let res = sqlx::query(
                "INSERT OR IGNORE INTO entries
                    (id, kind, title, creators_json, url, release_year, thumbnail_url,
                     metadata_json, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&resolved_det_id)
            .bind(item.kind)
            .bind(&clean_title)
            .bind(&creators_json)
            .bind(&item.url)
            .bind(item.year)
            .bind(&item.thumbnail_url)
            .bind(&metadata_json)
            .bind(self.now)
            .bind(self.now)
            .execute(&mut *self.tx)
            .await?;
            if res.rows_affected() > 0 {
                self.stats.entries_upserted += 1;
            }
            resolved_det_id
        };

        let mut any_ident_attached = false;
        for ident in &norm_idents {
            if self.attach_identifier(&resolved_id, item.kind, &ident.scheme, &ident.value).await? {
                any_ident_attached = true;
            }
        }
        if was_existing && !existing_entry_updated && any_ident_attached {
            sqlx::query("UPDATE entries SET updated_at = ? WHERE id = ?")
                .bind(self.now)
                .bind(&resolved_id)
                .execute(&mut *self.tx)
                .await?;
        }

        if let Some(key) = album_key {
            self.album_edition_index.entry(key).or_insert_with(|| resolved_id.clone());
        }

        self.register_id_mapping(item.kind, &item.id, &resolved_id);
        Ok(resolved_id)
    }

    async fn resolve_or_create_placeholder(
        &mut self,
        kind: EntryKind,
        great_id: &str,
    ) -> Result<String> {
        if let Some(cached) = self.id_map.get(&(kind, great_id.to_owned())) {
            return Ok(cached.clone());
        }
        if let Some((scheme, val)) = infer_identifier_from_great_id(kind, great_id)
            && let Some(found) = self.lookup_entry_by_identifier(kind, &scheme, &val).await?
        {
            self.register_id_mapping(kind, great_id, &found);
            return Ok(found);
        }
        self.upsert_great_item_core(RawGreatItem {
            id: great_id.to_owned(),
            kind,
            title: great_id.to_owned(),
            year: None,
            creators: Vec::new(),
            parent_id: None,
            external_ids: BTreeMap::new(),
            metadata: serde_json::Map::new(),
            thumbnail_url: None,
            url: None,
            is_user_source: false,
        })
        .await
    }

    async fn lookup_entry_by_identifier(
        &mut self,
        expected_kind: EntryKind,
        scheme: &str,
        value: &str,
    ) -> Result<Option<String>> {
        let norm_scheme = normalize_identifier_scheme(scheme);
        let norm_val = normalize_identifier_value(expected_kind, &norm_scheme, value);
        if norm_scheme.is_empty() || norm_val.is_empty() {
            return Ok(None);
        }
        let owner_ids: Vec<String> = sqlx::query_scalar(
            "SELECT entry_id FROM entry_identifiers WHERE scheme = ? AND value = ?",
        )
        .bind(&norm_scheme)
        .bind(&norm_val)
        .fetch_all(&mut *self.tx)
        .await?;

        for owner_id in owner_ids {
            if let Some(canon_id) = self.follow_merged_id(&owner_id, Some(expected_kind)).await? {
                return Ok(Some(canon_id));
            }
        }
        Ok(None)
    }

    async fn follow_merged_id(
        &mut self,
        start_id: &str,
        expected_kind: Option<EntryKind>,
    ) -> Result<Option<String>> {
        let mut current = start_id.to_owned();
        for _ in 0..8 {
            let row: Option<(EntryKind, Option<String>)> =
                sqlx::query_as("SELECT kind, merged_into_id FROM entries WHERE id = ?")
                    .bind(&current)
                    .fetch_optional(&mut *self.tx)
                    .await?;
            let Some((kind, next_opt)) = row else {
                return Ok(None);
            };
            if let Some(next_id) = next_opt.filter(|s| !s.is_empty() && s != &current) {
                current = next_id;
            } else {
                if expected_kind.is_some_and(|exp| exp != kind) {
                    return Ok(None);
                }
                return Ok(Some(current));
            }
        }
        Ok(None)
    }

    async fn attach_identifier(
        &mut self,
        entry_id: &str,
        kind: EntryKind,
        scheme: &str,
        value: &str,
    ) -> Result<bool> {
        let norm_scheme = normalize_identifier_scheme(scheme);
        let norm_val = normalize_identifier_value(kind, &norm_scheme, value);
        if norm_scheme.is_empty() || norm_val.is_empty() {
            return Ok(false);
        }
        let res = sqlx::query(
            "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
             VALUES (?, ?, ?)",
        )
        .bind(&norm_scheme)
        .bind(&norm_val)
        .bind(entry_id)
        .execute(&mut *self.tx)
        .await?;
        Ok(res.rows_affected() > 0)
    }

    fn register_id_mapping(&mut self, kind: EntryKind, great_id: &str, roche_id: &str) {
        self.id_map.insert((kind, great_id.to_owned()), roche_id.to_owned());
        self.any_id_map.entry(great_id.to_owned()).or_insert_with(|| roche_id.to_owned());
    }

    async fn insert_consumption_log(
        &mut self,
        entry_id: &str,
        ts: DateTime<Utc>,
        status: ConsumptionStatus,
        notes: Option<&str>,
    ) -> Result<()> {
        let log_id =
            deterministic_consumption_log_id(entry_id, ts.timestamp_millis(), status.as_str());
        let res = sqlx::query(
            "INSERT OR IGNORE INTO consumption_log (id, entry_id, ts, status, notes, created_at)
             VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(&log_id)
        .bind(entry_id)
        .bind(ts)
        .bind(status)
        .bind(notes)
        .bind(self.now)
        .execute(&mut *self.tx)
        .await?;
        if res.rows_affected() > 0 {
            self.stats.consumption_logs_added += 1;
        }
        if status == ConsumptionStatus::Consumed {
            let promoted_want_cols: Vec<String> = sqlx::query_scalar(
                "SELECT collection_id FROM collection_entries
                 WHERE entry_id = ?
                   AND collection_id IN (
                       SELECT collection_id FROM collection_specs WHERE collection_role = 'want'
                   )",
            )
            .bind(entry_id)
            .fetch_all(&mut *self.tx)
            .await?;
            if !promoted_want_cols.is_empty() {
                sqlx::query(
                    "DELETE FROM collection_entries
                     WHERE entry_id = ?
                       AND collection_id IN (
                           SELECT collection_id FROM collection_specs WHERE collection_role = 'want'
                       )",
                )
                .bind(entry_id)
                .execute(&mut *self.tx)
                .await?;
                for want_col_id in promoted_want_cols {
                    let nat_key = collection_entry_natural_key(&want_col_id, entry_id);
                    SyncRepo::record_tombstone(
                        &mut *self.tx,
                        ActivityEntityType::CollectionEntry,
                        &nat_key,
                        self.now,
                    )
                    .await?;
                }
            }
        }
        Ok(())
    }

    async fn ensure_entry_consumed_once(
        &mut self,
        entry_id: &str,
        fallback_ts: DateTime<Utc>,
    ) -> Result<()> {
        if self.has_status(entry_id, ConsumptionStatus::Consumed).await? {
            return Ok(());
        }
        self.insert_consumption_log(entry_id, fallback_ts, ConsumptionStatus::Consumed, None).await
    }

    async fn has_status(&mut self, entry_id: &str, status: ConsumptionStatus) -> Result<bool> {
        let found: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM consumption_log WHERE entry_id = ? AND status = ? LIMIT 1",
        )
        .bind(entry_id)
        .bind(status)
        .fetch_optional(&mut *self.tx)
        .await?;
        Ok(found.is_some())
    }

    async fn is_consumed_or_abandoned(&mut self, entry_id: &str) -> Result<bool> {
        let found: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM consumption_log
             WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
             LIMIT 1",
        )
        .bind(entry_id)
        .fetch_optional(&mut *self.tx)
        .await?;
        Ok(found.is_some())
    }
}

fn kind_plural(kind: EntryKind) -> Option<&'static str> {
    KIND_PLURAL_PAIRS.iter().find_map(|(k, p, _)| (*k == kind).then_some(*p))
}

fn kind_plural_display(kind: EntryKind) -> Option<&'static str> {
    KIND_PLURAL_PAIRS.iter().find_map(|(k, _, label)| (*k == kind).then_some(*label))
}

fn plural_to_kind(plural: &str) -> Option<EntryKind> {
    KIND_PLURAL_PAIRS.iter().find_map(|(k, p, _)| (*p == plural).then_some(*k))
}

const fn migration_parent_kind(kind: EntryKind) -> Option<EntryKind> {
    match kind {
        EntryKind::Song => Some(EntryKind::Album),
        EntryKind::PodcastEpisode => Some(EntryKind::Podcast),
        _ => None,
    }
}

fn infer_identifier_from_great_id(kind: EntryKind, raw_id: &str) -> Option<(String, String)> {
    let trimmed = raw_id.trim();
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.starts_with("spotify:") {
        return Some(("spotify".to_owned(), normalize_identifier_value(kind, "spotify", trimmed)));
    }
    if let Some(rest) = trimmed.strip_prefix("1001albums:") {
        return Some(("1001albums_uuid".to_owned(), rest.to_owned()));
    }
    if trimmed.starts_with("tt")
        && trimmed.len() >= 4
        && trimmed[2..].bytes().all(|b| b.is_ascii_digit())
    {
        return Some(("imdb".to_owned(), trimmed.to_owned()));
    }
    if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        if kind == EntryKind::Podcast {
            return Some((
                "feed_url".to_owned(),
                normalize_identifier_value(kind, "feed_url", trimmed),
            ));
        }
        if kind == EntryKind::PodcastEpisode && trimmed.contains('#') {
            return Some(("antennapod_episode".to_owned(), trimmed.to_owned()));
        }
        return Some(("url".to_owned(), normalize_identifier_value(kind, "url", trimmed)));
    }
    None
}

fn parse_flexible_consumption_status(raw: &str) -> Result<ConsumptionStatus> {
    match raw.trim().to_ascii_lowercase().replace('-', "_").as_str() {
        "consumed" | "completed" | "complete" | "finished" | "watched" | "read" | "listened"
        | "played" | "done" => Ok(ConsumptionStatus::Consumed),
        "started" | "reading" | "watching" | "playing" | "listening" | "in_progress" => {
            Ok(ConsumptionStatus::Started)
        }
        "abandoned" | "dropped" | "dnf" | "quit" => Ok(ConsumptionStatus::Abandoned),
        other => anyhow::bail!("Unrecognized consumption status '{other}'"),
    }
}

fn parse_iso_utc(raw: &str) -> Result<DateTime<Utc>> {
    let trimmed = raw.trim();
    if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
        return Ok(dt.with_timezone(&Utc));
    }
    if let Ok(ndt) = NaiveDateTime::parse_from_str(trimmed, "%Y-%m-%dT%H:%M:%S%.f") {
        return Ok(Utc.from_utc_datetime(&ndt));
    }
    if let Ok(ndt) = NaiveDateTime::parse_from_str(trimmed, "%Y-%m-%d %H:%M:%S%.f") {
        return Ok(Utc.from_utc_datetime(&ndt));
    }
    if let Ok(nd) = NaiveDate::parse_from_str(trimmed, "%Y-%m-%d")
        && let Some(ndt) = nd.and_hms_opt(0, 0, 0)
    {
        return Ok(Utc.from_utc_datetime(&ndt));
    }
    anyhow::bail!("Invalid ISO-8601 timestamp '{trimmed}'")
}

fn parse_release_year(raw: Option<&str>) -> Option<i32> {
    let s = raw?.trim();
    s.get(..4)?.parse::<i32>().ok()
}

fn select_1001ag_cover_url(album: &AgAlbumObj) -> Option<String> {
    let best_from_images = album
        .images
        .iter()
        .filter_map(|img| {
            let url = img.url.as_deref()?.trim();
            if url.starts_with("http://") || url.starts_with("https://") {
                Some((img.width.unwrap_or(0), url.to_owned()))
            } else {
                None
            }
        })
        .max_by_key(|(w, _)| *w)
        .map(|(_, url)| url);
    if best_from_images.is_some() {
        return best_from_images;
    }
    album
        .image
        .as_deref()
        .map(str::trim)
        .filter(|u| u.starts_with("http://") || u.starts_with("https://"))
        .map(str::to_owned)
}

fn format_1001ag_notes(rating: Option<i64>, review: Option<&str>) -> Option<String> {
    let mut parts = Vec::new();
    if let Some(r) = rating.filter(|r| (1..=MAX_STAR_RATING).contains(r))
        && let (Ok(filled), Ok(empty)) = (usize::try_from(r), usize::try_from(MAX_STAR_RATING - r))
    {
        parts.push(format!("{}{}", "★".repeat(filled), "☆".repeat(empty)));
    }
    if let Some(rev) = review.map(str::trim).filter(|s| !s.is_empty()) {
        parts.push(rev.to_owned());
    }
    (!parts.is_empty()).then(|| parts.join(" — "))
}

fn json_scalar_to_string(val: &serde_json::Value) -> String {
    match val {
        serde_json::Value::String(s) => s.trim().to_owned(),
        serde_json::Value::Number(n) => n.to_string(),
        serde_json::Value::Bool(b) => b.to_string(),
        _ => String::new(),
    }
}

fn sorted_jsonl_files(dir: &Path) -> Result<Vec<PathBuf>> {
    let mut paths: Vec<PathBuf> = std::fs::read_dir(dir)
        .with_context(|| format!("Failed to read directory {}", dir.display()))?
        .filter_map(std::result::Result::ok)
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.extension().and_then(|s| s.to_str()) == Some("jsonl"))
        .collect();
    paths.sort();
    Ok(paths)
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::too_many_lines)]
mod tests {
    use super::*;
    use crate::db::Db;

    #[tokio::test]
    async fn test_migrate_great_fixes_antennapod_collision_artist_pollution_and_active_draw() {
        let tmp_dir =
            std::env::temp_dir().join(format!("roche_migrate_great_{}", uuid::Uuid::new_v4()));
        let db_path = tmp_dir.join("roche.db");
        let great_dir = tmp_dir.join("great_repo");
        for sub in ["items", "want", "log", "comparisons/want", "sources"] {
            tokio::fs::create_dir_all(great_dir.join(sub)).await.unwrap();
        }

        tokio::fs::write(
            great_dir.join("great.toml"),
            "[[lists]]\nname = \"movies\"\nkind = \"film\"\n",
        )
        .await
        .unwrap();

        tokio::fs::write(
            great_dir.join("items/movies.toml"),
            r#"
[[items]]
id = "tt0062622"
title = "2001: A Space Odyssey"
year = 1968
creators = ["Stanley Kubrick"]
external_ids = { imdb = "tt0062622" }
"#,
        )
        .await
        .unwrap();

        // AntennaPod: 1 podcast + 2 episodes -> MUST produce 1 `podcast` and 2 distinct `podcast_episode` rows!
        tokio::fs::write(
            great_dir.join("sources/antennapod.json"),
            serde_json::json!({
                "feeds": [{
                    "feed_url": "https://feeds.example.org/history.xml",
                    "title": "History Weekly",
                    "author": "Ada Historian"
                }],
                "episodes": [
                    {
                        "feed_url": "https://feeds.example.org/history.xml",
                        "item_identifier": "ep-1",
                        "title": "Episode One: Sumer",
                        "duration_ms": 1_800_000,
                        "read": 1,
                        "completed_at_ms": 1_772_800_000_000_i64,
                        "is_favorite": true
                    },
                    {
                        "feed_url": "https://feeds.example.org/history.xml",
                        "item_identifier": "ep-2",
                        "title": "Episode Two: Akkad",
                        "duration_ms": 2_100_000,
                        "read": 0
                    }
                ]
            })
            .to_string(),
        )
        .await
        .unwrap();

        // Spotify: 1 song streamed on Elvis Presley's 1956 album
        tokio::fs::write(
            great_dir.join("sources/spotify.json"),
            serde_json::json!({
                "albums": [{
                    "id": "spotify-name:Elvis Presley::Elvis Presley (Expanded Edition)",
                    "title": "Elvis Presley (Expanded Edition)",
                    "artist": "Elvis Presley"
                }],
                "tracks": [{
                    "uri": "spotify:track:4dc6v7A9B0x12345678901",
                    "title": "Blue Suede Shoes",
                    "artist": "Elvis Presley",
                    "album_id": "spotify-name:Elvis Presley::Elvis Presley (Expanded Edition)"
                }],
                "completions": [{
                    "track_uri": "spotify:track:4dc6v7A9B0x12345678901",
                    "ts": "2026-03-05T19:30:00Z"
                }]
            })
            .to_string(),
        )
        .await
        .unwrap();

        // 1001AG: Duke Ellington is `currentAlbum`
        tokio::fs::write(
            great_dir.join("sources/albumsgenerator.json"),
            serde_json::json!({
                "paused": false,
                "currentAlbum": {
                    "uuid": "ag-duke-uuid",
                    "slug": "ellington-at-newport",
                    "spotifyId": "0t41BkcZayaAsa0FdRelfz",
                    "name": "Ellington at Newport",
                    "artist": "Duke Ellington",
                    "releaseDate": "1956"
                },
                "history": []
            })
            .to_string(),
        )
        .await
        .unwrap();

        // Log JSONL with Great legacy `kind = "film"` and `status = "completed"` / `"dnf"`
        tokio::fs::write(
            great_dir.join("log/2026.jsonl"),
            concat!(
                r#"{"ts":"2026-02-10T21:00:00Z","kind":"film","item":"tt0133093","status":"completed","notes":"4K"}"#,
                "\n",
                r#"{"ts":"2026-02-12T21:00:00Z","kind":"book","item":"Infinite Jest","status":"dnf"}"#,
                "\n"
            ),
        )
        .await
        .unwrap();

        // 1. Dry-run leaves zero database files on disk
        let dry_stats = migrate_great_database(&db_path, &great_dir, None, true).await.unwrap();
        assert!(dry_stats.seeded_1001_albums >= 1_000);
        assert!(!db_path.exists(), "--dry-run must not leave a database file on disk");

        // 2. Real migration creates and populates `roche.db`
        let stats1 = migrate_great_database(&db_path, &great_dir, None, false).await.unwrap();
        assert_eq!(stats1.seeded_1001_albums, dry_stats.seeded_1001_albums);
        let duke_id = "c8edff07-31ae-5f23-9d42-03468251904a";
        assert_eq!(stats1.active_1001_album_id.as_deref(), Some(duke_id));

        let db = Db::connect(&db_path).await.unwrap();

        // Bug 1 verified: Podcast ("History Weekly") and both episodes exist as distinct entries!
        let podcasts = db
            .catalog()
            .search_entries(Some("History Weekly"), Some(EntryKind::Podcast), 5)
            .await
            .unwrap();
        assert_eq!(podcasts.len(), 1);
        assert_eq!(podcasts[0].title, "History Weekly");
        let pod_detail = db.catalog().get_entry_detail(&podcasts[0].id).await.unwrap().unwrap();
        assert_eq!(pod_detail.children.len(), 2);
        let ep_titles: Vec<&str> = pod_detail.children.iter().map(|e| e.title.as_str()).collect();
        assert!(ep_titles.contains(&"Episode One: Sumer"));
        assert!(ep_titles.contains(&"Episode Two: Akkad"));

        // Bug 2 verified: Only engaged artists (Elvis Presley) exist; unengaged 1001-pool artists were NOT synthesized!
        let artist_count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM entries WHERE kind = 'artist'")
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(
            artist_count, 1,
            "only Elvis Presley should be synthesized, not ~750 pool artists"
        );

        // Bug 4 & Bug 5 verified:
        // - `2001: A Space Odyssey` (undated `items/movies.toml`) has baseline timestamp `1970-01-01T00:00:00Z`
        // - `tt0133093` (`kind = "film"`, `status = "completed"`) normalized to `movie` + `consumed`
        // - `Infinite Jest` (`status = "dnf"`) normalized to `abandoned`
        let diary_1970 = db
            .catalog()
            .list_diary(Some(1970), Some(ConsumptionStatus::Consumed), 10)
            .await
            .unwrap();
        assert!(diary_1970.iter().any(|d| d.entry.title == "2001: A Space Odyssey"));
        let diary_2026 = db.catalog().list_diary(Some(2026), None, 10).await.unwrap();
        assert!(
            diary_2026.iter().any(
                |d| d.entry.kind == EntryKind::Movie && d.status == ConsumptionStatus::Consumed
            )
        );
        assert!(
            diary_2026
                .iter()
                .any(|d| d.entry.title == "Infinite Jest"
                    && d.status == ConsumptionStatus::Abandoned)
        );

        // Bug 3 verified: Finish Duke Ellington natively in Roche (which draws a new active album),
        // then re-run `migrate_great_database` — Roche's new active draw MUST NOT be overwritten!
        let (_, after_finish) =
            db.catalog().finish_active_1001_album(Some("Finished in Roche")).await.unwrap();
        let next_roche_draw = after_finish.current_entry.unwrap().id;
        assert_ne!(next_roche_draw, duke_id);
        db.pool().close().await;
        drop(db);

        let stats2 = migrate_great_database(&db_path, &great_dir, None, false).await.unwrap();
        assert_eq!(stats2.seeded_1001_albums, 0);
        assert_eq!(stats2.consumption_logs_added, 0);
        assert_eq!(stats2.active_1001_album_id.as_deref(), Some(next_roche_draw.as_str()));

        // Re-running migration without changes MUST preserve existing `entries.updated_at` LWW timestamps!
        let db2 = Db::connect(&db_path).await.unwrap();
        let before_rerun_ts: DateTime<Utc> = sqlx::query_scalar(
            "SELECT updated_at FROM entries WHERE title = '2001: A Space Odyssey'",
        )
        .fetch_one(db2.pool())
        .await
        .unwrap();
        db2.pool().close().await;
        drop(db2);

        tokio::time::sleep(Duration::from_millis(15)).await;
        migrate_great_database(&db_path, &great_dir, None, false).await.unwrap();

        let db3 = Db::connect(&db_path).await.unwrap();
        let after_rerun_ts: DateTime<Utc> = sqlx::query_scalar(
            "SELECT updated_at FROM entries WHERE title = '2001: A Space Odyssey'",
        )
        .fetch_one(db3.pool())
        .await
        .unwrap();
        assert_eq!(
            after_rerun_ts, before_rerun_ts,
            "No-op migrate-great re-run must not clobber entries.updated_at"
        );
        db3.pool().close().await;
        drop(db3);

        let _ = tokio::fs::remove_dir_all(&tmp_dir).await;
    }

    #[tokio::test]
    async fn test_migrate_great_preserves_logged_want_titles_and_utf8_release_year() {
        assert_eq!(parse_release_year(Some("19—")), None);
        assert_eq!(parse_release_year(Some("1979-05-25")), Some(1979));

        let tmp_dir = std::env::temp_dir().join(format!("roche_mig_want_{}", uuid::Uuid::new_v4()));
        let great_dir = tmp_dir.join("great");
        tokio::fs::create_dir_all(great_dir.join("want")).await.unwrap();
        tokio::fs::create_dir_all(great_dir.join("log")).await.unwrap();
        tokio::fs::write(great_dir.join("great.toml"), "lists = []\n").await.unwrap();
        tokio::fs::write(
            great_dir.join("want/movies.toml"),
            "[[items]]\nid = \"tt0078748\"\ntitle = \"Alien\"\ncreators = [\"Ridley Scott\"]\nyear = 1979\n",
        )
        .await
        .unwrap();
        tokio::fs::write(
            great_dir.join("want/books.toml"),
            "[[items]]\nid = \"ddia-kleppmann\"\ntitle = \"Designing Data-Intensive Applications\"\ncreators = [\"Martin Kleppmann\"]\n",
        )
        .await
        .unwrap();
        tokio::fs::write(
            great_dir.join("log/movies.jsonl"),
            "{\"ts\":\"2026-04-01T10:00:00Z\",\"item\":\"tt0078748\",\"kind\":\"movie\",\"status\":\"started\"}\n",
        )
        .await
        .unwrap();
        tokio::fs::write(
            great_dir.join("log/books.jsonl"),
            "{\"ts\":\"2026-04-02T10:00:00Z\",\"item\":\"ddia-kleppmann\",\"kind\":\"book\",\"status\":\"consumed\"}\n",
        )
        .await
        .unwrap();

        let db_path = tmp_dir.join("priv/roche.db");
        let stats = migrate_great_database(&db_path, &great_dir, None, false).await.unwrap();
        // `Alien` (`started`) stays in `want:movies`; `ddia-kleppmann` (`consumed`) is excluded from `want:books`!
        assert_eq!(stats.want_entries_added, 1);

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let dir_mode =
                tokio::fs::metadata(db_path.parent().unwrap()).await.unwrap().permissions().mode()
                    & 0o777;
            let file_mode =
                tokio::fs::metadata(&db_path).await.unwrap().permissions().mode() & 0o777;
            assert_eq!(dir_mode, 0o700);
            assert_eq!(file_mode, 0o600);
        }

        let db = Db::connect(&db_path).await.unwrap();
        let movies =
            db.catalog().search_entries(Some("Alien"), Some(EntryKind::Movie), 10).await.unwrap();
        assert_eq!(movies.len(), 1);
        assert_eq!(movies[0].title, "Alien");

        let books = db.catalog().search_entries(None, Some(EntryKind::Book), 10).await.unwrap();
        assert_eq!(
            books.len(),
            1,
            "Slug-keyed want + consumed log must not create duplicate book rows"
        );
        assert_eq!(books[0].title, "Designing Data-Intensive Applications");
        db.pool().close().await;
        drop(db);

        let _ = tokio::fs::remove_dir_all(&tmp_dir).await;
    }
}
