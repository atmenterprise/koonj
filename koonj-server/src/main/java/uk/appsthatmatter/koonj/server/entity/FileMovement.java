package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_movements", indexes = {
        @Index(columnList = "source_connection_id, source_path, id"),
        @Index(columnList = "target_connection_id, target_path, id"),
        @Index(columnList = "job_id, id"),
        @Index(columnList = "job_id, outcome, id")
})
@Getter
@Setter
public class FileMovement {
    public enum Operation { ARCHIVE_COPY, ARCHIVE_PURGE, RECALL }
    public enum Outcome { VERIFIED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private UUID jobId;
    @Column(nullable = false)
    private Instant recordedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Operation operation;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Outcome outcome;
    @Column(nullable = false)
    private UUID sourceConnectionId;
    @Column(nullable = false, columnDefinition = "text")
    private String sourceLocation;
    @Column(nullable = false, columnDefinition = "text")
    private String sourcePath;
    @Column(nullable = false)
    private UUID targetConnectionId;
    @Column(nullable = false, columnDefinition = "text")
    private String targetLocation;
    @Column(nullable = false, columnDefinition = "text")
    private String targetPath;
    @Column(columnDefinition = "text")
    private String symlinkReference;
    @Column(length = 64)
    private String sourceChecksum;
    @Column(length = 64)
    private String targetChecksum;
    @Column(nullable = false)
    private long sizeBytes;
    @Column(nullable = false)
    private boolean archiveRemoved;
    @Column(columnDefinition = "text")
    private String error;
}
