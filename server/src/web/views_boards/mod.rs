mod board;
mod reader;

use axum::http::{HeaderMap, header};
use chrono::{DateTime, Datelike as _, Utc};
use roche_core::{classify::classify_url, media_urls::extract_arxiv_id};
use url::Url;

pub(super) use board::{
    board_detail_get, board_items_fragment_get, boards_get, post_board_create, post_board_delete,
    post_board_edit, post_board_item_dismiss, post_board_item_promote, post_board_item_undismiss,
    post_board_refresh, post_board_refresh_all, post_board_toggle_active,
};
pub(super) use reader::board_reader_get;

use crate::{
    db::{Board, BoardItemView},
    domain::ContentType,
    web::html::{content_type_badge_class, content_type_label},
};

pub(super) fn wants_json(headers: &HeaderMap) -> bool {
    if headers
        .get("x-roche-ajax")
        .and_then(|v| v.to_str().ok())
        .is_some_and(|v| v.trim() == "1" || v.trim().eq_ignore_ascii_case("true"))
    {
        return true;
    }
    headers
        .get_all(header::ACCEPT)
        .iter()
        .filter_map(|v| v.to_str().ok())
        .any(|raw| raw.to_ascii_lowercase().contains("application/json"))
}

#[must_use]
pub(super) fn board_section_label(source_key: &str) -> &'static str {
    match source_key.trim().to_ascii_lowercase().as_str() {
        "top" => "Front Page",
        "best" => "Best",
        "ask" => "Ask HN",
        "show" => "Show HN",
        "new" => "New",
        _ => "Board",
    }
}

#[must_use]
pub(super) fn board_source_badge(board: &Board) -> String {
    let adapter = board.adapter_type.display_label();
    let section = board_section_label(&board.source_key);
    let title_lower = board.title.trim().to_ascii_lowercase();
    let adapter_lower = adapter.to_ascii_lowercase();
    if title_lower.starts_with(&adapter_lower) {
        section.to_owned()
    } else if title_lower == section.to_ascii_lowercase() {
        adapter.to_owned()
    } else {
        format!("{adapter} · {section}")
    }
}

#[must_use]
pub(super) fn board_has_active_error(board: &Board) -> bool {
    board.is_active && board.last_error.as_deref().is_some_and(|s| !s.trim().is_empty())
}

#[must_use]
pub(super) fn clean_board_error(raw: &str) -> String {
    let mut txt = raw.trim();
    for prefix in
        ["Failed to refresh board: ", "Upstream feed error: ", "Upstream server returned "]
    {
        if let Some(stripped) = txt.strip_prefix(prefix) {
            txt = stripped;
        }
    }
    let mut chars = txt.chars();
    let short: String = chars.by_ref().take(130).collect();
    if chars.next().is_some() { format!("{short}…") } else { short }
}

#[must_use]
pub(super) fn format_relative_age(dt: DateTime<Utc>, now: DateTime<Utc>) -> String {
    let secs = now.signed_duration_since(dt).num_seconds();
    if secs < 60 {
        "just now".to_owned()
    } else if secs < 3_600 {
        format!("{}m ago", secs / 60)
    } else if secs < 86_400 {
        format!("{}h ago", secs / 3_600)
    } else if secs < 30 * 86_400 {
        format!("{}d ago", secs / 86_400)
    } else if dt.year() == now.year() {
        dt.format("%b %d").to_string()
    } else {
        dt.format("%b %d, %Y").to_string()
    }
}

#[must_use]
pub(super) fn format_comments_label(count: Option<i64>) -> String {
    match count {
        Some(1) => "1 comment".to_owned(),
        Some(n) if n >= 0 => format!("{n} comments"),
        _ => "discuss".to_owned(),
    }
}

#[must_use]
pub(super) fn board_item_is_self_post(item: &BoardItemView) -> bool {
    item.discussion_url.as_deref() == Some(item.url.as_str())
}

#[must_use]
pub(super) fn board_item_content_type(item: &BoardItemView) -> ContentType {
    if extract_arxiv_id(&item.url).is_some() {
        return ContentType::Paper;
    }
    Url::parse(&item.url).ok().map_or(ContentType::Article, |u| classify_url(&u))
}

#[must_use]
pub(super) fn board_item_kind_badge(
    item: &BoardItemView,
    board_source_key: Option<&str>,
) -> Option<(&'static str, &'static str)> {
    let lower_title = item.title.trim().to_ascii_lowercase();
    let on_ask_board = board_source_key.is_some_and(|k| k.trim().eq_ignore_ascii_case("ask"));
    let on_show_board = board_source_key.is_some_and(|k| k.trim().eq_ignore_ascii_case("show"));
    if lower_title.starts_with("ask hn:") && !on_ask_board {
        return Some(("", "Ask HN"));
    }
    if lower_title.starts_with("show hn:") && !on_show_board {
        return Some(("", "Show HN"));
    }
    let ct = board_item_content_type(item);
    if ct != ContentType::Article {
        return Some((content_type_badge_class(ct), content_type_label(ct)));
    }
    if board_item_is_self_post(item)
        && item.self_text_html.as_deref().is_some_and(|h| !h.trim().is_empty())
    {
        return Some(("", "Discussion"));
    }
    None
}

#[must_use]
pub(super) fn clean_plain_excerpt(raw: &str) -> String {
    const MAX_PLAIN_CHARS: usize = 220;
    let mut plain = String::with_capacity(raw.len().min(260));
    let mut plain_chars = 0usize;
    let mut truncated = false;
    let mut in_tag = false;
    let mut prev_space = true;
    for ch in raw.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                if !prev_space && plain_chars < MAX_PLAIN_CHARS {
                    plain.push(' ');
                    plain_chars += 1;
                    prev_space = true;
                }
            }
            c if !in_tag => {
                if c.is_whitespace() {
                    if !prev_space && plain_chars < MAX_PLAIN_CHARS {
                        plain.push(' ');
                        plain_chars += 1;
                        prev_space = true;
                    }
                } else {
                    if plain_chars >= MAX_PLAIN_CHARS {
                        truncated = true;
                        break;
                    }
                    plain.push(c);
                    plain_chars += 1;
                    prev_space = false;
                }
            }
            _ => {}
        }
    }
    let trimmed = plain.trim_end();
    if truncated && !trimmed.is_empty() { format!("{trimmed}…") } else { trimmed.to_owned() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_clean_plain_excerpt_checks_stripped_plain_length_not_raw_html() {
        let html_with_long_tags = format!(
            "<p><a href=\"https://example.com/{}\">Short readable excerpt.</a></p>",
            "a".repeat(260)
        );
        assert!(html_with_long_tags.chars().count() > 220);
        let excerpt = clean_plain_excerpt(&html_with_long_tags);
        assert_eq!(excerpt, "Short readable excerpt.");
        assert!(!excerpt.ends_with('…'));

        let long_plain = format!("<p>{}</p>", "x".repeat(240));
        let truncated = clean_plain_excerpt(&long_plain);
        assert!(truncated.ends_with('…'));
        assert_eq!(truncated.chars().count(), 221);
    }
}
