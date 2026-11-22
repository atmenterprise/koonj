package uk.appsthatmatter.koonj.server.dto;

import uk.appsthatmatter.koonj.server.entity.JobEvent;

import java.time.Instant;

public record JobEventResponse(long id, Instant recordedAt, JobEvent.Level level, String path, String message) {
    public static JobEventResponse from(JobEvent event) {
        return new JobEventResponse(event.getId(), event.getRecordedAt(), event.getLevel(), event.getPath(), event.getMessage());
    }
}
