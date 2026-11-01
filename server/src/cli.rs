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
        match self.command {
            Some(Command::Serve) | None => crate::RocheServer::run(self.config.as_deref()).await,
        }
    }
}

#[derive(Subcommand)]
pub enum Command {
    /// Run the HTTP server (default)
    Serve,
}
