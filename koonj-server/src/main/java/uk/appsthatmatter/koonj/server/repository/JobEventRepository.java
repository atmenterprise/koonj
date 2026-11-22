package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import uk.appsthatmatter.koonj.server.entity.JobEvent;

import java.util.UUID;

public interface JobEventRepository extends JpaRepository<JobEvent, Long> {
    Page<JobEvent> findByJobIdOrderByIdAsc(UUID jobId, Pageable page);
    Page<JobEvent> findByJobIdAndLevelOrderByIdAsc(UUID jobId, JobEvent.Level level, Pageable page);
}
