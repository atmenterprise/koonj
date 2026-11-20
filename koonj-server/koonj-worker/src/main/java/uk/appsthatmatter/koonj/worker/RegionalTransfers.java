package uk.appsthatmatter.koonj.worker;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

@Component
class RegionalTransfers implements AutoCloseable {
    record Target(String region, String bucket, String key, String version) {}

    private final int slots;
    private final Semaphore access;
    private final int connections;
    private final long nativeBytes;
    private final long partBytes;
    private final String endpoint;
    private final boolean pathStyle;
    private String currentRegion;
    private S3AsyncClient client;
    private S3TransferManager manager;

    RegionalTransfers(@Value("${koonj.worker.slots:2}") int slots,
                      @Value("${koonj.s3.connections:8}") int connections,
                      @Value("${koonj.s3.native-bytes:1073741824}") long nativeBytes,
                      @Value("${koonj.s3.part-bytes:8388608}") long partBytes,
                      @Value("${koonj.s3.endpoint:}") String endpoint,
                      @Value("${koonj.s3.path-style:false}") boolean pathStyle) {
        if (slots < 1 || connections < 1 || partBytes < 5L * 1024 * 1024 || nativeBytes < (1L << 30) || nativeBytes <= partBytes) {
            throw new IllegalArgumentException("Invalid transfer capacity");
        }
        this.slots = slots;
        this.access = new Semaphore(slots, true);
        this.connections = connections;
        this.nativeBytes = nativeBytes;
        this.partBytes = partBytes;
        this.endpoint = endpoint;
        this.pathStyle = pathStyle;
    }

    String upload(Target target, Path snapshot) throws InterruptedException {
        return transfer(target.region(), tm -> tm.uploadFile(file -> file.source(snapshot)
                .putObjectRequest(put -> put.bucket(target.bucket()).key(target.key())))
                .completionFuture().join().response().versionId());
    }

    void download(Target target, Path destination, long expectedBytes) throws InterruptedException {
        transfer(target.region(), tm -> {
            var head = client.headObject(get -> get.bucket(target.bucket()).key(target.key())
                    .versionId(target.version())).join();
            if (head.contentLength() != expectedBytes || head.eTag() == null) {
                throw new IllegalStateException("Stored object differs from the reserved download");
            }
            return tm.downloadFile(file -> file.destination(destination)
                    .getObjectRequest(get -> get.bucket(target.bucket()).key(target.key())
                            .versionId(target.version()).ifMatch(head.eTag()))).completionFuture().join();
        });
    }

    private <T> T transfer(String region, Function<S3TransferManager, T> action) throws InterruptedException {
        access.acquire();
        if (!region.equals(currentRegion)) {
            access.release();
            access.acquire(slots);
            try {
                if (!region.equals(currentRegion)) open(region);
            } catch (RuntimeException | Error failure) {
                access.release(slots);
                throw failure;
            }
            access.release(slots - 1);
        }
        try {
            // Wait for the native transfer to settle before returning its slot.
            return action.apply(manager);
        } finally {
            access.release();
        }
    }

    private void open(String region) {
        closeClient();
        var builder = S3AsyncClient.crtBuilder().region(Region.of(region)).maxConcurrency(connections)
                .maxNativeMemoryLimitInBytes(nativeBytes).minimumPartSizeInBytes(partBytes)
                .thresholdInBytes(partBytes).forcePathStyle(pathStyle);
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        client = builder.build();
        try {
            manager = S3TransferManager.builder().s3Client(client).build();
            currentRegion = region;
        } catch (RuntimeException | Error failure) {
            closeClient();
            throw failure;
        }
    }

    private void closeClient() {
        currentRegion = null;
        if (manager != null) manager.close();
        if (client != null) client.close();
        manager = null;
        client = null;
    }

    @Override
    public void close() {
        access.acquireUninterruptibly(slots);
        try {
            closeClient();
        } finally {
            access.release(slots);
        }
    }
}
