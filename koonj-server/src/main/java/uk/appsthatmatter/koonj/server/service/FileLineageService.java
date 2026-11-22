package uk.appsthatmatter.koonj.server.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import uk.appsthatmatter.koonj.server.connector.StoragePaths;
import uk.appsthatmatter.koonj.server.dto.FileMovementResponse;
import uk.appsthatmatter.koonj.server.dto.PageResponse;
import uk.appsthatmatter.koonj.server.entity.FileMovement;
import uk.appsthatmatter.koonj.server.entity.StorageConnection;
import uk.appsthatmatter.koonj.server.repository.FileMovementRepository;
import uk.appsthatmatter.koonj.server.repository.StorageConnectionRepository;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
public class FileLineageService {
    private static final PageRequest EXPORT_BATCH = PageRequest.of(0, 1000);
    private final FileMovementRepository movements;
    private final StorageConnectionRepository connections;

    public FileLineageService(FileMovementRepository movements, StorageConnectionRepository connections) {
        this.movements = movements;
        this.connections = connections;
    }

    public PageResponse<FileMovementResponse> forFile(UUID connectionId, String path, int page, int size) {
        return PageResponse.from(movements.forFile(connectionId, relativePath(path), PageRequest.of(page, size))
                .map(FileMovementResponse::from));
    }

    public StreamingResponseBody folderReport(UUID connectionId, String path) {
        String prefix = relativePath(path) + "/";
        String pattern = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String location = connections.findById(connectionId).map(StorageConnection::getName).orElse(connectionId.toString());
        long lastId = movements.latestId();
        return output -> {
            var csv = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            row(csv, "catalog_location", "file_path", "outcome", "operation", "recorded_at_utc", "job_id",
                    "source_connection_id", "source_location", "source_path", "target_connection_id", "target_location", "target_path",
                    "symlink_reference", "source_sha256", "target_sha256", "size_bytes", "archive_removed", "error");
            csv.flush();
            List<FileMovement> batch;
            long after = 0;
            do {
                batch = movements.forFolder(connectionId, pattern, after, lastId, EXPORT_BATCH);
                for (FileMovement movement : batch) {
                    boolean source = connectionId.equals(movement.getSourceConnectionId());
                    row(csv, source ? movement.getSourceLocation() : movement.getTargetLocation(),
                            source ? movement.getSourcePath() : movement.getTargetPath(),
                            movement.getOutcome(), movement.getOperation(), movement.getRecordedAt(), movement.getJobId(),
                            movement.getSourceConnectionId(), movement.getSourceLocation(), movement.getSourcePath(),
                            movement.getTargetConnectionId(), movement.getTargetLocation(), movement.getTargetPath(),
                            movement.getSymlinkReference(), movement.getSourceChecksum(), movement.getTargetChecksum(),
                            movement.getSizeBytes(), movement.isArchiveRemoved(), movement.getError());
                    after = movement.getId();
                }
                csv.flush();
            } while (batch.size() == EXPORT_BATCH.getPageSize());

            String afterPath = "";
            List<String> entries;
            do {
                entries = movements.withoutRecordedMovements(connectionId, pattern, afterPath, lastId, EXPORT_BATCH);
                for (String entry : entries) {
                    row(csv, location, entry, "NO_RECORD", null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null);
                    afterPath = entry;
                }
                csv.flush();
            } while (entries.size() == EXPORT_BATCH.getPageSize());
        };
    }

    private static String relativePath(String path) {
        String normalized = StoragePaths.selection(path);
        if (normalized.isEmpty()) throw new IllegalArgumentException("A file or folder path is required");
        return normalized;
    }

    private static void row(BufferedWriter csv, Object... cells) throws IOException {
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) csv.write(',');
            String value = cells[index] == null ? "" : cells[index].toString();
            String leading = value.stripLeading();
            csv.write('"');
            // Protect reports opened in spreadsheet software from formula-like paths or labels.
            if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) csv.write('\'');
            csv.write(value.replace("\"", "\"\""));
            csv.write('"');
        }
        csv.write("\r\n");
    }
}
