package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Update direct metadata for one or more catalog items. Folder metadata is inherited by descendants.")
public record CatalogMetadataWriteRequest(
        @NotNull @Size(min = 1, max = 500) List<@NotNull @Valid Target> targets,
        @NotNull @Valid CatalogMetadataValues values,
        @NotNull Mode mode) {
    public enum Scope { FILE, FOLDER }
    public enum Mode { REPLACE, MERGE }
    public record Target(@NotNull Scope scope, @NotBlank @Size(max = 2048) String path) {}
}
