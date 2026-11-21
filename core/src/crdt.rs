use std::{cmp::Ordering, collections::HashSet};

use chrono::{DateTime, SubsecRound, Utc};

use crate::{
    domain::{EntryKind, InboxState},
    hlc::clamp_incoming_timestamp_opt,
    identity_norm::strip_album_edition_suffix,
};

/// Pure value representation of an entry's reader/inbox state for CRDT merging.
#[derive(Debug, Clone, PartialEq)]
pub struct EntryStateSnapshot {
    pub inbox_state: InboxState,
    pub read_at: Option<DateTime<Utc>>,
    pub progress: f64,
    pub interaction_updated_at: Option<DateTime<Utc>>,
    pub hlc: String,
}

/// Clamps an incoming progress ratio into `[0.0, 1.0]`, replacing non-finite values with `0.0`.
#[must_use]
pub const fn clamp_progress(raw: f64) -> f64 {
    if raw.is_finite() { raw.clamp(0.0, 1.0) } else { 0.0 }
}

/// Pure CRDT LWW + equal-HLC join-semilattice merge for `entry_state`.
///
/// - When `incoming.hlc > existing.hlc`: incoming wins.
/// - When `incoming.hlc == existing.hlc`: monotonic join (`Dismissed > Unread > None`,
///   `max(read_at)`, `max(progress)`, `max(interaction_updated_at)`).
/// - When `incoming.hlc < existing.hlc`: existing wins.
/// - Guarantees `read_at` is populated whenever the resulting `inbox_state` is `Dismissed`.
#[must_use]
pub fn resolve_merged_entry_state(
    existing: &EntryStateSnapshot,
    incoming: &EntryStateSnapshot,
    now: DateTime<Utc>,
) -> EntryStateSnapshot {
    let ex_progress = clamp_progress(existing.progress);
    let inc_progress = clamp_progress(incoming.progress);
    let inc_inter = clamp_incoming_timestamp_opt(incoming.interaction_updated_at, now)
        .map(|ts| ts.trunc_subsecs(3));
    let inc_read_at = clamp_incoming_timestamp_opt(incoming.read_at, now);

    let (inbox_state, raw_read_at, progress, interaction_updated_at, hlc) =
        match incoming.hlc.as_str().cmp(existing.hlc.as_str()) {
            Ordering::Greater => {
                (incoming.inbox_state, inc_read_at, inc_progress, inc_inter, incoming.hlc.clone())
            }
            Ordering::Equal => (
                join_inbox_state(existing.inbox_state, incoming.inbox_state),
                existing.read_at.max(inc_read_at),
                ex_progress.max(inc_progress),
                existing.interaction_updated_at.max(inc_inter),
                existing.hlc.clone(),
            ),
            Ordering::Less => (
                existing.inbox_state,
                existing.read_at,
                ex_progress,
                existing.interaction_updated_at,
                existing.hlc.clone(),
            ),
        };

    let read_at = if inbox_state == InboxState::Dismissed {
        raw_read_at.or(existing.read_at).or(inc_read_at).or(interaction_updated_at).or(Some(now))
    } else {
        raw_read_at
    };
    EntryStateSnapshot { inbox_state, read_at, progress, interaction_updated_at, hlc }
}

/// Monotonic join over `InboxState` (`Dismissed > Unread > None`).
#[must_use]
pub const fn join_inbox_state(a: InboxState, b: InboxState) -> InboxState {
    if matches!(a, InboxState::Dismissed) || matches!(b, InboxState::Dismissed) {
        InboxState::Dismissed
    } else if matches!(a, InboxState::Unread) || matches!(b, InboxState::Unread) {
        InboxState::Unread
    } else {
        InboxState::None
    }
}

/// Merges a duplicate entry's reader state into a canonical entry's reader state during duplicate
/// folding (`merged_into_id`).
#[must_use]
pub fn merge_entry_state_for_duplicate_fold(
    canonical: Option<&EntryStateSnapshot>,
    duplicate: &EntryStateSnapshot,
) -> (InboxState, Option<DateTime<Utc>>, f64, Option<DateTime<Utc>>) {
    let dup_progress = clamp_progress(duplicate.progress);
    let Some(canon) = canonical else {
        return (
            duplicate.inbox_state,
            duplicate.read_at,
            dup_progress,
            duplicate.interaction_updated_at,
        );
    };
    let inbox = join_inbox_state(canon.inbox_state, duplicate.inbox_state);
    let read_at = match (canon.read_at, duplicate.read_at) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (Some(a), None) => Some(a),
        (None, Some(b)) => Some(b),
        (None, None) => None,
    };
    let progress = clamp_progress(canon.progress).max(dup_progress);
    let inter_ts = canon.interaction_updated_at.max(duplicate.interaction_updated_at);
    (inbox, read_at, progress, inter_ts)
}

/// Chooses the merged title between an existing entry row and an incoming sync record, replacing
/// URL-placeholder titles and preferring base album titles over edition-suffixed variants.
#[must_use]
pub fn compute_merged_entry_title(
    existing_title: &str,
    existing_url: Option<&str>,
    inc_url: Option<&str>,
    target_kind: EntryKind,
    final_title: &str,
    inc_newer: bool,
) -> String {
    let ex_is_url_placeholder = existing_title.trim().is_empty()
        || existing_url.is_some_and(|u| existing_title == u)
        || inc_url.is_some_and(|u| existing_title == u.trim());
    let inc_is_url_placeholder = inc_url.is_some_and(|u| final_title == u.trim());
    if (ex_is_url_placeholder || (inc_newer && !inc_is_url_placeholder)) && !final_title.is_empty()
    {
        final_title.to_owned()
    } else if target_kind == EntryKind::Album {
        let cur_stripped = strip_album_edition_suffix(existing_title);
        let inc_stripped = strip_album_edition_suffix(final_title);
        if existing_title.len() > cur_stripped.len() && final_title.len() == inc_stripped.len() {
            final_title.to_owned()
        } else {
            existing_title.to_owned()
        }
    } else {
        existing_title.to_owned()
    }
}

/// Merges incoming JSON metadata object keys into `existing_json`, overwriting existing keys only
/// when `inc_newer` is true and backfilling missing keys regardless.
#[must_use]
pub fn merge_entry_metadata_json(
    existing_json: &str,
    incoming_meta: Option<&serde_json::Value>,
    inc_newer: bool,
) -> String {
    if let Some(serde_json::Value::Object(inc_map)) = incoming_meta
        && !inc_map.is_empty()
    {
        let mut cur_obj = serde_json::from_str::<serde_json::Value>(existing_json)
            .ok()
            .and_then(|v| v.as_object().cloned())
            .unwrap_or_default();
        for (k, v) in inc_map {
            if inc_newer || !cur_obj.contains_key(k) {
                cur_obj.insert(k.clone(), v.clone());
            }
        }
        serde_json::Value::Object(cur_obj).to_string()
    } else {
        existing_json.to_owned()
    }
}

/// Picks the longer non-blank string between `existing` and `incoming`.
#[must_use]
pub fn pick_richer_text<'a>(
    existing: Option<&'a str>,
    incoming: Option<&'a str>,
) -> Option<&'a str> {
    match (
        existing.map(str::trim).filter(|s| !s.is_empty()),
        incoming.map(str::trim).filter(|s| !s.is_empty()),
    ) {
        (Some(ex), Some(inc)) => {
            if inc.len() > ex.len() {
                incoming
            } else {
                existing
            }
        }
        (Some(_), None) => existing,
        (None, Some(_)) => incoming,
        (None, None) => None,
    }
}

/// Rewrites a comparison ordering after merging `duplicate_id` into `canonical_id`, preserving the
/// first occurrence of each distinct entry ID and dropping emptied tie groups.
#[must_use]
pub fn rewrite_ordering_after_merge(
    ordering: &[Vec<String>],
    duplicate_id: &str,
    canonical_id: &str,
) -> Vec<Vec<String>> {
    let mut seen = HashSet::new();
    let mut out = Vec::with_capacity(ordering.len());
    for group in ordering {
        let mut new_group = Vec::new();
        for id in group {
            let mapped = if id == duplicate_id { canonical_id } else { id.as_str() };
            if seen.insert(mapped.to_owned()) {
                new_group.push(mapped.to_owned());
            }
        }
        if !new_group.is_empty() {
            out.push(new_group);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;

    #[test]
    #[allow(clippy::unwrap_used)]
    fn test_equal_hlc_entry_state_merge_is_commutative_and_clamped() {
        let t1 = Utc.with_ymd_and_hms(2026, 6, 1, 10, 0, 0).unwrap();
        let t2 = Utc.with_ymd_and_hms(2026, 6, 1, 11, 0, 0).unwrap();
        let now = Utc.with_ymd_and_hms(2026, 6, 1, 12, 0, 0).unwrap();
        let hlc = "001780315200000-0001-peer-a".to_owned();

        let a = EntryStateSnapshot {
            inbox_state: InboxState::Unread,
            read_at: None,
            progress: 0.75,
            interaction_updated_at: Some(t1),
            hlc: hlc.clone(),
        };
        let b = EntryStateSnapshot {
            inbox_state: InboxState::Dismissed,
            read_at: Some(t2),
            progress: 1.8,
            interaction_updated_at: Some(t2),
            hlc,
        };

        let ab = resolve_merged_entry_state(&a, &b, now);
        let ba = resolve_merged_entry_state(&b, &a, now);
        assert_eq!(ab, ba);
        assert_eq!(ab.inbox_state, InboxState::Dismissed);
        assert_eq!(ab.read_at, Some(t2));
        assert!((ab.progress - 1.0).abs() <= f64::EPSILON);
    }

    #[test]
    fn test_rewrite_ordering_after_merge_dedups_canonical() {
        let ord = vec![
            vec!["dup".to_owned(), "other".to_owned()],
            vec!["canon".to_owned()],
            vec!["third".to_owned()],
        ];
        assert_eq!(
            rewrite_ordering_after_merge(&ord, "dup", "canon"),
            vec![vec!["canon".to_owned(), "other".to_owned()], vec!["third".to_owned()]]
        );
    }
}
