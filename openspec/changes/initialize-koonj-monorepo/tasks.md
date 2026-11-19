# Tasks

## 1. Backend foundation

- [ ] 1.1 Configure the root and `koonj-server` Maven reactors with all four modules, Java 21, and pinned Spring Boot 3.4+ and AWS SDK v2 dependencies. Verify `mvn verify` builds the reactor on Java 21.
- [ ] 1.2 Create separate API and worker entry points with virtual threads enabled. Verify both start independently and the worker stays alive while polling.
- [ ] 1.3 Add Flyway baseline SQL for roots, file generations, scans, operations, leases, and indexes. Verify clean migration and repeat validation on PostgreSQL 16 and 18.
- [ ] 1.4 Add lifecycle enums, transition rules, and Spring Data JDBC repositories in `koonj-core`. Test invalid transitions, concurrent claims, expired leases, and stale claim tokens against both database majors.
- [ ] 1.5 Implement off-heap streaming SHA-256 in `koonj-core`. Verify known digests, empty files, and a file larger than the test JVM heap. Inspect the selected hashing implementation for heap copies.

## 2. Control plane

- [ ] 2.1 Implement registered-root validation, bounded scan admission, and `Files.walkFileTree` metadata batching. Test slow persistence, unreadable subtrees, symlink handling, and scan restart.
- [ ] 2.2 Implement metadata-only scan and operation commands, status reads, and indexed keyset inventory pagination. Document request and cursor behavior and test cursor continuation, invalid cursors, page caps, and payload rejection.
- [ ] 2.3 Implement bounded SSE subscriptions and reconnect via persisted status. Test a stalled client, subscriber limits, and refresh after disconnect.

## 3. Data plane

- [ ] 3.1 Implement permit-before-claim scheduling, lease renewal, retry backoff, and bounded staging reservations. Test queue saturation, lease loss, crash recovery, and capacity reclamation.
- [ ] 3.2 Configure the AWS CRT S3 client and path-based `S3TransferManager` transfers. Verify endpoint configuration, multipart transfers, storage and native memory budgets, and completion or cleanup on cancellation.
- [ ] 3.3 Implement generation-bound snapshots, remote digest verification, and archive/tier transitions. Test changed sources, unsupported mount semantics, corrupt objects, and crashes around quarantine and deletion.
- [ ] 3.4 Implement recall to verified temporary files with no-clobber publication. Test destination conflicts, interrupted downloads, digest mismatches, and retries after publication. Document filesystem guarantees required for tiering and recall.

## 4. Operator console

- [ ] 4.1 Replace the UI archetype with React 19, TypeScript, Vite, Tailwind CSS, and the three TanStack libraries. Verify a clean dependency install and production build.
- [ ] 4.2 Implement cursor fetching, page retention, and virtualized inventory rows. Test forward and reverse scrolling across page eviction and confirm bounds on rendered rows and retained pages.
- [ ] 4.3 Add scan, archive, tier, and recall controls with SSE updates and status refresh on reconnect. Verify operation submission and recovery after a simulated disconnect.

## 5. Compose and end-to-end verification

- [ ] 5.1 Add API, worker, and UI images and complete the five-service `deploy/docker-compose.yml` with PostgreSQL 16, a fresh named volume, LocalStack S3 bucket setup, health gating, and configured mounts. Document the startup command and verify a fresh stack reaches readiness.
- [ ] 5.2 From the Compose UI, scan, archive, tier, and recall a fixture through LocalStack. Verify the recalled SHA-256 and inspect REST traffic for metadata-only requests and responses. Exercise multipart and checksum behavior against Amazon S3 with the CRT path.
- [ ] 5.3 Run a multi-million-entry scan and a concurrent transfer workload with a file larger than the configured JVM heap. Slow PostgreSQL and S3 independently. Record entries and bytes per second, peak heap and RSS, staging use, cursor and SSE responsiveness, and observed queue depths. Verify the configured bounds hold and processing resumes as capacity returns.
