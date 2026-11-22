package uk.appsthatmatter.koonj.server.connector;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

public record FileAttributes(String owner, Instant createdAt, Instant lastAccessedAt, String author,
                             String createdBy, String lastModifiedBy, String lastAccessedBy) {
    public static final FileAttributes EMPTY = new FileAttributes(null, null, null, null, null, null, null);

    static FileAttributes from(String owner, Instant createdAt, Instant lastAccessedAt, Map<String, String> metadata) {
        return new FileAttributes(owner, createdAt != null ? createdAt : timestamp(metadata.get("created-at")),
                lastAccessedAt != null ? lastAccessedAt : timestamp(metadata.get("last-accessed-at")),
                metadata.get("author"), metadata.get("created-by"), metadata.get("last-modified-by"),
                metadata.get("last-accessed-by"));
    }

    private static Instant timestamp(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
