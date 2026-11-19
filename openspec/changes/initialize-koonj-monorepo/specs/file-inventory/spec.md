# File Inventory

## Purpose

Discover files on registered storage roots and expose metadata and progress for large inventories.

## ADDED Requirements

### Requirement: Incremental directory discovery

The control plane SHALL incrementally persist file metadata from registered local or mounted SMB/NAS roots using bounded scan concurrency and bounded batches.

#### Scenario: Scan outpaces persistence

- **WHEN** traversal encounters millions of entries and persistence slows
- **THEN** traversal waits for batch capacity and previously committed metadata remains queryable

#### Scenario: Scan cannot read a subtree

- **WHEN** a subtree is inaccessible
- **THEN** the scan reports the failure without treating its unseen entries as deleted

### Requirement: Cursor-based inventory

The API SHALL return metadata pages with a capped page size and an opaque continuation cursor over a deterministic order.

#### Scenario: Continue through tied sort values

- **WHEN** records share a sort value and a client follows the cursor over an unchanged result set
- **THEN** each matching record is returned once across pages

### Requirement: Live operation progress

The API SHALL publish scan and transfer progress through SSE with bounded subscriber capacity and a metadata refresh path after disconnect.

#### Scenario: Slow event consumer

- **WHEN** a consumer stops reading
- **THEN** the API coalesces or disconnects the consumer without blocking operation processing, and the client can recover current status through REST
