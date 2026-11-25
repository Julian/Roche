<script lang="ts">
  import { modals } from '../lib/stores/modals.svelte';

  let dialogEl = $state<HTMLDialogElement | null>(null);

  $effect(() => {
    if (!dialogEl) return;
    if (modals.shortcutsOpen) {
      if (!dialogEl.open) dialogEl.showModal();
    } else if (dialogEl.open) {
      dialogEl.close();
    }
  });

  function handleDialogClose(): void {
    if (modals.shortcutsOpen) modals.closeShortcuts();
  }

  function handleBackdropClick(e: MouseEvent): void {
    if (e.target === dialogEl) modals.closeShortcuts();
  }
</script>

<dialog
  id="roche-shortcuts-dialog"
  bind:this={dialogEl}
  class="roche-modal"
  aria-labelledby="roche-shortcuts-title"
  onclose={handleDialogClose}
  onclick={handleBackdropClick}
>
  <div class="roche-modal-head">
    <h2 id="roche-shortcuts-title">Keyboard Shortcuts</h2>
    <button
      type="button"
      class="btn btn-small btn-ghost js-modal-close"
      aria-label="Close shortcuts"
      onclick={() => modals.closeShortcuts()}
    >
      ×
    </button>
  </div>
  <div class="roche-modal-body">
    <div class="shortcuts-grid">
      <section>
        <h3>Global</h3>
        <dl class="shortcuts-dl">
          <div><dt><kbd>/</kbd></dt><dd>Focus search</dd></div>
          <div><dt><kbd>c</kbd></dt><dd>Quick Log / Save URL</dd></div>
          <div><dt><kbd>?</kbd></dt><dd>Toggle this help</dd></div>
          <div><dt><kbd>Esc</kbd></dt><dd>Close modal or go back</dd></div>
        </dl>
      </section>

      <section>
        <h3>Inbox &amp; Boards</h3>
        <dl class="shortcuts-dl">
          <div><dt><kbd>j</kbd> / <kbd>k</kbd></dt><dd>Next / previous item</dd></div>
          <div><dt><kbd>o</kbd> / <kbd>Enter</kbd></dt><dd>Open item</dd></div>
          <div><dt><kbd>d</kbd></dt><dd>Open discussion</dd></div>
          <div><dt><kbd>e</kbd></dt><dd>Dismiss / restore</dd></div>
          <div><dt><kbd>s</kbd></dt><dd>Star / unstar</dd></div>
          <div><dt><kbd>q</kbd></dt><dd>Toggle Queue</dd></div>
        </dl>
      </section>

      <section>
        <h3>Reader</h3>
        <dl class="shortcuts-dl">
          <div><dt><kbd>[</kbd> / <kbd>]</kbd></dt><dd>Smaller / larger text</dd></div>
          <div><dt><kbd>s</kbd> / <kbd>q</kbd> / <kbd>e</kbd></dt><dd>Star / Queue / Dismiss</dd></div>
          <div><dt><kbd>d</kbd></dt><dd>Open discussion</dd></div>
          <div><dt><kbd>Esc</kbd></dt><dd>Back to stream</dd></div>
        </dl>
      </section>

      <section>
        <h3>Ranking Board</h3>
        <dl class="shortcuts-dl">
          <div><dt><kbd>j</kbd> / <kbd>k</kbd></dt><dd>Select card</dd></div>
          <div><dt><kbd>J</kbd> / <kbd>K</kbd></dt><dd>Move card down / up</dd></div>
          <div><dt><kbd>1</kbd>–<kbd>9</kbd></dt><dd>Jump to rank slot</dd></div>
          <div><dt><kbd>=</kbd> / <kbd>t</kbd></dt><dd>Tie with above / tie all</dd></div>
          <div><dt><kbd>x</kbd></dt><dd>Exclude card</dd></div>
          <div><dt><kbd>-</kbd> / <kbd>+</kbd></dt><dd>Shrink / grow cluster</dd></div>
          <div><dt><kbd>s</kbd> / <kbd>o</kbd></dt><dd>Skip / open source</dd></div>
          <div><dt><kbd>Enter</kbd></dt><dd>Submit comparison</dd></div>
        </dl>
      </section>
    </div>
  </div>
</dialog>

<style>
  .shortcuts-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(12.5rem, 1fr));
    gap: 1rem;
  }

  .shortcuts-grid h3 {
    margin-top: 0;
  }

  .shortcuts-dl {
    margin: 0;
    font-size: 0.84rem;
  }

  .shortcuts-dl > div {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    gap: 0.5rem;
    padding: 0.22rem 0;
    border-bottom: 1px solid var(--rule-soft);
  }

  .shortcuts-dl dt {
    color: var(--ink);
    white-space: nowrap;
  }

  .shortcuts-dl dd {
    margin: 0;
    color: var(--ink-soft);
    text-align: right;
  }

  kbd {
    font-family: var(--mono);
    font-size: 0.76rem;
    padding: 0.08rem 0.32rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
  }
</style>
