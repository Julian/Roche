<script lang="ts">
  import { onMount, tick } from 'svelte';
  import { api, ApiError, buildQuery } from '../lib/api/client';
  import type { CatalogEntry, RankSessionPayload, RankSubmitResponse } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { safeHttpHref } from '../lib/utils/format';
  import {
    MAX_K_DEFAULT,
    MIN_K,
    activeCardCount,
    appendCoveredSignature,
    canMoveDown,
    canMoveUp,
    clusterSignature,
    createRankState,
    dropCardOnTarget,
    excludeCard,
    flattenBuckets,
    isAllTied,
    isCardTied,
    jumpCardToSlot,
    manualExcludedIds,
    mergeExcludedTokens,
    moveCardDown,
    moveCardUp,
    rankNumbersForBuckets,
    resizeClusterInMemory,
    restoreAllCards,
    restoreCard,
    toggleTieAll,
    toggleTieWithAbove,
    type RankBucketState,
  } from '../lib/utils/rankModel';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import Icons from '../components/Icons.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  const UNDO_EMPTY_SENTINEL = '__empty__';
  const UNDO_STORAGE_PREFIX = 'roche_rank_undo:';

  interface Props {
    slug: string;
  }

  let { slug }: Props = $props();

  let data = $state<RankSessionPayload | null>(null);
  let board = $state<RankBucketState>(createRankState([], [], null));
  let loading = $state(true);
  let submitting = $state(false);
  let undoing = $state(false);
  let busy = $derived(submitting || undoing || loading);
  let errorMsg = $state<string | null>(null);
  let loadController: AbortController | null = null;
  let unmounted = false;

  // Drag state
  let draggedId = $state<string | null>(null);
  let dropTargetId = $state<string | null>(null);
  let dropZone = $state<'before' | 'tie' | 'after' | null>(null);

  let wantParam = $derived(router.route.query.get('want') === 'true');
  let focusParam = $derived(router.route.query.get('focus') ?? '');
  let roundParam = $derived(Number.parseInt(router.route.query.get('round') ?? '0', 10) || 0);
  let skipParam = $derived(Number.parseInt(router.route.query.get('skip') ?? '0', 10) || 0);
  let maxRoundsParam = $derived(router.route.query.get('max_rounds') ?? '');
  let doneParam = $derived(router.route.query.get('done') === 'true');
  let randomParam = $derived(router.route.query.get('random') ?? '');
  let coveredParam = $derived(router.route.query.get('covered') ?? '');
  let excludedParam = $derived(router.route.query.get('excluded') ?? '');
  let kParam = $derived(router.route.query.get('k') ?? '');
  let undoCompParam = $derived(router.route.query.get('undo_comp') ?? '');
  let undoExcludedParam = $derived(router.route.query.get('undo_excluded'));

  let entriesById = $derived.by(() => {
    const map = new Map<string, CatalogEntry>();
    for (const e of data?.cluster ?? []) {
      map.set(e.id, e);
    }
    return map;
  });

  let loadedMaxK = $derived(data?.cluster.length ?? 0);
  let activeCount = $derived(activeCardCount(board));
  let rankNums = $derived(rankNumbersForBuckets(board.buckets));
  let allTied = $derived(isAllTied(board.buckets));
  let excludedList = $derived(Array.from(board.excluded.entries()));
  let hasManualExcluded = $derived(excludedList.some(([, r]) => r === 'manual'));
  let maxSelectableK = $derived(Math.min(data?.total_rows ?? MAX_K_DEFAULT, MAX_K_DEFAULT));
  let sizeOptions = $derived.by(() => {
    const opts: number[] = [];
    for (let k = MIN_K; k <= maxSelectableK; k++) opts.push(k);
    return opts;
  });

  let skipCoveredValue = $derived(
    appendCoveredSignature(coveredParam, clusterSignature(flattenBuckets(board.buckets))),
  );

  function writeRankKCookie(k: number): void {
    const clamped = Math.max(MIN_K, Math.min(MAX_K_DEFAULT, k));
    const secure = window.location.protocol === 'https:' ? '; Secure' : '';
    document.cookie = `roche_rank_k=${clamped}; Path=/; Max-Age=31536000; SameSite=Lax${secure}`;
  }

  function buildRankUrl(overrides: Record<string, string | number | boolean | null | undefined>): string {
    const base: Record<string, string | number | boolean | null | undefined> = {
      want: wantParam ? true : undefined,
      focus: focusParam || undefined,
      round: roundParam > 0 ? roundParam : undefined,
      skip: skipParam > 0 ? skipParam : undefined,
      max_rounds: maxRoundsParam || undefined,
      done: doneParam ? true : undefined,
      random: randomParam || undefined,
      covered: coveredParam || undefined,
      excluded: excludedParam || undefined,
      k: kParam || undefined,
      undo_comp: undefined,
      undo_excluded: undefined,
    };
    const merged = { ...base, ...overrides };
    const qs = buildQuery(merged);
    return `/rank/${encodeURIComponent(slug)}${qs}`;
  }

  async function loadSession(signal: AbortSignal): Promise<void> {
    loading = true;
    errorMsg = null;
    try {
      const qs = buildQuery({
        want: wantParam ? true : undefined,
        focus: focusParam || undefined,
        round: roundParam || undefined,
        skip: skipParam || undefined,
        max_rounds: maxRoundsParam || undefined,
        done: doneParam ? true : undefined,
        random: randomParam || undefined,
        covered: coveredParam || undefined,
        excluded: excludedParam || undefined,
        k: kParam || undefined,
      });
      const payload = await api.get<RankSessionPayload>(
        `/api/v1/catalog/rank/${encodeURIComponent(slug)}/session${qs}`,
        signal,
      );
      if (signal.aborted) return;
      data = payload;
      board = createRankState(
        payload.cluster.map((c) => c.id),
        payload.priority_ids,
        payload.focus_id,
      );
      draggedId = null;
      dropTargetId = null;
      dropZone = null;
      router.setTitle(`Rank: ${payload.collection.title}`);
    } catch (err) {
      if (signal.aborted || (err instanceof DOMException && err.name === 'AbortError')) return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load ranking session.';
    } finally {
      if (!signal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    void router.navVersion;
    void slug;
    void wantParam;
    void focusParam;
    void roundParam;
    void skipParam;
    void maxRoundsParam;
    void doneParam;
    void randomParam;
    void coveredParam;
    void excludedParam;
    void kParam;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadSession(controller.signal);
    return () => controller.abort();
  });

  function navigateAndLoad(url: string): void {
    const beforeNav = router.navVersion;
    loading = true;
    try {
      router.navigate(url);
    } catch (err) {
      loading = false;
      throw err;
    }
    if (router.navVersion === beforeNav) {
      loading = false;
    }
  }

  async function preserveMoveFocus(cardId: string, preferred: 'up' | 'down'): Promise<void> {
    await tick();
    const cardEl = document.querySelector<HTMLElement>(
      `.js-rank-card[data-entry-id="${CSS.escape(cardId)}"]`,
    );
    if (!cardEl) return;
    const primary = cardEl.querySelector<HTMLButtonElement>(
      preferred === 'up' ? '.js-card-up' : '.js-card-down',
    );
    const fallback = cardEl.querySelector<HTMLButtonElement>(
      preferred === 'up' ? '.js-card-down' : '.js-card-up',
    );
    if (primary && !primary.disabled) {
      primary.focus();
    } else if (fallback && !fallback.disabled) {
      fallback.focus();
    }
  }

  async function handleMoveUp(cardId: string): Promise<void> {
    if (busy) return;
    board = { ...board, buckets: moveCardUp(board.buckets, cardId), selectedId: cardId };
    await preserveMoveFocus(cardId, 'up');
  }

  async function handleMoveDown(cardId: string): Promise<void> {
    if (busy) return;
    board = { ...board, buckets: moveCardDown(board.buckets, cardId), selectedId: cardId };
    await preserveMoveFocus(cardId, 'down');
  }

  async function handleToggleTie(cardId: string): Promise<void> {
    if (busy) return;
    board = { ...board, buckets: toggleTieWithAbove(board.buckets, cardId), selectedId: cardId };
    await tick();
    const tieBtn = document.querySelector<HTMLButtonElement>(
      `.js-rank-card[data-entry-id="${CSS.escape(cardId)}"] .js-card-tie`,
    );
    if (tieBtn && !tieBtn.disabled) {
      tieBtn.focus();
    }
  }

  function handleExclude(cardId: string): void {
    if (busy) return;
    board = excludeCard(board, cardId, 'manual');
  }

  function handleRestore(cardId: string): void {
    if (busy) return;
    board = restoreCard(board, cardId);
    writeRankKCookie(activeCardCount(board));
  }

  function handleRestoreAll(): void {
    if (busy) return;
    board = restoreAllCards(board);
    writeRankKCookie(activeCardCount(board));
  }

  function handleSelectSize(targetK: number): void {
    if (busy || errorMsg) return;
    const clamped = Math.max(MIN_K, Math.min(maxSelectableK, targetK));
    writeRankKCookie(clamped);
    if (clamped <= loadedMaxK) {
      board = resizeClusterInMemory(board, clamped);
    } else {
      const mergedExcluded = mergeExcludedTokens(
        excludedParam,
        manualExcludedIds(board),
        flattenBuckets(board.buckets),
      );
      navigateAndLoad(
        buildRankUrl({
          k: clamped,
          excluded: mergedExcluded || undefined,
        }),
      );
    }
  }

  async function handleSubmitComparison(e?: Event): Promise<void> {
    e?.preventDefault();
    if (!data || errorMsg || busy || activeCount < MIN_K) return;
    const startNavVersion = router.navVersion;
    submitting = true;
    try {
      const kToPersist = activeCount;
      const prevCovered = coveredParam;
      const prevExcluded = excludedParam;
      writeRankKCookie(kToPersist);
      const res = await api.post<RankSubmitResponse>(
        `/api/v1/catalog/rank/${encodeURIComponent(slug)}/submit`,
        {
          ordering: board.buckets,
          want: data.want,
          covered: prevCovered || null,
          excluded: prevExcluded || null,
          round_excluded: manualExcludedIds(board),
          k: kToPersist,
        },
      );
      try {
        sessionStorage.setItem(
          `${UNDO_STORAGE_PREFIX}${res.comparison.id}`,
          JSON.stringify({ prevExcluded, prevCovered }),
        );
      } catch {
        // Ignore storage errors
      }
      if (unmounted || router.navVersion !== startNavVersion) return;
      const undoExcludedToken =
        res.undo_excluded !== null ? res.undo_excluded || UNDO_EMPTY_SENTINEL : undefined;
      navigateAndLoad(
        buildRankUrl({
          round: data.round + 1,
          skip: undefined,
          want: res.want ? true : undefined,
          covered: res.next_covered || undefined,
          excluded: res.next_excluded || undefined,
          k: kToPersist !== MAX_K_DEFAULT ? kToPersist : undefined,
          undo_comp: res.comparison.id,
          undo_excluded: undoExcludedToken,
        }),
      );
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to save comparison.', {
        isError: true,
      });
    } finally {
      submitting = false;
    }
  }

  function handleSkip(e?: Event): void {
    e?.preventDefault();
    if (!data || errorMsg || busy) return;
    const mergedExcluded = mergeExcludedTokens(
      excludedParam,
      manualExcludedIds(board),
      flattenBuckets(board.buckets),
    );
    navigateAndLoad(
      buildRankUrl({
        skip: data.skip + 1,
        covered: skipCoveredValue || undefined,
        excluded: mergedExcluded || undefined,
      }),
    );
  }

  async function handleUndoComparison(): Promise<void> {
    if (!undoCompParam || !data || errorMsg || busy) return;
    const startNavVersion = router.navVersion;
    undoing = true;
    try {
      await api.post(`/api/v1/catalog/comparisons/${encodeURIComponent(undoCompParam)}/undo`);
      toast.show('Undid last comparison');

      let savedPrev: { prevExcluded?: string; prevCovered?: string } | null = null;
      try {
        const raw = sessionStorage.getItem(`${UNDO_STORAGE_PREFIX}${undoCompParam}`);
        if (raw) {
          savedPrev = JSON.parse(raw) as { prevExcluded?: string; prevCovered?: string };
          sessionStorage.removeItem(`${UNDO_STORAGE_PREFIX}${undoCompParam}`);
        }
      } catch {
        // Ignore
      }

      if (unmounted || router.navVersion !== startNavVersion) return;
      const restoredExcluded = savedPrev
        ? savedPrev.prevExcluded || undefined
        : undoExcludedParam === UNDO_EMPTY_SENTINEL
          ? undefined
          : undoExcludedParam !== null
            ? undoExcludedParam || undefined
            : excludedParam || undefined;
      const restoredCovered = savedPrev
        ? savedPrev.prevCovered || undefined
        : coveredParam || undefined;

      navigateAndLoad(
        buildRankUrl({
          round: Math.max(0, data.round - 1),
          covered: restoredCovered,
          excluded: restoredExcluded,
          done: undefined,
          undo_comp: undefined,
          undo_excluded: undefined,
        }),
      );
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to undo comparison.', {
        isError: true,
      });
    } finally {
      undoing = false;
    }
  }

  function handleDragStart(e: DragEvent, cardId: string): void {
    if (busy) {
      e.preventDefault();
      return;
    }
    draggedId = cardId;
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', cardId);
    }
  }

  function handleDragOver(e: DragEvent, targetId: string): void {
    if (busy || !draggedId || draggedId === targetId) return;
    e.preventDefault();
    const el = e.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const relY = (e.clientY - rect.top) / Math.max(1, rect.height);
    dropTargetId = targetId;
    dropZone = relY < 0.27 ? 'before' : relY > 0.73 ? 'after' : 'tie';
  }

  function handleDrop(e: DragEvent, targetId: string): void {
    e.preventDefault();
    if (!busy && draggedId && draggedId !== targetId && dropZone) {
      board = {
        ...board,
        buckets: dropCardOnTarget(board.buckets, draggedId, targetId, dropZone),
        selectedId: draggedId,
      };
    }
    draggedId = null;
    dropTargetId = null;
    dropZone = null;
  }

  function handleDragEnd(): void {
    draggedId = null;
    dropTargetId = null;
    dropZone = null;
  }

  onMount(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (modals.anyOpen || errorMsg || !data || data.settled || busy) return;
      const target = e.target as HTMLElement | null;
      if (
        e.metaKey ||
        e.ctrlKey ||
        e.altKey ||
        target?.closest('input, textarea, select, dialog[open]')
      ) {
        return;
      }

      const flat = flattenBuckets(board.buckets);
      const selId = board.selectedId && flat.includes(board.selectedId) ? board.selectedId : (flat[0] ?? null);
      const selIdx = selId ? flat.indexOf(selId) : -1;

      if ((e.key === 'j' || e.key === 'ArrowDown') && !e.shiftKey) {
        e.preventDefault();
        if (flat.length > 0) {
          const next = flat[Math.min(flat.length - 1, selIdx + 1)]!;
          board = { ...board, selectedId: next };
        }
      } else if ((e.key === 'k' || e.key === 'ArrowUp') && !e.shiftKey) {
        e.preventDefault();
        if (flat.length > 0) {
          const prev = flat[Math.max(0, selIdx - 1)]!;
          board = { ...board, selectedId: prev };
        }
      } else if ((e.key === 'J' || (e.key === 'ArrowDown' && e.shiftKey)) && selId) {
        e.preventDefault();
        board = { ...board, buckets: moveCardDown(board.buckets, selId), selectedId: selId };
      } else if ((e.key === 'K' || (e.key === 'ArrowUp' && e.shiftKey)) && selId) {
        e.preventDefault();
        board = { ...board, buckets: moveCardUp(board.buckets, selId), selectedId: selId };
      } else if (/^[1-9]$/.test(e.key) && selId) {
        e.preventDefault();
        const slot = Number.parseInt(e.key, 10);
        const nextBuckets = jumpCardToSlot(board.buckets, selId, slot);
        const nextFlat = flattenBuckets(nextBuckets);
        const nextSel = nextFlat[(nextFlat.indexOf(selId) + 1) % nextFlat.length] ?? selId;
        board = { ...board, buckets: nextBuckets, selectedId: nextSel };
      } else if (e.key === '=' && selId) {
        e.preventDefault();
        board = { ...board, buckets: toggleTieWithAbove(board.buckets, selId), selectedId: selId };
      } else if (e.key === 't') {
        e.preventDefault();
        board = { ...board, buckets: toggleTieAll(board.buckets) };
      } else if ((e.key === 'x' || e.key === 'Backspace' || e.key === 'Delete') && selId) {
        e.preventDefault();
        board = excludeCard(board, selId, 'manual');
      } else if (e.key === '-' || e.key === '_') {
        e.preventDefault();
        if (activeCount > MIN_K) handleSelectSize(activeCount - 1);
      } else if (e.key === '+') {
        e.preventDefault();
        if (activeCount < maxSelectableK) handleSelectSize(activeCount + 1);
      } else if (e.key === 's') {
        e.preventDefault();
        handleSkip();
      } else if (e.key === 'o' && selId) {
        const u = safeHttpHref(data.open_urls[selId]);
        if (u) {
          e.preventDefault();
          window.open(u, '_blank', 'noopener,noreferrer');
        }
      } else if (e.key === 'Enter' && !target?.closest('button, a')) {
        e.preventDefault();
        void handleSubmitComparison();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      unmounted = true;
      loadController?.abort();
      window.removeEventListener('keydown', onKeyDown);
    };
  });
</script>

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{:else if loading && !data}
  <p class="empty">Loading ranking session…</p>
{:else if data}
  {@const listSlug = data.collection.slug ?? slug}
  <nav class="rank-crumb" aria-label="Breadcrumb">
    {#if data.want}
      <a href="/queue">Queue</a> <span aria-hidden="true">/</span> <span>{data.collection.title}</span>
    {:else}
      <a href="/lists">Lists</a> <span aria-hidden="true">/</span>
      <a href={`/lists/${encodeURIComponent(listSlug)}`}>{data.collection.title}</a>
    {/if}
  </nav>

  <div class="list-detail-head">
    <div class="list-detail-title-group">
      <h2>{data.want ? 'Prioritize' : 'Rank'}: {data.collection.title}</h2>
      <span class="list-count-badge">
        {#if data.focus_id}
          Round {data.round + (data.settled ? 0 : 1)} of {data.max_rounds}
        {:else if data.want}
          Queue Priority · Round {data.round + 1}
        {:else}
          Round {data.round + 1}
        {/if}
      </span>
    </div>
  </div>

  {#if undoCompParam}
    <div class="undo-banner" role="status">
      <span>Comparison saved.</span>
      <button
        type="button"
        class="btn btn-small"
        disabled={busy}
        onclick={() => void handleUndoComparison()}
      >
        {undoing ? 'Undoing…' : 'Undo'}
      </button>
    </div>
  {/if}

  {#if data.settled}
    <section class="settled-card" aria-label="Ranking settled">
      {#if data.focus_row}
        <div class="album-kicker">Placement Settled</div>
        <h3 class="settled-title">
          <MathTitle html={data.title_html[data.focus_row.entry.id]} text={data.focus_row.entry.title} />
        </h3>
        <p class="lede mb-md">
          Settled at <strong>#{data.focus_row.rank}</strong> of {data.total_rows}
          {#if data.focus_row.tier}
            · <strong class="tier-{data.focus_row.tier}">Tier {data.focus_row.tier}</strong>
          {/if}
          after {data.round} comparison round(s).
        </p>
      {:else if data.focus_missing && focusParam}
        <p class="lede mb-md">
          This work is not in <strong>{data.collection.title}</strong> yet. Mark it Finished on its
          <a href={`/entries/${encodeURIComponent(focusParam)}`}>entry page</a> before ranking it.
        </p>
      {:else if data.total_rows < 2}
        <p class="lede mb-md">Need at least 2 works in this collection before running comparisons.</p>
      {:else}
        <p class="lede mb-md">Rankings are well-calibrated after {data.round} round(s).</p>
      {/if}

      <div class="flex-row">
        {#if data.want}
          <a class="btn btn-primary" href="/queue">Back to Queue</a>
        {:else}
          <a class="btn btn-primary" href={`/lists/${encodeURIComponent(listSlug)}`}>
            View Ranked List
          </a>
        {/if}

        {#if data.focus_row && data.total_rows >= 2 && data.round < 50}
          <a
            class="btn"
            href={buildRankUrl({
              round: data.round,
              focus: data.focus_row.entry.id,
              max_rounds: Math.min(50, data.round + 5),
              done: undefined,
            })}
          >
            Keep Refining
          </a>
        {/if}

        {#if data.total_rows >= 2}
          <a
            class="btn"
            href={buildRankUrl({
              round: undefined,
              focus: undefined,
              done: undefined,
              random: 1,
            })}
          >
            Keep Comparing
          </a>
        {/if}

        <a class="btn btn-ghost" href="/">Home</a>
      </div>
    </section>
  {:else}
    <div class="rank-grid">
      <section class="rank-main" aria-label="Comparison board">
        {#if sizeOptions.length > 1}
          <div class="rank-size-bar" role="group" aria-label="Comparison cluster size">
            <span class="rank-size-label">Compare</span>
            {#each sizeOptions as kOpt (kOpt)}
              <button
                type="button"
                class="pill js-rank-size-opt"
                class:is-active={activeCount === kOpt}
                data-rank-k={kOpt}
                aria-current={activeCount === kOpt ? 'true' : undefined}
                disabled={busy}
                onclick={() => handleSelectSize(kOpt)}
              >
                {kOpt}
              </button>
            {/each}
          </div>
        {/if}

        <div
          id="roche-rank-board"
          class="rank-board"
          class:is-loading={busy}
          aria-busy={busy ? 'true' : undefined}
        >
          {#each board.buckets as bucket, bIdx (bucket.join('|'))}
            <div class="rank-bucket" class:is-tied={bucket.length > 1} data-bucket-idx={bIdx}>
              {#each bucket as cardId (cardId)}
                {@const entry = entriesById.get(cardId)}
                {#if entry}
                  {@const rankNum = rankNums.get(cardId) ?? bIdx + 1}
                  {@const tied = isCardTied(board.buckets, cardId)}
                  {@const upEnabled = canMoveUp(board.buckets, cardId)}
                  {@const downEnabled = canMoveDown(board.buckets, cardId)}
                  {@const excludeDisabled = cardId === board.focusId || activeCount <= MIN_K}
                  {@const openUrl = safeHttpHref(data.open_urls[cardId])}
                  <div
                    class="rank-card js-rank-card"
                    class:is-selected={board.selectedId === cardId}
                    class:is-focus={cardId === board.focusId}
                    class:is-dragging={draggedId === cardId}
                    class:drop-before={dropTargetId === cardId && dropZone === 'before'}
                    class:drop-tie={dropTargetId === cardId && dropZone === 'tie'}
                    class:drop-after={dropTargetId === cardId && dropZone === 'after'}
                    role="group"
                    aria-label={entry.title}
                    data-entry-id={cardId}
                    draggable={!busy}
                    ondragstart={(e) => handleDragStart(e, cardId)}
                    ondragover={(e) => handleDragOver(e, cardId)}
                    ondrop={(e) => handleDrop(e, cardId)}
                    ondragend={handleDragEnd}
                    onpointerdown={() => {
                      if (!busy) board = { ...board, selectedId: cardId };
                    }}
                    onfocusin={() => {
                      if (!busy) board = { ...board, selectedId: cardId };
                    }}
                  >
                    <span class="rank-grip" aria-hidden="true">
                      <Icons name="grip" class="rank-grip-icon" />
                    </span>

                    <span class="rank-pos">{tied ? `=${rankNum}` : rankNum}</span>

                    {#if entry.thumbnail_url}
                      <img
                        class="rank-thumb"
                        src={entry.thumbnail_url}
                        alt=""
                        loading="lazy"
                        referrerpolicy="no-referrer"
                      />
                    {/if}

                    <div class="rank-card-body">
                      <div class="rank-card-title">
                        <a href={`/entries/${encodeURIComponent(entry.id)}`} target="_blank" rel="noopener">
                          <MathTitle html={data.title_html[entry.id]} text={entry.title} />
                        </a>
                        {#if cardId === board.focusId}
                          <span class="badge">Focus</span>
                        {/if}
                      </div>
                      <div class="rank-card-meta">
                        {#if entry.creators.length > 0}
                          <CreatorLinks
                            creators={entry.creators}
                            artistLinks={data.artist_links}
                            openNewTab
                          />
                        {/if}
                        {#if entry.release_year}
                          {#if entry.creators.length > 0} · {/if}{entry.release_year}
                        {/if}
                        {#if openUrl}
                          · <a href={openUrl} target="_blank" rel="noopener noreferrer">Open ↗</a>
                        {/if}
                      </div>
                    </div>

                    <div class="rank-controls">
                      <button
                        type="button"
                        class="btn btn-small btn-ghost js-card-up"
                        aria-label={`Move ${entry.title} up`}
                        title="Move up (K)"
                        disabled={busy || !upEnabled}
                        onclick={(e) => {
                          e.stopPropagation();
                          void handleMoveUp(cardId);
                        }}
                      >
                        ↑
                      </button>
                      <button
                        type="button"
                        class="btn btn-small btn-ghost js-card-down"
                        aria-label={`Move ${entry.title} down`}
                        title="Move down (J)"
                        disabled={busy || !downEnabled}
                        onclick={(e) => {
                          e.stopPropagation();
                          void handleMoveDown(cardId);
                        }}
                      >
                        ↓
                      </button>
                      <button
                        type="button"
                        class="btn btn-small btn-ghost js-card-tie"
                        aria-label={tied ? `Untie ${entry.title}` : `Tie ${entry.title} with above`}
                        aria-pressed={tied}
                        disabled={busy || (!tied && bIdx === 0)}
                        title={tied ? 'Untie (=)' : 'Tie with above (=)'}
                        onclick={(e) => {
                          e.stopPropagation();
                          void handleToggleTie(cardId);
                        }}
                      >
                        {tied ? 'Untie' : 'Tie'}
                      </button>
                      <button
                        type="button"
                        class="btn btn-small btn-ghost btn-danger-quiet js-card-exclude"
                        aria-label={`Leave ${entry.title} out of this comparison`}
                        title="Leave out (x)"
                        disabled={busy || excludeDisabled}
                        onclick={(e) => {
                          e.stopPropagation();
                          handleExclude(cardId);
                        }}
                      >
                        ×
                      </button>
                    </div>
                  </div>
                {/if}
              {/each}
            </div>
          {/each}
        </div>

        <div class="rank-excluded-tray js-rank-excluded-tray" hidden={excludedList.length === 0}>
          <div class="rank-excluded-head">
            <span class="js-rank-excluded-label">
              {hasManualExcluded
                ? `Left out of this comparison (${excludedList.length})`
                : `Set aside at this size (${excludedList.length})`}
            </span>
            <button
              type="button"
              class="link-btn js-rank-restore-all"
              disabled={busy}
              onclick={handleRestoreAll}
            >
              Restore all
            </button>
          </div>
          <div class="rank-excluded-chips">
            {#each excludedList as [exId] (exId)}
              {@const exEntry = entriesById.get(exId)}
              {#if exEntry}
                <button
                  type="button"
                  class="pill js-rank-restore-chip"
                  disabled={busy}
                  onclick={() => handleRestore(exId)}
                >
                  + {exEntry.title}
                </button>
              {/if}
            {/each}
          </div>
        </div>

        <div class="rank-actions">
          <button
            type="button"
            class="btn btn-primary"
            disabled={busy || activeCount < MIN_K}
            onclick={() => void handleSubmitComparison()}
          >
            {submitting ? 'Saving…' : loading ? 'Loading next…' : 'Submit Comparison'}
          </button>

          <button
            type="button"
            class="btn btn-ghost"
            disabled={busy}
            onclick={() => {
              if (busy) return;
              board = { ...board, buckets: toggleTieAll(board.buckets) };
            }}
          >
            {allTied ? 'Untie All' : '= Tie All'}
          </button>

          <form id="rank-skip-form" class="inline-form" onsubmit={handleSkip}>
            <input type="hidden" name="covered" value={skipCoveredValue} />
            <button type="submit" class="btn btn-ghost" disabled={busy}>Skip (s)</button>
          </form>
        </div>
      </section>

      <aside class="rank-rail" aria-label="Session info">
        {#if data.focus_row}
          <section class="rail-block rail-card">
            <h3 class="mt-0">Focus Placement</h3>
            <p class="meta mb-sm">
              Provisional: <strong>#{data.focus_row.rank}</strong> of {data.total_rows}
              {#if data.focus_row.tier}
                · <strong class="tier-{data.focus_row.tier}">Tier {data.focus_row.tier}</strong>
              {/if}
            </p>
            {#if data.round >= 1}
              <a class="btn btn-small btn-ghost" href={buildRankUrl({ done: true })}>
                Done Placing
              </a>
            {/if}
          </section>
        {/if}

        <section class="rail-block rail-card">
          <h3 class="mt-0">Session</h3>
          <p class="meta mb-sm">
            {data.total_rows} works · {data.comparison_count} comparison{data.comparison_count === 1 ? '' : 's'}
          </p>
          <p class="meta mb-0">
            Drag cards or use <kbd>j</kbd>/<kbd>k</kbd> to select, <kbd>J</kbd>/<kbd>K</kbd> to reorder,
            <kbd>=</kbd> to tie, <kbd>x</kbd> to leave out, and <kbd>Enter</kbd> to submit.
          </p>
        </section>
      </aside>
    </div>
  {/if}
{/if}

<style>
  .rank-crumb {
    font-size: 0.84rem;
    color: var(--ink-faint);
    margin-bottom: 0.45rem;
  }

  .rank-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(18.5rem, 22rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .rank-size-bar {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    margin-bottom: 0.9rem;
  }

  .rank-size-label {
    font-size: 0.78rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--ink-faint);
    margin-right: 0.25rem;
  }

  .rank-board {
    display: flex;
    flex-direction: column;
    gap: 0.55rem;
    transition: opacity 120ms ease;
  }

  .rank-board.is-loading {
    opacity: 0.55;
    pointer-events: none;
  }

  .rank-bucket {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .rank-bucket.is-tied {
    padding: 0.4rem;
    background: var(--paper-deep);
    border: 1px dashed var(--control-border);
    border-radius: 3px;
  }

  .rank-card {
    flex: 1 1 18rem;
    display: flex;
    align-items: center;
    gap: 0.65rem;
    padding: 0.7rem 0.85rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    cursor: grab;
    transition: border-color 120ms ease, box-shadow 120ms ease;
  }

  .rank-card.is-selected {
    border-color: var(--accent);
    box-shadow: 0 0 0 1px var(--accent);
  }

  .rank-card.is-focus {
    border-left: 3px solid var(--accent);
  }

  .rank-card.is-dragging {
    opacity: 0.5;
  }

  .rank-card.drop-before {
    box-shadow: 0 -3px 0 0 var(--accent);
  }

  .rank-card.drop-after {
    box-shadow: 0 3px 0 0 var(--accent);
  }

  .rank-card.drop-tie {
    outline: 2px dashed var(--accent);
  }

  .rank-grip {
    color: var(--ink-faint);
    display: inline-flex;
    align-items: center;
  }

  .rank-grip :global(.rank-grip-icon) {
    width: 12px;
    height: 18px;
  }

  .rank-pos {
    font-family: var(--mono);
    font-weight: 600;
    font-size: 0.88rem;
    color: var(--ink-soft);
    min-width: 1.5rem;
  }

  .rank-thumb {
    width: 2.75rem;
    height: 2.75rem;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule);
    flex-shrink: 0;
  }

  .rank-card-body {
    flex: 1;
    min-width: 0;
  }

  .rank-card-title {
    display: flex;
    align-items: baseline;
    gap: 0.4rem;
    flex-wrap: wrap;
    font-family: var(--display);
    font-size: 1.04rem;
  }

  .rank-card-title a {
    color: var(--ink);
    text-decoration: none;
  }

  .rank-card-title a:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .rank-card-meta {
    font-size: 0.8rem;
    color: var(--ink-faint);
    margin-top: 0.12rem;
  }

  .rank-controls {
    display: flex;
    align-items: center;
    gap: 0.28rem;
    flex-shrink: 0;
  }

  .rank-excluded-tray {
    margin-top: 1rem;
    padding: 0.7rem 0.9rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
  }

  .rank-excluded-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 0.82rem;
    color: var(--ink-soft);
    margin-bottom: 0.45rem;
  }

  .rank-excluded-chips {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
  }

  .rank-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.55rem;
    margin-top: 1.25rem;
  }

  .inline-form {
    display: inline;
    margin: 0;
  }

  .settled-card {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    padding: 1.35rem 1.5rem;
    max-width: 44rem;
  }

  .album-kicker {
    font-size: 0.72rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--ink-faint);
    font-weight: 600;
  }

  .settled-title {
    border: none;
    margin: 0.2rem 0 0.5rem;
    font-size: 1.3rem;
    text-transform: none;
    letter-spacing: 0;
    color: var(--ink);
  }

  @media (max-width: 960px) {
    .rank-grid {
      grid-template-columns: 1fr;
    }
  }

  @media (max-width: 600px) {
    .rank-card {
      flex-wrap: wrap;
    }
    .rank-controls {
      width: 100%;
      justify-content: flex-end;
    }
  }
</style>
