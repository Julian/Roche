use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::Mutex,
    time::{Duration, Instant},
};

use hex::FromHex;
use hmac::{Hmac, KeyInit, Mac};
use sha2::Sha256;
use subtle::ConstantTimeEq;

use super::{AppError, RocheService};
use crate::{
    auth::{pairing::PairingManager, tokens::TokenManager},
    db::DeviceToken,
};

const MAX_DEVICE_NAME_LEN: usize = 128;
const MAX_TAILSCALE_LOGIN_KEY_LEN: usize = 256;
const PAIR_RATE_LIMIT_WINDOW: Duration = Duration::from_secs(60);
const PAIR_RATE_LIMIT_MAX_FAILURES: usize = 4;
const PAIR_PRE_DB_MAX_ATTEMPTS: usize = 12;
const MAX_TRACKED_PAIRING_IPS: usize = 4_096;
const MAX_TAILSCALE_WEB_TOKENS_PER_USER: i64 = 3;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum PairingClientKey {
    DirectIp(IpAddr),
    TailscaleLogin(String),
}

#[derive(Debug, Default)]
struct PairingRateLimiterState {
    active_hash: Option<String>,
    by_client: HashMap<PairingClientKey, Vec<Instant>>,
    pre_db_by_client: HashMap<PairingClientKey, Vec<Instant>>,
}

#[derive(Debug, Default)]
pub(super) struct PairingRateLimiter {
    state: Mutex<PairingRateLimiterState>,
}

impl PairingRateLimiter {
    fn compute_retry_after(now: Instant, timestamps: &[Instant]) -> u64 {
        let remaining = timestamps
            .iter()
            .copied()
            .min()
            .and_then(|oldest| PAIR_RATE_LIMIT_WINDOW.checked_sub(now.duration_since(oldest)))
            .unwrap_or(Duration::ZERO);
        if remaining.subsec_nanos() > 0 {
            remaining.as_secs().saturating_add(1)
        } else {
            remaining.as_secs()
        }
        .clamp(1, PAIR_RATE_LIMIT_WINDOW.as_secs())
    }

    fn record_in_map(
        map: &mut HashMap<PairingClientKey, Vec<Instant>>,
        client: PairingClientKey,
        now: Instant,
        max_attempts: usize,
    ) -> Result<(), u64> {
        map.retain(|_, timestamps| {
            timestamps.retain(|&ts| now.duration_since(ts) < PAIR_RATE_LIMIT_WINDOW);
            !timestamps.is_empty()
        });
        if let Some(timestamps) = map.get_mut(&client) {
            if timestamps.len() >= max_attempts {
                return Err(Self::compute_retry_after(now, timestamps));
            }
            timestamps.push(now);
            return Ok(());
        }
        if map.len() >= MAX_TRACKED_PAIRING_IPS
            && let Some(oldest_key) = map
                .iter()
                .min_by_key(|(_, ts)| ts.last().copied().unwrap_or(now))
                .map(|(k, _)| k.clone())
        {
            map.remove(&oldest_key);
        }
        map.insert(client, vec![now]);
        Ok(())
    }

    /// Lightweight in-memory flood guard checked BEFORE touching SQLite so sustained
    /// unauthenticated bursts cannot force unbounded DB reads.
    fn check_pre_db(&self, client: &PairingClientKey) -> Result<(), u64> {
        let now = Instant::now();
        let mut guard = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        Self::record_in_map(
            &mut guard.pre_db_by_client,
            client.clone(),
            now,
            PAIR_PRE_DB_MAX_ATTEMPTS,
        )
    }

    fn check_and_record(
        &self,
        active_hash: Option<&str>,
        client: PairingClientKey,
    ) -> Result<(), u64> {
        let now = Instant::now();
        let mut guard = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if guard.active_hash.as_deref() != active_hash {
            guard.active_hash = active_hash.map(str::to_owned);
            guard.by_client.clear();
        }
        Self::record_in_map(&mut guard.by_client, client, now, PAIR_RATE_LIMIT_MAX_FAILURES)
    }

    fn reset(&self) {
        let mut guard = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        guard.active_hash = None;
        guard.by_client.clear();
        guard.pre_db_by_client.clear();
    }
}

pub struct AuthService<'a> {
    pub(super) service: &'a RocheService,
}

impl AuthService<'_> {
    #[must_use]
    pub fn resolve_pairing_client_key(
        &self,
        peer_addr: Option<SocketAddr>,
        tailscale_user_login: Option<&str>,
        x_forwarded_for: Option<&str>,
    ) -> PairingClientKey {
        if self.service.config.tailscale.enabled
            && peer_addr.is_some_and(|addr| addr.ip().to_canonical().is_loopback())
        {
            if let Some(ts_login) =
                tailscale_user_login.map(|s| s.trim().to_ascii_lowercase()).filter(|s| {
                    !s.is_empty()
                        && s.len() <= MAX_TAILSCALE_LOGIN_KEY_LEN
                        && !s.contains(',')
                        && !s.chars().any(char::is_control)
                })
            {
                return PairingClientKey::TailscaleLogin(ts_login);
            }
            if let Some(fwd_ip) = x_forwarded_for
                .and_then(|s| s.rsplit(',').next())
                .and_then(|raw| raw.trim().parse::<IpAddr>().ok())
            {
                return PairingClientKey::DirectIp(fwd_ip.to_canonical());
            }
        }
        PairingClientKey::DirectIp(
            peer_addr.map_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED), |addr| addr.ip().to_canonical()),
        )
    }

    pub async fn pair_device_for_request(
        &self,
        device_name: &str,
        pairing_code: &str,
        peer_addr: Option<SocketAddr>,
        tailscale_user_login: Option<&str>,
        x_forwarded_for: Option<&str>,
    ) -> Result<String, AppError> {
        let key = self.resolve_pairing_client_key(peer_addr, tailscale_user_login, x_forwarded_for);
        self.pair_device_with_key(device_name, pairing_code, key).await
    }

    pub async fn issue_pairing_code(&self) -> Result<String, AppError> {
        let pairing = PairingManager::new(self.service.db.auth());
        let code = pairing.create_code(self.service.config.auth.pairing_code_ttl).await?;
        self.service.pairing_limiter.reset();
        Ok(code)
    }

    pub async fn pair_device_with_key(
        &self,
        device_name: &str,
        pairing_code: &str,
        client_key: PairingClientKey,
    ) -> Result<String, AppError> {
        let trimmed_name = device_name.trim();
        if trimmed_name.is_empty() {
            return Err(AppError::bad_request("device_name cannot be empty"));
        }
        if trimmed_name.len() > MAX_DEVICE_NAME_LEN {
            return Err(AppError::bad_request(format!(
                "device_name cannot exceed {MAX_DEVICE_NAME_LEN} characters"
            )));
        }

        if let Err(retry_after_secs) = self.service.pairing_limiter.check_pre_db(&client_key) {
            return Err(AppError::pairing_rate_limited(
                "Too many failed pairing attempts; please wait before retrying",
                retry_after_secs,
            ));
        }

        let active_hash = self.service.db.auth().active_pairing_code_hash().await?;

        if let Err(retry_after_secs) =
            self.service.pairing_limiter.check_and_record(active_hash.as_deref(), client_key)
        {
            return Err(AppError::pairing_rate_limited(
                "Too many failed pairing attempts; please wait before retrying",
                retry_after_secs,
            ));
        }

        if active_hash.is_none() {
            return Err(AppError::pairing_code_invalid("Invalid or expired pairing code"));
        }

        let pairing = PairingManager::new(self.service.db.auth());
        if !pairing.consume_valid_code(pairing_code.trim()).await? {
            return Err(AppError::pairing_code_invalid("Invalid or expired pairing code"));
        }

        self.service.pairing_limiter.reset();

        let tokens = TokenManager::new(self.service.db.auth());
        let token = tokens.create(trimmed_name, self.service.config.auth.token_length).await?;
        Ok(token)
    }

    pub async fn authenticate(&self, bearer_token: &str) -> Result<(), AppError> {
        let tokens = TokenManager::new(self.service.db.auth());
        if !tokens.validate(bearer_token).await? {
            return Err(AppError::token_invalid("Invalid or revoked bearer token"));
        }
        Ok(())
    }

    pub async fn count_devices(&self) -> Result<i64, AppError> {
        Ok(self.service.db.auth().count_devices().await?)
    }

    pub async fn list_devices(&self) -> Result<Vec<DeviceToken>, AppError> {
        Ok(self.service.db.auth().list_devices().await?)
    }

    pub async fn revoke_device(&self, id: &str) -> Result<(), AppError> {
        if !self.service.db.auth().revoke_device(id).await? {
            return Err(AppError::not_found("Device not found"));
        }
        Ok(())
    }

    fn csrf_mac_bytes(raw_session_token: &str) -> [u8; 32] {
        type HmacSha256 = Hmac<Sha256>;
        let mut mac = HmacSha256::new_from_slice(b"roche-web-csrf-hmac-v1")
            .expect("HMAC-SHA256 accepts keys of any size");
        mac.update(raw_session_token.as_bytes());
        mac.finalize().into_bytes().into()
    }

    #[must_use]
    pub fn csrf_token_for_session(&self, raw_session_token: &str) -> String {
        let _ = self;
        hex::encode(Self::csrf_mac_bytes(raw_session_token))
    }

    #[must_use]
    pub fn verify_csrf_token(&self, raw_session_token: &str, candidate: &str) -> bool {
        let _ = self;
        let Ok(cand_bytes) = <[u8; 32]>::from_hex(candidate.trim()) else {
            return false;
        };
        let exp_bytes = Self::csrf_mac_bytes(raw_session_token);
        bool::from(exp_bytes.ct_eq(&cand_bytes))
    }

    pub async fn mint_tailscale_web_session(&self, login: &str) -> Result<String, AppError> {
        let clean_login = login.trim().to_ascii_lowercase();
        let cached = self
            .service
            .tailscale_web_sessions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .get(&clean_login)
            .cloned();
        if let Some(existing) = cached
            && self.authenticate(&existing).await.is_ok()
        {
            return Ok(existing);
        }

        let raw_name = format!("Roche Web ({clean_login})");
        let safe_name: String = raw_name.chars().take(MAX_DEVICE_NAME_LEN).collect();
        let auth_repo = self.service.db.auth();
        let tokens = TokenManager::new(auth_repo);
        let minted = tokens.create(&safe_name, self.service.config.auth.token_length).await?;
        let _ = self
            .service
            .db
            .auth()
            .prune_excess_named_tokens(&safe_name, MAX_TAILSCALE_WEB_TOKENS_PER_USER)
            .await;
        self.service
            .tailscale_web_sessions
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .insert(clean_login, minted.clone());
        Ok(minted)
    }

    pub async fn revoke_session_token(&self, raw_session_token: &str) -> Result<bool, AppError> {
        {
            let mut guard = self
                .service
                .tailscale_web_sessions
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            guard.retain(|_, tok| tok != raw_session_token);
        }
        let token_hash = TokenManager::hash_token(raw_session_token);
        Ok(self.service.db.auth().revoke_device_by_token_hash(&token_hash).await?)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use super::*;

    #[test]
    fn test_pairing_rate_limiter_bounds_tracked_ips_and_evicts_oldest() {
        let limiter = PairingRateLimiter::default();
        let active_hash = Some("hash-1");

        for idx in 0..(MAX_TRACKED_PAIRING_IPS + 8) {
            let hi = u8::try_from((idx >> 8) & 0xff).unwrap();
            let lo = u8::try_from(idx & 0xff).unwrap();
            let ip = IpAddr::V4(Ipv4Addr::new(198, 51, hi, lo));
            assert!(limiter.check_and_record(active_hash, PairingClientKey::DirectIp(ip)).is_ok());
        }

        let guard = limiter.state.lock().unwrap();
        assert_eq!(guard.by_client.len(), MAX_TRACKED_PAIRING_IPS);
    }

    #[test]
    fn test_pairing_rate_limiter_isolates_tailscale_logins_behind_loopback() {
        let limiter = PairingRateLimiter::default();
        let active_hash = Some("hash-ts");

        for _ in 0..PAIR_RATE_LIMIT_MAX_FAILURES {
            assert!(
                limiter
                    .check_and_record(
                        active_hash,
                        PairingClientKey::TailscaleLogin("alice@tailnet.ts.net".to_owned()),
                    )
                    .is_ok()
            );
        }
        let retry_after = limiter
            .check_and_record(
                active_hash,
                PairingClientKey::TailscaleLogin("alice@tailnet.ts.net".to_owned()),
            )
            .unwrap_err();
        assert!((1..=PAIR_RATE_LIMIT_WINDOW.as_secs()).contains(&retry_after));

        // Another Tailscale user behind the same 127.0.0.1 `tailscale serve` proxy is unaffected.
        assert!(
            limiter
                .check_and_record(
                    active_hash,
                    PairingClientKey::TailscaleLogin("bob@tailnet.ts.net".to_owned()),
                )
                .is_ok()
        );
    }
}
