use sha2::{Digest, Sha256};
use url::Url;
use uuid::Uuid;

/// Generates a deterministic RFC 4122 v5-shaped UUID from `(kind, natural_key)` using SHA-256
/// so every peer (Rust, Android, CLI, Web) derives the exact same UUID for the same entity.
#[must_use]
pub fn deterministic_entity_uuid(kind: &str, natural_key: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(b"roche:entity:v1:");
    hasher.update(kind.as_bytes());
    hasher.update(b":");
    hasher.update(natural_key.as_bytes());
    let digest = hasher.finalize();
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&digest[..16]);
    bytes[6] = (bytes[6] & 0x0f) | 0x50;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    Uuid::from_bytes(bytes).to_string()
}

#[must_use]
pub fn canonicalize_sync_url(raw: &str) -> String {
    let trimmed = raw.trim();
    Url::parse(trimmed).map_or_else(|_| trimmed.to_ascii_lowercase(), String::from)
}

#[must_use]
pub fn feed_natural_key(url: &str) -> String {
    canonicalize_sync_url(url)
}

#[must_use]
pub fn item_natural_key(feed_url: Option<&str>, item_url: &str) -> String {
    let canon_item = canonicalize_sync_url(item_url);
    feed_url.map(str::trim).filter(|s| !s.is_empty()).map_or_else(
        || format!("manual\0{canon_item}"),
        |f_url| format!("{}\0{canon_item}", canonicalize_sync_url(f_url)),
    )
}

#[must_use]
pub fn tag_natural_key(name: &str) -> String {
    name.trim().to_lowercase()
}

#[must_use]
pub fn deterministic_feed_id(url: &str) -> String {
    deterministic_entity_uuid("feed", &feed_natural_key(url))
}

#[must_use]
pub fn deterministic_item_id(feed_url: Option<&str>, item_url: &str) -> String {
    deterministic_entity_uuid("item", &item_natural_key(feed_url, item_url))
}

#[must_use]
pub fn deterministic_tag_id(name: &str) -> String {
    deterministic_entity_uuid("tag", &tag_natural_key(name))
}
