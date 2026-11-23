package uk.appsthatmatter.koonj.server.notification;

import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.notification.NotificationEvent.Type;
import uk.appsthatmatter.koonj.server.entity.ArchiveMode;
import uk.appsthatmatter.koonj.server.entity.TransferJob;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

public final class JobNotification {
    private JobNotification() {}

    public static NotificationEvent job(Type type, TransferJob job, String path, String reason) {
        Instant now = job.getCompletedAt() == null ? Instant.now() : job.getCompletedAt();
        boolean purge = job.getOperation() == TransferJob.Operation.ARCHIVE && job.getArchiveMode() == ArchiveMode.PURGE;
        String operation = purge ? "PURGE" : job.getOperation().name();
        var details = new LinkedHashMap<String, Object>();
        details.put("jobId", job.getId());
        details.put("projectId", job.getProjectId());
        details.put("operation", operation);
        details.put("status", job.getStatus().name());
        details.put("sourceConnectionId", job.getSourceConnectionId());
        if (job.getTargetConnectionId() != null) details.put("targetConnectionId", job.getTargetConnectionId());
        Integer scannedFiles = job.getScannedFileCount();
        if (scannedFiles != null) details.put("catalogEntries", job.getProcessedFiles());
        details.put("fileCount", scannedFiles == null
                ? Math.max(job.getTotalFiles(), job.getProcessedFiles()) + (long) job.getSkippedFiles() : scannedFiles);
        details.put("totalBytes", job.getTotalBytes());
        details.put("bytesMoved", job.getTransferredBytes());
        details.put("succeeded", scannedFiles == null ? Math.max(0, job.getProcessedFiles() - job.getFailedFiles()) : scannedFiles);
        details.put("failed", job.getFailedFiles());
        details.put("skipped", job.getSkippedFiles());
        details.put("durationSeconds", job.getStartedAt() == null ? 0 : Duration.between(job.getStartedAt(), now).toSeconds());
        if (path != null) details.put("filePath", abbreviated(path));
        if (reason != null) details.put("reason", abbreviated(reason));
        String title = type == Type.TRANSFER_FAILED ? "Koonj transfer failure" : "Koonj batch completed";
        return new NotificationEvent(type, job.getId().toString(), job.getOwnerSubject(), job.getOwnerEmail(), title, details);
    }

    private static String abbreviated(String value) {
        return value.substring(0, Math.min(value.length(), 2_000));
    }
}
