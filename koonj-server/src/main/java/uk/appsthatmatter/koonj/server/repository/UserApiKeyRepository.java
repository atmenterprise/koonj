package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.UserApiKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, UUID> {
    Optional<UserApiKey> findByTokenHash(String tokenHash);

    @Query("""
            select k from UserApiKey k where k.ownerSubject = :owner and k.projectId = :project
            and k.revoked = false and (k.expiresAt is null or k.expiresAt > :now) order by k.createdAt desc
            """)
    List<UserApiKey> activeForOwner(String owner, UUID project, Instant now);

    @Query("""
            select count(k) > 0 from UserApiKey k where k.id = :id
            and k.revoked = false and (k.expiresAt is null or k.expiresAt > :now)
            """)
    boolean isActive(UUID id, Instant now);

    @Modifying
    @Query("""
            update UserApiKey k set k.lastUsedAt = :now where k.id = :id and k.revoked = false
            and (k.expiresAt is null or k.expiresAt > :now) and (k.lastUsedAt is null or k.lastUsedAt < :now)
            """)
    void recordUse(UUID id, Instant now);

    @Modifying
    @Query("update UserApiKey k set k.revoked = true where k.id = :id and k.ownerSubject = :owner and k.projectId = :project")
    int revoke(UUID id, String owner, UUID project);
}
