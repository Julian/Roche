pub mod arxiv;
pub mod opml;
pub mod rss;
pub mod youtube;

use anyhow::Result;
pub use arxiv::ArxivParser;
use chrono::{DateTime, SubsecRound, Utc};
pub use opml::OpmlDocument;
pub use rss::RssParser;
use serde::{Deserialize, Deserializer, Serialize};
use url::Url;
pub use youtube::YouTubeParser;

use crate::{
    domain::FeedType,
    util::{media_urls::is_direct_pdf_url, url_guard::parse_public_url},
};

pub const MAX_PARSED_FEED_ENTRIES: usize = 1_000;

/// Validated feed creation source: either a full [`Url`] or a 24-character `YouTube` channel ID (`UC...`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum FeedSource {
    Url(Url),
    YouTubeChannelId(String),
}

impl FeedSource {
    pub fn parse(raw: &str) -> Result<Self> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            anyhow::bail!("Feed URL cannot be empty");
        }
        if let Some(channel_id) = YouTubeParser::extract_channel_id(trimmed)
            && !trimmed.contains("://")
        {
            return Ok(Self::YouTubeChannelId(channel_id));
        }
        let normalized_url_str = if !trimmed.contains("://")
            && trimmed.contains('.')
            && !trimmed.chars().any(char::is_whitespace)
        {
            std::borrow::Cow::Owned(format!("https://{trimmed}"))
        } else {
            std::borrow::Cow::Borrowed(trimmed)
        };
        let url = parse_public_url(&normalized_url_str)?;
        Ok(Self::Url(url))
    }

    pub fn into_feed_url(self, requested_type: FeedType) -> Result<(FeedType, Url)> {
        match self {
            Self::YouTubeChannelId(channel_id) => match requested_type {
                FeedType::Youtube | FeedType::Rss => {
                    Ok((FeedType::Youtube, YouTubeParser::feed_url_for_channel_id(&channel_id)?))
                }
                FeedType::Arxiv | FeedType::Podcast => anyhow::bail!(
                    "Bare YouTube channel ID '{channel_id}' is only valid for youtube feeds"
                ),
            },
            Self::Url(url) => {
                let is_youtube_feed_url = YouTubeParser::is_youtube_feed_url(&url);
                match requested_type {
                    FeedType::Youtube => {
                        if !is_youtube_feed_url
                            && let Some(channel_id) =
                                YouTubeParser::extract_channel_id(url.as_str())
                        {
                            return Ok((
                                FeedType::Youtube,
                                YouTubeParser::feed_url_for_channel_id(&channel_id)?,
                            ));
                        }
                        if !YouTubeParser::is_youtube_host(url.host_str()) {
                            anyhow::bail!("YouTube feed URL must point to youtube.com");
                        }
                        Ok((FeedType::Youtube, url))
                    }
                    FeedType::Arxiv => {
                        if !ArxivParser::is_arxiv_host(url.host_str()) {
                            anyhow::bail!("arXiv feed URL must point to arxiv.org");
                        }
                        Self::validate_arxiv_feed_url(&url)?;
                        Ok((FeedType::Arxiv, url))
                    }
                    FeedType::Rss | FeedType::Podcast => {
                        if !is_youtube_feed_url
                            && let Some(channel_id) =
                                YouTubeParser::extract_channel_id(url.as_str())
                        {
                            return Ok((
                                FeedType::Youtube,
                                YouTubeParser::feed_url_for_channel_id(&channel_id)?,
                            ));
                        }
                        if is_youtube_feed_url {
                            return Ok((FeedType::Youtube, url));
                        }
                        if ArxivParser::is_arxiv_host(url.host_str()) {
                            Self::validate_arxiv_feed_url(&url)?;
                            return Ok((FeedType::Arxiv, url));
                        }
                        Ok((requested_type, url))
                    }
                }
            }
        }
    }

    fn validate_arxiv_feed_url(url: &Url) -> Result<()> {
        let path = url.path();
        if path.starts_with("/abs/") || path.starts_with("/pdf/") || is_direct_pdf_url(url) {
            anyhow::bail!("URL points to a single arXiv paper, not an arXiv feed");
        }
        Ok(())
    }
}

impl<'de> Deserialize<'de> for FeedSource {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let raw = String::deserialize(deserializer)?;
        Self::parse(&raw).map_err(serde::de::Error::custom)
    }
}

/// Shared helper to pick the preferred (`rel="alternate"`) validated HTTP(S) link from a `feed-rs` entry.
/// Excludes `rel="enclosure"` links so episode/article webpage permalinks (including `<guid>` / `<id>`)
/// take priority over media enclosures.
#[must_use]
pub fn select_entry_link(links: &[feed_rs::model::Link]) -> Option<Url> {
    links
        .iter()
        .filter(|l| l.rel.as_deref().is_none_or(|r| r.eq_ignore_ascii_case("alternate")))
        .find_map(|l| parse_public_url(&l.href).ok())
        .or_else(|| {
            links
                .iter()
                .filter(|l| l.rel.as_deref().is_none_or(|r| !r.eq_ignore_ascii_case("enclosure")))
                .find_map(|l| parse_public_url(&l.href).ok())
        })
}

/// Shared helper to extract the UTC publication/update timestamp (truncated to whole seconds) from a `feed-rs` entry.
#[must_use]
pub fn extract_entry_timestamp(entry: &feed_rs::model::Entry) -> Option<DateTime<Utc>> {
    entry.published.or(entry.updated).map(|dt| dt.with_timezone(&Utc).trunc_subsecs(0))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_feed_source_does_not_misclassify_substring_youtube_urls() {
        let spoofed =
            FeedSource::parse("https://example.com/rss?q=youtube.com/feeds/videos.xml").unwrap();
        let (feed_type, url) = spoofed.into_feed_url(FeedType::Rss).unwrap();
        assert_eq!(feed_type, FeedType::Rss);
        assert_eq!(url.as_str(), "https://example.com/rss?q=youtube.com/feeds/videos.xml");

        let real_yt = FeedSource::parse(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA",
        )
        .unwrap();
        let (yt_type, _) = real_yt.into_feed_url(FeedType::Rss).unwrap();
        assert_eq!(yt_type, FeedType::Youtube);
    }
}
