import { describe, expect, it } from 'vitest';
import { tokenizeCode } from './highlight';

describe('tokenizeCode', () => {
  it('tokenizes keywords, strings, numbers, comments, and functions without losing characters', () => {
    const src = 'fn main() {\n  // greet\n  let x = 42;\n  println!("hi");\n}';
    const tokens = tokenizeCode(src);
    expect(tokens.map((t) => t.text).join('')).toBe(src);
    expect(tokens.some((t) => t.className === 'syn-kw' && t.text === 'fn')).toBe(true);
    expect(tokens.some((t) => t.className === 'syn-fn' && t.text === 'main')).toBe(true);
    expect(tokens.some((t) => t.className === 'syn-cm' && t.text === '// greet')).toBe(true);
    expect(tokens.some((t) => t.className === 'syn-num' && t.text === '42')).toBe(true);
    expect(tokens.some((t) => t.className === 'syn-str' && t.text === '"hi"')).toBe(true);
  });

  it('handles Python hash comments and escaped strings', () => {
    const src = '# note\nmsg = "hello \\"world\\""\n';
    const tokens = tokenizeCode(src);
    expect(tokens.map((t) => t.text).join('')).toBe(src);
    expect(tokens[0]).toEqual({ className: 'syn-cm', text: '# note' });
    expect(tokens.some((t) => t.className === 'syn-str' && t.text === '"hello \\"world\\""')).toBe(
      true,
    );
  });
});
