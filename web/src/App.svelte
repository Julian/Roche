<script lang="ts">
  import { onMount } from 'svelte';
  import { router } from './lib/router.svelte';
  import { auth } from './lib/stores/auth.svelte';
  import { theme } from './lib/stores/theme.svelte';
  import { player } from './lib/stores/player.svelte';
  import { refresh } from './lib/stores/refresh.svelte';
  import { modals } from './lib/stores/modals.svelte';
  import { safeLocalReturnTo } from './lib/utils/format';

  import Masthead from './components/Masthead.svelte';
  import RefreshBanner from './components/RefreshBanner.svelte';
  import Colophon from './components/Colophon.svelte';
  import AudioPlayerBar from './components/AudioPlayerBar.svelte';
  import ToastRegion from './components/ToastRegion.svelte';
  import QuickLogModal from './components/QuickLogModal.svelte';
  import ShortcutsModal from './components/ShortcutsModal.svelte';

  import LoginPage from './routes/LoginPage.svelte';
  import HomePage from './routes/HomePage.svelte';
  import SearchPage from './routes/SearchPage.svelte';
  import InboxPage from './routes/InboxPage.svelte';
  import FeedsPage from './routes/FeedsPage.svelte';
  import ReaderPage from './routes/ReaderPage.svelte';
  import BoardsPage from './routes/BoardsPage.svelte';
  import BoardReaderPage from './routes/BoardReaderPage.svelte';
  import ListsPage from './routes/ListsPage.svelte';
  import ListDetailPage from './routes/ListDetailPage.svelte';
  import EntryDetailPage from './routes/EntryDetailPage.svelte';
  import DiaryPage from './routes/DiaryPage.svelte';
  import QueuePage from './routes/QueuePage.svelte';
  import RankPage from './routes/RankPage.svelte';

  let pageReloadKey = $state(0);

  function bumpPageReload(): void {
    pageReloadKey += 1;
  }

  onMount(() => {
    theme.init();
    router.init();

    void auth.bootstrap().then((status) => {
      refresh.syncInitial(
        status.is_refreshing,
        status.active_refresh_count,
        status.unread_items_count,
      );
      if (status.authenticated) {
        player.restore();
      }
      if (!status.authenticated && router.route.name !== 'login') {
        const ret = encodeURIComponent(router.fullPath);
        router.navigate(`/login?return_to=${ret}`, { replace: true });
      } else if (status.authenticated && router.route.name === 'login') {
        const dest = safeLocalReturnTo(router.route.query.get('return_to'), '/');
        router.navigate(dest, { replace: true });
      }
    });

    const onGlobalKeyDown = (e: KeyboardEvent) => {
      if (!auth.authenticated) return;
      const target = e.target as HTMLElement | null;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;

      if (e.key === '?') {
        if (!modals.quickLogOpen) {
          e.preventDefault();
          modals.toggleShortcuts();
        }
        return;
      }
      if (modals.anyOpen) return;

      const isRank = router.route.name === 'rank';
      if (e.key === 'c' && !isRank) {
        e.preventDefault();
        modals.openQuickLog('media');
      } else if (e.key === '/' && !isRank) {
        e.preventDefault();
        const searchEl =
          document.getElementById('roche-inbox-search') ??
          document.getElementById('roche-board-search') ??
          document.getElementById('roche-global-search');
        if (searchEl instanceof HTMLInputElement) {
          searchEl.focus();
          searchEl.select();
        }
      }
    };

    window.addEventListener('keydown', onGlobalKeyDown);
    return () => {
      window.removeEventListener('keydown', onGlobalKeyDown);
    };
  });
</script>

{#if !auth.initialized}
  <main id="roche-main" tabindex="-1">
    <p class="empty">Loading Roche…</p>
  </main>
{:else if router.route.name === 'login' || (!auth.authenticated && !auth.sessionExpiredBanner)}
  <LoginPage />
{:else}
  <a class="skip-link" href="#roche-main">Skip to content</a>
  <Masthead />
  <RefreshBanner onReload={bumpPageReload} />

  <main id="roche-main" tabindex="-1">
    {#if auth.sessionExpiredBanner}
      <div class="notice notice-error" role="alert">
        Session expired. Audio playback is unaffected —
        <a href={`/login?return_to=${encodeURIComponent(router.fullPath)}`}>sign in again</a>.
      </div>
    {/if}

    {#key pageReloadKey}
      {#if router.route.name === 'home'}
        <HomePage />
      {:else if router.route.name === 'search'}
        <SearchPage />
      {:else if router.route.name === 'inbox'}
        <InboxPage />
      {:else if router.route.name === 'feeds'}
        <FeedsPage />
      {:else if router.route.name === 'reader'}
        {#key router.route.id}
          <ReaderPage id={router.route.id} />
        {/key}
      {:else if router.route.name === 'boards'}
        {#key router.route.boardId ?? ''}
          <BoardsPage boardId={router.route.boardId} />
        {/key}
      {:else if router.route.name === 'board_reader'}
        {#key `${router.route.boardId}:${router.route.externalId}`}
          <BoardReaderPage boardId={router.route.boardId} externalId={router.route.externalId} />
        {/key}
      {:else if router.route.name === 'lists'}
        <ListsPage />
      {:else if router.route.name === 'list_detail'}
        {#key router.route.slug}
          <ListDetailPage slug={router.route.slug} />
        {/key}
      {:else if router.route.name === 'entry_detail'}
        {#key router.route.id}
          <EntryDetailPage id={router.route.id} />
        {/key}
      {:else if router.route.name === 'diary'}
        <DiaryPage />
      {:else if router.route.name === 'queue'}
        <QueuePage />
      {:else if router.route.name === 'rank'}
        {#key router.route.slug}
          <RankPage slug={router.route.slug} />
        {/key}
      {:else}
        <section>
          <h2>Not Found</h2>
          <p class="lede">The requested page does not exist.</p>
          <a class="btn btn-primary" href="/">Return Home</a>
        </section>
      {/if}
    {/key}
  </main>

  <Colophon />
  <QuickLogModal onSaved={bumpPageReload} />
  <ShortcutsModal />
{/if}

<ToastRegion />

{#if auth.authenticated || player.hasActiveTrack}
  <AudioPlayerBar />
{/if}
