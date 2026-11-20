package uk.appsthatmatter.koonj.server.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.appsthatmatter.koonj.server.dto.ServerTelemetryResponse;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;

import java.lang.management.ManagementFactory;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final HealthEndpoint health;

    public AdminController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/telemetry")
    @Operation(summary = "Read server telemetry", description = "Administrator view of service health, uptime and current JVM measurements.")
    @PreAuthorize(KoonjRoles.ADMIN_ONLY)
    public ServerTelemetryResponse telemetry() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new ServerTelemetryResponse(health.health().getStatus().getCode(), Instant.now(),
                runtime.getUptime() / 1000, runtime.getSpecVersion(), heap.getUsed(), heap.getCommitted(),
                ManagementFactory.getThreadMXBean().getThreadCount(),
                ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors());
    }
}
