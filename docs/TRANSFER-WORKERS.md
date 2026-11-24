# Archive and recall workers

Koonj uses PostgreSQL for job status, file and folder reservations, and pending dispatch. RabbitMQ carries job IDs and dispatch generations to independently scalable transfer workers. Workers load the saved job plan and use the existing transfer, checksum, catalog, and audit logic.

## What users see

- Archive and recall can run at the same time on disjoint storage paths, including within one project or registered connection. The job must fit the worker and storage capacity limits.
- Once accepted, a queued or running job reserves the selected paths on both storage endpoints. A folder reserves its descendants. An overlapping submission returns HTTP 409 without exposing the other job. A project-authorized FUSE caller can wait on an existing interactive recall for the same catalog file.
- Mounted folder recall resolves catalog aliases during admission and verifies each source and destination against the reservation before transferring it. A root selection can reserve an entire storage location. Catalog scans reserve their scanned tree against transfers while different connections can be scanned independently.
- Pause and stop allow active files to finish. Paused and stopped jobs release their reservations after writes finish. Resume and retry reacquire paths and can return 409 if another job reserved them in the meantime.
- Distributed interactive recall returns the provider's estimate when S3 is restoring a cold object. The same job retains its paths and polls again through RabbitMQ without occupying a worker slot while waiting. Bulk recall retains its existing per-file result for a cold object and can be retried later.

## Enable distributed transfers

1. For an upgrade, stop accepting work on the old release, let pending and running work finish, and stop all old instances. Keep paused jobs for users to resume through the new version. If a prior instance was interrupted, reconcile storage and those jobs before switching modes. For an existing database using `KOONJ_DDL_AUTO=validate`, apply `deploy/migrations/transfer-dispatch.sql` before starting the new release. With `update`, start one server to update the schema before adding replicas.
2. Enable `KOONJ_TRANSFER_RABBIT_ENABLED=true` in every API and worker process sharing the database. Production Docker Compose starts a separate `koonj-transfer-worker` by default. A standalone installation runs the same server JAR with `KOONJ_TRANSFER_WORKER_ENABLED=false` on API replicas and `KOONJ_TRANSFER_RELAY_ENABLED=false` on worker instances. Leave a relay enabled on at least one API. Keep worker HTTP ports on a private network.
3. Give transfers and notifications separate RabbitMQ credentials and virtual hosts in a SaaS deployment. Set `KOONJ_TRANSFER_RABBIT_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_VHOST`, and `_SSL` as needed. The transfer settings fall back to the shared broker settings. On deployment, the worker declares the queues for its pool. Use a replicated RabbitMQ cluster for quorum queue failover and a highly available PostgreSQL instance.
4. Give API admission processes and eligible workers access to configured mounts at the registered paths. Use identical namespace, alias, case, and routing configuration across replicas. Scale one service at a time and inspect queue age, worker throughput, and S3 and filesystem utilization.

Embedded execution is available for single-process deployments with `KOONJ_TRANSFER_RABBIT_ENABLED=false`. Its job concurrency defaults to one and can be raised with `KOONJ_TRANSFER_EMBEDDED_JOBS`. Drain the transfer workers and resolve stalled attempts before changing modes. Run all processes connected to a given database in the same mode.

## Storage identity and routing

The reservation namespace is scoped to the physical storage. Koonj identifies S3 storage by endpoint and bucket, and mounted paths by their resolved root and relative path inside the configured mounted domain. Nested roots under the same domain can therefore overlap. Case-insensitive mounted-path comparisons default to enabled. Set `KOONJ_TRANSFER_CASE_INSENSITIVE_MOUNTS=false` only if every mount in the domain is case-sensitive.

Use `KOONJ_TRANSFER_STORAGE_ALIASES` to register alternate access paths that resolve to the same underlying data, such as bind mounts or a second hostname for an S3 gateway. Specify semicolon-separated pairs and use one canonical identity per physical location.

```properties
KOONJ_TRANSFER_STORAGE_ALIASES="mount:/srv/media-alias=mount:/mnt/media;s3:s3-alias.internal:443/archive=s3:s3.internal:443/archive"
KOONJ_TRANSFER_MOUNT_POOLS="/mnt/media=media;/mnt/projects=projects"
```

Mounted routes match the longest canonical mount prefix. Run a worker with `KOONJ_TRANSFER_WORKER_POOL=media` for the first route and with `KOONJ_TRANSFER_WORKER_POOL=projects` for the second. Unmatched routes and S3-only scans use the `default` pool. For each pool, register only paths its workers can access. Physical changes to mounts or endpoint alias configuration should be made after affected jobs drain.

| Setting | Default | Scope |
| --- | --- | --- |
| `KOONJ_TRANSFER_BACKGROUND_WORKERS` | 2 | Concurrent bulk jobs per worker instance |
| `KOONJ_TRANSFER_INTERACTIVE_WORKERS` | 1 | Concurrent FUSE recalls per worker instance |
| `KOONJ_TRANSFER_TENANT_JOBS` | 2 | Active bulk jobs per project across workers |
| `KOONJ_TRANSFER_TENANT_INTERACTIVE_JOBS` | 1 | Active FUSE recalls per project across workers |
| `KOONJ_TRANSFER_BACKEND_JOBS` | 8 | Active jobs per bucket or mounted domain across workers. One slot is reserved for interactive work. |
| `KOONJ_TRANSFER_MAX_PARALLEL_FILES` | 24 | Concurrent files per transfer job |
| `KOONJ_TRANSFER_IN_FLIGHT_MIB` | 512 | In-flight file size budget per transfer job |

The backend job cap bounds aggregate file concurrency and byte exposure through the per-job limits. Configure identical cluster-wide limits on workers. The relay batches dispatch across projects. A job that cannot obtain capacity is rescheduled briefly so other projects can proceed.

## Failure and recovery

A queued job is stored with its reservation before the API returns. The relay resumes publication after an unconfirmed publish. Each worker consumer keeps its delivery unacknowledged while it executes the claimed job. With prefetch set to one, RabbitMQ can deliver other jobs to free consumers. Deferred and stale deliveries are acknowledged after their database decisions. Repeated deliveries with the same generation cannot start another attempt. Set the broker's delivery acknowledgement timeout above your expected longest job to avoid unnecessary channel closure and redelivery. A confirmed dispatch is backed by RabbitMQ persistence. After a full broker data loss, rearm only unclaimed pending jobs by setting their `dispatch_after` to the current timestamp in PostgreSQL once replacement queues exist.

Watch `koonj.transfer.jobs.pending`, `koonj.transfer.jobs.oldest.pending.seconds`, `koonj.transfer.attempts.stalled`, and the pool's two RabbitMQ queues and invalid queue. Oldest pending age excludes interactive recall jobs that are already waiting on cold storage. If job age rises while worker slots remain free, check queue routing and endpoint limits.

A stale heartbeat keeps the reservation because a disconnected worker may still be writing. Distributed startup leaves other workers' attempts alone. To release a stalled job:

1. Identify its `worker_instance`, `worker_attempt`, and `worker_heartbeat` in `transfer_jobs`. Confirm that the identified process has exited or that its storage access has been revoked. Check the job events, file movement records, staged S3 objects, destination content, and mounted links. Reconcile storage and the catalog before releasing the reservation if a copy, link replacement, or deletion was interrupted.
2. With the job and attempt IDs copied from that review, run the following in `psql` as a database operator. The attempt and heartbeat conditions prevent this action from reclaiming a different or recently live attempt.

```sql
-- psql variables job_id and attempt_id are the UUIDs reviewed above.
WITH stopped AS (
    UPDATE transfer_jobs
    SET status = 'FAILED', completed_at = current_timestamp,
        details = 'Stalled worker stopped and storage reviewed by an operator.',
        worker_attempt = NULL, worker_instance = NULL, worker_heartbeat = NULL,
        dispatch_after = NULL, active_paths = NULL
    WHERE id = :'job_id'::uuid AND worker_attempt = :'attempt_id'::uuid
      AND worker_heartbeat < current_timestamp - interval '90 seconds'
    RETURNING id, details
), released AS (
    DELETE FROM transfer_reservations WHERE job_id IN (SELECT id FROM stopped)
)
INSERT INTO job_events (job_id, recorded_at, level, message)
SELECT id, current_timestamp, 'ERROR', details FROM stopped;
```

3. Retry from Activity after checking the failed job. Automatic takeover requires a storage layer that can fence the former attempt. The mounted filesystem and S3 connectors do not currently provide that guarantee.

For maintenance, let active jobs finish before removing workers. An instance terminated mid-transfer can require the same recovery procedure.

## Integration test

The normal server suite runs against H2. The broker integration test is opt-in. Point `KOONJ_TRANSFER_IT_POSTGRES_URL` at a disposable PostgreSQL database and set `KOONJ_TRANSFER_IT_USERNAME`, `KOONJ_TRANSFER_IT_PASSWORD`, `KOONJ_TRANSFER_IT_RABBIT_PORT`, and the matching `KOONJ_TRANSFER_IT_RABBIT_USERNAME`, `KOONJ_TRANSFER_IT_RABBIT_PASSWORD`, and `KOONJ_TRANSFER_IT_RABBIT_VHOST` for a disposable local RabbitMQ broker. Run `mvn -pl koonj-server -am test`.
