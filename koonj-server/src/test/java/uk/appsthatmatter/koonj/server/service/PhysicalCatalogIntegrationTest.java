package uk.appsthatmatter.koonj.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.FileResponse;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.TransferRequest;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Operation.*;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Status.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:physical-catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PhysicalCatalogIntegrationTest {
    @TempDir Path root;
    @Autowired StorageConnectionRepository connections;
    @Autowired FileMetadataRepository files;
    @Autowired CatalogScanService catalog;
    @Autowired FileTransferService transfers;
    @Autowired JobService jobs;
    @Autowired BucketFixture bucketFixture;
    private TestBucket s3;
    private StorageConnection source;
    private StorageConnection bucket;

    @BeforeEach
    void locations() {
        s3 = bucketFixture.connector();
        s3.reset();
        source = connection(ProviderType.LOCAL, root.toString());
        bucket = connection(ProviderType.MINIO, "archive");
    }

    @Test
    void scansPhysicalEntriesAndPrunesOnlyTheScannedLocation() throws IOException {
        Path gone = Files.writeString(root.resolve("gone.txt"), "gone soon");
        Files.createDirectory(root.resolve("empty"));
        Path link = Files.createSymbolicLink(root.resolve("link"), Path.of("missing"));
        var other = connection(ProviderType.SMB, Files.createDirectory(root.resolve("share")).toString());
        Files.writeString(root.resolve("share/gone.txt"), "still present on the share");

        completed(catalog.submit(source.getId()));
        completed(catalog.submit(other.getId()));
        assertThat(inventory(source)).extracting(FileResponse::entryType)
                .contains(EntryType.FILE, EntryType.DIRECTORY, EntryType.SYMLINK);
        assertThat(entry(source, "link").symlinkTarget()).endsWith("/missing");

        Files.delete(gone);
        Files.delete(link);
        Files.writeString(link, "now a file");
        completed(catalog.submit(source.getId()));
        assertThat(files.findByConnectionIdAndRelativePath(source.getId(), "gone.txt")).isEmpty();
        assertThat(entry(source, "link").entryType()).isEqualTo(EntryType.FILE);
        assertThat(entry(source, "link").symlinkTarget()).isNull();
        assertThat(entry(other, "gone.txt").entryType()).isEqualTo(EntryType.FILE);

        s3.put("external", new byte[]{1});
        completed(catalog.submit(bucket.getId()));
        assertThat(entry(bucket, "external").entryType()).isEqualTo(EntryType.FILE);
        s3.deleteFile(bucket, "external");
        completed(catalog.submit(bucket.getId()));
        assertThat(inventory(bucket)).isEmpty();
    }

    @Test
    void copyPublishesOnlyAfterVerificationAndKeepsLocalFile() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        completed(catalog.submit(source.getId()));
        var gate = s3.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX);
        var archive = transfers.submit(ARCHIVE, request(source, bucket, null, false));
        try {
            gate.awaitRead();
            assertThat(jobs.get(archive.id()).status()).isEqualTo(RUNNING);
            assertThat(archive.archiveMode()).isEqualTo(ArchiveMode.COPY);
            assertThat(inventory(bucket)).isEmpty();
            assertThat(s3.objects).doesNotContainKey("report");
            assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.FILE);
        } finally {
            gate.release.countDown();
        }
        completed(archive);
        assertThat(original).hasContent("original bytes");
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.ARCHIVED_COPY);
        assertThat(entry(bucket, "report").storageState()).isEqualTo(StorageState.ARCHIVED);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        assertThat(entry(bucket, "report").id()).isNotEqualTo(entry(source, "report").id());
        assertThat(s3.objects.keySet()).containsExactly("report");

        completed(catalog.submit(source.getId()));
        completed(catalog.submit(bucket.getId()));
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.ARCHIVED_COPY);
        assertThat(entry(bucket, "report").checksumSha256()).isNotBlank();
        s3.deleteFile(bucket, "report");
        completed(catalog.submit(bucket.getId()));
        assertThat(inventory(bucket)).isEmpty();
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.LOCAL_ONLY);
    }

    @Test
    void purgeCreatesSymlinkAndRecallKeepsItVisibleUntilVerifiedThenRestoresFile() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        completed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        assertThat(Files.isSymbolicLink(original)).isTrue();
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.ARCHIVED_SYMLINK);
        assertThat(entry(source, "report").symlinkTarget()).isEqualTo("s3://archive/report");
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        completed(catalog.submit(source.getId()));
        UUID linkId = entry(source, "report").id();

        var gate = s3.blockRead("report");
        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                null, List.of(linkId), null, null, false));
        try {
            gate.awaitRead();
            assertThat(jobs.get(recall.id()).status()).isEqualTo(RUNNING);
            assertThat(Files.isSymbolicLink(original)).isTrue();
            assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.SYMLINK);
            assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        } finally {
            gate.release.countDown();
        }
        completed(recall);
        assertThat(Files.isSymbolicLink(original)).isFalse();
        assertThat(original).hasContent("original bytes");
        assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.FILE);
        assertThat(entry(source, "report").storageState()).isEqualTo(StorageState.LOCAL_ONLY);
        assertThat(entry(source, "report").symlinkTarget()).isNull();
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);

        completed(transfers.submit(RECALL, request(bucket, source, null, true)));
        assertThat(inventory(bucket)).isEmpty();
        assertThat(s3.objects).doesNotContainKey("report");
    }

    @Test
    void failedArchiveAndRecallPreserveThePreviousPhysicalEntry() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        s3.corruptReads = "report";
        failed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        assertThat(original).hasContent("original bytes");
        assertThat(inventory(bucket)).isEmpty();
        assertThat(s3.objects).isEmpty();

        s3.corruptReads = null;
        completed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        s3.corruptReads = "report";
        failed(transfers.submit(RECALL, request(bucket, source, null, true)));
        assertThat(Files.isSymbolicLink(original)).isTrue();
        assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.SYMLINK);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);

        s3.corruptReads = null;
        s3.failDelete = "report";
        failed(transfers.submit(RECALL, request(bucket, source, null, true)));
        assertThat(Files.isSymbolicLink(original)).isTrue();
        assertThat(entry(source, "report").entryType()).isEqualTo(EntryType.SYMLINK);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
    }

    private StorageConnection connection(ProviderType provider, String location) {
        var connection = new StorageConnection();
        connection.setName(UUID.randomUUID().toString());
        connection.setProviderType(provider);
        connection.setRole(StorageRole.BOTH);
        connection.setBasePathOrBucket(location);
        return connections.saveAndFlush(connection);
    }

    private JobResponse finished(JobResponse accepted) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jobs.get(accepted.id()).completedAt() != null);
        return jobs.get(accepted.id());
    }

    private void completed(JobResponse accepted) {
        JobResponse job = finished(accepted);
        assertThat(job.status()).as(job.details()).isEqualTo(COMPLETED);
    }

    private void failed(JobResponse accepted) {
        assertThat(finished(accepted).status()).isEqualTo(FAILED);
    }

    private List<FileResponse> inventory(StorageConnection location) {
        return catalog.list(location.getId(), null, null, PageRequest.of(0, 50)).content();
    }

    private FileResponse entry(StorageConnection location, String path) {
        return inventory(location).stream().filter(file -> file.relativePath().equals(path)).findFirst().orElseThrow();
    }

    private TransferRequest request(StorageConnection from, StorageConnection to, ArchiveMode mode, boolean delete) {
        return new TransferRequest(from.getId(), to.getId(), List.of("report"), null, null, mode, delete);
    }

    @TestConfiguration
    static class ConnectorConfiguration {
        @Bean BucketFixture bucketFixture() { return new BucketFixture(new TestBucket()); }

        @Bean @Primary
        StorageConnectorFactory testConnectorFactory(BucketFixture fixture) {
            return new StorageConnectorFactory(List.of(new LocalStorageConnector(), new SmbStorageConnector(), fixture.connector()));
        }
    }

    private record BucketFixture(TestBucket connector) {}

    static class TestBucket implements StorageConnector {
        private record ObjectData(byte[] bytes, Instant modified) {}
        private final Map<String, ObjectData> objects = new ConcurrentHashMap<>();
        private volatile ReadGate gate;
        private volatile String corruptReads;
        private volatile String failDelete;

        void reset() {
            objects.clear();
            gate = null;
            corruptReads = null;
            failDelete = null;
        }

        ReadGate blockRead(String pathPrefix) {
            gate = new ReadGate(pathPrefix, new CountDownLatch(1), new CountDownLatch(1));
            return gate;
        }

        void put(String path, byte[] bytes) { objects.put(path, new ObjectData(bytes, Instant.now())); }

        @Override public boolean supports(ProviderType type) {
            return type != ProviderType.LOCAL && type != ProviderType.SMB && type != ProviderType.NAS;
        }
        @Override public boolean objectStorage() { return true; }
        @Override public void testConnection(StorageConnection config) {}

        @Override
        public List<ScannedFileInfo> listFiles(StorageConnection config, String path) {
            return objects.entrySet().stream()
                    .filter(item -> !StoragePaths.isArchiveStagingKey(item.getKey()))
                    .filter(item -> path.isEmpty() || item.getKey().equals(path) || item.getKey().startsWith(path + "/"))
                    .map(item -> new ScannedFileInfo(item.getKey(), item.getValue().bytes.length,
                            item.getValue().modified, "STANDARD")).toList();
        }

        @Override
        public InputStream openReadStream(StorageConnection config, String path) throws IOException {
            ReadGate current = gate;
            if (current != null && path.startsWith(current.pathPrefix)) {
                gate = null;
                current.entered.countDown();
                try {
                    if (!current.release.await(10, TimeUnit.SECONDS)) throw new IOException("Read gate timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            byte[] bytes = objects.get(path).bytes.clone();
            if (path.equals(corruptReads)) bytes[0] ^= 1;
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void writeStream(StorageConnection config, String path, InputStream data, long size, String storageClass)
                throws IOException {
            byte[] bytes = data.readAllBytes();
            if (bytes.length != size) throw new IOException("Unexpected length");
            put(path, bytes);
        }

        @Override public void deleteFile(StorageConnection config, String path) throws IOException {
            if (path.equals(failDelete)) throw new IOException("Delete failed");
            objects.remove(path);
        }
    }

    private record ReadGate(String pathPrefix, CountDownLatch entered, CountDownLatch release) {
        void awaitRead() {
            await().atMost(Duration.ofSeconds(10)).until(() -> entered.getCount() == 0);
        }
    }
}
