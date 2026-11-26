BEGIN;
ALTER TABLE project ADD COLUMN deleted_at timestamptz;
ALTER TABLE project_account ADD COLUMN fallback_project_id uuid REFERENCES project(id);
COMMIT;
