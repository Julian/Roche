<script lang="ts">
  import { api, ApiError } from '../lib/api/client';
  import type { CatalogEntry, EntryLogResponse } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { entryKindLabel, formatShortDate } from '../lib/utils/format';
  import CreatorLinks from './CreatorLinks.svelte';
  import MathTitle from './MathTitle.svelte';

  interface Props {
    entry: CatalogEntry;
    ts: string;
    notes?: string | null;
    titleHtml?: string;
    artistLinks?: Record<string, string>;
    isReader?: boolean;
    returnTo?: string;
    onResolved?: (entryId: string) => void | Promise<void>;
  }

  let {
    entry,
    ts,
    notes = null,
    titleHtml,
    artistLinks = {},
    isReader = false,
    returnTo = '/queue',
    onResolved,
  }: Props = $props();

  let busy = $state(false);

  let href = $derived(
    isReader
      ? `/reader/${encodeURIComponent(entry.id)}?return_to=${encodeURIComponent(returnTo)}`
      : `/entries/${encodeURIComponent(entry.id)}`,
  );

  async function logStatus(status: 'consumed' | 'abandoned', rankAfter: boolean): Promise<void> {
    if (busy) return;
    busy = true;
    try {
      const res = await api.post<EntryLogResponse>(
        `/api/v1/catalog/entries/${encodeURIComponent(entry.id)}/log`,
        { status, rank_after: rankAfter },
      );
      if (status === 'consumed' && rankAfter && res.rank_slug) {
        const focusId = res.logged.entry.id || entry.id;
        toast.show(`Finished '${entry.title}' — rank globally`);
        router.navigate(
          `/rank/${encodeURIComponent(res.rank_slug)}?focus=${encodeURIComponent(focusId)}`,
        );
      } else {
        if (status === 'consumed') {
          toast.show(`Finished '${entry.title}' & logged to Diary`);
        } else {
          toast.show(`Skipped '${entry.title}'`);
        }
        await onResolved?.(entry.id);
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update entry.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }
</script>

<li class="inbox-row catalog-progress-row" data-entry-id={entry.id}>
  {#if entry.thumbnail_url}
    <a {href} class="catalog-thumb-link" tabindex="-1" aria-hidden="true">
      <img
        class="catalog-thumb"
        src={entry.thumbnail_url}
        alt=""
        loading="lazy"
        referrerpolicy="no-referrer"
      />
    </a>
  {/if}

  <div class="catalog-body">
    <div class="catalog-title-line">
      <a {href} class="catalog-title">
        <MathTitle html={titleHtml} text={entry.title} />
      </a>
      <span class="badge badge-started">Started</span>
      <span class="badge badge-kind">{entryKindLabel(entry.kind)}</span>
    </div>

    <div class="item-byline">
      {#if entry.creators.length > 0}
        <span class="byline-item">
          <CreatorLinks creators={entry.creators} {artistLinks} />
        </span>
      {/if}
      {#if entry.release_year}
        <span class="byline-item">{entry.release_year}</span>
      {/if}
      <span class="byline-item">Started {formatShortDate(ts)}</span>
    </div>

    {#if notes}
      <p class="catalog-notes">{notes}</p>
    {/if}
  </div>

  <div class="inbox-controls inbox-controls-subtle">
    <button
      type="button"
      class="btn btn-small btn-ghost btn-quiet"
      title="Mark finished without ranking"
      disabled={busy}
      onclick={() => void logStatus('consumed', false)}
    >
      ✓ Done
    </button>
    <button
      type="button"
      class="btn btn-small btn-primary"
      title="Mark finished and rank"
      disabled={busy}
      onclick={() => void logStatus('consumed', true)}
    >
      Finish &amp; Rank
    </button>
    <button
      type="button"
      class="btn btn-small btn-ghost btn-danger-quiet"
      title="Skip and clear from In Progress"
      aria-label={`Skip ${entry.title}`}
      disabled={busy}
      onclick={() => void logStatus('abandoned', false)}
    >
      Skip
    </button>
  </div>
</li>

<style>
  li.catalog-progress-row {
    display: flex;
    align-items: flex-start;
    gap: 0.65rem;
    padding: 0.72rem 0.45rem;
    border-bottom: 1px solid var(--rule-soft);
    border-radius: 2px;
  }

  .catalog-thumb-link {
    flex-shrink: 0;
    text-decoration: none;
  }

  .catalog-thumb {
    width: 3rem;
    height: 3rem;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule);
    display: block;
  }

  .catalog-body {
    flex: 1;
    min-width: 0;
  }

  .catalog-title-line {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.35rem 0.45rem;
  }

  .catalog-title {
    font-family: var(--display);
    font-size: 1.03rem;
    color: var(--ink);
    text-decoration: none;
    line-height: 1.3;
  }

  .catalog-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .item-byline {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.25rem 0.65rem;
    font-size: 0.8rem;
    color: var(--ink-faint);
    margin-top: 0.2rem;
  }

  .catalog-notes {
    margin: 0.25rem 0 0;
    font-size: 0.84rem;
    color: var(--ink-soft);
    line-height: 1.4;
  }

  .inbox-controls {
    display: flex;
    align-items: center;
    gap: 0.32rem;
    flex-shrink: 0;
    flex-wrap: wrap;
  }

  @media (max-width: 600px) {
    li.catalog-progress-row {
      flex-wrap: wrap;
      gap: 0.35rem 0.55rem;
    }
    .inbox-controls {
      flex-basis: 100%;
    }
  }
</style>
