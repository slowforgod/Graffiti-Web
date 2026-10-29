import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { TEST_CONFIG } from '../canvas/testConfig';
import type { Tool } from '../tools/useDraft';
import type { DrawAccess } from './Toolbar';
import Toolbar from './Toolbar';

function show(access: DrawAccess, draftKind: 'DRAWING' | 'TEXT' | null = null, tool: Tool = 'move') {
  const onTool = vi.fn();
  const onLogin = vi.fn();
  const onFont = vi.fn();
  const onWidth = vi.fn();
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
      onWidth={onWidth}
      fontSize={24}
      onFontSize={vi.fn()}
      font="pretendard"
      onFont={onFont}
    />,
  );
  return { onTool, onLogin, onFont, onWidth };
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
    expect(screen.getByRole('button', { name: '펜' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '지우개' })).toBeEnabled();
  });

  it('keeps the eraser off while a text is open (D-10)', () => {
    show('ok', 'TEXT', 'text');
    expect(screen.getByRole('button', { name: '지우개' })).toBeDisabled();
  });

  it('shows the tools as pictures, the eraser among them', async () => {
    const { onTool } = show('ok');
    for (const name of ['이동', '펜', '지우개', '텍스트']) {
      expect(screen.getByRole('button', { name }).querySelector('img')).not.toBeNull();
    }
    await userEvent.click(screen.getByRole('button', { name: '지우개' }));
    expect(onTool).toHaveBeenCalledWith('eraser');
  });

  it.each<Tool>(['pen', 'eraser'])('sets the %s width with a slider over the configured range', (tool) => {
    const { onWidth } = show('ok', null, tool);
    const slider = screen.getByRole('slider', { name: /굵기/ });
    expect(slider).toHaveAttribute('min', String(TEST_CONFIG.strokeWidthMin));
    expect(slider).toHaveAttribute('max', String(TEST_CONFIG.strokeWidthMax));
    expect(slider).toHaveValue('4');
    expect(screen.getByText('4px')).toBeInTheDocument();
    fireEvent.change(slider, { target: { value: '11' } });
    expect(onWidth).toHaveBeenCalledWith(11);
  });

  it('shows no width slider for moving or text', () => {
    show('ok', null, 'text');
    expect(screen.queryByRole('slider')).not.toBeInTheDocument();
  });

  it('puts the zoom buttons last and turns one off at its limit', async () => {
    const onIn = vi.fn();
    render(
      <Toolbar
        config={TEST_CONFIG}
        access="ok"
        tool="move"
        onTool={vi.fn()}
        onLogin={vi.fn()}
        draftKind={null}
        color={0}
        onColor={vi.fn()}
        width={4}
        onWidth={vi.fn()}
        fontSize={24}
        onFontSize={vi.fn()}
        font="pretendard"
        onFont={vi.fn()}
        zoom={{ percent: 400, onIn: undefined, onOut: onIn }}
      />,
    );
    const buttons = screen.getAllByRole('button');
    expect(buttons.at(-1)).toHaveAccessibleName('확대');
    expect(buttons.at(-2)).toHaveAccessibleName('축소');
    expect(screen.getByRole('button', { name: '확대' })).toBeDisabled();
    expect(screen.getByText('400%')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '축소' }));
    expect(onIn).toHaveBeenCalled();
  });

  it('offers the configured palette (INV-10)', () => {
    show('ok', null, 'pen');
    expect(screen.getAllByRole('radio', { name: /./ }).filter((r) => r.classList.contains('swatch'))).toHaveLength(12);
  });

  it('shows the font sizes for text with the default picked', () => {
    show('ok', null, 'text');
    expect(screen.getByRole('combobox')).toHaveValue('24');
    expect(screen.getAllByRole('option')).toHaveLength(TEST_CONFIG.fontSizes.length);
  });

  it('shows each font as a picture next to the size and picks one (13)', async () => {
    const { onFont } = show('ok', null, 'text');
    const button = screen.getByRole('button', { name: '글꼴: Pretendard' });
    expect(button.querySelector('img')).not.toBeNull();
    await userEvent.click(button);
    const options = screen.getAllByRole('option', { name: /./ }).filter((o) => o.tagName === 'LI');
    expect(options.map((o) => o.textContent)).toEqual(['Pretendard', '고운돋움', '갈무리', '잘난체', '교보 손글씨', '카페24 써라운드']);
    for (const o of options) {
      expect(o.querySelector('img')).not.toBeNull();
    }
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    await userEvent.click(screen.getByRole('option', { name: '잘난체' }));
    expect(onFont).toHaveBeenCalledWith('jalnan');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('moves through the fonts with the keyboard and closes on Escape', async () => {
    const { onFont } = show('ok', null, 'text');
    await userEvent.click(screen.getByRole('button', { name: '글꼴: Pretendard' }));
    await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}');
    expect(onFont).toHaveBeenCalledWith('galmuri');
    await userEvent.click(screen.getByRole('button', { name: '글꼴: Pretendard' }));
    await userEvent.keyboard('{ArrowUp}{Escape}');
    expect(onFont).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('leaves out fonts the server lists but this build has no files for', async () => {
    render(
      <Toolbar
        config={{ ...TEST_CONFIG, fonts: ['comic-sans', 'jalnan'] }}
        access="ok"
        tool="text"
        onTool={vi.fn()}
        onLogin={vi.fn()}
        draftKind={null}
        color={0}
        onColor={vi.fn()}
        width={4}
        onWidth={vi.fn()}
        fontSize={24}
        onFontSize={vi.fn()}
        font="jalnan"
        onFont={vi.fn()}
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: '글꼴: 잘난체' }));
    expect(screen.getAllByRole('option').filter((o) => o.tagName === 'LI')).toHaveLength(1);
  });
});
