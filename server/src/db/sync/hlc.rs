use std::fmt;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

pub const MAX_HLC_WALL_MS: i64 = 999_999_999_999_999;
pub const MAX_CLOCK_DRIFT_MS: i64 = 3_600_000;
const WALL_WIDTH: usize = 15;
const COUNTER_WIDTH: usize = 4;

const fn advance_on_counter_overflow(wall_ms: i64) -> (i64, u16) {
    if wall_ms < MAX_HLC_WALL_MS { (wall_ms + 1, 0) } else { (MAX_HLC_WALL_MS, u16::MAX) }
}

/// Hybrid Logical Clock timestamp formatted as `<15-digit-utc-ms>-<4-hex-counter>-<node_id>`.
///
/// The canonical string representation is strictly order-preserving under standard ASCII / UTF-8
/// byte comparison (`strcmp` and SQLite `BINARY` `TEXT` collation).
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct HlcTimestamp {
    pub wall_ms: i64,
    pub counter: u16,
    pub node_id: String,
}

impl HlcTimestamp {
    #[must_use]
    pub fn new(wall_ms: i64, counter: u16, node_id: impl Into<String>) -> Self {
        let clamped_ms = wall_ms.clamp(0, MAX_HLC_WALL_MS);
        let raw_node = node_id.into();
        let clean_node = sanitize_hlc_node_id(&raw_node);
        Self { wall_ms: clamped_ms, counter, node_id: clean_node }
    }

    #[must_use]
    pub fn from_datetime(dt: DateTime<Utc>, node_id: &str) -> Self {
        Self::new(dt.timestamp_millis(), 0, node_id)
    }

    #[must_use]
    pub fn parse(raw: &str) -> Option<Self> {
        let trimmed = raw.trim();
        if trimmed.len() < WALL_WIDTH + 1 + COUNTER_WIDTH + 2 {
            return None;
        }
        let bytes = trimmed.as_bytes();
        if bytes.get(WALL_WIDTH) != Some(&b'-')
            || bytes.get(WALL_WIDTH + 1 + COUNTER_WIDTH) != Some(&b'-')
        {
            return None;
        }
        let wall_str = trimmed.get(..WALL_WIDTH)?;
        if !wall_str.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        let wall_ms = wall_str.parse::<i64>().ok()?;
        let counter_str = trimmed.get(WALL_WIDTH + 1..WALL_WIDTH + 1 + COUNTER_WIDTH)?;
        if !counter_str.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) {
            return None;
        }
        let counter = u16::from_str_radix(counter_str, 16).ok()?;
        let node_id = trimmed.get(WALL_WIDTH + 1 + COUNTER_WIDTH + 1..)?.trim();
        if node_id.is_empty() {
            return None;
        }
        Some(Self { wall_ms, counter, node_id: node_id.to_owned() })
    }

    #[must_use]
    pub fn encode(&self) -> String {
        format!("{:015}-{:04x}-{}", self.wall_ms, self.counter, self.node_id)
    }
}

impl fmt::Display for HlcTimestamp {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:015}-{:04x}-{}", self.wall_ms, self.counter, self.node_id)
    }
}

#[must_use]
pub fn sanitize_hlc_node_id(raw: &str) -> String {
    let filtered: String = raw
        .trim()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':'))
        .take(64)
        .collect();
    if filtered.is_empty() { "peer".to_owned() } else { filtered }
}

/// Stateful Hybrid Logical Clock generator for a single peer/node.
#[derive(Debug, Clone)]
pub struct HlcClock {
    node_id: String,
    last: Option<HlcTimestamp>,
}

impl HlcClock {
    #[must_use]
    pub fn new(node_id: impl Into<String>, initial: Option<&str>) -> Self {
        let clean_node = sanitize_hlc_node_id(&node_id.into());
        let parsed = initial.and_then(HlcTimestamp::parse);
        Self { node_id: clean_node, last: parsed }
    }

    #[must_use]
    pub const fn current(&self) -> Option<&HlcTimestamp> {
        self.last.as_ref()
    }

    /// Advances the clock for a local write at `now_ms`.
    pub fn tick(&mut self, now_ms: i64) -> HlcTimestamp {
        let phys = now_ms.clamp(0, MAX_HLC_WALL_MS);
        let next = match &self.last {
            None => HlcTimestamp::new(phys, 0, self.node_id.clone()),
            Some(prev) if phys > prev.wall_ms => HlcTimestamp::new(phys, 0, self.node_id.clone()),
            Some(prev) => {
                if let Some(c) = prev.counter.checked_add(1) {
                    HlcTimestamp::new(prev.wall_ms, c, self.node_id.clone())
                } else {
                    let (w, c) = advance_on_counter_overflow(prev.wall_ms);
                    HlcTimestamp::new(w, c, self.node_id.clone())
                }
            }
        };
        self.last = Some(next.clone());
        next
    }

    /// Witnesses a remote HLC timestamp and advances the local clock causally beyond both
    /// `self.last` and `remote` at wall-clock `now_ms`, enforcing `MAX_CLOCK_DRIFT_MS`.
    pub fn witness(&mut self, remote: &HlcTimestamp, now_ms: i64) -> HlcTimestamp {
        let phys = now_ms.clamp(0, MAX_HLC_WALL_MS);
        let max_allowed_wall = phys.saturating_add(MAX_CLOCK_DRIFT_MS).min(MAX_HLC_WALL_MS);
        let (rem_ms, rem_counter) = if remote.wall_ms > max_allowed_wall {
            (max_allowed_wall, 0)
        } else {
            (remote.wall_ms.clamp(0, MAX_HLC_WALL_MS), remote.counter)
        };
        let next = match &self.last {
            None => {
                if phys > rem_ms {
                    HlcTimestamp::new(phys, 0, self.node_id.clone())
                } else if let Some(c) = rem_counter.checked_add(1) {
                    HlcTimestamp::new(rem_ms, c, self.node_id.clone())
                } else {
                    let (w, c) = advance_on_counter_overflow(rem_ms);
                    HlcTimestamp::new(w, c, self.node_id.clone())
                }
            }
            Some(prev) => {
                let max_wall = phys.max(prev.wall_ms).max(rem_ms);
                let next_counter = if max_wall == prev.wall_ms && max_wall == rem_ms {
                    prev.counter.max(rem_counter).checked_add(1)
                } else if max_wall == prev.wall_ms {
                    prev.counter.checked_add(1)
                } else if max_wall == rem_ms {
                    rem_counter.checked_add(1)
                } else {
                    Some(0)
                };
                if let Some(c) = next_counter {
                    HlcTimestamp::new(max_wall, c, self.node_id.clone())
                } else {
                    let (w, c) = advance_on_counter_overflow(max_wall);
                    HlcTimestamp::new(w, c, self.node_id.clone())
                }
            }
        };
        self.last = Some(next.clone());
        next
    }

    pub fn witness_str(&mut self, remote_raw: &str, now_ms: i64) -> HlcTimestamp {
        if let Some(rem) = HlcTimestamp::parse(remote_raw) {
            self.witness(&rem, now_ms)
        } else {
            self.tick(now_ms)
        }
    }
}

/// Normalizes an incoming HLC string or synthesizes a deterministic fallback from `fallback_ts`
/// and `peer_id` when a client omits `hlc`, clamping future wall-clock drift to
/// `now + MAX_CLOCK_DRIFT_MS`.
#[must_use]
pub fn resolve_or_synthesize_hlc(
    raw_hlc: &str,
    fallback_ts: Option<DateTime<Utc>>,
    now: DateTime<Utc>,
    peer_id: &str,
) -> String {
    let max_allowed_wall = now
        .timestamp_millis()
        .clamp(0, MAX_HLC_WALL_MS)
        .saturating_add(MAX_CLOCK_DRIFT_MS)
        .min(MAX_HLC_WALL_MS);
    let ts = HlcTimestamp::parse(raw_hlc)
        .unwrap_or_else(|| HlcTimestamp::from_datetime(fallback_ts.unwrap_or(now), peer_id));
    if ts.wall_ms > max_allowed_wall {
        HlcTimestamp::new(max_allowed_wall, 0, ts.node_id).encode()
    } else {
        ts.encode()
    }
}
