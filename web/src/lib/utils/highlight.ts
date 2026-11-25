// Lightweight warm-editorial client syntax highlighter for Reader <pre><code> blocks.
// Runs only on text nodes <= 24,000 characters, emitting safe DOM spans (zero innerHTML).

const KEYWORDS = new Set([
  'as', 'async', 'await', 'break', 'case', 'catch', 'class', 'const', 'continue',
  'def', 'default', 'defer', 'do', 'elif', 'else', 'enum', 'export', 'extends',
  'false', 'finally', 'fn', 'for', 'from', 'fun', 'func', 'function', 'go', 'if',
  'impl', 'import', 'in', 'interface', 'is', 'lambda', 'let', 'loop', 'match',
  'mod', 'mut', 'new', 'nil', 'None', 'not', 'null', 'or', 'package', 'pass',
  'private', 'protected', 'pub', 'public', 'raise', 'ref', 'return', 'select',
  'self', 'Self', 'static', 'struct', 'super', 'switch', 'this', 'throw', 'trait',
  'true', 'True', 'False', 'try', 'type', 'typeof', 'undefined', 'unsafe', 'use',
  'val', 'var', 'void', 'where', 'while', 'with', 'yield',
]);

const TOKEN_RE =
  /(\/\/[^\n]*|\/\*[\s\S]*?\*\/|#[^\n]*|--[^\n]*|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\b0x[0-9a-fA-F_]+\b|\b\d+(?:\.\d+)?(?:e[+-]?\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)/g;

export interface HighlightToken {
  text: string;
  className: string | null;
}

export function tokenizeCode(source: string): HighlightToken[] {
  const tokens: HighlightToken[] = [];
  let lastIndex = 0;
  TOKEN_RE.lastIndex = 0;

  let m: RegExpExecArray | null;
  while ((m = TOKEN_RE.exec(source)) !== null) {
    if (m.index > lastIndex) {
      tokens.push({ text: source.slice(lastIndex, m.index), className: null });
    }
    const raw = m[0];
    let cls: string | null = null;
    if (
      raw.startsWith('//') ||
      raw.startsWith('/*') ||
      raw.startsWith('#') ||
      raw.startsWith('--')
    ) {
      cls = 'syn-cm';
    } else if (raw.startsWith('"') || raw.startsWith("'") || raw.startsWith('`')) {
      cls = 'syn-str';
    } else if (/^\d/.test(raw)) {
      cls = 'syn-num';
    } else if (KEYWORDS.has(raw)) {
      cls = 'syn-kw';
    } else if (source[TOKEN_RE.lastIndex] === '(') {
      cls = 'syn-fn';
    }
    tokens.push({ text: raw, className: cls });
    lastIndex = TOKEN_RE.lastIndex;
  }

  if (lastIndex < source.length) {
    tokens.push({ text: source.slice(lastIndex), className: null });
  }
  return tokens;
}

export function highlightCodeBlocks(container: HTMLElement): void {
  for (const codeEl of container.querySelectorAll('pre > code')) {
    if (codeEl.getAttribute('data-roche-hl') === '1') continue;
    if (codeEl.children.length > 0) continue;
    const raw = codeEl.textContent ?? '';
    if (!raw || raw.length > 24_000) continue;
    const frag = document.createDocumentFragment();
    for (const tok of tokenizeCode(raw)) {
      if (tok.className) {
        const span = document.createElement('span');
        span.className = tok.className;
        span.textContent = tok.text;
        frag.appendChild(span);
      } else {
        frag.appendChild(document.createTextNode(tok.text));
      }
    }
    codeEl.textContent = '';
    codeEl.appendChild(frag);
    codeEl.setAttribute('data-roche-hl', '1');
  }
}
