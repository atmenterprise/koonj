package uk.appsthatmatter.koonj.server.entity;

import uk.appsthatmatter.koonj.server.security.ProjectScope;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.ColumnDefault;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "storage_connections", uniqueConstraints =
        @UniqueConstraint(name = "uk_storage_project_name", columnNames = {"project_id", "name"}))
@Check(name = "storage_connections_credential_envelope_check",
        constraints = "(auth_mode = 'ENCRYPTED_CREDENTIALS') = (credential_envelope is not null)")
@Getter
@Setter
public class StorageConnection {
    @Id
    private UUID id;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID();
    }

    @Column(nullable = false)
    @ColumnDefault("'" + ProjectScope.LEGACY_VALUE + "'")
    private UUID projectId = ProjectScope.LEGACY;

    @Column(nullable = false)
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StorageAuthMode authMode;
    private String secretRef;
    @JsonIgnore
    @Column(length = 16384)
    private byte[] credentialEnvelope;
    private String awsRoleArn;
    private String awsExternalId;
    private String objectPrefix;
    private boolean pathStyleAccessEnabled = true;
    private boolean active = true;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
