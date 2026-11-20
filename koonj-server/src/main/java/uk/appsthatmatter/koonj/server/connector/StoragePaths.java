package uk.appsthatmatter.koonj.server.connector;

import java.util.Arrays;

public final class StoragePaths {
    private StoragePaths() {}

    public static String selection(String input) {
        String path = input.replaceFirst("^/+", "").replaceFirst("/+$", "");
        requireSafe(path);
        return path;
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
