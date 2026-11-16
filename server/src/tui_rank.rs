use std::{
    collections::HashMap,
    io::Write,
    sync::{
        Once,
        atomic::{AtomicBool, Ordering},
    },
};

use anyhow::{Context, Result};
use crossterm::{
    cursor::{Hide, Show},
    event::{self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers},
    execute,
    terminal::{EnterAlternateScreen, LeaveAlternateScreen, disable_raw_mode, enable_raw_mode},
};
use ratatui::{
    Frame, Terminal,
    backend::CrosstermBackend,
    layout::{Constraint, Layout},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap},
};

use crate::{
    db::CatalogEntry,
    ranking::{RankBoard, RankBoardRow},
};

const ACCENT_COPPER: Color = Color::Indexed(130);
const ACCENT_FOCUS: Color = Color::Indexed(166);
const HELP_LINE: &str = "j/k or ↑/↓ focus · J/K or Shift+↑/↓ move · 1-9 send to rank · = tie above · t tie all · s skip · o open · Enter submit · q quit";
const COMPACT_HELP_LINE: &str =
    "j/k focus · J/K move · 1-9 rank · =/t tie · s skip · o open · Enter · q quit";

static TERMINAL_RAW_ACTIVE: AtomicBool = AtomicBool::new(false);
static PANIC_HOOK_ONCE: Once = Once::new();

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

    pub fn draw(&self, frame: &mut Frame<'_>, title: &str, iteration: usize, appended: usize) {
        let area = frame.area();
        let footer_height = self.compute_footer_height(area.width, area.height);
        let chunks = Layout::vertical([
            Constraint::Length(2),
            Constraint::Min(3),
            Constraint::Length(footer_height),
        ])
        .split(area);

        let header = Paragraph::new(vec![
            Line::from(vec![
                Span::styled(
                    "Roche Rank",
                    Style::default().fg(ACCENT_COPPER).add_modifier(Modifier::BOLD),
                ),
                Span::raw(" — "),
                Span::styled(sanitize_line(title), Style::default().add_modifier(Modifier::BOLD)),
                Span::styled(
                    format!("  (round {}, saved {appended})", iteration + 1),
                    Style::default().fg(Color::DarkGray),
                ),
            ]),
            Line::styled(
                "Order best → worst (ties share a rank bucket).",
                Style::default().fg(Color::DarkGray),
            ),
        ]);
        frame.render_widget(header, chunks[0]);

        let rows = self.board.flat_rows();
        let has_ties = rows.iter().any(|r| r.is_tied_with_above);
        let mut list_items = Vec::with_capacity(rows.len() * 2);
        let mut selected_visual_index = None;

        for row in &rows {
            if has_ties && row.flat_index > 0 && !row.is_tied_with_above {
                list_items.push(ListItem::new(Line::styled(
                    "   ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄",
                    Style::default().fg(Color::DarkGray),
                )));
            }
            let Some(entry) = self.entries_by_id.get(&row.entry_id) else {
                continue;
            };
            if row.is_selected {
                selected_visual_index = Some(list_items.len());
            }
            list_items.push(Self::format_cluster_row(row, entry));
        }

        let mut list_state = ListState::default().with_selected(selected_visual_index);
        let list = List::new(list_items).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray))
                .title(Span::styled(" Cluster ", Style::default().fg(ACCENT_COPPER))),
        );
        frame.render_stateful_widget(list, chunks[1], &mut list_state);

        let help_text = if area.width < 90 { COMPACT_HELP_LINE } else { HELP_LINE };
        let mut footer_lines = vec![Line::styled(help_text, Style::default().fg(Color::DarkGray))];
        if let Some(note) = &self.status_note {
            footer_lines
                .push(Line::styled(sanitize_line(note), Style::default().fg(Color::Yellow)));
        }
        let footer = Paragraph::new(footer_lines).wrap(Wrap { trim: true });
        frame.render_widget(footer, chunks[2]);
    }

    fn compute_footer_height(&self, width: u16, height: u16) -> u16 {
        let usable_w = usize::from(width.max(1));
        let help_text = if width < 90 { COMPACT_HELP_LINE } else { HELP_LINE };
        let help_rows = word_wrapped_rows(help_text, usable_w);
        let note_rows = self
            .status_note
            .as_deref()
            .map_or(0, |n| word_wrapped_rows(&sanitize_line(n), usable_w));
        let desired = u16::try_from(help_rows + note_rows).unwrap_or(6).max(2);
        let max_allowed = height.saturating_sub(5).max(1);
        desired.min(max_allowed)
    }

    fn format_cluster_row(row: &RankBoardRow, entry: &CatalogEntry) -> ListItem<'static> {
        let mut spans = Vec::with_capacity(7);
        if row.is_selected {
            spans.push(Span::styled(
                " ▸ ",
                Style::default().fg(ACCENT_FOCUS).add_modifier(Modifier::BOLD),
            ));
        } else {
            spans.push(Span::raw("   "));
        }
        spans.push(Span::styled(
            format!("{:2}. ", row.display_rank),
            if row.is_selected {
                Style::default().fg(ACCENT_FOCUS).add_modifier(Modifier::BOLD)
            } else {
                Style::default().fg(Color::Gray)
            },
        ));
        if !entry.creators.is_empty() {
            spans.push(Span::styled(
                format!("{} ", sanitize_line(&entry.creators.join(", "))),
                Style::default().fg(Color::DarkGray).add_modifier(Modifier::ITALIC),
            ));
        }
        let title_style = if row.is_selected {
            Style::default().add_modifier(Modifier::BOLD | Modifier::UNDERLINED)
        } else {
            Style::default()
        };
        spans.push(Span::styled(sanitize_line(&entry.title), title_style));
        if let Some(year) = entry.release_year {
            spans.push(Span::styled(format!(" ({year})"), Style::default().fg(Color::DarkGray)));
        }
        if row.is_tied_with_above {
            spans.push(Span::styled(
                " [=]",
                Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD),
            ));
        }
        ListItem::new(Line::from(spans))
    }
}

fn install_terminal_panic_hook() {
    PANIC_HOOK_ONCE.call_once(|| {
        let prev = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            if TERMINAL_RAW_ACTIVE.swap(false, Ordering::SeqCst) {
                if let Ok(mut tty) = open_tty_writer() {
                    let _ = execute!(tty, Show, LeaveAlternateScreen);
                    let _ = tty.flush();
                }
                let _ = disable_raw_mode();
            }
            prev(info);
        }));
    });
}

struct TerminalSession<W: Write> {
    terminal: Terminal<CrosstermBackend<W>>,
}

impl<W: Write> TerminalSession<W> {
    fn enter(writer: W) -> Result<Self> {
        install_terminal_panic_hook();
        let backend = CrosstermBackend::new(writer);
        let mut terminal =
            Terminal::new(backend).context("Failed to initialize Ratatui terminal")?;
        enable_raw_mode().context("Failed to enable terminal raw mode")?;
        TERMINAL_RAW_ACTIVE.store(true, Ordering::SeqCst);
        if let Err(err) = execute!(terminal.backend_mut(), EnterAlternateScreen, Hide) {
            TERMINAL_RAW_ACTIVE.store(false, Ordering::SeqCst);
            let _ = disable_raw_mode();
            return Err(err).context("Failed to enter alternate screen");
        }
        if let Err(err) = terminal.clear() {
            TERMINAL_RAW_ACTIVE.store(false, Ordering::SeqCst);
            let _ = execute!(terminal.backend_mut(), Show, LeaveAlternateScreen);
            let _ = terminal.backend_mut().flush();
            let _ = disable_raw_mode();
            return Err(err).context("Failed to clear alternate screen");
        }
        Ok(Self { terminal })
    }
}

impl<W: Write> Drop for TerminalSession<W> {
    fn drop(&mut self) {
        TERMINAL_RAW_ACTIVE.store(false, Ordering::SeqCst);
        let _ = execute!(self.terminal.backend_mut(), Show, LeaveAlternateScreen);
        let _ = self.terminal.backend_mut().flush();
        let _ = disable_raw_mode();
    }
}

#[cfg(unix)]
fn open_tty_writer() -> Result<std::fs::File> {
    std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/tty")
        .context("Interactive ranking requires an attached terminal (/dev/tty)")
}

#[cfg(not(unix))]
fn open_tty_writer() -> Result<std::io::Stdout> {
    use std::io::IsTerminal;

    let stdout = std::io::stdout();
    if !stdout.is_terminal() || !std::io::stdin().is_terminal() {
        anyhow::bail!("Interactive ranking requires an attached terminal");
    }
    Ok(stdout)
}

pub fn prompt_cluster_tty(
    collection_title: &str,
    entries: Vec<CatalogEntry>,
    iteration: usize,
    appended: usize,
) -> Result<ClusterRankOutcome> {
    let writer = open_tty_writer()?;
    let mut session = TerminalSession::enter(writer)?;
    let mut state = ClusterRankState::new(entries);

    loop {
        session.terminal.draw(|frame| state.draw(frame, collection_title, iteration, appended))?;

        let ev = event::read().context("Failed to read terminal event")?;
        let Event::Key(key_event) = ev else {
            continue;
        };
        let Some(key) = map_key_event(key_event) else {
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

#[must_use]
fn map_key_event(event: KeyEvent) -> Option<RankKey> {
    match event.kind {
        KeyEventKind::Press => {}
        KeyEventKind::Repeat => {
            return match event.code {
                KeyCode::Down | KeyCode::Char('j')
                    if !event.modifiers.intersects(KeyModifiers::SHIFT | KeyModifiers::ALT) =>
                {
                    Some(RankKey::Down)
                }
                KeyCode::Up | KeyCode::Char('k')
                    if !event.modifiers.intersects(KeyModifiers::SHIFT | KeyModifiers::ALT) =>
                {
                    Some(RankKey::Up)
                }
                _ => None,
            };
        }
        KeyEventKind::Release => return None,
    }

    if event.modifiers.contains(KeyModifiers::CONTROL) {
        return match event.code {
            KeyCode::Char('c' | 'C' | 'd' | 'D') => Some(RankKey::Cancel),
            _ => None,
        };
    }

    let move_modifier = event.modifiers.intersects(KeyModifiers::SHIFT | KeyModifiers::ALT);
    match event.code {
        KeyCode::Down if move_modifier => Some(RankKey::MoveDown),
        KeyCode::Up if move_modifier => Some(RankKey::MoveUp),
        KeyCode::Down | KeyCode::Char('j') => Some(RankKey::Down),
        KeyCode::Up | KeyCode::Char('k') => Some(RankKey::Up),
        KeyCode::Char('J') => Some(RankKey::MoveDown),
        KeyCode::Char('K') => Some(RankKey::MoveUp),
        KeyCode::Char(d @ '1'..='9') => Some(RankKey::MoveTo((d as u8 - b'0') as usize)),
        KeyCode::Char('=') => Some(RankKey::TieWithAbove),
        KeyCode::Char('t' | 'T') => Some(RankKey::TieAll),
        KeyCode::Char('s' | 'S') => Some(RankKey::Skip),
        KeyCode::Char('o' | 'O') => Some(RankKey::OpenUrl),
        KeyCode::Enter => Some(RankKey::Submit),
        KeyCode::Char('q' | 'Q') | KeyCode::Esc => Some(RankKey::Cancel),
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
    open::that_detached(parsed.as_str()).context("Failed to launch system browser")?;
    Ok(())
}

fn sanitize_line(input: &str) -> String {
    input.chars().filter(|c| !c.is_control()).collect()
}

fn word_wrapped_rows(text: &str, width: usize) -> usize {
    let max_w = width.max(1);
    let mut rows = 1usize;
    let mut col = 0usize;
    for word in text.split_whitespace() {
        let wlen = word.chars().count();
        if col == 0 {
            rows += wlen.saturating_sub(1) / max_w;
            col = wlen % max_w;
            if col == 0 && wlen > 0 {
                col = max_w;
            }
        } else if col + 1 + wlen <= max_w {
            col += 1 + wlen;
        } else {
            rows += 1 + wlen.saturating_sub(1) / max_w;
            col = wlen % max_w;
            if col == 0 && wlen > 0 {
                col = max_w;
            }
        }
    }
    rows
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use chrono::Utc;
    use ratatui::backend::TestBackend;

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
    fn test_map_key_event_handles_modifiers_and_ignores_release_or_unknown() {
        assert_eq!(
            map_key_event(KeyEvent::new_with_kind(
                KeyCode::Enter,
                KeyModifiers::SHIFT,
                KeyEventKind::Press,
            )),
            Some(RankKey::Submit)
        );
        assert_eq!(
            map_key_event(KeyEvent::new_with_kind(
                KeyCode::Up,
                KeyModifiers::SHIFT,
                KeyEventKind::Press,
            )),
            Some(RankKey::MoveUp)
        );
        assert_eq!(
            map_key_event(KeyEvent::new_with_kind(
                KeyCode::Char('c'),
                KeyModifiers::CONTROL,
                KeyEventKind::Press,
            )),
            Some(RankKey::Cancel)
        );
        assert_eq!(
            map_key_event(KeyEvent::new_with_kind(
                KeyCode::Char('3'),
                KeyModifiers::NONE,
                KeyEventKind::Release,
            )),
            None
        );
        assert_eq!(
            map_key_event(KeyEvent::new_with_kind(
                KeyCode::F(5),
                KeyModifiers::NONE,
                KeyEventKind::Press,
            )),
            None
        );
    }

    #[test]
    fn test_ratatui_render_displays_ties_and_status_note() {
        let mut state = ClusterRankState::new(vec![
            sample_entry("a", "OK Computer"),
            sample_entry("b", "Kid A"),
        ]);
        state.apply_key(RankKey::Down);
        state.apply_key(RankKey::TieWithAbove);
        state.set_status_note("Opened https://example.com");

        let backend = TestBackend::new(100, 16);
        let mut terminal = Terminal::new(backend).unwrap();
        terminal.draw(|frame| state.draw(frame, "Albums", 0, 2)).unwrap();

        let rendered: String = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect();
        assert!(rendered.contains("Roche Rank"));
        assert!(rendered.contains("OK Computer"));
        assert!(rendered.contains("Kid A"));
        assert!(rendered.contains("[=]"));
        assert!(rendered.contains("Opened https://example.com"));
    }

    #[test]
    fn test_ratatui_render_narrow_terminal_preserves_footer_and_note() {
        let mut state = ClusterRankState::new(vec![
            sample_entry("a", "OK Computer"),
            sample_entry("b", "Kid A"),
        ]);
        state.set_status_note("No URL on focused entry");

        let backend = TestBackend::new(40, 12);
        let mut terminal = Terminal::new(backend).unwrap();
        terminal.draw(|frame| state.draw(frame, "Albums", 0, 0)).unwrap();

        let rendered: String = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(ratatui::buffer::Cell::symbol)
            .collect();
        assert!(rendered.contains("q quit"));
        assert!(rendered.contains("No URL on focused entry"));
    }
}
