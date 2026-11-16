# Roche

A self-hosted personal learning, reading, listening, and media catalog tracker with local-first multiplatform sync.

Track RSS/Atom blogs, arXiv papers, podcast episodes, YouTube subscriptions, bookmarks, books, films, TV series, and albums in one unified catalog—keeping both your active inbox and long-term consumption diary + rankings under your control.

## Overview & Capabilities

- **Stack**:
  - **Server & CLI (`server/`)**: Rust 2024 (`axum`, `sqlx` compile-time checked queries over `STRICT` SQLite WAL + FTS5 tables, embedded SSR + ES2022 PJAX Web UI, `ratatui` + `crossterm` terminal TUI, and background feed/media workers).
  - **Android (`android/`)**: Local-first Kotlin client (`Jetpack Compose`, `Navigation 3` with predictive back, `Room 3` + bundled SQLite FTS5, platform `HttpEngine` HTTP/2+HTTP/3 transport, `ImageDecoder` hardware bitmaps, `AndroidX Media3` `ExoPlayer` + `MediaSessionService` background audio with silence-skipping, cooperative HRW feed polling, and bidirectional peer sync).
- **Unified Media Catalog & Dual Ranking Model**:
  - **Shared Entry Graph**: Feed items, saved articles/PDFs/videos/episodes, manual media logs (`album`, `book`, `movie`, `tv`, `song`), and collections share a unified entry & scheme-normalized identifier space (`spotify`, `1001albums`, `imdb`, `wikipedia`, `url`, `feed_url`, `slug`) with tombstone-aware deduplication and merge convergence across peers.
  - **Global Quality vs Local Priority**: Pairwise comparisons on consumed works update global quality ratings via **Expectation Propagation (EP)** (`S`/`A`/`B`/`C`/`D` tiers + `0–10` scores) that propagate across dynamic/manual lists, whereas comparisons inside `want:*` queues stay strictly local (`scope_collection_id`) for priority ordering.
  - **1001 Albums Generator**: Built-in daily draw workflow over the bundled 1,101-album canon with streaming deep-links, listen/skip tracking, and immediate post-listen cluster ranking.
- **CLI & TUI**:
  - Daemon & pairing: `roche` (alias `roche serve`), `roche pair`, `roche discover`, `roche devices <list|revoke>`.
  - Subscriptions: `roche import <file.opml> [--poll]`, `roche export [-o file.opml]`.
  - Catalog & ranking: `roche consumed <kind> <title> [--creator ...] [--year ...] [--rank]`, `roche want <kind> <title> [--notes ...]`, `roche lists [--want]`, `roche show <target> [--want]`, `roche rank <collection_slug_or_id>` (interactive bucket-tie TUI), and `roche catalog apply <file.json>` (atomic declarative batch upsert/log/rank).
- **Configuration**:
  - Overrides apply in order: CLI flags (`--host`, `--port`, `--data-dir`, `--advertise-service`) > `ROCHE_*` env vars (`ROCHE_CONFIG`, `ROCHE_HOST`, `ROCHE_PORT`, `ROCHE_DATA_DIR`, `ROCHE_TAILSCALE_AUTO_AUTH_USERS`, etc.) > `./config.toml` > defaults (`127.0.0.1:8080`, `./data`).

## Security & Deployment Notes

- **HTTPS via Tailscale (`tailscale serve`)**: Expose Roche over HTTPS on port `443` using `tailscale.advertise_service = "svc:roche"` (or `tailscale serve`) and pair Android clients using the `https://<node-or-service>.<tailnet>.ts.net` URL. Android disables cleartext HTTP globally except loopback, and background sync gates any `.ts.net` / CGNAT peer traffic on an active VPN transport when appropriate.
- **`tailscale.auto_auth_users` Single-User Caveat**: `tailscale.auto_auth_users` defaults to `[]` (disabled). When enabled, Roche trusts `Tailscale-User-Login` headers arriving on loopback TCP connections from `tailscale serve`; enable it only on single-user hosts where no untrusted local users or non-stripping loopback proxies share `127.0.0.1` / `::1`.
- **SSRF & Content Isolation**: Both the Rust server (`SafeHttpClient`) and Android on-device poller/media cache (`PublicUrlValidator`) validate resolved IP addresses against private/loopback/link-local ranges on every hop, enforce strict `Content-Security-Policy` headers on web and inline PDF endpoints, and load reader images with `referrerpolicy="no-referrer"`.
- **Storage Hygiene & Backups**: All server state lives under `storage.data_dir` (`roche.db` and `media/`), created with owner-only `0700` directory and `0600` file permissions on Unix. Snapshot the live WAL database safely with `sqlite3 data/roche.db ".backup data/roche.bak"` and copy `data/media/`.
