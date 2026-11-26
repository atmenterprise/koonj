package uk.appsthatmatter.koonj.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.Project;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> lock(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);
    boolean existsByGeneratedName(String name);
}
