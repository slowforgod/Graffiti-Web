// fetch wrapper for the same-origin backend (D-02). State-changing requests carry the CSRF token
// from the XSRF-TOKEN cookie in the X-XSRF-TOKEN header (SEC-03).

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(status: number, code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const match = document.cookie.split('; ').find((c) => c.startsWith(prefix));
  return match === undefined ? undefined : decodeURIComponent(match.slice(prefix.length));
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (!SAFE_METHODS.has(method)) {
    const token = readCookie(CSRF_COOKIE);
    if (token !== undefined) {
      headers.set(CSRF_HEADER, token);
    }
  }
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(path, { ...init, method, headers, credentials: 'same-origin' });
  const text = await res.text();
  const body: unknown = text === '' ? undefined : JSON.parse(text);
  if (!res.ok) {
    throw toApiError(res.status, body);
  }
  return body as T;
}

function toApiError(status: number, body: unknown): ApiError {
  const error = (body as { error?: { code?: string; message?: string; details?: Record<string, unknown> } })
    ?.error;
  if (error?.code) {
    return new ApiError(status, error.code, error.message ?? '', error.details ?? {});
  }
  return new ApiError(status, 'INTERNAL', '일시적인 오류가 발생했어요.');
}
