package uk.appsthatmatter.koonj.worker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import uk.appsthatmatter.koonj.core.FileSha256;
import uk.appsthatmatter.koonj.core.OperationClaims;

final class ManagedFiles {
    private ManagedFiles() {}

    static FileChannel lock(Path root, long fileId) throws IOException, InterruptedException {
        Path directory = root.resolve(".koonj");
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException exists) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw exists;
        }
        var channel = FileChannel.open(directory.resolve("lock-" + fileId), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() == null) throw new IOException("Managed file is busy");
            verifyLock(directory.resolve("lock-" + fileId));
            return channel;
        } catch (IOException | RuntimeException | InterruptedException failure) {
            channel.close();
            throw failure;
        }
    }

    private static void verifyLock(Path path) throws IOException, InterruptedException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var source = new org.springframework.boot.system.ApplicationHome(LockProbe.class).getSource();
        ProcessBuilder command = source != null && source.isFile()
                ? new ProcessBuilder(java, "-jar", source.getAbsolutePath(), "--probe-lock", path.toString())
                : new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        LockProbe.class.getName(), "--probe-lock", path.toString());
        var child = command.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!child.waitFor(15, TimeUnit.SECONDS) || child.exitValue() != 0) {
                throw new IOException("Mount failed the cross-process lock probe");
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    static void quarantine(Path root, Path source, OperationClaims.Claim claim, FileSnapshot.Generation generation,
                           byte[] digest, Runnable guard) throws IOException, InterruptedException {
        Path directory = root.resolve(".koonj");
        Path held = directory.resolve("quarantine-" + claim.operationId());
        Path verified = directory.resolve("verified-" + claim.operationId());
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(verified) || Files.exists(held)) throw new IOException("Source conflicts with quarantine");
            FileSnapshot.verifySource(source, generation, digest);
            guard.run();
            Files.move(source, held, StandardCopyOption.ATOMIC_MOVE);
        }
        if (Files.exists(held, LinkOption.NOFOLLOW_LINKS)) {
            FileSnapshot.verifySource(held, generation, digest);
            guard.run();
            if (Files.notExists(verified)) {
                try (FileChannel marker = FileChannel.open(verified, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    marker.force(true);
                }
            }
            Files.delete(held);
        } else if (!Files.isRegularFile(verified, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing verified quarantine");
        }
    }

    static void publish(Path verified, Path destination, byte[] digest, OperationClaims.Claim claim, Runnable guard)
            throws IOException, InterruptedException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            verifyDestination(destination, digest);
            return;
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".koonj-recall-" + claim.operationId() + "-", ".part");
        try {
            try (var source = FileChannel.open(verified, StandardOpenOption.READ);
                 var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.allocateDirect(128 * 1024);
                while (source.read(buffer) != -1) {
                    buffer.flip();
                    while (buffer.hasRemaining()) output.write(buffer);
                    buffer.clear();
                }
                output.force(true);
            }
            verifyDestination(temporary, digest);
            guard.run();
            try {
                Files.createLink(destination, temporary);
            } catch (FileAlreadyExistsException conflict) {
                verifyDestination(destination, digest);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verifyDestination(Path file, byte[] expected) throws IOException, InterruptedException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Arrays.equals(expected, FileSha256.digest(file))) {
            throw new IOException("Destination conflicts with verified archive");
        }
    }
}
