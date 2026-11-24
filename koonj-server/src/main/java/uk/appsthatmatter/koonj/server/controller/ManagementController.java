package uk.appsthatmatter.koonj.server.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uk.appsthatmatter.koonj.server.entity.ProjectTemperatureSettings;
import uk.appsthatmatter.koonj.server.repository.ProjectTemperatureSettingsRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import uk.appsthatmatter.koonj.server.service.TemperaturePolicyService;
import uk.appsthatmatter.koonj.server.security.PermissionRules;

@RestController
@RequestMapping("/api/v1/management")
public class ManagementController {
    private final TemperaturePolicyService policy;
    private final ProjectTemperatureSettingsRepository settings;

    public ManagementController(TemperaturePolicyService policy, ProjectTemperatureSettingsRepository settings) {
        this.policy = policy;
        this.settings = settings;
    }

    public record TemperaturePolicy(@Min(1) @Max(36500) int hotDays, @Min(2) @Max(36500) int coldDays) {}

    @GetMapping("/temperature-policy")
    @PreAuthorize(PermissionRules.POLICY_READ)
    public TemperaturePolicy temperaturePolicy() {
        var thresholds = policy.current();
        return new TemperaturePolicy(thresholds.hotDays(), thresholds.coldDays());
    }

    @PutMapping("/temperature-policy")
    @PreAuthorize(PermissionRules.POLICY_MANAGE)
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
