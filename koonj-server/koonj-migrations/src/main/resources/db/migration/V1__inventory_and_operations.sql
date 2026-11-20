CREATE TABLE storage_root (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name text NOT NULL UNIQUE,
    mount_path text NOT NULL UNIQUE,
    managed boolean NOT NULL DEFAULT false
);

CREATE TABLE inventory_file (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    root_id bigint NOT NULL REFERENCES storage_root (id),
    relative_path text NOT NULL,
    UNIQUE (root_id, relative_path)
);
CREATE INDEX inventory_file_root_id_idx ON inventory_file (root_id, id);

CREATE TABLE file_generation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_id bigint NOT NULL REFERENCES inventory_file (id),
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    modified_at timestamptz NOT NULL,
    source_identity text,
    observed_at timestamptz NOT NULL DEFAULT now(),
    state text NOT NULL CHECK (state IN ('LOCAL', 'ARCHIVED', 'TIERED')),
    sha256 bytea CHECK (octet_length(sha256) = 32),
    object_key text UNIQUE,
    CHECK (state = 'LOCAL' OR (sha256 IS NOT NULL AND object_key IS NOT NULL))
);
CREATE INDEX file_generation_latest_idx ON file_generation (file_id, id DESC);

CREATE TABLE scan (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    root_id bigint NOT NULL REFERENCES storage_root (id),
    state text NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    files_seen bigint NOT NULL DEFAULT 0 CHECK (files_seen >= 0),
    errors_seen bigint NOT NULL DEFAULT 0 CHECK (errors_seen >= 0),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX scan_active_root_idx ON scan (root_id) WHERE state IN ('QUEUED', 'RUNNING');
CREATE INDEX scan_updated_idx ON scan (updated_at, id);

CREATE TABLE file_operation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    generation_id bigint NOT NULL REFERENCES file_generation (id),
    kind text NOT NULL CHECK (kind IN ('ARCHIVE', 'TIER', 'RECALL')),
    state text NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    claim_token uuid,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((state = 'RUNNING' AND lease_until IS NOT NULL AND claim_token IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_until IS NULL AND claim_token IS NULL))
);
CREATE INDEX file_operation_runnable_idx ON file_operation (available_at, id) WHERE state = 'QUEUED';
CREATE INDEX file_operation_expired_idx ON file_operation (lease_until, id) WHERE state = 'RUNNING';
CREATE INDEX file_operation_generation_idx ON file_operation (generation_id, id);
CREATE INDEX file_operation_updated_idx ON file_operation (updated_at, id);
