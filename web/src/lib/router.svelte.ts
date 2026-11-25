// Lightweight History API router built with Svelte 5 runes.

export type NavSection =
  | 'home'
  | 'inbox'
  | 'boards'
  | 'starred'
  | 'feeds'
  | 'lists'
  | 'queue'
  | 'diary'
  | null;

export type RouteMatch =
  | { name: 'login'; query: URLSearchParams }
  | { name: 'home'; query: URLSearchParams }
  | { name: 'search'; query: URLSearchParams }
  | { name: 'inbox'; query: URLSearchParams }
  | { name: 'feeds'; query: URLSearchParams }
  | { name: 'reader'; id: string; query: URLSearchParams }
  | { name: 'boards'; boardId: string | null; query: URLSearchParams }
  | { name: 'board_reader'; boardId: string; externalId: string; query: URLSearchParams }
  | { name: 'lists'; query: URLSearchParams }
  | { name: 'list_detail'; slug: string; query: URLSearchParams }
  | { name: 'entry_detail'; id: string; query: URLSearchParams }
  | { name: 'diary'; query: URLSearchParams }
  | { name: 'queue'; query: URLSearchParams }
  | { name: 'rank'; slug: string; query: URLSearchParams }
  | { name: 'not_found'; path: string; query: URLSearchParams };

function decodeSegment(raw: string): string {
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

export function matchRoute(pathname: string, search: string): RouteMatch {
  const query = new URLSearchParams(search);
  const clean = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;

  if (clean === '/' || clean === '') return { name: 'home', query };
  if (clean === '/login') return { name: 'login', query };
  if (clean === '/search') return { name: 'search', query };
  if (clean === '/inbox') return { name: 'inbox', query };
  if (clean === '/starred') return { name: 'list_detail', slug: 'starred', query };
  if (clean === '/feeds') return { name: 'feeds', query };
  if (clean === '/boards') return { name: 'boards', boardId: null, query };
  if (clean === '/lists') return { name: 'lists', query };
  if (clean === '/diary') return { name: 'diary', query };
  if (clean === '/queue') return { name: 'queue', query };
  if (clean === '/rank') return { name: 'lists', query };

  const parts = clean.split('/').filter(Boolean);
  if (parts[0] === 'reader' && parts.length === 2 && parts[1]) {
    return { name: 'reader', id: decodeSegment(parts[1]), query };
  }
  if (parts[0] === 'boards' && parts.length === 2 && parts[1]) {
    return { name: 'boards', boardId: decodeSegment(parts[1]), query };
  }
  if (parts[0] === 'boards' && parts.length === 4 && parts[1] && parts[2] === 'read' && parts[3]) {
    return {
      name: 'board_reader',
      boardId: decodeSegment(parts[1]),
      externalId: decodeSegment(parts[3]),
      query,
    };
  }
  if (parts[0] === 'lists' && parts.length === 2 && parts[1]) {
    return { name: 'list_detail', slug: decodeSegment(parts[1]), query };
  }
  if (parts[0] === 'entries' && parts.length === 2 && parts[1]) {
    return { name: 'entry_detail', id: decodeSegment(parts[1]), query };
  }
  if (parts[0] === 'rank' && parts.length === 2 && parts[1]) {
    return { name: 'rank', slug: decodeSegment(parts[1]), query };
  }

  return { name: 'not_found', path: clean, query };
}

export function activeNavForRoute(route: RouteMatch): NavSection {
  switch (route.name) {
    case 'home':
      return 'home';
    case 'inbox':
      return 'inbox';
    case 'boards':
    case 'board_reader':
      return 'boards';
    case 'feeds':
      return 'feeds';
    case 'lists':
    case 'entry_detail':
      return 'lists';
    case 'list_detail':
      return route.slug === 'starred' ? 'starred' : 'lists';
    case 'diary':
      return 'diary';
    case 'queue':
      return 'queue';
    case 'rank':
      return route.query.get('want') === 'true' || route.slug.startsWith('want:')
        ? 'queue'
        : 'lists';
    case 'reader': {
      const ret = route.query.get('return_to') ?? '';
      if (ret.startsWith('/boards')) return 'boards';
      if (ret.startsWith('/queue')) return 'queue';
      if (ret.startsWith('/lists/starred') || ret.startsWith('/starred')) return 'starred';
      return 'inbox';
    }
    case 'search':
    case 'login':
    case 'not_found':
      return null;
  }
}

class RouterStore {
  pathname = $state(typeof window !== 'undefined' ? window.location.pathname : '/');
  search = $state(typeof window !== 'undefined' ? window.location.search : '');
  hash = $state(typeof window !== 'undefined' ? window.location.hash : '');
  navVersion = $state(0);
  #initialized = false;

  get route(): RouteMatch {
    return matchRoute(this.pathname, this.search);
  }

  get activeNav(): NavSection {
    return activeNavForRoute(this.route);
  }

  get fullPath(): string {
    return `${this.pathname}${this.search}`;
  }

  init(): void {
    if (this.#initialized || typeof window === 'undefined') return;
    this.#initialized = true;

    // Canonicalize legacy redirect aliases on initial load.
    if (window.location.pathname === '/starred') {
      window.history.replaceState(null, '', `/lists/starred${window.location.search}`);
      this.syncFromWindow();
    } else if (window.location.pathname === '/rank') {
      window.history.replaceState(null, '', `/lists${window.location.search}`);
      this.syncFromWindow();
    }

    window.addEventListener('popstate', (event: PopStateEvent) => {
      this.syncFromWindow();
      const savedY =
        event.state && typeof (event.state as { scrollY?: unknown }).scrollY === 'number'
          ? (event.state as { scrollY: number }).scrollY
          : null;
      if (savedY !== null) {
        requestAnimationFrame(() => window.scrollTo({ top: savedY, behavior: 'auto' }));
      }
    });

    // Global delegated click interception for internal SPA links.
    document.addEventListener('click', (event: MouseEvent) => {
      if (
        event.defaultPrevented ||
        event.button !== 0 ||
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.altKey
      ) {
        return;
      }
      const target = event.target as HTMLElement | null;
      const anchor = target?.closest('a[href]') as HTMLAnchorElement | null;
      if (!anchor) return;
      if (
        anchor.target &&
        anchor.target !== '_self'
      ) {
        return;
      }
      if (anchor.hasAttribute('download') || anchor.getAttribute('data-native-nav') === '1') {
        return;
      }
      const rawHref = anchor.getAttribute('href');
      if (!rawHref || rawHref.startsWith('#') || rawHref.startsWith('mailto:') || rawHref.startsWith('tel:')) {
        return;
      }
      let parsed: URL;
      try {
        parsed = new URL(anchor.href, window.location.origin);
      } catch {
        return;
      }
      if (parsed.origin !== window.location.origin) return;
      if (parsed.pathname.startsWith('/api/') || parsed.pathname.startsWith('/assets/')) return;
      // Same-page hash jump: let browser handle natively.
      if (
        parsed.pathname === window.location.pathname &&
        parsed.search === window.location.search &&
        parsed.hash
      ) {
        return;
      }
      event.preventDefault();
      this.navigate(parsed.pathname + parsed.search + parsed.hash);
    });
  }

  syncFromWindow(): void {
    this.pathname = window.location.pathname;
    this.search = window.location.search;
    this.hash = window.location.hash;
    this.navVersion += 1;
  }

  navigate(to: string, opts: { replace?: boolean; preserveScroll?: boolean } = {}): void {
    if (typeof window === 'undefined') return;
    let parsed: URL;
    try {
      parsed = new URL(to, window.location.origin);
    } catch {
      return;
    }
    let nextPath = parsed.pathname;
    if (nextPath === '/starred') nextPath = '/lists/starred';
    if (nextPath === '/rank') nextPath = '/lists';
    const nextUrl = `${nextPath}${parsed.search}${parsed.hash}`;
    const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;

    if (opts.replace || nextUrl === currentUrl) {
      window.history.replaceState({ scrollY: window.scrollY }, '', nextUrl);
    } else {
      // Save current scroll position into the departing entry before pushing.
      try {
        window.history.replaceState({ scrollY: window.scrollY }, '', currentUrl);
      } catch {
        // Ignore SecurityError on exotic origins
      }
      window.history.pushState({ scrollY: 0 }, '', nextUrl);
    }
    this.syncFromWindow();

    if (!opts.preserveScroll) {
      if (parsed.hash) {
        const targetId = parsed.hash.slice(1);
        const tryScroll = () => {
          const el = document.getElementById(targetId);
          if (el) {
            el.scrollIntoView();
            return true;
          }
          return false;
        };
        if (!tryScroll()) {
          requestAnimationFrame(() => {
            if (!tryScroll()) {
              window.scrollTo({ top: 0, behavior: 'auto' });
            }
          });
        }
        return;
      }
      window.scrollTo({ top: 0, behavior: 'auto' });
    }
  }

  replaceSearch(params: URLSearchParams): void {
    const q = params.toString();
    const next = q ? `${this.pathname}?${q}` : this.pathname;
    window.history.replaceState(window.history.state, '', next);
    this.search = q ? `?${q}` : '';
  }

  setTitle(pageTitle: string | null): void {
    if (typeof document === 'undefined') return;
    document.title = pageTitle && pageTitle !== 'Roche' ? `${pageTitle} — Roche` : 'Roche';
  }
}

export const router = new RouterStore();
