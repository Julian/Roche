-- 017_feed_poll_backoff.sql
--
-- Tracks per-feed consecutive poll failures for cross-peer exponential backoff
-- and updates `feed_sources_sync_seq_au` so backoff telemetry syncs without
-- bumping feed configuration HLC.

ALTER TABLE feed_sources
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0);

UPDATE feed_sources
SET consecutive_failures = 1
WHERE last_error IS NOT NULL AND TRIM(last_error) != '';

DROP TRIGGER IF EXISTS feed_sources_sync_seq_au;
CREATE TRIGGER feed_sources_sync_seq_au
AFTER UPDATE OF
    feed_url, source_type, feed_title, language, poll_interval, is_active,
    last_polled_at, last_polled_by, last_error, last_error_by, consecutive_failures, hlc
ON feed_sources
WHEN old.feed_url IS NOT new.feed_url
  OR old.source_type IS NOT new.source_type
  OR old.feed_title IS NOT new.feed_title
  OR old.language IS NOT new.language
  OR old.poll_interval IS NOT new.poll_interval
  OR old.is_active IS NOT new.is_active
  OR old.last_polled_at IS NOT new.last_polled_at
  OR old.last_polled_by IS NOT new.last_polled_by
  OR old.last_error IS NOT new.last_error
  OR old.last_error_by IS NOT new.last_error_by
  OR old.consecutive_failures IS NOT new.consecutive_failures
  OR old.hlc IS NOT new.hlc
BEGIN
    UPDATE sync_clock SET current_seq = current_seq + 1 WHERE id = 1;
    UPDATE feed_sources
    SET hlc = printf(
        '%015d-0000-server',
        MAX(
            CAST(ROUND((julianday('now') - 2440587.5) * 86400000.0) AS INTEGER),
            CASE WHEN LENGTH(old.hlc) >= 15 THEN CAST(SUBSTR(old.hlc, 1, 15) AS INTEGER) + 1 ELSE 0 END
        )
    )
    WHERE collection_id = new.collection_id
      AND (new.hlc = '' OR new.hlc IS old.hlc)
      AND (
          old.feed_url IS NOT new.feed_url
          OR old.source_type IS NOT new.source_type
          OR old.feed_title IS NOT new.feed_title
          OR old.language IS NOT new.language
          OR old.poll_interval IS NOT new.poll_interval
          OR old.is_active IS NOT new.is_active
          OR new.hlc = ''
      );
    UPDATE entries
    SET sync_seq = (SELECT current_seq FROM sync_clock WHERE id = 1)
    WHERE id = new.collection_id;
END;
