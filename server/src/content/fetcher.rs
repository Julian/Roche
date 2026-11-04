use std::{
    collections::HashSet,
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};

use anyhow::Result;
use ip_network::IpNetwork;
use quick_xml::{Reader, XmlVersion, escape::unescape, events::Event};
use reqwest::dns::{Addrs, Name, Resolve, Resolving};
use url::{Host, Url};

use crate::{
    db::ContentType,
    feeds::{ArxivParser, YouTubeParser},
};

/// Custom DNS resolver for `reqwest` that validates every resolved IP address against
/// [`SafeHttpClient::is_global_ip`] at socket connection time.
///
/// This eliminates both DNS-rebinding TOCTOU windows and redirect-target DNS-rebinding bypasses.
#[derive(Debug, Default, Clone, Copy)]
pub struct SafeDnsResolver;

impl Resolve for SafeDnsResolver {
    fn resolve(&self, name: Name) -> Resolving {
        Box::pin(async move {
            let host = name.as_str().to_owned();
            let addrs: Vec<SocketAddr> =
                tokio::net::lookup_host((host.as_str(), 0)).await?.collect();
            if addrs.is_empty() {
                return Err(Box::new(std::io::Error::new(
                    std::io::ErrorKind::NotFound,
                    format!("Host '{host}' did not resolve to any IP address"),
                )) as Box<dyn std::error::Error + Send + Sync>);
            }
            for addr in &addrs {
                if !SafeHttpClient::is_global_ip(addr.ip()) {
                    return Err(Box::new(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        format!("Host '{host}' resolved to disallowed IP address {}", addr.ip()),
                    )) as Box<dyn std::error::Error + Send + Sync>);
                }
            }
            let iter: Addrs = Box::new(addrs.into_iter());
            Ok(iter)
        })
    }
}

/// Shared SSRF-hardened HTTP client enforcing [`SafeDnsResolver`], redirect validation,
/// timeouts, and response body size limits.
#[derive(Debug, Clone)]
pub struct SafeHttpClient {
    client: reqwest::Client,
}

impl SafeHttpClient {
    pub fn new(timeout: Duration) -> Result<Self> {
        let client = reqwest::Client::builder()
            .timeout(timeout)
            .dns_resolver(Arc::new(SafeDnsResolver))
            .redirect(Self::redirect_policy())
            .user_agent("Roche/0.1")
            .build()?;
        Ok(Self { client })
    }

    /// Parses and validates an external HTTP(S) [`Url`], rejecting SSRF targets.
    pub fn parse_url(raw_url: &str) -> Result<Url> {
        let parsed = Url::parse(raw_url.trim())?;
        Self::validate_url(&parsed)?;
        Ok(parsed)
    }

    /// Validates that a [`Url`] uses HTTP(S) on an allowed port and points to a public/global host.
    pub fn validate_url(parsed: &Url) -> Result<()> {
        if !matches!(parsed.scheme(), "http" | "https") {
            anyhow::bail!("URL scheme must be http or https");
        }
        if !parsed.username().is_empty() || parsed.password().is_some() {
            anyhow::bail!("URL must not contain embedded credentials");
        }
        if let Some(port) = parsed.port()
            && !matches!(port, 80 | 443 | 8080 | 8443)
        {
            anyhow::bail!("Disallowed URL port: {port}");
        }

        let Some(host) = parsed.host() else {
            anyhow::bail!("URL must include a valid host");
        };

        match host {
            Host::Domain(domain) => {
                let normalized = domain.trim_end_matches('.').to_ascii_lowercase();
                if normalized.is_empty() || !normalized.contains('.') {
                    anyhow::bail!("Single-label or empty domain names are not allowed");
                }
                if normalized.rsplit('.').next().is_some_and(|tld| {
                    matches!(tld, "localhost" | "local" | "internal" | "home" | "lan")
                }) || normalized.ends_with(".ts.net")
                {
                    anyhow::bail!("Local or Tailnet domain names are not allowed");
                }
            }
            Host::Ipv4(ipv4) => {
                if !Self::is_global_ip(IpAddr::V4(ipv4)) {
                    anyhow::bail!("Private, loopback, or reserved IPv4 addresses are not allowed");
                }
            }
            Host::Ipv6(ipv6) => {
                if !Self::is_global_ip(IpAddr::V6(ipv6)) {
                    anyhow::bail!("Private, loopback, or reserved IPv6 addresses are not allowed");
                }
            }
        }

        Ok(())
    }

    #[must_use]
    pub fn is_global_ip(ip: IpAddr) -> bool {
        if let IpAddr::V6(v6) = ip {
            if let Some(v4) = v6.to_ipv4() {
                return IpNetwork::from(IpAddr::V4(v4)).is_global();
            }
            let s = v6.segments();
            // NAT64 Well-Known Prefix (64:ff9b::/96) and Local-Use NAT64 (64:ff9b:1::/48)
            // SIIT IPv4-translated (::ffff:0:0/96)
            // Discard-Only (100::/64)
            // ORCHIDv2 (2001:20::/28)
            if (s[0] == 0x0064 && s[1] == 0xff9b)
                || (s[0] == 0 && s[1] == 0 && s[2] == 0 && s[3] == 0 && s[4] == 0xffff && s[5] == 0)
                || (s[0] == 0x0100 && s[1] == 0 && s[2] == 0 && s[3] == 0)
                || (s[0] == 0x2001 && (s[1] & 0xfff0) == 0x0020)
            {
                return false;
            }
        }
        IpNetwork::from(ip).is_global()
    }

    pub async fn fetch_bytes(&self, url: &Url, max_bytes: usize) -> Result<Vec<u8>> {
        self.fetch_bytes_internal(url, max_bytes, false, false).await
    }

    pub async fn fetch_pdf_bytes(&self, url: &Url, max_bytes: usize) -> Result<Vec<u8>> {
        self.fetch_bytes_internal(url, max_bytes, false, true).await
    }

    pub async fn fetch_text(&self, url: &Url, max_bytes: usize) -> Result<String> {
        let bytes = self.fetch_bytes(url, max_bytes).await?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    pub async fn fetch_html_text(&self, url: &Url, max_bytes: usize) -> Result<String> {
        let bytes = self.fetch_bytes_internal(url, max_bytes, true, false).await?;
        if is_valid_pdf_bytes(&bytes) || bytes.contains(&0) {
            anyhow::bail!("Upstream response from {url} is binary, not HTML");
        }
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    async fn fetch_bytes_internal(
        &self,
        url: &Url,
        max_bytes: usize,
        require_html_content_type: bool,
        require_pdf_magic: bool,
    ) -> Result<Vec<u8>> {
        Self::validate_url(url)?;
        let mut response = self.client.get(url.clone()).send().await?;
        let response_status = response.status();
        if !response_status.is_success() {
            anyhow::bail!("Upstream server returned HTTP {response_status}");
        }
        if require_html_content_type
            && let Some(ct_header) = response.headers().get(reqwest::header::CONTENT_TYPE)
            && let Ok(ct_str) = ct_header.to_str()
        {
            let mime = ct_str.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
            if !mime.is_empty()
                && !matches!(mime.as_str(), "text/html" | "application/xhtml+xml" | "text/plain")
            {
                anyhow::bail!("Disallowed HTML Content-Type '{mime}' from {url}");
            }
        }
        if let Some(len) = response.content_length()
            && usize::try_from(len).unwrap_or(usize::MAX) > max_bytes
        {
            anyhow::bail!(
                "Response content-length ({len} bytes) exceeds limit ({max_bytes} bytes)"
            );
        }

        let mut buffer = Vec::new();
        let mut checked_pdf_magic = false;
        while let Some(chunk) = response.chunk().await? {
            if buffer.len().saturating_add(chunk.len()) > max_bytes {
                anyhow::bail!("Response body exceeded maximum size of {max_bytes} bytes");
            }
            buffer.extend_from_slice(&chunk);
            if require_pdf_magic && !checked_pdf_magic && buffer.len() >= 5 {
                if !is_valid_pdf_bytes(&buffer) {
                    anyhow::bail!(
                        "Upstream response from {url} does not start with %PDF- magic header"
                    );
                }
                checked_pdf_magic = true;
            }
        }
        if require_pdf_magic && !is_valid_pdf_bytes(&buffer) {
            anyhow::bail!("Upstream response from {url} does not start with %PDF- magic header");
        }
        Ok(buffer)
    }

    fn redirect_policy() -> reqwest::redirect::Policy {
        reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 5 {
                return attempt.error("Too many redirects");
            }
            if let Err(err) = Self::validate_url(attempt.url()) {
                return attempt.error(err);
            }
            attempt.follow()
        })
    }
}

pub struct HtmlSanitizer;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtractedSnapshot {
    pub title: Option<String>,
    pub summary: Option<String>,
    pub content_html: String,
}

impl HtmlSanitizer {
    #[must_use]
    pub fn clean(html: &str) -> String {
        let mut builder = ammonia::Builder::default();
        builder.add_clean_content_tags(["head", "title", "noscript", "template", "svg", "math"]);
        builder.clean(html).to_string()
    }

    /// Extracts `<title>`, `<meta name="description">` / `<meta property="og:description">`,
    /// and sanitized `content_html` from a raw HTML5 document using `ammonia` (`html5ever`) + `quick_xml`.
    #[must_use]
    pub fn extract_snapshot(raw_html: &str) -> ExtractedSnapshot {
        // Normalize <title> and <meta> tags through html5ever (via ammonia) so inline <script>if (a < b)</script>,
        // <svg><title>, unquoted attributes, and bare ampersands are parsed per HTML5 spec.
        let mut meta_builder = ammonia::Builder::new();
        meta_builder
            .tags(HashSet::from(["title", "meta"]))
            .clean_content_tags(HashSet::from([
                "script", "style", "svg", "noscript", "template", "math",
            ]))
            .add_tag_attributes("meta", &["name", "property", "content"]);
        let normalized_meta_html = meta_builder.clean(raw_html).to_string();

        let (title, meta_desc) = Self::parse_title_and_meta(&normalized_meta_html);
        let content_html = Self::clean(raw_html);
        let summary = meta_desc
            .or_else(|| Self::extract_first_paragraph(&content_html))
            .filter(|s| !s.is_empty());

        ExtractedSnapshot { title, summary, content_html }
    }

    fn parse_title_and_meta(normalized_html: &str) -> (Option<String>, Option<String>) {
        let mut reader = Reader::from_str(normalized_html);
        reader.config_mut().check_end_names = false;

        let mut title: Option<String> = None;
        let mut og_title: Option<String> = None;
        let mut meta_desc: Option<String> = None;
        let mut in_title = false;
        let mut current_buf = String::new();

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) => {
                    let name = tag.name();
                    let name_bytes = name.as_ref();
                    if name_bytes.eq_ignore_ascii_case("title") && title.is_none() {
                        in_title = true;
                        current_buf.clear();
                    } else if name_bytes.eq_ignore_ascii_case("meta") {
                        Self::inspect_meta_tag(&tag, &mut og_title, &mut meta_desc);
                    }
                }
                Ok(Event::Empty(tag)) if tag.name().as_ref().eq_ignore_ascii_case("meta") => {
                    Self::inspect_meta_tag(&tag, &mut og_title, &mut meta_desc);
                }
                Ok(Event::Text(text)) if in_title => {
                    let decoded =
                        unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into);
                    current_buf.push_str(&decoded);
                }
                Ok(Event::GeneralRef(entity)) if in_title => {
                    let raw = format!("&{};", entity.as_ref());
                    if let Ok(decoded) = unescape(&raw) {
                        current_buf.push_str(&decoded);
                    }
                }
                Ok(Event::End(tag))
                    if in_title && tag.name().as_ref().eq_ignore_ascii_case("title") =>
                {
                    in_title = false;
                    let trimmed = current_buf.trim();
                    if !trimmed.is_empty() {
                        title = Some(trimmed.to_owned());
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        (title.or(og_title), meta_desc)
    }

    fn extract_first_paragraph(clean_body_html: &str) -> Option<String> {
        let mut reader = Reader::from_str(clean_body_html);
        reader.config_mut().check_end_names = false;

        let mut in_p = false;
        let mut current_buf = String::new();

        loop {
            match reader.read_event() {
                Ok(Event::Start(tag)) if tag.name().as_ref().eq_ignore_ascii_case("p") => {
                    if !in_p {
                        in_p = true;
                        current_buf.clear();
                    }
                }
                Ok(Event::Text(text)) if in_p => {
                    let decoded =
                        unescape(text.as_ref()).map_or_else(|_| text.to_string(), Into::into);
                    current_buf.push_str(&decoded);
                }
                Ok(Event::GeneralRef(entity)) if in_p => {
                    let raw = format!("&{};", entity.as_ref());
                    if let Ok(decoded) = unescape(&raw) {
                        current_buf.push_str(&decoded);
                    }
                }
                Ok(Event::End(tag)) if in_p && tag.name().as_ref().eq_ignore_ascii_case("p") => {
                    in_p = false;
                    let trimmed = current_buf.trim();
                    if !trimmed.is_empty() {
                        return Some(trimmed.to_owned());
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
        }

        None
    }

    fn inspect_meta_tag(
        tag: &quick_xml::events::BytesStart<'_>,
        og_title: &mut Option<String>,
        meta_desc: &mut Option<String>,
    ) {
        let mut attr_name: Option<String> = None;
        let mut attr_content: Option<String> = None;

        for attr in tag.attributes().with_checks(false).flatten() {
            let key = attr.key.as_ref();
            let val = attr
                .normalized_value(XmlVersion::default())
                .map_or_else(|_| attr.value.as_ref().to_owned(), Into::into);
            if key.eq_ignore_ascii_case("name") || key.eq_ignore_ascii_case("property") {
                attr_name = Some(val);
            } else if key.eq_ignore_ascii_case("content") {
                attr_content = Some(val);
            }
        }

        let Some(name) = attr_name else { return };
        let Some(content) = attr_content.map(|c| c.trim().to_owned()).filter(|c| !c.is_empty())
        else {
            return;
        };

        if meta_desc.is_none()
            && (name.eq_ignore_ascii_case("description")
                || name.eq_ignore_ascii_case("og:description"))
        {
            *meta_desc = Some(content);
        } else if og_title.is_none() && name.eq_ignore_ascii_case("og:title") {
            *og_title = Some(content);
        }
    }
}

#[must_use]
pub fn is_direct_pdf_url(url: &Url) -> bool {
    let raw_path = url.path();
    std::path::Path::new(raw_path).extension().is_some_and(|ext| ext.eq_ignore_ascii_case("pdf"))
        || raw_path.to_ascii_lowercase().ends_with("/pdf")
}

#[must_use]
pub fn classify_url(url: &Url) -> ContentType {
    if ArxivParser::pdf_url_for_paper(url).is_some() {
        ContentType::Paper
    } else if YouTubeParser::extract_video_id(url).is_some() {
        ContentType::Video
    } else if is_direct_pdf_url(url) {
        ContentType::Pdf
    } else {
        ContentType::Article
    }
}

#[must_use]
pub fn resolve_pdf_url(url: &Url) -> Option<Url> {
    ArxivParser::pdf_url_for_paper(url).or_else(|| is_direct_pdf_url(url).then(|| url.clone()))
}

#[must_use]
pub fn is_valid_pdf_bytes(bytes: &[u8]) -> bool {
    bytes.starts_with(b"%PDF-")
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

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
}
