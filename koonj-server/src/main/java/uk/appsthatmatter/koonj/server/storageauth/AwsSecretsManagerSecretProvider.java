package uk.appsthatmatter.koonj.server.storageauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import java.time.Duration;

@Component
final class AwsSecretsManagerSecretProvider implements SecretProvider {
    private final ObjectMapper json;
    private final String region;
    private final AwsWorkloadIdentity identity = new AwsWorkloadIdentity();
    private SecretsManagerClient client;

    AwsSecretsManagerSecretProvider(ObjectMapper json,
            @Value("${KOONJ_AWS_SECRETS_REGION:}") String region) {
        this.json = json;
        this.region = region;
    }

    @Override
    public VersionedSecret read(String path) {
        try {
            var response = client().getSecretValue(request -> request.secretId(path));
            if (response.secretString() == null || response.versionId() == null) {
                throw new StorageCredentialException("AWS Secrets Manager must return a versioned JSON credential");
            }
            JsonNode fields = json.readTree(response.secretString());
            if (fields == null || !fields.isObject()) {
                throw new StorageCredentialException("AWS Secrets Manager returned an invalid credential");
            }
            return new VersionedSecret(response.versionId(), fields);
        } catch (ResourceNotFoundException failure) {
            throw new StorageCredentialException("The AWS Secrets Manager credential was not found");
        } catch (SecretsManagerException failure) {
            throw new StorageCredentialException(failure.statusCode() == 403
                    ? "AWS Secrets Manager denied access to the credential"
                    : "AWS Secrets Manager could not resolve the credential");
        } catch (JsonProcessingException | SdkException failure) {
            throw new StorageCredentialException("Could not read the credential from AWS Secrets Manager");
        }
    }

    private synchronized SecretsManagerClient client() {
        if (region.isBlank()) throw new StorageCredentialException("AWS Secrets Manager is not configured");
        if (client == null) {
            client = SecretsManagerClient.builder().region(Region.of(region)).credentialsProvider(identity)
                    .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(8)))
                    .build();
        }
        return client;
    }

    @PreDestroy
    synchronized void close() {
        try { if (client != null) client.close(); } finally { identity.close(); }
    }
}
