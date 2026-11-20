package uk.appsthatmatter.koonj.server.service;

import org.springframework.stereotype.Service;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.TransferRequest;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class FileTransferService {
    private final ConnectionService connections;
    private final StorageConnectorFactory connectors;
    private final FileMetadataRepository files;
    private final CatalogScanService catalog;
    private final JobService jobs;

    public FileTransferService(ConnectionService connections, StorageConnectorFactory connectors,
                               FileMetadataRepository files, CatalogScanService catalog, JobService jobs) {
        this.connections = connections;
        this.connectors = connectors;
        this.files = files;
        this.catalog = catalog;
        this.jobs = jobs;
    }

    public JobResponse submit(Operation operation, TransferRequest request) {
        endpoints(operation, request.sourceConnectionId(), request.targetConnectionId());
        boolean archive = operation == Operation.ARCHIVE;
        String storageClass = request.storageClass() == null ? "STANDARD"
                : request.storageClass().strip().toUpperCase(Locale.ROOT);
        if (!storageClass.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid S3 storage class");
        }
        if (request.purgeSource() && (!archive || Set.of("GLACIER", "DEEP_ARCHIVE").contains(storageClass))) {
            throw new IllegalArgumentException("Purge requires an archive class available for immediate readback");
        }
        Set<String> paths = new LinkedHashSet<>();
        if (request.paths() != null) request.paths().stream().map(FileTransferService::transferPath).forEach(paths::add);
        if (request.fileIds() != null) {
            for (UUID id : request.fileIds()) paths.add(resolveFileId(operation, request.sourceConnectionId(), id));
        }
        if (paths.isEmpty()) throw new IllegalArgumentException("Provide at least one path or file ID");
        TransferJob job = jobs.create(operation, request.sourceConnectionId(), request.targetConnectionId(),
                List.copyOf(paths));
        JobResponse accepted = JobResponse.from(job);
        jobs.execute(job, running -> transfer(running, storageClass, request.purgeSource()));
        return accepted;
    }

    private static String transferPath(String requested) {
        String path = StoragePaths.selection(requested);
        return !path.isEmpty() && requested.endsWith("/") ? path + "/" : path;
    }

    private String resolveFileId(Operation operation, UUID sourceId, UUID id) {
        FileMetadata file = files.findById(id).orElseThrow(() ->
                new IllegalArgumentException("Unknown catalog file ID " + id));
        if (operation == Operation.RECALL && file.getTargetConnection() != null
                && file.getTargetConnection().getId().equals(sourceId) && file.getS3Key() != null) {
            return file.getS3Key();
        }
        if (!file.getSourceConnection().getId().equals(sourceId)) {
            throw new IllegalArgumentException("Catalog file ID does not belong to the transfer source");
        }
        return file.getRelativePath();
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
        // A recall reverses the storage direction of an archive.
        if (reader.objectStorage() == archive || writer.objectStorage() != archive) {
            throw new IllegalArgumentException("Archive requires mounted source and S3 target; recall requires S3 source and mounted target");
        }
        return new Endpoints(source, reader, target, writer);
    }

    private void transfer(TransferJob job, String storageClass, boolean purge) {
        Endpoints endpoints = endpoints(job.getOperation(), job.getSourceConnectionId(), job.getTargetConnectionId());
        Map<String, ScannedFileInfo> selected = new LinkedHashMap<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        for (String path : job.getRequestedPaths()) {
            try {
                boolean folder = path.endsWith("/");
                String prefix = folder ? path.substring(0, path.length() - 1) : path;
                StoragePaths.requireSafe(prefix);
                List<ScannedFileInfo> found = endpoints.reader().listFiles(endpoints.source(), prefix);
                if (folder) found = found.stream().filter(file -> file.relativePath().startsWith(path)).toList();
                if (found.isEmpty()) unavailable.put(path, "No files found");
                found.forEach(file -> selected.putIfAbsent(file.relativePath(), file));
            } catch (IOException | RuntimeException failure) {
                unavailable.put(path, StorageErrors.describe(failure));
            }
        }
        jobs.totals(job, selected.size() + unavailable.size(),
                selected.values().stream().mapToLong(ScannedFileInfo::sizeBytes).sum());
        unavailable.forEach((path, error) -> jobs.progress(job, 0, path + ": " + error));
        for (ScannedFileInfo found : selected.values()) {
            String error = null;
            try {
                transferFile(job.getOperation(), endpoints, found, storageClass, purge);
            } catch (IOException | RuntimeException failure) {
                error = found.relativePath() + ": " + StorageErrors.describe(failure);
            }
            jobs.progress(job, error == null ? found.sizeBytes() : 0, error);
        }
    }

    private void transferFile(Operation operation, Endpoints endpoints, ScannedFileInfo found,
                              String storageClass, boolean purge) throws IOException {
        String path = found.relativePath();
        StoragePaths.requireSafe(path);
        boolean archive = operation == Operation.ARCHIVE;
        StorageConnection mounted = archive ? endpoints.source() : endpoints.target();
        StorageConnection bucket = archive ? endpoints.target() : endpoints.source();
        List<FileMetadata> bindings = files.findByTargetConnectionIdAndS3Key(bucket.getId(), path);
        if (archive && bindings.stream().anyMatch(binding -> !binding.getSourceConnection().getId().equals(mounted.getId()))) {
            throw new IOException("Archive key already belongs to another source connection");
        }
        FileMetadata file = catalog.entry(mounted, found);
        String expectedChecksum = archive ? null : bindings.stream()
                .map(FileMetadata::getChecksumSha256).filter(Objects::nonNull).findFirst().orElse(null);
        file.setStorageState(archive ? StorageState.ARCHIVING : StorageState.RECALLING);
        file.setErrorMessage(null);
        file = files.save(file);
        try {
            MessageDigest digest = sha256();
            try (var hashing = new DigestInputStream(
                    endpoints.reader().openReadStream(endpoints.source(), path), digest)) {
                InputStream stream = expectedChecksum == null ? hashing : verifyAtEnd(hashing, expectedChecksum);
                endpoints.writer().writeStream(endpoints.target(), path, stream, found.sizeBytes(),
                        archive ? storageClass : null);
            }
            String checksum = encode(digest.digest());
            file.setChecksumSha256(checksum);
            file.setSizeBytes(found.sizeBytes());
            file.setLastModifiedAt(found.lastModifiedAt());
            file.setTargetConnection(bucket);
            file.setS3Key(path);
            if (archive) {
                file.setS3StorageClass(storageClass);
                file.setArchivedAt(Instant.now());
                file.setStorageState(StorageState.ARCHIVED);
                file = files.save(file);
                if (purge) {
                    requireChecksum(endpoints.writer(), bucket, path, checksum);
                    requireChecksum(endpoints.reader(), mounted, path, checksum);
                    endpoints.reader().deleteFile(mounted, path);
                    file.setStorageState(StorageState.PURGED_LOCAL);
                }
            } else {
                if (found.storageClass() != null) file.setS3StorageClass(found.storageClass());
                var restored = endpoints.writer().listFiles(mounted, path);
                if (!restored.isEmpty()) file.setLastModifiedAt(restored.getFirst().lastModifiedAt());
                file.setStorageState(StorageState.LOCAL_ONLY);
            }
            files.save(file);
        } catch (IOException | RuntimeException failure) {
            file.setStorageState(StorageState.FAILED);
            file.setErrorMessage(StorageErrors.describe(failure));
            files.save(file);
            throw failure;
        }
    }

    private static void requireChecksum(StorageConnector connector, StorageConnection connection, String path,
                                        String expected) throws IOException {
        MessageDigest digest = sha256();
        try (var stream = new DigestInputStream(connector.openReadStream(connection, path), digest)) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (!expected.equals(encode(digest.digest()))) throw new IOException("Purge verification checksum mismatch");
    }

    private static InputStream verifyAtEnd(DigestInputStream stream, String expected) {
        return new FilterInputStream(stream) {
            private boolean verified;

            private int check(int count) throws IOException {
                if (count == -1 && !verified) {
                    verified = true;
                    try {
                        MessageDigest copy = (MessageDigest) stream.getMessageDigest().clone();
                        if (!expected.equals(encode(copy.digest()))) throw new IOException("Recall checksum mismatch");
                    } catch (CloneNotSupportedException failure) {
                        throw new IOException("SHA-256 provider does not support checksum snapshots", failure);
                    }
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
