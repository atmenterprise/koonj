package uk.appsthatmatter.koonj.server.connector;

import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface StorageConnector {
    boolean supports(ProviderType type);
    default Optional<Instant> requestColdRestore(StorageConnection config, String key) {
        return Optional.empty();
    }

    default boolean objectStorage() { return false; }
    void testConnection(StorageConnection config) throws IOException;
    List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) throws IOException;
    default void visitFiles(StorageConnection config, String subPath, Consumer<ScannedFileInfo> visitor) throws IOException {
        listFiles(config, subPath).forEach(visitor);
    }
    default void visitFolderFiles(StorageConnection config, String folder, Consumer<ScannedFileInfo> visitor) throws IOException {
        visitFiles(config, folder.isEmpty() ? folder : folder.substring(0, folder.length() - 1), visitor);
    }
    default Optional<ScannedFileInfo> statFile(StorageConnection config, String path) throws IOException {
        return listFiles(config, path).stream().filter(item -> path.equals(item.relativePath())).findFirst();
    }
    default FileAttributes fileAttributes(StorageConnection config, String relativePath) throws IOException {
        return FileAttributes.EMPTY;
    }
    InputStream openReadStream(StorageConnection config, String relativePath) throws IOException;
    default InputStream openTransferStream(StorageConnection config, String relativePath, long size) throws IOException {
        return openReadStream(config, relativePath);
    }
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
