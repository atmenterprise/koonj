package uk.appsthatmatter.koonj.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import uk.appsthatmatter.koonj.notification.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static uk.appsthatmatter.koonj.notification.NotificationDelivery.Status.*;
import static uk.appsthatmatter.koonj.notification.NotificationEvent.Type.LARGE_BATCH_COMPLETED;

@EnabledIfEnvironmentVariable(named = "KOONJ_TEST_RABBITMQ", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=${KOONJ_TEST_DB_URL:jdbc:h2:mem:notification-worker;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${KOONJ_TEST_DB_USER:sa}", "spring.datasource.password=${KOONJ_TEST_DB_PASSWORD:}",
        "spring.jpa.hibernate.ddl-auto=${KOONJ_TEST_DDL_AUTO:create-drop}",
        "koonj.worker.poll-interval=50ms", "koonj.worker.mail.retry-delay=2s", "koonj.worker.webhook.retry-delay=10ms"
})
class NotificationWorkerIntegrationTest {
    @Autowired NotificationOutboxRepository outbox;
    @Autowired NotificationChannelRepository channels;
    @Autowired NotificationRuleRepository rules;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired UserNotificationPreferenceRepository preferences;
    @Autowired RecordingTransport transport;
    @Autowired RabbitTemplate broker;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ObjectMapper json;

    @Test
    void emailRetriesThroughRabbitAndHonorsPreferenceChanges() {
        String actor = UUID.randomUUID().toString();
        String address = actor + "@example.test";
        var channel = channel(NotificationChannel.Type.EMAIL, null);
        var rule = rule(channel);
        try {
            String key = enqueue(actor, address);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivery(key)).satisfies(row -> {
                assertThat(row.getStatus()).isEqualTo(PENDING);
                assertThat(row.getAttemptCount()).isEqualTo(1);
                assertThat(row.getErrorMessage()).isEqualTo("SMTP returned 451");
            }));
            var preference = new UserNotificationPreference();
            preference.setUserId(actor);
            preference.setSilencedUntil(Instant.now().plusSeconds(120));
            preferences.saveAndFlush(preference);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivery(key)).satisfies(row -> {
                assertThat(row.getStatus()).isEqualTo(SUPPRESSED_OPT_OUT);
                assertThat(row.getAttemptCount()).isEqualTo(1);
                assertThat(row.getPayload()).isNull();
            }));
            assertThat(transport.mail.stream().filter(address::equals).count()).isEqualTo(1);
            assertThat(transport.mail).doesNotContain("admin@example.test");
        } finally {
            rules.delete(rule);
            channels.delete(channel);
        }
    }

    @Test
    void rabbitKeepsEmailAvailableWhileConsumersAreStopped() throws Exception {
        try (var smtp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var received = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> acceptMail(smtp, received));
            var channel = channel(NotificationChannel.Type.EMAIL, null);
            channel.setConfig(new NotificationChannel.Config(null, null, null, List.of(), "127.0.0.1",
                    smtp.getLocalPort(), "NONE", null, null, "sender@example.test", null, null));
            channels.saveAndFlush(channel);
            var rule = rule(channel);
            var listener = listeners.getListenerContainer(NotificationConsumer.EMAIL_LISTENER);
            String address = "durable-" + UUID.randomUUID() + "@example.test";
            try {
                listener.stop();
                String key = enqueue("durable-actor", address);
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(delivery(key).getStatus()).isEqualTo(PENDING);
                    int waiting = broker.execute(client -> client.queueDeclarePassive(NotificationQueues.EMAIL).getMessageCount());
                    assertThat(waiting).isGreaterThanOrEqualTo(1);
                });
                assertThat(transport.mail).doesNotContain(address);

                listener.start();
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivery(key)).satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo(SENT);
                    assertThat(row.getAttemptCount()).isEqualTo(1);
                }));
                assertThat(transport.mail).containsOnlyOnce(address);
                assertThat(received.get(5, TimeUnit.SECONDS)).contains("To: " + address, "Test completed");
            } finally {
                if (!listener.isRunning()) listener.start();
                rules.delete(rule);
                channels.delete(channel);
            }
        }
    }

    private static void acceptMail(ServerSocket server, CompletableFuture<String> received) {
        try (var socket = server.accept();
             var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             var output = new PrintWriter(socket.getOutputStream())) {
            reply(output, "220 fixture SMTP");
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("DATA")) {
                    reply(output, "354 End data with a dot");
                    var body = new StringBuilder();
                    for (String data; (data = input.readLine()) != null && !data.equals(".");) body.append(data).append('\n');
                    received.complete(body.toString());
                    reply(output, "250 queued");
                } else if (line.equals("QUIT")) {
                    reply(output, "221 closing");
                    return;
                } else {
                    reply(output, "250 OK");
                }
            }
        } catch (Exception failure) {
            received.completeExceptionally(failure);
        }
    }

    private static void reply(PrintWriter output, String line) {
        output.print(line + "\r\n");
        output.flush();
    }

    @Test
    void webhookRetriesWithStableDeliveryIdAndCompletedMessagesAreIgnored() throws Exception {
        var requests = new ConcurrentLinkedQueue<byte[]>();
        var callCount = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            http.createContext("/worker-test", exchange -> {
                try (exchange) {
                    requests.add(exchange.getRequestBody().readAllBytes());
                    assertThat(exchange.getRequestHeaders().getFirst("X-Koonj-Signature")).startsWith("sha256=");
                    exchange.sendResponseHeaders(callCount.incrementAndGet() == 1 ? 503 : 204, -1);
                }
            });
            http.start();
            var channel = channel(NotificationChannel.Type.WEBHOOK, "http://127.0.0.1:" + http.getAddress().getPort() + "/worker-test");
            var rule = rule(channel);
            try {
                String key = enqueue("actor", "owner@example.test");
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(delivery(key)).satisfies(row -> {
                    assertThat(row.getStatus()).isEqualTo(SENT);
                    assertThat(row.getAttemptCount()).isEqualTo(2);
                }));
                var sent = delivery(key);
                for (byte[] body : requests) assertThat(json.readTree(body).get("deliveryId").asText()).isEqualTo(sent.getId().toString());
                var duplicate = MessageBuilder.withBody((sent.getId() + ":" + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII))
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                broker.send(NotificationQueues.EXCHANGE, NotificationQueues.WEBHOOK, duplicate);
                await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                        assertThat(callCount.get()).isEqualTo(2));
            } finally {
                rules.delete(rule);
                channels.delete(channel);
            }
        } finally {
            http.stop(0);
        }
    }

    private NotificationChannel channel(NotificationChannel.Type type, String endpoint) {
        var channel = new NotificationChannel();
        channel.setType(type);
        channel.setName(type.name());
        channel.setEnabled(true);
        channel.setConfig(new NotificationChannel.Config(endpoint, Map.of(), "test-key", List.of("admin@example.test"),
                null, null, null, null, null, null, null, null));
        return channels.saveAndFlush(channel);
    }

    private NotificationRule rule(NotificationChannel channel) {
        var rule = new NotificationRule();
        rule.setEventType(LARGE_BATCH_COMPLETED);
        rule.setThresholdConfig(new NotificationRule.Thresholds(1L, null, null, null, null, null));
        rule.setChannelIds(List.of(channel.getId()));
        rule.setEnabled(true);
        return rules.saveAndFlush(rule);
    }

    private String enqueue(String actor, String address) {
        String key = UUID.randomUUID().toString();
        var row = new NotificationOutbox();
        row.setEvent(new NotificationEvent(LARGE_BATCH_COMPLETED, key, actor, address,
                "Test completed", Map.of("fileCount", 1, "totalBytes", 10)));
        outbox.saveAndFlush(row);
        return key;
    }

    private NotificationDelivery delivery(String key) {
        var match = deliveries.findAll().stream().filter(row -> key.equals(row.getDedupeKey())).findFirst();
        assertThat(match).as("delivery for %s", key).isPresent();
        return match.orElseThrow();
    }

    @TestConfiguration
    static class Fixture {
        @Bean @Primary
        RecordingTransport recordingTransport(ObjectMapper json) {
            return new RecordingTransport(json);
        }
    }

    static class RecordingTransport extends NotificationTransport {
        final ConcurrentLinkedQueue<String> mail = new ConcurrentLinkedQueue<>();
        RecordingTransport(ObjectMapper json) {
            super(json, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(2),
                    "", 587, "STARTTLS", "", "", "");
        }

        @Override
        Result mail(NotificationChannel channel, NotificationEvent event, String recipient) {
            mail.add(recipient);
            return recipient.startsWith("durable-") ? super.mail(channel, event, recipient)
                    : new Result(1, "SMTP returned 451", true);
        }
    }
}
