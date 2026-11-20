package uk.appsthatmatter.koonj.api;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ScanRunner {
    private final JdbcClient jdbc;
    private final JdbcTemplate batches;
    private final TransactionTemplate transactions;
    private final Path mount;
    private final int depth;
    private final int batchSize;

    ScanRunner(JdbcClient jdbc, JdbcTemplate batches, TransactionTemplate transactions,
               @Value("${koonj.mount:/mnt/koonj}") Path mount,
               @Value("${koonj.scan.depth:128}") int depth,
               @Value("${koonj.scan.batch-size:256}") int batchSize) {
        this.jdbc = jdbc;
        this.batches = batches;
        this.transactions = transactions;
        this.mount = mount;
        this.depth = depth;
        this.batchSize = batchSize;
    }

    record Scan(long id, long rootId, Path path, UUID token) {}

    @Scheduled(fixedDelayString = "${koonj.scan.poll-ms:1000}")
    public void poll() {
        UUID token = UUID.randomUUID();
        jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM scan
                    WHERE state = 'QUEUED' OR (state = 'RUNNING' AND updated_at < now() - interval '5 minutes')
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE scan s SET state = 'RUNNING', claim_token = :token, files_seen = 0,
                    errors_seen = 0, last_error = NULL, updated_at = now()
                FROM candidate c, storage_root r WHERE s.id = c.id AND s.root_id = r.id
                RETURNING s.id, r.id AS root_id, r.mount_path
                """).param("token", token).query((rs, i) -> new Scan(rs.getLong("id"),
                rs.getLong("root_id"), Path.of(rs.getString("mount_path")), token)).optional().ifPresent(this::run);
    }

    private void run(Scan scan) {
        try {
            MetadataWalker.walk(mount, scan.path(), depth, batchSize,
                    entries -> persist(scan, entries), failure -> recordError(scan, failure));
            jdbc.sql("""
                    UPDATE scan SET state = CASE WHEN errors_seen = 0 THEN 'COMPLETED' ELSE 'PARTIAL' END,
                    updated_at = now(), claim_token = NULL WHERE id = :id AND claim_token = :token
                    """).param("id", scan.id()).param("token", scan.token()).update();
        } catch (IOException | RuntimeException failure) {
            jdbc.sql("""
                    UPDATE scan SET state = 'FAILED', errors_seen = errors_seen + 1, last_error = :error,
                    updated_at = now(), claim_token = NULL WHERE id = :id AND claim_token = :token
                    """).param("id", scan.id()).param("token", scan.token())
                    .param("error", failure.toString()).update();
        }
    }

    private void recordError(Scan scan, IOException failure) {
        int changed = jdbc.sql("""
                UPDATE scan SET errors_seen = errors_seen + 1, last_error = :error, updated_at = now()
                WHERE id = :id AND claim_token = :token
                """).param("id", scan.id()).param("token", scan.token()).param("error", failure.toString()).update();
        requireClaim(changed);
    }

    private void persist(Scan scan, List<MetadataWalker.Entry> entries) {
        transactions.executeWithoutResult(status -> {
            requireClaim(jdbc.sql("""
                    UPDATE scan SET files_seen = files_seen + :count, updated_at = now()
                    WHERE id = :id AND claim_token = :token
                    """).param("id", scan.id()).param("token", scan.token()).param("count", entries.size()).update());
            batches.batchUpdate("""
                    WITH file AS (
                        INSERT INTO inventory_file (root_id, relative_path) VALUES (?, ?)
                        ON CONFLICT (root_id, relative_path) DO UPDATE SET relative_path = EXCLUDED.relative_path
                        RETURNING id
                    )
                    INSERT INTO file_generation (file_id, size_bytes, modified_at, source_identity, state)
                    SELECT file.id, ?, ?, ?, 'LOCAL' FROM file
                    LEFT JOIN LATERAL (
                        SELECT id, size_bytes, modified_at, source_identity, state FROM file_generation
                        WHERE file_id = file.id ORDER BY id DESC LIMIT 1
                    ) latest ON true
                    WHERE (latest.state IS NULL OR latest.state = 'TIERED'
                       OR (latest.size_bytes, latest.modified_at, latest.source_identity)
                          IS DISTINCT FROM (?, CAST(? AS timestamptz), CAST(? AS text)))
                    AND NOT EXISTS (
                        SELECT 1 FROM file_operation WHERE generation_id = latest.id AND kind = 'RECALL'
                            AND state IN ('QUEUED', 'RUNNING')
                    )
                    """, entries, batchSize, (ps, entry) -> {
                ps.setLong(1, scan.rootId());
                ps.setString(2, entry.relativePath());
                ps.setLong(3, entry.size());
                ps.setTimestamp(4, Timestamp.from(entry.modifiedAt()));
                ps.setString(5, entry.fileKey());
                ps.setLong(6, entry.size());
                ps.setTimestamp(7, Timestamp.from(entry.modifiedAt()));
                ps.setString(8, entry.fileKey());
            });
        });
    }

    private static void requireClaim(int changed) {
        if (changed != 1) throw new IllegalStateException("Scan claim expired");
    }
}
