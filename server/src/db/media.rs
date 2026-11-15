use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{SqliteConnection, SqlitePool};
use uuid::Uuid;

use super::{
    ActivityAction, ActivityEntityType, MediaType, SyncRepo, sync::deterministic_enclosure_media_id,
};
use crate::media::MediaStore;

macro_rules! local_media_select_sql {
    ($suffix:literal) => {
        concat!(
            "SELECT m.id, m.entry_id AS item_id, m.media_type, m.local_path AS path, m.file_size, m.duration
FROM entry_media m
WHERE m.local_path IS NOT NULL ",
            $suffix
        )
    };
}

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

impl<'a> MediaRepo<'a> {
    #[must_use]
    pub const fn new(pool: &'a SqlitePool) -> Self {
        Self { pool }
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
        sqlx::query(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
             VALUES (?, ?, 'audio', ?, NULL, NULL, ?, ?)
             ON CONFLICT(id) DO UPDATE SET
                 remote_url = COALESCE(entry_media.remote_url, excluded.remote_url),
                 duration = COALESCE(entry_media.duration, excluded.duration)",
        )
        .bind(&enc_id)
        .bind(entry_id)
        .bind(clean_url)
        .bind(clean_dur)
        .bind(created_at)
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

        sqlx::query(
            "INSERT INTO entry_media (id, entry_id, media_type, remote_url, local_path, file_size, duration, created_at)
             VALUES (?, ?, ?, NULL, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(item_id)
        .bind(media_type)
        .bind(path)
        .bind(file_size)
        .bind(duration)
        .bind(now)
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
        Ok(sqlx::query_as::<_, Media>(local_media_select_sql!("AND m.id = ?"))
            .bind(id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn list_for_item(&self, item_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as::<_, Media>(local_media_select_sql!("AND m.entry_id = ?"))
            .bind(item_id)
            .fetch_all(self.pool)
            .await?)
    }

    pub async fn list_for_feed(&self, feed_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as::<_, Media>(local_media_select_sql!(
            "AND EXISTS (
                SELECT 1 FROM collection_entries ce
                WHERE ce.entry_id = m.entry_id AND ce.collection_id = ?
            )"
        ))
        .bind(feed_id)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn delete(&self, id: &str) -> Result<()> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res = sqlx::query("DELETE FROM entry_media WHERE id = ? AND local_path IS NOT NULL")
            .bind(id)
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
        Ok(sqlx::query_as::<_, Media>(local_media_select_sql!(
            "AND m.entry_id NOT IN (SELECT id FROM entries)"
        ))
        .fetch_all(self.pool)
        .await?)
    }
}
