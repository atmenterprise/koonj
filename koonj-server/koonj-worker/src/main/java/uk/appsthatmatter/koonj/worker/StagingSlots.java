package uk.appsthatmatter.koonj.worker;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class StagingSlots {
    private final Path root;
    private final long reservation;
    private final int count;

    StagingSlots(@Value("${koonj.worker.staging:/var/lib/koonj/staging}") Path root,
                 @Value("${koonj.worker.slots:2}") int slots,
                 @Value("${koonj.worker.max-object-bytes:1073741824}") long maxObjectBytes,
                 @Value("${koonj.worker.staging-bytes:4294967296}") long stagingBytes) {
        if (maxObjectBytes < 1 || slots < 1) throw new IllegalArgumentException("Invalid worker limits");
        this.root = root;
        this.reservation = Math.multiplyExact(maxObjectBytes, 2L);
        this.count = Math.toIntExact(Math.min(slots, stagingBytes / reservation));
        if (count < 1) throw new IllegalArgumentException("Staging must fit a snapshot and verification copy");
    }

    record Slot(Path directory, FileChannel channel, FileLock lock) implements AutoCloseable {
        Path snapshot() { return directory.resolve("snapshot"); }
        Path verification() { return directory.resolve("verification"); }

        void clear() throws IOException {
            Files.deleteIfExists(snapshot());
            Files.deleteIfExists(verification());
        }

        @Override
        public void close() throws IOException {
            try (channel; lock) {
                if (lock.isValid()) clear();
            }
        }
    }

    Optional<Slot> reserve() throws IOException {
        Files.createDirectories(root);
        if (Files.getFileStore(root).getUsableSpace() < reservation) return Optional.empty();
        for (int i = 0; i < count; i++) {
            Path directory = Files.createDirectories(root.resolve("slot-" + i));
            FileChannel channel = FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            boolean acquired = false;
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) continue;
                Slot slot = new Slot(directory, channel, lock);
                slot.clear();
                acquired = true;
                return Optional.of(slot);
            } catch (OverlappingFileLockException busy) {
                // Another transfer in this process owns this reservation.
            } finally {
                if (!acquired) channel.close();
            }
        }
        return Optional.empty();
    }
}
