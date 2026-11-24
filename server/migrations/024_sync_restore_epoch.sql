-- Track backup restores so peers reconnecting after a server rollback always trigger a full
-- bidirectional reset sync, even if post-restore server writes advance `sync_clock.current_seq`
-- past the peer's previous cursor before the peer reconnects.
ALTER TABLE sync_clock ADD COLUMN restore_epoch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE peer_nodes ADD COLUMN synced_epoch INTEGER NOT NULL DEFAULT 0;
