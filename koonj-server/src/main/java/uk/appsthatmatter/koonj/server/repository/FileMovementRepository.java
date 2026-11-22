package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.FileMovement;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FileMovementRepository extends JpaRepository<FileMovement, Long> {
    @Query("""
            select m.sourcePath from FileMovement m
            where m.sourceConnectionId = :connectionId and m.sourcePath in :paths and m.jobId in :jobIds
              and (m.jobId = :currentJob or m.outcome = uk.appsthatmatter.koonj.server.entity.FileMovement.Outcome.VERIFIED)
            """)
    List<String> handledSourcePaths(UUID connectionId, Collection<String> paths, Collection<UUID> jobIds, UUID currentJob);

    @Query("""
            select m.id from FileMovement m
            where m.sourceConnectionId = :connectionId and m.sourcePath like :pattern escape '!' and m.jobId in :jobIds
              and (m.jobId = :currentJob or m.outcome = uk.appsthatmatter.koonj.server.entity.FileMovement.Outcome.VERIFIED)
            """)
    List<Long> handledSourceFolder(UUID connectionId, String pattern, Collection<UUID> jobIds, UUID currentJob, Pageable page);

    @Query("""
            select m.id from FileMovement m
            where m.targetConnectionId = :connectionId and m.targetPath like :pattern escape '!' and m.jobId in :jobIds
              and (m.jobId = :currentJob or m.outcome = uk.appsthatmatter.koonj.server.entity.FileMovement.Outcome.VERIFIED)
            """)
    List<Long> handledTargetFolder(UUID connectionId, String pattern, Collection<UUID> jobIds, UUID currentJob, Pageable page);

    Page<FileMovement> findByJobIdOrderByIdAsc(UUID jobId, Pageable page);
    Page<FileMovement> findByJobIdAndOutcomeOrderByIdAsc(UUID jobId, FileMovement.Outcome outcome, Pageable page);

    @Query("""
            select m from FileMovement m where (m.sourceConnectionId = :connectionId and m.sourcePath = :path)
                or (m.targetConnectionId = :connectionId and m.targetPath = :path)
            order by m.id desc
            """)
    Page<FileMovement> forFile(UUID connectionId, String path, Pageable page);

    @Query("""
            select m from FileMovement m where m.id > :after and m.id <= :lastId and
                ((m.sourceConnectionId = :connectionId and m.sourcePath like :pattern escape '!')
                or (m.targetConnectionId = :connectionId and m.targetPath like :pattern escape '!'))
            order by m.id
            """)
    List<FileMovement> forFolder(UUID connectionId, String pattern, long after, long lastId, Pageable page);

    @Query("select coalesce(max(m.id), 0) from FileMovement m")
    long latestId();

    @Query("""
            select f.relativePath from FileMetadata f
            where f.connection.id = :connectionId and f.relativePath like :pattern escape '!' and f.relativePath > :after
                and f.entryType <> uk.appsthatmatter.koonj.server.entity.EntryType.DIRECTORY
                and f.storageState <> uk.appsthatmatter.koonj.server.entity.StorageState.PURGED_LOCAL
                and not exists (select m.id from FileMovement m where m.id <= :lastId and
                    ((m.sourceConnectionId = :connectionId and m.sourcePath = f.relativePath)
                    or (m.targetConnectionId = :connectionId and m.targetPath = f.relativePath)))
            order by f.relativePath
            """)
    List<String> withoutRecordedMovements(UUID connectionId, String pattern, String after, long lastId, Pageable page);
}
