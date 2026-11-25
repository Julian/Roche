export interface ToastItem {
  id: number;
  message: string;
  isError: boolean;
  undoLabel: string;
  onUndo?: () => void | Promise<void>;
}

const DEFAULT_DURATION_MS = 7_000;

class ToastStore {
  items = $state<ToastItem[]>([]);
  #nextId = 1;
  #timers = new Map<number, ReturnType<typeof setTimeout>>();

  show(
    message: string,
    opts: {
      isError?: boolean;
      undoLabel?: string;
      onUndo?: () => void | Promise<void>;
      durationMs?: number;
    } = {},
  ): number {
    const id = this.#nextId++;
    const item: ToastItem = {
      id,
      message,
      isError: Boolean(opts.isError),
      undoLabel: opts.undoLabel ?? 'Undo',
      onUndo: opts.onUndo,
    };
    // Keep a single crisp active toast in the live region (replacing any prior toast & timer).
    for (const old of this.items) {
      this.pause(old.id);
    }
    this.items = [item];
    this.scheduleDismiss(id, opts.durationMs ?? DEFAULT_DURATION_MS);
    return id;
  }

  scheduleDismiss(id: number, delayMs = DEFAULT_DURATION_MS): void {
    this.pause(id);
    const t = setTimeout(() => {
      this.dismiss(id);
    }, delayMs);
    this.#timers.set(id, t);
  }

  pause(id: number): void {
    const existing = this.#timers.get(id);
    if (existing !== undefined) {
      clearTimeout(existing);
      this.#timers.delete(id);
    }
  }

  dismiss(id: number): void {
    this.pause(id);
    this.items = this.items.filter((t) => t.id !== id);
  }

  async triggerUndo(id: number): Promise<void> {
    const found = this.items.find((t) => t.id === id);
    this.dismiss(id);
    if (found?.onUndo) {
      try {
        await found.onUndo();
      } catch (err) {
        const msg = err instanceof Error && err.message ? err.message : 'Unable to undo action.';
        this.show(msg, { isError: true });
      }
    }
  }
}

export const toast = new ToastStore();
