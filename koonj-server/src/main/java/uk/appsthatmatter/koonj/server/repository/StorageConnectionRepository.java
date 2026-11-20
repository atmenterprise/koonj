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
    @Query("select c from StorageConnection c where c.id = :id")
    Optional<StorageConnection> findByIdForUpdate(UUID id);

    List<StorageConnection> findAllByOrderByNameAsc();
}
