use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use super::SyncRepo;

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
}

impl<'a> AuthRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn save_pairing_code(&self, code: &str, expires_at: DateTime<Utc>) -> Result<()> {
        let now = SyncRepo::now_utc();
        let normalized = code.trim().to_ascii_lowercase();
        let code_hash = crate::auth::tokens::TokenManager::hash_token(&normalized);
        let mut tx = self.pool.begin().await?;
        sqlx::query!("DELETE FROM pairing_codes").execute(&mut *tx).await?;

        sqlx::query!(
            "INSERT INTO pairing_codes (code, failed_attempts, created_at, expires_at) VALUES (?, 0, ?, ?)",
            code_hash,
            now as _,
            expires_at as _,
        )
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn active_pairing_code_hash(&self) -> Result<Option<String>> {
        let now = SyncRepo::now_utc();
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
        let now = SyncRepo::now_utc();
        let normalized = code.trim().to_ascii_lowercase();
        let code_hash = crate::auth::tokens::TokenManager::hash_token(&normalized);
        let mut tx = self.pool.begin().await?;

        sqlx::query!(
            "DELETE FROM pairing_codes WHERE datetime(expires_at) <= datetime(?)",
            now as _
        )
        .execute(&mut *tx)
        .await?;

        let result = sqlx::query!(
            "DELETE FROM pairing_codes
             WHERE code = ? AND datetime(expires_at) > datetime(?) AND failed_attempts < ?",
            code_hash,
            now as _,
            MAX_PAIRING_ATTEMPTS,
        )
        .execute(&mut *tx)
        .await?;

        if result.rows_affected() > 0 {
            tx.commit().await?;
            return Ok(true);
        }

        sqlx::query!("UPDATE pairing_codes SET failed_attempts = failed_attempts + 1")
            .execute(&mut *tx)
            .await?;
        sqlx::query!("DELETE FROM pairing_codes WHERE failed_attempts >= ?", MAX_PAIRING_ATTEMPTS)
            .execute(&mut *tx)
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
        let now = SyncRepo::now_utc();
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
        let needs_touch = sqlx::query_scalar!(
            "SELECT (last_used_at IS NULL OR datetime(last_used_at) <= datetime('now', '-60 seconds')) AS \"needs_touch!: bool\"
             FROM device_tokens
             WHERE token = ?",
            token_hash,
        )
        .fetch_optional(self.pool)
        .await?;

        let Some(should_update) = needs_touch else {
            return Ok(false);
        };

        if should_update {
            let now = SyncRepo::now_utc();
            sqlx::query!(
                "UPDATE device_tokens SET last_used_at = ? WHERE token = ?",
                now as _,
                token_hash,
            )
            .execute(self.pool)
            .await?;
        }

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
        let res =
            sqlx::query!("DELETE FROM device_tokens WHERE id = ?", id).execute(self.pool).await?;
        Ok(res.rows_affected() > 0)
    }

    pub async fn revoke_device_by_token_hash(&self, token_hash: &str) -> Result<bool> {
        let res = sqlx::query!("DELETE FROM device_tokens WHERE token = ?", token_hash)
            .execute(self.pool)
            .await?;
        Ok(res.rows_affected() > 0)
    }

    /// Prunes older Tailscale web session tokens for `device_name` beyond `keep_most_recent`
    /// so `device_tokens` stays bounded even across repeated server restarts.
    pub async fn prune_excess_named_tokens(
        &self,
        device_name: &str,
        keep_most_recent: i64,
    ) -> Result<u64> {
        let keep = keep_most_recent.max(1);
        let res = sqlx::query!(
            "DELETE FROM device_tokens
             WHERE device_name = ?
               AND id NOT IN (
                   SELECT id FROM device_tokens
                   WHERE device_name = ?
                   ORDER BY COALESCE(last_used_at, created_at) DESC, created_at DESC
                   LIMIT ?
               )",
            device_name,
            device_name,
            keep,
        )
        .execute(self.pool)
        .await?;
        Ok(res.rows_affected())
    }
}
