<script lang="ts">
  import { api, ApiError } from '../lib/api/client';
  import type { EntryKind, ItemDetail, QuickLogResponse } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { LOGGABLE_KINDS, normalizeUrlInput, parseCsvList } from '../lib/utils/format';

  interface Props {
    onSaved?: () => void;
  }

  let { onSaved }: Props = $props();

  let dialogEl = $state<HTMLDialogElement | null>(null);

  // Media tab state
  let title = $state('');
  let kind = $state<EntryKind>('album');
  let action = $state<'consumed' | 'want' | 'started' | 'abandoned'>('consumed');
  let creators = $state('');
  let releaseYear = $state('');
  let identifier = $state('');
  let notes = $state('');
  let rankAfter = $state(true);

  // URL tab state
  let url = $state('');
  let urlTitle = $state('');
  let urlTags = $state('');

  let submitting = $state(false);
  let errorMsg = $state<string | null>(null);

  let canRankAfter = $derived(action === 'consumed' || action === 'want');

  $effect(() => {
    if (!canRankAfter) {
      rankAfter = false;
    }
  });

  $effect(() => {
    if (!dialogEl) return;
    if (modals.quickLogOpen) {
      errorMsg = null;
      if (!dialogEl.open) {
        dialogEl.showModal();
      }
    } else if (dialogEl.open) {
      dialogEl.close();
    }
  });

  function handleDialogClose(): void {
    if (modals.quickLogOpen) {
      modals.closeQuickLog();
    }
  }

  function handleBackdropClick(e: MouseEvent): void {
    if (e.target === dialogEl) {
      modals.closeQuickLog();
    }
  }

  async function submitMedia(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const cleanTitle = title.trim();
    if (!cleanTitle) {
      errorMsg = 'Title is required.';
      return;
    }
    submitting = true;
    errorMsg = null;
    try {
      const yearNum = releaseYear.trim() ? Number.parseInt(releaseYear.trim(), 10) : null;
      const res = await api.post<QuickLogResponse>('/api/v1/catalog/quick-log', {
        title: cleanTitle,
        kind,
        action,
        creators: parseCsvList(creators),
        release_year: yearNum && Number.isFinite(yearNum) ? yearNum : null,
        identifier: identifier.trim() || null,
        notes: notes.trim() || null,
        rank_after: canRankAfter && rankAfter,
      });

      // Reset form
      title = '';
      creators = '';
      releaseYear = '';
      identifier = '';
      notes = '';
      modals.closeQuickLog();

      if (res.rank_slug) {
        const wantQ = res.rank_want ? 'want=true&' : '';
        toast.show(res.action === 'want' ? 'Added to Queue' : 'Logged to Diary');
        router.navigate(`/rank/${encodeURIComponent(res.rank_slug)}?${wantQ}focus=${encodeURIComponent(res.entry.id)}`);
      } else {
        toast.show(
          res.action === 'want'
            ? `Added '${res.entry.title}' to ${res.queue_title ?? 'Queue'}`
            : `Logged '${res.entry.title}' to Diary`,
        );
        onSaved?.();
      }
    } catch (err) {
      errorMsg = err instanceof ApiError ? err.message : 'Unable to save entry.';
    } finally {
      submitting = false;
    }
  }

  async function submitUrl(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const normalized = normalizeUrlInput(url);
    if (!normalized) {
      errorMsg = 'URL is required.';
      return;
    }
    submitting = true;
    errorMsg = null;
    try {
      const saved = await api.post<ItemDetail>('/api/v1/items/save', {
        url: normalized,
        title: urlTitle.trim() || null,
        tags: parseCsvList(urlTags),
      });
      url = '';
      urlTitle = '';
      urlTags = '';
      modals.closeQuickLog();
      toast.show(`Saved '${saved.title}' to Queue`);
      onSaved?.();
    } catch (err) {
      errorMsg = err instanceof ApiError ? err.message : 'Unable to save URL.';
    } finally {
      submitting = false;
    }
  }
</script>

<dialog
  id="roche-quick-log-dialog"
  bind:this={dialogEl}
  class="roche-modal"
  aria-labelledby="roche-ql-title"
  onclose={handleDialogClose}
  onclick={handleBackdropClick}
>
  <div class="roche-modal-head">
    <h2 id="roche-ql-title">Quick Log &amp; Capture</h2>
    <button
      type="button"
      class="btn btn-small btn-ghost js-modal-close"
      aria-label="Close modal"
      onclick={() => modals.closeQuickLog()}
    >
      ×
    </button>
  </div>

  <div
    class="roche-modal-tabs"
    role="tablist"
    aria-label="Capture mode"
  >
    <button
      type="button"
      id="roche-ql-tab-media"
      role="tab"
      tabindex={modals.quickLogTab === 'media' ? 0 : -1}
      class="pill"
      class:is-active={modals.quickLogTab === 'media'}
      aria-selected={modals.quickLogTab === 'media'}
      aria-controls="roche-ql-panel-media"
      onclick={() => {
        errorMsg = null;
        modals.quickLogTab = 'media';
      }}
      onkeydown={(e) => {
        if (e.key === 'ArrowRight' || e.key === 'ArrowLeft' || e.key === 'End') {
          e.preventDefault();
          errorMsg = null;
          modals.quickLogTab = 'url';
          document.getElementById('roche-ql-tab-url')?.focus();
        }
      }}
    >
      Log / Queue Media
    </button>
    <button
      type="button"
      id="roche-ql-tab-url"
      role="tab"
      tabindex={modals.quickLogTab === 'url' ? 0 : -1}
      class="pill"
      class:is-active={modals.quickLogTab === 'url'}
      aria-selected={modals.quickLogTab === 'url'}
      aria-controls="roche-ql-panel-url"
      onclick={() => {
        errorMsg = null;
        modals.quickLogTab = 'url';
      }}
      onkeydown={(e) => {
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight' || e.key === 'Home') {
          e.preventDefault();
          errorMsg = null;
          modals.quickLogTab = 'media';
          document.getElementById('roche-ql-tab-media')?.focus();
        }
      }}
    >
      Save URL
    </button>
  </div>

  <div class="roche-modal-body">
    {#if errorMsg}
      <div class="notice notice-error" role="alert">{errorMsg}</div>
    {/if}

    {#if modals.quickLogTab === 'media'}
      <div
        id="roche-ql-panel-media"
        role="tabpanel"
        aria-labelledby="roche-ql-tab-media"
      >
        <form class="rail-form" onsubmit={submitMedia}>
        <div class="field">
          <label for="qlm-title">Title</label>
          <input id="qlm-title" type="text" required bind:value={title} placeholder="e.g. Kind of Blue" />
        </div>

        <div class="roche-modal-grid">
          <div class="field">
            <label for="qlm-kind">Type</label>
            <select id="qlm-kind" bind:value={kind}>
              {#each LOGGABLE_KINDS as k (k.value)}
                <option value={k.value}>{k.label}</option>
              {/each}
            </select>
          </div>
          <div class="field">
            <label for="qlm-action">Status</label>
            <select id="qlm-action" bind:value={action}>
              <option value="consumed">Finished</option>
              <option value="want">Want Queue</option>
              <option value="started">Started</option>
              <option value="abandoned">Skipped</option>
            </select>
          </div>
        </div>

        <div class="roche-modal-grid">
          <div class="field">
            <label for="qlm-creators">Creators</label>
            <input id="qlm-creators" type="text" bind:value={creators} placeholder="Miles Davis" />
          </div>
          <div class="field">
            <label for="qlm-year">Year</label>
            <input id="qlm-year" type="number" min="1000" max="2100" bind:value={releaseYear} placeholder="1959" />
          </div>
        </div>

        <div class="field">
          <label for="qlm-identifier">URL or Identifier (optional)</label>
          <input id="qlm-identifier" type="text" bind:value={identifier} placeholder="Spotify URL, IMDb ID, ISBN…" />
        </div>

        <div class="field">
          <label for="qlm-notes">Notes (optional)</label>
          <textarea id="qlm-notes" class="textarea-compact" bind:value={notes} placeholder="Thoughts or context…"></textarea>
        </div>

        <div class="field field-check">
          <input
            id="qlm-rank-after"
            type="checkbox"
            bind:checked={rankAfter}
            disabled={!canRankAfter}
          />
          <label for="qlm-rank-after">Rank immediately after saving</label>
        </div>

        <div class="roche-modal-actions">
          <button
            type="button"
            class="btn btn-ghost js-modal-close"
            onclick={() => modals.closeQuickLog()}
          >
            Cancel
          </button>
          <button type="submit" class="btn btn-primary" disabled={submitting}>
            {submitting ? 'Saving…' : 'Save'}
          </button>
        </div>
        </form>
      </div>
    {:else}
      <div
        id="roche-ql-panel-url"
        role="tabpanel"
        aria-labelledby="roche-ql-tab-url"
      >
        <form class="rail-form" onsubmit={submitUrl}>
        <div class="field">
          <label for="qlm-url">Article, Paper, or Video URL</label>
          <input
            id="qlm-url"
            type="text"
            inputmode="url"
            required
            bind:value={url}
            placeholder="https://example.org/essay"
          />
        </div>
        <div class="field">
          <label for="qlm-url-title">Title (optional)</label>
          <input id="qlm-url-title" type="text" bind:value={urlTitle} placeholder="Auto-detected if blank" />
        </div>
        <div class="field">
          <label for="qlm-url-tags">Tags (comma-separated, optional)</label>
          <input id="qlm-url-tags" type="text" bind:value={urlTags} placeholder="systems, typography" />
        </div>
        <div class="roche-modal-actions">
          <button
            type="button"
            class="btn btn-ghost js-modal-close"
            onclick={() => modals.closeQuickLog()}
          >
            Cancel
          </button>
          <button type="submit" class="btn btn-primary" disabled={submitting}>
            {submitting ? 'Saving…' : 'Save to Queue'}
          </button>
        </div>
        </form>
      </div>
    {/if}
  </div>
</dialog>
