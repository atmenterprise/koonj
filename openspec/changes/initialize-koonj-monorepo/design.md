# Design

## Context

The repository contains a Java 21 Maven root, a backend POM stub, a UI archetype, and a Compose file with PostgreSQL 18 and LocalStack. See [proposal.md](proposal.md) for the target workspace and [file lifecycle](specs/file-lifecycle/spec.md) for the payload boundary.

## Goals / Non-Goals

The first deployment exercises scan, browse, archive, tier, and recall end to end, with independent API and worker processes. Policy scheduling, cloud deployment automation, and cold S3 storage-class restore orchestration are outside this initialization. Tiering here means moving a verified file generation off its source filesystem while retaining its inventory record and online S3 copy.

## Decisions

### Build and service boundaries

Keep the root Maven aggregator and the `uk.appsthatmatter` group. Make `koonj-server` a Maven parent/aggregator with these children.

| Module | Responsibility |
| --- | --- |
| `koonj-core` | Shared domain records and lifecycle enums, legal transition rules, streaming SHA-256 utility, and Spring Data JDBC repositories with explicit SQL for keyset and claim queries |
| `koonj-api` | Spring Boot control-plane REST, metadata-only file visitor, scan orchestration, SSE, and operation submission |
| `koonj-worker` | Spring Boot worker process with durable PostgreSQL claims, filesystem I/O, and AWS SDK v2 S3 Transfer Manager |
| `koonj-migrations` | Versioned Flyway SQL for inventory, operation state, and supporting indexes |

Use Java 21 and a pinned Spring Boot release at or above 3.4. Enable `spring.threads.virtual.enabled` in both applications and use explicitly bounded admission for background work. Keep the worker process alive while its polling loop runs. Give S3 permissions to workers and mount scan roots read-only in the API. Use node tooling directly under `koonj-ui` and remove the archetype module from the Maven reactor. Independent builds let workers scale without rebuilding the frontend or running an HTTP server for transfers.

### Incremental metadata and progress

Use `Files.walkFileTree` with bounded depth and concurrent scan count. Visit regular files without following symbolic links. Resolve paths from registered root IDs and validate mount confinement. Upsert metadata in fixed-size transactions from the visitor. With synchronous flushes, database latency pauses traversal and the visitor retains only its current batch. Record scan errors and progress incrementally, leaving unseen entries intact on partial scans.

Use an indexed, immutable sort tuple such as `(root_id, file_id)` within each root. Cap page sizes and scope opaque cursors to the query and sort order. Define continuation over live data and verify completeness for a stable result set. Persist current progress in PostgreSQL and have the SSE broadcaster periodically read changed summaries. Bound subscriber count, pending events, and write time. Coalesce progress for active subscriptions and close stalled subscriptions. On reconnect, the UI refreshes durable status through REST.

### Claims, transfers, and integrity

Model file generations and operations separately. Scope each operation to a file generation and serialize competing operations for the same file. Use short PostgreSQL claim transactions with `FOR UPDATE SKIP LOCKED`, renewable leases, and claim tokens guarding updates. Acquire a worker `Semaphore` permit before claiming each job and hold it until its transfer, verification, and cleanup settle. Use bounded claim retries with backoff. Durable work remains in PostgreSQL across process restarts, which avoids introducing a separate broker for the baseline.

Build one shared explicitly configured CRT S3 async client per worker process, with a native memory budget, connection cap, and multipart settings, and inject it into AWS SDK v2 `S3TransferManager`. Use path-based `uploadFile` and `downloadFile`, generation-specific object identities, and bounded disk staging. Select the registered target region for each transfer. Drain active transfers before replacing the shared client on a region change to preserve the aggregate native memory budget. Validate limits against the pinned SDK and CRT on the supported container architectures. [AWS Transfer Manager guide](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/transfer-manager.html) · [AWS CRT memory guidance](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/crt-based-s3-client-direct-buffer-pool.html)

Compute a full-file SHA-256 through a streaming native digest implementation that reads directly from disk or fixed-size off-heap buffers. Verify that the chosen implementation avoids heap copies of file payloads. Hash an immutable, disk-backed upload snapshot and verify the resulting object using full-object SHA-256 when available, or stream the exact stored object into bounded staging and hash it there. Reserve staging capacity before claiming payload work and release it on cleanup. Store only metadata and digests in PostgreSQL. Object sizes, staging space, aggregate memory limits, and concurrency must be independently configurable.

Archive retains the local copy. Tier only after remote verification, under exclusive control of the source generation. A managed root is dedicated to Koonj and grants exclusive write ownership to its workers. Verify cross-process file locking and atomic rename on the actual mount. All Koonj mutations of a managed file must acquire the same stable lock and revalidate the generation and database claim before a side effect. For roots where external writers cannot be coordinated, allow archival and recall but reject destructive tiering. Use a recoverable same-filesystem quarantine step and reconcile its state on retry before removal. For recall, download to a unique temporary path on the destination filesystem, verify the digest, then use atomic no-clobber publication where supported. Fail safely when the mount lacks required guarantees. Reconcile existing objects, staged files, and final paths before retrying side effects after a lease expires.

### UI and local runtime

Use React 19, TypeScript, Vite, and Tailwind CSS. Use TanStack Query for bounded cursor pages and operation status, TanStack Table for metadata columns, and TanStack Virtual for row rendering. Preserve scroll position when pages are evicted and refetch evicted pages as needed. Coalesce live updates to avoid invalidating the entire inventory on every transfer.

Compose runs `postgres`, `localstack`, `koonj-api`, `koonj-worker`, and `koonj-ui`. Compose uses `postgres:18-alpine` and PostgreSQL 18 is the deployment target. Run migration and repository tests against `postgres:18-alpine`. The API owns local migration startup. Gate the worker on schema readiness and the local S3 bucket on LocalStack readiness. Supply mount roots and environment-specific S3 configuration, including the LocalStack endpoint and addressing mode. Keep the same CRT file-transfer path in integration tests and exercise real S3 separately.

## Risks / Trade-offs

- Disk snapshots, remote re-reads for checksum verification, and staging are extra I/O. Enforce disk quotas and backpressure and measure throughput before increasing worker concurrency.
- SMB and NAS vary in path, rename, and locking semantics. Validate managed roots before enabling destructive tiering or final recall publication.
- Virtual threads can increase pressure on JDBC, filesystem, and native S3 resources. Bound each separately and measure heap and process RSS under sustained workload.
- LocalStack can differ from Amazon S3 on multipart and checksum behavior. Run a separate AWS smoke test for those paths.

## Migration Plan

Bootstrap a fresh named Compose volume mounted at `/var/lib/postgresql`, where the PostgreSQL 18 image stores its version-specific data directory. Preserve any existing Compose data volumes. Run Flyway before serving requests or claiming work. For PostgreSQL 18 deployments, apply the same baseline to a fresh database and verify health before starting workers. To roll back an initial deployment, stop the services and preserve the database, S3 objects, and filesystem state for recovery.
