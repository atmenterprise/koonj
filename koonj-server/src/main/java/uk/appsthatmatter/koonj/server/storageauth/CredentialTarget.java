package uk.appsthatmatter.koonj.server.storageauth;

import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.util.Objects;
import java.util.UUID;

public record CredentialTarget(UUID project, UUID connection, ProviderType provider, String bucket,
                               String endpoint, String region, String prefix, boolean pathStyle) {
    public static CredentialTarget from(StorageConnection settings) {
        return new CredentialTarget(settings.getProjectId(), settings.getId(), settings.getProviderType(),
                settings.getBasePathOrBucket(), settings.getEndpointUrl(),
                Objects.requireNonNullElse(settings.getRegion(), "us-east-1"), settings.getObjectPrefix(),
                settings.isPathStyleAccessEnabled());
    }
}
