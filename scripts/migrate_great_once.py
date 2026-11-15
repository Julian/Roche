#!/usr/bin/env python3
"""Thin CLI wrapper delegating Great -> Roche migration to `roche migrate-great`.

The canonical implementation lives in `server/src/migrate_great.rs` so identifier
normalization, deterministic UUIDs, album edition collapsing, and schema migrations
share a single source of truth with the Rust server.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

ALBUMS_1001_COLLECTION_ID = "9eb44de7-bdfb-5bea-85d1-be913c542d8b"
REPO_ROOT = Path(__file__).resolve().parent.parent


def deterministic_entity_uuid(kind: str, natural_key: str) -> str:
    h = hashlib.sha256()
    h.update(b"roche:entity:v1:")
    h.update(kind.encode("utf-8"))
    h.update(b":")
    h.update(natural_key.encode("utf-8"))
    raw = bytearray(h.digest()[:16])
    raw[6] = (raw[6] & 0x0F) | 0x50
    raw[8] = (raw[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(raw)))


def deterministic_system_collection_id(slug: str) -> str:
    return deterministic_entity_uuid("system_collection", slug.strip().lower())


def deterministic_catalog_entry_id(kind: str, scheme: str, value: str) -> str:
    key = f"{kind.strip().lower()}\0{scheme.strip().lower()}\0{value.strip()}"
    return deterministic_entity_uuid("entry", key)


@dataclass
class MigrationStats:
    seeded_1001_albums: int = 0
    entries_upserted: int = 0
    want_entries_added: int = 0
    consumption_logs_added: int = 0
    comparisons_added: int = 0
    active_1001_album_id: str | None = None


def _latest_server_source_mtime() -> float:
    server_dir = REPO_ROOT / "server"
    latest = 0.0
    for marker in (
        REPO_ROOT / "Cargo.toml",
        REPO_ROOT / "Cargo.lock",
        server_dir / "Cargo.toml",
    ):
        if marker.is_file():
            latest = max(latest, marker.stat().st_mtime)
    for subdir in (server_dir / "src", server_dir / "migrations"):
        if subdir.is_dir():
            for path in subdir.rglob("*"):
                if path.is_file():
                    latest = max(latest, path.stat().st_mtime)
    return latest


def _resolve_roche_binary() -> Path:
    env_bin = os.environ.get("ROCHE_BIN", "").strip()
    if env_bin:
        candidate = Path(env_bin).expanduser().resolve()
        if candidate.is_file():
            return candidate
        raise FileNotFoundError(f"ROCHE_BIN points to missing binary: {candidate}")

    exe_name = "roche-server.exe" if sys.platform == "win32" else "roche-server"
    src_mtime = _latest_server_source_mtime()

    candidates: list[Path] = []
    cargo_target_dir = os.environ.get("CARGO_TARGET_DIR", "").strip()
    if cargo_target_dir:
        custom_target = Path(cargo_target_dir).expanduser().resolve()
        candidates.extend(
            [
                custom_target / "debug" / exe_name,
                custom_target / "release" / exe_name,
            ]
        )
    candidates.extend(
        [
            REPO_ROOT / "server" / "target" / "debug" / exe_name,
            REPO_ROOT / "server" / "target" / "release" / exe_name,
            REPO_ROOT / "target" / "debug" / exe_name,
            REPO_ROOT / "target" / "release" / exe_name,
        ]
    )
    fresh_candidates = [
        p for p in candidates if p.is_file() and p.stat().st_mtime >= src_mtime
    ]
    if fresh_candidates:
        return max(fresh_candidates, key=lambda p: p.stat().st_mtime)

    manifest = REPO_ROOT / "server" / "Cargo.toml"
    subprocess.run(
        [
            "cargo",
            "build",
            "--manifest-path",
            str(manifest),
            "--bin",
            "roche-server",
            "--locked",
        ],
        check=True,
    )
    rebuilt_candidates = [
        p for p in candidates if p.is_file() and p.stat().st_mtime >= src_mtime
    ]
    if rebuilt_candidates:
        return max(rebuilt_candidates, key=lambda p: p.stat().st_mtime)
    existing = [p for p in candidates if p.is_file()]
    if existing:
        return max(existing, key=lambda p: p.stat().st_mtime)
    raise RuntimeError("Failed to locate built `roche-server` binary after `cargo build`")


def migrate_great_repo(
    great_dir: Path,
    roche_db_path: Path,
    pool_json_path: Path | None = None,
    *,
    dry_run: bool = False,
) -> MigrationStats:
    resolved_great = great_dir.expanduser().resolve()
    if not (resolved_great / "great.toml").is_file():
        raise FileNotFoundError(f"No great.toml found in {resolved_great}")

    resolved_db = roche_db_path.expanduser().resolve()
    roche_bin = _resolve_roche_binary()

    cmd = [
        str(roche_bin),
        "migrate-great",
        str(resolved_great),
        "--db-path",
        str(resolved_db),
        "--json",
    ]
    if pool_json_path is not None:
        cmd.extend(["--pool-json", str(pool_json_path.expanduser().resolve())])
    if dry_run:
        cmd.append("--dry-run")

    try:
        proc = subprocess.run(cmd, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exc:
        if exc.stderr:
            sys.stderr.write(exc.stderr)
        raise
    payload = json.loads(proc.stdout.strip())
    return MigrationStats(
        seeded_1001_albums=int(payload["seeded_1001_albums"]),
        entries_upserted=int(payload["entries_upserted"]),
        want_entries_added=int(payload["want_entries_added"]),
        consumption_logs_added=int(payload["consumption_logs_added"]),
        comparisons_added=int(payload["comparisons_added"]),
        active_1001_album_id=payload.get("active_1001_album_id"),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Migrate a Great repository into Roche's unified SQLite DB (`roche migrate-great`)."
    )
    parser.add_argument(
        "great_dir",
        type=Path,
        help="Path to the Great data repository directory (containing great.toml).",
    )
    parser.add_argument(
        "--roche-db",
        required=True,
        type=Path,
        help="Path to Roche's SQLite database (roche.db).",
    )
    parser.add_argument(
        "--pool-json",
        type=Path,
        default=None,
        help="Optional override path to albums_1001_pool.json.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate migration inside a rolled-back transaction without modifying disk.",
    )
    args = parser.parse_args(argv)

    stats = migrate_great_repo(
        args.great_dir,
        args.roche_db,
        args.pool_json,
        dry_run=args.dry_run,
    )
    suffix = " (dry run — rolled back)" if args.dry_run else ""
    print(
        f"Migrated Great -> Roche successfully{suffix}:\n"
        f"  1001 Albums seeded:     {stats.seeded_1001_albums}\n"
        f"  Catalog entries added:  {stats.entries_upserted}\n"
        f"  Want queue memberships: {stats.want_entries_added}\n"
        f"  Consumption log rows:   {stats.consumption_logs_added}\n"
        f"  Comparisons imported:   {stats.comparisons_added}\n"
        f"  Active 1001 Album ID:   {stats.active_1001_album_id or '(none)'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
