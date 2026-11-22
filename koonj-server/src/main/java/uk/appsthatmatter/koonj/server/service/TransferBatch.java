package uk.appsthatmatter.koonj.server.service;

import uk.appsthatmatter.koonj.server.entity.TransferJob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One producer enumerates storage while a bounded window of file transfers runs concurrently. */
final class TransferBatch implements AutoCloseable {
    private static final int DISCOVERY_BATCH = 64;
    private static final int PROGRESS_BATCH = 16;
    private static final long MIN_FILE_WEIGHT = 1024L * 1024;
    private final List<Work> discovered = new ArrayList<>(DISCOVERY_BATCH);
    private final List<String> errors = new ArrayList<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorCompletionService<Result> completed = new ExecutorCompletionService<>(executor);
    private final JobService jobs;
    private final TransferJob job;
    private final JobService.HandledPaths handled;
    private final int maxFiles;
    private final long byteBudget;
    private int active;
    private long activeBytes;
    private int processed;
    private long transferred;
    private long byteAdjustment;

    TransferBatch(JobService jobs, TransferJob job, int maxFiles, long byteBudget, JobService.HandledPaths handled) {
        this.jobs = jobs;
        this.job = job;
        this.handled = handled;
        this.maxFiles = maxFiles;
        this.byteBudget = byteBudget;
    }

    void add(String path, long size, Transfer transfer) {
        jobs.checkpoint(job);
        discovered.add(new Work(path, size, transfer));
        if (discovered.size() == DISCOVERY_BATCH) submitDiscovered();
    }

    void selectionFailed(String path, String reason) {
        jobs.checkpoint(job);
        jobs.planTransferBatch(job, 1, 0);
        jobs.selectionFailed(job, path, reason);
    }

    void submitDiscovered() {
        if (discovered.isEmpty()) return;
        var completedPaths = handled.sourcePaths(discovered.stream().map(Work::path).toList());
        discovered.removeIf(file -> completedPaths.contains(file.path()));
        if (discovered.isEmpty()) return;
        jobs.planTransferBatch(job, discovered.size(), discovered.stream().mapToLong(Work::size).sum());
        for (var file : discovered) {
            jobs.checkpoint(job);
            if (jobs.hasWaitingInteractiveJob()) {
                drain();
                jobs.checkpoint(job);
                jobs.runWaitingInteractiveJobs();
            }
            long weight = Math.min(byteBudget, Math.max(MIN_FILE_WEIGHT, file.size()));
            while (active >= maxFiles || activeBytes + weight > byteBudget) {
                reap();
                jobs.checkpoint(job);
            }
            jobs.beginFile(job, file.path());
            completed.submit(() -> {
                try {
                    return new Result(file.path(), weight, file.transfer().run(), file.size(), null);
                } catch (IOException | RuntimeException failure) {
                    return new Result(file.path(), weight, 0, file.size(), StorageErrors.describe(failure));
                }
            });
            active++;
            activeBytes += weight;
        }
        discovered.clear();
    }

    private void reap() {
        try {
            Result result = completed.take().get();
            active--;
            activeBytes -= result.weight();
            jobs.endFile(job, result.path());
            processed++;
            if (result.error() == null) {
                transferred += result.bytes();
                byteAdjustment += result.bytes() - result.estimatedBytes();
            } else errors.add(result.path() + ": " + result.error());
            if (processed == PROGRESS_BATCH || active == 0) saveProgress();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for file transfers", failure);
        } catch (ExecutionException failure) {
            active--;
            throw new IllegalStateException("File transfer worker failed", failure.getCause());
        }
    }

    private void saveProgress() {
        if (processed == 0) return;
        jobs.transferProgress(job, processed, transferred, byteAdjustment, errors);
        processed = 0;
        transferred = 0;
        byteAdjustment = 0;
        errors.clear();
    }

    private void drain() {
        while (active > 0) reap();
        saveProgress();
    }

    @Override
    public void close() {
        // Pause and stop leave the accumulated but unsubmitted files for the next execution.
        try { drain(); } finally { executor.close(); }
    }

    @FunctionalInterface
    interface Transfer {
        long run() throws IOException;
    }

    private record Work(String path, long size, Transfer transfer) {}

    private record Result(String path, long weight, long bytes, long estimatedBytes, String error) {}
}
