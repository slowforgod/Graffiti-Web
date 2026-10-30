import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

// The canvas has its own tests; here it would only add requests to the mocked fetch.
vi.mock('./canvas/CanvasScreen', () => ({ default: () => null }));

const signedIn = {
  authenticated: true,
  id: '42',
  nickname: '익명의 펭귄 #3842',
  cursorColor: '#1E88E5',
  role: 'USER',
  banned: false,
  cooldownEndsAt: null,
};

function reply(body: unknown, status = 200): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}

describe('App', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
    window.history.replaceState(null, '', '/');
  });

  it('renders the Korean service title', async () => {
    fetchMock.mockResolvedValue(reply({ authenticated: false }));
    render(<App />);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('경희대 사랑의 낙서장');
    await screen.findByRole('link', { name: '경희대 계정으로 로그인' });
  });

  it('shows the settings button to admins only and opens the admin tools from it', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/me') return reply({ ...signedIn, role: 'ADMIN', consentRequired: false });
      if (url.startsWith('/api/admin/doodles')) return reply({ items: [], page: 0, size: 20, total: 0 });
      return reply({ error: { code: 'NOT_FOUND', message: '' } }, 404);
    });
    render(<App />);

    const settings = await screen.findByRole('button', { name: '관리자 설정' });
    expect(screen.queryByRole('link', { name: '관리자 검토' })).not.toBeInTheDocument();
    await userEvent.click(settings);
    const drawer = screen.getByRole('dialog', { name: '관리자 설정' });
    expect(drawer).toHaveTextContent('보류');
    expect(drawer).toHaveTextContent('조치 기록');
    await userEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog', { name: '관리자 설정' })).not.toBeInTheDocument();
  });

  it('hides the settings button from other users', async () => {
    fetchMock.mockResolvedValue(reply({ ...signedIn, consentRequired: false }));
    render(<App />);
    await screen.findByText('익명의 펭귄 #3842');
    expect(screen.queryByRole('button', { name: '관리자 설정' })).not.toBeInTheDocument();
  });

  it('offers Google login when signed out', async () => {
    fetchMock.mockResolvedValue(reply({ authenticated: false }));
    render(<App />);

    const link = await screen.findByRole('link', { name: '경희대 계정으로 로그인' });
    expect(link).toHaveAttribute('href', '/oauth2/authorization/google');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows the consent screen until the user agrees', async () => {
    fetchMock
      .mockResolvedValueOnce(reply({ ...signedIn, consentRequired: true }))
      .mockResolvedValueOnce(reply({ ...signedIn, consentRequired: false }));
    render(<App />);

    const dialog = await screen.findByRole('dialog', { name: '개인정보 수집·이용 동의' });
    expect(dialog).toHaveTextContent('부적절한 낙서 판별 모델 개선');
    await userEvent.click(screen.getByRole('button', { name: '동의하고 시작하기' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(fetchMock).toHaveBeenLastCalledWith('/api/me/consent', expect.objectContaining({ method: 'POST' }));
    expect(screen.getByText('익명의 펭귄 #3842')).toBeInTheDocument();
  });

  it('keeps the consent screen and shows an error when saving fails', async () => {
    fetchMock
      .mockResolvedValueOnce(reply({ ...signedIn, consentRequired: true }))
      .mockResolvedValueOnce(reply({ error: { code: 'INTERNAL', message: '오류' } }, 500));
    render(<App />);

    await userEvent.click(await screen.findByRole('button', { name: '동의하고 시작하기' }));

    expect(await screen.findByText('동의를 저장하지 못했어요. 다시 시도해 주세요.')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('logs out and returns to the signed-out bar', async () => {
    fetchMock
      .mockResolvedValueOnce(reply({ ...signedIn, consentRequired: false }))
      .mockResolvedValueOnce(reply(undefined, 204))
      .mockResolvedValueOnce(reply({ authenticated: false }));
    render(<App />);

    await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }));

    expect(await screen.findByRole('link', { name: '경희대 계정으로 로그인' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST' }));
  });

  it('explains a non-school login and clears the query', async () => {
    window.history.replaceState(null, '', '/?loginError=DOMAIN_NOT_ALLOWED');
    fetchMock.mockResolvedValue(reply({ authenticated: false }));
    render(<App />);

    expect(screen.getByRole('alert')).toHaveTextContent('경희대학교 계정으로만 로그인할 수 있어요.');
    expect(window.location.search).toBe('');
    await screen.findByRole('link', { name: '경희대 계정으로 로그인' });
  });
});
