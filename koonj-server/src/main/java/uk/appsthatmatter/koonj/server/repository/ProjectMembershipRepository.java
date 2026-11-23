package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.entity.ProjectMembership;
import java.util.List;
import java.util.UUID;

public interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, UUID> {
    List<ProjectMembership> findByAccountSubjectOrderByProjectNameAscProjectIdAsc(String subject);
    List<ProjectMembership> findByProjectIdOrderByAccountDisplayNameAscAccountSubjectAsc(UUID projectId);
    boolean existsByProjectIdAndAccountSubject(UUID projectId, String subject);
    long countByAccountSubject(String subject);
    void deleteByProjectIdAndAccountSubject(UUID projectId, String subject);
}
