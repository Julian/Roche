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

    let raw_pairs: Vec<(String, String)> = orderings
        .iter()
        .filter(|ord| {
            let width: usize = ord.iter().map(Vec::len).sum();
            if width > MAX_COMPARISON_WIDTH {
                tracing::warn!(
                    width,
                    max_width = MAX_COMPARISON_WIDTH,
                    "Ignoring oversized comparison ordering during EP inference"
                );
                false
            } else {
                true
            }
        })
        .flat_map(|ord| to_pairs(ord))
        .collect();
    if raw_pairs.is_empty() {
        return scores;
    }

    let mut adj: HashMap<&str, Vec<&str>> = HashMap::new();
    for (w, l) in &raw_pairs {
        adj.entry(w.as_str()).or_default().push(l.as_str());
        adj.entry(l.as_str()).or_default().push(w.as_str());
    }

    let target_set: HashSet<&str> = item_ids.iter().map(String::as_str).collect();
    let mut sorted_targets: Vec<&str> = target_set.iter().copied().collect();
    sorted_targets.sort_unstable();

    let mut visited: HashSet<&str> = HashSet::new();
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
        let idx_map: HashMap<&str, usize> =
            comp.iter().enumerate().map(|(i, &id)| (id, i)).collect();
        let comp_pairs: Vec<(usize, usize)> = raw_pairs
            .iter()
            .filter_map(|(w, l)| {
                let &wi = idx_map.get(w.as_str())?;
                let &li = idx_map.get(l.as_str())?;
                (wi != li).then_some((wi, li))
            })
            .collect();
        if comp_pairs.is_empty() {
            continue;
        }
        if let Some((means, variances)) =
            ep_pairwise_component(comp.len(), &comp_pairs, PRIOR_ALPHA, 100)
        {
            for (i, &id) in comp.iter().enumerate() {
                if target_set.contains(id) {
                    let clean_mean = if means[i].abs() < 1e-12 { 0.0 } else { means[i] };
                    scores
                        .insert(id.to_owned(), Score { mean: clean_mean, variance: variances[i] });
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

/// Selects a cluster of up to `max_k` items to compare next, returning `< 2` items when settled.
pub fn select_cluster<R: Rng + ?Sized, S: BuildHasher>(
    scores: &HashMap<String, Score, S>,
    item_ids: &[String],
    max_k: usize,
    force_random_seed: bool,
    seed_pool: Option<&[String]>,
    rng: &mut R,
) -> Vec<String> {
    let cap = max_k.max(MIN_K);
    if item_ids.is_empty() {
        return Vec::new();
    }

    let candidates: Vec<&String> = seed_pool.map_or_else(
        || item_ids.iter().collect(),
        |pool| {
            let pool_set: HashSet<&str> = pool.iter().map(String::as_str).collect();
            item_ids.iter().filter(|id| pool_set.contains(id.as_str())).collect()
        },
    );
    if candidates.is_empty() {
        return Vec::new();
    }

    let seed_id: &String = if force_random_seed {
        let mut ranked: Vec<(f64, f64, &String)> = candidates
            .iter()
            .map(|&id| {
                let var = scores.get(id).copied().unwrap_or_default().variance;
                (var, rng.random::<f64>(), id)
            })
            .collect();
        ranked.sort_by(|a, b| b.0.total_cmp(&a.0).then_with(|| b.1.total_cmp(&a.1)));
        ranked.truncate(cap);
        let pick = rng.random_range(0..ranked.len());
        ranked[pick].2
    } else {
        let mut best: Option<(f64, f64, &String)> = None;
        for &id in &candidates {
            let var = scores.get(id).copied().unwrap_or_default().variance;
            let tie = rng.random::<f64>();
            if best.is_none_or(|b| (var, tie) > (b.0, b.1)) {
                best = Some((var, tie, id));
            }
        }
        match best {
            Some((_, _, id)) => id,
            None => return Vec::new(),
        }
    };

    let seed_score = scores.get(seed_id).copied().unwrap_or_default();
    let mut neighbors: Vec<(f64, f64, &String)> = item_ids
        .iter()
        .filter(|&id| id != seed_id)
        .map(|id| {
            let cand_score = scores.get(id).copied().unwrap_or_default();
            (confusability(seed_score, cand_score), rng.random::<f64>(), id)
        })
        .collect();
    neighbors.sort_by(|a, b| b.0.total_cmp(&a.0).then_with(|| b.1.total_cmp(&a.1)));

    let mut cluster = vec![seed_id.clone()];
    for (conf, _, cand_id) in neighbors {
        if cluster.len() >= cap || conf < MIN_CONFUSABILITY {
            break;
        }
        cluster.push(cand_id.clone());
    }

    cluster.sort_by(|a, b| {
        let sa = scores.get(a).copied().unwrap_or_default().mean;
        let sb = scores.get(b).copied().unwrap_or_default().mean;
        sb.total_cmp(&sa).then_with(|| a.cmp(b))
    });
    cluster
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

#[allow(clippy::too_many_lines)]
fn ep_pairwise_component(
    n_items: usize,
    pairs: &[(usize, usize)],
    alpha: f64,
    max_iter: usize,
) -> Option<(Vec<f64>, Vec<f64>)> {
    let m = pairs.len();
    let mut mean = vec![0.0; n_items];
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
            let f_var = cov[a * n_items + a] + cov[b * n_items + b] - 2.0 * cov[a * n_items + b];
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

            if delta_tau.abs() > 0.0 {
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
                let scale = -1.0 / scale_denom;
                for k in 0..n_items {
                    diff_col[k] = cov[a * n_items + k] - cov[b * n_items + k];
                }
                for r in 0..n_items {
                    let sr = scale * diff_col[r];
                    let row_offset = r * n_items;
                    for c in 0..n_items {
                        cov[row_offset + c] += sr * diff_col[c];
                    }
                }
            }
            mat_vec_mul(&cov, &xs, &mut mean, n_items);
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

fn mat_vec_mul(mat: &[f64], vec_x: &[f64], out: &mut [f64], n: usize) {
    for r in 0..n {
        let row = &mat[r * n..(r + 1) * n];
        out[r] = row.iter().zip(vec_x).map(|(a, b)| a * b).sum();
    }
}

fn cholesky_invert(mat: &[f64], n: usize) -> Option<Vec<f64>> {
    let mut low = vec![0.0; n * n];
    for i in 0..n {
        for j in 0..=i {
            let mut sum = mat[i * n + j];
            for k in 0..j {
                sum -= low[i * n + k] * low[j * n + k];
            }
            if i == j {
                if sum <= 0.0 || !sum.is_finite() {
                    return None;
                }
                low[i * n + i] = sum.sqrt();
            } else {
                low[i * n + j] = sum / low[j * n + j];
            }
        }
    }

    let mut inv_low = vec![0.0; n * n];
    for col in 0..n {
        inv_low[col * n + col] = 1.0 / low[col * n + col];
        for row in (col + 1)..n {
            let mut sum = 0.0;
            for k in col..row {
                sum += low[row * n + k] * inv_low[k * n + col];
            }
            inv_low[row * n + col] = -sum / low[row * n + row];
        }
    }

    let mut out = vec![0.0; n * n];
    for i in 0..n {
        for j in i..n {
            let mut sum = 0.0;
            for k in j..n {
                sum += inv_low[k * n + i] * inv_low[k * n + j];
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
}
