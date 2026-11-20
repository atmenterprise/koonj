package uk.appsthatmatter.koonj.core;

import java.io.IOException;
import java.nio.file.Path;

/** Computes a digest of a stable file without reading its payload into Java. */
public final class FileSha256 {
    private static final int DIGEST_BYTES = 32;

    private FileSha256() {
    }

    public static byte[] digest(Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("openssl", "dgst", "-sha256", "-binary")
                .redirectInput(file.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (var output = process.getInputStream()) {
            // OpenSSL reads from the file descriptor. Only the digest crosses the pipe.
            int status = process.waitFor();
            byte[] digest = output.readNBytes(DIGEST_BYTES + 1);
            if (status != 0 || digest.length != DIGEST_BYTES) {
                throw new IOException("OpenSSL SHA-256 failed for " + file + " (exit " + status + ")");
            }
            return digest;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
