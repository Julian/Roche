use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use super::WriteLock;
use crate::util::{clock::now_utc, hash::sha256_hex};

const MAX_PAIRING_ATTEMPTS: i64 = 25;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceToken {
    pub id: String,
    pub device_name: String,
    pub created_at: DateTime<Utc>,
    pub last_used_at: Option<DateTime<Utc>>,
}

pub struct AuthRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
    pub(crate) write_lock: &'a WriteLock,
}

impl<'a> AuthRepo<'a> {
    #[must_use]
    pub(crate) const fn new(pool: &'a SqlitePool, write_lock: &'a WriteLock) -> Self {
        Self { pool, write_lock }
    }

    pub async fn save_pairing_code(&self, code: &str, expires_at: DateTime<Utc>) -> Result<()> {
        let now = now_utc();
        let normalized = code.trim().to_ascii_lowercase();
        let code_hash = sha256_hex(&normalized);
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        sqlx::query!("DELETE FROM pairing_codes").execute(tx.conn()).await?;

        sqlx::query!(
            "INSERT INTO pairing_codes (code, failed_attempts, created_at, expires_at) VALUES (?, 0, ?, ?)",
            code_hash,
            now as _,
            expires_at as _,
        )
        .execute(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn active_pairing_code_hash(&self) -> Result<Option<String>> {
        let now = now_utc();
        Ok(sqlx::query_scalar!(
            "SELECT code FROM pairing_codes
             WHERE datetime(expires_at) > datetime(?) AND failed_attempts < ?
             LIMIT 1",
            now as _,
            MAX_PAIRING_ATTEMPTS,
        )
        .fetch_optional(self.pool)
        .await?)
    }

    pub async fn consume_valid_pairing_code(&self, code: &str) -> Result<bool> {
        let now = now_utc();
        let normalized = code.trim().to_ascii_lowercase();
        let code_hash = sha256_hex(&normalized);
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;

        sqlx::query!(
            "DELETE FROM pairing_codes WHERE datetime(expires_at) <= datetime(?)",
            now as _
        )
        .execute(tx.conn())
        .await?;

        let result = sqlx::query!(
            "DELETE FROM pairing_codes
             WHERE code = ? AND datetime(expires_at) > datetime(?) AND failed_attempts < ?",
            code_hash,
            now as _,
            MAX_PAIRING_ATTEMPTS,
        )
        .execute(tx.conn())
        .await?;

        if result.rows_affected() > 0 {
            tx.commit().await?;
            return Ok(true);
        }

        sqlx::query!("UPDATE pairing_codes SET failed_attempts = failed_attempts + 1")
            .execute(tx.conn())
            .await?;
        sqlx::query!("DELETE FROM pairing_codes WHERE failed_attempts >= ?", MAX_PAIRING_ATTEMPTS)
            .execute(tx.conn())
            .await?;

        tx.commit().await?;
        Ok(false)
    }

    pub async fn create_device_token(
        &self,
        id: &str,
        device_name: &str,
        token_hash: &str,
    ) -> Result<()> {
        let now = now_utc();
        let _write_guard = self.write_lock.acquire().await?;
        sqlx::query!(
            "INSERT INTO device_tokens (id, device_name, token, created_at) VALUES (?, ?, ?, ?)",
            id,
            device_name,
            token_hash,
            now as _,
        )
        .execute(self.pool)
        .await?;
        Ok(())
    }

    pub async fn validate_device_token(&self, token_hash: &str) -> Result<bool> {
        Ok(self.validate_device_token_id(token_hash).await?.is_some())
    }

    pub async fn validate_device_token_id(&self, token_hash: &str) -> Result<Option<String>> {
        let row: Option<(String, bool)> = sqlx::query_as(
            "SELECT id,
                    (last_used_at IS NULL OR datetime(last_used_at) <= datetime('now', '-60 seconds'))
             FROM device_tokens
             WHERE token = ?",
        )
        .bind(token_hash)
        .fetch_optional(self.pool)
        .await?;

        let Some((id, should_update)) = row else {
            return Ok(None);
        };

        if should_update {
            let now = now_utc();
            let _write_guard = self.write_lock.acquire().await?;
            sqlx::query("UPDATE device_tokens SET last_used_at = ? WHERE id = ?")
                .bind(now)
                .bind(&id)
                .execute(self.pool)
                .await?;
        }

        Ok(Some(id))
    }

    /// Binds `token_id` to `peer_id` on first sync (TOFU) or verifies an existing binding.
    /// Returns `Ok(false)` if this token is bound to a different `peer_id` or another token
    /// already owns `peer_id`.
    pub async fn bind_or_verify_peer_id(&self, token_id: &str, peer_id: &str) -> Result<bool> {
        let mut tx = crate::db::begin_write(self.pool, self.write_lock).await?;
        let current: Option<Option<String>> =
            sqlx::query_scalar("SELECT bound_peer_id FROM device_tokens WHERE id = ?")
                .bind(token_id)
                .fetch_optional(tx.conn())
                .await?;
        let Some(existing_bound) = current else {
            return Ok(false);
        };
        if let Some(bound) = existing_bound {
            return Ok(bound == peer_id);
        }
        let claimed_by_other: Option<String> = sqlx::query_scalar(
            "SELECT id FROM device_tokens WHERE bound_peer_id = ? AND id != ? LIMIT 1",
        )
        .bind(peer_id)
        .bind(token_id)
        .fetch_optional(tx.conn())
        .await?;
        if claimed_by_other.is_some() {
            return Ok(false);
        }
        sqlx::query(
            "UPDATE device_tokens SET bound_peer_id = ? WHERE id = ? AND bound_peer_id IS NULL",
        )
        .bind(peer_id)
        .bind(token_id)
        .execute(tx.conn())
        .await?;
        tx.commit().await?;
        Ok(true)
    }

    pub async fn count_devices(&self) -> Result<i64> {
        Ok(sqlx::query_scalar!("SELECT COUNT(*) AS \"count!: i64\" FROM device_tokens")
            .fetch_one(self.pool)
            .await?)
    }

    pub async fn list_devices(&self) -> Result<Vec<DeviceToken>> {
        Ok(sqlx::query_as!(
            DeviceToken,
            "SELECT id,
                    device_name,
                    created_at AS \"created_at: DateTime<Utc>\",
                    last_used_at AS \"last_used_at: DateTime<Utc>\"
             FROM device_tokens
             ORDER BY created_at DESC",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn revoke_device(&self, id: &str) -> Result<bool> {
        let _write_guard = self.write_lock.acquire().await?;
        let res =
            sqlx::query!("DELETE FROM device_tokens WHERE id = ?", id).execute(self.pool).await?;
        Ok(res.rows_affected() > 0)
    }

    pub async fn create_web_session(
        &self,
        id: &str,
        label: &str,
        token_hash: &str,
        expires_at: DateTime<Utc>,
    ) -> Result<()> {
        let now = now_utc();
        let _write_guard = self.write_lock.acquire().await?;
        sqlx::query(
            "INSERT INTO web_sessions (id, token_hash, label, created_at, expires_at)
             VALUES (?, ?, ?, ?, ?)",
        )
        .bind(id)
        .bind(token_hash)
        .bind(label)
        .bind(now)
        .bind(expires_at)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    /// Read-only web session lookup returning `(session_id, should_touch_last_used)` so
    /// authenticated `GET` page navigations never block on SQLite's write lock.
    pub async fn lookup_valid_web_session_id(
        &self,
        token_hash: &str,
    ) -> Result<Option<(String, bool)>> {
        let now = now_utc();
        let row: Option<(String, bool)> = sqlx::query_as(
            "SELECT id,
                    (last_used_at IS NULL OR datetime(last_used_at) <= datetime('now', '-60 seconds'))
             FROM web_sessions
             WHERE token_hash = ? AND datetime(expires_at) > datetime(?)",
        )
        .bind(token_hash)
        .bind(now)
        .fetch_optional(self.pool)
        .await?;
        Ok(row)
    }

    /// Updates `web_sessions.last_used_at` for an active session under the serialized write lock.
    pub async fn touch_web_session_last_used(&self, id: &str) -> Result<()> {
        let now = now_utc();
        let _write_guard = self.write_lock.acquire().await?;
        sqlx::query("UPDATE web_sessions SET last_used_at = ? WHERE id = ?")
            .bind(now)
            .bind(id)
            .execute(self.pool)
            .await?;
        Ok(())
    }

    pub async fn revoke_web_session_by_token_hash(&self, token_hash: &str) -> Result<bool> {
        let _write_guard = self.write_lock.acquire().await?;
        let res = sqlx::query("DELETE FROM web_sessions WHERE token_hash = ?")
            .bind(token_hash)
            .execute(self.pool)
            .await?;
        Ok(res.rows_affected() > 0)
    }

    pub async fn prune_expired_web_sessions(&self) -> Result<u64> {
        let now = now_utc();
        let _write_guard = self.write_lock.acquire().await?;
        let res = sqlx::query("DELETE FROM web_sessions WHERE datetime(expires_at) <= datetime(?)")
            .bind(now)
            .execute(self.pool)
            .await?;
        Ok(res.rows_affected())
    }

    /// Prunes older web sessions for `label` beyond `keep_most_recent`
    /// so `web_sessions` stays bounded even across repeated server restarts.
    pub async fn prune_excess_web_sessions(
        &self,
        label: &str,
        keep_most_recent: i64,
    ) -> Result<u64> {
        let keep = keep_most_recent.max(1);
        let _write_guard = self.write_lock.acquire().await?;
        let res = sqlx::query(
            "DELETE FROM web_sessions
             WHERE label = ?
               AND id NOT IN (
                   SELECT id FROM web_sessions
                   WHERE label = ?
                   ORDER BY COALESCE(last_used_at, created_at) DESC, created_at DESC
                   LIMIT ?
               )",
        )
        .bind(label)
        .bind(label)
        .bind(keep)
        .execute(self.pool)
        .await?;
        Ok(res.rows_affected())
    }

    #[cfg(test)]
    pub async fn count_web_sessions(&self) -> Result<i64> {
        Ok(sqlx::query_scalar("SELECT COUNT(*) FROM web_sessions").fetch_one(self.pool).await?)
    }
}
