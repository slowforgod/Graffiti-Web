import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import type { Me } from '../auth/me';
import { fetchCurrentPage } from '../shared/pageConfig';
import type { ReviewDoodle, ReviewFilter } from './adminApi';
import { describeChecks, fetchReview, rejectDoodle, restoreDoodle } from './adminApi';
import DoodlePreview from './DoodlePreview';

interface Props {
  me: Me | undefined;
}

const TABS: { filter: ReviewFilter; label: string }[] = [
  { filter: 'HELD', label: '보류' },
  { filter: 'REJECTED', label: '거부' },
  { filter: 'UNCHECKED', label: '미검사 게시' },
];

const PREVIEW_PX = 160;
const PAGE_SIZE = 20;

const ERRORS: Record<string, string> = {
  PAGE_ENDED: '이미 끝난 페이지의 낙서라 게시할 수 없어요.',
  STATE_CONFLICT: '이미 다른 처리가 끝났어요. 목록을 새로고침해 주세요.',
};

function message(e: unknown): string {
  if (e instanceof ApiError) {
    return ERRORS[e.code] ?? (e.message || '처리하지 못했어요.');
  }
  return '처리하지 못했어요.';
}

// /admin: the review queue of D-23 (held, rejected, published unchecked doodles).
export default function AdminScreen({ me }: Props) {
  const [filter, setFilter] = useState<ReviewFilter>('HELD');
  const [items, setItems] = useState<ReviewDoodle[]>([]);
  const [total, setTotal] = useState(0);
  const [palette, setPalette] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const isAdmin = me?.authenticated === true && me.role === 'ADMIN';

  // Bumped to load the list again (tab change or the refresh button).
  const [reload, setReload] = useState(0);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    let active = true;
    fetchReview(filter, 0, PAGE_SIZE).then(
      (page) => {
        if (active) {
          setError(null);
          setItems(page.items);
          setTotal(page.total);
        }
      },
      (e: unknown) => {
        if (active) {
          setItems([]);
          setTotal(0);
          setError(message(e));
        }
      },
    );
    return () => {
      active = false;
    };
  }, [isAdmin, filter, reload]);

  useEffect(() => {
    if (!isAdmin) {
      return;
    }
    let active = true;
    fetchCurrentPage().then(
      (page) => {
        if (active) {
          setPalette(page.config.palette);
        }
      },
      () => {
        if (active) {
          setError('캔버스 설정을 불러오지 못했어요.');
        }
      },
    );
    return () => {
      active = false;
    };
  }, [isAdmin]);

  const act = async (item: ReviewDoodle, action: 'restore' | 'reject') => {
    setBusy(item.id);
    setError(null);
    try {
      await (action === 'restore' ? restoreDoodle(item.id) : rejectDoodle(item.id));
      setItems((list) => list.filter((d) => d.id !== item.id));
      setTotal((n) => Math.max(0, n - 1));
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(null);
    }
  };

  if (!me) {
    return <p className="admin-screen">불러오는 중…</p>;
  }
  if (!isAdmin) {
    return (
      <section className="admin-screen">
        <p role="alert">관리자만 볼 수 있어요.</p>
        <a href="/">캔버스로 돌아가기</a>
      </section>
    );
  }

  return (
    <section className="admin-screen">
      <header className="admin-header">
        <h1>관리자 검토</h1>
        <a href="/">캔버스로</a>
      </header>
      <nav className="admin-tabs" aria-label="검토 목록">
        {TABS.map((t) => (
          <button key={t.filter} type="button" aria-pressed={filter === t.filter} onClick={() => setFilter(t.filter)}>
            {t.label}
          </button>
        ))}
        <button type="button" onClick={() => setReload((n) => n + 1)}>
          새로고침
        </button>
      </nav>
      {error && <p role="alert">{error}</p>}
      <p className="admin-count">{total}개</p>
      <ul className="admin-list" aria-label="낙서 목록">
        {items.map((item) => (
          <li key={item.id} className="admin-item">
            {palette.length > 0 && <DoodlePreview doodle={item} palette={palette} maxSize={PREVIEW_PX} />}
            <dl>
              <dt>작성자</dt>
              <dd>
                {item.author.nickname} ({item.author.email})
              </dd>
              <dt>제출</dt>
              <dd>{new Date(item.submittedAt).toLocaleString('ko-KR')}</dd>
              <dt>페이지</dt>
              <dd>
                {item.pageId}
                {item.pageEnded ? ' (끝난 페이지)' : ''}
              </dd>
              <dt>검사</dt>
              <dd>{describeChecks(item.moderationDetail)}</dd>
            </dl>
            <div className="admin-actions">
              {(item.status === 'HELD' || item.status === 'REJECTED') && (
                <button
                  type="button"
                  disabled={busy !== null || item.pageEnded}
                  onClick={() => void act(item, 'restore')}
                >
                  {item.status === 'HELD' ? '게시' : '복구해서 게시'}
                </button>
              )}
              {item.status === 'HELD' && (
                <button type="button" disabled={busy !== null} onClick={() => void act(item, 'reject')}>
                  거부
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
