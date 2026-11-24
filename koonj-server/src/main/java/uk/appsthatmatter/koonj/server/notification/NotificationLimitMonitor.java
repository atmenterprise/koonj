package uk.appsthatmatter.koonj.server.notification;

import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.notification.NotificationRuleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.entity.EntryType;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;
import uk.appsthatmatter.koonj.server.service.JobService;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static uk.appsthatmatter.koonj.notification.NotificationEvent.Type.LICENSE_ALERT;
import static uk.appsthatmatter.koonj.notification.NotificationEvent.Type.QUOTA_THRESHOLD;

@Component
@EnableScheduling
class NotificationLimitMonitor {
    private final NotificationRuleRepository rules;
    private final FileMetadataRepository files;
    private final StorageConnectionRepository connections;
    private final JobService jobs;
    private final NotificationOutboxWriter events;
    private final long fileLimit;
    private final long connectionLimit;
    private final long concurrencyLimit;
    private final Instant expiresAt;
    private final Instant graceEndsAt;

    NotificationLimitMonitor(NotificationRuleRepository rules, FileMetadataRepository files, StorageConnectionRepository connections,
                             JobService jobs, NotificationOutboxWriter events,
                             @Value("${koonj.notifications.limits.cataloged-files}") long fileLimit,
                             @Value("${koonj.notifications.limits.storage-connections}") long connectionLimit,
                             @Value("${koonj.notifications.limits.transfer-concurrency}") long concurrencyLimit,
                             @Value("${koonj.notifications.limits.license-expires-at}") String expiry,
                             @Value("${koonj.notifications.limits.license-grace-ends-at}") String grace) {
        this.rules = rules;
        this.files = files;
        this.connections = connections;
        this.jobs = jobs;
        this.events = events;
        this.fileLimit = fileLimit;
        this.connectionLimit = connectionLimit;
        this.concurrencyLimit = concurrencyLimit;
        this.expiresAt = expiry.isBlank() ? null : Instant.parse(expiry);
        this.graceEndsAt = grace.isBlank() ? null : Instant.parse(grace);
        if (graceEndsAt != null && (expiresAt == null || graceEndsAt.isBefore(expiresAt))) {
            throw new IllegalArgumentException("License grace end must be at or after license expiry");
        }
    }

    @Scheduled(fixedDelayString = "${koonj.notifications.limits.check-interval}", initialDelayString = "${koonj.notifications.limits.check-interval}")
    void check() {
        if ((fileLimit > 0 || connectionLimit > 0 || concurrencyLimit > 0) && rules.existsByEventTypeAndEnabledTrue(QUOTA_THRESHOLD)) {
            if (fileLimit > 0) quota("CATALOGED_FILES", files.countByEntryType(EntryType.FILE), fileLimit);
            if (connectionLimit > 0) quota("STORAGE_CONNECTIONS", connections.count(), connectionLimit);
            if (concurrencyLimit > 0) quota("TRANSFER_CONCURRENCY", jobs.sampleTransferConcurrency(), concurrencyLimit);
        }
        if (expiresAt != null && rules.existsByEventTypeAndEnabledTrue(LICENSE_ALERT)) license(Instant.now());
    }

    private void quota(String metric, long usage, long limit) {
        String state = usage >= limit ? "LIMIT_REACHED" : "APPROACHING_LIMIT";
        events.enqueue(new NotificationEvent(QUOTA_THRESHOLD, metric + ":" + limit, null, null,
                usage >= limit ? "Koonj quota reached" : "Koonj quota warning",
                Map.of("metric", metric, "state", state, "usage", usage, "limit", limit,
                        "percent", Math.round(usage * 100.0 / limit))));
    }

    private void license(Instant now) {
        String state = now.isBefore(expiresAt) ? "EXPIRING"
                : graceEndsAt != null && now.isBefore(graceEndsAt) ? "GRACE_PERIOD" : "EXPIRED";
        long secondsRemaining = Math.max(0, Duration.between(now, expiresAt).toSeconds());
        long daysRemaining = Math.ceilDiv(secondsRemaining, 86_400L);
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("state", state);
        details.put("expiresAt", expiresAt);
        details.put("daysRemaining", daysRemaining);
        if (graceEndsAt != null) details.put("graceEndsAt", graceEndsAt);
        events.enqueue(new NotificationEvent(LICENSE_ALERT, state + ":" + expiresAt, null, null, "Koonj license alert", details));
    }
}
