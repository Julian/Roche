use std::path::Path;

use anyhow::{Context, Result, bail};
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt as _;

use crate::{db::Db, domain::MediaType, media::MediaStore};

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) enum PdfMediaOutcome {
    Stored(String),
    Reused(String),
}

/// Reads a regular file safely into memory while strictly enforcing `max_bytes` against TOCTOU
/// growth or special-file substitution.
pub(super) async fn read_file_bounded(path: &Path, max_bytes: u64) -> Result<Vec<u8>> {
    let meta = tokio::fs::metadata(path)
        .await
        .with_context(|| format!("Failed inspecting file '{}'", path.display()))?;
    if !meta.is_file() {
        bail!("Path '{}' is not a regular file", path.display());
    }
    if meta.len() > max_bytes {
        bail!("File '{}' exceeds maximum allowed size of {max_bytes} bytes", path.display());
    }
    let file = tokio::fs::File::open(path)
        .await
        .with_context(|| format!("Failed opening file '{}'", path.display()))?;
    let mut reader = file.take(max_bytes.saturating_add(1));
    let mut buf = Vec::with_capacity(usize::try_from(meta.len().min(4 * 1024 * 1024)).unwrap_or(0));
    reader
        .read_to_end(&mut buf)
        .await
        .with_context(|| format!("Failed reading file '{}'", path.display()))?;
    if u64::try_from(buf.len()).unwrap_or(u64::MAX) > max_bytes {
        bail!(
            "File '{}' exceeded maximum allowed size of {max_bytes} bytes while reading",
            path.display()
        );
    }
    Ok(buf)
}

pub(super) fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hex::encode(hasher.finalize())
}

pub(super) async fn ensure_pdf_media_stored(
    db: &Db,
    media_store: &MediaStore,
    entry_id: &str,
    source_path: &Path,
    expected_sha256: &str,
    expected_size: i64,
    max_pdf_bytes: u64,
) -> Result<PdfMediaOutcome> {
    let rel_path = format!("pdfs/{entry_id}.pdf");
    let safe_target = media_store.validate_path_async(Path::new(&rel_path)).await?;
    let existing_rows = db.media().list_for_item(entry_id).await?;
    let existing_row =
        existing_rows.iter().find(|m| m.media_type == MediaType::Pdf && m.path == rel_path);

    let mut had_existing_file = false;
    if let Ok(existing_meta) = tokio::fs::metadata(&safe_target).await
        && existing_meta.is_file()
    {
        had_existing_file = true;
        if i64::try_from(existing_meta.len()).ok() == Some(expected_size)
            && let Ok(existing_bytes) = read_file_bounded(&safe_target, max_pdf_bytes).await
            && sha256_hex(&existing_bytes) == expected_sha256
        {
            if existing_row.is_none_or(|row| row.file_size != Some(expected_size)) {
                db.media()
                    .create(entry_id, MediaType::Pdf, &rel_path, Some(expected_size), None)
                    .await?;
            }
            return Ok(PdfMediaOutcome::Reused(rel_path));
        }
    }

    let bytes = read_file_bounded(source_path, max_pdf_bytes)
        .await
        .with_context(|| format!("Failed re-reading source PDF '{}'", source_path.display()))?;
    if sha256_hex(&bytes) != expected_sha256 {
        bail!(
            "Source PDF '{}' changed on disk during import; aborting media copy",
            source_path.display()
        );
    }

    let written_size = media_store.write_bytes(&rel_path, &bytes).await?;
    if let Err(err) =
        db.media().create(entry_id, MediaType::Pdf, &rel_path, Some(written_size), None).await
    {
        if !had_existing_file {
            let _ = media_store.delete_file(&rel_path).await;
        }
        return Err(err);
    }

    Ok(PdfMediaOutcome::Stored(rel_path))
}
