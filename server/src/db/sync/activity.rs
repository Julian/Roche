use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqliteConnection;

use super::SyncRepo;
use crate::db::{ActivityAction, ActivityEntityType, WriteTx};

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

    pub(crate) async fn record_tombstone(
        tx: &mut WriteTx<'_>,
        entity_type: ActivityEntityType,
        natural_key: &str,
        deleted_at: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let hlc = tx.next_hlc().await?;
        Self::record_tombstone_with_hlc(tx.conn(), entity_type, natural_key, deleted_at, &hlc).await
    }

    pub async fn record_tombstone_with_hlc(
        conn: &mut SqliteConnection,
        entity_type: ActivityEntityType,
        natural_key: &str,
        deleted_at: DateTime<Utc>,
        hlc: &str,
    ) -> std::result::Result<(), sqlx::Error> {
        let recorded_at = Self::now_utc();
        sqlx::query(
            "INSERT INTO sync_tombstones (entity_type, natural_key, deleted_at, hlc, recorded_at)
             VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(entity_type, natural_key) DO UPDATE SET
                 deleted_at = CASE
                     WHEN excluded.hlc > sync_tombstones.hlc THEN excluded.deleted_at
                     ELSE sync_tombstones.deleted_at
                 END,
                 hlc = CASE
                     WHEN excluded.hlc > sync_tombstones.hlc THEN excluded.hlc
                     ELSE sync_tombstones.hlc
                 END,
                 recorded_at = CASE
                     WHEN excluded.hlc > sync_tombstones.hlc THEN excluded.recorded_at
                     ELSE COALESCE(sync_tombstones.recorded_at, excluded.recorded_at)
                 END",
        )
        .bind(entity_type)
        .bind(natural_key)
        .bind(deleted_at)
        .bind(hlc)
        .bind(recorded_at)
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

    pub(crate) async fn copy_item_tombstones_for_feed_url_change(
        conn: &mut SqliteConnection,
        old_feed_url: &str,
        new_feed_url: &str,
    ) -> std::result::Result<(), sqlx::Error> {
        let old_canon = super::canonicalize_sync_url(old_feed_url);
        let new_canon = super::canonicalize_sync_url(new_feed_url);
        if old_canon.is_empty() || new_canon.is_empty() || old_canon == new_canon {
            return Ok(());
        }
        let old_prefix = format!("{old_canon}\0");
        let old_prefix_end = format!("{old_canon}\u{0001}");
        let rows: Vec<(String, DateTime<Utc>, String)> = sqlx::query_as(
            "SELECT natural_key, deleted_at, hlc
             FROM sync_tombstones
             WHERE entity_type = 'item'
               AND natural_key >= ?
               AND natural_key < ?",
        )
        .bind(&old_prefix)
        .bind(&old_prefix_end)
        .fetch_all(&mut *conn)
        .await?;
        for (natural_key, deleted_at, hlc) in rows {
            if let Some(item_part) = natural_key.strip_prefix(&old_prefix) {
                let migrated_key = format!("{new_canon}\0{item_part}");
                Self::record_tombstone_with_hlc(
                    &mut *conn,
                    ActivityEntityType::Item,
                    &migrated_key,
                    deleted_at,
                    &hlc,
                )
                .await?;
            }
        }
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
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', COALESCE(recorded_at, deleted_at))
                 < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .fetch_optional(tx.conn())
        .await?
        .flatten();

        if let Some(seq) = pruned_max_seq {
            sqlx::query(
                "UPDATE sync_clock SET min_retained_seq = MAX(min_retained_seq, ?) WHERE id = 1",
            )
            .bind(seq)
            .execute(tx.conn())
            .await?;
        }

        let result = sqlx::query!(
            "DELETE FROM activity_log
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', timestamp) < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
            cutoff as _,
        )
        .execute(tx.conn())
        .await?;
        sqlx::query(
            "DELETE FROM sync_tombstones
             WHERE strftime('%Y-%m-%dT%H:%M:%fZ', COALESCE(recorded_at, deleted_at))
                 < strftime('%Y-%m-%dT%H:%M:%fZ', ?)",
        )
        .bind(cutoff)
        .execute(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(result.rows_affected())
    }
}
