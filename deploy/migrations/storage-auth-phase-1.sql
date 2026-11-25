-- Stop old API and worker processes before applying. Apply project-workspaces.sql first.
BEGIN;
LOCK TABLE storage_connections IN ACCESS EXCLUSIVE MODE;

ALTER TABLE storage_connections
    ADD COLUMN IF NOT EXISTS auth_mode varchar(255),
    ADD COLUMN IF NOT EXISTS secret_ref varchar(255),
    ADD COLUMN IF NOT EXISTS aws_role_arn varchar(255),
    ADD COLUMN IF NOT EXISTS aws_external_id varchar(255),
    ADD COLUMN IF NOT EXISTS object_prefix varchar(255);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_attribute
               WHERE attrelid = 'storage_connections'::regclass
                 AND attname = 'secret_key_or_password' AND NOT attisdropped) THEN
        UPDATE storage_connections
        SET auth_mode = CASE
            WHEN provider_type IN ('LOCAL', 'SMB', 'NAS') THEN 'HOST_IDENTITY'
            WHEN provider_type = 'AWS_S3' AND endpoint_url IS NULL
                AND access_key_or_username IS NULL AND secret_key_or_password IS NULL THEN 'AWS_DEFAULT_CHAIN'
            ELSE 'MIGRATION_REQUIRED'
        END
        WHERE auth_mode IS NULL;

        UPDATE storage_connections
        SET access_key_or_username = NULL, secret_key_or_password = NULL, endpoint_url = NULL, region = NULL
        WHERE provider_type IN ('LOCAL', 'SMB', 'NAS')
          AND (access_key_or_username IS NOT NULL OR secret_key_or_password IS NOT NULL
              OR endpoint_url IS NOT NULL OR region IS NOT NULL);

        -- Old rows are quarantined. A verified authentication update erases the old values in
        -- the same transaction. Reject attempts by old clients to write either plaintext column.
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

        DROP TRIGGER IF EXISTS storage_legacy_credentials_guard ON storage_connections;
        CREATE TRIGGER storage_legacy_credentials_guard BEFORE INSERT OR UPDATE ON storage_connections
            FOR EACH ROW EXECUTE FUNCTION guard_storage_legacy_credentials();
    END IF;

    -- Covers existing schemas without the old columns, including reapplying after Phase 3.
    UPDATE storage_connections
    SET auth_mode = CASE
        WHEN provider_type IN ('LOCAL', 'SMB', 'NAS') THEN 'HOST_IDENTITY'
        WHEN provider_type = 'AWS_S3' AND endpoint_url IS NULL THEN 'AWS_DEFAULT_CHAIN'
        ELSE 'MIGRATION_REQUIRED'
    END
    WHERE auth_mode IS NULL;
END;
$$;

ALTER TABLE storage_connections ALTER COLUMN auth_mode SET NOT NULL;
COMMIT;
