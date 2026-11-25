<script lang="ts">
  import { onMount } from 'svelte';
  import { api, ApiError, buildQuery } from '../lib/api/client';
  import type { CollectionSummary, ListsOverviewPayload } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { ALL_SEARCHABLE_KINDS, entryKindLabel, isDynamicCollection } from '../lib/utils/format';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import Icons from '../components/Icons.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  const VIEW_KEY = 'roche_lists_view_v1';

  let data = $state<ListsOverviewPayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  let viewMode = $state<'previews' | 'compact'>('previews');
  let showNewListForm = $state(false);
  let newListTitle = $state('');
  let newListSummary = $state('');
  let createBusy = $state(false);

  let searchQ = $state('');
  let searchKind = $state('');

  let qParam = $derived(router.route.query.get('q') ?? '');
  let kindParam = $derived(router.route.query.get('kind') ?? '');
  let dynamicIds = $derived(new Set(data?.dynamic_collection_ids ?? []));

  let manualLists = $derived.by(() => {
    if (!data) return [];
    return data.collections
      .filter((c) => !isDynamicCollection(c, dynamicIds))
      .sort(
        (a, b) =>
          b.comparison_count - a.comparison_count ||
          b.member_count - a.member_count ||
          a.title.localeCompare(b.title),
      );
  });

  let mediaActive = $derived.by(() => {
    if (!data) return [];
    return data.collections
      .filter((c) => isDynamicCollection(c, dynamicIds) && c.member_count > 0)
      .sort(
        (a, b) =>
          b.comparison_count - a.comparison_count ||
          b.member_count - a.member_count ||
          a.title.localeCompare(b.title),
      );
  });

  let mediaEmpty = $derived.by(() => {
    if (!data) return [];
    return data.collections
      .filter((c) => isDynamicCollection(c, dynamicIds) && c.member_count === 0)
      .sort((a, b) => a.title.localeCompare(b.title));
  });

  let totalActiveLists = $derived(
    (data?.collections ?? []).filter((c) => c.member_count > 0).length,
  );
  let totalEntries = $derived(
    (data?.collections ?? [])
      .filter((c) => isDynamicCollection(c, dynamicIds))
      .reduce((acc, c) => acc + c.member_count, 0),
  );
  let totalRanked = $derived(
    (data?.collections ?? []).filter((c) => c.comparison_count > 0).length,
  );

  async function loadOverview(signal: AbortSignal): Promise<void> {
    loading = true;
    errorMsg = null;
    try {
      const qs = buildQuery({ q: qParam || undefined, kind: kindParam || undefined });
      const payload = await api.get<ListsOverviewPayload>(
        `/api/v1/catalog/lists/overview${qs}`,
        signal,
      );
      if (signal.aborted) return;
      data = payload;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load lists.';
    } finally {
      if (!signal.aborted) {
        loading = false;
      }
    }
  }

  onMount(() => {
    router.setTitle('Lists');
    try {
      const saved = localStorage.getItem(VIEW_KEY);
      if (saved === 'compact' || saved === 'previews') viewMode = saved;
    } catch {
      // Ignore
    }
  });

  $effect(() => {
    searchQ = qParam;
    searchKind = kindParam;
    const controller = new AbortController();
    void loadOverview(controller.signal);
    return () => controller.abort();
  });

  function setDensity(next: 'previews' | 'compact'): void {
    viewMode = next;
    try {
      localStorage.setItem(VIEW_KEY, next);
    } catch {
      // Ignore
    }
  }

  async function handleCreateList(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const title = newListTitle.trim();
    if (!title || createBusy) return;
    createBusy = true;
    try {
      const created = await api.post<CollectionSummary>('/api/v1/catalog/lists', {
        title,
        summary: newListSummary.trim() || null,
      });
      newListTitle = '';
      newListSummary = '';
      showNewListForm = false;
      toast.show(`Created list '${created.title}'`);
      router.navigate(`/lists/${encodeURIComponent(created.slug ?? created.id)}`);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to create list.', {
        isError: true,
      });
    } finally {
      createBusy = false;
    }
  }

  function handleSearchSubmit(e: SubmitEvent): void {
    e.preventDefault();
    const qs = buildQuery({ q: searchQ.trim() || undefined, kind: searchKind || undefined });
    router.navigate(`/lists${qs}`);
  }
</script>

<div class="lists-grid">
  <section class="lists-main" id="roche-lists-main" data-lists-view={viewMode} aria-label="Lists">
    <div class="lists-top-bar">
      <h2>Lists</h2>
      <div class="view-mode-switch" role="group" aria-label="Display density">
        <button
          type="button"
          class="view-mode-btn"
          class:is-active={viewMode === 'previews'}
          aria-pressed={viewMode === 'previews'}
          onclick={() => setDensity('previews')}
        >
          <Icons name="previews_view" />
          <span>Previews</span>
        </button>
        <button
          type="button"
          class="view-mode-btn"
          class:is-active={viewMode === 'compact'}
          aria-pressed={viewMode === 'compact'}
          onclick={() => setDensity('compact')}
        >
          <Icons name="compact_view" />
          <span>Compact</span>
        </button>
      </div>
    </div>

    {#if errorMsg}
      <div class="notice notice-error" role="alert">{errorMsg}</div>
    {/if}

    {#if data?.invalid_kind}
      <div class="notice notice-error" role="alert">
        Unknown entry kind filter '{data.invalid_kind}'
      </div>
    {/if}

    {#if data?.search_entries}
      <section class="catalog-search-block" aria-label="Catalog search results">
        <div class="section-head mt-0">
          <h3>
            Catalog Matches ({data.search_entries.length}{data.search_truncated ? '+' : ''})
          </h3>
          <a class="section-action" href="/lists">Clear search</a>
        </div>
        {#if data.search_entries.length === 0}
          <p class="empty">No matching catalog works found.</p>
        {:else}
          <ul class="catalog-search-list">
            {#each data.search_entries as entry (entry.id)}
              <li>
                <a class="entry-link" href={`/entries/${encodeURIComponent(entry.id)}`}>
                  <MathTitle html={data.title_html[entry.id]} text={entry.title} />
                </a>
                <span class="badge">{entryKindLabel(entry.kind)}</span>
                {#if entry.creators.length > 0 || entry.release_year}
                  <div class="entry-sub">
                    <CreatorLinks creators={entry.creators} artistLinks={data.artist_links} />
                    {#if entry.release_year}
                      {#if entry.creators.length > 0} · {/if}{entry.release_year}
                    {/if}
                  </div>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      </section>
    {/if}

    <div class="section-head">
      <h3>Your Lists</h3>
      <button
        type="button"
        class="btn btn-small btn-ghost js-new-list-toggle"
        aria-expanded={showNewListForm}
        onclick={() => {
          showNewListForm = !showNewListForm;
        }}
      >
        + New List
      </button>
    </div>

    {#if showNewListForm}
      <form class="new-list-form" data-list-form="create" onsubmit={handleCreateList}>
        <div class="field">
          <label for="list-title">List Title</label>
          <input
            id="list-title"
            name="title"
            type="text"
            required
            placeholder="e.g. Favorite Essays"
            bind:value={newListTitle}
          />
        </div>
        <div class="field">
          <label for="list-summary">Summary (optional)</label>
          <input
            id="list-summary"
            name="summary"
            type="text"
            placeholder="What belongs here?"
            bind:value={newListSummary}
          />
        </div>
        <div class="flex-row">
          <button type="submit" class="btn btn-primary btn-small" disabled={createBusy}>
            {createBusy ? 'Creating…' : 'Create List'}
          </button>
          <button
            type="button"
            class="btn btn-small btn-ghost"
            onclick={() => {
              showNewListForm = false;
            }}
          >
            Cancel
          </button>
        </div>
      </form>
    {/if}

    {#if loading && !data}
      <p class="empty">Loading lists…</p>
    {:else if manualLists.length === 0}
      <p class="empty">No custom lists yet. Use + New List to start one.</p>
    {:else}
      <ul class="lists-active-list">
        {#each manualLists as col (col.id)}
          {@const slug = col.slug ?? col.id}
          {@const thumbs = data?.previews[col.id] ?? []}
          {@const gen = data?.generators[col.id]}
          <li class="list-row">
            <div class="list-row-main">
              <div class="list-row-head">
                <a class="list-row-title" href={`/lists/${encodeURIComponent(slug)}`}>
                  {col.title}
                </a>
                <span class="list-count-badge">{col.member_count}</span>
                {#if gen?.enabled}
                  <span class="badge">Draw Active</span>
                {/if}
              </div>
              {#if col.summary}
                <p class="list-row-summary">{col.summary}</p>
              {/if}
              {#if viewMode === 'previews' && thumbs.length > 0}
                <div class="preview-strip">
                  {#each thumbs as [title, thumbUrl], thumbIdx (`${thumbIdx}-${thumbUrl}`)}
                    <img
                      class="preview-thumb"
                      src={thumbUrl}
                      alt={title}
                      loading="lazy"
                      referrerpolicy="no-referrer"
                    />
                  {/each}
                </div>
              {/if}
            </div>
            <div class="list-row-actions">
              {#if col.member_count >= 2}
                <a
                  class="btn btn-small btn-ghost"
                  href={`/rank/${encodeURIComponent(slug)}`}
                >
                  Rank{col.comparison_count > 0 ? ` · ${col.comparison_count}` : ''}
                </a>
              {/if}
            </div>
          </li>
        {/each}
      </ul>
    {/if}

    {#if mediaActive.length > 0 || mediaEmpty.length > 0}
      <div class="section-head">
        <h3>By Media Type</h3>
        <span class="section-action">{mediaActive.length} active · {mediaEmpty.length} empty</span>
      </div>

      {#if mediaActive.length > 0}
        <ul class="lists-active-list">
          {#each mediaActive as col (col.id)}
            {@const slug = col.slug ?? col.id}
            {@const thumbs = data?.previews[col.id] ?? []}
            <li class="list-row">
              <div class="list-row-main">
                <div class="list-row-head">
                  <a class="list-row-title" href={`/lists/${encodeURIComponent(slug)}`}>
                    {col.title}
                  </a>
                  <span class="list-count-badge">{col.member_count}</span>
                </div>
                {#if viewMode === 'previews' && thumbs.length > 0}
                  <div class="preview-strip">
                    {#each thumbs as [title, thumbUrl], thumbIdx (`${thumbIdx}-${thumbUrl}`)}
                      <img
                        class="preview-thumb"
                        src={thumbUrl}
                        alt={title}
                        loading="lazy"
                        referrerpolicy="no-referrer"
                      />
                    {/each}
                  </div>
                {/if}
              </div>
              <div class="list-row-actions">
                {#if col.member_count >= 2}
                  <a class="btn btn-small btn-ghost" href={`/rank/${encodeURIComponent(slug)}`}>
                    Rank{col.comparison_count > 0 ? ` · ${col.comparison_count}` : ''}
                  </a>
                {/if}
              </div>
            </li>
          {/each}
        </ul>
      {/if}

      {#if mediaEmpty.length > 0}
        <ul class="lists-empty-tiles">
          {#each mediaEmpty as col (col.id)}
            {@const slug = col.slug ?? col.id}
            <li>
              <a class="empty-tile" href={`/lists/${encodeURIComponent(slug)}`}>
                <span>{col.title}</span>
                <span class="text-faint">0</span>
              </a>
            </li>
          {/each}
        </ul>
      {/if}
    {/if}
  </section>

  <aside class="lists-rail" aria-label="Catalog search and stats">
    <section class="rail-block rail-card">
      <h3 class="mt-0">Search Catalog</h3>
      <form class="rail-form" onsubmit={handleSearchSubmit}>
        <div class="field">
          <label for="catalog-search-q">Title or Creator</label>
          <input
            id="catalog-search-q"
            type="search"
            placeholder="e.g. Design"
            bind:value={searchQ}
          />
        </div>
        <div class="field">
          <label for="catalog-search-kind">Media Type</label>
          <select id="catalog-search-kind" bind:value={searchKind}>
            <option value="">All types</option>
            {#each ALL_SEARCHABLE_KINDS as k (k.value)}
              <option value={k.value}>{k.label}</option>
            {/each}
          </select>
        </div>
        <div class="flex-row">
          <button type="submit" class="btn btn-primary btn-small">Search</button>
          {#if qParam || kindParam}
            <a class="btn btn-small btn-ghost" href="/lists">Reset</a>
          {/if}
        </div>
      </form>
    </section>

    <section class="rail-block rail-card">
      <h3 class="mt-0">Library</h3>
      <dl class="stats-dl">
        <div><dt>Active lists</dt><dd>{totalActiveLists}</dd></div>
        <div><dt>Catalog works</dt><dd>{totalEntries}</dd></div>
        <div><dt>Ranked lists</dt><dd>{totalRanked}</dd></div>
      </dl>
    </section>
  </aside>
</div>

<style>
  .lists-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(20rem, 24.5rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .lists-top-bar {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.75rem;
    flex-wrap: wrap;
    margin-bottom: 0.75rem;
  }

  .lists-top-bar h2 {
    margin: 0;
  }

  .view-mode-switch {
    display: inline-flex;
    border: 1px solid var(--control-border);
    border-radius: 2px;
    overflow: hidden;
  }

  .view-mode-btn {
    display: inline-flex;
    align-items: center;
    gap: 0.3rem;
    padding: 0.25rem 0.6rem;
    border: none;
    background: transparent;
    color: var(--ink-soft);
    font-family: var(--serif);
    font-size: 0.76rem;
    cursor: pointer;
  }

  .view-mode-btn.is-active {
    background: var(--ink);
    color: var(--paper);
  }

  .new-list-form {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    padding: 0.85rem 1rem;
    margin: 0.5rem 0 1rem;
    display: flex;
    flex-direction: column;
    gap: 0.6rem;
  }

  ul.lists-active-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  li.list-row {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 0.75rem;
    padding: 0.72rem 0;
    border-bottom: 1px solid var(--rule-soft);
  }

  .list-row-main {
    flex: 1;
    min-width: 0;
  }

  .list-row-head {
    display: flex;
    align-items: baseline;
    gap: 0.45rem;
    flex-wrap: wrap;
  }

  .list-row-title {
    font-family: var(--display);
    font-size: 1.08rem;
    color: var(--ink);
    text-decoration: none;
  }

  .list-row-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .list-row-summary {
    margin: 0.2rem 0 0;
    font-size: 0.84rem;
    color: var(--ink-soft);
  }

  .preview-strip {
    display: flex;
    gap: 0.35rem;
    margin-top: 0.45rem;
    flex-wrap: wrap;
  }

  .preview-thumb {
    width: 2.35rem;
    height: 2.35rem;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule);
  }

  .lists-empty-tiles {
    list-style: none;
    padding: 0;
    margin: 0.75rem 0 0;
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
    gap: 0.45rem;
  }

  .empty-tile {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0.42rem 0.65rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    text-decoration: none;
    color: var(--ink-soft);
    font-size: 0.84rem;
  }

  .empty-tile:hover {
    border-color: var(--control-border);
    color: var(--ink);
  }

  .catalog-search-block {
    margin-bottom: 1.5rem;
  }

  .catalog-search-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .catalog-search-list li {
    padding: 0.45rem 0;
    border-bottom: 1px solid var(--rule-soft);
  }

  .entry-link {
    font-family: var(--display);
    font-size: 1rem;
    color: var(--ink);
    text-decoration: none;
    margin-right: 0.4rem;
  }

  .entry-link:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .entry-sub {
    font-size: 0.8rem;
    color: var(--ink-faint);
  }

  .stats-dl {
    margin: 0;
    font-size: 0.88rem;
  }

  .stats-dl > div {
    display: flex;
    justify-content: space-between;
    padding: 0.3rem 0;
    border-bottom: 1px solid var(--rule-soft);
  }

  .stats-dl dt {
    color: var(--ink-soft);
  }

  .stats-dl dd {
    margin: 0;
    font-family: var(--mono);
    color: var(--ink);
  }

  @media (max-width: 880px) {
    .lists-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
