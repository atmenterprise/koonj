package uk.appsthatmatter.koonj.worker;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.core.FileLifecycle;
import uk.appsthatmatter.koonj.core.FileSha256;
import uk.appsthatmatter.koonj.core.OperationClaims;

@Component
class TransferWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TransferWorker.class);
    private final OperationClaims claims;
    private final StagingSlots staging;
    private final RegionalTransfers transfers;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Duration lease;
    private final long maxBytes;
    private final Path mount;
    private final Semaphore permits;
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();

    TransferWorker(OperationClaims claims, StagingSlots staging, RegionalTransfers transfers, JdbcClient jdbc,
                   TransactionTemplate transactions, @Value("${koonj.worker.slots:2}") int slots,
                   @Value("${koonj.worker.lease:PT2M}") Duration lease,
                   @Value("${koonj.worker.max-object-bytes:1073741824}") long maxBytes,
                   @Value("${koonj.mount:/mnt/koonj}") Path mount) {
        this.claims = claims;
        this.staging = staging;
        this.transfers = transfers;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.permits = new Semaphore(slots);
        this.lease = lease;
        this.maxBytes = maxBytes;
        this.mount = mount;
        if (lease.toMillis() < 300) throw new IllegalArgumentException("Worker lease is too short");
    }

    record Data(Path root, String relative, long fileId, boolean managed, FileSnapshot.Generation generation,
                FileLifecycle.State state, byte[] digest, String key, String version, String region, String bucket) {}

    @Scheduled(fixedDelayString = "${koonj.worker.poll-ms:500}")
    void poll() throws IOException {
        if (!permits.tryAcquire()) return;
        StagingSlots.Slot slot = null;
        boolean submitted = false;
        try {
            slot = staging.reserve().orElse(null);
            if (slot == null) return;
            var claim = claims.claim(lease);
            if (claim.isEmpty()) return;
            var reserved = slot;
            jobs.submit(() -> execute(claim.get(), reserved));
            submitted = true;
        } finally {
            if (!submitted) {
                try { if (slot != null) slot.close(); } finally { permits.release(); }
            }
        }
    }

    private void execute(OperationClaims.Claim claim, StagingSlots.Slot slot) {
        var lost = new AtomicBoolean();
        var heartbeat = renewals.scheduleWithFixedDelay(() -> {
            try {
                if (!claims.renew(claim, lease)) lost.set(true);
            } catch (RuntimeException error) {
                lost.set(true);
            }
        }, lease.toMillis() / 3, lease.toMillis() / 3, TimeUnit.MILLISECONDS);
        try (slot) {
            Data data = load(claim);
            if (data.generation().bytes() > maxBytes) throw new IOException("File exceeds maximum object size");
            Path root = data.root().toRealPath();
            if (!root.startsWith(mount.toRealPath())) throw new IOException("Root outside worker mount");
            Path relative = Path.of(data.relative());
            Path file = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !file.startsWith(root) || file.equals(root)
                    || relative.startsWith(".koonj") || !file.getParent().toRealPath().equals(file.getParent())) {
                throw new IOException("Path outside registered root or reserved");
            }
            if (data.managed()) {
                try (var lock = ManagedFiles.lock(root, data.fileId())) {
                    perform(claim, slot, data, file, lost);
                }
            } else {
                if (claim.kind() == FileLifecycle.Operation.TIER) throw new IOException("Tiering requires a dedicated managed root");
                perform(claim, slot, data, file, lost);
            }
        } catch (Exception error) {
            LOG.warn("Operation {} attempt {} failed ({})", claim.operationId(), claim.attempt(), error.getClass().getSimpleName());
            jdbc.sql("""
                    UPDATE file_operation SET state = CASE WHEN attempts >= 5 THEN 'FAILED' ELSE 'QUEUED' END,
                        available_at = now() + least(300, power(2, least(attempts, 8))) * interval '1 second',
                        last_error = :error, updated_at = now(), lease_until = NULL, claim_token = NULL
                    WHERE id = :id AND claim_token = :token AND lease_until > clock_timestamp()
                    """).param("error", error.getClass().getSimpleName()).param("id", claim.operationId())
                    .param("token", claim.token()).update();
        } finally {
            heartbeat.cancel(false);
            permits.release();
        }
    }

    private Data load(OperationClaims.Claim claim) {
        return jdbc.sql("""
                SELECT r.mount_path, r.managed, f.id AS file_id, f.relative_path, g.size_bytes, g.modified_at,
                    g.source_identity, g.state, g.sha256, g.object_key, g.object_version, l.aws_region, l.bucket_name
                FROM file_operation o JOIN file_generation g ON g.id = o.generation_id
                JOIN inventory_file f ON f.id = g.file_id JOIN storage_root r ON r.id = f.root_id
                JOIN storage_locations l ON l.id = CASE WHEN o.kind = 'ARCHIVE' THEN o.target_location_id
                        ELSE g.archive_location_id END
                WHERE o.id = :id AND o.claim_token = :token
                    AND g.id = (SELECT id FROM file_generation WHERE file_id = f.id ORDER BY id DESC LIMIT 1)
                """).param("id", claim.operationId()).param("token", claim.token()).query((rs, row) ->
                new Data(Path.of(rs.getString("mount_path")), rs.getString("relative_path"), rs.getLong("file_id"),
                        rs.getBoolean("managed"), new FileSnapshot.Generation(rs.getLong("size_bytes"),
                        rs.getTimestamp("modified_at").toInstant(), rs.getString("source_identity")),
                        FileLifecycle.State.valueOf(rs.getString("state")), rs.getBytes("sha256"),
                        rs.getString("object_key"), rs.getString("object_version"), rs.getString("aws_region"),
                        rs.getString("bucket_name"))).single();
    }

    private void perform(OperationClaims.Claim claim, StagingSlots.Slot slot, Data data, Path file,
                         AtomicBoolean lost) throws Exception {
        FileLifecycle.State result = FileLifecycle.after(data.state(), claim.kind());
        String key = data.key();
        String version = data.version();
        byte[] digest = data.digest();
        guard(claim, lost);
        if (claim.kind() == FileLifecycle.Operation.ARCHIVE) {
            digest = FileSnapshot.create(file, data.generation(), slot.snapshot());
            key = "koonj/generations/" + claim.generationId() + "/" + java.util.HexFormat.of().formatHex(digest);
            version = transfers.upload(new RegionalTransfers.Target(data.region(), data.bucket(), key, null), slot.snapshot());
        }
        var target = new RegionalTransfers.Target(data.region(), data.bucket(), key, version);
        transfers.download(target, slot.verification(), data.generation().bytes());
        if (Files.size(slot.verification()) != data.generation().bytes()
                || !Arrays.equals(digest, FileSha256.digest(slot.verification()))) {
            throw new IOException("Remote verification failed");
        }
        guard(claim, lost);
        switch (claim.kind()) {
            case ARCHIVE -> FileSnapshot.verifySource(file, data.generation(), digest);
            case TIER -> ManagedFiles.quarantine(data.root().toRealPath(), file, claim, data.generation(), digest,
                    () -> guard(claim, lost));
            case RECALL -> ManagedFiles.publish(slot.verification(), file, digest, claim, () -> guard(claim, lost));
        }
        var recalled = claim.kind() == FileLifecycle.Operation.RECALL
                ? Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS) : null;
        finish(claim, result, key, version, digest, recalled);
        if (claim.kind() == FileLifecycle.Operation.TIER) {
            Files.deleteIfExists(data.root().resolve(".koonj/verified-" + claim.operationId()));
        }
    }

    private void guard(OperationClaims.Claim claim, AtomicBoolean lost) {
        if (lost.get() || !claims.renew(claim, lease)) throw new IllegalStateException("Claim lost");
    }

    private void finish(OperationClaims.Claim claim, FileLifecycle.State state, String key, String version, byte[] digest,
                        java.nio.file.attribute.BasicFileAttributes recalled) {
        transactions.executeWithoutResult(status -> {
            int accepted = jdbc.sql("""
                    UPDATE file_operation SET state = 'COMPLETED', updated_at = now(), last_error = NULL,
                        lease_until = NULL, claim_token = NULL
                    WHERE id = :id AND claim_token = :token AND lease_until > clock_timestamp()
                    """).param("id", claim.operationId()).param("token", claim.token()).update();
            if (accepted != 1) throw new IllegalStateException("Claim lost");
            int changed = jdbc.sql("""
                    UPDATE file_generation g SET state = :state, object_key = :key, object_version = :version,
                        sha256 = :digest, archive_location_id = coalesce(o.target_location_id, g.archive_location_id)
                    FROM file_operation o WHERE o.id = :id AND g.id = o.generation_id
                    AND g.id = (SELECT id FROM file_generation WHERE file_id = g.file_id ORDER BY id DESC LIMIT 1)
                    """).param("state", state.name()).param("key", key).param("version", version, java.sql.Types.VARCHAR)
                    .param("digest", digest).param("id", claim.operationId()).update();
            if (changed != 1) throw new IllegalStateException("Generation changed");
            if (recalled != null) {
                jdbc.sql("UPDATE file_generation SET modified_at = :modified, source_identity = :identity WHERE id = :id")
                        .param("modified", java.sql.Timestamp.from(recalled.lastModifiedTime().toInstant()))
                        .param("identity", java.util.Objects.toString(recalled.fileKey(), null), java.sql.Types.VARCHAR)
                        .param("id", claim.generationId()).update();
            }
        });
    }

    @PreDestroy
    void close() {
        jobs.close();
        renewals.shutdownNow();
    }
}
