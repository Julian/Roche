use anyhow::Result;
use rand::{RngExt, seq::IndexedRandom};

use crate::db::{AuthRepo, SyncRepo};

pub struct PairingManager<'a> {
    repo: AuthRepo<'a>,
}

impl<'a> PairingManager<'a> {
    #[must_use]
    pub const fn new(repo: AuthRepo<'a>) -> Self {
        Self { repo }
    }

    pub async fn create_code(&self, ttl_seconds: u64) -> Result<String> {
        let code = Self::generate_human_readable_code();
        let ttl = i64::try_from(ttl_seconds).unwrap_or(300).max(1);
        let expires_at = SyncRepo::now_utc() + chrono::Duration::seconds(ttl);
        self.repo.save_pairing_code(&code, expires_at).await?;
        Ok(code)
    }

    pub async fn consume_valid_code(&self, code: &str) -> Result<bool> {
        self.repo.consume_valid_pairing_code(code).await
    }

    fn generate_human_readable_code() -> String {
        const WORDS: &[&str] = &[
            "guitarist",
            "revenue",
            "lantern",
            "horizon",
            "cascade",
            "whisper",
            "velvet",
            "thunder",
            "meadow",
            "crystal",
            "shadow",
            "ember",
            "frost",
            "storm",
            "harbor",
            "orbit",
            "pixel",
            "nebula",
            "quantum",
            "zephyr",
            "compass",
            "granite",
            "beacon",
            "falcon",
            "glacier",
            "monolith",
            "prism",
            "solstice",
            "tempest",
            "voyager",
            "wildfire",
            "zenith",
        ];

        let mut rng = rand::rng();
        let num = rng.random_range(1000..10000u16);
        let words: Vec<&str> = (0..4).filter_map(|_| WORDS.choose(&mut rng).copied()).collect();

        format!("{num}-{}", words.join("-"))
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::db::Db;

    #[tokio::test]
    async fn test_pairing_code_lifecycle_and_brute_force_lockout() {
        let db = Db::connect("sqlite::memory:").await.unwrap();
        let pairing = PairingManager::new(db.auth());
        let code = pairing.create_code(300).await.unwrap();

        // Raw pairing code is never stored in plaintext in the database
        let stored_code: String = sqlx::query_scalar("SELECT code FROM pairing_codes")
            .fetch_one(db.pool())
            .await
            .unwrap();
        assert_ne!(stored_code, code);
        assert_eq!(stored_code, crate::auth::tokens::TokenManager::hash_token(&code));

        // Submitting the stored SHA-256 hash directly as the pairing code is rejected
        assert!(!pairing.consume_valid_code(&stored_code).await.unwrap());

        // Auto-capitalized code from mobile keyboards still matches case-insensitively
        assert!(pairing.consume_valid_code(&code.to_ascii_uppercase()).await.unwrap());
        assert!(!pairing.consume_valid_code(&code).await.unwrap());

        // Brute-force protection: 5 wrong attempts invalidate the active code
        let code2 = pairing.create_code(300).await.unwrap();
        for _ in 0..5 {
            assert!(!pairing.consume_valid_code("0000-wrong-code").await.unwrap());
        }
        assert!(!pairing.consume_valid_code(&code2).await.unwrap());
    }
}
