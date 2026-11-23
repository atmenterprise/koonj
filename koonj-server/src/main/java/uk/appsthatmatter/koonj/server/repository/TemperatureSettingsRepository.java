package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.entity.TemperatureSettings;

public interface TemperatureSettingsRepository extends JpaRepository<TemperatureSettings, Integer> {}
