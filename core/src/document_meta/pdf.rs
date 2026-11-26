use std::{collections::HashMap, io::Read};

use chrono::{DateTime, Datelike, Utc};
use flate2::read::ZlibDecoder;

use super::{
    ExtractedDocumentMetadata, collapse_whitespace, extract_all_arxiv_ids_from_text,
    extract_all_dois_from_text, extract_doi_from_text,
    heuristics::{
        extract_metadata_from_layout_lines, is_junk_pdf_author, is_junk_pdf_title,
        split_author_names,
    },
    parse_pdf_date, parse_publication_date_str,
};

const MAX_PDF_SCAN_BYTES: usize = 32 * 1024 * 1024;
const MAX_DECOMPRESSED_STREAM_BYTES: u64 = 2 * 1024 * 1024;
const MAX_STREAMS_TO_INSPECT: usize = 64;
const MAX_MULTI_PAGE_STREAMS: usize = 512;
const MAX_LAYOUT_LINES: usize = 160;
const MAX_OBJSTM_BYTES: u64 = 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum PdfFontKind {
    #[default]
    Roman,
    MathItalic,
    MathSymbol,
    BlackboardBold,
    Bold,
    TextItalic,
    Mono,
}

impl PdfFontKind {
    const fn is_math(self) -> bool {
        matches!(self, Self::MathItalic | Self::MathSymbol | Self::BlackboardBold)
    }
}

#[derive(Debug, Clone)]
struct LineSpan {
    text: String,
    font_kind: PdfFontKind,
    font_size: f32,
    y: f32,
    space_before: bool,
}

#[derive(Debug, Clone)]
struct RawLayoutLine {
    spans: Vec<LineSpan>,
    font_size: f32,
    y: f32,
}

/// Single line of text extracted from page 1 of a PDF with approximate font size and vertical position.
#[derive(Debug, Clone, PartialEq)]
pub struct LayoutLine {
    pub text: String,
    pub font_size: f32,
    pub y: f32,
}

#[derive(Debug, Default)]
struct PdfInfoAndXmp {
    title: Option<String>,
    authors: Vec<String>,
    summary: Option<String>,
    creation_date: Option<DateTime<Utc>>,
    doi: Option<String>,
    arxiv_id: Option<String>,
    all_dois: Vec<String>,
    all_arxiv_ids: Vec<String>,
}

impl PdfInfoAndXmp {
    fn record_doi(&mut self, doi: String) {
        if !self.all_dois.contains(&doi) {
            self.all_dois.push(doi.clone());
        }
        if self.doi.is_none() {
            self.doi = Some(doi);
        }
    }

    fn record_arxiv_id(&mut self, id: String) {
        if !self.all_arxiv_ids.contains(&id) {
            self.all_arxiv_ids.push(id.clone());
        }
        if self.arxiv_id.is_none() {
            self.arxiv_id = Some(id);
        }
    }
}

/// Full single-pass result of pure-Rust PDF metadata and front-matter extraction.
#[derive(Debug, Default, Clone)]
pub struct PdfMetadataExtraction {
    pub metadata: ExtractedDocumentMetadata,
    pub front_text: String,
    pub info_dois: Vec<String>,
    pub info_arxiv_ids: Vec<String>,
    /// `true` when `metadata.release_year` was derived solely from PDF `/CreationDate` rather
    /// than a publication date printed on page 1 (important so modern re-typeset/scanned copies
    /// of classic papers are not rejected on publication year during Crossref verification).
    pub year_is_creation_date_only: bool,
}

/// Extracts bibliographic metadata directly from raw PDF bytes using:
/// 1. PDF `/Info` dictionary & embedded XMP metadata (`<x:xmpmeta>`), filtering software junk.
/// 2. Pure-Rust first-page content stream decompression (`FlateDecode`) and layout-aware text
///    extraction (`Tf` font sizes, `Td`/`Tm` coordinates, `TJ`/`Tj` runs, `ToUnicode` `CMap`, and
///    OT1 Computer Modern ligatures) fed into academic header heuristics.
#[must_use]
pub fn extract_pdf_metadata(pdf_bytes: &[u8]) -> ExtractedDocumentMetadata {
    extract_pdf_metadata_with_front_text(pdf_bytes).metadata
}

/// Single-pass variant of [`extract_pdf_metadata`] that also returns extracted text from pages
/// 1–2, all candidate DOIs/arXiv IDs found in `/Info` + XMP, and whether `release_year` came only
/// from `/CreationDate`.
#[must_use]
pub fn extract_pdf_metadata_with_front_text(pdf_bytes: &[u8]) -> PdfMetadataExtraction {
    if pdf_bytes.len() < 5 || !pdf_bytes.starts_with(b"%PDF-") {
        return PdfMetadataExtraction::default();
    }
    let bounded = &pdf_bytes[..pdf_bytes.len().min(MAX_PDF_SCAN_BYTES)];
    let info_meta = extract_info_and_xmp(bounded);
    let pages = extract_pdf_pages_lines(bounded, 2);
    let page1_lines = pages.first().map_or(&[][..], Vec::as_slice);
    let mut layout_meta = extract_metadata_from_layout_lines(page1_lines);
    let front_text = pages
        .into_iter()
        .flat_map(IntoIterator::into_iter)
        .map(|l| l.text)
        .collect::<Vec<_>>()
        .join("\n");

    // Combine: prefer clean Info/XMP title/author when present, but fall back to page-1 layout
    // (critical for LaTeX/pdfTeX papers where `/Title()` and `/Author()` are empty).
    // For publication date, prefer explicit page-1 date over PDF `/CreationDate`.
    let title = info_meta.title.or_else(|| layout_meta.title.take());
    let authors = if info_meta.authors.is_empty() {
        std::mem::take(&mut layout_meta.authors)
    } else {
        info_meta.authors
    };
    let year_is_creation_date_only = layout_meta.release_year.is_none()
        && layout_meta.published_at.is_none()
        && info_meta.creation_date.is_some();
    let published_at = layout_meta.published_at.or(info_meta.creation_date);
    let release_year = layout_meta.release_year.or_else(|| published_at.map(|dt| dt.year()));
    let summary = layout_meta.summary.or(info_meta.summary);
    let doi = info_meta.doi.or(layout_meta.doi);
    let arxiv_id = info_meta.arxiv_id.or(layout_meta.arxiv_id);

    PdfMetadataExtraction {
        metadata: ExtractedDocumentMetadata {
            title,
            authors,
            published_at,
            release_year,
            summary,
            doi,
            arxiv_id,
            isbn: None,
        },
        front_text,
        info_dois: info_meta.all_dois,
        info_arxiv_ids: info_meta.all_arxiv_ids,
        year_is_creation_date_only,
    }
}

fn extract_info_and_xmp(bytes: &[u8]) -> PdfInfoAndXmp {
    let mut out = PdfInfoAndXmp::default();

    // 1. Parse XMP packet (`<x:xmpmeta ...>...</x:xmpmeta>`) if present
    if let Some(xmp_xml) = extract_xmp_packet(bytes) {
        parse_xmp_metadata(&xmp_xml, &mut out);
    }

    // 2. Parse classic PDF `/Info` dictionary keys (`/Title`, `/Author`, `/Subject`, `/CreationDate`)
    if out.title.is_none()
        && let Some(raw_title) = find_pdf_dict_string(bytes, b"/Title")
    {
        let clean = collapse_whitespace(&raw_title);
        if !clean.is_empty() && !is_junk_pdf_title(&clean) {
            out.title = Some(clean);
        }
    }
    if out.authors.is_empty()
        && let Some(raw_author) = find_pdf_dict_string(bytes, b"/Author")
    {
        let clean = collapse_whitespace(&raw_author);
        if !clean.is_empty() && !is_junk_pdf_author(&clean) {
            let split = split_author_names(&clean);
            if split.is_empty() {
                out.authors = vec![clean];
            } else {
                out.authors = split;
            }
        }
    }
    if let Some(raw_subj) = find_pdf_dict_string(bytes, b"/Subject") {
        let clean = collapse_whitespace(&raw_subj);
        for d in extract_all_dois_from_text(&clean) {
            out.record_doi(d);
        }
        for aid in extract_all_arxiv_ids_from_text(&clean) {
            out.record_arxiv_id(aid);
        }
        if out.summary.is_none() && clean.len() >= 25 {
            out.summary = Some(clean);
        }
    }
    if let Some(raw_kw) = find_pdf_dict_string(bytes, b"/Keywords") {
        let clean = collapse_whitespace(&raw_kw);
        for d in extract_all_dois_from_text(&clean) {
            out.record_doi(d);
        }
        for aid in extract_all_arxiv_ids_from_text(&clean) {
            out.record_arxiv_id(aid);
        }
    }
    if out.creation_date.is_none()
        && let Some(raw_date) = find_pdf_dict_string(bytes, b"/CreationDate")
    {
        out.creation_date = parse_pdf_date(&raw_date);
    }
    if let Some(raw_doi) =
        find_pdf_dict_string(bytes, b"/doi").or_else(|| find_pdf_dict_string(bytes, b"/DOI"))
    {
        for d in extract_all_dois_from_text(&raw_doi) {
            out.record_doi(d);
        }
    }

    out
}

fn extract_xmp_packet(bytes: &[u8]) -> Option<String> {
    let start = find_subslice(bytes, b"<x:xmpmeta")?;
    let end_rel = find_subslice(&bytes[start..], b"</x:xmpmeta>")?;
    let end = start + end_rel + b"</x:xmpmeta>".len();
    if end - start > 512 * 1024 {
        return None;
    }
    Some(String::from_utf8_lossy(&bytes[start..end]).into_owned())
}

fn parse_xmp_metadata(xmp: &str, out: &mut PdfInfoAndXmp) {
    if let Some(title_block) = extract_xml_tag_block(xmp, "dc:title")
        && let Some(val) = extract_rdf_items(&title_block).into_iter().next()
    {
        let clean = collapse_whitespace(&val);
        if !clean.is_empty() && !is_junk_pdf_title(&clean) {
            out.title = Some(clean);
        }
    }
    if let Some(creator_block) = extract_xml_tag_block(xmp, "dc:creator") {
        let creators: Vec<String> = extract_rdf_items(&creator_block)
            .into_iter()
            .map(|c| collapse_whitespace(&c))
            .filter(|c| !c.is_empty() && !is_junk_pdf_author(c))
            .collect();
        if !creators.is_empty() {
            out.authors = creators;
        }
    }
    if let Some(desc_block) = extract_xml_tag_block(xmp, "dc:description")
        && let Some(val) = extract_rdf_items(&desc_block).into_iter().next()
    {
        let clean = collapse_whitespace(&val);
        if clean.len() >= 25 {
            out.summary = Some(clean);
        }
    }
    if let Some(date_raw) = extract_xml_simple_text(xmp, "xmp:CreateDate")
        .or_else(|| extract_xml_simple_text(xmp, "dc:date"))
        && let Some(parsed) = parse_publication_date_str(&date_raw)
    {
        out.creation_date = parsed.published_at;
    }
    if let Some(prism_doi) = extract_xml_simple_text(xmp, "prism:doi")
        .or_else(|| extract_xml_attr_value(xmp, "prism:doi"))
        && let Some(d) = extract_doi_from_text(&prism_doi)
    {
        out.record_doi(d);
    }
    if let Some(dc_id) = extract_xml_simple_text(xmp, "dc:identifier")
        .or_else(|| extract_xml_attr_value(xmp, "dc:identifier"))
    {
        for d in extract_all_dois_from_text(&dc_id) {
            out.record_doi(d);
        }
        for aid in extract_all_arxiv_ids_from_text(&dc_id) {
            out.record_arxiv_id(aid);
        }
    }
    for d in extract_all_dois_from_text(xmp) {
        out.record_doi(d);
    }
    for aid in extract_all_arxiv_ids_from_text(xmp) {
        out.record_arxiv_id(aid);
    }
}

fn extract_xml_attr_value(xml: &str, attr_name: &str) -> Option<String> {
    let needle = format!("{attr_name}=");
    let pos = xml.find(&needle)?;
    let rest = xml[pos + needle.len()..].trim_start();
    let quote = rest.chars().next().filter(|&c| c == '"' || c == '\'')?;
    let after_q = &rest[quote.len_utf8()..];
    let end = after_q.find(quote)?;
    let val = decode_basic_xml_entities(after_q[..end].trim());
    (!val.is_empty()).then_some(val)
}

fn extract_xml_tag_block(xml: &str, tag: &str) -> Option<String> {
    let open = format!("<{tag}");
    let close = format!("</{tag}>");
    let mut cursor = 0usize;
    while let Some(rel_start) = xml[cursor..].find(&open) {
        let start = cursor + rel_start;
        let after_tag_name = start + open.len();
        // Ensure we didn't match a longer tag name sharing a prefix
        if xml
            .as_bytes()
            .get(after_tag_name)
            .is_some_and(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b':'))
        {
            cursor = after_tag_name;
            continue;
        }
        let gt_rel = xml[after_tag_name..].find('>')?;
        let gt_abs = after_tag_name + gt_rel;
        // Skip self-closing `<tag .../>` and keep looking for a paired `<tag>...</tag>`
        if xml[after_tag_name..gt_abs].trim_end().ends_with('/') {
            cursor = gt_abs + 1;
            continue;
        }
        let content_start = gt_abs + 1;
        let end = xml[content_start..].find(&close)? + content_start;
        return Some(xml[content_start..end].to_owned());
    }
    None
}

fn extract_xml_simple_text(xml: &str, tag: &str) -> Option<String> {
    let inner = extract_xml_tag_block(xml, tag)?;
    let stripped = strip_xml_tags(&inner);
    let clean = decode_basic_xml_entities(&stripped).trim().to_owned();
    (!clean.is_empty()).then_some(clean)
}

fn extract_rdf_items(block: &str) -> Vec<String> {
    let mut items = Vec::new();
    let mut rest = block;
    while let Some(start) = rest.find("<rdf:li") {
        let Some(gt) = rest[start..].find('>') else {
            break;
        };
        let content_start = start + gt + 1;
        let Some(end) = rest[content_start..].find("</rdf:li>") else {
            break;
        };
        let raw = &rest[content_start..content_start + end];
        let text = decode_basic_xml_entities(&strip_xml_tags(raw)).trim().to_owned();
        if !text.is_empty() {
            items.push(text);
        }
        rest = &rest[content_start + end + "</rdf:li>".len()..];
    }
    if items.is_empty() {
        let fallback = decode_basic_xml_entities(&strip_xml_tags(block)).trim().to_owned();
        if !fallback.is_empty() {
            items.push(fallback);
        }
    }
    items
}

fn strip_xml_tags(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_tag = false;
    for ch in raw.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => in_tag = false,
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }
    out
}

fn decode_basic_xml_entities(raw: &str) -> String {
    raw.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}

/// Scans a PDF byte slice for `/Key (...)` or `/Key <hex>` dictionary entries.
fn find_pdf_dict_string(bytes: &[u8], key: &[u8]) -> Option<String> {
    let mut offset = 0;
    while let Some(rel) = find_subslice(&bytes[offset..], key) {
        let after_key = offset + rel + key.len();
        offset = after_key;
        // Require boundary after key name
        if let Some(&next_b) = bytes.get(after_key)
            && (next_b.is_ascii_alphanumeric() || next_b == b'_' || next_b == b'-')
        {
            continue;
        }
        let mut pos = after_key;
        while pos < bytes.len() && bytes[pos].is_ascii_whitespace() {
            pos += 1;
        }
        if pos >= bytes.len() {
            break;
        }
        let decoded = match bytes[pos] {
            b'(' => parse_pdf_literal_string(bytes, pos).map(|(s, _)| decode_pdf_text_bytes(&s)),
            b'<' if bytes.get(pos + 1) != Some(&b'<') => {
                parse_pdf_hex_string(bytes, pos).map(|(s, _)| decode_pdf_text_bytes(&s))
            }
            _ => None,
        };
        if let Some(val) = decoded
            && !val.trim().is_empty()
        {
            return Some(val);
        }
    }
    None
}

/// Extracts layout lines from up to `max_pages` text-bearing content streams of a PDF.
///
/// Used both for page-1 bibliographic metadata discovery and as a pure-Rust fallback for
/// multi-page reflow when external PDF tools are unavailable.
#[must_use]
pub fn extract_pdf_pages_lines(pdf_bytes: &[u8], max_pages: usize) -> Vec<Vec<LayoutLine>> {
    if max_pages == 0 || pdf_bytes.len() < 5 || !pdf_bytes.starts_with(b"%PDF-") {
        return Vec::new();
    }
    let bounded = &pdf_bytes[..pdf_bytes.len().min(MAX_PDF_SCAN_BYTES)];
    let cmap = collect_tounicode_cmaps(bounded);
    let font_kinds = collect_pdf_font_kinds(bounded);
    let max_streams = if max_pages > 1 {
        (max_pages.saturating_mul(4).saturating_add(MAX_STREAMS_TO_INSPECT))
            .min(MAX_MULTI_PAGE_STREAMS)
    } else {
        MAX_STREAMS_TO_INSPECT
    };

    let mut pages = Vec::new();
    let mut offset = 0;
    let mut inspected = 0usize;
    while inspected < max_streams && pages.len() < max_pages {
        let Some(stream_kw) = find_subslice(&bounded[offset..], b"stream") else {
            break;
        };
        let abs_kw = offset + stream_kw;
        let dict_start = abs_kw.saturating_sub(512);
        let dict_slice = &bounded[dict_start..abs_kw];

        let mut data_start = abs_kw + b"stream".len();
        if bounded.get(data_start) == Some(&b'\r') && bounded.get(data_start + 1) == Some(&b'\n') {
            data_start += 2;
        } else if bounded.get(data_start) == Some(&b'\n') {
            data_start += 1;
        } else {
            offset = data_start;
            continue;
        }

        let Some(end_rel) = find_subslice(&bounded[data_start..], b"endstream") else {
            break;
        };
        let raw_stream = &bounded[data_start..data_start + end_rel];
        offset = data_start + end_rel + b"endstream".len();
        inspected += 1;

        // Skip image, font, XRef, and compressed object streams quickly
        if find_subslice(dict_slice, b"/Image").is_some()
            || find_subslice(dict_slice, b"/Subtype /Image").is_some()
            || find_subslice(dict_slice, b"/FontFile").is_some()
            || find_subslice(dict_slice, b"/DCTDecode").is_some()
            || find_subslice(dict_slice, b"/ObjStm").is_some()
            || find_subslice(dict_slice, b"/XRef").is_some()
        {
            continue;
        }

        let decompressed: Vec<u8> = if find_subslice(dict_slice, b"/FlateDecode").is_some() {
            let Some(dec) = inflate_bounded(raw_stream, MAX_DECOMPRESSED_STREAM_BYTES) else {
                continue;
            };
            dec
        } else {
            let max_raw = usize::try_from(MAX_DECOMPRESSED_STREAM_BYTES).unwrap_or(usize::MAX);
            raw_stream[..raw_stream.len().min(max_raw)].to_vec()
        };

        if find_subslice(&decompressed, b"BT").is_none()
            || find_subslice(&decompressed, b"beginbfchar").is_some()
            || find_subslice(&decompressed, b"beginbfrange").is_some()
        {
            continue;
        }

        let lines = parse_content_stream_lines(&decompressed, &cmap, &font_kinds);
        let min_alpha = if pages.is_empty() { 10 } else { 4 };
        if lines.iter().any(|l| l.text.chars().filter(|c| c.is_alphabetic()).count() >= min_alpha) {
            pages.push(lines);
        }
    }
    pages
}

/// Scans top-level PDF objects and compressed PDF 1.5 `/ObjStm` streams to map font resource names
/// (e.g. `"F40"`) to semantic font families (`MathItalic`, `MathSymbol`, `BlackboardBold`, etc.).
fn collect_pdf_font_kinds(bytes: &[u8]) -> HashMap<String, PdfFontKind> {
    let mut obj_kinds: HashMap<u32, PdfFontKind> = HashMap::new();
    let mut res_to_obj: HashMap<String, u32> = HashMap::new();

    scan_top_level_objects_for_fonts(bytes, &mut obj_kinds);
    scan_font_resource_dicts(bytes, &mut res_to_obj);
    scan_objstm_streams_for_fonts(bytes, &mut obj_kinds, &mut res_to_obj);

    let mut resolved = HashMap::new();
    for (res_name, obj_num) in res_to_obj {
        if let Some(&kind) = obj_kinds.get(&obj_num) {
            resolved.insert(res_name, kind);
        }
    }
    resolved
}

fn scan_top_level_objects_for_fonts(bytes: &[u8], obj_kinds: &mut HashMap<u32, PdfFontKind>) {
    let mut pos = 0usize;
    while let Some(rel) = find_subslice(&bytes[pos..], b"/BaseFont") {
        let bf_pos = pos + rel;
        pos = bf_pos + b"/BaseFont".len();
        let Some(font_name) = parse_pdf_name_after(bytes, pos) else {
            continue;
        };
        let kind = classify_pdf_base_font(&font_name);
        let lookback_start = bf_pos.saturating_sub(512);
        if let Some(obj_num) = find_preceding_obj_number(&bytes[lookback_start..bf_pos]) {
            obj_kinds.entry(obj_num).or_insert(kind);
        }
    }
}

fn scan_objstm_streams_for_fonts(
    bytes: &[u8],
    obj_kinds: &mut HashMap<u32, PdfFontKind>,
    res_to_obj: &mut HashMap<String, u32>,
) {
    let mut offset = 0usize;
    let mut seen = 0usize;
    while seen < 64 {
        let Some(rel) = find_subslice(&bytes[offset..], b"/ObjStm") else {
            break;
        };
        let objstm_pos = offset + rel;
        offset = objstm_pos + b"/ObjStm".len();
        seen += 1;

        let dict_start = objstm_pos.saturating_sub(256);
        let Some(stream_rel) = find_subslice(&bytes[objstm_pos..], b"stream") else {
            break;
        };
        if stream_rel > 512 {
            continue;
        }
        let abs_stream = objstm_pos + stream_rel;
        let dict_slice = &bytes[dict_start..abs_stream];
        let Some(n_objs) = parse_pdf_dict_uint(dict_slice, b"/N") else {
            continue;
        };
        let Some(first_offset) = parse_pdf_dict_uint(dict_slice, b"/First") else {
            continue;
        };
        if n_objs == 0 || n_objs > 10_000 {
            continue;
        }

        let mut data_start = abs_stream + b"stream".len();
        if bytes.get(data_start) == Some(&b'\r') && bytes.get(data_start + 1) == Some(&b'\n') {
            data_start += 2;
        } else if bytes.get(data_start) == Some(&b'\n') {
            data_start += 1;
        } else {
            continue;
        }
        let Some(end_rel) = find_subslice(&bytes[data_start..], b"endstream") else {
            break;
        };
        let raw = &bytes[data_start..data_start + end_rel];
        offset = data_start + end_rel + b"endstream".len();

        let decompressed = if find_subslice(dict_slice, b"/FlateDecode").is_some() {
            let Some(dec) = inflate_bounded(raw, MAX_OBJSTM_BYTES) else {
                continue;
            };
            dec
        } else {
            let max_raw = usize::try_from(MAX_OBJSTM_BYTES).unwrap_or(usize::MAX);
            raw[..raw.len().min(max_raw)].to_vec()
        };

        let first = usize::try_from(first_offset).unwrap_or(usize::MAX);
        if first >= decompressed.len() {
            continue;
        }
        let header_text = String::from_utf8_lossy(&decompressed[..first]);
        let nums: Vec<u32> =
            header_text.split_whitespace().filter_map(|tok| tok.parse::<u32>().ok()).collect();
        let pairs: Vec<(u32, usize)> = nums
            .as_chunks::<2>()
            .0
            .iter()
            .take(usize::try_from(n_objs).unwrap_or(0))
            .filter_map(|c| Some((c[0], usize::try_from(c[1]).ok()?)))
            .collect();

        for (idx, &(obj_num, rel_off)) in pairs.iter().enumerate() {
            let start = first.saturating_add(rel_off);
            let end = pairs
                .get(idx + 1)
                .map_or(decompressed.len(), |&(_, next_off)| first.saturating_add(next_off))
                .min(decompressed.len());
            if start >= end {
                continue;
            }
            let body = &decompressed[start..end];
            if let Some(bf_rel) = find_subslice(body, b"/BaseFont")
                && let Some(font_name) = parse_pdf_name_after(body, bf_rel + b"/BaseFont".len())
            {
                obj_kinds.entry(obj_num).or_insert_with(|| classify_pdf_base_font(&font_name));
            }
            if find_subslice(body, b"/Font").is_some() {
                scan_font_resource_dicts(body, res_to_obj);
            }
        }
    }
}

fn scan_font_resource_dicts(bytes: &[u8], res_to_obj: &mut HashMap<String, u32>) {
    let mut offset = 0usize;
    while let Some(rel) = find_subslice(&bytes[offset..], b"/Font") {
        let mut pos = offset + rel + b"/Font".len();
        offset = pos;
        if bytes.get(pos).is_some_and(|b| b.is_ascii_alphanumeric() || *b == b'_') {
            continue;
        }
        while pos < bytes.len() && bytes[pos].is_ascii_whitespace() {
            pos += 1;
        }
        if bytes.get(pos..pos + 2) != Some(b"<<") {
            continue;
        }
        pos += 2;
        let Some(end_rel) = find_subslice(&bytes[pos..], b">>") else {
            break;
        };
        let dict_body = &bytes[pos..pos + end_rel.min(4096)];
        parse_font_ref_entries(dict_body, res_to_obj);
        offset = pos + end_rel + 2;
    }
}

fn parse_font_ref_entries(dict_body: &[u8], res_to_obj: &mut HashMap<String, u32>) {
    let text = String::from_utf8_lossy(dict_body);
    let tokens: Vec<&str> = text.split_whitespace().collect();
    let mut i = 0usize;
    while i + 3 < tokens.len() {
        if let Some(res_name) = tokens[i].strip_prefix('/')
            && !res_name.is_empty()
            && tokens[i + 3] == "R"
            && let Ok(obj_num) = tokens[i + 1].parse::<u32>()
        {
            res_to_obj.entry(res_name.to_owned()).or_insert(obj_num);
            i += 4;
            continue;
        }
        i += 1;
    }
}

fn parse_pdf_name_after(bytes: &[u8], mut pos: usize) -> Option<String> {
    while pos < bytes.len() && bytes[pos].is_ascii_whitespace() {
        pos += 1;
    }
    if bytes.get(pos) != Some(&b'/') {
        return None;
    }
    pos += 1;
    let start = pos;
    while pos < bytes.len() && !is_pdf_delimiter(bytes[pos]) {
        pos += 1;
    }
    (pos > start).then(|| String::from_utf8_lossy(&bytes[start..pos]).into_owned())
}

fn parse_pdf_dict_uint(dict: &[u8], key: &[u8]) -> Option<u32> {
    let mut offset = 0usize;
    while let Some(rel) = find_subslice(&dict[offset..], key) {
        let mut pos = offset + rel + key.len();
        offset = pos;
        if dict.get(pos).is_some_and(u8::is_ascii_alphanumeric) {
            continue;
        }
        while pos < dict.len() && dict[pos].is_ascii_whitespace() {
            pos += 1;
        }
        let start = pos;
        while pos < dict.len() && dict[pos].is_ascii_digit() {
            pos += 1;
        }
        if pos > start
            && let Ok(val) = std::str::from_utf8(&dict[start..pos]).unwrap_or("").parse::<u32>()
        {
            return Some(val);
        }
    }
    None
}

fn find_preceding_obj_number(slice: &[u8]) -> Option<u32> {
    let text = String::from_utf8_lossy(slice);
    let tokens: Vec<&str> = text.split_whitespace().collect();
    for idx in (2..tokens.len()).rev() {
        if tokens[idx] == "obj"
            && tokens[idx - 1] == "0"
            && let Ok(num) = tokens[idx - 2].parse::<u32>()
        {
            return Some(num);
        }
    }
    None
}

fn classify_pdf_base_font(base_font: &str) -> PdfFontKind {
    // Strip 6-letter subset tag (e.g. `ENDZGJ+CMMI12` -> `CMMI12`)
    let stripped = match base_font.split_once('+') {
        Some((tag, rest)) if tag.len() == 6 && tag.bytes().all(|b| b.is_ascii_uppercase()) => rest,
        _ => base_font,
    };
    let lower = stripped.to_ascii_lowercase();
    if lower.contains("msbm")
        || lower.contains("bbold")
        || lower.contains("dsrom")
        || lower.contains("mathbb")
    {
        return PdfFontKind::BlackboardBold;
    }
    if lower.contains("cmmi")
        || lower.contains("lmmi")
        || lower.contains("mathitalic")
        || lower.contains("math-italic")
        || lower.contains("mathit")
        || lower.contains("mtmi")
        || lower.contains("txmi")
        || lower.contains("pxmi")
        || lower.contains("newtxmi")
        || lower.contains("zplmmi")
        || lower.contains("eurm")
    {
        return PdfFontKind::MathItalic;
    }
    if lower.contains("cmsy")
        || lower.contains("cmex")
        || lower.contains("lmsy")
        || lower.contains("lmex")
        || lower.contains("msam")
        || lower.contains("mtsy")
        || lower.contains("txsy")
        || lower.contains("pxsy")
        || lower.contains("mathsym")
        || lower.contains("eusm")
        || lower.contains("wasy")
        || lower == "symbol"
    {
        return PdfFontKind::MathSymbol;
    }
    if lower.contains("cmti")
        || lower.contains("cmsl")
        || lower.contains("lmti")
        || lower.contains("italic")
        || lower.contains("oblique")
        || lower.contains("slant")
    {
        return PdfFontKind::TextItalic;
    }
    if lower.contains("cmbx")
        || lower.contains("lmbx")
        || lower.contains("bold")
        || lower.contains("black")
        || lower.contains("heavy")
        || lower.contains("demi")
    {
        return PdfFontKind::Bold;
    }
    if lower.contains("cmtt")
        || lower.contains("lmtt")
        || lower.contains("mono")
        || lower.contains("courier")
        || lower.contains("consolas")
    {
        return PdfFontKind::Mono;
    }
    PdfFontKind::Roman
}

fn inflate_bounded(raw: &[u8], max_bytes: u64) -> Option<Vec<u8>> {
    let trimmed = raw.strip_suffix(b"\r\n").or_else(|| raw.strip_suffix(b"\n")).unwrap_or(raw);
    let mut decoder = ZlibDecoder::new(trimmed).take(max_bytes);
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).ok()?;
    (!out.is_empty()).then_some(out)
}

/// Collects merged `ToUnicode` `CMap` mappings across the PDF to decode subsetted/CID fonts.
fn collect_tounicode_cmaps(bytes: &[u8]) -> HashMap<u16, String> {
    let mut map = HashMap::new();
    let mut offset = 0;
    let mut inspected = 0usize;
    while inspected < MAX_STREAMS_TO_INSPECT {
        let Some(stream_kw) = find_subslice(&bytes[offset..], b"stream") else {
            break;
        };
        let abs_kw = offset + stream_kw;
        let dict_start = abs_kw.saturating_sub(320);
        let dict_slice = &bytes[dict_start..abs_kw];
        let mut data_start = abs_kw + b"stream".len();
        if bytes.get(data_start) == Some(&b'\r') && bytes.get(data_start + 1) == Some(&b'\n') {
            data_start += 2;
        } else if bytes.get(data_start) == Some(&b'\n') {
            data_start += 1;
        } else {
            offset = data_start;
            continue;
        }
        let Some(end_rel) = find_subslice(&bytes[data_start..], b"endstream") else {
            break;
        };
        let raw_stream = &bytes[data_start..data_start + end_rel];
        offset = data_start + end_rel + b"endstream".len();
        inspected += 1;

        let data = if find_subslice(dict_slice, b"/FlateDecode").is_some() {
            let Some(dec) = inflate_bounded(raw_stream, 256 * 1024) else {
                continue;
            };
            dec
        } else {
            raw_stream[..raw_stream.len().min(256 * 1024)].to_vec()
        };
        if find_subslice(&data, b"beginbfchar").is_some()
            || find_subslice(&data, b"beginbfrange").is_some()
        {
            parse_cmap_stream(&String::from_utf8_lossy(&data), &mut map);
        }
    }
    map
}

fn parse_cmap_stream(text: &str, out: &mut HashMap<u16, String>) {
    let mut in_bfchar = false;
    let mut in_bfrange = false;
    for raw_line in text.lines() {
        let line = raw_line.trim();
        if line.contains("beginbfchar") {
            in_bfchar = true;
            continue;
        }
        if line.contains("endbfchar") {
            in_bfchar = false;
            continue;
        }
        if line.contains("beginbfrange") {
            in_bfrange = true;
            continue;
        }
        if line.contains("endbfrange") {
            in_bfrange = false;
            continue;
        }
        if in_bfchar {
            let hexes = extract_angle_hex_tokens(line);
            if hexes.len() >= 2
                && let (Some(code), Some(dst)) =
                    (parse_u16_hex(&hexes[0]), decode_utf16be_hex(&hexes[1]))
            {
                out.entry(code).or_insert(dst);
            }
        } else if in_bfrange {
            let hexes = extract_angle_hex_tokens(line);
            if hexes.len() >= 3
                && let (Some(start), Some(end), Some(base)) =
                    (parse_u16_hex(&hexes[0]), parse_u16_hex(&hexes[1]), parse_u16_hex(&hexes[2]))
                && end >= start
                && (end - start) <= 256
            {
                for (i, code) in (start..=end).enumerate() {
                    if let Ok(offset) = u32::try_from(i)
                        && let Some(ch) = char::from_u32(u32::from(base) + offset)
                    {
                        out.entry(code).or_insert_with(|| ch.to_string());
                    }
                }
            }
        }
    }
}

fn extract_angle_hex_tokens(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut rest = line;
    while let Some(open) = rest.find('<') {
        let after = &rest[open + 1..];
        let Some(close) = after.find('>') else {
            break;
        };
        out.push(after[..close].trim().to_owned());
        rest = &after[close + 1..];
    }
    out
}

fn parse_u16_hex(hex: &str) -> Option<u16> {
    u16::from_str_radix(hex.trim(), 16).ok()
}

fn decode_utf16be_hex(hex: &str) -> Option<String> {
    let clean: String = hex.chars().filter(char::is_ascii_hexdigit).collect();
    if clean.is_empty() || !clean.len().is_multiple_of(2) {
        return None;
    }
    let bytes: Vec<u8> = (0..clean.len())
        .step_by(2)
        .filter_map(|i| u8::from_str_radix(&clean[i..i + 2], 16).ok())
        .collect();
    if bytes.len() >= 2 && bytes.len().is_multiple_of(2) {
        let units: Vec<u16> =
            bytes.as_chunks::<2>().0.iter().map(|c| u16::from_be_bytes(*c)).collect();
        let s: String =
            char::decode_utf16(units).map(|r| r.unwrap_or(char::REPLACEMENT_CHARACTER)).collect();
        return (!s.is_empty()).then_some(s);
    }
    None
}

#[derive(Debug, Clone)]
enum StreamToken {
    Num(f32),
    Name(String),
    Str(Vec<u8>, bool), // (bytes, is_hex)
    Array(Vec<Self>),
    Word(String),
}

struct TextExtractorState<'a> {
    cmap: &'a HashMap<u16, String>,
    font_kinds: &'a HashMap<String, PdfFontKind>,
    in_text: bool,
    font_kind: PdfFontKind,
    font_size: f32,
    tm_scale: f32,
    cur_x: f32,
    cur_y: f32,
    leading: f32,
    last_emit_width: f32,
    lines: Vec<RawLayoutLine>,
}

impl<'a> TextExtractorState<'a> {
    const fn new(
        cmap: &'a HashMap<u16, String>,
        font_kinds: &'a HashMap<String, PdfFontKind>,
    ) -> Self {
        Self {
            cmap,
            font_kinds,
            in_text: false,
            font_kind: PdfFontKind::Roman,
            font_size: 10.0,
            tm_scale: 1.0,
            cur_x: 0.0,
            cur_y: 0.0,
            leading: 12.0,
            last_emit_width: 0.0,
            lines: Vec::new(),
        }
    }

    fn effective_font_size(&self) -> f32 {
        (self.font_size * self.tm_scale).abs().clamp(4.0, 96.0)
    }

    fn emit_text(&mut self, piece: &str, prepend_space_if_same_line: bool) {
        if piece.is_empty() || self.lines.len() >= MAX_LAYOUT_LINES {
            return;
        }
        let fs = self.effective_font_size();
        let y = self.cur_y;
        self.last_emit_width += estimate_text_width(piece, fs);

        if let Some(last) = self.lines.last_mut()
            && (last.y - y).abs() <= last.font_size.max(fs) * 0.45
        {
            last.spans.push(LineSpan {
                text: piece.to_owned(),
                font_kind: self.font_kind,
                font_size: fs,
                y,
                space_before: prepend_space_if_same_line,
            });
            if fs > last.font_size {
                last.font_size = fs;
            }
            return;
        }
        self.lines.push(RawLayoutLine {
            spans: vec![LineSpan {
                text: piece.to_owned(),
                font_kind: self.font_kind,
                font_size: fs,
                y,
                space_before: false,
            }],
            font_size: fs,
            y,
        });
    }

    fn decode_content_bytes(&self, bytes: &[u8], is_hex: bool) -> String {
        if !self.cmap.is_empty() && is_hex && bytes.len() >= 2 && bytes.len().is_multiple_of(2) {
            let mut mapped = String::new();
            let mut hits = 0usize;
            for pair in bytes.as_chunks::<2>().0 {
                let code = u16::from_be_bytes(*pair);
                if let Some(s) = self.cmap.get(&code) {
                    mapped.push_str(s);
                    hits += 1;
                }
            }
            if hits * 2 >= bytes.len() / 2 {
                return mapped;
            }
        }
        decode_content_stream_string(bytes, self.cmap, self.font_kind)
    }
}

#[allow(clippy::too_many_lines)]
fn parse_content_stream_lines(
    stream: &[u8],
    cmap: &HashMap<u16, String>,
    font_kinds: &HashMap<String, PdfFontKind>,
) -> Vec<LayoutLine> {
    let tokens = tokenize_pdf_stream(stream);
    let mut state = TextExtractorState::new(cmap, font_kinds);
    let mut operands: Vec<StreamToken> = Vec::new();
    let mut pending_inline_space = false;

    for tok in tokens {
        match tok {
            StreamToken::Word(op) => {
                match op.as_str() {
                    "BT" => {
                        state.in_text = true;
                        state.cur_x = 0.0;
                        state.cur_y = 0.0;
                        state.tm_scale = 1.0;
                        state.last_emit_width = 0.0;
                        pending_inline_space = false;
                    }
                    "ET" => {
                        state.in_text = false;
                        pending_inline_space = false;
                    }
                    "Tf" if operands.len() >= 2 => {
                        let n = operands.len();
                        if let StreamToken::Name(font_name) = &operands[n - 2] {
                            state.font_kind =
                                *state.font_kinds.get(font_name).unwrap_or(&PdfFontKind::Roman);
                        }
                        if let StreamToken::Num(sz) = &operands[n - 1] {
                            state.font_size = sz.abs().max(1.0);
                        }
                    }
                    "TL" if !operands.is_empty() => {
                        if let Some(StreamToken::Num(l)) = operands.last() {
                            state.leading = l.abs();
                        }
                    }
                    "Td" | "TD" if state.in_text && operands.len() >= 2 => {
                        let n = operands.len();
                        if let (StreamToken::Num(tx), StreamToken::Num(ty)) =
                            (&operands[n - 2], &operands[n - 1])
                        {
                            let fs = state.effective_font_size();
                            if op == "TD" {
                                state.leading = (-ty).abs();
                            }
                            if ty.abs() <= fs * 0.45 {
                                let gap = *tx - state.last_emit_width;
                                pending_inline_space = gap > fs * 0.11;
                            } else {
                                pending_inline_space = false;
                            }
                            state.cur_x += *tx;
                            state.cur_y += *ty;
                            state.last_emit_width = 0.0;
                        }
                    }
                    "Tm" if state.in_text && operands.len() >= 6 => {
                        let n = operands.len();
                        if let (
                            StreamToken::Num(a),
                            StreamToken::Num(_b),
                            StreamToken::Num(_c),
                            StreamToken::Num(d),
                            StreamToken::Num(e),
                            StreamToken::Num(f),
                        ) = (
                            &operands[n - 6],
                            &operands[n - 5],
                            &operands[n - 4],
                            &operands[n - 3],
                            &operands[n - 2],
                            &operands[n - 1],
                        ) {
                            let scale = a.abs().max(d.abs());
                            if scale > 0.01 {
                                state.tm_scale = scale;
                            }
                            let fs = state.effective_font_size();
                            if (f - state.cur_y).abs() <= fs * 0.45 {
                                let gap = (*e - state.cur_x) - state.last_emit_width;
                                pending_inline_space = gap > fs * 0.11;
                            } else {
                                pending_inline_space = false;
                            }
                            state.cur_x = *e;
                            state.cur_y = *f;
                            state.last_emit_width = 0.0;
                        }
                    }
                    "T*" if state.in_text => {
                        state.cur_y -= state.leading;
                        state.last_emit_width = 0.0;
                        pending_inline_space = false;
                    }
                    "Tj" | "'" | "\"" if state.in_text => {
                        if op == "'" || op == "\"" {
                            state.cur_y -= state.leading;
                            state.last_emit_width = 0.0;
                            pending_inline_space = false;
                        }
                        if let Some(StreamToken::Str(bytes, is_hex)) = operands.last() {
                            let s = state.decode_content_bytes(bytes, *is_hex);
                            state.emit_text(&s, pending_inline_space);
                            pending_inline_space = false;
                        }
                    }
                    "TJ" if state.in_text => {
                        if let Some(StreamToken::Array(items)) = operands.last() {
                            let mut buf = String::new();
                            for item in items {
                                match item {
                                    StreamToken::Str(bytes, is_hex) => {
                                        buf.push_str(&state.decode_content_bytes(bytes, *is_hex));
                                    }
                                    StreamToken::Num(kern)
                                        if *kern <= -140.0
                                            && !buf.is_empty()
                                            && !buf.ends_with(' ') =>
                                    {
                                        buf.push(' ');
                                    }
                                    _ => {}
                                }
                            }
                            state.emit_text(&buf, pending_inline_space);
                            pending_inline_space = false;
                        }
                    }
                    _ => {}
                }
                operands.clear();
            }
            other => {
                if operands.len() < 16 {
                    operands.push(other);
                }
            }
        }
    }

    state.lines.iter().map(finalize_raw_line).collect()
}

fn finalize_raw_line(raw: &RawLayoutLine) -> LayoutLine {
    let has_math = raw.spans.iter().any(|s| s.font_kind.is_math());
    if !has_math {
        let mut text = String::new();
        for span in &raw.spans {
            append_plain_span(&mut text, &span.text, span.space_before);
        }
        return LayoutLine { text, font_size: raw.font_size, y: raw.y };
    }

    let base_y = raw.y;
    let base_fs = raw.font_size;
    let spans = &raw.spans;
    let mut out = String::new();
    let mut i = 0usize;
    let mut partial_plain_prefix: Option<&str> = None;

    while i < spans.len() {
        if let Some(rem) = partial_plain_prefix.take() {
            append_plain_span(&mut out, rem, false);
            i += 1;
            continue;
        }
        if !spans[i].font_kind.is_math() {
            append_plain_span(&mut out, &spans[i].text, spans[i].space_before);
            i += 1;
            continue;
        }

        // Start a coalesced math run anchored on a real math font span.
        let space_before_run = spans[i].space_before;
        let mut expr = String::new();
        let mut open_parens = 0usize;
        let mut open_brackets = 0usize;
        let mut j = i;

        while j < spans.len() {
            let sp = &spans[j];
            let trimmed = sp.text.trim();
            if trimmed.is_empty() {
                j += 1;
                continue;
            }
            let is_script = is_baseline_script_span(sp, base_y, base_fs);
            if sp.font_kind.is_math() {
                append_math_span(&mut expr, sp, base_y, base_fs, is_script);
                update_math_delimiters(trimmed, &mut open_parens, &mut open_brackets);
                j += 1;
                continue;
            }
            if is_script && is_math_script_text(trimmed) {
                append_math_span(&mut expr, sp, base_y, base_fs, true);
                j += 1;
                continue;
            }
            if sp.font_kind == PdfFontKind::Roman {
                let next_is_math = has_upcoming_math_span(spans, j + 1, base_y, base_fs);
                if next_is_math && is_roman_math_glue(trimmed) {
                    append_roman_math_glue(&mut expr, trimmed);
                    update_math_delimiters(trimmed, &mut open_parens, &mut open_brackets);
                    j += 1;
                    continue;
                }
                if is_closing_math_delimiter(trimmed, open_parens, open_brackets) {
                    append_roman_math_glue(&mut expr, trimmed);
                    update_math_delimiters(trimmed, &mut open_parens, &mut open_brackets);
                    j += 1;
                    continue;
                }
                if (open_parens > 0 || open_brackets > 0)
                    && let Some((closers, rest)) =
                        split_leading_closing_delims(&sp.text, open_parens, open_brackets)
                {
                    append_roman_math_glue(&mut expr, closers);
                    update_math_delimiters(closers, &mut open_parens, &mut open_brackets);
                    partial_plain_prefix = Some(rest);
                    break;
                }
                if is_trailing_math_number_after_op(&expr, trimmed) {
                    append_roman_math_glue(&mut expr, trimmed);
                    j += 1;
                    continue;
                }
            }
            break;
        }

        let clean_expr = expr.trim();
        if !clean_expr.is_empty() {
            let wrapped = format!("${clean_expr}$");
            append_plain_span(&mut out, &wrapped, space_before_run);
        }
        i = j.max(i + 1);
    }

    LayoutLine { text: out, font_size: raw.font_size, y: raw.y }
}

fn split_leading_closing_delims(
    raw: &str,
    mut open_parens: usize,
    mut open_brackets: usize,
) -> Option<(&str, &str)> {
    let trimmed = raw.trim_start();
    let mut split_idx = 0usize;
    for (idx, ch) in trimmed.char_indices() {
        match ch {
            ')' if open_parens > 0 => {
                open_parens -= 1;
                split_idx = idx + ch.len_utf8();
            }
            ']' if open_brackets > 0 => {
                open_brackets -= 1;
                split_idx = idx + ch.len_utf8();
            }
            _ => break,
        }
    }
    (split_idx > 0 && split_idx < trimmed.len())
        .then(|| (&trimmed[..split_idx], &trimmed[split_idx..]))
}

fn append_plain_span(out: &mut String, piece: &str, space_before: bool) {
    if space_before
        && !out.is_empty()
        && !out.ends_with(' ')
        && !out.ends_with('(')
        && !out.ends_with('[')
        && !piece.starts_with(' ')
        && !piece.starts_with(')')
        && !piece.starts_with(']')
        && !piece.starts_with(',')
        && !piece.starts_with('.')
        && !piece.starts_with(';')
        && !piece.starts_with(':')
    {
        out.push(' ');
    }
    out.push_str(piece);
}

fn is_baseline_script_span(span: &LineSpan, base_y: f32, base_fs: f32) -> bool {
    span.font_size <= base_fs * 0.82 && (span.y - base_y).abs() >= base_fs * 0.14
}

fn is_math_script_text(trimmed: &str) -> bool {
    !trimmed.is_empty()
        && trimmed.len() <= 16
        && trimmed
            .chars()
            .all(|c| c.is_alphanumeric() || matches!(c, '+' | '-' | '=' | '(' | ')' | ',' | '*'))
}

fn has_upcoming_math_span(spans: &[LineSpan], start: usize, base_y: f32, base_fs: f32) -> bool {
    let mut checked = 0usize;
    for sp in spans.iter().skip(start) {
        let trimmed = sp.text.trim();
        if trimmed.is_empty() {
            continue;
        }
        if sp.font_kind.is_math()
            || (is_baseline_script_span(sp, base_y, base_fs) && is_math_script_text(trimmed))
        {
            return true;
        }
        if sp.font_kind == PdfFontKind::Roman && is_roman_math_glue(trimmed) && checked < 3 {
            checked += 1;
            continue;
        }
        return false;
    }
    false
}

fn is_roman_math_glue(trimmed: &str) -> bool {
    if is_latex_operator_word(trimmed) {
        return true;
    }
    if trimmed.len() > 12 {
        return false;
    }
    trimmed.chars().all(|c| {
        c.is_ascii_digit()
            || matches!(
                c,
                '(' | ')'
                    | '['
                    | ']'
                    | '{'
                    | '}'
                    | '+'
                    | '-'
                    | '='
                    | '<'
                    | '>'
                    | '/'
                    | ','
                    | ':'
                    | ';'
                    | '|'
                    | '!'
                    | '.'
                    | ' '
            )
    })
}

fn is_latex_operator_word(word: &str) -> bool {
    matches!(
        word,
        "log"
            | "ln"
            | "lg"
            | "exp"
            | "sin"
            | "cos"
            | "tan"
            | "min"
            | "max"
            | "inf"
            | "sup"
            | "lim"
            | "gcd"
            | "lcm"
            | "det"
            | "dim"
            | "deg"
            | "ker"
            | "arg"
            | "mod"
            | "Pr"
            | "poly"
            | "polylog"
            | "diag"
            | "wt"
            | "sgn"
            | "supp"
            | "rank"
            | "tr"
    )
}

fn is_closing_math_delimiter(trimmed: &str, open_parens: usize, open_brackets: usize) -> bool {
    if trimmed.is_empty() || trimmed.len() > 3 {
        return false;
    }
    let mut need_p = 0usize;
    let mut need_b = 0usize;
    for ch in trimmed.chars() {
        match ch {
            ')' => need_p += 1,
            ']' => need_b += 1,
            _ => return false,
        }
    }
    (need_p > 0 || need_b > 0) && need_p <= open_parens && need_b <= open_brackets
}

fn is_trailing_math_number_after_op(expr: &str, trimmed: &str) -> bool {
    if trimmed.is_empty() || trimmed.len() > 8 || !trimmed.bytes().all(|b| b.is_ascii_digit()) {
        return false;
    }
    let tail = expr.trim_end();
    tail.ends_with(['=', '<', '>', '+', '-', '≤', '≥', '≠', '≈', '≡'])
        || tail.ends_with("\\leq")
        || tail.ends_with("\\geq")
        || tail.ends_with("\\neq")
        || tail.ends_with("\\sim")
        || tail.ends_with("\\approx")
        || tail.ends_with("\\cdot")
        || tail.ends_with("\\times")
}

fn update_math_delimiters(trimmed: &str, open_parens: &mut usize, open_brackets: &mut usize) {
    for ch in trimmed.chars() {
        match ch {
            '(' => *open_parens = open_parens.saturating_add(1),
            ')' => *open_parens = open_parens.saturating_sub(1),
            '[' => *open_brackets = open_brackets.saturating_add(1),
            ']' => *open_brackets = open_brackets.saturating_sub(1),
            _ => {}
        }
    }
}

fn append_math_span(
    expr: &mut String,
    span: &LineSpan,
    base_y: f32,
    _base_fs: f32,
    is_script: bool,
) {
    let trimmed = span.text.trim();
    if trimmed.is_empty() {
        return;
    }
    let formatted = if span.font_kind == PdfFontKind::BlackboardBold
        && trimmed.chars().all(|c| c.is_ascii_uppercase())
    {
        format!("\\mathbb{{{trimmed}}}")
    } else {
        trimmed.to_owned()
    };

    if is_script && !expr.is_empty() {
        let marker = if span.y >= base_y { '^' } else { '_' };
        expr.push(marker);
        expr.push('{');
        expr.push_str(&formatted);
        expr.push('}');
        return;
    }

    if !expr.is_empty() && needs_space_between_math_tokens(expr, &formatted) {
        expr.push(' ');
    }
    expr.push_str(&formatted);
}

fn append_roman_math_glue(expr: &mut String, trimmed: &str) {
    if is_latex_operator_word(trimmed) {
        if !expr.is_empty() && !expr.ends_with([' ', '(', '[', '{']) {
            expr.push(' ');
        }
        expr.push('\\');
        expr.push_str(trimmed);
        return;
    }
    if !expr.is_empty()
        && !expr.ends_with([' ', '(', '[', '{'])
        && !trimmed.starts_with([')', ']', '}', ',', ';', ':', '(', '[', '!'])
        && (expr.ends_with(|c: char| c.is_ascii_alphabetic())
            && trimmed.starts_with(|c: char| c.is_ascii_alphanumeric()))
    {
        expr.push(' ');
    }
    expr.push_str(trimmed);
}

fn needs_space_between_math_tokens(expr: &str, next: &str) -> bool {
    if expr.ends_with([' ', '(', '[', '{', '^', '_'])
        || next.starts_with([')', ']', '}', ',', ';', ':'])
    {
        return false;
    }
    // Separate LaTeX command like `\log` from following identifier `n` (`\log n`)
    if let Some(last_slash) = expr.rfind('\\') {
        let after = &expr[last_slash + 1..];
        if !after.is_empty()
            && after.chars().all(|c| c.is_ascii_alphabetic())
            && next.chars().next().is_some_and(|c| c.is_ascii_alphanumeric())
        {
            return true;
        }
    }
    false
}

fn estimate_text_width(text: &str, font_size: f32) -> f32 {
    let units: f32 = text
        .chars()
        .map(|c| match c {
            ' ' => 0.28,
            'i' | 'l' | 'I' | '1' | '.' | ',' | ':' | ';' | '!' | '\'' | '(' | ')' | '|' => 0.27,
            'f' | 'j' | 'r' | 't' | '-' => 0.34,
            'm' | 'w' | 'M' | 'W' => 0.72,
            c if c.is_ascii_uppercase() => 0.62,
            _ => 0.47,
        })
        .sum();
    units * font_size
}

fn tokenize_pdf_stream(bytes: &[u8]) -> Vec<StreamToken> {
    let mut out = Vec::new();
    let mut pos = 0;
    let len = bytes.len();
    while pos < len && out.len() < 20_000 {
        let b = bytes[pos];
        if b.is_ascii_whitespace() {
            pos += 1;
        } else if b == b'%' {
            while pos < len && bytes[pos] != b'\n' && bytes[pos] != b'\r' {
                pos += 1;
            }
        } else if b == b'(' {
            if let Some((s, next)) = parse_pdf_literal_string(bytes, pos) {
                out.push(StreamToken::Str(s, false));
                pos = next;
            } else {
                pos += 1;
            }
        } else if b == b'<' {
            if bytes.get(pos + 1) == Some(&b'<') {
                pos += 2;
            } else if let Some((s, next)) = parse_pdf_hex_string(bytes, pos) {
                out.push(StreamToken::Str(s, true));
                pos = next;
            } else {
                pos += 1;
            }
        } else if b == b'>' {
            pos += 1;
        } else if b == b'[' {
            let (arr, next) = parse_pdf_array(bytes, pos + 1);
            out.push(StreamToken::Array(arr));
            pos = next;
        } else if b == b'/' {
            pos += 1;
            let start = pos;
            while pos < len && !is_pdf_delimiter(bytes[pos]) {
                pos += 1;
            }
            out.push(StreamToken::Name(String::from_utf8_lossy(&bytes[start..pos]).into_owned()));
        } else {
            let start = pos;
            while pos < len && !is_pdf_delimiter(bytes[pos]) {
                pos += 1;
            }
            if pos == start {
                pos += 1;
                continue;
            }
            let word = String::from_utf8_lossy(&bytes[start..pos]);
            if let Ok(num) = word.parse::<f32>() {
                out.push(StreamToken::Num(num));
            } else {
                out.push(StreamToken::Word(word.into_owned()));
            }
        }
    }
    out
}

fn parse_pdf_array(bytes: &[u8], mut pos: usize) -> (Vec<StreamToken>, usize) {
    const MAX_PDF_ARRAY_ITEMS: usize = 4_096;
    let mut items = Vec::new();
    let len = bytes.len();
    while pos < len && items.len() < MAX_PDF_ARRAY_ITEMS {
        let b = bytes[pos];
        if b == b']' {
            return (items, pos + 1);
        }
        if b.is_ascii_whitespace() {
            pos += 1;
        } else if b == b'(' {
            if let Some((s, next)) = parse_pdf_literal_string(bytes, pos) {
                items.push(StreamToken::Str(s, false));
                pos = next;
            } else {
                pos += 1;
            }
        } else if b == b'<' {
            if let Some((s, next)) = parse_pdf_hex_string(bytes, pos) {
                items.push(StreamToken::Str(s, true));
                pos = next;
            } else {
                pos += 1;
            }
        } else {
            let start = pos;
            while pos < len && !is_pdf_delimiter(bytes[pos]) {
                pos += 1;
            }
            if pos == start {
                pos += 1;
                continue;
            }
            let word = String::from_utf8_lossy(&bytes[start..pos]);
            if let Ok(num) = word.parse::<f32>() {
                items.push(StreamToken::Num(num));
            }
        }
    }
    (items, pos)
}

const fn is_pdf_delimiter(b: u8) -> bool {
    b.is_ascii_whitespace()
        || matches!(b, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}

fn parse_pdf_literal_string(bytes: &[u8], start: usize) -> Option<(Vec<u8>, usize)> {
    if bytes.get(start) != Some(&b'(') {
        return None;
    }
    let mut out = Vec::new();
    let mut pos = start + 1;
    let mut depth = 1usize;
    while pos < bytes.len() && out.len() < 4096 {
        let b = bytes[pos];
        if b == b'\\' && pos + 1 < bytes.len() {
            pos += 1;
            let esc = bytes[pos];
            match esc {
                b'n' => {
                    out.push(b'\n');
                    pos += 1;
                }
                b'r' => {
                    out.push(b'\r');
                    pos += 1;
                }
                b't' => {
                    out.push(b'\t');
                    pos += 1;
                }
                b'b' => {
                    out.push(0x08);
                    pos += 1;
                }
                b'f' => {
                    out.push(0x0C);
                    pos += 1;
                }
                b'\r' => {
                    pos += 1;
                    if bytes.get(pos) == Some(&b'\n') {
                        pos += 1;
                    }
                }
                b'\n' => {
                    pos += 1;
                }
                b'0'..=b'7' => {
                    let mut val = u32::from(esc - b'0');
                    pos += 1;
                    for _ in 0..2 {
                        if let Some(&oct) = bytes.get(pos)
                            && (b'0'..=b'7').contains(&oct)
                        {
                            val = val * 8 + u32::from(oct - b'0');
                            pos += 1;
                        } else {
                            break;
                        }
                    }
                    #[allow(clippy::cast_possible_truncation)]
                    out.push((val & 0xFF) as u8);
                }
                other => {
                    out.push(other);
                    pos += 1;
                }
            }
        } else if b == b'(' {
            depth += 1;
            out.push(b);
            pos += 1;
        } else if b == b')' {
            depth -= 1;
            pos += 1;
            if depth == 0 {
                return Some((out, pos));
            }
            out.push(b);
        } else {
            out.push(b);
            pos += 1;
        }
    }
    None
}

fn parse_pdf_hex_string(bytes: &[u8], start: usize) -> Option<(Vec<u8>, usize)> {
    if bytes.get(start) != Some(&b'<') {
        return None;
    }
    let mut hex = Vec::new();
    let mut pos = start + 1;
    while pos < bytes.len() && hex.len() < 8192 {
        let b = bytes[pos];
        pos += 1;
        if b == b'>' {
            if !hex.len().is_multiple_of(2) {
                hex.push(b'0');
            }
            let out: Vec<u8> = hex
                .as_chunks::<2>()
                .0
                .iter()
                .filter_map(|pair| {
                    let s = std::str::from_utf8(pair).ok()?;
                    u8::from_str_radix(s, 16).ok()
                })
                .collect();
            return Some((out, pos));
        }
        if b.is_ascii_hexdigit() {
            hex.push(b);
        }
    }
    None
}

fn decode_pdf_text_bytes(bytes: &[u8]) -> String {
    if bytes.len() >= 2 && bytes[0] == 0xFE && bytes[1] == 0xFF {
        let units: Vec<u16> =
            bytes[2..].as_chunks::<2>().0.iter().map(|c| u16::from_be_bytes(*c)).collect();
        return char::decode_utf16(units)
            .map(|r| r.unwrap_or(char::REPLACEMENT_CHARACTER))
            .collect();
    }
    if bytes.len() >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF {
        return String::from_utf8_lossy(&bytes[3..]).into_owned();
    }
    if let Ok(utf8) = std::str::from_utf8(bytes) {
        return utf8.to_owned();
    }
    bytes.iter().map(|&b| decode_winansi_byte(b)).collect()
}

fn decode_content_stream_string(
    bytes: &[u8],
    cmap: &HashMap<u16, String>,
    font_kind: PdfFontKind,
) -> String {
    let mut out = String::with_capacity(bytes.len());
    for &b in bytes {
        match font_kind {
            PdfFontKind::MathItalic => {
                if let Some(sym) = decode_cmmi_oml_byte(b) {
                    out.push_str(sym);
                } else if b >= 0x80
                    && let Some(mapped) = cmap.get(&u16::from(b))
                {
                    out.push_str(mapped);
                } else if b >= 0x20 {
                    out.push(decode_winansi_byte(b));
                }
            }
            PdfFontKind::MathSymbol => {
                if let Some(sym) = decode_cmsy_oms_byte(b) {
                    out.push_str(sym);
                } else if b >= 0x80
                    && let Some(mapped) = cmap.get(&u16::from(b))
                {
                    out.push_str(mapped);
                } else if b >= 0x20 {
                    out.push(decode_winansi_byte(b));
                }
            }
            _ => {
                // OT1 Computer Modern / TeX standard ligatures in low control range (0x0B..=0x0F)
                match b {
                    0x0B => out.push_str("ff"),
                    0x0C => out.push_str("fi"),
                    0x0D => out.push_str("fl"),
                    0x0E => out.push_str("ffi"),
                    0x0F => out.push_str("ffl"),
                    0x00..=0x0A | 0x10..=0x1F => {
                        if let Some(mapped) = cmap.get(&u16::from(b)) {
                            out.push_str(mapped);
                        }
                    }
                    _ => {
                        if b >= 0x80
                            && let Some(mapped) = cmap.get(&u16::from(b))
                        {
                            out.push_str(mapped);
                        } else {
                            out.push(decode_winansi_byte(b));
                        }
                    }
                }
            }
        }
    }
    out
}

const fn decode_cmmi_oml_byte(b: u8) -> Option<&'static str> {
    Some(match b {
        0x0B => "\\alpha",
        0x0C => "\\beta",
        0x0D => "\\gamma",
        0x0E => "\\delta",
        0x0F => "\\epsilon",
        0x10 => "\\zeta",
        0x11 => "\\eta",
        0x12 => "\\theta",
        0x13 => "\\iota",
        0x14 => "\\kappa",
        0x15 => "\\lambda",
        0x16 => "\\mu",
        0x17 => "\\nu",
        0x18 => "\\xi",
        0x19 => "\\pi",
        0x1A => "\\rho",
        0x1B => "\\sigma",
        0x1C => "\\tau",
        0x1D => "\\upsilon",
        0x1E => "\\phi",
        0x1F => "\\chi",
        0x21 => "\\omega",
        0x22 => "\\varepsilon",
        0x23 => "\\vartheta",
        0x27 => "\\varphi",
        0x60 => "\\ell",
        _ => return None,
    })
}

const fn decode_cmsy_oms_byte(b: u8) -> Option<&'static str> {
    Some(match b {
        0x00 => "-",
        0x01 => "\\cdot",
        0x02 => "\\times",
        0x03 => "*",
        0x06 => "\\pm",
        0x0E => "\\circ",
        0x14 => "\\leq",
        0x15 => "\\geq",
        0x18 => "\\sim",
        0x19 => "\\approx",
        0x1A => "\\subset",
        0x1B => "\\supset",
        0x1C => "\\ll",
        0x1D => "\\gg",
        0x20 => "\\leftarrow",
        0x21 => "\\to",
        0x31 => "\\infty",
        0x32 => "\\in",
        0x38 => "\\forall",
        0x39 => "\\exists",
        0x3B => "\\emptyset",
        0x5B => "\\cup",
        0x5C => "\\cap",
        0x66 => "\\{",
        0x67 => "\\}",
        _ => return None,
    })
}

const fn decode_winansi_byte(b: u8) -> char {
    match b {
        0x80 => '€',
        0x85 => '…',
        0x91 => '‘',
        0x92 => '’',
        0x93 => '“',
        0x94 => '”',
        0x95 => '•',
        0x96 => '–',
        0x97 => '—',
        other => other as char,
    }
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || needle.len() > haystack.len() {
        return None;
    }
    haystack.windows(needle.len()).position(|w| w == needle)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::io::Write;

    use flate2::{Compression, write::ZlibEncoder};

    use super::*;

    fn build_minimal_pdf(info_dict: &str, page1_stream: &str) -> Vec<u8> {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(page1_stream.as_bytes()).unwrap();
        let compressed = encoder.finish().unwrap();

        let mut pdf = Vec::new();
        pdf.extend_from_slice(b"%PDF-1.5\n");
        pdf.extend_from_slice(format!("1 0 obj\n<< {info_dict} >>\nendobj\n").as_bytes());
        pdf.extend_from_slice(
            format!("2 0 obj\n<< /Length {} /Filter /FlateDecode >>\nstream\n", compressed.len())
                .as_bytes(),
        );
        pdf.extend_from_slice(&compressed);
        pdf.extend_from_slice(b"\nendstream\nendobj\n%%EOF\n");
        pdf
    }

    #[test]
    fn test_extract_pdf_metadata_from_latex_page1_when_info_blank() {
        let stream = r"BT
/F38 17.2154 Tf 182.797 665.305 Td [(On)-302(the)-302(recen)26(tly)-302(p)1(r)-1(op)-26(osed)]TJ/F40 17.2154 Tf 179.934 0 Td [(o)]TJ/F38 17.2154 Tf 8.104 0 Td [(\050)]TJ/F40 17.2154 Tf 6.096 0 Td [(n)]TJ/F38 17.2154 Tf 12.931 0 Td [(log)]TJ/F40 17.2154 Tf 23.183 0 Td [(n)]TJ/F38 17.2154 Tf 10.062 0 Td [(\051)]TJ -226.793 -21.918 Td [(in)26(teger)-302(m)26(ultiplication)-302(algorithm)]TJ
/F39 11.9552 Tf 32.712 -37.086 Td [(Da)27(vid)-326(Harv)27(ey)82(,)-327(UNSW)-326(Sydney)]TJ
29.337 -31.233 Td [(20th)-326(Octob)-27(er)-327(2026)]TJ
/F64 9.9626 Tf 25.719 -28.319 Td [(Abstract)]TJ
/F28 9.9626 Tf -169.865 -18.978 Td [(I)-413(o\013er)-414(some)-413(thoughts)-414(about)-413(the)-413(algorithm.)]TJ
ET";
        let pdf =
            build_minimal_pdf("/Title() /Author() /CreationDate (D:20261020135200+11'00')", stream);
        let meta = extract_pdf_metadata(&pdf);
        assert_eq!(
            meta.title.as_deref(),
            Some("On the recently proposed o(n log n) integer multiplication algorithm")
        );
        assert_eq!(meta.authors, vec!["David Harvey"]);
        assert_eq!(meta.release_year, Some(2026));
        assert_eq!(meta.published_at.unwrap().format("%Y-%m-%d").to_string(), "2026-10-20");
        assert_eq!(meta.summary.as_deref(), Some("I offer some thoughts about the algorithm."));
    }

    #[test]
    fn test_extract_pdf_metadata_prefers_clean_info_and_ignores_junk() {
        let stream = "BT /F1 16 Tf 72 720 Td (Real Paper Title) Tj /F2 11 Tf 0 -24 Td (Alice Smith and Bob Jones) Tj ET";
        let pdf = build_minimal_pdf("/Title (Microsoft Word - draft.docx) /Author (Admin)", stream);
        let meta = extract_pdf_metadata(&pdf);
        assert_eq!(meta.title.as_deref(), Some("Real Paper Title"));
        assert_eq!(meta.authors, vec!["Alice Smith", "Bob Jones"]);

        let good_info = build_minimal_pdf(
            "/Title (Attention Is All You Need) /Author (Ashish Vaswani; Noam Shazeer) /CreationDate (D:20170612000000Z)",
            "",
        );
        let meta2 = extract_pdf_metadata(&good_info);
        assert_eq!(meta2.title.as_deref(), Some("Attention Is All You Need"));
        assert_eq!(meta2.authors, vec!["Ashish Vaswani", "Noam Shazeer"]);
        assert_eq!(meta2.release_year, Some(2017));
    }

    #[test]
    fn test_extract_pdf_metadata_detects_math_font_runs_as_latex() {
        let stream = r"BT
/F38 17.2154 Tf 182.797 665.305 Td [(On)-302(the)-302(recen)26(tly)-302(p)1(r)-1(op)-26(osed)]TJ/F40 17.2154 Tf 179.934 0 Td [(o)]TJ/F38 17.2154 Tf 8.104 0 Td [(\050)]TJ/F40 17.2154 Tf 6.096 0 Td [(n)]TJ/F38 17.2154 Tf 12.931 0 Td [(log)]TJ/F40 17.2154 Tf 23.183 0 Td [(n)]TJ/F38 17.2154 Tf 10.062 0 Td [(\051)]TJ -226.793 -21.918 Td [(in)26(teger)-302(m)26(ultiplication)-302(algorithm)]TJ
/F39 11.9552 Tf 32.712 -37.086 Td [(Da)27(vid)-326(Harv)27(ey)82(,)-327(UNSW)-326(Sydney)]TJ
29.337 -31.233 Td [(20th)-326(Octob)-27(er)-327(2026)]TJ
/F64 9.9626 Tf 25.719 -28.319 Td [(Abstract)]TJ
/F28 9.9626 Tf -169.865 -18.978 Td [(We)-300(study)]TJ /F40 9.9626 Tf 50 0 Td [(n)]TJ /F31 9.9626 Tf 12 0 Td [(important)]TJ /F28 9.9626 Tf 55 0 Td [(bounds.)]TJ
ET";
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(stream.as_bytes()).unwrap();
        let compressed = encoder.finish().unwrap();

        let mut pdf = Vec::new();
        pdf.extend_from_slice(b"%PDF-1.5\n");
        pdf.extend_from_slice(b"1 0 obj\n<< /Title() /Author() >>\nendobj\n");
        pdf.extend_from_slice(
            b"3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /FRQZFX+CMR17 >>\nendobj\n",
        );
        pdf.extend_from_slice(
            b"4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /ENDZGJ+CMMI12 >>\nendobj\n",
        );
        pdf.extend_from_slice(
            b"5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /GJXXPT+CMTI10 >>\nendobj\n",
        );
        pdf.extend_from_slice(
            b"6 0 obj\n<< /Resources << /Font << /F38 3 0 R /F40 4 0 R /F31 5 0 R >> >> >>\nendobj\n",
        );
        pdf.extend_from_slice(
            format!("2 0 obj\n<< /Length {} /Filter /FlateDecode >>\nstream\n", compressed.len())
                .as_bytes(),
        );
        pdf.extend_from_slice(&compressed);
        pdf.extend_from_slice(b"\nendstream\nendobj\n%%EOF\n");

        let meta = extract_pdf_metadata(&pdf);
        assert_eq!(
            meta.title.as_deref(),
            Some("On the recently proposed $o(n \\log n)$ integer multiplication algorithm")
        );
        // CMTI text italics (`important`) must NOT be wrapped in `$...$`
        assert_eq!(meta.summary.as_deref(), Some("We study $n$ important bounds."));
    }

    #[test]
    fn test_extract_pdf_metadata_reads_doi_and_arxiv_from_subject_keywords_and_xmp() {
        let mut pdf = build_minimal_pdf(
            "/Title (Empirical Study of Types) /Author (Alice Smith) /Subject (ACM OOPSLA doi:10.1145/3133873) /Keywords (software engineering, arXiv:1707.01234)",
            "",
        );
        // Append XMP with dc:description (>= 25 chars) so out.summary is already populated before /Subject runs
        pdf.extend_from_slice(
            b"<x:xmpmeta><dc:description><rdf:Alt><rdf:li>XMP abstract text describing empirical type studies.</rdf:li></rdf:Alt></dc:description></x:xmpmeta>\n",
        );
        let meta = extract_pdf_metadata(&pdf);
        assert_eq!(
            meta.summary.as_deref(),
            Some("XMP abstract text describing empirical type studies.")
        );
        assert_eq!(meta.doi.as_deref(), Some("10.1145/3133873"));
        assert_eq!(meta.arxiv_id.as_deref(), Some("1707.01234"));

        let mut xmp_id_pdf = build_minimal_pdf("/Title (Preprint Study) /Author (Bob Jones)", "");
        xmp_id_pdf.extend_from_slice(
            b"<x:xmpmeta><dc:identifier rdf:resource=\"urn:skip\"/><dc:identifier>doi:10.1109/ICSE.2019.00042</dc:identifier></x:xmpmeta>\n",
        );
        let meta2 = extract_pdf_metadata(&xmp_id_pdf);
        assert_eq!(meta2.doi.as_deref(), Some("10.1109/icse.2019.00042"));

        // Attribute shorthand prism:doi + multiple DOIs across /Subject and /Keywords
        let mut multi_doi_pdf = build_minimal_pdf(
            "/Title (Multi DOI Paper) /Author (Carol Lin) /Subject (Proc 10.1145/2635868) /Keywords (doi:10.1145/2635868.2635922) /CreationDate (D:20250101000000Z)",
            "",
        );
        multi_doi_pdf.extend_from_slice(
            b"<x:xmpmeta><rdf:Description prism:doi=\"10.1145/2635868.9999999\"/></x:xmpmeta>\n",
        );
        let full = extract_pdf_metadata_with_front_text(&multi_doi_pdf);
        assert!(full.year_is_creation_date_only);
        assert!(full.info_dois.contains(&"10.1145/2635868.9999999".to_owned()));
        assert!(full.info_dois.contains(&"10.1145/2635868".to_owned()));
        assert!(full.info_dois.contains(&"10.1145/2635868.2635922".to_owned()));
    }
}
