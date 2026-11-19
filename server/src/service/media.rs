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

    /// Deletes any remaining DB rows for `media_list` and removes their underlying files
    /// from [`MediaStore`], keeping filesystem orchestration out of `db::MediaRepo`.
    pub(super) async fn remove_media_files_and_rows(
        &self,
        media_list: Vec<Media>,
    ) -> Result<(), AppError> {
        let deleted = self.service.db.media().delete_rows_returning(media_list).await?;
        for media in deleted {
            self.service.media_store.remove(&media.path).await?;
        }
        Ok(())
    }
}
