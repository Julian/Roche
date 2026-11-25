<script lang="ts">
  import { onMount } from 'svelte';
  import { api, ApiError } from '../lib/api/client';
  import type {
    ConsumptionStatus,
    DiscographyAlbum,
    EntryDetailRichPayload,
    EntryKind,
  } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import {
    ALL_SEARCHABLE_KINDS,
    consumptionStatusLabel,
    entryKindDefaultListSlug,
    entryKindLabel,
    formatAlbumTotalDuration,
    formatIsoDateInput,
    formatShortDate,
    formatTrackDuration,
    normalizeUrlInput,
    parseCsvList,
    safeHttpHref,
    stripMathDelimiters,
  } from '../lib/utils/format';
  import CreatorLinks from '../components/CreatorLinks.svelte';
  import ExternalLinks from '../components/ExternalLinks.svelte';
  import Icons from '../components/Icons.svelte';
  import MathTitle from '../components/MathTitle.svelte';

  interface Props {
    id: string;
  }

  let { id }: Props = $props();

  let data = $state<EntryDetailRichPayload | null>(null);
  let loading = $state(true);
  let errorMsg = $state<string | null>(null);
  let enrichingEntryId = $state<string | null>(null);
  let editOpen = $state(false);
  let busy = $state(false);
  let loadController: AbortController | null = null;
  let destroyed = false;

  let enriching = $derived(enrichingEntryId === id);

  // Edit form fields
  let editKind = $state<EntryKind>('book');
  let editTitle = $state('');
  let editCreators = $state('');
  let editPublishedDate = $state('');
  let editReleaseYear = $state('');
  let editSummary = $state('');
  let editUrl = $state('');
  let editDoi = $state('');
  let editArxiv = $state('');
  let editIsbn = $state('');

  // Quick log form in rail
  let logStatus = $state<ConsumptionStatus>('consumed');
  let logNotes = $state('');
  let logRankAfter = $state(true);

  // Add to list form
  let addListSlug = $state('');

  // Merge duplicate preview
  let mergeQuery = $state('');

  // Per-log edit drafts
  let logDrafts = $state<Record<string, { status: ConsumptionStatus; date: string; notes: string }>>({});

  let mergePreviewParam = $derived(router.route.query.get('merge_preview') ?? '');
  let editQueryParam = $derived(router.route.query.get('edit') === '1' || router.route.query.get('edit') === 'true');

  let defaultListSlug = $derived(data ? entryKindDefaultListSlug(data.detail.entry.kind) : null);
  let entryHref = $derived(safeHttpHref(data?.detail.entry.url));
  let totalAlbumMs = $derived(
    (data?.tracks ?? []).reduce((sum, t) => sum + (t.duration_ms ?? 0), 0),
  );
  let hasMultiDisc = $derived(
    new Set((data?.tracks ?? []).map((t) => t.disc)).size > 1,
  );
  let actionExternalLinks = $derived(
    (data?.external_links ?? []).filter(
      (l) => !['Web', 'DOI', 'arXiv'].includes(l.label),
    ),
  );

  function syncEditForm(payload: EntryDetailRichPayload): void {
    const e = payload.detail.entry;
    editKind = e.kind;
    editTitle = e.title;
    editCreators = e.creators.join(', ');
    editPublishedDate = formatIsoDateInput(e.published_at);
    editReleaseYear = e.release_year ? String(e.release_year) : '';
    editSummary = e.summary ?? '';
    editUrl = e.url ?? '';
    editDoi = e.identifiers.find((i) => i.scheme === 'doi')?.value ?? '';
    editArxiv = e.identifiers.find((i) => i.scheme === 'arxiv')?.value ?? '';
    editIsbn = e.identifiers.find((i) => i.scheme === 'isbn')?.value ?? '';
    addListSlug = payload.available_manual_lists[0]?.slug ?? payload.available_manual_lists[0]?.id ?? '';
    const drafts: typeof logDrafts = {};
    for (const log of payload.detail.log_history) {
      drafts[log.id] = {
        status: log.status,
        date: formatIsoDateInput(log.ts),
        notes: log.notes ?? '',
      };
    }
    logDrafts = drafts;
  }

  async function loadEntry(allowAutoEnrich = true, signal?: AbortSignal): Promise<void> {
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
      const qs = mergePreviewParam
        ? `?merge_preview=${encodeURIComponent(mergePreviewParam)}`
        : '';
      const payload = await api.get<EntryDetailRichPayload>(
        `/api/v1/catalog/entries/${encodeURIComponent(id)}/detail${qs}`,
        activeSignal,
      );
      if (destroyed || activeSignal.aborted) return;
      data = payload;
      syncEditForm(payload);
      router.setTitle(stripMathDelimiters(payload.detail.entry.title));

      if (allowAutoEnrich && payload.needs_enrich && enrichingEntryId !== payload.detail.entry.id) {
        void runBackgroundEnrich(payload.detail.entry.id);
      }
    } catch (err) {
      if (destroyed || (err instanceof DOMException && err.name === 'AbortError')) return;
      errorMsg = err instanceof ApiError ? err.message : 'Unable to load entry.';
    } finally {
      if (!destroyed && !activeSignal.aborted) {
        loading = false;
      }
    }
  }

  async function runBackgroundEnrich(entryId: string): Promise<void> {
    enrichingEntryId = entryId;
    try {
      await api.post(`/api/v1/catalog/entries/${encodeURIComponent(entryId)}/enrich`);
      if (!destroyed && id === entryId && !editOpen) {
        await loadEntry(false);
      }
    } catch {
      // Silent best-effort enrichment
    } finally {
      if (enrichingEntryId === entryId) {
        enrichingEntryId = null;
      }
    }
  }

  $effect(() => {
    void id;
    void mergePreviewParam;
    mergeQuery = mergePreviewParam;
    editOpen = editQueryParam;
    loadController?.abort();
    const controller = new AbortController();
    loadController = controller;
    void loadEntry(true, controller.signal);
    return () => controller.abort();
  });

  $effect(() => {
    if (logStatus !== 'consumed') {
      logRankAfter = false;
    }
  });

  onMount(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && editOpen) {
        e.preventDefault();
        editOpen = false;
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      destroyed = true;
      loadController?.abort();
      window.removeEventListener('keydown', onKeyDown);
    };
  });

  async function handleSaveEdit(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!data || busy) return;
    busy = true;
    try {
      const yr = editReleaseYear.trim() ? Number.parseInt(editReleaseYear.trim(), 10) : null;
      const cleanUrl = editUrl.trim() ? normalizeUrlInput(editUrl) : null;
      await api.patch(`/api/v1/catalog/entries/${encodeURIComponent(data.detail.entry.id)}`, {
        kind: editKind,
        title: editTitle.trim(),
        creators: parseCsvList(editCreators),
        published_date: editPublishedDate.trim() || null,
        release_year: yr && Number.isFinite(yr) ? yr : null,
        summary: editSummary.trim() || null,
        url: cleanUrl,
        doi: editDoi.trim() || null,
        arxiv_id: editArxiv.trim() || null,
        isbn: editIsbn.trim() || null,
      });
      editOpen = false;
      toast.show('Saved metadata');
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to save entry.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleQueueToggle(): Promise<void> {
    if (!data || busy) return;
    busy = true;
    try {
      const res = await api.post<{ in_queue: boolean; queue_title: string }>(
        `/api/v1/catalog/entries/${encodeURIComponent(data.detail.entry.id)}/queue-toggle`,
        {},
      );
      toast.show(res.in_queue ? `Added to ${res.queue_title}` : `Removed from ${res.queue_title}`);
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update queue.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleRefreshMetadata(): Promise<void> {
    if (!data || busy) return;
    busy = true;
    try {
      await api.post(`/api/v1/catalog/entries/${encodeURIComponent(data.detail.entry.id)}/refresh-metadata`);
      toast.show('Refreshed metadata');
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to refresh metadata.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }

  async function handleLogSubmit(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!data || busy) return;
    busy = true;
    try {
      await api.post('/api/v1/catalog/log', {
        entry_id: data.detail.entry.id,
        status: logStatus,
        notes: logNotes.trim() || null,
      });
      logNotes = '';
      toast.show('Logged to Diary');
      const slug = defaultListSlug;
      if (logStatus === 'consumed' && logRankAfter && slug) {
        router.navigate(
          `/rank/${encodeURIComponent(slug)}?focus=${encodeURIComponent(data.detail.entry.id)}`,
        );
      } else {
        await loadEntry(false);
      }
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to log activity.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleUpdateLog(e: SubmitEvent, logId: string): Promise<void> {
    e.preventDefault();
    const draft = logDrafts[logId];
    if (!draft || busy) return;
    const orig = data?.detail.log_history.find((l) => l.id === logId);
    const dateChanged = Boolean(draft.date && draft.date !== formatIsoDateInput(orig?.ts));
    busy = true;
    try {
      await api.patch(`/api/v1/catalog/diary/${encodeURIComponent(logId)}`, {
        status: draft.status,
        ts: dateChanged ? `${draft.date}T12:00:00Z` : undefined,
        notes: draft.notes.trim() || null,
      });
      toast.show('Updated diary entry');
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to update log.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleDeleteLog(logId: string): Promise<void> {
    if (!window.confirm('Delete this diary log entry?')) return;
    busy = true;
    try {
      await api.delete(`/api/v1/catalog/diary/${encodeURIComponent(logId)}`);
      toast.show('Deleted diary entry');
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to delete log.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleAddToList(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!data || !addListSlug || busy) return;
    busy = true;
    try {
      await api.post(`/api/v1/catalog/lists/${encodeURIComponent(addListSlug)}/entries`, {
        entry_id: data.detail.entry.id,
      });
      toast.show('Added to list');
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to add to list.', { isError: true });
    } finally {
      busy = false;
    }
  }

  async function handleRemoveFromList(listSlug: string): Promise<void> {
    if (!data || busy) return;
    busy = true;
    try {
      await api.delete(
        `/api/v1/catalog/lists/${encodeURIComponent(listSlug)}/entries/${encodeURIComponent(data.detail.entry.id)}`,
      );
      toast.show('Removed from list');
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to remove from list.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }

  async function handleAddExternalAlbum(album: DiscographyAlbum): Promise<void> {
    if (!data || busy) return;
    busy = true;
    try {
      const identifiers = album.apple_music_id
        ? [{ scheme: 'apple_music', value: album.apple_music_id }]
        : [];
      const created = await api.post<{ id: string; title: string }>('/api/v1/catalog/entries', {
        kind: 'album',
        title: album.title,
        creators: [data.detail.entry.title],
        release_year: album.release_year,
        thumbnail_url: album.thumbnail_url,
        identifiers,
      });
      toast.show(`Added '${created.title}' to library`);
      await loadEntry(false);
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to add album.', { isError: true });
    } finally {
      busy = false;
    }
  }

  function handleMergePreviewSubmit(e: SubmitEvent): void {
    e.preventDefault();
    const q = mergeQuery.trim();
    router.navigate(
      q
        ? `/entries/${encodeURIComponent(id)}?merge_preview=${encodeURIComponent(q)}`
        : `/entries/${encodeURIComponent(id)}`,
    );
  }

  async function handleConfirmMerge(canonicalId: string, canonicalTitle: string): Promise<void> {
    if (!data || busy) return;
    if (!window.confirm(`Merge '${data.detail.entry.title}' into '${canonicalTitle}'? This cannot be undone.`)) {
      return;
    }
    busy = true;
    try {
      await api.post(`/api/v1/catalog/entries/${encodeURIComponent(data.detail.entry.id)}/merge`, {
        canonical_id: canonicalId,
      });
      toast.show(`Merged into '${canonicalTitle}'`);
      router.navigate(`/entries/${encodeURIComponent(canonicalId)}`, { replace: true });
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Merge failed.', { isError: true });
    } finally {
      busy = false;
    }
  }

  function trackSpotifyUrl(uri: string | null): string | null {
    if (!uri) return null;
    const tid = uri.replace(/^spotify:track:/, '');
    return /^[A-Za-z0-9]{10,40}$/.test(tid) ? `https://open.spotify.com/track/${tid}` : null;
  }
</script>

{#if errorMsg}
  <div class="notice notice-error" role="alert">{errorMsg}</div>
{:else if loading && !data}
  <p class="empty">Loading entry…</p>
{:else if data}
  {@const entry = data.detail.entry}
  {@const doiVal = entry.identifiers.find((i) => i.scheme === 'doi')?.value}
  {@const arxivVal = entry.identifiers.find((i) => i.scheme === 'arxiv')?.value}
  {@const isbnVal = entry.identifiers.find((i) => i.scheme === 'isbn')?.value}
  <div class="entry-grid">
    <section class="entry-main" aria-label="Entry details">
      <div class="list-detail-crumb">
        {#if defaultListSlug}
          <a class="reader-back-link" href={`/lists/${encodeURIComponent(defaultListSlug)}`}>
            ← {entryKindLabel(entry.kind)}s
          </a>
        {:else}
          <a class="reader-back-link" href="/lists">← All Lists</a>
        {/if}
      </div>

      {#if enriching}
        <div class="notice" role="status">
          {entry.kind === 'artist'
            ? 'Fetching artist bio & discography…'
            : entry.kind === 'album'
              ? 'Fetching album tracklist…'
              : entry.kind === 'paper'
                ? 'Discovering paper metadata…'
                : 'Refreshing metadata…'}
        </div>
      {/if}

      <header class="entry-hero" class:has-art={Boolean(entry.thumbnail_url)}>
        {#if entry.thumbnail_url}
          <img
            class="entry-art"
            class:is-portrait={entry.kind === 'artist'}
            src={entry.thumbnail_url}
            alt=""
            referrerpolicy="no-referrer"
          />
        {/if}

        <div class="entry-hero-main">
          <div class="entry-kicker-row">
            <span class="badge">{entryKindLabel(entry.kind)}</span>
            <button
              type="button"
              class="btn btn-small btn-ghost"
              aria-expanded={editOpen}
              aria-label="Edit entry metadata"
              onclick={() => {
                editOpen = !editOpen;
              }}
            >
              <Icons name="pencil" />
              <span>Edit</span>
            </button>
          </div>

          <h2>
            {#if entryHref}
              <a
                class="entry-title-link"
                href={entryHref}
                target="_blank"
                rel="noopener noreferrer"
              >
                <MathTitle html={data.title_html} text={entry.title} /><span class="title-ext-mark" aria-hidden="true">↗</span>
              </a>
            {:else}
              <MathTitle html={data.title_html} text={entry.title} />
            {/if}
          </h2>

          <div class="entry-byline">
            {#if entry.creators.length > 0}
              <CreatorLinks creators={entry.creators} artistLinks={data.artist_links} />
            {/if}
            {#if entry.release_year}
              {#if entry.creators.length > 0} · {/if}{entry.release_year}
            {:else if entry.published_at}
              {#if entry.creators.length > 0} · {/if}{formatShortDate(entry.published_at)}
            {/if}
            {#if data.tracks.length > 0}
              · {data.tracks.length} tracks
              {#if formatAlbumTotalDuration(totalAlbumMs)}
                · {formatAlbumTotalDuration(totalAlbumMs)}
              {/if}
            {/if}
          </div>

          {#if data.summary_html}
            <div class="entry-summary reader-prose">{@html data.summary_html}</div>
          {/if}

          {#if doiVal || arxivVal || isbnVal || data.tags.length > 0}
            <div class="entry-facts">
              {#if doiVal}
                <a class="badge" href={`https://doi.org/${encodeURIComponent(doiVal)}`} target="_blank" rel="noopener noreferrer">
                  DOI: {doiVal}
                </a>
              {/if}
              {#if arxivVal}
                <a class="badge" href={`https://arxiv.org/abs/${encodeURIComponent(arxivVal)}`} target="_blank" rel="noopener noreferrer">
                  arXiv: {arxivVal}
                </a>
              {/if}
              {#if isbnVal}
                <span class="badge">ISBN: {isbnVal}</span>
              {/if}
              {#each data.tags as tag (tag)}
                <span class="badge">{tag}</span>
              {/each}
            </div>
          {/if}

          <div class="entry-actions">
            {#if data.has_reader_item}
              <a class="btn btn-primary btn-small" href={`/reader/${encodeURIComponent(entry.id)}`}>
                Open in Reader →
              </a>
            {/if}
            <button
              type="button"
              class="btn btn-small btn-ghost"
              class:is-in-queue={Boolean(data.want_queue_slug)}
              disabled={busy}
              onclick={handleQueueToggle}
            >
              {data.want_queue_slug ? '✓ In Queue' : '+ Add to Queue'}
            </button>
            {#if data.can_refresh}
              <button
                type="button"
                class="btn btn-small btn-ghost"
                disabled={busy}
                onclick={handleRefreshMetadata}
              >
                <Icons name="refresh" />
                <span>Refresh Metadata</span>
              </button>
            {/if}
            <ExternalLinks links={actionExternalLinks} compact />
          </div>
        </div>
      </header>

      {#if editOpen}
        <section class="edit-drawer" aria-label="Edit entry metadata">
          <h3 class="mt-0">Edit Metadata</h3>
          <form class="rail-form" onsubmit={handleSaveEdit}>
            <div class="form-grid">
              <div class="field">
                <label for="edit-title">Title</label>
                <input id="edit-title" type="text" required bind:value={editTitle} />
              </div>
              <div class="field">
                <label for="edit-kind">Kind</label>
                <select id="edit-kind" bind:value={editKind}>
                  {#each ALL_SEARCHABLE_KINDS as k (k.value)}
                    <option value={k.value}>{k.label}</option>
                  {/each}
                </select>
              </div>
            </div>
            <div class="form-grid">
              <div class="field">
                <label for="edit-creators">Creators (comma-separated)</label>
                <input id="edit-creators" type="text" bind:value={editCreators} />
              </div>
              <div class="field">
                <label for="edit-pub-date">Published Date</label>
                <input
                  id="edit-pub-date"
                  type="text"
                  placeholder="YYYY-MM-DD"
                  bind:value={editPublishedDate}
                  oninput={() => {
                    const m = editPublishedDate.match(/^(\d{4})/);
                    if (m?.[1]) editReleaseYear = m[1];
                  }}
                />
              </div>
              <div class="field">
                <label for="edit-year">Year</label>
                <input id="edit-year" type="number" min="1000" max="2100" bind:value={editReleaseYear} />
              </div>
            </div>
            <div class="field">
              <label for="edit-url">URL</label>
              <input id="edit-url" type="text" inputmode="url" bind:value={editUrl} />
            </div>
            <div class="form-grid">
              <div class="field">
                <label for="edit-doi">DOI</label>
                <input id="edit-doi" type="text" bind:value={editDoi} />
              </div>
              <div class="field">
                <label for="edit-arxiv">arXiv ID</label>
                <input id="edit-arxiv" type="text" bind:value={editArxiv} />
              </div>
              <div class="field">
                <label for="edit-isbn">ISBN</label>
                <input id="edit-isbn" type="text" bind:value={editIsbn} />
              </div>
            </div>
            <div class="field">
              <label for="edit-summary">Summary</label>
              <textarea id="edit-summary" bind:value={editSummary}></textarea>
            </div>
            <div class="flex-row">
              <button type="submit" class="btn btn-primary btn-small" disabled={busy}>
                Save Changes
              </button>
              <button
                type="button"
                class="btn btn-small btn-ghost"
                onclick={() => {
                  editOpen = false;
                }}
              >
                Cancel
              </button>
            </div>
          </form>
        </section>
      {/if}

      {#if data.tracks.length > 0}
        <section aria-label="Album tracklist">
          <h3>Tracklist</h3>
          <table class="tracklist">
            <thead>
              <tr>
                <th class="track-num" scope="col">#</th>
                <th scope="col">Title</th>
                <th class="track-dur" scope="col">
                  <Icons name="clock" size={14} />
                  <span class="sr-only">Duration</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {#each data.tracks as track, idx (`${idx}-${track.disc}-${track.number}-${track.title}`)}
                {@const prevDisc = idx > 0 ? data.tracks[idx - 1]?.disc : null}
                {#if hasMultiDisc && track.disc !== prevDisc}
                  <tr class="disc-row"><td colspan="3">Disc {track.disc}</td></tr>
                {/if}
                {@const spUrl = trackSpotifyUrl(track.spotify_uri)}
                <tr>
                  <td class="track-num">{track.number}</td>
                  <td>
                    {#if track.entry_id}
                      <a href={`/entries/${encodeURIComponent(track.entry_id)}`}>{track.title}</a>
                    {:else if spUrl}
                      <a href={spUrl} target="_blank" rel="noopener noreferrer">{track.title}</a>
                    {:else}
                      {track.title}
                    {/if}
                    {#if track.artists.length > 0}
                      <span class="text-faint ml-xs">· {track.artists.join(', ')}</span>
                    {/if}
                  </td>
                  <td class="track-dur">{formatTrackDuration(track.duration_ms)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </section>
      {/if}

      {#if data.detail.children.length > 0 || data.external_discography.length > 0}
        <section aria-label={entry.kind === 'artist' ? 'Discography' : 'Contains'}>
          <h3>{entry.kind === 'artist' ? 'Discography' : 'Contains'}</h3>
          <div class="discography-grid">
            {#each data.detail.children.slice(0, 100) as child (child.id)}
              {@const tier = data.child_tiers[child.id]}
              <article class="disco-card">
                <a class="disco-link" href={`/entries/${encodeURIComponent(child.id)}`}>
                  {#if child.thumbnail_url}
                    <img
                      class="disco-art"
                      src={child.thumbnail_url}
                      alt=""
                      loading="lazy"
                      referrerpolicy="no-referrer"
                    />
                  {:else}
                    <div class="disco-art disco-art-placeholder" aria-hidden="true">
                      {child.title.slice(0, 1)}
                    </div>
                  {/if}
                  <div class="disco-title">
                    <MathTitle html={data.child_title_html[child.id]} text={child.title} />
                  </div>
                  <div class="disco-meta">
                    <span>{child.release_year ?? entryKindLabel(child.kind)}</span>
                    {#if tier}
                      <span class="tier-pill tier-{tier}">{tier}</span>
                    {:else}
                      <span class="badge">In library</span>
                    {/if}
                  </div>
                </a>
              </article>
            {/each}

            {#each data.external_discography as album, idx (`${idx}-${album.apple_music_id ?? ''}-${album.title}-${album.release_year ?? ''}`)}
              <article class="disco-card is-external">
                {#if album.thumbnail_url}
                  <img
                    class="disco-art"
                    src={album.thumbnail_url}
                    alt=""
                    loading="lazy"
                    referrerpolicy="no-referrer"
                  />
                {:else}
                  <div class="disco-art disco-art-placeholder" aria-hidden="true">
                    {album.title.slice(0, 1)}
                  </div>
                {/if}
                <div class="disco-title">{album.title}</div>
                <div class="disco-meta">
                  <span>{album.release_year ?? 'Album'}</span>
                  <button
                    type="button"
                    class="btn btn-small btn-ghost"
                    disabled={busy}
                    onclick={() => void handleAddExternalAlbum(album)}
                  >
                    + Add
                  </button>
                </div>
              </article>
            {/each}
          </div>
        </section>
      {/if}
    </section>

    <aside class="entry-rail" aria-label="Rankings, diary, and lists">
      {#if data.appearances.length > 0}
        <section class="rail-block rail-card">
          <h3 class="mt-0">Rankings</h3>
          <ul class="rail-simple-list">
            {#each data.appearances as app (app.slug)}
              <li class="ranking-app-row">
                <div>
                  <a href={`/lists/${encodeURIComponent(app.slug)}`}>{app.title}</a>
                  <div class="text-faint ranking-sub">#{app.rank} of {app.total}</div>
                </div>
                <div class="flex-row flex-center">
                  {#if app.tier}
                    <span class="tier-pill tier-{app.tier}">{app.tier}</span>
                  {/if}
                  <a
                    class="btn btn-small btn-ghost"
                    href={`/rank/${encodeURIComponent(app.slug)}?focus=${encodeURIComponent(entry.id)}`}
                  >
                    Rank
                  </a>
                </div>
              </li>
            {/each}
          </ul>
        </section>
      {/if}

      <section class="rail-block rail-card">
        <h3 class="mt-0">Diary ({data.detail.total_log_count})</h3>
        {#if data.detail.log_history.length > 0}
          <ul class="rail-simple-list mb-md">
            {#each data.detail.log_history as log (log.id)}
              {@const draft = logDrafts[log.id]}
              <li class="log-hist-row">
                <div class="flex-row flex-center">
                  <span class="badge">{consumptionStatusLabel(log.status)}</span>
                  <span class="text-faint">{formatShortDate(log.ts)}</span>
                  <button
                    type="button"
                    class="btn btn-small btn-ghost btn-danger-quiet ml-auto"
                    aria-label="Delete diary log"
                    onclick={() => void handleDeleteLog(log.id)}
                  >
                    ×
                  </button>
                </div>
                {#if log.notes}
                  <p class="log-notes">{log.notes}</p>
                {/if}
                {#if draft}
                  <details class="log-edit-details">
                    <summary>Edit</summary>
                    <form class="rail-form mt-sm" onsubmit={(e) => void handleUpdateLog(e, log.id)}>
                      <div class="field">
                        <label for={`log-st-${log.id}`}>Status</label>
                        <select id={`log-st-${log.id}`} bind:value={draft.status}>
                          <option value="consumed">Finished</option>
                          <option value="started">Started</option>
                          <option value="abandoned">Skipped</option>
                        </select>
                      </div>
                      <div class="field">
                        <label for={`log-dt-${log.id}`}>Date</label>
                        <input id={`log-dt-${log.id}`} type="date" bind:value={draft.date} />
                      </div>
                      <div class="field">
                        <label for={`log-nt-${log.id}`}>Notes</label>
                        <textarea id={`log-nt-${log.id}`} class="textarea-compact" bind:value={draft.notes}></textarea>
                      </div>
                      <button type="submit" class="btn btn-primary btn-small" disabled={busy}>Save</button>
                    </form>
                  </details>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}

        <form class="rail-form" onsubmit={handleLogSubmit}>
          <div class="field">
            <label for="entry-log-status">Log Activity</label>
            <select id="entry-log-status" bind:value={logStatus}>
              <option value="consumed">Finished</option>
              <option value="started">Started</option>
              <option value="abandoned">Skipped</option>
            </select>
          </div>
          <div class="field">
            <label for="entry-log-notes">Notes (optional)</label>
            <textarea id="entry-log-notes" class="textarea-compact" bind:value={logNotes}></textarea>
          </div>
          <div class="field field-check">
            <input
              id="entry-log-rank"
              type="checkbox"
              bind:checked={logRankAfter}
              disabled={logStatus !== 'consumed'}
            />
            <label for="entry-log-rank">Rank immediately</label>
          </div>
          <button type="submit" class="btn btn-primary btn-small" disabled={busy}>Log</button>
        </form>
      </section>

      <section class="rail-block rail-card">
        <h3 class="mt-0">Lists</h3>
        {#if data.parent_lists.length > 0}
          <ul class="rail-simple-list mb-md">
            {#each data.parent_lists as pl (pl.id)}
              <li class="ranking-app-row">
                <a href={`/lists/${encodeURIComponent(pl.slug)}`}>{pl.title}</a>
                {#if pl.is_editable_manual}
                  <button
                    type="button"
                    class="btn btn-small btn-ghost btn-danger-quiet"
                    aria-label={`Remove from ${pl.title}`}
                    onclick={() => void handleRemoveFromList(pl.slug)}
                  >
                    ×
                  </button>
                {/if}
              </li>
            {/each}
          </ul>
        {:else}
          <p class="meta mb-sm">Not in any custom lists.</p>
        {/if}

        {#if data.available_manual_lists.length > 0}
          <form class="flex-row" onsubmit={handleAddToList}>
            <label for="entry-add-list-select" class="sr-only">Add to list</label>
            <select id="entry-add-list-select" class="flex-1" bind:value={addListSlug}>
              {#each data.available_manual_lists as col (col.id)}
                <option value={col.slug ?? col.id}>{col.title}</option>
              {/each}
            </select>
            <button type="submit" class="btn btn-small btn-ghost" disabled={busy}>Add</button>
          </form>
        {/if}
      </section>

      <section class="rail-block rail-card">
        <details class="editorial-panel rail-editorial-panel" open={Boolean(mergePreviewParam)}>
          <summary>Merge Duplicate…</summary>
          <form class="rail-form" onsubmit={handleMergePreviewSubmit}>
            <div class="field">
              <label for="merge-target-q">Canonical Entry Title or ID</label>
              <input
                id="merge-target-q"
                type="text"
                placeholder="Search target work…"
                bind:value={mergeQuery}
              />
            </div>
            <button type="submit" class="btn btn-small btn-ghost">Preview Merge</button>
          </form>

          {#if data.merge_preview_error}
            <div class="notice notice-error mt-sm" role="alert">{data.merge_preview_error}</div>
          {:else if data.merge_preview}
            {@const target = data.merge_preview}
            <div class="merge-preview-box mt-sm">
              <p class="meta mb-sm">
                Merge into <strong>{target.entry.title}</strong> ({entryKindLabel(target.entry.kind)},
                {target.total_log_count} logs)
              </p>
              <button
                type="button"
                class="btn btn-primary btn-small"
                disabled={busy}
                onclick={() => void handleConfirmMerge(target.entry.id, target.entry.title)}
              >
                Confirm Merge
              </button>
            </div>
          {/if}
        </details>
      </section>
    </aside>
  </div>
{/if}

<style>
  .entry-grid {
    display: grid;
    grid-template-columns: minmax(0, 56rem) minmax(20rem, 24.5rem);
    justify-content: space-between;
    gap: 2.25rem;
    align-items: start;
  }

  .entry-hero {
    display: grid;
    grid-template-columns: 1fr;
    gap: 1.35rem;
    margin-bottom: 1.5rem;
  }

  .entry-hero.has-art {
    grid-template-columns: 11rem minmax(0, 1fr);
  }

  .entry-art {
    width: 11rem;
    height: auto;
    max-height: 15rem;
    object-fit: cover;
    border-radius: 3px;
    border: 1px solid var(--rule);
    box-shadow: 0 6px 18px var(--shadow-art);
  }

  .entry-art.is-portrait {
    height: 11rem;
    border-radius: 50%;
  }

  .entry-kicker-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    margin-bottom: 0.35rem;
  }

  .entry-hero-main h2 {
    font-size: 1.9rem;
    margin: 0 0 0.35rem;
  }

  .entry-title-link {
    color: inherit;
    text-decoration: none;
  }

  .entry-title-link:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .title-ext-mark {
    font-size: 0.52em;
    vertical-align: 0.28em;
    margin-left: 0.24em;
    color: var(--ink-faint);
  }

  .entry-byline {
    font-size: 0.94rem;
    color: var(--ink-soft);
    margin-bottom: 0.65rem;
  }

  .entry-summary {
    font-size: 0.95rem;
    margin-bottom: 0.85rem;
  }

  .entry-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
    margin-bottom: 0.85rem;
  }

  .entry-actions {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.45rem;
  }

  .edit-drawer {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    padding: 1rem 1.15rem;
    margin-bottom: 1.5rem;
  }

  table.tracklist {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.9rem;
  }

  table.tracklist th,
  table.tracklist td {
    padding: 0.42rem 0.5rem;
    border-bottom: 1px solid var(--rule-soft);
    text-align: left;
  }

  table.tracklist th {
    font-size: 0.72rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--ink-faint);
    border-bottom-color: var(--rule);
  }

  .track-num {
    width: 2.2rem;
    font-family: var(--mono);
    font-size: 0.8rem;
    color: var(--ink-faint);
  }

  .track-dur {
    width: 3.5rem;
    text-align: right;
    font-family: var(--mono);
    font-size: 0.8rem;
    color: var(--ink-faint);
  }

  .disc-row td {
    font-size: 0.74rem;
    text-transform: uppercase;
    letter-spacing: 0.1em;
    color: var(--ink-soft);
    background: var(--paper-deep);
    font-weight: 600;
  }

  .discography-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
    gap: 0.85rem;
  }

  .disco-card {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    padding: 0.55rem;
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }

  .disco-link {
    text-decoration: none;
    color: inherit;
    display: flex;
    flex-direction: column;
    gap: 0.35rem;
  }

  .disco-art {
    width: 100%;
    aspect-ratio: 1;
    object-fit: cover;
    border-radius: 2px;
    border: 1px solid var(--rule-soft);
  }

  .disco-art-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--paper);
    color: var(--ink-faint);
    font-family: var(--display);
    font-size: 1.8rem;
  }

  .disco-title {
    font-family: var(--display);
    font-size: 0.9rem;
    line-height: 1.25;
  }

  .disco-meta {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.3rem;
    font-size: 0.76rem;
    color: var(--ink-faint);
  }

  .rail-simple-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .ranking-app-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.42rem 0;
    border-bottom: 1px solid var(--rule-soft);
    font-size: 0.88rem;
  }

  .ranking-sub {
    font-size: 0.76rem;
  }

  .log-hist-row {
    padding: 0.5rem 0;
    border-bottom: 1px solid var(--rule-soft);
    font-size: 0.84rem;
  }

  .log-notes {
    margin: 0.3rem 0 0;
    color: var(--ink-soft);
  }

  .log-edit-details summary {
    cursor: pointer;
    font-size: 0.76rem;
    color: var(--ink-faint);
    margin-top: 0.25rem;
  }

  .ml-auto {
    margin-left: auto;
  }

  .merge-preview-box {
    background: var(--paper);
    border: 1px solid var(--rule);
    padding: 0.65rem;
    border-radius: 2px;
  }

  @media (max-width: 880px) {
    .entry-grid {
      grid-template-columns: 1fr;
    }
  }

  @media (max-width: 600px) {
    .entry-hero.has-art {
      grid-template-columns: 1fr;
    }
  }
</style>
