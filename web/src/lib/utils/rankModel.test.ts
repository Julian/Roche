import { describe, expect, it } from 'vitest';
import {
  MAX_K_DEFAULT,
  MIN_K,
  canMoveDown,
  canMoveUp,
  createRankState,
  dropCardOnTarget,
  excludeCard,
  flattenBuckets,
  isCardTied,
  jumpCardToSlot,
  moveCardDown,
  moveCardUp,
  rankNumbersForBuckets,
  resizeClusterInMemory,
  restoreAllCards,
  restoreCard,
  toggleTieAll,
  toggleTieWithAbove,
} from './rankModel';

describe('rankModel', () => {
  it('initializes singleton buckets and computes competition rank numbers', () => {
    const state = createRankState(['a', 'b', 'c', 'd'], ['a', 'b', 'c', 'd'], 'a');
    expect(state.buckets).toEqual([['a'], ['b'], ['c'], ['d']]);
    expect(state.focusId).toBe('a');
    expect(state.selectedId).toBe('a');
    expect(flattenBuckets(state.buckets)).toEqual(['a', 'b', 'c', 'd']);

    const ranks = rankNumbersForBuckets([['a'], ['b', 'c'], ['d']]);
    expect(ranks.get('a')).toBe(1);
    expect(ranks.get('b')).toBe(2);
    expect(ranks.get('c')).toBe(2);
    expect(ranks.get('d')).toBe(4);
  });

  it('moves cards up/down, ties and unties cleanly', () => {
    let buckets = [['a'], ['b'], ['c']];
    expect(canMoveUp(buckets, 'a')).toBe(false);
    expect(canMoveDown(buckets, 'c')).toBe(false);

    buckets = moveCardUp(buckets, 'b');
    expect(buckets).toEqual([['b'], ['a'], ['c']]);

    buckets = toggleTieWithAbove(buckets, 'a');
    expect(buckets).toEqual([['b', 'a'], ['c']]);
    expect(isCardTied(buckets, 'a')).toBe(true);
    expect(isCardTied(buckets, 'c')).toBe(false);

    // Moving a tied card up breaks it out above its tie bucket
    buckets = moveCardUp(buckets, 'a');
    expect(buckets).toEqual([['a'], ['b'], ['c']]);

    // Tie all & untie all
    buckets = toggleTieAll(buckets);
    expect(buckets).toEqual([['a', 'b', 'c']]);
    buckets = toggleTieAll(buckets);
    expect(buckets).toEqual([['a'], ['b'], ['c']]);

    buckets = moveCardDown(buckets, 'a');
    expect(buckets).toEqual([['b'], ['a'], ['c']]);
  });

  it('jumps to slot and handles drag-and-drop zones (before, tie, after)', () => {
    let buckets = [['a'], ['b'], ['c']];
    buckets = jumpCardToSlot(buckets, 'c', 1);
    expect(buckets).toEqual([['c'], ['a'], ['b']]);

    buckets = dropCardOnTarget(buckets, 'b', 'c', 'tie');
    expect(buckets).toEqual([['c', 'b'], ['a']]);

    buckets = dropCardOnTarget(buckets, 'a', 'c', 'before');
    expect(buckets).toEqual([['a'], ['c', 'b']]);

    buckets = dropCardOnTarget(buckets, 'a', 'b', 'after');
    expect(buckets).toEqual([['c', 'b'], ['a']]);
  });

  it('protects focusId and MIN_K floor on exclude, resize, and restore', () => {
    let state = createRankState(['focus', 'b', 'c', 'd'], ['focus', 'b', 'c', 'd'], 'focus');

    // Cannot exclude focus card
    const unchanged = excludeCard(state, 'focus');
    expect(flattenBuckets(unchanged.buckets)).toHaveLength(4);

    // Resize down to 3 moves trailing non-focus card to excluded with reason 'size'
    state = resizeClusterInMemory(state, 3);
    expect(flattenBuckets(state.buckets)).toEqual(['focus', 'b', 'c']);
    expect(state.excluded.get('d')).toBe('size');

    // Exclude one more manually down to MIN_K (2)
    state = excludeCard(state, 'c');
    expect(flattenBuckets(state.buckets)).toEqual(['focus', 'b']);
    expect(state.excluded.size).toBe(2);

    // Cannot exclude below MIN_K
    const atFloor = excludeCard(state, 'b');
    expect(flattenBuckets(atFloor.buckets)).toHaveLength(MIN_K);

    // Restore single card
    state = restoreCard(state, 'c');
    expect(flattenBuckets(state.buckets)).toEqual(['focus', 'b', 'c']);
    expect(state.excluded.size).toBe(1);
    expect(state.excluded.get('d')).toBe('size');

    // Restore all
    state = restoreAllCards(state);
    expect(flattenBuckets(state.buckets)).toEqual(['focus', 'b', 'c', 'd']);
    expect(state.excluded.size).toBe(0);
    expect(MAX_K_DEFAULT).toBeGreaterThanOrEqual(4);
  });
});
