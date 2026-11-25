<script lang="ts">
  import {
    faviconInitial,
    faviconToneIndex,
    isPublicFaviconHost,
  } from '../lib/utils/format';

  interface Props {
    title: string;
    url?: string | null;
    size?: 'sm' | 'md' | 'lg';
  }

  let { title, url = null, size = 'md' }: Props = $props();

  let imgFailed = $state(false);

  let host = $derived(isPublicFaviconHost(url));
  let iconUrl = $derived(host ? `https://icons.duckduckgo.com/ip3/${encodeURIComponent(host)}.ico` : null);
  let tone = $derived(faviconToneIndex(host ?? title));
  let initial = $derived(faviconInitial(title));

  $effect(() => {
    void iconUrl;
    imgFailed = false;
  });
</script>

<span
  class="feed-favicon fav-tone-{tone}"
  class:feed-favicon-sm={size === 'sm'}
  class:feed-favicon-lg={size === 'lg'}
  aria-hidden="true"
>
  {initial}
  {#if iconUrl && !imgFailed}
    <img
      src={iconUrl}
      alt=""
      loading="lazy"
      decoding="async"
      referrerpolicy="no-referrer"
      onerror={() => {
        imgFailed = true;
      }}
    />
  {/if}
</span>
