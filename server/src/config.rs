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
    pub backup: BackupConfig,
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
        format!("{}:{}", crate::util::url_guard::bracket_ipv6_host(host), self.port)
    }
}

#[must_use]
pub fn normalize_service_name(raw: &str) -> String {
    let trimmed = raw.trim().to_ascii_lowercase();
    if trimmed.is_empty() {
        return String::new();
    }
    if trimmed.starts_with("svc:") { trimmed } else { format!("svc:{trimmed}") }
}

#[must_use]
pub fn is_valid_service_name(svc: &str) -> bool {
    svc.strip_prefix("svc:").is_some_and(is_valid_dns_label)
}

#[must_use]
pub fn is_valid_dns_label(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= 63
        && !label.starts_with('-')
        && !label.starts_with('.')
        && !label.ends_with('-')
        && label.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
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
pub struct BackupConfig {
    /// Enable automatic periodic database backups inside `roche serve`.
    pub enabled: bool,
    /// Directory where managed backup archives are stored.
    pub dir: PathBuf,
    /// Minimum hours between automatic periodic backups.
    pub interval_hours: u64,
    /// Compress backup snapshots with Zstandard (`.db.zst`).
    pub compress: bool,
    /// Zstandard compression level (`1..=19`).
    pub compression_level: i32,
    /// Also mirror `storage.media_dir` into `<backup.dir>/media`.
    pub include_media: bool,
    /// Number of most recent distinct calendar days to retain.
    pub keep_daily: usize,
    /// Number of 7-day weekly retention slots to retain.
    pub keep_weekly: usize,
    /// Number of 30-day monthly retention slots to retain.
    pub keep_monthly: usize,
}

impl Default for BackupConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            dir: PathBuf::from("./data/backups"),
            interval_hours: 24,
            compress: true,
            compression_level: 9,
            include_media: false,
            keep_daily: 3,
            keep_weekly: 1,
            keep_monthly: 1,
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
    pub max_video_size_mb: u64,
}

impl Default for MediaConfig {
    fn default() -> Self {
        Self {
            ytdlp_path: PathBuf::from("/usr/local/bin/yt-dlp"),
            download_dir: PathBuf::from("./data/media/downloads"),
            max_concurrent_downloads: 2,
            max_video_size_mb: 500,
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
    /// Allowlisted Tailscale login emails/handles permitted to sign into the Web UI automatically
    /// via the `Tailscale-User-Login` header on loopback (`127.0.0.1` / `::1`) connections.
    ///
    /// Security boundary: keep empty (`[]`, the default) on multi-user hosts or behind any
    /// reverse proxy other than `tailscale serve` that does not strip `Tailscale-User-Login`,
    /// because any local process able to connect to loopback could otherwise forge that header.
    #[serde(default, deserialize_with = "deserialize_string_or_vec")]
    pub auto_auth_users: Vec<String>,
}

fn deserialize_string_or_vec<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    struct StringOrVecVisitor;

    impl<'de> serde::de::Visitor<'de> for StringOrVecVisitor {
        type Value = Vec<String>;

        fn expecting(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            formatter.write_str("a list of user logins or a comma-separated string")
        }

        fn visit_str<E>(self, value: &str) -> std::result::Result<Self::Value, E>
        where
            E: serde::de::Error,
        {
            let trimmed = value.trim().trim_start_matches('[').trim_end_matches(']');
            Ok(trimmed
                .split(',')
                .map(|part| part.trim().trim_matches('"').trim_matches('\'').to_owned())
                .filter(|s| !s.is_empty())
                .collect())
        }

        fn visit_seq<A>(self, mut seq: A) -> std::result::Result<Self::Value, A::Error>
        where
            A: serde::de::SeqAccess<'de>,
        {
            let mut items = Vec::new();
            while let Some(elem) = seq.next_element::<String>()? {
                items.push(elem);
            }
            Ok(items)
        }
    }

    deserializer.deserialize_any(StringOrVecVisitor)
}

impl Default for TailscaleConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            tailscale_bin: PathBuf::from("tailscale"),
            advertise_service: Some("svc:roche".to_string()),
            auto_auth_users: Vec::new(),
        }
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
        } else if let Some(default_path) = Self::resolve_default_config_path() {
            figment = figment.merge(Toml::file(default_path));
        }

        figment = figment.merge(Self::env_provider()).merge(cli_provider);
        Self::from_figment(&figment)
    }

    /// Prefers user-owned XDG config paths (`$XDG_CONFIG_HOME/roche/config.toml` or
    /// `$HOME/.config/roche/config.toml`) over an implicit `./config.toml` in the working
    /// directory, and refuses symlinked or world-writable `./config.toml` files.
    fn resolve_default_config_path() -> Option<PathBuf> {
        if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
            let trimmed = xdg.trim();
            if !trimmed.is_empty() {
                let candidate = PathBuf::from(trimmed).join("roche").join("config.toml");
                if candidate.is_absolute() && candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
        if let Ok(home) = std::env::var("HOME") {
            let trimmed = home.trim();
            if !trimmed.is_empty() {
                let candidate =
                    PathBuf::from(trimmed).join(".config").join("roche").join("config.toml");
                if candidate.is_absolute() && candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
        let cwd_path = Path::new("config.toml");
        if Self::is_safe_implicit_cwd_config(cwd_path) {
            return Some(cwd_path.to_path_buf());
        }
        None
    }

    fn is_safe_implicit_cwd_config(path: &Path) -> bool {
        let Ok(meta) = std::fs::symlink_metadata(path) else {
            return false;
        };
        if meta.file_type().is_symlink() || !meta.is_file() {
            return false;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if (meta.permissions().mode() & 0o002) != 0 {
                return false;
            }
        }
        true
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
                "backup_enabled" => "backup.enabled".into(),
                "backup_dir" => "backup.dir".into(),
                "backup_interval_hours" => "backup.interval_hours".into(),
                "backup_compress" => "backup.compress".into(),
                "backup_compression_level" => "backup.compression_level".into(),
                "backup_include_media" => "backup.include_media".into(),
                "backup_keep_daily" => "backup.keep_daily".into(),
                "backup_keep_weekly" => "backup.keep_weekly".into(),
                "backup_keep_monthly" => "backup.keep_monthly".into(),
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
                "max_video_size_mb" | "media_max_video_size_mb" => "media.max_video_size_mb".into(),
                "op_log_prune_after" | "sync_op_log_prune_after" => {
                    "sync.op_log_prune_after".into()
                }
                "pairing_code_ttl" | "auth_pairing_code_ttl" => "auth.pairing_code_ttl".into(),
                "token_length" | "auth_token_length" => "auth.token_length".into(),
                "tailscale_enabled" => "tailscale.enabled".into(),
                "tailscale_bin" | "tailscale_tailscale_bin" => "tailscale.tailscale_bin".into(),
                "tailscale_advertise_service" => "tailscale.advertise_service".into(),
                "tailscale_auto_auth_users" => "tailscale.auto_auth_users".into(),
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
        let explicit_backup_dir = overrides.contains("backup.dir");
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
            if !explicit_backup_dir {
                cfg.backup.dir = cfg.storage.data_dir.join("backups");
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
            .map(normalize_service_name);

        let mut normalized_users: Vec<String> = Vec::new();
        for raw_user in &cfg.tailscale.auto_auth_users {
            let clean = raw_user.trim().to_ascii_lowercase();
            if !clean.is_empty() && !normalized_users.contains(&clean) {
                normalized_users.push(clean);
            }
        }
        cfg.tailscale.auto_auth_users = normalized_users;

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
        if self.media.max_video_size_mb == 0 {
            anyhow::bail!("media.max_video_size_mb must be greater than 0");
        }
        let norm_media = crate::util::fs_perms::normalize_lexical(&self.storage.media_dir);
        let norm_downloads = crate::util::fs_perms::normalize_lexical(&self.media.download_dir);
        if !norm_downloads.starts_with(&norm_media)
            || norm_downloads == norm_media
            || norm_downloads == norm_media.join("pdfs")
            || norm_downloads == norm_media.join("thumbnails")
        {
            anyhow::bail!(
                "media.download_dir must be a dedicated subdirectory inside storage.media_dir"
            );
        }
        if self.backup.interval_hours == 0 {
            anyhow::bail!("backup.interval_hours must be greater than 0");
        }
        if !(1..=19).contains(&self.backup.compression_level) {
            anyhow::bail!("backup.compression_level must be between 1 and 19");
        }
        if self.backup.keep_daily == 0
            && self.backup.keep_weekly == 0
            && self.backup.keep_monthly == 0
        {
            anyhow::bail!(
                "at least one of backup.keep_daily, backup.keep_weekly, or backup.keep_monthly must be > 0"
            );
        }
        crate::backup::validate_backup_dir(
            &self.backup.dir,
            &self.storage.database_path,
            &self.storage.media_dir,
        )?;
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
            && !is_valid_service_name(svc)
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

    #[test]
    fn test_tailscale_auto_auth_users_normalizes_and_deduplicates() {
        let toml_str = r#"
[tailscale]
enabled = true
auto_auth_users = [" Alice@Example.com ", "", "bob@github", "alice@example.com"]
"#;
        let cfg = Config::from_toml_str(toml_str).unwrap();
        assert_eq!(
            cfg.tailscale.auto_auth_users,
            vec!["alice@example.com".to_string(), "bob@github".to_string()]
        );

        let csv_env = Figment::new().merge((
            "tailscale.auto_auth_users",
            " Alice@Example.com , bob@github , alice@example.com ",
        ));
        let cfg_csv = Config::from_figment(&csv_env).unwrap();
        assert_eq!(
            cfg_csv.tailscale.auto_auth_users,
            vec!["alice@example.com".to_string(), "bob@github".to_string()]
        );
    }

    #[test]
    fn test_download_dir_cannot_alias_media_dir_or_reserved_subdirs() {
        for bad in
            ["/var/lib/roche/media", "/var/lib/roche/media/pdfs", "/var/lib/roche/media/thumbnails"]
        {
            let fig = Figment::new()
                .merge(("storage.media_dir", "/var/lib/roche/media"))
                .merge(("media.download_dir", bad));
            assert!(
                Config::from_figment(&fig).is_err(),
                "expected validation failure for download_dir={bad}"
            );
        }
    }

    #[test]
    fn test_backup_config_defaults_derivation_and_validation() {
        let defaults = Config::default();
        assert!(defaults.backup.enabled);
        assert_eq!(defaults.backup.dir, Path::new("./data/backups"));
        assert_eq!(defaults.backup.interval_hours, 24);
        assert!(defaults.backup.compress);
        assert_eq!(defaults.backup.compression_level, 9);
        assert!(!defaults.backup.include_media);
        assert_eq!(defaults.backup.keep_daily, 3);
        assert_eq!(defaults.backup.keep_weekly, 1);
        assert_eq!(defaults.backup.keep_monthly, 1);

        let derived = Config::from_toml_str(
            r#"
[storage]
data_dir = "/srv/roche"
"#,
        )
        .unwrap();
        assert_eq!(derived.backup.dir, Path::new("/srv/roche/backups"));

        let explicit = Config::from_toml_str(
            r#"
[storage]
data_dir = "/srv/roche"

[backup]
dir = "/mnt/nas/roche-backups"
interval_hours = 12
compress = false
keep_daily = 7
keep_weekly = 4
keep_monthly = 2
"#,
        )
        .unwrap();
        assert_eq!(explicit.backup.dir, Path::new("/mnt/nas/roche-backups"));
        assert_eq!(explicit.backup.interval_hours, 12);
        assert!(!explicit.backup.compress);
        assert_eq!(explicit.backup.keep_daily, 7);
        assert_eq!(explicit.backup.keep_weekly, 4);
        assert_eq!(explicit.backup.keep_monthly, 2);

        // Zero retention across all tiers must fail validation
        let zero_ret = Figment::new()
            .merge(("backup.keep_daily", 0))
            .merge(("backup.keep_weekly", 0))
            .merge(("backup.keep_monthly", 0));
        assert!(Config::from_figment(&zero_ret).is_err());

        // Invalid compression level or interval
        assert!(Config::from_figment(&Figment::new().merge(("backup.interval_hours", 0))).is_err());
        assert!(
            Config::from_figment(&Figment::new().merge(("backup.compression_level", 0))).is_err()
        );
        assert!(
            Config::from_figment(&Figment::new().merge(("backup.compression_level", 20))).is_err()
        );

        // backup.dir must not be empty, overlap storage.media_dir, or contain storage.data_dir/db
        let overlap = Figment::new()
            .merge(("storage.media_dir", "/srv/roche/media"))
            .merge(("backup.dir", "/srv/roche/media/backups"));
        assert!(Config::from_figment(&overlap).is_err());

        let equals_data_dir = Figment::new()
            .merge(("storage.data_dir", "/srv/roche"))
            .merge(("backup.dir", "/srv/roche"));
        assert!(Config::from_figment(&equals_data_dir).is_err());

        let empty_backup_dir = Figment::new().merge(("backup.dir", ""));
        assert!(Config::from_figment(&empty_backup_dir).is_err());
    }
}
