package uk.appsthatmatter.koonj.server.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "user_api_key", indexes = {
        @Index(name = "uk_user_api_key_hash", columnList = "token_hash", unique = true),
        @Index(name = "idx_user_api_key_owner", columnList = "owner_subject, project_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserApiKey {
    @Id @GeneratedValue
    private UUID id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 64)
    private String tokenHash;
    @Column(nullable = false, length = 24)
    private String tokenPrefix;
    @Column(nullable = false)
    private String ownerSubject;
    @Column(nullable = false)
    private String ownerName;
    @Column(nullable = false)
    private UUID projectId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Set<String> roles;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    @Column(nullable = false)
    private boolean revoked;

    public UserApiKey(String name, String tokenHash, String tokenPrefix, String ownerSubject, String ownerName,
                      UUID projectId, Set<String> roles, Instant createdAt, Instant expiresAt) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.tokenPrefix = tokenPrefix;
        this.ownerSubject = ownerSubject;
        this.ownerName = ownerName;
        this.projectId = projectId;
        this.roles = Set.copyOf(roles);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
}
