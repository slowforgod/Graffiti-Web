import { renderHook } from '@testing-library/react';
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
});
