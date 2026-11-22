package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.FileMetadata;
import uk.appsthatmatter.koonj.server.entity.StorageState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID>, JpaSpecificationExecutor<FileMetadata> {
    @Modifying
    @Query("update FileMetadata f set f.storageState = :state, f.errorMessage = :error where f.storageState in :active")
    int failInterrupted(StorageState state, String error, Collection<StorageState> active);

    Optional<FileMetadata> findByConnectionIdAndRelativePath(UUID connectionId, String relativePath);
    List<FileMetadata> findByTargetConnectionIdAndS3Key(UUID targetId, String key);
    List<FileMetadata> findByConnectionId(UUID connectionId);
    List<FileMetadata> findByTargetConnectionIdAndStorageStateIn(UUID targetId, Collection<StorageState> states);
    boolean existsByConnectionIdOrTargetConnectionId(UUID connectionId, UUID targetId);

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
