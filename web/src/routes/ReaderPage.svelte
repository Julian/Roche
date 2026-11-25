<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import { api, ApiError, sendProgressBeacon } from '../lib/api/client';
  import {
    WANT_TO_READ_COLLECTION_ID,
    type FinishItemResponse,
    type ItemDetail,
    type ItemReaderPayload,
  } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { player } from '../lib/stores/player.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    formatDuration,
    formatShortDate,
    safeHttpHref,
    safeLocalReturnTo,
    stripMathDelimiters,
  } from '../lib/utils/format';
  import { highlightCodeBlocks } from '../lib/utils/highlight';
  import FeedFavicon from '../components/FeedFavicon.svelte';
  import Icons from '../components/Icons.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  const FONT_KEY = 'roche_reader_font_v1';

  interface Props {
    id: string;
  }

  let { id }: Props = $props();

  let data = $state<ItemReaderPayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);

  let fontSizeIdx = $state(1);
  let pdfMode = $state<'extracted' | 'pdf' | 'split' | null>(null);
  let pdfIframeLoaded = $state(false);
  let readPct = $state(0);
  let suppressAutoFinish = $state(false);
  let autoFinishTriggered = $state(false);

  let proseEl = $state<HTMLElement | null>(null);
  let videoEl = $state<HTMLVideoElement | null>(null);
  let tocItems = $state<Array<{ id: string; text: string; level: number }>>([]);
  let activeTocId = $state<string | null>(null);

  let newTagName = $state('');
  let tagBusy = $state(false);
  let triageBusy = $state(false);
  let loadController: AbortController | null = null;

  let returnTo = $derived.by(() => {
    const fallback = data?.detail.in_want_to_read ? '/queue' : '/inbox';
    return safeLocalReturnTo(router.route.query.get('return_to'), fallback);
  });

  let backLabel = $derived.by(() => {
    if (returnTo === '/') return 'Back to Home';
    if (returnTo.startsWith('/boards')) return 'Back to Boards';
    if (returnTo.startsWith('/queue')) return 'Back to Queue';
    if (returnTo.startsWith('/search')) return 'Back to Search';
    if (returnTo.startsWith('/lists/starred') || returnTo.startsWith('/starred')) return 'Back to Starred';
    if (returnTo.startsWith('/lists')) return 'Back to List';
    return 'Back to Inbox';
  });

  let isFinished = $derived(data?.triage_state === 'finished');
  let isDismissed = $derived(data?.triage_state === 'dismissed');
  let hasAudio = $derived(
    Boolean(
      data &&
        (data.detail.audio_url ||
          data.audio_media_id ||
          data.detail.content_type === 'audio' ||
          data.detail.entry_kind === 'podcast_episode'),
    ),
  );
  let itemHref = $derived(safeHttpHref(data?.detail.url));
  let isPlayingAudio = $derived(data ? player.isItemPlaying(data.detail.id) : false);
  let showProseTools = $derived(Boolean(data?.content_html) && pdfMode !== 'pdf');

  async function loadReader(viewParam?: string | null, signal?: AbortSignal): Promise<void> {
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
      const q = viewParam ? `?view=${encodeURIComponent(viewParam)}` : '';
      const payload = await api.get<ItemReaderPayload>(
        `/api/v1/items/${encodeURIComponent(id)}/reader${q}`,
        activeSignal,
      );
      if (activeSignal.aborted) return;
      data = payload;
      pdfMode = payload.pdf_mode;
      pdfIframeLoaded = payload.pdf_mode === 'pdf' || payload.pdf_mode === 'split';
      router.setTitle(stripMathDelimiters(payload.detail.title));
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load article.';
    } finally {
      if (!activeSignal.aborted) {
        loading = false;
      }
    }
  }

  $effect(() => {
    void id;
    suppressAutoFinish = false;
    const initialView = untrack(() => router.route.query.get('view'));
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadReader(initialView, controller.signal);
    return () => controller.abort();
  });

  $effect(() => {
    // Highlight code blocks & build TOC whenever prose renders.
    if (!proseEl || !data?.content_html) {
      tocItems = [];
      return;
    }
    highlightCodeBlocks(proseEl);
    const headings = Array.from(proseEl.querySelectorAll<HTMLElement>('h2, h3, h4'));
    const nextToc: Array<{ id: string; text: string; level: number }> = [];
    const usedIds = new Set<string>();
    headings.forEach((h, idx) => {
      let hid = h.id.trim() || `sec-${idx + 1}`;
      if (usedIds.has(hid)) {
        let suffix = 2;
        while (usedIds.has(`${hid}-${suffix}`)) suffix += 1;
        hid = `${hid}-${suffix}`;
      }
      h.id = hid;
      usedIds.add(hid);
      const text = h.textContent?.trim() ?? '';
      if (text) {
        nextToc.push({ id: hid, text, level: Number(h.tagName.slice(1)) });
      }
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

    const unsubAudio = player.onAudioAutoFinished((finishedId) => {
      if (data && data.detail.id === finishedId) {
        data = { ...data, triage_state: 'finished', is_consumed: true };
      }
    });

    let lastSentPct = 0;
    let shortDwellTimer: ReturnType<typeof setTimeout> | null = null;

    const canAutoTrackScroll = () =>
      Boolean(
        data &&
          (data.detail.content_type === 'article' || data.detail.content_type === 'paper') &&
          pdfMode !== 'pdf' &&
          !player.isItemPlaying(data.detail.id),
      );

    const flushProgress = (keepalive: boolean) => {
      if (!data) return;
      if (videoEl && Number.isFinite(videoEl.duration) && videoEl.duration > 0 && videoEl.currentTime > 0) {
        sendProgressBeacon(
          data.detail.id,
          Math.max(0, Math.min(1, videoEl.currentTime / videoEl.duration)),
          'reader',
          keepalive,
        );
        return;
      }
      if (!canAutoTrackScroll() || data.triage_state !== 'new') return;
      const doc = document.documentElement;
      const maxScroll = Math.max(0, doc.scrollHeight - window.innerHeight);
      if (maxScroll <= 8) return;
      const frac = Math.max(0, Math.min(1, window.scrollY / maxScroll));
      if (frac > 0.01 && Math.abs(frac - lastSentPct) >= 0.01) {
        lastSentPct = frac;
        sendProgressBeacon(data.detail.id, frac, 'reader', keepalive);
      }
    };

    const triggerScrollAutoFinish = () => {
      if (
        !data ||
        autoFinishTriggered ||
        suppressAutoFinish ||
        data.triage_state === 'finished' ||
        data.triage_state === 'dismissed'
      ) {
        return;
      }
      autoFinishTriggered = true;
      const itemId = data.detail.id;
      sendProgressBeacon(itemId, 1, 'reader', true);
      data = {
        ...data,
        triage_state: 'finished',
        is_consumed: true,
        detail: { ...data.detail, is_finished: true, read_progress: 1, in_want_to_read: false },
      };
      toast.show('Marked finished', {
        onUndo: async () => {
          try {
            const updated = await api.post<ItemDetail>(
              `/api/v1/items/${encodeURIComponent(itemId)}/unfinish`,
              { requeue: true },
            );
            suppressAutoFinish = true;
            if (data && data.detail.id === itemId) {
              data = { ...data, detail: updated, triage_state: 'new', is_consumed: false };
            }
            toast.show('Marked unfinished');
          } catch {
            toast.show('Could not undo Finished — please try again.', { isError: true });
          }
        },
      });
    };

    const onScroll = () => {
      const doc = document.documentElement;
      const maxScroll = Math.max(0, doc.scrollHeight - window.innerHeight);
      const frac = maxScroll <= 8 ? 1 : Math.max(0, Math.min(1, window.scrollY / maxScroll));
      readPct = Math.round(frac * 100);
      if (!canAutoTrackScroll() || !data) return;

      if (frac >= 0.98 && maxScroll > 8) {
        triggerScrollAutoFinish();
      } else if (frac - lastSentPct >= 0.05 && data.triage_state === 'new') {
        lastSentPct = frac;
        sendProgressBeacon(data.detail.id, frac, 'reader', false);
      }
    };

    const onPageHide = () => {
      flushProgress(true);
    };

    // Short-article 4s dwell auto-finish check
    shortDwellTimer = setTimeout(() => {
      if (document.visibilityState !== 'visible') return;
      if (!canAutoTrackScroll() || !data) return;
      const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      if (maxScroll <= 8 && (data.content_html || data.summary_html)) {
        triggerScrollAutoFinish();
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
      if (e.key === '[' && showProseTools) {
        e.preventDefault();
        adjustFont(-1);
      } else if (e.key === ']' && showProseTools) {
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
      } else if (e.key === 'd') {
        const discHref = data?.discussions.map((d) => safeHttpHref(d.url)).find(Boolean);
        if (discHref) {
          e.preventDefault();
          window.open(discHref, '_blank', 'noopener,noreferrer');
        }
      } else if (e.key === 'Escape') {
        const openDetails = document.querySelector('main details[open]');
        if (openDetails) {
          openDetails.removeAttribute('open');
        } else {
          router.navigate(returnTo);
        }
      }
    };

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('pagehide', onPageHide);
    onScroll();

    return () => {
      flushProgress(true);
      unsubAudio();
      loadController?.abort();
      if (shortDwellTimer !== null) clearTimeout(shortDwellTimer);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('pagehide', onPageHide);
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

  async function switchPdfMode(mode: 'extracted' | 'pdf' | 'split'): Promise<void> {
    if (!data) return;
    if (mode !== 'pdf' && data.pdf_unextracted) {
      const sp = new URLSearchParams(router.search);
      sp.set('view', mode);
      router.replaceSearch(sp);
      await loadReader(mode);
      return;
    }
    pdfMode = mode;
    if (mode === 'pdf' || mode === 'split') {
      pdfIframeLoaded = true;
    }
    const sp = new URLSearchParams(router.search);
    sp.set('view', mode);
    router.replaceSearch(sp);
  }

  async function toggleStar(): Promise<void> {
    if (!data) return;
    const prev = data.detail.is_favorite;
    const next = !prev;
    data = { ...data, detail: { ...data.detail, is_favorite: next } };
    try {
      await api.patch(`/api/v1/items/${encodeURIComponent(data.detail.id)}`, { is_favorite: next });
    } catch (err) {
      data = { ...data, detail: { ...data.detail, is_favorite: prev } };
      toast.show(err instanceof ApiError ? err.message : 'Unable to update star.', { isError: true });
    }
  }

  async function toggleQueue(): Promise<void> {
    if (!data) return;
    const prev = data.detail.in_want_to_read;
    const next = !prev;
    data = { ...data, detail: { ...data.detail, in_want_to_read: next } };
    try {
      if (next) {
        await api.post(`/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries`, {
          entry_id: data.detail.id,
        });
        toast.show('Added to Queue');
      } else {
        await api.delete(
          `/api/v1/catalog/lists/${encodeURIComponent(WANT_TO_READ_COLLECTION_ID)}/entries/${encodeURIComponent(data.detail.id)}`,
        );
        toast.show('Removed from Queue');
      }
    } catch (err) {
      data = { ...data, detail: { ...data.detail, in_want_to_read: prev } };
      toast.show(err instanceof ApiError ? err.message : 'Unable to update queue.', { isError: true });
    }
  }

  async function handleFinishToggle(rankAfter = false): Promise<void> {
    if (!data || triageBusy) return;
    triageBusy = true;
    try {
      if (isFinished) {
        if (!window.confirm('Undo Finished and remove this item from your Diary?')) return;
        const updated = await api.post<ItemDetail>(
          `/api/v1/items/${encodeURIComponent(data.detail.id)}/unfinish`,
          { requeue: true },
        );
        suppressAutoFinish = true;
        data = { ...data, detail: updated, triage_state: 'new', is_consumed: false };
        toast.show('Marked unfinished');
      } else {
        const res = await api.post<FinishItemResponse>(
          `/api/v1/items/${encodeURIComponent(data.detail.id)}/finish`,
          {},
        );
        data = {
          ...data,
          detail: res.item,
          triage_state: 'finished',
          is_consumed: true,
          rank_slug: res.rank_slug ?? data.rank_slug,
        };
        if (rankAfter && res.rank_slug) {
          toast.show('Marked finished & logged to Diary');
          router.navigate(
            `/rank/${encodeURIComponent(res.rank_slug)}?focus=${encodeURIComponent(data.detail.id)}`,
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
      triageBusy = false;
    }
  }

  async function toggleDismiss(): Promise<void> {
    if (!data || triageBusy) return;
    triageBusy = true;
    try {
      if (isDismissed) {
        const updated = await api.post<ItemDetail>(
          `/api/v1/items/${encodeURIComponent(data.detail.id)}/mark-new`,
        );
        suppressAutoFinish = true;
        data = { ...data, detail: updated, triage_state: 'new' };
        toast.show('Put back to Inbox');
      } else {
        await api.post(`/api/v1/items/${encodeURIComponent(data.detail.id)}/dismiss`);
        toast.show('Dismissed');
        router.navigate(returnTo);
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update item.', { isError: true });
    } finally {
      triageBusy = false;
    }
  }

  async function handleAddTag(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!data || !newTagName.trim() || tagBusy) return;
    tagBusy = true;
    try {
      const updated = await api.post<ItemDetail>(
        `/api/v1/items/${encodeURIComponent(data.detail.id)}/tags`,
        { name: newTagName.trim() },
      );
      data = { ...data, detail: updated };
      newTagName = '';
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to add tag.', { isError: true });
    } finally {
      tagBusy = false;
    }
  }

  async function handleRemoveTag(tagId: string): Promise<void> {
    if (!data || tagBusy) return;
    tagBusy = true;
    try {
      await api.delete(
        `/api/v1/items/${encodeURIComponent(data.detail.id)}/tags/${encodeURIComponent(tagId)}`,
      );
      data = {
        ...data,
        detail: { ...data.detail, tags: data.detail.tags.filter((t) => t.id !== tagId) },
      };
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to remove tag.', { isError: true });
    } finally {
      tagBusy = false;
    }
  }

  function resumeScroll(): void {
    if (!data) return;
    const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
    window.scrollTo({ top: Math.round(maxScroll * data.detail.read_progress), behavior: 'smooth' });
  }

  let lastVideoPost = 0;
  function handleVideoTimeUpdate(): void {
    if (!videoEl || !data || !Number.isFinite(videoEl.duration) || videoEl.duration <= 0) return;
    const now = Date.now();
    const frac = Math.max(0, Math.min(1, videoEl.currentTime / videoEl.duration));
    if (now - lastVideoPost >= 5_000 || frac >= 0.96) {
      lastVideoPost = now;
      sendProgressBeacon(data.detail.id, frac, 'reader', false);
    }
  }
</script>

<progress class="reader-progress-bar" max="100" value={readPct} aria-hidden="true"></progress>

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{:else if loading && !data}
  <p class="empty">Loading reader…</p>
{:else if data}
  <div
    class="reader-layout"
    class:no-toc={tocItems.length === 0}
    data-pdf-mode={pdfMode ?? undefined}
  >
    <article
      class="reader-article js-reader-root"
      data-item-id={data.detail.id}
      data-reader-size={fontSizeIdx}
    >
      <div class="reader-crumb-row">
        <a class="reader-back-link" href={returnTo}>← {backLabel}</a>
        <div class="flex-row flex-center">
          {#if data.reading_minutes}
            <span>{data.reading_minutes} min read</span>
          {/if}
          {#if data.detail.duration}
            <span>{formatDuration(data.detail.duration)}</span>
          {/if}
          {#if (data.detail.content_type === 'article' || data.detail.content_type === 'paper') && data.detail.read_progress >= 0.05 && data.detail.read_progress < 0.95}
            <button type="button" class="link-btn" onclick={resumeScroll}>
              Resume at {Math.round(data.detail.read_progress * 100)}%
            </button>
          {/if}
          {#each data.discussions as disc (disc.url)}
            {@const discHref = safeHttpHref(disc.url)}
            {#if discHref}
              <a
                class="reader-discussion-link"
                href={discHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                {disc.label} ↗
              </a>
            {/if}
          {/each}
        </div>
      </div>

      <header class="reader-header">
        <div class="reader-title-row">
          <button
            type="button"
            class="btn btn-small btn-ghost reader-star-btn"
            class:is-starred={data.detail.is_favorite}
            aria-label={data.detail.is_favorite ? 'Remove star' : 'Star item'}
            aria-pressed={data.detail.is_favorite}
            onclick={toggleStar}
          >
            {data.detail.is_favorite ? '★' : '☆'}
          </button>
          <h2>
            {#if itemHref}
              <a
                class="reader-title-link"
                href={itemHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                <MathTitle html={data.title_html} text={data.detail.title} /><span class="title-ext-mark" aria-hidden="true">↗</span>
              </a>
            {:else}
              <MathTitle html={data.title_html} text={data.detail.title} />
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
            {#if data.feed}
              {@const feedLabel = data.feed.title?.trim() || data.feed.url}
              <a
                class="feed-meta-link"
                href={`/inbox?status=all&feed=${encodeURIComponent(data.feed.id)}`}
              >
                <FeedFavicon title={feedLabel} url={data.feed.url} size="sm" />
                <span>{feedLabel}</span>
              </a>
            {/if}
            {#if data.detail.author}
              <span>{data.detail.author}</span>
            {/if}
            {#if data.detail.published_at}
              <span>{formatShortDate(data.detail.published_at)}</span>
            {:else if data.entry_release_year}
              <span>{data.entry_release_year}</span>
            {/if}
          </div>

          <div class="reader-quick-tools">
            {#if hasAudio}
              <button
                type="button"
                class="btn btn-small btn-primary"
                aria-pressed={isPlayingAudio}
                onclick={(e) => {
                  if (!data) return;
                  player.playOrToggle({
                    itemId: data.detail.id,
                    title: data.detail.title,
                    feed: data.feed?.title ?? data.detail.feed_title,
                    progress: data.detail.read_progress,
                    durationSeconds: data.detail.duration,
                    triggerEl: e.currentTarget as HTMLElement,
                  });
                }}
              >
                {isPlayingAudio ? '❚❚ Pause' : '▶ Listen'}
              </button>
            {/if}
            <button
              type="button"
              class="btn btn-small btn-ghost"
              class:is-in-queue={data.detail.in_want_to_read}
              onclick={toggleQueue}
            >
              {data.detail.in_want_to_read ? '✓ In Queue' : '+ Queue'}
            </button>
          </div>
        </div>
      </header>

      {#if data.pdf_media_id}
        <div class="reader-pdf-toolbar">
          <div class="view-mode-switch" role="group" aria-label="PDF view mode">
            {#each [
              { mode: 'extracted', label: 'Extracted' },
              { mode: 'split', label: 'Split' },
              { mode: 'pdf', label: 'PDF' },
            ] as opt (opt.mode)}
              <button
                type="button"
                class="btn btn-small btn-ghost"
                class:is-active={pdfMode === opt.mode}
                aria-pressed={pdfMode === opt.mode}
                onclick={() => void switchPdfMode(opt.mode as 'extracted' | 'pdf' | 'split')}
              >
                {opt.label}
              </button>
            {/each}
          </div>
          <a
            class="reader-pdf-tab-link"
            href={`/api/v1/media/${encodeURIComponent(data.pdf_media_id)}`}
            target="_blank"
            rel="noopener noreferrer"
            data-native-nav="1"
          >
            Open PDF ↗
          </a>
        </div>
      {/if}

      {#if data.video_media_id}
        <div class="reader-media-box">
          <video
            bind:this={videoEl}
            controls
            preload="metadata"
            src={`/api/v1/media/${encodeURIComponent(data.video_media_id)}`}
            onloadedmetadata={() => {
              if (
                videoEl &&
                data &&
                data.detail.read_progress > 0.02 &&
                data.detail.read_progress < 0.98 &&
                Number.isFinite(videoEl.duration)
              ) {
                videoEl.currentTime = videoEl.duration * data.detail.read_progress;
              }
            }}
            ontimeupdate={handleVideoTimeUpdate}
            onpause={() => {
              if (videoEl && data && Number.isFinite(videoEl.duration) && videoEl.duration > 0) {
                sendProgressBeacon(
                  data.detail.id,
                  videoEl.currentTime / videoEl.duration,
                  'reader',
                  true,
                );
              }
            }}
          >
            <track kind="captions" />
          </video>
        </div>
      {:else if data.detail.content_type === 'video'}
        <div class="reader-media-box video-hero-card">
          <p class="mb-sm">External video hosted at original source.</p>
          {#if itemHref}
            <a
              class="btn btn-primary"
              href={itemHref}
              target="_blank"
              rel="noopener noreferrer"
            >
              Watch Original ↗
            </a>
          {/if}
        </div>
      {/if}

      <div class="reader-pdf-workspace">
        {#if data.content_html}
          <div bind:this={proseEl} class="reader-prose">
            {@html data.content_html}
          </div>
        {:else if data.summary_html}
          <div class="reader-prose">
            {@html data.summary_html}
          </div>
        {:else if !data.pdf_media_id && !data.video_media_id}
          <p class="empty">No extracted text available. Open the original link above to read.</p>
        {/if}

        {#if data.pdf_media_id}
          <div class="reader-media-box reader-pdf-box">
            {#if pdfIframeLoaded}
              <iframe
                class="reader-pdf-frame"
                title={stripMathDelimiters(data.detail.title)}
                src={`/api/v1/media/${encodeURIComponent(data.pdf_media_id)}`}
              ></iframe>
            {/if}
          </div>
        {/if}
      </div>

      <section class="reader-end-card" aria-label="Finish and triage actions">
        <div class="reader-end-primary">
          <button
            type="button"
            class="btn btn-finished"
            class:btn-primary={!isFinished}
            class:btn-ghost={isFinished}
            class:is-done={isFinished}
            aria-pressed={isFinished}
            disabled={triageBusy}
            onclick={() => void handleFinishToggle(false)}
          >
            {isFinished ? '✓ Finished' : 'Mark Finished'}
          </button>

          {#if !isFinished && data.can_rank}
            <button
              type="button"
              class="btn btn-ghost reader-end-rank-btn"
              disabled={triageBusy}
              onclick={() => void handleFinishToggle(true)}
            >
              Finish &amp; Rank
            </button>
          {:else if isFinished && data.rank_slug}
            <a
              class="btn btn-small btn-ghost"
              href={`/rank/${encodeURIComponent(data.rank_slug)}?focus=${encodeURIComponent(data.detail.id)}`}
            >
              Compare →
            </a>
          {/if}
        </div>

        <div class="reader-end-secondary">
          <button
            type="button"
            class="btn btn-small btn-ghost"
            disabled={triageBusy}
            onclick={toggleDismiss}
          >
            {#if isDismissed}
              <Icons name="restore" />
              <span>Put Back to Inbox</span>
            {:else}
              <Icons name="dismiss" />
              <span>Dismiss</span>
            {/if}
          </button>
        </div>
      </section>

      <section class="reader-tags-section" aria-label="Tags">
        <h3>Tags</h3>
        <div class="flex-row flex-center">
          {#each data.detail.tags as tag (tag.id)}
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
            <label for="reader-add-tag" class="sr-only">Add tag</label>
            <input
              id="reader-add-tag"
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
    </article>

    <aside class="reader-rail" aria-label="Reader tools">
      {#if tocItems.length > 0 && pdfMode !== 'pdf'}
        <div class="reader-rail-group">
          <div class="reader-rail-title">Contents</div>
          <ul class="reader-toc">
            {#each tocItems as item (item.id)}
              <li class:toc-sub={item.level >= 4}>
                <a href={`#${item.id}`} class:is-current={activeTocId === item.id}>
                  {item.text}
                </a>
              </li>
            {/each}
          </ul>
        </div>
      {/if}

      {#if showProseTools}
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
            class:is-starred={data.detail.is_favorite}
            title={data.detail.is_favorite ? 'Unstar' : 'Star'}
            aria-label={data.detail.is_favorite ? 'Unstar' : 'Star'}
            onclick={toggleStar}
          >
            {data.detail.is_favorite ? '★' : '☆'}
          </button>
          <button
            type="button"
            class="rail-icon-btn"
            title={data.detail.in_want_to_read ? 'Remove from Queue' : 'Add to Queue'}
            aria-label={data.detail.in_want_to_read ? 'Remove from Queue' : 'Add to Queue'}
            onclick={toggleQueue}
          >
            <Icons name={data.detail.in_want_to_read ? 'queue_check' : 'queue_add'} />
          </button>
          <a
            class="rail-icon-btn"
            href={`/entries/${encodeURIComponent(data.detail.id)}?edit=1`}
            title="Edit metadata"
            aria-label="Edit metadata"
          >
            <Icons name="pencil" />
          </a>
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
        <div>
          <a class="reader-rail-link" href={`/entries/${encodeURIComponent(data.detail.id)}`}>
            History &amp; Lists →
          </a>
          {#if isFinished && data.rank_slug}
            <br />
            <a
              class="reader-rail-link"
              href={`/rank/${encodeURIComponent(data.rank_slug)}?focus=${encodeURIComponent(data.detail.id)}`}
            >
              Compare →
            </a>
          {/if}
        </div>
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

  .feed-meta-link {
    display: inline-flex;
    align-items: center;
    gap: 0.35rem;
    color: var(--ink-soft);
    text-decoration: none;
  }

  .reader-quick-tools {
    display: inline-flex;
    align-items: center;
    gap: 0.45rem;
    flex-wrap: wrap;
  }

  .reader-pdf-toolbar {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 0.55rem 1rem;
    margin: 0 0 1.25rem;
    padding-bottom: 0.7rem;
    border-bottom: 1px solid var(--rule-soft);
  }

  .view-mode-switch {
    display: inline-flex;
    gap: 0.3rem;
  }

  .view-mode-switch button.is-active {
    background: var(--ink);
    border-color: var(--ink);
    color: var(--paper);
  }

  .reader-media-box {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    padding: 0.9rem 1rem;
    margin: 0 0 1.5rem;
  }

  .reader-media-box video {
    width: 100%;
    max-height: 28rem;
    background: #000;
    display: block;
  }

  .reader-pdf-box {
    padding: 0.65rem 0.75rem;
  }

  .reader-pdf-frame {
    width: 100%;
    height: 78vh;
    min-height: 36rem;
    border: 1px solid var(--rule);
    border-radius: 2px;
    background: var(--paper);
    display: block;
  }

  .reader-layout[data-pdf-mode="extracted"] .reader-pdf-box {
    display: none;
  }

  .reader-layout[data-pdf-mode="pdf"] {
    grid-template-columns: minmax(0, 56rem) 11.5rem;
  }

  .reader-layout[data-pdf-mode="pdf"] article.reader-article {
    max-width: 56rem;
  }

  .reader-layout[data-pdf-mode="pdf"] :global(.reader-prose) {
    display: none;
  }

  .reader-layout[data-pdf-mode="split"] {
    grid-template-columns: minmax(0, 1fr) 12rem;
    gap: 1.75rem;
  }

  .reader-layout[data-pdf-mode="split"] article.reader-article {
    max-width: none;
  }

  .reader-layout[data-pdf-mode="split"] .reader-pdf-workspace {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    gap: 1.5rem;
    align-items: start;
  }

  .reader-layout[data-pdf-mode="split"] .reader-pdf-box {
    position: sticky;
    top: 1rem;
    margin: 0;
  }

  .reader-layout[data-pdf-mode="split"] .reader-pdf-frame {
    height: calc(100vh - 5.5rem);
    min-height: 32rem;
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

  .reader-toc a.is-current {
    font-weight: 600;
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

  .reader-rail-link:hover {
    color: var(--link);
    text-decoration: underline;
  }

  @media (max-width: 1120px) {
    .reader-layout[data-pdf-mode="split"] .reader-pdf-workspace {
      grid-template-columns: 1fr;
    }
    .reader-layout[data-pdf-mode="split"] .reader-pdf-box {
      position: static;
      margin-bottom: 1.5rem;
    }
  }

  @media (max-width: 1020px) {
    .reader-layout,
    .reader-layout.no-toc,
    .reader-layout[data-pdf-mode="pdf"],
    .reader-layout[data-pdf-mode="split"] {
      grid-template-columns: minmax(0, 1fr);
    }
    .reader-rail {
      display: none;
    }
  }
</style>
