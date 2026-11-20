use std::collections::HashMap;

use anyhow::Result;
use chrono::{DateTime, Utc};
use quick_xml::{Reader, events::Event};
use url::Url;

use super::{MAX_PARSED_FEED_ENTRIES, extract_entry_timestamp};
use crate::{
    content::xml_util,
    util::{media_urls, url_guard::parse_public_url},
};

#[derive(Debug, Clone)]
pub struct YouTubeFeed {
    pub channel_title: String,
    pub items: Vec<YouTubeVideo>,
}

#[derive(Debug, Clone)]
pub struct YouTubeVideo {
    pub watch_url: Url,
    pub title: String,
    pub description: String,
    pub published_at: Option<DateTime<Utc>>,
}

pub struct YouTubeParser;

impl YouTubeParser {
    #[allow(dead_code)]
    #[must_use]
    pub fn is_youtube_host(host: Option<&str>) -> bool {
        media_urls::is_youtube_host(host)
    }

    #[allow(dead_code)]
    #[must_use]
    pub fn is_youtube_feed_url(url: &Url) -> bool {
        media_urls::is_youtube_feed_url(url)
    }

    #[allow(dead_code)]
    #[must_use]
    pub fn extract_video_id(url: &Url) -> Option<String> {
        media_urls::extract_youtube_video_id(url)
    }

    #[allow(dead_code)]
    #[must_use]
    pub fn canonical_watch_url(url: &Url) -> Option<Url> {
        media_urls::canonical_youtube_watch_url(url)
    }

    pub fn feed_url_for_channel_id(channel_id: &str) -> Result<Url> {
        media_urls::youtube_feed_url_for_channel_id(channel_id)
    }

    pub fn parse(content: &str) -> Result<YouTubeFeed> {
        let mut feed = feed_rs::parser::parse(content.as_bytes())?;
        feed.entries.truncate(MAX_PARSED_FEED_ENTRIES);

        let channel_title = feed.title.map_or_default(|t| t.content);
        let fallback_descriptions = Self::extract_all_media_descriptions(content);

        let items = feed
            .entries
            .into_iter()
            .filter_map(|entry| {
                let published_at = extract_entry_timestamp(&entry);
                let video_id =
                    entry.id.strip_prefix("yt:video:").unwrap_or(&entry.id).trim().to_string();
                if !media_urls::is_valid_youtube_video_id(&video_id) {
                    return None;
                }

                let watch_url =
                    parse_public_url(&format!("https://www.youtube.com/watch?v={video_id}"))
                        .ok()?;
                let title = entry.title.map_or_default(|t| t.content);

                let mut description = entry
                    .media
                    .first()
                    .and_then(|m| m.description.as_ref().map(|d| d.content.clone()))
                    .or_else(|| entry.summary.map(|s| s.content))
                    .unwrap_or_default();

                if description.is_empty()
                    && let Some(desc) = fallback_descriptions.get(&video_id)
                {
                    description.clone_from(desc);
                }

                Some(YouTubeVideo { watch_url, title, description, published_at })
            })
            .collect();

        Ok(YouTubeFeed { channel_title, items })
    }

    #[must_use]
    pub fn extract_channel_id(input: &str) -> Option<String> {
        media_urls::extract_youtube_channel_id(input)
    }

    fn extract_all_media_descriptions(xml: &str) -> HashMap<String, String> {
        let mut reader = Reader::from_str(xml);
        let mut map = HashMap::new();
        let mut current_video_id = None;
        let mut current_desc = None;
        let mut seen_entries = 0usize;

        loop {
            if seen_entries >= MAX_PARSED_FEED_ENTRIES {
                break;
            }
            match reader.read_event() {
                Ok(Event::Start(tag)) => match tag.name().as_ref() {
                    "entry" => {
                        current_video_id = None;
                        current_desc = None;
                    }
                    "yt:videoId" => {
                        if let Some(vid) =
                            xml_util::read_element_text(&mut reader, tag.name().as_ref())
                        {
                            current_video_id = Some(vid.trim().to_owned());
                        }
                    }
                    "id" if current_video_id.is_none() => {
                        if let Some(raw_id) =
                            xml_util::read_element_text(&mut reader, tag.name().as_ref())
                            && let Some(vid) = raw_id.trim().strip_prefix("yt:video:")
                        {
                            current_video_id = Some(vid.trim().to_string());
                        }
                    }
                    "media:description" => {
                        if let Some(desc) =
                            xml_util::read_element_text(&mut reader, tag.name().as_ref())
                        {
                            current_desc = Some(desc.trim().to_owned());
                        }
                    }
                    _ => {}
                },
                Ok(Event::End(tag)) if tag.name().as_ref() == "entry" => {
                    seen_entries += 1;
                    if let (Some(vid), Some(desc)) = (current_video_id.take(), current_desc.take())
                        && media_urls::is_valid_youtube_video_id(&vid)
                    {
                        map.insert(vid, desc);
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }
        map
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_is_youtube_feed_url_rejects_spoofed_hosts() {
        let valid = Url::parse(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA",
        )
        .unwrap();
        assert!(YouTubeParser::is_youtube_feed_url(&valid));

        for spoofed in [
            "https://evil.example/page?q=youtube.com/feeds/videos.xml",
            "https://evil.example/youtube.com/feeds/videos.xml",
            "https://notyoutube.com/feeds/videos.xml",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        ] {
            let parsed = Url::parse(spoofed).unwrap();
            assert!(!YouTubeParser::is_youtube_feed_url(&parsed), "expected false for {spoofed}");
        }
    }

    #[test]
    fn test_extract_channel_id() {
        assert_eq!(
            YouTubeParser::extract_channel_id("UCX6OQ3DkcsbYNE6H8uQQuVA"),
            Some("UCX6OQ3DkcsbYNE6H8uQQuVA".to_string())
        );
        assert_eq!(
            YouTubeParser::extract_channel_id(
                "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA"
            ),
            Some("UCX6OQ3DkcsbYNE6H8uQQuVA".to_string())
        );
        assert_eq!(
            YouTubeParser::extract_channel_id(
                "https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA/videos"
            ),
            Some("UCX6OQ3DkcsbYNE6H8uQQuVA".to_string())
        );
        assert_eq!(
            YouTubeParser::extract_channel_id("https://evil.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA"),
            None
        );
        assert_eq!(
            YouTubeParser::extract_channel_id("https://www.youtube.com/channel/not-a-valid-id"),
            None
        );
    }

    #[test]
    fn test_parse_youtube_atom_feed_multi_entry_descriptions() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
              xmlns:media="http://search.yahoo.com/mrss/"
              xmlns="http://www.w3.org/2005/Atom">
         <link rel="self" href="http://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA"/>
         <id>yt:channel:UCX6OQ3DkcsbYNE6H8uQQuVA</id>
         <title>Test Channel</title>
         <entry>
          <id>yt:video:dQw4w9WgXcQ</id>
          <yt:videoId>dQw4w9WgXcQ</yt:videoId>
          <title>Test Video</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=dQw4w9WgXcQ"/>
          <published>2026-10-14T08:00:00+00:00</published>
          <media:group>
           <media:title>Test Video</media:title>
           <media:description>A description mentioning secondVidId</media:description>
           <media:thumbnail url="https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg" width="480" height="360"/>
          </media:group>
         </entry>
         <entry>
          <id>yt:video:secondVidId</id>
          <yt:videoId>secondVidId</yt:videoId>
          <title>Second Video Without Description</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=secondVidId"/>
         </entry>
        </feed>"#;

        let feed = YouTubeParser::parse(xml).unwrap();
        assert_eq!(feed.channel_title, "Test Channel");
        assert_eq!(feed.items.len(), 2);
        assert_eq!(feed.items[0].watch_url.as_str(), "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assert_eq!(feed.items[0].title, "Test Video");
        assert_eq!(feed.items[0].description, "A description mentioning secondVidId");
        assert_eq!(
            feed.items[0].published_at.map(|dt| dt.to_rfc3339()),
            Some("2026-10-14T08:00:00+00:00".to_string())
        );
        assert_eq!(feed.items[1].watch_url.as_str(), "https://www.youtube.com/watch?v=secondVidId");
        assert_eq!(feed.items[1].description, "");
        assert_eq!(feed.items[1].published_at, None);
    }

    #[test]
    fn test_extract_media_descriptions_tolerates_unknown_html_entities() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
              xmlns:media="http://search.yahoo.com/mrss/"
              xmlns="http://www.w3.org/2005/Atom">
         <entry>
          <yt:videoId>vidWithEnt1</yt:videoId>
          <media:description>Hello&nbsp;world &mdash; Rust &amp; Friends</media:description>
         </entry>
         <entry>
          <yt:videoId>vidAfterEnt</yt:videoId>
          <media:description>Second entry still parsed</media:description>
         </entry>
        </feed>"#;

        let descriptions = YouTubeParser::extract_all_media_descriptions(xml);
        assert_eq!(
            descriptions.get("vidWithEnt1").map(String::as_str),
            Some("Hello&nbsp;world &mdash; Rust & Friends")
        );
        assert_eq!(
            descriptions.get("vidAfterEnt").map(String::as_str),
            Some("Second entry still parsed")
        );
    }
}
