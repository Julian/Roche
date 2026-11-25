pub use roche_core::url_guard::{
    SsrfBlockedError, bracket_ipv6_host, decode_percent_utf8, is_global_ip, parse_public_url,
    sanitize_public_http_href, validate_public_url,
};

/// Sanitizes an upstream fetch/parse error before returning it to HTTP clients or persisting it
/// in `feeds.last_error`, preventing internal DNS IP oracle leaks from `SafeDnsResolver` or
/// `reqwest` socket errors while leaving full details in server `tracing` logs.
#[must_use]
pub fn sanitize_upstream_error(err: &anyhow::Error) -> String {
    for cause in err.chain() {
        if let Some(ssrf) = cause.downcast_ref::<SsrfBlockedError>() {
            return ssrf.0.clone();
        }
        if let Some(io_err) = cause.downcast_ref::<std::io::Error>()
            && io_err.kind() == std::io::ErrorKind::PermissionDenied
        {
            return "Upstream host resolved to a disallowed network address".to_owned();
        }
        if let Some(req_err) = cause.downcast_ref::<reqwest::Error>() {
            if req_err.is_timeout() {
                return "Upstream request timed out".to_owned();
            }
            if let Some(status) = req_err.status() {
                return format!("Upstream server returned HTTP {}", status.as_u16());
            }
            if req_err.is_redirect() {
                return "Upstream redirect blocked or exceeded redirect limit".to_owned();
            }
            if req_err.is_connect() {
                return "Unable to connect to upstream host".to_owned();
            }
            return "Upstream HTTP request failed".to_owned();
        }
    }
    let rendered = err.to_string();
    if rendered.contains("disallowed IP address") {
        return "Upstream host resolved to a disallowed network address".to_owned();
    }
    rendered
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sanitize_upstream_error_scrubs_resolved_internal_ips() {
        let io_err = std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "Host 'rebind.example' resolved to disallowed IP address 100.64.0.42",
        );
        let err = anyhow::Error::new(io_err).context("GET https://rebind.example/feed");
        let safe = sanitize_upstream_error(&err);
        assert!(!safe.contains("100.64.0.42"));
        assert!(safe.contains("disallowed network address"));
    }
}
