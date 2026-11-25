const THEME_STORAGE_KEY = 'roche_theme_v1';

export type ThemeMode = 'light' | 'dark';

function detectInitialTheme(): ThemeMode {
  if (typeof document === 'undefined') return 'light';
  const htmlAttr = document.documentElement.getAttribute('data-theme');
  if (htmlAttr === 'dark' || htmlAttr === 'light') return htmlAttr;
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY);
    if (saved === 'dark' || saved === 'light') return saved;
  } catch {
    // Ignore storage failures
  }
  if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

function writeThemeCookie(mode: ThemeMode): void {
  if (typeof document === 'undefined') return;
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = `roche_theme=${mode}; Path=/; Max-Age=31536000; SameSite=Lax${secure}`;
}

class ThemeStore {
  mode = $state<ThemeMode>('light');

  init(): void {
    const initial = detectInitialTheme();
    this.mode = initial;
    if (typeof document !== 'undefined') {
      const explicitAttr = document.documentElement.getAttribute('data-theme');
      let stored: string | null = null;
      try {
        stored = localStorage.getItem(THEME_STORAGE_KEY);
      } catch {
        // Ignore
      }
      if (stored === 'dark' || stored === 'light') {
        document.documentElement.setAttribute('data-theme', stored);
        this.mode = stored;
        if (explicitAttr !== stored) {
          writeThemeCookie(stored);
        }
      } else if (explicitAttr === 'dark' || explicitAttr === 'light') {
        this.mode = explicitAttr;
      }
    }
  }

  set(next: ThemeMode): void {
    this.mode = next;
    if (typeof document !== 'undefined') {
      document.documentElement.setAttribute('data-theme', next);
      writeThemeCookie(next);
    }
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // Ignore
    }
  }

  toggle(): void {
    this.set(this.mode === 'dark' ? 'light' : 'dark');
  }
}

export const theme = new ThemeStore();
