pub mod albums_pool;
mod api;
mod auth;
pub mod cli;
pub mod config;
mod content;
pub mod db;
mod error;
mod feeds;
mod media;
pub mod migrate_great;
pub mod ranking;
mod scheduler;
mod service;
pub mod tailscale;
mod tui_rank;
mod web;
mod workers;

pub struct RocheServer;

impl RocheServer {
    pub async fn run(cfg: config::Config) -> anyhow::Result<()> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        let fetcher = scheduler::HttpFeedFetcher::new()?;

        let mut ts_identity = tailscale::inspect_local_node(&cfg.tailscale).await;

        let listen_host = if cfg.server.host.trim().eq_ignore_ascii_case("tailscale") {
            let ts_ip = ts_identity
                .as_ref()
                .and_then(|id| id.ipv4.as_deref())
                .ok_or_else(|| anyhow::anyhow!("`--host tailscale` requested, but no active Tailscale IPv4 address was found"))?;
            ts_ip.to_string()
        } else {
            cfg.server.host.clone()
        };
        let listen_cfg = config::ServerConfig { host: listen_host, port: cfg.server.port };

        let advertised_ok = match Self::maybe_advertise_tailscale(&cfg, &listen_cfg.host).await {
            Ok(()) => true,
            Err(e) => {
                tracing::warn!(error = %e, "Failed to auto-advertise Tailscale service");
                false
            }
        };
        if !advertised_ok && let Some(identity) = &mut ts_identity {
            identity.service_name = None;
        }
        if let Some(identity) = &ts_identity {
            let urls = identity.candidate_urls(cfg.server.port);
            tracing::info!(tailscale_urls = ?urls, "Detected Tailscale node addresses");
        }

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

        let roche_service = service::RocheService::new(db.clone(), fetcher, cfg.clone())
            .with_node_identity(ts_identity);

        if cfg.tailscale.enabled && !cfg.tailscale.auto_auth_users.is_empty() {
            tracing::info!(
                allowed_users = cfg.tailscale.auto_auth_users.len(),
                "tailscale.auto_auth_users enabled; ensure this is a single-user host fronted exclusively by `tailscale serve`"
            );
        }

        if roche_service.auth().count_devices().await.unwrap_or(0) == 0 {
            match roche_service.auth().issue_pairing_code().await {
                Ok(code) => {
                    if std::io::IsTerminal::is_terminal(&std::io::stderr()) {
                        eprintln!("Bootstrap pairing code (valid 5m): {code}");
                    }
                    tracing::info!(
                        "No paired devices found; generated 5-minute bootstrap pairing code (run `roche pair` to mint a new code)"
                    );
                }
                Err(e) => {
                    tracing::error!(error = %e, "Failed to generate bootstrap device pairing code");
                }
            }
        } else {
            tracing::info!("Paired devices exist; run `roche pair` to generate a new pairing code");
        }

        let poll_scheduler = roche_service.poll_scheduler();
        let scheduler_handle = tokio::spawn(async move {
            if let Err(e) = poll_scheduler.run().await {
                tracing::error!(error = %e, "PollScheduler terminated with error");
            }
        });

        let worker_db = db.clone();
        let prune_after = cfg.sync.op_log_prune_after;
        let media_dir = cfg.storage.media_dir.clone();
        let download_dir = cfg.media.download_dir.clone();
        let worker_handle = tokio::spawn(async move {
            let worker_mgr = workers::WorkerManager::new(worker_db, prune_after, media_dir)
                .with_download_dir(download_dir);
            if let Err(e) = worker_mgr.run_all().await {
                tracing::error!(error = %e, "WorkerManager terminated with error");
            }
        });

        let app = api::ApiRoutes::router_with_service(roche_service);
        let addr = listen_cfg.listen_addr();
        let listener = tokio::net::TcpListener::bind(&addr).await?;

        tracing::info!(addr = %addr, "Roche server listening");
        axum::serve(listener, app.into_make_service_with_connect_info::<std::net::SocketAddr>())
            .with_graceful_shutdown(Self::shutdown_signal())
            .await?;

        scheduler_handle.abort();
        worker_handle.abort();
        Ok(())
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
            Err(error::ApiError::NotFound(_)) => Ok(false),
            Err(other) => Err(other.into()),
        }
    }

    pub async fn import_opml_file(
        cfg: &config::Config,
        path: &std::path::Path,
        poll: bool,
    ) -> anyhow::Result<usize> {
        let bytes = tokio::fs::read(path).await?;
        let opml_str = content::fetcher::SafeHttpClient::decode_xml_or_text_bytes(&bytes);
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
        let identity = tailscale::inspect_local_node(&cfg.tailscale).await?;
        identity.canonical_url(cfg.server.port)
    }

    async fn maybe_advertise_tailscale(
        cfg: &config::Config,
        listen_host: &str,
    ) -> anyhow::Result<()> {
        if let Some(svc) =
            tailscale::advertise_service_with_target(&cfg.tailscale, listen_host, cfg.server.port)
                .await?
        {
            tracing::info!(
                service = %svc,
                upstream = %tailscale::serve_upstream_host(listen_host),
                port = cfg.server.port,
                "Advertised Roche server on Tailnet via `tailscale serve`"
            );
        }
        Ok(())
    }
}
