use std::path::{Path, PathBuf};

use anyhow::Result;
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
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
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

impl Config {
    pub fn load(path: Option<&Path>) -> Result<Self> {
        if let Some(explicit) = path {
            let contents = std::fs::read_to_string(explicit)?;
            Self::from_toml_str(&contents)
        } else {
            let default_path = Path::new("config.toml");
            if default_path.exists() {
                let contents = std::fs::read_to_string(default_path)?;
                Self::from_toml_str(&contents)
            } else {
                Ok(Self::default())
            }
        }
    }

    pub fn from_toml_str(contents: &str) -> Result<Self> {
        let mut cfg: Self = toml::from_str(contents)?;
        let default_storage = StorageConfig::default();
        let default_media = MediaConfig::default();
        if cfg.storage.data_dir != default_storage.data_dir {
            if cfg.storage.database_path == default_storage.database_path {
                cfg.storage.database_path = cfg.storage.data_dir.join("roche.db");
            }
            if cfg.storage.media_dir == default_storage.media_dir {
                cfg.storage.media_dir = cfg.storage.data_dir.join("media");
            }
        }
        if cfg.storage.media_dir != default_storage.media_dir
            && cfg.media.download_dir == default_media.download_dir
        {
            cfg.media.download_dir = cfg.storage.media_dir.join("downloads");
        }
        cfg.validate()?;
        Ok(cfg)
    }

    pub fn validate(&self) -> Result<()> {
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
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
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
}
