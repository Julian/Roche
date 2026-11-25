<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import { api, ApiError, buildQuery } from '../lib/api/client';
  import {
    WANT_TO_READ_COLLECTION_ID,
    type Board,
    type BoardDismissViewResponse,
    type BoardItemView,
    type BoardItemsPage,
    type BoardOrderMode,
    type BoardPromoteViewResponse,
    type BoardUndismissViewResponse,
  } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { refresh } from '../lib/stores/refresh.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    clampExcerpt,
    formatCommentsLabel,
    formatRelativeAge,
    safeHttpHref,
  } from '../lib/utils/format';
  import Icons from '../components/Icons.svelte';

  interface Props {
    boardId: string | null;
  }

  let { boardId }: Props = $props();

  const QUICK_HN_BOARDS = [
    { section: 'top', title: 'Hacker News', desc: 'Front page ranked stories' },
    { section: 'best', title: 'HN Best', desc: 'Highest-voted recent stories' },
    { section: 'ask', title: 'Ask HN', desc: 'Community questions & discussions' },
    { section: 'show', title: 'Show HN', desc: 'Projects shared by builders' },
  ] as const;

  let boards = $state<Board[]>([]);
  let selectedBoard = $state<Board | null>(null);
  let items = $state<BoardItemView[]>([]);
  let hiddenIds = $state<Set<string>>(new Set());
  let liveIds = $state<Set<string>>(new Set());
  let busyStoryIds = $state<Set<string>>(new Set());
  let currentPage = $state(1);
  let hasMoreBuffered = $state(false);
  let hasMoreLive = $state(false);
  let nextLiveOffset = $state<number | null>(null);
  let loading = $state(true);
  let loadingMore = $state(false);
  let errorMsg = $state<string | null>(null);

  let searchQuery = $state('');
  let selectedIdx = $state(-1);
  let settingsDetailsEl = $state<HTMLDetailsElement | null>(null);

  // Add board form
  let addSection = $state('top');
  let addTitle = $state('');
  let addBusy = $state(false);

  // Settings form
  let editTitle = $state('');
  let editOrderMode = $state<BoardOrderMode>('ranked');
  let editPollInterval = $state('30');
  let settingsBusy = $state(false);

  let pendingBackfills = 0;
  let loadMoreEpoch = 0;
  let boardEpoch = 0;
  let loadController: AbortController | null = null;

  let viewMode = $derived(router.route.query.get('view') === 'dismissed' ? 'dismissed' : 'active');
  let queryBoardId = $derived(router.route.query.get('board'));
  let urlQParam = $derived(router.route.query.get('q') ?? '');
  let effectiveBoardId = $derived(boardId ?? queryBoardId ?? boards[0]?.id ?? null);

  let visibleItems = $derived.by(() => {
    const q = searchQuery.trim().toLowerCase();
    return items.filter((item) => {
      if (hiddenIds.has(item.external_id)) return false;
      if (!q) return true;
      return `${item.title} ${item.domain ?? ''} ${item.author ?? ''}`.toLowerCase().includes(q);
    });
  });

  let activeCount = $derived(selectedBoard ? Math.max(0, selectedBoard.visible_count) : 0);
  let dismissedCount = $derived(
    selectedBoard ? Math.max(0, selectedBoard.item_count - selectedBoard.visible_count) : 0,
  );

  function sourceBadgeLabel(b: Board): string {
    switch (b.source_key) {
      case 'top':
        return 'Hacker News · Front Page';
      case 'best':
        return 'Hacker News · Best';
      case 'ask':
        return 'Hacker News · Ask HN';
      case 'show':
        return 'Hacker News · Show HN';
      case 'new':
        return 'Hacker News · Newest';
      default:
        return `Hacker News · ${b.source_key}`;
    }
  }

  function updateBoardCounts(bId: string, visible: number, dismissed: number): void {
    const itemCount = visible + dismissed;
    boards = boards.map((b) =>
      b.id === bId ? { ...b, visible_count: visible, item_count: itemCount } : b,
    );
    if (selectedBoard && selectedBoard.id === bId) {
      selectedBoard = { ...selectedBoard, visible_count: visible, item_count: itemCount };
    }
  }

  function syncSearchToUrl(): void {
    const sp = new URLSearchParams(router.route.query);
    const trimmed = searchQuery.trim();
    if (trimmed) {
      sp.set('q', trimmed);
    } else {
      sp.delete('q');
    }
    router.replaceSearch(sp);
  }

  async function loadBoardsAndItems(signal?: AbortSignal): Promise<void> {
    let localController: AbortController | null = null;
    if (!signal) {
      loadController?.abort();
      localController = new AbortController();
      loadController = localController;
    }
    const activeSignal = signal ?? localController!.signal;

    loading = true;
    errorMsg = null;
    hiddenIds = new Set();
    liveIds = new Set();
    busyStoryIds = new Set();
    pendingBackfills = 0;
    loadMoreEpoch += 1;
    boardEpoch += 1;
    try {
      const listRes = await api.get<{ boards: Board[] }>('/api/v1/boards', activeSignal);
      if (activeSignal.aborted) return;
      boards = listRes.boards;
      if (boards.length === 0) {
        selectedBoard = null;
        items = [];
        router.setTitle('Boards');
        return;
      }
      const targetId = boardId ?? queryBoardId ?? boards[0]!.id;
      const found = boards.find((b) => b.id === targetId) ?? boards[0]!;
      selectedBoard = found;
      editTitle = found.title;
      editOrderMode = found.order_mode;
      editPollInterval = String(found.poll_interval);
      router.setTitle(found.title);

      const urlQ = urlQParam;
      searchQuery = urlQ;

      const qs =
        viewMode === 'dismissed'
          ? buildQuery({
              page: 1,
              per_page: urlQ ? 90 : 30,
              dismissed_only: true,
            })
          : buildQuery({
              page: 1,
              per_page: urlQ ? 90 : 30,
              include_dismissed: false,
            });
      const pageRes = await api.get<BoardItemsPage>(
        `/api/v1/boards/${encodeURIComponent(found.id)}/items${qs}`,
        activeSignal,
      );
      if (activeSignal.aborted) return;
      selectedBoard = pageRes.board;
      boards = boards.map((b) => (b.id === pageRes.board.id ? pageRes.board : b));
      items =
        viewMode === 'dismissed'
          ? pageRes.items.filter((i) => Boolean(i.dismissed_at))
          : pageRes.items.filter((i) => !i.dismissed_at);
      liveIds = pageRes.is_live_page
        ? new Set(items.map((i) => i.external_id))
        : new Set();
      currentPage = pageRes.page;
      hasMoreBuffered = pageRes.has_more_buffered;
      hasMoreLive = pageRes.has_more_live;
      nextLiveOffset = pageRes.next_live_offset;
      selectedIdx = -1;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load boards.';
    } finally {
      if (!activeSignal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    const nextQ = urlQParam;
    if (untrack(() => searchQuery).trim() !== nextQ) {
      searchQuery = nextQ;
    }
  });

  $effect(() => {
    if (selectedIdx >= visibleItems.length) {
      selectedIdx = visibleItems.length - 1;
    }
  });

  $effect(() => {
    void boardId;
    void queryBoardId;
    void viewMode;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadBoardsAndItems(controller.signal);
    return () => controller.abort();
  });

  onMount(() => {
    const unsub = refresh.onRefreshComplete(() => {
      void loadBoardsAndItems();
    });
    const onKeyDown = (e: KeyboardEvent) => {
      if (modals.anyOpen) return;
      if (e.key === 'Escape' && settingsDetailsEl?.open) {
        e.preventDefault();
        settingsDetailsEl.open = false;
        return;
      }
      const target = e.target as HTMLElement | null;
      if (
        e.metaKey ||
        e.ctrlKey ||
        e.altKey ||
        target?.closest('input, textarea, select, dialog[open]')
      ) {
        return;
      }
      const list = visibleItems;
      if (e.key === 'j') {
        e.preventDefault();
        if (list.length > 0) {
          selectedIdx = Math.min(list.length - 1, selectedIdx + 1);
          scrollRowIntoView(list[selectedIdx]?.external_id);
        }
      } else if (e.key === 'k') {
        e.preventDefault();
        if (list.length > 0) {
          selectedIdx = Math.max(0, selectedIdx - 1);
          scrollRowIntoView(list[selectedIdx]?.external_id);
        }
      } else if ((e.key === 'o' || e.key === 'Enter') && selectedIdx >= 0 && list[selectedIdx] && selectedBoard) {
        e.preventDefault();
        const story = list[selectedIdx]!;
        router.navigate(
          `/boards/${encodeURIComponent(selectedBoard.id)}/read/${encodeURIComponent(story.external_id)}?return_to=${encodeURIComponent(router.fullPath)}`,
        );
      } else if (e.key === 'd' && selectedIdx >= 0 && list[selectedIdx]) {
        const discHref = safeHttpHref(list[selectedIdx]?.discussion_url);
        if (discHref) {
          e.preventDefault();
          window.open(discHref, '_blank', 'noopener,noreferrer');
        }
      } else if (e.key === 'e' && selectedIdx >= 0 && list[selectedIdx]) {
        e.preventDefault();
        if (viewMode === 'dismissed') {
          void handleRestore(list[selectedIdx]!);
        } else {
          void handleDismiss(list[selectedIdx]!);
        }
      } else if (e.key === 's' && selectedIdx >= 0 && list[selectedIdx]) {
        e.preventDefault();
        void handleStar(list[selectedIdx]!);
      } else if (e.key === 'q' && selectedIdx >= 0 && list[selectedIdx]) {
        e.preventDefault();
        void handleQueue(list[selectedIdx]!);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      unsub();
      loadController?.abort();
      window.removeEventListener('keydown', onKeyDown);
    };
  });

  function scrollRowIntoView(extId: string | undefined): void {
    if (!extId) return;
    document
      .querySelector(`li.board-row[data-external-id="${CSS.escape(extId)}"]`)
      ?.scrollIntoView({ block: 'nearest' });
  }

  async function handleQuickAdd(section: string, title?: string): Promise<void> {
    if (addBusy) return;
    addBusy = true;
    try {
      const created = await api.post<Board>('/api/v1/boards', {
        adapter_type: 'hacker_news',
        section,
        title: title?.trim() || undefined,
      });
      addTitle = '';
      toast.show(`Added board '${created.title}'`);
      router.navigate(`/boards/${encodeURIComponent(created.id)}`);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to add board.', { isError: true });
    } finally {
      addBusy = false;
    }
  }

  async function handleRefreshBoard(): Promise<void> {
    if (!selectedBoard) return;
    try {
      await api.post(`/api/v1/boards/${encodeURIComponent(selectedBoard.id)}/refresh?background=true`);
      refresh.startPolling();
      toast.show(`Refreshing '${selectedBoard.title}'…`);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to refresh board.', {
        isError: true,
      });
    }
  }

  async function handleSaveSettings(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!selectedBoard || settingsBusy) return;
    settingsBusy = true;
    try {
      const poll = Number.parseInt(editPollInterval, 10);
      const updated = await api.patch<Board>(
        `/api/v1/boards/${encodeURIComponent(selectedBoard.id)}`,
        {
          title: editTitle.trim() || undefined,
          order_mode: editOrderMode,
          poll_interval: Number.isFinite(poll) ? Math.max(5, Math.min(10080, poll)) : undefined,
        },
      );
      selectedBoard = updated;
      boards = boards.map((b) => (b.id === updated.id ? updated : b));
      if (settingsDetailsEl) settingsDetailsEl.open = false;
      toast.show('Updated board settings');
      await loadBoardsAndItems();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update board.', {
        isError: true,
      });
    } finally {
      settingsBusy = false;
    }
  }

  async function handleTogglePause(): Promise<void> {
    if (!selectedBoard || settingsBusy) return;
    settingsBusy = true;
    try {
      const ep = selectedBoard.is_active ? 'archive' : 'unarchive';
      const updated = await api.post<Board>(
        `/api/v1/boards/${encodeURIComponent(selectedBoard.id)}/${ep}`,
      );
      selectedBoard = updated;
      boards = boards.map((b) => (b.id === updated.id ? updated : b));
      if (settingsDetailsEl) settingsDetailsEl.open = false;
      toast.show(updated.is_active ? `Resumed '${updated.title}'` : `Paused '${updated.title}'`);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update board.', {
        isError: true,
      });
    } finally {
      settingsBusy = false;
    }
  }

  async function handleDeleteBoard(): Promise<void> {
    if (!selectedBoard || settingsBusy) return;
    if (!window.confirm(`Delete board '${selectedBoard.title}'?`)) return;
    settingsBusy = true;
    try {
      const id = selectedBoard.id;
      await api.delete(`/api/v1/boards/${encodeURIComponent(id)}`);
      if (settingsDetailsEl) settingsDetailsEl.open = false;
      toast.show(`Deleted '${selectedBoard.title}'`);
      const remaining = boards.filter((b) => b.id !== id);
      if (remaining[0]) {
        router.navigate(`/boards/${encodeURIComponent(remaining[0].id)}`);
      } else {
        router.navigate('/boards');
        await loadBoardsAndItems();
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to delete board.', {
        isError: true,
      });
    } finally {
      settingsBusy = false;
    }
  }

  async function handleDismiss(story: BoardItemView): Promise<void> {
    if (!selectedBoard) return;
    const bId = selectedBoard.id;
    const extId = story.external_id;
    const expectedEpoch = boardEpoch;
    if (hiddenIds.has(extId)) return;

    const isLiveStory = liveIds.has(extId);

    // Optimistically hide immediately so concurrent double-dismisses in the same tick
    // compute consecutive backfill_offset slots!
    const nextHidden = new Set(hiddenIds);
    nextHidden.add(extId);
    hiddenIds = nextHidden;

    const remainingBuffered = items.filter(
      (i) => !liveIds.has(i.external_id) && !nextHidden.has(i.external_id),
    ).length;
    const backfillOffset = isLiveStory ? null : remainingBuffered + pendingBackfills;
    const epochAtDismiss = loadMoreEpoch;
    if (!isLiveStory) {
      pendingBackfills += 1;
    }

    let backfilledExtId: string | null = null;
    try {
      const res = await api.post<BoardDismissViewResponse>(
        `/api/v1/boards/${encodeURIComponent(bId)}/items/${encodeURIComponent(extId)}/dismiss-view`,
        backfillOffset !== null ? { backfill_offset: backfillOffset } : {},
      );
      updateBoardCounts(bId, res.visible_count, res.dismissed_count);
      if (boardEpoch !== expectedEpoch || selectedBoard?.id !== bId) {
        return;
      }
      const rep = res.outcome.replacement_item;
      if (rep && !items.some((i) => i.external_id === rep.external_id)) {
        backfilledExtId = rep.external_id;
        // Insert replacement before any live rows.
        const firstLiveIdx = items.findIndex((i) => liveIds.has(i.external_id));
        if (firstLiveIdx === -1) {
          items = [...items, rep];
        } else {
          items = [...items.slice(0, firstLiveIdx), rep, ...items.slice(firstLiveIdx)];
        }
      }
    } catch (err) {
      if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
        const revert = new Set(hiddenIds);
        revert.delete(extId);
        hiddenIds = revert;
      }
      toast.show(err instanceof ApiError ? err.message : 'Unable to dismiss story.', {
        isError: true,
      });
      return;
    } finally {
      if (!isLiveStory && boardEpoch === expectedEpoch) {
        pendingBackfills = Math.max(0, pendingBackfills - 1);
      }
    }

    toast.show('Dismissed story', {
      onUndo: async () => {
        try {
          const res = await api.post<BoardUndismissViewResponse>(
            `/api/v1/boards/${encodeURIComponent(bId)}/items/${encodeURIComponent(extId)}/undismiss-view`,
          );
          updateBoardCounts(bId, res.visible_count, res.dismissed_count);
          if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
            const restored = new Set(hiddenIds);
            restored.delete(extId);
            hiddenIds = restored;
            if (backfilledExtId && loadMoreEpoch === epochAtDismiss) {
              items = items.filter((i) => i.external_id !== backfilledExtId);
            }
          }
        } catch {
          toast.show('Could not restore story.', { isError: true });
        }
      },
    });
  }

  async function handleRestore(story: BoardItemView): Promise<void> {
    if (!selectedBoard) return;
    const bId = selectedBoard.id;
    const expectedEpoch = boardEpoch;
    try {
      const res = await api.post<BoardUndismissViewResponse>(
        `/api/v1/boards/${encodeURIComponent(bId)}/items/${encodeURIComponent(story.external_id)}/undismiss-view`,
      );
      updateBoardCounts(bId, res.visible_count, res.dismissed_count);
      if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
        items = items.filter((i) => i.external_id !== story.external_id);
      }
      toast.show('Restored to board');
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to restore story.', {
        isError: true,
      });
    }
  }

  async function handleStar(story: BoardItemView): Promise<void> {
    if (!selectedBoard) return;
    const bId = selectedBoard.id;
    const extId = story.external_id;
    if (busyStoryIds.has(extId)) return;
    const expectedEpoch = boardEpoch;
    const prev = story.is_favorite;
    const next = !prev;
    busyStoryIds = new Set([...busyStoryIds, extId]);
    items = items.map((i) => (i.external_id === extId ? { ...i, is_favorite: next } : i));
    try {
      if (story.linked_entry_id) {
        await api.patch(`/api/v1/items/${encodeURIComponent(story.linked_entry_id)}`, {
          is_favorite: next,
        });
      } else {
        const res = await api.post<BoardPromoteViewResponse>(
          `/api/v1/boards/${encodeURIComponent(bId)}/items/${encodeURIComponent(extId)}/promote-view`,
          { action: 'star' },
        );
        if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
          items = items.map((i) =>
            i.external_id === extId
              ? {
                  ...(res.result.board_item ?? i),
                  linked_entry_id: res.result.entry_id,
                  is_favorite: true,
                }
              : i,
          );
        }
      }
    } catch (err) {
      if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
        items = items.map((i) => (i.external_id === extId ? { ...i, is_favorite: prev } : i));
      }
      toast.show(err instanceof ApiError ? err.message : 'Unable to update star.', { isError: true });
    } finally {
      if (boardEpoch === expectedEpoch) {
        const nextBusy = new Set(busyStoryIds);
        nextBusy.delete(extId);
        busyStoryIds = nextBusy;
      }
    }
  }

  async function handleQueue(story: BoardItemView): Promise<void> {
    if (!selectedBoard) return;
    const bId = selectedBoard.id;
    const extId = story.external_id;
    if (busyStoryIds.has(extId)) return;
    const expectedEpoch = boardEpoch;
    const prev = story.in_want_to_read;
    const next = !prev;
    busyStoryIds = new Set([...busyStoryIds, extId]);
    items = items.map((i) => (i.external_id === extId ? { ...i, in_want_to_read: next } : i));
    try {
      if (!next && story.linked_entry_id) {
        await api.delete(
          `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries/${encodeURIComponent(story.linked_entry_id)}`,
        );
        toast.show('Removed from Queue');
      } else {
        const res = await api.post<BoardPromoteViewResponse>(
          `/api/v1/boards/${encodeURIComponent(bId)}/items/${encodeURIComponent(extId)}/promote-view`,
          { action: 'queue' },
        );
        const entryId = res.result.entry_id;
        if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
          items = items.map((i) =>
            i.external_id === extId
              ? {
                  ...(res.result.board_item ?? i),
                  linked_entry_id: entryId,
                  in_want_to_read: true,
                }
              : i,
          );
        }
        toast.show('Added to Queue', {
          onUndo: async () => {
            await api.delete(
              `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries/${encodeURIComponent(entryId)}`,
            );
            if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
              items = items.map((i) =>
                i.external_id === extId ? { ...i, in_want_to_read: false } : i,
              );
            }
          },
        });
      }
    } catch (err) {
      if (boardEpoch === expectedEpoch && selectedBoard?.id === bId) {
        items = items.map((i) => (i.external_id === extId ? { ...i, in_want_to_read: prev } : i));
      }
      toast.show(err instanceof ApiError ? err.message : 'Unable to update queue.', {
        isError: true,
      });
    } finally {
      if (boardEpoch === expectedEpoch) {
        const nextBusy = new Set(busyStoryIds);
        nextBusy.delete(extId);
        busyStoryIds = nextBusy;
      }
    }
  }

  async function handleLoadMore(): Promise<void> {
    if (!selectedBoard || loadingMore) return;
    const isDismissedView = viewMode === 'dismissed';
    if (isDismissedView && !hasMoreBuffered) return;
    const bId = selectedBoard.id;
    const expectedEpoch = boardEpoch;
    loadingMore = true;
    loadMoreEpoch += 1;
    try {
      const qs = hasMoreBuffered
        ? isDismissedView
          ? buildQuery({ page: currentPage + 1, per_page: 30, dismissed_only: true })
          : buildQuery({ page: currentPage + 1, per_page: 30, include_dismissed: false })
        : buildQuery({
            live_more: true,
            live_offset: nextLiveOffset ?? items.length,
            per_page: 30,
          });
      let pageRes = await api.get<BoardItemsPage>(
        `/api/v1/boards/${encodeURIComponent(bId)}/items${qs}`,
      );
      if (boardEpoch !== expectedEpoch || selectedBoard?.id !== bId) return;
      // Fall through buffered -> live if buffered page was empty
      if (!isDismissedView && hasMoreBuffered && pageRes.items.length === 0 && pageRes.has_more_live) {
        const liveQs = buildQuery({
          live_more: true,
          live_offset: pageRes.next_live_offset ?? items.length,
          per_page: 30,
        });
        pageRes = await api.get<BoardItemsPage>(
          `/api/v1/boards/${encodeURIComponent(bId)}/items${liveQs}`,
        );
        if (boardEpoch !== expectedEpoch || selectedBoard?.id !== bId) return;
      }
      const existing = new Set(items.map((i) => i.external_id));
      const fresh = pageRes.items.filter((i) => !existing.has(i.external_id));
      items = [...items, ...fresh];
      if (pageRes.is_live_page) {
        const nextLive = new Set(liveIds);
        for (const item of fresh) nextLive.add(item.external_id);
        liveIds = nextLive;
      }
      currentPage = pageRes.page;
      hasMoreBuffered = pageRes.has_more_buffered;
      hasMoreLive = pageRes.has_more_live;
      nextLiveOffset = pageRes.next_live_offset;
    } catch (err) {
      if (boardEpoch === expectedEpoch) {
        toast.show(err instanceof ApiError ? err.message : 'Unable to load more stories.', {
          isError: true,
        });
      }
    } finally {
      if (boardEpoch === expectedEpoch) {
        loadingMore = false;
      }
    }
  }
</script>

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{/if}

{#if loading && boards.length === 0}
  <p class="empty">Loading boards…</p>
{:else if boards.length === 0}
  <section class="board-empty-onboarding" aria-label="Boards onboarding">
    <div class="album-kicker">Ephemeral Front Pages</div>
    <h2>Boards</h2>
    <p class="lede">
      Browse ranked link aggregators without polluting your permanent Feed Inbox or search index.
      Stories stay ephemeral until you Star, Queue, or Finish them.
    </p>

    <h3>Quick Add Hacker News</h3>
    <div class="board-quick-add-grid">
      {#each QUICK_HN_BOARDS as preset (preset.section)}
        <button
          type="button"
          class="board-quick-add-btn"
          disabled={addBusy}
          onclick={() => void handleQuickAdd(preset.section, preset.title)}
        >
          <span class="board-quick-add-title">+ {preset.title}</span>
          <span class="board-quick-add-desc">{preset.desc}</span>
        </button>
      {/each}
    </div>

    <details class="editorial-panel board-onboarding-custom">
      <summary>Custom Board</summary>
      <form
        class="rail-form"
        onsubmit={(e) => {
          e.preventDefault();
          void handleQuickAdd(addSection, addTitle);
        }}
      >
        <div class="form-grid">
          <div class="field">
            <label for="onboard-hn-section">Hacker News Section</label>
            <select id="onboard-hn-section" bind:value={addSection}>
              <option value="top">Top (Front Page)</option>
              <option value="best">Best</option>
              <option value="ask">Ask HN</option>
              <option value="show">Show HN</option>
              <option value="new">Newest</option>
            </select>
          </div>
          <div class="field">
            <label for="onboard-hn-title">Title (optional)</label>
            <input id="onboard-hn-title" type="text" placeholder="Hacker News" bind:value={addTitle} />
          </div>
        </div>
        <button type="submit" class="btn btn-primary btn-small" disabled={addBusy}>Add Board</button>
      </form>
    </details>
  </section>
{:else if selectedBoard}
  <div class="boards-grid">
    <aside class="inbox-sidebar boards-sidebar" aria-label="Boards navigation">
      <div class="sidebar-group">
        <div class="sidebar-heading">Boards ({boards.length})</div>
        <ul class="sidebar-list">
          {#each boards as b (b.id)}
            <li>
              <a
                href={`/boards/${encodeURIComponent(b.id)}`}
                class:is-active={b.id === selectedBoard.id}
                aria-current={b.id === selectedBoard.id ? 'page' : undefined}
              >
                <span class="sidebar-label">{b.title}</span>
                <span class="sidebar-count js-board-sidebar-count">
                  {!b.is_active ? 'Paused' : Math.max(0, b.visible_count)}
                </span>
              </a>
            </li>
          {/each}
        </ul>
      </div>

      <div class="sidebar-group">
        <div class="sidebar-heading">View</div>
        <ul class="sidebar-list">
          <li>
            <a
              href={`/boards/${encodeURIComponent(selectedBoard.id)}`}
              class:is-active={viewMode === 'active'}
            >
              <span class="sidebar-label">Active</span>
              <span class="sidebar-count js-board-active-count">{activeCount}</span>
            </a>
          </li>
          <li>
            <a
              href={`/boards/${encodeURIComponent(selectedBoard.id)}?view=dismissed`}
              class:is-active={viewMode === 'dismissed'}
            >
              <span class="sidebar-label">Dismissed</span>
              <span class="sidebar-count js-board-dismissed-count">{dismissedCount}</span>
            </a>
          </li>
        </ul>
      </div>

      <div class="sidebar-group">
        <details class="sidebar-add-board">
          <summary>+ Add Board</summary>
          <form
            class="rail-form mt-sm"
            onsubmit={(e) => {
              e.preventDefault();
              void handleQuickAdd(addSection, addTitle);
            }}
          >
            <div class="field">
              <label for="sb-hn-section">Section</label>
              <select id="sb-hn-section" bind:value={addSection}>
                <option value="top">Top</option>
                <option value="best">Best</option>
                <option value="ask">Ask HN</option>
                <option value="show">Show HN</option>
                <option value="new">Newest</option>
              </select>
            </div>
            <div class="field">
              <label for="sb-hn-title">Title (optional)</label>
              <input id="sb-hn-title" type="text" placeholder="Custom label" bind:value={addTitle} />
            </div>
            <button type="submit" class="btn btn-primary btn-small" disabled={addBusy}>Add</button>
          </form>
        </details>
      </div>
    </aside>

    <section class="boards-main" aria-label={`${selectedBoard.title} stories`}>
      <div class="boards-mobile-controls">
        <div class="boards-mobile-bar">
          {#if boards.length > 1}
            <label for="mobile-board-select" class="sr-only">Switch board</label>
            <select
              id="mobile-board-select"
              value={effectiveBoardId ?? ''}
              onchange={(e) => {
                const nextId = (e.currentTarget as HTMLSelectElement).value;
                if (nextId) router.navigate(`/boards/${encodeURIComponent(nextId)}`);
              }}
            >
              {#each boards as b (b.id)}
                <option value={b.id}>{b.title} ({Math.max(0, b.visible_count)})</option>
              {/each}
            </select>
          {/if}
          <div class="pill-bar boards-mobile-views">
            <a
              class="pill"
              class:is-active={viewMode === 'active'}
              href={`/boards/${encodeURIComponent(selectedBoard.id)}`}
            >
              Active ({activeCount})
            </a>
            <a
              class="pill"
              class:is-active={viewMode === 'dismissed'}
              href={`/boards/${encodeURIComponent(selectedBoard.id)}?view=dismissed`}
            >
              Dismissed ({dismissedCount})
            </a>
          </div>
        </div>
      </div>

      <div class="board-toolbar">
        <div class="board-toolbar-title">
          <h2>{selectedBoard.title}</h2>
          <span class="badge">{sourceBadgeLabel(selectedBoard)}</span>
          {#if selectedBoard.snapshot_fetched_at}
            <time class="text-faint board-freshness" datetime={selectedBoard.snapshot_fetched_at}>
              {formatRelativeAge(selectedBoard.snapshot_fetched_at)}
            </time>
          {/if}
        </div>

        <div class="board-toolbar-actions">
          <div class="board-toolbar-search">
            <label for="roche-board-search" class="sr-only">Filter board stories</label>
            <input
              id="roche-board-search"
              type="search"
              placeholder="Filter stories…"
              bind:value={searchQuery}
              oninput={syncSearchToUrl}
            />
          </div>

          <button
            type="button"
            class="btn btn-small btn-ghost"
            onclick={() => void handleRefreshBoard()}
          >
            <Icons name="refresh" />
            <span>Refresh</span>
          </button>

          <details bind:this={settingsDetailsEl} class="board-settings-details">
            <summary class="btn btn-small btn-ghost">Settings</summary>
            <div class="board-settings-panel">
              <form class="rail-form" onsubmit={handleSaveSettings}>
                <div class="field">
                  <label for="board-edit-title">Title</label>
                  <input id="board-edit-title" type="text" required bind:value={editTitle} />
                </div>
                <div class="field">
                  <label for="board-edit-order">Order</label>
                  <select id="board-edit-order" bind:value={editOrderMode}>
                    <option value="ranked">Ranked</option>
                    <option value="chronological">Chronological</option>
                  </select>
                </div>
                <div class="field">
                  <label for="board-edit-poll">Poll Interval (min)</label>
                  <input
                    id="board-edit-poll"
                    type="number"
                    min="5"
                    max="10080"
                    bind:value={editPollInterval}
                  />
                </div>
                <div class="flex-row">
                  <button type="submit" class="btn btn-small btn-primary" disabled={settingsBusy}>
                    Save
                  </button>
                  <button
                    type="button"
                    class="btn btn-small btn-ghost"
                    disabled={settingsBusy}
                    onclick={() => void handleTogglePause()}
                  >
                    {selectedBoard.is_active ? 'Pause' : 'Resume'}
                  </button>
                  <button
                    type="button"
                    class="btn btn-small btn-ghost btn-danger-quiet"
                    disabled={settingsBusy}
                    onclick={() => void handleDeleteBoard()}
                  >
                    Delete
                  </button>
                </div>
              </form>
            </div>
          </details>
        </div>
      </div>

      {#if visibleItems.length === 0}
        {#if viewMode === 'dismissed'}
          <p id="roche-board-dismissed-empty" class="empty">
            No dismissed stories on this board.
          </p>
        {:else}
          <p class="empty">
            {searchQuery.trim()
              ? 'No stories match that filter.'
              : 'No stories yet. Try Refresh above.'}
          </p>
        {/if}
      {:else}
        <ol id="roche-board-list" class="board-list">
          {#each visibleItems as story, idx (story.external_id)}
            {@const readerHref = `/boards/${encodeURIComponent(selectedBoard.id)}/read/${encodeURIComponent(story.external_id)}?return_to=${encodeURIComponent(router.fullPath)}`}
            {@const discHref = safeHttpHref(story.discussion_url)}
            {@const isLive = liveIds.has(story.external_id)}
            {@const storyBusy = busyStoryIds.has(story.external_id)}
            <li
              class="board-row js-kbd-row"
              class:board-seen={Boolean(story.seen_at)}
              class:is-kbd-selected={idx === selectedIdx}
              data-external-id={story.external_id}
              data-live-row={isLive ? '1' : undefined}
            >
              <span class="board-rank-col">{story.rank}</span>

              <button
                type="button"
                class="btn btn-small btn-ghost btn-icon inbox-star-btn"
                class:is-starred={story.is_favorite}
                aria-label={story.is_favorite ? `Unstar ${story.title}` : `Star ${story.title}`}
                aria-pressed={story.is_favorite}
                disabled={storyBusy}
                onclick={() => void handleStar(story)}
              >
                {story.is_favorite ? '★' : '☆'}
              </button>

              <div class="board-body">
                <div class="board-title-line">
                  <a class="board-title js-kbd-open" href={readerHref}>{story.title}</a>
                  {#if story.is_finished}
                    <span class="badge badge-finished">✓ Finished</span>
                  {/if}
                  {#if story.in_want_to_read}
                    <span class="badge badge-queued">In Queue</span>
                  {/if}
                </div>

                <div class="item-byline board-byline">
                  {#if story.domain}
                    <span class="board-meta-domain">{story.domain}</span>
                  {/if}
                  {#if story.score !== null}
                    <span class="board-meta-points">{story.score} pts</span>
                  {/if}
                  {#if discHref}
                    <a
                      class="board-comments-link js-kbd-discuss"
                      href={discHref}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {formatCommentsLabel(story.comment_count)}
                    </a>
                  {/if}
                  {#if story.author}
                    <span>by {story.author}</span>
                  {/if}
                  {#if story.published_at}
                    <span>{formatRelativeAge(story.published_at)}</span>
                  {/if}
                </div>

                {#if story.summary}
                  <p class="board-excerpt">{clampExcerpt(story.summary, 200)}</p>
                {/if}
              </div>

              <div class="inbox-controls inbox-controls-subtle">
                <button
                  type="button"
                  class="btn btn-small btn-ghost"
                  class:is-in-queue={story.in_want_to_read}
                  data-board-action={story.in_want_to_read ? 'unqueue' : 'board-queue'}
                  disabled={storyBusy}
                  onclick={() => void handleQueue(story)}
                >
                  {story.in_want_to_read ? '✓ In Queue' : '+ Queue'}
                </button>

                {#if viewMode === 'dismissed'}
                  <button
                    type="button"
                    class="btn btn-small btn-ghost"
                    data-board-action="board-restore"
                    aria-label={`Restore ${story.title}`}
                    onclick={() => void handleRestore(story)}
                  >
                    <Icons name="restore" />
                    <span>Restore</span>
                  </button>
                {:else}
                  <button
                    type="button"
                    class="btn btn-small btn-ghost btn-quiet"
                    data-board-action="board-dismiss"
                    title="Dismiss story"
                    aria-label={`Dismiss ${story.title}`}
                    onclick={() => void handleDismiss(story)}
                  >
                    <Icons name="dismiss" />
                  </button>
                {/if}
              </div>
            </li>
          {/each}
        </ol>

        {#if viewMode === 'active' || hasMoreBuffered}
          <div class="board-load-more">
            {#if hasMoreBuffered || (viewMode === 'active' && hasMoreLive)}
              <button
                type="button"
                class="btn btn-ghost js-board-load-more"
                disabled={loadingMore}
                onclick={() => void handleLoadMore()}
              >
                {loadingMore ? 'Loading…' : hasMoreBuffered ? 'Load More Stories' : 'Load More Live'}
              </button>
            {:else if viewMode === 'active'}
              <p class="board-end-note">End of board</p>
            {/if}
          </div>
        {/if}
      {/if}
    </section>
  </div>
{/if}

<style>
  .boards-grid {
    display: grid;
    grid-template-columns: 16.5rem minmax(0, 56rem);
    gap: 2.25rem;
    align-items: start;
  }

  .boards-sidebar {
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

  .sidebar-list li a:hover,
  .sidebar-list li a.is-active {
    background: var(--paper-deep);
    color: var(--accent);
  }

  .sidebar-list li a.is-active {
    font-weight: 600;
  }

  .sidebar-count {
    font-family: var(--mono);
    font-size: 0.74rem;
    color: var(--ink-faint);
  }

  .sidebar-add-board summary {
    cursor: pointer;
    font-size: 0.8rem;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--ink-soft);
  }

  .boards-mobile-controls {
    display: none;
  }

  .board-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem 1rem;
    padding-bottom: 0.7rem;
    margin-bottom: 0.4rem;
    border-bottom: 1px solid var(--rule);
    position: relative;
  }

  .board-toolbar-title {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .board-toolbar-title h2 {
    margin: 0;
  }

  .board-freshness {
    font-size: 0.8rem;
  }

  .board-toolbar-actions {
    display: flex;
    align-items: center;
    gap: 0.45rem;
    flex-wrap: wrap;
  }

  .board-toolbar-search input {
    width: 11.5rem;
    padding: 0.26rem 0.52rem;
    font-size: 0.84rem;
  }

  .board-settings-details {
    position: relative;
  }

  .board-settings-details > summary {
    list-style: none;
  }

  .board-settings-details > summary::-webkit-details-marker {
    display: none;
  }

  .board-settings-panel {
    position: absolute;
    right: 0;
    top: calc(100% + 0.35rem);
    width: 19rem;
    max-width: calc(100vw - 2.2rem);
    z-index: 40;
    background: var(--paper-deep);
    border: 1px solid var(--control-border);
    border-radius: 3px;
    padding: 0.85rem 0.95rem;
    box-shadow: 0 12px 32px var(--shadow-art);
  }

  ol.board-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  li.board-row {
    display: flex;
    align-items: flex-start;
    gap: 0.55rem;
    padding: 0.68rem 0.4rem;
    border-bottom: 1px solid var(--rule-soft);
  }

  li.board-row.board-seen .board-title {
    color: var(--ink-soft);
  }

  li.board-row.is-kbd-selected {
    background: var(--paper-deep);
    box-shadow: inset 3px 0 0 var(--accent);
  }

  .board-rank-col {
    width: 1.85rem;
    flex-shrink: 0;
    text-align: right;
    font-family: var(--mono);
    font-size: 0.82rem;
    color: var(--ink-faint);
    padding-top: 0.16rem;
    font-variant-numeric: tabular-nums;
  }

  .inbox-star-btn {
    flex-shrink: 0;
    font-size: 1rem;
    color: var(--ink-faint);
  }

  .board-body {
    flex: 1;
    min-width: 0;
  }

  .board-title-line {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: 0.35rem 0.45rem;
  }

  .board-title {
    font-family: var(--display);
    font-size: 1.03rem;
    color: var(--ink);
    text-decoration: none;
    line-height: 1.3;
  }

  .board-title:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .item-byline {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.28rem 0.5rem;
    font-size: 0.8rem;
    color: var(--ink-faint);
    margin-top: 0.22rem;
  }

  .board-meta-domain {
    color: var(--ink-soft);
  }

  .board-meta-points {
    color: var(--ink-soft);
    font-weight: 600;
    font-variant-numeric: lining-nums tabular-nums;
  }

  .item-byline a.board-comments-link {
    display: inline-flex;
    align-items: center;
    min-height: 24px;
    padding: 0.04rem 0.46rem;
    border: 1px solid var(--rule);
    border-radius: 2px;
    background-color: var(--paper-deep);
    color: var(--link);
    font-size: 0.78rem;
    font-weight: 600;
    line-height: 1.25;
    text-decoration: none;
    font-variant-numeric: lining-nums tabular-nums;
  }

  .item-byline a.board-comments-link:hover {
    border-color: var(--link);
    background-color: var(--paper);
    color: var(--link-hover);
  }

  @media (pointer: coarse) {
    .item-byline a.board-comments-link {
      min-height: 40px;
      padding: 0.25rem 0.65rem;
    }
  }

  .badge-queued {
    color: var(--accent);
    border-color: var(--accent);
    font-weight: 600;
  }

  .board-excerpt {
    margin: 0.3rem 0 0;
    font-size: 0.86rem;
    color: var(--ink-soft);
  }

  .inbox-controls {
    display: flex;
    align-items: center;
    gap: 0.32rem;
    flex-shrink: 0;
  }

  .board-load-more {
    display: flex;
    justify-content: center;
    margin: 1.35rem 0 0.5rem;
  }

  .board-end-note {
    margin: 0;
    font-size: 0.84rem;
    color: var(--ink-faint);
  }

  .board-empty-onboarding {
    max-width: 48rem;
    margin: 0.5rem auto 1.5rem;
    padding: 1.5rem 1.75rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
  }

  .album-kicker {
    font-size: 0.72rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--ink-faint);
    font-weight: 600;
  }

  .board-empty-onboarding h2 {
    margin: 0.15rem 0 0.45rem;
  }

  .board-quick-add-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(13.5rem, 1fr));
    gap: 0.65rem;
    margin-top: 0.6rem;
  }

  .board-quick-add-btn {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 0.18rem;
    width: 100%;
    min-height: 44px;
    padding: 0.72rem 0.9rem;
    text-align: left;
    font-family: var(--serif);
    background: var(--paper);
    border: 1px solid var(--control-border);
    border-radius: 2px;
    color: var(--ink);
    cursor: pointer;
  }

  .board-quick-add-btn:hover {
    border-color: var(--accent);
    background: var(--paper-input);
  }

  .board-quick-add-title {
    font-weight: 600;
    font-size: 0.94rem;
  }

  .board-quick-add-desc {
    font-size: 0.8rem;
    color: var(--ink-soft);
  }

  .board-onboarding-custom {
    margin-top: 1.25rem;
  }

  @media (max-width: 880px) {
    .boards-grid {
      grid-template-columns: 1fr;
    }
    .boards-sidebar {
      display: none;
    }
    .boards-mobile-controls {
      display: flex;
      flex-direction: column;
      gap: 0.55rem;
      margin-bottom: 0.9rem;
    }
    .boards-mobile-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .boards-mobile-views {
      margin: 0;
    }
    .board-settings-details {
      position: static;
    }
    .board-settings-panel {
      left: 0;
      right: 0;
      width: auto;
      max-width: none;
    }
  }

  @media (max-width: 600px) {
    li.board-row {
      flex-wrap: wrap;
      gap: 0.35rem 0.55rem;
    }
    li.board-row .inbox-controls {
      flex-basis: 100%;
      padding-left: 2.1rem;
    }
    .board-toolbar-search {
      width: 100%;
    }
    .board-toolbar-search input {
      width: 100%;
    }
    .board-rank-col {
      width: 1.55rem;
      font-size: 0.8rem;
    }
  }
</style>
