package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_metadata",
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_connection_id", "relative_path"}),
        indexes = {@Index(columnList = "relative_path"), @Index(columnList = "storage_state"),
                @Index(columnList = "target_connection_id, s3_key")})
@Getter
@Setter
public class FileMetadata {
    @Id @GeneratedValue
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_connection_id")
    private StorageConnection connection;
    @ManyToOne(fetch = FetchType.LAZY)
    private StorageConnection targetConnection;
    @Column(nullable = false, columnDefinition = "text")
    private String relativePath;
    @Column(nullable = false, columnDefinition = "text")
    private String fileName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @ColumnDefault("'FILE'")
    private EntryType entryType = EntryType.FILE;
    @Column(columnDefinition = "text")
    private String symlinkTarget;
    private long sizeBytes;
    @Column(length = 64)
    private String checksumSha256;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private StorageState storageState = StorageState.LOCAL_ONLY;
    @Column(name = "s3_key", columnDefinition = "text")
    private String s3Key;
    @Column(name = "s3_storage_class")
    private String s3StorageClass = "STANDARD";
    private Instant lastModifiedAt;
    private Instant archivedAt;
    @Column(columnDefinition = "text")
    private String errorMessage;
}
