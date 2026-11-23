package uk.appsthatmatter.koonj.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.notification.NotificationChannel;
import uk.appsthatmatter.koonj.notification.NotificationDelivery;
import uk.appsthatmatter.koonj.notification.NotificationDeliveryRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
class NotificationRelay {
    private static final Logger log = LoggerFactory.getLogger(NotificationRelay.class);
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PUBLISH_LEASE = Duration.ofSeconds(30);
    private final NotificationDeliveryRepository deliveries;
    private final RabbitTemplate broker;
    private final TransactionTemplate transaction;
    private final NotificationRetry retry;

    NotificationRelay(NotificationDeliveryRepository deliveries, RabbitTemplate broker, TransactionTemplate transaction, NotificationRetry retry) {
        this.deliveries = deliveries;
        this.broker = broker;
        this.transaction = transaction;
        this.retry = retry;
    }

    @Scheduled(fixedDelayString = "${koonj.worker.poll-interval}")
    void publishReady() {
        for (int i = 0; i < 25; i++) {
            Envelope envelope = transaction.execute(status -> reserve());
            if (envelope == null || !publish(envelope)) return;
        }
    }

    private Envelope reserve() {
        Instant now = Instant.now();
        List<NotificationDelivery> claimed = deliveries.claimPublishable(now, 1);
        if (claimed.isEmpty()) return null;
        var delivery = claimed.getFirst();
        if (delivery.getLeaseToken() != null) {
            retry.recoverInterrupted(delivery);
            return null;
        }
        delivery.setPublishAfter(now.plus(PUBLISH_LEASE));
        String queue = delivery.getTransportType() == NotificationChannel.Type.EMAIL ? NotificationQueues.EMAIL : NotificationQueues.WEBHOOK;
        return new Envelope(delivery.getId(), delivery.getDispatchToken(), queue);
    }

    private boolean publish(Envelope envelope) {
        String body = envelope.id() + ":" + envelope.token();
        var message = MessageBuilder.withBody(body.getBytes(StandardCharsets.US_ASCII))
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT).setMessageId(body).build();
        var correlation = new CorrelationData();
        try {
            broker.send(NotificationQueues.EXCHANGE, envelope.queue(), message, correlation);
            var confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                log.warn("RabbitMQ did not accept notification {}", envelope.id());
                return false;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            log.warn("Could not publish notification {} ({})", envelope.id(), failure.getClass().getSimpleName());
            return false;
        }
        transaction.executeWithoutResult(status -> deliveries.lock(envelope.id()).ifPresent(delivery -> {
            if (envelope.token().equals(delivery.getDispatchToken()) && delivery.getLeaseToken() == null) {
                delivery.setPublishAfter(null);
            }
        }));
        return true;
    }

    @Scheduled(fixedDelayString = "15m", initialDelayString = "1m")
    void expireLegacyDeliveries() {
        deliveries.failLegacyPending(Instant.now().minus(Duration.ofHours(1)));
    }

    private record Envelope(UUID id, UUID token, String queue) {}
}
