package uk.appsthatmatter.koonj.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import uk.appsthatmatter.koonj.server.entity.ProjectAccount;
import java.util.Optional;

public interface ProjectAccountRepository extends JpaRepository<ProjectAccount, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ProjectAccount a where a.subject = :subject")
    Optional<ProjectAccount> lock(String subject);

}
