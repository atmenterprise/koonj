package uk.appsthatmatter.koonj.server.service;

import uk.appsthatmatter.koonj.server.security.ProjectScope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.data.domain.PageRequest;
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
import uk.appsthatmatter.koonj.server.entity.TransferPlan;
import uk.appsthatmatter.koonj.server.entity.ArchiveMode;
import uk.appsthatmatter.koonj.server.entity.FileMovement;
import uk.appsthatmatter.koonj.server.entity.JobEvent;
import uk.appsthatmatter.koonj.server.entity.JobEvent.Level;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Operation;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Status;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;
import uk.appsthatmatter.koonj.server.repository.JobEventRepository;
import uk.appsthatmatter.koonj.server.repository.FileMovementRepository;

import uk.appsthatmatter.koonj.server.notification.JobNotification;
import uk.appsthatmatter.koonj.server.notification.NotificationOutboxWriter;
import uk.appsthatmatter.koonj.notification.NotificationEvent;
import uk.appsthatmatter.koonj.server.security.JobActor;

import java.nio.file.FileSystemException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
public class JobService {
    private final TransferJobRepository jobs;
    private final JobEventRepository events;
    private final FileMovementRepository movements;
    private final ObservationRegistry observations;
    private final TransactionTemplate transaction;
    private final NotificationOutboxWriter notificationEvents;
    private final AtomicLong peakTransferConcurrency = new AtomicLong();
    public static final int INTERACTIVE = 100;
    static final int BACKGROUND = 5;
    public enum Action { PAUSE, RESUME, STOP, RESTART }
    private final ConcurrentHashMap<UUID, Status> interruptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> activeFiles = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();
    private final PriorityBlockingQueue<Runnable> pending = new PriorityBlockingQueue<>(11,
            Comparator.comparingInt((Runnable task) -> ((QueuedJob) task).priority()).reversed()
                    .thenComparingLong(task -> ((QueuedJob) task).sequence()));
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, pending,
            Thread.ofPlatform().name("koonj-transfer").factory());

    public JobService(TransferJobRepository jobs, JobEventRepository events, FileMovementRepository movements,
                      ObservationRegistry observations, TransactionTemplate transaction, NotificationOutboxWriter notificationEvents) {
        this.jobs = jobs;
        this.events = events;
        this.movements = movements;
        this.observations = observations;
        this.transaction = transaction;
        this.notificationEvents = notificationEvents;
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths) {
        return create(operation, source, target, paths, ArchiveMode.COPY, false);
    }

    boolean hasActiveScan(UUID sourceId) {
        return jobs.existsBySourceConnectionIdAndOperationAndStatusIn(sourceId, Operation.SCAN,
                List.of(Status.PENDING, Status.RUNNING, Status.PAUSING, Status.PAUSED, Status.STOPPING));
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths,
                              ArchiveMode archiveMode, boolean deleteFromTarget) {
        return create(operation, source, target, paths, archiveMode, deleteFromTarget, BACKGROUND);
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths,
                              ArchiveMode archiveMode, boolean deleteFromTarget, int priority) {
        return create(operation, source, target, paths, archiveMode, deleteFromTarget, priority, null, null);
    }

    public TransferJob create(Operation operation, UUID source, UUID target, List<String> paths,
                              ArchiveMode archiveMode, boolean deleteFromTarget, int priority,
                              TransferPlan plan, UUID restartedFrom) {
        TransferJob job = new TransferJob();
        job.setProjectId(ProjectScope.id());
        job.setPriority(priority);
        job.setArchiveMode(archiveMode);
        job.setDeleteFromTarget(deleteFromTarget);
        job.setOperation(operation);
        job.setTriggeredBy(Objects.requireNonNullElse(JobActor.current(), "System"));
        job.setTriggeredByName(JobActor.displayName());
        job.setOwnerSubject(JobActor.subject());
        job.setOwnerEmail(JobActor.verifiedEmail());
        job.setSourceConnectionId(source);
        job.setTargetConnectionId(target);
        job.setRequestedPaths(paths);
        job.setPlan(plan);
        job.setRestartedFrom(restartedFrom);
        return transaction.execute(status -> {
            TransferJob saved = jobs.saveAndFlush(job);
            log(saved.getId(), saved.getCreatedAt(), Level.INFO, null, "Job queued");
            return saved;
        });
    }

    public TransferJob control(UUID id, Action action, boolean canPurge) {
        return transaction.execute(transactionStatus -> {
            TransferJob job = locked(id);
            ProjectScope.require(job.getProjectId(), "Transfer job was not found");
            if (!Objects.equals(job.getOwnerSubject(), JobActor.subject())) throw missingJob();
            if (job.getOperation() != Operation.SCAN && job.getPlan() == null) throw conflict("This job has no saved execution plan");
            if ((action == Action.RESUME || action == Action.RESTART) && job.getOperation() == Operation.ARCHIVE
                    && job.getArchiveMode() == ArchiveMode.PURGE && !canPurge) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Resuming or restarting a purge archive requires the purge permission");
            }
            Status previous = job.getStatus();
            if (action == Action.RESTART) {
                if (previous != Status.STOPPED || job.getRestartedAs() != null) throw conflict("This job cannot be restarted");
                TransferJob replacement = create(job.getOperation(), job.getSourceConnectionId(), job.getTargetConnectionId(),
                        job.getRequestedPaths(), job.getArchiveMode(), job.isDeleteFromTarget(), BACKGROUND, job.getPlan(), id);
                job.setRestartedAs(replacement.getId());
                log(id, Instant.now(), Level.INFO, null, "Restarted as " + replacement.getId() + " by " + actor());
                return replacement;
            }
            Status next = switch (action) {
                case PAUSE -> {
                    if (previous != Status.PENDING && previous != Status.RUNNING) throw conflict("This job cannot be paused");
                    yield previous == Status.PENDING ? Status.PAUSED : Status.PAUSING;
                }
                case RESUME -> {
                    if (previous != Status.PAUSED) throw conflict("This job is not paused");
                    yield Status.PENDING;
                }
                case STOP -> {
                    if (previous != Status.PENDING && previous != Status.RUNNING && previous != Status.PAUSED
                            && previous != Status.PAUSING) throw conflict("This job cannot be stopped");
                    yield previous == Status.RUNNING || previous == Status.PAUSING ? Status.STOPPING : Status.STOPPED;
                }
                default -> throw new IllegalStateException();
            };
            job.setStatus(next);
            Instant now = Instant.now();
            if (next == Status.STOPPED) job.setCompletedAt(now);
            if (next == Status.PAUSING || next == Status.STOPPING) interruptions.put(id, next);
            log(id, now, Level.INFO, null, switch (action) {
                case PAUSE -> (next == Status.PAUSING ? "Pause requested by " : "Paused by ") + actor();
                case RESUME -> "Resume requested by " + actor();
                case STOP -> (next == Status.STOPPING ? "Stop requested by " : "Stopped by ") + actor();
                default -> throw new IllegalStateException();
            });
            return job;
        });
    }

    public record ArchiveActivity(String currentFile, List<String> requestedPaths, List<String> currentFiles) {}

    public List<ArchiveActivity> activeArchives(UUID connectionId) {
        return jobs.findBySourceConnectionIdAndOperationAndStatusIn(connectionId, Operation.ARCHIVE,
                List.of(Status.RUNNING, Status.PAUSING, Status.STOPPING)).stream()
                .map(job -> {
                    var current = activeFiles.getOrDefault(job.getId(), Set.of()).stream().sorted().toList();
                    return new ArchiveActivity(current.isEmpty() ? null : current.getFirst(), List.copyOf(job.getRequestedPaths()), current);
                })
                .toList();
    }

    public void checkpoint(TransferJob job) {
        Status request = interruptions.get(job.getId());
        if (request == null) return;
        Status persisted = transaction.execute(status -> locked(job.getId()).getStatus());
        if (persisted == Status.PAUSING || persisted == Status.STOPPING) throw new JobInterrupted();
        interruptions.remove(job.getId(), request);
    }

    public void beginFile(TransferJob job, String path) {
        checkpoint(job);
        activeFiles.computeIfAbsent(job.getId(), ignored -> ConcurrentHashMap.newKeySet()).add(path);
        peakTransferConcurrency.accumulateAndGet(activeTransferCount(), Math::max);
    }

    public long sampleTransferConcurrency() {
        return Math.max(peakTransferConcurrency.getAndSet(0), activeTransferCount());
    }

    private long activeTransferCount() {
        return activeFiles.values().stream().mapToLong(Set::size).sum();
    }

    void endFile(TransferJob job, String path) {
        var current = activeFiles.get(job.getId());
        if (current != null) current.remove(path);
    }

    final class HandledPaths {
        private final TransferJob job;
        private final Set<UUID> history;
        private final Set<String> failedSelections;

        HandledPaths(TransferJob job, Set<UUID> history, Set<String> failedSelections) {
            this.job = job;
            this.history = history;
            this.failedSelections = failedSelections;
        }

        Set<String> sourcePaths(List<String> paths) {
            if (history.isEmpty() || paths.isEmpty()) return Set.of();
            var handled = new HashSet<>(movements.handledSourcePaths(job.getSourceConnectionId(), paths, history, job.getId()));
            if (job.getRestartedFrom() != null && !handled.isEmpty()) {
                int alreadyCounted = history.contains(job.getId())
                        ? new HashSet<>(movements.handledSourcePaths(job.getSourceConnectionId(), handled, Set.of(job.getId()), job.getId())).size()
                        : 0;
                skipped(job, handled.size() - alreadyCounted);
            }
            return handled;
        }

        boolean hasSourceFolder(String folder) {
            return !history.isEmpty() && !movements.handledSourceFolder(job.getSourceConnectionId(), prefixPattern(folder),
                    history, job.getId(), PageRequest.ofSize(1)).isEmpty();
        }

        boolean hasTargetFolder(String folder) {
            return !history.isEmpty() && !movements.handledTargetFolder(job.getTargetConnectionId(), prefixPattern(folder),
                    history, job.getId(), PageRequest.ofSize(1)).isEmpty();
        }

        Set<String> failedSelections() { return failedSelections; }
    }

    HandledPaths handledPaths(TransferJob job) {
        var history = new HashSet<UUID>();
        if (job.getProcessedFiles() > 0) history.add(job.getId());
        for (UUID previous = job.getRestartedFrom(); previous != null && history.add(previous);) {
            previous = jobs.findById(previous).map(TransferJob::getRestartedFrom).orElse(null);
        }
        return new HandledPaths(job, history, job.getFailedFiles() > 0
                ? new HashSet<>(events.failedPaths(job.getId())) : Set.of());
    }

    static String prefixPattern(String folder) {
        return folder.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static class JobInterrupted extends RuntimeException {}

    private static String actor() { return Objects.requireNonNullElse(JobActor.current(), "System"); }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private TransferJob locked(UUID id) {
        return jobs.lock(id).orElseThrow(JobService::missingJob);
    }

    private void update(TransferJob job, Consumer<TransferJob> change) {
        transaction.executeWithoutResult(status -> change.accept(locked(job.getId())));
    }

    public JobResponse get(UUID id) {
        return JobResponse.from(jobs.findByIdAndProjectIdAndOwnerSubject(id, ProjectScope.id(), JobActor.subject())
                .orElseThrow(JobService::missingJob));
    }

    public PageResponse<JobResponse> list(Pageable pageable) {
        return PageResponse.from(jobs.findByProjectIdAndOwnerSubject(ProjectScope.id(), JobActor.subject(), pageable).map(JobResponse::from));
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
        if (!jobs.existsByIdAndProjectIdAndOwnerSubject(jobId, ProjectScope.id(), JobActor.subject())) throw missingJob();
    }

    private static ResponseStatusException missingJob() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer job was not found");
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
        worker.execute(new QueuedJob(job.getPriority(), sequence.getAndIncrement(), () -> ProjectScope.run(job.getProjectId(), () -> {
            try {
                run(job.getId(), work);
                completion.complete(JobResponse.from(jobs.findByIdAndProjectId(job.getId(), job.getProjectId())
                        .orElseThrow(JobService::missingJob)));
            } catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
            }
        })));
        return completion;
    }

    // Yield only between complete files so another transfer cannot race an atomic replacement.
    void runWaitingInteractiveJobs() {
        while (pending.peek() instanceof QueuedJob next && next.priority() == INTERACTIVE && pending.remove(next)) {
            next.run();
        }
    }

    boolean hasWaitingInteractiveJob() {
        return pending.peek() instanceof QueuedJob next && next.priority() == INTERACTIVE;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    private record QueuedJob(int priority, long sequence, Runnable task) implements Runnable {
        @Override
        public void run() { task.run(); }
    }

    private void run(UUID id, JobWork work) {
        TransferJob job = transaction.execute(status -> {
            TransferJob current = locked(id);
            if (current.getStatus() != Status.PENDING) return null;
            current.setStatus(Status.RUNNING);
            current.setSkippedFiles(0);
            Instant now = Instant.now();
            boolean resumed = current.getStartedAt() != null;
            if (!resumed) current.setStartedAt(now);
            log(id, now, Level.INFO, null, resumed ? "Execution resumed" : "Execution started");
            return current;
        });
        if (job == null) return;
        Observation activity = Observation.createNotStarted("koonj.job.execution", observations)
                .lowCardinalityKeyValue("operation", job.getOperation().name());
        try {
            activity.start();
            String executionError = null;
            String executionPath = null;
            boolean interrupted = false;
            try {
                checkpoint(job);
                work.run(job);
            } catch (JobInterrupted pauseOrStop) {
                interrupted = true;
            } catch (Exception failure) {
                executionError = StorageErrors.describe(failure);
                if (failure instanceof FileSystemException filesystem) executionPath = filesystem.getFile();
            }
            String error = executionError;
            String path = executionPath;
            boolean yielded = interrupted;
            Status outcome = transaction.execute(status -> {
                TransferJob current = locked(id);
                Instant now = Instant.now();
                Status next = yielded ? (current.getStatus() == Status.STOPPING ? Status.STOPPED : Status.PAUSED)
                        : error != null ? Status.FAILED : current.getFailedFiles() == 0 ? Status.COMPLETED
                        : current.getProcessedFiles() > current.getFailedFiles() ? Status.PARTIAL_FAILURE : Status.FAILED;
                current.setStatus(next);
                if (next != Status.PAUSED) current.setCompletedAt(now);
                if (error != null) {
                    appendError(current, error);
                    log(id, now, Level.ERROR, path, error);
                }
                String result = switch (next) {
                    case COMPLETED -> "Execution completed";
                    case PARTIAL_FAILURE -> "Execution completed with failures";
                    case FAILED -> "Execution failed";
                    case PAUSED -> "Execution paused";
                    case STOPPED -> "Execution stopped";
                    default -> throw new IllegalStateException("Job has no terminal status");
                };
                interruptions.remove(id);
                activeFiles.remove(id);
                log(id, now, next == Status.FAILED || next == Status.PARTIAL_FAILURE ? Level.WARNING : Level.INFO, null, result);
                if (next == Status.COMPLETED || next == Status.PARTIAL_FAILURE || (next == Status.FAILED && error == null)) {
                    current.setScannedFileCount(job.getScannedFileCount());
                    publishNotification(NotificationEvent.Type.LARGE_BATCH_COMPLETED, current, null, null);
                }
                if (next == Status.FAILED && current.getOperation() != Operation.SCAN) {
                    publishNotification(NotificationEvent.Type.TRANSFER_FAILED, current, path,
                            error == null ? current.getDetails() : error);
                }
                return next;
            });
            activity.lowCardinalityKeyValue("outcome", outcome.name());
        } catch (RuntimeException failure) {
            activity.lowCardinalityKeyValue("outcome", "FAILED");
            throw failure;
        } finally {
            activity.stop();
        }
    }

    void publishNotification(NotificationEvent.Type type, TransferJob job, String path, String reason) {
        notificationEvents.enqueue(JobNotification.job(type, job, path, reason));
    }

    public void progress(TransferJob job, long successfulBytes, String error) {
        update(job, current -> {
            increment(current, successfulBytes, error);
            if (error != null && current.getOperation() != Operation.SCAN) {
                publishNotification(NotificationEvent.Type.TRANSFER_FAILED, current, null, error);
            }
        });
        activeFiles.remove(job.getId());
    }

    void planTransferBatch(TransferJob job, int files, long bytes) {
        update(job, current -> {
            current.setTotalFiles(current.getTotalFiles() + files);
            current.setTotalBytes(current.getTotalBytes() + bytes);
        });
    }

    void skipped(TransferJob job, int count) {
        if (count > 0) update(job, current -> current.setSkippedFiles(current.getSkippedFiles() + count));
    }

    void initializeTransferTotals(TransferJob job) {
        update(job, current -> {
            current.setTotalFiles(current.getProcessedFiles());
            current.setTotalBytes(current.getTransferredBytes());
            log(job.getId(), Instant.now(), Level.INFO, null, "Discovering files in batches");
        });
    }

    void transferProgress(TransferJob job, int processed, long bytes, long byteAdjustment, List<String> errors, String failedPath, String failureReason) {
        update(job, current -> {
            current.setTotalBytes(current.getTotalBytes() + byteAdjustment);
            current.setProcessedFiles(current.getProcessedFiles() + processed);
            current.setTransferredBytes(current.getTransferredBytes() + bytes);
            current.setFailedFiles(current.getFailedFiles() + errors.size());
            errors.forEach(error -> appendError(current, error));
            if (!errors.isEmpty()) publishNotification(NotificationEvent.Type.TRANSFER_FAILED, current, failedPath, failureReason);
        });
    }

    private void increment(TransferJob current, long successfulBytes, String error) {
        current.setProcessedFiles(current.getProcessedFiles() + 1);
        current.setTransferredBytes(current.getTransferredBytes() + successfulBytes);
        if (error != null) {
            current.setFailedFiles(current.getFailedFiles() + 1);
            appendError(current, error);
        }
    }

    public void totals(TransferJob job, int count, long bytes) {
        update(job, current -> {
            current.setTotalFiles(count);
            current.setTotalBytes(bytes);
            String items = job.getOperation() == Operation.SCAN ? " catalog entries" : " transfer items";
            log(job.getId(), Instant.now(), Level.INFO, null, "Discovered " + count + items + " (" + bytes + " bytes)");
        });
    }

    public void selectionFailed(TransferJob job, String path, String reason) {
        update(job, current -> {
            increment(current, 0, path + ": " + reason);
            log(job.getId(), Instant.now(), Level.ERROR, path, reason);
            if (current.getOperation() != Operation.SCAN) {
                publishNotification(NotificationEvent.Type.TRANSFER_FAILED, current, path, reason);
            }
        });
    }

    public void scanned(TransferJob job, int entries, int files) {
        update(job, current -> current.setProcessedFiles(entries));
        job.setScannedFileCount(files);
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
