<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import { api, ApiError } from '../lib/api/client';
  import type { Feed, FeedType } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { refresh } from '../lib/stores/refresh.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    extractDisplayHost,
    feedTypeLabel,
    formatRelativeAge,
    normalizeUrlInput,
  } from '../lib/utils/format';
  import FeedFavicon from '../components/FeedFavicon.svelte';

  let feeds = $state<Feed[]>([]);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  // Filters synced with URL
  let statusFilter = $state<'all' | 'active' | 'errors' | 'paused'>('all');
  let kindFilter = $state<'all' | FeedType>('all');
  let searchQuery = $state('');

  // Subscribe form
  let subUrl = $state('');
  let subType = $state<FeedType>('rss');
  let subTitle = $state('');
  let subBusy = $state(false);

  // OPML import
  let opmlText = $state('');
  let opmlBusy = $state(false);

  // Per-feed inline edit drafts
  let editDrafts = $state<Record<string, { url: string; title: string; poll_interval: string; feed_type: FeedType }>>({});
  let busyIds = $state<Set<string>>(new Set());
  let loadController: AbortController | null = null;

  function feedTitle(feed: Feed): string {
    return feed.title?.trim() || feed.url;
  }

  let activeCount = $derived(feeds.filter((f) => f.is_active).length);
  let pausedCount = $derived(feeds.filter((f) => !f.is_active).length);
  let errorCount = $derived(feeds.filter((f) => f.is_active && Boolean(f.last_error)).length);

  let filteredFeeds = $derived.by(() => {
    const q = searchQuery.trim().toLowerCase();
    return [...feeds]
      .sort((a, b) => {
        const rank = (f: Feed) => (f.is_active && f.last_error ? 0 : f.is_active ? 1 : 2);
        return rank(a) - rank(b) || feedTitle(a).localeCompare(feedTitle(b));
      })
      .filter((f) => {
        if (statusFilter === 'active' && !f.is_active) return false;
        if (statusFilter === 'paused' && f.is_active) return false;
        if (statusFilter === 'errors' && (!f.is_active || !f.last_error)) return false;
        if (kindFilter !== 'all' && f.feed_type !== kindFilter) return false;
        if (q && !`${feedTitle(f)} ${f.url}`.toLowerCase().includes(q)) return false;
        return true;
      });
  });

  function syncUrl(): void {
    const sp = new URLSearchParams();
    if (statusFilter !== 'all') sp.set('status', statusFilter);
    if (kindFilter !== 'all') sp.set('kind', kindFilter);
    if (searchQuery.trim()) sp.set('q', searchQuery.trim());
    router.replaceSearch(sp);
  }

  async function loadFeeds(): Promise<void> {
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    loading = true;
    errorMsg = null;
    try {
      const res = await api.get<{ feeds: Feed[] }>('/api/v1/feeds', controller.signal);
      if (controller.signal.aborted) return;
      feeds = res.feeds;
      const drafts: typeof editDrafts = {};
      for (const f of res.feeds) {
        drafts[f.id] = {
          url: f.url,
          title: f.title ?? '',
          poll_interval: String(f.poll_interval),
          feed_type: f.feed_type,
        };
      }
      editDrafts = drafts;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load feeds.';
    } finally {
      if (!controller.signal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    const q = router.route.query;
    const st = q.get('status');
    statusFilter =
      st === 'active' || st === 'errors' || st === 'paused' ? st : 'all';
    const kd = q.get('kind');
    kindFilter =
      kd === 'rss' || kd === 'podcast' || kd === 'youtube' || kd === 'arxiv' ? kd : 'all';
    const nextQ = q.get('q') ?? '';
    if (untrack(() => searchQuery).trim() !== nextQ) {
      searchQuery = nextQ;
    }
  });

  onMount(() => {
    router.setTitle('Feeds');
    void loadFeeds();
    const unsub = refresh.onRefreshComplete(() => void loadFeeds());
    return () => {
      unsub();
      loadController?.abort();
    };
  });

  async function handleSubscribe(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const url = normalizeUrlInput(subUrl);
    if (!url || subBusy) return;
    subBusy = true;
    try {
      const created = await api.post<Feed>('/api/v1/feeds?background=true', {
        url,
        feed_type: subType,
        title: subTitle.trim() || null,
      });
      subUrl = '';
      subTitle = '';
      toast.show(`Subscribed to '${feedTitle(created)}' (refreshing in background)`);
      refresh.startPolling();
      await loadFeeds();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to subscribe to feed.', {
        isError: true,
      });
    } finally {
      subBusy = false;
    }
  }

  async function handleSaveFeed(e: SubmitEvent, feed: Feed): Promise<void> {
    e.preventDefault();
    const draft = editDrafts[feed.id];
    if (!draft) return;
    busyIds = new Set([...busyIds, feed.id]);
    try {
      const pollInt = Number.parseInt(draft.poll_interval, 10);
      const normalizedUrl = normalizeUrlInput(draft.url);
      const urlChanged = normalizedUrl !== feed.url;
      await api.patch(`/api/v1/feeds/${encodeURIComponent(feed.id)}?background=true`, {
        url: urlChanged ? normalizedUrl : undefined,
        title: draft.title.trim() || undefined,
        poll_interval: Number.isFinite(pollInt) ? Math.max(5, Math.min(10080, pollInt)) : undefined,
        feed_type: draft.feed_type,
      });
      toast.show(urlChanged ? 'Updated feed URL (refreshing in background)' : 'Updated feed');
      if (urlChanged) refresh.startPolling();
      await loadFeeds();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update feed.', {
        isError: true,
      });
    } finally {
      const next = new Set(busyIds);
      next.delete(feed.id);
      busyIds = next;
    }
  }

  async function handleRefreshFeed(feed: Feed): Promise<void> {
    busyIds = new Set([...busyIds, feed.id]);
    try {
      await api.post(`/api/v1/feeds/${encodeURIComponent(feed.id)}/refresh?background=true`);
      refresh.startPolling();
      toast.show(`Refreshing '${feedTitle(feed)}'…`);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to refresh feed.', {
        isError: true,
      });
    } finally {
      const next = new Set(busyIds);
      next.delete(feed.id);
      busyIds = next;
    }
  }

  async function handleToggleActive(feed: Feed): Promise<void> {
    busyIds = new Set([...busyIds, feed.id]);
    try {
      const ep = feed.is_active ? 'archive' : 'unarchive';
      await api.post(`/api/v1/feeds/${encodeURIComponent(feed.id)}/${ep}`);
      toast.show(feed.is_active ? `Paused '${feedTitle(feed)}'` : `Resumed '${feedTitle(feed)}'`);
      await loadFeeds();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update feed.', {
        isError: true,
      });
    } finally {
      const next = new Set(busyIds);
      next.delete(feed.id);
      busyIds = next;
    }
  }

  async function handleDeleteFeed(feed: Feed): Promise<void> {
    const label = feedTitle(feed);
    if (!window.confirm(`Delete feed '${label}' and stop polling it?`)) return;
    busyIds = new Set([...busyIds, feed.id]);
    try {
      await api.delete(`/api/v1/feeds/${encodeURIComponent(feed.id)}`);
      feeds = feeds.filter((f) => f.id !== feed.id);
      toast.show(`Deleted '${label}'`);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to delete feed.', {
        isError: true,
      });
    } finally {
      const next = new Set(busyIds);
      next.delete(feed.id);
      busyIds = next;
    }
  }

  function handleOpmlFileChange(e: Event): void {
    const input = e.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      opmlText = typeof reader.result === 'string' ? reader.result : '';
    };
    reader.readAsText(file);
  }

  async function handleImportOpml(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!opmlText.trim() || opmlBusy) return;
    opmlBusy = true;
    try {
      const res = await api.post<{ imported_count: number }>(
        '/api/v1/import/opml?background=true',
        { opml: opmlText },
      );
      opmlText = '';
      toast.show(`Imported ${res.imported_count} feed${res.imported_count === 1 ? '' : 's'}`);
      if (res.imported_count > 0) refresh.startPolling();
      await loadFeeds();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'OPML import failed.', { isError: true });
    } finally {
      opmlBusy = false;
    }
  }

  async function copyText(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      toast.show('Copied URL to clipboard');
    } catch {
      toast.show('Could not copy to clipboard', { isError: true });
    }
  }
</script>

<div class="feeds-grid">
  <section class="feeds-main" aria-label="Subscribed feeds">
    <div class="list-detail-head">
      <div class="list-detail-title-group">
        <h2>Feeds</h2>
        <span class="list-count-badge">{filteredFeeds.length}</span>
      </div>
    </div>

    <div class="feeds-toolbar">
      <div class="pill-bar mt-0 mb-0" role="group" aria-label="Feed status filter">
        {#each [
          { key: 'all', label: `All (${feeds.length})` },
          { key: 'active', label: `Active (${activeCount})` },
          { key: 'errors', label: `Errors (${errorCount})` },
          { key: 'paused', label: `Paused (${pausedCount})` },
        ] as opt (opt.key)}
          <button
            type="button"
            class="pill"
            class:is-active={statusFilter === opt.key}
            aria-pressed={statusFilter === opt.key}
            onclick={() => {
              statusFilter = opt.key as typeof statusFilter;
              syncUrl();
            }}
          >
            {opt.label}
          </button>
        {/each}
      </div>

      <div class="feeds-toolbar-right">
        <label for="roche-feeds-kind" class="sr-only">Filter by feed type</label>
        <select
          id="roche-feeds-kind"
          bind:value={kindFilter}
          onchange={syncUrl}
        >
          <option value="all">All types</option>
          <option value="rss">RSS</option>
          <option value="podcast">Podcast</option>
          <option value="youtube">YouTube</option>
          <option value="arxiv">arXiv</option>
        </select>

        <label for="roche-feeds-search" class="sr-only">Search feeds</label>
        <input
          id="roche-feeds-search"
          type="search"
          placeholder="Filter feeds…"
          bind:value={searchQuery}
          oninput={syncUrl}
        />
      </div>
    </div>

    {#if errorMsg}
      <div class="notice notice-error" role="alert">{errorMsg}</div>
    {/if}

    {#if loading && feeds.length === 0}
      <p class="empty">Loading feeds…</p>
    {:else if filteredFeeds.length === 0}
      <p class="empty">No feeds match the current filters.</p>
    {:else}
      <ul class="feeds-list">
        {#each filteredFeeds as feed (feed.id)}
          {@const draft = editDrafts[feed.id]}
          {@const busy = busyIds.has(feed.id)}
          {@const title = feedTitle(feed)}
          <li class="feed-card" class:is-paused={!feed.is_active}>
            <div class="feed-card-top">
              <FeedFavicon {title} url={feed.url} size="lg" />
              <div class="feed-card-body">
                <div class="feed-title-line">
                  <a
                    class="feed-title"
                    href={`/inbox?status=${feed.unread_count > 0 ? 'new' : 'all'}&feed=${encodeURIComponent(feed.id)}`}
                  >
                    {title}
                  </a>
                  {#if feed.feed_type !== 'rss'}
                    <span class="badge">{feedTypeLabel(feed.feed_type)}</span>
                  {/if}
                  {#if !feed.is_active}
                    <span class="badge badge-dismissed">Paused</span>
                  {/if}
                  {#if feed.unread_count > 0}
                    <span class="list-count-badge">{feed.unread_count} new</span>
                  {/if}
                </div>
                <div class="feed-meta">
                  <span>{extractDisplayHost(feed.url) ?? feed.url}</span>
                  {#if feed.last_polled}
                    <span>· Polled {formatRelativeAge(feed.last_polled)}</span>
                  {/if}
                </div>
                {#if feed.is_active && feed.last_error}
                  <div class="feed-error" role="status">Error: {feed.last_error}</div>
                {/if}
              </div>

              <div class="feed-card-actions">
                <button
                  type="button"
                  class="btn btn-small btn-ghost"
                  disabled={busy || !feed.is_active}
                  onclick={() => void handleRefreshFeed(feed)}
                >
                  Refresh
                </button>
                <button
                  type="button"
                  class="btn btn-small btn-ghost"
                  disabled={busy}
                  onclick={() => void handleToggleActive(feed)}
                >
                  {feed.is_active ? 'Pause' : 'Resume'}
                </button>
                <button
                  type="button"
                  class="btn btn-small btn-ghost btn-danger-quiet"
                  aria-label={`Delete ${title}`}
                  disabled={busy}
                  onclick={() => void handleDeleteFeed(feed)}
                >
                  Delete
                </button>
              </div>
            </div>

            {#if draft}
              <details class="feed-edit-details">
                <summary>Details &amp; Edit</summary>
                <form class="rail-form mt-sm" onsubmit={(e) => void handleSaveFeed(e, feed)}>
                  <div class="field">
                    <label for={`feed-url-${feed.id}`}>Feed URL</label>
                    <div class="flex-row">
                      <input
                        id={`feed-url-${feed.id}`}
                        class="flex-1"
                        type="text"
                        inputmode="url"
                        required
                        bind:value={draft.url}
                      />
                      <button
                        type="button"
                        class="btn btn-small btn-ghost"
                        onclick={() => void copyText(draft.url)}
                      >
                        Copy URL
                      </button>
                    </div>
                  </div>
                  <div class="form-grid">
                    <div class="field">
                      <label for={`feed-title-${feed.id}`}>Title</label>
                      <input id={`feed-title-${feed.id}`} type="text" required bind:value={draft.title} />
                    </div>
                    <div class="field">
                      <label for={`feed-poll-${feed.id}`}>Poll Interval (min)</label>
                      <input
                        id={`feed-poll-${feed.id}`}
                        type="number"
                        min="5"
                        max="10080"
                        bind:value={draft.poll_interval}
                      />
                    </div>
                    {#if feed.feed_type === 'rss' || feed.feed_type === 'podcast'}
                      <div class="field">
                        <label for={`feed-type-${feed.id}`}>Feed Format</label>
                        <select id={`feed-type-${feed.id}`} bind:value={draft.feed_type}>
                          <option value="rss">RSS / Blog</option>
                          <option value="podcast">Podcast</option>
                        </select>
                      </div>
                    {/if}
                  </div>
                  <div class="flex-row">
                    <button type="submit" class="btn btn-small btn-primary" disabled={busy}>
                      Save Changes
                    </button>
                  </div>
                </form>
              </details>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  </section>

  <aside class="feeds-rail" aria-label="Feed management tools">
    <section class="rail-block rail-card">
      <h3 class="mt-0">Subscribe to Feed</h3>
      <form class="rail-form" onsubmit={handleSubscribe}>
        <div class="field">
          <label for="sub-url">Feed or Site URL</label>
          <input
            id="sub-url"
            type="text"
            required
            placeholder="https://blog.example/rss.xml"
            bind:value={subUrl}
          />
        </div>
        <div class="field">
          <label for="sub-type">Type</label>
          <select id="sub-type" bind:value={subType}>
            <option value="rss">RSS / Atom</option>
            <option value="podcast">Podcast</option>
            <option value="youtube">YouTube</option>
            <option value="arxiv">arXiv</option>
          </select>
        </div>
        <div class="field">
          <label for="sub-title">Title (optional)</label>
          <input id="sub-title" type="text" placeholder="Auto-detected if blank" bind:value={subTitle} />
        </div>
        <button type="submit" class="btn btn-primary btn-small" disabled={subBusy}>
          {subBusy ? 'Subscribing…' : 'Subscribe'}
        </button>
      </form>
    </section>

    <section class="rail-block rail-card">
      <h3 class="mt-0">Feed Health</h3>
      <div class="health-stats">
        <button
          type="button"
          class="health-stat"
          onclick={() => {
            statusFilter = 'active';
            syncUrl();
          }}
        >
          <span class="health-num">{activeCount}</span>
          <span class="health-label">Active</span>
        </button>
        <button
          type="button"
          class="health-stat"
          onclick={() => {
            statusFilter = 'paused';
            syncUrl();
          }}
        >
          <span class="health-num">{pausedCount}</span>
          <span class="health-label">Paused</span>
        </button>
        <button
          type="button"
          class="health-stat"
          onclick={() => {
            statusFilter = 'errors';
            syncUrl();
          }}
        >
          <span class="health-num" class:is-err={errorCount > 0}>{errorCount}</span>
          <span class="health-label">Failing</span>
        </button>
      </div>
    </section>

    <section class="rail-block rail-card">
      <h3 class="mt-0">OPML Backup</h3>
      <div class="flex-row mb-sm">
        <a
          class="btn btn-small btn-ghost"
          href="/api/v1/export/feeds"
          download="roche-feeds.opml"
          data-native-nav="1"
        >
          Export OPML
        </a>
      </div>
      <details class="editorial-panel rail-editorial-panel">
        <summary>Import OPML</summary>
        <form class="rail-form mt-sm" onsubmit={handleImportOpml}>
          <div class="field">
            <label for="opml-file">Choose .opml file</label>
            <input id="opml-file" type="file" accept=".opml,.xml,text/xml" onchange={handleOpmlFileChange} />
          </div>
          <div class="field">
            <label for="opml-xml">Or paste OPML XML</label>
            <textarea id="opml-xml" bind:value={opmlText} placeholder="<opml version='2.0'>…"></textarea>
          </div>
          <button type="submit" class="btn btn-primary btn-small" disabled={opmlBusy || !opmlText.trim()}>
            {opmlBusy ? 'Importing…' : 'Import OPML'}
          </button>
        </form>
      </details>
    </section>
  </aside>
</div>

<style>
  .feeds-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(20rem, 24.5rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .feeds-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.65rem;
    margin-bottom: 1rem;
  }

  .feeds-toolbar-right {
    display: flex;
    gap: 0.45rem;
    flex-wrap: wrap;
  }

  .feeds-toolbar-right input,
  .feeds-toolbar-right select {
    padding: 0.28rem 0.55rem;
    font-size: 0.84rem;
  }

  ul.feeds-list {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 0.7rem;
  }

  .feed-card {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    padding: 0.8rem 0.95rem;
  }

  .feed-card.is-paused {
    opacity: 0.72;
  }

  .feed-card-top {
    display: flex;
    align-items: flex-start;
    gap: 0.75rem;
    flex-wrap: wrap;
  }

  .feed-card-body {
    flex: 1;
    min-width: 12rem;
  }

  .feed-title-line {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.4rem;
  }

  .feed-title {
    font-family: var(--display);
    font-size: 1.06rem;
    color: var(--ink);
    text-decoration: none;
  }

  .feed-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .feed-meta {
    font-size: 0.8rem;
    color: var(--ink-faint);
    margin-top: 0.15rem;
  }

  .feed-error {
    margin-top: 0.35rem;
    font-size: 0.8rem;
    color: var(--accent);
  }

  .feed-card-actions {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    flex-wrap: wrap;
  }

  .feed-edit-details {
    margin-top: 0.6rem;
    padding-top: 0.5rem;
    border-top: 1px solid var(--rule-soft);
  }

  .feed-edit-details summary {
    cursor: pointer;
    font-size: 0.78rem;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--ink-soft);
  }

  .health-stats {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 0.5rem;
  }

  .health-stat {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 0.55rem 0.4rem;
    background: var(--paper);
    border: 1px solid var(--rule);
    border-radius: 2px;
    cursor: pointer;
    font-family: var(--serif);
    color: var(--ink);
  }

  .health-stat:hover {
    border-color: var(--control-border);
  }

  .health-num {
    font-family: var(--display);
    font-size: 1.35rem;
    line-height: 1.1;
  }

  .health-num.is-err {
    color: var(--accent);
  }

  .health-label {
    font-size: 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--ink-faint);
    margin-top: 0.2rem;
  }

  @media (max-width: 880px) {
    .feeds-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
