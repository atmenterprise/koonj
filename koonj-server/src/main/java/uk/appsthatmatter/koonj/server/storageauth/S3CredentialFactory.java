package uk.appsthatmatter.koonj.server.storageauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import uk.appsthatmatter.koonj.server.entity.StorageAuthMode;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class S3CredentialFactory {
    private final SecretProvider awsSecrets;
    private final DatabaseStorageCredentials databaseCredentials;
    private final ObjectMapper json;
    private final StorageAuthentication authentication;
    private final Duration ttl;

    S3CredentialFactory(SecretProvider awsSecrets, DatabaseStorageCredentials databaseCredentials,
                        ObjectMapper json, StorageAuthentication authentication,
                        @Value("${KOONJ_STORAGE_SECRET_CACHE_TTL:PT30S}") Duration ttl) {
        this.awsSecrets = awsSecrets;
        this.databaseCredentials = databaseCredentials;
        this.json = json;
        this.authentication = authentication;
        if (ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Storage secret cache TTL must be between 1 second and 5 minutes");
        }
        this.ttl = ttl;
    }

    public Credentials create(CredentialTarget target, StorageAuthMode mode, String secretRef,
                              String roleArn, String externalId) {
        if (mode == StorageAuthMode.AWS_DEFAULT_CHAIN && authentication.allowsDefaultChain()) {
            var provider = new AwsWorkloadIdentity();
            return new Credentials(provider, provider::close);
        }
        if (mode == StorageAuthMode.AWS_ASSUME_ROLE) {
            return assumeRole(target.project(), roleArn, externalId, target.region(), target.bucket(), target.prefix());
        }
        if (mode == StorageAuthMode.SECRET_REF) {
            SecretReference reference;
            try {
                reference = SecretReference.parse(secretRef);
            } catch (IllegalArgumentException failure) {
                throw new StorageCredentialException("Storage authentication requires migration to a supported credential source");
            }
            var credentials = new RefreshingSecretCredentials("aws-sm",
                    () -> awsSecrets.read(reference.path(target.project())).forTarget(target.bucket(), target.endpoint(), target.prefix()),
                    target.project(), target.connection(), ttl, Clock.systemUTC());
            return new Credentials(credentials, () -> {});
        }
        if (mode == StorageAuthMode.ENCRYPTED_CREDENTIALS) {
            var credentials = new RefreshingSecretCredentials("postgresql", () -> databaseCredentials.read(target),
                    target.project(), target.connection(), ttl, Clock.systemUTC());
            return new Credentials(credentials, () -> {});
        }
        throw new StorageCredentialException(mode == StorageAuthMode.MIGRATION_REQUIRED
                ? "Storage authentication requires migration. Choose workload identity, encrypted credentials or AWS Secrets Manager"
                : "This deployment does not allow the configured storage authentication mode");
    }

    private Credentials assumeRole(UUID project, String roleArn, String externalId, String region, String bucket, String prefix) {
        String policy = sessionPolicy(roleArn.split(":", 3)[1], bucket, prefix);
        Region authRegion = Region.of(region);
        var source = new AwsWorkloadIdentity();
        try {
            var sts = StsClient.builder().region(authRegion).credentialsProvider(source)
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))
                            .addExecutionInterceptor(StorageSdkErrors.credentials("AWS STS could not assume the storage role. Check the role trust and permissions")))
                    .build();
            try {
                var assumed = StsAssumeRoleCredentialsProvider.builder().stsClient(sts)
                        .refreshRequest(request -> request.roleArn(roleArn).externalId(externalId).policy(policy)
                                .roleSessionName("koonj-" + project).durationSeconds(3600))
                        .staleTime(Duration.ofMinutes(1))
                        .prefetchTime(Duration.ofMinutes(5))
                        .asyncCredentialUpdateEnabled(true)
                        .build();
                return new Credentials(assumed, () -> {
                    try { assumed.close(); } finally { try { sts.close(); } finally { source.close(); } }
                });
            } catch (RuntimeException failure) {
                sts.close();
                throw failure;
            }
        } catch (RuntimeException failure) {
            source.close();
            throw failure;
        }
    }

    String sessionPolicy(String partition, String bucket, String prefix) {
        if (prefix != null && !prefix.matches("[A-Za-z0-9_./-]+")) {
            throw new StorageCredentialException("The IAM role storage prefix contains unsupported characters");
        }
        String bucketArn = "arn:" + partition + ":s3:::" + bucket;
        String objects = bucketArn + "/" + (prefix == null ? "" : prefix + "/") + "*";
        Map<String, Object> list = new HashMap<>(Map.of(
                "Effect", "Allow", "Action", "s3:ListBucket", "Resource", bucketArn));
        if (prefix != null) list.put("Condition", Map.of("StringLike", Map.of("s3:prefix", prefix + "/*")));
        var object = Map.of("Effect", "Allow", "Resource", objects, "Action", List.of("s3:GetObject",
                "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts", "s3:RestoreObject"));
        var kms = Map.of("Effect", "Allow", "Resource", "*", "Action", List.of("kms:Decrypt", "kms:GenerateDataKey"),
                "Condition", Map.of("StringLike", Map.of("kms:EncryptionContext:aws:s3:arn", List.of(bucketArn, objects))));
        try {
            return json.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(list, object, kms)));
        } catch (JsonProcessingException failure) {
            throw new StorageCredentialException("Could not prepare the AWS storage role session policy");
        }
    }

    public record Credentials(AwsCredentialsProvider provider, Runnable release) implements AutoCloseable {
        @Override public void close() { release.run(); }
        @Override public String toString() { return "S3 credential provider"; }
    }
}
