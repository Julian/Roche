use std::io::Cursor;

use quick_xml::{
    Reader, Writer,
    events::{BytesDecl, BytesEnd, BytesStart, BytesText, Event},
};
use url::Url;

use crate::{
    content::{fetcher::SafeHttpClient, xml_util},
    db::{Feed, FeedType},
};

const MAX_OPML_OUTLINES: usize = 2_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpmlOutline {
    pub feed_type: FeedType,
    pub xml_url: Url,
    pub title: Option<String>,
}

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

                    for (key, val) in xml_util::iter_attrs(&tag) {
                        if key.eq_ignore_ascii_case("xmlurl") {
                            xml_url = Some(val);
                        } else if key.eq_ignore_ascii_case("title") {
                            title = Some(val);
                        } else if key.eq_ignore_ascii_case("text") {
                            text = Some(val);
                        }
                    }

                    if let Some((feed_type, normalized_url)) = xml_url
                        .and_then(|u| SafeHttpClient::parse_url(&u).ok())
                        .and_then(Self::normalize_outline_url)
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
    pub fn to_xml(feeds: &[Feed]) -> String {
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
        for feed in feeds {
            let title = feed.title.as_deref().unwrap_or_else(|| feed.url.as_str());
            let mut outline = BytesStart::new("outline");
            outline.push_attribute(("type", "rss"));
            outline.push_attribute(("text", title));
            outline.push_attribute(("title", title));
            outline.push_attribute(("xmlUrl", feed.url.as_str()));
            let _ = writer.write_event(Event::Empty(outline));
        }
        let _ = writer.write_event(Event::End(BytesEnd::new("body")));
        let _ = writer.write_event(Event::End(BytesEnd::new("opml")));

        let mut out = String::from_utf8(writer.into_inner().into_inner()).unwrap_or_default();
        out.push('\n');
        out
    }

    fn normalize_outline_url(url: Url) -> Option<(FeedType, Url)> {
        super::FeedSource::Url(url).into_feed_url(FeedType::Rss).ok()
    }
}

#[cfg(test)]
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
}
