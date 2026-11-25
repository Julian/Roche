<script lang="ts">
  interface Props {
    page: number;
    totalPages: number;
    ariaLabel?: string;
    hrefForPage: (page: number) => string;
  }

  let { page, totalPages, ariaLabel = 'Pagination', hrefForPage }: Props = $props();

  let pageNumbers = $derived.by(() => {
    const out: Array<number | 'ellipsis'> = [];
    for (let p = 1; p <= totalPages; p++) {
      if (p === 1 || p === totalPages || Math.abs(p - page) <= 2) {
        out.push(p);
      } else if (Math.abs(p - page) === 3) {
        out.push('ellipsis');
      }
    }
    return out;
  });
</script>

{#if totalPages > 1}
  <nav class="pager" aria-label={ariaLabel}>
    {#if page > 1}
      <a href={hrefForPage(page - 1)}><span aria-hidden="true">←</span> Prev</a>
    {/if}

    {#each pageNumbers as entry, idx (idx)}
      {#if entry === 'ellipsis'}
        <span aria-hidden="true">…</span>
      {:else if entry === page}
        <span class="current" aria-current="page">{entry}</span>
      {:else}
        <a href={hrefForPage(entry)}>{entry}</a>
      {/if}
    {/each}

    {#if page < totalPages}
      <a href={hrefForPage(page + 1)}>Next <span aria-hidden="true">→</span></a>
    {/if}
  </nav>
{/if}

<style>
  nav.pager {
    display: flex;
    align-items: center;
    gap: 0.35rem;
    flex-wrap: wrap;
    margin: 1rem 0;
    font-size: 0.84rem;
    font-variant-numeric: tabular-nums;
  }

  nav.pager a,
  nav.pager .current {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 1.9rem;
    min-height: 1.9rem;
    padding: 0.15rem 0.5rem;
    border: 1px solid var(--rule);
    border-radius: 2px;
    text-decoration: none;
    color: var(--ink-soft);
  }

  nav.pager a:hover {
    border-color: var(--control-border);
    color: var(--ink);
  }

  nav.pager .current {
    background: var(--ink);
    border-color: var(--ink);
    color: var(--paper);
    font-weight: 600;
  }
</style>
