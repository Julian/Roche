use std::path::PathBuf;

use clap::{Args, Parser, Subcommand};
use figment::{
    Figment, Metadata, Profile, Provider,
    value::{Dict, Map},
};

use crate::{
    config::Config,
    db::{CatalogBatchInput, CatalogEntry, CollectionGeneratorState, Db, UpsertEntryInput},
    domain::{CollectionRole, ConsumptionStatus, EntryKind},
    ranking::RANDOM_SEED_EVERY,
    service::{CatalogService, ClusterSelectFilter},
    tui_rank::{ClusterRankOutcome, open_system_browser, prompt_cluster_tty},
    util::terminal::sanitize_terminal_text,
};

#[derive(Parser, Debug, Clone, PartialEq, Eq)]
#[command(
    name = "roche",
    version,
    about = "Roche — Unified Feeds, Media Catalog & Ranking Tracker"
)]
pub struct Cli {
    /// Path to configuration file [env: `ROCHE_CONFIG`]
    #[arg(short, long, global = true, value_name = "FILE")]
    pub config: Option<PathBuf>,

    #[command(flatten)]
    pub overrides: CliOverrides,

    #[command(subcommand)]
    pub command: Option<Command>,
}

/// Command-line configuration overrides (highest precedence: CLI > env > config.toml > defaults).
#[derive(Args, Debug, Default, Clone, PartialEq, Eq)]
pub struct CliOverrides {
    /// Interface/host address to listen on [env: `ROCHE_HOST`]
    #[arg(
        short = 'H',
        long,
        visible_alias = "interface",
        alias = "bind",
        global = true,
        value_name = "HOST"
    )]
    pub host: Option<String>,

    /// TCP port to listen on [env: `ROCHE_PORT`]
    #[arg(short, long, global = true, value_name = "PORT")]
    pub port: Option<u16>,

    /// Base directory for data, database, and media storage [env: `ROCHE_DATA_DIR`]
    #[arg(long, global = true, value_name = "DIR")]
    pub data_dir: Option<PathBuf>,

    /// Tailscale Service name to auto-advertise via `tailscale serve` (e.g. `svc:roche`) [env: `ROCHE_TAILSCALE_ADVERTISE_SERVICE`]
    #[arg(long, global = true, value_name = "SERVICE")]
    pub advertise_service: Option<String>,
}

impl Provider for CliOverrides {
    fn metadata(&self) -> Metadata {
        Metadata::named("CLI options")
    }

    fn data(&self) -> Result<Map<Profile, Dict>, figment::Error> {
        let mut figment = Figment::new();
        if let Some(host) = &self.host {
            figment = figment.merge(("server.host", host.as_str()));
        }
        if let Some(port) = self.port {
            figment = figment.merge(("server.port", port));
        }
        if let Some(data_dir) = &self.data_dir {
            figment = figment.merge(("storage.data_dir", data_dir.as_path()));
        }
        if let Some(svc) = &self.advertise_service {
            figment = figment.merge(("tailscale.advertise_service", svc.as_str()));
        }
        figment.data()
    }
}

impl Cli {
    #[must_use]
    pub const fn is_server_daemon(&self) -> bool {
        matches!(self.command, None | Some(Command::Serve))
    }

    pub fn load_config(&self) -> anyhow::Result<Config> {
        Config::load_with_provider(self.config.as_deref(), &self.overrides)
    }

    #[allow(clippy::too_many_lines)]
    pub async fn run(self) -> anyhow::Result<()> {
        let cfg = self.load_config()?;
        match self.command {
            Some(Command::Serve) | None => crate::RocheServer::run(cfg).await,
            Some(Command::Pair) => {
                let code = crate::RocheServer::issue_pairing_code(&cfg).await?;
                println!("{code}");
                if let Some(url) = crate::RocheServer::inspect_tailscale_canonical_url(&cfg).await {
                    eprintln!("Tailscale one-step pairing bundle: {url}#{code}");
                }
                Ok(())
            }
            Some(Command::Discover) => {
                let servers = crate::RocheServer::discover_servers(&cfg).await?;
                if servers.is_empty() {
                    println!("No Roche servers discovered on Tailnet.");
                } else {
                    for s in servers {
                        let canonical =
                            sanitize_terminal_text(s.canonical_url.as_deref().unwrap_or(&s.url));
                        let probed = sanitize_terminal_text(&s.url);
                        let ver = sanitize_terminal_text(s.version.as_deref().unwrap_or("unknown"));
                        println!("{canonical}\t(probed={probed}, version={ver})");
                    }
                }
                Ok(())
            }
            Some(Command::Devices { action }) => match action {
                DeviceCommand::List => {
                    let devices = crate::RocheServer::list_devices(&cfg).await?;
                    if devices.is_empty() {
                        println!("No paired devices.");
                    } else {
                        for d in devices {
                            let safe_name = sanitize_terminal_text(&d.device_name);
                            let last = d
                                .last_used_at
                                .map_or_else(|| "never".to_string(), |ts| ts.to_rfc3339());
                            println!(
                                "{}\t{safe_name}\tcreated={}\tlast_used={last}",
                                d.id,
                                d.created_at.to_rfc3339()
                            );
                        }
                    }
                    Ok(())
                }
                DeviceCommand::Revoke { id } => {
                    if crate::RocheServer::revoke_device(&cfg, &id).await? {
                        println!("Revoked device {id}");
                    } else {
                        anyhow::bail!("Device {id} not found");
                    }
                    Ok(())
                }
            },
            Some(Command::Import { file, poll }) => {
                let imported = crate::RocheServer::import_opml_file(&cfg, &file, poll).await?;
                println!("Imported {imported} feeds from {}", file.display());
                Ok(())
            }
            Some(Command::Export { output }) => {
                let xml = crate::RocheServer::export_opml(&cfg).await?;
                if let Some(path) = output {
                    tokio::fs::write(&path, xml).await?;
                    println!("Exported feeds to {}", path.display());
                } else {
                    print!("{xml}");
                }
                Ok(())
            }
            Some(Command::Backup { args, action }) => run_backup_cmd(&cfg, &args, action).await,
            Some(Command::Lists { want, all }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_lists_cmd(&db, want || all, all).await
            }
            Some(Command::Show { target, want }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_show_cmd(&db, &target, want).await
            }
            Some(Command::Diary { year, status, kind, limit }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_diary_cmd(&db, year, status.as_deref(), kind.as_deref(), limit).await
            }
            Some(Command::Consumed { args, rank }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_log_entry_cmd(&db, &args, ConsumptionStatus::Consumed, rank).await
            }
            Some(Command::Started { args }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_log_entry_cmd(&db, &args, ConsumptionStatus::Started, false).await
            }
            Some(Command::Abandoned { args }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_log_entry_cmd(&db, &args, ConsumptionStatus::Abandoned, false).await
            }
            Some(Command::Want { args, queue }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                let catalog = CatalogService::new(&db);
                let input = args.to_upsert_input()?;
                let (want_col, entry) = catalog
                    .want_resolved_or_upsert(&input, args.notes.as_deref(), queue.as_deref())
                    .await?;
                println!(
                    "Added '{}' ({}) to {}",
                    sanitize_terminal_text(&entry.title),
                    entry.kind.as_str(),
                    sanitize_terminal_text(&want_col.title)
                );
                Ok(())
            }
            Some(Command::Unwant { target, query }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                let entry = CatalogService::new(&db).unwant_entry(&target, &query).await?;
                println!(
                    "Removed '{}' ({}) from want queue '{}'",
                    sanitize_terminal_text(&entry.title),
                    entry.kind.as_str(),
                    sanitize_terminal_text(&target)
                );
                Ok(())
            }
            Some(Command::Rank(rank_args)) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_rank_cmd(&db, &rank_args).await
            }
            Some(Command::Draw { action }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_draw_cmd(&db, action.unwrap_or(DrawCommand::Status { collection: None })).await
            }
            Some(Command::Catalog { action }) => match action {
                CatalogCommand::Apply { file, ndjson, dry_run, json } => {
                    let db = crate::RocheServer::connect_db(&cfg).await?;
                    run_catalog_apply_cmd(&db, &file, ndjson, dry_run, json).await
                }
            },
        }
    }
}

async fn run_backup_cmd(
    cfg: &Config,
    args: &BackupRunArgs,
    action: Option<BackupCommand>,
) -> anyhow::Result<()> {
    let policy = crate::backup::RetentionPolicy::from_config(&cfg.backup);
    if action.is_some()
        && (args.output.is_some() || args.no_compress || args.no_prune || args.include_media)
    {
        anyhow::bail!(
            "`--output`, `--no-compress`, `--no-prune`, and `--include-media` only apply when creating a backup (not with subcommands)"
        );
    }
    match action {
        None => run_backup_create(cfg, args).await?,
        Some(BackupCommand::List { dir }) => {
            let target_dir = dir.as_deref().or(args.dir.as_deref()).unwrap_or(&cfg.backup.dir);
            run_backup_list(target_dir, policy).await?;
        }
        Some(BackupCommand::Prune { dir, dry_run }) => {
            let target_dir = dir.as_deref().or(args.dir.as_deref()).unwrap_or(&cfg.backup.dir);
            run_backup_prune(target_dir, policy, dry_run).await?;
        }
        Some(BackupCommand::Verify { file }) => {
            if args.dir.is_some() {
                anyhow::bail!("`--dir` cannot be combined with `roche backup verify`");
            }
            let verified = crate::backup::verify_backup_file(&file).await?;
            let kind = if verified.compressed { "zstd" } else { "sqlite" };
            println!(
                "Verified {} ({kind}, {} archive -> {} db, {} entries)",
                sanitize_terminal_text(&verified.path.display().to_string()),
                format_bytes(verified.archive_bytes),
                format_bytes(verified.database_bytes),
                verified.entry_count
            );
        }
        Some(BackupCommand::Restore { file, force }) => {
            if args.dir.is_some() {
                anyhow::bail!("`--dir` cannot be combined with `roche backup restore`");
            }
            let restored =
                crate::backup::restore_backup_file(&file, &cfg.storage.database_path, force)
                    .await?;
            println!(
                "Restored {} -> {} ({}, {} entries)",
                sanitize_terminal_text(&restored.source_path.display().to_string()),
                sanitize_terminal_text(&restored.target_path.display().to_string()),
                format_bytes(restored.database_bytes),
                restored.entry_count
            );
            if !restored.removed_sidecars.is_empty() {
                println!(
                    "Removed {} stale SQLite sidecar file(s).",
                    restored.removed_sidecars.len()
                );
            }
        }
    }
    Ok(())
}

async fn run_backup_create(cfg: &Config, args: &BackupRunArgs) -> anyhow::Result<()> {
    if args.output.is_some() && args.dir.is_some() {
        anyhow::bail!("`--output` and `--dir` cannot be combined");
    }
    if args.output.is_some() && args.include_media {
        anyhow::bail!(
            "`--include-media` cannot be combined with `--output`; use `--dir <DIR>` so database snapshots and `media/` stay together"
        );
    }

    let mut opts = crate::backup::BackupOptions::from_config(cfg);
    if let Some(ref dir) = args.dir {
        crate::backup::validate_backup_dir(
            dir,
            &cfg.storage.database_path,
            &cfg.storage.media_dir,
        )?;
        opts.backup_dir.clone_from(dir);
    }
    if let Some(ref output) = args.output {
        opts.explicit_output = Some(output.clone());
        opts.prune = false;
        // Never silently mirror media to config's `backup.dir` during an explicit `-o` backup.
        opts.include_media = false;
    }
    opts.compress = resolve_backup_compression(cfg, args);
    if args.no_prune {
        opts.prune = false;
    }
    if args.include_media {
        opts.include_media = true;
    }

    let outcome = crate::backup::create_backup_from_path(&cfg.storage.database_path, &opts).await?;
    let path_text = sanitize_terminal_text(&outcome.path.display().to_string());
    if outcome.compressed {
        println!(
            "Created backup {path_text} ({} compressed from {}, zstd)",
            format_bytes(outcome.final_bytes),
            format_bytes(outcome.uncompressed_bytes)
        );
    } else {
        println!("Created backup {path_text} ({})", format_bytes(outcome.final_bytes));
    }
    if !outcome.pruned.is_empty() {
        println!("Pruned {} old backup(s).", outcome.pruned.len());
    }
    if let Some(media) = outcome.media {
        println!(
            "Mirrored media to {}: {} copied, {} unchanged, {} removed ({} total)",
            sanitize_terminal_text(&media.dest_dir.display().to_string()),
            media.copied_files,
            media.skipped_files,
            media.removed_files,
            format_bytes(media.total_bytes)
        );
    }
    Ok(())
}

async fn run_backup_list(
    target_dir: &std::path::Path,
    policy: crate::backup::RetentionPolicy,
) -> anyhow::Result<()> {
    let entries = crate::backup::list_backups(target_dir).await?;
    if entries.is_empty() {
        println!(
            "No managed backups found in {}.",
            sanitize_terminal_text(&target_dir.display().to_string())
        );
        return Ok(());
    }
    let plan = crate::backup::plan_retention(&entries, policy);
    let tiers_by_name: std::collections::HashMap<&str, &[crate::backup::RetentionTier]> =
        plan.keep.iter().map(|r| (r.entry.file_name.as_str(), r.tiers.as_slice())).collect();
    for entry in &entries {
        let tier_label = tiers_by_name.get(entry.file_name.as_str()).map_or_else(
            || "prunable".to_owned(),
            |tiers| tiers.iter().map(|t| t.as_str()).collect::<Vec<_>>().join("+"),
        );
        println!(
            "{}  {:>10}  [{:<20}]  {}",
            entry.timestamp.format("%Y-%m-%d %H:%M:%SZ"),
            format_bytes(entry.size_bytes),
            tier_label,
            sanitize_terminal_text(&entry.file_name)
        );
    }
    Ok(())
}

async fn run_backup_prune(
    target_dir: &std::path::Path,
    policy: crate::backup::RetentionPolicy,
    dry_run: bool,
) -> anyhow::Result<()> {
    let plan = crate::backup::prune_backups(target_dir, policy, dry_run).await?;
    if plan.prune.is_empty() {
        println!(
            "No backups to prune in {} (keeping {}).",
            sanitize_terminal_text(&target_dir.display().to_string()),
            plan.keep.len()
        );
    } else {
        let verb = if dry_run { "Would prune" } else { "Pruned" };
        for item in &plan.prune {
            println!(
                "{verb} {} ({})",
                sanitize_terminal_text(&item.file_name),
                format_bytes(item.size_bytes)
            );
        }
        println!("Retained {} backup(s).", plan.keep.len());
    }
    Ok(())
}

fn resolve_backup_compression(cfg: &Config, args: &BackupRunArgs) -> bool {
    if args.no_compress {
        return false;
    }
    if let Some(ext) = args.output.as_deref().and_then(|p| p.extension()) {
        if ext.eq_ignore_ascii_case("zst") {
            return true;
        }
        if ext.eq_ignore_ascii_case("db")
            || ext.eq_ignore_ascii_case("sqlite")
            || ext.eq_ignore_ascii_case("sqlite3")
        {
            return false;
        }
    }
    cfg.backup.compress
}

fn format_bytes(bytes: u64) -> String {
    const KIB: u64 = 1024;
    const MIB: u64 = 1024 * 1024;
    const GIB: u64 = 1024 * 1024 * 1024;
    if bytes >= GIB {
        format!("{}.{} GiB", bytes / GIB, ((bytes % GIB) * 10) / GIB)
    } else if bytes >= MIB {
        format!("{}.{} MiB", bytes / MIB, ((bytes % MIB) * 10) / MIB)
    } else if bytes >= KIB {
        format!("{}.{} KiB", bytes / KIB, ((bytes % KIB) * 10) / KIB)
    } else {
        format!("{bytes} B")
    }
}

async fn run_catalog_apply_cmd(
    db: &Db,
    file: &std::path::Path,
    ndjson: bool,
    dry_run_flag: bool,
    json: bool,
) -> anyhow::Result<()> {
    if ndjson {
        let mut on_batch = |batch_num: usize, _: &crate::db::CatalogBatchResult| {
            if !json && batch_num.is_multiple_of(10) {
                eprintln!("Processed {batch_num} catalog batches...");
            }
        };
        let result = if file.as_os_str() == "-" {
            let reader = tokio::io::BufReader::new(tokio::io::stdin());
            db.catalog().apply_batch_ndjson(reader, dry_run_flag, &mut on_batch).await?
        } else {
            let opened = tokio::fs::File::open(file).await?;
            let reader = tokio::io::BufReader::new(opened);
            db.catalog().apply_batch_ndjson(reader, dry_run_flag, &mut on_batch).await?
        };
        if json {
            println!("{}", serde_json::to_string(&result)?);
        } else {
            let suffix = if result.dry_run { " (dry run — rolled back)" } else { "" };
            println!("Applied {} catalog batch(es){suffix}:", result.batches_processed);
            println!("  Collections processed: {}", result.collections_processed);
            println!("  Entries processed:     {}", result.entries_processed);
            println!("  Consumption logs:      {}", result.logs_recorded);
            println!("  Comparisons recorded:  {}", result.comparisons_recorded);
            println!("  Generators updated:    {}", result.generators_updated);
        }
        return Ok(());
    }

    let raw = if file.as_os_str() == "-" {
        use std::io::Read as _;
        let mut buf = String::new();
        std::io::stdin().read_to_string(&mut buf)?;
        buf
    } else {
        tokio::fs::read_to_string(file).await?
    };
    let mut batch: CatalogBatchInput = serde_json::from_str(&raw)?;
    if dry_run_flag {
        batch.dry_run = true;
    }
    let result = CatalogService::new(db).apply_batch(&batch).await?;
    if json {
        println!("{}", serde_json::to_string(&result)?);
    } else {
        let suffix = if result.dry_run { " (dry run — rolled back)" } else { "" };
        println!("Applied catalog batch{suffix}:");
        println!("  Collections processed: {}", result.collections_processed);
        println!("  Entries processed:     {}", result.entries_processed);
        println!("  Consumption logs:      {}", result.logs_recorded);
        println!("  Comparisons recorded:  {}", result.comparisons_recorded);
        println!("  Generators updated:    {}", result.generators_updated);
    }
    Ok(())
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum Command {
    /// Run the HTTP server (default)
    Serve,
    /// Generate a short-lived pairing code on demand
    Pair,
    /// Auto-discover running Roche servers on the local Tailnet
    Discover,
    /// Manage paired devices and tokens
    Devices {
        #[command(subcommand)]
        action: DeviceCommand,
    },
    /// Import feeds from an OPML file into the local database
    Import {
        /// Path to the OPML file to import
        file: PathBuf,
        /// Immediately poll newly imported feeds
        #[arg(long)]
        poll: bool,
    },
    /// Export subscribed feeds as OPML XML
    Export {
        /// Optional output file path (defaults to stdout)
        #[arg(short, long, value_name = "FILE")]
        output: Option<PathBuf>,
    },
    /// Create, list, verify, prune, or restore verified SQLite backups
    Backup {
        #[command(flatten)]
        args: BackupRunArgs,
        #[command(subcommand)]
        action: Option<BackupCommand>,
    },
    /// List ranked collections and want queues
    Lists {
        /// Include want queues
        #[arg(long)]
        want: bool,
        /// Include all system collections
        #[arg(long)]
        all: bool,
    },
    /// Display the inferred ranking for a collection or want queue
    Show {
        /// Collection slug, kind name (e.g. `movies`, `albums`), or UUID
        target: String,
        /// Show the want-queue priority ranking for `target`
        #[arg(long)]
        want: bool,
    },
    /// Display the chronological consumption diary
    Diary {
        /// Filter to a specific calendar year
        #[arg(long)]
        year: Option<i32>,
        /// Filter by status (`consumed`, `started`, `abandoned`)
        #[arg(long)]
        status: Option<String>,
        /// Filter by media kind (`song`, `album`, `book`, `movie`, `all`, ...; hides songs by default)
        #[arg(long)]
        kind: Option<String>,
        /// Maximum entries to display
        #[arg(short = 'n', long, default_value_t = 50)]
        limit: usize,
    },
    /// Log a piece of media as consumed (auto-promoting out of Want queues)
    Consumed {
        #[command(flatten)]
        args: MediaEntryArgs,
        /// Immediately launch a focused interactive ranking session for this item
        #[arg(long)]
        rank: bool,
    },
    /// Log a piece of media as started
    Started {
        #[command(flatten)]
        args: MediaEntryArgs,
    },
    /// Log a piece of media as abandoned
    Abandoned {
        #[command(flatten)]
        args: MediaEntryArgs,
    },
    /// Add an item to a Want queue
    Want {
        #[command(flatten)]
        args: MediaEntryArgs,
        /// Optional custom want-queue slug
        #[arg(long)]
        queue: Option<String>,
    },
    /// Remove an item from a Want queue
    Unwant {
        /// Kind or want-queue slug (e.g. `movies` or `want:movies`)
        target: String,
        /// Item title, UUID, or external identifier
        query: String,
    },
    /// Run an interactive k-way terminal ranking session
    Rank(RankArgs),
    /// Manage random active draws across manual lists (status, enable, finish, abandon)
    Draw {
        #[command(subcommand)]
        action: Option<DrawCommand>,
    },
    /// Apply declarative operations against the unified media catalog
    Catalog {
        #[command(subcommand)]
        action: CatalogCommand,
    },
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum CatalogCommand {
    /// Apply a JSON (or streaming NDJSON) batch of catalog entries, logs, comparisons, and generators
    Apply {
        /// Path to the JSON batch file (or `-` to read from stdin)
        #[arg(default_value = "-")]
        file: PathBuf,
        /// Stream newline-delimited JSON (`CatalogBatchInput` per non-empty line)
        #[arg(long)]
        ndjson: bool,
        /// Validate and simulate inside a rolled-back transaction without modifying disk
        #[arg(long)]
        dry_run: bool,
        /// Emit machine-readable JSON result to stdout
        #[arg(long)]
        json: bool,
    },
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum DeviceCommand {
    /// List all paired devices
    List,
    /// Revoke a paired device token by ID
    Revoke {
        /// Device token ID to revoke
        id: String,
    },
}

#[derive(Args, Debug, Default, Clone, PartialEq, Eq)]
pub struct BackupRunArgs {
    /// Write the backup directly to an explicit file path (skips retention pruning)
    #[arg(short, long, value_name = "FILE")]
    pub output: Option<PathBuf>,
    /// Override the managed backup directory for this run
    #[arg(long, value_name = "DIR")]
    pub dir: Option<PathBuf>,
    /// Write an uncompressed `.db` snapshot instead of Zstandard `.db.zst`
    #[arg(long)]
    pub no_compress: bool,
    /// Skip GFS retention pruning after creating the backup
    #[arg(long)]
    pub no_prune: bool,
    /// Also incrementally mirror `storage.media_dir` into `<backup_dir>/media`
    #[arg(long)]
    pub include_media: bool,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum BackupCommand {
    /// List managed backups and their GFS retention tiers
    List {
        /// Override the managed backup directory to inspect
        #[arg(long, value_name = "DIR")]
        dir: Option<PathBuf>,
    },
    /// Apply GFS retention pruning to managed backups
    Prune {
        /// Override the managed backup directory to prune
        #[arg(long, value_name = "DIR")]
        dir: Option<PathBuf>,
        /// Show which backups would be kept or pruned without deleting any files
        #[arg(long)]
        dry_run: bool,
    },
    /// Verify a `.db.zst` or `.db` backup archive's checksum, SQLite integrity, and foreign keys
    Verify {
        /// Path to the backup file to verify
        file: PathBuf,
    },
    /// Verify and restore a `.db.zst` or `.db` backup into `storage.database_path`
    Restore {
        /// Path to the backup file to restore
        file: PathBuf,
        /// Overwrite an existing database file (stop `roche serve` first!)
        #[arg(long)]
        force: bool,
    },
}

#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct MediaEntryArgs {
    /// Entry kind (`movie`, `album`, `book`, `tv`, `game`, `song`, `article`, `paper`, `video`, ...)
    pub kind: String,
    /// Title, UUID, or external identifier
    pub title: String,
    /// Release year
    #[arg(short, long)]
    pub year: Option<i32>,
    /// Creator(s) (repeatable: `--creator "Frank Sinatra"`)
    #[arg(long = "creator", visible_alias = "by")]
    pub creators: Vec<String>,
    /// Primary web/consumption URL
    #[arg(long)]
    pub url: Option<String>,
    /// External identifiers as `scheme=value` (e.g. `--id spotify=3GmwKB1tgPZgXeRJZSm9WX`)
    #[arg(long = "id", value_name = "SCHEME=VALUE")]
    pub identifiers: Vec<String>,
    /// Optional diary notes
    #[arg(long)]
    pub notes: Option<String>,
}

impl MediaEntryArgs {
    fn to_upsert_input(&self) -> anyhow::Result<UpsertEntryInput> {
        let parsed_kind = EntryKind::parse_flexible(&self.kind)?;
        let mut idents = Vec::with_capacity(self.identifiers.len());
        for raw in &self.identifiers {
            idents.push(CatalogService::parse_cli_identifier(raw)?);
        }
        Ok(UpsertEntryInput {
            id: None,
            kind: Some(parsed_kind),
            title: self.title.clone(),
            creators: self.creators.clone(),
            url: self.url.clone(),
            published_at: None,
            release_year: self.year,
            summary: None,
            thumbnail_url: None,
            metadata: None,
            identifiers: idents,
            parent_collection_id: None,
            parent_position: None,
        })
    }
}

#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct RankArgs {
    /// Target collection slug or kind (e.g. `albums`, `movies`, `sci_fi_canon`)
    pub target: String,
    /// Rank the want-queue priority order for `target`
    #[arg(long)]
    pub want: bool,
    /// Maximum cluster size per round (2..=5)
    #[arg(short = 'k', long = "max-k", default_value_t = 5)]
    pub max_k: usize,
    /// Maximum comparison rounds in this session
    #[arg(short = 'n', long = "max-iters", default_value_t = 50)]
    pub max_iters: usize,
    /// Seed clusters around a specific item (title, UUID, or external ID) until settled
    #[arg(long)]
    pub focus: Option<String>,
    /// Seed clusters from the N most recently consumed items
    #[arg(long)]
    pub recent: Option<usize>,
    /// Restrict universe to items matching creator substring
    #[arg(long)]
    pub by: Option<String>,
    /// Restrict universe to children of a parent collection (title or UUID)
    #[arg(long)]
    pub from: Option<String>,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum DrawCommand {
    /// Display active collection draws (or a specific list's generator status)
    Status {
        /// Optional manual list slug or ID
        collection: Option<String>,
    },
    /// Enable random draws on a manual list (drawing an initial item if none is active)
    Enable {
        /// Manual list slug or ID
        collection: String,
    },
    /// Pause random draws on a manual list (preserving any active draw)
    Disable {
        /// Manual list slug or ID
        collection: String,
    },
    /// Open the active drawn item in the system browser
    Open {
        /// Optional manual list slug or ID (auto-selected if exactly one draw is active)
        collection: Option<String>,
    },
    /// Finish the active drawn item, run focused ranking, and draw the next item
    Finish {
        /// Optional manual list slug or ID (auto-selected if exactly one draw is active)
        collection: Option<String>,
        /// Optional notes
        #[arg(long)]
        notes: Option<String>,
        /// Skip the interactive ranking prompt after finishing
        #[arg(long)]
        no_rank: bool,
    },
    /// Abandon the active drawn item without ranking it and draw the next item
    Abandon {
        /// Optional manual list slug or ID (auto-selected if exactly one draw is active)
        collection: Option<String>,
        /// Optional notes on why it was abandoned
        #[arg(long)]
        notes: Option<String>,
    },
}

async fn run_lists_cmd(db: &Db, include_want: bool, include_system: bool) -> anyhow::Result<()> {
    let collections =
        CatalogService::new(db).list_collections(include_want, include_system).await?;
    if collections.is_empty() {
        println!("No collections found.");
        return Ok(());
    }
    for col in collections {
        let slug = col.slug.as_deref().unwrap_or(col.id.as_str());
        let kind_label = if col.is_dynamic() { "dynamic" } else { "manual" };
        println!(
            "{:<22} {:<24} items={:<4} comparisons={:<4} ({kind_label})",
            sanitize_terminal_text(slug),
            sanitize_terminal_text(&col.title),
            col.member_count,
            col.comparison_count,
        );
    }
    Ok(())
}

async fn run_show_cmd(db: &Db, target: &str, want: bool) -> anyhow::Result<()> {
    let view = CatalogService::new(db).get_ranked_collection(target, want).await?;
    if view.rows.is_empty() {
        println!("{} is empty.", sanitize_terminal_text(&view.collection.title));
        return Ok(());
    }
    for row in &view.rows {
        let tier_prefix = row.tier.map_or_else(String::new, |t| format!("[{t}] "));
        let creators = if row.entry.creators.is_empty() {
            String::new()
        } else {
            format!("{} — ", sanitize_terminal_text(&row.entry.creators.join(", ")))
        };
        let year = row.entry.release_year.map_or_else(String::new, |y| format!(" ({y})"));
        println!(
            "{:3}. {tier_prefix}{creators}{}{year}  score={:+.2} sd={:.2}",
            row.rank,
            sanitize_terminal_text(&row.entry.title),
            row.score.mean,
            row.score.variance.sqrt()
        );
    }
    Ok(())
}

async fn run_diary_cmd(
    db: &Db,
    year: Option<i32>,
    status_raw: Option<&str>,
    kind_raw: Option<&str>,
    limit: usize,
) -> anyhow::Result<()> {
    let status = match status_raw.map(str::trim).filter(|s| !s.is_empty()) {
        Some("consumed") => Some(ConsumptionStatus::Consumed),
        Some("started") => Some(ConsumptionStatus::Started),
        Some("abandoned") => Some(ConsumptionStatus::Abandoned),
        Some(other) => {
            anyhow::bail!("Unknown status '{other}'; expected consumed, started, or abandoned")
        }
        None => None,
    };
    let kind_filter = crate::db::DiaryKindFilter::parse_optional(kind_raw)?;
    let entries =
        CatalogService::new(db).list_diary_with_kind(year, status, kind_filter, limit).await?;
    if entries.is_empty() {
        println!("No diary entries found.");
        return Ok(());
    }
    let mut current_month = String::new();
    for item in entries {
        let month_label = item.ts.format("%B %Y").to_string();
        if month_label != current_month {
            if !current_month.is_empty() {
                println!();
            }
            println!("=== {month_label} ===");
            current_month = month_label;
        }
        let date_str = item.ts.format("%Y-%m-%d").to_string();
        let creators = if item.entry.creators.is_empty() {
            String::new()
        } else {
            format!("{} — ", sanitize_terminal_text(&item.entry.creators.join(", ")))
        };
        let notes_suffix = item
            .notes
            .as_deref()
            .map_or_else(String::new, |n| format!("  \"{}\"", sanitize_terminal_text(n)));
        println!(
            "  {date_str}  [{:<9}]  {:<10} {creators}{}{notes_suffix}",
            item.status.as_str(),
            item.entry.kind.as_str(),
            sanitize_terminal_text(&item.entry.title)
        );
    }
    Ok(())
}

async fn run_log_entry_cmd(
    db: &Db,
    args: &MediaEntryArgs,
    status: ConsumptionStatus,
    rank_after: bool,
) -> anyhow::Result<()> {
    let catalog = CatalogService::new(db);
    let input = args.to_upsert_input()?;
    let logged = catalog
        .log_entry_resolved_or_upsert(None, Some(&input), status, args.notes.as_deref(), None)
        .await?;
    println!(
        "Logged {} '{}' ({})",
        logged.status.as_str(),
        sanitize_terminal_text(&logged.entry.title),
        logged.entry.kind.as_str()
    );

    if rank_after && status == ConsumptionStatus::Consumed {
        if let Some(list_slug) = catalog.rank_target_after_consumption(&logged.entry).await? {
            let focus_args = RankArgs {
                target: list_slug,
                want: false,
                max_k: 5,
                max_iters: 25,
                focus: Some(logged.entry.id.clone()),
                recent: None,
                by: None,
                from: None,
            };
            run_rank_cmd(db, &focus_args).await?;
        } else if logged.entry.kind.default_list_slug().is_some() {
            println!(
                "Ranked #1 in {} (log at least 2 items to compare).",
                logged.entry.kind.display_label()
            );
        }
    }
    Ok(())
}

async fn run_rank_cmd(db: &Db, args: &RankArgs) -> anyhow::Result<()> {
    let catalog = CatalogService::new(db);
    let focus_ids = if let Some(ref f) = args.focus {
        let entry = catalog.resolve_entry(f, None, None).await?;
        vec![entry.id]
    } else {
        Vec::new()
    };
    let parent_id = if let Some(ref p) = args.from {
        let entry = catalog.resolve_entry(p, None, None).await?;
        Some(entry.id)
    } else {
        None
    };

    let mut appended = 0usize;
    let mut force_random_next = false;
    let mut judged_clusters: Vec<std::collections::HashSet<String>> = Vec::new();
    let max_k = args.max_k.clamp(2, 9);

    let mut effective_want = args.want;
    for iteration in 0..args.max_iters {
        let eff_iter = if force_random_next { RANDOM_SEED_EVERY } else { iteration };
        force_random_next = false;
        let filter = ClusterSelectFilter {
            max_k,
            iteration: eff_iter,
            focus_ids: focus_ids.clone(),
            recent_limit: args.recent,
            by_creator: args.by.clone(),
            from_parent_id: parent_id.clone(),
            ..ClusterSelectFilter::default()
        };
        let (summary, cluster, _) = catalog
            .select_next_uncovered_cluster(&args.target, effective_want, &filter, |cand| {
                !cand.is_empty()
                    && judged_clusters.iter().any(|s| cand.iter().all(|c| s.contains(&c.id)))
            })
            .await?;
        if summary.role == CollectionRole::Want {
            effective_want = true;
        }
        if cluster.len() < 2 {
            if iteration == 0 && summary.member_count < 2 {
                anyhow::bail!(
                    "Need at least 2 items in '{}' to rank (found {}).",
                    sanitize_terminal_text(&summary.title),
                    summary.member_count
                );
            }
            break;
        }

        let cluster_ids: std::collections::HashSet<String> =
            cluster.iter().map(|c| c.id.clone()).collect();
        match prompt_cluster_tty(&summary.title, cluster, iteration, appended)? {
            ClusterRankOutcome::Submitted(ordering) => {
                let scope_id = effective_want.then_some(summary.id.as_str());
                catalog.append_comparison(scope_id, &ordering, None).await?;
                judged_clusters.push(cluster_ids);
                appended += 1;
            }
            ClusterRankOutcome::Skip => {
                force_random_next = true;
            }
            ClusterRankOutcome::Quit => break,
        }
    }

    println!("Saved {appended} comparison(s).");
    if let Some(fid) = focus_ids.first() {
        let view = catalog.get_ranked_collection(&args.target, effective_want).await?;
        if let Some(row) = view.rows.iter().find(|r| &r.entry.id == fid) {
            let tier_badge = row.tier.map_or_else(String::new, |t| format!(" [{t} tier]"));
            println!(
                "Settled '{}' at #{}/{} (score {:+.2}){tier_badge}",
                sanitize_terminal_text(&row.entry.title),
                row.rank,
                view.rows.len(),
                row.score.mean
            );
        }
    }
    Ok(())
}

async fn resolve_target_generator_collection(
    catalog: &CatalogService<'_>,
    explicit: Option<&str>,
) -> anyhow::Result<String> {
    if let Some(col) = explicit.map(str::trim).filter(|s| !s.is_empty()) {
        return Ok(col.to_owned());
    }
    let active = catalog.list_collection_generators(true).await?;
    match active.as_slice() {
        [single] => Ok(single.collection_id.clone()),
        [] => anyhow::bail!(
            "No manual lists currently have an enabled draw. Enable one with `roche draw enable <list>`."
        ),
        many => {
            let names: Vec<String> = many
                .iter()
                .map(|g| {
                    sanitize_terminal_text(
                        g.collection_slug.as_deref().unwrap_or(&g.collection_title),
                    )
                })
                .collect();
            anyhow::bail!(
                "Multiple list draws are active ({}); specify which collection to target.",
                names.join(", ")
            )
        }
    }
}

async fn run_draw_cmd(db: &Db, action: DrawCommand) -> anyhow::Result<()> {
    let catalog = CatalogService::new(db);
    match action {
        DrawCommand::Status { collection } => {
            if let Some(col) = collection.as_deref() {
                let state = catalog.get_collection_generator(col).await?;
                print_generator_state(&state);
            } else {
                let states = catalog.list_collection_generators(false).await?;
                if states.is_empty() {
                    println!(
                        "No collection draws configured. Run `roche draw enable <list>` to start one."
                    );
                } else {
                    for (idx, state) in states.iter().enumerate() {
                        if idx > 0 {
                            println!();
                        }
                        print_generator_state(state);
                    }
                }
            }
        }
        DrawCommand::Enable { collection } => {
            let state = catalog.set_collection_generator_enabled(&collection, true).await?;
            print_generator_state(&state);
        }
        DrawCommand::Disable { collection } => {
            let state = catalog.set_collection_generator_enabled(&collection, false).await?;
            print_generator_state(&state);
        }
        DrawCommand::Open { collection } => {
            let target =
                resolve_target_generator_collection(&catalog, collection.as_deref()).await?;
            let state = catalog.get_collection_generator(&target).await?;
            let Some(active) = state.current_entry else {
                anyhow::bail!(
                    "No active entry is currently drawn for '{}'.",
                    sanitize_terminal_text(&state.collection_title)
                );
            };
            let Some(url) = active.primary_open_url() else {
                anyhow::bail!(
                    "No external URL available for '{}'.",
                    sanitize_terminal_text(&active.title)
                );
            };
            open_system_browser(&url)?;
            println!("Opened {}", sanitize_terminal_text(&url));
        }
        DrawCommand::Finish { collection, notes, no_rank } => {
            run_draw_finish_cmd(db, &catalog, collection.as_deref(), notes.as_deref(), no_rank)
                .await?;
        }
        DrawCommand::Abandon { collection, notes } => {
            let target =
                resolve_target_generator_collection(&catalog, collection.as_deref()).await?;
            let (abandoned, next_state) =
                catalog.abandon_active_generator_entry(&target, notes.as_deref()).await?;
            println!(
                "Abandoned '{}' — {}",
                sanitize_terminal_text(&abandoned.title),
                sanitize_terminal_text(&abandoned.creators.join(", "))
            );
            println!();
            print_generator_state(&next_state);
        }
    }
    Ok(())
}

async fn run_draw_finish_cmd(
    db: &Db,
    catalog: &CatalogService<'_>,
    collection: Option<&str>,
    notes: Option<&str>,
    no_rank: bool,
) -> anyhow::Result<()> {
    let target = resolve_target_generator_collection(catalog, collection).await?;
    let (finished, next_state) = catalog.finish_active_generator_entry(&target, notes).await?;
    println!(
        "Finished '{}' — {}",
        sanitize_terminal_text(&finished.title),
        sanitize_terminal_text(&finished.creators.join(", "))
    );
    if !no_rank {
        let rank_target = catalog
            .rank_target_after_generator_finish(&next_state.collection_id, &finished)
            .await?;
        if let Some(list_slug) = rank_target {
            let rank_args = RankArgs {
                target: list_slug,
                want: false,
                max_k: 5,
                max_iters: 20,
                focus: Some(finished.id.clone()),
                recent: None,
                by: None,
                from: None,
            };
            if let Err(err) = run_rank_cmd(db, &rank_args).await {
                eprintln!(
                    "Skipped interactive ranking ({})",
                    sanitize_terminal_text(&err.to_string())
                );
            }
        }
    }
    println!();
    print_generator_state(&next_state);
    Ok(())
}

fn print_generator_state(state: &CollectionGeneratorState) {
    let status_word = if state.enabled { "enabled" } else { "paused" };
    println!(
        "{} Draw ({status_word}) — {}/{} finished, {} abandoned, {} remaining",
        sanitize_terminal_text(&state.collection_title),
        state.consumed_count,
        state.total_pool,
        state.abandoned_count,
        state.remaining_count
    );
    if let Some(entry) = &state.current_entry {
        print_active_draw_card(entry);
    } else if !state.enabled {
        let slug = state.collection_slug.as_deref().unwrap_or(&state.collection_id);
        println!(
            "Run `roche draw enable {}` to draw your next item.",
            sanitize_terminal_text(slug)
        );
    } else {
        println!(
            "All items in '{}' have been completed!",
            sanitize_terminal_text(&state.collection_title)
        );
    }
}

fn print_active_draw_card(entry: &CatalogEntry) {
    let creator_suffix = if entry.creators.is_empty() {
        String::new()
    } else {
        format!(" — {}", sanitize_terminal_text(&entry.creators.join(", ")))
    };
    let year = entry.release_year.map_or_else(String::new, |y| format!(" ({y})"));
    println!("Active draw: {}{creator_suffix}{year}", sanitize_terminal_text(&entry.title));
    if let Some(genres) = entry.metadata.get("genres").and_then(|v| v.as_array()) {
        let joined: Vec<String> =
            genres.iter().filter_map(|v| v.as_str()).map(sanitize_terminal_text).collect();
        if !joined.is_empty() {
            println!("Tags:        {}", joined.join(", "));
        }
    }
    if let Some(open_url) = entry.primary_open_url() {
        println!("Open:        {}", sanitize_terminal_text(&open_url));
    }
    let providers: Vec<String> = entry
        .sorted_external_identifiers()
        .into_iter()
        .map(|id| sanitize_terminal_text(id.label()))
        .filter(|s| !s.is_empty())
        .collect();
    if !providers.is_empty() {
        println!("Sources:     {}", providers.join(" · "));
    }
    if let Some(spotify_uri) = entry.identifier("spotify") {
        println!("Spotify URI: {}", sanitize_terminal_text(spotify_uri));
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_cli_parses_host_and_interface_aliases_at_root_and_subcommand() {
        let root_host = Cli::try_parse_from(["roche", "--host", "0.0.0.0"]).unwrap();
        assert_eq!(root_host.overrides.host.as_deref(), Some("0.0.0.0"));
        assert_eq!(root_host.command, None);
        assert!(root_host.is_server_daemon());

        let serve_interface =
            Cli::try_parse_from(["roche", "serve", "--interface", "100.64.0.1", "-p", "9090"])
                .unwrap();
        assert_eq!(serve_interface.overrides.host.as_deref(), Some("100.64.0.1"));
        assert_eq!(serve_interface.overrides.port, Some(9090));
        assert_eq!(serve_interface.command, Some(Command::Serve));
        assert!(serve_interface.is_server_daemon());

        let bind_short =
            Cli::try_parse_from(["roche", "-H", "127.0.0.1", "--data-dir", "/srv/roche", "serve"])
                .unwrap();
        assert_eq!(bind_short.overrides.host.as_deref(), Some("127.0.0.1"));
        assert_eq!(bind_short.overrides.data_dir, Some(PathBuf::from("/srv/roche")));
        assert_eq!(bind_short.command, Some(Command::Serve));

        let bind_alias = Cli::try_parse_from(["roche", "--bind", "::"]).unwrap();
        assert_eq!(bind_alias.overrides.host.as_deref(), Some("::"));

        let import_cmd = Cli::try_parse_from(["roche", "import", "feeds.opml", "--poll"]).unwrap();
        assert_eq!(
            import_cmd.command,
            Some(Command::Import { file: PathBuf::from("feeds.opml"), poll: true })
        );

        let export_cmd = Cli::try_parse_from(["roche", "export", "-o", "backup.opml"]).unwrap();
        assert_eq!(
            export_cmd.command,
            Some(Command::Export { output: Some(PathBuf::from("backup.opml")) })
        );
    }

    #[test]
    fn test_cli_parses_media_catalog_ranking_and_draw_commands() {
        let consumed = Cli::try_parse_from([
            "roche",
            "consumed",
            "album",
            "In The Wee Small Hours",
            "--creator",
            "Frank Sinatra",
            "--year",
            "1955",
            "--id",
            "spotify=3GmwKB1tgPZgXeRJZSm9WX",
            "--notes",
            "Late-night perfection",
            "--rank",
        ])
        .unwrap();
        assert!(!consumed.is_server_daemon());
        assert!(matches!(consumed.command, Some(Command::Consumed { rank: true, .. })));

        let rank_cmd = Cli::try_parse_from([
            "roche", "rank", "albums", "--max-k", "4", "--recent", "10", "--by", "Sinatra",
        ])
        .unwrap();
        assert!(matches!(
            rank_cmd.command,
            Some(Command::Rank(RankArgs { max_k: 4, recent: Some(10), .. }))
        ));

        let draw_finish = Cli::try_parse_from([
            "roche",
            "draw",
            "finish",
            "canon_queue",
            "--notes",
            "Superb",
            "--no-rank",
        ])
        .unwrap();
        assert_eq!(
            draw_finish.command,
            Some(Command::Draw {
                action: Some(DrawCommand::Finish {
                    collection: Some("canon_queue".to_owned()),
                    notes: Some("Superb".to_owned()),
                    no_rank: true,
                }),
            })
        );

        let catalog_cmd = Cli::try_parse_from([
            "roche",
            "catalog",
            "apply",
            "/tmp/batch.json",
            "--dry-run",
            "--json",
        ])
        .unwrap();
        assert_eq!(
            catalog_cmd.command,
            Some(Command::Catalog {
                action: CatalogCommand::Apply {
                    file: PathBuf::from("/tmp/batch.json"),
                    ndjson: false,
                    dry_run: true,
                    json: true,
                },
            })
        );

        let catalog_ndjson_cmd =
            Cli::try_parse_from(["roche", "catalog", "apply", "--ndjson", "--dry-run"]).unwrap();
        assert_eq!(
            catalog_ndjson_cmd.command,
            Some(Command::Catalog {
                action: CatalogCommand::Apply {
                    file: PathBuf::from("-"),
                    ndjson: true,
                    dry_run: true,
                    json: false,
                },
            })
        );
    }

    #[test]
    fn test_cli_parses_backup_commands() {
        let backup_default = Cli::try_parse_from(["roche", "backup"]).unwrap();
        assert_eq!(
            backup_default.command,
            Some(Command::Backup { args: BackupRunArgs::default(), action: None })
        );

        let backup_custom = Cli::try_parse_from([
            "roche",
            "backup",
            "-o",
            "/tmp/snap.db",
            "--no-compress",
            "--include-media",
        ])
        .unwrap();
        assert_eq!(
            backup_custom.command,
            Some(Command::Backup {
                args: BackupRunArgs {
                    output: Some(PathBuf::from("/tmp/snap.db")),
                    dir: None,
                    no_compress: true,
                    no_prune: false,
                    include_media: true,
                },
                action: None,
            })
        );

        let backup_list = Cli::try_parse_from(["roche", "backup", "list"]).unwrap();
        assert_eq!(
            backup_list.command,
            Some(Command::Backup {
                args: BackupRunArgs::default(),
                action: Some(BackupCommand::List { dir: None }),
            })
        );

        let backup_prune = Cli::try_parse_from(["roche", "backup", "prune", "--dry-run"]).unwrap();
        assert_eq!(
            backup_prune.command,
            Some(Command::Backup {
                args: BackupRunArgs::default(),
                action: Some(BackupCommand::Prune { dir: None, dry_run: true }),
            })
        );

        let backup_verify =
            Cli::try_parse_from(["roche", "backup", "verify", "/tmp/snap.db.zst"]).unwrap();
        assert_eq!(
            backup_verify.command,
            Some(Command::Backup {
                args: BackupRunArgs::default(),
                action: Some(BackupCommand::Verify { file: PathBuf::from("/tmp/snap.db.zst") }),
            })
        );

        let backup_restore =
            Cli::try_parse_from(["roche", "backup", "restore", "/tmp/snap.db.zst", "--force"])
                .unwrap();
        assert_eq!(
            backup_restore.command,
            Some(Command::Backup {
                args: BackupRunArgs::default(),
                action: Some(BackupCommand::Restore {
                    file: PathBuf::from("/tmp/snap.db.zst"),
                    force: true,
                }),
            })
        );
    }

    #[tokio::test]
    async fn test_cli_backup_rejects_conflicting_flags_and_overlapping_dir() {
        let cfg = Config::default();

        // Create-only flag with subcommand must error
        let sub_conflict = BackupRunArgs { include_media: true, ..BackupRunArgs::default() };
        assert!(
            run_backup_cmd(&cfg, &sub_conflict, Some(BackupCommand::List { dir: None }))
                .await
                .is_err()
        );

        // `--output` combined with `--include-media` or `--dir` must error
        let out_media = BackupRunArgs {
            output: Some(PathBuf::from("/tmp/snap.db.zst")),
            include_media: true,
            ..BackupRunArgs::default()
        };
        assert!(run_backup_cmd(&cfg, &out_media, None).await.is_err());

        let out_dir = BackupRunArgs {
            output: Some(PathBuf::from("/tmp/snap.db.zst")),
            dir: Some(PathBuf::from("/tmp/backups")),
            ..BackupRunArgs::default()
        };
        assert!(run_backup_cmd(&cfg, &out_dir, None).await.is_err());

        // `--dir` overlapping `storage.media_dir` must error before touching disk
        let overlap_dir =
            BackupRunArgs { dir: Some(cfg.storage.media_dir.clone()), ..BackupRunArgs::default() };
        assert!(run_backup_cmd(&cfg, &overlap_dir, None).await.is_err());
    }

    #[tokio::test]
    async fn test_cli_consumed_rank_on_first_item_does_not_bail() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let args = MediaEntryArgs {
            kind: "movie".to_owned(),
            title: "Stalker".to_owned(),
            year: Some(1979),
            creators: vec!["Andrei Tarkovsky".to_owned()],
            url: None,
            identifiers: vec!["imdb:tt0079944".to_owned()],
            notes: Some("First logged film".to_owned()),
        };
        // Previously bailed with "Need at least 2 items in 'Movies' to rank (found 1)."
        run_log_entry_cmd(&db, &args, ConsumptionStatus::Consumed, true).await.unwrap();
        let diary = db.catalog().list_diary(None, None, 10).await.unwrap();
        assert_eq!(diary.len(), 1);
        assert_eq!(diary[0].entry.title, "Stalker");
    }
}
