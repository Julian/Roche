export type QuickLogTab = 'media' | 'url';

class ModalsStore {
  quickLogOpen = $state(false);
  quickLogTab = $state<QuickLogTab>('media');
  shortcutsOpen = $state(false);

  openQuickLog(tab: QuickLogTab = 'media'): void {
    this.shortcutsOpen = false;
    this.quickLogTab = tab;
    this.quickLogOpen = true;
  }

  closeQuickLog(): void {
    this.quickLogOpen = false;
  }

  openShortcuts(): void {
    this.quickLogOpen = false;
    this.shortcutsOpen = true;
  }

  toggleShortcuts(): void {
    if (this.shortcutsOpen) {
      this.shortcutsOpen = false;
    } else {
      this.openShortcuts();
    }
  }

  closeShortcuts(): void {
    this.shortcutsOpen = false;
  }

  closeAll(): void {
    this.quickLogOpen = false;
    this.shortcutsOpen = false;
  }

  get anyOpen(): boolean {
    return this.quickLogOpen || this.shortcutsOpen;
  }
}

export const modals = new ModalsStore();
