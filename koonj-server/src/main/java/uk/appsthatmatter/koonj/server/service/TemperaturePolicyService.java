package uk.appsthatmatter.koonj.server.service;

import org.springframework.stereotype.Service;
import uk.appsthatmatter.koonj.server.repository.ProjectTemperatureSettingsRepository;
import uk.appsthatmatter.koonj.server.repository.TemperatureSettingsRepository;
import uk.appsthatmatter.koonj.server.security.ProjectScope;

@Service
public class TemperaturePolicyService {
    private static final Thresholds DEFAULTS = new Thresholds(15, 30);
    private final TemperatureSettingsRepository legacySettings;
    private final ProjectTemperatureSettingsRepository settings;

    public TemperaturePolicyService(TemperatureSettingsRepository legacySettings, ProjectTemperatureSettingsRepository settings) {
        this.legacySettings = legacySettings;
        this.settings = settings;
    }

    public record Thresholds(int hotDays, int coldDays) {}

    public Thresholds current() {
        var projectId = ProjectScope.id();
        return settings.findById(projectId).map(policy -> new Thresholds(policy.getHotDays(), policy.getColdDays()))
                .orElseGet(() -> ProjectScope.LEGACY.equals(projectId) ? legacySettings.findById(1)
                        .map(policy -> new Thresholds(policy.getHotDays(), policy.getColdDays()))
                        .orElse(DEFAULTS) : DEFAULTS);
    }
}
