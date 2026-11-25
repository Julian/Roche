<script lang="ts">
  import { api, ApiError } from '../lib/api/client';
  import type { RichSearchPayload } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import {
    consumptionStatusLabel,
    entryKindLabel,
    formatShortDate,
  } from '../lib/utils/format';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  let data = $state<RichSearchPayload | null>(null);
  let loading = $state(false);
  let errorMsg = $state<string | null>(null);
  let draft = $state('');

  let qParam = $derived((router.route.query.get('q') ?? '').trim());

  let totalHits = $derived.by(() => {
    if (!data) return 0;
    const r = data.results;
    return (
      r.catalog_entries.length +
      r.collections.length +
      r.want_entries.length +
      r.inbox_items.length +
      r.diary_logs.length
    );
  });

  let readerIds = $derived(new Set(data?.reader_item_ids ?? []));

  async function runSearch(query: string, signal: AbortSignal): Promise<void> {
    if (!query) {
      data = null;
      errorMsg = null;
      loading = false;
      router.setTitle('Search');
      return;
    }
    loading = true;
    errorMsg = null;
    router.setTitle(`Search: ${query}`);
    try {
      const payload = await api.get<RichSearchPayload>(
        `/api/v1/search/rich?q=${encodeURIComponent(query)}&limit=24`,
        signal,
      );
      if (signal.aborted) return;
      data = payload;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Search failed.';
    } finally {
      if (!signal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    const query = qParam;
    draft = query;
    const controller = new AbortController();
    void runSearch(query, controller.signal);
    return () => controller.abort();
  });

  function handleSubmit(e: SubmitEvent): void {
    e.preventDefault();
    const clean = draft.trim();
    router.navigate(clean ? `/search?q=${encodeURIComponent(clean)}` : '/search');
  }
</script>

<section class="search-page" aria-label="Workspace search">
  <div class="list-detail-head">
    <div class="list-detail-title-group">
      <h2>Search</h2>
      {#if qParam && data}
        <span class="list-count-badge">{totalHits}</span>
      {/if}
    </div>
  </div>

  <form class="search-form" onsubmit={handleSubmit}>
    <label for="page-search-input" class="sr-only">Search across Roche</label>
    <input
      id="page-search-input"
      type="search"
      class="search-input"
      placeholder="Search works, lists, queue, inbox, diary…"
      bind:value={draft}
    />
    <button type="submit" class="btn btn-primary">Search</button>
    {#if qParam}
      <a class="btn btn-ghost" href="/search">Clear</a>
    {/if}
  </form>

  {#if errorMsg}
    <div class="notice notice-error" role="alert">{errorMsg}</div>
  {:else if loading && !data}
    <p class="empty">Searching…</p>
  {:else if !qParam}
    <p class="empty">Enter a query above to search your catalog, lists, queue, inbox, and diary.</p>
  {:else if data && totalHits === 0}
    <p class="empty">No results found for “{qParam}”.</p>
  {:else if data}
    {@const r = data.results}
    <div class="pill-bar" aria-label="Jump to result group">
      {#if r.catalog_entries.length > 0}
        <a class="pill" href="#search-catalog">Catalog ({r.catalog_entries.length})</a>
      {/if}
      {#if r.collections.length > 0}
        <a class="pill" href="#search-lists">Lists ({r.collections.length})</a>
      {/if}
      {#if r.want_entries.length > 0}
        <a class="pill" href="#search-queue">Queue ({r.want_entries.length})</a>
      {/if}
      {#if r.inbox_items.length > 0}
        <a class="pill" href="#search-inbox">Inbox ({r.inbox_items.length})</a>
      {/if}
      {#if r.diary_logs.length > 0}
        <a class="pill" href="#search-diary">Diary ({r.diary_logs.length})</a>
      {/if}
    </div>

    {#if r.catalog_entries.length > 0}
      <section id="search-catalog">
        <h3>Catalog Works ({r.catalog_entries.length})</h3>
        <ul class="search-results-list">
          {#each r.catalog_entries as entry (entry.id)}
            <li>
              <a class="result-title" href={`/entries/${encodeURIComponent(entry.id)}`}>
                <MathTitle html={data.title_html[entry.id]} text={entry.title} />
              </a>
              <span class="badge">{entryKindLabel(entry.kind)}</span>
              {#if entry.creators.length > 0 || entry.release_year}
                <div class="result-meta">
                  <CreatorLinks creators={entry.creators} artistLinks={data.artist_links} />
                  {#if entry.release_year}
                    {#if entry.creators.length > 0} · {/if}{entry.release_year}
                  {/if}
                </div>
              {/if}
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if r.collections.length > 0}
      <section id="search-lists">
        <h3>Lists ({r.collections.length})</h3>
        <ul class="search-results-list">
          {#each r.collections as col (col.id)}
            <li>
              <a
                class="result-title"
                href={`/lists/${encodeURIComponent(col.slug ?? col.id)}`}
              >
                {col.title}
              </a>
              <span class="list-count-badge">{col.member_count}</span>
              {#if col.summary}
                <div class="result-meta">{col.summary}</div>
              {/if}
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if r.want_entries.length > 0}
      <section id="search-queue">
        <h3>Queue &amp; Want ({r.want_entries.length})</h3>
        <ul class="search-results-list">
          {#each r.want_entries as item (`${item.queue_id}-${item.entry.id}`)}
            {@const href = readerIds.has(item.entry.id)
              ? `/reader/${encodeURIComponent(item.entry.id)}?return_to=${encodeURIComponent(router.fullPath)}`
              : `/entries/${encodeURIComponent(item.entry.id)}`}
            <li>
              <a class="result-title" {href}>
                <MathTitle html={data.title_html[item.entry.id]} text={item.entry.title} />
              </a>
              <span class="badge badge-queue">{item.queue_title}</span>
              {#if item.entry.creators.length > 0}
                <div class="result-meta">
                  <CreatorLinks creators={item.entry.creators} artistLinks={data.artist_links} />
                </div>
              {/if}
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if r.inbox_items.length > 0}
      <section id="search-inbox">
        <h3>Feed Inbox ({r.inbox_items.length})</h3>
        <ul class="search-results-list">
          {#each r.inbox_items as item (item.id)}
            <li>
              <a
                class="result-title"
                href={`/reader/${encodeURIComponent(item.id)}?return_to=${encodeURIComponent(router.fullPath)}`}
              >
                <MathTitle html={data.title_html[item.id]} text={item.title} />
              </a>
              {#if item.is_finished}
                <span class="badge badge-finished">✓ Finished</span>
              {:else if item.status === 'archived'}
                <span class="badge badge-dismissed">Dismissed</span>
              {/if}
              <div class="result-meta">
                {item.feed_title ?? 'Saved'} · {formatShortDate(item.published_at ?? item.created_at)}
              </div>
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    {#if r.diary_logs.length > 0}
      <section id="search-diary">
        <h3>Diary ({r.diary_logs.length})</h3>
        <ul class="search-results-list">
          {#each r.diary_logs as log (log.id)}
            <li>
              <a class="result-title" href={`/entries/${encodeURIComponent(log.entry.id)}`}>
                <MathTitle html={data.title_html[log.entry.id]} text={log.entry.title} />
              </a>
              <span class="badge">{consumptionStatusLabel(log.status)}</span>
              <div class="result-meta">
                {formatShortDate(log.ts)}
                {#if log.entry.creators.length > 0}
                  · <CreatorLinks creators={log.entry.creators} artistLinks={data.artist_links} />
                {/if}
                {#if log.notes}
                  — {log.notes}
                {/if}
              </div>
            </li>
          {/each}
        </ul>
      </section>
    {/if}
  {/if}
</section>

<style>
  .search-page {
    max-width: 56rem;
  }

  ul.search-results-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  ul.search-results-list li {
    padding: 0.55rem 0;
    border-bottom: 1px solid var(--rule-soft);
  }

  .result-title {
    font-family: var(--display);
    font-size: 1.02rem;
    color: var(--ink);
    text-decoration: none;
    margin-right: 0.45rem;
  }

  .result-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .result-meta {
    font-size: 0.82rem;
    color: var(--ink-faint);
    margin-top: 0.15rem;
  }
</style>
