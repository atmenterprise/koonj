package uk.appsthatmatter.koonj.server.connector;

import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface StorageConnector {
    boolean supports(ProviderType type);
    default boolean objectStorage() { return false; }
    void testConnection(StorageConnection config) throws IOException;
    List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) throws IOException;
    InputStream openReadStream(StorageConnection config, String relativePath) throws IOException;
    void writeStream(StorageConnection config, String relativePath, InputStream dataStream,
                     long contentLength, String storageClass) throws IOException;
    void deleteFile(StorageConnection config, String relativePath) throws IOException;
}
