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
        let abs = std::path::absolute(&raw).unwrap_or(raw);
        Self { base_dir: abs }
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
            let normalized = std::path::absolute(candidate)?;
            if !normalized.starts_with(&self.base_dir) {
                anyhow::bail!("Media path is outside configured media_dir");
            }
            normalized
        } else {
            std::path::absolute(self.base_dir.join(candidate))?
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

        if let Ok(canon_base) = std::fs::canonicalize(&self.base_dir)
            && let Some(existing_ancestor) =
                norm_cand.ancestors().find(|a| a.starts_with(&self.base_dir) && a.exists())
            && let Ok(canon_ancestor) = std::fs::canonicalize(existing_ancestor)
            && !canon_ancestor.starts_with(&canon_base)
        {
            anyhow::bail!("Canonical media path escapes configured media_dir");
        }

        Ok(norm_cand)
    }

    pub async fn open_file(&self, stored_path: &str) -> Result<tokio::fs::File> {
        let safe_path = self.validate_path(Path::new(stored_path))?;
        Ok(tokio::fs::File::open(&safe_path).await?)
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
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e.into()),
        }
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
