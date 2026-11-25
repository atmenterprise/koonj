package uk.appsthatmatter.koonj.server.storageauth;

import java.util.UUID;
import java.util.regex.Pattern;

record SecretReference(String alias) {
    private static final Pattern REFERENCE = Pattern.compile("aws-sm:([a-zA-Z0-9][a-zA-Z0-9_-]{0,79})");

    static SecretReference parse(String value) {
        var match = REFERENCE.matcher(value == null ? "" : value);
        if (!match.matches()) {
            throw new IllegalArgumentException("Use an AWS Secrets Manager reference such as aws-sm:archive-storage");
        }
        return new SecretReference(match.group(1));
    }

    String path(UUID project) {
        return "koonj/" + project + "/" + alias;
    }
}
