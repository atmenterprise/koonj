package uk.appsthatmatter.koonj.server.storageauth;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;

final class AwsWorkloadIdentity implements AwsCredentialsProvider, AutoCloseable {
    private final DefaultCredentialsProvider chain = DefaultCredentialsProvider.builder().build();

    @Override
    public AwsCredentials resolveCredentials() {
        AwsCredentials credentials;
        try {
            credentials = chain.resolveCredentials();
        } catch (SdkException failure) {
            throw new StorageCredentialException("AWS workload identity did not provide credentials");
        }
        if (!(credentials instanceof AwsSessionCredentials)) {
            throw new StorageCredentialException("AWS workload identity requires temporary session credentials");
        }
        return credentials;
    }

    @Override
    public void close() {
        chain.close();
    }
}
