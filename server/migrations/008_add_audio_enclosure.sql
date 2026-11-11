ALTER TABLE items ADD COLUMN audio_url TEXT;
ALTER TABLE items ADD COLUMN duration INTEGER CHECK (duration IS NULL OR duration >= 0);
