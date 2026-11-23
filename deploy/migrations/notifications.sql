BEGIN;

ALTER TABLE transfer_jobs ADD COLUMN IF NOT EXISTS owner_email varchar(320);
ALTER TABLE transfer_jobs ADD COLUMN IF NOT EXISTS skipped_files integer NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS notification_channels (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    type varchar(255) NOT NULL CHECK (type IN ('WEBHOOK', 'SLACK', 'TEAMS', 'EMAIL')),
    config jsonb NOT NULL,
    enabled boolean NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS notification_rules (
    id uuid PRIMARY KEY,
    event_type varchar(255) NOT NULL CHECK (event_type IN (
        'LARGE_BATCH_COMPLETED', 'TRANSFER_FAILED', 'QUOTA_THRESHOLD', 'LICENSE_ALERT'
    )),
    threshold_config jsonb NOT NULL,
    channel_ids jsonb NOT NULL,
    enabled boolean NOT NULL
);

CREATE TABLE IF NOT EXISTS user_notification_preferences (
    user_id varchar(255) PRIMARY KEY,
    verified_email varchar(320),
    email_enabled boolean NOT NULL,
    muted_event_types jsonb NOT NULL,
    silenced_until timestamptz,
    updated_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_preference_email ON user_notification_preferences (verified_email);

CREATE TABLE IF NOT EXISTS notification_delivery_logs (
    id uuid PRIMARY KEY,
    event_type varchar(255) NOT NULL CHECK (event_type IN (
        'LARGE_BATCH_COMPLETED', 'TRANSFER_FAILED', 'QUOTA_THRESHOLD', 'LICENSE_ALERT'
    )),
    channel_id uuid NOT NULL,
    rule_id uuid,
    dedupe_key varchar(255),
    recipient varchar(320),
    status varchar(255) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SUPPRESSED_OPT_OUT')),
    attempt_count integer NOT NULL,
    error_message varchar(500),
    test_delivery boolean NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_created ON notification_delivery_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_cooldown
    ON notification_delivery_logs (rule_id, channel_id, dedupe_key, created_at);

CREATE TABLE IF NOT EXISTS notification_outbox (
    id uuid PRIMARY KEY,
    event jsonb NOT NULL,
    test_channel_id uuid,
    created_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notification_outbox_created ON notification_outbox (created_at, id);

ALTER TABLE notification_delivery_logs
    ADD COLUMN IF NOT EXISTS transport_type varchar(255),
    ADD COLUMN IF NOT EXISTS payload jsonb,
    ADD COLUMN IF NOT EXISTS dispatch_token uuid,
    ADD COLUMN IF NOT EXISTS publish_after timestamptz,
    ADD COLUMN IF NOT EXISTS lease_token uuid;
CREATE INDEX IF NOT EXISTS idx_notification_delivery_publish ON notification_delivery_logs (publish_after, id);

COMMIT;
