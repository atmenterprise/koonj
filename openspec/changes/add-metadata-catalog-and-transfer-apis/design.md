# Design

## Context

The monorepo initialization change defines bounded scans, durable worker claims, and verification before publication or local removal. Extend those services and repositories in place. The current repository contains backend scaffolding, so implementation follows the foundation change.

## Goals / Non-Goals

Deliver the catalog, durable queue, and versioned backend contracts. Location registration, UI changes, and new public purge and scan routes are outside this change. Existing scan and tier controls use the same activity accounting.

## Decisions

### PostgreSQL catalog and execution model

| Table | Columns and constraints |
| --- | --- |
| `storage_locations` | UUID `id`, `type` (`LOCAL`, `SMB`, `NAS`, `S3`), `root_path` or `bucket_name` according to type, `mount_status`, nullable nonnegative bigint `total_capacity_bytes` and `used_bytes`. S3 uses access status. |
| `file_metadata` | UUID `id`, FK `location_id`, `parent_path`, `full_path`, `name`, `is_directory`, bigint `size_bytes`, `mime_type`, `checksum_sha256`, `storage_state`, `s3_bucket`, `s3_key`, `s3_storage_class`, `last_modified_at`, `archived_at`, JSONB object `custom_tags`. Store nullable values for unknown or inapplicable metadata. Preserve an immutable generation reference for execution and an explicit local-presence indicator. |
| `user_activities` | UUID, actor, action, status, created/start/end timestamps, total/completed/failed file counts, successful bytes transferred, and durable selection and expansion state. |
| `transfer_jobs` | UUID, activity FK, file/generation reference, action, job status, class/tier options, scheduled time, claim token, lease, attempt count, progress, result/error, and timestamps. Append bounded execution records in `transfer_job_attempts` keyed by job and attempt number. Scan coordination may use a location-scoped job. |

Constrain file states to `LOCAL_ONLY`, `QUEUED_ARCHIVE`, `ARCHIVING`, `ARCHIVED`, `PURGED_LOCAL`, `QUEUED_RECALL`, `RECALLING`, and `FAILED`. Directory state is null. Successful archive or recall with an intact remote copy yields `ARCHIVED`. A confirmed local purge yields `PURGED_LOCAL`. Keep confirmed local presence and archive identity independent of the latest operation state so metrics and recovery work even after failure. Map `STANDARD`, `GLACIER_IR`, `GLACIER`, and `DEEP_ARCHIVE` explicitly to provider values.

Uniqueness is scoped by location, path identity and directory discriminator. Preserve exact S3 object keys, even when key segments resemble filesystem traversal. Treat filesystem paths and S3 prefix identities according to their respective location rules. Record S3 metadata in bounded listing pages and model navigable prefixes independently from objects. Link managed archive objects to their originating generation so index refreshes and metrics remain consistent.

### REST contracts

| Endpoint | Contract |
| --- | --- |
| `GET /api/v1/explorer/tree` | Requires `locationId` and `parentPath` (empty for root). Returns direct child directories in name order. |
| `GET /api/v1/explorer/items` | Same scope, plus optional case-insensitive name search `q`, `storageState`, `sort` (`name`, `sizeBytes`, `lastModifiedAt`), and `direction`. Defaults to ascending name. |
| `POST /api/v1/actions/archive` | JSON `{ids, storageClass}`. Uses the configured archive bucket and defaults class to `STANDARD`. |
| `POST /api/v1/actions/recall` | JSON `{ids, restoreTier}`. Uses the registered original destination and defaults tier to `STANDARD`. An entry with no eligible destination fails per file. |
| `GET /api/v1/activities` | Newest first by creation time and ID, with activity type, timestamps, status, counts and links. |
| `GET /api/v1/activities/{activityId}/jobs` | Per-file state, attempts, last error code/message and links to paginated `/{jobId}/attempts` beneath the jobs collection. |
| `GET /api/v1/dashboard/summary` | Cataloged non-directory count, cumulative bytes successfully reclaimed, managed archive object count and bytes by class, recent transfer bytes per second, and bounded recent alerts. Includes an as-of timestamp. |
| `GET /api/v1/events/stream` | SSE `activity.progress`, `activity.finished`, and `worker.throughput` events with timestamps and activity revisions or aggregate sample windows. |

Listings return `{items, nextCursor}` and cap `limit`. Bind opaque cursors to the query, scope, sort direction, null ordering, and last sort value plus UUID. Support the supported sort shapes with indexes beginning with `(location_id, parent_path)`, use an indexed search strategy for case-insensitive matching, and use keyset predicates without `OFFSET`. Cursors describe a live view. Check scope and resource access before returning any records.

For actions, validate bounded requests and authorized IDs before returning HTTP 202 with `activityId` and a status link (`GET /api/v1/activities/{activityId}`). Use HTTP 400 for malformed values, 404 for missing or inaccessible IDs, and 409 for inconsistent retries using the same optional idempotency key. Bind the key to actor, action, and normalized request.

### Batch expansion, claims, and restore

Persist validated seeds and an expansion checkpoint with the activity. Expand currently cataloged descendants incrementally in stable key order, with segment-aware ancestry and restartable cursor writes in the same transaction as inserts. Pin the generation observed at expansion time and deduplicate by activity and file generation. A later generation change makes the job stale instead of redirecting it. Keep selection semantics explicit for a catalog changing during expansion. An empty successful expansion completes with zero file jobs.

Use short `FOR UPDATE SKIP LOCKED` claim transactions with runnable and expired-lease indexes. Claim after admission and commit before filesystem or S3 calls. Serialize incompatible work per file across activities, issue a new token on reclaim and condition state changes and accounting on that token. Reconcile external effects on retry. Finish the activity only when expansion and outstanding work settle. All successes yield `COMPLETED`, mixed outcomes yield `PARTIAL_FAILURE`, and all failures or an expansion failure with no successful work yield `FAILED`. Count scan files by durable traversal results. Record each file result once. [PostgreSQL locking clauses](https://www.postgresql.org/docs/18/sql-select.html)

Instant-access classes go directly to verified download. For Glacier Flexible Retrieval and Deep Archive, request restore with a configured retention period, then persist a waiting phase and next check time and release worker capacity. Poll restore metadata with bounded backoff and reconcile previously issued requests. Support `EXPEDITED`, `STANDARD`, and `BULK` on Flexible Retrieval and Standard or Bulk on Deep Archive. Ignore the tier for instant-access jobs in mixed batches. Verify that upload integrity for cold objects is supported for the selected SDK, size, and checksum path. If verification requires a full read, keep the archive pending until the exact stored object can be read and verified. Local purge remains gated on verification. [AWS restore API](https://docs.aws.amazon.com/AmazonS3/latest/API/API_RestoreObject.html) · [AWS retrieval options](https://docs.aws.amazon.com/AmazonS3/latest/userguide/restoring-objects-retrieval-options.html)

### Metrics and delivery

Track archive totals from distinct verified archive identities and reclaimed bytes from durable confirmed purge effects keyed by generation and purge operation. Measure active throughput from bounded interval samples of actual bytes moved, including in-flight work. Batch counter writes and precompute dashboard aggregates with a freshness timestamp to avoid scanning the catalog per request. Keep notification feeds and per-attempt messages bounded and free of credentials. Use the existing bounded SSE broadcaster with heartbeats, coalescing and write deadlines. Refresh REST on reconnect and merge only newer activity revisions.

## Risks / Trade-offs

- Live browsing and expansion can see concurrent catalog changes. Stable keysets, pinned file generations, and explicit retryable conflicts limit accidental work.
- Busy batches can contend on aggregate counters. Batch progress publication and verify counter reconciliation under concurrent completion.
- S3 emulators can differ in restore and checksum behavior. Use deterministic simulation for queue phases and AWS integration coverage for provider behavior.

## Migration Plan

Add forward Flyway migrations after the foundation. Reuse and backfill existing root, inventory, and operation rows, preserving IDs and timestamps. Validate both fresh installs and upgrades on PostgreSQL 16 and 18. Deploy schema first, then compatible API and workers. Preserve queue and catalog data if application rollout is reversed.
