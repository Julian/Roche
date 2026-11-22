/// Returns `true` for Unicode bidirectional overrides, isolates, marks, and line/paragraph
/// separators that can visually reorder or spoof terminal output.
#[must_use]
const fn is_bidi_or_invisible_format_char(ch: char) -> bool {
    matches!(
        ch,
        '\u{061C}'
            | '\u{200E}'
            | '\u{200F}'
            | '\u{2028}'..='\u{202E}'
            | '\u{2066}'..='\u{2069}'
            | '\u{FEFF}'
    )
}

/// Strips ASCII/Unicode control characters (including ANSI escape sequences' ESC/CSI)
/// and Unicode bidirectional override/isolate/mark characters before printing untrusted text
/// to a terminal or TUI buffer.
#[must_use]
pub fn sanitize_terminal_text(input: &str) -> String {
    input.chars().filter(|&c| !c.is_control() && !is_bidi_or_invisible_format_char(c)).collect()
}

#[cfg(test)]
mod tests {
    use super::sanitize_terminal_text;

    #[test]
    fn test_sanitize_terminal_text_strips_controls_and_bidi_overrides() {
        let malicious = concat!(
            "Safe\x1b[31mRed",
            "\u{202E}txt.exe\u{202C}",
            "\u{2066}LRI\u{2067}RLI\u{2068}FSI\u{2069}PDI",
            "\u{061C}\u{200E}\u{200F}\u{2028}\u{2029}\u{FEFF}",
            "\r\n\tTitle — こんにちは"
        );
        assert_eq!(
            sanitize_terminal_text(malicious),
            "Safe[31mRedtxt.exeLRIRLIFSIPDITitle — こんにちは"
        );
    }
}
