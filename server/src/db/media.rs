use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};
use uuid::Uuid;

use super::{
    ActivityAction, ActivityEntityType, MediaType, SyncRepo, sync::deterministic_enclosure_media_id,
};
use crate::media::MediaStore;

#[derive(Debug, Clone, Serialize, Deserialize)]
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
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
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
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;

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

    pub async fn list_for_feed(&self, feed_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as!(
            Media,
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type AS \"media_type: MediaType\",
                    m.local_path AS \"path!: String\",
                    m.file_size,
                    m.duration
             FROM entry_media m
             WHERE m.local_path IS NOT NULL
               AND EXISTS (
                   SELECT 1 FROM collection_entries ce
                   WHERE ce.entry_id = m.entry_id AND ce.collection_id = ?
               )",
            feed_id,
        )
        .fetch_all(self.pool)
        .await?)
    }

    /// Lists downloaded media files belonging to items in `feed_id` that will NOT remain in Inbox
    /// after deleting `feed_id` (i.e. items that are neither starred nor cross-posted in another feed).
    pub async fn list_exclusively_owned_by_feed(&self, feed_id: &str) -> Result<Vec<Media>> {
        let starred_id = super::STARRED_COLLECTION_ID;
        Ok(sqlx::query_as!(
            Media,
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type AS \"media_type: MediaType\",
                    m.local_path AS \"path!: String\",
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
                   WHERE ce2.entry_id = m.entry_id
                     AND ce2.collection_id != ?
                     AND (
                         ce2.collection_id = ?
                         OR EXISTS (
                             SELECT 1 FROM feed_sources fs2
                             WHERE fs2.collection_id = ce2.collection_id
                         )
                     )
               )",
            feed_id,
            feed_id,
            starred_id,
        )
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn delete(&self, id: &str) -> Result<()> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res =
            sqlx::query!("DELETE FROM entry_media WHERE id = ? AND local_path IS NOT NULL", id,)
                .execute(&mut *tx)
                .await?;
        if res.rows_affected() > 0 {
            SyncRepo::record_activity(
                &mut tx,
                ActivityEntityType::Media,
                id,
                ActivityAction::Delete,
                now,
            )
            .await?;
        }
        tx.commit().await?;
        Ok(())
    }

    pub async fn delete_with_files(
        &self,
        store: &MediaStore,
        media_list: Vec<Media>,
    ) -> Result<()> {
        for media in media_list {
            if let Err(e) = store.remove(&media.path).await {
                tracing::warn!(path = %media.path, error = %e, "Skipped removing media outside media_dir or failed I/O");
            }
            self.delete(&media.id).await?;
        }
        Ok(())
    }

    pub async fn find_orphaned(&self) -> Result<Vec<Media>> {
        Ok(sqlx::query_as!(
            Media,
            "SELECT m.id,
                    m.entry_id AS item_id,
                    m.media_type AS \"media_type: MediaType\",
                    m.local_path AS \"path!: String\",
                    m.file_size,
                    m.duration
             FROM entry_media m
             WHERE m.local_path IS NOT NULL
               AND (
                   m.entry_id NOT IN (SELECT id FROM entries WHERE merged_into_id IS NULL)
                   OR EXISTS (
                       SELECT 1 FROM entry_state es
                       WHERE es.entry_id = m.entry_id AND es.inbox_state = 'none'
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
