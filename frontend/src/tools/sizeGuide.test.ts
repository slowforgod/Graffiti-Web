import { sizeGuide } from './sizeGuide';

const limits = { width: 10000, height: 10000, maxBbox: 1024 };
const view = { x: 2000, y: 3000, w: 1600, h: 900 };

describe('sizeGuide', () => {
  it('sits in the middle of the view before the first stroke', () => {
    expect(sizeGuide(null, null, view, limits)).toEqual({ x: 2288, y: 2938, w: 1024, h: 1024 });
  });

  it('keeps a held square where it is, even before anything is drawn', () => {
    const prev = { x: 500, y: 600, w: 1024, h: 1024 };
    expect(sizeGuide(prev, null, view, limits)).toEqual(prev);
  });

  it('pulls a held square dragged past the edge back inside the canvas', () => {
    const prev = { x: -40, y: 9500, w: 1024, h: 1024 };
    expect(sizeGuide(prev, null, view, limits)).toEqual({ x: 0, y: 8976, w: 1024, h: 1024 });
  });

  it('stays inside the canvas near its edge', () => {
    expect(sizeGuide(null, null, { x: 0, y: 9500, w: 800, h: 500 }, limits)).toEqual({
      x: 0,
      y: 8976,
      w: 1024,
      h: 1024,
    });
  });

  it('stays put while the drawing is inside it', () => {
    const prev = { x: 2288, y: 2938, w: 1024, h: 1024 };
    const bounds = { x: 2400, y: 3000, w: 300, h: 200 };
    expect(sizeGuide(prev, bounds, { x: 0, y: 0, w: 800, h: 600 }, limits)).toEqual(prev);
  });

  it('moves only as far as needed to keep the drawing inside', () => {
    const prev = { x: 2288, y: 2938, w: 1024, h: 1024 };
    const bounds = { x: 3000, y: 2900, w: 500, h: 100 };
    expect(sizeGuide(prev, bounds, view, limits)).toEqual({ x: 2476, y: 2900, w: 1024, h: 1024 });
  });

  it('starts around the first stroke when it begins outside the middle square', () => {
    const bounds = { x: 100, y: 100, w: 4, h: 4 };
    expect(sizeGuide(null, bounds, view, limits)).toEqual({ x: 100, y: 100, w: 1024, h: 1024 });
  });
});
