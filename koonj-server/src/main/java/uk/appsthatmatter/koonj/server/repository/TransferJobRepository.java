package uk.appsthatmatter.koonj.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import uk.appsthatmatter.koonj.server.entity.TransferJob;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferJobRepository extends JpaRepository<TransferJob, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from TransferJob j where j.id = :id")
    Optional<TransferJob> lock(UUID id);

    List<TransferJob> findBySourceConnectionIdAndOperationAndStatusIn(UUID id, TransferJob.Operation operation,
                                                                     Collection<TransferJob.Status> statuses);

    @Query("select j.id from TransferJob j where j.status in :statuses")
    List<UUID> idsWithStatus(Collection<TransferJob.Status> statuses);

    @Modifying
    @Query("""
            update TransferJob j set j.status = :status, j.completedAt = :finished, j.details = :details
            where j.status in :active
            """)
    int finishInterrupted(TransferJob.Status status, Instant finished, String details,
                          Collection<TransferJob.Status> active);

    @Query("""
            select count(j) from TransferJob j
            where (j.sourceConnectionId = :connectionId or j.targetConnectionId = :connectionId)
            and j.status in :statuses
            """)
    long countActive(UUID connectionId, Collection<TransferJob.Status> statuses);
}
