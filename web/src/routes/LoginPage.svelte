<script lang="ts">
  import { ApiError } from '../lib/api/client';
  import { router } from '../lib/router.svelte';
  import { auth } from '../lib/stores/auth.svelte';
  import { safeLocalReturnTo } from '../lib/utils/format';

  let pairingCode = $state('');
  let deviceName = $state('');
  let showPassword = $state(false);
  let submitting = $state(false);
  let errorMsg = $state<string | null>(null);

  let returnTo = $derived.by(() => {
    const raw = router.route.query.get('return_to');
    const clean = safeLocalReturnTo(raw, '/');
    const path = clean.split(/[?#]/)[0] ?? '/';
    if (path === '/login' || path.startsWith('/api/')) return '/';
    return clean;
  });

  $effect(() => {
    router.setTitle('Sign in');
  });

  async function handleSubmit(e: SubmitEvent): Promise<void> {
    e.preventDefault();
    const code = pairingCode.trim();
    if (!code) {
      errorMsg = 'Enter a pairing code from `roche pair`.';
      return;
    }
    submitting = true;
    errorMsg = null;
    try {
      await auth.loginWithPairingCode(code, deviceName);
      router.navigate(returnTo, { replace: true });
    } catch (err) {
      if (err instanceof ApiError) {
        errorMsg = err.retryAfterSeconds
          ? `${err.message} (retry in ${err.retryAfterSeconds}s)`
          : err.message;
      } else {
        errorMsg = 'Unable to sign in. Please try again.';
      }
    } finally {
      submitting = false;
    }
  }

  async function handleTailscaleLogin(): Promise<void> {
    submitting = true;
    errorMsg = null;
    try {
      await auth.loginWithTailscale();
      router.navigate(returnTo, { replace: true });
    } catch (err) {
      errorMsg = err instanceof ApiError ? err.message : 'Tailscale sign-in failed.';
    } finally {
      submitting = false;
    }
  }
</script>

<main id="roche-main" class="login-shell" tabindex="-1">
  <div class="login-box">
    <h1 class="login-brand">Roche</h1>
    <p class="lede">Sign in to your personal reading, listening, and media library.</p>

    {#if errorMsg}
      <div id="login-error" class="notice notice-error" role="alert">{errorMsg}</div>
    {/if}

    {#if auth.tailscaleUser}
      <div class="tailscale-box">
        <p class="meta mb-sm">
          Signed in to Tailscale as <strong>{auth.tailscaleUser}</strong>.
        </p>
        <button
          type="button"
          class="btn btn-primary w-full"
          disabled={submitting}
          onclick={handleTailscaleLogin}
        >
          Continue with Tailscale
        </button>
      </div>
      <hr class="login-sep" />
    {/if}

    <form class="rail-form" onsubmit={handleSubmit}>
      <div class="field">
        <label for="pairing_code">Pairing Code</label>
        <div class="password-wrap">
          <input
            id="pairing_code"
            name="pairing_code"
            class="pairing-code-input"
            type={showPassword ? 'text' : 'password'}
            required
            autocomplete="one-time-code"
            placeholder="Run `roche pair` on the server"
            aria-invalid={errorMsg ? 'true' : undefined}
            aria-describedby={errorMsg ? 'login-error' : undefined}
            bind:value={pairingCode}
          />
          <button
            type="button"
            class="btn btn-small btn-ghost"
            aria-pressed={showPassword}
            onclick={() => {
              showPassword = !showPassword;
            }}
          >
            {showPassword ? 'Hide' : 'Show'}
          </button>
        </div>
      </div>

      <div class="field">
        <label for="device_name">Browser Label (optional)</label>
        <input
          id="device_name"
          name="device_name"
          type="text"
          autocomplete="off"
          placeholder="Roche Web"
          bind:value={deviceName}
        />
      </div>

      <button type="submit" class="btn btn-primary w-full" disabled={submitting}>
        {submitting ? 'Signing in…' : 'Sign in'}
      </button>
    </form>

    {#if auth.tailscaleEnabled && !auth.tailscaleUser}
      <p class="meta login-tip">
        Tip: access Roche over your Tailscale Serve URL to enable automatic Tailscale identity sign-in.
      </p>
    {/if}
  </div>
</main>

<style>
  .login-shell {
    min-height: 70vh;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .login-box {
    width: 100%;
    max-width: 26rem;
    background: var(--paper-deep);
    border: 1px solid var(--rule);
    border-radius: 3px;
    padding: 1.75rem 1.85rem;
    box-shadow: 0 12px 32px var(--shadow-art);
  }

  .login-brand {
    font-family: var(--display);
    font-style: italic;
    font-weight: 400;
    font-size: 2.15rem;
    margin: 0 0 0.25rem;
    line-height: 1.1;
  }

  .tailscale-box {
    margin-bottom: 1rem;
  }

  .login-sep {
    border: none;
    border-top: 1px solid var(--rule);
    margin: 1rem 0;
  }

  .password-wrap {
    display: flex;
    gap: 0.4rem;
    align-items: stretch;
  }

  .pairing-code-input {
    flex: 1;
    min-width: 0;
    font-family: var(--mono);
  }

  .login-tip {
    margin-top: 1.25rem;
    margin-bottom: 0;
    font-size: 0.82rem;
  }
</style>
