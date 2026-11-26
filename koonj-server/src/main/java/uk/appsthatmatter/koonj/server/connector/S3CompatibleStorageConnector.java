package uk.appsthatmatter.koonj.server.connector;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import uk.appsthatmatter.koonj.server.storageauth.CredentialTarget;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.services.s3.model.*;
import uk.appsthatmatter.koonj.server.entity.EntryType;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;
import uk.appsthatmatter.koonj.server.entity.StorageAuthMode;
import uk.appsthatmatter.koonj.server.security.ProjectScope;
import uk.appsthatmatter.koonj.server.storageauth.S3CredentialFactory;
import uk.appsthatmatter.koonj.server.storageauth.StorageSdkErrors;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class S3CompatibleStorageConnector implements StorageConnector, AutoCloseable {
    private static final Duration METADATA_REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final int MEMORY_FILE_LIMIT = 8 * 1024 * 1024;
    private final S3CredentialFactory credentialFactory;
    private final Map<ClientConfig, Clients> clients = new ConcurrentHashMap<>();

    public S3CompatibleStorageConnector(S3CredentialFactory credentialFactory) {
        this.credentialFactory = credentialFactory;
    }

    private record ClientConfig(CredentialTarget target, StorageAuthMode authMode,
                                String secretRef, String roleArn, String externalId) {
        static ClientConfig from(StorageConnection config) {
            ProjectScope.require(config.getProjectId(), "Storage connection was not found");
            return new ClientConfig(CredentialTarget.from(config), config.getAuthMode(),
                    config.getSecretRef(), config.getAwsRoleArn(), config.getAwsExternalId());
        }
    }

    private final class Clients implements AutoCloseable {
        private final ClientConfig settings;
        private final S3CredentialFactory.Credentials credentials;
        private S3Client sync;
        private S3AsyncClient async;
        private S3TransferManager transfers;

        private Clients(ClientConfig settings) {
            this(settings, credentialFactory.create(settings.target(), settings.authMode(),
                    settings.secretRef(), settings.roleArn(), settings.externalId()));
        }

        private Clients(ClientConfig settings, S3CredentialFactory.Credentials credentials) {
            this.settings = settings;
            this.credentials = credentials;
        }

        private synchronized S3Client sync() {
            if (sync == null) {
                var builder = S3Client.builder().region(Region.of(settings.target().region())).forcePathStyle(settings.target().pathStyle())
                        .credentialsProvider(credentials.provider())
                        .overrideConfiguration(options -> options.addExecutionInterceptor(StorageSdkErrors.S3))
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
                if (settings.target().endpoint() != null) builder.endpointOverride(URI.create(settings.target().endpoint()));
                sync = builder.build();
            }
            return sync;
        }

        private synchronized S3TransferManager transfers() {
            if (transfers == null) {
                var builder = S3AsyncClient.builder().region(Region.of(settings.target().region())).forcePathStyle(settings.target().pathStyle())
                        .credentialsProvider(credentials.provider())
                        .overrideConfiguration(options -> options.addExecutionInterceptor(StorageSdkErrors.S3))
                        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                        .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(64).maxPendingConnectionAcquires(256))
                        .multipartEnabled(true)
                        .multipartConfiguration(multipart -> multipart.thresholdInBytes(16L * 1024 * 1024)
                                .minimumPartSizeInBytes(8L * 1024 * 1024).apiCallBufferSizeInBytes(32L * 1024 * 1024));
                if (settings.target().endpoint() != null) builder.endpointOverride(URI.create(settings.target().endpoint()));
                async = builder.build();
                try {
                    transfers = S3TransferManager.builder().s3Client(async).build();
                } catch (RuntimeException failure) {
                    async.close();
                    async = null;
                    throw failure;
                }
            }
            return transfers;
        }

        @Override
        public void close() {
            try (credentials; var syncToClose = sync; var asyncToClose = async; var transfersToClose = transfers) {
                // Close the transfer manager before its client, and clients before their credential provider.
            }
        }
    }

    @Override
    @PreDestroy
    public void close() {
        clients.values().forEach(Clients::close);
    }

    @Override
    public boolean supports(ProviderType type) {
        return switch (type) {
            case AWS_S3, MINIO, RUSTFS, SCALITY, CUSTOM_S3 -> true;
            default -> false;
        };
    }

    @Override
    public boolean objectStorage() { return true; }

    private S3Client client(StorageConnection config) {
        return clients.computeIfAbsent(ClientConfig.from(config), Clients::new).sync();
    }

    private S3TransferManager transferManager(StorageConnection config) {
        return clients.computeIfAbsent(ClientConfig.from(config), Clients::new).transfers();
    }

    @Override
    public void checkConnectivity(StorageConnection config) {
        client(config).listObjectsV2(request -> request.bucket(config.getBasePathOrBucket())
                .prefix(StoragePaths.objectKey(config, "")).maxKeys(1)
                .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)));
    }

    @Override
    public void testConnection(StorageConnection config) throws IOException {
        try (var draft = new Clients(ClientConfig.from(config))) {
            testAccess(config, draft.sync());
        }
    }

    @Override
    public void testConnection(StorageConnection config, AwsCredentials credentials) throws IOException {
        var supplied = new S3CredentialFactory.Credentials(StaticCredentialsProvider.create(credentials), () -> {});
        try (var draft = new Clients(ClientConfig.from(config), supplied)) {
            testAccess(config, draft.sync());
        }
    }

    private static void testAccess(StorageConnection config, S3Client client) throws IOException {
        String bucket = config.getBasePathOrBucket();
        String root = StoragePaths.objectKey(config, "");
        var sample = client.listObjectsV2(request -> request.bucket(bucket).prefix(root).maxKeys(1)
                .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT))).contents();
        if (config.getRole() != StorageRole.TARGET && !sample.isEmpty() && sample.getFirst().key().startsWith(root)
                && !Set.of("GLACIER", "DEEP_ARCHIVE").contains(sample.getFirst().storageClassAsString())) {
            try (var response = client.getObject(request -> request.bucket(bucket).key(sample.getFirst().key())
                    .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)))) {
                response.read();
                response.abort();
            }
        }
        if (config.getRole() != StorageRole.SOURCE) {
            String key = StoragePaths.objectKey(config, StoragePaths.ARCHIVE_STAGING_PREFIX + UUID.randomUUID());
            client.putObject(request -> request.bucket(bucket).key(key).storageClass(StorageClass.STANDARD)
                            .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)),
                    RequestBody.fromBytes(new byte[]{42}));
            try (InputStream stream = client.getObject(request -> request.bucket(bucket).key(key)
                    .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)))) {
                if (stream.read() != 42 || stream.read() != -1) throw new IOException("S3 probe readback failed");
            } finally {
                client.deleteObject(request -> request.bucket(bucket).key(key)
                        .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)));
            }
        }
    }

    @Override
    public List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) {
        List<ScannedFileInfo> files = new ArrayList<>();
        visitFiles(config, subPath, files::add);
        return files;
    }

    @Override
    public void visitFiles(StorageConnection config, String subPath, Consumer<ScannedFileInfo> visitor) {
        String children = subPath.isEmpty() || subPath.endsWith("/") ? subPath : subPath + "/";
        String root = StoragePaths.objectKey(config, "");
        var request = ListObjectsV2Request.builder().bucket(config.getBasePathOrBucket())
                .prefix(root + subPath).build();
        for (S3Object item : client(config).listObjectsV2Paginator(request).contents()) {
            if (!item.key().startsWith(root) || item.key().length() == root.length()) continue;
            String key = item.key().substring(root.length());
            if (StoragePaths.isArchiveStagingKey(key) || !(key.equals(subPath) || key.startsWith(children))) continue;
            EntryType type = key.endsWith("/") && item.size() == 0 ? EntryType.DIRECTORY : EntryType.FILE;
            visitor.accept(new ScannedFileInfo(key, item.size(), item.lastModified(), item.storageClassAsString(), type, null));
        }
    }

    @Override
    public void visitFolderFiles(StorageConnection config, String folder, Consumer<ScannedFileInfo> visitor) {
        visitFiles(config, folder, visitor);
    }

    @Override
    public Optional<ScannedFileInfo> statFile(StorageConnection config, String key) {
        try {
            var head = client(config).headObject(request -> request.bucket(config.getBasePathOrBucket()).key(StoragePaths.objectKey(config, key))
                    .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)));
            EntryType type = key.endsWith("/") && head.contentLength() == 0 ? EntryType.DIRECTORY : EntryType.FILE;
            return Optional.of(new ScannedFileInfo(key, head.contentLength(), head.lastModified(),
                    Objects.requireNonNullElse(head.storageClassAsString(), "STANDARD"), type, null));
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return Optional.empty();
            throw failure;
        }
    }

    @Override
    public FileAttributes fileAttributes(StorageConnection config, String key) {
        var head = client(config).headObject(request -> request.bucket(config.getBasePathOrBucket()).key(StoragePaths.objectKey(config, key))
                .overrideConfiguration(options -> options.apiCallTimeout(METADATA_REQUEST_TIMEOUT)));
        return FileAttributes.from(null, null, null, head.metadata());
    }

    @Override
    public Optional<Instant> requestColdRestore(StorageConnection config, String key) {
        String bucket = config.getBasePathOrBucket();
        S3Client client = client(config);
        var head = client.headObject(request -> request.bucket(bucket).key(StoragePaths.objectKey(config, key))
                .overrideConfiguration(configOverride -> configOverride.apiCallTimeout(METADATA_REQUEST_TIMEOUT)));
        StorageClass storageClass = head.storageClass();
        if (storageClass != StorageClass.GLACIER && storageClass != StorageClass.DEEP_ARCHIVE) return Optional.empty();
        String restore = head.restore();
        if (restore != null && restore.contains("ongoing-request=\"false\"")) return Optional.empty();
        if (restore == null || !restore.contains("ongoing-request=\"true\"")) {
            try {
                client.restoreObject(request -> request.bucket(bucket).key(StoragePaths.objectKey(config, key))
                        .overrideConfiguration(configOverride -> configOverride.apiCallTimeout(METADATA_REQUEST_TIMEOUT))
                        .restoreRequest(restoreRequest -> restoreRequest.days(2)
                                .glacierJobParameters(parameters -> parameters.tier(Tier.STANDARD))));
            } catch (S3Exception failure) {
                if (failure.awsErrorDetails() == null
                        || !"RestoreAlreadyInProgress".equals(failure.awsErrorDetails().errorCode())) throw failure;
            }
        }
        return Optional.of(Instant.now().plus(Duration.ofHours(
                storageClass == StorageClass.DEEP_ARCHIVE ? 12 : 5)));
    }

    @Override
    public InputStream openReadStream(StorageConnection config, String relativePath) {
        return client(config).getObject(request -> request.bucket(config.getBasePathOrBucket()).key(StoragePaths.objectKey(config, relativePath)));
    }

    @Override
    public InputStream openTransferStream(StorageConnection config, String key, long size) throws IOException {
        if (size <= MEMORY_FILE_LIMIT) return openReadStream(config, key);
        var request = GetObjectRequest.builder().bucket(config.getBasePathOrBucket()).key(StoragePaths.objectKey(config, key)).build();
        Path directory = createStagingDirectory("koonj-download-");
        Path staging = directory.resolve("object");
        try {
            await(transferManager(config).downloadFile(download -> download.getObjectRequest(request)
                    .destination(staging)).completionFuture());
            if (Files.size(staging) != size) throw new IOException("Archive object length changed during transfer");
            return new FilterInputStream(Files.newInputStream(staging)) {
                @Override
                public void close() throws IOException {
                    try { super.close(); } finally { removeStaging(staging, directory); }
                }
            };
        } catch (IOException | RuntimeException failure) {
            removeStaging(staging, directory);
            throw failure;
        }
    }

    @Override
    public void writeStream(StorageConnection config, String key, InputStream stream,
                            long length, String storageClass) throws IOException {
        var request = PutObjectRequest.builder().bucket(config.getBasePathOrBucket()).key(StoragePaths.objectKey(config, key)).storageClass(storageClass).build();
        var manager = transferManager(config);
        if (length <= MEMORY_FILE_LIMIT) {
            byte[] bytes = stream.readNBytes((int) length);
            if (bytes.length != length) throw new IOException("Source ended before its cataloged length");
            requireEnd(stream);
            await(manager.upload(call -> call.putObjectRequest(request)
                    .requestBody(AsyncRequestBody.fromBytesUnsafe(bytes))).completionFuture());
            return;
        }
        // A file-backed request can replay parts on retries without rereading the original stream.
        Path directory = createStagingDirectory("koonj-upload-");
        Path staging = directory.resolve("object");
        try {
            try (var output = Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[1024 * 1024];
                for (long remaining = length; remaining > 0;) {
                    int count = stream.readNBytes(buffer, 0, (int) Math.min(remaining, buffer.length));
                    if (count == 0) throw new IOException("Source ended before its cataloged length");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
                requireEnd(stream);
            }
            await(manager.uploadFile(call -> call.putObjectRequest(request).source(staging)).completionFuture());
        } finally {
            removeStaging(staging, directory);
        }
    }

    private static Path createStagingDirectory(String prefix) throws IOException {
        try {
            return Files.createTempDirectory(prefix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException failure) {
            throw new IOException("Transfer staging requires a temporary filesystem with POSIX permissions", failure);
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }

    private static void removeStaging(Path... paths) {
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException failure) {
                System.getLogger(S3CompatibleStorageConnector.class.getName()).log(System.Logger.Level.WARNING,
                        "Could not remove local transfer staging", failure);
            }
        }
    }

    private static void requireEnd(InputStream stream) throws IOException {
        if (stream.read() != -1) throw new IOException("Source grew during transfer");
    }

    @Override
    public void deleteFile(StorageConnection config, String relativePath) {
        client(config).deleteObject(request -> request.bucket(config.getBasePathOrBucket()).key(StoragePaths.objectKey(config, relativePath)));
    }
}
