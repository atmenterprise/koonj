package uk.appsthatmatter.koonj.server.connector;

import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.entity.EntryType;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

@Component
public class LocalStorageConnector implements StorageConnector {
    private static final String STUB_SUFFIX = ".koonj.symlink";
    private static final String STUB_HEADER = "KOONJ-SYMLINK\n";
    public static final String ARCHIVE_REFERENCE = "koonj-ref://";
    private static final String WINDOWS_REFERENCE = ".koonj-ref-";
    private static final String TEMP_PREFIX = ".koonj-transfer-";
    private static final List<String> INSPECTED_ATTRIBUTES = List.of(
            "author", "created-by", "last-modified-by", "last-accessed-by", "created-at", "last-accessed-at");

    @Override
    public boolean supports(ProviderType type) {
        return type == ProviderType.LOCAL || type == ProviderType.NAS;
    }

    protected Path configuredPath(StorageConnection config) throws IOException {
        return Path.of(config.getBasePathOrBucket());
    }

    public Path storageRoot(StorageConnection config) throws IOException {
        Path root = configuredPath(config).toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Storage root must be a directory");
        return root;
    }

    private Path resolve(Path root, String relativePath) throws IOException {
        StoragePaths.requireSafe(relativePath);
        Path relative = Path.of(relativePath);
        Path path = root.resolve(relative);
        if (relative.isAbsolute() || !path.startsWith(root)) throw new IOException("Expected a path under the storage root");
        for (Path parent = path.getParent(); parent != null && !parent.equals(root) && parent.startsWith(root);
             parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Storage path traverses a symbolic link");
        }
        return path;
    }

    @Override
    public void checkConnectivity(StorageConnection config) throws IOException {
        checkReadable(storageRoot(config));
    }

    @Override
    public Optional<StorageCapacity> capacity(StorageConnection config) throws IOException {
        var store = Files.getFileStore(storageRoot(config));
        long total = store.getTotalSpace();
        if (total <= 0) return Optional.empty();
        long free = Math.clamp(store.getUnallocatedSpace(), 0, total);
        long available = Math.clamp(store.getUsableSpace(), 0, free);
        return Optional.of(new StorageCapacity(total, total - free, available));
    }

    private static void checkReadable(Path root) throws IOException {
        try (var entries = Files.newDirectoryStream(root)) {
            entries.iterator().hasNext();
        }
    }

    @Override
    public void testConnection(StorageConnection config) throws IOException {
        Path root = storageRoot(config);
        checkReadable(root);
        if (config.getRole() != StorageRole.SOURCE) {
            Files.delete(Files.createTempFile(root, TEMP_PREFIX, ".koonj.tmp"));
        }
    }

    @Override
    public List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) throws IOException {
        List<ScannedFileInfo> files = new ArrayList<>();
        visitFiles(config, subPath, files::add);
        return files;
    }

    @Override
    public Optional<ScannedFileInfo> statFile(StorageConnection config, String path) throws IOException {
        Path root = storageRoot(config);
        Path entry = resolve(root, path);
        if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
            if (entry.equals(root)) return Optional.empty();
            var modified = Files.getLastModifiedTime(entry, NOFOLLOW_LINKS).toInstant();
            return Optional.of(new ScannedFileInfo(path, 0, modified, null, EntryType.DIRECTORY, null));
        }
        return StorageConnector.super.statFile(config, path);
    }

    @Override
    public void visitFiles(StorageConnection config, String subPath, Consumer<ScannedFileInfo> visitor) throws IOException {
        Path root = storageRoot(config);
        Path start = resolve(root, subPath);
        if (Files.notExists(start, NOFOLLOW_LINKS)) {
            if (subPath.isEmpty() || stubTarget(stub(start)) == null) return;
            start = stub(start);
        }
        // The default walk options leave symlinks, including directory links, untraversed.
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root)) add(directory, attributes, EntryType.DIRECTORY, null);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (file.getFileName().toString().startsWith(TEMP_PREFIX)
                        && file.getFileName().toString().endsWith(".koonj.tmp")) return FileVisitResult.CONTINUE;
                if (attributes.isSymbolicLink()) {
                    Path link = Files.readSymbolicLink(file);
                    String target = link.toString();
                    if (target.startsWith("koonj-ref:/") || target.startsWith(WINDOWS_REFERENCE)) {
                        String id = target.startsWith(WINDOWS_REFERENCE) ? target.substring(WINDOWS_REFERENCE.length())
                                : target.substring("koonj-ref:".length()).replaceFirst("^/+", "");
                        if (id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) target = ARCHIVE_REFERENCE + id;
                        else target = file.getParent().resolve(link).normalize().toString();
                    } else if (target.startsWith("s3:/")) {
                        target = "s3://" + target.substring(4).replaceFirst("^/+", "");
                    } else {
                        target = file.getParent().resolve(link).normalize().toString();
                    }
                    add(file, attributes, EntryType.SYMLINK, target);
                } else if (attributes.isRegularFile()) {
                    String target = stubTarget(file);
                    if (target == null) {
                        add(file, attributes, EntryType.FILE, null);
                    } else {
                        String name = file.getFileName().toString();
                        Path logical = file.resolveSibling(name.substring(0, name.length() - STUB_SUFFIX.length()));
                        if (Files.notExists(logical, NOFOLLOW_LINKS)) add(logical, attributes, EntryType.SYMLINK, target);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            private void add(Path path, BasicFileAttributes attributes, EntryType type, String target) {
                visitor.accept(new ScannedFileInfo(root.relativize(path).toString().replace(File.separatorChar, '/'),
                        type == EntryType.FILE ? attributes.size() : 0,
                        attributes.lastModifiedTime().toInstant(), null, type, target));
            }
        });
    }

    @Override
    public FileAttributes fileAttributes(StorageConnection config, String relativePath) throws IOException {
        Path path = resolve(storageRoot(config), relativePath);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) return FileAttributes.EMPTY;
        String owner = null;
        try {
            owner = Files.getOwner(path, NOFOLLOW_LINKS).getName();
        } catch (IOException | UnsupportedOperationException ignored) {
            // The mount may expose timestamps without sharing owner information.
        }
        return FileAttributes.from(owner, reportedTime(attributes.creationTime()), reportedTime(attributes.lastAccessTime()),
                userAttributes(path));
    }

    private static Instant reportedTime(FileTime time) {
        Instant value = time.toInstant();
        return Instant.EPOCH.equals(value) ? null : value;
    }

    private static Map<String, String> userAttributes(Path path) {
        var view = Files.getFileAttributeView(path, UserDefinedFileAttributeView.class, NOFOLLOW_LINKS);
        if (view == null) return Map.of();
        Map<String, String> values = new HashMap<>();
        try {
            var names = view.list();
            for (String name : INSPECTED_ATTRIBUTES) {
                if (!names.contains(name)) continue;
                int length = view.size(name);
                if (length == 0 || length > 4096) continue;
                var content = ByteBuffer.allocate(length);
                view.read(name, content);
                String value = StandardCharsets.UTF_8.decode(content.flip()).toString().strip();
                if (!value.isEmpty()) values.put(name, value);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Optional user attributes are independent of the basic file attributes.
        }
        return values;
    }

    @Override
    public InputStream openReadStream(StorageConnection config, String relativePath) throws IOException {
        Path path = resolve(storageRoot(config), relativePath);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || stubTarget(path) != null) throw new IOException("Expected a regular file");
        return new BufferedInputStream(Files.newInputStream(path, NOFOLLOW_LINKS));
    }

    @Override
    public boolean isSymlink(StorageConnection config, String relativePath) throws IOException {
        Path path = resolve(storageRoot(config), relativePath);
        return Files.isSymbolicLink(path) || (Files.notExists(path, NOFOLLOW_LINKS) && stubTarget(stub(path)) != null);
    }

    @Override
    public void createSymlink(StorageConnection config, String relativePath, String targetReference) throws IOException {
        Path path = resolve(storageRoot(config), relativePath);
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) throw new IOException("Cannot replace a directory with a symbolic link");
        try (var temporary = new TemporaryFiles(path.getParent())) {
            Path staged = temporary.reserve();
            // Windows Path rejects the URI colon. Store a relative UUID marker in the
            // NTFS reparse point and expose the same canonical reference in the catalog.
            String nativeTarget = File.separatorChar == '\\' && targetReference.startsWith(ARCHIVE_REFERENCE)
                    ? WINDOWS_REFERENCE + targetReference.substring(ARCHIVE_REFERENCE.length())
                    : targetReference;
            Files.createSymbolicLink(staged, Path.of(nativeTarget));
            Files.move(staged, path, ATOMIC_MOVE, REPLACE_EXISTING);
        }
    }

    @Override
    public void replaceWithSymlink(StorageConnection config, String relativePath, String targetReference,
                                   RestoreCommit commit) throws IOException {
        Path path = resolve(storageRoot(config), relativePath);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        try (var temporary = new TemporaryFiles(path.getParent())) {
            Path backup = backup(path, temporary);
            createSymlink(config, relativePath, targetReference);
            try {
                commit.run();
            } catch (IOException | RuntimeException failure) {
                rollback(temporary, backup, path, failure);
                try {
                    Path marker = stub(path);
                    if (stubTarget(marker) != null) Files.delete(marker);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    @Override
    public void writeStream(StorageConnection config, String relativePath, InputStream stream,
                            long contentLength, String storageClass) throws IOException {
        restore(config, relativePath, stream, contentLength, () -> {});
    }

    @Override
    public void restore(StorageConnection config, String relativePath, InputStream stream,
                        long contentLength, RestoreCommit commit) throws IOException {
        Path destination = resolve(storageRoot(config), relativePath);
        Files.createDirectories(destination.getParent());
        try (var temporary = new TemporaryFiles(destination.getParent())) {
            Path staging = temporary.create();
            long written = Files.copy(stream, staging, REPLACE_EXISTING);
            if (written != contentLength) throw new IOException("Source length changed during transfer");

            Path marker = stub(destination);
            Path original = Files.exists(destination, NOFOLLOW_LINKS) ? destination
                    : stubTarget(marker) != null ? marker : null;
            Path backup = original == null ? null : backup(original, temporary);
            Files.move(staging, destination, ATOMIC_MOVE, REPLACE_EXISTING);
            try {
                if (marker.equals(original)) Files.delete(marker);
                commit.run();
            } catch (IOException | RuntimeException failure) {
                if (backup != null) rollback(temporary, backup, original, failure);
                if (!destination.equals(original)) {
                    try {
                        Files.deleteIfExists(destination);
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            }
        }
    }

    private static Path backup(Path original, TemporaryFiles temporary) throws IOException {
        if (Files.isDirectory(original, NOFOLLOW_LINKS)) throw new IOException("Cannot replace a directory");
        Path backup = temporary.reserve();
        if (!Files.isSymbolicLink(original)) {
            try {
                return Files.createLink(backup, original);
            } catch (IOException | UnsupportedOperationException unavailable) {
                // Some mounted filesystems cannot create hard links.
            }
        }
        return Files.copy(original, backup, NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void rollback(TemporaryFiles temporary, Path backup, Path original, Exception failure) {
        try {
            Files.move(backup, original, ATOMIC_MOVE, REPLACE_EXISTING);
        } catch (IOException cleanup) {
            temporary.preserve(backup);
            failure.addSuppressed(cleanup);
        }
    }

    @Override
    public void deleteFile(StorageConnection config, String relativePath) throws IOException {
        Path path = resolve(storageRoot(config), relativePath);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw new IOException("Expected a regular file");
        Files.delete(path);
    }

    private static Path stub(Path path) {
        return path.resolveSibling(path.getFileName() + STUB_SUFFIX);
    }

    private static String stubTarget(Path path) throws IOException {
        if (!path.getFileName().toString().endsWith(STUB_SUFFIX) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return null;
        try (InputStream stream = Files.newInputStream(path, NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(16 * 1024);
            if (bytes.length == 16 * 1024) return null;
            String contents = new String(bytes, StandardCharsets.UTF_8);
            return contents.startsWith(STUB_HEADER + "s3://") ? contents.substring(STUB_HEADER.length()) : null;
        }
    }

    private static final class TemporaryFiles implements AutoCloseable {
        private final Path directory;
        private final List<Path> paths = new ArrayList<>();

        private TemporaryFiles(Path directory) { this.directory = directory; }

        private Path create() throws IOException {
            Path path = Files.createTempFile(directory, TEMP_PREFIX, ".koonj.tmp");
            paths.add(path);
            return path;
        }

        private Path reserve() throws IOException {
            Path path = create();
            Files.delete(path);
            return path;
        }

        private void preserve(Path path) { paths.remove(path); }

        @Override
        public void close() {
            for (Path path : paths) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException failure) {
                    System.getLogger(LocalStorageConnector.class.getName()).log(System.Logger.Level.WARNING,
                            "Could not remove storage transfer temporary file", failure);
                }
            }
        }
    }
}
