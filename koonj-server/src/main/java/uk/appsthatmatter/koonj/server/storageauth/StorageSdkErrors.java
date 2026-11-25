package uk.appsthatmatter.koonj.server.storageauth;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.s3.model.S3Exception;

public final class StorageSdkErrors implements ExecutionInterceptor {
    public static final StorageSdkErrors S3 = new StorageSdkErrors(null);

    private final String credentialError;

    private StorageSdkErrors(String credentialError) {
        this.credentialError = credentialError;
    }

    static StorageSdkErrors credentials(String message) {
        return new StorageSdkErrors(message);
    }

    @Override
    public Throwable modifyException(Context.FailedExecution context, ExecutionAttributes attributes) {
        Throwable failure = context.exception();
        if (credentialError != null) return new StorageCredentialException(credentialError);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof StorageCredentialException) return cause;
        }
        if (failure instanceof S3Exception s3) {
            var builder = S3Exception.builder().statusCode(s3.statusCode())
                    .message("S3 request failed with HTTP " + s3.statusCode());
            if (s3.awsErrorDetails() != null && "RestoreAlreadyInProgress".equals(s3.awsErrorDetails().errorCode())) {
                builder.awsErrorDetails(AwsErrorDetails.builder().errorCode("RestoreAlreadyInProgress").build());
            }
            return builder.build();
        }
        return SdkClientException.create("Could not communicate with the S3 endpoint");
    }
}
