package uk.appsthatmatter.koonj.server.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StorageCredentialInput(
        @NotBlank @Size(max = 256) @Pattern(regexp = "[!-~]+")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String accessKeyId,
        @NotBlank @Size(max = 4096) @Pattern(regexp = "[!-~]+")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String secretAccessKey) {

    @JsonAnySetter
    public void rejectUnsupportedField(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported storage credential field");
    }

    @Override public String toString() { return "StorageCredentialInput[redacted]"; }
}
