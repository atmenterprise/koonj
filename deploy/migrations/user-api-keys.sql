BEGIN;

CREATE TABLE IF NOT EXISTS user_api_key (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    token_hash varchar(64) NOT NULL,
    token_prefix varchar(24) NOT NULL,
    owner_subject varchar(255) NOT NULL,
    owner_name varchar(255) NOT NULL,
    project_id uuid NOT NULL,
    roles jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz,
    expires_at timestamptz,
    revoked boolean NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_api_key_hash ON user_api_key (token_hash);
CREATE INDEX IF NOT EXISTS idx_user_api_key_owner ON user_api_key (owner_subject, project_id);

COMMIT;
