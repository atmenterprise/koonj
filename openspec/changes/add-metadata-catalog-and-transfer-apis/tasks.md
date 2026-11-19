# Tasks

## 1. Schema and catalog

- [ ] 1.1 Extend the foundation with Flyway migrations for `storage_locations`, `file_metadata`, `user_activities`, `transfer_jobs`, and attempt history. Verify clean installs and upgrades on PostgreSQL 16 and 18, including preservation of existing identities, data, and constraints.
- [ ] 1.2 Implement shared JDBC catalog repositories and incremental filesystem and S3 metadata upserts. Test partial scans, object/prefix collisions, raw S3 keys, stable UUIDs, and changed generations with retained tags.
- [ ] 1.3 Implement and document explorer tree and item queries with authorization, search, state filter, and indexed keysets. Test tied and null sort values, sort directions, invalid scope/cursors, and query plans on large folders without OFFSET.

## 2. Submission and execution

- [ ] 2.1 Implement and document archive and recall request validation, idempotent submission, HTTP 202 responses, and activity status links. Test repeated and conflicting keys, malformed requests, unauthorized IDs, and input limits.
- [ ] 2.2 Implement checkpointed folder expansion and file-generation pinning. Test overlapping selections, path-segment boundaries, empty folders, changed sources, and restart between committed expansion batches.
- [ ] 2.3 Implement bounded worker admission, claim queries with `FOR UPDATE SKIP LOCKED`, leases, and attempt logs. Verify parallel workers, lease expiry, stale-token rejection, and reconciliation around side effects.
- [ ] 2.4 Integrate archive, recall, existing purge and scan operations with activity accounting. Verify terminal counts, all status outcomes, timestamps, byte totals, and exactly-once accounting under retries and concurrent completion.
- [ ] 2.5 Implement archive class mapping and cold restore scheduling, readiness polling, and verified worker download. Verify class/tier combinations, release of permits during restore waits, restart recovery, expiry, and archive verification before local purge. Cover provider behavior with an AWS integration run.

## 3. History, dashboard, and events

- [ ] 3.1 Implement and document paginated activity, activity-detail, job and attempt endpoints. Test history ordering, access scope, and bounded per-file errors for a large batch.
- [ ] 3.2 Implement dashboard aggregates from confirmed catalog and purge effects, throughput samples, and system alerts. Test archive totals by class, retry deduplication, idle decay, and bounded recent alerts.
- [ ] 3.3 Extend the foundation SSE broadcaster with activity revisions, terminal updates, heartbeats, and throughput. Document event payloads and test slow subscribers, missing events, reconnect via REST, and terminal activity recovery.

## 4. Integration

- [ ] 4.1 Exercise the complete flow from catalog queries through bulk archive and recall, activity history, dashboard and SSE on the local stack. Verify metadata-only REST and event payloads.
- [ ] 4.2 Run the explorer and large-folder expansion against a multi-million-entry catalog while workers claim and finish jobs. Capture query plans, queue and subscriber bounds, and memory use during database slowdown. Verify cursor correctness for unchanged results and recovery after a worker restart.
