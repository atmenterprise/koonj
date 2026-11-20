# Proposal

## Why

Koonj, by Apps That Matter (appsthatmatter.uk), needs a foundation for archiving, tiering, and recalling millions of files between local, SMB, or NAS storage and Amazon S3. Separating control requests from file transfers lets each plane scale within its own resource limits.

## What Changes

- Turn the existing Maven placeholders into a Java 21 and Spring Boot 3.4+ backend with virtual threads and four modules: `koonj-core`, `koonj-api`, `koonj-worker`, and `koonj-migrations`.
- Add incremental directory discovery, cursor-based inventory browsing, SSE progress, and worker-owned S3 archival and recall. Apply bounded backpressure from scan through transfer. File payloads never traverse REST or accumulate in JVM heap.
- Replace the UI archetype with React 19, TypeScript, Vite, Tailwind CSS, TanStack Query, TanStack Table, and TanStack Virtual.
- Complete the five-service local Compose stack with `postgres:18-alpine` and LocalStack S3. Verify the Flyway baseline on PostgreSQL 18.

## Capabilities

### New Capabilities

- `file-inventory` provides incremental discovery, metadata queries, and progress updates.
- `file-lifecycle` manages archival, tiering, and recall with bounded worker transfers.
- `operator-console` provides responsive inventory browsing and operation monitoring.

### Modified Capabilities

## Impact

The root Maven reactor and backend POM are existing scaffolding. The UI is currently a Maven archetype placeholder and Compose currently defines PostgreSQL 18 and LocalStack only. This change introduces metadata APIs, a PostgreSQL schema, worker access to the configured filesystem mounts and S3, and app containers for API, worker, and UI. The local PostgreSQL 18 stack uses a fresh named volume.
