<script lang="ts">
  import type { WebExternalLink } from '../lib/api/types';
  import Icons from './Icons.svelte';

  interface Props {
    links: WebExternalLink[];
    compact?: boolean;
  }

  let { links, compact = false }: Props = $props();
</script>

{#if links.length > 0}
  <ul class="external-links" class:is-compact={compact}>
    {#each links as link (link.href)}
      <li>
        <a
          href={link.href}
          target="_blank"
          rel="noopener noreferrer"
          class="btn btn-small btn-ghost"
        >
          <Icons name={link.icon} size={14} />
          <span>{link.label}</span>
        </a>
      </li>
    {/each}
  </ul>
{/if}

<style>
  ul.external-links {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-wrap: wrap;
    gap: 0.4rem;
  }

  ul.external-links.is-compact {
    gap: 0.3rem;
  }
</style>
