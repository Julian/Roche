use std::path::PathBuf;

use clap::{Args, Parser, Subcommand};
use figment::{
    Figment, Metadata, Profile, Provider,
    value::{Dict, Map},
};

use crate::config::Config;

#[derive(Parser, Debug, Clone, PartialEq, Eq)]
#[command(name = "roche", version, about = "Roche — News & Learning Tracker")]
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
    pub fn load_config(&self) -> anyhow::Result<Config> {
        Config::load_with_provider(self.config.as_deref(), &self.overrides)
    }

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
                            let last = d
                                .last_used_at
                                .map_or_else(|| "never".to_string(), |ts| ts.to_rfc3339());
                            println!(
                                "{}\t{}\tcreated={}\tlast_used={last}",
                                d.id,
                                d.device_name,
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
        }
    }
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

fn sanitize_terminal_text(input: &str) -> String {
    input.chars().filter(|c| !c.is_control()).collect()
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

        let serve_interface =
            Cli::try_parse_from(["roche", "serve", "--interface", "100.64.0.1", "-p", "9090"])
                .unwrap();
        assert_eq!(serve_interface.overrides.host.as_deref(), Some("100.64.0.1"));
        assert_eq!(serve_interface.overrides.port, Some(9090));
        assert_eq!(serve_interface.command, Some(Command::Serve));

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
}
