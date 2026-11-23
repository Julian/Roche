use std::str::FromStr;

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use url::Url;

use crate::{
    external_links::{external_identifier_label, external_identifier_url},
    identity::{WANT_TO_READ_SLUG, deterministic_system_collection_id},
};

/// Reader scroll / playback progress threshold (`>= 0.98`) at which an item is automatically
/// marked Finished (logging `'consumed'` and clearing it from New / Want queues).
pub const READER_AUTO_READ_THRESHOLD: f64 = 0.98;

/// Origin of a reading or playback progress update so audio playback completion can be
/// distinguished from article scrolling on items that have both text and playable audio.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProgressSource {
    Reader,
    Audio,
}

/// Canonical focused-placement ranking round budget shared across Web and Android.
pub const MAX_FOCUSED_ROUNDS: usize = 5;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
pub enum FeedType {
    #[default]
    Rss,
    Podcast,
    Youtube,
    Arxiv,
}

/// Parsed OPML `<outline>` feed subscription target.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpmlOutline {
    pub feed_type: FeedType,
    pub xml_url: Url,
    pub title: Option<String>,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
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
    /// Archive all non-archived items (`inbox_state = 'unread'`) in a specific feed.
    Feed { feed_id: String },
    /// Archive all unread items (`status = 'unread' AND read_at IS NULL`) across all feeds.
    AllUnread,
}

impl ArchiveBatchScope {
    #[must_use]
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
            Some(Self::Feed { feed_id: fid })
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

/// Mutually exclusive 3-state triage lifecycle for reader/feed entries.
///
/// Derived without schema changes from `entry_state` + `consumption_log`:
/// - `Finished`: has a `'consumed'` row in `consumption_log`.
/// - `Dismissed`: cleared from New / Want queue without being `'consumed'`.
/// - `New`: unseen / active in Feed Inbox or Want queue.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EntryTriageState {
    #[default]
    New,
    Finished,
    Dismissed,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ItemStatusFilter {
    #[serde(alias = "new")]
    Unread,
    Finished,
    Dismissed,
    Archived,
    #[default]
    All,
}

impl ItemStatusFilter {
    #[must_use]
    pub const fn into_status(self) -> Option<ItemStatus> {
        match self {
            Self::Unread => Some(ItemStatus::Unread),
            Self::Archived | Self::Dismissed => Some(ItemStatus::Archived),
            Self::Finished | Self::All => None,
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
pub enum ContentType {
    #[default]
    Article,
    Video,
    Paper,
    #[serde(alias = "podcast")]
    Audio,
    Pdf,
}

impl ContentType {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Article => "article",
            Self::Video => "video",
            Self::Paper => "paper",
            Self::Audio => "audio",
            Self::Pdf => "pdf",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
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

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SortOrder {
    Asc,
    #[default]
    Desc,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "snake_case")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "snake_case"))]
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
    pub const fn default_want_slug(self) -> Option<&'static str> {
        match self {
            Self::Article | Self::Paper => Some("want:read"),
            Self::Movie => Some("want:movies"),
            Self::Tv => Some("want:tv"),
            Self::Artist => Some("want:artists"),
            Self::Album => Some("want:albums"),
            Self::Song => Some("want:songs"),
            Self::Book => Some("want:books"),
            Self::Podcast => Some("want:podcasts"),
            Self::PodcastEpisode => Some("want:podcast_episodes"),
            Self::Game => Some("want:games"),
            Self::Video => Some("want:videos"),
            Self::Channel => Some("want:channels"),
            Self::Publication => Some("want:publications"),
            Self::Collection => None,
        }
    }

    #[must_use]
    pub const fn is_reader_openable(self) -> bool {
        matches!(self, Self::Article | Self::Paper | Self::Video | Self::PodcastEpisode)
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
    pub const fn for_feed_item(feed_type: Option<FeedType>, ct: ContentType) -> Self {
        match ct {
            ContentType::Article => Self::Article,
            ContentType::Video => Self::Video,
            ContentType::Paper | ContentType::Pdf => Self::Paper,
            ContentType::Audio => match feed_type {
                Some(FeedType::Podcast) => Self::PodcastEpisode,
                _ => Self::Article,
            },
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

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
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

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
pub enum CollectionRole {
    #[default]
    List,
    Want,
    System,
    Tag,
    Feed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
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

    #[must_use]
    pub const fn display_label(self) -> &'static str {
        match self {
            Self::Consumed => "Finished",
            Self::Started => "Started",
            Self::Abandoned => "Skipped",
        }
    }
}

impl FromStr for ConsumptionStatus {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "started" => Ok(Self::Started),
            "consumed" | "finished" => Ok(Self::Consumed),
            "abandoned" | "skipped" => Ok(Self::Abandoned),
            other => anyhow::bail!("Unknown consumption status '{other}'"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "snake_case")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "snake_case"))]
pub enum ActivityEntityType {
    Feed,
    Item,
    Tag,
    Media,
    Entry,
    Collection,
    CollectionEntry,
    CollectionGenerator,
    ConsumptionLog,
    Comparison,
    Board,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "snake_case")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "snake_case"))]
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
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(try_from = "String", into = "String")]
#[cfg_attr(feature = "sqlx", sqlx(transparent))]
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

    /// Validates and normalizes an optional CSS hex color (`#RRGGBB`).
    ///
    /// Empty or whitespace-only inputs normalize to `Ok(None)`.
    pub fn parse_optional(raw: Option<&str>) -> Result<Option<Self>> {
        let Some(trimmed) = raw.map(str::trim).filter(|s| !s.is_empty()) else {
            return Ok(None);
        };
        Self::parse(trimmed).map(Some)
    }

    /// Tolerantly normalizes a persisted color value loaded from storage, returning `None`
    /// for empty or invalid legacy rows rather than failing decoding.
    #[must_use]
    pub fn from_stored_tolerant(raw: Option<&str>) -> Option<Self> {
        raw.map(str::trim).filter(|s| !s.is_empty()).and_then(|c| Self::parse(c).ok())
    }

    #[must_use]
    pub const fn as_str(&self) -> &str {
        self.0.as_str()
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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EntryIdentifier {
    pub scheme: String,
    pub value: String,
}

impl EntryIdentifier {
    #[must_use]
    pub fn label(&self) -> &str {
        external_identifier_label(&self.scheme)
    }

    #[must_use]
    pub fn external_url(&self) -> Option<String> {
        external_identifier_url(&self.scheme, &self.value)
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DynamicCollectionRule {
    #[serde(default)]
    pub kind: Option<EntryKind>,
    #[serde(default)]
    pub kinds: Vec<EntryKind>,
    #[serde(default)]
    pub consumed: Option<bool>,
    #[serde(default)]
    pub status: Option<ConsumptionStatus>,
    #[serde(default, alias = "creator_contains")]
    pub creator: Option<String>,
    #[serde(default)]
    pub release_year: Option<i32>,
}

#[must_use]
pub fn parse_dynamic_collection_rule(rule_json: Option<&str>) -> Option<DynamicCollectionRule> {
    let trimmed = rule_json.map(str::trim).filter(|s| !s.is_empty())?;
    serde_json::from_str::<DynamicCollectionRule>(trimmed).ok()
}

/// Evaluates whether an entry with `statuses` (its recorded `consumption_log` statuses) satisfies
/// `rule`, matching the SQL + creator semantics used across Rust and Kotlin.
#[must_use]
pub fn matches_dynamic_collection_rule(
    rule: &DynamicCollectionRule,
    kind: EntryKind,
    creators: &[String],
    release_year: Option<i32>,
    statuses: &[ConsumptionStatus],
) -> bool {
    let mut target_kinds: Vec<EntryKind> = Vec::new();
    if let Some(k) = rule.kind {
        target_kinds.push(k);
    }
    for &k in &rule.kinds {
        if !target_kinds.contains(&k) {
            target_kinds.push(k);
        }
    }
    if !target_kinds.is_empty() && !target_kinds.contains(&kind) {
        return false;
    }
    if let Some(consumed_flag) = rule.consumed {
        let has_consumed = statuses.contains(&ConsumptionStatus::Consumed);
        if consumed_flag != has_consumed {
            return false;
        }
    }
    if let Some(st) = rule.status
        && !statuses.contains(&st)
    {
        return false;
    }
    if let Some(yr) = rule.release_year
        && release_year != Some(yr)
    {
        return false;
    }
    if let Some(creator_needle) = rule.creator.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        let lower_needle = creator_needle.to_lowercase();
        if !creators.iter().any(|c| c.to_lowercase().contains(&lower_needle)) {
            return false;
        }
    }
    true
}

#[must_use]
pub fn is_want_to_read_base_slug(base_slug: &str) -> bool {
    matches!(base_slug, "read" | "article" | "articles" | "paper" | "papers")
}

#[must_use]
pub fn slugify_title(title: &str) -> String {
    let mut out = String::with_capacity(title.len());
    let mut prev_sep = false;
    for ch in title.chars() {
        if ch.is_ascii_alphanumeric() {
            out.push(ch.to_ascii_lowercase());
            prev_sep = false;
        } else if !prev_sep && !out.is_empty() {
            out.push('_');
            prev_sep = true;
        }
    }
    while out.ends_with('_') {
        out.pop();
    }
    out
}

/// Derives `(clean_title, derived_slug, deterministic_collection_id)` for a manual `List` or `Want`
/// collection. Returns `Err(message)` when the title, role, or derived slug is invalid.
pub fn derive_manual_collection_slug_and_id(
    title: &str,
    slug: Option<&str>,
    role: CollectionRole,
) -> std::result::Result<(String, String, String), String> {
    let clean_title = title.trim();
    if clean_title.is_empty() {
        return Err("Collection title cannot be empty".to_owned());
    }
    if !matches!(role, CollectionRole::List | CollectionRole::Want) {
        return Err("Custom collections must have role 'list' or 'want'".to_owned());
    }

    let raw_source = slug.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(clean_title);
    let slug_input = if role == CollectionRole::Want
        && raw_source.get(..5).is_some_and(|prefix| prefix.eq_ignore_ascii_case("want:"))
    {
        &raw_source[5..]
    } else {
        raw_source
    };
    let base_slug = slugify_title(slug_input);
    if base_slug.is_empty() {
        return Err("Collection slug cannot be empty".to_owned());
    }

    let (derived_slug, id) = if role == CollectionRole::Want {
        let full_slug = if is_want_to_read_base_slug(&base_slug) {
            WANT_TO_READ_SLUG.to_owned()
        } else {
            format!("want:{base_slug}")
        };
        let det_id = deterministic_system_collection_id(&full_slug);
        (full_slug, det_id)
    } else {
        let det_id = deterministic_system_collection_id(&format!("custom:{base_slug}"));
        (base_slug, det_id)
    };

    Ok((clean_title.to_owned(), derived_slug, id))
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
        assert_eq!(feed_unread, Some(ArchiveBatchScope::Feed { feed_id: "feed-1".to_string() }));
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

    #[test]
    #[allow(clippy::unwrap_used)]
    fn test_consumption_status_from_str_and_display_label() {
        assert_eq!("consumed".parse::<ConsumptionStatus>().unwrap(), ConsumptionStatus::Consumed);
        assert_eq!("finished".parse::<ConsumptionStatus>().unwrap(), ConsumptionStatus::Consumed);
        assert_eq!("started".parse::<ConsumptionStatus>().unwrap(), ConsumptionStatus::Started);
        assert_eq!("abandoned".parse::<ConsumptionStatus>().unwrap(), ConsumptionStatus::Abandoned);
        assert_eq!("skipped".parse::<ConsumptionStatus>().unwrap(), ConsumptionStatus::Abandoned);
        assert!("bogus".parse::<ConsumptionStatus>().is_err());

        assert_eq!(ConsumptionStatus::Consumed.display_label(), "Finished");
        assert_eq!(ConsumptionStatus::Started.display_label(), "Started");
        assert_eq!(ConsumptionStatus::Abandoned.display_label(), "Skipped");
    }

    #[test]
    #[allow(clippy::unwrap_used)]
    fn test_hex_color_parse_optional_and_tolerant_read() {
        assert_eq!(HexColor::parse("#3b82f6").unwrap().as_str(), "#3b82f6");
        assert_eq!(HexColor::parse("  #ABCDEF  ").unwrap().as_str(), "#abcdef");
        assert_eq!(HexColor::parse_optional(None).unwrap(), None);
        assert_eq!(HexColor::parse_optional(Some("")).unwrap(), None);
        assert_eq!(HexColor::parse_optional(Some("   \t ")).unwrap(), None);
        assert_eq!(
            HexColor::parse_optional(Some("#10B981")).unwrap().as_ref().map(HexColor::as_str),
            Some("#10b981")
        );

        for invalid in ["not-a-color", "javascript:alert(1)", "#12345", "#123", "#gggggg", "3b82f6"]
        {
            assert!(HexColor::parse(invalid).is_err(), "expected '{invalid}' to fail");
            assert!(HexColor::parse_optional(Some(invalid)).is_err());
            assert_eq!(HexColor::from_stored_tolerant(Some(invalid)), None);
        }

        assert_eq!(
            HexColor::from_stored_tolerant(Some(" #AA11BB ")).as_ref().map(HexColor::as_str),
            Some("#aa11bb")
        );
        assert_eq!(HexColor::from_stored_tolerant(Some("   ")), None);
        assert_eq!(HexColor::from_stored_tolerant(None), None);
    }
}
