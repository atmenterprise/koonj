package uk.appsthatmatter.koonj.server.connector;

import java.time.Instant;

public record ScannedFileInfo(String relativePath, long sizeBytes, Instant lastModifiedAt, String storageClass) {}
