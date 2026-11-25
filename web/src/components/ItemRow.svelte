<script lang="ts">
  import { api, ApiError } from '../lib/api/client';
  import { WANT_TO_READ_COLLECTION_ID, type Item } from '../lib/api/types';
  import { player } from '../lib/stores/player.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    clampExcerpt,
    contentTypeBadge,
    formatDuration,
    formatShortDate,
  } from '../lib/utils/format';
  import FeedFavicon from './FeedFavicon.svelte';
  import Icons from './Icons.svelte';

  interface Props {
    item: Item;
    titleHtml?: string;
    returnTo?: string;
    selected?: boolean;
    compact?: boolean;
    showProgress?: boolean;
    showExcerpt?: boolean;
    tagIdByLowerName?: Record<string, string>;
    primaryMode?: 'auto' | 'dismiss' | 'done';
    onStarChanged?: (id: string, isFavorite: boolean) => void;
    onQueueChanged?: (id: string, inQueue: boolean) => void;
    onTriageRemoved?: (id: string) => void;
    onTriageRestored?: () => void;
  }

  let {
    item,
    titleHtml,
    returnTo = '/inbox',
    selected = false,
    compact = false,
    showProgress = false,
    showExcerpt = true,
    tagIdByLowerName = {},
    primaryMode = 'auto',
    onStarChanged,
    onQueueChanged,
    onTriageRemoved,
    onTriageRestored,
  }: Props = $props();

  let busyStar = $state(false);
  let busyQueue = $state(false);
  let busyTriage = $state(false);
  let isFavorite = $derived(item.is_favorite);
  let inQueue = $derived(item.in_want_to_read);

  let readerHref = $derived(
    returnTo === '/inbox'
      ? `/reader/${encodeURIComponent(item.id)}`
      : `/reader/${encodeURIComponent(item.id)}?return_to=${encodeURIComponent(returnTo)}`,
  );
  let hasAudio = $derived(
    Boolean(item.audio_url) ||
      item.content_type === 'audio' ||
      item.entry_kind === 'podcast_episode',
  );
  let kindBadge = $derived(contentTypeBadge(item.content_type, hasAudio, item.entry_kind));
  let durationText = $derived(formatDuration(item.duration));
  let remainingText = $derived.by(() => {
    if (!item.duration || item.read_progress < 0.03 || item.read_progress >= 0.98) {
      return null;
    }
    const rem = Math.round(item.duration * (1 - item.read_progress));
    const fmt = formatDuration(rem);
    return fmt ? `${fmt} left` : null;
  });
  let isPlayingThis = $derived(player.isItemPlaying(item.id));
  let isDismissed = $derived(item.status === 'archived' && !item.is_finished);
  let isFinished = $derived(item.is_finished);
  let effectivePrimary = $derived<'dismiss' | 'done'>(
    primaryMode === 'auto' ? (item.feed_id ? 'dismiss' : 'done') : primaryMode,
  );
  let excerptText = $derived(showExcerpt && !compact ? clampExcerpt(item.summary, 220) : '');

  async function toggleStar(): Promise<void> {
    if (busyStar) return;
    const prev = isFavorite;
    const next = !prev;
    isFavorite = next;
    busyStar = true;
    try {
      await api.patch(`/api/v1/items/${encodeURIComponent(item.id)}`, { is_favorite: next });
      onStarChanged?.(item.id, next);
    } catch (err) {
      isFavorite = prev;
      toast.show(err instanceof ApiError ? err.message : 'Unable to update star.', { isError: true });
    } finally {
      busyStar = false;
    }
  }

  async function toggleQueue(): Promise<void> {
    if (busyQueue) return;
    const prev = inQueue;
    const next = !prev;
    inQueue = next;
    busyQueue = true;
    try {
      if (next) {
        await api.post(`/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries`, {
          entry_id: item.id,
        });
        toast.show('Added to Queue', {
          onUndo: async () => {
            await api.delete(
              `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries/${encodeURIComponent(item.id)}`,
            );
            inQueue = false;
            onQueueChanged?.(item.id, false);
          },
        });
      } else {
        await api.delete(
          `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries/${encodeURIComponent(item.id)}`,
        );
        toast.show('Removed from Queue', {
          onUndo: async () => {
            await api.post(
              `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries`,
              { entry_id: item.id },
            );
            inQueue = true;
            onQueueChanged?.(item.id, true);
          },
        });
      }
      onQueueChanged?.(item.id, next);
    } catch (err) {
      inQueue = prev;
      toast.show(err instanceof ApiError ? err.message : 'Unable to update queue.', {
        isError: true,
      });
    } finally {
      busyQueue = false;
    }
  }

  async function handleDismiss(): Promise<void> {
    if (busyTriage) return;
    busyTriage = true;
    try {
      await api.post(`/api/v1/items/${encodeURIComponent(item.id)}/dismiss`);
      onTriageRemoved?.(item.id);
      toast.show('Dismissed', {
        onUndo: async () => {
          await api.post(`/api/v1/items/${encodeURIComponent(item.id)}/mark-new`);
          onTriageRestored?.();
        },
      });
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to dismiss item.', {
        isError: true,
      });
    } finally {
      busyTriage = false;
    }
  }

  async function handleMarkNew(): Promise<void> {
    if (busyTriage) return;
    busyTriage = true;
    try {
      await api.post(`/api/v1/items/${encodeURIComponent(item.id)}/mark-new`);
      onTriageRemoved?.(item.id);
      toast.show('Put back to Inbox');
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to restore item.', {
        isError: true,
      });
    } finally {
      busyTriage = false;
    }
  }

  async function handleFinish(): Promise<void> {
    if (busyTriage) return;
    busyTriage = true;
    try {
      await api.post(`/api/v1/items/${encodeURIComponent(item.id)}/finish`, {});
      onTriageRemoved?.(item.id);
      toast.show('Marked finished & logged to Diary', {
        onUndo: async () => {
          await api.post(`/api/v1/items/${encodeURIComponent(item.id)}/unfinish`, { requeue: true });
          onTriageRestored?.();
        },
      });
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to finish item.', {
        isError: true,
      });
    } finally {
      busyTriage = false;
    }
  }

  function handlePlay(e: MouseEvent): void {
    player.playOrToggle({
      itemId: item.id,
      title: item.title,
      feed: item.feed_title,
      progress: item.read_progress,
      durationSeconds: item.duration,
      triggerEl: e.currentTarget as HTMLElement,
    });
  }
</script>

<li class="inbox-row js-kbd-row" class:is-selected={selected} class:is-compact={compact} data-item-id={item.id}>
  {#if !compact}
    <button
      type="button"
      class="btn btn-small btn-ghost btn-icon inbox-star-btn"
      class:is-starred={isFavorite}
      aria-label={isFavorite ? `Unstar ${item.title}` : `Star ${item.title}`}
      aria-pressed={isFavorite}
      disabled={busyStar}
      onclick={toggleStar}
    >
      {isFavorite ? '★' : '☆'}
    </button>
  {/if}

  {#if !compact && item.thumbnail_url}
    <a href={readerHref} class="inbox-thumb-link" tabindex="-1" aria-hidden="true">
      <img
        class="inbox-thumb"
        src={item.thumbnail_url}
        alt=""
        loading="lazy"
        referrerpolicy="no-referrer"
      />
    </a>
  {/if}

  <div class="inbox-body">
    <div class="inbox-title-line">
      <a href={readerHref} class="inbox-title js-kbd-open">
        {#if titleHtml}
          {@html titleHtml}
        {:else}
          {item.title}
        {/if}
      </a>
      {#if isFinished}
        <span class="badge badge-finished">✓ Finished</span>
      {:else if isDismissed}
        <span class="badge badge-dismissed">Dismissed</span>
      {/if}
      {#if kindBadge}
        <span class="badge badge-kind">{kindBadge}</span>
      {/if}
    </div>

    <div class="item-byline">
      {#if item.feed_title && item.feed_id}
        <a
          class="feed-byline-link"
          href={`/inbox?status=new&feed=${encodeURIComponent(item.feed_id)}`}
        >
          <FeedFavicon title={item.feed_title} url={item.feed_url ?? item.url} size="sm" />
          <span>{item.feed_title}</span>
        </a>
      {/if}
      {#if durationText}
        <span class="byline-item">{durationText}</span>
      {/if}
      {#if remainingText}
        <span class="byline-item">{remainingText}</span>
      {/if}
      {#if item.published_at || item.created_at}
        <span class="byline-item">{formatShortDate(item.published_at ?? item.created_at)}</span>
      {/if}
      {#each item.tag_names as tagName (tagName)}
        {@const tid = tagIdByLowerName[tagName.toLowerCase()]}
        {#if tid}
          <a class="tag-pill" href={`/inbox?status=all&tag=${encodeURIComponent(tid)}`}>#{tagName}</a>
        {:else}
          <span class="tag-pill">#{tagName}</span>
        {/if}
      {/each}
    </div>

    {#if showProgress && item.read_progress > 0}
      <div class="item-progress-row">
        <progress value={Math.round(item.read_progress * 100)} max="100"></progress>
        <span class="text-faint">{Math.round(item.read_progress * 100)}%</span>
      </div>
    {/if}

    {#if excerptText}
      <p class="inbox-excerpt">{excerptText}</p>
    {/if}
  </div>

  <div class="inbox-controls inbox-controls-subtle">
    {#if hasAudio}
      <button
        type="button"
        class="btn btn-small btn-ghost"
        aria-pressed={isPlayingThis}
        onclick={handlePlay}
      >
        {isPlayingThis ? '❚❚ Pause' : '▶ Play'}
      </button>
    {/if}

    {#if !compact && item.feed_id}
      <button
        type="button"
        class="btn btn-small btn-ghost"
        class:is-in-queue={inQueue}
        data-item-action="queue"
        disabled={busyQueue}
        onclick={toggleQueue}
      >
        {inQueue ? '✓ In Queue' : '+ Queue'}
      </button>
    {/if}

    {#if isDismissed}
      <button
        type="button"
        class="btn btn-small btn-ghost"
        data-item-action="restore"
        title="Put back to Inbox"
        aria-label={`Put ${item.title} back to Inbox`}
        disabled={busyTriage}
        onclick={handleMarkNew}
      >
        <Icons name="restore" />
        <span>Inbox</span>
      </button>
    {:else if effectivePrimary === 'done'}
      <button
        type="button"
        class="btn btn-small btn-ghost btn-quiet"
        data-item-action="done"
        title="Mark finished"
        disabled={busyTriage}
        onclick={handleFinish}
      >
        ✓ Done
      </button>
    {:else if !isFinished}
      <button
        type="button"
        class="btn btn-small btn-ghost btn-quiet"
        data-item-action="dismiss"
        title="Dismiss from Inbox"
        aria-label={`Dismiss ${item.title}`}
        disabled={busyTriage}
        onclick={handleDismiss}
      >
        <Icons name="dismiss" />
      </button>
    {/if}
  </div>
</li>

<style>
  li.inbox-row {
    display: flex;
    align-items: flex-start;
    gap: 0.65rem;
    padding: 0.72rem 0.45rem;
    border-bottom: 1px solid var(--rule-soft);
    border-radius: 2px;
  }

  li.inbox-row.is-compact {
    padding: 0.55rem 0.25rem;
  }

  li.inbox-row.is-selected {
    background: var(--paper-deep);
    outline: 1px solid var(--accent);
  }

  .inbox-star-btn {
    flex-shrink: 0;
    margin-top: 0.05rem;
    font-size: 1rem;
    color: var(--ink-faint);
  }

  .inbox-thumb-link {
    flex-shrink: 0;
    text-decoration: none;
  }

  .inbox-thumb {
    width: 3.4rem;
    height: 3.4rem;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule);
    display: block;
  }

  .inbox-body {
    flex: 1;
    min-width: 0;
  }

  .inbox-title-line {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.35rem 0.45rem;
  }

  .inbox-title {
    font-family: var(--display);
    font-size: 1.03rem;
    color: var(--ink);
    text-decoration: none;
    line-height: 1.3;
  }

  .inbox-title:hover {
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

  .feed-byline-link {
    display: inline-flex;
    align-items: center;
    gap: 0.32rem;
    color: var(--ink-soft);
    text-decoration: none;
  }

  .feed-byline-link:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .tag-pill {
    font-size: 0.74rem;
    color: var(--link);
    text-decoration: none;
  }

  .tag-pill:hover {
    text-decoration: underline;
  }

  .item-progress-row {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-top: 0.35rem;
    font-size: 0.74rem;
  }

  .item-progress-row progress {
    width: 7rem;
    height: 4px;
    accent-color: var(--accent);
  }

  .inbox-excerpt {
    margin: 0.32rem 0 0;
    font-size: 0.86rem;
    color: var(--ink-soft);
    line-height: 1.45;
  }

  .inbox-controls {
    display: flex;
    align-items: center;
    gap: 0.32rem;
    flex-shrink: 0;
  }

  .inbox-controls-subtle {
    opacity: 0.85;
    transition: opacity 120ms ease;
  }

  li.inbox-row:hover .inbox-controls-subtle,
  li.inbox-row:focus-within .inbox-controls-subtle {
    opacity: 1;
  }

  @media (max-width: 600px) {
    li.inbox-row {
      flex-wrap: wrap;
      gap: 0.35rem 0.55rem;
    }
    .inbox-controls {
      flex-basis: 100%;
      padding-left: 1.75rem;
    }
    li.inbox-row.is-compact .inbox-controls {
      padding-left: 0;
    }
    .inbox-controls-subtle {
      opacity: 1;
    }
  }
</style>
