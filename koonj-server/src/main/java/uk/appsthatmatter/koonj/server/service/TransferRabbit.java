package uk.appsthatmatter.koonj.server.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;

import static uk.appsthatmatter.koonj.server.entity.TransferJob.Status.PENDING;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "koonj.transfer.rabbit.enabled", havingValue = "true")
class TransferRabbit {
    static final String EXCHANGE = "koonj.transfers.v1";

    @Bean
    DirectExchange transferExchange() { return new DirectExchange(EXCHANGE); }

    static String route(String pool, boolean interactive) {
        return EXCHANGE + "." + pool + (interactive ? ".interactive" : ".background");
    }
}

@Component
@ConditionalOnProperty(name = "koonj.transfer.rabbit.enabled", havingValue = "true")
final class TransferRelay {
    private static final Logger LOG = LoggerFactory.getLogger(TransferRelay.class);
    private final TransferDispatch dispatch;
    private final RabbitTemplate rabbit;
    private final boolean enabled;

    TransferRelay(TransferDispatch dispatch, RabbitTemplate rabbit, TransferJobRepository jobs, MeterRegistry meters,
                  @Value("${koonj.transfer.rabbit.relay-enabled:true}") boolean enabled) {
        this.dispatch = dispatch;
        this.rabbit = rabbit;
        this.enabled = enabled;
        meters.gauge("koonj.transfer.attempts.stalled", jobs, repo -> repo.countStalled(Instant.now().minusSeconds(90)));
        meters.gauge("koonj.transfer.jobs.pending", jobs, repo -> repo.countByStatusAndDispatchGenerationIsNotNull(PENDING));
        meters.gauge("koonj.transfer.jobs.oldest.pending.seconds", jobs, repo -> {
            Instant oldest = repo.oldestWaitingTransfer();
            return oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
        });
    }

    @Scheduled(fixedDelayString = "${koonj.transfer.rabbit.relay-interval:500ms}")
    void publish() {
        if (!enabled) return;
        for (var id : dispatch.publishable()) {
            var envelope = dispatch.reservePublication(id);
            if (envelope == null) continue;
            String body = envelope.job() + ":" + envelope.generation();
            var message = MessageBuilder.withBody(body.getBytes(StandardCharsets.US_ASCII)).setMessageId(body)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
            var correlation = new CorrelationData();
            String route = TransferRabbit.route(envelope.pool(), envelope.interactive());
            try {
                rabbit.send(TransferRabbit.EXCHANGE, route, message, correlation);
                var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (confirm.isAck() && correlation.getReturned() == null) {
                    dispatch.published(envelope);
                } else {
                    LOG.warn("RabbitMQ did not route transfer publication {} to {}", id, route);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                LOG.warn("Could not publish transfer {}", id, failure);
                return;
            }
        }
    }
}
