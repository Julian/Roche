use std::{borrow::Cow, net::IpAddr};

use anyhow::Result;
use ip_network::IpNetwork;
use thiserror::Error;
use url::{Host, Url};

/// Typed error returned when outbound URL validation blocks an SSRF or disallowed URL target.
#[derive(Debug, Clone, PartialEq, Eq, Error)]
#[error("{0}")]
pub struct SsrfBlockedError(pub String);

/// Parses and validates an external HTTP(S) [`Url`], rejecting SSRF targets.
pub fn parse_public_url(raw_url: &str) -> Result<Url> {
    let parsed = Url::parse(raw_url.trim())?;
    validate_public_url(&parsed)?;
    Ok(parsed)
}

/// Validates that a [`Url`] uses HTTP(S) on an allowed port and points to a public/global host.
pub fn validate_public_url(parsed: &Url) -> std::result::Result<(), SsrfBlockedError> {
    if !matches!(parsed.scheme(), "http" | "https") {
        return Err(SsrfBlockedError("URL scheme must be http or https".to_owned()));
    }
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return Err(SsrfBlockedError("URL must not contain embedded credentials".to_owned()));
    }
    if let Some(port) = parsed.port()
        && !matches!(port, 80 | 443 | 8080 | 8443)
    {
        return Err(SsrfBlockedError(format!("Disallowed URL port: {port}")));
    }

    let Some(host) = parsed.host() else {
        return Err(SsrfBlockedError("URL must include a valid host".to_owned()));
    };

    match host {
        Host::Domain(domain) => {
            let normalized = domain.trim_end_matches('.').to_ascii_lowercase();
            if normalized.is_empty() || !normalized.contains('.') {
                return Err(SsrfBlockedError(
                    "Single-label or empty domain names are not allowed".to_owned(),
                ));
            }
            if is_local_or_tailnet_domain(&normalized) {
                return Err(SsrfBlockedError(
                    "Local or Tailnet domain names are not allowed".to_owned(),
                ));
            }
        }
        Host::Ipv4(ipv4) => {
            if !is_global_ip(IpAddr::V4(ipv4)) {
                return Err(SsrfBlockedError(
                    "Private, loopback, or reserved IPv4 addresses are not allowed".to_owned(),
                ));
            }
        }
        Host::Ipv6(ipv6) => {
            if !is_global_ip(IpAddr::V6(ipv6)) {
                return Err(SsrfBlockedError(
                    "Private, loopback, or reserved IPv6 addresses are not allowed".to_owned(),
                ));
            }
        }
    }

    Ok(())
}

/// Ensures a link string is an external `http://` or `https://` URL pointing to a public/global host
/// with no embedded credentials (suitable for rendering in browser `href` / `src` attributes).
#[must_use]
pub fn sanitize_public_http_href(raw: &str) -> Option<&str> {
    let trimmed = raw.trim();
    if !trimmed.starts_with("https://") && !trimmed.starts_with("http://") {
        return None;
    }
    let parsed = Url::parse(trimmed).ok()?;
    if !matches!(parsed.scheme(), "http" | "https")
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return None;
    }
    match parsed.host()? {
        Host::Domain(domain) => {
            let lower = domain.trim_end_matches('.').to_ascii_lowercase();
            if lower.is_empty() || !lower.contains('.') || is_local_or_tailnet_domain(&lower) {
                return None;
            }
        }
        Host::Ipv4(v4) => {
            if !is_global_ip(IpAddr::V4(v4)) {
                return None;
            }
        }
        Host::Ipv6(v6) => {
            if !is_global_ip(IpAddr::V6(v6)) {
                return None;
            }
        }
    }
    Some(trimmed)
}

/// Returns `true` if a lowercased, trailing-dot-stripped domain name belongs to a
/// local, private, or Tailnet namespace.
#[must_use]
pub fn is_local_or_tailnet_domain(normalized_domain: &str) -> bool {
    normalized_domain == "home.arpa"
        || normalized_domain.ends_with(".home.arpa")
        || normalized_domain.ends_with(".ts.net")
        || normalized_domain
            .rsplit('.')
            .next()
            .is_some_and(|tld| matches!(tld, "localhost" | "local" | "internal" | "home" | "lan"))
}

/// Returns `true` if `ip` is globally routable (blocking loopback, private, link-local,
/// CGNAT, ULA, NAT64, Teredo, 6to4, documentation, and IPv4-mapped/compatible private targets).
#[must_use]
pub fn is_global_ip(ip: IpAddr) -> bool {
    if ip.is_multicast() {
        return false;
    }
    if let IpAddr::V6(v6) = ip {
        if let Some(v4) = v6.to_ipv4_mapped() {
            return is_global_ip(IpAddr::V4(v4));
        }
        let s = v6.segments();
        // Deprecated IPv4-compatible (::/96, also covers :: and ::1)
        // NAT64 Well-Known Prefix (64:ff9b::/96) and Local-Use NAT64 (64:ff9b:1::/48)
        // SIIT IPv4-translated (::ffff:0:0/96)
        // Discard-Only (100::/64)
        // Teredo (2001:0000::/32)
        // Benchmarking (2001:0002::/48)
        // ORCHIDv2 (2001:20::/28)
        // Documentation (2001:db8::/32)
        // 6to4 (2002::/16) — can embed private/loopback IPv4 addresses
        // ULA (fc00::/7), link-local (fe80::/10), deprecated site-local (fec0::/10)
        if s[0..6] == [0, 0, 0, 0, 0, 0]
            || (s[0] == 0x0064 && s[1] == 0xff9b)
            || s[0..6] == [0, 0, 0, 0, 0xffff, 0]
            || (s[0] == 0x0100 && s[1] == 0 && s[2] == 0 && s[3] == 0)
            || (s[0] == 0x2001 && s[1] == 0x0000)
            || (s[0] == 0x2001 && s[1] == 0x0002 && s[2] == 0)
            || (s[0] == 0x2001 && (s[1] & 0xfff0) == 0x0020)
            || (s[0] == 0x2001 && s[1] == 0x0db8)
            || s[0] == 0x2002
            || (s[0] & 0xfe00) == 0xfc00
            || (s[0] & 0xffc0) == 0xfe80
            || (s[0] & 0xffc0) == 0xfec0
        {
            return false;
        }
    }
    IpNetwork::from(ip).is_global()
}

/// Decodes `%XX` percent-encoded UTF-8 byte sequences lossily while leaving invalid escapes literal.
#[must_use]
pub fn decode_percent_utf8(input: &str) -> String {
    if !input.contains('%') {
        return input.to_owned();
    }
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let (Some(hi), Some(lo)) = (hex_nibble(bytes[i + 1]), hex_nibble(bytes[i + 2]))
        {
            out.push((hi << 4) | lo);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

const fn hex_nibble(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

/// Wraps an unbracketed IPv6 literal in `[...]` for `host:port` formatting.
#[must_use]
pub fn bracket_ipv6_host(host: &str) -> Cow<'_, str> {
    if host.contains(':') && !host.starts_with('[') {
        Cow::Owned(format!("[{host}]"))
    } else {
        Cow::Borrowed(host)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_public_url_blocks_ssrf_targets() {
        assert!(parse_public_url("https://example.com/feed.xml").is_ok());
        assert!(parse_public_url("ftp://example.com/feed.xml").is_err());
        assert!(parse_public_url("http://user:pass@example.com/feed.xml").is_err());
        assert!(parse_public_url("http://example.com:6379/").is_err());
        assert!(parse_public_url("http://localhost:8080/admin").is_err());
        assert!(parse_public_url("http://localhost.:8080/admin").is_err());
        assert!(parse_public_url("http://nas.local./").is_err());
        assert!(parse_public_url("http://home.arpa/").is_err());
        assert!(parse_public_url("http://router.home.arpa/").is_err());
        assert!(parse_public_url("http://router.home.arpa./").is_err());
        assert!(parse_public_url("http://my-node.tail1234.ts.net/").is_err());
        assert!(parse_public_url("http://singlelabel/").is_err());
        assert!(parse_public_url("http://127.0.0.1:8080/").is_err());
        assert!(parse_public_url("http://169.254.169.254/latest/meta-data").is_err());
        assert!(parse_public_url("http://192.168.1.1/").is_err());
        assert!(parse_public_url("http://192.0.2.1/").is_err());
        assert!(parse_public_url("http://198.18.0.1/").is_err());
        assert!(parse_public_url("http://100.100.100.100/").is_err());
        assert!(parse_public_url("http://100.64.0.5:8080/").is_err());
        assert!(parse_public_url("http://[::1]:8080/").is_err());
        assert!(parse_public_url("http://[::8.8.8.8]/").is_err());
        assert!(parse_public_url("http://[::ffff:127.0.0.1]/").is_err());
        assert!(parse_public_url("http://[::ffff:0:127.0.0.1]/").is_err());
        assert!(parse_public_url("http://[64:ff9b::127.0.0.1]/").is_err());
        assert!(parse_public_url("http://[100::1]/").is_err());
        assert!(parse_public_url("http://[2001:0000:4136:e378:8000:63bf:3fff:fdd2]/").is_err());
        assert!(parse_public_url("http://[2001:20::1]/").is_err());
        assert!(parse_public_url("http://[fc00::1]/").is_err());
        assert!(parse_public_url("http://[fe80::1]/").is_err());
        assert!(parse_public_url("http://[fec0::1]/").is_err());
        assert!(parse_public_url("http://[2001:db8::1]/").is_err());
        assert!(parse_public_url("http://[2002:7f00:0001::]/").is_err());
        assert!(parse_public_url("http://[2002:c0a8:0101::1]/").is_err());
    }

    #[test]
    fn test_decode_percent_utf8_and_bracket_ipv6_host() {
        assert_eq!(decode_percent_utf8("Bj%C3%B6rk"), "Björk");
        assert_eq!(decode_percent_utf8("plain"), "plain");
        assert_eq!(decode_percent_utf8("100%20pure%ZZ"), "100 pure%ZZ");

        assert_eq!(bracket_ipv6_host("127.0.0.1"), "127.0.0.1");
        assert_eq!(bracket_ipv6_host("::1"), "[::1]");
        assert_eq!(bracket_ipv6_host("[::1]"), "[::1]");
    }
}
