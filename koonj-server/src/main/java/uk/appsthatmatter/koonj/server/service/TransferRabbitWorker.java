package uk.appsthatmatter.koonj.server.service;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${koonj.transfer.rabbit.enabled:false} && ${koonj.transfer.rabbit.worker-enabled:true}")
class TransferRabbitWorkerTopology {
    @Bean
    Declarables transferWorkerQueues(DirectExchange transferExchange,
            @Value("${koonj.transfer.rabbit.worker-pool:default}") String pool) {
        if (!pool.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Invalid transfer worker pool");
        String deadRoute = TransferRabbit.EXCHANGE + "." + pool + ".invalid";
        var dead = QueueBuilder.durable(deadRoute).quorum().build();
        var background = queue(pool, false, deadRoute);
        var interactive = queue(pool, true, deadRoute);
        return new Declarables(background, interactive, dead,
                BindingBuilder.bind(background).to(transferExchange).with(background.getName()),
                BindingBuilder.bind(interactive).to(transferExchange).with(interactive.getName()),
                BindingBuilder.bind(dead).to(transferExchange).with(deadRoute));
    }

    private Queue queue(String pool, boolean interactive, String deadRoute) {
        return QueueBuilder.durable(TransferRabbit.route(pool, interactive)).quorum()
                .deadLetterExchange(TransferRabbit.EXCHANGE).deadLetterRoutingKey(deadRoute)
                .withArgument("x-delivery-limit", -1).build();
    }

    @Bean
    SimpleRabbitListenerContainerFactory transferListenerFactory(ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        return factory;
    }
}

@Component
@ConditionalOnExpression("${koonj.transfer.rabbit.enabled:false} && ${koonj.transfer.rabbit.worker-enabled:true}")
final class TransferRabbitWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TransferRabbitWorker.class);
    private final UUID instance = UUID.randomUUID();
    private final ConcurrentHashMap<UUID, Activity> active = new ConcurrentHashMap<>();
    private final TransferDispatch dispatch;
    private final JobService jobs;
    private final TransferJobRepository repository;
    private final FileTransferService transfers;
    private final CatalogScanService catalog;
    private final TransactionTemplate tx;
    private final String pool;

    TransferRabbitWorker(TransferDispatch dispatch, JobService jobs, TransferJobRepository repository,
                         FileTransferService transfers, CatalogScanService catalog, TransactionTemplate tx,
                         @Value("${koonj.transfer.rabbit.worker-pool:default}") String pool) {
        this.dispatch = dispatch;
        this.jobs = jobs;
        this.repository = repository;
        this.transfers = transfers;
        this.catalog = catalog;
        this.tx = tx;
        this.pool = pool;
        LOG.info("Transfer worker {} started for pool {}", instance, pool);
    }

    @RabbitListener(queues = "koonj.transfers.v1.${koonj.transfer.rabbit.worker-pool:default}.background",
            containerFactory = "transferListenerFactory", concurrency = "${koonj.transfer.rabbit.background-workers:2}")
    void background(Message message, Channel channel) throws IOException {
        consume(message, channel, false);
    }

    @RabbitListener(queues = "koonj.transfers.v1.${koonj.transfer.rabbit.worker-pool:default}.interactive",
            containerFactory = "transferListenerFactory", concurrency = "${koonj.transfer.rabbit.interactive-workers:1}")
    void interactive(Message message, Channel channel) throws IOException {
        consume(message, channel, true);
    }

    private void consume(Message message, Channel channel, boolean interactive) throws IOException {
        var tag = message.getMessageProperties().getDeliveryTag();
        var fields = new String(message.getBody(), StandardCharsets.US_ASCII).split(":", -1);
        UUID id;
        UUID generation;
        try {
            if (fields.length != 2) throw new IllegalArgumentException("Expected job and generation");
            id = UUID.fromString(fields[0]);
            generation = UUID.fromString(fields[1]);
        } catch (IllegalArgumentException invalid) {
            throw new AmqpRejectAndDontRequeueException("Invalid transfer envelope", true, invalid);
        }

        TransferJob job;
        try {
            job = dispatch.claim(id, generation, pool, interactive, instance);
        } catch (RuntimeException failure) {
            LOG.warn("Transfer claim failed for {}", id, failure);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            channel.basicNack(tag, false, true);
            return;
        }

        if (job == null) {
            channel.basicAck(tag, false);
            return;
        }
        active.put(job.getWorkerAttempt(), new Activity(job));
        try {
            // Keep this consumer's prefetch slot occupied until it can accept another job.
            ProjectScope.run(job.getProjectId(), () -> jobs.runClaimed(job,
                    job.getOperation() == TransferJob.Operation.SCAN ? catalog::scan : transfers::transfer));
        } catch (RuntimeException failure) {
            LOG.error("Transfer {} could not finalize attempt {}. Its reservations remain held.",
                    id, job.getWorkerAttempt(), failure);
        } finally {
            active.remove(job.getWorkerAttempt());
            try {
                channel.basicAck(tag, false);
            } catch (IOException | com.rabbitmq.client.AlreadyClosedException failure) {
                LOG.warn("RabbitMQ could not acknowledge transfer {}. Its durable claim prevents duplicate execution.", id, failure);
            }
        }
    }

    @Scheduled(fixedDelayString = "10s")
    void heartbeat() {
        var attempts = List.copyOf(active.keySet());
        if (!attempts.isEmpty()) tx.executeWithoutResult(status -> repository.heartbeat(instance, attempts, Instant.now()));
    }

    @Scheduled(fixedDelayString = "1s")
    void publishActiveFiles() {
        for (Activity activity : active.values()) {
            var paths = jobs.activePaths(activity.job.getId());
            if (paths.equals(activity.publishedPaths)) continue;
            jobs.publishActivePaths(activity.job, paths);
            activity.publishedPaths = paths;
        }
    }

    private static final class Activity {
        final TransferJob job;
        List<String> publishedPaths = List.of();

        Activity(TransferJob job) { this.job = job; }
    }

}
