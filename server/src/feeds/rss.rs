use anyhow::Result;
use chrono::{DateTime, Utc};
use url::Url;

use super::{extract_entry_timestamp, select_entry_link};
use crate::content::fetcher::SafeHttpClient;

#[derive(Debug, Clone)]
pub struct RssFeed {
    pub title: String,
    pub description: String,
    pub language: String,
}

#[derive(Debug, Clone)]
pub struct RssItem {
    pub title: String,
    pub link: Option<Url>,
    pub description: String,
    pub content_html: Option<String>,
    pub thumbnail_url: Option<String>,
    pub author: Option<String>,
    pub pub_date: Option<DateTime<Utc>>,
}

pub struct RssParser;

impl RssParser {
    pub fn parse(content: &str) -> Result<(RssFeed, Vec<RssItem>)> {
        let feed = feed_rs::parser::parse(content.as_bytes())?;

        let parsed_feed = RssFeed {
            title: feed.title.map(|t| t.content).unwrap_or_default(),
            description: feed.description.map(|d| d.content).unwrap_or_default(),
            language: feed.language.unwrap_or_default(),
        };

        let items = feed
            .entries
            .into_iter()
            .map(|entry| {
                let pub_date = extract_entry_timestamp(&entry);
                let thumbnail_url = Self::extract_entry_thumbnail_url(&entry);
                let title = entry.title.map(|t| t.content).unwrap_or_default();
                let link = select_entry_link(&entry.links)
                    .or_else(|| SafeHttpClient::parse_url(&entry.id).ok());
                let body = entry.content.and_then(|c| c.body);
                let summary = entry.summary.map(|s| s.content);
                let description = summary.clone().or_else(|| body.clone()).unwrap_or_default();
                let content_html = body.or(summary);

                let authors: Vec<String> =
                    entry.authors.into_iter().filter_map(|a| a.name).collect();
                let author = (!authors.is_empty()).then(|| authors.join(", "));

                RssItem { title, link, description, content_html, thumbnail_url, author, pub_date }
            })
            .collect();

        Ok((parsed_feed, items))
    }

    fn extract_entry_thumbnail_url(entry: &feed_rs::model::Entry) -> Option<String> {
        for media in &entry.media {
            for thumb in &media.thumbnails {
                if let Ok(url) = SafeHttpClient::parse_url(&thumb.image.uri) {
                    return Some(url.into());
                }
            }
            for content in &media.content {
                let is_image_mime = content
                    .content_type
                    .as_ref()
                    .is_some_and(|mime| mime.ty().as_str().eq_ignore_ascii_case("image"));
                if let Some(ref url) = content.url {
                    let has_img_ext = Self::has_image_extension(url.path());
                    if (is_image_mime || has_img_ext) && SafeHttpClient::validate_url(url).is_ok() {
                        return Some(url.as_str().to_owned());
                    }
                }
            }
        }
        for link in &entry.links {
            let is_enclosure =
                link.rel.as_deref().is_some_and(|r| r.eq_ignore_ascii_case("enclosure"));
            let is_image_mime = link
                .media_type
                .as_deref()
                .is_some_and(|mt| mt.trim().to_ascii_lowercase().starts_with("image/"));
            if is_enclosure
                && is_image_mime
                && let Ok(url) = SafeHttpClient::parse_url(&link.href)
            {
                return Some(url.into());
            }
        }
        None
    }

    fn has_image_extension(path: &str) -> bool {
        std::path::Path::new(path).extension().is_some_and(|ext| {
            matches!(
                ext.to_str().unwrap_or("").to_ascii_lowercase().as_str(),
                "jpg" | "jpeg" | "png" | "webp" | "gif" | "avif"
            )
        })
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_rss2() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8" ?>
        <rss version="2.0">
        <channel>
            <title>Sample Feed</title>
            <description>A test RSS feed</description>
            <link>https://example.com</link>
            <item>
                <title>Article 1</title>
                <link>https://example.com/1</link>
                <description>First article</description>
                <guid>guid-1</guid>
            </item>
        </channel>
        </rss>"#;

        let (feed, items) = RssParser::parse(xml).unwrap();
        assert_eq!(feed.title, "Sample Feed");
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "Article 1");
        assert_eq!(items[0].link.as_ref().map(Url::as_str), Some("https://example.com/1"));
    }

    #[test]
    fn test_parse_atom_prefers_alternate_link() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
            <title>Atom Feed</title>
            <link rel="self" href="https://example.com/atom.xml"/>
            <link rel="alternate" href="https://example.com"/>
            <entry>
                <title>Atom Article</title>
                <id>urn:uuid:1234</id>
                <link rel="edit" href="https://example.com/edit/atom1"/>
                <link rel="alternate" href="https://example.com/atom1"/>
                <summary>Atom summary</summary>
            </entry>
        </feed>"#;

        let (feed, items) = RssParser::parse(xml).unwrap();
        assert_eq!(feed.title, "Atom Feed");
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "Atom Article");
        assert_eq!(items[0].link.as_ref().map(Url::as_str), Some("https://example.com/atom1"));
    }
}
