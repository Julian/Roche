# Roche

A self-hosted personal learning, reading, listening, and media catalog tracker with local-first multiplatform sync.

Track RSS/Atom blogs, arXiv papers, podcast episodes, YouTube subscriptions, bookmarks, books, films, TV series, and albums in one unified catalog—keeping both your active inbox and long-term consumption diary + rankings under your control.

## Overview & Capabilities

- **Stack**:
  - **Server & CLI (`server/`)**: Rust 2024 (`axum`, `sqlx` compile-time checked queries over `STRICT` SQLite WAL + FTS5 tables, embedded SSR + ES2022 PJAX Web UI, `ratatui` + `crossterm` terminal TUI, and background feed/media workers).
  - **Android (`android/`)**: Local-first Kotlin client (`Jetpack Compose`, `Navigation 3` with predictive back, `Room 3` + bundled SQLite FTS5, platform `HttpEngine` HTTP/2+HTTP/3 transport, `ImageDecoder` hardware bitmaps, `AndroidX Media3` `ExoPlayer` + `MediaSessionService` background audio with silence-skipping, cooperative HRW feed polling, and bidirectional peer sync).
- **Unified Media Catalog & Dual Ranking Model**:
  - **Shared Entry Graph**: Feed items, saved articles/PDFs/videos/episodes, manual media logs (`album`, `book`, `movie`, `tv`, `song`), and collections share a unified entry & scheme-normalized identifier space (`spotify`, `musicbrainz_release`, `imdb`, `wikipedia`, `url`, `feed_url`, `slug`) with tombstone-aware deduplication and merge convergence across peers.
  - **Global Quality vs Local Priority**: Pairwise comparisons on consumed works update global quality ratings via **Expectation Propagation (EP)** (`S`/`A`/`B`/`C`/`D` tiers + `0–10` scores) that propagate across dynamic/manual lists, whereas comparisons inside `want:*` queues stay strictly local (`scope_collection_id`) for priority ordering.
  - **Collection Generators**: Any manual list can enable an active random draw workflow with streaming/external deep-links, finish/abandon tracking, and immediate post-completion cluster ranking.
- **CLI & TUI**:
  - Daemon & pairing: `roche` (alias `roche serve`), `roche pair`, `roche discover`, `roche devices <list|revoke>`.
  - Subscriptions & backups: `roche import <file.opml> [--poll]`, `roche export [-o file.opml]`, `roche backup [--include-media] [--no-compress] [-o file]`, `roche backup <list|prune|verify|restore>`.
  - Catalog & ranking: `roche consumed <kind> <title> [--creator ...] [--year ...] [--rank]`, `roche want <kind> <title> [--notes ...]`, `roche lists [--want]`, `roche show <target> [--want]`, `roche draw [status|enable|disable|finish|abandon]`, `roche rank <collection_slug_or_id>` (interactive bucket-tie TUI), and `roche catalog apply <file.json>` (atomic declarative batch upsert/log/rank).
- **Configuration**:
  - Overrides apply in order: CLI flags (`--host`, `--port`, `--data-dir`, `--advertise-service`) > `ROCHE_*` env vars (`ROCHE_CONFIG`, `ROCHE_HOST`, `ROCHE_PORT`, `ROCHE_DATA_DIR`, `ROCHE_TAILSCALE_AUTO_AUTH_USERS`, etc.) > `./config.toml` > defaults (`127.0.0.1:8080`, `./data`).

## Security & Deployment Notes

- **HTTPS via Tailscale (`roche serve`)**: `roche serve` binds locally (`127.0.0.1:8080`) and automatically configures `tailscale serve` for per-peer HTTPS (`https://<node>.<tailnet>.ts.net`, or `:8443` if `443` is already in use) plus the host-independent Tailscale Service `svc:roche` (`https://roche.<tailnet>.ts.net` on `443` when hosted on an ACL-tagged node). Android disables cleartext HTTP globally except loopback and gates `.ts.net` / CGNAT peer traffic on an active VPN transport.
- **`tailscale.auto_auth_users` Single-User Caveat**: `tailscale.auto_auth_users` defaults to `[]` (disabled). When enabled, Roche trusts `Tailscale-User-Login` headers arriving on loopback TCP connections from `tailscale serve`; enable it only on single-user hosts where no untrusted local users or non-stripping loopback proxies share `127.0.0.1` / `::1`.
- **SSRF & Content Isolation**: Both the Rust server (`SafeHttpClient`) and Android on-device poller/media cache (`PublicUrlValidator`) validate resolved IP addresses against private/loopback/link-local ranges on every hop, enforce strict `Content-Security-Policy` headers on web and inline PDF endpoints, and load reader images with `referrerpolicy="no-referrer"`.
- **Storage Hygiene & Backups**: All server state lives under `storage.data_dir` (`roche.db`, `media/`, and `backups/`), created with owner-only `0700` directory and `0600` file permissions on Unix. `roche serve` automatically takes daily Zstandard-compressed (`VACUUM INTO`, integrity-verified) database backups into `backup.dir` (default `<data_dir>/backups`, keeping the last 3 days, 1 from the last week, and 1 from the last month—configurable via `[backup]` / `ROCHE_BACKUP_*`). Run `roche backup` (optionally `--include-media`), `roche backup list`, `roche backup verify <file>`, or—after stopping `roche serve`—`roche backup restore <file> --force` on demand.
