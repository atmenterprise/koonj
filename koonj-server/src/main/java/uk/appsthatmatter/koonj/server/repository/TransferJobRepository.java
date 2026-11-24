package uk.appsthatmatter.koonj.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    Page<TransferJob> findByProjectIdAndOwnerSubject(UUID projectId, String ownerSubject, Pageable page);
    Optional<TransferJob> findByIdAndProjectId(UUID id, UUID projectId);
    Optional<TransferJob> findByIdAndProjectIdAndOwnerSubject(UUID id, UUID projectId, String ownerSubject);
    boolean existsByIdAndProjectIdAndOwnerSubject(UUID id, UUID projectId, String ownerSubject);

    Optional<TransferJob> findFirstByProjectIdAndInteractiveFileIdAndStatusInOrderByCreatedAtDesc(
            UUID projectId, UUID fileId, Collection<TransferJob.Status> statuses);

    Optional<TransferJob> findFirstByProjectIdAndInteractiveFileIdOrderByCreatedAtDesc(UUID projectId, UUID fileId);

    long countByProjectIdAndWorkerAttemptIsNotNullAndPriorityLessThan(UUID projectId, int priority);

    long countByProjectIdAndWorkerAttemptIsNotNullAndPriorityGreaterThanEqual(UUID projectId, int priority);

    @Modifying
    @Query("update TransferJob j set j.workerHeartbeat = :now where j.workerInstance = :instance and j.workerAttempt in :attempts")
    int heartbeat(UUID instance, Collection<UUID> attempts, Instant now);

    @Query("select count(j) from TransferJob j where j.workerAttempt is not null and j.workerHeartbeat < :cutoff")
    long countStalled(Instant cutoff);

    long countByStatusAndDispatchGenerationIsNotNull(TransferJob.Status status);

    @Query("""
            select min(j.createdAt) from TransferJob j
            where j.status = uk.appsthatmatter.koonj.server.entity.TransferJob.Status.PENDING
              and j.dispatchGeneration is not null and j.recallReadyAt is null
            """)
    Instant oldestWaitingTransfer();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from TransferJob j where j.id = :id")
    Optional<TransferJob> lock(UUID id);

    List<TransferJob> findBySourceConnectionIdAndOperationAndStatusIn(UUID id, TransferJob.Operation operation,
                                                                     Collection<TransferJob.Status> statuses);

    boolean existsBySourceConnectionIdAndOperationAndStatusIn(UUID id, TransferJob.Operation operation,
                                                               Collection<TransferJob.Status> statuses);

    List<TransferJob> findByStatusAndOwnerSubjectIsNull(TransferJob.Status status);

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
