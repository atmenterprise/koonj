package uk.appsthatmatter.koonj.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.core.env.Environment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.appsthatmatter.koonj.server.dto.ServerTelemetryResponse;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final HealthEndpoint health;
    private final Environment environment;

    public AdminController(HealthEndpoint health, Environment environment) {
        this.health = health;
        this.environment = environment;
    }

    @GetMapping("/telemetry")
    @Operation(summary = "Read server telemetry", description = "Administrator view of service health, uptime and current JVM measurements.")
    @PreAuthorize(KoonjRoles.ADMIN_ONLY)
    public ServerTelemetryResponse telemetry() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var status = health.health();
        var components = new TreeMap<String, String>();
        if (status instanceof CompositeHealth composite) {
            composite.getComponents().forEach((name, component) -> components.put(name, component.getStatus().getCode()));
        }
        return new ServerTelemetryResponse(status.getStatus().getCode(), runtime.getName(), Instant.now(),
                runtime.getUptime() / 1000, runtime.getSpecVersion(), heap.getUsed(), heap.getCommitted(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors(), components);
    }

    @GetMapping("/observability")
    @PreAuthorize(KoonjRoles.ADMIN_ONLY)
    public Observability observability() {
        String url = environment.getProperty("koonj.observability.openobserve-url", "");
        String organization = environment.getProperty("koonj.observability.openobserve-organization", "default");
        return new Observability(browserUrl(url), organization, Map.of(
                "logs", !environment.getProperty("koonj.observability.logs-endpoint", "").isBlank(),
                "metrics", environment.getProperty("management.otlp.metrics.export.enabled", boolean.class, false),
                "traces", environment.getProperty("management.otlp.tracing.export.enabled", boolean.class, false)));
    }

    private static String browserUrl(String value) {
        try {
            URI uri = URI.create(value.strip());
            if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null) return uri.toString();
        } catch (IllegalArgumentException invalidUrl) {
            return null;
        }
        return null;
    }

    public record Observability(String openObserveUrl, String organization, Map<String, Boolean> exporters) {}
}
