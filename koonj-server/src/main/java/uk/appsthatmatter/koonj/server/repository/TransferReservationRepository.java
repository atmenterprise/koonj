package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.TransferReservation;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransferReservationRepository extends JpaRepository<TransferReservation, UUID> {
    List<TransferReservation> findByNamespaceIn(Collection<String> namespaces);
    List<TransferReservation> findByJobId(UUID jobId);

    @Query("select distinct r.namespace from TransferReservation r where r.jobId = :jobId")
    List<String> namespaces(UUID jobId);

    @Query("""
            select count(distinct r.jobId) from TransferReservation r, TransferJob j
            where j.id = r.jobId and j.workerAttempt is not null and r.namespace = :namespace
            """)
    long runningIn(String namespace);

    @Modifying
    @Query("delete from TransferReservation r where r.jobId = :jobId")
    void release(UUID jobId);
}
