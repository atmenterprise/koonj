package uk.appsthatmatter.koonj.server.storageauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

final class RefreshingSecretCredentials implements AwsCredentialsProvider {
    private static final Logger LOG = LoggerFactory.getLogger(RefreshingSecretCredentials.class);
    private final String backend;
    private final Supplier<CredentialSnapshot> load;
    private final UUID project;
    private final UUID connection;
    private final Duration ttl;
    private final Clock clock;
    private volatile Cached cached;
    private String previousVersion;
    private StorageCredentialException lastFailure;
    private Instant retryAt = Instant.MIN;

    RefreshingSecretCredentials(String backend, Supplier<CredentialSnapshot> load, UUID project,
                                 UUID connection, Duration ttl, Clock clock) {
        this.backend = backend;
        this.load = load;
        this.project = project;
        this.connection = connection;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public AwsCredentials resolveCredentials() {
        Cached current = cached;
        if (current != null && clock.instant().isBefore(current.refreshAt())) return current.snapshot().credentials();
        return refresh();
    }

    private synchronized AwsCredentials refresh() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.refreshAt())) return cached.snapshot().credentials();
        if (now.isBefore(retryAt)) throw lastFailure;

        try {
            CredentialSnapshot replacement = load.get();
            now = clock.instant();
            Instant refreshAt = now.plus(ttl);
            if (replacement.expiresAt() != null) {
                Instant expiresAt = replacement.expiresAt();
                Duration remaining = Duration.between(now, expiresAt);
                if (remaining.compareTo(Duration.ofSeconds(2)) <= 0) {
                    throw new StorageCredentialException("The external session credential is expired or about to expire");
                }
                Duration headroom = remaining.dividedBy(4);
                if (headroom.compareTo(Duration.ofSeconds(30)) > 0) headroom = Duration.ofSeconds(30);
                Instant earlyRefresh = expiresAt.minus(headroom);
                if (earlyRefresh.isBefore(refreshAt)) refreshAt = earlyRefresh;
            }
            if (previousVersion != null && !Objects.equals(previousVersion, replacement.version())) {
                LOG.info("Storage credential rotation observed backend={} project={} connection={}",
                        backend, project, connection);
            }
            LOG.info("Storage credential resolved backend={} project={} connection={}", backend, project, connection);
            previousVersion = replacement.version();
            cached = new Cached(replacement, refreshAt);
            lastFailure = null;
            return replacement.credentials();
        } catch (StorageCredentialException failure) {
            cached = null;
            lastFailure = failure;
            retryAt = clock.instant().plusSeconds(2);
            LOG.warn("Storage credential resolution failed backend={} project={} connection={} reason={}",
                    backend, project, connection, failure.getMessage());
            throw failure;
        }
    }

    private record Cached(CredentialSnapshot snapshot, Instant refreshAt) {}
}
