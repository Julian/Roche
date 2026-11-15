use std::sync::LazyLock;

use serde::{Deserialize, Serialize};

const RAW_ALBUMS_POOL_JSON: &str = include_str!("data/albums_1001_pool.json");

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BundledAlbumEntry {
    pub id: String,
    pub spotify_id: String,
    pub slug: String,
    pub title: String,
    pub artist: String,
    pub year: i32,
    pub cover_url: Option<String>,
    #[serde(default)]
    pub genres: Vec<String>,
    #[serde(default)]
    pub styles: Vec<String>,
}

static ALBUMS_1001_POOL: LazyLock<Vec<BundledAlbumEntry>> =
    LazyLock::new(|| serde_json::from_str(RAW_ALBUMS_POOL_JSON).unwrap_or_default());

#[must_use]
pub fn bundled_1001_albums() -> &'static [BundledAlbumEntry] {
    &ALBUMS_1001_POOL
}

#[must_use]
pub fn find_bundled_album_by_id(entry_id: &str) -> Option<&'static BundledAlbumEntry> {
    ALBUMS_1001_POOL.iter().find(|a| a.id == entry_id)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_bundled_1001_albums_loaded_and_valid() {
        let pool = bundled_1001_albums();
        assert!(pool.len() >= 1_000, "expected >= 1000 albums, found {}", pool.len());
        let first = &pool[0];
        assert_ne!(first.id, "");
        assert_ne!(first.spotify_id, "");
        assert_ne!(first.title, "");
        assert_ne!(first.artist, "");
        assert!(first.year >= 1900);
        assert!(find_bundled_album_by_id(&first.id).is_some());
    }
}
