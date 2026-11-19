# File Lifecycle

## Purpose

Archive and tier file generations to S3 and recall verified copies onto configured filesystem mounts.

## ADDED Requirements

### Requirement: Worker-owned payload movement

Workers SHALL stream file payloads between filesystem mounts and S3 within configured resource limits. REST SHALL carry metadata and commands only. File payloads SHALL stay outside JVM heap buffers.

#### Scenario: A file exceeds the worker heap size

- **WHEN** a worker archives and recalls a file larger than its configured heap
- **THEN** the transfer completes within the configured resource budgets and the API handles only metadata

#### Scenario: Worker capacity is exhausted

- **WHEN** all transfer slots are occupied
- **THEN** excess work remains durable and waiting without accumulating in process memory

### Requirement: Verified archival and tiering

The system SHALL record a durable verified S3 copy for each successfully archived generation and retain its local copy during archival. Tiering SHALL remove a local copy only after verifying its matching remote generation and confirming that the local generation is unchanged.

#### Scenario: Source changes during archival

- **WHEN** the source changes before verification completes
- **THEN** the original operation does not mark the new generation as archived or remove the changed local file

### Requirement: Verified recall

The system SHALL verify recalled bytes against the archived SHA-256 before publishing the file to its destination and SHALL preserve any conflicting destination.

#### Scenario: Interrupted or corrupt download

- **WHEN** a recall download fails or its digest differs
- **THEN** the partial file remains unpublished and the operation reports a retryable failure or a terminal error

### Requirement: Durable operation recovery

The system SHALL persist operation status and recover abandoned work without repeating externally visible destructive effects.

#### Scenario: Worker exits before recording completion

- **WHEN** another worker claims expired work
- **THEN** it reconciles durable and storage-side state before taking further action
