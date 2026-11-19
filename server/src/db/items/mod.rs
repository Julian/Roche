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
    ContentType, InboxState, ItemSortField, ItemStatus, ItemStatusFilter, STARRED_COLLECTION_ID,
    SortOrder, WANT_TO_READ_COLLECTION_ID,
};

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

pub(super) fn item_select_query_builder(proj: ItemContentProjection) -> QueryBuilder<Sqlite> {
    let content_html_expr = match proj {
        ItemContentProjection::Full => "ec.content_html",
        ItemContentProjection::List => {
            "CASE WHEN length(ec.content_html) <= 65536 THEN ec.content_html ELSE NULL END"
        }
        ItemContentProjection::MetadataOnly => "NULL",
    };

    let mut qb = QueryBuilder::<Sqlite>::new(
        "SELECT i.id,
       fs_primary.collection_id AS feed_id,
       fs_primary.feed_url AS feed_url,
       fs_primary.feed_title AS feed_title,
       i.title,
       COALESCE(i.url, 'https://roche.local/entry/' || i.id) AS url,
       (SELECT group_concat(value, ', ') FROM json_each(i.creators_json)) AS author,
       i.published_at,
       i.created_at,
       i.summary, ",
    );
    qb.push(content_html_expr).push(
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
FROM entries i
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
    );
    qb.push_bind(WANT_TO_READ_COLLECTION_ID)
        .push(" AND ce_wtr.entry_id = i.id LEFT JOIN collection_entries ce_fav ON ce_fav.collection_id = ")
        .push_bind(STARRED_COLLECTION_ID)
        .push(" AND ce_fav.entry_id = i.id");
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

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Item {
    pub id: String,
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

fn deserialize_optional_rfc3339<'de, D>(
    deserializer: D,
) -> std::result::Result<Option<DateTime<Utc>>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let opt = Option::<String>::deserialize(deserializer)?;
    let Some(raw) = opt else {
        return Ok(None);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err(serde::de::Error::custom("updated_after cannot be empty"));
    }
    if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
        return Ok(Some(dt.with_timezone(&Utc)));
    }
    if trimmed.contains(' ')
        && let Ok(dt) = DateTime::parse_from_rfc3339(&trimmed.replace(' ', "+"))
    {
        return Ok(Some(dt.with_timezone(&Utc)));
    }
    Err(serde::de::Error::custom(format!(
        "Invalid RFC 3339 timestamp for updated_after: '{trimmed}'"
    )))
}

#[derive(Debug, Default, Clone, Deserialize)]
pub struct ItemFilter {
    pub status: Option<ItemStatusFilter>,
    pub feed_id: Option<String>,
    pub tag_id: Option<String>,
    #[serde(default)]
    pub collection_id: Option<String>,
    #[serde(default)]
    pub inbox_only: Option<bool>,
    pub content_type: Option<ContentType>,
    pub is_favorite: Option<bool>,
    pub is_read: Option<bool>,
    pub include_content: Option<bool>,
    pub search: Option<String>,
    #[serde(default, deserialize_with = "deserialize_optional_rfc3339")]
    pub updated_after: Option<DateTime<Utc>>,
    #[serde(default)]
    pub updated_after_item_id: Option<String>,
    pub sort: Option<ItemSortField>,
    pub order: Option<SortOrder>,
    pub page: Option<usize>,
    pub per_page: Option<usize>,
    #[serde(default)]
    pub sort_updated_asc: bool,
}

pub struct ItemRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

impl<'a> ItemRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }
}
