use sha2::{Digest, Sha256};

/// Computes the lowercase hex-encoded SHA-256 digest of `raw`.
#[must_use]
pub fn sha256_hex(raw: &str) -> String {
    sha256_bytes_hex(raw.as_bytes())
}

/// Computes the lowercase hex-encoded SHA-256 digest of `bytes`.
#[must_use]
pub fn sha256_bytes_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    hex::encode(digest)
}
