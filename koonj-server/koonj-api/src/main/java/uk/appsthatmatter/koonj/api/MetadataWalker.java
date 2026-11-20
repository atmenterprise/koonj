package uk.appsthatmatter.koonj.api;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

final class MetadataWalker {
    record Entry(String relativePath, long size, Instant modifiedAt, String fileKey) {
    }

    private MetadataWalker() {
    }

    static void walk(Path mountBoundary, Path registeredRoot, int maxDepth, int batchSize,
                     Consumer<List<Entry>> flush, Consumer<IOException> reportFailure) throws IOException {
        if (maxDepth < 1 || batchSize < 1) {
            throw new IllegalArgumentException("Scan depth and batch size must be positive");
        }
        Path boundary = mountBoundary.toRealPath();
        if (!registeredRoot.isAbsolute()) {
            throw new IOException("Registered root must be absolute");
        }
        Path root = registeredRoot.toRealPath();
        if (!root.startsWith(boundary) || !Files.isDirectory(root)) {
            throw new IOException("Registered root must be a directory inside the configured mount");
        }

        List<Entry> batch = new ArrayList<>(batchSize);
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return directory.equals(root.resolve(".koonj")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isDirectory()) {
                    reportFailure.accept(new IOException("Scan depth limit reached at " + root.relativize(file)));
                }
                if (attributes.isRegularFile()) {
                    Object key = attributes.fileKey();
                    batch.add(new Entry(root.relativize(file).toString(), attributes.size(),
                            attributes.lastModifiedTime().toInstant(), key == null ? null : key.toString()));
                    if (batch.size() == batchSize) {
                        flush.accept(List.copyOf(batch));
                        batch.clear();
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                reportFailure.accept(exception);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
                if (exception != null) {
                    reportFailure.accept(exception);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!batch.isEmpty()) {
            flush.accept(List.copyOf(batch));
        }
    }
}
