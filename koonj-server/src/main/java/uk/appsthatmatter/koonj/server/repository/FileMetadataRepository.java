package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.dto.StorageUsage;
import uk.appsthatmatter.koonj.server.entity.EntryType;
import uk.appsthatmatter.koonj.server.entity.FileMetadata;
import uk.appsthatmatter.koonj.server.entity.StorageState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID>, JpaSpecificationExecutor<FileMetadata> {
    long countByEntryType(EntryType entryType);

    @Modifying
    @Query("update FileMetadata f set f.storageState = :state, f.errorMessage = :error where f.storageState in :active")
    int failInterrupted(StorageState state, String error, Collection<StorageState> active);

    Optional<FileMetadata> findByIdAndConnectionProjectId(UUID id, UUID projectId);
    Optional<FileMetadata> findByConnectionIdAndRelativePath(UUID connectionId, String relativePath);
    List<FileMetadata> findByTargetConnectionIdAndS3Key(UUID targetId, String key);
    List<FileMetadata> findByConnectionId(UUID connectionId);
    List<FileMetadata> findByTargetConnectionIdAndStorageStateIn(UUID targetId, Collection<StorageState> states);
    boolean existsByConnectionIdOrTargetConnectionId(UUID connectionId, UUID targetId);

    @Query("""
            select new uk.appsthatmatter.koonj.server.dto.StorageUsage$Footprint(
                f.connection.id, f.s3StorageClass, count(f), sum(f.sizeBytes))
            from FileMetadata f
            where f.connection.projectId = :projectId and f.entryType = uk.appsthatmatter.koonj.server.entity.EntryType.FILE
                and f.storageState <> uk.appsthatmatter.koonj.server.entity.StorageState.PURGED_LOCAL
            group by f.connection.id, f.s3StorageClass
            """)
    List<StorageUsage.Footprint> storageFootprints(UUID projectId);

    @Query("""
            select new uk.appsthatmatter.koonj.server.dto.StorageUsage$Reclaimed(
                link.connection.id, object.connection.id, count(link), sum(object.sizeBytes))
            from FileMetadata link join FileMetadata object
                on object.connection.id = link.targetConnection.id and object.relativePath = link.s3Key
            where link.connection.projectId = :projectId and object.connection.projectId = :projectId
                and link.entryType = uk.appsthatmatter.koonj.server.entity.EntryType.SYMLINK
                and object.entryType = uk.appsthatmatter.koonj.server.entity.EntryType.FILE
                and object.storageState <> uk.appsthatmatter.koonj.server.entity.StorageState.PURGED_LOCAL
            group by link.connection.id, object.connection.id
            """)
    List<StorageUsage.Reclaimed> reclaimedStorage(UUID projectId);

    interface RecallLink {
        UUID getId();
        String getRelativePath();
        String getSymlinkTarget();
        String getS3Key();
        UUID getTargetId();
        long getSizeBytes();
    }

    @Query("""
            select f.id as id, f.relativePath as relativePath, f.symlinkTarget as symlinkTarget,
                   f.s3Key as s3Key, target.id as targetId, f.sizeBytes as sizeBytes
            from FileMetadata f left join f.targetConnection target
            where f.connection.id = :connectionId and f.entryType = uk.appsthatmatter.koonj.server.entity.EntryType.SYMLINK
              and f.relativePath like :pattern escape '!' and f.relativePath > :after
              and (:aliasesOnly = false or f.s3Key <> f.relativePath or f.s3Key is null or f.symlinkTarget like 's3:%')
            order by f.relativePath
            """)
    List<RecallLink> recallLinks(UUID connectionId, String pattern, String after, boolean aliasesOnly, Pageable page);
}
