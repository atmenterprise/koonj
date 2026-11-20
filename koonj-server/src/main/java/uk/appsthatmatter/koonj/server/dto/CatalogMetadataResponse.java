package uk.appsthatmatter.koonj.server.dto;

import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest.Scope;

public record CatalogMetadataResponse(Scope scope, String path, CatalogMetadataValues values) {}
