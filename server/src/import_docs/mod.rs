mod manifest;
mod media;
#[cfg(test)]
mod tests;

use std::{
    collections::{HashMap, HashSet},
    future::Future,
    path::{Path, PathBuf},
    pin::Pin,
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result, bail};
use quick_xml::{Reader, events::Event};
use roche_core::document_meta::{encode_doi_for_url_path, extract_arxiv_id_from_doi};
use serde::{Deserialize, Serialize};
use tokio::{sync::Semaphore, task::JoinSet};
use url::Url;

pub use self::manifest::{LocalDocCollectionSpec, LocalDocSpec, LocalDocsImportManifest};
use self::{
    manifest::{
        DocFormat, MAX_LOCAL_HTML_BYTES, MAX_LOCAL_PDF_BYTES, ValidatedDocJob,
        validate_document_url, validate_manifest_jobs,
    },
    media::{PdfMediaOutcome, ensure_pdf_media_stored, read_file_bounded, sha256_hex},
};
use crate::{
    content::{
        HtmlSanitizer, MAX_SUMMARY_CHARS, SafeHttpClient, extract_reflowed_pdf_html,
        is_valid_pdf_bytes, xml_util,
    },
    db::{
        BatchCollectionInput, ContentType, Db, EntryIdentifier, LocalDocDbBatch,
        LocalDocDbBatchResult, LocalDocDbInput, UpsertEntryInput,
    },
    domain::EntryKind,
    media::MediaStore,
    service::enrich::{
        ArtistMetadataFetcher, DocumentIdentifierHint, discover_verified_pdf_metadata,
        fetch_arxiv_html5,
    },
    util::media_urls::{extract_arxiv_id, strip_arxiv_version},
};

const DEFAULT_IMPORT_CONCURRENCY: usize = 4;
const MAX_IMPORT_CONCURRENCY: usize = 16;
const MAX_ENRICH_FETCH_ATTEMPTS: u32 = 3;

/// Per-host paced, transient-retry wrapper around [`SafeHttpClient`] for batch document
/// enrichment so concurrent imports against Crossref, `DataCite`, and arXiv stay polite and
/// recover cleanly from HTTP 429 / 5xx throttling.
struct PoliteEnrichmentFetcher {
    client: SafeHttpClient,
    next_slot_by_host: Mutex<HashMap<String, tokio::time::Instant>>,
}

impl PoliteEnrichmentFetcher {
    fn new(client: SafeHttpClient) -> Self {
        Self { client, next_slot_by_host: Mutex::new(HashMap::new()) }
    }

    const fn host_spacing(host: &str) -> Duration {
        if host.eq_ignore_ascii_case("export.arxiv.org") || host.eq_ignore_ascii_case("arxiv.org") {
            Duration::from_millis(1_000)
        } else if host.eq_ignore_ascii_case("api.crossref.org")
            || host.eq_ignore_ascii_case("api.datacite.org")
        {
            Duration::from_millis(450)
        } else {
            Duration::from_millis(250)
        }
    }

    async fn wait_for_host_slot(&self, host: &str) {
        let spacing = Self::host_spacing(host);
        let wait_until = {
            let now = tokio::time::Instant::now();
            let mut slots =
                self.next_slot_by_host.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            let slot = slots.get(host).copied().filter(|&t| t > now).unwrap_or(now);
            slots.insert(host.to_owned(), slot + spacing);
            drop(slots);
            slot
        };
        if wait_until > tokio::time::Instant::now() {
            tokio::time::sleep_until(wait_until).await;
        }
    }

    fn push_host_backoff(&self, host: &str, backoff: Duration) {
        let target = tokio::time::Instant::now() + backoff;
        let mut slots =
            self.next_slot_by_host.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let entry = slots.entry(host.to_owned()).or_insert(target);
        if *entry < target {
            *entry = target;
        }
        drop(slots);
    }
}

pub(crate) fn is_retryable_fetch_error(msg: &str) -> bool {
    let lower = msg.to_ascii_lowercase();
    if lower.contains("disallowed")
        || lower.contains("not a valid public")
        || lower.contains("exceeded maximum")
        || lower.contains("exceeds limit")
        || lower.contains("too many redirects")
        || lower.contains("invalid")
    {
        return false;
    }
    if lower.contains("http 4") {
        return lower.contains("http 429") || lower.contains("http 408");
    }
    true
}

impl ArtistMetadataFetcher for PoliteEnrichmentFetcher {
    fn fetch_text<'a>(
        &'a self,
        url: &'a Url,
        max_bytes: usize,
    ) -> Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>> {
        Box::pin(async move {
            let host = url.host_str().unwrap_or("").to_ascii_lowercase();
            for attempt in 0..MAX_ENRICH_FETCH_ATTEMPTS {
                if !host.is_empty() {
                    self.wait_for_host_slot(&host).await;
                }
                match self.client.fetch_text(url, max_bytes).await {
                    Ok(body) => return Some(body),
                    Err(err) => {
                        let msg = err.to_string();
                        if attempt + 1 >= MAX_ENRICH_FETCH_ATTEMPTS
                            || !is_retryable_fetch_error(&msg)
                        {
                            return None;
                        }
                        let backoff =
                            Duration::from_secs(2u64.saturating_mul(u64::from(attempt) + 1));
                        if !host.is_empty() {
                            self.push_host_backoff(&host, backoff);
                        }
                    }
                }
            }
            None
        })
    }
}

/// Execution options controlling document extraction, online verification, and persistence.
#[derive(Debug, Clone)]
pub struct LocalDocsImportOptions {
    pub base_dir_override: Option<PathBuf>,
    pub enrich: bool,
    pub reflow_pdfs: bool,
    pub concurrency: usize,
    pub dry_run: bool,
}

impl Default for LocalDocsImportOptions {
    fn default() -> Self {
        Self {
            base_dir_override: None,
            enrich: false,
            reflow_pdfs: true,
            concurrency: DEFAULT_IMPORT_CONCURRENCY,
            dry_run: false,
        }
    }
}

/// Per-document import summary included in [`LocalDocsImportReport`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ImportedDocumentReport {
    pub source_path: String,
    pub entry_id: String,
    pub kind: EntryKind,
    pub title: String,
    pub creators: Vec<String>,
    pub release_year: Option<i32>,
    pub url: Option<String>,
    pub doi: Option<String>,
    pub arxiv_id: Option<String>,
    pub created: bool,
    pub content_html_bytes: usize,
    pub pdf_stored_path: Option<String>,
    pub added_to_want_to_read: bool,
}

/// Aggregate import report emitted by [`import_local_documents`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LocalDocsImportReport {
    pub dry_run: bool,
    pub collections_processed: usize,
    pub documents_total: usize,
    pub entries_created: usize,
    pub entries_updated: usize,
    pub pdf_media_stored: usize,
    pub pdf_media_reused: usize,
    pub reflowed_html_count: usize,
    pub dois_detected: usize,
    pub arxiv_ids_detected: usize,
    pub added_to_want_to_read: usize,
    pub items: Vec<ImportedDocumentReport>,
}

#[derive(Debug)]
struct PreparedDoc {
    index: usize,
    source_path: PathBuf,
    display_path: String,
    format: DocFormat,
    expected_sha256: Option<String>,
    expected_size: i64,
    doi: Option<String>,
    arxiv_id: Option<String>,
    db_input: LocalDocDbInput,
}

/// Imports a declarative manifest of local PDF and HTML documents into `db` and `media_store`.
///
/// Heavy CPU and network work (PDF parsing, Crossref/arXiv/DataCite verification, PDF-to-HTML
/// reflow, HTML sanitization) runs concurrently off-DB before persisting all catalog rows in one
/// atomic SQLite transaction. PDF files are copied into `media_store` only after the DB commit
/// succeeds (and never during `dry_run`).
pub async fn import_local_documents(
    db: &Db,
    media_store: &MediaStore,
    manifest: &LocalDocsImportManifest,
    opts: &LocalDocsImportOptions,
) -> Result<LocalDocsImportReport> {
    let fetcher = if opts.enrich {
        Some(Arc::new(PoliteEnrichmentFetcher::new(
            SafeHttpClient::new(Duration::from_secs(15))
                .context("Failed to initialize HTTP client for document enrichment")?,
        )))
    } else {
        None
    };
    import_local_documents_with_fetcher(db, media_store, manifest, opts, fetcher).await
}

/// Generic variant of [`import_local_documents`] allowing deterministic test fetchers.
pub async fn import_local_documents_with_fetcher<M: ArtistMetadataFetcher + 'static>(
    db: &Db,
    media_store: &MediaStore,
    manifest: &LocalDocsImportManifest,
    opts: &LocalDocsImportOptions,
    fetcher: Option<Arc<M>>,
) -> Result<LocalDocsImportReport> {
    let effective_base = opts.base_dir_override.as_deref().or(manifest.base_dir.as_deref());
    let jobs = validate_manifest_jobs(manifest, effective_base).await?;

    let active_fetcher = if opts.enrich { fetcher } else { None };
    let prepared = prepare_documents_concurrently(
        jobs,
        opts.reflow_pdfs,
        opts.concurrency.clamp(1, MAX_IMPORT_CONCURRENCY),
        active_fetcher,
    )
    .await?;

    let collections: Vec<BatchCollectionInput> = manifest
        .collections
        .iter()
        .map(|col| BatchCollectionInput {
            ref_key: col.ref_key.clone(),
            id: None,
            title: col.title.clone(),
            slug: col.slug.clone(),
            role: col.role,
            summary: col.summary.clone(),
            color: col.color.clone(),
            members: Vec::new(),
            replace_members: false,
        })
        .collect();

    let db_batch = LocalDocDbBatch {
        collections,
        documents: prepared.iter().map(|p| p.db_input.clone()).collect(),
        dry_run: opts.dry_run,
    };

    let db_result = db
        .catalog()
        .apply_local_document_batch(&db_batch)
        .await
        .context("Failed to persist imported local documents to database")?;

    finalize_import_report_and_media(db, media_store, &prepared, &db_result, opts.dry_run).await
}

async fn finalize_import_report_and_media(
    db: &Db,
    media_store: &MediaStore,
    prepared: &[PreparedDoc],
    db_result: &LocalDocDbBatchResult,
    dry_run: bool,
) -> Result<LocalDocsImportReport> {
    let mut report = LocalDocsImportReport {
        dry_run,
        collections_processed: db_result.collections_processed,
        documents_total: prepared.len(),
        items: Vec::with_capacity(prepared.len()),
        ..Default::default()
    };
    let mut media_failures: Vec<String> = Vec::new();

    for (prep, applied) in prepared.iter().zip(db_result.items.iter()) {
        let mut pdf_stored_path = None;
        if prep.format == DocFormat::Pdf {
            let planned_rel_path = format!("pdfs/{}.pdf", applied.entry_id);
            if dry_run {
                pdf_stored_path = Some(planned_rel_path);
            } else {
                let expected_hash = prep.expected_sha256.as_deref().unwrap_or("");
                match ensure_pdf_media_stored(
                    db,
                    media_store,
                    &applied.entry_id,
                    &prep.source_path,
                    expected_hash,
                    prep.expected_size,
                    MAX_LOCAL_PDF_BYTES,
                )
                .await
                {
                    Ok(PdfMediaOutcome::Stored(path)) => {
                        report.pdf_media_stored += 1;
                        pdf_stored_path = Some(path);
                    }
                    Ok(PdfMediaOutcome::Reused(path)) => {
                        report.pdf_media_reused += 1;
                        pdf_stored_path = Some(path);
                    }
                    Err(err) => {
                        media_failures.push(format!(
                            "entry '{}' ('{}' / {}): {err:#}",
                            applied.entry_id, prep.db_input.entry.title, prep.display_path
                        ));
                    }
                }
            }
        }

        if applied.created {
            report.entries_created += 1;
        } else {
            report.entries_updated += 1;
        }
        let html_bytes = prep.db_input.content_html.as_deref().map_or(0, str::len);
        if html_bytes > 0 {
            report.reflowed_html_count += 1;
        }
        if prep.doi.is_some() {
            report.dois_detected += 1;
        }
        if prep.arxiv_id.is_some() {
            report.arxiv_ids_detected += 1;
        }
        if applied.added_to_want_to_read {
            report.added_to_want_to_read += 1;
        }

        report.items.push(ImportedDocumentReport {
            source_path: prep.display_path.clone(),
            entry_id: applied.entry_id.clone(),
            kind: prep.db_input.entry.kind.unwrap_or(EntryKind::Paper),
            title: prep.db_input.entry.title.clone(),
            creators: prep.db_input.entry.creators.clone(),
            release_year: prep.db_input.entry.release_year,
            url: applied.persisted_url.clone().or_else(|| prep.db_input.entry.url.clone()),
            doi: prep.doi.clone(),
            arxiv_id: prep.arxiv_id.clone(),
            created: applied.created,
            content_html_bytes: html_bytes,
            pdf_stored_path,
            added_to_want_to_read: applied.added_to_want_to_read,
        });
    }

    if !media_failures.is_empty() {
        bail!(
            "Database batch committed ({} entries), but {} PDF media file(s) failed to copy/register ({} stored, {} reused):\n  - {}",
            report.documents_total,
            media_failures.len(),
            report.pdf_media_stored,
            report.pdf_media_reused,
            media_failures.join("\n  - ")
        );
    }

    Ok(report)
}

async fn prepare_documents_concurrently<M: ArtistMetadataFetcher + 'static>(
    jobs: Vec<ValidatedDocJob>,
    reflow_pdfs: bool,
    concurrency: usize,
    fetcher: Option<Arc<M>>,
) -> Result<Vec<PreparedDoc>> {
    let sem = Arc::new(Semaphore::new(concurrency));
    let mut join_set = JoinSet::new();

    for job in jobs {
        let permit = Arc::clone(&sem)
            .acquire_owned()
            .await
            .map_err(|_| anyhow::anyhow!("Document import concurrency semaphore closed"))?;
        let task_fetcher = fetcher.as_ref().map(Arc::clone);
        join_set.spawn(async move {
            let _permit = permit;
            prepare_single_document(job, reflow_pdfs, task_fetcher.as_deref()).await
        });
    }

    let mut prepared = Vec::new();
    while let Some(joined) = join_set.join_next().await {
        let doc = joined.context("Document preparation worker panicked")??;
        prepared.push(doc);
    }
    prepared.sort_by_key(|p| p.index);
    Ok(prepared)
}

async fn prepare_single_document<M: ArtistMetadataFetcher>(
    job: ValidatedDocJob,
    reflow_pdfs: bool,
    fetcher: Option<&M>,
) -> Result<PreparedDoc> {
    match job.format {
        DocFormat::Pdf => prepare_pdf_document(job, reflow_pdfs, fetcher).await,
        DocFormat::Html => prepare_html_document(job).await,
    }
}

async fn prepare_pdf_document<M: ArtistMetadataFetcher>(
    job: ValidatedDocJob,
    reflow_pdfs: bool,
    fetcher: Option<&M>,
) -> Result<PreparedDoc> {
    let bytes = read_file_bounded(&job.source_path, MAX_LOCAL_PDF_BYTES)
        .await
        .with_context(|| format!("Failed reading PDF '{}'", job.source_path.display()))?;
    if !is_valid_pdf_bytes(&bytes) {
        bail!("File '{}' is not a valid PDF (missing %PDF- header)", job.display_path);
    }
    let expected_size = i64::try_from(bytes.len()).unwrap_or(i64::MAX);
    let expected_sha256 = sha256_hex(&bytes);

    let discovered = discover_verified_pdf_metadata(
        bytes,
        Some(job.source_path.clone()),
        job.explicit_url.clone(),
        DocumentIdentifierHint {
            title: job.spec.title.as_deref(),
            authors: &job.spec.creators,
            release_year: job.spec.release_year,
            doi: job.explicit_doi.as_deref(),
            arxiv_id: job.explicit_arxiv_id.as_deref(),
        },
        fetcher,
    )
    .await;

    let final_title = resolve_final_title(
        job.spec.title.as_deref(),
        discovered.title.as_deref(),
        &job.source_path,
    );
    let final_creators =
        if job.spec.creators.is_empty() { discovered.authors } else { job.spec.creators.clone() };
    let final_published_at = job.spec.published_at.or(discovered.published_at);
    let final_release_year = job.spec.release_year.or(discovered.release_year);
    let final_summary = resolve_final_summary(job.spec.summary.as_deref(), discovered.summary);
    let final_doi = job.explicit_doi.or(discovered.doi);
    let final_arxiv_id = job
        .explicit_arxiv_id
        .or(discovered.arxiv_id)
        .or_else(|| final_doi.as_deref().and_then(extract_arxiv_id_from_doi))
        .map(|id| strip_arxiv_version(&id).to_owned());
    let final_isbn = job.explicit_isbn.or(discovered.isbn);

    let canonical_url = derive_pdf_canonical_url(
        job.explicit_url.as_deref(),
        final_doi.as_deref(),
        final_arxiv_id.as_deref(),
    );

    let content_html = if reflow_pdfs {
        let arxiv_html = match (final_arxiv_id.as_deref(), fetcher) {
            (Some(aid), Some(f)) => fetch_arxiv_html5(aid, f).await,
            _ => None,
        };
        if let Some(html) = arxiv_html {
            Some(html)
        } else {
            extract_reflowed_pdf_html(Some(&job.source_path), None, Some(&final_title)).await
        }
    } else {
        None
    };

    let identifiers = merge_document_identifiers(
        final_doi.as_deref(),
        final_arxiv_id.as_deref(),
        final_isbn.as_deref(),
        &job.extra_identifiers,
    );

    let entry = UpsertEntryInput {
        id: None,
        kind: Some(job.kind),
        title: final_title,
        creators: final_creators,
        url: canonical_url,
        published_at: final_published_at,
        release_year: final_release_year,
        summary: final_summary,
        thumbnail_url: None,
        metadata: job.spec.metadata,
        identifiers,
        parent_collection_id: None,
        parent_position: None,
    };

    Ok(PreparedDoc {
        index: job.index,
        source_path: job.source_path,
        display_path: job.display_path,
        format: DocFormat::Pdf,
        expected_sha256: Some(expected_sha256),
        expected_size,
        doi: final_doi,
        arxiv_id: final_arxiv_id,
        db_input: LocalDocDbInput {
            entry,
            content_type: ContentType::Pdf,
            content_html,
            collections: job.spec.collections,
            position: job.spec.position,
            want_to_read: job.spec.want_to_read,
            tags: job.spec.tags,
        },
    })
}

async fn prepare_html_document(job: ValidatedDocJob) -> Result<PreparedDoc> {
    let bytes = read_file_bounded(&job.source_path, MAX_LOCAL_HTML_BYTES)
        .await
        .with_context(|| format!("Failed reading HTML '{}'", job.source_path.display()))?;
    let expected_size = i64::try_from(bytes.len()).unwrap_or(i64::MAX);
    let raw_html = String::from_utf8_lossy(&bytes).into_owned();

    let explicit_url_parsed = job.explicit_url.as_deref().and_then(|u| Url::parse(u).ok());
    let fallback_base = explicit_url_parsed.as_ref().or(job.explicit_base_url.as_ref());
    let canonical_from_html = extract_canonical_html_url_with_base(&raw_html, fallback_base);
    let effective_base = explicit_url_parsed
        .clone()
        .or_else(|| job.explicit_base_url.clone())
        .or_else(|| canonical_from_html.clone());
    let final_url = job
        .explicit_url
        .clone()
        .or_else(|| canonical_from_html.map(String::from))
        .or_else(|| job.explicit_base_url.as_ref().map(ToString::to_string));

    let snap_base = effective_base.clone();
    let snap = tokio::task::spawn_blocking(move || {
        HtmlSanitizer::extract_snapshot_with_base(&raw_html, snap_base.as_ref())
    })
    .await
    .context("HTML snapshot extraction task failed")?;

    let final_title =
        resolve_final_title(job.spec.title.as_deref(), snap.title.as_deref(), &job.source_path);
    let final_creators =
        if job.spec.creators.is_empty() { snap.authors } else { job.spec.creators.clone() };
    let final_published_at = job.spec.published_at.or(snap.published_at);
    let final_release_year = job.spec.release_year.or(snap.release_year);
    let final_summary = resolve_final_summary(job.spec.summary.as_deref(), snap.summary);
    let final_doi = job.explicit_doi.or(snap.doi);
    let final_arxiv_id = job
        .explicit_arxiv_id
        .or_else(|| final_url.as_deref().and_then(extract_arxiv_id))
        .or_else(|| final_doi.as_deref().and_then(extract_arxiv_id_from_doi))
        .map(|id| strip_arxiv_version(&id).to_owned());
    let final_isbn = job.explicit_isbn.or(snap.isbn);

    let identifiers = merge_document_identifiers(
        final_doi.as_deref(),
        final_arxiv_id.as_deref(),
        final_isbn.as_deref(),
        &job.extra_identifiers,
    );
    let content_html = (!snap.content_html.trim().is_empty()).then_some(snap.content_html);

    let entry = UpsertEntryInput {
        id: None,
        kind: Some(job.kind),
        title: final_title,
        creators: final_creators,
        url: final_url,
        published_at: final_published_at,
        release_year: final_release_year,
        summary: final_summary,
        thumbnail_url: snap.thumbnail_url,
        metadata: job.spec.metadata,
        identifiers,
        parent_collection_id: None,
        parent_position: None,
    };

    Ok(PreparedDoc {
        index: job.index,
        source_path: job.source_path,
        display_path: job.display_path,
        format: DocFormat::Html,
        expected_sha256: None,
        expected_size,
        doi: final_doi,
        arxiv_id: final_arxiv_id,
        db_input: LocalDocDbInput {
            entry,
            content_type: ContentType::Article,
            content_html,
            collections: job.spec.collections,
            position: job.spec.position,
            want_to_read: job.spec.want_to_read,
            tags: job.spec.tags,
        },
    })
}

/// Extracts a public canonical URL from `<link rel="canonical" href="...">` or
/// `<meta property="og:url" content="...">` inside a local HTML document.
#[must_use]
pub fn extract_canonical_html_url(raw_html: &str) -> Option<Url> {
    extract_canonical_html_url_with_base(raw_html, None)
}

/// Extracts a canonical URL from local HTML, resolving relative `href`s against `fallback_base`.
///
/// Normalizes raw HTML5 through `ammonia` (`html5ever`) first so inline `<script>` (`if (a < b)`),
/// `<template>`, and unquoted HTML5 attributes never break scanning.
#[must_use]
pub fn extract_canonical_html_url_with_base(
    raw_html: &str,
    fallback_base: Option<&Url>,
) -> Option<Url> {
    let head_slice = &raw_html[..raw_html.floor_char_boundary(256 * 1024)];
    let mut builder = ammonia::Builder::new();
    builder
        .tags(HashSet::from(["link", "meta"]))
        .clean_content_tags(HashSet::from([
            "script", "style", "template", "noscript", "svg", "math",
        ]))
        .add_tag_attributes("link", &["rel", "href"])
        .add_tag_attributes("meta", &["property", "name", "content"])
        .url_schemes(HashSet::from(["http", "https"]));
    let normalized = builder.clean(head_slice).to_string();

    let mut reader = Reader::from_str(&normalized);
    reader.config_mut().check_end_names = false;

    let mut og_url: Option<Url> = None;
    let mut events = 0usize;
    while events < 1024 {
        events += 1;
        match reader.read_event() {
            Ok(Event::Start(tag) | Event::Empty(tag)) => {
                let name = tag.name();
                let raw_name = name.as_ref();
                if raw_name.eq_ignore_ascii_case("link") {
                    let mut is_canonical = false;
                    let mut href = None;
                    for (k, v) in xml_util::iter_attrs(&tag) {
                        if k.eq_ignore_ascii_case("rel")
                            && v.split_whitespace().any(|tok| tok.eq_ignore_ascii_case("canonical"))
                        {
                            is_canonical = true;
                        } else if k.eq_ignore_ascii_case("href") {
                            href = Some(v);
                        }
                    }
                    if is_canonical
                        && let Some(raw_href) = href
                        && let Some(valid) =
                            resolve_candidate_canonical_url(&raw_href, fallback_base)
                    {
                        return Some(valid);
                    }
                } else if raw_name.eq_ignore_ascii_case("meta") && og_url.is_none() {
                    let mut is_og_url = false;
                    let mut content = None;
                    for (k, v) in xml_util::iter_attrs(&tag) {
                        if (k.eq_ignore_ascii_case("property") || k.eq_ignore_ascii_case("name"))
                            && v.trim().eq_ignore_ascii_case("og:url")
                        {
                            is_og_url = true;
                        } else if k.eq_ignore_ascii_case("content") {
                            content = Some(v);
                        }
                    }
                    if is_og_url
                        && let Some(raw_content) = content
                        && let Some(valid) =
                            resolve_candidate_canonical_url(&raw_content, fallback_base)
                    {
                        og_url = Some(valid);
                    }
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    og_url
}

fn resolve_candidate_canonical_url(raw: &str, fallback_base: Option<&Url>) -> Option<Url> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let candidate = match Url::parse(trimmed) {
        Ok(u) => u,
        Err(url::ParseError::RelativeUrlWithoutBase) => fallback_base?.join(trimmed).ok()?,
        Err(_) => return None,
    };
    validate_document_url(candidate.as_str(), "canonical").ok()
}

/// Derives a canonical, Android-syncable public HTTPS URL for a PDF from (in priority order):
/// explicit manifest URL, published (non-arXiv-DataCite) DOI, arXiv abstract URL, or preprint DOI.
/// Percent-encodes special DOI characters (`<`, `>`, `#`, `?`, `[`, `]`) safely.
#[must_use]
pub(crate) fn derive_pdf_canonical_url(
    explicit_url: Option<&str>,
    doi: Option<&str>,
    arxiv_id: Option<&str>,
) -> Option<String> {
    if let Some(u) = explicit_url.map(str::trim).filter(|s| !s.is_empty()) {
        return Some(u.to_owned());
    }
    if let Some(d) = doi
        && extract_arxiv_id_from_doi(d).is_none()
        && let Some(encoded) = encode_doi_for_url_path(d)
        && let Ok(valid) = validate_document_url(&format!("https://doi.org/{encoded}"), "doi_url")
    {
        return Some(valid.to_string());
    }
    if let Some(aid) = arxiv_id {
        let versionless = strip_arxiv_version(aid);
        if let Ok(valid) =
            validate_document_url(&format!("https://arxiv.org/abs/{versionless}"), "arxiv_url")
        {
            return Some(valid.to_string());
        }
    }
    doi.and_then(encode_doi_for_url_path)
        .and_then(|enc| validate_document_url(&format!("https://doi.org/{enc}"), "doi_url").ok())
        .map(String::from)
}

fn resolve_final_title(
    manifest_title: Option<&str>,
    discovered_title: Option<&str>,
    path: &Path,
) -> String {
    if let Some(t) = manifest_title.map(str::trim).filter(|s| !s.is_empty()) {
        return t.to_owned();
    }
    if let Some(t) = discovered_title.map(str::trim).filter(|s| !s.is_empty()) {
        return t.to_owned();
    }
    let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("");
    let cleaned = stem.replace(['_', '-'], " ").split_whitespace().collect::<Vec<_>>().join(" ");
    if cleaned.is_empty() { "Untitled Document".to_owned() } else { cleaned }
}

fn resolve_final_summary(
    manifest_summary: Option<&str>,
    discovered_summary: Option<String>,
) -> Option<String> {
    if let Some(s) = manifest_summary.map(str::trim).filter(|s| !s.is_empty()) {
        return Some(s.to_owned());
    }
    discovered_summary.and_then(|s| {
        let clean = HtmlSanitizer::plain_text_truncate(&s, MAX_SUMMARY_CHARS);
        (!clean.is_empty()).then_some(clean)
    })
}

fn merge_document_identifiers(
    doi: Option<&str>,
    arxiv_id: Option<&str>,
    isbn: Option<&str>,
    extra: &[EntryIdentifier],
) -> Vec<EntryIdentifier> {
    let mut out = Vec::new();
    let mut seen: HashSet<(String, String)> = HashSet::new();
    let mut push = |scheme: &str, val: &str| {
        let s = scheme.trim().to_ascii_lowercase();
        let v = val.trim();
        if !s.is_empty() && !v.is_empty() && seen.insert((s.clone(), v.to_owned())) {
            out.push(EntryIdentifier { scheme: s, value: v.to_owned() });
        }
    };
    if let Some(d) = doi {
        push("doi", d);
    }
    if let Some(a) = arxiv_id {
        push("arxiv", a);
    }
    if let Some(i) = isbn {
        push("isbn", i);
    }
    for ident in extra {
        push(&ident.scheme, &ident.value);
    }
    out
}
