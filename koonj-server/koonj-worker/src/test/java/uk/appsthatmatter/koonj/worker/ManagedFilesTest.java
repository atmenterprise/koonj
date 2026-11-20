package uk.appsthatmatter.koonj.worker;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.appsthatmatter.koonj.core.FileLifecycle;
import uk.appsthatmatter.koonj.core.FileSha256;
import uk.appsthatmatter.koonj.core.OperationClaims;
import static org.junit.jupiter.api.Assertions.*;

class ManagedFilesTest {
    final OperationClaims.Claim claim = new OperationClaims.Claim(1, 1, FileLifecycle.Operation.TIER, 1, UUID.randomUUID());
    @TempDir Path root;

    @Test
    void preservesConflictsAndRecoversPublishedRecall() throws Exception {
        Path verified = Files.writeString(root.resolve("stage"), "verified");
        byte[] digest = FileSha256.digest(verified);
        Path target = root.resolve("result");
        assertThrows(IllegalStateException.class, () -> ManagedFiles.publish(verified, target, digest, claim,
                () -> { throw new IllegalStateException("lost lease"); }));
        assertFalse(Files.exists(target));
        assertThrows(IOException.class, () -> ManagedFiles.publish(verified, target, new byte[32], claim, () -> {}));
        assertFalse(Files.exists(target));
        Files.writeString(target, "existing");
        assertThrows(IOException.class, () -> ManagedFiles.publish(verified, target, digest, claim, () -> {}));
        assertEquals("existing", Files.readString(target));
        Files.delete(target);
        ManagedFiles.publish(verified, target, digest, claim, () -> {});
        Object original = attrs(target).fileKey();
        ManagedFiles.publish(verified, target, digest, claim, () -> {});
        assertEquals(original, attrs(target).fileKey());
    }

    @Test
    void reconcilesQuarantineAndVerifiedDeletion() throws Exception {
        Path file = Files.writeString(root.resolve("source"), "keep me");
        Files.createDirectory(root.resolve(".koonj"));
        var info = attrs(file);
        var generation = new FileSnapshot.Generation(info.size(),
                info.lastModifiedTime().toInstant().plusNanos(500).truncatedTo(ChronoUnit.MICROS), info.fileKey().toString());
        byte[] digest = FileSha256.digest(file);
        var checks = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ManagedFiles.quarantine(root, file, claim, generation, digest,
                () -> { if (checks.incrementAndGet() == 2) throw new IllegalStateException("before unlink"); }));
        assertEquals("keep me", Files.readString(root.resolve(".koonj/quarantine-1")));
        Files.writeString(file, "replacement");
        assertThrows(IOException.class, () -> ManagedFiles.quarantine(root, file, claim, generation, digest, () -> {}));
        assertEquals("replacement", Files.readString(file));
        Files.delete(file);
        ManagedFiles.quarantine(root, file, claim, generation, digest, () -> {});
        ManagedFiles.quarantine(root, file, claim, generation, digest, () -> {});
        assertTrue(Files.exists(root.resolve(".koonj/verified-1")));
        assertFalse(Files.exists(root.resolve(".koonj/quarantine-1")));
    }

    @Test
    void abandonedStageIsReclaimedAndSlotsAreBounded() throws Exception {
        var slots = new StagingSlots(root, 4, 1024, 2048);
        try (var slot = slots.reserve().orElseThrow()) {
            Files.writeString(slot.snapshot(), "partial");
            assertTrue(slots.reserve().isEmpty());
            slot.channel().close(); // Simulate process exit before cleanup.
            try (var replacement = slots.reserve().orElseThrow()) {
                assertFalse(Files.exists(replacement.snapshot()));
                assertTrue(slots.reserve().isEmpty());
            }
        } catch (java.nio.channels.ClosedChannelException expectedAfterSimulatedExit) {
            // The interrupted owner's channel was deliberately closed.
        }
    }

    private static BasicFileAttributes attrs(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class);
    }
}
