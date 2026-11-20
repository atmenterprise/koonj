package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageRole;

@Schema(description = "Storage endpoint configuration. Supply both credential fields when using static credentials.")
public record ConnectionRequest(
        @NotBlank @Size(max = 255) @Schema(example = "MinIO Cold Vault") String name,
        @NotNull ProviderType providerType,
        @NotNull StorageRole role,
        @NotBlank @Schema(description = "Absolute mounted root, SMB share, or bucket name") String basePathOrBucket,
        @Schema(description = "HTTP(S) endpoint required for non-AWS object storage", example = "http://localhost:9000")
        String endpointUrl,
        @Size(max = 255) @Schema(defaultValue = "us-east-1") String region,
        @Size(max = 255) String accessKeyOrUsername,
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String secretKeyOrPassword,
        @Schema(defaultValue = "true") Boolean pathStyleAccessEnabled,
        @Schema(defaultValue = "true") Boolean active) {}
