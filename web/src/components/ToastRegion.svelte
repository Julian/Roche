<script lang="ts">
  import { toast } from '../lib/stores/toast.svelte';
</script>

<div id="roche-toast-region" class="roche-toast-region">
  {#each toast.items as item (item.id)}
    <div
      class="roche-toast"
      class:is-error={item.isError}
      role={item.isError ? 'alert' : 'status'}
      onmouseenter={() => toast.pause(item.id)}
      onmouseleave={() => toast.scheduleDismiss(item.id)}
      onfocusin={() => toast.pause(item.id)}
      onfocusout={() => toast.scheduleDismiss(item.id)}
    >
      <span class="roche-toast-msg">{item.message}</span>
      {#if item.onUndo}
        <button
          type="button"
          class="btn btn-small btn-ghost roche-toast-undo"
          onclick={() => void toast.triggerUndo(item.id)}
        >
          {item.undoLabel}
        </button>
      {/if}
      <button
        type="button"
        class="roche-toast-close"
        aria-label="Dismiss notification"
        onclick={() => toast.dismiss(item.id)}
      >
        ×
      </button>
    </div>
  {/each}
</div>

<style>
  .roche-toast-region {
    position: fixed;
    right: 1.25rem;
    bottom: 1.25rem;
    z-index: 95;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    max-width: min(26rem, calc(100vw - 2rem));
    pointer-events: none;
  }

  :global(body.has-player-bar) .roche-toast-region {
    bottom: 6.25rem;
  }

  .roche-toast {
    pointer-events: auto;
    display: flex;
    align-items: center;
    gap: 0.65rem;
    background: var(--paper-deep);
    color: var(--ink);
    border: 1px solid var(--control-border);
    border-left: 3px solid var(--tier-A);
    border-radius: 3px;
    padding: 0.55rem 0.75rem;
    font-size: 0.86rem;
    box-shadow: 0 10px 28px var(--shadow-art);
  }

  .roche-toast.is-error {
    border-left-color: var(--accent);
  }

  .roche-toast-msg {
    flex: 1;
    min-width: 0;
  }

  .roche-toast-close {
    background: none;
    border: none;
    color: var(--ink-faint);
    cursor: pointer;
    font-size: 1.1rem;
    line-height: 1;
    padding: 0.1rem 0.25rem;
    min-width: 24px;
    min-height: 24px;
  }

  .roche-toast-close:hover {
    color: var(--ink);
  }
</style>
