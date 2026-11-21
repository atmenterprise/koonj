package uk.appsthatmatter.koonj.server.connector;

import uk.appsthatmatter.koonj.server.entity.EntryType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public record ScannedFileInfo(String relativePath, long sizeBytes, Instant lastModifiedAt, String storageClass,
                              EntryType entryType, String symlinkTarget) {
    public ScannedFileInfo {
        // PostgreSQL timestamps are stored at microsecond precision.
        if (lastModifiedAt != null) lastModifiedAt = lastModifiedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public ScannedFileInfo(String path, long size, Instant modified, String storageClass) {
        this(path, size, modified, storageClass, EntryType.FILE, null);
    }
}
