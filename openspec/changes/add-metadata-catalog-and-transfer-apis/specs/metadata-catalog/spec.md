# Metadata Catalog

## Purpose

Maintain a searchable inventory of files and directories across registered filesystem and S3 locations.

## ADDED Requirements

### Requirement: Registered locations

The catalog SHALL record each location's type (`LOCAL`, `SMB`, `NAS`, or `S3`), root path or bucket, mount or access status, total capacity, and used bytes. Unknown measurements SHALL be represented as unknown.

#### Scenario: S3 bucket has no reported capacity
- **WHEN** a registered S3 bucket has no capacity measurement
- **THEN** its total capacity is unknown and any available usage measurement remains independently visible

### Requirement: Catalog entry metadata

Each entry SHALL record a UUID, location, parent path, full path, name, directory flag, size, MIME type, SHA-256, storage state, S3 bucket, key and storage class, modification and archive timestamps, and structured custom tags. Inapplicable or unavailable values SHALL be representable.

#### Scenario: Existing metadata is refreshed
- **WHEN** a scan observes an existing path again
- **THEN** its catalog UUID and custom tags survive and observed metadata is updated without reusing a checksum for changed bytes

#### Scenario: An S3 prefix and object share a name
- **WHEN** a bucket contains both an object and a navigable prefix of the same name
- **THEN** the catalog exposes distinct entries and preserves the original object key

### Requirement: Direct-child tree navigation

`GET /api/v1/explorer/tree` SHALL return only direct child directories or bucket prefixes within the specified `locationId` and `parentPath`, with bounded pages.

#### Scenario: A folder has nested descendants
- **WHEN** a client expands a folder with grandchildren
- **THEN** the response lists only its immediate child directories and offers a continuation when capped

### Requirement: Explorer item pagination

`GET /api/v1/explorer/items` SHALL list direct child files and folders for `locationId` and `parentPath`, with name search, storage-state filtering, allowlisted sorting, and capped keyset pagination.

#### Scenario: Sort values tie
- **WHEN** a client follows successive cursors under the same query over unchanged results
- **THEN** each entry appears once in deterministic order

#### Scenario: A cursor is reused for another query
- **WHEN** a client changes the location, parent, filter, or sort associated with a cursor
- **THEN** the API rejects the cursor with a client error

### Requirement: Administrator-managed S3 archive targets

The API SHALL let administrators register a bucket name and AWS region, using a configurable OIDC role claim. Each new archive command SHALL identify an authorized registered target. The resulting job SHALL retain the selected bucket location through execution and retries.

#### Scenario: An administrator registers a regional bucket

- **WHEN** an authenticated administrator registers a valid bucket and region
- **THEN** the API returns a stable location ID that can be granted to operators

#### Scenario: An operator attempts bucket registration

- **WHEN** a caller lacks the configured administrator role
- **THEN** registration returns HTTP 403 and creates no location

#### Scenario: Upgrade contains key-only archive records

- **WHEN** an administrator explicitly confirms one registered bucket and region for the upgraded database
- **THEN** the API binds unmapped legacy archives to that location, records the administrator and timestamp, and rejects a conflicting confirmation
