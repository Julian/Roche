<script lang="ts">
  import { onMount } from 'svelte';
  import { api, ApiError } from '../lib/api/client';
  import {
    WANT_TO_READ_COLLECTION_ID,
    type BoardPromoteViewResponse,
    type BoardReaderRichPayload,
    type ItemDetail,
  } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    formatCommentsLabel,
    formatRelativeAge,
    safeHttpHref,
    safeLocalReturnTo,
  } from '../lib/utils/format';
  import { highlightCodeBlocks } from '../lib/utils/highlight';
  import Icons from '../components/Icons.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  const FONT_KEY = 'roche_reader_font_v1';

  interface Props {
    boardId: string;
    externalId: string;
  }

  let { boardId, externalId }: Props = $props();

  let data = $state<BoardReaderRichPayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  let fontSizeIdx = $state(1);
  let readPct = $state(0);
  let suppressAutoFinish = $state(false);
  let autoFinishTriggered = $state(false);
  let busy = $state(false);
  let newTagName = $state('');
  let tagBusy = $state(false);
  let loadController: AbortController | null = null;

  let proseEl = $state<HTMLElement | null>(null);
  let tocItems = $state<Array<{ id: string; text: string; level: number }>>([]);
  let activeTocId = $state<string | null>(null);

  let defaultBack = $derived(`/boards/${encodeURIComponent(boardId)}`);
  let returnTo = $derived(safeLocalReturnTo(router.route.query.get('return_to'), defaultBack));

  let item = $derived(data?.view.item ?? null);
  let itemHref = $derived(safeHttpHref(item?.url));
  let discHref = $derived(safeHttpHref(item?.discussion_url));
  let hasProse = $derived(Boolean(data?.content_html));
  let isFinished = $derived(Boolean(item?.is_finished));
  let isDismissed = $derived(Boolean(item?.dismissed_at));
  let isStarred = $derived(Boolean(item?.is_favorite));
  let isInQueue = $derived(Boolean(item?.in_want_to_read));
  let linkedEntryId = $derived(item?.linked_entry_id ?? data?.view.linked_entry?.id ?? null);

  let hnLabel = $derived.by(() => {
    if (!item) return 'Hacker News';
    const c = item.comment_count;
    if (c === 1) return 'Hacker News (1 comment)';
    if (typeof c === 'number' && c >= 0) return `Hacker News (${c} comments)`;
    return 'Hacker News';
  });

  let fallbackCopy = $derived.by(() => {
    const reason = data?.view.fallback_reason;
    switch (reason) {
      case 'pdf':
        return {
          kicker: 'PDF document',
          message: 'This story points to a PDF document. Open it directly or save it to your Queue for full PDF reading.',
          primaryLabel: 'Open PDF',
        };
      case 'video':
        return {
          kicker: 'Video story',
          message: 'This story links to a video hosted externally.',
          primaryLabel: 'Watch Video',
        };
      case 'paper':
        return {
          kicker: 'Research paper',
          message: 'Showing extracted abstract. Open the original paper or save to Queue to archive it.',
          primaryLabel: 'Open Paper',
        };
      case 'self_post_empty':
        return {
          kicker: 'Discussion post',
          message: 'This post has no body text — the conversation lives in the comments.',
          primaryLabel: 'Open Post',
        };
      case 'fetch_failed':
        return {
          kicker: 'Could not fetch page',
          message: 'The source page did not return readable content.',
          primaryLabel: 'Open Original',
        };
      default:
        return {
          kicker: 'External page',
          message: 'Reader view could not extract article prose for this link.',
          primaryLabel: 'Open Original',
        };
    }
  });

  async function loadBoardReader(signal?: AbortSignal): Promise<void> {
    let localController: AbortController | null = null;
    if (!signal) {
      loadController?.abort();
      localController = new AbortController();
      loadController = localController;
    }
    const activeSignal = signal ?? localController!.signal;

    loading = true;
    errorMsg = null;
    autoFinishTriggered = false;
    try {
      const payload = await api.get<BoardReaderRichPayload>(
        `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/reader-view`,
        activeSignal,
      );
      if (activeSignal.aborted) return;
      data = payload;
      router.setTitle(payload.view.item.title);
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load board story.';
    } finally {
      if (!activeSignal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    void boardId;
    void externalId;
    suppressAutoFinish = false;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadBoardReader(controller.signal);
    return () => controller.abort();
  });

  $effect(() => {
    if (!proseEl || !data?.content_html) {
      tocItems = [];
      return;
    }
    highlightCodeBlocks(proseEl);
    const headings = Array.from(proseEl.querySelectorAll<HTMLElement>('h2, h3, h4'));
    const nextToc: Array<{ id: string; text: string; level: number }> = [];
    const usedIds = new Set<string>();
    headings.forEach((h, idx) => {
      let hid = h.id.trim() || `bsec-${idx + 1}`;
      if (usedIds.has(hid)) {
        let suffix = 2;
        while (usedIds.has(`${hid}-${suffix}`)) suffix += 1;
        hid = `${hid}-${suffix}`;
      }
      h.id = hid;
      usedIds.add(hid);
      const text = h.textContent?.trim() ?? '';
      if (text) nextToc.push({ id: hid, text, level: Number(h.tagName.slice(1)) });
    });
    tocItems = nextToc.length >= 2 ? nextToc : [];

    if (nextToc.length >= 2 && typeof IntersectionObserver !== 'undefined') {
      const obs = new IntersectionObserver(
        (entries) => {
          for (const entry of entries) {
            if (entry.isIntersecting) {
              activeTocId = entry.target.id;
              break;
            }
          }
        },
        { rootMargin: '-15% 0px -70% 0px' },
      );
      for (const h of headings) obs.observe(h);
      return () => obs.disconnect();
    }
  });

  onMount(() => {
    try {
      const saved = Number.parseInt(localStorage.getItem(FONT_KEY) ?? '1', 10);
      if (Number.isFinite(saved) && saved >= 0 && saved <= 3) fontSizeIdx = saved;
    } catch {
      // Ignore
    }

    const triggerAutoFinish = async () => {
      if (
        !data ||
        !hasProse ||
        autoFinishTriggered ||
        suppressAutoFinish ||
        isFinished ||
        isDismissed
      ) {
        return;
      }
      autoFinishTriggered = true;
      try {
        const res = await api.post<BoardPromoteViewResponse>(
          `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/promote-view`,
          { action: 'finish' },
        );
        const entryId = res.result.entry_id;
        if (data) {
          data = {
            ...data,
            rank_slug: res.rank_slug ?? data.rank_slug,
            view: {
              ...data.view,
              linked_entry: res.result.item,
              item: res.result.board_item ?? {
                ...data.view.item,
                linked_entry_id: entryId,
                is_finished: true,
                in_want_to_read: false,
                dismissed_at: null,
              },
            },
          };
        }
        toast.show('Marked finished', {
          onUndo: async () => {
            try {
              await api.post(`/api/v1/items/${encodeURIComponent(entryId)}/unfinish`, {
                requeue: false,
              });
              suppressAutoFinish = true;
              if (data) {
                data = {
                  ...data,
                  view: {
                    ...data.view,
                    item: { ...data.view.item, is_finished: false },
                  },
                };
              }
              toast.show('Marked unfinished');
            } catch {
              toast.show('Could not undo Finished — please try again.', { isError: true });
            }
          },
        });
      } catch {
        autoFinishTriggered = false;
      }
    };

    const onScroll = () => {
      const doc = document.documentElement;
      const maxScroll = Math.max(0, doc.scrollHeight - window.innerHeight);
      const frac = maxScroll <= 8 ? 1 : Math.max(0, Math.min(1, window.scrollY / maxScroll));
      readPct = Math.round(frac * 100);
      if (hasProse && maxScroll > 8 && frac >= 0.98) {
        void triggerAutoFinish();
      }
    };

    const dwellTimer = setTimeout(() => {
      if (document.visibilityState !== 'visible' || !hasProse) return;
      const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      if (maxScroll <= 8) {
        void triggerAutoFinish();
      }
    }, 4_000);

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
      if (e.key === '[' && hasProse) {
        e.preventDefault();
        adjustFont(-1);
      } else if (e.key === ']' && hasProse) {
        e.preventDefault();
        adjustFont(1);
      } else if (e.key === 's') {
        e.preventDefault();
        void toggleStar();
      } else if (e.key === 'q') {
        e.preventDefault();
        void toggleQueue();
      } else if (e.key === 'e') {
        e.preventDefault();
        void toggleDismiss();
      } else if (e.key === 'd' && discHref) {
        e.preventDefault();
        window.open(discHref, '_blank', 'noopener,noreferrer');
      } else if (e.key === 'Escape') {
        router.navigate(returnTo);
      }
    };

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('keydown', onKeyDown);
    onScroll();

    return () => {
      loadController?.abort();
      clearTimeout(dwellTimer);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('keydown', onKeyDown);
    };
  });

  function adjustFont(delta: number): void {
    fontSizeIdx = Math.max(0, Math.min(3, fontSizeIdx + delta));
    try {
      localStorage.setItem(FONT_KEY, String(fontSizeIdx));
    } catch {
      // Ignore
    }
  }

  async function toggleStar(): Promise<void> {
    if (!data || !item || busy) return;
    busy = true;
    try {
      if (linkedEntryId) {
        const next = !isStarred;
        await api.patch(`/api/v1/items/${encodeURIComponent(linkedEntryId)}`, {
          is_favorite: next,
        });
        data = { ...data, view: { ...data.view, item: { ...item, is_favorite: next } } };
      } else {
        const res = await api.post<BoardPromoteViewResponse>(
          `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/promote-view`,
          { action: 'star' },
        );
        data = {
          ...data,
          view: {
            ...data.view,
            linked_entry: res.result.item,
            item: res.result.board_item ?? {
              ...item,
              linked_entry_id: res.result.entry_id,
              is_favorite: true,
            },
          },
        };
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update star.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function toggleQueue(): Promise<void> {
    if (!data || !item || busy) return;
    busy = true;
    try {
      if (isInQueue && linkedEntryId) {
        await api.delete(
          `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries/${encodeURIComponent(linkedEntryId)}`,
        );
        data = { ...data, view: { ...data.view, item: { ...item, in_want_to_read: false } } };
        toast.show('Removed from Queue');
      } else {
        const res = await api.post<BoardPromoteViewResponse>(
          `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/promote-view`,
          { action: 'queue' },
        );
        data = {
          ...data,
          view: {
            ...data.view,
            linked_entry: res.result.item,
            item: res.result.board_item ?? {
              ...item,
              linked_entry_id: res.result.entry_id,
              in_want_to_read: true,
            },
          },
        };
        toast.show('Added to Queue');
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update queue.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleFinishToggle(rankAfter = false): Promise<void> {
    if (!data || !item || busy) return;
    busy = true;
    try {
      if (isFinished && linkedEntryId) {
        if (!window.confirm('Undo Finished and remove this item from your Diary?')) return;
        await api.post(`/api/v1/items/${encodeURIComponent(linkedEntryId)}/unfinish`, {
          requeue: false,
        });
        suppressAutoFinish = true;
        data = { ...data, view: { ...data.view, item: { ...item, is_finished: false } } };
        toast.show('Marked unfinished');
      } else {
        const res = await api.post<BoardPromoteViewResponse>(
          `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/promote-view`,
          { action: 'finish' },
        );
        data = {
          ...data,
          rank_slug: res.rank_slug ?? data.rank_slug,
          view: {
            ...data.view,
            linked_entry: res.result.item,
            item: res.result.board_item ?? {
              ...item,
              linked_entry_id: res.result.entry_id,
              is_finished: true,
              in_want_to_read: false,
              dismissed_at: null,
            },
          },
        };
        if (rankAfter && res.rank_slug) {
          toast.show('Marked finished & logged to Diary');
          router.navigate(
            `/rank/${encodeURIComponent(res.rank_slug)}?focus=${encodeURIComponent(res.result.entry_id)}`,
          );
        } else {
          toast.show('Marked finished & logged to Diary');
        }
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update finished state.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }

  async function toggleDismiss(): Promise<void> {
    if (!data || !item || busy) return;
    busy = true;
    try {
      if (isDismissed) {
        await api.post(
          `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/undismiss-view`,
        );
        data = { ...data, view: { ...data.view, item: { ...item, dismissed_at: null } } };
        toast.show('Restored to board');
      } else {
        await api.post(
          `/api/v1/boards/${encodeURIComponent(boardId)}/items/${encodeURIComponent(externalId)}/dismiss-view`,
          {},
        );
        toast.show('Dismissed story');
        router.navigate(returnTo);
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update story.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleAddTag(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!data || !linkedEntryId || !newTagName.trim() || tagBusy) return;
    tagBusy = true;
    try {
      const updated = await api.post<ItemDetail>(
        `/api/v1/items/${encodeURIComponent(linkedEntryId)}/tags`,
        { name: newTagName.trim() },
      );
      data = { ...data, tags: updated.tags };
      newTagName = '';
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to add tag.', { isError: true });
    } finally {
      tagBusy = false;
    }
  }

  async function handleRemoveTag(tagId: string): Promise<void> {
    if (!data || !linkedEntryId || tagBusy) return;
    tagBusy = true;
    try {
      await api.delete(
        `/api/v1/items/${encodeURIComponent(linkedEntryId)}/tags/${encodeURIComponent(tagId)}`,
      );
      data = { ...data, tags: data.tags.filter((t) => t.id !== tagId) };
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to remove tag.', { isError: true });
    } finally {
      tagBusy = false;
    }
  }
</script>

{#if hasProse}
  <progress class="reader-progress-bar" max="100" value={readPct} aria-hidden="true"></progress>
{/if}

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{:else if loading && !data}
  <p class="empty">Loading story…</p>
{:else if data && item}
  <div class="reader-layout" class:no-toc={tocItems.length === 0}>
    <article
      class="reader-article js-reader-root"
      data-board-reader="1"
      data-item-id={linkedEntryId ?? ''}
      data-reader-size={fontSizeIdx}
    >
      <div class="reader-crumb-row">
        <a class="reader-back-link" href={returnTo}>← Back to {data.view.board.title}</a>
        <div class="flex-row flex-center">
          {#if data.reading_minutes}
            <span>{data.reading_minutes} min read</span>
          {/if}
          {#if itemHref && itemHref !== discHref}
            <a href={itemHref} target="_blank" rel="noopener noreferrer">Original ↗</a>
          {/if}
          {#if discHref}
            <a
              class="reader-discussion-link"
              href={discHref}
              target="_blank"
              rel="noopener noreferrer"
            >
              {hnLabel} ↗
            </a>
          {/if}
        </div>
      </div>

      <header class="reader-header">
        <div class="reader-title-row">
          <button
            type="button"
            class="btn btn-small btn-ghost reader-star-btn"
            class:is-starred={isStarred}
            aria-label={isStarred ? 'Remove star' : 'Star story'}
            aria-pressed={isStarred}
            disabled={busy}
            onclick={toggleStar}
          >
            {isStarred ? '★' : '☆'}
          </button>
          <h2>
            {#if itemHref}
              <a
                class="reader-title-link"
                href={itemHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                <MathTitle html={data.title_html} text={item.title} /><span class="title-ext-mark" aria-hidden="true">↗</span>
              </a>
            {:else}
              <MathTitle html={data.title_html} text={item.title} />
            {/if}
          </h2>
        </div>

        <div class="reader-meta-row">
          <div class="flex-row flex-center text-faint">
            {#if isFinished}
              <span class="badge badge-finished">✓ Finished</span>
            {:else if isDismissed}
              <span class="badge badge-dismissed">Dismissed</span>
            {/if}
            <span>{data.view.board.title}</span>
            {#if item.domain}
              <span>· {item.domain}</span>
            {/if}
            {#if item.score !== null}
              <span>· {item.score} pts</span>
            {/if}
            {#if item.author}
              <span>· by {item.author}</span>
            {/if}
            {#if item.published_at}
              <span>· {formatRelativeAge(item.published_at)}</span>
            {/if}
          </div>

          <div class="reader-quick-tools">
            <button
              type="button"
              class="btn btn-small btn-ghost"
              class:is-in-queue={isInQueue}
              disabled={busy}
              onclick={toggleQueue}
            >
              {isInQueue ? '✓ In Queue' : '+ Queue'}
            </button>
          </div>
        </div>
      </header>

      {#if data.content_html}
        <div bind:this={proseEl} class="reader-prose">
          {@html data.content_html}
        </div>
      {:else}
        <section class="board-reader-fallback" aria-label="External story summary">
          <div class="fallback-kicker">{fallbackCopy.kicker}</div>
          <p class="fallback-msg">{fallbackCopy.message}</p>
          {#if data.fallback_summary_html}
            <div class="fallback-summary reader-prose">
              {@html data.fallback_summary_html}
            </div>
          {/if}
          <div class="flex-row">
            {#if itemHref}
              <a
                class="btn btn-primary"
                href={itemHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                {fallbackCopy.primaryLabel} ↗
              </a>
            {/if}
            {#if discHref && discHref !== itemHref}
              <a
                class="btn btn-ghost"
                href={discHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                Open Hacker News Discussion ({formatCommentsLabel(item.comment_count)}) ↗
              </a>
            {/if}
            {#if data.view.fallback_reason === 'fetch_failed'}
              <button type="button" class="btn btn-ghost" onclick={() => void loadBoardReader()}>
                Retry Extraction
              </button>
            {/if}
          </div>
        </section>
      {/if}

      <section class="reader-end-card" aria-label="Finish and triage actions">
        <div class="reader-end-primary">
          <button
            type="button"
            class="btn btn-finished"
            class:btn-primary={!isFinished}
            class:btn-ghost={isFinished}
            class:is-done={isFinished}
            aria-pressed={isFinished}
            disabled={busy}
            onclick={() => void handleFinishToggle(false)}
          >
            {isFinished ? '✓ Finished' : 'Mark Finished'}
          </button>

          {#if !isFinished && data.can_rank}
            <button
              type="button"
              class="btn btn-ghost reader-end-rank-btn"
              disabled={busy}
              onclick={() => void handleFinishToggle(true)}
            >
              Finish &amp; Rank
            </button>
          {:else if isFinished && data.rank_slug && linkedEntryId}
            <a
              class="btn btn-small btn-ghost"
              href={`/rank/${encodeURIComponent(data.rank_slug)}?focus=${encodeURIComponent(linkedEntryId)}`}
            >
              Compare →
            </a>
          {/if}
        </div>

        <div class="reader-end-secondary">
          <button
            type="button"
            class="btn btn-small btn-ghost"
            disabled={busy}
            onclick={toggleDismiss}
          >
            {#if isDismissed}
              <Icons name="restore" />
              <span>Restore</span>
            {:else}
              <Icons name="dismiss" />
              <span>Dismiss</span>
            {/if}
          </button>
        </div>
      </section>

      {#if linkedEntryId}
        <section class="reader-tags-section" aria-label="Tags">
          <h3>Tags</h3>
          <div class="flex-row flex-center">
            {#each data.tags as tag (tag.id)}
              <span class="tag-chip">
                <a href={`/inbox?status=all&tag=${encodeURIComponent(tag.id)}`}>#{tag.name}</a>
                <button
                  type="button"
                  class="tag-remove-btn"
                  aria-label={`Remove tag ${tag.name}`}
                  onclick={() => void handleRemoveTag(tag.id)}
                >
                  ×
                </button>
              </span>
            {/each}
            <form class="tag-add-form" onsubmit={handleAddTag}>
              <label for="board-reader-add-tag" class="sr-only">Add tag</label>
              <input
                id="board-reader-add-tag"
                type="text"
                class="tag-add-input"
                placeholder="Add tag…"
                bind:value={newTagName}
              />
              <button type="submit" class="btn btn-small btn-ghost" disabled={tagBusy || !newTagName.trim()}>
                Add
              </button>
            </form>
          </div>
        </section>
      {/if}
    </article>

    <aside class="reader-rail" aria-label="Reader tools">
      {#if tocItems.length > 0}
        <div class="reader-rail-group">
          <div class="reader-rail-title">Contents</div>
          <ul class="reader-toc">
            {#each tocItems as heading (heading.id)}
              <li class:toc-sub={heading.level >= 4}>
                <a href={`#${heading.id}`} class:is-current={activeTocId === heading.id}>
                  {heading.text}
                </a>
              </li>
            {/each}
          </ul>
        </div>
      {/if}

      {#if hasProse}
        <div class="reader-rail-group">
          <div class="reader-rail-title">Text Size</div>
          <div class="reader-size-controls">
            <button
              type="button"
              class="btn btn-small btn-ghost js-font-dec"
              title="Decrease text size"
              disabled={fontSizeIdx <= 0}
              onclick={() => adjustFont(-1)}
            >
              A−<span class="sr-only"> decrease text size</span>
            </button>
            <button
              type="button"
              class="btn btn-small btn-ghost js-font-inc"
              title="Increase text size"
              disabled={fontSizeIdx >= 3}
              onclick={() => adjustFont(1)}
            >
              A+<span class="sr-only"> increase text size</span>
            </button>
          </div>
        </div>
      {/if}

      <div class="reader-rail-group">
        <div class="reader-rail-title">Actions</div>
        <div class="reader-rail-actions">
          <button
            type="button"
            class="rail-icon-btn"
            class:is-starred={isStarred}
            title={isStarred ? 'Unstar' : 'Star'}
            aria-label={isStarred ? 'Unstar' : 'Star'}
            onclick={toggleStar}
          >
            {isStarred ? '★' : '☆'}
          </button>
          <button
            type="button"
            class="rail-icon-btn"
            title={isInQueue ? 'Remove from Queue' : 'Add to Queue'}
            aria-label={isInQueue ? 'Remove from Queue' : 'Add to Queue'}
            onclick={toggleQueue}
          >
            <Icons name={isInQueue ? 'queue_check' : 'queue_add'} />
          </button>
          <button
            type="button"
            class="rail-icon-btn"
            title="Back to top"
            aria-label="Back to top"
            onclick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
          >
            <Icons name="top_arrow" />
          </button>
        </div>
        {#if linkedEntryId}
          <div>
            <a class="reader-rail-link" href={`/entries/${encodeURIComponent(linkedEntryId)}`}>
              History &amp; Lists →
            </a>
            {#if isFinished && data.rank_slug}
              <br />
              <a
                class="reader-rail-link"
                href={`/rank/${encodeURIComponent(data.rank_slug)}?focus=${encodeURIComponent(linkedEntryId)}`}
              >
                Compare →
              </a>
            {/if}
          </div>
        {/if}
      </div>
    </aside>
  </div>
{/if}

<style>
  progress.reader-progress-bar {
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 2.5px;
    margin: 0;
    padding: 0;
    border: 0;
    appearance: none;
    -webkit-appearance: none;
    background: transparent;
    color: var(--accent);
    z-index: 90;
    pointer-events: none;
  }

  progress.reader-progress-bar::-webkit-progress-bar {
    background: transparent;
  }

  progress.reader-progress-bar::-webkit-progress-value {
    background: var(--accent);
    transition: width 80ms linear;
  }

  progress.reader-progress-bar::-moz-progress-bar {
    background: var(--accent);
  }

  .reader-layout {
    display: grid;
    grid-template-columns: minmax(0, 44rem) 14.5rem;
    justify-content: center;
    gap: 3rem;
    align-items: start;
  }

  .reader-layout.no-toc {
    grid-template-columns: minmax(0, 44rem) 11.5rem;
  }

  article.reader-article {
    margin: 0;
    max-width: 44rem;
    min-width: 0;
  }

  .reader-crumb-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.4rem 1rem;
    margin-bottom: 0.75rem;
    font-size: 0.82rem;
    color: var(--ink-faint);
  }

  .reader-header {
    border-bottom: 1px solid var(--rule);
    padding-bottom: 1.05rem;
    margin-bottom: 1.65rem;
  }

  .reader-title-row {
    display: flex;
    align-items: flex-start;
    gap: 0.45rem;
    margin: 0.15rem 0 0.65rem;
  }

  .reader-star-btn {
    flex-shrink: 0;
    margin-left: -0.45rem;
    margin-top: 0.25rem;
    min-width: 30px;
    min-height: 30px;
    padding: 0.15rem;
    font-size: 1.25rem;
    line-height: 1;
    color: var(--ink-faint);
    border-color: transparent;
  }

  .reader-star-btn.is-starred {
    color: var(--accent);
  }

  .reader-header h2 {
    font-family: var(--display);
    font-size: 2rem;
    font-weight: 400;
    line-height: 1.18;
    margin: 0;
    flex: 1;
    min-width: 0;
  }

  .reader-title-link {
    color: inherit;
    text-decoration: none;
  }

  .reader-title-link:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .title-ext-mark {
    font-size: 0.52em;
    vertical-align: 0.28em;
    margin-left: 0.24em;
    color: var(--ink-faint);
  }

  .reader-meta-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem 1rem;
    font-size: 0.86rem;
  }

  .board-reader-fallback {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-left: 3px solid var(--accent);
    border-radius: 2px;
    padding: 1.15rem 1.25rem;
    margin: 0 0 1.5rem;
  }

  .fallback-kicker {
    font-size: 0.72rem;
    font-weight: 700;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--accent);
    margin-bottom: 0.4rem;
  }

  .fallback-msg {
    margin: 0 0 0.85rem;
    color: var(--ink-soft);
    font-size: 0.94rem;
  }

  .fallback-summary {
    margin-bottom: 0.9rem;
    font-size: 0.94rem;
  }

  .reader-end-card {
    margin: 2.5rem 0 1.75rem;
    padding: 1rem 1.25rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
  }

  .reader-end-primary,
  .reader-end-secondary {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.55rem;
  }

  .reader-tags-section {
    margin-top: 1.25rem;
  }

  .tag-chip {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    padding: 0.14rem 0.45rem;
    background: var(--badge-bg);
    border: 1px solid var(--rule);
    border-radius: 2px;
    font-size: 0.82rem;
  }

  .tag-chip a {
    text-decoration: none;
  }

  .tag-remove-btn {
    background: none;
    border: none;
    color: var(--ink-faint);
    cursor: pointer;
    padding: 0 0.1rem;
  }

  .tag-add-form {
    display: inline-flex;
    gap: 0.35rem;
  }

  .tag-add-input {
    width: 9rem;
    padding: 0.22rem 0.45rem;
    font-size: 0.82rem;
  }

  .reader-rail {
    position: sticky;
    top: 1.5rem;
    max-height: calc(100vh - 3rem);
    overflow-y: auto;
    font-size: 0.8rem;
    color: var(--ink-soft);
    padding-left: 1rem;
    border-left: 1px solid var(--rule-soft);
  }

  .reader-rail-group + .reader-rail-group {
    margin-top: 1.25rem;
  }

  .reader-rail-title {
    font-size: 0.68rem;
    font-weight: 700;
    letter-spacing: 0.11em;
    text-transform: uppercase;
    color: var(--ink-faint);
    margin: 0 0 0.45rem;
  }

  .reader-toc {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .reader-toc li {
    margin-bottom: 0.32rem;
    line-height: 1.32;
  }

  .reader-toc li.toc-sub {
    padding-left: 0.75rem;
    font-size: 0.76rem;
  }

  .reader-toc a {
    color: var(--ink-soft);
    text-decoration: none;
  }

  .reader-toc a:hover,
  .reader-toc a.is-current {
    color: var(--accent);
  }

  .reader-size-controls {
    display: inline-flex;
    gap: 0.25rem;
  }

  .reader-rail-actions {
    display: flex;
    gap: 0.35rem;
  }

  .rail-icon-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: 1px solid var(--rule);
    background: var(--paper-deep);
    color: var(--ink-soft);
    border-radius: 2px;
    text-decoration: none;
    cursor: pointer;
  }

  .rail-icon-btn.is-starred {
    color: var(--accent);
  }

  .reader-rail-link {
    display: inline-block;
    margin-top: 0.55rem;
    font-size: 0.78rem;
    color: var(--ink-soft);
    text-decoration: none;
  }

  @media (max-width: 1020px) {
    .reader-layout,
    .reader-layout.no-toc {
      grid-template-columns: minmax(0, 1fr);
    }
    .reader-rail {
      display: none;
    }
  }
</style>
