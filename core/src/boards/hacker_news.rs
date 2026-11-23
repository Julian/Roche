use std::{collections::HashSet, str::FromStr};

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use url::Url;

use super::BoardOrderMode;
use crate::{media_urls::canonical_arxiv_abstract_url, url_guard::parse_public_url};

pub const HN_WEB_HOST: &str = "news.ycombinator.com";
pub const HN_API_HOST: &str = "hacker-news.firebaseio.com";

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum HackerNewsSection {
    #[default]
    Top,
    Best,
    Ask,
    Show,
    New,
}

impl HackerNewsSection {
    pub const ALL: [Self; 5] = [Self::Top, Self::Best, Self::Ask, Self::Show, Self::New];

    #[must_use]
    pub const fn source_key(self) -> &'static str {
        match self {
            Self::Top => "top",
            Self::Best => "best",
            Self::Ask => "ask",
            Self::Show => "show",
            Self::New => "new",
        }
    }

    #[must_use]
    pub const fn source_url(self) -> &'static str {
        match self {
            Self::Top => "https://news.ycombinator.com/",
            Self::Best => "https://news.ycombinator.com/best",
            Self::Ask => "https://news.ycombinator.com/ask",
            Self::Show => "https://news.ycombinator.com/show",
            Self::New => "https://news.ycombinator.com/newest",
        }
    }

    #[must_use]
    pub const fn api_stories_url(self) -> &'static str {
        match self {
            Self::Top => "https://hacker-news.firebaseio.com/v0/topstories.json",
            Self::Best => "https://hacker-news.firebaseio.com/v0/beststories.json",
            Self::Ask => "https://hacker-news.firebaseio.com/v0/askstories.json",
            Self::Show => "https://hacker-news.firebaseio.com/v0/showstories.json",
            Self::New => "https://hacker-news.firebaseio.com/v0/newstories.json",
        }
    }

    #[must_use]
    pub const fn default_title(self) -> &'static str {
        match self {
            Self::Top => "Hacker News",
            Self::Best => "Hacker News · Best",
            Self::Ask => "Ask HN",
            Self::Show => "Show HN",
            Self::New => "Hacker News · New",
        }
    }

    #[must_use]
    pub const fn default_order_mode(self) -> BoardOrderMode {
        match self {
            Self::New => BoardOrderMode::Chronological,
            Self::Top | Self::Best | Self::Ask | Self::Show => BoardOrderMode::Ranked,
        }
    }

    #[must_use]
    pub fn canonical_config_json(self) -> String {
        format!(r#"{{"section":"{}"}}"#, self.source_key())
    }

    pub fn parse_flexible(raw: &str) -> Result<Self> {
        let norm = raw.trim().to_ascii_lowercase().replace('-', "_");
        match norm.as_str() {
            "top" | "news" | "front" | "frontpage" | "topstories" => Ok(Self::Top),
            "best" | "beststories" => Ok(Self::Best),
            "ask" | "ask_hn" | "askstories" => Ok(Self::Ask),
            "show" | "show_hn" | "showstories" => Ok(Self::Show),
            "new" | "newest" | "newstories" => Ok(Self::New),
            _ => anyhow::bail!("Unknown Hacker News section '{raw}'"),
        }
    }

    /// Detects a Hacker News board section from a `news.ycombinator.com` or
    /// `hacker-news.firebaseio.com` stories URL.
    #[must_use]
    pub fn detect_from_url(raw: &str) -> Option<Self> {
        let parsed = Url::parse(raw.trim()).ok()?;
        if !matches!(parsed.scheme(), "http" | "https") {
            return None;
        }
        let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
        let path = parsed.path().trim_end_matches('/').to_ascii_lowercase();
        if host == HN_WEB_HOST {
            match path.as_str() {
                "" | "/news" | "/front" => Some(Self::Top),
                "/best" => Some(Self::Best),
                "/ask" => Some(Self::Ask),
                "/show" | "/shownew" => Some(Self::Show),
                "/newest" | "/new" => Some(Self::New),
                _ => None,
            }
        } else if host == HN_API_HOST {
            match path.as_str() {
                "/v0/topstories.json" => Some(Self::Top),
                "/v0/beststories.json" => Some(Self::Best),
                "/v0/askstories.json" => Some(Self::Ask),
                "/v0/showstories.json" => Some(Self::Show),
                "/v0/newstories.json" => Some(Self::New),
                _ => None,
            }
        } else {
            None
        }
    }
}

impl FromStr for HackerNewsSection {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self> {
        Self::parse_flexible(s)
    }
}

/// Returns `true` when `raw` is a positive base-10 ASCII Hacker News item ID without leading zeros.
#[must_use]
pub fn is_valid_hn_item_id(raw: &str) -> bool {
    !raw.is_empty()
        && raw.len() <= 20
        && !raw.starts_with('0')
        && raw.bytes().all(|b| b.is_ascii_digit())
}

/// Extracts a positive Hacker News item ID from either a bare digit string or a
/// `http(s)://news.ycombinator.com/item?id=<digits>` URL.
#[must_use]
pub fn extract_hn_item_id(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if is_valid_hn_item_id(trimmed) {
        return Some(trimmed.to_owned());
    }
    let parsed = Url::parse(trimmed).ok()?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return None;
    }
    let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
    if host != HN_WEB_HOST {
        return None;
    }
    let path = parsed.path().trim_end_matches('/');
    if path != "/item" {
        return None;
    }
    parsed
        .query_pairs()
        .find(|(k, _)| k == "id")
        .map(|(_, v)| v.trim().to_owned())
        .filter(|v| is_valid_hn_item_id(v))
}

#[must_use]
pub fn hn_discussion_url(id: u64) -> String {
    format!("https://news.ycombinator.com/item?id={id}")
}

#[must_use]
pub fn hn_item_api_url(id: u64) -> String {
    format!("https://hacker-news.firebaseio.com/v0/item/{id}.json")
}

/// Extracts a normalized display domain (lowercase, trailing dot and leading `www.` stripped)
/// from an HTTP(S) URL.
#[must_use]
pub fn extract_domain(raw_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_url.trim()).ok()?;
    let host = parsed.host_str()?.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return None;
    }
    let stripped =
        host.strip_prefix("www.").filter(|rest| rest.contains('.')).unwrap_or(host.as_str());
    Some(stripped.to_owned())
}

/// Parses a Hacker News Firebase stories endpoint JSON array (`[42123456, ...]`), filtering out
/// zero values and preserving first-occurrence order.
pub fn parse_hn_story_ids(json: &str) -> Result<Vec<u64>> {
    let raw_ids: Vec<u64> = serde_json::from_str(json)?;
    let mut seen = HashSet::with_capacity(raw_ids.len());
    let mut out = Vec::with_capacity(raw_ids.len());
    for id in raw_ids {
        if id > 0 && seen.insert(id) {
            out.push(id);
        }
    }
    Ok(out)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParsedHnItem {
    pub external_id: String,
    pub title: String,
    pub url: Url,
    pub discussion_url: String,
    pub author: Option<String>,
    pub domain: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub score: Option<i64>,
    pub comment_count: Option<i64>,
    pub raw_self_text: Option<String>,
    pub item_type: String,
}

#[derive(Debug, Deserialize)]
struct RawHnItem {
    id: Option<u64>,
    #[serde(default)]
    deleted: bool,
    #[serde(default)]
    dead: bool,
    #[serde(rename = "type")]
    item_type: Option<String>,
    by: Option<String>,
    time: Option<i64>,
    title: Option<String>,
    url: Option<String>,
    text: Option<String>,
    score: Option<i64>,
    descendants: Option<i64>,
}

/// Parses a single Hacker News Firebase item JSON payload.
///
/// Returns `Ok(None)` gracefully for `null`, deleted, dead, untitled, non-story (e.g. `comment`),
/// or SSRF-blocked external URL items so board polling skips bad items without aborting the batch.
pub fn parse_hn_item_json(json: &str) -> Result<Option<ParsedHnItem>> {
    let raw_opt: Option<RawHnItem> = serde_json::from_str(json)?;
    let Some(raw) = raw_opt else {
        return Ok(None);
    };
    if raw.deleted || raw.dead {
        return Ok(None);
    }
    let Some(id) = raw.id.filter(|&id| id > 0) else {
        return Ok(None);
    };
    let item_type = raw
        .item_type
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("story")
        .to_ascii_lowercase();
    if matches!(item_type.as_str(), "comment" | "pollopt") {
        return Ok(None);
    }
    let Some(title) = raw
        .title
        .as_deref()
        .map(|t| t.split_whitespace().collect::<Vec<_>>().join(" "))
        .filter(|t| !t.is_empty())
    else {
        return Ok(None);
    };

    let discussion_url = hn_discussion_url(id);
    let raw_external_url = raw.url.as_deref().map(str::trim).filter(|s| !s.is_empty());
    let (url, domain) = if let Some(ext) = raw_external_url {
        let Ok(parsed) = parse_public_url(ext) else {
            return Ok(None);
        };
        let canonical = canonical_arxiv_abstract_url(&parsed).unwrap_or(parsed);
        let domain = extract_domain(canonical.as_str());
        (canonical, domain)
    } else {
        let Ok(parsed_disc) = parse_public_url(&discussion_url) else {
            return Ok(None);
        };
        (parsed_disc, None)
    };

    let author = raw.by.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
    let published_at = raw.time.filter(|&t| t > 0).and_then(|t| DateTime::from_timestamp(t, 0));
    let score = raw.score.filter(|&s| s >= 0);
    let comment_count = raw.descendants.filter(|&c| c >= 0);
    let raw_self_text =
        raw.text.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);

    Ok(Some(ParsedHnItem {
        external_id: id.to_string(),
        title,
        url,
        discussion_url,
        author,
        domain,
        published_at,
        score,
        comment_count,
        raw_self_text,
        item_type,
    }))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_section_metadata_and_url_detection() {
        assert_eq!(HackerNewsSection::Top.source_key(), "top");
        assert_eq!(HackerNewsSection::Top.source_url(), "https://news.ycombinator.com/");
        assert_eq!(
            HackerNewsSection::Top.api_stories_url(),
            "https://hacker-news.firebaseio.com/v0/topstories.json"
        );
        assert_eq!(HackerNewsSection::Top.default_title(), "Hacker News");
        assert_eq!(HackerNewsSection::Top.canonical_config_json(), r#"{"section":"top"}"#);

        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/"),
            Some(HackerNewsSection::Top)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/news"),
            Some(HackerNewsSection::Top)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/best"),
            Some(HackerNewsSection::Best)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/ask"),
            Some(HackerNewsSection::Ask)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/show"),
            Some(HackerNewsSection::Show)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/newest"),
            Some(HackerNewsSection::New)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url(
                "https://hacker-news.firebaseio.com/v0/showstories.json"
            ),
            Some(HackerNewsSection::Show)
        );
        assert_eq!(
            HackerNewsSection::detect_from_url("https://news.ycombinator.com/item?id=42123456"),
            None
        );
        assert_eq!(HackerNewsSection::detect_from_url("https://example.com/"), None);
    }

    #[test]
    fn test_parse_hn_story_ids_dedups_and_skips_zero() {
        let ids = parse_hn_story_ids("[42, 0, 100, 42, 999]").unwrap();
        assert_eq!(ids, vec![42, 100, 999]);
    }

    #[test]
    fn test_parse_hn_item_json_handles_stories_ask_hn_ssrf_and_deleted() {
        let story_json = r#"{
            "id": 42123456,
            "type": "story",
            "by": "pg",
            "time": 1732386600,
            "title": "Example  Story",
            "url": "https://www.example.com/post",
            "score": 250,
            "descendants": 142
        }"#;
        let item = parse_hn_item_json(story_json).unwrap().unwrap();
        assert_eq!(item.external_id, "42123456");
        assert_eq!(item.title, "Example Story");
        assert_eq!(item.url.as_str(), "https://www.example.com/post");
        assert_eq!(item.discussion_url, "https://news.ycombinator.com/item?id=42123456");
        assert_eq!(item.domain.as_deref(), Some("example.com"));
        assert_eq!(item.author.as_deref(), Some("pg"));
        assert_eq!(item.score, Some(250));
        assert_eq!(item.comment_count, Some(142));

        // Ask HN self-post falls back to discussion_url and preserves raw_self_text
        let ask_json = r#"{
            "id": 42123457,
            "type": "story",
            "by": "alice",
            "time": 1732386700,
            "title": "Ask HN: Favorite systems papers?",
            "text": "<p>Looking for recommendations.</p>",
            "score": 88,
            "descendants": 41
        }"#;
        let ask = parse_hn_item_json(ask_json).unwrap().unwrap();
        assert_eq!(ask.url.as_str(), "https://news.ycombinator.com/item?id=42123457");
        assert_eq!(ask.domain, None);
        assert_eq!(ask.raw_self_text.as_deref(), Some("<p>Looking for recommendations.</p>"));

        // arXiv link canonicalizes away version suffix
        let arxiv_json = r#"{
            "id": 42123458,
            "type": "story",
            "title": "Cool Paper",
            "url": "https://arxiv.org/abs/2301.07041v2"
        }"#;
        let paper = parse_hn_item_json(arxiv_json).unwrap().unwrap();
        assert_eq!(paper.url.as_str(), "https://arxiv.org/abs/2301.07041");

        // SSRF external URLs are rejected
        for bad_url in [
            "http://127.0.0.1:8080/secret",
            "http://169.254.169.254/latest/meta-data",
            "http://router.local/admin",
            "http://my-node.tail1234.ts.net/",
            "javascript:alert(1)",
        ] {
            let bad = serde_json::json!({
                "id": 999,
                "type": "story",
                "title": "SSRF",
                "url": bad_url
            })
            .to_string();
            assert!(parse_hn_item_json(&bad).unwrap().is_none(), "must reject {bad_url}");
        }

        // Deleted / dead / null / untitled / comment skipped gracefully
        assert!(parse_hn_item_json("null").unwrap().is_none());
        assert!(parse_hn_item_json(r#"{"id":1,"deleted":true,"title":"Gone"}"#).unwrap().is_none());
        assert!(parse_hn_item_json(r#"{"id":2,"dead":true,"title":"Dead"}"#).unwrap().is_none());
        assert!(parse_hn_item_json(r#"{"id":3,"type":"story","title":"   "}"#).unwrap().is_none());
        assert!(
            parse_hn_item_json(r#"{"id":4,"type":"comment","title":"Not a story"}"#)
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn test_extract_domain() {
        assert_eq!(
            extract_domain("https://www.rust-lang.org/learn").as_deref(),
            Some("rust-lang.org")
        );
        assert_eq!(
            extract_domain("https://Blog.Example.COM./post").as_deref(),
            Some("blog.example.com")
        );
        assert_eq!(extract_domain("https://www.com/").as_deref(), Some("www.com"));
        assert_eq!(extract_domain("not-a-url"), None);
    }
}
