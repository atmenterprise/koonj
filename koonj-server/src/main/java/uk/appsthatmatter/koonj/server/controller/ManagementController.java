package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.entity.ProjectTemperatureSettings;
import uk.appsthatmatter.koonj.server.repository.ProjectTemperatureSettingsRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import uk.appsthatmatter.koonj.server.repository.TemperatureSettingsRepository;
import uk.appsthatmatter.koonj.server.security.KoonjRoles;

@RestController
@RequestMapping("/api/v1/management")
public class ManagementController {
    private static final int POLICY_ID = 1;
    private final TemperatureSettingsRepository legacySettings;
    private final ProjectTemperatureSettingsRepository settings;

    public ManagementController(TemperatureSettingsRepository legacySettings, ProjectTemperatureSettingsRepository settings) {
        this.legacySettings = legacySettings;
        this.settings = settings;
    }

    public record TemperaturePolicy(@Min(1) @Max(36500) int hotDays, @Min(2) @Max(36500) int coldDays) {}

    @GetMapping("/temperature-policy")
    @PreAuthorize(KoonjRoles.READ)
    public TemperaturePolicy temperaturePolicy() {
        var projectId = ProjectScope.id();
        return settings.findById(projectId).map(policy -> new TemperaturePolicy(policy.getHotDays(), policy.getColdDays()))
                .orElseGet(() -> ProjectScope.LEGACY.equals(projectId) ? legacySettings.findById(POLICY_ID)
                        .map(policy -> new TemperaturePolicy(policy.getHotDays(), policy.getColdDays()))
                        .orElse(new TemperaturePolicy(15, 30)) : new TemperaturePolicy(15, 30));
    }

    @PutMapping("/temperature-policy")
    @PreAuthorize(KoonjRoles.ADMIN_ONLY)
    public TemperaturePolicy updateTemperaturePolicy(@Valid @RequestBody TemperaturePolicy policy) {
        if (policy.hotDays() >= policy.coldDays()) throw new IllegalArgumentException("Cold must start after the hot threshold");
        var stored = new ProjectTemperatureSettings();
        stored.setProjectId(ProjectScope.id());
        stored.setHotDays(policy.hotDays());
        stored.setColdDays(policy.coldDays());
        settings.save(stored);
        return policy;
    }
}
