package uk.appsthatmatter.koonj.server.service;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.PageResponse;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Status;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;

import uk.appsthatmatter.koonj.server.security.JobActor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class JobService {
    private final TransferJobRepository jobs;
    private final ReentrantLock execution = new ReentrantLock(true);

    public JobService(TransferJobRepository jobs) { this.jobs = jobs; }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths) {
        TransferJob job = new TransferJob();
        job.setOperation(operation);
        job.setTriggeredBy(JobActor.current());
        job.setSourceConnectionId(source);
        job.setTargetConnectionId(target);
        job.setRequestedPaths(paths);
        return jobs.saveAndFlush(job);
    }

    public JobResponse get(UUID id) {
        return JobResponse.from(jobs.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer job was not found")));
    }

    public PageResponse<JobResponse> list(Pageable pageable) {
        return PageResponse.from(jobs.findAll(pageable).map(JobResponse::from));
    }

    @Async("applicationTaskExecutor")
    public void execute(TransferJob job, JobWork work) {
        execution.lock();
        try {
            job.setStatus(Status.RUNNING);
            jobs.save(job);
            try {
                work.run(job);
                job.setStatus(job.getFailedFiles() == 0 ? Status.COMPLETED
                        : job.getProcessedFiles() > job.getFailedFiles() ? Status.PARTIAL_FAILURE : Status.FAILED);
            } catch (Exception failure) {
                appendError(job, StorageErrors.describe(failure));
                job.setStatus(Status.FAILED);
            }
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        } finally {
            execution.unlock();
        }
    }

    public void progress(TransferJob job, long successfulBytes, String error) {
        job.setProcessedFiles(job.getProcessedFiles() + 1);
        job.setTransferredBytes(job.getTransferredBytes() + successfulBytes);
        if (error != null) {
            job.setFailedFiles(job.getFailedFiles() + 1);
            appendError(job, error);
        }
        jobs.save(job);
    }

    public void totals(TransferJob job, int count, long bytes) {
        job.setTotalFiles(count);
        job.setTotalBytes(bytes);
        jobs.save(job);
    }

    private void appendError(TransferJob job, String error) {
        String existing = job.getDetails();
        if (existing != null && existing.length() >= 8000) return;
        String message = error.substring(0, Math.min(error.length(), 2000));
        job.setDetails(existing == null ? message : existing + "\n" + message);
    }

    @FunctionalInterface
    public interface JobWork {
        void run(TransferJob job) throws Exception;
    }
}
