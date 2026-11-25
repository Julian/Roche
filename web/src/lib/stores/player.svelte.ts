import { api, sendProgressBeacon } from '../api/client';
import { toast } from './toast.svelte';

const STORAGE_KEY = 'roche_active_audio_v1';
export const PLAYBACK_SPEEDS = [1, 1.25, 1.5, 1.75, 2] as const;

export interface AudioTrack {
  itemId: string;
  title: string;
  feed: string;
  src: string;
  currentTime: number;
  duration: number;
  rate: number;
}

type FinishListener = (itemId: string) => void;

class AudioPlayerStore {
  track = $state<AudioTrack | null>(null);
  isPlaying = $state(false);
  errorMessage = $state<string | null>(null);
  rate = $state<number>(1);
  #audioEl: HTMLAudioElement | null = null;
  #lastPostedProgress = 0;
  #lastPostTs = 0;
  #autoFinishSentFor: string | null = null;
  #pendingSeekTime: number | null = null;
  #pendingSeekFraction: number | null = null;
  #returnFocusEl: HTMLElement | null = null;
  #finishListeners = new Set<FinishListener>();
  #closing = false;

  get hasActiveTrack(): boolean {
    return this.track !== null;
  }

  isItemPlaying(itemId: string): boolean {
    return this.track?.itemId === itemId && this.isPlaying;
  }

  isItemLoaded(itemId: string): boolean {
    return this.track?.itemId === itemId;
  }

  onAudioAutoFinished(cb: FinishListener): () => void {
    this.#finishListeners.add(cb);
    return () => this.#finishListeners.delete(cb);
  }

  restore(): void {
    if (typeof window === 'undefined') return;
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Partial<AudioTrack>;
      if (
        typeof parsed.itemId === 'string' &&
        parsed.itemId.length > 0 &&
        typeof parsed.src === 'string' &&
        parsed.src.startsWith('/api/v1/items/')
      ) {
        this.track = {
          itemId: parsed.itemId,
          title: parsed.title ?? 'Audio',
          feed: parsed.feed ?? '',
          src: parsed.src,
          currentTime: Number(parsed.currentTime ?? 0) || 0,
          duration: Number(parsed.duration ?? 0) || 0,
          rate: Number(parsed.rate ?? 1) || 1,
        };
        this.rate = this.track.rate;
        this.#pendingSeekTime = this.track.currentTime > 1 ? this.track.currentTime : null;
        this.#pendingSeekFraction = null;
        this.syncBodyClass();
        if (this.#audioEl) {
          this.#audioEl.playbackRate = this.rate;
          const targetHref = new URL(this.track.src, window.location.origin).href;
          if (this.#audioEl.src !== targetHref) {
            this.#audioEl.src = this.track.src;
          }
        }
      }
    } catch {
      // Ignore corrupt sessionStorage
    }
  }

  bindAudioElement(el: HTMLAudioElement | null): void {
    this.#audioEl = el;
    if (el && this.track) {
      el.playbackRate = this.rate;
      const targetHref = new URL(this.track.src, window.location.origin).href;
      if (el.src !== targetHref) {
        el.src = this.track.src;
      }
    }
  }

  playOrToggle(opts: {
    itemId: string;
    title: string;
    feed?: string | null;
    progress?: number;
    durationSeconds?: number | null;
    triggerEl?: HTMLElement | null;
  }): void {
    const src = `/api/v1/items/${encodeURIComponent(opts.itemId)}/audio`;
    if (this.track?.itemId === opts.itemId && this.#audioEl) {
      if (this.#audioEl.paused) {
        this.errorMessage = null;
        this.#audioEl.play().catch(() => {
          this.errorMessage = 'Playback could not start — press Play or open the episode.';
        });
      } else {
        this.#audioEl.pause();
      }
      return;
    }

    // Flush previous track progress before switching.
    if (this.track) {
      this.flushProgress(true);
    }

    this.#returnFocusEl = opts.triggerEl ?? null;
    this.errorMessage = null;
    this.#autoFinishSentFor = null;
    this.#lastPostedProgress = opts.progress ?? 0;
    this.#lastPostTs = 0;

    const estDuration = opts.durationSeconds ?? 0;
    const resumableFrac =
      opts.progress && opts.progress > 0.02 && opts.progress < 0.98 ? opts.progress : null;
    const startSec = estDuration > 0 && resumableFrac !== null ? resumableFrac * estDuration : 0;
    this.#pendingSeekTime = startSec > 0 ? startSec : null;
    this.#pendingSeekFraction = startSec === 0 && resumableFrac !== null ? resumableFrac : null;

    this.track = {
      itemId: opts.itemId,
      title: opts.title,
      feed: opts.feed ?? '',
      src,
      currentTime: startSec,
      duration: estDuration,
      rate: this.rate,
    };
    this.syncBodyClass();
    this.persist();

    // `<audio>` is mounted unconditionally so we can invoke `.play()` synchronously within the
    // user gesture (preserving Safari/iOS autoplay without racing DOM mount).
    if (this.#audioEl) {
      this.#audioEl.src = src;
      this.#audioEl.playbackRate = this.rate;
      this.#audioEl.play().catch(() => {
        this.errorMessage = 'Tap Play on the audio controls to start listening.';
      });
    }
  }

  seekBy(deltaSeconds: number): void {
    if (!this.#audioEl || !this.track) return;
    const dur = Number.isFinite(this.#audioEl.duration) ? this.#audioEl.duration : Infinity;
    const next = Math.max(0, Math.min(dur, this.#audioEl.currentTime + deltaSeconds));
    this.#audioEl.currentTime = next;
    this.track.currentTime = next;
    this.persist();
  }

  cycleSpeed(): void {
    const idx = PLAYBACK_SPEEDS.indexOf(this.rate as (typeof PLAYBACK_SPEEDS)[number]);
    const next = PLAYBACK_SPEEDS[(idx + 1) % PLAYBACK_SPEEDS.length] ?? 1;
    this.rate = next;
    if (this.track) {
      this.track.rate = next;
      this.persist();
    }
    if (this.#audioEl) {
      this.#audioEl.playbackRate = next;
    }
  }

  close(): void {
    if (this.track) {
      this.flushProgress(true);
    }
    this.#closing = true;
    this.track = null;
    this.isPlaying = false;
    this.errorMessage = null;
    this.#autoFinishSentFor = null;
    this.#pendingSeekTime = null;
    this.#pendingSeekFraction = null;
    if (this.#audioEl) {
      this.#audioEl.pause();
      this.#audioEl.removeAttribute('src');
      this.#audioEl.load();
    }
    this.#closing = false;
    try {
      sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      // Ignore
    }
    this.syncBodyClass();
    if (this.#returnFocusEl && document.contains(this.#returnFocusEl)) {
      this.#returnFocusEl.focus();
    }
    this.#returnFocusEl = null;
  }

  handleLoadedMetadata(): void {
    if (!this.#audioEl || !this.track) return;
    this.#audioEl.playbackRate = this.rate;
    if (Number.isFinite(this.#audioEl.duration) && this.#audioEl.duration > 0) {
      this.track.duration = this.#audioEl.duration;
      const desiredTime =
        this.#pendingSeekTime ??
        (this.#pendingSeekFraction !== null
          ? this.#pendingSeekFraction * this.#audioEl.duration
          : null);
      if (desiredTime !== null) {
        const target = Math.min(desiredTime, Math.max(0, this.#audioEl.duration - 2));
        if (target > 1) {
          this.#audioEl.currentTime = target;
          this.track.currentTime = target;
        }
        this.#pendingSeekTime = null;
        this.#pendingSeekFraction = null;
      }
      this.persist();
    }
  }

  handlePlay(): void {
    if (!this.track) return;
    this.isPlaying = true;
    this.errorMessage = null;
  }

  handlePause(): void {
    if (this.#closing || !this.track) return;
    this.isPlaying = false;
    this.flushProgress(false);
    this.persist();
  }

  handleTimeUpdate(): void {
    if (!this.#audioEl || !this.track) return;
    const ct = this.#audioEl.currentTime;
    const dur = this.#audioEl.duration;
    this.track.currentTime = ct;
    if (Number.isFinite(dur) && dur > 0) {
      this.track.duration = dur;
      const frac = Math.max(0, Math.min(1, ct / dur));
      const now = Date.now();
      if (frac >= 0.98 && this.#autoFinishSentFor !== this.track.itemId) {
        this.markFinishedFromAudio(this.track.itemId);
      } else if (now - this.#lastPostTs >= 5_000 && Math.abs(frac - this.#lastPostedProgress) >= 0.005) {
        this.#lastPostTs = now;
        this.#lastPostedProgress = frac;
        sendProgressBeacon(this.track.itemId, frac, 'audio', false);
        this.persist();
      }
    }
  }

  handleEnded(): void {
    this.isPlaying = false;
    if (this.track) {
      this.markFinishedFromAudio(this.track.itemId);
    }
  }

  handleError(): void {
    if (this.#closing || !this.track) return;
    this.isPlaying = false;
    this.errorMessage = 'Audio stream failed to load. Open the episode to listen directly.';
  }

  flushProgress(keepalive: boolean): void {
    if (!this.#audioEl || !this.track) return;
    const dur = this.#audioEl.duration;
    if (!Number.isFinite(dur) || dur <= 0) return;
    const frac = Math.max(0, Math.min(1, this.#audioEl.currentTime / dur));
    this.#lastPostedProgress = frac;
    this.#lastPostTs = Date.now();
    sendProgressBeacon(this.track.itemId, frac, 'audio', keepalive);
  }

  private markFinishedFromAudio(itemId: string): void {
    if (this.#autoFinishSentFor === itemId) return;
    this.#autoFinishSentFor = itemId;
    this.#lastPostedProgress = 1;
    sendProgressBeacon(itemId, 1, 'audio', true);
    for (const cb of this.#finishListeners) {
      cb(itemId);
    }
    toast.show('Finished episode & logged to Diary', {
      onUndo: async () => {
        await api.post(`/api/v1/items/${encodeURIComponent(itemId)}/unfinish`, {
          requeue: true,
        });
        toast.show('Marked unfinished');
      },
    });
  }

  private persist(): void {
    if (!this.track) return;
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(this.track));
    } catch {
      // Ignore storage quota errors
    }
  }

  private syncBodyClass(): void {
    if (typeof document === 'undefined') return;
    const active = this.track !== null;
    document.body.classList.toggle('has-player-bar', active);
    document.documentElement.classList.toggle('has-player-bar', active);
  }
}

export const player = new AudioPlayerStore();
