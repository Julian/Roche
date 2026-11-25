// Typed fetch wrapper with automatic X-Roche-Csrf header injection,
// RFC 9457 Problem Details error handling, and single-retry CSRF recovery.

export class ApiError extends Error {
  readonly status: number;
  readonly problemType: string;
  readonly title: string;
  readonly retryAfterSeconds: number | null;
  readonly candidates: string[] | null;

  constructor(opts: {
    status: number;
    message: string;
    problemType?: string;
    title?: string;
    retryAfterSeconds?: number | null;
    candidates?: string[] | null;
  }) {
    super(opts.message);
    this.name = 'ApiError';
    this.status = opts.status;
    this.problemType = opts.problemType ?? 'about:blank';
    this.title = opts.title ?? 'Request Failed';
    this.retryAfterSeconds = opts.retryAfterSeconds ?? null;
    this.candidates = opts.candidates ?? null;
  }
}

interface AuthBridge {
  getCsrfToken: () => string | null;
  refreshCsrfToken: () => Promise<string | null>;
  onUnauthorized: () => void;
}

let bridge: AuthBridge = {
  getCsrfToken: () => null,
  refreshCsrfToken: async () => null,
  onUnauthorized: () => {},
};

export function configureApiAuthBridge(next: AuthBridge): void {
  bridge = next;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  rawBody?: string;
  contentType?: string;
  keepalive?: boolean;
  skipUnauthorizedHandler?: boolean;
  signal?: AbortSignal;
}

async function parseErrorResponse(res: Response): Promise<ApiError> {
  const retryHdr = res.headers.get('retry-after');
  const retryAfterHdr = retryHdr ? Number.parseInt(retryHdr, 10) : null;
  const text = await res.text().catch(() => '');
  if (text) {
    try {
      const parsed = JSON.parse(text) as {
        detail?: string;
        title?: string;
        type?: string;
        retry_after_seconds?: number;
        candidates?: string[];
      };
      const message = parsed.detail || parsed.title || `Request failed (${res.status})`;
      return new ApiError({
        status: res.status,
        message,
        problemType: parsed.type,
        title: parsed.title,
        retryAfterSeconds:
          typeof parsed.retry_after_seconds === 'number'
            ? parsed.retry_after_seconds
            : Number.isFinite(retryAfterHdr)
              ? retryAfterHdr
              : null,
        candidates: Array.isArray(parsed.candidates) ? parsed.candidates : null,
      });
    } catch {
      // Non-JSON error body
    }
  }
  return new ApiError({
    status: res.status,
    message: text.trim() || res.statusText || `Request failed (${res.status})`,
    retryAfterSeconds: Number.isFinite(retryAfterHdr) ? retryAfterHdr : null,
  });
}

async function performRequest<T>(
  path: string,
  opts: RequestOptions = {},
  isCsrfRetry = false,
): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers = new Headers();
  headers.set('Accept', 'application/json');

  const isMutating = method !== 'GET';
  if (isMutating) {
    const csrf = bridge.getCsrfToken();
    if (csrf) {
      headers.set('X-Roche-Csrf', csrf);
    }
  }

  let body: BodyInit | undefined;
  if (opts.rawBody !== undefined) {
    body = opts.rawBody;
    headers.set('Content-Type', opts.contentType ?? 'text/plain; charset=utf-8');
  } else if (opts.body !== undefined) {
    body = JSON.stringify(opts.body);
    headers.set('Content-Type', opts.contentType ?? 'application/json');
  }

  const res = await fetch(path, {
    method,
    headers,
    body,
    credentials: 'same-origin',
    keepalive: Boolean(opts.keepalive),
    signal: opts.signal,
  });

  if (res.status === 403 && isMutating && !isCsrfRetry) {
    const err = await parseErrorResponse(res);
    if (/csrf/i.test(err.message)) {
      const fresh = await bridge.refreshCsrfToken();
      if (fresh) {
        return performRequest<T>(path, opts, true);
      }
    }
    throw err;
  }

  if (res.status === 401) {
    const err = await parseErrorResponse(res);
    if (!opts.skipUnauthorizedHandler) {
      bridge.onUnauthorized();
    }
    throw err;
  }

  if (!res.ok) {
    throw await parseErrorResponse(res);
  }

  if (res.status === 204) {
    return undefined as T;
  }

  const ct = res.headers.get('content-type') ?? '';
  if (ct.includes('json')) {
    return (await res.json()) as T;
  }
  return (await res.text()) as unknown as T;
}

export const api = {
  get<T>(path: string, signal?: AbortSignal): Promise<T> {
    return performRequest<T>(path, { method: 'GET', signal });
  },
  post<T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>): Promise<T> {
    return performRequest<T>(path, { ...opts, method: 'POST', body });
  },
  patch<T>(path: string, body?: unknown, opts?: Omit<RequestOptions, 'method' | 'body'>): Promise<T> {
    return performRequest<T>(path, { ...opts, method: 'PATCH', body });
  },
  delete<T = void>(path: string, opts?: Omit<RequestOptions, 'method'>): Promise<T> {
    return performRequest<T>(path, { ...opts, method: 'DELETE' });
  },
};

export function sendProgressBeacon(
  itemId: string,
  progress: number,
  source: 'reader' | 'audio' | null = null,
  keepalive = false,
): void {
  if (!itemId || !Number.isFinite(progress)) return;
  const clamped = Math.max(0, Math.min(1, progress));
  const payload: Record<string, unknown> = {
    read_progress: Number(clamped.toFixed(4)),
  };
  if (source) {
    payload.progress_source = source;
  }
  api
    .patch(`/api/v1/items/${encodeURIComponent(itemId)}`, payload, {
      keepalive,
      skipUnauthorizedHandler: true,
    })
    .catch(() => {});
}

export function buildQuery(params: Record<string, string | number | boolean | null | undefined>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === '') continue;
    sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}
