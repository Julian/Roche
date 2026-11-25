import type {
  CollectionSummary,
  ConsumptionStatus,
  ContentType,
  EntryKind,
  FeedType,
} from '../api/types';

export function formatDuration(totalSeconds: number | null | undefined): string | null {
  if (totalSeconds === null || totalSeconds === undefined || !Number.isFinite(totalSeconds) || totalSeconds <= 0) {
    return null;
  }
  const s = Math.floor(totalSeconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (h > 0) {
    return `${h}h ${m}m`;
  }
  return `${Math.max(1, m)}m`;
}

export function formatTrackDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms) || ms <= 0) return '';
  const totalSecs = Math.round(ms / 1000);
  const m = Math.floor(totalSecs / 60);
  const s = totalSecs % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function formatAlbumTotalDuration(totalMs: number): string | null {
  if (!Number.isFinite(totalMs) || totalMs <= 0) return null;
  const totalMin = Math.round(totalMs / 60_000);
  if (totalMin >= 60) {
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    return `${h}h ${m}m`;
  }
  return `${Math.max(1, totalMin)} min`;
}

export function formatShortDate(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

export function formatMonthDay(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  });
}

export function formatMonthYear(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'Undated';
  return d.toLocaleDateString('en-US', {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  });
}

export function formatIsoDateInput(iso: string | null | undefined): string {
  if (!iso) return '';
  const match = iso.match(/^(\d{4}-\d{2}-\d{2})/);
  return match?.[1] ?? '';
}

export function formatDayGroupHeading(iso: string | null | undefined, now = new Date()): string {
  if (!iso) return 'Earlier';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'Earlier';
  const todayUtc = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const itemUtc = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  const diffDays = Math.round((todayUtc - itemUtc) / 86_400_000);
  const full = d.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  });
  if (diffDays === 0) {
    const short = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
    return `Today · ${short}`;
  }
  if (diffDays === 1) {
    const short = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
    return `Yesterday · ${short}`;
  }
  if (diffDays > 1 && diffDays < 7) {
    const wd = d.toLocaleDateString('en-US', { weekday: 'long', timeZone: 'UTC' });
    const short = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
    return `${wd} · ${short}`;
  }
  return full;
}

export function formatRelativeAge(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '';
  const ts = Date.parse(iso);
  if (Number.isNaN(ts)) return '';
  const diffSec = Math.max(0, Math.floor((now - ts) / 1000));
  if (diffSec < 60) return 'just now';
  const mins = Math.floor(diffSec / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 30) return `${days}d ago`;
  return formatShortDate(iso);
}

export function formatCommentsLabel(count: number | null | undefined): string {
  if (count === null || count === undefined || count < 0) return 'discuss';
  if (count === 1) return '1 comment';
  return `${count} comments`;
}

export function extractDisplayHost(rawUrl: string | null | undefined): string | null {
  if (!rawUrl) return null;
  try {
    const u = new URL(rawUrl);
    return u.hostname.replace(/^www\./i, '') || null;
  } catch {
    return null;
  }
}

export function normalizeUrlInput(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) return '';
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)) {
    return trimmed;
  }
  return `https://${trimmed}`;
}

/** Allows only http(s) URLs and rejects synthetic `roche.local` placeholders. */
export function safeHttpHref(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;
  try {
    const u = new URL(trimmed);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    const host = u.hostname.toLowerCase().replace(/\.$/, '');
    if (!host || host === 'roche.local' || host.endsWith('.roche.local')) return null;
    return u.href;
  } catch {
    return null;
  }
}

export function isDynamicCollection(
  col: Pick<CollectionSummary, 'id' | 'rule_json'>,
  dynamicIds?: ReadonlySet<string> | readonly string[],
): boolean {
  if (dynamicIds) {
    return Array.isArray(dynamicIds) ? dynamicIds.includes(col.id) : (dynamicIds as ReadonlySet<string>).has(col.id);
  }
  return Boolean(col.rule_json?.trim());
}

export function isPublicFaviconHost(rawUrl: string | null | undefined): string | null {
  if (!rawUrl) return null;
  try {
    const u = new URL(rawUrl);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    const host = u.hostname.toLowerCase().replace(/\.$/, '');
    if (!host || !host.includes('.')) return null;
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host) || host.includes(':')) return null;
    if (
      host === 'localhost' ||
      host.endsWith('.local') ||
      host.endsWith('.localhost') ||
      host.endsWith('.internal') ||
      host.endsWith('.ts.net') ||
      host.endsWith('.lan') ||
      host.endsWith('.home') ||
      host.endsWith('.arpa')
    ) {
      return null;
    }
    return host;
  } catch {
    return null;
  }
}

export function faviconToneIndex(seed: string): number {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return hash % 5;
}

export function faviconInitial(title: string): string {
  const cleaned = title.trim().replace(/^the\s+/i, '');
  const ch = cleaned.charAt(0).toUpperCase();
  return ch && /[A-Z0-9]/.test(ch) ? ch : 'R';
}

export function entryKindLabel(kind: EntryKind): string {
  switch (kind) {
    case 'article':
      return 'Article';
    case 'podcast_episode':
      return 'Podcast';
    case 'video':
      return 'Video';
    case 'paper':
      return 'Paper';
    case 'book':
      return 'Book';
    case 'movie':
      return 'Movie';
    case 'tv':
      return 'TV';
    case 'album':
      return 'Album';
    case 'song':
      return 'Song';
    case 'artist':
      return 'Artist';
    case 'podcast':
      return 'Podcast Show';
    case 'publication':
      return 'Publication';
    case 'channel':
      return 'Channel';
    case 'game':
      return 'Game';
    case 'collection':
      return 'List';
  }
}

export function entryKindDefaultListSlug(kind: EntryKind): string | null {
  switch (kind) {
    case 'book':
      return 'books';
    case 'movie':
      return 'movies';
    case 'tv':
      return 'tv';
    case 'album':
      return 'albums';
    case 'song':
      return 'songs';
    case 'artist':
      return 'artists';
    case 'podcast':
      return 'podcasts';
    case 'publication':
      return 'publications';
    case 'channel':
      return 'channels';
    case 'game':
      return 'games';
    case 'article':
      return 'articles';
    case 'podcast_episode':
      return 'podcast_episodes';
    case 'video':
      return 'videos';
    case 'paper':
      return 'papers';
    case 'collection':
      return null;
  }
}

export function contentTypeBadge(
  contentType: ContentType,
  hasAudio: boolean,
  entryKind?: EntryKind | null,
): string | null {
  if (entryKind === 'podcast_episode') return 'Podcast';
  if (contentType === 'video') return 'Video';
  if (contentType === 'paper') return 'Paper';
  if (contentType === 'pdf') return 'PDF';
  if (contentType === 'audio' || hasAudio) return 'Audio';
  return null;
}

export function consumptionStatusLabel(status: ConsumptionStatus): string {
  switch (status) {
    case 'consumed':
      return 'Finished';
    case 'started':
      return 'Started';
    case 'abandoned':
      return 'Skipped';
  }
}

export function feedTypeLabel(ft: FeedType): string {
  switch (ft) {
    case 'rss':
      return 'RSS';
    case 'podcast':
      return 'Podcast';
    case 'youtube':
      return 'YouTube';
    case 'arxiv':
      return 'arXiv';
  }
}

export function stripMathDelimiters(text: string): string {
  return text.replace(/\$\$([^$]+)\$\$/g, '$1').replace(/\$([^$]+)\$/g, '$1');
}

export function clampExcerpt(text: string | null | undefined, maxChars = 220): string {
  if (!text) return '';
  const flat = text.replace(/\s+/g, ' ').trim();
  if (flat.length <= maxChars) return flat;
  return `${flat.slice(0, maxChars).trimEnd()}…`;
}

export function parseCsvList(raw: string): string[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

export function safeLocalReturnTo(candidate: string | null | undefined, fallback = '/'): string {
  if (!candidate) return fallback;
  const trimmed = candidate.trim();
  if (
    trimmed.startsWith('/') &&
    !trimmed.startsWith('//') &&
    !trimmed.includes('\\') &&
    !/[\x00-\x1f\x7f]/.test(trimmed)
  ) {
    const path = trimmed.split(/[?#]/, 1)[0] ?? '/';
    if (
      path === '/login' ||
      path === '/login/tailscale' ||
      path === '/logout' ||
      path === '/api' ||
      path.startsWith('/api/') ||
      path === '/web' ||
      path.startsWith('/web/')
    ) {
      return fallback;
    }
    return trimmed;
  }
  return fallback;
}

export const LOGGABLE_KINDS: Array<{ value: EntryKind; label: string }> = [
  { value: 'album', label: 'Album' },
  { value: 'book', label: 'Book' },
  { value: 'movie', label: 'Movie' },
  { value: 'tv', label: 'TV Series' },
  { value: 'game', label: 'Game' },
  { value: 'song', label: 'Song' },
  { value: 'article', label: 'Article' },
  { value: 'paper', label: 'Paper' },
  { value: 'podcast_episode', label: 'Podcast Episode' },
  { value: 'video', label: 'Video' },
];

export const ALL_SEARCHABLE_KINDS: Array<{ value: EntryKind; label: string }> = [
  ...LOGGABLE_KINDS,
  { value: 'artist', label: 'Artist' },
  { value: 'podcast', label: 'Podcast Show' },
  { value: 'publication', label: 'Publication' },
  { value: 'channel', label: 'Channel' },
];
