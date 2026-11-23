package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.entity.RolePermissionSettings;

public interface RolePermissionSettingsRepository extends JpaRepository<RolePermissionSettings, String> {}
