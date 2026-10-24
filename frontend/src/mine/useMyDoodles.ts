import { useCallback, useState } from 'react';
import { ApiError } from '../api/client';
import type { ClientConfig } from '../shared/pageConfig';
import type { MineItem } from './myDoodles';
import { deleteMine, fetchMine, loadDoodles } from './myDoodles';
import { savePng } from './png';

interface Options {
  pageId: string;
  seq: number;
  config: ClientConfig;
  // Takes deleted doodles off the screen before their doodle:removed arrives.
  onDeleted: (ids: string[]) => void;
  onPageEnded: () => void;
}

const LOAD_FAILED = '내 낙서를 불러오지 못했어요.';
const DELETE_FAILED = '지우지 못했어요. 잠시 후 다시 시도해 주세요.';
const SAVE_FAILED = 'PNG를 만들지 못했어요.';
const NOTHING_TO_SAVE = '저장할 낙서가 없어요.';

// The "내 낙서" panel (D-13): the author's doodles of this page, a selection made on the canvas, deleting the
// selection (all or none) and saving them as a PNG made in the browser.
export function useMyDoodles({ pageId, seq, config, onDeleted, onPageEnded }: Options) {
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<MineItem[] | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const fail = useCallback(
    (e: unknown, fallback: string) => {
      if (e instanceof ApiError && e.code === 'PAGE_ENDED') {
        onPageEnded();
        return;
      }
      setNotice(e instanceof ApiError && e.message ? e.message : fallback);
    },
    [onPageEnded],
  );

  const show = useCallback(async () => {
    setOpen(true);
    setItems(null);
    setSelected(new Set());
    setConfirming(false);
    setNotice(null);
    try {
      setItems(await fetchMine(pageId));
    } catch (e) {
      setItems([]);
      fail(e, LOAD_FAILED);
    }
  }, [pageId, fail]);

  const close = useCallback(() => {
    setOpen(false);
    setItems(null);
    setSelected(new Set());
    setConfirming(false);
    setNotice(null);
  }, []);

  const toggle = useCallback((id: string) => {
    setConfirming(false);
    setSelected((s) => {
      const next = new Set(s);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });
  }, []);

  const selectAll = useCallback(() => setSelected(new Set((items ?? []).map((i) => i.id))), [items]);
  const clearSelection = useCallback(() => {
    setSelected(new Set());
    setConfirming(false);
  }, []);

  const remove = useCallback(async () => {
    const ids = (items ?? []).map((i) => i.id).filter((id) => selected.has(id));
    if (ids.length === 0 || busy) {
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      await deleteMine(ids);
      setItems((list) => (list ?? []).filter((i) => !selected.has(i.id)));
      setSelected(new Set());
      onDeleted(ids);
      setNotice(`${ids.length}개를 지웠어요.`);
    } catch (e) {
      fail(e, DELETE_FAILED);
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  }, [items, selected, busy, onDeleted, fail]);

  // The selection when there is one, else every doodle of the author on this page.
  const save = useCallback(async () => {
    const all = items ?? [];
    const chosen = selected.size > 0 ? all.filter((i) => selected.has(i.id)) : all;
    if (busy) {
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      const doodles = await loadDoodles(pageId, chosen, config);
      if (!(await savePng(doodles, config.palette, config, `내-낙서-${seq}장.png`))) {
        setNotice(NOTHING_TO_SAVE);
      }
    } catch (e) {
      fail(e, SAVE_FAILED);
    } finally {
      setBusy(false);
    }
  }, [items, selected, busy, pageId, config, seq, fail]);

  return {
    open,
    items,
    selected,
    confirming,
    busy,
    notice,
    show,
    close,
    toggle,
    selectAll,
    clearSelection,
    askDelete: () => setConfirming(true),
    cancelDelete: () => setConfirming(false),
    remove,
    save,
    clearNotice: () => setNotice(null),
  };
}

export type MyDoodles = ReturnType<typeof useMyDoodles>;
