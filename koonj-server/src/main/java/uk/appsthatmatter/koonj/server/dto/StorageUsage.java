package uk.appsthatmatter.koonj.server.dto;

import java.util.List;
import java.util.UUID;

public record StorageUsage(List<Footprint> footprints, List<Reclaimed> reclaimed) {
    public record Footprint(UUID connectionId, String storageClass, long fileCount, long sizeBytes) {}
    public record Reclaimed(UUID sourceConnectionId, UUID targetConnectionId, long fileCount, long sizeBytes) {}
}
