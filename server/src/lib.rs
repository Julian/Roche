mod api;
mod auth;
pub mod cli;
pub mod config;
mod content;
pub mod db;
pub mod domain;
mod error;
mod feeds;
mod media;
pub mod ranking;
mod scheduler;
mod service;
pub mod tailscale;
mod tui_rank;
mod util;
mod web;
mod workers;

pub struct RocheServer;

impl RocheServer {
    pub async fn run(cfg: config::Config) -> anyhow::Result<()> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        let fetcher = scheduler::HttpFeedFetcher::new()?;
        let (listen_cfg, ts_identity) = Self::prepare_tailscale(&cfg).await?;
        let bootstrap_canonical_url =
            ts_identity.as_ref().and_then(tailscale::NodeIdentity::canonical_url);

        Self::prepare_startup_media(&db, &cfg).await;

        let roche_service = service::RocheService::new(db.clone(), fetcher, cfg.clone())
            .with_node_identity(ts_identity);

        if cfg.tailscale.enabled && !cfg.tailscale.auto_auth_users.is_empty() {
            tracing::info!(
                allowed_users = cfg.tailscale.auto_auth_users.len(),
                "tailscale.auto_auth_users enabled; ensure this is a single-user host fronted exclusively by `tailscale serve`"
            );
        }

        let addr = listen_cfg.listen_addr();
        let listener = tokio::net::TcpListener::bind(&addr).await?;

        Self::maybe_issue_bootstrap_code(
            &roche_service,
            bootstrap_canonical_url.as_deref(),
            cfg.auth.pairing_code_ttl,
        )
        .await;

        let (shutdown_tx, shutdown_rx) = tokio::sync::watch::channel(false);

        let poll_scheduler = roche_service.poll_scheduler();
        let sched_shutdown = shutdown_rx.clone();
        let scheduler_handle = tokio::spawn(async move {
            if let Err(e) = poll_scheduler.run_until_shutdown(sched_shutdown).await {
                tracing::error!(error = %e, "PollScheduler terminated with error");
            }
        });

        let worker_db = db.clone();
        let prune_after = cfg.sync.op_log_prune_after;
        let media_dir = cfg.storage.media_dir.clone();
        let download_dir = cfg.media.download_dir.clone();
        let worker_shutdown = shutdown_rx.clone();
        let worker_handle = tokio::spawn(async move {
            let worker_mgr = workers::WorkerManager::new(worker_db, prune_after, media_dir)
                .with_download_dir(download_dir);
            if let Err(e) = worker_mgr.run_all_until_shutdown(worker_shutdown).await {
                tracing::error!(error = %e, "WorkerManager terminated with error");
            }
        });

        let app = api::ApiRoutes::router_with_service(roche_service);

        tracing::info!(addr = %addr, "Roche server listening");
        let signal_tx = shutdown_tx.clone();
        let serve_res = axum::serve(
            listener,
            app.into_make_service_with_connect_info::<std::net::SocketAddr>(),
        )
        .with_graceful_shutdown(async move {
            Self::shutdown_signal().await;
            let _ = signal_tx.send(true);
        })
        .await;

        let _ = shutdown_tx.send(true);
        let _ = tokio::time::timeout(std::time::Duration::from_secs(15), async {
            let _ = scheduler_handle.await;
            let _ = worker_handle.await;
        })
        .await;
        serve_res?;
        Ok(())
    }

    async fn prepare_tailscale(
        cfg: &config::Config,
    ) -> anyhow::Result<(config::ServerConfig, Option<tailscale::NodeIdentity>)> {
        let mut ts_identity = tailscale::inspect_local_node(&cfg.tailscale).await;
        let listen_host = if cfg.server.host.trim().eq_ignore_ascii_case("tailscale") {
            let ts_ip = ts_identity.as_ref().and_then(|id| id.ipv4.as_deref()).ok_or_else(|| {
                anyhow::anyhow!(
                    "`--host tailscale` requested, but no active Tailscale IPv4 address was found"
                )
            })?;
            ts_ip.to_string()
        } else {
            cfg.server.host.clone()
        };
        let listen_cfg = config::ServerConfig { host: listen_host, port: cfg.server.port };

        if ts_identity.is_some() {
            let exposure =
                tailscale::expose_over_tailscale(&cfg.tailscale, &listen_cfg.host, cfg.server.port)
                    .await;
            if let Some(identity) = &mut ts_identity {
                identity.service_name = exposure.service_name;
                identity.node_https_port = exposure.node_https_port;
                let urls = identity.candidate_urls();
                if !urls.is_empty() {
                    tracing::info!(tailscale_https_urls = ?urls, "Exposed Roche peer over Tailscale HTTPS");
                }
            }
        }
        Ok((listen_cfg, ts_identity))
    }

    async fn prepare_startup_media(db: &db::Db, cfg: &config::Config) {
        let startup_worker = workers::WorkerManager::new(
            db.clone(),
            cfg.sync.op_log_prune_after,
            cfg.storage.media_dir.clone(),
        )
        .with_download_dir(cfg.media.download_dir.clone());
        if let Err(e) = startup_worker.cleanup_startup_partials().await {
            tracing::warn!(error = %e, "Startup partial media cleanup failed");
        }
        let ytdlp_probe = media::ytdlp::YtDlpWorker::new(
            cfg.media.clone(),
            media::MediaStore::new(&cfg.storage.media_dir),
        );
        match ytdlp_probe.check_version().await {
            Ok(ver) => tracing::info!(ytdlp_version = %ver, "Detected yt-dlp binary"),
            Err(e) => tracing::debug!(error = %e, "yt-dlp binary unavailable at startup"),
        }
    }

    async fn maybe_issue_bootstrap_code(
        roche_service: &service::RocheService,
        canonical_url: Option<&str>,
        pairing_code_ttl: u64,
    ) {
        let stderr_is_tty = std::io::IsTerminal::is_terminal(&std::io::stderr());
        Self::maybe_issue_bootstrap_code_with_tty(
            roche_service,
            canonical_url,
            pairing_code_ttl,
            stderr_is_tty,
        )
        .await;
    }

    async fn maybe_issue_bootstrap_code_with_tty(
        roche_service: &service::RocheService,
        canonical_url: Option<&str>,
        pairing_code_ttl: u64,
        stderr_is_tty: bool,
    ) {
        match roche_service.auth().count_devices().await {
            Ok(0) if !stderr_is_tty => {
                tracing::info!(
                    "No paired devices found; skipping bootstrap pairing code on non-interactive stderr (run `roche pair` to mint a code)"
                );
            }
            Ok(0) => match roche_service.auth().issue_pairing_code().await {
                Ok(code) => {
                    let ttl = format_pairing_ttl(pairing_code_ttl);
                    if let Some(url) = canonical_url {
                        eprintln!("Bootstrap pairing bundle (valid {ttl}): {url}#{code}");
                    } else {
                        eprintln!("Bootstrap pairing code (valid {ttl}): {code}");
                    }
                    tracing::info!(
                        ttl = %ttl,
                        "No paired devices found; generated bootstrap pairing code (valid {ttl}; run `roche pair` to mint a new code)"
                    );
                }
                Err(e) => {
                    tracing::error!(error = %e, "Failed to generate bootstrap device pairing code");
                }
            },
            Ok(_) => {
                tracing::info!(
                    "Paired devices exist; run `roche pair` to generate a new pairing code"
                );
            }
            Err(e) => {
                tracing::error!(
                    error = %e,
                    "Failed to count paired devices; skipping bootstrap pairing code generation"
                );
            }
        }
    }

    async fn shutdown_signal() {
        let ctrl_c = async {
            if let Err(e) = tokio::signal::ctrl_c().await {
                tracing::error!(error = %e, "Failed to listen for Ctrl+C signal");
            }
        };

        #[cfg(unix)]
        let terminate = async {
            match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
                Ok(mut sig) => {
                    sig.recv().await;
                }
                Err(e) => {
                    tracing::error!(error = %e, "Failed to listen for SIGTERM signal");
                    std::future::pending::<()>().await;
                }
            }
        };

        #[cfg(not(unix))]
        let terminate = std::future::pending::<()>();

        tokio::select! {
            () = ctrl_c => {}
            () = terminate => {}
        }

        tracing::info!("Shutdown signal received, stopping server");
    }

    pub(crate) async fn connect_db(cfg: &config::Config) -> anyhow::Result<db::Db> {
        db::Db::connect(&cfg.storage.database_path).await
    }

    async fn connect_service(cfg: &config::Config) -> anyhow::Result<service::RocheService> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        let fetcher = scheduler::HttpFeedFetcher::new()?;
        Ok(service::RocheService::new(db, fetcher, cfg.clone()))
    }

    pub async fn issue_pairing_code(cfg: &config::Config) -> anyhow::Result<String> {
        let service = Self::connect_service(cfg).await?;
        Ok(service.auth().issue_pairing_code().await?)
    }

    pub async fn list_devices(cfg: &config::Config) -> anyhow::Result<Vec<db::DeviceToken>> {
        let service = Self::connect_service(cfg).await?;
        Ok(service.auth().list_devices().await?)
    }

    pub async fn revoke_device(cfg: &config::Config, device_id: &str) -> anyhow::Result<bool> {
        let service = Self::connect_service(cfg).await?;
        match service.auth().revoke_device(device_id).await {
            Ok(()) => Ok(true),
            Err(service::AppError::NotFound(_)) => Ok(false),
            Err(other) => Err(other.into()),
        }
    }

    pub async fn import_opml_file(
        cfg: &config::Config,
        path: &std::path::Path,
        poll: bool,
    ) -> anyhow::Result<usize> {
        let bytes = tokio::fs::read(path).await?;
        let opml_str = content::SafeHttpClient::decode_xml_or_text_bytes(&bytes);
        let service = Self::connect_service(cfg).await?;
        let imported = service.feeds().import_opml(opml_str).await?;
        let count = imported.len();
        if poll && !imported.is_empty() {
            service.feeds().poll_feeds_concurrently(imported).await;
        }
        Ok(count)
    }

    pub async fn export_opml(cfg: &config::Config) -> anyhow::Result<String> {
        let service = Self::connect_service(cfg).await?;
        Ok(service.feeds().export_opml().await?)
    }

    pub async fn discover_servers(
        cfg: &config::Config,
    ) -> anyhow::Result<Vec<tailscale::DiscoveredServer>> {
        Ok(tailscale::discover_on_tailnet(&cfg.tailscale, cfg.server.port).await)
    }

    pub async fn inspect_tailscale_canonical_url(cfg: &config::Config) -> Option<String> {
        tailscale::resolve_live_canonical_url(&cfg.tailscale, &cfg.server.host, cfg.server.port)
            .await
    }
}

fn format_pairing_ttl(seconds: u64) -> String {
    if seconds >= 3600 && seconds.is_multiple_of(3600) {
        format!("{}h", seconds / 3600)
    } else if seconds >= 60 && seconds.is_multiple_of(60) {
        format!("{}m", seconds / 60)
    } else {
        format!("{seconds}s")
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    struct NoopFetcher;

    impl scheduler::FeedFetcher for NoopFetcher {
        fn fetch_feed(
            &self,
            _url: &url::Url,
        ) -> impl std::future::Future<Output = anyhow::Result<String>> {
            std::future::ready(Ok(String::new()))
        }
    }

    #[test]
    fn test_format_pairing_ttl() {
        assert_eq!(format_pairing_ttl(45), "45s");
        assert_eq!(format_pairing_ttl(60), "1m");
        assert_eq!(format_pairing_ttl(90), "90s");
        assert_eq!(format_pairing_ttl(300), "5m");
        assert_eq!(format_pairing_ttl(3600), "1h");
        assert_eq!(format_pairing_ttl(7200), "2h");
    }

    #[tokio::test]
    async fn test_maybe_issue_bootstrap_code_gates_on_tty() {
        let db = db::Db::connect("sqlite::memory:").await.unwrap();
        let mut cfg = config::Config::default();
        cfg.auth.pairing_code_ttl = 120;
        let ttl = cfg.auth.pairing_code_ttl;
        let svc = service::RocheService::new(db.clone(), NoopFetcher, cfg);

        RocheServer::maybe_issue_bootstrap_code_with_tty(
            &svc,
            Some("https://roche.tail9876.ts.net"),
            ttl,
            false,
        )
        .await;
        assert!(
            db.auth().active_pairing_code_hash().await.unwrap().is_none(),
            "non-TTY startup must not mint an unprintable pairing code"
        );

        RocheServer::maybe_issue_bootstrap_code_with_tty(
            &svc,
            Some("https://roche.tail9876.ts.net"),
            ttl,
            true,
        )
        .await;
        assert!(
            db.auth().active_pairing_code_hash().await.unwrap().is_some(),
            "TTY startup with zero devices should mint a bootstrap pairing code"
        );
    }

    #[tokio::test]
    async fn test_run_binds_listener_before_spawning_background_workers() {
        let occupied = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let occupied_port = occupied.local_addr().unwrap().port();

        let temp_dir =
            std::env::temp_dir().join(format!("roche-bind-order-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&temp_dir).await.unwrap();
        let db_path = temp_dir.join("roche.db");
        let media_dir = temp_dir.join("media");

        let db = db::Db::connect(&db_path).await.unwrap();
        sqlx::query(
            "INSERT INTO activity_log (entity_type, entity_id, action, timestamp)
             VALUES ('item', 'pre-bind-stale', 'archive', strftime('%Y-%m-%dT%H:%M:%SZ', 'now', '-60 days'))",
        )
        .execute(db.pool())
        .await
        .unwrap();
        drop(db);

        let mut cfg = config::Config::default();
        cfg.server.host = "127.0.0.1".to_string();
        cfg.server.port = occupied_port;
        cfg.storage.data_dir = temp_dir.clone();
        cfg.storage.database_path = db_path.clone();
        cfg.media.download_dir = media_dir.join("downloads");
        cfg.storage.media_dir = media_dir;
        cfg.media.ytdlp_path = temp_dir.join("nonexistent-yt-dlp");
        cfg.tailscale.enabled = false;

        let run_res = RocheServer::run(cfg).await;
        assert!(run_res.is_err(), "expected TcpListener::bind failure on occupied port");
        drop(occupied);

        // Give any mistakenly spawned task a brief moment to run if it had been started.
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;

        let check_db = db::Db::connect(&db_path).await.unwrap();
        let stale_count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM activity_log WHERE entity_id = 'pre-bind-stale'",
        )
        .fetch_one(check_db.pool())
        .await
        .unwrap();
        assert_eq!(stale_count, 1, "worker must not prune activity_log when bind fails");
        assert!(check_db.auth().active_pairing_code_hash().await.unwrap().is_none());

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn test_prepare_tailscale_clears_node_https_port_when_serve_fails() {
        use std::os::unix::fs::PermissionsExt;

        let temp_dir = std::env::temp_dir().join(format!("roche-ts-prep-{}", uuid::Uuid::new_v4()));
        tokio::fs::create_dir_all(&temp_dir).await.unwrap();
        let status_json = r#"{
          "BackendState": "Running",
          "MagicDNSSuffix": "tail9876.ts.net",
          "Self": {
            "HostName": "mac-mini",
            "DNSName": "mac-mini.tail9876.ts.net.",
            "TailscaleIPs": ["100.64.0.12"],
            "Online": true
          }
        }"#;
        let status_file = temp_dir.join("status.json");
        tokio::fs::write(&status_file, status_json).await.unwrap();

        let script_path = temp_dir.join("fake-tailscale.sh");
        let script = format!(
            "#!/bin/sh\n\
             if [ \"$1\" = \"status\" ]; then\n\
               cat \"{}\"\n\
               exit 0\n\
             fi\n\
             echo \"HTTPS not enabled\" >&2\n\
             exit 1\n",
            status_file.display()
        );
        tokio::fs::write(&script_path, script).await.unwrap();
        tokio::fs::set_permissions(&script_path, std::fs::Permissions::from_mode(0o700))
            .await
            .unwrap();

        let mut cfg = config::Config::default();
        cfg.tailscale.enabled = true;
        cfg.tailscale.tailscale_bin = script_path;
        cfg.tailscale.advertise_service = Some("svc:roche".to_string());

        let (_listen_cfg, ts_identity) = RocheServer::prepare_tailscale(&cfg).await.unwrap();
        let identity = ts_identity.unwrap();
        assert_eq!(identity.node_https_port, None);
        assert!(identity.node_https_url().is_none());
        assert_eq!(identity.candidate_urls(), Vec::<String>::new());
        assert!(identity.canonical_url().is_none());

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
