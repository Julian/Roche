use std::path::{Path, PathBuf};

use anyhow::Result;
use figment::{
    Figment, Provider,
    providers::{Env, Format, Serialized, Toml},
};
use serde::{Deserialize, Serialize};

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub server: ServerConfig,
    pub storage: StorageConfig,
    pub feeds: FeedsConfig,
    pub media: MediaConfig,
    pub sync: SyncConfig,
    pub auth: AuthConfig,
    pub tailscale: TailscaleConfig,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
}

impl ServerConfig {
    #[must_use]
    pub fn listen_addr(&self) -> String {
        let host = self.host.trim();
        if host.contains(':') && !host.starts_with('[') {
            format!("[{host}]:{}", self.port)
        } else {
            format!("{host}:{}", self.port)
        }
    }
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self { host: "127.0.0.1".to_string(), port: 8080 }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct StorageConfig {
    pub data_dir: PathBuf,
    pub database_path: PathBuf,
    pub media_dir: PathBuf,
}

impl Default for StorageConfig {
    fn default() -> Self {
        Self {
            data_dir: PathBuf::from("./data"),
            database_path: PathBuf::from("./data/roche.db"),
            media_dir: PathBuf::from("./data/media"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
#[allow(clippy::struct_field_names)]
pub struct FeedsConfig {
    pub default_poll_interval: i64,
    pub max_poll_interval: i64,
    pub min_poll_interval: i64,
}

impl Default for FeedsConfig {
    fn default() -> Self {
        Self { default_poll_interval: 30, max_poll_interval: 1440, min_poll_interval: 5 }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct MediaConfig {
    pub ytdlp_path: PathBuf,
    pub download_dir: PathBuf,
    pub max_concurrent_downloads: usize,
}

impl Default for MediaConfig {
    fn default() -> Self {
        Self {
            ytdlp_path: PathBuf::from("/usr/local/bin/yt-dlp"),
            download_dir: PathBuf::from("./data/media/downloads"),
            max_concurrent_downloads: 2,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SyncConfig {
    pub op_log_prune_after: i64,
}

impl Default for SyncConfig {
    fn default() -> Self {
        Self { op_log_prune_after: 30 }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AuthConfig {
    pub pairing_code_ttl: u64,
    pub token_length: usize,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self { pairing_code_ttl: 300, token_length: 32 }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct TailscaleConfig {
    pub enabled: bool,
    pub tailscale_bin: PathBuf,
    pub advertise_service: Option<String>,
}

impl Default for TailscaleConfig {
    fn default() -> Self {
        Self { enabled: true, tailscale_bin: PathBuf::from("tailscale"), advertise_service: None }
    }
}

impl Config {
    pub fn load(path: Option<&Path>) -> Result<Self> {
        Self::load_with_provider(path, Figment::new())
    }

    pub fn load_with_provider<P: Provider>(path: Option<&Path>, cli_provider: P) -> Result<Self> {
        let env_config_path = std::env::var("ROCHE_CONFIG")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .map(PathBuf::from);
        let effective_path = path.or(env_config_path.as_deref());

        let mut figment = Figment::new();
        if let Some(explicit) = effective_path {
            if !explicit.exists() {
                anyhow::bail!("configuration file not found: {}", explicit.display());
            }
            figment = figment.merge(Toml::file(explicit));
        } else {
            let default_path = Path::new("config.toml");
            if default_path.exists() {
                figment = figment.merge(Toml::file(default_path));
            }
        }

        figment = figment.merge(Self::env_provider()).merge(cli_provider);
        Self::from_figment(&figment)
    }

    #[must_use]
    pub fn env_provider() -> Env {
        Env::prefixed("ROCHE_").ignore(&["CONFIG"]).map(|key| {
            match key.as_str().to_ascii_lowercase().as_str() {
                "host" | "interface" | "server_host" => "server.host".into(),
                "port" | "server_port" => "server.port".into(),
                "data_dir" | "storage_data_dir" => "storage.data_dir".into(),
                "database_path" | "storage_database_path" => "storage.database_path".into(),
                "media_dir" | "storage_media_dir" => "storage.media_dir".into(),
                "default_poll_interval" | "feeds_default_poll_interval" => {
                    "feeds.default_poll_interval".into()
                }
                "max_poll_interval" | "feeds_max_poll_interval" => "feeds.max_poll_interval".into(),
                "min_poll_interval" | "feeds_min_poll_interval" => "feeds.min_poll_interval".into(),
                "ytdlp_path" | "media_ytdlp_path" => "media.ytdlp_path".into(),
                "download_dir" | "media_download_dir" => "media.download_dir".into(),
                "max_concurrent_downloads" | "media_max_concurrent_downloads" => {
                    "media.max_concurrent_downloads".into()
                }
                "op_log_prune_after" | "sync_op_log_prune_after" => {
                    "sync.op_log_prune_after".into()
                }
                "pairing_code_ttl" | "auth_pairing_code_ttl" => "auth.pairing_code_ttl".into(),
                "token_length" | "auth_token_length" => "auth.token_length".into(),
                "tailscale_enabled" => "tailscale.enabled".into(),
                "tailscale_bin" => "tailscale.tailscale_bin".into(),
                "tailscale_advertise_service" => "tailscale.advertise_service".into(),
                other => other.replace("__", ".").into(),
            }
        })
    }

    pub fn from_toml_str(contents: &str) -> Result<Self> {
        let figment = Figment::new().merge(Toml::string(contents));
        Self::from_figment(&figment)
    }

    pub fn from_figment(overrides: &Figment) -> Result<Self> {
        let explicit_data_dir = overrides.contains("storage.data_dir");
        let explicit_db_path = overrides.contains("storage.database_path");
        let explicit_media_dir = overrides.contains("storage.media_dir");
        let explicit_download_dir = overrides.contains("media.download_dir");

        let mut cfg: Self =
            Figment::from(Serialized::defaults(Self::default())).merge(overrides).extract()?;

        if explicit_data_dir {
            if !explicit_db_path {
                cfg.storage.database_path = cfg.storage.data_dir.join("roche.db");
            }
            if !explicit_media_dir {
                cfg.storage.media_dir = cfg.storage.data_dir.join("media");
            }
        }
        if (explicit_data_dir || explicit_media_dir) && !explicit_download_dir {
            cfg.media.download_dir = cfg.storage.media_dir.join("downloads");
        }

        cfg.tailscale.advertise_service = cfg
            .tailscale
            .advertise_service
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(crate::tailscale::normalize_service_name);

        cfg.validate()?;
        Ok(cfg)
    }

    pub fn validate(&self) -> Result<()> {
        if self.server.host.trim().is_empty() {
            anyhow::bail!("server.host must not be empty");
        }
        if self.feeds.min_poll_interval <= 0 {
            anyhow::bail!("feeds.min_poll_interval must be greater than 0");
        }
        if self.feeds.max_poll_interval < self.feeds.min_poll_interval {
            anyhow::bail!("feeds.max_poll_interval must be >= feeds.min_poll_interval");
        }
        if !(self.feeds.min_poll_interval..=self.feeds.max_poll_interval)
            .contains(&self.feeds.default_poll_interval)
        {
            anyhow::bail!(
                "feeds.default_poll_interval must be between min_poll_interval and max_poll_interval"
            );
        }
        if self.media.max_concurrent_downloads == 0 {
            anyhow::bail!("media.max_concurrent_downloads must be greater than 0");
        }
        let norm_media = crate::media::MediaStore::normalize_lexical(&self.storage.media_dir);
        let norm_downloads = crate::media::MediaStore::normalize_lexical(&self.media.download_dir);
        if !norm_downloads.starts_with(&norm_media) {
            anyhow::bail!("media.download_dir must be inside storage.media_dir");
        }
        if self.sync.op_log_prune_after <= 0 {
            anyhow::bail!("sync.op_log_prune_after must be greater than 0");
        }
        if self.auth.pairing_code_ttl == 0 {
            anyhow::bail!("auth.pairing_code_ttl must be greater than 0");
        }
        if self.auth.token_length < crate::auth::MIN_TOKEN_BYTES {
            anyhow::bail!("auth.token_length must be at least {}", crate::auth::MIN_TOKEN_BYTES);
        }
        if let Some(svc) = &self.tailscale.advertise_service
            && !crate::tailscale::is_valid_service_name(svc)
        {
            anyhow::bail!(
                "tailscale.advertise_service must be a valid service name (e.g. 'svc:roche')"
            );
        }
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use clap::Parser;

    use super::*;

    #[test]
    fn test_parse_documented_config_toml() {
        let toml_str = r#"
[server]
host = "0.0.0.0"
port = 8080

[storage]
data_dir = "/var/lib/roche"
database_path = "/var/lib/roche/roche.db"
media_dir = "/var/lib/roche/media"

[feeds]
default_poll_interval = 30
max_poll_interval = 1440
min_poll_interval = 5

[media]
ytdlp_path = "/usr/local/bin/yt-dlp"
download_dir = "/var/lib/roche/media/downloads"
max_concurrent_downloads = 2

[sync]
op_log_prune_after = 30

[auth]
pairing_code_ttl = 300
token_length = 32
"#;
        let cfg = Config::from_toml_str(toml_str).unwrap();
        assert_eq!(cfg.server.port, 8080);
        assert_eq!(cfg.storage.database_path, Path::new("/var/lib/roche/roche.db"));
        assert_eq!(cfg.feeds.max_poll_interval, 1440);
        assert_eq!(cfg.media.ytdlp_path, Path::new("/usr/local/bin/yt-dlp"));
        assert_eq!(cfg.media.download_dir, Path::new("/var/lib/roche/media/downloads"));
        assert_eq!(cfg.media.max_concurrent_downloads, 2);
        assert_eq!(cfg.sync.op_log_prune_after, 30);
        assert_eq!(cfg.auth.token_length, 32);
    }

    #[test]
    fn test_parse_partial_config_uses_defaults() {
        let partial = r#"
[server]
port = 9090

[storage]
data_dir = "/srv/roche"
"#;
        let cfg = Config::from_toml_str(partial).unwrap();
        assert_eq!(cfg.server.port, 9090);
        assert_eq!(cfg.server.host, "127.0.0.1");
        assert_eq!(cfg.storage.database_path, Path::new("/srv/roche/roche.db"));
        assert_eq!(cfg.storage.media_dir, Path::new("/srv/roche/media"));
        assert_eq!(cfg.media.download_dir, Path::new("/srv/roche/media/downloads"));
    }

    #[test]
    fn test_override_precedence_cli_over_env_over_toml_over_defaults() {
        let toml_layer = Toml::string(
            r#"
[server]
host = "192.168.1.10"
port = 7000

[storage]
data_dir = "/toml/roche"

[feeds]
default_poll_interval = 45
"#,
        );

        let env_layer = Figment::new()
            .merge(("server.host", "100.64.0.5"))
            .merge(("server.port", 8000))
            .merge(("media.max_concurrent_downloads", 4));

        let cli = crate::cli::Cli::try_parse_from(["roche", "--host", "0.0.0.0", "serve"]).unwrap();

        let figment = Figment::new().merge(toml_layer).merge(env_layer).merge(&cli.overrides);
        let cfg = Config::from_figment(&figment).unwrap();

        // CLI overrides env and TOML
        assert_eq!(cfg.server.host, "0.0.0.0");
        // Env overrides TOML when CLI is unset
        assert_eq!(cfg.server.port, 8000);
        assert_eq!(cfg.media.max_concurrent_downloads, 4);
        // TOML overrides default when neither CLI nor env is set
        assert_eq!(cfg.feeds.default_poll_interval, 45);
        assert_eq!(cfg.storage.data_dir, Path::new("/toml/roche"));
        assert_eq!(cfg.storage.database_path, Path::new("/toml/roche/roche.db"));
        // Default preserved when unset in all layers
        assert_eq!(cfg.sync.op_log_prune_after, 30);
    }

    #[test]
    fn test_derived_paths_update_when_data_dir_overridden_by_env_or_cli() {
        let base_figment = Figment::new()
            .merge(Toml::string(
                r#"
[server]
host = "127.0.0.1"
"#,
            ))
            .merge(("storage.data_dir", "/env/roche"));

        let cfg_from_env = Config::from_figment(&base_figment).unwrap();
        assert_eq!(cfg_from_env.storage.data_dir, Path::new("/env/roche"));
        assert_eq!(cfg_from_env.storage.database_path, Path::new("/env/roche/roche.db"));
        assert_eq!(cfg_from_env.storage.media_dir, Path::new("/env/roche/media"));
        assert_eq!(cfg_from_env.media.download_dir, Path::new("/env/roche/media/downloads"));

        let cli = crate::cli::Cli::try_parse_from(["roche", "--data-dir", "/cli/roche"]).unwrap();

        let cfg_from_cli = Config::from_figment(&base_figment.merge(&cli.overrides)).unwrap();
        assert_eq!(cfg_from_cli.storage.data_dir, Path::new("/cli/roche"));
        assert_eq!(cfg_from_cli.storage.database_path, Path::new("/cli/roche/roche.db"));
        assert_eq!(cfg_from_cli.storage.media_dir, Path::new("/cli/roche/media"));
        assert_eq!(cfg_from_cli.media.download_dir, Path::new("/cli/roche/media/downloads"));
    }

    #[test]
    fn test_empty_host_validation() {
        let empty_host = Figment::new().merge(("server.host", "   "));
        assert!(Config::from_figment(&empty_host).is_err());
    }

    #[test]
    fn test_server_config_listen_addr_brackets_ipv6() {
        let v4 = ServerConfig { host: "0.0.0.0".to_string(), port: 8080 };
        assert_eq!(v4.listen_addr(), "0.0.0.0:8080");

        let v6 = ServerConfig { host: "::".to_string(), port: 8080 };
        assert_eq!(v6.listen_addr(), "[::]:8080");

        let bracketed_v6 = ServerConfig { host: "[::1]".to_string(), port: 9090 };
        assert_eq!(bracketed_v6.listen_addr(), "[::1]:9090");
    }
}
