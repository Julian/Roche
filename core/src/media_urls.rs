use std::path::Path;

use anyhow::Result;
use url::Url;

use crate::url_guard::parse_public_url;

#[must_use]
pub fn is_arxiv_host(host: Option<&str>) -> bool {
    host.is_some_and(|raw| {
        let trimmed = raw.trim_end_matches('.');
        trimmed.eq_ignore_ascii_case("arxiv.org")
            || trimmed.eq_ignore_ascii_case("www.arxiv.org")
            || trimmed.eq_ignore_ascii_case("rss.arxiv.org")
            || trimmed.eq_ignore_ascii_case("export.arxiv.org")
    })
}

#[must_use]
pub fn is_valid_arxiv_id(candidate: &str) -> bool {
    !candidate.is_empty()
        && candidate.len() <= 64
        && !candidate.contains("..")
        && !candidate.starts_with('/')
        && !candidate.ends_with('/')
        && !candidate.starts_with('.')
        && !candidate.ends_with('.')
        && candidate.matches('/').count() <= 1
        && candidate
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-' | b'/'))
}

#[must_use]
pub fn extract_arxiv_id(url_or_id: &str) -> Option<String> {
    let trimmed = url_or_id.trim();
    let is_http = trimmed.get(..7).is_some_and(|p| p.eq_ignore_ascii_case("http://"))
        || trimmed.get(..8).is_some_and(|p| p.eq_ignore_ascii_case("https://"));
    if is_http {
        let parsed = Url::parse(trimmed).ok()?;
        if !is_arxiv_host(parsed.host_str()) {
            return None;
        }
        let path = parsed.path();
        if !path.starts_with("/abs/") && !path.starts_with("/pdf/") {
            return None;
        }
    }
    let stripped_oai = trimmed
        .strip_prefix("oai:arXiv.org:")
        .or_else(|| trimmed.strip_prefix("arXiv:"))
        .or_else(|| trimmed.strip_prefix("arxiv:"))
        .unwrap_or(trimmed);

    let id_part = stripped_oai
        .find("/abs/")
        .map(|idx| &stripped_oai[idx + "/abs/".len()..])
        .or_else(|| stripped_oai.find("/pdf/").map(|idx| &stripped_oai[idx + "/pdf/".len()..]))
        .unwrap_or(stripped_oai);

    let cleaned = id_part.split(&['?', '#'][..]).next().unwrap_or(id_part);
    let cleaned = cleaned.strip_suffix(".pdf").unwrap_or(cleaned).trim_matches('/');

    is_valid_arxiv_id(cleaned).then(|| cleaned.to_string())
}

/// Strips a trailing arXiv version suffix (`v1`, `v2`, ...) from an arXiv identifier
/// (for example `"2301.07041v2"` -> `"2301.07041"`, `"hep-th/9901001v1"` -> `"hep-th/9901001"`).
#[must_use]
pub fn strip_arxiv_version(arxiv_id: &str) -> &str {
    let trimmed = arxiv_id.trim();
    if let Some((base, suffix)) = trimmed.rsplit_once('v')
        && !base.is_empty()
        && !suffix.is_empty()
        && suffix.bytes().all(|b| b.is_ascii_digit())
    {
        return base;
    }
    trimmed
}

/// Canonicalizes an arXiv `/abs/` URL to HTTPS without a `vN` version suffix
/// (`https://arxiv.org/abs/<id>`) so Rust and Android generate identical deterministic item UUIDs.
#[must_use]
pub fn canonical_arxiv_abstract_url(raw_url: &Url) -> Option<Url> {
    if !is_arxiv_host(raw_url.host_str()) || !raw_url.path().starts_with("/abs/") {
        return None;
    }
    let arxiv_id = extract_arxiv_id(raw_url.as_str())?;
    let versionless = strip_arxiv_version(&arxiv_id);
    if !is_valid_arxiv_id(versionless) {
        return None;
    }
    Url::parse(&format!("https://arxiv.org/abs/{versionless}")).ok()
}

#[must_use]
pub fn arxiv_pdf_url(paper_url: &Url) -> Option<Url> {
    if !is_arxiv_host(paper_url.host_str()) {
        return None;
    }
    let path = paper_url.path();
    if !path.starts_with("/abs/") && !path.starts_with("/pdf/") {
        return None;
    }
    let arxiv_id = extract_arxiv_id(paper_url.as_str())?;
    if !is_valid_arxiv_id(&arxiv_id) {
        return None;
    }
    Url::parse(&format!("https://arxiv.org/pdf/{arxiv_id}.pdf")).ok()
}

#[must_use]
pub fn is_youtube_host(host: Option<&str>) -> bool {
    host.is_some_and(|raw| {
        let trimmed = raw.trim_end_matches('.');
        trimmed.eq_ignore_ascii_case("youtube.com")
            || trimmed.eq_ignore_ascii_case("www.youtube.com")
            || trimmed.eq_ignore_ascii_case("m.youtube.com")
            || trimmed.eq_ignore_ascii_case("music.youtube.com")
    })
}

fn is_youtu_be_host(host: Option<&str>) -> bool {
    host.is_some_and(|raw| {
        let trimmed = raw.trim_end_matches('.');
        trimmed.eq_ignore_ascii_case("youtu.be") || trimmed.eq_ignore_ascii_case("www.youtu.be")
    })
}

#[must_use]
pub fn is_youtube_url(url: &Url) -> bool {
    is_youtube_host(url.host_str()) || is_youtu_be_host(url.host_str())
}

#[must_use]
pub fn is_youtube_feed_url(url: &Url) -> bool {
    is_youtube_host(url.host_str()) && url.path() == "/feeds/videos.xml"
}

#[must_use]
pub fn is_valid_youtube_channel_id(candidate: &str) -> bool {
    candidate.len() == 24
        && candidate.starts_with("UC")
        && candidate.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

#[must_use]
pub fn is_valid_youtube_video_id(candidate: &str) -> bool {
    !candidate.is_empty()
        && candidate.len() <= 64
        && candidate.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

#[must_use]
pub fn extract_youtube_channel_id(input: &str) -> Option<String> {
    let trimmed = input.trim();

    if is_valid_youtube_channel_id(trimmed) {
        return Some(trimmed.to_string());
    }

    let parsed = Url::parse(trimmed).ok()?;
    if !is_youtube_host(parsed.host_str()) {
        return None;
    }

    for (key, value) in parsed.query_pairs() {
        if key == "channel_id" && is_valid_youtube_channel_id(&value) {
            return Some(value.into_owned());
        }
    }

    let mut segments = parsed.path_segments()?;
    while let Some(seg) = segments.next() {
        if seg == "channel"
            && let Some(candidate) = segments.next()
            && is_valid_youtube_channel_id(candidate)
        {
            return Some(candidate.to_string());
        }
    }

    None
}

pub fn youtube_feed_url_for_channel_id(channel_id: &str) -> Result<Url> {
    parse_public_url(&format!("https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"))
}

#[must_use]
pub fn extract_youtube_video_id(url: &Url) -> Option<String> {
    let host = url.host_str()?;
    if is_youtube_host(Some(host)) {
        if url.path() == "/watch" {
            for (key, value) in url.query_pairs() {
                if key == "v" && is_valid_youtube_video_id(&value) {
                    return Some(value.into_owned());
                }
            }
        } else if let Some(mut segments) = url.path_segments()
            && let Some(prefix) = segments.next()
            && matches!(prefix, "shorts" | "embed" | "v")
            && let Some(candidate) = segments.next()
            && is_valid_youtube_video_id(candidate)
        {
            return Some(candidate.to_string());
        }
    } else if is_youtu_be_host(Some(host))
        && let Some(mut segments) = url.path_segments()
        && let Some(candidate) = segments.next()
        && is_valid_youtube_video_id(candidate)
    {
        return Some(candidate.to_string());
    }
    None
}

#[must_use]
pub fn canonical_youtube_watch_url(url: &Url) -> Option<Url> {
    let video_id = extract_youtube_video_id(url)?;
    Url::parse(&format!("https://www.youtube.com/watch?v={video_id}")).ok()
}

#[must_use]
pub fn youtube_thumbnail_url(url: &Url) -> Option<String> {
    extract_youtube_video_id(url).map(|vid| format!("https://i.ytimg.com/vi/{vid}/hqdefault.jpg"))
}

#[must_use]
pub fn is_direct_pdf_url(url: &Url) -> bool {
    let raw_path = url.path();
    Path::new(raw_path).extension().is_some_and(|ext| ext.eq_ignore_ascii_case("pdf"))
        || raw_path.to_ascii_lowercase().ends_with("/pdf")
}

#[must_use]
pub fn is_direct_audio_url(url: &Url) -> bool {
    let raw_path = url.path();
    Path::new(raw_path).extension().is_some_and(|ext| {
        matches!(
            ext.to_str().unwrap_or("").to_ascii_lowercase().as_str(),
            "mp3" | "m4a" | "aac" | "ogg" | "oga" | "opus" | "wav" | "flac"
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_arxiv_id_rejects_query_injection() {
        assert_eq!(extract_arxiv_id("2301.07041v2").as_deref(), Some("2301.07041v2"));
        assert_eq!(extract_arxiv_id("hep-th/9901001").as_deref(), Some("hep-th/9901001"));
        assert!(extract_arxiv_id("2301.07041&max_results=999").is_none());
        assert!(extract_arxiv_id("2301.07041%26search_query=all").is_none());
        assert!(extract_arxiv_id("../etc/passwd").is_none());
    }
}
