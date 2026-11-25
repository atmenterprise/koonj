package uk.appsthatmatter.koonj.server.storageauth;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
final class AwsKmsWrappingKeys {
    private static final Pattern KEY_ARN = Pattern.compile("arn:aws(?:-[a-z0-9-]+)?:kms:([a-z0-9-]+):[0-9]{12}:key/[a-zA-Z0-9-]+");
    private final String activeKey;
    private final Set<String> allowedKeys;
    private final AwsWorkloadIdentity identity = new AwsWorkloadIdentity();
    private final Map<String, KmsClient> clients = new ConcurrentHashMap<>();

    AwsKmsWrappingKeys(@Value("${KOONJ_STORAGE_KMS_KEY_ARN:}") String activeKey,
                       @Value("${KOONJ_STORAGE_KMS_PREVIOUS_KEY_ARNS:}") String previousKeys) {
        this.activeKey = activeKey;
        var keys = new HashSet<String>();
        if (!activeKey.isBlank()) keys.add(activeKey);
        if (!previousKeys.isBlank()) {
            for (String key : previousKeys.split(",", -1)) keys.add(key.strip());
        }
        if (keys.stream().anyMatch(key -> !KEY_ARN.matcher(key).matches())) {
            throw new IllegalArgumentException("Configure AWS KMS wrapping keys as full key ARNs");
        }
        this.allowedKeys = Set.copyOf(keys);
    }

    boolean configured() { return !activeKey.isBlank(); }

    GeneratedKey generate(Map<String, String> context) {
        try {
            var result = client(activeKey).generateDataKey(request -> request.keyId(activeKey)
                    .keySpec(DataKeySpec.AES_256).encryptionContext(context));
            return new GeneratedKey(activeKey, result.plaintext().asByteArray(), result.ciphertextBlob().asByteArray());
        } catch (SdkException failure) {
            throw new StorageCredentialException("AWS KMS could not generate the storage credential data key");
        }
    }

    byte[] unwrap(String key, byte[] wrapped, Map<String, String> context) {
        try {
            return client(key).decrypt(request -> request.keyId(key).ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                    .encryptionContext(context)).plaintext().asByteArray();
        } catch (SdkException failure) {
            throw new StorageCredentialException("AWS KMS could not unwrap the storage credential data key");
        }
    }

    private KmsClient client(String arn) {
        if (!allowedKeys.contains(arn)) throw new StorageCredentialException("The credential wrapping key is unavailable");
        var match = KEY_ARN.matcher(arn);
        if (!match.matches()) throw new StorageCredentialException("The credential wrapping key is unavailable");
        return clients.computeIfAbsent(match.group(1), region -> KmsClient.builder().region(Region.of(region))
                .credentialsProvider(identity)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(8))
                        .addExecutionInterceptor(StorageSdkErrors.credentials("AWS KMS could not access the storage credential wrapping key")))
                .build());
    }

    @PreDestroy
    void close() {
        try { clients.values().forEach(KmsClient::close); } finally { identity.close(); }
    }

    record GeneratedKey(String keyId, byte[] plaintext, byte[] wrapped) {
        @Override public String toString() { return "Generated storage data key"; }
    }
}
