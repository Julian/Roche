from __future__ import annotations

import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from migrate_great_once import (
    ALBUMS_1001_COLLECTION_ID,
    deterministic_catalog_entry_id,
    deterministic_system_collection_id,
    migrate_great_repo,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS_DIR = REPO_ROOT / "server" / "migrations"


def create_migrated_roche_db(db_path: Path) -> None:
    conn = sqlite3.connect(str(db_path))
    try:
        conn.execute("PRAGMA foreign_keys = ON")
        for sql_file in sorted(MIGRATIONS_DIR.glob("*.sql")):
            conn.executescript(sql_file.read_text(encoding="utf-8"))
        conn.commit()
    finally:
        conn.close()


class TestMigrateGreatOnce(unittest.TestCase):
    def test_deterministic_uuid_parity_with_rust(self) -> None:
        self.assertEqual(
            deterministic_system_collection_id("1001_albums"),
            ALBUMS_1001_COLLECTION_ID,
        )
        self.assertEqual(
            deterministic_catalog_entry_id(
                "album",
                "spotify",
                "spotify:album:3GmwKB1tgPZgXeRJZSm9WX",
            ),
            "83f8410d-71bc-5ea4-911e-06869fe1b039",
        )

    def test_full_great_migration_respects_spotify_single_track_vs_album_rule(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            db_path = root / "roche.db"
            create_migrated_roche_db(db_path)

            great_dir = root / "great_repo"
            for sub in ("items", "want", "log", "comparisons/want", "sources"):
                (great_dir / sub).mkdir(parents=True, exist_ok=True)

            (great_dir / "great.toml").write_text(
                """
[[lists]]
name = "movies"
kind = "movie"

[[lists]]
name = "albums"
kind = "album"
""".strip()
                + "\n",
                encoding="utf-8",
            )

            # 1. User-curated movies
            (great_dir / "items" / "movies.toml").write_text(
                """
[[items]]
id = "tt0133093"
title = "The Matrix"
year = 1999
creators = ["Lana Wachowski", "Lilly Wachowski"]
external_ids = { imdb = "tt0133093" }

[[items]]
id = "tt0062622"
title = "2001: A Space Odyssey"
year = 1968
creators = ["Stanley Kubrick"]
external_ids = { imdb = "tt0062622" }
""".strip()
                + "\n",
                encoding="utf-8",
            )

            # 1b. Two distinct books with the exact same title by different authors (no external IDs)
            (great_dir / "items" / "books.toml").write_text(
                """
[[items]]
id = "home-robinson"
title = "Home"
year = 2008
creators = ["Marilynne Robinson"]

[[items]]
id = "home-morrison"
title = "Home"
year = 2012
creators = ["Toni Morrison"]
""".strip()
                + "\n",
                encoding="utf-8",
            )

            # 2. Movie want queue (one already consumed via log, two still wanted)
            (great_dir / "want" / "movies.toml").write_text(
                """
[[items]]
id = "tt0078748"
title = "Alien"
year = 1979
creators = ["Ridley Scott"]

[[items]]
id = "tt0083658"
title = "Blade Runner"
year = 1982
creators = ["Ridley Scott"]

[[items]]
id = "tt0133093"
title = "The Matrix"
year = 1999
""".strip()
                + "\n",
                encoding="utf-8",
            )

            # 3. 1001AG cache:
            # - Sinatra (83f8410d-71bc-5ea4-911e-06869fe1b039) is revealed/rated -> consumed!
            # - Duke Ellington (c8edff07-31ae-5f23-9d42-03468251904a) is unrevealed currentAlbum -> active draw!
            ag_payload = {
                "paused": False,
                "currentAlbum": {
                    "uuid": "ag-duke-uuid",
                    "slug": "ellington-at-newport",
                    "spotifyId": "0t41BkcZayaAsa0FdRelfz",
                    "name": "Ellington at Newport",
                    "artist": "Duke Ellington",
                    "releaseDate": "1956",
                },
                "history": [
                    {
                        "generatedAt": "2026-03-01T08:00:00Z",
                        "revealedAlbum": True,
                        "rating": 5,
                        "review": "Late-night masterpiece.",
                        "album": {
                            "uuid": "ag-sinatra-uuid",
                            "slug": "in-the-wee-small-hours",
                            "spotifyId": "3GmwKB1tgPZgXeRJZSm9WX",
                            "appleMusicId": "1440859661",
                            "wikipediaUrl": "https://en.wikipedia.org/wiki/In_the_Wee_Small_Hours",
                            "name": "In The Wee Small Hours",
                            "artist": "Frank Sinatra",
                            "releaseDate": "1955-04-25",
                        },
                    },
                    {
                        "generatedAt": "2026-03-02T08:00:00Z",
                        "revealedAlbum": False,
                        "album": {
                            "uuid": "ag-duke-uuid",
                            "slug": "ellington-at-newport",
                            "spotifyId": "0t41BkcZayaAsa0FdRelfz",
                            "name": "Ellington at Newport",
                            "artist": "Duke Ellington",
                            "releaseDate": "1956",
                        },
                    },
                ],
            }
            (great_dir / "sources" / "albumsgenerator.json").write_text(
                json.dumps(ag_payload),
                encoding="utf-8",
            )

            # 4. Spotify streaming cache:
            # User streamed a single song ("Blue Suede Shoes") from Elvis Presley's 1956 debut
            # (`0da41ec9-61c0-587c-9f29-afe2bfa18e8d` in 1001_albums), even tagged with "(Expanded Edition)".
            # MUST fold into the canonical Elvis album entry, log `consumed` ONLY on the song + artist,
            # and NEVER mark the Elvis album itself consumed!
            spotify_payload = {
                "albums": [
                    {
                        "id": "spotify-name:Elvis Presley::Elvis Presley (Expanded Edition)",
                        "title": "Elvis Presley (Expanded Edition)",
                        "artist": "Elvis Presley",
                    }
                ],
                "tracks": [
                    {
                        "uri": "spotify:track:4dc6v7A9B0x12345678901",
                        "title": "Blue Suede Shoes",
                        "artist": "Elvis Presley",
                        "album_id": "spotify-name:Elvis Presley::Elvis Presley (Expanded Edition)",
                    }
                ],
                "completions": [
                    {
                        "track_uri": "spotify:track:4dc6v7A9B0x12345678901",
                        "ts": "2026-03-05T19:30:00Z",
                    }
                ],
            }
            (great_dir / "sources" / "spotify.json").write_text(
                json.dumps(spotify_payload),
                encoding="utf-8",
            )

            # 5. AntennaPod cache: one podcast + one completed episode
            antennapod_payload = {
                "schema_version": 3110000,
                "feeds": [
                    {
                        "feed_url": "https://feeds.example.org/history.xml",
                        "title": "History Weekly",
                        "author": "Ada Historian",
                        "state": 0,
                    }
                ],
                "episodes": [
                    {
                        "feed_url": "https://feeds.example.org/history.xml",
                        "item_identifier": "ep-42",
                        "title": "The Bronze Age Collapse",
                        "duration_ms": 2400000,
                        "read": 1,
                        "completed_at_ms": 1772800000000,
                        "is_favorite": True,
                    }
                ],
            }
            (great_dir / "sources" / "antennapod.json").write_text(
                json.dumps(antennapod_payload),
                encoding="utf-8",
            )

            # 6. User diary log
            (great_dir / "log" / "2026.jsonl").write_text(
                json.dumps(
                    {
                        "ts": "2026-02-10T21:00:00Z",
                        "kind": "movie",
                        "item": "tt0133093",
                        "status": "consumed",
                        "notes": "Rewatched in 4K.",
                    }
                )
                + "\n",
                encoding="utf-8",
            )

            # 7. Global quality comparison + local want priority comparison
            (great_dir / "comparisons" / "movies.jsonl").write_text(
                json.dumps(
                    {
                        "ts": "2026-02-11T10:00:00Z",
                        "ordering": [["tt0062622"], ["tt0133093"]],
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            (great_dir / "comparisons" / "want" / "movies.jsonl").write_text(
                json.dumps(
                    {
                        "ts": "2026-02-12T10:00:00Z",
                        "ordering": [["tt0078748"], ["tt0083658"]],
                    }
                )
                + "\n",
                encoding="utf-8",
            )

            stats1 = migrate_great_repo(great_dir, db_path)
            self.assertGreaterEqual(stats1.seeded_1001_albums, 1000)
            self.assertEqual(
                stats1.active_1001_album_id,
                "c8edff07-31ae-5f23-9d42-03468251904a",
            )

            # Idempotency check: running a second time inserts 0 duplicates
            stats2 = migrate_great_repo(great_dir, db_path)
            self.assertEqual(stats2.seeded_1001_albums, 0)
            self.assertEqual(stats2.comparisons_added, 0)

            conn = sqlite3.connect(str(db_path))
            try:
                sinatra_id = "83f8410d-71bc-5ea4-911e-06869fe1b039"
                duke_id = "c8edff07-31ae-5f23-9d42-03468251904a"
                elvis_id = "0da41ec9-61c0-587c-9f29-afe2bfa18e8d"

                # Sinatra was revealed in 1001AG -> consumed with notes & Apple/Wiki IDs attached
                sinatra_logs = conn.execute(
                    "SELECT status, notes FROM consumption_log WHERE entry_id = ?",
                    (sinatra_id,),
                ).fetchall()
                self.assertEqual(
                    sinatra_logs,
                    [("consumed", "★★★★★ — Late-night masterpiece.")],
                )
                wiki_ident = conn.execute(
                    "SELECT value FROM entry_identifiers WHERE entry_id = ? AND scheme = 'wikipedia'",
                    (sinatra_id,),
                ).fetchone()
                self.assertEqual(wiki_ident, ("In_the_Wee_Small_Hours",))

                # Duke Ellington is the active unrated 1001 album -> NOT consumed!
                duke_logs = conn.execute(
                    "SELECT status FROM consumption_log WHERE entry_id = ?",
                    (duke_id,),
                ).fetchall()
                self.assertEqual(duke_logs, [])
                gen_row = conn.execute(
                    "SELECT enabled, current_entry_id FROM collection_generators WHERE collection_id = ?",
                    (ALBUMS_1001_COLLECTION_ID,),
                ).fetchone()
                self.assertEqual(gen_row, (1, duke_id))

                # CRITICAL RULE: Elvis had 1 song played on Spotify ->
                # Song IS linked to canonical Elvis album & marked consumed;
                # Elvis artist IS synthesized & marked consumed;
                # Elvis album itself has ZERO consumption_log rows and stays in remaining 1001 pool!
                elvis_album_logs = conn.execute(
                    "SELECT status FROM consumption_log WHERE entry_id = ?",
                    (elvis_id,),
                ).fetchall()
                self.assertEqual(elvis_album_logs, [])

                duplicate_elvis_albums = conn.execute(
                    "SELECT COUNT(*) FROM entries WHERE kind = 'album' AND LOWER(title) LIKE 'elvis presley%'"
                ).fetchone()[0]
                self.assertEqual(duplicate_elvis_albums, 1)

                song_row = conn.execute(
                    """
                    SELECT e.id, e.title
                    FROM collection_entries ce
                    JOIN entries e ON e.id = ce.entry_id
                    WHERE ce.collection_id = ? AND e.kind = 'song'
                    """,
                    (elvis_id,),
                ).fetchone()
                self.assertIsNotNone(song_row)
                self.assertEqual(song_row[1], "Blue Suede Shoes")
                song_logs = conn.execute(
                    "SELECT status FROM consumption_log WHERE entry_id = ?",
                    (song_row[0],),
                ).fetchall()
                self.assertEqual(song_logs, [("consumed",)])

                elvis_artist_row = conn.execute(
                    """
                    SELECT e.id
                    FROM entries e
                    JOIN consumption_log cl ON cl.entry_id = e.id
                    WHERE e.kind = 'artist' AND e.title = 'Elvis Presley' AND cl.status = 'consumed'
                    """
                ).fetchone()
                self.assertIsNotNone(elvis_artist_row)
                elvis_linked_works = conn.execute(
                    "SELECT entry_id FROM collection_entries WHERE collection_id = ?",
                    (elvis_artist_row[0],),
                ).fetchall()
                linked_ids = {r[0] for r in elvis_linked_works}
                self.assertIn(elvis_id, linked_ids)
                self.assertIn(song_row[0], linked_ids)

                # AntennaPod episode + parent podcast both marked consumed
                pod_consumed = conn.execute(
                    """
                    SELECT COUNT(*)
                    FROM entries e
                    JOIN consumption_log cl ON cl.entry_id = e.id
                    WHERE e.kind = 'podcast' AND e.title = 'History Weekly' AND cl.status = 'consumed'
                    """
                ).fetchone()[0]
                self.assertEqual(pod_consumed, 1)

                # Want queue: Alien & Blade Runner present; The Matrix skipped because already consumed!
                want_movies_id = deterministic_system_collection_id("want:movies")
                want_titles = [
                    r[0]
                    for r in conn.execute(
                        """
                        SELECT e.title
                        FROM collection_entries ce
                        JOIN entries e ON e.id = ce.entry_id
                        WHERE ce.collection_id = ?
                        ORDER BY ce.position ASC
                        """,
                        (want_movies_id,),
                    ).fetchall()
                ]
                self.assertEqual(want_titles, ["Alien", "Blade Runner"])

                # Global quality comparison (`scope_collection_id IS NULL`) vs local want priority
                global_comps = conn.execute(
                    "SELECT ordering_json FROM comparisons WHERE scope_collection_id IS NULL"
                ).fetchall()
                self.assertEqual(len(global_comps), 1)
                want_comps = conn.execute(
                    "SELECT ordering_json FROM comparisons WHERE scope_collection_id = ?",
                    (want_movies_id,),
                ).fetchall()
                self.assertEqual(len(want_comps), 1)

                # Same-titled books by different authors get distinct creator-prefixed UUIDs
                home_books = conn.execute(
                    "SELECT id, creators_json FROM entries WHERE kind = 'book' AND title = 'Home' ORDER BY release_year ASC"
                ).fetchall()
                self.assertEqual(len(home_books), 2)
                self.assertNotEqual(home_books[0][0], home_books[1][0])
            finally:
                conn.close()


if __name__ == "__main__":
    unittest.main()
