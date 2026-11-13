use std::collections::HashSet;

use quick_xml::{Reader, XmlVersion, escape::unescape, events::Event};
use url::Url;

use super::http::SafeHttpClient;

pub const MAX_SUMMARY_CHARS: usize = 320;

pub struct HtmlSanitizer;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedSnapshot {
    pub title: Option<String>,
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
        let mut builder = ammonia::Builder::default();
        builder.add_clean_content_tags(["head", "title", "noscript", "template", "svg", "math"]);
        if let Some(base) = base_url {
            builder.url_relative(ammonia::UrlRelative::RewriteWithBase(base.clone()));
        }
        builder.clean(html).to_string()
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
                    let decoded =
                        unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into);
                    collected_chars = collected_chars.saturating_add(Self::push_collapsed_segment(
                        &mut collapsed,
                        &mut pending_space,
                        &decoded,
                    ));
                }
                Ok(Event::GeneralRef(entity)) => {
                    let raw = format!("&{};", entity.as_ref());
                    if let Ok(decoded) = unescape(&raw) {
                        collected_chars =
                            collected_chars.saturating_add(Self::push_collapsed_segment(
                                &mut collapsed,
                                &mut pending_space,
                                &decoded,
                            ));
                    }
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

        let (title, meta_desc, meta_image) =
            Self::parse_title_and_meta(&normalized_meta_html, base_url);
        let raw_image = Self::extract_first_image_url_with_base(raw_html, base_url);
        let content_html = Self::clean_with_base(raw_html, base_url);
        let thumbnail_url = meta_image
            .or(raw_image)
            .or_else(|| Self::extract_first_image_url_with_base(&content_html, base_url));
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

        ExtractedSnapshot { title, summary, content_html, thumbnail_url }
    }

    /// Scans HTML for the first valid raster `<img src="...">` URL (resolving relative URLs against `base_url`),
    /// skipping tracking pixels, inline emojis, footnote icons, and `.svg` images.
    #[must_use]
    pub fn extract_first_image_url_with_base(html: &str, base_url: Option<&Url>) -> Option<String> {
        if !html.contains("<img") && !html.contains("<IMG") {
            return None;
        }
        let mut reader = Reader::from_str(html);
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
        let candidate = raw.trim();
        if candidate.is_empty()
            || candidate.starts_with("data:")
            || candidate.starts_with("javascript:")
            || candidate.starts_with("mailto:")
        {
            return None;
        }
        let parsed = match Url::parse(candidate) {
            Ok(url) => url,
            Err(url::ParseError::RelativeUrlWithoutBase) => base_url?.join(candidate).ok()?,
            Err(_) => return None,
        };
        SafeHttpClient::validate_url(&parsed).ok()?;
        if Self::is_non_thumbnail_url(&parsed) {
            return None;
        }
        Some(parsed.into())
    }

    #[must_use]
    pub fn is_non_thumbnail_url(url: &Url) -> bool {
        let path_lower = url.path().to_ascii_lowercase();
        if std::path::Path::new(&path_lower)
            .extension()
            .is_some_and(|ext| matches!(ext.to_str().unwrap_or(""), "svg" | "svgz" | "ico"))
        {
            return true;
        }
        let full_lower = url.as_str().to_ascii_lowercase();
        full_lower.contains("/images/core/emoji/")
            || full_lower.contains("/wpcom-smileys/")
            || full_lower.contains("/twemoji/")
            || full_lower.contains("feeds.feedburner.com/~")
            || path_lower.ends_with("/spacer.gif")
            || path_lower.ends_with("/blank.gif")
            || path_lower.ends_with("/1x1.gif")
            || path_lower.ends_with("/pixel.gif")
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

        for attr in tag.attributes().with_checks(false).flatten() {
            let key = attr.key.as_ref();
            let val = attr
                .normalized_value(XmlVersion::default())
                .map_or_else(|_| attr.value.as_ref().to_owned(), Into::into);
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
    ) -> (Option<String>, Option<String>, Option<String>) {
        let mut reader = Reader::from_str(normalized_html);
        reader.config_mut().check_end_names = false;

        let mut title: Option<String> = None;
        let mut og_title: Option<String> = None;
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
                        &mut meta_desc,
                        &mut meta_image,
                    );
                }
                Ok(Event::Text(text)) if in_title => {
                    let decoded =
                        unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into);
                    current_buf.push_str(&decoded);
                }
                Ok(Event::GeneralRef(entity)) if in_title => {
                    let raw = format!("&{};", entity.as_ref());
                    if let Ok(decoded) = unescape(&raw) {
                        current_buf.push_str(&decoded);
                    }
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

        (title.or(og_title), meta_desc, meta_image)
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
                    let decoded =
                        unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into);
                    current_buf.push_str(&decoded);
                }
                Ok(Event::GeneralRef(entity)) if in_p => {
                    let raw = format!("&{};", entity.as_ref());
                    if let Ok(decoded) = unescape(&raw) {
                        current_buf.push_str(&decoded);
                    }
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
        meta_desc: &mut Option<String>,
        meta_image: &mut Option<String>,
    ) {
        let mut attr_name: Option<String> = None;
        let mut attr_content: Option<String> = None;

        for attr in tag.attributes().with_checks(false).flatten() {
            let key = attr.key.as_ref();
            let val = attr
                .normalized_value(XmlVersion::default())
                .map_or_else(|_| attr.value.as_ref().to_owned(), Into::into);
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
        } else if meta_image.is_none()
            && (name.eq_ignore_ascii_case("og:image") || name.eq_ignore_ascii_case("twitter:image"))
            && let Some(valid_url) = Self::resolve_and_validate_thumbnail_url(&content, base_url)
        {
            *meta_image = Some(valid_url);
        }
    }
}
