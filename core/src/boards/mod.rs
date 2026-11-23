pub mod hacker_news;

use std::{collections::HashMap, str::FromStr};

use anyhow::Result;
use serde::{Deserialize, Serialize};

pub use self::hacker_news::{
    HackerNewsSection, ParsedHnItem, extract_domain, extract_hn_item_id, hn_discussion_url,
    hn_item_api_url, is_valid_hn_item_id, parse_hn_item_json, parse_hn_story_ids,
};
use crate::{identity::canonicalize_sync_url, url_guard::parse_public_url};

pub const BOARD_SNAPSHOT_BUFFER_LIMIT: usize = 90;
pub const BOARD_DEFAULT_PAGE_SIZE: usize = 30;
pub const BOARD_STATE_GRACE_HOURS: i64 = 48;

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "snake_case")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "snake_case"))]
pub enum BoardType {
    #[default]
    HackerNews,
}

impl BoardType {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::HackerNews => "hacker_news",
        }
    }

    #[must_use]
    pub const fn display_label(self) -> &'static str {
        match self {
            Self::HackerNews => "Hacker News",
        }
    }

    pub fn parse_flexible(raw: &str) -> Result<Self> {
        let norm = raw.trim().to_ascii_lowercase().replace('-', "_");
        match norm.as_str() {
            "hacker_news" | "hackernews" | "hn" => Ok(Self::HackerNews),
            _ => anyhow::bail!("Unsupported board adapter_type '{raw}'"),
        }
    }
}

impl FromStr for BoardType {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self> {
        Self::parse_flexible(s)
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "sqlx", derive(sqlx::Type))]
#[serde(rename_all = "lowercase")]
#[cfg_attr(feature = "sqlx", sqlx(type_name = "TEXT", rename_all = "lowercase"))]
pub enum BoardOrderMode {
    #[default]
    Ranked,
    Chronological,
}

impl BoardOrderMode {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Ranked => "ranked",
            Self::Chronological => "chronological",
        }
    }
}

impl FromStr for BoardOrderMode {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "ranked" | "rank" => Ok(Self::Ranked),
            "chronological" | "chrono" | "newest" => Ok(Self::Chronological),
            _ => anyhow::bail!("Unknown board order_mode '{s}'"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HackerNewsConfig {
    #[serde(default)]
    pub section: HackerNewsSection,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BoardSourceConfig {
    HackerNews(HackerNewsConfig),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BoardSourceSpec {
    pub adapter_type: BoardType,
    pub source_key: String,
    pub source_url: String,
    pub source_config_json: String,
    pub default_title: String,
    pub default_order_mode: BoardOrderMode,
}

impl BoardSourceSpec {
    #[must_use]
    pub fn for_hacker_news(section: HackerNewsSection) -> Self {
        Self {
            adapter_type: BoardType::HackerNews,
            source_key: section.source_key().to_owned(),
            source_url: section.source_url().to_owned(),
            source_config_json: section.canonical_config_json(),
            default_title: section.default_title().to_owned(),
            default_order_mode: section.default_order_mode(),
        }
    }

    pub fn from_url(raw_url: &str) -> Result<Self> {
        let trimmed = raw_url.trim();
        let _ = parse_public_url(trimmed)?;
        if let Some(section) = HackerNewsSection::detect_from_url(trimmed) {
            return Ok(Self::for_hacker_news(section));
        }
        anyhow::bail!(
            "Unsupported board URL '{trimmed}': currently Hacker News board URLs (news.ycombinator.com) are supported"
        )
    }

    pub fn resolve(
        adapter_type: Option<BoardType>,
        url: Option<&str>,
        section: Option<&str>,
        source_config: Option<&serde_json::Value>,
    ) -> Result<Self> {
        if let Some(raw_url) = url.map(str::trim).filter(|s| !s.is_empty()) {
            let detected = Self::from_url(raw_url)?;
            if let Some(explicit_adapter) = adapter_type
                && explicit_adapter != detected.adapter_type
            {
                anyhow::bail!(
                    "Board URL resolves to '{}', which does not match requested adapter '{}'",
                    detected.adapter_type.as_str(),
                    explicit_adapter.as_str()
                );
            }
            return Ok(detected);
        }

        let resolved_adapter = adapter_type.unwrap_or(BoardType::HackerNews);
        match resolved_adapter {
            BoardType::HackerNews => {
                let raw_section = section.map(str::trim).filter(|s| !s.is_empty()).or_else(|| {
                    source_config
                        .and_then(|v| v.get("section"))
                        .and_then(serde_json::Value::as_str)
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                });
                let parsed_section = match raw_section {
                    Some(raw) => HackerNewsSection::parse_flexible(raw)?,
                    None => HackerNewsSection::Top,
                };
                Ok(Self::for_hacker_news(parsed_section))
            }
        }
    }

    /// Validates and canonicalizes stored/synced board source metadata.
    pub fn validate_config(
        adapter_type: BoardType,
        source_key: &str,
        source_url: &str,
        source_config_json: &str,
    ) -> Result<Self> {
        let _ = parse_public_url(source_url)?;
        match adapter_type {
            BoardType::HackerNews => {
                let section = Self::parse_hn_section(adapter_type, source_key, source_config_json)?;
                Ok(Self::for_hacker_news(section))
            }
        }
    }

    pub fn parse_hn_section(
        adapter_type: BoardType,
        source_key: &str,
        source_config_json: &str,
    ) -> Result<HackerNewsSection> {
        match adapter_type {
            BoardType::HackerNews => {
                if !source_config_json.trim().is_empty()
                    && let Ok(val) = serde_json::from_str::<serde_json::Value>(source_config_json)
                    && let Some(sec_str) = val
                        .get("section")
                        .and_then(serde_json::Value::as_str)
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                {
                    return HackerNewsSection::parse_flexible(sec_str);
                }
                if !source_key.trim().is_empty() {
                    return HackerNewsSection::parse_flexible(source_key);
                }
                Ok(HackerNewsSection::Top)
            }
        }
    }

    pub fn parse_config(&self) -> Result<BoardSourceConfig> {
        match self.adapter_type {
            BoardType::HackerNews => {
                let section = Self::parse_hn_section(
                    self.adapter_type,
                    &self.source_key,
                    &self.source_config_json,
                )?;
                Ok(BoardSourceConfig::HackerNews(HackerNewsConfig { section }))
            }
        }
    }
}

/// External discussion thread link persisted inside `entries.metadata_json.discussions`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DiscussionLink {
    pub source: String,
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub external_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
}

impl DiscussionLink {
    #[must_use]
    pub fn hacker_news(raw_id: &str) -> Option<Self> {
        let id = extract_hn_item_id(raw_id)?;
        Some(Self {
            source: BoardType::HackerNews.as_str().to_owned(),
            url: format!("https://news.ycombinator.com/item?id={id}"),
            external_id: Some(id),
            label: Some(BoardType::HackerNews.display_label().to_owned()),
        })
    }

    fn normalized(&self) -> Option<Self> {
        let clean_url = parse_public_url(&self.url).ok()?.to_string();
        let source = self.source.trim().to_ascii_lowercase();
        let external_id =
            self.external_id.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        let label =
            self.label.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_owned);
        Some(Self { source, url: clean_url, external_id, label })
    }
}

/// Extracts sanitized, deduplicated `discussions` links from an entry's `metadata_json`.
#[must_use]
pub fn extract_discussions_from_metadata_json(metadata_json: &str) -> Vec<DiscussionLink> {
    let Ok(serde_json::Value::Object(map)) =
        serde_json::from_str::<serde_json::Value>(metadata_json)
    else {
        return Vec::new();
    };
    let Some(serde_json::Value::Array(arr)) = map.get("discussions") else {
        return Vec::new();
    };
    let parsed: Vec<DiscussionLink> = arr
        .iter()
        .filter_map(|v| serde_json::from_value::<DiscussionLink>(v.clone()).ok())
        .collect();
    merge_discussion_lists(&[], &parsed)
}

/// Extracts `discussions` from `metadata_json` and unions in any `hacker_news` entry identifiers.
#[must_use]
pub fn extract_discussions_with_hn_ids(
    metadata_json: &str,
    hn_ids: &[String],
) -> Vec<DiscussionLink> {
    let base = extract_discussions_from_metadata_json(metadata_json);
    if hn_ids.is_empty() {
        return base;
    }
    let extra: Vec<DiscussionLink> =
        hn_ids.iter().filter_map(|raw| DiscussionLink::hacker_news(raw)).collect();
    merge_discussion_lists(&base, &extra)
}

/// Performs a deduplicated set-union of discussion links keyed by canonicalized `url`, preserving
/// `existing` order first and enriching blank fields (`source`, `external_id`, `label`) from
/// `incoming`.
#[must_use]
pub fn merge_discussion_lists(
    existing: &[DiscussionLink],
    incoming: &[DiscussionLink],
) -> Vec<DiscussionLink> {
    let mut out: Vec<DiscussionLink> = Vec::with_capacity(existing.len() + incoming.len());
    let mut idx_by_url: HashMap<String, usize> = HashMap::new();

    for raw in existing.iter().chain(incoming.iter()) {
        let Some(clean) = raw.normalized() else {
            continue;
        };
        let key = canonicalize_sync_url(&clean.url);
        if key.is_empty() {
            continue;
        }
        if let Some(&pos) = idx_by_url.get(&key) {
            let slot = &mut out[pos];
            if slot.source.trim().is_empty() && !clean.source.is_empty() {
                slot.source = clean.source;
            }
            if slot.external_id.as_deref().is_none_or(|s| s.trim().is_empty())
                && clean.external_id.is_some()
            {
                slot.external_id = clean.external_id;
            }
            if slot.label.as_deref().is_none_or(|s| s.trim().is_empty()) && clean.label.is_some() {
                slot.label = clean.label;
            }
        } else {
            idx_by_url.insert(key, out.len());
            out.push(clean);
        }
    }
    out
}

/// Unions `incoming` discussion links into `metadata_json["discussions"]`, preserving all other
/// metadata keys.
#[must_use]
pub fn merge_discussions_into_metadata_json(
    metadata_json: &str,
    incoming: &[DiscussionLink],
) -> String {
    let existing = extract_discussions_from_metadata_json(metadata_json);
    let merged = merge_discussion_lists(&existing, incoming);
    if merged.is_empty() {
        return metadata_json.to_owned();
    }
    let mut obj = serde_json::from_str::<serde_json::Value>(metadata_json)
        .ok()
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    if let Ok(val) = serde_json::to_value(&merged) {
        obj.insert("discussions".to_owned(), val);
    }
    serde_json::Value::Object(obj).to_string()
}

/// CRDT union-merge of raw JSON `"discussions"` arrays by canonicalized `url`, preserving existing
/// order and enriching blank `source`/`external_id`/`label` fields from incoming items.
#[must_use]
pub fn merge_discussions_json_arrays(
    existing: &[serde_json::Value],
    incoming: &[serde_json::Value],
) -> Vec<serde_json::Value> {
    let mut out: Vec<serde_json::Value> = Vec::with_capacity(existing.len() + incoming.len());
    let mut idx_by_url: HashMap<String, usize> = HashMap::new();

    for val in existing.iter().chain(incoming.iter()) {
        let Some(obj) = val.as_object() else {
            continue;
        };
        let Some(raw_url) = obj.get("url").and_then(serde_json::Value::as_str) else {
            continue;
        };
        let Ok(parsed_url) = parse_public_url(raw_url) else {
            continue;
        };
        let clean_url = parsed_url.to_string();
        let key = canonicalize_sync_url(&clean_url);
        if key.is_empty() {
            continue;
        }
        if let Some(&pos) = idx_by_url.get(&key) {
            if let Some(target) = out[pos].as_object_mut() {
                for field in ["source", "external_id", "label"] {
                    let target_blank = target
                        .get(field)
                        .and_then(serde_json::Value::as_str)
                        .is_none_or(|s| s.trim().is_empty());
                    if target_blank
                        && let Some(inc_str) = obj
                            .get(field)
                            .and_then(serde_json::Value::as_str)
                            .map(str::trim)
                            .filter(|s| !s.is_empty())
                    {
                        target.insert(
                            field.to_owned(),
                            serde_json::Value::String(inc_str.to_owned()),
                        );
                    }
                }
            }
        } else {
            let mut clean_obj = obj.clone();
            clean_obj.insert("url".to_owned(), serde_json::Value::String(clean_url));
            idx_by_url.insert(key, out.len());
            out.push(serde_json::Value::Object(clean_obj));
        }
    }
    out
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_board_source_spec_resolution_and_validation() {
        let by_url =
            BoardSourceSpec::resolve(None, Some("https://news.ycombinator.com/ask"), None, None)
                .unwrap();
        assert_eq!(by_url.adapter_type, BoardType::HackerNews);
        assert_eq!(by_url.source_key, "ask");
        assert_eq!(by_url.source_url, "https://news.ycombinator.com/ask");
        assert_eq!(by_url.default_title, "Ask HN");
        assert_eq!(by_url.source_config_json, r#"{"section":"ask"}"#);

        let by_section =
            BoardSourceSpec::resolve(Some(BoardType::HackerNews), None, Some("show"), None)
                .unwrap();
        assert_eq!(by_section.source_key, "show");
        assert_eq!(by_section.default_title, "Show HN");

        let by_config = BoardSourceSpec::resolve(
            Some(BoardType::HackerNews),
            None,
            None,
            Some(&serde_json::json!({"section": "best"})),
        )
        .unwrap();
        assert_eq!(by_config.source_key, "best");

        assert!(BoardSourceSpec::from_url("http://127.0.0.1/news").is_err());
        assert!(BoardSourceSpec::from_url("https://example.com/news").is_err());
    }

    #[test]
    fn test_discussion_merge_dedups_and_enriches_fields() {
        let a = vec![DiscussionLink {
            source: "hacker_news".to_owned(),
            url: "https://news.ycombinator.com/item?id=100".to_owned(),
            external_id: None,
            label: None,
        }];
        let b = vec![
            DiscussionLink {
                source: "hacker_news".to_owned(),
                url: "HTTPS://NEWS.YCOMBINATOR.COM:443/item?id=100".to_owned(),
                external_id: Some("100".to_owned()),
                label: Some("Hacker News".to_owned()),
            },
            DiscussionLink {
                source: "lobsters".to_owned(),
                url: "https://lobste.rs/s/abc123/example".to_owned(),
                external_id: Some("abc123".to_owned()),
                label: Some("Lobsters".to_owned()),
            },
        ];

        let merged = merge_discussion_lists(&a, &b);
        assert_eq!(merged.len(), 2);
        assert_eq!(merged[0].external_id.as_deref(), Some("100"));
        assert_eq!(merged[0].label.as_deref(), Some("Hacker News"));
        assert_eq!(merged[1].source, "lobsters");

        let meta = merge_discussions_into_metadata_json(r#"{"want_notes":"read soon"}"#, &merged);
        let parsed: serde_json::Value = serde_json::from_str(&meta).unwrap();
        assert_eq!(parsed["want_notes"], "read soon");
        assert_eq!(parsed["discussions"].as_array().unwrap().len(), 2);
    }
}
