use std::io::Cursor;

use quick_xml::{
    Reader, Writer,
    events::{BytesDecl, BytesEnd, BytesStart, BytesText, Event},
};
use url::Url;

use crate::{
    domain::{FeedType, OpmlOutline},
    url_guard::parse_public_url,
    xml_util,
};

const MAX_OPML_OUTLINES: usize = 2_000;

pub struct OpmlDocument;

impl OpmlDocument {
    /// Parses `<outline>` elements from an OPML XML document using `quick-xml`.
    #[must_use]
    pub fn parse(opml: &str) -> Vec<OpmlOutline> {
        let mut reader = Reader::from_str(opml);
        reader.config_mut().trim_text(true);
        let mut results = Vec::new();

        loop {
            if results.len() >= MAX_OPML_OUTLINES {
                break;
            }
            match reader.read_event() {
                Ok(Event::Start(tag) | Event::Empty(tag))
                    if tag.name().as_ref().eq_ignore_ascii_case("outline") =>
                {
                    let mut xml_url = None;
                    let mut title = None;
                    let mut text = None;
                    let mut explicit_podcast = false;

                    for (key, val) in xml_util::iter_attrs(&tag) {
                        if key.eq_ignore_ascii_case("xmlurl") {
                            xml_url = Some(val);
                        } else if key.eq_ignore_ascii_case("title") {
                            title = Some(val);
                        } else if key.eq_ignore_ascii_case("text") {
                            text = Some(val);
                        } else if (key.eq_ignore_ascii_case("type")
                            || key.eq_ignore_ascii_case("rochefeedtype")
                            || key.eq_ignore_ascii_case("feedtype"))
                            && val.trim().eq_ignore_ascii_case("podcast")
                        {
                            explicit_podcast = true;
                        }
                    }

                    let requested_type =
                        if explicit_podcast { FeedType::Podcast } else { FeedType::Rss };
                    if let Some((feed_type, normalized_url)) = xml_url
                        .and_then(|u| parse_public_url(&u).ok())
                        .and_then(|url| Self::normalize_outline_url(url, requested_type))
                    {
                        results.push(OpmlOutline {
                            feed_type,
                            xml_url: normalized_url,
                            title: title.or(text),
                        });
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        results
    }

    #[must_use]
    pub fn to_xml(outlines: &[OpmlOutline]) -> String {
        let mut writer = Writer::new_with_indent(Cursor::new(Vec::new()), b' ', 2);
        let _ = writer.write_event(Event::Decl(BytesDecl::new("1.0", Some("UTF-8"), None)));

        let mut opml_start = BytesStart::new("opml");
        opml_start.push_attribute(("version", "2.0"));
        let _ = writer.write_event(Event::Start(opml_start));

        let _ = writer.write_event(Event::Start(BytesStart::new("head")));
        let _ = writer.write_event(Event::Start(BytesStart::new("title")));
        let _ = writer.write_event(Event::Text(BytesText::new("Roche Feeds")));
        let _ = writer.write_event(Event::End(BytesEnd::new("title")));
        let _ = writer.write_event(Event::End(BytesEnd::new("head")));

        let _ = writer.write_event(Event::Start(BytesStart::new("body")));
        for item in outlines {
            let title = item.title.as_deref().unwrap_or_else(|| item.xml_url.as_str());
            let mut outline = BytesStart::new("outline");
            outline.push_attribute(("type", "rss"));
            if item.feed_type == FeedType::Podcast {
                outline.push_attribute(("rocheFeedType", "podcast"));
            }
            outline.push_attribute(("text", title));
            outline.push_attribute(("title", title));
            outline.push_attribute(("xmlUrl", item.xml_url.as_str()));
            let _ = writer.write_event(Event::Empty(outline));
        }
        let _ = writer.write_event(Event::End(BytesEnd::new("body")));
        let _ = writer.write_event(Event::End(BytesEnd::new("opml")));

        let mut out = String::from_utf8(writer.into_inner().into_inner()).unwrap_or_default();
        out.push('\n');
        out
    }

    fn normalize_outline_url(url: Url, requested_type: FeedType) -> Option<(FeedType, Url)> {
        super::FeedSource::Url(url).into_feed_url(requested_type).ok()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_opml_parser_handles_quotes_entities_and_comments() {
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <!-- <outline xmlUrl="https://commented-out.example.com/feed.xml" /> -->
            <outlineGroup title="Tech">
              <outline type="rss" text="Fake xmlUrl='https://spoofed.example.com/rss'" title="Rust &amp; Friends &#38; &#x26;" xmlUrl = "https://blog.rust-lang.org/feed.xml?a=1&amp;b=2" />
            </outlineGroup>
          </body>
        </opml>"#;

        let outlines = OpmlDocument::parse(opml);
        assert_eq!(outlines.len(), 1);
        assert_eq!(outlines[0].xml_url.as_str(), "https://blog.rust-lang.org/feed.xml?a=1&b=2");
        assert_eq!(outlines[0].title.as_deref(), Some("Rust & Friends & &"));
        assert_eq!(outlines[0].feed_type, FeedType::Rss);
    }

    #[test]
    fn test_opml_parser_skips_single_arxiv_paper_urls_and_keeps_valid_feeds() {
        let opml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0">
          <body>
            <outline type="rss" title="Rust Blog" xmlUrl="https://blog.rust-lang.org/feed.xml" />
            <outline type="rss" title="arXiv cs.AI" xmlUrl="https://rss.arxiv.org/rss/cs.AI" />
            <outline type="rss" title="Single arXiv Abs" xmlUrl="https://arxiv.org/abs/2301.07041" />
            <outline type="rss" title="Single arXiv PDF" xmlUrl="https://arxiv.org/pdf/2301.07041.pdf" />
            <outline type="rss" title="YouTube Channel" xmlUrl="https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA" />
          </body>
        </opml>"#;

        let outlines = OpmlDocument::parse(opml);
        assert_eq!(outlines.len(), 3);
        assert_eq!(outlines[0].feed_type, FeedType::Rss);
        assert_eq!(outlines[0].xml_url.as_str(), "https://blog.rust-lang.org/feed.xml");
        assert_eq!(outlines[1].feed_type, FeedType::Arxiv);
        assert_eq!(outlines[1].xml_url.as_str(), "https://rss.arxiv.org/rss/cs.AI");
        assert_eq!(outlines[2].feed_type, FeedType::Youtube);
        assert_eq!(
            outlines[2].xml_url.as_str(),
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCX6OQ3DkcsbYNE6H8uQQuVA"
        );
    }

    #[test]
    fn test_opml_roundtrip_preserves_podcast_feed_type() {
        let exported = OpmlDocument::to_xml(&[
            OpmlOutline {
                feed_type: FeedType::Rss,
                xml_url: Url::parse("https://blog.example.com/feed.xml").unwrap(),
                title: Some("Essay Blog".to_owned()),
            },
            OpmlOutline {
                feed_type: FeedType::Podcast,
                xml_url: Url::parse("https://shows.example.com/pod.xml").unwrap(),
                title: Some("Deep Dive Pod".to_owned()),
            },
        ]);
        assert!(exported.contains(r#"rocheFeedType="podcast""#));

        let reparsed = OpmlDocument::parse(&exported);
        assert_eq!(reparsed.len(), 2);
        assert_eq!(reparsed[0].feed_type, FeedType::Rss);
        assert_eq!(reparsed[1].feed_type, FeedType::Podcast);

        let podcatcher = r#"<?xml version="1.0" encoding="UTF-8"?>
        <opml version="2.0"><body>
          <outline type="podcast" text="Third-Party Pod" xmlUrl="https://casts.example.com/rss" />
        </body></opml>"#;
        let parsed_podcatcher = OpmlDocument::parse(podcatcher);
        assert_eq!(parsed_podcatcher.len(), 1);
        assert_eq!(parsed_podcatcher[0].feed_type, FeedType::Podcast);
    }
}
