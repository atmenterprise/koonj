package uk.appsthatmatter.koonj.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.entity.ArchiveMode;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Persisted job progress. Processed files include successful and failed attempts.")
public record JobResponse(UUID id, Operation operation, Status status, UUID sourceConnectionId, UUID targetConnectionId,
        ArchiveMode archiveMode, boolean deleteFromTarget, List<String> requestedPaths, int totalFiles, int processedFiles, int failedFiles, long totalBytes,
        long transferredBytes, Instant createdAt, Instant completedAt, String details,
        @Schema(description = "Authenticated account that submitted the job") String triggeredBy) {
    public static JobResponse from(TransferJob job) {
        return new JobResponse(job.getId(), job.getOperation(), job.getStatus(), job.getSourceConnectionId(),
                job.getTargetConnectionId(), job.getArchiveMode(), job.isDeleteFromTarget(), List.copyOf(job.getRequestedPaths()), job.getTotalFiles(),
                job.getProcessedFiles(), job.getFailedFiles(), job.getTotalBytes(), job.getTransferredBytes(),
                job.getCreatedAt(), job.getCompletedAt(), job.getDetails(), job.getTriggeredBy());
    }
}
