pub mod ytdlp;

use std::path::{Component, Path, PathBuf};

use anyhow::Result;

use crate::db::MediaType;

/// Manages media files on disk while strictly enforcing path containment within `media_dir`
/// to prevent path traversal (`../`), symlink escapes, or arbitrary host file deletion.
#[derive(Debug, Clone)]
pub struct MediaStore {
    base_dir: PathBuf,
}

impl MediaStore {
    #[must_use]
    pub fn new(base_dir: impl Into<PathBuf>) -> Self {
        let raw = base_dir.into();
        Self { base_dir: Self::normalize_lexical(&raw) }
    }

    #[must_use]
    pub fn normalize_lexical(path: &Path) -> PathBuf {
        let abs = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
        let mut normalized = PathBuf::new();
        for comp in abs.components() {
            match comp {
                Component::CurDir => {}
                Component::ParentDir => {
                    let _ = normalized.pop();
                }
                other => normalized.push(other.as_os_str()),
            }
        }
        normalized
    }

    #[must_use]
    pub fn base_dir(&self) -> &Path {
        &self.base_dir
    }

    /// Converts a path inside `base_dir` to a normalized forward-slash relative path for database storage.
    pub fn relative_path(&self, candidate: &Path) -> Result<String> {
        let safe_path = self.validate_path(candidate)?;
        let rel = safe_path
            .strip_prefix(&self.base_dir)
            .map_err(|_| anyhow::anyhow!("Media path is outside configured media_dir"))?;
        Ok(rel.to_string_lossy().replace('\\', "/"))
    }

    /// Validates that `candidate` resolves inside `self.base_dir` with no parent-directory (`..`)
    /// traversal components or symlinks escaping `base_dir`.
    pub fn validate_path(&self, candidate: &Path) -> Result<PathBuf> {
        for comp in candidate.components() {
            if matches!(comp, Component::ParentDir | Component::Prefix(_)) {
                anyhow::bail!("Path traversal components are not allowed in media paths");
            }
        }

        let norm_cand = if candidate.is_absolute() {
            let normalized = Self::normalize_lexical(candidate);
            if !normalized.starts_with(&self.base_dir) {
                anyhow::bail!("Media path is outside configured media_dir");
            }
            normalized
        } else {
            Self::normalize_lexical(&self.base_dir.join(candidate))
        };

        let rel = norm_cand
            .strip_prefix(&self.base_dir)
            .map_err(|_| anyhow::anyhow!("Media path is outside configured media_dir"))?;
        let mut current = self.base_dir.clone();
        for comp in rel.components() {
            current.push(comp.as_os_str());
            if let Ok(meta) = std::fs::symlink_metadata(&current)
                && meta.file_type().is_symlink()
            {
                anyhow::bail!("Symlinks are not permitted inside media_dir");
            }
        }

        if let Some(base_ancestor) = self.base_dir.ancestors().find(|a| a.exists())
            && let Ok(canon_base_anc) = std::fs::canonicalize(base_ancestor)
            && let Some(cand_ancestor) = norm_cand.ancestors().find(|a| a.exists())
            && let Ok(canon_cand_anc) = std::fs::canonicalize(cand_ancestor)
            && !canon_cand_anc.starts_with(&canon_base_anc)
        {
            anyhow::bail!("Canonical media path escapes configured media_dir");
        }

        Ok(norm_cand)
    }

    async fn validate_path_async(&self, candidate: &Path) -> Result<PathBuf> {
        let store = self.clone();
        let cand = candidate.to_path_buf();
        tokio::task::spawn_blocking(move || store.validate_path(&cand))
            .await
            .map_err(|e| anyhow::anyhow!("Media path validation task failed: {e}"))?
    }

    pub async fn open_file(&self, stored_path: &str) -> Result<tokio::fs::File> {
        let safe_path = self.validate_path_async(Path::new(stored_path)).await?;
        Ok(tokio::fs::File::open(&safe_path).await?)
    }

    pub async fn write_bytes(&self, stored_path: &str, bytes: &[u8]) -> Result<i64> {
        let safe_path = self.validate_path_async(Path::new(stored_path)).await?;
        if let Some(parent) = safe_path.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        // Re-validate after creating parent directories to guard against TOCTOU symlink swaps
        let verified_path = self.validate_path_async(&safe_path).await?;
        let file_name = verified_path.file_name().and_then(|n| n.to_str()).unwrap_or("media");
        let temp_name = format!(".tmp-{}-{file_name}", uuid::Uuid::new_v4());
        let temp_path = verified_path.with_file_name(temp_name);

        if let Err(e) = tokio::fs::write(&temp_path, bytes).await {
            let _ = tokio::fs::remove_file(&temp_path).await;
            return Err(e.into());
        }
        if let Err(e) = tokio::fs::rename(&temp_path, &verified_path).await {
            let _ = tokio::fs::remove_file(&temp_path).await;
            return Err(e.into());
        }
        Ok(i64::try_from(bytes.len()).unwrap_or(i64::MAX))
    }

    pub async fn delete_file(&self, stored_path: &str) -> Result<()> {
        self.remove(stored_path).await
    }

    /// Removes any `.tmp-*` temporary files older than `max_age` inside `base_dir`, `pdfs/`, and `thumbnails/`.
    pub async fn cleanup_stale_temp_files(&self, max_age: std::time::Duration) -> Result<usize> {
        let mut removed = 0;
        let dirs =
            [self.base_dir.clone(), self.base_dir.join("pdfs"), self.base_dir.join("thumbnails")];
        for dir in dirs {
            let Ok(mut entries) = tokio::fs::read_dir(&dir).await else {
                continue;
            };
            while let Ok(Some(entry)) = entries.next_entry().await {
                if !entry.file_name().to_string_lossy().starts_with(".tmp-") {
                    continue;
                }
                let Ok(meta) = entry.metadata().await else {
                    continue;
                };
                if !meta.is_file() {
                    continue;
                }
                let is_stale = meta
                    .modified()
                    .ok()
                    .and_then(|m| m.elapsed().ok())
                    .is_some_and(|age| age >= max_age);
                if is_stale && tokio::fs::remove_file(entry.path()).await.is_ok() {
                    removed += 1;
                }
            }
        }
        Ok(removed)
    }

    #[must_use]
    pub fn content_type_for(media_type: MediaType, stored_path: &str) -> &'static str {
        let ext = Path::new(stored_path)
            .extension()
            .and_then(|e| e.to_str())
            .map(str::to_ascii_lowercase);
        match ext.as_deref() {
            Some("pdf") => "application/pdf",
            Some("webm") => "video/webm",
            Some("mkv") => "video/x-matroska",
            Some("mp4" | "m4v") => "video/mp4",
            Some("m4a") => "audio/mp4",
            Some("ogg" | "opus") => "audio/ogg",
            Some("mp3") => "audio/mpeg",
            Some("png") => "image/png",
            Some("webp") => "image/webp",
            Some("jpg" | "jpeg") => "image/jpeg",
            _ => match media_type {
                MediaType::Pdf => "application/pdf",
                MediaType::Video => "video/mp4",
                MediaType::Audio => "audio/mpeg",
                MediaType::Thumbnail => "image/jpeg",
            },
        }
    }

    pub async fn remove(&self, stored_path: &str) -> Result<()> {
        let safe_path = self.validate_path_async(Path::new(stored_path)).await?;
        match tokio::fs::remove_file(&safe_path).await {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(e.into()),
        }
        if let Some(parent) = safe_path.parent()
            && parent != self.base_dir
            && parent.starts_with(&self.base_dir)
        {
            let _ = tokio::fs::remove_dir(parent).await;
        }
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_media_store_rejects_path_traversal() {
        let store = MediaStore::new("/var/lib/roche/media");
        assert!(store.validate_path(Path::new("video1.mp4")).is_ok());
        assert!(store.validate_path(Path::new("/var/lib/roche/media/sub/video1.mp4")).is_ok());
        assert!(store.validate_path(Path::new("../roche.db")).is_err());
        assert!(store.validate_path(Path::new("/var/lib/roche/media/../../etc/passwd")).is_err());
        assert!(store.validate_path(Path::new("/etc/passwd")).is_err());

        // Relative media_dir resolves against cwd so absolute paths under it work seamlessly
        let rel_store = MediaStore::new("./data/media");
        let expected_abs = std::env::current_dir().unwrap().join("data/media/clip.mp4");
        assert!(rel_store.validate_path(&expected_abs).is_ok());
    }

    #[tokio::test]
    async fn test_media_store_atomic_write_bytes_and_delete_file() {
        let temp_dir =
            std::env::temp_dir().join(format!("roche-media-atomic-{}", uuid::Uuid::new_v4()));
        let store = MediaStore::new(&temp_dir);

        let payload = b"%PDF-1.7\natomic-write-test\n%%EOF";
        let size = store.write_bytes("pdfs/paper-1.pdf", payload).await.unwrap();
        assert_eq!(size, i64::try_from(payload.len()).unwrap());

        let written = tokio::fs::read(temp_dir.join("pdfs/paper-1.pdf")).await.unwrap();
        assert_eq!(written, payload);

        // Verify no temporary .tmp-* sibling files remain in pdfs/
        let mut entries = tokio::fs::read_dir(temp_dir.join("pdfs")).await.unwrap();
        while let Some(entry) = entries.next_entry().await.unwrap() {
            let name = entry.file_name();
            assert!(!name.to_string_lossy().starts_with(".tmp-"));
        }

        store.delete_file("pdfs/paper-1.pdf").await.unwrap();
        assert!(!temp_dir.join("pdfs/paper-1.pdf").exists());

        // Stale .tmp-* file in pdfs/ is cleaned up by cleanup_stale_temp_files
        tokio::fs::create_dir_all(temp_dir.join("pdfs")).await.unwrap();
        let stale_tmp = temp_dir.join("pdfs/.tmp-1234-aborted.pdf");
        tokio::fs::write(&stale_tmp, b"partial").await.unwrap();
        let removed = store.cleanup_stale_temp_files(std::time::Duration::ZERO).await.unwrap();
        assert_eq!(removed, 1);
        assert!(!stale_tmp.exists());

        let _ = tokio::fs::remove_dir_all(&temp_dir).await;
    }
}
