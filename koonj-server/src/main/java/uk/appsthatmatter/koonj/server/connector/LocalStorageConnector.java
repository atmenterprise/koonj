package uk.appsthatmatter.koonj.server.connector;

import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

@Component
public class LocalStorageConnector implements StorageConnector {
    @Override
    public boolean supports(ProviderType type) {
        return type == ProviderType.LOCAL || type == ProviderType.NAS;
    }

    private Path root(StorageConnection config) throws IOException {
        Path root = Path.of(config.getBasePathOrBucket()).toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Storage root must be a directory");
        return root;
    }

    private Path resolve(Path root, String relativePath) throws IOException {
        StoragePaths.requireSafe(relativePath);
        Path path = root;
        for (Path part : Path.of(relativePath)) {
            path = path.resolve(part);
            if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links under the storage root are unsupported");
        }
        return path;
    }

    @Override
    public void testConnection(StorageConnection config) throws IOException {
        Path root = root(config);
        try (var entries = Files.newDirectoryStream(root)) {
            entries.iterator().hasNext();
        }
        if (config.getRole() != StorageRole.SOURCE) {
            Files.delete(Files.createTempFile(root, ".koonj-probe-", ".tmp"));
        }
    }

    @Override
    public List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) throws IOException {
        Path root = root(config);
        Path start = resolve(root, subPath);
        List<ScannedFileInfo> files = new ArrayList<>();
        if (Files.notExists(start)) return files;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    files.add(new ScannedFileInfo(root.relativize(file).toString().replace(File.separatorChar, '/'),
                            attributes.size(), attributes.lastModifiedTime().toInstant(), null));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    @Override
    public InputStream openReadStream(StorageConnection config, String relativePath) throws IOException {
        Path path = resolve(root(config), relativePath);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        return new BufferedInputStream(Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS));
    }

    @Override
    public void writeStream(StorageConnection config, String relativePath, InputStream stream,
                            long contentLength, String storageClass) throws IOException {
        Path destination = resolve(root(config), relativePath);
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempFile(destination.getParent(), ".koonj-transfer-", ".tmp");
        try {
            long written = Files.copy(new BufferedInputStream(stream), staging, StandardCopyOption.REPLACE_EXISTING);
            if (written != contentLength) throw new IOException("Source length changed during transfer");
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    @Override
    public void deleteFile(StorageConnection config, String relativePath) throws IOException {
        Path path = resolve(root(config), relativePath);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        Files.delete(path);
    }
}
