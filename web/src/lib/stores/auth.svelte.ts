import { api, configureApiAuthBridge } from '../api/client';
import type { SessionStatus } from '../api/types';
import { router } from '../router.svelte';
import { player } from './player.svelte';
import { refresh } from './refresh.svelte';

class AuthStore {
  initialized = $state(false);
  authenticated = $state(false);
  csrfToken = $state<string | null>(null);
  tailscaleEnabled = $state(false);
  tailscaleUser = $state<string | null>(null);
  sessionExpiredBanner = $state(false);

  constructor() {
    configureApiAuthBridge({
      getCsrfToken: () => this.csrfToken,
      refreshCsrfToken: () => this.refreshSessionCsrf(),
      onUnauthorized: () => this.handleUnauthorized(),
    });
  }

  async bootstrap(): Promise<SessionStatus> {
    try {
      const status = await api.get<SessionStatus>('/api/v1/auth/session');
      this.applyStatus(status);
      return status;
    } catch {
      this.authenticated = false;
      this.csrfToken = null;
      this.initialized = true;
      return {
        authenticated: false,
        csrf_token: null,
        tailscale_enabled: false,
        tailscale_user: null,
        is_refreshing: false,
        active_refresh_count: 0,
        unread_items_count: 0,
      };
    }
  }

  private applyStatus(status: SessionStatus): void {
    this.authenticated = status.authenticated;
    this.csrfToken = status.csrf_token;
    this.tailscaleEnabled = status.tailscale_enabled;
    this.tailscaleUser = status.tailscale_user;
    this.initialized = true;
    if (status.authenticated) {
      this.sessionExpiredBanner = false;
    }
  }

  async refreshSessionCsrf(): Promise<string | null> {
    try {
      const status = await api.get<SessionStatus>('/api/v1/auth/session');
      this.applyStatus(status);
      return status.csrf_token;
    } catch {
      return null;
    }
  }

  handleUnauthorized(): void {
    this.authenticated = false;
    this.csrfToken = null;
    if (player.hasActiveTrack) {
      // Keep persistent audio playback uninterrupted; surface inline sign-in banner.
      this.sessionExpiredBanner = true;
      return;
    }
    if (router.pathname !== '/login') {
      const returnTo = encodeURIComponent(router.fullPath);
      router.navigate(`/login?return_to=${returnTo}`, { replace: true });
    }
  }

  async loginWithPairingCode(
    pairingCode: string,
    deviceName?: string,
  ): Promise<void> {
    const res = await api.post<{ authenticated: boolean; csrf_token: string }>(
      '/api/v1/auth/login',
      {
        pairing_code: pairingCode,
        device_name: deviceName?.trim() || undefined,
      },
      { skipUnauthorizedHandler: true },
    );
    this.authenticated = res.authenticated;
    this.csrfToken = res.csrf_token;
    this.sessionExpiredBanner = false;
    this.initialized = true;
  }

  async loginWithTailscale(): Promise<void> {
    const res = await api.post<{ authenticated: boolean; csrf_token: string }>(
      '/api/v1/auth/login/tailscale',
      {},
      { skipUnauthorizedHandler: true },
    );
    this.authenticated = res.authenticated;
    this.csrfToken = res.csrf_token;
    this.sessionExpiredBanner = false;
    this.initialized = true;
  }

  async logout(): Promise<void> {
    try {
      await api.post<void>('/api/v1/auth/logout', undefined, {
        skipUnauthorizedHandler: true,
      });
    } finally {
      this.authenticated = false;
      this.csrfToken = null;
      this.sessionExpiredBanner = false;
      refresh.stopPolling();
      player.close();
      router.navigate('/login', { replace: true });
    }
  }
}

export const auth = new AuthStore();
