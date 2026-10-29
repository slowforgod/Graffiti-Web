import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import type { FontInfo } from '../fonts/fonts';

interface Props {
  fonts: FontInfo[];
  value: string;
  onChange: (id: string) => void;
}

// A select whose options show what each font looks like (13). A native <select> cannot hold images, so this
// is a button with a listbox, keyboard included: arrows move, Enter picks, Escape closes.
export default function FontPicker({ fonts, value, onChange }: Props) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const current = fonts.find((f) => f.id === value) ?? fonts[0];

  useEffect(() => {
    if (!open) {
      return;
    }
    list.current?.focus();
    const away = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', away);
    return () => document.removeEventListener('pointerdown', away);
  }, [open]);

  if (!current) {
    return null;
  }

  const show = () => {
    setActive(Math.max(0, fonts.indexOf(current)));
    setOpen(true);
  };

  const pick = (id: string) => {
    onChange(id);
    setOpen(false);
  };

  const onListKey = (e: KeyboardEvent) => {
    // Typing keys belong to the picker, not to the canvas shortcuts.
    e.stopPropagation();
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      setActive((i) => (i + step + fonts.length) % fonts.length);
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      pick(fonts[active]!.id);
    } else if (e.key === 'Escape' || e.key === 'Tab') {
      setOpen(false);
    }
  };

  return (
    <div className="font-picker" ref={root}>
      <button
        type="button"
        className="font-picker-button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`글꼴: ${current.label}`}
        onClick={() => (open ? setOpen(false) : show())}
      >
        <img src={current.preview} alt="" />
      </button>
      {open && (
        <ul
          ref={list}
          className="font-picker-list"
          role="listbox"
          aria-label="글꼴"
          tabIndex={-1}
          aria-activedescendant={`font-${fonts[active]!.id}`}
          onKeyDown={onListKey}
        >
          {fonts.map((f, i) => (
            <li
              key={f.id}
              id={`font-${f.id}`}
              role="option"
              aria-selected={f.id === current.id}
              className={i === active ? 'active' : undefined}
              onPointerEnter={() => setActive(i)}
              onClick={() => pick(f.id)}
            >
              <img src={f.preview} alt="" />
              <span>{f.label}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
