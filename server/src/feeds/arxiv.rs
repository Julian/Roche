use anyhow::Result;
use chrono::{DateTime, Utc};
use url::Url;

use super::{extract_entry_timestamp, select_entry_link};
use crate::content::fetcher::SafeHttpClient;

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
    #[must_use]
    pub fn is_arxiv_host(host: Option<&str>) -> bool {
        matches!(host, Some("arxiv.org" | "rss.arxiv.org" | "export.arxiv.org"))
    }

    pub fn parse(content: &str) -> Result<ArxivFeed> {
        let feed = feed_rs::parser::parse(content.as_bytes())?;

        let title = feed.title.map(|t| t.content).unwrap_or_default();

        let items = feed
            .entries
            .into_iter()
            .map(|entry| {
                let published_at = extract_entry_timestamp(&entry);
                let abstract_url = select_entry_link(&entry.links).or_else(|| {
                    Self::extract_arxiv_id(&entry.id).and_then(|id| {
                        SafeHttpClient::parse_url(&format!("https://arxiv.org/abs/{id}")).ok()
                    })
                });
                let title = entry.title.map(|t| t.content).unwrap_or_default();

                let authors: Vec<String> =
                    entry.authors.into_iter().filter_map(|a| a.name).collect();

                let summary = entry.summary.map(|s| s.content).unwrap_or_default();

                ArxivPaper { title, authors, abstract_url, published_at, summary }
            })
            .collect();

        Ok(ArxivFeed { title, items })
    }

    #[must_use]
    pub fn extract_arxiv_id(url_or_id: &str) -> Option<String> {
        let trimmed = url_or_id.trim();
        let stripped_oai = trimmed
            .strip_prefix("oai:arXiv.org:")
            .or_else(|| trimmed.strip_prefix("arXiv:"))
            .unwrap_or(trimmed);

        let id_part = stripped_oai
            .find("/abs/")
            .map(|idx| &stripped_oai[idx + "/abs/".len()..])
            .or_else(|| stripped_oai.find("/pdf/").map(|idx| &stripped_oai[idx + "/pdf/".len()..]))
            .unwrap_or(stripped_oai);

        let cleaned = id_part.split(&['?', '#'][..]).next().unwrap_or(id_part);
        let cleaned = cleaned.strip_suffix(".pdf").unwrap_or(cleaned).trim_matches('/');

        if cleaned.is_empty() { None } else { Some(cleaned.to_string()) }
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
            Some("http://arxiv.org/abs/2301.07041")
        );
        assert_eq!(feed.items[0].authors, vec!["Vaswani, Ashish"]);
    }
}
