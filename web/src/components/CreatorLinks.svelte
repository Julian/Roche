<script lang="ts">
  interface Props {
    creators: string[];
    artistLinks?: Record<string, string>;
    openNewTab?: boolean;
  }

  let { creators, artistLinks = {}, openNewTab = false }: Props = $props();
</script>

{#each creators as creator, idx (creator + idx)}
  {#if idx > 0}, {/if}
  {@const artistId = artistLinks[creator.trim().toLowerCase()]}
  {#if artistId}
    <a
      href={`/entries/${encodeURIComponent(artistId)}`}
      target={openNewTab ? '_blank' : undefined}
      rel={openNewTab ? 'noopener' : undefined}
    >{creator}</a>
  {:else}
    {creator}
  {/if}
{/each}
