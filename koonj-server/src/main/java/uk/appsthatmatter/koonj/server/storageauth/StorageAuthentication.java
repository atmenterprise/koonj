package uk.appsthatmatter.koonj.server.storageauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.appsthatmatter.koonj.server.dto.ConnectionRequest;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageAuthMode;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class StorageAuthentication {
    private static final Pattern ROLE_ARN = Pattern.compile("arn:(aws(?:-[a-z0-9-]+)?):iam::([0-9]{12}):role/[A-Za-z0-9+=,.@_/!-]+");
    private static final Pattern AWS_BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern REGION = Pattern.compile("[a-z0-9]++(?:-[a-z0-9]++)*+");

    private final String externalIdNamespace;
    private final boolean allowDefaultChain;
    private final boolean allowInsecureEndpoints;

    public StorageAuthentication(
            @Value("${KOONJ_AWS_EXTERNAL_ID_NAMESPACE:}") String externalIdNamespace,
            @Value("${KOONJ_AWS_ALLOW_DEFAULT_CHAIN:true}") boolean allowDefaultChain,
            @Value("${KOONJ_STORAGE_ALLOW_INSECURE_ENDPOINTS:false}") boolean allowInsecureEndpoints) {
        this.externalIdNamespace = externalIdNamespace;
        this.allowDefaultChain = allowDefaultChain;
        this.allowInsecureEndpoints = allowInsecureEndpoints;
    }

    public String externalId(UUID project, String roleArn) {
        var match = ROLE_ARN.matcher(roleArn == null ? "" : roleArn);
        if (roleArn == null || roleArn.length() > 255 || !match.matches()) {
            throw new IllegalArgumentException("Enter a valid IAM role ARN");
        }
        if (externalIdNamespace.isBlank()) {
            throw new IllegalArgumentException("The deployment must configure an AWS external ID namespace to use IAM roles");
        }
        String binding = externalIdNamespace + ":" + project + ":" + match.group(1) + ":" + match.group(2);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(binding.getBytes(StandardCharsets.UTF_8));
            return "koonj-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public void configure(StorageConnection connection, ConnectionRequest request, boolean objectStorage) {
        ProviderType provider = request.providerType();
        StorageAuthMode mode = request.authMode();
        if (mode == null && connection.getProviderType() == provider) mode = connection.getAuthMode();
        if (mode == null) {
            if (!objectStorage) mode = StorageAuthMode.HOST_IDENTITY;
            else if (provider != ProviderType.AWS_S3) mode = request.secretRef() == null
                    ? StorageAuthMode.ENCRYPTED_CREDENTIALS : StorageAuthMode.SECRET_REF;
            else mode = allowDefaultChain ? StorageAuthMode.AWS_DEFAULT_CHAIN : StorageAuthMode.AWS_ASSUME_ROLE;
        }
        String reference = request.secretRef();
        String role = request.awsRoleArn();
        String endpoint = request.endpointUrl();
        String region = request.region();
        if (region != null && !REGION.matcher(region).matches()) {
            throw new IllegalArgumentException("Enter a valid S3 region");
        }
        if (objectStorage && request.basePathOrBucket().length() > 255) {
            throw new IllegalArgumentException("Storage bucket names must be 255 characters or fewer");
        }
        if (objectStorage && provider != ProviderType.AWS_S3) {
            validateEndpoint(endpoint);
        } else if (endpoint != null) {
            throw new IllegalArgumentException("An endpoint override requires a custom S3 provider");
        }
        if (objectStorage && provider == ProviderType.AWS_S3
                && !AWS_BUCKET.matcher(request.basePathOrBucket()).matches()) {
            throw new IllegalArgumentException("Enter an AWS S3 bucket name");
        }
        if ((mode == StorageAuthMode.SECRET_REF) != (reference != null)
                || (mode == StorageAuthMode.AWS_ASSUME_ROLE) != (role != null)
                || (request.credentials() != null && mode != StorageAuthMode.ENCRYPTED_CREDENTIALS)) {
            throw new IllegalArgumentException("Supply only the authentication settings required by the selected mode");
        }
        boolean supported = switch (mode) {
            case HOST_IDENTITY -> !objectStorage;
            case AWS_DEFAULT_CHAIN -> provider == ProviderType.AWS_S3 && allowDefaultChain;
            case AWS_ASSUME_ROLE -> provider == ProviderType.AWS_S3;
            case SECRET_REF, ENCRYPTED_CREDENTIALS -> objectStorage;
            case MIGRATION_REQUIRED -> false;
        };
        if (!supported) throw new IllegalArgumentException("Select a supported storage authentication mode");

        if (reference != null) SecretReference.parse(reference);
        if (mode == StorageAuthMode.AWS_ASSUME_ROLE && request.objectPrefix() != null
                && !request.objectPrefix().matches("[A-Za-z0-9_./-]+")) {
            throw new IllegalArgumentException("The IAM role storage prefix contains unsupported characters");
        }
        String externalId = role == null ? null : externalId(connection.getProjectId(), role);
        connection.setAuthMode(mode);
        connection.setSecretRef(reference);
        connection.setAwsRoleArn(role);
        connection.setAwsExternalId(externalId);
    }

    public boolean allowsDefaultChain() {
        return allowDefaultChain;
    }

    private void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Enter a valid S3 endpoint URL");
        }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || allowInsecureEndpoints && "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Enter an HTTPS S3 endpoint URL without credentials, query or fragment");
        }
    }
}
