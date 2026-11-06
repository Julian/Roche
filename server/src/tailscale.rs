use std::{collections::BTreeMap, net::IpAddr, path::Path, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use tokio::{process::Command, sync::Semaphore};
use url::Url;

use crate::config::TailscaleConfig;

const STATUS_TIMEOUT: Duration = Duration::from_secs(3);
const SERVE_TIMEOUT: Duration = Duration::from_secs(5);
const PROBE_TIMEOUT: Duration = Duration::from_millis(800);
const MAX_CONCURRENT_PROBES: usize = 16;
const MAX_HEALTH_BYTES: usize = 16 * 1024;
const MACOS_APP_TAILSCALE_BIN: &str = "/Applications/Tailscale.app/Contents/MacOS/Tailscale";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NodeIdentity {
    pub hostname: String,
    pub dns_name: Option<String>,
    pub magic_dns_suffix: Option<String>,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub service_name: Option<String>,
}

impl NodeIdentity {
    /// Returns the ordered list of reachable Tailscale URLs for this Roche server.
    #[must_use]
    pub fn candidate_urls(&self, port: u16) -> Vec<String> {
        let mut urls = Vec::new();

        if let (Some(svc), Some(suffix)) = (&self.service_name, &self.magic_dns_suffix) {
            let bare_svc = svc.strip_prefix("svc:").unwrap_or(svc);
            if is_valid_dns_label(bare_svc) && is_valid_dns_name(suffix) {
                push_unique(&mut urls, format!("https://{bare_svc}.{suffix}"));
            }
        }

        if let Some(dns) = &self.dns_name
            && is_valid_dns_name(dns)
        {
            push_unique(&mut urls, format!("http://{dns}:{port}"));
        }

        let short = self.hostname.trim().to_ascii_lowercase();
        if is_valid_dns_label(&short) {
            push_unique(&mut urls, format!("http://{short}:{port}"));
        }

        if let Some(ip4) = &self.ipv4
            && ip4.parse::<std::net::Ipv4Addr>().is_ok()
        {
            push_unique(&mut urls, format!("http://{ip4}:{port}"));
        }

        urls
    }

    #[must_use]
    pub fn canonical_url(&self, port: u16) -> Option<String> {
        self.candidate_urls(port).into_iter().next()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DiscoveredServer {
    pub url: String,
    pub canonical_url: Option<String>,
    pub version: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawStatusResponse {
    #[serde(rename = "BackendState", default)]
    backend_state: String,
    #[serde(rename = "MagicDNSSuffix", default)]
    magic_dns_suffix: String,
    #[serde(rename = "Self")]
    self_node: Option<RawNode>,
    #[serde(rename = "Peer", default)]
    peers: BTreeMap<String, RawNode>,
}

#[derive(Debug, Clone, Deserialize)]
struct RawNode {
    #[serde(rename = "HostName", default)]
    host_name: String,
    #[serde(rename = "DNSName", default)]
    dns_name: String,
    #[serde(rename = "TailscaleIPs", default)]
    tailscale_ips: Vec<String>,
    #[serde(rename = "Online", default)]
    online: Option<bool>,
}

#[derive(Debug, Deserialize)]
struct HealthProbeResponse {
    status: String,
    #[serde(default)]
    service: Option<String>,
    #[serde(default)]
    version: Option<String>,
    #[serde(default)]
    canonical_url: Option<String>,
}

#[must_use]
pub fn normalize_service_name(raw: &str) -> String {
    let trimmed = raw.trim().to_ascii_lowercase();
    if trimmed.is_empty() {
        return String::new();
    }
    if trimmed.starts_with("svc:") { trimmed } else { format!("svc:{trimmed}") }
}

#[must_use]
pub fn is_valid_service_name(svc: &str) -> bool {
    svc.strip_prefix("svc:").is_some_and(is_valid_dns_label)
}

#[must_use]
pub fn is_valid_dns_label(label: &str) -> bool {
    !label.is_empty()
        && label.len() <= 63
        && !label.starts_with('-')
        && !label.ends_with('-')
        && label.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

#[must_use]
pub fn is_valid_dns_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 253 && name.split('.').all(is_valid_dns_label)
}

/// Validates a `canonical_url` advertised by a probed `/health` endpoint so a rogue peer
/// cannot redirect clients to an arbitrary external host or inject paths/credentials.
#[must_use]
pub fn validate_canonical_url(raw_canonical: &str, probed_base_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_canonical.trim()).ok()?;
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return None;
    }
    if !parsed.username().is_empty() || parsed.password().is_some() {
        return None;
    }
    if parsed.path() != "/" && !parsed.path().is_empty() {
        return None;
    }
    if parsed.query().is_some() || parsed.fragment().is_some() {
        return None;
    }
    let canonical_host = parsed.host_str()?.to_ascii_lowercase();
    let probed = Url::parse(probed_base_url.trim()).ok()?;
    let probed_host = probed.host_str()?.to_ascii_lowercase();

    if !is_trusted_canonical_host(&canonical_host, &probed_host) {
        return None;
    }

    let port_suffix = parsed.port().map_or_else(String::new, |p| format!(":{p}"));
    Some(format!("{}://{canonical_host}{port_suffix}", parsed.scheme()))
}

fn is_trusted_canonical_host(canonical_host: &str, probed_host: &str) -> bool {
    if canonical_host == probed_host {
        return true;
    }
    if !is_valid_dns_name(canonical_host) || !canonical_host.ends_with(".ts.net") {
        return false;
    }
    if probed_host == "roche"
        || probed_host == "127.0.0.1"
        || probed_host == "localhost"
        || is_tailscale_cgnat_ipv4(probed_host)
    {
        return true;
    }
    if let (Some(c_suffix), Some(p_suffix)) =
        (extract_ts_net_suffix(canonical_host), extract_ts_net_suffix(probed_host))
    {
        return c_suffix == p_suffix;
    }
    canonical_host.starts_with(&format!("{probed_host}."))
}

fn extract_ts_net_suffix(host: &str) -> Option<&str> {
    let without_ts = host.strip_suffix(".ts.net")?;
    let tailnet = without_ts.rsplit('.').next()?;
    (!tailnet.is_empty()).then_some(tailnet)
}

fn is_tailscale_cgnat_ipv4(host: &str) -> bool {
    let Ok(ip) = host.parse::<std::net::Ipv4Addr>() else {
        return false;
    };
    let octets = ip.octets();
    octets[0] == 100 && (64..=127).contains(&octets[1])
}

/// Parses `tailscale status --json` into the local node's Tailscale identity.
pub fn parse_self_identity(
    json_str: &str,
    configured_service: Option<&str>,
) -> Result<Option<NodeIdentity>> {
    let parsed: RawStatusResponse =
        serde_json::from_str(json_str).context("Failed to parse tailscale status JSON")?;

    if !parsed.backend_state.is_empty() && parsed.backend_state != "Running" {
        return Ok(None);
    }

    let Some(self_node) = parsed.self_node else {
        return Ok(None);
    };

    let magic_dns_suffix = clean_dns_name(&parsed.magic_dns_suffix);
    let dns_name = clean_dns_name(&self_node.dns_name);
    let (ipv4, ipv6) = extract_ip_pair(&self_node.tailscale_ips);

    let service_name =
        configured_service.map(normalize_service_name).filter(|s| is_valid_service_name(s));

    let clean_host = self_node.host_name.trim().to_ascii_lowercase();
    let hostname = if is_valid_dns_label(&clean_host) { clean_host } else { String::new() };

    Ok(Some(NodeIdentity { hostname, dns_name, magic_dns_suffix, ipv4, ipv6, service_name }))
}

/// Parses `tailscale status --json` and builds an ordered list of candidate base URLs
/// to probe when searching the tailnet for a running Roche server.
pub fn parse_peer_probe_candidates(json_str: &str, default_port: u16) -> Result<Vec<String>> {
    let parsed: RawStatusResponse =
        serde_json::from_str(json_str).context("Failed to parse tailscale status JSON")?;

    let mut candidates = Vec::new();
    let suffix = clean_dns_name(&parsed.magic_dns_suffix);

    if let Some(suf) = &suffix {
        push_unique(&mut candidates, format!("https://roche.{suf}"));
        push_unique(&mut candidates, format!("http://roche.{suf}:{default_port}"));
    }
    push_unique(&mut candidates, format!("http://roche:{default_port}"));

    if let Some(self_node) = &parsed.self_node {
        append_node_candidates(&mut candidates, self_node, default_port);
    }

    for peer in parsed.peers.values() {
        if peer.online == Some(false) {
            continue;
        }
        append_node_candidates(&mut candidates, peer, default_port);
    }

    Ok(candidates)
}

/// Queries the local `tailscale` CLI for node identity.
pub async fn inspect_local_node(cfg: &TailscaleConfig) -> Option<NodeIdentity> {
    if !cfg.enabled {
        return None;
    }
    let raw = run_tailscale_status(&cfg.tailscale_bin).await.ok()?;
    parse_self_identity(&raw, cfg.advertise_service.as_deref()).ok().flatten()
}

/// Advertises the local Roche server as a Tailscale Service (`svc:<name>`) via `tailscale serve`.
pub async fn advertise_service(cfg: &TailscaleConfig, port: u16) -> Result<Option<String>> {
    if !cfg.enabled {
        return Ok(None);
    }
    let Some(raw_svc) = cfg.advertise_service.as_deref() else {
        return Ok(None);
    };
    let svc = normalize_service_name(raw_svc);
    if !is_valid_service_name(&svc) {
        anyhow::bail!("Invalid Tailscale service name: {svc}");
    }

    let service_arg = format!("--service={svc}");
    let target_arg = format!("127.0.0.1:{port}");
    let output = run_tailscale_cmd(
        &cfg.tailscale_bin,
        &["serve", "--bg", &service_arg, "--https=443", &target_arg],
        SERVE_TIMEOUT,
    )
    .await
    .context("Timed out running `tailscale serve`")?
    .context("Failed to spawn `tailscale serve`")?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("`tailscale serve` exited with {}: {}", output.status, stderr.trim());
    }

    Ok(Some(svc))
}

/// Discovers running Roche servers across the tailnet by combining `tailscale status --json`
/// peers and well-known `MagicDNS` / Tailscale Service names, then probing `/health`.
pub async fn discover_on_tailnet(
    cfg: &TailscaleConfig,
    default_port: u16,
) -> Vec<DiscoveredServer> {
    let mut candidates = Vec::new();
    if cfg.enabled
        && let Ok(raw) = run_tailscale_status(&cfg.tailscale_bin).await
        && let Ok(peer_urls) = parse_peer_probe_candidates(&raw, default_port)
    {
        for url in peer_urls {
            push_unique(&mut candidates, url);
        }
    }
    push_unique(&mut candidates, format!("http://roche:{default_port}"));
    push_unique(&mut candidates, format!("http://127.0.0.1:{default_port}"));

    probe_candidates(&candidates).await
}

/// Probes a list of candidate base URLs with bounded concurrency and returns verified Roche servers.
pub async fn probe_candidates(candidates: &[String]) -> Vec<DiscoveredServer> {
    let Ok(client) = reqwest::Client::builder()
        .timeout(PROBE_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .build()
    else {
        return Vec::new();
    };

    let semaphore = Arc::new(Semaphore::new(MAX_CONCURRENT_PROBES));
    let mut handles = Vec::with_capacity(candidates.len());
    for base in candidates {
        let client = client.clone();
        let sem = Arc::clone(&semaphore);
        let base_url = base.trim_end_matches('/').to_string();
        handles.push(tokio::spawn(async move {
            let _permit = sem.acquire_owned().await.ok()?;
            probe_single(&client, &base_url).await
        }));
    }

    let mut discovered = Vec::new();
    let mut seen_canonical = Vec::new();
    for handle in handles {
        if let Ok(Some(server)) = handle.await {
            let dedup_key =
                server.canonical_url.as_deref().unwrap_or(server.url.as_str()).to_string();
            if !seen_canonical.contains(&dedup_key) {
                seen_canonical.push(dedup_key);
                discovered.push(server);
            }
        }
    }
    discovered
}

async fn probe_single(client: &reqwest::Client, base_url: &str) -> Option<DiscoveredServer> {
    let health_url = format!("{base_url}/health");
    let mut resp = client.get(&health_url).send().await.ok()?;
    if !resp.status().is_success() {
        return None;
    }

    let mut bytes = Vec::new();
    while let Some(chunk) = resp.chunk().await.ok()? {
        if bytes.len().saturating_add(chunk.len()) > MAX_HEALTH_BYTES {
            return None;
        }
        bytes.extend_from_slice(&chunk);
    }

    let body: HealthProbeResponse = serde_json::from_slice(&bytes).ok()?;
    if body.status != "ok" || body.service.as_deref() != Some("roche") {
        return None;
    }
    let validated_canonical =
        body.canonical_url.as_deref().and_then(|c| validate_canonical_url(c, base_url));
    let clean_version = body
        .version
        .map(|v| v.chars().filter(|c| !c.is_control()).take(64).collect::<String>())
        .filter(|v| !v.is_empty());

    Some(DiscoveredServer {
        url: base_url.to_string(),
        canonical_url: validated_canonical,
        version: clean_version,
    })
}

async fn run_tailscale_status(tailscale_bin: &Path) -> Result<String> {
    let output = run_tailscale_cmd(tailscale_bin, &["status", "--json"], STATUS_TIMEOUT)
        .await
        .context("Timed out querying `tailscale status --json`")?
        .context("Failed to execute `tailscale status --json`")?;

    if !output.status.success() {
        anyhow::bail!("tailscale status exited with {}", output.status);
    }

    String::from_utf8(output.stdout).context("Invalid UTF-8 in tailscale status output")
}

async fn run_tailscale_cmd(
    tailscale_bin: &Path,
    args: &[&str],
    timeout: Duration,
) -> std::result::Result<std::io::Result<std::process::Output>, tokio::time::error::Elapsed> {
    let first = tokio::time::timeout(
        timeout,
        Command::new(tailscale_bin).args(args).kill_on_drop(true).output(),
    )
    .await?;
    match first {
        Err(ref e)
            if e.kind() == std::io::ErrorKind::NotFound
                && tailscale_bin == Path::new("tailscale")
                && Path::new(MACOS_APP_TAILSCALE_BIN).exists() =>
        {
            tokio::time::timeout(
                timeout,
                Command::new(MACOS_APP_TAILSCALE_BIN).args(args).kill_on_drop(true).output(),
            )
            .await
        }
        other => Ok(other),
    }
}

fn append_node_candidates(candidates: &mut Vec<String>, node: &RawNode, port: u16) {
    if let Some(dns) = clean_dns_name(&node.dns_name) {
        push_unique(candidates, format!("http://{dns}:{port}"));
    }
    let (ip4, _) = extract_ip_pair(&node.tailscale_ips);
    if let Some(ip) = ip4 {
        push_unique(candidates, format!("http://{ip}:{port}"));
    }
}

fn clean_dns_name(raw: &str) -> Option<String> {
    let cleaned = raw.trim().trim_end_matches('.').to_ascii_lowercase();
    is_valid_dns_name(&cleaned).then_some(cleaned)
}

fn extract_ip_pair(ips: &[String]) -> (Option<String>, Option<String>) {
    let mut ipv4 = None;
    let mut ipv6 = None;
    for raw in ips {
        if let Ok(parsed) = raw.trim().parse::<IpAddr>() {
            match parsed {
                IpAddr::V4(v4) if ipv4.is_none() => ipv4 = Some(v4.to_string()),
                IpAddr::V6(v6) if ipv6.is_none() => ipv6 = Some(v6.to_string()),
                _ => {}
            }
        }
    }
    (ipv4, ipv6)
}

fn push_unique(vec: &mut Vec<String>, item: String) {
    if !vec.contains(&item) {
        vec.push(item);
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const SAMPLE_STATUS_JSON: &str = r#"{
      "BackendState": "Running",
      "MagicDNSSuffix": "tail9876.ts.net",
      "Self": {
        "HostName": "mac-mini",
        "DNSName": "mac-mini.tail9876.ts.net.",
        "TailscaleIPs": ["100.64.0.12", "fd7a:115c:a1e0::12"],
        "Online": true
      },
      "Peer": {
        "nodekey:1111": {
          "HostName": "nas-box",
          "DNSName": "nas-box.tail9876.ts.net.",
          "TailscaleIPs": ["100.64.0.42", "fd7a:115c:a1e0::42"],
          "Online": true
        },
        "nodekey:2222": {
          "HostName": "offline-laptop",
          "DNSName": "offline-laptop.tail9876.ts.net.",
          "TailscaleIPs": ["100.64.0.99"],
          "Online": false
        }
      }
    }"#;

    #[test]
    fn test_parse_self_identity_and_candidate_urls() {
        let identity = parse_self_identity(SAMPLE_STATUS_JSON, Some("roche")).unwrap().unwrap();
        assert_eq!(identity.hostname, "mac-mini");
        assert_eq!(identity.dns_name.as_deref(), Some("mac-mini.tail9876.ts.net"));
        assert_eq!(identity.magic_dns_suffix.as_deref(), Some("tail9876.ts.net"));
        assert_eq!(identity.ipv4.as_deref(), Some("100.64.0.12"));
        assert_eq!(identity.service_name.as_deref(), Some("svc:roche"));

        let urls = identity.candidate_urls(8080);
        assert_eq!(
            urls,
            vec![
                "https://roche.tail9876.ts.net",
                "http://mac-mini.tail9876.ts.net:8080",
                "http://mac-mini:8080",
                "http://100.64.0.12:8080",
            ]
        );
        assert_eq!(identity.canonical_url(8080).as_deref(), Some("https://roche.tail9876.ts.net"));
    }

    #[test]
    fn test_parse_peer_probe_candidates_skips_offline_peers() {
        let candidates = parse_peer_probe_candidates(SAMPLE_STATUS_JSON, 8080).unwrap();
        assert_eq!(
            candidates,
            vec![
                "https://roche.tail9876.ts.net",
                "http://roche.tail9876.ts.net:8080",
                "http://roche:8080",
                "http://mac-mini.tail9876.ts.net:8080",
                "http://100.64.0.12:8080",
                "http://nas-box.tail9876.ts.net:8080",
                "http://100.64.0.42:8080",
            ]
        );
    }

    #[test]
    fn test_service_name_validation_and_normalization() {
        assert_eq!(normalize_service_name("roche"), "svc:roche");
        assert_eq!(normalize_service_name("svc:roche-prod"), "svc:roche-prod");
        assert!(is_valid_service_name("svc:roche"));
        assert!(is_valid_service_name("svc:roche-1"));
        assert!(!is_valid_service_name("roche"));
        assert!(!is_valid_service_name("svc:-bad"));
        assert!(!is_valid_service_name("svc:bad_name"));
    }

    #[test]
    fn test_validate_canonical_url_rejects_untrusted_hosts_and_url_injection() {
        // Valid: probed short MagicDNS `roche` upgrading to tailnet FQDN
        assert_eq!(
            validate_canonical_url("https://roche.tail9876.ts.net", "http://roche:8080").as_deref(),
            Some("https://roche.tail9876.ts.net")
        );
        // Valid: probed Tailscale CGNAT IP upgrading to tailnet FQDN
        assert_eq!(
            validate_canonical_url(
                "http://mac-mini.tail9876.ts.net:8080",
                "http://100.64.0.12:8080"
            )
            .as_deref(),
            Some("http://mac-mini.tail9876.ts.net:8080")
        );
        // Valid: same tailnet suffix (`tail9876.ts.net`)
        assert_eq!(
            validate_canonical_url(
                "https://roche.tail9876.ts.net",
                "http://mac-mini.tail9876.ts.net:8080"
            )
            .as_deref(),
            Some("https://roche.tail9876.ts.net")
        );

        // Rejected: cross-domain redirect to attacker host
        assert!(
            validate_canonical_url("https://attacker.example.com", "http://roche:8080").is_none()
        );
        // Rejected: different tailnet suffix
        assert!(
            validate_canonical_url(
                "https://roche.evil9999.ts.net",
                "http://mac-mini.tail9876.ts.net:8080"
            )
            .is_none()
        );
        // Rejected: non-http(s) scheme, credentials, path, query, fragment
        assert!(validate_canonical_url("javascript:alert(1)", "http://roche:8080").is_none());
        assert!(
            validate_canonical_url("http://user:pass@roche:8080", "http://roche:8080").is_none()
        );
        assert!(
            validate_canonical_url("http://roche:8080/evil/path", "http://roche:8080").is_none()
        );
        assert!(validate_canonical_url("http://roche:8080?foo=bar", "http://roche:8080").is_none());
        assert!(validate_canonical_url("http://roche:8080#frag", "http://roche:8080").is_none());
    }

    #[test]
    fn test_adversarial_dns_and_hostname_in_status_json_are_filtered_out() {
        let malicious_json = r#"{
          "BackendState": "Running",
          "MagicDNSSuffix": "evil.com#@.ts.net",
          "Self": {
            "HostName": "bad/host?name#1",
            "DNSName": "evil.com/path.",
            "TailscaleIPs": ["100.64.0.12"],
            "Online": true
          }
        }"#;
        let identity = parse_self_identity(malicious_json, Some("roche")).unwrap().unwrap();
        assert_eq!(identity.hostname, "");
        assert!(identity.dns_name.is_none());
        assert!(identity.magic_dns_suffix.is_none());
        // Only the validated IPv4 address remains a candidate
        assert_eq!(identity.candidate_urls(8080), vec!["http://100.64.0.12:8080"]);
    }
}
