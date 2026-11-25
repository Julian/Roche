<script lang="ts">
  import { auth } from '../lib/stores/auth.svelte';
  import { modals } from '../lib/stores/modals.svelte';
</script>

<footer class="colophon">
  <span>❦ Roche · Reader, Podcasts &amp; Ranked Library</span>
  <div class="colophon-actions">
    <button
      type="button"
      class="link-btn colophon-shortcuts-btn"
      onclick={() => modals.openShortcuts()}
    >
      Shortcuts (?)
    </button>
    <button
      type="button"
      class="link-btn"
      onclick={() => void auth.logout()}
    >
      Sign out
    </button>
  </div>
</footer>

<style>
  footer.colophon {
    margin-top: 3.5rem;
    padding-top: 1.15rem;
    border-top: 1px solid var(--rule);
    font-size: 0.8rem;
    color: var(--ink-faint);
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 0.75rem;
    flex-wrap: wrap;
  }

  .colophon-actions {
    display: inline-flex;
    align-items: center;
    gap: 0.75rem;
  }

  .colophon-shortcuts-btn {
    font-size: 0.78rem;
  }
</style>
