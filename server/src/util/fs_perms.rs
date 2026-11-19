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
/// directories to `0700` (skipping shared system/home/temp/cwd roots).
pub async fn ensure_private_dir(dir: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        let mut builder = tokio::fs::DirBuilder::new();
        builder.recursive(true).mode(0o700);
        builder.create(dir).await?;
        if is_safe_to_chmod_dir(dir) {
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

/// Creates `file` if missing with `0600` permissions on Unix and tightens existing file mode to `0600`.
pub async fn precreate_or_tighten_private_file(file: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut opts = tokio::fs::OpenOptions::new();
        opts.write(true).create(true).truncate(false).mode(0o600);
        let handle = opts.open(file).await?;
        drop(handle);
        let _ = tokio::fs::set_permissions(file, std::fs::Permissions::from_mode(0o600)).await;
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

/// Tightens `file` permissions to `0600` on Unix if the file exists.
pub async fn tighten_private_file_if_exists(file: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if tokio::fs::try_exists(file).await.unwrap_or(false) {
            let _ = tokio::fs::set_permissions(file, std::fs::Permissions::from_mode(0o600)).await;
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
