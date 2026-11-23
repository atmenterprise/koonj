package uk.appsthatmatter.koonj.server.notification;

import uk.appsthatmatter.koonj.notification.EmailAddress;
import uk.appsthatmatter.koonj.notification.NotificationChannel;
import uk.appsthatmatter.koonj.notification.NotificationChannelRepository;
import uk.appsthatmatter.koonj.notification.NotificationDelivery;
import uk.appsthatmatter.koonj.notification.NotificationDeliveryRepository;
import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.notification.NotificationRule;
import uk.appsthatmatter.koonj.notification.NotificationRuleRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.dto.PageResponse;
import uk.appsthatmatter.koonj.server.security.JobActor;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasRole('KOONJ_ADMIN')")
class NotificationAdminController {
    private final NotificationChannelRepository channels;
    private final NotificationRuleRepository rules;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationOutboxWriter outbox;

    NotificationAdminController(NotificationChannelRepository channels, NotificationRuleRepository rules,
                                NotificationDeliveryRepository deliveries, NotificationOutboxWriter outbox) {
        this.channels = channels;
        this.rules = rules;
        this.deliveries = deliveries;
        this.outbox = outbox;
    }

    record ChannelSettings(@NotBlank @Size(max = 120) String name, @NotNull NotificationChannel.Type type,
            boolean enabled, @Size(max = 4096) String endpoint, @Size(max = 20) Map<String, String> headers,
            @Size(max = 4096) String signingSecret, @Size(max = 50) List<String> adminRecipients,
            @Size(max = 253) String smtpHost, @Min(1) @Max(65535) Integer smtpPort,
            @Pattern(regexp = "NONE|STARTTLS|SSL") String smtpTls, @Size(max = 320) String smtpUsername,
            @Size(max = 4096) String smtpPassword, @Size(max = 320) String from,
            @Size(max = 250) String subjectTemplate, @Size(max = 10000) String bodyTemplate) {}

    record ChannelView(UUID id, String name, NotificationChannel.Type type, boolean enabled,
            boolean hasEndpoint, boolean hasHeaders, boolean hasSigningSecret, List<String> adminRecipients,
            String smtpHost, Integer smtpPort, String smtpTls, String smtpUsername, boolean hasSmtpPassword,
            String from, String subjectTemplate, String bodyTemplate, Instant createdAt, Instant updatedAt) {
        static ChannelView from(NotificationChannel channel) {
            var config = channel.getConfig();
            return new ChannelView(channel.getId(), channel.getName(), channel.getType(), channel.isEnabled(),
                    configured(config.endpoint()), config.headers() != null && !config.headers().isEmpty(),
                    configured(config.signingSecret()), config.adminRecipients(), config.smtpHost(), config.smtpPort(),
                    config.smtpTls(), config.smtpUsername(), configured(config.smtpPassword()), config.from(),
                    config.subjectTemplate(), config.bodyTemplate(), channel.getCreatedAt(), channel.getUpdatedAt());
        }
    }

    record ThresholdSettings(@Positive Long fileCount, @Positive Long bytes, @Positive Integer failureCount,
            @Min(1) @Max(100) Integer warningPercent, @Min(0) @Max(3650) Integer expiryWarningDays,
            @Min(1) @Max(43200) Integer cooldownMinutes) {
        NotificationRule.Thresholds values() {
            return new NotificationRule.Thresholds(fileCount, bytes, failureCount, warningPercent, expiryWarningDays, cooldownMinutes);
        }
    }

    record RuleSettings(@NotNull NotificationEvent.Type eventType, @NotNull @Valid ThresholdSettings thresholdConfig,
                        @NotEmpty @Size(max = 50) List<@NotNull UUID> channelIds, boolean enabled) {}

    record DeliveryView(UUID id, NotificationEvent.Type eventType, UUID channelId, String recipient,
                        NotificationDelivery.Status status, int attemptCount, String errorMessage, boolean testDelivery,
                        Instant createdAt) {
        static DeliveryView from(NotificationDelivery entry) {
            return new DeliveryView(entry.getId(), entry.getEventType(), entry.getChannelId(), entry.getRecipient(),
                    entry.getStatus(), entry.getAttemptCount(), entry.getErrorMessage(), entry.isTestDelivery(), entry.getCreatedAt());
        }
    }

    @GetMapping("/channels")
    public List<ChannelView> channels() {
        return channels.findAll(Sort.by("name", "id")).stream().map(ChannelView::from).toList();
    }

    @GetMapping("/channels/{id}")
    public ChannelView channel(@PathVariable UUID id) {
        return ChannelView.from(findChannel(id));
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelView createChannel(@Valid @RequestBody ChannelSettings input) {
        return saveChannel(new NotificationChannel(), input);
    }

    @PutMapping("/channels/{id}")
    @Transactional
    public ChannelView updateChannel(@PathVariable UUID id, @Valid @RequestBody ChannelSettings input) {
        var channel = findChannel(id);
        if (channel.getType() != input.type()) throw new IllegalArgumentException("Channel type cannot change");
        return saveChannel(channel, input);
    }

    @DeleteMapping("/channels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteChannel(@PathVariable UUID id) {
        var channel = findChannel(id);
        for (var candidate : rules.findAll(Sort.by("id"))) {
            if (!candidate.getChannelIds().contains(id)) continue;
            var rule = rules.lock(candidate.getId()).orElse(null);
            if (rule == null) continue;
            var remaining = rule.getChannelIds().stream().filter(channelId -> !channelId.equals(id)).toList();
            rule.setChannelIds(remaining);
            if (remaining.isEmpty()) rule.setEnabled(false);
        }
        channels.delete(channel);
    }

    @PostMapping("/channels/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void test(@PathVariable UUID id) {
        outbox.test(findChannel(id).getId(), JobActor.subject(), JobActor.verifiedEmail());
    }

    @GetMapping("/rules")
    public List<NotificationRule> rules() {
        return rules.findAll(Sort.by("eventType", "id"));
    }

    @GetMapping("/rules/{id}")
    public NotificationRule rule(@PathVariable UUID id) {
        return rules.findById(id).orElseThrow(NotificationAdminController::missingRule);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationRule createRule(@Valid @RequestBody RuleSettings input) {
        return saveRule(new NotificationRule(), input);
    }

    @PutMapping("/rules/{id}")
    @Transactional
    public NotificationRule updateRule(@PathVariable UUID id, @Valid @RequestBody RuleSettings input) {
        return saveRule(rules.lock(id).orElseThrow(NotificationAdminController::missingRule), input);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteRule(@PathVariable UUID id) {
        rules.delete(rules.lock(id).orElseThrow(NotificationAdminController::missingRule));
    }

    @GetMapping("/deliveries")
    public PageResponse<DeliveryView> deliveries(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return PageResponse.from(deliveries.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(DeliveryView::from));
    }

    private NotificationChannel findChannel(UUID id) {
        return channels.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification channel was not found"));
    }

    private static ResponseStatusException missingRule() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification rule was not found");
    }

    private NotificationRule saveRule(NotificationRule rule, RuleSettings input) {
        var ids = input.channelIds().stream().distinct().toList();
        if (channels.findAllById(ids).size() != ids.size()) throw new IllegalArgumentException("Select existing channels");
        rule.setEventType(input.eventType());
        rule.setEnabled(input.enabled());
        rule.setThresholdConfig(input.thresholdConfig().values());
        rule.setChannelIds(ids);
        return rules.save(rule);
    }

    private ChannelView saveChannel(NotificationChannel channel, ChannelSettings input) {
        var previous = channel.getConfig();
        NotificationChannel.Config config;
        if (input.type() == NotificationChannel.Type.EMAIL) {
            var recipients = input.adminRecipients() == null ? List.<String>of()
                    : input.adminRecipients().stream().map(address -> address == null ? "" : address.strip()).distinct().toList();
            if (recipients.stream().anyMatch(address -> !EmailAddress.valid(address))
                    || (configured(input.from()) && !EmailAddress.valid(input.from()))) {
                throw new IllegalArgumentException("Enter valid single email addresses for the sender and admin recipients");
            }
            String smtpHost = configured(input.smtpHost()) ? input.smtpHost().strip() : null;
            boolean sameRelay = previous != null && Objects.equals(smtpHost, previous.smtpHost());
            config = new NotificationChannel.Config(null, null, null, recipients, smtpHost, input.smtpPort(),
                    input.smtpTls(), input.smtpUsername(),
                    input.smtpPassword() == null && sameRelay ? previous.smtpPassword() : input.smtpPassword(),
                    input.from(), input.subjectTemplate(), input.bodyTemplate());
        } else {
            var endpoint = input.endpoint() == null && previous != null ? previous.endpoint() : input.endpoint();
            URI uri = endpoint(endpoint, input.type());
            boolean sameOrigin = previous != null && sameOrigin(uri, URI.create(previous.endpoint()));
            var headers = input.headers() == null && sameOrigin ? previous.headers() : input.headers();
            var signingSecret = input.signingSecret() == null && sameOrigin ? previous.signingSecret() : input.signingSecret();
            config = new NotificationChannel.Config(uri.toString(), validateHeaders(uri, headers),
                    input.type() == NotificationChannel.Type.WEBHOOK ? signingSecret : null,
                    List.of(), null, null, null, null, null, null, null, null);
        }
        channel.setName(input.name().strip());
        channel.setType(input.type());
        channel.setEnabled(input.enabled());
        channel.setConfig(config);
        return ChannelView.from(channels.saveAndFlush(channel));
    }

    private static URI endpoint(String endpoint, NotificationChannel.Type type) {
        if (!configured(endpoint)) throw new IllegalArgumentException("Enter a webhook endpoint");
        URI uri;
        try {
            uri = URI.create(endpoint.strip());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Enter a valid webhook endpoint");
        }
        boolean allowed = "https".equalsIgnoreCase(uri.getScheme())
                || (type == NotificationChannel.Type.WEBHOOK && "http".equalsIgnoreCase(uri.getScheme()));
        if (!allowed || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use a valid webhook URL with HTTPS, or HTTP for a generic webhook");
        }
        return uri;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost())
                && port(first) == port(second);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Map<String, String> validateHeaders(URI uri, Map<String, String> headers) {
        if (headers == null) return Map.of();
        var builder = HttpRequest.newBuilder(uri);
        var validated = new LinkedHashMap<String, String>();
        headers.forEach((name, value) -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.equals("content-type") || normalized.equals("x-koonj-signature") || value == null || value.length() > 4096) {
                throw new IllegalArgumentException("Invalid or reserved webhook header");
            }
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid or reserved webhook header");
            }
            if (validated.putIfAbsent(normalized, value) != null) throw new IllegalArgumentException("Duplicate webhook header");
        });
        return validated;
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
