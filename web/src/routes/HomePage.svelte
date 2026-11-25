<script lang="ts">
  import { onMount } from 'svelte';
  import { api, ApiError } from '../lib/api/client';
  import type { HomePayload, ItemDetail } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { refresh } from '../lib/stores/refresh.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    consumptionStatusLabel,
    formatMonthDay,
    normalizeUrlInput,
  } from '../lib/utils/format';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import DrawSleeve from '../components/DrawSleeve.svelte';
  import InProgressCatalogRow from '../components/InProgressCatalogRow.svelte';
  import ItemRow from '../components/ItemRow.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  let data = $state<HomePayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  let quickSaveUrl = $state('');
  let saveBusy = $state(false);
  let loadController: AbortController | null = null;
  let readerIds = $derived(new Set(data?.reader_item_ids ?? []));

  async function loadHome(): Promise<void> {
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    loading = true;
    errorMsg = null;
    try {
      const payload = await api.get<HomePayload>('/api/v1/home', controller.signal);
      if (controller.signal.aborted) return;
      data = payload;
      refresh.setUnread(payload.unread_total);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load dashboard.';
    } finally {
      if (!controller.signal.aborted) {
        loading = false;
      }
    }
  }

  onMount(() => {
    router.setTitle('Roche');
    void loadHome();
    const unsub = refresh.onRefreshComplete(() => void loadHome());
    return () => {
      unsub();
      loadController?.abort();
    };
  });

  async function handleQuickSave(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const url = normalizeUrlInput(quickSaveUrl);
    if (!url || saveBusy) return;
    saveBusy = true;
    try {
      const saved = await api.post<ItemDetail>('/api/v1/items/save', { url });
      quickSaveUrl = '';
      toast.show(`Saved '${saved.title}' to Queue`);
      await loadHome();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to save URL.', { isError: true });
    } finally {
      saveBusy = false;
    }
  }

  function handleInProgressTriageRemoved(id: string): void {
    if (!data) return;
    const removed = data.in_progress.find(
      (r) => (r.source === 'item' ? r.item.id : r.entry.id) === id,
    );
    data = {
      ...data,
      in_progress: data.in_progress.filter(
        (r) => (r.source === 'item' ? r.item.id : r.entry.id) !== id,
      ),
    };
    if (removed?.source === 'item' && removed.item.feed_id) {
      refresh.adjustUnread(-1);
    }
    void loadHome();
  }

  function removeUnread(id: string): void {
    if (!data) return;
    data = {
      ...data,
      unread_items: data.unread_items.filter((i) => i.id !== id),
      unread_total: Math.max(0, data.unread_total - 1),
    };
    refresh.adjustUnread(-1);
  }

  function removeWtr(id: string): void {
    if (!data) return;
    data = {
      ...data,
      want_to_read_items: data.want_to_read_items.filter((i) => i.id !== id),
      want_to_read_total: Math.max(0, data.want_to_read_total - 1),
    };
  }
</script>

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{:else if loading && !data}
  <p class="empty">Loading home…</p>
{:else if data}
  <div class="home-grid">
    <section class="home-main" aria-label="Feed Inbox">
      {#if data.in_progress.length > 0}
        <div class="section-head section-head-link">
          <h2><a class="section-head-anchor" href="/queue">Continue Reading &amp; Listening</a></h2>
          <span class="section-action">Queue →</span>
        </div>
        <ul class="inbox-list mb-lg">
          {#each data.in_progress as row (row.source === 'item' ? row.item.id : row.entry.id)}
            {#if row.source === 'item'}
              <ItemRow
                item={row.item}
                titleHtml={data.title_html[row.item.id]}
                returnTo="/"
                showProgress
                showExcerpt={false}
                onTriageRemoved={handleInProgressTriageRemoved}
                onTriageRestored={() => void loadHome()}
                onQueueChanged={() => void loadHome()}
              />
            {:else}
              <InProgressCatalogRow
                entry={row.entry}
                ts={row.ts}
                notes={row.notes}
                titleHtml={data.title_html[row.entry.id]}
                artistLinks={data.artist_links}
                isReader={readerIds.has(row.entry.id)}
                returnTo="/"
                onResolved={loadHome}
              />
            {/if}
          {/each}
        </ul>
      {/if}

      <div class="section-head section-head-link">
        <h2>
          <a class="section-head-anchor" href="/inbox">
            Feed Inbox
            {#if data.unread_total > 0}
              <span class="js-unread-count">({data.unread_total})</span>
            {/if}
          </a>
        </h2>
        <span class="section-action">Open Inbox →</span>
      </div>

      {#if data.unread_items.length === 0}
        {#if data.unread_total > 0 && data.in_progress.length > 0}
          <p class="empty">
            All unread items are currently in progress above. <a href="/inbox">Open Inbox</a>.
          </p>
        {:else if data.unread_total > 0}
          <p class="empty">
            More unread items are waiting in your <a href="/inbox">Inbox</a>.
          </p>
        {:else}
          <p class="empty">
            All caught up! <a href="/feeds">Manage feeds</a> or browse <a href="/boards">Boards</a>.
          </p>
        {/if}
      {:else}
        <ul class="inbox-list">
          {#each data.unread_items as item (item.id)}
            <ItemRow
              {item}
              titleHtml={data.title_html[item.id]}
              returnTo="/"
              showExcerpt={false}
              onTriageRemoved={removeUnread}
              onTriageRestored={() => void loadHome()}
            />
          {/each}
        </ul>
      {/if}
    </section>

    <div class="home-rail">
      {#each data.active_generators as gen (gen.collection_id)}
        <DrawSleeve
          generator={gen}
          externalLinks={gen.current_entry ? (data.generator_external_links[gen.current_entry.id] ?? []) : []}
          tags={gen.current_entry ? (data.generator_tags[gen.current_entry.id] ?? []) : []}
          artistLinks={data.artist_links}
          titleHtml={data.title_html}
          onUpdated={loadHome}
        />
      {/each}

      <section class="rail-block rail-card" aria-label="Reading Queue">
        <div class="section-head section-head-link mt-0">
          <h3>
            <a class="section-head-anchor" href="/queue">
              Queue ({data.want_to_read_total}{data.other_want_total > 0 ? ` + ${data.other_want_total}` : ''})
            </a>
          </h3>
          <span class="section-action">All →</span>
        </div>

        <form class="quick-save-form" onsubmit={handleQuickSave}>
          <label for="home-quick-save-url" class="sr-only">Save URL to reading queue</label>
          <input
            id="home-quick-save-url"
            type="text"
            placeholder="Add URL to queue…"
            bind:value={quickSaveUrl}
          />
          <button type="submit" class="btn btn-small btn-primary" disabled={saveBusy || !quickSaveUrl.trim()}>
            Save
          </button>
        </form>

        {#if data.want_to_read_items.length === 0}
          {#if data.want_to_read_total > 0 && data.in_progress.length > 0}
            <p class="empty">All saved articles are currently in progress above.</p>
          {:else if data.want_to_read_total > 0}
            <p class="empty">
              More saved articles are in your <a href="/queue">Queue →</a>
            </p>
          {:else if data.other_want_total > 0}
            <p class="empty">
              No saved articles. <a href="/queue">View {data.other_want_total} in other Want queues →</a>
            </p>
          {:else}
            <p class="empty">No saved articles in your reading queue.</p>
          {/if}
        {:else}
          <ul class="inbox-list">
            {#each data.want_to_read_items as item (item.id)}
              <ItemRow
                {item}
                titleHtml={data.title_html[item.id]}
                returnTo="/"
                compact
                primaryMode="done"
                onTriageRemoved={removeWtr}
                onTriageRestored={() => void loadHome()}
              />
            {/each}
          </ul>
        {/if}
      </section>

      <section class="rail-block rail-card" aria-label="Recent Diary">
        <div class="section-head section-head-link mt-0">
          <h3><a class="section-head-anchor" href="/diary">Recent Diary</a></h3>
          <span class="section-action">All →</span>
        </div>

        {#if data.recent_diary.length === 0}
          <p class="empty">No diary logs yet.</p>
        {:else}
          <ul class="home-diary-list">
            {#each data.recent_diary as log (log.id)}
              {@const tier = data.diary_tiers[log.entry.id]}
              <li class="home-diary-row">
                <span class="home-diary-date">{formatMonthDay(log.ts)}</span>
                <div class="home-diary-main">
                  <a class="home-diary-title" href={`/entries/${encodeURIComponent(log.entry.id)}`}>
                    <MathTitle html={data.title_html[log.entry.id]} text={log.entry.title} />
                  </a>
                  {#if log.entry.creators.length > 0}
                    <div class="home-diary-sub">
                      <CreatorLinks creators={log.entry.creators} artistLinks={data.artist_links} />
                    </div>
                  {/if}
                </div>
                <div class="home-diary-badge">
                  {#if log.status !== 'consumed'}
                    <span class="badge">{consumptionStatusLabel(log.status)}</span>
                  {:else if tier}
                    <span class="tier-pill tier-{tier}">{tier}</span>
                  {/if}
                </div>
              </li>
            {/each}
          </ul>
        {/if}
      </section>
    </div>
  </div>
{/if}

<style>
  .home-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(21rem, 25rem);
    justify-content: space-between;
    gap: 2.5rem;
    align-items: start;
  }

  ul.inbox-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .quick-save-form {
    display: flex;
    gap: 0.4rem;
    margin: 0.65rem 0 0.5rem;
  }

  .quick-save-form input {
    flex: 1;
    min-width: 0;
    padding: 0.3rem 0.5rem;
    font-size: 0.84rem;
  }

  .home-diary-list {
    list-style: none;
    padding: 0;
    margin: 0.35rem 0 0;
  }

  .home-diary-row {
    display: grid;
    grid-template-columns: 3.6rem minmax(0, 1fr) auto;
    gap: 0.55rem;
    align-items: baseline;
    padding: 0.42rem 0;
    border-bottom: 1px solid var(--rule-soft);
    font-size: 0.86rem;
  }

  .home-diary-date {
    font-family: var(--mono);
    font-size: 0.74rem;
    color: var(--ink-faint);
    white-space: nowrap;
  }

  .home-diary-title {
    display: inline-block;
    color: var(--ink);
    text-decoration: none;
    font-family: var(--display);
    font-size: 0.94rem;
    line-height: 1.35;
    padding: 0.1rem 0;
  }

  .home-diary-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .home-diary-sub {
    font-size: 0.76rem;
    color: var(--ink-faint);
    margin-top: 0.25rem;
    line-height: 1.5;
  }

  .home-diary-sub :global(a) {
    display: inline-block;
    padding: 0.15rem 0;
  }

  @media (max-width: 880px) {
    .home-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
