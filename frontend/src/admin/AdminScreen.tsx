import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import type { Me } from '../auth/me';
import { fetchCurrentPage } from '../shared/pageConfig';
import type { AdminAction, ReviewDoodle, ReviewFilter } from './adminApi';
import { describeChecks, fetchActions, fetchReview, rejectDoodle, restoreDoodle, takenDown } from './adminApi';
import DoodlePreview from './DoodlePreview';

interface Props {
  me: Me | undefined;
  // Shown in the settings drawer over the canvas: a close button instead of the link back.
  onClose?: () => void;
}

type View = ReviewFilter | 'ACTIONS';

const TABS: { filter: View; label: string }[] = [
  { filter: 'HELD', label: '보류' },
  { filter: 'REJECTED', label: '거부' },
  { filter: 'PASSED', label: '통과 게시' },
  { filter: 'UNCHECKED', label: '미검사 게시' },
  { filter: 'ACTIONS', label: '조치 기록' },
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

// The admin tools: the review queue of D-23 (held, rejected, published passed or unchecked doodles) and the
// action log. Opened from the settings button on the canvas, or at /admin.
export default function AdminScreen({ me, onClose }: Props) {
  const [filter, setFilter] = useState<View>('HELD');
  const [items, setItems] = useState<ReviewDoodle[]>([]);
  const [actions, setActions] = useState<AdminAction[]>([]);
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
    const load =
      filter === 'ACTIONS'
        ? fetchActions(0, PAGE_SIZE).then((page) => ({ ...page, items: [], actions: page.items }))
        : fetchReview(filter, 0, PAGE_SIZE).then((page) => ({ ...page, actions: [] }));
    load.then(
      (page) => {
        if (active) {
          setError(null);
          setItems(page.items);
          setActions(page.actions);
          setTotal(page.total);
        }
      },
      (e: unknown) => {
        if (active) {
          setItems([]);
          setActions([]);
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
        <h1>관리자</h1>
        {onClose ? (
          <button type="button" onClick={onClose}>
            닫기
          </button>
        ) : (
          <a href="/">캔버스로</a>
        )}
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
      {filter === 'ACTIONS' && (
        <ul className="admin-log" aria-label="조치 기록">
          {actions.map((a) => (
            <li key={a.id}>
              <time>{new Date(a.createdAt).toLocaleString('ko-KR')}</time> <strong>{a.action}</strong> ({a.actorType}
              {a.actorId ? ` #${a.actorId}` : ''})
              {a.targetDoodleId && ` · 낙서 ${a.targetDoodleId}`}
              {a.targetUserId && ` · 사용자 ${a.targetUserId}`}
              {a.reason && ` · ${a.reason}`}
              {a.detail?.from === 'PUBLISHED' && ' · 게시 중 내림'}
            </li>
          ))}
        </ul>
      )}
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
              {takenDown(item) && (
                <>
                  <dt>조치</dt>
                  <dd>게시된 뒤 관리자가 내림</dd>
                </>
              )}
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
              {item.status === 'PUBLISHED' && (
                <button type="button" disabled={busy !== null} onClick={() => void act(item, 'reject')}>
                  거부하고 내리기
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
