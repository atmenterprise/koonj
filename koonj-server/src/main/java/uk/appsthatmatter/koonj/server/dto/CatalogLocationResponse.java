package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageRole;

import java.util.UUID;

@Schema(description = "Catalog location and optional object bucket for building the catalog tree and object URIs")
public record CatalogLocationResponse(UUID id, String name, ProviderType providerType, StorageRole role,
                                      String bucket, boolean active) {}
