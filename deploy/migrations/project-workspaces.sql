BEGIN;

CREATE TABLE IF NOT EXISTS project (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL
);

INSERT INTO project (id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Default project')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS project_account (
    subject varchar(255) PRIMARY KEY,
    display_name varchar(255) NOT NULL,
    email varchar(255)
);

CREATE TABLE IF NOT EXISTS project_membership (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project (id),
    account_subject varchar(255) NOT NULL REFERENCES project_account (subject),
    CONSTRAINT uk_project_membership UNIQUE (project_id, account_subject)
);

CREATE INDEX IF NOT EXISTS idx_project_member_account ON project_membership (account_subject);

CREATE TABLE IF NOT EXISTS role_permission_settings (
    role varchar(40) PRIMARY KEY,
    permissions jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS project_temperature_settings (
    project_id uuid PRIMARY KEY,
    hot_days integer NOT NULL,
    cold_days integer NOT NULL
);

ALTER TABLE storage_connections
    ADD COLUMN IF NOT EXISTS project_id uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001';
ALTER TABLE transfer_jobs
    ADD COLUMN IF NOT EXISTS project_id uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001';
ALTER TABLE transfer_jobs ADD COLUMN IF NOT EXISTS owner_subject varchar(255);
ALTER TABLE IF EXISTS integration_endpoint
    ADD COLUMN IF NOT EXISTS project_id uuid NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001';

CREATE UNIQUE INDEX IF NOT EXISTS uk_storage_project_name ON storage_connections (project_id, name);
CREATE INDEX IF NOT EXISTS idx_transfer_job_project_created ON transfer_jobs (project_id, created_at);
CREATE INDEX IF NOT EXISTS idx_transfer_job_project_owner_created ON transfer_jobs (project_id, owner_subject, created_at);

-- Hibernate generated different constraint names depending on how the previous schema was created.
DO $$
DECLARE
    legacy_constraint text;
BEGIN
    FOR legacy_constraint IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND c.conkey = ARRAY[a.attnum]
        WHERE c.conrelid = 'storage_connections'::regclass AND c.contype = 'u' AND a.attname = 'name'
    LOOP
        EXECUTE format('ALTER TABLE storage_connections DROP CONSTRAINT %I', legacy_constraint);
    END LOOP;
END
$$;

COMMIT;
