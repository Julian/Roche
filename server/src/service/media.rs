use super::{AppError, RocheService};
use crate::{db::Media, media::MediaStore};

pub struct MediaStream {
    pub content_type: &'static str,
    pub filename: String,
    pub total_len: u64,
    pub file: tokio::fs::File,
}

pub struct MediaService<'a> {
    pub(super) service: &'a RocheService,
}

impl MediaService<'_> {
    pub async fn open_stream(&self, id: &str) -> Result<MediaStream, AppError> {
        let repo = self.service.db.media();
        let media = repo.get(id).await?.ok_or_else(|| AppError::not_found("Media not found"))?;
        let content_type = MediaStore::content_type_for(media.media_type, &media.path);
        let file = self.service.media_store.open_file(&media.path).await.map_err(|e| {
            if e.downcast_ref::<std::io::Error>()
                .is_some_and(|io_err| io_err.kind() == std::io::ErrorKind::NotFound)
            {
                AppError::not_found("Media file not found on disk")
            } else {
                AppError::Internal(e)
            }
        })?;
        let total_len = match file.metadata().await {
            Ok(meta) => meta.len(),
            Err(_) => media.file_size.and_then(|s| u64::try_from(s).ok()).unwrap_or(0),
        };
        let filename = std::path::Path::new(&media.path)
            .file_name()
            .and_then(|n| n.to_str())
            .map(|name| {
                name.chars()
                    .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
                    .collect::<String>()
            })
            .filter(|s| !s.trim_matches('.').is_empty() && !s.starts_with('.'))
            .unwrap_or_else(|| "media".to_string());
        Ok(MediaStream { content_type, filename, total_len, file })
    }

    pub async fn delete(&self, id: &str) -> Result<(), AppError> {
        let repo = self.service.db.media();
        let media = repo.get(id).await?.ok_or_else(|| AppError::not_found("Media not found"))?;

        self.service.media_store.remove(&media.path).await?;
        repo.delete(id).await?;
        Ok(())
    }

    /// Unlinks underlying media files from [`MediaStore`] before deleting their DB rows so an
    /// I/O error never strands an untracked orphan file on disk.
    pub(super) async fn remove_media_files_and_rows(
        &self,
        media_list: Vec<Media>,
    ) -> Result<(), AppError> {
        if let Some(err) =
            unlink_and_delete_media_rows(&self.service.db, &self.service.media_store, media_list)
                .await?
        {
            return Err(AppError::Internal(err));
        }
        Ok(())
    }
}

/// Unlinks each media file on disk first and deletes only the DB rows whose files were cleanly
/// unlinked (or already missing), returning the first unlink error (if any) after flushing all
/// successful row deletes.
pub async fn unlink_and_delete_media_rows(
    db: &crate::db::Db,
    media_store: &MediaStore,
    media_list: Vec<Media>,
) -> anyhow::Result<Option<anyhow::Error>> {
    let mut unlinked = Vec::with_capacity(media_list.len());
    let mut first_err = None;
    for media in media_list {
        match media_store.remove(&media.path).await {
            Ok(()) => unlinked.push(media),
            Err(e) => {
                tracing::warn!(
                    media_id = %media.id,
                    path = %media.path,
                    error = %e,
                    "Failed to unlink media file; retaining DB row for cleanup retry"
                );
                if first_err.is_none() {
                    first_err = Some(e);
                }
            }
        }
    }
    if !unlinked.is_empty() {
        let _ = db.media().delete_rows_returning(unlinked).await?;
    }
    Ok(first_err)
}
