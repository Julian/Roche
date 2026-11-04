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

    pub async fn open_file(&self, stored_path: &str) -> Result<tokio::fs::File> {
        let safe_path = self.validate_path(Path::new(stored_path))?;
        Ok(tokio::fs::File::open(&safe_path).await?)
    }

    pub async fn write_bytes(&self, stored_path: &str, bytes: &[u8]) -> Result<i64> {
        let safe_path = self.validate_path(Path::new(stored_path))?;
        if let Some(parent) = safe_path.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        // Re-validate after creating parent directories to guard against TOCTOU symlink swaps
        let verified_path = self.validate_path(&safe_path)?;
        tokio::fs::write(&verified_path, bytes).await?;
        Ok(i64::try_from(bytes.len()).unwrap_or(i64::MAX))
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
        let safe_path = self.validate_path(Path::new(stored_path))?;
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
}
