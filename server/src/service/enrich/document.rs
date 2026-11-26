use std::{
    future::Future,
    path::{Path, PathBuf},
    pin::Pin,
    time::Duration,
};

use chrono::Datelike;
use quick_xml::{Reader, events::Event};
use roche_core::document_meta::{
    CrossrefWorkCandidate, ExtractedDocumentMetadata, crossref_work_matches_paper,
    document_metadata_matches_paper, encode_doi_for_url_path, extract_all_arxiv_ids_from_text,
    extract_all_dois_from_text, extract_arxiv_id_from_doi, extract_arxiv_id_from_text,
    extract_doi_from_text, extract_metadata_from_plain_text, extract_pdf_metadata,
    extract_pdf_metadata_with_front_text, is_auxiliary_document_title, is_placeholder_entry_title,
    normalize_doi, parse_crossref_search_works_json, parse_crossref_work_candidate_json,
    parse_crossref_work_json, parse_datacite_doi_json, select_verified_crossref_match,
    strip_references_section,
};
use tokio::io::AsyncReadExt as _;
use url::Url;

use super::ArtistMetadataFetcher;
use crate::{
    content::{
        HtmlSanitizer, MAX_SUMMARY_CHARS, extract_reflowed_pdf_html, is_valid_pdf_bytes,
        resolve_pdf_url, xml_util,
    },
    db::EntryDetailView,
    domain::{EntryKind, MediaType},
    feeds::ArxivParser,
    ingest::FeedFetcher,
    service::RocheService,
    util::{
        media_urls::{extract_arxiv_id, is_valid_arxiv_id, strip_arxiv_version},
        url_guard::validate_public_url,
    },
};

const MAX_EXTERNAL_DOC_API_BYTES: usize = 512 * 1024;
const MAX_ARXIV_HTML_BYTES: usize = 4 * 1024 * 1024;
const MAX_PDF_READ_BYTES: u64 = 50 * 1024 * 1024;
const PDFTOTEXT_TIMEOUT: Duration = Duration::from_secs(5);
const MAX_PDFTOTEXT_OUTPUT_BYTES: usize = 128 * 1024;
const MAX_VERIFIED_DOI_CANDIDATES: usize = 4;
const MAX_VERIFIED_ARXIV_CANDIDATES: usize = 2;

/// Optional caller-supplied bibliographic hints (such as curated manifest metadata) used to verify
/// candidate DOIs / arXiv IDs and run Crossref bibliographic search when PDF front-matter omits them.
#[derive(Debug, Default, Clone, Copy)]
pub struct DocumentIdentifierHint<'a> {
    pub title: Option<&'a str>,
    pub authors: &'a [String],
    pub release_year: Option<i32>,
    pub doi: Option<&'a str>,
    pub arxiv_id: Option<&'a str>,
}

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

    if (meta.title.is_none()
        || meta.authors.is_empty()
        || meta.doi.is_none()
        || meta.arxiv_id.is_none())
        && let Some(path) = pdf_path.as_deref()
        && let Some(plain_text) = extract_pages_with_pdftotext(path, 2).await
    {
        let fallback = tokio::task::spawn_blocking(move || {
            extract_metadata_from_plain_text(strip_references_section(&plain_text))
        })
        .await
        .unwrap_or_default();
        meta.fill_missing_from(fallback);
    }

    seed_identifiers_from_url(&mut meta, source_url.as_deref());

    if let Some(f) = fetcher {
        lookup_external_document_metadata(&mut meta, f).await;
    }

    truncate_doc_summary(&mut meta);
    meta
}

/// Discovers and verifies bibliographic metadata, DOIs, and arXiv IDs for a PDF using:
/// 1. Pure-Rust PDF Info/XMP + pages 1–2 layout extraction,
/// 2. Optional `pdftotext -f 1 -l 2` (truncated before `References` / `Bibliography`),
/// 3. Optional online verification via arXiv Atom API (including `<arxiv:doi>`) and Crossref
///    `/works/<doi>` + bibliographic search fallback, rejecting proceedings-volume DOIs, errata,
///    and cited-work DOIs while preferring published DOIs over preprint DOIs.
pub async fn discover_verified_pdf_metadata<M: ArtistMetadataFetcher>(
    pdf_bytes: Vec<u8>,
    pdf_path: Option<PathBuf>,
    source_url: Option<String>,
    hint: DocumentIdentifierHint<'_>,
    fetcher: Option<&M>,
) -> ExtractedDocumentMetadata {
    let extraction =
        tokio::task::spawn_blocking(move || extract_pdf_metadata_with_front_text(&pdf_bytes))
            .await
            .unwrap_or_default();
    let mut local_meta = extraction.metadata;
    let rust_front_text = extraction.front_text;
    let mut year_is_creation_date_only = extraction.year_is_creation_date_only;

    let pdftotext_front = if let Some(path) = pdf_path.as_deref()
        && let Some(raw) = extract_pages_with_pdftotext(path, 2).await
    {
        let (stripped, fallback) = tokio::task::spawn_blocking(move || {
            let s = strip_references_section(&raw).to_owned();
            let fb = extract_metadata_from_plain_text(&s);
            (s, fb)
        })
        .await
        .unwrap_or_default();
        if year_is_creation_date_only
            && (fallback.release_year.is_some() || fallback.published_at.is_some())
        {
            local_meta.release_year = fallback.release_year;
            local_meta.published_at = fallback.published_at;
            year_is_creation_date_only = false;
        }
        local_meta.fill_missing_from(fallback);
        Some(stripped)
    } else {
        None
    };

    let explicit_hint_doi = hint.doi.and_then(normalize_doi);
    let explicit_hint_arxiv = hint
        .arxiv_id
        .and_then(extract_arxiv_id)
        .filter(|id| is_valid_arxiv_id(id))
        .map(|id| strip_arxiv_version(&id).to_owned());

    let (mut candidate_dois, candidate_arxiv_ids) = collect_pdf_identifier_candidates(
        explicit_hint_doi.as_deref(),
        explicit_hint_arxiv.as_deref(),
        source_url.as_deref(),
        &local_meta,
        &extraction.info_dois,
        &extraction.info_arxiv_ids,
        pdftotext_front.as_deref(),
        &rust_front_text,
    );

    let Some(f) = fetcher else {
        local_meta.doi = candidate_dois
            .iter()
            .find(|d| extract_arxiv_id_from_doi(d).is_none())
            .or_else(|| candidate_dois.first())
            .cloned();
        local_meta.arxiv_id = candidate_arxiv_ids.into_iter().next();
        truncate_doc_summary(&mut local_meta);
        return local_meta;
    };

    let expected_title = hint
        .title
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .or_else(|| local_meta.title.as_deref().map(str::trim).filter(|s| !s.is_empty()))
        .map(ToOwned::to_owned);
    let expected_authors: Vec<String> =
        if hint.authors.is_empty() { local_meta.authors.clone() } else { hint.authors.to_vec() };
    // Never feed PDF `/CreationDate` (file export timestamp) into Crossref search or year-delta
    // rejection, because modern re-typeset/scanned copies of classic papers carry recent timestamps.
    let expected_year = hint
        .release_year
        .or_else(|| (!year_is_creation_date_only).then_some(local_meta.release_year).flatten());

    let (verified_crossref, verified_arxiv_id, verified_arxiv_meta) =
        verify_pdf_identifiers_online(
            &mut candidate_dois,
            &candidate_arxiv_ids,
            expected_title.as_deref(),
            &expected_authors,
            expected_year,
            f,
        )
        .await;

    assemble_verified_pdf_metadata(
        verified_crossref,
        verified_arxiv_id,
        verified_arxiv_meta,
        local_meta,
        explicit_hint_doi,
        explicit_hint_arxiv,
    )
}

fn assemble_verified_pdf_metadata(
    verified_crossref: Option<CrossrefWorkCandidate>,
    verified_arxiv_id: Option<String>,
    verified_arxiv_meta: Option<ExtractedDocumentMetadata>,
    mut local_meta: ExtractedDocumentMetadata,
    explicit_hint_doi: Option<String>,
    explicit_hint_arxiv: Option<String>,
) -> ExtractedDocumentMetadata {
    // Assemble final metadata strictly from verified identifiers (plus explicit caller hints).
    // Clear `arxiv_meta.doi` too so any `<arxiv:doi>` rejected by Crossref never leaks back in.
    local_meta.doi = None;
    local_meta.arxiv_id = None;

    let mut out = verified_crossref.map_or_default(|c| c.metadata);
    if let Some(mut arxiv_meta) = verified_arxiv_meta {
        arxiv_meta.doi = None;
        out.fill_missing_from(arxiv_meta);
    }
    out.fill_missing_from(local_meta);

    if out.doi.is_none() {
        out.doi = explicit_hint_doi;
    }
    if let Some(aid) = verified_arxiv_id {
        out.arxiv_id = Some(aid);
    } else if out.arxiv_id.is_none() {
        out.arxiv_id = out
            .doi
            .as_deref()
            .and_then(extract_arxiv_id_from_doi)
            .map(|id| strip_arxiv_version(&id).to_owned())
            .or(explicit_hint_arxiv);
    } else if let Some(ref existing_aid) = out.arxiv_id {
        out.arxiv_id = Some(strip_arxiv_version(existing_aid).to_owned());
    }

    truncate_doc_summary(&mut out);
    out
}

#[allow(clippy::too_many_arguments)]
fn collect_pdf_identifier_candidates(
    explicit_hint_doi: Option<&str>,
    explicit_hint_arxiv: Option<&str>,
    source_url: Option<&str>,
    local_meta: &ExtractedDocumentMetadata,
    info_dois: &[String],
    info_arxiv_ids: &[String],
    pdftotext_front: Option<&str>,
    rust_front_text: &str,
) -> (Vec<String>, Vec<String>) {
    let mut candidate_dois = Vec::new();
    let mut candidate_arxiv_ids = Vec::new();

    if let Some(doi) = explicit_hint_doi {
        push_unique_doi(&mut candidate_dois, doi);
    }
    if let Some(aid) = explicit_hint_arxiv {
        push_unique_arxiv(&mut candidate_arxiv_ids, aid);
    }
    if let Some(url) = source_url {
        if let Some(doi) = normalize_doi(url) {
            push_unique_doi(&mut candidate_dois, &doi);
        }
        for doi in extract_all_dois_from_text(url) {
            push_unique_doi(&mut candidate_dois, &doi);
        }
        if let Some(aid) = extract_arxiv_id(url) {
            push_unique_arxiv(&mut candidate_arxiv_ids, &aid);
        }
        for aid in extract_all_arxiv_ids_from_text(url) {
            push_unique_arxiv(&mut candidate_arxiv_ids, &aid);
        }
    }
    for doi in info_dois {
        push_unique_doi(&mut candidate_dois, doi);
    }
    for aid in info_arxiv_ids {
        push_unique_arxiv(&mut candidate_arxiv_ids, aid);
    }
    if let Some(ref doi) = local_meta.doi {
        push_unique_doi(&mut candidate_dois, doi);
    }
    if let Some(ref aid) = local_meta.arxiv_id {
        push_unique_arxiv(&mut candidate_arxiv_ids, aid);
    }
    if let Some(text) = pdftotext_front {
        for doi in extract_all_dois_from_text(text) {
            push_unique_doi(&mut candidate_dois, &doi);
        }
        for aid in extract_all_arxiv_ids_from_text(text) {
            push_unique_arxiv(&mut candidate_arxiv_ids, &aid);
        }
    }
    let rust_stripped = strip_references_section(rust_front_text);
    for doi in extract_all_dois_from_text(rust_stripped) {
        push_unique_doi(&mut candidate_dois, &doi);
    }
    for aid in extract_all_arxiv_ids_from_text(rust_stripped) {
        push_unique_arxiv(&mut candidate_arxiv_ids, &aid);
    }
    let arxiv_from_dois: Vec<String> =
        candidate_dois.iter().filter_map(|d| extract_arxiv_id_from_doi(d)).collect();
    for aid in arxiv_from_dois {
        push_unique_arxiv(&mut candidate_arxiv_ids, &aid);
    }
    (candidate_dois, candidate_arxiv_ids)
}

async fn verify_pdf_identifiers_online<M: ArtistMetadataFetcher>(
    candidate_dois: &mut Vec<String>,
    candidate_arxiv_ids: &[String],
    expected_title: Option<&str>,
    expected_authors: &[String],
    expected_year: Option<i32>,
    fetcher: &M,
) -> (Option<CrossrefWorkCandidate>, Option<String>, Option<ExtractedDocumentMetadata>) {
    // 1. Verify candidate arXiv IDs and harvest any authoritative `<arxiv:doi>`.
    let mut verified_arxiv_id: Option<String> = None;
    let mut verified_arxiv_meta: Option<ExtractedDocumentMetadata> = None;
    for cand_aid in candidate_arxiv_ids.iter().take(MAX_VERIFIED_ARXIV_CANDIDATES) {
        if let Some((arxiv_meta, linked_doi)) = fetch_arxiv_record(cand_aid, fetcher).await {
            let matches = expected_title.is_none_or(|exp_t| {
                document_metadata_matches_paper(
                    exp_t,
                    expected_authors,
                    expected_year,
                    arxiv_meta.title.as_deref(),
                    &arxiv_meta.authors,
                    arxiv_meta.release_year,
                )
            });
            if matches {
                verified_arxiv_id = Some(cand_aid.clone());
                verified_arxiv_meta = Some(arxiv_meta);
                if let Some(doi) = linked_doi {
                    prepend_unique_doi(candidate_dois, &doi);
                }
                break;
            }
        }
    }

    // Backfill expected bibliographic targets from verified arXiv metadata when the PDF itself
    // yielded no title (e.g. scanned/image-only PDF with an embedded arXiv ID).
    let eff_title = expected_title
        .map(ToOwned::to_owned)
        .or_else(|| verified_arxiv_meta.as_ref().and_then(|m| m.title.clone()));
    let eff_authors: Vec<String> = if expected_authors.is_empty() {
        verified_arxiv_meta.as_ref().map_or_else(Vec::new, |m| m.authors.clone())
    } else {
        expected_authors.to_vec()
    };
    let eff_year =
        expected_year.or_else(|| verified_arxiv_meta.as_ref().and_then(|m| m.release_year));

    // 2. Verify candidate DOIs against Crossref `/works/<doi>` (with DataCite fallback).
    let mut verified_crossref_candidates: Vec<CrossrefWorkCandidate> = Vec::new();
    for cand_doi in candidate_dois.iter().take(MAX_VERIFIED_DOI_CANDIDATES) {
        let resolved = match fetch_crossref_work_candidate(cand_doi, fetcher).await {
            Some(cand) => Some(cand),
            None => fetch_datacite_work_candidate(cand_doi, fetcher).await,
        };
        if let Some(cand) = resolved {
            if cand.is_container_or_auxiliary_type() {
                continue;
            }
            let matches = match eff_title.as_deref() {
                Some(exp_t) => crossref_work_matches_paper(exp_t, &eff_authors, eff_year, &cand),
                None => {
                    cand.metadata.title.as_deref().is_none_or(|t| !is_auxiliary_document_title(t))
                }
            };
            if matches {
                if verified_arxiv_id.is_none()
                    && let Some(ref aid) = cand.metadata.arxiv_id
                {
                    verified_arxiv_id = Some(strip_arxiv_version(aid).to_owned());
                }
                verified_crossref_candidates.push(cand);
            }
        }
    }

    let mut verified_crossref = eff_title.as_deref().map_or_else(
        || {
            verified_crossref_candidates
                .iter()
                .find(|c| !c.is_preprint())
                .or_else(|| verified_crossref_candidates.first())
                .cloned()
        },
        |exp_t| {
            select_verified_crossref_match(
                exp_t,
                &eff_authors,
                eff_year,
                &verified_crossref_candidates,
            )
            .cloned()
        },
    );

    // 3. Crossref bibliographic search fallback when no verified publisher DOI was found yet.
    if verified_crossref.as_ref().is_none_or(CrossrefWorkCandidate::is_preprint)
        && let Some(exp_t) = eff_title.as_deref()
    {
        let query = build_crossref_bibliographic_query(exp_t, &eff_authors, eff_year);
        if let Some(search_candidates) = search_crossref_works(&query, fetcher).await
            && let Some(best) =
                select_verified_crossref_match(exp_t, &eff_authors, eff_year, &search_candidates)
        {
            if verified_arxiv_id.is_none()
                && let Some(ref aid) = best.metadata.arxiv_id
            {
                verified_arxiv_id = Some(strip_arxiv_version(aid).to_owned());
            }
            if verified_crossref.is_none() || !best.is_preprint() {
                verified_crossref = Some(best.clone());
            }
        }
    }

    (verified_crossref, verified_arxiv_id, verified_arxiv_meta)
}

fn truncate_doc_summary(meta: &mut ExtractedDocumentMetadata) {
    if let Some(ref mut summary) = meta.summary {
        let clean = HtmlSanitizer::plain_text_truncate(summary, MAX_SUMMARY_CHARS);
        if clean.is_empty() {
            meta.summary = None;
        } else {
            *summary = clean;
        }
    }
}

fn push_unique_doi(out: &mut Vec<String>, raw: &str) {
    if let Some(norm) = normalize_doi(raw)
        && !out.contains(&norm)
    {
        out.push(norm);
    }
}

fn prepend_unique_doi(out: &mut Vec<String>, raw: &str) {
    if let Some(norm) = normalize_doi(raw) {
        out.retain(|existing| existing != &norm);
        out.insert(0, norm);
    }
}

fn push_unique_arxiv(out: &mut Vec<String>, raw: &str) {
    if let Some(aid) = extract_arxiv_id(raw)
        && is_valid_arxiv_id(&aid)
    {
        let versionless = strip_arxiv_version(&aid).to_owned();
        if !out.contains(&versionless) {
            out.push(versionless);
        }
    }
}

fn build_crossref_bibliographic_query(
    title: &str,
    authors: &[String],
    year: Option<i32>,
) -> String {
    let mut parts: Vec<String> = vec![title.split_whitespace().collect::<Vec<_>>().join(" ")];
    for author in authors.iter().take(2) {
        let cleaned = author
            .trim()
            .trim_end_matches("et al.")
            .trim_end_matches("et al")
            .trim()
            .trim_end_matches(',');
        if !cleaned.is_empty() {
            parts.push(cleaned.to_owned());
        }
    }
    if let Some(yr) = year {
        parts.push(yr.to_string());
    }
    let joined = parts.join(" ");
    let end = joined.floor_char_boundary(240);
    joined[..end].trim().to_owned()
}

/// Extracts an authoritative published DOI from an arXiv Atom API response's first `<entry>`
/// (`<arxiv:doi>...</arxiv:doi>`), which `feed_rs` does not surface.
#[must_use]
fn extract_arxiv_doi_from_atom_xml(xml: &str) -> Option<String> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().check_end_names = false;
    let mut in_entry = false;
    let mut in_doi = false;
    let mut buf = String::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let name = tag.name();
                let raw = name.as_ref();
                if raw.eq_ignore_ascii_case("entry") {
                    in_entry = true;
                } else if in_entry && raw.eq_ignore_ascii_case("arxiv:doi") {
                    in_doi = true;
                    buf.clear();
                }
            }
            Ok(Event::Text(text)) if in_doi => {
                buf.push_str(&xml_util::decode_text(&text));
            }
            Ok(Event::GeneralRef(entity)) if in_doi => {
                buf.push_str(&xml_util::decode_entity(&entity));
            }
            Ok(Event::End(tag)) => {
                let name = tag.name();
                let raw = name.as_ref();
                if in_doi && raw.eq_ignore_ascii_case("arxiv:doi") {
                    return normalize_doi(&buf);
                }
                if in_entry && raw.eq_ignore_ascii_case("entry") {
                    return None;
                }
            }
            Ok(Event::Eof) | Err(_) => return None,
            _ => {}
        }
    }
}

async fn fetch_arxiv_record<M: ArtistMetadataFetcher>(
    arxiv_id: &str,
    fetcher: &M,
) -> Option<(ExtractedDocumentMetadata, Option<String>)> {
    if !is_valid_arxiv_id(arxiv_id) {
        return None;
    }
    let api_url =
        Url::parse_with_params("https://export.arxiv.org/api/query", &[("id_list", arxiv_id)])
            .ok()?;
    validate_public_url(&api_url).ok()?;
    let xml = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await?;
    let aid_owned = strip_arxiv_version(arxiv_id).to_owned();
    tokio::task::spawn_blocking(move || {
        // arXiv's Atom API returns HTTP 200 with `<id>http://arxiv.org/api/errors#...</id>` and
        // `<title>Error</title>` / `<author><name>arXiv api core</name></author>` on bad IDs.
        if xml.contains("arxiv.org/api/errors") {
            return None;
        }
        let linked_doi = extract_arxiv_doi_from_atom_xml(&xml);
        let parsed = ArxivParser::parse(&xml).ok()?;
        let paper = parsed.items.into_iter().next()?;
        let clean_title = paper.title.split_whitespace().collect::<Vec<_>>().join(" ");
        if paper.abstract_url.is_none()
            || clean_title.eq_ignore_ascii_case("error")
            || paper.authors.iter().any(|a| a.eq_ignore_ascii_case("arxiv api core"))
        {
            return None;
        }
        let clean_summary = HtmlSanitizer::plain_text_truncate(&paper.summary, MAX_SUMMARY_CHARS);
        let meta = ExtractedDocumentMetadata {
            title: (!clean_title.is_empty()).then_some(clean_title),
            authors: paper.authors,
            published_at: paper.published_at,
            release_year: paper.published_at.map(|dt| dt.year()),
            summary: (!clean_summary.is_empty()).then_some(clean_summary),
            doi: linked_doi.clone(),
            arxiv_id: Some(aid_owned),
            isbn: None,
        };
        Some((meta, linked_doi))
    })
    .await
    .ok()
    .flatten()
}

async fn fetch_crossref_work_candidate<M: ArtistMetadataFetcher>(
    doi: &str,
    fetcher: &M,
) -> Option<CrossrefWorkCandidate> {
    let encoded_doi = encode_doi_for_url_path(doi)?;
    let api_url = Url::parse(&format!("https://api.crossref.org/works/{encoded_doi}")).ok()?;
    validate_public_url(&api_url).ok()?;
    let json_str = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await?;
    let mut cand = parse_crossref_work_candidate_json(&json_str)?;
    truncate_doc_summary(&mut cand.metadata);
    Some(cand)
}

async fn fetch_datacite_work_candidate<M: ArtistMetadataFetcher>(
    doi: &str,
    fetcher: &M,
) -> Option<CrossrefWorkCandidate> {
    let encoded_doi = encode_doi_for_url_path(doi)?;
    let api_url = Url::parse(&format!("https://api.datacite.org/dois/{encoded_doi}")).ok()?;
    validate_public_url(&api_url).ok()?;
    let json_str = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await?;
    let mut cand = parse_datacite_doi_json(&json_str)?;
    truncate_doc_summary(&mut cand.metadata);
    Some(cand)
}

async fn search_crossref_works<M: ArtistMetadataFetcher>(
    bibliographic_query: &str,
    fetcher: &M,
) -> Option<Vec<CrossrefWorkCandidate>> {
    let trimmed = bibliographic_query.trim();
    if trimmed.is_empty() {
        return None;
    }
    let api_url = Url::parse_with_params(
        "https://api.crossref.org/works",
        &[("query.bibliographic", trimmed), ("rows", "5")],
    )
    .ok()?;
    validate_public_url(&api_url).ok()?;
    let json_str = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await?;
    let mut candidates = parse_crossref_search_works_json(&json_str);
    for cand in &mut candidates {
        truncate_doc_summary(&mut cand.metadata);
    }
    (!candidates.is_empty()).then_some(candidates)
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
        && let Some((mut authoritative, _)) = fetch_arxiv_record(&aid, fetcher).await
    {
        let fallback = std::mem::take(meta);
        authoritative.fill_missing_from(fallback);
        *meta = authoritative;
        return;
    }

    if let Some(doi) = meta.doi.clone()
        && let Some(encoded_doi) = encode_doi_for_url_path(&doi)
        && let Ok(api_url) = Url::parse(&format!("https://api.crossref.org/works/{encoded_doi}"))
        && validate_public_url(&api_url).is_ok()
        && let Some(json_str) = fetcher.fetch_text(&api_url, MAX_EXTERNAL_DOC_API_BYTES).await
        && let Some(mut authoritative) = parse_crossref_work_json(&json_str)
    {
        truncate_doc_summary(&mut authoritative);
        let fallback = std::mem::take(meta);
        authoritative.fill_missing_from(fallback);
        *meta = authoritative;
    }
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

async fn extract_pages_with_pdftotext(pdf_path: &Path, last_page: u32) -> Option<String> {
    let mut cmd = tokio::process::Command::new("pdftotext");
    cmd.arg("-f")
        .arg("1")
        .arg("-l")
        .arg(last_page.max(1).to_string())
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

    #[tokio::test]
    async fn test_discover_verified_pdf_metadata_rejects_proceedings_doi_and_uses_search_and_arxiv_doi()
     {
        // PDF page 1 embeds ONLY a proceedings-volume DOI + an arXiv margin stamp.
        let stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (A Large Scale Study of Programming Languages and Code Quality in Github) Tj\n\
/F1 11 Tf\n\
0 -24 Td (Baishakhi Ray and Daryl Posnett) Tj\n\
0 -18 Td (Proceedings DOI: 10.1145/2635868  arXiv:1408.1234v2) Tj\n\
ET\n";
        let pdf_bytes = build_synthetic_latex_pdf(stream);

        let mock_fetcher = |url: &Url| -> Option<String> {
            let s = url.as_str();
            if s.contains("export.arxiv.org/api/query") && s.contains("1408.1234") {
                Some(
                    r#"<?xml version="1.0" encoding="UTF-8"?>
                    <feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom">
                      <title>ArXiv Query</title>
                      <entry>
                        <id>http://arxiv.org/abs/1408.1234v2</id>
                        <published>2014-08-05T00:00:00Z</published>
                        <title>A Large Scale Study of Programming Languages and Code Quality in Github</title>
                        <summary>Empirical study of 729 GitHub projects across 17 languages.</summary>
                        <author><name>Baishakhi Ray</name></author>
                        <author><name>Daryl Posnett</name></author>
                        <arxiv:doi>10.1145/2635868.2635922</arxiv:doi>
                      </entry>
                    </feed>"#
                        .to_owned(),
                )
            } else if s.contains("api.crossref.org/works/10.1145/2635868.2635922") {
                Some(
                    r#"{
                        "message": {
                            "type": "proceedings-article",
                            "DOI": "10.1145/2635868.2635922",
                            "title": ["A Large Scale Study of Programming Languages and Code Quality in Github"],
                            "author": [
                                {"given": "Baishakhi", "family": "Ray"},
                                {"given": "Daryl", "family": "Posnett"}
                            ],
                            "issued": {"date-parts": [[2014, 11, 11]]}
                        }
                    }"#
                    .to_owned(),
                )
            } else if s.contains("api.crossref.org/works/10.1145/2635868") {
                Some(
                    r#"{
                        "message": {
                            "type": "proceedings",
                            "DOI": "10.1145/2635868",
                            "title": ["Proceedings of FSE 2014"],
                            "issued": {"date-parts": [[2014]]}
                        }
                    }"#
                    .to_owned(),
                )
            } else {
                None
            }
        };

        let authors = vec!["Baishakhi Ray".to_owned(), "Daryl Posnett".to_owned()];
        let verified = discover_verified_pdf_metadata(
            pdf_bytes,
            None,
            None,
            DocumentIdentifierHint {
                title: Some(
                    "A Large Scale Study of Programming Languages and Code Quality in Github",
                ),
                authors: &authors,
                release_year: Some(2014),
                doi: None,
                arxiv_id: None,
            },
            Some(&mock_fetcher),
        )
        .await;

        assert_eq!(verified.doi.as_deref(), Some("10.1145/2635868.2635922"));
        assert_eq!(verified.arxiv_id.as_deref(), Some("1408.1234"));
        assert_eq!(verified.release_year, Some(2014));
        assert!(verified.summary.as_deref().unwrap().contains("729 GitHub projects"));
    }

    #[tokio::test]
    async fn test_discover_verified_pdf_metadata_falls_back_to_crossref_search_and_drops_bogus_doi()
    {
        // PDF embeds an unrelated cited DOI only, no arXiv ID.
        let stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (On the Criteria To Be Used in Decomposing Systems into Modules) Tj\n\
/F1 11 Tf\n\
0 -24 Td (David L. Parnas) Tj\n\
0 -18 Td (See prior work 10.1145/0000000.unrelated) Tj\n\
ET\n";
        let pdf_bytes = build_synthetic_latex_pdf(stream);

        let mock_fetcher = |url: &Url| -> Option<String> {
            let s = url.as_str();
            if s.contains("api.crossref.org/works/10.1145/0000000.unrelated") {
                Some(
                    r#"{
                        "message": {
                            "type": "journal-article",
                            "DOI": "10.1145/0000000.unrelated",
                            "title": ["Totally Unrelated Cited Paper"],
                            "author": [{"given": "Other", "family": "Person"}],
                            "issued": {"date-parts": [[1970]]}
                        }
                    }"#
                    .to_owned(),
                )
            } else if s.contains("api.crossref.org/works?query.bibliographic=") {
                Some(
                    r#"{
                        "message": {
                            "items": [
                                {
                                    "type": "journal-article",
                                    "DOI": "10.1145/361598.361623",
                                    "title": ["On the criteria to be used in decomposing systems into modules"],
                                    "author": [{"given": "D. L.", "family": "Parnas"}],
                                    "issued": {"date-parts": [[1972, 12]]}
                                }
                            ]
                        }
                    }"#
                    .to_owned(),
                )
            } else {
                None
            }
        };

        let authors = vec!["David L. Parnas".to_owned()];
        let verified = discover_verified_pdf_metadata(
            pdf_bytes.clone(),
            None,
            None,
            DocumentIdentifierHint {
                title: Some("On the Criteria To Be Used in Decomposing Systems into Modules"),
                authors: &authors,
                release_year: Some(1972),
                doi: None,
                arxiv_id: None,
            },
            Some(&mock_fetcher),
        )
        .await;
        assert_eq!(verified.doi.as_deref(), Some("10.1145/361598.361623"));
        assert!(verified.arxiv_id.is_none());

        // When Crossref search also returns nothing, bogus embedded DOI MUST be dropped (not kept).
        let reject_only_fetcher = |url: &Url| -> Option<String> {
            if url.as_str().contains("10.1145/0000000.unrelated") {
                Some(
                    r#"{
                        "message": {
                            "type": "journal-article",
                            "DOI": "10.1145/0000000.unrelated",
                            "title": ["Totally Unrelated Cited Paper"],
                            "author": [{"given": "Other", "family": "Person"}],
                            "issued": {"date-parts": [[1970]]}
                        }
                    }"#
                    .to_owned(),
                )
            } else {
                None
            }
        };
        let rejected = discover_verified_pdf_metadata(
            pdf_bytes,
            None,
            None,
            DocumentIdentifierHint {
                title: Some("On the Criteria To Be Used in Decomposing Systems into Modules"),
                authors: &authors,
                release_year: Some(1972),
                doi: None,
                arxiv_id: None,
            },
            Some(&reject_only_fetcher),
        )
        .await;
        assert!(rejected.doi.is_none(), "unrelated embedded DOI must be rejected");
    }

    #[tokio::test]
    async fn test_discover_verified_pdf_metadata_rejects_leaked_arxiv_doi_and_error_entries() {
        // 1. Rejected `<arxiv:doi>` (proceedings container) must NOT leak back into `out.doi`.
        let stream_arxiv = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (Sound Empirical Evaluation of Compilers) Tj\n\
/F1 11 Tf\n\
0 -24 Td (Ada Lovelace) Tj\n\
0 -18 Td (arXiv:2401.11111) Tj\n\
ET\n";
        let pdf_arxiv = build_synthetic_latex_pdf(stream_arxiv);
        let proc_leak_fetcher = |url: &Url| -> Option<String> {
            let s = url.as_str();
            if s.contains("export.arxiv.org/api/query") && s.contains("2401.11111") {
                Some(
                    r#"<?xml version="1.0" encoding="UTF-8"?>
                    <feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom">
                      <entry>
                        <id>http://arxiv.org/abs/2401.11111v1</id>
                        <published>2024-01-10T00:00:00Z</published>
                        <title>Sound Empirical Evaluation of Compilers</title>
                        <summary>Compiler testing study.</summary>
                        <author><name>Ada Lovelace</name></author>
                        <arxiv:doi>10.1145/9999999</arxiv:doi>
                      </entry>
                    </feed>"#
                        .to_owned(),
                )
            } else if s.contains("api.crossref.org/works/10.1145/9999999") {
                Some(
                    r#"{"message":{"type":"proceedings","DOI":"10.1145/9999999","title":["Proceedings of PLDI 2024"],"issued":{"date-parts":[[2024]]}}}"#
                        .to_owned(),
                )
            } else {
                None
            }
        };
        let res_no_leak = discover_verified_pdf_metadata(
            pdf_arxiv,
            None,
            None,
            DocumentIdentifierHint::default(),
            Some(&proc_leak_fetcher),
        )
        .await;
        assert_eq!(res_no_leak.arxiv_id.as_deref(), Some("2401.11111"));
        assert!(
            res_no_leak.doi.is_none(),
            "rejected proceedings <arxiv:doi> must not leak into out.doi"
        );

        // 2. arXiv API HTTP 200 `<title>Error</title>` must be rejected even when PDF has no title
        let empty_pdf =
            build_synthetic_latex_pdf("BT /F1 10 Tf 72 720 Td (arXiv:9999.99999) Tj ET");
        let err_arxiv_fetcher = |url: &Url| -> Option<String> {
            if url.as_str().contains("export.arxiv.org/api/query") {
                Some(
                    r#"<?xml version="1.0" encoding="UTF-8"?>
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry>
                        <id>http://arxiv.org/api/errors#incorrect_id_format</id>
                        <title>Error</title>
                        <summary>incorrect id format</summary>
                        <author><name>arXiv api core</name></author>
                      </entry>
                    </feed>"#
                        .to_owned(),
                )
            } else {
                None
            }
        };
        let err_res = discover_verified_pdf_metadata(
            empty_pdf,
            None,
            None,
            DocumentIdentifierHint::default(),
            Some(&err_arxiv_fetcher),
        )
        .await;
        assert!(err_res.arxiv_id.is_none(), "arXiv API error entry must not be accepted");
        assert_ne!(err_res.title.as_deref(), Some("Error"));
    }

    #[tokio::test]
    async fn test_discover_verified_pdf_metadata_ignores_creation_date_and_uses_datacite_fallback()
    {
        // 1. Classic 1972 paper whose PDF has a 2026 `/CreationDate` must NOT be rejected on year
        let classic_stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (On the Criteria To Be Used in Decomposing Systems into Modules) Tj\n\
/F1 11 Tf\n\
0 -24 Td (David L. Parnas) Tj\n\
ET\n";
        let classic_pdf = build_synthetic_latex_pdf(classic_stream); // CreationDate is 2026!
        let classic_fetcher = |url: &Url| -> Option<String> {
            let s = url.as_str();
            if s.contains("api.crossref.org/works?query.bibliographic=") {
                assert!(
                    !s.contains("2026"),
                    "PDF /CreationDate year 2026 must not poison Crossref search query: {s}"
                );
                Some(
                    r#"{
                        "message": {
                            "items": [{
                                "type": "journal-article",
                                "DOI": "10.1145/361598.361623",
                                "title": ["On the criteria to be used in decomposing systems into modules"],
                                "author": [{"given": "D. L.", "family": "Parnas"}],
                                "issued": {"date-parts": [[1972, 12]]}
                            }]
                        }
                    }"#
                    .to_owned(),
                )
            } else {
                None
            }
        };
        let classic_res = discover_verified_pdf_metadata(
            classic_pdf,
            None,
            None,
            DocumentIdentifierHint::default(),
            Some(&classic_fetcher),
        )
        .await;
        assert_eq!(classic_res.doi.as_deref(), Some("10.1145/361598.361623"));
        assert_eq!(classic_res.release_year, Some(1972));

        // 4. DataCite fallback verifies LIPIcs DOI when Crossref returns 404/None
        let lipics_stream = "\
BT\n\
/F1 16 Tf\n\
72 720 Td (Empirical Type Soundness) Tj\n\
/F1 11 Tf\n\
0 -24 Td (Ada Lovelace) Tj\n\
0 -18 Td (DOI: 10.4230/LIPIcs.ECOOP.2021.12) Tj\n\
ET\n";
        let lipics_pdf = build_synthetic_latex_pdf(lipics_stream);
        let datacite_fetcher = |url: &Url| -> Option<String> {
            if url.as_str().contains("api.datacite.org/dois/10.4230/lipics.ecoop.2021.12") {
                Some(
                    r#"{
                        "data": {
                            "attributes": {
                                "doi": "10.4230/LIPIcs.ECOOP.2021.12",
                                "titles": [{"title": "Empirical Type Soundness"}],
                                "creators": [{"givenName": "Ada", "familyName": "Lovelace"}],
                                "publicationYear": 2021,
                                "types": {"resourceTypeGeneral": "ConferencePaper"}
                            }
                        }
                    }"#
                    .to_owned(),
                )
            } else {
                None
            }
        };
        let dc_res = discover_verified_pdf_metadata(
            lipics_pdf,
            None,
            None,
            DocumentIdentifierHint::default(),
            Some(&datacite_fetcher),
        )
        .await;
        assert_eq!(dc_res.doi.as_deref(), Some("10.4230/lipics.ecoop.2021.12"));
    }
}
