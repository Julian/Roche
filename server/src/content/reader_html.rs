use std::{
    collections::{HashMap, HashSet},
    fmt::{self, Write as _},
};

use roche_core::document_meta::render_inline_math_html;
use url::Url;

use super::{HtmlSanitizer, semantic_html};

/// HTML-escapes interpolated text (`&`, `<`, `>`, `"`, `'`) with zero intermediate heap allocation.
#[derive(Debug, Clone, Copy)]
pub struct Esc<'a>(pub &'a str);

impl fmt::Display for Esc<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for ch in self.0.chars() {
            match ch {
                '&' => f.write_str("&amp;")?,
                '<' => f.write_str("&lt;")?,
                '>' => f.write_str("&gt;")?,
                '"' => f.write_str("&quot;")?,
                '\'' => f.write_str("&#39;")?,
                other => f.write_char(other)?,
            }
        }
        Ok(())
    }
}

/// Estimates reading time in minutes (at ~230 WPM) from HTML or plain text.
#[must_use]
pub fn estimate_html_reading_minutes(src: &str) -> Option<usize> {
    let mut words = 0usize;
    let mut in_tag = false;
    let mut in_word = false;
    for ch in src.chars() {
        match ch {
            '<' => {
                in_tag = true;
                in_word = false;
            }
            '>' => in_tag = false,
            c if !in_tag => {
                if c.is_whitespace() {
                    in_word = false;
                } else if !in_word {
                    words += 1;
                    in_word = true;
                }
            }
            _ => {}
        }
    }
    if words == 0 { None } else { Some((words / 230).max(1)) }
}

/// Renders a plain-text summary (which may contain inline LaTeX math) into safe `<p>...</p>` HTML.
#[must_use]
pub fn render_summary_paragraphs_html(summary: &str) -> Option<String> {
    let trimmed = summary.trim();
    if trimmed.is_empty() {
        return None;
    }
    let mut out = String::with_capacity(trimmed.len() + 32);
    for para in trimmed.split("\n\n").map(str::trim).filter(|p| !p.is_empty()) {
        out.push_str("<p>");
        out.push_str(&render_inline_math_html(para));
        out.push_str("</p>\n");
    }
    (!out.is_empty()).then_some(out)
}

/// Finds the byte index of the closing `>` of an HTML tag starting immediately after `<`,
/// ignoring any `>` characters inside single- or double-quoted attribute values.
fn find_html_tag_end(after_lt: &str) -> Option<usize> {
    let mut quote: Option<u8> = None;
    for (idx, b) in after_lt.bytes().enumerate() {
        match quote {
            Some(q) if b == q => quote = None,
            None if b == b'"' || b == b'\'' => quote = Some(b),
            None if b == b'>' => return Some(idx),
            Some(_) | None => {}
        }
    }
    None
}

/// Checks whether an HTML tag's attribute string defines an attribute named `target`
/// (ASCII case-insensitive), skipping quoted attribute values so text inside another
/// attribute value (e.g. `title="alt=foo"`) is never mistaken for an attribute name.
fn has_html_attr(attrs: &str, target: &str) -> bool {
    let bytes = attrs.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let name_start = i;
        while i < bytes.len()
            && !bytes[i].is_ascii_whitespace()
            && bytes[i] != b'='
            && bytes[i] != b'/'
        {
            i += 1;
        }
        let matched = attrs[name_start..i].eq_ignore_ascii_case(target);

        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < bytes.len() && (bytes[i] == b'"' || bytes[i] == b'\'') {
                let quote = bytes[i];
                i += 1;
                while i < bytes.len() && bytes[i] != quote {
                    i += 1;
                }
                if i < bytes.len() {
                    i += 1;
                }
            } else {
                while i < bytes.len() && !bytes[i].is_ascii_whitespace() {
                    i += 1;
                }
            }
        }
        if matched {
            return true;
        }
    }
    false
}

fn parse_heading_level(tag_name: &str) -> Option<u8> {
    let bytes = tag_name.as_bytes();
    if bytes.len() == 2 && bytes[0].eq_ignore_ascii_case(&b'h') && (b'1'..=b'6').contains(&bytes[1])
    {
        Some(bytes[1] - b'0')
    } else {
        None
    }
}

const fn heading_tag_for_level(level: u8) -> &'static str {
    match level {
        1 => "h1",
        2 => "h2",
        3 => "h3",
        4 => "h4",
        5 => "h5",
        _ => "h6",
    }
}

fn find_min_heading_level(clean_html: &str) -> Option<u8> {
    let mut min_lvl: Option<u8> = None;
    let mut rest = clean_html;
    while let Some(lt) = rest.find('<') {
        let after_lt = &rest[lt + 1..];
        let Some(gt) = find_html_tag_end(after_lt) else {
            break;
        };
        let raw_tag = &after_lt[..gt];
        rest = &after_lt[gt + 1..];
        let tag_body = raw_tag.strip_prefix('/').unwrap_or(raw_tag);
        let name_end =
            tag_body.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(tag_body.len());
        if let Some(lvl) = parse_heading_level(&tag_body[..name_end]) {
            min_lvl = Some(min_lvl.map_or(lvl, |cur| cur.min(lvl)));
            if lvl == 1 {
                break;
            }
        }
    }
    min_lvl
}

fn parse_html_attrs(attrs: &str) -> Vec<(String, Option<String>)> {
    let mut out = Vec::new();
    let bytes = attrs.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b'/') {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let name_start = i;
        while i < bytes.len()
            && !bytes[i].is_ascii_whitespace()
            && bytes[i] != b'='
            && bytes[i] != b'/'
        {
            i += 1;
        }
        let name = attrs[name_start..i].to_owned();
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let value = if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            if i < bytes.len() && (bytes[i] == b'"' || bytes[i] == b'\'') {
                let quote = bytes[i];
                i += 1;
                let val_start = i;
                while i < bytes.len() && bytes[i] != quote {
                    i += 1;
                }
                let raw = &attrs[val_start..i];
                if i < bytes.len() {
                    i += 1;
                }
                Some(decode_attr_entities(raw))
            } else {
                let val_start = i;
                while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b'/' {
                    i += 1;
                }
                Some(decode_attr_entities(&attrs[val_start..i]))
            }
        } else {
            None
        };
        if !name.is_empty() {
            out.push((name, value));
        }
    }
    out
}

fn decode_attr_entities(raw: &str) -> String {
    quick_xml::escape::unescape(raw).map_or_else(|_| raw.to_owned(), std::borrow::Cow::into_owned)
}

fn get_parsed_attr<'a>(attrs: &'a [(String, Option<String>)], target: &str) -> Option<&'a str> {
    attrs.iter().find_map(|(k, v)| k.eq_ignore_ascii_case(target).then_some(v.as_deref()).flatten())
}

fn is_inline_emoji_img(src: &str, alt: &str) -> bool {
    let lower = src.to_ascii_lowercase();
    lower.contains("/images/core/emoji/")
        || lower.contains("/wp-includes/images/smilies/")
        || lower.contains("/wpcom-smileys/")
        || lower.contains("twemoji.maxcdn.com/")
        || lower.contains("/twemoji/")
        || is_footnote_backarrow_emoji(src, alt)
}

fn is_footnote_backarrow_emoji(src: &str, alt: &str) -> bool {
    let trimmed = alt.trim().trim_end_matches(['\u{FE0E}', '\u{FE0F}']);
    if trimmed == "↩" {
        return true;
    }
    let lower = src.to_ascii_lowercase();
    trimmed.is_empty() && (lower.ends_with("/21a9.png") || lower.ends_with("/21a9.svg"))
}

fn is_latex_img_src(src: &str) -> bool {
    src.to_ascii_lowercase().contains("/latex.php")
}

fn is_tracking_pixel_img(src: &str, width: Option<&str>, height: Option<&str>) -> bool {
    let is_tiny = |raw: &str| {
        let trimmed = raw.trim();
        !trimmed.ends_with('%')
            && trimmed
                .trim_end_matches("px")
                .trim_end_matches("PX")
                .trim()
                .parse::<u32>()
                .is_ok_and(|px| px <= 4)
    };
    if width.is_some_and(is_tiny) || height.is_some_and(is_tiny) {
        return true;
    }
    let lower = src.to_ascii_lowercase();
    lower.contains("feeds.feedburner.com/~")
        || lower.contains("/1x1.gif")
        || lower.contains("/pixel.gif")
        || lower.contains("/spacer.gif")
}

fn is_empty_html_text(raw: &str) -> bool {
    let mut rest = raw;
    while !rest.is_empty() {
        let trimmed = rest.trim_start();
        if trimmed.is_empty() {
            return true;
        }
        if let Some(after) = trimmed
            .strip_prefix("&nbsp;")
            .or_else(|| trimmed.strip_prefix("&#160;"))
            .or_else(|| trimmed.strip_prefix("&#xA0;"))
            .or_else(|| trimmed.strip_prefix("&#xa0;"))
            .or_else(|| trimmed.strip_prefix('\u{00a0}'))
        {
            rest = after;
        } else {
            return false;
        }
    }
    true
}

fn normalize_title_for_compare(raw: &str) -> String {
    raw.split_whitespace().collect::<Vec<_>>().join(" ").to_lowercase()
}

fn strip_duplicate_leading_h1<'a>(clean_html: &'a str, article_title: Option<&str>) -> &'a str {
    let Some(title) = article_title.map(str::trim).filter(|t| !t.is_empty()) else {
        return clean_html;
    };
    let trimmed = clean_html.trim_start();
    let Some(after_lt) = trimmed.strip_prefix('<') else {
        return clean_html;
    };
    let Some(gt) = find_html_tag_end(after_lt) else {
        return clean_html;
    };
    let open_tag = &after_lt[..gt];
    let name_end =
        open_tag.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(open_tag.len());
    if !open_tag[..name_end].eq_ignore_ascii_case("h1") {
        return clean_html;
    }
    let body_start = &after_lt[gt + 1..];
    let lower_rest = body_start.to_ascii_lowercase();
    let Some(close_pos) = lower_rest.find("</h1>") else {
        return clean_html;
    };
    let inner_html = &body_start[..close_pos];
    let heading_text = HtmlSanitizer::plain_text_excerpt_from_clean(inner_html, 512);
    if normalize_title_for_compare(&heading_text) == normalize_title_for_compare(title) {
        &body_start[close_pos + "</h1>".len()..]
    } else {
        clean_html
    }
}

fn extract_same_page_fragment<'a>(
    href: &'a str,
    article_url: Option<&Url>,
) -> Option<std::borrow::Cow<'a, str>> {
    let trimmed = href.trim();
    let raw_frag: std::borrow::Cow<'_, str> = if let Some(frag) = trimmed.strip_prefix('#') {
        std::borrow::Cow::Borrowed(frag)
    } else {
        let base = article_url?;
        let parsed = Url::parse(trimmed).ok()?;
        let frag = parsed.fragment()?;
        let same_host =
            parsed.host_str().zip(base.host_str()).is_some_and(|(a, b)| a.eq_ignore_ascii_case(b));
        let same_path = parsed.path().trim_end_matches('/') == base.path().trim_end_matches('/');
        if !same_host || !same_path || parsed.query() != base.query() {
            return None;
        }
        std::borrow::Cow::Owned(frag.to_owned())
    };
    (!raw_frag.is_empty()).then_some(raw_frag)
}

fn reciprocal_footnote_candidates(raw_frag: &str) -> Vec<String> {
    let frag = raw_frag.strip_prefix("prose-").unwrap_or(raw_frag);
    if let Some(base) = frag.strip_suffix("-link") {
        return vec![base.to_owned()];
    }
    if let Some(rest) = frag.strip_prefix("footnote_") {
        return vec![format!("identifier_{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("identifier_") {
        return vec![format!("footnote_{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("fnref") {
        return vec![format!("fn{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("fn") {
        return vec![format!("fnref{rest}"), format!("{frag}-link")];
    }
    if let Some(rest) =
        frag.strip_prefix("footnote-anchor-").or_else(|| frag.strip_prefix("footnote-ref-"))
    {
        return vec![format!("footnote-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("footnote-") {
        return vec![
            format!("footnote-anchor-{rest}"),
            format!("footnote-ref-{rest}"),
            format!("{frag}-link"),
        ];
    }
    if let Some(rest) = frag.strip_prefix("cite_note-") {
        return vec![format!("cite_ref-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("cite_ref-") {
        return vec![format!("cite_note-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("noteref-") {
        return vec![format!("note-{rest}")];
    }
    if let Some(rest) = frag.strip_prefix("note-") {
        return vec![format!("noteref-{rest}"), format!("{frag}-link")];
    }
    vec![format!("{frag}-link")]
}

fn synthesize_paired_footnote_ids(
    html: &str,
    article_url: Option<&Url>,
    known_ids: &mut HashSet<String>,
) -> HashMap<String, String> {
    let mut referenced_frags = HashSet::new();
    let mut rest = html;
    while let Some(lt) = rest.find('<') {
        let after_lt = &rest[lt + 1..];
        let Some(gt) = find_html_tag_end(after_lt) else {
            break;
        };
        let raw_tag = &after_lt[..gt];
        rest = &after_lt[gt + 1..];
        if raw_tag.starts_with('/') {
            continue;
        }
        let name_end =
            raw_tag.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(raw_tag.len());
        if !raw_tag[..name_end].eq_ignore_ascii_case("a") {
            continue;
        }
        let parsed = parse_html_attrs(&raw_tag[name_end..]);
        if let Some(href) = get_parsed_attr(&parsed, "href")
            && let Some(frag) = extract_same_page_fragment(href, article_url)
        {
            let frag_str = frag.as_ref();
            let norm = frag_str.strip_prefix("prose-").unwrap_or(frag_str);
            if !norm.is_empty() {
                referenced_frags.insert(norm.to_owned());
            }
        }
    }

    let mut synthetic_by_href_frag = HashMap::new();
    for frag in &referenced_frags {
        for recip in reciprocal_footnote_candidates(frag) {
            let prose_recip = format!("prose-{recip}");
            if referenced_frags.contains(&recip)
                && !known_ids.contains(&recip)
                && !known_ids.contains(&prose_recip)
            {
                known_ids.insert(prose_recip.clone());
                synthetic_by_href_frag.insert(frag.clone(), prose_recip);
                break;
            }
        }
    }
    synthetic_by_href_frag
}

fn resolve_local_prose_fragment<'a>(
    href: &str,
    article_url: Option<&Url>,
    known_ids: &'a HashSet<String>,
) -> Option<&'a str> {
    let raw_frag = extract_same_page_fragment(href, article_url)?;
    let raw_str = raw_frag.as_ref();
    if let Some(exact) = known_ids.get(raw_str) {
        return Some(exact.as_str());
    }
    let stripped = raw_str.strip_prefix("prose-").unwrap_or(raw_str);
    if let Some(exact) = known_ids.get(stripped) {
        return Some(exact.as_str());
    }
    let prefixed = format!("prose-{stripped}");
    known_ids.get(&prefixed).map(String::as_str)
}

fn strip_duplicate_id_attr(attrs: &str, seen_ids: &mut HashSet<String>) -> Option<String> {
    let parsed = parse_html_attrs(attrs);
    let id_val = get_parsed_attr(&parsed, "id")?;
    if seen_ids.insert(id_val.to_owned()) {
        return None;
    }
    let mut rebuilt = String::new();
    for (k, v) in parsed {
        if k.eq_ignore_ascii_case("id") {
            continue;
        }
        rebuilt.push(' ');
        rebuilt.push_str(&k);
        if let Some(val) = v {
            let _ = write!(rebuilt, "=\"{}\"", Esc(&val));
        }
    }
    Some(rebuilt)
}

fn rewrite_anchor_attrs_for_local_fragment(
    attrs: &str,
    local_id: &str,
    inject_id: Option<&str>,
) -> String {
    let parsed = parse_html_attrs(attrs);
    let mut rebuilt = String::new();
    if let Some(id_val) = inject_id {
        let _ = write!(rebuilt, " id=\"{}\"", Esc(id_val));
    }
    for (k, v) in parsed {
        if k.eq_ignore_ascii_case("href") {
            let _ = write!(rebuilt, " href=\"#{}\"", Esc(local_id));
        } else if !k.eq_ignore_ascii_case("target") && !k.eq_ignore_ascii_case("rel") {
            rebuilt.push(' ');
            rebuilt.push_str(&k);
            if let Some(val) = v {
                let _ = write!(rebuilt, "=\"{}\"", Esc(&val));
            }
        }
    }
    rebuilt
}

/// Sanitizes untrusted article/board HTML with `ammonia` and applies Roche's reader prose
/// accessibility/privacy hardening in a single atomic pipeline for embedding under the Reader's
/// `<h2>` title:
///
/// - Strips a duplicate leading `<h1>` when it repeats the article title.
/// - Replaces WordPress/Twemoji emoji `<img>` tags with Unicode `alt` text, converts `latex.php`
///   math images into readable `<em>` math, and strips tracking pixels.
/// - Drops empty `<p></p>` blocks, deduplicates `id` attributes, and rewires in-page footnote
///   links (`#fn1` / `<article-url>#fn1`) to matching `#prose-...` targets inside the article.
/// - Dynamically demotes headings from the highest heading level present so top article
///   headings land on `<h3>` (and subheadings on `<h4>`/`<h5>`, capped at `<h6>`), preserving
///   a single `<h1>` and a clean prose heading hierarchy without skipping levels.
/// - Ensures `<pre>` blocks carry `tabindex="0"` for keyboard scrolling.
/// - Ensures `<img>` tags without an `alt` attribute default to `alt=""`.
#[must_use]
pub fn sanitize_and_harden_reader_html(
    raw_html: &str,
    article_url: Option<&Url>,
    article_title: Option<&str>,
) -> String {
    let cleaned = HtmlSanitizer::clean_with_base(raw_html, article_url);
    harden_reader_prose_html_with_context(&cleaned, article_url, article_title)
}

#[must_use]
#[allow(clippy::too_many_lines)]
fn harden_reader_prose_html_with_context(
    clean_html: &str,
    article_url: Option<&Url>,
    article_title: Option<&str>,
) -> String {
    let without_dup_h1 = strip_duplicate_leading_h1(clean_html, article_title);
    let heading_shift =
        find_min_heading_level(without_dup_h1).map_or(0, |min_lvl| 3u8.saturating_sub(min_lvl));
    let mut known_ids = semantic_html::collect_html_ids(without_dup_h1);
    let synthetic_ids = synthesize_paired_footnote_ids(without_dup_h1, article_url, &mut known_ids);
    let mut seen_ids = HashSet::new();
    let mut out = String::with_capacity(without_dup_h1.len() + 64);
    let mut rest = without_dup_h1;

    while let Some(lt) = rest.find('<') {
        out.push_str(&rest[..lt]);
        let after_lt = &rest[lt + 1..];
        let Some(gt) = find_html_tag_end(after_lt) else {
            out.push('<');
            out.push_str(after_lt);
            return out;
        };
        let raw_tag = &after_lt[..gt];
        rest = &after_lt[gt + 1..];

        let (is_closing, tag_body) =
            raw_tag.strip_prefix('/').map_or((false, raw_tag), |stripped| (true, stripped));
        let name_end =
            tag_body.find(|c: char| c.is_ascii_whitespace() || c == '/').unwrap_or(tag_body.len());
        let tag_name = &tag_body[..name_end];
        let attrs = &tag_body[name_end..];

        if !is_closing
            && tag_name.eq_ignore_ascii_case("p")
            && !attrs.ends_with('/')
            && let Some(next_lt) = rest.find('<')
            && is_empty_html_text(&rest[..next_lt])
            && let Some(next_gt) = find_html_tag_end(&rest[next_lt + 1..])
            && rest[next_lt + 1..next_lt + 1 + next_gt].trim().eq_ignore_ascii_case("/p")
        {
            rest = &rest[next_lt + 1 + next_gt + 1..];
            continue;
        }

        if !is_closing && tag_name.eq_ignore_ascii_case("img") {
            let parsed_attrs = parse_html_attrs(attrs);
            let src = get_parsed_attr(&parsed_attrs, "src").unwrap_or("");
            let alt = get_parsed_attr(&parsed_attrs, "alt").unwrap_or("");
            let width = get_parsed_attr(&parsed_attrs, "width");
            let height = get_parsed_attr(&parsed_attrs, "height");
            if is_latex_img_src(src) && !alt.trim().is_empty() {
                let formatted = semantic_html::format_latex_alt_to_html(alt);
                if !formatted.is_empty() {
                    let _ = write!(out, "<em>{formatted}</em>");
                }
                continue;
            }
            if is_inline_emoji_img(src, alt) {
                if is_footnote_backarrow_emoji(src, alt) {
                    out.push_str("↩\u{FE0E}");
                    rest = rest.trim_start_matches(['\u{FE0E}', '\u{FE0F}']);
                } else {
                    let _ = write!(out, "{}", Esc(alt.trim()));
                }
                continue;
            }
            if is_tracking_pixel_img(src, width, height) {
                continue;
            }
        }

        let demoted_heading = parse_heading_level(tag_name)
            .map(|lvl| heading_tag_for_level((lvl + heading_shift).min(6)));

        let deduped_attrs_owned = if !is_closing && has_html_attr(attrs, "id") {
            strip_duplicate_id_attr(attrs, &mut seen_ids)
        } else {
            None
        };
        let effective_attrs = deduped_attrs_owned.as_deref().unwrap_or(attrs);

        out.push('<');
        if is_closing {
            out.push('/');
            out.push_str(demoted_heading.unwrap_or(tag_name));
            out.push_str(attrs);
        } else if let Some(new_tag) = demoted_heading {
            out.push_str(new_tag);
            out.push_str(effective_attrs);
        } else if tag_name.eq_ignore_ascii_case("a")
            && !known_ids.is_empty()
            && let Some(href) = get_parsed_attr(&parse_html_attrs(attrs), "href")
            && let Some(local_id) = resolve_local_prose_fragment(href, article_url, &known_ids)
        {
            let inject_id = if has_html_attr(effective_attrs, "id") {
                None
            } else {
                extract_same_page_fragment(href, article_url).and_then(|frag| {
                    let frag_str = frag.as_ref();
                    let norm = frag_str.strip_prefix("prose-").unwrap_or(frag_str);
                    synthetic_ids
                        .get(norm)
                        .and_then(|synth| seen_ids.insert(synth.clone()).then_some(synth.as_str()))
                })
            };
            out.push_str(tag_name);
            out.push_str(&rewrite_anchor_attrs_for_local_fragment(
                effective_attrs,
                local_id,
                inject_id,
            ));
        } else if tag_name.eq_ignore_ascii_case("pre") {
            out.push_str(tag_name);
            out.push_str(effective_attrs);
            if !has_html_attr(effective_attrs, "tabindex") {
                out.push_str(" tabindex=\"0\"");
            }
        } else if tag_name.eq_ignore_ascii_case("img") {
            out.push_str(tag_name);
            let has_alt = has_html_attr(effective_attrs, "alt");
            if let Some(before_slash) = effective_attrs.strip_suffix('/') {
                out.push_str(before_slash);
                if !has_alt {
                    out.push_str(" alt=\"\"");
                }
                out.push('/');
            } else {
                out.push_str(effective_attrs);
                if !has_alt {
                    out.push_str(" alt=\"\"");
                }
            }
        } else {
            out.push_str(tag_name);
            out.push_str(effective_attrs);
        }
        out.push('>');
    }
    out.push_str(rest);
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn harden_reader_prose_demotes_headings_and_adds_a11y_attrs() {
        let html =
            "<h1>Top</h1><h2>Sub</h2><pre><code>x</code></pre><img src=\"https://a.test/1.png\">";
        let out = harden_reader_prose_html_with_context(html, None, None);
        assert!(out.contains("<h3>Top</h3>"));
        assert!(out.contains("<h4>Sub</h4>"));
        assert!(out.contains("<pre tabindex=\"0\"><code>x</code></pre>"));
        assert!(out.contains("alt=\"\""));
    }

    #[test]
    fn harden_reader_prose_strips_duplicate_h1_and_tracking_pixels() {
        let html = "<h1>Same Title</h1><p></p><p>Body</p><img src=\"https://feeds.feedburner.com/~r/x\" width=\"1\" height=\"1\">";
        let out = harden_reader_prose_html_with_context(html, None, Some("Same Title"));
        assert!(!out.contains("Same Title"));
        assert!(!out.contains("<p></p>"));
        assert!(!out.contains("feedburner"));
        assert!(out.contains("<p>Body</p>"));
    }
}
