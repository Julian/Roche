use std::fmt::Write as _;

const MAX_INLINE_MATH_LEN: usize = 240;
const MAX_DISPLAY_MATH_LEN: usize = 2048;
const MAX_MATH_DEPTH: usize = 16;

/// Renders text containing optional `$...$` inline math or `$$...$$` display math into
/// HTML-escaped prose with native HTML5 `MathML` (`<math>`).
///
/// Ordinary currency values like `$5` or `$5 and $10` are left untouched as escaped text.
#[must_use]
pub fn render_inline_math_html(text: &str) -> String {
    if !text.contains('$') {
        return escape_html(text);
    }
    let mut out = String::with_capacity(text.len() + 32);
    let bytes = text.as_bytes();
    let mut i = 0usize;
    let mut plain_start = 0usize;

    while i < bytes.len() {
        if bytes[i] != b'$' {
            i += 1;
            continue;
        }
        if i > 0 && bytes[i - 1] == b'\\' {
            i += 1;
            continue;
        }
        let is_display = bytes.get(i + 1) == Some(&b'$');
        let found = if is_display {
            find_display_math_end(text, i + 2).map(|end| (i + 2, end, end + 2, true))
        } else {
            find_inline_math_end(text, i + 1).map(|end| (i + 1, end, end + 1, false))
        };
        if let Some((expr_start, expr_end, next_i, display)) = found {
            push_escaped_html(&mut out, &text[plain_start..i]);
            let expr = text[expr_start..expr_end].trim();
            out.push_str(&render_latex_expr_to_mathml(expr, display));
            i = next_i;
            plain_start = next_i;
        } else {
            i += if is_display { 2 } else { 1 };
        }
    }
    push_escaped_html(&mut out, &text[plain_start..]);
    out
}

/// Strips `$...$` / `$$...$$` delimiters and converts basic LaTeX commands to clean Unicode plain
/// text suitable for `<title>`, `alt` attributes, search indexing, and sorting.
#[must_use]
pub fn strip_inline_math_delimiters(text: &str) -> String {
    if !text.contains('$') {
        return text.to_owned();
    }
    let mut out = String::with_capacity(text.len());
    let bytes = text.as_bytes();
    let mut i = 0usize;
    let mut plain_start = 0usize;

    while i < bytes.len() {
        if bytes[i] != b'$' {
            i += 1;
            continue;
        }
        if i > 0 && bytes[i - 1] == b'\\' {
            i += 1;
            continue;
        }
        let is_display = bytes.get(i + 1) == Some(&b'$');
        let found = if is_display {
            find_display_math_end(text, i + 2).map(|end| (i + 2, end, end + 2))
        } else {
            find_inline_math_end(text, i + 1).map(|end| (i + 1, end, end + 1))
        };
        if let Some((expr_start, expr_end, next_i)) = found {
            out.push_str(&text[plain_start..i]);
            out.push_str(&latex_expr_to_plain_text(&text[expr_start..expr_end]));
            i = next_i;
            plain_start = next_i;
        } else {
            i += if is_display { 2 } else { 1 };
        }
    }
    out.push_str(&text[plain_start..]);
    out
}

/// Converts a single LaTeX math expression (without surrounding `$` delimiters) into native HTML5
/// presentation `MathML` (`<math>...</math>`).
#[must_use]
pub fn render_latex_expr_to_mathml(expr: &str, display_block: bool) -> String {
    let trimmed = expr.trim();
    if trimmed.is_empty() {
        return String::new();
    }
    let bounded = if trimmed.len() > MAX_DISPLAY_MATH_LEN {
        &trimmed[..trimmed.floor_char_boundary(MAX_DISPLAY_MATH_LEN)]
    } else {
        trimmed
    };
    let tokens = tokenize_latex(bounded);
    let mut parser = MathParser::new(&tokens);
    let nodes = parser.parse_sequence(0, false);
    let mut out = String::with_capacity(bounded.len() * 3 + 32);
    if display_block {
        out.push_str("<math display=\"block\">");
    } else {
        out.push_str("<math>");
    }
    render_math_nodes(&nodes, &mut out, display_block);
    out.push_str("</math>");
    out
}

/// Converts a LaTeX math expression into readable plain Unicode text (e.g.
/// `o(n \log n)` -> `o(n log n)`, `\frac{a}{b}` -> `a/b`).
#[must_use]
pub fn latex_expr_to_plain_text(expr: &str) -> String {
    let tokens = tokenize_latex(expr.trim());
    let mut out = String::with_capacity(expr.len());
    render_plain_tok_slice(&tokens, &mut out, 0);
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn render_plain_tok_slice(tokens: &[MathTok], out: &mut String, depth: usize) {
    if depth >= MAX_MATH_DEPTH {
        return;
    }
    let mut prev_wordlike = false;
    let mut idx = 0usize;
    while idx < tokens.len() {
        match &tokens[idx] {
            MathTok::Command(cmd) => {
                if render_plain_command(cmd, tokens, &mut idx, out, depth, &mut prev_wordlike) {
                    continue;
                }
            }
            MathTok::Ident(s) => {
                if prev_wordlike && !out.ends_with(' ') {
                    out.push(' ');
                }
                out.push_str(s);
                prev_wordlike = true;
            }
            MathTok::Number(n) => {
                out.push_str(n);
                prev_wordlike = true;
            }
            MathTok::Op(ch) => {
                out.push(*ch);
                prev_wordlike = false;
            }
            MathTok::Caret => {
                out.push('^');
                prev_wordlike = false;
            }
            MathTok::Underscore => {
                out.push('_');
                prev_wordlike = false;
            }
            MathTok::Space => {
                if !out.is_empty() && !out.ends_with(' ') && !out.ends_with('(') {
                    out.push(' ');
                }
                prev_wordlike = false;
            }
            MathTok::LBrace | MathTok::RBrace => {}
        }
        idx += 1;
    }
}

fn render_plain_command(
    cmd: &str,
    tokens: &[MathTok],
    idx: &mut usize,
    out: &mut String,
    depth: usize,
    prev_wordlike: &mut bool,
) -> bool {
    if let Some(op) = named_operator_text(cmd) {
        if *prev_wordlike && !out.ends_with(' ') && !out.ends_with('(') {
            out.push(' ');
        }
        out.push_str(op);
        *prev_wordlike = true;
    } else if let Some(sym) = latex_symbol_text(cmd) {
        out.push_str(sym);
        *prev_wordlike = false;
    } else if matches!(cmd, "frac" | "dfrac" | "tfrac") {
        *idx += 1;
        let num = take_plain_group_slice(tokens, idx);
        let den = take_plain_group_slice(tokens, idx);
        render_plain_tok_slice(num, out, depth + 1);
        out.push('/');
        render_plain_tok_slice(den, out, depth + 1);
        *prev_wordlike = false;
        return true;
    } else if cmd == "mathbb" {
        *idx += 1;
        let arg = take_plain_group_slice(tokens, idx);
        for t in arg {
            if let MathTok::Ident(s) = t {
                for ch in s.chars() {
                    out.push(to_blackboard_bold(ch));
                }
            }
        }
        *prev_wordlike = true;
        return true;
    } else if matches!(
        cmd,
        "mathrm"
            | "mathbf"
            | "mathit"
            | "mathsf"
            | "mathtt"
            | "mathcal"
            | "operatorname"
            | "text"
            | "textbf"
            | "textit"
            | "textrm"
            | "tilde"
            | "hat"
            | "bar"
            | "overline"
            | "vec"
            | "sqrt"
    ) {
        if cmd == "sqrt" {
            out.push('√');
        }
        *prev_wordlike = false;
    } else if matches!(cmd, "left" | "right" | "," | ";" | "!") {
        if cmd == ";" && !out.ends_with(' ') {
            out.push(' ');
        }
        *prev_wordlike = false;
    }
    false
}

fn take_plain_group_slice<'a>(tokens: &'a [MathTok], idx: &mut usize) -> &'a [MathTok] {
    while *idx < tokens.len() && matches!(tokens[*idx], MathTok::Space) {
        *idx += 1;
    }
    if *idx >= tokens.len() {
        return &[];
    }
    if matches!(tokens[*idx], MathTok::LBrace) {
        let start = *idx + 1;
        let mut depth = 1usize;
        *idx += 1;
        while *idx < tokens.len() && depth > 0 {
            match tokens[*idx] {
                MathTok::LBrace => depth += 1,
                MathTok::RBrace => depth -= 1,
                _ => {}
            }
            *idx += 1;
        }
        let end = if depth == 0 { *idx - 1 } else { *idx };
        &tokens[start..end.max(start)]
    } else {
        let start = *idx;
        *idx += 1;
        &tokens[start..*idx]
    }
}

fn find_display_math_end(text: &str, start: usize) -> Option<usize> {
    let rest = text.get(start..)?;
    let bytes = rest.as_bytes();
    let limit = bytes.len().min(MAX_DISPLAY_MATH_LEN);
    let mut i = 0usize;
    while i + 1 < limit {
        if bytes[i] == b'$' && bytes[i + 1] == b'$' && (i == 0 || bytes[i - 1] != b'\\') {
            return (!rest[..i].trim().is_empty()).then_some(start + i);
        }
        i += 1;
    }
    None
}

fn find_inline_math_end(text: &str, start: usize) -> Option<usize> {
    let rest = text.get(start..)?;
    let bytes = rest.as_bytes();
    let first = *bytes.first()?;
    if first.is_ascii_whitespace() || first == b'$' {
        return None;
    }
    let limit = bytes.len().min(MAX_INLINE_MATH_LEN);
    let mut i = 0usize;
    while i < limit {
        let b = bytes[i];
        if b == b'\n' || b == b'\r' {
            return None;
        }
        if b == b'$' && (i == 0 || bytes[i - 1] != b'\\') {
            if i == 0 || bytes[i - 1].is_ascii_whitespace() {
                return None;
            }
            if bytes.get(i + 1).is_some_and(u8::is_ascii_digit) {
                return None;
            }
            let inner = &rest[..i];
            if looks_like_currency_span(inner) {
                return None;
            }
            return Some(start + i);
        }
        i += 1;
    }
    None
}

fn looks_like_currency_span(inner: &str) -> bool {
    let trimmed = inner.trim();
    if trimmed.is_empty() {
        return true;
    }
    let starts_digit = trimmed.as_bytes().first().is_some_and(u8::is_ascii_digit);
    if !starts_digit {
        return false;
    }
    let lower = trimmed.to_ascii_lowercase();
    lower.contains(" and ")
        || lower.contains(" or ")
        || lower.contains(" to ")
        || lower.contains(" per ")
        || lower.contains(" for ")
        || lower.contains(" million")
        || lower.contains(" billion")
        || lower.contains(" thousand")
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum MathTok {
    Command(String),
    Ident(String),
    Number(String),
    Op(char),
    LBrace,
    RBrace,
    Caret,
    Underscore,
    Space,
}

fn tokenize_latex(input: &str) -> Vec<MathTok> {
    let mut out = Vec::new();
    let mut chars = input.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '{' => out.push(MathTok::LBrace),
            '}' => out.push(MathTok::RBrace),
            '^' => out.push(MathTok::Caret),
            '_' => out.push(MathTok::Underscore),
            '\\' => {
                if let Some(&next) = chars.peek() {
                    if next.is_ascii_alphabetic() {
                        let mut name = String::new();
                        while let Some(&c) = chars.peek() {
                            if c.is_ascii_alphabetic() {
                                name.push(c);
                                chars.next();
                            } else {
                                break;
                            }
                        }
                        out.push(MathTok::Command(name));
                    } else {
                        chars.next();
                        out.push(MathTok::Command(next.to_string()));
                    }
                }
            }
            c if c.is_whitespace() => {
                if !matches!(out.last(), Some(MathTok::Space)) {
                    out.push(MathTok::Space);
                }
            }
            c if c.is_ascii_digit() => {
                let mut num = String::new();
                num.push(c);
                while let Some(&next) = chars.peek() {
                    if next.is_ascii_digit() || next == '.' {
                        num.push(next);
                        chars.next();
                    } else {
                        break;
                    }
                }
                out.push(MathTok::Number(num));
            }
            c if c.is_alphabetic() => {
                let mut ident = String::new();
                ident.push(c);
                while let Some(&next) = chars.peek() {
                    if next.is_ascii_alphabetic() && c.is_ascii_alphabetic() {
                        ident.push(next);
                        chars.next();
                    } else {
                        break;
                    }
                }
                if ident.len() > 1 && is_bare_named_operator(&ident) {
                    out.push(MathTok::Command(ident));
                } else {
                    for letter in ident.chars() {
                        out.push(MathTok::Ident(letter.to_string()));
                    }
                }
            }
            other => out.push(MathTok::Op(other)),
        }
    }
    out
}

#[derive(Debug, Clone)]
enum MathNode {
    Mi(String, Option<&'static str>),
    Mn(String),
    Mo(String, bool), // (text, stretchy_false)
    Mtext(String),
    Mspace(&'static str),
    Mrow(Vec<Self>),
    Msup(Box<Self>, Box<Self>),
    Msub(Box<Self>, Box<Self>),
    Msubsup(Box<Self>, Box<Self>, Box<Self>),
    Mfrac(Box<Self>, Box<Self>),
    Msqrt(Box<Self>),
    Mover(Box<Self>, &'static str),
}

struct MathParser<'a> {
    tokens: &'a [MathTok],
    pos: usize,
}

impl<'a> MathParser<'a> {
    const fn new(tokens: &'a [MathTok]) -> Self {
        Self { tokens, pos: 0 }
    }

    fn peek(&self) -> Option<&'a MathTok> {
        self.tokens.get(self.pos)
    }

    fn next(&mut self) -> Option<&'a MathTok> {
        let tok = self.tokens.get(self.pos)?;
        self.pos += 1;
        Some(tok)
    }

    fn skip_spaces(&mut self) {
        while matches!(self.peek(), Some(MathTok::Space)) {
            self.pos += 1;
        }
    }

    fn parse_sequence(&mut self, depth: usize, stop_at_rbrace: bool) -> Vec<MathNode> {
        if depth >= MAX_MATH_DEPTH {
            return Vec::new();
        }
        let mut nodes = Vec::new();
        while let Some(tok) = self.peek() {
            match tok {
                MathTok::RBrace if stop_at_rbrace => {
                    self.pos += 1;
                    break;
                }
                MathTok::RBrace | MathTok::Space => {
                    self.pos += 1;
                }
                _ => {
                    if let Some(atom) = self.parse_scripted_atom(depth) {
                        nodes.push(atom);
                    }
                }
            }
        }
        insert_operator_spacing(&nodes)
    }

    fn parse_scripted_atom(&mut self, depth: usize) -> Option<MathNode> {
        let base = self.parse_atom(depth)?;
        let mut sub: Option<MathNode> = None;
        let mut sup: Option<MathNode> = None;
        for _ in 0..2 {
            self.skip_spaces();
            match self.peek() {
                Some(MathTok::Caret) if sup.is_none() => {
                    self.pos += 1;
                    self.skip_spaces();
                    sup = self.parse_atom(depth + 1);
                }
                Some(MathTok::Underscore) if sub.is_none() => {
                    self.pos += 1;
                    self.skip_spaces();
                    sub = self.parse_atom(depth + 1);
                }
                _ => break,
            }
        }
        Some(match (sub, sup) {
            (Some(b), Some(p)) => MathNode::Msubsup(Box::new(base), Box::new(b), Box::new(p)),
            (Some(b), None) => MathNode::Msub(Box::new(base), Box::new(b)),
            (None, Some(p)) => MathNode::Msup(Box::new(base), Box::new(p)),
            (None, None) => base,
        })
    }

    fn parse_atom(&mut self, depth: usize) -> Option<MathNode> {
        if depth >= MAX_MATH_DEPTH {
            let _ = self.next();
            return None;
        }
        self.skip_spaces();
        let tok = self.next()?.clone();
        match tok {
            MathTok::LBrace => {
                let inner = self.parse_sequence(depth + 1, true);
                Some(collapse_mrow(inner))
            }
            MathTok::RBrace | MathTok::Space => None,
            MathTok::Ident(s) => Some(MathNode::Mi(s, None)),
            MathTok::Number(n) => Some(MathNode::Mn(n)),
            MathTok::Op(ch) => Some(op_char_node(ch)),
            MathTok::Caret => Some(MathNode::Mo("^".to_owned(), true)),
            MathTok::Underscore => Some(MathNode::Mo("_".to_owned(), true)),
            MathTok::Command(cmd) => self.parse_command(&cmd, depth),
        }
    }

    fn parse_command(&mut self, cmd: &str, depth: usize) -> Option<MathNode> {
        match cmd {
            "frac" | "tfrac" | "dfrac" => {
                let num = self.parse_atom(depth + 1).unwrap_or_else(|| MathNode::Mrow(Vec::new()));
                let den = self.parse_atom(depth + 1).unwrap_or_else(|| MathNode::Mrow(Vec::new()));
                Some(MathNode::Mfrac(Box::new(num), Box::new(den)))
            }
            "sqrt" => {
                let inner =
                    self.parse_atom(depth + 1).unwrap_or_else(|| MathNode::Mrow(Vec::new()));
                Some(MathNode::Msqrt(Box::new(inner)))
            }
            "tilde" | "widetilde" => {
                let inner = self.parse_atom(depth + 1)?;
                Some(MathNode::Mover(Box::new(inner), "~"))
            }
            "hat" | "widehat" => {
                let inner = self.parse_atom(depth + 1)?;
                Some(MathNode::Mover(Box::new(inner), "^"))
            }
            "bar" | "overline" => {
                let inner = self.parse_atom(depth + 1)?;
                Some(MathNode::Mover(Box::new(inner), "¯"))
            }
            "vec" => {
                let inner = self.parse_atom(depth + 1)?;
                Some(MathNode::Mover(Box::new(inner), "→"))
            }
            "mathbb" => {
                let raw = self.parse_plain_arg();
                let mapped: String = raw.chars().map(to_blackboard_bold).collect();
                Some(MathNode::Mi(mapped, Some("normal")))
            }
            "mathcal" => {
                let raw = self.parse_plain_arg();
                Some(MathNode::Mi(raw, Some("script")))
            }
            "mathrm" | "operatorname" => {
                let raw = self.parse_plain_arg();
                Some(MathNode::Mi(raw, Some("normal")))
            }
            "mathbf" | "boldsymbol" => {
                let raw = self.parse_plain_arg();
                Some(MathNode::Mi(raw, Some("bold")))
            }
            "mathit" => {
                let raw = self.parse_plain_arg();
                Some(MathNode::Mi(raw, Some("italic")))
            }
            "text" | "textrm" | "textbf" | "textit" | "mathsf" | "mathtt" => {
                let raw = self.parse_plain_arg();
                Some(MathNode::Mtext(raw))
            }
            "left" | "right" | "big" | "Big" | "bigg" | "Bigg" => {
                self.skip_spaces();
                match self.peek() {
                    Some(MathTok::Op('.')) => {
                        self.pos += 1;
                        None
                    }
                    _ => self.parse_atom(depth + 1),
                }
            }
            "," => Some(MathNode::Mspace("0.167em")),
            ";" | ":" | " " => Some(MathNode::Mspace("0.222em")),
            "quad" => Some(MathNode::Mspace("1em")),
            "qquad" => Some(MathNode::Mspace("2em")),
            "!" => None,
            "{" | "}" | "|" => Some(MathNode::Mo(cmd.to_owned(), true)),
            _ => named_operator_text(cmd).map_or_else(
                || {
                    latex_symbol_text(cmd).map_or_else(
                        || Some(MathNode::Mi(cmd.to_owned(), Some("normal"))),
                        |sym| {
                            let is_letter = sym.chars().count() == 1
                                && sym.chars().next().is_some_and(char::is_alphabetic);
                            if is_letter {
                                Some(MathNode::Mi(sym.to_owned(), None))
                            } else {
                                Some(MathNode::Mo(sym.to_owned(), true))
                            }
                        },
                    )
                },
                |op| Some(MathNode::Mi(op.to_owned(), Some("normal"))),
            ),
        }
    }

    fn parse_plain_arg(&mut self) -> String {
        self.skip_spaces();
        if matches!(self.peek(), Some(MathTok::LBrace)) {
            self.pos += 1;
            let mut out = String::new();
            let mut brace_depth = 1usize;
            while let Some(tok) = self.next() {
                match tok {
                    MathTok::LBrace => brace_depth += 1,
                    MathTok::RBrace => {
                        brace_depth -= 1;
                        if brace_depth == 0 {
                            break;
                        }
                    }
                    MathTok::Ident(s) | MathTok::Number(s) => out.push_str(s),
                    MathTok::Op(c) => out.push(*c),
                    MathTok::Space => out.push(' '),
                    MathTok::Command(c) => {
                        if let Some(sym) = latex_symbol_text(c) {
                            out.push_str(sym);
                        } else if let Some(op) = named_operator_text(c) {
                            out.push_str(op);
                        } else {
                            out.push_str(c);
                        }
                    }
                    MathTok::Caret => out.push('^'),
                    MathTok::Underscore => out.push('_'),
                }
            }
            out
        } else if let Some(tok) = self.next() {
            match tok {
                MathTok::Ident(s) | MathTok::Number(s) => s.clone(),
                MathTok::Op(c) => c.to_string(),
                MathTok::Command(c) => latex_symbol_text(c).unwrap_or(c).to_owned(),
                _ => String::new(),
            }
        } else {
            String::new()
        }
    }
}

fn collapse_mrow(mut nodes: Vec<MathNode>) -> MathNode {
    if nodes.len() == 1 { nodes.remove(0) } else { MathNode::Mrow(nodes) }
}

fn op_char_node(ch: char) -> MathNode {
    match ch {
        '-' => MathNode::Mo("−".to_owned(), true),
        '*' => MathNode::Mo("∗".to_owned(), true),
        '\'' => MathNode::Mo("′".to_owned(), true),
        other => MathNode::Mo(other.to_string(), true),
    }
}

/// Inserts thin `MathML` spacing around upright named operators (`log`, `sin`, `mod`, `diag`, …)
/// when directly adjacent to variables or numbers so `$o(n \log n)$` reads cleanly.
fn insert_operator_spacing(nodes: &[MathNode]) -> Vec<MathNode> {
    let mut out = Vec::with_capacity(nodes.len() + 4);
    for (idx, node) in nodes.iter().enumerate() {
        let is_named_op = is_named_op_node(node);
        if is_named_op
            && let Some(prev) = out.last()
            && needs_thin_space_beside_op(prev)
        {
            out.push(MathNode::Mspace("0.167em"));
        }
        out.push(node.clone());
        if is_named_op
            && let Some(next) = nodes.get(idx + 1)
            && needs_thin_space_beside_op(next)
        {
            out.push(MathNode::Mspace("0.167em"));
        }
    }
    out
}

fn is_named_op_node(node: &MathNode) -> bool {
    matches!(node, MathNode::Mi(s, Some("normal")) if s.len() >= 2 && is_bare_named_operator(s))
}

const fn needs_thin_space_beside_op(node: &MathNode) -> bool {
    matches!(
        node,
        MathNode::Mi(_, _)
            | MathNode::Mn(_)
            | MathNode::Msup(_, _)
            | MathNode::Msub(_, _)
            | MathNode::Msubsup(_, _, _)
            | MathNode::Mover(_, _)
    )
}

fn render_math_nodes(nodes: &[MathNode], out: &mut String, display_block: bool) {
    for node in nodes {
        render_math_node(node, out, display_block);
    }
}

fn render_math_node(node: &MathNode, out: &mut String, display_block: bool) {
    match node {
        MathNode::Mi(s, variant) => {
            if let Some(v) = variant {
                let _ = write!(out, "<mi mathvariant=\"{v}\">");
            } else {
                out.push_str("<mi>");
            }
            push_escaped_html(out, s);
            out.push_str("</mi>");
        }
        MathNode::Mn(n) => {
            out.push_str("<mn>");
            push_escaped_html(out, n);
            out.push_str("</mn>");
        }
        MathNode::Mo(op, stretchy_false) => {
            if !display_block && *stretchy_false && is_fence_char(op) {
                out.push_str("<mo stretchy=\"false\">");
            } else {
                out.push_str("<mo>");
            }
            push_escaped_html(out, op);
            out.push_str("</mo>");
        }
        MathNode::Mtext(t) => {
            out.push_str("<mtext>");
            push_escaped_html(out, t);
            out.push_str("</mtext>");
        }
        MathNode::Mspace(w) => {
            let _ = write!(out, "<mspace width=\"{w}\"></mspace>");
        }
        MathNode::Mrow(children) => {
            out.push_str("<mrow>");
            render_math_nodes(children, out, display_block);
            out.push_str("</mrow>");
        }
        MathNode::Msup(base, sup) => {
            out.push_str("<msup>");
            render_math_node(base, out, display_block);
            render_math_node(sup, out, display_block);
            out.push_str("</msup>");
        }
        MathNode::Msub(base, sub) => {
            out.push_str("<msub>");
            render_math_node(base, out, display_block);
            render_math_node(sub, out, display_block);
            out.push_str("</msub>");
        }
        MathNode::Msubsup(base, sub, sup) => {
            out.push_str("<msubsup>");
            render_math_node(base, out, display_block);
            render_math_node(sub, out, display_block);
            render_math_node(sup, out, display_block);
            out.push_str("</msubsup>");
        }
        MathNode::Mfrac(num, den) => {
            out.push_str("<mfrac>");
            render_math_node(num, out, display_block);
            render_math_node(den, out, display_block);
            out.push_str("</mfrac>");
        }
        MathNode::Msqrt(inner) => {
            out.push_str("<msqrt>");
            render_math_node(inner, out, display_block);
            out.push_str("</msqrt>");
        }
        MathNode::Mover(inner, accent) => {
            out.push_str("<mover accent=\"true\">");
            render_math_node(inner, out, display_block);
            let _ = write!(out, "<mo>{accent}</mo></mover>");
        }
    }
}

fn is_fence_char(op: &str) -> bool {
    matches!(op, "(" | ")" | "[" | "]" | "{" | "}" | "|" | "⟨" | "⟩" | "⌊" | "⌋" | "⌈" | "⌉")
}

fn is_bare_named_operator(s: &str) -> bool {
    matches!(
        s,
        "log"
            | "ln"
            | "exp"
            | "sin"
            | "cos"
            | "tan"
            | "lim"
            | "min"
            | "max"
            | "mod"
            | "dim"
            | "det"
            | "deg"
            | "gcd"
            | "sup"
            | "inf"
            | "arg"
            | "ker"
            | "hom"
            | "diag"
            | "wt"
            | "poly"
            | "polylog"
            | "Pr"
    )
}

fn named_operator_text(cmd: &str) -> Option<&'static str> {
    Some(match cmd {
        "log" => "log",
        "ln" => "ln",
        "exp" => "exp",
        "sin" => "sin",
        "cos" => "cos",
        "tan" => "tan",
        "lim" => "lim",
        "limsup" => "lim sup",
        "liminf" => "lim inf",
        "min" => "min",
        "max" => "max",
        "mod" | "bmod" | "pmod" => "mod",
        "dim" => "dim",
        "det" => "det",
        "deg" => "deg",
        "gcd" => "gcd",
        "sup" => "sup",
        "inf" => "inf",
        "arg" => "arg",
        "ker" => "ker",
        "hom" => "hom",
        "diag" => "diag",
        "wt" => "wt",
        "poly" => "poly",
        "polylog" => "polylog",
        "Pr" => "Pr",
        _ => return None,
    })
}

fn latex_symbol_text(cmd: &str) -> Option<&'static str> {
    Some(match cmd {
        "geq" | "ge" => "≥",
        "leq" | "le" => "≤",
        "neq" | "ne" => "≠",
        "approx" => "≈",
        "equiv" => "≡",
        "sim" => "∼",
        "simeq" | "cong" => "≅",
        "times" => "×",
        "cdot" => "·",
        "cdots" => "⋯",
        "ldots" | "dots" => "…",
        "pm" => "±",
        "mp" => "∓",
        "infty" => "∞",
        "notin" => "∉",
        "in" => "∈",
        "subseteq" => "⊆",
        "subset" => "⊂",
        "supseteq" => "⊇",
        "supset" => "⊃",
        "cup" => "∪",
        "cap" => "∩",
        "otimes" => "⊗",
        "oplus" => "⊕",
        "perp" => "⊥",
        "ast" => "∗",
        "star" => "⋆",
        "circ" => "∘",
        "forall" => "∀",
        "exists" => "∃",
        "emptyset" | "varnothing" => "∅",
        "nabla" => "∇",
        "partial" => "∂",
        "rightarrow" | "to" => "→",
        "leftarrow" => "←",
        "mapsto" => "↦",
        "Rightarrow" => "⇒",
        "Leftarrow" => "⇐",
        "iff" | "Leftrightarrow" => "⇔",
        "langle" => "⟨",
        "rangle" => "⟩",
        "lfloor" => "⌊",
        "rfloor" => "⌋",
        "lceil" => "⌈",
        "rceil" => "⌉",
        "sum" => "∑",
        "prod" => "∏",
        "int" => "∫",
        "ell" => "ℓ",
        "alpha" => "α",
        "beta" => "β",
        "gamma" => "γ",
        "delta" => "δ",
        "epsilon" | "varepsilon" => "ε",
        "zeta" => "ζ",
        "eta" => "η",
        "theta" | "vartheta" => "θ",
        "iota" => "ι",
        "kappa" => "κ",
        "lambda" => "λ",
        "mu" => "μ",
        "nu" => "ν",
        "xi" => "ξ",
        "pi" | "varpi" => "π",
        "rho" | "varrho" => "ρ",
        "sigma" | "varsigma" => "σ",
        "tau" => "τ",
        "upsilon" => "υ",
        "phi" | "varphi" => "φ",
        "chi" => "χ",
        "psi" => "ψ",
        "omega" => "ω",
        "Gamma" => "Γ",
        "Delta" => "Δ",
        "Theta" => "Θ",
        "Lambda" => "Λ",
        "Xi" => "Ξ",
        "Pi" => "Π",
        "Sigma" => "Σ",
        "Upsilon" => "Υ",
        "Phi" => "Φ",
        "Psi" => "Ψ",
        "Omega" => "Ω",
        _ => return None,
    })
}

const fn to_blackboard_bold(c: char) -> char {
    match c {
        'C' => 'ℂ',
        'F' => '𝔽',
        'H' => 'ℍ',
        'N' => 'ℕ',
        'P' => 'ℙ',
        'Q' => 'ℚ',
        'R' => 'ℝ',
        'Z' => 'ℤ',
        other => other,
    }
}

fn escape_html(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    push_escaped_html(&mut out, raw);
    out
}

fn push_escaped_html(out: &mut String, raw: &str) {
    for ch in raw.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            other => out.push(other),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_render_inline_math_html_typesets_complexity_and_skips_currency() {
        let html = render_inline_math_html(
            "On the recently proposed $o(n \\log n)$ integer multiplication algorithm",
        );
        assert!(html.contains("<math><mi>o</mi><mo stretchy=\"false\">(</mo><mi>n</mi>"));
        assert!(html.contains("<mi mathvariant=\"normal\">log</mi>"));
        assert!(html.contains("<mi>n</mi><mo stretchy=\"false\">)</mo></math>"));

        let stripped = strip_inline_math_delimiters(
            "On the recently proposed $o(n \\log n)$ integer multiplication algorithm",
        );
        assert_eq!(
            stripped,
            "On the recently proposed o(n log n) integer multiplication algorithm"
        );

        // Currency must never be misinterpreted as inline math
        let currency = "Costs $5 today and $10 tomorrow (<script>x</script>).";
        let rendered_currency = render_inline_math_html(currency);
        assert!(!rendered_currency.contains("<math>"));
        assert!(rendered_currency.contains("$5 today and $10 tomorrow"));
        assert!(rendered_currency.contains("&lt;script&gt;"));
    }

    #[test]
    fn test_render_latex_expr_to_mathml_handles_scripts_fracs_greek_and_blackboard() {
        let html = render_inline_math_html(r"$O(n (\log n)^{1-\varepsilon})$ in $\mathbb{F}_2^m$");
        assert!(html.contains("<msup>"));
        assert!(html.contains("<mi>ε</mi>"));
        assert!(
            html.contains(
                "<msubsup><mi mathvariant=\"normal\">𝔽</mi><mn>2</mn><mi>m</mi></msubsup>"
            )
        );

        let frac = render_latex_expr_to_mathml(r"\frac{a + b}{\sqrt{n}}", true);
        assert!(frac.starts_with("<math display=\"block\"><mfrac>"));
        assert!(frac.contains("<msqrt><mi>n</mi></msqrt>"));
    }

    #[test]
    fn test_latex_expr_to_plain_text_formats_frac_and_blackboard() {
        assert_eq!(latex_expr_to_plain_text(r"\frac{a+1}{b}"), "a+1/b");
        assert_eq!(latex_expr_to_plain_text(r"x \in \mathbb{R}^2"), "x ∈ ℝ^2");
        assert_eq!(
            strip_inline_math_delimiters(r"Ratio $\frac{a+1}{b}$ over $\mathbb{R}$"),
            "Ratio a+1/b over ℝ"
        );
    }
}
