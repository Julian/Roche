use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    process::{Command, Stdio},
};

use anyhow::{Context, Result};

use crate::db::CatalogEntry;

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
    TieRest,
    TieAll,
    Skip,
    OpenUrl,
    Submit,
    Cancel,
}

#[derive(Debug, Clone)]
pub struct ClusterRankState {
    entries: Vec<CatalogEntry>,
    order: Vec<usize>,
    /// `tied[pos]` is true when `pos > 0` and `pos` is tied with `pos - 1`.
    tied: Vec<bool>,
    cursor: usize,
    status_note: Option<String>,
}

impl ClusterRankState {
    #[must_use]
    pub fn new(entries: Vec<CatalogEntry>) -> Self {
        let n = entries.len();
        Self {
            entries,
            order: (0..n).collect(),
            tied: vec![false; n],
            cursor: 0,
            status_note: None,
        }
    }

    #[must_use]
    pub fn display_ranks(&self) -> Vec<usize> {
        let mut ranks = Vec::with_capacity(self.order.len());
        let mut cur = 1usize;
        for pos in 0..self.order.len() {
            if pos > 0 && !self.tied[pos] {
                cur = pos + 1;
            }
            ranks.push(cur);
        }
        ranks
    }

    #[must_use]
    pub fn focused_entry(&self) -> Option<&CatalogEntry> {
        self.order.get(self.cursor).and_then(|&idx| self.entries.get(idx))
    }

    pub fn set_status_note(&mut self, msg: impl Into<String>) {
        self.status_note = Some(msg.into());
    }

    #[must_use]
    pub fn to_ordering(&self) -> Vec<Vec<String>> {
        let mut groups: Vec<Vec<String>> = Vec::new();
        for (pos, &idx) in self.order.iter().enumerate() {
            let id = self.entries[idx].id.clone();
            if pos > 0 && self.tied[pos] {
                if let Some(last) = groups.last_mut() {
                    last.push(id);
                }
            } else {
                groups.push(vec![id]);
            }
        }
        groups
    }

    pub fn apply_key(&mut self, key: RankKey) -> Option<ClusterRankOutcome> {
        let n = self.order.len();
        if n == 0 {
            return Some(ClusterRankOutcome::Quit);
        }
        self.status_note = None;

        match key {
            RankKey::Down => {
                if self.cursor + 1 < n {
                    self.cursor += 1;
                }
                None
            }
            RankKey::Up => {
                self.cursor = self.cursor.saturating_sub(1);
                None
            }
            RankKey::MoveDown => {
                if self.cursor + 1 < n {
                    self.order.swap(self.cursor, self.cursor + 1);
                    self.cursor += 1;
                }
                None
            }
            RankKey::MoveUp => {
                if self.cursor > 0 {
                    self.order.swap(self.cursor - 1, self.cursor);
                    self.cursor -= 1;
                }
                None
            }
            RankKey::MoveTo(rank_one_based) => {
                if rank_one_based >= 1 && rank_one_based <= n {
                    let target = rank_one_based - 1;
                    while self.cursor > target {
                        self.order.swap(self.cursor - 1, self.cursor);
                        self.cursor -= 1;
                    }
                    while self.cursor < target {
                        self.order.swap(self.cursor, self.cursor + 1);
                        self.cursor += 1;
                    }
                    if target + 1 < n {
                        self.cursor = target + 1;
                    }
                }
                None
            }
            RankKey::TieRest => {
                if self.cursor + 1 < n {
                    let already_one_group =
                        !self.tied[self.cursor] && (self.cursor + 1..n).all(|i| self.tied[i]);
                    for i in (self.cursor + 1)..n {
                        self.tied[i] = !already_one_group;
                    }
                    if !already_one_group {
                        self.tied[self.cursor] = false;
                    }
                }
                None
            }
            RankKey::TieAll => {
                let all_ids: Vec<String> =
                    self.order.iter().map(|&idx| self.entries[idx].id.clone()).collect();
                Some(ClusterRankOutcome::Submitted(vec![all_ids]))
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
            "\x1b[2mOrder best → worst. j/k or ↑/↓ focus · J/K or Shift+↑/↓ move · 1-9 send to rank · = tie below · t tie all · s skip · o open · Enter submit · q quit\x1b[0m\r\n\r\n",
        );

        let ranks = self.display_ranks();
        let has_ties = self.tied.iter().any(|&t| t);
        for (pos, &idx) in self.order.iter().enumerate() {
            if has_ties && pos > 0 && !self.tied[pos] {
                buf.push_str("  \x1b[2m┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄\x1b[0m\r\n");
            }
            let entry = &self.entries[idx];
            let focused = pos == self.cursor;
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
            let tie_badge = if pos > 0 && self.tied[pos] { " \x1b[33m[=]\x1b[0m" } else { "" };
            let _ = write!(
                buf,
                " {cursor_glyph} {:2}. {byline}{title_styled}{year_suffix}{tie_badge}\r\n",
                ranks[pos]
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

        let tty_raw = OpenOptions::new().read(true).write(true).open("/dev/tty")?;
        let status = Command::new("stty")
            .args(["-icanon", "-echo", "min", "1", "time", "0"])
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

        Ok((Self { saved_stty }, reader, writer))
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

fn read_next_key(reader: &mut File) -> Result<Option<RankKey>> {
    let mut buf = [0u8; 8];
    let n = reader.read(&mut buf)?;
    if n == 0 {
        return Ok(Some(RankKey::Cancel));
    }
    match &buf[..n] {
        [b'j', ..] | b"\x1b[B" | b"\x1bOB" => Ok(Some(RankKey::Down)),
        [b'k', ..] | b"\x1b[A" | b"\x1bOA" => Ok(Some(RankKey::Up)),
        [b'J', ..] | b"\x1b[1;2B" => Ok(Some(RankKey::MoveDown)),
        [b'K', ..] | b"\x1b[1;2A" => Ok(Some(RankKey::MoveUp)),
        [d @ b'1'..=b'9', ..] => Ok(Some(RankKey::MoveTo(usize::from(d - b'0')))),
        [b'=', ..] => Ok(Some(RankKey::TieRest)),
        [b't', ..] => Ok(Some(RankKey::TieAll)),
        [b's', ..] => Ok(Some(RankKey::Skip)),
        [b'o', ..] => Ok(Some(RankKey::OpenUrl)),
        [b'\r' | b'\n', ..] => Ok(Some(RankKey::Submit)),
        [b'q' | 0x03 | 0x04, ..] | [0x1b] => Ok(Some(RankKey::Cancel)),
        _ => Ok(None),
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
    fn test_cluster_rank_state_reorder_move_to_and_tie_groups() {
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
        assert_eq!(state.cursor, 1);

        // Press `=` (`TieRest`) at cursor 1 -> ties `a`, `b`, `d` into one group `[1, 2, 2, 2]`
        state.apply_key(RankKey::TieRest);
        assert_eq!(state.display_ranks(), vec![1, 2, 2, 2]);
        assert_eq!(
            state.to_ordering(),
            vec![vec!["c".to_owned()], vec!["a".to_owned(), "b".to_owned(), "d".to_owned()]]
        );

        // Pressing `=` again at the same group start splits back into singletons
        state.apply_key(RankKey::TieRest);
        assert_eq!(state.display_ranks(), vec![1, 2, 3, 4]);

        // `TieAll` submits single all-tied group
        assert_eq!(
            state.apply_key(RankKey::TieAll),
            Some(ClusterRankOutcome::Submitted(vec![vec![
                "c".to_owned(),
                "a".to_owned(),
                "b".to_owned(),
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
}
