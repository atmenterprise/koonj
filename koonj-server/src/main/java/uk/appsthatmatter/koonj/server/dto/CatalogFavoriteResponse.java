package uk.appsthatmatter.koonj.server.dto;

import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;
import uk.appsthatmatter.koonj.server.entity.CatalogFavorite;

import java.util.UUID;

public record CatalogFavoriteResponse(UUID connectionId, String connectionName, Scope scope, String path) {
    public static CatalogFavoriteResponse from(CatalogFavorite favorite) {
        return new CatalogFavoriteResponse(favorite.getConnection().getId(), favorite.getConnection().getName(),
                favorite.getScope(), favorite.getPath());
    }
}
