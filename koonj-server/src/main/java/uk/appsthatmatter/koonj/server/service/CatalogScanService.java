package uk.appsthatmatter.koonj.server.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import uk.appsthatmatter.koonj.server.connector.*;
import uk.appsthatmatter.koonj.server.dto.*;
import uk.appsthatmatter.koonj.server.entity.*;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class CatalogScanService {
    private final ConnectionService connections;
    private final StorageConnectorFactory connectors;
    private final FileMetadataRepository files;
    private final JobService jobs;

    public CatalogScanService(ConnectionService connections, StorageConnectorFactory connectors,
                              FileMetadataRepository files, JobService jobs) {
        this.connections = connections;
        this.connectors = connectors;
        this.files = files;
        this.jobs = jobs;
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
        for (ScannedFileInfo found : discovered) {
            String error = null;
            try {
                FileMetadata file = entry(source, found);
                boolean changed = file.getSizeBytes() != found.sizeBytes()
                        || !Objects.equals(file.getLastModifiedAt(), found.lastModifiedAt());
                if (connector.objectStorage()) {
                    file.setStorageState(StorageState.ARCHIVED);
                    file.setS3Key(found.relativePath());
                    if (found.storageClass() != null) file.setS3StorageClass(found.storageClass());
                    if (changed) file.setChecksumSha256(null);
                } else if (changed || file.getStorageState() == StorageState.PURGED_LOCAL) {
                    file.setStorageState(StorageState.LOCAL_ONLY);
                    if (file.getTargetConnection() == null) file.setChecksumSha256(null);
                    file.setErrorMessage(null);
                }
                file.setSizeBytes(found.sizeBytes());
                file.setLastModifiedAt(found.lastModifiedAt());
                files.save(file);
            } catch (RuntimeException failure) {
                error = found.relativePath() + ": " + StorageErrors.describe(failure);
            }
            jobs.progress(job, 0, error);
        }
    }

    FileMetadata entry(StorageConnection source, ScannedFileInfo found) {
        return files.findBySourceConnectionIdAndRelativePath(source.getId(), found.relativePath()).orElseGet(() -> {
            FileMetadata file = new FileMetadata();
            file.setSourceConnection(source);
            file.setRelativePath(found.relativePath());
            file.setFileName(found.relativePath().substring(found.relativePath().lastIndexOf('/') + 1));
            return file;
        });
    }

    public PageResponse<FileResponse> list(UUID connectionId, StorageState state, String prefix, Pageable pageable) {
        Specification<FileMetadata> filter = Specification.unrestricted();
        if (connectionId != null) {
            filter = filter.and((root, query, cb) -> cb.or(cb.equal(root.get("sourceConnection").get("id"), connectionId),
                    cb.equal(root.get("targetConnection").get("id"), connectionId)));
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
