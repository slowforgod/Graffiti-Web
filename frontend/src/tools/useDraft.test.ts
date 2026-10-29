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

  it('keeps a whole width in range for the pen and another for the eraser (D-07)', () => {
    const link = new FakeLink();
    const { result } = renderHook(() => useDraft({ pageId: '3', config: TEST_CONFIG, link }));
    expect(result.current.width).toBe(TEST_CONFIG.defaultStrokeWidth);

    act(() => result.current.setTool('pen'));
    act(() => result.current.setWidth(6.6));
    expect(result.current.width).toBe(7);
    act(() => result.current.setWidth(40));
    expect(result.current.width).toBe(TEST_CONFIG.strokeWidthMax);

    act(() => result.current.setTool('eraser'));
    expect(result.current.width).toBe(TEST_CONFIG.strokeWidthMax);
    act(() => result.current.setWidth(0));
    expect(result.current.width).toBe(TEST_CONFIG.strokeWidthMin);

    act(() => result.current.setTool('pen'));
    expect(result.current.width).toBe(TEST_CONFIG.strokeWidthMax);
  });

  it('draws with the picked pen width and erases as far as half the eraser width', () => {
    const link = new FakeLink();
    vi.spyOn(link, 'connected').mockReturnValue(true);
    const { result } = renderHook(() => useDraft({ pageId: '3', config: TEST_CONFIG, link }));
    act(() => result.current.setTool('pen'));
    act(() => result.current.setWidth(5));
    act(() => result.current.pointerDown({ x: 100, y: 100 }));
    act(() => result.current.pointerUp());
    const drawing = result.current.draft;
    expect(drawing?.kind === 'DRAWING' && drawing.strokes[0]?.w).toBe(5);

    act(() => result.current.setTool('eraser'));
    act(() => result.current.setWidth(2));
    // 1px of eraser reach plus 2.5px of stroke: 4px away is out of reach, 3px is in.
    act(() => result.current.pointerDown({ x: 104, y: 100 }));
    expect(drawing?.kind === 'DRAWING' && drawing.strokes[0]).not.toBeNull();
    act(() => result.current.pointerMove({ x: 103, y: 100 }));
    expect(drawing?.kind === 'DRAWING' && drawing.strokes[0]).toBeNull();
  });
});
