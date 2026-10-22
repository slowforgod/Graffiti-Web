import { describe, expect, it } from 'vitest';
import { decodeBitmap } from './minimap';

describe('decodeBitmap', () => {
  it('reads cell row * cols + col from the most significant bit first (API.md §2)', () => {
    const bytes = new Uint8Array(1800);
    bytes[0] = 0x80; // cell 0
    bytes[161 >> 3] = 0x80 >>> (161 % 8); // row 1, col 1 of a 160-column grid
    bytes[1799] = 0x01; // last cell, 14,399
    const base64 = btoa(String.fromCharCode(...bytes));

    const cells = decodeBitmap(base64, 160 * 90);

    expect(cells).toHaveLength(14_400);
    expect([...cells.keys()].filter((i) => cells[i] === 1)).toEqual([0, 161, 14_399]);
  });
});
