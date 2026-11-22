package uk.appsthatmatter.koonj.server.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import uk.appsthatmatter.koonj.server.dto.JobResponse;
import uk.appsthatmatter.koonj.server.dto.JobEventResponse;
import uk.appsthatmatter.koonj.server.dto.FileMovementResponse;
import uk.appsthatmatter.koonj.server.dto.PageResponse;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.entity.ArchiveMode;
import uk.appsthatmatter.koonj.server.entity.FileMovement;
import uk.appsthatmatter.koonj.server.entity.JobEvent;
import uk.appsthatmatter.koonj.server.entity.JobEvent.Level;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Status;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;
import uk.appsthatmatter.koonj.server.repository.JobEventRepository;
import uk.appsthatmatter.koonj.server.repository.FileMovementRepository;

import uk.appsthatmatter.koonj.server.security.JobActor;

import java.nio.file.FileSystemException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
    private final JobEventRepository events;
    private final FileMovementRepository movements;
    private final ObservationRegistry observations;
    private final TransactionTemplate transaction;
    public static final int INTERACTIVE = 100;
    private static final int BACKGROUND = 5;

    private final AtomicLong sequence = new AtomicLong();
    private final PriorityBlockingQueue<Runnable> pending = new PriorityBlockingQueue<>(11,
            Comparator.comparingInt((Runnable task) -> ((QueuedJob) task).priority()).reversed()
                    .thenComparingLong(task -> ((QueuedJob) task).sequence()));
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, pending,
            Thread.ofPlatform().name("koonj-transfer").factory());

    public JobService(TransferJobRepository jobs, JobEventRepository events, FileMovementRepository movements,
                      ObservationRegistry observations, TransactionTemplate transaction) {
        this.jobs = jobs;
        this.events = events;
        this.movements = movements;
        this.observations = observations;
        this.transaction = transaction;
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
        job.setTriggeredBy(Objects.requireNonNullElse(JobActor.current(), "System"));
        job.setSourceConnectionId(source);
        job.setTargetConnectionId(target);
        job.setRequestedPaths(paths);
        return transaction.execute(status -> {
            TransferJob saved = jobs.saveAndFlush(job);
            log(saved.getId(), saved.getCreatedAt(), Level.INFO, null, "Job queued");
            return saved;
        });
    }

    public JobResponse get(UUID id) {
        return JobResponse.from(jobs.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer job was not found")));
    }

    public PageResponse<JobResponse> list(Pageable pageable) {
        return PageResponse.from(jobs.findAll(pageable).map(JobResponse::from));
    }

    public PageResponse<JobEventResponse> events(UUID jobId, boolean errorsOnly, Pageable page) {
        requireJob(jobId);
        var result = errorsOnly ? events.findByJobIdAndLevelOrderByIdAsc(jobId, Level.ERROR, page)
                : events.findByJobIdOrderByIdAsc(jobId, page);
        return PageResponse.from(result.map(JobEventResponse::from));
    }

    public PageResponse<FileMovementResponse> fileResults(UUID jobId, boolean failedOnly, Pageable page) {
        requireJob(jobId);
        var result = failedOnly ? movements.findByJobIdAndOutcomeOrderByIdAsc(jobId, FileMovement.Outcome.FAILED, page)
                : movements.findByJobIdOrderByIdAsc(jobId, page);
        return PageResponse.from(result.map(FileMovementResponse::from));
    }

    private void requireJob(UUID jobId) {
        if (!jobs.existsById(jobId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer job was not found");
    }

    void log(UUID jobId, Instant recordedAt, Level level, String path, String message) {
        var event = new JobEvent();
        event.setJobId(jobId);
        event.setRecordedAt(recordedAt);
        event.setLevel(level);
        event.setPath(path);
        event.setMessage(message);
        events.save(event);
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
            job.setStartedAt(Instant.now());
            transaction.executeWithoutResult(status -> {
                jobs.save(job);
                log(job.getId(), job.getStartedAt(), Level.INFO, null, "Execution started");
            });
            String executionError = null;
            String executionPath = null;
            try {
                work.run(job);
                job.setStatus(job.getFailedFiles() == 0 ? Status.COMPLETED
                        : job.getProcessedFiles() > job.getFailedFiles() ? Status.PARTIAL_FAILURE : Status.FAILED);
            } catch (Exception failure) {
                executionError = StorageErrors.describe(failure);
                if (failure instanceof FileSystemException filesystem) executionPath = filesystem.getFile();
                appendError(job, executionError);
                job.setStatus(Status.FAILED);
            }
            job.setCompletedAt(Instant.now());
            String error = executionError;
            String path = executionPath;
            transaction.executeWithoutResult(status -> {
                jobs.save(job);
                if (error != null) log(job.getId(), job.getCompletedAt(), Level.ERROR, path, error);
                String result = switch (job.getStatus()) {
                    case COMPLETED -> "Execution completed";
                    case PARTIAL_FAILURE -> "Execution completed with failures";
                    case FAILED -> "Execution failed";
                    default -> throw new IllegalStateException("Job has no terminal status");
                };
                log(job.getId(), job.getCompletedAt(), job.getStatus() == Status.COMPLETED ? Level.INFO : Level.WARNING, null, result);
            });
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
        transaction.executeWithoutResult(status -> {
            jobs.save(job);
            String items = job.getOperation() == Operation.SCAN ? " catalog entries" : " transfer items";
            log(job.getId(), Instant.now(), Level.INFO, null, "Discovered " + count + items + " (" + bytes + " bytes)");
        });
    }

    public void selectionFailed(TransferJob job, String path, String reason) {
        transaction.executeWithoutResult(status -> {
            progress(job, 0, path + ": " + reason);
            log(job.getId(), Instant.now(), Level.ERROR, path, reason);
        });
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
