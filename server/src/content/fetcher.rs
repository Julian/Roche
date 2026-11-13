#[allow(unused_imports)]
pub use super::{
    classify::{
        UrlClassifier, classify_url, is_direct_audio_url, is_direct_pdf_url, is_valid_pdf_bytes,
        resolve_pdf_url,
    },
    html::{ExtractedSnapshot, HtmlSanitizer, MAX_SUMMARY_CHARS},
    http::{SafeDnsResolver, SafeHttpClient},
};

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use url::Url;

    use super::*;
    use crate::db::ContentType;

    #[test]
    fn test_html_sanitizer_strips_scripts() {
        let clean =
            HtmlSanitizer::clean("<html><body><script>alert(1)</script><p>Hello</p></body></html>");
        assert!(!clean.contains("<script>"));
        assert!(clean.contains("<p>Hello</p>"));
    }

    #[test]
    fn test_extract_snapshot_and_url_classification() {
        let html = r#"<!DOCTYPE html>
        <html>
          <head>
            <script>if (1 < 2 && 3 > 1) { console.log("<title>Fake</title>"); }</script>
            <title>Deep Systems &amp; Storage</title>
            <meta name="description" content="A guide to crash-safe SQLite & WAL." />
          </head>
          <body>
            <svg><title>Icon Title</title></svg>
            <script>evil()</script>
            <article><p>First paragraph body.</p></article>
          </body>
        </html>"#;

        let snap = HtmlSanitizer::extract_snapshot(html);
        assert_eq!(snap.title.as_deref(), Some("Deep Systems & Storage"));
        assert_eq!(snap.summary.as_deref(), Some("A guide to crash-safe SQLite & WAL."));
        assert!(snap.content_html.contains("<p>First paragraph body.</p>"));
        assert!(!snap.content_html.contains("<script>"));
        assert!(!snap.content_html.contains("Deep Systems"));
        assert!(!snap.content_html.contains("Icon Title"));

        let arxiv = Url::parse("https://arxiv.org/abs/2301.07041v2").unwrap();
        assert_eq!(UrlClassifier::classify(&arxiv), ContentType::Paper);
        assert_eq!(classify_url(&arxiv), ContentType::Paper);
        assert_eq!(
            resolve_pdf_url(&arxiv).unwrap().as_str(),
            "https://arxiv.org/pdf/2301.07041v2.pdf"
        );

        let pdf = Url::parse("https://example.com/papers/whitepaper.pdf").unwrap();
        assert_eq!(classify_url(&pdf), ContentType::Pdf);
        assert_eq!(resolve_pdf_url(&pdf).unwrap().as_str(), pdf.as_str());

        let yt = Url::parse("https://youtu.be/dQw4w9WgXcQ").unwrap();
        assert_eq!(classify_url(&yt), ContentType::Video);
        assert!(resolve_pdf_url(&yt).is_none());
    }

    #[test]
    fn test_safe_http_client_blocks_ssrf_targets() {
        assert!(SafeHttpClient::parse_url("https://example.com/feed.xml").is_ok());
        assert!(SafeHttpClient::parse_url("ftp://example.com/feed.xml").is_err());
        assert!(SafeHttpClient::parse_url("http://user:pass@example.com/feed.xml").is_err());
        assert!(SafeHttpClient::parse_url("http://example.com:6379/").is_err());
        assert!(SafeHttpClient::parse_url("http://localhost:8080/admin").is_err());
        assert!(SafeHttpClient::parse_url("http://localhost.:8080/admin").is_err());
        assert!(SafeHttpClient::parse_url("http://nas.local./").is_err());
        assert!(SafeHttpClient::parse_url("http://my-node.tail1234.ts.net/").is_err());
        assert!(SafeHttpClient::parse_url("http://singlelabel/").is_err());
        assert!(SafeHttpClient::parse_url("http://127.0.0.1:8080/").is_err());
        assert!(SafeHttpClient::parse_url("http://169.254.169.254/latest/meta-data").is_err());
        assert!(SafeHttpClient::parse_url("http://192.168.1.1/").is_err());
        assert!(SafeHttpClient::parse_url("http://100.100.100.100/").is_err());
        assert!(SafeHttpClient::parse_url("http://100.64.0.5:8080/").is_err());
        assert!(SafeHttpClient::parse_url("http://[::1]:8080/").is_err());
        assert!(SafeHttpClient::parse_url("http://[::ffff:127.0.0.1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[::ffff:0:127.0.0.1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[64:ff9b::127.0.0.1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[100::1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[2001:20::1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[fc00::1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[fe80::1]/").is_err());
        assert!(SafeHttpClient::parse_url("http://[2001:db8::1]/").is_err());
    }

    #[test]
    fn test_plain_text_excerpt_strips_html_decodes_entities_and_truncates() {
        let html = r#"<figure><img src="https://example.com/chart.png" alt="chart"></figure><p>We had some <strong>big drawdowns</strong> &amp; volatility &mdash; in the market.</p><p>Second paragraph here.</p>"#;
        let excerpt = HtmlSanitizer::plain_text_excerpt(html, 320);
        assert_eq!(
            excerpt,
            "We had some big drawdowns & volatility \u{2014} in the market. Second paragraph here."
        );
        assert!(!excerpt.contains('<'));
        assert!(!excerpt.contains("&amp;"));

        let truncated = HtmlSanitizer::plain_text_excerpt(html, 40);
        assert!(truncated.ends_with('…'));
        assert!(truncated.chars().count() <= 40);

        // Heavily indented HTML with > 600 whitespace characters before text should not truncate early
        let indented_html = format!(
            "<div>\n{}\n<p>{}\n<br/>Second line after break</p></div>",
            " ".repeat(600),
            "word ".repeat(50)
        );
        let indented_excerpt = HtmlSanitizer::plain_text_excerpt(&indented_html, 320);
        assert!(indented_excerpt.contains("Second line after break"));
        assert!(indented_excerpt.chars().count() > 250);

        let snap = HtmlSanitizer::extract_snapshot(
            "<html><body><p>First line<br>Second line</p></body></html>",
        );
        assert_eq!(snap.summary.as_deref(), Some("First line Second line"));
    }

    #[test]
    fn test_decode_xml_or_text_bytes_handles_utf16_bom_and_encoding_prolog() {
        let raw_xml = r#"<?xml version="1.0" encoding="utf-16"?><rss version="2.0"><channel><title>Petzold</title></channel></rss>"#;
        let mut utf16le = vec![0xFF, 0xFE];
        for unit in raw_xml.encode_utf16() {
            utf16le.extend_from_slice(&unit.to_le_bytes());
        }
        let decoded = SafeHttpClient::decode_xml_or_text_bytes(&utf16le);
        assert!(decoded.starts_with(r#"<?xml version="1.0" encoding="UTF-8"?>"#));
        let (feed, _) = crate::feeds::RssParser::parse(&decoded).unwrap();
        assert_eq!(feed.title, "Petzold");
    }

    #[test]
    fn test_extract_first_image_url_with_base_resolves_relative_and_skips_emojis_svg_and_small_icons()
     {
        let base = Url::parse("https://blog.example.com/posts/2026/predicate-logic/").unwrap();
        let html = r#"
            <p>Emoji <img src="https://s.w.org/images/core/emoji/17.0.2/72x72/21a9.png" alt="↩"> and
            smiley <img class="wp-smiley" src="https://blog.example.com/smile.png"> and
            tiny <img src="/icon.png" width="24" height="24"> and
            vector <img src="/diagram.svg"> and
            real <img src="img/quantifier-breakdown.png"></p>
        "#;
        let extracted = HtmlSanitizer::extract_first_image_url_with_base(html, Some(&base));
        assert_eq!(
            extracted.as_deref(),
            Some(
                "https://blog.example.com/posts/2026/predicate-logic/img/quantifier-breakdown.png"
            )
        );

        let root_rel = HtmlSanitizer::extract_first_image_url_with_base(
            r#"<img src="/static/hero.webp">"#,
            Some(&base),
        );
        assert_eq!(root_rel.as_deref(), Some("https://blog.example.com/static/hero.webp"));
    }
}
