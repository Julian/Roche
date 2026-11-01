use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};

use anyhow::Result;
use ip_network::IpNetwork;
use reqwest::dns::{Addrs, Name, Resolve, Resolving};
use url::{Host, Url};

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
        Self::validate_url(url)?;
        let mut response = self.client.get(url.clone()).send().await?;
        let response_status = response.status();
        if !response_status.is_success() {
            anyhow::bail!("Upstream server returned HTTP {response_status}");
        }
        if let Some(len) = response.content_length()
            && usize::try_from(len).unwrap_or(usize::MAX) > max_bytes
        {
            anyhow::bail!(
                "Response content-length ({len} bytes) exceeds limit ({max_bytes} bytes)"
            );
        }

        let mut buffer = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if buffer.len().saturating_add(chunk.len()) > max_bytes {
                anyhow::bail!("Response body exceeded maximum size of {max_bytes} bytes");
            }
            buffer.extend_from_slice(&chunk);
        }
        Ok(buffer)
    }

    pub async fn fetch_text(&self, url: &Url, max_bytes: usize) -> Result<String> {
        let bytes = self.fetch_bytes(url, max_bytes).await?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
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

impl HtmlSanitizer {
    #[must_use]
    pub fn clean(html: &str) -> String {
        ammonia::clean(html)
    }
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
