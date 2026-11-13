use std::collections::HashMap;

use anyhow::Result;
use chrono::{DateTime, Utc};
use quick_xml::{Reader, escape::unescape, events::Event};
use url::Url;

use super::extract_entry_timestamp;
use crate::content::fetcher::SafeHttpClient;

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
    #[must_use]
    pub fn is_youtube_host(host: Option<&str>) -> bool {
        matches!(host, Some("youtube.com" | "www.youtube.com" | "m.youtube.com"))
    }

    #[must_use]
    pub fn extract_video_id(url: &Url) -> Option<String> {
        let host = url.host_str()?;
        if Self::is_youtube_host(Some(host)) {
            if url.path() == "/watch" {
                for (key, value) in url.query_pairs() {
                    if key == "v" && Self::is_valid_video_id(&value) {
                        return Some(value.into_owned());
                    }
                }
            } else if let Some(mut segments) = url.path_segments()
                && let Some(prefix) = segments.next()
                && matches!(prefix, "shorts" | "embed" | "v")
                && let Some(candidate) = segments.next()
                && Self::is_valid_video_id(candidate)
            {
                return Some(candidate.to_string());
            }
        } else if host.eq_ignore_ascii_case("youtu.be")
            && let Some(mut segments) = url.path_segments()
            && let Some(candidate) = segments.next()
            && Self::is_valid_video_id(candidate)
        {
            return Some(candidate.to_string());
        }
        None
    }

    #[must_use]
    pub fn canonical_watch_url(url: &Url) -> Option<Url> {
        let video_id = Self::extract_video_id(url)?;
        Url::parse(&format!("https://www.youtube.com/watch?v={video_id}")).ok()
    }

    pub fn feed_url_for_channel_id(channel_id: &str) -> Result<Url> {
        SafeHttpClient::parse_url(&format!(
            "https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
        ))
    }

    pub fn parse(content: &str) -> Result<YouTubeFeed> {
        let feed = feed_rs::parser::parse(content.as_bytes())?;

        let channel_title = feed.title.map_or_default(|t| t.content);
        let fallback_descriptions = Self::extract_all_media_descriptions(content);

        let items = feed
            .entries
            .into_iter()
            .filter_map(|entry| {
                let published_at = extract_entry_timestamp(&entry);
                let video_id =
                    entry.id.strip_prefix("yt:video:").unwrap_or(&entry.id).trim().to_string();
                if !Self::is_valid_video_id(&video_id) {
                    return None;
                }

                let watch_url = SafeHttpClient::parse_url(&format!(
                    "https://www.youtube.com/watch?v={video_id}"
                ))
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
        let trimmed = input.trim();

        // Direct channel ID: starts with UC, 24 chars, valid YouTube ID alphabet
        if Self::is_valid_channel_id(trimmed) {
            return Some(trimmed.to_string());
        }

        let parsed = Url::parse(trimmed).ok()?;
        if !Self::is_youtube_host(parsed.host_str()) {
            return None;
        }

        // URL with channel_id= query parameter
        for (key, value) in parsed.query_pairs() {
            if key == "channel_id" && Self::is_valid_channel_id(&value) {
                return Some(value.into_owned());
            }
        }

        // /channel/UC... path
        let mut segments = parsed.path_segments()?;
        while let Some(seg) = segments.next() {
            if seg == "channel"
                && let Some(candidate) = segments.next()
                && Self::is_valid_channel_id(candidate)
            {
                return Some(candidate.to_string());
            }
        }

        None
    }

    fn is_valid_channel_id(candidate: &str) -> bool {
        candidate.len() == 24
            && candidate.starts_with("UC")
            && candidate.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    }

    fn is_valid_video_id(candidate: &str) -> bool {
        !candidate.is_empty()
            && candidate.len() <= 64
            && candidate.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    }

    fn extract_all_media_descriptions(xml: &str) -> HashMap<String, String> {
        let mut reader = Reader::from_str(xml);
        reader.config_mut().trim_text(true);
        let mut map = HashMap::new();
        let mut current_video_id = None;
        let mut current_desc = None;

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) => match tag.name().as_ref() {
                    "entry" => {
                        current_video_id = None;
                        current_desc = None;
                    }
                    "yt:videoId" => {
                        if let Some(vid) = Self::read_element_text(&mut reader, tag.name().as_ref())
                        {
                            current_video_id = Some(vid);
                        }
                    }
                    "id" if current_video_id.is_none() => {
                        if let Some(raw_id) =
                            Self::read_element_text(&mut reader, tag.name().as_ref())
                            && let Some(vid) = raw_id.strip_prefix("yt:video:")
                        {
                            current_video_id = Some(vid.trim().to_string());
                        }
                    }
                    "media:description" => {
                        if let Some(desc) =
                            Self::read_element_text(&mut reader, tag.name().as_ref())
                        {
                            current_desc = Some(desc);
                        }
                    }
                    _ => {}
                },
                Ok(Event::End(tag)) if tag.name().as_ref() == "entry" => {
                    if let (Some(vid), Some(desc)) = (current_video_id.take(), current_desc.take())
                        && Self::is_valid_video_id(&vid)
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

    fn read_element_text(reader: &mut Reader<&[u8]>, end_tag: &str) -> Option<String> {
        let mut out = String::new();
        loop {
            match reader.read_event() {
                Ok(Event::Text(text)) => {
                    let unescaped = unescape(text.as_ref()).ok()?;
                    out.push_str(&unescaped);
                }
                Ok(Event::GeneralRef(entity)) => {
                    let raw = format!("&{};", entity.as_ref());
                    let unescaped = unescape(&raw).ok()?;
                    out.push_str(&unescaped);
                }
                Ok(Event::CData(cdata)) => {
                    out.push_str(cdata.as_ref());
                }
                Ok(Event::End(tag)) if tag.name().as_ref() == end_tag => break,
                Ok(Event::Eof) | Err(_) => return None,
                _ => {}
            }
        }
        Some(out)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

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
}
