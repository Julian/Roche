use std::{
    future::Future,
    path::{Path, PathBuf},
    pin::Pin,
    time::Duration,
};

use chrono::Datelike;
use roche_core::document_meta::{
    ExtractedDocumentMetadata, extract_arxiv_id_from_text, extract_doi_from_text,
    extract_metadata_from_plain_text, extract_pdf_metadata, is_placeholder_entry_title,
    normalize_doi, parse_crossref_work_json,
};
use tokio::io::AsyncReadExt as _;
use url::Url;

use super::ArtistMetadataFetcher;
use crate::{
    content::{
        HtmlSanitizer, MAX_SUMMARY_CHARS, extract_reflowed_pdf_html, is_valid_pdf_bytes,
        resolve_pdf_url,
    },
    db::EntryDetailView,
    domain::{EntryKind, MediaType},
    feeds::ArxivParser,
    ingest::FeedFetcher,
    service::RocheService,
    util::{
        media_urls::{extract_arxiv_id, is_valid_arxiv_id},
        url_guard::validate_public_url,
    },
};

const MAX_EXTERNAL_DOC_API_BYTES: usize = 512 * 1024;
const MAX_ARXIV_HTML_BYTES: usize = 4 * 1024 * 1024;
const MAX_PDF_READ_BYTES: u64 = 50 * 1024 * 1024;
const PDFTOTEXT_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_PDFTOTEXT_OUTPUT_BYTES: usize = 128 * 1024;

/// Adapts any [`FeedFetcher`] to [`ArtistMetadataFetcher`] so `FeedIngester` can reuse the
/// document metadata lookup pipeline in both production and unit tests.
pub struct FeedFetcherTextAdapter<'a, F: FeedFetcher>(pub &'a F);

impl<F: FeedFetcher> ArtistMetadataFetcher for FeedFetcherTextAdapter<'_, F> {
    fn fetch_text<'a>(
        &'a self,
        url: &'a Url,
        max_bytes: usize,
    ) -> Pin<Box<dyn Future<Output = Option<String>> + Send + 'a>> {
        Box::pin(async move {
            let text = tokio::time::timeout(Duration::from_secs(8), self.0.fetch_feed(url))
                .await
                .ok()
                .and_then(Result::ok)?;
            (text.len() <= max_bytes).then_some(text)
        })
    }
}

/// Returns `true` when opening an entry page should automatically trigger background document
/// metadata discovery (e.g. a Paper whose title is still a PDF filename/URL or missing authors
/// and abstract, and has not been explicitly edited by the user).
#[must_use]
pub fn should_auto_enrich_document(detail: &EntryDetailView) -> bool {
    let entry = &detail.entry;
    if entry.kind != EntryKind::Paper {
        return false;
    }
    if roche_core::crdt::extract_entry_edited_hlc_from_value(Some(&entry.metadata)).is_some() {
        return false;
    }
    let title_placeholder = is_placeholder_entry_title(&entry.title, entry.url.as_deref());
    let missing_authors = entry.creators.is_empty();
    let missing_summary = entry.summary.as_deref().map(str::trim).is_none_or(str::is_empty);
    let missing_date = entry.published_at.is_none() && entry.release_year.is_none();
    if !(title_placeholder || (missing_authors && (missing_summary || missing_date))) {
        return false;
    }
    has_document_enrichment_source(detail)
}

/// Returns `true` when the entry supports explicit on-demand "Refresh Metadata" discovery.
#[must_use]
pub fn can_refresh_document_metadata(detail: &EntryDetailView) -> bool {
    matches!(detail.entry.kind, EntryKind::Paper | EntryKind::Article | EntryKind::Book)
        && has_document_enrichment_source(detail)
}

fn has_document_enrichment_source(detail: &EntryDetailView) -> bool {
    let entry = &detail.entry;
    entry.url.as_deref().map(str::trim).is_some_and(|u| !u.is_empty())
        || entry.identifier("doi").is_some()
        || entry.identifier("arxiv").is_some()
}

/// Extracts bibliographic metadata from raw PDF bytes (pure-Rust Info + XMP + page-1 layout
/// parser), optionally falling back to system `pdftotext` on `pdf_path` if available, and then
/// enriches with authoritative arXiv or Crossref API records when an arXiv ID or DOI is found.
pub async fn discover_pdf_metadata_from_bytes<M: ArtistMetadataFetcher>(
    pdf_bytes: Vec<u8>,
    pdf_path: Option<PathBuf>,
    source_url: Option<String>,
    fetcher: Option<&M>,
) -> ExtractedDocumentMetadata {
    let mut meta = tokio::task::spawn_blocking(move || extract_pdf_metadata(&pdf_bytes))
        .await
        .unwrap_or_default();

    if (meta.title.is_none() || meta.authors.is_empty())
        && let Some(path) = pdf_path.as_deref()
        && let Some(plain_text) = extract_first_page_with_pdftotext(path).await
    {
        let fallback =
            tokio::task::spawn_blocking(move || extract_metadata_from_plain_text(&plain_text))
                .await
                .unwrap_or_default();
        meta.fill_missing_from(fallback);
    }

    seed_identifiers_from_url(&mut meta, source_url.as_deref());

    if let Some(f) = fetcher {
        lookup_external_document_metadata(&mut meta, f).await;
    }

    if let Some(ref mut summary) = meta.summary {
        let clean = HtmlSanitizer::plain_text_truncate(summary, MAX_SUMMARY_CHARS);
        if clean.is_empty() {
            meta.summary = None;
        } else {
            *summary = clean;
        }
    }

    meta
}

/// Enriches a `Paper`, `Article`, or `Book` entry using its local cached PDF (if any), remote PDF
/// download, embedded/linked arXiv or Crossref API lookup, and/or HTML page snapshot.
pub async fn enrich_document_detail_with_fetcher<M: ArtistMetadataFetcher>(
    service: &RocheService,
    detail: &mut EntryDetailView,
    fetcher: Option<&M>,
    force_refresh: bool,
) {
    if !matches!(detail.entry.kind, EntryKind::Paper | EntryKind::Article | EntryKind::Book) {
        return;
    }

    let entry_id = detail.entry.id.clone();
    let entry_url = detail.entry.url.clone();
    let mut meta = ExtractedDocumentMetadata {
        doi: detail.entry.identifier("doi").and_then(normalize_doi),
        arxiv_id: detail.entry.identifier("arxiv").map(str::to_owned),
        isbn: detail.entry.identifier("isbn").map(str::to_owned),
        ..ExtractedDocumentMetadata::default()
    };
    seed_identifiers_from_url(&mut meta, entry_url.as_deref());

    let parsed_url = entry_url.as_deref().and_then(|u| Url::parse(u.trim()).ok());
    let mut inspected_pdf =
        inspect_local_cached_pdf(service, &entry_id, entry_url.as_deref(), &mut meta).await;

    if !inspected_pdf
        && fetcher.is_some()
        && detail.entry.kind == EntryKind::Paper
        && let Some(ref url) = parsed_url
    {
        inspected_pdf = fetch_and_inspect_remote_pdf(service, &entry_id, url, &mut meta).await;
    }

    if let Some(f) = fetcher {
        lookup_external_document_metadata(&mut meta, f).await;
    }

    let (thumb, mut content_html) = enrich_from_html_snapshot(
        service,
        detail.entry.kind,
        parsed_url.as_ref(),
        fetcher,
        inspected_pdf,
        &mut meta,
    )
    .await;

    if detail.entry.kind == EntryKind::Paper && content_html.is_none() {
        let strip_title = meta.title.as_deref().or(Some(detail.entry.title.as_str()));
        content_html = extract_paper_reader_html(
            service,
            &entry_id,
            meta.arxiv_id.as_deref(),
            strip_title,
            fetcher,
        )
        .await;
    }

    if let Some(ref mut summary) = meta.summary {
        let clean = HtmlSanitizer::plain_text_truncate(summary, MAX_SUMMARY_CHARS);
        if clean.is_empty() {
            meta.summary = None;
        } else {
            *summary = clean;
        }
    }

    if service
        .db
        .items()
        .apply_document_enrichment(
            &entry_id,
            &meta,
            thumb.as_deref(),
            content_html.as_deref(),
            force_refresh,
        )
        .await
        .is_ok()
        && let Ok(Some(refreshed)) = service.catalog().get_entry_detail(&entry_id).await
    {
        *detail = refreshed;
    }
}

async fn inspect_local_cached_pdf(
    service: &RocheService,
    entry_id: &str,
    entry_url: Option<&str>,
    meta: &mut ExtractedDocumentMetadata,
) -> bool {
    let Ok(media_list) = service.db.media().list_for_item(entry_id).await else {
        return false;
    };
    let Some(pdf_media) = media_list.into_iter().find(|m| m.media_type == MediaType::Pdf) else {
        return false;
    };
    let Ok(safe_path) = service.media_store.validate_path_async(Path::new(&pdf_media.path)).await
    else {
        return false;
    };
    let Ok(file_meta) = tokio::fs::metadata(&safe_path).await else {
        return false;
    };
    if file_meta.len() > MAX_PDF_READ_BYTES {
        return false;
    }
    let Ok(bytes) = tokio::fs::read(&safe_path).await else {
        return false;
    };
    if !is_valid_pdf_bytes(&bytes) {
        return false;
    }
    let pdf_meta = discover_pdf_metadata_from_bytes(
        bytes,
        Some(safe_path),
        entry_url.map(ToOwned::to_owned),
        None::<&crate::content::SafeHttpClient>,
    )
    .await;
    meta.fill_missing_from(pdf_meta);
    true
}

async fn fetch_and_inspect_remote_pdf(
    service: &RocheService,
    entry_id: &str,
    url: &Url,
    meta: &mut ExtractedDocumentMetadata,
) -> bool {
    let Some(pdf_url) = resolve_pdf_url(url) else {
        return false;
    };
    if validate_public_url(&pdf_url).is_err() {
        return false;
    }
    let Ok(Ok(Some(bytes))) =
        tokio::time::timeout(Duration::from_secs(12), service.fetcher.fetch_pdf(&pdf_url)).await
    else {
        return false;
    };
    if !is_valid_pdf_bytes(&bytes) {
        return false;
    }
    let rel_path = format!("pdfs/{entry_id}.pdf");
    let saved_path = match service.media_store.write_bytes(&rel_path, &bytes).await {
        Ok(file_size) => {
            if service
                .db
                .media()
                .create(entry_id, MediaType::Pdf, &rel_path, Some(file_size), None)
                .await
                .is_ok()
            {
                service.media_store.validate_path_async(Path::new(&rel_path)).await.ok()
            } else {
                let _ = service.media_store.delete_file(&rel_path).await;
                None
            }
        }
        Err(_) => None,
    };
    let pdf_meta = discover_pdf_metadata_from_bytes(
        bytes,
        saved_path,
        Some(url.to_string()),
        None::<&crate::content::SafeHttpClient>,
    )
    .await;
    meta.fill_missing_from(pdf_meta);
    true
}

async fn enrich_from_html_snapshot<M: ArtistMetadataFetcher>(
    service: &RocheService,
    kind: EntryKind,
    parsed_url: Option<&Url>,
    fetcher: Option<&M>,
    inspected_pdf: bool,
    meta: &mut ExtractedDocumentMetadata,
) -> (Option<String>, Option<String>) {
    let Some(f) = fetcher else {
        return (None, None);
    };
    let Some(url) = parsed_url else {
        return (None, None);
    };
    let is_direct_pdf =
        Path::new(url.path()).extension().is_some_and(|ext| ext.eq_ignore_ascii_case("pdf"));
    let has_missing_title = meta.title.is_none();
    let has_missing_authors = meta.authors.is_empty();
    let has_missing_date = meta.published_at.is_none() && meta.release_year.is_none();
    let needs_more = has_missing_title
        || has_missing_authors
        || has_missing_date
        || meta.summary.is_none()
        || meta.doi.is_none();
    if is_direct_pdf || (inspected_pdf && !needs_more) || validate_public_url(url).is_err() {
        return (None, None);
    }
    let Some(raw_html) = fetch_html_for_enrichment(service, f, url).await else {
        return (None, None);
    };
    let base_url = url.clone();
    let Ok(snap) = tokio::task::spawn_blocking(move || {
        HtmlSanitizer::extract_snapshot_with_base(&raw_html, Some(&base_url))
    })
    .await
    else {
        return (None, None);
    };
    let html_meta = ExtractedDocumentMetadata {
        title: snap.title,
        authors: snap.authors,
        published_at: snap.published_at,
        release_year: snap.release_year,
        summary: snap.summary,
        doi: snap.doi,
        arxiv_id: extract_arxiv_id(url.as_str()),
        isbn: snap.isbn,
    };
    let had_doi_or_arxiv = meta.doi.is_some() || meta.arxiv_id.is_some();
    meta.fill_missing_from(html_meta);
    let content_html = (kind == EntryKind::Article && !snap.content_html.trim().is_empty())
        .then_some(snap.content_html);
    if !had_doi_or_arxiv && (meta.doi.is_some() || meta.arxiv_id.is_some()) {
        lookup_external_document_metadata(meta, f).await;
    }
    (snap.thumbnail_url, content_html)
}

async fn fetch_html_for_enrichment<M: ArtistMetadataFetcher>(
    service: &RocheService,
    fetcher: &M,
    url: &Url,
) -> Option<String> {
    if let Ok(Ok(Some(html))) =
        tokio::time::timeout(Duration::from_secs(8), service.fetcher.fetch_html(url)).await
    {
        return Some(html);
    }
    fetcher.fetch_text(url, 2 * 1024 * 1024).await
}

fn seed_identifiers_from_url(meta: &mut ExtractedDocumentMetadata, url: Option<&str>) {
    let Some(u) = url.map(str::trim).filter(|s| !s.is_empty()) else {
        return;
    };
    if meta.arxiv_id.is_none() {
        meta.arxiv_id = extract_arxiv_id(u).or_else(|| extract_arxiv_id_from_text(u));
    }
    if meta.doi.is_none() {
        meta.doi = normalize_doi(u).or_else(|| extract_doi_from_text(u));
    }
}

pub async fn lookup_external_document_metadata<M: ArtistMetadataFetcher>(
    meta: &mut ExtractedDocumentMetadata,
    fetcher: &M,
) {
    if let Some(aid) = meta.arxiv_id.clone()
        && is_valid_arxiv_id(&aid)
        && let Ok(api_url) = Url::parse_with_params(
            "https://export.arxiv.org/api/query",
            &[("id_list", aid.as_str())],
        )
        && validate_public_url(&api_url).is_ok()
        && let Some(xml) = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await
        && let Ok(Ok(parsed)) = tokio::task::spawn_blocking(move || ArxivParser::parse(&xml)).await
        && let Some(paper) = parsed.items.into_iter().next()
    {
        let clean_title = paper.title.split_whitespace().collect::<Vec<_>>().join(" ");
        let clean_summary = HtmlSanitizer::plain_text_truncate(&paper.summary, MAX_SUMMARY_CHARS);
        let mut authoritative = ExtractedDocumentMetadata {
            title: (!clean_title.is_empty()).then_some(clean_title),
            authors: paper.authors,
            published_at: paper.published_at,
            release_year: paper.published_at.map(|dt| dt.year()),
            summary: (!clean_summary.is_empty()).then_some(clean_summary),
            doi: None,
            arxiv_id: Some(aid),
            isbn: None,
        };
        let fallback = std::mem::take(meta);
        authoritative.fill_missing_from(fallback);
        *meta = authoritative;
        return;
    }

    if let Some(doi) = meta.doi.clone()
        && let Some(encoded_doi) = encode_doi_for_crossref_url(&doi)
        && let Ok(api_url) = Url::parse(&format!("https://api.crossref.org/works/{encoded_doi}"))
        && validate_public_url(&api_url).is_ok()
        && let Some(json_str) = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await
        && let Some(mut authoritative) = parse_crossref_work_json(&json_str)
    {
        if let Some(ref mut summary) = authoritative.summary {
            let clean = HtmlSanitizer::plain_text_truncate(summary, MAX_SUMMARY_CHARS);
            if clean.is_empty() {
                authoritative.summary = None;
            } else {
                *summary = clean;
            }
        }
        let fallback = std::mem::take(meta);
        authoritative.fill_missing_from(fallback);
        *meta = authoritative;
    }
}

fn encode_doi_for_crossref_url(doi: &str) -> Option<String> {
    let norm = normalize_doi(doi)?;
    let mut out = String::with_capacity(norm.len() + 8);
    for b in norm.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~' | b'/') {
            out.push(char::from(b));
        } else {
            use std::fmt::Write as _;
            let _ = write!(out, "%{b:02X}");
        }
    }
    Some(out)
}

/// Layer 1 of paper reflow: fetches official arXiv HTML5+`MathML` (`https://arxiv.org/html/<id>`)
/// when available and sanitizes it into clean presentation `MathML` + semantic prose.
pub async fn fetch_arxiv_html5<M: ArtistMetadataFetcher>(
    arxiv_id: &str,
    fetcher: &M,
) -> Option<String> {
    let trimmed = arxiv_id.trim();
    if !is_valid_arxiv_id(trimmed) {
        return None;
    }
    let html_url = Url::parse(&format!("https://arxiv.org/html/{trimmed}")).ok()?;
    validate_public_url(&html_url).ok()?;
    let raw_html = fetcher.fetch_text(&html_url, MAX_ARXIV_HTML_BYTES).await?;
    let head_end = raw_html.floor_char_boundary(4096);
    let lower_head = raw_html[..head_end].to_ascii_lowercase();
    if lower_head.contains("html is not available")
        || lower_head.contains("no html for")
        || !raw_html.contains("ltx_document")
    {
        return None;
    }
    let base = html_url.clone();
    let snap = tokio::task::spawn_blocking(move || {
        HtmlSanitizer::extract_snapshot_with_base(&raw_html, Some(&base))
    })
    .await
    .ok()?;
    let clean = snap.content_html;
    let plain_len = HtmlSanitizer::plain_text_excerpt_from_clean(&clean, 400).len();
    (plain_len >= 200).then_some(clean)
}

/// Layered reflow extraction for papers:
/// 1. Tries official arXiv HTML5+`MathML` (`https://arxiv.org/html/<id>`) when an arXiv ID is known.
/// 2. Falls back to font/layout-aware PDF reflow with `MathML` on the local cached PDF.
pub async fn extract_paper_reader_html<M: ArtistMetadataFetcher>(
    service: &RocheService,
    entry_id: &str,
    arxiv_id: Option<&str>,
    strip_title: Option<&str>,
    fetcher: Option<&M>,
) -> Option<String> {
    if let (Some(aid), Some(f)) = (arxiv_id, fetcher)
        && let Some(arxiv_html) = fetch_arxiv_html5(aid, f).await
    {
        return Some(arxiv_html);
    }

    let media_list = service.db.media().list_for_item(entry_id).await.ok()?;
    let pdf_media = media_list.into_iter().find(|m| m.media_type == MediaType::Pdf)?;
    let safe_path =
        service.media_store.validate_path_async(Path::new(&pdf_media.path)).await.ok()?;
    let file_meta = tokio::fs::metadata(&safe_path).await.ok()?;
    if file_meta.len() > MAX_PDF_READ_BYTES {
        return None;
    }
    extract_reflowed_pdf_html(Some(&safe_path), None, strip_title).await
}

async fn extract_first_page_with_pdftotext(pdf_path: &Path) -> Option<String> {
    let mut cmd = tokio::process::Command::new("pdftotext");
    cmd.arg("-f")
        .arg("1")
        .arg("-l")
        .arg("1")
        .arg("-enc")
        .arg("UTF-8")
        .arg(pdf_path)
        .arg("-")
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    let mut child = cmd.spawn().ok()?;
    let stdout = child.stdout.take()?;
    let read_fut = async {
        let mut buf = Vec::new();
        let limit = u64::try_from(MAX_PDFTOTEXT_OUTPUT_BYTES).unwrap_or(u64::MAX);
        let mut reader = stdout.take(limit.saturating_add(1));
        reader.read_to_end(&mut buf).await.ok()?;
        if buf.len() > MAX_PDFTOTEXT_OUTPUT_BYTES {
            let _ = child.kill().await;
            let _ = child.wait().await;
            buf.truncate(MAX_PDFTOTEXT_OUTPUT_BYTES);
            return Some(buf);
        }
        let status = child.wait().await.ok()?;
        status.success().then_some(buf)
    };
    let Ok(Some(buf)) = tokio::time::timeout(PDFTOTEXT_TIMEOUT, read_fut).await else {
        let _ = child.kill().await;
        let _ = child.wait().await;
        return None;
    };
    if buf.is_empty() {
        return None;
    }
    let text = String::from_utf8_lossy(&buf).into_owned();
    (!text.trim().is_empty()).then_some(text)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn build_synthetic_latex_pdf(content_stream: &str) -> Vec<u8> {
        let mut pdf = Vec::new();
        pdf.extend_from_slice(b"%PDF-1.5\n");
        pdf.extend_from_slice(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        pdf.extend_from_slice(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>\nendobj\n",
        );
        pdf.extend_from_slice(
            format!("4 0 obj\n<< /Length {} >>\nstream\n", content_stream.len()).as_bytes(),
        );
        pdf.extend_from_slice(content_stream.as_bytes());
        pdf.extend_from_slice(b"\nendstream\nendobj\n");
        // Blank pdfTeX /Info dictionary like subnlogn.pdf
        pdf.extend_from_slice(
            b"5 0 obj\n<< /Title () /Author () /Producer (pdfTeX-1.40.25) /CreationDate (D:20261020103249+11'00') >>\nendobj\n",
        );
        pdf.extend_from_slice(b"%%EOF\n");
        pdf
    }

    #[tokio::test]
    async fn test_discover_pdf_metadata_handles_blank_latex_info_and_crossref_lookup() {
        let stream = "\
BT\n\
/F1 17.28 Tf\n\
100 720 Td (Integer multiplication in time O\\(n log n\\)) Tj\n\
/F1 12 Tf\n\
0 -28 Td (David Harvey and Joris van der Hoeven) Tj\n\
/F1 10 Tf\n\
0 -20 Td (October 20, 2026) Tj\n\
0 -28 Td (Abstract) Tj\n\
0 -14 Td (We prove that n-bit integers can be multiplied in O\\(n log n\\) bit operations. DOI: 10.4007/annals.2021.193.2.4) Tj\n\
ET\n";
        let pdf_bytes = build_synthetic_latex_pdf(stream);

        // 1. Offline discovery purely from compressed stream (no external fetcher)
        let no_fetcher: Option<&fn(&Url) -> Option<String>> = None;
        let offline = discover_pdf_metadata_from_bytes(
            pdf_bytes.clone(),
            None,
            Some("https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf".to_owned()),
            no_fetcher,
        )
        .await;
        assert_eq!(offline.title.as_deref(), Some("Integer multiplication in time O(n log n)"));
        assert_eq!(offline.authors, vec!["David Harvey", "Joris van der Hoeven"]);
        assert_eq!(offline.release_year, Some(2026));
        assert_eq!(offline.doi.as_deref(), Some("10.4007/annals.2021.193.2.4"));
        assert!(offline.summary.as_deref().unwrap_or("").contains("O(n log n)"));

        // 2. With Crossref lookup mocked, authoritative Crossref record upgrades metadata while
        // preserving PDF abstract when Crossref omits it.
        let mock_fetcher = |url: &Url| -> Option<String> {
            if url.as_str().contains("api.crossref.org/works/10.4007/annals.2021.193.2.4") {
                Some(
                    r#"{
                        "message": {
                            "title": ["Integer multiplication in time O(n log n) (Annals Edition)"],
                            "author": [
                                {"given": "David", "family": "Harvey"},
                                {"given": "Joris", "family": "van der Hoeven"}
                            ],
                            "published-print": {"date-parts": [[2021, 3, 1]]},
                            "DOI": "10.4007/annals.2021.193.2.4"
                        }
                    }"#
                    .to_owned(),
                )
            } else {
                None
            }
        };
        let online = discover_pdf_metadata_from_bytes(
            pdf_bytes,
            None,
            Some("https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf".to_owned()),
            Some(&mock_fetcher),
        )
        .await;
        assert_eq!(
            online.title.as_deref(),
            Some("Integer multiplication in time O(n log n) (Annals Edition)")
        );
        assert_eq!(online.authors, vec!["David Harvey", "Joris van der Hoeven"]);
        assert_eq!(online.release_year, Some(2021));
        assert!(online.summary.as_deref().unwrap_or("").contains("n-bit integers"));
    }
}
