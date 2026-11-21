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
    default void createSymlink(StorageConnection config, String relativePath, String targetReference) throws IOException {
        throw new UnsupportedOperationException("This storage does not support symbolic links");
    }

    default void replaceWithSymlink(StorageConnection config, String relativePath, String targetReference,
                                    RestoreCommit commit) throws IOException {
        createSymlink(config, relativePath, targetReference);
        commit.run();
    }

    default boolean isSymlink(StorageConnection config, String relativePath) throws IOException {
        return false;
    }

    default void restore(StorageConnection config, String relativePath, InputStream stream,
                         long contentLength, RestoreCommit commit) throws IOException {
        writeStream(config, relativePath, stream, contentLength, null);
        commit.run();
    }

    @FunctionalInterface
    interface RestoreCommit {
        void run() throws IOException;
    }

    void deleteFile(StorageConnection config, String relativePath) throws IOException;
}
