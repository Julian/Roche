use std::{
    borrow::Cow,
    collections::{HashMap, HashSet},
};

use quick_xml::{Reader, events::Event};
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
    pub summary: Option<String>,
    pub content_html: String,
    pub thumbnail_url: Option<String>,
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
        builder.add_clean_content_tags(["head", "title", "noscript", "template", "svg", "math"]);
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
            Self::filter_sanitized_url_attr(element, attribute, value, filter_base.as_ref())
        });
        builder.clean(html).to_string()
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
        // Normalize <title> and <meta> tags through html5ever (via ammonia) so inline <script>if (a < b)</script>,
        // <svg><title>, unquoted attributes, and bare ampersands are parsed per HTML5 spec.
        let mut meta_builder = ammonia::Builder::new();
        meta_builder
            .tags(HashSet::from(["title", "meta"]))
            .clean_content_tags(HashSet::from([
                "script", "style", "svg", "noscript", "template", "math",
            ]))
            .add_tag_attributes("meta", &["name", "property", "content"]);
        let normalized_meta_html = meta_builder.clean(raw_html).to_string();

        let (title, author, meta_desc, meta_image) =
            Self::parse_title_and_meta(&normalized_meta_html, base_url);
        let css_rules = semantic_html::parse_stylesheet_typographic_rules(raw_html);
        let prepared_body = semantic_html::preprocess_semantic_html(raw_html, &css_rules, true);
        let content_html = Self::sanitize_prepared_html(&prepared_body, base_url);
        let thumbnail_url =
            meta_image.or_else(|| Self::extract_first_image_url_with_base(raw_html, base_url));
        let summary = meta_desc
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

        ExtractedSnapshot { title, author, summary, content_html, thumbnail_url }
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

    fn parse_title_and_meta(
        normalized_html: &str,
        base_url: Option<&Url>,
    ) -> (Option<String>, Option<String>, Option<String>, Option<String>) {
        let mut reader = Reader::from_str(normalized_html);
        reader.config_mut().check_end_names = false;

        let mut title: Option<String> = None;
        let mut og_title: Option<String> = None;
        let mut meta_author: Option<String> = None;
        let mut meta_desc: Option<String> = None;
        let mut meta_image: Option<String> = None;
        let mut in_title = false;
        let mut current_buf = String::new();

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) => {
                    let name = tag.name();
                    let name_bytes = name.as_ref();
                    if name_bytes.eq_ignore_ascii_case("title") && title.is_none() {
                        in_title = true;
                        current_buf.clear();
                    } else if name_bytes.eq_ignore_ascii_case("meta") {
                        Self::inspect_meta_tag(
                            &tag,
                            base_url,
                            &mut og_title,
                            &mut meta_author,
                            &mut meta_desc,
                            &mut meta_image,
                        );
                    }
                }
                Ok(Event::Empty(tag)) if tag.name().as_ref().eq_ignore_ascii_case("meta") => {
                    Self::inspect_meta_tag(
                        &tag,
                        base_url,
                        &mut og_title,
                        &mut meta_author,
                        &mut meta_desc,
                        &mut meta_image,
                    );
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
                        title = Some(trimmed.to_owned());
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        (title.or(og_title), meta_author, meta_desc, meta_image)
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
        og_title: &mut Option<String>,
        meta_author: &mut Option<String>,
        meta_desc: &mut Option<String>,
        meta_image: &mut Option<String>,
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

        if meta_desc.is_none()
            && (name.eq_ignore_ascii_case("description")
                || name.eq_ignore_ascii_case("og:description"))
        {
            *meta_desc = Some(content);
        } else if og_title.is_none() && name.eq_ignore_ascii_case("og:title") {
            *og_title = Some(content);
        } else if meta_author.is_none()
            && (name.eq_ignore_ascii_case("author")
                || name.eq_ignore_ascii_case("article:author")
                || name.eq_ignore_ascii_case("dc.creator")
                || name.eq_ignore_ascii_case("byl"))
            && !content.starts_with("http://")
            && !content.starts_with("https://")
        {
            *meta_author = Some(content);
        } else if meta_image.is_none()
            && (name.eq_ignore_ascii_case("og:image") || name.eq_ignore_ascii_case("twitter:image"))
            && let Some(valid_url) = Self::resolve_and_validate_thumbnail_url(&content, base_url)
        {
            *meta_image = Some(valid_url);
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
}
