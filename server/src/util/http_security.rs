use std::net::SocketAddr;

use axum::http::HeaderMap;

/// Trusts `X-Forwarded-Proto: https` ONLY when the immediate TCP peer is loopback
/// (e.g. local `tailscale serve` or local TLS reverse proxy), preventing direct LAN/HTTP
/// peers from spoofing `X-Forwarded-Proto: https` and setting `Secure` cookies or HSTS over HTTP.
/// Reads the last `X-Forwarded-Proto` header and its rightmost comma-separated token so
/// client-supplied leading entries cannot spoof the proxy-appended value.
#[must_use]
pub fn is_forwarded_https(peer_addr: Option<SocketAddr>, headers: &HeaderMap) -> bool {
    peer_addr.is_some_and(|addr| addr.ip().to_canonical().is_loopback())
        && headers
            .get_all("x-forwarded-proto")
            .iter()
            .next_back()
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.rsplit(',').next())
            .is_some_and(|proto| proto.trim().eq_ignore_ascii_case("https"))
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

    use axum::http::HeaderValue;

    use super::*;

    #[test]
    fn test_is_forwarded_https_requires_loopback_and_rightmost_https() {
        let mut headers = HeaderMap::new();
        headers.insert("x-forwarded-proto", HeaderValue::from_static("http, https"));

        let loopback_v4 = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 12345);
        let loopback_v6 = SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 12345);
        let mapped_v4 = SocketAddr::new(IpAddr::V6(Ipv4Addr::LOCALHOST.to_ipv6_mapped()), 12345);
        let lan_peer = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 1, 10)), 12345);

        assert!(is_forwarded_https(Some(loopback_v4), &headers));
        assert!(is_forwarded_https(Some(loopback_v6), &headers));
        assert!(is_forwarded_https(Some(mapped_v4), &headers));
        assert!(!is_forwarded_https(Some(lan_peer), &headers));
        assert!(!is_forwarded_https(None, &headers));

        let mut spoofed = HeaderMap::new();
        spoofed.insert("x-forwarded-proto", HeaderValue::from_static("https, http"));
        assert!(!is_forwarded_https(Some(loopback_v4), &spoofed));
    }
}
