import { describe, expect, it } from 'vitest';
import { centerPictureBox } from './PaperBackground';

describe('centerPictureBox', () => {
  it('fits the picture in 60% of the page height and centers it', () => {
    const box = centerPictureBox({ width: 7680, height: 4320 }, { width: 1441, height: 1225 });
    expect(box.height).toBeCloseTo(2592);
    expect(box.width / box.height).toBeCloseTo(1441 / 1225);
    expect(box.x + box.width / 2).toBeCloseTo(3840);
    expect(box.y + box.height / 2).toBeCloseTo(2160);
  });

  it('is limited by the width on a narrow page', () => {
    const box = centerPictureBox({ width: 1000, height: 4000 }, { width: 1441, height: 1225 });
    expect(box.width).toBeCloseTo(600);
    expect(box.y + box.height / 2).toBeCloseTo(2000);
  });
});
