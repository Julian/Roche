use sha2::{Digest, Sha256};

/// Computes the lowercase hex-encoded SHA-256 digest of `raw`.
#[must_use]
pub fn sha256_hex(raw: &str) -> String {
    let digest = Sha256::digest(raw.as_bytes());
    hex::encode(digest)
}
