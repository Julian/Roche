use std::{cmp::Ordering, collections::HashSet};

use chrono::{DateTime, SubsecRound, Utc};

use crate::{
    boards::merge_discussions_json_arrays,
    domain::{EntryKind, InboxState},
    hlc::{clamp_incoming_timestamp, clamp_incoming_timestamp_opt},
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

/// Pure value representation of a board item's user interaction state for CRDT merging.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BoardItemStateSnapshot {
    pub seen_at: Option<DateTime<Utc>>,
    pub dismissed_at: Option<DateTime<Utc>>,
    pub last_seen_in_snapshot_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub hlc: String,
}

/// Pure CRDT LWW + equal-HLC commutative join for `board_item_states`.
///
/// - `last_seen_in_snapshot_at` is always monotonic `max(existing, clamped_incoming)`.
/// - When `incoming.hlc > existing.hlc`: incoming wins (`seen_at`, `dismissed_at`, `updated_at`,
///   `hlc`).
/// - When `incoming.hlc == existing.hlc`: commutative join (`max(seen_at)`, `max(dismissed_at)`,
///   `max(updated_at)`).
/// - When `incoming.hlc < existing.hlc`: existing wins (`seen_at`, `dismissed_at`, `updated_at`,
///   `hlc`).
#[must_use]
pub fn resolve_merged_board_item_state(
    existing: &BoardItemStateSnapshot,
    incoming: &BoardItemStateSnapshot,
    now: DateTime<Utc>,
) -> BoardItemStateSnapshot {
    let inc_seen = clamp_incoming_timestamp_opt(incoming.seen_at, now);
    let inc_dismissed = clamp_incoming_timestamp_opt(incoming.dismissed_at, now);
    let inc_last_seen = clamp_incoming_timestamp(incoming.last_seen_in_snapshot_at, now);
    let inc_updated = clamp_incoming_timestamp(incoming.updated_at, now);
    let last_seen_in_snapshot_at = existing.last_seen_in_snapshot_at.max(inc_last_seen);

    match incoming.hlc.as_str().cmp(existing.hlc.as_str()) {
        Ordering::Greater => BoardItemStateSnapshot {
            seen_at: inc_seen,
            dismissed_at: inc_dismissed,
            last_seen_in_snapshot_at,
            updated_at: inc_updated,
            hlc: incoming.hlc.clone(),
        },
        Ordering::Equal => BoardItemStateSnapshot {
            seen_at: existing.seen_at.max(inc_seen),
            dismissed_at: existing.dismissed_at.max(inc_dismissed),
            last_seen_in_snapshot_at,
            updated_at: existing.updated_at.max(inc_updated),
            hlc: existing.hlc.clone(),
        },
        Ordering::Less => BoardItemStateSnapshot {
            seen_at: existing.seen_at,
            dismissed_at: existing.dismissed_at,
            last_seen_in_snapshot_at,
            updated_at: existing.updated_at,
            hlc: existing.hlc.clone(),
        },
    }
}

/// Merges incoming JSON metadata object keys into `existing_json`, overwriting existing keys only
/// when `inc_newer` is true and backfilling missing keys regardless.
///
/// The `"discussions"` array performs a grow-only deduplicated set-union by canonicalized `url`
/// regardless of `inc_newer`, preserving existing order and enriching blank fields from incoming.
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
            if k == "discussions"
                && let serde_json::Value::Array(inc_arr) = v
            {
                let ex_arr = cur_obj
                    .get("discussions")
                    .and_then(serde_json::Value::as_array)
                    .map_or(&[][..], Vec::as_slice);
                let union = merge_discussions_json_arrays(ex_arr, inc_arr);
                if !union.is_empty() || !cur_obj.contains_key(k) {
                    cur_obj.insert(k.clone(), serde_json::Value::Array(union));
                }
                continue;
            }
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

    #[test]
    #[allow(clippy::unwrap_used)]
    fn test_merge_entry_metadata_json_unions_discussions_regardless_of_hlc_order() {
        let existing = serde_json::json!({
            "want_notes": "existing note",
            "discussions": [
                {
                    "source": "hacker_news",
                    "url": "https://news.ycombinator.com/item?id=101",
                    "external_id": "101",
                    "label": ""
                }
            ]
        })
        .to_string();
        let incoming = serde_json::json!({
            "want_notes": "older incoming note",
            "discussions": [
                {
                    "source": "hacker_news",
                    "url": "HTTPS://NEWS.YCOMBINATOR.COM:443/item?id=101",
                    "external_id": "101",
                    "label": "Hacker News"
                },
                {
                    "source": "hacker_news",
                    "url": "https://news.ycombinator.com/item?id=202",
                    "external_id": "202",
                    "label": "Hacker News"
                }
            ]
        });

        // Even with `inc_newer = false`, discussions perform a deduplicated set-union and enrich blank label
        let merged_str = merge_entry_metadata_json(&existing, Some(&incoming), false);
        let merged: serde_json::Value = serde_json::from_str(&merged_str).unwrap();
        assert_eq!(merged["want_notes"], "existing note");
        let discs = merged["discussions"].as_array().unwrap();
        assert_eq!(discs.len(), 2);
        assert_eq!(discs[0]["url"], "https://news.ycombinator.com/item?id=101");
        assert_eq!(discs[0]["label"], "Hacker News");
        assert_eq!(discs[1]["url"], "https://news.ycombinator.com/item?id=202");
    }

    #[test]
    #[allow(clippy::unwrap_used)]
    fn test_resolve_merged_board_item_state_lww_and_commutative_equal_hlc_join() {
        let t1 = Utc.with_ymd_and_hms(2026, 6, 1, 10, 0, 0).unwrap();
        let t2 = Utc.with_ymd_and_hms(2026, 6, 1, 11, 0, 0).unwrap();
        let t3 = Utc.with_ymd_and_hms(2026, 6, 1, 11, 30, 0).unwrap();
        let now = Utc.with_ymd_and_hms(2026, 6, 1, 12, 0, 0).unwrap();

        let same_hlc = "001780315200000-0001-peer-a".to_owned();
        let a = BoardItemStateSnapshot {
            seen_at: Some(t1),
            dismissed_at: None,
            last_seen_in_snapshot_at: t2,
            updated_at: t1,
            hlc: same_hlc.clone(),
        };
        let b = BoardItemStateSnapshot {
            seen_at: None,
            dismissed_at: Some(t2),
            last_seen_in_snapshot_at: t1,
            updated_at: t2,
            hlc: same_hlc,
        };
        let ab = resolve_merged_board_item_state(&a, &b, now);
        let ba = resolve_merged_board_item_state(&b, &a, now);
        assert_eq!(ab, ba);
        assert_eq!(ab.seen_at, Some(t1));
        assert_eq!(ab.dismissed_at, Some(t2));
        assert_eq!(ab.last_seen_in_snapshot_at, t2);
        assert_eq!(ab.updated_at, t2);

        // Higher HLC undismiss wins dismissed_at = None while keeping max(last_seen_in_snapshot_at)
        let undismiss = BoardItemStateSnapshot {
            seen_at: Some(t1),
            dismissed_at: None,
            last_seen_in_snapshot_at: t1,
            updated_at: t3,
            hlc: "001780317000000-0000-peer-b".to_owned(),
        };
        let res = resolve_merged_board_item_state(&ab, &undismiss, now);
        assert_eq!(res.dismissed_at, None);
        assert_eq!(res.last_seen_in_snapshot_at, t2);
        assert_eq!(res.hlc, undismiss.hlc);

        // Lower HLC still advances last_seen_in_snapshot_at without overwriting dismissed_at
        let older_with_fresh_snap = BoardItemStateSnapshot {
            seen_at: None,
            dismissed_at: Some(t1),
            last_seen_in_snapshot_at: t3,
            updated_at: t1,
            hlc: "001780310000000-0000-peer-c".to_owned(),
        };
        let res2 = resolve_merged_board_item_state(&res, &older_with_fresh_snap, now);
        assert_eq!(res2.dismissed_at, None);
        assert_eq!(res2.last_seen_in_snapshot_at, t3);
        assert_eq!(res2.hlc, res.hlc);
    }
}
