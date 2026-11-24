use std::{
    borrow::Cow,
    collections::{HashMap, HashSet},
};

use chrono::{DateTime, Datelike, Utc};
use quick_xml::{Reader, events::Event};
use roche_core::document_meta::{
    ParsedPublicationDate, extract_doi_from_text, normalize_doi, normalize_isbn,
    parse_publication_date_str, split_author_names,
};
use url::Url;

use super::{semantic_html, xml_util};
use crate::util::{
    media_urls::{extract_youtube_channel_id, is_youtube_host, youtube_feed_url_for_channel_id},
    url_guard::{sanitize_public_http_href, validate_public_url},
};

pub const MAX_SUMMARY_CHARS: usize = 320;

pub struct HtmlSanitizer;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedSnapshot {
    pub title: Option<String>,
    pub author: Option<String>,
    pub authors: Vec<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub release_year: Option<i32>,
    pub doi: Option<String>,
    pub isbn: Option<String>,
    pub summary: Option<String>,
    pub content_html: String,
    pub thumbnail_url: Option<String>,
}

#[derive(Default)]
struct HtmlMetaAccumulator {
    citation_title: Option<String>,
    title: Option<String>,
    og_title: Option<String>,
    citation_authors: Vec<String>,
    meta_authors: Vec<String>,
    meta_desc: Option<String>,
    meta_image: Option<String>,
    citation_date: Option<ParsedPublicationDate>,
    meta_date: Option<ParsedPublicationDate>,
    time_date: Option<ParsedPublicationDate>,
    doi: Option<String>,
    isbn: Option<String>,
}

impl HtmlSanitizer {
    #[must_use]
    pub fn clean(html: &str) -> String {
        Self::clean_with_base(html, None)
    }

    #[must_use]
    pub fn clean_with_base(html: &str, base_url: Option<&Url>) -> String {
        let prepared = semantic_html::preprocess_semantic_html(html, &HashMap::new(), false);
        Self::sanitize_prepared_html(&prepared, base_url)
    }

    fn sanitize_prepared_html(html: &str, base_url: Option<&Url>) -> String {
        let valid_base = base_url.filter(|base| validate_public_url(base).is_ok()).cloned();
        let filter_base = valid_base.clone();

        let mut builder = ammonia::Builder::default();
        builder.url_schemes(HashSet::from(["http", "https", "mailto"]));
        builder.add_clean_content_tags([
            "head",
            "title",
            "noscript",
            "template",
            "svg",
            "iframe",
            "object",
            "embed",
            "noembed",
            "noframes",
            "xmp",
            "textarea",
            "plaintext",
        ]);
        semantic_html::configure_safe_mathml(&mut builder);
        builder.add_allowed_classes("figure", ["paper-equation"]);
        builder.add_allowed_classes("span", ["paper-eqno"]);
        builder.add_generic_attributes(["id"]);
        builder.set_tag_attribute_value("img", "referrerpolicy", "no-referrer");
        builder.set_tag_attribute_value("img", "loading", "lazy");
        builder.set_tag_attribute_value("img", "decoding", "async");
        if let Some(base) = valid_base {
            builder.url_relative(ammonia::UrlRelative::RewriteWithBase(base));
        }
        builder.attribute_filter(move |element, attribute, value| {
            if attribute.eq_ignore_ascii_case("id") {
                return semantic_html::normalize_prose_id(value);
            }
            match Self::filter_mathml_layout_attr(element, attribute, value) {
                MathMlAttrFilter::Keep(v) => return Some(v),
                MathMlAttrFilter::Drop => return None,
                MathMlAttrFilter::NotHandled => {}
            }
            Self::filter_sanitized_url_attr(element, attribute, value, filter_base.as_ref())
        });
        builder.clean(html).to_string()
    }

    fn filter_mathml_layout_attr<'a>(
        element: &str,
        attribute: &str,
        value: &'a str,
    ) -> MathMlAttrFilter<'a> {
        let attr = attribute.to_ascii_lowercase();
        let trimmed = value.trim();
        let filtered: Option<Cow<'a, str>> = match attr.as_str() {
            "display" if element.eq_ignore_ascii_case("math") => {
                matches!(trimmed.to_ascii_lowercase().as_str(), "block" | "inline")
                    .then(|| Cow::Owned(trimmed.to_ascii_lowercase()))
            }
            "mathvariant" => matches!(
                trimmed.to_ascii_lowercase().as_str(),
                "normal"
                    | "bold"
                    | "italic"
                    | "bold-italic"
                    | "double-struck"
                    | "script"
                    | "fraktur"
                    | "sans-serif"
                    | "monospace"
            )
            .then(|| Cow::Owned(trimmed.to_ascii_lowercase())),
            "stretchy" | "fence" | "separator" | "accent" | "accentunder" | "largeop"
            | "movablelimits" | "symmetric" => {
                matches!(trimmed.to_ascii_lowercase().as_str(), "true" | "false")
                    .then(|| Cow::Owned(trimmed.to_ascii_lowercase()))
            }
            "columnalign" => trimmed
                .split_whitespace()
                .all(|tok| matches!(tok, "left" | "center" | "right"))
                .then_some(Cow::Borrowed(trimmed))
                .filter(|s| !s.is_empty() && s.len() <= 64),
            "rowalign" => trimmed
                .split_whitespace()
                .all(|tok| matches!(tok, "top" | "bottom" | "center" | "baseline" | "axis"))
                .then_some(Cow::Borrowed(trimmed))
                .filter(|s| !s.is_empty() && s.len() <= 64),
            "columnspan" | "rowspan" => trimmed
                .parse::<u32>()
                .ok()
                .filter(|&n| (1..=32).contains(&n))
                .map(|n| Cow::Owned(n.to_string())),
            "width" | "lspace" | "rspace" | "linethickness"
                if matches!(element.to_ascii_lowercase().as_str(), "mspace" | "mo" | "mfrac") =>
            {
                is_safe_mathml_length(trimmed).then_some(Cow::Borrowed(trimmed))
            }
            _ => return MathMlAttrFilter::NotHandled,
        };
        filtered.map_or(MathMlAttrFilter::Drop, MathMlAttrFilter::Keep)
    }

    fn filter_sanitized_url_attr<'a>(
        element: &str,
        attribute: &str,
        value: &'a str,
        valid_base: Option<&Url>,
    ) -> Option<Cow<'a, str>> {
        let is_href = attribute.eq_ignore_ascii_case("href");
        let is_media_or_cite = attribute.eq_ignore_ascii_case("src")
            || attribute.eq_ignore_ascii_case("poster")
            || attribute.eq_ignore_ascii_case("cite");
        if !is_href && !is_media_or_cite {
            return Some(Cow::Borrowed(value));
        }
        let trimmed = value.trim();
        if trimmed.is_empty() {
            return None;
        }
        if is_href && (element.eq_ignore_ascii_case("a") || element.eq_ignore_ascii_case("area")) {
            if trimmed.starts_with('#') {
                return Some(Cow::Borrowed(trimmed));
            }
            if trimmed.len() > "mailto:".len()
                && trimmed.get(.."mailto:".len()).is_some_and(|p| p.eq_ignore_ascii_case("mailto:"))
            {
                return Some(Cow::Borrowed(trimmed));
            }
        }
        let resolved = match Url::parse(trimmed) {
            Ok(url) => url,
            Err(url::ParseError::RelativeUrlWithoutBase) => valid_base?.join(trimmed).ok()?,
            Err(_) => return None,
        };
        sanitize_public_http_href(resolved.as_str())?;
        Some(Cow::Owned(resolved.into()))
    }

    /// Converts raw HTML into a single-line plain-text excerpt of at most `max_chars` characters.
    #[must_use]
    pub fn plain_text_excerpt(html_or_text: &str, max_chars: usize) -> String {
        if html_or_text.trim().is_empty() || max_chars == 0 {
            return String::new();
        }
        let cleaned = Self::clean(html_or_text);
        Self::plain_text_excerpt_from_clean(&cleaned, max_chars)
    }

    /// Collapses whitespace and truncates an already-plain-text string without running HTML tag stripping.
    #[must_use]
    pub fn plain_text_truncate(plain_text: &str, max_chars: usize) -> String {
        if plain_text.trim().is_empty() || max_chars == 0 {
            return String::new();
        }
        let collapsed = plain_text.split_whitespace().collect::<Vec<_>>().join(" ");
        Self::truncate_excerpt(&collapsed, max_chars)
    }

    /// Extracts a plain-text excerpt from already-sanitized HTML, stripping all markup tags,
    /// decoding character/entity references, collapsing whitespace, and truncating at `max_chars`.
    #[must_use]
    pub fn plain_text_excerpt_from_clean(clean_html: &str, max_chars: usize) -> String {
        if clean_html.trim().is_empty() || max_chars == 0 {
            return String::new();
        }

        let mut reader = Reader::from_str(clean_html);
        reader.config_mut().check_end_names = false;

        let mut collapsed = String::new();
        let target_scan_chars = max_chars.saturating_add(96);
        let mut collected_chars = 0usize;
        let mut pending_space = false;

        loop {
            if collected_chars >= target_scan_chars {
                break;
            }
            match reader.read_event() {
                Ok(Event::Start(tag) | Event::Empty(tag)) => {
                    if Self::is_block_or_break_tag(tag.name().as_ref()) && !collapsed.is_empty() {
                        pending_space = true;
                    }
                }
                Ok(Event::End(tag)) => {
                    if Self::is_block_or_break_tag(tag.name().as_ref()) && !collapsed.is_empty() {
                        pending_space = true;
                    }
                }
                Ok(Event::Text(text)) => {
                    let decoded = xml_util::decode_text(&text);
                    collected_chars = collected_chars.saturating_add(Self::push_collapsed_segment(
                        &mut collapsed,
                        &mut pending_space,
                        &decoded,
                    ));
                }
                Ok(Event::GeneralRef(entity)) => {
                    let decoded = xml_util::decode_entity(&entity);
                    collected_chars = collected_chars.saturating_add(Self::push_collapsed_segment(
                        &mut collapsed,
                        &mut pending_space,
                        &decoded,
                    ));
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        Self::truncate_excerpt(&collapsed, max_chars)
    }

    fn push_collapsed_segment(
        collapsed: &mut String,
        pending_space: &mut bool,
        segment: &str,
    ) -> usize {
        if segment.is_empty() {
            return 0;
        }
        let starts_ws = segment.chars().next().is_some_and(char::is_whitespace);
        let ends_ws = segment.chars().last().is_some_and(char::is_whitespace);
        let mut added = 0usize;

        for (idx, word) in segment.split_whitespace().enumerate() {
            if !collapsed.is_empty() && (idx > 0 || *pending_space || starts_ws) {
                collapsed.push(' ');
                added = added.saturating_add(1);
            }
            collapsed.push_str(word);
            added = added.saturating_add(word.chars().count());
            *pending_space = false;
        }
        if ends_ws && !collapsed.is_empty() {
            *pending_space = true;
        }
        added
    }

    fn is_block_or_break_tag(name: &str) -> bool {
        matches!(
            name.as_bytes().to_ascii_lowercase().as_slice(),
            b"p" | b"br"
                | b"div"
                | b"li"
                | b"ul"
                | b"ol"
                | b"h1"
                | b"h2"
                | b"h3"
                | b"h4"
                | b"h5"
                | b"h6"
                | b"blockquote"
                | b"pre"
                | b"tr"
                | b"td"
                | b"th"
                | b"hr"
                | b"figure"
                | b"figcaption"
                | b"section"
                | b"article"
                | b"header"
                | b"footer"
        )
    }

    fn truncate_excerpt(collapsed: &str, max_chars: usize) -> String {
        let total_chars = collapsed.chars().count();
        if total_chars <= max_chars {
            return collapsed.to_owned();
        }
        if max_chars <= 1 {
            return "…".to_owned();
        }
        let budget = max_chars - 1;
        let prefix: String = collapsed.chars().take(budget).collect();
        if let Some(last_space) = prefix.rfind(' ')
            && prefix[..last_space].chars().count() >= budget * 2 / 3
        {
            format!("{}…", prefix[..last_space].trim_end())
        } else {
            format!("{}…", prefix.trim_end())
        }
    }

    /// Extracts `<title>`, `<meta name="description">` / `<meta property="og:description">`,
    /// and sanitized `content_html` from a raw HTML5 document using `ammonia` (`html5ever`) + `quick_xml`.
    #[cfg(test)]
    #[must_use]
    pub fn extract_snapshot(raw_html: &str) -> ExtractedSnapshot {
        Self::extract_snapshot_with_base(raw_html, None)
    }

    #[must_use]
    pub fn extract_snapshot_with_base(raw_html: &str, base_url: Option<&Url>) -> ExtractedSnapshot {
        // Normalize <title>, <meta>, and <time> tags through html5ever (via ammonia) so inline
        // <script>if (a < b)</script>, <svg><title>, unquoted attributes, and bare ampersands are
        // parsed per HTML5 spec.
        let mut meta_builder = ammonia::Builder::new();
        meta_builder
            .tags(HashSet::from(["title", "meta", "time"]))
            .clean_content_tags(HashSet::from([
                "script", "style", "svg", "noscript", "template", "math",
            ]))
            .add_tag_attributes("meta", &["name", "property", "content"])
            .add_tag_attributes("time", &["datetime"]);
        let normalized_meta_html = meta_builder.clean(raw_html).to_string();

        let mut acc = Self::parse_title_and_meta(&normalized_meta_html, base_url);
        let jsonld = Self::extract_jsonld_metadata(raw_html);

        let title = acc.citation_title.or(acc.title).or(acc.og_title).or(jsonld.title);
        let authors = if !acc.citation_authors.is_empty() {
            dedup_preserve_order(acc.citation_authors)
        } else if !acc.meta_authors.is_empty() {
            dedup_preserve_order(acc.meta_authors)
        } else {
            dedup_preserve_order(jsonld.authors)
        };
        let author = (!authors.is_empty()).then(|| authors.join(", "));

        let best_date = acc.citation_date.or(acc.meta_date).or(jsonld.date).or(acc.time_date);
        let published_at = best_date.and_then(|d| d.published_at);
        let release_year =
            best_date.and_then(|d| d.release_year).or_else(|| published_at.map(|dt| dt.year()));
        let doi = acc.doi.take().or(jsonld.doi);
        let isbn = acc.isbn.take().or(jsonld.isbn);

        let css_rules = semantic_html::parse_stylesheet_typographic_rules(raw_html);
        let prepared_body = semantic_html::preprocess_semantic_html(raw_html, &css_rules, true);
        let content_html = Self::sanitize_prepared_html(&prepared_body, base_url);
        let thumbnail_url =
            acc.meta_image.or_else(|| Self::extract_first_image_url_with_base(raw_html, base_url));
        let summary = acc
            .meta_desc
            .map(|desc| Self::plain_text_truncate(&desc, MAX_SUMMARY_CHARS))
            .filter(|s| !s.is_empty())
            .or_else(|| {
                Self::extract_first_paragraph(&content_html)
                    .map(|p| Self::plain_text_truncate(&p, MAX_SUMMARY_CHARS))
                    .filter(|s| !s.is_empty())
            })
            .or_else(|| {
                let fallback =
                    Self::plain_text_excerpt_from_clean(&content_html, MAX_SUMMARY_CHARS);
                (!fallback.is_empty()).then_some(fallback)
            });

        ExtractedSnapshot {
            title,
            author,
            authors,
            published_at,
            release_year,
            doi,
            isbn,
            summary,
            content_html,
            thumbnail_url,
        }
    }

    /// Scans HTML for the first valid raster `<img src="...">` URL (resolving relative URLs against `base_url`),
    /// skipping tracking pixels, inline emojis, footnote icons, and `.svg` images.
    ///
    /// Normalizes the input through `ammonia` (`html5ever`) first so `<script>`, `<style>`,
    /// `<noscript>`, `<template>`, `<svg>`, and `<math>` contents cannot spoof thumbnails or break
    /// XML parsing.
    #[must_use]
    pub fn extract_first_image_url_with_base(html: &str, base_url: Option<&Url>) -> Option<String> {
        if !html.as_bytes().windows(4).any(|w| w.eq_ignore_ascii_case(b"<img")) {
            return None;
        }
        let mut img_builder = ammonia::Builder::new();
        img_builder
            .tags(HashSet::from(["img"]))
            .clean_content_tags(HashSet::from([
                "script", "style", "svg", "noscript", "template", "math",
            ]))
            .add_tag_attributes("img", &["src", "width", "height", "class", "alt"]);
        let normalized_html = img_builder.clean(html).to_string();

        let mut reader = Reader::from_str(&normalized_html);
        reader.config_mut().check_end_names = false;

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag) | Event::Empty(tag))
                    if tag.name().as_ref().eq_ignore_ascii_case("img") =>
                {
                    if let Some(url) = Self::inspect_img_tag(&tag, base_url) {
                        return Some(url);
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }
        None
    }

    #[must_use]
    pub fn resolve_and_validate_thumbnail_url(raw: &str, base_url: Option<&Url>) -> Option<String> {
        roche_core::url_guard::resolve_and_validate_thumbnail_url(raw, base_url)
    }

    fn parse_small_dimension(raw: &str) -> bool {
        let trimmed = raw.trim().trim_end_matches("px").trim();
        trimmed.parse::<u32>().is_ok_and(|dim| dim <= 32)
    }

    fn inspect_img_tag(
        tag: &quick_xml::events::BytesStart<'_>,
        base_url: Option<&Url>,
    ) -> Option<String> {
        let mut src: Option<String> = None;
        let mut width: Option<String> = None;
        let mut height: Option<String> = None;
        let mut class_attr: Option<String> = None;
        let mut alt_attr: Option<String> = None;

        for (key, val) in xml_util::iter_attrs(tag) {
            if key.eq_ignore_ascii_case("src") {
                src = Some(val);
            } else if key.eq_ignore_ascii_case("width") {
                width = Some(val);
            } else if key.eq_ignore_ascii_case("height") {
                height = Some(val);
            } else if key.eq_ignore_ascii_case("class") {
                class_attr = Some(val);
            } else if key.eq_ignore_ascii_case("alt") {
                alt_attr = Some(val);
            }
        }

        if width.as_deref().is_some_and(Self::parse_small_dimension)
            || height.as_deref().is_some_and(Self::parse_small_dimension)
        {
            return None;
        }

        if let Some(cls) = class_attr {
            let lower = cls.to_ascii_lowercase();
            if lower.contains("wp-smiley") || lower.contains("emoji") || lower.contains("smiley") {
                return None;
            }
        }

        if alt_attr.as_deref().is_some_and(|a| matches!(a.trim(), "↩" | "↩︎")) {
            return None;
        }

        Self::resolve_and_validate_thumbnail_url(src.as_deref()?, base_url)
    }

    fn parse_title_and_meta(normalized_html: &str, base_url: Option<&Url>) -> HtmlMetaAccumulator {
        let mut reader = Reader::from_str(normalized_html);
        reader.config_mut().check_end_names = false;

        let mut acc = HtmlMetaAccumulator::default();
        let mut in_title = false;
        let mut current_buf = String::new();

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) => {
                    let name = tag.name();
                    let name_bytes = name.as_ref();
                    if name_bytes.eq_ignore_ascii_case("title") && acc.title.is_none() {
                        in_title = true;
                        current_buf.clear();
                    } else if name_bytes.eq_ignore_ascii_case("meta") {
                        Self::inspect_meta_tag(&tag, base_url, &mut acc);
                    } else if name_bytes.eq_ignore_ascii_case("time") && acc.time_date.is_none() {
                        Self::inspect_time_tag(&tag, &mut acc);
                    }
                }
                Ok(Event::Empty(tag)) => {
                    let name = tag.name();
                    let name_bytes = name.as_ref();
                    if name_bytes.eq_ignore_ascii_case("meta") {
                        Self::inspect_meta_tag(&tag, base_url, &mut acc);
                    } else if name_bytes.eq_ignore_ascii_case("time") && acc.time_date.is_none() {
                        Self::inspect_time_tag(&tag, &mut acc);
                    }
                }
                Ok(Event::Text(text)) if in_title => {
                    current_buf.push_str(&xml_util::decode_text(&text));
                }
                Ok(Event::GeneralRef(entity)) if in_title => {
                    current_buf.push_str(&xml_util::decode_entity(&entity));
                }
                Ok(Event::End(tag))
                    if in_title && tag.name().as_ref().eq_ignore_ascii_case("title") =>
                {
                    in_title = false;
                    let trimmed = current_buf.trim();
                    if !trimmed.is_empty() {
                        acc.title = Some(trimmed.to_owned());
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        acc
    }

    fn inspect_time_tag(tag: &quick_xml::events::BytesStart<'_>, acc: &mut HtmlMetaAccumulator) {
        if let Some(dt_attr) = xml_util::find_attr_ignore_ascii_case(tag, "datetime")
            && let Some(parsed) = parse_publication_date_str(&dt_attr)
        {
            acc.time_date = Some(parsed);
        }
    }

    fn extract_jsonld_metadata(raw_html: &str) -> JsonLdMetadata {
        let mut out = JsonLdMetadata::default();
        if !raw_html.as_bytes().windows(7).any(|w| w.eq_ignore_ascii_case(b"ld+json")) {
            return out;
        }
        let lower = raw_html.to_ascii_lowercase();
        let mut cursor = 0usize;
        let mut inspected = 0usize;
        while inspected < 12
            && let Some(rel_start) = lower[cursor..].find("<script")
        {
            let tag_start = cursor + rel_start;
            let Some(rel_gt) = lower[tag_start..].find('>') else {
                break;
            };
            let open_end = tag_start + rel_gt + 1;
            let open_tag = &lower[tag_start..open_end];
            let Some(rel_close) = lower[open_end..].find("</script") else {
                break;
            };
            let body_end = open_end + rel_close;
            cursor = body_end + 8;

            if !open_tag.contains("ld+json") {
                continue;
            }
            inspected += 1;
            let json_text = raw_html[open_end..body_end].trim();
            if json_text.is_empty() || json_text.len() > 256_000 {
                continue;
            }
            if let Ok(val) = serde_json::from_str::<serde_json::Value>(json_text) {
                Self::collect_jsonld_value(&val, &mut out, 0);
            }
        }
        out
    }

    fn collect_jsonld_value(val: &serde_json::Value, out: &mut JsonLdMetadata, depth: usize) {
        if depth > 4 {
            return;
        }
        match val {
            serde_json::Value::Array(arr) => {
                for item in arr.iter().take(24) {
                    Self::collect_jsonld_value(item, out, depth + 1);
                }
            }
            serde_json::Value::Object(map) => {
                if let Some(graph) = map.get("@graph") {
                    Self::collect_jsonld_value(graph, out, depth + 1);
                }
                if out.title.is_none() {
                    for key in ["headline", "name"] {
                        if let Some(raw_title) = map.get(key).and_then(serde_json::Value::as_str) {
                            let clean = raw_title.split_whitespace().collect::<Vec<_>>().join(" ");
                            if !clean.is_empty() {
                                out.title = Some(clean);
                                break;
                            }
                        }
                    }
                }
                if out.date.is_none() {
                    for key in ["datePublished", "dateCreated"] {
                        if let Some(raw_date) = map.get(key).and_then(serde_json::Value::as_str)
                            && let Some(parsed) = parse_publication_date_str(raw_date)
                        {
                            out.date = Some(parsed);
                            break;
                        }
                    }
                }
                if out.authors.is_empty()
                    && let Some(author_val) = map.get("author")
                {
                    collect_jsonld_authors(author_val, &mut out.authors);
                }
                if out.isbn.is_none()
                    && let Some(raw_isbn) = map.get("isbn").and_then(serde_json::Value::as_str)
                {
                    out.isbn = normalize_isbn(raw_isbn);
                }
                if out.doi.is_none() {
                    for key in ["doi", "identifier", "sameAs"] {
                        if let Some(val) = map.get(key)
                            && let Some(doi) = extract_jsonld_doi(val)
                        {
                            out.doi = Some(doi);
                            break;
                        }
                    }
                }
            }
            _ => {}
        }
    }

    fn extract_first_paragraph(clean_body_html: &str) -> Option<String> {
        let mut reader = Reader::from_str(clean_body_html);
        reader.config_mut().check_end_names = false;

        let mut in_p = false;
        let mut current_buf = String::new();

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) if tag.name().as_ref().eq_ignore_ascii_case("p") => {
                    if !in_p {
                        in_p = true;
                        current_buf.clear();
                    }
                }
                Ok(Event::Start(tag) | Event::Empty(tag))
                    if in_p && Self::is_block_or_break_tag(tag.name().as_ref()) =>
                {
                    current_buf.push(' ');
                }
                Ok(Event::Text(text)) if in_p => {
                    current_buf.push_str(&xml_util::decode_text(&text));
                }
                Ok(Event::GeneralRef(entity)) if in_p => {
                    current_buf.push_str(&xml_util::decode_entity(&entity));
                }
                Ok(Event::End(tag)) if in_p && tag.name().as_ref().eq_ignore_ascii_case("p") => {
                    in_p = false;
                    let trimmed = current_buf.trim();
                    if !trimmed.is_empty() {
                        return Some(trimmed.to_owned());
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        None
    }

    fn inspect_meta_tag(
        tag: &quick_xml::events::BytesStart<'_>,
        base_url: Option<&Url>,
        acc: &mut HtmlMetaAccumulator,
    ) {
        let mut attr_name: Option<String> = None;
        let mut attr_content: Option<String> = None;

        for (key, val) in xml_util::iter_attrs(tag) {
            if key.eq_ignore_ascii_case("name") || key.eq_ignore_ascii_case("property") {
                attr_name = Some(val);
            } else if key.eq_ignore_ascii_case("content") {
                attr_content = Some(val);
            }
        }

        let Some(name) = attr_name else { return };
        let Some(content) = attr_content.map(|c| c.trim().to_owned()).filter(|c| !c.is_empty())
        else {
            return;
        };

        let lower = name.to_ascii_lowercase();
        match lower.as_str() {
            "citation_title" if acc.citation_title.is_none() => {
                acc.citation_title = Some(content);
            }
            "og:title" if acc.og_title.is_none() => {
                acc.og_title = Some(content);
            }
            "citation_abstract" | "description" | "og:description" | "twitter:description"
                if acc.meta_desc.is_none() =>
            {
                acc.meta_desc = Some(content);
            }
            "citation_author" => {
                if !content.starts_with("http://") && !content.starts_with("https://") {
                    acc.citation_authors.push(content);
                }
            }
            "author" | "article:author" | "dc.creator" | "dcterms.creator" | "byl" => {
                if !content.starts_with("http://") && !content.starts_with("https://") {
                    let parsed = split_author_names(&content);
                    if parsed.is_empty() {
                        acc.meta_authors.push(content);
                    } else {
                        acc.meta_authors.extend(parsed);
                    }
                }
            }
            "citation_publication_date" | "citation_date" | "citation_online_date"
                if acc.citation_date.is_none() =>
            {
                acc.citation_date = parse_publication_date_str(&content);
            }
            "article:published_time"
            | "og:article:published_time"
            | "pubdate"
            | "publishdate"
            | "publish_date"
            | "datepublished"
            | "dc.date"
            | "dc.date.issued"
            | "dcterms.issued"
            | "dcterms.created"
            | "sailthru.date"
            | "date"
                if acc.meta_date.is_none() =>
            {
                acc.meta_date = parse_publication_date_str(&content);
            }
            "citation_doi" | "prism.doi" | "doi" if acc.doi.is_none() => {
                acc.doi = normalize_doi(&content);
            }
            "dc.identifier" | "dcterms.identifier" if acc.doi.is_none() => {
                acc.doi = normalize_doi(&content).or_else(|| extract_doi_from_text(&content));
            }
            "citation_isbn" | "book:isbn" | "isbn" if acc.isbn.is_none() => {
                acc.isbn = normalize_isbn(&content);
            }
            "og:image" | "twitter:image" if acc.meta_image.is_none() => {
                if let Some(valid_url) =
                    Self::resolve_and_validate_thumbnail_url(&content, base_url)
                {
                    acc.meta_image = Some(valid_url);
                }
            }
            _ => {}
        }
    }

    /// Discovers candidate RSS/Atom/`YouTube` feed URLs from an HTML document, ordered by confidence:
    /// 1. `<link rel="alternate" type="application/rss+xml|application/atom+xml|..." href="...">`
    /// 2. `YouTube` `<meta itemprop="channelId" content="UC...">` or canonical `/channel/UC...` link
    /// 3. `<a href="...">` links pointing to feed paths (`/feed`, `/rss.xml`, `/atom.xml`, `/index.xml`)
    /// 4. Standard origin feed paths (`/feed.xml`, `/rss.xml`, `/atom.xml`, `/index.xml`, `/feed`, `/rss`)
    ///
    /// Every candidate is validated through [`validate_public_url`] to prevent SSRF.
    #[must_use]
    pub fn discover_feed_candidates(raw_html: &str, base_url: &Url) -> Vec<Url> {
        let mut disc_builder = ammonia::Builder::new();
        disc_builder
            .tags(HashSet::from(["link", "meta", "a"]))
            .clean_content_tags(HashSet::from([
                "script", "style", "svg", "noscript", "template", "math",
            ]))
            .add_tag_attributes("link", &["rel", "type", "href"])
            .add_tag_attributes("meta", &["itemprop", "property", "name", "content"])
            .add_tag_attributes("a", &["href"]);
        let normalized_html = disc_builder.clean(raw_html).to_string();

        let mut alt_links = Vec::new();
        let mut yt_links = Vec::new();
        let mut anchor_links = Vec::new();

        let mut reader = Reader::from_str(&normalized_html);
        reader.config_mut().check_end_names = false;

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag) | Event::Empty(tag)) => {
                    let name = tag.name();
                    let name_bytes = name.as_ref();
                    if name_bytes.eq_ignore_ascii_case("link") {
                        Self::collect_link_feed_candidate(
                            &tag,
                            base_url,
                            &mut alt_links,
                            &mut yt_links,
                        );
                    } else if name_bytes.eq_ignore_ascii_case("meta") {
                        Self::collect_meta_youtube_candidate(&tag, &mut yt_links);
                    } else if name_bytes.eq_ignore_ascii_case("a") {
                        Self::collect_anchor_feed_candidate(&tag, base_url, &mut anchor_links);
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        let mut out = Vec::new();
        let mut seen = HashSet::new();
        let mut push_valid = |u: Url| {
            if validate_public_url(&u).is_ok() && seen.insert(u.as_str().to_owned()) {
                out.push(u);
            }
        };

        for u in yt_links.into_iter().chain(alt_links).chain(anchor_links) {
            push_valid(u);
        }

        if !is_youtube_host(base_url.host_str()) {
            for rel_path in ["/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/feed", "/rss"] {
                if let Ok(u) = base_url.join(rel_path) {
                    push_valid(u);
                }
            }
        }

        out.truncate(8);
        out
    }

    fn collect_link_feed_candidate(
        tag: &quick_xml::events::BytesStart<'_>,
        base_url: &Url,
        alt_links: &mut Vec<Url>,
        yt_links: &mut Vec<Url>,
    ) {
        let mut rel: Option<String> = None;
        let mut mime: Option<String> = None;
        let mut href: Option<String> = None;
        for (key, val) in xml_util::iter_attrs(tag) {
            if key.eq_ignore_ascii_case("rel") {
                rel = Some(val);
            } else if key.eq_ignore_ascii_case("type") {
                mime = Some(val);
            } else if key.eq_ignore_ascii_case("href") {
                href = Some(val);
            }
        }
        let Some(raw_href) = href.map(|h| h.trim().to_owned()).filter(|h| !h.is_empty()) else {
            return;
        };
        let Ok(resolved) = base_url.join(&raw_href) else {
            return;
        };

        if let Some(cid) = extract_youtube_channel_id(resolved.as_str())
            && let Ok(yt_url) = youtube_feed_url_for_channel_id(&cid)
        {
            yt_links.push(yt_url);
            return;
        }

        let is_alt = rel
            .as_deref()
            .is_some_and(|r| r.split_whitespace().any(|tok| tok.eq_ignore_ascii_case("alternate")));
        if !is_alt {
            return;
        }
        let mime_lower = mime.unwrap_or_default().to_ascii_lowercase();
        let is_feed_mime = mime_lower.contains("rss")
            || mime_lower.contains("atom")
            || mime_lower.contains("rdf")
            || mime_lower == "application/xml"
            || mime_lower == "text/xml";
        let path_lower = resolved.path().to_ascii_lowercase();
        if is_feed_mime
            && !mime_lower.contains("oembed")
            && !mime_lower.contains("opensearch")
            && !path_lower.contains("/comments/feed")
        {
            alt_links.push(resolved);
        }
    }

    fn collect_meta_youtube_candidate(
        tag: &quick_xml::events::BytesStart<'_>,
        yt_links: &mut Vec<Url>,
    ) {
        let mut key_name: Option<String> = None;
        let mut content: Option<String> = None;
        for (key, val) in xml_util::iter_attrs(tag) {
            if key.eq_ignore_ascii_case("itemprop")
                || key.eq_ignore_ascii_case("property")
                || key.eq_ignore_ascii_case("name")
            {
                key_name = Some(val);
            } else if key.eq_ignore_ascii_case("content") {
                content = Some(val);
            }
        }
        let Some(k) = key_name else { return };
        let Some(c) = content else { return };
        if (k.eq_ignore_ascii_case("channelId") || k.eq_ignore_ascii_case("og:url"))
            && let Some(cid) = extract_youtube_channel_id(c.trim())
            && let Ok(yt_url) = youtube_feed_url_for_channel_id(&cid)
        {
            yt_links.push(yt_url);
        }
    }

    fn collect_anchor_feed_candidate(
        tag: &quick_xml::events::BytesStart<'_>,
        base_url: &Url,
        anchor_links: &mut Vec<Url>,
    ) {
        let href = xml_util::find_attr_ignore_ascii_case(tag, "href");
        let Some(raw_href) = href.map(|h| h.trim().to_owned()).filter(|h| !h.is_empty()) else {
            return;
        };
        let Ok(resolved) = base_url.join(&raw_href) else {
            return;
        };
        let path = resolved.path().to_ascii_lowercase();
        let trimmed_path = path.trim_end_matches('/');
        let has_rss_ext = std::path::Path::new(&path)
            .extension()
            .is_some_and(|ext| ext.eq_ignore_ascii_case("rss"));
        let looks_like_feed = has_rss_ext
            || path.ends_with("/rss.xml")
            || path.ends_with("/atom.xml")
            || path.ends_with("/feed.xml")
            || path.ends_with("/index.xml")
            || trimmed_path.ends_with("/feed")
            || trimmed_path.ends_with("/rss")
            || trimmed_path.ends_with("/atom");
        if looks_like_feed && !path.contains("/comments/feed") {
            anchor_links.push(resolved);
        }
    }
}

enum MathMlAttrFilter<'a> {
    Keep(Cow<'a, str>),
    Drop,
    NotHandled,
}

#[derive(Default)]
struct JsonLdMetadata {
    title: Option<String>,
    date: Option<ParsedPublicationDate>,
    authors: Vec<String>,
    doi: Option<String>,
    isbn: Option<String>,
}

fn is_safe_mathml_length(raw: &str) -> bool {
    if matches!(
        raw,
        "thin"
            | "medium"
            | "thick"
            | "verythinmathspace"
            | "thinmathspace"
            | "mediummathspace"
            | "thickmathspace"
            | "verythickmathspace"
            | "0"
    ) {
        return true;
    }
    if raw.is_empty() || raw.len() > 16 {
        return false;
    }
    let unit = ["em", "ex", "px", "pt", "%"].iter().find(|&&u| raw.ends_with(u));
    let Some(&u) = unit else {
        return false;
    };
    let num_str = &raw[..raw.len() - u.len()];
    let Ok(val) = num_str.parse::<f32>() else {
        return false;
    };
    if !val.is_finite() {
        return false;
    }
    match u {
        "em" | "ex" => (-4.0..=12.0).contains(&val),
        "px" | "pt" => (-32.0..=96.0).contains(&val),
        "%" => (0.0..=300.0).contains(&val),
        _ => false,
    }
}

fn extract_jsonld_doi(val: &serde_json::Value) -> Option<String> {
    match val {
        serde_json::Value::String(s) => normalize_doi(s).or_else(|| extract_doi_from_text(s)),
        serde_json::Value::Object(obj) => obj
            .get("value")
            .and_then(serde_json::Value::as_str)
            .and_then(|s| normalize_doi(s).or_else(|| extract_doi_from_text(s))),
        serde_json::Value::Array(arr) => arr.iter().take(16).find_map(extract_jsonld_doi),
        _ => None,
    }
}

fn collect_jsonld_authors(val: &serde_json::Value, out: &mut Vec<String>) {
    match val {
        serde_json::Value::String(s) => {
            let trimmed = s.trim();
            if !trimmed.is_empty()
                && !trimmed.starts_with("http://")
                && !trimmed.starts_with("https://")
            {
                out.extend(split_author_names(trimmed));
            }
        }
        serde_json::Value::Object(obj) => {
            if let Some(name) = obj.get("name").and_then(serde_json::Value::as_str) {
                let trimmed = name.trim();
                if !trimmed.is_empty() {
                    out.push(trimmed.to_owned());
                }
            }
        }
        serde_json::Value::Array(arr) => {
            for item in arr.iter().take(24) {
                collect_jsonld_authors(item, out);
            }
        }
        _ => {}
    }
}

fn dedup_preserve_order(items: Vec<String>) -> Vec<String> {
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    for item in items {
        let trimmed = item.trim();
        if !trimmed.is_empty() && seen.insert(trimmed.to_lowercase()) {
            out.push(trimmed.to_owned());
        }
    }
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_html_sanitizer_strips_scripts() {
        let clean =
            HtmlSanitizer::clean("<html><body><script>alert(1)</script><p>Hello</p></body></html>");
        assert!(!clean.contains("<script>"));
        assert!(clean.contains("<p>Hello</p>"));
    }

    #[test]
    fn test_extract_snapshot() {
        let html = r#"<!DOCTYPE html>
        <html>
          <head>
            <script>if (1 < 2 && 3 > 1) { console.log("<title>Fake</title>"); }</script>
            <title>Deep Systems &amp; Storage</title>
            <meta name="description" content="A guide to crash-safe SQLite & WAL." />
          </head>
          <body>
            <svg><title>Icon Title</title></svg>
            <script>evil()</script>
            <article><p>First paragraph body.</p></article>
          </body>
        </html>"#;

        let snap = HtmlSanitizer::extract_snapshot(html);
        assert_eq!(snap.title.as_deref(), Some("Deep Systems & Storage"));
        assert_eq!(snap.summary.as_deref(), Some("A guide to crash-safe SQLite & WAL."));
        assert!(snap.content_html.contains("<p>First paragraph body.</p>"));
        assert!(!snap.content_html.contains("<script>"));
        assert!(!snap.content_html.contains("Deep Systems"));
        assert!(!snap.content_html.contains("Icon Title"));
    }

    #[test]
    fn test_plain_text_excerpt_strips_html_decodes_entities_and_truncates() {
        let html = r#"<figure><img src="https://example.com/chart.png" alt="chart"></figure><p>We had some <strong>big drawdowns</strong> &amp; volatility &mdash; in the market.</p><p>Second paragraph here.</p>"#;
        let excerpt = HtmlSanitizer::plain_text_excerpt(html, 320);
        assert_eq!(
            excerpt,
            "We had some big drawdowns & volatility \u{2014} in the market. Second paragraph here."
        );
        assert!(!excerpt.contains('<'));
        assert!(!excerpt.contains("&amp;"));

        let truncated = HtmlSanitizer::plain_text_excerpt(html, 40);
        assert!(truncated.ends_with('…'));
        assert!(truncated.chars().count() <= 40);

        // Heavily indented HTML with > 600 whitespace characters before text should not truncate early
        let indented_html = format!(
            "<div>\n{}\n<p>{}\n<br/>Second line after break</p></div>",
            " ".repeat(600),
            "word ".repeat(50)
        );
        let indented_excerpt = HtmlSanitizer::plain_text_excerpt(&indented_html, 320);
        assert!(indented_excerpt.contains("Second line after break"));
        assert!(indented_excerpt.chars().count() > 250);

        let snap = HtmlSanitizer::extract_snapshot(
            "<html><body><p>First line<br>Second line</p></body></html>",
        );
        assert_eq!(snap.summary.as_deref(), Some("First line Second line"));
    }

    #[test]
    fn test_extract_first_image_url_with_base_resolves_relative_and_skips_emojis_svg_and_small_icons()
     {
        let base = Url::parse("https://blog.example.com/posts/2026/predicate-logic/").unwrap();
        let html = r#"
            <p>Emoji <img src="https://s.w.org/images/core/emoji/17.0.2/72x72/21a9.png" alt="↩"> and
            smiley <img class="wp-smiley" src="https://blog.example.com/smile.png"> and
            tiny <img src="/icon.png" width="24" height="24"> and
            vector <img src="/diagram.svg"> and
            real <img src="img/quantifier-breakdown.png"></p>
        "#;
        let extracted = HtmlSanitizer::extract_first_image_url_with_base(html, Some(&base));
        assert_eq!(
            extracted.as_deref(),
            Some(
                "https://blog.example.com/posts/2026/predicate-logic/img/quantifier-breakdown.png"
            )
        );

        let root_rel = HtmlSanitizer::extract_first_image_url_with_base(
            r#"<img src="/static/hero.webp">"#,
            Some(&base),
        );
        assert_eq!(root_rel.as_deref(), Some("https://blog.example.com/static/hero.webp"));

        // Images inside <script>, <noscript>, <template>, <style>, <svg>, <math> must be ignored,
        // and `<` inside <script> must not abort extraction of the real image below.
        let tricky_html = r#"
            <script>if (a < b) { const fake = '<img src="https://evil.example.com/script.jpg">'; }</script>
            <noscript><img src="https://tracker.example.com/beacon.png"></noscript>
            <template><img src="https://evil.example.com/template.png"></template>
            <style>.x { content: "<img src='https://evil.example.com/style.png'>"; }</style>
            <p><img src="https://blog.example.com/real-hero.jpg" width="800" height="600"></p>
        "#;
        let extracted_safe =
            HtmlSanitizer::extract_first_image_url_with_base(tricky_html, Some(&base));
        assert_eq!(extracted_safe.as_deref(), Some("https://blog.example.com/real-hero.jpg"));
    }

    #[test]
    fn test_extract_first_image_url_handles_mixed_case_and_image_free_html() {
        let base = Url::parse("https://blog.example.com/posts/demo/").unwrap();
        assert!(HtmlSanitizer::extract_first_image_url_with_base("", Some(&base)).is_none());
        assert!(HtmlSanitizer::extract_first_image_url_with_base("<p>", Some(&base)).is_none());
        assert!(
            HtmlSanitizer::extract_first_image_url_with_base(
                "<article><p>No images in this article at all.</p></article>",
                Some(&base),
            )
            .is_none()
        );
        assert_eq!(
            HtmlSanitizer::extract_first_image_url_with_base(
                r#"<p><Img src="https://cdn.example.com/mixed.jpg" width="640"></p>"#,
                Some(&base),
            )
            .as_deref(),
            Some("https://cdn.example.com/mixed.jpg")
        );
        assert_eq!(
            HtmlSanitizer::extract_first_image_url_with_base(
                r#"<p><iMg SRC="/hero.webp"></p>"#,
                Some(&base),
            )
            .as_deref(),
            Some("https://blog.example.com/hero.webp")
        );
    }

    #[test]
    fn test_clean_with_base_blocks_private_tailnet_and_exotic_schemes() {
        let base = Url::parse("https://blog.example.com/posts/demo/").unwrap();
        let dirty = r##"
            <p><img src="https://secret.tail1234.ts.net/pixel.png"></p>
            <p><img src="http://127.0.0.1:8080/internal.png"></p>
            <p><img src="mailto:user@example.com"></p>
            <p><img src="/assets/diagram.png"></p>
            <p><a href="irc://irc.example.com/room">IRC</a></p>
            <p><a href="https://nas.local/admin">Local</a></p>
            <p><a href="mailto:author@example.com">Email</a></p>
            <p><a href="#fn1">Footnote</a></p>
            <p><a href="/about">About</a></p>
        "##;
        let cleaned = HtmlSanitizer::clean_with_base(dirty, Some(&base));
        assert!(!cleaned.contains("tail1234.ts.net"));
        assert!(!cleaned.contains("127.0.0.1"));
        assert!(!cleaned.contains("irc://"));
        assert!(!cleaned.contains("nas.local"));
        assert!(!cleaned.contains(r#"src="mailto:"#));
        assert!(cleaned.contains("https://blog.example.com/assets/diagram.png"));
        assert!(cleaned.contains("mailto:author@example.com"));
        assert!(cleaned.contains("https://blog.example.com/posts/demo/#fn1"));
        assert!(cleaned.contains("https://blog.example.com/about"));
        assert!(
            HtmlSanitizer::clean(r##"<a href="#fn1">Footnote</a>"##).contains(r##"href="#fn1""##)
        );

        // Private base_url is ignored so relative img/a URLs cannot rewrite to Tailnet/loopback
        let private_base = Url::parse("https://roche.tail1234.ts.net/items/1").unwrap();
        let cleaned_priv = HtmlSanitizer::clean_with_base(
            r#"<img src="/secret.png"><a href="/logout">Out</a>"#,
            Some(&private_base),
        );
        assert!(!cleaned_priv.contains("secret.png"));
        assert!(!cleaned_priv.contains("logout"));
    }

    #[test]
    fn test_clean_and_extract_snapshot_preserve_byline_italics_inline_styles_and_idempotency() {
        let base = Url::parse("https://proofsandprompts.com/2026/11/22/a-mathematical-community/")
            .unwrap();
        let rss_html = r#"
            <p class="author-line wp-block-paragraph"><a href="https://www.jm-kantor.fr/">Jean-Michel Kantor</a>, Emeritus from IMJ</p>
            <p class="pp-in-reply-to wp-block-paragraph"><strong>In reply to</strong> <a href="https://proofsandprompts.com/2026/11/03/slow-maths/">Slow Maths</a></p>
            <p>Inline <span style="font-style: italic">italics</span>, <span style="font-weight: 700">bold</span>, <span style="text-decoration: underline">under</span>, <span style="text-decoration: line-through">strike</span><span style="display:none">hidden</span>.</p>
            <ol><li id="fn-1">Footnote text</li></ol>
        "#;
        let clean1 = HtmlSanitizer::clean_with_base(rss_html, Some(&base));
        assert!(clean1.contains(
            "<p><em><a href=\"https://www.jm-kantor.fr/\" rel=\"noopener noreferrer\">Jean-Michel Kantor</a>, Emeritus from IMJ</em></p>"
        ));
        assert!(clean1.contains("<p><small><strong>In reply to</strong>"));
        assert!(clean1.contains("<em>italics</em>"));
        assert!(clean1.contains("<strong>bold</strong>"));
        assert!(clean1.contains("<u>under</u>"));
        assert!(clean1.contains("<s>strike</s>"));
        assert!(!clean1.contains("hidden"));
        assert!(clean1.contains("id=\"prose-fn-1\""));

        // Running clean_with_base a second time (as Web reader does) must be strictly idempotent.
        let clean2 = HtmlSanitizer::clean_with_base(&clean1, Some(&base));
        assert_eq!(clean1, clean2);

        // Full webpage extraction lifts site <style> rules, picks .entry-content over sidebar <article> comments,
        // and strips Jetpack share widgets and chrome.
        let full_page = r#"<!DOCTYPE html>
        <html>
          <head>
            <title>A mathematical community? – Proofs and Prompts</title>
            <style>.custom-dek { font-style: italic; } .author-line { font-style: italic; }</style>
          </head>
          <body>
            <header><nav><a href="/">Home</a></nav></header>
            <main>
              <div class="entry-content wp-block-post-content">
                <p class="author-line wp-block-paragraph"><a href="https://www.jm-kantor.fr/">Jean-Michel Kantor</a>, Emeritus</p>
                <p class="custom-dek">A reply to Isabelle Gallagher.</p>
                <p>Body text.</p>
                <div class="sharedaddy"><h3>Share this:</h3><a href="/share">Mastodon</a></div>
              </div>
              <aside>
                <ul class="wp-block-latest-comments">
                  <li class="wp-block-latest-comments__comment"><article><p>Sidebar comment noise</p></article></li>
                </ul>
              </aside>
            </main>
          </body>
        </html>"#;
        let snap = HtmlSanitizer::extract_snapshot_with_base(full_page, Some(&base));
        assert!(snap.content_html.contains("<em><a href=\"https://www.jm-kantor.fr/\""));
        assert!(snap.content_html.contains("<p><em>A reply to Isabelle Gallagher.</em></p>"));
        assert!(snap.content_html.contains("<p>Body text.</p>"));
        assert!(!snap.content_html.contains("Sidebar comment noise"));
        assert!(!snap.content_html.contains("Share this:"));
    }

    #[test]
    fn test_extract_snapshot_extracts_bibliographic_citation_jsonld_and_time_metadata() {
        let scholar_base = Url::parse("https://journal.example.org/papers/42").unwrap();
        let scholar_html = r#"<!DOCTYPE html>
        <html>
          <head>
            <title>Journal Wrapper Title</title>
            <meta name="citation_title" content="Integer multiplication in time O(n log n)" />
            <meta name="citation_author" content="David Harvey" />
            <meta name="citation_author" content="Joris van der Hoeven" />
            <meta name="citation_publication_date" content="2021/03/15" />
            <meta name="citation_doi" content="https://doi.org/10.4007/ANNALS.2021.193.2.4" />
            <meta name="citation_isbn" content="978-0-306-40615-7" />
            <meta name="citation_abstract" content="We prove that n-bit integers can be multiplied in O(n log n) bit operations." />
          </head>
          <body><article><p>Paper landing page.</p></article></body>
        </html>"#;
        let snap = HtmlSanitizer::extract_snapshot_with_base(scholar_html, Some(&scholar_base));
        assert_eq!(snap.title.as_deref(), Some("Integer multiplication in time O(n log n)"));
        assert_eq!(snap.authors, vec!["David Harvey", "Joris van der Hoeven"]);
        assert_eq!(snap.author.as_deref(), Some("David Harvey, Joris van der Hoeven"));
        assert_eq!(snap.release_year, Some(2021));
        assert_eq!(
            snap.published_at.map(|dt| dt.format("%Y-%m-%d").to_string()).as_deref(),
            Some("2021-03-15")
        );
        assert_eq!(snap.doi.as_deref(), Some("10.4007/annals.2021.193.2.4"));
        assert_eq!(snap.isbn.as_deref(), Some("9780306406157"));
        assert!(snap.summary.as_deref().unwrap_or("").contains("O(n log n)"));

        let ld_base = Url::parse("https://blog.example.org/posts/distributed-clocks").unwrap();
        let ld_html = r#"<!DOCTYPE html>
        <html>
          <head>
            <title>Logical Clocks</title>
            <script type="application/ld+json">
              {
                "@context": "https://schema.org",
                "@graph": [
                  {
                    "@type": "ScholarlyArticle",
                    "headline": "Time, Clocks, and the Ordering of Events",
                    "author": [{"@type": "Person", "name": "Leslie Lamport"}],
                    "datePublished": "1978-07-01",
                    "identifier": "doi:10.1145/359545.359563"
                  }
                ]
              }
            </script>
          </head>
          <body>
            <article>
              <time datetime="1978-07-01">July 1, 1978</time>
              <p>Distributed systems essay body.</p>
            </article>
          </body>
        </html>"#;
        let ld_snap = HtmlSanitizer::extract_snapshot_with_base(ld_html, Some(&ld_base));
        assert_eq!(ld_snap.authors, vec!["Leslie Lamport"]);
        assert_eq!(ld_snap.release_year, Some(1978));
        assert_eq!(
            ld_snap.published_at.map(|dt| dt.format("%Y-%m-%d").to_string()).as_deref(),
            Some("1978-07-01")
        );
        assert_eq!(ld_snap.doi.as_deref(), Some("10.1145/359545.359563"));
    }

    #[test]
    fn test_clean_preserves_safe_mathml_and_strips_annotations_and_scripts() {
        let dirty = r#"
            <p>Complexity <math class="ltx_Math" display="inline" href="javascript:alert(1)">
              <semantics>
                <mrow><mi>o</mi><mo stretchy="false">(</mo><mi>n</mi><mo lspace="0.167em">log</mo><mi>n</mi><mo stretchy="false">)</mo></mrow>
                <annotation-xml encoding="MathML-Content"><ci>secret</ci></annotation-xml>
                <annotation encoding="application/x-tex">o(n\log n)</annotation>
              </semantics>
              <script>alert(1)</script>
            </math>.</p>
            <figure class="paper-equation"><math display="block"><mfrac><msup><mi>x</mi><mn>2</mn></msup><mn>2</mn></mfrac></math><span class="paper-eqno">(1)</span></figure>
        "#;
        let cleaned = HtmlSanitizer::clean(dirty);
        assert!(cleaned.contains("<math display=\"inline\">"));
        assert!(cleaned.contains("<mi>o</mi>"));
        assert!(cleaned.contains("<mo stretchy=\"false\">(</mo>"));
        assert!(cleaned.contains("<figure class=\"paper-equation\"><math display=\"block\">"));
        assert!(cleaned.contains("<span class=\"paper-eqno\">(1)</span>"));
        assert!(!cleaned.contains("javascript:"));
        assert!(!cleaned.contains("alert(1)"));
        assert!(!cleaned.contains("annotation"));
        assert!(!cleaned.contains("secret"));
        assert!(!cleaned.contains(r"o(n\log n)"));
        // Idempotent
        assert_eq!(cleaned, HtmlSanitizer::clean(&cleaned));
    }

    #[test]
    fn test_clean_clamps_mathml_attributes_and_strips_embedded_containers() {
        let dirty = r#"
            <p>Safe <math display="block">
              <mspace width="0.167em"></mspace>
              <mspace width="verythickmathspace"></mspace>
              <mo lspace="verythinmathspace">+</mo>
              <mspace width="99999em"></mspace>
              <mspace width="1000px"></mspace>
              <mi mathvariant="normal">log</mi>
              <mi mathvariant="javascript:alert(1)">x</mi>
              <mtable><mtr><mtd columnspan="2">a</mtd><mtd columnspan="999">b</mtd></mtr></mtable>
            </math></p>
            <math display="evil"><mi>y</mi></math>
            <iframe src="https://evil.example">iframe_secret</iframe>
            <textarea>textarea_secret</textarea>
            <object data="https://evil.example">object_secret</object>
        "#;
        let cleaned = HtmlSanitizer::clean(dirty);
        assert!(cleaned.contains(r#"display="block""#));
        assert!(cleaned.contains(r#"width="0.167em""#));
        assert!(cleaned.contains(r#"width="verythickmathspace""#));
        assert!(cleaned.contains(r#"lspace="verythinmathspace""#));
        assert!(cleaned.contains(r#"mathvariant="normal""#));
        assert!(cleaned.contains(r#"columnspan="2""#));
        assert!(!cleaned.contains("99999em"));
        assert!(!cleaned.contains("1000px"));
        assert!(!cleaned.contains(r#"columnspan="999""#));
        assert!(!cleaned.contains(r#"display="evil""#));
        assert!(!cleaned.contains("javascript:"));
        assert!(!cleaned.contains("iframe_secret"));
        assert!(!cleaned.contains("textarea_secret"));
        assert!(!cleaned.contains("object_secret"));
    }

    #[test]
    fn test_extract_snapshot_jsonld_headline_array_identifier_and_same_as_doi() {
        let base = Url::parse("https://journal.example.org/p/99").unwrap();
        let html = r#"<!DOCTYPE html>
        <html>
          <head>
            <script type="application/ld+json">
              {
                "@context": "https://schema.org",
                "@type": "ScholarlyArticle",
                "headline": "Fast Matrix Multiplication Revisited",
                "author": [{"@type": "Person", "name": "Volker Strassen"}],
                "datePublished": "2024-05-10",
                "identifier": ["urn:custom:1", {"@type": "PropertyValue", "value": "https://doi.org/10.1000/array-doi"}],
                "sameAs": "https://doi.org/10.1000/fallback-doi"
              }
            </script>
          </head>
          <body><article><p>Article body text.</p></article></body>
        </html>"#;
        let snap = HtmlSanitizer::extract_snapshot_with_base(html, Some(&base));
        assert_eq!(snap.title.as_deref(), Some("Fast Matrix Multiplication Revisited"));
        assert_eq!(snap.authors, vec!["Volker Strassen"]);
        assert_eq!(snap.doi.as_deref(), Some("10.1000/array-doi"));

        let same_as_html = r#"<!DOCTYPE html>
        <html><head><script type="application/ld+json">
          {"@type": "Article", "name": "SameAs Fallback", "sameAs": ["https://doi.org/10.1000/sameas-doi"]}
        </script></head><body><article><p>Body.</p></article></body></html>"#;
        let snap2 = HtmlSanitizer::extract_snapshot_with_base(same_as_html, Some(&base));
        assert_eq!(snap2.title.as_deref(), Some("SameAs Fallback"));
        assert_eq!(snap2.doi.as_deref(), Some("10.1000/sameas-doi"));
    }
}
