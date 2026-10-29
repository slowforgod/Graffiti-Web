import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TextDraftEditor from './TextDraftEditor';

const draft = { id: 't', kind: 'TEXT' as const, text: '너가 뭘 원하는지', c: 0, f: 'jalnan', size: 40, x: 100, y: 200, w: 300, h: 40 };

describe('TextDraftEditor', () => {
  it('leaves room above and below the line so tall letters are not cut off', () => {
    render(<TextDraftEditor draft={draft} color="#000000" viewport={{ x: 10, y: 20, scale: 2 }} onChange={vi.fn()} />);
    const box = screen.getByRole('textbox', { name: '낙서 글자' });
    // 40px letters at 200% are 80px; a quarter of that above and below.
    expect(box.style.paddingTop).toBe('20px');
    expect(box.style.paddingBottom).toBe('20px');
    // The line itself stays where the canvas will draw it: its top at y × scale + view offset.
    expect(box.style.top).toBe(`${200 * 2 + 20 - 20}px`);
    expect(box.style.height).toBe('80px');
  });
});
