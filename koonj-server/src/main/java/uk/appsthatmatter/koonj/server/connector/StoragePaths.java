package uk.appsthatmatter.koonj.server.connector;

import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.util.Arrays;
import java.util.regex.Pattern;

public final class StoragePaths {
    public static final String ARCHIVE_STAGING_PREFIX = ".koonj-transfer/";

    private static final Pattern ARCHIVE_STAGING_KEY = Pattern.compile(ARCHIVE_STAGING_PREFIX
            + "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:-previous)?");

    private StoragePaths() {}

    public static String objectKey(StorageConnection connection, String relativeKey) {
        String prefix = connection.getObjectPrefix();
        return prefix == null ? relativeKey : prefix + "/" + relativeKey;
    }

    public static boolean isArchiveStagingKey(String key) {
        return ARCHIVE_STAGING_KEY.matcher(key).matches();
    }

    public static String selection(String input) {
        String path = trimSlashes(input);
        requireSafe(path);
        return path;
    }

    public static String trimSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') start++;
        return trimTrailingSlashes(path.substring(start));
    }

    public static String trimTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    public static void requireSafe(String path) {
        if (path.isEmpty()) return;
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
                || Arrays.stream(path.split("/", -1)).anyMatch(part ->
                part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IllegalArgumentException("Use a relative storage path without traversal segments");
        }
    }
}
