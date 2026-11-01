mod api;
mod auth;
pub mod cli;
mod config;
mod content;
mod db;
mod error;
mod feeds;
mod media;
mod scheduler;
mod service;
mod workers;

pub struct RocheServer;

impl RocheServer {
    pub async fn run(config_path: Option<&std::path::Path>) -> anyhow::Result<()> {
        let cfg = config::Config::load(config_path)?;
        let db = db::Db::connect(&cfg.storage.database_path).await?;
        let fetcher = scheduler::HttpFeedFetcher::new()?;
        let roche_service = service::RocheService::new(db.clone(), fetcher.clone(), cfg.clone());

        match roche_service.auth().issue_pairing_code().await {
            Ok(code) => tracing::info!(pairing_code = %code, "Device pairing code generated"),
            Err(e) => tracing::error!(error = %e, "Failed to generate startup device pairing code"),
        }

        let scheduler_db = db.clone();
        let scheduler_fetcher = fetcher.clone();
        let scheduler_handle = tokio::spawn(async move {
            let poll_scheduler =
                scheduler::PollScheduler::with_fetcher(scheduler_db, scheduler_fetcher);
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
        let addr = format!("{}:{}", cfg.server.host, cfg.server.port);
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
}
