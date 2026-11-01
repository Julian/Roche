use anyhow::Result;
use serde::{Deserialize, Deserializer, Serialize};
use url::Url;

use crate::{
    content::fetcher::SafeHttpClient,
    feeds::{ArxivParser, YouTubeParser},
};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum FeedType {
    #[default]
    Rss,
    Youtube,
    Arxiv,
}

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

    pub fn into_feed_url(self, feed_type: FeedType) -> Result<Url> {
        match self {
            Self::YouTubeChannelId(channel_id) => {
                if feed_type != FeedType::Youtube {
                    anyhow::bail!(
                        "Bare YouTube channel ID '{channel_id}' is only valid for youtube feeds"
                    );
                }
                YouTubeParser::feed_url_for_channel_id(&channel_id)
            }
            Self::Url(url) => match feed_type {
                FeedType::Youtube => {
                    if !url.as_str().contains("/feeds/videos.xml")
                        && let Some(channel_id) = YouTubeParser::extract_channel_id(url.as_str())
                    {
                        return YouTubeParser::feed_url_for_channel_id(&channel_id);
                    }
                    if !YouTubeParser::is_youtube_host(url.host_str()) {
                        anyhow::bail!("YouTube feed URL must point to youtube.com");
                    }
                    Ok(url)
                }
                FeedType::Arxiv => {
                    if !ArxivParser::is_arxiv_host(url.host_str()) {
                        anyhow::bail!("arXiv feed URL must point to arxiv.org");
                    }
                    Ok(url)
                }
                FeedType::Rss => Ok(url),
            },
        }
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

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum ItemStatus {
    #[default]
    Unread,
    Archived,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ItemStatusFilter {
    Unread,
    Archived,
    #[default]
    All,
}

impl ItemStatusFilter {
    #[must_use]
    pub const fn into_status(self) -> Option<ItemStatus> {
        match self {
            Self::Unread => Some(ItemStatus::Unread),
            Self::Archived => Some(ItemStatus::Archived),
            Self::All => None,
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum ContentType {
    #[default]
    Article,
    Video,
    Paper,
    Audio,
    Pdf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum MediaType {
    Video,
    Audio,
    Pdf,
    Thumbnail,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ItemSortField {
    #[default]
    CreatedAt,
    PublishedAt,
    Title,
}

impl ItemSortField {
    #[must_use]
    pub const fn as_sql_expr(self) -> &'static str {
        match self {
            Self::CreatedAt => "i.created_at",
            Self::PublishedAt => "COALESCE(i.published_at, i.created_at)",
            Self::Title => "i.title COLLATE NOCASE",
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SortOrder {
    Asc,
    #[default]
    Desc,
}

impl SortOrder {
    #[must_use]
    pub const fn as_sql(self) -> &'static str {
        match self {
            Self::Asc => "ASC",
            Self::Desc => "DESC",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum ActivityEntityType {
    Feed,
    Item,
    Tag,
    Media,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "snake_case")]
#[sqlx(type_name = "TEXT", rename_all = "snake_case")]
pub enum ActivityAction {
    Create,
    Update,
    Archive,
    Unarchive,
    Favorite,
    Unfavorite,
    TagAdd,
    TagRemove,
    Delete,
}

/// Validated CSS hex color (`#RRGGBB`).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(try_from = "String", into = "String")]
#[sqlx(transparent)]
pub struct HexColor(String);

impl HexColor {
    pub fn parse(raw: &str) -> Result<Self> {
        let trimmed = raw.trim();
        if let Some(hex_part) = trimmed.strip_prefix('#')
            && hex_part.len() == 6
            && hex_part.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Ok(Self(format!("#{}", hex_part.to_ascii_lowercase())));
        }
        anyhow::bail!("Invalid hex color '{trimmed}': expected #RRGGBB format")
    }
}

impl TryFrom<String> for HexColor {
    type Error = anyhow::Error;

    fn try_from(value: String) -> Result<Self> {
        Self::parse(&value)
    }
}

impl From<HexColor> for String {
    fn from(color: HexColor) -> Self {
        color.0
    }
}
