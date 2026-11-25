<script lang="ts">
  import { onDestroy } from 'svelte';
  import { api, ApiError, buildQuery } from '../lib/api/client';
  import type { CatalogEntry, ListDetailPayload } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { ALL_SEARCHABLE_KINDS, entryKindLabel, parseCsvList } from '../lib/utils/format';
  import DrawSleeve from '../components/DrawSleeve.svelte';
  import Pagination from '../components/Pagination.svelte';
  import RankedTable from '../components/RankedTable.svelte';

  interface Props {
    slug: string;
  }

  let { slug }: Props = $props();

  const TIERS = ['S', 'A', 'B', 'C', 'D'] as const;

  let data = $state<ListDetailPayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  let localFilter = $state('');
  let addTitle = $state('');
  let addKind = $state('');
  let addCreators = $state('');
  let addYear = $state('');
  let addBusy = $state(false);
  let genBusy = $state(false);

  let pageParam = $derived(Math.max(1, Number.parseInt(router.route.query.get('page') ?? '1', 10) || 1));
  let tierParam = $derived((router.route.query.get('tier') ?? '').toUpperCase());
  let kindParam = $derived(router.route.query.get('kind') ?? '');
  let qParam = $derived(router.route.query.get('q') ?? '');

  let isManualList = $derived(
    Boolean(data && !data.is_dynamic && data.view.collection.role === 'list'),
  );
  let isEditableManual = $derived(isManualList && !data?.view.collection.has_generator);
  let rankedMode = $derived((data?.view.comparison_count ?? 0) > 0);
  let isFiltered = $derived(Boolean(tierParam || kindParam || qParam));
  let readerIds = $derived(new Set(data?.reader_item_ids ?? []));
  let loadController: AbortController | null = null;
  let destroyed = false;

  onDestroy(() => {
    destroyed = true;
    loadController?.abort();
  });

  let displayedRows = $derived.by(() => {
    if (!data) return [];
    const q = localFilter.trim().toLowerCase();
    if (!q || q === qParam.trim().toLowerCase()) return data.view.rows;
    return data.view.rows.filter((r) =>
      `${r.entry.title} ${r.entry.creators.join(' ')} ${r.entry.release_year ?? ''}`
        .toLowerCase()
        .includes(q),
    );
  });

  function buildListHref(overrides: {
    page?: number | null;
    tier?: string | null;
    kind?: string | null;
    q?: string | null;
  }): string {
    const canonSlug = data?.view.collection.slug ?? slug;
    const nextPage = overrides.page !== undefined ? overrides.page : pageParam;
    const nextTier = overrides.tier !== undefined ? overrides.tier : tierParam;
    const nextKind = overrides.kind !== undefined ? overrides.kind : kindParam;
    const nextQ = overrides.q !== undefined ? overrides.q : qParam;
    const qs = buildQuery({
      page: nextPage && nextPage > 1 ? nextPage : undefined,
      tier: nextTier || undefined,
      kind: nextKind || undefined,
      q: nextQ || undefined,
    });
    return `/lists/${encodeURIComponent(canonSlug)}${qs}`;
  }

  async function loadDetail(signal?: AbortSignal): Promise<void> {
    if (destroyed) return;
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
      const qs = buildQuery({
        page: pageParam,
        tier: tierParam || undefined,
        kind: kindParam || undefined,
        q: qParam || undefined,
      });
      const payload = await api.get<ListDetailPayload>(
        `/api/v1/catalog/lists/${encodeURIComponent(slug)}/detail${qs}`,
        activeSignal,
      );
      if (destroyed || activeSignal.aborted) return;
      data = payload;
      router.setTitle(payload.view.collection.title);
    } catch (err) {
      if (destroyed || (err instanceof DOMException && err.name === 'AbortError')) return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load list.';
    } finally {
      if (!destroyed && !activeSignal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    localFilter = qParam;
  });

  $effect(() => {
    void slug;
    void pageParam;
    void tierParam;
    void kindParam;
    void qParam;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadDetail(controller.signal);
    return () => controller.abort();
  });

  async function handleAddWork(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const title = addTitle.trim();
    if (!title || addBusy) return;
    addBusy = true;
    try {
      const yr = addYear.trim() ? Number.parseInt(addYear.trim(), 10) : null;
      const res = await api.post<{ entry: CatalogEntry; linked_existing: boolean }>(
        `/api/v1/catalog/lists/${encodeURIComponent(slug)}/entries/resolve`,
        {
          title_or_id: title,
          kind: addKind || null,
          creators: parseCsvList(addCreators),
          release_year: yr && Number.isFinite(yr) ? yr : null,
        },
      );
      addTitle = '';
      addCreators = '';
      addYear = '';
      toast.show(
        res.linked_existing
          ? `Linked '${res.entry.title}' to list`
          : `Added '${res.entry.title}' to list`,
      );
      await loadDetail();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to add work.', { isError: true });
    } finally {
      addBusy = false;
    }
  }

  async function handleRemoveEntry(entryId: string, title: string): Promise<void> {
    try {
      await api.delete(
        `/api/v1/catalog/lists/${encodeURIComponent(slug)}/entries/${encodeURIComponent(entryId)}`,
      );
      toast.show(`Removed '${title}' from list`, {
        onUndo: async () => {
          await api.post(`/api/v1/catalog/lists/${encodeURIComponent(slug)}/entries`, {
            entry_id: entryId,
          });
          await loadDetail();
        },
      });
      await loadDetail();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to remove entry.', {
        isError: true,
      });
    }
  }

  async function handleToggleGenerator(enabled: boolean): Promise<void> {
    if (genBusy) return;
    genBusy = true;
    try {
      await api.patch(`/api/v1/catalog/lists/${encodeURIComponent(slug)}/generator`, { enabled });
      toast.show(enabled ? 'Enabled collection draw' : 'Paused collection draw');
      await loadDetail();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update draw.', {
        isError: true,
      });
    } finally {
      genBusy = false;
    }
  }

  function handleSearchSubmit(e: SubmitEvent): void {
    e.preventDefault();
    router.navigate(buildListHref({ q: localFilter.trim() || null, page: 1 }));
  }
</script>

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{:else if loading && !data}
  <p class="empty">Loading list…</p>
{:else if data}
  {@const col = data.view.collection}
  {@const canonicalSlug = col.slug ?? col.id}
  <div class="lists-grid">
    <section class="lists-main" aria-label="List entries">
      <div class="list-detail-crumb">
        <a class="reader-back-link" href="/lists">← All Lists</a>
      </div>

      <div class="list-detail-head">
        <div class="list-detail-title-group">
          <h2>{col.title}</h2>
          <span class="list-count-badge">
            {isFiltered ? `${data.total_matching} of ${data.full_total}` : data.full_total}
          </span>
        </div>

        {#if data.full_total >= 2}
          <a
            class="btn btn-primary btn-small"
            href={`/rank/${encodeURIComponent(canonicalSlug)}${col.role === 'want' ? '?want=true' : ''}`}
          >
            Rank
          </a>
        {/if}
      </div>

      <p class="lede">
        {#if col.summary}{col.summary}{/if}
        {#if col.summary && data.view.comparison_count > 0} · {/if}
        {#if data.view.comparison_count > 0}
          {data.view.comparison_count} comparison{data.view.comparison_count === 1 ? '' : 's'}
        {/if}
      </p>

      <div class="list-toolbar">
        {#if rankedMode}
          <div class="pill-bar mt-0 mb-0" role="group" aria-label="Filter by tier">
            <a
              class="pill"
              class:is-active={!tierParam}
              href={buildListHref({ tier: null, page: 1 })}
            >
              All
            </a>
            {#each TIERS as t, idx (t)}
              {#if (data.full_tier_counts[idx] ?? 0) > 0}
                <a
                  class="pill tier-{t}"
                  class:is-active={tierParam === t}
                  href={buildListHref({ tier: tierParam === t ? null : t, page: 1 })}
                >
                  {t} ({data.full_tier_counts[idx]})
                </a>
              {/if}
            {/each}
          </div>
        {/if}

        <form class="list-search-form" onsubmit={handleSearchSubmit}>
          <label for="roche-list-search" class="sr-only">Filter works in list</label>
          <input
            id="roche-list-search"
            type="search"
            placeholder="Filter list…"
            bind:value={localFilter}
          />
        </form>
      </div>

      {#if data.kind_counts.length > 1}
        <div class="pill-bar" aria-label="Filter by media type">
          <a
            class="pill"
            class:is-active={!kindParam}
            href={buildListHref({ kind: null, page: 1 })}
          >
            All types
          </a>
          {#each data.kind_counts as [kind, count] (kind)}
            <a
              class="pill"
              class:is-active={kindParam === kind}
              href={buildListHref({ kind: kindParam === kind ? null : kind, page: 1 })}
            >
              {entryKindLabel(kind)} ({count})
            </a>
          {/each}
        </div>
      {/if}

      <Pagination
        page={data.page_num}
        totalPages={data.total_pages}
        ariaLabel="Pagination (top)"
        hrefForPage={(p) => buildListHref({ page: p })}
      />

      {#if displayedRows.length === 0}
        <p class="empty">
          {data.full_total === 0
            ? 'No works in this list yet.'
            : 'No entries match the current filter.'}
        </p>
      {:else}
        <RankedTable
          rows={displayedRows}
          {rankedMode}
          artistLinks={data.artist_links}
          readerItemIds={readerIds}
          titleHtml={data.title_html}
          showRemove={isEditableManual}
          onRemove={handleRemoveEntry}
        />
      {/if}

      <Pagination
        page={data.page_num}
        totalPages={data.total_pages}
        ariaLabel="Pagination (bottom)"
        hrefForPage={(p) => buildListHref({ page: p })}
      />
    </section>

    <aside class="lists-rail" aria-label="List summary and actions">
      {#if data.generator?.enabled}
        <DrawSleeve
          generator={data.generator}
          externalLinks={data.generator_external_links}
          tags={data.generator_tags}
          artistLinks={data.artist_links}
          titleHtml={data.title_html}
          onUpdated={loadDetail}
        />
      {/if}

      {#if rankedMode}
        <section class="rail-block rail-card">
          <h3 class="mt-0">Tier Distribution</h3>
          <div class="tier-bars">
            {#each TIERS as t, idx (t)}
              {@const cnt = data.full_tier_counts[idx] ?? 0}
              <a
                class="tier-bar-row"
                href={buildListHref({ tier: tierParam === t ? null : t, page: 1 })}
              >
                <span class="tier-pill tier-{t}">{t}</span>
                <progress value={cnt} max={Math.max(1, data.full_total)}></progress>
                <span class="tier-bar-cnt">{cnt}</span>
              </a>
            {/each}
          </div>
        </section>
      {:else if !data.generator}
        <section class="rail-block rail-card">
          <h3 class="mt-0">Ranking</h3>
          {#if data.full_total >= 2}
            <p class="meta mb-sm">Compare works side-by-side to establish S–D tiers.</p>
            <a
              class="btn btn-primary btn-small"
              href={`/rank/${encodeURIComponent(canonicalSlug)}${col.role === 'want' ? '?want=true' : ''}`}
            >
              Start Ranking
            </a>
          {:else}
            <p class="meta mb-0">Add at least 2 entries to rank.</p>
          {/if}
        </section>
      {/if}

      {#if data.generator}
        <section class="rail-block rail-card">
          <h3 class="mt-0">Collection Draw</h3>
          <p class="meta mb-sm">
            {data.generator.enabled
              ? 'Random draw workflow is active on Home.'
              : 'Random draw workflow is paused.'}
          </p>
          <button
            type="button"
            class="btn btn-small btn-ghost"
            disabled={genBusy}
            onclick={() => void handleToggleGenerator(!data!.generator!.enabled)}
          >
            {data.generator.enabled ? 'Pause Draw' : 'Enable Draw'}
          </button>
        </section>
      {/if}

      {#if isEditableManual}
        <section class="rail-block rail-card">
          <details
            class="editorial-panel rail-editorial-panel"
            open={data.full_total === 0}
            data-default-open={data.full_total === 0 ? '' : undefined}
          >
            <summary>+ Add Work to List</summary>
            <form class="rail-form" data-list-form="add" onsubmit={handleAddWork}>
              <div class="field">
                <label for="add-title">Title or Existing Entry ID</label>
                <input
                  id="add-title"
                  name="title_or_id"
                  type="text"
                  required
                  placeholder="e.g. Stalker"
                  bind:value={addTitle}
                />
              </div>
              <div class="field">
                <label for="add-kind">Kind (Auto matches existing)</label>
                <select id="add-kind" name="kind" bind:value={addKind}>
                  <option value="">Auto</option>
                  {#each ALL_SEARCHABLE_KINDS as k (k.value)}
                    <option value={k.value}>{k.label}</option>
                  {/each}
                </select>
              </div>
              <div class="field">
                <label for="add-creators">Creators (comma-separated)</label>
                <input
                  id="add-creators"
                  name="creators"
                  type="text"
                  placeholder="Andrei Tarkovsky"
                  bind:value={addCreators}
                />
              </div>
              <div class="field">
                <label for="add-year">Year</label>
                <input
                  id="add-year"
                  name="release_year"
                  type="number"
                  min="1000"
                  max="2100"
                  placeholder="1979"
                  bind:value={addYear}
                />
              </div>
              <button type="submit" class="btn btn-primary btn-small" disabled={addBusy}>
                {addBusy ? 'Adding…' : 'Add to List'}
              </button>
            </form>
          </details>
        </section>
      {/if}
    </aside>
  </div>
{/if}

<style>
  .lists-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(20rem, 24.5rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .list-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.65rem;
    margin-bottom: 0.85rem;
  }

  .list-search-form {
    margin: 0;
  }

  .list-search-form input {
    width: 12.5rem;
    padding: 0.28rem 0.55rem;
    font-size: 0.84rem;
  }

  .tier-bars {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
  }

  .tier-bar-row {
    display: grid;
    grid-template-columns: 1.7rem minmax(0, 1fr) 2rem;
    align-items: center;
    gap: 0.55rem;
    text-decoration: none;
    color: var(--ink-soft);
    font-size: 0.82rem;
  }

  .tier-bar-row progress {
    width: 100%;
    height: 6px;
    accent-color: var(--accent);
  }

  .tier-bar-cnt {
    font-family: var(--mono);
    text-align: right;
    color: var(--ink-faint);
  }

  @media (max-width: 880px) {
    .lists-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
