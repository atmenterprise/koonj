package uk.appsthatmatter.koonj.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
class OpenObserveDashboardProvisioner {
    private static final Logger LOG = LoggerFactory.getLogger(OpenObserveDashboardProvisioner.class);
    private static final int MAX_ATTEMPTS = 6;
    private final String endpoint;
    private final String authorization;
    private final ResourcePatternResolver resources;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TransferTransactionLocks locks;

    OpenObserveDashboardProvisioner(@Value("${KOONJ_OPENOBSERVE_DASHBOARDS_ENDPOINT:}") String endpoint,
            @Value("${KOONJ_OPENOBSERVE_DASHBOARDS_AUTHORIZATION:${OPENOBSERVE_AUTHORIZATION:}}") String authorization,
            ResourcePatternResolver resources, ObjectMapper json, TransactionTemplate transactions,
            TransferTransactionLocks locks) {
        this.endpoint = endpoint;
        this.authorization = authorization;
        this.resources = resources;
        this.json = json;
        this.transactions = transactions;
        this.locks = locks;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void provision() {
        if (endpoint.isBlank()) return;
        if (authorization.isBlank()) {
            LOG.warn("Configure authorization to provision OpenObserve dashboards");
            return;
        }
        URI dashboards;
        List<JsonNode> templates;
        try {
            URI base = URI.create(endpoint);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                    || base.getUserInfo() != null || base.getRawQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("Invalid dashboard endpoint");
            }
            dashboards = UriComponentsBuilder.fromUri(base).queryParam("folder", "default").build(true).toUri();
            templates = loadTemplates();
        } catch (IOException | IllegalArgumentException failure) {
            LOG.error("Could not read the OpenObserve dashboard endpoint or templates ({})", failure.getClass().getSimpleName());
            return;
        }

        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()) {
            var requestFactory = new JdkClientHttpRequestFactory(http);
            requestFactory.setReadTimeout(Duration.ofSeconds(8));
            var client = RestClient.builder().requestFactory(requestFactory)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authorization).build();
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    transactions.executeWithoutResult(status -> {
                        // OpenObserve assigns IDs on create, so serialize the list/create sequence across replicas.
                        locks.acquire(List.of("openobserve-dashboards:" + dashboards));
                        importMissing(client, dashboards, templates);
                    });
                    return;
                } catch (RestClientResponseException failure) {
                    int status = failure.getStatusCode().value();
                    if (status != 408 && status != 429 && !failure.getStatusCode().is5xxServerError()) {
                        LOG.error("OpenObserve dashboard provisioning failed with HTTP {}", status);
                        return;
                    }
                    LOG.warn("OpenObserve dashboard provisioning attempt {} returned HTTP {}", attempt, status);
                } catch (ResourceAccessException failure) {
                    LOG.warn("OpenObserve was unavailable during dashboard provisioning attempt {}", attempt);
                }
                if (attempt < MAX_ATTEMPTS) Thread.sleep(Duration.ofSeconds(15));
            }
            LOG.error("OpenObserve dashboard provisioning did not finish after {} attempts", MAX_ATTEMPTS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOG.error("OpenObserve dashboard provisioning failed ({})", failure.getClass().getSimpleName());
        }
    }

    private List<JsonNode> loadTemplates() throws IOException {
        List<JsonNode> templates = new ArrayList<>();
        for (var file : resources.getResources("classpath*:openobserve-dashboards/*.dashboard.json")) {
            try (var input = file.getInputStream()) {
                templates.add(json.readTree(input));
            }
        }
        if (templates.isEmpty() || templates.stream().anyMatch(template -> template.path("title").asText().isBlank())) {
            throw new IOException("No valid OpenObserve dashboard templates");
        }
        return templates;
    }

    private static void importMissing(RestClient client, URI uri, List<JsonNode> templates) {
        for (JsonNode template : templates) {
            String title = template.path("title").asText();
            URI lookup = UriComponentsBuilder.fromUri(uri)
                    .queryParam("title", UriUtils.encode(title, StandardCharsets.UTF_8)).build(true).toUri();
            if (hasDashboard(client, lookup, title)) continue;
            client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(template).retrieve().toBodilessEntity();
            LOG.info("Imported OpenObserve dashboard {}", title);
        }
    }

    private static boolean hasDashboard(RestClient client, URI lookup, String title) {
        JsonNode response = client.get().uri(lookup).retrieve().body(JsonNode.class);
        if (response == null || !response.path("dashboards").isArray()) {
            throw new IllegalStateException("Unexpected OpenObserve dashboard list response");
        }
        for (JsonNode dashboard : response.path("dashboards")) {
            if (title.equals(dashboard.path("title").asText())) return true;
        }
        return false;
    }
}
