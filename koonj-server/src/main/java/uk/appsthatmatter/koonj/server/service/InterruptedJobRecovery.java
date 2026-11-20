package uk.appsthatmatter.koonj.server.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.appsthatmatter.koonj.server.entity.StorageState;
import uk.appsthatmatter.koonj.server.entity.TransferJob.Status;
import uk.appsthatmatter.koonj.server.repository.FileMetadataRepository;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;

import java.time.Instant;
import java.util.List;

@Component
public class InterruptedJobRecovery implements ApplicationRunner {
    private final TransferJobRepository jobs;
    private final FileMetadataRepository files;

    public InterruptedJobRecovery(TransferJobRepository jobs, FileMetadataRepository files) {
        this.jobs = jobs;
        this.files = files;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String reason = "Interrupted by server restart. Resubmit to retry.";
        jobs.finishInterrupted(Status.FAILED, Instant.now(), reason, List.of(Status.PENDING, Status.RUNNING));
        files.failInterrupted(StorageState.FAILED, reason, List.of(StorageState.QUEUED_ARCHIVE,
                StorageState.ARCHIVING, StorageState.QUEUED_RECALL, StorageState.RECALLING));
    }
}
