use std::path::{Component, Path, PathBuf};

use anyhow::Result;

/// Lexically normalizes `path` against the current working directory, resolving `.` and `..`
/// components without touching the filesystem.
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

/// Creates `dir` recursively with `0700` permissions on Unix and tightens existing dedicated
/// directories to `0700` (skipping shared system/home/temp/cwd roots and symlinks).
pub async fn ensure_private_dir(dir: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        let mut builder = tokio::fs::DirBuilder::new();
        builder.recursive(true).mode(0o700);
        builder.create(dir).await?;
        if is_safe_to_chmod_dir(dir)
            && tokio::fs::symlink_metadata(dir)
                .await
                .is_ok_and(|meta| meta.is_dir() && !meta.file_type().is_symlink())
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = tokio::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700)).await;
        }
    }
    #[cfg(not(unix))]
    {
        tokio::fs::create_dir_all(dir).await?;
    }
    Ok(())
}

/// Creates `file` if missing with `0600` permissions on Unix and tightens existing file mode
/// to `0600` via file-descriptor `fchmod` (refusing to follow symlinks).
pub async fn precreate_or_tighten_private_file(file: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = tokio::fs::symlink_metadata(file).await
            && meta.file_type().is_symlink()
        {
            anyhow::bail!("Refusing to open or chmod symlinked private file: {}", file.display());
        }
        let mut opts = tokio::fs::OpenOptions::new();
        opts.write(true).create(true).truncate(false).mode(0o600);
        let handle = opts.open(file).await?;
        let meta = handle.metadata().await?;
        if meta.is_file() {
            let _ = handle.set_permissions(std::fs::Permissions::from_mode(0o600)).await;
        }
    }
    #[cfg(not(unix))]
    {
        let handle = tokio::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(false)
            .open(file)
            .await?;
        drop(handle);
    }
    Ok(())
}

/// Tightens `file` permissions to `0600` on Unix if the file exists (using `fchmod` on the opened
/// file descriptor and refusing symlinks).
pub async fn tighten_private_file_if_exists(file: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let is_regular = tokio::fs::symlink_metadata(file)
            .await
            .is_ok_and(|meta| meta.is_file() && !meta.file_type().is_symlink());
        if !is_regular {
            return;
        }
        if let Ok(handle) = tokio::fs::OpenOptions::new().read(true).open(file).await
            && handle.metadata().await.is_ok_and(|m| m.is_file())
        {
            let _ = handle.set_permissions(std::fs::Permissions::from_mode(0o600)).await;
        }
    }
    #[cfg(not(unix))]
    {
        let _ = file;
    }
}

#[cfg(unix)]
fn is_safe_to_chmod_dir(dir: &Path) -> bool {
    let abs = normalize_lexical(dir);
    if abs == Path::new("/")
        || abs == Path::new("/tmp")
        || abs == Path::new("/private/tmp")
        || abs == Path::new("/var/tmp")
    {
        return false;
    }
    if std::env::current_dir().is_ok_and(|cwd| normalize_lexical(&cwd) == abs) {
        return false;
    }
    let temp_norm = normalize_lexical(&std::env::temp_dir());
    if abs == temp_norm {
        return false;
    }
    if std::env::var_os("HOME")
        .map(PathBuf::from)
        .is_some_and(|home| normalize_lexical(&home) == abs)
    {
        return false;
    }
    true
}
