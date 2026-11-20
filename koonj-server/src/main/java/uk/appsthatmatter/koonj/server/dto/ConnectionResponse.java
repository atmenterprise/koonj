package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.appsthatmatter.koonj.server.entity.*;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Registered storage endpoint with masked credential values")
public record ConnectionResponse(UUID id, String name, ProviderType providerType, StorageRole role,
        String basePathOrBucket, String endpointUrl, String region, String accessKeyOrUsername,
        String secretKeyOrPassword, boolean pathStyleAccessEnabled, boolean active,
        Instant createdAt, Instant updatedAt) {

    public static ConnectionResponse from(StorageConnection connection) {
        return new ConnectionResponse(connection.getId(), connection.getName(), connection.getProviderType(),
                connection.getRole(), connection.getBasePathOrBucket(), connection.getEndpointUrl(), connection.getRegion(),
                mask(connection.getAccessKeyOrUsername()), mask(connection.getSecretKeyOrPassword()),
                connection.isPathStyleAccessEnabled(), connection.isActive(), connection.getCreatedAt(),
                connection.getUpdatedAt());
    }

    private static String mask(String value) { return value == null ? null : "********"; }
}
