package uk.appsthatmatter.koonj.server.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.exception.SdkException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class CatalogScanService {
    private final ConnectionService connections;
    private final StorageConnectorFactory connectors;
    private final FileMetadataRepository files;
    private final JobService jobs;
    private final TransactionTemplate transaction;

    public CatalogScanService(ConnectionService connections, StorageConnectorFactory connectors,
                              FileMetadataRepository files, JobService jobs, TransactionTemplate transaction) {
        this.connections = connections;
        this.connectors = connectors;
        this.files = files;
        this.jobs = jobs;
        this.transaction = transaction;
    }

    public JobResponse submit(UUID id) {
        connections.active(id);
        TransferJob job = jobs.create(TransferJob.Operation.SCAN, id, null, List.of());
        JobResponse accepted = JobResponse.from(job);
        jobs.execute(job, this::scan);
        return accepted;
    }

    private void scan(TransferJob job) throws java.io.IOException {
        StorageConnection source = connections.active(job.getSourceConnectionId());
        StorageConnector connector = connectors.resolve(source.getProviderType());
        List<ScannedFileInfo> discovered = connector.listFiles(source, "");
        jobs.totals(job, discovered.size(), discovered.stream().mapToLong(ScannedFileInfo::sizeBytes).sum());
        transaction.executeWithoutResult(status -> {
            var stale = new HashMap<String, FileMetadata>();
            var objects = new HashSet<String>();
            files.findByConnectionId(source.getId()).forEach(file -> stale.put(file.getRelativePath(), file));
            for (ScannedFileInfo found : discovered) {
                FileMetadata file = stale.remove(found.relativePath());
                if (file == null) file = newEntry(source, found.relativePath());
                if (file.getId() != null && found.entryType() == EntryType.SYMLINK
                        && (LocalStorageConnector.ARCHIVE_REFERENCE + file.getId()).equals(found.symlinkTarget())
                        && file.getTargetConnection() != null && file.getS3Key() != null) {
                    file.setEntryType(EntryType.SYMLINK);
                    file.setSymlinkTarget(found.symlinkTarget());
                    if (file.getStorageState() != StorageState.FAILED) file.setStorageState(StorageState.ARCHIVED_SYMLINK);
                    files.save(file);
                    continue;
                }
                boolean changed = file.getEntryType() != found.entryType() || file.getSizeBytes() != found.sizeBytes()
                        || !Objects.equals(file.getLastModifiedAt(), found.lastModifiedAt())
                        || !Objects.equals(file.getSymlinkTarget(), found.symlinkTarget());
                if (changed) {
                    file.setChecksumSha256(null);
                    file.setErrorMessage(null);
                    if (found.entryType() != EntryType.SYMLINK
                            || !Objects.equals(file.getSymlinkTarget(), found.symlinkTarget())) {
                        file.setArchivedAt(null);
                        file.setTargetConnection(null);
                        file.setS3Key(null);
                        file.setS3StorageClass(null);
                    }
                }
                if (connector.objectStorage()) {
                    if (found.entryType() == EntryType.FILE) objects.add(found.relativePath());
                    file.setStorageState(StorageState.ARCHIVED);
                    file.setS3Key(found.relativePath());
                    file.setS3StorageClass(found.storageClass());
                } else if (found.symlinkTarget() != null && found.symlinkTarget().startsWith("s3://")) {
                    if (changed || file.getStorageState() != StorageState.FAILED) {
                        file.setStorageState(StorageState.ARCHIVED_SYMLINK);
                    }
                } else if (changed || file.getStorageState() == StorageState.PURGED_LOCAL) {
                    file.setStorageState(StorageState.LOCAL_ONLY);
                } else if (file.getStorageState() == StorageState.ARCHIVED) {
                    file.setStorageState(StorageState.ARCHIVED_COPY);
                }
                updatePhysical(file, found);
                files.save(file);
            }
            files.deleteAll(stale.values());
            if (connector.objectStorage()) clearMissingArchiveCopies(source.getId(), key -> !objects.contains(key));
        });
        jobs.scanned(job, discovered.size());
    }

    void clearMissingArchiveCopies(UUID bucketId, Predicate<String> missing) {
        var states = List.of(StorageState.ARCHIVED_COPY, StorageState.ARCHIVED);
        for (FileMetadata copy : files.findByTargetConnectionIdAndStorageStateIn(bucketId, states)) {
            if (copy.getEntryType() == EntryType.FILE && missing.test(copy.getS3Key())) {
                copy.setStorageState(StorageState.LOCAL_ONLY);
                copy.setTargetConnection(null);
                copy.setS3Key(null);
                copy.setS3StorageClass(null);
                files.save(copy);
            }
        }
    }

    FileMetadata entry(StorageConnection connection, String path) {
        return files.findByConnectionIdAndRelativePath(connection.getId(), path)
                .orElseGet(() -> newEntry(connection, path));
    }

    private static FileMetadata newEntry(StorageConnection connection, String path) {
        FileMetadata file = new FileMetadata();
        file.setConnection(connection);
        file.setRelativePath(path);
        String name = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        file.setFileName(name.substring(name.lastIndexOf('/') + 1));
        return file;
    }

    static void updatePhysical(FileMetadata file, ScannedFileInfo found) {
        file.setEntryType(found.entryType());
        file.setSymlinkTarget(found.symlinkTarget());
        file.setSizeBytes(found.sizeBytes());
        file.setLastModifiedAt(found.lastModifiedAt());
    }

    public FileResponse get(UUID id) {
        return files.findById(id).map(FileResponse::from).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog entry was not found"));
    }

    public FileAttributes attributes(UUID id) {
        FileResponse file = get(id);
        if (file.entryType() != EntryType.FILE) return FileAttributes.EMPTY;
        StorageConnection source = connections.get(file.connectionId());
        if (!source.isActive()) return FileAttributes.EMPTY;
        try {
            return connectors.resolve(source.getProviderType()).fileAttributes(source, file.relativePath());
        } catch (IOException | SdkException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "File attributes could not be read from storage", failure);
        }
    }

    public PageResponse<FileResponse> list(UUID connectionId, StorageState state, String prefix, Pageable pageable) {
        Specification<FileMetadata> filter = (root, query, cb) ->
                cb.notEqual(root.get("storageState"), StorageState.PURGED_LOCAL);
        if (connectionId != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("connection").get("id"), connectionId));
        }
        if (state != null) filter = filter.and((root, query, cb) -> cb.equal(root.get("storageState"), state));
        if (prefix != null) {
            String normalized = StoragePaths.selection(prefix);
            if (!normalized.isEmpty() && prefix.endsWith("/")) normalized += "/";
            String value = normalized.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            filter = filter.and((root, query, cb) -> cb.like(root.get("relativePath"), value, '!'));
        }
        return PageResponse.from(files.findAll(filter, pageable).map(FileResponse::from));
    }
}
