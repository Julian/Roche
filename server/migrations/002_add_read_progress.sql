ALTER TABLE items ADD COLUMN read_progress REAL NOT NULL DEFAULT 0.0 CHECK (read_progress >= 0.0 AND read_progress <= 1.0);
