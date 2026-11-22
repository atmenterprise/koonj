package uk.appsthatmatter.koonj.server.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.TransferRequest;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class FileTransferService {
    private final ConnectionService connections;
    private final StorageConnectorFactory connectors;
    private final FileMetadataRepository files;
    private final CatalogScanService catalog;
    private final JobService jobs;
    private final TransactionTemplate transaction;
    private final ConcurrentHashMap<UUID, CompletableFuture<JobResponse>> interactiveRecalls = new ConcurrentHashMap<>();

    public FileTransferService(ConnectionService connections, StorageConnectorFactory connectors,
                               FileMetadataRepository files, CatalogScanService catalog, JobService jobs,
                               TransactionTemplate transaction) {
        this.connections = connections;
        this.connectors = connectors;
        this.files = files;
        this.catalog = catalog;
        this.jobs = jobs;
        this.transaction = transaction;
    }

    public JobResponse submit(Operation operation, TransferRequest request) {
        Endpoints endpoints = endpoints(operation, request.sourceConnectionId(), request.targetConnectionId());
        boolean archive = operation == Operation.ARCHIVE;
        String storageClass = request.storageClass() == null ? "STANDARD"
                : request.storageClass().strip().toUpperCase(Locale.ROOT);
        if (!storageClass.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid S3 storage class");
        }
        if (archive && Set.of("GLACIER", "DEEP_ARCHIVE").contains(storageClass)) {
            throw new IllegalArgumentException("Archive requires an S3 storage class available for immediate SHA-256 verification");
        }
        Map<String, String> restorePaths = new HashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        if (request.paths() != null) request.paths().stream().map(FileTransferService::transferPath).forEach(paths::add);
        Set<String> exactPaths = new HashSet<>();
        if (request.fileIds() != null) {
            for (UUID id : request.fileIds()) {
                FileMetadata selected = files.findById(id).orElseThrow(() ->
                        new IllegalArgumentException("Unknown catalog file ID " + id));
                String path = resolveFileId(operation, endpoints, selected, restorePaths);
                exactPaths.add(path);
                paths.add(path);
            }
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("Provide at least one path or file ID");
        TransferJob job = jobs.create(operation, request.sourceConnectionId(), request.targetConnectionId(),
                List.copyOf(paths), request.archiveMode(), request.deleteFromTarget());
        JobResponse accepted = JobResponse.from(job);
        jobs.execute(job, running -> transfer(running, storageClass, restorePaths, exactPaths));
        return accepted;
    }

    public record RecallResult(String status, Instant estimatedReadyAt) {}

    public RecallResult recallSync(UUID fileId, int timeoutSeconds) {
        FileMetadata file = file(fileId);
        if (isLocal(file)) return new RecallResult("RECALLED", null);
        if (file.getEntryType() != EntryType.SYMLINK || file.getTargetConnection() == null || file.getS3Key() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "File ID has no mounted archive reference");
        }
        Endpoints endpoints = endpoints(Operation.RECALL, file.getTargetConnection().getId(), file.getConnection().getId());
        String key = resolveFileId(Operation.RECALL, endpoints, file, new HashMap<>());
        CompletableFuture<JobResponse> completion = interactiveRecalls.get(fileId);
        if (completion != null) return awaitRecall(completion, timeoutSeconds);
        Optional<Instant> ready;
        try {
            ready = endpoints.reader().requestColdRestore(endpoints.source(), key);
        } catch (software.amazon.awssdk.core.exception.SdkException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, StorageErrors.describe(failure), failure);
        }
        if (ready.isPresent()) return new RecallResult("RESTORING_GLACIER", ready.get());

        var submitted = interactiveRecalls.computeIfAbsent(fileId, id -> {
            TransferJob job = jobs.create(Operation.RECALL, endpoints.source().getId(), endpoints.target().getId(),
                    List.of(key), ArchiveMode.COPY, false, JobService.INTERACTIVE);
            return jobs.execute(job, running -> {
                FileMetadata current = file(id);
                if (isLocal(current)) return;
                String currentKey = resolveFileId(Operation.RECALL, endpoints, current, new HashMap<>());
                if (!key.equals(currentKey)) throw new IOException("Archive reference changed while recall was queued");
                ScannedFileInfo found = exact(endpoints.reader(), endpoints.source(), key)
                        .filter(item -> item.entryType() == EntryType.FILE)
                        .orElseThrow(() -> new IOException("Archive object was not found"));
                jobs.totals(running, 1, found.sizeBytes());
                recall(endpoints, found, current.getRelativePath(), false);
                jobs.progress(running, found.sizeBytes(), null);
            });
        });
        submitted.whenComplete((result, failure) -> interactiveRecalls.remove(fileId, submitted));
        return awaitRecall(submitted, timeoutSeconds);
    }

    private static RecallResult awaitRecall(CompletableFuture<JobResponse> completion, int timeoutSeconds) {
        try {
            JobResponse result = completion.get(timeoutSeconds, TimeUnit.SECONDS);
            if (result.status() != TransferJob.Status.COMPLETED) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recall failed. See transfer job " + result.id());
            }
            return new RecallResult("RECALLED", null);
        } catch (TimeoutException failure) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Recall is still queued or running", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recall wait interrupted", failure);
        } catch (ExecutionException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recall could not complete", failure);
        }
    }

    private FileMetadata file(UUID id) {
        return files.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown file ID"));
    }

    private static boolean isLocal(FileMetadata file) {
        return file.getEntryType() == EntryType.FILE && file.getStorageState() == StorageState.LOCAL_ONLY;
    }

    private static boolean matchesArchiveLink(FileMetadata file, StorageConnection bucket, String key, String target) {
        return s3Uri(bucket, key).equals(target)
                || (file.getId() != null && (LocalStorageConnector.ARCHIVE_REFERENCE + file.getId()).equals(target)
                    && file.getTargetConnection() != null && file.getTargetConnection().getId().equals(bucket.getId())
                    && key.equals(file.getS3Key()));
    }

    private static String transferPath(String requested) {
        String path = StoragePaths.selection(requested);
        return !path.isEmpty() && requested.endsWith("/") ? path + "/" : path;
    }

    private String resolveFileId(Operation operation, Endpoints endpoints, FileMetadata file, Map<String, String> restorePaths) {
        if (file.getConnection().getId().equals(endpoints.source().getId()) && file.getEntryType() == EntryType.FILE) {
            return file.getRelativePath();
        }
        if (operation == Operation.RECALL && file.getConnection().getId().equals(endpoints.target().getId())
                && file.getEntryType() == EntryType.SYMLINK && file.getSymlinkTarget() != null) {
            String prefix = s3Uri(endpoints.source(), "");
            String reference = file.getSymlinkTarget();
            String key = reference.startsWith(prefix) ? reference.substring(prefix.length()) : file.getS3Key();
            if (key != null && matchesArchiveLink(file, endpoints.source(), key, reference)
                    && (file.getTargetConnection() == null
                    || file.getTargetConnection().getId().equals(endpoints.source().getId()))) {
                StoragePaths.requireSafe(key);
                if (key.isEmpty() || StoragePaths.isArchiveStagingKey(key)) {
                    throw new IllegalArgumentException("Invalid archive object key");
                }
                String previous = restorePaths.putIfAbsent(key, file.getRelativePath());
                if (previous != null && !previous.equals(file.getRelativePath())) {
                    throw new IllegalArgumentException("Select one source symlink per archived object");
                }
                return key;
            }
        }
        throw new IllegalArgumentException("Catalog file ID is not transferable between the selected connections");
    }

    private Endpoints endpoints(Operation operation, UUID sourceId, UUID targetId) {
        StorageConnection source = connections.active(sourceId);
        StorageConnection target = connections.active(targetId);
        StorageConnector reader = connectors.resolve(source.getProviderType());
        StorageConnector writer = connectors.resolve(target.getProviderType());
        boolean archive = operation == Operation.ARCHIVE;
        if (archive && (source.getRole() == StorageRole.TARGET || target.getRole() == StorageRole.SOURCE)) {
            throw new IllegalArgumentException("Source and target connection roles must permit archiving");
        }
        if (reader.objectStorage() == archive || writer.objectStorage() != archive) {
            throw new IllegalArgumentException("Archive requires mounted source and S3 target; recall requires S3 source and mounted target");
        }
        return new Endpoints(source, reader, target, writer);
    }

    private void transfer(TransferJob job, String storageClass, Map<String, String> restorePaths, Set<String> exactPaths) {
        Endpoints endpoints = endpoints(job.getOperation(), job.getSourceConnectionId(), job.getTargetConnectionId());
        Map<String, ScannedFileInfo> selected = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        for (String path : job.getRequestedPaths()) {
            try {
                boolean folder = path.endsWith("/");
                String prefix = folder ? path.substring(0, path.length() - 1) : path;
                StoragePaths.requireSafe(prefix);
                List<ScannedFileInfo> found = endpoints.reader().listFiles(endpoints.source(), prefix).stream()
                        .filter(file -> file.entryType() == EntryType.FILE)
                        .filter(file -> !folder || file.relativePath().startsWith(path)).toList();
                if (!folder && (exactPaths.contains(path) || found.stream().anyMatch(file -> path.equals(file.relativePath())))) {
                    found = found.stream().filter(file -> path.equals(file.relativePath())).toList();
                }
                if (found.isEmpty()) unavailable.put(path, "No regular files found");
                found.forEach(file -> selected.putIfAbsent(file.relativePath(), file));
            } catch (IOException | RuntimeException failure) {
                unavailable.put(path, StorageErrors.describe(failure));
            }
        }
        jobs.totals(job, selected.size() + unavailable.size(),
                selected.values().stream().mapToLong(ScannedFileInfo::sizeBytes).sum());
        unavailable.forEach((path, error) -> jobs.progress(job, 0, path + ": " + error));
        for (ScannedFileInfo found : selected.values()) {
            jobs.runWaitingInteractiveJobs();
            String error = null;
            try {
                StoragePaths.requireSafe(found.relativePath());
                if (StoragePaths.isArchiveStagingKey(found.relativePath())) {
                    throw new IOException("Path is reserved for archive staging");
                }
                if (job.getOperation() == Operation.ARCHIVE) {
                    archive(endpoints, found, storageClass, job.getArchiveMode());
                } else {
                    recall(endpoints, found, restorePaths.getOrDefault(found.relativePath(), found.relativePath()),
                            job.isDeleteFromTarget());
                }
            } catch (IOException | RuntimeException failure) {
                error = found.relativePath() + ": " + StorageErrors.describe(failure);
            }
            jobs.progress(job, error == null ? found.sizeBytes() : 0, error);
        }
    }

    private void archive(Endpoints endpoints, ScannedFileInfo found, String storageClass, ArchiveMode mode) throws IOException {
        String path = found.relativePath();
        StorageConnection mounted = endpoints.source();
        StorageConnection bucket = endpoints.target();
        StorageConnector source = endpoints.reader();
        StorageConnector s3 = endpoints.writer();
        if (files.findByTargetConnectionIdAndS3Key(bucket.getId(), path).stream()
                .anyMatch(binding -> !binding.getConnection().getId().equals(mounted.getId()))) {
            throw new IOException("Archive key already belongs to another source connection");
        }

        FileMetadata local = catalog.entry(mounted, path);
        CatalogScanService.updatePhysical(local, found);
        local.setStorageState(StorageState.ARCHIVING);
        local.setErrorMessage(null);
        files.save(local);

        String staging = StoragePaths.ARCHIVE_STAGING_PREFIX + UUID.randomUUID();
        String backup = staging + "-previous";
        Optional<ScannedFileInfo> previous = Optional.empty();
        boolean publishAttempted = false;
        boolean preserveBackup = false;
        String backupChecksum = null;
        try {
            previous = exact(s3, bucket, path);
            if (previous.isPresent()) {
                ScannedFileInfo prior = previous.get();
                MessageDigest digest = sha256();
                try (var stream = new DigestInputStream(s3.openReadStream(bucket, path), digest)) {
                    s3.writeStream(bucket, backup, stream, prior.sizeBytes(), "STANDARD");
                }
                backupChecksum = encode(digest.digest());
                FileMetadata known = catalog.entry(bucket, path);
                boolean knownVersion = prior.sizeBytes() == known.getSizeBytes()
                        && Objects.equals(prior.lastModifiedAt(), known.getLastModifiedAt());
                if ((knownVersion && known.getChecksumSha256() != null && !backupChecksum.equals(known.getChecksumSha256()))
                        || !backupChecksum.equals(checksum(s3, bucket, backup))) {
                    throw new IOException("Existing archive backup checksum mismatch");
                }
            }

            MessageDigest digest = sha256();
            try (var stream = new DigestInputStream(source.openReadStream(mounted, path), digest)) {
                s3.writeStream(bucket, staging, stream, found.sizeBytes(), "STANDARD");
            }
            String checksum = encode(digest.digest());
            if (!checksum.equals(checksum(s3, bucket, staging))) throw new IOException("Archive checksum mismatch");

            publishAttempted = true;
            try (InputStream verified = verifying(s3.openReadStream(bucket, staging), checksum)) {
                s3.writeStream(bucket, path, verified, found.sizeBytes(), storageClass);
            }
            if (!checksum.equals(checksum(s3, bucket, path))) throw new IOException("Published archive checksum mismatch");
            ScannedFileInfo object = exact(s3, bucket, path)
                    .orElseThrow(() -> new IOException("Uploaded archive object could not be found"));
            if (mode == ArchiveMode.PURGE) {
                if (!checksum.equals(checksum(source, mounted, path))) throw new IOException("Source changed during archive");
                source.replaceWithSymlink(mounted, path, LocalStorageConnector.ARCHIVE_REFERENCE + local.getId(), () -> {
                    ScannedFileInfo link = exact(source, mounted, path).filter(item -> item.entryType() == EntryType.SYMLINK)
                            .orElseThrow(() -> new IOException("Archive symbolic link was not created"));
                    recordArchive(local, bucket, object, new ScannedFileInfo(path, found.sizeBytes(),
                            found.lastModifiedAt(), null, EntryType.SYMLINK, link.symlinkTarget()), checksum, mode);
                });
            } else {
                ScannedFileInfo physical = exact(source, mounted, path).filter(item -> item.entryType() == EntryType.FILE)
                        .orElseThrow(() -> new IOException("Source file disappeared during archive"));
                if (!Objects.equals(physical, found)) throw new IOException("Source changed during archive");
                recordArchive(local, bucket, object, physical, checksum, mode);
            }
        } catch (IOException | RuntimeException failure) {
            if (publishAttempted) {
                if (previous.isEmpty()) suppress(failure, () -> s3.deleteFile(bucket, path));
                else {
                    ScannedFileInfo prior = previous.get();
                    try (InputStream stream = verifying(s3.openReadStream(bucket, backup), backupChecksum)) {
                        s3.writeStream(bucket, path, stream, prior.sizeBytes(), prior.storageClass());
                    } catch (IOException | RuntimeException rollback) {
                        preserveBackup = true;
                        failure.addSuppressed(rollback);
                        System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                                "Could not restore previous archive. Backup retained at " + backup, rollback);
                    }
                }
            }
            markFailed(source, mounted, path, failure);
            throw failure;
        } finally {
            removeStaging(s3, bucket, staging);
            if (previous.isPresent() && !preserveBackup) removeStaging(s3, bucket, backup);
        }
    }

    private void recordArchive(FileMetadata local, StorageConnection bucket, ScannedFileInfo object,
                               ScannedFileInfo physicalSource, String checksum, ArchiveMode mode) {
        transaction.executeWithoutResult(status -> {
            Instant now = Instant.now();
            FileMetadata target = catalog.entry(bucket, object.relativePath());
            CatalogScanService.updatePhysical(target, object);
            target.setStorageState(StorageState.ARCHIVED);
            target.setChecksumSha256(checksum);
            target.setS3Key(object.relativePath());
            target.setS3StorageClass(object.storageClass());
            target.setArchivedAt(now);
            files.save(target);

            CatalogScanService.updatePhysical(local, physicalSource);
            local.setTargetConnection(bucket);
            local.setS3Key(object.relativePath());
            local.setS3StorageClass(object.storageClass());
            local.setChecksumSha256(checksum);
            local.setArchivedAt(now);
            local.setStorageState(mode == ArchiveMode.PURGE ? StorageState.ARCHIVED_SYMLINK : StorageState.ARCHIVED_COPY);
            files.save(local);
        });
    }

    private void removeStaging(StorageConnector connector, StorageConnection connection, String key) {
        try {
            connector.deleteFile(connection, key);
        } catch (IOException | RuntimeException failure) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Could not remove archive staging object", failure);
        }
    }

    private void recall(Endpoints endpoints, ScannedFileInfo found, String path, boolean deleteFromTarget) throws IOException {
        StorageConnection bucket = endpoints.source();
        StorageConnection mounted = endpoints.target();
        StorageConnector s3 = endpoints.reader();
        StorageConnector source = endpoints.writer();
        String key = found.relativePath();
        if (("GLACIER".equals(found.storageClass()) || "DEEP_ARCHIVE".equals(found.storageClass()))
                && s3.requestColdRestore(bucket, key).isPresent()) {
            throw new IOException("S3 restore in progress. Retry recall after the object is available");
        }
        FileMetadata local = catalog.entry(mounted, path);
        var existing = exact(source, mounted, path);
        if (existing.isPresent()) {
            ScannedFileInfo physical = existing.get();
            if (physical.entryType() == EntryType.DIRECTORY
                    || (physical.entryType() == EntryType.SYMLINK && !matchesArchiveLink(local, bucket, key, physical.symlinkTarget()))) {
                throw new IOException("Recall destination does not match the archive reference");
            }
            if (physical.entryType() == EntryType.FILE) CatalogScanService.updatePhysical(local, physical);
            local.setStorageState(StorageState.RECALLING);
            local.setErrorMessage(null);
            files.save(local);
        } else if (local.getId() != null) {
            files.delete(local);
        }

        try {
            FileMetadata object = catalog.entry(bucket, key);
            String expected = local.getEntryType() == EntryType.SYMLINK && local.getChecksumSha256() != null
                    ? local.getChecksumSha256() : object.getChecksumSha256();
            String checksum = expected == null ? checksum(s3, bucket, key) : expected;
            try (InputStream stream = verifying(s3.openReadStream(bucket, key), checksum)) {
                source.restore(mounted, path, stream, found.sizeBytes(), () -> {
                    ScannedFileInfo restored = exact(source, mounted, path)
                            .filter(item -> item.entryType() == EntryType.FILE)
                            .orElseThrow(() -> new IOException("Restored source file could not be found"));
                    if (deleteFromTarget) s3.deleteFile(bucket, key);
                    try {
                        transaction.executeWithoutResult(status -> {
                            FileMetadata file = catalog.entry(mounted, path);
                            CatalogScanService.updatePhysical(file, restored);
                            file.setStorageState(StorageState.LOCAL_ONLY);
                            file.setErrorMessage(null);
                            file.setChecksumSha256(checksum);
                            file.setTargetConnection(deleteFromTarget ? null : bucket);
                            file.setS3Key(deleteFromTarget ? null : key);
                            file.setS3StorageClass(deleteFromTarget ? null : found.storageClass());
                            files.save(file);

                            if (deleteFromTarget) {
                                if (object.getId() != null) files.delete(object);
                                catalog.clearMissingArchiveCopies(bucket.getId(), key::equals);
                            } else {
                                CatalogScanService.updatePhysical(object, found);
                                object.setStorageState(StorageState.ARCHIVED);
                                object.setChecksumSha256(checksum);
                                object.setS3Key(key);
                                object.setS3StorageClass(found.storageClass());
                                files.save(object);
                            }
                        });
                    } catch (RuntimeException failure) {
                        if (deleteFromTarget) suppress(failure, () -> {
                            try (InputStream restoredBytes = source.openReadStream(mounted, path)) {
                                s3.writeStream(bucket, key, restoredBytes, restored.sizeBytes(), found.storageClass());
                            }
                        });
                        throw failure;
                    }
                });
            }
        } catch (IOException | RuntimeException failure) {
            markFailed(source, mounted, path, failure);
            throw failure;
        }
    }

    private void markFailed(StorageConnector connector, StorageConnection connection, String path, Exception failure) {
        suppress(failure, () -> {
            var physical = exact(connector, connection, path);
            var current = files.findByConnectionIdAndRelativePath(connection.getId(), path);
            if (physical.isEmpty()) {
                current.ifPresent(files::delete);
                return;
            }
            FileMetadata file = current.orElseGet(() -> catalog.entry(connection, path));
            if (file.getEntryType() != EntryType.SYMLINK
                    || !Objects.equals(file.getSymlinkTarget(), physical.get().symlinkTarget())) {
                CatalogScanService.updatePhysical(file, physical.get());
            }
            file.setStorageState(StorageState.FAILED);
            file.setErrorMessage(StorageErrors.describe(failure));
            files.save(file);
        });
    }

    private static void suppress(Exception failure, StorageConnector.RestoreCommit cleanup) {
        try {
            cleanup.run();
        } catch (IOException | RuntimeException error) {
            failure.addSuppressed(error);
        }
    }

    private static Optional<ScannedFileInfo> exact(StorageConnector connector, StorageConnection connection, String path)
            throws IOException {
        return connector.listFiles(connection, path).stream().filter(item -> path.equals(item.relativePath())).findFirst();
    }

    private static String s3Uri(StorageConnection bucket, String key) {
        return "s3://" + bucket.getBasePathOrBucket() + "/" + key;
    }

    private static String checksum(StorageConnector connector, StorageConnection connection, String path) throws IOException {
        MessageDigest digest = sha256();
        try (var stream = new DigestInputStream(connector.openReadStream(connection, path), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return encode(digest.digest());
    }

    private static InputStream verifying(InputStream input, String expected) {
        var stream = new DigestInputStream(input, sha256());
        return new FilterInputStream(stream) {
            private boolean verified;

            private int check(int count) throws IOException {
                if (count == -1 && !verified) {
                    if (!expected.equals(encode(stream.getMessageDigest().digest()))) throw new IOException("SHA-256 checksum mismatch");
                    verified = true;
                }
                return count;
            }

            @Override
            public int read() throws IOException { return check(super.read()); }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return check(super.read(bytes, offset, length));
            }
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String encode(byte[] digest) { return HexFormat.of().formatHex(digest); }

    private record Endpoints(StorageConnection source, StorageConnector reader,
                             StorageConnection target, StorageConnector writer) {}
}
