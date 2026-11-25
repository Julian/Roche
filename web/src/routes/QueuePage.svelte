<script lang="ts">
  import { onMount } from 'svelte';
  import { api, ApiError } from '../lib/api/client';
  import type { EntryKind, ItemDetail, QueuesPayload, QuickLogResponse } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { refresh } from '../lib/stores/refresh.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    LOGGABLE_KINDS,
    entryKindLabel,
    extractDisplayHost,
    normalizeUrlInput,
    parseCsvList,
  } from '../lib/utils/format';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  let data = $state<QueuesPayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);
  let busy = $state(false);
  let loadController: AbortController | null = null;

  // Quick Save URL form
  let saveUrl = $state('');
  let saveTitle = $state('');

  // Quick Add to Queue form
  let addTitle = $state('');
  let addKind = $state<EntryKind>('movie');
  let addCreators = $state('');
  let addYear = $state('');
  let addAction = $state<'want' | 'consumed'>('want');
  let addRankAfter = $state(false);

  let readerIds = $derived(new Set(data?.reader_item_ids ?? []));

  async function loadQueues(): Promise<void> {
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    loading = true;
    errorMsg = null;
    try {
      const payload = await api.get<QueuesPayload>('/api/v1/catalog/queues', controller.signal);
      if (controller.signal.aborted) return;
      data = payload;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load queues.';
    } finally {
      if (!controller.signal.aborted) {
        loading = false;
      }
    }
  }

  onMount(() => {
    router.setTitle('Queue');
    void loadQueues();
    const unsub = refresh.onRefreshComplete(() => void loadQueues());
    return () => {
      unsub();
      loadController?.abort();
    };
  });

  async function handleComplete(
    queueSlug: string,
    entryId: string,
    title: string,
    rankAfter: boolean,
  ): Promise<void> {
    if (busy) return;
    busy = true;
    try {
      const res = await api.post<{ rank_slug: string | null }>(
        `/api/v1/catalog/want/${encodeURIComponent(queueSlug)}/complete/${encodeURIComponent(entryId)}`,
        { rank_after: rankAfter },
      );
      if (rankAfter && res.rank_slug) {
        toast.show('Completed from queue — rank globally');
        router.navigate(
          `/rank/${encodeURIComponent(res.rank_slug)}?focus=${encodeURIComponent(entryId)}`,
        );
      } else {
        toast.show(`Completed '${title}' & logged to Diary`);
        await loadQueues();
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to complete item.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }

  async function handleRemove(queueSlug: string, entryId: string, title: string): Promise<void> {
    if (busy) return;
    busy = true;
    try {
      await api.delete(
        `/api/v1/catalog/want/${encodeURIComponent(queueSlug)}/${encodeURIComponent(entryId)}`,
      );
      toast.show(`Removed '${title}' from queue`, {
        onUndo: async () => {
          await api.post(
            `/api/v1/catalog/entries/${encodeURIComponent(entryId)}/queue-toggle`,
            { queue: queueSlug },
          );
          await loadQueues();
        },
      });
      await loadQueues();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to remove from queue.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }

  async function handleSaveUrl(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const url = normalizeUrlInput(saveUrl);
    if (!url || busy) return;
    busy = true;
    try {
      const saved = await api.post<ItemDetail>('/api/v1/items/save', {
        url,
        title: saveTitle.trim() || null,
      });
      saveUrl = '';
      saveTitle = '';
      toast.show(`Saved '${saved.title}' to Queue`);
      await loadQueues();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to save URL.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleAddWork(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const title = addTitle.trim();
    if (!title || busy) return;
    busy = true;
    try {
      const yr = addYear.trim() ? Number.parseInt(addYear.trim(), 10) : null;
      const res = await api.post<QuickLogResponse>('/api/v1/catalog/quick-log', {
        title,
        kind: addKind,
        action: addAction,
        creators: parseCsvList(addCreators),
        release_year: yr && Number.isFinite(yr) ? yr : null,
        rank_after: addRankAfter,
      });
      addTitle = '';
      addCreators = '';
      addYear = '';
      toast.show(
        res.action === 'want'
          ? `Added '${res.entry.title}' to ${res.queue_title ?? 'Queue'}`
          : `Logged '${res.entry.title}' to Diary`,
      );
      if (res.rank_slug) {
        const wantQ = res.rank_want ? 'want=true&' : '';
        router.navigate(
          `/rank/${encodeURIComponent(res.rank_slug)}?${wantQ}focus=${encodeURIComponent(res.entry.id)}`,
        );
      } else {
        await loadQueues();
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to save item.', { isError: true });
    } finally {
      busy = false;
    }
  }
</script>

<div class="queue-grid">
  <section class="queue-main" aria-label="Want queues">
    <div class="list-detail-head">
      <div class="list-detail-title-group">
        <h2>Queue</h2>
        {#if data}
          <span class="list-count-badge">{data.total_items}</span>
        {/if}
      </div>
    </div>

    {#if errorMsg}
      <div class="notice notice-error" role="alert">{errorMsg}</div>
    {:else if loading && !data}
      <p class="empty">Loading queue…</p>
    {:else if data && data.queues.length === 0}
      <p class="empty">All queues are empty. Save a URL or add a book, movie, or album on the right.</p>
    {:else if data}
      {#each data.queues as qview (qview.collection.id)}
        {@const qSlug = qview.collection.slug ?? qview.collection.id}
        {@const baseSlug = qSlug.startsWith('want:') ? qSlug.slice(5) : qSlug}
        <section class="queue-section">
          <div class="section-head">
            <h3>
              {qview.collection.title}
              <span class="list-count-badge">{qview.rows.length}</span>
            </h3>
            {#if qview.rows.length >= 2}
              <a
                class="section-action"
                href={`/rank/${encodeURIComponent(baseSlug)}?want=true`}
              >
                Rank Queue →
              </a>
            {/if}
          </div>

          <ol class="queue-list">
            {#each qview.rows as row, idx (row.entry.id)}
              {@const isReader = readerIds.has(row.entry.id)}
              {@const titleHref = isReader
                ? `/reader/${encodeURIComponent(row.entry.id)}?return_to=%2Fqueue`
                : `/entries/${encodeURIComponent(row.entry.id)}`}
              {@const host = extractDisplayHost(row.entry.url)}
              <li class="queue-item-row">
                <div class="queue-item-left">
                  <span class="queue-rank-num">{idx + 1}</span>
                  <div class="queue-item-main">
                    <div class="queue-item-title">
                      <a href={titleHref}>
                        <MathTitle html={data.title_html[row.entry.id]} text={row.entry.title} />
                      </a>
                    </div>
                    <div class="queue-item-meta">
                      {#if row.entry.creators.length > 0}
                        <span>
                          <CreatorLinks creators={row.entry.creators} artistLinks={data.artist_links} />
                        </span>
                      {/if}
                      {#if host}
                        <span>{host}</span>
                      {/if}
                      <span>{entryKindLabel(row.entry.kind)}</span>
                    </div>
                  </div>
                </div>

                <div class="inbox-controls">
                  {#if isReader}
                    <a
                      class="btn btn-small btn-ghost"
                      href={`/reader/${encodeURIComponent(row.entry.id)}?return_to=%2Fqueue`}
                    >
                      Read
                    </a>
                  {/if}
                  <button
                    type="button"
                    class="btn btn-small btn-ghost btn-quiet"
                    title="Mark finished without ranking"
                    disabled={busy}
                    onclick={() => void handleComplete(qSlug, row.entry.id, row.entry.title, false)}
                  >
                    ✓ Done
                  </button>
                  <button
                    type="button"
                    class="btn btn-small btn-primary"
                    title="Mark finished and rank"
                    disabled={busy}
                    onclick={() => void handleComplete(qSlug, row.entry.id, row.entry.title, true)}
                  >
                    Finish &amp; Rank
                  </button>
                  <span class="controls-sep" aria-hidden="true"></span>
                  <button
                    type="button"
                    class="btn btn-small btn-ghost btn-danger-quiet"
                    aria-label={`Remove ${row.entry.title} from queue`}
                    title="Remove from queue"
                    disabled={busy}
                    onclick={() => void handleRemove(qSlug, row.entry.id, row.entry.title)}
                  >
                    ×
                  </button>
                </div>
              </li>
            {/each}
          </ol>
        </section>
      {/each}
    {/if}
  </section>

  <aside class="queue-rail" aria-label="Add to queue">
    <section class="rail-block rail-card">
      <h3 class="mt-0">Quick Save URL</h3>
      <form class="rail-form" onsubmit={handleSaveUrl}>
        <div class="field">
          <label for="queue-save-url">URL</label>
          <input
            id="queue-save-url"
            type="text"
            required
            placeholder="https://example.org/essay"
            bind:value={saveUrl}
          />
        </div>
        <div class="field">
          <label for="queue-save-title">Title (optional)</label>
          <input id="queue-save-title" type="text" placeholder="Auto-detected" bind:value={saveTitle} />
        </div>
        <button type="submit" class="btn btn-primary btn-small" disabled={busy}>Save to Queue</button>
      </form>
    </section>

    <section class="rail-block rail-card">
      <details class="editorial-panel rail-editorial-panel" open data-default-open>
        <summary>+ Add to Queue or Log</summary>
        <form class="rail-form" onsubmit={handleAddWork}>
          <div class="field">
            <label for="queue-add-title">Title</label>
            <input id="queue-add-title" type="text" required placeholder="e.g. Stalker" bind:value={addTitle} />
          </div>
          <div class="field">
            <label for="queue-add-kind">Type</label>
            <select id="queue-add-kind" bind:value={addKind}>
              {#each LOGGABLE_KINDS as k (k.value)}
                <option value={k.value}>{k.label}</option>
              {/each}
            </select>
          </div>
          <div class="field">
            <label for="queue-add-action">Action</label>
            <select id="queue-add-action" bind:value={addAction}>
              <option value="want">Want Queue</option>
              <option value="consumed">Finished (Diary)</option>
            </select>
          </div>
          <div class="field">
            <label for="queue-add-creators">Creators</label>
            <input id="queue-add-creators" type="text" placeholder="Andrei Tarkovsky" bind:value={addCreators} />
          </div>
          <div class="field">
            <label for="queue-add-year">Year</label>
            <input id="queue-add-year" type="number" min="1000" max="2100" placeholder="1979" bind:value={addYear} />
          </div>
          <div class="field field-check">
            <input id="queue-add-rank" type="checkbox" bind:checked={addRankAfter} />
            <label for="queue-add-rank">Rank immediately</label>
          </div>
          <button type="submit" class="btn btn-primary btn-small" disabled={busy}>Save</button>
        </form>
      </details>
    </section>
  </aside>
</div>

<style>
  .queue-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(20rem, 24.5rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .queue-section {
    margin-bottom: 1.75rem;
  }

  ol.queue-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  li.queue-item-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    padding: 0.65rem 0;
    border-bottom: 1px solid var(--rule-soft);
    flex-wrap: wrap;
  }

  .queue-item-left {
    display: flex;
    align-items: baseline;
    gap: 0.65rem;
    flex: 1;
    min-width: 14rem;
  }

  .queue-rank-num {
    width: 1.6rem;
    flex-shrink: 0;
    text-align: right;
    font-family: var(--mono);
    font-size: 0.8rem;
    color: var(--ink-faint);
  }

  .queue-item-title a {
    font-family: var(--display);
    font-size: 1.03rem;
    color: var(--ink);
    text-decoration: none;
  }

  .queue-item-title a:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .queue-item-meta {
    display: flex;
    flex-wrap: wrap;
    gap: 0.25rem 0.6rem;
    font-size: 0.8rem;
    color: var(--ink-faint);
    margin-top: 0.12rem;
  }

  .inbox-controls {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    flex-wrap: wrap;
  }

  @media (max-width: 880px) {
    .queue-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
