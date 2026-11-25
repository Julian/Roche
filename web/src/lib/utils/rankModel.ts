// Pure tie-bucket k-way ranking state model.
// Separated cleanly from Svelte components so every ranking invariant is unit-tested in Vitest.

export const MIN_K = 2;
export const MAX_K_DEFAULT = 5;
export const MAX_COVERED_CLUSTERS = 12;
export const MAX_EXCLUDED_TOKENS = 24;

export type ExclusionReason = 'manual' | 'size';

export interface RankBucketState {
  /** Ordered array of tie-buckets; each bucket holds 1+ entry IDs. */
  buckets: string[][];
  /** Priority-ordered entry IDs loaded for the current round (highest priority first). */
  priorityIds: string[];
  /** Currently excluded entry IDs and why they were excluded. */
  excluded: Map<string, ExclusionReason>;
  /** Currently focused item (cannot be excluded). */
  focusId: string | null;
  /** Currently keyboard-selected entry ID. */
  selectedId: string | null;
}

export function createRankState(
  clusterIds: string[],
  priorityIds: string[],
  focusId: string | null,
): RankBucketState {
  const orderedPriority = priorityIds.length > 0 ? [...priorityIds] : [...clusterIds];
  return {
    buckets: clusterIds.map((id) => [id]),
    priorityIds: orderedPriority,
    excluded: new Map(),
    focusId,
    selectedId: clusterIds[0] ?? null,
  };
}

export function flattenBuckets(buckets: string[][]): string[] {
  return buckets.flat();
}

export function activeCardCount(state: RankBucketState): number {
  return state.buckets.reduce((sum, b) => sum + b.length, 0);
}

export function findCardPosition(
  buckets: string[][],
  id: string,
): { bucketIdx: number; itemIdx: number } | null {
  for (let b = 0; b < buckets.length; b++) {
    const idx = buckets[b]!.indexOf(id);
    if (idx !== -1) return { bucketIdx: b, itemIdx: idx };
  }
  return null;
}

export function rankNumbersForBuckets(buckets: string[][]): Map<string, number> {
  const map = new Map<string, number>();
  let pos = 1;
  for (const bucket of buckets) {
    for (const id of bucket) {
      map.set(id, pos);
    }
    pos += bucket.length;
  }
  return map;
}

function cloneBuckets(buckets: string[][]): string[][] {
  return buckets.map((b) => [...b]);
}

function removeCard(buckets: string[][], id: string): string[][] {
  return buckets.map((b) => b.filter((x) => x !== id)).filter((b) => b.length > 0);
}

export function moveCardUp(buckets: string[][], id: string): string[][] {
  const pos = findCardPosition(buckets, id);
  if (!pos) return buckets;
  const next = cloneBuckets(buckets);
  const bucket = next[pos.bucketIdx]!;
  if (bucket.length > 1) {
    // Break out of tie into a new solo bucket immediately above.
    bucket.splice(pos.itemIdx, 1);
    next.splice(pos.bucketIdx, 0, [id]);
    return next;
  }
  if (pos.bucketIdx === 0) return buckets;
  // Swap solo bucket with the bucket immediately above.
  const above = next[pos.bucketIdx - 1]!;
  next[pos.bucketIdx - 1] = bucket;
  next[pos.bucketIdx] = above;
  return next;
}

export function moveCardDown(buckets: string[][], id: string): string[][] {
  const pos = findCardPosition(buckets, id);
  if (!pos) return buckets;
  const next = cloneBuckets(buckets);
  const bucket = next[pos.bucketIdx]!;
  if (bucket.length > 1) {
    // Break out of tie into a new solo bucket immediately below.
    bucket.splice(pos.itemIdx, 1);
    next.splice(pos.bucketIdx + 1, 0, [id]);
    return next;
  }
  if (pos.bucketIdx >= next.length - 1) return buckets;
  const below = next[pos.bucketIdx + 1]!;
  next[pos.bucketIdx + 1] = bucket;
  next[pos.bucketIdx] = below;
  return next;
}

export function canMoveUp(buckets: string[][], id: string): boolean {
  const pos = findCardPosition(buckets, id);
  if (!pos) return false;
  return pos.bucketIdx > 0 || buckets[pos.bucketIdx]!.length > 1;
}

export function canMoveDown(buckets: string[][], id: string): boolean {
  const pos = findCardPosition(buckets, id);
  if (!pos) return false;
  return pos.bucketIdx < buckets.length - 1 || buckets[pos.bucketIdx]!.length > 1;
}

export function toggleTieWithAbove(buckets: string[][], id: string): string[][] {
  const pos = findCardPosition(buckets, id);
  if (!pos) return buckets;
  const next = cloneBuckets(buckets);
  const bucket = next[pos.bucketIdx]!;
  if (bucket.length > 1) {
    // Untie into own solo bucket right below the remaining tied cards.
    bucket.splice(pos.itemIdx, 1);
    next.splice(pos.bucketIdx + 1, 0, [id]);
    return next;
  }
  if (pos.bucketIdx === 0) return buckets;
  // Merge solo bucket into the bucket immediately above.
  next[pos.bucketIdx - 1]!.push(id);
  next.splice(pos.bucketIdx, 1);
  return next;
}

export function isCardTied(buckets: string[][], id: string): boolean {
  const pos = findCardPosition(buckets, id);
  return pos !== null && buckets[pos.bucketIdx]!.length > 1;
}

export function isAllTied(buckets: string[][]): boolean {
  return buckets.length === 1 && buckets[0]!.length >= 2;
}

export function toggleTieAll(buckets: string[][]): string[][] {
  const flat = flattenBuckets(buckets);
  if (flat.length < 2) return buckets;
  if (isAllTied(buckets)) {
    return flat.map((id) => [id]);
  }
  return [flat];
}

export function jumpCardToSlot(buckets: string[][], id: string, oneBasedSlot: number): string[][] {
  const flat = flattenBuckets(buckets);
  if (!flat.includes(id)) return buckets;
  const cleaned = removeCard(buckets, id);
  const targetIdx = Math.max(0, Math.min(cleaned.length, oneBasedSlot - 1));
  cleaned.splice(targetIdx, 0, [id]);
  return cleaned;
}

export function dropCardOnTarget(
  buckets: string[][],
  draggedId: string,
  targetId: string,
  zone: 'before' | 'tie' | 'after',
): string[][] {
  if (draggedId === targetId) return buckets;
  const cleaned = removeCard(buckets, draggedId);
  const pos = findCardPosition(cleaned, targetId);
  if (!pos) return buckets;

  if (zone === 'tie') {
    cleaned[pos.bucketIdx]!.push(draggedId);
  } else if (zone === 'before') {
    cleaned.splice(pos.bucketIdx, 0, [draggedId]);
  } else {
    cleaned.splice(pos.bucketIdx + 1, 0, [draggedId]);
  }
  return cleaned;
}

export function excludeCard(
  state: RankBucketState,
  id: string,
  reason: ExclusionReason = 'manual',
): RankBucketState {
  if (id === state.focusId) return state;
  if (activeCardCount(state) <= MIN_K) return state;
  const pos = findCardPosition(state.buckets, id);
  if (!pos) return state;

  const nextBuckets = removeCard(state.buckets, id);
  const nextExcluded = new Map(state.excluded);
  nextExcluded.set(id, reason);
  const nextFlat = flattenBuckets(nextBuckets);
  const nextSelected =
    state.selectedId === id ? (nextFlat[0] ?? null) : state.selectedId;
  return {
    ...state,
    buckets: nextBuckets,
    excluded: nextExcluded,
    selectedId: nextSelected,
  };
}

export function restoreCard(state: RankBucketState, id: string): RankBucketState {
  if (!state.excluded.has(id)) return state;
  const nextExcluded = new Map(state.excluded);
  nextExcluded.delete(id);
  const nextBuckets = [...cloneBuckets(state.buckets), [id]];
  return {
    ...state,
    buckets: nextBuckets,
    excluded: nextExcluded,
  };
}

export function restoreAllCards(state: RankBucketState): RankBucketState {
  if (state.excluded.size === 0) return state;
  const nextBuckets = cloneBuckets(state.buckets);
  for (const id of state.priorityIds) {
    if (state.excluded.has(id)) {
      nextBuckets.push([id]);
    }
  }
  // Catch any excluded IDs not in priorityIds (defensive).
  for (const id of state.excluded.keys()) {
    if (!findCardPosition(nextBuckets, id)) {
      nextBuckets.push([id]);
    }
  }
  return {
    ...state,
    buckets: nextBuckets,
    excluded: new Map(),
  };
}

/**
 * Resizes the active cluster in-memory to `targetK` (when `targetK <= loadedTotal`):
 * - Shrinking drops lowest-priority non-focus active cards with reason `'size'`.
 * - Growing restores `'size'`-excluded cards first, then `'manual'`-excluded cards, in priority order.
 */
export function resizeClusterInMemory(state: RankBucketState, targetK: number): RankBucketState {
  const clamped = Math.max(MIN_K, Math.min(state.priorityIds.length, targetK));
  let cur = state;

  // Shrink if needed: drop lowest-priority active non-focus cards.
  while (activeCardCount(cur) > clamped) {
    const activeSet = new Set(flattenBuckets(cur.buckets));
    let dropCandidate: string | null = null;
    for (let i = cur.priorityIds.length - 1; i >= 0; i--) {
      const cand = cur.priorityIds[i]!;
      if (activeSet.has(cand) && cand !== cur.focusId) {
        dropCandidate = cand;
        break;
      }
    }
    if (!dropCandidate) break;
    cur = excludeCard(cur, dropCandidate, 'size');
  }

  // Grow if needed: restore 'size' exclusions first in priority order, then 'manual'.
  for (const targetReason of ['size', 'manual'] as const) {
    for (const id of cur.priorityIds) {
      if (activeCardCount(cur) >= clamped) break;
      if (cur.excluded.get(id) === targetReason) {
        cur = restoreCard(cur, id);
      }
    }
  }

  return cur;
}

export function manualExcludedIds(state: RankBucketState): string[] {
  const out: string[] = [];
  for (const [id, reason] of state.excluded.entries()) {
    if (reason === 'manual') out.push(id);
  }
  return out;
}

export function shortClusterToken(id: string): string {
  return id.slice(0, 8);
}

export function clusterSignature(ids: string[]): string {
  const tokens = Array.from(new Set(ids.map(shortClusterToken))).sort();
  return tokens.join('.');
}

export function appendCoveredSignature(existing: string | null | undefined, sig: string): string {
  const list = (existing ?? '')
    .trim()
    .split('|')
    .filter(Boolean);
  if (sig && !list.includes(sig)) {
    list.push(sig);
  }
  if (list.length > MAX_COVERED_CLUSTERS) {
    list.splice(0, list.length - MAX_COVERED_CLUSTERS);
  }
  return list.join('|');
}

export function mergeExcludedTokens(
  existing: string | null | undefined,
  newlyExcludedIds: string[],
  activeIds: string[],
): string {
  const activeTokens = new Set(activeIds.map(shortClusterToken));
  const list = (existing ?? '')
    .trim()
    .split('.')
    .filter((tok) => tok && !activeTokens.has(tok));
  for (const rawId of newlyExcludedIds) {
    const tok = shortClusterToken(rawId);
    if (tok && !activeTokens.has(tok) && !list.includes(tok)) {
      list.push(tok);
    }
  }
  if (list.length > MAX_EXCLUDED_TOKENS) {
    list.splice(0, list.length - MAX_EXCLUDED_TOKENS);
  }
  return list.join('.');
}
