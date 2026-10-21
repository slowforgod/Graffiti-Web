import { apiFetch } from '../api/client';

export type Me =
  | { authenticated: false }
  | {
      authenticated: true;
      id: string;
      nickname: string;
      cursorColor: string;
      role: 'USER' | 'ADMIN';
      consentRequired: boolean;
      banned: boolean;
      cooldownEndsAt: string | null;
    };

export const LOGIN_URL = '/oauth2/authorization/google';

export function fetchMe(): Promise<Me> {
  return apiFetch<Me>('/api/me');
}

export function consent(): Promise<Me> {
  return apiFetch<Me>('/api/me/consent', { method: 'POST' });
}

export function logout(): Promise<void> {
  return apiFetch<void>('/api/auth/logout', { method: 'POST' });
}
