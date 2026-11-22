package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Description, tags, and custom properties defined directly on a catalog file or folder")
public record CatalogMetadataValues(
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 100) String> tags,
        @NotNull @Size(max = 100) List<@NotNull @Valid Property> properties,
        @Size(max = 4000) @Schema(description = "Plain-text description. Omit to preserve it or send an empty string to clear it.")
        String description) {
    public CatalogMetadataValues(List<String> tags, List<Property> properties) {
        this(tags, properties, null);
    }

    public record Property(@NotBlank @Size(max = 100) String name,
                           @NotBlank @Size(max = 4000) String value) {}
}
