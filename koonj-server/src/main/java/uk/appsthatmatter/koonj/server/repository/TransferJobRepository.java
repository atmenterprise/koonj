package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import uk.appsthatmatter.koonj.server.entity.TransferJob;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferJobRepository extends JpaRepository<TransferJob, UUID> {
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
