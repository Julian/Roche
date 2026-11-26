use chrono::{DateTime, Utc};
use sqlx::SqliteConnection;

use super::{
    tx::WriteTx,
    types::{ContentType, EntryKind, InboxState},
};

const INSERT_ENTRY_SQL: &str = "INSERT INTO entries
    (id, kind, title, creators_json, url, published_at, release_year, summary,
     thumbnail_url, metadata_json, merged_into_id, created_at, updated_at, hlc)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

const INSERT_ENTRY_OR_IGNORE_SQL: &str = "INSERT OR IGNORE INTO entries
    (id, kind, title, creators_json, url, published_at, release_year, summary,
     thumbnail_url, metadata_json, merged_into_id, created_at, updated_at, hlc)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

/// Canonical parameter bundle for inserting a new row into the unified `entries` table.
#[derive(Debug, Clone, Copy)]
pub struct EntryInsertParams<'a> {
    pub id: &'a str,
    pub kind: EntryKind,
    pub title: &'a str,
    pub creators_json: &'a str,
    pub url: Option<&'a str>,
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
    pub summary: Option<&'a str>,
    pub thumbnail_url: Option<&'a str>,
    pub metadata_json: &'a str,
    pub merged_into_id: Option<&'a str>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub hlc: Option<&'a str>,
}

impl<'a> EntryInsertParams<'a> {
    /// Constructs an entry insert payload with canonical defaults (`creators_json = "[]"`,
    /// `metadata_json = "{}"`, no optional metadata, and a causal HLC minted by `WriteTx`).
    #[must_use]
    pub const fn minimal(
        id: &'a str,
        kind: EntryKind,
        title: &'a str,
        created_at: DateTime<Utc>,
        updated_at: DateTime<Utc>,
    ) -> Self {
        Self {
            id,
            kind,
            title,
            creators_json: "[]",
            url: None,
            published_at: None,
            release_year: None,
            summary: None,
            thumbnail_url: None,
            metadata_json: "{}",
            merged_into_id: None,
            created_at,
            updated_at,
            hlc: None,
        }
    }
}

/// Parameters for upserting an entry's reader/inbox `entry_state` row.
#[derive(Debug, Clone, Copy)]
pub struct EntryStateUpsert<'a> {
    pub entry_id: &'a str,
    pub inbox_state: InboxState,
    pub read_at: Option<DateTime<Utc>>,
    pub progress: f64,
    pub interaction_updated_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
}

async fn execute_entry_insert_in_tx(
    tx: &mut WriteTx<'_>,
    sql: &'static str,
    params: &EntryInsertParams<'_>,
) -> std::result::Result<u64, sqlx::Error> {
    let hlc = match params.hlc {
        Some(explicit) if !explicit.is_empty() => explicit.to_owned(),
        _ => tx.next_hlc().await?,
    };
    let res = sqlx::query(sql)
        .bind(params.id)
        .bind(params.kind)
        .bind(params.title)
        .bind(params.creators_json)
        .bind(params.url)
        .bind(params.published_at)
        .bind(params.release_year)
        .bind(params.summary)
        .bind(params.thumbnail_url)
        .bind(params.metadata_json)
        .bind(params.merged_into_id)
        .bind(params.created_at)
        .bind(params.updated_at)
        .bind(&hlc)
        .execute(tx.conn())
        .await?;
    Ok(res.rows_affected())
}

/// Inserts a new row into `entries`, failing if `params.id` already exists.
pub async fn insert_entry_row_in_tx(
    tx: &mut WriteTx<'_>,
    params: &EntryInsertParams<'_>,
) -> std::result::Result<(), sqlx::Error> {
    let _ = execute_entry_insert_in_tx(tx, INSERT_ENTRY_SQL, params).await?;
    Ok(())
}

/// Inserts a new row into `entries` with `INSERT OR IGNORE`, returning `true` when a new row was
/// inserted and `false` when `params.id` already existed.
pub async fn insert_entry_row_or_ignore_in_tx(
    tx: &mut WriteTx<'_>,
    params: &EntryInsertParams<'_>,
) -> std::result::Result<bool, sqlx::Error> {
    Ok(execute_entry_insert_in_tx(tx, INSERT_ENTRY_OR_IGNORE_SQL, params).await? > 0)
}

/// Inserts an `entry_identifiers` mapping (`(scheme, value) -> entry_id`) using `INSERT OR IGNORE`
/// so existing canonical owners are preserved. Empty schemes or values are ignored.
pub async fn upsert_entry_identifier_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    scheme: &str,
    value: &str,
) -> std::result::Result<(), sqlx::Error> {
    let _ = upsert_entry_identifier_changed_in_tx(tx, entry_id, scheme, value).await?;
    Ok(())
}

/// Inserts an `entry_identifiers` mapping using `INSERT OR IGNORE`, returning `true` only when a
/// new row was inserted.
pub async fn upsert_entry_identifier_changed_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    scheme: &str,
    value: &str,
) -> std::result::Result<bool, sqlx::Error> {
    if scheme.is_empty() || value.is_empty() {
        return Ok(false);
    }
    let res = sqlx::query(
        "INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
         VALUES (?, ?, ?)",
    )
    .bind(scheme)
    .bind(value)
    .bind(entry_id)
    .execute(&mut *tx)
    .await?;
    Ok(res.rows_affected() > 0)
}

/// Replaces an entry's sole identifier for `scheme` (for example when renaming a `'tag'`
/// collection).
pub async fn replace_entry_identifier_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    scheme: &str,
    value: &str,
) -> std::result::Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM entry_identifiers WHERE scheme = ? AND entry_id = ?")
        .bind(scheme)
        .bind(entry_id)
        .execute(&mut *tx)
        .await?;
    if !scheme.is_empty() && !value.is_empty() {
        sqlx::query(
            "INSERT OR REPLACE INTO entry_identifiers (scheme, value, entry_id)
             VALUES (?, ?, ?)",
        )
        .bind(scheme)
        .bind(value)
        .bind(entry_id)
        .execute(&mut *tx)
        .await?;
    }
    Ok(())
}

/// Inserts canonical `'url'` (and optional `'arxiv'`) identifiers for an entry.
pub async fn upsert_url_and_arxiv_identifiers_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    canon_url: &str,
    arxiv_id: Option<&str>,
) -> std::result::Result<(), sqlx::Error> {
    upsert_entry_identifier_in_tx(tx, entry_id, "url", canon_url).await?;
    if let Some(aid) = arxiv_id {
        upsert_entry_identifier_in_tx(tx, entry_id, "arxiv", aid).await?;
    }
    Ok(())
}

/// Looks up the owning `entry_id` for a `(scheme, value)` pair in `entry_identifiers`.
pub async fn find_entry_id_by_identifier_in_tx(
    tx: &mut SqliteConnection,
    scheme: &str,
    value: &str,
) -> std::result::Result<Option<String>, sqlx::Error> {
    if scheme.is_empty() || value.is_empty() {
        return Ok(None);
    }
    sqlx::query_scalar(
        "SELECT entry_id FROM entry_identifiers WHERE scheme = ? AND value = ? LIMIT 1",
    )
    .bind(scheme)
    .bind(value)
    .fetch_optional(&mut *tx)
    .await
}

/// Inserts an initial `entry_content` row for a freshly created entry.
pub async fn insert_entry_content_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    content_html: Option<&str>,
    content_type: ContentType,
) -> std::result::Result<(), sqlx::Error> {
    sqlx::query(
        "INSERT INTO entry_content (entry_id, content_html, content_type)
         VALUES (?, ?, ?)",
    )
    .bind(entry_id)
    .bind(content_html)
    .bind(content_type)
    .execute(&mut *tx)
    .await?;
    Ok(())
}

/// Resolves the appropriate `entry_content.content_type` for `entry_id`, preferring
/// [`ContentType::Pdf`] whenever local PDF media is attached in `entry_media`.
pub async fn resolve_entry_content_type_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    entry_kind: EntryKind,
) -> std::result::Result<ContentType, sqlx::Error> {
    let has_local_pdf: Option<i64> = sqlx::query_scalar(
        "SELECT 1 FROM entry_media
         WHERE entry_id = ? AND media_type = 'pdf' AND local_path IS NOT NULL
         LIMIT 1",
    )
    .bind(entry_id)
    .fetch_optional(&mut *tx)
    .await?;
    Ok(if has_local_pdf.is_some() { ContentType::Pdf } else { entry_kind.default_content_type() })
}

/// Inserts or updates an `entry_content` row, returning `true` when the row was inserted or
/// modified.
///
/// When `overwrite_html` is `false`, existing non-empty `content_html` is preserved. When
/// `content_type` is `ContentType::Pdf`, an existing `'paper'` or `'article'` row is upgraded to
/// `'pdf'` so clients recognize cached local PDF media.
pub async fn upsert_entry_content_in_tx(
    tx: &mut SqliteConnection,
    entry_id: &str,
    content_html: Option<&str>,
    content_type: ContentType,
    overwrite_html: bool,
) -> std::result::Result<bool, sqlx::Error> {
    let clean_html = content_html.filter(|s| !s.trim().is_empty());
    let existing: Option<(Option<String>, ContentType)> =
        sqlx::query_as("SELECT content_html, content_type FROM entry_content WHERE entry_id = ?")
            .bind(entry_id)
            .fetch_optional(&mut *tx)
            .await?;

    let Some((cur_html, cur_ct)) = existing else {
        sqlx::query(
            "INSERT INTO entry_content (entry_id, content_html, content_type)
             VALUES (?, ?, ?)",
        )
        .bind(entry_id)
        .bind(clean_html)
        .bind(content_type)
        .execute(&mut *tx)
        .await?;
        return Ok(true);
    };

    let has_cur_html = cur_html.as_deref().is_some_and(|s| !s.trim().is_empty());
    let next_html = clean_html.map_or(cur_html.as_deref(), |new_html| {
        if overwrite_html || !has_cur_html { Some(new_html) } else { cur_html.as_deref() }
    });
    let next_ct = if content_type == ContentType::Pdf
        && matches!(cur_ct, ContentType::Paper | ContentType::Article)
    {
        content_type
    } else {
        cur_ct
    };

    let html_changed = next_html != cur_html.as_deref();
    let ct_changed = next_ct != cur_ct;
    if !html_changed && !ct_changed {
        return Ok(false);
    }

    sqlx::query("UPDATE entry_content SET content_html = ?, content_type = ? WHERE entry_id = ?")
        .bind(next_html)
        .bind(next_ct)
        .bind(entry_id)
        .execute(&mut *tx)
        .await?;
    Ok(true)
}

/// Ensures an `entry_state` row exists with the given initial `inbox_state` and `progress = 0.0`
/// without overwriting any existing state row.
pub async fn ensure_entry_state_in_tx(
    tx: &mut WriteTx<'_>,
    entry_id: &str,
    inbox_state: InboxState,
    updated_at: DateTime<Utc>,
) -> std::result::Result<(), sqlx::Error> {
    let hlc = tx.next_hlc().await?;
    sqlx::query(
        "INSERT OR IGNORE INTO entry_state (entry_id, inbox_state, progress, updated_at, hlc)
         VALUES (?, ?, 0.0, ?, ?)",
    )
    .bind(entry_id)
    .bind(inbox_state)
    .bind(updated_at)
    .bind(&hlc)
    .execute(tx.conn())
    .await?;
    Ok(())
}

/// Inserts or updates an `entry_state` row with full reader/inbox state fields.
pub async fn upsert_entry_state_in_tx(
    tx: &mut WriteTx<'_>,
    state: &EntryStateUpsert<'_>,
) -> std::result::Result<(), sqlx::Error> {
    let hlc = tx.next_hlc().await?;
    sqlx::query(
        "INSERT INTO entry_state
         (entry_id, inbox_state, read_at, progress, interaction_updated_at, updated_at, hlc)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(entry_id) DO UPDATE SET
             inbox_state = excluded.inbox_state,
             read_at = excluded.read_at,
             progress = excluded.progress,
             interaction_updated_at = excluded.interaction_updated_at,
             updated_at = excluded.updated_at,
             hlc = excluded.hlc",
    )
    .bind(state.entry_id)
    .bind(state.inbox_state)
    .bind(state.read_at)
    .bind(state.progress)
    .bind(state.interaction_updated_at)
    .bind(state.updated_at)
    .bind(&hlc)
    .execute(tx.conn())
    .await?;
    Ok(())
}
