-- Drain previous-version jobs and stop those servers before enabling broker dispatch.
BEGIN;

ALTER TABLE transfer_jobs
    ADD COLUMN IF NOT EXISTS interactive_file_id uuid,
    ADD COLUMN IF NOT EXISTS recall_ready_at timestamptz,
    ADD COLUMN IF NOT EXISTS dispatch_generation uuid,
    ADD COLUMN IF NOT EXISTS dispatch_after timestamptz,
    ADD COLUMN IF NOT EXISTS dispatch_pool varchar(255),
    ADD COLUMN IF NOT EXISTS worker_attempt uuid,
    ADD COLUMN IF NOT EXISTS worker_instance uuid,
    ADD COLUMN IF NOT EXISTS worker_heartbeat timestamptz,
    ADD COLUMN IF NOT EXISTS active_paths jsonb;

CREATE TABLE IF NOT EXISTS transfer_reservations (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES transfer_jobs(id),
    namespace varchar(64) NOT NULL,
    path text NOT NULL,
    subtree boolean NOT NULL,
    scan boolean NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_transfer_job_dispatch ON transfer_jobs (status, dispatch_after);
CREATE INDEX IF NOT EXISTS idx_transfer_job_attempt ON transfer_jobs (worker_attempt) WHERE worker_attempt IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transfer_job_worker_instance ON transfer_jobs (worker_instance) WHERE worker_instance IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transfer_job_interactive ON transfer_jobs (project_id, interactive_file_id, created_at);
CREATE INDEX IF NOT EXISTS idx_transfer_reservation_namespace ON transfer_reservations (namespace);
CREATE INDEX IF NOT EXISTS idx_transfer_reservation_job ON transfer_reservations (job_id);

COMMIT;
