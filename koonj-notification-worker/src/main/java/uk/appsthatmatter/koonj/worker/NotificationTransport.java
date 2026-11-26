package uk.appsthatmatter.koonj.worker;

import uk.appsthatmatter.koonj.notification.NotificationChannel;
import uk.appsthatmatter.koonj.notification.NotificationDelivery;
import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.notification.EmailAddress;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
class NotificationTransport {
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final int smtpTimeoutMillis;
    private final Smtp defaults;

    record Smtp(String host, int port, String tls, String username, String password, String from) {}
    record Result(int attempts, String error, boolean retryable) {}

    NotificationTransport(ObjectMapper json,
                          @Value("${koonj.worker.webhook.connect-timeout}") Duration connectTimeout,
                          @Value("${koonj.worker.webhook.request-timeout}") Duration requestTimeout,
                          @Value("${koonj.worker.mail.timeout}") Duration mailTimeout,
                          @Value("${koonj.worker.mail.host}") String host,
                          @Value("${koonj.worker.mail.port}") int port,
                          @Value("${koonj.worker.mail.tls}") String tls,
                          @Value("${koonj.worker.mail.username}") String username,
                          @Value("${koonj.worker.mail.password}") String password,
                          @Value("${koonj.worker.mail.from}") String from) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        this.requestTimeout = requestTimeout;
        this.smtpTimeoutMillis = Math.toIntExact(mailTimeout.toMillis());
        if (smtpTimeoutMillis <= 0 || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Notification timeouts must be positive");
        }
        this.defaults = new Smtp(host, port, tls.toUpperCase(Locale.ROOT), username, password, from);
    }

    Result post(NotificationChannel channel, NotificationEvent event, NotificationDelivery delivery) {
        HttpRequest request;
        try {
            byte[] body = json.writeValueAsBytes(payload(channel.getType(), event, delivery));
            var config = channel.getConfig();
            var builder = HttpRequest.newBuilder(URI.create(config.endpoint())).timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (config.headers() != null) config.headers().forEach(builder::header);
            if (config.signingSecret() != null && !config.signingSecret().isBlank()) {
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(config.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                builder.header("X-Koonj-Signature", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)));
            }
            request = builder.build();
        } catch (Exception failure) {
            return new Result(0, "Could not prepare the webhook request", false);
        }

        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();
            int code = response.statusCode();
            if (code >= 200 && code < 300) return new Result(1, null, false);
            return new Result(1, "Webhook returned HTTP " + code, code >= 500 && code <= 599);
        } catch (IOException failure) {
            return new Result(1, failure instanceof java.net.http.HttpTimeoutException
                    ? "Webhook request timed out" : "Webhook connection failed", true);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(1, "Webhook request interrupted", true);
        }
    }

    private Object payload(NotificationChannel.Type type, NotificationEvent event, NotificationDelivery delivery) {
        return switch (type) {
            case WEBHOOK -> Map.of("eventType", event.type(), "deliveryId", delivery.getId(), "test", delivery.isTestDelivery(),
                    "timestamp", delivery.getCreatedAt(), "title", event.title(), "details", event.details());
            case SLACK -> Map.of("text", event.title(), "blocks", List.of(
                    Map.of("type", "header", "text", Map.of("type", "plain_text", "text", event.title())),
                    Map.of("type", "section", "text", Map.of("type", "plain_text", "text", clip(event.detailText(), 2_900)))));
            case TEAMS -> Map.of("type", "message", "attachments", List.of(Map.of(
                    "contentType", "application/vnd.microsoft.card.adaptive", "content", Map.of(
                            "$schema", "http://adaptivecards.io/schemas/adaptive-card.json", "type", "AdaptiveCard", "version", "1.2",
                            "body", List.of(Map.of("type", "TextBlock", "weight", "Bolder", "text", event.title(), "wrap", true),
                                    Map.of("type", "TextBlock", "text", clip(event.detailText(), 4_000), "wrap", true))))));
            case EMAIL -> throw new IllegalArgumentException("Email uses SMTP");
        };
    }

    Result mail(NotificationChannel channel, NotificationEvent event, String recipient) {
        var config = channel.getConfig();
        return mail(smtp(config), recipient, render(config.subjectTemplate(), "{title}", event),
                render(config.bodyTemplate(), "{summary}", event));
    }

    Result invitation(NotificationChannel channel, String recipient, String body) {
        return mail(smtp(channel.getConfig()), recipient, "Invitation to join a Koonj project", body);
    }

    private Result mail(Smtp smtp, String recipient, String subject, String body) {
        if (smtp.host().isBlank() || !EmailAddress.valid(smtp.from())) return new Result(0, "SMTP host and sender are required", false);
        if (!List.of("NONE", "STARTTLS", "SSL").contains(smtp.tls())) return new Result(0, "Invalid SMTP TLS mode", false);
        var sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", String.valueOf(smtpTimeoutMillis));
        properties.setProperty("mail.smtp.timeout", String.valueOf(smtpTimeoutMillis));
        properties.setProperty("mail.smtp.writetimeout", String.valueOf(smtpTimeoutMillis));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        if ("STARTTLS".equals(smtp.tls())) {
            properties.setProperty("mail.smtp.starttls.enable", "true");
            properties.setProperty("mail.smtp.starttls.required", "true");
        }
        if ("SSL".equals(smtp.tls())) properties.setProperty("mail.smtp.ssl.enable", "true");
        if (!smtp.username().isBlank()) {
            properties.setProperty("mail.smtp.auth", "true");
            sender.setUsername(smtp.username());
            sender.setPassword(smtp.password());
        }

        var message = new SimpleMailMessage();
        message.setFrom(smtp.from());
        message.setTo(recipient);
        message.setSubject(subject.replaceAll("[\\r\\n]", " "));
        message.setText(body);
        try {
            sender.send(message);
            return new Result(1, null, false);
        } catch (RuntimeException failure) {
            return smtpFailure(failure);
        }
    }

    static Result smtpFailure(RuntimeException failure) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(failure);
        boolean temporaryNetworkFailure = false;
        Integer replyCode = null;
        while (!pending.isEmpty()) {
            Throwable cause = pending.remove();
            if (!seen.add(cause)) continue;
            if (cause instanceof SMTPAddressFailedException address) replyCode = address.getReturnCode();
            if (cause instanceof SMTPSendFailedException send && replyCode == null) replyCode = send.getReturnCode();
            if (cause instanceof SocketException || cause instanceof SocketTimeoutException || cause instanceof UnknownHostException) {
                temporaryNetworkFailure = true;
            }
            if (cause.getCause() != null) pending.add(cause.getCause());
            if (cause instanceof MessagingException mail && mail.getNextException() != null) pending.add(mail.getNextException());
            if (cause instanceof MailSendException mail) Collections.addAll(pending, mail.getMessageExceptions());
        }
        if (replyCode != null && replyCode > 0) {
            return new Result(1, "SMTP returned " + replyCode, replyCode >= 400 && replyCode < 500);
        }
        return new Result(1, temporaryNetworkFailure ? "SMTP connection failed or timed out"
                : "SMTP delivery failed (" + failure.getClass().getSimpleName() + ")", temporaryNetworkFailure);
    }

    private Smtp smtp(NotificationChannel.Config config) {
        boolean hostOverride = config.smtpHost() != null && !config.smtpHost().isBlank();
        return new Smtp(orDefault(config.smtpHost(), defaults.host()),
                config.smtpPort() == null ? defaults.port() : config.smtpPort(),
                orDefault(config.smtpTls(), defaults.tls()),
                orDefault(config.smtpUsername(), hostOverride ? "" : defaults.username()),
                orDefault(config.smtpPassword(), hostOverride || config.smtpUsername() != null ? "" : defaults.password()),
                orDefault(config.from(), defaults.from()));
    }

    private static String render(String template, String fallback, NotificationEvent event) {
        return orDefault(template, fallback).replace("{title}", event.title())
                .replace("{eventType}", event.type().name()).replace("{summary}", event.summary());
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clip(String text, int size) {
        return text.length() <= size ? text : text.substring(0, size - 1) + "…";
    }
}
