use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum FeedType {
    #[default]
    #[serde(alias = "podcast")]
    Rss,
    Youtube,
    Arxiv,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum ItemStatus {
    #[default]
    Unread,
    Archived,
}

/// Domain enum replacing `Option<Option<DateTime<Utc>>>` for updating an item's read timestamp.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub enum ReadAtPatch {
    /// Leave the existing `read_at` timestamp untouched (unless archiving/unarchiving requires a default transition).
    #[default]
    Unchanged,
    /// Explicitly mark the item as read at the given timestamp.
    MarkRead(DateTime<Utc>),
    /// Explicitly clear `read_at` (`NULL`) to mark the item as unread.
    MarkUnread,
}

impl From<Option<Option<DateTime<Utc>>>> for ReadAtPatch {
    fn from(opt: Option<Option<DateTime<Utc>>>) -> Self {
        match opt {
            None => Self::Unchanged,
            Some(Some(ts)) => Self::MarkRead(ts),
            Some(None) => Self::MarkUnread,
        }
    }
}

/// Domain sum type replacing `(item_ids, feed_id, all_unread: bool)` for batch-archiving items.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArchiveBatchScope {
    /// Archive specific non-archived item IDs, optionally restricted to a specific feed.
    Items { item_ids: Vec<String>, feed_id: Option<String> },
    /// Archive all non-archived items (or only unread items with `read_at IS NULL`) in a specific feed.
    Feed { feed_id: String, unread_only: bool },
    /// Archive all unread items (`status = 'unread' AND read_at IS NULL`) across all feeds.
    AllUnread,
}

impl ArchiveBatchScope {
    pub fn from_request(
        item_ids: &[String],
        feed_id: Option<&str>,
        all_unread: bool,
    ) -> Option<Self> {
        let clean_feed_id = feed_id.map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        let valid_ids: Vec<String> = item_ids
            .iter()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .collect();

        if !valid_ids.is_empty() {
            Some(Self::Items { item_ids: valid_ids, feed_id: clean_feed_id })
        } else if let Some(fid) = clean_feed_id {
            Some(Self::Feed { feed_id: fid, unread_only: all_unread })
        } else if all_unread {
            Some(Self::AllUnread)
        } else {
            None
        }
    }

    #[must_use]
    pub fn feed_id(&self) -> Option<&str> {
        match self {
            Self::Items { feed_id, .. } => feed_id.as_deref(),
            Self::Feed { feed_id, .. } => Some(feed_id.as_str()),
            Self::AllUnread => None,
        }
    }
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
    #[serde(alias = "podcast")]
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
    CreatedAt,
    #[default]
    PublishedAt,
    Title,
    Relevance,
}

impl ItemSortField {
    #[must_use]
    pub const fn as_sql_expr(self) -> &'static str {
        match self {
            Self::CreatedAt => "i.created_at",
            Self::PublishedAt | Self::Relevance => "COALESCE(i.published_at, i.created_at)",
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_read_at_patch_from_option_option() {
        let now = Utc::now();
        assert_eq!(ReadAtPatch::from(None), ReadAtPatch::Unchanged);
        assert_eq!(ReadAtPatch::from(Some(Some(now))), ReadAtPatch::MarkRead(now));
        assert_eq!(ReadAtPatch::from(Some(None)), ReadAtPatch::MarkUnread);
    }

    #[test]
    fn test_archive_batch_scope_from_request() {
        assert_eq!(ArchiveBatchScope::from_request(&[], None, false), None);
        assert_eq!(ArchiveBatchScope::from_request(&["  ".to_string()], Some(" "), false), None);

        let all_unread = ArchiveBatchScope::from_request(&[], None, true);
        assert_eq!(all_unread, Some(ArchiveBatchScope::AllUnread));
        assert_eq!(all_unread.as_ref().and_then(ArchiveBatchScope::feed_id), None);

        let feed_unread = ArchiveBatchScope::from_request(&[], Some(" feed-1 "), true);
        assert_eq!(
            feed_unread,
            Some(ArchiveBatchScope::Feed { feed_id: "feed-1".to_string(), unread_only: true })
        );
        assert_eq!(feed_unread.as_ref().and_then(ArchiveBatchScope::feed_id), Some("feed-1"));

        let items = ArchiveBatchScope::from_request(
            &[" id-1 ".to_string(), String::new(), "id-2".to_string()],
            Some("feed-2"),
            true,
        );
        assert_eq!(
            items,
            Some(ArchiveBatchScope::Items {
                item_ids: vec!["id-1".to_string(), "id-2".to_string()],
                feed_id: Some("feed-2".to_string()),
            })
        );
        assert_eq!(items.as_ref().and_then(ArchiveBatchScope::feed_id), Some("feed-2"));
    }
}
