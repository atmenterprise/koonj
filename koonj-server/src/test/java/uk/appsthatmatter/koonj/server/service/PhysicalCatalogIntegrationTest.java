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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteRequest;
import uk.appsthatmatter.koonj.server.dto.CatalogFavoriteResponse;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataValues;
import uk.appsthatmatter.koonj.server.dto.CatalogMetadataWriteRequest;
import uk.appsthatmatter.koonj.server.dto.FileResponse;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.TransferRequest;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Operation.*;
import static uk.appsthatmatter.koonj.server.entity.TransferJob.Status.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:physical-catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "koonj.transfer.max-parallel-files=2"
})
class PhysicalCatalogIntegrationTest {
    @TempDir Path root;
    @Autowired StorageConnectionRepository connections;
    @Autowired FileMetadataRepository files;
    @Autowired CatalogScanService catalog;
    @Autowired FileLineageService lineage;
    @Autowired CatalogMetadataService metadata;
    @Autowired CatalogFavoriteService favorites;
    @Autowired ConnectionService connectionService;
    @Autowired FileTransferService transfers;
    @Autowired JobService jobs;
    @Autowired TransferJobRepository jobRepository;
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
    void keepsFavoritesPrivateAndCleansThemUpWhenTheirConnectionIsDeleted() {
        var folder = CatalogMetadataWriteRequest.Scope.FOLDER;
        var file = CatalogMetadataWriteRequest.Scope.FILE;
        var selectedFolder = new CatalogFavoriteRequest(source.getId(), folder, "docs/", true);
        favorites.setFavorite("alice", selectedFolder);
        favorites.setFavorite("alice", new CatalogFavoriteRequest(source.getId(), folder, "docs", true));
        favorites.setFavorite("alice", new CatalogFavoriteRequest(source.getId(), file, "docs/report.txt", true));
        favorites.setFavorite("bob", selectedFolder);

        assertThat(favorites.list("alice")).extracting(CatalogFavoriteResponse::path)
                .containsExactlyInAnyOrder("docs", "docs/report.txt");
        assertThat(favorites.list("bob")).extracting(CatalogFavoriteResponse::path).containsExactly("docs");

        favorites.setFavorite("alice", new CatalogFavoriteRequest(source.getId(), folder, "docs/", false));
        assertThat(favorites.list("alice")).extracting(CatalogFavoriteResponse::path).containsExactly("docs/report.txt");
        assertThat(favorites.list("bob")).hasSize(1);
        connectionService.delete(source.getId());
        assertThat(favorites.list("alice")).isEmpty();
        assertThat(favorites.list("bob")).isEmpty();
    }

    @Test
    void readsFileAttributesOnDemandWithoutInferringTheAuthorFromTheOwner() throws IOException {
        Path report = Files.writeString(root.resolve("report"), "contents");
        completed(catalog.submit(source.getId()));

        var attributes = catalog.attributes(entry(source, "report").id());
        assertThat(attributes.owner()).isEqualTo(Files.getOwner(report).getName());
        assertThat(attributes.createdAt()).isNotNull();
        assertThat(attributes.lastAccessedAt()).isNotNull();
        assertThat(attributes.author()).isNull();
        assertThat(attributes.lastAccessedBy()).isNull();
    }

    @Test
    void savesFileAndFolderDescriptionsAndPreservesThemWhenOnlyOtherMetadataChanges() {
        var folder = new CatalogMetadataWriteRequest.Target(CatalogMetadataWriteRequest.Scope.FOLDER, "docs/");
        var file = new CatalogMetadataWriteRequest.Target(CatalogMetadataWriteRequest.Scope.FILE, "docs/report");
        var replace = CatalogMetadataWriteRequest.Mode.REPLACE;
        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(folder),
                new CatalogMetadataValues(List.of(), List.of(), "Reports\nand supporting files"), replace));
        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(file),
                new CatalogMetadataValues(List.of("finance"), List.of(), "Quarterly report"), replace));
        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(file),
                new CatalogMetadataValues(List.of("reviewed"), List.of()), replace));

        assertThat(metadata.list(source.getId())).satisfiesExactly(
                item -> assertThat(item.values().description()).isEqualTo("Reports\nand supporting files"),
                item -> {
                    assertThat(item.values().description()).isEqualTo("Quarterly report");
                    assertThat(item.values().tags()).containsExactly("reviewed");
                });

        metadata.save(source.getId(), new CatalogMetadataWriteRequest(List.of(folder),
                new CatalogMetadataValues(List.of(), List.of(), ""), replace));
        assertThat(metadata.list(source.getId())).extracting(item -> item.values().description()).containsExactly("Quarterly report");
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
        UUID linkId = entry(source, "report").id();
        assertThat(entry(source, "report").symlinkTarget()).isEqualTo("koonj-ref://" + linkId);
        assertThat(root.toFile().list()).containsExactly("report");
        assertThat(entry(source, "report").sizeBytes()).isEqualTo(14);
        assertThat(entry(bucket, "report").entryType()).isEqualTo(EntryType.FILE);
        completed(catalog.submit(source.getId()));
        assertThat(catalog.get(linkId).sizeBytes()).isEqualTo(14);
        assertThat(catalog.get(linkId).checksumSha256()).isNotBlank();

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
    void synchronousRecallReturnsColdEstimateThenWaitsForVerifiedReplacement() throws Exception {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        completed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        UUID fileId = entry(source, "report").id();
        Instant estimate = Instant.now().plus(Duration.ofHours(5));
        s3.coldReady = estimate;
        assertThat(transfers.recallSync(fileId, 5))
                .isEqualTo(new FileTransferService.RecallResult("RESTORING_GLACIER", estimate));
        assertThat(Files.isSymbolicLink(original)).isTrue();

        s3.coldReady = null;
        var gate = s3.blockRead("report");
        var recall = CompletableFuture.supplyAsync(() -> transfers.recallSync(fileId, 10));
        try {
            gate.awaitRead();
            assertThat(recall).isNotDone();
            assertThat(Files.isSymbolicLink(original)).isTrue();
        } finally {
            gate.release.countDown();
        }
        assertThat(recall.get(10, TimeUnit.SECONDS).status()).isEqualTo("RECALLED");
        assertThat(original).hasContent("original bytes");
        assertThat(catalog.get(fileId).storageState()).isEqualTo(StorageState.LOCAL_ONLY);
        assertThat(jobRepository.findAll()).anySatisfy(job -> {
            assertThat(job.getTargetConnectionId()).isEqualTo(source.getId());
            assertThat(job.getPriority()).isEqualTo(100);
            assertThat(job.getStatus()).isEqualTo(COMPLETED);
        });
    }

    @Test
    void pausesAfterBothInFlightFilesAndResumesTheSameArchive() throws Exception {
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs/first.txt"), "first file");
        Files.writeString(root.resolve("docs/second.txt"), "second file");
        Files.writeString(root.resolve("docs/third.txt"), "third file");
        var gate = s3.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX, 2);
        var job = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("docs/"), null, null, ArchiveMode.COPY, false));
        List<String> started;
        try {
            gate.awaitRead();
            var activities = jobs.activeArchives(source.getId());
            assertThat(activities).hasSize(1);
            started = activities.getFirst().currentFiles();
            assertThat(started).hasSize(2);
            assertThat(jobs.control(job.id(), JobService.Action.PAUSE, true).getStatus()).isEqualTo(PAUSING);
        } finally {
            gate.release.countDown();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jobs.get(job.id()).status()).isEqualTo(PAUSED));
        assertThat(jobs.get(job.id()).completedAt()).isNull();
        assertThat(jobs.get(job.id()).processedFiles()).isEqualTo(2);
        assertThat(s3.objects.keySet()).containsExactlyInAnyOrderElementsOf(started);
        assertThat(jobs.activeArchives(source.getId())).isEmpty();
        transfers.execute(jobs.control(job.id(), JobService.Action.RESUME, true));
        completed(job);
        assertThat(jobs.get(job.id()).processedFiles()).isEqualTo(3);
        assertThat(jobs.fileResults(job.id(), false, PageRequest.of(0, 10)).content()).hasSize(3);
    }

    @Test
    void restartsAStoppedPurgeWithoutReprocessingVerifiedFiles() throws Exception {
        Path first = Files.writeString(root.resolve("first.txt"), "first");
        Path second = Files.writeString(root.resolve("second.txt"), "second");
        Path third = Files.writeString(root.resolve("third.txt"), "third");
        var gate = s3.blockRead(StoragePaths.ARCHIVE_STAGING_PREFIX, 2);
        var job = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("first.txt", "second.txt", "third.txt"), null, null, ArchiveMode.PURGE, false));
        try {
            gate.awaitRead();
            assertThat(jobs.control(job.id(), JobService.Action.STOP, true).getStatus()).isEqualTo(STOPPING);
        } finally {
            gate.release.countDown();
        }
        assertThat(finished(job).status()).isEqualTo(STOPPED);
        assertThat(Files.isSymbolicLink(first)).isTrue();
        assertThat(Files.isSymbolicLink(second)).isTrue();
        assertThat(Files.isSymbolicLink(third)).isFalse();
        assertThatThrownBy(() -> jobs.control(job.id(), JobService.Action.RESTART, false))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Only an administrator");
        var replacement = jobs.control(job.id(), JobService.Action.RESTART, true);
        transfers.execute(replacement);
        completed(JobResponse.from(replacement));
        assertThat(jobs.get(job.id()).restartedAs()).isEqualTo(replacement.getId());
        assertThat(jobs.get(replacement.getId()).restartedFrom()).isEqualTo(job.id());
        assertThat(jobs.get(replacement.getId()).processedFiles()).isEqualTo(1);
        assertThat(Files.isSymbolicLink(third)).isTrue();
        assertThat(lineage.forFile(source.getId(), "first.txt", 0, 10).content()).hasSize(1);
        assertThat(lineage.forFile(source.getId(), "second.txt", 0, 10).content()).hasSize(1);
    }

    @Test
    void recallsDifferentFilesInParallel() throws IOException {
        s3.put("docs/first.txt", "first".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        s3.put("docs/second.txt", "second".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var gate = s3.blockRead("docs/", 2);
        var job = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                List.of("docs/", "docs/first.txt"), null, null, null, false));
        try {
            gate.awaitRead();
        } finally {
            gate.release.countDown();
        }
        completed(job);
        assertThat(jobs.get(job.id()).processedFiles()).isEqualTo(2);
        assertThat(root.resolve("docs/first.txt")).hasContent("first");
        assertThat(root.resolve("docs/second.txt")).hasContent("second");
    }

    @Test
    void logsTimingActorAndIndividualReasonsForPartialFailures() throws IOException {
        Files.writeString(root.resolve("copied.txt"), "verified bytes");
        Files.writeString(root.resolve("corrupt.txt"), "damaged during archive");
        s3.corruptReads = "corrupt.txt";
        JobResponse accepted;
        var context = SecurityContextHolder.createEmptyContext();
        var token = Jwt.withTokenValue("test-token").header("alg", "none").subject("operator-id")
                .claim("preferred_username", "operator@example.com").claim("name", "Catalog Operator").build();
        context.setAuthentication(new JwtAuthenticationToken(token));
        SecurityContextHolder.setContext(context);
        try {
            accepted = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                    List.of("copied.txt", "corrupt.txt", "missing.txt"), null, null, ArchiveMode.COPY, false));
        } finally {
            SecurityContextHolder.clearContext();
        }
        var job = finished(accepted);
        assertThat(job.status()).isEqualTo(PARTIAL_FAILURE);
        assertThat(job.triggeredBy()).isEqualTo("operator@example.com");
        assertThat(job.triggeredByName()).isEqualTo("Catalog Operator");
        assertThat(job.startedAt()).isBetween(job.createdAt(), job.completedAt());
        assertThat(job.failedFiles()).isEqualTo(2);
        assertThat(jobs.fileResults(job.id(), false, PageRequest.of(0, 1)).totalElements()).isEqualTo(2);
        assertThat(jobs.fileResults(job.id(), true, PageRequest.of(0, 1)).content()).singleElement().satisfies(file -> {
            assertThat(file.sourcePath()).isEqualTo("corrupt.txt");
            assertThat(file.error()).contains("checksum mismatch");
        });
        assertThat(jobs.events(job.id(), true, PageRequest.of(0, 1)).content()).singleElement().satisfies(event -> {
            assertThat(event.path()).isEqualTo("missing.txt");
            assertThat(event.message()).isEqualTo("No regular files found");
        });
        assertThat(jobs.events(job.id(), false, PageRequest.of(0, 10)).content()).extracting(event -> event.message())
                .contains("Job queued", "Execution started", "Execution completed with failures");
    }

    @Test
    void retainsCopyAndSymlinkRecallEvidenceAndExportsFolderChecksums() throws Exception {
        Files.createDirectories(root.resolve("docs_%/nested"));
        Files.createDirectories(root.resolve("docs-other"));
        String path = "docs_%/nested/report.txt";
        Path report = Files.writeString(root.resolve(path), "audited bytes");
        Files.writeString(root.resolve("docs_%/pending.txt"), "never archived");
        Files.writeString(root.resolve("docs-other/other.txt"), "outside report scope");
        completed(catalog.submit(source.getId()));
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(report)));
        var copy = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of(path), null, null, ArchiveMode.COPY, false));
        completed(copy);
        var purge = transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of(path), null, null, ArchiveMode.PURGE, false));
        completed(purge);
        assertThat(Files.isSymbolicLink(report)).isTrue();
        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                List.of(path), null, null, null, true));
        completed(recall);

        for (var connection : List.of(source, bucket)) {
            var movements = lineage.forFile(connection.getId(), path, 0, 10).content();
            assertThat(movements).extracting(item -> item.jobId()).containsExactly(recall.id(), purge.id(), copy.id());
            assertThat(movements).extracting(item -> item.operation()).containsExactly(
                    FileMovement.Operation.RECALL, FileMovement.Operation.ARCHIVE_PURGE, FileMovement.Operation.ARCHIVE_COPY);
            assertThat(movements).allSatisfy(item -> {
                assertThat(item.outcome()).isEqualTo(FileMovement.Outcome.VERIFIED);
                assertThat(item.sourceChecksum()).isEqualTo(expected);
                assertThat(item.targetChecksum()).isEqualTo(expected);
                assertThat(item.sourcePath()).isEqualTo(path);
                assertThat(item.targetPath()).isEqualTo(path);
            });
            assertThat(movements.getFirst().archiveRemoved()).isTrue();
            assertThat(movements.getFirst().symlinkReference()).startsWith("koonj-ref://");
            assertThat(movements.get(1).symlinkReference()).isEqualTo(movements.getFirst().symlinkReference());
            assertThat(movements.getLast().symlinkReference()).isNull();
        }
        assertThat(inventory(bucket)).isEmpty();

        var output = new ByteArrayOutputStream();
        lineage.folderReport(source.getId(), "docs_%/").writeTo(output);
        String csv = output.toString(StandardCharsets.UTF_8);
        assertThat(csv.lines().toList()).hasSize(5);
        assertThat(csv).contains("\"source_sha256\",\"target_sha256\"", "\"" + expected + "\",\"" + expected + "\"",
                "\"docs_%/pending.txt\",\"NO_RECORD\"", copy.id().toString(), recall.id().toString())
                .doesNotContain("docs-other");
    }

    @Test
    void failedArchiveAndRecallPreserveThePreviousPhysicalEntry() throws IOException {
        Path original = Files.writeString(root.resolve("report"), "original bytes");
        s3.corruptReads = "report";
        failed(transfers.submit(ARCHIVE, request(source, bucket, ArchiveMode.PURGE, false)));
        assertThat(original).hasContent("original bytes");
        assertThat(inventory(bucket)).isEmpty();
        assertThat(s3.objects).isEmpty();
        var evidence = lineage.forFile(source.getId(), "report", 0, 10).content().getFirst();
        assertThat(evidence.outcome()).isEqualTo(FileMovement.Outcome.FAILED);
        assertThat(evidence.sourceChecksum()).isNotBlank().isNotEqualTo(evidence.targetChecksum());
        assertThat(evidence.targetChecksum()).isNotBlank();

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

    @Test
    void recallsOnlyTheArchivedLinksUnderTheSelectedMountedFolder() throws IOException {
        Files.createDirectories(root.resolve("selected_%/nested"));
        Files.createDirectory(root.resolve("selected-other"));
        Path inside = Files.writeString(root.resolve("selected_%/nested/report"), "archived in folder");
        Path outside = Files.writeString(root.resolve("selected-other/report"), "archived outside folder");
        Path localOnly = Files.writeString(root.resolve("selected_%/local"), "still local");
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("selected_%/nested/report", "selected-other/report"), null, null, ArchiveMode.PURGE, false)));
        assertThat(Files.isSymbolicLink(inside)).isTrue();
        assertThat(Files.isSymbolicLink(outside)).isTrue();

        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                null, null, null, null, false, List.of("selected_%/")));
        assertThat(recall.requestedPaths()).containsExactly("selected_%/");
        var finished = finished(recall);
        assertThat(finished.status()).as(finished.details()).isEqualTo(COMPLETED);
        assertThat(finished.totalFiles()).isEqualTo(1);
        assertThat(inside).hasContent("archived in folder");
        assertThat(Files.isSymbolicLink(inside)).isFalse();
        assertThat(Files.isSymbolicLink(outside)).isTrue();
        assertThat(localOnly).hasContent("still local");
    }

    @Test
    void deduplicatesMountedLinksAndOverlappingS3Selections() throws IOException {
        Files.createDirectories(root.resolve("docs/nested"));
        Path first = Files.writeString(root.resolve("docs/first"), "first");
        Path second = Files.writeString(root.resolve("docs/nested/second"), "second");
        completed(transfers.submit(ARCHIVE, new TransferRequest(source.getId(), bucket.getId(),
                List.of("docs/"), null, null, ArchiveMode.PURGE, false)));

        var recall = transfers.submit(RECALL, new TransferRequest(bucket.getId(), source.getId(),
                List.of("docs/nested/", "docs/nested/second"), null, null, null, true, List.of("docs/", "docs/nested/")));
        completed(recall);

        assertThat(jobs.get(recall.id()).processedFiles()).isEqualTo(2);
        assertThat(jobs.fileResults(recall.id(), false, PageRequest.ofSize(10)).content()).hasSize(2);
        assertThat(first).hasContent("first");
        assertThat(second).hasContent("second");
        assertThat(s3.objects).isEmpty();
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
        private volatile Instant coldReady;

        void reset() {
            objects.clear();
            gate = null;
            corruptReads = null;
            failDelete = null;
            coldReady = null;
        }

        ReadGate blockRead(String pathPrefix) {
            return blockRead(pathPrefix, 1);
        }

        ReadGate blockRead(String pathPrefix, int readers) {
            gate = new ReadGate(pathPrefix, new CountDownLatch(readers), new CountDownLatch(1));
            return gate;
        }

        void put(String path, byte[] bytes) { objects.put(path, new ObjectData(bytes, Instant.now())); }

        @Override public boolean supports(ProviderType type) {
            return type != ProviderType.LOCAL && type != ProviderType.SMB && type != ProviderType.NAS;
        }
        @Override public boolean objectStorage() { return true; }
        @Override public Optional<Instant> requestColdRestore(StorageConnection connection, String key) {
            return Optional.ofNullable(coldReady);
        }
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
