package uk.appsthatmatter.koonj.server.connector;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import uk.appsthatmatter.koonj.server.entity.ProviderType;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.entity.StorageRole;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

@Component
public class S3CompatibleStorageConnector implements StorageConnector {
    private static final int PART_BYTES = 8 * 1024 * 1024;

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
        var builder = S3Client.builder().region(Region.of(Objects.requireNonNullElse(config.getRegion(), "us-east-1")))
                .forcePathStyle(config.isPathStyleAccessEnabled())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (config.getEndpointUrl() != null) builder.endpointOverride(URI.create(config.getEndpointUrl()));
        if (config.getAccessKeyOrUsername() != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.getAccessKeyOrUsername(), config.getSecretKeyOrPassword())));
        }
        return builder.build();
    }

    @Override
    public void testConnection(StorageConnection config) throws IOException {
        String bucket = config.getBasePathOrBucket();
        try (S3Client client = client(config)) {
            client.headBucket(request -> request.bucket(bucket));
            var sample = client.listObjectsV2(request -> request.bucket(bucket).maxKeys(1)).contents();
            if (config.getRole() != StorageRole.TARGET && !sample.isEmpty()
                    && !Set.of("GLACIER", "DEEP_ARCHIVE").contains(sample.getFirst().storageClassAsString())) {
                try (var response = client.getObject(request -> request.bucket(bucket).key(sample.getFirst().key()))) {
                    response.read();
                    response.abort();
                }
            }
        }
        if (config.getRole() != StorageRole.SOURCE) {
            String key = ".koonj-probe/" + UUID.randomUUID();
            byte[] probe = {42};
            writeStream(config, key, new java.io.ByteArrayInputStream(probe), probe.length, "STANDARD");
            try (InputStream stream = openReadStream(config, key)) {
                if (stream.read() != 42 || stream.read() != -1) throw new IOException("S3 probe readback failed");
            } finally {
                deleteFile(config, key);
            }
        }
    }

    @Override
    public List<ScannedFileInfo> listFiles(StorageConnection config, String subPath) {
        List<ScannedFileInfo> files = new ArrayList<>();
        try (S3Client client = client(config)) {
            var request = ListObjectsV2Request.builder().bucket(config.getBasePathOrBucket()).prefix(subPath).build();
            for (S3Object item : client.listObjectsV2Paginator(request).contents()) {
                String key = item.key();
                if (!key.endsWith("/") && (subPath.isEmpty() || key.equals(subPath) || key.startsWith(subPath + "/"))) {
                    files.add(new ScannedFileInfo(key, item.size(), item.lastModified(), item.storageClassAsString()));
                }
            }
        }
        return files;
    }

    @Override
    public InputStream openReadStream(StorageConnection config, String relativePath) {
        S3Client client = client(config);
        try {
            var response = client.getObject(request -> request.bucket(config.getBasePathOrBucket()).key(relativePath));
            return new FilterInputStream(response) {
                @Override
                public void close() {
                    try {
                        response.abort();
                    } finally {
                        client.close();
                    }
                }
            };
        } catch (RuntimeException failure) {
            client.close();
            throw failure;
        }
    }

    @Override
    public void writeStream(StorageConnection config, String key, InputStream stream,
                            long length, String storageClass) throws IOException {
        String bucket = config.getBasePathOrBucket();
        try (S3Client client = client(config)) {
            if (length <= PART_BYTES) {
                byte[] bytes = readPart(stream, (int) length);
                requireEnd(stream);
                client.putObject(request -> request.bucket(bucket).key(key).storageClass(storageClass),
                        RequestBody.fromBytes(bytes));
                return;
            }
            long requiredPartSize = Math.max(PART_BYTES, Math.ceilDiv(length, 10_000));
            if (requiredPartSize > 64 * 1024 * 1024) throw new IOException("Object exceeds upload size limit");
            int partSize = (int) requiredPartSize;
            String uploadId = client.createMultipartUpload(request ->
                    request.bucket(bucket).key(key).storageClass(storageClass)).uploadId();
            try {
                List<CompletedPart> parts = new ArrayList<>();
                for (long remaining = length; remaining > 0; ) {
                    byte[] bytes = readPart(stream, (int) Math.min(remaining, partSize));
                    int number = parts.size() + 1;
                    var result = client.uploadPart(request ->
                            request.bucket(bucket).key(key).uploadId(uploadId).partNumber(number),
                            RequestBody.fromBytes(bytes));
                    parts.add(CompletedPart.builder().partNumber(number).eTag(result.eTag()).build());
                    remaining -= bytes.length;
                }
                requireEnd(stream);
                client.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId)
                        .multipartUpload(upload -> upload.parts(parts)));
            } catch (IOException | RuntimeException failure) {
                try {
                    client.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(uploadId));
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }
    }

    private byte[] readPart(InputStream stream, int length) throws IOException {
        byte[] bytes = stream.readNBytes(length);
        if (bytes.length != length) throw new IOException("Source ended before its cataloged length");
        return bytes;
    }

    private void requireEnd(InputStream stream) throws IOException {
        if (stream.read() != -1) throw new IOException("Source grew during transfer");
    }

    @Override
    public void deleteFile(StorageConnection config, String relativePath) {
        try (S3Client client = client(config)) {
            client.deleteObject(request -> request.bucket(config.getBasePathOrBucket()).key(relativePath));
        }
    }
}
