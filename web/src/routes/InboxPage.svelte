<script lang="ts">
  import { onMount } from 'svelte';
  import { api, ApiError, buildQuery } from '../lib/api/client';
  import type { Feed, InboxPagePayload, Item, SyncStatus, Tag } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { refresh } from '../lib/stores/refresh.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { formatDayGroupHeading, safeHttpHref } from '../lib/utils/format';
  import FeedFavicon from '../components/FeedFavicon.svelte';
  import ItemRow from '../components/ItemRow.svelte';

  const PER_PAGE = 40;
  const FORMAT_OPTIONS = [
    { key: 'article', label: 'Articles' },
    { key: 'podcast', label: 'Podcasts' },
    { key: 'audio', label: 'Audio' },
    { key: 'paper', label: 'Papers' },
    { key: 'pdf', label: 'PDFs' },
    { key: 'video', label: 'Videos' },
  ] as const;

  let items = $state<Item[]>([]);
  let titleHtmlMap = $state<Record<string, string>>({});
  let hasMore = $state(false);
  let feeds = $state<Feed[]>([]);
  let tags = $state<Tag[]>([]);
  let totalUnread = $state(0);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  let sidebarFeedFilter = $state('');
  let searchDraft = $state('');
  let selectedIdx = $state(-1);
  let batchBusy = $state(false);
  let loadController: AbortController | null = null;

  function feedLabel(feed: Feed): string {
    return feed.title?.trim() || feed.url;
  }

  let statusParam = $derived.by(() => {
    const raw = (router.route.query.get('status') ?? 'new').toLowerCase();
    if (raw === 'finished' || raw === 'dismissed' || raw === 'all') return raw;
    if (raw === 'archived') return 'dismissed';
    return 'new';
  });
  let kindParam = $derived(router.route.query.get('kind') ?? '');
  let feedParam = $derived(router.route.query.get('feed') ?? '');
  let tagParam = $derived(router.route.query.get('tag') ?? '');
  let qParam = $derived(router.route.query.get('q') ?? '');
  let pageParam = $derived(Math.max(1, Number.parseInt(router.route.query.get('page') ?? '1', 10) || 1));

  let activeFeed = $derived(feeds.find((f) => f.id === feedParam) ?? null);
  let activeTag = $derived(tags.find((t) => t.id === tagParam) ?? null);
  let tagIdByLowerName = $derived.by(() => {
    const map: Record<string, string> = {};
    for (const t of tags) {
      map[t.name.toLowerCase()] = t.id;
    }
    return map;
  });

  let sortedActiveFeeds = $derived.by(() => {
    const q = sidebarFeedFilter.trim().toLowerCase();
    return [...feeds]
      .filter((f) => f.is_active)
      .filter((f) => !q || feedLabel(f).toLowerCase().includes(q))
      .sort((a, b) => b.unread_count - a.unread_count || feedLabel(a).localeCompare(feedLabel(b)));
  });

  let hasNarrowingFilters = $derived(Boolean(kindParam || tagParam || qParam));
  let shownNewIds = $derived(
    items.filter((i) => i.status === 'unread' && !i.is_finished).map((i) => i.id),
  );

  let dayGroups = $derived.by(() => {
    const groups: Array<{ label: string; items: Item[] }> = [];
    for (const item of items) {
      const label = formatDayGroupHeading(item.published_at ?? item.created_at);
      const last = groups[groups.length - 1];
      if (last && last.label === label) {
        last.items.push(item);
      } else {
        groups.push({ label, items: [item] });
      }
    }
    return groups;
  });

  function buildInboxHref(overrides: {
    status?: string | null;
    kind?: string | null;
    feed?: string | null;
    tag?: string | null;
    q?: string | null;
    page?: number | null;
  }): string {
    const nextStatus = overrides.status !== undefined ? overrides.status : statusParam;
    const nextKind = overrides.kind !== undefined ? overrides.kind : kindParam;
    const nextFeed = overrides.feed !== undefined ? overrides.feed : feedParam;
    const nextTag = overrides.tag !== undefined ? overrides.tag : tagParam;
    const nextQ = overrides.q !== undefined ? overrides.q : qParam;
    const nextPage = overrides.page !== undefined ? overrides.page : 1;
    const qs = buildQuery({
      status: nextStatus && nextStatus !== 'new' ? nextStatus : undefined,
      kind: nextKind || undefined,
      feed: nextFeed || undefined,
      tag: nextTag || undefined,
      q: nextQ || undefined,
      page: nextPage && nextPage > 1 ? nextPage : undefined,
    });
    return `/inbox${qs}`;
  }

  async function loadInbox(signal?: AbortSignal): Promise<void> {
    let localController: AbortController | null = null;
    if (!signal) {
      loadController?.abort();
      localController = new AbortController();
      loadController = localController;
    }
    const activeSignal = signal ?? localController!.signal;

    loading = true;
    errorMsg = null;
    try {
      const apiStatus =
        statusParam === 'new'
          ? 'unread'
          : statusParam === 'finished'
            ? 'finished'
            : statusParam === 'dismissed'
              ? 'dismissed'
              : 'all';

      let contentType: string | undefined;
      let hasAudio: boolean | undefined;
      let podcastOnly: boolean | undefined;
      if (kindParam === 'podcast') {
        podcastOnly = true;
      } else if (kindParam === 'audio') {
        hasAudio = true;
      } else if (kindParam) {
        contentType = kindParam;
      }

      const itemsQs = buildQuery({
        status: apiStatus,
        inbox_only: feedParam || tagParam ? undefined : true,
        feed_id: feedParam || undefined,
        tag_id: tagParam || undefined,
        content_type: contentType,
        has_audio: hasAudio,
        podcast_only: podcastOnly,
        search: qParam || undefined,
        include_content: false,
        page: pageParam,
        per_page: PER_PAGE,
      });

      const [pageData, feedsData, tagsData, syncData] = await Promise.all([
        api.get<InboxPagePayload>(`/api/v1/items/inbox${itemsQs}`, activeSignal),
        api.get<{ feeds: Feed[] }>('/api/v1/feeds', activeSignal),
        api.get<{ tags: Tag[] }>('/api/v1/tags', activeSignal),
        api.get<SyncStatus>('/api/v1/sync/status', activeSignal),
      ]);
      if (activeSignal.aborted) return;
      items = pageData.items;
      titleHtmlMap = pageData.title_html ?? {};
      hasMore = pageData.has_more;
      feeds = feedsData.feeds;
      tags = tagsData.tags;
      totalUnread = syncData.unread_items_count;
      refresh.setUnread(syncData.unread_items_count);
      selectedIdx = -1;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load inbox.';
    } finally {
      if (!activeSignal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    searchDraft = qParam;
  });

  $effect(() => {
    void statusParam;
    void kindParam;
    void feedParam;
    void tagParam;
    void qParam;
    void pageParam;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadInbox(controller.signal);
    return () => controller.abort();
  });

  $effect(() => {
    router.setTitle(activeFeed ? feedLabel(activeFeed) : 'Inbox');
  });

  onMount(() => {
    const unsub = refresh.onRefreshComplete(() => {
      void loadInbox();
    });
    const onKeyDown = (e: KeyboardEvent) => {
      if (modals.anyOpen) return;
      const target = e.target as HTMLElement | null;
      if (
        e.metaKey ||
        e.ctrlKey ||
        e.altKey ||
        target?.closest('input, textarea, select, dialog[open]')
      ) {
        return;
      }
      if (e.key === 'j') {
        e.preventDefault();
        if (items.length > 0) {
          selectedIdx = Math.min(items.length - 1, selectedIdx + 1);
          scrollRowIntoView(selectedIdx);
        }
      } else if (e.key === 'k') {
        e.preventDefault();
        if (items.length > 0) {
          selectedIdx = Math.max(0, selectedIdx - 1);
          scrollRowIntoView(selectedIdx);
        }
      } else if ((e.key === 'o' || e.key === 'Enter') && selectedIdx >= 0 && items[selectedIdx]) {
        e.preventDefault();
        const item = items[selectedIdx]!;
        router.navigate(`/reader/${encodeURIComponent(item.id)}?return_to=${encodeURIComponent(router.fullPath)}`);
      } else if (e.key === 'e' && selectedIdx >= 0 && items[selectedIdx]) {
        e.preventDefault();
        const item = items[selectedIdx]!;
        const sel = `li[data-item-id="${CSS.escape(item.id)}"] .inbox-controls`;
        const btn = document.querySelector<HTMLButtonElement>(
          `${sel} button[data-item-action="dismiss"], ${sel} button[data-item-action="restore"]`,
        );
        btn?.click();
      } else if (e.key === 'q' && selectedIdx >= 0 && items[selectedIdx]) {
        e.preventDefault();
        const item = items[selectedIdx]!;
        const btn = document.querySelector<HTMLButtonElement>(
          `li[data-item-id="${CSS.escape(item.id)}"] .inbox-controls button[data-item-action="queue"]`,
        );
        btn?.click();
      } else if (e.key === 'd' && selectedIdx >= 0 && items[selectedIdx]) {
        const item = items[selectedIdx]!;
        const discHref = item.discussions.map((d) => safeHttpHref(d.url)).find(Boolean);
        if (discHref) {
          e.preventDefault();
          window.open(discHref, '_blank', 'noopener,noreferrer');
        }
      } else if (e.key === 's' && selectedIdx >= 0 && items[selectedIdx]) {
        e.preventDefault();
        const item = items[selectedIdx]!;
        const btn = document.querySelector<HTMLButtonElement>(
          `li[data-item-id="${CSS.escape(item.id)}"] .inbox-star-btn`,
        );
        btn?.click();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      unsub();
      loadController?.abort();
      window.removeEventListener('keydown', onKeyDown);
    };
  });

  function scrollRowIntoView(idx: number): void {
    const item = items[idx];
    if (!item) return;
    document
      .querySelector(`li[data-item-id="${CSS.escape(item.id)}"]`)
      ?.scrollIntoView({ block: 'nearest' });
  }

  function handleSearchSubmit(e: SubmitEvent): void {
    e.preventDefault();
    router.navigate(buildInboxHref({ q: searchDraft.trim() || null, page: 1 }));
  }

  async function handleBatchDismiss(): Promise<void> {
    if (batchBusy) return;
    const isAllUnread = !hasNarrowingFilters && !feedParam;
    if (isAllUnread && !window.confirm(`Dismiss all ${totalUnread} unread items in Inbox?`)) {
      return;
    }
    batchBusy = true;
    try {
      const payload = hasNarrowingFilters
        ? { item_ids: shownNewIds, feed_id: null, all_unread: false }
        : feedParam
          ? { item_ids: [], feed_id: feedParam, all_unread: false }
          : { item_ids: [], feed_id: null, all_unread: true };
      const res = await api.post<{ archived_count: number }>('/api/v1/items/dismiss-batch', payload);
      toast.show(`Dismissed ${res.archived_count} item${res.archived_count === 1 ? '' : 's'}`);
      await loadInbox();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to dismiss items.', {
        isError: true,
      });
    } finally {
      batchBusy = false;
    }
  }

  function handleItemRemoved(id: string): void {
    if (statusParam === 'all') {
      void loadInbox();
      return;
    }
    const removed = items.find((i) => i.id === id);
    items = items.filter((i) => i.id !== id);
    selectedIdx = items.length === 0 ? -1 : Math.min(selectedIdx, items.length - 1);
    if (statusParam === 'new' && removed && removed.status === 'unread') {
      totalUnread = Math.max(0, totalUnread - 1);
      refresh.adjustUnread(-1);
      if (removed.feed_id) {
        feeds = feeds.map((f) =>
          f.id === removed.feed_id ? { ...f, unread_count: Math.max(0, f.unread_count - 1) } : f,
        );
      }
    }
  }
</script>

<div class="inbox-grid">
  <aside class="inbox-sidebar" aria-label="Inbox filters">
    <div class="sidebar-group">
      <div class="sidebar-heading">Views</div>
      <ul class="sidebar-list">
        <li>
          <a
            href={buildInboxHref({ status: 'new', page: 1 })}
            class:is-active={statusParam === 'new'}
            aria-current={statusParam === 'new' ? 'page' : undefined}
          >
            <span class="sidebar-label">New</span>
            <span class="sidebar-count">{totalUnread}</span>
          </a>
        </li>
        <li>
          <a
            href={buildInboxHref({ status: 'finished', page: 1 })}
            class:is-active={statusParam === 'finished'}
            aria-current={statusParam === 'finished' ? 'page' : undefined}
          >
            <span class="sidebar-label">Finished</span>
          </a>
        </li>
        <li>
          <a
            href={buildInboxHref({ status: 'dismissed', page: 1 })}
            class:is-active={statusParam === 'dismissed'}
            aria-current={statusParam === 'dismissed' ? 'page' : undefined}
          >
            <span class="sidebar-label">Dismissed</span>
          </a>
        </li>
        <li>
          <a
            href={buildInboxHref({ status: 'all', page: 1 })}
            class:is-active={statusParam === 'all'}
            aria-current={statusParam === 'all' ? 'page' : undefined}
          >
            <span class="sidebar-label">All</span>
          </a>
        </li>
      </ul>
    </div>

    <div class="sidebar-group">
      <div class="sidebar-heading">Format</div>
      <ul class="sidebar-list">
        {#each FORMAT_OPTIONS as fmt (fmt.key)}
          <li>
            <a
              href={buildInboxHref({ kind: kindParam === fmt.key ? null : fmt.key, page: 1 })}
              class:is-active={kindParam === fmt.key}
            >
              <span class="sidebar-label">{fmt.label}</span>
            </a>
          </li>
        {/each}
      </ul>
    </div>

    {#if feeds.some((f) => f.is_active)}
      <div class="sidebar-group">
        <div class="sidebar-heading">Feeds</div>
        <label for="inbox-sidebar-feed-filter" class="sr-only">Filter feeds</label>
        <input
          id="inbox-sidebar-feed-filter"
          type="search"
          class="sidebar-feed-filter"
          placeholder="Filter feeds…"
          bind:value={sidebarFeedFilter}
        />
        <ul class="sidebar-list">
          {#each sortedActiveFeeds as feed (feed.id)}
            {@const label = feedLabel(feed)}
            <li>
              <a
                href={buildInboxHref({ feed: feedParam === feed.id ? null : feed.id, page: 1 })}
                class:is-active={feedParam === feed.id}
              >
                <span class="sidebar-label sidebar-feed-label">
                  <FeedFavicon title={label} url={feed.url} size="sm" />
                  <span class="sidebar-feed-title">{label}</span>
                </span>
                {#if feed.unread_count > 0}
                  <span class="sidebar-count">{feed.unread_count}</span>
                {/if}
              </a>
            </li>
          {/each}
        </ul>
      </div>
    {/if}
  </aside>

  <section class="inbox-main" aria-label="Feed stream">
    <div class="inbox-mobile-filters">
      <div class="pill-bar mt-0 mb-sm">
        {#each [
          { key: 'new', label: `New (${totalUnread})` },
          { key: 'finished', label: 'Finished' },
          { key: 'dismissed', label: 'Dismissed' },
          { key: 'all', label: 'All' },
        ] as tab (tab.key)}
          <a
            class="pill"
            class:is-active={statusParam === tab.key}
            href={buildInboxHref({ status: tab.key, page: 1 })}
          >
            {tab.label}
          </a>
        {/each}
      </div>

      <label for="mobile-kind-select" class="sr-only">Select format</label>
      <select
        id="mobile-kind-select"
        class="w-full mb-sm"
        value={kindParam}
        onchange={(e) => {
          const val = (e.currentTarget as HTMLSelectElement).value;
          router.navigate(buildInboxHref({ kind: val || null, page: 1 }));
        }}
      >
        <option value="">All formats</option>
        {#each FORMAT_OPTIONS as fmt (fmt.key)}
          <option value={fmt.key}>{fmt.label}</option>
        {/each}
      </select>

      {#if sortedActiveFeeds.length > 0}
        <label for="mobile-feed-select" class="sr-only">Select feed</label>
        <select
          id="mobile-feed-select"
          class="w-full mb-sm"
          value={feedParam}
          onchange={(e) => {
            const val = (e.currentTarget as HTMLSelectElement).value;
            router.navigate(buildInboxHref({ feed: val || null, page: 1 }));
          }}
        >
          <option value="">All active feeds</option>
          {#each sortedActiveFeeds as feed (feed.id)}
            <option value={feed.id}>
              {feedLabel(feed)}{feed.unread_count > 0 ? ` (${feed.unread_count})` : ''}
            </option>
          {/each}
        </select>
      {/if}
    </div>

    <div class="list-detail-head">
      <div class="list-detail-title-group">
        <h2>{activeFeed ? feedLabel(activeFeed) : 'Inbox'}</h2>
        <span class="list-count-badge">{items.length}{hasMore ? '+' : ''}</span>
      </div>

      <div class="inbox-toolbar-actions">
        <form class="inbox-search-form" onsubmit={handleSearchSubmit}>
          <label for="roche-inbox-search" class="sr-only">Search inbox</label>
          <input
            id="roche-inbox-search"
            type="search"
            placeholder="Search stream…"
            bind:value={searchDraft}
          />
        </form>

        {#if statusParam === 'new' && (shownNewIds.length > 0 || (!hasNarrowingFilters && totalUnread > 0))}
          <button
            type="button"
            class="btn btn-small btn-ghost"
            disabled={batchBusy}
            onclick={handleBatchDismiss}
          >
            {#if hasNarrowingFilters}
              Dismiss Shown New ({shownNewIds.length})
            {:else if activeFeed}
              Dismiss Feed New ({activeFeed.unread_count})
            {:else}
              Dismiss All New
            {/if}
          </button>
        {/if}
      </div>
    </div>

    {#if kindParam || feedParam || tagParam || qParam}
      <div class="pill-bar active-filters" aria-label="Active filters">
        {#if activeFeed}
          <a class="pill is-active" href={buildInboxHref({ feed: null, page: 1 })}>
            Feed: {feedLabel(activeFeed)} ×
          </a>
        {/if}
        {#if kindParam}
          <a class="pill is-active" href={buildInboxHref({ kind: null, page: 1 })}>
            Format: {kindParam} ×
          </a>
        {/if}
        {#if activeTag}
          <a class="pill is-active" href={buildInboxHref({ tag: null, page: 1 })}>
            Tag: #{activeTag.name} ×
          </a>
        {/if}
        {#if qParam}
          <a class="pill is-active" href={buildInboxHref({ q: null, page: 1 })}>
            Query: “{qParam}” ×
          </a>
        {/if}
        <a class="link-btn" href={buildInboxHref({ kind: null, feed: null, tag: null, q: null, page: 1 })}>
          Clear filters
        </a>
      </div>
    {/if}

    {#if errorMsg}
      <div class="notice notice-error" role="alert">{errorMsg}</div>
    {/if}

    {#if loading && items.length === 0}
      <p class="empty">Loading inbox…</p>
    {:else if items.length === 0}
      <p class="empty">
        {statusParam === 'new'
          ? 'Inbox is clear. Enjoy the quiet.'
          : 'No items match the current filters.'}
      </p>
    {:else}
      {#each dayGroups as group, idx (`${idx}-${group.label}`)}
        <h3 class="day-heading">{group.label}</h3>
        <ul class="inbox-list">
          {#each group.items as item (item.id)}
            <ItemRow
              {item}
              titleHtml={titleHtmlMap[item.id]}
              returnTo={router.fullPath}
              selected={items[selectedIdx]?.id === item.id}
              {tagIdByLowerName}
              onTriageRemoved={handleItemRemoved}
              onTriageRestored={() => void loadInbox()}
            />
          {/each}
        </ul>
      {/each}

      {#if pageParam > 1 || hasMore}
        <nav class="pager" aria-label="Inbox pagination">
          {#if pageParam > 1}
            <a class="btn btn-small btn-ghost" href={buildInboxHref({ page: pageParam - 1 })}>← Newer</a>
          {/if}
          {#if hasMore}
            <a class="btn btn-small btn-ghost" href={buildInboxHref({ page: pageParam + 1 })}>Older →</a>
          {/if}
        </nav>
      {/if}
    {/if}
  </section>
</div>

<style>
  .inbox-grid {
    display: grid;
    grid-template-columns: 16.5rem minmax(0, 56rem);
    gap: 2.25rem;
    align-items: start;
  }

  .inbox-sidebar {
    position: sticky;
    top: 1.25rem;
    max-height: calc(100vh - 2.5rem);
    overflow-y: auto;
    padding-right: 0.75rem;
    border-right: 1px solid var(--rule-soft);
  }

  .sidebar-group + .sidebar-group {
    margin-top: 1.25rem;
  }

  .sidebar-heading {
    font-size: 0.7rem;
    font-weight: 700;
    letter-spacing: 0.11em;
    text-transform: uppercase;
    color: var(--ink-faint);
    margin-bottom: 0.35rem;
  }

  .sidebar-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .sidebar-list li a {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.45rem;
    padding: 0.28rem 0.45rem;
    border-radius: 2px;
    color: var(--ink-soft);
    text-decoration: none;
    font-size: 0.86rem;
  }

  .sidebar-list li a:hover {
    background: var(--paper-deep);
    color: var(--ink);
  }

  .sidebar-list li a.is-active {
    background: var(--paper-deep);
    color: var(--accent);
    font-weight: 600;
  }

  .sidebar-feed-label {
    display: inline-flex;
    align-items: center;
    gap: 0.4rem;
    min-width: 0;
  }

  .sidebar-feed-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .sidebar-count {
    font-family: var(--mono);
    font-size: 0.74rem;
    color: var(--ink-faint);
    flex-shrink: 0;
  }

  .sidebar-feed-filter {
    width: 100%;
    padding: 0.26rem 0.48rem;
    font-size: 0.8rem;
    margin-bottom: 0.4rem;
  }

  .inbox-mobile-filters {
    display: none;
  }

  .inbox-toolbar-actions {
    display: flex;
    align-items: center;
    gap: 0.45rem;
    flex-wrap: wrap;
  }

  .inbox-search-form {
    margin: 0;
  }

  .inbox-search-form input {
    width: 12rem;
    padding: 0.28rem 0.55rem;
    font-size: 0.84rem;
  }

  .day-heading {
    margin-top: 1.5rem;
    margin-bottom: 0.25rem;
  }

  ul.inbox-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  nav.pager {
    display: flex;
    gap: 0.5rem;
    margin-top: 1.25rem;
  }

  @media (max-width: 880px) {
    .inbox-grid {
      grid-template-columns: 1fr;
    }
    .inbox-sidebar {
      display: none;
    }
    .inbox-mobile-filters {
      display: block;
    }
  }
</style>
