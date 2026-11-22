package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_events", indexes = @Index(columnList = "job_id, id"))
@Getter
@Setter
public class JobEvent {
    public enum Level { INFO, WARNING, ERROR }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private UUID jobId;
    @Column(nullable = false)
    private Instant recordedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level level;
    @Column(columnDefinition = "text")
    private String path;
    @Column(nullable = false, columnDefinition = "text")
    private String message;
}
