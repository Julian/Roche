use anyhow::Result;
use chrono::{DateTime, SubsecRound, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};

use super::{ActivityAction, ActivityEntityType};

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ActivityEntry {
    pub id: i64,
    pub entity_type: ActivityEntityType,
    pub entity_id: String,
    pub action: ActivityAction,
    pub timestamp: DateTime<Utc>,
}

pub struct SyncRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

impl<'a> SyncRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    /// Returns the current UTC timestamp truncated to whole seconds so SQLite RFC3339 strings
    /// have a uniform width (`YYYY-MM-DDTHH:MM:SS+00:00`) for B-tree index comparisons.
    #[must_use]
    pub fn now_utc() -> DateTime<Utc> {
        Utc::now().trunc_subsecs(0)
    }

    pub async fn record_activity(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        entity_id: &str,
        action: ActivityAction,
        timestamp: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query(
            "INSERT INTO activity_log (entity_type, entity_id, action, timestamp)
             VALUES (?, ?, ?, ?)",
        )
        .bind(entity_type)
        .bind(entity_id)
        .bind(action)
        .bind(timestamp)
        .execute(conn)
        .await?;
        Ok(())
    }

    pub async fn list_activity(&self) -> Result<Vec<ActivityEntry>> {
        Ok(sqlx::query_as::<_, ActivityEntry>(
            "SELECT id, entity_type, entity_id, action, timestamp
             FROM activity_log
             ORDER BY id DESC",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn prune_activity_older_than_days(&self, days: i64) -> Result<u64> {
        let cutoff = Self::now_utc() - chrono::Duration::days(days.max(0));
        let result =
            sqlx::query("DELETE FROM activity_log WHERE datetime(timestamp) < datetime(?)")
                .bind(cutoff)
                .execute(self.pool)
                .await?;
        Ok(result.rows_affected())
    }
}
