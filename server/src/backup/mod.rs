use std::{
    collections::{BTreeMap, HashSet},
    fs::File,
    io::{BufReader, BufWriter, Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, SystemTime},
};

use anyhow::{Context, Result};
use chrono::{DateTime, Datelike, NaiveDate, NaiveDateTime, Utc};
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions};

use crate::{
    config::BackupConfig,
    db::Db,
    util::{
        clock::now_utc,
        fs_perms::{ensure_private_dir, normalize_lexical, tighten_private_file_if_exists},
    },
};

const BACKUP_FILE_PREFIX: &str = "roche-";
const BACKUP_TMP_PREFIX: &str = ".tmp-roche-backup-";
const MIRROR_TMP_PREFIX: &str = ".tmp-mirror-";
const EXT_DB: &str = ".db";
const EXT_DB_ZST: &str = ".db.zst";
const ZSTD_MAGIC: [u8; 4] = [0x28, 0xB5, 0x2F, 0xFD];
const SQLITE_MAGIC_PREFIX: [u8; 16] = *b"SQLite format 3\0";
const SQLITE_SIDECAR_SUFFIXES: [&str; 3] = ["-wal", "-shm", "-journal"];
const IO_BUF_BYTES: usize = 64 * 1024;
const STALE_TMP_MAX_AGE: Duration = Duration::from_secs(3600);

/// Grandfather-Father-Son retention counts for managed backups.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RetentionPolicy {
    pub keep_daily: usize,
    pub keep_weekly: usize,
    pub keep_monthly: usize,
}

impl RetentionPolicy {
    #[must_use]
    pub const fn from_config(cfg: &BackupConfig) -> Self {
        Self {
            keep_daily: cfg.keep_daily,
            keep_weekly: cfg.keep_weekly,
            keep_monthly: cfg.keep_monthly,
        }
    }
}

/// Retention bucket(s) that caused a managed backup to be preserved.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum RetentionTier {
    Latest,
    Daily,
    Weekly,
    Monthly,
}

impl RetentionTier {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Latest => "latest",
            Self::Daily => "daily",
            Self::Weekly => "weekly",
            Self::Monthly => "monthly",
        }
    }
}

/// Metadata for a managed backup file discovered in a backup directory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupEntry {
    pub path: PathBuf,
    pub file_name: String,
    pub timestamp: DateTime<Utc>,
    pub size_bytes: u64,
    pub compressed: bool,
}

/// A managed backup paired with the retention tier(s) preserving it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RetainedBackup {
    pub entry: BackupEntry,
    pub tiers: Vec<RetentionTier>,
}

/// Planned retention split for a backup directory.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RetentionPlan {
    pub keep: Vec<RetainedBackup>,
    pub prune: Vec<BackupEntry>,
}

/// Summary of an optional incremental `media/` mirror run.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaMirrorStats {
    pub dest_dir: PathBuf,
    pub copied_files: usize,
    pub skipped_files: usize,
    pub removed_files: usize,
    pub total_bytes: u64,
}

/// Result of creating a database backup (and optional pruning / media mirror).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupOutcome {
    pub path: PathBuf,
    pub timestamp: DateTime<Utc>,
    pub uncompressed_bytes: u64,
    pub final_bytes: u64,
    pub compressed: bool,
    pub pruned: Vec<PathBuf>,
    pub media: Option<MediaMirrorStats>,
}

/// Result of verifying a backup archive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifyOutcome {
    pub path: PathBuf,
    pub compressed: bool,
    pub archive_bytes: u64,
    pub database_bytes: u64,
    pub entry_count: i64,
}

/// Result of restoring a backup archive into a target database path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RestoreOutcome {
    pub source_path: PathBuf,
    pub target_path: PathBuf,
    pub database_bytes: u64,
    pub entry_count: i64,
    pub removed_sidecars: Vec<PathBuf>,
}

/// Options for running a backup via the CLI or background worker.
#[derive(Debug, Clone)]
pub struct BackupOptions {
    pub backup_dir: PathBuf,
    pub explicit_output: Option<PathBuf>,
    pub compress: bool,
    pub compression_level: i32,
    pub prune: bool,
    pub retention: RetentionPolicy,
    pub include_media: bool,
    pub media_dir: PathBuf,
}

impl BackupOptions {
    #[must_use]
    pub fn from_config(cfg: &crate::config::Config) -> Self {
        Self {
            backup_dir: cfg.backup.dir.clone(),
            explicit_output: None,
            compress: cfg.backup.compress,
            compression_level: cfg.backup.compression_level,
            prune: true,
            retention: RetentionPolicy::from_config(&cfg.backup),
            include_media: cfg.backup.include_media,
            media_dir: cfg.storage.media_dir.clone(),
        }
    }
}

/// Validates that a managed `backup_dir` is non-empty and does not overlap `database_path` or `media_dir`.
///
/// Checks containment in both directions so neither `backup.dir == storage.data_dir` nor nesting
/// `backup.dir` inside `storage.media_dir` is allowed.
pub fn validate_backup_dir(
    backup_dir: &Path,
    database_path: &Path,
    media_dir: &Path,
) -> Result<()> {
    if backup_dir.as_os_str().is_empty() || backup_dir.to_str().is_some_and(|s| s.trim().is_empty())
    {
        anyhow::bail!("backup.dir must not be empty");
    }
    let norm_backup = normalize_lexical(backup_dir);
    let norm_media = normalize_lexical(media_dir);
    let norm_db = normalize_lexical(database_path);
    if norm_backup == norm_media
        || norm_backup.starts_with(&norm_media)
        || norm_media.starts_with(&norm_backup)
    {
        anyhow::bail!("backup.dir must not overlap storage.media_dir");
    }
    if norm_backup == norm_db
        || norm_backup.starts_with(&norm_db)
        || norm_db.starts_with(&norm_backup)
    {
        anyhow::bail!("backup.dir must not overlap storage.database_path");
    }
    Ok(())
}

/// Formats a managed backup filename for `timestamp` (UTC).
#[must_use]
pub fn format_backup_filename(timestamp: DateTime<Utc>, compressed: bool) -> String {
    let ext = if compressed { EXT_DB_ZST } else { EXT_DB };
    format!("{BACKUP_FILE_PREFIX}{}{ext}", timestamp.format("%Y-%m-%dT%H-%M-%SZ"))
}

/// Formats a managed backup filename with millisecond precision when a same-second filename exists.
#[must_use]
fn format_backup_filename_millis(timestamp: DateTime<Utc>, compressed: bool) -> String {
    let ext = if compressed { EXT_DB_ZST } else { EXT_DB };
    format!("{BACKUP_FILE_PREFIX}{}{ext}", timestamp.format("%Y-%m-%dT%H-%M-%S-%3fZ"))
}

/// Parses a managed Roche backup filename (`roche-YYYY-MM-DDTHH-MM-SSZ.db[.zst]` or millisecond variant).
#[must_use]
pub fn parse_backup_filename(file_name: &str) -> Option<(DateTime<Utc>, bool)> {
    let rest = file_name.strip_prefix(BACKUP_FILE_PREFIX)?;
    let (ts_part, compressed) = if let Some(s) = rest.strip_suffix(EXT_DB_ZST) {
        (s, true)
    } else {
        (rest.strip_suffix(EXT_DB)?, false)
    };

    let naive = NaiveDateTime::parse_from_str(ts_part, "%Y-%m-%dT%H-%M-%SZ")
        .or_else(|_| NaiveDateTime::parse_from_str(ts_part, "%Y-%m-%dT%H-%M-%S-%3fZ"))
        .ok()?;
    Some((DateTime::<Utc>::from_naive_utc_and_offset(naive, Utc), compressed))
}

/// Plans which managed backups to retain and prune under a non-overlapping calendar GFS policy:
/// - **Latest**: the newest backup is always preserved even if `keep_daily == 0`.
/// - **Daily (`keep_daily`)**: newest backup on each of the `keep_daily` most recent distinct UTC days.
/// - **Weekly (`keep_weekly`)**: newest backup from each of the `keep_weekly` most recent distinct
///   ISO weeks whose newest backup is not already preserved by a finer tier.
/// - **Monthly (`keep_monthly`)**: newest backup from each of the `keep_monthly` most recent
///   distinct calendar months whose newest backup is not already preserved by a finer tier and is
///   at least `keep_weekly * 7` days old relative to the newest backup.
///
/// Anchoring retention relative to the newest backup rather than wall-clock time ensures an offline
/// host never deletes its remaining history upon waking up, while multi-slot weekly/monthly counts
/// advance across ISO-week and calendar-month boundaries without history cliffs.
#[must_use]
pub fn plan_retention(entries: &[BackupEntry], policy: RetentionPolicy) -> RetentionPlan {
    if entries.is_empty() {
        return RetentionPlan { keep: Vec::new(), prune: Vec::new() };
    }

    let mut ordered_indices: Vec<usize> = (0..entries.len()).collect();
    ordered_indices.sort_by(|&a, &b| {
        entries[b]
            .timestamp
            .cmp(&entries[a].timestamp)
            .then_with(|| entries[b].file_name.cmp(&entries[a].file_name))
    });

    let newest_idx = ordered_indices[0];
    let anchor_date = entries[newest_idx].timestamp.date_naive();
    let mut tiers_by_idx: BTreeMap<usize, Vec<RetentionTier>> = BTreeMap::new();

    // 1. Daily tier (and unconditional protection of newest_idx when keep_daily == 0).
    if policy.keep_daily > 0 {
        let mut seen_days: HashSet<NaiveDate> = HashSet::with_capacity(policy.keep_daily);
        for &idx in &ordered_indices {
            let day = entries[idx].timestamp.date_naive();
            if seen_days.insert(day) {
                add_tier(&mut tiers_by_idx, idx, RetentionTier::Daily);
                if seen_days.len() >= policy.keep_daily {
                    break;
                }
            }
        }
    } else {
        add_tier(&mut tiers_by_idx, newest_idx, RetentionTier::Latest);
    }

    // 2. Weekly tier: newest backup from each distinct ISO week not already covered by Daily/Latest.
    if policy.keep_weekly > 0 {
        let mut seen_weeks: HashSet<(i32, u32)> = HashSet::new();
        let mut kept_weeks = 0usize;
        for &idx in &ordered_indices {
            let iso = entries[idx].timestamp.date_naive().iso_week();
            if !seen_weeks.insert((iso.year(), iso.week())) {
                continue;
            }
            if tiers_by_idx.contains_key(&idx) {
                continue;
            }
            add_tier(&mut tiers_by_idx, idx, RetentionTier::Weekly);
            kept_weeks += 1;
            if kept_weeks >= policy.keep_weekly {
                break;
            }
        }
    }

    // 3. Monthly tier: newest backup from each distinct calendar month not already covered by
    //    Daily/Weekly, spaced past the weekly span (`keep_weekly * 7` days) to avoid age inversion.
    if policy.keep_monthly > 0 {
        let min_monthly_age =
            i64::try_from(policy.keep_weekly).unwrap_or(i64::MAX / 7).saturating_mul(7);
        let mut seen_months: HashSet<(i32, u32)> = HashSet::new();
        let mut kept_months = 0usize;
        for &idx in &ordered_indices {
            let date = entries[idx].timestamp.date_naive();
            let month_key = (date.year(), date.month());
            if seen_months.contains(&month_key) {
                continue;
            }
            if tiers_by_idx.contains_key(&idx) {
                // Month is already represented by a finer tier (Daily/Weekly); skip this month.
                seen_months.insert(month_key);
                continue;
            }
            let age_days = anchor_date.signed_duration_since(date).num_days();
            if age_days < min_monthly_age {
                continue;
            }
            seen_months.insert(month_key);
            add_tier(&mut tiers_by_idx, idx, RetentionTier::Monthly);
            kept_months += 1;
            if kept_months >= policy.keep_monthly {
                break;
            }
        }
    }

    let mut keep = Vec::with_capacity(tiers_by_idx.len());
    let mut prune = Vec::new();
    for &idx in &ordered_indices {
        if let Some(tiers) = tiers_by_idx.remove(&idx) {
            keep.push(RetainedBackup { entry: entries[idx].clone(), tiers });
        } else {
            prune.push(entries[idx].clone());
        }
    }
    prune.reverse(); // oldest-first for deterministic removal order

    RetentionPlan { keep, prune }
}

fn add_tier(map: &mut BTreeMap<usize, Vec<RetentionTier>>, idx: usize, tier: RetentionTier) {
    let tiers = map.entry(idx).or_default();
    if !tiers.contains(&tier) {
        tiers.push(tier);
    }
}

/// Lists all valid managed Roche backup files in `backup_dir`, ordered newest-first.
pub async fn list_backups(backup_dir: &Path) -> Result<Vec<BackupEntry>> {
    match tokio::fs::symlink_metadata(backup_dir).await {
        Ok(meta) => {
            if meta.file_type().is_symlink() {
                anyhow::bail!(
                    "Refusing to follow symlinked backup directory: {}",
                    backup_dir.display()
                );
            }
            if !meta.is_dir() {
                anyhow::bail!("Backup directory path is not a directory: {}", backup_dir.display());
            }
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => {
            return Err(e).with_context(|| {
                format!("Failed to inspect backup directory {}", backup_dir.display())
            });
        }
    }

    let mut rd = tokio::fs::read_dir(backup_dir)
        .await
        .with_context(|| format!("Failed to read backup directory {}", backup_dir.display()))?;
    let mut entries = Vec::new();
    while let Some(dir_entry) = rd.next_entry().await? {
        let Ok(ft) = dir_entry.file_type().await else {
            continue;
        };
        if ft.is_symlink() || !ft.is_file() {
            continue;
        }
        let file_name = dir_entry.file_name().to_string_lossy().into_owned();
        let Some((timestamp, compressed)) = parse_backup_filename(&file_name) else {
            continue;
        };
        let size_bytes = dir_entry.metadata().await.map_or(0, |m| m.len());
        entries.push(BackupEntry {
            path: dir_entry.path(),
            file_name,
            timestamp,
            size_bytes,
            compressed,
        });
    }
    entries
        .sort_by(|a, b| b.timestamp.cmp(&a.timestamp).then_with(|| b.file_name.cmp(&a.file_name)));
    Ok(entries)
}

/// Returns `true` if no managed backup exists in `backup_dir` or the newest one is at least
/// `interval_hours` old relative to `now`.
pub async fn is_backup_due(
    backup_dir: &Path,
    interval_hours: u64,
    now: DateTime<Utc>,
) -> Result<bool> {
    let existing = list_backups(backup_dir).await?;
    let Some(newest) = existing.first() else {
        return Ok(true);
    };
    let elapsed_secs = now.signed_duration_since(newest.timestamp).num_seconds();
    let threshold_secs = i64::try_from(interval_hours.saturating_mul(3600)).unwrap_or(i64::MAX);
    Ok(elapsed_secs >= threshold_secs)
}

/// Runs GFS retention pruning on `backup_dir`, deleting pruned backup files unless `dry_run` is set.
pub async fn prune_backups(
    backup_dir: &Path,
    policy: RetentionPolicy,
    dry_run: bool,
) -> Result<RetentionPlan> {
    let entries = list_backups(backup_dir).await?;
    let plan = plan_retention(&entries, policy);
    if !dry_run {
        if path_exists_no_symlink(backup_dir).await? {
            let _ = cleanup_stale_temp_entries(backup_dir, STALE_TMP_MAX_AGE).await;
        }
        for item in &plan.prune {
            reject_if_symlink(&item.path).await?;
            match tokio::fs::remove_file(&item.path).await {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => {
                    return Err(e).with_context(|| {
                        format!("Failed to remove pruned backup {}", item.path.display())
                    });
                }
            }
        }
        if !plan.prune.is_empty() {
            sync_dir_async(backup_dir.to_path_buf()).await;
        }
    }
    Ok(plan)
}

/// Creates a live, verified backup directly from an active [`Db`] handle (used by `WorkerManager`).
pub async fn create_backup_from_db(db: &Db, opts: &BackupOptions) -> Result<BackupOutcome> {
    let never_cancel = Arc::new(AtomicBool::new(false));
    create_backup_from_db_with_cancel(db, opts, never_cancel).await
}

/// Creates a live, verified backup from an active [`Db`] handle with cooperative cancellation.
pub async fn create_backup_from_db_with_cancel(
    db: &Db,
    opts: &BackupOptions,
    cancel: Arc<AtomicBool>,
) -> Result<BackupOutcome> {
    create_backup_with_snapshotter(opts, None, cancel, |staging_db| async move {
        db.vacuum_into(&staging_db).await
    })
    .await
}

/// Creates a live, verified backup by connecting directly to an existing SQLite file at
/// `database_path` without creating an empty DB or running startup migrations (used by CLI).
pub async fn create_backup_from_path(
    database_path: &Path,
    opts: &BackupOptions,
) -> Result<BackupOutcome> {
    if !path_exists_no_symlink(database_path).await? {
        anyhow::bail!("Database file not found: {}", database_path.display());
    }
    if opts.explicit_output.is_none() {
        validate_backup_dir(&opts.backup_dir, database_path, &opts.media_dir)?;
    }
    let connect_opts = SqliteConnectOptions::new()
        .filename(database_path)
        .create_if_missing(false)
        .busy_timeout(Duration::from_secs(30));
    let pool = SqlitePoolOptions::new().max_connections(1).connect_with(connect_opts).await?;
    let never_cancel = Arc::new(AtomicBool::new(false));
    let res =
        create_backup_with_snapshotter(opts, Some(database_path), never_cancel, |staging_db| {
            let pool_ref = &pool;
            async move { crate::db::vacuum_into_pool(pool_ref, &staging_db).await }
        })
        .await;
    pool.close().await;
    res
}

/// Runs an automatic periodic backup if enabled and at least `cfg.interval_hours` have passed since
/// the newest backup in `cfg.dir`. Returns `Ok(Some(outcome))` when a backup was created.
#[cfg(test)]
pub async fn run_periodic_backup_if_due(
    db: &Db,
    cfg: &BackupConfig,
    media_dir: &Path,
) -> Result<Option<BackupOutcome>> {
    run_periodic_backup_if_due_with_cancel(db, cfg, media_dir, Arc::new(AtomicBool::new(false)))
        .await
}

/// Runs an automatic periodic backup if enabled and due, honoring `cancel` during compression and
/// optional media mirroring so server shutdown stays responsive.
pub async fn run_periodic_backup_if_due_with_cancel(
    db: &Db,
    cfg: &BackupConfig,
    media_dir: &Path,
    cancel: Arc<AtomicBool>,
) -> Result<Option<BackupOutcome>> {
    if !cfg.enabled || cancel.load(Ordering::Acquire) {
        return Ok(None);
    }
    let now = now_utc();
    if !is_backup_due(&cfg.dir, cfg.interval_hours, now).await? {
        return Ok(None);
    }
    let opts = BackupOptions {
        backup_dir: cfg.dir.clone(),
        explicit_output: None,
        compress: cfg.compress,
        compression_level: cfg.compression_level,
        prune: true,
        retention: RetentionPolicy::from_config(cfg),
        include_media: cfg.include_media,
        media_dir: media_dir.to_path_buf(),
    };
    let outcome = create_backup_from_db_with_cancel(db, &opts, cancel).await?;
    Ok(Some(outcome))
}

async fn create_backup_with_snapshotter<F, Fut>(
    opts: &BackupOptions,
    source_database_path: Option<&Path>,
    cancel: Arc<AtomicBool>,
    snapshotter: F,
) -> Result<BackupOutcome>
where
    F: FnOnce(PathBuf) -> Fut,
    Fut: std::future::Future<Output = Result<()>>,
{
    if opts.explicit_output.is_some() && opts.include_media {
        anyhow::bail!(
            "`--include-media` cannot be combined with `--output`; use `--dir <DIR>` so database snapshots and `media/` stay together"
        );
    }

    let timestamp = now_utc();
    let (target_dir, target_path, is_managed) = resolve_output_target(opts, timestamp).await?;
    if let Some(src_db) = source_database_path {
        validate_output_against_database(&target_path, src_db).await?;
    }

    // Check symlinks BEFORE touching or creating directories.
    reject_if_symlink(&target_dir).await?;
    reject_if_symlink(&target_path).await?;
    if is_managed {
        ensure_private_dir(&target_dir).await?;
    } else if !path_exists_no_symlink(&target_dir).await? {
        tokio::fs::create_dir_all(&target_dir).await?;
    }
    let _ = cleanup_stale_temp_entries(&target_dir, STALE_TMP_MAX_AGE).await;

    // Always stage inside a dedicated `0700` temp subdirectory so `VACUUM INTO` never exposes a
    // world-readable `0644` database file even when `-o` points into `/tmp` or a shared mount.
    let run_id = uuid::Uuid::new_v4();
    let staging_dir = target_dir.join(format!("{BACKUP_TMP_PREFIX}{run_id}"));
    create_exclusive_private_dir(&staging_dir).await?;
    let staging_db = staging_dir.join("snapshot.db");
    let staging_zst = staging_dir.join("snapshot.db.zst");

    let build_res: Result<(u64, u64)> = async {
        snapshotter(staging_db.clone()).await?;
        if cancel.load(Ordering::Acquire) {
            anyhow::bail!("Backup cancelled during shutdown");
        }
        tighten_private_file_if_exists(&staging_db).await;
        let uncompressed_bytes = tokio::fs::metadata(&staging_db).await?.len();
        verify_sqlite_snapshot(&staging_db, false).await?;

        if opts.compress {
            let final_bytes = compress_file_zstd(
                staging_db.clone(),
                staging_zst.clone(),
                opts.compression_level,
                Arc::clone(&cancel),
            )
            .await?;
            tokio::fs::rename(&staging_zst, &target_path).await?;
            tighten_private_file_if_exists(&target_path).await;
            sync_dir_async(target_dir.clone()).await;
            Ok((uncompressed_bytes, final_bytes))
        } else {
            sync_file_async(staging_db.clone()).await?;
            tokio::fs::rename(&staging_db, &target_path).await?;
            tighten_private_file_if_exists(&target_path).await;
            sync_dir_async(target_dir.clone()).await;
            Ok((uncompressed_bytes, uncompressed_bytes))
        }
    }
    .await;

    let _ = tokio::fs::remove_dir_all(&staging_dir).await;
    let (uncompressed_bytes, final_bytes) = build_res?;

    let pruned = if is_managed && opts.prune {
        let plan = prune_backups(&opts.backup_dir, opts.retention, false).await?;
        plan.prune.into_iter().map(|e| e.path).collect()
    } else {
        Vec::new()
    };

    let media = if opts.include_media {
        let media_dest = opts.backup_dir.join("media");
        Some(mirror_media_dir_with_cancel(&opts.media_dir, &media_dest, cancel).await?)
    } else {
        None
    };

    Ok(BackupOutcome {
        path: target_path,
        timestamp,
        uncompressed_bytes,
        final_bytes,
        compressed: opts.compress,
        pruned,
        media,
    })
}

async fn resolve_output_target(
    opts: &BackupOptions,
    timestamp: DateTime<Utc>,
) -> Result<(PathBuf, PathBuf, bool)> {
    if let Some(ref explicit) = opts.explicit_output {
        if let Ok(meta) = tokio::fs::symlink_metadata(explicit).await {
            if meta.file_type().is_symlink() {
                anyhow::bail!(
                    "Refusing to overwrite symlinked output path: {}",
                    explicit.display()
                );
            }
            if meta.is_dir() {
                anyhow::bail!(
                    "Output path '{}' is a directory; pass a file path or use `--dir` instead",
                    explicit.display()
                );
            }
        }
        let parent = explicit
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .map_or_else(|| PathBuf::from("."), Path::to_path_buf);
        return Ok((parent, explicit.clone(), false));
    }

    let dir = opts.backup_dir.clone();
    let candidate = dir.join(format_backup_filename(timestamp, opts.compress));
    let final_path = if path_exists_no_symlink(&candidate).await? {
        dir.join(format_backup_filename_millis(timestamp, opts.compress))
    } else {
        candidate
    };
    Ok((dir, final_path, true))
}

async fn validate_output_against_database(target_path: &Path, database_path: &Path) -> Result<()> {
    let norm_target = normalize_lexical(target_path);
    let mut forbidden = vec![normalize_lexical(database_path)];
    for suffix in SQLITE_SIDECAR_SUFFIXES {
        forbidden.push(normalize_lexical(&append_path_suffix(database_path, suffix)));
    }
    if forbidden.iter().any(|p| p == &norm_target) {
        anyhow::bail!(
            "Refusing to write backup over live database or SQLite sidecar: {}",
            target_path.display()
        );
    }
    if let (Ok(canon_target), Ok(canon_db)) =
        (tokio::fs::canonicalize(target_path).await, tokio::fs::canonicalize(database_path).await)
        && canon_target == canon_db
    {
        anyhow::bail!("Refusing to write backup over live database: {}", target_path.display());
    }
    Ok(())
}

/// Thoroughly verifies a backup file (`.db.zst` or `.db`): checks Zstandard frame checksum (if
/// compressed), SQLite `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, and Roche schema.
pub async fn verify_backup_file(backup_path: &Path) -> Result<VerifyOutcome> {
    if !path_exists_no_symlink(backup_path).await? {
        anyhow::bail!("Backup file not found: {}", backup_path.display());
    }
    let archive_bytes = tokio::fs::metadata(backup_path).await?.len();
    let kind = detect_backup_format(backup_path).await?;

    match kind {
        BackupFormat::Zstd => {
            let staging_dir = std::env::temp_dir()
                .join(format!("{BACKUP_TMP_PREFIX}verify-{}", uuid::Uuid::new_v4()));
            create_exclusive_private_dir(&staging_dir).await?;
            let temp_db = staging_dir.join("verify.db");
            let res = async {
                let db_bytes =
                    decompress_file_zstd(backup_path.to_path_buf(), temp_db.clone()).await?;
                let entry_count = verify_sqlite_snapshot(&temp_db, true).await?;
                Ok(VerifyOutcome {
                    path: backup_path.to_path_buf(),
                    compressed: true,
                    archive_bytes,
                    database_bytes: db_bytes,
                    entry_count,
                })
            }
            .await;
            let _ = tokio::fs::remove_dir_all(&staging_dir).await;
            res
        }
        BackupFormat::Sqlite => {
            let entry_count = verify_sqlite_snapshot(backup_path, true).await?;
            Ok(VerifyOutcome {
                path: backup_path.to_path_buf(),
                compressed: false,
                archive_bytes,
                database_bytes: archive_bytes,
                entry_count,
            })
        }
    }
}

/// Verifies and atomically restores a `.db.zst` or `.db` backup into `target_db_path`.
///
/// Advances `sync_clock.restore_epoch` so peers automatically re-sync cleanly, refuses to overwrite
/// a live locked database, and stashes existing `-wal`/`-shm`/`-journal` sidecars with rollback
/// protection if the final rename fails. Always stop `roche serve` before restoring a live DB.
pub async fn restore_backup_file(
    backup_path: &Path,
    target_db_path: &Path,
    force: bool,
) -> Result<RestoreOutcome> {
    if !path_exists_no_symlink(backup_path).await? {
        anyhow::bail!("Backup file not found: {}", backup_path.display());
    }
    let (target_exists, parent, initial_sidecars) =
        inspect_restore_target_and_sidecars(backup_path, target_db_path, force).await?;

    let staging_dir = parent.join(format!("{BACKUP_TMP_PREFIX}restore-{}", uuid::Uuid::new_v4()));
    create_exclusive_private_dir(&staging_dir).await?;
    let staging_db = staging_dir.join("restored.db");
    let kind = detect_backup_format(backup_path).await?;

    let prep_res: Result<(u64, i64)> = async {
        match kind {
            BackupFormat::Zstd => {
                let _ = decompress_file_zstd(backup_path.to_path_buf(), staging_db.clone()).await?;
            }
            BackupFormat::Sqlite => {
                let _ =
                    copy_private_file_synced(backup_path.to_path_buf(), staging_db.clone()).await?;
            }
        }
        let entry_count = verify_sqlite_snapshot(&staging_db, true).await?;
        bump_restore_epoch_in_staged_db(&staging_db).await?;
        sync_file_async(staging_db.clone()).await?;
        let db_bytes = tokio::fs::metadata(&staging_db).await?.len();
        Ok((db_bytes, entry_count))
    }
    .await;

    let (database_bytes, entry_count) = match prep_res {
        Ok(res) => res,
        Err(err) => {
            let _ = tokio::fs::remove_dir_all(&staging_dir).await;
            return Err(err);
        }
    };

    // Guard against restoring over a database held open by `roche serve` (even an idle WAL pool)
    // AFTER backup verification passes so corrupt backups never touch the target database.
    if target_exists && let Err(err) = probe_target_db_not_locked(target_db_path).await {
        let _ = tokio::fs::remove_dir_all(&staging_dir).await;
        return Err(err);
    }

    // Re-scan remaining SQLite sidecars AFTER the lock probe closes (since `journal_mode=DELETE`
    // checkpoints & unlinks WAL/SHM on a closed real WAL DB), then stash any remaining sidecars
    // into `staging_dir` so they can be restored cleanly if the final rename fails.
    let remaining_sidecars = match collect_existing_sidecars(target_db_path).await {
        Ok(list) => list,
        Err(err) => {
            let _ = tokio::fs::remove_dir_all(&staging_dir).await;
            return Err(err);
        }
    };
    let mut stashed_sidecars: Vec<(PathBuf, PathBuf)> = Vec::new();
    for (suffix, orig_path) in remaining_sidecars {
        let stash_path = staging_dir.join(format!("old-sidecar{suffix}"));
        match tokio::fs::rename(&orig_path, &stash_path).await {
            Ok(()) => stashed_sidecars.push((orig_path, stash_path)),
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => {}
            Err(err) => {
                rollback_stashed_sidecars(&stashed_sidecars).await;
                let _ = tokio::fs::remove_dir_all(&staging_dir).await;
                return Err(err).with_context(|| {
                    format!("Failed to move stale SQLite sidecar {}", orig_path.display())
                });
            }
        }
    }

    if let Err(err) = tokio::fs::rename(&staging_db, target_db_path).await {
        rollback_stashed_sidecars(&stashed_sidecars).await;
        let _ = tokio::fs::remove_dir_all(&staging_dir).await;
        return Err(err).with_context(|| {
            format!("Failed to replace target database {}", target_db_path.display())
        });
    }

    let mut removed_sidecars: Vec<PathBuf> = Vec::new();
    for (_, path) in initial_sidecars {
        if !tokio::fs::try_exists(&path).await.unwrap_or(false) {
            removed_sidecars.push(path);
        }
    }
    for (orig, _) in stashed_sidecars {
        if !removed_sidecars.contains(&orig) {
            removed_sidecars.push(orig);
        }
    }
    let _ = tokio::fs::remove_dir_all(&staging_dir).await;
    tighten_private_file_if_exists(target_db_path).await;
    sync_dir_async(parent).await;

    Ok(RestoreOutcome {
        source_path: backup_path.to_path_buf(),
        target_path: target_db_path.to_path_buf(),
        database_bytes,
        entry_count,
        removed_sidecars,
    })
}

async fn inspect_restore_target_and_sidecars(
    backup_path: &Path,
    target_db_path: &Path,
    force: bool,
) -> Result<(bool, PathBuf, Vec<(&'static str, PathBuf)>)> {
    let target_exists = match tokio::fs::symlink_metadata(target_db_path).await {
        Ok(meta) => {
            if meta.file_type().is_symlink() {
                anyhow::bail!(
                    "Refusing to overwrite symlinked target database: {}",
                    target_db_path.display()
                );
            }
            if meta.is_dir() {
                anyhow::bail!("Target database path is a directory: {}", target_db_path.display());
            }
            true
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => false,
        Err(e) => {
            return Err(e).with_context(|| {
                format!("Failed to inspect target database path {}", target_db_path.display())
            });
        }
    };

    if target_exists && !force {
        anyhow::bail!(
            "Target database already exists at {}. Stop `roche serve` and pass `--force` to overwrite it.",
            target_db_path.display()
        );
    }

    let canon_same = target_exists
        && matches!(
            (
                tokio::fs::canonicalize(backup_path).await,
                tokio::fs::canonicalize(target_db_path).await,
            ),
            (Ok(a), Ok(b)) if a == b
        );
    if normalize_lexical(backup_path) == normalize_lexical(target_db_path) || canon_same {
        anyhow::bail!(
            "Backup file '{}' and target database path refer to the same file",
            backup_path.display()
        );
    }

    let parent = target_db_path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .map_or_else(|| PathBuf::from("."), Path::to_path_buf);
    reject_if_symlink(&parent).await?;
    ensure_private_dir(&parent).await?;

    // Inspect all SQLite sidecars upfront (rejecting even dangling symlinks or directories).
    let existing_sidecars = collect_existing_sidecars(target_db_path).await?;
    Ok((target_exists, parent, existing_sidecars))
}

async fn collect_existing_sidecars(target_db_path: &Path) -> Result<Vec<(&'static str, PathBuf)>> {
    let mut existing_sidecars = Vec::new();
    for suffix in SQLITE_SIDECAR_SUFFIXES {
        let sidecar = append_path_suffix(target_db_path, suffix);
        match tokio::fs::symlink_metadata(&sidecar).await {
            Ok(meta) => {
                if meta.file_type().is_symlink() {
                    anyhow::bail!(
                        "Refusing to follow symlinked SQLite sidecar: {}",
                        sidecar.display()
                    );
                }
                if !meta.is_file() {
                    anyhow::bail!(
                        "SQLite sidecar path is not a regular file: {}",
                        sidecar.display()
                    );
                }
                existing_sidecars.push((suffix, sidecar));
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => {
                return Err(e).with_context(|| {
                    format!("Failed to inspect SQLite sidecar {}", sidecar.display())
                });
            }
        }
    }
    Ok(existing_sidecars)
}

async fn rollback_stashed_sidecars(stashed: &[(PathBuf, PathBuf)]) {
    for (orig_path, stash_path) in stashed.iter().rev() {
        let _ = tokio::fs::rename(stash_path, orig_path).await;
    }
}

async fn probe_target_db_not_locked(target_db_path: &Path) -> Result<()> {
    // Switching `journal_mode = DELETE` with `busy_timeout(0)` acquires an exclusive lock on
    // `WAL_DMS` (`sqlite3WalClose`), catching even an idle running `roche serve` WAL pool.
    let opts = SqliteConnectOptions::new()
        .filename(target_db_path)
        .create_if_missing(false)
        .journal_mode(SqliteJournalMode::Delete)
        .busy_timeout(Duration::ZERO);
    let pool = match SqlitePoolOptions::new().max_connections(1).connect_with(opts).await {
        Ok(pool) => pool,
        Err(e) if is_sqlite_busy_or_locked(&e) => {
            anyhow::bail!(
                "Target database {} is currently in use (locked); stop `roche serve` before restoring",
                target_db_path.display()
            );
        }
        Err(_) => {
            // Unopenable / corrupted DB file is fine to overwrite during restore.
            return Ok(());
        }
    };
    let probe_res: Result<()> = async {
        let mut conn = match pool.acquire().await {
            Ok(c) => c,
            Err(e) if is_sqlite_busy_or_locked(&e) => {
                anyhow::bail!(
                    "Target database {} is currently in use (locked); stop `roche serve` before restoring",
                    target_db_path.display()
                );
            }
            Err(_) => return Ok(()),
        };
        if let Err(e) = sqlx::query("PRAGMA locking_mode = EXCLUSIVE").execute(&mut *conn).await {
            if is_sqlite_busy_or_locked(&e) {
                anyhow::bail!(
                    "Target database {} is currently in use (locked); stop `roche serve` before restoring",
                    target_db_path.display()
                );
            }
            return Ok(());
        }
        match sqlx::query("BEGIN EXCLUSIVE").execute(&mut *conn).await {
            Ok(_) => {
                let _ = sqlx::query("ROLLBACK").execute(&mut *conn).await;
                Ok(())
            }
            Err(e) if is_sqlite_busy_or_locked(&e) => {
                anyhow::bail!(
                    "Target database {} is currently in use (locked); stop `roche serve` before restoring",
                    target_db_path.display()
                );
            }
            Err(_) => Ok(()),
        }
    }
    .await;
    pool.close().await;
    probe_res
}

fn is_sqlite_busy_or_locked(err: &sqlx::Error) -> bool {
    if let sqlx::Error::Database(db_err) = err
        && matches!(db_err.code().as_deref(), Some("5" | "6" | "261" | "517"))
    {
        return true;
    }
    let msg = err.to_string().to_ascii_lowercase();
    msg.contains("database is locked") || msg.contains("database is busy")
}

async fn bump_restore_epoch_in_staged_db(staging_db: &Path) -> Result<()> {
    let opts = SqliteConnectOptions::new()
        .filename(staging_db)
        .create_if_missing(false)
        .journal_mode(SqliteJournalMode::Delete)
        .busy_timeout(Duration::from_secs(10));
    let pool = SqlitePoolOptions::new().max_connections(1).connect_with(opts).await?;
    let res: Result<()> = async {
        let has_col: Option<i64> = sqlx::query_scalar(
            "SELECT 1 FROM pragma_table_info('sync_clock') WHERE name = 'restore_epoch' LIMIT 1",
        )
        .fetch_optional(&pool)
        .await?;
        if has_col.is_none() {
            sqlx::migrate!("./migrations").run(&pool).await?;
        }
        sqlx::query("UPDATE sync_clock SET restore_epoch = restore_epoch + 1 WHERE id = 1")
            .execute(&pool)
            .await?;
        Ok(())
    }
    .await;
    pool.close().await;
    res
}

/// Incrementally mirrors `source_media_dir` into `dest_media_dir`, copying only new/changed
/// regular files (skipping symlinks and `.tmp-*` staging artifacts) and pruning deleted files.
#[cfg(test)]
pub async fn mirror_media_dir(
    source_media_dir: &Path,
    dest_media_dir: &Path,
) -> Result<MediaMirrorStats> {
    mirror_media_dir_with_cancel(source_media_dir, dest_media_dir, Arc::new(AtomicBool::new(false)))
        .await
}

async fn mirror_media_dir_with_cancel(
    source_media_dir: &Path,
    dest_media_dir: &Path,
    cancel: Arc<AtomicBool>,
) -> Result<MediaMirrorStats> {
    if !path_exists_no_symlink(source_media_dir).await? {
        return Ok(MediaMirrorStats {
            dest_dir: dest_media_dir.to_path_buf(),
            copied_files: 0,
            skipped_files: 0,
            removed_files: 0,
            total_bytes: 0,
        });
    }
    let norm_src = normalize_lexical(source_media_dir);
    let norm_dst = normalize_lexical(dest_media_dir);
    if paths_overlap(&norm_src, &norm_dst) {
        anyhow::bail!("Backup media mirror directory must not overlap storage.media_dir");
    }
    reject_if_symlink(dest_media_dir).await?;

    // Canonicalize `source_media_dir` and the nearest existing ancestor of `dest_media_dir`
    // BEFORE creating `dest_media_dir`, so symlink-alias rejection leaves no stray subdir behind.
    let canon_src = tokio::fs::canonicalize(source_media_dir).await?;
    check_canonical_overlap_before_create(&canon_src, &norm_dst).await?;
    ensure_private_dir(dest_media_dir).await?;

    let canon_dst = tokio::fs::canonicalize(dest_media_dir).await?;
    if paths_overlap(&canon_src, &canon_dst) {
        anyhow::bail!("Backup media mirror directory canonically overlaps storage.media_dir");
    }

    let src_owned = canon_src;
    let dst_owned = dest_media_dir.to_path_buf();
    tokio::task::spawn_blocking(move || mirror_media_dir_blocking(&src_owned, &dst_owned, &cancel))
        .await
        .map_err(|e| anyhow::anyhow!("Media mirror task failed: {e}"))?
}

fn paths_overlap(a: &Path, b: &Path) -> bool {
    a == b || a.starts_with(b) || b.starts_with(a)
}

async fn check_canonical_overlap_before_create(canon_src: &Path, norm_dst: &Path) -> Result<()> {
    for anc in norm_dst.ancestors() {
        if let Ok(canon_anc) = tokio::fs::canonicalize(anc).await {
            let tail = norm_dst.strip_prefix(anc).unwrap_or_else(|_| Path::new(""));
            let resolved_dst =
                if tail.as_os_str().is_empty() { canon_anc } else { canon_anc.join(tail) };
            if paths_overlap(canon_src, &resolved_dst) {
                anyhow::bail!(
                    "Backup media mirror directory canonically overlaps storage.media_dir"
                );
            }
            break;
        }
    }
    Ok(())
}

fn mirror_media_dir_blocking(
    src_root: &Path,
    dst_root: &Path,
    cancel: &AtomicBool,
) -> Result<MediaMirrorStats> {
    let mut stats = MediaMirrorStats {
        dest_dir: dst_root.to_path_buf(),
        copied_files: 0,
        skipped_files: 0,
        removed_files: 0,
        total_bytes: 0,
    };
    let mut seen_rel: HashSet<PathBuf> = HashSet::new();
    let mut stack: Vec<PathBuf> = vec![PathBuf::new()];

    while let Some(rel_dir) = stack.pop() {
        if cancel.load(Ordering::Acquire) {
            anyhow::bail!("Media mirror cancelled during shutdown");
        }
        let cur_src = src_root.join(&rel_dir);
        let cur_dst = dst_root.join(&rel_dir);
        let rd = match std::fs::read_dir(&cur_src) {
            Ok(rd) => rd,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e.into()),
        };

        // If a previous mirror had a regular file where a directory now exists, replace it.
        if let Ok(dm) = std::fs::symlink_metadata(&cur_dst)
            && !dm.is_dir()
            && !dm.file_type().is_symlink()
        {
            std::fs::remove_file(&cur_dst)?;
        }
        ensure_private_dir_sync(&cur_dst)?;

        for entry_res in rd {
            if cancel.load(Ordering::Acquire) {
                anyhow::bail!("Media mirror cancelled during shutdown");
            }
            let entry = match entry_res {
                Ok(e) => e,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
                Err(e) => return Err(e.into()),
            };
            let name = entry.file_name();
            if name.to_string_lossy().starts_with(".tmp-") {
                continue;
            }
            let ft = match entry.file_type() {
                Ok(ft) => ft,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
                Err(e) => return Err(e.into()),
            };
            if ft.is_symlink() {
                continue;
            }
            let rel_path = rel_dir.join(&name);
            if ft.is_dir() {
                stack.push(rel_path);
            } else if ft.is_file() {
                let src_file = entry.path();
                let dst_file = dst_root.join(&rel_path);
                let src_meta = match entry.metadata() {
                    Ok(m) => m,
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
                    Err(e) => return Err(e.into()),
                };
                let src_len = src_meta.len();
                let src_mtime = src_meta.modified().ok();

                let unchanged = std::fs::symlink_metadata(&dst_file).is_ok_and(|dm| {
                    dm.is_file()
                        && !dm.file_type().is_symlink()
                        && dm.len() == src_len
                        && timestamps_match(src_mtime, dm.modified().ok())
                });
                if unchanged {
                    seen_rel.insert(rel_path);
                    stats.total_bytes = stats.total_bytes.saturating_add(src_len);
                    stats.skipped_files += 1;
                    continue;
                }

                match copy_private_file_atomic_sync(&src_file, &dst_file, src_mtime, cancel) {
                    Ok(copied_len) => {
                        seen_rel.insert(rel_path);
                        stats.total_bytes = stats.total_bytes.saturating_add(copied_len);
                        stats.copied_files += 1;
                    }
                    Err(e) if is_not_found_err(&e) => {
                        // Source file vanished concurrently (e.g., deleted by user); skip cleanly.
                    }
                    Err(e) => return Err(e),
                }
            }
        }
    }

    stats.removed_files = prune_stale_mirror_files(dst_root, &seen_rel);
    Ok(stats)
}

fn is_not_found_err(err: &anyhow::Error) -> bool {
    err.chain().any(|cause| {
        cause
            .downcast_ref::<std::io::Error>()
            .is_some_and(|io_err| io_err.kind() == std::io::ErrorKind::NotFound)
    })
}

fn timestamps_match(a: Option<SystemTime>, b: Option<SystemTime>) -> bool {
    let (Some(ta), Some(tb)) = (a, b) else {
        return false;
    };
    let diff = ta.duration_since(tb).or_else(|_| tb.duration_since(ta)).unwrap_or(Duration::MAX);
    diff < Duration::from_secs(1)
}

fn prune_stale_mirror_files(dst_root: &Path, expected_rel: &HashSet<PathBuf>) -> usize {
    let mut removed = 0usize;
    let mut dirs_to_visit: Vec<PathBuf> = vec![PathBuf::new()];
    let mut empty_check_dirs: Vec<PathBuf> = Vec::new();

    while let Some(rel_dir) = dirs_to_visit.pop() {
        let cur_dst = dst_root.join(&rel_dir);
        let Ok(rd) = std::fs::read_dir(&cur_dst) else {
            continue;
        };
        for entry_res in rd {
            let Ok(entry) = entry_res else {
                continue;
            };
            let Ok(ft) = entry.file_type() else {
                continue;
            };
            if ft.is_symlink() {
                continue;
            }
            let file_name = entry.file_name();
            let name_str = file_name.to_string_lossy();
            let rel_path = rel_dir.join(&file_name);
            if ft.is_dir() {
                dirs_to_visit.push(rel_path.clone());
                empty_check_dirs.push(rel_path);
            } else if ft.is_file() && !expected_rel.contains(&rel_path) {
                if name_str.starts_with(MIRROR_TMP_PREFIX) {
                    let is_stale = entry
                        .metadata()
                        .ok()
                        .and_then(|m| m.modified().ok())
                        .and_then(|t| t.elapsed().ok())
                        .is_some_and(|age| age >= STALE_TMP_MAX_AGE);
                    if !is_stale {
                        continue;
                    }
                }
                if std::fs::remove_file(entry.path()).is_ok() {
                    removed += 1;
                }
            }
        }
    }

    // Remove subdirectories deepest-first if now empty.
    empty_check_dirs.sort_by_key(|p| std::cmp::Reverse(p.components().count()));
    for rel_dir in empty_check_dirs {
        let _ = std::fs::remove_dir(dst_root.join(rel_dir));
    }
    removed
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BackupFormat {
    Zstd,
    Sqlite,
}

async fn detect_backup_format(path: &Path) -> Result<BackupFormat> {
    let p = path.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<BackupFormat> {
        let mut f = File::open(&p)
            .with_context(|| format!("Failed to open backup file {}", p.display()))?;
        let mut header = [0u8; 16];
        let mut filled = 0usize;
        while filled < header.len() {
            match f.read(&mut header[filled..]) {
                Ok(0) => break,
                Ok(n) => filled += n,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                Err(e) => return Err(e.into()),
            }
        }
        if filled >= ZSTD_MAGIC.len() && header[..4] == ZSTD_MAGIC {
            Ok(BackupFormat::Zstd)
        } else if filled == SQLITE_MAGIC_PREFIX.len() && header == SQLITE_MAGIC_PREFIX {
            Ok(BackupFormat::Sqlite)
        } else {
            anyhow::bail!(
                "Backup file '{}' is neither a Zstandard archive nor a SQLite 3 database",
                p.display()
            )
        }
    })
    .await
    .map_err(|e| anyhow::anyhow!("Backup format probe task failed: {e}"))?
}

async fn verify_sqlite_snapshot(db_path: &Path, full_check: bool) -> Result<i64> {
    // Open with `immutable(true)` so cleanly-closed WAL-mode snapshots (e.g. from `sqlite3 .backup`)
    // verify cleanly without requiring `-shm` and never incorporate a stray adjacent `-wal` file.
    let opts = SqliteConnectOptions::new()
        .filename(db_path)
        .create_if_missing(false)
        .read_only(true)
        .immutable(true);
    let pool = SqlitePoolOptions::new().max_connections(1).connect_with(opts).await?;
    let res: Result<i64> = async {
        let pragma = if full_check { "PRAGMA integrity_check" } else { "PRAGMA quick_check" };
        let checks: Vec<String> = sqlx::query_scalar(pragma).fetch_all(&pool).await?;
        if checks.len() != 1 || checks[0] != "ok" {
            anyhow::bail!("SQLite {pragma} failed on {}: {}", db_path.display(), checks.join("; "));
        }

        let core_tables: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM sqlite_master
             WHERE type = 'table' AND name IN ('entries', 'sync_clock')",
        )
        .fetch_one(&pool)
        .await?;
        if core_tables < 2 {
            anyhow::bail!(
                "Database {} does not appear to be a Roche database (missing `entries` or `sync_clock` table)",
                db_path.display()
            );
        }

        if full_check {
            let violations: Vec<(String, Option<i64>, String, i64)> =
                sqlx::query_as("PRAGMA foreign_key_check").fetch_all(&pool).await?;
            if !violations.is_empty() {
                anyhow::bail!(
                    "SQLite foreign_key_check failed on {} with {} violation(s): {:?}",
                    db_path.display(),
                    violations.len(),
                    violations.first()
                );
            }
        }

        let entry_count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM entries").fetch_one(&pool).await?;
        Ok(entry_count)
    }
    .await;
    pool.close().await;
    res
}

async fn compress_file_zstd(
    src: PathBuf,
    dst: PathBuf,
    level: i32,
    cancel: Arc<AtomicBool>,
) -> Result<u64> {
    tokio::task::spawn_blocking(move || -> Result<u64> {
        let mut reader = BufReader::with_capacity(IO_BUF_BYTES, File::open(&src)?);
        let out_file = create_private_file_sync(&dst)?;
        let writer = BufWriter::with_capacity(IO_BUF_BYTES, out_file);
        let mut encoder = zstd::stream::Encoder::new(writer, level)?;
        encoder.include_checksum(true)?;
        let mut buf = vec![0u8; IO_BUF_BYTES];
        loop {
            if cancel.load(Ordering::Acquire) {
                anyhow::bail!("Backup compression cancelled during shutdown");
            }
            let n = reader.read(&mut buf)?;
            if n == 0 {
                break;
            }
            encoder.write_all(&buf[..n])?;
        }
        let mut writer = encoder.finish()?;
        writer.flush()?;
        let out_file = writer
            .into_inner()
            .map_err(|e| anyhow::anyhow!("Failed to flush compressed backup: {}", e.error()))?;
        out_file.sync_all()?;
        Ok(out_file.metadata()?.len())
    })
    .await
    .map_err(|e| anyhow::anyhow!("Zstandard compression task failed: {e}"))?
}

async fn decompress_file_zstd(src: PathBuf, dst: PathBuf) -> Result<u64> {
    tokio::task::spawn_blocking(move || -> Result<u64> {
        let reader = BufReader::with_capacity(IO_BUF_BYTES, File::open(&src)?);
        let mut decoder = zstd::stream::Decoder::new(reader)?;
        let out_file = create_private_file_sync(&dst)?;
        let mut writer = BufWriter::with_capacity(IO_BUF_BYTES, out_file);
        let written = std::io::copy(&mut decoder, &mut writer)?;
        writer.flush()?;
        let out_file = writer
            .into_inner()
            .map_err(|e| anyhow::anyhow!("Failed to flush decompressed backup: {}", e.error()))?;
        out_file.sync_all()?;
        Ok(written)
    })
    .await
    .map_err(|e| anyhow::anyhow!("Zstandard decompression task failed: {e}"))?
}

async fn copy_private_file_synced(src: PathBuf, dst: PathBuf) -> Result<u64> {
    tokio::task::spawn_blocking(move || -> Result<u64> {
        let mut reader = BufReader::with_capacity(IO_BUF_BYTES, File::open(&src)?);
        let out_file = create_private_file_sync(&dst)?;
        let mut writer = BufWriter::with_capacity(IO_BUF_BYTES, out_file);
        let written = std::io::copy(&mut reader, &mut writer)?;
        writer.flush()?;
        let out_file = writer
            .into_inner()
            .map_err(|e| anyhow::anyhow!("Failed to flush copied backup: {}", e.error()))?;
        out_file.sync_all()?;
        Ok(written)
    })
    .await
    .map_err(|e| anyhow::anyhow!("Backup copy task failed: {e}"))?
}

async fn sync_file_async(path: PathBuf) -> Result<()> {
    tokio::task::spawn_blocking(move || -> Result<()> {
        let f = File::open(&path)?;
        f.sync_all()?;
        Ok(())
    })
    .await
    .map_err(|e| anyhow::anyhow!("Backup sync task failed: {e}"))?
}

async fn sync_dir_async(dir: PathBuf) {
    let _ = tokio::task::spawn_blocking(move || sync_dir_sync(&dir)).await;
}

fn sync_dir_sync(dir: &Path) {
    #[cfg(unix)]
    {
        if let Ok(f) = File::open(dir) {
            let _ = f.sync_all();
        }
    }
    #[cfg(not(unix))]
    {
        let _ = dir;
    }
}

fn create_private_file_sync(path: &Path) -> Result<File> {
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    opts.open(path).with_context(|| format!("Failed to create private file {}", path.display()))
}

async fn create_exclusive_private_dir(dir: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        let mut builder = tokio::fs::DirBuilder::new();
        builder.recursive(false).mode(0o700);
        builder
            .create(dir)
            .await
            .with_context(|| format!("Failed to create staging directory {}", dir.display()))?;
    }
    #[cfg(not(unix))]
    {
        tokio::fs::create_dir(dir)
            .await
            .with_context(|| format!("Failed to create staging directory {}", dir.display()))?;
    }
    Ok(())
}

fn ensure_private_dir_sync(dir: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        let mut builder = std::fs::DirBuilder::new();
        builder.recursive(true).mode(0o700);
        builder.create(dir)?;
    }
    #[cfg(not(unix))]
    {
        std::fs::create_dir_all(dir)?;
    }
    Ok(())
}

fn copy_private_file_atomic_sync(
    src: &Path,
    dst: &Path,
    preserve_mtime: Option<SystemTime>,
    cancel: &AtomicBool,
) -> Result<u64> {
    let parent = dst
        .parent()
        .ok_or_else(|| anyhow::anyhow!("Destination path has no parent: {}", dst.display()))?;
    ensure_private_dir_sync(parent)?;
    let tmp = parent.join(format!("{MIRROR_TMP_PREFIX}{}", uuid::Uuid::new_v4()));

    let write_res: Result<u64> = (|| {
        let mut reader = BufReader::with_capacity(IO_BUF_BYTES, File::open(src)?);
        let out_file = create_private_file_sync(&tmp)?;
        let mut writer = BufWriter::with_capacity(IO_BUF_BYTES, out_file);
        let mut buf = vec![0u8; IO_BUF_BYTES];
        let mut total = 0u64;
        loop {
            if cancel.load(Ordering::Acquire) {
                anyhow::bail!("Media mirror cancelled during shutdown");
            }
            let n = reader.read(&mut buf)?;
            if n == 0 {
                break;
            }
            writer.write_all(&buf[..n])?;
            total = total.saturating_add(u64::try_from(n).unwrap_or(0));
        }
        writer.flush()?;
        let out_file = writer
            .into_inner()
            .map_err(|e| anyhow::anyhow!("Failed to flush mirrored file: {}", e.error()))?;
        if let Some(mtime) = preserve_mtime {
            let _ = out_file.set_modified(mtime);
        }
        out_file.sync_all()?;
        Ok(total)
    })();

    let copied_len = match write_res {
        Ok(len) => len,
        Err(e) => {
            let _ = std::fs::remove_file(&tmp);
            return Err(e);
        }
    };

    // If destination previously existed as a directory (file <-> dir transition), remove it first.
    if let Ok(dm) = std::fs::symlink_metadata(dst)
        && dm.is_dir()
        && !dm.file_type().is_symlink()
    {
        let _ = std::fs::remove_dir_all(dst);
    }

    if let Err(e) = std::fs::rename(&tmp, dst) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e.into());
    }
    sync_dir_sync(parent);
    Ok(copied_len)
}

fn append_path_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut os = path.as_os_str().to_os_string();
    os.push(suffix);
    PathBuf::from(os)
}

async fn path_exists_no_symlink(path: &Path) -> Result<bool> {
    match tokio::fs::symlink_metadata(path).await {
        Ok(meta) => {
            if meta.file_type().is_symlink() {
                anyhow::bail!("Refusing to follow symlinked backup path: {}", path.display());
            }
            Ok(true)
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e).with_context(|| format!("Failed to inspect path {}", path.display())),
    }
}

async fn reject_if_symlink(path: &Path) -> Result<()> {
    match tokio::fs::symlink_metadata(path).await {
        Ok(meta) if meta.file_type().is_symlink() => {
            anyhow::bail!("Refusing to follow symlinked backup path: {}", path.display());
        }
        Ok(_) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e).with_context(|| format!("Failed to inspect path {}", path.display())),
    }
}

async fn cleanup_stale_temp_entries(dir: &Path, max_age: Duration) -> Result<usize> {
    let Ok(mut rd) = tokio::fs::read_dir(dir).await else {
        return Ok(0);
    };
    let mut removed = 0usize;
    while let Ok(Some(entry)) = rd.next_entry().await {
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.starts_with(BACKUP_TMP_PREFIX) {
            continue;
        }
        let Ok(ft) = entry.file_type().await else {
            continue;
        };
        if ft.is_symlink() {
            continue;
        }
        let is_stale = entry
            .metadata()
            .await
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.elapsed().ok())
            .is_some_and(|age| age >= max_age);
        if !is_stale {
            continue;
        }
        let ok = if ft.is_dir() {
            tokio::fs::remove_dir_all(entry.path()).await.is_ok()
        } else if ft.is_file() {
            tokio::fs::remove_file(entry.path()).await.is_ok()
        } else {
            false
        };
        if ok {
            removed += 1;
        }
    }
    Ok(removed)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use chrono::TimeZone;
    use url::Url;

    use super::*;
    use crate::{db::NewItem, domain::ContentType};

    fn fake_entry(year: i32, month: u32, day: u32, hour: u32) -> BackupEntry {
        let ts = Utc.with_ymd_and_hms(year, month, day, hour, 0, 0).unwrap();
        let file_name = format_backup_filename(ts, true);
        BackupEntry {
            path: PathBuf::from(&file_name),
            file_name,
            timestamp: ts,
            size_bytes: 1024,
            compressed: true,
        }
    }

    fn simulate_daily_backups(
        start: DateTime<Utc>,
        days: i64,
        policy: RetentionPolicy,
    ) -> Vec<(i64, Vec<i64>)> {
        let mut on_disk: Vec<BackupEntry> = Vec::new();
        let mut snapshots = Vec::new();
        for day in 0..days {
            let ts = start + chrono::Duration::days(day);
            let file_name = format_backup_filename(ts, true);
            let entry = BackupEntry {
                path: PathBuf::from(&file_name),
                file_name: file_name.clone(),
                timestamp: ts,
                size_bytes: 1024,
                compressed: true,
            };
            on_disk.push(entry);
            let plan = plan_retention(&on_disk, policy);
            // Newly created backup must NEVER be immediately pruned (even when keep_daily == 0).
            assert!(
                plan.keep.iter().any(|r| r.entry.file_name == file_name),
                "day {day}: freshly created backup {file_name} was pruned under {policy:?}"
            );
            on_disk = plan.keep.iter().map(|r| r.entry.clone()).collect();
            let anchor_date = ts.date_naive();
            let ages: Vec<i64> = on_disk
                .iter()
                .map(|e| anchor_date.signed_duration_since(e.timestamp.date_naive()).num_days())
                .collect();
            snapshots.push((day, ages));
        }
        snapshots
    }

    #[test]
    fn test_filename_roundtrip_seconds_and_millis() {
        let ts = Utc.with_ymd_and_hms(2026, 11, 24, 10, 48, 19).unwrap();
        let zst = format_backup_filename(ts, true);
        assert_eq!(zst, "roche-2026-11-24T10-48-19Z.db.zst");
        assert_eq!(parse_backup_filename(&zst), Some((ts, true)));

        let raw = format_backup_filename(ts, false);
        assert_eq!(raw, "roche-2026-11-24T10-48-19Z.db");
        assert_eq!(parse_backup_filename(&raw), Some((ts, false)));

        let millis = format_backup_filename_millis(ts + chrono::Duration::milliseconds(42), true);
        assert_eq!(millis, "roche-2026-11-24T10-48-19-042Z.db.zst");
        let parsed = parse_backup_filename(&millis).unwrap();
        assert_eq!(parsed.0, ts + chrono::Duration::milliseconds(42));
        assert!(parsed.1);

        assert_eq!(parse_backup_filename("notes.txt"), None);
        assert_eq!(parse_backup_filename(".tmp-roche-backup-123.db"), None);
        assert_eq!(parse_backup_filename("roche-invalid.db.zst"), None);
    }

    #[test]
    fn test_gfs_retention_180_day_simulations_across_policies() {
        let start = Utc.with_ymd_and_hms(2026, 1, 1, 3, 0, 0).unwrap();

        // 1. Default policy: last 3 days + 1 weekly + 1 monthly (max 5 files, steady ages).
        let default_policy = RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 1 };
        for (day, ages) in simulate_daily_backups(start, 180, default_policy) {
            assert!(ages.len() <= 5, "day {day}: expected <= 5 files, got {ages:?}");
            if day >= 2 {
                assert!(ages.contains(&0) && ages.contains(&1) && ages.contains(&2));
            }
            if day >= 14 {
                assert!(
                    ages.iter().any(|&a| (3..=13).contains(&a)),
                    "day {day}: missing weekly backup (ages={ages:?})"
                );
            }
            if day >= 45 {
                assert!(
                    ages.iter().any(|&a| (7..=45).contains(&a)),
                    "day {day}: missing monthly backup (ages={ages:?})"
                );
            }
        }

        // 2. Multi-slot policy (7, 4, 2): must retain 2 distinct prior months with zero cliff.
        let multi = RetentionPolicy { keep_daily: 7, keep_weekly: 4, keep_monthly: 2 };
        for (day, ages) in simulate_daily_backups(start, 180, multi) {
            assert!(ages.len() <= 13, "day {day}: expected <= 13 files, got {ages:?}");
            if day >= 120 {
                assert!(
                    ages.iter().any(|&a| a >= 60),
                    "day {day}: (7,4,2) collapsed oldest history (ages={ages:?})"
                );
            }
        }

        // 3. Multi-monthly with 1 weekly (3, 1, 2): must keep 2 prior months (oldest >= 35d) after 90 days.
        let two_months = RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 2 };
        for (day, ages) in simulate_daily_backups(start, 180, two_months) {
            assert!(ages.len() <= 6, "day {day}: expected <= 6 files, got {ages:?}");
            if day >= 90 {
                assert_eq!(ages.len(), 6, "day {day}: expected 6 files (3+1+2), got {ages:?}");
                assert!(
                    ages.iter().any(|&a| a >= 35),
                    "day {day}: (3,1,2) collapsed oldest history (ages={ages:?})"
                );
            }
        }

        // 4. Zero-weekly multi-monthly (3, 0, 2) and zero-monthly multi-weekly (3, 2, 0).
        let no_weekly = RetentionPolicy { keep_daily: 3, keep_weekly: 0, keep_monthly: 2 };
        for (day, ages) in simulate_daily_backups(start, 120, no_weekly) {
            assert!(ages.len() <= 5);
            if day >= 75 {
                assert!(ages.iter().any(|&a| a >= 31), "day {day}: (3,0,2) ages={ages:?}");
            }
        }

        let no_monthly = RetentionPolicy { keep_daily: 3, keep_weekly: 2, keep_monthly: 0 };
        for (day, ages) in simulate_daily_backups(start, 60, no_monthly) {
            assert!(ages.len() <= 5);
            if day >= 21 {
                assert!(ages.iter().any(|&a| a >= 10), "day {day}: (3,2,0) ages={ages:?}");
            }
        }

        // 5. Zero-daily (0, 1, 1): newest backup is NEVER pruned; keeps latest + weekly + monthly.
        let zero_daily = RetentionPolicy { keep_daily: 0, keep_weekly: 1, keep_monthly: 1 };
        for (day, ages) in simulate_daily_backups(start, 90, zero_daily) {
            assert!(ages.len() <= 3, "day {day}: expected <= 3 files, got {ages:?}");
            assert!(ages.contains(&0));
        }
    }

    #[test]
    fn test_gfs_retention_deduplicates_same_day_and_preserves_offline_history() {
        let policy = RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 1 };

        // Multiple backups on the same day keep only the newest one for that day
        let same_day = vec![
            fake_entry(2026, 11, 24, 8),
            fake_entry(2026, 11, 24, 14),
            fake_entry(2026, 11, 24, 20),
        ];
        let plan = plan_retention(&same_day, policy);
        assert_eq!(plan.keep.len(), 1);
        assert_eq!(plan.keep[0].entry.timestamp.format("%H").to_string(), "20");
        assert_eq!(plan.prune.len(), 2);

        // Host wakes up after 60 days offline: daily tier still keeps the 3 most recent distinct days
        let sparse = vec![
            fake_entry(2026, 9, 1, 3),
            fake_entry(2026, 9, 20, 3),
            fake_entry(2026, 11, 24, 3),
        ];
        let sparse_plan = plan_retention(&sparse, policy);
        assert_eq!(sparse_plan.keep.len(), 3);
        assert_eq!(sparse_plan.prune.len(), 0);
    }

    async fn seed_roundtrip_items(db: &Db) -> (String, String, String) {
        let url1 = Url::parse("https://example.com/backup-keep-1").unwrap();
        let url2 = Url::parse("https://example.com/backup-delete-2").unwrap();
        let url3 = Url::parse("https://example.com/backup-keep-3").unwrap();
        let item1 = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Keep Me One",
                url: &url1,
                author: Some("Alice"),
                published_at: None,
                summary: Some("Important summary"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        let item2 = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Delete Before Vacuum",
                url: &url2,
                author: None,
                published_at: None,
                summary: None,
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        let item3 = db
            .items()
            .create(&NewItem {
                feed_id: None,
                title: "Keep Me Three",
                url: &url3,
                author: None,
                published_at: None,
                summary: Some("Searchable keyword zstdbackup"),
                content_type: ContentType::Article,
            })
            .await
            .unwrap();
        // Delete middle item so VACUUM INTO exercises rowid compaction + FTS healing on restore.
        db.items().delete(&item2.id).await.unwrap();
        (item1.id, item2.id, item3.id)
    }

    #[tokio::test]
    async fn test_live_wal_backup_verify_and_restore_roundtrip() {
        let root = std::env::temp_dir().join(format!("roche-backup-test-{}", uuid::Uuid::new_v4()));
        let db_path = root.join("roche.db");
        let media_dir = root.join("media");
        let backup_dir = root.join("backups");
        tokio::fs::create_dir_all(media_dir.join("pdfs")).await.unwrap();
        tokio::fs::write(media_dir.join("pdfs/paper.pdf"), b"%PDF-1.7\nhello\n%%EOF")
            .await
            .unwrap();

        let db = Db::connect(&db_path).await.unwrap();
        let (item1_id, item2_id, item3_id) = seed_roundtrip_items(&db).await;

        let opts = BackupOptions {
            backup_dir: backup_dir.clone(),
            explicit_output: None,
            compress: true,
            compression_level: 3,
            prune: true,
            retention: RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 1 },
            include_media: true,
            media_dir: media_dir.clone(),
        };
        let outcome = create_backup_from_db(&db, &opts).await.unwrap();
        assert!(outcome.compressed);
        assert!(outcome.path.exists());
        assert!(outcome.final_bytes > 0);
        assert!(outcome.final_bytes < outcome.uncompressed_bytes);
        assert_eq!(outcome.media.unwrap().copied_files, 1);
        assert!(backup_dir.join("media/pdfs/paper.pdf").exists());

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode =
                tokio::fs::metadata(&outcome.path).await.unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }

        // Verify compressed backup thoroughly and confirm bit-flip corruption is caught
        let verified = verify_backup_file(&outcome.path).await.unwrap();
        assert!(verified.compressed);
        assert!(verified.entry_count >= 2);
        let corrupt_path = root.join("corrupt.db.zst");
        let mut bytes = tokio::fs::read(&outcome.path).await.unwrap();
        let mid = bytes.len() / 2;
        bytes[mid] ^= 0xFF;
        tokio::fs::write(&corrupt_path, &bytes).await.unwrap();
        assert!(verify_backup_file(&corrupt_path).await.is_err());

        // Restore into a target DB path that already has stale DB + WAL + SHM + journal sidecars
        let restored_path = root.join("restored/roche.db");
        tokio::fs::create_dir_all(restored_path.parent().unwrap()).await.unwrap();
        tokio::fs::write(&restored_path, b"stale").await.unwrap();
        tokio::fs::write(root.join("restored/roche.db-wal"), b"stale-wal").await.unwrap();
        tokio::fs::write(root.join("restored/roche.db-shm"), b"stale-shm").await.unwrap();
        tokio::fs::write(root.join("restored/roche.db-journal"), b"stale-jnl").await.unwrap();
        assert!(restore_backup_file(&outcome.path, &restored_path, false).await.is_err());

        let restored = restore_backup_file(&outcome.path, &restored_path, true).await.unwrap();
        assert_eq!(restored.removed_sidecars.len(), 3);
        assert!(!root.join("restored/roche.db-wal").exists());
        assert!(!root.join("restored/roche.db-shm").exists());
        assert!(!root.join("restored/roche.db-journal").exists());

        // Connect to restored DB with full Roche Db::connect (including FK check + FTS heal)
        drop(db);
        let restored_db = Db::connect(&restored_path).await.unwrap();
        assert!(restored_db.items().get(&item1_id).await.unwrap().is_some());
        assert!(restored_db.items().get(&item2_id).await.unwrap().is_none());
        assert!(restored_db.items().get(&item3_id).await.unwrap().is_some());
        let restore_epoch: i64 =
            sqlx::query_scalar("SELECT restore_epoch FROM sync_clock WHERE id = 1")
                .fetch_one(restored_db.pool())
                .await
                .unwrap();
        assert_eq!(restore_epoch, 1, "restore_backup_file must bump sync_clock.restore_epoch");

        let found = restored_db
            .items()
            .list_filtered_page(&crate::db::ItemFilter {
                search: Some("zstdbackup".to_owned()),
                ..crate::db::ItemFilter::default()
            })
            .await
            .unwrap();
        assert_eq!(found.items.len(), 1);
        assert_eq!(found.items[0].id, item3_id);

        // CLI path-based backup must reject overwriting live DB; uncompressed explicit backup works
        let mut raw_opts = BackupOptions {
            backup_dir,
            explicit_output: Some(restored_path.clone()),
            compress: false,
            compression_level: 3,
            prune: false,
            retention: RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 1 },
            include_media: false,
            media_dir,
        };
        assert!(create_backup_from_path(&restored_path, &raw_opts).await.is_err());
        let raw_out = root.join("manual.db");
        raw_opts.explicit_output = Some(raw_out.clone());
        let raw_outcome = create_backup_from_path(&restored_path, &raw_opts).await.unwrap();
        assert!(!raw_outcome.compressed);
        assert!(verify_backup_file(&raw_out).await.is_ok());

        let _ = tokio::fs::remove_dir_all(&root).await;
    }

    #[tokio::test]
    async fn test_restore_rejects_locked_db_and_verifies_wal_mode_snapshot() {
        let root = std::env::temp_dir().join(format!("roche-bk-guard-{}", uuid::Uuid::new_v4()));
        let db_path = root.join("roche.db");
        let backup_dir = root.join("backups");
        let media_dir = root.join("media");

        let db = Db::connect(&db_path).await.unwrap();
        let _ = seed_roundtrip_items(&db).await;

        let opts = BackupOptions {
            backup_dir: backup_dir.clone(),
            explicit_output: None,
            compress: true,
            compression_level: 3,
            prune: true,
            retention: RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 1 },
            include_media: false,
            media_dir,
        };
        let outcome = create_backup_from_db(&db, &opts).await.unwrap();

        // 1. Even an IDLE open WAL `Db` pool (no active WriteTx) must be detected and rejected!
        let idle_err = restore_backup_file(&outcome.path, &db_path, true).await.unwrap_err();
        assert!(
            idle_err.to_string().contains("locked") || idle_err.to_string().contains("in use"),
            "idle open WAL pool must be rejected: {idle_err}"
        );

        // 2. Capture a real crashed WAL DB snapshot (main DB + genuine -wal + -shm while open).
        let crashed_dir = root.join("crashed");
        tokio::fs::create_dir_all(&crashed_dir).await.unwrap();
        let crashed_db = crashed_dir.join("roche.db");
        tokio::fs::copy(&db_path, &crashed_db).await.unwrap();
        let src_wal = append_path_suffix(&db_path, "-wal");
        let src_shm = append_path_suffix(&db_path, "-shm");
        assert!(src_wal.exists(), "expected live -wal file");
        tokio::fs::copy(&src_wal, append_path_suffix(&crashed_db, "-wal")).await.unwrap();
        if src_shm.exists() {
            tokio::fs::copy(&src_shm, append_path_suffix(&crashed_db, "-shm")).await.unwrap();
        }

        // 3. While an active write transaction holds the DB lock, restore must also refuse.
        let mut active_tx = db.begin_write().await.unwrap();
        sqlx::query("UPDATE sync_clock SET current_seq = current_seq WHERE id = 1")
            .execute(active_tx.conn())
            .await
            .unwrap();
        let locked_err = restore_backup_file(&outcome.path, &db_path, true).await.unwrap_err();
        assert!(
            locked_err.to_string().contains("locked") || locked_err.to_string().contains("in use"),
            "unexpected error: {locked_err}"
        );
        drop(active_tx);
        drop(db);

        // 4. Restoring over a real crashed WAL DB with genuine -wal/-shm must succeed without ENOENT!
        let crashed_restore = restore_backup_file(&outcome.path, &crashed_db, true).await.unwrap();
        assert!(crashed_restore.entry_count >= 2);
        assert!(!append_path_suffix(&crashed_db, "-wal").exists());
        assert!(!append_path_suffix(&crashed_db, "-shm").exists());

        // 5. A cleanly closed WAL-mode DB copy (no -shm present) must verify cleanly via immutable=true.
        let wal_snap = root.join("wal-snapshot.db");
        tokio::fs::copy(&db_path, &wal_snap).await.unwrap();
        let verified = verify_backup_file(&wal_snap).await.unwrap();
        assert!(!verified.compressed);
        assert!(verified.entry_count >= 2);

        // 6. `prune_backups(..., dry_run = true)` must NOT delete stale temp files.
        let stale_tmp = backup_dir.join(format!("{BACKUP_TMP_PREFIX}dryrun-keep.db"));
        tokio::fs::write(&stale_tmp, b"temp").await.unwrap();
        let _ = prune_backups(
            &backup_dir,
            RetentionPolicy { keep_daily: 3, keep_weekly: 1, keep_monthly: 1 },
            true,
        )
        .await
        .unwrap();
        assert!(stale_tmp.exists(), "dry-run prune must not delete any files");

        let _ = tokio::fs::remove_dir_all(&root).await;
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn test_media_mirror_rejects_parent_symlink_overlap_and_tolerates_deletes() {
        let root = std::env::temp_dir().join(format!("roche-mirror-test-{}", uuid::Uuid::new_v4()));
        let media_dir = root.join("media");
        let dst_dir = root.join("backup-media");
        tokio::fs::create_dir_all(media_dir.join("pdfs")).await.unwrap();
        tokio::fs::write(media_dir.join("pdfs/a.pdf"), b"%PDF-1.7\na").await.unwrap();

        let alias_link = root.join("media-link");
        std::os::unix::fs::symlink(&media_dir, &alias_link).unwrap();
        assert!(
            mirror_media_dir(&media_dir, &alias_link.join("nested")).await.is_err(),
            "parent symlink aliasing media_dir must be rejected"
        );
        assert!(
            !media_dir.join("nested").exists(),
            "rejected parent-symlink overlap must not leave a stray directory inside media_dir"
        );

        let s1 = mirror_media_dir(&media_dir, &dst_dir).await.unwrap();
        assert_eq!(s1.copied_files, 1);
        assert_eq!(s1.skipped_files, 0);

        // Unchanged second pass skips file; removing source deletes mirrored copy
        let s2 = mirror_media_dir(&media_dir, &dst_dir).await.unwrap();
        assert_eq!(s2.copied_files, 0);
        assert_eq!(s2.skipped_files, 1);

        tokio::fs::remove_file(media_dir.join("pdfs/a.pdf")).await.unwrap();
        let s3 = mirror_media_dir(&media_dir, &dst_dir).await.unwrap();
        assert_eq!(s3.removed_files, 1);
        assert!(!dst_dir.join("pdfs/a.pdf").exists());

        let _ = tokio::fs::remove_dir_all(&root).await;
    }

    #[tokio::test]
    async fn test_periodic_backup_skips_when_recent_and_runs_when_due() {
        let root = std::env::temp_dir().join(format!("roche-periodic-bk-{}", uuid::Uuid::new_v4()));
        let db_path = root.join("roche.db");
        let backup_dir = root.join("backups");
        let media_dir = root.join("media");
        let db = Db::connect(&db_path).await.unwrap();

        let cfg = BackupConfig {
            enabled: true,
            dir: backup_dir.clone(),
            interval_hours: 24,
            compress: true,
            compression_level: 3,
            include_media: false,
            keep_daily: 3,
            keep_weekly: 1,
            keep_monthly: 1,
        };

        // First run creates a backup
        let first = run_periodic_backup_if_due(&db, &cfg, &media_dir).await.unwrap();
        assert!(first.is_some());

        // Immediate second run skips because < 24h elapsed
        let second = run_periodic_backup_if_due(&db, &cfg, &media_dir).await.unwrap();
        assert!(second.is_none());

        // Replace with an older timestamped backup (48h ago) -> next pass runs and retains both!
        let first_path = first.unwrap().path;
        let old_ts = now_utc() - chrono::Duration::hours(48);
        let old_path = backup_dir.join(format_backup_filename(old_ts, true));
        tokio::fs::rename(&first_path, &old_path).await.unwrap();

        let third = run_periodic_backup_if_due(&db, &cfg, &media_dir).await.unwrap();
        assert!(third.is_some());
        let listed = list_backups(&backup_dir).await.unwrap();
        assert_eq!(listed.len(), 2);

        let _ = tokio::fs::remove_dir_all(&root).await;
    }
}
