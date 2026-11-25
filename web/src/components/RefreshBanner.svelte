<script lang="ts">
  import { refresh } from '../lib/stores/refresh.svelte';

  interface Props {
    onReload?: () => void;
  }

  let { onReload }: Props = $props();

  function handleReload(): void {
    refresh.clearPendingReload();
    onReload?.();
  }
</script>

{#if refresh.isRefreshing}
  <div id="roche-refresh-banner" class="refresh-banner" role="status" aria-live="polite" data-active="1">
    <span class="refresh-spinner" aria-hidden="true">↻</span>
    <span>
      Refreshing feeds &amp; boards{refresh.activeCount > 1 ? ` (${refresh.activeCount} active)` : ''}…
    </span>
  </div>
{:else if refresh.pendingReloadDelta !== null}
  <div id="roche-refresh-banner" class="refresh-banner" role="status" aria-live="polite" data-active="0">
    <span>
      {refresh.pendingReloadDelta > 0
        ? `+${refresh.pendingReloadDelta} new item${refresh.pendingReloadDelta === 1 ? '' : 's'} fetched.`
        : 'Refresh complete.'}
    </span>
    <button type="button" class="btn btn-small btn-ghost" onclick={handleReload}>Reload</button>
  </div>
{/if}

<style>
  .refresh-banner {
    display: flex;
    align-items: center;
    gap: 0.55rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-left: 3px solid var(--accent);
    padding: 0.45rem 0.85rem;
    margin: 0 0 1.25rem;
    font-size: 0.86rem;
    color: var(--ink-soft);
    border-radius: 2px;
  }

  .refresh-spinner {
    display: inline-block;
    color: var(--accent);
    animation: roche-spin 1.1s linear infinite;
  }

  @keyframes roche-spin {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
</style>
