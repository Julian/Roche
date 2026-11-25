<script lang="ts">
  import { onDestroy } from 'svelte';
  import { api, ApiError, buildQuery } from '../lib/api/client';
  import type {
    ConsumptionLogView,
    ConsumptionStatus,
    DiaryPagePayload,
    EntryKind,
    QuickLogResponse,
  } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    LOGGABLE_KINDS,
    consumptionStatusLabel,
    entryKindLabel,
    formatIsoDateInput,
    formatMonthDay,
    formatMonthYear,
    parseCsvList,
  } from '../lib/utils/format';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import MathTitle from '../components/MathTitle.svelte';
  import Pagination from '../components/Pagination.svelte';

  let data = $state<DiaryPagePayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);
  let localFilter = $state('');
  let busy = $state(false);
  let loadController: AbortController | null = null;
  let destroyed = false;

  onDestroy(() => {
    destroyed = true;
    loadController?.abort();
  });

  // Per-row edit drafts
  let drafts = $state<Record<string, { status: ConsumptionStatus; date: string; notes: string }>>({});

  // Quick log rail form
  let qlTitle = $state('');
  let qlKind = $state<EntryKind>('album');
  let qlCreators = $state('');
  let qlYear = $state('');
  let qlNotes = $state('');
  let qlRankAfter = $state(true);

  let yearParam = $derived(router.route.query.get('year') ?? '');
  let statusParam = $derived.by(() => {
    const raw = (router.route.query.get('status') ?? '').trim().toLowerCase();
    if (raw === 'all' || raw === 'started' || raw === 'abandoned') return raw;
    if (raw === 'skipped') return 'abandoned';
    return 'consumed';
  });
  let kindParam = $derived(router.route.query.get('kind') ?? '');
  let pageParam = $derived(Math.max(1, Number.parseInt(router.route.query.get('page') ?? '1', 10) || 1));

  let monthGroups = $derived.by(() => {
    if (!data) return [];
    const q = localFilter.trim().toLowerCase();
    const visible = q
      ? data.entries.filter((e) =>
          `${e.entry.title} ${e.entry.creators.join(' ')} ${e.notes ?? ''}`.toLowerCase().includes(q),
        )
      : data.entries;
    const groups: Array<{ label: string; entries: ConsumptionLogView[] }> = [];
    for (const log of visible) {
      const label = formatMonthYear(log.ts);
      const last = groups[groups.length - 1];
      if (last && last.label === label) {
        last.entries.push(log);
      } else {
        groups.push({ label, entries: [log] });
      }
    }
    return groups;
  });

  let maxCadence = $derived(
    Math.max(1, ...(data?.cadence_months ?? []).map(([, cnt]) => cnt)),
  );

  function buildDiaryHref(overrides: {
    year?: string | null;
    status?: string | null;
    kind?: string | null;
    page?: number | null;
  }): string {
    const nextYear = overrides.year !== undefined ? overrides.year : yearParam;
    const nextStatus = overrides.status !== undefined ? overrides.status : statusParam;
    const nextKind = overrides.kind !== undefined ? overrides.kind : kindParam;
    const nextPage = overrides.page !== undefined ? overrides.page : 1;
    const qs = buildQuery({
      year: nextYear || undefined,
      status: nextStatus && nextStatus !== 'consumed' ? nextStatus : undefined,
      kind: nextKind || undefined,
      page: nextPage && nextPage > 1 ? nextPage : undefined,
    });
    return `/diary${qs}`;
  }

  async function loadDiary(signal?: AbortSignal): Promise<void> {
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
        year: yearParam || undefined,
        status: statusParam || undefined,
        kind: kindParam || undefined,
        page: pageParam,
      });
      const payload = await api.get<DiaryPagePayload>(
        `/api/v1/catalog/diary/page${qs}`,
        activeSignal,
      );
      if (destroyed || activeSignal.aborted) return;
      data = payload;
      const nextDrafts: typeof drafts = {};
      for (const e of payload.entries) {
        nextDrafts[e.id] = {
          status: e.status,
          date: formatIsoDateInput(e.ts),
          notes: e.notes ?? '',
        };
      }
      drafts = nextDrafts;
      router.setTitle('Diary');
    } catch (err) {
      if (destroyed || (err instanceof DOMException && err.name === 'AbortError')) return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load diary.';
    } finally {
      if (!destroyed && !activeSignal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    void yearParam;
    void statusParam;
    void kindParam;
    void pageParam;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadDiary(controller.signal);
    return () => controller.abort();
  });

  async function handleSaveLog(e: SubmitEvent, logId: string): Promise<void> {
    e.preventDefault();
    const d = drafts[logId];
    if (!d || busy) return;
    const orig = data?.entries.find((item) => item.id === logId);
    const dateChanged = Boolean(d.date && d.date !== formatIsoDateInput(orig?.ts));
    busy = true;
    try {
      await api.patch(`/api/v1/catalog/diary/${encodeURIComponent(logId)}`, {
        status: d.status,
        ts: dateChanged ? `${d.date}T12:00:00Z` : undefined,
        notes: d.notes.trim() || null,
      });
      toast.show('Updated diary entry');
      await loadDiary();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update entry.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleDeleteLog(logId: string, title: string): Promise<void> {
    if (!window.confirm(`Delete diary log for '${title}'?`)) return;
    busy = true;
    try {
      await api.delete(`/api/v1/catalog/diary/${encodeURIComponent(logId)}`);
      toast.show('Deleted diary entry');
      await loadDiary();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to delete entry.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleQuickLog(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const title = qlTitle.trim();
    if (!title || busy) return;
    busy = true;
    try {
      const yr = qlYear.trim() ? Number.parseInt(qlYear.trim(), 10) : null;
      const res = await api.post<QuickLogResponse>('/api/v1/catalog/quick-log', {
        title,
        kind: qlKind,
        action: 'consumed',
        creators: parseCsvList(qlCreators),
        release_year: yr && Number.isFinite(yr) ? yr : null,
        notes: qlNotes.trim() || null,
        rank_after: qlRankAfter,
      });
      qlTitle = '';
      qlCreators = '';
      qlYear = '';
      qlNotes = '';
      toast.show(`Logged '${res.entry.title}' to Diary`);
      if (res.rank_slug) {
        router.navigate(
          `/rank/${encodeURIComponent(res.rank_slug)}?focus=${encodeURIComponent(res.entry.id)}`,
        );
      } else {
        await loadDiary();
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to log work.', { isError: true });
    } finally {
      busy = false;
    }
  }
</script>

<div class="diary-grid">
  <section class="diary-main" aria-label="Diary log">
    <div class="list-detail-head">
      <div class="list-detail-title-group">
        <h2>Diary</h2>
        {#if data}
          <span class="list-count-badge">{data.total_for_filter}</span>
        {/if}
      </div>
    </div>

    <div class="diary-toolbar">
      <div class="pill-bar diary-toolbar-pills mt-0 mb-0" role="group" aria-label="Filter by status">
        {#each [
          { key: 'consumed', label: 'Finished' },
          { key: 'started', label: 'Started' },
          { key: 'abandoned', label: 'Skipped' },
          { key: 'all', label: 'All' },
        ] as st (st.key)}
          <a
            class="pill"
            class:is-active={statusParam === st.key}
            href={buildDiaryHref({ status: st.key, page: 1 })}
          >
            {st.label}
          </a>
        {/each}
      </div>

      <div class="diary-search-wrap">
        <label for="roche-diary-search" class="sr-only">Filter diary entries</label>
        <input
          id="roche-diary-search"
          type="search"
          placeholder="Filter page…"
          bind:value={localFilter}
        />
      </div>
    </div>

    {#if data && (data.non_song_kind_counts.length > 0 || data.has_songs)}
      <div class="pill-bar" aria-label="Filter by media type">
        <a
          class="pill"
          class:is-active={!kindParam}
          href={buildDiaryHref({ kind: null, page: 1 })}
        >
          Diary{data.has_songs ? ' (excl. songs)' : ''}
        </a>
        {#each data.non_song_kind_counts as [kind, count] (kind)}
          <a
            class="pill"
            class:is-active={kindParam === kind}
            href={buildDiaryHref({ kind: kindParam === kind ? null : kind, page: 1 })}
          >
            {entryKindLabel(kind)} ({count})
          </a>
        {/each}
        {#if data.has_songs}
          <a
            class="pill"
            class:is-active={kindParam === 'song'}
            href={buildDiaryHref({ kind: 'song', page: 1 })}
          >
            Songs
          </a>
          <a
            class="pill"
            class:is-active={kindParam === 'all'}
            href={buildDiaryHref({ kind: 'all', page: 1 })}
          >
            All (incl. songs)
          </a>
        {/if}
      </div>
    {/if}

    {#if errorMsg}
      <div class="notice notice-error" role="alert">{errorMsg}</div>
    {:else if loading && !data}
      <p class="empty">Loading diary…</p>
    {:else if data}
      <Pagination
        page={data.page_num}
        totalPages={data.total_pages}
        ariaLabel="Diary pagination (top)"
        hrefForPage={(p) => buildDiaryHref({ page: p })}
      />

      {#if monthGroups.length === 0}
        <p class="empty">No diary logs match the current filters.</p>
      {:else}
        {#each monthGroups as group, idx (`${idx}-${group.label}`)}
          <h3>{group.label}</h3>
          <ul class="diary-list">
            {#each group.entries as log (log.id)}
              {@const tier = data.diary_tiers[log.entry.id]}
              {@const draft = drafts[log.id]}
              <li class="diary-entry-row">
                <span class="diary-date">{formatMonthDay(log.ts)}</span>

                {#if log.entry.thumbnail_url}
                  <img
                    class="diary-thumb"
                    src={log.entry.thumbnail_url}
                    alt=""
                    loading="lazy"
                    referrerpolicy="no-referrer"
                  />
                {/if}

                <div class="diary-body">
                  <div class="diary-title-line">
                    <a class="diary-title" href={`/entries/${encodeURIComponent(log.entry.id)}`}>
                      <MathTitle html={data.title_html[log.entry.id]} text={log.entry.title} />
                    </a>
                    {#if log.status !== 'consumed'}
                      <span class="badge">{consumptionStatusLabel(log.status)}</span>
                    {:else if tier}
                      <span class="tier-pill tier-{tier}">{tier}</span>
                    {/if}
                  </div>
                  <div class="diary-meta">
                    {#if log.entry.creators.length > 0}
                      <CreatorLinks creators={log.entry.creators} artistLinks={data.artist_links} />
                      ·
                    {/if}
                    <span>{entryKindLabel(log.entry.kind)}</span>
                  </div>
                  {#if log.notes}
                    <p class="diary-notes">{log.notes}</p>
                  {/if}
                  {#if draft}
                    <details class="diary-edit-details">
                      <summary>Edit</summary>
                      <form class="rail-form mt-sm" onsubmit={(e) => void handleSaveLog(e, log.id)}>
                        <div class="form-grid">
                          <div class="field">
                            <label for={`de-st-${log.id}`}>Status</label>
                            <select id={`de-st-${log.id}`} bind:value={draft.status}>
                              <option value="consumed">Finished</option>
                              <option value="started">Started</option>
                              <option value="abandoned">Skipped</option>
                            </select>
                          </div>
                          <div class="field">
                            <label for={`de-dt-${log.id}`}>Date</label>
                            <input id={`de-dt-${log.id}`} type="date" bind:value={draft.date} />
                          </div>
                        </div>
                        <div class="field">
                          <label for={`de-nt-${log.id}`}>Notes</label>
                          <textarea id={`de-nt-${log.id}`} class="textarea-compact" bind:value={draft.notes}></textarea>
                        </div>
                        <button type="submit" class="btn btn-primary btn-small" disabled={busy}>
                          Save
                        </button>
                      </form>
                    </details>
                  {/if}
                </div>

                <button
                  type="button"
                  class="btn btn-small btn-ghost btn-danger-quiet"
                  aria-label={`Delete diary log for ${log.entry.title}`}
                  onclick={() => void handleDeleteLog(log.id, log.entry.title)}
                >
                  ×
                </button>
              </li>
            {/each}
          </ul>
        {/each}
      {/if}

      <Pagination
        page={data.page_num}
        totalPages={data.total_pages}
        ariaLabel="Diary pagination (bottom)"
        hrefForPage={(p) => buildDiaryHref({ page: p })}
      />
    {/if}
  </section>

  <aside class="diary-rail" aria-label="Diary stats and quick log">
    {#if data && data.cadence_year && data.cadence_months.length > 0}
      <section class="rail-block rail-card">
        <h3 class="mt-0">{data.cadence_year} Pace</h3>
        <div class="pace-bars">
          {#each data.cadence_months as [monthLabel, cnt] (monthLabel)}
            <div class="pace-row">
              <span class="pace-month">{monthLabel}</span>
              <progress value={cnt} max={maxCadence}></progress>
              <span class="pace-cnt">{cnt}</span>
            </div>
          {/each}
        </div>
      </section>
    {/if}

    {#if data && data.year_counts.length > 0}
      <section class="rail-block rail-card">
        <h3 class="mt-0">By Year</h3>
        <ul class="year-list">
          <li>
            <a href={buildDiaryHref({ year: null, page: 1 })} class:is-active={!yearParam}>
              <span>All years</span>
              <span class="text-faint">{data.total_all_years}</span>
            </a>
          </li>
          {#each data.year_counts as [yr, cnt] (yr)}
            <li>
              <a
                href={buildDiaryHref({ year: String(yr), page: 1 })}
                class:is-active={yearParam === String(yr)}
              >
                <span>{yr}</span>
                <span class="text-faint">{cnt}</span>
              </a>
            </li>
          {/each}
        </ul>
      </section>
    {/if}

    <section class="rail-block rail-card">
      <h3 class="mt-0">Quick Log</h3>
      <form class="rail-form" onsubmit={handleQuickLog}>
        <div class="field">
          <label for="diary-ql-title">Title</label>
          <input id="diary-ql-title" type="text" required placeholder="e.g. Kind of Blue" bind:value={qlTitle} />
        </div>
        <div class="field">
          <label for="diary-ql-kind">Kind</label>
          <select id="diary-ql-kind" bind:value={qlKind}>
            {#each LOGGABLE_KINDS as k (k.value)}
              <option value={k.value}>{k.label}</option>
            {/each}
          </select>
        </div>
        <div class="field">
          <label for="diary-ql-creators">Creators</label>
          <input id="diary-ql-creators" type="text" placeholder="Miles Davis" bind:value={qlCreators} />
        </div>
        <div class="field">
          <label for="diary-ql-year">Year</label>
          <input id="diary-ql-year" type="number" min="1000" max="2100" placeholder="1959" bind:value={qlYear} />
        </div>
        <div class="field">
          <label for="diary-ql-notes">Notes</label>
          <textarea id="diary-ql-notes" class="textarea-compact" bind:value={qlNotes}></textarea>
        </div>
        <div class="field field-check">
          <input id="diary-ql-rank" type="checkbox" bind:checked={qlRankAfter} />
          <label for="diary-ql-rank">Rank immediately</label>
        </div>
        <button type="submit" class="btn btn-primary btn-small" disabled={busy}>Log Finished</button>
      </form>
    </section>
  </aside>
</div>

<style>
  .diary-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(20rem, 24.5rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .diary-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.65rem;
    margin-bottom: 0.85rem;
  }

  .diary-search-wrap input {
    width: 12rem;
    padding: 0.28rem 0.55rem;
    font-size: 0.84rem;
  }

  ul.diary-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  li.diary-entry-row {
    display: flex;
    align-items: flex-start;
    gap: 0.7rem;
    padding: 0.62rem 0;
    border-bottom: 1px solid var(--rule-soft);
  }

  .diary-date {
    width: 3.8rem;
    flex-shrink: 0;
    font-family: var(--mono);
    font-size: 0.78rem;
    color: var(--ink-faint);
    padding-top: 0.15rem;
  }

  .diary-thumb {
    width: 2.4rem;
    height: 2.4rem;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule);
    flex-shrink: 0;
  }

  .diary-body {
    flex: 1;
    min-width: 0;
  }

  .diary-title-line {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.45rem;
  }

  .diary-title {
    font-family: var(--display);
    font-size: 1.02rem;
    color: var(--ink);
    text-decoration: none;
  }

  .diary-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .diary-meta {
    font-size: 0.8rem;
    color: var(--ink-faint);
    margin-top: 0.12rem;
  }

  .diary-notes {
    margin: 0.3rem 0 0;
    font-size: 0.86rem;
    color: var(--ink-soft);
  }

  .diary-edit-details summary {
    cursor: pointer;
    font-size: 0.76rem;
    color: var(--ink-faint);
    margin-top: 0.28rem;
  }

  .pace-bars {
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
  }

  .pace-row {
    display: grid;
    grid-template-columns: 2.3rem minmax(0, 1fr) 2rem;
    align-items: center;
    gap: 0.5rem;
    font-size: 0.8rem;
  }

  .pace-row progress {
    width: 100%;
    height: 6px;
    accent-color: var(--accent);
  }

  .pace-month {
    color: var(--ink-soft);
  }

  .pace-cnt {
    font-family: var(--mono);
    text-align: right;
    color: var(--ink-faint);
  }

  .year-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .year-list a {
    display: flex;
    justify-content: space-between;
    padding: 0.3rem 0.4rem;
    border-radius: 2px;
    text-decoration: none;
    color: var(--ink-soft);
    font-size: 0.86rem;
  }

  .year-list a:hover,
  .year-list a.is-active {
    background: var(--paper);
    color: var(--accent);
    font-weight: 600;
  }

  @media (max-width: 880px) {
    .diary-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
