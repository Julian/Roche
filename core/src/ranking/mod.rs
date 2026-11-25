// Standard IEEE-754 floating-point evaluation order (without hardware FMA fusion) is preserved
// intentionally so EP inference matches Python (`choix`) and Kotlin (`EpRankingEngine`) to 1e-12.
#![allow(clippy::similar_names, clippy::suboptimal_flops, clippy::suspicious_operation_groupings)]

pub mod board;
pub use board::{RankBoard, RankBoardRow};

use std::{
    cmp::Ordering,
    collections::{HashMap, HashSet},
    hash::BuildHasher,
};

use rand::{Rng, RngExt};
use serde::{Deserialize, Serialize};

pub use crate::domain::MAX_FOCUSED_ROUNDS;

pub const PRIOR_ALPHA: f64 = 1.0;
pub const COLD_START_VARIANCE: f64 = 1.0 / PRIOR_ALPHA;
pub const MIN_K: usize = 2;
pub const MAX_K_DEFAULT: usize = 5;
pub const MAX_COMPARISON_WIDTH: usize = 64;
pub const MIN_CONFUSABILITY: f64 = 0.15;
pub const RANDOM_SEED_EVERY: usize = 5;
pub const N_QUANTILES: usize = 5;
pub const TIER_LETTERS: [char; N_QUANTILES] = ['D', 'C', 'B', 'A', 'S'];

const THRESHOLD: f64 = 1e-6;
pub const MEAN_TIE_EPSILON: f64 = 1e-5;
const SQRT2: f64 = std::f64::consts::SQRT_2;
const SQRT_2_OVER_PI: f64 = 0.797_884_560_802_865_4;
const SQRT_2PI: f64 = 2.506_628_274_631_000_2;

const LOG_PHI_CS: [f64; 14] = [
    0.000_482_04,
    -0.001_429_06,
    0.001_320_024_317_4,
    0.000_946_158_903_2,
    -0.004_556_333_980_2,
    0.005_569_646_491_38,
    0.001_259_939_617_621_16,
    -0.016_215_753_788_354_04,
    0.026_296_515_210_574_65,
    -0.001_829_764_677_455_021,
    2.0 * (1.0 - std::f64::consts::PI / 3.0),
    (4.0 - std::f64::consts::PI) / 3.0,
    1.0,
    1.0,
];

const LOG_PHI_RS: [f64; 5] = [
    1.275_366_644_729_966,
    5.019_049_726_784_267,
    6.160_209_853_109_63,
    7.409_740_605_964_742,
    2.978_865_626_393_993,
];

const LOG_PHI_QS: [f64; 6] = [
    2.260_528_520_767_327,
    9.396_034_016_235_054,
    12.048_951_927_855_129,
    17.081_440_747_466_004,
    9.608_965_327_192_788,
    3.369_075_206_982_753,
];

const LOGIT_LAMBDAS: [f64; 5] =
    [SQRT2 * 0.44, SQRT2 * 0.41, SQRT2 * 0.40, SQRT2 * 0.39, SQRT2 * 0.36];

const LOGIT_CS: [f64; 5] = [
    1.146_480_988_574_439e2,
    -1.508_871_030_070_582e3,
    2.676_085_036_831_241e3,
    -1.356_294_962_039_222e3,
    7.543_285_642_111_85e1,
];

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Score {
    pub mean: f64,
    pub variance: f64,
}

impl Default for Score {
    fn default() -> Self {
        Self { mean: 0.0, variance: COLD_START_VARIANCE }
    }
}

#[must_use]
pub const fn tier_label(quantile: usize) -> char {
    if quantile < N_QUANTILES { TIER_LETTERS[quantile] } else { 'S' }
}

/// Validates a k-way comparison ordering (list of non-empty tie groups with distinct IDs,
/// `MIN_K <= total <= MAX_COMPARISON_WIDTH`).
pub fn validate_ordering(ordering: &[Vec<String>]) -> Result<(), String> {
    let mut seen = HashSet::new();
    let mut total = 0usize;
    for group in ordering {
        if group.is_empty() {
            return Err("tie groups must be non-empty".to_owned());
        }
        for raw_id in group {
            let trimmed = raw_id.trim();
            if trimmed.is_empty() {
                return Err("entry IDs in ordering cannot be empty".to_owned());
            }
            if !seen.insert(trimmed) {
                return Err(format!("ordering must not repeat entry ID '{trimmed}'"));
            }
            total += 1;
            if total > MAX_COMPARISON_WIDTH {
                return Err(format!(
                    "a comparison cannot include more than {MAX_COMPARISON_WIDTH} entries"
                ));
            }
        }
    }
    if total < MIN_K {
        return Err(format!("a comparison needs at least {MIN_K} entries"));
    }
    Ok(())
}

/// Decomposes a k-way ordering into `(winner, loser)` pairs; ties emit both directions.
#[must_use]
pub fn to_pairs(ordering: &[Vec<String>]) -> Vec<(String, String)> {
    let mut pairs = Vec::new();
    for (gi, winners) in ordering.iter().enumerate() {
        for losers in &ordering[gi + 1..] {
            for w in winners {
                for l in losers {
                    if w != l {
                        pairs.push((w.clone(), l.clone()));
                    }
                }
            }
        }
    }
    for group in ordering {
        for (i, a) in group.iter().enumerate() {
            for b in &group[i + 1..] {
                if a != b {
                    pairs.push((a.clone(), b.clone()));
                    pairs.push((b.clone(), a.clone()));
                }
            }
        }
    }
    pairs
}

/// Canonical connected component extracted from comparison orderings for EP inference.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct EpComponent {
    /// Lexicographically sorted item IDs belonging to this connected component.
    pub items: Vec<String>,
    /// Directed `(winner_idx, loser_idx)` pairs in canonical ordering-traversal order.
    pub pairs: Vec<(usize, usize)>,
}

/// Extracts the independent connected components intersecting `item_ids` from global `orderings`.
///
/// Each returned [`EpComponent`] is self-contained and canonically ordered, so callers can cache
/// solved component scores across comparison revisions without re-solving unrelated components.
#[must_use]
pub fn extract_ep_components(
    item_ids: &[String],
    orderings: &[Vec<Vec<String>>],
) -> Vec<EpComponent> {
    if item_ids.is_empty() || orderings.is_empty() {
        return Vec::new();
    }

    let mut raw_pairs: Vec<(&str, &str)> = Vec::new();
    for ord in orderings {
        let width: usize = ord.iter().map(Vec::len).sum();
        if width > MAX_COMPARISON_WIDTH {
            tracing::warn!(
                width,
                max_width = MAX_COMPARISON_WIDTH,
                "Ignoring oversized comparison ordering during EP inference"
            );
            continue;
        }
        for (gi, winners) in ord.iter().enumerate() {
            for losers in &ord[gi + 1..] {
                for w in winners {
                    for l in losers {
                        if w != l {
                            raw_pairs.push((w.as_str(), l.as_str()));
                        }
                    }
                }
            }
        }
        for group in ord {
            for (i, a) in group.iter().enumerate() {
                for b in &group[i + 1..] {
                    if a != b {
                        raw_pairs.push((a.as_str(), b.as_str()));
                        raw_pairs.push((b.as_str(), a.as_str()));
                    }
                }
            }
        }
    }
    if raw_pairs.is_empty() {
        return Vec::new();
    }

    let mut adj: HashMap<&str, Vec<&str>> = HashMap::new();
    for &(w, l) in &raw_pairs {
        adj.entry(w).or_default().push(l);
        adj.entry(l).or_default().push(w);
    }

    let target_set: HashSet<&str> = item_ids.iter().map(String::as_str).collect();
    let mut sorted_targets: Vec<&str> = target_set.into_iter().collect();
    sorted_targets.sort_unstable();

    let mut visited: HashSet<&str> = HashSet::new();
    let mut comp_items: Vec<Vec<&str>> = Vec::new();
    let mut node_loc: HashMap<&str, (usize, usize)> = HashMap::new();

    for seed in sorted_targets {
        if visited.contains(seed) || !adj.contains_key(seed) {
            continue;
        }
        let mut comp: Vec<&str> = Vec::new();
        let mut stack = vec![seed];
        visited.insert(seed);
        while let Some(cur) = stack.pop() {
            comp.push(cur);
            if let Some(neighbors) = adj.get(cur) {
                for &nb in neighbors {
                    if visited.insert(nb) {
                        stack.push(nb);
                    }
                }
            }
        }
        comp.sort_unstable();
        let comp_idx = comp_items.len();
        for (local_idx, &id) in comp.iter().enumerate() {
            node_loc.insert(id, (comp_idx, local_idx));
        }
        comp_items.push(comp);
    }

    if comp_items.is_empty() {
        return Vec::new();
    }

    let mut comp_pairs_list: Vec<Vec<(usize, usize)>> = vec![Vec::new(); comp_items.len()];
    for &(w, l) in &raw_pairs {
        if let (Some(&(c_w, wi)), Some(&(c_l, li))) = (node_loc.get(w), node_loc.get(l))
            && c_w == c_l
            && wi != li
        {
            comp_pairs_list[c_w].push((wi, li));
        }
    }

    comp_items
        .into_iter()
        .zip(comp_pairs_list)
        .filter(|(_, pairs)| !pairs.is_empty())
        .map(|(items, pairs)| EpComponent {
            items: items.into_iter().map(ToOwned::to_owned).collect(),
            pairs,
        })
        .collect()
}

/// Runs Expectation Propagation on a single extracted [`EpComponent`], returning posterior
/// [`Score`]s aligned 1-to-1 with `component.items` (or `None` if EP aborts/fails to converge).
#[must_use]
pub fn solve_ep_component(component: &EpComponent) -> Option<Vec<Score>> {
    let n = component.items.len();
    if n == 0
        || component.pairs.is_empty()
        || component.pairs.iter().any(|&(a, b)| a == b || a >= n || b >= n)
    {
        return None;
    }
    let (means, variances) = ep_pairwise_component(n, &component.pairs, PRIOR_ALPHA, 100)?;
    Some(
        means
            .into_iter()
            .zip(variances)
            .map(|(m, variance)| {
                let mean = if m.abs() < 1e-12 { 0.0 } else { m };
                Score { mean, variance }
            })
            .collect(),
    )
}

/// Runs Expectation Propagation over the connected components intersecting `item_ids`.
///
/// Passing all global quality `orderings` allows transitive calibration across items outside
/// `item_ids`, while disconnected components solve independently in `O(sum n_c^3)`.
#[must_use]
pub fn infer_scores(item_ids: &[String], orderings: &[Vec<Vec<String>>]) -> HashMap<String, Score> {
    let mut scores: HashMap<String, Score> =
        item_ids.iter().map(|id| (id.clone(), Score::default())).collect();
    if item_ids.is_empty() || orderings.is_empty() {
        return scores;
    }

    let target_set: HashSet<&str> = item_ids.iter().map(String::as_str).collect();
    for component in extract_ep_components(item_ids, orderings) {
        if let Some(comp_scores) = solve_ep_component(&component) {
            for (id, score) in component.items.into_iter().zip(comp_scores) {
                if target_set.contains(id.as_str()) {
                    scores.insert(id, score);
                }
            }
        }
    }

    scores
}

/// Posterior probability that the apparently-worse item is actually better (`0.5` = uncertain, `0` = separated).
#[must_use]
pub fn confusability(a: Score, b: Score) -> f64 {
    let diff_var = a.variance + b.variance;
    if diff_var <= 0.0 {
        return if (a.mean - b.mean).abs() <= f64::EPSILON { 0.5 } else { 0.0 };
    }
    let z = (a.mean - b.mean).abs() / diff_var.sqrt();
    0.5 * cody_erfc(z / SQRT2)
}

/// Selected comparison cluster in both display order and selection-priority order.
///
/// `ordered_ids` is sorted by descending posterior mean, while `priority_ids` preserves
/// `[seed, most_confusable, ...]` so UIs can safely shrink cluster width without dropping
/// the seed/focus item or biasing against lower-ranked items.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SelectedCluster {
    pub ordered_ids: Vec<String>,
    pub priority_ids: Vec<String>,
}

/// Selects a cluster of up to `max_k` items to compare next, returning `< 2` items when settled.
pub fn select_cluster<R: Rng + ?Sized, S: BuildHasher>(
    scores: &HashMap<String, Score, S>,
    item_ids: &[String],
    max_k: usize,
    force_random_seed: bool,
    seed_pool: Option<&[String]>,
    rng: &mut R,
) -> Vec<String> {
    select_cluster_prioritized(scores, item_ids, max_k, force_random_seed, seed_pool, rng)
        .ordered_ids
}

/// Selects a cluster of up to `max_k` items along with its selection-priority order
/// (`[seed, highest confusability neighbor, ...]`).
pub fn select_cluster_prioritized<R: Rng + ?Sized, S: BuildHasher>(
    scores: &HashMap<String, Score, S>,
    item_ids: &[String],
    max_k: usize,
    force_random_seed: bool,
    seed_pool: Option<&[String]>,
    rng: &mut R,
) -> SelectedCluster {
    select_cluster_prioritized_generic(scores, item_ids, max_k, force_random_seed, seed_pool, rng)
}

/// Zero-copy slice variant of [`select_cluster_prioritized`] accepting `&[&str]` candidate pools
/// so filtered/retry pools avoid cloning thousands of item ID strings.
pub fn select_cluster_prioritized_refs<R: Rng + ?Sized, S: BuildHasher>(
    scores: &HashMap<String, Score, S>,
    item_ids: &[&str],
    max_k: usize,
    force_random_seed: bool,
    seed_pool: Option<&[String]>,
    rng: &mut R,
) -> SelectedCluster {
    select_cluster_prioritized_generic(scores, item_ids, max_k, force_random_seed, seed_pool, rng)
}

fn insert_top_k_desc<'a>(
    buf: &mut Vec<(f64, f64, &'a str)>,
    limit: usize,
    entry: (f64, f64, &'a str),
) {
    if limit == 0 {
        return;
    }
    let is_greater = |a: &(f64, f64, &str), b: &(f64, f64, &str)| {
        a.0.total_cmp(&b.0).then_with(|| a.1.total_cmp(&b.1)).is_gt()
    };
    if buf.len() == limit && !is_greater(&entry, &buf[limit - 1]) {
        return;
    }
    let pos = buf.partition_point(|x| !is_greater(&entry, x));
    if pos < limit {
        if buf.len() == limit {
            buf.pop();
        }
        buf.insert(pos, entry);
    }
}

fn select_cluster_prioritized_generic<T: AsRef<str>, R: Rng + ?Sized, S: BuildHasher>(
    scores: &HashMap<String, Score, S>,
    item_ids: &[T],
    max_k: usize,
    force_random_seed: bool,
    seed_pool: Option<&[String]>,
    rng: &mut R,
) -> SelectedCluster {
    let cap = max_k.max(MIN_K);
    if item_ids.is_empty() {
        return SelectedCluster { ordered_ids: Vec::new(), priority_ids: Vec::new() };
    }

    let pool_set: Option<HashSet<&str>> =
        seed_pool.map(|pool| pool.iter().map(String::as_str).collect());

    let seed_id: &str = if force_random_seed {
        let top_k = cap.max(MAX_K_DEFAULT);
        let mut top: Vec<(f64, f64, &str)> = Vec::with_capacity(top_k);
        for raw_id in item_ids {
            let id = raw_id.as_ref();
            if pool_set.as_ref().is_some_and(|set| !set.contains(id)) {
                continue;
            }
            let var = scores.get(id).copied().unwrap_or_default().variance;
            insert_top_k_desc(&mut top, top_k, (var, rng.random::<f64>(), id));
        }
        if top.is_empty() {
            return SelectedCluster { ordered_ids: Vec::new(), priority_ids: Vec::new() };
        }
        let pick = rng.random_range(0..top.len());
        top[pick].2
    } else {
        let mut best: Option<(f64, f64, &str)> = None;
        for raw_id in item_ids {
            let id = raw_id.as_ref();
            if pool_set.as_ref().is_some_and(|set| !set.contains(id)) {
                continue;
            }
            let var = scores.get(id).copied().unwrap_or_default().variance;
            let tie = rng.random::<f64>();
            if best.is_none_or(|b| var.total_cmp(&b.0).then_with(|| tie.total_cmp(&b.1)).is_gt()) {
                best = Some((var, tie, id));
            }
        }
        match best {
            Some((_, _, id)) => id,
            None => return SelectedCluster { ordered_ids: Vec::new(), priority_ids: Vec::new() },
        }
    };

    let seed_score = scores.get(seed_id).copied().unwrap_or_default();
    let default_conf = confusability(seed_score, Score::default());
    let needed = cap.saturating_sub(1);
    let mut top_neighbors: Vec<(f64, f64, &str)> = Vec::with_capacity(needed);
    if needed > 0 {
        for raw_id in item_ids {
            let id = raw_id.as_ref();
            if id == seed_id {
                continue;
            }
            let conf = match scores.get(id) {
                Some(&cand_score) => confusability(seed_score, cand_score),
                None => default_conf,
            };
            if conf < MIN_CONFUSABILITY {
                continue;
            }
            insert_top_k_desc(&mut top_neighbors, needed, (conf, rng.random::<f64>(), id));
        }
    }

    let mut priority_ids = Vec::with_capacity(1 + top_neighbors.len());
    priority_ids.push(seed_id.to_owned());
    for (_, _, cand_id) in top_neighbors {
        priority_ids.push(cand_id.to_owned());
    }

    let mut ordered_ids = priority_ids.clone();
    ordered_ids.sort_by(|a, b| {
        let sa = scores.get(a).copied().unwrap_or_default().mean;
        let sb = scores.get(b).copied().unwrap_or_default().mean;
        sb.total_cmp(&sa).then_with(|| a.cmp(b))
    });
    SelectedCluster { ordered_ids, priority_ids }
}

/// Quantile bucket result separating compared items from uncompared items (which all share `mean = 0.0`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct QuantilesWithUncompared {
    pub compared: HashMap<String, usize>,
    pub uncompared: Option<usize>,
}

enum WeightedQuantileRun<'a> {
    Compared(&'a str, f64),
    Uncompared(usize),
}

impl WeightedQuantileRun<'_> {
    const fn mean(&self) -> f64 {
        match *self {
            Self::Compared(_, m) => m,
            Self::Uncompared(_) => 0.0,
        }
    }

    const fn weight(&self) -> usize {
        match *self {
            Self::Compared(_, _) => 1,
            Self::Uncompared(w) => w,
        }
    }
}

/// Maps posterior means into `n_quantiles` monotonic buckets (`0..n_quantiles - 1`) without
/// materializing `uncompared_count` zero-mean entries, producing bit-for-bit identical buckets to
/// [`rescale_to_quantiles`].
#[must_use]
#[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation, clippy::cast_sign_loss)]
pub fn rescale_to_quantiles_with_uncompared<S: BuildHasher>(
    compared_scores: &HashMap<String, Score, S>,
    uncompared_count: usize,
    n_quantiles: usize,
) -> QuantilesWithUncompared {
    let total = compared_scores.len().saturating_add(uncompared_count);
    if total == 0 || n_quantiles == 0 {
        return QuantilesWithUncompared::default();
    }
    if total == 1 {
        let top = n_quantiles - 1;
        return compared_scores.keys().next().map_or_else(
            || QuantilesWithUncompared { compared: HashMap::new(), uncompared: Some(top) },
            |only_id| {
                let mut compared = HashMap::with_capacity(1);
                compared.insert(only_id.clone(), top);
                QuantilesWithUncompared { compared, uncompared: None }
            },
        );
    }

    let mut sorted: Vec<(&str, f64)> =
        compared_scores.iter().map(|(id, sc)| (id.as_str(), sc.mean)).collect();
    sorted.sort_by(|a, b| a.1.total_cmp(&b.1).then_with(|| a.0.cmp(b.0)));

    let mut runs: Vec<WeightedQuantileRun<'_>> =
        Vec::with_capacity(sorted.len() + usize::from(uncompared_count > 0));
    let mut inserted_uncompared = uncompared_count == 0;
    for (id, mean) in sorted {
        if !inserted_uncompared && mean.total_cmp(&0.0) != Ordering::Less {
            runs.push(WeightedQuantileRun::Uncompared(uncompared_count));
            inserted_uncompared = true;
        }
        runs.push(WeightedQuantileRun::Compared(id, mean));
    }
    if !inserted_uncompared {
        runs.push(WeightedQuantileRun::Uncompared(uncompared_count));
    }

    let mut compared_buckets = HashMap::with_capacity(compared_scores.len());
    let mut uncompared_bucket = None;
    let mut idx = 0usize;
    let mut offset = 0usize;
    while idx < runs.len() {
        let base_mean = runs[idx].mean();
        let mut end = idx + 1;
        let mut group_weight = runs[idx].weight();
        while end < runs.len() && (runs[end].mean() - base_mean).abs() <= MEAN_TIE_EPSILON {
            group_weight += runs[end].weight();
            end += 1;
        }
        let midpoint = (offset + offset + group_weight - 1) as f64 / 2.0;
        let raw_bucket = (midpoint / (total - 1) as f64 * n_quantiles as f64).floor() as usize;
        let bucket = raw_bucket.min(n_quantiles - 1);
        for run in &runs[idx..end] {
            match *run {
                WeightedQuantileRun::Compared(id, _) => {
                    compared_buckets.insert(id.to_owned(), bucket);
                }
                WeightedQuantileRun::Uncompared(_) => {
                    uncompared_bucket = Some(bucket);
                }
            }
        }
        offset += group_weight;
        idx = end;
    }

    QuantilesWithUncompared { compared: compared_buckets, uncompared: uncompared_bucket }
}

/// Maps posterior means into `n_quantiles` monotonic buckets (`0..n_quantiles - 1`).
#[must_use]
#[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation, clippy::cast_sign_loss)]
pub fn rescale_to_quantiles<S: BuildHasher>(
    scores: &HashMap<String, Score, S>,
    n_quantiles: usize,
) -> HashMap<String, usize> {
    if scores.is_empty() || n_quantiles == 0 {
        return HashMap::new();
    }
    let mut sorted_ids: Vec<&String> = scores.keys().collect();
    sorted_ids.sort_by(|a, b| {
        let ma = scores[*a].mean;
        let mb = scores[*b].mean;
        ma.total_cmp(&mb).then_with(|| a.cmp(b))
    });

    let n = sorted_ids.len();
    if n == 1 {
        let mut single = HashMap::with_capacity(1);
        single.insert(sorted_ids[0].clone(), n_quantiles - 1);
        return single;
    }

    let mut buckets = HashMap::with_capacity(n);
    let mut i = 0usize;
    while i < n {
        let base_mean = scores[sorted_ids[i]].mean;
        let mut j = i + 1;
        while j < n && (scores[sorted_ids[j]].mean - base_mean).abs() <= MEAN_TIE_EPSILON {
            j += 1;
        }
        let midpoint = (i + j - 1) as f64 / 2.0;
        let raw_bucket = (midpoint / (n - 1) as f64 * n_quantiles as f64).floor() as usize;
        let bucket = raw_bucket.min(n_quantiles - 1);
        for &id in &sorted_ids[i..j] {
            buckets.insert(id.clone(), bucket);
        }
        i = j;
    }
    buckets
}

/// Sorts `items` in place from highest posterior mean to lowest, grouping items whose means
/// differ by at most [`MEAN_TIE_EPSILON`] and ordering each tie band with `tie_cmp`.
pub fn sort_by_score_with_ties<T, S, IdFn, TieCmp>(
    items: &mut [T],
    scores: &HashMap<String, Score, S>,
    id_of: IdFn,
    mut tie_cmp: TieCmp,
) where
    S: BuildHasher,
    IdFn: Fn(&T) -> &str,
    TieCmp: FnMut(&T, &T) -> Ordering,
{
    let mean_of = |item: &T| scores.get(id_of(item)).copied().unwrap_or_default().mean;
    items.sort_by(|a, b| mean_of(b).total_cmp(&mean_of(a)).then_with(|| tie_cmp(a, b)));
    let mut tie_start = 0usize;
    while tie_start < items.len() {
        let base_mean = mean_of(&items[tie_start]);
        let mut tie_end = tie_start + 1;
        while tie_end < items.len()
            && (mean_of(&items[tie_end]) - base_mean).abs() <= MEAN_TIE_EPSILON
        {
            tie_end += 1;
        }
        if tie_end - tie_start > 1 {
            items[tie_start..tie_end].sort_by(&mut tie_cmp);
        }
        tie_start = tie_end;
    }
}

#[allow(clippy::too_many_lines, clippy::needless_range_loop)]
fn ep_pairwise_component(
    n_items: usize,
    pairs: &[(usize, usize)],
    alpha: f64,
    max_iter: usize,
) -> Option<(Vec<f64>, Vec<f64>)> {
    let m = pairs.len();
    let mut mean = vec![0.0; n_items];
    // During each inner sweep we maintain only the upper triangle (`r <= c`) of the symmetric
    // covariance matrix `cov`; `cholesky_invert` refreshes the full symmetric matrix after each sweep.
    let mut cov = vec![0.0; n_items * n_items];
    for i in 0..n_items {
        cov[i * n_items + i] = 1.0 / alpha;
    }
    let mut tau = vec![0.0; m];
    let mut nu = vec![0.0; m];
    let mut prec = vec![0.0; n_items * n_items];
    let mut xs = vec![0.0; n_items];
    let mut diff_col = vec![0.0; n_items];

    for _ in 0..max_iter {
        let tau_old = tau.clone();
        let nu_old = nu.clone();

        for (i, &(a, b)) in pairs.iter().enumerate() {
            let (lo, hi) = if a <= b { (a, b) } else { (b, a) };
            let f_var = cov[a * n_items + a] + cov[b * n_items + b] - 2.0 * cov[lo * n_items + hi];
            let f_mean = mean[a] - mean[b];
            if f_var <= 0.0 || !f_var.is_finite() {
                tracing::warn!(
                    n_items,
                    n_pairs = pairs.len(),
                    f_var,
                    "EP pairwise inference aborted due to non-positive or non-finite cavity variance"
                );
                return None;
            }
            let tau_tot = 1.0 / f_var;
            let nu_tot = tau_tot * f_mean;
            let tau_cav = tau_tot - tau[i];
            if tau_cav <= 0.0 || !tau_cav.is_finite() {
                tracing::warn!(
                    n_items,
                    n_pairs = pairs.len(),
                    tau_cav,
                    "EP pairwise inference aborted due to non-positive or non-finite cavity precision"
                );
                return None;
            }
            let nu_cav = nu_tot - nu[i];
            let cov_cav = 1.0 / tau_cav;
            let mean_cav = cov_cav * nu_cav;

            let (_, dlogpart, d2logpart) = match_moments_logit(mean_cav, cov_cav);
            if !dlogpart.is_finite() || !d2logpart.is_finite() {
                tracing::warn!(
                    n_items,
                    n_pairs = pairs.len(),
                    dlogpart,
                    d2logpart,
                    "EP pairwise inference aborted due to non-finite moment derivatives"
                );
                return None;
            }
            let denom = 1.0 + d2logpart / tau_cav;
            if !denom.is_finite() || denom.abs() <= f64::EPSILON {
                tracing::warn!(
                    n_items,
                    n_pairs = pairs.len(),
                    denom,
                    "EP pairwise inference aborted due to degenerate site update denominator"
                );
                return None;
            }
            tau[i] = -d2logpart / denom;
            let delta_tau = tau[i] - tau_old[i];
            nu[i] = (dlogpart - (nu_cav / tau_cav) * d2logpart) / denom;
            let delta_nu = nu[i] - nu_old[i];
            if !tau[i].is_finite()
                || !nu[i].is_finite()
                || !delta_tau.is_finite()
                || !delta_nu.is_finite()
            {
                tracing::warn!(
                    n_items,
                    n_pairs = pairs.len(),
                    "EP pairwise inference aborted due to non-finite site parameters"
                );
                return None;
            }

            prec[a * n_items + a] += delta_tau;
            prec[a * n_items + b] -= delta_tau;
            prec[b * n_items + a] -= delta_tau;
            prec[b * n_items + b] += delta_tau;
            xs[a] += delta_nu;
            xs[b] -= delta_nu;

            if delta_tau.abs() > 0.0 || delta_nu.abs() > 0.0 {
                // Reconstruct `diff_col = cov[:, a] - cov[:, b]` from the symmetric upper triangle.
                for k in 0..a {
                    diff_col[k] = cov[k * n_items + a];
                }
                diff_col[a..].copy_from_slice(&cov[a * n_items + a..(a + 1) * n_items]);
                for k in 0..b {
                    diff_col[k] -= cov[k * n_items + b];
                }
                let b_row = &cov[b * n_items + b..(b + 1) * n_items];
                let diff_tail = &mut diff_col[b..];
                for k in 0..diff_tail.len() {
                    diff_tail[k] -= b_row[k];
                }

                let scale = if delta_tau.abs() > 0.0 {
                    let scale_denom = (1.0 / delta_tau) + f_var;
                    if !scale_denom.is_finite() || scale_denom.abs() <= f64::EPSILON {
                        tracing::warn!(
                            n_items,
                            n_pairs = pairs.len(),
                            scale_denom,
                            "EP pairwise inference aborted due to degenerate rank-1 covariance update"
                        );
                        return None;
                    }
                    let s = -1.0 / scale_denom;
                    for r in 0..n_items {
                        let dr = diff_col[r];
                        if dr == 0.0 {
                            continue;
                        }
                        let sr = s * dr;
                        let row = &mut cov[r * n_items + r..(r + 1) * n_items];
                        let dc = &diff_col[r..];
                        for c in 0..row.len() {
                            row[c] += sr * dc[c];
                        }
                    }
                    s
                } else {
                    0.0
                };

                // Exact rank-1 mean update: `(cov + s * d * d^T)(xs + delta_nu * (e_a - e_b))`
                // equals `mean + (delta_nu + s * (f_mean + delta_nu * f_var)) * d`, avoiding an
                // `O(n^2)` matrix-vector product on every site update.
                let mean_step = if scale == 0.0 {
                    delta_nu
                } else {
                    delta_nu + scale * (f_mean + delta_nu * f_var)
                };
                for k in 0..n_items {
                    mean[k] += mean_step * diff_col[k];
                }
            }
        }

        let mut sys_mat = prec.clone();
        for d in 0..n_items {
            sys_mat[d * n_items + d] += alpha;
        }
        let Some(inv_cov) = cholesky_invert(&sys_mat, n_items) else {
            tracing::warn!(
                n_items,
                n_pairs = pairs.len(),
                "EP pairwise inference aborted due to non-positive-definite precision matrix"
            );
            return None;
        };
        cov = inv_cov;
        mat_vec_mul(&cov, &xs, &mut mean, n_items);

        let Some(max_delta) = max_finite_site_delta(&tau, &tau_old, &nu, &nu_old) else {
            tracing::warn!(
                n_items,
                n_pairs = pairs.len(),
                "EP pairwise inference aborted due to non-finite site delta"
            );
            return None;
        };
        if max_delta <= THRESHOLD {
            let variances: Vec<f64> = (0..n_items).map(|k| cov[k * n_items + k]).collect();
            if mean.iter().all(|v| v.is_finite())
                && variances.iter().all(|v| v.is_finite() && *v >= 0.0)
            {
                return Some((mean, variances));
            }
            tracing::warn!(
                n_items,
                n_pairs = pairs.len(),
                "EP pairwise inference aborted due to non-finite posterior mean or variance"
            );
            return None;
        }
    }
    tracing::warn!(
        n_items,
        n_pairs = pairs.len(),
        max_iter,
        "EP pairwise inference did not converge within max_iter; falling back to prior scores"
    );
    None
}

fn max_finite_site_delta(tau: &[f64], tau_old: &[f64], nu: &[f64], nu_old: &[f64]) -> Option<f64> {
    let mut max_delta = 0.0_f64;
    for (&x, &y) in tau.iter().zip(tau_old).chain(nu.iter().zip(nu_old)) {
        if !x.is_finite() || !y.is_finite() {
            return None;
        }
        let diff = (x - y).abs();
        if !diff.is_finite() {
            return None;
        }
        if diff > max_delta {
            max_delta = diff;
        }
    }
    Some(max_delta)
}

#[allow(clippy::needless_range_loop)]
fn mat_vec_mul(mat: &[f64], vec_x: &[f64], out: &mut [f64], n: usize) {
    for r in 0..n {
        let row = &mat[r * n..(r + 1) * n];
        let mut sum = 0.0;
        for c in 0..n {
            sum += row[c] * vec_x[c];
        }
        out[r] = sum;
    }
}

#[allow(clippy::needless_range_loop)]
fn cholesky_invert(mat: &[f64], n: usize) -> Option<Vec<f64>> {
    let mut low = vec![0.0; n * n];
    for i in 0..n {
        let row_i_off = i * n;
        for j in 0..=i {
            let row_j_off = j * n;
            let mut sum = mat[row_i_off + j];
            for k in 0..j {
                sum -= low[row_i_off + k] * low[row_j_off + k];
            }
            if i == j {
                if sum <= 0.0 || !sum.is_finite() {
                    return None;
                }
                low[row_i_off + i] = sum.sqrt();
            } else {
                low[row_i_off + j] = sum / low[row_j_off + j];
            }
        }
    }

    // Store `L^{-1}` transposed (`inv_low_t[col * n + row] = (L^{-1})_{row, col}`) so both
    // triangular inversion and `L^{-T} L^{-1}` read contiguous row-major slices with identical
    // IEEE-754 summation order.
    let mut inv_low_t = vec![0.0; n * n];
    for col in 0..n {
        let col_off = col * n;
        inv_low_t[col_off + col] = 1.0 / low[col_off + col];
        for row in (col + 1)..n {
            let low_row = &low[row * n + col..row * n + row];
            let inv_col = &inv_low_t[col_off + col..col_off + row];
            let mut sum = 0.0;
            for k in 0..low_row.len() {
                sum += low_row[k] * inv_col[k];
            }
            inv_low_t[col_off + row] = -sum / low[row * n + row];
        }
    }

    let mut out = vec![0.0; n * n];
    for i in 0..n {
        let row_i = &inv_low_t[i * n..(i + 1) * n];
        for j in i..n {
            let row_j = &inv_low_t[j * n..(j + 1) * n];
            let mut sum = 0.0;
            for k in j..n {
                sum += row_i[k] * row_j[k];
            }
            out[i * n + j] = sum;
            out[j * n + i] = sum;
        }
    }
    Some(out)
}

fn log_phi(z: f64) -> (f64, f64) {
    if z * z < 0.0492 {
        let coef = -z / SQRT_2PI;
        let val = LOG_PHI_CS.iter().fold(0.0, |acc, &c| coef * (c + acc));
        let res = -2.0 * val - std::f64::consts::LN_2;
        let dres = (-(z * z) / 2.0 - res).exp() / SQRT_2PI;
        (res, dres)
    } else if z < -11.3137 {
        let num = LOG_PHI_RS.iter().fold(0.564_189_583_547_755_1, |acc, &r| -z * acc / SQRT2 + r);
        let den = LOG_PHI_QS.iter().fold(1.0, |acc, &q| -z * acc / SQRT2 + q);
        let res = (num / (2.0 * den)).ln() - (z * z) / 2.0;
        let dres = (den / num).abs() * SQRT_2_OVER_PI;
        (res, dres)
    } else {
        let cdf = 0.5 * cody_erfc(-z / SQRT2);
        let res = cdf.ln();
        let dres = (-(z * z) / 2.0 - res).exp() / SQRT_2PI;
        (res, dres)
    }
}

fn match_moments_probit(mean_cav: f64, cov_cav: f64) -> (f64, f64, f64) {
    let s = (1.0 + cov_cav).sqrt();
    let z = mean_cav / s;
    let (logpart, val) = log_phi(z);
    let dlogpart = val / s;
    let d2logpart = -val * (z + val) / (1.0 + cov_cav);
    (logpart, dlogpart, d2logpart)
}

fn match_moments_logit(mean_cav: f64, cov_cav: f64) -> (f64, f64, f64) {
    let exponent = -10.0 * (mean_cav.abs() - (196.0 / 200.0) * cov_cav - 4.0);
    let (lambd, logpart2, dlogpart2, d2logpart2) = if exponent < 500.0 {
        let l = 1.0 / (1.0 + exponent.exp());
        let lp2_raw = (cov_cav / 2.0 - mean_cav.abs()).min(-0.1);
        if mean_cav > 0.0 {
            (l, (1.0 - lp2_raw.exp()).ln(), 0.0, 0.0)
        } else {
            (l, lp2_raw, 1.0, 0.0)
        }
    } else {
        (0.0, 0.0, 0.0, 0.0)
    };

    if 1.0 - lambd < 1e-15 {
        return (logpart2, dlogpart2, d2logpart2);
    }

    let mut arr1 = [0.0; 5];
    let mut arr2 = [0.0; 5];
    let mut arr3 = [0.0; 5];
    for (i, &lam) in LOGIT_LAMBDAS.iter().enumerate() {
        let (p1, p2, p3) = match_moments_probit(lam * mean_cav, lam * lam * cov_cav);
        arr1[i] = p1;
        arr2[i] = p2;
        arr3[i] = p3;
    }

    let max_a1 = arr1.iter().copied().fold(f64::NEG_INFINITY, f64::max);
    let mut exps = [0.0; 5];
    for i in 0..5 {
        exps[i] = (arr1[i] - max_a1).exp();
    }

    let mut denom = 0.0;
    let mut num1 = 0.0;
    let mut num2 = 0.0;
    for i in 0..5 {
        let w = exps[i] * LOGIT_CS[i];
        let lam = LOGIT_LAMBDAS[i];
        denom += w;
        num1 += w * arr2[i] * lam;
        num2 += w * (arr2[i] * arr2[i] + arr3[i]) * lam * lam;
    }

    if !denom.is_finite() || denom <= 0.0 {
        return if lambd > 0.0 {
            (logpart2, dlogpart2, d2logpart2)
        } else {
            (f64::NAN, f64::NAN, f64::NAN)
        };
    }

    let logpart1 = max_a1 + denom.ln();
    let dlogpart1 = num1 / denom;
    let d2logpart1 = (num2 / denom) - (dlogpart1 * dlogpart1);

    let logpart = (1.0 - lambd) * logpart1 + lambd * logpart2;
    let dlogpart = (1.0 - lambd) * dlogpart1 + lambd * dlogpart2;
    let d2logpart = (1.0 - lambd) * d2logpart1 + lambd * d2logpart2;
    (logpart, dlogpart, d2logpart)
}

/// W. J. Cody (1969) IEEE-754 rational Chebyshev approximation for `erfc(x)`.
#[must_use]
pub fn cody_erfc(x: f64) -> f64 {
    const A: [f64; 5] = [
        3.161_123_743_870_565_6,
        1.138_641_541_510_501_6e2,
        3.774_852_376_853_02e2,
        3.209_377_589_138_469_5e3,
        1.857_777_061_846_031_5e-1,
    ];
    const B: [f64; 4] = [
        2.360_129_095_234_412e1,
        2.440_246_379_344_441_7e2,
        1.282_616_526_077_372_3e3,
        2.844_236_833_439_171e3,
    ];
    const C: [f64; 9] = [
        5.641_884_969_886_701e-1,
        8.883_149_794_388_376,
        6.611_919_063_714_163e1,
        2.986_351_381_974_001e2,
        8.819_522_212_417_69e2,
        1.712_047_612_634_070_6e3,
        2.051_078_377_826_071_5e3,
        1.230_339_354_797_997_2e3,
        2.153_115_354_744_038_5e-8,
    ];
    const D: [f64; 8] = [
        1.574_492_611_070_983_5e1,
        1.176_939_508_913_125e2,
        5.371_811_018_620_099e2,
        1.621_389_574_566_690_2e3,
        3.290_799_235_733_459_6e3,
        4.362_619_090_143_247e3,
        3.439_367_674_143_721_6e3,
        1.230_339_354_803_749_4e3,
    ];
    const P: [f64; 6] = [
        3.053_266_349_612_323_4e-1,
        3.603_448_999_498_044_4e-1,
        1.257_817_261_112_292_5e-1,
        1.608_378_514_874_228e-2,
        6.587_491_615_298_378e-4,
        1.631_538_713_730_209_8e-2,
    ];
    const Q: [f64; 5] = [
        2.568_520_192_289_822,
        1.872_952_849_923_460_5,
        5.279_051_029_514_284e-1,
        6.051_834_131_244_132e-2,
        2.335_204_976_268_692e-3,
    ];

    if x.is_nan() {
        return f64::NAN;
    }
    let ax = x.abs();
    if ax <= 0.46875 {
        let ysq = ax * ax;
        let mut xnum = A[4] * ysq;
        let mut xden = ysq;
        for i in 0..3 {
            xnum = (xnum + A[i]) * ysq;
            xden = (xden + B[i]) * ysq;
        }
        let erf_val = x * (xnum + A[3]) / (xden + B[3]);
        return 1.0 - erf_val;
    }

    let res = if ax <= 4.0 {
        let mut xnum = C[8] * ax;
        let mut xden = ax;
        for i in 0..7 {
            xnum = (xnum + C[i]) * ax;
            xden = (xden + D[i]) * ax;
        }
        let ratio = (xnum + C[7]) / (xden + D[7]);
        let ysq = (ax * 16.0).floor() / 16.0;
        let del = (ax - ysq) * (ax + ysq);
        (-ysq * ysq).exp() * (-del).exp() * ratio
    } else if ax >= 26.55 {
        0.0
    } else {
        let ysq = 1.0 / (ax * ax);
        let mut xnum = P[5] * ysq;
        let mut xden = ysq;
        for i in 0..4 {
            xnum = (xnum + P[i]) * ysq;
            xden = (xden + Q[i]) * ysq;
        }
        let ratio = ysq * (xnum + P[4]) / (xden + Q[4]);
        let scaled = (0.564_189_583_547_756_3 - ratio) / ax;
        let ysq_hi = (ax * 16.0).floor() / 16.0;
        let del = (ax - ysq_hi) * (ax + ysq_hi);
        (-ysq_hi * ysq_hi).exp() * (-del).exp() * scaled
    };

    if x < 0.0 { 2.0 - res } else { res }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[derive(Debug, Deserialize)]
    struct EpVectorCase {
        name: String,
        items: Vec<String>,
        orderings: Vec<Vec<Vec<String>>>,
        expected_scores: HashMap<String, Score>,
        expected_quantiles: HashMap<String, usize>,
        expected_first_pair_confusability: f64,
    }

    #[derive(Debug, Deserialize)]
    struct EpGoldenFile {
        ep_cases: Vec<EpVectorCase>,
    }

    #[test]
    fn test_ep_ranking_matches_golden_vectors() {
        let raw = include_str!("../../../testdata/ep_ranking_vectors.json");
        let parsed: EpGoldenFile = serde_json::from_str(raw).unwrap();
        assert!(parsed.ep_cases.len() >= 9);

        for case in parsed.ep_cases {
            let scores = infer_scores(&case.items, &case.orderings);
            for (id, expected) in &case.expected_scores {
                let actual = scores.get(id).unwrap();
                assert!(
                    actual.mean.is_finite()
                        && actual.variance.is_finite()
                        && actual.variance >= 0.0,
                    "case '{}' item '{}' produced non-finite or negative variance: {:?}",
                    case.name,
                    id,
                    actual
                );
                assert!(
                    (actual.mean - expected.mean).abs() <= 1e-6,
                    "case '{}' item '{}' mean mismatch: actual={} expected={}",
                    case.name,
                    id,
                    actual.mean,
                    expected.mean
                );
                assert!(
                    (actual.variance - expected.variance).abs() <= 1e-6,
                    "case '{}' item '{}' variance mismatch: actual={} expected={}",
                    case.name,
                    id,
                    actual.variance,
                    expected.variance
                );
            }

            let quantiles = rescale_to_quantiles(&scores, N_QUANTILES);
            assert_eq!(
                quantiles, case.expected_quantiles,
                "case '{}' quantile mismatch",
                case.name
            );

            let first = scores[&case.items[0]];
            let second = scores[&case.items[1]];
            let conf = confusability(first, second);
            assert!(
                (conf - case.expected_first_pair_confusability).abs() <= 1e-6,
                "case '{}' confusability mismatch: actual={} expected={}",
                case.name,
                conf,
                case.expected_first_pair_confusability
            );
        }
    }

    #[test]
    fn test_select_cluster_focus_terminates_when_settled() {
        let items = vec!["a".to_owned(), "b".to_owned(), "c".to_owned()];
        let mut scores = HashMap::new();
        scores.insert("a".to_owned(), Score { mean: 5.0, variance: 0.1 });
        scores.insert("b".to_owned(), Score { mean: 0.0, variance: 0.1 });
        scores.insert("c".to_owned(), Score { mean: -5.0, variance: 0.1 });

        let mut rng = rand::rng();
        let focus = vec!["a".to_owned()];
        let cluster = select_cluster(&scores, &items, 5, false, Some(&focus), &mut rng);
        assert_eq!(cluster, vec!["a".to_owned()]);
    }

    #[test]
    fn test_sort_by_score_with_ties_groups_epsilon_bands() {
        let mut items = vec![
            ("id-z", "Zebra"),
            ("id-low", "Aardvark"),
            ("id-a", "alpha"),
            ("id-unscored-b", "Beta"),
            ("id-unscored-a", "Alpha"),
        ];
        let scores = HashMap::from([
            ("id-z".to_owned(), Score { mean: 1.0, variance: 0.2 }),
            ("id-a".to_owned(), Score { mean: 1.0 - MEAN_TIE_EPSILON * 0.5, variance: 0.2 }),
            ("id-low".to_owned(), Score { mean: -1.0, variance: 0.2 }),
        ]);

        sort_by_score_with_ties(
            &mut items,
            &scores,
            |&(id, _)| id,
            |a, b| a.1.to_lowercase().cmp(&b.1.to_lowercase()).then_with(|| a.0.cmp(b.0)),
        );

        let ordered_ids: Vec<&str> = items.iter().map(|&(id, _)| id).collect();
        assert_eq!(ordered_ids, vec!["id-a", "id-z", "id-unscored-a", "id-unscored-b", "id-low"]);
    }

    #[test]
    fn test_validate_ordering_caps_comparison_width() {
        let ok: Vec<Vec<String>> =
            (0..MAX_COMPARISON_WIDTH).map(|i| vec![format!("e{i}")]).collect();
        assert!(validate_ordering(&ok).is_ok());

        let too_wide: Vec<Vec<String>> =
            (0..=MAX_COMPARISON_WIDTH).map(|i| vec![format!("e{i}")]).collect();
        assert!(validate_ordering(&too_wide).is_err());
    }

    #[test]
    fn test_match_moments_logit_and_site_delta_resist_nan_poisoning() {
        for mean_cav in [-1000.0, -50.0, 50.0, 1000.0] {
            let (lp, dlp, d2lp) = match_moments_logit(mean_cav, 1.0);
            assert!(lp.is_finite(), "logpart non-finite for mean_cav={mean_cav}: {lp}");
            assert!(dlp.is_finite(), "dlogpart non-finite for mean_cav={mean_cav}: {dlp}");
            assert!(d2lp.is_finite(), "d2logpart non-finite for mean_cav={mean_cav}: {d2lp}");
        }

        assert!(max_finite_site_delta(&[f64::NAN], &[0.0], &[0.0], &[0.0]).is_none());
        assert!(max_finite_site_delta(&[0.0], &[0.0], &[f64::INFINITY], &[0.0]).is_none());
        assert_eq!(max_finite_site_delta(&[0.1], &[0.0], &[0.0], &[0.2]), Some(0.2));
    }

    #[test]
    fn test_confusability_zero_variance_parity() {
        let base = Score { mean: 1.0, variance: 0.0 };
        let within_eps = Score { mean: 1.0 + f64::EPSILON * 0.5, variance: 0.0 };
        let outside_eps = Score { mean: 1.0 + 1e-12, variance: 0.0 };
        assert!((confusability(base, within_eps) - 0.5).abs() <= f64::EPSILON);
        assert!((confusability(base, outside_eps) - 0.0).abs() <= f64::EPSILON);
    }

    #[test]
    fn test_select_cluster_prioritized_preserves_seed_and_confusability_order() {
        use rand::SeedableRng as _;

        let items: Vec<String> =
            ["focus", "hi", "near1", "near2", "far"].into_iter().map(ToOwned::to_owned).collect();
        let scores = HashMap::from([
            ("focus".to_owned(), Score { mean: -0.4, variance: 0.8 }),
            ("hi".to_owned(), Score { mean: 0.45, variance: 0.3 }),
            ("near1".to_owned(), Score { mean: -0.35, variance: 0.4 }),
            ("near2".to_owned(), Score { mean: 0.1, variance: 0.4 }),
            ("far".to_owned(), Score { mean: 0.3, variance: 0.2 }),
        ]);

        let mut rng = rand::rngs::StdRng::seed_from_u64(7);
        let focus = vec!["focus".to_owned()];
        let sel = select_cluster_prioritized(&scores, &items, 5, false, Some(&focus), &mut rng);

        // Display order sorts descending by posterior mean, placing `focus` near the bottom...
        assert_eq!(sel.ordered_ids.first().map(String::as_str), Some("hi"));
        assert_eq!(sel.ordered_ids.last().map(String::as_str), Some("focus"));
        // ...while selection-priority order always keeps `focus` at index 0 and orders neighbors
        // by descending confusability so shrinking to k=2 or k=3 drops least-informative items.
        assert_eq!(sel.priority_ids[0], "focus");
        assert_eq!(sel.priority_ids[1], "near1");
        assert_eq!(sel.priority_ids[2], "near2");
    }

    #[test]
    fn test_select_cluster_force_random_seed_explores_beyond_k2() {
        use rand::SeedableRng as _;

        let items: Vec<String> = (0..6).map(|i| format!("i{i}")).collect();
        let scores: HashMap<String, Score> = (0..6)
            .map(|i| (format!("i{i}"), Score { mean: 0.0, variance: 1.0 - f64::from(i) * 0.05 }))
            .collect();

        let mut rng = rand::rngs::StdRng::seed_from_u64(42);
        let mut seen_seeds = HashSet::new();
        for _ in 0..80 {
            let sel = select_cluster_prioritized(&scores, &items, 2, true, None, &mut rng);
            assert_eq!(sel.ordered_ids.len(), 2);
            seen_seeds.insert(sel.priority_ids[0].clone());
        }
        assert!(
            seen_seeds.len() >= 3,
            "expected random seed pool >= MAX_K_DEFAULT even at max_k=2, saw {seen_seeds:?}"
        );
    }

    #[test]
    fn test_rescale_to_quantiles_with_uncompared_matches_full_rescale_bit_for_bit() {
        let cases: Vec<(Vec<(&str, f64)>, usize)> = vec![
            (vec![], 0),
            (vec![], 1),
            (vec![], 50),
            (vec![("a", 0.5)], 0),
            (vec![("a", 0.5)], 10),
            (vec![("a", -0.5)], 10),
            (vec![("a", 1.2), ("b", 0.4), ("c", 0.0), ("d", -0.3), ("e", -1.1)], 100),
            // Tie-chaining right around `0.0` within and outside `MEAN_TIE_EPSILON`.
            (vec![("neg_near", -MEAN_TIE_EPSILON * 0.5), ("pos_near", MEAN_TIE_EPSILON * 0.6)], 25),
            (vec![("neg_far", -MEAN_TIE_EPSILON * 2.0), ("pos_near", MEAN_TIE_EPSILON * 0.5)], 25),
        ];
        for (compared_list, uncompared_count) in cases {
            let compared_scores: HashMap<String, Score> = compared_list
                .iter()
                .map(|&(id, mean)| (id.to_owned(), Score { mean, variance: 0.5 }))
                .collect();
            let mut full_scores = compared_scores.clone();
            for i in 0..uncompared_count {
                full_scores.insert(format!("uncompared-{i:04}"), Score::default());
            }
            let expected = rescale_to_quantiles(&full_scores, N_QUANTILES);
            let actual = rescale_to_quantiles_with_uncompared(
                &compared_scores,
                uncompared_count,
                N_QUANTILES,
            );
            for id in compared_scores.keys() {
                assert_eq!(
                    actual.compared.get(id),
                    expected.get(id),
                    "mismatch for compared id {id} with uncompared={uncompared_count}"
                );
            }
            for i in 0..uncompared_count {
                let uid = format!("uncompared-{i:04}");
                assert_eq!(
                    actual.uncompared,
                    expected.get(&uid).copied(),
                    "mismatch for uncompared {uid} with uncompared={uncompared_count}"
                );
            }
            if uncompared_count == 0 {
                assert_eq!(actual.uncompared, None);
            }
        }
    }

    #[test]
    fn test_extract_ep_components_isolates_unrelated_components_and_preserves_bridges() {
        let orderings = vec![
            vec![
                vec!["album_a".to_owned()],
                vec!["album_b".to_owned()],
                vec!["album_c".to_owned()],
            ],
            vec![vec!["artist_1".to_owned()], vec!["artist_2".to_owned()]],
            vec![vec!["artist_3".to_owned()], vec!["bridge_work".to_owned()]],
            vec![vec!["bridge_work".to_owned()], vec!["artist_4".to_owned()]],
        ];

        // Querying only artist IDs must skip the unrelated album component while retaining
        // transitive bridges through `bridge_work`.
        let artist_targets = vec![
            "artist_1".to_owned(),
            "artist_2".to_owned(),
            "artist_3".to_owned(),
            "artist_4".to_owned(),
        ];
        let comps = extract_ep_components(&artist_targets, &orderings);
        assert_eq!(comps.len(), 2);
        assert_eq!(comps[0].items, vec!["artist_1", "artist_2"]);
        assert_eq!(comps[1].items, vec!["artist_3", "artist_4", "bridge_work"]);

        // Component-by-component solve matches `infer_scores` bit-for-bit.
        let direct = infer_scores(&artist_targets, &orderings);
        let mut from_comps = HashMap::new();
        for comp in &comps {
            let solved = solve_ep_component(comp).unwrap();
            for (id, score) in comp.items.iter().zip(solved) {
                from_comps.insert(id.clone(), score);
            }
        }
        for id in &artist_targets {
            assert_eq!(from_comps.get(id), direct.get(id));
        }

        // Unrelated album component key stays identical when a new artist comparison is appended.
        let album_before = extract_ep_components(&["album_a".to_owned()], &orderings);
        let mut with_extra_artist = orderings;
        with_extra_artist.push(vec![vec!["artist_5".to_owned()], vec!["artist_6".to_owned()]]);
        let album_after = extract_ep_components(&["album_a".to_owned()], &with_extra_artist);
        assert_eq!(album_before, album_after);

        // Out-of-bounds or self-loop pair indices in manually constructed components fail gracefully with None.
        let invalid =
            EpComponent { items: vec!["a".to_owned(), "b".to_owned()], pairs: vec![(0, 2)] };
        assert!(solve_ep_component(&invalid).is_none());
        let self_loop =
            EpComponent { items: vec!["a".to_owned(), "b".to_owned()], pairs: vec![(0, 0)] };
        assert!(solve_ep_component(&self_loop).is_none());
    }
}
