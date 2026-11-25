<script lang="ts">
  export type IconName =
    | 'search'
    | 'refresh'
    | 'moon'
    | 'sun'
    | 'dismiss'
    | 'restore'
    | 'pencil'
    | 'queue_add'
    | 'queue_check'
    | 'top_arrow'
    | 'grip'
    | 'external'
    | 'wikipedia'
    | 'spotify'
    | 'spotify_mark'
    | 'wiki_mark'
    | 'film'
    | 'book'
    | 'paper'
    | 'game'
    | 'rss'
    | 'discussion'
    | 'clock'
    | 'compact_view'
    | 'previews_view';

  interface Props {
    name: IconName;
    class?: string;
    size?: number;
  }

  let { name, class: className = 'icon', size }: Props = $props();
</script>

{#if name === 'search'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
    <circle cx="7" cy="7" r="4.5" />
    <path d="M10.5 10.5 14 14" />
  </svg>
{:else if name === 'refresh'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M13.5 8a5.5 5.5 0 1 1-1.4-3.65" />
    <path d="M13.5 2.5v2.7h-2.7" />
  </svg>
{:else if name === 'moon'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M13.5 9.8A5.7 5.7 0 0 1 6.2 2.5a5.7 5.7 0 1 0 7.3 7.3Z" />
  </svg>
{:else if name === 'sun'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
    <circle cx="8" cy="8" r="2.8" />
    <path d="M8 1.5v1.6M8 12.9v1.6M1.5 8h1.6M12.9 8h1.6M3.4 3.4l1.1 1.1M11.5 11.5l1.1 1.1M12.6 3.4l-1.1 1.1M4.5 11.5l-1.1 1.1" />
  </svg>
{:else if name === 'dismiss'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
    <path d="M4.25 4.25 11.75 11.75M11.75 4.25 4.25 11.75" />
  </svg>
{:else if name === 'restore'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M2.5 5.5v7.5a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1V5.5" />
    <path d="M1.75 2.5h12.5v3H1.75z" />
    <path d="M8 11.5V7.5M6 9.5l2-2 2 2" />
  </svg>
{:else if name === 'pencil'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M11.6 2.4a1.4 1.4 0 0 1 2 2L5.5 12.5l-3 .75.75-3 8.35-7.85Z" />
  </svg>
{:else if name === 'queue_add'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M3.5 2.5h9v11l-4.5-3-4.5 3v-11Z" />
    <path d="M8 5.2v3.6M6.2 7h3.6" />
  </svg>
{:else if name === 'queue_check'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.45" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M3.5 2.5h9v11l-4.5-3-4.5 3v-11Z" />
    <path d="m6 6.8 1.5 1.5 2.6-2.6" />
  </svg>
{:else if name === 'top_arrow'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M8 13V3.5" />
    <path d="M4 7.5 8 3.5l4 4" />
  </svg>
{:else if name === 'grip'}
  <svg class={className} width={size} height={size} viewBox="0 0 12 18" fill="currentColor" aria-hidden="true">
    <circle cx="3.5" cy="4" r="1.25" />
    <circle cx="8.5" cy="4" r="1.25" />
    <circle cx="3.5" cy="9" r="1.25" />
    <circle cx="8.5" cy="9" r="1.25" />
    <circle cx="3.5" cy="14" r="1.25" />
    <circle cx="8.5" cy="14" r="1.25" />
  </svg>
{:else if name === 'wikipedia'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M12 7v14" />
    <path d="M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z" />
  </svg>
{:else if name === 'spotify'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3" />
  </svg>
{:else if name === 'spotify_mark'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm4.58 14.42a.62.62 0 0 1-.86.2c-2.35-1.44-5.31-1.76-8.8-.96a.62.62 0 1 1-.28-1.22c3.82-.87 7.1-.5 9.74 1.12a.62.62 0 0 1 .2.86zm1.22-2.72a.78.78 0 0 1-1.07.26c-2.69-1.65-6.79-2.13-9.97-1.17a.78.78 0 1 1-.45-1.49c3.63-1.1 8.15-.57 11.23 1.33a.78.78 0 0 1 .26 1.07zm.1-2.83C14.68 8.95 9.37 8.78 6.29 9.71a.94.94 0 1 1-.54-1.8c3.53-1.07 9.4-.87 13.11 1.34a.94.94 0 0 1-.96 1.62z" />
  </svg>
{:else if name === 'wiki_mark'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path d="M12.09 13.119c-.936 1.932-2.217 4.548-2.853 5.728-.616 1.074-1.127.931-1.532.029-1.406-3.321-4.293-9.144-5.651-12.409-.251-.601-.441-.987-.619-1.139-.181-.15-.554-.24-1.122-.271C.103 5.033 0 4.982 0 4.898v-.455l.052-.045c.924-.005 5.401 0 5.401 0l.051.045v.434c0 .119-.075.176-.225.176l-.564.031c-.485.029-.727.164-.727.436 0 .135.053.33.166.601 1.082 2.646 4.818 10.521 4.818 10.521l.136.046 2.411-4.81-.482-1.067-1.658-3.264s-.318-.654-.428-.872c-.728-1.443-.712-1.518-1.447-1.617-.207-.023-.313-.05-.313-.149v-.468l.06-.045h4.292l.113.037v.451c0 .105-.076.15-.227.15l-.308.047c-.792.061-.661.381-.136 1.422l1.582 3.252 1.758-3.504c.293-.64.233-.801.111-.947-.07-.084-.305-.22-.812-.24l-.201-.021c-.052 0-.098-.015-.145-.051-.045-.031-.067-.076-.067-.129v-.427l.061-.045c1.247-.008 4.043 0 4.043 0l.059.045v.436c0 .121-.059.178-.193.178-.646.03-.782.095-1.023.439-.12.186-.375.589-.646 1.039l-2.301 4.273-.065.135 2.792 5.712.17.048 4.396-10.438c.154-.422.129-.722-.064-.895-.197-.172-.346-.273-.857-.295l-.42-.016c-.061 0-.105-.014-.152-.045-.043-.029-.072-.075-.072-.119v-.436l.059-.045h4.961l.041.045v.437c0 .119-.074.18-.209.18-.648.03-1.127.18-1.443.421-.314.255-.557.616-.736 1.067 0 0-4.043 9.258-5.426 12.339-.525 1.007-1.053.917-1.503-.031-.571-1.171-1.773-3.786-2.646-5.711l.053-.036z" />
  </svg>
{:else if name === 'film'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <rect width="18" height="18" x="3" y="3" rx="2" />
    <path d="M7 3v18M3 7.5h4M3 12h18M3 16.5h4M17 3v18M17 7.5h4M17 16.5h4" />
  </svg>
{:else if name === 'book'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H19a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H6.5a1 1 0 0 1 0-5H20" />
  </svg>
{:else if name === 'paper'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
    <path d="M14 2v4a2 2 0 0 0 2 2h4M10 9H8M16 13H8M16 17H8" />
  </svg>
{:else if name === 'game'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <line x1="6" x2="10" y1="11" y2="11" />
    <line x1="8" x2="8" y1="9" y2="13" />
    <line x1="15" x2="15.01" y1="12" y2="12" />
    <line x1="18" x2="18.01" y1="10" y2="10" />
    <path d="M17.32 5H6.68a4 4 0 0 0-3.978 3.59c-.006.052-.01.101-.017.152C2.604 9.416 2 14.456 2 16a3 3 0 0 0 3 3c1 0 1.5-.5 2-1l1.414-1.414A2 2 0 0 1 9.828 16h4.344a2 2 0 0 1 1.414.586L17 18c.5.5 1 1 2 1a3 3 0 0 0 3-3c0-1.545-.604-6.584-.685-7.258-.007-.05-.011-.1-.017-.151A4 4 0 0 0 17.32 5z" />
  </svg>
{:else if name === 'rss'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M4 11a9 9 0 0 1 9 9" />
    <path d="M4 4a16 16 0 0 1 16 16" />
    <circle cx="5" cy="19" r="1" />
  </svg>
{:else if name === 'discussion'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
  </svg>
{:else if name === 'clock'}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <circle cx="8" cy="8" r="6" />
    <path d="M8 4.5v3.75l2.5 1.5" />
  </svg>
{:else if name === 'compact_view'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
    <path d="M2.5 4h11M2.5 8h11M2.5 12h11" />
  </svg>
{:else if name === 'previews_view'}
  <svg class={className} width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.45" stroke-linejoin="round" aria-hidden="true">
    <rect x="2" y="2.5" width="5" height="5" rx="0.8" />
    <rect x="9" y="2.5" width="5" height="5" rx="0.8" />
    <rect x="2" y="9" width="5" height="4.5" rx="0.8" />
    <rect x="9" y="9" width="5" height="4.5" rx="0.8" />
  </svg>
{:else}
  <svg class={className} width={size ?? 14} height={size ?? 14} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path d="M15 3h6v6" />
    <path d="M10 14 21 3" />
    <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
  </svg>
{/if}
