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
    #[serde(default)]
    pub hlc: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
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
        sqlx::query!(
            "INSERT INTO activity_log (entity_type, entity_id, action, timestamp)
             VALUES (?, ?, ?, ?)",
            entity_type as _,
            entity_id,
            action as _,
            timestamp as _,
        )
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
        sqlx::query!(
            "INSERT INTO sync_tombstones (entity_type, natural_key, deleted_at)
             VALUES (?, ?, ?)
             ON CONFLICT(entity_type, natural_key) DO UPDATE SET
                 deleted_at = CASE
                     WHEN strftime('%Y-%m-%dT%H:%M:%fZ', excluded.deleted_at) > strftime('%Y-%m-%dT%H:%M:%fZ', sync_tombstones.deleted_at)
                     THEN excluded.deleted_at
                     ELSE sync_tombstones.deleted_at
                 END",
            entity_type as _,
            natural_key,
            deleted_at as _,
        )
        .execute(conn)
        .await?;
        Ok(())
    }

    pub async fn record_tombstone_with_hlc(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
        deleted_at: DateTime<Utc>,
        hlc: &str,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query(
            "INSERT INTO sync_tombstones (entity_type, natural_key, deleted_at, hlc)
             VALUES (?, ?, ?, ?)
             ON CONFLICT(entity_type, natural_key) DO UPDATE SET
                 deleted_at = CASE
                     WHEN excluded.hlc > sync_tombstones.hlc THEN excluded.deleted_at
                     ELSE sync_tombstones.deleted_at
                 END,
                 hlc = CASE
                     WHEN excluded.hlc > sync_tombstones.hlc THEN excluded.hlc
                     ELSE sync_tombstones.hlc
                 END",
        )
        .bind(entity_type)
        .bind(natural_key)
        .bind(deleted_at)
        .bind(hlc)
        .execute(conn)
        .await?;
        Ok(())
    }

    pub async fn clear_tombstone(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
    ) -> std::result::Result<(), sqlx::Error> {
        sqlx::query!(
            "DELETE FROM sync_tombstones WHERE entity_type = ? AND natural_key = ?",
            entity_type as _,
            natural_key,
        )
        .execute(conn)
        .await?;
        Ok(())
    }

    pub(crate) async fn list_activity_in_tx(
        conn: &mut SqliteConnection,
    ) -> Result<Vec<ActivityEntry>> {
        Ok(sqlx::query_as!(
            ActivityEntry,
            "SELECT id AS \"id!: i64\",
                    entity_type AS \"entity_type: ActivityEntityType\",
                    entity_id,
                    action AS \"action: ActivityAction\",
                    timestamp AS \"timestamp: DateTime<Utc>\"
             FROM activity_log
             ORDER BY id DESC
             LIMIT 5000",
        )
        .fetch_all(conn)
        .await?)
    }

    pub async fn latest_activity_id(&self) -> Result<i64> {
        Ok(sqlx::query_scalar!("SELECT COALESCE(MAX(id), 0) AS \"id!: i64\" FROM activity_log")
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
        Ok(sqlx::query_as!(
            ActivityEntry,
            "SELECT id AS \"id!: i64\",
                    entity_type AS \"entity_type: ActivityEntityType\",
                    entity_id,
                    action AS \"action: ActivityAction\",
                    timestamp AS \"timestamp: DateTime<Utc>\"
             FROM activity_log
             WHERE id > ?
             ORDER BY id ASC
             LIMIT ?",
            clamped_since,
            clamped_limit,
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn prune_activity_older_than_days(&self, days: i64) -> Result<u64> {
        let cutoff = Self::now_utc() - chrono::Duration::days(days.max(0));
        let mut tx = crate::db::begin_write(self.pool).await?;
        let pruned_max_seq: Option<i64> = sqlx::query_scalar(
            "SELECT MAX(sync_seq) FROM sync_tombstones
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .fetch_optional(&mut *tx)
        .await?
        .flatten();

        if let Some(seq) = pruned_max_seq {
            sqlx::query(
                "UPDATE sync_clock SET min_retained_seq = MAX(min_retained_seq, ?) WHERE id = 1",
            )
            .bind(seq)
            .execute(&mut *tx)
            .await?;
        }

        let result = sqlx::query!(
            "DELETE FROM activity_log
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', timestamp) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
            cutoff as _,
        )
        .execute(&mut *tx)
        .await?;
        sqlx::query!(
            "DELETE FROM sync_tombstones
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', deleted_at) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
            cutoff as _,
        )
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(result.rows_affected())
    }
}
