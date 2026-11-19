# Transfer Observability

## Purpose

Expose durable activity outcomes and current catalog and transfer metrics for monitoring and recovery.

## ADDED Requirements

### Requirement: Dashboard summary
`GET /api/v1/dashboard/summary` SHALL return total cataloged files, reclaimed local bytes, archived object totals by storage class, active transfer throughput, and recent system notifications and alerts.

#### Scenario: A transfer is retried
- **WHEN** an archive or purge completion is reported again
- **THEN** the archived and reclaimed totals reflect the effect once

#### Scenario: No recent data movement
- **WHEN** workers are idle or waiting for cold restore
- **THEN** throughput falls to zero after the reporting window

### Requirement: Paginated activity history
`GET /api/v1/activities` SHALL return paginated user-triggered activities with batch counts, status, and timestamps. Each activity SHALL link to bounded per-file job detail and error history.

#### Scenario: Expand a large activity
- **WHEN** a client opens an activity with many file outcomes
- **THEN** it can page through per-file statuses and error logs independently of the activity feed

### Requirement: Live progress stream
`GET /api/v1/events/stream` SHALL provide metadata-only SSE events for running activity progress, terminal status changes, and worker throughput with bounded subscriber buffering.

#### Scenario: The client reconnects
- **WHEN** a client reconnects after missing events
- **THEN** it can refresh authoritative state through REST and continue receiving live updates

#### Scenario: A subscriber stalls
- **WHEN** a subscriber consumes updates slower than they are produced
- **THEN** the stream coalesces updates or disconnects it without delaying worker progress
