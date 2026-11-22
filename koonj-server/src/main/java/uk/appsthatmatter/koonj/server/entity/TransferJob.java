package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transfer_jobs", indexes = {@Index(columnList = "created_at"), @Index(columnList = "status")})
@Getter
@Setter
public class TransferJob {
    public enum Operation { ARCHIVE, RECALL, SCAN }
    public enum Status { PENDING, RUNNING, COMPLETED, PARTIAL_FAILURE, FAILED }

    @Id @GeneratedValue
    private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Operation operation;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private UUID sourceConnectionId;
    private UUID targetConnectionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @ColumnDefault("'COPY'")
    private ArchiveMode archiveMode = ArchiveMode.COPY;
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean deleteFromTarget;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> requestedPaths = new ArrayList<>();
    @Column(nullable = false)
    @ColumnDefault("5")
    private int priority = 5;
    private int totalFiles;
    private int processedFiles;
    private int failedFiles;
    private long totalBytes;
    private long transferredBytes;
    @Column(columnDefinition = "text")
    private String triggeredBy;
    @CreationTimestamp
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    @Column(columnDefinition = "text")
    private String details;
}
