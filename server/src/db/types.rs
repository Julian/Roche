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
#[serde(rename_all = "snake_case")]
#[sqlx(type_name = "TEXT", rename_all = "snake_case")]
pub enum EntryKind {
    Article,
    Paper,
    Video,
    PodcastEpisode,
    Song,
    Movie,
    Book,
    Game,
    Album,
    Tv,
    Artist,
    Podcast,
    Publication,
    Channel,
    Collection,
}

impl EntryKind {
    pub const ALL: [Self; 15] = [
        Self::Article,
        Self::Paper,
        Self::Video,
        Self::PodcastEpisode,
        Self::Song,
        Self::Movie,
        Self::Book,
        Self::Game,
        Self::Album,
        Self::Tv,
        Self::Artist,
        Self::Podcast,
        Self::Publication,
        Self::Channel,
        Self::Collection,
    ];

    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Article => "article",
            Self::Paper => "paper",
            Self::Video => "video",
            Self::PodcastEpisode => "podcast_episode",
            Self::Song => "song",
            Self::Movie => "movie",
            Self::Book => "book",
            Self::Game => "game",
            Self::Album => "album",
            Self::Tv => "tv",
            Self::Artist => "artist",
            Self::Podcast => "podcast",
            Self::Publication => "publication",
            Self::Channel => "channel",
            Self::Collection => "collection",
        }
    }

    #[must_use]
    pub const fn display_label(self) -> &'static str {
        match self {
            Self::Article => "Article",
            Self::Paper => "Paper",
            Self::Video => "Video",
            Self::PodcastEpisode => "Podcast Episode",
            Self::Song => "Song",
            Self::Movie => "Movie",
            Self::Book => "Book",
            Self::Game => "Game",
            Self::Album => "Album",
            Self::Tv => "TV",
            Self::Artist => "Artist",
            Self::Podcast => "Podcast",
            Self::Publication => "Publication",
            Self::Channel => "Channel",
            Self::Collection => "Collection",
        }
    }

    #[must_use]
    pub const fn default_list_slug(self) -> Option<&'static str> {
        match self {
            Self::Movie => Some("movies"),
            Self::Tv => Some("tv"),
            Self::Artist => Some("artists"),
            Self::Album => Some("albums"),
            Self::Song => Some("songs"),
            Self::Book => Some("books"),
            Self::Podcast => Some("podcasts"),
            Self::PodcastEpisode => Some("podcast_episodes"),
            Self::Game => Some("games"),
            Self::Article => Some("articles"),
            Self::Paper => Some("papers"),
            Self::Video => Some("videos"),
            Self::Channel => Some("channels"),
            Self::Publication => Some("publications"),
            Self::Collection => None,
        }
    }

    #[must_use]
    pub const fn parent_kind(self) -> Option<Self> {
        match self {
            Self::Song => Some(Self::Album),
            Self::PodcastEpisode => Some(Self::Podcast),
            Self::Article => Some(Self::Publication),
            Self::Video => Some(Self::Channel),
            _ => None,
        }
    }

    #[must_use]
    pub const fn from_content_type(ct: ContentType) -> Self {
        match ct {
            ContentType::Article => Self::Article,
            ContentType::Video => Self::Video,
            ContentType::Paper | ContentType::Pdf => Self::Paper,
            ContentType::Audio => Self::PodcastEpisode,
        }
    }

    #[must_use]
    pub const fn default_content_type(self) -> ContentType {
        match self {
            Self::Video => ContentType::Video,
            Self::Paper => ContentType::Paper,
            Self::PodcastEpisode | Self::Song | Self::Podcast | Self::Album => ContentType::Audio,
            _ => ContentType::Article,
        }
    }

    pub fn parse_flexible(raw: &str) -> Result<Self> {
        let norm = raw.trim().to_ascii_lowercase().replace('-', "_");
        match norm.as_str() {
            "article" | "articles" => Ok(Self::Article),
            "paper" | "papers" => Ok(Self::Paper),
            "video" | "videos" => Ok(Self::Video),
            "podcast_episode" | "podcast_episodes" | "episode" | "episodes" => {
                Ok(Self::PodcastEpisode)
            }
            "song" | "songs" | "track" | "tracks" => Ok(Self::Song),
            "movie" | "movies" | "film" | "films" => Ok(Self::Movie),
            "book" | "books" => Ok(Self::Book),
            "game" | "games" => Ok(Self::Game),
            "album" | "albums" => Ok(Self::Album),
            "tv" | "show" | "shows" | "series" => Ok(Self::Tv),
            "artist" | "artists" => Ok(Self::Artist),
            "podcast" | "podcasts" => Ok(Self::Podcast),
            "publication" | "publications" | "blog" | "blogs" => Ok(Self::Publication),
            "channel" | "channels" => Ok(Self::Channel),
            "collection" | "collections" | "list" | "lists" => Ok(Self::Collection),
            _ => anyhow::bail!("Unknown entry kind '{raw}'"),
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum InboxState {
    #[default]
    None,
    Unread,
    Dismissed,
}

impl From<ItemStatus> for InboxState {
    fn from(status: ItemStatus) -> Self {
        match status {
            ItemStatus::Unread => Self::Unread,
            ItemStatus::Archived => Self::Dismissed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum CollectionMode {
    Manual,
    Feed,
    Dynamic,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum CollectionRole {
    #[default]
    List,
    Want,
    System,
    Tag,
    Feed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "lowercase")]
#[sqlx(type_name = "TEXT", rename_all = "lowercase")]
pub enum ConsumptionStatus {
    Started,
    Consumed,
    Abandoned,
}

impl ConsumptionStatus {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Started => "started",
            Self::Consumed => "consumed",
            Self::Abandoned => "abandoned",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, sqlx::Type)]
#[serde(rename_all = "snake_case")]
#[sqlx(type_name = "TEXT", rename_all = "snake_case")]
pub enum ActivityEntityType {
    Feed,
    Item,
    Tag,
    Media,
    Entry,
    Collection,
    CollectionEntry,
    ConsumptionLog,
    Comparison,
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

    #[test]
    #[allow(clippy::unwrap_used)]
    fn test_entry_kind_all_roundtrip_and_parent_mapping() {
        assert_eq!(EntryKind::ALL.len(), 15);
        for kind in EntryKind::ALL {
            assert_eq!(EntryKind::parse_flexible(kind.as_str()).unwrap(), kind);
            let _ = kind.default_content_type();
        }
        assert_eq!(EntryKind::Song.parent_kind(), Some(EntryKind::Album));
        assert_eq!(EntryKind::PodcastEpisode.parent_kind(), Some(EntryKind::Podcast));
        assert_eq!(EntryKind::Article.parent_kind(), Some(EntryKind::Publication));
        assert_eq!(EntryKind::Video.parent_kind(), Some(EntryKind::Channel));
        assert_eq!(EntryKind::Movie.parent_kind(), None);
    }
}
