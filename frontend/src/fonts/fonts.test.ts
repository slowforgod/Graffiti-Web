import { afterEach, describe, expect, it, vi } from 'vitest';
import { FONTS, fontInfo, loadFont } from './fonts';
import licenses from './LICENSES.md?raw';

describe('fonts', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    Reflect.deleteProperty(document, 'fonts');
  });

  it('has a picture and its own family for every font', () => {
    expect(FONTS.map((f) => f.id)).toEqual(['pretendard', 'gowun-dodum', 'galmuri', 'jalnan', 'kyobo-hand', 'cafe24-ssurround']);
    for (const f of FONTS) {
      expect(f.preview).not.toBe('');
      expect(f.family).toBe(`gf-${f.id}, sans-serif`);
    }
  });

  it('has a license entry for every font (D-44)', () => {
    for (const f of FONTS) {
      expect(licenses).toContain(`| ${f.id} |`);
    }
  });

  it('draws texts without a font, or with one this build does not know, in the first font', () => {
    expect(fontInfo(undefined).id).toBe('pretendard');
    expect(fontInfo('comic-sans').id).toBe('pretendard');
    expect(fontInfo('jalnan').label).toBe('잘난체');
  });

  it('says a font is not ready where the browser cannot load fonts', async () => {
    expect(await loadFont('jalnan', '안녕')).toBe(false);
  });

  it('adds each slice of a font once, by unicode-range, and loads the ones a text needs', async () => {
    const added: { family: string; range: string | undefined }[] = [];
    vi.stubGlobal(
      'FontFace',
      class {
        constructor(family: string, _src: string, descriptors: { unicodeRange?: string }) {
          added.push({ family, range: descriptors.unicodeRange });
        }
      },
    );
    const load = vi.fn().mockResolvedValue([]);
    Object.defineProperty(document, 'fonts', { value: { add: vi.fn(), load }, configurable: true });

    expect(await loadFont('galmuri', '가')).toBe(true);
    expect(await loadFont('galmuri', '나')).toBe(true);

    expect(added.length).toBeGreaterThan(1);
    expect(new Set(added.map((a) => a.family))).toEqual(new Set(['gf-galmuri']));
    expect(added[0]!.range).toContain('U+20-7E');
    expect(load).toHaveBeenLastCalledWith('16px gf-galmuri, sans-serif', '나');
  });

  it('says a font is not ready when its files fail to load', async () => {
    vi.stubGlobal('FontFace', class {});
    Object.defineProperty(document, 'fonts', {
      value: { add: vi.fn(), load: vi.fn().mockRejectedValue(new Error('network')) },
      configurable: true,
    });
    expect(await loadFont('jalnan', '안녕')).toBe(false);
  });
});
