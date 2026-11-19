use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};
use uuid::Uuid;

use super::{
    ActivityAction, ActivityEntityType, MediaType, SyncRepo, sync::deterministic_enclosure_media_id,
};
use crate::util::clock::now_utc;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
#[allow(clippy::struct_field_names)]
pub struct Media {
    pub id: String,
    pub item_id: String,
    pub media_type: MediaType,
    pub path: String,
    pub file_size: Option<i64>,
    pub duration: Option<i64>,
}

pub struct MediaRepo<'a> {
    pub(crate) pool: &'a SqlitePool,
}

pub struct NewMediaSpec<'a> {
    pub media_type: MediaType,
    pub path: &'a str,
    pub file_size: Option<i64>,
    pub duration: Option<i64>,
}

impl<'a> MediaRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn create_batch(
        &self,
        item_id: &str,
        specs: &[NewMediaSpec<'_>],
    ) -> Result<Vec<Media>> {
        if specs.is_empty() {
            return Ok(Vec::new());
        }
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        let mut out = Vec::with_capacity(specs.len());

        for spec in specs {
            let id = Uuid::new_v4().to_string();
            sqlx::query!(
                "INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
                 VALUES (?, ?, ?, NULL, ?, ?, ?, ?)",
                id,
                item_id,
                spec.media_type as _,
                spec.path,
                spec.file_size,
                spec.duration,
                now as _,
            )
            .execute(&mut *tx)
            .await?;

            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Media,
                &id,
                ActivityAction::Create,
                now,
            )
            .await?;

            out.push(Media {
                id,
                item_id: item_id.to_owned(),
                media_type: spec.media_type,
                path: spec.path.to_owned(),
                file_size: spec.file_size,
                duration: spec.duration,
            });
        }

        tx.commit().await?;
        Ok(out)
    }

    pub(crate) async fn upsert_audio_enclosure_in_tx(
        conn: &mut SqliteConnection,
        entry_id: &str,
        audio_url: Option<&str>,
        duration: Option<i64>,
        created_at: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let clean_url = audio_url.map(str::trim).filter(|s| !s.is_empty());
        let clean_dur = duration.filter(|&d| d > 0);
        if clean_url.is_none() && clean_dur.is_none() {
            return Ok(());
        }
        let enc_id = deterministic_enclosure_media_id(entry_id);
        sqlx::query!(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
             VALUES (?, ?, 'audio', ?, NULL, NULL, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                 remote_url = COALESCE(entry_media.remote_url, excluded.remote_url),
                 duration = COALESCE(entry_media.duration, excluded.duration)",
            enc_id,
            entry_id,
            clean_url,
            clean_dur,
            created_at as _,
        )
        .execute(&mut *conn)
        .await?;
        Ok(())
    }

    pub async fn create(
        &self,
        item_id: &str,
        media_type: MediaType,
        path: &str,
        file_size: Option<i64>,
        duration: Option<i64>,
    ) -> Result<Media> {
        let id = Uuid::new_v4().to_string();
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;

        sqlx::query!(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
             VALUES (?, ?, ?, NULL, ?, ?, ?, ?)",
            id,
            item_id,
            media_type as _,
            path,
            file_size,
            duration,
            now as _,
        )
        .execute(&mut *tx)
        .await?;

        SyncRepo::record_activity(
            &mut tx,
            ActivityEntityType::Media,
            &id,
            ActivityAction::Create,
            now,
        )
        .await?;

        tx.commit().await?;

        Ok(Media {
            id,
            item_id: item_id.to_string(),
            media_type,
            path: path.to_string(),
            file_size,
            duration,
        })
    }

    pub async fn get(&self, id: &str) -> Result<Option<Media>> {
        Ok(sqlx::query_as!(
            Media,
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type AS \"media_type: MediaType\",
                    m.local_path AS \"path!: String\",
                    m.file_size,
                    m.duration
             FROM entry_media m
             WHERE m.local_path IS NOT NULL AND m.id = ?",
            id,
        )
        .fetch_optional(self.pool)
        .await?)
    }

    pub async fn list_for_item(&self, item_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as!(
            Media,
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type AS \"media_type: MediaType\",
                    m.local_path AS \"path!: String\",
                    m.file_size,
                    m.duration
             FROM entry_media m
             WHERE m.local_path IS NOT NULL AND m.entry_id = ?",
            item_id,
        )
        .fetch_all(self.pool)
        .await?)
    }

    /// Lists downloaded media files belonging to items in `feed_id` that will NOT remain readable
    /// after deleting `feed_id` (i.e. items that are neither saved in a `list`/`want` collection
    /// such as `Starred` or `Want to Read`, nor cross-posted in another subscribed feed).
    pub async fn list_exclusively_owned_by_feed(&self, feed_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as::<_, Media>(
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type,
                    m.local_path AS path,
                    m.file_size,
                    m.duration
             FROM entry_media m
             WHERE m.local_path IS NOT NULL
               AND EXISTS (
                   SELECT 1 FROM collection_entries ce
                   WHERE ce.entry_id = m.entry_id AND ce.collection_id = ?
               )
               AND NOT EXISTS (
                   SELECT 1 FROM collection_entries ce2
                   LEFT JOIN collection_specs cs2 ON cs2.collection_id = ce2.collection_id
                   WHERE ce2.entry_id = m.entry_id
                     AND ce2.collection_id != ?
                     AND (
                         cs2.collection_role IN ('list', 'want')
                         OR EXISTS (
                             SELECT 1 FROM feed_sources fs2
                             WHERE fs2.collection_id = ce2.collection_id
                         )
                     )
               )",
        )
        .bind(feed_id)
        .bind(feed_id)
        .fetch_all(self.pool)
        .await?)
    }

    async fn delete_local_in_tx(
        tx: &mut SqliteConnection,
        id: &str,
        now: DateTime<Utc>,
    ) -> std::result::Result<(), sqlx::Error> {
        let res =
            sqlx::query!("DELETE FROM entry_media WHERE id = ? AND local_path IS NOT NULL", id,)
                .execute(&mut *tx)
                .await?;
        if res.rows_affected() > 0 {
            SyncRepo::record_activity(
                tx,
                ActivityEntityType::Media,
                id,
                ActivityAction::Delete,
                now,
            )
            .await?;
        }
        Ok(())
    }

    pub async fn delete(&self, id: &str) -> Result<()> {
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        Self::delete_local_in_tx(&mut tx, id, now).await?;
        tx.commit().await?;
        Ok(())
    }

    /// Deletes any remaining local media rows for `media_list` in a single write transaction
    /// (recording delete activity for each deleted row) and returns `media_list` so callers
    /// can unlink the corresponding files on disk.
    pub async fn delete_rows_returning(&self, media_list: Vec<Media>) -> Result<Vec<Media>> {
        if media_list.is_empty() {
            return Ok(media_list);
        }
        let now = now_utc();
        let mut tx = crate::db::begin_write(self.pool).await?;
        for media in &media_list {
            Self::delete_local_in_tx(&mut tx, &media.id, now).await?;
        }
        tx.commit().await?;
        Ok(media_list)
    }

    pub async fn find_orphaned(&self) -> Result<Vec<Media>> {
        Ok(sqlx::query_as::<_, Media>(
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type,
                    m.local_path AS path,
                    m.file_size,
                    m.duration
             FROM entry_media m
             WHERE m.local_path IS NOT NULL
               AND (
                   m.entry_id NOT IN (SELECT id FROM entries WHERE merged_into_id IS NULL)
                   OR (
                       NOT EXISTS (
                           SELECT 1 FROM entry_state es
                           WHERE es.entry_id = m.entry_id AND es.inbox_state IN ('unread', 'dismissed')
                       )
                       AND NOT EXISTS (
                           SELECT 1 FROM entry_content ec
                           WHERE ec.entry_id = m.entry_id
                       )
                       AND NOT EXISTS (
                           SELECT 1 FROM collection_entries ce
                           JOIN collection_specs cs ON cs.collection_id = ce.collection_id
                           WHERE ce.entry_id = m.entry_id AND cs.collection_role IN ('list', 'want')
                       )
                   )
               )",
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn list_registered_item_ids(&self) -> Result<std::collections::HashSet<String>> {
        let rows = sqlx::query_scalar!(
            "SELECT DISTINCT entry_id AS \"entry_id!: String\" FROM entry_media WHERE local_path IS NOT NULL",
        )
        .fetch_all(self.pool)
        .await?;
        Ok(rows.into_iter().collect())
    }
}
