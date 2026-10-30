import { apiFetch } from '../api/client';
import type { Doodle } from '../canvas/doodles';

// The admin review screen (D-23). nginx serves index.html for it like any other path.
export const ADMIN_PATH = '/admin';

// API.md §4, review part. Admins see the author (D-22); the detail lists check names and outcomes only.
export type ReviewFilter = 'HELD' | 'REJECTED' | 'PASSED' | 'UNCHECKED';

export interface CheckEntry {
  check: string;
  outcome: 'PASS' | 'BLOCK' | 'REVIEW' | 'ERROR' | 'SKIPPED';
  category?: string;
  note?: string;
}

export interface ModerationDetail {
  checks?: CheckEntry[];
  ml?: CheckEntry & { mode: string };
  heldAsUnchecked?: boolean;
}

export type ReviewDoodle = Doodle & {
  pageId: string;
  pageEnded: boolean;
  status: string;
  moderationState: string;
  moderationDetail: ModerationDetail | null;
  submittedAt: string;
  author: { id: string; nickname: string; email: string };
};

export interface ReviewPage {
  items: ReviewDoodle[];
  page: number;
  size: number;
  total: number;
}

export function fetchReview(filter: ReviewFilter, page = 0, size = 20): Promise<ReviewPage> {
  return apiFetch<ReviewPage>(`/api/admin/doodles?moderation=${filter}&page=${page}&size=${size}`);
}

export function restoreDoodle(id: string): Promise<{ doodle: Doodle }> {
  return apiFetch(`/api/admin/doodles/${encodeURIComponent(id)}/restore`, { method: 'POST' });
}

// A REJECTED doodle whose AI verdict let it onto the canvas: an admin took it down after it was published.
export function takenDown(d: Pick<ReviewDoodle, 'status' | 'moderationState'>): boolean {
  return d.status === 'REJECTED' && (d.moderationState === 'PASSED' || d.moderationState === 'UNCHECKED');
}

// HELD → REJECTED, or PUBLISHED → REJECTED (off the canvas, the row stays).
export function rejectDoodle(id: string): Promise<{ id: string; status: string }> {
  return apiFetch(`/api/admin/doodles/${encodeURIComponent(id)}/reject`, { method: 'POST' });
}

// The admin action log (SEC-14), newest first.
export interface AdminAction {
  id: string;
  actorType: string;
  actorId: string | null;
  action: string;
  targetDoodleId: string | null;
  targetUserId: string | null;
  reason: string | null;
  // { from: 'PUBLISHED' } when an admin REJECT took a published doodle off the canvas.
  detail: { from?: string } | null;
  createdAt: string;
}

export function fetchActions(page = 0, size = 50): Promise<{ items: AdminAction[]; total: number }> {
  return apiFetch(`/api/admin/actions?page=${page}&size=${size}`);
}

// One line for the card, e.g. "word-filter 통과 · chatkhu 미구현".
const OUTCOME: Record<CheckEntry['outcome'], string> = {
  PASS: '통과',
  BLOCK: '차단',
  REVIEW: '애매',
  ERROR: '실패',
  SKIPPED: '미구현',
};

export function describeChecks(detail: ModerationDetail | null): string {
  if (!detail) {
    return '검사 기록 없음';
  }
  const entries = [...(detail.checks ?? []), ...(detail.ml ? [{ ...detail.ml, check: `${detail.ml.check}(${detail.ml.mode})` }] : [])];
  const parts = entries.map((e) => {
    const extra = e.category ?? e.note;
    return `${e.check} ${OUTCOME[e.outcome] ?? e.outcome}${extra ? `(${extra})` : ''}`;
  });
  if (detail.heldAsUnchecked) {
    parts.push('실제 검사 없음 → 보류');
  }
  return parts.length > 0 ? parts.join(' · ') : '검사 기록 없음';
}
