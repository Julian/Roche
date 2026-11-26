use std::{
    collections::HashSet,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail};
use chrono::{DateTime, Utc};
use roche_core::{
    document_meta::{normalize_doi, normalize_isbn},
    identity_norm::normalize_identifier_scheme,
};
use serde::{Deserialize, Serialize};
use url::Url;

use crate::{
    db::EntryIdentifier,
    domain::{CollectionRole, EntryKind},
    util::{
        media_urls::{extract_arxiv_id, is_valid_arxiv_id, strip_arxiv_version},
        url_guard::validate_public_url,
    },
};

pub(super) const MAX_LOCAL_PDF_BYTES: u64 = 50 * 1024 * 1024;
pub(super) const MAX_LOCAL_HTML_BYTES: u64 = 10 * 1024 * 1024;

/// Declarative JSON manifest for importing local PDF and HTML documents into Roche.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LocalDocsImportManifest {
    /// Optional base directory for resolving relative `documents[*].path` values.
    #[serde(default)]
    pub base_dir: Option<PathBuf>,
    /// Manual collections (`List` or `Want`) to create or update before attaching documents.
    #[serde(default)]
    pub collections: Vec<LocalDocCollectionSpec>,
    /// Ordered list of local PDF or HTML documents to import.
    #[serde(default)]
    pub documents: Vec<LocalDocSpec>,
}

/// Collection specification inside a [`LocalDocsImportManifest`].
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocalDocCollectionSpec {
    #[serde(default)]
    pub ref_key: Option<String>,
    pub title: String,
    #[serde(default)]
    pub slug: Option<String>,
    #[serde(default = "default_list_role")]
    pub role: CollectionRole,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub color: Option<String>,
}

const fn default_list_role() -> CollectionRole {
    CollectionRole::List
}

/// Single local PDF or HTML document specification inside a [`LocalDocsImportManifest`].
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LocalDocSpec {
    pub path: PathBuf,
    #[serde(default)]
    pub kind: Option<EntryKind>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub creators: Vec<String>,
    #[serde(default)]
    pub url: Option<String>,
    /// Optional base URL for resolving relative links/images in local HTML files when `url` is absent.
    #[serde(default)]
    pub base_url: Option<String>,
    #[serde(default)]
    pub published_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub release_year: Option<i32>,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub doi: Option<String>,
    #[serde(default)]
    pub arxiv_id: Option<String>,
    #[serde(default)]
    pub isbn: Option<String>,
    #[serde(default)]
    pub identifiers: Vec<EntryIdentifier>,
    #[serde(default)]
    pub metadata: Option<serde_json::Value>,
    #[serde(default)]
    pub collections: Vec<String>,
    #[serde(default)]
    pub position: Option<f64>,
    #[serde(default)]
    pub want_to_read: bool,
    #[serde(default)]
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum DocFormat {
    Pdf,
    Html,
}

#[derive(Debug)]
pub(super) struct ValidatedDocJob {
    pub index: usize,
    pub source_path: PathBuf,
    pub display_path: String,
    pub format: DocFormat,
    pub kind: EntryKind,
    pub spec: LocalDocSpec,
    pub explicit_url: Option<String>,
    pub explicit_base_url: Option<Url>,
    pub explicit_doi: Option<String>,
    pub explicit_arxiv_id: Option<String>,
    pub explicit_isbn: Option<String>,
    pub extra_identifiers: Vec<EntryIdentifier>,
}

pub(super) async fn validate_manifest_jobs(
    manifest: &LocalDocsImportManifest,
    base_dir: Option<&Path>,
) -> Result<Vec<ValidatedDocJob>> {
    for (idx, col) in manifest.collections.iter().enumerate() {
        if col.title.trim().is_empty()
            && col.slug.as_deref().map(str::trim).is_none_or(str::is_empty)
        {
            bail!("Manifest collection #{idx} must specify a non-empty `title` or `slug`");
        }
        if !matches!(col.role, CollectionRole::List | CollectionRole::Want) {
            bail!(
                "Manifest collection '{}' has unsupported role '{:?}'; expected 'list' or 'want'",
                col.title,
                col.role
            );
        }
    }

    let mut jobs = Vec::with_capacity(manifest.documents.len());
    let mut seen_canon_paths: HashSet<PathBuf> = HashSet::with_capacity(manifest.documents.len());
    for (index, spec) in manifest.documents.iter().enumerate() {
        let job = validate_single_doc_job(index, spec, base_dir).await?;
        let canon = tokio::fs::canonicalize(&job.source_path)
            .await
            .unwrap_or_else(|_| job.source_path.clone());
        if !seen_canon_paths.insert(canon) {
            bail!(
                "Document #{index} ('{}') duplicates an earlier file path in the same manifest",
                job.display_path
            );
        }
        jobs.push(job);
    }
    Ok(jobs)
}

async fn validate_single_doc_job(
    index: usize,
    spec: &LocalDocSpec,
    base_dir: Option<&Path>,
) -> Result<ValidatedDocJob> {
    let display_path = spec.path.display().to_string();
    if spec.path.as_os_str().is_empty() {
        bail!("Document #{index} has an empty `path`");
    }
    let resolved_path = if spec.path.is_absolute() {
        spec.path.clone()
    } else if let Some(base) = base_dir {
        base.join(&spec.path)
    } else {
        spec.path.clone()
    };

    let format = detect_doc_format(&resolved_path).with_context(|| {
        format!("Document #{index} ('{display_path}') has unsupported file type")
    })?;

    let meta = tokio::fs::metadata(&resolved_path).await.with_context(|| {
        format!("Document #{index} not found or unreadable: '{}'", resolved_path.display())
    })?;
    if !meta.is_file() {
        bail!("Document #{index} path is not a regular file: '{}'", resolved_path.display());
    }
    let max_bytes = match format {
        DocFormat::Pdf => MAX_LOCAL_PDF_BYTES,
        DocFormat::Html => MAX_LOCAL_HTML_BYTES,
    };
    if meta.len() == 0 {
        bail!("Document #{index} is empty (0 bytes): '{}'", resolved_path.display());
    }
    if meta.len() > max_bytes {
        bail!(
            "Document #{index} ('{display_path}') exceeds maximum allowed size of {max_bytes} bytes ({} bytes)",
            meta.len()
        );
    }

    let kind = match spec.kind {
        Some(EntryKind::Collection) => {
            bail!("Document #{index} ('{display_path}') cannot have kind 'collection'");
        }
        Some(k) => k,
        None => match format {
            DocFormat::Pdf => EntryKind::Paper,
            DocFormat::Html => EntryKind::Article,
        },
    };

    if let Some(pos) = spec.position
        && !pos.is_finite()
    {
        bail!("Document #{index} ('{display_path}') has non-finite `position`");
    }
    if let Some(ref metadata_val) = spec.metadata
        && !metadata_val.is_object()
    {
        bail!("Document #{index} ('{display_path}') `metadata` must be a JSON object");
    }

    let explicit_url = match spec.url.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw) => Some(
            validate_document_url(raw, "url")
                .with_context(|| format!("Document #{index} ('{display_path}')"))?
                .to_string(),
        ),
        None => None,
    };

    let explicit_base_url = match spec.base_url.as_deref().map(str::trim).filter(|s| !s.is_empty())
    {
        Some(raw) => Some(
            validate_document_url(raw, "base_url")
                .with_context(|| format!("Document #{index} ('{display_path}')"))?,
        ),
        None => None,
    };

    let ids = validate_explicit_identifiers(index, &display_path, spec)?;

    Ok(ValidatedDocJob {
        index,
        source_path: resolved_path,
        display_path,
        format,
        kind,
        spec: spec.clone(),
        explicit_url,
        explicit_base_url,
        explicit_doi: ids.doi,
        explicit_arxiv_id: ids.arxiv_id,
        explicit_isbn: ids.isbn,
        extra_identifiers: ids.extra,
    })
}

struct NormalizedSpecIdentifiers {
    doi: Option<String>,
    arxiv_id: Option<String>,
    isbn: Option<String>,
    extra: Vec<EntryIdentifier>,
}

fn validate_explicit_identifiers(
    index: usize,
    display_path: &str,
    spec: &LocalDocSpec,
) -> Result<NormalizedSpecIdentifiers> {
    let mut explicit_doi = match spec.doi.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw) => Some(normalize_doi(raw).ok_or_else(|| {
            anyhow::anyhow!("Document #{index} ('{display_path}') has invalid DOI '{raw}'")
        })?),
        None => None,
    };

    let mut explicit_arxiv_id =
        match spec.arxiv_id.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            Some(raw) => Some(parse_valid_arxiv_id(index, display_path, raw)?),
            None => None,
        };

    let mut explicit_isbn = match spec.isbn.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(raw) => Some(normalize_isbn(raw).ok_or_else(|| {
            anyhow::anyhow!("Document #{index} ('{display_path}') has invalid ISBN '{raw}'")
        })?),
        None => None,
    };

    let mut extra = Vec::with_capacity(spec.identifiers.len());
    for ident in &spec.identifiers {
        let scheme = normalize_identifier_scheme(&ident.scheme);
        let value = ident.value.trim();
        if scheme.is_empty() || value.is_empty() {
            bail!(
                "Document #{index} ('{display_path}') contains an empty identifier scheme or value"
            );
        }
        if matches!(scheme.as_str(), "slug" | "tag" | "feed_url") {
            bail!(
                "Document #{index} ('{display_path}') cannot specify reserved identifier scheme '{scheme}'"
            );
        }
        match scheme.as_str() {
            "url" => {
                let valid = validate_document_url(value, "identifiers[url]")
                    .with_context(|| format!("Document #{index} ('{display_path}')"))?;
                extra.push(EntryIdentifier { scheme, value: valid.to_string() });
            }
            "doi" => {
                let norm = normalize_doi(value).ok_or_else(|| {
                    anyhow::anyhow!(
                        "Document #{index} ('{display_path}') has invalid DOI '{value}'"
                    )
                })?;
                if explicit_doi.is_none() {
                    explicit_doi = Some(norm.clone());
                }
                extra.push(EntryIdentifier { scheme, value: norm });
            }
            "arxiv" => {
                let norm = parse_valid_arxiv_id(index, display_path, value)?;
                if explicit_arxiv_id.is_none() {
                    explicit_arxiv_id = Some(norm.clone());
                }
                extra.push(EntryIdentifier { scheme, value: norm });
            }
            "isbn" => {
                let norm = normalize_isbn(value).ok_or_else(|| {
                    anyhow::anyhow!(
                        "Document #{index} ('{display_path}') has invalid ISBN '{value}'"
                    )
                })?;
                if explicit_isbn.is_none() {
                    explicit_isbn = Some(norm.clone());
                }
                extra.push(EntryIdentifier { scheme, value: norm });
            }
            _ => extra.push(EntryIdentifier { scheme, value: value.to_owned() }),
        }
    }

    Ok(NormalizedSpecIdentifiers {
        doi: explicit_doi,
        arxiv_id: explicit_arxiv_id,
        isbn: explicit_isbn,
        extra,
    })
}

fn parse_valid_arxiv_id(index: usize, display_path: &str, raw: &str) -> Result<String> {
    let parsed = extract_arxiv_id(raw).filter(|id| is_valid_arxiv_id(id)).ok_or_else(|| {
        anyhow::anyhow!("Document #{index} ('{display_path}') has invalid arXiv ID '{raw}'")
    })?;
    Ok(strip_arxiv_version(&parsed).to_owned())
}

fn detect_doc_format(path: &Path) -> Result<DocFormat> {
    let ext = path.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase);
    match ext.as_deref() {
        Some("pdf") => Ok(DocFormat::Pdf),
        Some("html" | "htm") => Ok(DocFormat::Html),
        Some(other) => {
            bail!("Unsupported document extension '.{other}'; expected .pdf, .html, or .htm")
        }
        None => bail!("Document path has no file extension; expected .pdf, .html, or .htm"),
    }
}

pub(super) fn validate_document_url(raw: &str, field_name: &str) -> Result<Url> {
    let parsed =
        Url::parse(raw.trim()).with_context(|| format!("Invalid `{field_name}` URL '{raw}'"))?;
    if !matches!(parsed.scheme(), "http" | "https") {
        bail!("`{field_name}` must use http:// or https:// (got '{}')", parsed.scheme());
    }
    if let Some(host) = parsed.host_str()
        && (host.eq_ignore_ascii_case("roche.local")
            || host.to_ascii_lowercase().ends_with(".roche.local"))
    {
        bail!("`{field_name}` cannot use synthetic internal host '{host}'");
    }
    validate_public_url(&parsed)
        .map_err(|e| anyhow::anyhow!("`{field_name}` is not a valid public URL: {e}"))?;
    Ok(parsed)
}
