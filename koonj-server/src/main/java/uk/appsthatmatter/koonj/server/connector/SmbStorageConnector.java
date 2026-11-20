package uk.appsthatmatter.koonj.server.connector;

import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.io.InputStream;
import java.util.List;

@Component
public class SmbStorageConnector implements StorageConnector {
    @Override
    public boolean supports(ProviderType type) { return type == ProviderType.SMB; }

    public static void validateShare(String share) {
        if (!share.matches("(smb://|//)[^/]+/[^/]+(?:/.*)?")
                && !share.matches("\\\\\\\\[^\\\\]+\\\\[^\\\\]+(?:\\\\.*)?")) {
            throw new IllegalArgumentException("SMB requires an smb://host/share or UNC path");
        }
    }

    private UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("SMB driver wiring scheduled for Phase 2");
    }

    @Override
    public void testConnection(StorageConnection config) {
        validateShare(config.getBasePathOrBucket());
        throw unavailable();
    }

    @Override
    public List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) { throw unavailable(); }

    @Override
    public InputStream openReadStream(StorageConnection config, String relativePath) { throw unavailable(); }

    @Override
    public void writeStream(StorageConnection config, String relativePath, InputStream dataStream,
                            long contentLength, String storageClass) { throw unavailable(); }

    @Override
    public void deleteFile(StorageConnection config, String relativePath) { throw unavailable(); }
}
