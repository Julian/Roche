use std::io::Cursor;

use quick_xml::{
    Reader, Writer, XmlVersion,
    events::{BytesDecl, BytesEnd, BytesStart, BytesText, Event},
};
use url::Url;

use crate::{
    content::fetcher::SafeHttpClient,
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

                    for attr in tag.attributes().with_checks(false).flatten() {
                        let key = attr.key.as_ref();
                        let Ok(val) = attr.normalized_value(XmlVersion::default()) else {
                            continue;
                        };
                        if key.eq_ignore_ascii_case("xmlurl") {
                            xml_url = Some(val.into_owned());
                        } else if key.eq_ignore_ascii_case("title") {
                            title = Some(val.into_owned());
                        } else if key.eq_ignore_ascii_case("text") {
                            text = Some(val.into_owned());
                        }
                    }

                    if let Some(parsed_url) =
                        xml_url.and_then(|u| SafeHttpClient::parse_url(&u).ok())
                    {
                        let (feed_type, normalized_url) = Self::normalize_outline_url(parsed_url);
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

    fn normalize_outline_url(url: Url) -> (FeedType, Url) {
        super::FeedSource::Url(url.clone())
            .into_feed_url(FeedType::Rss)
            .unwrap_or((FeedType::Rss, url))
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
}
