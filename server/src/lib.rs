mod api;
mod auth;
pub mod cli;
pub mod config;
mod content;
mod db;
mod error;
mod feeds;
mod media;
mod scheduler;
mod service;
pub mod tailscale;
mod workers;

pub struct RocheServer;

impl RocheServer {
    pub async fn run(cfg: config::Config) -> anyhow::Result<()> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        let fetcher = scheduler::HttpFeedFetcher::new()?;

        let advertised_ok = match Self::maybe_advertise_tailscale(&cfg).await {
            Ok(()) => true,
            Err(e) => {
                tracing::warn!(error = %e, "Failed to auto-advertise Tailscale service");
                false
            }
        };
        let mut ts_identity = tailscale::inspect_local_node(&cfg.tailscale).await;
        if !advertised_ok && let Some(identity) = &mut ts_identity {
            identity.service_name = None;
        }
        if let Some(identity) = &ts_identity {
            let urls = identity.candidate_urls(cfg.server.port);
            tracing::info!(tailscale_urls = ?urls, "Detected Tailscale node addresses");
        }

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

        let roche_service = service::RocheService::new(db.clone(), fetcher.clone(), cfg.clone())
            .with_node_identity(ts_identity);

        if roche_service.auth().count_devices().await.unwrap_or(0) == 0 {
            match roche_service.auth().issue_pairing_code().await {
                Ok(code) => tracing::info!(
                    pairing_code = %code,
                    "No paired devices found; generated bootstrap pairing code"
                ),
                Err(e) => {
                    tracing::error!(error = %e, "Failed to generate bootstrap device pairing code");
                }
            }
        } else {
            tracing::info!("Paired devices exist; run `roche pair` to generate a new pairing code");
        }

        let scheduler_db = db.clone();
        let scheduler_fetcher = fetcher.clone();
        let scheduler_media_store = media::MediaStore::new(&cfg.storage.media_dir);
        let scheduler_ytdlp =
            media::ytdlp::YtDlpWorker::new(cfg.media.clone(), scheduler_media_store.clone());
        let shared_download_sem = roche_service.download_semaphore();
        let scheduler_handle =
            tokio::spawn(async move {
                let poll_scheduler =
                    scheduler::PollScheduler::with_fetcher(scheduler_db, scheduler_fetcher)
                        .with_media(scheduler_media_store, scheduler_ytdlp, shared_download_sem);
                if let Err(e) = poll_scheduler.run().await {
                    tracing::error!(error = %e, "PollScheduler terminated with error");
                }
            });

        let worker_db = db.clone();
        let prune_after = cfg.sync.op_log_prune_after;
        let media_dir = cfg.storage.media_dir.clone();
        let worker_handle = tokio::spawn(async move {
            let worker_mgr = workers::WorkerManager::new(worker_db, prune_after, media_dir);
            if let Err(e) = worker_mgr.run_all().await {
                tracing::error!(error = %e, "WorkerManager terminated with error");
            }
        });

        let app = api::ApiRoutes::router_with_service(roche_service);
        let addr = listen_cfg.listen_addr();
        let listener = tokio::net::TcpListener::bind(&addr).await?;

        tracing::info!(addr = %addr, "Roche server listening");
        axum::serve(listener, app).with_graceful_shutdown(Self::shutdown_signal()).await?;

        scheduler_handle.abort();
        worker_handle.abort();
        Ok(())
    }

    async fn shutdown_signal() {
        if let Err(e) = tokio::signal::ctrl_c().await {
            tracing::error!(error = %e, "Failed to listen for shutdown signal");
        }
        tracing::info!("Shutdown signal received, stopping server");
    }

    pub async fn issue_pairing_code(cfg: &config::Config) -> anyhow::Result<String> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        let pairing = auth::pairing::PairingManager::new(db.auth());
        pairing.create_code(cfg.auth.pairing_code_ttl).await
    }

    pub async fn list_devices(cfg: &config::Config) -> anyhow::Result<Vec<db::DeviceToken>> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        db.auth().list_devices().await
    }

    pub async fn revoke_device(cfg: &config::Config, device_id: &str) -> anyhow::Result<bool> {
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        db.auth().revoke_device(device_id).await
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

    async fn maybe_advertise_tailscale(cfg: &config::Config) -> anyhow::Result<()> {
        if let Some(svc) = tailscale::advertise_service(&cfg.tailscale, cfg.server.port).await? {
            tracing::info!(
                service = %svc,
                port = cfg.server.port,
                "Advertised Roche server on Tailnet via `tailscale serve`"
            );
        }
        Ok(())
    }
}
