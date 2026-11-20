package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.appsthatmatter.koonj.server.entity.FileMetadata;
import uk.appsthatmatter.koonj.server.entity.StorageState;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Catalog entry with source path, checksum, lifecycle state and any associated S3 object")
public record FileResponse(UUID id, UUID sourceConnectionId, UUID targetConnectionId, String relativePath,
        String fileName, long sizeBytes, String checksumSha256, StorageState storageState, String s3Key,
        String s3StorageClass, Instant lastModifiedAt, Instant archivedAt, String errorMessage) {
    public static FileResponse from(FileMetadata file) {
        return new FileResponse(file.getId(), file.getSourceConnection().getId(),
                file.getTargetConnection() == null ? null : file.getTargetConnection().getId(), file.getRelativePath(),
                file.getFileName(), file.getSizeBytes(), file.getChecksumSha256(), file.getStorageState(),
                file.getS3Key(), file.getS3StorageClass(), file.getLastModifiedAt(), file.getArchivedAt(),
                file.getErrorMessage());
    }
}
