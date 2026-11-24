use std::{
    borrow::Cow,
    collections::{HashMap, HashSet},
};

use quick_xml::{Reader, events::Event};

use super::xml_util;

/// Typographic styling flags lifted from stylesheet rules, semantic classes, or inline `style`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TypographicFlags {
    pub italic: Option<bool>,
    pub bold: Option<bool>,
    pub underline: Option<bool>,
    pub strikethrough: Option<bool>,
    pub monospace: Option<bool>,
    pub superscript: Option<bool>,
    pub subscript: Option<bool>,
    pub small: Option<bool>,
    pub hidden: Option<bool>,
}

impl TypographicFlags {
    const fn merge_over(&mut self, other: Self) {
        if other.italic.is_some() {
            self.italic = other.italic;
        }
        if other.bold.is_some() {
            self.bold = other.bold;
        }
        if other.underline.is_some() {
            self.underline = other.underline;
        }
        if other.strikethrough.is_some() {
            self.strikethrough = other.strikethrough;
        }
        if other.monospace.is_some() {
            self.monospace = other.monospace;
        }
        if other.superscript.is_some() {
            self.superscript = other.superscript;
        }
        if other.subscript.is_some() {
            self.subscript = other.subscript;
        }
        if other.small.is_some() {
            self.small = other.small;
        }
        if other.hidden.is_some() {
            self.hidden = other.hidden;
        }
    }

    const fn is_empty(self) -> bool {
        self.italic.is_none()
            && self.bold.is_none()
            && self.underline.is_none()
            && self.strikethrough.is_none()
            && self.monospace.is_none()
            && self.superscript.is_none()
            && self.subscript.is_none()
            && self.small.is_none()
            && self.hidden.is_none()
    }
}

#[derive(Debug, Clone, Copy, Default)]
#[allow(clippy::struct_excessive_bools)]
struct AncestorContext {
    in_pre: bool,
    in_em: bool,
    in_strong: bool,
    in_u: bool,
    in_s: bool,
    in_code: bool,
    in_sup: bool,
    in_sub: bool,
    in_small: bool,
}

impl AncestorContext {
    fn for_tag(self, tag: &str) -> Self {
        let mut next = self;
        match tag {
            "pre" => next.in_pre = true,
            "em" | "i" | "cite" | "dfn" | "var" | "blockquote" | "figcaption" => next.in_em = true,
            "strong" | "b" => next.in_strong = true,
            "u" | "ins" => next.in_u = true,
            "s" | "del" | "strike" => next.in_s = true,
            "code" | "tt" | "kbd" | "samp" => next.in_code = true,
            "sup" => next.in_sup = true,
            "sub" => next.in_sub = true,
            "small" => next.in_small = true,
            _ => {}
        }
        next
    }
}

#[derive(Debug, Clone)]
enum DomNode {
    Element(DomElement),
    RawText(String),
}

#[derive(Debug, Clone)]
struct DomElement {
    tag: String,
    attrs: Vec<(String, String)>,
    children: Vec<DomNode>,
}

impl DomElement {
    fn attr(&self, name: &str) -> Option<&str> {
        self.attrs.iter().find_map(|(k, v)| k.eq_ignore_ascii_case(name).then_some(v.as_str()))
    }

    fn has_attr(&self, name: &str) -> bool {
        self.attrs.iter().any(|(k, _)| k.eq_ignore_ascii_case(name))
    }

    fn remove_attrs(&mut self, names: &[&str]) {
        self.attrs.retain(|(k, _)| !names.iter().any(|n| k.eq_ignore_ascii_case(n)));
    }

    fn has_block_descendant(&self) -> bool {
        self.children.iter().any(|child| match child {
            DomNode::RawText(_) => false,
            DomNode::Element(el) => is_block_tag(&el.tag) || el.has_block_descendant(),
        })
    }

    fn has_visible_text(&self) -> bool {
        self.children.iter().any(|child| match child {
            DomNode::RawText(t) => has_non_ws_raw_text(t),
            DomNode::Element(el) => !is_void_tag(&el.tag) && el.has_visible_text(),
        })
    }

    fn text_len(&self) -> usize {
        self.children
            .iter()
            .map(|child| match child {
                DomNode::RawText(t) => t.split_whitespace().map(str::len).sum(),
                DomNode::Element(el) => el.text_len(),
            })
            .sum()
    }
}

const MAX_PROSE_ID_LEN: usize = 128;

/// Validates a DOM `id` attribute and prefixes it with `prose-` (idempotently) so article anchors
/// can never collide with Roche's own page element IDs.
#[must_use]
pub fn normalize_prose_id(raw: &str) -> Option<Cow<'_, str>> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.len() > MAX_PROSE_ID_LEN {
        return None;
    }
    if !trimmed.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b':'))
    {
        return None;
    }
    if trimmed.len() > 6 && trimmed[..6].eq_ignore_ascii_case("prose-") {
        Some(Cow::Borrowed(trimmed))
    } else {
        Some(Cow::Owned(format!("prose-{trimmed}")))
    }
}

/// Extracts simple class-based typographic rules from inline `<style>` blocks in a raw HTML page.
///
/// Only unconditional or screen-scoped class selectors without pseudo-classes/pseudo-elements are
/// collected, and `display: none` / `visibility: hidden` are only honored from top-level rules
/// (never from responsive `@media` blocks).
#[must_use]
pub fn parse_stylesheet_typographic_rules(raw_html: &str) -> HashMap<String, TypographicFlags> {
    let mut rules: HashMap<String, TypographicFlags> = HashMap::new();
    let lower = raw_html.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    let mut cursor = 0usize;

    while let Some(rel_start) = lower[cursor..].find("<style") {
        let tag_start = cursor + rel_start;
        let after_name = tag_start + "<style".len();
        if bytes
            .get(after_name)
            .is_some_and(|b| !b.is_ascii_whitespace() && *b != b'>' && *b != b'/')
        {
            cursor = after_name;
            continue;
        }
        let Some(rel_gt) = lower[after_name..].find('>') else {
            break;
        };
        let body_start = after_name + rel_gt + 1;
        let Some(rel_end) = lower[body_start..].find("</style") else {
            break;
        };
        let css_block = &raw_html[body_start..body_start + rel_end];
        parse_css_stylesheet_into(css_block, false, &mut rules);
        cursor = body_start + rel_end + "</style".len();
    }

    rules
}

fn strip_css_comments(css: &str) -> String {
    let mut out = String::with_capacity(css.len());
    let mut rest = css;
    while let Some(start) = rest.find("/*") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        if let Some(end) = after.find("*/") {
            rest = &after[end + 2..];
        } else {
            return out;
        }
    }
    out.push_str(rest);
    out
}

fn parse_css_stylesheet_into(
    raw_css: &str,
    inside_media: bool,
    out: &mut HashMap<String, TypographicFlags>,
) {
    let cleaned = strip_css_comments(raw_css);
    let bytes = cleaned.as_bytes();
    let mut i = 0usize;

    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b';') {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        let header_start = i;
        while i < bytes.len() && bytes[i] != b'{' && bytes[i] != b';' {
            i += 1;
        }
        if i >= bytes.len() {
            break;
        }
        if bytes[i] == b';' {
            i += 1;
            continue;
        }
        let header = cleaned[header_start..i].trim();
        let body_start = i + 1;
        let Some(body_end) = find_matching_brace(&cleaned, i) else {
            break;
        };
        let body = &cleaned[body_start..body_end];
        i = body_end + 1;

        if header.starts_with('@') {
            let header_lower = header.to_ascii_lowercase();
            if header_lower.starts_with("@media") && !header_lower.contains("print") {
                parse_css_stylesheet_into(body, true, out);
            }
            continue;
        }

        let mut flags = parse_css_declarations(body);
        if inside_media {
            flags.hidden = None;
        }
        if flags.is_empty() {
            continue;
        }
        for selector in header.split(',') {
            if let Some(cls) = extract_target_class_from_selector(selector) {
                out.entry(cls).or_default().merge_over(flags);
            }
        }
    }
}

const fn find_matching_brace(s: &str, open_idx: usize) -> Option<usize> {
    let mut depth = 0usize;
    let mut quote: Option<u8> = None;
    let bytes = s.as_bytes();
    let mut i = open_idx;
    while i < bytes.len() {
        let b = bytes[i];
        match quote {
            Some(_) if b == b'\\' => {
                i += 2;
                continue;
            }
            Some(q) if b == q => quote = None,
            None if b == b'"' || b == b'\'' => quote = Some(b),
            None if b == b'{' => depth += 1,
            None if b == b'}' => {
                depth = depth.saturating_sub(1);
                if depth == 0 {
                    return Some(i);
                }
            }
            Some(_) | None => {}
        }
        i += 1;
    }
    None
}

fn extract_target_class_from_selector(raw_selector: &str) -> Option<String> {
    let trimmed = raw_selector.trim();
    if trimmed.is_empty() || trimmed.contains(':') || trimmed.contains('[') {
        return None;
    }
    let lower = trimmed.to_ascii_lowercase();
    for skip_ctx in [
        ".home",
        ".page-id-",
        "header",
        "footer",
        "nav",
        ".sidebar",
        ".comment",
        ".wp-block-comment",
        ".wp-block-latest-comments",
        ".wp-block-navigation",
        ".wp-block-site-",
        ".pp-brand",
        ".pp-summary",
        ".pp-terms",
        ".pp-intro",
    ] {
        if lower.contains(skip_ctx) {
            return None;
        }
    }
    let last_compound =
        trimmed.rsplit(|c: char| c.is_ascii_whitespace() || matches!(c, '>' | '+' | '~')).next()?;
    let dot_pos = last_compound.find('.')?;
    let class_part = &last_compound[dot_pos + 1..];
    // Reject multi-class compound selectors like `.a.b` so we don't over-apply `.a` alone.
    if class_part.is_empty() || class_part.contains('.') || class_part.contains('#') {
        return None;
    }
    let prefix = &last_compound[..dot_pos];
    if !prefix.is_empty() && !prefix.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') {
        return None;
    }
    if !class_part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        return None;
    }
    Some(class_part.to_ascii_lowercase())
}

#[must_use]
pub fn parse_css_declarations(decls: &str) -> TypographicFlags {
    let mut flags = TypographicFlags::default();
    for part in decls.split(';') {
        let Some((raw_prop, raw_val)) = part.split_once(':') else {
            continue;
        };
        let prop = raw_prop.trim().to_ascii_lowercase();
        let val = raw_val.trim().trim_end_matches("!important").trim().to_ascii_lowercase();
        if val.is_empty() {
            continue;
        }
        apply_css_property(&mut flags, &prop, &val);
    }
    flags
}

fn apply_css_property(flags: &mut TypographicFlags, prop: &str, val: &str) {
    match prop {
        "font-style" => {
            if val.starts_with("italic") || val.starts_with("oblique") {
                flags.italic = Some(true);
            } else if matches!(val, "normal" | "initial" | "unset") {
                flags.italic = Some(false);
            }
        }
        "font-weight" => {
            if matches!(val, "bold" | "bolder") {
                flags.bold = Some(true);
            } else if matches!(val, "normal" | "lighter" | "initial" | "unset") {
                flags.bold = Some(false);
            } else if let Ok(num) = val.parse::<u16>() {
                flags.bold = Some(num >= 600);
            }
        }
        "text-decoration" | "text-decoration-line" => {
            if val == "none" {
                flags.underline = Some(false);
                flags.strikethrough = Some(false);
            } else {
                if val.split_whitespace().any(|t| t == "line-through") {
                    flags.strikethrough = Some(true);
                }
                if val.split_whitespace().any(|t| t == "underline") {
                    flags.underline = Some(true);
                }
            }
        }
        "font-family" => {
            if val.contains("monospace")
                || val.contains("courier")
                || val.contains("menlo")
                || val.contains("consolas")
                || val.contains("monaco")
            {
                flags.monospace = Some(true);
            }
        }
        "vertical-align" => match val {
            "super" => flags.superscript = Some(true),
            "sub" => flags.subscript = Some(true),
            "baseline" | "normal" => {
                flags.superscript = Some(false);
                flags.subscript = Some(false);
            }
            _ => {}
        },
        "font-size" if is_small_css_font_size(val) => {
            flags.small = Some(true);
        }
        "display" if val == "none" => {
            flags.hidden = Some(true);
        }
        "visibility" if val == "hidden" => {
            flags.hidden = Some(true);
        }
        _ => {}
    }
}

fn is_small_css_font_size(val: &str) -> bool {
    if matches!(val, "smaller" | "small" | "x-small" | "xx-small") {
        return true;
    }
    if let Some(num) = val
        .strip_suffix("em")
        .and_then(|s| (!s.ends_with('r')).then(|| s.trim().parse::<f32>().ok()))
        .flatten()
    {
        return num > 0.0 && num <= 0.88;
    }
    if let Some(num) = val.strip_suffix('%').and_then(|s| s.trim().parse::<f32>().ok()) {
        return num > 0.0 && num <= 88.0;
    }
    false
}

fn builtin_class_flags(class_attr: &str) -> TypographicFlags {
    let mut flags = TypographicFlags::default();
    for raw_tok in class_attr.split_whitespace() {
        let tok = raw_tok.to_ascii_lowercase();
        match tok.as_str() {
            "sr-only"
            | "screen-reader-text"
            | "visually-hidden"
            | "pp-visually-hidden"
            | "d-none"
            | "u-hidden"
            | "hidden"
            | "sharedaddy"
            | "jp-relatedposts"
            | "jetpack-likes-widget-wrapper" => {
                flags.hidden = Some(true);
            }
            "author-line" | "byline" | "post-byline" | "article-byline" | "entry-byline"
            | "author-bio" | "author-credit" | "author-affiliation" | "subtitle"
            | "post-subtitle" | "article-subtitle" | "entry-subtitle" | "subhead" | "deck"
            | "dek" | "standfirst" | "epigraph" | "caption" | "wp-caption-text"
            | "wp-element-caption" | "image-caption" | "photo-caption" | "figure-caption"
            | "credit" | "photo-credit" | "attribution" | "italic" | "italics" | "font-italic"
            | "is-italic" | "oblique" => {
                flags.italic = Some(true);
            }
            "bold" | "font-bold" | "font-semibold" | "is-bold" => {
                flags.bold = Some(true);
            }
            "underline" | "is-underlined" => {
                flags.underline = Some(true);
            }
            "line-through" | "strike" | "strikethrough" | "is-strikethrough" => {
                flags.strikethrough = Some(true);
            }
            "monospace" | "font-mono" => {
                flags.monospace = Some(true);
            }
            "pp-in-reply-to" | "pp-series-note" | "series-note" | "editors-note"
            | "editor-note" => {
                flags.small = Some(true);
            }
            _ => {}
        }
    }
    flags
}

fn contains_ascii_case_insensitive(haystack: &str, needle: &[u8]) -> bool {
    haystack.as_bytes().windows(needle.len()).any(|w| w.eq_ignore_ascii_case(needle))
}

/// Normalizes raw HTML5 via `ammonia`, optionally extracts the primary article container from a
/// full webpage, and lifts safe typographic `style`, `class`, and `<style>` rules into semantic
/// HTML elements (`<em>`, `<strong>`, `<u>`, `<s>`, `<code>`, `<sup>`, `<sub>`, `<small>`).
///
/// The returned HTML must still be passed through the final `ammonia` sanitizer pass.
#[must_use]
pub fn preprocess_semantic_html(
    raw_html: &str,
    css_rules: &HashMap<String, TypographicFlags>,
    extract_article: bool,
) -> String {
    if !extract_article
        && css_rules.is_empty()
        && !contains_ascii_case_insensitive(raw_html, b"class=")
        && !contains_ascii_case_insensitive(raw_html, b"style=")
        && !contains_ascii_case_insensitive(raw_html, b"hidden")
    {
        return raw_html.to_owned();
    }

    let normalized = run_structural_pre_pass(raw_html, extract_article);
    let mut root = parse_normalized_html_tree(&normalized);
    if extract_article {
        root = extract_primary_article_root(root);
    }
    let mut transformed = Vec::with_capacity(root.len());
    for node in root {
        transform_node(
            node,
            css_rules,
            TypographicFlags::default(),
            AncestorContext::default(),
            &mut transformed,
        );
    }
    let mut out = String::with_capacity(normalized.len() + 64);
    serialize_nodes(&transformed, &mut out);
    out
}

/// Allowlists strictly safe presentation `MathML` tags and non-URL layout attributes on an
/// `ammonia::Builder`, while ensuring `<annotation>` / `<annotation-xml>` subtrees are stripped.
pub fn configure_safe_mathml(builder: &mut ammonia::Builder<'_>) {
    const MATHML_TAGS: [&str; 22] = [
        "math",
        "mi",
        "mn",
        "mo",
        "mtext",
        "mspace",
        "mrow",
        "msup",
        "msub",
        "msubsup",
        "mfrac",
        "msqrt",
        "mroot",
        "mover",
        "munder",
        "munderover",
        "mtable",
        "mtr",
        "mtd",
        "mstyle",
        "mpadded",
        "semantics",
    ];
    builder.add_clean_content_tags(["annotation", "annotation-xml"]);
    builder.add_tags(MATHML_TAGS);
    builder.add_tag_attributes("math", ["display"]);
    builder.add_tag_attributes("mi", ["mathvariant"]);
    builder.add_tag_attributes("mn", ["mathvariant"]);
    builder.add_tag_attributes("mtext", ["mathvariant"]);
    builder.add_tag_attributes("mstyle", ["mathvariant"]);
    builder.add_tag_attributes(
        "mo",
        [
            "mathvariant",
            "stretchy",
            "lspace",
            "rspace",
            "fence",
            "separator",
            "accent",
            "largeop",
            "movablelimits",
            "symmetric",
        ],
    );
    builder.add_tag_attributes("mspace", ["width"]);
    builder.add_tag_attributes("mfrac", ["linethickness"]);
    builder.add_tag_attributes("mover", ["accent"]);
    builder.add_tag_attributes("munder", ["accentunder"]);
    builder.add_tag_attributes("munderover", ["accent", "accentunder"]);
    builder.add_tag_attributes("mtable", ["columnalign", "rowalign"]);
    builder.add_tag_attributes("mtr", ["columnalign", "rowalign"]);
    builder.add_tag_attributes("mtd", ["columnalign", "rowalign", "columnspan", "rowspan"]);
}

fn run_structural_pre_pass(raw_html: &str, include_page_chrome_tags: bool) -> String {
    let mut builder = ammonia::Builder::default();
    builder.add_tags([
        "article",
        "aside",
        "details",
        "figcaption",
        "figure",
        "footer",
        "header",
        "main",
        "mark",
        "section",
        "summary",
        "time",
    ]);
    if include_page_chrome_tags {
        builder.add_tags(["nav", "form"]);
    }
    builder.add_clean_content_tags([
        "head",
        "title",
        "script",
        "style",
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
    configure_safe_mathml(&mut builder);
    builder.add_generic_attributes(["class", "style", "id", "hidden", "role", "itemprop"]);
    builder.url_relative(ammonia::UrlRelative::PassThrough);
    builder.link_rel(None);
    builder.clean(raw_html).to_string()
}

fn parse_normalized_html_tree(html: &str) -> Vec<DomNode> {
    let mut reader = Reader::from_str(html);
    reader.config_mut().check_end_names = false;

    let mut stack: Vec<DomElement> =
        vec![DomElement { tag: String::new(), attrs: Vec::new(), children: Vec::new() }];

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let name = tag.name().as_ref().to_ascii_lowercase();
                let attrs: Vec<(String, String)> =
                    xml_util::iter_attrs(&tag).map(|(k, v)| (k.to_ascii_lowercase(), v)).collect();
                let elem = DomElement { tag: name.clone(), attrs, children: Vec::new() };
                if is_void_tag(&name) {
                    if let Some(parent) = stack.last_mut() {
                        parent.children.push(DomNode::Element(elem));
                    }
                } else {
                    stack.push(elem);
                }
            }
            Ok(Event::Empty(tag)) => {
                let name = tag.name().as_ref().to_ascii_lowercase();
                let attrs: Vec<(String, String)> =
                    xml_util::iter_attrs(&tag).map(|(k, v)| (k.to_ascii_lowercase(), v)).collect();
                if let Some(parent) = stack.last_mut() {
                    parent.children.push(DomNode::Element(DomElement {
                        tag: name,
                        attrs,
                        children: Vec::new(),
                    }));
                }
            }
            Ok(Event::End(tag)) => {
                let end_name = tag.name().as_ref().to_ascii_lowercase();
                if let Some(pos) =
                    stack.iter().rposition(|el| !el.tag.is_empty() && el.tag == end_name)
                {
                    while stack.len() > pos {
                        if let Some(finished) = stack.pop()
                            && let Some(parent) = stack.last_mut()
                        {
                            parent.children.push(DomNode::Element(finished));
                        }
                    }
                }
            }
            Ok(Event::Text(text)) => {
                if let Some(parent) = stack.last_mut() {
                    parent.children.push(DomNode::RawText(text.as_ref().to_owned()));
                }
            }
            Ok(Event::GeneralRef(entity)) => {
                if let Some(parent) = stack.last_mut() {
                    parent.children.push(DomNode::RawText(format!("&{};", entity.as_ref())));
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }

    while stack.len() > 1 {
        if let Some(finished) = stack.pop()
            && let Some(parent) = stack.last_mut()
        {
            parent.children.push(DomNode::Element(finished));
        }
    }
    stack.pop().map_or_else(Vec::new, |root| root.children)
}

fn extract_primary_article_root(mut nodes: Vec<DomNode>) -> Vec<DomNode> {
    strip_page_boilerplate(&mut nodes, false);

    // 1. Prefer explicit article-body containers (e.g. WordPress `.entry-content`, `.wp-block-post-content`).
    if let Some(best) = find_best_container(&nodes, is_explicit_article_body) {
        return best.children;
    }
    // 2. Otherwise prefer the richest `<article>` (already excluding comments/sidebar).
    if let Some(best) = find_best_container(&nodes, |el| el.tag == "article") {
        return best.children;
    }
    // 3. Otherwise `<main>` or `[role="main"]`.
    if let Some(best) = find_best_container(&nodes, |el| {
        el.tag == "main" || el.attr("role").is_some_and(|r| r.eq_ignore_ascii_case("main"))
    }) {
        return best.children;
    }
    nodes
}

fn strip_page_boilerplate(nodes: &mut Vec<DomNode>, inside_blockquote: bool) {
    nodes.retain_mut(|node| {
        let DomNode::Element(el) = node else {
            return true;
        };
        if is_page_boilerplate_element(el, inside_blockquote) {
            return false;
        }
        let next_in_quote = inside_blockquote || el.tag == "blockquote";
        strip_page_boilerplate(&mut el.children, next_in_quote);
        true
    });
}

fn is_page_boilerplate_element(el: &DomElement, inside_blockquote: bool) -> bool {
    if matches!(el.tag.as_str(), "nav" | "aside" | "form") {
        return true;
    }
    if !inside_blockquote && matches!(el.tag.as_str(), "header" | "footer") {
        let role = el.attr("role").unwrap_or("");
        let has_article_header_class = el.attr("class").is_some_and(|c| {
            let l = c.to_ascii_lowercase();
            l.contains("entry-header") || l.contains("post-header") || l.contains("article-header")
        });
        if role.eq_ignore_ascii_case("banner")
            || role.eq_ignore_ascii_case("contentinfo")
            || !has_article_header_class
        {
            return true;
        }
    }
    if let Some(role) = el.attr("role")
        && matches!(
            role.to_ascii_lowercase().as_str(),
            "navigation" | "banner" | "contentinfo" | "complementary" | "search" | "dialog"
        )
    {
        return true;
    }
    if let Some(id) = el.attr("id") {
        let l = id.to_ascii_lowercase();
        if matches!(
            l.as_str(),
            "comments" | "respond" | "sidebar" | "footer" | "header" | "likes-other-gravatars"
        ) {
            return true;
        }
    }
    if let Some(cls) = el.attr("class") {
        for raw_tok in cls.split_whitespace() {
            let tok = raw_tok.to_ascii_lowercase();
            if matches!(
                tok.as_str(),
                "comments-area"
                    | "comment-list"
                    | "comment-respond"
                    | "wp-block-comments"
                    | "wp-block-latest-comments"
                    | "wp-block-post-comments-form"
                    | "sharedaddy"
                    | "sd-sharing"
                    | "jetpack-likes-widget-wrapper"
                    | "jp-relatedposts"
                    | "jp-relatedposts-i2"
                    | "pp-read-next"
                    | "related-posts"
                    | "share-buttons"
                    | "social-share"
                    | "site-header"
                    | "site-footer"
                    | "sidebar"
                    | "widget-area"
                    | "breadcrumb"
                    | "breadcrumbs"
                    | "ltx_page_logo"
                    | "ltx_page_header"
                    | "ltx_page_footer"
                    | "ltx_pagination"
                    | "ar5iv-footer"
            ) {
                return true;
            }
        }
    }
    false
}

fn is_explicit_article_body(el: &DomElement) -> bool {
    if el.attr("itemprop").is_some_and(|p| p.eq_ignore_ascii_case("articlebody")) {
        return true;
    }
    let Some(cls) = el.attr("class") else {
        return false;
    };
    cls.split_whitespace().any(|tok| {
        matches!(
            tok.to_ascii_lowercase().as_str(),
            "entry-content"
                | "post-content"
                | "wp-block-post-content"
                | "article-content"
                | "article-body"
                | "post-body"
                | "story-body"
        )
    })
}

fn find_best_container<F: Fn(&DomElement) -> bool + Copy>(
    nodes: &[DomNode],
    predicate: F,
) -> Option<DomElement> {
    let mut best: Option<(usize, DomElement)> = None;
    collect_best_container(nodes, predicate, &mut best);
    best.map(|(_, el)| el)
}

fn collect_best_container<F: Fn(&DomElement) -> bool + Copy>(
    nodes: &[DomNode],
    predicate: F,
    best: &mut Option<(usize, DomElement)>,
) {
    for node in nodes {
        if let DomNode::Element(el) = node {
            if predicate(el) {
                let score = el.text_len();
                if score > 0 && best.as_ref().is_none_or(|(best_score, _)| score > *best_score) {
                    *best = Some((score, el.clone()));
                }
            }
            collect_best_container(&el.children, predicate, best);
        }
    }
}

fn resolve_element_flags(
    el: &DomElement,
    css_rules: &HashMap<String, TypographicFlags>,
) -> TypographicFlags {
    let mut flags = TypographicFlags::default();
    if el.has_attr("hidden") {
        flags.hidden = Some(true);
    }
    if let Some(class_attr) = el.attr("class") {
        for raw_tok in class_attr.split_whitespace() {
            let tok = raw_tok.to_ascii_lowercase();
            if let Some(css_flags) = css_rules.get(&tok) {
                flags.merge_over(*css_flags);
            }
        }
        flags.merge_over(builtin_class_flags(class_attr));
    }
    if let Some(style_attr) = el.attr("style") {
        flags.merge_over(parse_css_declarations(style_attr));
    }
    flags
}

fn transform_node(
    node: DomNode,
    css_rules: &HashMap<String, TypographicFlags>,
    inherited: TypographicFlags,
    ctx: AncestorContext,
    out: &mut Vec<DomNode>,
) {
    let DomNode::Element(mut el) = node else {
        out.push(node);
        return;
    };

    let own = resolve_element_flags(&el, css_rules);
    if own.hidden == Some(true) {
        return;
    }

    // Preserve presentation MathML subtrees untouched; `ammonia` sanitizes tags & attributes downstream.
    if el.tag == "math" {
        el.remove_attrs(&["class", "style", "hidden", "role", "itemprop"]);
        out.push(DomNode::Element(el));
        return;
    }

    let mut effective = inherited;
    effective.merge_over(own);

    let preserved_class = match el.tag.as_str() {
        "figure"
            if el
                .attr("class")
                .is_some_and(|c| c.split_whitespace().any(|t| t == "paper-equation")) =>
        {
            Some("paper-equation")
        }
        "span"
            if el
                .attr("class")
                .is_some_and(|c| c.split_whitespace().any(|t| t == "paper-eqno")) =>
        {
            Some("paper-eqno")
        }
        _ => None,
    };

    el.remove_attrs(&["class", "style", "hidden", "role", "itemprop"]);
    if let Some(cls) = preserved_class {
        el.attrs.push(("class".to_owned(), cls.to_owned()));
    }

    if is_void_tag(&el.tag) {
        out.push(DomNode::Element(el));
        return;
    }

    let next_ctx = ctx.for_tag(&el.tag);
    if next_ctx.in_pre {
        out.push(DomNode::Element(el));
        return;
    }

    let is_block = is_block_tag(&el.tag);
    let has_block_child = is_block && el.has_block_descendant();

    if has_block_child {
        // Container block (e.g. `<div class="byline"><p>...</p></div>`): propagate style flags
        // down to leaf blocks rather than emitting invalid `<em><p>...</p></em>`.
        let mut new_children = Vec::with_capacity(el.children.len());
        for child in el.children {
            transform_node(child, css_rules, effective, next_ctx, &mut new_children);
        }
        el.children = new_children;
        out.push(DomNode::Element(el));
        return;
    }

    // Leaf block (e.g. `<p class="author-line">...`) or inline element (`<span style="...">`):
    // recurse into children first with cleared inherited flags, then wrap our own inline content.
    let child_ctx = context_with_flags(next_ctx, effective, &el.tag);
    let mut new_children = Vec::with_capacity(el.children.len());
    for child in el.children {
        transform_node(child, css_rules, TypographicFlags::default(), child_ctx, &mut new_children);
    }
    el.children = new_children;

    if el.has_visible_text() {
        el.children = wrap_inline_children(el.children, effective, next_ctx, &el.tag);
    }

    // Unwrap bare `<span>` without `id` if its styles were lifted into semantic child elements.
    if el.tag == "span" && el.attrs.is_empty() && !effective.is_empty() {
        out.extend(el.children);
    } else {
        out.push(DomNode::Element(el));
    }
}

fn context_with_flags(
    mut ctx: AncestorContext,
    flags: TypographicFlags,
    tag: &str,
) -> AncestorContext {
    if flags.italic == Some(true) {
        ctx.in_em = true;
    }
    if flags.bold == Some(true) {
        ctx.in_strong = true;
    }
    if flags.underline == Some(true) && tag != "a" {
        ctx.in_u = true;
    }
    if flags.strikethrough == Some(true) {
        ctx.in_s = true;
    }
    if flags.monospace == Some(true) {
        ctx.in_code = true;
    }
    if flags.superscript == Some(true) {
        ctx.in_sup = true;
    }
    if flags.subscript == Some(true) {
        ctx.in_sub = true;
    }
    if flags.small == Some(true) {
        ctx.in_small = true;
    }
    ctx
}

fn sole_meaningful_child_tag(children: &[DomNode]) -> Option<&str> {
    let mut found: Option<&str> = None;
    for child in children {
        match child {
            DomNode::RawText(t) if !has_non_ws_raw_text(t) => {}
            DomNode::RawText(_) => return None,
            DomNode::Element(el) => {
                if found.is_some() {
                    return None;
                }
                found = Some(el.tag.as_str());
            }
        }
    }
    found
}

fn wrap_in_tag(tag: &str, children: Vec<DomNode>) -> Vec<DomNode> {
    vec![DomNode::Element(DomElement { tag: tag.to_owned(), attrs: Vec::new(), children })]
}

fn wrap_inline_children(
    mut children: Vec<DomNode>,
    flags: TypographicFlags,
    ctx: AncestorContext,
    host_tag: &str,
) -> Vec<DomNode> {
    if flags.monospace == Some(true)
        && !ctx.in_code
        && sole_meaningful_child_tag(&children) != Some("code")
    {
        children = wrap_in_tag("code", children);
    }
    if flags.italic == Some(true)
        && !ctx.in_em
        && !matches!(sole_meaningful_child_tag(&children), Some("em" | "i" | "cite" | "dfn"))
    {
        children = wrap_in_tag("em", children);
    }
    if flags.bold == Some(true)
        && !ctx.in_strong
        && !matches!(sole_meaningful_child_tag(&children), Some("strong" | "b"))
    {
        children = wrap_in_tag("strong", children);
    }
    if flags.underline == Some(true)
        && host_tag != "a"
        && !ctx.in_u
        && !matches!(sole_meaningful_child_tag(&children), Some("u" | "ins"))
    {
        children = wrap_in_tag("u", children);
    }
    if flags.strikethrough == Some(true)
        && !ctx.in_s
        && !matches!(sole_meaningful_child_tag(&children), Some("s" | "del" | "strike"))
    {
        children = wrap_in_tag("s", children);
    }
    if flags.superscript == Some(true)
        && !ctx.in_sup
        && sole_meaningful_child_tag(&children) != Some("sup")
    {
        children = wrap_in_tag("sup", children);
    } else if flags.subscript == Some(true)
        && !ctx.in_sub
        && sole_meaningful_child_tag(&children) != Some("sub")
    {
        children = wrap_in_tag("sub", children);
    }
    if flags.small == Some(true)
        && !ctx.in_small
        && sole_meaningful_child_tag(&children) != Some("small")
    {
        children = wrap_in_tag("small", children);
    }
    children
}

fn serialize_nodes(nodes: &[DomNode], out: &mut String) {
    for node in nodes {
        match node {
            DomNode::RawText(t) => out.push_str(t),
            DomNode::Element(el) => {
                out.push('<');
                out.push_str(&el.tag);
                for (k, v) in &el.attrs {
                    out.push(' ');
                    out.push_str(k);
                    out.push_str("=\"");
                    push_escaped_attr(out, v);
                    out.push('"');
                }
                out.push('>');
                if !is_void_tag(&el.tag) {
                    serialize_nodes(&el.children, out);
                    out.push_str("</");
                    out.push_str(&el.tag);
                    out.push('>');
                }
            }
        }
    }
}

fn push_escaped_attr(out: &mut String, val: &str) {
    for ch in val.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '"' => out.push_str("&quot;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            other => out.push(other),
        }
    }
}

fn has_non_ws_raw_text(raw: &str) -> bool {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return false;
    }
    !matches!(
        trimmed,
        "&nbsp;" | "&#160;" | "&#xA0;" | "&#xa0;" | "&ensp;" | "&emsp;" | "&thinsp;" | "\u{00a0}"
    )
}

fn is_void_tag(tag: &str) -> bool {
    matches!(
        tag,
        "area"
            | "base"
            | "br"
            | "col"
            | "embed"
            | "hr"
            | "img"
            | "input"
            | "link"
            | "meta"
            | "param"
            | "source"
            | "track"
            | "wbr"
    )
}

fn is_block_tag(tag: &str) -> bool {
    matches!(
        tag,
        "address"
            | "article"
            | "aside"
            | "blockquote"
            | "caption"
            | "center"
            | "dd"
            | "details"
            | "div"
            | "dl"
            | "dt"
            | "figcaption"
            | "figure"
            | "footer"
            | "form"
            | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
            | "header"
            | "hgroup"
            | "hr"
            | "li"
            | "main"
            | "nav"
            | "ol"
            | "p"
            | "pre"
            | "section"
            | "summary"
            | "table"
            | "tbody"
            | "td"
            | "tfoot"
            | "th"
            | "thead"
            | "tr"
            | "ul"
    )
}

/// Converts simple inline `WordPress` `latex.php` `alt` text (e.g. `p\geq 17`, `x^2 + \alpha_i`)
/// into readable HTML using Unicode math symbols and `<sup>`/`<sub>` tags.
#[must_use]
pub fn format_latex_alt_to_html(raw_latex: &str) -> String {
    let trimmed = raw_latex.trim().trim_matches('$').trim();
    if trimmed.is_empty() {
        return String::new();
    }
    let replaced = replace_latex_commands(trimmed);
    convert_latex_scripts(&replaced)
}

fn replace_latex_commands(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let bytes = input.as_bytes();
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == b'\\' {
            let start = i + 1;
            let mut end = start;
            while end < bytes.len() && bytes[end].is_ascii_alphabetic() {
                end += 1;
            }
            if end > start {
                let cmd = &input[i..end];
                if let Some(sym) = latex_symbol_replacement(cmd) {
                    out.push_str(sym);
                } else if !is_latex_font_command(cmd) {
                    out.push_str(cmd);
                }
                i = end;
                continue;
            }
        }
        if let Some(ch) = input[i..].chars().next() {
            out.push(ch);
            i += ch.len_utf8();
        } else {
            break;
        }
    }
    out
}

fn is_latex_font_command(cmd: &str) -> bool {
    matches!(
        cmd,
        "\\mathrm"
            | "\\mathbf"
            | "\\mathit"
            | "\\mathsf"
            | "\\mathtt"
            | "\\mathcal"
            | "\\mathbb"
            | "\\text"
            | "\\textbf"
            | "\\textit"
            | "\\textrm"
            | "\\texttt"
            | "\\operatorname"
    )
}

fn latex_symbol_replacement(cmd: &str) -> Option<&'static str> {
    Some(match cmd {
        "\\geq" | "\\ge" => " ≥ ",
        "\\leq" | "\\le" => " ≤ ",
        "\\neq" | "\\ne" => " ≠ ",
        "\\approx" => " ≈ ",
        "\\equiv" => " ≡ ",
        "\\sim" => " ∼ ",
        "\\times" => " × ",
        "\\cdot" => " · ",
        "\\pm" => " ± ",
        "\\infty" => "∞",
        "\\notin" => " ∉ ",
        "\\in" => " ∈ ",
        "\\subseteq" => " ⊆ ",
        "\\subset" => " ⊂ ",
        "\\supseteq" => " ⊇ ",
        "\\supset" => " ⊃ ",
        "\\cup" => " ∪ ",
        "\\cap" => " ∩ ",
        "\\forall" => "∀",
        "\\exists" => "∃",
        "\\emptyset" => "∅",
        "\\rightarrow" | "\\to" => " → ",
        "\\leftarrow" => " ← ",
        "\\mapsto" => " ↦ ",
        "\\Rightarrow" => " ⇒ ",
        "\\iff" => " ⇔ ",
        "\\ell" => "ℓ",
        "\\alpha" => "α",
        "\\beta" => "β",
        "\\gamma" => "γ",
        "\\delta" => "δ",
        "\\epsilon" | "\\varepsilon" => "ε",
        "\\zeta" => "ζ",
        "\\eta" => "η",
        "\\theta" => "θ",
        "\\lambda" => "λ",
        "\\mu" => "μ",
        "\\nu" => "ν",
        "\\pi" => "π",
        "\\rho" => "ρ",
        "\\sigma" => "σ",
        "\\tau" => "τ",
        "\\phi" | "\\varphi" => "φ",
        "\\chi" => "χ",
        "\\psi" => "ψ",
        "\\omega" => "ω",
        "\\Gamma" => "Γ",
        "\\Delta" => "Δ",
        "\\Theta" => "Θ",
        "\\Lambda" => "Λ",
        "\\Pi" => "Π",
        "\\Sigma" => "Σ",
        "\\Phi" => "Φ",
        "\\Psi" => "Ψ",
        "\\Omega" => "Ω",
        _ => return None,
    })
}

fn convert_latex_scripts(input: &str) -> String {
    let chars: Vec<char> = input.chars().collect();
    let mut out = String::with_capacity(input.len() + 16);
    let mut i = 0usize;
    while i < chars.len() {
        let ch = chars[i];
        if (ch == '^' || ch == '_') && i + 1 < chars.len() {
            let tag = if ch == '^' { "sup" } else { "sub" };
            let next = chars[i + 1];
            if next == '{' {
                if let Some(rel_close) = chars[i + 2..].iter().position(|&c| c == '}') {
                    let close_idx = i + 2 + rel_close;
                    out.push('<');
                    out.push_str(tag);
                    out.push('>');
                    for &c in &chars[i + 2..close_idx] {
                        push_escaped_html_char(&mut out, c);
                    }
                    out.push_str("</");
                    out.push_str(tag);
                    out.push('>');
                    i = close_idx + 1;
                    continue;
                }
            } else if next.is_alphanumeric() || matches!(next, '+' | '-' | '=') {
                out.push('<');
                out.push_str(tag);
                out.push('>');
                push_escaped_html_char(&mut out, next);
                out.push_str("</");
                out.push_str(tag);
                out.push('>');
                i += 2;
                continue;
            }
        }
        if ch != '{' && ch != '}' {
            push_escaped_html_char(&mut out, ch);
        }
        i += 1;
    }
    collapse_ascii_spaces(&out)
}

fn push_escaped_html_char(out: &mut String, ch: char) {
    match ch {
        '&' => out.push_str("&amp;"),
        '<' => out.push_str("&lt;"),
        '>' => out.push_str("&gt;"),
        other => out.push(other),
    }
}

fn collapse_ascii_spaces(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_ws = false;
    for ch in s.chars() {
        if ch.is_whitespace() {
            if !prev_ws {
                out.push(' ');
                prev_ws = true;
            }
        } else {
            out.push(ch);
            prev_ws = false;
        }
    }
    out.trim().to_owned()
}

/// Collects all `id="..."` attribute values appearing in sanitized HTML.
#[must_use]
pub fn collect_html_ids(clean_html: &str) -> HashSet<String> {
    let mut ids = HashSet::new();
    let mut reader = Reader::from_str(clean_html);
    reader.config_mut().check_end_names = false;
    loop {
        match reader.read_event() {
            Ok(Event::Start(tag) | Event::Empty(tag)) => {
                if let Some(id) = xml_util::find_non_empty_attr(&tag, "id") {
                    ids.insert(id);
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    ids
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_stylesheet_extracts_author_line_italic_and_ignores_media_hidden() {
        let html = r"
        <html><head><style>
            .author-line {
                color: #26430a;
                font-style: italic;
                font-size: 1.25rem;
            }
            .custom-bold { font-weight: 700; }
            .hidden-banner { display: none; }
            @media (max-width: 899px) { .pp-sidenotes { display: none; } }
            .wp-block-navigation .nav-only { font-style: italic; }
        </style></head><body></body></html>
        ";
        let rules = parse_stylesheet_typographic_rules(html);
        assert_eq!(rules.get("author-line").and_then(|f| f.italic), Some(true));
        assert_eq!(rules.get("custom-bold").and_then(|f| f.bold), Some(true));
        assert_eq!(rules.get("hidden-banner").and_then(|f| f.hidden), Some(true));
        assert_eq!(rules.get("pp-sidenotes").and_then(|f| f.hidden), None);
        assert!(!rules.contains_key("nav-only"));
    }

    #[test]
    fn test_preprocess_lifts_author_line_inline_styles_and_respects_normal_override() {
        let html = r#"
            <p class="author-line wp-block-paragraph"><a href="https://www.jm-kantor.fr/">Jean-Michel Kantor</a>, Emeritus from IMJ</p>
            <p class="pp-series-note" style="font-size:0.8em;font-style:normal"><strong>SERIES</strong> · Note</p>
            <p>Text with <span style="font-style: italic; font-weight: 700;">both</span> and <span style="text-decoration: line-through;">removed</span> and <span style="display:none">secret</span>.</p>
        "#;
        let out = preprocess_semantic_html(html, &HashMap::new(), false);
        assert!(out.contains("<p><em><a href=\"https://www.jm-kantor.fr/\">Jean-Michel Kantor</a>, Emeritus from IMJ</em></p>"));
        assert!(out.contains("<p><small><strong>SERIES</strong> · Note</small></p>"));
        assert!(out.contains("<strong><em>both</em></strong>"));
        assert!(out.contains("<s>removed</s>"));
        assert!(!out.contains("secret"));
    }

    #[test]
    fn test_normalize_prose_id_is_safe_and_idempotent() {
        assert_eq!(normalize_prose_id("fn:1").as_deref(), Some("prose-fn:1"));
        assert_eq!(normalize_prose_id("prose-fn:1").as_deref(), Some("prose-fn:1"));
        assert_eq!(normalize_prose_id("has space"), None);
        assert_eq!(normalize_prose_id(""), None);
    }

    #[test]
    fn test_format_latex_alt_to_html_handles_symbols_and_scripts() {
        assert_eq!(format_latex_alt_to_html(r"p\geq 17"), "p ≥ 17");
        assert_eq!(
            format_latex_alt_to_html(r"\alpha_i + x^{n+1} \in \mathbb{R}"),
            "α<sub>i</sub> + x<sup>n+1</sup> ∈ R"
        );
    }
}
