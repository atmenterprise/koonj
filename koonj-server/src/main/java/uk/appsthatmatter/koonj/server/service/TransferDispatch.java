package uk.appsthatmatter.koonj.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uk.appsthatmatter.koonj.server.entity.TransferJob;
import uk.appsthatmatter.koonj.server.repository.TransferJobRepository;
import uk.appsthatmatter.koonj.server.repository.TransferReservationRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static uk.appsthatmatter.koonj.server.entity.TransferJob.Status.PENDING;

@Service
@ConditionalOnProperty(name = "koonj.transfer.rabbit.enabled", havingValue = "true")
final class TransferDispatch {
    private final TransferJobRepository jobs;
    private final TransferReservationRepository reservations;
    private final TransferTransactionLocks locks;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobService execution;
    private final int tenantJobs;
    private final int tenantInteractiveJobs;
    private final int backendJobs;

    TransferDispatch(TransferJobRepository jobs, TransferReservationRepository reservations, TransferTransactionLocks locks,
                     JdbcTemplate jdbc, TransactionTemplate tx, JobService execution,
                     @Value("${koonj.transfer.rabbit.tenant-jobs:2}") int tenantJobs,
                     @Value("${koonj.transfer.rabbit.tenant-interactive-jobs:1}") int tenantInteractiveJobs,
                     @Value("${koonj.transfer.rabbit.backend-jobs:8}") int backendJobs) {
        if (!locks.postgres()) throw new IllegalStateException("Distributed transfers require PostgreSQL");
        this.jobs = jobs;
        this.reservations = reservations;
        this.locks = locks;
        this.jdbc = jdbc;
        this.tx = tx;
        this.execution = execution;
        this.tenantJobs = Math.max(1, tenantJobs);
        this.tenantInteractiveJobs = Math.max(1, tenantInteractiveJobs);
        this.backendJobs = Math.max(2, backendJobs);
    }

    List<UUID> publishable() {
        // Bound each project's share of the broker backlog as well as its active attempts.
        return jdbc.query("""
                with occupied as (
                    select project_id, priority >= 100 as interactive, count(*) as slots
                    from transfer_jobs
                    where worker_attempt is not null or
                          (status = 'PENDING' and dispatch_after is null and dispatch_generation is not null)
                    group by project_id, priority >= 100
                ), ready as (
                    select id, project_id, priority >= 100 as interactive, priority, dispatch_after,
                        row_number() over (partition by project_id, (priority >= 100)
                                           order by dispatch_after, created_at, id) as turn
                    from transfer_jobs
                    where status = 'PENDING' and dispatch_after <= current_timestamp and dispatch_generation is not null
                )
                select ready.id from ready left join occupied using (project_id, interactive)
                where ready.turn + coalesce(occupied.slots, 0) <= case when ready.interactive then ? else ? end
                order by ready.turn, ready.priority desc, ready.dispatch_after, ready.id limit 32
                """, (row, number) -> row.getObject("id", UUID.class), tenantInteractiveJobs + 1, tenantJobs + 1);
    }

    Envelope reservePublication(UUID id) {
        return tx.execute(status -> {
            var job = jobs.lock(id).orElse(null);
            if (job == null || job.getStatus() != PENDING || job.getDispatchAfter() == null
                    || job.getDispatchAfter().isAfter(Instant.now())) return null;
            Instant deadline = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);
            job.setDispatchAfter(deadline);
            return new Envelope(id, job.getDispatchGeneration(), job.getDispatchPool(),
                    job.getPriority() >= JobService.INTERACTIVE, deadline);
        });
    }

    void published(Envelope envelope) {
        tx.executeWithoutResult(status -> jobs.lock(envelope.job()).ifPresent(job -> {
            if (job.getStatus() == PENDING && envelope.generation().equals(job.getDispatchGeneration())
                    && envelope.deadline().equals(job.getDispatchAfter())) {
                job.setDispatchAfter(null);
            }
        }));
    }

    TransferJob claim(UUID id, UUID generation, String pool, boolean interactive, UUID instance) {
        return tx.execute(status -> {
            var job = jobs.lock(id).orElse(null);
            if (job == null || job.getStatus() != PENDING || !generation.equals(job.getDispatchGeneration())
                    || !Objects.equals(pool, job.getDispatchPool()) || interactive != (job.getPriority() >= JobService.INTERACTIVE)) {
                return null;
            }
            var namespaces = reservations.namespaces(id);
            if (namespaces.isEmpty()) throw new IllegalStateException("Pending job lacks storage reservations: " + id);
            var coordination = new ArrayList<>(namespaces);
            coordination.add("transfer-capacity:project:" + job.getProjectId());
            locks.acquire(coordination);
            long tenantActive = interactive
                    ? jobs.countByProjectIdAndWorkerAttemptIsNotNullAndPriorityGreaterThanEqual(job.getProjectId(), JobService.INTERACTIVE)
                    : jobs.countByProjectIdAndWorkerAttemptIsNotNullAndPriorityLessThan(job.getProjectId(), JobService.INTERACTIVE);
            int backendLimit = interactive ? backendJobs : backendJobs - 1;
            if (tenantActive >= (interactive ? tenantInteractiveJobs : tenantJobs)
                    || namespaces.stream().anyMatch(namespace -> reservations.runningIn(namespace) >= backendLimit)) {
                job.setDispatchGeneration(UUID.randomUUID());
                job.setDispatchAfter(Instant.now().plusSeconds(2));
                return null;
            }
            job.setDispatchAfter(null);
            job.setWorkerAttempt(UUID.randomUUID());
            job.setWorkerInstance(instance);
            job.setWorkerHeartbeat(Instant.now());
            execution.started(job);
            return job;
        });
    }

    record Envelope(UUID job, UUID generation, String pool, boolean interactive, Instant deadline) {}
}
