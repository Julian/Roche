import { api, ApiError } from '../api/client';
import type { SyncStatus } from '../api/types';
import { toast } from './toast.svelte';

type RefreshCompleteListener = (deltaUnread: number) => void;

const MAX_POLL_ERRORS = 4;

class RefreshStore {
  isRefreshing = $state(false);
  activeCount = $state(0);
  unreadCount = $state(0);
  pendingReloadDelta = $state<number | null>(null);
  #timer: ReturnType<typeof setTimeout> | null = null;
  #baselineUnread: number | null = null;
  #errorCount = 0;
  #listeners = new Set<RefreshCompleteListener>();

  syncInitial(isRefreshing: boolean, activeCount: number, unreadCount: number): void {
    this.unreadCount = unreadCount;
    this.activeCount = activeCount;
    if (isRefreshing) {
      this.startPolling(unreadCount);
    }
  }

  adjustUnread(delta: number): void {
    this.unreadCount = Math.max(0, this.unreadCount + delta);
  }

  setUnread(count: number): void {
    this.unreadCount = Math.max(0, count);
  }

  onRefreshComplete(cb: RefreshCompleteListener): () => void {
    this.#listeners.add(cb);
    return () => this.#listeners.delete(cb);
  }

  startPolling(baselineUnread?: number): void {
    this.isRefreshing = true;
    this.pendingReloadDelta = null;
    this.#errorCount = 0;
    if (this.#baselineUnread === null) {
      this.#baselineUnread = baselineUnread ?? this.unreadCount;
    }
    this.schedulePoll(1_200);
  }

  async triggerRefreshAll(): Promise<void> {
    this.startPolling();
    try {
      await api.post('/api/v1/refresh-all', {});
    } catch (err) {
      this.stopPolling();
      toast.show(err instanceof ApiError ? err.message : 'Unable to start refresh.', {
        isError: true,
      });
    }
  }

  clearPendingReload(): void {
    this.pendingReloadDelta = null;
  }

  stopPolling(): void {
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
      this.#timer = null;
    }
    this.isRefreshing = false;
    this.activeCount = 0;
    this.#baselineUnread = null;
  }

  private schedulePoll(delayMs: number): void {
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
    }
    this.#timer = setTimeout(() => {
      void this.pollOnce();
    }, delayMs);
  }

  private async pollOnce(): Promise<void> {
    this.#timer = null;
    try {
      const status = await api.get<SyncStatus>('/api/v1/sync/status');
      this.#errorCount = 0;
      this.unreadCount = status.unread_items_count;
      this.activeCount = status.active_refresh_count;
      if (status.is_refreshing) {
        this.isRefreshing = true;
        this.schedulePoll(1_500);
      } else {
        const delta = Math.max(0, status.unread_items_count - (this.#baselineUnread ?? status.unread_items_count));
        this.isRefreshing = false;
        this.#baselineUnread = null;
        if (this.hasDirtyFormOrOpenDetails()) {
          this.pendingReloadDelta = delta;
        } else {
          for (const cb of this.#listeners) {
            cb(delta);
          }
        }
      }
    } catch {
      this.#errorCount += 1;
      if (this.#errorCount >= MAX_POLL_ERRORS) {
        this.stopPolling();
      } else {
        this.schedulePoll(1_500 * Math.pow(2, this.#errorCount - 1));
      }
    }
  }

  private hasDirtyFormOrOpenDetails(): boolean {
    if (typeof document === 'undefined') return false;
    const main = document.getElementById('roche-main');
    if (!main) return false;
    const active = document.activeElement;
    if (
      active &&
      main.contains(active) &&
      (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.tagName === 'SELECT')
    ) {
      return true;
    }
    for (const det of main.querySelectorAll('details[open]')) {
      if (!det.hasAttribute('data-default-open')) {
        return true;
      }
    }
    return false;
  }
}

export const refresh = new RefreshStore();
