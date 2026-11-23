package uk.appsthatmatter.koonj.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.notification.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class NotificationRouter {
    private final NotificationOutboxRepository outbox;
    private final NotificationRuleRepository rules;
    private final NotificationChannelRepository channels;
    private final NotificationDeliveryRepository deliveries;
    private final TransactionTemplate transaction;

    NotificationRouter(NotificationOutboxRepository outbox, NotificationRuleRepository rules,
                       NotificationChannelRepository channels, NotificationDeliveryRepository deliveries,
                       TransactionTemplate transaction) {
        this.outbox = outbox;
        this.rules = rules;
        this.channels = channels;
        this.deliveries = deliveries;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${koonj.worker.poll-interval}")
    void route() {
        for (int i = 0; i < 25; i++) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> routeOne()))) break;
        }
    }

    private boolean routeOne() {
        var claimed = outbox.claim(1);
        if (claimed.isEmpty()) return false;
        var row = claimed.getFirst();
        var event = row.getEvent();
        if (row.getTestChannelId() != null) {
            channels.findById(row.getTestChannelId()).ifPresent(channel -> createDeliveries(channel, null, event, "test", true));
        } else {
            rules.findByEventTypeAndEnabledTrue(event.type()).stream().map(NotificationRule::getId).sorted()
                    .forEach(id -> routeRule(id, event));
        }
        outbox.delete(row);
        return true;
    }

    private void routeRule(UUID ruleId, NotificationEvent event) {
        var rule = rules.lock(ruleId).orElse(null);
        if (rule == null || !rule.isEnabled() || rule.getEventType() != event.type() || !matches(rule.getThresholdConfig(), event)) return;
        String key = event.key();
        if (event.type() == NotificationEvent.Type.QUOTA_THRESHOLD) {
            key += event.number("usage") >= event.number("limit") ? ":limit" : ":warning";
        }
        Instant since = event.type() == NotificationEvent.Type.LARGE_BATCH_COMPLETED ? Instant.EPOCH
                : Instant.now().minus(Duration.ofMinutes(rule.getThresholdConfig().cooldown()));
        for (var channel : channels.findAllById(rule.getChannelIds())) {
            if (channel.isEnabled() && !deliveries.existsByRuleIdAndChannelIdAndDedupeKeyAndCreatedAtGreaterThanEqual(
                    ruleId, channel.getId(), key, since)) {
                createDeliveries(channel, ruleId, event, key, false);
            }
        }
    }

    private static boolean matches(NotificationRule.Thresholds threshold, NotificationEvent event) {
        return switch (event.type()) {
            case LARGE_BATCH_COMPLETED -> threshold.matchesBatch(event.number("fileCount"), event.number("totalBytes"));
            case TRANSFER_FAILED -> "FAILED".equals(event.details().get("status")) || event.number("failed") >= threshold.failures();
            case QUOTA_THRESHOLD -> event.number("usage") * 100.0 / event.number("limit") >= threshold.percent();
            case LICENSE_ALERT -> !"EXPIRING".equals(event.details().get("state")) || event.number("daysRemaining") <= threshold.days();
        };
    }

    private void createDeliveries(NotificationChannel channel, UUID ruleId, NotificationEvent event, String key, boolean test) {
        if (channel.getType() != NotificationChannel.Type.EMAIL) {
            record(channel, ruleId, event, key, test, null);
            return;
        }
        List<String> admins = channel.getConfig().adminRecipients();
        if ((test || event.system()) && admins != null && !admins.isEmpty()) {
            admins.forEach(recipient -> record(channel, ruleId, event, key, test, recipient));
        } else {
            boolean validActor = event.actorId() != null && EmailAddress.valid(event.actorEmail());
            record(channel, ruleId, event, key, test, !event.system() && validActor ? event.actorEmail() : null);
        }
    }

    private void record(NotificationChannel channel, UUID ruleId, NotificationEvent event, String key, boolean test, String recipient) {
        var delivery = new NotificationDelivery();
        delivery.setChannelId(channel.getId());
        delivery.setRuleId(ruleId);
        delivery.setEventType(event.type());
        delivery.setDedupeKey(key);
        delivery.setTestDelivery(test);
        delivery.setRecipient(recipient);
        delivery.setTransportType(channel.getType());
        delivery.setPayload(event);
        delivery.setDispatchToken(UUID.randomUUID());
        delivery.setPublishAfter(Instant.now());
        deliveries.save(delivery);
    }
}
