package uk.appsthatmatter.koonj.server.dto;

import uk.appsthatmatter.koonj.server.entity.FileMovement;
import uk.appsthatmatter.koonj.server.entity.FileMovement.Operation;
import uk.appsthatmatter.koonj.server.entity.FileMovement.Outcome;

import java.time.Instant;
import java.util.UUID;

public record FileMovementResponse(long id, UUID jobId, Instant recordedAt, Operation operation, Outcome outcome,
        String sourceLocation, String sourcePath, String targetLocation, String targetPath, String symlinkReference,
        String sourceChecksum, String targetChecksum, long sizeBytes, boolean archiveRemoved, String error) {
    public static FileMovementResponse from(FileMovement movement) {
        return new FileMovementResponse(movement.getId(), movement.getJobId(), movement.getRecordedAt(), movement.getOperation(),
                movement.getOutcome(), movement.getSourceLocation(), movement.getSourcePath(), movement.getTargetLocation(),
                movement.getTargetPath(), movement.getSymlinkReference(), movement.getSourceChecksum(), movement.getTargetChecksum(),
                movement.getSizeBytes(), movement.isArchiveRemoved(), movement.getError());
    }
}
