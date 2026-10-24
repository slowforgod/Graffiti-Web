import type { MyDoodles } from '../mine/useMyDoodles';

interface Props {
  mine: MyDoodles;
  // Banned authors may still look at and save their doodles, but not delete them (API.md §3).
  canDelete: boolean;
}

// The "내 낙서" panel (D-13). Doodles are picked on the canvas; the panel acts on the selection.
export default function MyDoodlesPanel({ mine, canDelete }: Props) {
  const count = mine.items?.length ?? 0;
  const picked = mine.selected.size;
  return (
    <section className="mine-panel" aria-label="내 낙서">
      <header className="mine-header">
        <strong>내 낙서</strong>
        <button type="button" onClick={mine.close}>
          닫기
        </button>
      </header>
      {mine.items === null ? (
        <p>불러오는 중이에요…</p>
      ) : count === 0 ? (
        <p>이 페이지에 게시한 낙서가 없어요.</p>
      ) : (
        <>
          <p>
            이 페이지에 내 낙서가 {count}개 있어요. 캔버스에서 눌러 고르세요.
            {picked > 0 && ` (${picked}개 고름)`}
          </p>
          <div className="mine-actions">
            <button type="button" onClick={picked === count ? mine.clearSelection : mine.selectAll}>
              {picked === count ? '고르기 취소' : '모두 고르기'}
            </button>
            {canDelete && (
              <button type="button" disabled={picked === 0 || mine.busy} onClick={mine.askDelete}>
                고른 낙서 지우기
              </button>
            )}
            <button type="button" disabled={mine.busy} onClick={() => void mine.save()}>
              {picked > 0 ? '고른 낙서 PNG로 저장' : '모두 PNG로 저장'}
            </button>
          </div>
        </>
      )}
      {mine.confirming && picked > 0 && (
        <div className="mine-confirm" role="alertdialog" aria-label="지우기 확인">
          <p>고른 낙서 {picked}개를 지울까요? 지운 낙서는 되돌릴 수 없고, 게시 대기 시간도 돌아오지 않아요.</p>
          <button type="button" disabled={mine.busy} onClick={() => void mine.remove()}>
            지우기
          </button>
          <button type="button" onClick={mine.cancelDelete}>
            그만두기
          </button>
        </div>
      )}
      {mine.notice && (
        <p role="status" className="mine-notice" onClick={mine.clearNotice}>
          {mine.notice}
        </p>
      )}
    </section>
  );
}
