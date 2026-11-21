package uk.appsthatmatter.koonj.server.repository;

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
}
