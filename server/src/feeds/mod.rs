pub mod arxiv;
pub mod opml;
pub mod rss;
pub mod youtube;

use anyhow::Result;
pub use arxiv::ArxivParser;
use chrono::{DateTime, SubsecRound, Utc};
pub use opml::{OpmlDocument, OpmlOutline};
pub use rss::RssParser;
use serde::{Deserialize, Deserializer, Serialize};
use url::Url;
pub use youtube::YouTubeParser;

use crate::{content::fetcher::SafeHttpClient, db::FeedType};

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
        let url = SafeHttpClient::parse_url(trimmed)?;
        Ok(Self::Url(url))
    }

    pub fn into_feed_url(self, requested_type: FeedType) -> Result<(FeedType, Url)> {
        match self {
            Self::YouTubeChannelId(channel_id) => match requested_type {
                FeedType::Youtube | FeedType::Rss => {
                    Ok((FeedType::Youtube, YouTubeParser::feed_url_for_channel_id(&channel_id)?))
                }
                FeedType::Arxiv => anyhow::bail!(
                    "Bare YouTube channel ID '{channel_id}' is only valid for youtube feeds"
                ),
            },
            Self::Url(url) => match requested_type {
                FeedType::Youtube => {
                    if !url.as_str().contains("/feeds/videos.xml")
                        && let Some(channel_id) = YouTubeParser::extract_channel_id(url.as_str())
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
                FeedType::Rss => {
                    if !url.as_str().contains("/feeds/videos.xml")
                        && let Some(channel_id) = YouTubeParser::extract_channel_id(url.as_str())
                    {
                        return Ok((
                            FeedType::Youtube,
                            YouTubeParser::feed_url_for_channel_id(&channel_id)?,
                        ));
                    }
                    if url.as_str().contains("youtube.com/feeds/videos.xml") {
                        return Ok((FeedType::Youtube, url));
                    }
                    if ArxivParser::is_arxiv_host(url.host_str()) {
                        Self::validate_arxiv_feed_url(&url)?;
                        return Ok((FeedType::Arxiv, url));
                    }
                    Ok((FeedType::Rss, url))
                }
            },
        }
    }

    fn validate_arxiv_feed_url(url: &Url) -> Result<()> {
        let path = url.path();
        if path.starts_with("/abs/")
            || path.starts_with("/pdf/")
            || crate::content::fetcher::is_direct_pdf_url(url)
        {
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
#[must_use]
pub fn select_entry_link(links: &[feed_rs::model::Link]) -> Option<Url> {
    links
        .iter()
        .find(|l| l.rel.as_deref().is_none_or(|r| r.eq_ignore_ascii_case("alternate")))
        .or_else(|| links.first())
        .and_then(|l| SafeHttpClient::parse_url(&l.href).ok())
}

/// Shared helper to extract the UTC publication/update timestamp (truncated to whole seconds) from a `feed-rs` entry.
#[must_use]
pub fn extract_entry_timestamp(entry: &feed_rs::model::Entry) -> Option<DateTime<Utc>> {
    entry.published.or(entry.updated).map(|dt| dt.with_timezone(&Utc).trunc_subsecs(0))
}
