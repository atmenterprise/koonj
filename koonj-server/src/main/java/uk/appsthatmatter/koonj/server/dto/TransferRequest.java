package uk.appsthatmatter.koonj.server.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

@Schema(description = "Transfer selection. Provide paths, catalog file IDs, or both. Paths are relative to the source.")
public record TransferRequest(@NotNull UUID sourceConnectionId, @NotNull UUID targetConnectionId,
        @Schema(example = "[\"docs/report.pdf\", \"photos/\"]") List<@NotBlank String> paths,
        List<@NotNull UUID> fileIds,
        @JsonAlias("s3StorageClass") @Schema(defaultValue = "STANDARD") String storageClass,
        @JsonAlias("deleteSourceAfterTransfer")
        @Schema(description = "Delete the source after verified archive. Recall retains the archive.", defaultValue = "false")
        boolean purgeSource) {}
