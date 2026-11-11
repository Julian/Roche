use anyhow::Result;
use chrono::{DateTime, Utc};
use quick_xml::{Reader, XmlVersion, escape::unescape, events::Event};
use url::Url;

use super::{extract_entry_timestamp, select_entry_link};
use crate::content::fetcher::{HtmlSanitizer, SafeHttpClient, is_direct_audio_url};

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
    pub audio_url: Option<Url>,
    pub duration: Option<i64>,
    pub author: Option<String>,
    pub pub_date: Option<DateTime<Utc>>,
}

#[derive(Debug, Default, Clone)]
struct SupplementalEntryMeta {
    enclosure_url: Option<Url>,
    duration_secs: Option<i64>,
    itunes_image_url: Option<String>,
    itunes_author: Option<String>,
}

#[derive(Debug, Default)]
struct SupplementalFeedMeta {
    feed_image_url: Option<String>,
    feed_author: Option<String>,
    entries: Vec<SupplementalEntryMeta>,
}

pub struct RssParser;

impl RssParser {
    pub fn parse(content: &str) -> Result<(RssFeed, Vec<RssItem>)> {
        let feed = feed_rs::parser::parse(content.as_bytes())?;
        let supplemental = Self::extract_supplemental_xml_metadata(content);

        let feed_base_url = select_entry_link(&feed.links);
        let feed_fallback_thumb = feed
            .logo
            .as_ref()
            .and_then(|img| {
                HtmlSanitizer::resolve_and_validate_thumbnail_url(&img.uri, feed_base_url.as_ref())
            })
            .or_else(|| {
                feed.icon.as_ref().and_then(|img| {
                    HtmlSanitizer::resolve_and_validate_thumbnail_url(
                        &img.uri,
                        feed_base_url.as_ref(),
                    )
                })
            })
            .or_else(|| {
                supplemental.feed_image_url.as_deref().and_then(|raw| {
                    HtmlSanitizer::resolve_and_validate_thumbnail_url(raw, feed_base_url.as_ref())
                })
            });

        let parsed_feed = RssFeed {
            title: feed.title.map(|t| t.content).unwrap_or_default(),
            description: feed.description.map(|d| d.content).unwrap_or_default(),
            language: feed.language.unwrap_or_default(),
        };

        let items = feed
            .entries
            .into_iter()
            .enumerate()
            .map(|(idx, entry)| {
                let supp = supplemental.entries.get(idx);
                let pub_date = extract_entry_timestamp(&entry);
                let (media_audio_url, media_duration) = Self::extract_entry_audio(&entry);
                let audio_url =
                    media_audio_url.or_else(|| supp.and_then(|s| s.enclosure_url.clone()));
                let duration = media_duration.or_else(|| supp.and_then(|s| s.duration_secs));
                let link = select_entry_link(&entry.links)
                    .or_else(|| SafeHttpClient::parse_url(&entry.id).ok())
                    .or_else(|| audio_url.clone());
                let base_for_thumb = link.as_ref().or(feed_base_url.as_ref());
                let thumbnail_url = Self::extract_entry_thumbnail_url(&entry, base_for_thumb)
                    .or_else(|| {
                        supp.and_then(|s| s.itunes_image_url.as_deref()).and_then(|raw| {
                            HtmlSanitizer::resolve_and_validate_thumbnail_url(raw, base_for_thumb)
                        })
                    })
                    .or_else(|| audio_url.is_some().then(|| feed_fallback_thumb.clone()).flatten());
                let title = entry.title.map(|t| t.content).unwrap_or_default();
                let body = entry.content.and_then(|c| c.body);
                let summary = entry.summary.map(|s| s.content);
                let description = summary.clone().or_else(|| body.clone()).unwrap_or_default();
                let content_html = body.or(summary);

                let authors: Vec<String> = entry
                    .authors
                    .into_iter()
                    .filter_map(|a| {
                        let trimmed = a.name.as_deref()?.trim();
                        (!trimmed.is_empty()).then(|| trimmed.to_owned())
                    })
                    .collect();
                let author = (!authors.is_empty())
                    .then(|| authors.join(", "))
                    .or_else(|| supp.and_then(|s| s.itunes_author.clone()))
                    .or_else(|| {
                        audio_url.is_some().then(|| supplemental.feed_author.clone()).flatten()
                    });

                RssItem {
                    title,
                    link,
                    description,
                    content_html,
                    thumbnail_url,
                    audio_url,
                    duration,
                    author,
                    pub_date,
                }
            })
            .collect();

        Ok((parsed_feed, items))
    }

    fn extract_entry_audio(entry: &feed_rs::model::Entry) -> (Option<Url>, Option<i64>) {
        let mut found_url: Option<Url> = None;
        let mut found_duration: Option<i64> = None;

        for media in &entry.media {
            let media_level_secs = media
                .duration
                .map(|dur| i64::try_from(dur.as_secs()).unwrap_or(0))
                .filter(|&s| s > 0);
            for content in &media.content {
                let is_audio_mime = content
                    .content_type
                    .as_ref()
                    .is_some_and(|mime| mime.ty().as_str().eq_ignore_ascii_case("audio"));
                if found_url.is_none()
                    && let Some(ref url) = content.url
                    && (is_audio_mime || is_direct_audio_url(url))
                    && SafeHttpClient::validate_url(url).is_ok()
                {
                    found_url = Some(url.clone());
                    found_duration = content
                        .duration
                        .map(|dur| i64::try_from(dur.as_secs()).unwrap_or(0))
                        .filter(|&s| s > 0)
                        .or(media_level_secs);
                }
            }
        }

        if found_url.is_none() {
            for link in &entry.links {
                let is_enclosure =
                    link.rel.as_deref().is_some_and(|r| r.eq_ignore_ascii_case("enclosure"));
                let is_audio_mime = link
                    .media_type
                    .as_deref()
                    .is_some_and(|mt| mt.trim().to_ascii_lowercase().starts_with("audio/"));
                if is_enclosure
                    && let Ok(parsed) = SafeHttpClient::parse_url(&link.href)
                    && (is_audio_mime || is_direct_audio_url(&parsed))
                {
                    found_url = Some(parsed);
                    break;
                }
            }
        }

        (found_url, found_duration)
    }

    #[must_use]
    pub fn parse_duration_seconds(raw: &str) -> Option<i64> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return None;
        }
        let parts: Vec<&str> = trimmed.split(':').collect();
        let parse_part = |s: &str| -> Option<i64> {
            let clean = s.trim().split('.').next().unwrap_or("");
            clean.parse::<i64>().ok().filter(|&v| v >= 0)
        };
        let total = match parts.as_slice() {
            [secs] => parse_part(secs)?,
            [mins, secs] => parse_part(mins)?.checked_mul(60)?.checked_add(parse_part(secs)?)?,
            [hours, mins, secs] => parse_part(hours)?
                .checked_mul(3600)?
                .checked_add(parse_part(mins)?.checked_mul(60)?)?
                .checked_add(parse_part(secs)?)?,
            _ => return None,
        };
        (total > 0).then_some(total)
    }

    #[allow(clippy::too_many_lines)]
    fn extract_supplemental_xml_metadata(content: &str) -> SupplementalFeedMeta {
        let mut reader = Reader::from_str(content);
        reader.config_mut().check_end_names = false;

        let mut meta = SupplementalFeedMeta::default();
        let mut current_entry: Option<SupplementalEntryMeta> = None;
        let mut in_channel_image = false;
        let mut active_text_tag: Option<&'static str> = None;
        let mut text_buf = String::new();

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) => {
                    let qname = tag.name();
                    let raw_name = qname.as_ref();
                    let local = Self::local_tag_name(raw_name);
                    if Self::is_entry_tag(raw_name) {
                        if let Some(unfinished) = current_entry.take() {
                            meta.entries.push(unfinished);
                        }
                        current_entry = Some(SupplementalEntryMeta::default());
                        active_text_tag = None;
                    } else if local.eq_ignore_ascii_case("enclosure")
                        || (local.eq_ignore_ascii_case("link") && Self::is_enclosure_link(&tag))
                    {
                        if let Some(ref mut entry) = current_entry
                            && entry.enclosure_url.is_none()
                        {
                            entry.enclosure_url = Self::inspect_enclosure_tag(&tag);
                        }
                    } else if Self::is_itunes_image(raw_name) {
                        if let Some(href) = Self::extract_attr(&tag, "href") {
                            if let Some(ref mut entry) = current_entry {
                                if entry.itunes_image_url.is_none() {
                                    entry.itunes_image_url = Some(href);
                                }
                            } else if meta.feed_image_url.is_none() {
                                meta.feed_image_url = Some(href);
                            }
                        }
                    } else if current_entry.is_none() && raw_name.eq_ignore_ascii_case("image") {
                        in_channel_image = true;
                    } else if in_channel_image
                        && current_entry.is_none()
                        && raw_name.eq_ignore_ascii_case("url")
                    {
                        active_text_tag = Some("channel_image_url");
                        text_buf.clear();
                    } else if Self::is_itunes_duration(raw_name) && current_entry.is_some() {
                        active_text_tag = Some("duration");
                        text_buf.clear();
                    } else if Self::is_itunes_author(raw_name) {
                        active_text_tag = Some("itunes_author");
                        text_buf.clear();
                    }
                }
                Ok(Event::Empty(tag)) => {
                    let qname = tag.name();
                    let raw_name = qname.as_ref();
                    let local = Self::local_tag_name(raw_name);
                    if Self::is_entry_tag(raw_name) {
                        if let Some(unfinished) = current_entry.take() {
                            meta.entries.push(unfinished);
                        }
                        meta.entries.push(SupplementalEntryMeta::default());
                        active_text_tag = None;
                    } else if local.eq_ignore_ascii_case("enclosure")
                        || (local.eq_ignore_ascii_case("link") && Self::is_enclosure_link(&tag))
                    {
                        if let Some(ref mut entry) = current_entry
                            && entry.enclosure_url.is_none()
                        {
                            entry.enclosure_url = Self::inspect_enclosure_tag(&tag);
                        }
                    } else if Self::is_itunes_image(raw_name)
                        && let Some(href) = Self::extract_attr(&tag, "href")
                    {
                        if let Some(ref mut entry) = current_entry {
                            if entry.itunes_image_url.is_none() {
                                entry.itunes_image_url = Some(href);
                            }
                        } else if meta.feed_image_url.is_none() {
                            meta.feed_image_url = Some(href);
                        }
                    }
                }
                Ok(Event::Text(text)) if active_text_tag.is_some() => {
                    let decoded =
                        unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into);
                    text_buf.push_str(&decoded);
                }
                Ok(Event::GeneralRef(entity)) if active_text_tag.is_some() => {
                    let raw_entity = format!("&{};", entity.as_ref());
                    if let Ok(decoded) = unescape(&raw_entity) {
                        text_buf.push_str(&decoded);
                    } else {
                        text_buf.push_str(&raw_entity);
                    }
                }
                Ok(Event::CData(cdata)) if active_text_tag.is_some() => {
                    text_buf.push_str(cdata.as_ref());
                }
                Ok(Event::End(tag)) => {
                    let qname = tag.name();
                    let raw_name = qname.as_ref();
                    if Self::is_entry_tag(raw_name) {
                        if let Some(finished) = current_entry.take() {
                            meta.entries.push(finished);
                        }
                        active_text_tag = None;
                    } else if raw_name.eq_ignore_ascii_case("image") && current_entry.is_none() {
                        in_channel_image = false;
                        if active_text_tag == Some("channel_image_url") {
                            active_text_tag = None;
                        }
                    } else if active_text_tag
                        .is_some_and(|kind| Self::matches_active_text_end_tag(kind, raw_name))
                        && let Some(kind) = active_text_tag.take()
                    {
                        let val = text_buf.trim();
                        if !val.is_empty() {
                            match kind {
                                "duration" => {
                                    if let Some(ref mut entry) = current_entry
                                        && entry.duration_secs.is_none()
                                    {
                                        entry.duration_secs = Self::parse_duration_seconds(val);
                                    }
                                }
                                "itunes_author" => {
                                    if let Some(ref mut entry) = current_entry {
                                        if entry.itunes_author.is_none() {
                                            entry.itunes_author = Some(val.to_owned());
                                        }
                                    } else if meta.feed_author.is_none() {
                                        meta.feed_author = Some(val.to_owned());
                                    }
                                }
                                "channel_image_url" if meta.feed_image_url.is_none() => {
                                    meta.feed_image_url = Some(val.to_owned());
                                }
                                _ => {}
                            }
                        }
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        if let Some(unfinished) = current_entry.take() {
            meta.entries.push(unfinished);
        }

        meta
    }

    fn local_tag_name(raw: &str) -> &str {
        raw.rsplit(':').next().unwrap_or(raw)
    }

    const fn is_entry_tag(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("item")
            || raw.eq_ignore_ascii_case("entry")
            || raw.eq_ignore_ascii_case("rdf:item")
            || raw.eq_ignore_ascii_case("atom:entry")
    }

    fn is_enclosure_link(tag: &quick_xml::events::BytesStart<'_>) -> bool {
        Self::extract_attr(tag, "rel").is_some_and(|r| r.eq_ignore_ascii_case("enclosure"))
    }

    fn matches_active_text_end_tag(kind: &str, raw_name: &str) -> bool {
        match kind {
            "duration" => Self::is_itunes_duration(raw_name),
            "itunes_author" => Self::is_itunes_author(raw_name),
            "channel_image_url" => raw_name.eq_ignore_ascii_case("url"),
            _ => false,
        }
    }

    const fn is_itunes_image(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("itunes:image")
    }

    fn is_itunes_duration(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("itunes:duration")
            || Self::local_tag_name(raw).eq_ignore_ascii_case("duration")
    }

    const fn is_itunes_author(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("itunes:author")
    }

    fn extract_attr(tag: &quick_xml::events::BytesStart<'_>, name: &str) -> Option<String> {
        for attr in tag.attributes().with_checks(false).flatten() {
            if attr.key.as_ref().eq_ignore_ascii_case(name) {
                let val = attr
                    .normalized_value(XmlVersion::default())
                    .map_or_else(|_| attr.value.as_ref().to_owned(), Into::into);
                let trimmed = val.trim();
                if !trimmed.is_empty() {
                    return Some(trimmed.to_owned());
                }
            }
        }
        None
    }

    fn inspect_enclosure_tag(tag: &quick_xml::events::BytesStart<'_>) -> Option<Url> {
        let url_str = Self::extract_attr(tag, "url").or_else(|| Self::extract_attr(tag, "href"))?;
        let parsed = SafeHttpClient::parse_url(&url_str).ok()?;
        let mime = Self::extract_attr(tag, "type").unwrap_or_default().to_ascii_lowercase();
        if mime.starts_with("audio/") || is_direct_audio_url(&parsed) { Some(parsed) } else { None }
    }

    fn extract_entry_thumbnail_url(
        entry: &feed_rs::model::Entry,
        base_url: Option<&Url>,
    ) -> Option<String> {
        for media in &entry.media {
            for thumb in &media.thumbnails {
                if let Some(url) =
                    HtmlSanitizer::resolve_and_validate_thumbnail_url(&thumb.image.uri, base_url)
                {
                    return Some(url);
                }
            }
            for content in &media.content {
                let is_image_mime = content
                    .content_type
                    .as_ref()
                    .is_some_and(|mime| mime.ty().as_str().eq_ignore_ascii_case("image"));
                if let Some(ref url) = content.url {
                    let has_img_ext = Self::has_image_extension(url.path());
                    if (is_image_mime || has_img_ext)
                        && !HtmlSanitizer::is_non_thumbnail_url(url)
                        && SafeHttpClient::validate_url(url).is_ok()
                    {
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
                && let Some(url) =
                    HtmlSanitizer::resolve_and_validate_thumbnail_url(&link.href, base_url)
            {
                return Some(url);
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

    #[test]
    fn test_parse_podcast_rss_enclosure_duration_and_itunes_artwork() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
        <channel>
            <title>Acquired</title>
            <link>https://www.acquired.fm</link>
            <description>Every company has a story.</description>
            <itunes:author>Ben Gilbert &amp; David Rosenthal</itunes:author>
            <itunes:image href="https://cdn.example.com/acquired-cover.jpg"/>
            <item>
                <title>NVIDIA: The GPU Company (Part III)</title>
                <link>https://www.acquired.fm/episodes/nvidia-part-3</link>
                <guid isPermaLink="false">ep-nvidia-3</guid>
                <description><![CDATA[<p>00:00 Intro<br/>18:40 CUDA Ecosystem</p>]]></description>
                <enclosure url="https://cdn.example.com/audio/nvidia-part-3.mp3" length="151200000" type="audio/mpeg"/>
                <itunes:duration>04:12:20</itunes:duration>
            </item>
            <item>
                <title>Linkless Episode With Custom Art</title>
                <guid isPermaLink="false">ep-linkless</guid>
                <description>Episode without link tag</description>
                <enclosure url="https://cdn.example.com/audio/linkless.m4a" length="45000000" type="audio/mp4"/>
                <itunes:duration>48:15</itunes:duration>
                <itunes:image href="https://cdn.example.com/ep-custom-art.png"/>
                <itunes:author>Guest Host</itunes:author>
            </item>
        </channel>
        </rss>"#;

        let (feed, items) = RssParser::parse(xml).unwrap();
        assert_eq!(feed.title, "Acquired");
        assert_eq!(items.len(), 2);

        assert_eq!(items[0].title, "NVIDIA: The GPU Company (Part III)");
        assert_eq!(
            items[0].link.as_ref().map(Url::as_str),
            Some("https://www.acquired.fm/episodes/nvidia-part-3")
        );
        assert_eq!(
            items[0].audio_url.as_ref().map(Url::as_str),
            Some("https://cdn.example.com/audio/nvidia-part-3.mp3")
        );
        assert_eq!(items[0].duration, Some(4 * 3600 + 12 * 60 + 20));
        assert_eq!(
            items[0].thumbnail_url.as_deref(),
            Some("https://cdn.example.com/acquired-cover.jpg")
        );
        assert_eq!(items[0].author.as_deref(), Some("Ben Gilbert & David Rosenthal"));

        // Second item has no <link> and non-URL <guid>, so link falls back to audio_url
        assert_eq!(
            items[1].link.as_ref().map(Url::as_str),
            Some("https://cdn.example.com/audio/linkless.m4a")
        );
        assert_eq!(
            items[1].audio_url.as_ref().map(Url::as_str),
            Some("https://cdn.example.com/audio/linkless.m4a")
        );
        assert_eq!(items[1].duration, Some(48 * 60 + 15));
        assert_eq!(
            items[1].thumbnail_url.as_deref(),
            Some("https://cdn.example.com/ep-custom-art.png")
        );
        assert_eq!(items[1].author.as_deref(), Some("Guest Host"));
    }
}
