package uk.appsthatmatter.koonj.server.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageRole;
import uk.appsthatmatter.koonj.server.entity.StorageAuthMode;

@Schema(description = "Storage endpoint and its authentication settings")
public record ConnectionRequest(
        @NotBlank @Size(max = 255) @Schema(example = "MinIO Cold Vault") String name,
        @NotNull ProviderType providerType,
        @NotNull StorageRole role,
        @NotBlank @Schema(description = "Absolute mounted root, SMB share, or bucket name") String basePathOrBucket,
        @Schema(description = "HTTP(S) endpoint required for non-AWS object storage", example = "http://localhost:9000")
        @Size(max = 2048) String endpointUrl,
        @Size(max = 255) @Schema(defaultValue = "us-east-1") String region,
        StorageAuthMode authMode,
        @Size(max = 100) String secretRef,
        @Valid @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        StorageCredentialInput credentials,
        @Size(max = 255) String awsRoleArn,
        @Size(max = 255) String objectPrefix,
        @Schema(defaultValue = "true") Boolean pathStyleAccessEnabled,
        @Schema(defaultValue = "true") Boolean active) {

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported storage connection field");
    }
}
