// @vitest-environment node
import { ESLint } from 'eslint';

// SEC-12: guards the lint rule itself so a config change cannot silently drop it.
describe('eslint config', () => {
  const eslint = new ESLint();

  async function ruleIds(code: string): Promise<string[]> {
    const [result] = await eslint.lintText(code, { filePath: 'src/Probe.tsx' });
    return result?.messages.map((m) => m.ruleId ?? '') ?? [];
  }

  it('rejects dangerouslySetInnerHTML as a JSX attribute', async () => {
    const ids = await ruleIds(
      'export const P = (p: { t: string }) => <div dangerouslySetInnerHTML={{ __html: p.t }} />;\n',
    );
    expect(ids).toContain('no-restricted-syntax');
  });

  it('rejects dangerouslySetInnerHTML passed through a props object', async () => {
    const ids = await ruleIds(
      'export const props = (t: string) => ({ dangerouslySetInnerHTML: { __html: t } });\n',
    );
    expect(ids).toContain('no-restricted-syntax');
  });

  it('accepts plain text children', async () => {
    const ids = await ruleIds('export const P = (p: { t: string }) => <div>{p.t}</div>;\n');
    expect(ids).toEqual([]);
  });
});
