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

pub const STARRED_COLLECTION_ID: &str = "533b1315-6e2e-57d3-ba85-dc88531c5a0b";
pub const UP_NEXT_COLLECTION_ID: &str = "3864ff37-5ed3-5256-9380-f85ff8054f1c";
pub const ALBUMS_1001_COLLECTION_ID: &str = "9eb44de7-bdfb-5bea-85d1-be913c542d8b";

#[must_use]
pub fn deterministic_tag_id(name: &str) -> String {
    deterministic_entity_uuid("tag", &tag_natural_key(name))
}

#[must_use]
pub fn deterministic_system_collection_id(slug: &str) -> String {
    deterministic_entity_uuid("system_collection", &slug.trim().to_lowercase())
}

#[must_use]
pub fn deterministic_catalog_entry_id(kind: &str, scheme: &str, value: &str) -> String {
    let key = format!(
        "{}\0{}\0{}",
        kind.trim().to_lowercase(),
        scheme.trim().to_lowercase(),
        value.trim()
    );
    deterministic_entity_uuid("entry", &key)
}

#[must_use]
pub fn deterministic_title_entry_id(kind: &str, title: &str) -> String {
    let key = format!("{}\0{}", kind.trim().to_lowercase(), title.trim().to_lowercase());
    deterministic_entity_uuid("entry_title", &key)
}

#[must_use]
pub fn deterministic_enclosure_media_id(entry_id: &str) -> String {
    format!("enclosure:{}", entry_id.trim())
}

#[must_use]
pub fn deterministic_consumption_log_id(entry_id: &str, ts_millis: i64, status: &str) -> String {
    let key = format!("{}\0{ts_millis}\0{}", entry_id.trim(), status.trim().to_lowercase());
    deterministic_entity_uuid("consumption_log", &key)
}

/// Deterministic consumption-log UUID for automatic feed/parent completion so online peers
/// independently auto-logging the same finished item converge on a single Diary row.
#[must_use]
pub fn deterministic_auto_consumption_log_id(entry_id: &str) -> String {
    deterministic_entity_uuid("auto_consumption_log", entry_id.trim())
}

#[must_use]
pub fn canonical_ordering_json(ordering: &[Vec<String>]) -> String {
    let normalized: Vec<Vec<String>> = ordering
        .iter()
        .map(|group| {
            let mut sorted: Vec<String> = group.iter().map(|s| s.trim().to_owned()).collect();
            sorted.sort();
            sorted
        })
        .collect();
    serde_json::to_string(&normalized).unwrap_or_else(|_| "[]".to_owned())
}

#[must_use]
pub fn deterministic_comparison_id(
    scope_collection_id: Option<&str>,
    ts_millis: i64,
    ordering: &[Vec<String>],
) -> String {
    let scope_key =
        scope_collection_id.map(str::trim).filter(|s| !s.is_empty()).unwrap_or("global");
    let ord_json = canonical_ordering_json(ordering);
    deterministic_entity_uuid("comparison", &format!("{scope_key}\0{ts_millis}\0{ord_json}"))
}

#[must_use]
pub fn collection_entry_natural_key(collection_id: &str, entry_id: &str) -> String {
    format!("{}\0{}", collection_id.trim(), entry_id.trim())
}
