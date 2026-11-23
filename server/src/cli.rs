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
            Some(Command::Lists { want, all }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_lists_cmd(&db, want || all, all).await
            }
            Some(Command::Show { target, want }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_show_cmd(&db, &target, want).await
            }
            Some(Command::Diary { year, status, limit }) => {
                let db = crate::RocheServer::connect_db(&cfg).await?;
                run_diary_cmd(&db, year, status.as_deref(), limit).await
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
                CatalogCommand::Apply { file, dry_run, json } => {
                    let db = crate::RocheServer::connect_db(&cfg).await?;
                    run_catalog_apply_cmd(&db, &file, dry_run, json).await
                }
            },
        }
    }
}

async fn run_catalog_apply_cmd(
    db: &Db,
    file: &std::path::Path,
    dry_run_flag: bool,
    json: bool,
) -> anyhow::Result<()> {
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
    /// Apply a JSON batch of catalog entries, logs, comparisons, and generator updates
    Apply {
        /// Path to the JSON batch file (or `-` to read from stdin)
        #[arg(default_value = "-")]
        file: PathBuf,
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
    let entries = CatalogService::new(db).list_diary(year, status, limit).await?;
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
                    dry_run: true,
                    json: true,
                },
            })
        );
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
