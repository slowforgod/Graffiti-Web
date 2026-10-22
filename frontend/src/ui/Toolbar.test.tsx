import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TEST_CONFIG } from '../canvas/testConfig';
import type { DrawAccess } from './Toolbar';
import Toolbar from './Toolbar';

function show(access: DrawAccess, draftKind: 'DRAWING' | 'TEXT' | null = null, tool: 'move' | 'pen' | 'text' = 'move') {
  const onTool = vi.fn();
  const onLogin = vi.fn();
  render(
    <Toolbar
      config={TEST_CONFIG}
      access={access}
      tool={tool}
      onTool={onTool}
      onLogin={onLogin}
      draftKind={draftKind}
      color={0}
      onColor={vi.fn()}
      width={4}
      onWidth={vi.fn()}
      fontSize={24}
      onFontSize={vi.fn()}
    />,
  );
  return { onTool, onLogin };
}

describe('Toolbar', () => {
  it('sends a signed-out user to login when a drawing tool is picked (D-21)', async () => {
    const { onTool, onLogin } = show('anonymous');
    await userEvent.click(screen.getByRole('button', { name: '펜' }));
    expect(onLogin).toHaveBeenCalled();
    expect(onTool).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: '이동' }));
    expect(onTool).toHaveBeenCalledWith('move');
  });

  it('turns the drawing tools off for banned users and while connecting', () => {
    show('banned');
    expect(screen.getByRole('button', { name: '펜' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '텍스트' })).toBeDisabled();
    expect(screen.getByText('이용이 제한된 계정이에요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이동' })).toBeEnabled();
  });

  it('keeps the other kind of tool off while a draft is open (D-10)', () => {
    show('ok', 'DRAWING', 'pen');
    expect(screen.getByRole('button', { name: '텍스트' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '지우개' })).toBeEnabled();
  });

  it('offers the configured palette, widths and font sizes (INV-10)', () => {
    show('ok', null, 'pen');
    expect(screen.getAllByRole('radio', { name: /./ }).filter((r) => r.classList.contains('swatch'))).toHaveLength(12);
    expect(screen.getByRole('radio', { name: '16px' })).toBeInTheDocument();
  });

  it('shows the font sizes for text with the default picked', () => {
    show('ok', null, 'text');
    expect(screen.getByRole('combobox')).toHaveValue('24');
    expect(screen.getAllByRole('option')).toHaveLength(TEST_CONFIG.fontSizes.length);
  });
});
