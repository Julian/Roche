<script lang="ts">
  import { onMount } from 'svelte';
  import { player } from '../lib/stores/player.svelte';

  let audioEl = $state<HTMLAudioElement | null>(null);

  $effect(() => {
    player.bindAudioElement(audioEl);
    return () => {
      player.bindAudioElement(null);
    };
  });

  onMount(() => {
    const onPageHide = () => {
      if (player.hasActiveTrack) {
        player.flushProgress(true);
      }
    };
    window.addEventListener('pagehide', onPageHide);
    return () => {
      window.removeEventListener('pagehide', onPageHide);
    };
  });
</script>

<aside
  id="roche-player-bar"
  class="player-bar"
  aria-label="Audio player"
  hidden={!player.track}
>
  <div class="player-inner">
    <div class="player-meta">
      <div class="player-title" title={player.track?.title ?? ''}>
        {player.track?.title ?? ''}
      </div>
      {#if player.errorMessage}
        <div class="player-error" role="alert">{player.errorMessage}</div>
      {:else if player.track?.feed}
        <div class="player-sub">{player.track.feed}</div>
      {/if}
    </div>

    <div class="player-controls">
      <button
        type="button"
        class="btn btn-small btn-ghost"
        title="Back 15 seconds"
        onclick={() => player.seekBy(-15)}
      >
        -15s
      </button>

      <audio
        id="roche-audio"
        bind:this={audioEl}
        controls
        preload="metadata"
        onloadedmetadata={() => player.handleLoadedMetadata()}
        onplay={() => player.handlePlay()}
        onpause={() => player.handlePause()}
        ontimeupdate={() => player.handleTimeUpdate()}
        onended={() => player.handleEnded()}
        onerror={() => player.handleError()}
      ></audio>

      <button
        type="button"
        class="btn btn-small btn-ghost"
        title="Forward 30 seconds"
        onclick={() => player.seekBy(30)}
      >
        +30s
      </button>

      <button
        type="button"
        class="btn btn-small btn-ghost"
        title="Cycle playback speed"
        onclick={() => player.cycleSpeed()}
      >
        {player.rate}x
      </button>

      {#if player.track}
        <a
          class="btn btn-small btn-ghost"
          href={`/reader/${encodeURIComponent(player.track.itemId)}`}
        >
          Open
        </a>
      {/if}

      <button
        type="button"
        class="btn btn-small btn-ghost"
        aria-label="Close audio player"
        title="Close audio player"
        onclick={() => player.close()}
      >
        ×
      </button>
    </div>
  </div>
</aside>

<style>
  .player-bar {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 80;
    background: var(--paper-deep);
    border-top: 2px solid var(--rule);
    padding: 0.6rem 1.25rem;
    box-shadow: 0 -6px 24px var(--shadow-art);
  }

  .player-bar[hidden] {
    display: none;
  }

  .player-inner {
    max-width: 68rem;
    margin: 0 auto;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem 1.25rem;
    flex-wrap: wrap;
  }

  .player-meta {
    flex: 1 1 13rem;
    min-width: 0;
  }

  .player-title {
    font-family: var(--display);
    font-size: 0.94rem;
    color: var(--ink);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .player-sub {
    font-size: 0.76rem;
    color: var(--ink-faint);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .player-error {
    font-size: 0.76rem;
    color: var(--accent);
  }

  .player-controls {
    display: flex;
    align-items: center;
    gap: 0.4rem;
    flex-wrap: wrap;
  }

  .player-controls audio {
    height: 32px;
    max-width: min(22rem, 100%);
  }

  @media (max-width: 600px) {
    .player-bar {
      padding: 0.55rem 0.8rem;
    }
    .player-controls {
      width: 100%;
    }
    .player-controls audio {
      flex: 1 1 100%;
      max-width: 100%;
    }
  }
</style>
