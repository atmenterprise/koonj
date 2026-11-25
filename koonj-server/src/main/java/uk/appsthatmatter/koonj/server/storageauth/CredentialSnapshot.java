package uk.appsthatmatter.koonj.server.storageauth;

import software.amazon.awssdk.auth.credentials.AwsCredentials;

import java.time.Instant;

record CredentialSnapshot(String version, AwsCredentials credentials, Instant expiresAt) {
    @Override public String toString() { return "Storage credential snapshot"; }
}
