use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{QueryBuilder, Sqlite, SqlitePool};
use url::Url;

mod batch;
mod fts;
mod mutations;
mod queries;
#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::too_many_lines)]
mod tests;

#[allow(unused_imports)]
pub use fts::SearchTerm;

use super::{
    ContentType, EntryKind, EntryTriageState, InboxState, ItemSortField, ItemStatus,
    ItemStatusFilter, STARRED_COLLECTION_ID, SortOrder, WANT_TO_READ_COLLECTION_ID, WriteLock,
};

/// Canonical SQL predicate defining the read-only `Item` projection boundary over unified `entries`.
///
/// `Item` (along with [`crate::db::Feed`]) is a read-only projection over `entries` plus its
/// satellite tables (`entry_state`, `entry_content`, `entry_media`, `collection_entries`, and
/// `feed_sources`), rather than a separate backing table. An `entries` row (aliased as `i`, joined
/// to `entry_state es` and `entry_content ec`) is projected as a live reader `Item` when
/// `i.merged_into_id IS NULL` and at least one of the following holds:
/// - `es.inbox_state IN ('unread', 'dismissed')` (Feed Inbox emission),
/// - `ec.entry_id IS NOT NULL` (saved/enriched reader body or media content), or
/// - `i.kind` is reader-openable (`article`, `paper`, `video`, `podcast_episode`) and the entry
///   belongs to a `want` or `list` collection.
///
/// Non-reader catalog works (such as `movie`, `book`, `album`, `game`, `artist`, or `collection`)
/// without reader state/content are excluded from this projection.
pub const IS_LIVE_ITEM_SQL: &str = "(
    es.inbox_state IN ('unread', 'dismissed')
    OR ec.entry_id IS NOT NULL
    OR (
        i.kind IN ('article', 'paper', 'video', 'podcast_episode')
        AND EXISTS (
            SELECT 1 FROM collection_entries ce_li
            JOIN collection_specs cs_li ON cs_li.collection_id = ce_li.collection_id
            WHERE ce_li.entry_id = i.id
              AND cs_li.collection_role IN ('want', 'list')
        )
    )
)";

/// Stricter SQL predicate narrowing [`IS_LIVE_ITEM_SQL`] strictly to Feed Inbox emissions:
/// entries with `es.inbox_state IN ('unread', 'dismissed')` that belong to at least one subscribed
/// `feed_sources` collection.
pub const IS_FEED_INBOX_ITEM_SQL: &str = "(
    es.inbox_state IN ('unread', 'dismissed')
    AND EXISTS (
        SELECT 1 FROM collection_entries ce_fi
        JOIN feed_sources fs_fi ON fs_fi.collection_id = ce_fi.collection_id
        WHERE ce_fi.entry_id = i.id
    )
)";

#[derive(Debug, Clone, Copy)]
pub(super) enum ItemContentProjection {
    Full,
    List,
    MetadataOnly,
}

#[allow(clippy::too_many_lines)]
pub(super) fn push_item_select_projection(
    qb: &mut QueryBuilder<Sqlite>,
    proj: ItemContentProjection,
    from_clause: &str,
) {
    let content_html_expr = match proj {
        ItemContentProjection::Full => "ec.content_html",
        ItemContentProjection::List => {
            "CASE WHEN length(ec.content_html) <= 65536 THEN ec.content_html ELSE NULL END"
        }
        ItemContentProjection::MetadataOnly => "NULL",
    };

    qb.push(
        "SELECT i.id,
       i.kind AS entry_kind,
       fs_primary.collection_id AS feed_id,
       fs_primary.feed_url AS feed_url,
       fs_primary.feed_title AS feed_title,
       i.title,
       COALESCE(i.url, 'https://roche.local/entry/' || i.id) AS url,
       (SELECT group_concat(value, ', ') FROM json_each(i.creators_json)) AS author,
       i.published_at,
       i.created_at,
       i.summary, ",
    )
    .push(content_html_expr)
    .push(
        " AS content_html,
       i.thumbnail_url,
       (SELECT m.remote_url
        FROM entry_media m
        WHERE m.entry_id = i.id AND m.media_type = 'audio' AND m.remote_url IS NOT NULL
        ORDER BY m.created_at DESC
        LIMIT 1) AS audio_url,
       COALESCE(ec.content_type, CASE i.kind
           WHEN 'video' THEN 'video'
           WHEN 'paper' THEN 'paper'
           WHEN 'podcast_episode' THEN 'audio'
           ELSE 'article'
       END) AS content_type,
       CASE
           WHEN es.inbox_state = 'dismissed' THEN 'archived'
           WHEN COALESCE(es.inbox_state, 'none') = 'none'
                AND es.read_at IS NOT NULL
                AND ce_wtr.entry_id IS NULL THEN 'archived'
           ELSE 'unread'
       END AS status,
       COALESCE(es.inbox_state, 'none') AS inbox_state,
       (ce_wtr.entry_id IS NOT NULL) AS in_want_to_read,
       (ce_fav.entry_id IS NOT NULL) AS is_favorite,
       EXISTS (
           SELECT 1 FROM consumption_log cl_fin
           WHERE cl_fin.entry_id = i.id AND cl_fin.status = 'consumed'
       ) AS is_finished,
       es.read_at,
       COALESCE(es.progress, 0.0) AS read_progress,
       es.interaction_updated_at,
       (SELECT m.id
        FROM entry_media m
        WHERE m.entry_id = i.id AND m.local_path IS NOT NULL AND m.media_type = 'thumbnail'
        ORDER BY m.created_at DESC
        LIMIT 1) AS thumbnail_media_id,
       (SELECT m.duration
        FROM entry_media m
        WHERE m.entry_id = i.id AND m.duration IS NOT NULL
        ORDER BY CASE WHEN m.local_path IS NOT NULL THEN 0 ELSE 1 END ASC, m.created_at DESC
        LIMIT 1) AS duration,
       COALESCE((
           SELECT json_group_array(name) FROM (
               SELECT e_t.title AS name
               FROM collection_entries ce_t
               JOIN collection_specs cs_t ON cs_t.collection_id = ce_t.collection_id
               JOIN entries e_t ON e_t.id = ce_t.collection_id
               WHERE ce_t.entry_id = i.id
                 AND cs_t.collection_role = 'tag'
                 AND e_t.merged_into_id IS NULL
               ORDER BY e_t.title ASC
           )
       ), '[]') AS tag_names
",
    )
    .push(from_clause)
    .push(
        "
LEFT JOIN entry_state es ON es.entry_id = i.id
LEFT JOIN entry_content ec ON ec.entry_id = i.id
LEFT JOIN feed_sources fs_primary ON fs_primary.collection_id = (
    SELECT ce_f.collection_id
    FROM collection_entries ce_f
    JOIN feed_sources fs_f ON fs_f.collection_id = ce_f.collection_id
    WHERE ce_f.entry_id = i.id
    ORDER BY ce_f.added_at ASC, ce_f.collection_id ASC
    LIMIT 1
)
LEFT JOIN collection_entries ce_wtr ON ce_wtr.collection_id = ",
    )
    .push_bind(WANT_TO_READ_COLLECTION_ID)
    .push(" AND ce_wtr.entry_id = i.id LEFT JOIN collection_entries ce_fav ON ce_fav.collection_id = ")
    .push_bind(STARRED_COLLECTION_ID)
    .push(" AND ce_fav.entry_id = i.id");
}

pub(super) fn item_select_query_builder(proj: ItemContentProjection) -> QueryBuilder<Sqlite> {
    let mut qb = QueryBuilder::<Sqlite>::new("");
    push_item_select_projection(&mut qb, proj, "FROM entries i");
    qb
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct TagNames(pub Vec<String>);

impl TryFrom<&str> for TagNames {
    type Error = serde_json::Error;

    fn try_from(value: &str) -> std::result::Result<Self, Self::Error> {
        serde_json::from_str(value).map(Self)
    }
}

const fn default_item_entry_kind() -> EntryKind {
    EntryKind::Article
}

/// Read-only reader/inbox item projection over unified `entries` and satellite tables
/// (`entry_state`, `entry_content`, `entry_media`, `collection_entries`, `feed_sources`).
///
/// Only rows matching `merged_into_id IS NULL` and [`IS_LIVE_ITEM_SQL`] are exposed as `Item`s.
#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Item {
    pub id: String,
    #[serde(default = "default_item_entry_kind")]
    pub entry_kind: EntryKind,
    pub feed_id: Option<String>,
    #[serde(default)]
    pub feed_url: Option<String>,
    pub feed_title: Option<String>,
    pub title: String,
    #[sqlx(try_from = "&'a str")]
    pub url: Url,
    pub author: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub summary: Option<String>,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
    #[serde(default)]
    pub audio_url: Option<String>,
    pub content_type: ContentType,
    pub status: ItemStatus,
    #[serde(default)]
    pub inbox_state: InboxState,
    #[serde(default)]
    pub in_want_to_read: bool,
    pub is_favorite: bool,
    #[serde(default)]
    pub is_finished: bool,
    pub read_at: Option<DateTime<Utc>>,
    pub read_progress: f64,
    #[serde(default)]
    pub interaction_updated_at: Option<DateTime<Utc>>,
    pub thumbnail_media_id: Option<String>,
    pub duration: Option<i64>,
    #[serde(default)]
    #[sqlx(try_from = "&'a str")]
    pub tag_names: TagNames,
}

impl Item {
    /// Whether this item has playable audio (either audio-only content or an attached audio enclosure).
    #[must_use]
    pub fn has_audio(&self) -> bool {
        self.content_type == ContentType::Audio
            || self.audio_url.as_deref().is_some_and(|u| !u.trim().is_empty())
    }

    /// Whether this item is an explicit podcast episode (`entries.kind == 'podcast_episode'`).
    #[must_use]
    pub const fn is_podcast_episode(&self) -> bool {
        matches!(self.entry_kind, EntryKind::PodcastEpisode)
    }

    /// Canonical 3-state triage classification (`New`, `Finished`, or `Dismissed`).
    #[must_use]
    pub const fn triage_state(&self) -> EntryTriageState {
        if self.is_finished {
            EntryTriageState::Finished
        } else if matches!(self.status, ItemStatus::Archived) {
            EntryTriageState::Dismissed
        } else {
            EntryTriageState::New
        }
    }
}

#[cfg(test)]
#[derive(Debug, Clone, Copy)]
pub struct NewItem<'a> {
    pub feed_id: Option<&'a str>,
    pub title: &'a str,
    pub url: &'a Url,
    pub author: Option<&'a str>,
    pub published_at: Option<DateTime<Utc>>,
    pub summary: Option<&'a str>,
    pub content_type: ContentType,
}

#[derive(Debug, Clone, Copy)]
pub struct ManualItemInput<'a> {
    pub url: &'a Url,
    pub title: &'a str,
    pub summary: Option<&'a str>,
    pub content_html: Option<&'a str>,
    pub thumbnail_url: Option<&'a str>,
    pub audio_url: Option<&'a str>,
    pub duration: Option<i64>,
    pub content_type: ContentType,
    pub tags: &'a [String],
}

#[derive(Debug, Clone, Serialize)]
pub struct ItemPage {
    pub items: Vec<Item>,
    pub page: usize,
    pub per_page: usize,
    pub has_more: bool,
}

#[derive(Debug, Default, Clone)]
pub struct ItemFilter {
    pub status: Option<ItemStatusFilter>,
    pub feed_id: Option<String>,
    pub tag_id: Option<String>,
    pub collection_id: Option<String>,
    pub inbox_only: Option<bool>,
    pub in_progress_only: Option<bool>,
    pub content_type: Option<ContentType>,
    pub has_audio: Option<bool>,
    pub podcast_only: Option<bool>,
    pub is_favorite: Option<bool>,
    pub is_read: Option<bool>,
    pub include_content: Option<bool>,
    pub search: Option<String>,
    pub updated_after: Option<DateTime<Utc>>,
    pub updated_after_item_id: Option<String>,
    pub sort: Option<ItemSortField>,
    pub order: Option<SortOrder>,
    pub page: Option<usize>,
    pub per_page: Option<usize>,
    pub sort_updated_asc: bool,
}

pub struct ItemRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
    pub(crate) write_lock: &'a WriteLock,
}

impl<'a> ItemRepo<'a> {
    #[must_use]
    pub(crate) const fn new(pool: &'a SqlitePool, write_lock: &'a WriteLock) -> Self {
        Self { pool, write_lock }
    }
}
