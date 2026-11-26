package uk.appsthatmatter.koonj.server.storageauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import uk.appsthatmatter.koonj.server.dto.StorageCredentialInput;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class StorageCredentialEncryption {
    private static final int FORMAT = 1;
    private static final int KEY_BYTES = 32;
    private static final String PURPOSE = "koonj-storage-credential-v1";
    private final ObjectMapper json;
    private final AwsKmsWrappingKeys kms;
    private final String provider;
    private final String activeLocalKey;
    private final Map<String, SecretKeySpec> localKeys;
    private final SecureRandom random = new SecureRandom();

    StorageCredentialEncryption(ObjectMapper json, AwsKmsWrappingKeys kms,
            @Value("${KOONJ_STORAGE_KEY_PROVIDER:disabled}") String provider,
            @Value("${KOONJ_STORAGE_LOCAL_ACTIVE_KEY:}") String activeLocalKey,
            @Value("${KOONJ_STORAGE_LOCAL_KEYS:}") String localKeys) {
        this.json = json;
        this.kms = kms;
        this.provider = provider;
        this.activeLocalKey = activeLocalKey;
        this.localKeys = parseKeys(localKeys);
        if (!List.of("disabled", "local", "aws-kms").contains(provider)
                || ("local".equals(provider) && !this.localKeys.containsKey(activeLocalKey))
                || ("aws-kms".equals(provider) && !kms.configured())) {
            throw new IllegalArgumentException("Configure a valid storage credential wrapping key provider");
        }
    }

    public boolean enabled() { return !"disabled".equals(provider); }

    public byte[] encrypt(CredentialTarget target, StorageCredentialInput credential) {
        requireEnabled();
        byte[] plaintext = null;
        byte[] dataKey = null;
        try {
            UUID revision = UUID.randomUUID();
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            String wrappingKey;
            byte[] wrappedKey;
            if ("aws-kms".equals(provider)) {
                var generated = kms.generate(context(target, revision));
                dataKey = generated.plaintext();
                wrappedKey = generated.wrapped();
                wrappingKey = generated.keyId();
            } else {
                dataKey = new byte[KEY_BYTES];
                random.nextBytes(dataKey);
                wrappingKey = "local:" + activeLocalKey;
                wrappedKey = localWrap(Cipher.ENCRYPT_MODE, activeLocalKey, dataKey);
            }
            plaintext = json.writeValueAsBytes(json.createObjectNode()
                    .put("accessKeyId", credential.accessKeyId()).put("secretAccessKey", credential.secretAccessKey()));
            byte[] ciphertext = payload(Cipher.ENCRYPT_MODE, target, revision, nonce, dataKey, plaintext);
            return json.writeValueAsBytes(new Envelope(FORMAT, revision, wrappingKey, wrappedKey, nonce, ciphertext));
        } catch (GeneralSecurityException | IOException failure) {
            throw new StorageCredentialException("Could not encrypt the storage credential");
        } finally {
            clear(plaintext);
            clear(dataKey);
        }
    }

    CredentialSnapshot decrypt(CredentialTarget target, byte[] encoded) {
        requireEnabled();
        byte[] plaintext = null;
        byte[] dataKey = null;
        try {
            Envelope envelope = json.readValue(encoded, Envelope.class);
            if (envelope == null || envelope.version() != FORMAT || envelope.revision() == null
                    || envelope.keyId() == null || envelope.nonce() == null || envelope.nonce().length != 12
                    || envelope.wrappedKey() == null || envelope.ciphertext() == null) {
                throw new StorageCredentialException("Stored storage credential has an invalid envelope");
            }
            String key = envelope.keyId();
            dataKey = key.startsWith("local:")
                    ? localWrap(Cipher.DECRYPT_MODE, key.substring("local:".length()), envelope.wrappedKey())
                    : kms.unwrap(key, envelope.wrappedKey(), context(target, envelope.revision()));
            plaintext = payload(Cipher.DECRYPT_MODE, target, envelope.revision(), envelope.nonce(), dataKey, envelope.ciphertext());
            var fields = json.readTree(plaintext);
            return new CredentialSnapshot(envelope.revision().toString(),
                    AwsBasicCredentials.create(fields.path("accessKeyId").asText(), fields.path("secretAccessKey").asText()), null);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException failure) {
            throw new StorageCredentialException("Could not decrypt the storage credential for this connection");
        } finally {
            clear(plaintext);
            clear(dataKey);
        }
    }

    private byte[] payload(int operation, CredentialTarget target, UUID revision, byte[] nonce, byte[] key, byte[] data)
            throws GeneralSecurityException, IOException {
        if (key.length != KEY_BYTES) throw new GeneralSecurityException();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(operation, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(binding(target, revision));
        return cipher.doFinal(data);
    }

    private static byte[] binding(CredentialTarget target, UUID revision) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(buffer)) {
            output.writeUTF(PURPOSE);
            output.writeUTF(target.project().toString());
            output.writeUTF(target.connection().toString());
            output.writeUTF(target.provider().name());
            output.writeUTF(target.bucket());
            output.writeUTF(Objects.toString(target.endpoint(), ""));
            output.writeUTF(target.region());
            output.writeUTF(Objects.toString(target.prefix(), ""));
            output.writeBoolean(target.pathStyle());
            output.writeUTF(revision.toString());
        }
        return buffer.toByteArray();
    }

    private byte[] localWrap(int operation, String keyId, byte[] data) throws GeneralSecurityException {
        SecretKeySpec key = localKeys.get(keyId);
        if (key == null) throw new StorageCredentialException("The credential wrapping key is unavailable");
        // This is the explicit RFC 3394 transformation for the AESWrap alias used by existing envelopes.
        var cipher = Cipher.getInstance("AES/KW/NoPadding");
        cipher.init(operation, key);
        return cipher.doFinal(data);
    }

    private static Map<String, String> context(CredentialTarget target, UUID revision) {
        return Map.of("purpose", PURPOSE, "project", target.project().toString(),
                "connection", target.connection().toString(), "revision", revision.toString());
    }

    private void requireEnabled() {
        if (!enabled()) throw new StorageCredentialException("Encrypted storage credentials are disabled for this deployment");
    }

    private static Map<String, SecretKeySpec> parseKeys(String configuration) {
        if (configuration.isBlank()) return Map.of();
        var keys = new HashMap<String, SecretKeySpec>();
        for (String entry : configuration.split(",", -1)) {
            String[] parts = entry.strip().split(":", 2);
            byte[] decoded = null;
            try {
                if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{1,40}") || keys.containsKey(parts[0])) {
                    throw new IllegalArgumentException();
                }
                decoded = Base64.getDecoder().decode(parts[1]);
                if (decoded.length != KEY_BYTES) throw new IllegalArgumentException();
                keys.put(parts[0], new SecretKeySpec(decoded, "AES"));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Local storage wrapping keys require unique IDs and Base64-encoded 32-byte keys");
            } finally {
                clear(decoded);
            }
        }
        return Map.copyOf(keys);
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private record Envelope(int version, UUID revision, String keyId, byte[] wrappedKey, byte[] nonce, byte[] ciphertext) {}
}
