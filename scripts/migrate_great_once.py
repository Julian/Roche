#!/usr/bin/env python3
"""One-time standalone migration from a Great data repository into Roche's unified SQLite DB.

This script is intentionally standalone (Python 3.11+ stdlib only; not compiled into the
`roche` binary) so Great's TOML/JSONL git repository can be retired cleanly after migrating
existing items, want queues, diary logs, comparisons, and cached provider exports into
`roche.db`.

Key invariants enforced:
1. Seeds the bundled `albums_1001_pool.json` into `1001 Albums` (`9eb44de7-bdfb-5bea-85d1-be913c542d8b`)
   using the exact deterministic UUIDs shared by Rust and Android.
2. Collapses album edition variants (`(Deluxe)`, `[Remastered]`, etc.) sharing case-folded
   `(artist, base_title)` into the canonical album entry.
3. Marks `album` entries `consumed` ONLY from user-curated `items/albums.toml`, explicit album
   rows in `log/*.jsonl`, or revealed `sources/albumsgenerator.json` history.
4. `sources/spotify.json` streaming completions log `consumed` ONLY on `song` entries (plus
   engaging `artist` entries) — single-track plays NEVER mark parent `album` entries `consumed`,
   preserving unheard albums in the `1001 Albums` generator pool.
5. Maps `comparisons/*.jsonl` to `scope_collection_id = NULL` (global quality) and
   `comparisons/want/*.jsonl` to `scope_collection_id = <want_collection_id>` (local priority).
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import UTC, datetime
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import tomllib
from typing import Any
from urllib.parse import unquote, urlparse, urlsplit, urlunsplit
import uuid

ALBUMS_1001_COLLECTION_ID = "9eb44de7-bdfb-5bea-85d1-be913c542d8b"

KIND_PLURAL: dict[str, str] = {
    "movie": "movies",
    "tv": "tv",
    "song": "songs",
    "album": "albums",
    "artist": "artists",
    "podcast": "podcasts",
    "podcast_episode": "podcast_episodes",
    "book": "books",
    "game": "games",
}
PLURAL_TO_KIND: dict[str, str] = {v: k for k, v in KIND_PLURAL.items()}

KIND_DISPLAY_LABEL: dict[str, str] = {
    "movie": "Movies",
    "tv": "TV",
    "song": "Songs",
    "album": "Albums",
    "artist": "Artists",
    "podcast": "Podcasts",
    "podcast_episode": "Podcast Episodes",
    "book": "Books",
    "game": "Games",
}

MUSIC_KINDS: frozenset[str] = frozenset({"album", "song"})
PARENT_KIND: dict[str, str] = {
    "song": "album",
    "podcast_episode": "podcast",
}

_ALBUM_EDITION_RE = re.compile(
    r"\s*[(\[][^()\[\]]*?\b(?:"
    r"deluxe|standard|remaster(?:ed)?|expanded|special|limited|"
    r"anniversary|bonus|edition|version|reissue|explicit|clean"
    r")\b[^()\[\]]*?[)\]]\s*$",
    re.IGNORECASE,
)

_SPOTIFY_BARE_ID_RE = re.compile(r"^[A-Za-z0-9]{22}$")
_READ_PLAYED = 1
_MAX_STAR_RATING = 5


# ---------------------------------------------------------------------------
# Deterministic UUID helpers (byte-identical to server/src/db/sync/identity.rs)
# ---------------------------------------------------------------------------


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


def canonicalize_sync_url(raw: str) -> str:
    trimmed = raw.strip()
    parts = urlsplit(trimmed)
    if not parts.scheme or not parts.netloc:
        return trimmed.lower()
    scheme = parts.scheme.lower()
    netloc = parts.netloc.lower()
    path = parts.path or ("/" if scheme in ("http", "https", "ws", "wss", "ftp") else "")
    return urlunsplit((scheme, netloc, path, parts.query, parts.fragment))


def deterministic_system_collection_id(slug: str) -> str:
    return deterministic_entity_uuid("system_collection", slug.strip().lower())


def deterministic_catalog_entry_id(kind: str, scheme: str, value: str) -> str:
    key = f"{kind.strip().lower()}\0{scheme.strip().lower()}\0{value.strip()}"
    return deterministic_entity_uuid("entry", key)


def deterministic_title_entry_id(kind: str, title: str) -> str:
    key = f"{kind.strip().lower()}\0{title.strip().lower()}"
    return deterministic_entity_uuid("entry_title", key)


def deterministic_consumption_log_id(entry_id: str, ts_millis: int, status: str) -> str:
    key = f"{entry_id.strip()}\0{ts_millis}\0{status.strip().lower()}"
    return deterministic_entity_uuid("consumption_log", key)


def canonical_ordering_json(ordering: list[list[str]]) -> str:
    normalized = [sorted(item.strip() for item in group if item.strip()) for group in ordering]
    return json.dumps(normalized, separators=(",", ":"))


def deterministic_comparison_id(
    scope_collection_id: str | None,
    ts_millis: int,
    ordering: list[list[str]],
) -> str:
    scope_key = (
        scope_collection_id.strip()
        if scope_collection_id and scope_collection_id.strip()
        else "global"
    )
    ord_json = canonical_ordering_json(ordering)
    return deterministic_entity_uuid("comparison", f"{scope_key}\0{ts_millis}\0{ord_json}")


# ---------------------------------------------------------------------------
# Normalization & Parsing Helpers
# ---------------------------------------------------------------------------


def strip_album_edition_suffix(title: str) -> str:
    cur = title.strip()
    while True:
        nxt = _ALBUM_EDITION_RE.sub("", cur).strip()
        if nxt == cur or not nxt:
            return cur
        cur = nxt


def normalize_spotify_value(value: str, entry_kind: str) -> str:
    trimmed = value.strip()
    default_entity = {
        "song": "track",
        "artist": "artist",
        "podcast": "show",
        "podcast_episode": "episode",
    }.get(entry_kind, "album")

    if trimmed.startswith("spotify:"):
        parts = [p for p in trimmed.split(":") if p]
        if len(parts) >= 3:
            return f"spotify:{parts[-2].lower()}:{parts[-1]}"
        if len(parts) == 2:
            return f"spotify:{default_entity}:{parts[1]}"

    if "open.spotify.com/" in trimmed:
        parsed = urlparse(trimmed)
        segs = [s for s in parsed.path.split("/") if s and not s.startswith("intl-")]
        if len(segs) >= 2 and segs[0] in ("album", "track", "artist", "show", "episode"):
            return f"spotify:{segs[0]}:{segs[1]}"

    if _SPOTIFY_BARE_ID_RE.match(trimmed):
        return f"spotify:{default_entity}:{trimmed}"

    return trimmed


def normalize_identifier(scheme: str, value: str, entry_kind: str) -> tuple[str, str]:
    clean_scheme = scheme.strip().lower().replace("-", "_")
    clean_val = value.strip()
    if clean_scheme == "spotify":
        clean_val = normalize_spotify_value(clean_val, entry_kind)
    elif clean_scheme in ("url", "feed_url"):
        clean_val = canonicalize_sync_url(clean_val)
    elif clean_scheme == "imdb" and clean_val.startswith("http"):
        for seg in urlparse(clean_val).path.split("/"):
            if seg.startswith("tt") and seg[2:].isdigit():
                clean_val = seg
                break
    elif clean_scheme == "wikipedia" and clean_val.startswith("http"):
        slug = _wikipedia_slug(clean_val)
        if slug:
            clean_val = slug
    return clean_scheme, clean_val


def infer_identifier_from_great_id(kind: str, raw_id: str) -> tuple[str, str] | None:
    trimmed = raw_id.strip()
    if not trimmed:
        return None
    if trimmed.startswith("spotify:"):
        return normalize_identifier("spotify", trimmed, kind)
    if trimmed.startswith("1001albums:"):
        return ("1001albums_uuid", trimmed.removeprefix("1001albums:"))
    if trimmed.startswith("tt") and trimmed[2:].isdigit():
        return ("imdb", trimmed)
    if trimmed.startswith(("http://", "https://")):
        if kind == "podcast":
            return normalize_identifier("feed_url", trimmed, kind)
        if kind == "podcast_episode" and "#" in trimmed:
            return ("antennapod_episode", trimmed)
        return normalize_identifier("url", trimmed, kind)
    return None


def _wikipedia_slug(url: str) -> str | None:
    parsed = urlparse(url)
    if parsed.netloc != "en.wikipedia.org":
        return None
    prefix = "/wiki/"
    if not parsed.path.startswith(prefix):
        return None
    return unquote(parsed.path[len(prefix) :]) or None


def _parse_year(release_date: str | None) -> int | None:
    if not release_date or len(release_date) < 4:
        return None
    try:
        return int(release_date[:4])
    except ValueError:
        return None


def _parse_iso_utc(raw: str | datetime) -> datetime:
    dt = raw if isinstance(raw, datetime) else datetime.fromisoformat(str(raw))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def _format_sql_ts(dt: datetime) -> str:
    utc_dt = dt.astimezone(UTC)
    return utc_dt.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _dt_millis(dt: datetime) -> int:
    return int(round(dt.astimezone(UTC).timestamp() * 1000))


def _select_1001ag_cover_url(album: dict[str, Any]) -> str | None:
    images = album.get("images")
    if isinstance(images, list):
        candidates: list[tuple[int, str]] = []
        for img in images:
            if isinstance(img, dict):
                url = str(img.get("url") or "").strip()
                if url.startswith(("http://", "https://")):
                    width = int(img.get("width") or 0)
                    candidates.append((width, url))
        if candidates:
            return max(candidates, key=lambda x: x[0])[1]
    direct = str(album.get("image") or "").strip()
    return direct if direct.startswith(("http://", "https://")) else None


def _format_1001ag_notes(entry: dict[str, Any]) -> str | None:
    rating = int(entry.get("rating") or 0)
    review = str(entry.get("review") or "").strip()
    parts: list[str] = []
    if 1 <= rating <= _MAX_STAR_RATING:
        parts.append("★" * rating + "☆" * (_MAX_STAR_RATING - rating))
    if review:
        parts.append(review)
    return " — ".join(parts) if parts else None


# ---------------------------------------------------------------------------
# Migration Data Structures & Engine
# ---------------------------------------------------------------------------


@dataclass
class MigrationStats:
    seeded_1001_albums: int = 0
    entries_upserted: int = 0
    want_entries_added: int = 0
    consumption_logs_added: int = 0
    comparisons_added: int = 0
    active_1001_album_id: str | None = None


@dataclass
class RawGreatItem:
    id: str
    kind: str
    title: str
    year: int | None = None
    creators: list[str] = field(default_factory=list)
    parent_id: str | None = None
    external_ids: dict[str, str] = field(default_factory=dict)
    metadata: dict[str, Any] = field(default_factory=dict)
    thumbnail_url: str | None = None
    url: str | None = None
    is_user_source: bool = False


class GreatMigrator:
    def __init__(
        self,
        conn: sqlite3.Connection,
        great_root: Path,
        pool_json_path: Path,
    ) -> None:
        self.conn = conn
        self.great_root = great_root
        self.pool_json_path = pool_json_path
        self.now_dt = datetime.now(tz=UTC)
        self.now_sql = _format_sql_ts(self.now_dt)
        self.stats = MigrationStats()
        # Maps (kind, great_item_id) -> roche entries.id
        self.id_map: dict[tuple[str, str], str] = {}
        # Fallback map great_item_id -> roche entries.id
        self.any_id_map: dict[str, str] = {}
        # Index (artist_cf, base_title_cf) -> roche album entry_id for edition merging
        self.album_edition_index: dict[tuple[str, str], str] = {}

    def verify_schema(self) -> None:
        row = self.conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'entries'"
        ).fetchone()
        if row is None:
            raise RuntimeError(
                "Target SQLite database does not contain the unified 'entries' table "
                "(migration 010_unified_entries_model.sql). Run `roche lists` against "
                "this database first so SQLx applies all migrations, then re-run."
            )

    def run(self) -> MigrationStats:
        self.verify_schema()
        with self.conn:
            self._seed_1001_albums_pool()
            self._index_existing_albums()

            list_name_to_kind = self._load_great_config_lists()
            user_source_keys = self._import_user_items()
            self._import_albumsgenerator_source()
            self._import_antennapod_source()
            self._import_spotify_source()
            self._import_user_logs()
            self._ensure_user_catalog_consumed_logs(user_source_keys)
            self._engage_music_artists_from_consumed_works()
            self._import_wants()
            self._import_comparisons(list_name_to_kind)

        return self.stats

    # -- Step 1: 1001-Albums Pool Seeding -----------------------------------

    def _seed_1001_albums_pool(self) -> None:
        if not self.pool_json_path.is_file():
            raise FileNotFoundError(f"Missing 1001 albums pool JSON at {self.pool_json_path}")
        pool_albums: list[dict[str, Any]] = json.loads(
            self.pool_json_path.read_text(encoding="utf-8")
        )

        # Ensure system 1001_albums collection + generator row exist
        self.conn.execute(
            """
            INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
            VALUES (?, 'collection', '1001 Albums', ?, ?)
            """,
            (ALBUMS_1001_COLLECTION_ID, self.now_sql, self.now_sql),
        )
        self.conn.execute(
            """
            INSERT OR IGNORE INTO collection_specs
                (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
            VALUES (?, '1001_albums', 'manual', 'system', NULL, 0)
            """,
            (ALBUMS_1001_COLLECTION_ID,),
        )
        self.conn.execute(
            """
            INSERT OR IGNORE INTO collection_generators (collection_id, enabled, updated_at)
            VALUES (?, 0, ?)
            """,
            (ALBUMS_1001_COLLECTION_ID, self.now_sql),
        )

        for idx, album in enumerate(pool_albums, start=1):
            spotify_uri = f"spotify:album:{album['spotify_id']}"
            existing_id = self._lookup_entry_by_identifier("spotify", spotify_uri)
            if existing_id is not None:
                target_id = existing_id
            else:
                target_id = album["id"]
                creators_json = json.dumps([album["artist"]])
                metadata_json = json.dumps(
                    {
                        "genres": album.get("genres", []),
                        "styles": album.get("styles", []),
                    }
                )
                cur = self.conn.execute(
                    """
                    INSERT OR IGNORE INTO entries
                        (id, kind, title, creators_json, url, release_year, thumbnail_url,
                         metadata_json, created_at, updated_at)
                    VALUES (?, 'album', ?, ?, NULL, ?, ?, ?, ?, ?)
                    """,
                    (
                        target_id,
                        album["title"],
                        creators_json,
                        album.get("year"),
                        album.get("cover_url"),
                        metadata_json,
                        self.now_sql,
                        self.now_sql,
                    ),
                )
                if cur.rowcount > 0:
                    self.stats.seeded_1001_albums += 1

            self._attach_identifier(target_id, "spotify", spotify_uri, "album")
            self._attach_identifier(target_id, "1001albums", album["slug"], "album")
            self.conn.execute(
                """
                INSERT OR IGNORE INTO collection_entries (collection_id, entry_id, position, added_at)
                VALUES (?, ?, ?, ?)
                """,
                (ALBUMS_1001_COLLECTION_ID, target_id, float(idx), self.now_sql),
            )
            self._register_id_mapping("album", spotify_uri, target_id)

    def _index_existing_albums(self) -> None:
        rows = self.conn.execute(
            """
            SELECT id, title, creators_json
            FROM entries
            WHERE kind = 'album' AND merged_into_id IS NULL
            """
        ).fetchall()
        for entry_id, title, creators_json in rows:
            try:
                creators = json.loads(creators_json)
            except ValueError:
                creators = []
            key = self._album_match_key(creators, title)
            if key is not None and key not in self.album_edition_index:
                self.album_edition_index[key] = entry_id

    @staticmethod
    def _album_match_key(creators: list[str], title: str) -> tuple[str, str] | None:
        if not creators:
            return None
        artist = creators[0].strip().casefold()
        base = strip_album_edition_suffix(title).casefold().strip()
        if not artist or not base:
            return None
        return (artist, base)

    # -- Step 2: Config & User-Source Items ---------------------------------

    def _load_great_config_lists(self) -> dict[str, str]:
        cfg_path = self.great_root / "great.toml"
        mapping = dict(PLURAL_TO_KIND)
        if not cfg_path.is_file():
            return mapping
        with cfg_path.open("rb") as f:
            data = tomllib.load(f)
        for lst in data.get("lists", []):
            name = str(lst.get("name") or "").strip()
            kind = str(lst.get("kind") or "").strip()
            if name and kind:
                mapping[name] = kind
        return mapping

    def _read_toml_items(self, path: Path, kind: str, *, is_user_source: bool) -> list[RawGreatItem]:
        if not path.is_file():
            return []
        with path.open("rb") as f:
            data = tomllib.load(f)
        out: list[RawGreatItem] = []
        for raw in data.get("items", []):
            title = str(raw["title"]).strip()
            item_id = str(raw.get("id") or title).strip()
            creators = [str(c).strip() for c in raw.get("creators", []) if str(c).strip()]
            ext_ids = {
                str(k): str(v)
                for k, v in (raw.get("external_ids") or {}).items()
                if v is not None and str(v).strip()
            }
            metadata = dict(raw.get("metadata") or {})
            thumb = str(metadata.pop("image_url", "")).strip() or None
            url_val = str(ext_ids.get("url") or "").strip() or None
            out.append(
                RawGreatItem(
                    id=item_id,
                    kind=kind,
                    title=title,
                    year=int(raw["year"]) if raw.get("year") is not None else None,
                    creators=creators,
                    parent_id=str(raw["parent_id"]).strip() if raw.get("parent_id") else None,
                    external_ids=ext_ids,
                    metadata=metadata,
                    thumbnail_url=thumb,
                    url=url_val,
                    is_user_source=is_user_source,
                )
            )
        return out

    def _import_user_items(self) -> list[tuple[str, str, str]]:
        """Imports `items/<kind_plural>.toml` (parents before children).

        Returns `[(kind, great_id, roche_entry_id), ...]` for baseline consumption logging.
        """
        ordered_kinds = [
            "artist",
            "album",
            "podcast",
            "movie",
            "tv",
            "book",
            "game",
            "song",
            "podcast_episode",
        ]
        user_entries: list[tuple[str, str, str]] = []
        for kind in ordered_kinds:
            plural = KIND_PLURAL[kind]
            items = self._read_toml_items(
                self.great_root / "items" / f"{plural}.toml",
                kind,
                is_user_source=True,
            )
            for item in items:
                entry_id = self._upsert_great_item(item)
                user_entries.append((kind, item.id, entry_id))
        return user_entries

    # -- Step 3: Provider Caches (`sources/*.json`) -------------------------

    def _import_albumsgenerator_source(self) -> None:
        path = self.great_root / "sources" / "albumsgenerator.json"
        if not path.is_file():
            return
        data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
        paused = bool(data.get("paused", False))

        unrevealed_candidate: tuple[str, datetime | None] | None = None

        for hist in data.get("history", []):
            album_obj = hist.get("album")
            if not isinstance(album_obj, dict) or not album_obj.get("name"):
                continue
            raw_item = self._raw_item_from_1001ag_album(album_obj)
            entry_id = self._upsert_great_item(raw_item)
            gen_dt = (
                _parse_iso_utc(hist["generatedAt"])
                if hist.get("generatedAt")
                else self.now_dt
            )
            if hist.get("revealedAlbum"):
                self._insert_consumption_log(
                    entry_id=entry_id,
                    ts=gen_dt,
                    status="consumed",
                    notes=_format_1001ag_notes(hist),
                )
            else:
                unrevealed_candidate = (entry_id, gen_dt)

        current_album_obj = data.get("currentAlbum")
        if isinstance(current_album_obj, dict) and current_album_obj.get("name"):
            raw_cur = self._raw_item_from_1001ag_album(current_album_obj)
            cur_entry_id = self._upsert_great_item(raw_cur)
            if not self._is_consumed_or_abandoned(cur_entry_id):
                drawn_dt = unrevealed_candidate[1] if unrevealed_candidate else self.now_dt
                unrevealed_candidate = (cur_entry_id, drawn_dt)

        active_id: str | None = None
        drawn_sql: str | None = None
        if unrevealed_candidate is not None and not self._is_consumed_or_abandoned(
            unrevealed_candidate[0]
        ):
            active_id = unrevealed_candidate[0]
            drawn_sql = _format_sql_ts(unrevealed_candidate[1] or self.now_dt)

        enabled_int = 0 if paused else 1
        if enabled_int == 1 and active_id is None:
            # Draw a remaining album deterministically if enabled with no pending draw
            rem_row = self.conn.execute(
                """
                SELECT ce.entry_id
                FROM collection_entries ce
                JOIN entries e ON e.id = ce.entry_id
                WHERE ce.collection_id = ?
                  AND e.merged_into_id IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM consumption_log cl
                      WHERE cl.entry_id = ce.entry_id AND cl.status IN ('consumed', 'abandoned')
                  )
                ORDER BY ce.position ASC, ce.entry_id ASC
                LIMIT 1
                """,
                (ALBUMS_1001_COLLECTION_ID,),
            ).fetchone()
            if rem_row is not None:
                active_id = str(rem_row[0])
                drawn_sql = self.now_sql

        self.conn.execute(
            """
            UPDATE collection_generators
            SET enabled = ?,
                current_entry_id = COALESCE(?, current_entry_id),
                drawn_at = COALESCE(?, drawn_at),
                updated_at = ?
            WHERE collection_id = ?
            """,
            (enabled_int, active_id, drawn_sql, self.now_sql, ALBUMS_1001_COLLECTION_ID),
        )
        self.stats.active_1001_album_id = active_id

    def _raw_item_from_1001ag_album(self, album: dict[str, Any]) -> RawGreatItem:
        spotify_id = str(album.get("spotifyId") or "").strip()
        ag_uuid = str(album.get("uuid") or "").strip()
        ag_slug = str(album.get("slug") or "").strip()
        great_id = f"spotify:album:{spotify_id}" if spotify_id else f"1001albums:{ag_uuid}"

        ext_ids: dict[str, str] = {}
        if ag_slug:
            ext_ids["1001albums"] = ag_slug
        if ag_uuid:
            ext_ids["1001albums_uuid"] = ag_uuid
        if spotify_id:
            ext_ids["spotify"] = f"spotify:album:{spotify_id}"
        for scheme, key in (
            ("apple_music", "appleMusicId"),
            ("tidal", "tidalId"),
            ("youtube_music", "youtubeMusicId"),
            ("qobuz", "qobuzId"),
            ("deezer", "deezerId"),
        ):
            val = str(album.get(key) or "").strip()
            if val:
                ext_ids[scheme] = val
        wiki_url = str(album.get("wikipediaUrl") or "").strip()
        if wiki_url:
            slug = _wikipedia_slug(wiki_url)
            if slug:
                ext_ids["wikipedia"] = slug

        artist = str(album.get("artist") or "").strip()
        return RawGreatItem(
            id=great_id,
            kind="album",
            title=str(album["name"]).strip(),
            year=_parse_year(album.get("releaseDate")),
            creators=[artist] if artist else [],
            external_ids=ext_ids,
            thumbnail_url=_select_1001ag_cover_url(album),
        )

    def _import_antennapod_source(self) -> None:
        path = self.great_root / "sources" / "antennapod.json"
        if not path.is_file():
            return
        data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
        feeds_by_url: dict[str, dict[str, Any]] = {}
        podcast_entry_by_url: dict[str, str] = {}

        for feed in data.get("feeds", []):
            feed_url = str(feed.get("feed_url") or "").strip()
            if not feed_url:
                continue
            feeds_by_url[feed_url] = feed
            ext_ids: dict[str, str] = {"feed_url": feed_url}
            if feed.get("feed_identifier"):
                ext_ids["feed_identifier"] = str(feed["feed_identifier"])
            author = str(feed.get("author") or "").strip()
            pod_item = RawGreatItem(
                id=feed_url,
                kind="podcast",
                title=str(feed.get("title") or feed_url).strip(),
                creators=[author] if author else [],
                external_ids=ext_ids,
                thumbnail_url=str(feed.get("image_url") or "").strip() or None,
            )
            podcast_entry_by_url[feed_url] = self._upsert_great_item(pod_item)

        engaged_podcasts: dict[str, datetime] = {}
        for ep in data.get("episodes", []):
            feed_url = str(ep.get("feed_url") or "").strip()
            if feed_url not in feeds_by_url:
                continue
            guid = str(ep.get("item_identifier") or "").strip()
            great_ep_id = f"{feed_url}#{guid}"
            parent_feed = feeds_by_url[feed_url]
            meta: dict[str, Any] = {}
            if parent_feed.get("title"):
                meta["parent_title"] = parent_feed["title"]
            if ep.get("duration_ms"):
                meta["duration_ms"] = ep["duration_ms"]
            if ep.get("is_favorite"):
                meta["favorite"] = True

            ep_item = RawGreatItem(
                id=great_ep_id,
                kind="podcast_episode",
                title=str(ep.get("title") or guid).strip(),
                parent_id=feed_url,
                external_ids={
                    "feed_url": feed_url,
                    "rss_guid": f"{canonicalize_sync_url(feed_url)}\0{guid}",
                    "guid": guid,
                },
                metadata=meta,
                thumbnail_url=str(ep.get("image_url") or "").strip() or None,
            )
            ep_entry_id = self._upsert_great_item(ep_item)

            completed_ms = ep.get("completed_at_ms")
            if ep.get("read") == _READ_PLAYED and completed_ms:
                ts = datetime.fromtimestamp(float(completed_ms) / 1000.0, tz=UTC)
                notes = "★ favorite" if ep.get("is_favorite") else None
                self._insert_consumption_log(
                    entry_id=ep_entry_id,
                    ts=ts,
                    status="consumed",
                    notes=notes,
                )
                pod_id = podcast_entry_by_url.get(feed_url)
                if pod_id is not None:
                    prev = engaged_podcasts.get(pod_id)
                    if prev is None or ts < prev:
                        engaged_podcasts[pod_id] = ts

        for pod_id, first_ts in engaged_podcasts.items():
            self._ensure_entry_consumed_once(pod_id, first_ts)

    def _import_spotify_source(self) -> None:
        """Imports `sources/spotify.json`.

        CRITICAL INVARIANT: Streaming completions log `consumed` ONLY on `song` entries!
        Parent `album` entries are created/matched for catalog hierarchy (`collection_entries`),
        never marked `consumed`, so single-track plays never exclude unheard albums from
        `1001 Albums`.
        """
        path = self.great_root / "sources" / "spotify.json"
        if not path.is_file():
            return
        data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))

        for album in data.get("albums", []):
            artist = str(album.get("artist") or "").strip()
            alb_item = RawGreatItem(
                id=str(album["id"]),
                kind="album",
                title=str(album["title"]).strip(),
                creators=[artist] if artist else [],
            )
            self._upsert_great_item(alb_item)

        for track in data.get("tracks", []):
            uri = str(track["uri"]).strip()
            artist = str(track.get("artist") or "").strip()
            song_item = RawGreatItem(
                id=uri,
                kind="song",
                title=str(track.get("title") or uri).strip(),
                creators=[artist] if artist else [],
                parent_id=str(track["album_id"]) if track.get("album_id") else None,
                external_ids={"spotify": uri},
            )
            self._upsert_great_item(song_item)

        for completion in data.get("completions", []):
            uri = str(completion["track_uri"]).strip()
            song_entry_id = self.id_map.get(("song", uri)) or self._lookup_entry_by_identifier(
                "spotify", normalize_spotify_value(uri, "song")
            )
            if song_entry_id is None:
                song_entry_id = self._upsert_great_item(
                    RawGreatItem(
                        id=uri,
                        kind="song",
                        title=uri,
                        external_ids={"spotify": uri},
                    )
                )
            ts = _parse_iso_utc(completion["ts"])
            self._insert_consumption_log(entry_id=song_entry_id, ts=ts, status="consumed")

    # -- Step 4: Diary Logs, Artist Synthesis & Want Queues -----------------

    def _import_user_logs(self) -> None:
        log_dir = self.great_root / "log"
        if not log_dir.is_dir():
            return
        for path in sorted(log_dir.glob("*.jsonl")):
            for raw_line in path.read_text(encoding="utf-8").splitlines():
                line = raw_line.strip()
                if not line:
                    continue
                rec = json.loads(line)
                kind = str(rec["kind"]).strip()
                great_item_id = str(rec["item"]).strip()
                status = str(rec["status"]).strip()
                notes = str(rec["notes"]).strip() if rec.get("notes") else None
                ts = _parse_iso_utc(rec["ts"])

                entry_id = self._resolve_or_create_placeholder(kind, great_item_id)
                self._insert_consumption_log(
                    entry_id=entry_id,
                    ts=ts,
                    status=status,
                    notes=notes,
                )

    def _ensure_user_catalog_consumed_logs(
        self,
        user_source_keys: list[tuple[str, str, str]],
    ) -> None:
        """Items in Great's `items/<kind>.toml` represent consumed works even if predating diary logs."""
        for _kind, _great_id, entry_id in user_source_keys:
            self._ensure_entry_consumed_once(entry_id, self.now_dt)

    def _engage_music_artists_from_consumed_works(self) -> None:
        """Synthesizes `artist` entries for music creators and marks artists of consumed music engaged."""
        rows = self.conn.execute(
            """
            SELECT e.id, e.creators_json,
                   (SELECT MIN(cl.ts) FROM consumption_log cl
                    WHERE cl.entry_id = e.id AND cl.status = 'consumed') AS first_consumed_ts
            FROM entries e
            WHERE e.kind IN ('album', 'song') AND e.merged_into_id IS NULL
            """
        ).fetchall()

        for work_id, creators_json, first_consumed_ts in rows:
            try:
                creators = json.loads(creators_json)
            except ValueError:
                creators = []
            for raw_name in creators:
                name = str(raw_name).strip()
                if not name:
                    continue
                artist_id = self._ensure_artist_entry(name)
                if artist_id != work_id:
                    self.conn.execute(
                        """
                        INSERT OR IGNORE INTO collection_entries
                            (collection_id, entry_id, position, added_at)
                        VALUES (?, ?, 0, ?)
                        """,
                        (artist_id, work_id, self.now_sql),
                    )
                if first_consumed_ts:
                    self._ensure_entry_consumed_once(artist_id, _parse_iso_utc(first_consumed_ts))

    def _ensure_artist_entry(self, name: str) -> str:
        cached = self.id_map.get(("artist", name))
        if cached is not None:
            return cached
        row = self.conn.execute(
            """
            SELECT id FROM entries
            WHERE kind = 'artist' AND merged_into_id IS NULL AND LOWER(title) = LOWER(?)
            LIMIT 1
            """,
            (name,),
        ).fetchone()
        if row is not None:
            artist_id = str(row[0])
        else:
            artist_id = deterministic_title_entry_id("artist", name)
            cur = self.conn.execute(
                """
                INSERT OR IGNORE INTO entries
                    (id, kind, title, creators_json, metadata_json, created_at, updated_at)
                VALUES (?, 'artist', ?, '[]', '{}', ?, ?)
                """,
                (artist_id, name, self.now_sql, self.now_sql),
            )
            if cur.rowcount > 0:
                self.stats.entries_upserted += 1
        self._register_id_mapping("artist", name, artist_id)
        return artist_id

    def _import_wants(self) -> None:
        want_dir = self.great_root / "want"
        if not want_dir.is_dir():
            return
        for kind, plural in KIND_PLURAL.items():
            items = self._read_toml_items(
                want_dir / f"{plural}.toml",
                kind,
                is_user_source=False,
            )
            if not items:
                continue
            want_col_id = self._ensure_want_collection(kind, plural)
            for pos, item in enumerate(items, start=1):
                entry_id = self._upsert_great_item(item)
                if self._has_status(entry_id, "consumed"):
                    continue
                cur = self.conn.execute(
                    """
                    INSERT OR IGNORE INTO collection_entries
                        (collection_id, entry_id, position, added_at)
                    VALUES (?, ?, ?, ?)
                    """,
                    (want_col_id, entry_id, float(pos), self.now_sql),
                )
                if cur.rowcount > 0:
                    self.stats.want_entries_added += 1

    def _ensure_want_collection(self, kind: str, plural: str) -> str:
        want_slug = f"want:{plural}"
        existing = self.conn.execute(
            "SELECT collection_id FROM collection_specs WHERE slug = ?",
            (want_slug,),
        ).fetchone()
        if existing is not None:
            return str(existing[0])
        col_id = deterministic_system_collection_id(want_slug)
        label = f"Want: {KIND_DISPLAY_LABEL.get(kind, plural)}"
        self.conn.execute(
            """
            INSERT OR IGNORE INTO entries (id, kind, title, created_at, updated_at)
            VALUES (?, 'collection', ?, ?, ?)
            """,
            (col_id, label, self.now_sql, self.now_sql),
        )
        self.conn.execute(
            """
            INSERT OR IGNORE INTO collection_specs
                (collection_id, slug, mode, collection_role, rule_json, show_in_lists)
            VALUES (?, ?, 'manual', 'want', NULL, 0)
            """,
            (col_id, want_slug),
        )
        self._attach_identifier(col_id, "slug", want_slug, "collection")
        return col_id

    # -- Step 5: Comparisons (`comparisons/*.jsonl` & `want/*.jsonl`) -------

    def _import_comparisons(self, list_name_to_kind: dict[str, str]) -> None:
        comp_dir = self.great_root / "comparisons"
        if not comp_dir.is_dir():
            return

        # 1. Global quality comparisons (`scope_collection_id = NULL`)
        for path in sorted(comp_dir.glob("*.jsonl")):
            list_name = path.stem
            expected_kind = list_name_to_kind.get(list_name)
            self._import_comparison_file(
                path=path,
                expected_kind=expected_kind,
                scope_collection_id=None,
            )

        # 2. Local want-queue priority comparisons (`scope_collection_id = want_col_id`)
        want_comp_dir = comp_dir / "want"
        if want_comp_dir.is_dir():
            for path in sorted(want_comp_dir.glob("*.jsonl")):
                plural = path.stem
                kind = PLURAL_TO_KIND.get(plural)
                if kind is None:
                    continue
                want_col_id = self._ensure_want_collection(kind, plural)
                self._import_comparison_file(
                    path=path,
                    expected_kind=kind,
                    scope_collection_id=want_col_id,
                )

    def _import_comparison_file(
        self,
        *,
        path: Path,
        expected_kind: str | None,
        scope_collection_id: str | None,
    ) -> None:
        for raw_line in path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line:
                continue
            rec = json.loads(line)
            ts = _parse_iso_utc(rec["ts"])
            raw_ordering: list[list[str]] = rec["ordering"]

            mapped_ordering: list[list[str]] = []
            seen_ids: set[str] = set()
            for group in raw_ordering:
                mapped_group: list[str] = []
                for raw_item_id in group:
                    entry_id = self._resolve_comparison_item_id(expected_kind, str(raw_item_id))
                    if entry_id not in seen_ids:
                        seen_ids.add(entry_id)
                        mapped_group.append(entry_id)
                if mapped_group:
                    mapped_ordering.append(mapped_group)

            if len(seen_ids) < 2:
                continue

            ts_ms = _dt_millis(ts)
            comp_id = deterministic_comparison_id(scope_collection_id, ts_ms, mapped_ordering)
            canon_ord = canonical_ordering_json(mapped_ordering)
            cur = self.conn.execute(
                """
                INSERT OR IGNORE INTO comparisons
                    (id, ts, scope_collection_id, ordering_json, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    comp_id,
                    _format_sql_ts(ts),
                    scope_collection_id,
                    canon_ord,
                    self.now_sql,
                ),
            )
            if cur.rowcount > 0:
                self.stats.comparisons_added += 1

    def _resolve_comparison_item_id(self, expected_kind: str | None, raw_id: str) -> str:
        clean = raw_id.strip()
        if expected_kind is not None:
            direct = self.id_map.get((expected_kind, clean))
            if direct is not None:
                return direct
            return self._resolve_or_create_placeholder(expected_kind, clean)
        any_hit = self.any_id_map.get(clean)
        if any_hit is not None:
            return any_hit
        return self._resolve_or_create_placeholder("movie", clean)

    # -- Core Upsert & Lookup Primitives ------------------------------------

    def _upsert_great_item(self, item: RawGreatItem) -> str:
        norm_idents: list[tuple[str, str]] = []
        inferred = infer_identifier_from_great_id(item.kind, item.id)
        if inferred is not None:
            norm_idents.append(inferred)
        for raw_scheme, raw_val in item.external_ids.items():
            pair = normalize_identifier(raw_scheme, raw_val, item.kind)
            if pair[0] and pair[1] and pair not in norm_idents:
                norm_idents.append(pair)

        # 1. Check existing identifiers (including feed_sources for podcasts)
        target_id: str | None = None
        if item.kind == "podcast":
            for scheme, val in norm_idents:
                if scheme == "feed_url":
                    feed_row = self.conn.execute(
                        "SELECT collection_id FROM feed_sources WHERE feed_url = ?",
                        (val,),
                    ).fetchone()
                    if feed_row is not None:
                        target_id = str(feed_row[0])
                        break

        if target_id is None:
            for scheme, val in norm_idents:
                found = self._lookup_entry_by_identifier(scheme, val)
                if found is not None:
                    target_id = found
                    break

        # 2. Album edition matching on case-folded (artist, edition-stripped title)
        album_key = (
            self._album_match_key(item.creators, item.title) if item.kind == "album" else None
        )
        if target_id is None and album_key is not None:
            target_id = self.album_edition_index.get(album_key)

        # 3. Artist exact case-insensitive title match
        if target_id is None and item.kind == "artist":
            art_row = self.conn.execute(
                """
                SELECT id FROM entries
                WHERE kind = 'artist' AND merged_into_id IS NULL AND LOWER(title) = LOWER(?)
                LIMIT 1
                """,
                (item.title,),
            ).fetchone()
            if art_row is not None:
                target_id = str(art_row[0])

        clean_title = (
            strip_album_edition_suffix(item.title) if item.kind == "album" else item.title
        )

        if target_id is None:
            if norm_idents:
                # Prefer spotify identifier when present so 1001-album & Spotify IDs align
                primary = next(
                    (p for p in norm_idents if p[0] == "spotify"),
                    norm_idents[0],
                )
                target_id = deterministic_catalog_entry_id(item.kind, primary[0], primary[1])
            else:
                primary_creator = (
                    item.creators[0].strip().lower()
                    if item.kind != "artist" and item.creators and item.creators[0].strip()
                    else ""
                )
                title_key = (
                    f"{primary_creator}\0{clean_title.lower()}"
                    if primary_creator
                    else clean_title.lower()
                )
                target_id = deterministic_title_entry_id(item.kind, title_key)

            creators_json = json.dumps(item.creators)
            metadata_json = json.dumps(item.metadata) if item.metadata else "{}"
            cur = self.conn.execute(
                """
                INSERT OR IGNORE INTO entries
                    (id, kind, title, creators_json, url, release_year, thumbnail_url,
                     metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    target_id,
                    item.kind,
                    clean_title,
                    creators_json,
                    item.url,
                    item.year,
                    item.thumbnail_url,
                    metadata_json,
                    self.now_sql,
                    self.now_sql,
                ),
            )
            if cur.rowcount > 0:
                self.stats.entries_upserted += 1
        else:
            # Enrich missing fields; user-source titles/years/creators take priority
            row = self.conn.execute(
                """
                SELECT title, creators_json, url, release_year, thumbnail_url, metadata_json
                FROM entries WHERE id = ?
                """,
                (target_id,),
            ).fetchone()
            if row is not None:
                cur_title, cur_creators_json, cur_url, cur_year, cur_thumb, cur_meta_json = row
                try:
                    cur_creators = json.loads(cur_creators_json)
                except ValueError:
                    cur_creators = []
                try:
                    cur_meta = json.loads(cur_meta_json)
                except ValueError:
                    cur_meta = {}
                for k, v in item.metadata.items():
                    cur_meta.setdefault(k, v)

                new_title = clean_title if item.is_user_source and clean_title else cur_title
                new_creators = (
                    item.creators
                    if (item.is_user_source and item.creators) or not cur_creators
                    else cur_creators
                )
                new_year = (
                    item.year
                    if (item.is_user_source and item.year is not None) or cur_year is None
                    else cur_year
                )
                new_url = cur_url or item.url
                new_thumb = cur_thumb or item.thumbnail_url

                self.conn.execute(
                    """
                    UPDATE entries
                    SET title = ?,
                        creators_json = ?,
                        url = ?,
                        release_year = ?,
                        thumbnail_url = ?,
                        metadata_json = ?,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        new_title,
                        json.dumps(new_creators),
                        new_url,
                        new_year,
                        new_thumb,
                        json.dumps(cur_meta),
                        self.now_sql,
                        target_id,
                    ),
                )

        for scheme, val in norm_idents:
            self._attach_identifier(target_id, scheme, val, item.kind)

        if album_key is not None and album_key not in self.album_edition_index:
            self.album_edition_index[album_key] = target_id

        if item.parent_id and item.kind in PARENT_KIND:
            parent_kind = PARENT_KIND[item.kind]
            parent_entry_id = self.id_map.get((parent_kind, item.parent_id))
            if parent_entry_id is None:
                parent_entry_id = self._resolve_or_create_placeholder(parent_kind, item.parent_id)
            if parent_entry_id != target_id:
                self.conn.execute(
                    """
                    INSERT OR IGNORE INTO collection_entries
                        (collection_id, entry_id, position, added_at)
                    VALUES (?, ?, NULL, ?)
                    """,
                    (parent_entry_id, target_id, self.now_sql),
                )

        self._register_id_mapping(item.kind, item.id, target_id)
        return target_id

    def _resolve_or_create_placeholder(self, kind: str, great_id: str) -> str:
        cached = self.id_map.get((kind, great_id))
        if cached is not None:
            return cached
        inferred = infer_identifier_from_great_id(kind, great_id)
        if inferred is not None:
            found = self._lookup_entry_by_identifier(inferred[0], inferred[1])
            if found is not None:
                self._register_id_mapping(kind, great_id, found)
                return found
        return self._upsert_great_item(RawGreatItem(id=great_id, kind=kind, title=great_id))

    def _lookup_entry_by_identifier(self, scheme: str, value: str) -> str | None:
        row = self.conn.execute(
            """
            SELECT COALESCE(e.merged_into_id, e.id)
            FROM entry_identifiers ei
            JOIN entries e ON e.id = ei.entry_id
            WHERE ei.scheme = ? AND ei.value = ?
            LIMIT 1
            """,
            (scheme, value),
        ).fetchone()
        return str(row[0]) if row is not None else None

    def _attach_identifier(self, entry_id: str, scheme: str, value: str, kind: str) -> None:
        norm_scheme, norm_val = normalize_identifier(scheme, value, kind)
        if not norm_scheme or not norm_val:
            return
        self.conn.execute(
            """
            INSERT OR IGNORE INTO entry_identifiers (scheme, value, entry_id)
            VALUES (?, ?, ?)
            """,
            (norm_scheme, norm_val, entry_id),
        )

    def _register_id_mapping(self, kind: str, great_id: str, roche_id: str) -> None:
        self.id_map[(kind, great_id)] = roche_id
        self.any_id_map.setdefault(great_id, roche_id)

    def _insert_consumption_log(
        self,
        *,
        entry_id: str,
        ts: datetime,
        status: str,
        notes: str | None = None,
    ) -> None:
        log_id = deterministic_consumption_log_id(entry_id, _dt_millis(ts), status)
        cur = self.conn.execute(
            """
            INSERT OR IGNORE INTO consumption_log (id, entry_id, ts, status, notes, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (log_id, entry_id, _format_sql_ts(ts), status, notes, self.now_sql),
        )
        if cur.rowcount > 0:
            self.stats.consumption_logs_added += 1
        if status == "consumed":
            # Auto-remove from any Want queues on consumption
            self.conn.execute(
                """
                DELETE FROM collection_entries
                WHERE entry_id = ?
                  AND collection_id IN (
                      SELECT collection_id FROM collection_specs WHERE collection_role = 'want'
                  )
                """,
                (entry_id,),
            )

    def _ensure_entry_consumed_once(self, entry_id: str, fallback_ts: datetime) -> None:
        if self._has_status(entry_id, "consumed"):
            return
        self._insert_consumption_log(entry_id=entry_id, ts=fallback_ts, status="consumed")

    def _has_status(self, entry_id: str, status: str) -> bool:
        row = self.conn.execute(
            "SELECT 1 FROM consumption_log WHERE entry_id = ? AND status = ? LIMIT 1",
            (entry_id, status),
        ).fetchone()
        return row is not None

    def _is_consumed_or_abandoned(self, entry_id: str) -> bool:
        row = self.conn.execute(
            """
            SELECT 1 FROM consumption_log
            WHERE entry_id = ? AND status IN ('consumed', 'abandoned')
            LIMIT 1
            """,
            (entry_id,),
        ).fetchone()
        return row is not None


def migrate_great_repo(
    great_dir: Path,
    roche_db_path: Path,
    pool_json_path: Path | None = None,
) -> MigrationStats:
    resolved_great = great_dir.expanduser().resolve()
    if not (resolved_great / "great.toml").is_file():
        raise FileNotFoundError(f"No great.toml found in {resolved_great}")

    resolved_db = roche_db_path.expanduser().resolve()
    if not resolved_db.is_file():
        raise FileNotFoundError(f"No Roche SQLite database found at {resolved_db}")

    default_pool = (
        Path(__file__).resolve().parent.parent / "server" / "src" / "data" / "albums_1001_pool.json"
    )
    resolved_pool = (pool_json_path or default_pool).expanduser().resolve()

    conn = sqlite3.connect(str(resolved_db))
    try:
        conn.execute("PRAGMA foreign_keys = ON")
        conn.execute("PRAGMA busy_timeout = 5000")
        migrator = GreatMigrator(conn, resolved_great, resolved_pool)
        return migrator.run()
    finally:
        conn.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="One-time migration from a Great repository into Roche's unified SQLite DB."
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
    args = parser.parse_args(argv)

    stats = migrate_great_repo(args.great_dir, args.roche_db, args.pool_json)
    print(
        f"Migrated Great -> Roche successfully:\n"
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
