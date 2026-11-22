package uk.appsthatmatter.koonj.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;

import java.util.UUID;

public record CatalogFavoriteRequest(@NotNull UUID connectionId, @NotNull Scope scope,
                                     @NotBlank @Size(max = 2048) String path, @NotNull Boolean favorite) {}
