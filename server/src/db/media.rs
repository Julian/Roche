use anyhow::Result;
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
#[cfg(test)]
use uuid::Uuid;

use super::{ActivityAction, ActivityEntityType, MediaType, SyncRepo};
use crate::media::MediaStore;

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

    #[cfg(test)]
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

        sqlx::query(
            "INSERT INTO media (id, item_id, media_type, path, file_size, duration, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(item_id)
        .bind(media_type)
        .bind(path)
        .bind(file_size)
        .bind(duration)
        .bind(now)
        .execute(self.pool)
        .await?;

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
        Ok(sqlx::query_as::<_, Media>("SELECT * FROM media WHERE id = ?")
            .bind(id)
            .fetch_optional(self.pool)
            .await?)
    }

    pub async fn list_for_item(&self, item_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as::<_, Media>("SELECT * FROM media WHERE item_id = ?")
            .bind(item_id)
            .fetch_all(self.pool)
            .await?)
    }

    pub async fn list_for_feed(&self, feed_id: &str) -> Result<Vec<Media>> {
        Ok(sqlx::query_as::<_, Media>(
            "SELECT m.* FROM media m
             JOIN items i ON m.item_id = i.id
             WHERE i.feed_id = ?",
        )
        .bind(feed_id)
        .fetch_all(self.pool)
        .await?)
    }

    pub async fn delete(&self, id: &str) -> Result<()> {
        let now = SyncRepo::now_utc();
        let mut tx = self.pool.begin().await?;
        let res = sqlx::query("DELETE FROM media WHERE id = ?").bind(id).execute(&mut *tx).await?;
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
        Ok(sqlx::query_as::<_, Media>(
            "SELECT * FROM media WHERE item_id NOT IN (SELECT id FROM items)",
        )
        .fetch_all(self.pool)
        .await?)
    }
}
