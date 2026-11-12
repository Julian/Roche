CREATE TABLE IF NOT EXISTS maintenance_runs (
    task_name TEXT PRIMARY KEY,
    completed_at TEXT NOT NULL
);
