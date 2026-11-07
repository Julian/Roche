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
             ORDER BY id DESC
             LIMIT 5000",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn latest_activity_id(&self) -> Result<i64> {
        Ok(sqlx::query_scalar::<_, i64>("SELECT COALESCE(MAX(id), 0) FROM activity_log")
            .fetch_one(self.pool)
            .await?)
    }

    pub async fn list_changes_since(
        &self,
        since_id: i64,
        limit: i64,
    ) -> Result<Vec<ActivityEntry>> {
        let clamped_since = since_id.max(0);
        let clamped_limit = limit.clamp(1, 1001);
        Ok(sqlx::query_as::<_, ActivityEntry>(
            "SELECT id, entity_type, entity_id, action, timestamp
             FROM activity_log
             WHERE id > ?
             ORDER BY id ASC
             LIMIT ?",
        )
        .bind(clamped_since)
        .bind(clamped_limit)
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
