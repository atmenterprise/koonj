# Proposal

## Why

Operators need a durable view of files across mounted storage and S3, a reliable way to submit bulk actions, and progress they can recover after a disconnect. These contracts let the API and workers handle millions of files without moving payloads through REST or building unbounded in-memory queues.

## What Changes

- Define the PostgreSQL catalog for registered locations, files, directories, and S3 object metadata.
- Define durable user activities, per-file jobs and execution logs, bounded selection expansion, and safe claims across workers.
- Specify versioned REST contracts for the explorer, archive and recall, activity history, and dashboard. Specify SSE for live activity progress and throughput.
- Support storage-class selection and asynchronous Glacier restore as part of recall.

## Capabilities

### New Capabilities

- `metadata-catalog` defines persisted locations and catalog entries, including the hierarchical explorer API.
- `transfer-job-queue` defines action submission, batch accounting, durable execution, and cold recall coordination.
- `transfer-observability` defines dashboard metrics, activity history and per-file logs, and live updates.

### Modified Capabilities

## Impact

Builds on the in-progress `initialize-koonj-monorepo` change and its file inventory and lifecycle contracts. Apply that foundation first and extend its schema and repositories in place. This change affects `koonj-migrations`, `koonj-core`, `koonj-api`, and the worker integration contract. PostgreSQL 18 is the deployment target and PostgreSQL 16 remains the local Compose baseline.
