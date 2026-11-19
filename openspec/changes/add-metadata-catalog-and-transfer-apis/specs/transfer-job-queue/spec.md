# Transfer Job Queue

## Purpose

Track single and bulk actions and coordinate recoverable worker execution for each selected file.

## ADDED Requirements

### Requirement: Archive and recall submission
`POST /api/v1/actions/archive` and `POST /api/v1/actions/recall` SHALL accept a bounded array of file or folder UUIDs and return a tracked activity ID with HTTP 202 after recording the request. Archive SHALL accept a target S3 storage class and recall SHALL accept a restore tier for cold objects.

#### Scenario: Overlapping folder and file IDs
- **WHEN** a file is selected directly and through one or more selected folders
- **THEN** the activity produces at most one job for that file generation

#### Scenario: Invalid selection
- **WHEN** a request contains an unknown or inaccessible ID or an invalid storage class or tier value
- **THEN** the API rejects the request without creating the activity

### Requirement: Durable bounded expansion
The system SHALL expand folder selections in bounded, resumable batches and bind each file job to its catalog generation.

#### Scenario: Expansion is interrupted
- **WHEN** expansion of a large subtree stops after some batches commit
- **THEN** expansion resumes without duplicating jobs and the activity stays nonterminal until expansion and execution settle

### Requirement: Activity accounting
The queue SHALL track `ARCHIVE`, `RECALL`, `PURGE_LOCAL`, and `SCAN` activities, timestamps, total, completed and failed file counts, and bytes transferred. Activity status SHALL use `PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL_FAILURE`, or `FAILED`.

#### Scenario: Some jobs fail permanently
- **WHEN** expansion is complete and all jobs terminate with both successful and failed outcomes
- **THEN** the activity is `PARTIAL_FAILURE` and each terminal result contributes to its counts once

### Requirement: Recoverable per-file execution
The queue SHALL persist job attempts and error logs. Concurrent workers SHALL claim available jobs without waiting for claims already being made by another worker. Expired claims SHALL be recoverable and stale workers SHALL be fenced from recording outcomes.

#### Scenario: Worker disappears mid-transfer
- **WHEN** its lease expires and another worker takes the job
- **THEN** the new worker can reconcile earlier effects, and a stale completion cannot advance the file state or double-count the batch

### Requirement: Cold object recall
Recall SHALL wait durably for retrieval readiness when the storage class requires restore, then download and verify through the worker. Pending restores SHALL release transfer capacity.

#### Scenario: Mixed storage classes
- **WHEN** a recall selects instant-access and cold objects
- **THEN** instant-access jobs proceed directly and cold jobs use the requested supported tier or default Standard

#### Scenario: Unsupported tier for an expanded file
- **WHEN** an Expedited recall reaches a Deep Archive object
- **THEN** that per-file job reports a validation failure without sending a restore request
