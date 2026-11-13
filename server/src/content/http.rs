use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};

use anyhow::Result;
use ip_network::IpNetwork;
use reqwest::dns::{Addrs, Name, Resolve, Resolving};
use url::{Host, Url};

use super::classify::UrlClassifier;

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum FetchValidation {
    Raw,
    HtmlText,
    PdfMagic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Utf16Endian {
    Little,
    Big,
}

impl SafeHttpClient {
    pub fn new(timeout: Duration) -> Result<Self> {
        let mut default_headers = reqwest::header::HeaderMap::new();
        default_headers.insert(
            reqwest::header::ACCEPT,
            reqwest::header::HeaderValue::from_static(
                "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, application/xhtml+xml, */*;q=0.8",
            ),
        );
        default_headers.insert(
            reqwest::header::ACCEPT_LANGUAGE,
            reqwest::header::HeaderValue::from_static("en-US,en;q=0.9"),
        );

        let client = reqwest::Client::builder()
            .timeout(timeout)
            .dns_resolver(Arc::new(SafeDnsResolver))
            .redirect(Self::redirect_policy())
            .default_headers(default_headers)
            .user_agent("Mozilla/5.0 (compatible; Roche/0.1; +https://github.com/julian/roche)")
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
        self.fetch_bytes_with_validation(url, max_bytes, FetchValidation::Raw).await
    }

    pub async fn fetch_pdf_bytes(&self, url: &Url, max_bytes: usize) -> Result<Vec<u8>> {
        self.fetch_bytes_with_validation(url, max_bytes, FetchValidation::PdfMagic).await
    }

    pub async fn fetch_text(&self, url: &Url, max_bytes: usize) -> Result<String> {
        let bytes = self.fetch_bytes(url, max_bytes).await?;
        Ok(Self::decode_xml_or_text_bytes(&bytes))
    }

    #[must_use]
    pub fn decode_xml_or_text_bytes(bytes: &[u8]) -> String {
        let decoded = match bytes {
            [0xFF, 0xFE, rest @ ..] => Self::decode_utf16_slice(rest, Utf16Endian::Little),
            [0xFE, 0xFF, rest @ ..] => Self::decode_utf16_slice(rest, Utf16Endian::Big),
            [b0, 0, b2, 0, ..] if *b0 != 0 && *b2 != 0 => {
                Self::decode_utf16_slice(bytes, Utf16Endian::Little)
            }
            [0, b1, 0, b3, ..] if *b1 != 0 && *b3 != 0 => {
                Self::decode_utf16_slice(bytes, Utf16Endian::Big)
            }
            _ => String::from_utf8_lossy(bytes).into_owned(),
        };

        let trimmed = decoded.trim_start_matches('\u{FEFF}').trim_start();
        if let Some(prolog_end) = trimmed.find("?>")
            && trimmed.starts_with("<?xml")
        {
            let prolog = &trimmed[..prolog_end + 2];
            if prolog.to_ascii_lowercase().contains("utf-16") {
                let normalized_prolog = Self::normalize_xml_prolog_encoding(prolog);
                return format!("{normalized_prolog}{}", &trimmed[prolog_end + 2..]);
            }
        }
        trimmed.to_owned()
    }

    fn decode_utf16_slice(bytes: &[u8], endian: Utf16Endian) -> String {
        let units = bytes.as_chunks::<2>().0.iter().map(|pair| match endian {
            Utf16Endian::Little => u16::from_le_bytes(*pair),
            Utf16Endian::Big => u16::from_be_bytes(*pair),
        });
        char::decode_utf16(units).map(|r| r.unwrap_or(char::REPLACEMENT_CHARACTER)).collect()
    }

    fn normalize_xml_prolog_encoding(prolog: &str) -> String {
        let lower = prolog.to_ascii_lowercase();
        if let Some(enc_idx) = lower.find("encoding")
            && let Some(quote_offset) = prolog[enc_idx..].find(['"', '\''])
        {
            let quote_pos = enc_idx + quote_offset;
            let quote_char = prolog.as_bytes()[quote_pos] as char;
            if let Some(end_offset) = prolog[quote_pos + 1..].find(quote_char) {
                let end_pos = quote_pos + 1 + end_offset;
                return format!("{}UTF-8{}", &prolog[..=quote_pos], &prolog[end_pos..]);
            }
        }
        prolog.to_owned()
    }

    pub async fn fetch_html_text(&self, url: &Url, max_bytes: usize) -> Result<String> {
        let bytes =
            self.fetch_bytes_with_validation(url, max_bytes, FetchValidation::HtmlText).await?;
        if UrlClassifier::is_valid_pdf_bytes(&bytes) || bytes.contains(&0) {
            anyhow::bail!("Upstream response from {url} is binary, not HTML");
        }
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    async fn fetch_bytes_with_validation(
        &self,
        url: &Url,
        max_bytes: usize,
        validation: FetchValidation,
    ) -> Result<Vec<u8>> {
        Self::validate_url(url)?;
        let mut response = self.client.get(url.clone()).send().await?;
        let response_status = response.status();
        if !response_status.is_success() {
            anyhow::bail!("Upstream server returned HTTP {response_status}");
        }
        if validation == FetchValidation::HtmlText
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

        let require_pdf_magic = validation == FetchValidation::PdfMagic;
        let mut buffer = Vec::new();
        let mut checked_pdf_magic = false;
        while let Some(chunk) = response.chunk().await? {
            if buffer.len().saturating_add(chunk.len()) > max_bytes {
                anyhow::bail!("Response body exceeded maximum size of {max_bytes} bytes");
            }
            buffer.extend_from_slice(&chunk);
            if require_pdf_magic && !checked_pdf_magic && buffer.len() >= 5 {
                if !UrlClassifier::is_valid_pdf_bytes(&buffer) {
                    anyhow::bail!(
                        "Upstream response from {url} does not start with %PDF- magic header"
                    );
                }
                checked_pdf_magic = true;
            }
        }
        if require_pdf_magic && !UrlClassifier::is_valid_pdf_bytes(&buffer) {
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
