use std::{path::Path, str::FromStr, time::Duration};

use anyhow::Result;
use sqlx::sqlite::{
    SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions, SqliteSynchronous,
};

mod auth;
mod feeds;
mod items;
mod media;
mod sync;
mod tags;
mod types;

pub use auth::{AuthRepo, DeviceToken};
pub use feeds::{Feed, FeedRepo, PollBatch, PollItemCandidate};
#[cfg(test)]
pub use items::NewItem;
pub use items::{Item, ItemFilter, ItemPage, ItemRepo, ManualItemInput};
pub use media::{Media, MediaRepo};
pub use sync::{ActivityEntry, SyncRepo};
pub use tags::{ItemTag, Tag, TagRepo};
pub use types::{
    ActivityAction, ActivityEntityType, ContentType, FeedSource, FeedType, HexColor, ItemSortField,
    ItemStatus, ItemStatusFilter, MediaType, SortOrder,
};

#[derive(Debug, Clone)]
pub struct Db {
    pool: SqlitePool,
}

impl Db {
    /// Connects to the SQLite database at `database_path` and runs all embedded migrations.
    pub async fn connect(database_path: impl AsRef<Path>) -> Result<Self> {
        let path = database_path.as_ref();
        let base_options = match path.to_str() {
            Some(s) if s.starts_with("sqlite:") => SqliteConnectOptions::from_str(s)?,
            _ => {
                if let Some(parent) = path.parent()
                    && !parent.as_os_str().is_empty()
                {
                    tokio::fs::create_dir_all(parent).await?;
                }
                SqliteConnectOptions::new().filename(path)
            }
        };

        let connect_options = base_options
            .create_if_missing(true)
            .foreign_keys(true)
            .journal_mode(SqliteJournalMode::Wal)
            .synchronous(SqliteSynchronous::Normal)
            .busy_timeout(Duration::from_secs(5));

        let pool =
            SqlitePoolOptions::new().max_connections(5).connect_with(connect_options).await?;

        sqlx::migrate!("./migrations").run(&pool).await?;

        let db = Self { pool };
        if let Err(e) = db.items().normalize_legacy_html_summaries().await {
            tracing::warn!(error = %e, "Failed to normalize legacy HTML summaries");
        }

        Ok(db)
    }

    #[cfg(test)]
    #[must_use]
    pub const fn pool(&self) -> &SqlitePool {
        &self.pool
    }

    #[must_use]
    pub const fn feeds(&self) -> FeedRepo<'_> {
        FeedRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn items(&self) -> ItemRepo<'_> {
        ItemRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn tags(&self) -> TagRepo<'_> {
        TagRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn media(&self) -> MediaRepo<'_> {
        MediaRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn auth(&self) -> AuthRepo<'_> {
        AuthRepo::new(&self.pool)
    }

    #[must_use]
    pub const fn sync(&self) -> SyncRepo<'_> {
        SyncRepo::new(&self.pool)
    }
}
