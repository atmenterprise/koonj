ALTER TABLE scan ADD COLUMN claim_token uuid;
CREATE INDEX scan_poll_idx ON scan (id) WHERE state IN ('QUEUED', 'RUNNING');
