use std::{collections::HashMap, path::Path, time::Duration};

use quick_xml::{Reader, events::Event};
use roche_core::document_meta::{
    LayoutLine, extract_pdf_pages_lines, render_inline_math_html, render_latex_expr_to_mathml,
};
use tokio::io::AsyncReadExt as _;

use super::{HtmlSanitizer, xml_util};

const PDFTOHTML_TIMEOUT: Duration = Duration::from_secs(12);
const MAX_PDFTOHTML_XML_BYTES: usize = 8 * 1024 * 1024;
const MAX_REFLOW_HTML_BYTES: usize = 2 * 1024 * 1024;
const MAX_FALLBACK_PAGES: usize = 48;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum XmlFontKind {
    #[default]
    Roman,
    MathItalic,
    MathSymbol,
    BlackboardBold,
    Bold,
    TextItalic,
    Mono,
}

impl XmlFontKind {
    const fn is_math(self) -> bool {
        matches!(self, Self::MathItalic | Self::MathSymbol | Self::BlackboardBold)
    }
}

#[derive(Debug, Clone)]
struct XmlFontSpec {
    size: f32,
    kind: XmlFontKind,
}

#[derive(Debug, Clone)]
struct XmlTextSpan {
    top: f32,
    left: f32,
    width: f32,
    height: f32,
    size: f32,
    kind: XmlFontKind,
    text: String,
}

#[derive(Debug, Clone)]
struct XmlPage {
    number: usize,
    width: f32,
    height: f32,
    spans: Vec<XmlTextSpan>,
}

#[derive(Debug, Clone)]
struct VisualLine {
    page_num: usize,
    col_id: u8,
    page_height: f32,
    top: f32,
    left: f32,
    right: f32,
    height: f32,
    dominant_size: f32,
    spans: Vec<XmlTextSpan>,
    eqno: Option<String>,
}

/// Extracts reflowable, reading-friendly semantic HTML (with native `MathML` equations and section
/// headings for Reader TOC) from a PDF file or raw PDF bytes.
///
/// Uses Poppler `pdftohtml -stdout -xml -i -nodrm` when available on disk for high-fidelity
/// font/layout classification, and falls back to `roche-core`'s pure-Rust multi-page extractor.
pub async fn extract_reflowed_pdf_html(
    pdf_path: Option<&Path>,
    pdf_bytes: Option<Vec<u8>>,
    strip_title: Option<&str>,
) -> Option<String> {
    if let Some(path) = pdf_path
        && let Some(xml) = run_pdftohtml_xml(path).await
    {
        let title_owned = strip_title.map(ToOwned::to_owned);
        if let Ok(Some(html)) =
            tokio::task::spawn_blocking(move || reflow_pdftohtml_xml(&xml, title_owned.as_deref()))
                .await
        {
            return Some(html);
        }
    }

    let bytes = match pdf_bytes {
        Some(b) => Some(b),
        None => match pdf_path {
            Some(path) => tokio::fs::read(path).await.ok(),
            None => None,
        },
    }?;

    let title_owned = strip_title.map(ToOwned::to_owned);
    tokio::task::spawn_blocking(move || reflow_pdf_bytes_fallback(&bytes, title_owned.as_deref()))
        .await
        .ok()
        .flatten()
}

async fn run_pdftohtml_xml(pdf_path: &Path) -> Option<String> {
    let mut cmd = tokio::process::Command::new("pdftohtml");
    cmd.arg("-stdout")
        .arg("-xml")
        .arg("-i")
        .arg("-nodrm")
        .arg("-enc")
        .arg("UTF-8")
        .arg(pdf_path)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .kill_on_drop(true);
    let mut child = cmd.spawn().ok()?;
    let stdout = child.stdout.take()?;
    let read_fut = async {
        let mut buf = Vec::new();
        let limit = u64::try_from(MAX_PDFTOHTML_XML_BYTES).unwrap_or(u64::MAX);
        let mut reader = stdout.take(limit.saturating_add(1));
        reader.read_to_end(&mut buf).await.ok()?;
        if buf.len() > MAX_PDFTOHTML_XML_BYTES {
            let _ = child.kill().await;
            let _ = child.wait().await;
            buf.truncate(MAX_PDFTOHTML_XML_BYTES);
            return Some((buf, true));
        }
        let status = child.wait().await.ok()?;
        status.success().then_some((buf, false))
    };
    let Ok(Some((buf, _truncated))) = tokio::time::timeout(PDFTOHTML_TIMEOUT, read_fut).await
    else {
        let _ = child.kill().await;
        let _ = child.wait().await;
        return None;
    };
    if buf.is_empty() {
        return None;
    }
    let xml = String::from_utf8_lossy(&buf).into_owned();
    xml.contains("<page").then_some(xml)
}

/// Converts Poppler `pdftohtml -xml` output into sanitized semantic HTML with `MathML` and headings.
#[must_use]
#[allow(clippy::too_many_lines)]
pub fn reflow_pdftohtml_xml(xml: &str, strip_title: Option<&str>) -> Option<String> {
    let pages = parse_pdftohtml_pages(xml);
    if pages.is_empty() {
        return None;
    }

    let body_size = estimate_dominant_body_size(&pages);
    let mut page_lines: Vec<Vec<VisualLine>> =
        pages.iter().map(|p| build_visual_lines(p, body_size)).collect();

    let col_geoms = estimate_column_geometries(&page_lines, body_size);
    let repeating_hf = detect_repeating_headers_footers(&page_lines);

    // Filter headers, footers, standalone page numbers, and page-1 bibliographic cover block
    for (idx, lines) in page_lines.iter_mut().enumerate() {
        lines.retain(|l| !is_header_footer_or_page_num(l, &repeating_hf, body_size));
        if idx == 0 {
            strip_page1_frontmatter(lines, body_size, strip_title);
        }
    }

    let mut html = String::with_capacity(32 * 1024);
    let mut open_para = false;
    let mut prev_line_meta: Option<(f32, f32, bool)> = None; // (top, right, ended_hyphen)
    let mut prev_page = 0usize;
    let mut prev_col = 0u8;

    for lines in &page_lines {
        for line in lines {
            if html.len() >= MAX_REFLOW_HTML_BYTES {
                break;
            }
            let plain = line_plain_text(line);
            if plain.is_empty() {
                continue;
            }
            let (body_left, body_right, typical_step) = col_geoms[usize::from(line.col_id.min(2))];

            if let Some((heading_tag, heading_html)) = classify_heading(line, &plain, body_size) {
                if open_para {
                    html.push_str("</p>\n");
                    open_para = false;
                }
                html.push('<');
                html.push_str(heading_tag);
                html.push('>');
                html.push_str(&heading_html);
                html.push_str("</");
                html.push_str(heading_tag);
                html.push_str(">\n");
                prev_line_meta = None;
                prev_page = line.page_num;
                prev_col = line.col_id;
                continue;
            }

            if is_display_equation_line(line, &plain, body_left, body_right, body_size) {
                if open_para {
                    html.push_str("</p>\n");
                    open_para = false;
                }
                let eq_latex = render_line_as_latex_expr(line, body_size);
                let mathml = render_latex_expr_to_mathml(&eq_latex, true);
                html.push_str("<figure class=\"paper-equation\">");
                html.push_str(&mathml);
                if let Some(ref eqno) = line.eqno {
                    html.push_str("<span class=\"paper-eqno\">");
                    push_html_escaped(&mut html, eqno);
                    html.push_str("</span>");
                }
                html.push_str("</figure>\n");
                prev_line_meta = None;
                prev_page = line.page_num;
                prev_col = line.col_id;
                continue;
            }

            let same_flow = prev_page == line.page_num && prev_col == line.col_id;
            let starts_new_para = !open_para
                || should_start_new_paragraph(
                    line,
                    &plain,
                    prev_line_meta,
                    same_flow,
                    body_left,
                    body_right,
                    typical_step,
                );

            let rendered_inline = render_visual_line_inline_html(line, body_size);
            if rendered_inline.trim().is_empty() {
                continue;
            }

            if starts_new_para {
                if open_para {
                    html.push_str("</p>\n");
                }
                html.push_str("<p>");
                html.push_str(&rendered_inline);
                open_para = true;
            } else {
                let ended_hyphen = prev_line_meta.is_some_and(|(_, _, h)| h);
                if ended_hyphen && plain.chars().next().is_some_and(char::is_lowercase) {
                    if html.ends_with('-') {
                        html.pop();
                    }
                } else if !html.ends_with(' ') && !rendered_inline.starts_with(' ') {
                    html.push(' ');
                }
                html.push_str(&rendered_inline);
            }

            let ends_hyphen = plain.ends_with('-')
                && !plain.ends_with(" -")
                && plain.len() >= 3
                && plain.chars().rev().nth(1).is_some_and(char::is_alphabetic);
            prev_line_meta = Some((line.top, line.right, ends_hyphen));
            prev_page = line.page_num;
            prev_col = line.col_id;
        }
    }

    if open_para {
        html.push_str("</p>\n");
    }

    if html.trim().is_empty() {
        return None;
    }

    let cleaned = HtmlSanitizer::clean(&html);
    let plain_len = HtmlSanitizer::plain_text_excerpt_from_clean(&cleaned, 200).len();
    (plain_len >= 40).then_some(cleaned)
}

fn reflow_pdf_bytes_fallback(pdf_bytes: &[u8], strip_title: Option<&str>) -> Option<String> {
    let pages = extract_pdf_pages_lines(pdf_bytes, MAX_FALLBACK_PAGES);
    if pages.is_empty() {
        return None;
    }
    reflow_layout_line_pages(&pages, strip_title)
}

fn reflow_layout_line_pages(
    pages: &[Vec<LayoutLine>],
    strip_title: Option<&str>,
) -> Option<String> {
    let mut sizes: Vec<f32> = pages
        .iter()
        .flatten()
        .filter(|l| l.font_size > 1.0 && l.text.len() >= 25)
        .map(|l| l.font_size)
        .collect();
    if sizes.is_empty() {
        sizes = pages.iter().flatten().filter(|l| l.font_size > 1.0).map(|l| l.font_size).collect();
    }
    sizes.sort_by(f32::total_cmp);
    let body_size = sizes.get(sizes.len() / 2).copied().unwrap_or(10.0);

    let mut html = String::with_capacity(16 * 1024);
    let mut open_para = false;
    let strip_title_norm = strip_title.map(normalize_cmp_text);

    for (page_idx, page) in pages.iter().enumerate() {
        let start_idx = if page_idx == 0 {
            find_page1_body_start_plain(page, strip_title_norm.as_deref())
        } else {
            0
        };
        let mut prev_y: Option<f32> = None;
        let mut prev_ended_hyphen = false;

        for line in page.iter().skip(start_idx) {
            if html.len() >= MAX_REFLOW_HTML_BYTES {
                break;
            }
            let text = line.text.trim();
            if text.is_empty() || is_standalone_page_number(text) {
                continue;
            }
            if let Some(tag) = classify_plain_heading(text, line.font_size, body_size) {
                if open_para {
                    html.push_str("</p>\n");
                    open_para = false;
                }
                html.push('<');
                html.push_str(tag);
                html.push('>');
                html.push_str(&render_inline_math_html(text));
                html.push_str("</");
                html.push_str(tag);
                html.push_str(">\n");
                prev_y = None;
                prev_ended_hyphen = false;
                continue;
            }

            let big_gap = prev_y.is_some_and(|py| (py - line.y).abs() > body_size * 1.9);
            let lead_in = starts_new_structural_block(text);
            let rendered = render_inline_math_html(text);

            if !open_para || big_gap || lead_in {
                if open_para {
                    html.push_str("</p>\n");
                }
                html.push_str("<p>");
                html.push_str(&rendered);
                open_para = true;
            } else {
                if prev_ended_hyphen && text.chars().next().is_some_and(char::is_lowercase) {
                    if html.ends_with('-') {
                        html.pop();
                    }
                } else {
                    html.push(' ');
                }
                html.push_str(&rendered);
            }
            prev_y = Some(line.y);
            prev_ended_hyphen = text.ends_with('-')
                && !text.ends_with(" -")
                && text.len() >= 3
                && text.chars().rev().nth(1).is_some_and(char::is_alphabetic);
        }
    }

    if open_para {
        html.push_str("</p>\n");
    }
    if html.trim().is_empty() {
        return None;
    }
    let cleaned = HtmlSanitizer::clean(&html);
    let plain_len = HtmlSanitizer::plain_text_excerpt_from_clean(&cleaned, 200).len();
    (plain_len >= 40).then_some(cleaned)
}

#[allow(clippy::too_many_lines)]
fn parse_pdftohtml_pages(xml: &str) -> Vec<XmlPage> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().check_end_names = false;

    let mut pages = Vec::new();
    let mut fonts: HashMap<String, XmlFontSpec> = HashMap::new();
    let mut current_page: Option<XmlPage> = None;

    let mut in_text = false;
    let mut cur_top = 0.0_f32;
    let mut cur_left = 0.0_f32;
    let mut cur_width = 0.0_f32;
    let mut cur_height = 0.0_f32;
    let mut cur_font = String::new();
    let mut cur_bold_tag = false;
    let mut cur_italic_tag = false;
    let mut cur_buf = String::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(tag)) => {
                let name = tag.name().as_ref().to_ascii_lowercase();
                match name.as_str() {
                    "page" => {
                        let number = xml_util::find_non_empty_attr(&tag, "number")
                            .and_then(|v| v.parse::<usize>().ok())
                            .unwrap_or(pages.len() + 1);
                        let width = parse_attr_f32(&tag, "width").unwrap_or(612.0);
                        let height = parse_attr_f32(&tag, "height").unwrap_or(792.0);
                        current_page = Some(XmlPage { number, width, height, spans: Vec::new() });
                    }
                    "fontspec" => {
                        record_fontspec(&tag, &mut fonts);
                    }
                    "text" => {
                        in_text = true;
                        cur_top = parse_attr_f32(&tag, "top").unwrap_or(0.0);
                        cur_left = parse_attr_f32(&tag, "left").unwrap_or(0.0);
                        cur_width = parse_attr_f32(&tag, "width").unwrap_or(0.0);
                        cur_height = parse_attr_f32(&tag, "height").unwrap_or(12.0);
                        cur_font = xml_util::find_non_empty_attr(&tag, "font").unwrap_or_default();
                        cur_bold_tag = false;
                        cur_italic_tag = false;
                        cur_buf.clear();
                    }
                    "b" | "strong" if in_text => cur_bold_tag = true,
                    "i" | "em" if in_text => cur_italic_tag = true,
                    _ => {}
                }
            }
            Ok(Event::Empty(tag)) => {
                let name = tag.name().as_ref().to_ascii_lowercase();
                if name == "fontspec" {
                    record_fontspec(&tag, &mut fonts);
                }
            }
            Ok(Event::Text(text)) if in_text => {
                let decoded = xml_util::decode_text(&text);
                cur_buf.push_str(&decoded);
            }
            Ok(Event::GeneralRef(entity)) if in_text => {
                let decoded = xml_util::decode_entity(&entity);
                cur_buf.push_str(&decoded);
            }
            Ok(Event::End(tag)) => {
                let name = tag.name().as_ref().to_ascii_lowercase();
                match name.as_str() {
                    "text" if in_text => {
                        in_text = false;
                        let cleaned = normalize_pdf_glyphs(&cur_buf);
                        if !cleaned.trim().is_empty()
                            && let Some(ref mut page) = current_page
                        {
                            let spec = fonts.get(&cur_font);
                            let size = spec.map_or_else(|| cur_height.max(6.0), |s| s.size);
                            let mut kind = spec.map_or(XmlFontKind::Roman, |s| s.kind);
                            if kind == XmlFontKind::Roman {
                                if cur_bold_tag {
                                    kind = XmlFontKind::Bold;
                                } else if cur_italic_tag {
                                    kind = XmlFontKind::TextItalic;
                                }
                            }
                            page.spans.push(XmlTextSpan {
                                top: cur_top,
                                left: cur_left,
                                width: cur_width,
                                height: cur_height,
                                size,
                                kind,
                                text: cleaned,
                            });
                        }
                        cur_buf.clear();
                    }
                    "page" => {
                        if let Some(page) = current_page.take() {
                            pages.push(page);
                        }
                    }
                    _ => {}
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
    }
    if let Some(page) = current_page {
        pages.push(page);
    }
    pages
}

fn record_fontspec(
    tag: &quick_xml::events::BytesStart<'_>,
    fonts: &mut HashMap<String, XmlFontSpec>,
) {
    let Some(id) = xml_util::find_non_empty_attr(tag, "id") else {
        return;
    };
    let size = parse_attr_f32(tag, "size").unwrap_or(12.0).max(4.0);
    let family = xml_util::find_non_empty_attr(tag, "family").unwrap_or_default();
    let kind = classify_xml_font_family(&family);
    fonts.insert(id, XmlFontSpec { size, kind });
}

fn parse_attr_f32(tag: &quick_xml::events::BytesStart<'_>, name: &str) -> Option<f32> {
    xml_util::find_non_empty_attr(tag, name)?.trim().parse::<f32>().ok()
}

fn classify_xml_font_family(family: &str) -> XmlFontKind {
    let stripped = match family.split_once('+') {
        Some((tag, rest)) if tag.len() == 6 && tag.bytes().all(|b| b.is_ascii_uppercase()) => rest,
        _ => family,
    };
    let lower = stripped.to_ascii_lowercase();
    if lower.contains("msbm")
        || lower.contains("bbold")
        || lower.contains("dsrom")
        || lower.contains("mathbb")
    {
        return XmlFontKind::BlackboardBold;
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
        return XmlFontKind::MathItalic;
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
        return XmlFontKind::MathSymbol;
    }
    if lower.contains("cmti")
        || lower.contains("cmsl")
        || lower.contains("lmti")
        || lower.contains("italic")
        || lower.contains("oblique")
        || lower.contains("slant")
    {
        return XmlFontKind::TextItalic;
    }
    if lower.contains("cmbx")
        || lower.contains("lmbx")
        || lower.contains("bold")
        || lower.contains("black")
        || lower.contains("heavy")
        || lower.contains("demi")
    {
        return XmlFontKind::Bold;
    }
    if lower.contains("cmtt")
        || lower.contains("lmtt")
        || lower.contains("mono")
        || lower.contains("courier")
        || lower.contains("consolas")
    {
        return XmlFontKind::Mono;
    }
    XmlFontKind::Roman
}

fn normalize_pdf_glyphs(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.chars() {
        match ch {
            '\u{FB00}' => out.push_str("ff"),
            '\u{FB01}' => out.push_str("fi"),
            '\u{FB02}' => out.push_str("fl"),
            '\u{FB03}' => out.push_str("ffi"),
            '\u{FB04}' => out.push_str("ffl"),
            '\u{00AD}' => {} // soft hyphen
            '\u{00A0}' => out.push(' '),
            c if c.is_control() && c != '\t' && c != '\n' => {}
            other => out.push(other),
        }
    }
    out
}

fn estimate_dominant_body_size(pages: &[XmlPage]) -> f32 {
    let mut counts: HashMap<i32, usize> = HashMap::new();
    for page in pages {
        for span in &page.spans {
            #[allow(clippy::cast_possible_truncation)]
            let bucket = span.size.round() as i32;
            if (6..=40).contains(&bucket) {
                *counts.entry(bucket).or_insert(0) += span.text.chars().count();
            }
        }
    }
    counts.into_iter().max_by_key(|&(_, weight)| weight).map_or(14.0, |(bucket, _)| {
        #[allow(clippy::cast_precision_loss)]
        let f = bucket as f32;
        f
    })
}

fn build_visual_lines(page: &XmlPage, body_size: f32) -> Vec<VisualLine> {
    if page.spans.is_empty() {
        return Vec::new();
    }
    if let Some(mid_x) = detect_two_column_gutter(page) {
        let mut top_full = Vec::new();
        let mut left_col = Vec::new();
        let mut right_col = Vec::new();
        let mut bottom_full = Vec::new();
        let mut min_col_top = f32::INFINITY;
        let mut max_col_top = 0.0_f32;
        for sp in &page.spans {
            let crosses = sp.left < mid_x - 6.0 && sp.left + sp.width > mid_x + 6.0;
            if !crosses && sp.top >= page.height * 0.08 && sp.top <= page.height * 0.92 {
                min_col_top = min_col_top.min(sp.top);
                max_col_top = max_col_top.max(sp.top);
            }
        }
        for sp in &page.spans {
            let crosses = sp.left < mid_x - 6.0 && sp.left + sp.width > mid_x + 6.0;
            if crosses || sp.top < min_col_top - body_size {
                if sp.top > max_col_top {
                    bottom_full.push(sp.clone());
                } else {
                    top_full.push(sp.clone());
                }
            } else if sp.left + sp.width <= mid_x + 8.0 {
                left_col.push(sp.clone());
            } else {
                right_col.push(sp.clone());
            }
        }
        let mut out = cluster_spans_into_lines(top_full, page, body_size, 0);
        out.extend(cluster_spans_into_lines(left_col, page, body_size, 1));
        out.extend(cluster_spans_into_lines(right_col, page, body_size, 2));
        out.extend(cluster_spans_into_lines(bottom_full, page, body_size, 0));
        return out;
    }
    cluster_spans_into_lines(page.spans.clone(), page, body_size, 0)
}

fn detect_two_column_gutter(page: &XmlPage) -> Option<f32> {
    if page.width < 300.0 || page.spans.len() < 10 {
        return None;
    }
    let mid_x = page.width * 0.5;
    let mut left_count = 0usize;
    let mut right_count = 0usize;
    let mut crossing_count = 0usize;
    let mut left_min_top = f32::INFINITY;
    let mut left_max_top = 0.0_f32;
    let mut right_min_top = f32::INFINITY;
    let mut right_max_top = 0.0_f32;
    for sp in &page.spans {
        if sp.top < page.height * 0.08 || sp.top > page.height * 0.92 {
            continue;
        }
        let right = sp.left + sp.width;
        if right <= mid_x + 4.0 {
            left_count += 1;
            left_min_top = left_min_top.min(sp.top);
            left_max_top = left_max_top.max(sp.top);
        } else if sp.left >= mid_x - 4.0 {
            right_count += 1;
            right_min_top = right_min_top.min(sp.top);
            right_max_top = right_max_top.max(sp.top);
        } else {
            crossing_count += 1;
        }
    }
    let total = left_count + right_count + crossing_count;
    let vertically_overlapping = left_min_top < right_max_top && right_min_top < left_max_top;
    (left_count >= 5 && right_count >= 5 && crossing_count * 10 < total && vertically_overlapping)
        .then_some(mid_x)
}

fn cluster_spans_into_lines(
    mut sorted: Vec<XmlTextSpan>,
    page: &XmlPage,
    body_size: f32,
    col_id: u8,
) -> Vec<VisualLine> {
    if sorted.is_empty() {
        return Vec::new();
    }
    sorted.sort_by(|a, b| {
        let a_mid = a.height.mul_add(0.5, a.top);
        let b_mid = b.height.mul_add(0.5, b.top);
        a_mid.total_cmp(&b_mid).then_with(|| a.left.total_cmp(&b.left))
    });

    let mut lines: Vec<VisualLine> = Vec::new();
    for span in sorted {
        let span_mid = span.height.mul_add(0.5, span.top);
        let attached = lines.last_mut().and_then(|last| {
            let last_mid = last.height.mul_add(0.5, last.top);
            let tol = last.height.max(span.height).max(body_size) * 0.48;
            if (span_mid - last_mid).abs() <= tol { Some(last) } else { None }
        });

        if let Some(line) = attached {
            line.left = line.left.min(span.left);
            line.right = line.right.max(span.left + span.width);
            if span.size > line.dominant_size {
                line.dominant_size = span.size;
                line.top = span.top;
                line.height = span.height;
            }
            line.spans.push(span);
        } else {
            let right = span.left + span.width;
            lines.push(VisualLine {
                page_num: page.number,
                col_id,
                page_height: page.height,
                top: span.top,
                left: span.left,
                right,
                height: span.height,
                dominant_size: span.size,
                spans: vec![span],
                eqno: None,
            });
        }
    }

    for line in &mut lines {
        line.spans.sort_by(|a, b| a.left.total_cmp(&b.left));
        // Split off right-aligned equation number `(1)` / `(7.1)` when separated by wide gap
        if line.spans.len() >= 2
            && let Some(last) = line.spans.last()
            && let Some(prev) = line.spans.get(line.spans.len() - 2)
        {
            let gap = last.left - (prev.left + prev.width);
            let trimmed = last.text.trim();
            if gap >= body_size * 2.4 && is_equation_number_token(trimmed) {
                line.eqno = Some(trimmed.to_owned());
                line.spans.pop();
                line.right = line.spans.iter().map(|s| s.left + s.width).fold(line.left, f32::max);
            }
        }
    }

    lines
}

fn is_equation_number_token(s: &str) -> bool {
    let Some(inner) = s.strip_prefix('(').and_then(|r| r.strip_suffix(')')) else {
        return false;
    };
    !inner.is_empty()
        && inner.len() <= 10
        && inner.chars().any(|c| c.is_ascii_digit())
        && inner.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '*'))
}

fn estimate_column_geometries(
    page_lines: &[Vec<VisualLine>],
    body_size: f32,
) -> [(f32, f32, f32); 3] {
    let fallback = estimate_page_geometry_for_col(page_lines, body_size, None).unwrap_or((
        100.0,
        520.0,
        body_size * 1.25,
    ));
    let col0 = estimate_page_geometry_for_col(page_lines, body_size, Some(0)).unwrap_or(fallback);
    let col1 = estimate_page_geometry_for_col(page_lines, body_size, Some(1)).unwrap_or(col0);
    let col2 = estimate_page_geometry_for_col(page_lines, body_size, Some(2)).unwrap_or(col0);
    [col0, col1, col2]
}

fn estimate_page_geometry_for_col(
    page_lines: &[Vec<VisualLine>],
    body_size: f32,
    col_filter: Option<u8>,
) -> Option<(f32, f32, f32)> {
    let mut lefts = Vec::new();
    let mut rights = Vec::new();
    let mut steps = Vec::new();

    for lines in page_lines {
        let mut prev_top: Option<f32> = None;
        for line in lines {
            if col_filter.is_some_and(|c| line.col_id != c) {
                continue;
            }
            let plain_len: usize = line.spans.iter().map(|s| s.text.len()).sum();
            let min_len = if col_filter.is_some_and(|c| c > 0) { 24 } else { 40 };
            if plain_len >= min_len && (line.dominant_size - body_size).abs() <= body_size * 0.2 {
                lefts.push(line.left);
                rights.push(line.right);
                if let Some(pt) = prev_top {
                    let dy = line.top - pt;
                    if dy > body_size * 0.7 && dy < body_size * 2.5 {
                        steps.push(dy);
                    }
                }
                prev_top = Some(line.top);
            }
        }
    }

    if col_filter.is_some() && lefts.is_empty() {
        return None;
    }

    lefts.sort_by(f32::total_cmp);
    rights.sort_by(f32::total_cmp);
    steps.sort_by(f32::total_cmp);

    let body_left = lefts.get(lefts.len() / 4).copied().unwrap_or(100.0);
    let body_right = rights.get(rights.len().saturating_mul(3) / 4).copied().unwrap_or(520.0);
    let typical_step = steps.get(steps.len() / 2).copied().unwrap_or(body_size * 1.25);
    Some((body_left, body_right, typical_step))
}

fn detect_repeating_headers_footers(page_lines: &[Vec<VisualLine>]) -> HashMap<String, usize> {
    let mut counts: HashMap<String, usize> = HashMap::new();
    for lines in page_lines {
        for line in lines {
            if line.top <= line.page_height * 0.09 || line.top >= line.page_height * 0.90 {
                let key = normalize_running_header_key(&line_plain_text(line));
                if !key.is_empty() && key.len() <= 90 {
                    *counts.entry(key).or_insert(0) += 1;
                }
            }
        }
    }
    counts.retain(|_, c| *c >= 3);
    counts
}

fn normalize_running_header_key(s: &str) -> String {
    s.chars()
        .filter(|c| !c.is_ascii_digit())
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_ascii_lowercase()
}

fn is_header_footer_or_page_num(
    line: &VisualLine,
    repeating: &HashMap<String, usize>,
    body_size: f32,
) -> bool {
    let in_margin_band = line.top <= line.page_height * 0.09 || line.top >= line.page_height * 0.90;
    let plain = line_plain_text(line);
    if plain.is_empty() {
        return true;
    }
    if in_margin_band && is_standalone_page_number(&plain) {
        return true;
    }
    if in_margin_band {
        let key = normalize_running_header_key(&plain);
        if repeating.contains_key(&key) {
            return true;
        }
    }
    // Also skip isolated single-token page number at very bottom even slightly above 0.90
    line.top >= line.page_height * 0.85
        && is_standalone_page_number(&plain)
        && line.dominant_size <= body_size * 1.1
}

fn is_standalone_page_number(text: &str) -> bool {
    let t = text.trim().trim_matches(|c| matches!(c, '-' | '–' | '—' | ' '));
    !t.is_empty() && t.len() <= 4 && t.bytes().all(|b| b.is_ascii_digit())
}

fn strip_page1_frontmatter(lines: &mut Vec<VisualLine>, body_size: f32, strip_title: Option<&str>) {
    // If Abstract or Section 1 appears near the top of page 1, drop the cover title/author/date
    // block above it since Roche's Reader header already renders title, authors, and date.
    let scan_end = lines.len().min(24);
    for (idx, line) in lines[..scan_end].iter().enumerate() {
        let plain = line_plain_text(line);
        let lower = plain.to_ascii_lowercase();
        if lower == "abstract"
            || lower == "abstract."
            || lower.starts_with("abstract.")
            || lower.starts_with("abstract:")
            || is_numbered_section_prefix(&plain)
        {
            if idx > 0 {
                lines.drain(..idx);
            }
            return;
        }
    }

    // Fallback: drop oversized title lines at the very top matching strip_title
    if let Some(title) = strip_title {
        let norm_title = normalize_cmp_text(title);
        let mut drop_count = 0usize;
        for line in lines.iter().take(6) {
            let norm_line = normalize_cmp_text(&line_plain_text(line));
            if !norm_line.is_empty()
                && (line.dominant_size >= body_size * 1.25 || norm_title.contains(&norm_line))
            {
                drop_count += 1;
            } else {
                break;
            }
        }
        if drop_count > 0 {
            lines.drain(..drop_count);
        }
    }
}

fn find_page1_body_start_plain(lines: &[LayoutLine], strip_title_norm: Option<&str>) -> usize {
    let scan_end = lines.len().min(22);
    for (idx, line) in lines[..scan_end].iter().enumerate() {
        let t = line.text.trim();
        let lower = t.to_ascii_lowercase();
        if lower == "abstract"
            || lower == "abstract."
            || lower.starts_with("abstract:")
            || is_numbered_section_prefix(t)
        {
            return idx;
        }
    }
    if let Some(tn) = strip_title_norm {
        let mut idx = 0;
        while idx < lines.len().min(4) && tn.contains(&normalize_cmp_text(&lines[idx].text)) {
            idx += 1;
        }
        return idx;
    }
    0
}

fn normalize_cmp_text(s: &str) -> String {
    s.chars()
        .filter(|c| c.is_alphanumeric() || c.is_whitespace())
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_ascii_lowercase()
}

fn line_plain_text(line: &VisualLine) -> String {
    let mut out = String::new();
    let mut prev_right: Option<f32> = None;
    for sp in &line.spans {
        if let Some(pr) = prev_right
            && sp.left - pr > sp.size * 0.16
            && !out.ends_with(' ')
            && !sp.text.starts_with(' ')
        {
            out.push(' ');
        }
        out.push_str(&sp.text);
        prev_right = Some(sp.left + sp.width);
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn classify_heading(
    line: &VisualLine,
    plain: &str,
    body_size: f32,
) -> Option<(&'static str, String)> {
    let trimmed = plain.trim();
    if trimmed.is_empty() || trimmed.len() > 120 || line.eqno.is_some() {
        return None;
    }
    let lower = trimmed.to_ascii_lowercase();
    if matches!(
        lower.as_str(),
        "abstract"
            | "abstract."
            | "references"
            | "bibliography"
            | "acknowledgments"
            | "acknowledgements"
            | "appendix"
            | "contents"
            | "introduction"
            | "conclusion"
            | "conclusions"
    ) {
        let label = trimmed.trim_end_matches('.');
        return Some(("h2", render_visual_line_heading_html(line, label, body_size)));
    }

    let is_bold_or_large = line.dominant_size >= body_size * 1.12
        || line
            .spans
            .iter()
            .filter(|s| s.text.chars().any(char::is_alphabetic))
            .all(|s| s.kind == XmlFontKind::Bold);

    if !is_bold_or_large {
        return None;
    }

    // Subsections like `2.1 ...` or `3.2.1 ...`
    if is_subsection_prefix(trimmed) && !trimmed.ends_with('.') {
        return Some(("h3", render_visual_line_inline_html_no_strong(line, body_size)));
    }
    // Top-level numbered sections like `1 Introduction` or `2. Preliminaries` or `A Appendix`
    if is_numbered_section_prefix(trimmed) && !trimmed.ends_with('.') {
        return Some(("h2", render_visual_line_inline_html_no_strong(line, body_size)));
    }
    None
}

fn classify_plain_heading(text: &str, font_size: f32, body_size: f32) -> Option<&'static str> {
    let trimmed = text.trim();
    if trimmed.is_empty() || trimmed.len() > 110 {
        return None;
    }
    let lower = trimmed.to_ascii_lowercase();
    if matches!(
        lower.as_str(),
        "abstract" | "references" | "bibliography" | "acknowledgments" | "acknowledgements"
    ) {
        return Some("h2");
    }
    let is_large = font_size >= body_size * 1.12;
    if is_subsection_prefix(trimmed) && !trimmed.ends_with('.') {
        return Some("h3");
    }
    if is_large && is_numbered_section_prefix(trimmed) && !trimmed.ends_with('.') {
        return Some("h2");
    }
    None
}

fn is_numbered_section_prefix(trimmed: &str) -> bool {
    if let Some(after_app) = trimmed.strip_prefix("Appendix ") {
        return after_app.chars().next().is_some_and(|c| c.is_ascii_alphanumeric());
    }
    let mut parts = trimmed.splitn(2, ' ');
    let (Some(num), Some(rest)) = (parts.next(), parts.next()) else {
        return false;
    };
    let clean_num = num.trim_end_matches('.');
    let valid_numeric = !clean_num.is_empty()
        && clean_num.len() <= 2
        && clean_num.bytes().all(|b| b.is_ascii_digit());
    // Require an explicit trailing period for single-letter appendix prefixes (`A. Proof`) so
    // ordinary English titles/sentences starting with `"A "` or `"I "` are never misclassified.
    let valid_appendix_letter = num.ends_with('.')
        && clean_num.len() == 1
        && clean_num.bytes().all(|b| b.is_ascii_uppercase());
    (valid_numeric || valid_appendix_letter)
        && !clean_num.contains('.')
        && rest.chars().next().is_some_and(char::is_uppercase)
        && rest.chars().filter(|c| c.is_alphabetic()).count() >= 3
}

fn is_subsection_prefix(trimmed: &str) -> bool {
    let mut parts = trimmed.splitn(2, ' ');
    let (Some(num), Some(rest)) = (parts.next(), parts.next()) else {
        return false;
    };
    let clean_num = num.trim_end_matches('.');
    clean_num.contains('.')
        && clean_num.len() <= 9
        && clean_num
            .split('.')
            .all(|seg| !seg.is_empty() && seg.bytes().all(|b| b.is_ascii_digit()))
        && rest.chars().next().is_some_and(char::is_uppercase)
        && rest.chars().filter(|c| c.is_alphabetic()).count() >= 3
}

fn is_display_equation_line(
    line: &VisualLine,
    plain: &str,
    body_left: f32,
    body_right: f32,
    body_size: f32,
) -> bool {
    if line.eqno.is_some() {
        return true;
    }
    if plain.len() > 140 || line.dominant_size > body_size * 1.25 {
        return false;
    }
    let has_math_font = line.spans.iter().any(|s| s.kind.is_math());
    if !has_math_font {
        return false;
    }
    let centered = line.left >= body_left + 32.0 && line.right <= body_right - 22.0;
    if !centered {
        return false;
    }
    // Ensure it isn't a centered short English sentence (like a theorem/caption)
    let prose_words = plain
        .split_whitespace()
        .filter(|w| w.len() >= 4 && w.chars().all(char::is_alphabetic) && !is_math_op_word(w))
        .count();
    prose_words <= 2
        && (plain.contains('=')
            || plain.contains('≤')
            || plain.contains('≥')
            || plain.contains('∈')
            || plain.contains('→')
            || plain.contains('∑')
            || plain.contains('∫')
            || plain.len() <= 48)
}

fn should_start_new_paragraph(
    line: &VisualLine,
    plain: &str,
    prev_line_meta: Option<(f32, f32, bool)>,
    same_page: bool,
    body_left: f32,
    body_right: f32,
    typical_step: f32,
) -> bool {
    if starts_new_structural_block(plain) {
        return true;
    }
    let is_indented = line.left >= body_left + 10.0 && line.left <= body_left + 56.0;
    let Some((prev_top, prev_right, _)) = prev_line_meta else {
        return true;
    };
    if same_page && (line.top - prev_top) > typical_step * 1.48 {
        return true;
    }
    if is_indented {
        return true;
    }
    // Previous line ended significantly short of the right margin
    if same_page && prev_right < body_right - 85.0 {
        return true;
    }
    false
}

fn starts_new_structural_block(plain: &str) -> bool {
    let trimmed = plain.trim();
    if trimmed.starts_with('[')
        && let Some(close) = trimmed.find(']')
        && (1..=14).contains(&close)
    {
        return true;
    }
    let first = trimmed.split_whitespace().next().unwrap_or("");
    matches!(
        first,
        "Theorem"
            | "Lemma"
            | "Proposition"
            | "Corollary"
            | "Definition"
            | "Remark"
            | "Example"
            | "Conjecture"
            | "Claim"
            | "Proof."
            | "Proof"
            | "Algorithm"
            | "Note."
            | "Note:"
    )
}

fn render_visual_line_heading_html(_line: &VisualLine, label: &str, _body_size: f32) -> String {
    let mut out = String::new();
    push_html_escaped(&mut out, label);
    out
}

fn render_visual_line_inline_html_no_strong(line: &VisualLine, body_size: f32) -> String {
    render_visual_line_spans(line, body_size, false)
}

fn render_visual_line_inline_html(line: &VisualLine, body_size: f32) -> String {
    render_visual_line_spans(line, body_size, true)
}

fn render_visual_line_spans(line: &VisualLine, body_size: f32, allow_strong: bool) -> String {
    let spans = &line.spans;
    let base_mid = line.height.mul_add(0.5, line.top);
    let base_size = line.dominant_size.max(body_size);
    let mut out = String::new();
    let mut i = 0usize;
    let mut partial_plain_rest: Option<&str> = None;

    while i < spans.len() {
        if let Some(rest) = partial_plain_rest.take() {
            push_html_escaped(&mut out, rest);
            i += 1;
            continue;
        }
        let sp = &spans[i];
        let need_space = i > 0
            && needs_horizontal_space(&spans[i - 1], sp)
            && !out.ends_with(' ')
            && !out.ends_with('(')
            && !out.ends_with('[');

        if sp.kind.is_math() {
            if need_space {
                out.push(' ');
            }
            let (expr, next_i, rest) = consume_xml_math_run(spans, i, base_mid, base_size);
            out.push_str(&render_latex_expr_to_mathml(&expr, false));
            partial_plain_rest = rest;
            i = next_i;
            continue;
        }

        if need_space && !starts_with_tight_punct(&sp.text) {
            out.push(' ');
        }

        let is_script = is_xml_script_span(sp, base_mid, base_size);
        if is_script {
            let tag = if sp.height.mul_add(0.5, sp.top) < base_mid { "sup" } else { "sub" };
            out.push('<');
            out.push_str(tag);
            out.push('>');
            push_html_escaped(&mut out, sp.text.trim());
            out.push_str("</");
            out.push_str(tag);
            out.push('>');
            i += 1;
            continue;
        }

        match sp.kind {
            XmlFontKind::Bold if allow_strong => {
                out.push_str("<strong>");
                push_html_escaped(&mut out, &sp.text);
                out.push_str("</strong>");
            }
            XmlFontKind::TextItalic => {
                out.push_str("<em>");
                push_html_escaped(&mut out, &sp.text);
                out.push_str("</em>");
            }
            XmlFontKind::Mono => {
                out.push_str("<code>");
                push_html_escaped(&mut out, &sp.text);
                out.push_str("</code>");
            }
            _ => {
                push_html_escaped(&mut out, &sp.text);
            }
        }
        i += 1;
    }

    out.replace("</em><em>", "")
        .replace("</em> <em>", " ")
        .replace("</strong><strong>", "")
        .replace("</strong> <strong>", " ")
}

fn render_line_as_latex_expr(line: &VisualLine, body_size: f32) -> String {
    let base_mid = line.height.mul_add(0.5, line.top);
    let base_size = line.dominant_size.max(body_size);
    let mut expr = String::new();
    for sp in &line.spans {
        let trimmed = sp.text.trim();
        if trimmed.is_empty() {
            continue;
        }
        let is_script = is_xml_script_span(sp, base_mid, base_size);
        append_xml_math_token(&mut expr, sp, base_mid, is_script);
    }
    expr
}

fn consume_xml_math_run(
    spans: &[XmlTextSpan],
    start: usize,
    base_mid: f32,
    base_size: f32,
) -> (String, usize, Option<&str>) {
    let mut expr = String::new();
    let mut open_parens = 0usize;
    let mut open_brackets = 0usize;
    let mut j = start;
    let mut trailing_plain: Option<&str> = None;

    while j < spans.len() {
        let sp = &spans[j];
        let trimmed = sp.text.trim();
        if trimmed.is_empty() {
            j += 1;
            continue;
        }
        let is_script = is_xml_script_span(sp, base_mid, base_size);
        if sp.kind.is_math() {
            append_xml_math_token(&mut expr, sp, base_mid, is_script);
            update_delims(trimmed, &mut open_parens, &mut open_brackets);
            j += 1;
            continue;
        }
        if is_script && is_simple_script_text(trimmed) {
            append_xml_math_token(&mut expr, sp, base_mid, true);
            j += 1;
            continue;
        }
        if sp.kind == XmlFontKind::Roman {
            let next_math = has_next_xml_math(spans, j + 1, base_mid, base_size);
            if next_math && is_xml_math_glue(trimmed) {
                append_xml_roman_glue(&mut expr, trimmed);
                update_delims(trimmed, &mut open_parens, &mut open_brackets);
                j += 1;
                continue;
            }
            if is_closing_delim(trimmed, open_parens, open_brackets) {
                append_xml_roman_glue(&mut expr, trimmed);
                update_delims(trimmed, &mut open_parens, &mut open_brackets);
                j += 1;
                continue;
            }
            if (open_parens > 0 || open_brackets > 0)
                && let Some((closers, rest)) =
                    split_leading_xml_closers(&sp.text, open_parens, open_brackets)
            {
                append_xml_roman_glue(&mut expr, closers);
                update_delims(closers, &mut open_parens, &mut open_brackets);
                trailing_plain = Some(rest);
                break;
            }
            if is_trailing_number_after_op(&expr, trimmed) {
                append_xml_roman_glue(&mut expr, trimmed);
                j += 1;
                continue;
            }
        }
        break;
    }

    (expr, j.max(start + 1), trailing_plain)
}

fn split_leading_xml_closers(
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

fn is_xml_script_span(sp: &XmlTextSpan, base_mid: f32, base_size: f32) -> bool {
    sp.size <= base_size * 0.80
        && (sp.height.mul_add(0.5, sp.top) - base_mid).abs() >= base_size * 0.14
}

fn is_simple_script_text(trimmed: &str) -> bool {
    !trimmed.is_empty()
        && trimmed.len() <= 16
        && trimmed
            .chars()
            .all(|c| c.is_alphanumeric() || matches!(c, '+' | '-' | '=' | '(' | ')' | ',' | '*'))
}

fn has_next_xml_math(spans: &[XmlTextSpan], start: usize, base_mid: f32, base_size: f32) -> bool {
    let mut glue_seen = 0usize;
    for sp in spans.iter().skip(start) {
        let t = sp.text.trim();
        if t.is_empty() {
            continue;
        }
        if sp.kind.is_math()
            || (is_xml_script_span(sp, base_mid, base_size) && is_simple_script_text(t))
        {
            return true;
        }
        if sp.kind == XmlFontKind::Roman && is_xml_math_glue(t) && glue_seen < 3 {
            glue_seen += 1;
            continue;
        }
        return false;
    }
    false
}

fn is_xml_math_glue(trimmed: &str) -> bool {
    if is_math_op_word(trimmed) {
        return true;
    }
    if trimmed.len() > 14 {
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
                    | '−'
                    | '='
                    | '<'
                    | '>'
                    | '≤'
                    | '≥'
                    | '≠'
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

fn is_math_op_word(word: &str) -> bool {
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

fn is_closing_delim(trimmed: &str, open_parens: usize, open_brackets: usize) -> bool {
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

fn is_trailing_number_after_op(expr: &str, trimmed: &str) -> bool {
    if trimmed.is_empty() || trimmed.len() > 8 || !trimmed.bytes().all(|b| b.is_ascii_digit()) {
        return false;
    }
    let tail = expr.trim_end();
    tail.ends_with(['=', '<', '>', '+', '-', '−', '≤', '≥', '≠', '≈', '≡'])
        || tail.ends_with("\\leq")
        || tail.ends_with("\\geq")
        || tail.ends_with("\\neq")
        || tail.ends_with("\\cdot")
        || tail.ends_with("\\times")
}

fn update_delims(trimmed: &str, open_parens: &mut usize, open_brackets: &mut usize) {
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

fn append_xml_math_token(expr: &mut String, sp: &XmlTextSpan, base_mid: f32, is_script: bool) {
    let trimmed = sp.text.trim();
    if trimmed.is_empty() {
        return;
    }
    let tok = if sp.kind == XmlFontKind::BlackboardBold
        && trimmed.chars().all(|c| c.is_ascii_uppercase())
    {
        format!("\\mathbb{{{trimmed}}}")
    } else if sp.kind == XmlFontKind::Roman && is_math_op_word(trimmed) {
        format!("\\{trimmed}")
    } else {
        trimmed.to_owned()
    };

    if is_script && !expr.is_empty() {
        let marker = if sp.height.mul_add(0.5, sp.top) < base_mid { '^' } else { '_' };
        expr.push(marker);
        expr.push('{');
        expr.push_str(&tok);
        expr.push('}');
        return;
    }

    if !expr.is_empty() && needs_latex_space(expr, &tok) {
        expr.push(' ');
    }
    expr.push_str(&tok);
}

fn append_xml_roman_glue(expr: &mut String, trimmed: &str) {
    if is_math_op_word(trimmed) {
        if !expr.is_empty() && !expr.ends_with([' ', '(', '[', '{']) {
            expr.push(' ');
        }
        expr.push('\\');
        expr.push_str(trimmed);
        return;
    }
    if !expr.is_empty() && needs_latex_space(expr, trimmed) {
        expr.push(' ');
    }
    expr.push_str(trimmed);
}

fn needs_latex_space(expr: &str, next: &str) -> bool {
    if expr.ends_with([' ', '(', '[', '{', '^', '_'])
        || next.starts_with([')', ']', '}', ',', ';', ':'])
    {
        return false;
    }
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

fn needs_horizontal_space(prev: &XmlTextSpan, cur: &XmlTextSpan) -> bool {
    let gap = cur.left - (prev.left + prev.width);
    gap > prev.size.min(cur.size) * 0.16
}

fn starts_with_tight_punct(s: &str) -> bool {
    s.starts_with([',', '.', ';', ':', ')', ']', '?', '!'])
}

fn push_html_escaped(out: &mut String, s: &str) {
    for ch in s.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            other => out.push(other),
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_reflow_pdftohtml_xml_extracts_headings_mathml_and_equations() {
        let xml = r##"<?xml version="1.0" encoding="UTF-8"?>
<pdf2xml producer="poppler">
<page number="1" position="absolute" top="0" left="0" height="1188" width="918">
  <fontspec id="0" size="25" family="FRQZFX+CMR17" color="#000000"/>
  <fontspec id="1" size="18" family="ENDZGJ+CMMI12" color="#000000"/>
  <fontspec id="2" size="14" family="XERXVS+CMBX10" color="#000000"/>
  <fontspec id="3" size="14" family="GLWDLZ+CMR10" color="#000000"/>
  <fontspec id="4" size="14" family="KICPUL+CMMI10" color="#000000"/>
  <fontspec id="5" size="14" family="GJXXPT+CMTI10" color="#000000"/>
  <text top="162" left="274" width="270" height="27" font="0">On the recently proposed</text>
  <text top="162" left="544" width="12" height="27" font="1">o</text>
  <text top="220" left="390" width="140" height="16" font="3">David Harvey</text>
  <text top="290" left="430" width="58" height="15" font="2">Abstract</text>
  <text top="320" left="180" width="210" height="15" font="3">We examine the proposed</text>
  <text top="320" left="396" width="8" height="15" font="4">o</text>
  <text top="320" left="404" width="6" height="15" font="3">(</text>
  <text top="320" left="410" width="10" height="15" font="4">n</text>
  <text top="320" left="422" width="22" height="15" font="3">log</text>
  <text top="320" left="446" width="10" height="15" font="4">n</text>
  <text top="320" left="456" width="6" height="15" font="3">)</text>
  <text top="320" left="466" width="260" height="15" font="3">integer multiplica-</text>
  <text top="338" left="180" width="280" height="15" font="3">tion algorithm in</text>
  <text top="338" left="468" width="60" height="15" font="5">detail.</text>
  <text top="390" left="180" width="180" height="17" font="2">1 Introduction</text>
  <text top="420" left="180" width="540" height="15" font="3">The primary complexity bound satisfies the recurrence below:</text>
  <text top="456" left="360" width="14" height="15" font="4">T</text>
  <text top="456" left="374" width="6" height="15" font="3">(</text>
  <text top="456" left="380" width="10" height="15" font="4">n</text>
  <text top="456" left="390" width="32" height="15" font="3">) = 2</text>
  <text top="456" left="424" width="14" height="15" font="4">n</text>
  <text top="456" left="690" width="28" height="15" font="3">(1.1)</text>
  <text top="1120" left="450" width="10" height="14" font="3">1</text>
</page>
</pdf2xml>"##;

        let html = reflow_pdftohtml_xml(xml, Some("On the recently proposed o(n log n)")).unwrap();
        assert!(!html.contains("David Harvey"), "frontmatter should be stripped: {html}");
        assert!(html.contains("<h2>Abstract</h2>"), "missing Abstract heading: {html}");
        assert!(html.contains("<h2>1 Introduction</h2>"), "missing section heading: {html}");
        assert!(html.contains("multiplication algorithm"), "de-hyphenation failed: {html}");
        assert!(html.contains("<em>detail.</em>"), "CMTI italic lost: {html}");
        assert!(
            html.contains("<math><mi>o</mi><mo stretchy=\"false\">(</mo><mi>n</mi>"),
            "inline MathML missing: {html}"
        );
        assert!(
            html.contains("<figure class=\"paper-equation\"><math display=\"block\">"),
            "display eq missing: {html}"
        );
        assert!(html.contains("<span class=\"paper-eqno\">(1.1)</span>"), "eqno missing: {html}");
    }

    #[test]
    fn test_reflow_handles_a_prefixed_titles_sentences_and_two_column_pages() {
        // 1. Paper title starting with "A " must still strip frontmatter; body-size sentence
        //    starting with "A " must NOT become <h2>; bold "A. Proof" MUST become <h2>.
        let xml_single = r##"<?xml version="1.0" encoding="UTF-8"?>
<pdf2xml producer="poppler">
<page number="1" position="absolute" top="0" left="0" height="1188" width="918">
  <fontspec id="0" size="24" family="CMR17" color="#000000"/>
  <fontspec id="1" size="14" family="CMBX10" color="#000000"/>
  <fontspec id="2" size="14" family="CMR10" color="#000000"/>
  <text top="140" left="220" width="460" height="26" font="0">A Fast Algorithm for Integer Multiplication</text>
  <text top="190" left="380" width="140" height="15" font="2">Ada Lovelace</text>
  <text top="250" left="430" width="60" height="15" font="1">Abstract</text>
  <text top="280" left="180" width="540" height="15" font="2">We describe an improved multiplication method with clean asymptotic bounds.</text>
  <text top="340" left="180" width="180" height="16" font="1">1 Introduction</text>
  <text top="370" left="180" width="540" height="15" font="2">A Gaussian bound holds for all sufficiently large inputs in this regime.</text>
  <text top="420" left="180" width="220" height="16" font="1">A. Proof of Main Lemma</text>
  <text top="450" left="180" width="540" height="15" font="2">The proof follows directly by induction on the degree parameter.</text>
</page>
</pdf2xml>"##;

        let html =
            reflow_pdftohtml_xml(xml_single, Some("A Fast Algorithm for Integer Multiplication"))
                .unwrap();
        assert!(!html.contains("Ada Lovelace"), "frontmatter should be stripped: {html}");
        assert!(!html.contains("<h2>A Gaussian"), "body 'A ' sentence misclassified as h2: {html}");
        assert!(html.contains("<h2>A. Proof of Main Lemma</h2>"), "appendix heading lost: {html}");

        // 2. Two-column page with central gutter must read all left-column lines before right-column lines.
        let xml_twocol = r##"<?xml version="1.0" encoding="UTF-8"?>
<pdf2xml producer="poppler">
<page number="1" position="absolute" top="0" left="0" height="1188" width="918">
  <fontspec id="0" size="14" family="CMBX10" color="#000000"/>
  <fontspec id="1" size="14" family="CMR10" color="#000000"/>
  <text top="120" left="80" width="160" height="16" font="0">1 Introduction</text>
  <text top="160" left="80" width="330" height="15" font="1">LEFT_COL_ONE begins the first paragraph cleanly.</text>
  <text top="180" left="80" width="330" height="15" font="1">LEFT_COL_TWO continues in the left column.</text>
  <text top="200" left="80" width="330" height="15" font="1">LEFT_COL_THREE stays on the left side.</text>
  <text top="220" left="80" width="330" height="15" font="1">LEFT_COL_FOUR finishes the left column.</text>
  <text top="240" left="80" width="330" height="15" font="1">LEFT_COL_FIVE ensures column detection.</text>
  <text top="160" left="490" width="330" height="15" font="1">RIGHT_COL_ONE begins the right column.</text>
  <text top="180" left="490" width="330" height="15" font="1">RIGHT_COL_TWO continues in the right column.</text>
  <text top="200" left="490" width="330" height="15" font="1">RIGHT_COL_THREE stays on the right side.</text>
  <text top="220" left="490" width="330" height="15" font="1">RIGHT_COL_FOUR finishes the right column.</text>
  <text top="240" left="490" width="330" height="15" font="1">RIGHT_COL_FIVE completes the right column.</text>
</page>
</pdf2xml>"##;

        let html2 = reflow_pdftohtml_xml(xml_twocol, None).unwrap();
        let left_last = html2.find("LEFT_COL_FIVE").expect("missing LEFT_COL_FIVE");
        let right_first = html2.find("RIGHT_COL_ONE").expect("missing RIGHT_COL_ONE");
        assert!(
            left_last < right_first,
            "left column must precede right column in reflowed HTML: {html2}"
        );
    }
}
