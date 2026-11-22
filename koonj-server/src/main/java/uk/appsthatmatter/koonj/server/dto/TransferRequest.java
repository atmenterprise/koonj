package uk.appsthatmatter.koonj.server.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import uk.appsthatmatter.koonj.server.entity.ArchiveMode;

import java.util.List;
import java.util.UUID;

@Schema(description = "Transfer selection by source path, catalog file ID, or mounted recall folder.")
public record TransferRequest(@NotNull UUID sourceConnectionId, @NotNull UUID targetConnectionId,
        @Schema(example = "[\"docs/report.pdf\", \"photos/\"]") List<@NotBlank String> paths,
        List<@NotNull UUID> fileIds,
        @JsonAlias("s3StorageClass") @Schema(defaultValue = "STANDARD") String storageClass,
        @Schema(defaultValue = "COPY") ArchiveMode archiveMode,
        @Schema(defaultValue = "false", description = "Remove the S3 object after a verified recall")
        boolean deleteFromTarget,
        @Schema(description = "Recall archived links under these folder paths on the mounted destination")
        List<@NotBlank String> targetFolderPaths) {
    public TransferRequest {
        if (archiveMode == null) archiveMode = ArchiveMode.COPY;
    }

    public TransferRequest(UUID sourceConnectionId, UUID targetConnectionId, List<String> paths, List<UUID> fileIds,
                           String storageClass, ArchiveMode archiveMode, boolean deleteFromTarget) {
        this(sourceConnectionId, targetConnectionId, paths, fileIds, storageClass, archiveMode, deleteFromTarget, null);
    }
}
