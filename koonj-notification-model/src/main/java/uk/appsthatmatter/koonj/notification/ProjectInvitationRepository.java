package uk.appsthatmatter.koonj.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, UUID> {
    List<ProjectInvitation> findByProjectIdOrderByEmail(UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProjectInvitation> findByProjectIdAndEmail(UUID id, String email);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProjectInvitation> findByIdAndProjectId(UUID invitationId, UUID projectId);
    @Query("select i.projectId from ProjectInvitation i where i.tokenDigest = :digest")
    Optional<UUID> projectForToken(String digest);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProjectInvitation> findByTokenDigest(String digest);
    @Query(value = "select * from project_invitation where send_after <= current_timestamp and expires_at > current_timestamp order by send_after limit 1 for update skip locked", nativeQuery = true)
    Optional<ProjectInvitation> claimMail();
}
