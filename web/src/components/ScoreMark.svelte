<script lang="ts">
  import type { RankingScore } from '../lib/api/types';

  interface Props {
    score: RankingScore;
    barPct: number;
    tier: 'S' | 'A' | 'B' | 'C' | 'D' | null;
  }

  let { score, barPct, tier }: Props = $props();

  const W = 88;
  const H = 14;
  const PAD = 4;
  const TRACK = W - PAD * 2;

  let mu = $derived(Number.isFinite(score?.mean) ? score.mean : 0);
  let sigma = $derived(
    Number.isFinite(score?.variance) ? Math.sqrt(Math.max(0, score.variance)) : 1,
  );
  let clampedPct = $derived(Number.isFinite(barPct) ? Math.max(0, Math.min(100, barPct)) : 50);
  let score10 = $derived(clampedPct / 10);
  let cx = $derived(PAD + (clampedPct / 100) * TRACK);
  // Show a ±1σ band mapped roughly onto the 0..10 display scale (clamped to track bounds).
  let halfBand = $derived(Math.min(TRACK * 0.35, Math.max(2, (sigma / 3.2) * TRACK)));
  let bandX1 = $derived(Math.max(PAD, cx - halfBand));
  let bandX2 = $derived(Math.min(W - PAD, cx + halfBand));
  let tierColor = $derived(tier ? `var(--tier-${tier})` : 'var(--ink-soft)');
</script>

<span
  class="score-wrap"
  title={`Score ${score10.toFixed(1)}/10 (μ=${mu.toFixed(2)}, σ=${sigma.toFixed(2)})`}
>
  <svg
    class="score-mark"
    width={W}
    height={H}
    viewBox={`0 0 ${W} ${H}`}
    aria-hidden="true"
  >
    <line x1={PAD} y1={H / 2} x2={W - PAD} y2={H / 2} stroke="var(--rule)" stroke-width="1.5" />
    <line
      x1={bandX1}
      y1={H / 2}
      x2={bandX2}
      y2={H / 2}
      stroke={tierColor}
      stroke-width="3.5"
      stroke-linecap="round"
      opacity="0.38"
    />
    <circle cx={cx} cy={H / 2} r="3.2" fill={tierColor} />
  </svg>
  <span class="score-num">{score10.toFixed(1)}</span>
</span>

<style>
  .score-wrap {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
    white-space: nowrap;
  }

  .score-mark {
    display: inline-block;
    vertical-align: middle;
  }

  .score-num {
    font-family: var(--mono);
    font-size: 0.78rem;
    color: var(--ink-soft);
    font-variant-numeric: tabular-nums;
    min-width: 2.1rem;
    text-align: right;
  }
</style>
