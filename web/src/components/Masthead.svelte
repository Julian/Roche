<script lang="ts">
  import { router, type NavSection } from '../lib/router.svelte';
  import { theme } from '../lib/stores/theme.svelte';
  import { modals } from '../lib/stores/modals.svelte';
  import { refresh } from '../lib/stores/refresh.svelte';
  import Icons from './Icons.svelte';

  const NAV_ITEMS: Array<{ section: Exclude<NavSection, null>; href: string; label: string }> = [
    { section: 'home', href: '/', label: 'Home' },
    { section: 'inbox', href: '/inbox', label: 'Inbox' },
    { section: 'boards', href: '/boards', label: 'Boards' },
    { section: 'starred', href: '/lists/starred', label: 'Starred' },
    { section: 'feeds', href: '/feeds', label: 'Feeds' },
    { section: 'lists', href: '/lists', label: 'Lists' },
    { section: 'queue', href: '/queue', label: 'Queue' },
    { section: 'diary', href: '/diary', label: 'Diary' },
  ];

  let searchOpen = $state(false);
  let searchValue = $state('');
  let navEl = $state<HTMLElement | null>(null);
  let searchInputEl = $state<HTMLInputElement | null>(null);

  $effect(() => {
    const r = router.route;
    if (r.name === 'search') {
      searchValue = r.query.get('q') ?? '';
    } else if (!searchInputEl || document.activeElement !== searchInputEl) {
      searchValue = '';
    }
  });

  $effect(() => {
    // Auto-scroll active nav link into view on narrow mobile screens.
    void router.activeNav;
    if (!navEl) return;
    const activeLink = navEl.querySelector<HTMLElement>('a.is-active');
    if (activeLink && navEl.scrollWidth > navEl.clientWidth) {
      activeLink.scrollIntoView({ block: 'nearest', inline: 'center' });
    }
  });

  function handleSearchSubmit(e: SubmitEvent): void {
    e.preventDefault();
    const q = searchValue.trim();
    searchOpen = false;
    router.navigate(q ? `/search?q=${encodeURIComponent(q)}` : '/search');
  }

  function toggleMobileSearch(): void {
    searchOpen = !searchOpen;
    if (searchOpen) {
      queueMicrotask(() => searchInputEl?.focus());
    }
  }
</script>

<header class="masthead" class:is-search-open={searchOpen}>
  <div class="masthead-top">
    <h1><a href="/">Roche</a></h1>

    <form
      class="masthead-search"
      role="search"
      aria-label="Global search"
      onsubmit={handleSearchSubmit}
    >
      <Icons name="search" class="icon search-icon" />
      <label for="roche-global-search" class="sr-only">Search Roche</label>
      <input
        id="roche-global-search"
        bind:this={searchInputEl}
        bind:value={searchValue}
        class="masthead-search-input"
        type="search"
        name="q"
        placeholder="Search…"
        autocomplete="off"
      />
      <kbd class="search-kbd" aria-hidden="true">/</kbd>
    </form>

    <div class="masthead-utils">
      <button
        type="button"
        class="util-btn masthead-search-toggle"
        aria-label="Toggle search"
        aria-expanded={searchOpen}
        onclick={toggleMobileSearch}
      >
        <Icons name="search" />
      </button>

      <button
        type="button"
        id="roche-quick-log-btn"
        class="util-btn masthead-quick-log"
        title="Quick Log or Save URL (c)"
        onclick={() => modals.openQuickLog('media')}
      >
        <span>+ Log</span>
      </button>

      <button
        type="button"
        class="util-btn"
        title="Refresh all active feeds and boards"
        aria-label="Refresh all feeds and boards"
        disabled={refresh.isRefreshing}
        onclick={() => void refresh.triggerRefreshAll()}
      >
        <Icons name="refresh" />
        <span class="util-label">Refresh</span>
      </button>

      <button
        type="button"
        id="roche-theme-toggle"
        class="util-btn"
        title={theme.mode === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        aria-label={theme.mode === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
        aria-pressed={theme.mode === 'dark'}
        onclick={() => theme.toggle()}
      >
        {#if theme.mode === 'dark'}
          <Icons name="sun" class="icon icon-sun" />
        {:else}
          <Icons name="moon" class="icon icon-moon" />
        {/if}
      </button>
    </div>
  </div>

  <nav id="roche-nav" bind:this={navEl} class="primary-nav" aria-label="Primary">
    {#each NAV_ITEMS as item (item.section)}
      <a
        href={item.href}
        class:is-active={router.activeNav === item.section}
        aria-current={router.activeNav === item.section ? 'page' : undefined}
      >
        {item.label}
      </a>
    {/each}
  </nav>
</header>

<style>
  header.masthead {
    border-bottom: 3px double var(--rule);
    padding-bottom: 0.9rem;
    margin-bottom: 1.95rem;
  }

  .masthead-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.6rem 0.65rem;
    flex-wrap: wrap;
  }

  header.masthead h1 {
    font-family: var(--display);
    font-weight: 400;
    font-style: italic;
    font-size: 1.95rem;
    letter-spacing: -0.01em;
    margin: 0 auto 0 0;
    line-height: 1.1;
  }

  header.masthead h1 a {
    color: var(--ink);
    text-decoration: none;
  }

  .masthead-utils {
    display: flex;
    align-items: center;
    gap: 0.35rem;
  }

  .masthead-search {
    position: relative;
    display: inline-flex;
    align-items: center;
    margin: 0;
  }

  .masthead-search :global(.search-icon) {
    position: absolute;
    left: 0.5rem;
    color: var(--ink-faint);
    pointer-events: none;
    width: 0.85rem;
    height: 0.85rem;
  }

  .masthead-search input.masthead-search-input {
    width: 11.5rem;
    padding: 0.28rem 1.85rem 0.28rem 1.85rem;
    font-size: 0.84rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 2px;
    color: var(--ink);
    transition: width 150ms ease, border-color 120ms ease, background 120ms ease;
  }

  .masthead-search input.masthead-search-input:focus {
    width: 15rem;
    background: var(--paper-input);
    border-color: var(--accent);
  }

  .search-kbd {
    position: absolute;
    right: 0.42rem;
    font-family: var(--mono);
    font-size: 0.68rem;
    line-height: 1;
    color: var(--ink-faint);
    border: 1px solid var(--rule);
    background: var(--paper);
    padding: 0.12rem 0.28rem;
    border-radius: 2px;
    pointer-events: none;
  }

  .masthead-search:focus-within .search-kbd {
    opacity: 0;
  }

  .util-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.32rem;
    min-width: 30px;
    min-height: 30px;
    padding: 0.24rem 0.5rem;
    background: transparent;
    border: 1px solid transparent;
    border-radius: 2px;
    font-family: var(--serif);
    font-size: 0.75rem;
    letter-spacing: 0.06em;
    text-transform: uppercase;
    color: var(--ink-soft);
    cursor: pointer;
    transition: background 120ms ease, border-color 120ms ease, color 120ms ease;
  }

  .util-btn.masthead-search-toggle {
    display: none;
  }

  .masthead-quick-log {
    white-space: nowrap;
  }

  .util-btn:hover {
    background: var(--paper-deep);
    border-color: var(--rule);
    color: var(--ink);
  }

  @media (pointer: coarse) {
    .util-btn {
      min-width: 40px;
      min-height: 40px;
    }
  }

  .util-btn :global(.icon) {
    width: 0.92rem;
    height: 0.92rem;
  }

  nav.primary-nav {
    margin-top: 0.75rem;
    font-size: 0.8rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    font-feature-settings: "kern" 1, "liga" 1, "lnum" 1, "tnum" 1;
    font-variant-numeric: lining-nums tabular-nums;
    color: var(--ink-soft);
    display: flex;
    flex-wrap: wrap;
    gap: 0.35rem 1.15rem;
    align-items: baseline;
  }

  nav.primary-nav a {
    color: var(--ink-soft);
    text-decoration: none;
    padding: 0.15rem 0;
    white-space: nowrap;
  }

  nav.primary-nav a:hover,
  nav.primary-nav a.is-active {
    color: var(--accent);
  }

  nav.primary-nav a.is-active {
    border-bottom: 1.5px solid var(--accent);
  }

  @media (max-width: 600px) {
    header.masthead {
      padding-bottom: 0.7rem;
      margin-bottom: 1.35rem;
    }
    .masthead-search {
      display: none;
      order: 20;
      flex-basis: 100%;
      margin-top: 0.4rem;
    }
    header.masthead.is-search-open .masthead-search {
      display: flex;
    }
    .masthead-search input.masthead-search-input,
    .masthead-search input.masthead-search-input:focus {
      width: 100%;
      padding-right: 0.7rem;
    }
    .search-kbd {
      display: none;
    }
    .util-btn.masthead-search-toggle {
      display: inline-flex;
    }
    .util-btn .util-label {
      display: none;
    }
    nav.primary-nav {
      flex-wrap: nowrap;
      overflow-x: auto;
      scrollbar-width: none;
      -webkit-overflow-scrolling: touch;
      gap: 0 0.85rem;
      font-size: 0.75rem;
      padding: 0 1.25rem 0.15rem 0;
      margin-top: 0.65rem;
      mask-image: linear-gradient(to right, #000 86%, transparent);
      -webkit-mask-image: linear-gradient(to right, #000 86%, transparent);
    }
    nav.primary-nav::-webkit-scrollbar {
      display: none;
    }
  }
</style>
