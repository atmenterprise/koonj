package uk.appsthatmatter.koonj.api;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

@RestController
@RequestMapping("/api/v1/storage/connections")
class StorageConnectionController {
    record Request(String origin, String path, String region, String endpointUrl, String auth,
                   String keyId, String secretKey, String sessionToken) {}
    record Result(boolean verified, String message) {}

    private final Path mount;
    private final Set<String> allowedS3Endpoints;
    private final Semaphore slots = new Semaphore(4);

    StorageConnectionController(@Value("${koonj.mount:/mnt/koonj}") Path mount,
                                @Value("${koonj.connection.allowed-s3-endpoints:}") String allowedS3Endpoints) {
        this.mount = mount.toAbsolutePath().normalize();
        this.allowedS3Endpoints = Arrays.stream(allowedS3Endpoints.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(StorageConnectionController::endpointKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping(value = "/test", consumes = "application/json")
    Result test(@RequestBody Request request) {
        if (!slots.tryAcquire()) return failure("Connection checks are busy. Try again in a moment.");
        try {
            return switch (request.origin() == null ? "" : request.origin()) {
                case "LOCAL", "NAS", "SMB" -> checkDirectory(request);
                case "S3" -> checkBucket(request);
                default -> failure("Select a supported storage type.");
            };
        } finally {
            slots.release();
        }
    }

    private Result checkDirectory(Request request) {
        if (!"host-mount".equals(request.auth())) {
            return failure("Mount the share on the Koonj server and select Use host mount to verify it.");
        }
        if (blank(request.path())) return failure("Enter an absolute server directory.");
        try {
            Path path = Path.of(request.path()).normalize();
            if (!path.isAbsolute() || !path.startsWith(mount)) {
                return failure("Choose a directory under the Koonj server storage mount.");
            }
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(mount.toRealPath()) || !Files.isDirectory(realPath)) {
                return failure("The path is not a directory within the Koonj server storage mount.");
            }
            try (var directory = Files.newDirectoryStream(realPath)) {
                directory.iterator().hasNext();
                return new Result(true, "Koonj can read the mounted directory.");
            }
        } catch (IOException | DirectoryIteratorException | IllegalArgumentException | SecurityException failure) {
            return failure("Koonj could not read the directory. Check that the path is mounted and readable.");
        }
    }

    private Result checkBucket(Request request) {
        if (request.path() == null || !request.path().matches("s3://[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || request.path().contains("..") || request.path().matches("s3://[0-9]+(\\.[0-9]+){3}")
                || request.region() == null || !request.region().matches("[a-z][a-z0-9]*(-[a-z0-9]+)+-[0-9]+")) {
            return failure("Enter a valid S3 bucket name and region.");
        }
        boolean custom = !blank(request.endpointUrl());
        if (!"aws-keys".equals(request.auth()) && !"aws-default".equals(request.auth())) {
            return failure("Use AWS access keys or AWS default credentials to test this connection.");
        }
        if (custom && !"aws-keys".equals(request.auth())) {
            return failure("Enter access keys for this S3-compatible endpoint.");
        }
        var builder = S3Client.builder().region(Region.of(request.region())).forcePathStyle(custom)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(6)).apiCallAttemptTimeout(Duration.ofSeconds(4)));
        try {
            if (custom) {
                String endpoint = endpointKey(request.endpointUrl());
                if (!allowedS3Endpoints.contains(endpoint)) return failure("This S3 endpoint is not enabled on the Koonj server.");
                builder.endpointOverride(URI.create(endpoint));
            }
            if ("aws-keys".equals(request.auth())) {
                if (blank(request.keyId()) || blank(request.secretKey())) return failure("Enter the access key ID and secret access key.");
                var credential = blank(request.sessionToken()) ? AwsBasicCredentials.create(request.keyId(), request.secretKey())
                        : AwsSessionCredentials.create(request.keyId(), request.secretKey(), request.sessionToken());
                builder.credentialsProvider(StaticCredentialsProvider.create(credential));
            }
            try (var client = builder.build()) {
                client.headBucket(bucket -> bucket.bucket(request.path().substring("s3://".length())));
                return new Result(true, "The bucket is reachable and the supplied credentials can access it.");
            }
        } catch (S3Exception failure) {
            return failure("Bucket access could not be verified (HTTP " + failure.statusCode() + "). Check the bucket, region, and permissions.");
        } catch (SdkException | IllegalArgumentException failure) {
            return failure("Koonj could not connect to S3. Check the endpoint URL, credentials, and network access.");
        }
    }

    private static String endpointKey(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid endpoint URL");
        }
        return uri.normalize().toString().replaceAll("/+$", "");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Result failure(String message) {
        return new Result(false, message);
    }
}
