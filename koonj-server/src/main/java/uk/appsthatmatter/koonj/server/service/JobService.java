package uk.appsthatmatter.koonj.server.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.PageResponse;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.entity.ArchiveMode;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Status;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;

import uk.appsthatmatter.koonj.server.security.JobActor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class JobService {
    private final TransferJobRepository jobs;
    private final ObservationRegistry observations;
    public static final int INTERACTIVE = 100;
    private static final int BACKGROUND = 5;

    private final AtomicLong sequence = new AtomicLong();
    private final PriorityBlockingQueue<Runnable> pending = new PriorityBlockingQueue<>(11,
            Comparator.comparingInt((Runnable task) -> ((QueuedJob) task).priority()).reversed()
                    .thenComparingLong(task -> ((QueuedJob) task).sequence()));
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, pending,
            Thread.ofPlatform().name("koonj-transfer").factory());

    public JobService(TransferJobRepository jobs, ObservationRegistry observations) {
        this.jobs = jobs;
        this.observations = observations;
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths) {
        return create(operation, source, target, paths, ArchiveMode.COPY, false);
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths,
                              ArchiveMode archiveMode, boolean deleteFromTarget) {
        return create(operation, source, target, paths, archiveMode, deleteFromTarget, BACKGROUND);
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths,
                              ArchiveMode archiveMode, boolean deleteFromTarget, int priority) {
        TransferJob job = new TransferJob();
        job.setPriority(priority);
        job.setArchiveMode(archiveMode);
        job.setDeleteFromTarget(deleteFromTarget);
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

    public CompletableFuture<JobResponse> execute(TransferJob job, JobWork work) {
        var completion = new CompletableFuture<JobResponse>();
        worker.execute(new QueuedJob(job.getPriority(), sequence.getAndIncrement(), () -> {
            try {
                run(job, work);
                completion.complete(JobResponse.from(job));
            } catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
            }
        }));
        return completion;
    }

    // Yield only between complete files so another transfer cannot race an atomic replacement.
    void runWaitingInteractiveJobs() {
        while (pending.peek() instanceof QueuedJob next && next.priority() == INTERACTIVE && pending.remove(next)) {
            next.run();
        }
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    private record QueuedJob(int priority, long sequence, Runnable task) implements Runnable {
        @Override
        public void run() { task.run(); }
    }

    private void run(TransferJob job, JobWork work) {
        Observation activity = Observation.createNotStarted("koonj.job.execution", observations)
                .lowCardinalityKeyValue("operation", job.getOperation().name());
        try {
            activity.start();
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
            activity.lowCardinalityKeyValue("outcome", job.getStatus().name());
        } catch (RuntimeException failure) {
            activity.lowCardinalityKeyValue("outcome", "FAILED");
            throw failure;
        } finally {
            activity.stop();
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

    public void scanned(TransferJob job, int count) {
        job.setProcessedFiles(count);
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
