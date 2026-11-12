use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use super::SyncRepo;

const MAX_PAIRING_ATTEMPTS: i64 = 25;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
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
        sqlx::query("DELETE FROM pairing_codes").execute(&mut *tx).await?;

        sqlx::query(
            "INSERT INTO pairing_codes (code, failed_attempts, created_at, expires_at) VALUES (?, 0, ?, ?)",
        )
        .bind(&code_hash)
        .bind(now)
        .bind(expires_at)
        .execute(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn active_pairing_code_hash(&self) -> Result<Option<String>> {
        let now = SyncRepo::now_utc();
        Ok(sqlx::query_scalar::<_, String>(
            "SELECT code FROM pairing_codes
             WHERE datetime(expires_at) > datetime(?) AND failed_attempts < ?
             LIMIT 1",
        )
        .bind(now)
        .bind(MAX_PAIRING_ATTEMPTS)
        .fetch_optional(self.pool)
        .await?)
    }

    pub async fn consume_valid_pairing_code(&self, code: &str) -> Result<bool> {
        let now = SyncRepo::now_utc();
        let normalized = code.trim().to_ascii_lowercase();
        let code_hash = crate::auth::tokens::TokenManager::hash_token(&normalized);
        let mut tx = self.pool.begin().await?;

        sqlx::query("DELETE FROM pairing_codes WHERE datetime(expires_at) <= datetime(?)")
            .bind(now)
            .execute(&mut *tx)
            .await?;

        let result = sqlx::query(
            "DELETE FROM pairing_codes
             WHERE code = ? AND datetime(expires_at) > datetime(?) AND failed_attempts < ?",
        )
        .bind(&code_hash)
        .bind(now)
        .bind(MAX_PAIRING_ATTEMPTS)
        .execute(&mut *tx)
        .await?;

        if result.rows_affected() > 0 {
            tx.commit().await?;
            return Ok(true);
        }

        sqlx::query("UPDATE pairing_codes SET failed_attempts = failed_attempts + 1")
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM pairing_codes WHERE failed_attempts >= ?")
            .bind(MAX_PAIRING_ATTEMPTS)
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
        sqlx::query(
            "INSERT INTO device_tokens (id, device_name, token, created_at) VALUES (?, ?, ?, ?)",
        )
        .bind(id)
        .bind(device_name)
        .bind(token_hash)
        .bind(now)
        .execute(self.pool)
        .await?;
        Ok(())
    }

    pub async fn validate_device_token(&self, token_hash: &str) -> Result<bool> {
        let needs_touch: Option<(bool,)> = sqlx::query_as(
            "SELECT (last_used_at IS NULL OR datetime(last_used_at) <= datetime('now', '-60 seconds'))
             FROM device_tokens
             WHERE token = ?",
        )
        .bind(token_hash)
        .fetch_optional(self.pool)
        .await?;

        let Some((should_update,)) = needs_touch else {
            return Ok(false);
        };

        if should_update {
            let now = SyncRepo::now_utc();
            sqlx::query("UPDATE device_tokens SET last_used_at = ? WHERE token = ?")
                .bind(now)
                .bind(token_hash)
                .execute(self.pool)
                .await?;
        }

        Ok(true)
    }

    pub async fn count_devices(&self) -> Result<i64> {
        Ok(sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM device_tokens")
            .fetch_one(self.pool)
            .await?)
    }

    pub async fn list_devices(&self) -> Result<Vec<DeviceToken>> {
        Ok(sqlx::query_as::<_, DeviceToken>(
            "SELECT id, device_name, created_at, last_used_at
             FROM device_tokens
             ORDER BY created_at DESC",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn revoke_device(&self, id: &str) -> Result<bool> {
        let res = sqlx::query("DELETE FROM device_tokens WHERE id = ?")
            .bind(id)
            .execute(self.pool)
            .await?;
        Ok(res.rows_affected() > 0)
    }
}
