package uk.appsthatmatter.koonj.core;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OperationClaims {
    public record Claim(long operationId, long generationId, FileLifecycle.Operation kind,
                        int attempt, UUID token) {
    }

    private final JdbcClient jdbc;

    public OperationClaims(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Optional<Claim> claim(Duration lease) {
        Optional<Long> file = jdbc.sql("""
                SELECT f.id
                FROM file_operation o
                JOIN file_generation g ON g.id = o.generation_id
                JOIN inventory_file f ON f.id = g.file_id
                WHERE (o.state = 'QUEUED' AND o.available_at <= statement_timestamp()
                    OR o.state = 'RUNNING' AND o.lease_until <= statement_timestamp())
                  AND NOT EXISTS (
                      SELECT 1 FROM file_operation active
                      JOIN file_generation ag ON ag.id = active.generation_id
                      WHERE ag.file_id = f.id AND active.state = 'RUNNING'
                        AND active.lease_until > statement_timestamp()
                  )
                ORDER BY CASE WHEN o.state = 'RUNNING' THEN o.lease_until ELSE o.available_at END, o.id
                LIMIT 1 FOR UPDATE OF f SKIP LOCKED
                """).query(Long.class).optional();

        return file.flatMap(fileId -> jdbc.sql("""
                UPDATE file_operation SET state = 'RUNNING', attempts = attempts + 1,
                    claim_token = :token,
                    lease_until = statement_timestamp() + :milliseconds * interval '1 millisecond',
                    updated_at = statement_timestamp()
                WHERE id = (
                    SELECT o.id FROM file_operation o
                    JOIN file_generation g ON g.id = o.generation_id
                    WHERE g.file_id = :file
                      AND (o.state = 'QUEUED' AND o.available_at <= statement_timestamp()
                        OR o.state = 'RUNNING' AND o.lease_until <= statement_timestamp())
                      AND NOT EXISTS (
                          SELECT 1 FROM file_operation active
                          JOIN file_generation ag ON ag.id = active.generation_id
                          WHERE ag.file_id = :file AND active.state = 'RUNNING'
                            AND active.lease_until > statement_timestamp()
                      )
                    ORDER BY (o.state = 'RUNNING') DESC, o.available_at, o.id
                    LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                )
                RETURNING id, generation_id, kind, attempts, claim_token
                """).param("file", fileId).param("token", UUID.randomUUID())
                .param("milliseconds", leaseMillis(lease))
                .query((rs, row) -> new Claim(rs.getLong("id"), rs.getLong("generation_id"),
                        FileLifecycle.Operation.valueOf(rs.getString("kind")), rs.getInt("attempts"),
                        rs.getObject("claim_token", UUID.class)))
                .optional());
    }

    public boolean renew(Claim claim, Duration lease) {
        return jdbc.sql("""
                UPDATE file_operation SET
                    lease_until = statement_timestamp() + :milliseconds * interval '1 millisecond',
                    updated_at = statement_timestamp()
                WHERE id = :id AND claim_token = :token AND state = 'RUNNING'
                    AND lease_until > statement_timestamp()
                """).param("milliseconds", leaseMillis(lease))
                .param("id", claim.operationId()).param("token", claim.token()).update() == 1;
    }

    private static long leaseMillis(Duration lease) {
        long milliseconds = lease.toMillis();
        if (milliseconds < 1) {
            throw new IllegalArgumentException("Lease must be at least one millisecond");
        }
        return milliseconds;
    }
}
