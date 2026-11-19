# Operator Console

## Purpose

Give operators a responsive interface to browse inventories and follow file operations.

## ADDED Requirements

### Requirement: Bounded inventory rendering

The console SHALL fetch inventory incrementally and virtualize rendered rows with bounded page retention.

#### Scenario: Browse a large root

- **WHEN** an operator scrolls through a root with millions of files
- **THEN** the console requests cursor pages on demand and renders only the viewport and limited overscan

### Requirement: Operation controls and status

The console SHALL let operators start scans, request archive, tier, and recall operations, and follow their persisted status.

#### Scenario: Connection resumes

- **WHEN** the progress stream reconnects after an interruption
- **THEN** the console refreshes current status and resumes live updates
