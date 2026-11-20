package uk.appsthatmatter.koonj.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class OperationClaimsTest {
    @Test
    void invalidLifecycleTransitionFails() {
        assertThrows(IllegalStateException.class,
                () -> FileLifecycle.after(FileLifecycle.State.LOCAL, FileLifecycle.Operation.TIER));
    }

    @Test
    void serializesClaimsAndRejectsStaleTokens() throws Exception {
        String url = System.getenv("KOONJ_TEST_JDBC_URL");
        assumeTrue(url != null, "Requires a disposable PostgreSQL 18 database");
        var dataSource = new DriverManagerDataSource(url,
                System.getenv("KOONJ_TEST_DB_USER"), System.getenv("KOONJ_TEST_DB_PASSWORD"));
        var jdbc = JdbcClient.create(dataSource);
        assertEquals(18, jdbc.sql("SHOW server_version_num").query(Integer.class).single() / 10000);
        var migration = Path.of("../koonj-migrations/src/main/resources/db/migration/V1__inventory_and_operations.sql");
        new ResourceDatabasePopulator(new FileSystemResource(migration)).execute(dataSource);

        jdbc.sql("INSERT INTO storage_root (name, mount_path) VALUES ('test', '/test')").update();
        jdbc.sql("INSERT INTO inventory_file (root_id, relative_path) VALUES (1, 'file')").update();
        jdbc.sql("""
                INSERT INTO file_generation (file_id, size_bytes, modified_at, state)
                VALUES (1, 1, now(), 'LOCAL'), (1, 2, now(), 'LOCAL')
                """).update();
        jdbc.sql("""
                INSERT INTO file_operation (generation_id, kind, state)
                VALUES (1, 'ARCHIVE', 'QUEUED'), (2, 'ARCHIVE', 'QUEUED')
                """).update();

        var claims = new OperationClaims(jdbc);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var lease = Duration.ofMinutes(1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transactions.execute(status -> {
                var claim = claims.claim(lease).orElseThrow();
                locked.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return claim;
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var competing = workers.submit(() -> transactions.execute(status -> claims.claim(lease)));
                assertTrue(competing.get(5, TimeUnit.SECONDS).isEmpty());
            } finally {
                release.countDown();
            }

            var oldClaim = first.get(10, TimeUnit.SECONDS);
            assertTrue(transactions.execute(status -> claims.claim(lease)).isEmpty());
            jdbc.sql("UPDATE file_operation SET lease_until = now() - interval '1 second' WHERE id = :id")
                    .param("id", oldClaim.operationId()).update();
            var replacement = transactions.execute(status -> claims.claim(lease)).orElseThrow();
            assertEquals(oldClaim.operationId(), replacement.operationId());
            assertEquals(2, replacement.attempt());
            assertNotEquals(oldClaim.token(), replacement.token());
            assertFalse(claims.renew(oldClaim, lease));
            assertTrue(claims.renew(replacement, lease));
        }
    }
}
