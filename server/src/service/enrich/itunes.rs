use std::collections::HashMap;

use serde_json::Value;
use url::Url;

use super::{ArtistMetadataFetcher, normalize_artist_tokens, strip_leading_the};
use crate::{
    db::{CatalogEntry, EntryDetailView, strip_album_edition_suffix},
    domain::EntryKind,
    util::url_guard::sanitize_public_http_href,
};

pub(super) const MAX_ITUNES_JSON_BYTES: usize = 512 * 1024;
pub(super) const MAX_ALBUM_TRACKS: usize = 120;
const MAX_DISCOGRAPHY_ALBUMS: usize = 40;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AlbumTrack {
    pub number: u32,
    pub disc: u32,
    pub title: String,
    pub duration_ms: Option<u64>,
    pub artists: Vec<String>,
    pub spotify_uri: Option<String>,
    pub entry_id: Option<String>,
}

impl AlbumTrack {
    #[must_use]
    pub fn spotify_web_url(&self) -> Option<String> {
        let uri = self.spotify_uri.as_deref()?.trim();
        let id = uri.strip_prefix("spotify:track:")?;
        let valid =
            !id.is_empty() && id.len() <= 64 && id.bytes().all(|b| b.is_ascii_alphanumeric());
        valid.then(|| format!("https://open.spotify.com/track/{id}"))
    }
}

/// Returns the album's tracks from either explicit child `Song` entries or cached `metadata.tracks`.
#[must_use]
pub fn extract_album_tracks(detail: &EntryDetailView) -> Vec<AlbumTrack> {
    let song_children: Vec<&CatalogEntry> =
        detail.children.iter().filter(|c| c.kind == EntryKind::Song).collect();
    if !song_children.is_empty() {
        return song_children
            .into_iter()
            .enumerate()
            .map(|(idx, child)| {
                let disc = child
                    .metadata
                    .get("disc_number")
                    .and_then(Value::as_u64)
                    .and_then(|v| u32::try_from(v).ok())
                    .filter(|&d| d > 0)
                    .unwrap_or(1);
                let number = child
                    .metadata
                    .get("track_number")
                    .and_then(Value::as_u64)
                    .and_then(|v| u32::try_from(v).ok())
                    .filter(|&n| n > 0)
                    .unwrap_or_else(|| u32::try_from(idx + 1).unwrap_or(1));
                let duration_ms =
                    child.metadata.get("duration_ms").and_then(Value::as_u64).filter(|&ms| ms > 0);
                let spotify_uri = child
                    .identifier("spotify")
                    .map(str::trim)
                    .filter(|s| s.starts_with("spotify:track:"))
                    .map(ToOwned::to_owned);
                AlbumTrack {
                    number,
                    disc,
                    title: child.title.clone(),
                    duration_ms,
                    artists: child.creators.clone(),
                    spotify_uri,
                    entry_id: Some(child.id.clone()),
                }
            })
            .collect();
    }

    let Some(arr) = detail.entry.metadata.get("tracks").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut tracks = Vec::with_capacity(arr.len().min(MAX_ALBUM_TRACKS));
    for (idx, item) in arr.iter().take(MAX_ALBUM_TRACKS).enumerate() {
        let Some(title) = item.get("title").and_then(Value::as_str).map(str::trim) else {
            continue;
        };
        if title.is_empty() {
            continue;
        }
        let disc = item
            .get("disc")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&d| d > 0)
            .unwrap_or(1);
        let number = item
            .get("number")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&n| n > 0)
            .unwrap_or_else(|| u32::try_from(idx + 1).unwrap_or(1));
        let duration_ms = item.get("duration_ms").and_then(Value::as_u64).filter(|&ms| ms > 0);
        let artists = item.get("artists").and_then(Value::as_array).map_or_default(|a| {
            a.iter()
                .filter_map(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned)
                .collect()
        });
        let spotify_uri = item
            .get("spotify_uri")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| s.starts_with("spotify:track:"))
            .map(ToOwned::to_owned);
        tracks.push(AlbumTrack {
            number,
            disc,
            title: title.to_owned(),
            duration_ms,
            artists,
            spotify_uri,
            entry_id: None,
        });
    }
    tracks
}

pub(super) fn extract_apple_music_album_id(entry: &CatalogEntry) -> Option<String> {
    let raw = entry.identifier("apple_music")?.trim();
    let clean = raw.rsplit('/').next().unwrap_or(raw).split('?').next().unwrap_or(raw).trim();
    let valid = !clean.is_empty() && clean.len() <= 32 && clean.bytes().all(|b| b.is_ascii_digit());
    valid.then(|| clean.to_owned())
}

#[must_use]
pub fn parse_itunes_lookup_tracks(json_str: &str) -> Vec<AlbumTrack> {
    let Ok(val) = serde_json::from_str::<Value>(json_str) else {
        return Vec::new();
    };
    let Some(results) = val.get("results").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for item in results {
        if item.get("wrapperType").and_then(Value::as_str) != Some("track") {
            continue;
        }
        if item.get("kind").and_then(Value::as_str).is_some_and(|k| k != "song") {
            continue;
        }
        let Some(title) = item.get("trackName").and_then(Value::as_str).map(str::trim) else {
            continue;
        };
        if title.is_empty() {
            continue;
        }
        let disc = item
            .get("discNumber")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&d| d > 0)
            .unwrap_or(1);
        let number = item
            .get("trackNumber")
            .and_then(Value::as_u64)
            .and_then(|v| u32::try_from(v).ok())
            .filter(|&n| n > 0)
            .unwrap_or_else(|| u32::try_from(out.len() + 1).unwrap_or(1));
        let duration_ms = item.get("trackTimeMillis").and_then(Value::as_u64).filter(|&ms| ms > 0);
        let artists = item
            .get("artistName")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map_or_default(|a| vec![a.to_owned()]);
        out.push(AlbumTrack {
            number,
            disc,
            title: title.to_owned(),
            duration_ms,
            artists,
            spotify_uri: None,
            entry_id: None,
        });
        if out.len() >= MAX_ALBUM_TRACKS {
            break;
        }
    }
    out.sort_by_key(|t| (t.disc, t.number));
    out
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscographyAlbum {
    pub title: String,
    pub release_year: Option<i32>,
    pub thumbnail_url: Option<String>,
    pub apple_music_id: Option<String>,
}

#[must_use]
pub fn normalize_album_match_key(title: &str) -> String {
    let stripped = strip_album_edition_suffix(title);
    let norm = normalize_artist_tokens(&stripped);
    strip_leading_the(&norm).to_owned()
}

#[must_use]
pub fn extract_artist_discography(entry: &CatalogEntry) -> Vec<DiscographyAlbum> {
    let Some(arr) = entry.metadata.get("discography").and_then(Value::as_array) else {
        return Vec::new();
    };
    arr.iter()
        .take(MAX_DISCOGRAPHY_ALBUMS)
        .filter_map(|item| {
            let title = item.get("title").and_then(Value::as_str).map(str::trim)?;
            if title.is_empty() {
                return None;
            }
            let release_year = item
                .get("release_year")
                .and_then(Value::as_i64)
                .and_then(|y| i32::try_from(y).ok())
                .filter(|&y| (1900..=2100).contains(&y));
            let thumbnail_url = item
                .get("thumbnail_url")
                .and_then(Value::as_str)
                .and_then(sanitize_public_http_href)
                .map(ToOwned::to_owned);
            let apple_music_id = item
                .get("apple_music_id")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
                .map(ToOwned::to_owned);
            Some(DiscographyAlbum {
                title: title.to_owned(),
                release_year,
                thumbnail_url,
                apple_music_id,
            })
        })
        .collect()
}

pub(super) fn serialize_discography(albums: &[DiscographyAlbum]) -> Vec<Value> {
    albums
        .iter()
        .map(|a| {
            let mut obj = serde_json::Map::new();
            obj.insert("title".to_owned(), Value::from(a.title.clone()));
            if let Some(yr) = a.release_year {
                obj.insert("release_year".to_owned(), Value::from(yr));
            }
            if let Some(thumb) = &a.thumbnail_url {
                obj.insert("thumbnail_url".to_owned(), Value::from(thumb.clone()));
            }
            if let Some(am_id) = &a.apple_music_id {
                obj.insert("apple_music_id".to_owned(), Value::from(am_id.clone()));
            }
            Value::Object(obj)
        })
        .collect()
}

pub(super) async fn resolve_itunes_artist_discography<M: ArtistMetadataFetcher>(
    artist_name: &str,
    fetcher: &M,
) -> Vec<DiscographyAlbum> {
    let trimmed = artist_name.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let encoded: String = url::form_urlencoded::byte_serialize(trimmed.as_bytes()).collect();
    let url_str = format!(
        "https://itunes.apple.com/search?term={encoded}&entity=album&attribute=artistTerm&limit=50",
    );
    let Ok(url) = Url::parse(&url_str) else {
        return Vec::new();
    };
    let Some(json_str) = fetcher.fetch_text(&url, MAX_ITUNES_JSON_BYTES).await else {
        return Vec::new();
    };
    parse_itunes_artist_discography(&json_str, trimmed)
}

#[must_use]
pub fn parse_itunes_artist_discography(json_str: &str, artist_name: &str) -> Vec<DiscographyAlbum> {
    let Ok(val) = serde_json::from_str::<Value>(json_str) else {
        return Vec::new();
    };
    let Some(results) = val.get("results").and_then(Value::as_array) else {
        return Vec::new();
    };
    let target_artist = strip_leading_the(&normalize_artist_tokens(artist_name)).to_owned();
    if target_artist.is_empty() {
        return Vec::new();
    }

    let mut by_key: HashMap<String, DiscographyAlbum> = HashMap::new();
    for item in results {
        if item.get("wrapperType").and_then(Value::as_str) != Some("collection") {
            continue;
        }
        if item.get("collectionType").and_then(Value::as_str).is_some_and(|t| t != "Album") {
            continue;
        }
        let Some(found_artist) = item.get("artistName").and_then(Value::as_str) else {
            continue;
        };
        if strip_leading_the(&normalize_artist_tokens(found_artist)) != target_artist {
            continue;
        }
        let Some(raw_title) = item.get("collectionName").and_then(Value::as_str).map(str::trim)
        else {
            continue;
        };
        let lower_title = raw_title.to_ascii_lowercase();
        if raw_title.is_empty()
            || lower_title.ends_with("- single")
            || lower_title.ends_with("- ep")
            || lower_title.contains("karaoke")
        {
            continue;
        }
        let clean_title = strip_album_edition_suffix(raw_title);
        let display_title = if clean_title.is_empty() { raw_title.to_owned() } else { clean_title };
        let key = normalize_album_match_key(&display_title);
        if key.is_empty() {
            continue;
        }
        let release_year = item
            .get("releaseDate")
            .and_then(Value::as_str)
            .and_then(|d| d.get(..4))
            .and_then(|y| y.parse::<i32>().ok())
            .filter(|&y| (1900..=2100).contains(&y));
        let thumbnail_url = item
            .get("artworkUrl100")
            .and_then(Value::as_str)
            .map(|u| u.replace("100x100bb", "600x600bb"))
            .and_then(|u| sanitize_public_http_href(&u).map(ToOwned::to_owned));
        let apple_music_id =
            item.get("collectionId").and_then(Value::as_u64).map(|id| id.to_string());

        let candidate =
            DiscographyAlbum { title: display_title, release_year, thumbnail_url, apple_music_id };
        match by_key.get_mut(&key) {
            Some(existing) => {
                let earlier = match (candidate.release_year, existing.release_year) {
                    (Some(cy), Some(ey)) => cy < ey,
                    (Some(_), None) => true,
                    _ => false,
                };
                if earlier {
                    existing.release_year = candidate.release_year;
                }
                if existing.thumbnail_url.is_none() && candidate.thumbnail_url.is_some() {
                    existing.thumbnail_url = candidate.thumbnail_url;
                }
            }
            None => {
                by_key.insert(key, candidate);
            }
        }
    }

    let mut out: Vec<DiscographyAlbum> = by_key.into_values().collect();
    out.sort_by(|a, b| {
        b.release_year
            .cmp(&a.release_year)
            .then_with(|| a.title.to_lowercase().cmp(&b.title.to_lowercase()))
    });
    out.truncate(MAX_DISCOGRAPHY_ALBUMS);
    out
}
