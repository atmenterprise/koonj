package uk.appsthatmatter.koonj.server.dto;

import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;

import java.util.UUID;

public record StorageLocationStatus(UUID id, String name, ProviderType providerType, StorageRole role,
                                    String basePathOrBucket, String endpointUrl, String region, boolean active) {
    public static StorageLocationStatus from(StorageConnection connection) {
        return new StorageLocationStatus(connection.getId(), connection.getName(), connection.getProviderType(),
                connection.getRole(), connection.getBasePathOrBucket(), connection.getEndpointUrl(),
                connection.getRegion(), connection.isActive());
    }
}
