package uk.appsthatmatter.koonj.worker;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.notification.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static uk.appsthatmatter.koonj.notification.NotificationDelivery.Status.*;

@Service
class NotificationConsumer {
    static final String EMAIL_LISTENER = "koonj-notification-email";
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final NotificationDeliveryRepository deliveries;
    private final NotificationChannelRepository channels;
    private final UserNotificationPreferenceRepository preferences;
    private final NotificationTransport transport;
    private final TransactionTemplate transaction;
    private final NotificationRetry retry;

    NotificationConsumer(NotificationDeliveryRepository deliveries, NotificationChannelRepository channels,
                         UserNotificationPreferenceRepository preferences, NotificationTransport transport,
                         TransactionTemplate transaction, NotificationRetry retry) {
        this.deliveries = deliveries;
        this.channels = channels;
        this.preferences = preferences;
        this.transport = transport;
        this.transaction = transaction;
        this.retry = retry;
    }

    @RabbitListener(id = EMAIL_LISTENER, queues = NotificationQueues.EMAIL, concurrency = "${koonj.worker.email-concurrency}")
    void email(Message message) {
        consume(message, true);
    }

    @RabbitListener(queues = NotificationQueues.WEBHOOK, concurrency = "${koonj.worker.webhook-concurrency}")
    void webhook(Message message) {
        consume(message, false);
    }

    private void consume(Message message, boolean email) {
        var reference = DeliveryReference.parse(message);
        try {
            process(reference, email);
        } catch (AmqpRejectAndDontRequeueException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            // Keep broker messages available during database or transaction failures.
            try {
                Thread.sleep(Duration.ofSeconds(1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new ImmediateRequeueAmqpException("Notification state could not be processed", failure);
        }
    }

    private void process(DeliveryReference reference, boolean email) {
        var claimed = transaction.execute(status -> claim(reference, email));
        if (claimed == null) return;

        var outcome = deliver(claimed);
        transaction.executeWithoutResult(status -> deliveries.lock(claimed.getId()).ifPresent(current -> {
            if (claimed.getLeaseToken().equals(current.getLeaseToken())) {
                retry.finish(current, outcome.status(), outcome.attempts(), outcome.error(), outcome.retryable());
            }
        }));
    }

    private NotificationDelivery claim(DeliveryReference reference, boolean email) {
        var delivery = deliveries.lock(reference.id()).orElse(null);
        if (delivery == null || delivery.getStatus() != PENDING || !reference.token().equals(delivery.getDispatchToken())) return null;
        if ((delivery.getTransportType() == NotificationChannel.Type.EMAIL) != email) {
            throw new AmqpRejectAndDontRequeueException("Notification was routed to the wrong queue");
        }
        if (delivery.getLeaseToken() != null) return null;
        Instant until = Instant.now().plus(LEASE);
        delivery.setLeaseToken(UUID.randomUUID());
        delivery.setPublishAfter(until);
        return delivery;
    }

    private Outcome deliver(NotificationDelivery delivery) {
        var event = delivery.getPayload();
        var channel = channels.findById(delivery.getChannelId()).orElse(null);
        if (channel == null || (!delivery.isTestDelivery() && !channel.isEnabled())) {
            return new Outcome(FAILED, 0, "Channel was disabled or removed", false);
        }
        if (channel.getType() != NotificationChannel.Type.EMAIL) {
            return Outcome.from(transport.post(channel, event, delivery));
        }
        String recipient = delivery.getRecipient();
        if (recipient == null) {
            return new Outcome(FAILED, 0, event.system() ? "No admin recipients configured" : "The job owner has no verified email address", false);
        }
        boolean actorRecipient = !event.system() && event.actorId() != null && Objects.equals(event.actorEmail(), recipient);
        var accounts = actorRecipient ? preferences.findById(event.actorId()).stream().toList()
                : preferences.findByVerifiedEmail(recipient.toLowerCase(Locale.ROOT));
        Instant now = Instant.now();
        if (accounts.stream().anyMatch(account -> account.suppresses(event.type(), now))) {
            return new Outcome(SUPPRESSED_OPT_OUT, 0, null, false);
        }
        return Outcome.from(transport.mail(channel, event, recipient));
    }

    private record DeliveryReference(UUID id, UUID token) {
        static DeliveryReference parse(Message message) {
            String body = new String(message.getBody(), StandardCharsets.US_ASCII);
            if (body.length() != 73 || body.charAt(36) != ':') {
                throw new AmqpRejectAndDontRequeueException("Invalid notification message");
            }
            try {
                return new DeliveryReference(UUID.fromString(body.substring(0, 36)), UUID.fromString(body.substring(37)));
            } catch (IllegalArgumentException failure) {
                throw new AmqpRejectAndDontRequeueException("Invalid notification message", failure);
            }
        }
    }

    private record Outcome(NotificationDelivery.Status status, int attempts, String error, boolean retryable) {
        static Outcome from(NotificationTransport.Result result) {
            return new Outcome(result.error() == null ? SENT : FAILED, result.attempts(), result.error(), result.retryable());
        }
    }
}
