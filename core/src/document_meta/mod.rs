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
    pdf::{LayoutLine, extract_pdf_metadata, extract_pdf_pages_lines},
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

/// Scans free text (e.g. PDF page 1 or HTML metadata) for the first valid DOI.
#[must_use]
pub fn extract_doi_from_text(text: &str) -> Option<String> {
    let bytes = text.as_bytes();
    let mut i = 0;
    while i + 7 <= bytes.len() {
        if bytes[i] == b'1'
            && bytes[i + 1] == b'0'
            && bytes[i + 2] == b'.'
            && (i == 0 || !bytes[i - 1].is_ascii_alphanumeric())
        {
            let start = i;
            let mut end = i + 3;
            while end < bytes.len()
                && !bytes[end].is_ascii_whitespace()
                && !matches!(bytes[end], b'<' | b'>' | b'"' | b'\'')
            {
                end += 1;
            }
            if let Some(slice) = text.get(start..end)
                && let Some(doi) = normalize_doi(slice)
            {
                return Some(doi);
            }
            i = end;
        } else {
            i += 1;
        }
    }
    None
}

/// Scans free text (e.g. PDF page 1 margin stamp) for an embedded `arXiv:<id>` or arXiv URL.
#[must_use]
pub fn extract_arxiv_id_from_text(text: &str) -> Option<String> {
    let lower = text.to_ascii_lowercase();
    for needle in ["arxiv:", "arxiv.org/abs/", "arxiv.org/pdf/"] {
        let mut search_start = 0;
        while let Some(rel) = lower[search_start..].find(needle) {
            let abs = search_start + rel + needle.len();
            let rest = text.get(abs..)?.trim_start();
            let token_end = rest
                .find(|c: char| {
                    c.is_whitespace() || matches!(c, '[' | ']' | '(' | ')' | '<' | '>' | ',' | ';')
                })
                .unwrap_or(rest.len());
            let token = rest[..token_end].trim_end_matches('.');
            if looks_like_arxiv_token(token)
                && let Some(id) = extract_arxiv_id(token)
            {
                return Some(id);
            }
            search_start = abs;
        }
    }
    None
}

fn looks_like_arxiv_token(token: &str) -> bool {
    let clean = token.strip_suffix(".pdf").unwrap_or(token);
    // Modern form: YYMM.NNNNN[vN]
    if let Some((yymm, rest)) = clean.split_once('.')
        && yymm.len() == 4
        && yymm.bytes().all(|b| b.is_ascii_digit())
    {
        let num_part = rest.split_once('v').map_or(rest, |(n, _)| n);
        if (4..=5).contains(&num_part.len()) && num_part.bytes().all(|b| b.is_ascii_digit()) {
            return true;
        }
    }
    // Legacy form: archive/YYMMNNN[vN]
    if let Some((arch, rest)) = clean.split_once('/')
        && !arch.is_empty()
        && arch.len() <= 24
        && arch.bytes().all(|b| b.is_ascii_alphabetic() || b == b'-' || b == b'.')
    {
        let num_part = rest.split_once('v').map_or(rest, |(n, _)| n);
        if num_part.len() == 7 && num_part.bytes().all(|b| b.is_ascii_digit()) {
            return true;
        }
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

/// Parses a Crossref REST API `/works/{doi}` JSON payload into [`ExtractedDocumentMetadata`].
#[must_use]
pub fn parse_crossref_work_json(raw_json: &str) -> Option<ExtractedDocumentMetadata> {
    let root: serde_json::Value = serde_json::from_str(raw_json).ok()?;
    let msg = root.get("message").unwrap_or(&root);
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
    let isbn = msg
        .get("ISBN")
        .and_then(serde_json::Value::as_array)
        .and_then(|arr| arr.iter().find_map(|v| v.as_str().and_then(normalize_isbn)));

    let out = ExtractedDocumentMetadata {
        title,
        authors,
        published_at: parsed_date.and_then(|d| d.published_at),
        release_year: parsed_date.and_then(|d| d.release_year),
        summary,
        doi,
        arxiv_id: None,
        isbn,
    };
    (!out.is_empty()).then_some(out)
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
}
