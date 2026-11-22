package uk.appsthatmatter.koonj.server.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uk.appsthatmatter.koonj.server.entity.JobEvent;

import java.util.UUID;
import java.util.List;

public interface JobEventRepository extends JpaRepository<JobEvent, Long> {
    @Query("select e.path from JobEvent e where e.jobId = :jobId and e.path is not null and e.level = uk.appsthatmatter.koonj.server.entity.JobEvent.Level.ERROR")
    List<String> failedPaths(UUID jobId);
    Page<JobEvent> findByJobIdOrderByIdAsc(UUID jobId, Pageable page);
    Page<JobEvent> findByJobIdAndLevelOrderByIdAsc(UUID jobId, JobEvent.Level level, Pageable page);
}
