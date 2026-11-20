use anyhow::Result;
use chrono::{DateTime, Utc};
use url::Url;

use super::{extract_entry_timestamp, select_entry_link};
use crate::util::{media_urls, url_guard::parse_public_url};

#[derive(Debug, Clone)]
pub struct ArxivFeed {
    pub title: String,
    pub items: Vec<ArxivPaper>,
}

#[derive(Debug, Clone)]
pub struct ArxivPaper {
    pub title: String,
    pub authors: Vec<String>,
    pub abstract_url: Option<Url>,
    pub published_at: Option<DateTime<Utc>>,
    pub summary: String,
}

pub struct ArxivParser;

impl ArxivParser {
    #[allow(dead_code)]
    #[must_use]
    pub fn is_arxiv_host(host: Option<&str>) -> bool {
        media_urls::is_arxiv_host(host)
    }

    pub fn parse(content: &str) -> Result<ArxivFeed> {
        let feed = feed_rs::parser::parse(content.as_bytes())?;

        let title = feed.title.map_or_default(|t| t.content);

        let items = feed
            .entries
            .into_iter()
            .map(|entry| {
                let published_at = extract_entry_timestamp(&entry);
                let abstract_url = select_entry_link(&entry.links)
                    .map(|url| media_urls::canonical_arxiv_abstract_url(&url).unwrap_or(url))
                    .or_else(|| {
                        Self::extract_arxiv_id(&entry.id).and_then(|id| {
                            let versionless = media_urls::strip_arxiv_version(&id);
                            parse_public_url(&format!("https://arxiv.org/abs/{versionless}")).ok()
                        })
                    });
                let title = entry.title.map_or_default(|t| t.content);

                let authors: Vec<String> = entry
                    .authors
                    .into_iter()
                    .filter_map(|a| {
                        let trimmed = a.name.as_deref()?.trim();
                        (!trimmed.is_empty()).then(|| trimmed.to_owned())
                    })
                    .collect();

                let summary = entry.summary.map_or_default(|s| s.content);

                ArxivPaper { title, authors, abstract_url, published_at, summary }
            })
            .collect();

        Ok(ArxivFeed { title, items })
    }

    #[must_use]
    pub fn extract_arxiv_id(url_or_id: &str) -> Option<String> {
        media_urls::extract_arxiv_id(url_or_id)
    }

    #[allow(dead_code)]
    #[must_use]
    pub fn pdf_url_for_paper(paper_url: &Url) -> Option<Url> {
        media_urls::arxiv_pdf_url(paper_url)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_arxiv_id() {
        assert_eq!(
            ArxivParser::extract_arxiv_id("http://arxiv.org/abs/2301.07041"),
            Some("2301.07041".to_string())
        );
        assert_eq!(
            ArxivParser::extract_arxiv_id("https://arxiv.org/pdf/2301.07041.pdf"),
            Some("2301.07041".to_string())
        );
        assert_eq!(
            ArxivParser::extract_arxiv_id("http://arxiv.org/abs/2301.07041v2?context=cs"),
            Some("2301.07041v2".to_string())
        );
        assert_eq!(
            ArxivParser::extract_arxiv_id("oai:arXiv.org:2301.07041v1"),
            Some("2301.07041v1".to_string())
        );
        assert_eq!(
            ArxivParser::extract_arxiv_id("http://arxiv.org/abs/hep-th/9901001"),
            Some("hep-th/9901001".to_string())
        );
        assert_eq!(ArxivParser::extract_arxiv_id("2301.07041"), Some("2301.07041".to_string()));
    }

    #[test]
    fn test_parse_arxiv_feed() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns="http://purl.org/rss/1.0/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
         <channel rdf:about="http://arxiv.org/rss/cs.LG">
          <title>cs.LG updates on arXiv.org</title>
          <link>http://arxiv.org/rss/cs.LG</link>
          <description>arXiv cs.LG updates</description>
         </channel>
         <item rdf:about="http://arxiv.org/abs/2301.07041">
          <title>Attention Is All You Need</title>
          <link>http://arxiv.org/abs/2301.07041</link>
          <description>Abstract of paper</description>
          <dc:creator>Vaswani, Ashish</dc:creator>
         </item>
        </rdf:RDF>"#;

        let feed = ArxivParser::parse(xml).unwrap();
        assert_eq!(feed.title, "cs.LG updates on arXiv.org");
        assert_eq!(feed.items.len(), 1);
        assert_eq!(feed.items[0].title, "Attention Is All You Need");
        assert_eq!(
            feed.items[0].abstract_url.as_ref().map(Url::as_str),
            Some("https://arxiv.org/abs/2301.07041")
        );
        assert_eq!(feed.items[0].authors, vec!["Vaswani, Ashish"]);
    }

    #[test]
    fn test_parse_arxiv_feed_canonicalizes_http_and_version_suffix() {
        let xml = r#"<?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                 xmlns="http://purl.org/rss/1.0/"
                 xmlns:dc="http://purl.org/dc/elements/1.1/">
         <channel rdf:about="http://arxiv.org/rss/cs.LG">
          <title>cs.LG updates on arXiv.org</title>
          <link>http://arxiv.org/rss/cs.LG</link>
          <description>arXiv cs.LG updates</description>
         </channel>
         <item rdf:about="http://arxiv.org/abs/2603.11842v1">
          <title>Speculative Decoding</title>
          <link>http://arxiv.org/abs/2603.11842v1</link>
          <description>Abstract</description>
         </item>
         <item rdf:about="http://arxiv.org/abs/hep-th/9901001v2">
          <title>Classic Paper</title>
          <link>http://arxiv.org/abs/hep-th/9901001v2</link>
          <description>Abstract</description>
         </item>
        </rdf:RDF>"#;

        let feed = ArxivParser::parse(xml).unwrap();
        assert_eq!(feed.items.len(), 2);
        assert_eq!(
            feed.items[0].abstract_url.as_ref().map(Url::as_str),
            Some("https://arxiv.org/abs/2603.11842")
        );
        assert_eq!(
            feed.items[1].abstract_url.as_ref().map(Url::as_str),
            Some("https://arxiv.org/abs/hep-th/9901001")
        );
    }
}
