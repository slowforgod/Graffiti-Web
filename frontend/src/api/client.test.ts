import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from './client';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), { status });
}

describe('apiFetch', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    document.cookie = 'XSRF-TOKEN=token%2Fvalue';
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('adds the CSRF header to state-changing requests', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ok: true }));

    await apiFetch('/api/me/consent', { method: 'post' });

    const init = fetchMock.mock.calls[0]?.[1];
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('token/value');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('same-origin');
  });

  it('does not add the CSRF header to GET', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { authenticated: false }));

    const body = await apiFetch('/api/me');

    const init = fetchMock.mock.calls[0]?.[1];
    expect(new Headers(init?.headers).has('X-XSRF-TOKEN')).toBe(false);
    expect(body).toEqual({ authenticated: false });
  });

  it('returns undefined for an empty body', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(apiFetch('/api/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
  });

  it('throws ApiError with the server code and message', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(403, { error: { code: 'CSRF_INVALID', message: '요청이 만료됐어요.' } }),
    );

    const error = await apiFetch('/api/me/consent', { method: 'POST' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: 'CSRF_INVALID', message: '요청이 만료됐어요.' });
  });

  it('falls back to INTERNAL when the error body is not ours', async () => {
    fetchMock.mockResolvedValue(jsonResponse(502, undefined));

    await expect(apiFetch('/api/me')).rejects.toMatchObject({ status: 502, code: 'INTERNAL' });
  });
});
