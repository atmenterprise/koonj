package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "storage_connections")
@Getter
@Setter
public class StorageConnection {
    @Id @GeneratedValue
    private UUID id;
    @Column(nullable = false, unique = true)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ProviderType providerType;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private StorageRole role;
    @Column(nullable = false, columnDefinition = "text")
    private String basePathOrBucket;
    @Column(columnDefinition = "text")
    private String endpointUrl;
    private String region;
    private String accessKeyOrUsername;
    @Column(columnDefinition = "text")
    private String secretKeyOrPassword;
    private boolean pathStyleAccessEnabled = true;
    private boolean active = true;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
