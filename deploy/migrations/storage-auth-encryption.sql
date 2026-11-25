-- Run before starting the new API and workers. Run storage-auth-phase-1.sql first
-- for installations that predate explicit authentication modes.
BEGIN;
LOCK TABLE storage_connections IN ACCESS EXCLUSIVE MODE;

ALTER TABLE storage_connections
    ADD COLUMN IF NOT EXISTS credential_envelope bytea,
    DROP CONSTRAINT IF EXISTS storage_connections_auth_mode_check,
    DROP CONSTRAINT IF EXISTS storage_connections_credential_envelope_check;

-- References from the retired OpenBao backend include both bare and prefixed aliases.
-- Keep the connection IDs and catalog links. A project administrator supplies replacement access.
UPDATE storage_connections
SET auth_mode = 'MIGRATION_REQUIRED', secret_ref = NULL
WHERE auth_mode = 'SECRET_REF'
  AND (secret_ref IS NULL OR secret_ref !~ '^aws-sm:[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$');

-- Reinstall the Phase 1 guard on upgrades that still have quarantined columns.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_attribute WHERE attrelid = 'storage_connections'::regclass
                   AND attname = 'secret_key_or_password' AND NOT attisdropped) THEN
        CREATE OR REPLACE FUNCTION guard_storage_legacy_credentials()
        RETURNS trigger LANGUAGE plpgsql AS $trigger$
        BEGIN
            IF TG_OP = 'UPDATE' THEN
                IF OLD.auth_mode = 'MIGRATION_REQUIRED' AND NEW.auth_mode IN
                    ('HOST_IDENTITY', 'AWS_DEFAULT_CHAIN', 'AWS_ASSUME_ROLE', 'SECRET_REF', 'ENCRYPTED_CREDENTIALS')
                    AND NEW.access_key_or_username IS NOT DISTINCT FROM OLD.access_key_or_username
                    AND NEW.secret_key_or_password IS NOT DISTINCT FROM OLD.secret_key_or_password THEN
                    NEW.access_key_or_username := NULL;
                    NEW.secret_key_or_password := NULL;
                END IF;
            END IF;
            IF NEW.access_key_or_username IS NOT NULL OR NEW.secret_key_or_password IS NOT NULL THEN
                RAISE EXCEPTION 'Plaintext storage credentials are disabled';
            END IF;
            RETURN NEW;
        END;
        $trigger$;
    END IF;
END;
$$;

ALTER TABLE storage_connections
    ADD CONSTRAINT storage_connections_auth_mode_check CHECK (auth_mode IN
        ('HOST_IDENTITY', 'AWS_DEFAULT_CHAIN', 'AWS_ASSUME_ROLE', 'SECRET_REF', 'ENCRYPTED_CREDENTIALS', 'MIGRATION_REQUIRED')),
    ADD CONSTRAINT storage_connections_credential_envelope_check CHECK
        ((auth_mode = 'ENCRYPTED_CREDENTIALS') = (credential_envelope IS NOT NULL));
COMMIT;
