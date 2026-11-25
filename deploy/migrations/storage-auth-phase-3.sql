-- Run after Phase 1 and after migration, verification, and rotation. This removes only legacy columns.
BEGIN;
LOCK TABLE storage_connections IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT FROM storage_connections WHERE active AND auth_mode = 'MIGRATION_REQUIRED') THEN
        RAISE EXCEPTION 'Migrate all active storage connections before dropping legacy credential columns';
    END IF;

    IF EXISTS (SELECT FROM pg_attribute
               WHERE attrelid = 'storage_connections'::regclass
                 AND attname = 'secret_key_or_password' AND NOT attisdropped) THEN
        IF EXISTS (SELECT FROM storage_connections
                   WHERE access_key_or_username IS NOT NULL OR secret_key_or_password IS NOT NULL) THEN
            RAISE EXCEPTION 'Clear legacy storage credentials before dropping the legacy columns';
        END IF;
    END IF;
END;
$$;

DROP TRIGGER IF EXISTS storage_legacy_credentials_guard ON storage_connections;
DROP FUNCTION IF EXISTS guard_storage_legacy_credentials();
ALTER TABLE storage_connections
    DROP COLUMN IF EXISTS access_key_or_username,
    DROP COLUMN IF EXISTS secret_key_or_password;
COMMIT;
