use std::collections::HashSet;

use chrono::{DateTime, Datelike, Utc};

use super::{
    ExtractedDocumentMetadata, LayoutLine, collapse_whitespace, extract_arxiv_id_from_text,
    extract_doi_from_text, parse_publication_date_str,
};

const MAX_ABSTRACT_CHARS: usize = 600;
const MAX_TITLE_CHARS: usize = 260;

const JUNK_TITLES: [&str; 12] = [
    "untitled",
    "document",
    "microsoft word",
    "latex",
    "slides",
    "presentation",
    "paper",
    "article",
    "manuscript",
    "draft",
    "noname",
    "main",
];

const JUNK_AUTHORS: [&str; 10] = [
    "admin",
    "administrator",
    "user",
    "owner",
    "root",
    "unknown",
    "author",
    "default",
    "latex",
    "tex",
];

const AFFILIATION_KEYWORDS: [&str; 32] = [
    "university",
    "univ.",
    "univ ",
    "unsw",
    "institute",
    "department",
    "dept.",
    "school of",
    "college",
    "faculty",
    "laboratory",
    "lab",
    "labs",
    "research",
    "center",
    "centre",
    "google",
    "deepmind",
    "microsoft",
    "meta",
    "openai",
    "anthropic",
    "nvidia",
    "ibm",
    "mit",
    "stanford",
    "berkeley",
    "harvard",
    "oxford",
    "cambridge",
    "cnrs",
    "inria",
];

/// Returns `true` when a PDF `/Title` metadata field looks like a software placeholder or raw filename.
#[must_use]
pub fn is_junk_pdf_title(raw: &str) -> bool {
    let trimmed = raw.trim();
    if trimmed.len() < 3 {
        return true;
    }
    let lower = trimmed.to_ascii_lowercase();
    if JUNK_TITLES.iter().any(|j| lower == *j) {
        return true;
    }
    if lower.starts_with("microsoft word -")
        || lower.starts_with("microsoft powerpoint -")
        || lower.starts_with("powerpoint presentation")
        || lower.starts_with("untitled")
        || lower.starts_with("latex ")
    {
        return true;
    }
    let has_file_ext =
        [".pdf", ".tex", ".dvi", ".ps", ".eps", ".doc", ".docx", ".ppt", ".pptx", ".rtf", ".odt"]
            .iter()
            .any(|ext| lower.ends_with(ext));
    if has_file_ext && !trimmed.contains(' ') {
        return true;
    }
    // Single-token snake_case/kebab-case filename slugs like `subnlogn` or `paper_v2_final`
    if !trimmed.contains(' ')
        && (trimmed.contains('_') || trimmed.len() <= 14)
        && trimmed.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
    {
        return true;
    }
    false
}

/// Returns `true` when a PDF `/Author` metadata field looks like an OS username or placeholder.
#[must_use]
pub fn is_junk_pdf_author(raw: &str) -> bool {
    let trimmed = raw.trim();
    if trimmed.len() < 2 {
        return true;
    }
    let lower = trimmed.to_ascii_lowercase();
    JUNK_AUTHORS.iter().any(|j| lower == *j)
}

/// Extracts title, authors, publication date, abstract, DOI, and arXiv ID from font-sized layout lines
/// taken from page 1 of a PDF.
#[must_use]
pub fn extract_metadata_from_layout_lines(lines: &[LayoutLine]) -> ExtractedDocumentMetadata {
    let cleaned_lines: Vec<LayoutLine> = lines
        .iter()
        .filter_map(|l| {
            let text = collapse_whitespace(&l.text);
            (!text.is_empty()).then_some(LayoutLine { text, font_size: l.font_size, y: l.y })
        })
        .collect();
    if cleaned_lines.is_empty() {
        return ExtractedDocumentMetadata::default();
    }

    let full_text = cleaned_lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join("\n");
    let doi = extract_doi_from_text(&full_text);
    let arxiv_id = extract_arxiv_id_from_text(&full_text);

    let has_font_sizes = cleaned_lines.iter().any(|l| l.font_size > 1.0);
    let (title, title_end_idx) = if has_font_sizes {
        extract_title_by_font_size(&cleaned_lines)
            .unwrap_or_else(|| extract_title_plain_lines(&cleaned_lines))
    } else {
        extract_title_plain_lines(&cleaned_lines)
    };

    let (authors, published_at, release_year, summary) =
        extract_header_after_title(&cleaned_lines, title_end_idx);

    ExtractedDocumentMetadata {
        title,
        authors,
        published_at,
        release_year: release_year.or_else(|| published_at.map(|dt| dt.year())),
        summary,
        doi,
        arxiv_id,
        isbn: None,
    }
}

/// Extracts bibliographic metadata from plain first-page text (e.g. `pdftotext` or Android `PdfRenderer`).
#[must_use]
pub fn extract_metadata_from_plain_text(page_text: &str) -> ExtractedDocumentMetadata {
    let lines: Vec<LayoutLine> = page_text
        .lines()
        .enumerate()
        .filter_map(|(idx, raw)| {
            let text = collapse_whitespace(raw);
            if text.is_empty() {
                None
            } else {
                #[allow(clippy::cast_precision_loss)]
                Some(LayoutLine { text, font_size: 0.0, y: -(idx as f32) })
            }
        })
        .collect();
    extract_metadata_from_layout_lines(&lines)
}

fn extract_title_by_font_size(lines: &[LayoutLine]) -> Option<(Option<String>, usize)> {
    // Estimate body font size as the median of lines with >= 25 chars (or all lines if short page)
    let mut body_sizes: Vec<f32> = lines
        .iter()
        .filter(|l| l.font_size > 1.0 && l.text.len() >= 25)
        .map(|l| l.font_size)
        .collect();
    if body_sizes.is_empty() {
        body_sizes = lines.iter().filter(|l| l.font_size > 1.0).map(|l| l.font_size).collect();
    }
    if body_sizes.is_empty() {
        return None;
    }
    body_sizes.sort_by(f32::total_cmp);
    let body_size = body_sizes[body_sizes.len() / 2];

    // Inspect top 18 lines (before Abstract/Section 1) for the largest title font
    let scan_end = lines.len().min(18);
    let mut max_title_size = 0.0_f32;
    let mut start_idx: Option<usize> = None;
    for (idx, line) in lines[..scan_end].iter().enumerate() {
        if is_abstract_heading(&line.text) || is_section_heading(&line.text) {
            break;
        }
        if is_ignorable_top_banner(&line.text) {
            continue;
        }
        if line.font_size > max_title_size + 0.4
            && line.font_size >= body_size * 1.15
            && line.text.chars().any(char::is_alphabetic)
        {
            max_title_size = line.font_size;
            start_idx = Some(idx);
        }
    }

    let first_idx = start_idx?;
    let mut parts = vec![lines[first_idx].text.clone()];
    let mut end_idx = first_idx + 1;
    for (offset, line) in lines[first_idx + 1..scan_end].iter().enumerate() {
        if offset >= 4 {
            break;
        }
        if (line.font_size - max_title_size).abs() <= max_title_size * 0.12
            && !is_abstract_heading(&line.text)
            && !is_section_heading(&line.text)
            && parse_publication_date_str(&line.text).is_none()
        {
            parts.push(line.text.clone());
            end_idx = first_idx + 1 + offset + 1;
        } else {
            break;
        }
    }

    let joined = join_wrapped_lines(&parts);
    let clean = clean_extracted_title(&joined)?;
    Some((Some(clean), end_idx))
}

fn extract_title_plain_lines(lines: &[LayoutLine]) -> (Option<String>, usize) {
    let mut idx = 0;
    while idx < lines.len().min(8) && is_ignorable_top_banner(&lines[idx].text) {
        idx += 1;
    }
    if idx >= lines.len() {
        return (None, 0);
    }

    let mut title_parts = Vec::new();
    let max_title_lines = 4;
    while idx < lines.len() && title_parts.len() < max_title_lines {
        let txt = lines[idx].text.as_str();
        if is_abstract_heading(txt) || is_section_heading(txt) {
            break;
        }
        if !title_parts.is_empty()
            && (parse_publication_date_str(txt).is_some()
                || looks_like_author_line(txt)
                || looks_like_affiliation_or_contact_line(txt))
        {
            break;
        }
        if txt.len() > 160 && !title_parts.is_empty() {
            break;
        }
        title_parts.push(txt.to_owned());
        idx += 1;
        // Single-line sentence ending with terminal period is rarely a multi-line title
        if txt.ends_with('.') && txt.len() > 50 {
            break;
        }
    }
    let joined = join_wrapped_lines(&title_parts);
    (clean_extracted_title(&joined), idx)
}

fn extract_header_after_title(
    lines: &[LayoutLine],
    start_idx: usize,
) -> (Vec<String>, Option<DateTime<Utc>>, Option<i32>, Option<String>) {
    let mut authors: Vec<String> = Vec::new();
    let mut seen_authors: HashSet<String> = HashSet::new();
    let mut published_at: Option<DateTime<Utc>> = None;
    let mut release_year: Option<i32> = None;
    let mut abstract_idx: Option<usize> = None;

    // Also scan top lines before title for date inside arXiv stamp (e.g. `arXiv:2610.01234 [cs] 20 Oct 2026`)
    for line in lines.iter().take(start_idx.min(4)) {
        if published_at.is_none()
            && let Some(d) = extract_embedded_date(&line.text)
        {
            published_at = d.published_at;
            release_year = d.release_year;
        }
    }

    let header_limit = lines.len().min(start_idx + 24);
    for (idx, line) in lines.iter().enumerate().take(header_limit).skip(start_idx) {
        let txt = line.text.as_str();
        if is_abstract_heading(txt) {
            abstract_idx = Some(idx);
            break;
        }
        if is_section_heading(txt) {
            break;
        }
        if published_at.is_none()
            && let Some(parsed_date) = parse_publication_date_str(txt)
        {
            published_at = parsed_date.published_at;
            release_year = parsed_date.release_year;
            continue;
        }
        if looks_like_affiliation_or_contact_line(txt) || is_ignorable_top_banner(txt) {
            continue;
        }
        if looks_like_author_line(txt) {
            for name in split_author_names(txt) {
                let key = name.to_lowercase();
                if seen_authors.insert(key) {
                    authors.push(name);
                }
            }
            continue;
        }
        // Once we hit body prose (>120 chars with sentence punctuation), stop scanning for header authors
        if txt.len() > 120 && txt.contains(". ") {
            break;
        }
    }

    let summary = abstract_idx.and_then(|idx| extract_abstract_text(lines, idx));
    (authors, published_at, release_year, summary)
}

fn extract_abstract_text(lines: &[LayoutLine], abstract_idx: usize) -> Option<String> {
    let mut chunks = Vec::new();
    let heading_line = lines[abstract_idx].text.as_str();
    if let Some(inline_rest) = strip_abstract_heading_prefix(heading_line)
        && !inline_rest.is_empty()
    {
        chunks.push(inline_rest.to_owned());
    }
    let body_limit = lines.len().min(abstract_idx + 25);
    let mut total_chars = chunks.iter().map(String::len).sum::<usize>();
    for line in &lines[abstract_idx + 1..body_limit] {
        let txt = line.text.as_str();
        if is_section_heading(txt) || is_keywords_line(txt) || is_page_number(txt) {
            break;
        }
        chunks.push(txt.to_owned());
        total_chars += txt.len();
        if total_chars >= MAX_ABSTRACT_CHARS + 120 {
            break;
        }
    }
    let joined = join_wrapped_lines(&chunks);
    let collapsed = collapse_whitespace(&joined);
    if collapsed.len() < 20 {
        return None;
    }
    Some(truncate_clean(&collapsed, MAX_ABSTRACT_CHARS))
}

fn strip_abstract_heading_prefix(line: &str) -> Option<&str> {
    let trimmed = line.trim();
    if trimmed.len() >= 8
        && trimmed.is_char_boundary(8)
        && trimmed[..8].eq_ignore_ascii_case("abstract")
    {
        let rest = trimmed[8..].trim_start_matches([':', '.', '—', '–', '-', ' ']);
        return Some(rest.trim());
    }
    None
}

fn is_abstract_heading(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.eq_ignore_ascii_case("abstract") || trimmed.eq_ignore_ascii_case("abstract.") {
        return true;
    }
    trimmed.len() > 8
        && trimmed.is_char_boundary(8)
        && trimmed[..8].eq_ignore_ascii_case("abstract")
        && matches!(trimmed.as_bytes()[8], b':' | b'.' | b'-' | b' ')
        && trimmed[8..]
            .trim_start()
            .starts_with(|c: char| matches!(c, ':' | '.' | '—' | '–' | '-') || trimmed.len() > 25)
}

fn is_section_heading(line: &str) -> bool {
    let trimmed = line.trim();
    let lower = trimmed.to_ascii_lowercase();
    if matches!(
        lower.as_str(),
        "introduction"
            | "1 introduction"
            | "1. introduction"
            | "i. introduction"
            | "executive summary"
            | "1 executive summary"
            | "1. executive summary"
            | "background"
            | "1 background"
            | "1. background"
            | "contents"
            | "table of contents"
    ) {
        return true;
    }
    // Pattern: `1 Heading` or `1. Heading` or `I. Heading` with short length
    if trimmed.len() <= 60 {
        let mut parts = trimmed.splitn(2, ' ');
        if let (Some(num), Some(rest)) = (parts.next(), parts.next()) {
            let num_clean = num.trim_end_matches('.');
            let is_sec_num = matches!(num_clean, "1" | "I" | "0")
                && rest.chars().next().is_some_and(char::is_uppercase)
                && rest.chars().filter(|c| c.is_alphabetic()).count() >= 4;
            if is_sec_num && parse_publication_date_str(trimmed).is_none() {
                return true;
            }
        }
    }
    false
}

fn is_keywords_line(line: &str) -> bool {
    let lower = line.trim().to_ascii_lowercase();
    lower.starts_with("keywords")
        || lower.starts_with("key words")
        || lower.starts_with("index terms")
        || lower.starts_with("ccs concepts")
        || lower.starts_with("msc ")
        || lower.starts_with("2020 mathematics subject")
}

fn is_page_number(line: &str) -> bool {
    let t = line.trim();
    !t.is_empty() && t.len() <= 4 && t.bytes().all(|b| b.is_ascii_digit())
}

fn is_ignorable_top_banner(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.is_empty() || is_page_number(trimmed) {
        return true;
    }
    let lower = trimmed.to_ascii_lowercase();
    lower.starts_with("arxiv:")
        || lower.starts_with("http://")
        || lower.starts_with("https://")
        || lower.starts_with("doi:")
        || lower.starts_with("preprint")
        || lower.starts_with("published as a conference paper")
        || lower.starts_with("proceedings of")
        || lower.starts_with("accepted at")
        || lower.starts_with("under review")
        || lower.starts_with("working paper")
        || lower.starts_with("technical report")
        || lower.starts_with("manuscript")
        || lower.contains("copyright ©")
        || lower.contains("© 20")
}

fn extract_embedded_date(line: &str) -> Option<super::ParsedPublicationDate> {
    // Handles strings like `arXiv:2610.01234v1 [cs.DS] 20 Oct 2026`
    if let Some((_, after_bracket)) = line.rsplit_once(']')
        && let Some(d) = parse_publication_date_str(after_bracket.trim())
    {
        return Some(d);
    }
    parse_publication_date_str(line)
}

fn contains_affiliation_keyword(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    AFFILIATION_KEYWORDS.iter().any(|kw| {
        lower.find(kw).is_some_and(|pos| {
            let before_ok = pos == 0 || !lower.as_bytes()[pos - 1].is_ascii_alphanumeric();
            let after_idx = pos + kw.len();
            let after_ok = after_idx >= lower.len()
                || kw.ends_with(' ')
                || kw.ends_with('.')
                || !lower.as_bytes()[after_idx].is_ascii_alphanumeric();
            before_ok && after_ok
        })
    })
}

fn looks_like_affiliation_or_contact_line(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.contains('@')
        || trimmed.contains("http://")
        || trimmed.contains("https://")
        || trimmed.contains("orcid")
        || trimmed.starts_with("∗")
        || trimmed.starts_with('*')
        || trimmed.starts_with('†')
        || trimmed.starts_with('‡')
    {
        return true;
    }
    // Pure affiliation line (no leading personal name before comma): e.g. `UNSW Sydney`, `Department of Mathematics`
    if !trimmed.contains(',') && contains_affiliation_keyword(trimmed) {
        return true;
    }
    // Affiliation starting with a keyword or digit (e.g. `1 University of X`, `Department of CS, MIT`)
    let first_part = trimmed.split(',').next().unwrap_or(trimmed).trim();
    let first_clean = first_part
        .trim_start_matches(|c: char| c.is_ascii_digit() || matches!(c, '*' | '†' | '‡' | ' '));
    contains_affiliation_keyword(first_clean)
}

fn looks_like_author_line(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.is_empty() || trimmed.len() > 180 {
        return false;
    }
    if trimmed.ends_with('.') || trimmed.ends_with('?') || trimmed.ends_with(':') {
        return false;
    }
    if trimmed.contains('@')
        || trimmed.contains("http://")
        || trimmed.contains("https://")
        || trimmed.contains('$')
    {
        return false;
    }
    let names = split_author_names(trimmed);
    !names.is_empty()
}

/// Splits a raw author/byline string (e.g. `"David Harvey, UNSW Sydney"`, `"Alice Smith and Bob Jones"`,
/// `"Alice Smith1, Bob Jones2"`) into clean individual author names.
#[must_use]
pub fn split_author_names(raw: &str) -> Vec<String> {
    let cleaned = raw.trim();
    if cleaned.is_empty() {
        return Vec::new();
    }
    // Strip optional leading "by "
    let without_by = if cleaned.len() > 3
        && cleaned.is_char_boundary(3)
        && cleaned[..3].eq_ignore_ascii_case("by ")
    {
        cleaned[3..].trim()
    } else {
        cleaned
    };

    // Split on semicolons first (common in PDF `/Author` metadata: `Smith, Alice; Jones, Bob`)
    let mut candidates = Vec::new();
    for semi_part in without_by.split(';') {
        for and_part in split_on_author_conjunction(semi_part) {
            for comma_part in and_part.split(',') {
                let norm = normalize_single_author_candidate(comma_part);
                if let Some(name) = norm {
                    candidates.push(name);
                }
            }
        }
    }

    // Deduplicate while preserving order
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for name in candidates {
        if seen.insert(name.to_lowercase()) {
            out.push(name);
        }
    }
    out
}

fn split_on_author_conjunction(raw: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut rest = raw;
    loop {
        let next_split = [", and ", " and ", " & ", " · ", " • "]
            .iter()
            .filter_map(|sep| rest.find(sep).map(|idx| (idx, sep.len())))
            .min_by_key(|(idx, _)| *idx);
        let Some((idx, sep_len)) = next_split else {
            parts.push(rest);
            break;
        };
        parts.push(&rest[..idx]);
        rest = &rest[idx + sep_len..];
    }
    parts
}

#[allow(clippy::too_many_lines)]
fn normalize_single_author_candidate(raw: &str) -> Option<String> {
    // Strip parenthesized affiliations/notes like `(MIT)` or `(corresponding author)`
    let mut stripped = String::with_capacity(raw.len());
    let mut depth = 0usize;
    for ch in raw.chars() {
        match ch {
            '(' | '[' => depth += 1,
            ')' | ']' => depth = depth.saturating_sub(1),
            _ if depth == 0 => stripped.push(ch),
            _ => {}
        }
    }
    // Remove superscript footnote markers and trailing digits attached to names (`Smith1`, `Jones*`)
    let no_markers: String = stripped
        .chars()
        .map(|c| {
            if matches!(
                c,
                '*' | '†'
                    | '‡'
                    | '§'
                    | '¶'
                    | '∗'
                    | '¹'
                    | '²'
                    | '³'
                    | '⁴'
                    | '⁵'
                    | '⁶'
                    | '⁷'
                    | '⁸'
                    | '⁹'
                    | '⁰'
            ) {
                ' '
            } else {
                c
            }
        })
        .collect();
    let words: Vec<String> = no_markers
        .split_whitespace()
        .map(|w| w.trim_end_matches(|c: char| c.is_ascii_digit()).trim_matches('.'))
        .filter(|w| !w.is_empty())
        .map(|w| {
            // Preserve initials like "J." when single letter
            if w.chars().count() == 1 && w.chars().all(char::is_alphabetic) {
                format!("{w}.")
            } else {
                w.to_owned()
            }
        })
        .collect();

    if words.len() < 2 || words.len() > 5 {
        return None;
    }
    let joined = words.join(" ");
    if is_junk_pdf_author(&joined)
        || contains_affiliation_keyword(&joined)
        || parse_publication_date_str(&joined).is_some()
        || joined.contains('@')
        || joined.chars().any(char::is_numeric)
    {
        return None;
    }
    // Must look like a personal name: at least 2 words starting with uppercase (allowing particles like `van`, `der`, `de`, `von`, `la`, `di`, `da`, `del`, `al`)
    let cap_words =
        words.iter().filter(|w| w.chars().next().is_some_and(char::is_uppercase)).count();
    let lower_non_particles = words
        .iter()
        .filter(|w| {
            w.chars().next().is_some_and(char::is_lowercase)
                && !matches!(
                    w.as_str(),
                    "van"
                        | "der"
                        | "de"
                        | "del"
                        | "della"
                        | "von"
                        | "la"
                        | "le"
                        | "da"
                        | "di"
                        | "du"
                        | "des"
                        | "dos"
                        | "al"
                        | "el"
                        | "bin"
                        | "ibn"
                        | "ter"
                        | "ten"
                )
        })
        .count();
    if cap_words < 2 || lower_non_particles > 0 {
        return None;
    }
    // Reject all-caps acronyms like `UNSW SYDNEY` or `ACM SIGPLAN`
    let all_upper_words = words
        .iter()
        .filter(|w| w.chars().count() >= 3 && w.chars().all(|c| !c.is_lowercase()))
        .count();
    if all_upper_words >= 1 && words.len() == 2 {
        return None;
    }
    Some(joined)
}

fn join_wrapped_lines(lines: &[String]) -> String {
    let mut out = String::new();
    for line in lines {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if out.ends_with('-')
            && !out.ends_with(" -")
            && trimmed.chars().next().is_some_and(char::is_lowercase)
        {
            out.pop();
        } else if !out.is_empty() {
            out.push(' ');
        }
        out.push_str(trimmed);
    }
    collapse_whitespace(&out)
}

fn clean_extracted_title(raw: &str) -> Option<String> {
    let collapsed = collapse_whitespace(raw);
    let trimmed = collapsed.trim_matches(|c: char| matches!(c, '"' | '\'')).trim();
    if trimmed.len() < 4 || is_junk_pdf_title(trimmed) {
        return None;
    }
    Some(truncate_clean(trimmed, MAX_TITLE_CHARS))
}

fn truncate_clean(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        return s.to_owned();
    }
    let budget = max_chars.saturating_sub(1);
    let prefix: String = s.chars().take(budget).collect();
    if let Some(last_space) = prefix.rfind(' ')
        && prefix[..last_space].chars().count() >= budget * 2 / 3
    {
        format!("{}…", prefix[..last_space].trim_end())
    } else {
        format!("{}…", prefix.trim_end())
    }
}
