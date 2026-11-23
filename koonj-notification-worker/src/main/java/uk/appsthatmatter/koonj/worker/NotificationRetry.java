package uk.appsthatmatter.koonj.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.notification.NotificationChannel;
import uk.appsthatmatter.koonj.notification.NotificationDelivery;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static uk.appsthatmatter.koonj.notification.NotificationDelivery.Status.FAILED;

@Component
class NotificationRetry {
    private final int mailAttempts;
    private final int webhookAttempts;
    private final Duration mailDelay;
    private final Duration webhookDelay;

    NotificationRetry(@Value("${koonj.worker.mail.max-attempts}") int mailAttempts,
                      @Value("${koonj.worker.webhook.max-attempts}") int webhookAttempts,
                      @Value("${koonj.worker.mail.retry-delay}") Duration mailDelay,
                      @Value("${koonj.worker.webhook.retry-delay}") Duration webhookDelay) {
        this.mailAttempts = Math.clamp(mailAttempts, 1, 10);
        this.webhookAttempts = Math.clamp(webhookAttempts, 1, 10);
        if (mailDelay.isNegative() || webhookDelay.isNegative()) {
            throw new IllegalArgumentException("Notification retry delays must be non-negative");
        }
        this.mailDelay = mailDelay;
        this.webhookDelay = webhookDelay;
    }

    void finish(NotificationDelivery delivery, NotificationDelivery.Status status, int attempts, String error, boolean retryable) {
        int totalAttempts = delivery.getAttemptCount() + attempts;
        delivery.setAttemptCount(totalAttempts);
        delivery.setErrorMessage(error);
        delivery.setLeaseToken(null);
        boolean email = delivery.getTransportType() == NotificationChannel.Type.EMAIL;
        if (retryable && totalAttempts < (email ? mailAttempts : webhookAttempts)) {
            Duration base = email ? mailDelay : webhookDelay;
            long delay = Math.min(900_000, Math.min(base.toMillis(), 900_000) * (1L << Math.max(0, totalAttempts - 1)));
            long jitter = ThreadLocalRandom.current().nextLong(delay / 4 + 1);
            delivery.setDispatchToken(UUID.randomUUID());
            delivery.setPublishAfter(Instant.now().plusMillis(delay + jitter));
            return;
        }
        delivery.setStatus(status);
        delivery.setDispatchToken(null);
        delivery.setPublishAfter(null);
        delivery.setPayload(null);
    }

    void recoverInterrupted(NotificationDelivery delivery) {
        finish(delivery, FAILED, 1, "Delivery attempt interrupted before its outcome was recorded", true);
    }
}
