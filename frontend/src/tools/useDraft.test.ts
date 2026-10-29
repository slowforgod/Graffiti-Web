import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TEST_CONFIG } from '../canvas/testConfig';
import { FakeLink } from '../realtime/fakeLink';
import { useDraft } from './useDraft';

describe('useDraft', () => {
  it('opens the tools when the link connected before it started listening', () => {
    const link = new FakeLink();
    // Disconnected on the first render, connected by the time the effect runs: the connect event was missed.
    vi.spyOn(link, 'connected').mockReturnValueOnce(false).mockReturnValue(true);

    const { result } = renderHook(() => useDraft({ pageId: '3', config: TEST_CONFIG, link }));

    expect(result.current.online).toBe(true);
  });

  it('writes a text in the picked font and changes the open text when another is picked (13)', () => {
    const link = new FakeLink();
    vi.spyOn(link, 'connected').mockReturnValue(true);
    const { result } = renderHook(() => useDraft({ pageId: '3', config: TEST_CONFIG, link }));
    expect(result.current.font).toBe('pretendard');

    act(() => result.current.setFont('gowun-dodum'));
    act(() => result.current.setTool('text'));
    act(() => result.current.pointerDown({ x: 100, y: 100 }, 1));
    expect(result.current.draft).toMatchObject({ kind: 'TEXT', f: 'gowun-dodum' });

    act(() => {
      result.current.editText('안녕');
      result.current.setFont('jalnan');
    });
    expect(result.current.draft).toMatchObject({ kind: 'TEXT', text: '안녕', f: 'jalnan' });
    expect(result.current.font).toBe('jalnan');
  });
});
