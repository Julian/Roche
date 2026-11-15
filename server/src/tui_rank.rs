use std::{
    collections::HashMap,
    fs::{File, OpenOptions},
    io::{Read, Write},
    process::{Command, Stdio},
};

use anyhow::{Context, Result};

use crate::{db::CatalogEntry, ranking::RankBoard};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClusterRankOutcome {
    Submitted(Vec<Vec<String>>),
    Skip,
    Quit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RankKey {
    Down,
    Up,
    MoveDown,
    MoveUp,
    MoveTo(usize),
    TieWithAbove,
    TieAll,
    Skip,
    OpenUrl,
    Submit,
    Cancel,
}

#[derive(Debug, Clone)]
pub struct ClusterRankState {
    entries_by_id: HashMap<String, CatalogEntry>,
    board: RankBoard,
    status_note: Option<String>,
}

impl ClusterRankState {
    #[must_use]
    pub fn new(entries: Vec<CatalogEntry>) -> Self {
        let ids: Vec<String> = entries.iter().map(|e| e.id.clone()).collect();
        let entries_by_id = entries.into_iter().map(|e| (e.id.clone(), e)).collect();
        Self { entries_by_id, board: RankBoard::new(ids), status_note: None }
    }

    #[cfg(test)]
    #[must_use]
    pub fn display_ranks(&self) -> Vec<usize> {
        self.board.flat_rows().into_iter().map(|r| r.display_rank).collect()
    }

    #[cfg(test)]
    #[must_use]
    pub fn cursor(&self) -> usize {
        self.board.selected_flat_index()
    }

    #[must_use]
    pub fn focused_entry(&self) -> Option<&CatalogEntry> {
        self.entries_by_id.get(self.board.selected_id())
    }

    pub fn set_status_note(&mut self, msg: impl Into<String>) {
        self.status_note = Some(msg.into());
    }

    #[must_use]
    pub fn to_ordering(&self) -> Vec<Vec<String>> {
        self.board.to_ordering()
    }

    pub fn apply_key(&mut self, key: RankKey) -> Option<ClusterRankOutcome> {
        let n = self.board.len();
        if n == 0 {
            return Some(ClusterRankOutcome::Quit);
        }
        self.status_note = None;

        match key {
            RankKey::Down => {
                self.board.select_next();
                None
            }
            RankKey::Up => {
                self.board.select_prev();
                None
            }
            RankKey::MoveDown => {
                self.board.move_down();
                None
            }
            RankKey::MoveUp => {
                self.board.move_up();
                None
            }
            RankKey::MoveTo(rank_one_based) => {
                if rank_one_based >= 1 && rank_one_based <= n {
                    self.board.move_to_rank_slot(rank_one_based - 1);
                    let placed = self.board.selected_flat_index();
                    if placed + 1 < n {
                        self.board.select_flat_index(placed + 1);
                    }
                }
                None
            }
            RankKey::TieWithAbove => {
                if self.board.selected_flat_index() == 0 {
                    self.status_note = Some(
                        "Move to row 2+ and press '=' to tie with the item above (or 't' to tie all)."
                            .to_owned(),
                    );
                } else {
                    self.board.toggle_tie_with_above();
                }
                None
            }
            RankKey::TieAll => {
                self.board.toggle_tie_all();
                None
            }
            RankKey::Skip => Some(ClusterRankOutcome::Skip),
            RankKey::OpenUrl => None,
            RankKey::Submit => Some(ClusterRankOutcome::Submitted(self.to_ordering())),
            RankKey::Cancel => Some(ClusterRankOutcome::Quit),
        }
    }

    fn render_ansi(&self, title: &str, iteration: usize, appended: usize) -> String {
        use std::fmt::Write as _;

        let mut buf = String::with_capacity(2048);
        // Clear screen & home cursor inside alternate buffer
        buf.push_str("\x1b[2J\x1b[H");
        let _ = write!(
            buf,
            "\x1b[1;38;5;130mRoche Rank\x1b[0m — \x1b[1m{}\x1b[0m  \x1b[2m(round {}, saved {})\x1b[0m\r\n",
            sanitize_line(title),
            iteration + 1,
            appended
        );
        buf.push_str(
            "\x1b[2mOrder best → worst. j/k or ↑/↓ focus · J/K or Shift+↑/↓ move · 1-9 send to rank · = tie above · t tie all · s skip · o open · Enter submit · q quit\x1b[0m\r\n\r\n",
        );

        let rows = self.board.flat_rows();
        let has_ties = rows.iter().any(|r| r.is_tied_with_above);
        for row in &rows {
            if has_ties && row.flat_index > 0 && !row.is_tied_with_above {
                buf.push_str("  \x1b[2m┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄\x1b[0m\r\n");
            }
            let Some(entry) = self.entries_by_id.get(&row.entry_id) else {
                continue;
            };
            let focused = row.is_selected;
            let cursor_glyph = if focused { "\x1b[1;38;5;166m▸\x1b[0m" } else { " " };
            let byline = if entry.creators.is_empty() {
                String::new()
            } else {
                format!("\x1b[3;2m{}\x1b[0m ", sanitize_line(&entry.creators.join(", ")))
            };
            let year_suffix =
                entry.release_year.map_or_else(String::new, |y| format!(" \x1b[2m({y})\x1b[0m"));
            let title_styled = if focused {
                format!("\x1b[1;4m{}\x1b[0m", sanitize_line(&entry.title))
            } else {
                sanitize_line(&entry.title)
            };
            let tie_badge = if row.is_tied_with_above { " \x1b[33m[=]\x1b[0m" } else { "" };
            let _ = write!(
                buf,
                " {cursor_glyph} {:2}. {byline}{title_styled}{year_suffix}{tie_badge}\r\n",
                row.display_rank
            );
        }

        if let Some(note) = &self.status_note {
            let _ = write!(buf, "\r\n  \x1b[33m{}\x1b[0m\r\n", sanitize_line(note));
        }
        buf
    }
}

struct RawTtyGuard {
    saved_stty: String,
}

impl RawTtyGuard {
    fn enter() -> Result<(Self, File, File)> {
        let tty_probe = OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/tty")
            .context("Interactive ranking requires an attached terminal (/dev/tty)")?;
        let stty_out = Command::new("stty")
            .arg("-g")
            .stdin(Stdio::from(tty_probe))
            .output()
            .context("Failed to query terminal settings via stty")?;
        if !stty_out.status.success() {
            anyhow::bail!("stty -g failed on /dev/tty");
        }
        let saved_stty = String::from_utf8_lossy(&stty_out.stdout).trim().to_owned();
        // Construct guard immediately so any failure below restores terminal state via Drop.
        let guard = Self { saved_stty };

        let tty_raw = OpenOptions::new().read(true).write(true).open("/dev/tty")?;
        // Include `-isig` so Ctrl+C delivers byte 0x03 (`RankKey::Cancel`) and unwinds cleanly
        // through `RawTtyGuard::drop` instead of killing the process with an un-restored TTY.
        let status = Command::new("stty")
            .args(["-icanon", "-echo", "-isig", "min", "1", "time", "0"])
            .stdin(Stdio::from(tty_raw))
            .status()
            .context("Failed to enable non-canonical mode via stty")?;
        if !status.success() {
            anyhow::bail!("stty raw mode configuration failed");
        }

        let reader = OpenOptions::new().read(true).open("/dev/tty")?;
        let mut writer = OpenOptions::new().write(true).open("/dev/tty")?;
        writer.write_all(b"\x1b[?1049h\x1b[?25l")?;
        writer.flush()?;

        Ok((guard, reader, writer))
    }
}

impl Drop for RawTtyGuard {
    fn drop(&mut self) {
        if let Ok(mut writer) = OpenOptions::new().write(true).open("/dev/tty") {
            let _ = writer.write_all(b"\x1b[?25h\x1b[?1049l");
            let _ = writer.flush();
        }
        if !self.saved_stty.is_empty()
            && let Ok(tty) = OpenOptions::new().read(true).write(true).open("/dev/tty")
        {
            let _ = Command::new("stty").arg(&self.saved_stty).stdin(Stdio::from(tty)).status();
        }
    }
}

pub fn prompt_cluster_tty(
    collection_title: &str,
    entries: Vec<CatalogEntry>,
    iteration: usize,
    appended: usize,
) -> Result<ClusterRankOutcome> {
    let (_guard, mut reader, mut writer) = RawTtyGuard::enter()?;
    let mut state = ClusterRankState::new(entries);

    loop {
        let frame = state.render_ansi(collection_title, iteration, appended);
        writer.write_all(frame.as_bytes())?;
        writer.flush()?;

        let Some(key) = read_next_key(&mut reader)? else {
            continue;
        };
        if key == RankKey::OpenUrl {
            match state.focused_entry().and_then(CatalogEntry::primary_open_url) {
                Some(url) => {
                    if let Err(e) = open_system_browser(&url) {
                        state.set_status_note(format!("Failed to open URL: {e}"));
                    } else {
                        state.set_status_note(format!("Opened {url}"));
                    }
                }
                None => state.set_status_note("No external URL available for this entry."),
            }
            continue;
        }
        if let Some(outcome) = state.apply_key(key) {
            return Ok(outcome);
        }
    }
}

fn read_next_key<R: Read>(reader: &mut R) -> Result<Option<RankKey>> {
    let mut buf = [0u8; 64];
    let n = reader.read(&mut buf)?;
    if n == 0 {
        return Ok(Some(RankKey::Cancel));
    }
    Ok(parse_rank_key(&buf[..n]))
}

fn parse_rank_key(bytes: &[u8]) -> Option<RankKey> {
    match bytes {
        [b'j', ..] | b"\x1b[B" | b"\x1bOB" => Some(RankKey::Down),
        [b'k', ..] | b"\x1b[A" | b"\x1bOA" => Some(RankKey::Up),
        [b'J', ..] | b"\x1b[1;2B" => Some(RankKey::MoveDown),
        [b'K', ..] | b"\x1b[1;2A" => Some(RankKey::MoveUp),
        [d @ b'1'..=b'9', ..] => Some(RankKey::MoveTo(usize::from(d - b'0'))),
        [b'=', ..] => Some(RankKey::TieWithAbove),
        [b't', ..] => Some(RankKey::TieAll),
        [b's', ..] => Some(RankKey::Skip),
        [b'o', ..] => Some(RankKey::OpenUrl),
        [b'\r' | b'\n', ..] => Some(RankKey::Submit),
        [b'q' | 0x03 | 0x04, ..] | [0x1b] => Some(RankKey::Cancel),
        _ => None,
    }
}

fn validate_browser_open_url(raw: &str) -> Result<url::Url> {
    let trimmed = raw.trim();
    if trimmed.is_empty() || trimmed.chars().any(char::is_control) {
        anyhow::bail!("Refusing to open empty or control-character URL");
    }
    let parsed = url::Url::parse(trimmed)?;
    if !matches!(parsed.scheme(), "http" | "https") {
        anyhow::bail!("Refusing to open non-http(s) URL scheme '{}'", parsed.scheme());
    }
    Ok(parsed)
}

pub fn open_system_browser(url: &str) -> Result<()> {
    let parsed = validate_browser_open_url(url)?;
    let safe_url = parsed.as_str();

    #[cfg(target_os = "macos")]
    let status = Command::new("open")
        .arg(safe_url)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()?;

    #[cfg(all(unix, not(target_os = "macos")))]
    let status = Command::new("xdg-open")
        .arg(safe_url)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()?;

    #[cfg(target_os = "windows")]
    let status = Command::new("rundll32.exe")
        .args(["url.dll,FileProtocolHandler", safe_url])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()?;

    if !status.success() {
        anyhow::bail!("Browser launcher exited with {status}");
    }
    Ok(())
}

fn sanitize_line(input: &str) -> String {
    input.chars().filter(|c| !c.is_control()).collect()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::db::EntryKind;

    fn sample_entry(id: &str, title: &str) -> CatalogEntry {
        let now = Utc::now();
        CatalogEntry {
            id: id.to_owned(),
            kind: EntryKind::Album,
            title: title.to_owned(),
            creators: vec!["Artist".to_owned()],
            url: None,
            published_at: None,
            release_year: Some(1997),
            summary: None,
            thumbnail_url: None,
            metadata: serde_json::json!({}),
            identifiers: Vec::new(),
            merged_into_id: None,
            created_at: now,
            updated_at: now,
        }
    }

    #[test]
    fn test_cluster_rank_state_reorder_move_to_and_pairwise_tie_buckets() {
        let entries = vec![
            sample_entry("a", "Alpha"),
            sample_entry("b", "Bravo"),
            sample_entry("c", "Charlie"),
            sample_entry("d", "Delta"),
        ];
        let mut state = ClusterRankState::new(entries);
        assert_eq!(state.display_ranks(), vec![1, 2, 3, 4]);

        // Move Charlie (pos 2) to rank 1 via `MoveTo(1)` -> order becomes [c, a, b, d], cursor advances to 1
        state.apply_key(RankKey::Down);
        state.apply_key(RankKey::Down);
        state.apply_key(RankKey::MoveTo(1));
        assert_eq!(
            state.to_ordering(),
            vec![
                vec!["c".to_owned()],
                vec!["a".to_owned()],
                vec!["b".to_owned()],
                vec!["d".to_owned()]
            ]
        );
        assert_eq!(state.cursor(), 1);

        // Pairwise `=` (`TieWithAbove`) at cursor 1 ties `c` and `a` ONLY (`#1 c = #1 a`, `#3 b`, `#4 d`)!
        state.apply_key(RankKey::TieWithAbove);
        assert_eq!(state.display_ranks(), vec![1, 1, 3, 4]);
        assert_eq!(
            state.to_ordering(),
            vec![vec!["c".to_owned(), "a".to_owned()], vec!["b".to_owned()], vec!["d".to_owned()]]
        );

        // Moving `b` (cursor 2) up jumps cleanly above tie group `["c", "a"]` without corrupting their tie!
        state.apply_key(RankKey::Down);
        assert_eq!(state.cursor(), 2);
        state.apply_key(RankKey::MoveUp);
        assert_eq!(
            state.to_ordering(),
            vec![vec!["b".to_owned()], vec!["c".to_owned(), "a".to_owned()], vec!["d".to_owned()]]
        );
        assert_eq!(state.display_ranks(), vec![1, 2, 2, 4]);

        // `TieAll` toggles all rows into a single tied bucket without exiting, and toggles back to singletons!
        assert_eq!(state.apply_key(RankKey::TieAll), None);
        assert_eq!(state.display_ranks(), vec![1, 1, 1, 1]);
        assert_eq!(
            state.to_ordering(),
            vec![vec!["b".to_owned(), "c".to_owned(), "a".to_owned(), "d".to_owned()]]
        );
        assert_eq!(state.apply_key(RankKey::TieAll), None);
        assert_eq!(state.display_ranks(), vec![1, 2, 3, 4]);
        assert_eq!(state.apply_key(RankKey::TieAll), None);
        assert_eq!(
            state.apply_key(RankKey::Submit),
            Some(ClusterRankOutcome::Submitted(vec![vec![
                "b".to_owned(),
                "c".to_owned(),
                "a".to_owned(),
                "d".to_owned()
            ]]))
        );
    }

    #[test]
    fn test_validate_browser_open_url_enforces_http_https_and_blocks_injection() {
        assert!(validate_browser_open_url("https://example.com/path?a=1&b=2").is_ok());
        assert!(validate_browser_open_url("http://localhost:8080/test").is_ok());
        assert!(validate_browser_open_url("javascript:alert(1)").is_err());
        assert!(validate_browser_open_url("file:///etc/passwd").is_err());
        assert!(validate_browser_open_url("-aCalculator").is_err());
        assert!(validate_browser_open_url("https://example.com/\nfoo").is_err());
    }

    #[test]
    fn test_read_next_key_consumes_long_escape_sequence_without_splitting_into_digits() {
        use std::io::Cursor;

        // xterm `modifyOtherKeys=2` Shift+Enter (`\x1b[27;2;13~`, 10 bytes) must be read in one shot
        // and ignored (`None`), never leaving trailing `3~` bytes that trigger `MoveTo(3)`.
        let mut cursor = Cursor::new(b"\x1b[27;2;13~".to_vec());
        assert_eq!(read_next_key(&mut cursor).unwrap(), None);
        assert_eq!(cursor.position(), 10);
        assert_eq!(read_next_key(&mut cursor).unwrap(), Some(RankKey::Cancel));
    }
}
