package uk.appsthatmatter.koonj.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageConnectionRepository extends JpaRepository<StorageConnection, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StorageConnection c where c.id = :id and c.projectId = :projectId")
    Optional<StorageConnection> findByIdForUpdate(UUID id, UUID projectId);

    List<StorageConnection> findByProjectIdOrderByNameAsc(UUID projectId);
    Optional<StorageConnection> findByIdAndProjectId(UUID id, UUID projectId);
    boolean existsByIdAndProjectId(UUID id, UUID projectId);

    @Query("""
            select c.id from StorageConnection c
            where c.projectId = :projectId and c.active = true and c.role <> uk.appsthatmatter.koonj.server.entity.StorageRole.TARGET
            order by c.id
            """)
    List<UUID> activeSourceIds(UUID projectId);
}
