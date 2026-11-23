package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.appsthatmatter.koonj.server.connector.StorageCapacity;

import java.util.UUID;

@Schema(description = "Result of a storage connectivity and access probe")
public record ConnectionHealth(UUID connectionId, boolean healthy, String message, StorageCapacity capacity) {
    public ConnectionHealth(UUID connectionId, boolean healthy, String message) {
        this(connectionId, healthy, message, null);
    }
}
