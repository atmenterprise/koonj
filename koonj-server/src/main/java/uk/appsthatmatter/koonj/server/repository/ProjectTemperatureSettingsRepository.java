package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.entity.ProjectTemperatureSettings;
import java.util.UUID;

public interface ProjectTemperatureSettingsRepository extends JpaRepository<ProjectTemperatureSettings, UUID> {}
