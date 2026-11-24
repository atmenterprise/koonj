package uk.appsthatmatter.koonj.server.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

@Component
final class TransferTransactionLocks {
    private static final ReentrantLock EMBEDDED = new ReentrantLock();
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    TransferTransactionLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    boolean postgres() { return postgres; }

    void acquire(Collection<String> identities) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Transfer coordination requires a transaction");
        }
        if (postgres) {
            identities.stream().map(TransferTransactionLocks::key).distinct().sorted().forEach(key ->
                    jdbc.query("select pg_advisory_xact_lock(?)", result -> { }, key));
        } else {
            EMBEDDED.lock();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { EMBEDDED.unlock(); }
            });
        }
    }

    private static long key(String identity) {
        String hash = TransferReservations.digest(identity);
        return HexFormat.fromHexDigitsToLong(hash, 0, 16);
    }
}
