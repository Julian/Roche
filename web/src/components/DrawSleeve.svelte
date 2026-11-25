<script lang="ts">
  import { api, ApiError } from '../lib/api/client';
  import type { CollectionGeneratorState, WebExternalLink } from '../lib/api/types';
  import { router } from '../lib/router.svelte';
  import { toast } from '../lib/stores/toast.svelte';
  import { entryKindLabel } from '../lib/utils/format';
  import CreatorLinks from './CreatorLinks.svelte';
  import Icons from './Icons.svelte';
  import MathTitle from './MathTitle.svelte';

  interface Props {
    generator: CollectionGeneratorState;
    externalLinks?: WebExternalLink[];
    tags?: string[];
    artistLinks?: Record<string, string>;
    titleHtml?: Record<string, string>;
    onUpdated?: () => void | Promise<void>;
  }

  let {
    generator,
    externalLinks = [],
    tags = [],
    artistLinks = {},
    titleHtml = {},
    onUpdated,
  }: Props = $props();

  let openDrawer = $state<'finish' | 'skip' | null>(null);
  let finishNotes = $state('');
  let skipNotes = $state('');
  let busy = $state(false);

  let listSlug = $derived(generator.collection_slug ?? generator.collection_id);
  let drawn = $derived(generator.current_entry);
  let completedCount = $derived(generator.consumed_count + generator.abandoned_count);
  let progressPct = $derived(
    generator.total_pool > 0 ? Math.min(100, Math.round((completedCount / generator.total_pool) * 100)) : 0,
  );
  let spotifyLink = $derived(externalLinks.find((l) => l.icon === 'spotify'));
  let wikiLink = $derived(externalLinks.find((l) => l.icon === 'wikipedia'));
  let otherLinks = $derived(externalLinks.filter((l) => l.icon !== 'spotify' && l.icon !== 'wikipedia'));

  async function handleFinish(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!drawn || busy) return;
    busy = true;
    try {
      const res = await api.post<{
        completed_entry: { id: string; title: string };
        generator: CollectionGeneratorState;
      }>(`/api/v1/catalog/lists/${encodeURIComponent(listSlug)}/generator/finish`, {
        notes: finishNotes.trim() || null,
      });
      finishNotes = '';
      openDrawer = null;
      toast.show(`Finished '${res.completed_entry.title}' — ready to rank`);
      router.navigate(
        `/rank/${encodeURIComponent(listSlug)}?focus=${encodeURIComponent(res.completed_entry.id)}`,
      );
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to finish draw.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }

  async function handleSkip(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    if (!drawn || busy) return;
    if (!window.confirm(`Skip '${drawn.title}' and draw the next work?`)) return;
    busy = true;
    try {
      await api.post(`/api/v1/catalog/lists/${encodeURIComponent(listSlug)}/generator/abandon`, {
        notes: skipNotes.trim() || null,
      });
      skipNotes = '';
      openDrawer = null;
      toast.show(`Skipped '${drawn.title}' & drew next work`);
      await onUpdated?.();
    } catch (err) {
      toast.show(err instanceof ApiError ? err.message : 'Unable to skip draw.', {
        isError: true,
      });
    } finally {
      busy = false;
    }
  }
</script>

{#if generator.enabled}
  {#if !drawn}
    <section class="rail-block album-sleeve album-sleeve-single">
      <div>
        <div class="album-kicker">{generator.collection_title} Completed</div>
        <h2 class="album-title">All {generator.total_pool} Works Completed</h2>
        <p class="meta">
          {generator.consumed_count} finished · {generator.abandoned_count} skipped.
          <a href={`/lists/${encodeURIComponent(listSlug)}`}>View ranked list →</a>
        </p>
      </div>
    </section>
  {:else}
    <section class="rail-block album-sleeve" aria-label={`Active draw: ${generator.collection_title}`}>
      <div class="sleeve-head">
        <div class="album-kicker">
          <a href={`/lists/${encodeURIComponent(listSlug)}`}>{generator.collection_title}</a>
        </div>
        <span class="sleeve-count">
          #{Math.min(completedCount + 1, generator.total_pool)} of {generator.total_pool}
        </span>
      </div>

      <div class="sleeve-body">
        <a
          href={`/entries/${encodeURIComponent(drawn.id)}`}
          class="sleeve-art-link"
          aria-label={drawn.title}
        >
          {#if drawn.thumbnail_url}
            <img
              class="album-cover"
              src={drawn.thumbnail_url}
              alt=""
              loading="lazy"
              referrerpolicy="no-referrer"
            />
          {:else}
            <div class="album-cover-placeholder" aria-hidden="true">
              {entryKindLabel(drawn.kind).slice(0, 1)}
            </div>
          {/if}
        </a>

        <div class="sleeve-info">
          <h3 class="album-title">
            <a href={`/entries/${encodeURIComponent(drawn.id)}`}>
              <MathTitle html={titleHtml[drawn.id]} text={drawn.title} />
            </a>
          </h3>

          {#if drawn.creators.length > 0 || drawn.release_year}
            <div class="sleeve-meta">
              {#if drawn.creators.length > 0}
                <CreatorLinks creators={drawn.creators} {artistLinks} />
              {/if}
              {#if drawn.release_year}
                {#if drawn.creators.length > 0} · {/if}
                {drawn.release_year}
              {/if}
            </div>
          {/if}

          {#if tags.length > 0}
            <div class="sleeve-tags">
              {#each tags.slice(0, 5) as tag (tag)}
                <span class="badge">{tag}</span>
              {/each}
            </div>
          {/if}

          {#if spotifyLink || wikiLink || otherLinks.length > 0}
            <div class="sleeve-links">
              {#if spotifyLink}
                <a
                  href={spotifyLink.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="btn btn-small sleeve-spotify-btn"
                >
                  <Icons name="spotify_mark" size={14} />
                  <span>Spotify</span>
                </a>
              {/if}
              {#if wikiLink}
                <a
                  href={wikiLink.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="btn btn-small btn-ghost"
                >
                  <Icons name="wiki_mark" size={14} />
                  <span>Wikipedia</span>
                </a>
              {/if}
              {#each otherLinks.slice(0, 2) as lnk (lnk.href)}
                <a
                  href={lnk.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  class="btn btn-small btn-ghost"
                >
                  <Icons name={lnk.icon} size={14} />
                  <span>{lnk.label}</span>
                </a>
              {/each}
            </div>
          {/if}
        </div>
      </div>

      <div class="sleeve-actions">
        <button
          type="button"
          class="btn btn-small btn-primary"
          aria-expanded={openDrawer === 'finish'}
          onclick={() => {
            openDrawer = openDrawer === 'finish' ? null : 'finish';
          }}
        >
          Finish &amp; Rank
        </button>
        <button
          type="button"
          class="btn btn-small btn-ghost"
          aria-expanded={openDrawer === 'skip'}
          onclick={() => {
            openDrawer = openDrawer === 'skip' ? null : 'skip';
          }}
        >
          Skip
        </button>
      </div>

      {#if openDrawer === 'finish'}
        <form class="sleeve-drawer-form" onsubmit={handleFinish}>
          <div class="field">
            <label for={`draw-finish-notes-${generator.collection_id}`}>Notes (optional)</label>
            <textarea
              id={`draw-finish-notes-${generator.collection_id}`}
              class="textarea-compact"
              bind:value={finishNotes}
              placeholder="Impressions before ranking…"
            ></textarea>
          </div>
          <div class="flex-row">
            <button type="submit" class="btn btn-small btn-primary" disabled={busy}>
              {busy ? 'Saving…' : 'Confirm Finish & Rank'}
            </button>
            <button
              type="button"
              class="btn btn-small btn-ghost"
              onclick={() => {
                openDrawer = null;
              }}
            >
              Cancel
            </button>
          </div>
        </form>
      {:else if openDrawer === 'skip'}
        <form class="sleeve-drawer-form" onsubmit={handleSkip}>
          <div class="field">
            <label for={`draw-skip-notes-${generator.collection_id}`}>Reason (optional)</label>
            <textarea
              id={`draw-skip-notes-${generator.collection_id}`}
              class="textarea-compact"
              bind:value={skipNotes}
              placeholder="Why skip this one?"
            ></textarea>
          </div>
          <div class="flex-row">
            <button type="submit" class="btn btn-small" disabled={busy}>
              {busy ? 'Skipping…' : 'Confirm Skip'}
            </button>
            <button
              type="button"
              class="btn btn-small btn-ghost"
              onclick={() => {
                openDrawer = null;
              }}
            >
              Cancel
            </button>
          </div>
        </form>
      {/if}

      <div class="sleeve-progress">
        <progress value={completedCount} max={Math.max(1, generator.total_pool)}></progress>
        <span class="text-faint">{progressPct}% · {generator.remaining_count} left</span>
      </div>
    </section>
  {/if}
{/if}

<style>
  .album-sleeve {
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    padding: 0.95rem 1.05rem;
  }

  .sleeve-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 0.5rem;
    margin-bottom: 0.65rem;
  }

  .album-kicker {
    font-size: 0.72rem;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--ink-faint);
    font-weight: 600;
  }

  .album-kicker a {
    color: inherit;
    text-decoration: none;
  }

  .album-kicker a:hover {
    color: var(--accent);
  }

  .sleeve-count {
    font-family: var(--mono);
    font-size: 0.74rem;
    color: var(--ink-faint);
  }

  .sleeve-body {
    display: grid;
    grid-template-columns: 6.25rem minmax(0, 1fr);
    gap: 0.85rem;
    align-items: start;
  }

  .sleeve-art-link {
    display: block;
    text-decoration: none;
  }

  .album-cover,
  .album-cover-placeholder {
    width: 6.25rem;
    height: 6.25rem;
    border-radius: 2px;
    border: 1px solid var(--rule);
    box-shadow: 0 4px 12px var(--shadow-art);
    display: flex;
    align-items: center;
    justify-content: center;
    object-fit: cover;
    background: var(--paper);
    color: var(--ink-faint);
    font-family: var(--display);
    font-size: 2rem;
  }

  .sleeve-info {
    min-width: 0;
  }

  .album-title {
    font-family: var(--display);
    font-size: 1.2rem;
    font-weight: 400;
    line-height: 1.2;
    text-transform: none;
    letter-spacing: 0;
    border: none;
    padding: 0;
    margin: 0 0 0.25rem;
    color: var(--ink);
  }

  .album-title a {
    color: inherit;
    text-decoration: none;
  }

  .album-title a:hover {
    color: var(--accent);
    text-decoration: underline;
  }

  .sleeve-meta {
    font-size: 0.84rem;
    color: var(--ink-soft);
    margin-bottom: 0.4rem;
  }

  .sleeve-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 0.28rem;
    margin-bottom: 0.45rem;
  }

  .sleeve-links {
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem;
  }

  .sleeve-spotify-btn {
    background: var(--paper);
    border-color: var(--control-border);
    color: var(--ink);
  }

  .sleeve-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.45rem;
    margin-top: 0.75rem;
  }

  .sleeve-drawer-form {
    margin-top: 0.65rem;
    padding-top: 0.65rem;
    border-top: 1px solid var(--rule);
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .sleeve-progress {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    margin-top: 0.75rem;
    font-size: 0.74rem;
  }

  .sleeve-progress progress {
    flex: 1;
    height: 5px;
    accent-color: var(--accent);
  }

  @media (max-width: 600px) {
    .sleeve-body {
      grid-template-columns: 5.25rem 1fr;
      gap: 0.75rem;
    }
    .album-cover,
    .album-cover-placeholder {
      width: 5.25rem;
      height: 5.25rem;
    }
  }
</style>
