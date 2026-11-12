# Roche

A self-hosted personal learning and content tracker with multiplatform support.

In other words: track your RSS feeds, podcast episodes, YouTube subscriptions, bookmarks and browser tabs in one curated place and keep track of what you've read and consumed.

## Overview & Operations

- **Stack**: Rust daemon and CLI in `server/` (Axum, SQLite WAL + FTS5, background feed/media workers) paired with a local-first Android client in `android/` (Jetpack Compose, Room 3, cooperative HRW polling, and peer sync).
- **CLI**: Run `roche` (alias `roche serve`) to start the server; use `roche pair`, `roche discover`, `roche devices <list|revoke>`, `roche import <file.opml> [--poll]`, and `roche export [-o file.opml]` for device pairing and OPML migration.
- **Configuration**: Overrides apply in order: CLI flags (`--host`, `--port`, `--data-dir`, `--advertise-service`) > `ROCHE_*` env vars (`ROCHE_CONFIG`, `ROCHE_HOST`, `ROCHE_PORT`, `ROCHE_DATA_DIR`, etc.) > `./config.toml` > defaults (`127.0.0.1:8080`, `./data`).
- **Tailscale & Auth**: Set `tailscale.advertise_service = "svc:roche"` (or `--advertise-service svc:roche`) to expose HTTPS on your Tailnet via `tailscale serve` and enable client auto-discovery; first boot prints a single-use pairing code (`roche pair` mints new ones).
- **Content & Media**: Feed and saved-page HTML is sanitized inline for offline reader mode, arXiv/direct PDFs are archived automatically, and YouTube feeds optionally archive video/thumbnails via `yt-dlp` (`media.ytdlp_path`).
- **Storage & Backups**: All state lives under `storage.data_dir` (`roche.db` and `media/`); snapshot the live WAL database safely with `sqlite3 roche.db ".backup roche.bak"` and copy `media/`.
