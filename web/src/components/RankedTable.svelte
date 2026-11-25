<script lang="ts">
  import type { RankedEntryRow } from '../lib/api/types';
  import { entryKindLabel } from '../lib/utils/format';
  import CreatorLinks from './CreatorLinks.svelte';
  import MathTitle from './MathTitle.svelte';
  import ScoreMark from './ScoreMark.svelte';

  interface Props {
    rows: RankedEntryRow[];
    rankedMode: boolean;
    artistLinks?: Record<string, string>;
    readerItemIds?: Set<string>;
    titleHtml?: Record<string, string>;
    showRemove?: boolean;
    onRemove?: (entryId: string, title: string) => void;
  }

  let {
    rows,
    rankedMode,
    artistLinks = {},
    readerItemIds = new Set(),
    titleHtml = {},
    showRemove = false,
    onRemove,
  }: Props = $props();
</script>

<table class="ranked">
  <thead>
    <tr>
      <th class="rank-col" scope="col">#</th>
      {#if rankedMode}
        <th class="tier-col" scope="col">Tier</th>
      {/if}
      <th scope="col">Work</th>
      {#if rankedMode}
        <th class="score" scope="col">Score</th>
      {/if}
      {#if showRemove}
        <th class="action-col" scope="col"><span class="sr-only">Remove</span></th>
      {/if}
    </tr>
  </thead>
  <tbody id="roche-ranked-tbody">
    {#each rows as row, idx (row.entry.id)}
      {@const prevTier = idx > 0 ? rows[idx - 1]?.tier : row.tier}
      {@const isTierBreak = rankedMode && idx > 0 && row.tier !== prevTier}
      {@const hasReader = readerItemIds.has(row.entry.id)}
      <tr class:tier-break={isTierBreak}>
        <td class="rank-col">{row.rank}</td>
        {#if rankedMode}
          <td class="tier-col">
            {#if row.tier}
              <span class="tier-pill tier-{row.tier}">{row.tier}</span>
            {:else}
              <span class="text-faint">—</span>
            {/if}
          </td>
        {/if}
        <td class="work-col">
          <div class="work-cell">
            {#if row.entry.thumbnail_url}
              <img
                class="work-thumb"
                src={row.entry.thumbnail_url}
                alt=""
                loading="lazy"
                referrerpolicy="no-referrer"
              />
            {/if}
            <div class="work-info">
              <div class="work-title-row">
                <a class="work-title" href={`/entries/${encodeURIComponent(row.entry.id)}`}>
                  <MathTitle html={titleHtml[row.entry.id]} text={row.entry.title} />
                </a>
                {#if hasReader}
                  <a
                    class="badge"
                    href={`/reader/${encodeURIComponent(row.entry.id)}`}
                  >
                    Read →
                  </a>
                {/if}
              </div>
              <div class="work-meta">
                {#if row.entry.creators.length > 0}
                  <CreatorLinks creators={row.entry.creators} {artistLinks} />
                {/if}
                {#if row.entry.release_year}
                  {#if row.entry.creators.length > 0} · {/if}
                  {row.entry.release_year}
                {/if}
                {#if row.entry.creators.length > 0 || row.entry.release_year} · {/if}
                <span>{entryKindLabel(row.entry.kind)}</span>
              </div>
            </div>
          </div>
        </td>
        {#if rankedMode}
          <td class="score">
            <ScoreMark score={row.score} barPct={row.bar_pct} tier={row.tier} />
          </td>
        {/if}
        {#if showRemove}
          <td class="action-col">
            <button
              type="button"
              class="btn btn-small btn-ghost btn-danger-quiet"
              aria-label={`Remove ${row.entry.title} from list`}
              title="Remove from list"
              onclick={() => onRemove?.(row.entry.id, row.entry.title)}
            >
              ×
            </button>
          </td>
        {/if}
      </tr>
    {/each}
  </tbody>
</table>

<style>
  table.ranked {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.92rem;
  }

  table.ranked th {
    font-size: 0.72rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--ink-faint);
    font-weight: 600;
    text-align: left;
    padding: 0.4rem 0.5rem;
    border-bottom: 1.5px solid var(--rule);
  }

  table.ranked td {
    padding: 0.58rem 0.5rem;
    border-bottom: 1px solid var(--rule-soft);
    vertical-align: middle;
  }

  table.ranked tr.tier-break td {
    border-top: 2px solid var(--rule);
  }

  .rank-col {
    width: 2.5rem;
    font-family: var(--mono);
    font-size: 0.8rem;
    color: var(--ink-faint);
    font-variant-numeric: tabular-nums;
  }

  .tier-col {
    width: 3rem;
  }

  .score {
    width: 7.5rem;
    text-align: right;
  }

  .action-col {
    width: 2.2rem;
    text-align: right;
  }

  .work-cell {
    display: flex;
    align-items: center;
    gap: 0.65rem;
    min-width: 0;
  }

  .work-thumb {
    width: 2.4rem;
    height: 2.4rem;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule);
    flex-shrink: 0;
  }

  .work-info {
    min-width: 0;
  }

  .work-title-row {
    display: flex;
    align-items: baseline;
    gap: 0.45rem;
    flex-wrap: wrap;
  }

  .work-title {
    font-family: var(--display);
    font-size: 0.99rem;
    color: var(--ink);
    text-decoration: none;
  }

  .work-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .work-meta {
    font-size: 0.79rem;
    color: var(--ink-faint);
    margin-top: 0.1rem;
  }

  @media (max-width: 600px) {
    th.score,
    td.score {
      display: none;
    }
  }
</style>
