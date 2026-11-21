package uk.appsthatmatter.koonj.server.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public InterruptedJobRecovery(TransferJobRepository jobs, FileMetadataRepository files, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.files = files;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        updateLegacyStorageStateConstraint();
        String reason = "Interrupted by server restart. Resubmit to retry.";
        jobs.finishInterrupted(Status.FAILED, Instant.now(), reason, List.of(Status.PENDING, Status.RUNNING));
        files.failInterrupted(StorageState.FAILED, reason, List.of(StorageState.QUEUED_ARCHIVE,
                StorageState.ARCHIVING, StorageState.QUEUED_RECALL, StorageState.RECALLING));
    }

    private void updateLegacyStorageStateConstraint() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return null;
            try (var statement = connection.createStatement()) {
                // Hibernate's schema update does not expand an existing enum CHECK.
                statement.execute("""
                        DO $$
                        DECLARE legacy record;
                        BEGIN
                            FOR legacy IN
                                SELECT conname FROM pg_constraint
                                WHERE conrelid = 'file_metadata'::regclass AND contype = 'c'
                                    AND pg_get_constraintdef(oid) LIKE '%storage_state%PURGED_LOCAL%'
                                    AND pg_get_constraintdef(oid) NOT LIKE '%ARCHIVED_COPY%'
                            LOOP
                                EXECUTE format('ALTER TABLE file_metadata DROP CONSTRAINT %I', legacy.conname);
                                EXECUTE format('ALTER TABLE file_metadata ADD CONSTRAINT %I CHECK
                                    (storage_state IN (''LOCAL_ONLY'', ''QUEUED_ARCHIVE'', ''ARCHIVING'',
                                    ''ARCHIVED'', ''ARCHIVED_COPY'', ''ARCHIVED_SYMLINK'', ''PURGED_LOCAL'',
                                    ''QUEUED_RECALL'', ''RECALLING'', ''FAILED''))', legacy.conname);
                            END LOOP;
                        END $$;
                        """);
            }
            return null;
        });
    }
}
