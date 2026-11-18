use std::{collections::BTreeMap, net::IpAddr, path::Path, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use tokio::{process::Command, sync::Semaphore};
use url::Url;

use crate::config::TailscaleConfig;

pub const DEFAULT_NODE_HTTPS_PORT: u16 = 443;
pub const FALLBACK_NODE_HTTPS_PORT: u16 = 8443;

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
    #[serde(default)]
    pub node_https_port: Option<u16>,
}

impl NodeIdentity {
    /// Returns the `https://roche.<tailnet>.ts.net` service URL if `service_name` and
    /// `magic_dns_suffix` are configured and valid.
    #[must_use]
    pub fn service_https_url(&self) -> Option<String> {
        let (svc, suffix) = (self.service_name.as_deref()?, self.magic_dns_suffix.as_deref()?);
        let bare_svc = svc.strip_prefix("svc:").unwrap_or(svc);
        (is_valid_dns_label(bare_svc) && is_valid_dns_name(suffix))
            .then(|| format!("https://{bare_svc}.{suffix}"))
    }

    /// Returns this node's direct `https://<node>.<tailnet>.ts.net[:port]` peer URL.
    #[must_use]
    pub fn node_https_url(&self) -> Option<String> {
        let dns = self.dns_name.as_deref()?;
        if !is_valid_dns_name(dns) {
            return None;
        }
        let port = self.node_https_port.unwrap_or(DEFAULT_NODE_HTTPS_PORT);
        if port == DEFAULT_NODE_HTTPS_PORT {
            Some(format!("https://{dns}"))
        } else {
            Some(format!("https://{dns}:{port}"))
        }
    }

    /// Returns the ordered list of reachable HTTPS Tailscale URLs for this Roche peer.
    #[must_use]
    pub fn candidate_urls(&self) -> Vec<String> {
        let mut urls = Vec::new();
        if let Some(svc_url) = self.service_https_url() {
            push_unique(&mut urls, svc_url);
        }
        if let Some(node_url) = self.node_https_url() {
            push_unique(&mut urls, node_url);
        }
        urls
    }

    #[must_use]
    pub fn canonical_url(&self) -> Option<String> {
        self.candidate_urls().into_iter().next()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TailscaleExposure {
    pub service_name: Option<String>,
    pub node_https_port: Option<u16>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ActiveServeState {
    pub node_https_port: Option<u16>,
    pub service_configured: bool,
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

#[derive(Debug, Default, Deserialize)]
struct RawServeStatus {
    #[serde(rename = "Web", default)]
    web: BTreeMap<String, RawServeWebHost>,
    #[serde(rename = "Services", default)]
    services: BTreeMap<String, RawServeService>,
}

#[derive(Debug, Default, Deserialize)]
struct RawServeService {
    #[serde(rename = "Web", default)]
    web: BTreeMap<String, RawServeWebHost>,
}

#[derive(Debug, Default, Deserialize)]
struct RawServeWebHost {
    #[serde(rename = "Handlers", default)]
    handlers: BTreeMap<String, RawServeHandler>,
}

#[derive(Debug, Default, Deserialize)]
struct RawServeHandler {
    #[serde(rename = "Proxy", default)]
    proxy: String,
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
        && !label.starts_with('.')
        && !label.ends_with('-')
        && label.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

#[must_use]
pub fn is_valid_dns_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 253 && name.split('.').all(is_valid_dns_label)
}

/// Validates a `canonical_url` advertised by a probed `/health` endpoint so a rogue peer
/// cannot redirect clients to an arbitrary external host, downgrade HTTPS to cleartext HTTP,
/// or inject paths/credentials.
#[must_use]
pub fn validate_canonical_url(raw_canonical: &str, probed_base_url: &str) -> Option<String> {
    let parsed = Url::parse(raw_canonical.trim()).ok()?;
    let probed = Url::parse(probed_base_url.trim()).ok()?;
    let probed_host = probed.host_str()?.to_ascii_lowercase();
    let is_local_probe = probed_host == "127.0.0.1" || probed_host == "localhost";
    if parsed.scheme() != "https" && !(is_local_probe && parsed.scheme() == "http") {
        return None;
    }
    if probed.scheme() == "https" && parsed.scheme() != "https" {
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

    Ok(Some(NodeIdentity {
        hostname,
        dns_name,
        magic_dns_suffix,
        ipv4,
        ipv6,
        service_name,
        node_https_port: Some(DEFAULT_NODE_HTTPS_PORT),
    }))
}

/// Parses `tailscale status --json` and builds an ordered list of HTTPS candidate base URLs
/// to probe when searching the tailnet for running Roche peers.
pub fn parse_peer_probe_candidates(json_str: &str) -> Result<Vec<String>> {
    let parsed: RawStatusResponse =
        serde_json::from_str(json_str).context("Failed to parse tailscale status JSON")?;

    let mut candidates = Vec::new();
    let suffix = clean_dns_name(&parsed.magic_dns_suffix);

    if let Some(suf) = &suffix {
        push_unique(&mut candidates, format!("https://roche.{suf}"));
    }

    if let Some(self_node) = &parsed.self_node {
        append_node_candidates(&mut candidates, self_node);
    }

    for peer in parsed.peers.values() {
        if peer.online == Some(false) {
            continue;
        }
        append_node_candidates(&mut candidates, peer);
    }

    Ok(candidates)
}

/// Inspects `tailscale serve status --json` to determine which HTTPS port and service handlers
/// currently forward to the local Roche HTTP listener.
#[must_use]
pub fn inspect_active_serve(
    serve_status_json: &str,
    upstream_host: &str,
    port: u16,
    expected_svc: Option<&str>,
) -> ActiveServeState {
    let Ok(parsed) = serde_json::from_str::<RawServeStatus>(serve_status_json) else {
        return ActiveServeState::default();
    };
    let mut node_https_port = None;
    for (host_key, web_host) in &parsed.web {
        let key_port = extract_web_key_port(host_key);
        if web_host_proxies_to(web_host, upstream_host, port)
            && (key_port == DEFAULT_NODE_HTTPS_PORT || node_https_port.is_none())
        {
            node_https_port = Some(key_port);
        }
    }
    let service_configured = expected_svc
        .map(normalize_service_name)
        .and_then(|svc| parsed.services.get(&svc))
        .is_some_and(|svc_cfg| {
            svc_cfg.web.values().any(|w| web_host_proxies_to(w, upstream_host, port))
        });

    ActiveServeState { node_https_port, service_configured }
}

/// Chooses port `443` when free or already pointing at Roche, or `8443` if another local
/// application already occupies node HTTPS port `443`.
#[must_use]
pub fn select_node_https_port(
    serve_status_json: Option<&str>,
    upstream_host: &str,
    port: u16,
) -> u16 {
    let Some(raw) = serve_status_json else {
        return DEFAULT_NODE_HTTPS_PORT;
    };
    let Ok(parsed) = serde_json::from_str::<RawServeStatus>(raw) else {
        return DEFAULT_NODE_HTTPS_PORT;
    };
    for (host_key, web_host) in &parsed.web {
        if web_host_proxies_to(web_host, upstream_host, port) {
            return extract_web_key_port(host_key);
        }
    }
    let port_443_busy = parsed.web.iter().any(|(host_key, web_host)| {
        extract_web_key_port(host_key) == DEFAULT_NODE_HTTPS_PORT && !web_host.handlers.is_empty()
    });
    if port_443_busy { FALLBACK_NODE_HTTPS_PORT } else { DEFAULT_NODE_HTTPS_PORT }
}

fn extract_web_key_port(host_key: &str) -> u16 {
    host_key
        .rsplit_once(':')
        .and_then(|(_, p)| p.parse::<u16>().ok())
        .unwrap_or(DEFAULT_NODE_HTTPS_PORT)
}

fn web_host_proxies_to(web_host: &RawServeWebHost, upstream_host: &str, port: u16) -> bool {
    web_host
        .handlers
        .get("/")
        .is_some_and(|handler| proxy_matches_upstream(&handler.proxy, upstream_host, port))
}

fn proxy_matches_upstream(proxy: &str, upstream_host: &str, port: u16) -> bool {
    let clean = proxy.trim().trim_end_matches('/').to_ascii_lowercase();
    let host = serve_upstream_host(upstream_host).to_ascii_lowercase();
    let expected = format_target_origin(&host, port);
    if clean == expected {
        return true;
    }
    if host == "127.0.0.1" {
        return clean == format!("http://localhost:{port}")
            || clean == format!("http://[::1]:{port}");
    }
    if host == "::1" {
        return clean == format!("http://localhost:{port}");
    }
    false
}

fn format_target_arg(upstream_host: &str, port: u16) -> Result<String> {
    let host = serve_upstream_host(upstream_host);
    if host.starts_with('-') {
        anyhow::bail!("Invalid Tailscale upstream host (cannot start with '-'): {host}");
    }
    if host.contains(':') && !host.starts_with('[') {
        Ok(format!("[{host}]:{port}"))
    } else {
        Ok(format!("{host}:{port}"))
    }
}

fn format_target_origin(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("http://[{host}]:{port}")
    } else {
        format!("http://{host}:{port}")
    }
}

/// Queries the local `tailscale` CLI for node identity.
pub async fn inspect_local_node(cfg: &TailscaleConfig) -> Option<NodeIdentity> {
    if !cfg.enabled {
        return None;
    }
    let raw = run_tailscale_status(&cfg.tailscale_bin).await.ok()?;
    parse_self_identity(&raw, cfg.advertise_service.as_deref()).ok().flatten()
}

/// Resolves the live HTTPS canonical URL for `roche pair`.
///
/// Prefers `https://roche.<tailnet>.ts.net` when `svc:roche` is actively configured and
/// responding, otherwise returns this peer's active `https://<node>.<tailnet>.ts.net[:port]`.
pub async fn resolve_live_canonical_url(
    cfg: &TailscaleConfig,
    listen_host: &str,
    port: u16,
) -> Option<String> {
    let mut identity = inspect_local_node(cfg).await?;
    if let Ok(serve_json) = run_tailscale_serve_status(&cfg.tailscale_bin).await {
        let active =
            inspect_active_serve(&serve_json, listen_host, port, cfg.advertise_service.as_deref());
        if let Some(active_port) = active.node_https_port {
            identity.node_https_port = Some(active_port);
        }
        if !active.service_configured {
            identity.service_name = None;
        }
    }
    if let Some(svc_url) = identity.service_https_url() {
        if is_roche_health_reachable(&svc_url).await {
            return Some(svc_url);
        }
        identity.service_name = None;
    }
    identity.canonical_url()
}

/// Automatically configures Tailscale HTTPS for `roche serve`.
///
/// 1. Exposes per-peer HTTPS (`https://<node>.<tailnet>.ts.net` on `443`, or `8443` if `443` is busy)
///    so every peer on the tailnet is directly reachable over verified TLS with zero ACL setup.
/// 2. Attempts to advertise the host-independent Tailscale Service (`svc:roche` ->
///    `https://roche.<tailnet>.ts.net` on `443`) when configured; on untagged personal machines
///    where Tailscale Services are unsupported, gracefully falls back to per-peer HTTPS.
pub async fn expose_over_tailscale(
    cfg: &TailscaleConfig,
    listen_host: &str,
    port: u16,
) -> TailscaleExposure {
    if !cfg.enabled {
        return TailscaleExposure::default();
    }
    let serve_status = run_tailscale_serve_status(&cfg.tailscale_bin).await.ok();
    let chosen_port = select_node_https_port(serve_status.as_deref(), listen_host, port);

    let node_https_port = match configure_node_https(
        &cfg.tailscale_bin,
        listen_host,
        port,
        chosen_port,
    )
    .await
    {
        Ok(()) => Some(chosen_port),
        Err(e) => {
            tracing::warn!(
                error = %e,
                "Failed to auto-configure node HTTPS via `tailscale serve`; ensure HTTPS Certificates are enabled in Tailscale DNS settings"
            );
            None
        }
    };

    let service_name = match advertise_service_with_target(cfg, listen_host, port).await {
        Ok(svc) => svc,
        Err(e) => {
            tracing::info!(
                reason = %e,
                "Tailscale Service (`svc:*`) skipped on this peer (requires an ACL-tagged host and Services definition); using direct peer HTTPS"
            );
            None
        }
    };

    TailscaleExposure { service_name, node_https_port }
}

/// Exposes `upstream_host:port` over per-node HTTPS via `tailscale serve --bg --https=<https_port>`.
pub async fn configure_node_https(
    tailscale_bin: &Path,
    upstream_host: &str,
    port: u16,
    https_port: u16,
) -> Result<()> {
    let target_arg = format_target_arg(upstream_host, port)?;
    let https_arg = format!("--https={https_port}");
    let output = run_tailscale_cmd(
        tailscale_bin,
        &["serve", "--bg", &https_arg, &target_arg],
        SERVE_TIMEOUT,
    )
    .await
    .context("Timed out running `tailscale serve` for node HTTPS")?
    .context("Failed to spawn `tailscale serve` for node HTTPS")?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("`tailscale serve` exited with {}: {}", output.status, stderr.trim());
    }
    Ok(())
}

/// Advertises the local Roche server as a Tailscale Service (`svc:<name>`) via `tailscale serve`.
pub async fn advertise_service(cfg: &TailscaleConfig, port: u16) -> Result<Option<String>> {
    advertise_service_with_target(cfg, "127.0.0.1", port).await
}

/// Resolves the local TCP upstream host that `tailscale serve` should proxy to for `listen_host`.
///
/// Maps wildcard/IPv4 loopback binds to `127.0.0.1` while preserving IPv6 loopback (`::1`) and
/// explicit interface IPs.
#[must_use]
pub fn serve_upstream_host(listen_host: &str) -> &str {
    let trimmed = listen_host.trim();
    match trimmed {
        "" | "0.0.0.0" | "::" | "[::]" | "127.0.0.1" | "localhost" => "127.0.0.1",
        "::1" | "[::1]" => "::1",
        other => other,
    }
}

/// Advertises the local Roche server at `upstream_host:port` via `tailscale serve --service=<svc>`.
pub async fn advertise_service_with_target(
    cfg: &TailscaleConfig,
    upstream_host: &str,
    port: u16,
) -> Result<Option<String>> {
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

    let target_arg = format_target_arg(upstream_host, port)?;
    let service_arg = format!("--service={svc}");
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

/// Discovers running Roche peers across the tailnet by combining `tailscale status --json`
/// peers and `https://roche.<tailnet>.ts.net`, then probing `/health`.
pub async fn discover_on_tailnet(
    cfg: &TailscaleConfig,
    default_port: u16,
) -> Vec<DiscoveredServer> {
    let mut candidates = Vec::new();
    if cfg.enabled
        && let Ok(raw) = run_tailscale_status(&cfg.tailscale_bin).await
        && let Ok(peer_urls) = parse_peer_probe_candidates(&raw)
    {
        for url in peer_urls {
            push_unique(&mut candidates, url);
        }
    }
    push_unique(&mut candidates, format!("http://127.0.0.1:{default_port}"));

    probe_candidates(&candidates).await
}

fn build_probe_client() -> Option<reqwest::Client> {
    reqwest::Client::builder()
        .timeout(PROBE_TIMEOUT)
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .ok()
}

async fn is_roche_health_reachable(base_url: &str) -> bool {
    let Some(client) = build_probe_client() else {
        return false;
    };
    probe_single(&client, base_url.trim_end_matches('/')).await.is_some()
}

/// Probes a list of candidate base URLs with bounded concurrency and returns verified Roche servers.
pub async fn probe_candidates(candidates: &[String]) -> Vec<DiscoveredServer> {
    let Some(client) = build_probe_client() else {
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

async fn run_tailscale_json(tailscale_bin: &Path, args: &[&str], label: &str) -> Result<String> {
    let output = run_tailscale_cmd(tailscale_bin, args, STATUS_TIMEOUT)
        .await
        .with_context(|| format!("Timed out querying `{label}`"))?
        .with_context(|| format!("Failed to execute `{label}`"))?;

    if !output.status.success() {
        anyhow::bail!("{label} exited with {}", output.status);
    }

    String::from_utf8(output.stdout).with_context(|| format!("Invalid UTF-8 in {label} output"))
}

async fn run_tailscale_status(tailscale_bin: &Path) -> Result<String> {
    run_tailscale_json(tailscale_bin, &["status", "--json"], "tailscale status --json").await
}

async fn run_tailscale_serve_status(tailscale_bin: &Path) -> Result<String> {
    run_tailscale_json(
        tailscale_bin,
        &["serve", "status", "--json"],
        "tailscale serve status --json",
    )
    .await
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

fn append_node_candidates(candidates: &mut Vec<String>, node: &RawNode) {
    if let Some(dns) = clean_dns_name(&node.dns_name) {
        push_unique(candidates, format!("https://{dns}"));
        push_unique(candidates, format!("https://{dns}:{FALLBACK_NODE_HTTPS_PORT}"));
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
    fn test_parse_self_identity_and_https_candidate_urls() {
        let mut identity = parse_self_identity(SAMPLE_STATUS_JSON, Some("roche")).unwrap().unwrap();
        assert_eq!(identity.hostname, "mac-mini");
        assert_eq!(identity.dns_name.as_deref(), Some("mac-mini.tail9876.ts.net"));
        assert_eq!(identity.magic_dns_suffix.as_deref(), Some("tail9876.ts.net"));
        assert_eq!(identity.ipv4.as_deref(), Some("100.64.0.12"));
        assert_eq!(identity.service_name.as_deref(), Some("svc:roche"));

        assert_eq!(
            identity.candidate_urls(),
            vec!["https://roche.tail9876.ts.net", "https://mac-mini.tail9876.ts.net"]
        );
        assert_eq!(identity.canonical_url().as_deref(), Some("https://roche.tail9876.ts.net"));

        identity.service_name = None;
        identity.node_https_port = Some(8443);
        assert_eq!(identity.candidate_urls(), vec!["https://mac-mini.tail9876.ts.net:8443"]);
        assert_eq!(
            identity.canonical_url().as_deref(),
            Some("https://mac-mini.tail9876.ts.net:8443")
        );
    }

    #[test]
    fn test_parse_peer_probe_candidates_https_only_and_skips_offline_peers() {
        let candidates = parse_peer_probe_candidates(SAMPLE_STATUS_JSON).unwrap();
        assert_eq!(
            candidates,
            vec![
                "https://roche.tail9876.ts.net",
                "https://mac-mini.tail9876.ts.net",
                "https://mac-mini.tail9876.ts.net:8443",
                "https://nas-box.tail9876.ts.net",
                "https://nas-box.tail9876.ts.net:8443",
            ]
        );
    }

    #[test]
    fn test_select_node_https_port_and_inspect_active_serve() {
        assert_eq!(select_node_https_port(None, "127.0.0.1", 8080), 443);
        assert_eq!(select_node_https_port(Some("{}"), "127.0.0.1", 8080), 443);

        let occupied_443 = r#"{
          "Web": {
            "mac-mini.tail9876.ts.net:443": {
              "Handlers": { "/": { "Proxy": "http://127.0.0.1:3000" } }
            }
          }
        }"#;
        assert_eq!(select_node_https_port(Some(occupied_443), "127.0.0.1", 8080), 8443);

        let roche_on_8443_and_svc = r#"{
          "Web": {
            "mac-mini.tail9876.ts.net:443": {
              "Handlers": { "/": { "Proxy": "http://127.0.0.1:3000" } }
            },
            "mac-mini.tail9876.ts.net:8443": {
              "Handlers": { "/": { "Proxy": "http://127.0.0.1:8080/" } }
            }
          },
          "Services": {
            "svc:roche": {
              "Web": {
                "roche.tail9876.ts.net:443": {
                  "Handlers": { "/": { "Proxy": "http://localhost:8080" } }
                }
              }
            }
          }
        }"#;
        assert_eq!(select_node_https_port(Some(roche_on_8443_and_svc), "127.0.0.1", 8080), 8443);
        let active =
            inspect_active_serve(roche_on_8443_and_svc, "127.0.0.1", 8080, Some("svc:roche"));
        assert_eq!(
            active,
            ActiveServeState { node_https_port: Some(8443), service_configured: true }
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
    fn test_validate_canonical_url_rejects_untrusted_hosts_downgrades_and_url_injection() {
        // Valid: probed local loopback upgrading to tailnet HTTPS FQDN
        assert_eq!(
            validate_canonical_url("https://roche.tail9876.ts.net", "http://127.0.0.1:8080")
                .as_deref(),
            Some("https://roche.tail9876.ts.net")
        );
        // Valid: same tailnet suffix (`tail9876.ts.net`) over HTTPS
        assert_eq!(
            validate_canonical_url(
                "https://roche.tail9876.ts.net",
                "https://mac-mini.tail9876.ts.net:8443"
            )
            .as_deref(),
            Some("https://roche.tail9876.ts.net")
        );

        // Rejected: cleartext HTTP canonical URL on Tailnet
        assert!(
            validate_canonical_url(
                "http://roche.tail9876.ts.net:8080",
                "https://mac-mini.tail9876.ts.net"
            )
            .is_none()
        );
        assert!(
            validate_canonical_url(
                "http://mac-mini.tail9876.ts.net:8080",
                "http://100.64.0.12:8080"
            )
            .is_none()
        );

        // Rejected: cross-domain redirect to attacker host
        assert!(
            validate_canonical_url("https://attacker.example.com", "https://roche.tail9876.ts.net")
                .is_none()
        );
        // Rejected: different tailnet suffix
        assert!(
            validate_canonical_url(
                "https://roche.evil9999.ts.net",
                "https://mac-mini.tail9876.ts.net"
            )
            .is_none()
        );
        // Rejected: non-http(s) scheme, credentials, path, query, fragment
        assert!(validate_canonical_url("javascript:alert(1)", "http://127.0.0.1:8080").is_none());
        assert!(
            validate_canonical_url(
                "https://user:pass@roche.tail9876.ts.net",
                "http://127.0.0.1:8080"
            )
            .is_none()
        );
        assert!(
            validate_canonical_url(
                "https://roche.tail9876.ts.net/evil/path",
                "http://127.0.0.1:8080"
            )
            .is_none()
        );
        assert!(
            validate_canonical_url(
                "https://roche.tail9876.ts.net?foo=bar",
                "http://127.0.0.1:8080"
            )
            .is_none()
        );
        assert!(
            validate_canonical_url("https://roche.tail9876.ts.net#frag", "http://127.0.0.1:8080")
                .is_none()
        );
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
        // No cleartext IP fallback is emitted when DNS names are invalid
        assert_eq!(identity.candidate_urls(), Vec::<String>::new());
    }

    #[test]
    fn test_serve_upstream_host_preserves_ipv6_loopback_and_rejects_flag_injection() {
        assert_eq!(serve_upstream_host("127.0.0.1"), "127.0.0.1");
        assert_eq!(serve_upstream_host("0.0.0.0"), "127.0.0.1");
        assert_eq!(serve_upstream_host("::"), "127.0.0.1");
        assert_eq!(serve_upstream_host("::1"), "::1");
        assert_eq!(serve_upstream_host("[::1]"), "::1");

        assert_eq!(format_target_arg("::1", 8080).unwrap(), "[::1]:8080");
        assert_eq!(format_target_arg("[::1]", 8080).unwrap(), "[::1]:8080");
        assert_eq!(format_target_arg("127.0.0.1", 8080).unwrap(), "127.0.0.1:8080");
        assert!(format_target_arg("--flag-injection", 8080).is_err());

        assert!(proxy_matches_upstream("http://[::1]:8080", "::1", 8080));
        assert!(!proxy_matches_upstream("http://127.0.0.1:8080", "::1", 8080));
    }
}
