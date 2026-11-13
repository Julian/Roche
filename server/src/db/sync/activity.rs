use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqliteConnection;

use super::SyncRepo;
use crate::db::{ActivityAction, ActivityEntityType};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, sqlx::FromRow)]
pub struct SyncTombstone {
    pub entity_type: ActivityEntityType,
    pub natural_key: String,
    pub deleted_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ActivityEntry {
    pub id: i64,
    pub entity_type: ActivityEntityType,
    pub entity_id: String,
    pub action: ActivityAction,
    pub timestamp: DateTime<Utc>,
}

impl SyncRepo<'_> {
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

    pub async fn record_tombstone(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
        deleted_at: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query(
            "INSERT INTO sync_tombstones (entity_type, natural_key, deleted_at)
             VALUES (?, ?, ?)
             ON CONFLICT(entity_type, natural_key) DO UPDATE SET
                 deleted_at = CASE
                     WHEN strftime('%Y-%m-%dT%H:%M:%fZ', excluded.deleted_at) > strftime('%Y-%m-%dT%H:%M:%fZ', sync_tombstones.deleted_at)
                     THEN excluded.deleted_at
                     ELSE sync_tombstones.deleted_at
                 END",
        )
        .bind(entity_type)
        .bind(natural_key)
        .bind(deleted_at)
        .execute(conn)
        .await?;
        Ok(())
    }

    pub async fn clear_tombstone(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query("DELETE FROM sync_tombstones WHERE entity_type = ? AND natural_key = ?")
            .bind(entity_type)
            .bind(natural_key)
            .execute(conn)
            .await?;
        Ok(())
    }

    pub async fn list_tombstones(
        &self,
        since: Option<DateTime<Utc>>,
    ) -> Result<Vec<SyncTombstone>> {
        if let Some(ts) = since {
            Ok(sqlx::query_as::<_, SyncTombstone>(
                "SELECT entity_type, natural_key, deleted_at
                 FROM sync_tombstones
                 WHERE strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) >= strftime('%Y-%m-%dT%H:%M:%fZ', ?)
                 ORDER BY deleted_at DESC
                 LIMIT 5000",
            )
            .bind(ts)
            .fetch_all(self.pool)
            .await?)
        } else {
            Ok(sqlx::query_as::<_, SyncTombstone>(
                "SELECT entity_type, natural_key, deleted_at
                 FROM sync_tombstones
                 ORDER BY deleted_at DESC
                 LIMIT 5000",
            )
            .fetch_all(self.pool)
            .await?)
        }
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
        let result = sqlx::query(
            "DELETE FROM activity_log
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', timestamp) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .execute(self.pool)
        .await?;
        let _ = sqlx::query(
            "DELETE FROM sync_tombstones
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .execute(self.pool)
        .await;
        Ok(result.rows_affected())
    }
}
