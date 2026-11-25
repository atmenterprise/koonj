package uk.appsthatmatter.koonj.server.storageauth;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class DatabaseStorageCredentials {
    private final JdbcTemplate jdbc;
    private final StorageCredentialEncryption encryption;

    DatabaseStorageCredentials(JdbcTemplate jdbc, StorageCredentialEncryption encryption) {
        this.jdbc = jdbc;
        this.encryption = encryption;
    }

    CredentialSnapshot read(CredentialTarget target) {
        byte[] envelope;
        try {
            envelope = jdbc.query("""
                    select credential_envelope from storage_connections
                    where project_id = ? and id = ? and auth_mode = 'ENCRYPTED_CREDENTIALS'
                    """, rows -> rows.next() ? rows.getBytes(1) : null, target.project(), target.connection());
        } catch (DataAccessException failure) {
            throw new StorageCredentialException("Could not read the encrypted storage credential");
        }
        if (envelope == null) throw new StorageCredentialException("No encrypted credential is configured for this connection");
        return encryption.decrypt(target, envelope);
    }
}
