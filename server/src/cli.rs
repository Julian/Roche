use std::path::PathBuf;

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "roche", version, about = "Roche — News & Learning Tracker")]
pub struct Cli {
    /// Path to configuration file
    #[arg(short, long, env = "ROCHE_CONFIG", global = true)]
    pub config: Option<PathBuf>,

    #[command(subcommand)]
    pub command: Option<Command>,
}

impl Cli {
    pub async fn run(self) -> anyhow::Result<()> {
        let config_path = self.config.as_deref();
        match self.command {
            Some(Command::Serve) | None => crate::RocheServer::run(config_path).await,
            Some(Command::Pair) => {
                let code = crate::RocheServer::issue_pairing_code(config_path).await?;
                println!("{code}");
                Ok(())
            }
            Some(Command::Devices { action }) => match action {
                DeviceCommand::List => {
                    let devices = crate::RocheServer::list_devices(config_path).await?;
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
                    if crate::RocheServer::revoke_device(config_path, &id).await? {
                        println!("Revoked device {id}");
                    } else {
                        anyhow::bail!("Device {id} not found");
                    }
                    Ok(())
                }
            },
        }
    }
}

#[derive(Subcommand)]
pub enum Command {
    /// Run the HTTP server (default)
    Serve,
    /// Generate a short-lived pairing code on demand
    Pair,
    /// Manage paired devices and tokens
    Devices {
        #[command(subcommand)]
        action: DeviceCommand,
    },
}

#[derive(Subcommand)]
pub enum DeviceCommand {
    /// List all paired devices
    List,
    /// Revoke a paired device token by ID
    Revoke {
        /// Device token ID to revoke
        id: String,
    },
}
