package uk.appsthatmatter.koonj.worker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import uk.appsthatmatter.koonj.core.FileSha256;

final class FileSnapshot {
    record Generation(long bytes, Instant modified, String fileKey) {}

    static void validate(Path path, Generation generation) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        // PostgreSQL stores timestamps to microsecond precision; JDBC rounds nanoseconds on insert.
        Instant modified = attrs.lastModifiedTime().toInstant().plusNanos(500)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!attrs.isRegularFile() || attrs.size() != generation.bytes() || !modified.equals(generation.modified())
                || !Objects.equals(Objects.toString(attrs.fileKey(), null), generation.fileKey())) {
            throw new IOException("Source no longer matches the catalog generation");
        }
    }

    static byte[] create(Path source, Generation generation, Path snapshot) throws IOException, InterruptedException {
        validate(source, generation);
        try (var input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var output = FileChannel.open(snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer block = ByteBuffer.allocateDirect(128 * 1024);
            long copied = 0;
            int read;
            while ((read = input.read(block)) != -1) {
                copied = Math.addExact(copied, read);
                if (copied > generation.bytes()) throw new IOException("Source grew during snapshot");
                block.flip();
                while (block.hasRemaining()) output.write(block);
                block.clear();
            }
            if (copied != generation.bytes()) throw new IOException("Source shrank during snapshot");
        }
        byte[] digest = FileSha256.digest(snapshot);
        verifySource(source, generation, digest);
        return digest;
    }

    static void verifySource(Path source, Generation generation, byte[] digest) throws IOException, InterruptedException {
        validate(source, generation);
        if (!Arrays.equals(FileSha256.digest(source), digest)) throw new IOException("Source digest changed");
        validate(source, generation);
    }

    private FileSnapshot() {}
}
