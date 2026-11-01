use anyhow::Result;
use rand::Rng;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::MIN_TOKEN_BYTES;
use crate::db::AuthRepo;

pub struct TokenManager<'a> {
    repo: AuthRepo<'a>,
}

impl<'a> TokenManager<'a> {
    #[must_use]
    pub const fn new(repo: AuthRepo<'a>) -> Self {
        Self { repo }
    }

    #[must_use]
    pub fn generate_token(length: usize) -> String {
        let byte_len = length.max(MIN_TOKEN_BYTES);
        let mut bytes = vec![0u8; byte_len];
        rand::rng().fill_bytes(&mut bytes);
        hex::encode(bytes)
    }

    #[must_use]
    pub fn hash_token(token: &str) -> String {
        let digest = Sha256::digest(token.as_bytes());
        hex::encode(digest)
    }

    pub async fn create(&self, device_name: &str, token_length: usize) -> Result<String> {
        let token = Self::generate_token(token_length);
        let token_hash = Self::hash_token(&token);
        let id = Uuid::new_v4().to_string();
        self.repo.create_device_token(&id, device_name, &token_hash).await?;
        Ok(token)
    }

    pub async fn validate(&self, token: &str) -> Result<bool> {
        let token_hash = Self::hash_token(token);
        self.repo.validate_device_token(&token_hash).await
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::Db;

    #[tokio::test]
    async fn test_token_lifecycle_hashes_and_updates_last_used() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let manager = TokenManager::new(db.auth());
        let token = manager.create("test-device", 32).await.unwrap();
        assert!(manager.validate(&token).await.unwrap());

        // Raw token is not stored in plaintext in the database
        let raw_count: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM device_tokens WHERE token = ?")
                .bind(&token)
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert_eq!(raw_count, 0);

        let last_used: Option<String> =
            sqlx::query_scalar("SELECT last_used_at FROM device_tokens WHERE token = ?")
                .bind(TokenManager::hash_token(&token))
                .fetch_one(db.pool())
                .await
                .unwrap();
        assert!(last_used.is_some());
    }
}
