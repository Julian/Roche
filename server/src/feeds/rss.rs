use std::collections::{HashMap, VecDeque};

use anyhow::Result;
use chrono::{DateTime, Utc};
use quick_xml::{Reader, events::Event};
use url::Url;

use super::{MAX_PARSED_FEED_ENTRIES, extract_entry_timestamp, select_entry_link};
use crate::{
    content::HtmlSanitizer,
    util::{
        media_urls::is_direct_audio_url,
        url_guard::{parse_public_url, validate_public_url},
    },
};

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
    pub authors: Vec<String>,
    pub pub_date: Option<DateTime<Utc>>,
}

#[derive(Debug, Default, Clone)]
struct SupplementalEntryMeta {
    entry_id: Option<String>,
    entry_link: Option<String>,
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

#[derive(Debug, Default)]
struct SupplementalXmlState {
    meta: SupplementalFeedMeta,
    current_entry: Option<SupplementalEntryMeta>,
    source_depth: usize,
    entry_child_depth: usize,
    current_entry_has_alt_link: bool,
    in_channel_image: bool,
    active_text_tag: Option<&'static str>,
    text_buf: String,
    entry_limit_reached: bool,
}

impl SupplementalXmlState {
    fn push_entry(&mut self, entry: SupplementalEntryMeta) {
        if self.meta.entries.len() < MAX_PARSED_FEED_ENTRIES {
            self.meta.entries.push(entry);
        }
        if self.meta.entries.len() >= MAX_PARSED_FEED_ENTRIES {
            self.entry_limit_reached = true;
        }
    }

    fn handle_open_tag(&mut self, tag: &quick_xml::events::BytesStart<'_>, is_empty: bool) {
        if self.entry_limit_reached {
            return;
        }
        let qname = tag.name();
        let raw_name = qname.as_ref();
        if RssParser::is_source_tag(raw_name) {
            if !is_empty {
                self.source_depth += 1;
                self.active_text_tag = None;
            }
            return;
        }
        if self.source_depth > 0 {
            return;
        }

        if RssParser::is_entry_tag(raw_name) {
            if let Some(unfinished) = self.current_entry.take() {
                self.push_entry(unfinished);
                if self.entry_limit_reached {
                    self.active_text_tag = None;
                    return;
                }
            }
            if is_empty {
                self.push_entry(SupplementalEntryMeta::default());
            } else {
                let mut next_entry = SupplementalEntryMeta::default();
                if (raw_name.eq_ignore_ascii_case("item")
                    || raw_name.eq_ignore_ascii_case("rdf:item"))
                    && let Some(about) = RssParser::extract_attr(tag, "rdf:about")
                {
                    next_entry.entry_id = RssParser::normalize_entry_id(&about);
                }
                self.current_entry = Some(next_entry);
            }
            self.entry_child_depth = 0;
            self.current_entry_has_alt_link = false;
            self.active_text_tag = None;
            return;
        }

        let is_direct_entry_child = self.current_entry.is_some() && self.entry_child_depth == 0;
        if !is_empty && self.current_entry.is_some() {
            self.entry_child_depth += 1;
        }

        let local = RssParser::local_tag_name(raw_name);
        if local.eq_ignore_ascii_case("enclosure")
            || (local.eq_ignore_ascii_case("link") && RssParser::is_enclosure_link(tag))
        {
            if let Some(ref mut entry) = self.current_entry
                && entry.enclosure_url.is_none()
            {
                entry.enclosure_url = RssParser::inspect_enclosure_tag(tag);
            }
        } else if is_direct_entry_child
            && RssParser::is_link_tag(raw_name)
            && let Some(ref mut entry) = self.current_entry
        {
            if let Some(href) = RssParser::extract_attr(tag, "href") {
                RssParser::record_atom_entry_link(
                    entry,
                    &mut self.current_entry_has_alt_link,
                    tag,
                    &href,
                );
            } else if !is_empty && entry.entry_link.is_none() {
                self.begin_text_tag("entry_link");
            }
        } else if !is_empty
            && is_direct_entry_child
            && RssParser::is_entry_id_tag(raw_name)
            && self.current_entry.as_ref().is_some_and(|e| e.entry_id.is_none())
        {
            self.begin_text_tag("entry_id");
        } else if RssParser::is_itunes_image(raw_name) {
            if let Some(href) = RssParser::extract_attr(tag, "href") {
                if let Some(ref mut entry) = self.current_entry {
                    if entry.itunes_image_url.is_none() {
                        entry.itunes_image_url = Some(href);
                    }
                } else if self.meta.feed_image_url.is_none() {
                    self.meta.feed_image_url = Some(href);
                }
            }
        } else if !is_empty {
            self.handle_non_empty_text_or_container_tag(raw_name);
        }
    }

    fn begin_text_tag(&mut self, kind: &'static str) {
        self.active_text_tag = Some(kind);
        self.text_buf.clear();
    }

    fn handle_non_empty_text_or_container_tag(&mut self, raw_name: &str) {
        if self.current_entry.is_none() && raw_name.eq_ignore_ascii_case("image") {
            self.in_channel_image = true;
        } else if self.in_channel_image
            && self.current_entry.is_none()
            && raw_name.eq_ignore_ascii_case("url")
        {
            self.begin_text_tag("channel_image_url");
        } else if RssParser::is_itunes_duration(raw_name) && self.current_entry.is_some() {
            self.begin_text_tag("duration");
        } else if RssParser::is_itunes_author(raw_name) {
            self.begin_text_tag("itunes_author");
        }
    }

    fn handle_end_tag(&mut self, raw_name: &str) {
        if RssParser::is_source_tag(raw_name) {
            self.source_depth = self.source_depth.saturating_sub(1);
            return;
        }
        if self.source_depth > 0 {
            return;
        }
        if RssParser::is_entry_tag(raw_name) {
            if let Some(finished) = self.current_entry.take() {
                self.push_entry(finished);
            }
            self.entry_child_depth = 0;
            self.current_entry_has_alt_link = false;
            self.active_text_tag = None;
            return;
        }

        if self.current_entry.is_some() {
            self.entry_child_depth = self.entry_child_depth.saturating_sub(1);
        }
        if raw_name.eq_ignore_ascii_case("image") && self.current_entry.is_none() {
            self.in_channel_image = false;
            if self.active_text_tag == Some("channel_image_url") {
                self.active_text_tag = None;
            }
        } else if self
            .active_text_tag
            .is_some_and(|kind| RssParser::matches_active_text_end_tag(kind, raw_name))
            && let Some(kind) = self.active_text_tag.take()
        {
            self.commit_active_text(kind);
        }
    }

    fn commit_active_text(&mut self, kind: &str) {
        let val = self.text_buf.trim();
        if val.is_empty() {
            return;
        }
        match kind {
            "entry_id" => {
                if let Some(ref mut entry) = self.current_entry
                    && entry.entry_id.is_none()
                {
                    entry.entry_id = RssParser::normalize_entry_id(val);
                }
            }
            "entry_link" => {
                if let Some(ref mut entry) = self.current_entry
                    && entry.entry_link.is_none()
                {
                    entry.entry_link = Some(val.to_owned());
                }
            }
            "duration" => {
                if let Some(ref mut entry) = self.current_entry
                    && entry.duration_secs.is_none()
                {
                    entry.duration_secs = RssParser::parse_duration_seconds(val);
                }
            }
            "itunes_author" => {
                if let Some(ref mut entry) = self.current_entry {
                    if entry.itunes_author.is_none() {
                        entry.itunes_author = Some(val.to_owned());
                    }
                } else if self.meta.feed_author.is_none() {
                    self.meta.feed_author = Some(val.to_owned());
                }
            }
            "channel_image_url" if self.meta.feed_image_url.is_none() => {
                self.meta.feed_image_url = Some(val.to_owned());
            }
            _ => {}
        }
    }

    fn finish(mut self) -> SupplementalFeedMeta {
        if let Some(unfinished) = self.current_entry.take() {
            self.push_entry(unfinished);
        }
        self.meta
    }
}

struct SupplementalIndex<'a> {
    entries: &'a [SupplementalEntryMeta],
    by_id: HashMap<&'a str, VecDeque<usize>>,
    by_link_without_id: HashMap<&'a str, VecDeque<usize>>,
    used: Vec<bool>,
}

impl<'a> SupplementalIndex<'a> {
    fn new(entries: &'a [SupplementalEntryMeta]) -> Self {
        let mut by_id: HashMap<&'a str, VecDeque<usize>> = HashMap::new();
        let mut by_link_without_id: HashMap<&'a str, VecDeque<usize>> = HashMap::new();
        for (idx, entry) in entries.iter().enumerate() {
            if let Some(id) = entry.entry_id.as_deref() {
                by_id.entry(id).or_default().push_back(idx);
            } else if let Some(link) = entry.entry_link.as_deref() {
                by_link_without_id.entry(link).or_default().push_back(idx);
            }
        }
        Self { entries, by_id, by_link_without_id, used: vec![false; entries.len()] }
    }

    fn take_first_unused(queue: &mut VecDeque<usize>, used: &mut [bool]) -> Option<usize> {
        while let Some(idx) = queue.pop_front() {
            if !used[idx] {
                used[idx] = true;
                return Some(idx);
            }
        }
        None
    }

    fn match_entry(
        &mut self,
        idx: usize,
        norm_entry_id: Option<&str>,
        norm_entry_link: Option<&str>,
    ) -> Option<&'a SupplementalEntryMeta> {
        if let Some(eid) = norm_entry_id
            && let Some(queue) = self.by_id.get_mut(eid)
            && let Some(found_idx) = Self::take_first_unused(queue, &mut self.used)
        {
            return Some(&self.entries[found_idx]);
        }

        // Any unused entry whose `entry_id == norm_entry_id` would already have matched above,
        // so an ID-compatible link fallback can only match supplemental entries with no `entry_id`.
        if let Some(elink) = norm_entry_link
            && let Some(queue) = self.by_link_without_id.get_mut(elink)
            && let Some(found_idx) = Self::take_first_unused(queue, &mut self.used)
        {
            return Some(&self.entries[found_idx]);
        }

        if let Some(candidate) = self.entries.get(idx)
            && !self.used[idx]
        {
            let id_conflict =
                candidate.entry_id.as_deref().is_some_and(|cid| norm_entry_id != Some(cid));
            let link_conflict = candidate.entry_link.as_deref() != norm_entry_link;
            if !id_conflict && !link_conflict {
                self.used[idx] = true;
                return Some(candidate);
            }
        }

        None
    }
}

pub struct RssParser;

impl RssParser {
    pub fn parse(content: &str) -> Result<(RssFeed, Vec<RssItem>)> {
        let mut feed = feed_rs::parser::parse(content.as_bytes())?;
        feed.entries.truncate(MAX_PARSED_FEED_ENTRIES);
        let mut supplemental = Self::extract_supplemental_xml_metadata(content);

        let feed_base_url = select_entry_link(&feed.links);
        for supp_entry in &mut supplemental.entries {
            supp_entry.entry_link = supp_entry
                .entry_link
                .as_deref()
                .and_then(|raw| Self::resolve_supp_link(raw, feed_base_url.as_ref()));
        }

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
            title: feed.title.map_or_default(|t| t.content),
            description: feed.description.map_or_default(|d| d.content),
            language: feed.language.unwrap_or_default(),
        };

        let mut supp_index = SupplementalIndex::new(&supplemental.entries);
        let items = feed
            .entries
            .into_iter()
            .enumerate()
            .map(|(idx, entry)| {
                let norm_entry_id = Self::normalize_entry_id(&entry.id);
                let primary_link = select_entry_link(&entry.links);
                let norm_entry_link = primary_link.as_ref().map(Url::as_str);
                let supp = supp_index.match_entry(idx, norm_entry_id.as_deref(), norm_entry_link);
                let pub_date = extract_entry_timestamp(&entry);
                let (media_audio_url, media_duration) = Self::extract_entry_audio(&entry);
                let audio_url =
                    media_audio_url.or_else(|| supp.and_then(|s| s.enclosure_url.clone()));
                let duration = media_duration.or_else(|| supp.and_then(|s| s.duration_secs));
                let link = primary_link
                    .or_else(|| parse_public_url(&entry.id).ok())
                    .or_else(|| audio_url.clone());
                let base_for_thumb = link.as_ref().or(feed_base_url.as_ref());
                let thumbnail_url = Self::extract_entry_thumbnail_url(&entry, base_for_thumb)
                    .or_else(|| {
                        supp.and_then(|s| s.itunes_image_url.as_deref()).and_then(|raw| {
                            HtmlSanitizer::resolve_and_validate_thumbnail_url(raw, base_for_thumb)
                        })
                    })
                    .or_else(|| audio_url.is_some().then(|| feed_fallback_thumb.clone()).flatten());
                let title = entry.title.map_or_default(|t| t.content);
                let body = entry.content.and_then(|c| c.body);
                let summary = entry.summary.map(|s| s.content);
                let description = summary.clone().or_else(|| body.clone()).unwrap_or_default();
                let content_html = body.or(summary);

                let authors = Self::extract_entry_authors(
                    entry.authors,
                    supp,
                    audio_url.is_some(),
                    supplemental.feed_author.as_deref(),
                );

                RssItem {
                    title,
                    link,
                    description,
                    content_html,
                    thumbnail_url,
                    audio_url,
                    duration,
                    authors,
                    pub_date,
                }
            })
            .collect();

        Ok((parsed_feed, items))
    }

    fn extract_entry_authors(
        entry_authors: Vec<feed_rs::model::Person>,
        supp: Option<&SupplementalEntryMeta>,
        has_audio: bool,
        feed_author: Option<&str>,
    ) -> Vec<String> {
        let mut authors: Vec<String> = entry_authors
            .into_iter()
            .filter_map(|a| {
                let trimmed = a.name.as_deref()?.trim();
                (!trimmed.is_empty()).then(|| trimmed.to_owned())
            })
            .collect();
        if authors.is_empty()
            && let Some(fallback) = supp
                .and_then(|s| s.itunes_author.as_deref())
                .or_else(|| has_audio.then_some(feed_author).flatten())
                .map(str::trim)
                .filter(|s| !s.is_empty())
        {
            authors.push(fallback.to_owned());
        }
        authors
    }

    fn normalize_entry_id(raw: &str) -> Option<String> {
        let trimmed = raw.trim();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
    }

    fn resolve_supp_link(raw: &str, base_url: Option<&Url>) -> Option<String> {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return None;
        }
        if let Ok(url) = parse_public_url(trimmed) {
            return Some(url.into());
        }
        let joined = base_url?.join(trimmed).ok()?;
        validate_public_url(&joined).ok()?;
        Some(joined.into())
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
                    && validate_public_url(url).is_ok()
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
                    && let Ok(parsed) = parse_public_url(&link.href)
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

    fn extract_supplemental_xml_metadata(content: &str) -> SupplementalFeedMeta {
        let mut reader = Reader::from_str(content);
        reader.config_mut().check_end_names = false;
        let mut state = SupplementalXmlState::default();

        loop {
            if state.entry_limit_reached && state.current_entry.is_none() {
                break;
            }
            match reader.read_event() {
                Ok(Event::Start(tag)) => state.handle_open_tag(&tag, false),
                Ok(Event::Empty(tag)) => state.handle_open_tag(&tag, true),
                Ok(Event::Text(text)) if state.active_text_tag.is_some() => {
                    state.text_buf.push_str(&crate::content::xml_util::decode_text(&text));
                }
                Ok(Event::GeneralRef(entity)) if state.active_text_tag.is_some() => {
                    state.text_buf.push_str(&crate::content::xml_util::decode_entity(&entity));
                }
                Ok(Event::CData(cdata)) if state.active_text_tag.is_some() => {
                    state.text_buf.push_str(cdata.as_ref());
                }
                Ok(Event::End(tag)) => {
                    let qname = tag.name();
                    state.handle_end_tag(qname.as_ref());
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        state.finish()
    }

    fn record_atom_entry_link(
        entry: &mut SupplementalEntryMeta,
        has_alt_link: &mut bool,
        tag: &quick_xml::events::BytesStart<'_>,
        href: &str,
    ) {
        let rel = Self::extract_attr(tag, "rel");
        if rel.as_deref().is_some_and(|r| r.eq_ignore_ascii_case("enclosure")) {
            return;
        }
        let is_alternate = rel.as_deref().is_none_or(|r| r.eq_ignore_ascii_case("alternate"));
        if is_alternate {
            if !*has_alt_link {
                entry.entry_link = Some(href.to_owned());
                *has_alt_link = true;
            }
        } else if entry.entry_link.is_none() {
            entry.entry_link = Some(href.to_owned());
        }
    }

    fn local_tag_name(raw: &str) -> &str {
        raw.rsplit(':').next().unwrap_or(raw)
    }

    fn is_source_tag(raw: &str) -> bool {
        Self::local_tag_name(raw).eq_ignore_ascii_case("source")
    }

    const fn is_entry_tag(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("item")
            || raw.eq_ignore_ascii_case("entry")
            || raw.eq_ignore_ascii_case("rdf:item")
            || raw.eq_ignore_ascii_case("atom:entry")
    }

    const fn is_entry_id_tag(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("id")
            || raw.eq_ignore_ascii_case("atom:id")
            || raw.eq_ignore_ascii_case("guid")
    }

    const fn is_link_tag(raw: &str) -> bool {
        raw.eq_ignore_ascii_case("link")
            || raw.eq_ignore_ascii_case("atom:link")
            || raw.eq_ignore_ascii_case("rss:link")
    }

    fn is_enclosure_link(tag: &quick_xml::events::BytesStart<'_>) -> bool {
        Self::extract_attr(tag, "rel").is_some_and(|r| r.eq_ignore_ascii_case("enclosure"))
    }

    fn matches_active_text_end_tag(kind: &str, raw_name: &str) -> bool {
        match kind {
            "entry_id" => Self::is_entry_id_tag(raw_name),
            "entry_link" => Self::is_link_tag(raw_name),
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
        crate::content::xml_util::find_non_empty_attr(tag, name)
    }

    fn inspect_enclosure_tag(tag: &quick_xml::events::BytesStart<'_>) -> Option<Url> {
        let url_str = Self::extract_attr(tag, "url").or_else(|| Self::extract_attr(tag, "href"))?;
        let parsed = parse_public_url(&url_str).ok()?;
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
                        && validate_public_url(url).is_ok()
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
        assert_eq!(items[0].authors, vec!["Ben Gilbert & David Rosenthal"]);

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
        assert_eq!(items[1].authors, vec!["Guest Host"]);
    }

    #[test]
    fn test_parse_atom_podcast_ignores_nested_source_entries_and_handles_missing_ids() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
            <title>Distributed Audio</title>
            <link rel="alternate" href="https://podcast.example.com"/>
            <itunes:author>Network Default</itunes:author>
            <itunes:image href="https://cdn.example.com/default-cover.jpg"/>
            <entry>
                <id>urn:uuid:ep-1</id>
                <title>Episode 1 With Source Block</title>
                <link rel="alternate" href="https://podcast.example.com/episodes/1"/>
                <source>
                    <id>urn:uuid:origin-feed</id>
                    <title>Origin Syndicated Feed</title>
                    <itunes:author>Wrong Source Author</itunes:author>
                    <itunes:image href="https://cdn.example.com/wrong-source-cover.jpg"/>
                    <entry>
                        <id>urn:uuid:nested-source-ep</id>
                        <title>Nested Source Episode</title>
                        <link rel="alternate" href="https://origin.example.com/nested"/>
                        <enclosure url="https://cdn.example.com/wrong-nested.mp3" type="audio/mpeg"/>
                        <itunes:duration>99:59</itunes:duration>
                        <itunes:image href="https://cdn.example.com/wrong-nested.jpg"/>
                        <itunes:author>Wrong Nested Host</itunes:author>
                    </entry>
                </source>
                <enclosure url="https://cdn.example.com/audio/ep-1.mp3" type="audio/mpeg"/>
                <itunes:duration>15:30</itunes:duration>
                <itunes:image href="https://cdn.example.com/art/ep-1.jpg"/>
                <itunes:author>Host One</itunes:author>
            </entry>
            <entry>
                <title>Episode 2 Missing ID Matched By Link</title>
                <link rel="edit" href="https://podcast.example.com/edit/2"/>
                <link rel="alternate" href="https://podcast.example.com/episodes/2"/>
                <enclosure url="https://cdn.example.com/audio/ep-2.mp3" type="audio/mpeg"/>
                <itunes:duration>42:10</itunes:duration>
                <itunes:image href="https://cdn.example.com/art/ep-2.jpg"/>
                <itunes:author>Host Two</itunes:author>
            </entry>
            <entry>
                <title>Episode 3 Missing ID And Link</title>
                <enclosure url="https://cdn.example.com/audio/ep-3.mp3" type="audio/mpeg"/>
                <itunes:duration>08:05</itunes:duration>
                <itunes:image href="https://cdn.example.com/art/ep-3.jpg"/>
                <itunes:author>Host Three</itunes:author>
            </entry>
        </feed>"#;

        let (feed, items) = RssParser::parse(xml).unwrap();
        assert_eq!(feed.title, "Distributed Audio");
        assert_eq!(items.len(), 3);

        assert_eq!(items[0].title, "Episode 1 With Source Block");
        assert_eq!(
            items[0].link.as_ref().map(Url::as_str),
            Some("https://podcast.example.com/episodes/1")
        );
        assert_eq!(
            items[0].audio_url.as_ref().map(Url::as_str),
            Some("https://cdn.example.com/audio/ep-1.mp3")
        );
        assert_eq!(items[0].duration, Some(15 * 60 + 30));
        assert_eq!(items[0].thumbnail_url.as_deref(), Some("https://cdn.example.com/art/ep-1.jpg"));
        assert_eq!(items[0].authors, vec!["Host One"]);

        assert_eq!(items[1].title, "Episode 2 Missing ID Matched By Link");
        assert_eq!(
            items[1].link.as_ref().map(Url::as_str),
            Some("https://podcast.example.com/episodes/2")
        );
        assert_eq!(
            items[1].audio_url.as_ref().map(Url::as_str),
            Some("https://cdn.example.com/audio/ep-2.mp3")
        );
        assert_eq!(items[1].duration, Some(42 * 60 + 10));
        assert_eq!(items[1].thumbnail_url.as_deref(), Some("https://cdn.example.com/art/ep-2.jpg"));
        assert_eq!(items[1].authors, vec!["Host Two"]);

        assert_eq!(items[2].title, "Episode 3 Missing ID And Link");
        assert_eq!(
            items[2].audio_url.as_ref().map(Url::as_str),
            Some("https://cdn.example.com/audio/ep-3.mp3")
        );
        assert_eq!(items[2].duration, Some(8 * 60 + 5));
        assert_eq!(items[2].thumbnail_url.as_deref(), Some("https://cdn.example.com/art/ep-3.jpg"));
        assert_eq!(items[2].authors, vec!["Host Three"]);
    }

    #[test]
    fn test_match_supplemental_entry_handles_reordered_and_conflicting_metadata() {
        let entries = vec![
            SupplementalEntryMeta {
                entry_id: Some("ep-skipped".to_string()),
                entry_link: Some("https://podcast.example.com/ep-skipped".to_string()),
                duration_secs: Some(999),
                ..SupplementalEntryMeta::default()
            },
            SupplementalEntryMeta {
                entry_id: None,
                entry_link: Some("https://podcast.example.com/ep-by-link".to_string()),
                duration_secs: Some(240),
                ..SupplementalEntryMeta::default()
            },
            SupplementalEntryMeta {
                entry_id: Some("ep-by-id".to_string()),
                entry_link: Some("https://podcast.example.com/shared".to_string()),
                duration_secs: Some(360),
                ..SupplementalEntryMeta::default()
            },
        ];
        let mut index = SupplementalIndex::new(&entries);

        // 1. Reordered entry matches by ID despite shifted index
        let m1 = index
            .match_entry(0, Some("ep-by-id"), Some("https://podcast.example.com/shared"))
            .unwrap();
        assert_eq!(m1.duration_secs, Some(360));

        // 2. Entry with synthesized/non-matching feed-rs ID matches unused entry by primary link
        let m2 = index
            .match_entry(
                1,
                Some("synthesized-feed-rs-hash"),
                Some("https://podcast.example.com/ep-by-link"),
            )
            .unwrap();
        assert_eq!(m2.duration_secs, Some(240));

        // 3. Mismatched ID and link at idx 0 must NOT steal ep-skipped via positional fallback
        let m3 = index.match_entry(
            0,
            Some("ep-different"),
            Some("https://podcast.example.com/ep-different"),
        );
        assert!(m3.is_none());
        assert!(!index.used[0]);
    }

    #[test]
    fn test_rss_parser_caps_and_linearly_parses_large_feeds() {
        use std::fmt::Write as _;

        let mut xml = String::from(
            r#"<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>Big</title>"#,
        );
        for i in 0..1_250 {
            let _ = write!(
                xml,
                "<item><title>Item {i}</title><guid>g-{i}</guid><link>https://example.com/{i}</link></item>"
            );
        }
        xml.push_str("</channel></rss>");

        let (_feed, items) = RssParser::parse(&xml).unwrap();
        assert_eq!(items.len(), MAX_PARSED_FEED_ENTRIES);
        assert_eq!(items[0].title, "Item 0");
    }
}
