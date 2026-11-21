use std::collections::HashSet;

use quick_xml::{Reader, events::Event};
use serde_json::Value;
use url::Url;

use super::{
    ArtistMetadataFetcher,
    itunes::{AlbumTrack, MAX_ALBUM_TRACKS},
    normalize_artist_tokens,
};
use crate::{content::xml_util, db::CatalogEntry};

pub(super) const MAX_SPOTIFY_HTML_BYTES: usize = 2 * 1024 * 1024;

pub(super) async fn resolve_spotify_artist_from_children<M: ArtistMetadataFetcher>(
    artist_name: &str,
    children: &[CatalogEntry],
    fetcher: &M,
) -> Option<String> {
    for child in children.iter().filter(|c| child_spotify_web_url(c).is_some()).take(4) {
        let Some(url) = child_spotify_web_url(child) else {
            continue;
        };
        let Some(html) = fetcher.fetch_text(&url, MAX_SPOTIFY_HTML_BYTES).await else {
            continue;
        };
        let allow_single_fallback = child.creators.len() <= 1;
        if let Some(uri) =
            extract_spotify_artist_uri_from_album_html(&html, artist_name, allow_single_fallback)
        {
            return Some(uri);
        }
    }
    None
}

pub(super) fn child_spotify_web_url(child: &CatalogEntry) -> Option<Url> {
    let ident = child.identifiers.iter().find(|i| i.scheme.eq_ignore_ascii_case("spotify"))?;
    let ext = ident.external_url()?;
    let parsed = Url::parse(&ext).ok()?;
    let host = parsed.host_str()?.to_ascii_lowercase();
    if host != "open.spotify.com" && host != "play.spotify.com" {
        return None;
    }
    let path = parsed.path();
    (path.starts_with("/album/") || path.starts_with("/track/")).then_some(parsed)
}

pub(super) fn extract_spotify_album_id(entry: &CatalogEntry) -> Option<String> {
    let raw = entry.identifier("spotify")?.trim();
    let owned = if let Some(rest) = raw.strip_prefix("spotify:album:") {
        rest.to_owned()
    } else if let Ok(url) = Url::parse(raw) {
        url.path().strip_prefix("/album/")?.split('/').next()?.to_owned()
    } else {
        return None;
    };
    let clean = owned.split('?').next().unwrap_or(&owned).trim();
    let valid =
        !clean.is_empty() && clean.len() <= 64 && clean.bytes().all(|b| b.is_ascii_alphanumeric());
    valid.then(|| clean.to_owned())
}

#[must_use]
pub fn extract_spotify_artist_uri_from_album_html(
    html: &str,
    artist_name: &str,
    allow_single_fallback: bool,
) -> Option<String> {
    if artist_name.trim().eq_ignore_ascii_case("various artists") {
        return None;
    }

    // Use `ammonia` (backed by html5ever) to strip `<script>`/`<style>` blocks and emit clean
    // `<a>`, `<meta>`, and `<link>` elements before scanning with `quick_xml`.
    let mut sanitizer = ammonia::Builder::empty();
    sanitizer
        .tags(HashSet::from(["a", "meta", "link"]))
        .add_tag_attributes("a", ["href"])
        .add_tag_attributes("meta", ["content"])
        .add_tag_attributes("link", ["href"])
        .add_clean_content_tags(["script", "style"])
        .url_schemes(HashSet::from(["http", "https"]))
        .url_relative(ammonia::UrlRelative::PassThrough);
    let cleaned = sanitizer.clean(html).to_string();

    let target_norm = normalize_artist_tokens(artist_name);
    let mut reader = Reader::from_str(&cleaned);
    reader.config_mut().check_end_names = false;

    let mut active_anchor: Option<(String, String)> = None;
    let mut unique_fallback_ids: HashSet<String> = HashSet::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let tag_name = tag.name();
                let is_anchor = tag_name.as_ref().eq_ignore_ascii_case("a");
                for (k, v) in xml_util::iter_attrs(&tag) {
                    if (k.eq_ignore_ascii_case("href") || k.eq_ignore_ascii_case("content"))
                        && let Some(id) = extract_artist_id_from_attr(&v)
                    {
                        unique_fallback_ids.insert(id.clone());
                        if is_anchor && k.eq_ignore_ascii_case("href") {
                            active_anchor = Some((id, String::new()));
                        }
                    }
                }
            }
            Ok(Event::Empty(tag)) => {
                for (k, v) in xml_util::iter_attrs(&tag) {
                    if (k.eq_ignore_ascii_case("href") || k.eq_ignore_ascii_case("content"))
                        && let Some(id) = extract_artist_id_from_attr(&v)
                    {
                        unique_fallback_ids.insert(id);
                    }
                }
            }
            Ok(Event::Text(text)) => {
                if let Some((_, ref mut buf)) = active_anchor {
                    buf.push_str(&xml_util::decode_text(&text));
                }
            }
            Ok(Event::GeneralRef(entity)) => {
                if let Some((_, ref mut buf)) = active_anchor {
                    if entity.as_ref() == "nbsp" {
                        buf.push(' ');
                    } else {
                        buf.push_str(&xml_util::decode_entity(&entity));
                    }
                }
            }
            Ok(Event::End(tag)) if tag.name().as_ref().eq_ignore_ascii_case("a") => {
                if let Some((artist_id, inner)) = active_anchor.take()
                    && !target_norm.is_empty()
                    && normalize_artist_tokens(&inner) == target_norm
                {
                    return Some(format!("spotify:artist:{artist_id}"));
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            Ok(_) => {}
        }
    }

    if allow_single_fallback && unique_fallback_ids.len() == 1 {
        unique_fallback_ids.into_iter().next().map(|id| format!("spotify:artist:{id}"))
    } else {
        None
    }
}

fn extract_artist_id_from_attr(attr: &str) -> Option<String> {
    let trimmed = attr.trim();
    let after_marker = if let Some(rest) = trimmed.strip_prefix("/artist/") {
        rest
    } else {
        let idx = trimmed.find("open.spotify.com/artist/")?;
        &trimmed[idx + "open.spotify.com/artist/".len()..]
    };
    let token: String = after_marker.chars().take_while(char::is_ascii_alphanumeric).collect();
    is_valid_spotify_id(&token).then_some(token)
}

fn is_valid_spotify_id(id: &str) -> bool {
    (15..=30).contains(&id.len()) && id.bytes().all(|b| b.is_ascii_alphanumeric())
}

#[must_use]
pub fn parse_spotify_embed_tracks(html: &str) -> Vec<AlbumTrack> {
    let Some(script_idx) = html.find("id=\"__NEXT_DATA__\"") else {
        return Vec::new();
    };
    let after_id = &html[script_idx..];
    let Some(open_end) = after_id.find('>') else {
        return Vec::new();
    };
    let json_start = &after_id[open_end + 1..];
    let Some(close_idx) = json_start.find("</script>") else {
        return Vec::new();
    };
    let Ok(val) = serde_json::from_str::<Value>(json_start[..close_idx].trim()) else {
        return Vec::new();
    };
    let Some(list) =
        val.pointer("/props/pageProps/state/data/entity/trackList").and_then(Value::as_array)
    else {
        return Vec::new();
    };

    let mut out = Vec::with_capacity(list.len().min(MAX_ALBUM_TRACKS));
    for (idx, item) in list.iter().take(MAX_ALBUM_TRACKS).enumerate() {
        let Some(title) = item.get("title").and_then(Value::as_str).map(str::trim) else {
            continue;
        };
        if title.is_empty() {
            continue;
        }
        let duration_ms = item.get("duration").and_then(Value::as_u64).filter(|&ms| ms > 0);
        let subtitle = item.get("subtitle").and_then(Value::as_str).unwrap_or("");
        let artists: Vec<String> = subtitle
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ToOwned::to_owned)
            .collect();
        let spotify_uri = item
            .get("uri")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| s.starts_with("spotify:track:"))
            .map(ToOwned::to_owned);
        out.push(AlbumTrack {
            number: u32::try_from(idx + 1).unwrap_or(1),
            disc: 1,
            title: title.to_owned(),
            duration_ms,
            artists,
            spotify_uri,
            entry_id: None,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::extract_spotify_artist_uri_from_album_html;

    #[test]
    fn test_spotify_artist_html_parser_terminates_on_malformed_xml() {
        // Malformed unclosed tags / broken attribute syntax must terminate promptly (no infinite loop)
        let broken =
            r#"<a href="/artist/0123456789abcdefghijkl" broken="unterminated><<<&bogus;<<a"#;
        let _ = extract_spotify_artist_uri_from_album_html(broken, "Some Artist", true);
        let valid = r#"<div><a href="https://open.spotify.com/artist/4Z8W4fKeB5YxbusRsdQVPb">Radiohead</a></div>"#;
        assert_eq!(
            extract_spotify_artist_uri_from_album_html(valid, "Radiohead", true).as_deref(),
            Some("spotify:artist:4Z8W4fKeB5YxbusRsdQVPb")
        );
    }
}
