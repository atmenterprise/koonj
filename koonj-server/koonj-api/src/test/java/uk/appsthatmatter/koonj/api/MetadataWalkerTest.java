package uk.appsthatmatter.koonj.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class MetadataWalkerTest {
    @Test
    void traversalWaitsForPersistence(@TempDir Path mount) throws Exception {
        for (int i = 0; i < 8; i++) Files.createFile(mount.resolve("file" + i));
        var stalled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var scan = executor.submit(() -> {
                MetadataWalker.walk(mount, mount, 4, 2, entries -> {
                    assertEquals(2, entries.size());
                    count.addAndGet(entries.size());
                    stalled.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }, e -> fail(e));
                return null;
            });
            try {
                assertTrue(stalled.await(5, TimeUnit.SECONDS));
                assertFalse(scan.isDone());
                assertEquals(2, count.get());
            } finally {
                release.countDown();
            }
            scan.get(5, TimeUnit.SECONDS);
            assertEquals(8, count.get());
        }
    }
}
