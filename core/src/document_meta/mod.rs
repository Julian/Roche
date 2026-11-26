mod heuristics;
mod math;
mod pdf;

use chrono::{DateTime, Datelike, NaiveDate, TimeZone, Utc};
use serde::{Deserialize, Serialize};
use url::Url;

pub use self::{
    heuristics::{
        extract_metadata_from_layout_lines, extract_metadata_from_plain_text, is_junk_pdf_author,
        is_junk_pdf_title, split_author_names,
    },
    math::{
        latex_expr_to_plain_text, render_inline_math_html, render_latex_expr_to_mathml,
        strip_inline_math_delimiters,
    },
    pdf::{
        LayoutLine, PdfMetadataExtraction, extract_pdf_metadata,
        extract_pdf_metadata_with_front_text, extract_pdf_pages_lines,
    },
};
use crate::{media_urls::extract_arxiv_id, url_guard::decode_percent_utf8};

/// Normalized publication date extracted from a document, feed, web page, or user edit.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ParsedPublicationDate {
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
}

/// Structured bibliographic metadata auto-discovered from a PDF, HTML page, arXiv, or Crossref.
#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExtractedDocumentMetadata {
    pub title: Option<String>,
    pub authors: Vec<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
    pub summary: Option<String>,
    pub doi: Option<String>,
    pub arxiv_id: Option<String>,
    pub isbn: Option<String>,
}

impl ExtractedDocumentMetadata {
    #[must_use]
    pub const fn is_empty(&self) -> bool {
        self.title.is_none()
            && self.authors.is_empty()
            && self.published_at.is_none()
            && self.release_year.is_none()
            && self.summary.is_none()
            && self.doi.is_none()
            && self.arxiv_id.is_none()
            && self.isbn.is_none()
    }

    /// Merges lower-priority `fallback` fields into `self` without overwriting populated fields.
    pub fn fill_missing_from(&mut self, fallback: Self) {
        if self.title.is_none() {
            self.title = fallback.title;
        }
        if self.authors.is_empty() {
            self.authors = fallback.authors;
        }
        if self.published_at.is_none() {
            self.published_at = fallback.published_at;
        }
        if self.release_year.is_none() {
            self.release_year =
                fallback.release_year.or_else(|| self.published_at.map(|dt| dt.year()));
        }
        if self.summary.is_none() {
            self.summary = fallback.summary;
        }
        if self.doi.is_none() {
            self.doi = fallback.doi;
        }
        if self.arxiv_id.is_none() {
            self.arxiv_id = fallback.arxiv_id;
        }
        if self.isbn.is_none() {
            self.isbn = fallback.isbn;
        }
    }
}

/// Normalizes a DOI string or `doi.org` URL to its canonical lowercase bare form (`10.xxxx/...`).
#[must_use]
pub fn normalize_doi(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let candidate = if let Ok(parsed) = Url::parse(trimmed)
        && matches!(
            parsed.host_str().map(|h| h.trim_end_matches('.').to_ascii_lowercase()).as_deref(),
            Some("doi.org" | "dx.doi.org" | "www.doi.org")
        ) {
        decode_percent_utf8(parsed.path().trim_start_matches('/'))
    } else if let Some(rest) = strip_prefix_ignore_ascii_case(trimmed, "doi:") {
        rest.trim().to_owned()
    } else if let Some(rest) = strip_prefix_ignore_ascii_case(trimmed, "doi ") {
        rest.trim().to_owned()
    } else {
        trimmed.to_owned()
    };

    let cleaned = trim_doi_token(&candidate);
    is_valid_doi(cleaned).then(|| cleaned.to_ascii_lowercase())
}

fn strip_prefix_ignore_ascii_case<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
    if s.len() >= prefix.len()
        && s.is_char_boundary(prefix.len())
        && s[..prefix.len()].eq_ignore_ascii_case(prefix)
    {
        Some(&s[prefix.len()..])
    } else {
        None
    }
}

fn trim_doi_token(raw: &str) -> &str {
    let mut cur = raw.trim().trim_start_matches(['(', '[', '{', '<', '"', '\'']);
    while let Some(last) = cur.chars().last() {
        let strip = match last {
            '.' | ',' | ';' | ':' | '>' | '"' | '\'' | '!' | '?' => true,
            ')' => {
                cur.chars().filter(|&c| c == ')').count()
                    > cur.chars().filter(|&c| c == '(').count()
            }
            ']' => {
                cur.chars().filter(|&c| c == ']').count()
                    > cur.chars().filter(|&c| c == '[').count()
            }
            '}' => {
                cur.chars().filter(|&c| c == '}').count()
                    > cur.chars().filter(|&c| c == '{').count()
            }
            _ => false,
        };
        if !strip {
            break;
        }
        cur = &cur[..cur.len() - last.len_utf8()];
    }
    cur.trim()
}

fn is_valid_doi(candidate: &str) -> bool {
    if candidate.len() < 7 || candidate.len() > 256 {
        return false;
    }
    let Some((prefix, suffix)) = candidate.split_once('/') else {
        return false;
    };
    let Some(registrant) = prefix.strip_prefix("10.") else {
        return false;
    };
    let reg_valid = registrant.len() >= 4
        && registrant.len() <= 20
        && registrant.bytes().all(|b| b.is_ascii_digit() || b == b'.')
        && registrant.split('.').next().is_some_and(|first| first.len() >= 4 && first.len() <= 9);
    if !reg_valid || suffix.is_empty() {
        return false;
    }
    !candidate.contains("..")
        && !candidate.contains("://")
        && !candidate.chars().any(|c| c.is_whitespace() || c.is_control())
}

/// Normalizes an ISBN-10 or ISBN-13 string by stripping prefixes, hyphens, and spaces.
#[must_use]
pub fn normalize_isbn(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let stripped = strip_prefix_ignore_ascii_case(trimmed, "isbn-13:")
        .or_else(|| strip_prefix_ignore_ascii_case(trimmed, "isbn-10:"))
        .or_else(|| strip_prefix_ignore_ascii_case(trimmed, "isbn:"))
        .or_else(|| strip_prefix_ignore_ascii_case(trimmed, "isbn "))
        .unwrap_or(trimmed);
    let compact: String = stripped
        .chars()
        .filter(|c| !matches!(c, '-' | ' ' | '\t' | '\u{2010}' | '\u{2013}'))
        .map(|c| c.to_ascii_uppercase())
        .collect();
    let is_isbn13 = compact.len() == 13
        && (compact.starts_with("978") || compact.starts_with("979"))
        && compact.bytes().all(|b| b.is_ascii_digit());
    let is_isbn10 = compact.len() == 10
        && compact.as_bytes()[..9].iter().all(u8::is_ascii_digit)
        && matches!(compact.as_bytes()[9], b'0'..=b'9' | b'X');
    (is_isbn13 || is_isbn10).then_some(compact)
}

/// Percent-encodes a normalized DOI for safe inclusion in a `https://doi.org/{encoded}` or
/// Crossref/DataCite REST URL path while preserving `/` separators and unreserved RFC 3986 chars.
#[must_use]
pub fn encode_doi_for_url_path(doi: &str) -> Option<String> {
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

/// Scans free text (e.g. PDF front matter or HTML metadata) for all distinct valid DOIs in order
/// of appearance.
#[must_use]
pub fn extract_all_dois_from_text(text: &str) -> Vec<String> {
    let bytes = text.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i + 7 <= bytes.len() {
        if bytes[i] == b'1'
            && bytes[i + 1] == b'0'
            && bytes[i + 2] == b'.'
            && (i == 0 || !bytes[i - 1].is_ascii_alphanumeric())
            && text.is_char_boundary(i)
        {
            let start = i;
            let mut end = i + 3;
            for (rel, ch) in text[i + 3..].char_indices() {
                if ch.is_whitespace() || matches!(ch, '<' | '>' | '"' | '\'' | ';' | ',') {
                    break;
                }
                end = i + 3 + rel + ch.len_utf8();
            }
            if let Some(slice) = text.get(start..end)
                && let Some(doi) = normalize_doi(slice)
            {
                if !out.contains(&doi) {
                    out.push(doi);
                }
                i = end;
            } else {
                i += 3;
            }
        } else {
            i += 1;
        }
    }
    out
}

/// Scans free text (e.g. PDF page 1 or HTML metadata) for the first valid DOI.
#[must_use]
pub fn extract_doi_from_text(text: &str) -> Option<String> {
    extract_all_dois_from_text(text).into_iter().next()
}

/// Extracts an arXiv identifier from an arXiv-issued `DataCite` DOI (`10.48550/arXiv.<id>`).
#[must_use]
pub fn extract_arxiv_id_from_doi(doi: &str) -> Option<String> {
    let norm = normalize_doi(doi)?;
    let suffix = norm.strip_prefix("10.48550/arxiv.")?;
    (looks_like_arxiv_token(suffix)).then(|| extract_arxiv_id(suffix)).flatten()
}

/// Truncates extracted document text before a standalone `References` / `Bibliography` heading so
/// identifier scanning across front pages never picks up DOIs or arXiv IDs of cited works.
#[must_use]
pub fn strip_references_section(text: &str) -> &str {
    let mut offset = 0usize;
    for line in text.split_inclusive('\n') {
        let trimmed = strip_section_number_prefix(line.trim());
        let lower = trimmed.to_ascii_lowercase();
        if matches!(
            lower.as_str(),
            "references"
                | "bibliography"
                | "works cited"
                | "literature cited"
                | "references and notes"
        ) {
            return &text[..offset];
        }
        offset += line.len();
    }
    text
}

fn strip_section_number_prefix(raw: &str) -> &str {
    let after_digits =
        raw.trim_start_matches(|c: char| c.is_ascii_digit() || matches!(c, '.' | ')' | ' '));
    // Also strip Roman numeral IEEE headings like `VI. REFERENCES` or `IV) References`.
    if let Some((prefix, rest)) =
        after_digits.split_once(|c: char| c.is_whitespace() || matches!(c, '.' | ')'))
        && !prefix.is_empty()
        && prefix.len() <= 6
        && prefix.bytes().all(|b| matches!(b.to_ascii_uppercase(), b'I' | b'V' | b'X'))
    {
        let stripped = rest.trim_start_matches(['.', ')', ' ']).trim();
        if !stripped.is_empty() {
            return stripped;
        }
    }
    after_digits.trim()
}

/// Scans free text (e.g. PDF page 1–2 margin stamps) for all distinct embedded `arXiv:<id>` or
/// arXiv URLs in true left-to-right order of appearance.
#[must_use]
pub fn extract_all_arxiv_ids_from_text(text: &str) -> Vec<String> {
    const NEEDLES: [&str; 3] = ["arxiv:", "arxiv.org/abs/", "arxiv.org/pdf/"];
    let lower = text.to_ascii_lowercase();
    let mut matches: Vec<(usize, String)> = Vec::new();
    for needle in NEEDLES {
        let mut search_start = 0;
        while let Some(rel) = lower[search_start..].find(needle) {
            let match_pos = search_start + rel;
            let abs = match_pos + needle.len();
            let Some(rest) = text.get(abs..).map(str::trim_start) else {
                break;
            };
            let token_end = rest
                .find(|c: char| {
                    c.is_whitespace()
                        || matches!(c, '[' | ']' | '(' | ')' | '<' | '>' | ',' | ';' | '"' | '\'')
                })
                .unwrap_or(rest.len());
            let raw_token = rest[..token_end].trim_end_matches(['.', ':', '"', '\'']);
            let token = strip_ascii_pdf_suffix(raw_token);
            if looks_like_arxiv_token(token)
                && let Some(id) = extract_arxiv_id(token)
            {
                matches.push((match_pos, id));
            }
            search_start = abs;
        }
    }
    matches.sort_by_key(|(pos, _)| *pos);
    let mut out = Vec::new();
    for (_, id) in matches {
        if !out.contains(&id) {
            out.push(id);
        }
    }
    out
}

/// Scans free text (e.g. PDF page 1 margin stamp) for an embedded `arXiv:<id>` or arXiv URL.
#[must_use]
pub fn extract_arxiv_id_from_text(text: &str) -> Option<String> {
    extract_all_arxiv_ids_from_text(text).into_iter().next()
}

fn strip_ascii_pdf_suffix(token: &str) -> &str {
    if token.len() > 4 && token.as_bytes()[token.len() - 4..].eq_ignore_ascii_case(b".pdf") {
        &token[..token.len() - 4]
    } else {
        token
    }
}

fn strip_optional_arxiv_version(rest: &str) -> Option<&str> {
    match rest.split_once(['v', 'V']) {
        Some((num_part, ver)) => {
            if (1..=3).contains(&ver.len()) && ver.bytes().all(|b| b.is_ascii_digit()) {
                Some(num_part)
            } else {
                None
            }
        }
        None => Some(rest),
    }
}

fn looks_like_arxiv_token(token: &str) -> bool {
    let clean = strip_ascii_pdf_suffix(token);
    // Modern form: YYMM.NNNNN[vN]
    if let Some((yymm, rest)) = clean.split_once('.')
        && yymm.len() == 4
        && yymm.bytes().all(|b| b.is_ascii_digit())
        && let Some(num_part) = strip_optional_arxiv_version(rest)
        && (4..=5).contains(&num_part.len())
        && num_part.bytes().all(|b| b.is_ascii_digit())
    {
        return true;
    }
    // Legacy form: archive/YYMMNNN[vN]
    if let Some((arch, rest)) = clean.split_once('/')
        && !arch.is_empty()
        && arch.len() <= 24
        && arch.bytes().all(|b| b.is_ascii_alphabetic() || b == b'-' || b == b'.')
        && let Some(num_part) = strip_optional_arxiv_version(rest)
        && num_part.len() == 7
        && num_part.bytes().all(|b| b.is_ascii_digit())
    {
        return true;
    }
    false
}

/// Returns `true` when `title` is a URL, raw `.pdf` filename, or generated placeholder title
/// rather than a real human-readable work title (so auto-discovery and CRDT sync may upgrade it).
#[must_use]
pub fn is_placeholder_entry_title(title: &str, url: Option<&str>) -> bool {
    let trimmed = title.trim();
    if trimmed.is_empty() {
        return true;
    }
    if url.is_some_and(|u| {
        let cu = u.trim();
        !cu.is_empty() && trimmed.eq_ignore_ascii_case(cu)
    }) {
        return true;
    }
    let lower = trimmed.to_ascii_lowercase();
    if matches!(
        lower.as_str(),
        "untitled" | "shared pdf" | "local pdf" | "local pdf — pdf" | "document" | "document.pdf"
    ) || lower.ends_with(" — pdf")
        || lower.ends_with(" — saved entry")
        || lower.ends_with(" - pdf")
    {
        return true;
    }
    if let Some(after_arxiv) = strip_prefix_ignore_ascii_case(trimmed, "arxiv:")
        && looks_like_arxiv_token(after_arxiv.trim())
    {
        return true;
    }
    let has_pdf_ext = std::path::Path::new(trimmed)
        .extension()
        .is_some_and(|ext| ext.eq_ignore_ascii_case("pdf"));
    if has_pdf_ext && trimmed.len() > 4 {
        if !trimmed.chars().any(char::is_whitespace) {
            return true;
        }
        if let Some(u) = url
            && url_last_path_segment_matches(u, trimmed)
        {
            return true;
        }
    }
    // Also detect stripped filename matching the URL's `.pdf` basename (e.g. `subnlogn` for `.../subnlogn.pdf`)
    if !trimmed.chars().any(char::is_whitespace)
        && let Some(u) = url
        && let Some(seg) = extract_decoded_url_last_segment(u)
        && std::path::Path::new(&seg).extension().is_some_and(|ext| ext.eq_ignore_ascii_case("pdf"))
    {
        let stem = &seg[..seg.len() - 4];
        if !stem.is_empty() && trimmed.eq_ignore_ascii_case(stem) {
            return true;
        }
    }
    false
}

fn extract_decoded_url_last_segment(raw_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_url.trim()).ok()?;
    let seg = parsed.path_segments()?.rfind(|s| !s.is_empty())?;
    let decoded = decode_percent_utf8(seg).trim().to_owned();
    (!decoded.is_empty()).then_some(decoded)
}

fn url_last_path_segment_matches(raw_url: &str, candidate: &str) -> bool {
    extract_decoded_url_last_segment(raw_url)
        .is_some_and(|seg| seg.eq_ignore_ascii_case(candidate.trim()))
}

/// Parses a PDF Info date (`D:YYYYMMDDHHmmSS+HH'mm'` or `YYYYMMDD`) into a UTC timestamp.
#[must_use]
pub fn parse_pdf_date(raw: &str) -> Option<DateTime<Utc>> {
    let trimmed = raw.trim();
    let cleaned =
        trimmed.strip_prefix("D:").or_else(|| trimmed.strip_prefix("d:")).unwrap_or(trimmed);
    let digits: String = cleaned.chars().take_while(char::is_ascii_digit).collect();
    if digits.len() < 4 {
        return None;
    }
    let year: i32 = digits[0..4].parse().ok()?;
    if !(1900..=2100).contains(&year) {
        return None;
    }
    let month: u32 = digits.get(4..6).and_then(|s| s.parse().ok()).unwrap_or(1).clamp(1, 12);
    let day: u32 = digits.get(6..8).and_then(|s| s.parse().ok()).unwrap_or(1).clamp(1, 31);
    let hour: u32 = digits.get(8..10).and_then(|s| s.parse().ok()).unwrap_or(12).min(23);
    let min: u32 = digits.get(10..12).and_then(|s| s.parse().ok()).unwrap_or(0).min(59);
    let sec: u32 = digits.get(12..14).and_then(|s| s.parse().ok()).unwrap_or(0).min(59);
    let date = NaiveDate::from_ymd_opt(year, month, day)
        .or_else(|| NaiveDate::from_ymd_opt(year, month, 1))?;
    let naive = date.and_hms_opt(hour, min, sec)?;
    // Apply PDF timezone offset if present (`+HH'mm'`, `-HH'mm'`, `Z`)
    let rest = &cleaned[digits.len().min(cleaned.len())..];
    if let Some(sign) = rest.as_bytes().first().copied().filter(|b| *b == b'+' || *b == b'-') {
        let tz_digits: String = rest[1..].chars().filter(char::is_ascii_digit).take(4).collect();
        if tz_digits.len() >= 2
            && let Ok(tz_h) = tz_digits[0..2].parse::<i32>()
        {
            let tz_m = tz_digits.get(2..4).and_then(|s| s.parse::<i32>().ok()).unwrap_or(0);
            let offset_secs =
                (tz_h.min(23) * 3600 + tz_m.min(59) * 60) * if sign == b'-' { -1 } else { 1 };
            if let Some(fixed) = chrono::FixedOffset::east_opt(offset_secs)
                && let Some(dt) = fixed.from_local_datetime(&naive).single()
            {
                return Some(dt.with_timezone(&Utc));
            }
        }
    }
    Some(naive.and_utc())
}

/// Parses a publication date string (ISO 8601, RFC 3339, PDF date, `20th October 2026`,
/// `October 20, 2026`, `Oct 2026`, `2026`) into a harmonized `(published_at, release_year)` pair.
#[must_use]
pub fn parse_publication_date_str(raw: &str) -> Option<ParsedPublicationDate> {
    let trimmed = raw.trim().trim_matches(|c: char| matches!(c, '(' | ')' | '[' | ']' | '.' | ','));
    if trimmed.is_empty() || trimmed.len() > 80 {
        return None;
    }
    // 1. Full RFC 3339 timestamp
    if let Ok(dt) = DateTime::parse_from_rfc3339(trimmed) {
        let utc = dt.with_timezone(&Utc);
        return is_plausible_year(utc.year()).then_some(ParsedPublicationDate {
            published_at: Some(utc),
            release_year: Some(utc.year()),
        });
    }
    // 2. RFC 2822
    if let Ok(dt) = DateTime::parse_from_rfc2822(trimmed) {
        let utc = dt.with_timezone(&Utc);
        return is_plausible_year(utc.year()).then_some(ParsedPublicationDate {
            published_at: Some(utc),
            release_year: Some(utc.year()),
        });
    }
    // 3. PDF date `D:YYYYMMDD...`
    if trimmed.starts_with("D:") || trimmed.starts_with("d:") {
        let utc = parse_pdf_date(trimmed)?;
        return Some(ParsedPublicationDate {
            published_at: Some(utc),
            release_year: Some(utc.year()),
        });
    }
    // 4. ISO / slash date (`YYYY-MM-DD`, `YYYY/MM/DD`, `YYYY-MM`)
    if let Some(iso) = parse_numeric_ymd_date(trimmed) {
        return Some(iso);
    }
    // 5. Bare 4-digit year (`2026`)
    if trimmed.len() == 4
        && trimmed.bytes().all(|b| b.is_ascii_digit())
        && let Ok(yr) = trimmed.parse::<i32>()
        && is_plausible_year(yr)
    {
        return Some(ParsedPublicationDate { published_at: None, release_year: Some(yr) });
    }
    // 6. Natural-language date (`20th October 2026`, `October 20, 2026`, `Oct 2026`)
    parse_natural_month_date(trimmed)
}

const fn is_plausible_year(year: i32) -> bool {
    year >= 1450 && year <= 2100
}

fn parse_numeric_ymd_date(raw: &str) -> Option<ParsedPublicationDate> {
    // Take leading date portion before `T` or space if present (e.g. `2026-10-20 14:30:00`)
    let date_part = raw.split(['T', ' ']).next().unwrap_or(raw).trim();
    let sep = if date_part.contains('-') {
        '-'
    } else if date_part.contains('/') {
        '/'
    } else {
        return None;
    };
    let parts: Vec<&str> = date_part.split(sep).collect();
    if parts.len() == 3
        && parts[0].len() == 4
        && let (Ok(y), Ok(m), Ok(d)) =
            (parts[0].parse::<i32>(), parts[1].parse::<u32>(), parts[2].parse::<u32>())
        && is_plausible_year(y)
        && let Some(nd) = NaiveDate::from_ymd_opt(y, m, d)
        && let Some(dt) = nd.and_hms_opt(12, 0, 0)
    {
        return Some(ParsedPublicationDate {
            published_at: Some(dt.and_utc()),
            release_year: Some(y),
        });
    }
    if parts.len() == 2
        && parts[0].len() == 4
        && let (Ok(y), Ok(m)) = (parts[0].parse::<i32>(), parts[1].parse::<u32>())
        && is_plausible_year(y)
        && let Some(nd) = NaiveDate::from_ymd_opt(y, m, 1)
        && let Some(dt) = nd.and_hms_opt(12, 0, 0)
    {
        return Some(ParsedPublicationDate {
            published_at: Some(dt.and_utc()),
            release_year: Some(y),
        });
    }
    None
}

fn parse_natural_month_date(raw: &str) -> Option<ParsedPublicationDate> {
    // Strip optional leading labels like "Date:", "Published:", "Updated:", "v1:"
    let stripped = strip_date_label_prefix(raw);
    let tokens: Vec<String> = stripped
        .split(|c: char| c.is_whitespace() || matches!(c, ',' | ';'))
        .map(|t| t.trim_matches('.'))
        .filter(|t| !t.is_empty())
        .map(ToOwned::to_owned)
        .collect();
    if tokens.is_empty() || tokens.len() > 5 {
        return None;
    }

    let mut month: Option<u32> = None;
    let mut day: Option<u32> = None;
    let mut year: Option<i32> = None;

    for tok in &tokens {
        if month.is_none()
            && let Some(m) = parse_month_name(tok)
        {
            month = Some(m);
            continue;
        }
        if year.is_none()
            && tok.len() == 4
            && tok.bytes().all(|b| b.is_ascii_digit())
            && let Ok(y) = tok.parse::<i32>()
            && is_plausible_year(y)
        {
            year = Some(y);
            continue;
        }
        if day.is_none()
            && let Some(d) = parse_ordinal_day(tok)
        {
            day = Some(d);
            continue;
        }
        // Reject stray non-date words (except weekday names or "of")
        if !is_ignorable_date_token(tok) {
            return None;
        }
    }

    let y = year?;
    let m = month?;
    let d = day.unwrap_or(1);
    let nd = NaiveDate::from_ymd_opt(y, m, d)?;
    let dt = nd.and_hms_opt(12, 0, 0)?.and_utc();
    Some(ParsedPublicationDate { published_at: Some(dt), release_year: Some(y) })
}

fn strip_date_label_prefix(raw: &str) -> &str {
    let trimmed = raw.trim();
    for prefix in
        ["date:", "published:", "published on", "posted:", "updated:", "last revised:", "revised:"]
    {
        if let Some(rest) = strip_prefix_ignore_ascii_case(trimmed, prefix) {
            return rest.trim();
        }
    }
    trimmed
}

fn parse_ordinal_day(tok: &str) -> Option<u32> {
    let lower = tok.to_ascii_lowercase();
    let num_str = lower
        .strip_suffix("st")
        .or_else(|| lower.strip_suffix("nd"))
        .or_else(|| lower.strip_suffix("rd"))
        .or_else(|| lower.strip_suffix("th"))
        .unwrap_or(&lower);
    if num_str.is_empty() || num_str.len() > 2 || !num_str.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let d: u32 = num_str.parse().ok()?;
    (1..=31).contains(&d).then_some(d)
}

fn parse_month_name(tok: &str) -> Option<u32> {
    match tok.to_ascii_lowercase().as_str() {
        "jan" | "january" => Some(1),
        "feb" | "february" => Some(2),
        "mar" | "march" => Some(3),
        "apr" | "april" => Some(4),
        "may" => Some(5),
        "jun" | "june" => Some(6),
        "jul" | "july" => Some(7),
        "aug" | "august" => Some(8),
        "sep" | "sept" | "september" => Some(9),
        "oct" | "october" => Some(10),
        "nov" | "november" => Some(11),
        "dec" | "december" => Some(12),
        _ => None,
    }
}

fn is_ignorable_date_token(tok: &str) -> bool {
    matches!(
        tok.to_ascii_lowercase().as_str(),
        "of" | "mon"
            | "monday"
            | "tue"
            | "tues"
            | "tuesday"
            | "wed"
            | "wednesday"
            | "thu"
            | "thur"
            | "thurs"
            | "thursday"
            | "fri"
            | "friday"
            | "sat"
            | "saturday"
            | "sun"
            | "sunday"
    )
}

/// Parsed Crossref work record together with its Crossref `type` (e.g. `journal-article`,
/// `proceedings-article`, `posted-content`, `proceedings`) for verification and disambiguation.
#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CrossrefWorkCandidate {
    pub metadata: ExtractedDocumentMetadata,
    pub work_type: Option<String>,
}

impl CrossrefWorkCandidate {
    /// Returns `true` when this Crossref record represents a container/wrapper (such as an entire
    /// conference proceedings volume, journal issue, or dataset) rather than an individual paper.
    #[must_use]
    pub fn is_container_or_auxiliary_type(&self) -> bool {
        self.work_type.as_deref().is_some_and(|t| {
            matches!(
                t.to_ascii_lowercase().as_str(),
                "proceedings"
                    | "proceedings-series"
                    | "report-series"
                    | "journal"
                    | "journal-issue"
                    | "journal-volume"
                    | "book-series"
                    | "book-set"
                    | "edited-book"
                    | "dataset"
                    | "peer-review"
                    | "component"
                    | "grant"
            )
        })
    }

    /// Returns `true` when this record is a preprint / posted-content DOI (including arXiv DOIs).
    #[must_use]
    pub fn is_preprint(&self) -> bool {
        self.work_type.as_deref().is_some_and(|t| t.eq_ignore_ascii_case("posted-content"))
            || self.metadata.doi.as_deref().is_some_and(|d| d.starts_with("10.48550/arxiv."))
    }
}

/// Parses a Crossref REST API `/works/{doi}` JSON payload into [`ExtractedDocumentMetadata`].
#[must_use]
pub fn parse_crossref_work_json(raw_json: &str) -> Option<ExtractedDocumentMetadata> {
    parse_crossref_work_candidate_json(raw_json).map(|c| c.metadata)
}

/// Parses a Crossref REST API `/works/{doi}` JSON payload into a [`CrossrefWorkCandidate`]
/// preserving Crossref `type` metadata for verification.
#[must_use]
pub fn parse_crossref_work_candidate_json(raw_json: &str) -> Option<CrossrefWorkCandidate> {
    let root: serde_json::Value = serde_json::from_str(raw_json).ok()?;
    let msg = root.get("message").unwrap_or(&root);
    parse_crossref_work_value(msg)
}

/// Parses a Crossref REST API `/works?query...` search response into [`CrossrefWorkCandidate`]s.
#[must_use]
pub fn parse_crossref_search_works_json(raw_json: &str) -> Vec<CrossrefWorkCandidate> {
    let Ok(root) = serde_json::from_str::<serde_json::Value>(raw_json) else {
        return Vec::new();
    };
    let Some(items) =
        root.get("message").and_then(|m| m.get("items")).and_then(serde_json::Value::as_array)
    else {
        return Vec::new();
    };
    items.iter().filter_map(parse_crossref_work_value).collect()
}

/// Parses a `DataCite` REST API `/dois/{doi}` JSON payload into a [`CrossrefWorkCandidate`].
///
/// Allows `DataCite`-registered conference and repository DOIs (e.g. `LIPIcs`, Zenodo) to be
/// verified through the same matching pipeline as Crossref DOIs.
#[must_use]
pub fn parse_datacite_doi_json(raw_json: &str) -> Option<CrossrefWorkCandidate> {
    let root: serde_json::Value = serde_json::from_str(raw_json).ok()?;
    let attrs = root.get("data")?.get("attributes")?;
    if !attrs.is_object() {
        return None;
    }

    let doi = attrs.get("doi").and_then(serde_json::Value::as_str).and_then(normalize_doi);
    let arxiv_id = doi.as_deref().and_then(extract_arxiv_id_from_doi);
    let title = attrs
        .get("titles")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.first())
        .and_then(|t| t.get("title"))
        .and_then(serde_json::Value::as_str)
        .map(strip_jats_tags)
        .map(|s| collapse_whitespace(&s))
        .filter(|s| !s.is_empty());

    let authors: Vec<String> =
        attrs.get("creators").and_then(serde_json::Value::as_array).map_or_default(|arr| {
            arr.iter()
                .filter_map(|c| {
                    let given =
                        c.get("givenName").and_then(serde_json::Value::as_str).map(str::trim);
                    let family =
                        c.get("familyName").and_then(serde_json::Value::as_str).map(str::trim);
                    let name = c.get("name").and_then(serde_json::Value::as_str).map(str::trim);
                    match (given, family, name) {
                        (Some(g), Some(f), _) if !g.is_empty() && !f.is_empty() => {
                            Some(collapse_whitespace(&format!("{g} {f}")))
                        }
                        (_, Some(f), _) if !f.is_empty() => Some(collapse_whitespace(f)),
                        (_, _, Some(n)) if !n.is_empty() => Some(collapse_whitespace(n)),
                        _ => None,
                    }
                })
                .collect()
        });

    let release_year = attrs
        .get("publicationYear")
        .and_then(serde_json::Value::as_i64)
        .and_then(|y| i32::try_from(y).ok())
        .filter(|&y| is_plausible_year(y));

    let summary = attrs
        .get("descriptions")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| {
            arr.iter()
                .find(|d| {
                    d.get("descriptionType")
                        .and_then(serde_json::Value::as_str)
                        .is_some_and(|dt| dt.eq_ignore_ascii_case("abstract"))
                })
                .or_else(|| arr.first())
        })
        .and_then(|d| d.get("description"))
        .and_then(serde_json::Value::as_str)
        .map(strip_jats_tags)
        .map(|s| collapse_whitespace(&s))
        .filter(|s| !s.is_empty());

    let raw_type = attrs
        .get("types")
        .and_then(|t| t.get("resourceTypeGeneral").or_else(|| t.get("citeproc")))
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .map(str::to_ascii_lowercase);
    let work_type = match raw_type.as_deref() {
        Some("preprint" | "posted-content") => Some("posted-content".to_owned()),
        Some("dataset" | "collection") => Some("dataset".to_owned()),
        Some("conferenceproceeding") => Some("proceedings".to_owned()),
        Some("journalissue") => Some("journal-issue".to_owned()),
        Some("peerreview") => Some("peer-review".to_owned()),
        Some("bookseries") => Some("book-series".to_owned()),
        Some(other) if !other.is_empty() => Some(other.to_owned()),
        _ => None,
    };

    let metadata = ExtractedDocumentMetadata {
        title,
        authors,
        published_at: None,
        release_year,
        summary,
        doi,
        arxiv_id,
        isbn: None,
    };
    (!metadata.is_empty()).then_some(CrossrefWorkCandidate { metadata, work_type })
}

fn parse_crossref_work_value(msg: &serde_json::Value) -> Option<CrossrefWorkCandidate> {
    if !msg.is_object() {
        return None;
    }

    let title = msg
        .get("title")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.first())
        .and_then(serde_json::Value::as_str)
        .map(strip_jats_tags)
        .map(|s| collapse_whitespace(&s))
        .filter(|s| !s.is_empty());

    let subtitle = msg
        .get("subtitle")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.first())
        .and_then(serde_json::Value::as_str)
        .map(strip_jats_tags)
        .map(|s| collapse_whitespace(&s))
        .filter(|s| !s.is_empty());

    let full_title = match (title, subtitle) {
        (Some(t), Some(sub)) if !t.to_ascii_lowercase().contains(&sub.to_ascii_lowercase()) => {
            Some(format!("{t}: {sub}"))
        }
        (t, _) => t,
    };

    let authors: Vec<String> =
        msg.get("author").and_then(serde_json::Value::as_array).map_or_default(|arr| {
            arr.iter()
                .filter_map(|a| {
                    let given = a.get("given").and_then(serde_json::Value::as_str).map(str::trim);
                    let family = a.get("family").and_then(serde_json::Value::as_str).map(str::trim);
                    let literal = a.get("name").and_then(serde_json::Value::as_str).map(str::trim);
                    match (given, family, literal) {
                        (Some(g), Some(f), _) if !g.is_empty() && !f.is_empty() => {
                            Some(collapse_whitespace(&format!("{g} {f}")))
                        }
                        (_, Some(f), _) if !f.is_empty() => Some(collapse_whitespace(f)),
                        (_, _, Some(n)) if !n.is_empty() => Some(collapse_whitespace(n)),
                        _ => None,
                    }
                })
                .collect()
        });

    let parsed_date = ["published-print", "published-online", "published", "issued", "created"]
        .iter()
        .find_map(|key| parse_crossref_date_parts(msg.get(*key)?));

    let summary = msg
        .get("abstract")
        .and_then(serde_json::Value::as_str)
        .map(strip_jats_tags)
        .map(|s| collapse_whitespace(&s))
        .filter(|s| !s.is_empty());

    let doi = msg.get("DOI").and_then(serde_json::Value::as_str).and_then(normalize_doi);
    let arxiv_id = doi.as_deref().and_then(extract_arxiv_id_from_doi);
    let isbn = msg
        .get("ISBN")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.iter().find_map(|v| v.as_str().and_then(normalize_isbn)));
    let work_type = msg
        .get("type")
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_ascii_lowercase);

    let metadata = ExtractedDocumentMetadata {
        title: full_title,
        authors,
        published_at: parsed_date.and_then(|d| d.published_at),
        release_year: parsed_date.and_then(|d| d.release_year),
        summary,
        doi,
        arxiv_id,
        isbn,
    };
    (!metadata.is_empty()).then_some(CrossrefWorkCandidate { metadata, work_type })
}

/// Verifies whether a Crossref work record matches the expected paper title, authors, and year,
/// rejecting proceedings containers, cited works, and unrelated papers with similar keywords.
#[must_use]
pub fn crossref_work_matches_paper(
    expected_title: &str,
    expected_authors: &[String],
    expected_year: Option<i32>,
    candidate: &CrossrefWorkCandidate,
) -> bool {
    score_crossref_candidate(expected_title, expected_authors, expected_year, candidate).is_some()
}

/// Selects the highest-confidence verified Crossref match for a paper, preferring published
/// journal/conference DOIs over preprints when both match cleanly.
#[must_use]
pub fn select_verified_crossref_match<'a>(
    expected_title: &str,
    expected_authors: &[String],
    expected_year: Option<i32>,
    candidates: &'a [CrossrefWorkCandidate],
) -> Option<&'a CrossrefWorkCandidate> {
    candidates
        .iter()
        .filter_map(|cand| {
            let score =
                score_crossref_candidate(expected_title, expected_authors, expected_year, cand)?;
            Some((score, cand))
        })
        .max_by_key(|(score, _)| *score)
        .map(|(_, cand)| cand)
}

/// Returns `true` when an arXiv metadata record matches an expected paper's title and authors.
#[must_use]
pub fn document_metadata_matches_paper(
    expected_title: &str,
    expected_authors: &[String],
    expected_year: Option<i32>,
    candidate_title: Option<&str>,
    candidate_authors: &[String],
    candidate_year: Option<i32>,
) -> bool {
    let synth = CrossrefWorkCandidate {
        metadata: ExtractedDocumentMetadata {
            title: candidate_title.map(ToOwned::to_owned),
            authors: candidate_authors.to_vec(),
            release_year: candidate_year,
            ..ExtractedDocumentMetadata::default()
        },
        work_type: None,
    };
    score_crossref_candidate(expected_title, expected_authors, expected_year, &synth).is_some()
}

/// Composite match priority tuple:
/// `(not_preprint, exact_title, author_matches, f1_milli, well_formed_authors, year_closeness)`.
fn score_crossref_candidate(
    expected_title: &str,
    expected_authors: &[String],
    expected_year: Option<i32>,
    candidate: &CrossrefWorkCandidate,
) -> Option<(u8, u8, usize, u32, u8, i32)> {
    if candidate.is_container_or_auxiliary_type() {
        return None;
    }
    let cand_title = candidate.metadata.title.as_deref()?;
    if is_spurious_auxiliary_title(expected_title, cand_title) {
        return None;
    }
    let exp_tokens = normalize_title_tokens(expected_title);
    let cand_tokens = normalize_title_tokens(cand_title);
    if exp_tokens.is_empty() || cand_tokens.is_empty() {
        return None;
    }

    let exact_full = exp_tokens == cand_tokens;
    let main_title_match = title_main_part_matches(expected_title, cand_title);
    let f1 = token_f1(&exp_tokens, &cand_tokens);

    let author_overlap =
        count_author_surname_overlap(expected_authors, &candidate.metadata.authors);
    let both_have_authors = !expected_authors.is_empty() && !candidate.metadata.authors.is_empty();
    if both_have_authors && author_overlap == 0 {
        return None;
    }

    let year_delta = match (expected_year, candidate.metadata.release_year) {
        (Some(ey), Some(cy)) => Some((ey - cy).abs()),
        _ => None,
    };
    let max_year_delta = if exact_full || (main_title_match && author_overlap > 0) { 6 } else { 3 };
    if year_delta.is_some_and(|d| d > max_year_delta) {
        return None;
    }

    let title_ok = if both_have_authors {
        exact_full
            || (f1 >= 0.80)
            || (main_title_match && f1 >= 0.45)
            || (author_overlap >= 2 && f1 >= 0.72)
    } else {
        exact_full || f1 >= 0.92
    };
    if !title_ok {
        return None;
    }

    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let f1_milli = (f1 * 1000.0).round() as u32;
    let not_preprint = u8::from(!candidate.is_preprint());
    let exact_flag = u8::from(exact_full);
    // Prefer primary records with proper full author names (`"Longqi Yang"`) over secondary
    // digest/yearbook records with inverted single-letter family fields (`"Yang L"`).
    let well_formed_authors = u8::from(candidate.metadata.authors.iter().any(|a| {
        a.split_whitespace()
            .last()
            .is_some_and(|tok| tok.chars().filter(|c| c.is_alphabetic()).count() >= 2)
    }));
    let year_closeness = -year_delta.unwrap_or(2);
    Some((not_preprint, exact_flag, author_overlap, f1_milli, well_formed_authors, year_closeness))
}

/// Returns `true` when `title` begins with an auxiliary/erratum prefix (e.g. `Erratum`,
/// `Author Correction`, `Retraction`, `Front Matter`) indicating it is not the primary paper.
#[must_use]
pub fn is_auxiliary_document_title(title: &str) -> bool {
    const AUX_PREFIXES: [&str; 17] = [
        "erratum",
        "corrigendum",
        "correction to",
        "correction:",
        "author correction",
        "publisher correction",
        "retraction",
        "comment on ",
        "reply to ",
        "addendum",
        "editorial",
        "preface",
        "foreword",
        "table of contents",
        "front matter",
        "back matter",
        "book review",
    ];
    let lower = title.trim().to_ascii_lowercase();
    AUX_PREFIXES.iter().any(|prefix| lower.starts_with(prefix))
}

fn is_spurious_auxiliary_title(expected_title: &str, candidate_title: &str) -> bool {
    is_auxiliary_document_title(candidate_title) && !is_auxiliary_document_title(expected_title)
}

const fn is_combining_mark(ch: char) -> bool {
    matches!(
        ch,
        '\u{0300}'..='\u{036F}'
            | '\u{1AB0}'..='\u{1AFF}'
            | '\u{1DC0}'..='\u{1DFF}'
            | '\u{20D0}'..='\u{20FF}'
            | '\u{FE20}'..='\u{FE2F}'
    )
}

const fn fold_latin_char(ch: char) -> &'static str {
    match ch {
        'á' | 'à' | 'â' | 'ä' | 'ã' | 'å' | 'ā' | 'ă' | 'ą' => "a",
        'é' | 'è' | 'ê' | 'ë' | 'ē' | 'ĕ' | 'ė' | 'ę' | 'ě' => "e",
        'í' | 'ì' | 'î' | 'ï' | 'ĩ' | 'ī' | 'ĭ' | 'į' | 'ı' => "i",
        'ó' | 'ò' | 'ô' | 'ö' | 'õ' | 'ø' | 'ō' | 'ŏ' | 'ő' => "o",
        'ú' | 'ù' | 'û' | 'ü' | 'ũ' | 'ū' | 'ŭ' | 'ů' | 'ű' | 'ų' => "u",
        'ñ' | 'ń' | 'ņ' | 'ň' => "n",
        'ç' | 'ć' | 'ĉ' | 'ċ' | 'č' => "c",
        'ý' | 'ÿ' | 'ŷ' => "y",
        'ś' | 'ŝ' | 'ş' | 'š' => "s",
        'ź' | 'ż' | 'ž' => "z",
        'ł' | 'ĺ' | 'ļ' | 'ľ' => "l",
        'ř' | 'ŕ' | 'ŗ' => "r",
        'ď' | 'đ' | 'ð' => "d",
        'ť' | 'ţ' | 'ŧ' => "t",
        'ğ' | 'ĝ' | 'ġ' | 'ģ' => "g",
        'ß' => "ss",
        'æ' => "ae",
        'œ' => "oe",
        _ => "",
    }
}

fn normalize_title_tokens(raw: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut cur = String::new();
    for ch in raw.chars() {
        if is_combining_mark(ch) {
            // Strip NFD combining diacritics without splitting tokens (`Bo\u{0308}hme` -> `bohme`)
        } else if ch.is_alphanumeric() {
            for lc in ch.to_lowercase() {
                let folded = fold_latin_char(lc);
                if folded.is_empty() {
                    cur.push(lc);
                } else {
                    cur.push_str(folded);
                }
            }
        } else if matches!(ch, '\'' | '\u{2019}' | '\u{2018}') {
            // Join contractions (`don't` -> `dont`, `developer's` -> `developers`)
        } else if !cur.is_empty() {
            tokens.push(std::mem::take(&mut cur));
        }
    }
    if !cur.is_empty() {
        tokens.push(cur);
    }
    tokens
}

fn title_main_part_matches(a: &str, b: &str) -> bool {
    let main_a = a.split([':', '—', '–']).next().unwrap_or(a);
    let main_b = b.split([':', '—', '–']).next().unwrap_or(b);
    let ta = normalize_title_tokens(main_a);
    let tb = normalize_title_tokens(main_b);
    ta.len() >= 3 && ta == tb
}

fn token_f1(a: &[String], b: &[String]) -> f64 {
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let set_a: std::collections::HashSet<&str> = a.iter().map(String::as_str).collect();
    let set_b: std::collections::HashSet<&str> = b.iter().map(String::as_str).collect();
    let inter = set_a.intersection(&set_b).count();
    if inter == 0 {
        return 0.0;
    }
    #[allow(clippy::cast_precision_loss)]
    let f = (2 * inter) as f64 / (set_a.len() + set_b.len()) as f64;
    f
}

fn count_author_surname_overlap(expected: &[String], candidate: &[String]) -> usize {
    let exp_surnames: std::collections::HashSet<String> =
        expected.iter().filter_map(|n| extract_author_surname(n)).collect();
    if exp_surnames.is_empty() {
        return 0;
    }
    let cand_surnames: std::collections::HashSet<String> =
        candidate.iter().filter_map(|n| extract_author_surname(n)).collect();
    exp_surnames.intersection(&cand_surnames).count()
}

fn is_name_suffix_or_etal(tok: &str) -> bool {
    matches!(tok, "jr" | "sr" | "ii" | "iii" | "iv" | "phd" | "md" | "et" | "al")
}

fn extract_author_surname(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    // Strip trailing `et al.` / `et al` so `"Ray et al."` resolves to `"ray"`.
    let cleaned = trimmed
        .strip_suffix("et al.")
        .or_else(|| trimmed.strip_suffix("et al"))
        .or_else(|| {
            let lower = trimmed.to_ascii_lowercase();
            if lower.ends_with(" et al.") {
                trimmed.get(..trimmed.len() - " et al.".len())
            } else if lower.ends_with(" et al") {
                trimmed.get(..trimmed.len() - " et al".len())
            } else {
                None
            }
        })
        .unwrap_or(trimmed)
        .trim()
        .trim_end_matches(',');

    // Handle `"Last, First"` format (e.g. `"Vaswani, Ashish"`, `"van der Hoeven, Joris"`),
    // while avoiding `"First Last, Jr."`.
    if let Some((before, after)) = cleaned.split_once(',') {
        let before_tokens = normalize_title_tokens(before);
        let after_tokens = normalize_title_tokens(after);
        let after_is_suffix =
            !after_tokens.is_empty() && after_tokens.iter().all(|t| is_name_suffix_or_etal(t));
        if !before_tokens.is_empty() && before_tokens.len() <= 3 && !after_is_suffix {
            return before_tokens
                .into_iter()
                .rev()
                .find(|t| !is_name_suffix_or_etal(t) && t.len() >= 2);
        }
    }

    let tokens = normalize_title_tokens(cleaned);
    tokens.into_iter().rev().find(|t| !is_name_suffix_or_etal(t) && t.len() >= 2)
}

fn parse_crossref_date_parts(val: &serde_json::Value) -> Option<ParsedPublicationDate> {
    let parts = val.get("date-parts")?.as_array()?.first()?.as_array()?;
    let year = i32::try_from(parts.first()?.as_i64()?).ok()?;
    if !is_plausible_year(year) {
        return None;
    }
    let month =
        parts.get(1).and_then(serde_json::Value::as_u64).and_then(|m| u32::try_from(m).ok());
    let day = parts.get(2).and_then(serde_json::Value::as_u64).and_then(|d| u32::try_from(d).ok());
    let published_at = match (month, day) {
        (Some(m), Some(d)) => NaiveDate::from_ymd_opt(year, m, d)
            .and_then(|nd| nd.and_hms_opt(12, 0, 0))
            .map(|dt| dt.and_utc()),
        (Some(m), None) => NaiveDate::from_ymd_opt(year, m, 1)
            .and_then(|nd| nd.and_hms_opt(12, 0, 0))
            .map(|dt| dt.and_utc()),
        _ => None,
    };
    Some(ParsedPublicationDate { published_at, release_year: Some(year) })
}

fn strip_jats_tags(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut in_tag = false;
    for ch in raw.chars() {
        match ch {
            '<' => {
                in_tag = true;
                if !out.ends_with(' ') {
                    out.push(' ');
                }
            }
            '>' => in_tag = false,
            _ if !in_tag => out.push(ch),
            _ => {}
        }
    }
    out
}

pub(crate) fn collapse_whitespace(raw: &str) -> String {
    raw.split_whitespace().collect::<Vec<_>>().join(" ")
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_normalize_doi_and_isbn() {
        assert_eq!(
            normalize_doi("https://doi.org/10.1145/3453483.3454032.").as_deref(),
            Some("10.1145/3453483.3454032")
        );
        assert_eq!(normalize_doi("DOI: 10.1000/XYZ-123").as_deref(), Some("10.1000/xyz-123"));
        assert!(normalize_doi("not-a-doi").is_none());

        assert_eq!(normalize_isbn("ISBN 978-0-14-312755-0").as_deref(), Some("9780143127550"));
        assert_eq!(normalize_isbn("0-201-63361-2").as_deref(), Some("0201633612"));
        assert_eq!(normalize_isbn("0-8044-2957-x").as_deref(), Some("080442957X"));
        assert!(normalize_isbn("12345").is_none());
    }

    #[test]
    fn test_extract_doi_and_arxiv_from_text() {
        let text = "Published in STOC 2026. https://doi.org/10.1145/3564246.3585200, see also arXiv:2610.01234v1 [cs.DS].";
        assert_eq!(extract_doi_from_text(text).as_deref(), Some("10.1145/3564246.3585200"));
        assert_eq!(extract_arxiv_id_from_text(text).as_deref(), Some("2610.01234v1"));
    }

    #[test]
    fn test_placeholder_entry_title_detection() {
        let url = Some("https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf");
        assert!(is_placeholder_entry_title("subnlogn.pdf", url));
        assert!(is_placeholder_entry_title("subnlogn", url));
        assert!(is_placeholder_entry_title(
            "https://web.maths.unsw.edu.au/~davidharvey/research/subnlogn.pdf",
            url
        ));
        assert!(is_placeholder_entry_title("web.maths.unsw.edu.au — PDF", url));
        assert!(is_placeholder_entry_title("arXiv:2401.12345v2", None));
        assert!(!is_placeholder_entry_title(
            "On the recently proposed o(n log n) integer multiplication algorithm",
            url
        ));
    }

    #[test]
    fn test_parse_publication_date_str_variants() {
        let d1 = parse_publication_date_str("20th October 2026").unwrap();
        assert_eq!(d1.release_year, Some(2026));
        assert_eq!(d1.published_at.unwrap().format("%Y-%m-%d").to_string(), "2026-10-20");

        let d2 = parse_publication_date_str("October 20, 2026").unwrap();
        assert_eq!(d2.release_year, Some(2026));
        assert_eq!(d2.published_at.unwrap().format("%Y-%m-%d").to_string(), "2026-10-20");

        let d3 = parse_publication_date_str("2026-10-20").unwrap();
        assert_eq!(d3.release_year, Some(2026));
        assert_eq!(d3.published_at.unwrap().format("%Y-%m-%d").to_string(), "2026-10-20");

        let d4 = parse_publication_date_str("D:20261020135200+11'00'").unwrap();
        assert_eq!(d4.release_year, Some(2026));
        assert_eq!(d4.published_at.unwrap().format("%Y-%m-%d").to_string(), "2026-10-20");

        let d5 = parse_publication_date_str("1979").unwrap();
        assert_eq!(d5.release_year, Some(1979));
        assert!(d5.published_at.is_none());
    }

    #[test]
    fn test_parse_crossref_work_json() {
        let json = r#"{
            "status": "ok",
            "message": {
                "DOI": "10.1145/3453483.3454032",
                "title": ["Integer multiplication in time O(n log n)"],
                "author": [
                    {"given": "David", "family": "Harvey"},
                    {"given": "Joris", "family": "van der Hoeven"}
                ],
                "published-print": {"date-parts": [[2021, 3, 15]]},
                "abstract": "<jats:p>We show how to multiply two n-bit integers.</jats:p>"
            }
        }"#;
        let meta = parse_crossref_work_json(json).unwrap();
        assert_eq!(meta.title.as_deref(), Some("Integer multiplication in time O(n log n)"));
        assert_eq!(meta.authors, vec!["David Harvey", "Joris van der Hoeven"]);
        assert_eq!(meta.release_year, Some(2021));
        assert_eq!(meta.published_at.unwrap().format("%Y-%m-%d").to_string(), "2021-03-15");
        assert_eq!(meta.summary.as_deref(), Some("We show how to multiply two n-bit integers."));
        assert_eq!(meta.doi.as_deref(), Some("10.1145/3453483.3454032"));
    }

    #[test]
    fn test_plain_text_academic_paper_header_extraction() {
        let text = "\
On the recently proposed o(n log n)
integer multiplication algorithm
David Harvey, UNSW Sydney
20th October 2026
Abstract
I offer some thoughts about the recently proposed integer multiplication algorithm whose
complexity is claimed to be o(n log n) bit operations.

1 Executive summary
I have not checked most of the details.
";
        let meta = extract_metadata_from_plain_text(text);
        assert_eq!(
            meta.title.as_deref(),
            Some("On the recently proposed o(n log n) integer multiplication algorithm")
        );
        assert_eq!(meta.authors, vec!["David Harvey"]);
        assert_eq!(meta.release_year, Some(2026));
        assert_eq!(meta.published_at.unwrap().format("%Y-%m-%d").to_string(), "2026-10-20");
        assert!(meta.summary.as_deref().unwrap().starts_with("I offer some thoughts"));
    }

    #[test]
    fn test_utf8_char_boundary_safety_and_crossref_jats_title() {
        // Multi-byte UTF-8 prefixes must never panic on ASCII prefix slicing
        assert!(normalize_doi("a🎉").is_none());
        assert!(normalize_doi("doi:🎉").is_none());
        assert_eq!(split_author_names("🎉x"), Vec::<String>::new());
        assert_eq!(split_author_names("by 🎉 Alice Smith"), vec!["🎉 Alice Smith"]);
        let _ = extract_metadata_from_plain_text("a🎉\nAbstract🎉\n🎉x\nBody text line.");

        // Crossref titles containing JATS/HTML inline tags must be stripped cleanly
        let json = r#"{
            "message": {
                "DOI": "10.1000/jats-title",
                "title": ["Study of <italic>o(n log n)</italic> <sub>2</sub> bounds"],
                "published": {"date-parts": [[2025, 6, 1]]}
            }
        }"#;
        let meta = parse_crossref_work_json(json).unwrap();
        assert_eq!(meta.title.as_deref(), Some("Study of o(n log n) 2 bounds"));
        assert_eq!(meta.release_year, Some(2025));
    }

    #[test]
    fn test_extract_all_dois_arxiv_from_doi_and_strip_references() {
        let text = "\
A Large Scale Study of Programming Languages and Code Quality in Github
Proceedings DOI: 10.1145/2635868
Article DOI: https://doi.org/10.1145/2635868.2635922.
Preprint: 10.48550/arXiv.2407.12345 and arXiv:2407.12345v2 [cs.SE].

7. References
[1] Some cited work https://doi.org/10.1109/TSE.2010.999999 arXiv:1901.00001
";
        let front = strip_references_section(text);
        assert!(!front.contains("10.1109/TSE.2010.999999"));
        assert!(!front.contains("1901.00001"));

        let dois = extract_all_dois_from_text(front);
        assert_eq!(
            dois,
            vec!["10.1145/2635868", "10.1145/2635868.2635922", "10.48550/arxiv.2407.12345"]
        );
        assert_eq!(
            extract_arxiv_id_from_doi("10.48550/arXiv.2407.12345").as_deref(),
            Some("2407.12345")
        );
        assert!(extract_arxiv_id_from_doi("10.1145/2635868.2635922").is_none());
        assert_eq!(extract_all_arxiv_ids_from_text(front), vec!["2407.12345v2"]);
    }

    #[test]
    fn test_crossref_verification_rejects_proceedings_errata_and_prefers_published_doi() {
        let search_json = r#"{
            "status": "ok",
            "message": {
                "items": [
                    {
                        "type": "proceedings",
                        "DOI": "10.1145/2635868",
                        "title": ["Proceedings of the 22nd ACM SIGSOFT International Symposium on Foundations of Software Engineering"],
                        "issued": {"date-parts": [[2014]]}
                    },
                    {
                        "type": "journal-article",
                        "DOI": "10.1145/9999999.erratum",
                        "title": ["Erratum: A Large Scale Study of Programming Languages and Code Quality in Github"],
                        "author": [{"given": "Baishakhi", "family": "Ray"}],
                        "issued": {"date-parts": [[2015]]}
                    },
                    {
                        "type": "posted-content",
                        "DOI": "10.48550/arXiv.1408.0001",
                        "title": ["A Large Scale Study of Programming Languages and Code Quality in Github"],
                        "author": [{"given": "Baishakhi", "family": "Ray"}, {"given": "Daryl", "family": "Posnett"}],
                        "issued": {"date-parts": [[2014]]}
                    },
                    {
                        "type": "proceedings-article",
                        "DOI": "10.1145/2635868.2635922",
                        "title": ["A Large Scale Study of Programming Languages and Code Quality in Github"],
                        "author": [{"given": "Baishakhi", "family": "Ray"}, {"given": "Daryl", "family": "Posnett"}],
                        "issued": {"date-parts": [[2014, 11, 11]]}
                    }
                ]
            }
        }"#;

        let candidates = parse_crossref_search_works_json(search_json);
        assert_eq!(candidates.len(), 4);
        assert!(!crossref_work_matches_paper(
            "A Large Scale Study of Programming Languages and Code Quality in Github",
            &["Ray et al.".to_owned()],
            Some(2014),
            &candidates[0],
        ));
        assert!(!crossref_work_matches_paper(
            "A Large Scale Study of Programming Languages and Code Quality in Github",
            &["Ray et al.".to_owned()],
            Some(2014),
            &candidates[1],
        ));

        let best = select_verified_crossref_match(
            "A Large Scale Study of Programming Languages and Code Quality in Github",
            &["Ray, Baishakhi".to_owned(), "Posnett et al.".to_owned()],
            Some(2014),
            &candidates,
        )
        .unwrap();
        assert_eq!(best.metadata.doi.as_deref(), Some("10.1145/2635868.2635922"));
        assert!(!best.is_preprint());
    }

    #[test]
    fn test_crossref_verification_diacritics_and_author_corrections() {
        // Diacritics folding + subtitle handling
        let umlaut_cand = CrossrefWorkCandidate {
            metadata: ExtractedDocumentMetadata {
                title: Some(
                    "No Silver Bullet: Essence and Accidents of Software Engineering".to_owned(),
                ),
                authors: vec!["Dag Sjøberg".to_owned(), "Marcel Böhme".to_owned()],
                release_year: Some(2020),
                doi: Some("10.1109/tse.2020.12345".to_owned()),
                ..ExtractedDocumentMetadata::default()
            },
            work_type: Some("journal-article".to_owned()),
        };
        assert!(crossref_work_matches_paper(
            "No Silver Bullet",
            &["Sjoberg".to_owned(), "Bohme".to_owned()],
            Some(2020),
            &umlaut_cand,
        ));
        assert!(!crossref_work_matches_paper(
            "No Silver Bullet",
            &["Completely Unrelated Author".to_owned()],
            Some(2020),
            &umlaut_cand,
        ));

        // NFD decomposed combining diacritics (`Bo\u{0308}hme`) must match NFC (`Böhme`)
        assert!(crossref_work_matches_paper(
            "No Silver Bullet",
            &["Marcel Bo\u{0308}hme".to_owned()],
            Some(2020),
            &umlaut_cand,
        ));

        // Nature/Springer "Author Correction:" must be rejected
        let author_corr = CrossrefWorkCandidate {
            metadata: ExtractedDocumentMetadata {
                title: Some("Author Correction: No Silver Bullet".to_owned()),
                authors: vec!["Dag Sjøberg".to_owned()],
                release_year: Some(2020),
                doi: Some("10.1038/s41562-020-0001".to_owned()),
                ..ExtractedDocumentMetadata::default()
            },
            work_type: Some("journal-article".to_owned()),
        };
        assert!(!crossref_work_matches_paper(
            "No Silver Bullet",
            &["Sjoberg".to_owned()],
            Some(2020),
            &author_corr,
        ));

        // Primary journal article with full author names must beat secondary yearbook digest
        // with inverted single-letter family names (`"Yang L"`) even if digest year is closer.
        let yearbook_digest = CrossrefWorkCandidate {
            metadata: ExtractedDocumentMetadata {
                title: Some(
                    "The effects of remote work on collaboration among information workers"
                        .to_owned(),
                ),
                authors: vec!["Yang L".to_owned(), "Holtz D".to_owned()],
                release_year: Some(2022),
                doi: Some("10.1530/ey.19.15.13".to_owned()),
                ..ExtractedDocumentMetadata::default()
            },
            work_type: Some("journal-article".to_owned()),
        };
        let primary_nature = CrossrefWorkCandidate {
            metadata: ExtractedDocumentMetadata {
                title: Some(
                    "The effects of remote work on collaboration among information workers"
                        .to_owned(),
                ),
                authors: vec!["Longqi Yang".to_owned(), "David Holtz".to_owned()],
                release_year: Some(2021),
                doi: Some("10.1038/s41562-021-01196-4".to_owned()),
                ..ExtractedDocumentMetadata::default()
            },
            work_type: Some("journal-article".to_owned()),
        };
        let digest_candidates = [yearbook_digest, primary_nature];
        let chosen = select_verified_crossref_match(
            "The Effects of Remote Work on Collaboration Among Information Workers",
            &["Longqi Yang".to_owned(), "David Holtz".to_owned()],
            Some(2022),
            &digest_candidates,
        )
        .unwrap();
        assert_eq!(chosen.metadata.doi.as_deref(), Some("10.1038/s41562-021-01196-4"));
    }

    #[test]
    fn test_doi_url_encoding_nbsp_roman_refs_arxiv_order_and_datacite_json() {
        // SICI DOI with `<`, `>`, `#`, `?` must percent-encode cleanly while keeping `/`
        let sici = "10.1002/(SICI)1097-0258(19980815)17:15/16<1661::AID-SIM968>3.0.CO;2-2";
        let enc = encode_doi_for_url_path(sici).unwrap();
        assert!(!enc.contains('<') && !enc.contains('>'));
        assert!(enc.starts_with("10.1002/"));
        assert!(enc.contains("%3C1661") && enc.contains("%3E3.0.co"));

        // NBSP, semicolon-separated DOIs, and malformed `10.bad` adjacent to real DOI
        let messy = "10.1145/111.222\u{00A0}and 10.bad;10.1145/333.444,10.1109/tse.2020.555";
        assert_eq!(
            extract_all_dois_from_text(messy),
            vec!["10.1145/111.222", "10.1145/333.444", "10.1109/tse.2020.555"]
        );

        // IEEE Roman-numeral `VI. REFERENCES` truncation
        let ieee = "Intro text 10.1109/icse.2019.00001\nVI. REFERENCES\n[1] 10.1145/999.999\n";
        let front = strip_references_section(ieee);
        assert_eq!(extract_all_dois_from_text(front), vec!["10.1109/icse.2019.00001"]);

        // True left-to-right arXiv ordering across URL vs prefix forms + version validation + UTF-8 safety
        let arxiv_text = "See arXiv:preprint\u{2013}v1 and https://arxiv.org/abs/2101.00001.PDF and arXiv:2407.99999vXYZ and arXiv:2202.00002V3:";
        assert_eq!(extract_all_arxiv_ids_from_text(arxiv_text), vec!["2101.00001", "2202.00002V3"]);

        // DataCite JSON parsing (e.g. LIPIcs / Zenodo) and container rejection
        let dc_json = r#"{
            "data": {
                "attributes": {
                    "doi": "10.4230/LIPIcs.ECOOP.2021.12",
                    "titles": [{"title": "Empirical Type Soundness"}],
                    "creators": [{"givenName": "Ada", "familyName": "Lovelace"}],
                    "publicationYear": 2021,
                    "types": {"resourceTypeGeneral": "ConferencePaper"},
                    "descriptions": [{"descriptionType": "Abstract", "description": "Study of type soundness."}]
                }
            }
        }"#;
        let dc = parse_datacite_doi_json(dc_json).unwrap();
        assert_eq!(dc.metadata.doi.as_deref(), Some("10.4230/lipics.ecoop.2021.12"));
        assert_eq!(dc.metadata.title.as_deref(), Some("Empirical Type Soundness"));
        assert_eq!(dc.metadata.authors, vec!["Ada Lovelace"]);
        assert_eq!(dc.metadata.release_year, Some(2021));
        assert!(!dc.is_preprint());
        assert!(!dc.is_container_or_auxiliary_type());

        let dc_proc_json = r#"{
            "data": {
                "attributes": {
                    "doi": "10.4230/LIPIcs.ECOOP.2021",
                    "titles": [{"title": "35th European Conference on Object-Oriented Programming"}],
                    "publicationYear": 2021,
                    "types": {"resourceTypeGeneral": "ConferenceProceeding"}
                }
            }
        }"#;
        let dc_proc = parse_datacite_doi_json(dc_proc_json).unwrap();
        assert!(dc_proc.is_container_or_auxiliary_type());
    }
}
