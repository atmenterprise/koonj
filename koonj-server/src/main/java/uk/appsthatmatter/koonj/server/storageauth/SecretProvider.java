package uk.appsthatmatter.koonj.server.storageauth;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

import java.time.Instant;
import java.util.Objects;

interface SecretProvider {
    VersionedSecret read(String path);

    record VersionedSecret(String version, JsonNode fields) {
        @Override public String toString() { return "Storage credential version"; }

        CredentialSnapshot forTarget(String bucket, String endpoint, String prefix) {
            if (!bucket.equals(text("bucket")) || !Objects.equals(endpoint, text("endpointUrl"))
                    || !Objects.equals(prefix, text("objectPrefix"))) {
                throw new StorageCredentialException("The external credential is not bound to this storage target");
            }
            String access = text("accessKeyId");
            String secret = text("secretAccessKey");
            String token = text("sessionToken");
            String expiry = text("expiresAt");
            if (access == null || secret == null || (token == null) != (expiry == null)) {
                throw new StorageCredentialException("The external credential is missing required fields");
            }
            try {
                if (token != null) {
                    Instant expiresAt = Instant.parse(expiry);
                    return new CredentialSnapshot(version, AwsSessionCredentials
                            .builder().accessKeyId(access).secretAccessKey(secret).sessionToken(token)
                            .expirationTime(expiresAt).build(), expiresAt);
                }
                return new CredentialSnapshot(version,
                        AwsBasicCredentials.create(access, secret), null);
            } catch (RuntimeException invalid) {
                throw new StorageCredentialException("The external credential has invalid fields");
            }
        }

        private String text(String key) {
            JsonNode value = fields.path(key);
            if (value.isMissingNode() || value.isNull()) return null;
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new StorageCredentialException("The external credential has invalid fields");
            }
            return value.asText();
        }
    }
}
